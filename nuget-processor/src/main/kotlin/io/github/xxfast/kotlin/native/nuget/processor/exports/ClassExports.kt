package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_TO_CSHARP_PARAM
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.isKotlinBuiltinPackage
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.LegacyRefusedInterfaceBridgePair
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinSpelling
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedStoredCallbackPair
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardLegacyAsyncRoute
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedCallbackMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedInterfaceBridgePair
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardOwnerTypeName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardMemberOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOptInRefused
import io.github.xxfast.kotlin.native.nuget.processor.forward.optInMarker
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativePrefix

/**
 * ADR-064 amendment (2026-09-13): the legacy class Flow/StateFlow route's own selection gate,
 * hoisted so the route below and the planner's unrouted-position reclassification cannot drift.
 * Deliberately *only* the return-type test: the ADR-114/ADR-123 refusal filters that follow it in
 * [addClassExports] have their own named diagnostic (`warnRefusedLegacyRouteMembers`), so folding
 * them in here would double-report the same member.
 */
internal fun KSFunctionDeclaration.hasLegacyFlowReturn(): Boolean {
  val returnQualified: String? = returnType?.resolve()
    ?.expandAliases()?.declaration?.qualifiedName?.asString()
  return returnQualified == "kotlinx.coroutines.flow.Flow" || returnQualified in STATE_FLOW_TYPES
}

/**
 * ADR-064 amendment (2026-09-13): the legacy per-call lambda-parameter route's own gate (the
 * `allNonFlowMethods.partition` below), hoisted for the same reason as [hasLegacyFlowReturn]. A
 * lambda carried by a collection *element* (`List<(Int) -> Unit>`) is not a lambda parameter and
 * this says so.
 */
internal fun KSFunctionDeclaration.hasLegacyLambdaParameter(): Boolean = parameters.any { param ->
  param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
}

/**
 * ADR-160: the per-call lambda-parameter members the ADR-062 plan now owns, which is what retires
 * the hand-written route for them on BOTH halves (`addLambdaParamMethodExport` here,
 * `translateCallbackMethod` in the C# translator). One predicate, read by every selector, so the
 * two halves cannot disagree about which route a member is on -- disagreement here is a duplicate
 * `@CName` (ADR-117) or a member one half declares and the other does not (the ADR-055 contract).
 *
 * True only when EVERY lambda parameter classifies as a [BridgeType.Callback]: a member carrying a
 * shape the plan's callback lowering does not implement (a `Char` payload, an object or enum lambda
 * return, a suspend lambda) keeps the legacy route exactly as it was. A stored-callback or
 * interface-bridge PAIR is excluded by its own selector upstream of this, not here: pair detection
 * is structural and must keep seeing both halves.
 */
internal fun KSFunctionDeclaration.hasPlannedCallbackParameter(
  classifier: ForwardBridgeTypeClassifier,
): Boolean = hasLegacyLambdaParameter() && parameters.all { param ->
  val type: KSType = param.type.resolve().expandAliases()
  type.declaration.qualifiedName?.asString() !in LAMBDA_TYPES ||
      classifier.classify(type) is BridgeType.Callback
}

/**
 * Why the hand-written callback routes cannot carry one of this member's lambda parameters, or null
 * when every lambda crosses. Named on both halves by `warnRefusedLegacyRouteMembers`.
 *
 * Boundary nullability part A2: a lambda PARAMETER whose own payload or return type is nullable.
 *
 * ADR-160's plan route never sees this shape: `classify` wraps a nullable component in
 * [BridgeType.Nullable], which is neither an admitted callback payload nor an admitted callback
 * result, so `callbackType` declines and the member falls through to the HAND-WRITTEN per-call
 * (ADR-036/102) and stored (ADR-037) routes. Those two decide "by value or by handle" from the
 * payload's simple name and never read its nullability, so a nullable payload is not a degraded
 * binding there: it is a broken build or an uncatchable crash, measured per shape.
 *  - `(Int?) -> Unit`: Kotlin `cbFn.invoke(it0, cbUserData)` against
 *    `CFunction<(Int, COpaquePointer) -> Unit>` -- "actual type is 'Int?', but 'Int' was expected",
 *    so the author's `packNuget` fails in GENERATED code with no diagnostic first.
 *  - `(Cat?) -> Unit`: `NugetHandles.retain(it0)` against `retain(value: Any)` -- same abort.
 *  - `(String?) -> Unit`: compiles, then `retain(it0 as Any)` throws an uncaught
 *    `NullPointerException` inside a `@CName` export with no error slot, which terminates the host
 *    process; no C# `catch` can see it.
 *  - `(Int) -> String?`: compiles, then the generated `cbFn.invoke(...)!!` NPEs when a C# callback
 *    legitimately returns null.
 *  - the STORED route is stricter still: its bridge lambda is declared with the nullability
 *    stripped, so even the reference payload fails the generated-Kotlin compile.
 * So the named skip only ever replaces a broken build or a process death.
 *
 * Deliberately NOT the lambda's own nullability (`listener: ((Int) -> Unit)?`): that payload has a
 * wire, the crossing is fine, and the only thing Kotlin can express that C# cannot is "no
 * listener", which a C# caller expresses by not calling the method. That shape keeps binding on the
 * hand-written route too -- `classify` wraps it as `Nullable(Callback)`, which
 * [hasPlannedCallbackParameter] declines, so the legacy selector (which keys on the expanded
 * declaration name only) carries it exactly as it carries the non-null spelling -- and the
 * generated wrapper rejects a null delegate with `ArgumentNullException` instead.
 *
 * Reads the UNEXPANDED argument types. Since the ADR-018 amendment `expandAliases()` carries a
 * use-site `?` too (`Name?` expands to `String?`), so either spelling reads a `Name?` payload as
 * nullable.
 *
 * Two more shapes neither hand-written route can carry, refused at the same five sites for the same
 * reason (both halves, class and sealed arm, per-call and stored, before the partition so a
 * refused stored pair loses both halves together):
 *  - a PAYLOAD that is a Kotlin builtin but not a scalar (`List`, `Set`, `Map`, `Any`, `Pair`, an
 *    array, `Duration`, anything under `kotlin`/`kotlinx` that [KOTLIN_TO_CSHARP_PARAM] does not
 *    key). The stored route spells it through `qualifiedElementCsType`, whose ADR-123 check aborted
 *    the whole KSP round; the per-call route spelled the bare simple name (`Action<List>`), which
 *    no C# `using` resolves (CS0246). The test is exactly the condition that check fires on, so the
 *    gate cannot drift from the abort it prevents. A denylist on purpose: ADR-160's
 *    `isCallbackPayload` would also drop `Char`, sealed-base and value-class payloads, which these
 *    routes carry today.
 *  - a lambda RESULT outside `Unit`, the primitives and `String`: the per-call route reads every
 *    other result back as a `String` box on both halves, so `() -> Cat` did not compile.
 */
internal fun KSFunctionDeclaration.refusedLegacyLambdaShape(): LegacyRefusedInterfaceBridgePair? =
  parameters.firstNotNullOfOrNull { param ->
    val expanded: KSType = param.type.resolve().expandAliases()
    val expandedName: String? = expanded.declaration.qualifiedName?.asString()
    if (expandedName !in LAMBDA_TYPES) return@firstNotNullOfOrNull null
    val paramName: String = param.name?.asString() ?: "_"
    // Every argument, the last of which is the lambda's RETURN type: both positions are refused,
    // and they are dropped by separate lines of the route's own selector, so neither is redundant.
    val arguments: List<KSType> = expanded.arguments
      .mapNotNull { argument -> argument.type?.resolve() }
    val nullable: KSType? = arguments.firstOrNull { argument -> argument.isMarkedNullable }
    if (nullable != null) {
      return@firstNotNullOfOrNull LegacyRefusedInterfaceBridgePair(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
        reason = "$CALLBACK_CARRIES$paramName: a lambda carrying the nullable type " +
            "${nullable.declaration.simpleName.asString()}?",
        hint = "make the lambda's own parameter and return types non-null (a C# delegate slot " +
            "has no way to say \"absent\" for a by-value payload, and the handle " +
            "payloads have no null arm on this route); if the absent case matters, pass it " +
            "as a separate flag parameter, or use a sentinel value the callback can " +
            "recognise. the lambda's OWN type may still be nullable (`listener: ((Int) -> " +
            "Unit)?`): that binds, and C# must pass a non-null delegate",
      )
    }
    val spelled: String = "`$paramName: ${expanded.kotlinSpelling()}`"
    val builtin: KSType? = arguments.dropLast(1)
      .map { argument -> argument.expandAliases() }
      .firstOrNull { argument -> argument.isBuiltinNonScalar() }
    if (builtin != null) {
      return@firstNotNullOfOrNull LegacyRefusedInterfaceBridgePair(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
        reason = "$CALLBACK_CARRIES$spelled, whose payload `${builtin.kotlinSpelling()}` is a " +
            "Kotlin builtin with no crossing on a callback",
        hint = "a collection, `Any`, a `Pair`, an array or another Kotlin builtin has no " +
            "crossing on a callback: pass the values one per call, or wrap them in an exported " +
            "class and pass that",
      )
    }
    val result: KSType = arguments.lastOrNull()?.expandAliases() ?: return@firstNotNullOfOrNull null
    if (result.declaration.qualifiedName?.asString() in LEGACY_LAMBDA_RESULTS) {
      return@firstNotNullOfOrNull null
    }
    LegacyRefusedInterfaceBridgePair(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
      reason = "a callback can return `Unit`, a primitive or a `String`, but $spelled returns " +
          "`${result.kotlinSpelling()}`",
      hint = "return one of those from the lambda; hand anything else back through a method on " +
          "the class instead",
    )
  }

private const val CALLBACK_CARRIES: String =
  "a callback parameter can carry a primitive/String/Char, a class handle or an enum, but not "

/** ADR-160's callback result set (`isCallbackResult`), by qualified name. */
private val LEGACY_LAMBDA_RESULTS: Set<String> = setOf(
  "kotlin.Unit", "kotlin.String", "kotlin.Boolean",
  "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
  "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
  "kotlin.Float", "kotlin.Double",
)

/** A type under `kotlin`/`kotlinx` that is not one of the scalars [KOTLIN_TO_CSHARP_PARAM] keys. */
private fun KSType.isBuiltinNonScalar(): Boolean {
  val declaration = declaration as? KSClassDeclaration ?: return false
  if (!declaration.packageName.asString().isKotlinBuiltinPackage()) return false
  return declaration.simpleName.asString() !in KOTLIN_TO_CSHARP_PARAM
}

/**
 * ADR-147: whether this member belongs to ANY specialized legacy route rather than to the ADR-062
 * plan. Every one of them spells the receiver as the bare owner name, so a generic owner's member
 * is refused on both halves through this one predicate instead of three parallel tests.
 *
 * A per-call lambda member the plan owns (ADR-160, [hasPlannedCallbackParameter]) is NOT legacy:
 * its export comes off the catalog with the `Crate<Any?>` receiver. An `add`/`remove` pair half
 * (stored callback or interface-bridge subscription) IS legacy, even when its lambda would
 * classify or it has no lambda at all, which is why the caller passes the owner's
 * [pairMembers]: pair detection is structural and the plan never sees a pair.
 */
internal fun KSFunctionDeclaration.isForwardLegacyRoute(
  classifier: ForwardBridgeTypeClassifier,
  pairMembers: Set<KSFunctionDeclaration>,
): Boolean = isForwardLegacyAsyncRoute() ||
    this in pairMembers ||
    (hasLegacyLambdaParameter() && !hasPlannedCallbackParameter(classifier))

/**
 * The `add`/`remove` pair halves among [methods] for [isForwardLegacyRoute], detected as
 * [addClassExports] detects them: a stored-callback pair over the lambda members, a subscription
 * pair over the rest.
 */
internal fun forwardLegacyPairMembers(
  methods: List<KSFunctionDeclaration>,
): Set<KSFunctionDeclaration> {
  val (lambdaMembers, otherMembers) = methods.partition { it.hasLegacyLambdaParameter() }
  return (findStoredCallbackPairs(lambdaMembers) + findInterfaceBridgePairs(otherMembers))
    .flatMap { pair -> pair.toList() }
    .toSet()
}

/**
 * The members of an ordinary class that the hand-written routes export, after every refusal: what
 * [addClassExports] emits, and what the planner reads to learn the entry points those routes take
 * (a same-package extension of the same name must not spell one, see `ForwardSymbolTable`). One
 * selector, so the two cannot disagree about which member exports.
 */
internal data class ForwardClassLegacyMembers(
  val flowMethods: List<KSFunctionDeclaration>,
  val storedCallbackPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>>,
  val perCallLambdaMethods: List<KSFunctionDeclaration>,
  val interfaceBridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>>,
) {
  /**
   * The `<prefix>_<name>` entry points the three callback routes export. Unnumbered: none of them
   * applies an overload suffix (an overloaded listener pair is refused upstream).
   */
  fun callbackExportNames(prefix: String): Set<String> =
    (storedCallbackPairs.flatMap { pair -> pair.toList() } + perCallLambdaMethods +
      interfaceBridgePairs.flatMap { pair -> pair.toList() })
      .map { method -> "${prefix}_${method.simpleName.asString()}" }
      .toSet()
}

internal fun KSClassDeclaration.forwardClassLegacyMembers(
  classifier: ForwardBridgeTypeClassifier,
  superClass: KSClassDeclaration?,
): ForwardClassLegacyMembers {
  val cls: KSClassDeclaration = this
  val memberMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { method -> !method.isCompilerOwnedMember(cls) }
    .filter { !it.modifiers.contains(Modifier.SUSPEND) }
    .filter { method ->
      method.isForwardMemberOf(cls, superClass) && !method.modifiers.contains(Modifier.ABSTRACT)
    }
    .toList()
  val legacyPairMembers: Set<KSFunctionDeclaration> = forwardLegacyPairMembers(memberMethods)
  // ADR-147: every specialized legacy route spells the receiver as the bare owner name
  // (`asStableRef<Crate>()`), which does not compile for a generic class. Refused on a generic
  // owner, on BOTH halves (the same predicate the C# translator reads), rather than emitting a
  // member one half declares and the other does not (the ADR-055 contract would then fail the
  // whole build). The planner names each refused member (`nameGenericOwnerLegacyRoutes`).
  val allRegularMethods: List<KSFunctionDeclaration> = memberMethods.filter { method ->
    cls.typeParameters.isEmpty() || !method.isForwardLegacyRoute(classifier, legacyPairMembers)
  }

  // ADR-065: StateFlow-returning methods route through the same `_collect` shape as plain-Flow
  // methods, plus a sibling synchronous `_value` export (see the flowMethods.forEach loop).
  val flowMethods: List<KSFunctionDeclaration> = allRegularMethods
    .filter { method -> method.hasLegacyFlowReturn() }
    // ADR-114: a generic parameter this route cannot marshal skips the member entirely rather
    // than emitting non-compiling Kotlin. `NugetProcessor` names it in a SKIPPED_UNSUPPORTED_INPUT.
    // ADR-123: likewise an element this route cannot marshal, named SKIPPED_UNSUPPORTED_RETURN.
    .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
    .filter { method -> classifier.legacyRefusedReturn(method) == null }

  val allNonFlowMethods: List<KSFunctionDeclaration> = allRegularMethods
    .filterNot { method -> method.hasLegacyFlowReturn() }
    // Boundary nullability part A2: refused BEFORE the partition, so a nullable- or builtin-payload
    // lambda member reaches neither the per-call route nor the stored pair detection (a pair whose
    // halves both vanish is never found, so `removeRinger` cannot survive as a cancel for a
    // subscription nobody can make) nor the ordinary `methods` list.
    // `warnRefusedLegacyRouteMembers` names it.
    .filterNot { method -> method.refusedLegacyLambdaShape() != null }

  val (lambdaParamMethods, methods) = allNonFlowMethods.partition { method ->
    method.hasLegacyLambdaParameter()
  }

  val detectedStoredPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findStoredCallbackPairs(lambdaParamMethods)
  val storedPairMembers: Set<KSFunctionDeclaration> =
    detectedStoredPairs.flatMap { pair -> pair.toList() }.toSet()

  val storedCallbackPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    detectedStoredPairs
      // ADR-037 amendment: a listener with a non-`Unit` result is refused after detection, so both
      // halves stay claimed by the pair and neither falls to the per-call route.
      .filter { (addMethod, _) -> legacyRefusedStoredCallbackPair(addMethod) == null }
      // ADR-115 / issue #121: a marked half takes its partner with it, as on a sealed arm.
      // `warnRefusedLegacyRouteMembers` names both halves.
      .filter { (addMethod, removeMethod) ->
        listOf(addMethod, removeMethod).none { it.optInMarker(classifier.exportMarkers) != null }
      }

  val perCallLambdaMethods: List<KSFunctionDeclaration> = lambdaParamMethods
    .filter { method -> method !in storedPairMembers }
    // ADR-160: the plan owns this member's export (emitted off the catalog), so the hand-written
    // route must not mint the same `@CName` a second time.
    .filterNot { method -> method.hasPlannedCallbackParameter(classifier) }
    // ADR-160 step 4: a member this route cannot marshal is dropped here and named by the
    // planner's own CALLBACK_PROTOCOL skip, instead of failing the ADR-055 contract (a scalar
    // outer return) or emitting Kotlin that does not compile (a dropped non-lambda parameter).
    .filter { method -> legacyRefusedCallbackMember(method) == null }
    // ADR-115 / issue #121: the planner already names a marked member SKIPPED_OPT_IN_MARKER, so
    // this route must not export it anyway.
    .filter { method -> method.optInMarker(classifier.exportMarkers) == null }

  val interfaceBridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findInterfaceBridgePairs(methods)
      // ADR-090 / ADR-039 amendments (2026-09-26): an overloaded listener member, or one whose
      // parameter or return the route cannot carry, is named by `warnRefusedLegacyRouteMembers`
      // and dropped here.
      .filter { (addMethod, _) -> classifier.legacyRefusedInterfaceBridgePair(addMethod) == null }
      // ADR-115 / issue #121: the stored pair's marker refusal, on the interface-bridge route.
      .filter { (addMethod, removeMethod) ->
        listOf(addMethod, removeMethod).none { it.optInMarker(classifier.exportMarkers) != null }
      }

  return ForwardClassLegacyMembers(
    flowMethods = flowMethods,
    storedCallbackPairs = storedCallbackPairs,
    perCallLambdaMethods = perCallLambdaMethods,
    interfaceBridgePairs = interfaceBridgePairs,
  )
}

/**
 * Generates @CName bridge exports for classes: dispose, planned constructors/properties/methods,
 * and named specialized-protocol adapters (Flow, lambda, stored callback, interface bridge).
 * Ordinary synchronous members without a plan are skipped — no IntPtr/defaultValueFor fallthrough.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/008-data-class-mapping.md">ADR-008: Data class mapping</a>
 */
internal fun FileSpec.Builder.addClassExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
  // ADR-114: the legacy flow route classifies its own parameters, so a collection crosses as a
  // handle and any other generic parameter is refused by name instead of emitting `kinds: List`.
  classifier: ForwardBridgeTypeClassifier,
  // ADR-101 amendment: the export set, so this emitter asks the *gated* has-superclass predicate
  // the planners ask. An unexported base is base-less here too, so the base's concrete members
  // are emitted with this class as receiver instead of being left to a C# base that never exists.
  exportedTypes: Set<String>,
  /** ADR-163: the one symbol table, so this legacy emitter derives the prefix the plan derived. */
  symbols: ForwardSymbolTable,
) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = cls.nativePrefix(symbols)
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)

  // The shared has-superclass predicate (`ForwardClassMembership.kt`), so this emitter keeps
  // exactly the member set the planner planned: a defaulted interface member the class does not
  // override is bound here too, and the ABI contract check is what would catch any drift.
  val superClass: KSClassDeclaration? = cls.forwardSuperClass(exportedTypes)

  // ADR-091: constructors come off the catalog rather than a `getConstructors()` walk. ADR-164's
  // widened defaults need no emitter support here: the wrapper's dispatch is built from the plan,
  // so Kotlin supplies every default that was left unset.
  callableCatalog.constructors(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose", ownedBy(cls, "generated Dispose")))
      .addParameter("handle", cOpaquePointer)
      .addStatement("%T.release(handle)", nugetHandles)
      .build()
  )

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop -> prop.isForwardMemberOf(cls, superClass) }
    .toList()

  properties.forEach { prop ->
    val propName: String = prop.simpleName.asString()
    val planned: ForwardPropertyPlan? = callableCatalog.propertyFor("$qualifiedName.$propName")
    if (planned != null) {
      addForwardPropertyPlanExports(planned)
      return@forEach
    }
    // Issue #121: the planner declined, but a decline is not always an invitation. A marked
    // declaration must reach neither artifact, so the legacy arms below never run for one.
    if (prop.isOptInRefused(classifier.exportMarkers)) return@forEach
    // ADR-147: every specialized legacy route spells the receiver as the bare owner name
    // (`asStableRef<Crate>()`), which does not compile for a generic class. Refused on a generic
    // owner, on BOTH halves, rather than emitting a member one half declares and the other does
    // not (the ADR-055 contract would then fail the whole build).
    if (cls.typeParameters.isNotEmpty()) return@forEach
    // Named specialized-protocol property adapters (lambda / suspend-lambda / Flow).
    val propTypeResolved: KSType = prop.type.resolve().expandAliases()
    val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
    val isLambdaProperty: Boolean = propType in LAMBDA_TYPES || propType in SUSPEND_LAMBDA_TYPES
    if (isLambdaProperty) {
      // CIR ships lambda property getters without errorOut (hasSyncErrorOut = false).
      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName", ownedBy(prop)))
          .addParameter("handle", cOpaquePointer)
          .returns(cOpaquePointer.copy(nullable = true))
          .addStatement(
            "return %T.retain(handle.asStableRef<%L>().get().%L)",
            nugetHandles, qualifiedName, propName,
          )
          .build()
      )
      return@forEach
    }
    // ADR-124: the flow property route, one emitter shared with the sealed-arm loop in
    // `NugetProcessor` (`FlowExports.kt`). Membership stays the caller's rule, as it was.
    if (!propTypeResolved.isForwardFlowType()) return@forEach
    addFlowPropertyExports(prop, qualifiedName, prefix, classifier)
  }

  val legacy: ForwardClassLegacyMembers = cls.forwardClassLegacyMembers(classifier, superClass)
  val flowMethods: List<KSFunctionDeclaration> = legacy.flowMethods
  legacy.storedCallbackPairs.forEach { (addMethod, removeMethod) ->
    addStoredCallbackExports(addMethod, removeMethod, qualifiedName, prefix)
  }
  legacy.perCallLambdaMethods.forEach { method ->
    addLambdaParamMethodExport(method, qualifiedName, prefix)
  }
  legacy.interfaceBridgePairs.forEach { (addMethod, removeMethod) ->
    addInterfaceBridgeExports(addMethod, removeMethod, qualifiedName, prefix, classifier)
  }

  // ADR-090: member plans come off the catalog, not from a per-declaration plan lookup. Overload
  // numbering lives in the planner, so `"$qualifiedName.$methodName"` is no longer a plan key for
  // anything past the first same-name declaration (and re-emitted the first one's plan). Flow,
  // lambda-parameter, stored-callback and interface-bridge members never produce a CLASS plan
  // (the planner skips them), so catalog iteration cannot double-emit the routes above.
  callableCatalog.classMethods(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }

  flowMethods.forEach { method ->
    addFlowMethodExports(method, qualifiedName, prefix, classifier, callableCatalog)
  }

  if (cls.modifiers.contains(Modifier.DATA)) {
    // ADR-147: a generic data class is read back applied (`Box<Any?>`), as its members are; the
    // bare qualified name is not a type there. None of the three takes a `T`.
    val ownerType: String = cls.forwardOwnerTypeName() ?: qualifiedName
    addFunction(
      FunSpec.builder("export_${prefix}_equals")
        .addAnnotation(cNameAnnotation("${prefix}_equals", ownedBy(cls, "data-class equals")))
        .addParameter("handle", cOpaquePointer)
        .addParameter("other", cOpaquePointer)
        .returns(Boolean::class)
        .addStatement(
          "return handle.asStableRef<%L>().get() == other.asStableRef<%L>().get()",
          ownerType, ownerType,
        )
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_hashcode")
        .addAnnotation(cNameAnnotation("${prefix}_hashcode", ownedBy(cls, "data-class hashCode")))
        .addParameter("handle", cOpaquePointer)
        .returns(Int::class)
        .addStatement(
          "return handle.asStableRef<%L>().get().hashCode()",
          ownerType,
        )
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_tostring")
        .addAnnotation(cNameAnnotation("${prefix}_tostring", ownedBy(cls, "data-class toString")))
        .addParameter("handle", cOpaquePointer)
        .returns(String::class)
        .addStatement(
          "return handle.asStableRef<%L>().get().toString()",
          ownerType,
        )
        .build()
    )

    val planned: ForwardCallablePlan? = callableCatalog.planFor("$qualifiedName.copy")
    if (planned != null) addForwardKotlinPlanExport(planned)
  }
}

internal fun FileSpec.Builder.addCompanionExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return

  val companion: KSClassDeclaration = cls.declarations
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { it.isCompanionObject } ?: return

  // ADR-095: companion members come off the catalog — per-companion overload numbering makes the
  // symbol underivable from a `getAllFunctions()` entry (see `addObjectExports`).
  callableCatalog.companionMethods(qualifiedName).forEach { plan ->
    addForwardKotlinPlanExport(plan)
  }

  companion.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { !it.modifiers.contains(Modifier.CONST) }
    .forEach { prop ->
      val planned: ForwardPropertyPlan? =
        callableCatalog.propertyFor("$qualifiedName.Companion.${prop.simpleName.asString()}")
      if (planned != null) addForwardPropertyPlanExports(planned)
    }
}

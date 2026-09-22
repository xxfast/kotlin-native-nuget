package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.findActualType
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.resolveDocLinks
import io.github.xxfast.kotlin.native.nuget.processor.cir.withSkipRemarks
import io.github.xxfast.kotlin.native.nuget.processor.cir.withoutEmptyStaticClasses
import io.github.xxfast.kotlin.native.nuget.processor.cir.mapPackageToNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.NugetContext
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.translate
import io.github.xxfast.kotlin.native.nuget.processor.exports.addClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addCompanionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addEnumExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addFlowMethodExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addFlowPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.declaresOrInheritsFlowMember
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmFlowMethods
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmLambdaMethods
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmFlowProperties
import io.github.xxfast.kotlin.native.nuget.processor.exports.addFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.hasLegacyGenericFunctionRoute
import io.github.xxfast.kotlin.native.nuget.processor.exports.addGenericFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addInterfaceBridgeFactoryExport
import io.github.xxfast.kotlin.native.nuget.processor.exports.addInterfaceExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addLambdaParamMethodExport
import io.github.xxfast.kotlin.native.nuget.processor.exports.addStoredCallbackExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addInterfaceBridgeExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.addExtensionFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addExtensionPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addObjectExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.NUGET_RUNTIME_MEMBERS
import io.github.xxfast.kotlin.native.nuget.processor.exports.NUGET_RUNTIME_PACKAGE
import io.github.xxfast.kotlin.native.nuget.processor.exports.addPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSealedClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSuspendClassMethodExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSuspendFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addValueClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeContext
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiRole
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallableCatalogEntry
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardNativeCall
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ownedBy
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardFileClassOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwners
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticHint
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardGuardName
import io.github.xxfast.kotlin.native.nuget.processor.forward.guarded
import io.github.xxfast.kotlin.native.nuget.processor.forward.internalFailureDetail
import io.github.xxfast.kotlin.native.nuget.processor.forward.internalFailureDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.PackageScope
import io.github.xxfast.kotlin.native.nuget.processor.forward.matchesDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.forward.escalatedForStrictDependencyTypes
import io.github.xxfast.kotlin.native.nuget.processor.forward.renderForwardDiagnosticsJson
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticTrackingLogger
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardInterfaceBridgePlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardReachabilityBucket
import io.github.xxfast.kotlin.native.nuget.processor.forward.isArmOfIneligibleSealedInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEligibleSealedInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEligibleSealedType
import io.github.xxfast.kotlin.native.nuget.processor.forward.isSealedInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDeclaredTypeNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEnumArm
import io.github.xxfast.kotlin.native.nuget.processor.forward.isSealedSubclass
import io.github.xxfast.kotlin.native.nuget.processor.forward.sealedInterfaceIneligibility
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardReachabilityClosure
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardReachabilityResult
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.calls
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticReason
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardLegacyAsyncRoute
import io.github.xxfast.kotlin.native.nuget.processor.forward.isValueClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyCollectionKinds
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementCollectionKinds
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedFlowElement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyReturnCollectionKinds
import io.github.xxfast.kotlin.native.nuget.processor.forward.optInMarker
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuspendRouteMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.ownsSentence
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.forward.toDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedCsName

// A `@kotlin.native.CName`-annotated function is already a C-ABI export by definition (its native
// export name is fixed by the annotation itself). It must never be picked up by the forward
// exporter's own top-level-function scan and re-wrapped in another `export_*` C-ABI wrapper: the
// reverse-direction generator (`NugetGenerateBindingsTask`, ADR-048) emits exactly this shape for
// its registration functions — `@CName("nuget_..._register") public fun nuget_..._register(...)`
// — and those raw `COpaquePointer` parameters do not round-trip through this translator, which
// expects ordinary Kotlin-authored public API. Excluding any `@CName` function here is the general,
// robust fix: it protects against this exact class of bug for any future generated C export, not
// just this one registration function.
// ADR-062/ADR-064: ordinary synchronous callables the planner cannot plan and no named legacy
// route re-emits simply disappear from the generated C# API (never as an `IntPtr` / `"0"`
// fallback). Warn once per dropped callable through the named ForwardDiagnostic sink, naming the
// symbol and the specific kind, so a consumer whose `fun f(m: Map<K, V>)` vanishes from C# learns
// why. Every one of these is `SKIPPED_*` (warning), never `ERROR_*`: existing fixtures
// intentionally contain such declarations and generation must keep succeeding.
/** The nested declaration kinds [ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION] names. An
 *  `annotation class` is excluded: it is not declared in C# wherever it lives, and
 *  [ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS] already says so. Shared with the ADR-066
 *  reachability closure, whose edge-B descent walks exactly the kinds ADR-133 can declare. */
internal val NESTED_DECLARATION_KINDS: Set<ClassKind> = setOf(
  ClassKind.CLASS,
  ClassKind.OBJECT,
  ClassKind.INTERFACE,
  ClassKind.ENUM_CLASS,
)

private fun KSClassDeclaration.nestedDeclarationKind(): String = when (classKind) {
  ClassKind.ENUM_CLASS -> "enum class"
  ClassKind.OBJECT -> "object"
  ClassKind.INTERFACE -> "interface"
  else -> "class"
}

// ADR-064 amendment (2026-09-11): "once per public nested declaration" holds at any depth, so the
// walk recurses instead of reading one level of `declarations`. A non-public child is not reachable
// API and is not descended into, which matches the public filter the candidates already carry.
private fun KSClassDeclaration.nestedClassDeclarations(): Sequence<KSClassDeclaration> =
  declarations
    .filterIsInstance<KSClassDeclaration>()
    // Issue #223, widened by issue #235: a declaration a compiler wrote is neither declared nor
    // descended into. The predicate is shared with every member walk (`exports/Helpers.kt`), so a
    // synthesized declaration cannot come back through another route the way `$serializer` did.
    .filter { !it.isCompilerOwnedDeclaration() }
    // Kind before visibility, verified: `getVisibility()` on an enum entry read from a dependency
    // klib/jar throws `Internal KSP Error` out of its `modifiers` delegate, and an entry is never a
    // nested-declaration candidate anyway.
    .filter { it.classKind in NESTED_DECLARATION_KINDS }
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .flatMap { nested ->
      // ADR-133: the walk descends into an `enum class` too. An enum owner stays DEFERRED (it has
      // no C# declaration block to nest into), but the deferred set is named, not silent, so
      // `Season.Almanac` has to be reached to be reported at all.
      sequenceOf(nested) + nested.nestedClassDeclarations()
    }

// ADR-133: the enclosing declaration chain, innermost-first, of a nested declaration.
private fun KSClassDeclaration.enclosingClassChain(): List<KSClassDeclaration> =
  generateSequence<KSDeclaration>(this) { it.parentDeclaration }
    .drop(1)
    .takeWhile { it is KSClassDeclaration }
    .filterIsInstance<KSClassDeclaration>()
    .toList()

/**
 * ADR-133: why this declaration cannot OWN a C# nested type, or null when it can.
 *
 * v1 declares children under a non-generic, non-inner `class` or `object` (root or admitted
 * dependency) at any depth. Every other shape keeps ADR-064's named skip, with this text as the
 * reason, so a deferred nested declaration still says why instead of vanishing.
 */
internal fun KSClassDeclaration.unsupportedNestedOwnerReason(): String? = when {
  classKind == ClassKind.ENUM_CLASS ->
    "an `enum class` owner has no C# declaration block to nest a type into"
  // ADR-134: an `interface` owner and a sealed base/arm owner are admitted, so their arms are
  // gone from here. The kind arm admits INTERFACE beside CLASS and OBJECT; the generic arm below
  // is what keeps a variant type parameter's scope free of nested types (C# spec 19.4.9).
  classKind != ClassKind.CLASS && classKind != ClassKind.OBJECT &&
      classKind != ClassKind.INTERFACE ->
    "only a `class`, `object` or `interface` owner carries nested declarations"
  typeParameters.isNotEmpty() ->
    "a generic owner's nested type is itself generic in C# (`Owner<T>.Nested`)"
  // ADR-141: an inner class is declared now, but its OWN nested types are not. Only another
  // `inner class` can nest inside one (a plain nested class there is NESTED_CLASS_NOT_ALLOWED), and
  // the receiver for that child would be the inner instance, one level up from this ADR's.
  modifiers.contains(Modifier.INNER) ->
    "an `inner class` owner's own nested types are deferred"
  isValueClass() -> "a `value class` owner has no nested-type slot"
  isCompanionObject -> "a companion object is folded into its owner's statics (ADR-013)"
  else -> null
}

/** ADR-133: why this nested candidate itself is deferred, or null when it is declared. */
internal fun KSClassDeclaration.unsupportedNestedCandidateReason(): String? = when {
  // ADR-141: no `inner` arm here any more -- an inner class IS declared, with the outer instance as
  // its constructor's first parameter. The owner arm above still defers an inner-of-inner.
  typeParameters.isNotEmpty() -> "a generic nested type is deferred"
  modifiers.contains(Modifier.SEALED) ->
    "a nested sealed hierarchy is deferred (its arms would have to nest twice)"
  else -> null
}

/**
 * ADR-133: the whole deferral reason for a nested candidate -- its own shape first, then the
 * nearest enclosing declaration that cannot own it. Null means "declare it as `Owner.Nested`".
 */
internal fun KSClassDeclaration.nestedDeclarationDeferral(): String? {
  unsupportedNestedCandidateReason()?.let { return it }
  enclosingClassChain().forEach { owner ->
    owner.unsupportedNestedOwnerReason()?.let { reason ->
      val ownerName: String = owner.qualifiedName?.asString() ?: owner.simpleName.asString()
      return "its enclosing declaration `$ownerName` cannot own one: $reason"
    }
  }
  return null
}

/**
 * ADR-133 surface 6: the C# member name of the owner this nested type's name collides with, or
 * null. C# forbids a member and a nested type sharing a name in the same declaring type (CS0102),
 * and forbids a nested type named like its owner (CS0542); Kotlin permits both, so
 * `class Config` beside `val config: Config` would otherwise generate uncompilable C#.
 */
internal fun KSClassDeclaration.nestedOwnerScopeCollision(): String? {
  val owner: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return null
  val name: String = simpleName.asString()
  // CS0542 compares the C# names, not the Kotlin ones: ADR-134 declares an `interface` owner's
  // child inside `public interface ICage`, so `interface Cage { class Cage }` is the legal
  // `ICage.Cage`. An ADR-112 eligible sealed interface renders as `public abstract class Beam` with
  // no `I` (issue #54), so `Beam.Beam` is still the error this arm exists for.
  val segments: List<String> = nestedCsName().split('.')
  if (segments.size >= 2 && segments[segments.size - 2] == segments.last()) {
    return "its owner's own name (CS0542)"
  }
  // ADR-013 folds a companion's public members into the owner's C# class as statics (`const val`
  // included, see `CirClassTranslator`), so they share the one member-name scope the nested type is
  // declared in: `companion object { fun config(): Config }` beside `class Config` is CS0102 just
  // as an instance `val config` is. Static-ness is not part of a C# member name.
  val companion: KSClassDeclaration? = owner.declarations
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { it.isCompanionObject }
  val companionMemberNames: List<String> = if (companion == null) {
    emptyList()
  } else {
    companion.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .map { it.simpleName.asString() }
      .toList() +
        companion.getAllFunctions()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .map { it.simpleName.asString() }
          .toList()
  }
  val memberNames: List<String> =
    (owner.getAllProperties().map { it.simpleName.asString() }.toList() +
        owner.getAllFunctions().map { it.simpleName.asString() }.toList() +
        companionMemberNames)
      .map { it.replaceFirstChar { c -> c.uppercase() } }
  return if (name in memberNames) "the member `$name` of the same C# type (CS0102)" else null
}

internal fun warnDroppedForwardCallables(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
  // ROADMAP line 37 / ADR-154: the author's own `exclude(...)` entries, so an excluded-type hint
  // quotes the entry that matched. Replaces the issue #55 `include(...)` scope list, which the
  // additive `admit(...)` hint no longer needs (ADR-154 §5).
  excludeEntries: List<String> = emptyList(),
  // ADR-154 §6: opt-in escalation of the two actionable dependency-scope refusals.
  strictDependencyTypes: Boolean = false,
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedCallables.map { dropped ->
    ForwardDiagnostic(
      kind = dropped.reason.toDiagnosticKind(dropped.position, dropped.structural)
        .escalatedForStrictDependencyTypes(dropped.reason, strictDependencyTypes),
      symbol = dropped.node,
      declaration = dropped.symbol,
      // ADR-064's 2026-09-10 amendment: the sentence lives on the reason, beside the hint it
      // reads with, so a drop that is not a type combination adds a `when` arm there rather than
      // a sixth special case here.
      reason = dropped.reason.diagnosticReason(dropped.detail, dropped.parameter),
      hint = dropped.reason.diagnosticHint(dropped.detail, dropped.parameter, excludeEntries),
      // Issue #249: stamped on the catalog entry by the walk that planned it, so an inherited
      // member's owner is the class being planned rather than the supertype its node reports.
      owner = dropped.owner,
      member = dropped.memberName,
    )
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

/**
 * ADR-064 amendment (2026-09-13): the structural top-level generic functions the planner never
 * sees. `catalog()` is called with `functions` (the `typeParameters.isEmpty()` half of the
 * collected list), so a `fun <T> f(...)` produces no catalog entry at all and the catalog's
 * unrouted-position reclassification cannot reach it. Its only route is
 * `addGenericFunctionExports` / `translateGenericFunction`, which dispatch on a parameter typed
 * with the function's own type parameter and refuse everything else with a bare `return` —
 * measured silent on both halves for `fun <T> f(): List<T>` (research H cell 16).
 *
 * Same shape as [warnRefusedLegacyRouteMembers]: the route's own hoisted gate decides, so the
 * diagnostic and the two emitters cannot drift. The position is the RETURN: a refused declaration
 * always has a `T` somewhere other than a direct parameter, which for every measured cell is the
 * return type.
 */
internal fun warnUnroutedGenericFunctions(
  genericFunctions: List<KSFunctionDeclaration>,
  logger: KSPLogger,
) {
  val diagnostics: List<ForwardDiagnostic> = genericFunctions
    .filterNot { function -> function.hasLegacyGenericFunctionRoute() }
    .map { function ->
      val declaration: String =
        "${function.packageName.asString()}.${function.simpleName.asString()}"
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
        symbol = function,
        declaration = declaration,
        reason = ForwardPlanSkipReason.UNROUTED_POSITION
          .diagnosticReason(ForwardPlanSkipReason.GENERIC.name),
        hint = ForwardPlanSkipReason.UNROUTED_POSITION
          .diagnosticHint(ForwardPlanSkipReason.GENERIC.name),
        // A top-level declaration, so the hole is on its ADR-007 file holder.
        owner = function.forwardFileClassOwner(),
        member = function.simpleName.asString(),
      )
    }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

// ADR-075 Decision 2/Question C: a mutable collection property whose element (or map key/value)
// fails `isWrappableComponent()` still plans -- get-only -- so this is a *partial* skip, not the
// callable-style "this member vanished entirely" one above. Wording says so explicitly: the C#
// property survives read-only, it was not dropped.
internal fun warnDroppedForwardPropertySetters(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedPropertySetters.map { dropped ->
    ForwardDiagnostic(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
      symbol = dropped.node,
      declaration = dropped.symbol,
      reason = "its setter is not generated because " +
          (dropped.reason ?: "the ${dropped.componentDescription} cannot be written into a " +
          "Kotlin collection"),
      hint = "the C# property ${dropped.publicName} is read-only",
      // Issue #249: the property SURVIVES read-only, so the remark belongs on the generated C#
      // property and never on its type -- a type-level "not available" paragraph would report a
      // member as absent that a consumer can call.
      owner = dropped.owner?.let { container ->
        ForwardDiagnosticOwner.Property(container, dropped.publicName)
      },
      member = dropped.memberName,
    )
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

// The whole-property counterpart to the partial setter skip above: the property planner had no
// getter or setter shape for the declared type at all, so nothing of the member reaches C#. The
// hint names the property's own type rather than a fixed example, so it stays accurate for every
// type that lands here.
internal fun warnDroppedForwardProperties(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
  // ROADMAP line 37 / ADR-154: the callable route's parameter, verbatim — the author's own
  // `exclude(...)` entries, so both routes quote the matched entry identically.
  excludeEntries: List<String> = emptyList(),
  // ADR-154 §6: the property position takes the SAME escalation, even though its kind
  // (`SKIPPED_UNSUPPORTED_PROPERTY`) is positional and names no dependency at all. That is exactly
  // why strict mode keys on the reason and never on the kind.
  strictDependencyTypes: Boolean = false,
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedProperties.map { dropped ->
    // ADR-115: the author's own signal, named as such. Checked first: the property's type is
    // typically fine, so every message below would send the author after the wrong declaration.
    if (dropped.optInMarker != null) {
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER,
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = ForwardPlanSkipReason.OPT_IN_MARKER.diagnosticReason(dropped.optInMarker),
        hint = ForwardPlanSkipReason.OPT_IN_MARKER.diagnosticHint(dropped.optInMarker),
        owner = dropped.owner,
        member = dropped.memberName,
      )
    } else if (dropped.boundInterface) {
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_BOUND_TYPE_POSITION,
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = "the bound C# interface ${dropped.typeDescription} is not marshalled at a " +
            "property position",
        hint = ForwardPlanSkipReason.BOUND_INTERFACE_POSITION.diagnosticHint(),
        owner = dropped.owner,
        member = dropped.memberName,
      )
    } else if (dropped.reason?.ownsSentence(dropped.detail) == true) {
      // ADR-064's 2026-09-11 amendment: the reason the property planner classified already has a
      // sentence and a remedy that agree with each other, and the shipped pair below contradicts
      // both (a nested enum is not fixed by "expose a property whose type is not Mode").
      //
      // The KIND stays the position one: `SKIPPED_UNSUPPORTED_PROPERTY` names *where* the drop
      // happened, which is still true, nine Tier 1 tests and the kind's own KDoc define it that
      // way, and `toDiagnosticKind()` deliberately `error()`s on the legacy-route reasons a
      // property can genuinely hold.
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY
          .escalatedForStrictDependencyTypes(dropped.reason, strictDependencyTypes),
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = dropped.reason.diagnosticReason(dropped.detail),
        hint = dropped.reason.diagnosticHint(dropped.detail, excludeEntries = excludeEntries),
        owner = dropped.owner,
        member = dropped.memberName,
      )
    } else {
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = "its type ${dropped.typeDescription} has no property getter or setter shape",
        hint = "expose a bridgeable property (or a getter function) whose type is not " +
            "${dropped.typeDescription}, and export that instead",
        owner = dropped.owner,
        member = dropped.memberName,
      )
    }
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

// Second producer for the same kind (the established `SKIPPED_UNSUPPORTED_INPUT` pattern, fed by
// both setter drops and input skips): an extension property dropped for its *receiver* type rather
// than its own. The wording says receiver explicitly, because the property's declared type is
// typically supported and naming it would send the author after the wrong declaration.
internal fun warnDroppedForwardExtensionReceivers(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedExtensionReceivers.map { dropped ->
    if (dropped.reason?.ownsSentence(dropped.detail) == true) {
      // ADR-064 amendment (2026-09-20): exactly the shape `warnDroppedForwardProperties` already
      // uses for a classified property drop. The planner named the refusal
      // (`RECEIVER_FAN_OUT`), that reason owns a sentence and a remedy that agree with each other,
      // and the shipped pair below contradicts both: it lists "primitive" and "nullable class" as
      // supported without explaining `Int?`, and its "top-level getter function" remedy hides the
      // simpler one (take the value as an ordinary parameter).
      //
      // The KIND stays this route's position kind, per ADR-064's rule that a kind names WHERE the
      // drop happened: the identical Kotlin shape reports `SKIPPED_UNSUPPORTED_INPUT` on the
      // extension-function route and `SKIPPED_UNSUPPORTED_PROPERTY` here, reading one sentence.
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = dropped.reason.diagnosticReason(dropped.detail),
        hint = dropped.reason.diagnosticHint(dropped.detail),
        // Issue #249: no owner. The C# hole would be on the `{Receiver}Extensions` static class,
        // which may never be generated at all, so there is nothing to attach a paragraph to.
        owner = null,
      )
    } else {
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = "its extension receiver type ${dropped.receiverDescription} is not a supported " +
            "extension-property receiver",
        // ADR-132 (2026-09-20): the list tracks `ForwardPropertyPlanner.isSupportedReceiver`, which
        // now reaches extension-function receiver parity. What is left out is the has-value fan-out
        // class (`Int?`, `Mood?`, `Instant?`, a nullable value class over a primitive or enum
        // underlying): a receiver is exactly one ABI slot and those need two. That class no longer
        // reaches this hint at all -- it takes the named branch above -- so what lands here is a
        // receiver this route has no lowering for at any width (a raw generic `Box<Int>`, a type
        // parameter, a `Char`).
        hint = "declare the property on a class, interface, nullable class, nullable interface, " +
            "String, nullable String, primitive, enum, Uuid, nullable Uuid, Instant, Duration, " +
            "collection, bound C# interface, value class, or nullable value class over a String " +
            "or class underlying receiver, or expose a top-level getter function instead",
        // Issue #249: no owner. The C# hole would be on the `{Receiver}Extensions` static class,
        // which may never be generated at all, so there is nothing to attach a paragraph to.
        owner = null,
      )
    }
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

/**
 * ADR-114: the refusal arm of the legacy Flow/StateFlow and suspend routes. Those routes marshal a
 * *collection* parameter, and refuse every other generic one by name rather than emitting the
 * `entry: Pair` that breaks the generated file's compile outright (issue #109). Emitted from one
 * place, so the Kotlin export builders and the CIR translators, which both drop the same members
 * silently, cannot double-report or disagree about which member vanished.
 *
 * ADR-119: the same walk names a suspend member's refused *return* (`Pair<String, Int>`,
 * `List<String>?`, `Flow<T>`), which used to render `Task<Pair>` and fail the consumer's compile
 * (issue #122). A member with both a refused parameter and a refused return is named once, for
 * the parameter: one skip per member, whichever gate it hit first.
 */
internal fun warnRefusedLegacyRouteMembers(
  classes: List<KSClassDeclaration>,
  // ADR-118: a sealed arm's suspend member is on the legacy route now, so a refused parameter is
  // filtered silently by both halves exactly as an ordinary class's is, and this walk is the only
  // thing left that names it. Before ADR-118 it was named as SEALED_SUBCLASS_UNROUTED instead.
  sealedClasses: List<KSClassDeclaration>,
  suspendFunctions: List<KSFunctionDeclaration>,
  classifier: ForwardBridgeTypeClassifier,
  logger: KSPLogger,
) {
  fun refusedParameter(
    member: KSFunctionDeclaration,
    declaration: String,
    refused: String,
    // Issue #249: the declaration being walked, never `member.parentDeclaration` -- this walk is
    // over `getAllFunctions()`, so an inherited member reports the supertype while the C# hole is
    // on the class (or arm) whose generated type would have carried it.
    owner: ForwardDiagnosticOwner?,
  ): ForwardDiagnostic = ForwardDiagnostic(
    kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
    symbol = member,
    declaration = declaration,
    // ADR-122 widened this from generic-only: an enum, Instant/Duration/Uuid, value class,
    // interface, nullable object or unexported class parameter used to render a public `IntPtr`
    // here, so the wording names what the route CAN take rather than only what it cannot.
    reason = "a Flow-returning or suspend member can take a primitive/String, a List/Set/Map, or " +
        "a class/object/sealed-type handle, but not $refused",
    hint = "pass a class, object or sealed type, a List/Set/Map, or a primitive/String, or " +
        "expose the values as separate parameters",
    owner = owner,
    member = member.simpleName.asString(),
  )

  fun refusedReturn(
    member: KSFunctionDeclaration,
    declaration: String,
    refused: String,
    owner: ForwardDiagnosticOwner?,
  ): ForwardDiagnostic = ForwardDiagnostic(
    kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    symbol = member,
    declaration = declaration,
    // ADR-123 widened this from the suspend return to the Flow/StateFlow element, which is the
    // same refusal one position over: `Flow<Pair<String, Int>>` has no more wire shape than
    // `Pair<String, Int>` does.
    reason = "a suspend member can return, and a Flow or StateFlow element can be, a " +
        "List/Set/Map, but not the generic type $refused",
    hint = "return a non-nullable List/Set/Map, or a non-generic type",
    owner = owner,
    member = member.simpleName.asString(),
  )

  // ADR-123: the property half of the same refusal. A flow property has no `KSFunctionDeclaration`
  // to hang the return diagnostic on, and SKIPPED_UNSUPPORTED_PROPERTY is what every other
  // dropped-property route already uses.
  fun refusedFlowProperty(
    property: KSPropertyDeclaration,
    declaration: String,
    refused: String,
    owner: ForwardDiagnosticOwner?,
  ): ForwardDiagnostic = ForwardDiagnostic(
    kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
    symbol = property,
    declaration = declaration,
    reason = "a Flow or StateFlow element can be a List/Set/Map, but not the generic type $refused",
    hint = "make the element a non-nullable List/Set/Map, or a non-generic type",
    owner = owner,
    member = property.simpleName.asString(),
  )

  fun MutableList<ForwardDiagnostic>.nameRefused(
    member: KSFunctionDeclaration,
    declaration: String,
    owner: ForwardDiagnosticOwner?,
  ) {
    val parameter: String? = classifier.legacyRefusedParameter(member.parameters)
    if (parameter != null) {
      add(refusedParameter(member, declaration, parameter, owner))
      return
    }
    val returned: String = classifier.legacyRefusedReturn(member) ?: return
    add(refusedReturn(member, declaration, returned, owner))
  }

  val diagnostics: List<ForwardDiagnostic> = buildList {
    classes.forEach { cls ->
      val owner: String = cls.simpleName.asString()
      val ownerDeclaration: ForwardDiagnosticOwner = cls.forwardDiagnosticOwner()
      cls.getAllFunctions()
        .filter { method -> method.getVisibility() == Visibility.PUBLIC }
        .filter { method -> method.isForwardLegacyAsyncRoute() }
        .forEach { method ->
          nameRefused(method, "$owner.${method.simpleName.asString()}", ownerDeclaration)
        }
      // ADR-123: a Flow/StateFlow *property* whose element cannot cross. Both halves drop it
      // silently, exactly as they drop a method, so this walk is the only thing that names it.
      cls.getAllProperties()
        .filter { property -> property.getVisibility() == Visibility.PUBLIC }
        .forEach { property ->
          val refused: String =
            classifier.legacyRefusedFlowElement(property.type.resolve()) ?: return@forEach
          add(
            refusedFlowProperty(
              property, "$owner.${property.simpleName.asString()}", refused, ownerDeclaration,
            ),
          )
        }
    }
    sealedClasses.forEach { sealed ->
      val sealedName: String = sealed.simpleName.asString()
      sealed.getSealedSubclasses().forEach { subclass ->
        // ADR-157: a boxed enum arm declares no members of this route's kind, and what it does
        // declare belongs to `{Enum}Extensions`. Naming one here would report a hole in a C# type
        // that carries the member perfectly well.
        if (subclass.isEnumArm()) return@forEach
        val owner: String = "$sealedName.${subclass.simpleName.asString()}"
        // ADR-009 declares an arm under its base, nested or not, so the arm is its own C# owner.
        val ownerDeclaration: ForwardDiagnosticOwner = subclass.forwardDiagnosticOwner()
        subclass.getAllFunctions()
          .filter { method -> method.getVisibility() == Visibility.PUBLIC }
          // Declared-only, as everywhere else on the sealed route.
          .filter { method -> method.parentDeclaration == subclass }
          // ADR-124: the Flow half of the same route joins the suspend half here. Both are routed
          // on an arm now, so both halves drop a refused member silently and this walk is the only
          // thing left that names it.
          .filter { method -> method.isForwardLegacyAsyncRoute() }
          .forEach { method ->
            nameRefused(method, "$owner.${method.simpleName.asString()}", ownerDeclaration)
          }
        // ADR-124: and the arm's flow *properties*, whose refused element has no
        // `KSFunctionDeclaration` to hang a return diagnostic on. All-properties, ADR-111's rule.
        subclass.getAllProperties()
          .filter { property -> property.getVisibility() == Visibility.PUBLIC }
          .forEach { property ->
            val refused: String =
              classifier.legacyRefusedFlowElement(property.type.resolve()) ?: return@forEach
            add(
              refusedFlowProperty(
                property, "$owner.${property.simpleName.asString()}", refused, ownerDeclaration,
              ),
            )
          }
      }
    }
    suspendFunctions.forEach { func ->
      nameRefused(func, func.simpleName.asString(), func.forwardFileClassOwner())
    }
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

/**
 * ADR-118: whether a sealed subclass **declares** a `suspend fun` of its own. The declared-only
 * gate is the sealed route's rule everywhere (the planner's `sealedSubclassEntries`, the C#
 * translator and the Kotlin export builder), so an inherited `open suspend fun` belongs to no arm.
 */
private fun KSClassDeclaration.declaresSuspendMember(): Boolean = getAllFunctions().any { method ->
  method.getVisibility() == Visibility.PUBLIC &&
      method.parentDeclaration == this &&
      method.modifiers.contains(Modifier.SUSPEND)
}

private fun KSAnnotated.hasCNameAnnotation(): Boolean =
  annotations.any { annotation ->
    val name: String? = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
    name == "kotlin.native.CName"
  }

/**
 * The generated C# in both the forms the ABI contract check needs: the CIR nodes for the ordinary
 * universe, and the rendered text that was written to Interop.cs for the legacy one (ADR-078).
 * Rendered once, so what is checked is what ships.
 */
private data class CsharpBindings(val cir: CirFile, val rendered: String)

class NugetProcessor(
  private val codeGenerator: CodeGenerator,
  logger: KSPLogger,
  private val context: NugetContext,
) : SymbolProcessor {

  private var processed = false

  private val renderer = CirRenderer()

  // ADR-064: every `logger.error(...)` this class makes (directly, or transitively through the
  // CIR translators it passes this to) routes through this wrapper, so `process()` can tell
  // whether an ERROR_* diagnostic (e.g. ERROR_CSHARP_SIGNATURE_COLLISION) fired during
  // generation, in time to skip writing `cNameExports.kt` for a construct that must never
  // compile — `logger.error` itself returns nothing, and KSP's own round-failure detection only
  // surfaces after `process()` returns.
  private val logger: ForwardDiagnosticTrackingLogger = ForwardDiagnosticTrackingLogger(logger)

  /**
   * ADR-162: the last line of containment. The per-declaration guards cover the loops, but the
   * round also does whole-file work no declaration owns — the ADR-066 closure, the post-passes,
   * and the single `CirRenderer.render` call, which cannot name a declaration without threading
   * `KSNode` through the whole CIR model (deferred). A failure there used to leave KSP printing one
   * bare `e: [ksp] java.lang.IllegalStateException: ...` with no `[nuget:...]` kind to grep for;
   * now it is labelled, at the cost of having no source location to attach.
   *
   * `Exception` only, so an `OutOfMemoryError` or a `StackOverflowError` still aborts the round.
   */
  override fun process(resolver: Resolver): List<KSAnnotated> = try {
    processRound(resolver)
  } catch (failure: Exception) {
    ForwardDiagnosticSink.emit(
      listOf(
        internalFailureDiagnostic(
          declaration = "this Kotlin module",
          node = null,
          detail = internalFailureDetail(failure),
        ),
      ),
      logger,
    )
    emptyList()
  }

  private fun processRound(resolver: Resolver): List<KSAnnotated> {
    if (processed) return emptyList()
    processed = true

    // ADR-100: the sink is a singleton inside a long-lived Gradle daemon; start this round empty so
    // NugetDiagnostics.json describes this compilation and no earlier one.
    ForwardDiagnosticSink.reset()

    // ADR-063: the effective include set is the explicit `include` when non-empty, else
    // `[rootPackage]` when `rootPackage` is set, else empty (= all). Mirrors the reverse side's
    // `IsNamespaceIncluded` predicate exactly (exclude wins; empty include = all; prefix match).
    val effectiveInclude: List<String> = when {
      context.includePackages.isNotEmpty() -> context.includePackages
      context.rootPackage.isNotBlank() -> listOf(context.rootPackage)
      else -> emptyList()
    }

    // ADR-109: this module's own ADR-063 predicate as a value, so the identical matching rules
    // (exclude wins, by package prefix or by qualified declaration name per issue #53; empty
    // include = everything; otherwise a package-prefix test) can be applied to ANOTHER
    // publisher's scope without a second, drifting copy of them.
    val ownScope = PackageScope(include = effectiveInclude, exclude = context.excludePackages)

    fun isExported(declaration: KSDeclaration): Boolean {
      val pkg: String = declaration.packageName.asString()
      val qualifiedName: String? = declaration.qualifiedName?.asString()

      // ADR-063 "Reverse-bound packages are always in scope": checked first, before exclude and
      // before include. A module that both publishes forward and consumes via `bind {}` returns
      // reverse-bound types from its own forward code; dropping the bound stub's declaration
      // while the forward-generated C# still references it is a dangling-reference build break,
      // not a scoping choice the user asked for.
      if (context.boundPackages.any { isUnderPackage(pkg, it) }) return true
      return ownScope.covers(pkg, qualifiedName)
    }

    // ADR-115: an opt-in-marked declaration is refused at the same place a package-scope refusal
    // is, so no C# type is declared for it and nothing in `CNameExports.kt` names it -- which is
    // the half of issue #113 that broke the generated file's own compile. Applied on top of the
    // scope predicate rather than inside it, so the marked declarations stay enumerable for the
    // diagnostic below. The reachability closure takes the composed predicate too, so a marked
    // dependency-module type is never admitted either.
    fun isMarkedOptIn(declaration: KSDeclaration): Boolean =
      declaration.optInMarker(context.exportMarkers) != null

    fun isExportedAndUnmarked(declaration: KSDeclaration): Boolean =
      isExported(declaration) && !isMarkedOptIn(declaration)

    // ADR-154 §1: the additive `admit(...)` matcher — the exact by-package-or-by-qualified-name
    // rule `exclude` uses (issue #53), so one entry can name a single type or a whole package.
    fun admitEntry(declaration: KSDeclaration): String? = context.admit.matchesDeclaration(
      declaration.packageName.asString(),
      declaration.qualifiedName?.asString(),
    )

    // ADR-154 §2: the closure's admission predicate for a CROSS-MODULE declaration.
    //
    // `effectiveInclude.isNotEmpty()` on the first disjunct is load-bearing, not defensive:
    // `PackageScope.covers` answers `true` for every non-excluded package when its include list is
    // empty (`ForwardPublishedScope.kt`), and today that never reaches a klib declaration only
    // because admission rule 4 shuts the gate first. `admit(...)` opens that gate (see
    // `crossModuleAdmissionAllowed` below), so without the guard a build with no `rootPackage`, no
    // `include` and one `admit("io.ktor.http.Url")` would admit EVERY reachable klib declaration
    // and walk the whole compile classpath — ADR-066 Alternative 3, silently.
    //
    // `exclude` still wins (the closure tests it before this predicate) and ADR-115's opt-in
    // markers still refuse, on both disjuncts.
    fun isAdmitted(declaration: KSDeclaration): Boolean {
      if (effectiveInclude.isNotEmpty() && isExportedAndUnmarked(declaration)) return true
      return admitEntry(declaration) != null && !isMarkedOptIn(declaration)
    }

    // ADR-074: for a native compilation `getAllFiles()` returns both halves of every
    // `expect`/`actual` pair as two files of one compilation (Verified, spike finding 1), so this
    // raw list is the shared input for the `isExpect` filter below, the by-name expect index, and
    // Decision 2's `actual typealias` target map.
    val allFilesDeclarations: List<KSDeclaration> = resolver.getAllFiles()
      .flatMap { it.declarations }
      .toList()

    val candidateDeclarations: List<KSDeclaration> = allFilesDeclarations
      .filter { it.packageName.asString() != "io.github.xxfast.kotlin.native.nuget.generated" }
      // ADR-074: the `actual` is the export root. Without this every pair is planned twice under
      // one qualified name and trips a catalog duplicate guard. Same rule, same three declaration
      // kinds, as Kotlin/Native's own C export (`CAdapterGenerator`) and ObjC export. No
      // diagnostic: filtering an `expect` is normal and every Kotlin backend does it silently.
      .filter { !it.isExpect }
      .toList()

    val allDeclarations: List<KSDeclaration> =
      candidateDeclarations.filter(::isExportedAndUnmarked)

    // ADR-157: the name index `armIneligibility` reads, so a `{Enum}Arm` box whose name is already
    // taken refuses its hierarchy by name instead of emitting a CS0101 nothing explains. Every
    // declared class in the module, not just the exported ones: a C# type this build does not emit
    // still cannot collide, but an internal Kotlin class is not the hazard -- the hazard is a
    // *public* sibling, and this filter is the same one the roots use.
    ForwardDeclaredTypeNames.reset(
      allDeclarations.asSequence().filterIsInstance<KSClassDeclaration>(),
    )

    // ADR-115: named once, where the author wrote the marker. A marked *member* of an exported
    // class skips per-callable in the planner instead; only a declaration the scope would
    // otherwise have exported is reported here, so an out-of-scope marked declaration stays silent
    // exactly as an unmarked one does.
    ForwardDiagnosticSink.emit(
      candidateDeclarations
        .filter { declaration -> isExported(declaration) && isMarkedOptIn(declaration) }
        .map { declaration ->
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER,
            symbol = declaration,
            declaration = declaration.qualifiedName?.asString()
              ?: declaration.simpleName.asString(),
            reason = ForwardPlanSkipReason.OPT_IN_MARKER
              .diagnosticReason(declaration.optInMarker(context.exportMarkers)),
            hint = ForwardPlanSkipReason.OPT_IN_MARKER
              .diagnosticHint(declaration.optInMarker(context.exportMarkers)),
            // Issue #249: a marked top-level function or property leaves a hole in its ADR-007
            // file holder and is named there. A marked TYPE has no C# owner at all -- nothing is
            // generated for it and nothing else lost a member -- so it stays ownerless.
            // An EXTENSION is ownerless for the same reason a dropped extension receiver is: its
            // holder is `{Receiver}Extensions`, which this declaration may have been the only
            // member of.
            owner = when (declaration) {
              is KSFunctionDeclaration ->
                declaration.forwardFileClassOwner().takeIf { declaration.extensionReceiver == null }

              is KSPropertyDeclaration ->
                declaration.forwardFileClassOwner().takeIf { declaration.extensionReceiver == null }

              else -> null
            },
            member = declaration.simpleName.asString(),
          )
        },
      logger,
    )

    // Issue #55: scoping that admits nothing used to be indistinguishable from a module with no
    // public API at all: `packNuget` stayed green with no `Interop.cs` in the package. Say so
    // once, naming the scope that did it, before the `hasNothingToProcess` early return below.
    if (allDeclarations.isEmpty() && candidateDeclarations.isNotEmpty()) {
      val scope: String = buildList {
        if (context.includePackages.isNotEmpty()) {
          add("include(${context.includePackages.joinToString { "\"$it\"" }})")
        }
        if (context.excludePackages.isNotEmpty()) {
          add("exclude(${context.excludePackages.joinToString { "\"$it\"" }})")
        }
        // ADR-154: listed for completeness, never as the cause — `admit(...)` is dependency-only
        // and cannot empty the module's OWN export set, which is what this diagnostic is about.
        if (context.admit.isNotEmpty()) {
          add("admit(${context.admit.joinToString { "\"$it\"" }})")
        }
        if (context.rootPackage.isNotBlank()) add("rootPackage = \"${context.rootPackage}\"")
      }.joinToString()
      val packages: String = candidateDeclarations
        .map { it.packageName.asString() }
        .distinct()
        .sorted()
        .joinToString { "\"$it\"" }
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.SKIPPED_ALL_DECLARATIONS,
            symbol = null,
            declaration = context.libraryName,
            reason = "the export scope ($scope) admits none of the module's " +
                "${candidateDeclarations.size} public declaration(s) in package(s) $packages, " +
                "so no Interop.cs is generated",
            hint = "an explicit include replaces the rootPackage default rather than adding to " +
                "it: list your own package(s) in include(...) as well, or drop include(...) to " +
                "fall back to rootPackage",
            // Build configuration, not a member: no `Interop.cs` is generated at all here, so
            // there is no declaration to carry a remark.
            owner = null,
          ),
        ),
        logger,
      )
    }

    // ADR-074: kept because the `actual` is structurally complete but metadata-poor (no KDoc, no
    // annotations, no parameter defaults). `findExpects()` is Verified empty on KSP 2.3.10, so the
    // qualified name is the only available link. Consumed by Decision 2 (actual typealias target
    // redirect) and Decision 3 (per-file C# static class naming). ADR-096: overloaded `expect fun`s
    // share one qualified name, so the index resolves functions by signature rather than by name.
    val expects = ExpectIndex(allFilesDeclarations)

    // ADR-074 Decision 2: collected from the same funnel input, before the `isExpect` filter drops
    // the paired expect class. `findActualType()` resolves the alias to its target
    // KSClassDeclaration (Verified, spike finding 8); keyed by the alias's own qualified name,
    // which is always the *expect*'s qualified name (Kotlin requires an `actual typealias` to
    // share the expect's package and simple name), since a type reference to that name is exactly
    // what the classifier/reachability-closure redirect needs to intercept.
    val actualTypeAliasTargets: Map<String, KSClassDeclaration> = allFilesDeclarations
      .filterIsInstance<KSTypeAlias>()
      .filter { it.isActual }
      .mapNotNull { alias -> alias.qualifiedName?.asString()?.let { it to alias.findActualType() } }
      .toMap()

    val allFunctions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver == null }
      .filter { !it.hasCNameAnnotation() }

    val extensionFunctions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver != null }
      .filter { !it.hasCNameAnnotation() }

    val (suspendFunctions, regularFunctions) = allFunctions
      .filter { it.typeParameters.isEmpty() }
      .partition { it.modifiers.contains(Modifier.SUSPEND) }

    val functions: List<KSFunctionDeclaration> = regularFunctions
    val genericFunctions: List<KSFunctionDeclaration> = allFunctions
      .filter { it.typeParameters.isNotEmpty() }

    val allProperties: List<KSPropertyDeclaration> = allDeclarations
      .filterIsInstance<KSPropertyDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver == null }

    val extensionProperties: List<KSPropertyDeclaration> = allDeclarations
      .filterIsInstance<KSPropertyDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver != null }

    val properties: List<KSPropertyDeclaration> = allProperties
      .filter { !it.modifiers.contains(Modifier.CONST) }
    val constProperties: List<KSPropertyDeclaration> = allProperties
      .filter { it.modifiers.contains(Modifier.CONST) }

    val rootClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { !it.modifiers.contains(Modifier.SEALED) }
      // Issue #54: a subclass declared *beside* its sealed base is a top-level class too, so
      // without this it was collected twice -- once here as a plain namespace-level class with
      // `label_*` exports, once by the ADR-009 sealed route as `Shape.Label` with `shape_label_*`
      // exports. The sealed route owns it.
      .filter { !it.isSealedSubclass() }
      .filter { !it.isValueClass() }

    val rootValueClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { it.isValueClass() }

    // ADR-112: the kind test used to be `classKind == CLASS`, so a sealed INTERFACE fell through
    // to `rootInterfaces` below and was declared as a bare `IShape` nothing could be typed with.
    // An eligible one (`isEligibleSealedType`) enters here instead and renders exactly like a
    // sealed class; an ineligible one stays on the interface route and says why.
    val rootSealedClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.isEligibleSealedType() }

    val rootObjects: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.OBJECT }
      .filter { it.parentDeclaration == null }
      // Issue #110, the object half of the issue #54 rule above: an `object` declared *beside* its
      // sealed base is a top-level object too, so without this it was declared twice -- once by the
      // ADR-009 sealed route as `FlatShape.Loaf`, and once here as an empty namespace-level
      // `public static class Loaf` (CS0101 against the sealed arm, and CS0722 at every position
      // that returns the concrete arm). The sealed route owns it.
      .filter { !it.isSealedSubclass() }

    val rootEnums: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.ENUM_CLASS }
      .filter { it.parentDeclaration == null }
      // ADR-125: the enum half of the same rule, and the reason this bucket used to be the only
      // one without it. An `enum class` arm is refused by `sealedInterfaceIneligibility()`, so
      // nothing reaches this filter today; it stays because it is what turns a future widening
      // mistake into a missing type rather than CS0101 in every consumer's build.
      .filter { !it.isSealedSubclass() }

    val rootInterfaces: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.INTERFACE }
      .filter { it.parentDeclaration == null }
      // ADR-112: an eligible sealed interface is declared by `rootSealedClasses` above as an
      // abstract class. Declaring it here as well would emit both that class and the ADR-040
      // backing wrapper under the same name (CS0101 in every consumer).
      .filter { !it.isEligibleSealedInterface() }
      // ADR-088: a bound interface's ADR-070 stub is now `public`, so without this it would enter
      // the forward export scan and be re-projected as a DUPLICATE `IIFeedable` + backing wrapper
      // (the shipped enum-duplication precedent, deliberately not extended). The classifier maps
      // it to the ORIGINAL `Test.Menagerie.IFeedable` instead, which is only coherent if this
      // pipeline never mints a second managed type for the same concept.
      .filter { it.qualifiedName?.asString() !in context.boundInterfaces }

    // ADR-064 (2026-09-07 amendment): every bucket above keys on CLASS/OBJECT/ENUM_CLASS/
    // INTERFACE, so a public `annotation class` passed `isExported`, landed in `allDeclarations`
    // and was then matched by nothing -- absent from the generated C# with no diagnostic at all.
    // There is no C# projection of a Kotlin annotation worth generating, so it stays absent; it
    // just says so now. Emitted here, before the `hasNothingToProcess` early return below, so a
    // module whose only public declaration is an annotation class still writes it into
    // NugetDiagnostics.json. An `expect annotation class` is dropped by the `isExpect` filter and
    // its `actual` is named here, the same "the actual is the export root" rule ADR-074 uses.
    val rootAnnotationClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.ANNOTATION_CLASS }
      .filter { it.parentDeclaration == null }
    ForwardDiagnosticSink.emit(
      rootAnnotationClasses.map { annotation ->
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS,
          symbol = annotation,
          declaration = annotation.qualifiedName?.asString() ?: annotation.simpleName.asString(),
          reason = "annotation classes are not bridged; there is no C# projection of a Kotlin " +
              "annotation, so nothing is generated for it",
          hint = "usages of it on exported declarations are unaffected. Make it internal, or " +
              "exclude(...) its package, if the warning is unwanted",
          // A whole-type skip: nothing is generated for the annotation and no other declaration
          // lost a member, so there is no owner to name it on.
          owner = null,
        )
      },
      logger,
    )

    // ADR-066: the reachability closure discovers dependency-module (klib) declarations reachable
    // from these module-local roots — the only way in, since `getDeclarationsFromPackage` returns
    // empty for a klib dependency (verified). A discovered declaration is admitted iff it passes
    // the same `isExported` predicate the roots already did, and only when the module
    // crosses into `include`/`rootPackage` scope at all (admission rule 4).
    val reachability: ForwardReachabilityResult = ForwardReachabilityClosure(
      isExported = ::isExportedAndUnmarked,
      isExcluded = { declaration ->
        ownScope.excludes(declaration.packageName.asString(), declaration.qualifiedName?.asString())
      },
      isAdmitted = ::isAdmitted,
      // ADR-154 §2: `admit(...)` opens rule 4's gate too. Neither set still means "never cross the
      // module boundary"; the per-type predicate above is what keeps the crossing bounded.
      crossModuleAdmissionAllowed = effectiveInclude.isNotEmpty() || context.admit.isNotEmpty(),
      actualTypeAliasTargets = actualTypeAliasTargets,
    ).walk(
      classes = rootClasses,
      valueClasses = rootValueClasses,
      sealedClasses = rootSealedClasses,
      objects = rootObjects,
      enums = rootEnums,
      interfaces = rootInterfaces,
      functions = functions + genericFunctions,
      extensionFunctions = extensionFunctions,
      properties = properties + constProperties,
      extensionProperties = extensionProperties,
    )
    if (reachability.admitted.isNotEmpty()) {
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY,
            symbol = null,
            declaration = context.className,
            reason = "the export closure admitted ${reachability.admitted.size} type(s) from " +
                "dependency modules: " +
                reachability.admitted.keys.sorted().joinToString(", "),
            hint = "these are generated exactly like module-local types; " +
                "narrow with exclude(...) if any of them should not be part of the public API",
            // Nothing is skipped: a run-level manifest of what WAS exported.
            owner = null,
          ),
        ),
        logger,
      )
    }

    // ADR-109: the same admitted set, matched against every OTHER forward publisher's export
    // scope in this Gradle build (`nuget.publishedScopes`; this module's own entry was dropped at
    // parse time). ADR-066 generates an admitted dependency type into THIS module's package, over
    // its own opaque handle — so if another published package's scope also covers that type's
    // package, that package declares its own unrelated copy and a consumer referencing both sees
    // two C# types for one Kotlin type. By package is the only match available: a cross-module
    // declaration carries no module identity at all.
    //
    // Nothing is skipped: the type still exports, the generated output is byte-identical, and the
    // remedy is structural. One line per (admitted type, covering publisher).
    val duplicated: List<ForwardDiagnostic> = reachability.admitted.values
      .sortedBy { requireNotNull(it.qualifiedName).asString() }
      .flatMap { cls ->
        val pkg: String = cls.packageName.asString()
        val qualifiedName: String = requireNotNull(cls.qualifiedName).asString()
        context.publishedScopes
          .filter { scope -> scope.covers(pkg, qualifiedName) }
          .map { scope ->
            ForwardDiagnostic(
              kind = ForwardDiagnosticKind.WARNING_DUPLICATED_DEPENDENCY_TYPE,
              // ADR-066, verified: a klib declaration has no `containingFile`, so there is no
              // source location to point the author at.
              symbol = null,
              declaration = qualifiedName,
              reason = "the export closure admitted it from a dependency module, and the " +
                  "${scope.packageId} NuGet package's export scope " +
                  "(rootPackage/include ${scope.scope.include.joinToString { "\"$it\"" }}) also " +
                  "covers it, so ${scope.packageId} declares its own copy (certainly if it is " +
                  "one of ${scope.packageId}'s own types, otherwise whenever " +
                  "${scope.packageId}'s API reaches it) and a consumer referencing both packages " +
                  "sees two unrelated C# " +
                  "types for one Kotlin type, with no conversion between them",
              // ADR-109 is explicit that a shared models NuGet is NOT a remedy and must never be
              // suggested: two published modules are two native libraries in one process, each
              // with its own Kotlin runtime and heap, so an ADR-003 StableRef handle minted in one
              // is meaningless to the other's exports.
              hint = "Kotlin objects cannot cross between two native libraries, so export it " +
                  "from exactly one package: publish a single umbrella module that depends on " +
                  "both, or add exclude(\"$pkg\") to nuget { publish { } } here so only " +
                  "${scope.packageId} declares it (callables reaching it are then skipped with " +
                  "${ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name})",
              // Nothing is skipped and the generated output is byte-identical (the verb says so),
              // so there is no hole to report on a declaration.
              owner = null,
            )
          }
      }
    if (duplicated.isNotEmpty()) ForwardDiagnosticSink.emit(duplicated, logger)

    val dependencyByBucket: Map<ForwardReachabilityBucket, List<KSClassDeclaration>> =
      reachability.admitted.values.groupBy { cls ->
        reachability.bucketOf.getValue(requireNotNull(cls.qualifiedName).asString())
      }

    fun dependenciesIn(bucket: ForwardReachabilityBucket): List<KSClassDeclaration> =
      dependencyByBucket[bucket] ?: emptyList()

    // ADR-133: the root+dependency sets. The nested walk below appends to them, so a nested
    // declaration reaches `exportedObjectHandles`, the planner catalog, the Kotlin export
    // generators and the CIR translator exactly like a top-level one.
    val declaredClasses: List<KSClassDeclaration> =
      rootClasses + dependenciesIn(ForwardReachabilityBucket.CLASS)
    val declaredValueClasses: List<KSClassDeclaration> =
      rootValueClasses + dependenciesIn(ForwardReachabilityBucket.VALUE_CLASS)
    val sealedClasses: List<KSClassDeclaration> =
      rootSealedClasses + dependenciesIn(ForwardReachabilityBucket.SEALED_CLASS)
    val declaredObjects: List<KSClassDeclaration> =
      rootObjects + dependenciesIn(ForwardReachabilityBucket.OBJECT)
    val declaredEnums: List<KSClassDeclaration> =
      rootEnums + dependenciesIn(ForwardReachabilityBucket.ENUM)
    val declaredInterfaces: List<KSClassDeclaration> =
      rootInterfaces + dependenciesIn(ForwardReachabilityBucket.INTERFACE)

    // ADR-133: the owner walk is the SOLE declarer of a nested type. Every root bucket filters
    // `parentDeclaration == null` and the ADR-066 closure feeds a nested dependency declaration to
    // no root list, so a nested declaration enters the pipeline here and nowhere else: declaring it
    // twice would be CS0101 in every consumer (the issue #54/#110 lesson).
    //
    // Excluded before any decision: a sealed subclass (ADR-009 declares it nested under its base),
    // a companion object (ADR-013 folds it into its owner's statics), and an arm of an ineligible
    // sealed interface (ADR-112 warns once for the whole hierarchy).
    val nestedCandidates: List<KSClassDeclaration> =
      (declaredClasses + declaredValueClasses + sealedClasses + declaredObjects +
          declaredInterfaces + declaredEnums)
        .flatMap { owner -> owner.nestedClassDeclarations() }
        .filter { it.getVisibility() == Visibility.PUBLIC }
        .filter { !it.isCompanionObject }
        .filter { !it.isSealedSubclass() }
        .filter { !it.isArmOfIneligibleSealedInterface() }
        .filter { it.classKind in NESTED_DECLARATION_KINDS }
        .distinctBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
        .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }

    // ADR-133 surface 6: Kotlin permits `class Config` beside `val config: Config`; C# does not
    // (CS0102), and it permits a nested type named like its owner, which C# rejects too (CS0542).
    // Fatal and skipped, never emitted: the alternative is C# the consumer cannot compile, with no
    // KSP message naming the Kotlin shape that caused it.
    val nestedCollisions: Map<String, String> = nestedCandidates
      .filter { it.nestedDeclarationDeferral() == null }
      .mapNotNull { nested ->
        val collision: String = nested.nestedOwnerScopeCollision() ?: return@mapNotNull null
        (nested.qualifiedName?.asString() ?: nested.simpleName.asString()) to collision
      }
      .toMap()
    // ADR-133: deferred, not emitted here. `NugetProcessor` stops before writing `CNameExports.kt`
    // once any ERROR_* has fired (ADR-064's gate), and that gate exists for a construct that must
    // never compile. This one is different: the colliding nested type is SKIPPED, so both halves of
    // the generated output are valid and the ABI contract check should still run over them. The
    // error is an authoring failure, so it still fails the consumer's build -- it just fires after
    // the two files are written.
    val nestedCollisionDiagnostics: List<ForwardDiagnostic> = nestedCandidates
      .filter { (it.qualifiedName?.asString() ?: it.simpleName.asString()) in nestedCollisions }
      .map { nested ->
        val name: String = nested.qualifiedName?.asString() ?: nested.simpleName.asString()
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
          symbol = nested.takeIf { it.containingFile != null },
          declaration = name,
          reason = "nested ${nested.nestedDeclarationKind()} `$name` is declared in C# as " +
              "`${nested.nestedCsName()}`, whose name collides with " +
              "${nestedCollisions.getValue(name)}",
          hint = "rename the nested declaration, or the colliding member, so the two names " +
              "differ after PascalCasing",
          // ERROR_*: the build fails, so no consumer ever reads a generated file for it.
          owner = null,
        )
      }

    val (nestedDeclared: List<KSClassDeclaration>, nestedDeferred: List<KSClassDeclaration>) =
      nestedCandidates
        .filter { (it.qualifiedName?.asString() ?: it.simpleName.asString()) !in nestedCollisions }
        .partition { it.nestedDeclarationDeferral() == null }

    // ADR-064's named skip survives for exactly the owner and candidate shapes ADR-133 defers, and
    // the reason now names WHICH shape rather than "only top-level declarations are declared".
    ForwardDiagnosticSink.emit(
      nestedDeferred.map { nested ->
        val name: String = nested.qualifiedName?.asString() ?: nested.simpleName.asString()
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION,
          // ADR-066, verified: a klib declaration has no containing file, so an admitted
          // dependency type's nested declaration has no source location to point at.
          symbol = nested.takeIf { it.containingFile != null },
          declaration = name,
          reason = "nested ${nested.nestedDeclarationKind()} `$name` is not declared in C#: " +
              "${nested.nestedDeclarationDeferral()}",
          hint = "move it to the top level of its file",
          // A whole-declaration skip (ADR-133 defers the nested TYPE, not a member of one): the
          // owner would be the enclosing type, and the memo's deferred list keeps it there until
          // a fixture asks for it.
          owner = null,
        )
      },
      logger,
    )

    val allClasses: List<KSClassDeclaration> =
      // ADR-134: a nested `value class` is a CLASS too, and belongs to the value-class bucket
      // below. Declaring it here as well would emit a handle class beside the record struct.
      declaredClasses +
          nestedDeclared.filter { it.classKind == ClassKind.CLASS && !it.isValueClass() }
    // ADR-134: the nested `value class` candidate, declared as a nested `readonly record struct`
    // under any admitted owner. One list from here on, so the KotlinPoet exports, the plan catalog
    // and the CIR translator cannot disagree about which value classes exist.
    val valueClasses: List<KSClassDeclaration> =
      declaredValueClasses + nestedDeclared.filter { it.isValueClass() }
    val objects: List<KSClassDeclaration> =
      declaredObjects + nestedDeclared.filter { it.classKind == ClassKind.OBJECT }
    val enums: List<KSClassDeclaration> =
      declaredEnums + nestedDeclared.filter { it.classKind == ClassKind.ENUM_CLASS }
    val interfaces: List<KSClassDeclaration> =
      declaredInterfaces + nestedDeclared.filter { it.classKind == ClassKind.INTERFACE }

    // ADR-112: an ineligible sealed interface is still declared as `I<Name>`, and every member
    // typed with it still skips as SKIPPED_SEALED_POSITION, but that skip can only say there is no
    // discriminator -- never why. Named here, once, at the declaration, with the disqualifying
    // reason, before the `hasNothingToProcess` early return so it reaches NugetDiagnostics.json
    // even in a module that generates nothing else.
    ForwardDiagnosticSink.emit(
      interfaces
        .filter { it.isSealedInterface() }
        .mapNotNull { iface ->
          val reason: String = iface.sealedInterfaceIneligibility() ?: return@mapNotNull null
          val name: String = iface.qualifiedName?.asString() ?: iface.simpleName.asString()
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE,
            // ADR-066, verified: a klib declaration has no containing file.
            symbol = iface.takeIf { it.containingFile != null },
            declaration = name,
            reason = "sealed interface `$name` is declared as " +
                "`I${iface.simpleName.asString()}` but cannot be reconstructed in C#: $reason",
            // ADR-125: the reason now always names a C# constraint, so the hint names the
            // constraints too. It used to ask for every subclass to be nested, which is a style
            // rule the renderer never needed and a breaking change for a library whose subtypes
            // are public API on other platforms.
            // ADR-157 drops the enum clause: an `enum class` arm is admitted now, boxed as
            // `{Enum}Arm`. The clause was also being printed on hierarchies whose real problem was
            // a second superclass, which sent the author looking for an enum that was not there.
            hint = "every subclass must be a class or object, or an enum class (boxed as " +
                "`{Enum}Arm`, ADR-157), declared in the interface or beside it, with no other " +
                "superclass, no sub-interface and no second sealed interface. Or declare it as a " +
                "sealed class",
            // The interface IS declared (as `I<Name>`); what is missing is the discriminator, and
            // every member typed with it is named on its own owner by its own position skip.
            owner = null,
          )
        },
      logger,
    )

    // ADR-147: generic classes are ordinary classes now; one bucket, one route.
    val classes: List<KSClassDeclaration> = allClasses

    val hasNothingToProcess: Boolean = functions.isEmpty() && genericFunctions.isEmpty() &&
        extensionFunctions.isEmpty() && extensionProperties.isEmpty() &&
        classes.isEmpty() && enums.isEmpty() &&
        interfaces.isEmpty() && sealedClasses.isEmpty() && objects.isEmpty() &&
        properties.isEmpty() && constProperties.isEmpty() && valueClasses.isEmpty() &&
        suspendFunctions.isEmpty()
    if (hasNothingToProcess) {
      // Issue #55: the diagnostics file is what `nugetReportDiagnostics` re-emits, so the
      // SKIPPED_ALL_DECLARATIONS warning above has to reach it even though nothing else is written.
      writeForwardDiagnostics(Dependencies.ALL_FILES)
      return emptyList()
    }

    // This processor is whole-module by construction: every round rescans
    // resolver.getAllFiles() and rewrites one Interop.cs and one CNameExports.kt from
    // everything. A narrower dependency set (e.g. only the files backing the declaration
    // buckets above) has never been correct here, since it silently drops any bucket an
    // agent forgets to list (see ROADMAP.md Tier 1 suspendFunctions). ALL_FILES gives every
    // incremental round the same semantics as a clean build, the only configuration known
    // to be correct.
    val deps = Dependencies.ALL_FILES

    // Phase 2 shadow migration: construct the source-neutral catalog once while KSP symbols are
    // still available, before either legacy emitter runs. It remains observational for now.
    val exportedObjectHandles: Set<String> = buildSet {
      allClasses.forEach { cls -> cls.qualifiedName?.asString()?.let(::add) }
      enums.forEach { enum -> enum.qualifiedName?.asString()?.let(::add) }
      interfaces.forEach { iface -> iface.qualifiedName?.asString()?.let(::add) }
      sealedClasses.forEach { sealed ->
        sealed.qualifiedName?.asString()?.let(::add)
        sealed.getSealedSubclasses().forEach { subclass ->
          subclass.qualifiedName?.asString()?.let(::add)
        }
      }
      objects.forEach { obj -> obj.qualifiedName?.asString()?.let(::add) }
    }
    // ADR-134: the value classes the renderer declares, nested ones included. Kept out of
    // `exportedObjectHandles` above: a record struct is not a handle, and that set answers a
    // different question for `forwardSuperClass` and the legacy `csTypeArguments` route.
    val exportedValueClasses: Set<String> = buildSet {
      valueClasses.forEach { cls -> cls.qualifiedName?.asString()?.let(::add) }
    }
    val forwardClassifier = ForwardBridgeTypeClassifier(
      ForwardBridgeTypeContext(
        exportedObjectHandles = exportedObjectHandles,
        exportedValueClasses = exportedValueClasses,
        rootPackage = context.rootPackage,
        rootNamespace = context.rootNamespace,
        actualTypeAliasTargets = actualTypeAliasTargets,
        boundInterfaces = context.boundInterfaces,
        refusedDependencyTypes = reachability.refused,
        exportMarkers = context.exportMarkers,
      ),
    )
    val forwardPlanner = ForwardCallablePlanner(forwardClassifier, expects)
    val forwardPropertyPlanner = ForwardPropertyPlanner(forwardClassifier, expects)
    val ordinaryCatalog: ForwardCallablePlanCatalog = forwardPlanner.catalog(
      classes, functions, extensionFunctions, objects, properties, extensionProperties, valueClasses,
      sealedClasses,
    )

    // ADR-040 sub-decision C.1 (reachability-driven): a Kotlin interface gets a concrete backing
    // class + `foo_*` dispatch exports only when it actually appears in a planned *position*
    // (method result, property type, method parameter, extension receiver, including nullable)
    // among the *ordinary* plans built above.
    // Computed before the interface's own members are planned, so there is no ordering cycle: an
    // interface's own members never mention that same interface as their receiver's result type.
    fun BridgeType.interfaceQualifiedNameOrNull(): String? {
      val unwrapped: BridgeType = if (this is BridgeType.Nullable) type else this
      return (unwrapped as? BridgeType.Interface)?.qualifiedName
    }

    // ADR-135's open question, settled by reading the planner: an ADR-132 extension receiver is
    // NOT in `publicSignature.parameters` (`ForwardCallablePlanner` builds `declared` from the
    // value parameters alone and carries the receiver as a separate RECEIVER-role ABI slot), so a
    // receiver-only interface needs this walk over the native calls or it stays unbridged.
    fun List<ForwardNativeCall>.receiverInterfaceQualifiedNames(): List<String> = flatMap { call ->
      call.parameters
        .filter { parameter -> parameter.role == ForwardAbiRole.RECEIVER }
        .mapNotNull { parameter -> parameter.transfer.type.interfaceQualifiedNameOrNull() }
    }

    // ADR-135: the walk covers parameter positions too, which is what ADR-084's Detection rule
    // always claimed ("reachable at a return or parameter position") but the return-only walk
    // never did. Without it, a C# class implementing a parameter-only interface reaches
    // `NugetBridge.HandleFor`'s NotSupportedException arm at runtime instead of a bridge.
    val reachableInterfaceNames: Set<String> = buildSet {
      ordinaryCatalog.plans.forEach { plan ->
        plan.publicSignature.result.interfaceQualifiedNameOrNull()?.let(::add)
        plan.publicSignature.parameters.forEach { parameter ->
          parameter.type.interfaceQualifiedNameOrNull()?.let(::add)
        }
        plan.nativeExports.receiverInterfaceQualifiedNames().forEach(::add)
      }
      ordinaryCatalog.propertyPlans.forEach { plan ->
        plan.type.interfaceQualifiedNameOrNull()?.let(::add)
        // Same receiver reasoning for an extension property over an interface receiver.
        plan.calls().receiverInterfaceQualifiedNames().forEach(::add)
      }
    }
    val reachableInterfaces: List<KSClassDeclaration> = interfaces
      .filter { iface -> iface.qualifiedName?.asString() in reachableInterfaceNames }

    // ADR-162: the interface planning loops are guarded per interface for the same reason the
    // callable planner's `planOrSkip` is: an interface whose planning throws used to abort the
    // round before any other interface was even looked at. A contained one contributes no
    // entries, which is safe here precisely because the round is going to fail at the gate anyway.
    val interfaceEntries: List<ForwardCallableCatalogEntry> = reachableInterfaces.flatMap { iface ->
      guarded(iface.forwardGuardName(), iface, logger) {
        // Issue #249: a REACHABLE interface's entries are stamped here too, not only on the
        // declaration catalog. This catalog is the one that reports for a reachable interface (the
        // declaration catalog's copy carries the same symbol and is suppressed by the symbol
        // guard below), so leaving it unstamped would silently leave `IFoo` with no `<remarks>` in
        // exactly the shape a consumer meets: an interface something returns.
        forwardPlanner.interfaceEntries(iface).ownedBy(iface.forwardDiagnosticOwner())
      }.orEmpty()
    }
    val interfacePropertyPlans: List<ForwardPropertyPlan> = reachableInterfaces.flatMap { iface ->
      guarded(iface.forwardGuardName(), iface, logger) {
        forwardPropertyPlanner.interfaceProperties(iface)
      }.orEmpty()
    }
    val callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(
      entries = ordinaryCatalog.entries + interfaceEntries,
      propertyPlans = ordinaryCatalog.propertyPlans + interfacePropertyPlans,
      // ADR-075: `ordinaryCatalog`'s own planner already folded the class/top-level/extension
      // property setter drops in; `forwardPropertyPlanner` here is the second, separate instance
      // (interface dispatch properties only), so its drops need adding explicitly.
      droppedPropertySetters = ordinaryCatalog.droppedPropertySetters +
          forwardPropertyPlanner.droppedPropertySetters,
      // Same two-instance merge as the setter drops above, for the whole-property channel.
      droppedProperties = ordinaryCatalog.droppedProperties +
          forwardPropertyPlanner.droppedProperties,
      // The interface-dispatch instance has no extension properties of its own, so this merge is
      // only ever the ordinary planner's list today; it is written as a merge anyway so a future
      // second producer does not silently lose its drops.
      droppedExtensionReceivers = ordinaryCatalog.droppedExtensionReceivers +
          forwardPropertyPlanner.droppedExtensionReceivers,
    )

    // ADR-113: a SECOND catalog, planned over every exported interface rather than the reachable
    // subset above, used only to shape the generated `IFoo` declarations. ADR-040 keeps `IFoo`
    // unconditional, so projecting it from `callableCatalog` would empty every interface that is
    // only implemented and never returned. Fresh planner instances: their drop channels are
    // deliberately NOT merged below, or every reachable interface's skip would be reported twice
    // (a reachable interface is planned by both).
    val declarationPlanner = ForwardCallablePlanner(forwardClassifier, expects)
    val declarationPropertyPlanner = ForwardPropertyPlanner(forwardClassifier, expects)

    // ADR-075 amendment (2026-09-13): the UNEXPORTED supertypes of exported classes, planned onto
    // the same declaration catalog. ADR-101 drops `: INesting` from the base list, but the members
    // it declares and the class never implements still have to be spelled on the C# class itself,
    // or the class's own generated subclass renders `public override` against nothing (CS0115
    // inside the generated file). Planning them here means `inheritedAbstractProperty` reads base
    // and override off ONE plan, so the type spelling and the setter's presence cannot drift
    // (CS1715 / CS0534 / CS0546).
    //
    // ADR-075 amendment (2026-09-19): an unexported abstract BASE CLASS, not only an interface.
    // ADR-101 drops `: Cushion()` the same way and re-homes the base's members onto the exported
    // subclass, so an unimplemented `abstract val` there needs the same plan to be spelled from,
    // and a miss then means the planner genuinely refused the type. `kotlin.Any` yields no
    // properties, and `interfaceProperties` keys on `parentDeclaration`, so a concrete base member
    // planned here is simply never looked up (only an unimplemented ABSTRACT member reaches
    // `inheritedAbstractProperty`).
    //
    // Transitive on purpose (`getAllSuperTypes`): an unexported supertype extending another
    // unexported one declares the grandparent's members on the class too, and a dropped
    // INTERMEDIATE base (`Dinghy : Skiff : Vessel`, only `Skiff` unexported) is reached through a
    // kept hop. The lookup key is built from the member's own `parentDeclaration`.
    //
    // Nothing is *rendered* for these supertypes: `translateInterface` is driven by `interfaces`
    // alone, and this catalog reaches only the C# translation, never `generateCNameWrappers` and
    // never the ADR-055 contract check, so no `DllImport` and no Kotlin export follows.
    val unexportedSupertypes: List<KSClassDeclaration> = allClasses
      .asSequence()
      .flatMap { cls -> cls.getAllSuperTypes() }
      .map { it.declaration }
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.classKind == ClassKind.INTERFACE || it.classKind == ClassKind.CLASS }
      .filter { it.qualifiedName?.asString() !in exportedObjectHandles }
      .distinctBy { it.qualifiedName?.asString() }
      .toList()
    // A THIRD planner instance, for the same reason the declaration planner above is a second one:
    // its drop channel must not be merged, or every declared member of an unexported supertype the
    // class implements concretely would be warned about on every build.
    val supertypePropertyPlanner = ForwardPropertyPlanner(forwardClassifier, expects)
    val interfaceDeclarationCatalog = ForwardCallablePlanCatalog(
      // Issue #249: the interface is the C# owner of whatever `IFoo` loses.
      // ADR-162: guarded per interface, same reasoning as the reachable loops above.
      entries = interfaces.flatMap { iface ->
        guarded(iface.forwardGuardName(), iface, logger) {
          declarationPlanner.interfaceEntries(iface).ownedBy(iface.forwardDiagnosticOwner())
        }.orEmpty()
      },
      propertyPlans = interfaces.flatMap { iface ->
        guarded(iface.forwardGuardName(), iface, logger) {
          declarationPropertyPlanner.interfaceProperties(iface)
        }.orEmpty()
      } + unexportedSupertypes.flatMap { supertype ->
        guarded(supertype.forwardGuardName(), supertype, logger) {
          supertypePropertyPlanner.interfaceProperties(supertype)
        }.orEmpty()
      },
      // `docs/backlog/interface-own-dropped-member-diagnosed-nowhere.md`: this planner's own drop
      // channel was built and thrown away, so an interface property `IFoo` silently lost was
      // named in no channel at all.
      droppedProperties = declarationPropertyPlanner.droppedProperties,
    )

    warnDroppedForwardCallables(
      callableCatalog, logger, context.excludePackages, context.strictDependencyTypes,
    )
    warnDroppedForwardPropertySetters(callableCatalog, logger)
    warnDroppedForwardProperties(
      callableCatalog, logger, context.excludePackages, context.strictDependencyTypes,
    )
    warnDroppedForwardExtensionReceivers(callableCatalog, logger)
    warnRefusedLegacyRouteMembers(
      classes, sealedClasses, suspendFunctions, forwardClassifier, logger,
    )
    // ADR-064 amendment (2026-09-13): the structural generic functions, which never reach the
    // planner (see the function's own KDoc).
    warnUnroutedGenericFunctions(genericFunctions, logger)
    // ADR-064 amendment (2026-09-13): an interface's own declared members are planned onto
    // `callableCatalog` only when the interface is REACHABLE (ADR-040), so an unrouted member of
    // an interface that is merely implemented would be named nowhere -- and `classEntries`
    // deliberately defers to the declaration rather than warning once per implementing class.
    // `interfaceDeclarationCatalog` plans every interface, so it is the one producer that sees
    // them all.
    //
    // Issue #249 widened this from `UNROUTED_POSITION` to every genuine drop, which is the
    // backlog item `interface-own-dropped-member-diagnosed-nowhere.md`: `Pounceable.rankTargets`
    // (`Map<String?, Int>`, ADR-083) was dropped from `IPounceable` by `translateInterface` with
    // ZERO records in any channel -- no console line, no JSON entry, and so no remark either. The
    // narrowing this replaces was about double reporting, which the symbol filter below handles:
    // a REACHABLE interface is planned into both catalogs under the SAME symbol, so it is named
    // once. An implementing CLASS reports under its own symbol and keeps doing so -- a different
    // owner is a different hole, and ADR-113's re-report is what a consumer of the class reads.
    val warnedCallableSymbols: Set<String> =
      callableCatalog.droppedCallables.map { it.symbol }.toSet()
    warnDroppedForwardCallables(
      ForwardCallablePlanCatalog(
        entries = interfaceDeclarationCatalog.entries.filter { entry ->
          entry is ForwardCallableCatalogEntry.Skipped &&
              entry.reason.droppedFromCSharp &&
              entry.symbol !in warnedCallableSymbols
        },
      ),
      logger,
      context.excludePackages,
      context.strictDependencyTypes,
    )
    // The property half of the same hole, under the same symbol guard.
    val warnedPropertySymbols: Set<String> =
      callableCatalog.droppedProperties.map { it.symbol }.toSet()
    warnDroppedForwardProperties(
      ForwardCallablePlanCatalog(
        entries = emptyList(),
        droppedProperties = interfaceDeclarationCatalog.droppedProperties
          .filter { dropped -> dropped.symbol !in warnedPropertySymbols },
      ),
      logger,
      context.excludePackages,
      context.strictDependencyTypes,
    )

    val cNameExports: FileSpec = generateCNameWrappers(
      functions, genericFunctions, extensionFunctions, extensionProperties,
      classes, enums, sealedClasses, objects, properties,
      valueClasses, suspendFunctions, callableCatalog, deps, reachableInterfaces,
      exportedObjectHandles, forwardClassifier,
    )
    val bindings: CsharpBindings = generateCSharpBindings(
      functions, genericFunctions, extensionFunctions, extensionProperties,
      allClasses, enums, interfaces, sealedClasses, objects, properties,
      constProperties, valueClasses, suspendFunctions, callableCatalog, deps, reachableInterfaces,
      expects, forwardClassifier, interfaceDeclarationCatalog,
    )

    // ADR-064: an ERROR_* diagnostic (e.g. ERROR_CSHARP_SIGNATURE_COLLISION, ADR-034) already
    // failed this KSP round via logger.error above. Stop here rather than also running the ABI
    // contract checks and writing cNameExports.kt for a construct that must never compile.
    if (logger.hasFatalDiagnostic) return emptyList()

    // ADR-078: the ordinary universe comes off the CIR nodes, the specialized legacy protocols off
    // the same rendered text that was just written to Interop.cs, so both halves of every route are
    // compared rather than only the planned ones.
    val ordinaryContracts: List<ForwardAbiSignature> = ForwardAbiContract.csharp(bindings.cir)
    // ADR-117: which Kotlin declaration composed each export, so a duplicate entry point names its
    // owners rather than only the mangled C symbol.
    val exportOwners: ForwardExportOwners =
      ForwardExportOwners.build(cNameExports, callableCatalog)
    val legacyContracts: ForwardAbiLegacyContracts = ForwardAbiContract.csharpLegacy(
      bindings.rendered,
      ordinaryContracts.map { signature -> signature.exportName }.toSet(),
      exportOwners,
    )
    // A collision is an authoring mistake, not a generator bug: report every owner and stop before
    // the remaining generator-bug `require`s (which would throw on the same duplicates) and before
    // `CNameExports.kt` is written.
    if (legacyContracts.collisions.isNotEmpty()) {
      reportEntryPointCollisions(legacyContracts.collisions)
      return emptyList()
    }
    val csharpContracts: List<ForwardAbiSignature> = ordinaryContracts + legacyContracts.signatures
    val collisions: List<ForwardAbiCollision> = ForwardAbiContract.assertMatches(
      csharp = csharpContracts,
      kotlin = ForwardAbiContract.kotlin(cNameExports, csharpContracts.map { it.exportName }.toSet()),
      owners = exportOwners,
    )
    if (collisions.isNotEmpty()) {
      reportEntryPointCollisions(collisions)
      return emptyList()
    }
    ForwardAbiContract.assertMatchesPlan(
      catalog = callableCatalog,
      csharp = csharpContracts,
      kotlin = ForwardAbiContract.kotlin(
        cNameExports,
        (
            callableCatalog.plans.flatMap { plan -> plan.nativeExports.map { call -> call.exportName } } +
                callableCatalog.propertyPlans.flatMap { plan -> plan.calls().map { call -> call.exportName } }
            ).toSet(),
      ),
    )
    cNameExports.writeTo(codeGenerator, deps)
    // ADR-133: the owner-scope collisions, after both files are written. The colliding nested type
    // was skipped, so the output compiles; the ERROR still fails the consumer build by name.
    if (nestedCollisionDiagnostics.isNotEmpty()) {
      ForwardDiagnosticSink.emit(nestedCollisionDiagnostics, logger)
    }
    writeForwardDiagnostics(deps)

    logger.info(
      "Generated bindings for ${functions.size} functions" +
          ", ${genericFunctions.size} generic functions" +
          ", ${extensionFunctions.size} extension functions" +
          ", ${extensionProperties.size} extension properties" +
          ", ${classes.size} classes" +
          ", ${enums.size} enums" +
          ", ${interfaces.size} interfaces" +
          ", ${sealedClasses.size} sealed classes" +
          ", ${objects.size} objects" +
          ", ${properties.size} properties" +
          ", ${constProperties.size} const properties" +
          ", and ${valueClasses.size} value classes"
    )

    return emptyList()
  }

  /**
   * ADR-100: the delivery channel for forward diagnostics. `KSPLogger.warn` output never reaches
   * the console (KSP runs the processor on a Worker API thread and its stdout is dropped), and a
   * normal `packNuget` does not even run the KSP task (`FROM-CACHE`, then `UP-TO-DATE`), so a
   * transport that only speaks during the task action is silent on most builds. A *declared KSP
   * output file* survives both: it is restored on a cache hit and present on an up-to-date run, and
   * `NugetReportDiagnosticsTask` re-emits it through Gradle's own `Task.logger`.
   *
   * `json` lands in the KSP resources dir (`CodeGeneratorImpl.extensionToDirectory` routes every
   * extension but `class`/`java`/`kt` there), beside `Interop.cs`, which is exactly where the
   * plugin already looks. Written unconditionally, empty array included, so "no file" unambiguously
   * means "KSP never ran for this target" rather than "no skips".
   */
  private fun writeForwardDiagnostics(deps: Dependencies) {
    val json: String = renderForwardDiagnosticsJson(ForwardDiagnosticSink.recorded())
    codeGenerator
      .createNewFile(
        dependencies = deps,
        packageName = "",
        fileName = "NugetDiagnostics",
        extensionName = "json",
      )
      .writer()
      .use { writer -> writer.write(json) }
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    interfaces: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    constProperties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration>,
    suspendFunctions: List<KSFunctionDeclaration>,
    callableCatalog: ForwardCallablePlanCatalog,
    deps: Dependencies,
    reachableInterfaces: List<KSClassDeclaration>,
    expects: ExpectIndex,
    // ADR-114: the same instance the Kotlin half classifies with.
    forwardClassifier: ForwardBridgeTypeClassifier,
    // ADR-113: shapes the `IFoo` declarations only; see the construction site.
    interfaceDeclarationCatalog: ForwardCallablePlanCatalog,
  ): CsharpBindings {
    val translated: CirFile = translate(
      context,
      logger,
      functions,
      genericFunctions,
      classes,
      enums,
      interfaces,
      sealedClasses,
      objects,
      properties,
      constProperties,
      extensionFunctions,
      extensionProperties,
      valueClasses,
      suspendFunctions,
      callableCatalog,
      reachableInterfaces,
      expects,
      forwardClassifier,
      interfaceDeclarationCatalog,
    )

    // ADR-064 amendment (issue #249): every member-level skip, named on the declaration it left a
    // hole in, from the SAME recorded list `NugetDiagnostics.json` is written from -- so the two
    // cannot name different members. A pure post-pass, beside the ADR-150 link resolver below.
    //
    // The ADR-064 husk sweep (2026-09-07, "an absent declaration leaves no husk") runs AFTER it
    // rather than inside `translate`, and spares a holder that carries a remark: a husk with a
    // REASON is no longer indistinguishable from "members still to come", which was that
    // amendment's whole objection. A file with nothing declared and nothing dropped still renders
    // no holder.
    val cirFile: CirFile = translated
      .withSkipRemarks(ForwardDiagnosticSink.recorded()) { pkg ->
        mapPackageToNamespace(pkg, context.rootPackage, context.rootNamespace)
      }
      .withoutEmptyStaticClasses()

    // ADR-150 amendment: the one place a KDoc `[link]` can be checked against the types this file
    // really declares, which is what keeps a CS1574 out of a consumer's build. Post-pass, so no
    // planner, projection or renderer signature knows about link resolution at all.
    val csharp: String = renderer.render(cirFile.resolveDocLinks())

    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "",
      fileName = "Interop",
      extensionName = "cs",
    )

    file.writer().use { writer -> writer.write(csharp) }
    return CsharpBindings(cirFile, csharp)
  }

  /**
   * ADR-117: each collision becomes one `ERROR_C_ENTRY_POINT_COLLISION`, pointing at the first
   * owner's own Kotlin source and naming every owner in its body.
   */
  private fun reportEntryPointCollisions(collisions: List<ForwardAbiCollision>) {
    ForwardDiagnosticSink.emit(
      collisions.map { collision ->
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION,
          symbol = collision.symbol,
          declaration = collision.declaration,
          reason = collision.reason,
          hint = collision.hint,
          // ERROR_*: the round fails before `CNameExports.kt` is written.
          owner = null,
        )
      },
      logger,
    )
  }

  private fun generateCNameWrappers(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration>,
    suspendFunctions: List<KSFunctionDeclaration>,
    callableCatalog: ForwardCallablePlanCatalog,
    deps: Dependencies,
    reachableInterfaces: List<KSClassDeclaration>,
    // ADR-101 amendment: `exportedObjectHandles`, threaded so `addClassExports` can ask the gated
    // `forwardSuperClass(exportedTypes)` predicate — the same set, bucket for bucket, the two
    // planners ask, so the Kotlin half cannot drift from the C# half about a dropped base class.
    exportedTypes: Set<String>,
    // ADR-114: the legacy Flow/suspend export builders classify their own generic parameters.
    forwardClassifier: ForwardBridgeTypeClassifier,
  ): FileSpec {
    val builder: FileSpec.Builder = FileSpec
      .builder("io.github.xxfast.kotlin.native.nuget.generated", "CNameExports")
      .addImport("kotlinx.cinterop", "asStableRef")
      .addImport("kotlinx.cinterop", "COpaquePointerVar")
      .addImport("kotlinx.cinterop", "reinterpret")
      .addImport("kotlinx.cinterop", "pointed")
      .addImport("kotlinx.cinterop", "value")
      .addImport("kotlinx.cinterop", "StableRef")

    // ADR-127: the fixed `nuget_*` block lives in the `nuget-runtime` klib. The generated file
    // calls into it by name, so the imports are added here, unconditionally, for every module.
    // Unconditional is the point: the `needs*` gating these used to sit behind is the defect
    // class (ADR-068, ADR-071, ADR-075, ADR-124) that moving the block removes by construction.
    NUGET_RUNTIME_MEMBERS.forEach { member -> builder.addImport(NUGET_RUNTIME_PACKAGE, member) }

    // ADR-127's skew guard: one line naming the runtime's ABI major. A runtime with a different
    // major renames the object, and the consumer's compile fails with an unresolved reference
    // naming it, which is earlier and cheaper than any startup check.
    builder.addProperty(
      PropertySpec
        .builder(
          "nugetRuntimeAbi",
          ClassName(NUGET_RUNTIME_PACKAGE, "NugetRuntimeAbi1"),
          KModifier.PRIVATE,
        )
        .addAnnotation(
          AnnotationSpec.builder(ClassName("kotlin", "Suppress"))
            .addMember("%S", "unused")
            .build()
        )
        .initializer("%T", ClassName(NUGET_RUNTIME_PACKAGE, "NugetRuntimeAbi1"))
        .build()
    )

    // ADR-162: the Kotlin half's containment seam. One guard per declaration per adder call, which
    // is exactly the granularity the `exports/*` builders iterate at: an emitter invariant that a
    // legal shape reaches is reported against that declaration's own source location and the loop
    // keeps going, so every offending declaration of the round is named in ONE build. The round
    // still stops at the fatal-diagnostic gate before this FileSpec is written, so a half-built
    // export set never ships.
    fun guardDeclaration(declaration: KSDeclaration, block: () -> Unit) {
      guarded(declaration.forwardGuardName(), declaration, logger, block)
    }

    functions.forEach { func ->
      guardDeclaration(func) {
        // ADR-095: node identity, not a name-derived symbol — top-level overloads number per
        // (package, name), so the n-th namesake's plan is keyed `..._$n`.
        // ADR-096: plural — a defaulted top-level function also carries its synthesized omitting
        // overloads on the same node.
        val planned: List<ForwardCallablePlan> = callableCatalog.plansFor(func)
        // ADR-064: the import goes behind the gate, never ahead of it. A skipped function used to
        // leave a line importing a symbol the generated file never mentions. The legacy route
        // imports its own, after its own early returns.
        if (planned.isNotEmpty()) {
          builder.addImport(func.packageName.asString(), func.simpleName.asString())
          planned.forEach { builder.addForwardKotlinPlanExport(it) }
        } else {
          builder.addFunctionExports(func)
        }
      }
    }

    genericFunctions.forEach { func ->
      // The import lives inside addGenericFunctionExports, behind its own gate (ADR-064).
      guardDeclaration(func) { builder.addGenericFunctionExports(func) }
    }

    classes.forEach { cls ->
      guardDeclaration(cls) {
        builder.addClassExports(cls, callableCatalog, forwardClassifier, exportedTypes)
      }
    }
    classes.forEach { cls ->
      guardDeclaration(cls) { builder.addCompanionExports(cls, callableCatalog) }
    }
    enums.forEach { enum -> guardDeclaration(enum) { builder.addEnumExports(enum) } }
    sealedClasses.forEach { sealed ->
      guardDeclaration(sealed) {
        builder.addSealedClassExports(sealed, callableCatalog, context.exportMarkers)
      }
    }
    objects.forEach { obj ->
      guardDeclaration(obj) { builder.addObjectExports(obj, callableCatalog) }
    }
    valueClasses.forEach { cls ->
      guardDeclaration(cls) { builder.addValueClassExports(cls, callableCatalog) }
    }
    reachableInterfaces.forEach { iface ->
      guardDeclaration(iface) { builder.addInterfaceExports(iface, callableCatalog) }
    }
    // ADR-084 stage 1: the per-interface bridge factory, projected from the same slot plan the C#
    // `{Iface}BridgeState` is projected from (see `ForwardInterfaceBridgePlanner`).
    val bridgePlans: List<ForwardBridgeInterfacePlan> =
      reachableInterfaces.mapNotNull { iface ->
        guarded(iface.forwardGuardName(), iface, logger) {
          ForwardInterfaceBridgePlanner.plan(iface, forwardClassifier)
        }
      }
    bridgePlans.forEach { plan -> builder.addInterfaceBridgeFactoryExport(plan) }
    // ADR-127: `nuget_gc_collect`, `nuget_csharp_token` and the `NugetCSharpBridge` marker moved
    // to the `nuget-runtime` klib, which exports all three unconditionally. The generated bridge
    // objects still implement the marker; it is imported, not declared, now.

    val suspendLambdaTypes: Set<String> = setOf(
      "kotlin.coroutines.SuspendFunction0",
      "kotlin.coroutines.SuspendFunction1",
      "kotlin.coroutines.SuspendFunction2",
      "kotlin.coroutines.SuspendFunction3",
    )

    fun KSType.isSuspendLambdaType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in suspendLambdaTypes

    fun KSType.suspendLambdaArity(): Int =
      expandAliases().arguments.size - 1

    val suspendLambdaArities: MutableSet<Int> = mutableSetOf()

    classes.forEach { cls ->
      cls.getAllProperties().forEach { prop ->
        val propType: KSType = prop.type.resolve()
        if (propType.isSuspendLambdaType()) suspendLambdaArities.add(propType.suspendLambdaArity())
      }
    }

    val needsSuspendLambdaSupport: Boolean = suspendLambdaArities.isNotEmpty()

    val classesHaveSuspendFunctions: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) }
    }

    // ADR-118: an arm's suspend export needs the same coroutine/cinterop imports a class's does,
    // and without it an arm's `GetOrCreateScope()` calls a `nuget_scope_create` that was never
    // exported -- an EntryPointNotFoundException at the first await.
    val armsHaveSuspendMethods: Boolean = sealedClasses.any { sealed ->
      sealed.getSealedSubclasses().any { subclass -> subclass.declaresSuspendMember() }
    }

    val hasSuspendFunctions: Boolean = suspendFunctions.isNotEmpty() ||
        needsSuspendLambdaSupport ||
        classesHaveSuspendFunctions ||
        armsHaveSuspendMethods

    val classesHaveFlowPropertiesForImports: Boolean = classes.any { cls ->
      cls.getAllProperties().any { prop ->
        val qualified: String? =
          prop.type.resolve().expandAliases().declaration.qualifiedName?.asString()
        qualified == "kotlinx.coroutines.flow.Flow" || qualified in STATE_FLOW_TYPES
      }
    }

    val classesHaveFlowMethodsForImports: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        val qualified: String? =
          method.returnType?.resolve()?.expandAliases()?.declaration?.qualifiedName?.asString()
        qualified == "kotlinx.coroutines.flow.Flow" || qualified in STATE_FLOW_TYPES
      }
    }

    // ADR-124: an arm's flow surface needs the same coroutines/cinterop imports a class's does.
    val armsHaveFlowMembers: Boolean = sealedClasses.any { sealed ->
      sealed.getSealedSubclasses().any { subclass ->
        subclass.declaresOrInheritsFlowMember(forwardClassifier)
      }
    }

    val needsFlowImports: Boolean = classesHaveFlowPropertiesForImports ||
        classesHaveFlowMethodsForImports || armsHaveFlowMembers

    // The coroutines opt-in is gated on the SAME condition as the coroutines imports below: every
    // emission that names anything from `kotlinx.coroutines` (suspend functions and suspend
    // lambdas, `Flow`/`StateFlow`, the scope helpers) sits under it. Naming the marker
    // unconditionally forced a library with no suspend/`Flow` surface to depend on
    // `kotlinx-coroutines-core` just to resolve an annotation it never otherwise needed.
    val optIns: List<ClassName> = buildList {
      add(ClassName("kotlin.experimental", "ExperimentalNativeApi"))
      add(ClassName("kotlinx.cinterop", "ExperimentalForeignApi"))
      // ADR-127: every runtime declaration the generated file calls is behind this marker.
      add(ClassName(NUGET_RUNTIME_PACKAGE, "NugetRuntimeApi"))
      if (hasSuspendFunctions || needsFlowImports) {
        add(ClassName("kotlinx.coroutines", "ExperimentalCoroutinesApi"))
      }
      // ADR-115 amendment: a waived marker puts a marked declaration back into `CNameExports.kt`,
      // and at `RequiresOptIn.Level.ERROR` reading one without opting in does not compile. That is
      // issue #113's original failure, so waiving a marker has to carry the opt-in with it. Every
      // configured marker is named, whether or not a declaration behind it survived the export
      // scope: an unused opt-in is a warning at most, a missing one is a build break.
      context.exportMarkers.forEach { marker -> add(ClassName.bestGuess(marker)) }
    }

    builder.addAnnotation(
      AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
        .addMember(
          optIns.joinToString(", ") { "%T::class" },
          *optIns.toTypedArray(),
        )
        .build()
    )

    val lambdaTypeSet: Set<String> = setOf(
      "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
    )

    // ADR-116 amendment (2026-09-11): a sealed arm owns the per-call callback route too, and a
    // sealed class is not in `classes` (ADR-009). Without the arm walk a module whose only
    // callback owner is an arm generates `fn.invoke(...)` with no `invoke`/`CFunction`/
    // `COpaquePointer` import: a compile error in the generated file, invisible in `test-library`
    // only because its suspend and flow surface imports the same three names anyway.
    val armsHaveLambdaParamMethods: Boolean = sealedClasses.any { sealed ->
      sealed.getSealedSubclasses().any { subclass ->
        subclass.forwardArmLambdaMethods(forwardClassifier).isNotEmpty()
      }
    }

    val classesHaveLambdaParamMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.parameters.any { param ->
          param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in lambdaTypeSet
        }
      }
    }

    // ADR-160: the plan owns per-call callbacks at owners `classes` does not list (a sealed arm, a
    // top-level function, an extension), and the arm selector above no longer sees a planned member
    // at all, so the three imports are gated on the CATALOG as well. Same defect class the ADR-116
    // amendment above fixed for the arm: without this a module whose only callback owner is a
    // planned arm or top-level member emits `reinterpret<CFunction<...>>` with no import.
    val plansHaveCallbackParameters: Boolean = callableCatalog.plans.any { plan ->
      plan.publicSignature.parameters.any { parameter -> parameter.type is BridgeType.Callback }
    }

    val hasLambdaParamMethods: Boolean = armsHaveLambdaParamMethods ||
        classesHaveLambdaParamMethods || plansHaveCallbackParameters

    // ADR-116 amendment (2026-09-13): the arm half of both gates below. A sealed class is not in
    // `classes` (ADR-009), so a module whose only callback owner is a pair-bearing arm would emit
    // `fn.invoke(...)` with no `invoke`/`CFunction`/`COpaquePointer` import — a compile error in
    // the generated file, and `armsHaveLambdaParamMethods` above cannot stand in for it because
    // its selector excludes the pairs on purpose.
    val armsHaveStoredCallbackPairs: Boolean = sealedClasses.any { sealed ->
      sealed.getSealedSubclasses().any { subclass ->
        subclass.forwardArmStoredCallbackPairs(forwardClassifier).isNotEmpty()
      }
    }

    val armsHaveInterfaceBridgePairs: Boolean = sealedClasses.any { sealed ->
      sealed.getSealedSubclasses().any { subclass ->
        subclass.forwardArmInterfaceBridgePairs(forwardClassifier).isNotEmpty()
      }
    }

    // Stored-callback pairs also need invoke/CFunction/COpaquePointer (the bridge lambda calls fn.invoke).
    val hasStoredCallbackMethods: Boolean = armsHaveStoredCallbackPairs || classes.any { cls ->
      val lambdaParamMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
        .filter { method ->
          method.parameters.any { param ->
            param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in lambdaTypeSet
          }
        }.toList()
      findStoredCallbackPairs(lambdaParamMethods).isNotEmpty()
    }

    // Interface-bridge pairs also need invoke/CFunction/COpaquePointer (each method's fn.invoke).
    val hasInterfaceBridgeMethods: Boolean = armsHaveInterfaceBridgePairs || classes.any { cls ->
      val allMethods: List<KSFunctionDeclaration> = cls.getAllFunctions().toList()
      findInterfaceBridgePairs(allMethods).isNotEmpty()
    }

    // ADR-084: every bridge factory slot is a `fn.invoke(...)` on a reinterpreted CFunction too.
    val hasBridgeFactories: Boolean = bridgePlans.isNotEmpty()

    // ADR-084 stage 2: the bridge object's cleaner.
    if (hasBridgeFactories) builder.addImport("kotlin.native.ref", "createCleaner")

    val needsCallbackImports: Boolean = hasLambdaParamMethods || hasStoredCallbackMethods ||
        hasInterfaceBridgeMethods || hasBridgeFactories
    // ADR-128: the callback route owns these three itself now. It used to be gated on the suspend
    // block *not* running, i.e. it relied on that block to supply `CFunction`/`invoke`/
    // `COpaquePointer` whenever a suspend or Flow member happened to be present. The suspend block
    // no longer needs them for its own text (the launch shape moved into the runtime helper), so
    // the coupling has to go first; KotlinPoet de-duplicates an import added twice.
    if (needsCallbackImports) {
      builder.addImport("kotlinx.cinterop", "invoke")
      builder.addImport("kotlinx.cinterop", "CFunction")
      builder.addImport("kotlinx.cinterop", "COpaquePointer")
    }

    if (hasSuspendFunctions || needsFlowImports) {
      builder.addImport("kotlinx.cinterop", "reinterpret")
      builder.addImport("kotlinx.cinterop", "invoke")
      builder.addImport("kotlinx.cinterop", "CFunction")
      builder.addImport("kotlinx.cinterop", "COpaquePointer")
      builder.addImport("kotlinx.cinterop", "StableRef")
      // ADR-128: the two runtime helpers the suspend and Flow bodies now call. Gated with the rest
      // of the coroutine surface rather than added to `NUGET_RUNTIME_MEMBERS`: both name
      // `CoroutineScope`, and a module with no suspend/Flow surface must keep compiling with
      // `kotlinx-coroutines-core` absent entirely (`Tier1CoroutineFreeModuleTest`).
      builder.addImport(NUGET_RUNTIME_PACKAGE, "launchForCSharp")
      builder.addImport(NUGET_RUNTIME_PACKAGE, "collectForCSharp")
      builder.addImport("kotlinx.coroutines", "CoroutineScope")
      builder.addImport("kotlinx.coroutines", "Dispatchers")
      builder.addImport("kotlinx.coroutines", "ExperimentalCoroutinesApi")
    }

    if (needsSuspendLambdaSupport) {
      builder.addImport("kotlin.coroutines", "SuspendFunction0")
      builder.addImport("kotlin.coroutines", "SuspendFunction1")
      builder.addImport("kotlin.coroutines", "SuspendFunction2")
      builder.addImport("kotlin.coroutines", "SuspendFunction3")
    }

    suspendFunctions.forEach { func ->
      // The import lives inside addSuspendFunctionExports, behind its legacy-refusal gates
      // (ADR-064): a refused suspend function used to leave a dead import behind.
      guardDeclaration(func) { builder.addSuspendFunctionExports(func, forwardClassifier) }
    }

    classes.forEach { cls ->
      guardDeclaration(cls) {
        // ADR-159: the same selector the export builder and the C# half read, so this gate cannot
        // admit a class whose suspend members all belong to a kept base (which is how
        // `paddedwindowseat_settle_async` used to be exported with no C# import behind it).
        val hasSuspendRouteMethods: Boolean = cls.forwardSuspendRouteMethods(
          forwardClassifier,
          cls.forwardSuperClass(exportedTypes),
        ).isNotEmpty()
        if (hasSuspendRouteMethods) {
          builder.addSuspendClassMethodExports(
            cls,
            forwardClassifier,
            callableCatalog,
            exportedTypes = exportedTypes,
          )
        }
      }
    }

    // ADR-118: a sealed arm is an owner of the legacy suspend route too, under the export prefix
    // its getters and `_dispose` already use. Declared-only, the same gate the planner's
    // `sealedSubclassEntries` and `translateSealedClass` apply, so all three halves agree on which
    // members exist.
    sealedClasses.forEach { sealed ->
      val sealedPrefix: String = sealed.simpleName.asString().lowercase()
      sealed.getSealedSubclasses().forEach { subclass ->
        // ADR-162: guarded on the ARM, which is the declaration the author would have to change.
        guardDeclaration(subclass) {
          if (!subclass.declaresSuspendMember()) return@guardDeclaration
          builder.addSuspendClassMethodExports(
            cls = subclass,
            classifier = forwardClassifier,
            callableCatalog = callableCatalog,
            prefix = "${sealedPrefix}_${subclass.simpleName.asString().lowercase()}",
            declaredOnly = true,
          )
        }
      }
    }

    // ADR-124: the sealed arm is an owner of the legacy Flow/StateFlow route too, under the same
    // export prefix its getters and `_dispose` use. Properties are all-properties (ADR-111's
    // `superClass = null`: the generated C# base is abstract and carries no members, so a
    // base-declared flow property has to bind on every arm), methods declared-only (ADR-116/118).
    // Both rules live in `FlowExports`, so this loop, the two gates below and `translateSealedClass`
    // cannot drift about which members exist.
    sealedClasses.forEach { sealed ->
      val sealedPrefix: String = sealed.simpleName.asString().lowercase()
      sealed.getSealedSubclasses().forEach { subclass ->
        guardDeclaration(subclass) {
          val subQualifiedName: String =
            subclass.qualifiedName?.asString() ?: return@guardDeclaration
          val armPrefix: String =
            "${sealedPrefix}_${subclass.simpleName.asString().lowercase()}"
          val armFlowProperties: List<KSPropertyDeclaration> =
            subclass.forwardArmFlowProperties(forwardClassifier)
          val armFlowMethods: List<KSFunctionDeclaration> =
            subclass.forwardArmFlowMethods(forwardClassifier)
          if (armFlowProperties.isEmpty() && armFlowMethods.isEmpty()) return@guardDeclaration
          armFlowProperties.forEach { prop ->
            builder.addFlowPropertyExports(prop, subQualifiedName, armPrefix, forwardClassifier)
          }
          armFlowMethods.forEach { method ->
            builder.addFlowMethodExports(
              method, subQualifiedName, armPrefix, forwardClassifier, callableCatalog,
            )
          }
        }
      }
    }

    // ADR-116 amendment (2026-09-11): and the third legacy route on the arm, the per-call
    // lambda-parameter one (ADR-036), under the same `${sealedPrefix}_${sub}` prefix.
    // `addLambdaParamMethodExport` is already prefix-keyed, so the arm needs no export shape of
    // its own; `forwardArmLambdaMethods` is the single selector this loop, the import gate above
    // and `translateSealedClass` all read.
    sealedClasses.forEach { sealed ->
      val sealedPrefix: String = sealed.simpleName.asString().lowercase()
      sealed.getSealedSubclasses().forEach { subclass ->
        guardDeclaration(subclass) {
          val subQualifiedName: String =
            subclass.qualifiedName?.asString() ?: return@guardDeclaration
          val armPrefix: String = "${sealedPrefix}_${subclass.simpleName.asString().lowercase()}"
          val armLambdaMethods: List<KSFunctionDeclaration> =
            subclass.forwardArmLambdaMethods(forwardClassifier)
          if (armLambdaMethods.isEmpty()) return@guardDeclaration
          armLambdaMethods.forEach { method ->
            builder.addLambdaParamMethodExport(method, subQualifiedName, armPrefix)
          }
        }
      }
    }

    // ADR-116 amendment (2026-09-13): the fourth legacy route on the arm, the stored-callback
    // (ADR-037) and interface-bridge (ADR-039) `addX`/`removeX` **pairs**, under the same
    // `${sealedPrefix}_${sub}` prefix. Both builders are already `(add, remove, qualifiedName,
    // prefix)`-keyed and mint their own `ownedBy(...)` owner, so the arm needs no export shape of
    // its own; `forwardArmStoredCallbackPairs` / `forwardArmInterfaceBridgePairs` are the two
    // selectors this loop, the import gates below and `translateSealedClass` all read.
    sealedClasses.forEach { sealed ->
      val sealedPrefix: String = sealed.simpleName.asString().lowercase()
      sealed.getSealedSubclasses().forEach { subclass ->
        guardDeclaration(subclass) {
          val subQualifiedName: String =
            subclass.qualifiedName?.asString() ?: return@guardDeclaration
          val armPrefix: String = "${sealedPrefix}_${subclass.simpleName.asString().lowercase()}"
          val storedPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
            subclass.forwardArmStoredCallbackPairs(forwardClassifier)
          val bridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
            subclass.forwardArmInterfaceBridgePairs(forwardClassifier)
          if (storedPairs.isEmpty() && bridgePairs.isEmpty()) return@guardDeclaration
          storedPairs.forEach { (addMethod, removeMethod) ->
            builder.addStoredCallbackExports(addMethod, removeMethod, subQualifiedName, armPrefix)
          }
          bridgePairs.forEach { (addMethod, removeMethod) ->
            builder.addInterfaceBridgeExports(addMethod, removeMethod, subQualifiedName, armPrefix)
          }
        }
      }
    }

    properties.forEach { prop ->
      // The import lives inside addPropertyExports, behind the plan gate (ADR-064), so the gate
      // and the import cannot drift apart.
      guardDeclaration(prop) { builder.addPropertyExports(prop, callableCatalog) }
    }

    extensionFunctions.forEach { func ->
      // The import lives inside addExtensionFunctionExports, behind the plan gate (ADR-064).
      guardDeclaration(func) { builder.addExtensionFunctionExports(func, callableCatalog) }
    }

    // The import lives inside addExtensionPropertyExports, behind the plan gate: adding it here
    // left a dead import for every dropped extension property.
    extensionProperties.forEach { prop ->
      guardDeclaration(prop) { builder.addExtensionPropertyExports(prop, callableCatalog) }
    }


    val flowTypes: Set<String> = setOf("kotlinx.coroutines.flow.Flow") + STATE_FLOW_TYPES

    fun KSType.isFlowType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in flowTypes

    val classesHaveFlowProperties: Boolean = classes.any { cls ->
      cls.getAllProperties().any { prop -> prop.type.resolve().isFlowType() }
    }

    val classesHaveFlowMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.returnType?.resolve()?.isFlowType() == true
      }
    }

    // ADR-124: `armsHaveFlowMembers` carries the arm half. Without it an arm's
    // `GetOrCreateScope()` calls a `nuget_scope_create` that was never exported, which is an
    // EntryPointNotFoundException at the first collect.
    val needsFlowSupport: Boolean = classesHaveFlowProperties || classesHaveFlowMethods ||
        armsHaveFlowMembers

    if (needsFlowSupport) builder.addImport("kotlinx.coroutines.flow", "collect")

    // ADR-127: `nuget_stateflow_collect` / `nuget_stateflow_value` moved to the runtime klib.

    return builder.build()
  }

}

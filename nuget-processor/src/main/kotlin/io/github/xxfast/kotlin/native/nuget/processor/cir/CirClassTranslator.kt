package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.csharpParameterName
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmFlowMethods
import io.github.xxfast.kotlin.native.nuget.processor.exports.isForwardFlowType
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOptInRefused
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallableCatalogEntry
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPlanProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPropertyProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpName
import io.github.xxfast.kotlin.native.nuget.processor.forward.declaredSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardPublicCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardLegacyAsyncRoute
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSupertypeNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardMemberOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOpenForOverride
import io.github.xxfast.kotlin.native.nuget.processor.forward.overridesBaseClassMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyCollectionRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementCollection
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementReadArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedFlowElement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/** Which half of issue #42 a dropped supertype is: the two lose genuinely different things, so
 *  they get genuinely different messages (an interface carries nothing C# could have called; a
 *  base class carries members that are re-homed onto the subclass). */
private enum class SupertypeKind { INTERFACE, BASE_CLASS }

/**
 * ADR-101 (+ its 2026-09-05 base-class amendment): is [supertype] one the generated C# base list
 * may name? Only if the export set carries it — `exportedTypes` is qualified-name keyed
 * (`CirTranslator.kt`), the same membership test every sibling site in this package uses. An
 * unexported supertype is dropped with a WARNING rather than rendered into a dangling `: IFoo` /
 * `: Foo` that fails to compile (CS0246, the reporter's case).
 *
 * The `include(...)` advice is deliberately narrow, because it was measured
 * (`Tier1UnexportedSupertypeSkipTest`): the ADR-066 reachability closure walks returns,
 * parameters, property types, type arguments, sealed subclasses and primary-ctor parameters but
 * never `superTypes`, so a *dependency* type reachable only as a supertype stays out of the
 * export set even after its package is included. A supertype declared in *this* module is a
 * different case (scope admits same-round source declarations directly), so the base-class hint
 * picks its clause from `supertype.containingFile` rather than hedging across both.
 */
private fun keepsSupertype(
  cls: KSClassDeclaration,
  name: String,
  supertype: KSClassDeclaration,
  kind: SupertypeKind,
  exportedTypes: Set<String>,
  logger: KSPLogger,
): Boolean {
  val qualified: String? = supertype.qualifiedName?.asString()
  if (qualified != null && qualified in exportedTypes) return true

  val simpleName: String = supertype.simpleName.asString()
  val supertypeName: String = qualified ?: simpleName
  val packageName: String = supertype.packageName.asString()
  val reason: String = when (kind) {
    SupertypeKind.INTERFACE ->
      "supertype '$supertypeName' is not in the export set, so it has no generated C# " +
          "interface; the class is generated without it and its own members still export"

    SupertypeKind.BASE_CLASS ->
      "base class '$supertypeName' is not in the export set, so it has no generated C# class; " +
          "$name is generated with no base at all and the base's public members are bound on " +
          "$name directly"
  }
  val hint: String = when (kind) {
    SupertypeKind.INTERFACE ->
      "an unexported supertype carries no members the C# side could call, so nothing " +
          "is lost; note that include(\"...\") does not help here — the export reachability " +
          "closure never walks supertypes"

    // ADR-101's 2026-09-11 amendment: which of the two clauses is true here is decided by
    // `containingFile`, the same cross-module signal the reachability closure keys on
    // (`ForwardReachabilityClosure.kt`), so the author is told the one fix that works for
    // *their* base instead of both halves of a hedge.
    SupertypeKind.BASE_CLASS -> {
      val lost: String =
        "nothing callable is lost ($simpleName's public members export as members of " +
            "$name), but C# sees no $simpleName type and no inheritance relation, so `is`/`as` " +
            "against it and any other subclass's shared base are gone; "
      if (supertype.containingFile == null) {
        lost + "$simpleName is declared in a dependency, and include(\"$packageName\") alone " +
            "will not admit it: the export reachability closure never walks supertypes, so it " +
            "enters the export set only when an exported member also names it as a return, " +
            "parameter or property type and its package is included"
      } else {
        lost + "$simpleName is declared in this module, so adding include(\"$packageName\") " +
            "alongside your existing rootPackage/include(...) admits it and renders it as the " +
            "C# base"
      }
    }
  }
  ForwardDiagnosticSink.emit(
    listOf(
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE,
        symbol = cls,
        declaration = "$name : $simpleName",
        reason = reason,
        hint = hint,
      ),
    ),
    logger,
  )
  return false
}

/** Does the author's own Kotlin declaration offer a constructor C# could have called? */
private fun KSClassDeclaration.hasPublicConstructor(): Boolean =
  getConstructors().any { it.getVisibility() == Visibility.PUBLIC }

/**
 * ROADMAP Phase 3: every public constructor of [cls] was skipped, so the generated C# type has
 * only its `internal $name(IntPtr handle)`. The type is kept on purpose (see
 * [ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR]); this says so, and names each
 * constructor with the reason it went.
 *
 * The reasons come off the catalog's skipped entries rather than being re-derived: they are the
 * planner's own verdicts, including the legacy-route deferrals `droppedCallables` filters out.
 * A class whose constructors never reached the planner at all (no skipped entry, no plan) still
 * warns, naming the count instead.
 *
 * Returns that detail string so the class's `<remarks>` doc comment (ADR-064 amendment,
 * 2026-09-10) names the same constructors and the same reasons as the build log, off one catalog
 * query. Two queries would be two chances to drift.
 */
private fun warnNoPublicConstructor(
  cls: KSClassDeclaration,
  name: String,
  callableCatalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
): String {
  val skipped: List<ForwardCallableCatalogEntry.Skipped> =
    callableCatalog.skippedConstructors(cls.qualifiedName?.asString() ?: name)
  val declared: Int = cls.getConstructors().count { it.getVisibility() == Visibility.PUBLIC }
  val detail: String = if (skipped.isEmpty()) {
    "all $declared of them, see the SKIPPED_* lines above"
  } else {
    skipped.joinToString { entry ->
      "${entry.symbol.substringAfterLast('.')}: ${entry.reason.name}"
    }
  }
  ForwardDiagnosticSink.emit(
    listOf(
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR,
        symbol = cls,
        declaration = name,
        reason = "every public constructor is skipped ($detail), so the generated C# class has " +
            "only its internal handle constructor and C# cannot construct one",
        hint = "the type is kept because instances can still come from Kotlin factories that " +
            "return it (a top-level function, or a companion factory); expose one, or change " +
            "the constructor parameters to types the bridge can express",
      ),
    ),
    logger,
  )
  return detail
}

/**
 * The consumer-facing half of [ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR]: what a C#
 * developer reads in IntelliSense on a class only Kotlin can hand them. The diagnostic's own hint
 * ("expose one, or change the constructor parameters") is author-facing and useless downstream,
 * but [detail]'s reason codes stay in: they are the search key back to the Gradle log and to the
 * ADR-064 catalogue.
 */
private fun noPublicConstructorRemark(name: String, detail: String): String =
  "Cannot be constructed from C#: every Kotlin constructor of $name was skipped by the bridge " +
      "($detail). Instances come from Kotlin factories that return this type."

/**
 * ADR-075 amendment (2026-09-11): the C# declaration for a property an exported abstract class
 * inherits from an exported interface and never implements, the property-side mirror of the
 * `abstractMethods` walk in [translateClass].
 *
 * There is no plan and no Kotlin export: `isForwardPlannableMemberOf` keeps an unimplemented
 * inherited member out of the planner, since a bridge getter would have nothing to dispatch to.
 * The C# type is therefore read off [interfaceDeclarationCatalog], the same ADR-113 plan
 * `translateInterface` spells `IFoo`'s member from, rather than hand-mapped here: a second
 * spelling of one plan is what CS0738 is made of.
 *
 * Null when the declaring interface's own planner skipped the member (so `IFoo` does not declare
 * it either) or the parent is not a class declaration. An unexported interface is out of scope:
 * ADR-101 drops `: IFoo` from the base list, and there is no plan to spell the member from.
 */
private fun inheritedAbstractProperty(
  prop: KSPropertyDeclaration,
  propName: String,
  interfaceDeclarationCatalog: ForwardCallablePlanCatalog,
): CirProperty? {
  val owner: KSClassDeclaration = prop.parentDeclaration as? KSClassDeclaration ?: return null
  val qualified: String = owner.qualifiedName?.asString() ?: return null
  val plan: ForwardPropertyPlan =
    interfaceDeclarationCatalog.propertyFor("$qualified.$propName") ?: return null
  return CirProperty(
    name = plan.publicName,
    type = ForwardCirPropertyProjection.publicType(plan),
    nativeReturnType = "",
    nativeName = propName,
    getter = "",
    // `{ get; set; }` when the interface declares a `var`: an implementing subclass keeps its own
    // setter (ADR-075's `readOnlyOverrideeOwner` finds the interface member mutable), and an
    // `override` of a get-only abstract property that adds a setter is CS0546.
    setter = if (plan.setter != null) "" else null,
    isAbstract = true,
    hasNativeImport = false,
  )
}

/**
 * ADR-101 amendment (2026-09-11): the C# base list entry for [base], with its type arguments
 * spelled when it is generic (`Crate<string>`), so a closed generic base resolves.
 *
 * A bare `Crate` is not a lesser spelling, it never compiles: CS0305 in the ordinary case, and
 * CS0118 when the library's namespace happens to carry the base's name, which is exactly what
 * `TestLibrary.Parcel` does. The arguments are spelled off the same classifier both halves of the
 * bridge use, so `Parcel<String>` in Kotlin and `Parcel<string>` in C# cannot drift.
 *
 * A non-generic base is unchanged: [nestedCsName] stops at the first non-class parent, so a nested
 * sealed arm still renders `Roost.Perch` (ADR-009 amendment).
 *
 * Fails the build when an argument has no public C# spelling (a nested generic, a lambda, a
 * `Flow`). That shape does not compile today either, so nothing regresses, and a silent skip would
 * have to drop the base class itself or the inherited members vanish with no diagnostic at all.
 */
private fun forwardBaseSpelling(
  cls: KSClassDeclaration,
  name: String,
  base: KSClassDeclaration,
  classifier: ForwardBridgeTypeClassifier,
): String {
  val baseName: String = base.nestedCsName()
  if (base.typeParameters.isEmpty()) return baseName

  val arguments: List<KSTypeArgument> = cls.superTypes
    .map { it.resolve() }
    .firstOrNull { it.declaration.qualifiedName?.asString() == base.qualifiedName?.asString() }
    ?.arguments
    .orEmpty()
  check(arguments.size == base.typeParameters.size) {
    "Cannot render the base class of $name: its base $baseName declares " +
        "${base.typeParameters.size} type parameter(s) but the declaration supplies " +
        "${arguments.size} argument(s)."
  }

  val spelled: List<String> = arguments.map { argument ->
    val type: KSType = checkNotNull(argument.type?.resolve()) {
      "Cannot render the base class of $name: the base $baseName is used with a star projection, " +
          "which has no C# spelling. Close the base over a concrete type."
    }
    val bridge: BridgeType = classifier.classify(type)
    // `forwardPublicCsharpType` refuses an unspellable head with `error(...)`, which is the right
    // outcome here but names only the BridgeType. Catch it to say which class and which argument,
    // since a build failure with no declaration in it is unactionable.
    try {
      bridge.forwardPublicCsharpType()
    } catch (e: IllegalStateException) {
      error(
        "Cannot render the base class of $name: the type argument '$type' of $baseName has no " +
            "public C# spelling ($bridge). Close the base over a bridgeable type. (${e.message})",
      )
    }
  }
  return "$baseName<${spelled.joinToString(", ")}>"
}

internal fun translateClass(
  cls: KSClassDeclaration,
  libraryName: String,
  tracker: CollectionHelperTracker,
  exportedTypes: Set<String>,
  logger: KSPLogger,
  callableCatalog: ForwardCallablePlanCatalog,
  context: NugetContext,
  // ADR-114: the same classifier the Kotlin export builders use, so the two halves agree on which
  // legacy-route members bind and which are refused.
  classifier: ForwardBridgeTypeClassifier,
  // ADR-113's DECLARATION catalog, planned over every exported interface. Used only to spell an
  // inherited-but-unimplemented interface property (ADR-075 amendment 2026-09-11): the C# type has
  // to come off the same plan `IFoo` is projected from, or the two spellings drift into CS0738.
  interfaceDeclarationCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
): CirClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()
  val isDataClass: Boolean = cls.modifiers.contains(Modifier.DATA)
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)
  val isOpen: Boolean = !isAbstract && cls.modifiers.contains(Modifier.OPEN)

  // The shared has-superclass predicate (`ForwardClassMembership.kt`), the same instance the two
  // planners filter their members with, so a member can never be kept here and skipped there.
  // ADR-101 amendment / issue #42: gated on the export set, so a base class nothing generates is
  // dropped here exactly as an unexported interface is, instead of rendering a dangling `: Base`.
  val declaredBase: KSClassDeclaration? = cls.declaredSuperClass()
  val superClassDeclaration: KSClassDeclaration? = cls.forwardSuperClass(exportedTypes)
  if (declaredBase != null && superClassDeclaration == null) {
    // `translateClass` is the one place a class is translated (the regular-class loop in
    // `CirTranslator`), and neither planner nor the Kotlin emitter holds a logger, so this is the
    // only site the diagnostic can fire from — exactly once per affected class.
    keepsSupertype(cls, name, declaredBase, SupertypeKind.BASE_CLASS, exportedTypes, logger)
  }
  // ADR-009 amendment (2026-09-11): spelled by nested C# name, so a class extending a nested
  // sealed arm renders `: Roost.Perch` and not the unresolvable `: Perch` (CS0246). A top-level
  // base is unchanged: `nestedCsName()` stops at the first non-class parent.
  // ADR-101 amendment (2026-09-11): a generic base carries its type arguments too.
  val superClass: String? = superClassDeclaration?.let { base ->
    forwardBaseSpelling(cls, name, base, classifier)
  }

  // ADR-101 amendment (2026-09-11): a kept base no longer empties the interface list. `class
  // Ledge : Shelf(), Groomable` renders `: Shelf, IGroomable`, and `ForwardClassMembership` binds
  // `Groomable`'s members on `Ledge` to match, or the declaration is CS0535.
  val baseSupertypes: Set<String> = superClassDeclaration?.forwardSupertypeNames().orEmpty()
  val interfaces: List<String> = cls.superTypes
    .map { it.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .filter { it.classKind == ClassKind.INTERFACE }
    // An interface the base already implements is carried by the base. Listing it again compiles
    // but says nothing, and re-binding its members here would hide the base's (CS0108), so it is
    // dropped before the export-set filter: it owes no diagnostic either, nothing is lost.
    .filter { iface -> iface.qualifiedName?.asString() !in baseSupertypes }
    // ADR-101 / issue #42: a supertype outside the export set has no generated C# interface, so
    // naming it in the base list is a guaranteed CS0246 (the reporter's `: IKoinComponent`).
    // Drop it and say so. Nothing is lost: an unexported interface has no C# members to call,
    // and its defaulted members still bind on the class itself (`ForwardClassMembership.kt`).
    .filter { iface ->
      keepsSupertype(cls, name, iface, SupertypeKind.INTERFACE, exportedTypes, logger)
    }
    .map { "I${it.simpleName.asString()}" }
    .toList()

  // ADR-091: constructors come off the catalog, the same move ADR-090 made for methods. The
  // ADR-034 `_$n` sequence now also carries planner-synthesized omitting overloads, so the extern
  // suffix is derived from the plan symbol's tail after `<init>` ("" or `_$n`) rather than from a
  // declaration index. C# constructors all share the class name, so the numbering stays invisible:
  // the surface is one natural overload set.
  val constructorPlans: List<ForwardCallablePlan> =
    callableCatalog.constructors(cls.qualifiedName?.asString() ?: name)
  val cirConstructors: List<CirConstructor> = constructorPlans.map { plan ->
    tracker.trackPlan(plan)
    val suffix: String = plan.invocation.symbol.substringAfterLast('.').removePrefix("<init>")
    ForwardCirPlanProjection.constructor(plan, suffix)
  }
  // An unsuffixed plan is the primary; everything else renders as an overload. A primary skipped
  // by the planner leaves `constructor` null with no IntPtr fallthrough, exactly as before.
  val cirConstructor: CirConstructor? = cirConstructors.firstOrNull { it.nativeSuffix.isEmpty() }
  val secondaryConstructors: List<CirConstructor> =
    cirConstructors.filter { it.nativeSuffix.isNotEmpty() }

  // ROADMAP Phase 3: the class is kept (a Kotlin factory returning it still hands C# a usable
  // instance) but nothing can construct it from C#, and for a legacy-route deferral -- a sealed
  // or generic parameter -- that outcome had no diagnostic anywhere.
  val noPublicConstructor: Boolean =
    !isAbstract && cirConstructors.isEmpty() && cls.hasPublicConstructor()
  val remarks: String? = if (noPublicConstructor) {
    noPublicConstructorRemark(name, warnNoPublicConstructor(cls, name, callableCatalog, logger))
  } else {
    null
  }

  // C has no overloading and C# cannot declare two constructors with identical parameter
  // types — fail fast rather than emit uncompilable C# (ADR-034). C# nullable *reference*
  // annotations (e.g. "Patient" vs "Patient?") are not part of a method's signature, so they
  // must be normalized away before comparing; nullable *value* types (e.g. "int" vs "int?")
  // really are distinct signatures and must NOT be normalized (CirParameter.isReferenceType,
  // sourced from BridgeType, tells them apart).
  val constructorSignatures: List<List<String>> =
    (listOfNotNull(cirConstructor) + secondaryConstructors).map { ctor ->
      ctor.parameters.map { param ->
        val stripReferenceNullability: Boolean = param.isReferenceType && param.type.endsWith("?")
        if (stripReferenceNullability) param.type.dropLast(1) else param.type
      }
    }
  if (constructorSignatures.size != constructorSignatures.toSet().size) {
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
          symbol = cls,
          declaration = "$name.<init>",
          reason = "two or more constructors render identical C# parameter types; C# cannot " +
              "declare two constructors with the same signature (ADR-034)",
          hint = "rename or remove the duplicate constructor, change one parameter's type so " +
              "the rendered C# signatures differ, or remove the default value whose synthesized " +
              "omitting overload collides (ADR-091)",
        ),
      ),
      logger,
    )
  }

  val properties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop -> prop.isForwardMemberOf(cls, superClassDeclaration) }
    .mapNotNull { prop ->
      val propName: String = prop.simpleName.asString()
      val planned = callableCatalog.propertyFor("${cls.qualifiedName?.asString() ?: name}.$propName")
      if (planned != null) {
        tracker.trackProperty(planned)
        // ADR-101 amendment (2026-09-11): a base *class* overridee, not the Kotlin modifier. An
        // `override val` implementing an interface property the base does not declare is a fresh
        // C# slot (`virtual`), never an `override`.
        val isOverride: Boolean = prop.overridesBaseClassMember(superClassDeclaration)
        return@mapNotNull ForwardCirPropertyProjection.classProperty(
          planned,
          isOverride = isOverride,
          // ADR-101 amendment (2026-09-10): everything Kotlin left overridable and C# is not
          // already spelling `override`. A declared `open val`/`open var` reaches `virtual` here.
          isVirtual = !isOverride && prop.modifiers.isOpenForOverride(),
          // ADR-075 amendment (2026-09-10): this class's own unimplemented `abstract val`/`var`.
          // `isAbstract()` (not `Modifier.ABSTRACT`) is the same predicate
          // `isForwardPlannableMemberOf` uses. The abstract *method* walk has its own, broader
          // hole (a class-declared `abstract fun` is dropped entirely); it is not touched here.
          isAbstract = prop.isAbstract(),
        )
      }
      // ADR-075 amendment (2026-09-11): a property this class inherits from an exported interface
      // and does not implement. `isForwardPlannableMemberOf` keeps it out of the planner (nothing
      // to dispatch to), so it takes the declaration walk the abstract *method* mirror takes: an
      // abstract C# property, no body, no export, no `DllImport`. Without it the generated
      // `Bird : IFeathered` is CS0535 and a consumer subclass's `override` is CS0115.
      if (prop.parentDeclaration != cls && prop.isAbstract()) {
        return@mapNotNull inheritedAbstractProperty(prop, propName, interfaceDeclarationCatalog)
      }
      // Issue #121: the planner declined, but a decline is not always an invitation. A marked
      // declaration must reach neither artifact, so the legacy arms below never run for one.
      if (prop.isOptInRefused()) return@mapNotNull null
      // Named specialized-protocol property adapters only (lambda / suspend-lambda / Flow).
      // Ordinary property types without a plan are skipped — no mapReturnType IntPtr fallthrough.
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }
      val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()

      // ADR-124: the Flow/StateFlow arm is one function now, so `translateSealedClass` projects
      // the identical property for a sealed arm. It owns its own detection and its own ADR-123
      // element refusal, and answers null for every other type, so the lambda arms below are
      // reached exactly as before.
      if (propTypeResolved.isForwardFlowType()) {
        return@mapNotNull flowProperty(prop, name, context, classifier, tracker)
      }

      val isLambdaType: Boolean = qualifiedTypeName in LAMBDA_TYPES
      val lambdaArity: Int = if (isLambdaType) propTypeResolved.arguments.size - 1 else -1
      if (isLambdaType) tracker.lambdaArities.add(lambdaArity)

      val isSuspendLambdaType: Boolean = qualifiedTypeName in SUSPEND_LAMBDA_TYPES
      val suspendLambdaArity: Int =
        if (isSuspendLambdaType) propTypeResolved.arguments.size - 1 else -1
      if (isSuspendLambdaType) {
        tracker.suspendLambdaArities.add(suspendLambdaArity)
        tracker.needsAsync = true
      }

      if (!isLambdaType && !isSuspendLambdaType) return@mapNotNull null

      // Issue #111: one type argument C# cannot name (`Flow<Snapshot>`, an unexported dependency
      // type, a nested class) makes the whole property unspellable, so it is skipped named rather
      // than emitted as `KotlinFunc<CamId, Flow>` for the consumer's compiler to reject.
      val unnameableTypeArgument: CsTypeArgument.Unnameable? =
        if (isLambdaType || isSuspendLambdaType) {
          csTypeArguments(propTypeResolved.arguments, exportedTypes, context)
        } else null
      if (unnameableTypeArgument != null) {
        ForwardDiagnosticSink.emit(
          listOf(
            lambdaTypeArgumentDiagnostic(
              kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
              symbol = prop,
              declaration = "$name.$propName",
              typeArgument = unnameableTypeArgument.typeArgument,
            ),
          ),
          logger,
        )
        return@mapNotNull null
      }

      val lambdaTypeArgs: List<String> = if (isLambdaType) {
        csTypeArgumentNames(propTypeResolved.arguments, exportedTypes, context)
      } else emptyList()

      val lambdaCsType: String = if (isLambdaType) csLambdaType(lambdaTypeArgs) else ""

      val suspendLambdaTypeArgs: List<String> = if (isSuspendLambdaType) {
        csTypeArgumentNames(propTypeResolved.arguments, exportedTypes, context)
      } else emptyList()

      val suspendLambdaIsUnit: Boolean = isSuspendLambdaType &&
          (suspendLambdaTypeArgs.lastOrNull() == "void" ||
              propTypeResolved.arguments.lastOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.Unit")

      val suspendLambdaCsType: String = if (isSuspendLambdaType) {
        if (suspendLambdaIsUnit) {
          if (suspendLambdaArity == 0) "KotlinSuspendAction"
          else {
            val typeParams: String = suspendLambdaTypeArgs.dropLast(1).joinToString(", ")
            "KotlinSuspendAction<$typeParams>"
          }
        } else {
          val typeParams: String = suspendLambdaTypeArgs.joinToString(", ")
          "KotlinSuspendFunc<$typeParams>"
        }
      } else ""

      val nativeReturnType: String = "IntPtr"
      val type: String = when {
        isLambdaType -> lambdaCsType
        isSuspendLambdaType -> suspendLambdaCsType
        else -> error("unreachable specialized property branch")
      }

      val getter: String = when {
        isLambdaType -> "new $lambdaCsType(Native_Get_$propName(_handle))"
        isSuspendLambdaType -> "new $suspendLambdaCsType(Native_Get_$propName(_handle))"
        else -> error("unreachable specialized property getter")
      }

      CirProperty(
        name = csPropName,
        type = type,
        nativeReturnType = nativeReturnType,
        nativeSetterType = nativeReturnType,
        nativeName = propName,
        getter = getter,
        setter = null,
        extraNatives = emptyList(),
        hasSyncErrorOut = false,
      )
    }.toList()

  val allMethods = cls.getAllFunctions().toList()

  val filteredMethods: List<KSFunctionDeclaration> = allMethods
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { method ->
      val methodName: String = method.simpleName.asString()
      val isDataClassMethod: Boolean = isDataClass &&
          (methodName == "copy" || methodName.startsWith("component"))
      val isSkipped: Boolean = methodName in listOf("equals", "hashCode", "toString", "<init>") ||
          isDataClassMethod
      if (isSkipped) return@filter false

      // ADR-114: a Flow-returning or suspend member with a generic parameter this route cannot
      // marshal is dropped on both halves. `NugetProcessor` names it once. ADR-119: likewise a
      // suspend member with a generic return that is not a marshallable collection.
      if (method.isForwardLegacyAsyncRoute() &&
        classifier.legacyRefusedParameter(method.parameters) != null
      ) {
        return@filter false
      }
      if (classifier.legacyRefusedReturn(method) != null) return@filter false

      method.isForwardMemberOf(cls, superClassDeclaration)
    }

  val (allSuspendMethods, regularMethods) = filteredMethods
    .partition { it.modifiers.contains(Modifier.SUSPEND) }

  val (flowMethods, nonFlowMethods) = regularMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in FLOW_TYPES || returnQualified in STATE_FLOW_TYPES
  }

  val (lambdaParamMethods, normalMethods) = nonFlowMethods.partition { method ->
    method.parameters.any { param ->
      param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
    }
  }

  // Detect stored-callback pairs; exclude both halves from per-call callback path.
  val storedCallbackPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findStoredCallbackPairs(lambdaParamMethods)
  val storedCallbackExcluded: Set<KSFunctionDeclaration> =
    (storedCallbackPairs.map { it.first } + storedCallbackPairs.map { it.second }).toSet()

  val callbackMembers: List<CirCallbackMethod> = lambdaParamMethods
    .filter { it !in storedCallbackExcluded }
    .mapNotNull { method ->
      translateCallbackMethod(method, libraryName, prefix, exportedTypes, tracker)
    }

  val storedCallbackMembers: List<CirStoredCallbackMethod> = storedCallbackPairs
    .mapNotNull { (addMethod, removeMethod) ->
      translateStoredCallbackMethod(
        addMethod,
        removeMethod,
        libraryName,
        prefix,
        exportedTypes,
        tracker,
        context,
      )
    }

  // Detect interface-bridge pairs; exclude both halves from regular method path.
  val interfaceBridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findInterfaceBridgePairs(normalMethods)
  val interfaceBridgeExcluded: Set<KSFunctionDeclaration> =
    (interfaceBridgePairs.map { it.first } + interfaceBridgePairs.map { it.second }).toSet()

  val interfaceBridgeMembers: List<CirInterfaceBridgeMethod> = interfaceBridgePairs
    .mapNotNull { (addMethod, removeMethod) ->
      translateInterfaceBridgeMethod(addMethod, removeMethod, libraryName, prefix, name, tracker)
    }

  // ADR-090: planned members come off the catalog (overload numbering makes the plan symbol
  // per-declaration underivable, and the two halves must not drift — the same move ADR-082 made
  // for value classes). `isOverride` / `isVirtual` ride the plan, computed in `classEntries`.
  val plannedMethodPlans: List<ForwardCallablePlan> =
    callableCatalog.classMethods(cls.qualifiedName?.asString() ?: name)
  val plannedMethods: List<CirMethod> = plannedMethodPlans.map { plan ->
    tracker.trackPlan(plan)
    ForwardCirPlanProjection.classMethod(
      plan = plan,
      nativePrefix = prefix,
      isOverride = plan.publicSignature.isOverride,
      isVirtual = plan.publicSignature.isVirtual,
    )
  }
  val plannedMemberNames: Set<String> = plannedMethodPlans
    .mapNotNull { plan -> plan.invocation.member }
    .toSet()

  // Abstract declarations still need a C# abstract method for the public surface even though they
  // have no native export / plan (planner skips ABSTRACT), so they stay on the declaration walk.
  val abstractMethods: List<CirMethod> = normalMethods
    .filter { it !in interfaceBridgeExcluded }
    .mapNotNull { method ->
      val methodName: String = method.simpleName.asString()
      if (methodName in plannedMemberNames) return@mapNotNull null
      val declaredInThisClass: Boolean = method.parentDeclaration == cls
      val hasImplementation: Boolean = declaredInThisClass ||
          method.modifiers.contains(Modifier.OVERRIDE)
      val isMethodAbstract: Boolean = !hasImplementation &&
          (isAbstract || method.modifiers.contains(Modifier.ABSTRACT))
      if (!isMethodAbstract) return@mapNotNull null
      val methodReturnTypeResolved = method.returnType?.resolve()?.expandAliases()
      val methodReturn: String =
        methodReturnTypeResolved?.declaration?.simpleName?.asString() ?: "Unit"
      val isNullableReturn: Boolean = methodReturnTypeResolved?.isMarkedNullable == true
      val returnType: String = when {
        methodReturn == "Unit" -> "void"
        methodReturn == "String" && isNullableReturn -> "string?"
        methodReturn == "String" -> "string"
        methodReturn in KOTLIN_TO_CSHARP_RETURN -> {
          val mapped = KOTLIN_TO_CSHARP_RETURN.getValue(methodReturn)
          if (isNullableReturn && mapped != "void") "$mapped?" else mapped
        }

        isNullableReturn -> "$methodReturn?"
        else -> methodReturn
      }
      val methodParams: List<CirParameter> = method.parameters.map { param ->
        val resolved = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
          ?.classKind == ClassKind.ENUM_CLASS
        val isNullableString: Boolean = !isEnum && kotlinType == "String" && resolved.isMarkedNullable
        val paramType: String = when {
          isEnum -> kotlinType
          isNullableString -> "string?"
          kotlinType in KOTLIN_TO_CSHARP_PARAM -> KOTLIN_TO_CSHARP_PARAM.getValue(kotlinType)
          else -> kotlinType
        }
        CirParameter((param.name?.asString() ?: "_").csharpParameterName(), paramType)
      }
      CirMethod(
        name = methodName.replaceFirstChar { it.uppercase() },
        returnType = returnType,
        parameters = methodParams,
        body = "",
        isAbstract = true,
        isOverride = method.overridesBaseClassMember(superClassDeclaration),
        isSyncErrorCheckEnabled = false,
      )
    }

  val methods: List<CirMethod> = plannedMethods + abstractMethods

  // ADR-118: the whole suspend projection (both the plain-async and the ADR-068 StateFlow half)
  // lives in one function now, so a sealed arm gets byte-identical externs and bodies from the
  // same call.
  val asyncMembers: List<CirMember> = suspendMembers(
    suspendMethods = allSuspendMethods,
    prefix = prefix,
    libraryName = libraryName,
    classifier = classifier,
    tracker = tracker,
    callableCatalog = callableCatalog,
    context = context,
  )

  // ADR-124: the whole flow projection lives in one function now, so a sealed arm gets
  // byte-identical externs and bodies from the same call.
  val flowRouteMembers: List<CirMember> = flowMembers(
    flowMethods = flowMethods,
    prefix = prefix,
    libraryName = libraryName,
    classifier = classifier,
    tracker = tracker,
    callableCatalog = callableCatalog,
    context = context,
  )

  val companion: KSClassDeclaration? = cls.declarations
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { it.isCompanionObject }

  val companionMembers: List<CirMember> = if (companion != null) {
    val companionConsts: List<CirMember> = companion.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.modifiers.contains(Modifier.CONST) }
      .mapNotNull { translateConstProperty(it) }
      .toList()

    val companionProperties: List<CirMember> = companion.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { !it.modifiers.contains(Modifier.CONST) }
      .flatMap { prop ->
        val symbol: String = "${cls.qualifiedName?.asString() ?: name}.Companion.${prop.simpleName.asString()}"
        val planned = callableCatalog.propertyFor(symbol)
        if (planned != null) {
          tracker.trackProperty(planned)
          ForwardCirPropertyProjection.staticProperty(planned, libraryName)
        } else {
          emptyList()
        }
      }
      .toList()

    // ADR-095: companion members come off the catalog rather than a per-declaration lookup — with
    // per-companion overload numbering an unsuffixed symbol binds every namesake to the first
    // one's plan (see `addCompanionExports` for the Kotlin half).
    val companionFunctions: List<CirMember> = callableCatalog
      .companionMethods(cls.qualifiedName?.asString() ?: name)
      .flatMap { planned ->
        tracker.trackPlan(planned)
        ForwardCirPlanProjection.static(planned, libraryName)
      }

    companionConsts + companionProperties + companionFunctions
  } else emptyList()

  // C# cannot declare two members of one type whose name and parameter types agree (ADR-034 /
  // ADR-090, extended to companions by ADR-095). Instance methods and companion statics are
  // checked *together*: static-ness is not part of a C# signature either.
  emitCsharpSignatureCollisions(
    methods = plannedMethods + companionMembers.filterIsInstance<CirMethod>(),
    container = name,
    symbol = cls,
    logger = logger,
  )

  // Phase 6: route data-class copy() through the shared plan when it is eligible (same symbol
  // ClassExports.kt checks for the Kotlin half), else keep the legacy hand-rolled route.
  val copyMethod: CirMethod? = if (isDataClass) {
    callableCatalog.planFor("${cls.qualifiedName?.asString() ?: name}.copy")
      ?.let { planned ->
        tracker.trackPlan(planned)
        ForwardCirPlanProjection.classMethod(planned, prefix, isOverride = false)
      }
  } else null

  return CirClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    constructor = cirConstructor,
    secondaryConstructors = secondaryConstructors,
    properties = properties,
    methods = methods,
    copyMethod = copyMethod,
    callbackMethods = callbackMembers,
    storedCallbackMethods = storedCallbackMembers,
    interfaceBridgeMethods = interfaceBridgeMembers,
    interfaces = interfaces,
    superClass = superClass,
    isDataClass = isDataClass,
    isAbstract = isAbstract,
    isOpen = isOpen,
    companionMembers = companionMembers + asyncMembers + flowRouteMembers,
    hasSuspendMethods = cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) } ||
        flowMethods.isNotEmpty() ||
        cls.getAllProperties().any { prop ->
          val qualified: String? =
            prop.type.resolve().expandAliases().declaration.qualifiedName?.asString()
          qualified in FLOW_TYPES || qualified in STATE_FLOW_TYPES
        },
    remarks = remarks,
  )
}

internal fun translateGenericClass(
  cls: KSClassDeclaration,
  libraryName: String,
  logger: KSPLogger,
): CirGenericClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()
  val typeParams: List<CirTypeParameter> = cls.typeParameters.map { param ->
    val bounds: List<String> = param.bounds.toList().mapNotNull { bound ->
      val resolved = bound.resolve()
      val qualifiedName: String? = resolved.declaration.qualifiedName?.asString()
      val simpleName: String = resolved.declaration.simpleName.asString()
      val isInterface: Boolean = resolved.declaration is KSClassDeclaration &&
          (resolved.declaration as KSClassDeclaration).classKind ==
          ClassKind.INTERFACE

      when {
        qualifiedName == "kotlin.Any" -> null
        isInterface -> "I$simpleName"
        else -> simpleName
      }
    }

    if (param.variance != Variance.INVARIANT) {
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.INFO_DROPPED_VARIANCE,
            symbol = cls,
            declaration = "${cls.simpleName.asString()}<${param.name.asString()}>",
            reason = "variance '${param.variance}' on this generic class type parameter is " +
                "dropped; C# does not support variance on classes",
            hint = "the member still binds; declare the parameter invariant if the dropped " +
                "variance was load-bearing",
          ),
        ),
        logger,
      )
    }

    CirTypeParameter(param.name.asString(), bounds)
  }

  val properties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .map { prop ->
      val propName: String = prop.simpleName.asString()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }

      // ADR-083: a nullable property reads back as the null pointer, so surface it as `T?`. C# 9
      // allows `T?` on an unconstrained type parameter; a value-type instantiation still collapses
      // it to `default(T)`, which is what the Zero branch of NugetMarshal.FromHandle returns.
      val isNullable: Boolean = prop.type.resolve().isMarkedNullable

      CirProperty(
        name = csPropName,
        type = if (isNullable) "${typeParams.first().name}?" else typeParams.first().name,
        nativeReturnType = "IntPtr",
        nativeName = propName,
        getter = "NugetMarshal.FromHandle<${typeParams.first().name}>(${name}Native.Get_$propName(_handle))",
        setter = null,
      )
    }
    .toList()

  return CirGenericClass(
    name = name,
    typeParameters = typeParams,
    libraryName = libraryName,
    nativePrefix = prefix,
    properties = properties,
    hasPublicConstructor = true,
    // ADR-101 amendment (2026-09-11): `open` reaches the generic route too, so a subclass closing
    // this class over a concrete type can `override` its `Dispose`.
    isOpen = cls.modifiers.contains(Modifier.OPEN),
  )
}

/**
 * ADR-124: the legacy Flow/StateFlow route's C# **property** half, lifted out of [translateClass]
 * so a sealed arm projects the identical property under its own name and prefix. Returns null when
 * the property is not on this route, or when ADR-123 refuses its element (the Kotlin half drops it
 * on the same rule, and `warnRefusedLegacyRouteMembers` names it once).
 *
 * [ownerCsName] is load-bearing, not cosmetic: the getter bakes
 * `throw new ObjectDisposedException(nameof(...))`, which has to name the **arm** rather than the
 * sealed base, or the generated C# names a type that is not the receiver.
 */
internal fun flowProperty(
  prop: KSPropertyDeclaration,
  ownerCsName: String,
  context: NugetContext,
  classifier: ForwardBridgeTypeClassifier,
  tracker: CollectionHelperTracker,
): CirProperty? {
  val propName: String = prop.simpleName.asString()
  val csPropName: String = propName.replaceFirstChar { it.uppercase() }
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()
  // ADR-065: StateFlow (and the read-only MutableStateFlow view) is checked BEFORE FLOW_TYPES
  // -- it is-a Flow, so an isAssignableFrom-style check would make it match the plain-Flow
  // branch and silently lose `.Value`. Detection is on the exact declared qualifiedName.
  val isStateFlowType: Boolean = qualifiedTypeName in STATE_FLOW_TYPES
  val isFlowType: Boolean = !isStateFlowType && qualifiedTypeName in FLOW_TYPES
  // Not on this route at all: the caller's other legacy arms (lambda, suspend lambda) own it.
  if (!isFlowType && !isStateFlowType) return null
  val flowElementTypeResolved: KSType? = if (isFlowType || isStateFlowType) {
    propTypeResolved.arguments.firstOrNull()?.type?.resolve()
  } else null
  // ADR-067: nullable element (`StateFlow<T?>`) and nullable member (`StateFlow<T>?`) are only
  // threaded for StateFlow; nullable Flow is out of scope (ADR-065 deferred).
  val isNullableElement: Boolean =
    isStateFlowType && flowElementTypeResolved?.isMarkedNullable == true
  val isNullableMember: Boolean = isStateFlowType && propTypeResolved.isMarkedNullable
  // ADR-071: a genuinely DECLARED MutableStateFlow<T> (not narrowed through .asStateFlow())
  // gains a settable `.Value` -- gated on the exact declared type, a non-nullable
  // element/member (both deferred), and a v1-supported element (primitive/String/object).
  val isMutableStateFlowProperty: Boolean = isStateFlowType &&
      qualifiedTypeName in MUTABLE_STATE_FLOW_TYPES &&
      !isNullableElement && !isNullableMember &&
      isMutableStateFlowElementSupported(flowElementTypeResolved)
  val isMutableStateFlowObjectElement: Boolean =
    isMutableStateFlowProperty && isMutableStateFlowElementObject(flowElementTypeResolved)
  if (isMutableStateFlowProperty) tracker.needsMutableStateFlow = true
  // ADR-123: a collection element is spelled and read like the ordinary route's collection
  // result, never through `qualifiedElementCsType` (which runs a Kotlin builtin through the
  // user-type namespace mapping and drops the type argument, issue #127). A refused element
  // drops the property on both halves; `NugetProcessor` names it once.
  if (classifier.legacyRefusedFlowElement(propTypeResolved) != null) return null
  val flowElementCollection: BridgeType.Collection? =
    classifier.legacyFlowElementCollection(propTypeResolved)
  if (flowElementCollection != null) tracker.trackCollection(flowElementCollection)
  val flowElementType: String? = when {
    flowElementCollection != null -> flowElementCollection.forwardPublicCsharpType()
    // ADR-066: qualified, not by simple name: an admitted dependency-module element type is
    // not guaranteed to share this class's own namespace.
    isFlowType || isStateFlowType ->
      qualifiedElementCsType(flowElementTypeResolved, context, isNullableElement)

    else -> null
  }
  val flowElementRead: String? =
    flowElementCollection?.let { collection -> legacyFlowElementReadArgument(collection) }
  if (isFlowType || isStateFlowType) {
    tracker.needsFlow = true
    tracker.needsAsync = true
  }
  if (isStateFlowType) tracker.needsStateFlow = true

  val nativeReturnType: String = "IntPtr"
  val type: String = when {
    isMutableStateFlowProperty -> "KotlinMutableStateFlow<$flowElementType>"
    isStateFlowType -> "KotlinStateFlow<$flowElementType>${if (isNullableMember) "?" else ""}"
    else -> "KotlinFlow<$flowElementType>"
  }

  val getter: String = if (isStateFlowType) {
      // ADR-065: the collect wiring is byte-for-byte the plain-Flow getter above; the only
      // addition is the second constructor argument, a synchronous `_value` read lambda.
      // ADR-067: a nullable member additionally probes `_has_value` before constructing.
      // ADR-071: a settable member additionally passes a third `Action<T>` write lambda,
      // backed by the sibling `_set_value` export.
      val collectNativeName = "Native_Get${csPropName}Collect"
      val valueNativeName = "Native_Get${csPropName}Value"
      val hasValueNativeName = "Native_Get${csPropName}HasValue"
      val setValueNativeName = "Native_Set${csPropName}Value"
      val ctorName: String =
        if (isMutableStateFlowProperty) "KotlinMutableStateFlow" else "KotlinStateFlow"
      buildString {
        appendLine()
        appendLine("                if (_handle == IntPtr.Zero)")
        appendLine("                    throw new ObjectDisposedException(nameof($ownerCsName));")
        if (isNullableMember) {
          appendLine("                if (!$hasValueNativeName(_handle))")
          appendLine("                    return null;")
        }
        appendLine("                return new $ctorName<$flowElementType>((onNext, onComplete, onError, userData) =>")
        appendLine("                    $collectNativeName(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),")
        if (isMutableStateFlowProperty) {
          appendLine("                    () => $valueNativeName(_handle),")
          val writeReceiver: String = if (isMutableStateFlowObjectElement) "v._handle" else "v"
          if (isMutableStateFlowObjectElement) {
            appendLine("                    v =>")
            appendLine("                    {")
            appendLine("                        if (v is null) throw new ArgumentNullException(nameof(v));")
            appendLine("                        $setValueNativeName(_handle, $writeReceiver, out IntPtr error);")
            appendLine("                        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
            appendLine("                    });")
          } else {
            appendLine("                    v =>")
            appendLine("                    {")
            appendLine("                        $setValueNativeName(_handle, $writeReceiver, out IntPtr error);")
            appendLine("                        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
            appendLine("                    });")
          }
        } else if (flowElementRead != null) {
          // ADR-123: `read:` is named, so it skips the ADR-068-only `ownedHandle` slot.
          appendLine("                    () => $valueNativeName(_handle),")
          appendLine("                    $flowElementRead);")
        } else {
          appendLine("                    () => $valueNativeName(_handle));")
        }
        append("            ")
      }
  } else {
      val collectNativeName = "Native_Get${csPropName}Collect"
      buildString {
        appendLine()
        appendLine("                if (_handle == IntPtr.Zero)")
        appendLine("                    throw new ObjectDisposedException(nameof($ownerCsName));")
        appendLine("                return new KotlinFlow<$flowElementType>((onNext, onComplete, onError, userData) =>")
        if (flowElementRead != null) {
          appendLine("                    $collectNativeName(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),")
          appendLine("                    $flowElementRead);")
        } else {
          appendLine("                    $collectNativeName(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData));")
        }
        append("            ")
      }
  }

  // ADR-071: the setter's native (DllImport) parameter type -- the element's own C# wire type
  // for a primitive/String, else IntPtr for an object handle. Only meaningful when
  // [isMutableStateFlowProperty]; otherwise it stays [nativeReturnType] (unused, since a
  // read-only StateFlow property has no setter).
  val mutableStateFlowNativeSetterType: String = when {
    !isMutableStateFlowProperty -> nativeReturnType
    isMutableStateFlowObjectElement -> "IntPtr"
    else -> flowElementType ?: nativeReturnType
  }

  return CirProperty(
    name = csPropName,
    type = type,
    nativeReturnType = nativeReturnType,
    nativeSetterType = mutableStateFlowNativeSetterType,
    nativeName = propName,
    getter = getter,
    setter = null,
    extraNatives = emptyList(),
    isFlow = true,
    isStateFlow = isStateFlowType,
    flowElementType = flowElementType ?: "",
    hasSyncErrorOut = false,
    isNullableMember = isNullableMember,
    isMutableStateFlow = isMutableStateFlowProperty,
    stateFlowSetValueNativeName =
      if (isMutableStateFlowProperty) "Native_Set${csPropName}Value" else "",
  )
}


/**
 * ADR-124: the legacy Flow/StateFlow route's C# **method** half, lifted out of [translateClass] so
 * a sealed arm projects the identical pair (a private `[DllImport]` set and a `CirMethod(isFlow =
 * true)`) under its own export prefix. The two callers differ only in which methods they hand in
 * and which prefix the externs take.
 *
 * The overload number is the planner's ([ForwardCallablePlanCatalog.overloadSuffix]), the same
 * number the Kotlin `@CName` reads, and it lands on the import's `entryPoint`, the import's `name`
 * and [CirMethod.nativeName] alike: numbering only the first two lets a second overload's body
 * bind to the *first* overload's extern whenever the arities agree, which compiles and answers the
 * wrong values.
 */
internal fun flowMembers(
  flowMethods: List<KSFunctionDeclaration>,
  prefix: String,
  libraryName: String,
  classifier: ForwardBridgeTypeClassifier,
  tracker: CollectionHelperTracker,
  callableCatalog: ForwardCallablePlanCatalog,
  context: NugetContext,
): List<CirMember> {
  if (flowMethods.isNotEmpty()) {
    tracker.needsFlow = true
    tracker.needsAsync = true
  }

  if (flowMethods.any { method ->
      method.returnType?.resolve()?.expandAliases()
        ?.declaration?.qualifiedName?.asString() in STATE_FLOW_TYPES
    }
  ) {
    tracker.needsStateFlow = true
  }

  return flowMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    // Issue #97: the overload number the planner assigned, on the C name and the extern stem alike,
    // the same two places the plan projection puts it (ADR-090).
    val suffix: String = callableCatalog.overloadSuffix(method)
    val cname: String = toCName(methodName) + suffix
    val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
    val nativeStem: String = "Native_$csMethodName$suffix"
    val returnType = method.returnType?.resolve()?.expandAliases()
    val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
    val isStateFlowMethod: Boolean = returnQualified in STATE_FLOW_TYPES
    val flowElementTypeResolved: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    // ADR-067: nullable element/member threading mirrors the sibling property branch above;
    // nullable Flow stays out of scope (ADR-065 deferred).
    val isNullableElement: Boolean =
      isStateFlowMethod && flowElementTypeResolved?.isMarkedNullable == true
    val isNullableMember: Boolean = isStateFlowMethod && returnType?.isMarkedNullable == true
    // ADR-071: mirrors the sibling property branch above -- a genuinely DECLARED
    // MutableStateFlow<T> function return (not narrowed to StateFlow<T>) gains a settable
    // `.Value`, gated on non-nullable element/member (both deferred) and a v1-supported element.
    val isMutableStateFlowMethod: Boolean = isStateFlowMethod &&
        returnQualified in MUTABLE_STATE_FLOW_TYPES &&
        !isNullableElement && !isNullableMember &&
        isMutableStateFlowElementSupported(flowElementTypeResolved)
    val isMutableStateFlowObjectElement: Boolean =
      isMutableStateFlowMethod && isMutableStateFlowElementObject(flowElementTypeResolved)
    if (isMutableStateFlowMethod) tracker.needsMutableStateFlow = true
    // ADR-123: a collection element, spelled and read like the ordinary route's collection result.
    // A refused element never reaches here: `filteredMethods` drops the member upstream.
    val flowElementCollection: BridgeType.Collection? =
      classifier.legacyFlowElementCollection(returnType)
    if (flowElementCollection != null) tracker.trackCollection(flowElementCollection)
    // ADR-066: qualified, not by simple name, see the sibling property branch above for why.
    val flowCsElementType: String = flowElementCollection?.forwardPublicCsharpType()
      ?: qualifiedElementCsType(flowElementTypeResolved, context, isNullableElement)
    val flowElementRead: String? =
      flowElementCollection?.let { collection -> legacyFlowElementReadArgument(collection) }

    // ADR-114: a collection parameter takes the public collection type with an IntPtr native
    // slot; every other parameter keeps mapParamType's shipped spelling.
    val methodParams: List<CirParameter> =
      legacyRouteParameters(method.parameters, classifier, tracker)

    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", "IntPtr"),
      CirParameter("scopeHandle", "IntPtr"),
    ) + methodParams + listOf(
      CirParameter("onNext", "IntPtr"),
      CirParameter("onComplete", "IntPtr"),
      CirParameter("onError", "IntPtr"),
      CirParameter("userData", "IntPtr"),
    )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_collect",
      returnType = "IntPtr",
      name = "${nativeStem}Collect",
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = methodParams.joinToString(", ") { it.nativeArgument }
    val nativeCallArgs: String = if (paramNames.isEmpty()) {
      "_handle, GetOrCreateScope(), onNext, onComplete, onError, userData"
    } else {
      "_handle, GetOrCreateScope(), $paramNames, onNext, onComplete, onError, userData"
    }

    if (isStateFlowMethod) {
      // ADR-065: sibling synchronous `_value` export -- takes handle + the method's own
      // parameters (no scope, no callbacks, no errorOut; StateFlow.value cannot throw).
      val valueNativeImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = "${prefix}_${cname}_value",
        returnType = "IntPtr",
        name = "${nativeStem}Value",
        parameters = listOf(CirParameter("handle", "IntPtr")) + methodParams,
        visibility = CirVisibility.PRIVATE,
      )

      // ADR-067: nullable member -- sibling `_has_value` presence-probe DllImport, same
      // parameter shape as `_value` (handle + the method's own parameters).
      val hasValueNativeImport: CirDllImport? = if (isNullableMember) {
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "${prefix}_${cname}_has_value",
          returnType = "bool",
          name = "${nativeStem}HasValue",
          parameters = listOf(CirParameter("handle", "IntPtr")) + methodParams,
          visibility = CirVisibility.PRIVATE,
          marshalBooleanReturn = true,
        )
      } else null

      // ADR-071: sibling `_set_value` DllImport -- handle + the method's own parameters + the
      // element's own wire type + a trailing `out IntPtr error` (the Kotlin setter can throw,
      // MutableStateFlow.value conflates by Any.equals on the previous value).
      val setValueNativeImport: CirDllImport? = if (isMutableStateFlowMethod) {
        val setValueParamType: String =
          if (isMutableStateFlowObjectElement) "IntPtr" else flowCsElementType
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "${prefix}_${cname}_set_value",
          returnType = "void",
          name = "${nativeStem}SetValue",
          parameters = listOf(CirParameter("handle", "IntPtr")) + methodParams +
              listOf(CirParameter("value", setValueParamType)),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      } else null

      val stateFlowMethod = CirMethod(
        name = csMethodName,
        returnType = if (isMutableStateFlowMethod) {
          "KotlinMutableStateFlow<$flowCsElementType>"
        } else {
          "KotlinStateFlow<$flowCsElementType>${if (isNullableMember) "?" else ""}"
        },
        nativeName = "${nativeStem}Collect",
        parameters = methodParams,
        body = nativeCallArgs,
        isFlow = true,
        isStateFlow = true,
        flowElementType = flowCsElementType,
        flowElementRead = flowElementRead,
        stateFlowValueNativeName = "${nativeStem}Value",
        isStateFlowNullableMember = isNullableMember,
        stateFlowHasValueNativeName =
          if (isNullableMember) "${nativeStem}HasValue" else "",
        isMutableStateFlow = isMutableStateFlowMethod,
        stateFlowSetValueNativeName =
          if (isMutableStateFlowMethod) "${nativeStem}SetValue" else "",
        isMutableStateFlowElementObject = isMutableStateFlowObjectElement,
      )

      return@flatMap listOfNotNull(
        nativeImport,
        valueNativeImport,
        hasValueNativeImport,
        setValueNativeImport,
        stateFlowMethod,
      )
    }

    val flowMethod = CirMethod(
      name = csMethodName,
      returnType = "KotlinFlow<$flowCsElementType>",
      nativeName = "${nativeStem}Collect",
      parameters = methodParams,
      body = nativeCallArgs,
      isFlow = true,
      flowElementType = flowCsElementType,
      flowElementRead = flowElementRead,
    )

    listOf(nativeImport, flowMethod)
  }
}

/**
 * ADR-118: the legacy suspend route's C# half, lifted out of [translateClass] so a sealed subclass
 * can project the identical pair (a private `[DllImport]` and an `async` [CirMethod]) under its own
 * export prefix. The two callers differ only in which methods they hand in and which prefix the
 * externs take.
 *
 * The overload number is the planner's ([ForwardCallablePlanCatalog.overloadSuffix]), the same
 * number the Kotlin `@CName` reads, and it lands on **three** fields: the import's `entryPoint`
 * (the C symbol), the import's `name` and [CirMethod.nativeName] (the private extern the rendered
 * body calls). Numbering only the first two lets a second overload's body bind to the *first*
 * overload's extern whenever the two agree on argument types -- it compiles, and answers the wrong
 * value.
 */
internal fun suspendMembers(
  suspendMethods: List<KSFunctionDeclaration>,
  prefix: String,
  libraryName: String,
  classifier: ForwardBridgeTypeClassifier,
  tracker: CollectionHelperTracker,
  callableCatalog: ForwardCallablePlanCatalog,
  context: NugetContext,
): List<CirMember> {
  // ADR-068: a `suspend fun` returning StateFlow<T>/MutableStateFlow<T> is peeled into its own
  // bucket BEFORE the plain-async path below claims it -- that path would otherwise resolve the
  // return type's simple name "StateFlow" through KOTLIN_TO_CSHARP_PARAM (a miss) and emit an
  // undefined-type `Task<StateFlow>`. `suspend fun` returning plain Flow<T> stays on the
  // (separately deferred) legacy plain-async path.
  val (stateFlowMethods, plainMethods) = suspendMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in STATE_FLOW_TYPES
  }

  if (plainMethods.isNotEmpty()) tracker.needsAsync = true
  if (stateFlowMethods.isNotEmpty()) {
    tracker.needsFlow = true
    tracker.needsStateFlow = true
    tracker.needsAsync = true
    tracker.needsSuspendStateFlow = true
  }

  val asyncMembers: List<CirMember> = plainMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    // ADR-118: the planner's overload number, on the C symbol and on the extern stem alike.
    val suffix: String = callableCatalog.overloadSuffix(method)
    val cname: String = toCName(methodName) + suffix
    val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
    val nativeStem: String = "Native_$csMethodName${suffix}Async"
    val resolvedReturn: KSType? = method.returnType?.resolve()?.expandAliases()
    val methodReturn: String = resolvedReturn?.declaration?.simpleName?.asString() ?: "Unit"
    val isUnit: Boolean = methodReturn == "Unit"

    // ADR-119: a collection return is read back through the ordinary route's wire container;
    // every other generic return has already been refused upstream, on both halves.
    val returnShape: ForwardLegacyReturnShape = classifier.legacyReturnShape(resolvedReturn)
    if (returnShape is ForwardLegacyReturnShape.Refused) return@flatMap emptyList()
    val collectionReturn: BridgeType.Collection? =
      (returnShape as? ForwardLegacyReturnShape.Marshalled)?.type
    if (collectionReturn != null) tracker.trackCollection(collectionReturn)

    // ADR-114: a collection parameter takes the public collection type with an IntPtr native
    // slot; every other parameter keeps mapParamType's shipped spelling.
    val methodParams: List<CirParameter> =
      legacyRouteParameters(method.parameters, classifier, tracker)

    // Issue #108: carry the nullability through, same as the top-level suspend route.
    val asyncReturnType: String = when {
      isUnit -> ""
      collectionReturn != null -> collectionReturn.forwardPublicCsharpType()
      else -> {
        val csharp: String = KOTLIN_TO_CSHARP_PARAM[methodReturn] ?: methodReturn
        if (resolvedReturn?.isMarkedNullable == true) "$csharp?" else csharp
      }
    }

    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", "IntPtr"),
      CirParameter("scopeHandle", "IntPtr"),
    ) + methodParams +
        listOf(
          // ADR-102: a raw thunk address, not a delegate the marshaller would have to build a
          // native-to-managed stub for. The native symbol is unchanged; Kotlin is untouched.
          CirParameter("callback", "IntPtr"),
          CirParameter("userData", "IntPtr"),
        )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_async",
      returnType = "IntPtr",
      name = nativeStem,
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val taskReturnType: String = if (isUnit) "Task" else "Task<$asyncReturnType>"

    val asyncMethod = CirMethod(
      name = "${csMethodName}Async",
      nativeName = nativeStem,
      returnType = taskReturnType,
      parameters = methodParams,
      body = "",
      isAsync = true,
      asyncReturnType = asyncReturnType,
      asyncResultRead = collectionReturn?.let { legacyCollectionRead("resultPtr", it) },
    )

    listOf(nativeImport, asyncMethod)
  }

  // ADR-068: `suspend fun` returning StateFlow<T>/MutableStateFlow<T> -- the `_async` export is
  // byte-for-byte the plain-async shape above (it already boxes the awaited StateFlow object as
  // a StableRef handle; SuspendFunctionExports.kt needs no change). The rendered method differs:
  // `renderAsyncMethod` recognizes the `KotlinStateFlow<` asyncReturnType prefix and wraps the
  // awaited handle in a handle-owning KotlinStateFlow<T> via the shared
  // `nuget_stateflow_collect`/`nuget_stateflow_value` exports, instead of `new T(resultPtr)`.
  val suspendStateFlowMembers: List<CirMember> = stateFlowMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    // ADR-118: a separate composition site, so it has to read the same number. The counter is
    // shared with the plain-async half, since the planner numbers over every declared member
    // regardless of which of these two buckets later claims it.
    val suffix: String = callableCatalog.overloadSuffix(method)
    val cname: String = toCName(methodName) + suffix
    val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
    val nativeStem: String = "Native_$csMethodName${suffix}Async"
    val returnType = method.returnType?.resolve()?.expandAliases()
    val flowElementTypeResolved: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    // v1 scope (ADR-068): nullable element/member is deferred; mirror ADR-065's plain (non-null)
    // shape only.
    val flowCsElementType: String = qualifiedElementCsType(flowElementTypeResolved, context)

    // ADR-114: a collection parameter takes the public collection type with an IntPtr native
    // slot; every other parameter keeps mapParamType's shipped spelling.
    val methodParams: List<CirParameter> =
      legacyRouteParameters(method.parameters, classifier, tracker)

    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", "IntPtr"),
      CirParameter("scopeHandle", "IntPtr"),
    ) + methodParams +
        listOf(
          // ADR-102: a raw thunk address, not a delegate the marshaller would have to build a
          // native-to-managed stub for. The native symbol is unchanged; Kotlin is untouched.
          CirParameter("callback", "IntPtr"),
          CirParameter("userData", "IntPtr"),
        )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_async",
      returnType = "IntPtr",
      name = nativeStem,
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val asyncReturnType = "KotlinStateFlow<$flowCsElementType>"

    val asyncMethod = CirMethod(
      name = "${csMethodName}Async",
      nativeName = nativeStem,
      returnType = "Task<$asyncReturnType>",
      parameters = methodParams,
      body = "",
      isAsync = true,
      asyncReturnType = asyncReturnType,
    )

    listOf(nativeImport, asyncMethod)
  }

  return asyncMembers + suspendStateFlowMembers
}

internal fun translateSealedClass(
  cls: KSClassDeclaration,
  context: NugetContext,
  tracker: CollectionHelperTracker,
  callableCatalog: ForwardCallablePlanCatalog,
  // ADR-118: the sealed route classifies too now -- the arm's suspend members go through the same
  // `legacyRouteParameters`/`legacyRefusedParameter` rules an ordinary class's do.
  classifier: ForwardBridgeTypeClassifier,
  // Issue #111: the residual legacy lambda-property route below needs both halves the ordinary
  // class arm already had -- the export set to decide whether a type argument is nameable, and a
  // logger to say so when it is not.
  exportedTypes: Set<String>,
  logger: KSPLogger,
): CirSealedClass {
  val libraryName: String = context.libraryName
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()

  val subclasses: List<CirSealedSubclass> = cls.getSealedSubclasses()
    .map { subclass ->
      val subName: String = subclass.simpleName.asString()
      val subPrefix: String = "${prefix}_${subName.lowercase()}"
      val isDataClass: Boolean = subclass.modifiers.contains(Modifier.DATA)
      val isNested: Boolean =
        subclass.parentDeclaration?.qualifiedName?.asString() == cls.qualifiedName?.asString()
      // ADR-009 amendment (2026-09-11): an `open` arm is extensible, which is what unlocks both
      // `public class` in the renderer and `virtual` on its own open members below.
      val isOpenArm: Boolean = subclass.modifiers.contains(Modifier.OPEN)

      val subQualifiedName: String? = subclass.qualifiedName?.asString()
      val properties: List<CirProperty> = subclass.getAllProperties()
        .filter { it.getVisibility() == Visibility.PUBLIC }
        .mapNotNull { prop ->
          val propName: String = prop.simpleName.asString()
          // ADR-111: the plan owns every ordinary property shape here, exactly as it does for an
          // ordinary class. A type it cannot express is absent from C#, with the property
          // planner's own SKIPPED_UNSUPPORTED_PROPERTY diagnostic behind it -- no local skip
          // list decides that any more.
          val planned: ForwardPropertyPlan? =
            subQualifiedName?.let { callableCatalog.propertyFor("$it.$propName") }
          if (planned != null) {
            tracker.trackProperty(planned)
            return@mapNotNull ForwardCirPropertyProjection.classProperty(
              planned,
              // ADR-009 amendment (2026-09-11): gated on the arm being open. An `open val` on a
              // final arm is effectively final in Kotlin (nothing can extend it), and `virtual`
              // inside a `public sealed class` is CS0549. `isOverride` stays false: the generated
              // sealed base declares no member to override (CS0115).
              isVirtual = isOpenArm && prop.modifiers.isOpenForOverride(),
            )
          }

          // Issue #121: same gate as the ordinary-class arm above. The planner declined, and a
          // marked declaration must reach neither artifact.
          if (prop.isOptInRefused()) return@mapNotNull null

          // ADR-124: the arm's Flow/StateFlow properties, off the same `flowProperty` an ordinary
          // class calls, so the externs, the element spelling and the getter body are an ordinary
          // class's. The arm's own C# name goes in, because the getter bakes
          // `ObjectDisposedException(nameof(...))` and the receiver is the arm.
          if (prop.type.resolve().expandAliases().isForwardFlowType()) {
            return@mapNotNull flowProperty(prop, subName, context, classifier, tracker)
          }

          // Residual legacy route: a lambda-typed property, whose Kotlin half is still
          // hand-spelled in `SealedClassExports` too. It swallows the error slot (`out _`) until
          // lambda properties migrate for ordinary classes.
          val propTypeResolved: KSType = prop.type.resolve().expandAliases()
          val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()
          if (qualifiedTypeName !in LAMBDA_TYPES) return@mapNotNull null
          val lambdaArity: Int = propTypeResolved.arguments.size - 1
          tracker.lambdaArities.add(lambdaArity)
          // Issue #111, the sealed-subclass copy of the same rule as the ordinary-class arm.
          val unnameableTypeArgument: CsTypeArgument.Unnameable? =
            csTypeArguments(propTypeResolved.arguments, exportedTypes, context)
          if (unnameableTypeArgument != null) {
            ForwardDiagnosticSink.emit(
              listOf(
                lambdaTypeArgumentDiagnostic(
                  kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
                  symbol = prop,
                  declaration = "$subName.$propName",
                  typeArgument = unnameableTypeArgument.typeArgument,
                ),
              ),
              logger,
            )
            return@mapNotNull null
          }
          val lambdaTypeArgs: List<String> =
            csTypeArgumentNames(propTypeResolved.arguments, exportedTypes, context)
          val lambdaCsType: String = csLambdaType(lambdaTypeArgs)
          CirProperty(
            name = propName.replaceFirstChar { it.uppercase() },
            type = lambdaCsType,
            nativeReturnType = "IntPtr",
            nativeName = propName,
            getter = "new $lambdaCsType(Native_Get_$propName(_handle, out _))",
            setter = null,
            hasSyncErrorOut = true,
          )
        }
        .toList()

      // ADR-116: the method half of ADR-111. The arm's declared member functions come off the same
      // catalog an ordinary class reads (`classMethods`), projected by the same `classMethod`, so
      // the error slot, the overload numbering and the wire types agree with the Kotlin half by
      // construction. `isOverride` is pinned false by the planner (the generated sealed base
      // declares nothing to override, CS0115); `isVirtual` rides the plan, which computes it from
      // the arm being `open` the same way `classEntries` computes an ordinary class's (ADR-009
      // amendment 2026-09-11).
      val methodPlans: List<ForwardCallablePlan> =
        subQualifiedName?.let { callableCatalog.classMethods(it) } ?: emptyList()
      val methods: List<CirMethod> = methodPlans.map { plan ->
        tracker.trackPlan(plan)
        ForwardCirPlanProjection.classMethod(
          plan = plan,
          nativePrefix = subPrefix,
          isOverride = false,
          isVirtual = plan.publicSignature.isVirtual,
        )
      }
      // ADR-034's collision guard, which the sealed route never ran: two arm methods whose C#
      // signatures agree (`set(x: Foo)` / `set(x: Foo?)`) are CS0111 in the generated file.
      emitCsharpSignatureCollisions(methods, "$name.$subName", subclass, logger)

      // ADR-118: the arm's declared `suspend` members ride the legacy suspend route under the
      // arm's own export prefix, projected by the same `suspendMembers` an ordinary class calls,
      // so the arm's externs and bodies are an ordinary class's.
      val armSuspendMethods: List<KSFunctionDeclaration> = subclass.getAllFunctions()
        .filter { it.getVisibility() == Visibility.PUBLIC }
        // Declared-only, the same `parentDeclaration == subclass` gate the plan methods above and
        // the planner's `sealedSubclassEntries` use: a base `open suspend fun` no arm overrides
        // belongs to no arm.
        .filter { it.parentDeclaration == subclass }
        .filter { it.modifiers.contains(Modifier.SUSPEND) }
        // ADR-114: the refusal `translateClass` applies upstream of its own projection. Both
        // halves must agree, or a C# import arrives with no Kotlin export behind it.
        .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
        // ADR-119: the return-side refusal, same rule.
        .filter { method -> classifier.legacyRefusedReturn(method) == null }
        .toList()
      val asyncMembers: List<CirMember> = suspendMembers(
        suspendMethods = armSuspendMethods,
        prefix = subPrefix,
        libraryName = libraryName,
        classifier = classifier,
        tracker = tracker,
        callableCatalog = callableCatalog,
        context = context,
      )

      // ADR-124: the arm's declared Flow/StateFlow-returning methods, on the same legacy route
      // under the arm's own export prefix. `forwardArmFlowMethods` is the one selector the Kotlin
      // export loop and both gates read, so the two halves cannot disagree about the member set.
      val flowMembers: List<CirMember> = flowMembers(
        flowMethods = subclass.forwardArmFlowMethods(classifier),
        prefix = subPrefix,
        libraryName = libraryName,
        classifier = classifier,
        tracker = tracker,
        callableCatalog = callableCatalog,
        context = context,
      )

      CirSealedSubclass(
        name = subName,
        nativePrefix = subPrefix,
        properties = properties,
        methods = methods,
        asyncMembers = asyncMembers,
        flowMembers = flowMembers,
        // Derived from what projected, not from a `getAllFunctions()` scan: a base-declared or
        // ADR-114 refused suspend member would otherwise hand the arm a scope, `IAsyncDisposable`
        // and `DisposeAsync` with no async method on it to use them. ADR-124: a flow member needs
        // the same scope, so one boolean covers both routes and the arm cannot emit two.
        hasSuspendMethods = asyncMembers.isNotEmpty() || flowMembers.isNotEmpty() ||
            properties.any { property -> property.isFlow },
        isDataClass = isDataClass,
        isNested = isNested,
        isOpen = isOpenArm,
      )
    }
    .toList()

  return CirSealedClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    subclasses = subclasses,
  )
}

/**
 * ADR-034's C# signature-collision guard, shared by every generated container (ADR-090's class
 * methods, ADR-095's objects, companions, top-level file classes and `{Receiver}Extensions`).
 *
 * C# cannot declare two members of one type whose name and parameter types agree, and overloads
 * that differ only in *reference* nullability render identically (nullable value types do not), so
 * such a pair is uncompilable output — fail fast rather than emit CS0111/CS0663. The receiver of an
 * extension is already the first [CirParameter], which is exactly how C# distinguishes extension
 * overloads, so no special case is needed for it.
 */
internal fun emitCsharpSignatureCollisions(
  methods: List<CirMethod>,
  container: String,
  symbol: KSNode?,
  logger: KSPLogger,
) {
  methods
    .map { method ->
      listOf(method.name) + method.parameters.map { param ->
        val stripReferenceNullability: Boolean = param.isReferenceType && param.type.endsWith("?")
        if (stripReferenceNullability) param.type.dropLast(1) else param.type
      }
    }
    .groupBy { signature -> signature }
    .filterValues { group -> group.size > 1 }
    .keys
    .forEach { signature ->
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
            symbol = symbol,
            declaration = "$container.${signature.first()}",
            reason = "two or more overloads render identical C# parameter types; C# cannot " +
                "declare two methods with the same signature (ADR-034)",
            hint = "rename one overload, change a parameter's type so the rendered C# " +
                "signatures differ, or remove the default value whose synthesized overload " +
                "collides (ADR-096)",
          ),
        ),
        logger,
      )
    }
}

internal fun translateObject(
  obj: KSClassDeclaration,
  libraryName: String,
  callableCatalog: ForwardCallablePlanCatalog,
  tracker: CollectionHelperTracker,
  logger: KSPLogger,
): CirObject {
  val name: String = obj.simpleName.asString()
  val prefix: String = name.lowercase()

  // Object methods are static (no receiver handle), so they route through the same
  // shape as top-level functions (CirFunctionTranslator's static template) rather than
  // the class instance-method loop, which hardcodes _handle. See the comment above
  // this function's call site / ADR-060 cells 1 & 25.
  // ADR-095: members come off the catalog (per-object overload numbering; see `addObjectExports`).
  // This also picks up the planner's `parentDeclaration == obj` filter, which this walk never had.
  val methods: List<CirMember> = callableCatalog
    .objectMethods(obj.qualifiedName?.asString() ?: name)
    .flatMap { planned ->
      tracker.trackPlan(planned)
      ForwardCirPlanProjection.static(planned, libraryName)
    }

  emitCsharpSignatureCollisions(
    methods = methods.filterIsInstance<CirMethod>(),
    container = name,
    symbol = obj,
    logger = logger,
  )

  return CirObject(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    methods = methods,
  )
}

internal fun translateCompanionProperty(
  prop: KSPropertyDeclaration,
  libraryName: String,
  classPrefix: String,
): List<CirMember> {
  val propName: String = prop.simpleName.asString()
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.simpleName.asString()
  val isMutable: Boolean = prop.isMutable
  val csPropName: String = propName.replaceFirstChar { it.uppercase() }

  if (propType !in KOTLIN_TO_CSHARP_RETURN) return emptyList()

  val members: MutableList<CirMember> = mutableListOf()

  val csNativeReturnType: String = mapReturnType(propType)

  members.add(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = "${classPrefix}_companion_get_$propName",
      returnType = csNativeReturnType,
      name = "Native_Companion_Get_$propName",
      parameters = emptyList(),
      visibility = CirVisibility.PRIVATE,
    )
  )

  val csType: String
  val getter: String
  val setter: String?

  if (propType == "String") {
    csType = "string"
    getter = "Marshal.PtrToStringUTF8(Native_Companion_Get_$propName())!"
    setter = if (isMutable) "Native_Companion_Set_$propName(value)" else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "${classPrefix}_companion_set_$propName",
          returnType = "void",
          name = "Native_Companion_Set_$propName",
          parameters = listOf(CirParameter("value", "string")),
          visibility = CirVisibility.PRIVATE,
        )
      )
    }
  } else {
    csType = csNativeReturnType
    getter = "Native_Companion_Get_$propName()"
    setter = if (isMutable) "Native_Companion_Set_$propName(value)" else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "${classPrefix}_companion_set_$propName",
          returnType = "void",
          name = "Native_Companion_Set_$propName",
          parameters = listOf(CirParameter("value", csNativeReturnType)),
          visibility = CirVisibility.PRIVATE,
        )
      )
    }
  }

  members.add(
    CirProperty(
      name = csPropName,
      type = csType,
      nativeReturnType = csNativeReturnType,
      nativeName = propName,
      getter = getter,
      setter = setter,
      isStatic = true,
    )
  )

  return members
}

internal fun translateCompanionFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  className: String,
  tracker: CollectionHelperTracker,
): List<CirMember> {
  val methodName: String = func.simpleName.asString()
  val cname: String = toCName(methodName)
  val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
  val returnType = func.returnType?.resolve()?.expandAliases()
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  val params: List<CirParameter> = func.parameters.map { param ->
    val kotlinType: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
    CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
  }

  val entryPoint: String = "${classPrefix}_companion_${cname}"
  val nativeName: String = "Native_Companion_$csMethodName"
  val paramNames: String = params.joinToString(", ") { it.name }
  val nativeCallArgs: String = if (paramNames.isEmpty()) {
    "out IntPtr error"
  } else {
    "$paramNames, out IntPtr error"
  }
  val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
  val isListReturn: Boolean = returnQualified in setOf(
    "kotlin.collections.List",
    "kotlin.collections.MutableList",
  )
  val isMutableListReturn: Boolean = returnQualified == "kotlin.collections.MutableList"
  val listElementType: String? = if (isListReturn) {
    val elementType: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
  } else null
  if (isListReturn) tracker.needsList = true

  fun nativeImport(returnType: String): CirDllImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = entryPoint,
    returnType = returnType,
    name = nativeName,
    parameters = params,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )

  val returnsEnclosingClass: Boolean = kotlinReturnType == className
  val isObjectReturn: Boolean = returnsEnclosingClass ||
      (kotlinReturnType !in KOTLIN_TO_CSHARP_RETURN && kotlinReturnType != "Unit" && !isListReturn)

  if (isListReturn) {
    val returnType: String = if (isMutableListReturn) {
      "IList<$listElementType>"
    } else {
      "IReadOnlyList<$listElementType>"
    }
    val body: String = buildString {
      appendLine()
      appendLine("                IntPtr listHandle = $nativeName($nativeCallArgs);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      appendLine("                int count = NugetListNative.Count(listHandle);")
      appendLine("                var result = new List<$listElementType>(count);")
      appendLine("                for (int i = 0; i < count; i++)")
      appendLine("                {")
      appendLine("                    result.Add(NugetMarshal.FromHandle<$listElementType>(NugetListNative.Get(listHandle, i)));")
      appendLine("                }")
      appendLine("                NugetListNative.Dispose(listHandle);")
      append(if (isMutableListReturn) "                return result;" else "                return result.AsReadOnly();")
    }
    val wrapper = CirMethod(
      name = csMethodName,
      returnType = returnType,
      nativeReturnType = "IntPtr",
      nativeName = nativeName,
      parameters = params,
      body = body,
      isStatic = true,
      isSyncErrorCheckEnabled = true,
      hasCustomBody = true,
    )

    return listOf(nativeImport("IntPtr"), wrapper)
  }

  if (isObjectReturn) {
    val wrapper = CirMethod(
      name = csMethodName,
      returnType = kotlinReturnType,
      nativeReturnType = "IntPtr",
      nativeName = nativeName,
      parameters = params,
      body = buildString {
        appendLine()
        appendLine("                IntPtr nativeResult = $nativeName($nativeCallArgs);")
        appendLine("                if (error != IntPtr.Zero)")
        appendLine("                {")
        appendLine("                    throw NugetErrorNative.BuildException(error);")
        appendLine("                }")
        append("                return new $kotlinReturnType(nativeResult);")
      },
      isStatic = true,
      isSyncErrorCheckEnabled = true,
      hasCustomBody = true,
    )

    return listOf(nativeImport("IntPtr"), wrapper)
  }

  val csReturnType: String = when (kotlinReturnType) {
    "Unit" -> "void"
    "String" -> "string"
    else -> mapReturnType(kotlinReturnType)
  }
  val nativeReturnType: String = if (kotlinReturnType == "String") "IntPtr" else csReturnType
  val wrapper = CirMethod(
    name = csMethodName,
    returnType = csReturnType,
    nativeReturnType = nativeReturnType,
    nativeName = nativeName,
    parameters = params,
    body = "",
    isStatic = true,
    isSyncErrorCheckEnabled = true,
  )

  return listOf(nativeImport(nativeReturnType), wrapper)
}

/**
 * ADR-113: the generated `IFoo` declaration, projected from the SAME forward plan every
 * implementation of it is projected from.
 *
 * [callableCatalog] here is the *declaration* catalog (`NugetProcessor`'s second, non-reachable-
 * inclusive one), not the export-driving one: ADR-040 keeps `IFoo` unconditional, so its member
 * list cannot be reachability-driven the way the backing class and the `foo_*` exports are.
 *
 * Members with no plan are omitted silently. The skip was already reported once by the route that
 * planned the member (the implementing class's own, or, for a reachable interface, the interface
 * planner's own drops merged at the `callableCatalog` construction site), so a second diagnostic
 * naming the same Kotlin declaration is duplicate noise.
 */
internal fun translateInterface(
  iface: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
): CirInterface {
  val name: String = iface.simpleName.asString()
  val interfaceName: String = "I$name"
  val qualified: String = iface.qualifiedName?.asString() ?: name

  val typeParams: List<CirTypeParameter> = iface.typeParameters.map { param ->
    val variance: CirVariance = when (param.variance) {
      Variance.COVARIANT -> CirVariance.COVARIANT
      Variance.CONTRAVARIANT -> CirVariance.CONTRAVARIANT
      else -> CirVariance.INVARIANT
    }
    CirTypeParameter(param.name.asString(), variance = variance)
  }

  val typeParamNames: Set<String> = typeParams.map { it.name }.toSet()

  // Read off the catalog rather than re-deriving a plan key per `getAllProperties()` /
  // `getAllFunctions()` entry: the planner owns member ordering and naming, and a declaration walk
  // re-derives the wrong plan as soon as two declared members share a simple name (ADR-090).
  val propertyPlans: List<ForwardPropertyPlan> = callableCatalog.propertyPlans
    .filter { plan -> plan.symbol.substringBeforeLast('.') == qualified }
  val plannedProperties: List<CirInterfaceProperty> = propertyPlans.map { plan ->
    // `hasSetter` deliberately stays at its default: ADR-113 leaves a `var` interface property
    // rendering `{ get; }`, since `{ get; set; }` would be CS0535 against an implementing class
    // whose own setter ADR-075 dropped.
    CirInterfaceProperty(plan.publicName, ForwardCirPropertyProjection.publicType(plan))
  }
  val properties: List<CirInterfaceProperty> =
    plannedProperties + typeParameterProperties(iface, typeParamNames, plannedProperties)

  val methodPlans: List<ForwardCallablePlan> = callableCatalog.classMethods(qualified)
  val plannedMethods: List<CirInterfaceMethod> = methodPlans.map { plan ->
    CirInterfaceMethod(
      name = plan.publicSignature.csharpName,
      returnType = plan.publicSignature.result.forwardPublicCsharpType(),
      parameters = plan.publicSignature.parameters.map { parameter ->
        CirParameter(parameter.csharpName, parameter.type.forwardPublicCsharpType())
      },
    )
  }

  val methods: List<CirInterfaceMethod> =
    plannedMethods + typeParameterMethods(iface, typeParamNames, plannedMethods)

  emitInterfaceNameCollisions(interfaceName, iface, propertyPlans, methodPlans, logger)

  return CirInterface(interfaceName, typeParams, properties, methods)
}

/**
 * ADR-113 carve-out: an interface member whose signature mentions the interface's OWN class type
 * parameter, which the forward planner has no entry for and which would otherwise vanish from
 * `IFoo` (`interface Readable<out T> { fun read(): T }` losing `T Read();`).
 *
 * These members are not unbridgeable, they are unplanned: `T Read()` is valid C# inside
 * `interface IReadable<T>` and rendered correctly before the plan became the source of truth. Per
 * issue #111's rule a type parameter stays BARE, needing no qualification, which is why the
 * pre-plan spelling was already right for exactly these members and no others.
 *
 * Deliberately narrow. The condition is "this signature names one of [typeParamNames]", NOT "the
 * plan has no entry": a super-interface member, an unbridgeable collection and a `ByteArray?`
 * return are all unplanned too, and every one of them must keep dropping rather than come back as
 * a raw `IntPtr`.
 */
/** The property half of the ADR-113 carve-out documented on [typeParameterMethods]. */
private fun typeParameterProperties(
  iface: KSClassDeclaration,
  typeParamNames: Set<String>,
  planned: List<CirInterfaceProperty>,
): List<CirInterfaceProperty> {
  if (typeParamNames.isEmpty()) return emptyList()
  val plannedNames: Set<String> = planned.map { it.name }.toSet()

  return iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop -> prop.parentDeclaration == iface }
    .mapNotNull { prop ->
      val typeName: String = prop.type.resolve().expandAliases().declaration.simpleName.asString()
      if (typeName !in typeParamNames) return@mapNotNull null
      val csName: String = prop.simpleName.asString().replaceFirstChar { it.uppercase() }
      if (csName in plannedNames) return@mapNotNull null
      CirInterfaceProperty(csName, typeName)
    }
    .toList()
}

private fun typeParameterMethods(
  iface: KSClassDeclaration,
  typeParamNames: Set<String>,
  planned: List<CirInterfaceMethod>,
): List<CirInterfaceMethod> {
  if (typeParamNames.isEmpty()) return emptyList()
  val plannedShapes: Set<Pair<String, Int>> = planned.map { it.name to it.parameters.size }.toSet()

  return iface.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
    .filter { method -> method.parentDeclaration == iface }
    .mapNotNull { method ->
      val returnName: String? = method.returnType?.resolve()?.expandAliases()
        ?.declaration?.simpleName?.asString()
      val paramNames: List<String> = method.parameters.map { param ->
        param.type.resolve().expandAliases().declaration.simpleName.asString()
      }
      val mentionsTypeParameter: Boolean =
        returnName in typeParamNames || paramNames.any { it in typeParamNames }
      if (!mentionsTypeParameter) return@mapNotNull null

      val csMethodName: String = method.simpleName.asString().replaceFirstChar { it.uppercase() }
      // A planned member of the same shape would be a duplicate declaration (CS0111). Cannot
      // happen today, since a class type parameter never classifies into a BridgeType, but the
      // carve-out must not be the thing that discovers otherwise.
      if (csMethodName to method.parameters.size in plannedShapes) return@mapNotNull null

      val csReturnType: String = when {
        returnName in typeParamNames -> requireNotNull(returnName)
        returnName == "String" -> "string"
        returnName == "Unit" || returnName == null -> "void"
        else -> mapReturnType(returnName)
      }
      val params: List<CirParameter> = method.parameters.mapIndexed { index, param ->
        val kotlinType: String = paramNames[index]
        val csType: String =
          if (kotlinType in typeParamNames) kotlinType
          else mapParamType(kotlinType)
        CirParameter((param.name?.asString() ?: "_").csharpParameterName(), csType)
      }
      CirInterfaceMethod(csMethodName, csReturnType, params)
    }
    .toList()
}

/**
 * ADR-113 Decision E, following ADR-110's settled precedent: a Kotlin interface declaring both
 * `val tag` and `fun tag(n)` renders one C# member name twice, which is CS0102 inside an
 * `interface` exactly as it is inside a class. Fatal with no rename, because renaming either member
 * would be a silently different API.
 *
 * Runs over the POST-filter member lists. Issue #112's own Kotlin has `val collarTag` next to an
 * unbridgeable `fun collarTag(code: Int): ByteArray?`, so the method has already dropped out and
 * that hierarchy still builds; a pre-filter guard would fail a real reporter's working library.
 */
private fun emitInterfaceNameCollisions(
  interfaceName: String,
  iface: KSClassDeclaration,
  propertyPlans: List<ForwardPropertyPlan>,
  methodPlans: List<ForwardCallablePlan>,
  logger: KSPLogger,
) {
  val propertyNames: Map<String, ForwardPropertyPlan> = propertyPlans.associateBy { it.publicName }
  methodPlans.forEach { plan ->
    val property: ForwardPropertyPlan =
      propertyNames[plan.publicSignature.csharpName] ?: return@forEach
    val kotlinName: String = plan.invocation.symbol.substringAfterLast('.')
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
          symbol = iface,
          declaration = "$interfaceName.${plan.publicSignature.csharpName}",
          reason = "the interface property '${property.kotlinName}' already claims that C# name, " +
              "and C# cannot declare a property and a method with one name (CS0102)",
          hint = "rename the Kotlin function '$kotlinName' or the property it collides with",
        ),
      ),
      logger,
    )
  }
}

/**
 * ADR-040: the concrete handle-backed wrapper class generated alongside `IFoo` for a reachable
 * Kotlin interface (one that appears in a planned return position — see the reachable-interfaces
 * computation in [io.github.xxfast.kotlin.native.nuget.processor.NugetProcessor]). Deliberately
 * reuses [CirClass] rather than a bespoke declaration node: every member here already has a
 * dispatch-export plan built by `ForwardCallablePlanner.interfaceEntries` /
 * `ForwardPropertyPlanner.interfaceProperties`, so the existing property/method DllImport
 * derivation, dispose rendering and ABI-contract signature extraction all apply unchanged — a
 * dispatch export IS an ordinary class-shaped native import once its plan exists.
 */
internal fun translateInterfaceBackingClass(
  iface: KSClassDeclaration,
  libraryName: String,
  callableCatalog: ForwardCallablePlanCatalog,
  tracker: CollectionHelperTracker,
): CirClass {
  val name: String = iface.simpleName.asString()
  val prefix: String = name.lowercase()
  val ifaceQualified: String = iface.qualifiedName?.asString() ?: name

  val properties: List<CirProperty> = iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop -> prop.parentDeclaration == iface }
    .mapNotNull { prop ->
      val symbol = "$ifaceQualified.${prop.simpleName.asString()}"
      callableCatalog.propertyFor(symbol)?.let { plan ->
        tracker.trackProperty(plan)
        ForwardCirPropertyProjection.classProperty(plan)
      }
    }
    .toList()

  val methods: List<CirMethod> = iface.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
    .filter { method -> method.parentDeclaration == iface }
    .mapNotNull { method ->
      val symbol = "$ifaceQualified.${method.simpleName.asString()}"
      callableCatalog.planFor(symbol)?.let { plan ->
        tracker.trackPlan(plan)
        ForwardCirPlanProjection.classMethod(plan, prefix, isOverride = false)
      }
    }
    .toList()

  return CirClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    constructor = null,
    properties = properties,
    methods = methods,
    interfaces = listOf("I$name"),
    hasInternalHandleConstructor = true,
    isSealed = true,
  )
}

internal fun translateEnum(
  enum: KSClassDeclaration,
  libraryName: String,
): CirEnum {
  val name: String = enum.simpleName.asString()
  val entries: List<CirEnumEntry> = enum.declarations
    .filterIsInstance<KSClassDeclaration>()
    .mapIndexed { index, entry ->
      val entryName: String = entry.simpleName.asString()
      val csEntryName: String = entryName.split("_")
        .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
      CirEnumEntry(csEntryName, index)
    }
    .toList()

  val properties: List<CirEnumProperty> = enum.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in setOf("name", "ordinal", "declaringJavaClass") }
    .map { prop ->
      val propName: String = prop.simpleName.asString()
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val propType: String = propTypeResolved.declaration.simpleName.asString()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }

      val nativeReturnType: String = mapReturnType(propType)
      val type: String = if (propType == "String") "string" else mapReturnType(propType)

      CirEnumProperty(
        name = csPropName,
        type = type,
        nativeReturnType = nativeReturnType,
        nativeName = propName,
      )
    }
    .toList()

  return CirEnum(name, libraryName, entries, properties)
}

internal fun translateValueClass(
  cls: KSClassDeclaration,
  libraryName: String,
  logger: KSPLogger,
  context: NugetContext,
  callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
): CirValueClass {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: name
  val prefix: String = name.lowercase()

  val underlyingParamName: String = cls.primaryConstructor!!.parameters.first().name!!.asString()
  val underlyingProp: KSPropertyDeclaration = cls.getAllProperties()
    .first { it.simpleName.asString() == underlyingParamName }

  val underlyingResolved: KSType = underlyingProp.type.resolve().expandAliases()
  val underlyingType: String = underlyingResolved.declaration.simpleName.asString()
  val underlyingName: String =
    underlyingProp.simpleName.asString().replaceFirstChar { it.uppercase() }
  // ADR-077 sub-item 4 prerequisite: an enum underlying is a *value* underlying (ordinal over the
  // int wire), not a reference one. The name-map check below cannot see that ("Mood" is not in
  // KOTLIN_TO_CSHARP_PARAM), and misclassifying it as reference used to defer the primary
  // constructor per ADR-035 while the planner (which classifies with BridgeType, not names) still
  // planned `_create` -- the exact one-sided ABI the contract check then failed on.
  val isEnumUnderlying: Boolean =
    (underlyingResolved.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
  val underlyingNativeType: String = if (isEnumUnderlying) "int" else mapParamType(underlyingType)
  val isReferenceUnderlying: Boolean =
    !isEnumUnderlying && underlyingType !in KOTLIN_TO_CSHARP_PARAM

  val nativeArg: String = if (isReferenceUnderlying) "${underlyingName}._handle" else underlyingName

  fun buildConstructorFromPlan(plan: ForwardCallablePlan, suffix: String): CirValueClassConstructor {
    return ForwardCirPlanProjection.valueClassConstructor(plan, suffix, underlyingType == "String")
  }

  val secondaryCtorDecls: List<KSFunctionDeclaration> = cls.declarations
    .filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "<init>" }
    .filter { it != cls.primaryConstructor }
    .toList()

  val constructors: List<CirValueClassConstructor> = if (isReferenceUnderlying) {
    // ADR-035: the positional record struct over the underlying handle is the only constructor.
    // A secondary has nothing to delegate to (the primary is deferred), so the planner skips it
    // and neither half emits anything -- no import, no export, no `this(CreateChecked(...))`
    // handing an IntPtr to a class-typed parameter.
    emptyList()
  } else {
    // ADR-035: plan-only for primitive-underlying constructors.
    buildList {
      val primaryPlan = callableCatalog.planFor("$qualifiedName.<init>")
      if (primaryPlan != null) add(buildConstructorFromPlan(primaryPlan, ""))
      secondaryCtorDecls.forEachIndexed { index, _ ->
        val number: Int = index + 2
        val planned = callableCatalog.planFor("$qualifiedName.<init>_$number")
        if (planned != null) add(buildConstructorFromPlan(planned, "_$number"))
      }
    }
  }

  // ADR-082: members come off the catalog, not from a per-declaration plan lookup — see
  // `addValueClassExports` for why (overload numbering makes the symbol per-declaration
  // underivable here, and the two halves must not drift).
  val properties: List<CirProperty> = callableCatalog.valueClassProperties(qualifiedName)
    .map { plan -> ForwardCirPlanProjection.valueClassProperty(plan, nativeArg) }

  val methods: List<CirMethod> = callableCatalog.valueClassMethods(qualifiedName)
    .map { plan -> ForwardCirPlanProjection.valueClassMethod(plan, nativeArg) }

  // C# cannot declare two methods whose parameter types are identical (ADR-034). Value-class
  // overloads share one public name, so two of them rendering the same C# parameter list is
  // uncompilable output — fail fast, the same way the constructor check above does. Reference
  // nullability is not part of a C# signature; nullable *value* types are.
  val methodSignatures: List<List<String>> = methods.map { method ->
    listOf(method.name) + method.parameters.map { param ->
      val stripReferenceNullability: Boolean = param.isReferenceType && param.type.endsWith("?")
      if (stripReferenceNullability) param.type.dropLast(1) else param.type
    }
  }
  val collidingSignatures: Set<List<String>> = methodSignatures
    .groupBy { it }
    .filterValues { it.size > 1 }
    .keys
  collidingSignatures.forEach { signature ->
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
          symbol = cls,
          declaration = "$name.${signature.first()}",
          reason = "two or more overloads render identical C# parameter types; C# cannot " +
              "declare two methods with the same signature (ADR-034)",
          hint = "rename one overload, or change a parameter's type so the rendered C# " +
              "signatures differ",
        ),
      ),
      logger,
    )
  }

  // The public member is the C# type itself (the enum `Mood`, the handle class `Patient`); only
  // the wire is its int ordinal / IntPtr handle. Spelling it with the *Kotlin* simple name only
  // resolved while the underlying happened to share the struct's own namespace: a root-package
  // value class over a sub-package underlying emitted a bare `Mood` inside `namespace TestLibrary`
  // and failed CS0246. Same helper, and same unconditional-qualification rule, that #41 applied at
  // the other cross-class render sites -- a known scalar keeps its C# primitive spelling (so the
  // renderer's `== "string"` wire checks still fire), everything else becomes
  // `global::Namespace.Name` once `rootNamespace` is non-empty.
  val csUnderlyingType: String = qualifiedElementCsType(underlyingResolved, context)

  return CirValueClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    underlyingType = csUnderlyingType,
    underlyingName = underlyingName,
    underlyingNativeType = underlyingNativeType,
    underlyingIsReference = isReferenceUnderlying,
    constructors = constructors,
    properties = properties,
    methods = methods,
  )
}

private fun translateCallbackMethod(
  method: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  exportedTypes: Set<String>,
  tracker: CollectionHelperTracker,
): CirCallbackMethod? {
  val methodName: String = method.simpleName.asString()
  val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
  val nativeEntryPoint: String = "${classPrefix}_$methodName"

  val lambdaParam = method.parameters.firstOrNull { param ->
    param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
  } ?: return null

  val lambdaParamName: String = (lambdaParam.name?.asString() ?: "callback").csharpParameterName()
  val lambdaType: KSType = lambdaParam.type.resolve().expandAliases()
  val lambdaArity: Int = lambdaType.arguments.size - 1

  val lambdaArgTypes: List<KSType> = lambdaType.arguments.dropLast(1)
    .mapNotNull { it.type?.resolve()?.expandAliases() }
  val lambdaRetType: KSType? = lambdaType.arguments.lastOrNull()?.type?.resolve()?.expandAliases()
  val lambdaRetKotlin: String = lambdaRetType?.declaration?.simpleName?.asString() ?: "Unit"
  val lambdaRetQualified: String =
    lambdaRetType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"

  val outerRetType: KSType? = method.returnType?.resolve()?.expandAliases()
  val outerRetKotlin: String = outerRetType?.declaration?.simpleName?.asString() ?: "Unit"
  val outerRetQualified: String =
    outerRetType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"

  val isOuterRetUnit: Boolean = outerRetQualified == "kotlin.Unit"
  val isOuterRetString: Boolean = outerRetQualified == "kotlin.String"
  val isOuterRetList: Boolean = outerRetQualified in setOf(
    "kotlin.collections.List", "kotlin.collections.MutableList",
  )

  // Delegate name: Nuget{Arg1}...{Return}Callback
  fun typeSuffix(kotlinType: String, qualified: String?): String = when {
    kotlinType == "Boolean" -> "Byte"
    kotlinType == "String" -> "String"
    kotlinType == "Unit" -> "Void"
    qualified != null && qualified in exportedTypes -> "Object"
    kotlinType in KOTLIN_TO_CSHARP_RETURN -> kotlinType
    else -> "Object"
  }

  val argSuffixes: List<String> = lambdaArgTypes.map { t ->
    typeSuffix(t.declaration.simpleName.asString(), t.declaration.qualifiedName?.asString())
  }
  val retSuffix: String = typeSuffix(lambdaRetKotlin, lambdaRetQualified)
  val delegateName: String = "Nuget${argSuffixes.joinToString("")}${retSuffix}Callback"

  // Delegate native return type and param list
  val delegateReturnType: String = when {
    lambdaRetKotlin == "Unit" -> "void"
    lambdaRetKotlin == "Boolean" -> "byte"
    else -> "IntPtr"
  }

  val delegateParamList: String = if (lambdaArity == 0) {
    "(IntPtr userData)"
  } else {
    val argParams: String = lambdaArgTypes
      .mapIndexed { i, _ -> "IntPtr arg${i}Ptr" }
      .joinToString(", ")
    "($argParams, IntPtr userData)"
  }

  val delegate = CirCallbackDelegate(delegateName, delegateParamList, delegateReturnType)
  if (tracker.callbackDelegates.none { it.name == delegateName }) {
    tracker.callbackDelegates.add(delegate)
  }

  // C# callback body (inside the nativeCallback lambda)
  val callbackBody: String = buildString {
    lambdaArgTypes.forEachIndexed { i, argType ->
      val argKotlin: String = argType.declaration.simpleName.asString()
      val csArgType: String = when (argKotlin) {
        "String" -> "string"
        else -> argKotlin
      }
      appendLine("            $csArgType arg$i = NugetMarshal.FromHandle<$csArgType>(arg${i}Ptr);")
    }
    val argCallNames: String = lambdaArgTypes.indices.joinToString(", ") { "arg$it" }
    val callExpr: String =
      if (lambdaArity == 0) "$lambdaParamName()" else "$lambdaParamName($argCallNames)"
    when {
      lambdaRetKotlin == "Unit" -> append("            $callExpr;")
      lambdaRetKotlin == "Boolean" -> append("            return $callExpr ? (byte)1 : (byte)0;")
      lambdaRetKotlin == "String" -> append("            return NugetMarshal.WrapString($callExpr);")
      else -> append("            return NugetMarshal.WrapString($callExpr);")
    }
  }

  // C# public method return type
  val csReturnType: String = when {
    isOuterRetUnit -> "void"
    isOuterRetString -> "string"
    isOuterRetList -> {
      val elemType = outerRetType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
      val elemKotlin: String = elemType?.declaration?.simpleName?.asString() ?: "string"
      val elemCs: String = KOTLIN_TO_CSHARP_PARAM[elemKotlin] ?: elemKotlin
      "IReadOnlyList<$elemCs>"
    }

    else -> outerRetKotlin
  }

  // C# param type (Func<> or Action<>)
  val csParamType: String = buildString {
    val isVoidReturn: Boolean = lambdaRetKotlin == "Unit"
    val argCsTypes: List<String> = lambdaArgTypes.map { t ->
      val k: String = t.declaration.simpleName.asString()
      KOTLIN_TO_CSHARP_PARAM[k] ?: k
    }
    if (isVoidReturn) {
      if (lambdaArity == 0) append("Action")
      else append("Action<${argCsTypes.joinToString(", ")}>")
    } else {
      val retCs: String = when {
        lambdaRetKotlin == "Boolean" -> "bool"
        lambdaRetKotlin == "String" -> "string"
        else -> KOTLIN_TO_CSHARP_PARAM[lambdaRetKotlin] ?: lambdaRetKotlin
      }
      val allTypes: List<String> = argCsTypes + retCs
      if (lambdaArity == 0) append("Func<$retCs>")
      else append("Func<${allTypes.joinToString(", ")}>")
    }
  }

  // C# wrapper body (inside try{})
  // ADR-102: the thunk address is a link-time constant and the ctx is the closure's own GCHandle,
  // both inlined at the call so the pair cannot drift apart.
  val nativeCall: String = "Native_$csMethodName(_handle, NugetThunks.${delegateName}Ptr, " +
      "GCHandle.ToIntPtr(cbHandle), out IntPtr error)"
  val wrapperBody: String = buildString {
    when {
      isOuterRetUnit -> appendLine("            $nativeCall;")
      isOuterRetString -> appendLine("            IntPtr nativeResult = $nativeCall;")
      isOuterRetList -> appendLine("            IntPtr listHandle = $nativeCall;")
      else -> appendLine("            IntPtr nativeHandle = $nativeCall;")
    }
    appendLine("            if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
    when {
      isOuterRetString -> append("            return Marshal.PtrToStringUTF8(nativeResult)!;")
      isOuterRetList -> {
        val elemType = outerRetType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
        val elemKotlin: String = elemType?.declaration?.simpleName?.asString() ?: "string"
        val elemCs: String = KOTLIN_TO_CSHARP_PARAM[elemKotlin] ?: elemKotlin
        appendLine("            int count = NugetListNative.Count(listHandle);")
        appendLine("            var result = new List<$elemCs>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$elemCs>(NugetListNative.Get(listHandle, i)));")
        appendLine("            }")
        appendLine("            NugetListNative.Dispose(listHandle);")
        append("            return result.AsReadOnly();")
      }

      !isOuterRetUnit -> append("            return new $outerRetKotlin(nativeHandle);")
    }
  }

  if (isOuterRetList) tracker.needsList = true

  val nativeImportReturnType: String = if (isOuterRetUnit) "void" else "IntPtr"

  return CirCallbackMethod(
    csMethodName = csMethodName,
    nativeEntryPoint = nativeEntryPoint,
    libraryName = libraryName,
    nativeImportReturnType = nativeImportReturnType,
    lambdaParamName = lambdaParamName,
    delegateName = delegateName,
    delegateParamList = delegateParamList,
    csReturnType = csReturnType,
    csParamType = csParamType,
    callbackBody = callbackBody,
    wrapperBody = wrapperBody,
  )
}

/**
 * Translates an `add{X}` / `remove{X}` method pair into a [CirStoredCallbackMethod].
 * The add method becomes a public `IDisposable Add{X}(Action<T> listener)` on the C# side;
 * the remove method is consumed internally by the generated NugetSubscription dispose action.
 *
 * Enum lambda arg types are passed as `int` (ordinal) at the C boundary rather than StableRef handles.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/037-stored-callbacks.md">ADR-037: Stored callbacks</a>
 */
private fun translateStoredCallbackMethod(
  addMethod: KSFunctionDeclaration,
  removeMethod: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  exportedTypes: Set<String>,
  tracker: CollectionHelperTracker,
  context: NugetContext,
): CirStoredCallbackMethod? {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()
  val csMethodName: String = addMethodName.replaceFirstChar { it.uppercase() }
  val csRemoveNativeName: String = "Native_${removeMethodName.replaceFirstChar { it.uppercase() }}"

  val lambdaParam = addMethod.parameters.firstOrNull { param ->
    param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
  } ?: return null

  val lambdaType: KSType = lambdaParam.type.resolve().expandAliases()
  val lambdaArgTypes: List<KSType> = lambdaType.arguments.dropLast(1)
    .mapNotNull { it.type?.resolve()?.expandAliases() }
  val lambdaArity: Int = lambdaArgTypes.size

  // For enum args: ordinal Int. For reference types: IntPtr.
  val isEnumArgs: List<Boolean> = lambdaArgTypes.map { argType ->
    (argType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
  }

  // Delegate naming for stored callbacks: enum ordinal -> "Int" suffix, object -> "Object" suffix.
  fun storedArgSuffix(argType: KSType, isEnum: Boolean): String = when {
    isEnum -> "Int"
    argType.declaration.simpleName.asString() == "Boolean" -> "Byte"
    argType.declaration.simpleName.asString() == "String" -> "Object"
    argType.declaration.qualifiedName?.asString() in KOTLIN_TO_CSHARP_RETURN ->
      argType.declaration.simpleName.asString()

    else -> "Object"
  }

  val argSuffixes: List<String> = lambdaArgTypes.mapIndexed { i, t -> storedArgSuffix(t, isEnumArgs[i]) }
  val delegateName: String = "Nuget${argSuffixes.joinToString("")}VoidCallback"

  // Delegate parameter list: enum -> int arg0Ord, object -> IntPtr arg0Ptr, arity-0 -> just userData
  val delegateParamList: String = if (lambdaArity == 0) {
    "(IntPtr _)"
  } else {
    val argParams: String = lambdaArgTypes.mapIndexed { i, argType ->
      if (isEnumArgs[i]) "int arg${i}Ord" else "IntPtr arg${i}Ptr"
    }.joinToString(", ")
    "($argParams, IntPtr _)"
  }

  val delegate = CirCallbackDelegate(delegateName, delegateParamList, "void")
  if (tracker.callbackDelegates.none { it.name == delegateName }) {
    tracker.callbackDelegates.add(delegate)
  }
  tracker.needsSubscription = true

  // C# param type for the public method: Action or Action<T1, T2, ...>
  // Issue #41: every argument spelling goes through `qualifiedElementCsType`, which keeps a known
  // scalar's C# primitive name and renders everything else `global::Namespace.Name` — a callback
  // argument type is no more guaranteed to share the subscribing class's namespace than a
  // `Flow<T>` element is (ADR-066 "Failure mode 2").
  val csParamType: String = buildString {
    val argCsTypes: List<String> = lambdaArgTypes.map { t -> qualifiedElementCsType(t, context) }
    if (lambdaArity == 0) append("Action")
    else append("Action<${argCsTypes.joinToString(", ")}>")
  }

  // nativeCallback body (inside the lambda assigned to the delegate variable)
  val nativeCallbackBody: String = buildString {
    lambdaArgTypes.forEachIndexed { i, argType ->
      val csType: String = qualifiedElementCsType(argType, context)
      if (isEnumArgs[i]) append("$csType arg$i = ($csType)arg${i}Ord; ")
      else append("$csType arg$i = NugetMarshal.FromHandle<$csType>(arg${i}Ptr); NugetMarshal.Dispose(arg${i}Ptr); ")
    }
    val callArgs: String = if (lambdaArity == 0) "" else
      lambdaArgTypes.indices.joinToString(", ") { "arg$it" }
    append("listener($callArgs);")
  }

  return CirStoredCallbackMethod(
    csMethodName = csMethodName,
    csRemoveNativeName = csRemoveNativeName,
    subscribeEntryPoint = "${classPrefix}_$addMethodName",
    removeEntryPoint = "${classPrefix}_$removeMethodName",
    libraryName = libraryName,
    delegateName = delegateName,
    delegateParamList = delegateParamList,
    csParamType = csParamType,
    nativeCallbackBody = nativeCallbackBody,
  )
}

/**
 * Translates an `add{X}` / `remove{X}` pair (where the parameter is a Kotlin interface type) into
 * a [CirInterfaceBridgeMethod]. The add method becomes a public `IDisposable Add{X}(IFace listener)`
 * on the C# side; the remove method is consumed internally by the generated NugetSubscription.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/039-interface-bridging.md">ADR-039: Interface bridging</a>
 */
private fun translateInterfaceBridgeMethod(
  addMethod: KSFunctionDeclaration,
  removeMethod: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  className: String,
  tracker: CollectionHelperTracker,
): CirInterfaceBridgeMethod? {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()
  val csMethodName: String = addMethodName.replaceFirstChar { it.uppercase() }
  val csRemoveNativeName: String = "Native_${removeMethodName.replaceFirstChar { it.uppercase() }}"

  val ifaceParam = addMethod.parameters.firstOrNull { param ->
    (param.type.resolve().expandAliases().declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.INTERFACE
  } ?: return null

  val ifaceDecl = ifaceParam.type.resolve().expandAliases().declaration as? KSClassDeclaration ?: return null
  val ifaceName: String = ifaceDecl.simpleName.asString()
  val interfaceCsName: String = "I$ifaceName"

  val ifaceMethods: List<KSFunctionDeclaration> = ifaceDecl.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .toList()

  tracker.needsSubscription = true

  val entries: List<CirInterfaceBridgeMethodEntry> = ifaceMethods.map { method ->
    val mName: String = method.simpleName.asString()
    val mCsName: String = mName.replaceFirstChar { it.uppercase() }
    val params = method.parameters.toList()
    val arity: Int = params.size

    // Delegate suffix naming follows stored-callback convention
    val argSuffixes: List<String> = params.map { param ->
      val pType = param.type.resolve().expandAliases()
      val pSimple: String = pType.declaration.simpleName.asString()
      val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
      val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
      val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
      when {
        isEnum -> "Int"
        pSimple == "Boolean" -> "Byte"
        isPrimitive -> pSimple
        else -> "Object"
      }
    }
    val delegateName: String = "Nuget${argSuffixes.joinToString("")}VoidCallback"

    // Delegate param list: enum -> int arg${i}Ord, reference/string -> IntPtr arg${i}Ptr, arity-0 -> IntPtr _
    val delegateParamList: String = if (arity == 0) {
      "(IntPtr _)"
    } else {
      val argParams: String = params.mapIndexed { i, param ->
        val pType = param.type.resolve().expandAliases()
        val pSimple: String = pType.declaration.simpleName.asString()
        val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
        val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
        val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
        when {
          isEnum -> "int arg${i}Ord"
          pSimple == "Boolean" -> "byte arg${i}"
          isPrimitive -> "${KOTLIN_TO_CSHARP_PARAM[pSimple] ?: "int"} arg${i}"
          else -> "IntPtr arg${i}Ptr"
        }
      }.joinToString(", ")
      "($argParams, IntPtr _)"
    }

    val delegate = CirCallbackDelegate(delegateName, delegateParamList, "void")
    if (tracker.callbackDelegates.none { it.name == delegateName }) {
      tracker.callbackDelegates.add(delegate)
    }

    // C# callback body: unmarshal args and call listener.Method(args)
    val callbackBody: String = buildString {
      params.forEachIndexed { i, param ->
        val pType = param.type.resolve().expandAliases()
        val pSimple: String = pType.declaration.simpleName.asString()
        val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
        val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
        val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
        val csType: String = when {
          isEnum -> pSimple
          pSimple == "Boolean" -> "bool"
          isPrimitive -> KOTLIN_TO_CSHARP_PARAM[pSimple] ?: pSimple
          pSimple == "String" -> "string"
          else -> pSimple
        }
        when {
          isEnum -> append("$csType arg$i = ($csType)arg${i}Ord; ")
          pSimple == "Boolean" -> append("$csType arg$i = arg${i} != 0; ")
          isPrimitive -> { /* arg is already the right type, no unmarshal needed */
          }

          else -> append("$csType arg$i = NugetMarshal.FromHandle<$csType>(arg${i}Ptr); NugetMarshal.Dispose(arg${i}Ptr); ")
        }
      }
      val callArgs: String = params.indices.joinToString(", ") { "arg$it" }
      append("listener.$mCsName($callArgs);")
    }

    CirInterfaceBridgeMethodEntry(
      methodCsName = mCsName,
      methodKtName = mName,
      delegateName = delegateName,
      delegateParamList = delegateParamList,
      callbackBody = callbackBody,
    )
  }

  return CirInterfaceBridgeMethod(
    csMethodName = csMethodName,
    csRemoveNativeName = csRemoveNativeName,
    subscribeEntryPoint = "${classPrefix}_$addMethodName",
    removeEntryPoint = "${classPrefix}_$removeMethodName",
    libraryName = libraryName,
    interfaceCsName = interfaceCsName,
    className = className,
    entries = entries,
  )
}

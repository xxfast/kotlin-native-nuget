package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.ExpectIndex
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import io.github.xxfast.kotlin.native.nuget.processor.csharpParameterName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLambdaPropertyCarrier
import io.github.xxfast.kotlin.native.nuget.processor.forward.carriesLegacyLambdaProperty
import io.github.xxfast.kotlin.native.nuget.processor.forward.reProjectsKeptBaseLambdaProperty
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpAsyncMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpMemberName
import io.github.xxfast.kotlin.native.nuget.processor.kotlinConstantToPascalCase
import io.github.xxfast.kotlin.native.nuget.processor.forward.cirDoc
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardAsyncInterfaceForwards
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInterfaceDeclaresScopeMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInterfaceFlowMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInterfaceFlowProperties
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInterfaceSuspendMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardKdoc
import io.github.xxfast.kotlin.native.nuget.processor.forward.toCirDoc
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedCallbackMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.optInMarker
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedInterfaceBridgePair
import io.github.xxfast.kotlin.native.nuget.processor.forward.InterfaceBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateName
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateParamList
import io.github.xxfast.kotlin.native.nuget.processor.forward.interfaceBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.listenerPropertySlots
import io.github.xxfast.kotlin.native.nuget.processor.exports.hasPlannedCallbackParameter
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmFlowMethods
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmLambdaMethods
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember
import io.github.xxfast.kotlin.native.nuget.processor.exports.isForwardFlowType
import io.github.xxfast.kotlin.native.nuget.processor.exports.returnsHeldMutableStateFlow
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardLegacyPairMembers
import io.github.xxfast.kotlin.native.nuget.processor.exports.isForwardLegacyRoute
import io.github.xxfast.kotlin.native.nuget.processor.exports.refusedLegacyLambdaShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedStoredCallbackPair
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardInheritedOutcome
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardInterfaceHierarchy
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardAsyncPlacement
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardInterfaceMemberPlacement
import io.github.xxfast.kotlin.native.nuget.processor.forward.declared
import io.github.xxfast.kotlin.native.nuget.processor.forward.declaresLexically
import io.github.xxfast.kotlin.native.nuget.processor.forward.inheritedOutcomeOn
import io.github.xxfast.kotlin.native.nuget.processor.forward.inlineList
import io.github.xxfast.kotlin.native.nuget.processor.forward.interfaceMethodSymbols
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOptInRefused
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallableCatalogEntry
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardMethodTypeParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.ENUM_ARM_VALUE_MEMBER
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumArmName
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEnumArm
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEligibleSealedType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPlanProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPropertyProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumMembersOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumReceiverName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardFileClassOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardSkipPosition
import io.github.xxfast.kotlin.native.nuget.processor.forward.isPubliclySpellable
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticHint
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticReason
import io.github.xxfast.kotlin.native.nuget.processor.forward.toDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.droppedBaseChain
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticTypeName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ownsSentence
import io.github.xxfast.kotlin.native.nuget.processor.forward.escalatedForStrictDependencyTypes
import io.github.xxfast.kotlin.native.nuget.processor.forward.sealedAsHandle
import io.github.xxfast.kotlin.native.nuget.processor.forward.skipDetail
import io.github.xxfast.kotlin.native.nuget.processor.forward.skipReason
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardCsharpMethodTypeParameterName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardCsharpTypeParameterName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardTypeParametersInScope
import io.github.xxfast.kotlin.native.nuget.processor.forward.capturedTypeParameterOwners
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardMemberGenericRefusal
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInheritedSignatureKey
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardPublicCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSignatureKey
import io.github.xxfast.kotlin.native.nuget.processor.forward.isDeclaredBy
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardScopeOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardArmMemberProjectedByBase
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSealedBaseAsyncMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSealedBaseFlowProperties
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuspendRouteMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.reProjectsKeptBaseMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardLegacyAsyncRoute
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSupertypeNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardMemberOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.isSealedSubclass
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardArmSealedParent
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOpenForOverride
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOpenForOverrideOn
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardExtensible
import io.github.xxfast.kotlin.native.nuget.processor.forward.admits
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardSealedArmShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardSealedBaseArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSealedArmShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.isGenericSealedType
import io.github.xxfast.kotlin.native.nuget.processor.forward.isIntermediateGenericSealedArm
import io.github.xxfast.kotlin.native.nuget.processor.forward.abstractBackingName
import io.github.xxfast.kotlin.native.nuget.processor.forward.hasAbstractBacking
import io.github.xxfast.kotlin.native.nuget.processor.forward.overridesBaseClassMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.overridesKeptBaseOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyMarshalledRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyDiscriminatedRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementCollection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyFlowElementShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyGenericSealedElement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyBytesCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyBytesElementReadArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyBytesRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementReadArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedFlowElement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.declaredCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyInterfaceElementReadArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyInterfaceRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyHandleRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyValueClassRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyEnumRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.toCSharpName

/** Which half of issue #42 a dropped supertype is: both re-home their public members onto the
 *  owner, but they lose different relations, so they get different messages. */
private enum class SupertypeKind { INTERFACE, SUPER_INTERFACE, BASE_CLASS }

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
  keptBase: KSClassDeclaration? = null,
): Boolean {
  val qualified: String? = supertype.qualifiedName?.asString()
  if (qualified != null && qualified in exportedTypes) return true

  val simpleName: String = supertype.simpleName.asString()
  val supertypeName: String = qualified ?: simpleName
  val packageName: String = supertype.packageName.asString()
  // The real per-member outcome, read off the planned catalog: a re-homed member no route carries
  // is named by its own `SKIPPED_*` warning on [cls], so "bound on X directly" must not claim it.
  // All bound keeps the shipped sentences to the byte.
  val outcome: ForwardInheritedOutcome = supertype.inheritedOutcomeOn(cls, keptBase)
  // "its public members are bound on X directly", or the clause naming the members that are not.
  fun membersOn(owner: String, possessive: String, verb: String, verbNone: String): String {
    val unrouted: List<String> = outcome.unrouted
    if (unrouted.isEmpty()) return "$possessive public members are $verb on $owner directly"
    val list: String = unrouted.inlineList()
    val one: Boolean = unrouted.size == 1
    val named: String =
      if (one) "is named by its own warning" else "are each named by their own warning"
    if (outcome.bound.isEmpty()) {
      return "none of $possessive public members $verbNone on $owner: $list " + named
    }
    return "$possessive public members are $verb on $owner directly except $list, which no " +
        "route carries and which " + named
  }
  val reason: String = when (kind) {
    // Interface super-interfaces: an INTERFACE owner re-homes the dropped super's members onto
    // `I$name` itself (the ADR-101 mirror), so a C# implementer still owes and gets them.
    SupertypeKind.SUPER_INTERFACE ->
      "super-interface '$supertypeName' is not in the export set, so it has no generated C# " +
          "interface; I$name is generated without it and " +
          membersOn("I$name", "its", "declared", "are declared")

    // ADR-101 amendment (2026-09-27): the old text said the interface "carries no members the C#
    // side could call", which was never true of a defaulted member and is plainly false on a
    // sealed base, which re-homes the interface's abstract members as well.
    SupertypeKind.INTERFACE ->
      "supertype '$supertypeName' is not in the export set, so it has no generated C# " +
          "interface; $name is generated without it and " +
          membersOn(name, "its", "bound", "bind")

    // ADR-101 amendment (2026-09-11): "no base at all" is only true when the whole declared
    // chain is unexported. With `Dinghy : Skiff : Vessel` the walk keeps `Vessel`, so the middle
    // clause names it: the author's `is`/`as` against the kept base still works, and only the
    // dropped hop's members re-home.
    SupertypeKind.BASE_CLASS -> {
      // The single-drop clause is unchanged to the byte ("the base's", not the dropped base's
      // name): it is quoted in ADR-101 and `forward-overview.md`, and only the chain case is new.
      val placement: String = if (keptBase == null) {
        "$name is generated with no base at all and"
      } else {
        "$name is generated extending ${keptBase.simpleName.asString()}, the nearest exported " +
            "base, and"
      }
      val possessive: String = if (keptBase == null) "the base's" else "$simpleName's"
      "base class '$supertypeName' is not in the export set, so it has no generated C# class; " +
          "$placement ${membersOn(name, possessive, "bound", "bind")}"
    }
  }
  // "nothing callable is lost" is only true when every re-homed member binds.
  val nothingLost: Boolean = outcome.unrouted.isEmpty()
  val hint: String = when (kind) {
    SupertypeKind.SUPER_INTERFACE ->
      if (nothingLost) {
        "nothing callable is lost ($simpleName's members are declared on I$name), but C# sees " +
            "no $simpleName type, so `is`/`as` against it is gone; "
      } else {
        "only the members named separately are lost, but C# also sees no $simpleName type, " +
            "so `is`/`as` against it is gone; "
      } + "note that include(\"...\") does not help here — the export reachability closure " +
          "never walks supertypes"

    SupertypeKind.INTERFACE ->
      if (nothingLost) {
        "nothing callable is lost ($simpleName's implemented members export as members of " +
            "$name), but C# sees no $simpleName type, so `is`/`as` against it is gone; "
      } else {
        "only the members named separately are lost, but C# also sees no $simpleName type, " +
            "so `is`/`as` against it is gone; "
      } + "note that include(\"...\") does not help here — the export reachability closure " +
          "never walks supertypes"

    // ADR-101's 2026-09-11 amendment: which of the two clauses is true here is decided by
    // `containingFile`, the same cross-module signal the reachability closure keys on
    // (`ForwardReachabilityClosure.kt`), so the author is told the one fix that works for
    // *their* base instead of both halves of a hedge.
    SupertypeKind.BASE_CLASS -> {
      val lost: String = if (nothingLost) {
        "nothing callable is lost ($simpleName's public members export as members of " +
            "$name), but C# sees no $simpleName type and no inheritance relation, so `is`/`as` " +
            "against it and any other subclass's shared base are gone; "
      } else {
        "only the members named separately are lost, but C# also sees no $simpleName type and " +
            "no inheritance relation, so `is`/`as` against it and any other subclass's shared " +
            "base are gone; "
      }
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
        // Issue #249 v1: no member is missing -- the class exports, with its base list shortened
        // (an interface's defaulted members still bind on it; a base class's are re-homed onto
        // it). What C# loses is a TYPE relation, which the memo lists as its own later cell.
        owner = null,
      ),
    ),
    logger,
  )
  return false
}

/**
 * Interface super-interfaces: a kept super-interface whose type argument has no public C#
 * spelling (`: Holder<List<Map<String?, Int>>>`, a star projection) is dropped from the base list
 * rather than rendered unclosed (CS0305). Its members still bind on [owner] itself.
 */
private fun emitUnspellableSuperInterface(
  owner: KSClassDeclaration,
  name: String,
  type: KSType,
  logger: KSPLogger,
) {
  val simpleName: String = type.declaration.simpleName.asString()
  ForwardDiagnosticSink.emit(
    listOf(
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE,
        symbol = owner,
        declaration = "$name : $type",
        reason = "super-interface '$type' has a type argument with no public C# spelling, so " +
            "the generated C# base list cannot close I$simpleName over it; $name is generated " +
            "without it and its members still bind on $name directly",
        hint = "C# sees no relation between $name and I$simpleName, so `is`/`as` against it is " +
            "gone; close the super-interface over a bridgeable type argument to keep it",
        owner = null,
      ),
    ),
    logger,
  )
}

/** Does the author's own Kotlin declaration offer a constructor C# could have called? */
private fun KSClassDeclaration.hasPublicConstructor(): Boolean =
  getConstructors().any { it.getVisibility() == Visibility.PUBLIC }

/**
 * ROADMAP Phase 3: every public constructor of [cls] was skipped, so the generated C# type has
 * only its `internal $name(IntPtr handle, out NugetHandleTag tag)`. The type is kept on purpose
 * (see [ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR]); this says so, and names each
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
        // Issue #249: deliberately ownerless. This kind ALREADY has a consumer-facing twin --
        // `noPublicConstructorRemark`, the `<remarks>` ADR-064's 2026-09-10 amendment shipped --
        // which this translator attaches directly. Giving it an owner too would render the same
        // hole twice, in two wordings, on one class.
        owner = null,
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
 * The named skip for an abstract member the walk drops because one of its positions has no public
 * C# spelling, whatever the type: a nested or out-of-scope enum, class or interface, an object
 * position, a `Throwable`, a bound interface, a sealed base with no eligible handle, a stale `T`.
 *
 * The wording comes off the same [ForwardPlanSkipReason] the planner route would have used, so a
 * type refused here reads identically to the same type refused on a concrete member: a nested enum
 * keeps [ForwardPlanSkipReason.UNDECLARED_ENUM] and its move-to-top-level hint, a dependency type
 * outside the export scope keeps its `include(...)` one.
 *
 * The kind follows `toDiagnosticKind(position)` for every reason that accepts one, which keeps the
 * shipped `UNDECLARED_ENUM` wording (`SKIPPED_UNSUPPORTED_TYPE`) and ADR-109's
 * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` remedy text exactly where they were. It is hardcoded
 * positionally only for the legacy-route reasons `toDiagnosticKind()` `error()`s on
 * (`droppedFromCSharp = false`), which a bare type at an abstract position can genuinely hold and
 * which no other route re-emits here. That is the fork `emitInheritedAbstractPropertySkip` makes.
 *
 * Emitted rather than dropped silently: a member vanishing from an abstract base with no warning is
 * the CS0115 trap the abstract-method predicate fix closed.
 */
private fun emitAbstractMethodSkip(
  method: KSFunctionDeclaration,
  declaration: String,
  type: BridgeType,
  position: ForwardSkipPosition,
  context: NugetContext,
  logger: KSPLogger,
  // Issue #249: the class being translated, which for a re-homed abstract member is NOT the
  // declaring supertype the node points at.
  owner: ForwardDiagnosticOwner?,
) {
  val reason: ForwardPlanSkipReason = type.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED
  val detail: String? = type.skipDetail()
  val kind: ForwardDiagnosticKind = if (reason.droppedFromCSharp) {
    reason.toDiagnosticKind(position)
  } else if (position == ForwardSkipPosition.INPUT) {
    ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT
  } else {
    ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN
  }.escalatedForStrictDependencyTypes(reason, context.strictDependencyTypes)
  val diagnostic: ForwardDiagnostic = if (reason.ownsSentence(detail)) {
    ForwardDiagnostic(
      kind = kind,
      symbol = method,
      declaration = declaration,
      reason = reason.diagnosticReason(detail),
      hint = reason.diagnosticHint(detail, excludeEntries = context.excludePackages),
      owner = owner,
      member = method.simpleName.asString(),
    )
  } else {
    val described: String = type.diagnosticTypeName()
    ForwardDiagnostic(
      kind = kind,
      symbol = method,
      declaration = declaration,
      reason = "its type $described has no public C# spelling, so the abstract declaration " +
          "cannot be rendered",
      hint = "declare the member with a bridgeable type instead of $described",
      owner = owner,
      member = method.simpleName.asString(),
    )
  }
  ForwardDiagnosticSink.emit(listOf(diagnostic), logger)
}

/**
 * ADR-075 amendment (2026-09-13): the named skip for an inherited-but-unimplemented property whose
 * own planner refused it, when the declaring supertype is UNEXPORTED. Always null: the point is the
 * diagnostic, the member is still dropped.
 *
 * Restricted to an owner outside the export set, the exact set of owners `NugetProcessor` plans
 * onto the declaration catalog, so a miss there is a genuine planner refusal and nothing else. An
 * EXPORTED owner deliberately stays silent, as before: `IFoo` (or the base class) did not declare
 * the member either, and its own planner already warned about it once.
 *
 * ADR-075 amendment (2026-09-19): an unexported abstract BASE CLASS owner is no longer refused
 * outright. It re-homes unplanned abstract members onto this walk exactly as an unexported
 * interface does, and `NugetProcessor` now plans it onto the same declaration catalog, so a miss
 * here means the planner refused the type rather than that nothing was ever planned. Without it a
 * supported `String` vanished with no diagnostic and the concrete Kotlin subclass's `override`
 * was CS0115.
 *
 * The kind is hardcoded rather than taken from `reason.toDiagnosticKind()`: the property kind is
 * positional (it names *where* the drop happened, see `warnDroppedForwardProperties`), and
 * `toDiagnosticKind()` `error()`s on the legacy-route reasons a property can genuinely hold.
 */
private fun emitInheritedAbstractPropertySkip(
  prop: KSPropertyDeclaration,
  propName: String,
  name: String,
  qualified: String,
  exportedTypes: Set<String>,
  classifier: ForwardBridgeTypeClassifier,
  context: NugetContext,
  logger: KSPLogger,
  // Issue #249: the class being translated, not `prop.parentDeclaration` (the supertype that
  // declared it): the C# hole is on the class that re-homes the member.
  owner: ForwardDiagnosticOwner?,
): CirProperty? {
  if (qualified in exportedTypes) return null
  // The same classification the property planner refused the member on (`sealedAsHandle()` is the
  // call `propertyPlan` makes), so the wording is the planner route's, not a second opinion.
  val type: BridgeType = classifier.classify(prop.type.resolve().expandAliases()).sealedAsHandle()
  val reason: ForwardPlanSkipReason? = type.skipReason()
  val detail: String? = type.skipDetail()
  val diagnostic: ForwardDiagnostic = if (reason?.ownsSentence(detail) == true) {
    ForwardDiagnostic(
      // ADR-154 §6: the re-homed abstract PROPERTY takes the same reason-keyed escalation the
      // planner routes do, so strict mode's "every dependency type is admitted or excluded by
      // name" has no hole on this route either.
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY
        .escalatedForStrictDependencyTypes(reason, context.strictDependencyTypes),
      symbol = prop,
      declaration = "$name.$propName",
      reason = reason.diagnosticReason(detail),
      hint = reason.diagnosticHint(detail, excludeEntries = context.excludePackages),
      owner = owner,
      member = propName,
    )
  } else {
    val described: String = type.diagnosticTypeName()
    ForwardDiagnostic(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
      symbol = prop,
      declaration = "$name.$propName",
      reason = "its type $described has no property getter or setter shape",
      hint = "expose a bridgeable property (or a getter function) whose type is not $described, " +
          "and export that instead",
      owner = owner,
      member = propName,
    )
  }
  ForwardDiagnosticSink.emit(listOf(diagnostic), logger)
  return null
}

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
 * ADR-075 amendment (2026-09-13): an UNEXPORTED interface is no longer out of scope. ADR-101 still
 * drops `: IFoo` from the base list, but the catalog is now planned over the unexported interface
 * supertypes of exported classes too (`NugetProcessor`), so the member is spelled from a plan
 * here exactly as an exported interface's is. Without it the member vanished while the concrete
 * Kotlin subclass still rendered `public override`: CS0115 in the generated file itself.
 *
 * ADR-075 amendment (2026-09-19): an unexported abstract BASE CLASS owner takes the same route.
 * ADR-101 drops `: Cushion()` and re-homes its members onto the exported subclass, so an
 * unimplemented `abstract val` there is a fresh abstract slot on that subclass, spelled from the
 * same plan. `isAbstract = true` with `setter` following the plan is right for a base-class owner
 * too: the dropped base has no C# class to carry the member.
 *
 * Null when the declaring supertype's own planner skipped the member (so neither `IFoo` nor the
 * dropped base declares it either) or the parent is not a class declaration. A miss on an
 * unexported owner is named at [name] rather than dropped silently, since there is no other
 * declaration carrying the author's member anywhere.
 */
private fun inheritedAbstractProperty(
  prop: KSPropertyDeclaration,
  propName: String,
  name: String,
  isOverride: Boolean,
  interfaceDeclarationCatalog: ForwardCallablePlanCatalog,
  exportedTypes: Set<String>,
  classifier: ForwardBridgeTypeClassifier,
  context: NugetContext,
  logger: KSPLogger,
  // Issue #249: the class whose generated type re-homes this inherited member.
  diagnosticOwner: ForwardDiagnosticOwner?,
): CirProperty? {
  val owner: KSClassDeclaration = prop.parentDeclaration as? KSClassDeclaration ?: return null
  val qualified: String = owner.qualifiedName?.asString() ?: return null
  val plan: ForwardPropertyPlan = interfaceDeclarationCatalog.propertyFor("$qualified.$propName")
    ?: return emitInheritedAbstractPropertySkip(
      prop, propName, name, qualified, exportedTypes, classifier, context, logger, diagnosticOwner,
    )
  return CirProperty(
    name = plan.publicName,
    type = ForwardCirPropertyProjection.publicType(plan),
    nativeReturnType = "",
    nativeName = propName.asCSymbol(),
    getter = "",
    // `{ get; set; }` when the interface declares a `var`: an implementing subclass keeps its own
    // setter (ADR-075's `readOnlyOverrideeOwner` finds the interface member mutable), and an
    // `override` of a get-only abstract property that adds a setter is CS0546.
    setter = if (plan.setter != null) "" else null,
    isAbstract = true,
    isOverride = isOverride,
    hasNativeImport = false,
  )
}

/**
 * The C# base list for [cls]: the nearest exported base class, spelled by [forwardBaseSpelling],
 * paired with the exported interfaces [cls] declares directly, spelled by
 * [forwardSuperInterfaceSpelling]. Shared by [translateClass] and [translateSealedClass], so an
 * ordinary class and a sealed base derive their C# base list the same way.
 *
 * A dropped base or interface -- outside the export set, or with no generated C# spelling -- is
 * named once by [keepsSupertype] rather than rendered, so a build never fails on a dangling
 * `: Base` or `: IFace`.
 */
private fun forwardBaseList(
  cls: KSClassDeclaration,
  name: String,
  superClassDeclaration: KSClassDeclaration?,
  exportedTypes: Set<String>,
  classifier: ForwardBridgeTypeClassifier,
  logger: KSPLogger,
): Pair<String?, List<String>> {
  // ADR-101 amendment (2026-09-11): one diagnostic per *dropped* hop, not one per class. The
  // chain prefix before the kept base is what has no generated C# class, and each of those hops
  // re-homes its own members, so each is named. A class whose direct base is exported drops
  // nothing and says nothing, exactly as before.
  // Neither planner nor the Kotlin emitter holds a logger, so the translator (the ordinary-class
  // and, since the 2026-09-27 amendment, the sealed-base route) is the only site it can fire from.
  cls.droppedBaseChain(superClassDeclaration).forEach { dropped ->
    keepsSupertype(
      cls,
      name,
      dropped,
      SupertypeKind.BASE_CLASS,
      exportedTypes,
      logger,
      keptBase = superClassDeclaration,
    )
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
  val interfaces: List<String> = forwardInterfaceList(
    cls,
    name,
    carried = superClassDeclaration?.forwardSupertypeNames().orEmpty(),
    exportedTypes,
    classifier,
    logger,
  )
  return superClass to interfaces
}

/**
 * The interface half of [forwardBaseList]: the exported interfaces [cls] declares directly, minus
 * every one its C# base already carries ([carried], qualified names), spelled for C#. Shared with
 * a sealed arm (ADR-101 amendment 2026-09-27), whose base is the sealed type itself, so an arm
 * lists its own interfaces by exactly the ordinary class's rule.
 */
private fun forwardInterfaceList(
  cls: KSClassDeclaration,
  name: String,
  carried: Set<String>,
  exportedTypes: Set<String>,
  classifier: ForwardBridgeTypeClassifier,
  logger: KSPLogger,
): List<String> {
  val baseSupertypes: Set<String> = carried
  return cls.superTypes
    .map { it.resolve() }
    .filter { type ->
      (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE
    }
    // An interface the base already implements is carried by the base. Listing it again compiles
    // but says nothing, and re-binding its members here would hide the base's (CS0108), so it is
    // dropped before the export-set filter: it owes no diagnostic either, nothing is lost.
    .filter { type -> type.declaration.qualifiedName?.asString() !in baseSupertypes }
    // ADR-101 / issue #42: a supertype outside the export set has no generated C# interface, so
    // naming it in the base list is a guaranteed CS0246 (the reporter's `: IKoinComponent`).
    // Drop it and say so. Its public members still bind on the class itself
    // (`ForwardClassMembership.kt`), so only the `is`/`as` relation is lost.
    .filter { type ->
      val iface = type.declaration as KSClassDeclaration
      keepsSupertype(cls, name, iface, SupertypeKind.INTERFACE, exportedTypes, logger)
    }
    // ADR-133: the enclosing scope with the `I` on the last segment (`Aviary.IKeeper`); a bare
    // `IKeeper` names nothing at namespace level (CS0234). Interface super-interfaces: with its
    // type arguments, since `: IHolder` for `Holder<Int>` is CS0305.
    .mapNotNull { type ->
      forwardSuperInterfaceSpelling(type, classifier, from = cls)
        ?: run {
          emitUnspellableSuperInterface(cls, name, type, logger)
          null
        }
    }
    .toList()
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

/**
 * ROADMAP line 28, case D: the C# names of the interfaces [plan]'s setter is implemented through
 * explicitly (`int ITally.Count { ... }`). Spelled by [forwardSuperInterfaceSpelling], the base
 * list's own function, off the class's own supertype (type arguments applied), so the explicit
 * member names the interface exactly as the base list does, `global::` qualification included.
 * An interface with no spelling is left out: the base list drops it too, so there is nothing to
 * implement.
 */
internal fun KSClassDeclaration.explicitSetterInterfaceSpellings(
  plan: ForwardPropertyPlan,
  classifier: ForwardBridgeTypeClassifier,
): List<String> {
  if (plan.explicitSetterInterfaces.isEmpty()) return emptyList()
  val supertypes: List<KSType> = getAllSuperTypes().toList()
  return plan.explicitSetterInterfaces.mapNotNull { qualified ->
    val type: KSType = supertypes
      .firstOrNull { it.declaration.qualifiedName?.asString() == qualified }
      ?: return@mapNotNull null
    forwardSuperInterfaceSpelling(type, classifier, from = this)
  }
}

/**
 * Interface super-interfaces: the C# spelling of a kept super-interface reference, type arguments
 * included (`IHolder<int>`), shared by the class route's interface list and `translateInterface`'s
 * base list. A bare `IHolder` for a generic interface is CS0305. Null when an argument has no
 * public C# spelling (a star projection, an unbridgeable type): the caller drops the entry with a
 * named skip rather than render something that cannot compile.
 *
 * ROADMAP line 28 (measured 2026-09-26): an interface in ANOTHER namespace than [from], the type
 * whose base list this is, is `global::Ns.IFoo`. The bare name resolved only in the same namespace:
 * `class TrainingClicker : Scoreboard, ITally` in `Interop.Impl` with `ITally` in `Interop.Api`
 * was CS0246, on both the class and the interface base list.
 *
 * ADR-133 amendment (2026-09-28): when [from] is itself nested (at any depth), a same-namespace
 * target is qualified too. A bare name inside a type body is looked up in the enclosing types'
 * members first, so a same-named nested type shadows it: in `interface Marker { interface Marker;
 * class Impl : Marker }`, `public class Impl : IMarker` bound to `IMarker.IMarker` and built
 * (silently wrong; the consumer's `Take(MakeImpl())` was CS1503), and the `Cage` variant with a
 * member on the nested interface was CS0535/CS0426. A top-level [from]'s same-namespace spelling
 * stays bare: namespace-level lookup cannot be shadowed by a member type.
 */
internal fun forwardSuperInterfaceSpelling(
  type: KSType,
  classifier: ForwardBridgeTypeClassifier,
  from: KSDeclaration,
): String? {
  val declaration: KSClassDeclaration = type.declaration as? KSClassDeclaration ?: return null
  val targetNamespace: String? = classifier.csharpNamespaceOf(declaration)
  val qualifier: String =
    if (
      targetNamespace != null &&
      (targetNamespace != classifier.csharpNamespaceOf(from) || from.parentDeclaration != null)
    ) {
      "global::$targetNamespace."
    } else {
      ""
    }
  val name: String = qualifier + declaration.nestedInterfaceCsName()
  if (declaration.typeParameters.isEmpty()) return name
  if (type.arguments.size != declaration.typeParameters.size) return null
  val spelled: List<String> = type.arguments.map { argument ->
    val argumentType: KSType = argument.type?.resolve() ?: return null
    val argumentDeclaration = argumentType.declaration
    if (argumentDeclaration is com.google.devtools.ksp.symbol.KSTypeParameter) {
      argumentDeclaration.simpleName.asString()
    } else {
      try {
        classifier.classify(argumentType).forwardPublicCsharpType()
      } catch (_: IllegalStateException) {
        return null
      }
    }
  }
  return "$name<${spelled.joinToString(", ")}>"
}

/**
 * ADR-147: a class's own type parameters, projected for the C# carrier. Lifted verbatim out of the
 * retired `translateGenericClass`, including the ADR-133 nested-bound qualification and the
 * dropped-variance INFO: a generic class is an ordinary class with this list filled now, so the
 * projection has to run on the ordinary path.
 */
internal fun KSClassDeclaration.cirTypeParameters(
  logger: KSPLogger,
  context: NugetContext,
): List<CirTypeParameter> =
  // ADR-196: an inner class of a generic owner is flattened onto the owner's holder, so the
  // parameters it captures are declared first (`Tin.Tag<T, U>`), spelled and constrained as their
  // own class spells them. Their variance note is the owner's own and is not repeated here.
  capturedTypeParameterOwners().flatMap { owner ->
    owner.typeParameters.map { param ->
      owner.cirTypeParameter(param, logger, context, reportVariance = false)
    }
  } + typeParameters.map { param ->
    cirTypeParameter(param, logger, context, reportVariance = true)
  }

private fun KSClassDeclaration.cirTypeParameter(
  param: KSTypeParameter,
  logger: KSPLogger,
  context: NugetContext,
  reportVariance: Boolean,
): CirTypeParameter {
  val constraints: List<String> = param.cirConstraints(
    context, logger, this, "${simpleName.asString()}<${param.name.asString()}>",
  ) { name ->
    // A bound's argument naming one of this class's parameters (`IRival<T>`) takes its C#
    // spelling, which may have been renamed around a member (`A` -> `TA`).
    typeParameters.firstOrNull { it.name.asString() == name }
      ?.let { named -> forwardCsharpTypeParameterName(named) } ?: name
  }

  if (reportVariance && param.variance != Variance.INVARIANT) {
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.INFO_DROPPED_VARIANCE,
          symbol = this,
          declaration = "${simpleName.asString()}<${param.name.asString()}>",
          reason = "variance '${param.variance}' on this generic class type parameter is " +
              "dropped; C# does not support variance on classes",
          hint = "the member still binds; declare the parameter invariant if the dropped " +
              "variance was load-bearing",
          // Nothing generated loses a member here (a note, or an ERROR_* that fails the
          // build before anything is read), so there is no owner to name it on.
          owner = null,
        ),
      ),
      logger,
    )
  }

  return CirTypeParameter(forwardCsharpTypeParameterName(param), constraints)
}

/**
 * ADR-015: this type parameter's C# `where` constraints, one per exportable bound, reported under
 * [declaration] on [symbol] when a builtin bound is dropped (ADR-015's 2026-10-03 amendment).
 */
internal fun KSTypeParameter.cirConstraints(
  context: NugetContext,
  logger: KSPLogger,
  symbol: KSNode,
  declaration: String,
  // The C# spelling of a type parameter a bound's arguments name (`IRival<T>`); see
  // [cirBoundConstraint].
  typeParameterName: (String) -> String = { name -> name },
): List<String> {
  val bounds: List<String> = bounds.toList().mapNotNull { bound ->
    cirBoundConstraint(bound.resolve(), context, logger, symbol, declaration, typeParameterName)
  }
  // `notnull` must come first in a C# constraint list and adds nothing next to a class bound, so
  // it survives only as the sole constraint. ADR-198: `struct` must come first too (CS0449).
  return (if (bounds.size > 1) bounds - NOTNULL_CONSTRAINT else bounds)
    .sortedByDescending { constraint -> constraint == ENUM_CONSTRAINT }
}

/**
 * ADR-197: fills each planned member's own type parameters with their C# constraints. The planner
 * holds no export context and runs twice (so a dropped-bound INFO reported there would be
 * reported twice); a planned entry keeps its declaration, so the constraints are spelled here,
 * once, before either half projects the plan.
 */
internal fun ForwardCallablePlanCatalog.withMethodTypeParameterConstraints(
  context: NugetContext,
  logger: KSPLogger,
): ForwardCallablePlanCatalog = copy(
  entries = entries.map { entry ->
    if (entry !is ForwardCallableCatalogEntry.Planned) return@map entry
    val declared: List<ForwardMethodTypeParameter> = entry.plan.publicSignature.typeParameters
    val function: KSFunctionDeclaration = entry.node as? KSFunctionDeclaration
      ?: return@map entry
    if (declared.isEmpty()) return@map entry
    val owner: String = function.parentDeclaration?.simpleName?.asString()?.let { "$it." }.orEmpty()
    val spelled: List<ForwardMethodTypeParameter> =
      declared.zip(function.typeParameters) { parameter, typeParameter ->
        parameter.copy(
          constraints = typeParameter.cirConstraints(
            context, logger, function,
            "$owner${function.simpleName.asString()}<${typeParameter.name.asString()}>",
          ),
        )
      }
    entry.copy(
      plan = entry.plan.copy(
        publicSignature = entry.plan.publicSignature.copy(typeParameters = spelled),
      ),
    )
  },
)

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
  // ADR-150: an `actual` class carries no KDoc of its own; the author wrote it on the `expect`.
  expects: ExpectIndex = ExpectIndex(),
  // ADR-110 amendment (ROADMAP line 32): where this class records the names it rendered, so the
  // inherited CS0108 post-pass can compare it against its kept base once every class translated.
  memberRegistry: CsMemberRegistry? = null,
): CirClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = cls.nativePrefix(context.symbols)
  val isDataClass: Boolean = cls.modifiers.contains(Modifier.DATA)
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)
  val isOpen: Boolean = !isAbstract && cls.modifiers.contains(Modifier.OPEN)

  // The shared has-superclass predicate (`ForwardClassMembership.kt`), the same instance the two
  // planners filter their members with, so a member can never be kept here and skipped there.
  // ADR-101 amendment / issue #42: gated on the export set, so a base class nothing generates is
  // dropped here exactly as an unexported interface is, instead of rendering a dangling `: Base`.
  val superClassDeclaration: KSClassDeclaration? = cls.forwardSuperClass(exportedTypes)
  val (superClass: String?, interfaces: List<String>) =
    forwardBaseList(cls, name, superClassDeclaration, exportedTypes, classifier, logger)

  // ADR-091: constructors come off the catalog, the same move ADR-090 made for methods. The extern
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
          hint = "rename or remove the duplicate constructor, or change one parameter's type so " +
              "the rendered C# signatures differ",
          // Nothing generated loses a member here (a note, or an ERROR_* that fails the
          // build before anything is read), so there is no owner to name it on.
          owner = null,
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
          // ADR-101 amendment (2026-09-27): an inherited interface default on an open class too.
          isVirtual = !isOverride && prop.isOpenForOverrideOn(cls),
          // ADR-075 amendment (2026-09-10): this class's own unimplemented `abstract val`/`var`.
          // `isAbstract()` (not `Modifier.ABSTRACT`) is the same predicate
          // `isForwardPlannableMemberOf` uses. The abstract *method* walk keys on the same
          // property as of the 2026-09-11 amendment, so both routes now agree.
          isAbstract = prop.isAbstract(),
          explicitSetterInterfaces = cls.explicitSetterInterfaceSpellings(planned, classifier),
        )
      }
      // ADR-075 amendment (2026-09-11): a property this class inherits from an interface
      // (2026-09-13: exported or not; 2026-09-19: or from an unexported abstract BASE CLASS,
      // whose members ADR-101 re-homes here) and does not implement.
      // `isForwardPlannableMemberOf` keeps it out of the planner (nothing to dispatch to), so it
      // takes the declaration walk the abstract *method* mirror takes: an abstract C# property, no
      // body, no export, no `DllImport`. Without it the generated `Bird : IFeathered` is CS0535
      // and a consumer subclass's `override` is CS0115.
      if (prop.parentDeclaration != cls && prop.isAbstract()) {
        // A backed class plans inherited abstract properties; one that did not plan has no
        // override for the wrapper to give it (CS0534), so it stays off C# as on a subclass.
        if (cls.hasAbstractBacking()) return@mapNotNull null
        return@mapNotNull inheritedAbstractProperty(
          prop, propName, name, prop.overridesBaseClassMember(superClassDeclaration),
          interfaceDeclarationCatalog, exportedTypes, classifier, context,
          logger, cls.forwardDiagnosticOwner(),
        )
      }
      // Issue #121: the planner declined, but a decline is not always an invitation. A marked
      // declaration must reach neither artifact, so the legacy arms below never run for one.
      if (prop.isOptInRefused(classifier.exportMarkers)) return@mapNotNull null
      // Named specialized-protocol property adapters only (lambda / suspend-lambda / Flow).
      // Ordinary property types without a plan are skipped — no mapReturnType IntPtr fallthrough.
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val csPropName: String = prop.csharpMemberName()
      val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()

      // ADR-124: the Flow/StateFlow arm is one function now, so `translateSealedClass` projects
      // the identical property for a sealed arm. It owns its own detection and its own ADR-123
      // element refusal, and answers null for every other type, so the lambda arms below are
      // reached exactly as before.
      // ADR-147: the C# half of the generic-owner refusal the Kotlin property loop makes. Every
      // legacy property arm below bakes `asStableRef<Crate>()` into its export, which does not
      // compile for a generic owner, so neither half emits one.
      if (cls.forwardTypeParametersInScope().isNotEmpty()) return@mapNotNull null

      if (propTypeResolved.isForwardFlowType()) {
        return@mapNotNull flowProperty(prop, name, context, classifier, tracker)
      }

      // The Kotlin half and the planner's skip report read the same predicate, so a lambda this
      // declines (a nullable one) is named once and emitted by neither half.
      if (!prop.carriesLegacyLambdaProperty(ForwardLambdaPropertyCarrier.CLASS)) {
        return@mapNotNull null
      }
      // A kept base already declares it, over a getter Kotlin dispatches to this override; a
      // second declaration here only hides it (CS0108). The Kotlin half reads the same rule.
      if (prop.reProjectsKeptBaseLambdaProperty(cls, superClassDeclaration)) {
        return@mapNotNull null
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
      // ADR-171: a value class with a box/unbox pair is nameable too; `Wrap<T>`/`FromHandle<T>`
      // carry it. One without a pair stays unnameable, so the lambda is refused by name here
      // rather than bound and left to throw at the first `Invoke`.
      val nameableTypes: Set<String> = exportedTypes + callableCatalog.boxedValueClasses
      val unnameableTypeArgument: CsTypeArgument.Unnameable? =
        if (isLambdaType || isSuspendLambdaType) {
          csTypeArguments(propTypeResolved.arguments, nameableTypes, context, classifier)
        } else null
      if (unnameableTypeArgument != null) {
        ForwardDiagnosticSink.emit(
          listOf(
            lambdaTypeArgumentDiagnostic(
              kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
              symbol = prop,
              declaration = "$name.$propName",
              typeArgument = unnameableTypeArgument.typeArgument,
              owner = cls.forwardDiagnosticOwner(),
              member = propName,
            ),
          ),
          logger,
        )
        return@mapNotNull null
      }

      val lambdaTypeArgs: List<String> = if (isLambdaType) {
        csTypeArgumentNames(propTypeResolved.arguments, nameableTypes, context, classifier)
      } else emptyList()

      val lambdaCsType: String = if (isLambdaType) csLambdaType(lambdaTypeArgs) else ""

      val suspendLambdaTypeArgs: List<String> = if (isSuspendLambdaType) {
        csTypeArgumentNames(propTypeResolved.arguments, nameableTypes, context, classifier)
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
        isLambdaType -> "new $lambdaCsType(Native_Get_${propName.asCSymbol()}(_handle))"
        isSuspendLambdaType ->
          "new $suspendLambdaCsType(Native_Get_${propName.asCSymbol()}(_handle))"
        else -> error("unreachable specialized property getter")
      }

      CirProperty(
        name = csPropName,
        type = type,
        nativeReturnType = nativeReturnType,
        nativeSetterType = nativeReturnType,
        nativeName = propName.asCSymbol(),
        getter = getter,
        setter = null,
        extraNatives = emptyList(),
        hasSyncErrorOut = false,
      )
    }.toList()

  val allMethods = cls.getAllFunctions().toList()
  // ADR-147: the pair halves the shared legacy-route predicate needs (pairs are structural).
  val legacyPairMembers: Set<KSFunctionDeclaration> = forwardLegacyPairMembers(
    allMethods
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { method -> !method.isCompilerOwnedMember(cls) }
      .filter { method -> !method.modifiers.contains(Modifier.SUSPEND) }
      .filter { method -> method.isForwardMemberOf(cls, superClassDeclaration) },
  )

  val filteredMethods: List<KSFunctionDeclaration> = allMethods
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { method ->
      if (method.isCompilerOwnedMember(cls)) return@filter false

      // ADR-114: a Flow-returning or suspend member with a generic parameter this route cannot
      // marshal is dropped on both halves. `NugetProcessor` names it once. ADR-119: likewise a
      // suspend member with a generic return that is not a marshallable collection.
      if (method.isForwardLegacyAsyncRoute() &&
        classifier.legacyRefusedParameter(method.parameters) != null
      ) {
        return@filter false
      }
      if (classifier.legacyRefusedReturn(method) != null) return@filter false

      // ADR-147: the C# half of the same refusal the Kotlin export builders make for a generic
      // owner. A generic class's suspend / Flow / legacy-callback members are deferred, named; a
      // planned callback member is not legacy and comes off the catalog below (ADR-160).
      if (cls.forwardTypeParametersInScope().isNotEmpty() &&
        method.isForwardLegacyRoute(classifier, legacyPairMembers)
      ) {
        return@filter false
      }

      method.isForwardMemberOf(cls, superClassDeclaration)
    }

  // ADR-159: the suspend half comes off the shared selector rather than off this walk, so the
  // Kotlin export builder and the C# projection cannot disagree about which members exist, and
  // an `override suspend fun` over a kept base's member is not re-projected here (the base's
  // export dispatches to it dynamically; a second `FillAsync` on the subclass is CS0108).
  val allSuspendMethods: List<KSFunctionDeclaration> =
    cls.forwardSuspendRouteMethods(classifier, superClassDeclaration)
  val regularMethods: List<KSFunctionDeclaration> = filteredMethods
    .filterNot { it.modifiers.contains(Modifier.SUSPEND) }

  val (allFlowMethods, nonFlowMethods) = regularMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in FLOW_TYPES || returnQualified in STATE_FLOW_TYPES
  }
  // The Kotlin half's rule (`forwardClassLegacyMembers`): a kept base's Flow member, abstract or
  // open, is declared once on that base over an export that dispatches virtually, so an override
  // here would only hide it (CS0108).
  val flowMethods: List<KSFunctionDeclaration> = allFlowMethods
    .filterNot { method -> method.reProjectsKeptBaseMember(cls, superClassDeclaration) }

  // Boundary nullability part A2: the C# twin of the Kotlin half's refusal, applied at the same
  // point (before the partition) so the two halves cannot disagree about which members exist. A
  // lambda whose payload or return is nullable, whose payload is a Kotlin builtin non-scalar, or
  // whose result is not `Unit`/primitive/`String` has no crossing on this route at all.
  val crossableNonFlowMethods: List<KSFunctionDeclaration> = nonFlowMethods
    .filterNot { method -> method.refusedLegacyLambdaShape() != null }

  val (lambdaParamMethods, normalMethods) = crossableNonFlowMethods.partition { method ->
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
    // ADR-160: the C# half of a planned callback member comes off the plan catalog below
    // (`plannedMethods`), so this route must not declare it again -- the same retirement the
    // Kotlin half applies through the identical predicate.
    .filterNot { method -> method.hasPlannedCallbackParameter(classifier) }
    // ADR-160 step 4: the C# half of the same named refusal the Kotlin half applies, so a member
    // this route cannot carry is absent from both artifacts rather than half-declared.
    .filterNot { method -> legacyRefusedCallbackMember(method) != null }
    // ADR-115 / issue #121: the C# half of the Kotlin half's marker gate (the planner names it).
    .filterNot { method -> method.optInMarker(classifier.exportMarkers) != null }
    .mapNotNull { method ->
      translateCallbackMethod(method, libraryName, prefix, exportedTypes, tracker)
    }

  val storedCallbackMembers: List<CirStoredCallbackMethod> = storedCallbackPairs
    // ADR-037 amendment: the C# half of the Kotlin half's post-detection refusal.
    .filter { (addMethod, _) -> legacyRefusedStoredCallbackPair(addMethod) == null }
    // ADR-115 / issue #121: the C# half of the Kotlin half's pair-wide marker gate.
    .filter { pair -> pair.toList().none { it.optInMarker(classifier.exportMarkers) != null } }
    .mapNotNull { (addMethod, removeMethod) ->
      translateStoredCallbackMethod(
        addMethod,
        removeMethod,
        libraryName,
        prefix,
        name,
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
    // ADR-090 amendment (2026-09-26): the C# half of the same named refusal.
    .filter { (addMethod, _) -> classifier.legacyRefusedInterfaceBridgePair(addMethod) == null }
    // ADR-115 / issue #121: the C# half of the Kotlin half's pair-wide marker gate.
    .filter { pair -> pair.toList().none { it.optInMarker(classifier.exportMarkers) != null } }
    .mapNotNull { (addMethod, removeMethod) ->
      translateInterfaceBridgeMethod(
        addMethod, removeMethod, libraryName, prefix, name, tracker, classifier, context,
      )
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
    ).let { method -> if (plan.publicSignature.isAbstract) method.asAbstract() else method }
  }
  val plannedMemberNames: Set<String> = plannedMethodPlans
    .mapNotNull { plan -> plan.invocation.member }
    .toSet()
  val backingName: String? = cls.abstractBackingName()

  // Abstract declarations still need a C# abstract method for the public surface even though they
  // have no native export / plan (planner skips ABSTRACT), so they stay on the declaration walk.
  // A class with a backing wrapper plans every abstract member instead, and one its plan refused
  // stays off C# (a subclass's plan refuses the same signature), since the wrapper could not
  // override a declaration-only member (CS0534).
  // The planner's verdict on this class's own abstract members (`classEntries`): it still tries
  // the plan, and a refusal there is a refusal of every subclass's override, so the walk must not
  // declare what nothing can implement (CS0534).
  val ownerSymbol: String = cls.qualifiedName?.asString() ?: name
  val refusedAbstract: Set<KSNode> = callableCatalog.entries
    .filterIsInstance<ForwardCallableCatalogEntry.Skipped>()
    .filter { entry -> entry.reason != ForwardPlanSkipReason.ABSTRACT }
    .filter { entry -> entry.symbol.substringBeforeLast('.') == ownerSymbol }
    .mapNotNull { entry -> entry.node }
    .toSet()
  // A per-call lambda member the plan owns (ADR-160) is declared here too: neither callback route
  // declares an abstract one, yet every subclass's planned override says `override` (CS0115).
  val plannedCallbackAbstracts: List<KSFunctionDeclaration> = lambdaParamMethods
    .filter { method -> method.isAbstract && method.hasPlannedCallbackParameter(classifier) }
  val abstractMethods: List<CirMethod> = (normalMethods + plannedCallbackAbstracts)
    .filter { it !in interfaceBridgeExcluded }
    .filter { backingName == null }
    .filter { method -> method !in refusedAbstract }
    .mapNotNull { method ->
      val methodName: String = method.simpleName.asString()
      if (methodName in plannedMemberNames) return@mapNotNull null
      // ADR-075 / ADR-101 amendment (2026-09-11): `abstract` is a property of the body, not of the
      // declaring class. KSP's `isAbstract` (not `Modifier.ABSTRACT`) is the same predicate
      // `isForwardPlannableMemberOf` uses, and covers an interface member declared without the
      // modifier. Asking `parentDeclaration == cls || OVERRIDE` instead got both halves wrong: a
      // class's own `abstract fun` looked implemented and was dropped from C# entirely (CS0115 on
      // a subclass `override`), while an inherited member *with* a body that the planner declined
      // looked unimplemented and rendered `public abstract` (CS0534 on any further C# subclass).
      if (!method.isAbstract) return@mapNotNull null
      val methodReturnTypeResolved = method.returnType?.resolve()?.expandAliases()

      // Every position goes through the classifier now, not just the 2026-09-05 enum gate. This
      // walk is the only route an inherited unimplemented member has --
      // `isForwardPlannableMemberOf` keeps it out of the planner, so nothing else classifies it,
      // skips it or diagnoses it -- and it hand-spelled every other class-typed position by its
      // bare simple name. That dangles
      // (CS0246) for a type declared in another namespace, for a nested type that exists only as
      // `Owner.Part`, and for a type nothing declares at all, and it drifts from the
      // `global::`-qualified spelling the planner gives the concrete subclass's own override.
      //
      // `sealedAsHandle()` is the call the planner makes, so an eligible sealed base spells its
      // handle here too: a bodiless declaration marshals nothing, only the spelling matters.
      val declaredReturn: BridgeType = methodReturnTypeResolved
        ?.let { classifier.classify(it).sealedAsHandle() }
        ?: BridgeType.Unit
      // ADR-108: an abstract `Result<T>` member is declared at `T`, as the planner lowers the
      // override that implements it; spelled as `Result` it named a C# type nothing declares
      // (CS0246) and the override's `T` return did not match it.
      val resultPayload: BridgeType? = (declaredReturn as? BridgeType.ValueClass)
        ?.takeIf { type -> type.qualifiedName == "kotlin.Result" }
        ?.typeArguments
        ?.singleOrNull()
      val returnBridge: BridgeType = resultPayload ?: declaredReturn
      val parameterBridges: List<BridgeType> = method.parameters
        .map { param -> classifier.classify(param.type.resolve().expandAliases()).sealedAsHandle() }
      // ADR-147: a `T` on this class's own generic carrier is a real C# name; one re-homed from a
      // generic base onto a non-generic subclass is not.
      // ADR-197: so is the member's own `T`, in a shape its overrides route (they plan as
      // `override`, which needs this declaration); any other shape keeps the skip below.
      val ownTypeParameters: List<CirTypeParameter> =
        if (method.typeParameters.isEmpty() || method.forwardMemberGenericRefusal() != null) {
          emptyList()
        } else {
          val owner: String = method.parentDeclaration?.simpleName?.asString() ?: name
          val isOverride: Boolean = method.overridesBaseClassMember(superClassDeclaration)
          method.typeParameters.map { parameter ->
            val csharpName: String = method.forwardCsharpMethodTypeParameterName(parameter)
            // The two rules `ForwardCirPlanProjection` applies to a planned override.
            val spelledNullable: Boolean = (parameterBridges + returnBridge).any { bridge ->
              val inner: BridgeType? = (bridge as? BridgeType.Nullable)?.type
              inner is BridgeType.TypeParameter && inner.name == csharpName &&
                  !inner.nullableFromBound
            }
            val constraints: List<String> = when {
              !isOverride -> parameter.cirConstraints(
                context, logger, method,
                "$owner.$methodName<${parameter.name.asString()}>",
              )
              spelledNullable -> listOf("default")
              else -> emptyList()
            }
            CirTypeParameter(csharpName, constraints)
          }
        }
      val typeParametersInScope: Set<String> =
        cls.forwardTypeParametersInScope()
          .map { (owner, param) -> owner.forwardCsharpTypeParameterName(param) }
          .toSet() + ownTypeParameters.map { parameter -> parameter.name }

      if (!returnBridge.isPubliclySpellable(typeParametersInScope)) {
        emitAbstractMethodSkip(
          method, "$name.$methodName", returnBridge, ForwardSkipPosition.RETURN, context, logger,
          cls.forwardDiagnosticOwner(),
        )
        return@mapNotNull null
      }
      val unspellableParameter: BridgeType? = parameterBridges
        .firstOrNull { bridge -> !bridge.isPubliclySpellable(typeParametersInScope) }
      if (unspellableParameter != null) {
        emitAbstractMethodSkip(
          method, "$name.$methodName", unspellableParameter, ForwardSkipPosition.INPUT, context,
          logger, cls.forwardDiagnosticOwner(),
        )
        return@mapNotNull null
      }

      val returnType: String = returnBridge.forwardPublicCsharpType()
      val methodParams: List<CirParameter> = method.parameters.mapIndexed { index, param ->
        val paramType: String = parameterBridges[index].forwardPublicCsharpType()
        CirParameter((param.name?.asString() ?: "_").csharpParameterName(), paramType)
      }
      val abstractMethod = CirMethod(
        name = method.csharpMemberName(),
        returnType = returnType,
        parameters = methodParams,
        body = "",
        isAbstract = true,
        isOverride = method.overridesBaseClassMember(superClassDeclaration),
        typeParameters = ownTypeParameters,
        isSyncErrorCheckEnabled = false,
      )
      // The override's own Try twin is `override`, so the abstract base declares one too.
      if (resultPayload == null) return@mapNotNull abstractMethod
      abstractMethod.copy(
        tryOverload = ForwardCirPlanProjection.abstractResultTry(abstractMethod, resultPayload),
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
    expects = expects,
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
      .mapNotNull { translateConstProperty(it, logger) }
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
  // ADR-159: the async and Flow-route methods are in the checked set now. The call used to run
  // before they were projected, so two `suspend` overloads that render one C# signature reached
  // the generated file and failed it with CS0111 instead of being named here (ADR-118 gives them
  // distinct C symbols, so the collision is purely the public C# signature).
  emitCsharpSignatureCollisions(
    methods = plannedMethods +
        (companionMembers + asyncMembers + flowRouteMembers).filterIsInstance<CirMethod>(),
    container = name,
    symbol = cls,
    logger = logger,
    // ADR-162: every generated handle class renders `public void Dispose()` (CirClassRenderer), so
    // an authored zero-argument `dispose()` is CS0111 against it.
    reservedSignatures = HANDLE_RESERVED_SIGNATURES,
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

  // ADR-110 amendment (ROADMAP line 32): one C# type holds the instance, companion, async, Flow
  // and callback members alike, so a property/const sharing a name with any of them is CS0102.
  val renderedNames: List<CsMemberName> = (
      properties + methods + listOfNotNull(copyMethod) + callbackMembers + storedCallbackMembers +
          interfaceBridgeMembers + companionMembers + asyncMembers + flowRouteMembers
      ).csMemberNames()
  val spellings: KotlinSpellings = KotlinSpellings.ofClass(cls)
  val classPhrase: String =
    if (cls.forwardTypeParametersInScope().isEmpty()) "class $name" else "generic class $name"
  emitMemberNameCollisions(name, classPhrase, cls, renderedNames, spellings, logger)
  cls.qualifiedName?.asString()?.let { qualified ->
    memberRegistry?.register(
      CsMemberRegistry.Entry(
        qualifiedName = qualified,
        csName = name,
        ownerPhrase = classPhrase,
        symbol = cls,
        keptBase = superClassDeclaration?.qualifiedName?.asString(),
        members = renderedNames,
        spellings = spellings,
        nestedTypes = cls.csNestedTypeNames(),
      ),
    )
  }

  // ADR-159: one scope per instance, owned by the root-most class in the kept chain that
  // projects a scope-using member. `null` means nothing in the chain does.
  val scopeOwner: KSClassDeclaration? = cls.forwardScopeOwner(classifier, exportedTypes)

  // ADR-174 ruling 4: a generic implementer's explicit implementations of the interface async
  // members ADR-147 keeps off its own surface. Empty for every non-generic class.
  val interfaceForwards: CirInterfaceAsyncMembers = interfaceAsyncForwards(
    cls, libraryName, classifier, tracker, callableCatalog, context, expects,
  )

  // The wrapper overrides an abstract `DisposeAsync`: this class's own when it owns the scope,
  // or an abstract (or sealed) owner's above it that nothing in between implemented.
  val scopeOwnerIsThisClass: Boolean =
    scopeOwner?.qualifiedName?.asString() == cls.qualifiedName?.asString()
  val scopeOwnerIsAbstract: Boolean = scopeOwner?.modifiers?.contains(Modifier.ABSTRACT) == true
  val scopeOwnerIsSealed: Boolean = scopeOwner?.isEligibleSealedType() == true
  val backingOverridesDisposeAsync: Boolean =
    scopeOwner != null && (scopeOwnerIsThisClass || scopeOwnerIsAbstract || scopeOwnerIsSealed)

  return CirClass(
    name = name,
    // ADR-147: empty for an ordinary class; `Crate<T>` fills it and renders as the carrier.
    typeParameters = cls.cirTypeParameters(logger, context),
    libraryName = libraryName,
    nativePrefix = prefix,
    constructor = cirConstructor,
    secondaryConstructors = secondaryConstructors,
    properties = properties + interfaceForwards.properties,
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
    companionMembers =
      companionMembers + asyncMembers + flowRouteMembers + interfaceForwards.members,
    // ADR-159: derived from what PROJECTED, in one place, for the whole kept chain. The raw
    // `getAllFunctions()` scan this replaces read inherited members (so a subclass of an async
    // base rendered a second `DisposeAsync`, CS0108) and refused ones (so a class whose only
    // suspend member was dropped advertised `IAsyncDisposable` and implemented nothing).
    hasSuspendMethods = scopeOwner != null,
    ownsScope = scopeOwner?.qualifiedName?.asString() == cls.qualifiedName?.asString() &&
        scopeOwner != null,
    overridesDisposeAsync = scopeOwner != null && !isAbstract &&
        scopeOwner.qualifiedName?.asString() != cls.qualifiedName?.asString() &&
        (scopeOwner.modifiers.contains(Modifier.ABSTRACT) || scopeOwner.isEligibleSealedType()),
    remarks = listOfNotNull(remarks),
    doc = cls.forwardKdoc(expects)?.toCirDoc(),
    backingName = backingName,
    backingOverridesDisposeAsync = backingOverridesDisposeAsync,
    backingInherited = if (backingName == null) {
      emptyList()
    } else {
      cls.backingInheritedOverrides(
        superClassDeclaration, exportedTypes, callableCatalog, classifier, context,
      )
    },
  )
}

/**
 * The abstract members [this] inherits from the abstract bases above it and leaves unimplemented,
 * as the overrides its backing wrapper needs (`Puppy : Animal` leaving `Animal.legs()` open). C#
 * does not redeclare them on this class, so they are not in its own member list.
 *
 * Each is found on the NEAREST kept base that planned it: that base has a wrapper too
 * (`hasAbstractBacking`), so it planned the member as a call-through export and declared it
 * `abstract`, and that export dispatches virtually in Kotlin. A member this class planned itself
 * (one re-homed from a dropped base) is already in its own list, and one no base planned (a
 * refused signature) is declared by none of them, so there is nothing to override.
 */
private fun KSClassDeclaration.backingInheritedOverrides(
  superClass: KSClassDeclaration?,
  exportedTypes: Set<String>,
  callableCatalog: ForwardCallablePlanCatalog,
  classifier: ForwardBridgeTypeClassifier,
  context: NugetContext,
): List<CirBackingInherited> {
  val cls: KSClassDeclaration = this
  val ancestors: List<KSClassDeclaration> =
    generateSequence(superClass) { base -> base.forwardSuperClass(exportedTypes) }.toList()
  if (ancestors.isEmpty()) return emptyList()
  val planned: List<ForwardCallableCatalogEntry.Planned> =
    callableCatalog.entries.filterIsInstance<ForwardCallableCatalogEntry.Planned>()
  // Below a generic base, `getAllFunctions()` hands back a member that mentions `T` substituted
  // onto this class (`pick(): String`), a different node from the one the base planned, so it is
  // matched to the base's plan by the base's wildcarded signature.
  fun methodPlan(owner: KSClassDeclaration, method: KSFunctionDeclaration): ForwardCallablePlan? {
    val qualified: String = owner.qualifiedName?.asString() ?: return null
    val key: List<String> = method.forwardSignatureKey()
    val owned: List<ForwardCallableCatalogEntry.Planned> =
      planned.filter { entry -> entry.plan.invocation.symbol.substringBeforeLast('.') == qualified }
    val entry: ForwardCallableCatalogEntry.Planned? = owned.firstOrNull { it.node === method }
      ?: owned.firstOrNull { candidate ->
        val inherited: List<String?>? =
          (candidate.node as? KSFunctionDeclaration)?.forwardInheritedSignatureKey()
        inherited?.admits(key) == true
      }
    return entry?.plan
  }
  fun propertyPlan(owner: KSClassDeclaration, name: String): ForwardPropertyPlan? =
    owner.qualifiedName?.asString()
      ?.let { qualified -> callableCatalog.propertyFor("$qualified.$name") }

  val methods: MutableMap<KSClassDeclaration, MutableList<CirMethod>> = linkedMapOf()
  cls.getAllFunctions()
    .filter { method -> method.getVisibility() == Visibility.PUBLIC }
    .filter { method -> method.isAbstract && !method.isDeclaredBy(cls) }
    .filter { method -> !method.isCompilerOwnedMember(cls) }
    .filter { method -> methodPlan(cls, method) == null }
    .forEach { method ->
      val owner: KSClassDeclaration =
        ancestors.firstOrNull { base -> methodPlan(base, method) != null } ?: return@forEach
      val plan: ForwardCallablePlan = methodPlan(owner, method) ?: return@forEach
      if (!plan.publicSignature.isAbstract) return@forEach
      // Projected as the base declares it, then through the base wrapper's own override rule, so
      // a generic member restates its type parameters exactly as `Base.Backing` does (ADR-197).
      val declared: CirMethod = ForwardCirPlanProjection.classMethod(
        plan, owner.backingExportPrefix(context),
        isOverride = plan.publicSignature.isOverride,
        isVirtual = plan.publicSignature.isVirtual,
      ).asAbstract().closedOver(cls.closedArgumentsOf(owner, classifier))
      methods.getOrPut(owner) { mutableListOf() }.addAll(listOf(declared).backingOverrides())
    }

  val properties: MutableMap<KSClassDeclaration, MutableList<CirProperty>> = linkedMapOf()
  cls.getAllProperties()
    .filter { property -> property.getVisibility() == Visibility.PUBLIC }
    .filter { property -> property.isAbstract() && !property.isDeclaredBy(cls) }
    .filter { property -> propertyPlan(cls, property.simpleName.asString()) == null }
    .forEach { property ->
      val name: String = property.simpleName.asString()
      val owner: KSClassDeclaration =
        ancestors.firstOrNull { base -> propertyPlan(base, name) != null } ?: return@forEach
      val plan: ForwardPropertyPlan = propertyPlan(owner, name) ?: return@forEach
      properties.getOrPut(owner) { mutableListOf() }.add(
        ForwardCirPropertyProjection.classProperty(plan, isOverride = true)
          .closedOver(cls.closedArgumentsOf(owner, classifier)),
      )
    }

  return ancestors
    .filter { base -> base in methods || base in properties }
    .map { base ->
      CirBackingInherited(
        ownerPath = listOfNotNull(classifier.csharpNamespaceOf(base), base.nestedCsName())
          .joinToString("."),
        nativePrefix = base.backingExportPrefix(context),
        properties = properties[base].orEmpty(),
        methods = methods[base].orEmpty(),
      )
    }
}

/**
 * The C# spelling of each type argument this class closes a generic [base] over, keyed by the
 * base's type parameter (`T` to `string` for `Alcove : Trove<String>`). Empty for a non-generic
 * base. A base member's export carries `T` boxed whatever the argument, so an override restated
 * at the closed type still reads it through `NugetMarshal.FromHandle<string>`.
 */
private fun KSClassDeclaration.closedArgumentsOf(
  base: KSClassDeclaration,
  classifier: ForwardBridgeTypeClassifier,
): Map<String, String> {
  if (base.typeParameters.isEmpty()) return emptyMap()
  val supertype: KSType = checkNotNull(
    getAllSuperTypes().firstOrNull { type ->
      type.declaration.qualifiedName?.asString() == base.qualifiedName?.asString()
    },
  ) { "${qualifiedName?.asString()} does not derive from ${base.qualifiedName?.asString()}" }
  return base.typeParameters.zip(supertype.arguments).associate { (parameter, argument) ->
    val type: KSType = checkNotNull(argument.type?.resolve()) {
      "${qualifiedName?.asString()} closes ${base.qualifiedName?.asString()} over a star " +
          "projection, which has no C# spelling"
    }
    parameter.name.asString() to classifier.classify(type).forwardPublicCsharpType()
  }
}

/** A type parameter's name as a C# type, never a member access (`.T`) or a longer identifier. */
private fun typeParameterName(name: String): Regex = Regex("""(?<![\w.])${Regex.escape(name)}\b""")

private fun String.closedOver(arguments: Map<String, String>): String =
  arguments.entries.fold(this) { text, (name, spelling) ->
    typeParameterName(name).replace(text) { spelling }
  }

/** A generic base's abstract member restated at the closed type arguments of a class below it. */
private fun CirMethod.closedOver(arguments: Map<String, String>): CirMethod {
  if (arguments.isEmpty()) return this
  return copy(
    returnType = returnType.closedOver(arguments),
    parameters = parameters.map { parameter ->
      parameter.copy(
        type = parameter.type.closedOver(arguments),
        nativeType = parameter.nativeType.closedOver(arguments),
      )
    },
    body = body.closedOver(arguments),
  )
}

private fun CirProperty.closedOver(arguments: Map<String, String>): CirProperty {
  if (arguments.isEmpty()) return this
  return copy(
    type = type.closedOver(arguments),
    getter = getter.closedOver(arguments),
    setter = setter?.closedOver(arguments),
  )
}

/**
 * The prefix a base's planned member exports sit under: a sealed arm's is its sealed base's
 * prefix plus the arm name (the sealed route's own spelling), anything else's its symbol prefix.
 */
private fun KSClassDeclaration.backingExportPrefix(context: NugetContext): String {
  val sealed: KSClassDeclaration? = if (isSealedSubclass()) forwardArmSealedParent() else null
  return if (sealed != null) {
    "${sealed.nativePrefix(context.symbols)}_${simpleName.asString().lowercase()}"
  } else {
    nativePrefix(context.symbols)
  }
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
  // ADR-174: the carrier a generic implementer's explicit implementation calls the interface's
  // imports through (`FeedNative.`, dot included). Empty keeps the owner's own private externs.
  nativeCarrier: String = "",
): CirProperty? {
  val propName: String = prop.simpleName.asString()
  // The extern stem (`CirProperty.nativeStem`), from the cleaned Kotlin name, never the public one.
  val csPropName: String = propName.asCSymbol().replaceFirstChar { it.uppercase() }
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
    propTypeResolved.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  } else null
  // ADR-067, widened 2026-09-20: a nullable ELEMENT is threaded on BOTH flow shapes. It used to be
  // `isStateFlowType && ...`, which left a plain `Flow<T?>` declared as a non-null `KotlinFlow<T>`
  // while the Kotlin half boxed `value as Any`, so the first null emission faulted the stream
  // (measured: `KotlinException` out of `MoveNextAsync`) instead of arriving as null -- a silent
  // nullability drop, the worst of the three possible outcomes. The element wire is identical on
  // the two shapes (one `StableRef` handle per item, `IntPtr.Zero` for null), so this reuses
  // ADR-067's encoding rather than inventing a second one. A nullable MEMBER (`StateFlow<T>?`) is
  // still StateFlow-only: it is the `_has_value` two-call pattern, which plain Flow has no half of.
  val isNullableElement: Boolean = flowElementTypeResolved?.isMarkedNullable == true
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
  // ADR-040: an interface element is DECLARED with the projected interface and READ through the
  // backing wrapper, the same split the suspend route takes. Deliberately not applied to a
  // settable MutableStateFlow property: its write path spells `v._handle`, which the projection
  // does not carry, and no fixture exercises that combination.
  val flowElementInterface: BridgeType.Interface? =
    if (isMutableStateFlowProperty) null
    else classifier.legacyFlowElementInterface(flowElementTypeResolved)
  // ROADMAP Phase 4 (ADR-151 amendment): a bare `ByteArray` element is `byte[]`, read through the
  // same per-member `read:` seam a collection element uses. It has to be decided here because
  // `qualifiedElementCsType` below crashes the processor on a Kotlin builtin (ADR-123's guard).
  val flowElementBytes: Boolean =
    classifier.legacyFlowElementShape(flowElementTypeResolved) is
        ForwardLegacyFlowElementShape.Bytes
  if (flowElementBytes) tracker.needsBytes = true
  val flowElementType: String? = when {
    flowElementCollection != null -> flowElementCollection.forwardPublicCsharpType()
    flowElementBytes -> legacyBytesCsharpType(isNullableElement)
    flowElementInterface != null ->
      flowElementInterface.csharpType + if (isNullableElement) "?" else ""
    // ADR-066: qualified, not by simple name: an admitted dependency-module element type is
    // not guaranteed to share this class's own namespace.
    isFlowType || isStateFlowType ->
      classifier.legacyGenericSealedElement(flowElementTypeResolved, isNullableElement)
        ?: qualifiedElementCsType(flowElementTypeResolved, context, isNullableElement)

    else -> null
  }
  val flowElementRead: String? = flowElementCollection
    ?.let { collection -> legacyFlowElementReadArgument(collection) }
    ?: flowElementInterface
      ?.let { iface -> legacyInterfaceElementReadArgument(iface, isNullableElement) }
    ?: legacyBytesElementReadArgument(isNullableElement).takeIf { flowElementBytes }
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
      val collectNativeName = "${nativeCarrier}Native_Get${csPropName}Collect"
      val valueNativeName = "${nativeCarrier}Native_Get${csPropName}Value"
      val hasValueNativeName = "${nativeCarrier}Native_Get${csPropName}HasValue"
      val setValueNativeName = "${nativeCarrier}Native_Set${csPropName}Value"
      val ctorName: String =
        if (isMutableStateFlowProperty) "KotlinMutableStateFlow" else "KotlinStateFlow"
      buildString {
        appendLine()
        appendLine("                if (_handle.IsInvalid)")
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
      val collectNativeName = "${nativeCarrier}Native_Get${csPropName}Collect"
      buildString {
        appendLine()
        appendLine("                if (_handle.IsInvalid)")
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
    isMutableStateFlowObjectElement -> KOTLIN_HANDLE
    else -> flowElementType ?: nativeReturnType
  }

  return CirProperty(
    name = prop.csharpMemberName(),
    type = type,
    nativeReturnType = nativeReturnType,
    nativeSetterType = mutableStateFlowNativeSetterType,
    nativeName = propName.asCSymbol(),
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
    // ADR-179: the public name takes `@CSharpName`; the extern stem keeps the Kotlin name.
    val csMethodName: String = method.csharpMemberName()
    val nativeStem: String =
      "Native_${methodName.asCSymbol().replaceFirstChar { it.uppercase() }}$suffix"
    val returnType = method.returnType?.resolve()?.expandAliases()
    val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
    val isStateFlowMethod: Boolean = returnQualified in STATE_FLOW_TYPES
    val flowElementTypeResolved: KSType? =
      returnType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
    // ADR-067 (widened 2026-09-20): nullable ELEMENT threading mirrors the sibling property branch
    // above -- both flow shapes, since the per-item wire is the same handle either way. A nullable
    // MEMBER stays StateFlow-only (the `_has_value` probe has no plain-Flow half).
    val isNullableElement: Boolean = flowElementTypeResolved?.isMarkedNullable == true
    val isNullableMember: Boolean = isStateFlowMethod && returnType?.isMarkedNullable == true
    // ADR-071: mirrors the sibling property branch above -- a genuinely DECLARED
    // MutableStateFlow<T> function return (not narrowed to StateFlow<T>) gains a settable
    // `.Value`, gated on non-nullable element/member (both deferred) and a v1-supported element.
    // ADR-071 (2026-09-11): that gate is now the shared predicate the Kotlin emitter reads, and it
    // selects the held route below rather than an extra export on the ADR-065 pair.
    val isHeldMutableStateFlow: Boolean = method.returnsHeldMutableStateFlow()
    val isMutableStateFlowObjectElement: Boolean =
      isHeldMutableStateFlow && isMutableStateFlowElementObject(flowElementTypeResolved)
    if (isHeldMutableStateFlow) tracker.needsMutableStateFlow = true
    // ADR-123: a collection element, spelled and read like the ordinary route's collection result.
    // A refused element never reaches here: `filteredMethods` drops the member upstream.
    val flowElementCollection: BridgeType.Collection? =
      classifier.legacyFlowElementCollection(returnType)
    if (flowElementCollection != null) tracker.trackCollection(flowElementCollection)
    // ADR-040: the sibling property branch's rule -- an interface element is declared with the
    // projection and read through the backing wrapper, and the held MutableStateFlow route (which
    // writes `v._handle`) keeps the shipped spelling.
    val flowElementInterface: BridgeType.Interface? =
      if (isHeldMutableStateFlow) null
      else classifier.legacyFlowElementInterface(flowElementTypeResolved)
    // ROADMAP Phase 4: the sibling property branch's bytes arm, for a
    // `fun ticks(): Flow<ByteArray>`.
    val flowElementBytes: Boolean =
      classifier.legacyFlowElementShape(flowElementTypeResolved) is
          ForwardLegacyFlowElementShape.Bytes
    if (flowElementBytes) tracker.needsBytes = true
    // ADR-066: qualified, not by simple name, see the sibling property branch above for why.
    val flowCsElementType: String = flowElementCollection?.forwardPublicCsharpType()
      ?: legacyBytesCsharpType(isNullableElement).takeIf { flowElementBytes }
      ?: flowElementInterface?.let { it.csharpType + if (isNullableElement) "?" else "" }
      ?: classifier.legacyGenericSealedElement(flowElementTypeResolved, isNullableElement)
      ?: qualifiedElementCsType(flowElementTypeResolved, context, isNullableElement)
    val flowElementRead: String? = flowElementCollection
      ?.let { collection -> legacyFlowElementReadArgument(collection) }
      ?: flowElementInterface
        ?.let { iface -> legacyInterfaceElementReadArgument(iface, isNullableElement) }
      ?: legacyBytesElementReadArgument(isNullableElement).takeIf { flowElementBytes }

    // ADR-114: a collection parameter takes the public collection type with an IntPtr native
    // slot; every other parameter keeps mapParamType's shipped spelling.
    val methodParams: List<CirParameter> =
      legacyRouteParameters(
        method.parameters, classifier, tracker,
        callableCatalog.legacyDefaultFlags(method),
        callableCatalog.legacySuspendSiblingArities(method),
      )

    // ADR-071 (2026-09-11): a `MutableStateFlow<T>`-declared return is HELD -- the Kotlin half
    // invokes the function once and hands the flow's own handle back, so this member's imports are
    // an acquire and a flow-keyed setter, not the ADR-065 per-member `_collect` / `_value` pair
    // (which re-invoked the function on every access and lost a write into a throwaway flow).
    // Reads go through ADR-068's shared `NugetStateFlowNative`, which is what
    // [CollectionHelperTracker.needsSuspendStateFlow] renders.
    if (method.returnsHeldMutableStateFlow()) {
      tracker.needsSuspendStateFlow = true
      val acquireArgs: String = methodParams.joinToString(", ") { it.nativeArgument }
      val acquireImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = "${prefix}_$cname",
        returnType = "IntPtr",
        name = nativeStem,
        parameters = listOf(CirParameter("handle", KOTLIN_HANDLE)) +
            methodParams.nativeImportParameters(),
        visibility = CirVisibility.PRIVATE,
      )

      // The owner handle and the method's own parameters are gone from the setter: the write is
      // keyed on the flow handle the acquire returned. The trailing `out IntPtr error` stays
      // (MutableStateFlow.value conflates by Any.equals on the previous value, which can throw).
      val heldSetValueImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = "${prefix}_${cname}_set_value",
        returnType = "void",
        name = "${nativeStem}SetValue",
        parameters = listOf(
          CirParameter("flowHandle", KOTLIN_HANDLE),
          CirParameter(
            "value",
            if (isMutableStateFlowObjectElement) KOTLIN_HANDLE else flowCsElementType,
          ),
        ),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      )

      val heldMethod = CirMethod(
        name = method.csharpMemberName(),
        returnType = "KotlinMutableStateFlow<$flowCsElementType>",
        nativeName = nativeStem,
        parameters = methodParams,
        // The acquire call's arguments, not the collect delegate's: the call happens once, in the
        // method body, before the wrapper is constructed.
        body = if (acquireArgs.isEmpty()) "_handle" else "_handle, $acquireArgs",
        isFlow = true,
        isStateFlow = true,
        flowElementType = flowCsElementType,
        isMutableStateFlow = true,
        stateFlowSetValueNativeName = "${nativeStem}SetValue",
        isMutableStateFlowElementObject = isMutableStateFlowObjectElement,
      )

      return@flatMap listOf(acquireImport, heldSetValueImport, heldMethod)
    }

    // The fixed slots and the collect lambda's parameters move off a user parameter spelled like
    // one of them; the lambda's would otherwise shadow the user's argument inside the native call.
    val flowNames: ForwardLegacyNames =
      legacyCsharpNames(method.parameters, classifier, callableCatalog.legacyDefaultFlags(method))
    val callbackNames: List<String> =
      listOf(flowNames.onNext, flowNames.onComplete, flowNames.onError, flowNames.userData)
    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", KOTLIN_HANDLE),
      CirParameter(flowNames.scopeHandle, KOTLIN_HANDLE),
    ) + methodParams.nativeImportParameters() +
        callbackNames.map { name -> CirParameter(name, "IntPtr") }

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_collect",
      returnType = "IntPtr",
      name = "${nativeStem}Collect",
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = methodParams.joinToString(", ") { it.nativeArgument }
    val callbackArgs: String = callbackNames.joinToString(", ")
    val nativeCallArgs: String = if (paramNames.isEmpty()) {
      "_handle, GetOrCreateScope(), $callbackArgs"
    } else {
      "_handle, GetOrCreateScope(), $paramNames, $callbackArgs"
    }

    if (isStateFlowMethod) {
      // ADR-065: sibling synchronous `_value` export -- takes handle + the method's own
      // parameters (no scope, no callbacks, no errorOut; StateFlow.value cannot throw).
      val valueNativeImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = "${prefix}_${cname}_value",
        returnType = "IntPtr",
        name = "${nativeStem}Value",
        parameters = listOf(CirParameter("handle", KOTLIN_HANDLE)) +
            methodParams.nativeImportParameters(),
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
          parameters = listOf(CirParameter("handle", KOTLIN_HANDLE)) +
              methodParams.nativeImportParameters(),
          visibility = CirVisibility.PRIVATE,
        )
      } else null

      // ADR-071 (2026-09-11): no `_set_value` sibling here. What reaches this point is a
      // read-only `StateFlow<T>` return; the settable one took the held route above.
      val stateFlowMethod = CirMethod(
        name = csMethodName,
        returnType = "KotlinStateFlow<$flowCsElementType>${if (isNullableMember) "?" else ""}",
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
        flowCallbackNames = callbackNames,
      )

      return@flatMap listOfNotNull(
        nativeImport,
        valueNativeImport,
        hasValueNativeImport,
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
      flowCallbackNames = callbackNames,
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
  // ADR-150: the expect index, so a documented suspend member of an `expect class` keeps its
  // summary; a bare `forwardKdoc()` here could never reach the expect half.
  expects: ExpectIndex = ExpectIndex(),
): List<CirMember> {
  // ADR-068/194: preserve asynchronous acquisition before exposing the acquired stream.
  // Plain Flow uses typed per-member collection; StateFlow retains its existing shared helpers.
  val (stateFlowMethods, plainMethods) = suspendMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in STATE_FLOW_TYPES || returnQualified in FLOW_TYPES
  }

  if (plainMethods.isNotEmpty()) tracker.needsAsync = true
  if (stateFlowMethods.isNotEmpty()) {
    tracker.needsFlow = true
    tracker.needsAsync = true
    val hasStateFlow: Boolean = stateFlowMethods.any { method ->
      method.returnType?.resolve()?.expandAliases()?.declaration?.qualifiedName?.asString() in
        STATE_FLOW_TYPES
    }
    if (hasStateFlow) {
      tracker.needsStateFlow = true
      tracker.needsSuspendStateFlow = true
    }
  }

  val asyncMembers: List<CirMember> = plainMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    // ADR-118: the planner's overload number, on the C symbol and on the extern stem alike.
    val suffix: String = callableCatalog.overloadSuffix(method)
    val cname: String = toCName(methodName) + suffix
    val csMethodName: String = methodName.asCSymbol().replaceFirstChar { it.uppercase() }
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
    // ROADMAP Phase 4: `suspend fun snapshot(): ByteArray` is `Task<byte[]>`, read with
    // `NugetMarshal.ReadBytes` off the awaited handle (the Kotlin half already retains the array).
    if (returnShape is ForwardLegacyReturnShape.Bytes) tracker.needsBytes = true

    // ADR-114: a collection parameter takes the public collection type with an IntPtr native
    // slot; every other parameter keeps mapParamType's shipped spelling.
    val methodParams: List<CirParameter> =
      legacyRouteParameters(
        method.parameters, classifier, tracker,
        callableCatalog.legacyDefaultFlags(method),
        callableCatalog.legacySuspendSiblingArities(method),
      )

    // Issue #108: carry the nullability through, same as the top-level suspend route.
    val asyncReturnType: String = when {
      isUnit -> ""
      returnShape is ForwardLegacyReturnShape.Marshalled -> returnShape.declaredCsharpType()
      returnShape is ForwardLegacyReturnShape.Bytes ->
        legacyBytesCsharpType(returnShape.nullable)
      // ADR-040: the projected interface, already `global::`-qualified and owner-chained by the
      // classifier. `nestedCsName()` below would spell the backing wrapper here.
      returnShape is ForwardLegacyReturnShape.Interface -> returnShape.declaredCsharpType()
      // ROADMAP Phase 4 line 23: `global::`-qualified, so a dependency type in another namespace
      // resolves; a value class is the record struct `NugetUnbox` returns.
      returnShape is ForwardLegacyReturnShape.Handle -> returnShape.declaredCsharpType()
      returnShape is ForwardLegacyReturnShape.ValueClass -> returnShape.declaredCsharpType()
      returnShape is ForwardLegacyReturnShape.Enum -> returnShape.declaredCsharpType()
      else -> {
        // ADR-118: a nested sealed arm is declared inside its base (ADR-009), so the bare simple
        // name is unresolvable at namespace scope (CS0246). `nestedCsName()` walks class parents
        // only, so a top-level type stays bare.
        val csharp: String = KOTLIN_TO_CSHARP_PARAM[methodReturn]
          ?: (resolvedReturn?.declaration as? KSClassDeclaration)?.nestedCsName()
          ?: methodReturn
        if (resolvedReturn?.isMarkedNullable == true) "$csharp?" else csharp
      }
    }

    // The fixed slots move off a user parameter spelled `scopeHandle` / `userData`, never the
    // user's parameter; the Kotlin export mints the same way (`legacyKotlinNames`).
    val slotNames: ForwardLegacyNames =
      legacyCsharpNames(method.parameters, classifier, callableCatalog.legacyDefaultFlags(method))
    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", KOTLIN_HANDLE),
      CirParameter(slotNames.scopeHandle, KOTLIN_HANDLE),
    ) + methodParams.nativeImportParameters() +
        listOf(
          // ADR-102: a raw thunk address, not a delegate the marshaller would have to build a
          // native-to-managed stub for. The native symbol is unchanged; Kotlin is untouched.
          CirParameter(slotNames.callback, "IntPtr"),
          CirParameter(slotNames.userData, "IntPtr"),
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
      // ADR-150: the suspend function's own KDoc, on its `Async` projection. `@return` documents
      // the awaited value, which is what the `Task<T>` yields.
      // ADR-150: the rendered `Async` signature ends in `CancellationToken cancellationToken`
      // (ADR-023), which is a parameter of the C# member and therefore needs a tag of its own the
      // moment any other parameter has one (CS1573, fatal under `GeneratedBindingsCheck`).
      doc = method.forwardKdoc(expects)?.toCirDoc(
        methodParams.map { it.name } + asyncCancellationParameter(methodParams),
        hasResult = !isUnit,
      ),
      name = method.csharpAsyncMemberName(),
      nativeName = nativeStem,
      returnType = taskReturnType,
      parameters = methodParams,
      body = "",
      isAsync = true,
      asyncReturnType = asyncReturnType,
      // ADR-119 / ADR-131: exhaustive on purpose -- a new return shape must be answered here
      // rather than fall through an `else` into the renderer's `new T(resultPtr)`.
      asyncResultRead = when (returnShape) {
        is ForwardLegacyReturnShape.Marshalled ->
          returnShape.legacyMarshalledRead("resultPtr")

        // ADR-131: spelled off `asyncReturnType`, which is the same `nestedCsName()` string
        // `Task<...>` above is built from, so the declared type and the read cannot drift.
        is ForwardLegacyReturnShape.Discriminated -> legacyDiscriminatedRead(
          handle = "resultPtr",
          csharpType = asyncReturnType.removeSuffix("?"),
          nullable = returnShape.nullable,
        )

        // ADR-040: the declared type above is the interface, so the read has to name the backing
        // wrapper itself rather than be spelled off `asyncReturnType` the way the sealed arm is.
        // Without this the renderer's `else` arm would emit `new ...IKeeper(resultPtr)` (CS0144).
        is ForwardLegacyReturnShape.Interface -> returnShape.legacyInterfaceRead("resultPtr")

        // ROADMAP Phase 4: the awaited handle IS the bytes handle, so the read is the standalone
        // `ReadBytes`, which copies and disposes it. The renderer's `else` would have emitted
        // `new byte[](resultPtr)`.
        is ForwardLegacyReturnShape.Bytes ->
          legacyBytesRead("resultPtr", returnShape.nullable)

        // `Refused` already returned above; `Plain` keeps the renderer's shipped spelling.
        // ROADMAP Phase 4 line 23: the qualified handle constructor, and `NugetUnbox` for a value
        // class.
        is ForwardLegacyReturnShape.Handle -> returnShape.legacyHandleRead("resultPtr")

        is ForwardLegacyReturnShape.ValueClass -> returnShape.legacyValueClassRead("resultPtr")

        // The boxed ordinal, cast back to the enum (never handed back as an `int`).
        is ForwardLegacyReturnShape.Enum -> returnShape.legacyEnumRead("resultPtr")

        ForwardLegacyReturnShape.Plain, is ForwardLegacyReturnShape.Refused -> null
      },
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
    val csMethodName: String = methodName.asCSymbol().replaceFirstChar { it.uppercase() }
    val nativeStem: String = "Native_$csMethodName${suffix}Async"
    val element: SuspendStateFlowElement =
      suspendStateFlowElement(method.returnType?.resolve(), classifier, context, tracker)

    // ADR-114: a collection parameter takes the public collection type with an IntPtr native
    // slot; every other parameter keeps mapParamType's shipped spelling.
    val methodParams: List<CirParameter> =
      legacyRouteParameters(
        method.parameters, classifier, tracker,
        callableCatalog.legacyDefaultFlags(method),
        callableCatalog.legacySuspendSiblingArities(method),
      )

    // The fixed slots move off a user parameter spelled `scopeHandle` / `userData`, never the
    // user's parameter; the Kotlin export mints the same way (`legacyKotlinNames`).
    val slotNames: ForwardLegacyNames =
      legacyCsharpNames(method.parameters, classifier, callableCatalog.legacyDefaultFlags(method))
    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", KOTLIN_HANDLE),
      CirParameter(slotNames.scopeHandle, KOTLIN_HANDLE),
    ) + methodParams.nativeImportParameters() +
        listOf(
          // ADR-102: a raw thunk address, not a delegate the marshaller would have to build a
          // native-to-managed stub for. The native symbol is unchanged; Kotlin is untouched.
          CirParameter(slotNames.callback, "IntPtr"),
          CirParameter(slotNames.userData, "IntPtr"),
        )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_async",
      returnType = "IntPtr",
      name = nativeStem,
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val asyncReturnType: String = element.asyncReturnType

    val asyncMethod = CirMethod(
      // ADR-150: the suspend function's own KDoc, on its `Async` projection. `@return` documents
      // the awaited value, which is what the `Task<T>` yields.
      doc = method.forwardKdoc(expects)?.toCirDoc(
        methodParams.map { it.name } + asyncCancellationParameter(methodParams),
        hasResult = true,
      ),
      name = method.csharpAsyncMemberName(),
      nativeName = nativeStem,
      returnType = "Task<$asyncReturnType>",
      parameters = methodParams,
      body = "",
      isAsync = true,
      asyncReturnType = asyncReturnType,
      // The `read:` the awaited `KotlinStateFlow<T>` is constructed with (ADR-123's slot,
      // ADR-136's expression). Null for a class element, which keeps the ctor's default read.
      flowElementRead = element.read,
      acquiredFlowCollectNativeName =
        if (element.asyncReturnType.startsWith("KotlinFlow<")) "${nativeStem}Collect" else null,
    )

    val collector: List<CirMember> = if (asyncMethod.acquiredFlowCollectNativeName != null) {
      listOf(
        acquiredFlowCollectImport(
          libraryName,
          "${prefix}_${cname}",
          asyncMethod.acquiredFlowCollectNativeName,
        ),
      )
    } else {
      emptyList()
    }
    listOf(nativeImport, asyncMethod) + collector
  }

  return asyncMembers + suspendStateFlowMembers
}

/**
 * ADR-068: how the awaited `KotlinStateFlow<T>` of a `suspend fun` returning `StateFlow<T>` is
 * spelled and read. Shared by the class-method route and (2026-09-27 amendment) the top-level
 * route, so the two cannot answer the same element differently.
 */
internal data class SuspendStateFlowElement(
  /** `KotlinStateFlow<T>`, the type the `Task` yields. */
  val asyncReturnType: String,
  /** The `read:` the holder is constructed with (ADR-123's slot), or null for the default read. */
  val read: String?,
)

internal fun suspendStateFlowElement(
  returnType: KSType?,
  classifier: ForwardBridgeTypeClassifier,
  context: NugetContext,
  tracker: CollectionHelperTracker? = null,
): SuspendStateFlowElement {
  val element: KSType? = returnType?.expandAliases()?.arguments?.firstOrNull()?.type?.resolve()
  if (returnType?.expandAliases()?.declaration?.qualifiedName?.asString() in FLOW_TYPES) {
    val nullable = element?.isMarkedNullable == true
    val collection = classifier.legacyFlowElementCollection(returnType)
    if (collection != null) tracker?.trackCollection(collection)
    val iface = classifier.legacyFlowElementInterface(element)
    val bytes = classifier.legacyFlowElementShape(element) is ForwardLegacyFlowElementShape.Bytes
    if (bytes && tracker != null) tracker.needsBytes = true
    val cs = collection?.forwardPublicCsharpType()
      ?: legacyBytesCsharpType(nullable).takeIf { bytes }
      ?: iface?.let { it.csharpType + if (nullable) "?" else "" }
      ?: classifier.legacyGenericSealedElement(element, nullable)
      ?: qualifiedElementCsType(element, context, nullable)
    val read = collection?.let { legacyFlowElementReadArgument(it) }
      ?: iface?.let { legacyInterfaceElementReadArgument(it, nullable) }
      ?: legacyBytesElementReadArgument(nullable).takeIf { bytes }
    return SuspendStateFlowElement("KotlinFlow<$cs>", read)
  }

  // v1 scope (ADR-068): nullable element/member is deferred; mirror ADR-065's plain (non-null)
  // shape only. The `false` passed to `legacyInterfaceElementReadArgument` below is safe rather
  // than optimistic: since 2026-09-20 `legacyReturnShape` REFUSES a nullable element on this
  // bucket by name, so a `suspend fun (): StateFlow<T?>` never reaches here at all. It has to be
  // refused, not threaded: this bucket reads through the module-wide `nuget_stateflow_value`,
  // which boxes `value as Any` with no null arm and no `try`, so a null would throw out of a
  // `@CName` export. The ADR-065 property/method routes thread it because they own per-member
  // exports they can widen to `COpaquePointer?`.
  // ADR-040 / ADR-133 amendment (2026-09-14): an interface element is DECLARED with the projected
  // interface and READ through the backing wrapper, the same split the property `Flow`/`StateFlow`
  // route takes. A class element keeps `qualifiedElementCsType` byte for byte.
  val elementInterface: BridgeType.Interface? = classifier.legacyFlowElementInterface(element)
  val csElementType: String = elementInterface?.csharpType
    ?: classifier.legacyGenericSealedElement(element, nullable = false)
    ?: qualifiedElementCsType(element, context)
  return SuspendStateFlowElement(
    asyncReturnType = "KotlinStateFlow<$csElementType>",
    read = elementInterface?.let { iface -> legacyInterfaceElementReadArgument(iface, false) },
  )
}

/**
 * ADR-157: the boxed enum arm, `{Enum}Arm`. Two members, both planned: the constructor taking the
 * C# enum and the `Value` getter returning it. Never the enum's own members -- those are ADR-006
 * extension methods on the enum itself, which is still declared as a C# `enum` beside this box.
 */
private fun enumArmSubclass(
  sealed: KSClassDeclaration,
  arm: KSClassDeclaration,
  subPrefix: String,
  callableCatalog: ForwardCallablePlanCatalog,
  tracker: CollectionHelperTracker,
  context: NugetContext,
  expects: ExpectIndex,
): CirSealedSubclass {
  val armQualifiedName: String? = arm.qualifiedName?.asString()
  val properties: List<CirProperty> = listOfNotNull(
    armQualifiedName
      ?.let { callableCatalog.propertyFor("$it.$ENUM_ARM_VALUE_MEMBER") }
      ?.let { plan ->
        tracker.trackProperty(plan)
        ForwardCirPropertyProjection.classProperty(plan)
      },
  )
  val constructors: List<CirConstructor> =
    (armQualifiedName?.let { callableCatalog.constructors(it) } ?: emptyList()).map { plan ->
      tracker.trackPlan(plan)
      ForwardCirPlanProjection.constructor(plan)
    }
  return CirSealedSubclass(
    doc = arm.forwardKdoc(expects)?.toCirDoc(),
    name = arm.enumArmName(),
    nativePrefix = subPrefix,
    properties = properties,
    constructors = constructors,
    // Where the enum is declared, the box is declared: nested in the base for a nested enum, at
    // namespace level beside it otherwise. The bare name belongs to the C# enum either way.
    isNested = arm.parentDeclaration?.qualifiedName?.asString() ==
        sealed.qualifiedName?.asString(),
    boxedEnumType = properties.firstOrNull()?.type ?: arm.nestedCsName(),
  )
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
  // ADR-134: the owner walk, supplied by `CirTranslator` so the base and each arm can carry the
  // nested declarations Kotlin declares inside them. Required rather than defaulted: a default
  // would silently declare none for a future caller that forgot to pass it, which is the one
  // failure mode of this feature that emits neither a twin nor a diagnostic.
  nestedOf: (KSClassDeclaration) -> List<CirDeclaration>,
  // ADR-150: the expect index. A bare `forwardKdoc()` here cannot reach the `expect` half,
  // so a documented `expect` declaration of this family rendered with no summary at all.
  expects: ExpectIndex = ExpectIndex(),
  // ADR-110 amendment (ROADMAP line 32): the base and each arm record their rendered names here
  // for the inherited CS0108 post-pass.
  memberRegistry: CsMemberRegistry? = null,
  // ADR-199: an intermediate arm's parent's C# type parameters, which its phantoms restate.
  parentTypeParameters: List<CirTypeParameter> = emptyList(),
): CirSealedClass {
  val libraryName: String = context.libraryName
  val name: String = cls.simpleName.asString()
  val prefix: String = cls.nativePrefix(context.symbols)
  val qualifiedName: String? = cls.qualifiedName?.asString()

  // ADR-101 amendment (2026-09-27): the sealed route takes the ordinary class's base list. An
  // exported supertype is named, so `is`/`as` against it hold; an unexported one is dropped with
  // `SKIPPED_UNEXPORTED_SUPERTYPE`, and both planners re-home its public members onto the base
  // (`ForwardClassMembership.isForwardMemberOf`), the only C# carrier it has.
  val superClassDeclaration: KSClassDeclaration? = cls.forwardSuperClass(exportedTypes)
  // ADR-199: an intermediate arm of a generic hierarchy derives from its parent by the arm rule
  // (`Outcome<T>` for `Lapse : Outcome<Nothing>`), which the ordinary base spelling cannot spell.
  val parentShape: ForwardSealedArmShape? = cls.forwardArmSealedParent()
    ?.takeIf { parent -> parent.isGenericSealedType() }
    ?.let { parent -> cls.forwardSealedArmShape(parent) }
  val (keptSuperClass: String?, interfaces: List<String>) = forwardBaseList(
    cls, name, superClassDeclaration.takeIf { parentShape == null }, exportedTypes, classifier,
    logger,
  )
  val superClass: String? = if (parentShape != null) {
    sealedBaseSpelling(checkNotNull(cls.forwardArmSealedParent()), parentShape, classifier)
  } else {
    keptSuperClass
  }
  // ADR-199: a generic sealed type renders `Outcome<T>`; an intermediate arm's parameters are the
  // arm rule's against its parent (`Lapse<T>`), possibly none (`Cell.Odd : Cell<int>`).
  val baseTypeParameters: List<CirTypeParameter> = when {
    parentShape != null ->
      cls.armCirTypeParameters(parentShape, parentTypeParameters, logger, context)
    cls.isGenericSealedType() -> cls.cirTypeParameters(logger, context)
    else -> emptyList()
  }

  // ADR-111/ADR-116 amendment (2026-09-11): the base's members, off base-keyed plans and the same
  // projections an ordinary class uses. Always `virtual`, never `abstract`: the export dispatches
  // through the base type in Kotlin, so the C# member has a body, and an `abstract` one would
  // oblige every arm to declare an override -- which the covariant arm (`Empty.sides: Int` over
  // `Int?`) cannot spell at all (CS1715, then CS0534 for the member it could not declare).
  // ADR-101 amendment (2026-09-27): no declared-only filter here. The catalog lookup is the gate,
  // so an inherited property is rendered exactly when the property planner re-homed it.
  val baseProperties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .mapNotNull { prop ->
      val planned: ForwardPropertyPlan? =
        qualifiedName?.let { callableCatalog.propertyFor("$it.${prop.simpleName.asString()}") }
      if (planned == null) return@mapNotNull null
      tracker.trackProperty(planned)
      // An `override val` of the kept base's own open member overrides it in C# as well.
      val isOverride: Boolean = prop.overridesBaseClassMember(superClassDeclaration)
      ForwardCirPropertyProjection.classProperty(
        planned,
        isOverride = isOverride,
        isVirtual = !isOverride,
      )
    }
    .toList()

  val baseMethods: List<CirMethod> =
    (qualifiedName?.let { callableCatalog.classMethods(it) } ?: emptyList()).map { plan ->
      tracker.trackPlan(plan)
      ForwardCirPlanProjection.classMethod(
        plan = plan,
        nativePrefix = prefix,
        isOverride = plan.publicSignature.isOverride,
        isVirtual = plan.publicSignature.isVirtual,
      )
    }
  // ADR-175: the base's own suspend / Flow / StateFlow members, on the legacy routes under the
  // base's own prefix, projected by the same `suspendMembers` / `flowMembers` / `flowProperty` an
  // ordinary class calls. The Kotlin exports dispatch through `asStableRef<Base>()`, so one C#
  // member on the base serves every arm, the enum-arm box and a default body alike. The selectors
  // are the ones the Kotlin export loop and the arm filter read, so the halves cannot disagree.
  val baseAsyncMethods: List<KSFunctionDeclaration> = cls.forwardSealedBaseAsyncMethods(classifier)
  val baseAsyncMembers: List<CirMember> = suspendMembers(
    suspendMethods = baseAsyncMethods.filter { it.modifiers.contains(Modifier.SUSPEND) },
    prefix = prefix,
    libraryName = libraryName,
    classifier = classifier,
    tracker = tracker,
    callableCatalog = callableCatalog,
    context = context,
    expects = expects,
  )
  val baseFlowMembers: List<CirMember> = flowMembers(
    flowMethods = baseAsyncMethods.filterNot { it.modifiers.contains(Modifier.SUSPEND) },
    prefix = prefix,
    libraryName = libraryName,
    classifier = classifier,
    tracker = tracker,
    callableCatalog = callableCatalog,
    context = context,
  )
  val baseFlowProperties: List<CirProperty> = cls.forwardSealedBaseFlowProperties(classifier)
    .mapNotNull { prop -> flowProperty(prop, name, context, classifier, tracker) }
  // ADR-175 (ADR-159's root-most rule): who owns the one scope of every instance in the hierarchy.
  // The sealed base itself when it projects a scope-using member, else a kept base above it, else
  // nobody, in which case each arm keeps owning its own (ADR-118/124, unchanged).
  val baseScopeOwner: KSClassDeclaration? = cls.forwardScopeOwner(classifier, exportedTypes)
  val baseOwnsScope: Boolean = baseScopeOwner != null &&
      baseScopeOwner.qualifiedName?.asString() == qualifiedName
  // The owner's `DisposeAsync` is abstract (so an arm overrides it with a body) when the owner
  // renders abstract: the sealed base always does, a kept ordinary base when Kotlin says so.
  val armsOverrideDisposeAsync: Boolean = baseScopeOwner != null &&
      (baseOwnsScope || baseScopeOwner.modifiers.contains(Modifier.ABSTRACT) ||
          baseScopeOwner.isEligibleSealedType())

  // ADR-162: the sealed base renders `: IDisposable, INugetHandle` with its own `Dispose()`
  // (CirSealedRenderer), so the reserved signature applies here too.
  emitCsharpSignatureCollisions(
    baseMethods + (baseAsyncMembers + baseFlowMembers).filterIsInstance<CirMethod>(),
    name,
    cls,
    logger,
    HANDLE_RESERVED_SIGNATURES,
  )
  // ADR-110 amendment (ROADMAP line 32): `abstract val area` beside `fun area(scale)` on the base.
  val baseNames: List<CsMemberName> =
    (baseProperties + baseFlowProperties + baseMethods + baseAsyncMembers + baseFlowMembers)
      .csMemberNames()
  val baseSpellings: KotlinSpellings = KotlinSpellings.ofClass(cls)
  emitMemberNameCollisions(name, "sealed class $name", cls, baseNames, baseSpellings, logger)
  qualifiedName?.let { qualified ->
    memberRegistry?.register(
      CsMemberRegistry.Entry(
        qualified, name, "sealed class $name", cls,
        keptBase = superClassDeclaration?.qualifiedName?.asString(), baseNames, baseSpellings,
        nestedTypes = cls.csNestedTypeNames(),
      ),
    )
  }

  val subclasses: List<CirSealedSubclass> = cls.getSealedSubclasses()
    .map { subclass ->
      val subName: String = subclass.simpleName.asString()
      val subPrefix: String = "${prefix}_${subName.lowercase()}"
      // ADR-157: an enum arm is a BOX. Its own members belong to `{Enum}Extensions` (ADR-006) and
      // the enum keeps being declared, exactly once, as a C# `enum`; the arm carries the box
      // constructor and the `Value` getter, both off the same plans and the same projections every
      // other arm member uses.
      if (subclass.isEnumArm()) {
        return@map enumArmSubclass(
          cls, subclass, subPrefix, callableCatalog, tracker, context, expects,
        ).genericArmOf(cls, subclass, baseTypeParameters, classifier, logger, context).let { box ->
          // ADR-175: the box owns no member, but it inherits the base's async ones, so it takes the
          // base-owned scope's cleanup and the `DisposeAsync` body like every other arm.
          if (baseScopeOwner == null) box
          else box.copy(
            hasSuspendMethods = true,
            ownsScope = false,
            overridesDisposeAsync = armsOverrideDisposeAsync,
          )
        }
      }
      val isDataClass: Boolean = subclass.modifiers.contains(Modifier.DATA)
      val isNested: Boolean =
        subclass.parentDeclaration?.qualifiedName?.asString() == cls.qualifiedName?.asString()
      // ADR-009 amendment (2026-09-11): an `open` arm is extensible, which is what unlocks both
      // `public class` in the renderer and `virtual` on its own open members below. An `abstract`
      // arm is extensible too, and renders `public abstract class` over its backing wrapper.
      val isOpenArm: Boolean = subclass.isForwardExtensible()
      val backingName: String? = subclass.abstractBackingName()

      // ADR-101 amendment (2026-09-27): the arm lists its own exported interfaces after the sealed
      // base, by the ordinary class's rule, so `arm is IFoo` holds. Everything the sealed type
      // already is (the sealed interface itself included, which has no `I` form in C#) stays on
      // the base; an unexported one is named and dropped, and its members re-home onto the arm
      // (`isForwardPlannableMemberOf(subclass, superClass = sealed)` in both planners).
      val armInterfaces: List<String> = forwardInterfaceList(
        subclass,
        "$name.$subName",
        carried = cls.forwardSupertypeNames(),
        exportedTypes,
        classifier,
        logger,
      )

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
            val projected: CirProperty = ForwardCirPropertyProjection.classProperty(
              planned,
              // ADR-009 amendment (2026-09-11): gated on the arm being open. An `open val` on a
              // final arm is effectively final in Kotlin (nothing can extend it), and `virtual`
              // inside a `public sealed class` is CS0549.
              isVirtual = isOpenArm && prop.isOpenForOverrideOn(subclass),
              // An abstract arm declares its abstract property `abstract`, as an ordinary abstract
              // class does, and its backing wrapper overrides it over the same export.
              isAbstract = backingName != null && prop.isAbstract(),
              // ADR-168 on an arm (2026-09-27): an `override var` over the sealed base's `open val`
              // is get-only in public (CS0546), and the interface `var` it also implements takes
              // its setter as an explicit `IFoo.X` member, which the arm's base list now allows.
              explicitSetterInterfaces =
                subclass.explicitSetterInterfaceSpellings(planned, classifier),
            )
            // ADR-101 amendment (2026-09-27): an override of the kept exported base's own member
            // overrides it in C# too, through the sealed base's base list (CS0114 otherwise).
            val overridesKeptBase: Boolean = prop.overridesKeptBaseOf(cls, superClassDeclaration) &&
                baseProperties.none { it.name == projected.name }
            if (overridesKeptBase) {
              return@mapNotNull projected.copy(isOverride = true, isVirtual = false)
            }
            return@mapNotNull projected.againstSealedBase(baseProperties)
          }

          // Issue #121: same gate as the ordinary-class arm above. The planner declined, and a
          // marked declaration must reach neither artifact.
          if (prop.isOptInRefused(classifier.exportMarkers)) return@mapNotNull null

          // ADR-124: the arm's Flow/StateFlow properties, off the same `flowProperty` an ordinary
          // class calls, so the externs, the element spelling and the getter body are an ordinary
          // class's. The arm's own C# name goes in, because the getter bakes
          // `ObjectDisposedException(nameof(...))` and the receiver is the arm.
          if (prop.type.resolve().expandAliases().isForwardFlowType()) {
            // ADR-175: a flow property the sealed base projects is the base's; the arm inherits
            // it (the `forwardArmFlowProperties` rule the Kotlin half applies).
            if (subclass.forwardArmMemberProjectedByBase(prop, classifier)) return@mapNotNull null
            return@mapNotNull flowProperty(prop, subName, context, classifier, tracker)
          }

          // Residual legacy route: a lambda-typed property, whose Kotlin half is still
          // hand-spelled in `SealedClassExports` too. It swallows the error slot (`out _`) until
          // lambda properties migrate for ordinary classes.
          val propTypeResolved: KSType = prop.type.resolve().expandAliases()
          if (!prop.carriesLegacyLambdaProperty(ForwardLambdaPropertyCarrier.SEALED_ARM)) {
            return@mapNotNull null
          }
          val lambdaArity: Int = propTypeResolved.arguments.size - 1
          tracker.lambdaArities.add(lambdaArity)
          // Issue #111, the sealed-subclass copy of the same rule as the ordinary-class arm.
          // ADR-171: a value class with a box/unbox pair is nameable here too.
          val nameableTypes: Set<String> = exportedTypes + callableCatalog.boxedValueClasses
          val unnameableTypeArgument: CsTypeArgument.Unnameable? =
            csTypeArguments(propTypeResolved.arguments, nameableTypes, context, classifier)
          if (unnameableTypeArgument != null) {
            ForwardDiagnosticSink.emit(
              listOf(
                lambdaTypeArgumentDiagnostic(
                  kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
                  symbol = prop,
                  declaration = "$subName.$propName",
                  typeArgument = unnameableTypeArgument.typeArgument,
                  // ADR-009: the arm is its own C# declaration, so the hole is on the arm.
                  owner = subclass.forwardDiagnosticOwner(),
                  member = propName,
                ),
              ),
              logger,
            )
            return@mapNotNull null
          }
          val lambdaTypeArgs: List<String> =
            csTypeArgumentNames(propTypeResolved.arguments, nameableTypes, context, classifier)
          val lambdaCsType: String = csLambdaType(lambdaTypeArgs)
          CirProperty(
            name = prop.csharpMemberName(),
            type = lambdaCsType,
            nativeReturnType = "IntPtr",
            nativeName = propName.asCSymbol(),
            getter = "new $lambdaCsType(Native_Get_${propName.asCSymbol()}(_handle, out _))",
            setter = null,
            hasSyncErrorOut = true,
          )
        }
        .toList()

      // ADR-116: the method half of ADR-111. The arm's declared member functions come off the same
      // catalog an ordinary class reads (`classMethods`), projected by the same `classMethod`, so
      // the error slot, the overload numbering and the wire types agree with the Kotlin half by
      // construction. `isOverride` rides the plan too: true only for an override of the sealed
      // base's kept exported base class (ADR-101 amendment 2026-09-27), which C# inherits through
      // the base list; `isVirtual` rides the plan, which computes it from the arm being `open` the
      // same way `classEntries` computes an ordinary class's (ADR-009 amendment 2026-09-11).
      val methodPlans: List<ForwardCallablePlan> =
        subQualifiedName?.let { callableCatalog.classMethods(it) } ?: emptyList()
      val methods: List<CirMethod> = methodPlans.map { plan ->
        tracker.trackPlan(plan)
        val projected: CirMethod = ForwardCirPlanProjection.classMethod(
          plan = plan,
          nativePrefix = subPrefix,
          isOverride = plan.publicSignature.isOverride,
          isVirtual = plan.publicSignature.isVirtual,
        )
        // ADR-116 amendment (2026-09-11): `override` when the base now carries the very same C#
        // signature. Compared on the projected signature rather than on Kotlin's `override`
        // keyword, because what C# needs is a base member with a matching shape: an arm can
        // override something the base's own plan declined, and then there is nothing to override.
        projected.againstSealedBase(baseMethods)
          // An abstract arm's abstract member: declared `abstract` here, implemented by the backing
          // wrapper below over the same export.
          .let { method -> if (plan.publicSignature.isAbstract) method.asAbstract() else method }
      }
      // ADR-118: the arm's `suspend` members ride the legacy suspend route under the arm's own
      // export prefix, projected by the same `suspendMembers` an ordinary class calls, so the
      // arm's externs and bodies are an ordinary class's. The selector is the one the Kotlin
      // export builder and its gate read (ADR-159), so the two halves cannot disagree: the arm's
      // own surface (ADR-101 amendment 2026-09-27), the ADR-114/119 refusals, the synthetic-member
      // filter. A base `open suspend fun` no arm overrides still belongs to no arm.
      val armSuspendMethods: List<KSFunctionDeclaration> =
        subclass.forwardSuspendRouteMethods(classifier, superClass = null, isArm = true)
      val asyncMembers: List<CirMember> = suspendMembers(
        suspendMethods = armSuspendMethods,
        prefix = subPrefix,
        libraryName = libraryName,
        classifier = classifier,
        tracker = tracker,
        callableCatalog = callableCatalog,
        context = context,
        expects = expects,
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

      // ADR-116 amendment (2026-09-11): the arm's per-call lambda-parameter methods (ADR-036),
      // through the same `translateCallbackMethod` an ordinary class's go through, so the thunk,
      // the delegate registration and the extern shape are an ordinary class's. The entry point is
      // composed from `subPrefix`, which is the prefix the Kotlin export loop passes to
      // `addLambdaParamMethodExport`; `forwardArmLambdaMethods` is the selector both read.
      val perCallCallbackMembers: List<CirMember> = subclass.forwardArmLambdaMethods(classifier)
        .mapNotNull { method ->
          translateCallbackMethod(method, libraryName, subPrefix, exportedTypes, tracker)
        }

      // ADR-116 amendment (2026-09-13): and the pair routes on the arm, through the same two
      // translators an ordinary class's pairs go through, so the delegate, the thunk, the
      // `NugetSubscription` and the extern shapes are an ordinary class's. Entry points compose
      // from `subPrefix`, the prefix the Kotlin export loop passes to the matching builders.
      val storedCallbackMembers: List<CirMember> =
        subclass.forwardArmStoredCallbackPairs(classifier).mapNotNull { (addMethod, removeMethod) ->
          translateStoredCallbackMethod(
            addMethod, removeMethod, libraryName, subPrefix, subName, exportedTypes, tracker,
            context,
          )
        }

      val interfaceBridgeMembers: List<CirMember> =
        subclass.forwardArmInterfaceBridgePairs(classifier)
          .mapNotNull { (addMethod, removeMethod) ->
            // The arm's own C# name, not the base's: it lands in the subscribe body's
            // `ObjectDisposedException(nameof(...))`, where the base's name compiles (the arm is
            // nested inside it) and misnames the owner of the handle that was disposed.
            translateInterfaceBridgeMethod(
              addMethod, removeMethod, libraryName, subPrefix, subName, tracker, classifier,
              context,
            )
          }

      val callbackMembers: List<CirMember> =
        perCallCallbackMembers + storedCallbackMembers + interfaceBridgeMembers

      // ADR-148: the arm's own public constructors, off the same catalog query and the same
      // projection `translateClass` runs for an ordinary class, so the extern suffix, the error
      // slot and the ADR-091 omitting overloads are an ordinary class's. An `object` arm plans
      // none (the planner only collects kind `CLASS`), so the query answers empty and the arm
      // renders exactly as it shipped.
      val constructorPlans: List<ForwardCallablePlan> =
        subQualifiedName?.let { callableCatalog.constructors(it) } ?: emptyList()
      val armConstructors: List<CirConstructor> = constructorPlans.map { plan ->
        tracker.trackPlan(plan)
        val suffix: String = plan.invocation.symbol.substringAfterLast('.').removePrefix("<init>")
        ForwardCirPlanProjection.constructor(plan, suffix)
      }
      // ADR-148: the same gate `translateClass` applies, one level in. An arm whose every public
      // constructor was refused is kept (a Kotlin factory still hands one over) and says so, in
      // the build log and in the arm's own `<remarks>`, instead of dropping silently.
      val armNoPublicConstructor: Boolean = subclass.classKind == ClassKind.CLASS &&
          !subclass.modifiers.contains(Modifier.ABSTRACT) &&
          // A sealed intermediate arm is never constructed, in Kotlin or in C#.
          !subclass.modifiers.contains(Modifier.SEALED) &&
          armConstructors.isEmpty() &&
          subclass.hasPublicConstructor()
      val armRemarks: String? = if (armNoPublicConstructor) {
        noPublicConstructorRemark(
          subName,
          warnNoPublicConstructor(subclass, "$name.$subName", callableCatalog, logger),
        )
      } else {
        null
      }

      // ADR-034's collision guard, which the sealed route never ran: two arm methods whose C#
      // signatures agree (`set(x: Foo)` / `set(x: Foo?)`) are CS0111 in the generated file.
      // ADR-159: below the async and Flow projection, so two `suspend` overloads that render one
      // `PlayAsync(string)` are named here rather than reaching the generated file.
      // ADR-162: an arm INHERITS the base's `Dispose()`, so an authored `dispose()` on the arm is
      // the same CS0111 pair one level down.
      emitCsharpSignatureCollisions(
        methods + (asyncMembers + flowMembers).filterIsInstance<CirMethod>(),
        "$name.$subName",
        subclass,
        logger,
        HANDLE_RESERVED_SIGNATURES,
      )

      // ADR-110 amendment (ROADMAP line 32): the arm is one C# type holding every route's members.
      // Registered with the base as its kept base, so a method hiding a base property (and a class
      // extending an open arm) reaches the inherited CS0108 post-pass.
      val armNames: List<CsMemberName> =
        (properties + methods + asyncMembers + flowMembers + callbackMembers).csMemberNames()
      val armSpellings: KotlinSpellings = KotlinSpellings.ofClass(subclass)
      emitMemberNameCollisions(
        "$name.$subName", "sealed arm $subName", subclass, armNames, armSpellings, logger,
      )
      subQualifiedName?.let { qualified ->
        memberRegistry?.register(
          CsMemberRegistry.Entry(
            qualified, subName, "sealed arm $subName", subclass, keptBase = qualifiedName,
            armNames, armSpellings, nestedTypes = subclass.csNestedTypeNames(),
          ),
        )
      }

      CirSealedSubclass(
        doc = subclass.forwardKdoc(expects)?.toCirDoc(),
        name = subName,
        nativePrefix = subPrefix,
        properties = properties,
        constructors = armConstructors,
        remarks = listOfNotNull(armRemarks),
        methods = methods,
        asyncMembers = asyncMembers,
        flowMembers = flowMembers,
        callbackMembers = callbackMembers,
        // Derived from what projected, not from a `getAllFunctions()` scan: a base-declared or
        // ADR-114 refused suspend member would otherwise hand the arm a scope, `IAsyncDisposable`
        // and `DisposeAsync` with no async method on it to use them. ADR-124: a flow member needs
        // the same scope, so one boolean covers both routes and the arm cannot emit two.
        // ADR-175: or the sealed base (or a kept base above it) owns the one scope, in which case
        // the arm declares none of its own: it cleans the inherited one up in `Dispose()` and
        // supplies the abstract owner's `DisposeAsync` body.
        hasSuspendMethods = baseScopeOwner != null || asyncMembers.isNotEmpty() ||
            flowMembers.isNotEmpty() || properties.any { property -> property.isFlow },
        ownsScope = baseScopeOwner == null && (asyncMembers.isNotEmpty() ||
            flowMembers.isNotEmpty() || properties.any { property -> property.isFlow }),
        overridesDisposeAsync = armsOverrideDisposeAsync,
        isDataClass = isDataClass,
        isNested = isNested,
        isOpen = isOpenArm,
        backingName = backingName,
        interfaces = armInterfaces,
        // ADR-134: the arm is an owner in its own right (`Purr.On.Trace`).
        nestedDeclarations = nestedOf(subclass),
      ).genericArmOf(cls, subclass, baseTypeParameters, classifier, logger, context)
    }
    .toList()

  // ADR-199: an intermediate sealed arm is a generic sealed hierarchy of its own, on this holder.
  val intermediates: List<CirSealedClass> = if (baseTypeParameters.isEmpty()) {
    emptyList()
  } else {
    cls.getSealedSubclasses()
      .filter { arm -> arm.isIntermediateGenericSealedArm() }
      .map { arm ->
        translateSealedClass(
          arm, context, tracker, callableCatalog, classifier, exportedTypes, logger, nestedOf,
          expects, memberRegistry, parentTypeParameters = baseTypeParameters,
        )
      }
      .toList()
  }

  return CirSealedClass(
    typeParameters = baseTypeParameters,
    intermediates = intermediates,
    scopedName = cls.nestedCsName(),
    doc = cls.forwardKdoc(expects)?.toCirDoc(),
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    subclasses = subclasses,
    superClass = superClass,
    interfaces = interfaces,
    properties = baseProperties + baseFlowProperties,
    methods = baseMethods,
    asyncMembers = baseAsyncMembers,
    flowMembers = baseFlowMembers,
    ownsScope = baseOwnsScope,
    // ADR-134: a type declared beside the arms, in the block ADR-009 owns. An ADR-112 eligible
    // sealed interface arrives here too, which is why its children must never be routed to the
    // interface slot instead: nothing would read them.
    nestedDeclarations = nestedOf(cls),
  )
}

/**
 * ADR-111/ADR-116 amendment (2026-09-11): the arm-side modifier for a property the sealed base now
 * also carries, decided on the *projected C# shape* rather than on Kotlin's `override` keyword.
 *
 * - Same name, same C# type: a plain `override`.
 * - Same name, different C# type: Kotlin allows a covariant override (`Int` narrowing `Int?`), C#
 *   does not (CS1715). The arm hides the base member with `new` and keeps its own export, so a
 *   consumer holding the arm reads the narrow type and one holding the base reads the wide one.
 * - No such base member: unchanged, whatever `isVirtual` the arm's own openness earned.
 */
private fun CirProperty.againstSealedBase(baseProperties: List<CirProperty>): CirProperty {
  val onBase: CirProperty = baseProperties.firstOrNull { it.name == name } ?: return this
  if (onBase.type == type) return copy(isOverride = true, isVirtual = false)
  return copy(isNew = true, isOverride = false, isVirtual = false)
}

/**
 * The method-side twin of [CirProperty.againstSealedBase], on the same three rules. C# decides
 * *hiding* on name and parameter types alone, so a base member with a matching signature but a
 * different return type is hidden with `new` (an `override` there is CS0508 for a value type, and
 * leaving the modifier off is the CS0108 warning `GeneratedBindingsCheck` compiles as an error).
 */
private fun CirMethod.againstSealedBase(baseMethods: List<CirMethod>): CirMethod {
  val parameterTypes: List<String> = positionalParameterTypes()
  val onBase: CirMethod = baseMethods.firstOrNull { candidate ->
    // ADR-197: a method's generic arity is part of its C# signature, and its own type parameters
    // match by position, not by name: `override fun <U> carry(item: U): U` overrides the base's
    // `fun <T> carry(item: T): T`, which C# allows under the arm's own names.
    candidate.name == name && candidate.typeParameters.size == typeParameters.size &&
        candidate.positionalParameterTypes() == parameterTypes &&
        // An override base restates no constraints, so only a declaring one is compared.
        (candidate.isOverride || candidate.positionalConstraints() == positionalConstraints())
  } ?: return this
  if (onBase.positional(onBase.returnType) == positional(returnType)) {
    return copy(
      isOverride = true,
      isVirtual = false,
      typeParameters = overridingTypeParameters(),
    )
  }
  return copy(isNew = true, isOverride = false, isVirtual = false)
}

/**
 * ADR-197: an overriding method's own type parameters, by the rule a planned `override` follows
 * (`ForwardCirPlanProjection`): no restated constraint (CS0460), and `where T : default` for a
 * `T` the signature spells `T?` (CS0115 otherwise). Read off the rendered C# types, where an
 * admitted member's `T` only ever appears bare or as `T?`.
 */
private fun CirMethod.positionalParameterTypes(): List<String> =
  parameters.map { parameter -> positional(parameter.type) }

/** The declared constraints, positional; an arm's are compared before the override drops them. */
private fun CirMethod.positionalConstraints(): List<List<String>> =
  typeParameters.map { parameter -> parameter.bounds.map { bound -> positional(bound) } }

/**
 * ADR-197: [type] with each of this method's own type parameters replaced by its position
 * (`U?` on `Carry<U>` reads `!!0?`), so two declarations that name them differently compare equal.
 * Unchanged for a method that declares none.
 */
private fun CirMethod.positional(type: String): String =
  typeParameters.foldIndexed(type) { index, spelled, parameter ->
    val token: Regex = Regex("(?<![A-Za-z0-9_])${Regex.escape(parameter.name)}(?![A-Za-z0-9_])")
    spelled.replace(token, "!!$index")
  }

internal fun CirMethod.overridingTypeParameters(): List<CirTypeParameter> {
  val spelled: List<String> = parameters.map { parameter -> parameter.type } + returnType
  return typeParameters.map { parameter ->
    val nullable: Boolean = spelled.any { type -> type == "${parameter.name}?" }
    CirTypeParameter(parameter.name, if (nullable) listOf("default") else emptyList())
  }
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
/**
 * ADR-162: the renderer-owned signatures of a generated handle container, in
 * [emitCsharpSignatureCollisions]'s encoding (member name, then rendered parameter types).
 *
 * Only `Dispose()` for now, because it is the only renderer-owned *member* an author can collide
 * with by accident (`FromHandle`, `Handle` and the `Native_*` externs are either static factories
 * ADR-009 already name-checks or `internal`). Kept as a set so the next one is a line, not a
 * refactor.
 */
internal val HANDLE_RESERVED_SIGNATURES: Set<List<String>> = setOf(listOf("Dispose"))

internal fun emitCsharpSignatureCollisions(
  methods: List<CirMethod>,
  container: String,
  symbol: KSNode?,
  logger: KSPLogger,
  reservedSignatures: Set<List<String>> = emptySet(),
) = emitCsharpSignatureCollisionsOf(
  methods.map { method -> method.name to method.parameters },
  container,
  symbol,
  logger,
  reservedSignatures,
)

/**
 * The guard over bare (member name, parameters) pairs, so a container whose members are not
 * [CirMethod]s can run it too. ADR-090 amendment (2026-09-26): the generated `IFoo` declares
 * [CirInterfaceMethod]s, and an interface `tag(s: String)` / `tag(s: String?)` pair emitted CS0111
 * on `IFoo` with no KSP error at all.
 */
internal fun emitCsharpSignatureCollisionsOf(
  methods: List<Pair<String, List<CirParameter>>>,
  container: String,
  symbol: KSNode?,
  logger: KSPLogger,
  /**
   * ADR-162 (ROADMAP line 87): signatures the *renderer* puts on this container, which are
   * therefore not in [methods] and cannot collide with each other, only with an authored member.
   * A handle container declares `IDisposable.Dispose()`, so `class Closer { fun dispose() {} }`
   * is CS0111 exactly like any other duplicate pair — but the generated half is not a
   * `CirMethod`, so it used to sail past this guard and surface much later as the generic
   * `ERROR_C_ENTRY_POINT_COLLISION` (two owners of `closer_dispose`), which reads as an ABI
   * accident rather than as the C# rule it is.
   *
   * Each entry is a signature in this function's own encoding: the member name followed by its
   * rendered parameter types.
   */
  reservedSignatures: Set<List<String>> = emptySet(),
) {
  val authored: List<List<String>> = methods
    .map { method ->
      listOf(method.first) + method.second.map { param ->
        val stripReferenceNullability: Boolean = param.isReferenceType && param.type.endsWith("?")
        if (stripReferenceNullability) param.type.dropLast(1) else param.type
      }
    }

  // A renderer-owned signature an authored member claims. Reported with the member's own wording,
  // because the remedy is different from the two-overloads case: there is no second overload of
  // the author's to rename, and `close()` already binds as `Close()` beside `Dispose()`.
  authored
    .filter { signature -> signature in reservedSignatures }
    .distinct()
    .forEach { signature ->
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
            symbol = symbol,
            declaration = "$container.${signature.first()}",
            reason = "it renders as " +
                "`${signature.first()}(${signature.drop(1).joinToString(", ")})`" +
                ", which collides with the `${signature.first()}()` every generated handle class " +
                "declares for `IDisposable`; C# cannot declare two members with the same " +
                "signature (ADR-034)",
            hint = "rename the Kotlin member: `close()` binds as `Close()` beside the generated " +
                "`Dispose()`, and the generated one already frees the handle",
            owner = null,
          ),
        ),
        logger,
      )
    }

  authored
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
            // ERROR_*: the build fails before anything generated is read.
            owner = null,
          ),
        ),
        logger,
      )
    }
}

/** A projected C# method with the Kotlin declaration behind it, as the author wrote it. */
internal data class SpelledMethod(
  val method: CirMethod,
  val kotlin: String,
  val node: KSNode,
)

/** ADR-006 amendment: a rendered C# property name and the Kotlin declaration behind it. */
internal data class SpelledProperty(
  val name: String,
  val kotlin: String,
  val node: KSNode,
)

/**
 * ADR-006 amendment / ADR-034: the `{Enum}Extensions` partial class is declared from two routes,
 * the enum's own member properties ([members]) and the extensions over the enum ([extensions]).
 * Each route's own overload guard sees only its half, so a member `val grooming` and an extension
 * `fun Coat.grooming()` (both `Grooming(this Coat …)`) used to reach the consumer as CS0111. Only
 * a signature at least one enum member claims is reported here: a clash between two extensions
 * is already the extension-function loop's own report.
 */
internal fun emitEnumExtensionSignatureCollisions(
  enumName: String,
  container: String,
  members: List<SpelledMethod>,
  extensions: List<SpelledMethod>,
  logger: KSPLogger,
  // ADR-006 amendment: the companion `val`/`var`s, static properties of the same class. A property
  // collides with any member of its name (CS0102), so it is checked by name, not by signature.
  properties: List<SpelledProperty> = emptyList(),
) {
  (properties.map { it.name to (it.kotlin to it.node) } +
      (members + extensions).map { it.method.name to (it.kotlin to it.node) })
    .groupBy({ it.first }, { it.second })
    .filterKeys { name -> properties.any { it.name == name } }
    .filterValues { group -> group.size > 1 }
    .forEach { (name, group) ->
      val declarations: String = group.map { it.first }.distinct().joinToString(" and ")
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
            symbol = group.first().second,
            declaration = "$container.$name",
            reason = "$declarations on enum class $enumName both render the member `$name` in " +
                "$container, and C# cannot declare a property beside another member of the same " +
                "name (CS0102); an enum companion `val`/`var` renders a static property there " +
                "(ADR-006)",
            hint = "rename one of them",
            owner = null,
          ),
        ),
        logger,
      )
    }

  fun SpelledMethod.signature(): List<String> =
    listOf(method.name) + method.parameters.map { param ->
      val stripReferenceNullability: Boolean = param.isReferenceType && param.type.endsWith("?")
      if (stripReferenceNullability) param.type.dropLast(1) else param.type
    }

  (members + extensions)
    .groupBy { spelled -> spelled.signature() }
    .filterValues { group -> group.size > 1 && group.any { it in members } }
    .forEach { (signature, group) ->
      val declarations: String = group.map { it.kotlin }.distinct().joinToString(" and ")
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
            symbol = group.first { it in members }.node,
            declaration = "$container.${signature.first()}",
            reason = "$declarations on enum class $enumName both render " +
                "`${signature.first()}(${signature.drop(1).joinToString(", ")})` in $container, " +
                "and C# cannot declare two methods with the same signature (ADR-034); an enum " +
                "member property renders its bare name there, and an enum member or companion " +
                "function its PascalCase name (ADR-006)",
            hint = "rename one of them",
            owner = null,
          ),
        ),
        logger,
      )
    }
}

internal fun translateObject(
  obj: KSClassDeclaration,
  libraryName: String,
  /** ADR-163: the one symbol table. */
  symbols: ForwardSymbolTable,
  callableCatalog: ForwardCallablePlanCatalog,
  tracker: CollectionHelperTracker,
  logger: KSPLogger,
  // ADR-150: the expect index. A bare `forwardKdoc()` here cannot reach the `expect` half,
  // so a documented `expect` declaration of this family rendered with no summary at all.
  expects: ExpectIndex = ExpectIndex(),
): CirObject {
  val name: String = obj.simpleName.asString()
  val prefix: String = obj.nativePrefix(symbols)

  // Object methods are static (no receiver handle), so they route through the same
  // shape as top-level functions (CirFunctionTranslator's static template) rather than
  // the class instance-method loop, which hardcodes _handle. See the comment above
  // this function's call site / ADR-060 cells 1 & 25.
  // ADR-095: members come off the catalog (per-object overload numbering; see `addObjectExports`).
  // This also picks up the planner's `parentDeclaration == obj` filter, which this walk never had.
  // The Kotlin spelling behind each rendered C# member name, so the CS0102 guard below can name
  // the two declarations the author has to choose between rather than only the C# name they share.
  val kotlinSpellings = KotlinSpellings()

  val methods: List<CirMember> = callableCatalog
    .objectMethods(obj.qualifiedName?.asString() ?: name)
    .flatMap { planned ->
      tracker.trackPlan(planned)
      val members: List<CirMember> = ForwardCirPlanProjection.static(planned, libraryName)
      val kotlinName: String = planned.invocation.member
        ?: planned.invocation.symbol.substringAfterLast('.')
      members.filterIsInstance<CirMethod>()
        .forEach { kotlinSpellings.record(it.name, KotlinSpelling("fun", kotlinName)) }
      members
    }

  // ROADMAP Phase 4: a `const val` on an object renders as a C# `const`, exactly as a companion's
  // does. Without this an object declaring nothing but consts rendered a completely empty static
  // class.
  val consts: List<CirMember> = obj.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.modifiers.contains(Modifier.CONST) }
    .mapNotNull { prop ->
      translateConstProperty(prop, logger)
        ?.also {
          kotlinSpellings.record(it.name, KotlinSpelling("const val", prop.simpleName.asString()))
        }
    }
    .toList()

  // ROADMAP Phase 4: the object's own planned properties, projected off the SAME plan the Kotlin
  // `@CName` half reads (`addObjectExports`). `CirObject.methods` is a `List<CirMember>`, so the
  // `CirDllImport` + `CirProperty` nodes `staticProperty` returns need no CIR model change: the
  // renderer, the ABI contract and the legacy-route scan already read them from this list.
  val properties: List<CirMember> = obj.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { !it.modifiers.contains(Modifier.CONST) }
    .flatMap { prop ->
      val symbol: String = "${obj.qualifiedName?.asString() ?: name}.${prop.simpleName.asString()}"
      val planned: ForwardPropertyPlan? = callableCatalog.propertyFor(symbol)
      if (planned != null) {
        tracker.trackProperty(planned)
        val keyword: String = if (planned.setter != null) "var" else "val"
        kotlinSpellings.record(planned.publicName, KotlinSpelling(keyword, planned.kotlinName))
        ForwardCirPropertyProjection.staticProperty(planned, libraryName)
      } else {
        emptyList()
      }
    }
    .toList()

  val members: List<CirMember> = consts + properties + methods

  emitCsharpSignatureCollisions(
    methods = members.filterIsInstance<CirMethod>(),
    container = name,
    symbol = obj,
    logger = logger,
  )
  emitObjectNameCollisions(members, name, obj, kotlinSpellings, logger)
  emitObjectDroppedSupertypes(obj, name, logger)

  return CirObject(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    methods = members,
    doc = obj.forwardKdoc(expects)?.toCirDoc(),
  )
}

/**
 * ROADMAP Phase 4: an `object` renders as a C# **static class**, which can neither extend a class
 * nor implement an interface, so every supertype the author declared is silently dropped from the
 * generated declaration. `object TreatPantry : Stockroom("kitchen"), Labelled` used to render a
 * plain `public static class TreatPantry` with nothing said about either name.
 *
 * Reuses the class route's [ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE] kind and sink (one
 * WARNING per dropped supertype, owner = the object). The reason differs from the class route's --
 * here the supertype is usually *exported* and still cannot be named, because the C# shape is a
 * static class, not because the type is missing -- but what is lost is the same thing that kind
 * exists for: the `is`/`as` relation, while the members stay reachable.
 *
 * A sealed `object` arm is excluded: the ADR-009 route declares it as a real `sealed class`
 * extending its base, so its supertype is not dropped at all.
 */
private fun emitObjectDroppedSupertypes(
  obj: KSClassDeclaration,
  name: String,
  logger: KSPLogger,
) {
  if (obj.isSealedSubclass()) return
  val supertypes: List<KSClassDeclaration> = obj.superTypes
    .map { reference -> reference.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .filter { supertype -> supertype.qualifiedName?.asString() != "kotlin.Any" }
    .toList()
  if (supertypes.isEmpty()) return
  ForwardDiagnosticSink.emit(
    supertypes.map { supertype ->
      val simpleName: String = supertype.simpleName.asString()
      val supertypeName: String = supertype.qualifiedName?.asString() ?: simpleName
      val relation: String =
        if (supertype.classKind == ClassKind.INTERFACE) "implement" else "extend"
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE,
        symbol = obj,
        declaration = "$name : $simpleName",
        reason = "object `$name` is generated as the C# static class `$name`, which cannot " +
            "$relation '$supertypeName', so that supertype is dropped from the generated " +
            "declaration; $name's own and inherited public members are still bound, as statics " +
            "on `$name`",
        hint = "nothing callable is lost, but C# sees no relation between $name and " +
            "$simpleName, so `is`/`as` against $simpleName and any dispatch through it are gone; " +
            "declare a class with a private constructor and a singleton instance instead if the " +
            "consumer needs to pass it as a $simpleName",
        // Issue #249: a whole-type skip stays ownerless, like the class route's dropped supertype:
        // no generated member is missing, so there is no paragraph to attach.
        owner = null,
      )
    },
    logger,
  )
}

/**
 * ADR-110's CS0102 guard at its second site, the `object` static class (ROADMAP Phase 4).
 *
 * `object Jar { val count: Int; fun count(): Int }` is legal Kotlin and renders `Count { get; }`
 * beside `Count()` on one static class, which C# refuses. Fatal rather than a skip, for ADR-110's
 * own reason: a planned member is projected into BOTH halves and ADR-055's contract requires it in
 * each, so it cannot be exported from Kotlin and quietly dropped from the C#.
 *
 * A library with this shape built before this guard existed -- the property was simply absent --
 * and fails after it. That is the knowing cost recorded in the ADR-110 amendment.
 */
private fun emitObjectNameCollisions(
  members: List<CirMember>,
  objectName: String,
  obj: KSClassDeclaration,
  // Every Kotlin declaration behind a rendered C# name, in declaration order. The message names
  // them: `const val TREAT_COUNT` and `fun treatCount()` both render `TreatCount`, and the C# name
  // alone is not something the author can search their own source for.
  kotlinSpellings: KotlinSpellings,
  logger: KSPLogger,
) {
  // A `const` collides with a method on exactly the same CS0102 grounds a property does, and two
  // consts meeting after `kotlinConstantToPascalCase` (`MAX_RETRIES` + `maxRetries`) on the same
  // grounds as each other.
  emitMemberNameCollisions(
    container = objectName,
    ownerPhrase = "object $objectName",
    symbol = obj,
    members = members.csMemberNames(),
    spellings = kotlinSpellings,
    logger = logger,
    reason = { collision ->
      val what: String =
        if (collision.methods.isEmpty()) "two members" else "a property and a method"
      "object $objectName declares ${collision.declarations()}, which both render the C# name " +
          "'${collision.name}', and C# cannot declare $what with one name on the static class " +
          "an object becomes (CS0102)"
    },
    hint = {
      "rename one of them on object $objectName; a property, a `const val` and a function all " +
          "render PascalCase in C# (ADR-110)"
    },
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
  val csPropName: String = prop.csharpMemberName()

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
      name = func.csharpMemberName(),
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
        append("                return new $kotlinReturnType(nativeResult, out _);")
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
  // ADR-113 (2026-09-26): an interface whose only collection use is on its own members must still
  // pull in `System.Collections.Generic` (CS0246 on `IReadOnlyList<>` under no implicit usings).
  tracker: CollectionHelperTracker,
  // ADR-150: the expect index. A bare `forwardKdoc()` here cannot reach the `expect` half,
  // so a documented `expect` declaration of this family rendered with no summary at all.
  expects: ExpectIndex = ExpectIndex(),
  // Interface super-interfaces: the export set decides which direct supers the base list keeps
  // (`keepsSupertype`), and the classifier spells their type arguments.
  exportedTypes: Set<String>,
  classifier: ForwardBridgeTypeClassifier,
  // ADR-174: the async members' builders need the library and the context; null (a unit caller)
  // declares none, which is the pre-ADR-174 shape.
  context: NugetContext? = null,
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

  // Interface super-interfaces: `IDerived : IBase` inherits rather than flattens. The catalog plans
  // every member (own and inherited) for the ADR-040 backing class; the declaration keeps only the
  // DECLARED placement: lexically declared here or in a re-homed unexported super, and not an
  // identical-signature override of a kept super's member (CS0108 under warnings-as-errors).
  val hierarchy = ForwardInterfaceHierarchy(iface, exportedTypes)
  hierarchy.rehomed.forEach { dropped ->
    keepsSupertype(iface, name, dropped, SupertypeKind.SUPER_INTERFACE, exportedTypes, logger)
  }
  val superInterfaces: List<String> = hierarchy.keptSupers.mapNotNull { type ->
    forwardSuperInterfaceSpelling(type, classifier, from = iface)
      ?: run {
        emitUnspellableSuperInterface(iface, interfaceName.removePrefix("I"), type, logger)
        null
      }
  }
  val methodPlacements: Map<String, ForwardInterfaceMemberPlacement> =
    iface.interfaceMethodSymbols().associate { (symbol, method) ->
      symbol to hierarchy.placement(method)
    }
  val propertyPlacements: Map<String, ForwardInterfaceMemberPlacement> = iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .associate { prop -> "$qualified.${prop.simpleName.asString()}" to hierarchy.placement(prop) }
  emitCovariantOverrideSkips(interfaceName, iface, hierarchy, logger)

  // Read off the catalog rather than re-deriving a plan key per `getAllProperties()` /
  // `getAllFunctions()` entry: the planner owns member ordering and naming, and a declaration walk
  // re-derives the wrong plan as soon as two declared members share a simple name (ADR-090).
  val propertyPlans: List<ForwardPropertyPlan> = callableCatalog.propertyPlans
    .filter { plan -> plan.symbol.substringBeforeLast('.') == qualified }
    .filter { plan -> declared(propertyPlacements[plan.symbol]) }
  val plannedProperties: List<CirInterfaceProperty> = propertyPlans.map { plan ->
    tracker.trackProperty(plan)
    // ROADMAP line 28: the setter comes off the same plan the getter does, so ADR-075's
    // independence holds on `IFoo` too (`var err: Throwable?` stays `{ get; }`). An implementing
    // class whose own setter the read-only-base guard refused (CS0546) satisfies the setter through
    // an explicit `IFoo.X` member instead (case D, `explicitSetterInterfaces`).
    CirInterfaceProperty(
      plan.publicName,
      ForwardCirPropertyProjection.publicType(plan),
      hasSetter = plan.setter != null,
      doc = plan.doc?.toCirDoc(),
      isNew = propertyPlacements[plan.symbol] == ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE,
    )
  }
  val properties: List<CirInterfaceProperty> =
    plannedProperties + typeParameterProperties(iface, typeParamNames, plannedProperties)

  val methodPlans: List<ForwardCallablePlan> = callableCatalog.classMethods(qualified)
    .filter { plan -> declared(methodPlacements[plan.invocation.symbol]) }
  // An `out` parameter is invariant in C#, so a variant `I<out T>` cannot declare a `Result` Try
  // twin whose `out` value mentions `T` (CS1961); its implementing classes still declare theirs.
  // A twin over any other payload is legal and kept.
  val variantTokens: List<Regex> = typeParams
    .filter { param -> param.variance != CirVariance.INVARIANT }
    .map { param -> Regex("(?<![A-Za-z0-9_])${Regex.escape(param.name)}(?![A-Za-z0-9_])") }
  val plannedMethods: List<CirInterfaceMethod> = methodPlans.map { plan ->
    tracker.trackPlan(plan)
    val method = CirInterfaceMethod(
      name = plan.publicSignature.name,
      returnType = plan.publicSignature.result.forwardPublicCsharpType(),
      // ADR-164: the same widened parameters (nullable form, `Optional<T>`, defaults) the
      // implementing class renders, so `IFoo` and its implementer declare one signature.
      parameters = ForwardCirPlanProjection.interfaceParameters(plan),
      doc = plan.publicSignature.cirDoc(),
      isNew = methodPlacements[plan.invocation.symbol] ==
          ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE,
    )
    val variantValue: Boolean = variantTokens.any { token -> token in method.returnType }
    method.copy(
      tryOverload =
        if (variantValue) null else ForwardCirPlanProjection.interfaceResultTry(plan, method),
    )
  }

  val methods: List<CirInterfaceMethod> =
    plannedMethods + typeParameterMethods(iface, typeParamNames, plannedMethods)

  emitInterfaceNameCollisions(interfaceName, iface, propertyPlans, methodPlans, logger)
  // Interface super-interfaces: the same CS0102-family guard across the base list. A declared
  // method named like an INHERITED property (or the reverse) hides it, CS0108, which the
  // warnings-as-errors consumer build turns into a break.
  emitInheritedInterfaceNameCollisions(
    interfaceName,
    iface,
    declaredProperties = propertyPlans,
    declaredMethods = methodPlans,
    inheritedProperties = callableCatalog.propertyPlans
      .filter { plan -> plan.symbol.substringBeforeLast('.') == qualified }
      .filterNot { plan -> declared(propertyPlacements[plan.symbol]) },
    inheritedMethods = callableCatalog.classMethods(qualified)
      .filterNot { plan -> declared(methodPlacements[plan.invocation.symbol]) },
    logger = logger,
  )
  // ADR-090 amendment (2026-09-26): the same ADR-034 guard every other container runs. Here, not on
  // the reachable-only backing class, because `IFoo` is emitted for every exported interface.
  emitCsharpSignatureCollisionsOf(
    plannedMethods.map { method -> method.name to method.parameters },
    interfaceName,
    iface,
    logger,
  )

  // ADR-174: the async members this interface DECLARES, with the class route's own signatures, so
  // an implementer's `FetchAsync`/`Ticks()`/`Level` satisfy them as they stand. The real tracker:
  // `IFeed` spells `KotlinFlow<T>`/`Task<T>`, so it needs the same helpers the wrapper does.
  // ADR-174 amendment: placed as the sync route places a member (`methodPlacements` above): a
  // DECLARED one plainly, a DIAMOND_OVERRIDE one with `new`; inherited and identical-override
  // members stay on the super that declares them.
  fun asyncAt(placement: ForwardInterfaceMemberPlacement): CirInterfaceAsyncMembers? =
    context?.let {
      interfaceAsyncMembers(
        iface, interfaceName, it.libraryName, classifier, tracker, callableCatalog,
        it, expects, placements = setOf(placement),
      )
    }
  val asyncByNew: List<Pair<Boolean, CirInterfaceAsyncMembers?>> = listOf(
    false to asyncAt(ForwardInterfaceMemberPlacement.DECLARED),
    true to asyncAt(ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE),
  )
  val asyncMethods: List<CirInterfaceMethod> = asyncByNew.flatMap { (isNew, async) ->
    async?.methods.orEmpty().map { method ->
      CirInterfaceMethod(
        name = method.name,
        returnType = method.returnType,
        parameters = method.parameters,
        doc = method.doc,
        isNew = isNew,
        isAsync = method.isAsync,
      )
    }
  }
  val asyncProperties: List<CirInterfaceProperty> = asyncByNew.flatMap { (isNew, async) ->
    async?.properties.orEmpty().map { prop ->
      CirInterfaceProperty(prop.name, prop.type, doc = prop.doc, isNew = isNew)
    }
  }

  return CirInterface(
    interfaceName, typeParams, properties + asyncProperties, methods + asyncMethods,
    doc = iface.forwardKdoc(expects)?.toCirDoc(),
    superInterfaces = superInterfaces,
    isAsyncDisposable = context != null && iface.forwardInterfaceDeclaresScopeMember(classifier),
  )
}

/**
 * Interface super-interfaces (Step 2 decision: skip and name). `override fun greet(): Cat` over a
 * kept super's `fun greet(): Pet` cannot be redeclared on `IDerived` without `new` plus explicit
 * implementation on every implementer; v1 leaves it off `IDerived`, where C# reaches it through
 * `IBase` at the base's type. The backing class implements it at that type too
 * (`interfaceEntries` plans a covariant override at the kept super's return type).
 */
private fun emitCovariantOverrideSkips(
  interfaceName: String,
  iface: KSClassDeclaration,
  hierarchy: ForwardInterfaceHierarchy,
  logger: KSPLogger,
) {
  val functions: List<KSDeclaration> = iface.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { hierarchy.placement(it) == ForwardInterfaceMemberPlacement.COVARIANT_OVERRIDE }
    .toList()
  val properties: List<KSDeclaration> = iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { hierarchy.placement(it) == ForwardInterfaceMemberPlacement.COVARIANT_OVERRIDE }
    .toList()
  ForwardDiagnosticSink.emit(
    (functions + properties).map { member ->
      val memberName: String = member.simpleName.asString()
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION,
        symbol = member,
        declaration = "$interfaceName.${memberName.replaceFirstChar { it.uppercase() }}",
        reason = "'$memberName' overrides a super-interface member with a narrower (covariant) " +
            "type, which $interfaceName cannot redeclare without hiding the inherited member " +
            "(CS0108), so it is not declared on $interfaceName; C# still calls it through the " +
            "super-interface, at the super-interface's type",
        hint = "cast the result to the narrower type on the C# side, or declare the member with " +
            "the super-interface's type in Kotlin",
        owner = null,
      )
    },
    logger,
  )
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
    .filter { prop -> iface.declaresLexically(prop) }
    .mapNotNull { prop ->
      val typeName: String = prop.type.resolve().expandAliases().declaration.simpleName.asString()
      if (typeName !in typeParamNames) return@mapNotNull null
      val csName: String = prop.csharpMemberName()
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
  val plannedShapes: Set<List<String>> =
    planned.map { method -> listOf(method.name) + method.parameters.map { it.type } }.toSet()

  return iface.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { method -> !method.isCompilerOwnedMember(iface) }
    .filter { method -> iface.declaresLexically(method) }
    // ADR-174 ruling 3: a generic interface's `suspend fun get(): T` is a named skip, never a
    // synchronous `T Get()` this carve-out would otherwise declare for it.
    .filter { method -> !method.modifiers.contains(Modifier.SUSPEND) }
    .mapNotNull { method ->
      val returnName: String? = method.returnType?.resolve()?.expandAliases()
        ?.declaration?.simpleName?.asString()
      val paramNames: List<String> = method.parameters.map { param ->
        param.type.resolve().expandAliases().declaration.simpleName.asString()
      }
      val mentionsTypeParameter: Boolean =
        returnName in typeParamNames || paramNames.any { it in typeParamNames }
      if (!mentionsTypeParameter) return@mapNotNull null

      val csMethodName: String = method.csharpMemberName()
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
      // A planned member of the same C# signature would be a duplicate declaration (CS0111).
      // Cannot happen today, since a class type parameter never classifies into a BridgeType, but
      // the carve-out must not be the thing that discovers otherwise. ADR-090 amendment
      // (2026-09-26): keyed on the rendered parameter types, not the arity, so `peek(x: T)`
      // beside a planned `peek(x: Int)` is a legal C# overload and is no longer silently dropped.
      if (listOf(csMethodName) + params.map { it.type } in plannedShapes) return@mapNotNull null
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
  val spellings = KotlinSpellings()
  val members: List<CsMemberName> = buildList {
    propertyPlans.forEach { plan ->
      spellings.record(plan.publicName, KotlinSpelling("val", plan.kotlinName))
      add(CsMemberName(plan.publicName, CsMemberKind.VALUE))
    }
    methodPlans.forEach { plan ->
      val kotlinName: String = plan.invocation.member
        ?: plan.invocation.symbol.substringAfterLast('.')
      spellings.record(plan.publicSignature.name, KotlinSpelling("fun", kotlinName))
      add(CsMemberName(plan.publicSignature.name, CsMemberKind.METHOD))
    }
  }
  emitMemberNameCollisions(
    container = interfaceName,
    ownerPhrase = "interface $interfaceName",
    symbol = iface,
    members = members,
    spellings = spellings,
    logger = logger,
    reason = { collision ->
      val property: String = collision.values.firstOrNull()?.name ?: collision.name
      if (collision.methods.isEmpty()) {
        "the interface declares ${collision.declarations()}, which ${collision.quantifier} " +
            "render the C# name '${collision.name}', and C# cannot declare two members with " +
            "one name (CS0102)"
      } else {
        "the interface property '$property' already claims that C# name, and C# cannot declare " +
            "a property and a method with one name (CS0102)"
      }
    },
    hint = { collision ->
      val function: String? = collision.methods.firstOrNull()?.name
      if (function == null) {
        "rename one of the properties on $interfaceName"
      } else {
        "rename the Kotlin function '$function' or the property it collides with"
      }
    },
  )
}

/**
 * Interface super-interfaces: [emitInterfaceNameCollisions] across the base list. `interface Pet :
 * Named { fun name(): String }` over `Named`'s `val name` renders `string Name()` on `IPet` beside
 * the inherited `string Name { get; }`, which C# reports as CS0108 (hiding), a build break under
 * the consumer's TreatWarningsAsErrors. Fatal with no rename, for ADR-110's reason: renaming
 * either member would be a silently different API.
 */
private fun emitInheritedInterfaceNameCollisions(
  interfaceName: String,
  iface: KSClassDeclaration,
  declaredProperties: List<ForwardPropertyPlan>,
  declaredMethods: List<ForwardCallablePlan>,
  inheritedProperties: List<ForwardPropertyPlan>,
  inheritedMethods: List<ForwardCallablePlan>,
  logger: KSPLogger,
) {
  val inheritedPropertyNames: Map<String, ForwardPropertyPlan> =
    inheritedProperties.associateBy { it.publicName }
  val inheritedMethodNames: Map<String, ForwardCallablePlan> =
    inheritedMethods.associateBy { it.publicSignature.name }
  val methodCollisions: List<ForwardDiagnostic> = declaredMethods.mapNotNull { plan ->
    val property: ForwardPropertyPlan =
      inheritedPropertyNames[plan.publicSignature.name] ?: return@mapNotNull null
    val kotlinName: String =
      plan.invocation.member ?: plan.invocation.symbol.substringAfterLast('.')
    ForwardDiagnostic(
      kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
      symbol = iface,
      declaration = "$interfaceName.${plan.publicSignature.name}",
      reason = "the inherited interface property '${property.kotlinName}' already claims that C# " +
          "name, and a method of that name on $interfaceName hides it (CS0108)",
      hint = "rename the Kotlin function '$kotlinName' or the inherited property",
      owner = null,
    )
  }
  val propertyCollisions: List<ForwardDiagnostic> = declaredProperties.mapNotNull { plan ->
    val method: ForwardCallablePlan =
      inheritedMethodNames[plan.publicName] ?: return@mapNotNull null
    val kotlinName: String =
      method.invocation.member ?: method.invocation.symbol.substringAfterLast('.')
    ForwardDiagnostic(
      kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
      symbol = iface,
      declaration = "$interfaceName.${plan.publicName}",
      reason = "the inherited interface function '$kotlinName' already claims that C# name, and " +
          "a property of that name on $interfaceName hides it (CS0108)",
      hint = "rename the Kotlin property '${plan.kotlinName}' or the inherited function",
      owner = null,
    )
  }
  ForwardDiagnosticSink.emit(methodCollisions + propertyCollisions, logger)
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
  /** ADR-163: the one symbol table. */
  symbols: ForwardSymbolTable,
  callableCatalog: ForwardCallablePlanCatalog,
  tracker: CollectionHelperTracker,
  logger: KSPLogger,
  // ADR-174: the async members' builders. Null (a unit caller) projects none, the pre-ADR-174
  // shape.
  classifier: ForwardBridgeTypeClassifier? = null,
  context: NugetContext? = null,
  expects: ExpectIndex = ExpectIndex(),
): CirClass {
  val name: String = iface.simpleName.asString()
  val prefix: String = iface.nativePrefix(symbols)
  val ifaceQualified: String = iface.qualifiedName?.asString() ?: name

  val properties: List<CirProperty> = iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { property -> !property.isCompilerOwnedMember(iface) }
    .mapNotNull { prop ->
      val symbol = "$ifaceQualified.${prop.simpleName.asString()}"
      callableCatalog.propertyFor(symbol)?.let { plan ->
        tracker.trackProperty(plan)
        ForwardCirPropertyProjection.classProperty(plan)
      }
    }
    .toList()

  // ADR-090 amendment (2026-09-26): owner-exact catalog read, the same one `translateInterface`
  // and the Kotlin half (`InterfaceExports`) use, so an overload pair binds each numbered plan
  // once.
  val methods: List<CirMethod> = callableCatalog.classMethods(ifaceQualified).map { plan ->
    tracker.trackPlan(plan)
    ForwardCirPlanProjection.classMethod(plan, prefix, isOverride = false)
  }

  // ADR-110 amendment (ROADMAP line 32): the wrapper mirrors `I$name`, whose own guard
  // (ADR-113 Decision E) runs over a different catalog; checked here too so the backing class never
  // relies on the two catalogs agreeing.
  // ADR-174: every async member, own and inherited, since the wrapper implements `IDerived` and
  // through it every `IBase`. Its externs hoist into `FeedNative` (ruling 4), which is where a
  // generic implementer's explicit implementations reach them.
  val async: CirInterfaceAsyncMembers? = if (classifier != null && context != null) {
    interfaceAsyncMembers(
      iface, name, libraryName, classifier, tracker, callableCatalog, context, expects,
      placements = null,
    )
  } else {
    null
  }
  val ownsScope: Boolean = async != null && !async.isEmpty()

  val allMembers: List<CirMember> =
    properties + methods + async?.properties.orEmpty() + async?.members.orEmpty()
  emitMemberNameCollisions(
    name, "interface $name", iface, allMembers.csMemberNames(),
    KotlinSpellings.ofClass(iface), logger,
  )

  return CirClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    constructor = null,
    properties = properties + async?.properties.orEmpty(),
    methods = methods,
    interfaces = listOf("I$name"),
    hasInternalHandleConstructor = true,
    isSealed = true,
    backsInterface = "I$name",
    companionMembers = async?.members.orEmpty(),
    nativeCarrier = interfaceNativeCarrier(iface).takeIf { ownsScope },
    // ADR-159 (ROADMAP:49), ADR-174 ruling 5: derived from what this wrapper projects. It has no
    // base chain, so it is its own scope owner whenever it projects a scope-using member.
    hasSuspendMethods = ownsScope,
    ownsScope = ownsScope,
  )
}

/**
 * The C# spelling of an enum at a type position, the same rule
 * `ForwardBridgeTypeClassifier.csharpTypeNameFor` applies: the enclosing-scope name, qualified with
 * `global::<namespace>.` unless there is no root namespace to qualify against.
 */
private fun csharpEnumTypeName(enum: KSClassDeclaration, context: NugetContext?): String {
  val nestedName: String = enum.nestedCsName()
  if (context == null || context.rootNamespace.isEmpty()) return nestedName
  val namespace: String = mapPackageToNamespace(
    enum.packageName.asString(),
    context.rootPackage,
    context.rootNamespace,
  )
  return "global::$namespace.$nestedName"
}

/**
 * Issue #285: two entries of one enum whose converted C# names are equal.
 *
 * This is not a new hazard introduced by the casing rule, it is an old one that was silent: `FOO`
 * beside `Foo` already rendered `Foo = 0, Foo = 1`, which is CS0102 at the *consumer's* compile
 * with no generator diagnostic at all. The per-segment rule adds one more colliding pair (`FOO_BAR`
 * beside `FooBar`, which used to differ only because the second was mangled to `Foobar`) and closes
 * both with the same fatal error ADR-110/ADR-113 already use for a C# name claimed twice.
 *
 * Fatal rather than a deterministic suffix on purpose: a silent `FooBar_1` is the very
 * unpredictability the issue reports, and the ordinals may not move, so there is no rename the
 * generator could pick that a consumer would rather have than a one-word source fix.
 */
private fun emitEnumEntryNameCollisions(
  enumName: String,
  enum: KSClassDeclaration,
  logger: KSPLogger,
) {
  enum.declarations
    .filterIsInstance<KSClassDeclaration>()
    .filter { it.classKind == ClassKind.ENUM_ENTRY }
    .map { entry -> entry.simpleName.asString() }
    .toList()
    .groupBy { kotlinName -> kotlinName.kotlinConstantToPascalCase() }
    .filter { (_, kotlinNames) -> kotlinNames.size > 1 }
    .forEach { (csName, kotlinNames) ->
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
            symbol = enum,
            declaration = "$enumName.$csName",
            reason = "the entries ${kotlinNames.joinToString(", ") { "'$it'" }} all convert to " +
                "that one C# name, and C# cannot declare an enum member twice (CS0102)",
            hint = "rename one entry; the C# spelling may not be suffixed automatically because " +
                "an entry crosses as its ordinal",
            // ERROR_*: the build fails, so nothing generated is ever read.
            owner = null,
          ),
        ),
        logger,
      )
    }
}

internal fun translateEnum(
  enum: KSClassDeclaration,
  libraryName: String,
  // Issue #285: required, not nullable-with-a-skip. Both call sites (`CirTranslator`'s nested walk
  // and its top-level one) have a logger in scope, and a nullable logger would silently disable the
  // after-casing collision guard in exactly the harness that tests it.
  logger: KSPLogger,
  /** ADR-163: the one symbol table. Non-null even where [context] is: the entry-point prefix is
   *  not optional, and an unqualified fallback is exactly the collision this ADR removes. */
  symbols: ForwardSymbolTable,
  expects: ExpectIndex = ExpectIndex(),
  // The namespace mapping, so an enum-typed enum property can spell the other enum the way every
  // other C# type position spells it. Null keeps the bare nested name (the Tier 1 no-namespace
  // shape), matching `ForwardBridgeTypeClassifier.csharpTypeNameFor`'s empty-rootNamespace case.
  context: NugetContext? = null,
  // ADR-006 amendment: the plans the enum's member properties project from, and the helper tracker
  // a collection-typed member has to mark (or its `NugetListNative` is never emitted).
  callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
  tracker: CollectionHelperTracker = CollectionHelperTracker(),
): CirEnum {
  val name: String = enum.simpleName.asString()
  val entries: List<CirEnumEntry> = enum.declarations
    .filterIsInstance<KSClassDeclaration>()
    // ADR-133: an enum body can also declare a nested CLASS, which is not an entry. Without this
    // filter it was rendered as an extra C# enum member (`Almanac = 2`) with an ordinal no Kotlin
    // entry has -- a pre-existing defect no fixture had reached.
    .filter { it.classKind == ClassKind.ENUM_ENTRY }
    .mapIndexed { index, entry ->
      // Issue #285: the one place an entry name is spelled for C#. Every value position crosses as
      // the ordinal, so this helper (shared with `const val`) owns the whole surface spelling.
      val csEntryName: String = entry.simpleName.asString().kotlinConstantToPascalCase()
      CirEnumEntry(csEntryName, index, doc = entry.forwardKdoc(expects)?.toCirDoc())
    }
    .toList()

  emitEnumEntryNameCollisions(name, enum, logger)

  // ADR-006 amendment: the enum's own member properties come off their ENUM_MEMBER plans, the
  // same plans `EnumExports` emits the Kotlin half from, so the two cannot disagree.
  val receiverName: String = enum.enumReceiverName()
  val qualifiedName: String? = enum.qualifiedName?.asString()
  val propertyMembers: List<CirMember> = qualifiedName
    ?.let { callableCatalog.propertyPlans.enumMembersOf(it) }
    .orEmpty()
    .flatMap { plan ->
      tracker.trackProperty(plan)
      ForwardCirPropertyProjection.enumMember(plan, libraryName, receiverName)
    }
  // ADR-006 amendment: member functions as `this {Enum}` extensions (the plan's receiver is already
  // named [receiverName]), and the companion's functions and `val`/`var`s as plain statics, all in
  // the same `{Enum}Extensions` class: a C# enum cannot declare members, and C# 12 has no static
  // extension members.
  val functionMembers: List<CirMember> = qualifiedName
    ?.let { callableCatalog.enumMethods(it) }
    .orEmpty()
    .flatMap { plan ->
      tracker.trackPlan(plan)
      ForwardCirPlanProjection.extension(plan, libraryName, receiverName)
    }
  val companionProperties: List<CirMember> = qualifiedName
    ?.let { callableCatalog.enumCompanionProperties(it) }
    .orEmpty()
    .flatMap { plan ->
      tracker.trackProperty(plan)
      ForwardCirPropertyProjection.staticProperty(plan, libraryName)
    }
  val companionFunctions: List<CirMember> = qualifiedName
    ?.let { callableCatalog.companionMethods(it) }
    .orEmpty()
    .flatMap { plan ->
      tracker.trackPlan(plan)
      ForwardCirPlanProjection.static(plan, libraryName)
    }
  val extensionMembers: List<CirMember> =
    propertyMembers + functionMembers + companionProperties + companionFunctions

  return CirEnum(
    name = name,
    libraryName = libraryName,
    // ADR-133: the chain, so a nested `Owner.Kind` exports `owner_kind_get_*` and cannot collide
    // with a top-level `Kind` (ADR-117).
    nativePrefix = enum.nativePrefix(symbols),
    csName = enum.nestedCsName(),
    entries = entries,
    extensionMembers = extensionMembers,
    doc = enum.forwardKdoc(expects)?.toCirDoc(),
    boxImport = qualifiedName
      ?.let { callableCatalog.enumBox(it) }
      ?.let { plan -> ForwardCirPlanProjection.enumBox(plan, libraryName) },
  )
}

internal fun translateValueClass(
  cls: KSClassDeclaration,
  libraryName: String,
  logger: KSPLogger,
  context: NugetContext,
  callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
  // ADR-150: the expect index. A bare `forwardKdoc()` here cannot reach the `expect` half,
  // so a documented `expect` declaration of this family rendered with no summary at all.
  expects: ExpectIndex = ExpectIndex(),
): CirValueClass {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: name
  val prefix: String = cls.nativePrefix(context.symbols)

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

  // ADR-035: plan-only, on both underlying kinds. A reference underlying has no *primary* plan
  // (the positional record header already constructs one, and a second `Wrapper(Cat)` would be
  // CS0111), but its secondaries are planned since the 2026-09-11 amendment; the lookup below
  // simply finds nothing for `<init>` in that case.
  val constructors: List<CirValueClassConstructor> = buildList {
    val primaryPlan: ForwardCallablePlan? = callableCatalog.planFor("$qualifiedName.<init>")
    if (primaryPlan != null) add(buildConstructorFromPlan(primaryPlan, ""))
    secondaryCtorDecls.forEachIndexed { index, _ ->
      val number: Int = index + 2
      val planned: ForwardCallablePlan? = callableCatalog.planFor("$qualifiedName.<init>_$number")
      if (planned != null) add(buildConstructorFromPlan(planned, "_$number"))
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
          // ERROR_*: the build fails before anything generated is read.
          owner = null,
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
  // ADR-110 amendment (ROADMAP line 32): the record struct declares the underlying property
  // itself (`double Value { get; }`), so it is a VALUE here even when no plan lists it.
  val valueClassNames: List<CsMemberName> = buildList {
    if (properties.none { it.name == underlyingName }) {
      add(CsMemberName(underlyingName, CsMemberKind.VALUE))
    }
    addAll((properties + methods).csMemberNames())
  }
  emitMemberNameCollisions(
    name, "value class $name", cls, valueClassNames, KotlinSpellings.ofClass(cls), logger,
  )

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
    doc = cls.forwardKdoc(expects)?.toCirDoc(),
    // ADR-150 amendment: the underlying property is never a `CirProperty` -- both value-class
    // shapes render it themselves -- so it needs its own slot to be documented at all.
    // `forwardKdoc()` gives it the precedence every other property has: its own KDoc first, then
    // the class comment's `@property <name>` text, then nothing.
    underlyingDoc = underlyingProp.forwardKdoc(expects)?.toCirDoc(),
    boxing = callableCatalog.valueClassBoxing(qualifiedName)?.let { (box, unbox) ->
      ForwardCirPlanProjection.valueClassBoxing(box, unbox, libraryName)
    },
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
  val csMethodName: String = method.csharpMemberName()
  // ADR-179: the extern keeps the Kotlin-derived stem; only the public member takes `@CSharpName`.
  val csNativeName: String =
    "Native_${methodName.asCSymbol().replaceFirstChar { it.uppercase() }}"
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
    kotlinType == "Boolean" -> "Bool"
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

  // ADR-036's marshalling table: a primitive payload crosses BY VALUE. `String` keeps its handle
  // (it is not a C ABI scalar) and so does `Char`, which has no crossing convention on any route.
  // Mirrors the interface-bridge route's branch, deliberately: the two share delegate names.
  val byValueArgs: List<Boolean> = lambdaArgTypes.map { argType ->
    val simple: String = argType.declaration.simpleName.asString()
    val qualified: String = argType.declaration.qualifiedName?.asString() ?: ""
    qualified.startsWith("kotlin.") && simple in KOTLIN_TO_CSHARP_PARAM &&
        simple != "String" && simple != "Char"
  }

  val delegateParamList: String = if (lambdaArity == 0) {
    "(IntPtr userData)"
  } else {
    val argParams: String = lambdaArgTypes
      .mapIndexed { i, argType ->
        val argKotlin: String = argType.declaration.simpleName.asString()
        when {
          // The `byte` wire is widened to `bool` in the body, under a DIFFERENT name: naming the
          // parameter `arg0` and then declaring `bool arg0` from it is CS0128.
          argKotlin == "Boolean" -> "byte arg${i}Byte"
          byValueArgs[i] -> "${KOTLIN_TO_CSHARP_PARAM[argKotlin]} arg$i"
          else -> "IntPtr arg${i}Ptr"
        }
      }
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
      val csArgType: String = when {
        argKotlin == "String" -> "string"
        byValueArgs[i] -> KOTLIN_TO_CSHARP_PARAM.getValue(argKotlin)
        else -> argKotlin
      }
      when {
        argKotlin == "Boolean" -> appendLine("            bool arg$i = arg${i}Byte != 0;")
        // A by-value primitive IS the delegate parameter; there is nothing to unmarshal.
        byValueArgs[i] -> Unit
        else -> appendLine(
          "            $csArgType arg$i = NugetMarshal.FromHandle<$csArgType>(arg${i}Ptr);",
        )
      }
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
  // ADR-102: the thunk address is a link-time constant and the ctx is the closure's own ADR-161
  // table key, both inlined at the call so the pair cannot drift apart.
  val nativeCall: String = "$csNativeName(_handle, NugetThunks.${delegateName}Ptr, " +
      "cbKey, out IntPtr error)"
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

      !isOuterRetUnit -> append("            return new $outerRetKotlin(nativeHandle, out _);")
    }
  }

  if (isOuterRetList) tracker.needsList = true

  val nativeImportReturnType: String = if (isOuterRetUnit) "void" else "IntPtr"

  return CirCallbackMethod(
    csMethodName = csMethodName,
    csNativeName = csNativeName,
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
  className: String,
  exportedTypes: Set<String>,
  tracker: CollectionHelperTracker,
  context: NugetContext,
): CirStoredCallbackMethod? {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()
  val csMethodName: String = addMethod.csharpMemberName()
  // ADR-179: the extern keeps the Kotlin-derived stem; only the public member takes `@CSharpName`.
  val csAddNativeName: String =
    "Native_${addMethodName.asCSymbol().replaceFirstChar { it.uppercase() }}"
  val csRemoveNativeName: String =
    "Native_${removeMethodName.asCSymbol().replaceFirstChar { it.uppercase() }}"

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
    argType.declaration.simpleName.asString() == "Boolean" -> "Object"
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
      // ADR-036 amendment (2026-09-11): `FromHandle` already owns the handle. Its `string` branch
      // disposes as it reads, and an exported object is materialised into a wrapper that frees it
      // in `Dispose()`. The explicit dispose here was a second free of the same handle.
      else append("$csType arg$i = NugetMarshal.FromHandle<$csType>(arg${i}Ptr); ")
    }
    val callArgs: String = if (lambdaArity == 0) "" else
      lambdaArgTypes.indices.joinToString(", ") { "arg$it" }
    append("listener($callArgs);")
  }

  return CirStoredCallbackMethod(
    csMethodName = csMethodName,
    csAddNativeName = csAddNativeName,
    csRemoveNativeName = csRemoveNativeName,
    subscribeEntryPoint = "${classPrefix}_$addMethodName",
    removeEntryPoint = "${classPrefix}_$removeMethodName",
    libraryName = libraryName,
    delegateName = delegateName,
    delegateParamList = delegateParamList,
    csParamType = csParamType,
    nativeCallbackBody = nativeCallbackBody,
    className = className,
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
  // Issue #41: the listener parameter and every callback argument are type references into a file
  // whose only usings are `System` ones, so both need the one qualifier. The classifier spells the
  // listener interface (owner-chained and `global::`-qualified); the context spells the arguments.
  classifier: ForwardBridgeTypeClassifier,
  context: NugetContext,
): CirInterfaceBridgeMethod? {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()
  val csMethodName: String = addMethod.csharpMemberName()
  // ADR-179: the extern keeps the Kotlin-derived stem; only the public member takes `@CSharpName`.
  val csAddNativeName: String =
    "Native_${addMethodName.asCSymbol().replaceFirstChar { it.uppercase() }}"
  val csRemoveNativeName: String =
    "Native_${removeMethodName.asCSymbol().replaceFirstChar { it.uppercase() }}"

  val ifaceParam = addMethod.parameters.firstOrNull { param ->
    (param.type.resolve().expandAliases().declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.INTERFACE
  } ?: return null

  val ifaceDecl = ifaceParam.type.resolve().expandAliases().declaration as? KSClassDeclaration ?: return null
  // ADR-133 amendment (2026-09-14): the projected interface name, owner-chained and qualified.
  // The bare `I$ifaceName` this used to print only resolved because every shipped pair fixture
  // sits beside its own top-level listener: a nested listener is declared `Aviary.IWatcher` and a
  // cross-package one lives in another namespace, so the bare name failed CS0246 at both.
  // A listener with no C# declaration at all never reaches here: the first arm of
  // `legacyRefusedInterfaceBridgePair` refuses and names the pair on both halves (ADR-039
  // amendment 2026-09-28), so the null branch is unreachable by construction.
  val interfaceCsName: String =
    classifier.legacyFlowElementInterface(ifaceParam.type.resolve())?.csharpType ?: return null

  val ifaceMethods: List<KSFunctionDeclaration> = ifaceDecl.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { method -> !method.isCompilerOwnedMember(ifaceDecl) }
    .toList()

  tracker.needsSubscription = true

  // A listener property crosses as its ADR-084 getter slot, ahead of the function slots, read off
  // the same list the Kotlin half's parameters come from.
  val propertyEntries: List<CirInterfaceBridgeMethodEntry> =
    classifier.listenerPropertySlots(ifaceDecl).map { slot ->
      val delegate = CirCallbackDelegate(
        slot.delegateName(),
        slot.delegateParamList(),
        slot.result.wire.csharpWire(),
      )
      if (tracker.callbackDelegates.none { it.name == delegate.name }) {
        tracker.callbackDelegates.add(delegate)
      }
      CirInterfaceBridgeMethodEntry(
        methodCsName = slot.csName,
        methodKtName = slot.slotPrefix,
        delegateName = delegate.name,
        delegateParamList = "(IntPtr _)",
        callbackBody = slotBody(slot, "listener"),
      )
    }

  val methodEntries: List<CirInterfaceBridgeMethodEntry> = ifaceMethods.map { method ->
    // The slot stem (`onMeowPtr`, `tug_hardCb`), cleaned as the Kotlin half cleans it.
    val mName: String = method.simpleName.asString().asCSymbol()
    val mCsName: String = method.csharpMemberName()
    val params = method.parameters.toList()
    val arity: Int = params.size

    // ADR-039 amendment (2026-09-26): one classification per parameter, the same one the pair gate
    // (`legacyRefusedInterfaceBridgePair`) admitted it by, so the wire cannot drift from the gate.
    val bridgeTypes: List<BridgeType> = params.map { param ->
      classifier.classify(param.type.resolve())
    }
    val wires: List<InterfaceBridgeWire> = bridgeTypes.map { type -> type.interfaceBridgeWire() }

    // Delegate suffix naming follows stored-callback convention
    val argSuffixes: List<String> = params.mapIndexed { i, param ->
      val pSimple: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
      when (wires[i]) {
        InterfaceBridgeWire.ORDINAL -> "Int"
        InterfaceBridgeWire.BOOL -> "Bool"
        InterfaceBridgeWire.BY_VALUE -> pSimple
        InterfaceBridgeWire.HANDLE -> "Object"
      }
    }
    val delegateName: String = "Nuget${argSuffixes.joinToString("")}VoidCallback"

    // Delegate param list: enum -> int arg${i}Ord, reference/string -> IntPtr arg${i}Ptr, arity-0 -> IntPtr _
    val delegateParamList: String = if (arity == 0) {
      "(IntPtr _)"
    } else {
      val argParams: String = params.mapIndexed { i, param ->
        val pSimple: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
        when (wires[i]) {
          InterfaceBridgeWire.ORDINAL -> "int arg${i}Ord"
          InterfaceBridgeWire.BOOL -> "byte arg${i}"
          InterfaceBridgeWire.BY_VALUE -> "${byValueCsType(pSimple)} arg${i}"
          InterfaceBridgeWire.HANDLE -> "IntPtr arg${i}Ptr"
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
        when (wires[i]) {
          InterfaceBridgeWire.ORDINAL -> {
            val csType: String = qualifiedElementCsType(pType, context)
            append("$csType arg$i = ($csType)arg${i}Ord; ")
          }
          InterfaceBridgeWire.BOOL -> append("bool arg$i = arg${i} != 0; ")
          InterfaceBridgeWire.BY_VALUE -> {
            /* arg is already the right type, no unmarshal needed */
          }

          // ADR-036 amendment (2026-09-11): see the stored-callback route above; `FromHandle` is
          // the owner, so there is no second dispose here.
          InterfaceBridgeWire.HANDLE -> {
            // Issue #41 again, one level down: the unmarshal declares a local of the argument's
            // own type, which the ADR-037 stored-callback sibling already qualifies
            // (`csParamType`).
            val csType: String =
              if (bridgeTypes[i] == BridgeType.String) "string"
              else qualifiedElementCsType(pType, context)
            append("$csType arg$i = NugetMarshal.FromHandle<$csType>(arg${i}Ptr); ")
          }
        }
      }
      val callArgs: String = params.indices.joinToString(", ") { "arg$it" }
      append("listener.${toCSharpName(mCsName)}($callArgs);")
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
    csAddNativeName = csAddNativeName,
    csRemoveNativeName = csRemoveNativeName,
    subscribeEntryPoint = "${classPrefix}_$addMethodName",
    removeEntryPoint = "${classPrefix}_$removeMethodName",
    libraryName = libraryName,
    interfaceCsName = interfaceCsName,
    className = className,
    entries = propertyEntries + methodEntries,
  )
}

/**
 * ADR-150: the generator's own trailing parameter on every `Async` projection (ADR-023). It is a
 * C# parameter like any other as far as the XML-doc pass is concerned, so it takes an (empty) tag
 * whenever the author documented any of the declared ones. Its name is the one `renderAsyncMethod`
 * declares, `cancellationToken` unless a user parameter already has it.
 */
private fun asyncCancellationParameter(parameters: List<CirParameter>): String =
  CirAsyncLocals.of(parameters).cancellationToken

/**
 * ADR-039 amendment (2026-09-26): a by-value listener parameter's C# type; the gate admits only
 * primitives.
 */
private fun byValueCsType(kotlinSimpleName: String): String =
  KOTLIN_TO_CSHARP_PARAM[kotlinSimpleName]
    ?: error("ADR-039: the subscription pair gate admitted `$kotlinSimpleName` by value")

/**
 * ADR-174: an interface's `suspend`/`Flow`/`StateFlow` members, projected by the class route's own
 * builders ([suspendMembers], [flowMembers], [flowProperty]) with the interface as owner and its
 * own export prefix, the way ADR-118/124 reused them for a sealed arm. [placements] narrows to
 * the members at those [ForwardInterfaceHierarchy] placements (the sync route's own), which
 * `I<Name>` and a generic implementer's forwards use; `null`, the backing wrapper, takes every
 * member, own and inherited.
 */
internal class CirInterfaceAsyncMembers(
  /** The `[DllImport]`s and `async`/flow [CirMethod]s, in the class route's own order. */
  val members: List<CirMember>,
  val properties: List<CirProperty>,
) {
  val methods: List<CirMethod> get() = members.filterIsInstance<CirMethod>()
  fun isEmpty(): Boolean = members.isEmpty() && properties.isEmpty()
}

internal fun interfaceAsyncMembers(
  iface: KSClassDeclaration,
  ownerCsName: String,
  libraryName: String,
  classifier: ForwardBridgeTypeClassifier,
  tracker: CollectionHelperTracker,
  callableCatalog: ForwardCallablePlanCatalog,
  context: NugetContext,
  expects: ExpectIndex,
  placements: Set<ForwardInterfaceMemberPlacement>?,
  nativeCarrier: String = "",
): CirInterfaceAsyncMembers {
  val prefix: String = iface.nativePrefix(context.symbols)
  val hierarchy: ForwardInterfaceHierarchy by lazy {
    ForwardInterfaceHierarchy(iface, classifier.exportedObjectHandles)
  }
  fun KSDeclaration.kept(): Boolean =
    placements == null || iface.forwardAsyncPlacement(this, hierarchy, classifier) in placements
  val suspend: List<CirMember> = suspendMembers(
    suspendMethods = iface.forwardInterfaceSuspendMethods(classifier).filter { it.kept() },
    prefix = prefix,
    libraryName = libraryName,
    classifier = classifier,
    tracker = tracker,
    callableCatalog = callableCatalog,
    context = context,
    expects = expects,
  )
  val flow: List<CirMember> = flowMembers(
    flowMethods = iface.forwardInterfaceFlowMethods(classifier).filter { it.kept() },
    prefix = prefix,
    libraryName = libraryName,
    classifier = classifier,
    tracker = tracker,
    callableCatalog = callableCatalog,
    context = context,
  )
  val properties: List<CirProperty> = iface.forwardInterfaceFlowProperties(classifier)
    .filter { it.kept() }
    .mapNotNull { prop ->
      flowProperty(prop, ownerCsName, context, classifier, tracker, nativeCarrier = nativeCarrier)
    }
  return CirInterfaceAsyncMembers(suspend + flow, properties)
}

/**
 * ADR-174 ruling 4: a generic implementer's explicit interface implementations of every async
 * member the interfaces it implements declare. Each calls the interface's own import through the
 * backing wrapper's carrier (`FeedNative`), with this instance's `_handle` and scope; the Kotlin
 * export reads the receiver as `asStableRef<Feed>()`, so dispatch reaches `Crate<Int>` itself.
 */
internal fun interfaceAsyncForwards(
  cls: KSClassDeclaration,
  libraryName: String,
  classifier: ForwardBridgeTypeClassifier,
  tracker: CollectionHelperTracker,
  callableCatalog: ForwardCallablePlanCatalog,
  context: NugetContext,
  expects: ExpectIndex,
): CirInterfaceAsyncMembers {
  val forwards: List<CirInterfaceAsyncMembers> =
    cls.forwardAsyncInterfaceForwards(classifier).mapNotNull { iface ->
      val type: KSType = cls.getAllSuperTypes()
        .firstOrNull { it.declaration.qualifiedName?.asString() == iface.qualifiedName?.asString() }
        ?: return@mapNotNull null
      val spelling: String = forwardSuperInterfaceSpelling(type, classifier, from = cls)
        ?: return@mapNotNull null
      // The wrapper nests beside its interface (ADR-133), so the carrier is spelled from the same
      // qualifier with the wrapper's own nested name.
      val qualifier: String = spelling.substringBeforeLast('.', missingDelimiterValue = "")
        .let { if (it.isEmpty()) "" else "$it." }
      val carrier: String = "$qualifier${iface.simpleName.asString()}Native"
      val projected: CirInterfaceAsyncMembers = interfaceAsyncMembers(
        iface, spelling, libraryName, classifier, tracker, callableCatalog, context, expects,
        placements = DECLARING_PLACEMENTS, nativeCarrier = "$carrier.",
      )
      return@mapNotNull CirInterfaceAsyncMembers(
        members = projected.methods.map { method ->
          fun String.carried(): String = if (isEmpty()) this else "$carrier.$this"
          method.copy(
            explicitInterface = spelling,
            nativeName = method.nativeName.carried(),
            acquiredFlowCollectNativeName = method.acquiredFlowCollectNativeName?.carried(),
            stateFlowValueNativeName = method.stateFlowValueNativeName.carried(),
            stateFlowHasValueNativeName = method.stateFlowHasValueNativeName.carried(),
            stateFlowSetValueNativeName = method.stateFlowSetValueNativeName.carried(),
          )
        },
        properties = projected.properties.map { prop ->
          prop.copy(explicitInterface = spelling, hasNativeImport = false)
        },
      )
    }
  return CirInterfaceAsyncMembers(
    forwards.flatMap { it.members },
    forwards.flatMap { it.properties },
  )
}

/** ADR-174: the backing wrapper's carrier, `FeedNative`, when it projects an async member. */
internal fun interfaceNativeCarrier(iface: KSClassDeclaration): String =
  "${iface.simpleName.asString()}Native"

/** ADR-174 amendment: the placements at which an interface's own `I<Name>` declares a member. */
private val DECLARING_PLACEMENTS: Set<ForwardInterfaceMemberPlacement> = setOf(
  ForwardInterfaceMemberPlacement.DECLARED,
  ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE,
)

internal fun acquiredFlowCollectImport(library: String, prefix: String, name: String): CirDllImport =
  CirDllImport(
    libraryName = library,
    entryPoint = "${prefix}_collect",
    returnType = "IntPtr",
    name = name,
    parameters = listOf(
      CirParameter("flowHandle", KOTLIN_HANDLE),
      CirParameter("scopeHandle", KOTLIN_HANDLE),
      CirParameter("onNextPtr", "IntPtr"),
      CirParameter("onCompletePtr", "IntPtr"),
      CirParameter("onErrorPtr", "IntPtr"),
      CirParameter("userData", "IntPtr"),
    ),
    visibility = CirVisibility.PRIVATE,
  )

/**
 * ADR-199: this arm's generic shape against its generic sealed [base], by the arm rule: its own C#
 * parameter list (an own parameter it forwards, or a phantom for a fixed variant argument), the
 * base it derives from (`Outcome<T>`, `Cell<int>`), and what the base's `FromHandle` evaluates for
 * it. Unchanged for a non-generic base.
 */
private fun CirSealedSubclass.genericArmOf(
  base: KSClassDeclaration,
  arm: KSClassDeclaration,
  baseTypeParameters: List<CirTypeParameter>,
  classifier: ForwardBridgeTypeClassifier,
  logger: KSPLogger,
  context: NugetContext,
): CirSealedSubclass {
  if (baseTypeParameters.isEmpty()) return this
  // The holder of `Outcome` is also called `Outcome`, so an arm of that name is CS0542 on it.
  if (isNested && name == base.simpleName.asString()) {
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
          symbol = arm.takeIf { it.containingFile != null },
          declaration = arm.qualifiedName?.asString() ?: name,
          reason = "the generic sealed arm `$name` is declared on its base's non-generic holder " +
              "`${base.simpleName.asString()}`, and a type named like its enclosing type is CS0542",
          hint = "rename the arm",
          // ERROR_*: the build fails before anything generated is read.
          owner = null,
        ),
      ),
      logger,
    )
  }
  val armCsName: String = if (isNested) "${base.nestedCsName()}.$name" else name
  val baseArguments: String = baseTypeParameters.joinToString(", ") { it.name }
  val shape: ForwardSealedArmShape = arm.forwardSealedArmShape(base) ?: return this
  val parameters: List<CirTypeParameter> =
    arm.armCirTypeParameters(shape, baseTypeParameters, logger, context)
  val spelledBase: String = sealedBaseSpelling(base, shape, classifier)
  // The base's `FromHandle` instantiates the arm at the base's own parameters.
  val atBase: String = if (parameters.isEmpty()) {
    ""
  } else {
    shape.parameters.joinToString(", ", "<", ">") { baseTypeParameters[it.baseIndex].name }
  }
  val self: String = "${base.nestedCsName()}<$baseArguments>"
  fun throughBase(expression: String): String =
    if (shape.isClosed) "($self)(object)$expression" else expression
  // An intermediate arm is declared by its own hierarchy, which discriminates it.
  if (arm.isIntermediateGenericSealedArm()) {
    return copy(
      isIntermediate = true,
      fromHandle = throughBase("$armCsName$atBase.FromHandle(handle)"),
    )
  }
  val constructed: String =
    if (backingName != null) "$armCsName.$backingName$atBase" else "$armCsName$atBase"
  val fromHandle: String = throughBase("new $constructed(handle, out _)")
  val ownArguments: String = if (parameters.isEmpty()) {
    ""
  } else {
    parameters.joinToString(", ", "<", ">") { it.name }
  }
  val ownConstructed: String =
    if (backingName != null) "$armCsName.$backingName$ownArguments" else "$armCsName$ownArguments"
  return copy(
    typeParameters = parameters,
    baseType = spelledBase,
    fromHandle = fromHandle,
    selfFactory = "new $ownConstructed(handle, out _)".takeIf { parameters.isNotEmpty() },
  )
}

/**
 * ADR-199: the C# type parameters of an arm, by its [shape]: an own parameter as the arm's class
 * route spells and constrains it, a phantom as the base parameter it stands in for.
 */
private fun KSClassDeclaration.armCirTypeParameters(
  shape: ForwardSealedArmShape,
  baseTypeParameters: List<CirTypeParameter>,
  logger: KSPLogger,
  context: NugetContext,
): List<CirTypeParameter> {
  val declared: List<CirTypeParameter> = cirTypeParameters(logger, context)
  return shape.parameters.map { slot ->
    val ownParameter: KSTypeParameter? = slot.own
    if (ownParameter != null) {
      declared.first { it.name == forwardCsharpTypeParameterName(ownParameter) }
    } else {
      baseTypeParameters[slot.baseIndex].copy(name = slot.csharpName)
    }
  }
}

/** ADR-199: the C# base an arm of [base] derives from (`Outcome<T>`, `Cell<int>`, `Cell.Odd`). */
private fun sealedBaseSpelling(
  base: KSClassDeclaration,
  shape: ForwardSealedArmShape,
  classifier: ForwardBridgeTypeClassifier,
): String {
  if (shape.baseArguments.isEmpty()) return base.nestedCsName()
  val spelled: String = shape.baseArguments.joinToString(", ") { argument ->
    when (argument) {
      is ForwardSealedBaseArgument.Listed -> argument.parameter.csharpName
      is ForwardSealedBaseArgument.Closed ->
        classifier.classify(argument.type).sealedAsHandle().forwardPublicCsharpType()
    }
  }
  return "${base.nestedCsName()}<$spelled>"
}

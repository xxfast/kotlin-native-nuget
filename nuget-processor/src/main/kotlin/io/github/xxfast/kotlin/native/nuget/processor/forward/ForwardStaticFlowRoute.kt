package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunction
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import io.github.xxfast.kotlin.native.nuget.processor.exports.hasLegacyFlowReturn
import io.github.xxfast.kotlin.native.nuget.processor.exports.isForwardFlowType
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * ADR-230: an owner with no C# instance scope whose `Flow` family members bind as static
 * `KotlinFlow<T>` (and hot variants), each collection on an ad-hoc root scope. [stem] is the export
 * name the members' `_collect` / `_value` / acquire exports extend, minted by the planner beside
 * the sync members of the same owner, so the overload number is shared.
 */
internal sealed interface ForwardStaticFlowOwner {
  val stem: String

  /** A top-level function or property, on its ADR-007 file facade. */
  data class TopLevel(override val stem: String) : ForwardStaticFlowOwner

  /** An `object`: a static member of its `public static class`. */
  data class Object(override val stem: String, val obj: KSClassDeclaration) :
    ForwardStaticFlowOwner

  /**
   * A companion, default-named or not, reached through [owner] (`Hourglass.grains()`). A static
   * member of the owner class, or of `{Enum}Extensions` for an enum owner.
   */
  data class Companion(override val stem: String, val owner: KSClassDeclaration) :
    ForwardStaticFlowOwner

  /** An enum body's member: the receiver is the ordinal, a C# extension in `{Enum}Extensions`. */
  data class EnumMember(
    override val stem: String,
    val enum: KSClassDeclaration,
    val receiver: ForwardStaticSuspendReceiver.Enum,
  ) : ForwardStaticFlowOwner

  /**
   * ADR-238: an extension (`fun Depot.ticks(): Flow<Int>`): a C# extension on
   * `{Receiver}Extensions`, the receiver crossing as the leading slot, as ADR-229's suspend
   * extension's does.
   */
  data class Extension(
    override val stem: String,
    val receiver: ForwardStaticSuspendReceiver,
    val receiverType: KSType,
  ) : ForwardStaticFlowOwner

  /** A value class's member: the receiver is the underlying's wire, rewrapped in Kotlin. */
  data class ValueClass(
    override val stem: String,
    val valueClass: KSClassDeclaration,
    val underlying: ForwardStaticSuspendReceiver,
    /** ADR-228: the constructor the receiver is rewrapped through (`pkg.Decal<Nothing>`). */
    val construction: String? = null,
  ) : ForwardStaticFlowOwner
}

/**
 * ADR-230: one static flow member, a function or a property, with its owner. [function] is the
 * member both halves bind; a property's is its [ForwardStaticFlowGetter], a zero-argument
 * function re-read on every call, so one emitter and one translator serve both kinds.
 */
internal sealed interface ForwardStaticFlowMember {
  val owner: ForwardStaticFlowOwner
  val function: KSFunctionDeclaration

  data class Function(
    override val function: KSFunctionDeclaration,
    override val owner: ForwardStaticFlowOwner,
  ) : ForwardStaticFlowMember

  data class Property(
    val property: KSPropertyDeclaration,
    override val owner: ForwardStaticFlowOwner,
  ) : ForwardStaticFlowMember {
    override val function: KSFunctionDeclaration = ForwardStaticFlowGetter(property)
  }
}

/**
 * ADR-230: a static flow property read as the zero-argument function its getter is. Every
 * [KSDeclaration] question (name, package, KDoc, location, annotations) is the property's own, so
 * diagnostics and `@CName` ownership name the property. The Kotlin access spells no parentheses.
 */
internal class ForwardStaticFlowGetter(val property: KSPropertyDeclaration) :
  KSFunctionDeclaration, KSDeclaration by property {
  override val functionKind: FunctionKind = FunctionKind.MEMBER
  override val isAbstract: Boolean = false
  override val extensionReceiver: KSTypeReference? = null
  override val returnType: KSTypeReference = property.type
  override val parameters: List<KSValueParameter> = emptyList()
  override val declarations: Sequence<KSDeclaration> = emptySequence()
  override fun findOverridee(): KSDeclaration? = null
  override fun asMemberOf(containing: KSType): KSFunction =
    error("ADR-230: a static flow property getter is never read as a member of ${containing}")
  override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R = property.accept(visitor, data)
}

/**
 * ADR-230: whether [function] is a static flow member: a non-suspend member whose return is a
 * `Flow` family type. The planner keeps such a deferral routed on a static owner.
 */
internal fun KSFunctionDeclaration.isStaticFlowCandidate(): Boolean =
  Modifier.SUSPEND !in modifiers && hasLegacyFlowReturn()

/**
 * ADR-230: whether [this] owner is an arm of a sealed type, whose flow members the sealed route
 * binds on the arm itself (ADR-124 / ADR-157). Such an owner is never a static flow owner.
 */
internal fun KSClassDeclaration.isSealedArm(): Boolean = superTypes.any { reference ->
  val parent: KSClassDeclaration? = reference.resolve().declaration as? KSClassDeclaration
  parent != null && Modifier.SEALED in parent.modifiers
}

/** ADR-230: whether [prop] is a static flow property this route binds, element included. */
internal fun ForwardBridgeTypeClassifier.isStaticFlowProperty(
  prop: KSPropertyDeclaration,
): Boolean =
  prop.type.resolve().expandAliases().isForwardFlowType() &&
    !prop.isOptInRefused(exportMarkers) &&
    legacyRefusedFlowElement(prop.type.resolve()) == null

/**
 * ADR-230: the static flow members this module binds, the one selector the Kotlin emitter and the
 * C# translator read. A function a legacy refusal drops is left out here and named by
 * `warnRefusedLegacyRouteMembers`, which reads [staticFlowCandidates].
 */
internal fun ForwardCallablePlanCatalog.staticFlowRouteMembers(
  classifier: ForwardBridgeTypeClassifier,
): List<ForwardStaticFlowMember> = staticFlowCandidates().filter { member ->
  member.function.parameters.let { classifier.legacyRefusedParameter(it) == null } &&
    member.function.forwardMemberGenericRefusal() == null &&
    classifier.legacyRefusedReturn(member.function) == null
} + staticFlowCatalogMembers().filterIsInstance<ForwardStaticFlowMember.Property>() +
  staticFlowProperties

/**
 * ADR-230: every flow member the callable planner handed the static flow route, refused or not:
 * its functions, and a value class's properties (the one owner whose properties it walks).
 */
internal fun ForwardCallablePlanCatalog.staticFlowCandidates():
  List<ForwardStaticFlowMember.Function> =
  staticFlowCatalogMembers().filterIsInstance<ForwardStaticFlowMember.Function>()

private fun ForwardCallablePlanCatalog.staticFlowCatalogMembers(): List<ForwardStaticFlowMember> =
  entries.asSequence()
    .filterIsInstance<ForwardCallableCatalogEntry.Skipped>()
    .mapNotNull { entry ->
      val owner: ForwardStaticFlowOwner = entry.staticFlow ?: return@mapNotNull null
      when (val node = entry.node) {
        is KSFunctionDeclaration -> ForwardStaticFlowMember.Function(node, owner)
        is KSPropertyDeclaration -> ForwardStaticFlowMember.Property(node, owner)
        else -> null
      }
    }
    .toList()

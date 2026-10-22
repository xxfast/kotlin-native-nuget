package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * ADR-229: a receiver the static suspend route passes as the leading slot of an `_async` export.
 * Read once by the Kotlin export and the C# translator, so the slot both halves spell is one
 * decision. A receiver outside these three keeps its named skip.
 */
internal sealed interface ForwardStaticSuspendReceiver {
  /** The receiver's Kotlin spelling, fully qualified. */
  val kotlinType: String

  /** A class, `object` or sealed handle, borrowed for the call. Read before the launch. */
  data class Handle(
    val type: BridgeType.ObjectHandle,
    override val kotlinType: String,
    val nullable: Boolean,
  ) : ForwardStaticSuspendReceiver

  /** A non-null primitive or `String`, passed as it is. */
  data class Scalar(val type: BridgeType, override val kotlinType: String) :
    ForwardStaticSuspendReceiver

  /** An exported enum, crossing by ordinal (ADR-080). */
  data class Enum(val type: BridgeType.Enum, override val kotlinType: String) :
    ForwardStaticSuspendReceiver

  /**
   * ADR-225: a generic value class's own `T` underlying, crossing as its `NugetMarshal.Wrap<T>`
   * box (ADR-147). Read before the launch; C# releases the box once the export returns.
   */
  data class Box(
    val type: BridgeType.TypeParameter,
    val nullable: Boolean,
    /** ADR-198: the declared bounds the trampoline re-states, for a `T` with no erased spelling. */
    val trampolineBounds: List<String> = emptyList(),
  ) : ForwardStaticSuspendReceiver {
    override val kotlinType: String = "kotlin.Any"
  }
}

/**
 * ADR-229: an owner with no C# instance scope whose `suspend` members bind on the top-level suspend
 * route, one ad-hoc root scope per call. [stem] is the export name before `_async`, minted by the
 * planner beside the sync members of the same owner, so the overload number is shared.
 */
internal sealed interface ForwardStaticSuspendOwner {
  val stem: String

  /** An `object`: a static member of its `public static class`. */
  data class Object(override val stem: String, val obj: KSClassDeclaration) :
    ForwardStaticSuspendOwner

  /**
   * A companion, default-named or not, reached through [owner] (`Den.open()`), the plan route's
   * spelling. A static member of the owner class, or of `{Enum}Extensions` for an enum owner.
   */
  data class Companion(override val stem: String, val owner: KSClassDeclaration) :
    ForwardStaticSuspendOwner

  /** A suspend extension: a C# `this` extension in `{Receiver}Extensions`. */
  data class Extension(
    override val stem: String,
    val receiver: ForwardStaticSuspendReceiver,
    val receiverType: KSType,
  ) : ForwardStaticSuspendOwner

  /** An enum body's member: the receiver is the ordinal, a C# extension in `{Enum}Extensions`. */
  data class EnumMember(
    override val stem: String,
    val enum: KSClassDeclaration,
    val receiver: ForwardStaticSuspendReceiver.Enum,
  ) : ForwardStaticSuspendOwner

  /**
   * A value class's member: the receiver is the underlying's wire, rewrapped in Kotlin; an
   * instance method on the C# record struct.
   */
  data class ValueClass(
    override val stem: String,
    val valueClass: KSClassDeclaration,
    val underlying: ForwardStaticSuspendReceiver,
    /**
     * ADR-225: the constructor the receiver is rewrapped through, `pkg.Decal<Nothing>` for a
     * phantom `T` (no argument infers it); null spells the qualified name bare.
     */
    val construction: String? = null,
  ) : ForwardStaticSuspendOwner
}

/** ADR-229: one member the static suspend route binds, with the owner its receiver comes from. */
internal data class ForwardStaticSuspendMember(
  val function: KSFunctionDeclaration,
  val owner: ForwardStaticSuspendOwner,
)

/**
 * ADR-229: the receiver slot [type] crosses on, or null when the route has none for it (a
 * collection, an interface, a value class, a generic receiver). Null keeps the member a named skip.
 */
internal fun ForwardBridgeTypeClassifier.staticSuspendReceiver(
  type: KSType,
): ForwardStaticSuspendReceiver? {
  val expanded: KSType = type.expandAliases()
  if (expanded.arguments.isNotEmpty()) return null
  val kotlinType: String = expanded.declaration.qualifiedName?.asString() ?: return null
  val classified: BridgeType = classify(expanded).sealedAsHandle()
  val nullable: Boolean = classified is BridgeType.Nullable
  return when (val inner: BridgeType = classified.unwrapNullable()) {
    is BridgeType.ObjectHandle -> ForwardStaticSuspendReceiver.Handle(inner, kotlinType, nullable)
    is BridgeType.Enum -> if (nullable) null else ForwardStaticSuspendReceiver.Enum(inner, kotlinType)
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String ->
      if (nullable) null else ForwardStaticSuspendReceiver.Scalar(inner, kotlinType)

    BridgeType.Unit,
    BridgeType.Instant,
    BridgeType.Duration,
    BridgeType.Uuid,
    BridgeType.ByteArray,
    is BridgeType.Throwable,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
    is BridgeType.Collection,
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> null
  }
}

/**
 * ADR-229: the static suspend members this module binds, the one selector the Kotlin emitter, the
 * C# translator and the diagnostic walk read. A member a legacy refusal drops (a parameter, a
 * return, its own `T`) is left out here and named by `warnRefusedLegacyRouteMembers`, which reads
 * [staticSuspendCandidates].
 */
internal fun ForwardCallablePlanCatalog.staticSuspendRouteMembers(
  classifier: ForwardBridgeTypeClassifier,
): List<ForwardStaticSuspendMember> = staticSuspendCandidates().filter { member ->
  classifier.legacyRefusedParameter(member.function.parameters) == null &&
    member.function.forwardMemberGenericRefusal() == null &&
    classifier.legacyRefusedReturn(member.function) == null
}

/** ADR-229: every suspend member the planner handed the static suspend route, refused or not. */
internal fun ForwardCallablePlanCatalog.staticSuspendCandidates(): List<ForwardStaticSuspendMember> =
  entries.asSequence()
    .filterIsInstance<ForwardCallableCatalogEntry.Skipped>()
    .filter { entry -> entry.reason == ForwardPlanSkipReason.SUSPEND }
    .mapNotNull { entry ->
      val owner: ForwardStaticSuspendOwner = entry.staticSuspend ?: return@mapNotNull null
      val function: KSFunctionDeclaration =
        entry.node as? KSFunctionDeclaration ?: return@mapNotNull null
      ForwardStaticSuspendMember(function, owner)
    }
    .toList()

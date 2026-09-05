package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * The alias-expanded semantic type seen by the forward marshaller.  This deliberately contains
 * no KSP symbols: a plan must be complete before either source renderer sees it.
 */
internal sealed interface BridgeType {
  data object Unit : BridgeType

  data class Primitive(val kind: PrimitiveKind) : BridgeType

  data object Char : BridgeType

  data object String : BridgeType

  /**
   * ADR-076: `kotlin.time.Instant`. Wires as a single `INT64` of .NET ticks (100ns since
   * 0001-01-01T00:00:00 UTC); the public C# type is `System.DateTimeOffset` with `Offset` always
   * `TimeSpan.Zero`. Modelled like [Enum]: a semantic type with a required conversion on both
   * sides plus a helper requirement -- a new sealed variant, not a reuse of `Primitive(LONG)`
   * with a flag, so the compiler enumerates every `when` that must change.
   */
  data object Instant : BridgeType

  /**
   * ADR-103: `kotlin.time.Duration`. Wires as a single `INT64` of `System.TimeSpan` ticks (100ns,
   * signed, the full `Int64` domain); the public C# type is `System.TimeSpan`. The second
   * known-scalar branch beside [Instant], and modelled the same way: a sealed variant with a
   * required conversion on both sides plus a helper requirement, so the compiler enumerates every
   * `when` that must change.
   */
  data object Duration : BridgeType

  /**
   * ADR-107: `kotlin.Throwable` and its stdlib subtypes, at a **property getter** position only.
   * Wires as the `POINTER` to the very same `StableRef<NugetError>` envelope a *thrown* exception
   * writes into `errorOut`, so C# reconstructs it with `NugetErrorNative.BuildException` and the
   * ADR-028 cause chain and ADR-029 type mapping come for free; the public C# type is
   * `System.Exception`. A sealed variant, not an [ObjectHandle] with a flag, so the compiler
   * enumerates every `when` that has to decide about it.
   */
  data object Throwable : BridgeType

  /**
   * ADR-106: `kotlin.uuid.Uuid`. Wires exactly as [String] does -- the RFC 9562 lowercase hex-dash
   * text (`Uuid.toString()` / `Uuid.parse`, `Guid.ToString()` / `Guid.Parse`), a `STRING` slot at
   * inputs and a `POINTER` at results, with `Uuid?` riding the null pointer sentinel rather than
   * [Instant]'s has-value channel. The public C# type is `System.Guid`, a value type, so `Uuid?`
   * renders `Guid?` (`Nullable<Guid>`).
   */
  data object Uuid : BridgeType

  /**
   * @param qualifiedName Kotlin FQCN (used by the Kotlin export for `.entries[n]` / `.ordinal`).
   * @param csharpType Public C# type spelling. Cross-namespace enums (e.g. reverse-generated
   *   packages) need `global::Namespace.Name`; same-namespace simple names still work under
   *   `global::` too, so classification always prefers the fully-qualified form when a root
   *   namespace is available.
   */
  data class Enum(
    val qualifiedName: kotlin.String,
    val csharpType: kotlin.String = qualifiedName.substringAfterLast('.'),
  ) : BridgeType

  /**
   * @param qualifiedName Kotlin FQCN.
   * @param csharpType Public C# type spelling. Mirrors [Enum]'s qualification shape exactly:
   *   classification always renders `global::Namespace.Name` when a root namespace is available,
   *   including for a module-local type, because a two-namespace assembly (e.g. `TestLibrary`
   *   referencing `TestLibrary.Issue41`) otherwise fails `CS0246` in generated code the consumer
   *   never wrote (issue #41; ADR-066's narrower "module-local stays bare" rule was the defect).
   *   The default here is a bare simple name for test convenience only — the classifier is the
   *   sole production constructor that computes a public spelling.
   */
  data class ObjectHandle(
    val qualifiedName: kotlin.String,
    val csharpType: kotlin.String = qualifiedName.substringAfterLast('.'),
    /**
     * ADR-105: this handle names an ADR-009 sealed *base*, whose generated C# class is `abstract`.
     * The wire is identical to any other handle (a `StableRef` to the base-typed value, which the
     * `<name>_get_type` discriminator export reads back as `asStableRef<Base>()`), but the C#
     * reconstruction has to go through the generated `internal static Base FromHandle(IntPtr)`
     * discriminator instead of `new Base(handle)`, which is CS0144 on an abstract type.
     *
     * It also gates the collection *write* side: [isWrappableComponent] refuses a discriminated
     * handle, so a `var shapes: MutableList<Shape>` plans get-only under the existing ADR-075
     * read-only diagnostic rather than boxing a sealed base through the ADR-073 write path, which
     * no fixture has ever run for an abstract C# base.
     */
    val viaDiscriminator: kotlin.Boolean = false,
  ) : BridgeType

  /**
   * ADR-040: a Kotlin interface returned or accepted at an ordinary position. Wire-identical to
   * [ObjectHandle] (POINTER, STABLE_REF_TO_HANDLE/HANDLE_TO_STABLE_REF, OWNED_HANDLE +
   * DISPOSE_STABLE_REF for a result), differing only in the two places where the *public C#
   * spelling* ([csharpType], the projected interface `IFoo`) and the *construction expression*
   * ([backingType], the generated concrete handle-backed wrapper `Foo`) diverge.
   *
   * @param qualifiedName Kotlin FQCN of the interface declaration.
   * @param csharpType the public C# interface spelling (`IPet`, or `global::Namespace.IPet` for an
   *   admitted dependency-module interface), mirroring [ObjectHandle.csharpType]'s qualification
   *   rule.
   * @param backingType the generated concrete wrapper class spelling (`Pet`, or
   *   `global::Namespace.Pet`) used only in construction expressions (`new Pet(handle)`).
   */
  data class Interface(
    val qualifiedName: kotlin.String,
    val csharpType: kotlin.String = "I${qualifiedName.substringAfterLast('.')}",
    val backingType: kotlin.String = qualifiedName.substringAfterLast('.'),
  ) : BridgeType

  /**
   * ADR-088: a bound C# interface (an ADR-070 reverse-generated pure stub) at an ordinary forward
   * position. Unlike every other [BridgeType], the forward pipeline does **not** own this type's
   * C# spelling: [csharpType] is the ORIGINAL interface from the consumer's own dependency
   * (`global::Test.Menagerie.IFeedable`), read from the plugin's `bound-types.json` manifest, not
   * a re-projected `IIFeedable`. A value handed back by a forward API therefore composes with the
   * bound reverse API without a conversion layer.
   *
   * Wire: a GCHandle IntPtr, the reverse pipeline's reference wire, NOT a Kotlin StableRef. The
   * receiving side owns the handle and every crossing is a fresh transfer handle (ADR-086's
   * ownership rule), which is why this is its own variant rather than an [Interface] with a flag:
   * the lowering helpers on both sides are entirely different.
   *
   * @param qualifiedName the generated Kotlin stub's FQCN (`test.menagerie.IFeedable`).
   * @param csharpType the original C# spelling, always `global::`-qualified.
   * @param implementable ADR-085 admissibility: a `mint{Iface}Bridge` exists, so a plain Kotlin
   *   implementation can be handed OUT. False means parameter positions only; a return position
   *   takes the named `SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE` skip.
   */
  data class BoundInterface(
    val qualifiedName: kotlin.String,
    val csharpType: kotlin.String,
    val implementable: kotlin.Boolean = true,
  ) : BridgeType

  /**
   * @param underlyingPropertyName the value class's single primary-constructor parameter name,
   *   needed to unbox a value-class *result* at an ordinary (non-value-class-own) position: e.g.
   *   `Newsroom.code(): StoryCode` must call `.value` on the computed `StoryCode` before crossing
   *   the wire as its underlying wire value (ADR-014's "wraps/unwraps at the boundary").
   * @param csharpType mirrors [ObjectHandle.csharpType] (ADR-066): a bare simple name for a
   *   module-local declaration, `global::Namespace.Name` for an admitted dependency-module one.
   */
  data class ValueClass(
    val qualifiedName: kotlin.String,
    val underlying: BridgeType,
    val underlyingPropertyName: kotlin.String = "value",
    val csharpType: kotlin.String = qualifiedName.substringAfterLast('.'),
    /**
     * ADR-108: the classified type arguments of this value class's declaration, in source order;
     * empty for a non-generic value class or a star projection. Read at exactly one seam, the
     * planner's `kotlin.Result<T>` return-position rewrite.
     */
    val typeArguments: List<BridgeType> = emptyList(),
  ) : BridgeType

  data class Collection(
    val kind: CollectionKind,
    val element: BridgeType? = null,
    val key: BridgeType? = null,
    val value: BridgeType? = null,
  ) : BridgeType

  data class Nullable(val type: BridgeType) : BridgeType

  /**
   * Protocols remain on named legacy routes until their dedicated planning adapters exist.
   *
   * @param sealedHandle ADR-105: for a `sealed helper <fqn>` protocol over an exported sealed
   *   *class*, the [ObjectHandle] this type would be if the position could bridge it. Carried so a
   *   position that CAN (today: a property, per ADR-105 scope (c)) unwraps it without the
   *   classifier having to know which position it is classifying for. `null` for every other
   *   protocol, and for a sealed *interface* or an out-of-scope sealed class, neither of which the
   *   ADR-009 renderer gives a `FromHandle` discriminator to reconstruct through.
   */
  data class SpecializedProtocol(
    val name: kotlin.String,
    val sealedHandle: ObjectHandle? = null,
  ) : BridgeType

  /** A planning bug: raw KSP types must not leak beyond classification. */
  data class RawKSType(val rendered: kotlin.String) : BridgeType

  /**
   * A type deliberately outside the ordinary synchronous bridgeable subset.
   *
   * @param isUnexportedDependency ADR-066: true when [rendered] is a cross-module (klib)
   *   declaration's qualified name that the reachability closure discovered but did not admit
   *   (its package failed [io.github.xxfast.kotlin.native.nuget.processor.forward
   *   .ForwardBridgeTypeContext.exportedObjectHandles]'s membership test for scope reasons, not
   *   because the shape itself is unsupported). Lets the planner route this specific case to
   *   `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` instead of the generic `SKIPPED_UNSUPPORTED_TYPE`.
   * @param isActualTypeAliasTarget ADR-074: true when [rendered] is the qualified name (or simple
   *   name, if unqualified) of an `actual typealias`'s erased target that the forward direction
   *   cannot export (a platform-library type, a stdlib type, an out-of-scope package, or a
   *   parameterized target — v1 admits only a redirect to a plain class). Lets the planner route
   *   this case to its own `SKIPPED_ACTUAL_TYPEALIAS_TARGET` diagnostic, whose hint differs from
   *   [isUnexportedDependency]'s: a platform library can never be brought into scope with
   *   `include(...)`.
   * @param actualTypeAliasExpectName ADR-074: the `expect` class's qualified name, when
   *   [isActualTypeAliasTarget] is true; carried for the diagnostic hint's "the actual typealias
   *   for `<expect name>` resolves to..." message.
   * @param isUndeclaredEnum true when [rendered] is an `enum class` that no route ever declares as
   *   a C# enum, so a member typed with it would otherwise be spelled as a reference to a type
   *   nothing emits (the CS0426/CS0234 class of consumer failure). Two shapes reach it: a *nested*
   *   enum (module-local or cross-module — only top-level enums are declared, and the reachability
   *   closure refuses to admit a nested dependency enum precisely so it lands here), and a
   *   module-local top-level enum whose package fell outside the export scope. Distinct from
   *   [isUnexportedDependency], whose `include(...)` hint is wrong for the nested case: no export
   *   scope can make a nested enum declarable.
   */
  data class Unsupported(
    val rendered: kotlin.String,
    val reason: kotlin.String,
    val isUnexportedDependency: kotlin.Boolean = false,
    val isActualTypeAliasTarget: kotlin.Boolean = false,
    val actualTypeAliasExpectName: kotlin.String? = null,
    val isUndeclaredEnum: kotlin.Boolean = false,
  ) : BridgeType

  /** A collection whose component type was lost during classification. */
  data class RawCollection(val kind: CollectionKind) : BridgeType
}

internal enum class PrimitiveKind {
  BOOLEAN,
  BYTE,
  UBYTE,
  SHORT,
  USHORT,
  INT,
  UINT,
  LONG,
  ULONG,
  FLOAT,
  DOUBLE,
}

internal enum class CollectionKind { LIST, MUTABLE_LIST, MAP, MUTABLE_MAP, SET, MUTABLE_SET }

/** The direction of a value over the language boundary, independent of declaration syntax. */
internal enum class ForwardFlow { INTO_KOTLIN, OUT_OF_KOTLIN }

/** The native ABI passing mode, independent of whether the declaration was a method or property. */
internal enum class ForwardPassing { VALUE, OUT, IN_OUT }

/** The number of native invocations that implement a single public callable. */
internal enum class ForwardEvaluation(val nativeCallCount: Int) {
  EXACTLY_ONCE(1),
  LEGACY_TWO_CALL(2),
}

/** Who is responsible for the resource represented by a transferred value. */
internal enum class ForwardOwnership { BORROWED, OWNED_HANDLE, MATERIALIZED }

/** The C ABI wire representation. POINTER is valid only when the typed transfer explains it. */
internal enum class ForwardAbiWireType {
  VOID,
  BOOLEAN,
  INT8,
  UINT8,
  INT16,
  UINT16,
  INT32,
  UINT32,
  INT64,
  UINT64,
  FLOAT32,
  FLOAT64,
  CHAR16,
  STRING,
  POINTER,
  UNKNOWN,
}

internal enum class ForwardAbiDirection { IN, OUT, IN_OUT }

/** A source-neutral conversion required to move a semantic [BridgeType] across its wire value. */
internal enum class ForwardConversion {
  DIRECT,
  STRING_TO_UTF8,
  UTF8_TO_STRING,
  ENUM_TO_ORDINAL,
  ORDINAL_TO_ENUM,
  HANDLE_TO_STABLE_REF,
  STABLE_REF_TO_HANDLE,
  BOX_VALUE_CLASS,
  UNBOX_VALUE_CLASS,
  COLLECTION_TO_HANDLE,
  HANDLE_TO_COLLECTION,

  /** ADR-076: Kotlin `Instant` -> .NET ticks (`Long`), out of Kotlin. */
  INSTANT_TO_TICKS,

  /** ADR-076: .NET ticks (`Long`) -> Kotlin `Instant`, into Kotlin. */
  TICKS_TO_INSTANT,

  /** ADR-103: Kotlin `Duration` -> `System.TimeSpan` ticks (`Long`), out of Kotlin. */
  DURATION_TO_TICKS,

  /** ADR-103: `System.TimeSpan` ticks (`Long`) -> Kotlin `Duration`, into Kotlin. */
  TICKS_TO_DURATION,

  /** ADR-106: Kotlin `Uuid` -> its RFC 9562 hex-dash `String`, out of Kotlin. */
  UUID_TO_STRING,

  /** ADR-106: an RFC 9562 hex-dash `String` -> Kotlin `Uuid`, into Kotlin. */
  STRING_TO_UUID,

  /** ADR-088: an incoming transfer GCHandle -> the Kotlin value, via `nuget{Iface}Value`. */
  GC_HANDLE_TO_BOUND_VALUE,

  /** ADR-088: a Kotlin value -> a fresh transfer GCHandle, via `nugetHandleOut`. */
  BOUND_VALUE_TO_GC_HANDLE,
}

internal enum class ForwardHelperRequirement {
  UTF8,
  ENUM_ORDINAL,
  STABLE_REF,
  VALUE_CLASS,
  COLLECTION,
  ERROR_TRANSFER,

  /** ADR-076: the generated `toDotNetTicks()`/`instantFromDotNetTicks()` conversion pair. */
  INSTANT,

  /** ADR-103: the generated `toDotNetTicks()`/`durationFromDotNetTicks()` conversion pair. */
  DURATION,

  /**
   * ADR-106: `kotlin.uuid.Uuid`'s own stdlib surface (`toString()` / `Uuid.parse`). No generated
   * helper function exists for it -- the generated Kotlin spells `kotlin.uuid.Uuid` in full -- so
   * this requirement exists only so the validator's conversion/helper pairing check holds, exactly
   * as it does for INSTANT/DURATION.
   */
  UUID,

  /**
   * ADR-088: the reverse pipeline's own per-interface helpers (`nuget{Iface}Value`,
   * `nugetHandleOut` + the interface's `dupHandleFn`). Unlike every other requirement here, the
   * forward pipeline does not emit these; they already exist in the same compilation, generated by
   * `nugetGenerateBindings`. Tracked anyway so the plan validator's
   * `requiredConversion.helper() in helperRequirements` check stays uniform.
   */
  BOUND_INTERFACE,
}

internal enum class ForwardCleanupKind { DISPOSE_STABLE_REF, FREE_UTF8, RELEASE_HANDLE }

internal data class ForwardTransfer(
  val subject: String,
  val type: BridgeType,
  val flow: ForwardFlow,
  val passing: ForwardPassing,
  val ownership: ForwardOwnership?,
  val conversion: ForwardConversion?,
)

internal data class ForwardAbiParameter(
  val name: String,
  val wireType: ForwardAbiWireType,
  val direction: ForwardAbiDirection,
  val transfer: ForwardTransfer,
)

internal data class ForwardNativeCall(
  val exportName: String,
  val result: ForwardAbiWireType,
  val parameters: List<ForwardAbiParameter>,
)

internal data class ForwardPublicParameter(val name: String, val type: BridgeType)

internal data class ForwardPublicSignature(
  val name: String,
  val parameters: List<ForwardPublicParameter>,
  val result: BridgeType,
  /**
   * ADR-090: the C# member modifiers, computed at planning time and carried on the plan.
   *
   * A planned entry does not retain its `KSNode`, and once ordinary-class methods are emitted off
   * the catalog (overload numbering makes the plan symbol per-declaration underivable) the C#
   * translator has no declaration to read `override` / `open` off. Only CLASS-origin class methods
   * and properties set these; every other origin leaves them false.
   */
  val isOverride: Boolean = false,
  val isVirtual: Boolean = false,
)

/** Symbol-level invocation information. Renderers decide syntax later. */
internal enum class ForwardCallableOrigin {
  CLASS,
  EXTENSION,
  TOP_LEVEL,
  OBJECT,
  COMPANION,
  CONSTRUCTOR,
  COPY,

  /**
   * Value-class constructors, computed properties, and methods. Reconstruction of the value class
   * from its underlying wire value is owned by the VALUE_CLASS emitters (ADR-014 / ADR-035).
   */
  VALUE_CLASS,
}

internal data class ForwardInvocation(
  val symbol: String,
  /**
   * Optional free-form slot. For [ForwardCallableOrigin.VALUE_CLASS] constructors this is the
   * underlying property name used to unbox the constructed value (`CatId(...).id`).
   */
  val receiver: String? = null,
  val origin: ForwardCallableOrigin = ForwardCallableOrigin.CLASS,
  /** Kotlin expression before the final method name for static/object calls. */
  val target: String? = null,
  /**
   * The declared Kotlin member name, when the symbol's last segment is not it. ADR-082's overload
   * numbering suffixes the *symbol* (`ChartId.describe_2`) to keep catalog keys unique; the Kotlin
   * call site still has to say `describe`.
   */
  val member: String? = null,
  /**
   * ADR-108: the declared Kotlin result was a `kotlin.Result<T>` that the plan lowered to `T`, so
   * the Kotlin export appends `.getOrThrow()` to the invocation, inside the `try` the exception
   * channel already wraps it in. A `Result.failure(e)` then reaches C# exactly as `throw e` would.
   */
  val unwrapsKotlinResult: Boolean = false,
)

internal data class ForwardResultConvention(
  val wireType: ForwardAbiWireType,
  val transfer: ForwardTransfer,
)

/** A declarative conversion step, intentionally not KotlinPoet or CIR source text. */
internal data class ForwardValueOperation(
  val subject: String,
  val type: BridgeType,
  val conversion: ForwardConversion,
)

internal data class ForwardCleanup(
  val subject: String,
  val kind: ForwardCleanupKind,
)

/**
 * Complete, emission-neutral description of one ordinary synchronous forward callable.
 *
 * [nativeExports] and [nativeImports] deliberately remain separate projections: later phases
 * build both from this plan and compare them, rather than inferring one side from the other.
 */
internal data class ForwardCallablePlan(
  val invocation: ForwardInvocation,
  val publicSignature: ForwardPublicSignature,
  val evaluation: ForwardEvaluation,
  val nativeExports: List<ForwardNativeCall>,
  val nativeImports: List<ForwardNativeCall>,
  val result: ForwardResultConvention,
  val errorSlot: ForwardAbiParameter? = null,
  val liftOperations: List<ForwardValueOperation> = emptyList(),
  val lowerOperations: List<ForwardValueOperation> = emptyList(),
  val cleanup: List<ForwardCleanup> = emptyList(),
  val helperRequirements: Set<ForwardHelperRequirement> = emptySet(),
) {
  fun validate(): ForwardCallablePlan {
    ForwardCallablePlanValidator.validate(this)
    return this
  }
}

internal object ForwardCallablePlanValidator {
  fun validate(plan: ForwardCallablePlan) {
    require(plan.invocation.symbol.isNotBlank()) { "Forward plan invocation symbol must not be blank" }
    require(plan.publicSignature.name.isNotBlank()) { "Forward plan public signature name must not be blank" }
    validateCallCount(plan)
    require(plan.nativeExports == plan.nativeImports) {
      "Forward plan ${plan.publicSignature.name} has different native export and import ABI projections"
    }

    validateType(plan.publicSignature.result, "public result")
    plan.publicSignature.parameters.forEach { parameter ->
      require(parameter.name.isNotBlank()) {
        "Forward plan ${plan.publicSignature.name} has a blank public parameter name"
      }
      validateType(parameter.type, "public parameter ${parameter.name}")
    }
    plan.nativeExports.forEach { call -> validateCall(plan, call) }
    validateWireType(plan.publicSignature.name, "result", plan.result.wireType)
    validateTransfer(plan, plan.result.transfer)
    plan.errorSlot?.let { slot ->
      require(slot.direction == ForwardAbiDirection.OUT) {
        "Forward plan ${plan.publicSignature.name} error slot ${slot.name} must be OUT"
      }
      require(slot.wireType == ForwardAbiWireType.POINTER) {
        "Forward plan ${plan.publicSignature.name} error slot ${slot.name} must use POINTER wire type"
      }
      validateTransfer(plan, slot.transfer)
    }
    (plan.liftOperations + plan.lowerOperations).forEach { operation ->
      require(operation.subject.isNotBlank()) {
        "Forward plan ${plan.publicSignature.name} has a conversion operation without a subject"
      }
      validateType(operation.type, "conversion operation ${operation.subject}")
    }
  }

  private fun validateCallCount(plan: ForwardCallablePlan) {
    val expected: Int = plan.evaluation.nativeCallCount
    require(plan.nativeExports.size == expected && plan.nativeImports.size == expected) {
      "Forward plan ${plan.publicSignature.name} has invalid call count: ${plan.evaluation} requires " +
          "$expected export/import calls, got ${plan.nativeExports.size}/${plan.nativeImports.size}"
    }
  }

  private fun validateCall(plan: ForwardCallablePlan, call: ForwardNativeCall) {
    require(call.exportName.isNotBlank()) {
      "Forward plan ${plan.publicSignature.name} has a native call without an export name"
    }
    validateWireType(plan.publicSignature.name, "native result ${call.exportName}", call.result)
    call.parameters.forEach { parameter ->
      require(parameter.name.isNotBlank()) {
        "Forward plan ${plan.publicSignature.name} has a native parameter without a name"
      }
      require(parameter.direction.passing() == parameter.transfer.passing) {
        "Forward plan ${plan.publicSignature.name} parameter ${parameter.name} has ABI direction " +
            "${parameter.direction} but semantic passing ${parameter.transfer.passing}"
      }
      validateWireType(plan.publicSignature.name, "parameter ${parameter.name}", parameter.wireType)
      validateTransfer(plan, parameter.transfer)
    }
  }

  private fun validateTransfer(plan: ForwardCallablePlan, transfer: ForwardTransfer) {
    require(transfer.subject.isNotBlank()) {
      "Forward plan ${plan.publicSignature.name} has a transfer without a subject"
    }
    validateType(transfer.type, "transfer ${transfer.subject}")
    requireNotNull(transfer.ownership) {
      "Forward plan ${plan.publicSignature.name} transfer ${transfer.subject} is missing ownership"
    }
    val requiredConversion: ForwardConversion? = requiredConversion(transfer.type, transfer.flow)
    if (requiredConversion != null) {
      require(transfer.conversion == requiredConversion) {
        "Forward plan ${plan.publicSignature.name} transfer ${transfer.subject} is missing conversion " +
            "$requiredConversion"
      }
      require(requiredConversion.helper() in plan.helperRequirements) {
        "Forward plan ${plan.publicSignature.name} transfer ${transfer.subject} is missing helper " +
            requiredConversion.helper()
      }
    } else {
      require(transfer.conversion == null || transfer.conversion == ForwardConversion.DIRECT) {
        "Forward plan ${plan.publicSignature.name} transfer ${transfer.subject} has an unnecessary conversion"
      }
    }
    if (transfer.ownership == ForwardOwnership.OWNED_HANDLE) {
      require(plan.cleanup.any { cleanup -> cleanup.subject == transfer.subject }) {
        "Forward plan ${plan.publicSignature.name} owned transfer ${transfer.subject} is missing cleanup"
      }
    }
  }

  private fun validateWireType(planName: String, position: String, wireType: ForwardAbiWireType) {
    require(wireType != ForwardAbiWireType.UNKNOWN) {
      "Forward plan $planName $position uses an unknown wire type"
    }
  }

  private fun validateType(type: BridgeType, position: String) {
    when (type) {
      BridgeType.Unit, BridgeType.Char, BridgeType.String, BridgeType.Instant, BridgeType.Duration,
        // ADR-107: valid in a plan, at the property-getter position the property planner admits.
      BridgeType.Throwable,
        // ADR-106: valid at every ordinary position, over the String wire.
      BridgeType.Uuid,
      is BridgeType.Primitive, is BridgeType.Enum, is BridgeType.ObjectHandle,
      is BridgeType.Interface, is BridgeType.BoundInterface,
        -> Unit

      is BridgeType.ValueClass -> validateType(type.underlying, "$position value-class underlying type")
      is BridgeType.Nullable -> {
        require(type.type !is BridgeType.Nullable && type.type != BridgeType.Unit) {
          "Forward plan $position has an invalid nullable type"
        }
        validateType(type.type, "$position nullable type")
      }

      is BridgeType.Collection -> validateCollection(type, position)
      is BridgeType.RawCollection -> error("Forward plan $position contains raw ${type.kind} collection")
      is BridgeType.RawKSType -> error("Forward plan $position contains raw KSType ${type.rendered}")
      is BridgeType.SpecializedProtocol -> error(
        "Forward plan $position uses specialized protocol ${type.name}; it requires a named legacy route"
      )

      is BridgeType.Unsupported -> error("Forward plan $position has unsupported type ${type.rendered}: ${type.reason}")
    }
  }

  private fun validateCollection(type: BridgeType.Collection, position: String) {
    val isMap: Boolean = type.kind == CollectionKind.MAP || type.kind == CollectionKind.MUTABLE_MAP
    if (isMap) {
      requireNotNull(type.key) { "Forward plan $position has raw ${type.kind} key type" }
      requireNotNull(type.value) { "Forward plan $position has raw ${type.kind} value type" }
      require(type.element == null) { "Forward plan $position has invalid ${type.kind} element type" }
      validateType(type.key, "$position collection key")
      validateType(type.value, "$position collection value")
    } else {
      requireNotNull(type.element) { "Forward plan $position has raw ${type.kind} element type" }
      require(type.key == null && type.value == null) {
        "Forward plan $position has invalid ${type.kind} key or value type"
      }
      validateType(type.element, "$position collection element")
    }
  }

  private fun requiredConversion(type: BridgeType, flow: ForwardFlow): ForwardConversion? = when (
    type.unwrapNullable()
  ) {
    BridgeType.String -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.STRING_TO_UTF8
    } else {
      ForwardConversion.UTF8_TO_STRING
    }

    is BridgeType.Enum -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.ORDINAL_TO_ENUM
    } else {
      ForwardConversion.ENUM_TO_ORDINAL
    }

    is BridgeType.ObjectHandle, is BridgeType.Interface -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.HANDLE_TO_STABLE_REF
    } else {
      ForwardConversion.STABLE_REF_TO_HANDLE
    }

    // ADR-088: a GCHandle, never a StableRef — the two directions are the reverse pipeline's
    // helpers, not the forward StableRef pair.
    is BridgeType.BoundInterface -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.GC_HANDLE_TO_BOUND_VALUE
    } else {
      ForwardConversion.BOUND_VALUE_TO_GC_HANDLE
    }

    is BridgeType.ValueClass -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.BOX_VALUE_CLASS
    } else {
      ForwardConversion.UNBOX_VALUE_CLASS
    }

    is BridgeType.Collection -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.HANDLE_TO_COLLECTION
    } else {
      ForwardConversion.COLLECTION_TO_HANDLE
    }

    BridgeType.Instant -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.TICKS_TO_INSTANT
    } else {
      ForwardConversion.INSTANT_TO_TICKS
    }

    BridgeType.Duration -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.TICKS_TO_DURATION
    } else {
      ForwardConversion.DURATION_TO_TICKS
    }

    BridgeType.Uuid -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.STRING_TO_UUID
    } else {
      ForwardConversion.UUID_TO_STRING
    }

    else -> null
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun ForwardAbiDirection.passing(): ForwardPassing = when (this) {
    ForwardAbiDirection.IN -> ForwardPassing.VALUE
    ForwardAbiDirection.OUT -> ForwardPassing.OUT
    ForwardAbiDirection.IN_OUT -> ForwardPassing.IN_OUT
  }

  private fun ForwardConversion.helper(): ForwardHelperRequirement = when (this) {
    ForwardConversion.STRING_TO_UTF8,
    ForwardConversion.UTF8_TO_STRING,
      -> ForwardHelperRequirement.UTF8

    ForwardConversion.ENUM_TO_ORDINAL,
    ForwardConversion.ORDINAL_TO_ENUM,
      -> ForwardHelperRequirement.ENUM_ORDINAL

    ForwardConversion.HANDLE_TO_STABLE_REF,
    ForwardConversion.STABLE_REF_TO_HANDLE,
      -> ForwardHelperRequirement.STABLE_REF

    ForwardConversion.BOX_VALUE_CLASS,
    ForwardConversion.UNBOX_VALUE_CLASS,
      -> ForwardHelperRequirement.VALUE_CLASS

    ForwardConversion.COLLECTION_TO_HANDLE,
    ForwardConversion.HANDLE_TO_COLLECTION,
      -> ForwardHelperRequirement.COLLECTION

    ForwardConversion.INSTANT_TO_TICKS,
    ForwardConversion.TICKS_TO_INSTANT,
      -> ForwardHelperRequirement.INSTANT

    ForwardConversion.DURATION_TO_TICKS,
    ForwardConversion.TICKS_TO_DURATION,
      -> ForwardHelperRequirement.DURATION

    ForwardConversion.UUID_TO_STRING,
    ForwardConversion.STRING_TO_UUID,
      -> ForwardHelperRequirement.UUID

    ForwardConversion.GC_HANDLE_TO_BOUND_VALUE,
    ForwardConversion.BOUND_VALUE_TO_GC_HANDLE,
      -> ForwardHelperRequirement.BOUND_INTERFACE

    ForwardConversion.DIRECT -> error("Direct conversion does not require a helper")
  }
}

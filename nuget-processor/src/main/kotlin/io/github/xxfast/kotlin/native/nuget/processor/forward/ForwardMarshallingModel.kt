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

  data class Enum(val qualifiedName: kotlin.String) : BridgeType

  data class ObjectHandle(val qualifiedName: kotlin.String) : BridgeType

  data class ValueClass(val qualifiedName: kotlin.String, val underlying: BridgeType) : BridgeType

  data class Collection(
    val kind: CollectionKind,
    val element: BridgeType? = null,
    val key: BridgeType? = null,
    val value: BridgeType? = null,
  ) : BridgeType

  data class Nullable(val type: BridgeType) : BridgeType

  /** Protocols remain on named legacy routes until their dedicated planning adapters exist. */
  data class SpecializedProtocol(val name: kotlin.String) : BridgeType

  /** A planning bug: raw KSP types must not leak beyond classification. */
  data class RawKSType(val rendered: kotlin.String) : BridgeType

  /** A type deliberately outside the ordinary synchronous bridgeable subset. */
  data class Unsupported(val rendered: kotlin.String, val reason: kotlin.String) : BridgeType

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
}

internal enum class ForwardHelperRequirement {
  UTF8,
  ENUM_ORDINAL,
  STABLE_REF,
  VALUE_CLASS,
  COLLECTION,
  ERROR_TRANSFER,
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
}

internal data class ForwardInvocation(
  val symbol: String,
  val receiver: String? = null,
  val origin: ForwardCallableOrigin = ForwardCallableOrigin.CLASS,
  /** Kotlin expression before the final method name for static/object calls. */
  val target: String? = null,
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
      require(parameter.name.isNotBlank()) { "Forward plan ${plan.publicSignature.name} has a blank public parameter name" }
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
      BridgeType.Unit, BridgeType.Char, BridgeType.String, is BridgeType.Primitive, is BridgeType.Enum,
      is BridgeType.ObjectHandle,
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

    is BridgeType.ObjectHandle -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.HANDLE_TO_STABLE_REF
    } else {
      ForwardConversion.STABLE_REF_TO_HANDLE
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

    else -> null
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun ForwardAbiDirection.passing(): ForwardPassing = when (this) {
    ForwardAbiDirection.IN -> ForwardPassing.VALUE
    ForwardAbiDirection.OUT -> ForwardPassing.OUT
    ForwardAbiDirection.IN_OUT -> ForwardPassing.IN_OUT
  }

  private fun ForwardConversion.helper(): ForwardHelperRequirement = when (this) {
    ForwardConversion.STRING_TO_UTF8, ForwardConversion.UTF8_TO_STRING -> ForwardHelperRequirement.UTF8
    ForwardConversion.ENUM_TO_ORDINAL, ForwardConversion.ORDINAL_TO_ENUM -> ForwardHelperRequirement.ENUM_ORDINAL
    ForwardConversion.HANDLE_TO_STABLE_REF, ForwardConversion.STABLE_REF_TO_HANDLE -> ForwardHelperRequirement.STABLE_REF
    ForwardConversion.BOX_VALUE_CLASS, ForwardConversion.UNBOX_VALUE_CLASS -> ForwardHelperRequirement.VALUE_CLASS
    ForwardConversion.COLLECTION_TO_HANDLE, ForwardConversion.HANDLE_TO_COLLECTION -> ForwardHelperRequirement.COLLECTION
    ForwardConversion.DIRECT -> error("Direct conversion does not require a helper")
  }
}

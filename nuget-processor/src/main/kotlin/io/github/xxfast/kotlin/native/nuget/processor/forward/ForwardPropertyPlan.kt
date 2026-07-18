package io.github.xxfast.kotlin.native.nuget.processor.forward

/** The declaration position determines the receiver and export naming, not marshalling semantics. */
internal enum class ForwardPropertyPosition { CLASS, TOP_LEVEL, EXTENSION, COMPANION }

internal sealed interface ForwardPropertyReceiver {
  data class Handle(val owner: String) : ForwardPropertyReceiver
  data class Value(val type: BridgeType) : ForwardPropertyReceiver
  data class Static(val owner: String?) : ForwardPropertyReceiver
}

internal sealed interface ForwardPropertyGetter {
  data class Direct(val call: ForwardNativeCall) : ForwardPropertyGetter

  /** ADR-002: inspect presence, then read the value only when present. */
  data class LegacyTwoCall(
    val presence: ForwardNativeCall,
    val value: ForwardNativeCall,
  ) : ForwardPropertyGetter
}

internal sealed interface ForwardPropertySetter {
  data class Direct(val call: ForwardNativeCall) : ForwardPropertySetter

  /** Nullable values dispatch to exactly one of these exports. */
  data class NullableDispatch(
    val value: ForwardNativeCall,
    val nullValue: ForwardNativeCall,
  ) : ForwardPropertySetter
}

/**
 * Source-neutral ABI description for one Kotlin property.  It is deliberately separate from
 * [ForwardCallablePlan]: a nullable setter has two mutually-exclusive exports, whereas a normal
 * callable has one ordered invocation sequence.
 */
internal data class ForwardPropertyPlan(
  val symbol: String,
  val position: ForwardPropertyPosition,
  val receiver: ForwardPropertyReceiver,
  val kotlinName: String,
  val publicName: String,
  val type: BridgeType,
  val getter: ForwardPropertyGetter,
  val setter: ForwardPropertySetter? = null,
  val helperRequirements: Set<ForwardHelperRequirement> = emptySet(),
) {
  fun validate(): ForwardPropertyPlan {
    require(symbol.isNotBlank()) { "Forward property plan symbol must not be blank" }
    require(kotlinName.isNotBlank() && publicName.isNotBlank()) {
      "Forward property plan $symbol has a blank name"
    }
    validateType(type)
    getter.calls().forEach(::validateCall)
    setter?.calls()?.forEach(::validateCall)
    if (getter is ForwardPropertyGetter.LegacyTwoCall) {
      require(type is BridgeType.Nullable && type.type is BridgeType.Primitive) {
        "Forward property plan $symbol uses LegacyTwoCall for non-nullable primitive $type"
      }
      require(getter.presence.result == ForwardAbiWireType.BOOLEAN) {
        "Forward property plan $symbol LegacyTwoCall presence export must return BOOLEAN"
      }
    }
    if (setter is ForwardPropertySetter.NullableDispatch) {
      require(type is BridgeType.Nullable && type.type is BridgeType.Primitive) {
        "Forward property plan $symbol uses nullable setter dispatch for $type"
      }
    }
    return this
  }

  fun calls(): List<ForwardNativeCall> = getter.calls() + (setter?.calls() ?: emptyList())

  private fun validateCall(call: ForwardNativeCall) {
    require(call.exportName.isNotBlank()) { "Forward property plan $symbol has a blank export" }
    require(call.parameters.lastOrNull()?.name == "errorOut") {
      "Forward property plan $symbol export ${call.exportName} must end in errorOut"
    }
  }

  private fun validateType(type: BridgeType) {
    when (type) {
      BridgeType.Unit, BridgeType.String, is BridgeType.Primitive, is BridgeType.Enum,
      is BridgeType.ObjectHandle -> Unit
      is BridgeType.Collection -> require(type.kind == CollectionKind.LIST || type.kind == CollectionKind.MUTABLE_LIST) {
        "Forward property plan $symbol has unsupported planned collection ${type.kind}"
      }
      is BridgeType.Nullable -> validateType(type.type)
      else -> error("Forward property plan $symbol has unsupported type $type")
    }
  }
}

internal fun ForwardPropertyGetter.calls(): List<ForwardNativeCall> = when (this) {
  is ForwardPropertyGetter.Direct -> listOf(call)
  is ForwardPropertyGetter.LegacyTwoCall -> listOf(presence, value)
}

internal fun ForwardPropertySetter.calls(): List<ForwardNativeCall> = when (this) {
  is ForwardPropertySetter.Direct -> listOf(call)
  is ForwardPropertySetter.NullableDispatch -> listOf(value, nullValue)
}

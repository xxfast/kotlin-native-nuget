package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * The declaration position determines the receiver and export naming, not marshalling semantics.
 *
 * [OBJECT] (ADR-075's positions list, ROADMAP Phase 4): an `object`'s own property, a static
 * position planned by the same `propertyPlan` [COMPANION] uses, so getter/setter independence
 * applies unchanged. It is a position of its own rather than a reuse of [COMPANION] because no
 * legacy adapter re-emits a flow, state-flow or lambda typed property for a static owner, so it
 * must NOT inherit the silence `ForwardPropertyPlanner.recordDropped` grants a class property.
 */
internal enum class ForwardPropertyPosition { CLASS, TOP_LEVEL, EXTENSION, COMPANION, OBJECT }

/** ADR-157: the catalog-key and Kotlin-side member name of a boxed enum arm's `Value`. */
internal const val ENUM_ARM_VALUE_MEMBER: String = "value"

internal sealed interface ForwardPropertyReceiver {
  data class Handle(val owner: String) : ForwardPropertyReceiver
  data class Value(val type: BridgeType) : ForwardPropertyReceiver
  data class Static(val owner: String?) : ForwardPropertyReceiver

  /**
   * ADR-157: the boxed enum arm's `Value` getter. The handle is read back as the sealed [base] --
   * the same type the ADR-009 discriminator reads it as -- and downcast to the arm's [enum], with
   * no member access after it: the property *is* the receiver. Every other receiver appends the
   * Kotlin property name, which an enum entry has nothing to answer with.
   */
  data class EnumArm(val base: String, val enum: String) : ForwardPropertyReceiver
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
  /** ADR-150: the author's KDoc, parsed once at planning time. `@param` never applies here. */
  val doc: ForwardKdoc? = null,
) {
  fun validate(): ForwardPropertyPlan {
    require(symbol.isNotBlank()) { "Forward property plan symbol must not be blank" }
    require(kotlinName.isNotBlank() && publicName.isNotBlank()) {
      "Forward property plan $symbol has a blank name"
    }
    validateType(type)
    getter.calls().forEach(::validateCall)
    setter?.calls()?.forEach(::validateCall)
    calls().flatMap { call -> call.parameters }.forEach(::validateHelper)
    // ADR-076: Instant shares the nullable-primitive LegacyTwoCall/NullableDispatch shape exactly.
    // ADR-079: so does a value class whose underlying is a Primitive or an Enum -- neither wire
    // has a spare null, so both need the out-of-band has-value channel.
    val nullableInner: BridgeType? = (type as? BridgeType.Nullable)?.type
    val underlying: BridgeType? = (nullableInner as? BridgeType.ValueClass)?.underlying
    // ADR-080: a bare nullable enum rides the same shapes, its `int` ordinal in the value slot.
    // ADR-098 amendment (boundary nullability part C): `Char?` joins the same set -- CHAR16 has no
    // spare null, so the has-value channel is out of band exactly as it is for a primitive.
    val isNullableLegacyPrimitive: Boolean = nullableInner is BridgeType.Primitive ||
        nullableInner == BridgeType.Char ||
        nullableInner == BridgeType.Instant || nullableInner == BridgeType.Duration ||
        nullableInner is BridgeType.Enum ||
        underlying is BridgeType.Primitive || underlying is BridgeType.Enum
    if (getter is ForwardPropertyGetter.LegacyTwoCall) {
      require(isNullableLegacyPrimitive) {
        "Forward property plan $symbol uses LegacyTwoCall for non-nullable primitive $type"
      }
      require(getter.presence.result == ForwardAbiWireType.BOOLEAN) {
        "Forward property plan $symbol LegacyTwoCall presence export must return BOOLEAN"
      }
    }
    if (setter is ForwardPropertySetter.NullableDispatch) {
      require(isNullableLegacyPrimitive) {
        "Forward property plan $symbol uses nullable setter dispatch for $type"
      }
    }
    return this
  }

  fun calls(): List<ForwardNativeCall> = getter.calls() + (setter?.calls() ?: emptyList())

  private fun validateCall(call: ForwardNativeCall) {
    require(call.exportName.isNotBlank()) { "Forward property plan $symbol has a blank export" }
    require(call.parameters.lastOrNull()?.role == ForwardAbiRole.ERROR) {
      "Forward property plan $symbol export ${call.exportName} must end in errorOut"
    }
  }

  /**
   * The callable side's pairing check ([ForwardCallablePlanValidator]'s `validateTransfer`), at
   * every ABI slot a property plan owns: a receiver, a setter value, and the error slot. A
   * transfer that names a conversion the plan does not claim a helper for is a plan whose
   * [helperRequirements] was computed from something narrower than its own ABI.
   */
  private fun validateHelper(parameter: ForwardAbiParameter) {
    val conversion: ForwardConversion = parameter.transfer.conversion ?: return
    if (conversion == ForwardConversion.DIRECT) return
    require(conversion.helper() in helperRequirements) {
      "Forward property plan $symbol transfer ${parameter.transfer.subject} is missing helper " +
          conversion.helper()
    }
  }

  private fun validateType(type: BridgeType) {
    when (type) {
      BridgeType.Unit, BridgeType.Char, BridgeType.String, BridgeType.Instant, BridgeType.Duration,
      is BridgeType.Primitive, is BridgeType.Enum, is BridgeType.ObjectHandle,
      is BridgeType.Interface, is BridgeType.Collection,
        // ADR-151: valid at a property, getter and setter alike, over the collection handle wire.
      BridgeType.ByteArray -> Unit

      // ADR-107: valid at a property, getter-only (the setter is refused in the planner, so a
      // plan carrying one never reaches here).
      BridgeType.Throwable -> Unit

      // ADR-106: valid at a property, getter and setter alike, over the String wire.
      BridgeType.Uuid -> Unit

      // ADR-147: valid at a generic class's property, getter-only (the planner refuses the
      // setter, so a plan carrying one never reaches here).
      is BridgeType.TypeParameter -> Unit

      // ADR-077 sub-item 2: a value-class property is valid exactly when its underlying is.
      is BridgeType.ValueClass -> validateType(type.underlying)
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

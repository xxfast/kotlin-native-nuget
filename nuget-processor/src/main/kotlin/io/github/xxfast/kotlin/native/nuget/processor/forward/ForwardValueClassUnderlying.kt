package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import io.github.xxfast.kotlin.native.nuget.processor.valueClassUnderlyingOrThrow

/**
 * How a value class's underlying crosses the wire, decided once for both halves.
 *
 * The planner and the Kotlin export builder used to decide this separately: the planner by
 * [BridgeType] (a handle is a reference, anything else a value), `ValueClassExports.kt` by
 * qualified name (a listed scalar is a value, anything else a reference). They disagreed on every
 * underlying that is neither a listed scalar nor a handle (`Char`, a plain interface, `Instant`, a
 * collection), so the planner planned a `_create` export the Kotlin half never emitted and KSP
 * aborted with `ERROR_INTERNAL_GENERATOR_FAILURE`. The planner reads this now, and the Kotlin half
 * emits exactly what the planner planned.
 */
internal enum class ForwardValueClassUnderlying {
  /** Crosses as its own wire value; the record struct is hand-written and runs `init` in C#. */
  VALUE,

  /** Crosses as a Kotlin handle; the record struct is positional over the C# wrapper (ADR-035). */
  REFERENCE,

  /** Neither wire implements it: the value class is not declared, and is refused by name. */
  REFUSED,
}

/** The value-class underlying's [ForwardValueClassUnderlying] role, see there. */
internal fun ForwardBridgeTypeClassifier.valueClassUnderlying(
  cls: KSClassDeclaration,
): ForwardValueClassUnderlying {
  val parameter: KSValueParameter = cls.primaryConstructor?.parameters?.singleOrNull()
    ?: return ForwardValueClassUnderlying.REFUSED
  return classify(parameter.type.resolve()).valueClassUnderlyingRole()
}

private fun BridgeType.valueClassUnderlyingRole(): ForwardValueClassUnderlying = when (this) {
  BridgeType.String, is BridgeType.Primitive, is BridgeType.Enum ->
    ForwardValueClassUnderlying.VALUE
  is BridgeType.ObjectHandle -> ForwardValueClassUnderlying.REFERENCE
  // ADR-105: a sealed underlying crosses as the base's handle on the shipped ABI.
  is BridgeType.SpecializedProtocol ->
    if (name.startsWith(SEALED_HELPER_PREFIX)) ForwardValueClassUnderlying.REFERENCE
    else ForwardValueClassUnderlying.REFUSED
  // A nullable scalar underlying (`value class MaybeId(val value: String?)`) is a value.
  is BridgeType.Nullable ->
    if (type == BridgeType.String || type is BridgeType.Primitive) ForwardValueClassUnderlying.VALUE
    else ForwardValueClassUnderlying.REFUSED
  BridgeType.Unit,
  BridgeType.Char,
  BridgeType.Instant,
  BridgeType.Duration,
  is BridgeType.Throwable,
  BridgeType.Uuid,
  is BridgeType.Interface,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Callback,
  is BridgeType.ReturnedLambda,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> ForwardValueClassUnderlying.REFUSED
}

/**
 * Whether `default(V)` of this value class's C# record struct carries a null that no non-null
 * Kotlin slot can take: a String or an exported-class underlying. A primitive or enum default is a
 * real value, and a nullable underlying's null is one too, so neither is refused.
 */
internal fun BridgeType.ValueClass.hasNullDefault(): Boolean =
  underlying == BridgeType.String || underlying is BridgeType.ObjectHandle

/**
 * [struct]'s underlying property in C#, the unwrap every C# to Kotlin crossing of a value class
 * starts from. A [hasNullDefault] struct is unwrapped through [valueClassUnderlyingOrThrow], so a
 * `default(V)` throws before anything crosses; [parameter] is its `nameof` target, when it has one.
 */
internal fun BridgeType.ValueClass.underlyingCs(struct: String, parameter: String?): String {
  val property: String = underlyingPropertyName.replaceFirstChar { it.uppercase() }
  if (!hasNullDefault()) return "$struct.$property"
  val structName: String = qualifiedName.substringAfterLast('.')
  return valueClassUnderlyingOrThrow(struct, property, structName, parameter)
}

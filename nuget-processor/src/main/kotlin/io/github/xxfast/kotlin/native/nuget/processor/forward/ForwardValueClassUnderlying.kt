package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
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

  /**
   * ADR-220: a generic value class whose underlying is one of its own type parameters (`T` or
   * `T?`) or a phantom one (`val raw: Long`, VALUE or REFERENCE). Declared once as a positional
   * `readonly record struct V<T>(T Value)`: no `_create`, no box pair, no members. A closed
   * instantiation crosses as its substituted underlying (the classifier substitutes it).
   */
  GENERIC,

  /** Neither wire implements it: the value class is not declared, and is refused by name. */
  REFUSED,
}

/** The value-class underlying's [ForwardValueClassUnderlying] role, see there. */
internal fun ForwardBridgeTypeClassifier.valueClassUnderlying(
  cls: KSClassDeclaration,
): ForwardValueClassUnderlying {
  val parameter: KSValueParameter = cls.primaryConstructor?.parameters?.singleOrNull()
    ?: return ForwardValueClassUnderlying.REFUSED
  val underlying: KSType = parameter.type.resolve()
  val role: ForwardValueClassUnderlying = classify(underlying).valueClassUnderlyingRole()
  if (cls.typeParameters.isEmpty()) return role
  // ADR-220: a bare own type parameter, or a phantom `T` over an underlying that already crosses.
  val isOwnTypeParameter: Boolean = underlying.declaration is KSTypeParameter &&
      cls.typeParameters.any { it.name.asString() == underlying.declaration.simpleName.asString() }
  val isPhantom: Boolean = role == ForwardValueClassUnderlying.VALUE ||
      role == ForwardValueClassUnderlying.REFERENCE
  return if (isOwnTypeParameter || isPhantom) ForwardValueClassUnderlying.GENERIC
  else ForwardValueClassUnderlying.REFUSED
}

internal fun BridgeType.valueClassUnderlyingRole(): ForwardValueClassUnderlying = when (this) {
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
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
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
  underlying == BridgeType.String || underlying is BridgeType.ObjectHandle ||
      // ADR-220: a bound own `T` (`T : Any`) inside a generic value class is a non-null slot, but a
      // `struct`-constrained one (`T : Enum<T>`) defaults to a real value, its first entry.
      underlying is BridgeType.TypeParameter && !underlying.valueType

/**
 * ADR-220: the member's own type parameter this generic value class instantiation carries as its
 * underlying (`Sticker<T>` inside `fun <T> stick`), either nullability, or null for any other.
 */
internal fun BridgeType.ValueClass.ownTypeParameterUnderlying(): BridgeType.TypeParameter? =
  underlying as? BridgeType.TypeParameter
    ?: (underlying as? BridgeType.Nullable)?.type as? BridgeType.TypeParameter

/**
 * [struct]'s underlying property in C#, the unwrap every C# to Kotlin crossing of a value class
 * starts from. A [hasNullDefault] struct is unwrapped through [valueClassUnderlyingOrThrow], so a
 * `default(V)` throws before anything crosses; [parameter] is its `nameof` target, when it has one.
 */
internal fun BridgeType.ValueClass.underlyingCs(struct: String, parameter: String?): String {
  val property: String = underlyingPropertyName.replaceFirstChar { it.uppercase() }
  if (!hasNullDefault()) return "$struct.$property"
  val structName: String = qualifiedName.substringAfterLast('.')
  val explicit: String? = parameter.takeIf { it != VALUE_CLASS_SELF }
  return valueClassUnderlyingOrThrow(struct, property, structName, explicit)
}


/**
 * ADR-225: the C# local a value-class struct member copies `this` into, its receiver. A
 * `default(V)` receiver is refused without a `nameof`: it is no parameter the caller passed.
 */
internal const val VALUE_CLASS_SELF: String = "nugetSelf"

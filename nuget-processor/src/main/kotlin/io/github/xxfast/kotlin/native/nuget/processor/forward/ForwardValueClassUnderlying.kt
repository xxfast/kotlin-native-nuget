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

  /**
   * ADR-235: an underlying that already has a C# spelling and a wire at every ordinary position
   * (`Instant`, `Duration`, `Uuid`, `ByteArray`, a collection, another value class, a plain
   * interface). Declared as a positional record struct over that spelling; a position typed with
   * the value class is planned as its innermost underlying, so it crosses on that wire, and the
   * value class is unwrapped in C# and re-wrapped (running `init`) in Kotlin. See
   * [ForwardValueClassView].
   */
  WIRE,

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
  // ADR-235: each already crosses on its own wire; the value class rides it as a view.
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.Interface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
    -> ForwardValueClassUnderlying.WIRE
  BridgeType.Unit,
  BridgeType.Char,
  is BridgeType.Throwable,
  is BridgeType.BoundInterface,
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

/**
 * ADR-235: a position typed with a [ForwardValueClassUnderlying.WIRE] value class (or its nullable
 * form), seen through to its innermost underlying. The plan carries [planned] at the position, so
 * the ABI slot, the preludes, the cleanups and the result read are exactly the underlying's; only
 * the public C# spelling stays [declared], and both halves convert at the edge: C# unwraps the
 * struct before the underlying's own lowering ([csharpUnwrap]) and wraps the read result
 * ([csharpWrap]); Kotlin re-wraps the lowered argument ([kotlinWrap], running each `init` from the
 * innermost out) and unwraps the call's result ([kotlinUnwrap]).
 */
internal data class ForwardValueClassView(val declared: BridgeType) {
  val isNullable: Boolean get() = declared is BridgeType.Nullable

  /** The value classes from the outermost in; a nested value class has more than one. */
  val chain: List<BridgeType.ValueClass>
    get() = generateSequence(declared.unwrapNullable() as BridgeType.ValueClass) { outer ->
      outer.underlying as? BridgeType.ValueClass
    }.toList()

  /** The type the plan carries at this position. */
  val planned: BridgeType
    get() {
      val innermost: BridgeType = chain.last().underlying
      return if (isNullable) BridgeType.Nullable(innermost) else innermost
    }

  fun kotlinWrap(lowered: String): String {
    fun wrap(value: String): String =
      chain.foldRight(value) { valueClass, inner -> "${valueClass.qualifiedName}($inner)" }
    return if (isNullable) "($lowered)?.let { nugetUnderlying -> ${wrap("nugetUnderlying")} }"
    else wrap(lowered)
  }

  fun kotlinUnwrap(call: String): String {
    val access: String = if (isNullable) "?." else "."
    return "($call)" + chain.joinToString("") { valueClass ->
      access + valueClass.underlyingPropertyName.kotlinIdentifier()
    }
  }

  /**
   * The struct's underlying. A reference-typed innermost underlying (`byte[]`, a collection, an
   * interface) refuses `default(V)`, whose underlying is a null no non-null Kotlin slot takes, as
   * the String and handle underlyings do; a nullable struct's own null stays null.
   */
  fun csharpUnwrap(value: String, parameter: String? = value): String {
    val properties: String = chain.joinToString(".") { valueClass ->
      valueClass.underlyingPropertyName.replaceFirstChar { it.uppercase() }
    }
    val innermost: BridgeType = chain.last().underlying
    val guarded: Boolean = innermost == BridgeType.ByteArray ||
        innermost is BridgeType.Collection || innermost is BridgeType.Interface
    if (!guarded) return if (isNullable) "($value?.$properties)" else "$value.$properties"
    val structName: String = chain.first().qualifiedName.substringAfterLast('.')
    if (!isNullable) return valueClassUnderlyingOrThrow(value, properties, structName, parameter)
    val present: String = valueClassUnderlyingOrThrow("$value.Value", properties, structName, parameter)
    return "($value.HasValue ? $present : null)"
  }

  fun csharpWrap(read: String): String {
    fun wrap(value: String): String =
      chain.foldRight(value) { valueClass, inner -> "new ${valueClass.csharpType}($inner)" }
    if (!isNullable) return wrap(read)
    if (read == "null") return read
    val underlying: String = chain.last().underlying.forwardPublicCsharpType()
    return "(($underlying?)($read)) is { } nugetUnderlying ? ${wrap("nugetUnderlying")} : " +
        "(${chain.first().csharpType}?)null"
  }
}

/** ADR-235: the view of this position when it is a [ForwardValueClassUnderlying.WIRE] value class. */
internal fun BridgeType.valueClassViewOrNull(): ForwardValueClassView? {
  val valueClass: BridgeType.ValueClass = unwrapNullable() as? BridgeType.ValueClass ?: return null
  if (valueClass.qualifiedName == "kotlin.Result") return null
  if (valueClass.underlying.valueClassUnderlyingRole() != ForwardValueClassUnderlying.WIRE) {
    return null
  }
  return ForwardValueClassView(this)
}

/** ADR-235: this value class when its underlying is a WIRE one, so it rides that wire as a view. */
internal fun BridgeType.ValueClass.wireOrNull(): BridgeType.ValueClass? =
  takeIf { underlying.valueClassUnderlyingRole() == ForwardValueClassUnderlying.WIRE }

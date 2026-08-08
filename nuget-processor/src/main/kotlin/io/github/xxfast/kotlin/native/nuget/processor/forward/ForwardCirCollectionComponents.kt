package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * ADR-081: the C# half of "a value-class collection component crosses as its underlying".
 *
 * Both CIR projections ([ForwardCirPlanProjection] for callables, [ForwardCirPropertyProjection]
 * for properties) need the same two per-element steps, so they live here once rather than in two
 * copies that can drift:
 *
 *  - [collectionCreateArgument]: the write side, projecting each component to its underlying
 *    *before* `NugetMarshal.Wrap<T>` is instantiated, so `Wrap<T>` only ever sees a type it already
 *    boxes (`string`, the five wide primitives, or a class carrying `_handle`).
 *  - [collectionComponentRead]: the read side, unwrapping the underlying's box and re-wrapping the
 *    record struct per element.
 *
 * Both take the caller's own `csharpType` projection as a function rather than duplicating it: the
 * two projections each keep a private copy with slightly different error messages.
 *
 * `Select` is spelled fully qualified because `System.Linq` is deliberately *not* among the
 * generated file's usings (`CirTranslator`'s `usings` list), and adding it there would mean
 * threading one more flag through the tracker for a single expression.
 */
private const val SELECT: String = "global::System.Linq.Enumerable.Select"

/**
 * The argument handed to `NugetMarshal.CreateList`/`CreateSet`/`CreateMap` for [name], projected
 * per element when a component is a value class. Returns [name] unchanged otherwise, so every
 * pre-ADR-081 call site keeps its exact previous rendering.
 */
internal fun collectionCreateArgument(
  name: String,
  type: BridgeType.Collection,
  csharpType: (BridgeType) -> String,
): String = when (type.kind) {
  CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
    val key: BridgeType = requireNotNull(type.key) { "Forward CIR Map input has no key type" }
    val value: BridgeType = requireNotNull(type.value) { "Forward CIR Map input has no value type" }
    if (key.componentValueClass() == null && value.componentValueClass() == null) {
      name
    } else {
      // The key and value slots project independently: either side may be the value class.
      val keyType: String = componentWireCsharpType(key, csharpType)
      val valueType: String = componentWireCsharpType(value, csharpType)
      "$SELECT($name, x => new KeyValuePair<$keyType, $valueType>(" +
          "${componentWireExpression("x.Key", key)}, ${componentWireExpression("x.Value", value)}))"
    }
  }

  else -> {
    val element: BridgeType =
      requireNotNull(type.element) { "Forward CIR collection input has no element type" }
    if (element.componentValueClass() == null) name
    else "$SELECT($name, x => ${componentWireExpression("x", element)})"
  }
}

/**
 * One component read out of its native box, as an optional local [declaration] to emit before the
 * read plus the [expression] that produces the component value.
 *
 * ADR-083: only a *nullable* component needs the declaration. Its handle has to be tested against
 * `IntPtr.Zero` and then read, and the handle expression cannot simply be repeated: every
 * `Get`/`KeyAt`/`ValueAt` call mints a fresh `StableRef` on the Kotlin side. A non-nullable
 * component keeps its exact pre-ADR-083 single-expression rendering, with no declaration.
 */
internal class CirComponentRead(val declaration: String?, val expression: String)

/** Reads one component out of its native box: `FromHandle<T>` for an ordinary component, and for a
 *  value class `FromHandle<underlying>` with the record struct reconstructed around it (re-running
 *  the value class's own `init`, per element, exactly as ADR-077 does at ordinary positions).
 *  [local] names the handle local a nullable component needs; it is unused otherwise. */
internal fun collectionComponentRead(
  local: String,
  handle: String,
  component: BridgeType,
  csharpType: (BridgeType) -> String,
): CirComponentRead {
  if (component !is BridgeType.Nullable) {
    return CirComponentRead(null, componentReadExpression(handle, component, csharpType))
  }
  // ADR-083: the null pointer *is* the null component. The cast on the null arm is what gives the
  // conditional a common type when the present arm is a value type (`int`, a record struct).
  val nullArm: String = "(${csharpType(component)})null"
  val present: String = componentReadExpression(local, component.type, csharpType)
  return CirComponentRead(
    "IntPtr $local = $handle;",
    "$local == IntPtr.Zero ? $nullArm : $present",
  )
}

/** The non-null half of [collectionComponentRead]: [handle]'s box unwrapped, and re-wrapped when
 *  the component is a value class. */
private fun componentReadExpression(
  handle: String,
  component: BridgeType,
  csharpType: (BridgeType) -> String,
): String {
  val wireType: String = componentWireCsharpType(component, csharpType)
  val raw: String = "NugetMarshal.FromHandle<$wireType>($handle)"
  if (component !is BridgeType.ValueClass) return raw
  // An enum underlying rides the int-ordinal wire, so the cast back to the C# enum is what makes
  // the record struct's own constructor applicable.
  val argument: String = if (component.underlying is BridgeType.Enum) {
    "(${csharpType(component.underlying)})$raw"
  } else {
    raw
  }
  return "new ${csharpType(component)}($argument)"
}

/** The static C# type actually crossing the wire for [component]: the value class's underlying
 *  (`int` for an enum underlying), or the component's own public type. ADR-083: a nullable value
 *  class rides the nullable spelling of that same underlying (`string?`, `int?`). */
private fun componentWireCsharpType(
  component: BridgeType,
  csharpType: (BridgeType) -> String,
): String {
  val valueClass: BridgeType.ValueClass =
    component.componentValueClass() ?: return csharpType(component)
  val suffix: String = if (component is BridgeType.Nullable) "?" else ""
  return if (valueClass.underlying is BridgeType.Enum) "int$suffix"
  else "${csharpType(valueClass.underlying)}$suffix"
}

/** One outgoing component projected to its wire value: `x.Value`, `(int)x.Mood`, `x.Patient`, and
 *  (ADR-083) their `?.`-lifted forms when the component is a nullable value class. */
private fun componentWireExpression(access: String, component: BridgeType): String {
  val valueClass: BridgeType.ValueClass = component.componentValueClass() ?: return access
  val property: String = valueClass.underlyingPropertyName.replaceFirstChar { it.uppercase() }
  val isEnum: Boolean = valueClass.underlying is BridgeType.Enum
  if (component !is BridgeType.Nullable) {
    return if (isEnum) "(int)$access.$property" else "$access.$property"
  }
  // A `Nullable<T>` of a record struct still projects its underlying through `?.`; the enum
  // ordinal needs the explicit conditional because the `(int)` cast is not itself lifted.
  return if (isEnum) "$access == null ? (int?)null : (int)$access.Value.$property"
  else "$access?.$property"
}

/** The value class a component projects through, seeing past ADR-083's nullable spelling; `null`
 *  when the component needs no value-class projection at all. */
internal fun BridgeType.componentValueClass(): BridgeType.ValueClass? = when (this) {
  is BridgeType.ValueClass -> this
  is BridgeType.Nullable -> type as? BridgeType.ValueClass
  else -> null
}

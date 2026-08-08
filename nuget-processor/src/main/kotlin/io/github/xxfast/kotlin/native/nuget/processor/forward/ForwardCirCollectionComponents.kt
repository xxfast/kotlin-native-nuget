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
    if (key !is BridgeType.ValueClass && value !is BridgeType.ValueClass) {
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
    if (element !is BridgeType.ValueClass) name
    else "$SELECT($name, x => ${componentWireExpression("x", element)})"
  }
}

/** Reads one component out of its native box: `FromHandle<T>` for an ordinary component, and for a
 *  value class `FromHandle<underlying>` with the record struct reconstructed around it (re-running
 *  the value class's own `init`, per element, exactly as ADR-077 does at ordinary positions). */
internal fun collectionComponentRead(
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
 *  (`int` for an enum underlying), or the component's own public type. */
private fun componentWireCsharpType(
  component: BridgeType,
  csharpType: (BridgeType) -> String,
): String =
  if (component !is BridgeType.ValueClass) csharpType(component)
  else if (component.underlying is BridgeType.Enum) "int"
  else csharpType(component.underlying)

/** One outgoing component projected to its wire value: `x.Value`, `(int)x.Mood`, `x.Patient`. */
private fun componentWireExpression(access: String, component: BridgeType): String {
  if (component !is BridgeType.ValueClass) return access
  val unwrapped: String =
    "$access.${component.underlyingPropertyName.replaceFirstChar { it.uppercase() }}"
  return if (component.underlying is BridgeType.Enum) "(int)$unwrapped" else unwrapped
}

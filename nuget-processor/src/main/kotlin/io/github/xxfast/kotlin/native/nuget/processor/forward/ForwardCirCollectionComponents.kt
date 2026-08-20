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
  depth: Int = 0,
  csharpType: (BridgeType) -> String,
): String = when (type.kind) {
  CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
    val key: BridgeType = requireNotNull(type.key) { "Forward CIR Map input has no key type" }
    val value: BridgeType = requireNotNull(type.value) { "Forward CIR Map input has no value type" }
    if (!key.componentNeedsWireProjection() && !value.componentNeedsWireProjection()) {
      name
    } else {
      // The key and value slots project independently: either side may need the projection.
      val keyType: String = componentWireCsharpType(key, csharpType)
      val valueType: String = componentWireCsharpType(value, csharpType)
      val lambda: String = lambdaParameter(depth)
      "$SELECT($name, $lambda => new KeyValuePair<$keyType, $valueType>(" +
          "${componentWireExpression("$lambda.Key", key, csharpType, depth)}, " +
          "${componentWireExpression("$lambda.Value", value, csharpType, depth)}))"
    }
  }

  else -> {
    val element: BridgeType =
      requireNotNull(type.element) { "Forward CIR collection input has no element type" }
    val lambda: String = lambdaParameter(depth)
    if (!element.componentNeedsWireProjection()) name
    else "$SELECT($name, $lambda => ${componentWireExpression(lambda, element, csharpType, depth)})"
  }
}

/**
 * ADR-099: the `Select` lambda parameter for nesting level [depth]. Level 0 keeps the shipped `x`
 * spelling; a nested level needs its own name because C# forbids a lambda parameter that shadows
 * an enclosing one (CS0136).
 */
private fun lambdaParameter(depth: Int): String = if (depth == 0) "x" else "x$depth"

/** ADR-099: the `NugetMarshal` factory that builds [kind]'s native handle. */
private fun collectionFactory(kind: CollectionKind): String = when (kind) {
  CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
  CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
  CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
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
  depth: Int = 0,
  csharpType: (BridgeType) -> String,
): CirComponentRead {
  if (component !is BridgeType.Nullable) {
    return CirComponentRead(null, componentReadExpression(handle, component, csharpType, depth))
  }
  // ADR-083: the null pointer *is* the null component. The cast on the null arm is what gives the
  // conditional a common type when the present arm is a value type (`int`, a record struct).
  val nullArm: String = "(${csharpType(component)})null"
  val present: String = componentReadExpression(local, component.type, csharpType, depth)
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
  depth: Int = 0,
): String {
  // ADR-099: a nested component's box IS the inner collection's own handle, so the read recurses
  // through the `ReadList`/`ReadSet`/`ReadMap` helpers. They are helpers rather than inlined loops
  // because the shipped read uses fixed local names (`listHandle`, `count`, `result`, `i`) that
  // cannot nest, and because their `finally` makes every inner level leak-free by construction.
  if (component is BridgeType.Collection) {
    return componentCollectionRead(handle, component, csharpType, depth)
  }
  val wireType: String = componentWireCsharpType(component, csharpType)
  val raw: String = "NugetMarshal.FromHandle<$wireType>($handle)"
  // ADR-097: a bare enum crossed as its int ordinal, so the cast back to the C# enum is the whole
  // of its read projection; `FromHandle<int>` has a real branch, unlike `FromHandle<Mood>`.
  if (component is BridgeType.Enum) return "(${csharpType(component)})$raw"
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
 *  (`int` for an enum underlying), `int` for a bare enum (ADR-097), or the component's own public
 *  type. ADR-083: a nullable component rides the nullable spelling of that same wire type
 *  (`string?`, `int?`). */
private fun componentWireCsharpType(
  component: BridgeType,
  csharpType: (BridgeType) -> String,
): String {
  // ADR-099: a nested collection crosses as its own native handle, in the same pointer-shaped slot
  // every other component already uses.
  if (component is BridgeType.Collection) return "IntPtr"
  val suffix: String = if (component is BridgeType.Nullable) "?" else ""
  if (component.componentEnum() != null) return "int$suffix"
  val valueClass: BridgeType.ValueClass =
    component.componentValueClass() ?: return csharpType(component)
  return if (valueClass.underlying is BridgeType.Enum) "int$suffix"
  else "${csharpType(valueClass.underlying)}$suffix"
}

/** One outgoing component projected to its wire value: `x.Value`, `(int)x.Mood`, `x.Patient`,
 *  (ADR-097) `(int)x` for a bare enum, and (ADR-083) their `?.`-lifted forms when the component is
 *  nullable. */
private fun componentWireExpression(
  access: String,
  component: BridgeType,
  csharpType: (BridgeType) -> String,
  depth: Int = 0,
): String {
  // ADR-099: the inner collection is built by the *same* factory the outer one uses, one level
  // down, and its handle is the component's wire value. Arbitrary depth is this one arm.
  if (component is BridgeType.Collection) {
    val factory: String = collectionFactory(component.kind)
    val argument: String = collectionCreateArgument(access, component, depth + 1, csharpType)
    return "NugetMarshal.$factory($argument)"
  }
  if (component.componentEnum() != null) {
    return if (component is BridgeType.Nullable) {
      "$access == null ? (int?)null : (int)$access.Value"
    } else {
      "(int)$access"
    }
  }
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

/** ADR-097: the bare enum a component projects through, seeing past ADR-083's nullable spelling;
 *  `null` when the component is not a bare enum. A value class *over* an enum is not this: it
 *  keeps ADR-081's own projection, which additionally reconstructs the record struct. */
internal fun BridgeType.componentEnum(): BridgeType.Enum? = when (this) {
  is BridgeType.Enum -> this
  is BridgeType.Nullable -> type as? BridgeType.Enum
  else -> null
}

/** ADR-081/097: whether a component crosses as something other than itself, and therefore needs a
 *  per-element projection on both sides of the seam. ADR-099: a nested collection needs one only
 *  when something *inside* it does: the Kotlin side boxes the inner container object as-is. */
internal fun BridgeType.componentNeedsProjection(): Boolean = when {
  componentValueClass() != null || componentEnum() != null -> true
  this is BridgeType.Collection -> element?.componentNeedsProjection() == true ||
      key?.componentNeedsProjection() == true || value?.componentNeedsProjection() == true

  else -> false
}

/**
 * ADR-099: the C# write side's stronger form of [componentNeedsProjection]. A nested collection
 * always needs a `Select` at the call site, whatever its own components do, because the wire value
 * is a handle this call site has to mint. The Kotlin raising side keeps the weaker predicate: it
 * hands the inner container over untouched unless a leaf converts.
 */
private fun BridgeType.componentNeedsWireProjection(): Boolean =
  this is BridgeType.Collection || componentNeedsProjection()

/**
 * ADR-099, read side: one nested component read back through the `ReadList`/`ReadSet`/`ReadMap`
 * helper for its kind, with the inner component's own read as the per-element lambda.
 * `.AsReadOnly()` is appended for the immutable `LIST` kind so an inner list matches the outer's
 * shipped rendering.
 */
private fun componentCollectionRead(
  handle: String,
  component: BridgeType.Collection,
  csharpType: (BridgeType) -> String,
  depth: Int,
): String {
  val inner: Int = depth + 1
  return when (component.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST, CollectionKind.SET,
    CollectionKind.MUTABLE_SET,
      -> {
      val element: BridgeType =
        requireNotNull(component.element) { "Forward CIR nested collection has no element type" }
      val helper: String =
        if (component.kind == CollectionKind.SET || component.kind == CollectionKind.MUTABLE_SET) {
          "ReadSet"
        } else {
          "ReadList"
        }
      val lambda: String =
        componentReadLambda("h$inner", "elementHandle$inner", element, csharpType, inner)
      val suffix: String = if (component.kind == CollectionKind.LIST) ".AsReadOnly()" else ""
      "NugetMarshal.$helper<${csharpType(element)}>($handle, $lambda)$suffix"
    }

    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
      val key: BridgeType =
        requireNotNull(component.key) { "Forward CIR nested Map has no key type" }
      val value: BridgeType =
        requireNotNull(component.value) { "Forward CIR nested Map has no value type" }
      val readKey: String =
        componentReadLambda("k$inner", "keyHandle$inner", key, csharpType, inner)
      val readValue: String =
        componentReadLambda("v$inner", "valueHandle$inner", value, csharpType, inner)
      val keyType: String = csharpType(key)
      val valueType: String = csharpType(value)
      "NugetMarshal.ReadMap<$keyType, $valueType>($handle, $readKey, $readValue)"
    }
  }
}

/** One `Func<IntPtr, T>` handed to a `Read*` helper. Block-bodied when the component carries a
 *  declaration (ADR-083's nullable read), expression-bodied otherwise. */
private fun componentReadLambda(
  parameter: String,
  local: String,
  component: BridgeType,
  csharpType: (BridgeType) -> String,
  depth: Int,
): String {
  val read: CirComponentRead =
    collectionComponentRead(local, parameter, component, depth, csharpType)
  if (read.declaration == null) return "static $parameter => ${read.expression}"
  return "static $parameter => { ${read.declaration} return ${read.expression}; }"
}

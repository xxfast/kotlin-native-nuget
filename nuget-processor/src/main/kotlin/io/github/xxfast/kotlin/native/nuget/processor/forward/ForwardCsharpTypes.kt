package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * The public C# spelling of a [BridgeType], i.e. what a caller writes at a call site, as opposed
 * to the native (`DllImport`) type it is projected to.
 *
 * Lifted out of [ForwardCirPlanProjection]'s private copy so ADR-114's legacy Flow/suspend routes
 * can spell a collection parameter the same way the ordinary route does. A third private copy was
 * the alternative, and the three would have drifted the first time a `BridgeType` variant landed.
 */
internal fun BridgeType.forwardPublicCsharpType(): String = when (this) {
  BridgeType.Unit -> "void"
  is BridgeType.Primitive -> kind.forwardPublicCsharpType()
  BridgeType.Char -> "char"
  BridgeType.String -> "string"
  // ADR-076: the public C# type is always System.DateTimeOffset, fully qualified so no "using
  // System;" is required in the generated file.
  BridgeType.Instant -> "global::System.DateTimeOffset"
  // ADR-103: likewise System.TimeSpan.
  BridgeType.Duration -> "global::System.TimeSpan"
  // ADR-106: System.Guid, over the RFC 9562 hex-dash text wire.
  BridgeType.Uuid -> "global::System.Guid"
  // ADR-066: the classifier already computed the correctly-qualified public spelling (bare
  // simple name in this class's own namespace, `global::Namespace.Name` otherwise).
  is BridgeType.ObjectHandle -> csharpType
  // ADR-040: the public C# spelling is the projected interface (`IPet`), never the backing
  // wrapper class: the wrapper is a construction-only implementation detail.
  is BridgeType.Interface -> csharpType
  // ADR-088: the ORIGINAL bound interface, read from the plugin's manifest.
  is BridgeType.BoundInterface -> csharpType
  is BridgeType.ValueClass -> csharpType
  is BridgeType.Enum -> csharpType
  is BridgeType.Collection -> when (kind) {
    CollectionKind.LIST -> "IReadOnlyList<${requireNotNull(element).forwardPublicCsharpType()}>"
    CollectionKind.MUTABLE_LIST -> "IList<${requireNotNull(element).forwardPublicCsharpType()}>"
    CollectionKind.MAP ->
      "IReadOnlyDictionary<${requireNotNull(key).forwardPublicCsharpType()}, " +
          "${requireNotNull(value).forwardPublicCsharpType()}>"

    CollectionKind.MUTABLE_MAP ->
      "IDictionary<${requireNotNull(key).forwardPublicCsharpType()}, " +
          "${requireNotNull(value).forwardPublicCsharpType()}>"

    CollectionKind.SET -> "IReadOnlySet<${requireNotNull(element).forwardPublicCsharpType()}>"
    CollectionKind.MUTABLE_SET -> "ISet<${requireNotNull(element).forwardPublicCsharpType()}>"
  }

  // ADR-151: the point of the feature -- a Kotlin ByteArray is a C# byte[], never List<sbyte>.
  BridgeType.ByteArray -> "byte[]"
  // ADR-147: a type parameter's public C# spelling is its own name, on the generic carrier.
  is BridgeType.TypeParameter -> name
  is BridgeType.Nullable -> "${type.forwardPublicCsharpType()}?"
  else -> error("Forward CIR direct-value projection cannot render public type $this")
}

/**
 * True when [forwardPublicCsharpType] has an arm for this type, i.e. when a declaration-only site
 * (the `abstractMethods` walk, which has no plan, no export and no marshalling) can spell it.
 *
 * Written out rather than derived by catching [forwardPublicCsharpType]'s `error()`: a predicate a
 * new `BridgeType` variant has to be added to is the point. Two spellable types are deliberately
 * excluded:
 *  - [BridgeType.BoundInterface]: ADR-088 defers the position, and `skipReason()` already names it
 *    [ForwardPlanSkipReason.BOUND_INTERFACE_POSITION]. Spelling the original bound type on an
 *    abstract member no route can implement would promise a surface v1 does not have.
 *  - [BridgeType.TypeParameter] whose name is not in [typeParametersInScope]: ADR-147 made a
 *    class's own `T` a first-class spelling **on that class's generic carrier**, so `T` is a real
 *    name inside `Crate<T>` and nowhere else. An inherited `T` re-homed onto a non-generic subclass
 *    is the same dangling bare name every other cell here is about.
 *
 * @param typeParametersInScope the names of the type parameters the declaring C# type declares.
 */
internal fun BridgeType.isPubliclySpellable(
  typeParametersInScope: Set<String> = emptySet(),
): Boolean = when (this) {
  BridgeType.Unit,
  is BridgeType.Primitive,
  BridgeType.Char,
  BridgeType.String,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  BridgeType.ByteArray,
  is BridgeType.ObjectHandle,
  is BridgeType.Interface,
  is BridgeType.Enum,
  is BridgeType.ValueClass,
    -> true

  is BridgeType.Collection -> listOfNotNull(element, key, value)
    .all { component -> component.isPubliclySpellable(typeParametersInScope) }

  is BridgeType.Nullable -> type.isPubliclySpellable(typeParametersInScope)
  is BridgeType.TypeParameter -> name in typeParametersInScope

  BridgeType.Throwable,
  is BridgeType.BoundInterface,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawCollection,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
    -> false
}

/** The C# spelling of a primitive kind, shared for the same reason as the type above. */
internal fun PrimitiveKind.forwardPublicCsharpType(): String = when (this) {
  PrimitiveKind.BOOLEAN -> "bool"
  PrimitiveKind.BYTE -> "sbyte"
  PrimitiveKind.UBYTE -> "byte"
  PrimitiveKind.SHORT -> "short"
  PrimitiveKind.USHORT -> "ushort"
  PrimitiveKind.INT -> "int"
  PrimitiveKind.UINT -> "uint"
  PrimitiveKind.LONG -> "long"
  PrimitiveKind.ULONG -> "ulong"
  PrimitiveKind.FLOAT -> "float"
  PrimitiveKind.DOUBLE -> "double"
}

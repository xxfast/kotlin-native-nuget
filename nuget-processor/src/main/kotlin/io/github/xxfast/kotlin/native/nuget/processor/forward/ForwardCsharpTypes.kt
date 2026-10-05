package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.csLambdaType

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
  // ADR-107 / ADR-201: the envelope reads back as a constructed, unthrown System.Exception, and
  // a System.Exception is what an input position accepts.
  is BridgeType.Throwable -> "global::System.Exception"
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
  // ADR-160: the idiomatic C# spelling of a per-call callback -- `Action` for a `Unit` lambda
  // result, `Func<..., R>` otherwise -- so the consumer writes a lambda and nothing else.
  is BridgeType.Callback -> {
    val payload: List<String> =
      parameters.map { parameter -> parameter.forwardPublicCsharpType() }
    if (result == BridgeType.Unit) {
      if (payload.isEmpty()) "Action" else "Action<${payload.joinToString(", ")}>"
    } else {
      (payload + result.forwardPublicCsharpType()).joinToString(", ", "Func<", ">")
    }
  }
  // ADR-160 amendment: ADR-012's consumer type, `KotlinFunc<...>`, or `KotlinAction<...>` for a
  // `Unit` result (issue #114). Only reached once [unnameableTypeArgument] found nothing.
  is BridgeType.ReturnedLambda ->
    csLambdaType(typeArguments.map { argument -> argument.forwardPublicCsharpType() })

  // ADR-147 amendment: a nullable-bounded bare `T` is `T`, not `T?`; the instantiation says
  // whether it holds null.
  is BridgeType.Nullable ->
    if ((type as? BridgeType.TypeParameter)?.nullableFromBound == true) type.name
    else "${type.forwardPublicCsharpType()}?"
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
  // ADR-160: a callback's C# spelling is an `Action<>`/`Func<>` over components that are each
  // publicly spellable in their own right; the classifier admits no other shape.
  is BridgeType.Callback -> parameters.all { parameter ->
    parameter.isPubliclySpellable(typeParametersInScope)
  } && result.isPubliclySpellable(typeParametersInScope)

  BridgeType.Unit,
  is BridgeType.Primitive,
  BridgeType.Char,
  BridgeType.String,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  BridgeType.ByteArray,
  is BridgeType.Throwable,
  is BridgeType.ObjectHandle,
  is BridgeType.Interface,
  is BridgeType.Enum,
  is BridgeType.ValueClass,
    -> true

  is BridgeType.Collection -> listOfNotNull(element, key, value)
    .all { component -> component.isPubliclySpellable(typeParametersInScope) }

  is BridgeType.Nullable -> type.isPubliclySpellable(typeParametersInScope)
  is BridgeType.ReturnedLambda -> unnameableTypeArgument() == null
  is BridgeType.TypeParameter -> name in typeParametersInScope

  is BridgeType.BoundInterface,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawCollection,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
    -> false
}

/**
 * True when this type crosses an IN slot on the **nullable** string wire, i.e. the C# call site
 * passes a `string?` expression into it. The wire type alone (`ForwardAbiWireType.STRING`) carries
 * no nullability, so a `DllImport` parameter spelled bare `string` takes a CS8604 under
 * `<Nullable>enable</Nullable>` + `<TreatWarningsAsErrors>` -- the settings
 * `NugetCompileInteropTask` builds the generated file with -- even though the marshalling is
 * identical either way.
 *
 * Three shapes qualify, and they are the same three on both routes:
 *  - `String?` (ADR-043), whose argument is the nullable value itself;
 *  - `Uuid?` (ADR-106), whose argument is `x?.ToString()`;
 *  - a nullable value class over a `String` underlying (ADR-077 sub-items 3/4), whose argument is
 *    `x?.Value`. An `ObjectHandle` underlying stays on the plain `IntPtr` wire and does not.
 *
 * Shared deliberately: the callable route (`ForwardCirPlanProjection.nativeCsharpType`) had the
 * rule and the property route (`ForwardCirPropertyProjection.extension`'s receiver import) did not,
 * which is exactly how an extension property over a `String?` receiver failed to compile.
 */
internal fun BridgeType.isNullableStringWire(): Boolean {
  val inner: BridgeType = (this as? BridgeType.Nullable)?.type ?: return false
  return inner == BridgeType.String || inner == BridgeType.Uuid || inner is BridgeType.Throwable ||
      (inner as? BridgeType.ValueClass)?.underlying == BridgeType.String
}

/**
 * ADR-187: a pointer slot whose argument is a generated wrapper's own `_handle` (an exported class,
 * object or sealed type, or a value class over one, either nullable), so its import is typed
 * `NugetKotlinHandle` and the marshaller keeps the handle alive for the call. A nullable one passes
 * `NugetKotlinHandle.Null` for a C# null. Shared by the callable and property routes, so the import
 * and the argument agree on both.
 */
internal fun BridgeType.isKotlinHandleWire(): Boolean =
  when (val type: BridgeType = if (this is BridgeType.Nullable) this.type else this) {
    is BridgeType.ObjectHandle -> true
    is BridgeType.ValueClass -> type.underlying is BridgeType.ObjectHandle
    else -> false
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

/**
 * Issue #111 on the ADR-062 plan (ADR-160 amendment): the Kotlin name of the first type argument
 * of a returned lambda that C# cannot spell, or `null` when every one has a spelling.
 *
 * The plan-side twin of `csTypeArgument`'s admission set: a primitive, `Char`, `String`, an
 * exported class, object, interface (spelled `IPet`, ADR-173) or enum, any of those nullable, and
 * `Unit` as the lambda's own result only (it narrows to `KotlinAction`). Everything else (a
 * collection, a Flow, another lambda, a value class, `Instant`/`Duration`/`Uuid`, anything
 * unexported) is refused, so the function is skipped naming the argument rather than returned as
 * a `KotlinFunc` whose `FromHandle<T>` has no arm for it.
 */
internal fun BridgeType.ReturnedLambda.unnameableTypeArgument(): String? =
  typeArguments.indices
    .firstOrNull { index ->
      !typeArguments[index].isLambdaTypeArgument(isResult = index == typeArguments.lastIndex)
    }
    ?.let { index -> kotlinTypeArguments[index] }

private fun BridgeType.isLambdaTypeArgument(isResult: Boolean): Boolean = when (this) {
  BridgeType.Unit -> isResult
  is BridgeType.Primitive, BridgeType.Char, BridgeType.String, is BridgeType.Enum,
  is BridgeType.ObjectHandle, is BridgeType.Interface,
    -> true

  // ADR-171: a value class with a box/unbox pair crosses an erased position; the same test the
  // planner mints that pair on, which it never does for a generic value class.
  is BridgeType.ValueClass -> typeArguments.isEmpty() && hasErasedCrossing()
  is BridgeType.Nullable -> type != BridgeType.Unit && type.isLambdaTypeArgument(isResult = false)
  else -> false
}

/**
 * ADR-201: the C# argument a `Throwable` input crosses as, `"{FullName}: {Message}"`, which the
 * Kotlin export splits back into a `NugetManagedException`. It is the same join ADR-161's
 * `TakeOriginalManagedFault` compares against, but that stash is set only by a throwing callback
 * (`CreateManagedError`), never by this parameter path, so a passed exception Kotlin rethrows
 * reaches C# as a `KotlinException` over the `NugetManagedException`, not as itself: the input is
 * lossy by design (ADR-201 amendment). A null [nullable] value is the null string pointer.
 */
internal fun managedExceptionTextCs(name: String, nullable: Boolean): String {
  val text = "($name.GetType().FullName ?? \"System.Exception\") + \": \" + $name.Message"
  return if (nullable) "$name == null ? null : $text" else text
}

package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.csLambdaType
import io.github.xxfast.kotlin.native.nuget.processor.cir.csSuspendLambdaType

/**
 * The public C# spelling of a [BridgeType], i.e. what a caller writes at a call site, as opposed
 * to the native (`DllImport`) type it is projected to.
 *
 * The one speller: the property route's private twin was folded in, so a callable and a property
 * can no longer spell the same member differently. Lifted out of [ForwardCirPlanProjection]'s
 * private copy so ADR-114's legacy Flow/suspend routes
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
  // ADR-210: `Func<T, CancellationToken, Task<R>>`, or `Task` for a `Unit` result.
  is BridgeType.AsyncCallback -> forwardAsyncCallbackCsharpType()
  // ADR-210: the consumer's own asynchronous sequence.
  is BridgeType.FlowSource ->
    "global::System.Collections.Generic.IAsyncEnumerable<${element.forwardPublicCsharpType()}>" +
      if (nullable) "?" else ""
  // ADR-239: a lambda handed out of Kotlin (a result or a property, ADR-236) is the BCL delegate a
  // C# lambda converts to, bound to the `KotlinFunc` wrapper that owns the handle. Only reached
  // once [unnameableTypeArgument] found nothing.
  is BridgeType.ReturnedLambda -> lambdaDelegateCsharpType()

  // ADR-147 amendment: a nullable-bounded bare `T` is `T`, not `T?`; the instantiation says
  // whether it holds null.
  is BridgeType.Nullable ->
    if ((type as? BridgeType.TypeParameter)?.nullableFromBound == true) type.name
    else "${type.forwardPublicCsharpType()}?"
  // Exhaustive with no `else`: a new variant fails to compile here until it is spelled or named
  // unspellable. These four never reach a public signature; planning skips them by name first.
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> error("Forward CIR direct-value projection cannot render public type $this")
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

  // ADR-210: the same rule over a suspend lambda's components.
  is BridgeType.AsyncCallback -> parameters.all { parameter ->
    parameter.isPubliclySpellable(typeParametersInScope)
  } && result.isPubliclySpellable(typeParametersInScope)

  is BridgeType.FlowSource -> element.isPubliclySpellable(typeParametersInScope)

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
    BridgeType.Unit,
    is BridgeType.Primitive,
    BridgeType.Char,
    BridgeType.String,
    BridgeType.Instant,
    BridgeType.Duration,
    is BridgeType.Throwable,
    BridgeType.Uuid,
    is BridgeType.Enum,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    BridgeType.ByteArray,
    is BridgeType.Collection,
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
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

private fun BridgeType.isLambdaTypeArgument(isResult: Boolean): Boolean =
  if (this == BridgeType.Unit) isResult else isErasedBoxable()

/**
 * ADR-239: whether a value of this type crosses an ERASED position, a box `NugetMarshal.Wrap<T>`
 * mints and `NugetMarshal.FromHandle<T>` reads by its static C# type: a lambda's type argument
 * (`KotlinFunc<A, R>`), and a C# callback's result, which Kotlin reads back out of the box it
 * owns. One vocabulary for both, so a result a lambda property carries is a result a callback
 * parameter carries.
 */
internal fun BridgeType.isErasedBoxable(): Boolean = when (this) {
  is BridgeType.Primitive, BridgeType.Char, BridgeType.String, is BridgeType.Enum,
  is BridgeType.ObjectHandle, is BridgeType.Interface,
  // ADR-239: over the runtime's `nuget_wrap_*` / `nuget_unwrap_*` pairs for each.
  BridgeType.Instant, BridgeType.Duration, BridgeType.Uuid,
  // ADR-239: over `NugetMarshal.ReadBytes` / `CreateBytes`, a copy each way.
  BridgeType.ByteArray,
    -> true

  // ADR-239: a `Throwable` as its ADR-107 envelope out of Kotlin and its managed-exception text
  // into it, over the two generated throwable exports; only where a `NugetManagedException` fits.
  is BridgeType.Throwable -> acceptsManagedException

  // ADR-171: a value class with a box/unbox pair crosses an erased position; the same test the
  // planner mints that pair on, which it never does for a generic value class.
  is BridgeType.ValueClass -> typeArguments.isEmpty() && hasErasedCrossing()
  is BridgeType.Nullable ->
    type != BridgeType.Unit && type !is BridgeType.Nullable && type.isErasedBoxable()
  // ADR-215 lane A: an ADR-211 `fun <T>` own `T`, which `KotlinFunc<T, R>` boxes like any argument.
  is BridgeType.TypeParameter -> true
  // ADR-239: a closed collection, read and minted through `NugetMarshal`'s per-type tables. A `T`
  // component has no closed `typeof` to key a table entry on, and a .NET dictionary holds no null
  // key.
  is BridgeType.Collection -> !containsTypeParameter() && key !is BridgeType.Nullable &&
      listOfNotNull(element, key, value).all { component -> component.isErasedBoxable() }

  // ADR-239: a bound C# interface at an erased position, through `NugetMarshal`'s per-type bound
  // tables over the two generated box exports. A payload out of Kotlin needs a mintable bridge.
  is BridgeType.BoundInterface -> implementable

  BridgeType.Unit,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> false
}

/** ADR-239: whether a `T` appears anywhere inside this collection's components. */
private fun BridgeType.containsTypeParameter(): Boolean = when (this) {
  is BridgeType.TypeParameter -> true
  is BridgeType.Nullable -> type.containsTypeParameter()
  is BridgeType.Collection ->
    listOfNotNull(element, key, value).any { component -> component.containsTypeParameter() }
  BridgeType.Unit,
  is BridgeType.Primitive,
  BridgeType.Char,
  BridgeType.String,
  BridgeType.Instant,
  BridgeType.Duration,
  is BridgeType.Throwable,
  BridgeType.Uuid,
  is BridgeType.Enum,
  is BridgeType.ObjectHandle,
  is BridgeType.Interface,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> false
}

/** ADR-239: whether this erased type is, or holds, a boxed `ByteArray`. */
internal fun BridgeType.boxesBytes(): Boolean {
  val type: BridgeType = unwrapNullable()
  if (type == BridgeType.ByteArray) return true
  val collection: BridgeType.Collection = type as? BridgeType.Collection ?: return false
  return listOfNotNull(collection.element, collection.key, collection.value)
    .any { component -> component.boxesBytes() }
}

/**
 * ADR-239: every closed collection inside [this] erased type, outermost first, nested components
 * included, so each has its `NugetMarshal` table entry. Empty for a non-boxable type.
 */
internal fun BridgeType.boxedCollections(): List<BridgeType.Collection> {
  if (!isErasedBoxable()) return emptyList()
  val collection: BridgeType.Collection = unwrapNullable() as? BridgeType.Collection
    ?: return emptyList()
  return listOf(collection) +
      listOfNotNull(collection.element, collection.key, collection.value)
        .flatMap { component -> component.boxedCollections() }
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

/**
 * ADR-236 / ADR-239: the BCL delegate spelling of [this] lambda, wherever it is handed out of
 * Kotlin: `Func<..., R>` / `Action<...>`, or the ADR-210 `Func<..., CancellationToken, Task<R>>` /
 * `Func<..., CancellationToken, Task>` for a `suspend` one. The delegate is bound to the lambda's
 * `KotlinFunc` wrapper ([lambdaWrapperCsharpType]), whose own handle a setter passes straight back.
 */
internal fun BridgeType.ReturnedLambda.lambdaDelegateCsharpType(): String {
  val spelled: List<String> = typeArguments.map { argument -> argument.forwardPublicCsharpType() }
  val parameters: List<String> = spelled.dropLast(1)
  val result: String = spelled.last()
  if (suspend) {
    val task: String = if (result == "void") "global::System.Threading.Tasks.Task"
    else "global::System.Threading.Tasks.Task<$result>"
    return (parameters + "global::System.Threading.CancellationToken" + task)
      .joinToString(", ", "Func<", ">")
  }
  if (result != "void") return (parameters + result).joinToString(", ", "Func<", ">")
  return if (parameters.isEmpty()) "Action" else parameters.joinToString(", ", "Action<", ">")
}

/** ADR-236: the `KotlinFunc` (or sibling) wrapper that owns [this] lambda's handle. */
internal fun BridgeType.ReturnedLambda.lambdaWrapperCsharpType(): String = typeArguments
  .map { argument -> argument.forwardPublicCsharpType() }
  .let { spelled -> if (suspend) csSuspendLambdaType(spelled) else csLambdaType(spelled) }

/** ADR-236: the wrapper method a property getter binds its delegate to. */
internal fun BridgeType.ReturnedLambda.lambdaInvokeMethod(): String =
  if (suspend) "InvokeAsync" else "Invoke"

/**
 * ADR-239: the delegate over an OWNED lambda handle [pointer], bound to the wrapper that releases
 * it when the delegate is collected: `new KotlinFunc<A, R>(h).Invoke`.
 */
internal fun BridgeType.ReturnedLambda.lambdaDelegateRead(pointer: String): String =
  "new ${lambdaWrapperCsharpType()}($pointer).${lambdaInvokeMethod()}"

/** ADR-239: whether this erased type is, or holds, a `Throwable`. */
internal fun BridgeType.boxesThrowable(): Boolean {
  val type: BridgeType = unwrapNullable()
  if (type is BridgeType.Throwable) return true
  val collection: BridgeType.Collection = type as? BridgeType.Collection ?: return false
  return listOfNotNull(collection.element, collection.key, collection.value)
    .any { component -> component.boxesThrowable() }
}

/** ADR-239: every bound C# interface this erased type is, or holds. */
internal fun BridgeType.boundInterfaceComponents(): List<BridgeType.BoundInterface> {
  val type: BridgeType = unwrapNullable()
  if (type is BridgeType.BoundInterface) return listOf(type)
  val collection: BridgeType.Collection = type as? BridgeType.Collection ?: return emptyList()
  return listOfNotNull(collection.element, collection.key, collection.value)
    .flatMap { component -> component.boundInterfaceComponents() }
}

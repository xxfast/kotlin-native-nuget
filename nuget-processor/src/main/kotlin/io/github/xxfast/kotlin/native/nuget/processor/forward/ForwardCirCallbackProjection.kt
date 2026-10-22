package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirAsyncBegin
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirAsyncEnd
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirCallbackDelegate
import io.github.xxfast.kotlin.native.nuget.processor.freshName

/**
 * ADR-160: the C# half of a per-call callback parameter on the ADR-062 plan.
 *
 * Everything here is derived from the [BridgeType.Callback] alone, so the delegate the `Interop.cs`
 * helper block declares and the delegate the call site instantiates cannot drift: the name is a
 * function of the WIRE shape (ADR-036's injectivity rule), which is what makes it safe for two
 * members with different semantic payloads but one wire (`(Int) -> Unit` and `(Mood) -> Unit`, both
 * `void(int, IntPtr)`) to share a single declared delegate.
 *
 * The ADR-102 AOT-safe shape is untouched: Kotlin receives `NugetThunks.{Delegate}Ptr`, the address
 * of an `[UnmanagedCallersOnly]` thunk resolved at link time, plus the echoed ctx of the managed
 * delegate instance -- an ADR-161 table key, no longer a `GCHandle`. Nothing here calls
 * `Marshal.GetFunctionPointerForDelegate`.
 */
internal fun BridgeType.Callback.forwardCallbackDelegate(): CirCallbackDelegate =
  CirCallbackDelegate(
    forwardCallbackDelegateName(),
    forwardCallbackDelegateParameterList(),
    forwardCallbackDelegateReturnType(),
  )

/** `Nuget{Arg}..{Result}Callback`, the shipped ADR-036 naming, minted off the wire. */
internal fun BridgeType.Callback.forwardCallbackDelegateName(): String {
  val suffixes: String = parameters.joinToString("") { parameter -> parameter.wireSuffix() }
  // ADR-201 amendment: a Throwable RESULT is a `String` box on the wire, a payload an envelope.
  val resultSuffix: String = if (result is BridgeType.Throwable) "String" else result.wireSuffix()
  return "Nuget$suffixes${resultSuffix}Callback"
}

internal fun BridgeType.Callback.forwardCallbackDelegateReturnType(): String =
  result.callbackWireCsharpType()

/**
 * The delegate's own parameter list, ending in the echoed ADR-102 ctx slot. [names] are the
 * parameter names, `a0`, `a1`, ... then `ctx` unless the call site moved them
 * ([forwardCallbackLambdaNames]).
 */
internal fun BridgeType.Callback.forwardCallbackDelegateParameterList(
  names: List<String> = forwardCallbackLambdaNames(),
): String {
  val payload: List<String> = parameters.mapIndexed { index, parameter ->
    "${parameter.callbackWireCsharpType()} ${names[index]}"
  }
  return (payload + "IntPtr ${names.last()}").joinToString(", ", "(", ")")
}

/**
 * The call-site lambda's parameter names: `a0`, `a1`, ... then `ctx`, each moved by `freshName` off
 * [lambda], the user's callback parameter the lambda body invokes. Spelled the same, a lambda
 * parameter would shadow the user's lambda inside its own call (the other user parameters are
 * never read in that body, so only this one can collide).
 */
internal fun BridgeType.Callback.forwardCallbackLambdaNames(lambda: String? = null): List<String> {
  val taken: MutableSet<String> = listOfNotNull(lambda?.removePrefix("@")).toMutableSet()
  fun mint(base: String): String = freshName(base, taken).also { taken += it }
  return parameters.indices.map { index -> mint("a$index") } + mint("ctx")
}

/**
 * The call-site prelude: the managed delegate instance that calls the consumer's lambda, then the
 * ADR-161 table key the thunk dispatches through. Both are declared before the `try` so the
 * `finally` [forwardCallbackCleanup] writes can name the key; the registration itself is the
 * guarded statement.
 */
internal fun forwardCallbackPrelude(
  name: String,
  type: BridgeType.Callback,
  // ADR-164: an omittable defaulted lambda may be null. Unset, nothing is registered and the ctx
  // stays `IntPtr.Zero`, so no handle is minted; Kotlin reads its `IsSet` slot and never calls it.
  omittable: Boolean = false,
  // The stem of the `${local}Native` / `${local}Ctx` locals (`ForwardPublicParameter.localStem`),
  // which moves off a sibling user parameter spelled like either; [name] stays the user's lambda.
  local: String = name,
): ForwardCirHandleStep {
  val delegateName: String = type.forwardCallbackDelegateName()
  val lambdaNames: List<String> = type.forwardCallbackLambdaNames(name)
  val body: String = forwardCallbackBody(name, type, omittable, lambdaNames)
  return forwardCallbackPreludeSteps(name, type, omittable, local, delegateName, lambdaNames, body)
}

/**
 * ADR-231: the managed delegate over the consumer's lambda [name] as one expression, for a legacy
 * `suspend` or Flow member whose key `collectionScopedCall` registers. A `null` delegate throws
 * `ArgumentNullException` before anything is registered.
 */
internal fun forwardCallbackDelegateExpression(name: String, type: BridgeType.Callback): String {
  val lambdaNames: List<String> = type.forwardCallbackLambdaNames(name)
  val body: String = forwardCallbackBody(name, type, omittable = false, lambdaNames)
  val parameters: String = type.forwardCallbackDelegateParameterList(lambdaNames)
  val delegate: String = "new ${type.forwardCallbackDelegateName()}($parameters => { $body })"
  val bare: String = name.removePrefix("@")
  return "$name is null ? throw new ArgumentNullException(\"$bare\") : $delegate"
}

/** The delegate body that calls the consumer's lambda [name] and hands its result to the wire. */
private fun forwardCallbackBody(
  name: String,
  type: BridgeType.Callback,
  omittable: Boolean,
  lambdaNames: List<String>,
): String {
  val arguments: String = type.parameters
    .mapIndexed { index, parameter -> parameter.callbackArgumentExpression(lambdaNames[index]) }
    .joinToString(", ")
  val call: String = if (omittable) "$name!($arguments)" else "$name($arguments)"
  return when (val result: BridgeType = type.result) {
    BridgeType.Unit -> "$call;"
    // `WrapString` mints a StableRef box over the managed string; the Kotlin side reads it and
    // releases it (ADR-036's return-box rule, unchanged by ADR-160).
    BridgeType.String -> "return NugetMarshal.WrapString($call);"
    // ADR-201 amendment: the managed-exception text over the same box, held once so the user's
    // lambda runs exactly once.
    is BridgeType.Throwable -> {
      val held: String = freshName("mishap", (lambdaNames + name.removePrefix("@")).toSet())
      val text: String = managedExceptionTextCs(held, nullable = false)
      "var $held = $call; return NugetMarshal.WrapString($text);"
    }
    is BridgeType.Primitive -> if (result.kind == PrimitiveKind.BOOLEAN) {
      "return $call ? (byte)1 : (byte)0;"
    } else {
      "return $call;"
    }
    // ADR-215 lane A: always an OWNED box, since the thunk returns before Kotlin reads it: a
    // borrowed wrapper handle is retained through the generated `<lib>__retain_handle`.
    // ADR-231: an exported class result is the same owned box, read back at its class.
    is BridgeType.TypeParameter, is BridgeType.Nullable, is BridgeType.ObjectHandle ->
      "return NugetMarshal.WrapOwned<${result.forwardPublicCsharpType()}>($call);"

    BridgeType.Char,
    BridgeType.Instant,
    BridgeType.Duration,
    BridgeType.Uuid,
    is BridgeType.Enum,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
    BridgeType.ByteArray,
    is BridgeType.Collection,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> error("Forward CIR callback projection has no result lowering for $result")
  }
}

private fun forwardCallbackPreludeSteps(
  name: String,
  type: BridgeType.Callback,
  omittable: Boolean,
  local: String,
  delegateName: String,
  lambdaNames: List<String>,
  body: String,
): ForwardCirHandleStep {
  val declarations: List<String> = listOf(
    "$delegateName ${local}Native = ${type.forwardCallbackDelegateParameterList(lambdaNames)} =>",
    "{",
    "    $body",
    "};",
    "IntPtr ${local}Ctx = IntPtr.Zero;",
  )
  val register: String = "${local}Ctx = NugetThunks.RegisterCtx(${local}Native);"
  val statement: String = if (omittable) "if ($name is not null) $register" else register
  return ForwardCirHandleStep(
    flat = (declarations + statement).joinToString("\n"),
    declarations = declarations,
    statement = statement,
  )
}

/**
 * The `finally` half of [forwardCallbackPrelude]: the ADR-161 key is removed from the table on
 * every exit path, which is what makes an invocation that outlives this frame a lookup miss rather
 * than a read of whatever took the freed GCHandle's slot. `IntPtr.Zero` means the registration
 * itself is the statement that threw, so there is nothing to remove.
 */
internal fun forwardCallbackCleanup(name: String): String =
  "if (${name}Ctx != IntPtr.Zero) NugetThunks.UnregisterCtx(${name}Ctx);"

/** The two native arguments a callback parameter contributes, in the planner's slot order. */
internal fun forwardCallbackArguments(name: String, type: BridgeType.Callback): List<String> =
  listOf(
    "NugetThunks.${type.forwardCallbackDelegateName()}Ptr",
    "${name}Ctx",
  )

/**
 * The expression that turns one delegate parameter into the argument the consumer's lambda
 * declared. ADR-036 ownership, unchanged: a handle-passed payload is freed on THIS side --
 * `FromHandle<string>` disposes the handle as it reads it, and an exported object's wrapper takes
 * the raw handle so the wrapper's own `Dispose()` is the free -- which is why the Kotlin side
 * retains without releasing. A by-value scalar and an enum ordinal free nothing.
 */
private fun BridgeType.callbackArgumentExpression(slot: String): String = when (this) {
  is BridgeType.Primitive -> if (kind == PrimitiveKind.BOOLEAN) "$slot != 0" else slot
  is BridgeType.Enum -> "($csharpType)$slot"
  BridgeType.String -> "NugetMarshal.FromHandle<string>($slot)"
  is BridgeType.ObjectHandle -> "NugetMarshal.FromHandle<$csharpType>($slot)"
  is BridgeType.Interface -> "NugetMarshal.FromHandle<$csharpType>($slot)"
  // ADR-201 amendment: the ADR-107 envelope; `BuildException` disposes it as it reads.
  is BridgeType.Throwable -> "NugetErrorNative.BuildException($slot)"
  // ADR-215 lane A: a boxed `T`; `IntPtr.Zero` reads as null, and every box read is consumed.
  is BridgeType.TypeParameter, is BridgeType.Nullable ->
    "NugetMarshal.FromHandle<${forwardPublicCsharpType()}>($slot)"
  BridgeType.Unit,
  BridgeType.Char,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> error("Forward CIR callback projection has no payload lowering for $this")
}

/** The C ABI spelling of one callback component. `Boolean` rides a `byte`: `bool` is not a legal
 *  `[UnmanagedCallersOnly]` signature type, which is also why `Char` never joined. */
private fun BridgeType.callbackWireCsharpType(): String = when (this) {
  BridgeType.Unit -> "void"
  is BridgeType.Primitive ->
    if (kind == PrimitiveKind.BOOLEAN) "byte" else kind.forwardPublicCsharpType()

  is BridgeType.Enum -> "int"
  BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface,
  is BridgeType.Throwable,
  // ADR-215 lane A: a `T` or `T?` box.
  is BridgeType.TypeParameter, is BridgeType.Nullable -> "IntPtr"
  BridgeType.Char,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> error("Forward CIR callback projection has no wire type for $this")
}

/**
 * The delegate-name segment for one component. Keyed to the WIRE, never to the semantic type, so a
 * name can never promise a shape the delegate does not have: an enum crosses as its ordinal and is
 * therefore an `Int` segment, exactly like a `kotlin.Int`.
 */
private fun BridgeType.wireSuffix(): String = when (this) {
  BridgeType.Unit -> "Void"
  // Kotlin's own simple name (`UInt`, not `Uint`), the spelling the legacy callback routes use, so
  // a wire shared across routes dedupes to one delegate and one thunk.
  is BridgeType.Primitive -> if (kind == PrimitiveKind.BOOLEAN) "Bool" else kind.simpleKotlinName()

  is BridgeType.Enum -> "Int"
  BridgeType.String -> "String"
  is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Throwable,
  is BridgeType.TypeParameter, is BridgeType.Nullable -> "Object"
  BridgeType.Char,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> error("Forward CIR callback projection has no delegate name segment for $this")
}

// ------------------------------------------------------------------------------------------------
// ADR-210: a suspend lambda parameter, `suspend (T) -> R` as `Func<T, CancellationToken, Task<R>>`.
// ------------------------------------------------------------------------------------------------

private const val CANCELLATION_TOKEN: String = "global::System.Threading.CancellationToken"
private const val TASK: String = "global::System.Threading.Tasks.Task"

/** The consumer's delegate type: the payloads, the token Kotlin cancellation reaches, the task. */
internal fun BridgeType.AsyncCallback.forwardAsyncCallbackCsharpType(): String {
  val payload: List<String> = parameters.map { parameter -> parameter.forwardPublicCsharpType() }
  val task: String =
    if (result == BridgeType.Unit) TASK else "$TASK<${result.forwardPublicCsharpType()}>"
  return (payload + CANCELLATION_TOKEN + task).joinToString(", ", "Func<", ">")
}

/** `NugetAsync{Payload}..Begin`, minted off the payload WIRE like the ADR-036 delegate names. */
internal fun BridgeType.AsyncCallback.forwardAsyncBeginName(): String =
  "NugetAsync${parameters.joinToString("") { parameter -> parameter.wireSuffix() }}Begin"

/** `NugetAsync{Result}End`, one per result type: the End thunk casts the `Task<R>` it names. */
internal fun BridgeType.AsyncCallback.forwardAsyncEndName(): String =
  "NugetAsync${result.wireSuffix()}End"

internal fun BridgeType.AsyncCallback.forwardAsyncBegin(): CirAsyncBegin =
  CirAsyncBegin(
    forwardAsyncBeginName(),
    parameters.map { parameter -> parameter.callbackWireCsharpType() },
  )

/**
 * The End thunk of this result type. The read goes through `NugetTasks.End`, which is
 * `GetAwaiter().GetResult()` with the task handle freed in its `finally`; a `String` is boxed only
 * here, after the read is certain, so the cancel-then-complete path has no box to leak (ADR-210
 * alternative 8).
 */
internal fun BridgeType.AsyncCallback.forwardAsyncEnd(): CirAsyncEnd {
  val name: String = forwardAsyncEndName()
  return when (val result: BridgeType = result) {
    BridgeType.Unit -> CirAsyncEnd(name, "void", "NugetTasks.End(a0)")
    BridgeType.String ->
      CirAsyncEnd(name, "IntPtr", "NugetMarshal.WrapString(NugetTasks.End<string>(a0))")

    is BridgeType.Primitive -> if (result.kind == PrimitiveKind.BOOLEAN) {
      CirAsyncEnd(name, "byte", "NugetTasks.End<bool>(a0) ? (byte)1 : (byte)0")
    } else {
      val type: String = result.kind.forwardPublicCsharpType()
      CirAsyncEnd(name, type, "NugetTasks.End<$type>(a0)")
    }

    // ADR-215 lane C: the Begin registration stored a `NugetBoxedTask<T>`, which boxes the result
    // through `WrapOwned<T>` on the read, so Kotlin always owns the box it reads.
    is BridgeType.TypeParameter, is BridgeType.Nullable ->
      CirAsyncEnd(name, "IntPtr", "NugetTasks.EndBoxed(a0)")

    BridgeType.Char,
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
    is BridgeType.Collection,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> error("Forward CIR projection has no suspend lambda result lowering for $result")
  }
}

/**
 * The expression that registers the managed delegate for a suspend lambda parameter [name] and
 * evaluates to its ADR-161 key. The delegate only adapts payloads and hands the consumer's `Task`
 * to `NugetTasks.Begin`; it converts no result (alternative 8).
 *
 * OWNERSHIP. C# owns the key from this expression until the native call has RETURNED; from then on
 * the Kotlin holder owns it, and its cleaner calls the release-context thunk (which is what lets a
 * constructor or a non-suspend member store the lambda). So every call site registers into a local
 * declared ahead of a guarded span, zeroes that local as the statement after the native call, and
 * unregisters it in the span's `finally`: a later argument's guard or marshalling that throws, a
 * second registration that throws, and a P/Invoke that throws before reaching Kotlin all release
 * every key registered so far. [forwardAsyncCallbackPrelude] is that shape on the plan route,
 * `collectionScopedCall` on the legacy suspend route.
 */
internal fun forwardAsyncCallbackRegistration(
  name: String,
  type: BridgeType.AsyncCallback,
): String {
  val taken: MutableSet<String> = mutableSetOf(name.removePrefix("@"))
  fun mint(base: String): String = freshName(base, taken).also { taken += it }
  val payloads: List<String> = type.parameters.indices.map { index -> mint("a$index") }
  val callback: String = mint("callback")
  val awaitCtx: String = mint("awaitCtx")
  val token: String = mint("token")
  val arguments: String = (
      type.parameters.mapIndexed { index, parameter ->
        parameter.callbackArgumentExpression(payloads[index])
      } + token
      ).joinToString(", ")
  // ADR-215 lane C: a `T` result is stored as a `NugetBoxedTask<T>`, which `EndBoxed` reads.
  val begin: String = if (type.boxesIntoKotlin()) {
    "NugetTasks.BeginBoxed<${type.result.forwardPublicCsharpType()}>"
  } else {
    "NugetTasks.Begin"
  }
  val delegate: String = "(${type.forwardAsyncBeginName()})(" +
      "(${(payloads + callback + awaitCtx).joinToString(", ")}) => " +
      "$begin($token => $name($arguments), $callback, $awaitCtx))"
  return "NugetTasks.Register($name, nameof($name), $delegate)"
}

/** `NugetThunks.UnregisterCtx` is a no-op on `IntPtr.Zero`, so a handed-over key needs no flag. */
internal fun forwardAsyncCallbackHandOver(keyLocal: String): String = "$keyLocal = IntPtr.Zero;"

internal fun forwardAsyncCallbackCleanup(keyLocal: String): String =
  "NugetThunks.UnregisterCtx($keyLocal);"

// ------------------------------------------------------------------------------------------------
// ADR-210: a `Flow<T>` parameter, as `IAsyncEnumerable<T>`, and the helpers both kinds share.
// ------------------------------------------------------------------------------------------------

/** `NugetAsync{Element}Current`, one per element type: the thunk casts the enumeration it names. */
internal fun BridgeType.FlowSource.forwardAsyncCurrentName(): String =
  "NugetAsync${element.wireSuffix()}Current"

/**
 * The Current thunk of this element type, in the End thunk's model: `a0` is the enumeration handle.
 * A `String` is boxed here, on the read, so nothing is minted for an element nobody asks for.
 */
internal fun BridgeType.FlowSource.forwardAsyncCurrent(): CirAsyncEnd {
  val name: String = forwardAsyncCurrentName()
  val public: String = element.forwardPublicCsharpType()
  return when {
    element == BridgeType.String -> CirAsyncEnd(
      name,
      "IntPtr",
      "NugetMarshal.WrapString(NugetAsyncEnumeration.Current<string>(a0))",
    )

    // ADR-215 lane C: an owned box per element read (`WrapOwned<T>`), nothing for one never read.
    element.unwrapNullable() is BridgeType.TypeParameter ->
      CirAsyncEnd(name, "IntPtr", "NugetAsyncEnumeration.CurrentBoxed(a0)")

    element is BridgeType.Primitive && element.kind == PrimitiveKind.BOOLEAN ->
      CirAsyncEnd(name, "byte", "NugetAsyncEnumeration.Current<bool>(a0) ? (byte)1 : (byte)0")

    else -> CirAsyncEnd(name, public, "NugetAsyncEnumeration.Current<$public>(a0)")
  }
}

/**
 * The expression that registers either asynchronous parameter [name] and evaluates to its key. A
 * `Flow` registers a factory, not the enumerable: the shared Enumerate thunk cannot name `T`, so
 * the closure does, and each call of it is one `GetAsyncEnumerator` (one Kotlin `collect`).
 */
internal fun forwardAsyncRegistration(name: String, type: BridgeType): String =
  when (type.legacyAsyncKind()) {
    // ADR-231: the ADR-160 delegate, registered into the same key table its thunk reads.
    ForwardAsyncKind.CALLBACK ->
      "NugetThunks.RegisterCtx(" +
          "${forwardCallbackDelegateExpression(name, type as BridgeType.Callback)})"

    ForwardAsyncKind.LAMBDA ->
      forwardAsyncCallbackRegistration(name, type as BridgeType.AsyncCallback)

    ForwardAsyncKind.FLOW -> {
      // The lambda runs later, outside the null check's flow state, hence the `!`.
      val bang: String = if (type.isNullableFlowSource()) "!" else ""
      val register: String = "NugetTasks.Register($name, nameof($name), " +
          "(Func<IntPtr>)(() => NugetAsyncEnumeration.Enumerate($name$bang)))"
      // ADR-210 amendment: a `null` source registers nothing; the zero key is Kotlin's `null`.
      if (type.isNullableFlowSource()) "$name is null ? IntPtr.Zero : $register" else register
    }

    null -> error("Forward CIR projection has no asynchronous registration for $type")
  }

private fun ForwardAsyncShared.thunkPointer(): String = when (this) {
  ForwardAsyncShared.RELEASE_TASK -> "NugetThunks.NugetAsyncReleaseTaskPtr"
  ForwardAsyncShared.RELEASE_CANCELLATION -> "NugetThunks.NugetAsyncReleaseCancellationPtr"
  ForwardAsyncShared.MOVE_NEXT_BEGIN -> "NugetThunks.NugetAsyncMoveNextBeginPtr"
  ForwardAsyncShared.MOVE_NEXT_END -> "NugetThunks.NugetAsyncMoveNextEndPtr"
  ForwardAsyncShared.DISPOSE_ENUMERATION -> "NugetThunks.NugetAsyncDisposeEnumerationPtr"
  ForwardAsyncShared.RELEASE_CONTEXT -> "NugetThunks.NugetAsyncReleaseContextPtr"
}

/**
 * The native arguments either asynchronous parameter contributes, in the planner's slot order: its
 * two own thunks and [key], then every parameter-independent thunk it is first to need
 * ([ForwardAsyncSlots.carried]), so each of those crosses once per export.
 */
internal fun forwardAsyncArguments(
  type: BridgeType,
  key: String,
  slots: ForwardAsyncSlots,
): List<String> {
  val own: List<String> = when (type.legacyAsyncKind()) {
    ForwardAsyncKind.CALLBACK ->
      listOf("NugetThunks.${(type as BridgeType.Callback).forwardCallbackDelegateName()}Ptr")

    ForwardAsyncKind.LAMBDA -> {
      val callback: BridgeType.AsyncCallback = type as BridgeType.AsyncCallback
      listOf(
        "NugetThunks.${callback.forwardAsyncBeginName()}Ptr",
        "NugetThunks.${callback.forwardAsyncEndName()}Ptr",
      )
    }

    ForwardAsyncKind.FLOW -> listOf(
      "NugetThunks.NugetAsyncEnumeratePtr",
      "NugetThunks.${(type as BridgeType.FlowSource).forwardAsyncCurrentName()}Ptr",
    )

    null -> error("Forward CIR projection has no asynchronous arguments for $type")
  }
  return own + key + slots.carried.map { slot -> slot.thunkPointer() }
}

/** The plan route's guarded span for either asynchronous parameter. See the registration above. */
internal fun forwardAsyncPrelude(
  name: String,
  type: BridgeType,
  keyLocal: String,
): ForwardCirHandleStep {
  val register: String = "$keyLocal = ${forwardAsyncRegistration(name, type)};"
  return ForwardCirHandleStep(
    flat = "IntPtr $register",
    declarations = listOf("IntPtr $keyLocal = IntPtr.Zero;"),
    statement = register,
    afterCall = forwardAsyncCallbackHandOver(keyLocal),
  )
}

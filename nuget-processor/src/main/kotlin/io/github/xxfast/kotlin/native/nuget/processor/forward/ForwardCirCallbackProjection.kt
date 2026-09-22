package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirCallbackDelegate

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
 * of an `[UnmanagedCallersOnly]` thunk resolved at link time, plus the `GCHandle` of the managed
 * delegate instance as the echoed ctx. Nothing here calls
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
  return "Nuget$suffixes${result.wireSuffix()}Callback"
}

internal fun BridgeType.Callback.forwardCallbackDelegateReturnType(): String =
  result.callbackWireCsharpType()

/** The delegate's own parameter list, ending in the echoed ADR-102 ctx slot. */
internal fun BridgeType.Callback.forwardCallbackDelegateParameterList(): String {
  val payload: List<String> = parameters.mapIndexed { index, parameter ->
    "${parameter.callbackWireCsharpType()} a$index"
  }
  return (payload + "IntPtr ctx").joinToString(", ", "(", ")")
}

/**
 * The call-site prelude: the managed delegate instance that calls the consumer's lambda, then the
 * `GCHandle` the thunk dispatches through. Both are declared before the `try` so the `finally`
 * [forwardCallbackCleanup] writes can name the handle; the alloc itself is the guarded statement.
 */
internal fun forwardCallbackPrelude(
  name: String,
  type: BridgeType.Callback,
): ForwardCirHandleStep {
  val delegateName: String = type.forwardCallbackDelegateName()
  val arguments: String = type.parameters
    .mapIndexed { index, parameter -> parameter.callbackArgumentExpression("a$index") }
    .joinToString(", ")
  val call: String = "$name($arguments)"
  val body: String = when (val result: BridgeType = type.result) {
    BridgeType.Unit -> "$call;"
    // `WrapString` mints a StableRef box over the managed string; the Kotlin side reads it and
    // releases it (ADR-036's return-box rule, unchanged by ADR-160).
    BridgeType.String -> "return NugetMarshal.WrapString($call);"
    is BridgeType.Primitive -> if (result.kind == PrimitiveKind.BOOLEAN) {
      "return $call ? (byte)1 : (byte)0;"
    } else {
      "return $call;"
    }

    else -> error("Forward CIR callback projection has no result lowering for $result")
  }
  val declarations: List<String> = listOf(
    "$delegateName ${name}Native = ${type.forwardCallbackDelegateParameterList()} =>",
    "{",
    "    $body",
    "};",
    "IntPtr ${name}Ctx = IntPtr.Zero;",
  )
  val statement = "${name}Ctx = NugetThunks.RegisterCtx(${name}Native);"
  return ForwardCirHandleStep(
    flat = (declarations + statement).joinToString("\n"),
    declarations = declarations,
    statement = statement,
  )
}

/**
 * The `finally` half of [forwardCallbackPrelude]: the ADR-161 key is removed from the table on every
 * exit path, which is what makes an invocation that outlives this frame a lookup miss rather than a
 * read of whatever took the freed GCHandle's slot. `IntPtr.Zero` means the registration itself is
 * the statement that threw, so there is nothing to remove.
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
  else -> error("Forward CIR callback projection has no payload lowering for $this")
}

/** The C ABI spelling of one callback component. `Boolean` rides a `byte`: `bool` is not a legal
 *  `[UnmanagedCallersOnly]` signature type, which is also why `Char` never joined. */
private fun BridgeType.callbackWireCsharpType(): String = when (this) {
  BridgeType.Unit -> "void"
  is BridgeType.Primitive ->
    if (kind == PrimitiveKind.BOOLEAN) "byte" else kind.forwardPublicCsharpType()

  is BridgeType.Enum -> "int"
  BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface -> "IntPtr"
  else -> error("Forward CIR callback projection has no wire type for $this")
}

/**
 * The delegate-name segment for one component. Keyed to the WIRE, never to the semantic type, so a
 * name can never promise a shape the delegate does not have: an enum crosses as its ordinal and is
 * therefore an `Int` segment, exactly like a `kotlin.Int`.
 */
private fun BridgeType.wireSuffix(): String = when (this) {
  BridgeType.Unit -> "Void"
  is BridgeType.Primitive ->
    if (kind == PrimitiveKind.BOOLEAN) "Bool" else kind.name.lowercase().replaceFirstChar {
      it.uppercase()
    }

  is BridgeType.Enum -> "Int"
  BridgeType.String -> "String"
  is BridgeType.ObjectHandle, is BridgeType.Interface -> "Object"
  else -> error("Forward CIR callback projection has no delegate name segment for $this")
}

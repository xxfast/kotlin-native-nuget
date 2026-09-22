package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderCallbackDelegateHelper(helper: CirCallbackDelegateHelper) {
  helper.delegates.forEach { delegate ->
    appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
    appendLine("    internal delegate ${delegate.returnType} ${delegate.name}${delegate.paramList};")
    appendLine()
  }

  // ADR-102: one ahead-of-time compiled [UnmanagedCallersOnly] thunk per delegate shape. The
  // managed closure still exists and is still the thing that runs; what changes is how Kotlin
  // learns its address -- through `&Thunk` (a link-time constant) plus the GCHandle ctx every
  // forward callback ABI already echoes, instead of Marshal.GetFunctionPointerForDelegate, which
  // needs a runtime code generator that Mono full-AOT and NativeAOT do not have.
  renderThunkClass {
    appendCtxKeyTable()
    helper.delegates.forEach { delegate ->
      appendCtxDispatchThunk(delegate.name, delegate.paramList, delegate.returnType)
    }
  }
}

/**
 * ADR-161 part C: the never-reused key table that replaces the raw `GCHandle` as every user-code
 * thunk's ctx.
 *
 * A `GCHandle` cannot be validated after it is freed. Its slots come off a LIFO free list, so the
 * very next `GCHandle.Alloc` takes the slot a just-freed subscription released, and a Kotlin
 * invocation that lands after `Dispose()` then reads a live handle belonging to somebody else:
 * measured deterministic, and the observable result is that the WRONG listener runs, with nothing
 * thrown for a thunk to catch. A monotonic key is never handed out twice, so a late invocation is a
 * lookup MISS and the thunk can say so.
 *
 * `ConcurrentDictionary` because the three parties (the subscribing thread, the disposing thread and
 * the Kotlin thread that invokes) are unsynchronised by construction. `TryRemove` drops the only
 * strong reference the table holds, so a removed delegate is collectable exactly as the freed
 * `GCHandle` made it collectable before.
 */
private fun StringBuilder.appendCtxKeyTable() {
  appendLine(
    "        private static readonly System.Collections.Concurrent" +
        ".ConcurrentDictionary<IntPtr, object> _ctxTable = new();"
  )
  // Starts at zero and is PRE-incremented, so the first key handed out is 1 and IntPtr.Zero is
  // never a live key: the call sites use zero as their "nothing registered yet" sentinel.
  appendLine("        private static long _ctxNextKey;")
  appendLine()
  appendLine("        internal static IntPtr RegisterCtx(object target)")
  appendLine("        {")
  appendLine(
    "            IntPtr key = (IntPtr)System.Threading.Interlocked.Increment(ref _ctxNextKey);"
  )
  appendLine("            _ctxTable[key] = target;")
  appendLine("            return key;")
  appendLine("        }")
  appendLine()
  appendLine("        internal static void UnregisterCtx(IntPtr key)")
  appendLine("        {")
  appendLine("            if (key != IntPtr.Zero) _ctxTable.TryRemove(key, out _);")
  appendLine("        }")
  appendLine()
  appendLine("        internal static object? LookupCtx(IntPtr key)")
  appendLine("        {")
  appendLine("            _ctxTable.TryGetValue(key, out object? target);")
  appendLine("            return target;")
  appendLine("        }")
  appendLine()
}

/**
 * The `NugetThunks` container. Emitted as `partial` because the four renderers that own callback
 * shapes (this one, [renderAsyncHelper], [renderFlowHelper] via CirFlowRenderer, and the bridge
 * delegates merged into the shared delegate list) each contribute their own block, and each is
 * gated on its own helper being present.
 */
internal fun StringBuilder.renderThunkClass(body: StringBuilder.() -> Unit) {
  appendLine("    internal static unsafe partial class NugetThunks")
  appendLine("    {")
  body()
  appendLine("    }")
  appendLine()
}

/**
 * The ABI-level parameter types of a delegate parameter list, e.g. `(IntPtr a, byte b)` ->
 * [IntPtr, byte].
 */
internal fun thunkParameterTypes(paramList: String): List<String> =
  paramList.trim().removePrefix("(").removeSuffix(")")
    .split(",")
    .map { part -> part.trim() }
    .filter { part -> part.isNotEmpty() }
    .map { part -> part.substringBefore(' ') }

/**
 * One thunk + pointer-getter pair for a delegate whose LAST parameter is the echoed ctx, where the
 * ctx is the ADR-161 table key of the delegate instance (never a `GCHandle`, see
 * [appendCtxKeyTable]).
 */
internal fun StringBuilder.appendCtxDispatchThunk(
  name: String,
  paramList: String,
  returnType: String,
  viaKeyTable: Boolean = true,
) {
  val types: List<String> = thunkParameterTypes(paramList)
  val parameters: String = types.mapIndexed { index, type -> "$type a$index" }.joinToString(", ")
  val arguments: String = types.indices.joinToString(", ") { index -> "a$index" }
  val ctx: String = "a${types.size - 1}"
  val invocation: String = if (viaKeyTable) {
    "(($name)target)($arguments)"
  } else {
    "(($name)GCHandle.FromIntPtr($ctx).Target!)($arguments)"
  }
  // ADR-161: only the user-code thunks (this function's callers, which are the four rows of the
  // memo's table plus the bridge release thunk) take the trailing error slot. The Flow and async
  // thunk families call `appendThunkBody` directly and keep today's arity and FailFast, because the
  // published `nuget-runtime` klib invokes them and they run no user C# code.
  appendThunkBody(
    name,
    "$parameters, IntPtr* errOut",
    returnType,
    invocation,
    errOut = true,
    preamble = if (viaKeyTable) ctxLookupPreamble(name, ctx, returnType) else emptyList(),
  )
  appendThunkPointer(name, types + "IntPtr*", returnType)
}

/**
 * ADR-161 part C, the miss branch: what a thunk does when the key it was handed is no longer in the
 * table, i.e. Kotlin invoked after C# disposed the subscription or after the per-call frame
 * returned.
 *
 * What-question 4, approved: a `void` shape is **dropped**. The consumer unsubscribed, so silence is
 * what they asked for, and it is also the only answer the racing-emitter cell can survive: reporting
 * an error there would surface a `KotlinException` on the emitting thread. A value-returning shape
 * has nothing honest to return (a `default` would become an NPE at the Kotlin call site, and `0` or
 * `""` would be a lie), so it throws `ObjectDisposedException`, which the shared catch below reports
 * through part B's `errOut` channel: Kotlin sees a `NugetManagedException` naming it and can catch
 * it at the invocation site.
 */
private fun ctxLookupPreamble(name: String, ctx: String, returnType: String): List<String> =
  listOf(
    "object? target = LookupCtx($ctx);",
    "if (target is null)",
    "{",
  ) + if (returnType == "void") {
    listOf("    return;")
  } else {
    listOf("    throw new ObjectDisposedException(\"$name\");")
  } + listOf("}")

/**
 * The shared thunk shell: the attribute, the catch-all, and ADR-102's decided exception discipline
 * -- a managed exception must never unwind through a native frame, so it fails the process loudly
 * instead of corrupting it silently.
 */
internal fun StringBuilder.appendThunkBody(
  name: String,
  parameters: String,
  returnType: String,
  invocation: String,
  errOut: Boolean = false,
  preamble: List<String> = emptyList(),
) {
  val isVoid: Boolean = returnType == "void"
  appendLine(
    "        [UnmanagedCallersOnly(CallConvs = new[] " +
        "{ typeof(global::System.Runtime.CompilerServices.CallConvCdecl) })]"
  )
  appendLine("        internal static $returnType ${name}Thunk($parameters)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  // ADR-161 part C: the ctx resolution happens INSIDE the try, so a miss's throw is contained by
  // the same catch that contains a user-code throw.
  preamble.forEach { line -> appendLine("                $line") }
  if (isVoid) {
    appendLine("                $invocation;")
  } else {
    appendLine("                return $invocation;")
  }
  appendLine("            }")
  appendLine("            catch (Exception ex)")
  appendLine("            {")
  if (errOut) {
    // ADR-161: the error channel, and its own failure mode. The nested try is load-bearing: an
    // OLD runtime under new generated C# has no `nuget_managed_error_create`, so the P/Invoke
    // throws `EntryPointNotFoundException` right here, and the inner catch turns that into a loud
    // FailFast instead of an exception unwinding out of an [UnmanagedCallersOnly] frame.
    appendLine("                if (errOut != null)")
    appendLine("                {")
    appendLine("                    try")
    appendLine("                    {")
    appendLine("                        *errOut = NugetErrorNative.CreateManagedError(ex);")
    appendLine("                    }")
    appendLine("                    catch (Exception channelFailure)")
    appendLine("                    {")
    appendLine(
      "                        Environment.FailFast(" +
          "\"nuget: error channel failed in $name\", channelFailure);"
    )
    appendLine("                    }")
    if (isVoid) {
      appendLine("                    return;")
    } else {
      appendLine("                    return default;")
    }
    appendLine("                }")
  }
  appendLine("                Environment.FailFast(\"nuget: unhandled exception in $name\", ex);")
  if (!isVoid) {
    appendLine("                return default;")
  }
  appendLine("            }")
  appendLine("        }")
  appendLine()
}

/** `&Thunk` as an IntPtr -- the address Kotlin receives, resolved at link time, not at runtime. */
internal fun StringBuilder.appendThunkPointer(
  name: String,
  parameterTypes: List<String>,
  returnType: String,
) {
  val signature: String = (parameterTypes + returnType).joinToString(", ")
  appendLine("        internal static IntPtr ${name}Ptr =>")
  appendLine("            (IntPtr)(delegate* unmanaged[Cdecl]<$signature>)&${name}Thunk;")
  appendLine()
}

internal fun StringBuilder.renderSubscriptionHelper(@Suppress("UNUSED_PARAMETER") helper: CirSubscriptionHelper) {
  appendLine("    internal sealed class NugetSubscription : IDisposable")
  appendLine("    {")
  appendLine("        private Action? _disposeAction;")
  appendLine()
  appendLine("        internal NugetSubscription(Action disposeAction) => _disposeAction = disposeAction;")
  appendLine()
  appendLine("        public void Dispose()")
  appendLine("        {")
  appendLine("            Action? action = Interlocked.Exchange(ref _disposeAction, null);")
  appendLine("            action?.Invoke();")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

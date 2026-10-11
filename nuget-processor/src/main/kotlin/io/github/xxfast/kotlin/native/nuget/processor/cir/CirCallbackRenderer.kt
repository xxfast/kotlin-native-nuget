package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderCallbackDelegateHelper(helper: CirCallbackDelegateHelper) {
  helper.delegates.forEach { delegate ->
    appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
    appendLine("    internal delegate ${delegate.returnType} ${delegate.name}${delegate.paramList};")
    appendLine()
  }
  renderAsyncCallbackDeclarations(helper)

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
    appendAsyncCallbackThunks(helper)
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
 * `ConcurrentDictionary` because the three parties (the subscribing thread, the disposing thread
 * and the Kotlin thread that invokes) are unsynchronised by construction. `TryRemove` drops the
 * only strong reference the table holds, so a removed delegate is collectable exactly as the freed
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
  errOut: Boolean = viaKeyTable,
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
  // ADR-161: only the user-code thunks take the trailing error slot and the key-table lookup, and
  // [viaKeyTable] selects exactly that set. The async completion family renders through here too
  // (`renderAsyncHelper`), and it is NOT user code: the published `nuget-runtime` klib invokes it,
  // at the arity its own `CFunction` type spells (`launchForCSharp`, four parameters), so it must
  // keep today's arity and FailFast. Giving it the slot anyway means the thunk reads `errOut` from
  // a stack slot the caller never supplied and, on its catch path, writes a managed handle through
  // whatever is there -- ADR-104's stated failure mode and the ADR-053 `SIGBUS` family.
  // `Tier1CallbackArityAgreementTest` pins both halves, including this family against the
  // runtime's signature. The Flow family calls `appendThunkBody` directly and was never affected.
  appendThunkBody(
    name,
    if (errOut) "$parameters, IntPtr* errOut" else parameters,
    returnType,
    invocation,
    errOut = errOut,
    preamble = if (viaKeyTable) ctxLookupPreamble(name, ctx, returnType, types) else emptyList(),
  )
  appendThunkPointer(name, if (errOut) types + "IntPtr*" else types, returnType)
}

/**
 * ADR-161 part C, the miss branch: what a thunk does when the key it was handed is no longer in the
 * table, i.e. Kotlin invoked after C# disposed the subscription or after the per-call frame
 * returned.
 *
 * What-question 4, approved: a `void` shape is **dropped**. The consumer unsubscribed, so silence
 * is what they asked for, and it is also the only answer the racing-emitter cell can survive:
 * reporting an error there would surface a `KotlinException` on the emitting thread. A
 * value-returning shape has nothing honest to return (a `default` would become an NPE at the
 * Kotlin call site, and `0` or `""` would be a lie), so it throws `ObjectDisposedException`, which
 * the shared catch below reports through part B's `errOut` channel: Kotlin sees a
 * `NugetManagedException` naming it and can catch it at the invocation site.
 *
 * Either way the delegate never runs, so neither does the read that would have freed a
 * handle-passed argument (`FromHandle`, a wrapper's constructor, `BuildException`). Kotlin minted
 * each one with `NugetHandles.retain` before the call, on all four key-table routes (stored
 * callback, listener interface pair, per-call lambda, ADR-084 slot): every `IntPtr` slot ahead of
 * the ctx is such a `StableRef` or zero, and every by-value scalar crosses as its own C# type. So
 * the miss frees each `IntPtr` argument slot first, with the same `nuget_dispose` that read would
 * have ended in. [types] is the thunk's own parameter list, ctx last.
 */
private fun ctxLookupPreamble(
  name: String,
  ctx: String,
  returnType: String,
  types: List<String>,
): List<String> {
  val releases: List<String> = types.dropLast(1).mapIndexedNotNull { index, type ->
    if (type == "IntPtr") "    if (a$index != IntPtr.Zero) NugetMarshal.Dispose(a$index);" else null
  }
  return listOf(
    "object? target = LookupCtx($ctx);",
    "if (target is null)",
    "{",
  ) + releases + if (returnType == "void") {
    listOf("    return;")
  } else {
    listOf("    throw new ObjectDisposedException(\"$name\");")
  } + listOf("}")
}

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
  // The owner this subscription is attached to (`NugetKotlinHandle.Attach`), which unregisters it
  // when the owner is released. The exchange below is the single gate between that drain and the
  // consumer's own `Dispose()`: whichever runs second is a silent no-op.
  appendLine("        private readonly NugetKotlinHandle? _owner;")
  appendLine()
  appendLine("        internal NugetSubscription(Action disposeAction, NugetKotlinHandle? owner = null)")
  appendLine("        {")
  appendLine("            _disposeAction = disposeAction;")
  appendLine("            _owner = owner;")
  appendLine("        }")
  appendLine()
  appendLine("        public void Dispose()")
  appendLine("        {")
  appendLine("            Action? action = Interlocked.Exchange(ref _disposeAction, null);")
  appendLine("            if (action is null) return;")
  appendLine("            try")
  appendLine("            {")
  appendLine("                action();")
  appendLine("            }")
  appendLine("            finally")
  appendLine("            {")
  appendLine("                _owner?.Detach(this);")
  appendLine("            }")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

/**
 * ADR-210: the managed half of a suspend lambda parameter, emitted only when a bound member takes
 * one. One managed-only delegate per Begin payload shape (what the context table holds, never
 * marshalled, so it carries no `UnmanagedFunctionPointer`), and `NugetTasks`, the forward twin of
 * the reverse shim's class of the same name (which lives in `<root>.NugetReverse`, so the two never
 * collide). Its rules are the reverse shim's, copied:
 *
 *  - `Attach` continues on `TaskScheduler.Default` with `TaskContinuationOptions.None`, and frees
 *    the task handle itself when attaching throws, because then nothing else ever can;
 *  - `Begin` mints the `CancellationTokenSource` BEFORE the user call and allocates its handle
 *    LAST, so nothing above the allocation can leak it;
 *  - `End` reads through `GetAwaiter().GetResult()` and frees the task handle in `finally`;
 *  - `ReleaseCancellation` frees the handle, then disposes, or queues `Cancel()` off-thread.
 */
private fun StringBuilder.renderAsyncCallbackDeclarations(helper: CirCallbackDelegateHelper) {
  if (helper.asyncBegins.isEmpty()) return
  helper.asyncBegins.forEach { begin ->
    val parameters: String = (
        begin.payloadTypes.mapIndexed { index, type -> "$type a$index" } +
            listOf("IntPtr callback", "IntPtr awaitCtx")
        ).joinToString(", ")
    appendLine("    internal delegate IntPtr ${begin.name}($parameters);")
    appendLine()
  }
  val task = "global::System.Threading.Tasks.Task"
  val source = "global::System.Threading.CancellationTokenSource"
  val token = "global::System.Threading.CancellationToken"
  appendLine("    internal static unsafe class NugetTasks")
  appendLine("    {")
  appendLine("        internal static IntPtr Register(object? lambda, string name, object begin)")
  appendLine("        {")
  appendLine("            if (lambda is null) throw new ArgumentNullException(name);")
  appendLine("            return NugetThunks.RegisterCtx(begin);")
  appendLine("        }")
  appendLine()
  appendLine("        internal static void Attach($task task, IntPtr callback, IntPtr ctx)")
  appendLine("        {")
  appendLine("            ArgumentNullException.ThrowIfNull(task);")
  appendLine("            IntPtr handle = GCHandle.ToIntPtr(GCHandle.Alloc(task));")
  appendLine("            try")
  appendLine("            {")
  appendLine("                task.ContinueWith(")
  appendLine("                    static (_, state) =>")
  appendLine("                    {")
  appendLine("                        var (cb, h, c) = ((IntPtr, IntPtr, IntPtr))state!;")
  appendLine(
    "                        ((delegate* unmanaged[Cdecl]<IntPtr, IntPtr, void>)cb)(h, c);"
  )
  appendLine("                    },")
  appendLine("                    (callback, handle, ctx),")
  appendLine("                    $token.None,")
  appendLine("                    global::System.Threading.Tasks.TaskContinuationOptions.None,")
  appendLine("                    global::System.Threading.Tasks.TaskScheduler.Default);")
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                GCHandle.FromIntPtr(handle).Free();")
  appendLine("                throw;")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine(
    "        internal static IntPtr Begin(Func<$token, $task> start, IntPtr callback, IntPtr ctx)"
  )
  appendLine("        {")
  appendLine("            $source cts = new $source();")
  appendLine("            try")
  appendLine("            {")
  appendLine("                $task task = start(cts.Token);")
  appendLine("                Attach(task, callback, ctx);")
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                cts.Dispose();")
  appendLine("                throw;")
  appendLine("            }")
  appendLine("            return GCHandle.ToIntPtr(GCHandle.Alloc(cts));")
  appendLine("        }")
  appendLine()
  appendLine("        internal static void End(IntPtr task)")
  appendLine("        {")
  appendLine("            GCHandle handle = GCHandle.FromIntPtr(task);")
  appendLine("            try")
  appendLine("            {")
  appendLine("                (($task)handle.Target!).GetAwaiter().GetResult();")
  appendLine("            }")
  appendLine("            finally")
  appendLine("            {")
  appendLine("                handle.Free();")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        internal static T End<T>(IntPtr task)")
  appendLine("        {")
  appendLine("            GCHandle handle = GCHandle.FromIntPtr(task);")
  appendLine("            try")
  appendLine("            {")
  appendLine("                return (($task<T>)handle.Target!).GetAwaiter().GetResult();")
  appendLine("            }")
  appendLine("            finally")
  appendLine("            {")
  appendLine("                handle.Free();")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        internal static void ReleaseCancellation(IntPtr handle, int cancel)")
  appendLine("        {")
  appendLine("            GCHandle h = GCHandle.FromIntPtr(handle);")
  appendLine("            $source cts = ($source)h.Target!;")
  appendLine("            h.Free();")
  appendLine("            if (cancel == 0)")
  appendLine("            {")
  appendLine("                cts.Dispose();")
  appendLine("                return;")
  appendLine("            }")
  appendLine("            global::System.Threading.ThreadPool.UnsafeQueueUserWorkItem(")
  appendLine("                static s => { try { s.Cancel(); } catch (AggregateException) { } },")
  appendLine("                cts,")
  appendLine("                preferLocal: false);")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

/**
 * ADR-210: the `[UnmanagedCallersOnly]` thunks of a suspend lambda parameter. Their OWN bodies:
 * never [appendCtxDispatchThunk] or [ctxLookupPreamble], whose miss branch disposes every `IntPtr`
 * slot ahead of the ctx. Here that would `nuget_dispose` the completion function pointer and free
 * the pending ctx `awaitForKotlin` still owns (and releases itself when `begin` throws).
 *
 * A Begin thunk is `(payload..., callback, awaitCtx, key, errOut) -> IntPtr source`. On a key miss
 * it disposes the handle-passed PAYLOAD slots only (the delegate read that would have freed them
 * never runs), leaves `callback` and `awaitCtx` untouched, mints no `CancellationTokenSource` and
 * no task handle, and reports `ObjectDisposedException` through `errOut`, returning `IntPtr.Zero`.
 * Every other thunk is keyless and acts on a handle Kotlin holds exactly once, so it has no miss.
 */
private fun StringBuilder.appendAsyncCallbackThunks(helper: CirCallbackDelegateHelper) {
  if (helper.asyncBegins.isEmpty()) return
  helper.asyncBegins.forEach { begin ->
    val payload: List<String> = begin.payloadTypes.indices.map { index -> "a$index" }
    val parameters: String = (
        begin.payloadTypes.mapIndexed { index, type -> "$type a$index" } +
            listOf("IntPtr callback", "IntPtr awaitCtx", "IntPtr key", "IntPtr* errOut")
        ).joinToString(", ")
    val releases: List<String> = begin.payloadTypes.mapIndexedNotNull { index, type ->
      if (type == "IntPtr") "    if (a$index != IntPtr.Zero) NugetMarshal.Dispose(a$index);"
      else null
    }
    appendThunkBody(
      begin.name,
      parameters,
      "IntPtr",
      "((${begin.name})target)(${(payload + "callback" + "awaitCtx").joinToString(", ")})",
      errOut = true,
      preamble = listOf("object? target = LookupCtx(key);", "if (target is null)", "{") +
          releases +
          listOf("    throw new ObjectDisposedException(\"${begin.name}\");", "}"),
    )
    appendThunkPointer(
      begin.name,
      begin.payloadTypes + listOf("IntPtr", "IntPtr", "IntPtr", "IntPtr*"),
      "IntPtr",
    )
  }
  helper.asyncEnds.forEach { end ->
    appendThunkBody(
      end.name, "IntPtr a0, IntPtr* errOut", end.returnType, end.invocation, errOut = true,
    )
    appendThunkPointer(end.name, listOf("IntPtr", "IntPtr*"), end.returnType)
  }
  // Not user code: a throw here has no Kotlin frame to land in, so these keep ADR-102's FailFast.
  appendThunkBody(
    "NugetAsyncReleaseTask", "IntPtr a0", "void", "GCHandle.FromIntPtr(a0).Free()",
  )
  appendThunkPointer("NugetAsyncReleaseTask", listOf("IntPtr"), "void")
  appendThunkBody(
    "NugetAsyncReleaseCancellation", "IntPtr a0, int a1", "void",
    "NugetTasks.ReleaseCancellation(a0, a1)",
  )
  appendThunkPointer("NugetAsyncReleaseCancellation", listOf("IntPtr", "int"), "void")
  // The cleaner passes a null error slot, which selects the shell's FailFast branch.
  appendThunkBody(
    "NugetAsyncReleaseContext", "IntPtr a0, IntPtr* errOut", "void", "UnregisterCtx(a0)",
    errOut = true,
  )
  appendThunkPointer("NugetAsyncReleaseContext", listOf("IntPtr", "IntPtr*"), "void")
}

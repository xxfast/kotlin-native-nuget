package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderAsyncHelper(helper: CirAsyncHelper) {
  appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
  appendLine("    internal delegate void NugetAsyncCallback(IntPtr result, IntPtr error, byte isCancelled, IntPtr userData);")
  appendLine()
  // ADR-102: the suspend continuation's ctx is the GCHandle of the completion closure itself. The
  // closure already captures its TaskCompletionSource, so the separate tcs handle that used to
  // travel through userData collapses into this one.
  // ADR-161 part C: this family stays on GCHandle dispatch, deliberately. It is not user code (the
  // published `nuget-runtime` klib invokes it through `launchForCSharp`), its ctx is a one-shot
  // handle the completion closure frees itself, and a `void` shape's table MISS is a DROP -- which
  // on this route would mean the Task never completes and every awaiting caller hangs forever
  // instead of failing. The never-reused key table is for the routes where C# can dispose the ctx
  // while Kotlin still holds the pointer.
  renderThunkClass {
    appendCtxDispatchThunk(
      "NugetAsyncCallback",
      "(IntPtr result, IntPtr error, byte isCancelled, IntPtr userData)",
      "void",
      viaKeyTable = false,
    )
  }
}

internal fun StringBuilder.renderScopeHelper(helper: CirScopeHelper) {
  appendLine("    internal static class NugetScopeNative")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_create\")]")
  appendLine("        internal static extern IntPtr Create();")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_cancel\")]")
  appendLine("        internal static extern void Cancel(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_dispose\")]")
  appendLine("        internal static extern void Dispose(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_drain\")]")
  appendLine(
    "        internal static extern IntPtr Drain(IntPtr handle, IntPtr callback, " +
        "IntPtr userData);"
  )
  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderJobHelper(helper: CirJobHelper) {
  appendLine("    internal static class NugetJobNative")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_job_cancel\")]")
  appendLine("        internal static extern void Cancel(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_job_dispose\")]")
  appendLine("        internal static extern void Dispose(IntPtr handle);")
  appendLine("    }")
  appendLine()
  renderJobCell()
}

/**
 * ADR-019 race: the job handle and its cancellation registration are assigned by the *caller*
 * after the native call returns, but the completion callback that releases them can fire before
 * that, from the coroutine, when the suspend body has no suspension point (Kotlin launches it
 * `ATOMIC` on `Dispatchers.Default`). The shipped code read a still-zero `jobHandle` local and a
 * default registration, so the callback disposed nothing and the job `StableRef` leaked. Measured
 * at roughly one handle per thousand crossings, run to run.
 *
 * The cell makes the two sides race explicitly on one interlocked exchange: whoever arrives second
 * does the release, exactly once. The caller writes the handle and the registration before its
 * exchange, and `Interlocked.Exchange` is a full fence, so a callback that arrives second reads
 * both.
 */
private fun StringBuilder.renderJobCell() {
  appendLine("    internal sealed class NugetJobCell")
  appendLine("    {")
  appendLine("        private const int Pending = 0;")
  appendLine("        private const int Published = 1;")
  appendLine("        private const int Completed = 2;")
  appendLine()
  appendLine("        private IntPtr _handle;")
  appendLine("        private CancellationTokenRegistration _registration;")
  appendLine("        private int _state = Pending;")
  appendLine()
  appendLine(
    "        /// <summary>The caller side, once the native call has returned the job " +
        "handle.</summary>"
  )
  appendLine(
    "        internal void PublishFromCaller(IntPtr handle, " +
        "CancellationTokenRegistration registration)"
  )
  appendLine("        {")
  appendLine("            _handle = handle;")
  appendLine("            _registration = registration;")
  appendLine("            if (Interlocked.Exchange(ref _state, Published) == Completed) Release();")
  appendLine("        }")
  appendLine()
  appendLine(
    "        /// <summary>The completion callback side, which may run before the caller " +
        "publishes.</summary>"
  )
  appendLine("        internal void CompleteFromCallback()")
  appendLine("        {")
  appendLine("            if (Interlocked.Exchange(ref _state, Completed) == Published) Release();")
  appendLine("        }")
  appendLine()
  appendLine("        private void Release()")
  appendLine("        {")
  appendLine("            _registration.Dispose();")
  appendLine("            if (_handle != IntPtr.Zero) NugetJobNative.Dispose(_handle);")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

/**
 * ADR-161: the ONE suspend-completion closure body, shared by every site that renders a
 * `NugetAsyncCallback` (this file's [renderAsyncMethod], `CirFunctionRenderer`'s four
 * `KotlinSuspendFunc`/`KotlinSuspendAction` invokers and `CirClassRenderer`'s drain closure).
 *
 * The closure runs inside the `NugetAsyncCallback` thunk, whose catch-all is
 * `Environment.FailFast` (ADR-102), so a bridge-internal failure while MATERIALISING the awaited
 * result -- `new T(resultPtr)`, a `FromHandle<T>` with no branch for the type, a collection read,
 * or `NugetErrorNative.BuildException` on the error arm -- used to end the host process. It now
 * faults the `Task` the caller is already awaiting: the awaiter sees the materialisation exception
 * and the process lives. The thunk's FailFast stays as the backstop for anything this `try` cannot
 * reach.
 *
 * `job.CompleteFromCallback()` and `callbackHandle.Free()` stay OUTSIDE the `try` on purpose: they
 * are the two steps that must have happened before anything can throw, and a throw from either is
 * a defect in generated code rather than a materialisation failure. Note that they also run before
 * the `try`, so after a fault the callback handle is already freed and the job already completed:
 * the `Task` completing is the only thing left to get right, which is exactly what the catch does.
 *
 * [prelude] carries site-specific statements that belong inside the containment (the drain
 * closure's scope and object disposal), [includesErrorBranch] is false for the drain closure, which
 * has no error arm, and [cancellationArgument] is empty where no token is in scope.
 */
internal fun StringBuilder.appendAsyncCompletionClosure(
  tcsType: String,
  resultExtraction: String,
  cancellationArgument: String = "cancellationToken",
  prelude: List<String> = emptyList(),
  includesErrorBranch: Boolean = true,
) {
  appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
  appendLine("            {")
  appendLine("                job.CompleteFromCallback();")
  appendLine("                callbackHandle.Free();")
  appendLine("                $tcsType t = tcs;")
  appendLine("                try")
  appendLine("                {")
  prelude.forEach { statement -> appendLine("                    $statement") }
  appendLine("                    if (isCancelled != 0)")
  appendLine("                    {")
  appendLine("                        t.TrySetCanceled($cancellationArgument);")
  appendLine("                    }")
  if (includesErrorBranch) {
    appendLine("                    else if (errorPtr != IntPtr.Zero)")
    appendLine("                    {")
    appendLine("                        t.SetException(NugetErrorNative.BuildException(errorPtr));")
    appendLine("                    }")
  }
  appendLine("                    else")
  appendLine("                    {")
  appendLine("                        ${reindentedExtraction(resultExtraction)}")
  appendLine("                    }")
  appendLine("                }")
  appendLine("                catch (Exception ex)")
  appendLine("                {")
  appendLine("                    t.TrySetException(ex);")
  appendLine("                }")
  appendLine("            };")
}

/**
 * A multi-line result extraction was written against the shipped 20-space body indent; the
 * containment `try` moves the body four columns right, so every continuation line moves with it and
 * keeps its relative nesting.
 */
private fun reindentedExtraction(extraction: String): String {
  val lines: List<String> = extraction.lines()
  if (lines.size == 1) return extraction
  return lines.mapIndexed { index, line ->
    if (index == 0) {
      line
    } else {
      val lead: Int = line.takeWhile { character -> character == ' ' }.length
      " ".repeat(24 + (lead - 20).coerceAtLeast(0)) + line.trimStart()
    }
  }.joinToString("\n")
}

private val primitiveAsyncTypes = setOf(
  "string", "int", "long", "float", "double", "bool",
  "sbyte", "byte", "short", "ushort", "uint", "ulong",
)

internal fun StringBuilder.renderAsyncMethod(method: CirMethod, className: String = "") {
  val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
  val static: String = if (method.isStatic) "static " else ""
  val isUnit: Boolean = method.asyncReturnType.isEmpty()
  val innerType: String = if (isUnit) "bool" else method.asyncReturnType
  val tcsType: String = "TaskCompletionSource<$innerType>"
  val nativeName: String = method.nativeName

  // ADR-114: the native call passes the wire handle, the public signature keeps the collection.
  val paramNames: String = method.parameters.joinToString(", ") { it.nativeArgument }

  val methodParams: String = if (method.parameters.isEmpty()) {
    "CancellationToken cancellationToken = default"
  } else {
    method.parameters.joinToString(", ") { "${it.type} ${it.name}" } +
        ", CancellationToken cancellationToken = default"
  }

  // ADR-102: the thunk address plus the completion closure's own GCHandle as the echoed ctx.
  val callbackArgs: String = "NugetThunks.NugetAsyncCallbackPtr, GCHandle.ToIntPtr(callbackHandle)"
  val nativeCallArgs: String = if (method.isStatic) {
    if (paramNames.isEmpty()) callbackArgs
    else "$paramNames, $callbackArgs"
  } else {
    if (paramNames.isEmpty()) "_handle, GetOrCreateScope(), $callbackArgs"
    else "_handle, GetOrCreateScope(), $paramNames, $callbackArgs"
  }

  // ADR-068: `suspend fun` returning StateFlow<T> -- the awaited resultPtr IS the StateFlow
  // object's own StableRef handle. Wrap it in a handle-owning KotlinStateFlow<T> (via the two
  // shared generic `nuget_stateflow_collect`/`nuget_stateflow_value` exports) instead of the
  // ordinary object-return `new T(resultPtr)` shape below -- a KotlinStateFlow<T> has no
  // single-IntPtr constructor.
  val isStateFlowReturn: Boolean = method.asyncReturnType.startsWith("KotlinStateFlow<")

  val resultExtraction: String = when {
    isUnit -> "t.SetResult(true);"
    isStateFlowReturn -> buildString {
      appendLine("IntPtr flowHandle = resultPtr;")
      appendLine("                    IntPtr collectScope = GetOrCreateScope();")
      appendLine("                    t.SetResult(new ${method.asyncReturnType}(")
      appendLine("                        (flowOnNext, flowOnComplete, flowOnError, flowUserData) =>")
      appendLine("                            NugetStateFlowNative.Collect(flowHandle, collectScope, flowOnNext, flowOnComplete, flowOnError, flowUserData),")
      appendLine("                        () => NugetStateFlowNative.Value(flowHandle),")
      // ADR-123's `read:` slot, fourth ctor argument (trailing optional, `CirFlowRenderer`): an
      // interface element materialises each `.Value` through the ADR-136 resolve-then-wrap
      // expression instead of the default `FromHandle<T>`, which has no factory for an interface.
      if (method.flowElementRead != null) {
        appendLine("                        flowHandle,")
        append("                        ${method.flowElementRead}));")
      } else {
        append("                        flowHandle));")
      }
    }

    // ADR-119: a collection result is a handle to the boxed wire container, read back through the
    // same `nuget_list_*` helpers the property route uses, never `new IReadOnlyList<T>(...)`.
    method.asyncResultRead != null -> "t.SetResult(${method.asyncResultRead});"

    // Issue #108: `FromHandle<T>` already reads a null handle as `default!` and already has an
    // ADR-067 `Nullable.GetUnderlyingType` branch, so a nullable primitive needs only the `?` on
    // the type argument: `FromHandle<int?>` returns null where `FromHandle<int>` returned 0.
    method.asyncReturnType.removeSuffix("?") in primitiveAsyncTypes ->
      "t.SetResult(NugetMarshal.FromHandle<${method.asyncReturnType}>(resultPtr));"

    // A nullable object return has no such guard: `new T(IntPtr.Zero)` would build a live wrapper
    // over a null handle, so the null is tested on the wire pointer instead.
    method.asyncReturnType.endsWith("?") -> {
      val nonNullableType: String = method.asyncReturnType.removeSuffix("?")
      "t.SetResult(resultPtr == IntPtr.Zero ? null : new $nonNullableType(resultPtr));"
    }

    else ->
      "t.SetResult(new ${method.asyncReturnType}(resultPtr));"
  }

  appendLine("        $visibility ${static}${method.returnType} ${method.name}($methodParams)")
  appendLine("        {")
  if (!method.isStatic && className.isNotEmpty()) {
    appendLine("            if (_handle == IntPtr.Zero)")
    appendLine("                throw new ObjectDisposedException(nameof($className));")
  }
  appendLine("            var tcs = new $tcsType(TaskCreationOptions.RunContinuationsAsynchronously);")
  appendLine("            NugetAsyncCallback callback = null!;")
  appendLine("            GCHandle callbackHandle = default;")
  appendLine("            var job = new NugetJobCell();")
  appendAsyncCompletionClosure(tcsType, resultExtraction)
  appendLine("            callbackHandle = GCHandle.Alloc(callback);")
  // ADR-114: the native call is synchronous even though the await is not, so the wire container is
  // built immediately before it and disposed in a `finally` immediately after it returns. The
  // Kotlin export copies out of it before `launch`, so the coroutine never sees the handle.
  val scoped: List<String>? = method.parameters
    .collectionScopedCall(
      "            ",
      "jobHandle = $nativeName($nativeCallArgs)",
      returns = false,
    )
  if (scoped == null) {
    appendLine("            IntPtr jobHandle = $nativeName($nativeCallArgs);")
  } else {
    // The scoped call assigns from inside its own block, so the local is declared outside it.
    appendLine("            IntPtr jobHandle = IntPtr.Zero;")
    scoped.forEach { appendLine(it) }
  }
  appendLine("            CancellationTokenRegistration reg = cancellationToken.CanBeCanceled")
  appendLine("                ? cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle))")
  appendLine("                : default;")
  appendLine("            job.PublishFromCaller(jobHandle, reg);")
  appendLine("            return tcs.Task;")
  appendLine("        }")
  appendLine()
}


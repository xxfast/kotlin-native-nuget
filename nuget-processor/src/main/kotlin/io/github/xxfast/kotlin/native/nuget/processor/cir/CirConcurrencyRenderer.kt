package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.freshName

internal fun StringBuilder.renderAsyncHelper(helper: CirAsyncHelper) {
  appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
  appendLine("    internal delegate void NugetAsyncCallback(IntPtr result, IntPtr error, byte isCancelled, IntPtr userData);")
  appendLine()
  // ADR-102: the suspend continuation's ctx is the GCHandle of the completion closure itself. The
  // closure already captures its TaskCompletionSource, so the separate tcs handle that used to
  // travel through userData collapses into this one.
  // ADR-161: this family is NOT user code, so it keeps both of part B's and part C's opt-outs, and
  // both are load-bearing.
  //
  // No error slot: the caller is the published `nuget-runtime` klib, whose `launchForCSharp` spells
  // the pointer as `CFunction<(COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>`
  // (`NugetLaunch.kt`), four parameters. A fifth parameter here would be read off a stack slot the
  // caller never supplied, and the catch path would write a managed handle through it.
  //
  // No key-table lookup: its ctx is a one-shot GCHandle the completion closure frees itself, and a
  // `void` shape answers a table MISS by DROPPING the call -- on this route that means the
  // `TaskCompletionSource` is never completed and every awaiting caller hangs forever, silently,
  // rather than failing. The table is for the routes where C# can dispose the ctx while Kotlin
  // still holds the pointer.
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
    "        internal static extern IntPtr Drain(NugetKotlinHandle handle, IntPtr callback, " +
        "IntPtr userData);"
  )
  appendLine("    }")
  appendLine()
  renderScopeHandle()
}

/**
 * ADR-187: the suspend scope's owned handle. Its release is not the shared `nuget_dispose`: an
 * undisposed wrapper's scope is cancelled and then disposed, exactly as `Dispose()` does it. The
 * drain path (which has already completed the scope) and the losing side of the creation race
 * release without the cancel, as they always have.
 */
private fun StringBuilder.renderScopeHandle() {
  appendLine("    internal sealed class NugetScopeHandle : NugetKotlinHandle")
  appendLine("    {")
  appendLine("        private volatile bool _uncancelled;")
  appendLine()
  appendLine("        internal NugetScopeHandle(IntPtr handle) : base(handle)")
  appendLine("        {")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>Releases a scope that was drained, or never published, without cancelling it.</summary>")
  appendLine("        internal void DisposeWithoutCancel()")
  appendLine("        {")
  appendLine("            _uncancelled = true;")
  appendLine("            Dispose();")
  appendLine("        }")
  appendLine()
  appendLine("        protected override bool ReleaseHandle()")
  appendLine("        {")
  appendLine("            if (!_uncancelled) NugetScopeNative.Cancel(handle);")
  appendLine("            NugetScopeNative.Dispose(handle);")
  appendLine("            return true;")
  appendLine("        }")
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
  // ADR-207: the Flow enumerator's credit return, runtime-fixed like the two above.
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_flow_resume\")]")
  appendLine("        internal static extern void Resume(IntPtr handle);")
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
  // The enclosing method's locals this closure assigns or reads; [renderAsyncMethod] moves them off
  // a user parameter spelled the same (`freshName`), every other site keeps the shipped spelling.
  locals: CirAsyncLocals = CirAsyncLocals(),
) {
  appendLine("            ${locals.callback} = (resultPtr, errorPtr, isCancelled, userData) =>")
  appendLine("            {")
  appendLine("                ${locals.job}.CompleteFromCallback();")
  appendLine("                ${locals.callbackHandle}.Free();")
  appendLine("                $tcsType t = ${locals.tcs};")
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

/**
 * The method-scope locals of a legacy suspend wrapper. They share a scope with the user's
 * parameters, so a parameter named `tcs` or `job` would be CS0136; [of] mints each apart from them.
 */
internal data class CirAsyncLocals(
  val tcs: String = "tcs",
  val callback: String = "callback",
  val callbackHandle: String = "callbackHandle",
  val job: String = "job",
  val jobHandle: String = "jobHandle",
  val reg: String = "reg",
  /**
   * The generated trailing `CancellationToken` parameter. Public, but still the generator's name:
   * a user parameter spelled `cancellationToken` keeps its name and this one moves. Also read by
   * the XML-doc pass (`CirClassTranslator`), which tags it like any other parameter.
   */
  val cancellationToken: String = "cancellationToken",
  val collectScope: String = "collectScope",
) {
  /** Every local this wrapper declares, for a nested lambda to mint its own names apart from. */
  fun names(): Set<String> =
    setOf(tcs, callback, callbackHandle, job, jobHandle, reg, cancellationToken, collectScope)

  companion object {
    fun of(parameters: List<CirParameter>): CirAsyncLocals {
      val taken: MutableSet<String> = parameters.localScopeNames()
      fun local(base: String): String = freshName(base, taken).also { taken += it }
      val cancellationToken: String = local("cancellationToken")
      return CirAsyncLocals(
        local("tcs"), local("callback"), local("callbackHandle"), local("job"), local("jobHandle"),
        local("reg"), cancellationToken, local("collectScope"),
      )
    }
  }
}

internal fun StringBuilder.renderAsyncMethod(method: CirMethod, className: String = "") {
  val locals: CirAsyncLocals = CirAsyncLocals.of(method.parameters)
  val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
  val static: String = if (method.isStatic) "static " else ""
  val isUnit: Boolean = method.asyncReturnType.isEmpty()
  // ADR-026 amendment (2026-10-09): a nullable acquired `Flow<T>?` completes as `KotlinFlow<T>?`.
  val innerType: String = when {
    isUnit -> "bool"
    method.acquiredFlowNullable -> "${method.asyncReturnType}?"
    else -> method.asyncReturnType
  }
  val tcsType: String = "TaskCompletionSource<$innerType>"
  val nativeName: String = method.nativeName

  // ADR-114: the native call passes the wire handle, the public signature keeps the collection.
  val paramNames: String = method.parameters.joinToString(", ") { it.nativeArgument }

  val token: String = locals.cancellationToken
  // ADR-174: an explicit interface implementation may not carry a default (CS1066); the interface
  // declaration already does.
  val tokenDefault: String = if (method.explicitInterface != null) "" else " = default"
  val methodParams: String = if (method.parameters.isEmpty()) {
    "CancellationToken $token$tokenDefault"
  } else {
    method.parameters.joinToString(", ") { it.declaration } +
        ", CancellationToken $token$tokenDefault"
  }

  // ADR-102: the thunk address plus the completion closure's own GCHandle as the echoed ctx.
  val callbackArgs: String =
    "NugetThunks.NugetAsyncCallbackPtr, GCHandle.ToIntPtr(${locals.callbackHandle})"
  val acquiredFlow = method.acquiredFlowCollectNativeName != null
  val flowNames = method.parameters.localScopeNames()
  fun flowLocal(base: String): String = freshName(base, flowNames).also { flowNames += it }
  val ownedFlow = flowLocal("flowHandle")
  val next = flowLocal("flowOnNext")
  val complete = flowLocal("flowOnComplete")
  val error = flowLocal("flowOnError")
  val data = flowLocal("flowUserData")
  val acquiredRead = if (acquiredFlow) method.flowElementRead?.let { read ->
    val replacements = Regex("\\bh[0-9]*\\b").findAll(read).map { it.value }.distinct()
      .associateWith { flowLocal(it) }
    Regex("\\bh[0-9]*\\b").replace(read) { replacements.getValue(it.value) }
  } else null
  val instanceScope = if (acquiredFlow) locals.collectScope else "GetOrCreateScope()"
  val nativeCallArgs: String = if (method.isStatic) {
    if (paramNames.isEmpty()) callbackArgs
    else "$paramNames, $callbackArgs"
  } else {
    if (paramNames.isEmpty()) "_handle, $instanceScope, $callbackArgs"
    else "_handle, $instanceScope, $paramNames, $callbackArgs"
  }

  // ADR-068: `suspend fun` returning StateFlow<T> -- the awaited resultPtr IS the StateFlow
  // object's own StableRef handle. Wrap it in a handle-owning KotlinStateFlow<T> (via the two
  // shared generic `nuget_stateflow_collect`/`nuget_stateflow_value` exports) instead of the
  // ordinary object-return `new T(resultPtr)` shape below -- a KotlinStateFlow<T> has no
  // single-IntPtr constructor.
  // ADR-071 held-route amendment: an awaited `MutableStateFlow<T>` is the same holder plus a write
  // lambda over the flow-keyed `_set_value` ([CirMethod.stateFlowWrite]).
  val isStateFlowReturn: Boolean = method.isAwaitedStateFlow
  // A nullable element (`StateFlow<T?>`) reads through the null-aware sibling export; a collection
  // element (ADR-068) reads through its own flow-handle-keyed pair, which projects each element.
  val stateFlowCollect: String =
    method.awaitedStateFlowCollectNativeName ?: "NugetStateFlowNative.Collect"
  val stateFlowValue: String = method.awaitedStateFlowValueNativeName
    ?: if (method.flowElementNullable) "NugetStateFlowNative.ValueOrNull"
    else "NugetStateFlowNative.Value"

  val resultExtraction: String = when {
    isUnit -> "t.SetResult(true);"
    acquiredFlow -> buildString {
      // ADR-026 amendment (2026-10-09): a nullable member (`Flow<T>?`) arrives as a zero
      // `resultPtr` when absent, tested before any handle is owned, so a null Kotlin return never
      // becomes a live `KotlinFlow<T>` over a zero handle.
      if (method.acquiredFlowNullable) {
        appendLine("if (resultPtr == IntPtr.Zero)")
        appendLine("                    {")
        appendLine("                        t.SetResult(null);")
        appendLine("                        return;")
        appendLine("                    }")
        append("                    ")
      }
      appendLine("var $ownedFlow = new NugetKotlinHandle(resultPtr);")
      appendLine("                    try")
      appendLine("                    {")
      appendLine("                        t.SetResult(new ${method.asyncReturnType}(")
      appendLine("                            ($next, $complete, $error, $data) =>")
      appendLine("                            {")
      appendLine("                                if ($ownedFlow.IsClosed || ${locals.collectScope}.IsClosed)")
      appendLine("                                    throw new ObjectDisposedException(\"${method.asyncReturnType}\");")
      appendLine("                                return ${method.acquiredFlowCollectNativeName}($ownedFlow, ${locals.collectScope}, $next, $complete, $error, $data);")
      appendLine("                            },")
      // ADR-209: an awaited shared flow's seams, keyed on the same owned flow handle and launched
      // on the same captured scope its collect uses.
      method.sharedFlow?.let { shared ->
        val taken: Set<String> = flowNames + locals.names() +
          setOf("t", "resultPtr", "errorPtr", "isCancelled", "userData")
        sharedFlowArguments(
          shared, ownedFlow, locals.collectScope, "                            ", taken,
        ).forEach { argument -> appendLine("$argument,") }
      }
      // Named, not positional: a collection element's read carries a trailing `release:` too
      // (row 16l), which sits after the owned-handle slot.
      appendLine("                            ${acquiredRead ?: "null"}, ownedHandle: $ownedFlow));")
      appendLine("                    }")
      appendLine("                    catch")
      appendLine("                    {")
      appendLine("                        $ownedFlow.Dispose();")
      appendLine("                        throw;")
      append("                    }")
    }
    isStateFlowReturn -> buildString {
      // A nullable member (`StateFlow<T>?`) awaits to `KotlinStateFlow<T>?`: the `_async` export
      // sends a null flow as a zero `resultPtr`, tested here before any handle is owned, so a null
      // Kotlin return never becomes a live holder over a zero handle.
      val memberNullable: Boolean = method.asyncReturnType.endsWith("?")
      val holderType: String = method.asyncReturnType.removeSuffix("?")
      val indent: String = if (memberNullable) "    " else ""
      if (memberNullable) {
        appendLine("if (resultPtr == IntPtr.Zero)")
        appendLine("                    {")
        appendLine("                        t.SetResult(null);")
        appendLine("                    }")
        appendLine("                    else")
        appendLine("                    {")
        append("                        ")
      }
      // ADR-187: the flow handle is owned from here, and the lambdas capture that object and the
      // scope handle object, never raw pointers (see `renderHeldStateFlowMethod`).
      appendLine("var flowHandle = new NugetKotlinHandle(resultPtr);")
      // ADR-068 (2026-09-27 amendment): a static (top-level) member has no parent scope. It passes
      // null and `nuget_stateflow_collect` launches on the runtime's ad-hoc scope, as the
      // top-level suspend call itself does; the enumerator's job is still the cancellation handle.
      val collectScope: String =
        if (method.isStatic) "NugetKotlinHandle.Null" else "GetOrCreateScope()"
      appendLine("$indent                    NugetKotlinHandle collectScope = $collectScope;")
      appendLine("$indent                    t.SetResult(new $holderType(")
      appendLine("$indent                        (flowOnNext, flowOnComplete, flowOnError, flowUserData) =>")
      appendLine("$indent                            $stateFlowCollect(flowHandle, collectScope, flowOnNext, flowOnComplete, flowOnError, flowUserData),")
      appendLine("$indent                        () => $stateFlowValue(flowHandle),")
      // The write lambda sits between the read and `ownedHandle`, as on the held route.
      method.stateFlowWrite?.let { write ->
        appendLine("$indent                        v =>")
        appendLine("$indent                        {")
        if (write.rejectsNull) {
          appendLine("$indent                            if (v is null) throw new ArgumentNullException(nameof(v));")
        }
        write.guard?.let { guard -> appendLine("$indent                            $guard") }
        appendLine("$indent                            ${method.stateFlowSetValueNativeName}(flowHandle, ${write.arguments}, out IntPtr error);")
        appendLine("$indent                            if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
        appendLine("$indent                            NugetErrorNative.ClearManagedFault();")
        appendLine("$indent                        },")
        appendLine(
          stateFlowCompareAndSetLambda(
            method.stateFlowCompareAndSetNativeName, "flowHandle", write,
            taken = method.parameters.localScopeNames() + setOf("flowHandle", "collectScope"),
            indent = "$indent                        ",
          ) + ",",
        )
      }
      // ADR-123's `read:` slot, fourth ctor argument (trailing optional, `CirFlowRenderer`): an
      // interface element materialises each `.Value` through the ADR-136 resolve-then-wrap
      // expression instead of the default `FromHandle<T>`, which has no factory for an interface.
      if (method.flowElementRead != null) {
        appendLine("$indent                        flowHandle,")
        append("$indent                        ${method.flowElementRead}));")
      } else {
        append("$indent                        flowHandle));")
      }
      if (memberNullable) {
        appendLine()
        append("                    }")
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
      "t.SetResult(resultPtr == IntPtr.Zero ? null : new $nonNullableType(resultPtr, out _));"
    }

    else ->
      "t.SetResult(new ${method.asyncReturnType}(resultPtr, out _));"
  }

  appendLine(
    "        ${method.memberHead("$visibility $static")}${method.returnType} " +
        "${method.explicitName}($methodParams)",
  )
  appendLine("        {")
  if (!method.isStatic && className.isNotEmpty()) {
    appendLine("            if (_handle.IsInvalid)")
    appendLine("                throw new ObjectDisposedException(nameof($className));")
  }
  if (acquiredFlow) {
    val scope = if (method.isStatic) "NugetKotlinHandle.Null" else "GetOrCreateScope()"
    appendLine("            NugetKotlinHandle ${locals.collectScope} = $scope;")
  }
  val (tcs, callback, callbackHandle, job, jobHandle, reg) = locals
  appendLine("            var $tcs = new $tcsType(TaskCreationOptions.RunContinuationsAsynchronously);")
  appendLine("            NugetAsyncCallback $callback = null!;")
  appendLine("            GCHandle $callbackHandle = default;")
  appendLine("            var $job = new NugetJobCell();")
  // ADR-187: an instance member's closure roots its wrapper until the call completes, so a wrapper
  // dropped mid-flight is never finalized, and its scope never cancelled, under the coroutine.
  val keepAlive: List<String> = if (method.isStatic) emptyList() else listOf("GC.KeepAlive(this);")
  appendAsyncCompletionClosure(
    tcsType, resultExtraction, cancellationArgument = token, prelude = keepAlive, locals = locals,
  )
  appendLine("            $callbackHandle = GCHandle.Alloc($callback);")
  // ADR-114: the native call is synchronous even though the await is not, so the wire container is
  // built immediately before it and disposed in a `finally` immediately after it returns. The
  // Kotlin export copies out of it before `launch`, so the coroutine never sees the handle.
  val scoped: List<String>? = method.parameters
    .collectionScopedCall(
      "            ",
      "$jobHandle = $nativeName($nativeCallArgs)",
      returns = false,
    )
  if (acquiredFlow) {
    appendLine("            IntPtr $jobHandle = IntPtr.Zero;")
    appendLine("            try")
    appendLine("            {")
    if (scoped == null) appendLine("                $jobHandle = $nativeName($nativeCallArgs);")
    else scoped.forEach { appendLine("    $it") }
    appendLine("            }")
    appendLine("            catch")
    appendLine("            {")
    appendLine("                if ($callbackHandle.IsAllocated) $callbackHandle.Free();")
    appendLine("                throw;")
    appendLine("            }")
  } else if (scoped == null) {
    appendLine("            IntPtr $jobHandle = $nativeName($nativeCallArgs);")
  } else {
    // The scoped call assigns from inside its own block, so the local is declared outside it.
    appendLine("            IntPtr $jobHandle = IntPtr.Zero;")
    scoped.forEach { appendLine(it) }
  }
  appendLine("            CancellationTokenRegistration $reg = $token.CanBeCanceled")
  appendLine("                ? $token.Register(() => NugetJobNative.Cancel($jobHandle))")
  appendLine("                : default;")
  appendLine("            $job.PublishFromCaller($jobHandle, $reg);")
  appendLine("            return $tcs.Task;")
  appendLine("        }")
  appendLine()
}


package io.github.xxfast.kotlin.native.nuget.processor.cir

// ADR-236: the C# half of a delegate set into a Kotlin lambda-typed property. Each lambda wrapper
// (`KotlinFunc<...>`, `KotlinAction<...>`, `KotlinSuspendFunc<...>`, `KotlinSuspendAction<...>`)
// converts implicitly from its BCL delegate by minting a Kotlin lambda over it through the
// runtime's `nuget_func_from_managed` / `nuget_suspend_func_from_managed`. The delegate lives
// behind a `GCHandle` key the Kotlin lambda's cleaner frees through [RELEASE] once Kotlin drops the
// lambda, so replacing or clearing the property is what lets the delegate be collected. A C#
// `KotlinFunc` read back from Kotlin passes its own handle straight through, so no conversion runs.

private const val CDECL: String = "global::System.Runtime.CompilerServices.CallConvCdecl"
private const val TOKEN: String = "global::System.Threading.CancellationToken"
private const val TASK: String = "global::System.Threading.Tasks.Task"

/** The type parameters of a wrapper of [arity] over a non-`Unit` result: `T1, ..., TResult`. */
private fun funcTypeParameters(arity: Int): String =
  ((1..arity).map { "T$it" } + "TResult").joinToString(", ")

/** A non-`Unit` wrapper's type parameters: `T1, ..., TResult`. */
internal fun managedTypeParameters(arity: Int): String = funcTypeParameters(arity)

/** `<T1, ...>` for an action wrapper of non-zero [arity]. */
internal fun managedActionGeneric(arity: Int): String = actionGeneric(arity)

private fun actionTypeParameters(arity: Int): String = (1..arity).joinToString(", ") { "T$it" }

/** `<T1, T2>` for a non-zero [arity], empty otherwise: an `Action` of no payload is not generic. */
private fun actionGeneric(arity: Int): String =
  if (arity == 0) "" else "<${actionTypeParameters(arity)}>"

private fun reads(arity: Int, marshal: String): List<String> = (0 until arity).map { index ->
  "$marshal.FromHandle<T${index + 1}>(args[$index])"
}

/** The implicit conversion a wrapper renders, from its BCL delegate [delegate]. */
internal fun StringBuilder.appendManagedConversion(
  wrapper: String,
  delegate: String,
  adapter: String,
  mint: String,
  arity: Int,
  unit: Boolean,
) {
  appendLine("        /// <summary>The Kotlin lambda <paramref name=\"fn\"/> is bound to, or a new one over it, so any delegate can be set into a Kotlin lambda-typed property.</summary>")
  appendLine("        public static implicit operator $wrapper($delegate? fn)")
  appendLine("        {")
  appendLine("            if (fn is null) return null!;")
  // A delegate a property getter bound to a Kotlin-backed wrapper hands that wrapper back, so a
  // lambda read from Kotlin and set again crosses as the very lambda it was.
  val method: String = if ("Suspend" in mint) "InvokeAsync" else "Invoke"
  appendLine("            if (fn.Target is $wrapper kotlin && fn.Method.Name == \"$method\") return kotlin;")
  appendLine("            return new $wrapper($mint($arity, new $adapter(fn), ${if (unit) "true" else "false"}));")
  appendLine("        }")
  appendLine()
}

/** `Func<T1, TResult>` / `Action<T1>` for a plain wrapper of [arity]. */
internal fun managedPlainDelegate(arity: Int, unit: Boolean): String = when {
  !unit -> "Func<${funcTypeParameters(arity)}>"
  arity == 0 -> "Action"
  else -> "Action<${actionTypeParameters(arity)}>"
}

/** `Func<T1, CancellationToken, Task<TResult>>` / `Func<T1, CancellationToken, Task>`. */
internal fun managedSuspendDelegate(arity: Int, unit: Boolean): String {
  val payload: List<String> = (1..arity).map { "T$it" } + TOKEN
  val result: String = if (unit) TASK else "$TASK<TResult>"
  return "Func<${(payload + result).joinToString(", ")}>"
}

private fun StringBuilder.appendErrorSlotCatch(what: String, returns: String) {
  appendLine("            catch (Exception ex)")
  appendLine("            {")
  appendLine("                if (errOut != null)")
  appendLine("                {")
  appendLine("                    try")
  appendLine("                    {")
  appendLine("                        *errOut = NugetErrorNative.CreateManagedError(ex);")
  appendLine("                    }")
  appendLine("                    catch (Exception channelFailure)")
  appendLine("                    {")
  appendLine("                        Environment.FailFast(\"nuget: error channel failed in $what\", channelFailure);")
  appendLine("                    }")
  appendLine("                    return$returns;")
  appendLine("                }")
  appendLine("                Environment.FailFast(\"nuget: unhandled exception in $what\", ex);")
  appendLine("                return$returns;")
  appendLine("            }")
}

private fun StringBuilder.appendResultHelper(libraryName: String, marshal: String) {
  appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_handle_retain\")]")
  appendLine("        private static extern IntPtr Native_Retain(IntPtr handle);")
  appendLine()
  appendLine("        /// <summary>An owned handle Kotlin releases, over <paramref name=\"value\"/>.</summary>")
  appendLine("        internal static IntPtr Result<T>(T value)")
  appendLine("        {")
  appendLine("            IntPtr handle = $marshal.Wrap(value, out bool owned);")
  appendLine("            if (!owned && handle != IntPtr.Zero) handle = Native_Retain(handle);")
  appendLine("            GC.KeepAlive(value);")
  appendLine("            return handle;")
  appendLine("        }")
  appendLine()
}

private fun StringBuilder.appendReleaseThunk(what: String) {
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof($CDECL) })]")
  appendLine("        private static void Release(IntPtr key)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                GCHandle.FromIntPtr(key).Free();")
  appendLine("            }")
  appendLine("            catch (Exception ex)")
  appendLine("            {")
  appendLine("                Environment.FailFast(\"nuget: unhandled exception releasing $what\", ex);")
  appendLine("            }")
  appendLine("        }")
  appendLine()
}

/** The plain adapters: one shared base and a `Func`/`Action` adapter per [arities] entry. */
internal fun StringBuilder.renderManagedLambdaAdapters(
  libraryName: String,
  ns: String,
  arities: Set<Int>,
) {
  val marshal = "$ns.NugetMarshal"
  appendLine("    internal abstract unsafe class NugetManagedLambda")
  appendLine("    {")
  appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_func_from_managed\")]")
  appendLine("        private static extern IntPtr Native_FromManaged(int arity, IntPtr invoke, IntPtr release, IntPtr key, int unit);")
  appendLine()
  appendResultHelper(libraryName, marshal)
  appendLine("        internal abstract IntPtr Call(IntPtr* args);")
  appendLine()
  appendLine("        internal static IntPtr Mint(int arity, NugetManagedLambda target, bool unit)")
  appendLine("        {")
  appendLine("            IntPtr key = GCHandle.ToIntPtr(GCHandle.Alloc(target));")
  appendLine("            try")
  appendLine("            {")
  appendLine("                return Native_FromManaged(")
  appendLine("                    arity,")
  appendLine("                    (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, IntPtr*, IntPtr>)&Invoke,")
  appendLine("                    (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)&Release,")
  appendLine("                    key,")
  appendLine("                    unit ? 1 : 0);")
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                GCHandle.FromIntPtr(key).Free();")
  appendLine("                throw;")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof($CDECL) })]")
  appendLine("        private static IntPtr Invoke(IntPtr key, IntPtr* args, IntPtr* errOut)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                return ((NugetManagedLambda)GCHandle.FromIntPtr(key).Target!).Call(args);")
  appendLine("            }")
  appendErrorSlotCatch("a managed lambda", " IntPtr.Zero")
  appendLine("        }")
  appendLine()
  appendReleaseThunk("a managed lambda")
  appendLine("    }")
  appendLine()
  for (arity in arities.sorted()) {
    val args: String = reads(arity, marshal).joinToString(", ")
    val func: String = managedPlainDelegate(arity, unit = false)
    appendLine("    internal sealed unsafe class NugetManagedFunc<${funcTypeParameters(arity)}> : NugetManagedLambda")
    appendLine("    {")
    appendLine("        private readonly $func _fn;")
    appendLine()
    appendLine("        internal NugetManagedFunc($func fn) { _fn = fn; }")
    appendLine()
    appendLine("        internal override IntPtr Call(IntPtr* args) => Result(_fn($args));")
    appendLine("    }")
    appendLine()
    val action: String = managedPlainDelegate(arity, unit = true)
    appendLine("    internal sealed unsafe class NugetManagedAction${actionGeneric(arity)} : NugetManagedLambda")
    appendLine("    {")
    appendLine("        private readonly $action _fn;")
    appendLine()
    appendLine("        internal NugetManagedAction($action fn) { _fn = fn; }")
    appendLine()
    appendLine("        internal override IntPtr Call(IntPtr* args)")
    appendLine("        {")
    appendLine("            _fn($args);")
    appendLine("            return IntPtr.Zero;")
    appendLine("        }")
    appendLine("    }")
    appendLine()
  }
}

/**
 * The suspend adapters: Begin reads the arguments synchronously and starts the delegate's `Task`
 * with a fresh `CancellationTokenSource`, whose `GCHandle` Kotlin owns (ADR-153); the attached
 * continuation calls Kotlin's completion callback with the task's `GCHandle`, which End unwraps.
 */
internal fun StringBuilder.renderManagedSuspendLambdaAdapters(
  libraryName: String,
  ns: String,
  arities: Set<Int>,
) {
  val marshal = "$ns.NugetMarshal"
  val source = "global::System.Threading.CancellationTokenSource"
  appendLine("    internal abstract class NugetManagedTask")
  appendLine("    {")
  appendLine("        internal abstract IntPtr End();")
  appendLine("    }")
  appendLine()
  appendLine("    internal sealed class NugetManagedTask<T> : NugetManagedTask")
  appendLine("    {")
  appendLine("        private readonly $TASK<T> _task;")
  appendLine()
  appendLine("        internal NugetManagedTask($TASK<T> task) { _task = task; }")
  appendLine()
  appendLine("        internal override IntPtr End() => NugetManagedSuspendLambda.Result(_task.GetAwaiter().GetResult());")
  appendLine("    }")
  appendLine()
  appendLine("    internal abstract unsafe class NugetManagedSuspendLambda")
  appendLine("    {")
  appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_suspend_func_from_managed\")]")
  appendLine("        private static extern IntPtr Native_FromManaged(int arity, IntPtr begin, IntPtr end, IntPtr releaseTask, IntPtr cancel, IntPtr release, IntPtr key, int unit);")
  appendLine()
  appendResultHelper(libraryName, marshal)
  appendLine("        internal abstract IntPtr Begin(IntPtr* args, IntPtr callback, IntPtr ctx);")
  appendLine()
  appendLine("        internal static IntPtr Mint(int arity, NugetManagedSuspendLambda target, bool unit)")
  appendLine("        {")
  appendLine("            IntPtr key = GCHandle.ToIntPtr(GCHandle.Alloc(target));")
  appendLine("            try")
  appendLine("            {")
  appendLine("                IntPtr end = unit")
  appendLine("                    ? (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, IntPtr>)&EndVoid")
  appendLine("                    : (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, IntPtr>)&EndValue;")
  appendLine("                return Native_FromManaged(")
  appendLine("                    arity,")
  appendLine("                    (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, IntPtr, IntPtr, IntPtr*, IntPtr>)&BeginThunk,")
  appendLine("                    end,")
  appendLine("                    (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)&ReleaseTask,")
  appendLine("                    (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, int, void>)&Cancel,")
  appendLine("                    (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)&Release,")
  appendLine("                    key,")
  appendLine("                    unit ? 1 : 0);")
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                GCHandle.FromIntPtr(key).Free();")
  appendLine("                throw;")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>Starts <paramref name=\"start\"/> and attaches Kotlin's completion callback; answers the token source's handle.</summary>")
  appendLine("        protected static IntPtr Start(Func<$TOKEN, $TASK> start, Func<$TASK, object> completed, IntPtr callback, IntPtr ctx)")
  appendLine("        {")
  appendLine("            $source cts = new $source();")
  appendLine("            try")
  appendLine("            {")
  appendLine("                $TASK task = start(cts.Token);")
  appendLine("                IntPtr handle = GCHandle.ToIntPtr(GCHandle.Alloc(completed(task)));")
  appendLine("                try")
  appendLine("                {")
  appendLine("                    task.ContinueWith(")
  appendLine("                        static (_, state) =>")
  appendLine("                        {")
  appendLine("                            var (cb, h, c) = ((IntPtr, IntPtr, IntPtr))state!;")
  appendLine("                            ((delegate* unmanaged[Cdecl]<IntPtr, IntPtr, void>)cb)(h, c);")
  appendLine("                        },")
  appendLine("                        (callback, handle, ctx),")
  appendLine("                        $TOKEN.None,")
  appendLine("                        global::System.Threading.Tasks.TaskContinuationOptions.None,")
  appendLine("                        global::System.Threading.Tasks.TaskScheduler.Default);")
  appendLine("                }")
  appendLine("                catch")
  appendLine("                {")
  appendLine("                    GCHandle.FromIntPtr(handle).Free();")
  appendLine("                    throw;")
  appendLine("                }")
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                cts.Dispose();")
  appendLine("                throw;")
  appendLine("            }")
  appendLine("            return GCHandle.ToIntPtr(GCHandle.Alloc(cts));")
  appendLine("        }")
  appendLine()
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof($CDECL) })]")
  appendLine("        private static IntPtr BeginThunk(IntPtr key, IntPtr* args, IntPtr callback, IntPtr ctx, IntPtr* errOut)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                return ((NugetManagedSuspendLambda)GCHandle.FromIntPtr(key).Target!).Begin(args, callback, ctx);")
  appendLine("            }")
  appendErrorSlotCatch("a managed suspend lambda", " IntPtr.Zero")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>Unwraps the completed task behind <paramref name=\"task\"/>, freeing its handle.</summary>")
  appendLine("        private static IntPtr End(IntPtr task, Func<object, IntPtr> end)")
  appendLine("        {")
  appendLine("            GCHandle handle = GCHandle.FromIntPtr(task);")
  appendLine("            try")
  appendLine("            {")
  appendLine("                return end(handle.Target!);")
  appendLine("            }")
  appendLine("            finally")
  appendLine("            {")
  appendLine("                handle.Free();")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof($CDECL) })]")
  appendLine("        private static IntPtr EndValue(IntPtr task, IntPtr* errOut)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                return End(task, static target => ((NugetManagedTask)target).End());")
  appendLine("            }")
  appendErrorSlotCatch("a managed suspend lambda's result", " IntPtr.Zero")
  appendLine("        }")
  appendLine()
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof($CDECL) })]")
  appendLine("        private static IntPtr EndVoid(IntPtr task, IntPtr* errOut)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                return End(task, static target => { (($TASK)target).GetAwaiter().GetResult(); return IntPtr.Zero; });")
  appendLine("            }")
  appendErrorSlotCatch("a managed suspend lambda's result", " IntPtr.Zero")
  appendLine("        }")
  appendLine()
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof($CDECL) })]")
  appendLine("        private static void ReleaseTask(IntPtr task)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                GCHandle.FromIntPtr(task).Free();")
  appendLine("            }")
  appendLine("            catch (Exception ex)")
  appendLine("            {")
  appendLine("                Environment.FailFast(\"nuget: unhandled exception releasing a managed suspend lambda's task\", ex);")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof($CDECL) })]")
  appendLine("        private static void Cancel(IntPtr source, int cancelled)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                GCHandle handle = GCHandle.FromIntPtr(source);")
  appendLine("                $source cts = ($source)handle.Target!;")
  appendLine("                handle.Free();")
  appendLine("                if (cancelled == 0)")
  appendLine("                {")
  appendLine("                    cts.Dispose();")
  appendLine("                    return;")
  appendLine("                }")
  appendLine("                global::System.Threading.ThreadPool.UnsafeQueueUserWorkItem(")
  appendLine("                    static s => { try { s.Cancel(); } catch (AggregateException) { } },")
  appendLine("                    cts,")
  appendLine("                    preferLocal: false);")
  appendLine("            }")
  appendLine("            catch (Exception ex)")
  appendLine("            {")
  appendLine("                Environment.FailFast(\"nuget: unhandled exception cancelling a managed suspend lambda\", ex);")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendReleaseThunk("a managed suspend lambda")
  appendLine("    }")
  appendLine()
  for (arity in arities.sorted()) {
    val prelude: List<String> = reads(arity, marshal).mapIndexed { index, read ->
      "            T${index + 1} a$index = $read;"
    }
    val call: String = ((0 until arity).map { "a$it" } + "token").joinToString(", ")
    val func: String = managedSuspendDelegate(arity, unit = false)
    appendLine("    internal sealed unsafe class NugetManagedSuspendFunc<${funcTypeParameters(arity)}> : NugetManagedSuspendLambda")
    appendLine("    {")
    appendLine("        private readonly $func _fn;")
    appendLine()
    appendLine("        internal NugetManagedSuspendFunc($func fn) { _fn = fn; }")
    appendLine()
    appendLine("        internal override IntPtr Begin(IntPtr* args, IntPtr callback, IntPtr ctx)")
    appendLine("        {")
    prelude.forEach(::appendLine)
    appendLine("            return Start(token => _fn($call), task => new NugetManagedTask<TResult>(($TASK<TResult>)task), callback, ctx);")
    appendLine("        }")
    appendLine("    }")
    appendLine()
    val action: String = managedSuspendDelegate(arity, unit = true)
    appendLine("    internal sealed unsafe class NugetManagedSuspendAction${actionGeneric(arity)} : NugetManagedSuspendLambda")
    appendLine("    {")
    appendLine("        private readonly $action _fn;")
    appendLine()
    appendLine("        internal NugetManagedSuspendAction($action fn) { _fn = fn; }")
    appendLine()
    appendLine("        internal override IntPtr Begin(IntPtr* args, IntPtr callback, IntPtr ctx)")
    appendLine("        {")
    prelude.forEach(::appendLine)
    appendLine("            return Start(token => _fn($call), task => task, callback, ctx);")
    appendLine("        }")
    appendLine("    }")
    appendLine()
  }
}

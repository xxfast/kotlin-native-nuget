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
    helper.delegates.forEach { delegate ->
      appendCtxDispatchThunk(delegate.name, delegate.paramList, delegate.returnType)
    }
  }
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
 * One thunk + pointer-getter pair for a delegate whose LAST parameter is the echoed ctx and whose
 * ctx is a GCHandle to the delegate instance itself.
 */
internal fun StringBuilder.appendCtxDispatchThunk(
  name: String,
  paramList: String,
  returnType: String,
) {
  val types: List<String> = thunkParameterTypes(paramList)
  val parameters: String = types.mapIndexed { index, type -> "$type a$index" }.joinToString(", ")
  val arguments: String = types.indices.joinToString(", ") { index -> "a$index" }
  val ctx: String = "a${types.size - 1}"
  val invocation: String = "(($name)GCHandle.FromIntPtr($ctx).Target!)($arguments)"
  appendThunkBody(name, parameters, returnType, invocation)
  appendThunkPointer(name, types, returnType)
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
  if (isVoid) {
    appendLine("                $invocation;")
  } else {
    appendLine("                return $invocation;")
  }
  appendLine("            }")
  appendLine("            catch (Exception ex)")
  appendLine("            {")
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

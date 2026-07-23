package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderAsyncHelper(helper: CirAsyncHelper) {
  appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
  appendLine("    internal delegate void NugetAsyncCallback(IntPtr result, IntPtr error, byte isCancelled, IntPtr userData);")
  appendLine()
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
  appendLine("        internal static extern IntPtr Drain(IntPtr handle, NugetAsyncCallback callback, IntPtr userData);")
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

  val paramNames: String = method.parameters.joinToString(", ") { it.name }

  val methodParams: String = if (method.parameters.isEmpty()) {
    "CancellationToken cancellationToken = default"
  } else {
    method.parameters.joinToString(", ") { "${it.type} ${it.name}" } +
        ", CancellationToken cancellationToken = default"
  }

  val nativeCallArgs: String = if (method.isStatic) {
    if (paramNames.isEmpty()) "callback, GCHandle.ToIntPtr(tcsHandle)"
    else "$paramNames, callback, GCHandle.ToIntPtr(tcsHandle)"
  } else {
    if (paramNames.isEmpty()) "_handle, GetOrCreateScope(), callback, GCHandle.ToIntPtr(tcsHandle)"
    else "_handle, GetOrCreateScope(), $paramNames, callback, GCHandle.ToIntPtr(tcsHandle)"
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
      append("                        flowHandle));")
    }

    method.asyncReturnType in primitiveAsyncTypes ->
      "t.SetResult(NugetMarshal.FromHandle<${method.asyncReturnType}>(resultPtr));"

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
  appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
  appendLine("            NugetAsyncCallback callback = null!;")
  appendLine("            GCHandle callbackHandle = default;")
  appendLine("            CancellationTokenRegistration reg = default;")
  appendLine("            IntPtr jobHandle = IntPtr.Zero;")
  appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
  appendLine("            {")
  appendLine("                reg.Dispose();")
  appendLine("                NugetJobNative.Dispose(jobHandle);")
  appendLine("                callbackHandle.Free();")
  appendLine("                var t = ($tcsType)GCHandle.FromIntPtr(userData).Target!;")
  appendLine("                GCHandle.FromIntPtr(userData).Free();")
  appendLine("                if (isCancelled != 0)")
  appendLine("                {")
  appendLine("                    t.TrySetCanceled(cancellationToken);")
  appendLine("                }")
  appendLine("                else if (errorPtr != IntPtr.Zero)")
  appendLine("                {")
  appendLine("                    t.SetException(NugetErrorNative.BuildException(errorPtr));")
  appendLine("                }")
  appendLine("                else")
  appendLine("                {")
  appendLine("                    $resultExtraction")
  appendLine("                }")
  appendLine("            };")
  appendLine("            callbackHandle = GCHandle.Alloc(callback);")
  appendLine("            jobHandle = $nativeName($nativeCallArgs);")
  appendLine("            if (cancellationToken.CanBeCanceled)")
  appendLine("                reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));")
  appendLine("            return tcs.Task;")
  appendLine("        }")
  appendLine()
}


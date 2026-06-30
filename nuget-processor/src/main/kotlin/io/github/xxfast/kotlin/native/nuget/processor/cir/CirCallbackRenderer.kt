package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderCallbackDelegateHelper(helper: CirCallbackDelegateHelper) {
  helper.delegates.forEach { delegate ->
    appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
    appendLine("    internal delegate ${delegate.returnType} ${delegate.name}${delegate.paramList};")
    appendLine()
  }
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

package io.github.xxfast.kotlin.native.nuget.processor.cir

// Boundary nullability part A1 deleted this helper's private `WrapArg<T>` and its six
// `nuget_wrap_*` imports. They were a strictly worse copy of `NugetMarshal.Wrap<T>` -- no null
// guard, no `Nullable<T>` normalisation, no narrow kinds, no `char`, and no ownership report, so
// nobody could dispose what it minted -- and every call site already had `NugetMarshal` in scope.
internal fun StringBuilder.renderFuncNativeHelper(helper: CirFuncNativeHelper) {
  appendLine("    internal static class NugetFuncNative")
  appendLine("    {")

  for (arity in helper.arities.sorted()) {
    val paramStr: String = (listOf("IntPtr handle") +
        (0 until arity).map { "IntPtr arg$it" }).joinToString(", ")

    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_func${arity}_invoke\")]")
    appendLine("        internal static extern IntPtr Invoke$arity($paramStr);")
    appendLine()
  }

  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
  appendLine("        internal static extern void Dispose(IntPtr handle);")

  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderFuncHelper(helper: CirFuncHelper) {
  val ns: String = helper.helperNamespace
  val marshalRef: String = "$ns.NugetMarshal"
  val funcNativeRef: String = "$ns.NugetFuncNative"

  for (arity in helper.arities.sorted()) {
    // Generate KotlinFunc variants (non-Unit)
    if (arity == 0) {
      appendLine("    public class KotlinFunc<TResult> : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinFunc(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public TResult Invoke()")
      appendLine("        {")
      appendLine("            IntPtr result = $funcNativeRef.Invoke0(_handle);")
      appendLine("            return $marshalRef.FromHandle<TResult>(result);")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    } else {
      val typeParams: String = (1..arity).map { "T$it" }.plus("TResult").joinToString(", ")
      val methodParams: String = (1..arity).map { "T$it arg${it - 1}" }.joinToString(", ")
      val invokeArgs: String = (listOf("_handle") + (0 until arity).map { "boxedArg$it" }).joinToString(", ")

      appendLine("    public class KotlinFunc<$typeParams> : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinFunc(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public TResult Invoke($methodParams)")
      appendLine("        {")
      // Boundary nullability part A1: `NugetMarshal.Wrap<T>` in place of the private `WrapArg<T>`
      // copy. Three defects in one substitution: `Wrap<T>` returns `IntPtr.Zero` for a null (the
      // copy passed a null string straight into `export_nuget_wrap_string(value: String)`, an
      // uncaught NPE that killed the host), it normalises `Nullable<T>` to its underlying (the copy
      // matched no branch for `int?` and threw `NotSupportedException`), and it covers the six
      // narrow kinds plus `char`, which the copy never learned. `owned` then closes the leak: the
      // copy's boxes were never disposed by anyone, one leaked StableRef per argument per call.
      for (i in 0 until arity) {
        appendLine(
          "            IntPtr boxedArg$i = $marshalRef.Wrap<T${i + 1}>(arg$i, out bool owned$i);",
        )
      }
      appendLine("            IntPtr result = $funcNativeRef.Invoke$arity($invokeArgs);")
      // Disposed after the native call, never before: the export dereferences each box
      // synchronously inside `Invoke`, so this is the first safe point.
      for (i in 0 until arity) {
        appendLine("            if (owned$i) $funcNativeRef.Dispose(boxedArg$i);")
      }
      appendLine("            return $marshalRef.FromHandle<TResult>(result);")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    }

    // Generate KotlinAction variants (Unit). Issue #114: `void` is not a legal C# type argument,
    // so a Unit-returning lambda drops the return and carries its arity in the parameters alone,
    // exactly as `KotlinSuspendAction` already does on the suspend arm.
    if (arity == 0) {
      appendLine("    public class KotlinAction : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinAction(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public void Invoke()")
      appendLine("        {")
      // `nuget_func0_invoke` returns `StableRef.create(fn.invoke() as Any)`, a live ref to the
      // `Unit` singleton for a Unit lambda. Discarding the pointer would leak one per call.
      appendLine("            IntPtr result = $funcNativeRef.Invoke0(_handle);")
      appendLine("            if (result != IntPtr.Zero) $funcNativeRef.Dispose(result);")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    } else {
      val actionTypeParams: String = (1..arity).map { "T$it" }.joinToString(", ")
      val actionMethodParams: String = (1..arity).map { "T$it arg${it - 1}" }.joinToString(", ")
      val actionInvokeArgs: String =
        (listOf("_handle") + (0 until arity).map { "boxedArg$it" }).joinToString(", ")

      appendLine("    public class KotlinAction<$actionTypeParams> : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinAction(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public void Invoke($actionMethodParams)")
      appendLine("        {")
      // Boundary nullability part A1, the Unit-returning twin; see `KotlinFunc.Invoke` above.
      for (i in 0 until arity) {
        appendLine(
          "            IntPtr boxedArg$i = $marshalRef.Wrap<T${i + 1}>(arg$i, out bool owned$i);",
        )
      }
      appendLine("            IntPtr result = $funcNativeRef.Invoke$arity($actionInvokeArgs);")
      for (i in 0 until arity) {
        appendLine("            if (owned$i) $funcNativeRef.Dispose(boxedArg$i);")
      }
      appendLine("            if (result != IntPtr.Zero) $funcNativeRef.Dispose(result);")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    }
  }
}

// Boundary nullability part A1 deleted the suspend twin of the private `WrapArg<T>` copy too, for
// the same reasons; see [renderFuncNativeHelper].
internal fun StringBuilder.renderSuspendFuncNativeHelper(helper: CirSuspendFuncNativeHelper) {
  appendLine("    internal static class NugetSuspendFuncNative")
  appendLine("    {")

  for (arity in helper.arities.sorted()) {
    val paramStr: String = (listOf("IntPtr handle") +
        (0 until arity).map { "IntPtr arg$it" } +
        listOf("IntPtr callback", "IntPtr userData")).joinToString(", ")

    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_suspend_func${arity}_invoke\")]")
    appendLine("        internal static extern IntPtr Invoke$arity($paramStr);")
    appendLine()
  }

  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
  appendLine("        internal static extern void Dispose(IntPtr handle);")

  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderSuspendFuncHelper(helper: CirSuspendFuncHelper) {
  val ns: String = helper.helperNamespace
  val marshalRef: String = "$ns.NugetMarshal"
  val funcNativeRef: String = "$ns.NugetSuspendFuncNative"

  for (arity in helper.arities.sorted()) {
    // Generate KotlinSuspendFunc variants (non-Unit)
    if (arity == 0) {
      appendLine("    public class KotlinSuspendFunc<TResult> : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinSuspendFunc(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public Task<TResult> InvokeAsync(CancellationToken cancellationToken = default)")
      appendLine("        {")
      appendLine("            var tcs = new TaskCompletionSource<TResult>(TaskCreationOptions.RunContinuationsAsynchronously);")
      appendLine("            NugetAsyncCallback callback = null!;")
      appendLine("            GCHandle callbackHandle = default;")
      appendLine("            var job = new NugetJobCell();")
      // ADR-161: one shared, materialisation-fault-containing closure body.
      appendAsyncCompletionClosure(
        "TaskCompletionSource<TResult>",
        "t.SetResult($marshalRef.FromHandle<TResult>(resultPtr));",
      )
      appendLine("            callbackHandle = GCHandle.Alloc(callback);")
      appendLine(
        "            IntPtr jobHandle = $funcNativeRef.Invoke0(_handle, " +
            "NugetThunks.NugetAsyncCallbackPtr, GCHandle.ToIntPtr(callbackHandle));"
      )
      appendLine("            CancellationTokenRegistration reg = cancellationToken.CanBeCanceled")
      appendLine(
        "                ? cancellationToken.Register(() => " +
            "NugetJobNative.Cancel(jobHandle))"
      )
      appendLine("                : default;")
      appendLine("            job.PublishFromCaller(jobHandle, reg);")
      appendLine("            return tcs.Task;")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    } else {
      val typeParams: String = (1..arity).map { "T$it" }.plus("TResult").joinToString(", ")
      val methodParams: String = (1..arity).map { "T$it arg${it - 1}" }.joinToString(", ")
      val invokeArgs: String = (
          listOf("_handle") +
              (0 until arity).map { "boxedArg$it" } +
              listOf("NugetThunks.NugetAsyncCallbackPtr", "GCHandle.ToIntPtr(callbackHandle)")
          ).joinToString(", ")

      appendLine("    public class KotlinSuspendFunc<$typeParams> : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinSuspendFunc(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public Task<TResult> InvokeAsync($methodParams, CancellationToken cancellationToken = default)")
      appendLine("        {")
      appendLine("            var tcs = new TaskCompletionSource<TResult>(TaskCreationOptions.RunContinuationsAsynchronously);")
      appendLine("            NugetAsyncCallback callback = null!;")
      appendLine("            GCHandle callbackHandle = default;")
      appendLine("            var job = new NugetJobCell();")
      // ADR-161: one shared, materialisation-fault-containing closure body.
      appendAsyncCompletionClosure(
        "TaskCompletionSource<TResult>",
        "t.SetResult($marshalRef.FromHandle<TResult>(resultPtr));",
      )
      appendLine("            callbackHandle = GCHandle.Alloc(callback);")
      // Boundary nullability part A1: `NugetMarshal.Wrap<T>` in place of the private `WrapArg<T>`
      // copy, so a null argument is `IntPtr.Zero` rather than an uncaught NPE inside the export,
      // and `owned` closes the per-call StableRef leak. LOAD-BEARING ordering: the suspend export
      // reads every box SYNCHRONOUSLY, before `launchForCSharp`, which is the only reason
      // disposing them the moment `Invoke` returns is safe rather than a use-after-free.
      for (i in 0 until arity) {
        appendLine(
          "            IntPtr boxedArg$i = $marshalRef.Wrap<T${i + 1}>(arg$i, out bool owned$i);",
        )
      }
      appendLine("            IntPtr jobHandle = $funcNativeRef.Invoke$arity($invokeArgs);")
      for (i in 0 until arity) {
        appendLine("            if (owned$i) $funcNativeRef.Dispose(boxedArg$i);")
      }
      appendLine("            CancellationTokenRegistration reg = cancellationToken.CanBeCanceled")
      appendLine(
        "                ? cancellationToken.Register(() => " +
            "NugetJobNative.Cancel(jobHandle))"
      )
      appendLine("                : default;")
      appendLine("            job.PublishFromCaller(jobHandle, reg);")
      appendLine("            return tcs.Task;")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    }

    // Generate KotlinSuspendAction variants (Unit)
    if (arity == 0) {
      appendLine("    public class KotlinSuspendAction : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinSuspendAction(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public Task InvokeAsync(CancellationToken cancellationToken = default)")
      appendLine("        {")
      appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
      appendLine("            NugetAsyncCallback callback = null!;")
      appendLine("            GCHandle callbackHandle = default;")
      appendLine("            var job = new NugetJobCell();")
      // ADR-161: one shared, materialisation-fault-containing closure body.
      appendAsyncCompletionClosure("TaskCompletionSource<bool>", "t.SetResult(true);")
      appendLine("            callbackHandle = GCHandle.Alloc(callback);")
      appendLine(
        "            IntPtr jobHandle = $funcNativeRef.Invoke0(_handle, " +
            "NugetThunks.NugetAsyncCallbackPtr, GCHandle.ToIntPtr(callbackHandle));"
      )
      appendLine("            CancellationTokenRegistration reg = cancellationToken.CanBeCanceled")
      appendLine(
        "                ? cancellationToken.Register(() => " +
            "NugetJobNative.Cancel(jobHandle))"
      )
      appendLine("                : default;")
      appendLine("            job.PublishFromCaller(jobHandle, reg);")
      appendLine("            return tcs.Task;")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    } else {
      val typeParams: String = (1..arity).map { "T$it" }.joinToString(", ")
      val methodParams: String = (1..arity).map { "T$it arg${it - 1}" }.joinToString(", ")
      val invokeArgs: String = (
          listOf("_handle") +
              (0 until arity).map { "boxedArg$it" } +
              listOf("NugetThunks.NugetAsyncCallbackPtr", "GCHandle.ToIntPtr(callbackHandle)")
          ).joinToString(", ")

      appendLine("    public class KotlinSuspendAction<$typeParams> : IDisposable, INugetHandle")
      appendLine("    {")
      appendLine("        internal IntPtr _handle;")
      appendLine()
      appendLine("        IntPtr INugetHandle.Handle => _handle;")
      appendLine()
      appendLine("        internal KotlinSuspendAction(IntPtr handle) { _handle = handle; }")
      appendLine()
      appendLine("        public Task InvokeAsync($methodParams, CancellationToken cancellationToken = default)")
      appendLine("        {")
      appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
      appendLine("            NugetAsyncCallback callback = null!;")
      appendLine("            GCHandle callbackHandle = default;")
      appendLine("            var job = new NugetJobCell();")
      // ADR-161: one shared, materialisation-fault-containing closure body.
      appendAsyncCompletionClosure("TaskCompletionSource<bool>", "t.SetResult(true);")
      appendLine("            callbackHandle = GCHandle.Alloc(callback);")
      // Boundary nullability part A1, the Unit-returning suspend twin; the same synchronous-read
      // ordering constraint applies, see `KotlinSuspendFunc.InvokeAsync` above.
      for (i in 0 until arity) {
        appendLine(
          "            IntPtr boxedArg$i = $marshalRef.Wrap<T${i + 1}>(arg$i, out bool owned$i);",
        )
      }
      appendLine("            IntPtr jobHandle = $funcNativeRef.Invoke$arity($invokeArgs);")
      for (i in 0 until arity) {
        appendLine("            if (owned$i) $funcNativeRef.Dispose(boxedArg$i);")
      }
      appendLine("            CancellationTokenRegistration reg = cancellationToken.CanBeCanceled")
      appendLine(
        "                ? cancellationToken.Register(() => " +
            "NugetJobNative.Cancel(jobHandle))"
      )
      appendLine("                : default;")
      appendLine("            job.PublishFromCaller(jobHandle, reg);")
      appendLine("            return tcs.Task;")
      appendLine("        }")
      appendLine()
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                $funcNativeRef.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    }
  }
}


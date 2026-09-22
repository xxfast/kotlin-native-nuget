package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-150: the Kotlin-exception to C#-exception table, shared by the `BuildMapped` switch this file
 * renders and by the `<exception cref>` an author's `@throws` becomes. One table, so the documented
 * exception is always the one the consumer actually catches. Iteration order is the switch's
 * rendered order. Anything absent is a `KotlinException`.
 */
internal val KOTLIN_EXCEPTION_TYPES: Map<String, String> = linkedMapOf(
  "kotlin.IllegalArgumentException" to "KotlinArgumentException",
  "kotlin.IllegalStateException" to "KotlinInvalidOperationException",
  "kotlin.NoSuchElementException" to "KotlinInvalidOperationException",
  "kotlin.ConcurrentModificationException" to "KotlinInvalidOperationException",
  "kotlin.UnsupportedOperationException" to "KotlinNotSupportedException",
  "kotlin.ClassCastException" to "KotlinInvalidCastException",
  "kotlin.ArithmeticException" to "KotlinArithmeticException",
  "kotlin.NumberFormatException" to "KotlinFormatException",
)

/**
 * ADR-161: the one type a forward callback's managed failure arrives as on the Kotlin side,
 * declared in `nuget-runtime`. Pinned here because the C# half has to recognise it by name when
 * it decides whether the escaping Kotlin error is the managed exception or an author's wrapper
 * around it.
 */
internal const val NUGET_MANAGED_EXCEPTION_TYPE: String =
  "io.github.xxfast.kotlin.native.nuget.runtime.NugetManagedException"

internal fun StringBuilder.renderErrorHelper(helper: CirErrorHelper) {
  appendLine("    internal static class NugetErrorNative")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_error_type\")]")
  appendLine("        private static extern IntPtr Native_type(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_error_message\")]")
  appendLine("        private static extern IntPtr Native_message(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_error_stacktrace\")]")
  appendLine("        private static extern IntPtr Native_stacktrace(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_error_cause_count\")]")
  appendLine("        private static extern int Native_causeCount(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_error_cause_type\")]")
  appendLine("        private static extern IntPtr Native_causeType(IntPtr handle, int index);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_error_cause_message\")]")
  appendLine("        private static extern IntPtr Native_causeMessage(IntPtr handle, int index);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_error_cause_stacktrace\")]")
  appendLine("        private static extern IntPtr Native_causeStackTrace(IntPtr handle, int index);")
  appendLine()
  appendLine("        internal static string Type(IntPtr handle) => Marshal.PtrToStringUTF8(Native_type(handle))!;")
  appendLine("        internal static string Message(IntPtr handle) => Marshal.PtrToStringUTF8(Native_message(handle))!;")
  appendLine("        internal static string StackTrace(IntPtr handle) => Marshal.PtrToStringUTF8(Native_stacktrace(handle))!;")
  appendLine("        internal static int CauseCount(IntPtr handle) => Native_causeCount(handle);")
  appendLine("        internal static string CauseType(IntPtr handle, int index) => Marshal.PtrToStringUTF8(Native_causeType(handle, index))!;")
  appendLine("        internal static string CauseMessage(IntPtr handle, int index) => Marshal.PtrToStringUTF8(Native_causeMessage(handle, index))!;")
  appendLine("        internal static string CauseStackTrace(IntPtr handle, int index) => Marshal.PtrToStringUTF8(Native_causeStackTrace(handle, index))!;")
  appendLine()
  // ADR-161: the C# half of the forward callback error channel. It lives here rather than in the
  // `NugetThunks` partial because `NugetErrorNative` is emitted unconditionally, while the thunk
  // block is gated on a callback helper: `BuildException` below has to be able to name the stash
  // even in a library that has no callbacks at all.
  //
  // `CreateManagedError` PUSHES a Kotlin-owned holder instead of reading a `GCHandle` back through
  // registered accessors (the recorded deviation from ADR-104's convergence note): a forward-only
  // library has no init-time registration step to hang accessors off, and the push costs one
  // P/Invoke and no new state. The trailing-slot shape and the single `NugetManagedException`
  // type, which are ADR-104's two stated convergence points, are unchanged.
  appendLine(
    "        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, " +
        "EntryPoint = \"nuget_managed_error_create\")]"
  )
  appendLine("        private static extern IntPtr Native_managedErrorCreate(")
  appendLine("            [MarshalAs(UnmanagedType.LPUTF8Str)] string type,")
  appendLine("            [MarshalAs(UnmanagedType.LPUTF8Str)] string message,")
  appendLine("            int kind);")
  appendLine()
  // The stash is what lets an escaping managed exception reach its C# caller as ITSELF rather than
  // as a `KotlinException` wrapper. `BuildException` only takes it when the escaping Kotlin error
  // IS the managed-exception type AND the message matches exactly, so a stash left behind by a
  // callback whose throw Kotlin caught cannot be mistaken for a fresh one, and a Kotlin author who
  // wraps the exception in their own type keeps their wrapper (the discriminating cell).
  appendLine("        [ThreadStatic] private static Exception? _lastManagedFault;")
  appendLine()
  appendLine("        internal static IntPtr CreateManagedError(Exception ex)")
  appendLine("        {")
  appendLine("            _lastManagedFault = ex;")
  // ADR-153's `kind == 1` mapping, mirrored forward: a cancelled C# callback cancels the Kotlin
  // coroutine that invoked it rather than failing it. Deliberately not covered by a cell.
  appendLine("            int kind = ex is OperationCanceledException ? 1 : 0;")
  appendLine("            return Native_managedErrorCreate(")
  appendLine("                ex.GetType().FullName ?? \"System.Exception\",")
  appendLine("                ex.Message ?? string.Empty,")
  appendLine("                kind);")
  appendLine("        }")
  appendLine()
  appendLine("        private const string ManagedExceptionType =")
  appendLine("            \"$NUGET_MANAGED_EXCEPTION_TYPE\";")
  appendLine()
  appendLine(
    "        private static Exception? TakeOriginalManagedFault(string kotlinType, " +
        "string message)"
  )
  appendLine("        {")
  appendLine("            if (kotlinType != ManagedExceptionType) return null;")
  appendLine("            Exception? fault = _lastManagedFault;")
  appendLine("            if (fault == null) return null;")
  appendLine("            string expected = (fault.GetType().FullName ?? \"System.Exception\")")
  appendLine("                + \": \" + (fault.Message ?? string.Empty);")
  appendLine("            if (expected != message) return null;")
  appendLine("            _lastManagedFault = null;")
  appendLine("            return fault;")
  appendLine("        }")
  appendLine()
  appendLine("        internal static Exception BuildException(IntPtr errorPtr)")
  appendLine("        {")
  appendLine("            int causeCount = CauseCount(errorPtr);")
  appendLine("            Exception? inner = null;")
  appendLine("            for (int i = causeCount - 1; i >= 1; i--)")
  appendLine("            {")
  appendLine("                string causeType = CauseType(errorPtr, i);")
  appendLine("                string causeMsg = CauseMessage(errorPtr, i);")
  appendLine("                string causeStack = CauseStackTrace(errorPtr, i);")
  appendLine("                inner = BuildMapped(causeType, causeMsg, causeStack, inner);")
  appendLine("            }")
  appendLine("            string kotlinType = Type(errorPtr);")
  appendLine("            string msg = Message(errorPtr);")
  appendLine("            string stackTrace = StackTrace(errorPtr);")
  appendLine("            NugetMarshal.Dispose(errorPtr);")
  // ADR-161: when the escaping Kotlin error IS the managed exception this process threw a moment
  // ago inside a callback thunk, the C# caller gets the ORIGINAL exception back, so
  // `Assert.Throws<InvalidOperationException>` works on `cat.DescribeWith(_ => throw ...)`.
  // Anything else, including a Kotlin author's own wrapper around it, keeps the ordinary mapping.
  appendLine("            Exception? original = TakeOriginalManagedFault(kotlinType, msg);")
  appendLine("            if (original != null) return original;")
  appendLine("            return BuildMapped(kotlinType, msg, stackTrace, inner);")
  appendLine("        }")
  appendLine()
  appendLine("        internal static T Check<T>(T result, IntPtr error)")
  appendLine("        {")
  appendLine("            if (error != IntPtr.Zero) throw BuildException(error);")
  appendLine("            return result;")
  appendLine("        }")
  appendLine()
  appendLine("        private static Exception BuildMapped(string kotlinType, string message, string stackTrace, Exception? inner) =>")
  appendLine("            kotlinType switch")
  appendLine("            {")
  for ((kotlinType, csharpType) in KOTLIN_EXCEPTION_TYPES) {
    appendLine("                \"$kotlinType\" =>")
    appendLine("                    new $csharpType(kotlinType, message, stackTrace, inner),")
  }
  appendLine("                _ => new KotlinException(kotlinType, message, stackTrace, inner)")
  appendLine("            };")
  appendLine("    }")
  appendLine()
  appendLine("    public interface IKotlinException")
  appendLine("    {")
  appendLine("        string KotlinType { get; }")
  appendLine("        string KotlinStackTrace { get; }")
  appendLine("    }")
  appendLine()
  appendLine("    public class KotlinException : Exception, IKotlinException")
  appendLine("    {")
  appendLine("        public string KotlinType { get; }")
  appendLine("        public string KotlinStackTrace { get; }")
  appendLine()
  appendLine("        public KotlinException(string kotlinType, string message, string kotlinStackTrace,")
  appendLine("            Exception? innerException = null) : base(message, innerException)")
  appendLine("        {")
  appendLine("            KotlinType = kotlinType;")
  appendLine("            KotlinStackTrace = kotlinStackTrace;")
  appendLine("        }")
  appendLine()
  appendLine("        public override string ToString()")
  appendLine("        {")
  appendLine("            return base.ToString()")
  appendLine("                + Environment.NewLine + \" ---> Kotlin stack trace:\"")
  appendLine("                + Environment.NewLine + KotlinStackTrace")
  appendLine("                + Environment.NewLine + \" --- End of Kotlin stack trace ---\";")
  appendLine("        }")
  appendLine("    }")
  appendLine()
  listOf(
    "KotlinArgumentException" to "ArgumentException",
    "KotlinInvalidOperationException" to "InvalidOperationException",
    "KotlinNotSupportedException" to "NotSupportedException",
    "KotlinInvalidCastException" to "InvalidCastException",
    "KotlinArithmeticException" to "ArithmeticException",
    "KotlinFormatException" to "FormatException",
  ).forEach { (name, base) -> renderMappedException(name, base) }
}

internal fun StringBuilder.renderMappedException(name: String, base: String) {
  appendLine("    public sealed class $name : $base, IKotlinException")
  appendLine("    {")
  appendLine("        public string KotlinType { get; }")
  appendLine("        public string KotlinStackTrace { get; }")
  appendLine()
  appendLine("        public $name(string kotlinType, string message, string kotlinStackTrace,")
  appendLine("            Exception? innerException = null) : base(message, innerException)")
  appendLine("        {")
  appendLine("            KotlinType = kotlinType;")
  appendLine("            KotlinStackTrace = kotlinStackTrace;")
  appendLine("        }")
  appendLine()
  appendLine("        public override string ToString()")
  appendLine("        {")
  appendLine("            return base.ToString()")
  appendLine("                + Environment.NewLine + \" ---> Kotlin stack trace:\"")
  appendLine("                + Environment.NewLine + KotlinStackTrace")
  appendLine("                + Environment.NewLine + \" --- End of Kotlin stack trace ---\";")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderSyncErrorCheckMethod(method: CirMethod, className: String = "") {
  val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
  val static: String = if (method.isStatic) "static " else ""
  val override: String = if (method.isOverride) "override " else if (method.isVirtual) "virtual " else ""
  val paramStr: String = method.parameters.mapIndexed { index, param ->
    if (method.isExtension && index == 0) "this ${param.type} ${param.name}"
    else "${param.type} ${param.name}"
  }.joinToString(", ")

  // For static methods (top-level functions), nativeName is the full native function name.
  // For instance methods (class methods), the extern name is whatever `resolvedExternName` says,
  // the one rule `methodNativeImport` mints the `[DllImport]` from.
  val nativeFuncName: String =
    if (method.isStatic) method.nativeName else method.resolvedExternName

  // When a parameter's native type differs from its public type (e.g. enum -> int), cast it.
  val nativeArgList: String = method.parameters.joinToString(", ") { param ->
    if (param.nativeType != param.type) "(${param.nativeType})${param.name}" else param.name
  }

  val nativeCallArgs: String = when {
    nativeArgList.isEmpty() && !method.isStatic -> "_handle, out IntPtr error"
    nativeArgList.isEmpty() -> "out IntPtr error"
    !method.isStatic -> "_handle, $nativeArgList, out IntPtr error"
    else -> "$nativeArgList, out IntPtr error"
  }

  val isVoid: Boolean = method.returnType == "void"
  val isString: Boolean = method.returnType == "string"
  val nativeReturnType: String = method.nativeReturnType

  appendLine("        $visibility ${static}${override}${method.returnType} ${method.name}($paramStr)")
  appendLine("        {")

  when {
    isVoid -> appendLine("            ${nativeFuncName}($nativeCallArgs);")
    isString -> appendLine("            IntPtr nativeResult = ${nativeFuncName}($nativeCallArgs);")
    else -> appendLine("            $nativeReturnType result = ${nativeFuncName}($nativeCallArgs);")
  }

  appendLine("            if (error != IntPtr.Zero)")
  appendLine("            {")
  appendLine("                throw NugetErrorNative.BuildException(error);")
  appendLine("            }")

  when {
    !isVoid && isString -> appendLine("            return Marshal.PtrToStringUTF8(nativeResult)!;")
    !isVoid && nativeReturnType != method.returnType ->
      appendLine("            return (${method.returnType})result;")

    !isVoid -> appendLine("            return result;")
  }

  appendLine("        }")
  appendLine()
}

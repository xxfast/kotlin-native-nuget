package io.github.xxfast.kotlin.native.nuget.processor.cir

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
  appendLine("            return BuildMapped(kotlinType, msg, stackTrace, inner);")
  appendLine("        }")
  appendLine()
  appendLine("        private static Exception BuildMapped(string kotlinType, string message, string stackTrace, Exception? inner) =>")
  appendLine("            kotlinType switch")
  appendLine("            {")
  appendLine("                \"kotlin.IllegalArgumentException\" =>")
  appendLine("                    new KotlinArgumentException(kotlinType, message, stackTrace, inner),")
  appendLine("                \"kotlin.IllegalStateException\" =>")
  appendLine("                    new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),")
  appendLine("                \"kotlin.NoSuchElementException\" =>")
  appendLine("                    new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),")
  appendLine("                \"kotlin.ConcurrentModificationException\" =>")
  appendLine("                    new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),")
  appendLine("                \"kotlin.UnsupportedOperationException\" =>")
  appendLine("                    new KotlinNotSupportedException(kotlinType, message, stackTrace, inner),")
  appendLine("                \"kotlin.ClassCastException\" =>")
  appendLine("                    new KotlinInvalidCastException(kotlinType, message, stackTrace, inner),")
  appendLine("                \"kotlin.ArithmeticException\" =>")
  appendLine("                    new KotlinArithmeticException(kotlinType, message, stackTrace, inner),")
  appendLine("                \"kotlin.NumberFormatException\" =>")
  appendLine("                    new KotlinFormatException(kotlinType, message, stackTrace, inner),")
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
  val override: String = if (method.isOverride) "override " else ""
  val paramStr: String = method.parameters.mapIndexed { index, param ->
    if (method.isExtension && index == 0) "this ${param.type} ${param.name}"
    else "${param.type} ${param.name}"
  }.joinToString(", ")

  // For static methods (top-level functions), nativeName is the full native function name.
  // For instance methods (class methods), the DllImport is rendered as "Native_${method.name}".
  val nativeFuncName: String = if (method.isStatic) method.nativeName else "Native_${method.name}"

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

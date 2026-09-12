package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-129: `NugetRuntime`, the one helper emitted for every library, gated or not. It P/Invokes the
 * 67th `nuget-runtime` export and prints, under the same `NUGET_INTEROP_TRACE` variable ADR-054
 * defined for the reverse bridge, which runtime the loaded native library carries.
 *
 * Two invariants live in the order of these lines, and a Tier 1 test pins both:
 *
 * - the environment check comes **before** the P/Invoke, so with the trace off a module
 *   initializer costs nothing and the moment the native library first loads is unchanged;
 * - the P/Invoke sits in a `try/catch`, because an initializer that throws surfaces as a confusing
 *   load failure, while the first real bridge call would throw the same
 *   `DllNotFoundException`/`EntryPointNotFoundException` with a stack that points at the caller.
 *
 * `System.Runtime.CompilerServices` is only added to the file's usings when the async helpers need
 * it, so the attribute is spelled fully qualified rather than growing the using list.
 */
internal fun StringBuilder.renderRuntimeHelper(helper: CirRuntimeHelper) {
  appendLine("    internal static class NugetRuntime")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_runtime_version\")]")
  appendLine("        private static extern IntPtr Native_version();")
  appendLine()
  appendLine("        internal static string Version => Marshal.PtrToStringUTF8(Native_version())!;")
  appendLine()
  // CA2255 ("ModuleInitializer is only intended for application code") fires on this attribute in
  // every consumer that builds `Interop.cs` with analyzers as errors -- verified: it failed
  // `GeneratedBindingsCheck` (net8.0, warnings as errors) on the first run of this renderer. The
  // suppression is local to the attribute and deliberate: this file IS generated code shipped as
  // source, which is the "advanced source generator scenario" the rule carves out, and a consumer
  // cannot reasonably be asked to add a NoWarn for a helper it did not write.
  appendLine("#pragma warning disable CA2255")
  appendLine("        [System.Runtime.CompilerServices.ModuleInitializer]")
  appendLine("#pragma warning restore CA2255")
  appendLine("        internal static void TraceLoaded()")
  appendLine("        {")
  appendLine("            if (Environment.GetEnvironmentVariable(\"NUGET_INTEROP_TRACE\") is not (\"1\" or \"true\" or \"all\")) return;")
  appendLine("            string line;")
  appendLine("            try { line = \$\"[nuget:interop] runtime {Version} loaded from ${helper.libraryName}\"; }")
  appendLine("            catch (Exception e) { line = \$\"[nuget:interop] runtime version unavailable from ${helper.libraryName}: {e.GetType().Name}: {e.Message}\"; }")
  appendLine("            string? file = Environment.GetEnvironmentVariable(\"NUGET_INTEROP_TRACEFILE\");")
  appendLine("            if (file is null) Console.Error.WriteLine(line);")
  appendLine("            else System.IO.File.AppendAllText(file, line + Environment.NewLine);")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

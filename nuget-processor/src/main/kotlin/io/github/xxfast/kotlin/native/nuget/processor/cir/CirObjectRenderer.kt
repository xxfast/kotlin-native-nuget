package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderObject(obj: CirObject) {
  appendLine("    public static class ${obj.name}")
  appendLine("    {")

  for (method in obj.methods) {
    renderDllImport(method)
  }

  appendLine("    }")
}

internal fun StringBuilder.renderValueClass(cls: CirValueClass) {
  if (cls.underlyingIsReference) {
    renderReferenceValueClass(cls)
    return
  }

  // ADR-035: hand-written record struct so the primary constructor's `init` runs
  // across the bridge. The underlying is a get-only property assigned from a
  // validating CreateChecked* helper, blocking object-initializer / `with` bypass.
  appendLine("    public readonly record struct ${cls.name}")
  appendLine("    {")
  appendLine("        public ${cls.underlyingType} ${cls.underlyingName} { get; }")
  appendLine()

  cls.constructors.forEach { ctor ->
    val paramStr: String = ctor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    val paramNames: String = ctor.parameters.joinToString(", ") { it.name }
    val nativeReturnType: String =
      if (cls.underlyingType == "string") "IntPtr" else cls.underlyingNativeType
    val suffix: String = ctor.nativeSuffix

    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${ctor.nativeName}\")]")
    appendLine("        private static extern $nativeReturnType Native_Create$suffix($paramStr, out IntPtr error);")
    appendLine()
    appendLine("        private static $nativeReturnType CreateChecked$suffix($paramStr)")
    appendLine("        {")
    appendLine("            $nativeReturnType underlying = Native_Create$suffix($paramNames, out IntPtr error);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    appendLine("            return underlying;")
    appendLine("        }")
    appendLine()
    appendLine("        public ${cls.name}($paramStr)")
    appendLine("        {")
    appendLine("            ${cls.underlyingName} = ${ctor.body};")
    appendLine("        }")
    appendLine()
  }

  renderValueClassMembers(cls)

  appendLine("    }")
}

// ADR-035: reference-underlying value classes are deferred — keep the positional
// record struct with `: this(...)` delegation (the ADR-033 secondary scheme).
private fun StringBuilder.renderReferenceValueClass(cls: CirValueClass) {
  appendLine("    public readonly record struct ${cls.name}(${cls.underlyingType} ${cls.underlyingName})")
  appendLine("    {")

  cls.constructors.forEachIndexed { index, ctor ->
    val paramStr: String = ctor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    val paramNames: String = ctor.parameters.joinToString(", ") { it.name }
    val nativeReturnType: String = if (cls.underlyingType == "string") "IntPtr" else cls.underlyingNativeType
    val nativeSuffix: String = if (index > 0) "_$index" else ""

    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${ctor.nativeName}\")]")

    if (ctor.hasErrorCheck) {
      appendLine("        private static extern $nativeReturnType Native_Create$nativeSuffix($paramStr, out IntPtr error);")
      appendLine()
      appendLine("        private static $nativeReturnType CreateChecked$nativeSuffix($paramStr)")
      appendLine("        {")
      appendLine("            $nativeReturnType underlying = Native_Create$nativeSuffix($paramNames, out IntPtr error);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
      appendLine("            return underlying;")
      appendLine("        }")
    } else {
      appendLine("        private static extern $nativeReturnType Native_Create$nativeSuffix($paramStr);")
    }

    appendLine()
    appendLine("        public ${cls.name}($paramStr) : this(${ctor.body}) { }")
    appendLine()
  }

  renderValueClassMembers(cls)

  appendLine("    }")
}

private fun StringBuilder.renderValueClassMembers(cls: CirValueClass) {
  cls.properties.forEach { prop ->
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
    appendLine("        private static extern ${prop.nativeReturnType} Native_Get${prop.name}(${cls.underlyingNativeType} value);")
    appendLine()
    appendLine("        public ${prop.type} ${prop.name} => ${prop.getter};")
    appendLine()
  }

  cls.methods.forEach { method ->
    val nativeReturnType: String = method.nativeReturnType
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_${method.nativeName}\")]")

    if (nativeReturnType == "bool") {
      appendLine("        [return: MarshalAs(UnmanagedType.I1)]")
    }

    appendLine("        private static extern $nativeReturnType Native_${method.name}(${cls.underlyingNativeType} value);")
    appendLine()
    appendLine("        public ${method.returnType} ${method.name}() => ${method.body};")
    appendLine()
  }
}


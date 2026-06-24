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
  appendLine("    public readonly record struct ${cls.name}(${cls.underlyingType} ${cls.underlyingName})")
  appendLine("    {")

  cls.constructors.forEachIndexed { index, ctor ->
    val paramStr: String = ctor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    val nativeReturnType: String = if (cls.underlyingType == "string") "IntPtr" else cls.underlyingNativeType
    val nativeSuffix: String = if (index > 0) "_$index" else ""

    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${ctor.nativeName}\")]")
    appendLine("        private static extern $nativeReturnType Native_Create$nativeSuffix($paramStr);")
    appendLine()
    appendLine("        public ${cls.name}($paramStr) : this(${ctor.body}) { }")
    appendLine()
  }

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

  appendLine("    }")
}


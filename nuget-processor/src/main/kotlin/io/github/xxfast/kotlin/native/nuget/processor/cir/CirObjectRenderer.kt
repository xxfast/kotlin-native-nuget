package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderObject(obj: CirObject) {
  appendLine("    public static class ${obj.name}")
  appendLine("    {")

  obj.methods.forEach { method -> renderMember(method) }

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
    // ADR-077: the native call lowers each argument to its wire shape when the projection
    // supplied one ((int)mood for an enum parameter); public and wire coincide otherwise.
    val nativeArgs: String = ctor.nativeArguments?.joinToString(", ") ?: paramNames
    val nativeReturnType: String =
      if (cls.underlyingType == "string") "IntPtr" else cls.underlyingNativeType
    val suffix: String = ctor.nativeSuffix

    renderDllImport(cls.constructorNativeImport(ctor))
    appendLine("        private static $nativeReturnType CreateChecked$suffix($paramStr)")
    appendLine("        {")
    appendLine("            $nativeReturnType underlying = Native_Create$suffix($nativeArgs, out IntPtr error);")
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

// ADR-035: a reference-underlying value class is the positional record struct over its underlying
// handle and nothing else. Its primary is deferred and its secondaries are skipped by the planner
// (`REFERENCE_UNDERLYING_VALUE_CLASS_CONSTRUCTOR`), so there is no constructor to render here.
private fun StringBuilder.renderReferenceValueClass(cls: CirValueClass) {
  appendLine("    public readonly record struct ${cls.name}(${cls.underlyingType} ${cls.underlyingName})")
  appendLine("    {")

  renderValueClassMembers(cls)

  appendLine("    }")
}

private fun StringBuilder.renderValueClassMembers(cls: CirValueClass) {
  cls.properties.forEach { prop ->
    renderDllImport(cls.propertyNativeImport(prop))
    appendLine("        public ${prop.type} ${prop.name} => ${prop.getter};")
    appendLine()
  }

  cls.methods.forEach { method ->
    renderDllImport(cls.methodNativeImport(method))
    val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    if (method.returnType == "void") {
      appendLine("        public void ${method.name}($paramStr) => ${method.body};")
    } else {
      appendLine("        public ${method.returnType} ${method.name}($paramStr) => ${method.body};")
    }
    appendLine()
  }
}

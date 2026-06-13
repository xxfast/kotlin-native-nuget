package io.github.xxfast.nuget.processor.cir

class CirRenderer {
  fun render(file: CirFile): String = buildString {
    for (using in file.usings) {
      appendLine("using $using;")
    }

    appendLine()

    for (namespace in file.namespaces) {
      renderNamespace(namespace)
    }
  }

  private fun StringBuilder.renderNamespace(namespace: CirNamespace) {
    appendLine("namespace ${namespace.name}")
    appendLine("{")

    for (declaration in namespace.declarations) {
      when (declaration) {
        is CirStaticClass -> renderStaticClass(declaration)
        is CirClass -> renderClass(declaration)
      }
    }

    appendLine("}")
    appendLine()
  }

  private fun StringBuilder.renderStaticClass(cls: CirStaticClass) {
    appendLine("    public static partial class ${cls.name}")
    appendLine("    {")

    for (member in cls.members) {
      renderMember(member)
    }

    appendLine("    }")
  }

  private fun StringBuilder.renderClass(cls: CirClass) {
    val implements: String = if (cls.disposable) " : IDisposable" else ""

    appendLine("    public class ${cls.name}$implements")
    appendLine("    {")
    appendLine("        private IntPtr _handle;")
    appendLine()

    if (cls.constructor != null) {
      val ctorParamStr: String = cls.constructor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_create\")]")
      appendLine("        private static extern IntPtr Native_Create($ctorParamStr);")
      appendLine()
      renderConstructor(cls.name, cls.constructor)
    }

    if (cls.hasInternalHandleConstructor) {
      appendLine("        internal ${cls.name}(IntPtr handle)")
      appendLine("        {")
      appendLine("            _handle = handle;")
      appendLine("        }")
      appendLine()
    }

    for (prop in cls.properties) {
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
      appendLine("        private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle);")
      appendLine()
      renderProperty(prop)
    }

    for (method in cls.methods) {
      val nativeParams: String = (listOf("IntPtr handle") +
        method.parameters.map { "${it.type} ${it.name}" }).joinToString(", ")
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_${method.nativeName}\")]")
      appendLine("        private static extern ${method.nativeReturnType} Native_${method.name}($nativeParams);")
      appendLine()
      renderMethod(method)
    }

    if (cls.disposable) {
      renderDispose(cls.libraryName, cls.nativePrefix)
    }

    appendLine("    }")
  }

  private fun StringBuilder.renderConstructor(className: String, ctor: CirConstructor) {
    val paramStr: String = ctor.parameters.joinToString(", ") { "${it.type} ${it.name}" }

    appendLine("        public $className($paramStr)")
    appendLine("        {")
    appendLine("            ${ctor.body}")
    appendLine("        }")
    appendLine()
  }

  private fun StringBuilder.renderProperty(prop: CirProperty) {
    appendLine("        public ${prop.type} ${prop.name} => ${prop.getter};")
    appendLine()
  }

  private fun StringBuilder.renderMember(member: CirMember) {
    when (member) {
      is CirDllImport -> renderDllImport(member)
      is CirMethod -> renderMethod(member)
      is CirProperty -> renderProperty(member)
    }
  }

  private fun StringBuilder.renderDllImport(import: CirDllImport) {
    val visibility: String = if (import.visibility == CirVisibility.PRIVATE) "private" else "public"
    val entryPoint: String = if (import.entryPoint != null) ", EntryPoint = \"${import.entryPoint}\"" else ""
    val paramStr: String = import.parameters.joinToString(", ") { "${it.type} ${it.name}" }

    appendLine("        [DllImport(\"${import.libraryName}\", CallingConvention = CallingConvention.Cdecl$entryPoint)]")
    appendLine("        $visibility static extern ${import.returnType} ${import.name}($paramStr);")
    appendLine()
  }

  private fun StringBuilder.renderMethod(method: CirMethod) {
    val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
    val static: String = if (method.isStatic) "static " else ""
    val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }

    if (method.returnType == "void") {
      appendLine("        $visibility $static void ${method.name}($paramStr)")
      appendLine("            => ${method.body};")
    } else {
      appendLine("        $visibility $static${method.returnType} ${method.name}($paramStr)")
      appendLine("            => ${method.body};")
    }

    appendLine()
  }

  private fun StringBuilder.renderDispose(libraryName: String, nativePrefix: String) {
    appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${nativePrefix}_dispose\")]")
    appendLine("        private static extern void Native_Dispose(IntPtr handle);")
    appendLine()
    appendLine("        public void Dispose()")
    appendLine("        {")
    appendLine("            if (_handle != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                Native_Dispose(_handle);")
    appendLine("                _handle = IntPtr.Zero;")
    appendLine("            }")
    appendLine("        }")
  }
}

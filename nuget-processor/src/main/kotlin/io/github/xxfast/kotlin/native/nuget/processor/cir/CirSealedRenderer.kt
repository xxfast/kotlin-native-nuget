package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderSealedClass(sealed: CirSealedClass) {
  appendLine("    public abstract class ${sealed.name} : IDisposable, INugetHandle")
  appendLine("    {")
  appendLine("        internal IntPtr _handle;")
  appendLine()
  appendLine("        IntPtr INugetHandle.Handle => _handle;")
  appendLine()
  appendLine("        internal ${sealed.name}(IntPtr handle)")
  appendLine("        {")
  appendLine("            _handle = handle;")
  appendLine("        }")
  appendLine()

  for (subclass in sealed.subclasses) {
    appendLine("        public sealed class ${subclass.name} : ${sealed.name}")
    appendLine("        {")
    appendLine("            internal ${subclass.name}(IntPtr handle) : base(handle)")
    appendLine("            {")
    appendLine("            }")
    appendLine()

    for (prop in subclass.properties) {
      val getterErrorParam: String = if (prop.hasSyncErrorOut) ", out IntPtr error" else ""
      appendLine("            [DllImport(\"${sealed.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${subclass.nativePrefix}_get_${prop.nativeName}\")]")
      appendLine("            private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle$getterErrorParam);")
      appendLine()
      renderSealedSubclassProperty(prop)
      appendLine()
    }

    if (subclass.isDataObject) {
      appendLine("            public override string ToString() => \"${subclass.name}\";")
      appendLine()
    } else if (subclass.isDataClass) {
      renderSealedSubclassDataMethods(sealed.libraryName, subclass.nativePrefix, sealed.name, subclass.name)
    }

    appendLine("            public override void Dispose()")
    appendLine("            {")
    appendLine("                if (_handle != IntPtr.Zero)")
    appendLine("                {")
    appendLine("                    Native_Dispose(_handle);")
    appendLine("                    _handle = IntPtr.Zero;")
    appendLine("                }")
    appendLine("            }")
    appendLine()
    appendLine("            [DllImport(\"${sealed.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${subclass.nativePrefix}_dispose\")]")
    appendLine("            private static extern void Native_Dispose(IntPtr handle);")
    appendLine("        }")
    appendLine()
  }

  appendLine("        [DllImport(\"${sealed.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${sealed.nativePrefix}_get_type\")]")
  appendLine("        private static extern int Native_GetType(IntPtr handle);")
  appendLine()
  appendLine("        internal static ${sealed.name} FromHandle(IntPtr handle)")
  appendLine("        {")
  appendLine("            return Native_GetType(handle) switch")
  appendLine("            {")

  for ((index, subclass) in sealed.subclasses.withIndex()) {
    appendLine("                $index => new ${subclass.name}(handle),")
  }

  appendLine("                _ => throw new InvalidOperationException(\"Unknown sealed class type\")")
  appendLine("            };")
  appendLine("        }")
  appendLine()
  appendLine("        public abstract void Dispose();")
  appendLine("    }")
}

internal fun StringBuilder.renderSealedSubclassDataMethods(libraryName: String, nativePrefix: String, sealedName: String, subclassName: String) {
  appendLine("            [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${nativePrefix}_equals\")]")
  appendLine("            private static extern bool Native_Equals(IntPtr handle, IntPtr other);")
  appendLine()
  appendLine("            [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${nativePrefix}_hashcode\")]")
  appendLine("            private static extern int Native_HashCode(IntPtr handle);")
  appendLine()
  appendLine("            [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${nativePrefix}_tostring\")]")
  appendLine("            private static extern IntPtr Native_ToString(IntPtr handle);")
  appendLine()
  appendLine("            public override bool Equals(object? obj)")
  appendLine("            {")
  appendLine("                if (obj is $subclassName other) return Native_Equals(_handle, other._handle);")
  appendLine("                return false;")
  appendLine("            }")
  appendLine()
  appendLine("            public override int GetHashCode() => Native_HashCode(_handle);")
  appendLine()
  appendLine("            public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;")
  appendLine()
}

/**
 * Renders one sealed-subclass property. A getter whose body is a statement block (every collection
 * getter: the `listHandle`/`count`/`for`/`Dispose`/`return` shape) must sit inside a `get { ... }`
 * block, exactly as the ordinary-class `renderProperty` does; only a single-expression getter (a
 * scalar, an enum, a handle) may use the `=> expr;` form. Emitting a block body as `=> block;` is
 * what made the generated `Interop.cs` unparseable (CS1002/CS1519/CS8124, issue #39).
 *
 * Bodies are shared verbatim with the ordinary-class path, so they are baked at that path's
 * indentation; a sealed subclass nests one level deeper, hence the +4 re-indent here rather than a
 * forked body in the translator.
 */
private fun StringBuilder.renderSealedSubclassProperty(prop: CirProperty) {
  if (!prop.getter.contains('\n')) {
    appendLine("            public ${prop.type} ${prop.name} => ${prop.getter};")
    return
  }

  appendLine("            public ${prop.type} ${prop.name}")
  appendLine("            {")
  appendLine("                get")
  appendLine("                {${prop.getter.indentNestedBody()}")
  appendLine("                }")
  appendLine("            }")
}

/** Shifts an ordinary-class member body one nesting level deeper, leaving blank lines untouched. */
private fun String.indentNestedBody(): String =
  lines().joinToString("\n") { line -> if (line.isBlank()) line else "    $line" }


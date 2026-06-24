package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderSealedClass(sealed: CirSealedClass) {
  appendLine("    public abstract class ${sealed.name} : IDisposable")
  appendLine("    {")
  appendLine("        internal IntPtr _handle;")
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
      appendLine("            [DllImport(\"${sealed.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${subclass.nativePrefix}_get_${prop.nativeName}\")]")
      appendLine("            private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle);")
      appendLine()
      appendLine("            public ${prop.type} ${prop.name} => ${prop.getter};")
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


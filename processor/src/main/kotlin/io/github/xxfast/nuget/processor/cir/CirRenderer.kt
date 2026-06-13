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
        is CirMarshalHelper -> renderMarshalHelper(declaration)
        is CirStaticClass -> renderStaticClass(declaration)
        is CirInterface -> renderInterface(declaration)
        is CirClass -> renderClass(declaration)
        is CirGenericClass -> renderGenericClass(declaration)
        is CirEnum -> renderEnum(declaration)
        is CirSealedClass -> renderSealedClass(declaration)
        is CirObject -> renderObject(declaration)
      }
    }

    appendLine("}")
    appendLine()
  }

  private fun StringBuilder.renderMarshalHelper(helper: CirMarshalHelper) {
    appendLine("    internal static class NugetMarshal")
    appendLine("    {")
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_string\")]")
    appendLine("        private static extern IntPtr Native_unwrap_string(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_byte\")]")
    appendLine("        private static extern sbyte nuget_unwrap_byte(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_ubyte\")]")
    appendLine("        private static extern byte nuget_unwrap_ubyte(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_short\")]")
    appendLine("        private static extern short nuget_unwrap_short(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_ushort\")]")
    appendLine("        private static extern ushort nuget_unwrap_ushort(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_int\")]")
    appendLine("        private static extern int Native_unwrap_int(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_uint\")]")
    appendLine("        private static extern uint nuget_unwrap_uint(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_long\")]")
    appendLine("        private static extern long Native_unwrap_long(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_ulong\")]")
    appendLine("        private static extern ulong nuget_unwrap_ulong(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_float\")]")
    appendLine("        private static extern float Native_unwrap_float(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_double\")]")
    appendLine("        private static extern double Native_unwrap_double(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_bool\")]")
    appendLine("        private static extern bool Native_unwrap_bool(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
    appendLine("        private static extern void Native_dispose(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_string\")]")
    appendLine("        private static extern IntPtr box_create_string(string value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_byte\")]")
    appendLine("        private static extern IntPtr box_create_byte(sbyte value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_ubyte\")]")
    appendLine("        private static extern IntPtr box_create_ubyte(byte value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_short\")]")
    appendLine("        private static extern IntPtr box_create_short(short value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_ushort\")]")
    appendLine("        private static extern IntPtr box_create_ushort(ushort value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_int\")]")
    appendLine("        private static extern IntPtr box_create_int(int value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_uint\")]")
    appendLine("        private static extern IntPtr box_create_uint(uint value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_long\")]")
    appendLine("        private static extern IntPtr box_create_long(long value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_ulong\")]")
    appendLine("        private static extern IntPtr box_create_ulong(ulong value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_float\")]")
    appendLine("        private static extern IntPtr box_create_float(float value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_double\")]")
    appendLine("        private static extern IntPtr box_create_double(double value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_bool\")]")
    appendLine("        private static extern IntPtr box_create_bool(bool value);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"box_create_object\")]")
    appendLine("        private static extern IntPtr box_create_object(IntPtr value);")
    appendLine()
    appendLine("        public static T FromHandle<T>(IntPtr handle)")
    appendLine("        {")
    appendLine("            if (handle == IntPtr.Zero) return default!;")
    appendLine("            if (typeof(T) == typeof(string))")
    appendLine("            {")
    appendLine("                IntPtr strPtr = Native_unwrap_string(handle);")
    appendLine("                string result = Marshal.PtrToStringUTF8(strPtr)!;")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(sbyte))")
    appendLine("            {")
    appendLine("                sbyte result = nuget_unwrap_byte(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(byte))")
    appendLine("            {")
    appendLine("                byte result = nuget_unwrap_ubyte(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(short))")
    appendLine("            {")
    appendLine("                short result = nuget_unwrap_short(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(ushort))")
    appendLine("            {")
    appendLine("                ushort result = nuget_unwrap_ushort(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(int))")
    appendLine("            {")
    appendLine("                int result = Native_unwrap_int(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(uint))")
    appendLine("            {")
    appendLine("                uint result = nuget_unwrap_uint(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(long))")
    appendLine("            {")
    appendLine("                long result = Native_unwrap_long(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(ulong))")
    appendLine("            {")
    appendLine("                ulong result = nuget_unwrap_ulong(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(float))")
    appendLine("            {")
    appendLine("                float result = Native_unwrap_float(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(double))")
    appendLine("            {")
    appendLine("                double result = Native_unwrap_double(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            if (typeof(T) == typeof(bool))")
    appendLine("            {")
    appendLine("                bool result = Native_unwrap_bool(handle);")
    appendLine("                Native_dispose(handle);")
    appendLine("                return (T)(object)result;")
    appendLine("            }")
    appendLine("            return (T)Activator.CreateInstance(typeof(T),")
    appendLine("                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public,")
    appendLine("                null, new object[] { handle }, null)!;")
    appendLine("        }")
    appendLine()
    appendLine("        public static IntPtr CreateBox<T>(T value)")
    appendLine("        {")
    appendLine("            if (typeof(T) == typeof(string)) return box_create_string((string)(object)value!);")
    appendLine("            if (typeof(T) == typeof(sbyte)) return box_create_byte((sbyte)(object)value!);")
    appendLine("            if (typeof(T) == typeof(byte)) return box_create_ubyte((byte)(object)value!);")
    appendLine("            if (typeof(T) == typeof(short)) return box_create_short((short)(object)value!);")
    appendLine("            if (typeof(T) == typeof(ushort)) return box_create_ushort((ushort)(object)value!);")
    appendLine("            if (typeof(T) == typeof(int)) return box_create_int((int)(object)value!);")
    appendLine("            if (typeof(T) == typeof(uint)) return box_create_uint((uint)(object)value!);")
    appendLine("            if (typeof(T) == typeof(long)) return box_create_long((long)(object)value!);")
    appendLine("            if (typeof(T) == typeof(ulong)) return box_create_ulong((ulong)(object)value!);")
    appendLine("            if (typeof(T) == typeof(float)) return box_create_float((float)(object)value!);")
    appendLine("            if (typeof(T) == typeof(double)) return box_create_double((double)(object)value!);")
    appendLine("            if (typeof(T) == typeof(bool)) return box_create_bool((bool)(object)value!);")
    appendLine("            var field = typeof(T).GetField(\"_handle\",")
    appendLine("                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);")
    appendLine("            if (field != null) return box_create_object((IntPtr)field.GetValue(value)!);")
    appendLine("            throw new NotSupportedException($\"Cannot create Box<{typeof(T).Name}>\");")
    appendLine("        }")
    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderGenericClass(cls: CirGenericClass) {
    val typeParams: String = cls.typeParameters.joinToString(", ")

    appendLine("    internal static class ${cls.name}Native")
    appendLine("    {")

    for (prop in cls.properties) {
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
      appendLine("        internal static extern ${prop.nativeReturnType} Get_${prop.nativeName}(IntPtr handle);")
      appendLine()
    }

    if (cls.disposable) {
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_dispose\")]")
      appendLine("        internal static extern void Dispose(IntPtr handle);")
    }

    appendLine("    }")
    appendLine()
    appendLine("    public class ${cls.name}<$typeParams> : IDisposable")
    appendLine("    {")
    appendLine("        internal IntPtr _handle;")
    appendLine()

    if (cls.hasPublicConstructor) {
      appendLine("        public ${cls.name}(${cls.typeParameters[0]} value)")
      appendLine("        {")
      appendLine("            _handle = NugetMarshal.CreateBox<${cls.typeParameters[0]}>(value);")
      appendLine("        }")
      appendLine()
    }

    appendLine("        internal ${cls.name}(IntPtr handle)")
    appendLine("        {")
    appendLine("            _handle = handle;")
    appendLine("        }")
    appendLine()

    for (prop in cls.properties) {
      appendLine("        public ${prop.type} ${prop.name} => ${prop.getter};")
      appendLine()
    }

    if (cls.disposable) {
      appendLine("        public void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                ${cls.name}Native.Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
    }

    appendLine("    }")
  }

  private fun StringBuilder.renderInterface(iface: CirInterface) {
    appendLine("    public interface ${iface.name} : IDisposable")
    appendLine("    {")

    for (prop in iface.properties) {
      if (prop.hasSetter) {
        appendLine("        ${prop.type} ${prop.name} { get; set; }")
      } else {
        appendLine("        ${prop.type} ${prop.name} { get; }")
      }
    }

    if (iface.properties.isNotEmpty() && iface.methods.isNotEmpty()) {
      appendLine()
    }

    for (method in iface.methods) {
      val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
      appendLine("        ${method.returnType} ${method.name}($paramStr);")
    }

    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderEnum(enum: CirEnum) {
    appendLine("    public enum ${enum.name}")
    appendLine("    {")

    for (entry in enum.entries) {
      appendLine("        ${entry.name} = ${entry.ordinal},")
    }

    appendLine("    }")

    if (enum.properties.isNotEmpty()) {
      appendLine()
      renderEnumExtensions(enum)
    }
  }

  private fun StringBuilder.renderEnumExtensions(enum: CirEnum) {
    appendLine("    public static class ${enum.name}Extensions")
    appendLine("    {")

    for (prop in enum.properties) {
      val enumLowercase: String = enum.name.lowercase()
      val propLowercase: String = prop.nativeName.lowercase()
      val entryPoint: String = "${enumLowercase}_get_$propLowercase"

      appendLine("        [DllImport(\"sample\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$entryPoint\")]")
      appendLine("        private static extern ${prop.nativeReturnType} Native_Get${prop.name}(int ordinal);")
      appendLine()

      val body: String = if (prop.type == "string") {
        "Marshal.PtrToStringUTF8(Native_Get${prop.name}((int)${enum.name.lowercase()}))!"
      } else {
        "Native_Get${prop.name}((int)${enum.name.lowercase()})"
      }

      appendLine("        public static ${prop.type} ${prop.name}(this ${enum.name} ${enum.name.lowercase()})")
      appendLine("            => $body;")
      appendLine()
    }

    appendLine("    }")
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
    val abstract: String = if (cls.isAbstract) "abstract " else ""

    val implements: String = when {
      cls.superClass != null -> " : ${cls.superClass}"
      cls.interfaces.isNotEmpty() -> " : ${cls.interfaces.joinToString(", ")}"
      cls.disposable -> " : IDisposable"
      else -> ""
    }

    appendLine("    public ${abstract}class ${cls.name}$implements")
    appendLine("    {")

    if (cls.superClass == null) {
      val handleVisibility: String = if (cls.isAbstract) "internal" else "private"
      appendLine("        $handleVisibility IntPtr _handle;")
      appendLine()
    }

    if (cls.constructor != null && !cls.isAbstract) {
      val ctorParamStr: String = cls.constructor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_create\")]")
      appendLine("        private static extern IntPtr Native_Create($ctorParamStr);")
      appendLine()
      renderConstructor(cls.name, cls.constructor, cls.superClass != null)
    }

    if (cls.hasInternalHandleConstructor) {
      if (cls.superClass != null) {
        appendLine("        internal ${cls.name}(IntPtr handle) : base(handle)")
        appendLine("        {")
        appendLine("        }")
      } else {
        appendLine("        internal ${cls.name}(IntPtr handle)")
        appendLine("        {")
        appendLine("            _handle = handle;")
        appendLine("        }")
      }
      appendLine()
    }

    for (prop in cls.properties) {
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
      appendLine("        private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle);")
      appendLine()
      if (prop.setter != null) {
        val setterType: String = prop.nativeReturnType
        appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_set_${prop.nativeName}\")]")
        appendLine("        private static extern void Native_Set_${prop.nativeName}(IntPtr handle, $setterType value);")
        appendLine()
      }
      renderProperty(prop)
    }

    for (method in cls.methods) {
      if (!method.isAbstract) {
        val nativeParams: String = (listOf("IntPtr handle") +
          method.parameters.map { "${it.type} ${it.name}" }).joinToString(", ")
        appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_${method.nativeName}\")]")
        appendLine("        private static extern ${method.nativeReturnType} Native_${method.name}($nativeParams);")
        appendLine()
      }
      renderMethod(method)
    }

    if (cls.isDataClass) {
      renderDataClassMethods(cls)
    }

    if (cls.disposable) {
      renderDispose(cls.libraryName, cls.nativePrefix, cls.isAbstract, cls.superClass != null)
    }

    appendLine("    }")
  }

  private fun StringBuilder.renderConstructor(className: String, ctor: CirConstructor, hasSuperClass: Boolean = false) {
    val paramStr: String = ctor.parameters.joinToString(", ") { "${it.type} ${it.name}" }

    if (hasSuperClass) {
      appendLine("        public $className($paramStr) : base(IntPtr.Zero)")
      appendLine("        {")
      appendLine("            ${ctor.body}")
      appendLine("        }")
    } else {
      appendLine("        public $className($paramStr)")
      appendLine("        {")
      appendLine("            ${ctor.body}")
      appendLine("        }")
    }
    appendLine()
  }

  private fun StringBuilder.renderProperty(prop: CirProperty) {
    if (prop.setter == null) {
      appendLine("        public ${prop.type} ${prop.name} => ${prop.getter};")
    } else {
      appendLine("        public ${prop.type} ${prop.name}")
      appendLine("        {")
      appendLine("            get => ${prop.getter};")
      appendLine("            set => ${prop.setter};")
      appendLine("        }")
    }
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
    val override: String = if (method.isOverride) "override " else ""
    val abstract: String = if (method.isAbstract) "abstract " else ""
    val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }

    val hasGenericType: Boolean = method.returnType.contains("T") ||
      method.parameters.any { it.type.contains("T") }

    val genericDecl: String = if (hasGenericType && method.isStatic) "<T>" else ""

    if (method.isAbstract) {
      appendLine("        $visibility ${abstract}${method.returnType} ${method.name}$genericDecl($paramStr);")
    } else {
      val isMultiLine: Boolean = method.body.contains('\n')

      if (isMultiLine) {
        if (method.returnType == "void") {
          appendLine("        $visibility ${static}${override}void ${method.name}$genericDecl($paramStr)")
        } else {
          appendLine("        $visibility $static$override${method.returnType} ${method.name}$genericDecl($paramStr)")
        }
        appendLine("        {${method.body}")
        appendLine("        }")
      } else {
        if (method.returnType == "void") {
          appendLine("        $visibility $static$override void ${method.name}$genericDecl($paramStr)")
          appendLine("            => ${method.body};")
        } else {
          appendLine("        $visibility $static$override${method.returnType} ${method.name}$genericDecl($paramStr)")
          appendLine("            => ${method.body};")
        }
      }
    }

    appendLine()
  }

  private fun StringBuilder.renderDataClassMethods(cls: CirClass) {
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_equals\")]")
    appendLine("        private static extern bool Native_Equals(IntPtr handle, IntPtr other);")
    appendLine()
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_hashcode\")]")
    appendLine("        private static extern int Native_HashCode(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_tostring\")]")
    appendLine("        private static extern IntPtr Native_ToString(IntPtr handle);")
    appendLine()

    if (cls.constructor != null) {
      val copyParams: String = cls.constructor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
      val copyParamNames: String = cls.constructor.parameters.joinToString(", ") { it.name }
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_copy\")]")
      appendLine("        private static extern IntPtr Native_Copy(IntPtr handle, $copyParams);")
      appendLine()
      appendLine("        public ${cls.name} Copy($copyParams) => new ${cls.name}(Native_Copy(_handle, $copyParamNames));")
      appendLine()
    }

    appendLine("        public override bool Equals(object? obj)")
    appendLine("        {")
    appendLine("            if (obj is ${cls.name} other) return Native_Equals(_handle, other._handle);")
    appendLine("            return false;")
    appendLine("        }")
    appendLine()
    appendLine("        public override int GetHashCode() => Native_HashCode(_handle);")
    appendLine()
    appendLine("        public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;")
    appendLine()
  }

  private fun StringBuilder.renderSealedClass(sealed: CirSealedClass) {
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

  private fun StringBuilder.renderSealedSubclassDataMethods(libraryName: String, nativePrefix: String, sealedName: String, subclassName: String) {
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

  private fun StringBuilder.renderDispose(libraryName: String, nativePrefix: String, isAbstract: Boolean = false, hasSuperClass: Boolean = false) {
    val abstract: String = if (isAbstract) "abstract " else ""
    val override: String = if (hasSuperClass) "override " else ""

    if (isAbstract) {
      appendLine("        public ${abstract}void Dispose();")
    } else {
      appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${nativePrefix}_dispose\")]")
      appendLine("        private static extern void Native_Dispose(IntPtr handle);")
      appendLine()
      appendLine("        public ${override}void Dispose()")
      appendLine("        {")
      appendLine("            if (_handle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                Native_Dispose(_handle);")
      appendLine("                _handle = IntPtr.Zero;")
      appendLine("            }")
      appendLine("        }")
    }
  }

  private fun StringBuilder.renderObject(obj: CirObject) {
    appendLine("    public static class ${obj.name}")
    appendLine("    {")

    for (method in obj.methods) {
      renderDllImport(method)
    }

    appendLine("    }")
  }
}

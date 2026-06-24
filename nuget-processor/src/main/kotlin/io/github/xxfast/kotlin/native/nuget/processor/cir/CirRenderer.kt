package io.github.xxfast.kotlin.native.nuget.processor.cir

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
        is CirListHelper -> renderListHelper(declaration)
        is CirMapHelper -> renderMapHelper(declaration)
        is CirSetHelper -> renderSetHelper(declaration)
        is CirFuncNativeHelper -> renderFuncNativeHelper(declaration)
        is CirFuncHelper -> renderFuncHelper(declaration)
        is CirSuspendFuncNativeHelper -> renderSuspendFuncNativeHelper(declaration)
        is CirSuspendFuncHelper -> renderSuspendFuncHelper(declaration)
        is CirAsyncHelper -> renderAsyncHelper(declaration)
        is CirScopeHelper -> renderScopeHelper(declaration)
        is CirJobHelper -> renderJobHelper(declaration)
        is CirErrorHelper -> renderErrorHelper(declaration)
        is CirFlowHelper -> renderFlowHelper(declaration)
        is CirStaticClass -> renderStaticClass(declaration)
        is CirInterface -> renderInterface(declaration)
        is CirClass -> renderClass(declaration)
        is CirGenericClass -> renderGenericClass(declaration)
        is CirEnum -> renderEnum(declaration)
        is CirSealedClass -> renderSealedClass(declaration)
        is CirObject -> renderObject(declaration)
        is CirValueClass -> renderValueClass(declaration)
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
    appendLine("        public static void Dispose(IntPtr handle) => Native_dispose(handle);")
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

  private fun StringBuilder.renderListHelper(helper: CirListHelper) {
    appendLine("    internal static class NugetListNative")
    appendLine("    {")
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_list_count\")]")
    appendLine("        internal static extern int Count(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_list_get\")]")
    appendLine("        internal static extern IntPtr Get(IntPtr handle, int index);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")
    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderMapHelper(helper: CirMapHelper) {
    appendLine("    internal static class NugetMapNative")
    appendLine("    {")
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_map_count\")]")
    appendLine("        internal static extern int Count(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_map_key_at\")]")
    appendLine("        internal static extern IntPtr KeyAt(IntPtr handle, int index);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_map_value_at\")]")
    appendLine("        internal static extern IntPtr ValueAt(IntPtr handle, int index);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")
    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderSetHelper(helper: CirSetHelper) {
    appendLine("    internal static class NugetSetNative")
    appendLine("    {")
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_set_count\")]")
    appendLine("        internal static extern int Count(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_set_element_at\")]")
    appendLine("        internal static extern IntPtr ElementAt(IntPtr handle, int index);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")
    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderFuncNativeHelper(helper: CirFuncNativeHelper) {
    val hasArgs: Boolean = helper.arities.any { it > 0 }

    appendLine("    internal static class NugetFuncNative")
    appendLine("    {")

    for (arity in helper.arities.sorted()) {
      val paramStr: String = (listOf("IntPtr handle") +
        (0 until arity).map { "IntPtr arg$it" }).joinToString(", ")

      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_func${arity}_invoke\")]")
      appendLine("        internal static extern IntPtr Invoke$arity($paramStr);")
      appendLine()
    }

    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")

    if (hasArgs) {
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_string\")]")
      appendLine("        private static extern IntPtr wrap_string(string value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_int\")]")
      appendLine("        private static extern IntPtr wrap_int(int value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_long\")]")
      appendLine("        private static extern IntPtr wrap_long(long value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_float\")]")
      appendLine("        private static extern IntPtr wrap_float(float value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_double\")]")
      appendLine("        private static extern IntPtr wrap_double(double value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_bool\")]")
      appendLine("        private static extern IntPtr wrap_bool(bool value);")
      appendLine()
      appendLine("        internal static IntPtr WrapArg<T>(T value)")
      appendLine("        {")
      appendLine("            if (typeof(T) == typeof(string)) return wrap_string((string)(object)value!);")
      appendLine("            if (typeof(T) == typeof(int)) return wrap_int((int)(object)value!);")
      appendLine("            if (typeof(T) == typeof(long)) return wrap_long((long)(object)value!);")
      appendLine("            if (typeof(T) == typeof(float)) return wrap_float((float)(object)value!);")
      appendLine("            if (typeof(T) == typeof(double)) return wrap_double((double)(object)value!);")
      appendLine("            if (typeof(T) == typeof(bool)) return wrap_bool((bool)(object)value!);")
      appendLine("            var field = typeof(T).GetField(\"_handle\",")
      appendLine("                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);")
      appendLine("            if (field != null) return (IntPtr)field.GetValue(value)!;")
      appendLine("            throw new NotSupportedException($\"Cannot wrap {typeof(T).Name} as lambda argument\");")
      appendLine("        }")
    }

    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderFuncHelper(helper: CirFuncHelper) {
    val ns: String = helper.helperNamespace
    val marshalRef: String = "$ns.NugetMarshal"
    val funcNativeRef: String = "$ns.NugetFuncNative"

    for (arity in helper.arities.sorted()) {
      if (arity == 0) {
        appendLine("    public class KotlinFunc<TResult> : IDisposable")
        appendLine("    {")
        appendLine("        internal IntPtr _handle;")
        appendLine()
        appendLine("        internal KotlinFunc(IntPtr handle) { _handle = handle; }")
        appendLine()
        appendLine("        public TResult Invoke()")
        appendLine("        {")
        appendLine("            IntPtr result = $funcNativeRef.Invoke0(_handle);")
        appendLine("            return $marshalRef.FromHandle<TResult>(result);")
        appendLine("        }")
        appendLine()
        appendLine("        public void Dispose()")
        appendLine("        {")
        appendLine("            if (_handle != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                $funcNativeRef.Dispose(_handle);")
        appendLine("                _handle = IntPtr.Zero;")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine()
      } else {
        val typeParams: String = (1..arity).map { "T$it" }.plus("TResult").joinToString(", ")
        val methodParams: String = (1..arity).map { "T$it arg${it - 1}" }.joinToString(", ")
        val invokeArgs: String = (listOf("_handle") + (0 until arity).map { "boxedArg$it" }).joinToString(", ")

        appendLine("    public class KotlinFunc<$typeParams> : IDisposable")
        appendLine("    {")
        appendLine("        internal IntPtr _handle;")
        appendLine()
        appendLine("        internal KotlinFunc(IntPtr handle) { _handle = handle; }")
        appendLine()
        appendLine("        public TResult Invoke($methodParams)")
        appendLine("        {")
        for (i in 0 until arity) {
          appendLine("            IntPtr boxedArg$i = $funcNativeRef.WrapArg<T${i + 1}>(arg$i);")
        }
        appendLine("            IntPtr result = $funcNativeRef.Invoke$arity($invokeArgs);")
        appendLine("            return $marshalRef.FromHandle<TResult>(result);")
        appendLine("        }")
        appendLine()
        appendLine("        public void Dispose()")
        appendLine("        {")
        appendLine("            if (_handle != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                $funcNativeRef.Dispose(_handle);")
        appendLine("                _handle = IntPtr.Zero;")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine()
      }
    }
  }

  private fun StringBuilder.renderSuspendFuncNativeHelper(helper: CirSuspendFuncNativeHelper) {
    val hasArgs: Boolean = helper.arities.any { it > 0 }

    appendLine("    internal static class NugetSuspendFuncNative")
    appendLine("    {")

    for (arity in helper.arities.sorted()) {
      val paramStr: String = (listOf("IntPtr handle") +
        (0 until arity).map { "IntPtr arg$it" } +
        listOf("NugetAsyncCallback callback", "IntPtr userData")).joinToString(", ")

      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_suspend_func${arity}_invoke\")]")
      appendLine("        internal static extern IntPtr Invoke$arity($paramStr);")
      appendLine()
    }

    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")

    if (hasArgs) {
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_string\")]")
      appendLine("        private static extern IntPtr wrap_string(string value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_int\")]")
      appendLine("        private static extern IntPtr wrap_int(int value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_long\")]")
      appendLine("        private static extern IntPtr wrap_long(long value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_float\")]")
      appendLine("        private static extern IntPtr wrap_float(float value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_double\")]")
      appendLine("        private static extern IntPtr wrap_double(double value);")
      appendLine()
      appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_bool\")]")
      appendLine("        private static extern IntPtr wrap_bool(bool value);")
      appendLine()
      appendLine("        internal static IntPtr WrapArg<T>(T value)")
      appendLine("        {")
      appendLine("            if (typeof(T) == typeof(string)) return wrap_string((string)(object)value!);")
      appendLine("            if (typeof(T) == typeof(int)) return wrap_int((int)(object)value!);")
      appendLine("            if (typeof(T) == typeof(long)) return wrap_long((long)(object)value!);")
      appendLine("            if (typeof(T) == typeof(float)) return wrap_float((float)(object)value!);")
      appendLine("            if (typeof(T) == typeof(double)) return wrap_double((double)(object)value!);")
      appendLine("            if (typeof(T) == typeof(bool)) return wrap_bool((bool)(object)value!);")
      appendLine("            var field = typeof(T).GetField(\"_handle\",")
      appendLine("                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);")
      appendLine("            if (field != null) return (IntPtr)field.GetValue(value)!;")
      appendLine("            throw new NotSupportedException(\$\"Cannot wrap {typeof(T).Name} as lambda argument\");")
      appendLine("        }")
    }

    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderSuspendFuncHelper(helper: CirSuspendFuncHelper) {
    val ns: String = helper.helperNamespace
    val marshalRef: String = "$ns.NugetMarshal"
    val funcNativeRef: String = "$ns.NugetSuspendFuncNative"

    for (arity in helper.arities.sorted()) {
      // Generate KotlinSuspendFunc variants (non-Unit)
      if (arity == 0) {
        appendLine("    public class KotlinSuspendFunc<TResult> : IDisposable")
        appendLine("    {")
        appendLine("        internal IntPtr _handle;")
        appendLine()
        appendLine("        internal KotlinSuspendFunc(IntPtr handle) { _handle = handle; }")
        appendLine()
        appendLine("        public Task<TResult> InvokeAsync(CancellationToken cancellationToken = default)")
        appendLine("        {")
        appendLine("            var tcs = new TaskCompletionSource<TResult>(TaskCreationOptions.RunContinuationsAsynchronously);")
        appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
        appendLine("            NugetAsyncCallback callback = null!;")
        appendLine("            GCHandle callbackHandle = default;")
        appendLine("            CancellationTokenRegistration reg = default;")
        appendLine("            IntPtr jobHandle = IntPtr.Zero;")
        appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
        appendLine("            {")
        appendLine("                reg.Dispose();")
        appendLine("                NugetJobNative.Dispose(jobHandle);")
        appendLine("                callbackHandle.Free();")
        appendLine("                var t = (TaskCompletionSource<TResult>)GCHandle.FromIntPtr(userData).Target!;")
        appendLine("                GCHandle.FromIntPtr(userData).Free();")
        appendLine("                if (isCancelled != 0)")
        appendLine("                {")
        appendLine("                    t.TrySetCanceled(cancellationToken);")
        appendLine("                }")
        appendLine("                else if (errorPtr != IntPtr.Zero)")
        appendLine("                {")
        appendLine("                    t.SetException(NugetErrorNative.BuildException(errorPtr));")
        appendLine("                }")
        appendLine("                else")
        appendLine("                {")
        appendLine("                    t.SetResult($marshalRef.FromHandle<TResult>(resultPtr));")
        appendLine("                }")
        appendLine("            };")
        appendLine("            callbackHandle = GCHandle.Alloc(callback);")
        appendLine("            jobHandle = $funcNativeRef.Invoke0(_handle, callback, GCHandle.ToIntPtr(tcsHandle));")
        appendLine("            if (cancellationToken.CanBeCanceled)")
        appendLine("                reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));")
        appendLine("            return tcs.Task;")
        appendLine("        }")
        appendLine()
        appendLine("        public void Dispose()")
        appendLine("        {")
        appendLine("            if (_handle != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                $funcNativeRef.Dispose(_handle);")
        appendLine("                _handle = IntPtr.Zero;")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine()
      } else {
        val typeParams: String = (1..arity).map { "T$it" }.plus("TResult").joinToString(", ")
        val methodParams: String = (1..arity).map { "T$it arg${it - 1}" }.joinToString(", ")
        val invokeArgs: String = (listOf("_handle") +
          (0 until arity).map { "boxedArg$it" } +
          listOf("callback", "GCHandle.ToIntPtr(tcsHandle)")).joinToString(", ")

        appendLine("    public class KotlinSuspendFunc<$typeParams> : IDisposable")
        appendLine("    {")
        appendLine("        internal IntPtr _handle;")
        appendLine()
        appendLine("        internal KotlinSuspendFunc(IntPtr handle) { _handle = handle; }")
        appendLine()
        appendLine("        public Task<TResult> InvokeAsync($methodParams, CancellationToken cancellationToken = default)")
        appendLine("        {")
        appendLine("            var tcs = new TaskCompletionSource<TResult>(TaskCreationOptions.RunContinuationsAsynchronously);")
        appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
        appendLine("            NugetAsyncCallback callback = null!;")
        appendLine("            GCHandle callbackHandle = default;")
        appendLine("            CancellationTokenRegistration reg = default;")
        appendLine("            IntPtr jobHandle = IntPtr.Zero;")
        appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
        appendLine("            {")
        appendLine("                reg.Dispose();")
        appendLine("                NugetJobNative.Dispose(jobHandle);")
        appendLine("                callbackHandle.Free();")
        appendLine("                var t = (TaskCompletionSource<TResult>)GCHandle.FromIntPtr(userData).Target!;")
        appendLine("                GCHandle.FromIntPtr(userData).Free();")
        appendLine("                if (isCancelled != 0)")
        appendLine("                {")
        appendLine("                    t.TrySetCanceled(cancellationToken);")
        appendLine("                }")
        appendLine("                else if (errorPtr != IntPtr.Zero)")
        appendLine("                {")
        appendLine("                    t.SetException(NugetErrorNative.BuildException(errorPtr));")
        appendLine("                }")
        appendLine("                else")
        appendLine("                {")
        appendLine("                    t.SetResult($marshalRef.FromHandle<TResult>(resultPtr));")
        appendLine("                }")
        appendLine("            };")
        appendLine("            callbackHandle = GCHandle.Alloc(callback);")
        for (i in 0 until arity) {
          appendLine("            IntPtr boxedArg$i = $funcNativeRef.WrapArg<T${i + 1}>(arg$i);")
        }
        appendLine("            jobHandle = $funcNativeRef.Invoke$arity($invokeArgs);")
        appendLine("            if (cancellationToken.CanBeCanceled)")
        appendLine("                reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));")
        appendLine("            return tcs.Task;")
        appendLine("        }")
        appendLine()
        appendLine("        public void Dispose()")
        appendLine("        {")
        appendLine("            if (_handle != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                $funcNativeRef.Dispose(_handle);")
        appendLine("                _handle = IntPtr.Zero;")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine()
      }

      // Generate KotlinSuspendAction variants (Unit)
      if (arity == 0) {
        appendLine("    public class KotlinSuspendAction : IDisposable")
        appendLine("    {")
        appendLine("        internal IntPtr _handle;")
        appendLine()
        appendLine("        internal KotlinSuspendAction(IntPtr handle) { _handle = handle; }")
        appendLine()
        appendLine("        public Task InvokeAsync(CancellationToken cancellationToken = default)")
        appendLine("        {")
        appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
        appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
        appendLine("            NugetAsyncCallback callback = null!;")
        appendLine("            GCHandle callbackHandle = default;")
        appendLine("            CancellationTokenRegistration reg = default;")
        appendLine("            IntPtr jobHandle = IntPtr.Zero;")
        appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
        appendLine("            {")
        appendLine("                reg.Dispose();")
        appendLine("                NugetJobNative.Dispose(jobHandle);")
        appendLine("                callbackHandle.Free();")
        appendLine("                var t = (TaskCompletionSource<bool>)GCHandle.FromIntPtr(userData).Target!;")
        appendLine("                GCHandle.FromIntPtr(userData).Free();")
        appendLine("                if (isCancelled != 0)")
        appendLine("                {")
        appendLine("                    t.TrySetCanceled(cancellationToken);")
        appendLine("                }")
        appendLine("                else if (errorPtr != IntPtr.Zero)")
        appendLine("                {")
        appendLine("                    t.SetException(NugetErrorNative.BuildException(errorPtr));")
        appendLine("                }")
        appendLine("                else")
        appendLine("                {")
        appendLine("                    t.SetResult(true);")
        appendLine("                }")
        appendLine("            };")
        appendLine("            callbackHandle = GCHandle.Alloc(callback);")
        appendLine("            jobHandle = $funcNativeRef.Invoke0(_handle, callback, GCHandle.ToIntPtr(tcsHandle));")
        appendLine("            if (cancellationToken.CanBeCanceled)")
        appendLine("                reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));")
        appendLine("            return tcs.Task;")
        appendLine("        }")
        appendLine()
        appendLine("        public void Dispose()")
        appendLine("        {")
        appendLine("            if (_handle != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                $funcNativeRef.Dispose(_handle);")
        appendLine("                _handle = IntPtr.Zero;")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine()
      } else {
        val typeParams: String = (1..arity).map { "T$it" }.joinToString(", ")
        val methodParams: String = (1..arity).map { "T$it arg${it - 1}" }.joinToString(", ")
        val invokeArgs: String = (listOf("_handle") +
          (0 until arity).map { "boxedArg$it" } +
          listOf("callback", "GCHandle.ToIntPtr(tcsHandle)")).joinToString(", ")

        appendLine("    public class KotlinSuspendAction<$typeParams> : IDisposable")
        appendLine("    {")
        appendLine("        internal IntPtr _handle;")
        appendLine()
        appendLine("        internal KotlinSuspendAction(IntPtr handle) { _handle = handle; }")
        appendLine()
        appendLine("        public Task InvokeAsync($methodParams, CancellationToken cancellationToken = default)")
        appendLine("        {")
        appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
        appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
        appendLine("            NugetAsyncCallback callback = null!;")
        appendLine("            GCHandle callbackHandle = default;")
        appendLine("            CancellationTokenRegistration reg = default;")
        appendLine("            IntPtr jobHandle = IntPtr.Zero;")
        appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
        appendLine("            {")
        appendLine("                reg.Dispose();")
        appendLine("                NugetJobNative.Dispose(jobHandle);")
        appendLine("                callbackHandle.Free();")
        appendLine("                var t = (TaskCompletionSource<bool>)GCHandle.FromIntPtr(userData).Target!;")
        appendLine("                GCHandle.FromIntPtr(userData).Free();")
        appendLine("                if (isCancelled != 0)")
        appendLine("                {")
        appendLine("                    t.TrySetCanceled(cancellationToken);")
        appendLine("                }")
        appendLine("                else if (errorPtr != IntPtr.Zero)")
        appendLine("                {")
        appendLine("                    t.SetException(NugetErrorNative.BuildException(errorPtr));")
        appendLine("                }")
        appendLine("                else")
        appendLine("                {")
        appendLine("                    t.SetResult(true);")
        appendLine("                }")
        appendLine("            };")
        appendLine("            callbackHandle = GCHandle.Alloc(callback);")
        for (i in 0 until arity) {
          appendLine("            IntPtr boxedArg$i = $funcNativeRef.WrapArg<T${i + 1}>(arg$i);")
        }
        appendLine("            jobHandle = $funcNativeRef.Invoke$arity($invokeArgs);")
        appendLine("            if (cancellationToken.CanBeCanceled)")
        appendLine("                reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));")
        appendLine("            return tcs.Task;")
        appendLine("        }")
        appendLine()
        appendLine("        public void Dispose()")
        appendLine("        {")
        appendLine("            if (_handle != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                $funcNativeRef.Dispose(_handle);")
        appendLine("                _handle = IntPtr.Zero;")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine()
      }
    }
  }

  private fun StringBuilder.renderAsyncHelper(helper: CirAsyncHelper) {
    appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
    appendLine("    internal delegate void NugetAsyncCallback(IntPtr result, IntPtr error, byte isCancelled, IntPtr userData);")
    appendLine()
  }

  private fun StringBuilder.renderScopeHelper(helper: CirScopeHelper) {
    appendLine("    internal static class NugetScopeNative")
    appendLine("    {")
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_create\")]")
    appendLine("        internal static extern IntPtr Create();")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_cancel\")]")
    appendLine("        internal static extern void Cancel(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_scope_drain\")]")
    appendLine("        internal static extern IntPtr Drain(IntPtr handle, NugetAsyncCallback callback, IntPtr userData);")
    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderJobHelper(helper: CirJobHelper) {
    appendLine("    internal static class NugetJobNative")
    appendLine("    {")
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_job_cancel\")]")
    appendLine("        internal static extern void Cancel(IntPtr handle);")
    appendLine()
    appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_job_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")
    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderErrorHelper(helper: CirErrorHelper) {
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
    appendLine("        internal static KotlinException BuildException(IntPtr errorPtr)")
    appendLine("        {")
    appendLine("            int causeCount = CauseCount(errorPtr);")
    appendLine("            KotlinException? inner = null;")
    appendLine("            for (int i = causeCount - 1; i >= 1; i--)")
    appendLine("            {")
    appendLine("                string causeType = CauseType(errorPtr, i);")
    appendLine("                string causeMsg = CauseMessage(errorPtr, i);")
    appendLine("                string causeStack = CauseStackTrace(errorPtr, i);")
    appendLine("                inner = new KotlinException(causeType, causeMsg, causeStack, inner);")
    appendLine("            }")
    appendLine("            string kotlinType = Type(errorPtr);")
    appendLine("            string msg = Message(errorPtr);")
    appendLine("            string stackTrace = StackTrace(errorPtr);")
    appendLine("            NugetMarshal.Dispose(errorPtr);")
    appendLine("            return new KotlinException(kotlinType, msg, stackTrace, inner);")
    appendLine("        }")
    appendLine("    }")
    appendLine()
    appendLine("    public class KotlinException : Exception")
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
  }

  private fun StringBuilder.renderFlowHelper(helper: CirFlowHelper) {
    appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
    appendLine("    internal delegate void NugetFlowOnNextCallback(IntPtr itemPtr, byte isCancelled, IntPtr userData);")
    appendLine()
    appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
    appendLine("    internal delegate void NugetFlowOnCompleteCallback(IntPtr userData);")
    appendLine()
    appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
    appendLine("    internal delegate void NugetFlowOnErrorCallback(IntPtr errorPtr, IntPtr userData);")
    appendLine()
    appendLine("    internal delegate IntPtr NugetFlowCollectDelegate(IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
    appendLine()
    appendLine("    public class KotlinFlow<T> : IAsyncEnumerable<T>")
    appendLine("    {")
    appendLine("        private readonly NugetFlowCollectDelegate _startCollect;")
    appendLine()
    appendLine("        internal KotlinFlow(NugetFlowCollectDelegate startCollect)")
    appendLine("        {")
    appendLine("            _startCollect = startCollect;")
    appendLine("        }")
    appendLine()
    appendLine("        public IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)")
    appendLine("            => new KotlinFlowEnumerator<T>(_startCollect, cancellationToken);")
    appendLine("    }")
    appendLine()
    appendLine("    internal class KotlinFlowEnumerator<T> : IAsyncEnumerator<T>")
    appendLine("    {")
    appendLine("        private readonly Channel<T> _channel;")
    appendLine("        private readonly CancellationTokenRegistration _cancelReg;")
    appendLine("        private IntPtr _jobHandle;")
    appendLine("        private GCHandle _onNextHandle;")
    appendLine("        private GCHandle _onCompleteHandle;")
    appendLine("        private GCHandle _onErrorHandle;")
    appendLine("        private bool _done;")
    appendLine()
    appendLine("        public T Current { get; private set; } = default!;")
    appendLine()
    appendLine("        internal KotlinFlowEnumerator(NugetFlowCollectDelegate startCollect, CancellationToken cancellationToken)")
    appendLine("        {")
    appendLine("            _channel = Channel.CreateUnbounded<T>(new UnboundedChannelOptions { SingleReader = true, SingleWriter = true });")
    appendLine()
    appendLine("            NugetFlowOnNextCallback onNext = (itemPtr, isCancelled, userData) =>")
    appendLine("            {")
    appendLine("                if (isCancelled != 0) { _channel.Writer.TryComplete(); return; }")
    appendLine("                T value = NugetMarshal.FromHandle<T>(itemPtr);")
    appendLine("                _channel.Writer.TryWrite(value);")
    appendLine("            };")
    appendLine()
    appendLine("            NugetFlowOnCompleteCallback onComplete = (userData) =>")
    appendLine("            {")
    appendLine("                _channel.Writer.TryComplete();")
    appendLine("            };")
    appendLine()
    appendLine("            NugetFlowOnErrorCallback onError = (errorPtr, userData) =>")
    appendLine("            {")
    appendLine("                _channel.Writer.TryComplete(NugetErrorNative.BuildException(errorPtr));")
    appendLine("            };")
    appendLine()
    appendLine("            _onNextHandle = GCHandle.Alloc(onNext);")
    appendLine("            _onCompleteHandle = GCHandle.Alloc(onComplete);")
    appendLine("            _onErrorHandle = GCHandle.Alloc(onError);")
    appendLine()
    appendLine("            IntPtr onNextPtr = Marshal.GetFunctionPointerForDelegate(onNext);")
    appendLine("            IntPtr onCompletePtr = Marshal.GetFunctionPointerForDelegate(onComplete);")
    appendLine("            IntPtr onErrorPtr = Marshal.GetFunctionPointerForDelegate(onError);")
    appendLine()
    appendLine("            _jobHandle = startCollect(onNextPtr, onCompletePtr, onErrorPtr, IntPtr.Zero);")
    appendLine()
    appendLine("            if (cancellationToken.CanBeCanceled)")
    appendLine("                _cancelReg = cancellationToken.Register(() => NugetJobNative.Cancel(_jobHandle));")
    appendLine("        }")
    appendLine()
    appendLine("        public async ValueTask<bool> MoveNextAsync()")
    appendLine("        {")
    appendLine("            if (_done) return false;")
    appendLine()
    appendLine("            try")
    appendLine("            {")
    appendLine("                if (await _channel.Reader.WaitToReadAsync())")
    appendLine("                {")
    appendLine("                    if (_channel.Reader.TryRead(out T? item))")
    appendLine("                    {")
    appendLine("                        Current = item;")
    appendLine("                        return true;")
    appendLine("                    }")
    appendLine("                }")
    appendLine("            }")
    appendLine("            catch (ChannelClosedException)")
    appendLine("            {")
    appendLine("                _done = true;")
    appendLine("                if (_channel.Reader.Completion.IsFaulted)")
    appendLine("                {")
    appendLine("                    var ex = _channel.Reader.Completion.Exception?.InnerException;")
    appendLine("                    if (ex != null) throw ex;")
    appendLine("                }")
    appendLine("                return false;")
    appendLine("            }")
    appendLine()
    appendLine("            _done = true;")
    appendLine()
    appendLine("            if (_channel.Reader.Completion.IsFaulted)")
    appendLine("            {")
    appendLine("                var ex = _channel.Reader.Completion.Exception?.InnerException;")
    appendLine("                if (ex != null) throw ex;")
    appendLine("            }")
    appendLine()
    appendLine("            return false;")
    appendLine("        }")
    appendLine()
    appendLine("        public ValueTask DisposeAsync()")
    appendLine("        {")
    appendLine("            _cancelReg.Dispose();")
    appendLine("            if (_jobHandle != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                NugetJobNative.Cancel(_jobHandle);")
    appendLine("                NugetJobNative.Dispose(_jobHandle);")
    appendLine("                _jobHandle = IntPtr.Zero;")
    appendLine("            }")
    appendLine("            if (_onNextHandle.IsAllocated) _onNextHandle.Free();")
    appendLine("            if (_onCompleteHandle.IsAllocated) _onCompleteHandle.Free();")
    appendLine("            if (_onErrorHandle.IsAllocated) _onErrorHandle.Free();")
    appendLine("            _channel.Writer.TryComplete();")
    appendLine("            return ValueTask.CompletedTask;")
    appendLine("        }")
    appendLine("    }")
    appendLine()
  }

  private fun StringBuilder.renderGenericClass(cls: CirGenericClass) {
    val typeParams: String = cls.typeParameters.joinToString(", ") { it.name }

    val isConstrained: Boolean = cls.typeParameters.any { it.bounds.isNotEmpty() }

    appendLine("    internal static class ${cls.name}Native")
    appendLine("    {")

    if (isConstrained && cls.hasPublicConstructor) {
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_create_object\")]")
      appendLine("        internal static extern IntPtr Create_object(IntPtr value);")
      appendLine()
    }

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

    val whereClause: String = cls.typeParameters
      .filter { it.bounds.isNotEmpty() }
      .joinToString(" ") { param ->
        "where ${param.name} : ${param.bounds.joinToString(", ")}"
      }

    val whereStr: String = if (whereClause.isNotEmpty()) " $whereClause" else ""
    appendLine("    public class ${cls.name}<$typeParams> : IDisposable$whereStr")
    appendLine("    {")
    appendLine("        internal IntPtr _handle;")
    appendLine()

    if (cls.hasPublicConstructor) {
      appendLine("        public ${cls.name}(${cls.typeParameters[0].name} value)")
      appendLine("        {")
      if (isConstrained) {
        appendLine("            var field = typeof(${cls.typeParameters[0].name}).GetField(\"_handle\",")
        appendLine("                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);")
        appendLine("            _handle = ${cls.name}Native.Create_object((IntPtr)field!.GetValue(value)!);")
      } else {
        appendLine("            _handle = NugetMarshal.CreateBox<${cls.typeParameters[0].name}>(value);")
      }
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
    val typeParamStr: String = if (iface.typeParameters.isNotEmpty()) {
      val params: String = iface.typeParameters.joinToString(", ") { param ->
        val prefix: String = when (param.variance) {
          CirVariance.COVARIANT -> "out "
          CirVariance.CONTRAVARIANT -> "in "
          CirVariance.INVARIANT -> ""
        }
        "$prefix${param.name}"
      }
      "<$params>"
    } else ""

    appendLine("    public interface ${iface.name}$typeParamStr : IDisposable")
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
      cls.disposable && cls.hasSuspendMethods -> " : IDisposable, IAsyncDisposable"
      cls.disposable -> " : IDisposable"
      else -> ""
    }

    appendLine("    public ${abstract}class ${cls.name}$implements")
    appendLine("    {")

    if (cls.superClass == null) {
      appendLine("        internal IntPtr _handle;")
      if (cls.hasSuspendMethods) {
        appendLine("        internal IntPtr _scopeHandle;")
      }
      appendLine()

      if (cls.hasSuspendMethods) {
        appendLine("        private IntPtr GetOrCreateScope()")
        appendLine("        {")
        appendLine("            IntPtr existing = _scopeHandle;")
        appendLine("            if (existing != IntPtr.Zero) return existing;")
        appendLine("            IntPtr created = NugetScopeNative.Create();")
        appendLine("            IntPtr prior = Interlocked.CompareExchange(ref _scopeHandle, created, IntPtr.Zero);")
        appendLine("            if (prior != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                NugetScopeNative.Dispose(created);")
        appendLine("                return prior;")
        appendLine("            }")
        appendLine("            return created;")
        appendLine("        }")
        appendLine()
      }
    }

    if (cls.constructor != null && !cls.isAbstract) {
      val ctorParamStr: String = cls.constructor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_create\")]")
      appendLine("        private static extern IntPtr Native_Create($ctorParamStr);")
      appendLine()
      renderConstructor(cls.name, cls.constructor, cls.superClass != null, cls.hasSuspendMethods)
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
      if (prop.isFlow) {
        val collectEntryPoint = "${cls.nativePrefix}_get_${prop.nativeName}_collect"
        appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$collectEntryPoint\")]")
        appendLine("        private static extern IntPtr Native_Get${prop.name}Collect(IntPtr handle, IntPtr scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
        appendLine()
        renderProperty(prop)
      } else {
        appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
        appendLine("        private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle);")
        appendLine()
        if (prop.setter != null) {
          val setterType: String = prop.nativeSetterType
          appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_set_${prop.nativeName}\")]")
          appendLine("        private static extern void Native_Set_${prop.nativeName}(IntPtr handle, $setterType value);")
          appendLine()
        }
        for (extra in prop.extraNatives) {
          val entryPoint = "${cls.nativePrefix}_${extra.entryPointSuffix}"
          val paramStr = if (extra.hasValueParam) "IntPtr handle, ${extra.returnType} value" else "IntPtr handle"
          val externReturn = if (extra.hasValueParam) "void" else extra.returnType
          appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$entryPoint\")]")
          appendLine("        private static extern $externReturn ${extra.name}($paramStr);")
          appendLine()
        }
        renderProperty(prop)
      }
    }

    for (method in cls.methods) {
      if (!method.isAbstract) {
        val nativeParamList: MutableList<String> = (listOf("IntPtr handle") +
          method.parameters.map { "${it.type} ${it.name}" }).toMutableList()
        if (method.isSyncErrorCheckEnabled) nativeParamList.add("out IntPtr error")
        val nativeParams: String = nativeParamList.joinToString(", ")
        appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_${method.nativeName}\")]")
        appendLine("        private static extern ${method.nativeReturnType} Native_${method.name}($nativeParams);")
        appendLine()
      }
      renderMethod(method, cls.name)
    }

    for (member in cls.companionMembers) {
      renderMember(member, cls.name)
    }

    if (cls.isDataClass) {
      renderDataClassMethods(cls)
    }

    if (cls.disposable) {
      renderDispose(
        libraryName = cls.libraryName,
        nativePrefix = cls.nativePrefix,
        isAbstract = cls.isAbstract,
        hasSuperClass = cls.superClass != null,
        hasSuspendMethods = cls.hasSuspendMethods,
      )
    }

    appendLine("    }")
  }

  private fun StringBuilder.renderConstructor(
    className: String,
    ctor: CirConstructor,
    hasSuperClass: Boolean = false,
    hasSuspendMethods: Boolean = false,
  ) {
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
    val static: String = if (prop.isStatic) "static " else ""
    val isMultiLineGetter: Boolean = prop.getter.contains('\n')
    val isMultiLineSetter: Boolean = prop.setter?.contains('\n') == true
    if (isMultiLineGetter) {
      appendLine("        public ${static}${prop.type} ${prop.name}")
      appendLine("        {")
      appendLine("            get")
      appendLine("            {${prop.getter}")
      appendLine("            }")
      appendLine("        }")
    } else if (prop.setter == null) {
      appendLine("        public ${static}${prop.type} ${prop.name} => ${prop.getter};")
    } else if (isMultiLineSetter) {
      appendLine("        public ${static}${prop.type} ${prop.name}")
      appendLine("        {")
      appendLine("            get => ${prop.getter};")
      appendLine("            set")
      appendLine("            {${prop.setter}")
      appendLine("            }")
      appendLine("        }")
    } else {
      appendLine("        public ${static}${prop.type} ${prop.name}")
      appendLine("        {")
      appendLine("            get => ${prop.getter};")
      appendLine("            set => ${prop.setter};")
      appendLine("        }")
    }
    appendLine()
  }

  private fun StringBuilder.renderMember(member: CirMember, className: String = "") {
    when (member) {
      is CirDllImport -> renderDllImport(member)
      is CirMethod -> renderMethod(member, className)
      is CirProperty -> renderProperty(member)
      is CirConst -> renderConst(member)
    }
  }

  private fun StringBuilder.renderConst(const: CirConst) {
    appendLine("        public const ${const.type} ${const.name} = ${const.value};")
    appendLine()
  }

  private fun StringBuilder.renderDllImport(import: CirDllImport) {
    val visibility: String = if (import.visibility == CirVisibility.PRIVATE) "private" else "public"
    val entryPoint: String = if (import.entryPoint != null) ", EntryPoint = \"${import.entryPoint}\"" else ""
    val paramList: MutableList<String> = import.parameters.map { "${it.type} ${it.name}" }.toMutableList()
    if (import.hasSyncErrorOut) paramList.add("out IntPtr error")
    val paramStr: String = paramList.joinToString(", ")

    appendLine("        [DllImport(\"${import.libraryName}\", CallingConvention = CallingConvention.Cdecl$entryPoint)]")
    appendLine("        $visibility static extern ${import.returnType} ${import.name}($paramStr);")
    appendLine()
  }

  private val primitiveAsyncTypes = setOf(
    "string", "int", "long", "float", "double", "bool",
    "sbyte", "byte", "short", "ushort", "uint", "ulong",
  )

  private fun StringBuilder.renderAsyncMethod(method: CirMethod, className: String = "") {
    val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
    val static: String = if (method.isStatic) "static " else ""
    val isUnit: Boolean = method.asyncReturnType.isEmpty()
    val innerType: String = if (isUnit) "bool" else method.asyncReturnType
    val tcsType: String = "TaskCompletionSource<$innerType>"
    val nativeName: String = method.nativeName

    val paramNames: String = method.parameters.joinToString(", ") { it.name }

    val methodParams: String = if (method.parameters.isEmpty()) {
      "CancellationToken cancellationToken = default"
    } else {
      method.parameters.joinToString(", ") { "${it.type} ${it.name}" } +
        ", CancellationToken cancellationToken = default"
    }

    val nativeCallArgs: String = if (method.isStatic) {
      if (paramNames.isEmpty()) "callback, GCHandle.ToIntPtr(tcsHandle)"
      else "$paramNames, callback, GCHandle.ToIntPtr(tcsHandle)"
    } else {
      if (paramNames.isEmpty()) "_handle, GetOrCreateScope(), callback, GCHandle.ToIntPtr(tcsHandle)"
      else "_handle, GetOrCreateScope(), $paramNames, callback, GCHandle.ToIntPtr(tcsHandle)"
    }

    val resultExtraction: String = when {
      isUnit -> "t.SetResult(true);"
      method.asyncReturnType in primitiveAsyncTypes ->
        "t.SetResult(NugetMarshal.FromHandle<${method.asyncReturnType}>(resultPtr));"
      else ->
        "t.SetResult(new ${method.asyncReturnType}(resultPtr));"
    }

    appendLine("        $visibility ${static}${method.returnType} ${method.name}($methodParams)")
    appendLine("        {")
    if (!method.isStatic && className.isNotEmpty()) {
      appendLine("            if (_handle == IntPtr.Zero)")
      appendLine("                throw new ObjectDisposedException(nameof($className));")
    }
    appendLine("            var tcs = new $tcsType(TaskCreationOptions.RunContinuationsAsynchronously);")
    appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
    appendLine("            NugetAsyncCallback callback = null!;")
    appendLine("            GCHandle callbackHandle = default;")
    appendLine("            CancellationTokenRegistration reg = default;")
    appendLine("            IntPtr jobHandle = IntPtr.Zero;")
    appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
    appendLine("            {")
    appendLine("                reg.Dispose();")
    appendLine("                NugetJobNative.Dispose(jobHandle);")
    appendLine("                callbackHandle.Free();")
    appendLine("                var t = ($tcsType)GCHandle.FromIntPtr(userData).Target!;")
    appendLine("                GCHandle.FromIntPtr(userData).Free();")
    appendLine("                if (isCancelled != 0)")
    appendLine("                {")
    appendLine("                    t.TrySetCanceled(cancellationToken);")
    appendLine("                }")
    appendLine("                else if (errorPtr != IntPtr.Zero)")
    appendLine("                {")
    appendLine("                    t.SetException(NugetErrorNative.BuildException(errorPtr));")
    appendLine("                }")
    appendLine("                else")
    appendLine("                {")
    appendLine("                    $resultExtraction")
    appendLine("                }")
    appendLine("            };")
    appendLine("            callbackHandle = GCHandle.Alloc(callback);")
    appendLine("            jobHandle = $nativeName($nativeCallArgs);")
    appendLine("            if (cancellationToken.CanBeCanceled)")
    appendLine("                reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));")
    appendLine("            return tcs.Task;")
    appendLine("        }")
    appendLine()
  }

  private fun StringBuilder.renderSyncErrorCheckMethod(method: CirMethod, className: String = "") {
    val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
    val static: String = if (method.isStatic) "static " else ""
    val override: String = if (method.isOverride) "override " else ""
    val paramStr: String = method.parameters.mapIndexed { index, param ->
      if (method.isExtension && index == 0) "this ${param.type} ${param.name}"
      else "${param.type} ${param.name}"
    }.joinToString(", ")

    val paramNames: String = method.parameters.joinToString(", ") { it.name }

    // For static methods (top-level functions), nativeName is the full native function name.
    // For instance methods (class methods), the DllImport is rendered as "Native_${method.name}".
    val nativeFuncName: String = if (method.isStatic) method.nativeName else "Native_${method.name}"

    val nativeCallArgs: String = if (paramNames.isEmpty()) {
      if (!method.isStatic) "_handle, out IntPtr error" else "out IntPtr error"
    } else {
      if (!method.isStatic) "_handle, $paramNames, out IntPtr error" else "$paramNames, out IntPtr error"
    }

    val isVoid: Boolean = method.returnType == "void"
    val isString: Boolean = method.returnType == "string"
    val nativeReturnType: String = method.nativeReturnType

    appendLine("        $visibility ${static}${override}${method.returnType} ${method.name}($paramStr)")
    appendLine("        {")

    when {
      isVoid -> appendLine("            ${nativeFuncName}($nativeCallArgs);")
      isString -> appendLine("            IntPtr nativeResult = ${nativeFuncName}($nativeCallArgs);")
      else -> appendLine("            ${method.returnType} result = ${nativeFuncName}($nativeCallArgs);")
    }

    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")

    when {
      !isVoid && isString -> appendLine("            return Marshal.PtrToStringUTF8(nativeResult)!;")
      !isVoid -> appendLine("            return result;")
    }

    appendLine("        }")
    appendLine()
  }

  private fun StringBuilder.renderFlowMethod(method: CirMethod, className: String) {
    val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    val nativeName: String = method.nativeName

    appendLine("        public KotlinFlow<${method.flowElementType}> ${method.name}($paramStr)")
    appendLine("        {")
    appendLine("            if (_handle == IntPtr.Zero)")
    appendLine("                throw new ObjectDisposedException(nameof($className));")
    appendLine("            return new KotlinFlow<${method.flowElementType}>((onNext, onComplete, onError, userData) =>")
    appendLine("                $nativeName(${method.body}));")
    appendLine("        }")
    appendLine()
  }

  private fun StringBuilder.renderMethod(method: CirMethod, className: String = "") {
    if (method.isAsync) {
      renderAsyncMethod(method, className)
      return
    }

    if (method.isFlow) {
      renderFlowMethod(method, className)
      return
    }

    if (method.isSyncErrorCheckEnabled) {
      renderSyncErrorCheckMethod(method, className)
      return
    }

    val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
    val static: String = if (method.isStatic) "static " else ""
    val override: String = if (method.isOverride) "override " else ""
    val abstract: String = if (method.isAbstract) "abstract " else ""
    val paramStr: String = method.parameters.mapIndexed { index, param ->
      if (method.isExtension && index == 0) "this ${param.type} ${param.name}"
      else "${param.type} ${param.name}"
    }.joinToString(", ")

    val hasGenericType: Boolean = method.typeParameters.isNotEmpty() ||
      method.returnType.contains("T") ||
      method.parameters.any { it.type.contains("T") }

    val genericDecl: String = if (hasGenericType && method.isStatic) {
      val names: String = if (method.typeParameters.isNotEmpty()) {
        method.typeParameters.joinToString(", ") { it.name }
      } else "T"
      "<$names>"
    } else ""

    val whereClause: String = method.typeParameters
      .filter { it.bounds.isNotEmpty() }
      .joinToString(" ") { param ->
        "where ${param.name} : ${param.bounds.joinToString(", ")}"
      }

    val whereStr: String =
      if (whereClause.isNotEmpty()) " $whereClause" else ""

    if (method.isAbstract) {
      appendLine("        $visibility ${abstract}${method.returnType} ${method.name}$genericDecl($paramStr)$whereStr;")
    } else {
      val isMultiLine: Boolean = method.body.contains('\n')

      if (isMultiLine) {
        if (method.returnType == "void") {
          appendLine("        $visibility ${static}${override}void ${method.name}$genericDecl($paramStr)$whereStr")
        } else {
          appendLine("        $visibility $static$override${method.returnType} ${method.name}$genericDecl($paramStr)$whereStr")
        }
        appendLine("        {${method.body}")
        appendLine("        }")
      } else {
        if (method.returnType == "void") {
          appendLine("        $visibility $static$override void ${method.name}$genericDecl($paramStr)$whereStr")
          appendLine("            => ${method.body};")
        } else {
          appendLine("        $visibility $static$override${method.returnType} ${method.name}$genericDecl($paramStr)$whereStr")
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

  private fun StringBuilder.renderDispose(
    libraryName: String,
    nativePrefix: String,
    isAbstract: Boolean = false,
    hasSuperClass: Boolean = false,
    hasSuspendMethods: Boolean = false,
  ) {
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
      appendLine("            IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);")
      appendLine("            if (handle == IntPtr.Zero) return;")
      if (hasSuspendMethods) {
        appendLine("            IntPtr scopeHandle = Interlocked.Exchange(ref _scopeHandle, IntPtr.Zero);")
        appendLine("            if (scopeHandle != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                NugetScopeNative.Cancel(scopeHandle);")
        appendLine("                NugetScopeNative.Dispose(scopeHandle);")
        appendLine("            }")
      }
      appendLine("            Native_Dispose(handle);")
      appendLine("        }")
      if (hasSuspendMethods) {
        appendLine()
        appendLine("        public ValueTask DisposeAsync()")
        appendLine("        {")
        appendLine("            IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);")
        appendLine("            if (handle == IntPtr.Zero) return ValueTask.CompletedTask;")
        appendLine("            IntPtr scopeHandle = Interlocked.Exchange(ref _scopeHandle, IntPtr.Zero);")
        appendLine("            if (scopeHandle == IntPtr.Zero)")
        appendLine("            {")
        appendLine("                Native_Dispose(handle);")
        appendLine("                return ValueTask.CompletedTask;")
        appendLine("            }")
        appendLine("            return new ValueTask(DrainAndDisposeAsync(handle, scopeHandle));")
        appendLine("        }")
        appendLine()
        appendLine("        private Task DrainAndDisposeAsync(IntPtr handle, IntPtr scopeHandle)")
        appendLine("        {")
        appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
        appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
        appendLine("            NugetAsyncCallback callback = null!;")
        appendLine("            GCHandle callbackHandle = default;")
        appendLine("            IntPtr drainJobHandle = IntPtr.Zero;")
        appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
        appendLine("            {")
        appendLine("                NugetJobNative.Dispose(drainJobHandle);")
        appendLine("                callbackHandle.Free();")
        appendLine("                var t = (TaskCompletionSource<bool>)GCHandle.FromIntPtr(userData).Target!;")
        appendLine("                GCHandle.FromIntPtr(userData).Free();")
        appendLine("                NugetScopeNative.Dispose(scopeHandle);")
        appendLine("                Native_Dispose(handle);")
        appendLine("                if (isCancelled != 0)")
        appendLine("                    t.TrySetCanceled();")
        appendLine("                else")
        appendLine("                    t.SetResult(true);")
        appendLine("            };")
        appendLine("            callbackHandle = GCHandle.Alloc(callback);")
        appendLine("            drainJobHandle = NugetScopeNative.Drain(scopeHandle, callback, GCHandle.ToIntPtr(tcsHandle));")
        appendLine("            return tcs.Task;")
        appendLine("        }")
      }
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

  private fun StringBuilder.renderValueClass(cls: CirValueClass) {
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
}

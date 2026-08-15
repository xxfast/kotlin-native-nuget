package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderMarshalHelper(helper: CirMarshalHelper) {
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
  // ADR-098 part B: a bare `char` return marshals as one ANSI byte, which loses every non-ASCII
  // character outright (U+FFFD). Kotlin's `KChar` is `unsigned short`, so the explicit U2 width
  // is what makes the two sides agree -- ADR-069's `[MarshalAs(UnmanagedType.I1)]` on `bool`,
  // same class of bug, same shape of fix.
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_unwrap_char\")]")
  appendLine("        [return: MarshalAs(UnmanagedType.U2)]")
  appendLine("        private static extern char nuget_unwrap_char(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
  appendLine("        private static extern void Native_dispose(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_string\")]")
  appendLine("        private static extern IntPtr nuget_wrap_string(string value);")
  appendLine()
  appendLine("        public static IntPtr WrapString(string value) => nuget_wrap_string(value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_int\")]")
  appendLine("        private static extern IntPtr nuget_wrap_int(int value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_long\")]")
  appendLine("        private static extern IntPtr nuget_wrap_long(long value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_float\")]")
  appendLine("        private static extern IntPtr nuget_wrap_float(float value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_double\")]")
  appendLine("        private static extern IntPtr nuget_wrap_double(double value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_bool\")]")
  appendLine("        private static extern IntPtr nuget_wrap_bool(bool value);")
  appendLine()
  // ADR-098 part A: the write half of the six narrow primitives `FromHandle<T>` has always read.
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_byte\")]")
  appendLine("        private static extern IntPtr nuget_wrap_byte(sbyte value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_ubyte\")]")
  appendLine("        private static extern IntPtr nuget_wrap_ubyte(byte value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_short\")]")
  appendLine("        private static extern IntPtr nuget_wrap_short(short value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_ushort\")]")
  appendLine("        private static extern IntPtr nuget_wrap_ushort(ushort value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_uint\")]")
  appendLine("        private static extern IntPtr nuget_wrap_uint(uint value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_ulong\")]")
  appendLine("        private static extern IntPtr nuget_wrap_ulong(ulong value);")
  appendLine()
  // ADR-098 part B: same U2 width directive as the unwrap side, on a by-value parameter.
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_wrap_char\")]")
  appendLine("        private static extern IntPtr nuget_wrap_char([MarshalAs(UnmanagedType.U2)] char value);")
  appendLine()
  // ADR-094: the reflection-free materialisation table. One statically written line per concrete
  // wrapper, so the trimmer keeps each constructor and the AOT compiler pre-compiles it. A plain
  // static field initializer is enough: the CLR runs the type initializer before the first access
  // to any static member of NugetMarshal, and every read goes through Materialize<T> below.
  appendLine("        internal static readonly System.Collections.Generic.Dictionary<Type, Func<IntPtr, object>> Factories =")
  appendLine("            new System.Collections.Generic.Dictionary<Type, Func<IntPtr, object>>")
  appendLine("        {")
  for (entry in helper.factories) {
    appendLine("            [typeof(global::${entry.qualifiedTypeName})] = static handle => new global::${entry.qualifiedTypeName}(handle),")
  }
  appendLine("        };")
  appendLine()
  appendLine("        internal static T Materialize<T>(IntPtr handle)")
  appendLine("        {")
  appendLine("            if (Factories.TryGetValue(typeof(T), out Func<IntPtr, object>? factory)) return (T)factory(handle);")
  appendLine("            throw new NotSupportedException($\"No generated factory materialises {typeof(T)} from a Kotlin handle\");")
  appendLine("        }")
  appendLine()
  appendLine("        public static T FromHandle<T>(IntPtr handle)")
  appendLine("        {")
  appendLine("            if (handle == IntPtr.Zero) return default!;")
  appendLine("            // ADR-067: a nullable VALUE element (e.g. StateFlow<int?>) instantiates T as")
  appendLine("            // Nullable<int>; typeof(T) never equals typeof(int) for that T, so the plain")
  appendLine("            // per-primitive dispatch below would fall through to the Activator branch and")
  appendLine("            // throw MissingMethodException. Dispatch on the underlying type first, reusing")
  appendLine("            // the same box readers; a null reference element needs no such branch (its")
  appendLine("            // `default!` is already null and its non-null path constructs normally below).")
  appendLine("            Type? nullableUnderlying = Nullable.GetUnderlyingType(typeof(T));")
  appendLine("            if (nullableUnderlying != null)")
  appendLine("            {")
  appendLine("                if (nullableUnderlying == typeof(sbyte))")
  appendLine("                {")
  appendLine("                    sbyte result = nuget_unwrap_byte(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(byte))")
  appendLine("                {")
  appendLine("                    byte result = nuget_unwrap_ubyte(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(short))")
  appendLine("                {")
  appendLine("                    short result = nuget_unwrap_short(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(ushort))")
  appendLine("                {")
  appendLine("                    ushort result = nuget_unwrap_ushort(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(int))")
  appendLine("                {")
  appendLine("                    int result = Native_unwrap_int(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(uint))")
  appendLine("                {")
  appendLine("                    uint result = nuget_unwrap_uint(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(long))")
  appendLine("                {")
  appendLine("                    long result = Native_unwrap_long(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(ulong))")
  appendLine("                {")
  appendLine("                    ulong result = nuget_unwrap_ulong(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(float))")
  appendLine("                {")
  appendLine("                    float result = Native_unwrap_float(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(double))")
  appendLine("                {")
  appendLine("                    double result = Native_unwrap_double(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("                if (nullableUnderlying == typeof(bool))")
  appendLine("                {")
  appendLine("                    bool result = Native_unwrap_bool(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  // ADR-098 part B: the one kind FromHandle never dispatched, which made a `List<char>` return
  // fall through to Materialize<char> and throw NotSupportedException.
  appendLine("                if (nullableUnderlying == typeof(char))")
  appendLine("                {")
  appendLine("                    char result = nuget_unwrap_char(handle);")
  appendLine("                    Native_dispose(handle);")
  appendLine("                    return (T)(object)result;")
  appendLine("                }")
  appendLine("            }")
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
  appendLine("            if (typeof(T) == typeof(char))")
  appendLine("            {")
  appendLine("                char result = nuget_unwrap_char(handle);")
  appendLine("                Native_dispose(handle);")
  appendLine("                return (T)(object)result;")
  appendLine("            }")
  appendLine("            return Materialize<T>(handle);")
  appendLine("        }")
  appendLine()
  appendLine("        public static void Dispose(IntPtr handle) => Native_dispose(handle);")
  appendLine()
  // ADR-073: the boxing switch shared by CreateList/CreateSet/CreateMap, so a future component
  // type is added in exactly one place.
  // ADR-099: `owned` reports whether this call MINTED the box. A freshly wrapped primitive/string
  // and a nested collection's handle are the caller's to dispose the instant `Add`/`Put` has
  // dereferenced them; an INugetHandle's `Handle` is the C# wrapper's own live handle and disposing
  // it would be a use-after-free on the next use of that wrapper.
  appendLine("        internal static IntPtr Wrap<T>(T value, out bool owned)")
  appendLine("        {")
  // ADR-083: the null pointer is the null component, for every component kind. The dispatch below
  // is on the *underlying* type because typeof(int?) != typeof(int), so without the normalization
  // a T of `int?` would miss every branch and fall into the reflective _handle fallback.
  appendLine("            owned = false;")
  appendLine("            if (value == null) return IntPtr.Zero;")
  appendLine("            var type = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);")
  // ADR-099: a nested collection component arrives already boxed -- its handle IS the box, minted
  // by this call site's own projection, so this factory owns it.
  appendLine("            owned = true;")
  appendLine("            if (type == typeof(IntPtr)) return (IntPtr)(object)value!;")
  appendLine("            if (type == typeof(string)) return nuget_wrap_string((string)(object)value!);")
  appendLine("            if (type == typeof(int)) return nuget_wrap_int((int)(object)value!);")
  appendLine("            if (type == typeof(long)) return nuget_wrap_long((long)(object)value!);")
  appendLine("            if (type == typeof(float)) return nuget_wrap_float((float)(object)value!);")
  appendLine("            if (type == typeof(double)) return nuget_wrap_double((double)(object)value!);")
  appendLine("            if (type == typeof(bool)) return nuget_wrap_bool((bool)(object)value!);")
  // ADR-098: the six narrow kinds FromHandle<T> has always read back, now writable too, plus
  // (part B) char. The dispatch variable is the *underlying* type, so `short?` rides the same
  // branch as `short` with no extra work.
  appendLine("            if (type == typeof(sbyte)) return nuget_wrap_byte((sbyte)(object)value!);")
  appendLine("            if (type == typeof(byte)) return nuget_wrap_ubyte((byte)(object)value!);")
  appendLine("            if (type == typeof(short)) return nuget_wrap_short((short)(object)value!);")
  appendLine("            if (type == typeof(ushort)) return nuget_wrap_ushort((ushort)(object)value!);")
  appendLine("            if (type == typeof(uint)) return nuget_wrap_uint((uint)(object)value!);")
  appendLine("            if (type == typeof(ulong)) return nuget_wrap_ulong((ulong)(object)value!);")
  appendLine("            if (type == typeof(char)) return nuget_wrap_char((char)(object)value!);")
  // ADR-094: every Kotlin-backed wrapper implements INugetHandle explicitly, so the handle comes
  // out of a type test instead of a private-field read.
  appendLine("            owned = false;")
  appendLine("            if (value is INugetHandle wrapper) return wrapper.Handle;")
  appendLine("            throw new NotSupportedException($\"Cannot pass {typeof(T).Name} to a Kotlin collection\");")
  appendLine("        }")
  appendLine()
  appendLine("        public static IntPtr CreateList<T>(IEnumerable<T> values)")
  appendLine("        {")
  appendLine("            IntPtr listHandle = NugetListNative.Create();")
  // A `catch`, not a `finally`: the happy path hands the live handle to the caller. An element's
  // own Wrap/enumeration can throw halfway through, and the partially built Kotlin collection is
  // a rooted StableRef nobody else has a reference to. `throw;` rethrows the original, preserving
  // the stack -- callers (and the ROADMAP:130 tests) see the consumer's exception, not a wrapper.
  appendCollectionFactoryGuard(
    "NugetListNative",
    "listHandle",
    elementLoop("NugetListNative", "Add(listHandle, element)"),
  )
  appendLine("            return listHandle;")
  appendLine("        }")
  appendLine()
  // Gated on helper.includesSet: NugetSetNative is only emitted when the tracker actually saw a
  // Set/MutableSet collection somewhere in the file, so this body must not exist unconditionally.
  if (helper.includesSet) {
    appendLine("        public static IntPtr CreateSet<T>(IEnumerable<T> values)")
    appendLine("        {")
    appendLine("            IntPtr setHandle = NugetSetNative.Create();")
    appendCollectionFactoryGuard(
      "NugetSetNative",
      "setHandle",
      elementLoop("NugetSetNative", "Add(setHandle, element)"),
    )
    appendLine("            return setHandle;")
    appendLine("        }")
    appendLine()
  }
  // Gated on helper.includesMap, mirroring includesSet above.
  if (helper.includesMap) {
    appendLine("        public static IntPtr CreateMap<TKey, TValue>(IEnumerable<KeyValuePair<TKey, TValue>> values)")
    appendLine("        {")
    appendLine("            IntPtr mapHandle = NugetMapNative.Create();")
    appendCollectionFactoryGuard(
      "NugetMapNative",
      "mapHandle",
      listOf(
        "foreach (var pair in values)",
        "{",
        "    IntPtr key = IntPtr.Zero;",
        "    bool keyOwned = false;",
        "    IntPtr value = IntPtr.Zero;",
        "    bool valueOwned = false;",
        "    try",
        "    {",
        "        key = Wrap(pair.Key, out keyOwned);",
        "        value = Wrap(pair.Value, out valueOwned);",
        "        NugetMapNative.Put(mapHandle, key, value);",
        "    }",
        "    finally",
        "    {",
        // ADR-099: both boxes were minted by this loop iteration, and nuget_map_put has already
        // stored the dereferenced objects into a map the outer StableRef roots.
        "        if (keyOwned) NugetMapNative.Dispose(key);",
        "        if (valueOwned) NugetMapNative.Dispose(value);",
        "    }",
        "}",
      ),
    )
    appendLine("            return mapHandle;")
    appendLine("        }")
    appendLine()
  }
  // ADR-099: the read side of a NESTED component. The shipped materialization is inlined codegen
  // with fixed local names (`listHandle`, `count`, `result`, `i`) that cannot nest, so an inner
  // level goes through these helpers instead. The `finally` is what keeps every inner level free of
  // ROADMAP.md:142's "result handle leaks if materialization throws mid-loop" shape; the handle was
  // minted by the Get/KeyAt/ValueAt that produced it, so this helper owns it.
  if (helper.includesList) {
    appendLine("        public static List<T> ReadList<T>(IntPtr handle, Func<IntPtr, T> read)")
    appendLine("        {")
    appendLine("            try")
    appendLine("            {")
    appendLine("                int count = NugetListNative.Count(handle);")
    appendLine("                var result = new List<T>(count);")
    appendLine("                for (int i = 0; i < count; i++) result.Add(read(NugetListNative.Get(handle, i)));")
    appendLine("                return result;")
    appendLine("            }")
    appendLine("            finally { NugetListNative.Dispose(handle); }")
    appendLine("        }")
    appendLine()
  }
  if (helper.includesSet) {
    appendLine("        public static HashSet<T> ReadSet<T>(IntPtr handle, Func<IntPtr, T> read)")
    appendLine("        {")
    appendLine("            try")
    appendLine("            {")
    appendLine("                int count = NugetSetNative.Count(handle);")
    appendLine("                var result = new HashSet<T>(count);")
    appendLine("                for (int i = 0; i < count; i++) result.Add(read(NugetSetNative.ElementAt(handle, i)));")
    appendLine("                return result;")
    appendLine("            }")
    appendLine("            finally { NugetSetNative.Dispose(handle); }")
    appendLine("        }")
    appendLine()
  }
  if (helper.includesMap) {
    appendLine("        public static Dictionary<TKey, TValue> ReadMap<TKey, TValue>(")
    appendLine("            IntPtr handle, Func<IntPtr, TKey> readKey, Func<IntPtr, TValue> readValue) where TKey : notnull")
    appendLine("        {")
    appendLine("            try")
    appendLine("            {")
    appendLine("                int count = NugetMapNative.Count(handle);")
    appendLine("                var result = new Dictionary<TKey, TValue>(count);")
    appendLine("                for (int i = 0; i < count; i++)")
    appendLine("                {")
    appendLine("                    result[readKey(NugetMapNative.KeyAt(handle, i))] = readValue(NugetMapNative.ValueAt(handle, i));")
    appendLine("                }")
    appendLine("                return result;")
    appendLine("            }")
    appendLine("            finally { NugetMapNative.Dispose(handle); }")
    appendLine("        }")
    appendLine()
  }
  // ADR-040 sub-decision B: the one shared reflective helper for an interface-typed parameter
  // (e.g. `Cat.Befriend(IPet pet)`). The static parameter type is the projected interface, which
  // does not carry `_handle` (only the generated backing wrapper class does), so extraction is
  // reflective here rather than a direct field read.
  //
  // ADR-084 facet 3: no `_handle` means the value is C#-implemented, and the bridge factory turns
  // it into a Kotlin-side `object : Foo` handle so the ordinary handle path below it is reused
  // unchanged. Without a bridge layer in this module there is nothing to fall back to, so the
  // ADR-040 boundary exception stands.
  appendLine("        internal static IntPtr HandleOf(object value)")
  appendLine("        {")
  appendLine("            if (value is INugetHandle wrapper) return wrapper.Handle;")
  if (helper.includesBridge) {
    appendLine("            return NugetBridge.HandleFor(value);")
  } else {
    appendLine("            throw new NotSupportedException(")
    appendLine("                $\"{value.GetType().Name} is not a Kotlin-backed object; passing a C#-implemented interface is not supported yet.\");")
  }
  appendLine("        }")
  appendLine()
  // ADR-084 stage 3: the same extraction, reporting whether it *minted* a transfer handle. A
  // Kotlin-backed wrapper's `_handle` belongs to that wrapper and must never be disposed here; a
  // bridge handle is a one-crossing transfer the call site frees once the native call returns.
  appendLine("        internal static IntPtr HandleOf(object value, out bool owned)")
  appendLine("        {")
  appendLine("            if (value is INugetHandle wrapper)")
  appendLine("            {")
  appendLine("                owned = false;")
  appendLine("                return wrapper.Handle;")
  appendLine("            }")
  appendLine("            owned = true;")
  appendLine("            return HandleOf(value);")
  appendLine("        }")
  appendLine()
  appendLine("        internal static IntPtr HandleOfOrZero(object? value, out bool owned)")
  appendLine("        {")
  appendLine("            if (value == null)")
  appendLine("            {")
  appendLine("                owned = false;")
  appendLine("                return IntPtr.Zero;")
  appendLine("            }")
  appendLine("            return HandleOf(value, out owned);")
  appendLine("        }")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_csharp_token\")]")
  appendLine("        private static extern IntPtr Native_csharp_token(IntPtr handle);")
  appendLine()
  // ADR-084 facet 5: ask the handle whether a C# object is already behind it before wrapping it in
  // a second bridge. A Kotlin-backed object answers IntPtr.Zero and the caller wraps as before.
  appendLine("        internal static bool TryResolveCSharp<T>(IntPtr handle, out T original) where T : class")
  appendLine("        {")
  appendLine("            IntPtr token = Native_csharp_token(handle);")
  appendLine("            if (token == IntPtr.Zero)")
  appendLine("            {")
  appendLine("                original = null!;")
  appendLine("                return false;")
  appendLine("            }")
  // The returned StableRef was minted for this crossing only; the original object is reached
  // through the token, so the fresh handle would otherwise leak.
  appendLine("            Native_dispose(handle);")
  appendLine("            original = (T)GCHandle.FromIntPtr(token).Target!;")
  appendLine("            return true;")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

/**
 * The `try { fill } catch { Dispose(handle); throw; }` guard shared by CreateList/CreateSet/
 * CreateMap. [native] is the per-kind static class the ADR-073 discipline requires: all three
 * `Dispose` members bind to the same `nuget_dispose` entry point, but a file that only ever saw a
 * Map never emits NugetListNative at all, so naming the wrong one is a CS0103.
 */
private fun StringBuilder.appendCollectionFactoryGuard(
  native: String,
  handle: String,
  fill: List<String>,
) {
  appendLine("            try")
  appendLine("            {")
  fill.forEach { line -> appendLine("                $line") }
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                $native.Dispose($handle);")
  appendLine("                throw;")
  appendLine("            }")
}

/**
 * ADR-099: the per-element fill shared by CreateList and CreateSet. `Wrap` reports whether it
 * minted the box; when it did, this loop disposes it the instant `Add` returns, because
 * `nuget_list_add`/`nuget_set_add` store the *dereferenced object* into a container the outer
 * StableRef already roots (verified by execution, ADR-099 spike 1). So at most one inner handle is
 * ever alive, and it is inside a `finally`.
 */
private fun elementLoop(native: String, add: String): List<String> = listOf(
  "foreach (T value in values)",
  "{",
  "    IntPtr element = IntPtr.Zero;",
  "    bool owned = false;",
  "    try",
  "    {",
  "        element = Wrap(value, out owned);",
  "        $native.$add;",
  "    }",
  "    finally { if (owned) $native.Dispose(element); }",
  "}",
)

internal fun StringBuilder.renderListHelper(helper: CirListHelper) {
  appendLine("    internal static class NugetListNative")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_list_count\")]")
  appendLine("        internal static extern int Count(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_list_get\")]")
  appendLine("        internal static extern IntPtr Get(IntPtr handle, int index);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_list_create\")]")
  appendLine("        internal static extern IntPtr Create();")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_list_add\")]")
  appendLine("        internal static extern void Add(IntPtr handle, IntPtr element);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
  appendLine("        internal static extern void Dispose(IntPtr handle);")
  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderMapHelper(helper: CirMapHelper) {
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
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_map_create\")]")
  appendLine("        internal static extern IntPtr Create();")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_map_put\")]")
  appendLine("        internal static extern void Put(IntPtr handle, IntPtr key, IntPtr value);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
  appendLine("        internal static extern void Dispose(IntPtr handle);")
  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderSetHelper(helper: CirSetHelper) {
  appendLine("    internal static class NugetSetNative")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_set_count\")]")
  appendLine("        internal static extern int Count(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_set_element_at\")]")
  appendLine("        internal static extern IntPtr ElementAt(IntPtr handle, int index);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_set_create\")]")
  appendLine("        internal static extern IntPtr Create();")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_set_add\")]")
  appendLine("        internal static extern void Add(IntPtr handle, IntPtr element);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_dispose\")]")
  appendLine("        internal static extern void Dispose(IntPtr handle);")
  appendLine("    }")
  appendLine()
}


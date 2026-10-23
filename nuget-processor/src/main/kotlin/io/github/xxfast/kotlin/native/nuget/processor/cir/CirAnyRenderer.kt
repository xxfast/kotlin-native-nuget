package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-237: the `object` wire inside `NugetMarshal`. A Kotlin `Any` box is read by its runtime kind
 * (`nuget_box_kind`): a primitive, `Char` or `String` reads as its boxed C# value, a collection as
 * a read-only collection of `object?`, a C# object as itself, and any other Kotlin object as the
 * generated wrapper registered under its Kotlin qualified name in `KotlinTypes`, or as a
 * [KotlinObject] that hands the same Kotlin object back. A C# `object` is written by its runtime
 * type, the reverse of the same dispatch; a managed object with no Kotlin counterpart crosses as a
 * `GCHandle` the Kotlin box frees when it is collected. No reflection and no `MakeGenericType`, so
 * it holds under trimming and NativeAOT.
 */
internal fun StringBuilder.renderAnyWire(helper: CirMarshalHelper) {
  val library: String = helper.libraryName
  appendLine("        [DllImport(\"$library\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_box_kind\")]")
  appendLine("        private static extern int Native_box_kind(IntPtr handle);")
  appendLine()
  appendLine("        [DllImport(\"$library\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_box_type_name\")]")
  appendLine("        private static extern unsafe int Native_box_type_name(IntPtr handle, byte* buffer, int capacity);")
  appendLine()
  appendLine("        [DllImport(\"$library\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_box_managed\")]")
  appendLine("        private static extern IntPtr Native_box_managed(IntPtr token, IntPtr release);")
  appendLine()
  // One statically written line per Kotlin class with a C# wrapper, keyed by the name the Kotlin
  // box reports. A generic class reads at `object?` for every argument.
  appendLine(
    "        internal static readonly System.Collections.Generic.Dictionary<string, " +
      "Func<NugetKotlinHandle, object>> KotlinTypes =",
  )
  appendLine(
    "            new System.Collections.Generic.Dictionary<string, " +
      "Func<NugetKotlinHandle, object>>",
  )
  appendLine("        {")
  helper.factories
    .filter { entry -> entry.kotlinName != null }
    .distinctBy { entry -> entry.kotlinName }
    .forEach { entry ->
      appendLine("            [\"${entry.kotlinName}\"] = static handle => ${entry.construct()},")
    }
  appendLine("        };")
  appendLine()
  appendLine("        private static unsafe string? KotlinTypeName(IntPtr handle)")
  appendLine("        {")
  appendLine("            Span<byte> buffer = stackalloc byte[256];")
  appendLine("            int length;")
  appendLine("            fixed (byte* pointer = buffer) length = Native_box_type_name(handle, pointer, buffer.Length);")
  appendLine("            if (length < 0) return null;")
  appendLine("            if (length <= buffer.Length) return System.Text.Encoding.UTF8.GetString(buffer.Slice(0, length));")
  appendLine("            byte[] larger = new byte[length];")
  appendLine("            fixed (byte* pointer = larger) Native_box_type_name(handle, pointer, larger.Length);")
  appendLine("            return System.Text.Encoding.UTF8.GetString(larger);")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>Reads a Kotlin <c>Any</c> box as its C# value, releasing the handle.</summary>")
  appendLine("        internal static object? ReadAny(IntPtr handle)")
  appendLine("        {")
  appendLine("            if (handle == IntPtr.Zero) return null;")
  appendLine("            switch (Native_box_kind(handle))")
  appendLine("            {")
  listOf(
    "string", "bool", "sbyte", "byte", "short", "ushort", "int", "uint", "long", "ulong", "float",
    "double", "char",
  ).forEachIndexed { index, type ->
    appendLine("                case ${index + 1}: return FromHandle<$type>(handle);")
  }
  if (helper.includesList) {
    appendLine("                case 14: return ReadList<object?>(handle, ReadAny).AsReadOnly();")
  }
  if (helper.includesSet) appendLine("                case 15: return ReadSet<object?>(handle, ReadAny);")
  if (helper.includesMap) {
    appendLine("                case 16: return ReadMap<object, object?>(handle, static key => ReadAny(key)!, ReadAny);")
  }
  appendLine("                case 17:")
  appendLine("                    if (TryResolveCSharpObject(handle, out object original)) return original;")
  appendLine("                    break;")
  if (helper.includesBytes) appendLine("                case 18: return ReadBytes(handle);")
  appendLine("            }")
  appendLine("            string? name = KotlinTypeName(handle);")
  appendLine("            if (name != null && KotlinTypes.TryGetValue(name, out Func<NugetKotlinHandle, object>? factory))")
  appendLine("            {")
  appendLine("                var owned = new NugetKotlinHandle(handle);")
  appendLine("                try { return factory(owned); }")
  appendLine("                catch { owned.Dispose(); throw; }")
  appendLine("            }")
  appendLine("            return new KotlinObject(handle);")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>Boxes a C# <c>object</c> for Kotlin by its runtime type; <paramref name=\"owned\"/> reports a minted box.</summary>")
  appendLine("        internal static IntPtr WrapAny(object? value, out bool owned)")
  appendLine("        {")
  appendLine("            owned = false;")
  appendLine("            if (value == null) return IntPtr.Zero;")
  appendLine("            if (value is INugetHandle wrapper) return wrapper.Handle;")
  appendLine("            IntPtr minted = value switch")
  appendLine("            {")
  appendLine("                string text => nuget_wrap_string(text),")
  appendLine("                bool flag => nuget_wrap_bool(flag),")
  appendLine("                sbyte number => nuget_wrap_byte(number),")
  appendLine("                byte number => nuget_wrap_ubyte(number),")
  appendLine("                short number => nuget_wrap_short(number),")
  appendLine("                ushort number => nuget_wrap_ushort(number),")
  appendLine("                int number => nuget_wrap_int(number),")
  appendLine("                uint number => nuget_wrap_uint(number),")
  appendLine("                long number => nuget_wrap_long(number),")
  appendLine("                ulong number => nuget_wrap_ulong(number),")
  appendLine("                float number => nuget_wrap_float(number),")
  appendLine("                double number => nuget_wrap_double(number),")
  appendLine("                char letter => nuget_wrap_char(letter),")
  appendLine("                _ => IntPtr.Zero,")
  appendLine("            };")
  appendLine("            if (minted == IntPtr.Zero) minted = WrapAnyReference(value);")
  appendLine("            owned = true;")
  appendLine("            return minted;")
  appendLine("        }")
  appendLine()
  appendLine("        private static IntPtr WrapAnyReference(object value)")
  appendLine("        {")
  // A value class or an enum, keyed on its runtime type (ADR-171, ADR-094).
  appendLine("            if (Boxers.TryGetValue(value.GetType(), out Func<object, IntPtr>? box)) return box(value);")
  if (helper.includesBytes) appendLine("            if (value is byte[] bytes) return CreateBytes(bytes);")
  if (helper.includesMap) {
    appendLine("            if (value is System.Collections.IDictionary map) return CreateMap(AnyEntries(map));")
  }
  if (helper.includesSet) {
    appendLine("            if (value is IReadOnlySet<object?> set) return CreateSet(set);")
    appendLine("            if (value is ISet<object?> mutableSet) return CreateSet(mutableSet);")
  }
  if (helper.includesList) {
    appendLine("            if (value is System.Collections.IEnumerable items) return CreateList(AnyItems(items));")
  }
  // A C# implementation of a bridged interface crosses as its bridge; `HandleFor` boxes any other
  // object at `typeof(object)` through `BoxManaged`.
  if (helper.includesBridge) {
    appendLine("            if (value is INugetBridgeable<object> bridgeable) return bridgeable.MintBridge();")
    appendLine("            return NugetBridge.HandleFor(value, typeof(object));")
  } else {
    appendLine("            return BoxManaged(value);")
  }
  appendLine("        }")
  appendLine()
  if (helper.includesMap) {
    appendLine("        private static IEnumerable<KeyValuePair<object, object?>> AnyEntries(System.Collections.IDictionary map)")
    appendLine("        {")
    appendLine("            foreach (System.Collections.DictionaryEntry entry in map)")
    appendLine("                yield return new KeyValuePair<object, object?>(entry.Key, entry.Value);")
    appendLine("        }")
    appendLine()
  }
  if (helper.includesList) {
    appendLine("        private static IEnumerable<object?> AnyItems(System.Collections.IEnumerable items)")
    appendLine("        {")
    appendLine("            foreach (object? item in items) yield return item;")
    appendLine("        }")
    appendLine()
  }
  appendLine("        /// <summary>Boxes a managed object with no Kotlin counterpart as a <c>GCHandle</c> Kotlin frees.</summary>")
  appendLine("        internal static IntPtr BoxManaged(object value)")
  appendLine("        {")
  appendLine("            GCHandle token = GCHandle.Alloc(value);")
  appendLine("            try { return Native_box_managed(GCHandle.ToIntPtr(token), FreeManagedPtr); }")
  appendLine("            catch { token.Free(); throw; }")
  appendLine("        }")
  appendLine()
  appendLine("        [UnmanagedCallersOnly(CallConvs = new[] { typeof(global::System.Runtime.CompilerServices.CallConvCdecl) })]")
  appendLine("        private static void FreeManaged(IntPtr token)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine("                GCHandle.FromIntPtr(token).Free();")
  appendLine("            }")
  appendLine("            catch (Exception ex)")
  appendLine("            {")
  appendLine("                Environment.FailFast(\"nuget: unhandled exception freeing a managed object box\", ex);")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  appendLine("        private static unsafe IntPtr FreeManagedPtr => (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)&FreeManaged;")
  appendLine()
}

/**
 * ADR-237: a Kotlin object with no C# wrapper, read through `object`. Opaque: it hands the same
 * Kotlin object back when C# passes it to Kotlin, and releases its handle on [IDisposable.Dispose]
 * or finalization.
 */
internal fun StringBuilder.renderKotlinObject() {
  appendLine("    /// <summary>A Kotlin object with no C# wrapper, read through <c>object</c>. Passing it back to Kotlin hands over the same object.</summary>")
  appendLine("    public sealed class KotlinObject : IDisposable, INugetHandle")
  appendLine("    {")
  appendLine("        private NugetKotlinHandle _handle;")
  appendLine()
  appendLine("        internal KotlinObject(IntPtr handle) => _handle = new NugetKotlinHandle(handle);")
  appendLine()
  appendLine("        IntPtr INugetHandle.Handle => _handle.DangerousGetHandle();")
  appendLine()
  appendLine("        public void Dispose()")
  appendLine("        {")
  appendLine("            NugetKotlinHandle handle = Interlocked.Exchange(ref _handle, NugetKotlinHandle.Null);")
  appendLine("            if (!handle.IsInvalid) handle.Dispose();")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

/** The expression a `Factories` or `KotlinTypes` line builds over its `handle` parameter. */
internal fun CirFactoryEntry.construct(): String = when {
  constructExpression != null -> constructExpression
  viaFromHandle -> "global::$constructTypeName.FromHandle(handle)"
  viaNugetUnbox -> "global::$constructTypeName.NugetUnbox(handle)"
  viaEnumOrdinal -> "(global::$constructTypeName)UnwrapEnumOrdinal(handle)"
  else -> "new global::$constructTypeName(handle, out _)"
}

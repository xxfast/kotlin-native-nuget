package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-009's hierarchy, declared the way Kotlin declares it (issue #54): a subclass nested inside
 * its sealed base stays nested in C# (`Shape.Circle`), a subclass declared *beside* its base is
 * declared beside it here too, at namespace level (`public sealed class Label : Shape`, emitted
 * after the base's closing brace so the base type it names is already complete).
 *
 * Both spellings come off the same subclass block, re-indented for the sibling, so the
 * discriminator order, the export prefixes and the member bodies stay identical between the two
 * positions. The sealed route is the only thing that declares either: the plain-class route no
 * longer collects a sibling subclass at all, which is what made one Kotlin type into two unrelated
 * C# types.
 */
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

  for (subclass in sealed.subclasses.filter { it.isNested }) {
    append(sealedSubclassBlock(sealed, subclass))
  }

  appendLine("        [DllImport(\"${sealed.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${sealed.nativePrefix}_get_type\")]")
  appendLine("        private static extern int Native_GetType(IntPtr handle);")
  appendLine()
  appendLine("        internal static ${sealed.name} FromHandle(IntPtr handle)")
  appendLine("        {")
  appendLine("            return Native_GetType(handle) switch")
  appendLine("            {")

  // Every subclass, nested or sibling, in the Kotlin discriminator's own order. A sibling resolves
  // bare from inside the base because it lives in the same namespace.
  for ((index, subclass) in sealed.subclasses.withIndex()) {
    appendLine("                $index => new ${subclass.name}(handle),")
  }

  appendLine("                _ => throw new InvalidOperationException(\"Unknown sealed class type\")")
  appendLine("            };")
  appendLine("        }")
  appendLine()
  appendLine("        public abstract void Dispose();")
  appendLine("    }")

  for (subclass in sealed.subclasses.filterNot { it.isNested }) {
    appendLine()
    append(sealedSubclassBlock(sealed, subclass).outdentToNamespaceLevel())
  }
}

/** One subclass, rendered at the nesting depth of a class declared inside its sealed base. */
private fun sealedSubclassBlock(
  sealed: CirSealedClass,
  subclass: CirSealedSubclass,
): String = buildString {
  appendLine("        public sealed class ${subclass.name} : ${sealed.name}")
  appendLine("        {")
  appendLine("            internal ${subclass.name}(IntPtr handle) : base(handle)")
  appendLine("            {")
  appendLine("            }")
  appendLine()

  for (prop in subclass.properties) {
    // ADR-111: the externs come off the same `propertyNativeImports` rule every ordinary class
    // property uses, so the error slot, the `_value` fan-out and `[return: MarshalAs]` on a `bool`
    // are decided in one place. A lambda property keeps its raw legacy import.
    if (prop.usesLegacyNativeImport()) {
      appendLine("            [DllImport(\"${sealed.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${subclass.nativePrefix}_get_${prop.nativeName}\")]")
      appendLine("            private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle, out IntPtr error);")
      appendLine()
    } else {
      append(
        buildString {
          propertyNativeImports(sealed.libraryName, subclass.nativePrefix, prop)
            .forEach { nativeImport -> renderDllImport(nativeImport) }
        }.indentNestedBody(),
      )
    }
    renderSealedSubclassProperty(prop)
    appendLine()
  }

  // Issue #54: a `data object` gets the same generated members a `data class` gets, and Kotlin
  // exports all three for it. Binding them here is what makes two wrappers over the one Kotlin
  // singleton compare equal: every read mints a fresh wrapper, so reference equality never held,
  // and a constant `ToString()` literal could disagree with Kotlin's own.
  if (subclass.isDataClass) {
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

/**
 * Lifts a subclass block out of its base's braces: one nesting level shallower, which is exactly
 * the depth every other namespace-level declaration renders at, so the member bodies baked at the
 * ordinary-class indentation land right again.
 */
private fun String.outdentToNamespaceLevel(): String =
  lines().joinToString("\n") { line -> line.removePrefix("    ") }

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
 * Renders one sealed-subclass property: `renderProperty`'s four shapes at one nesting level
 * deeper. A body that is a statement block must sit inside a `get { ... }` / `set { ... }` block;
 * emitting one as `=> block;` is what made the generated `Interop.cs` unparseable
 * (CS1002/CS1519/CS8124, issue #39).
 *
 * Bodies are shared verbatim with the ordinary-class path, so they are baked at that path's
 * indentation; a sealed subclass nests one level deeper, hence the +4 re-indent here rather than a
 * forked body in the translator. ADR-111: a `var` on a sealed subclass reaches the setter arm,
 * which the legacy route never had (it always passed `setter = null`).
 */
private fun StringBuilder.renderSealedSubclassProperty(prop: CirProperty) {
  val isMultiLineGetter: Boolean = prop.getter.contains('\n')
  val isMultiLineSetter: Boolean = prop.setter?.contains('\n') == true
  val setter: String? = prop.setter
  if (setter == null && !isMultiLineGetter) {
    appendLine("            public ${prop.type} ${prop.name} => ${prop.getter};")
    return
  }

  appendLine("            public ${prop.type} ${prop.name}")
  appendLine("            {")
  if (isMultiLineGetter) {
    appendLine("                get")
    appendLine("                {${prop.getter.indentNestedBody()}")
    appendLine("                }")
  } else {
    appendLine("                get => ${prop.getter};")
  }
  if (setter != null && isMultiLineSetter) {
    appendLine("                set")
    appendLine("                {${setter.indentNestedBody()}")
    appendLine("                }")
  } else if (setter != null) {
    appendLine("                set => $setter;")
  }
  appendLine("            }")
}

/** Shifts an ordinary-class member body one nesting level deeper, leaving blank lines untouched. */
private fun String.indentNestedBody(): String =
  lines().joinToString("\n") { line -> if (line.isBlank()) line else "    $line" }


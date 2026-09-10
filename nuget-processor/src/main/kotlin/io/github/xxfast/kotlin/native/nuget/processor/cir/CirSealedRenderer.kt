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

  // ADR-111/ADR-116 amendment (2026-09-11): the base's own declared members, ahead of the arms so
  // a reader meets the polymorphic surface before the discrimination. Externs and bodies come off
  // the same `propertyNativeImports` / `renderProperty` / `methodNativeImport` / `renderMethod`
  // rules an ordinary class uses, and all four bake the depth a class member sits at, which is
  // exactly the depth the base's members sit at here -- no re-indent, unlike the arm blocks.
  for (property in sealed.properties) {
    propertyNativeImports(sealed.libraryName, sealed.nativePrefix, property)
      .forEach { nativeImport -> renderDllImport(nativeImport) }
    renderProperty(property)
    appendLine()
  }

  for (method in sealed.methods) {
    renderDllImport(methodNativeImport(sealed.libraryName, sealed.nativePrefix, method))
    renderMethod(method, sealed.name)
  }

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
  // ADR-118: an arm that declares a `suspend fun` owns its own coroutine scope and therefore its
  // own async disposal. The base stays `: IDisposable, INugetHandle` -- its `Native_Dispose` is
  // per arm, so it has no scope to drain, and putting `IAsyncDisposable` there would advertise
  // `DisposeAsync` on arms that never suspend.
  val asyncDisposable: String = if (subclass.hasSuspendMethods) ", IAsyncDisposable" else ""
  // ADR-009 amendment (2026-09-11): an `open` arm drops `sealed`, so a Kotlin subclass of it (an
  // ordinary class, with the arm as its base) compiles and the arm's `open` members can be
  // `virtual`. A final arm keeps its shipped `public sealed class` spelling byte for byte.
  val sealedModifier: String = if (subclass.isOpen) "" else "sealed "
  // ADR-111/ADR-116 amendment (2026-09-11): the base's own extern names. A nested arm sits inside
  // the base's braces, so those private statics are accessible to it, and an arm that overrides a
  // base member mints an extern of exactly the same name: CS0108 unless it says `new`. A sibling
  // arm sees none of them, where `new` would be CS0109 instead, so both are keyed off `isNested`.
  val baseExternNames: Set<String> =
    if (subclass.isNested) sealed.ordinaryNativeImports().map { it.name }.toSet() else emptySet()
  appendLine(
    "        public ${sealedModifier}class ${subclass.name} : ${sealed.name}$asyncDisposable"
  )
  appendLine("        {")
  if (subclass.hasSuspendMethods) {
    append(buildString { renderScopeHandleField() }.indentNestedBody())
    appendLine()
  }
  appendLine("            internal ${subclass.name}(IntPtr handle) : base(handle)")
  appendLine("            {")
  appendLine("            }")
  appendLine()
  if (subclass.hasSuspendMethods) {
    append(buildString { renderGetOrCreateScope() }.indentNestedBody())
    appendLine()
  }

  for (prop in subclass.properties) {
    // ADR-124: a flow property's externs are the `_collect` / `_value` / `_has_value` /
    // `_set_value` block, off the same `renderFlowPropertyNativeImports` an ordinary class calls.
    // This arm MUST come before the one below: `usesLegacyNativeImport()` is true for a flow
    // property as well as a lambda one, so without it a flow property fell into the lambda arm and
    // rendered a `Native_Get_x(IntPtr, out IntPtr)` import that no Kotlin export backs.
    if (prop.isFlow) {
      append(
        buildString {
          renderFlowPropertyNativeImports(sealed.libraryName, subclass.nativePrefix, prop)
        }.indentNestedBody(),
      )
      renderSealedSubclassProperty(prop)
      appendLine()
      continue
    }
    // ADR-111: the externs come off the same `propertyNativeImports` rule every ordinary class
    // property uses, so the error slot, the `_value` fan-out and `[return: MarshalAs]` on a `bool`
    // are decided in one place. A lambda property keeps its raw legacy import.
    if (prop.usesLegacyNativeImport()) {
      require(!prop.isFlow) { "A Flow property takes the flow native-import route above" }
      appendLine("            [DllImport(\"${sealed.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${subclass.nativePrefix}_get_${prop.nativeName}\")]")
      appendLine("            private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle, out IntPtr error);")
      appendLine()
    } else {
      append(
        buildString {
          propertyNativeImports(sealed.libraryName, subclass.nativePrefix, prop)
            .forEach { nativeImport -> renderDllImport(nativeImport.hiding(baseExternNames)) }
        }.indentNestedBody(),
      )
    }
    renderSealedSubclassProperty(prop)
    appendLine()
  }

  // ADR-116: the arm's own declared methods, extern and body both off the ordinary-class rules
  // (`methodNativeImport`, `renderMethod`). Both are baked at the ordinary-class depth, so the
  // whole block takes the same +4 re-indent the property arm takes.
  subclass.methods.forEach { method ->
    append(
      buildString {
        renderDllImport(
          methodNativeImport(sealed.libraryName, subclass.nativePrefix, method)
            .hiding(baseExternNames),
        )
        // `renderMethod` already closes with its own blank separator line, unlike the property
        // renderer, so this loop adds none.
        renderMethod(method, subclass.name)
      }.indentNestedBody(),
    )
  }

  // ADR-118: the arm's declared `suspend` members. `renderMember` dispatches the pair the same way
  // an ordinary class's `companionMembers` are dispatched -- the private `[DllImport]` and the
  // `async` body -- both baked at the ordinary-class depth, so the whole block takes the same +4
  // re-indent the property and method arms take.
  subclass.asyncMembers.forEach { member ->
    append(buildString { renderMember(member, subclass.name) }.indentNestedBody())
  }

  // ADR-124: the arm's Flow/StateFlow-returning methods, dispatched by the same `renderMember` and
  // re-indented the same way. A flow member's private `[DllImport]` set rides the pair, so nothing
  // here composes an entry point of its own.
  subclass.flowMembers.forEach { member ->
    append(buildString { renderMember(member, subclass.name) }.indentNestedBody())
  }

  // Issue #54: a `data object` gets the same generated members a `data class` gets, and Kotlin
  // exports all three for it. Binding them here is what makes two wrappers over the one Kotlin
  // singleton compare equal: every read mints a fresh wrapper, so reference equality never held,
  // and a constant `ToString()` literal could disagree with Kotlin's own.
  if (subclass.isDataClass) {
    renderSealedSubclassDataMethods(sealed.libraryName, subclass.nativePrefix, sealed.name, subclass.name)
  }

  if (subclass.hasSuspendMethods) {
    // ADR-118: a suspending arm takes the ordinary class's dispose rule wholesale -- cancel and
    // dispose the scope before `Native_Dispose`, plus the `DisposeAsync` drain. Only a suspending
    // arm does: `renderDispose`'s `Interlocked.Exchange` body is not textually what the arms have
    // shipped, so a non-suspending arm keeps its own block below rather than churn every arm.
    val disposeImport = CirDllImport(
      libraryName = sealed.libraryName,
      entryPoint = "${subclass.nativePrefix}_dispose",
      returnType = "void",
      name = "Native_Dispose",
      parameters = listOf(CirParameter("handle", "IntPtr")),
      visibility = CirVisibility.PRIVATE,
    )
    append(
      buildString {
        renderDispose(
          nativeImport = disposeImport,
          isAbstract = false,
          hasSuperClass = true,
          hasSuspendMethods = true,
        )
      }.indentNestedBody(),
    )
    appendLine("        }")
    appendLine()
  } else {
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
}

/** The same import, marked `new` when it hides one of [baseExternNames]. */
private fun CirDllImport.hiding(baseExternNames: Set<String>): CirDllImport =
  if (name in baseExternNames) copy(isNew = true) else this

/**
 * Lifts a subclass block out of its base's braces: one nesting level shallower, which is exactly
 * the depth every other namespace-level declaration renders at, so the member bodies baked at the
 * ordinary-class indentation land right again.
 */
private fun String.outdentToNamespaceLevel(): String =
  lines().joinToString("\n") { line -> line.removePrefix("    ") }

internal fun StringBuilder.renderSealedSubclassDataMethods(
  libraryName: String,
  nativePrefix: String,
  sealedName: String,
  subclassName: String
) {
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
  // ADR-009 amendment (2026-09-11): the same modifier `CirClassRenderer` spells for an ordinary
  // class property. Only an `open` arm ever sets `isVirtual` (the translator gates it), so a final
  // arm's property stays byte-identical.
  val modifier: String = when {
    prop.isOverride -> "override "
    prop.isVirtual -> "virtual "
    // ADR-111 amendment (2026-09-11): a covariant arm property hides the base's, since C# has no
    // covariant property override (CS1715). Without `new` the hide is CS0108.
    prop.isNew -> "new "
    else -> ""
  }
  if (setter == null && !isMultiLineGetter) {
    appendLine("            public $modifier${prop.type} ${prop.name} => ${prop.getter};")
    return
  }

  appendLine("            public $modifier${prop.type} ${prop.name}")
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


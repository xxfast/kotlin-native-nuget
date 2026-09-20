package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderObject(obj: CirObject) {
  renderDoc(obj.doc)
  appendLine("    public static class ${obj.name}")
  appendLine("    {")

  obj.methods.forEach { method -> renderMember(method) }
  // ADR-133: an `object` owner nests its declarations exactly as a class owner does.
  renderNestedDeclarations(obj.nestedDeclarations)
  // Every member renderer ends with a separating blank line, which leaves one dangling before the
  // closing brace. Most visible on a `const val`-only object, whose whole body was one line of
  // code and one blank; dropped here so the body ends at its last member.
  dropTrailingBlankLine()

  appendLine("    }")
}

/** Removes one trailing blank line, if the builder ends with one. */
private fun StringBuilder.dropTrailingBlankLine() {
  val separator: String = System.lineSeparator()
  when {
    endsWith(separator + separator) -> setLength(length - separator.length)
    endsWith("\n\n") -> setLength(length - 1)
  }
}

internal fun StringBuilder.renderValueClass(cls: CirValueClass) {
  if (cls.underlyingIsReference) {
    renderReferenceValueClass(cls)
    return
  }

  // ADR-035: hand-written record struct so the primary constructor's `init` runs
  // across the bridge. The underlying is a get-only property assigned from a
  // validating CreateChecked* helper, blocking object-initializer / `with` bypass.
  renderDoc(cls.doc)
  appendLine("    public readonly record struct ${cls.name}")
  appendLine("    {")
  // ADR-150 amendment: the underlying property is written here rather than projected as a
  // `CirProperty`, so its KDoc rides `underlyingDoc` and is rendered at this one site.
  renderDoc(cls.underlyingDoc, "        ")
  appendLine("        public ${cls.underlyingType} ${cls.underlyingName} { get; }")
  appendLine()

  cls.constructors.forEach { ctor ->
    val paramStr: String = renderValueClassCreateChecked(cls, ctor)
    // ADR-150 amendment: the doc belongs on the public constructor, not on the private
    // `CreateChecked` helper above it, which is the surface a C# caller never sees.
    renderDoc(ctor.doc, "        ")
    appendLine("        public ${cls.name}($paramStr)")
    appendLine("        {")
    appendLine("            ${cls.underlyingName} = ${ctor.body};")
    appendLine("        }")
    appendLine()
  }

  renderValueClassMembers(cls)

  appendLine("    }")
}

// ADR-035: a reference-underlying value class keeps the positional record struct over its
// underlying handle as its primary (a hand-written second `Wrapper(Cat)` would be CS0111, so that
// one stays deferred). Its secondaries run in Kotlin and delegate to that positional constructor,
// rebuilding the handle the export minted: `: this(new Cat(CreateChecked_2(name)))`.
private fun StringBuilder.renderReferenceValueClass(cls: CirValueClass) {
  // ADR-150 amendment: this shape rendered NO class doc at all before (the early return in
  // `renderValueClass` skipped `renderDoc`), so a reference-underlying value class silently lost
  // its `<summary>`. Fixed here, together with the underlying property's only legal spelling.
  renderDoc(cls.recordHeaderDoc())
  appendLine("    public readonly record struct ${cls.name}(${cls.underlyingType} ${cls.underlyingName})")
  appendLine("    {")

  cls.constructors.forEach { ctor ->
    val paramStr: String = renderValueClassCreateChecked(cls, ctor)
    // ADR-150 amendment: only the SECONDARIES reach here. ADR-035 leaves a reference-underlying
    // value class's primary to the positional record header, so its `@constructor` has no
    // constructor surface of its own and stays unrendered.
    renderDoc(ctor.doc, "        ")
    appendLine("        public ${cls.name}($paramStr) : this(${ctor.body})")
    appendLine("        {")
    appendLine("        }")
    appendLine()
  }

  renderValueClassMembers(cls)

  appendLine("    }")
}

/**
 * ADR-150 amendment: the type doc of a reference-underlying value class, with the underlying
 * property's summary folded in as the record header's one `<param>`.
 *
 * That is the ONLY legal spelling for a positional record's property: a `<summary>` cannot attach
 * to it (there is no declaration to attach to), while a `<param name="Underlying">` on the type
 * is accepted and is copied by the compiler onto the type, onto the synthesized constructor and
 * onto the property itself (verified 2026-09-20). The three ways that fails are all fatal under
 * `GeneratedBindingsCheck`: a name that is not the positional parameter is CS1572, a second tag
 * for the same name is CS1571, and tagging none of several positional parameters while tagging
 * one is CS1573. There is exactly one positional parameter here, and the guard below refuses to
 * add a tag the class doc already carries, so none of the three is reachable.
 */
private fun CirValueClass.recordHeaderDoc(): CirDoc? {
  val summary: CirDocText = underlyingDoc?.summary ?: return doc
  if (doc?.params.orEmpty().any { it.name == underlyingName }) return doc
  return (doc ?: CirDoc()).copy(params = listOf(CirDocParam(underlyingName, summary)))
}

/**
 * The import + validating helper shared by both value-class shapes: the wire call, the ADR-033
 * error check, and the underlying it returns. Returns the public parameter list, which the caller
 * repeats on the constructor it renders around this.
 */
private fun StringBuilder.renderValueClassCreateChecked(
  cls: CirValueClass,
  ctor: CirValueClassConstructor,
): String {
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
  appendLine(
    "            $nativeReturnType underlying = " +
        "Native_Create$suffix($nativeArgs, out IntPtr error);",
  )
  appendLine("            if (error != IntPtr.Zero)")
  appendLine("            {")
  appendLine("                throw NugetErrorNative.BuildException(error);")
  appendLine("            }")
  appendLine("            return underlying;")
  appendLine("        }")
  appendLine()
  return paramStr
}

private fun StringBuilder.renderValueClassMembers(cls: CirValueClass) {
  cls.properties.forEach { prop ->
    renderDllImport(cls.propertyNativeImport(prop))
    renderDoc(prop.doc, "        ")
    appendLine("        public ${prop.type} ${prop.name} => ${prop.getter};")
    appendLine()
  }

  cls.methods.forEach { method ->
    renderDllImport(cls.methodNativeImport(method))
    renderDoc(method.doc, "        ")
    val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    if (method.returnType == "void") {
      appendLine("        public void ${method.name}($paramStr) => ${method.body};")
    } else {
      appendLine("        public ${method.returnType} ${method.name}($paramStr) => ${method.body};")
    }
    appendLine()
  }
}

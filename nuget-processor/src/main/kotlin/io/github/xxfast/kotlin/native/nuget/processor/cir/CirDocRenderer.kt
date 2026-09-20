package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-150: the author's KDoc as `///` XML doc comment lines, in the order docs.microsoft.com uses,
 * plus [generated] -- this generator's own prose (ADR-064's no-public-constructor remark).
 *
 * The one place plain prose becomes C# markup (ADR-064 amendment, 2026-09-10). Callers hand over
 * text, never markup: the reasons a remark carries name Kotlin constructors as `<init>`, which
 * unescaped is malformed XML doc and so a CS1570 in any consumer that generates a documentation
 * file (`GeneratedBindingsCheck` does). Every segment is escaped and then wrapped, so a code span
 * holding `Pair<A & B>` comes out `<c>Pair&lt;A &amp; B&gt;</c>`.
 *
 * ADR-150 amendment (2026-09-20): exactly ONE `<remarks>` per member, whatever it is made of --
 * the author's body paragraphs as `<para>`, then an unresolved `@see` as a closing `See also:`
 * paragraph, then [generated] last. When [generated] is the only content the output is
 * byte-identical to the three-line block ADR-064 shipped, which is what keeps its tests still.
 *
 * A parameter with no `@param` still gets an empty tag, because a partial set is a fatal CS1573
 * under `GenerateDocumentationFile` + `TreatWarningsAsErrors`.
 */
internal fun StringBuilder.renderDoc(
  doc: CirDoc? = null,
  indent: String = "    ",
  generated: List<String> = emptyList(),
) {
  if (doc == null && generated.isEmpty()) return
  renderSummary(doc, indent)
  renderRemarks(doc, generated, indent)
  for (param in doc?.params.orEmpty()) {
    appendLine("$indent/// <param name=\"${param.name}\">${param.text.inlined()}</param>")
  }
  if (doc?.returns != null) appendLine("$indent/// <returns>${doc.returns.inlined()}</returns>")
  for (thrown in doc?.throws.orEmpty()) {
    appendLine("$indent/// <exception cref=\"${thrown.cref}\">${thrown.text.inlined()}</exception>")
  }
  for (target in doc?.seeAlso.orEmpty().filterIsInstance<CirDocInline.TypeRef>()) {
    appendLine("$indent/// <seealso cref=\"${target.cref.attribute()}\"/>")
  }
}

/**
 * The summary stays a single `///` line -- the shape every ADR-150 test pins -- unless the author
 * put a fenced block in the first paragraph, which no inline element can hold.
 */
private fun StringBuilder.renderSummary(doc: CirDoc?, indent: String) {
  val summary: CirDocText = doc?.summary ?: emptyList()
  val code: List<String> = doc?.summaryCode.orEmpty()
  if (summary.isEmpty() && code.isEmpty()) return
  if (code.isEmpty()) {
    appendLine("$indent/// <summary>${summary.inlined()}</summary>")
    return
  }
  appendLine("$indent/// <summary>")
  if (summary.isNotEmpty()) appendLine("$indent/// ${summary.inlined()}")
  for (block in code) renderCode(block, indent)
  appendLine("$indent/// </summary>")
}

private fun StringBuilder.renderRemarks(doc: CirDoc?, generated: List<String>, indent: String) {
  val unresolved: List<CirDocInline> = doc?.seeAlso.orEmpty()
    .filter { it !is CirDocInline.TypeRef }
  val author: List<CirDocBlock> = doc?.remarks.orEmpty() +
      unresolved.map { CirDocBlock.Para(listOf(CirDocInline.Text("See also: "), it)) }
  if (author.isEmpty() && generated.isEmpty()) return

  appendLine("$indent/// <remarks>")
  // ADR-064's shape, kept byte-identical for the case it shipped: one generated remark and no
  // author prose renders as a bare line, not as a `<para>`.
  if (author.isEmpty() && generated.size == 1) {
    appendLine("$indent/// ${generated.single().xmlEscaped()}")
  } else {
    for (block in author) when (block) {
      is CirDocBlock.Para -> appendLine("$indent/// <para>${block.text.inlined()}</para>")
      is CirDocBlock.Code -> renderCode(block.value, indent)
    }
    for (paragraph in generated) appendLine("$indent/// <para>${paragraph.xmlEscaped()}</para>")
  }
  appendLine("$indent/// </remarks>")
}

/** A fenced KDoc block. Content is escaped verbatim; the fence's language tag never gets here. */
private fun StringBuilder.renderCode(value: String, indent: String) {
  appendLine("$indent/// <code>")
  for (line in value.lines()) appendLine("$indent/// ${line.xmlEscaped()}".trimEnd())
  appendLine("$indent/// </code>")
}

/** Escape first, wrap second: the whole reason the inline model exists. */
private fun CirDocText.inlined(): String = joinToString("") { segment ->
  when (segment) {
    is CirDocInline.Text -> segment.value.xmlEscaped()
    is CirDocInline.Code -> "<c>${segment.value.xmlEscaped()}</c>"
    // An unresolved link keeps the author's Kotlin spelling rather than guessing a C# rename for a
    // member nothing proved exists.
    is CirDocInline.Link -> "<c>${(segment.label ?: segment.target).xmlEscaped()}</c>"
    is CirDocInline.TypeRef ->
      if (segment.label == null) "<see cref=\"${segment.cref.attribute()}\"/>"
      else "<see cref=\"${segment.cref.attribute()}\">${segment.label.xmlEscaped()}</see>"
  }
}

/** `&` first: escaping it after `<` would double-escape the `&` of an `&lt;` we just wrote. */
private fun String.xmlEscaped(): String =
  replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** An attribute value additionally escapes the quote that delimits it. */
private fun String.attribute(): String = xmlEscaped().replace("\"", "&quot;")

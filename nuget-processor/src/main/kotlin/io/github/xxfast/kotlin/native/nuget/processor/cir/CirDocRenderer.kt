package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * XML doc comments on generated declarations (ADR-064 amendment, 2026-09-10).
 *
 * The one place plain prose becomes C# markup. Callers hand over text, never markup: the reasons a
 * remark carries name Kotlin constructors as `<init>`, which unescaped is malformed XML doc and so
 * a CS1570 in any consumer that generates a documentation file (`GeneratedBindingsCheck` does).
 */
internal fun StringBuilder.renderRemarks(remarks: String?, indent: String = "    ") {
  if (remarks == null) return
  appendLine("$indent/// <remarks>")
  appendLine("$indent/// ${remarks.xmlEscaped()}")
  appendLine("$indent/// </remarks>")
}

/**
 * ADR-150: the author's KDoc as `///` XML doc comment lines, in the order docs.microsoft.com uses.
 *
 * The same sink as [renderRemarks] and for the same reason: every fragment arrives as plain text
 * and leaves escaped. A parameter with no `@param` still gets an empty tag, because a partial set
 * is a fatal CS1573 under `GenerateDocumentationFile` + `TreatWarningsAsErrors`.
 */
internal fun StringBuilder.renderDoc(doc: CirDoc?, indent: String = "    ") {
  if (doc == null) return
  if (doc.summary != null) appendLine("$indent/// <summary>${doc.summary.xmlEscaped()}</summary>")
  for (param in doc.params) {
    appendLine("$indent/// <param name=\"${param.name}\">${param.text.xmlEscaped()}</param>")
  }
  if (doc.returns != null) appendLine("$indent/// <returns>${doc.returns.xmlEscaped()}</returns>")
  for (thrown in doc.throws) {
    val text: String = thrown.text.xmlEscaped()
    appendLine("$indent/// <exception cref=\"${thrown.cref}\">$text</exception>")
  }
}

/** `&` first: escaping it after `<` would double-escape the `&` of an `&lt;` we just wrote. */
private fun String.xmlEscaped(): String =
  replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

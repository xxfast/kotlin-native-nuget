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

/** `&` first: escaping it after `<` would double-escape the `&` of an `&lt;` we just wrote. */
private fun String.xmlEscaped(): String =
  replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

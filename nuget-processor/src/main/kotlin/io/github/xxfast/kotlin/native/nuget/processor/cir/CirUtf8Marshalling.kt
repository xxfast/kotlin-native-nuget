package io.github.xxfast.kotlin.native.nuget.processor.cir

private const val UTF8_ATTRIBUTE: String = "[MarshalAs(UnmanagedType.LPUTF8Str)]"
private const val EXTERN_MARKER: String = "static extern "

/**
 * A `[DllImport]` marshals a `string` parameter with the default `CharSet.Ansi`, which is UTF-8
 * only where the process code page happens to be UTF-8 (macOS, Linux). On Windows it is the active
 * legacy code page, so `"Röver 🐕"` reaches a Kotlin/Native export that decodes UTF-8 as
 * `"R?ver ??"`. `CharSet` has no UTF-8 member, so the per-parameter
 * `[MarshalAs(UnmanagedType.LPUTF8Str)]` is the only way to pin UTF-8 on a `DllImport`.
 *
 * Applied once over the whole rendered file rather than at each of the ~50 declaration sites (161
 * string-parameter imports in the sample library alone): every native import, structural
 * [CirDllImport] or raw legacy-route renderer text, is a `static extern` line in this output, so
 * one pass is complete by construction. Only parameters are annotated. A `string` *return* is
 * already declared `IntPtr` and read with `Marshal.PtrToStringUTF8`.
 *
 * The [io.github.xxfast.kotlin.native.nuget.processor.ForwardAbiContract] legacy-import parser
 * already strips a leading `[...] ` from a parameter, so the annotation is invisible to the ABI
 * check.
 */
internal fun String.withUtf8StringParameters(): String = lines()
  .joinToString("\n") { line ->
    if (line.contains(EXTERN_MARKER) && line.contains("string")) line.annotateStringParameters()
    else line
  }

private fun String.annotateStringParameters(): String {
  val open: Int = indexOf('(', startIndex = indexOf(EXTERN_MARKER))
  val close: Int = lastIndexOf(')')
  if (open < 0 || close < open) return this

  // Parameter types in a native import are never generic, so splitting on "," cannot cut inside
  // one. Unmodified pieces are rejoined verbatim, so a line with no string parameter is unchanged.
  val parameters: String = substring(open + 1, close)
    .split(",")
    .joinToString(",") { parameter -> parameter.annotateIfString() }

  return substring(0, open + 1) + parameters + substring(close)
}

private fun String.annotateIfString(): String {
  val declaration: String = trim()
  val type: String = declaration.substringBeforeLast(" ").trim()
  if (type != "string" && type != "string?") return this
  return takeWhile { character -> character == ' ' } + "$UTF8_ATTRIBUTE $declaration"
}

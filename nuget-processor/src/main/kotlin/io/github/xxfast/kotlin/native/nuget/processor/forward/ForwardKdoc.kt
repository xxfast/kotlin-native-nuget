package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDoc
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocParam
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocThrows
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_EXCEPTION_TYPES

/**
 * ADR-150: one `@throws T text` / `@exception T text` entry, [type] exactly as the author spelled
 * it.
 */
internal data class ForwardKdocThrows(val type: String, val text: String)

/**
 * ADR-150: the parsed shape of a `KSDeclaration.docString`, v1 tags only.
 *
 * Text, never markup: every fragment here reaches C# through `renderDoc`, the single escaping sink
 * (ADR-064's amendment). `[links]`, backticks and every tag outside the four below are plain text
 * or dropped.
 */
internal data class ForwardKdoc(
  val summary: String? = null,
  val params: Map<String, String> = emptyMap(),
  val returns: String? = null,
  val throws: List<ForwardKdocThrows> = emptyList(),
)

/**
 * ADR-150: parses [docString] as KSP 2.3.10 hands it over, which is the comment body with `/**`,
 * `*/` and each line's ` * ` prefix already removed and nothing else (verified): a multi-line
 * comment starts with `\n` and keeps one leading space per line, a one-line comment keeps a
 * trailing space, and every tag stays inline.
 *
 * `null` for `@suppress` (Dokka's opt-out: the declaration carries no doc comment at all) and for a
 * comment with no v1 content.
 */
internal fun parseKdoc(docString: String?): ForwardKdoc? {
  if (docString == null) return null
  val lines: List<String> = docString.lines().map { it.trim() }
  if (lines.any { it == "@suppress" || it.startsWith("@suppress ") }) return null

  val summary = StringBuilder()
  val params: MutableMap<String, String> = linkedMapOf()
  val throws: MutableList<ForwardKdocThrows> = mutableListOf()
  var returns: String? = null

  // The tag whose text the following continuation lines belong to, `null` while in the body.
  var tag: String? = null
  val text = StringBuilder()
  var summaryDone = false

  fun flush() {
    val current: String = tag ?: return
    val value: String = text.toString().trim()
    text.setLength(0)
    tag = null
    when {
      current.startsWith(PARAM) -> params[current.removePrefix(PARAM)] = value
      current == RETURN -> returns = value
      current.startsWith(THROWS) -> throws += ForwardKdocThrows(current.removePrefix(THROWS), value)
    }
  }

  fun open(kind: String, rest: String) {
    flush()
    summaryDone = true
    val name: String = rest.substringBefore(' ').trim()
    if (name.isEmpty()) return
    tag = "$kind$name"
    text.append(rest.removePrefix(name).trim())
  }

  for (line in lines) when {
    line.startsWith("@param") -> open(PARAM, line.removePrefix("@param").trim())
    line.startsWith("@return") -> {
      flush()
      summaryDone = true
      tag = RETURN
      text.append(line.removePrefix("@returns").removePrefix("@return").trim())
    }

    line.startsWith("@throws") -> open(THROWS, line.removePrefix("@throws").trim())
    line.startsWith("@exception") -> open(THROWS, line.removePrefix("@exception").trim())
    // Every other tag is dropped in v1, and ends the body: `@property`/`@constructor` text is not
    // this declaration's summary.
    line.startsWith("@") -> { flush(); summaryDone = true }
    line.isEmpty() -> { flush(); if (summary.isNotEmpty()) summaryDone = true }
    tag != null -> text.append(' ').append(line)
    !summaryDone -> {
      if (summary.isNotEmpty()) summary.append(' ')
      summary.append(line)
    }
  }
  flush()

  if (summary.isEmpty() && params.isEmpty() && returns == null && throws.isEmpty()) return null
  return ForwardKdoc(
    summary = summary.toString().trim().ifEmpty { null },
    params = params,
    returns = returns?.ifEmpty { null },
    throws = throws,
  )
}

private const val PARAM: String = "param:"
private const val RETURN: String = "return"
private const val THROWS: String = "throws:"

/**
 * ADR-150: the C# view of this comment for one rendered member.
 *
 * [parameters] are the C# parameter names of *that* member, in order, so an ADR-096/091 omitting
 * overload drops the omitted parameters' tags (a `<param>` naming a parameter the member does not
 * have is a fatal CS1572). All-or-none: once one `@param` matches, every parameter gets a tag and
 * the undocumented ones get an empty one (a partial set is a fatal CS1573).
 */
internal fun ForwardKdoc.toCirDoc(
  parameters: List<String> = emptyList(),
  hasResult: Boolean = false,
): CirDoc? {
  val documented: Boolean = parameters.any { it in params }
  val docParams: List<CirDocParam> =
    if (!documented) emptyList()
    else parameters.map { CirDocParam(it, params[it].orEmpty()) }
  val doc = CirDoc(
    summary = summary,
    params = docParams,
    returns = returns?.takeIf { hasResult },
    throws = throws.map { it.toCirDocThrows() },
  )
  return if (doc.isEmpty()) null else doc
}

/**
 * ADR-150: the `cref`, through the same table `CirErrorRenderer`'s `BuildMapped` switch is built
 * from, so the documented exception is the one the consumer actually catches. An author spells the
 * type as written (`IllegalArgumentException`, rarely `kotlin.IllegalArgumentException`), so the
 * match is on the simple name. An unmapped type crefs `KotlinException` and keeps the Kotlin
 * spelling as a plain `T: ` prefix on the text.
 */
private fun ForwardKdocThrows.toCirDocThrows(): CirDocThrows {
  val simple: String = type.substringAfterLast('.')
  val mapped: String? = KOTLIN_EXCEPTION_TYPES.entries
    .firstOrNull { (kotlinType, _) -> kotlinType.substringAfterLast('.') == simple }
    ?.value
  return if (mapped != null) CirDocThrows(mapped, text)
  else CirDocThrows("KotlinException", if (text.isEmpty()) "$simple" else "$simple: $text")
}

private fun CirDoc.isEmpty(): Boolean =
  summary == null && params.isEmpty() && returns == null && throws.isEmpty()

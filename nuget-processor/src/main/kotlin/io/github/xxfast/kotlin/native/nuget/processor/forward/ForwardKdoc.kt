package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDoc
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocBlock
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocInline
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocParam
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocThrows
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_EXCEPTION_TYPES

/**
 * ADR-150: one `@throws T text` / `@exception T text` entry, [type] exactly as the author spelled
 * it.
 */
internal data class ForwardKdocThrows(val type: String, val text: String)

/**
 * ADR-150 amendment: one block of the comment body. A [Para] is a paragraph of prose (its inline
 * `[links]` and backtick spans are still unparsed markdown at this point, and become
 * [io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocInline] segments in [toCirDoc]); a
 * [Code] is a fenced block, its language tag already discarded and its lines kept verbatim.
 */
internal sealed interface ForwardKdocBlock {

  data class Para(val text: String) : ForwardKdocBlock

  data class Code(val value: String) : ForwardKdocBlock
}

/**
 * ADR-150: the parsed shape of a `KSDeclaration.docString`.
 *
 * Text, never markup: every fragment here reaches C# through `renderDoc`, the single escaping sink
 * (ADR-064's amendment). The markdown that *is* recognised (fenced blocks here, `[links]` and
 * backtick spans in [toCirDoc]) is turned into model, never into markup.
 *
 * [summary] is the first body paragraph, [summaryCode] the fenced blocks that sit inside it, and
 * [remarks] every block after it. [properties] and [constructor] are the class-level tags that
 * document a *different* declaration than the one carrying the comment: a constructor property and
 * a primary constructor both report `docString == null` through KSP (spike 2, 2026-09-20), so the
 * class comment is the only place their text can come from.
 */
internal data class ForwardKdoc(
  val summary: String? = null,
  val summaryCode: List<String> = emptyList(),
  val remarks: List<ForwardKdocBlock> = emptyList(),
  val params: Map<String, String> = emptyMap(),
  val returns: String? = null,
  val throws: List<ForwardKdocThrows> = emptyList(),
  val properties: Map<String, String> = emptyMap(),
  val constructor: String? = null,
  val seeAlso: List<String> = emptyList(),
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
  val summaryCode: MutableList<String> = mutableListOf()
  val remarks: MutableList<ForwardKdocBlock> = mutableListOf()
  val params: MutableMap<String, String> = linkedMapOf()
  val properties: MutableMap<String, String> = linkedMapOf()
  val throws: MutableList<ForwardKdocThrows> = mutableListOf()
  val seeAlso: MutableList<String> = mutableListOf()
  var returns: String? = null
  var constructorText: String? = null

  // The tag whose text the following continuation lines belong to, `null` while in the body or
  // after a tag this generator drops. `tagSeen` is the one-way latch: ADR-150 amendment, once any
  // block tag has been read the body is closed, so a line after a dropped tag never rejoins it.
  var tag: String? = null
  val text = StringBuilder()
  var summaryDone = false
  var tagSeen = false

  // The body paragraph being accumulated, which is the summary until the first blank line and a
  // `<remarks>` paragraph after it.
  val para = StringBuilder()
  var fenced = false
  val fence: MutableList<String> = mutableListOf()

  fun flush() {
    val current: String = tag ?: return
    val value: String = text.toString().trim()
    text.setLength(0)
    tag = null
    when {
      current.startsWith(PARAM) -> params[current.removePrefix(PARAM)] = value
      current == RETURN -> returns = value
      current.startsWith(THROWS) -> throws += ForwardKdocThrows(current.removePrefix(THROWS), value)
      current.startsWith(PROPERTY) -> properties[current.removePrefix(PROPERTY)] = value
      current == CONSTRUCTOR -> constructorText = value
    }
  }

  fun flushPara() {
    val value: String = para.toString().trim()
    para.setLength(0)
    if (value.isNotEmpty()) remarks += ForwardKdocBlock.Para(value)
  }

  fun open(kind: String, rest: String) {
    flush()
    flushPara()
    summaryDone = true
    tagSeen = true
    val name: String = rest.substringBefore(' ').trim()
    if (name.isEmpty()) return
    tag = "$kind$name"
    text.append(rest.removePrefix(name).trim())
  }

  fun openUnnamed(kind: String, rest: String) {
    flush()
    flushPara()
    summaryDone = true
    tagSeen = true
    tag = kind
    text.append(rest.trim())
  }

  for (line in lines) when {
    // A fence owns every line until it closes: inside one, `@param` is code and a blank line is a
    // blank code line, not a tag and not a paragraph break.
    fenced && line.startsWith(FENCE) -> {
      fenced = false
      val value: String = fence.joinToString("\n").trim('\n')
      fence.clear()
      // A fence in a tag section has no element to land in (a `<code>` inside `<param>` would sit
      // in the wrong member's documentation), so it is dropped, as `@sample` is.
      when {
        tagSeen -> Unit
        summaryDone -> remarks += ForwardKdocBlock.Code(value)
        else -> summaryCode += value
      }
    }

    fenced -> fence += line
    line.startsWith(FENCE) -> { flushPara(); fenced = true }
    line.startsWith("@param") -> open(PARAM, line.removePrefix("@param").trim())
    line.startsWith("@property") -> open(PROPERTY, line.removePrefix("@property").trim())
    line.startsWith("@constructor") ->
      openUnnamed(CONSTRUCTOR, line.removePrefix("@constructor"))

    line.startsWith("@return") -> {
      openUnnamed(RETURN, line.removePrefix("@returns").removePrefix("@return"))
    }

    line.startsWith("@throws") -> open(THROWS, line.removePrefix("@throws").trim())
    line.startsWith("@exception") -> open(THROWS, line.removePrefix("@exception").trim())
    line.startsWith("@see") -> {
      flush()
      flushPara()
      summaryDone = true
      tagSeen = true
      // `@see identifier` names one target; Dokka ignores anything after it on the line.
      line.removePrefix("@see").trim().substringBefore(' ').trim()
        .takeIf { it.isNotEmpty() }
        ?.let { seeAlso += it }
    }

    // Every other tag (`@receiver`, `@sample`, `@since`, …) is dropped, and ends the body.
    line.startsWith("@") -> { flush(); flushPara(); summaryDone = true; tagSeen = true }
    // ADR-150 amendment: a blank line inside a tag section CONTINUES that section (the section
    // ends at the next tag), where v1 flushed the tag and silently dropped everything after it.
    line.isEmpty() -> when {
      tagSeen -> Unit
      !summaryDone -> if (summary.isNotEmpty()) summaryDone = true
      else -> flushPara()
    }

    tag != null -> text.append(' ').append(line)
    tagSeen -> Unit
    !summaryDone -> {
      if (summary.isNotEmpty()) summary.append(' ')
      summary.append(line)
    }

    else -> {
      if (para.isNotEmpty()) para.append(' ')
      para.append(line)
    }
  }
  flush()
  flushPara()
  // An unterminated fence keeps whatever it collected rather than dropping it on the floor.
  if (fenced && fence.isNotEmpty()) {
    remarks += ForwardKdocBlock.Code(fence.joinToString("\n").trim('\n'))
  }

  val empty: Boolean = summary.isEmpty() && summaryCode.isEmpty() && remarks.isEmpty() &&
      params.isEmpty() && returns == null && throws.isEmpty() && properties.isEmpty() &&
      constructorText == null && seeAlso.isEmpty()
  if (empty) return null
  return ForwardKdoc(
    summary = summary.toString().trim().ifEmpty { null },
    summaryCode = summaryCode,
    remarks = remarks,
    params = params,
    returns = returns?.ifEmpty { null },
    throws = throws,
    properties = properties,
    constructor = constructorText?.ifEmpty { null },
    seeAlso = seeAlso,
  )
}

private const val PARAM: String = "param:"
private const val RETURN: String = "return"
private const val THROWS: String = "throws:"
private const val PROPERTY: String = "property:"
private const val CONSTRUCTOR: String = "constructor"
private const val FENCE: String = "```"

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
    else parameters.map { CirDocParam(it, params[it].orEmpty().docInlines()) }
  val doc = CirDoc(
    summary = summary?.docInlines(),
    summaryCode = summaryCode,
    remarks = remarks.map { block ->
      when (block) {
        is ForwardKdocBlock.Para -> CirDocBlock.Para(block.text.docInlines())
        is ForwardKdocBlock.Code -> CirDocBlock.Code(block.value)
      }
    },
    params = docParams,
    returns = returns?.takeIf { hasResult }?.docInlines(),
    throws = throws.map { it.toCirDocThrows() },
    // Every `@see` starts unresolved; `CirFile.resolveDocLinks()` promotes the ones the finished
    // file proves it declares, and the rest close the `<remarks>` as prose.
    seeAlso = seeAlso.map { CirDocInline.Link(it) },
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
  // The `T: ` prefix is this generator's own prose, so it is glued on BEFORE tokenizing: the
  // Kotlin type name stays plain text rather than becoming a `<c>` span of its own.
  return if (mapped != null) CirDocThrows(mapped, text.docInlines())
  else CirDocThrows(
    "KotlinException",
    (if (text.isEmpty()) simple else "$simple: $text").docInlines(),
  )
}

private fun CirDoc.isEmpty(): Boolean =
  summary == null && summaryCode.isEmpty() && remarks.isEmpty() && params.isEmpty() &&
      returns == null && throws.isEmpty() && seeAlso.isEmpty()

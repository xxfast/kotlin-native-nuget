package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocInline
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocText

/**
 * ADR-150 amendment: the inline markdown KDoc allows inside one paragraph of prose, as model.
 *
 * Pure and total: every input produces segments, never markup and never a failure. Markup happens
 * once, in `renderDoc`, which escapes each segment's text *before* wrapping it -- which is why the
 * tokenizer must run first. A backtick span holding `Pair<A & B>` becomes
 * `<c>Pair&lt;A &amp; B&gt;</c>` and not `<c>` around already-escaped text.
 *
 * Rules, all of them Dokka's:
 * - a backtick run of length N opens a code span that closes on the next run of the same length;
 *   an unbalanced run is literal text, and brackets inside a span are literal.
 * - `[target]` and `[label][target]` are links, but only when `target` is dotted-identifier shaped
 *   (`[1, 2]` and `[see below]` are prose). A link is a [CirDocInline.Link] here and stays one
 *   unless `CirFile.resolveDocLinks()` proves the generated file declares that type.
 * - `\[` is a literal bracket.
 * - `[label](url)` is left literal: an `<see href>` is deferred scope, and half-recognising it
 *   would print the label as code and the URL as prose.
 */
internal fun String.docInlines(): CirDocText {
  val segments: MutableList<CirDocInline> = mutableListOf()
  val literal = StringBuilder()

  fun flush() {
    if (literal.isEmpty()) return
    segments += CirDocInline.Text(literal.toString())
    literal.setLength(0)
  }

  var index = 0
  while (index < length) {
    val char: Char = this[index]
    when {
      char == '\\' && index + 1 < length && (this[index + 1] == '[' || this[index + 1] == '`') -> {
        literal.append(this[index + 1])
        index += 2
      }

      char == '`' -> {
        val run: Int = backtickRun(index)
        val close: Int = indexOf("`".repeat(run), index + run)
        if (close < 0) {
          literal.append("`".repeat(run))
          index += run
        } else {
          flush()
          segments += CirDocInline.Code(substring(index + run, close))
          index = close + run
        }
      }

      char == '[' -> {
        val link: Link? = linkAt(index)
        if (link == null) {
          literal.append(char)
          index += 1
        } else {
          flush()
          segments += CirDocInline.Link(link.target, link.label)
          index = link.end
        }
      }

      else -> {
        literal.append(char)
        index += 1
      }
    }
  }
  flush()
  return segments
}

/** The length of the unbroken backtick run starting at [start]. */
private fun String.backtickRun(start: Int): Int {
  var end: Int = start
  while (end < length && this[end] == '`') end += 1
  return end - start
}

private class Link(val target: String, val label: String?, val end: Int)

/**
 * The link starting at the `[` at [start], or null when this bracket is prose. `[label](url)` is
 * deliberately prose here: see [docInlines].
 */
private fun String.linkAt(start: Int): Link? {
  val close: Int = indexOf(']', start + 1)
  if (close < 0) return null
  val first: String = substring(start + 1, close)
  val next: Char? = getOrNull(close + 1)
  if (next == '(') return null
  if (next != '[') return if (first.isLinkTarget()) Link(first, null, close + 1) else null

  val secondClose: Int = indexOf(']', close + 2)
  if (secondClose < 0) return null
  val target: String = substring(close + 2, secondClose)
  if (!target.isLinkTarget()) return null
  return Link(target, first.ifEmpty { null }, secondClose + 1)
}

/**
 * A dotted identifier and nothing else: this is what separates `[Snooze]` and `[BoardingDesk.book]`
 * from prose in brackets. A target that resolves to no declared type still renders, as `<c>`.
 */
private fun String.isLinkTarget(): Boolean = isNotEmpty() && matches(LINK_TARGET)

private val LINK_TARGET: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

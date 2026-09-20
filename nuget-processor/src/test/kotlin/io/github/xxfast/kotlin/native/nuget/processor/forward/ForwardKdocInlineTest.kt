package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocInline
import kotlin.test.Test
import kotlin.test.assertEquals

/** ADR-150 amendment: the inline tokenizer, on its own, with no KSP and no renderer. */
class ForwardKdocInlineTest {

  @Test
  fun `plain prose is one text segment`() {
    assertEquals(listOf(CirDocInline.Text("a & b < c")), "a & b < c".docInlines())
  }

  @Test
  fun `a backtick span becomes code and keeps its content verbatim`() {
    assertEquals(
      listOf(
        CirDocInline.Text("reads "),
        CirDocInline.Code("Pair<A & B>"),
        CirDocInline.Text(" now"),
      ),
      "reads `Pair<A & B>` now".docInlines(),
    )
  }

  @Test
  fun `a double backtick run closes on a double run and can hold a single one`() {
    assertEquals(
      listOf(CirDocInline.Code("a ` b")),
      "``a ` b``".docInlines(),
    )
  }

  @Test
  fun `an unbalanced backtick stays literal`() {
    assertEquals(listOf(CirDocInline.Text("cost is `10")), "cost is `10".docInlines())
  }

  @Test
  fun `a bracket inside a code span is not a link`() {
    assertEquals(listOf(CirDocInline.Code("[Snooze]")), "`[Snooze]`".docInlines())
  }

  @Test
  fun `an identifier in brackets is a link and prose in brackets is not`() {
    assertEquals(
      listOf(CirDocInline.Text("see "), CirDocInline.Link("Snooze")),
      "see [Snooze]".docInlines(),
    )
    assertEquals(
      listOf(CirDocInline.Link("BoardingDesk.book")),
      "[BoardingDesk.book]".docInlines(),
    )
    assertEquals(listOf(CirDocInline.Text("[1, 2]")), "[1, 2]".docInlines())
    assertEquals(listOf(CirDocInline.Text("[see below]")), "[see below]".docInlines())
  }

  @Test
  fun `a labelled link keeps the label and links the target`() {
    assertEquals(
      listOf(CirDocInline.Link("Snooze", "a nap")),
      "[a nap][Snooze]".docInlines(),
    )
  }

  @Test
  fun `an escaped bracket or backtick is literal`() {
    assertEquals(listOf(CirDocInline.Text("[Snooze]")), "\\[Snooze]".docInlines())
    assertEquals(listOf(CirDocInline.Text("a `b` c")), "a \\`b\\` c".docInlines())
  }

  @Test
  fun `a labelled link whose target is not an identifier stays prose`() {
    assertEquals(
      listOf(CirDocInline.Text("[a nap][not an ident]")),
      "[a nap][not an ident]".docInlines(),
    )
    assertEquals(listOf(CirDocInline.Text("[a nap][Snooze")), "[a nap][Snooze".docInlines())
  }

  /** Deferred scope: `<see href>` needs attribute escaping and a URL shape this item skips. */
  @Test
  fun `a markdown url link stays prose rather than half-rendering`() {
    assertEquals(
      listOf(CirDocInline.Text("[docs](https://example.org?a=1&b=2)")),
      "[docs](https://example.org?a=1&b=2)".docInlines(),
    )
  }

  @Test
  fun `an unterminated bracket is literal`() {
    assertEquals(listOf(CirDocInline.Text("see [Snooze")), "see [Snooze".docInlines())
  }
}

package io.github.xxfast.kotlin.native.nuget.processor.cir

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-150 promised this file and never got it; the ADR-150 amendment (2026-09-20) needs it, because
 * `renderDoc` is now the single sink for four kinds of markup rather than one.
 *
 * Everything here is the rendered text of a hand-built [CirDoc], with no KSP in the loop: escaping
 * per segment, the one-`<remarks>` merge, and the byte-identity ADR-064's own tests depend on.
 */
class CirDocRendererTest {

  private fun render(doc: CirDoc? = null, generated: List<String> = emptyList()): String =
    buildString { renderDoc(doc, generated = generated) }

  private fun text(value: String): CirDocText = listOf(CirDocInline.Text(value))

  @Test
  fun `a plain summary stays one line and is escaped`() {
    assertEquals(
      "    /// <summary>Holds a &lt;cat&gt; &amp; a dog.</summary>\n",
      render(CirDoc(summary = text("Holds a <cat> & a dog."))),
    )
  }

  @Test
  fun `a code span is escaped inside the c element, not around it`() {
    assertEquals(
      "    /// <summary>Reads <c>Pair&lt;A &amp; B&gt;</c> now.</summary>\n",
      render(
        CirDoc(
          summary = listOf(
            CirDocInline.Text("Reads "),
            CirDocInline.Code("Pair<A & B>"),
            CirDocInline.Text(" now."),
          ),
        ),
      ),
    )
  }

  @Test
  fun `a resolved link is a cref and an unresolved one keeps the Kotlin spelling in c`() {
    assertEquals(
      "    /// <summary>See <see cref=\"global::Lib.Snooze\"/> and <c>book</c>.</summary>\n",
      render(
        CirDoc(
          summary = listOf(
            CirDocInline.Text("See "),
            CirDocInline.TypeRef("global::Lib.Snooze"),
            CirDocInline.Text(" and "),
            CirDocInline.Link("book"),
            CirDocInline.Text("."),
          ),
        ),
      ),
    )
  }

  @Test
  fun `a labelled link renders the label as the element content`() {
    assertEquals(
      "    /// <summary><see cref=\"global::Lib.Snooze\">a nap</see>, <c>the label</c></summary>\n",
      render(
        CirDoc(
          summary = listOf(
            CirDocInline.TypeRef("global::Lib.Snooze", "a nap"),
            CirDocInline.Text(", "),
            CirDocInline.Link("Missing", "the label"),
          ),
        ),
      ),
    )
  }

  @Test
  fun `author paragraphs and a fenced block share one remarks element`() {
    assertEquals(
      """
      |    /// <summary>Books a stay.</summary>
      |    /// <remarks>
      |    /// <para>One.</para>
      |    /// <code>
      |    /// val x = 1 &amp; 2
      |    /// </code>
      |    /// </remarks>
      |
      """.trimMargin(),
      render(
        CirDoc(
          summary = text("Books a stay."),
          remarks = listOf(CirDocBlock.Para(text("One.")), CirDocBlock.Code("val x = 1 & 2")),
        ),
      ),
    )
  }

  /**
   * The seam ADR-064 and the ADR-150 amendment share: one element, author prose first, this
   * generator's prose last. Two `<remarks>` elements compile (verified 2026-09-20) but doc tooling
   * reads the first only, so the merge is the whole point.
   */
  @Test
  fun `the generated remark is the last para when the author has remarks too`() {
    assertEquals(
      """
      |    /// <remarks>
      |    /// <para>One.</para>
      |    /// <para>skipped &lt;init&gt;</para>
      |    /// </remarks>
      |
      """.trimMargin(),
      render(CirDoc(remarks = listOf(CirDocBlock.Para(text("One.")))), listOf("skipped <init>")),
    )
  }

  /** ADR-064's shipped shape, byte for byte: no `<para>`, no doc, three lines. */
  @Test
  fun `a lone generated remark renders exactly as ADR-064 shipped it`() {
    assertEquals(
      """
      |    /// <remarks>
      |    /// skipped &lt;init&gt; &amp; &lt;clinit&gt;
      |    /// </remarks>
      |
      """.trimMargin(),
      render(generated = listOf("skipped <init> & <clinit>")),
    )
  }

  @Test
  fun `an unresolved see closes the remarks and a resolved one is a seealso`() {
    assertEquals(
      """
      |    /// <remarks>
      |    /// <para>See also: <c>book</c></para>
      |    /// </remarks>
      |    /// <seealso cref="global::Lib.SunSpot"/>
      |
      """.trimMargin(),
      render(
        CirDoc(
          seeAlso = listOf(CirDocInline.TypeRef("global::Lib.SunSpot"), CirDocInline.Link("book")),
        ),
      ),
    )
  }

  @Test
  fun `a fence in the first paragraph rides the summary as a code element`() {
    assertEquals(
      """
      |    /// <summary>
      |    /// Books a stay.
      |    /// <code>
      |    /// desk.book(2)
      |    /// </code>
      |    /// </summary>
      |
      """.trimMargin(),
      render(CirDoc(summary = text("Books a stay."), summaryCode = listOf("desk.book(2)"))),
    )
  }

  @Test
  fun `params, returns and exceptions carry inline segments too`() {
    assertEquals(
      """
      |    /// <param name="nights">how many <c>Int</c></param>
      |    /// <param name="suite"></param>
      |    /// <returns>a <see cref="global::Lib.Booking"/></returns>
      |    /// <exception cref="KotlinException">RuntimeException: &lt;closed&gt;</exception>
      |
      """.trimMargin(),
      render(
        CirDoc(
          params = listOf(
            CirDocParam(
              "nights",
              listOf(CirDocInline.Text("how many "), CirDocInline.Code("Int")),
            ),
            CirDocParam("suite", emptyList()),
          ),
          returns = listOf(
            CirDocInline.Text("a "),
            CirDocInline.TypeRef("global::Lib.Booking"),
          ),
          throws = listOf(CirDocThrows("KotlinException", text("RuntimeException: <closed>"))),
        ),
      ),
    )
  }

  /**
   * The value-class seam: a `CirValueClassConstructor`'s doc renders at the struct-member indent,
   * above the `public <Name>(...)` line, with the class comment's `@constructor` as its summary
   * and its `@param` set already filtered to real parameters (a tag naming a non-parameter is
   * CS1572, fatal under `GeneratedBindingsCheck`).
   */
  @Test
  fun `a value-class constructor doc renders at the struct member indent`() {
    assertEquals(
      """
      |        /// <summary>Weighs a cat.</summary>
      |        /// <param name="value">the weight in grams</param>
      |
      """.trimMargin(),
      buildString {
        renderDoc(
          CirDoc(
            summary = text("Weighs a cat."),
            params = listOf(CirDocParam("value", text("the weight in grams"))),
          ),
          "        ",
        )
      },
    )
  }

  @Test
  fun `no doc and no generated remark renders nothing at all`() {
    assertEquals("", render())
  }
}

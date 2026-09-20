package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDoc
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocInline
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDocText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ADR-150: the parser, against the `docString` shapes KSP 2.3.10 really hands over (spike 1): a
 * multi-line comment starts with `\n` and keeps one leading space per line, a one-line comment
 * keeps a trailing space, and every tag stays inline.
 */
class ForwardKdocTest {

  @Test
  fun `a multi-line comment splits into summary, remarks and tags`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc(
        "\n Books a stay.\n\n Second paragraph.\n\n @param count how many\n" +
            " @param label   a label\n @return the result\n" +
            " @throws IllegalArgumentException when count is negative\n",
      ),
    )

    assertEquals("Books a stay.", doc.summary)
    // ADR-150 amendment: paragraph two is `<remarks>`, where v1 dropped it.
    assertEquals(listOf(ForwardKdocBlock.Para("Second paragraph.")), doc.remarks)
    assertEquals(mapOf("count" to "how many", "label" to "a label"), doc.params)
    assertEquals("the result", doc.returns)
    assertEquals(
      listOf(ForwardKdocThrows("IllegalArgumentException", "when count is negative")),
      doc.throws,
    )
  }

  @Test
  fun `every body paragraph after the first becomes a remarks block`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n One.\n\n Two,\n still two.\n\n Three.\n"),
    )

    assertEquals("One.", doc.summary)
    assertEquals(
      listOf(ForwardKdocBlock.Para("Two, still two."), ForwardKdocBlock.Para("Three.")),
      doc.remarks,
    )
  }

  @Test
  fun `a fenced block is a code block of its own and loses its language tag`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n One.\n\n Two.\n\n ```kotlin\n val x = 1 & 2\n val y = x\n ```\n\n Three.\n"),
    )

    assertEquals("One.", doc.summary)
    assertEquals(
      listOf(
        ForwardKdocBlock.Para("Two."),
        ForwardKdocBlock.Code("val x = 1 & 2\nval y = x"),
        ForwardKdocBlock.Para("Three."),
      ),
      doc.remarks,
    )
  }

  @Test
  fun `a fence inside the first paragraph rides the summary`() {
    val doc: ForwardKdoc = requireNotNull(parseKdoc("\n One.\n ```\n val x = 1\n ```\n"))

    assertEquals("One.", doc.summary)
    assertEquals(listOf("val x = 1"), doc.summaryCode)
    assertEquals(emptyList(), doc.remarks)
  }

  /** A malformed comment must not swallow text: the salvage keeps what the fence collected. */
  @Test
  fun `an unterminated fence keeps its content`() {
    val doc: ForwardKdoc = requireNotNull(parseKdoc("\n One.\n\n ```\n val x = 1\n"))

    assertEquals(listOf(ForwardKdocBlock.Code("val x = 1")), doc.remarks)
  }

  @Test
  fun `a tag line inside a fence is code, not a tag`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n One.\n\n ```\n @param not a tag\n ```\n"),
    )

    assertEquals(emptyMap(), doc.params)
    assertEquals(listOf(ForwardKdocBlock.Code("@param not a tag")), doc.remarks)
  }

  @Test
  fun `a blank line inside a tag section continues it and the section ends at the next tag`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc(
        "\n Rehomes.\n\n @param home where it goes\n\n once the sunbeam moves\n" +
            " @throws RuntimeException when closed\n",
      ),
    )

    // v1 flushed the tag on the blank line and dropped everything up to the next tag.
    assertEquals(mapOf("home" to "where it goes once the sunbeam moves"), doc.params)
    assertEquals(
      listOf(ForwardKdocThrows("RuntimeException", "when closed")),
      doc.throws,
    )
    assertEquals(emptyList(), doc.remarks)
  }

  @Test
  fun `see targets are collected and a comment of only a see still parses`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Rehomes.\n @see SunSpot\n @see book\n"),
    )
    assertEquals(listOf("SunSpot", "book"), doc.seeAlso)
    assertEquals(listOf("Perch"), parseKdoc("@see Perch ")?.seeAlso)
  }

  @Test
  fun `text after a dropped tag never rejoins the body`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Does it.\n @sample com.example.sample\n loose continuation\n"),
    )

    assertEquals("Does it.", doc.summary)
    assertEquals(emptyList(), doc.remarks)
  }

  @Test
  fun `a one-line comment keeps only its summary`() {
    assertEquals("Prop doc.", parseKdoc("Prop doc. ")?.summary)
  }

  @Test
  fun `a tag's continuation line joins with a space`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Does a thing.\n @param count how many\n   of them to take\n"),
    )
    assertEquals(mapOf("count" to "how many of them to take"), doc.params)
  }

  @Test
  fun `suppress opts the declaration out entirely`() {
    assertNull(parseKdoc("@suppress "))
    assertNull(parseKdoc("\n Documented, but suppressed.\n @suppress\n"))
  }

  /**
   * ADR-150 amendment: both tags are now kept, on their own slots. They document a *different*
   * declaration than the one carrying the comment, so neither may reach this declaration's summary
   * or its `<param>` set -- the planner routes them to the property and the primary constructor.
   */
  @Test
  fun `property and constructor tags land on their own slots, not on the summary`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc(
        "\n Summary line of Foo.\n\n @property name the name prop\n @constructor builds a Foo\n" +
            " @param scoops how many\n",
      ),
    )
    assertEquals("Summary line of Foo.", doc.summary)
    assertEquals(mapOf("name" to "the name prop"), doc.properties)
    assertEquals("builds a Foo", doc.constructor)
    // The class-level `@param` is the primary constructor's, and is kept for it alone.
    assertEquals(mapOf("scoops" to "how many"), doc.params)
    assertEquals(emptyList(), doc.remarks)
  }

  @Test
  fun `no comment and an empty comment parse to null`() {
    assertNull(parseKdoc(null))
    assertNull(parseKdoc("  "))
  }

  /**
   * ADR-150 amendment: a `CirDoc` text slot is a list of inline segments, so a test asserting the
   * prose asserts the plain text of those segments. A slot holding markup (`<c>`, `<see>`) is
   * asserted through `CirDocRendererTest`, which is where markup is written.
   */
  private fun CirDocText.plain(): String = joinToString("") { segment ->
    when (segment) {
      is CirDocInline.Text -> segment.value
      is CirDocInline.Code -> "`${segment.value}`"
      is CirDocInline.Link -> "[${segment.target}]"
      is CirDocInline.TypeRef -> "[${segment.cref}]"
    }
  }

  @Test
  fun `exception is an alias of throws and an unmapped type crefs KotlinException`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Rehomes.\n @exception RuntimeException when closed\n"),
    )
    val cir = requireNotNull(doc.toCirDoc())
    assertEquals("KotlinException", cir.throws.single().cref)
    // The Kotlin type name is this generator's prose, and stays plain text rather than a `<c>`.
    assertEquals("RuntimeException: when closed", cir.throws.single().text.plain())
  }

  @Test
  fun `inline markup in a tag or a summary becomes segments, not literal text`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Books a `Pair<A & B>`.\n @param nights see [Snooze]\n"),
    )
    val cir: CirDoc = requireNotNull(doc.toCirDoc(listOf("nights")))

    assertEquals(
      listOf(
        CirDocInline.Text("Books a "),
        CirDocInline.Code("Pair<A & B>"),
        CirDocInline.Text("."),
      ),
      cir.summary,
    )
    assertEquals(
      listOf(CirDocInline.Text("see "), CirDocInline.Link("Snooze")),
      cir.params.single().text,
    )
  }

  @Test
  fun `a mapped throws crefs the generated exception and keeps the text unprefixed`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc(
        "\n Books.\n @throws kotlin.IllegalArgumentException when nights is not positive\n",
      ),
    )
    val cir = requireNotNull(doc.toCirDoc())
    assertEquals("KotlinArgumentException", cir.throws.single().cref)
    assertEquals("when nights is not positive", cir.throws.single().text.plain())
  }

  @Test
  fun `an undocumented parameter beside a documented one gets an empty tag`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Grooms.\n @param brush which brush\n"),
    )
    val cir = requireNotNull(doc.toCirDoc(listOf("brush", "gentle")))
    assertEquals(listOf("which brush", ""), cir.params.map { it.text.plain() })
    assertEquals(listOf("brush", "gentle"), cir.params.map { it.name })
  }

  @Test
  fun `no matching param leaves the member without any param tag`() {
    val doc: ForwardKdoc = requireNotNull(parseKdoc("\n Ext doc.\n @receiver the foo\n"))
    assertEquals(emptyList(), requireNotNull(doc.toCirDoc(listOf("other"))).params)
  }

  @Test
  fun `a returns is dropped on a void member`() {
    val doc: ForwardKdoc = requireNotNull(parseKdoc("\n Does it.\n @return nothing useful\n"))
    assertNull(doc.toCirDoc(hasResult = false)?.returns)
    assertEquals("nothing useful", doc.toCirDoc(hasResult = true)?.returns?.plain())
  }
}

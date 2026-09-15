package io.github.xxfast.kotlin.native.nuget.processor.forward

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
  fun `a multi-line comment splits into summary and tags`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc(
        "\n Books a stay.\n\n Second paragraph, dropped.\n\n @param count how many\n" +
            " @param label   a label\n @return the result\n" +
            " @throws IllegalArgumentException when count is negative\n",
      ),
    )

    assertEquals("Books a stay.", doc.summary)
    assertEquals(mapOf("count" to "how many", "label" to "a label"), doc.params)
    assertEquals("the result", doc.returns)
    assertEquals(
      listOf(ForwardKdocThrows("IllegalArgumentException", "when count is negative")),
      doc.throws,
    )
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

  @Test
  fun `property and constructor tags are dropped and never reach the summary`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc(
        "\n Summary line of Foo.\n\n @property name the name prop\n @constructor builds a Foo\n",
      ),
    )
    assertEquals("Summary line of Foo.", doc.summary)
    assertEquals(emptyMap(), doc.params)
  }

  @Test
  fun `no comment and an empty comment parse to null`() {
    assertNull(parseKdoc(null))
    assertNull(parseKdoc("  "))
  }

  @Test
  fun `exception is an alias of throws and an unmapped type crefs KotlinException`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Rehomes.\n @exception RuntimeException when closed\n"),
    )
    val cir = requireNotNull(doc.toCirDoc())
    assertEquals("KotlinException", cir.throws.single().cref)
    assertEquals("RuntimeException: when closed", cir.throws.single().text)
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
    assertEquals("when nights is not positive", cir.throws.single().text)
  }

  @Test
  fun `an undocumented parameter beside a documented one gets an empty tag`() {
    val doc: ForwardKdoc = requireNotNull(
      parseKdoc("\n Grooms.\n @param brush which brush\n"),
    )
    val cir = requireNotNull(doc.toCirDoc(listOf("brush", "gentle")))
    assertEquals(listOf("which brush", ""), cir.params.map { it.text })
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
    assertEquals("nothing useful", doc.toCirDoc(hasResult = true)?.returns)
  }
}

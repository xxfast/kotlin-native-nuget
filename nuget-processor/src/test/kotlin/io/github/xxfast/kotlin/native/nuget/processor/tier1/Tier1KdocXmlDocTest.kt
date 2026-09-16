package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-150: an exported declaration's KDoc becomes an XML doc comment on its generated C#
 * declaration.
 *
 * Structural, not runtime: reflection cannot see a doc comment (the same reason ADR-064's
 * `<remarks>` amendment is asserted on the rendered text), so the assertions are on the `///`
 * lines of `Interop.cs`. The consumer-side proof that the compiler accepts them is
 * `GeneratedBindingsCheck` (warnings as errors) plus `IntegrationTests/XmlDocTests.cs`.
 */
class Tier1KdocXmlDocTest {

  private val fixture: String = """
    package tier1.kdoc

    /** A cattery desk. */
    class Desk {
      /**
       * Books a stay.
       *
       * Second paragraph, dropped in v1.
       *
       * @param nights how many nights
       * @param suite which suite
       * @return the booking reference
       * @throws IllegalArgumentException when nights is not positive
       */
      fun book(nights: Int, suite: String = "budget"): String = "ref"

      /**
       * Grooms the cat.
       *
       * @param brush which brush
       */
      fun groom(brush: String, gentle: Boolean): String = brush

      /**
       * Rehomes the cat.
       *
       * @throws RuntimeException when the desk is closed
       */
      fun rehome(): String = "home"

      /** @suppress */
      fun internalOnly(): Int = 0
    }

    /** A mood. */
    enum class Mood { HAPPY }
  """.trimIndent()

  @Test
  fun `a documented method renders summary, params, returns and the mapped exception`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertTrue(generated.contains("/// <summary>A cattery desk.</summary>"), generated)
    assertTrue(
      generated.contains(
        "        /// <summary>Books a stay.</summary>\n" +
            "        /// <param name=\"nights\">how many nights</param>\n" +
            "        /// <param name=\"suite\">which suite</param>\n" +
            "        /// <returns>the booking reference</returns>\n" +
            "        /// <exception cref=\"KotlinArgumentException\">" +
            "when nights is not positive</exception>\n",
      ),
      generated,
    )
    assertFalse(generated.contains("Second paragraph"), generated)
  }

  @Test
  fun `the omitting overload drops the omitted param tag and keeps the rest`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertTrue(
      generated.contains(
        "        /// <summary>Books a stay.</summary>\n" +
            "        /// <param name=\"nights\">how many nights</param>\n" +
            "        /// <returns>the booking reference</returns>\n",
      ),
      generated,
    )
  }

  @Test
  fun `an undocumented parameter beside a documented one gets an empty tag`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertTrue(
      generated.contains(
        "        /// <param name=\"brush\">which brush</param>\n" +
            "        /// <param name=\"gentle\"></param>\n",
      ),
      generated,
    )
  }

  @Test
  fun `an unmapped throws type crefs KotlinException and keeps the Kotlin spelling`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertTrue(
      generated.contains(
        "        /// <exception cref=\"KotlinException\">" +
          "RuntimeException: when the desk is closed</exception>",
      ),
      generated,
    )
  }

  @Test
  fun `a suppressed member carries no doc comment`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertFalse(generated.contains("/// <summary></summary>"), generated)
    val suppressed: Int = generated.indexOf("public int InternalOnly()")
    assertTrue(suppressed > 0, generated)
    assertFalse(generated.substring(suppressed - 60, suppressed).contains("///"), generated)
  }

  @Test
  fun `an enum summary lands on the enum and not on its synthetic members`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertTrue(
      generated.contains("/// <summary>A mood.</summary>\n    public enum Mood"),
      generated,
    )
    assertEquals(1, generated.split("/// <summary>A mood.</summary>").size - 1, generated)
  }

  @Test
  fun `an expect's KDoc documents the bare actual's generated class`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Fixture.kt" to """
          package tier1.kdocexpect

          actual class SunSpot {
            actual fun sunbeam(): String = "warm"
          }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "SunSpotCommon.kt" to """
          package tier1.kdocexpect

          /** Oreo's sunny window perch. */
          expect class SunSpot {
            /** How warm it is. */
            fun sunbeam(): String
          }
        """.trimIndent(),
      ),
    )
    val generated: String = result.generatedCSharp

    assertTrue(
      generated.contains("/// <summary>Oreo's sunny window perch.</summary>"),
      generated,
    )
    assertTrue(generated.contains("/// <summary>How warm it is.</summary>"), generated)
  }

  /**
   * The second fixture: one documented declaration per remaining family, so each family's cell
   * asserts its own `///` block against the same run.
   */
  private val families: String = """
    package tier1.kdoc

    /** A perch. */
    class Perch(val height: Int) {
      /** How high it sits. */
      val level: Int get() = height

      /**
       * Builds a perch from a label.
       *
       * @param label the label to measure
       */
      constructor(label: String) : this(label.length)

      /**
       * Naps for a while.
       *
       * @param minutes how long to nap
       * @return what happened
       */
      suspend fun nap(minutes: Int): String = "napped"
    }

    /** The registry of perches. */
    object PerchRegistry {
      /** How many are registered. */
      val count: Int get() = 3

      /**
       * Finds one.
       *
       * @param label which one
       * @return its height
       */
      fun find(label: String): Int = label.length
    }

    /** A height in centimetres. */
    value class Centimetres(val value: Int) {
      /** Twice as high. */
      fun doubled(): Int = value * 2
    }

    /** Anything that can be perched on. */
    interface Perchable {
      /** How wide it is. */
      val width: Int

      /**
       * Perches on it.
       *
       * @return a note
       */
      fun perch(): String
    }

    /**
     * Greets a perch.
     *
     * @param name who to greet
     * @return the greeting
     */
    fun greetPerch(name: String): String = "hi " + name

    /**
     * Doubles a perch's height.
     *
     * @return the doubled height
     */
    fun Perch.doubledHeight(): Int = height * 2

    /** The weather out the window. */
    enum class Weather {
      /** Sunny days. */
      SUNNY,
      RAINY
    }

    /** A kind of snooze. */
    sealed class Snooze {
      /** A short one. */
      data class Catnap(val minutes: Int) : Snooze() {
        /**
         * How it felt.
         *
         * @return the feeling
         */
        fun feels(): String = "short"
      }
    }
  """.trimIndent()

  /**
   * The doc block and the declaration it documents are adjacent lines, so "the text between this
   * summary and the next blank line" is exactly the block plus its declaration.
   */
  private fun assertDocuments(generated: String, summary: String, declaration: String) {
    val start: Int = generated.indexOf("/// <summary>$summary</summary>")
    assertTrue(start > 0, "no `<summary>$summary</summary>` in:\n$generated")
    val block: String = generated.substring(start).substringBefore("\n\n")
    assertTrue(
      block.contains(declaration),
      "`<summary>$summary</summary>` does not document `$declaration`; block was:\n$block",
    )
  }

  @Test
  fun `a property carries its own summary`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "How high it sits.", "Level")
  }

  @Test
  fun `a secondary constructor carries its summary and params`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "Builds a perch from a label.", "public Perch(string label)")
    assertTrue(
      generated.contains("/// <param name=\"label\">the label to measure</param>"),
      generated,
    )
  }

  @Test
  fun `a suspend function's Async projection carries the same doc`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "Naps for a while.", "NapAsync")
    assertTrue(generated.contains("/// <param name=\"minutes\">how long to nap</param>"), generated)
    assertTrue(generated.contains("/// <returns>what happened</returns>"), generated)
  }

  @Test
  fun `an object and its members carry their summaries`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "The registry of perches.", "PerchRegistry")
    assertDocuments(generated, "Finds one.", "Find")
    // The object's `val count` has no C# surface at all today (`CirObject` carries methods only),
    // which is a pre-existing gap, not a doc one: there is no declaration to document.
    assertFalse(generated.contains("How many are registered."), generated)
  }

  @Test
  fun `a value class struct and its member carry their summaries`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "A height in centimetres.", "Centimetres")
    assertDocuments(generated, "Twice as high.", "Doubled")
  }

  @Test
  fun `an interface and its members carry their summaries`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "Anything that can be perched on.", "IPerchable")
    assertDocuments(generated, "How wide it is.", "Width")
    assertDocuments(generated, "Perches on it.", "Perch()")
  }

  @Test
  fun `a top-level function carries its summary, params and returns`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "Greets a perch.", "GreetPerch")
    assertTrue(generated.contains("/// <param name=\"name\">who to greet</param>"), generated)
    assertTrue(generated.contains("/// <returns>the greeting</returns>"), generated)
  }

  @Test
  fun `an extension function carries its summary`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "Doubles a perch's height.", "DoubledHeight")
  }

  @Test
  fun `an enum entry carries its own summary`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "Sunny days.", "Sunny")
  }

  @Test
  fun `a sealed arm and its member carry their summaries`() {
    val generated: String = Tier1Harness.run(families).generatedCSharp
    assertDocuments(generated, "A short one.", "Catnap")
    assertDocuments(generated, "How it felt.", "Feels")
  }
}

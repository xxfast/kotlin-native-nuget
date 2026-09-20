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
   * ADR-074/ADR-150: the `expect` half of the expect-family fixture. One documented declaration
   * per mechanism that can only reach its doc through `ExpectIndex`, because the `actual` the
   * generator exports is deliberately bare.
   *
   * The file is named `SunPatch.kt`, not after any declaration in it: a top-level `actual` takes
   * its C# holder from the *expect's* file name (ADR-074 Decision 3), and a file stem claimed by a
   * function would be renamed `...Kt`.
   */
  private val expectFamiliesCommon: String = """
    package tier1.kdocexpectfamilies

    /** Oreo's sunny window perch. */
    expect class SunSpot() {
      /** How warm the perch is right now. */
      val warmth: String

      /**
       * Naps in the sun.
       *
       * @param minutes how long to nap
       * @return what happened
       */
      suspend fun nap(minutes: Int): String
    }

    /**
     * Finds where a cat is basking.
     *
     * @param cat which cat
     * @return the sunny spot
     */
    expect fun basking(cat: String): String

    /**
     * Finds who has basked this long.
     *
     * @param minutes how long
     * @return who was found
     */
    expect fun basking(minutes: Int): String

    /**
     * Purrs about a cat.
     *
     * @param mood the cat's mood
     * @return the purr
     */
    expect fun purring(mood: String): String

    /**
     * Purrs a number of times.
     *
     * @param mood how many purrs
     * @return the purr
     */
    expect fun purring(mood: Int): String

    /** The label stitched onto Oreo's favourite perch. */
    expect val baskingTag: String

    /**
     * Stretches out across the whole spot.
     *
     * @return how the stretch went
     */
    expect fun SunSpot.stretch(): String

    /** Where the whole household suns itself. */
    expect object SunLounge {
      /**
       * How many are lounging.
       *
       * @return the count
       */
      fun loungers(): Int
    }

    /** Anything with slats to sun through. */
    expect interface SunPorch {
      /** How many slats it has. */
      val slats: Int
    }
  """.trimIndent()

  /** The bare per-target half: not one KDoc anywhere, so every assertion below is the expect's. */
  private val expectFamiliesActual: String = """
    package tier1.kdocexpectfamilies

    actual class SunSpot {
      actual val warmth: String = "toasty"
      actual suspend fun nap(minutes: Int): String = "napped"
    }

    actual fun basking(cat: String): String = cat
    actual fun basking(minutes: Int): String = "m"
    actual fun purring(mood: String): String = mood
    actual fun purring(mood: Int): String = "p"
    actual val baskingTag: String = "perch"
    actual fun SunSpot.stretch(): String = "stretched"

    actual object SunLounge {
      actual fun loungers(): Int = 2
    }

    actual interface SunPorch {
      actual val slats: Int
    }

    /**
     * How many minutes Mylo napped in the sun, when anyone was counting.
     *
     * @return the minutes, or null when nobody kept count
     */
    fun sunnyNapMinutes(): Int? = 14
  """.trimIndent()

  private fun expectFamilies(): String = Tier1Harness.run(
    sources = mapOf("SunPatchTarget.kt" to expectFamiliesActual),
    commonSources = mapOf("SunPatch.kt" to expectFamiliesCommon),
  ).generatedCSharp

  @Test
  fun `overloaded expect funs each take their own expect's doc`() {
    val generated: String = expectFamilies()

    // The discriminating pair: same parameter count, different parameter names AND types. A
    // lookup keyed by name alone, or by count alone, puts one overload's text on the other.
    assertDocuments(generated, "Finds where a cat is basking.", "Basking(string cat)")
    assertTrue(generated.contains("/// <param name=\"cat\">which cat</param>"), generated)
    assertTrue(generated.contains("/// <returns>the sunny spot</returns>"), generated)
    assertDocuments(generated, "Finds who has basked this long.", "Basking(int minutes)")
    assertTrue(generated.contains("/// <param name=\"minutes\">how long</param>"), generated)
    // ADR-074 Decision 3: the holder is named from the *expect's* file, not the target's.
    assertTrue(generated.contains("class SunPatch"), generated)
  }

  @Test
  fun `expect fun overloads differing only in parameter type take their own docs`() {
    val generated: String = expectFamilies()

    // Same parameter NAME on both halves, so only the positional *type* can tell them apart: a
    // names-only signature key would match both and resolve to `null` (or to the wrong one).
    assertDocuments(generated, "Purrs about a cat.", "Purring(string mood)")
    assertTrue(generated.contains("/// <param name=\"mood\">the cat's mood</param>"), generated)
    assertDocuments(generated, "Purrs a number of times.", "Purring(int mood)")
    assertTrue(generated.contains("/// <param name=\"mood\">how many purrs</param>"), generated)
  }

  @Test
  fun `a documented expect val and expect class property keep their summaries`() {
    val generated: String = expectFamilies()

    // The property planner was built with no expect index at all, so both of these rendered
    // undocumented while the `expect class` cell above stayed green.
    assertDocuments(generated, "The label stitched onto Oreo's favourite perch.", "BaskingTag")
    assertDocuments(generated, "How warm the perch is right now.", "Warmth")
  }

  @Test
  fun `a documented expect extension function keeps its summary`() {
    val generated: String = expectFamilies()

    // `ExpectIndex.functionOrNull` used to return `null` for any extension, so this was lost.
    assertDocuments(generated, "Stretches out across the whole spot.", "Stretch")
    assertTrue(generated.contains("/// <returns>how the stretch went</returns>"), generated)
  }

  /**
   * ADR-096/ADR-074: making the expect lookup receiver-aware could have widened more than docs,
   * because an omitting overload is *generated API*, not a comment. Measured here rather than
   * assumed, and the measurement is that it does NOT: the extension route reads the exported
   * declaration's own `hasDefault` bits and never consults the index at all (ADR-096: "No other
   * route consults it in v1; class/object/companion/extension read the exported declaration's own
   * bit only", `topLevelDefaultFlags` is the sole caller and runs on the top-level route).
   *
   * So the cell pins BOTH halves: the doc now arrives, and the export set is unchanged — exactly
   * one export, no `_2`, no parameterless C# overload. If someone later wires the expect index
   * into the extension defaults route, this cell fails and that becomes a deliberate decision with
   * its own ABI review rather than a side effect of a doc fix.
   */
  @Test
  fun `an expect extension's default parameter does not add an omitting overload`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "StretchTarget.kt" to """
          package tier1.kdocexpectdefault

          actual class SunSpot {
            actual fun sunbeam(): String = "warm"
          }

          actual fun SunSpot.stretchFor(minutes: Int): String = "stretched " + minutes
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Stretch.kt" to """
          package tier1.kdocexpectdefault

          /** Oreo's sunny window perch. */
          expect class SunSpot() {
            fun sunbeam(): String
          }

          /**
           * Stretches out for a while.
           *
           * @param minutes how long to stretch
           * @return how the stretch went
           */
          expect fun SunSpot.stretchFor(minutes: Int = 5): String
        """.trimIndent(),
      ),
    )
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp

    // No `Forward ABI mismatch` and no skip. `compiledClean` is deliberately NOT asserted: the
    // harness compiles both source roots as one non-multiplatform module, so every
    // `expect`/`actual` fixture reports "declared in the same module" regardless of this feature.
    // The proof that the truncated call site compiles is `test-library` under `scripts/verify.sh`.
    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")

    assertTrue(kotlin.contains("@CName(\"sunspot_stretchFor\")"), kotlin)
    // The ABI is unchanged by the widening: one export, and no truncated call site to compile.
    assertFalse(kotlin.contains("sunspot_stretchFor_2"), kotlin)
    assertFalse(kotlin.contains(".stretchFor()"), kotlin)

    assertTrue(cs.contains("StretchFor(this global::Interop.SunSpot receiver, int minutes)"), cs)
    assertFalse(cs.contains("StretchFor(this global::Interop.SunSpot receiver)"), cs)
    // ADR-150 rides along: the doc is on both overloads, and the omitting one drops the `@param`
    // it does not declare (a `<param>` naming nothing is CS1572, fatal downstream).
    assertDocuments(cs, "Stretches out for a while.", "StretchFor")
    assertTrue(cs.contains("/// <param name=\"minutes\">how long to stretch</param>"), cs)
    // The generated `this` receiver is a C# parameter of this member too, so it needs a tag of its
    // own the moment `minutes` has one: a partial set is CS1573, fatal under
    // `GeneratedBindingsCheck` (measured — it failed `scripts/verify.sh` before this was fixed).
    assertTrue(cs.contains("/// <param name=\"receiver\"></param>"), cs)
  }

  @Test
  fun `a documented expect object and expect interface keep their summaries`() {
    val generated: String = expectFamilies()

    assertDocuments(generated, "Where the whole household suns itself.", "SunLounge")
    assertDocuments(generated, "How many are lounging.", "Loungers")
    assertDocuments(generated, "Anything with slats to sun through.", "ISunPorch")
    assertDocuments(generated, "How many slats it has.", "Slats")
  }

  @Test
  fun `a documented suspend member of an expect class keeps its summary`() {
    val generated: String = expectFamilies()

    assertDocuments(generated, "Naps in the sun.", "NapAsync")
    assertTrue(generated.contains("/// <param name=\"minutes\">how long to nap</param>"), generated)
  }

  @Test
  fun `a top-level function returning a nullable primitive is documented`() {
    val generated: String = expectFamilies()

    // Deliberately NOT an `expect`: the ADR-002 two-call route carried no doc for ANY top-level
    // `fun f(): Int?`, so this isolates the route from the expect index.
    assertDocuments(
      generated,
      "How many minutes Mylo napped in the sun, when anyone was counting.",
      "SunnyNapMinutes",
    )
    assertTrue(
      generated.contains("/// <returns>the minutes, or null when nobody kept count</returns>"),
      generated,
    )
  }

  @Test
  fun `a documented expect enum keeps its own and its entry's summaries`() {
    val generated: String = Tier1Harness.run(
      sources = mapOf(
        "WeatherTarget.kt" to """
          package tier1.kdocexpectenum

          actual enum class Weather { SUNNY, RAINY }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Weather.kt" to """
          package tier1.kdocexpectenum

          /** The weather out the window. */
          expect enum class Weather {
            /** Sunny days. */
            SUNNY,
            RAINY
          }
        """.trimIndent(),
      ),
    ).generatedCSharp

    assertDocuments(generated, "The weather out the window.", "Weather")
    assertDocuments(generated, "Sunny days.", "Sunny")
  }

  @Test
  fun `a documented expect value class and expect sealed class keep their summaries`() {
    val generated: String = Tier1Harness.run(
      sources = mapOf(
        "WhiskersTarget.kt" to """
          package tier1.kdocexpectshapes

          actual value class Whiskers(actual val length: Int)

          actual sealed class Snooze {
            data class Catnap(val minutes: Int) : Snooze()
          }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Whiskers.kt" to """
          package tier1.kdocexpectshapes

          /** A whisker length. */
          expect value class Whiskers(val length: Int)

          /** A kind of snooze. */
          expect sealed class Snooze
        """.trimIndent(),
      ),
    ).generatedCSharp

    assertDocuments(generated, "A whisker length.", "Whiskers")
    assertDocuments(generated, "A kind of snooze.", "Snooze")
  }

  @Test
  fun `an actual's own KDoc wins over the expect's`() {
    val generated: String = Tier1Harness.run(
      sources = mapOf(
        "NuzzleTarget.kt" to """
          package tier1.kdocactualwins

          /** What the mingw cat actually does. */
          actual fun nuzzle(): String = "nuzzled"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Nuzzle.kt" to """
          package tier1.kdocactualwins

          /** What a cat is supposed to do. */
          expect fun nuzzle(): String
        """.trimIndent(),
      ),
    ).generatedCSharp

    // `forwardKdoc` is `docString ?: expects.docOrNull(this)`, and BOTH arms are live: measured
    // 2026-09-20, an `actual` that carries its own KDoc reports it, contradicting ADR-150 spike 1
    // finding 5's "always null today".
    assertDocuments(generated, "What the mingw cat actually does.", "Nuzzle")
    assertFalse(generated.contains("What a cat is supposed to do."), generated)
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
    // ROADMAP Phase 4: the object's `val count` is now a static C# property, and it carries its
    // KDoc through the same `plan.doc` path a class property's does.
    assertDocuments(generated, "How many are registered.", "Count")
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

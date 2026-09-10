package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-075 amendment (2026-09-10): an exported base class's *unimplemented* `abstract val` /
 * `abstract var` renders as a C# abstract property, so a subclass's `override` compiles.
 *
 * Before this, the property route had no abstract path at all: the member was planned like any
 * other and rendered as a concrete, non-virtual property with a live getter body, so the
 * subclass's `override` failed `CS0506` (not the `CS0115` the gap note claimed; the member was
 * present in C#, just not overridable).
 *
 * The abstract *method* walk is a separate hole with its own backlog item and is not exercised
 * here: the fixture's `describe()` is an ordinary final `fun`.
 */
class Tier1AbstractPropertyTest {

  @Test
  fun `abstract val and var on a base class render as abstract C sharp properties`() {
    val result = Tier1Harness.run(
      """
      package tier1.abstractproperty

      abstract class Instrument(val name: String) {
        abstract val family: String
        abstract var tuning: String
        fun describe(): String = "${'$'}name (${'$'}family, tuned ${'$'}tuning)"
      }

      class Violin : Instrument("violin") {
        override val family: String = "strings"
        override var tuning: String = "G-D-A-E"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected Instrument/Violin to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public abstract string Family { get; }",
      message = "expected the unimplemented `abstract val` to render abstract and get-only; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public abstract string Tuning { get; set; }",
      message = "expected the unimplemented `abstract var` to render abstract with a setter; " +
          "generatedCSharp:\n$csharp",
    )
    assertFalse(
      "Family => " in csharp,
      "an abstract property must have no body on the base; generatedCSharp:\n$csharp",
    )
    assertFalse(
      "abstract virtual" in csharp,
      "`abstract virtual` is CS0503; generatedCSharp:\n$csharp",
    )

    // Control: the concrete constructor property on the same abstract base keeps its ordinary
    // body-carrying projection.
    assertContains(
      csharp,
      "public string Name",
      message = "expected the concrete property on the abstract base to keep its getter; " +
          "generatedCSharp:\n$csharp",
    )
    assertFalse(
      "abstract string Name" in csharp,
      "an implemented property on an abstract base stays concrete; generatedCSharp:\n$csharp",
    )

    // The subclass side is unchanged: it still overrides both members.
    assertContains(csharp, "public override string Family")
    assertContains(csharp, "public override string Tuning")
  }

  /**
   * The plan and its export pair survive: the abstract base keeps a `Native_Get_family` DllImport
   * even though nothing on the abstract class calls it. Suppressing the export while keeping the
   * plan would make `ForwardAbiContract` report the Kotlin projection missing a planned name.
   */
  @Test
  fun `an abstract property keeps its planned import pair on the base`() {
    val result = Tier1Harness.run(
      """
      package tier1.abstractpropertyimports

      abstract class Instrument(val name: String) {
        abstract var tuning: String
      }

      class Violin : Instrument("violin") {
        override var tuning: String = "G-D-A-E"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected Instrument/Violin to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertContains(csharp, "instrument_get_tuning")
    assertContains(csharp, "instrument_set_tuning")
  }
}

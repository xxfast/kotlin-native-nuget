package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-115 amendment (alternative 3): `publish { exportMarkers("com.x.ExperimentalFooApi") }` waives
 * one named `@RequiresOptIn` marker, so every declaration behind it exports through the ordinary
 * route exactly as if the marker were not written.
 *
 * The counterpart to [Tier1OptInMarkerSkipTest], which owns the default (drop everything marked).
 * What only Tier 1 can see: that no `SKIPPED_OPT_IN_MARKER` fires for the waived marker, that an
 * unlisted control marker still fires one, and `compiledClean`. That last one is load-bearing and
 * not incidental: a waived `ERROR`-level marker makes `CNameExports.kt` read a marked declaration
 * again, so the generated file's own `@OptIn` list has to name the waived marker or the generated
 * Kotlin stops compiling. That is issue #113's original failure, re-entered from the other side.
 */
class Tier1OptInMarkerExportTest {

  private val markers: String =
    """
    @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "still settling")
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
    annotation class ExperimentalDiet

    @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "internal")
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
    annotation class InternalApi
    """.trimIndent()

  @Test
  fun `a member behind a waived marker exports with no SKIPPED_OPT_IN_MARKER`() {
    val result = Tier1Harness.run(
      """
      package tier1.optin.waived

      $markers

      class Nutritionist {
        fun plainName(): String = "Mylo"
        @ExperimentalDiet fun dietName(): String = "kibble"
        @InternalApi fun ledgerName(): String = "ledger"
      }
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.exportMarkers" to "tier1.optin.waived.ExperimentalDiet",
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "DietName" in result.generatedCSharp,
      "the waived member exports through the ordinary route; " +
          "generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "PlainName" in result.generatedCSharp,
      "the unmarked sibling is unaffected; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("dietName")
      },
      "a waived declaration is treated as carrying no marker at all, so it is not diagnosed; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("tier1.optin.waived.InternalApi")
      },
      "the control marker is not on the list and still skips; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      "LedgerName" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `a waived ERROR level marker keeps the generated Kotlin compiling`() {
    // The whole class is marked, so `CNameExports.kt` names an `ERROR`-marked type in every
    // export it renders for it. Without the marker in the generated file's `@OptIn` list this is
    // "This declaration needs opt-in" and nothing in the package builds.
    val result = Tier1Harness.run(
      """
      package tier1.optin.waivedclass

      $markers

      @ExperimentalDiet
      class Nutritionist(val name: String) {
        fun plan(): String = "kibble"
      }
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.exportMarkers" to "tier1.optin.waivedclass.ExperimentalDiet",
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "class Nutritionist" in result.generatedCSharp,
      "a waived marker waives the type it marks too; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "Plan" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `an unset export marker list keeps the shipped default`() {
    val result = Tier1Harness.run(
      """
      package tier1.optin.unset

      $markers

      class Nutritionist {
        @ExperimentalDiet fun dietName(): String = "kibble"
      }
      """.trimIndent(),
    )

    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("tier1.optin.unset.ExperimentalDiet")
      },
      "no list means no waiver; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      "DietName" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
  }
}

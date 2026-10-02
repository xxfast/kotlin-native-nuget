package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-181: `@ExperimentalNugetBindingApi` is the reverse direction's opt-in marker, and the forward
 * processor waives it automatically, with no `publish { exportMarkers(...) }` entry.
 *
 * The marker describes the stability of the Kotlin-side binding, not of the author's forward API,
 * so an author who propagates it (`@ExperimentalNugetBindingApi fun adopt(f: IFeedable)`) instead
 * of opting in keeps the ADR-088 export. `compiledClean` is load-bearing: the marker is `ERROR`
 * level, so the generated `CNameExports.kt` reading a marked declaration compiles only because its
 * `@file:OptIn` names the marker unconditionally.
 */
class Tier1BindingMarkerExemptionTest {

  @Test
  fun `a declaration behind the binding marker exports with no SKIPPED_OPT_IN_MARKER`() {
    val result = Tier1Harness.run(
      """
      package tier1.optin.binding

      import io.github.xxfast.kotlin.native.nuget.annotations.ExperimentalNugetBindingApi

      @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "internal")
      @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
      annotation class InternalApi

      @ExperimentalNugetBindingApi
      class Kennel(val name: String) {
        fun describe(): String = name
      }

      @ExperimentalNugetBindingApi
      fun adopt(kennel: Kennel): String = kennel.name

      class Farm {
        @InternalApi fun ledgerName(): String = "ledger"
      }
      """.trimIndent(),
      libraries = listOf(csharpNameLibrary),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "class Kennel" in result.generatedCSharp,
      "a type behind the binding marker exports; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "Describe" in result.generatedCSharp && "Adopt" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("ExperimentalNugetBindingApi")
      },
      "the binding marker is waived without an exportMarkers entry; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) &&
            it.contains("tier1.optin.binding.InternalApi")
      },
      "any other marker still skips; kspWarnings=${result.kspWarnings}",
    )
    assertFalse("LedgerName" in result.generatedCSharp, result.generatedCSharp)
  }
}

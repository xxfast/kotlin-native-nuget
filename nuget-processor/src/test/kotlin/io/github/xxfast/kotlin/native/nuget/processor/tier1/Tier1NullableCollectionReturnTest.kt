package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-061 (2026-09-16 amendment): a nullable collection *method* return binds over the same
 * POINTER slot a nullable object handle already uses, with a null pointer standing for Kotlin
 * `null`. Before the amendment the planner had no `nullableResultShape` branch for
 * `BridgeType.Collection`, so the whole callable was dropped with a `SKIPPED_UNSUPPORTED_RETURN`
 * naming its "NULLABLE type combination" instead. The two halves asserted here are exactly the
 * two things that were missing: the member exists at all, and the C# side guards the handle for
 * zero before materializing.
 */
class Tier1NullableCollectionReturnTest {

  @Test
  fun `a nullable list method return binds instead of skipping`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectionreturn

      class Dispensary(val stocked: Boolean) {
        fun aliases(): List<String>? = if (stocked) listOf("biscuit", "milo") else null
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected export_dispensary_aliases to compile; got: ${result.compileErrors}",
    )

    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("SKIPPED_") },
      "expected no skip for a nullable collection return; kspWarnings=${result.kspWarnings}",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public IReadOnlyList<string>? Aliases()")
    assertContains(cs, "if (listHandle == IntPtr.Zero) return null;")
  }

  /**
   * The other half of the amendment: admitting `List<T>?` must not admit a `T` the non-nullable
   * arm already refuses. An out-of-scope element still skips, under the same reason its
   * non-nullable sibling gets (both now run the element's own `skipReason()`), never the NULLABLE
   * bucket whose "expose a non-nullable wrapper" hint would send the author after a fix that
   * cannot work.
   */
  @Test
  fun `a nullable list with an out-of-scope element skips under the element's own reason`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Dispensary.kt" to """
          package tier1.nullablecollectionelement.api

          import tier1.nullablecollectionelement.secret.Secret

          class Dispensary(val stocked: Boolean) {
            fun archive(): List<Secret>? = if (stocked) emptyList() else null
          }
        """.trimIndent(),
        "Secret.kt" to """
          package tier1.nullablecollectionelement.secret

          class Secret(val n: Int)
        """.trimIndent(),
      ),
      processorOptions = mapOf(
        "nuget.includePackages" to "tier1.nullablecollectionelement.api",
      ),
    )

    assertTrue(
      result.kspErrors.isEmpty(),
      "expected generation to skip the member, not crash; kspErrors=${result.kspErrors}",
    )

    val skips: List<String> = result.kspWarnings.filter { warning -> "archive" in warning }
    assertTrue(
      skips.any { warning ->
        warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name)
      },
      "expected the element's own reason; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      skips.none { warning -> warning.contains("NULLABLE") },
      "expected no generic NULLABLE wording; kspWarnings=${result.kspWarnings}",
    )
  }
}

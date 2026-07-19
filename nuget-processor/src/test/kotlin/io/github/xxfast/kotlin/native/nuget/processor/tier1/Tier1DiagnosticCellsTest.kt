package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-060 obs class **B** — cell 21, whose correct end state is a fail-fast diagnostic, never
 * working code. `KSPLogger` is supplied directly by [Tier1Harness] (via [RecordingKSPLogger]),
 * so this is the **diagnostic** assertion mode: it asserts on [Tier1Result.kspErrors], the third
 * mode the harness produces for free (ADR-060 "Three assertion modes fall out of one harness").
 */
class Tier1DiagnosticCellsTest {

  /**
   * Cell 21 · obs B. Secondary constructor × object-typed parameter. `mapParamType`'s `?:
   * "IntPtr"` fallthrough renders the secondary constructor `(IntPtr)`, colliding with the
   * `internal Referral(IntPtr handle)` the renderer always emits (`CirClassRenderer.kt:62`) —
   * verified in the real generated `Interop.cs`: both `public Referral(IntPtr from)` and
   * `internal Referral(IntPtr handle)` share the identical `(IntPtr)` signature, `CS0111` if
   * compiled. ADR-034 already decided this class of collision is a fail-fast
   * (`CirClassTranslator.kt:93-98` calls `logger.error`), but that check's signature set
   * excludes the handle constructor it collides with (`:89-92`), so it does not fire for this
   * shape today — `KSP2` reports exit `OK` with an empty error list, and both the Kotlin and the
   * (uncompiled) C# are produced regardless.
   *
   * This can never become "working code": once the fix adds the handle constructor to ADR-034's
   * collision set, `class Referral { constructor(from: Patient) }` fails generation *by design*.
   * So this is a permanent Tier 1 diagnostic cell, the same shape of thing as cell 23 (its
   * correct end state is also "never generated", not "generates something valid").
   *
   * ADR-064 names this fatal case `ERROR_CSHARP_SIGNATURE_COLLISION`. Generation must fail
   * (`kspErrors` non-empty) *before* any invalid `Interop.cs` is written — unchanged ADR-034
   * policy, now under the named kind.
   */
  @Test
  @XFail(
    "ADR-060 cell 21 - secondary ctor object param collision is not caught by ADR-034's fail-fast",
  )
  fun `cell 21 - secondary constructor colliding with the handle constructor is reported`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell21

      class Patient(val name: String)

      class Referral(val note: String) {
        constructor(from: Patient) : this("referred by ${'$'}{from.name}")
      }
      """.trimIndent()
    )

    assertTrue(
      result.kspErrors.any { it.contains("Referral") },
      "expected ADR-034's fail-fast to report the Referral(Patient) constructor colliding " +
          "with the always-emitted handle constructor; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) },
      "expected the fatal diagnostic to carry the named kind " +
          "ERROR_CSHARP_SIGNATURE_COLLISION, not just an unstructured message; " +
          "kspErrors=${result.kspErrors}",
    )
  }

  /**
   * Cell 23 · obs K, ADR-064's second permanent Tier 1 diagnostic cell. See
   * [Tier1CompileCellsTest] for the still-compile-mode version of this assertion (kept there
   * until ADR-064 lands, per that test's own doc comment) and the full mechanism writeup. Placed
   * here too, non-`@XFail`, because — unlike cell 21 — nothing here is expected to keep failing
   * once `ForwardDiagnosticKind` exists and the reclassification lands: the "no broken source"
   * half is *already* true (Phase 10), so once the SUSPEND-for-extension-with-no-legacy-route
   * case is reclassified as a genuine drop and given a diagnostic, this test goes green with no
   * further generator change needed for *this* cell.
   */
  @Test
  fun `cell 23 - suspend inline reified extension returning Result fires SKIPPED_UNSUPPORTED_COMBINATION`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell23diag

      class Patient(val name: String)

      suspend inline fun <reified T> Patient.chartEntry(entry: T): Result<T> = Result.success(entry)
      """.trimIndent()
    )

    assertTrue(
      "export_patient_chartEntry" !in result.generated,
      "expected chartEntry to be absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name) },
      "expected a SKIPPED_UNSUPPORTED_COMBINATION diagnostic naming Patient.chartEntry; " +
          "today this callable is dropped with zero diagnostic at all " +
          "(kspWarnings=${result.kspWarnings})",
    )
  }
}

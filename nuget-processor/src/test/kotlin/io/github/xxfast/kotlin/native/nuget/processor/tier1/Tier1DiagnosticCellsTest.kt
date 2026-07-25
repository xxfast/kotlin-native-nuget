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
   * Cell 21 · obs B. Secondary constructor × nullable-reference-typed parameter overload.
   *
   * The original cell-21 shape (`constructor(from: Patient)` colliding with the always-emitted
   * `internal Referral(IntPtr handle)` via `mapParamType`'s `?: "IntPtr"` fallthrough) was fixed
   * as a side effect of ADR-062: constructors now project through
   * `ForwardCirPlanProjection.constructor(...)` -> `BridgeType.csharpType()`, so an object-typed
   * parameter renders as the wrapper type (`Patient`), not `IntPtr` — verified by running that
   * exact fixture through [Tier1Harness].
   *
   * A live bug in the same family survives: ADR-034's duplicate-constructor fail-fast
   * (`CirClassTranslator.kt`) compares **raw rendered C# type strings**. C# nullable *reference*
   * annotations are not part of a method's signature, so `constructor(from: Patient)` and
   * `constructor(from: Patient?)` both render (correctly) as distinct strings `"Patient"` and
   * `"Patient?"`, the raw-string comparison sees no duplicate, and KSP happily emits both
   * `public Referral(Patient from)` and `public Referral(Patient? from)` — which is `CS0111` in
   * C#, because a nullable-reference annotation carries no signature weight there.
   *
   * ADR-064 names this fatal case `ERROR_CSHARP_SIGNATURE_COLLISION`. Generation must fail
   * (`kspErrors` non-empty) *before* any invalid `Interop.cs` is written — unchanged ADR-034
   * policy, now under the named kind. This remains a permanent Tier 1 diagnostic cell: once the
   * check normalizes reference-type nullability before comparing, this exact shape can never
   * become "working code" (the same shape of thing as cell 23).
   */
  @Test
  fun `cell 21 - secondary constructor overloading only on reference nullability is reported`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell21

      class Patient(val name: String)

      class Referral(val note: String) {
        constructor(from: Patient) : this("referred by ${'$'}{from.name}")
        constructor(from: Patient?) : this(from?.name ?: "unknown")
      }
      """.trimIndent()
    )

    assertTrue(
      result.kspErrors.any { it.contains("Referral") },
      "expected ADR-034's fail-fast to report the Referral(Patient)/(Patient?) constructor " +
          "overload colliding once C# nullable-reference annotations are normalized away; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) },
      "expected the fatal diagnostic to carry the named kind " +
          "ERROR_CSHARP_SIGNATURE_COLLISION, not just an unstructured message; " +
          "kspErrors=${result.kspErrors}",
    )
  }

  /**
   * Cell 21b · regression guard. Nullable *value* types are genuinely distinct C# signatures
   * (`int` and `int?` do not collide), unlike cell 21's nullable reference types. This must keep
   * generating both constructors with no diagnostic once the reference-nullability normalization
   * lands — it is the guard against over-normalizing value types away too.
   */
  @Test
  fun `cell 21b - secondary constructor overloading on value type nullability is not reported`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell21b

      class Counter(val note: String) {
        constructor(n: Int) : this("count ${'$'}n")
        constructor(n: Int?) : this("count ${'$'}{n ?: -1}")
      }
      """.trimIndent()
    )

    assertTrue(
      result.kspErrors.none {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected Counter(Int) and Counter(Int?) to keep generating as distinct C# signatures; " +
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

package io.github.xxfast.kotlin.native.nuget.processor.tier1

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
  }
}

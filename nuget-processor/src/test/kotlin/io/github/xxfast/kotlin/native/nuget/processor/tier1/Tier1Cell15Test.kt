package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-060 cell 15 — value class (primitive-underlying) x method-with-a-parameter, obs class
 * **K**. Seam crossed: `ValueClassExports.kt`'s primitive-underlying branch (`:161-168`) never
 * reads `method.parameters` — every method is emitted as `.methodName()` with literal empty
 * parens, no matter how many parameters the real Kotlin method takes. Defect pinned: `matches
 * (other: String)` loses `other` on the way out, so the generated export both declares zero
 * parameters *and* still calls `.matches()` with none supplied — invalid Kotlin ("no value
 * passed for parameter 'other'"), verified in ADR-060 against the real generator. `CatId
 * .isValid()` — `test-library`'s entire value-class method corpus before this cell — is
 * zero-arg, which is exactly why this was never caught (ROADMAP.md "An adversarial forward
 * fixture").
 */
class Tier1Cell15Test {
  @Test
  @XFail("ADR-060 cell 15 - value class (primitive-underlying) method drops its parameter")
  fun `cell 15 - value class method parameter survives export and the generated file compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell15

      @JvmInline
      value class ChartId(val value: String) {
        fun matches(other: String): Boolean = value == other

        // Control (ADR-060 table): the zero-arg shape that already worked, kept alongside so
        // a Tier 1 run over this fixture stays comparable to the ADR's own worked example.
        fun isValid(): Boolean = value.isNotBlank()
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_chartid_matches to carry the 'other' parameter through and the " +
          "generated CNameExports.kt to compile; got compiler errors: ${result.compileErrors}",
    )
  }
}

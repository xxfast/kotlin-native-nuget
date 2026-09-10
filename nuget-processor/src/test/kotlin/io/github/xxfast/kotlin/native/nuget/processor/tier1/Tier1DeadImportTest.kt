package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A skipped declaration must leave no `import` behind in `CNameExports.kt`. The per-declaration
 * loops in `NugetProcessor.kt` added the import *before* the plan gate, so a declaration that
 * emitted no `@CName` export still contributed a line importing a symbol the file never mentions.
 * kotlinc does not warn on an unused import, so the generated file still compiles and the harness's
 * `compileWarnings` cannot see it: the assertion has to be structural.
 *
 * The precedent is already in-tree: `ExtensionPropertyExports.kt` moved its import behind the
 * `propertyFor(...) == null` gate for exactly this reason. These cases cover the remaining loops:
 * top-level functions, top-level properties, and extension functions, each paired with a surviving
 * sibling so "no import for a skip" cannot degrade into "no import at all".
 */
class Tier1DeadImportTest {

  /**
   * `List<List<String>?>` is an unsupported input (ADR-099's nullable nested component), so `sift`
   * is skipped while `ping` is planned. Only `ping` may be imported.
   */
  @Test
  fun `skipped top-level function leaves no import behind`() {
    val result = Tier1Harness.run(
      """
      package tier1.deadfun

      fun sift(litters: List<List<String>?>): Int = litters.size

      fun ping(): Int = 1
      """.trimIndent(),
      fileName = "DeadFun.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the dead-import fixture; got: ${result.compileErrors}",
    )
    assertTrue(
      "import tier1.deadfun.ping" in result.generated,
      "expected the surviving function to keep its import; generated=${result.generated}",
    )
    assertFalse(
      "import tier1.deadfun.sift" in result.generated,
      "expected no import for the skipped function; generated=${result.generated}",
    )
  }

  /**
   * Same shape one declaration over: a top-level `val` whose type has no plan is skipped by
   * `addPropertyExports`, and the loop imported it anyway. A function type is the skip here:
   * `List<List<String>?>` reads fine as a property (it plans as an opaque handle getter), so the
   * function-fixture's unsupported *input* is not an unsupported property type.
   */
  @Test
  fun `skipped top-level property leaves no import behind`() {
    val result = Tier1Harness.run(
      """
      package tier1.deadval

      val handler: (Int) -> Int = { it }

      val count: Int = 1
      """.trimIndent(),
      fileName = "DeadVal.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the dead-import property fixture; got: " +
        "${result.compileErrors}",
    )
    assertTrue(
      "import tier1.deadval.count" in result.generated,
      "expected the surviving property to keep its import; generated=${result.generated}",
    )
    assertFalse(
      "import tier1.deadval.handler" in result.generated,
      "expected no import for the skipped property; generated=${result.generated}",
    )
  }

  /** The extension-function loop, the sibling of the already-fixed extension-property one. */
  @Test
  fun `skipped extension function leaves no import behind`() {
    val result = Tier1Harness.run(
      """
      package tier1.deadext

      fun String.sift(litters: List<List<String>?>): Int = litters.size + length

      fun String.ping(): Int = length
      """.trimIndent(),
      fileName = "DeadExt.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the dead-import extension fixture; got: " +
        "${result.compileErrors}",
    )
    assertTrue(
      "import tier1.deadext.ping" in result.generated,
      "expected the surviving extension to keep its import; generated=${result.generated}",
    )
    assertFalse(
      "import tier1.deadext.sift" in result.generated,
      "expected no import for the skipped extension; generated=${result.generated}",
    )
  }
}

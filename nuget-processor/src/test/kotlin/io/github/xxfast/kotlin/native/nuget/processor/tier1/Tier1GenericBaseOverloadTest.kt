package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 amendment (2026-09-13), sibling of [Tier1GenericBaseClassTest]: when a generic base's
 * function mentions the type parameter, KSP hands the subclass a *substituted* copy parented to
 * the subclass. `isDeclaredBy` matches the declared list by simple name only, so that substituted
 * `describe(String)` is mistaken for the subclass's own `describe(Int)` and rendered on it too.
 *
 * The subclass must declare exactly one `Describe`, the `Int` overload it really declares, with no
 * `override` (the generic base projects properties only, so there is nothing to override), and the
 * Kotlin side must mint exactly one `labelledcrate_describe` export rather than a numbered pair.
 */
class Tier1GenericBaseOverloadTest {

  @Test
  fun `a substituted generic-base function is not re-declared on the subclass overloading it`() {
    val result = Tier1Harness.run(
      """
      package tier1.genericbase.overload

      open class Crate<T>(val item: T) {
        fun describe(tag: T): String = "${'$'}tag:${'$'}item"
      }

      class LabelledCrate(item: String) : Crate<String>(item) {
        fun describe(tag: Int): String = "#${'$'}tag:${'$'}item"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the generic-base overload exports to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    val describes: Int = cs.split("public string Describe(").size - 1
    assertEquals(
      1,
      describes,
      "the subclass must render exactly one Describe, its own Int overload; got: $cs",
    )
    assertContains(cs, "public string Describe(int tag)")
    assertFalse(
      "Describe(string" in cs,
      "the substituted String overload has no C# carrier and must not be rendered; got: $cs",
    )
    assertFalse(
      "override string Describe" in cs,
      "the declared overload overrides nothing on the generic base; got: $cs",
    )
    assertFalse(
      "labelledcrate_describe_2" in cs,
      "no numbered entry point may be minted for a member the subclass does not declare; got: $cs",
    )

    val kotlin: String = result.generated
    val exports: Int = kotlin.lines()
      .count { "@CName(" in it && "labelledcrate_describe" in it }
    assertEquals(
      1,
      exports,
      "exactly one labelledcrate_describe export must be minted, with no numbered sibling; " +
          "got: $kotlin",
    )
  }
}

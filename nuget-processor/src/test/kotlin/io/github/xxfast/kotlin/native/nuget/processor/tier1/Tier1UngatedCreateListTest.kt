package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `NugetMarshal.CreateList<T>` was emitted unconditionally while the `NugetListNative` class it
 * calls into is gated on `tracker.needsList`, so a library exporting zero List/MutableList usage
 * got a `CS0103: The name 'NugetListNative' does not exist` for every reference in the factory
 * body. `CreateSet`, `CreateMap` and `ReadList` were already gated on their respective flags; this
 * is the one sibling that was not.
 */
class Tier1UngatedCreateListTest {

  /** The external report's exact shape: a collections-free library. */
  @Test
  fun `collections free file emits no list helpers`() {
    val result = Tier1Harness.run(
      """
      package tier1.nolists

      class Patient(val name: String) {
        fun greet(): String = "Hello, ${'$'}name"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source; got: ${result.compileErrors}",
    )
    // Positive control: the marshal helper is emitted at all, so the absence checks below cannot
    // pass vacuously on an empty C# file.
    assertTrue(
      result.generatedCSharp.contains("internal static class NugetMarshal"),
      "expected NugetMarshal to be emitted; generatedCSharp=${result.generatedCSharp}",
    )
    assertFalse(
      result.generatedCSharp.contains("NugetListNative"),
      "expected no reference to the ungenerated NugetListNative class; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertFalse(
      result.generatedCSharp.contains("CreateList"),
      "expected no CreateList factory in a collections-free file; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /** Over-gating control: a file that does use List keeps both the factory and the native class. */
  @Test
  fun `file using list keeps the create list factory`() {
    val result = Tier1Harness.run(
      """
      package tier1.withlists

      class Repo {
        fun tags(): List<String> = listOf("a")
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generatedCSharp.contains("public static IntPtr CreateList<T>"),
      "expected CreateList to survive for a List-using file; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.generatedCSharp.contains("internal static class NugetListNative"),
      "expected NugetListNative to be emitted for a List-using file; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }
}

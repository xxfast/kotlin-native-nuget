package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A generic class or function bounded by a Kotlin builtin (`T : Comparable<T>`, `T : Number`,
 * `T : CharSequence`, `T : Enum<T>`). C# has no spelling for any of them: the shipped
 * `where T : Number` was CS0246, `where T : global::TestLibrary.Kotlin.IComparable` CS0234 (the
 * ADR-123 builtin-package defect on the interface speller), and only `where T : Enum` compiled, by
 * binding to `System.Enum`. The bound is dropped from the `where` clause (keeping `notnull` for a
 * non-null bound) and reported as INFO_DROPPED_BOUND; the declaration still binds.
 *
 * Tier 1 never compiles the C#, so these cells pin the `where` line; the test-library fixture and
 * `BuiltinGenericBoundTests` compile and run it.
 */
class Tier1BuiltinGenericBoundTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private fun whereLines(result: Tier1Result): List<String> =
    result.generatedCSharp.lines().map { it.trim() }.filter { it.contains("where T") }

  private fun droppedBounds(result: Tier1Result): List<String> =
    result.kspWarnings.filter { it.contains(ForwardDiagnosticKind.INFO_DROPPED_BOUND.name) }

  private fun assertNoBuiltinSpelling(result: Tier1Result) {
    val builtins: List<String> =
      listOf("Comparable", "Number", "CharSequence", "Enum", "Kotlin.")
    val leaked: List<String> = whereLines(result).filter { line -> builtins.any(line::contains) }
    assertTrue(leaked.isEmpty(), "builtin bound leaked into a where clause: $leaked")
  }

  @Test
  fun `a Comparable bound is dropped to notnull on both the class and the function route`() {
    val result = Tier1Harness.run(
      """
      package tier1.builtinbound

      class Sorted<T : Comparable<T>>(val value: T)

      fun <T : Comparable<T>> pick(value: T): T = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    // The Kotlin half used to spell the bare `kotlin.Comparable` as a type argument.
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertNoBuiltinSpelling(result)
    assertContains(
      result.generatedCSharp,
      "public class Sorted<T> : IDisposable, INugetHandle where T : notnull",
    )
    assertContains(result.generatedCSharp, "public static T Pick<T>(T value) where T : notnull")
    assertContains(result.generated, "Sorted<kotlin.Comparable<Any?>>")
    assertContains(result.generated, "asStableRef<kotlin.Comparable<Any?>>()")

    val notes: List<String> = droppedBounds(result)
    assertEquals(2, notes.size, "one note per dropped bound: $notes")
    assertTrue(notes.any { it.contains("Sorted<T>") && it.contains("kotlin.Comparable") }, "$notes")
    assertTrue(notes.any { it.contains("pick<T>") && it.contains("kotlin.Comparable") }, "$notes")
  }

  @Test
  fun `a Number bound is dropped to notnull on both the class and the function route`() {
    val result = Tier1Harness.run(
      """
      package tier1.builtinbound

      class Counted<T : Number>(val value: T)

      fun <T : Number> twice(value: T): T = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertNoBuiltinSpelling(result)
    assertContains(
      result.generatedCSharp,
      "public class Counted<T> : IDisposable, INugetHandle where T : notnull",
    )
    assertContains(result.generatedCSharp, "public static T Twice<T>(T value) where T : notnull")
    assertEquals(2, droppedBounds(result).size, "${droppedBounds(result)}")
  }

  @Test
  fun `a CharSequence bound no longer renders a namespace under the root`() {
    val result = Tier1Harness.run(
      """
      package tier1.builtinbound

      class Labeled<T : CharSequence>(val value: T)
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertNoBuiltinSpelling(result)
    assertContains(
      result.generatedCSharp,
      "public class Labeled<T> : IDisposable, INugetHandle where T : notnull",
    )
    assertEquals(1, droppedBounds(result).size, "${droppedBounds(result)}")
  }

  @Test
  fun `a nullable builtin bound leaves no constraint at all but is still reported`() {
    val result = Tier1Harness.run(
      """
      package tier1.builtinbound

      class Maybe<T : Comparable<T>?>(val value: T)
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    val maybe: List<String> = whereLines(result).filter { it.contains("Maybe") }
    assertTrue(maybe.isEmpty(), "$maybe")
    assertContains(result.generatedCSharp, "public class Maybe<T> : IDisposable, INugetHandle")
    assertEquals(1, droppedBounds(result).size, "${droppedBounds(result)}")
  }

  @Test
  fun `a builtin bound beside a user interface bound leaves the user bound alone`() {
    val result = Tier1Harness.run(
      """
      package tier1.builtinbound

      interface Pet

      class Kennel<T>(val value: T) where T : Comparable<T>, T : Pet
      """.trimIndent(),
      processorOptions = options,
    )

    // C# half only: the Kotlin half spells a multi-bound owner by its first bound
    // (`Kennel<kotlin.Comparable<Any?>>`), which misses `Pet`; an intersection has no name.
    assertContains(
      result.generatedCSharp,
      "public class Kennel<T> : IDisposable, INugetHandle " +
          "where T : global::TestLibrary.Builtinbound.IPet",
    )
    assertEquals(1, droppedBounds(result).size, "${droppedBounds(result)}")
  }

  /**
   * `where T : Enum` compiled in C# by accident, but the Kotlin half has no type argument that
   * satisfies the invariant F-bound `T : Enum<T>` (`Enum<Any?>` is not an `Enum<Enum<Any?>>`),
   * so the generated Kotlin does not compile on either route. The C# side follows the same drop
   * rule; the Kotlin limit is pinned here so a fix shows up as this cell going red.
   */
  @Test
  fun `an Enum bound is dropped on the C# half and the Kotlin half remains a known limit`() {
    val result = Tier1Harness.run(
      """
      package tier1.builtinbound

      class Ranked<T : Enum<T>>(val value: T)

      fun <T : Enum<T>> first(value: T): T = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertNoBuiltinSpelling(result)
    assertContains(
      result.generatedCSharp,
      "public class Ranked<T> : IDisposable, INugetHandle where T : notnull",
    )
    assertContains(result.generatedCSharp, "public static T First<T>(T value) where T : notnull")
    assertEquals(2, droppedBounds(result).size, "${droppedBounds(result)}")
    assertTrue(
      result.compileErrors.any { it.contains("Enum") },
      "expected the known Enum<T> Kotlin-half limit; got ${result.compileErrors}",
    )
  }
}

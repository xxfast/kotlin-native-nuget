package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Issue #222 / ADR-148 crossed with ADR-105 scope (d), the sealed mirror of
 * [Tier1SealedParameterPositionTest]. An arm's constructor is planned through the very same
 * `constructorEntries` an ordinary class's is, so a sealed-typed parameter on it takes the same
 * `sealedAsHandle()` rewrite: it crosses as the instance handle the generated abstract C# base
 * carries, and Kotlin reads it back with `asStableRef<Shape>().get()`.
 *
 * This is the cell that would break first if the arm's constructors were given a route of their
 * own instead of reusing the class one, because the rewrite lives in the shared planner and
 * nowhere else.
 *
 * Oreo sits on whatever shape is nearest, then declares it his.
 */
class Tier1SealedSubclassConstructorSealedParameterTest {

  private val source: String =
    """
    package tier1.armctorsealed

    sealed class Shape {
      data object Empty : Shape()
      data class Circle(val radius: Int) : Shape()
      data class Framed(val inner: Shape, val label: String) : Shape()
    }

    class Board {
      fun describe(shape: Shape): String = shape.toString()
    }
    """.trimIndent()

  @Test
  fun `an arm constructor taking the sealed base crosses as the instance handle`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    // C#: the mapped base type at the arm's constructor, not an IntPtr and not a skip.
    assertContains(
      result.generatedCSharp,
      "public Framed(global::Interop.Shape inner, string label) : base(IntPtr.Zero, out _)",
    )
    // Kotlin: the same ADR-105 read-back an ordinary class's constructor gets.
    assertContains(result.generated, "inner.asStableRef<tier1.armctorsealed.Shape>().get()")
    assertContains(result.generated, "@CName(\"library_tier1_armctorsealed__shape_framed_create\")")
  }

  @Test
  fun `the arm beside it with a plain parameter is unaffected`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    assertContains(result.generatedCSharp, "public Circle(int radius) : base(IntPtr.Zero, out _)")
  }
}

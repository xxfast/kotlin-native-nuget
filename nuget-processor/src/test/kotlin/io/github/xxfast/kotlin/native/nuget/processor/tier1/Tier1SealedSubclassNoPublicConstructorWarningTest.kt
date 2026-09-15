package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #222 / ADR-148, the negative cell of [Tier1SealedSubclassConstructorTest] and the sealed
 * mirror of [Tier1NoPublicConstructorWarningTest]. "Export every arm's constructor" is not the
 * fix: an arm whose constructor parameter is opt-in-marked genuinely cannot be exported, and what
 * it owes then is the same `WARNING_NO_PUBLIC_CONSTRUCTOR` a non-subclass type already fires, with
 * the reason on it, rather than the silent drop every arm used to get.
 *
 * The arm is kept either way, exactly as an ordinary class is: a factory still hands one over and
 * its bridgeable payload still reads, so the diagnostic reports, it never removes.
 *
 * Oreo submits to a grooming plan once a week and would rather nobody knew.
 */
class Tier1SealedSubclassNoPublicConstructorWarningTest {

  private val source: String =
    """
    package tier1.armnoctor

    @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "internal")
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
    annotation class InternalApi

    @InternalApi
    enum class Grooming { DAILY, WEEKLY }

    sealed class FlatShape {
      data class Circle(val radius: Int) : FlatShape()

      @OptIn(InternalApi::class)
      class Groomed(grooming: Grooming) : FlatShape() {
        val level: Int = grooming.ordinal
      }
    }

    class FlatShapeFactory {
      @OptIn(InternalApi::class)
      fun groomed(): FlatShape.Groomed = FlatShape.Groomed(Grooming.WEEKLY)

      fun area(shape: FlatShape): Int = if (shape is FlatShape.Circle) shape.radius else 0
    }
    """.trimIndent()

  private fun warnings(result: Tier1Result): List<String> = result.kspWarnings
    .filter { it.contains(ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR.name) }

  @Test
  fun `an arm whose constructor parameter is opt-in-marked warns once with the reason`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val groomed: List<String> = warnings(result).filter { it.contains("Groomed") }
    assertEquals(1, groomed.size, "expected exactly one Groomed warning; got: ${warnings(result)}")
    assertContains(groomed.single(), "OPT_IN_MARKER")
  }

  @Test
  fun `the refused arm keeps its type and its handle constructor and gains no public one`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertContains(cs, "internal Groomed(IntPtr handle) : base(handle)")
    assertFalse(
      cs.contains("public Groomed("),
      "an opt-in-marked constructor parameter must reach neither artifact; generated=$cs",
    )
    // Kept, not dropped: the payload is bridgeable and a factory returns the arm.
    assertContains(cs, "public int Level")
    assertContains(cs, "public global::Interop.FlatShape.Groomed Groomed()")
  }

  @Test
  fun `the refused arm carries the consumer-facing remarks twin`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertContains(cs, "/// Cannot be constructed from C#: every Kotlin constructor of Groomed")
  }

  @Test
  fun `the bridgeable arm beside it still exports its constructor`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    // The control: one refused arm must not cost the hierarchy its other constructors, and must
    // not fire the warning for an arm that has one.
    assertContains(result.generatedCSharp, "public Circle(int radius) : base(IntPtr.Zero)")
    assertTrue(
      warnings(result).none { it.contains("Circle") },
      "expected no warning for a constructible arm; got: ${warnings(result)}",
    )
  }
}

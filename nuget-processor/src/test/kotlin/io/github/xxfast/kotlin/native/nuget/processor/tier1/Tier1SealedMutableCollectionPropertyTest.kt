package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-105 "Collection write side", opened by scope (d): a `var shapes: MutableList<Shape>` boxes
 * each element through the ADR-073 write path, whose `Wrap<T>` ends in a runtime
 * `is INugetHandle` test that an abstract C# base satisfies like any other wrapper. The setter
 * gate delegates to the shared `isWrappableComponent`, so admitting the sealed *parameter* half
 * admits this property setter in the same move, and the ADR-075 read-only diagnostic no longer
 * fires for it.
 *
 * A *scalar* `var current: Shape` was never gated: it rides the ordinary handle setter wire
 * (`value._handle` / `asStableRef<Shape>().get()`), and both halves stay asserted here together.
 */
class Tier1SealedMutableCollectionPropertyTest {

  private val source: String = """
    package tier1.sealedmutablecollectionproperty

    sealed class Shape {
      data class Circle(val radius: Double) : Shape()
    }

    class Board(
      var shapes: MutableList<Shape>,
      var current: Shape,
    )

    fun board(): Board = Board(mutableListOf(), Shape.Circle(1.0))
  """.trimIndent()

  @Test
  fun `a mutable sealed collection property plans both accessors`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains("export_board_get_shapes"),
      "expected the getter to bind; generated=${result.generated}",
    )
    assertTrue(
      result.generated.contains("export_board_set_shapes"),
      "expected the setter to bind; generated=${result.generated}",
    )
  }

  @Test
  fun `the setter is no longer named by the ADR-075 read-only diagnostic`() {
    val result = Tier1Harness.run(source)

    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            it.contains("Board.shapes")
      },
      "expected no read-only setter diagnostic; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a scalar sealed property keeps its setter on the ordinary handle wire`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_board_set_current"),
      "expected the scalar sealed setter to bind; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "Native_Set_current(_handle, value._handle, out IntPtr error)",
      ),
      "expected the C# setter to pass the handle; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("current") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("global::Interop.Shape.FromHandle(nativeResult)"),
      "expected the scalar getter to read through the discriminator; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Current") }}",
    )
  }
}

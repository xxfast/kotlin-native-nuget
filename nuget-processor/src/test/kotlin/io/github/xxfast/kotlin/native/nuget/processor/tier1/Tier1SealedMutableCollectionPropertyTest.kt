package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-105 "Collection write side" (decided: gated, not admitted). Opening the sealed **read** side
 * at a property position also runs the rewrite past the collection *setter* gate, which delegates
 * to the shared `isWrappableComponent`. Boxing a sealed base into a Kotlin collection through the
 * ADR-073 write path has never been rendered or run for an abstract C# base, so
 * `isWrappableComponent` refuses a `viaDiscriminator` handle and a `var shapes: MutableList<Shape>`
 * plans **get-only**, named by the existing ADR-075 read-only diagnostic rather than by a new one.
 *
 * A *scalar* `var current: Shape` is deliberately not gated: it rides the ordinary handle setter
 * wire (`value._handle` / `asStableRef<Shape>().get()`), so both halves are asserted here in the
 * same fixture as the negative.
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
  fun `a mutable sealed collection property plans get-only`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains("export_board_get_shapes"),
      "expected the getter to bind; generated=${result.generated}",
    )
    assertFalse(
      result.generated.contains("export_board_set_shapes"),
      "expected NO setter for the mutable sealed collection; generated=${result.generated}",
    )
  }

  @Test
  fun `the gated setter is named by the ADR-075 read-only diagnostic`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            it.contains("Board.shapes") &&
            it.contains("the C# property Shapes is read-only")
      },
      "expected the read-only setter diagnostic to name Board.shapes; " +
          "kspWarnings=${result.kspWarnings}",
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

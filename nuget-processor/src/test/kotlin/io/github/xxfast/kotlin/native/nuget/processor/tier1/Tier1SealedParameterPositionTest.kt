package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-105 scope (d), the parameter half: a sealed base at an *input* position crosses as the
 * ordinary instance handle the generated abstract C# base already carries (`shape._handle`, since
 * that base implements `INugetHandle` explicitly), and Kotlin reads it back with the same
 * `asStableRef<Shape>().get()` the `_get_type` discriminator export uses.
 *
 * The collection component rides the ADR-073 write path (`NugetMarshal.CreateList` /
 * `Wrap<T>`'s `INugetHandle` arm), which opens the shared `isWrappableComponent` gate and with it
 * the `var shapes: MutableList<Shape>` property setter that ADR-105 scope (c) left get-only.
 *
 * The control is a sealed **interface**: the classifier mints no `sealedHandle` for it (there is no
 * ADR-009 discriminator to reconstruct through), so it still skips as `SEALED_POSITION` and the
 * rewrite is provably not a blanket "every specialized protocol is a handle now".
 */
class Tier1SealedParameterPositionTest {

  private val source: String = """
    package tier1.sealedparameterposition

    sealed class Shape {
      data object Empty : Shape()
      data class Circle(val radius: Double) : Shape()
    }

    sealed interface Ghost {
      data object Nobody : Ghost
    }

    data class Drawing(
      val shape: Shape,
      val maybe: Shape?,
      val shapes: List<Shape>,
    )

    class Board {
      var shapes: MutableList<Shape> = mutableListOf()
      fun summary(): Int = shapes.size
    }

    object Shapes {
      fun describe(shape: Shape): String = if (shape is Shape.Circle) "circle" else "empty"
      fun describeMaybe(shape: Shape?): String = if (shape == null) "none" else describe(shape)
      fun count(shapes: List<Shape>): Int = shapes.size
      fun haunt(ghost: Ghost): String = ghost.toString()
    }

    fun board(): Board = Board()

    fun radius(shape: Shape): Double? = if (shape is Shape.Circle) shape.radius else null
  """.trimIndent()

  @Test
  fun `a bare sealed parameter crosses as the instance handle`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains(
        "shape.asStableRef<tier1.sealedparameterposition.Shape>().get()",
      ),
      "expected the export to read the parameter back through its StableRef; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("Native_Describe(shape._handle, out IntPtr error)"),
      "expected the C# call to pass the handle; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("describe") }}",
    )
  }

  @Test
  fun `a nullable sealed parameter rides the null pointer in band`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains(
        "shape?.asStableRef<tier1.sealedparameterposition.Shape>()?.get()",
      ),
      "expected the nullable lowering to short-circuit on null; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "Native_DescribeMaybe(shape?._handle ?? IntPtr.Zero, out IntPtr error)",
      ),
      "expected the C# call to pass IntPtr.Zero for null; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("describeMaybe") }}",
    )
  }

  @Test
  fun `a sealed collection parameter boxes each element through the write path`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_shapes_count"),
      "expected the collection parameter callable to bind; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("NugetMarshal.CreateList(shapes)"),
      "expected the C# call site to build the Kotlin list; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("CreateList") }}",
    )
    assertTrue(
      result.generated.contains("it as tier1.sealedparameterposition.Shape"),
      "expected each element to be cast back to the sealed base; generated=${result.generated}",
    )
  }

  @Test
  fun `a constructor with sealed parameters gets a public C# constructor`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_drawing_create"),
      "expected the constructor to bind; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("public Drawing("),
      "expected a public C# constructor; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Drawing(") }}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR.name) &&
            it.contains("Drawing")
      },
      "expected no missing-constructor warning; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a mutable sealed collection property gains its setter`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_board_set_shapes"),
      "expected the setter to bind now that the write side is open; " +
          "generated=${result.generated}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            it.contains("Board.shapes")
      },
      "expected no read-only setter diagnostic; kspWarnings=${result.kspWarnings}",
    )
  }

  /** The ADR-002 two-call route builds its parameters in `topLevelNullablePrimitivePlan`, not in
   *  `planOrSkip`, so the rewrite has to be applied there as well or a `Double?` return silently
   *  drops the sealed parameter its sibling shapes accept. */
  @Test
  fun `a sealed parameter binds on the two-call nullable primitive route`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("""@CName("radius_has_value")""") &&
          result.generated.contains("""@CName("radius_value")"""),
      "expected both halves of the two-call route to bind; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "Radius_has_value(shape._handle, out IntPtr __nuget_hasValueError)",
      ),
      "expected the presence call to pass the handle; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Radius") }}",
    )
  }

  @Test
  fun `a sealed interface parameter still skips as a sealed position`() {
    val result = Tier1Harness.run(source)

    assertFalse(
      result.generated.contains("export_shapes_haunt"),
      "expected the sealed-interface parameter to stay skipped; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) &&
            it.contains("Shapes.haunt")
      },
      "expected the sealed-position skip to name Shapes.haunt; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}

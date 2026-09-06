package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 3, the parameter half of the sealed-position story. ADR-105 bound a sealed base at
 * every RETURN and property position; a sealed base at an INPUT position is still deferred, and
 * *that* was the defect: the planner's skip claimed a legacy-route deferral
 * (`droppedFromCSharp = false`), but no legacy route re-emits a sealed parameter, so the member
 * left the C# API with no diagnostic in any channel.
 *
 * Every input spelling of the sealed base here (bare parameter, nullable parameter, collection
 * component, constructor parameter) must skip named as `SKIPPED_SEALED_POSITION` and name the
 * sealed type. `maybe` is the pointed one: it used to blame `NULLABLE`, whose hint sends the author
 * after a non-nullable wrapper that is just as undeclarable, exactly the trap issue #54 fixed for
 * undeclared types.
 *
 * `current()` is the control: a sealed RETURN still binds through the plan (ADR-105), so the new
 * skip must not widen to the return position.
 */
class Tier1SealedPositionSkipTest {

  private val source: String = """
    package tier1.sealedposition

    sealed class Shape {
      data object Empty : Shape()
      data class Circle(val radius: Double) : Shape()
    }

    class Drawing(shape: Shape, maybe: Shape?, shapes: List<Shape>) {
      val label: String = "drawing"
    }

    fun draw(shape: Shape) {}
    fun outline(maybe: Shape?) {}
    fun render(shapes: List<Shape>) {}
    fun current(): Shape = Shape.Empty
  """.trimIndent()

  private fun diagnostic(result: Tier1Result, member: String): String = requireNotNull(
    result.kspWarnings.firstOrNull {
      it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) && it.contains(member)
    },
  ) { "expected a sealed-position skip for $member; kspWarnings=${result.kspWarnings}" }

  @Test
  fun `every sealed input position skips named and names the sealed type`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf("draw", "outline", "render", "Drawing.<init>").forEach { member ->
      val diagnostic: String = diagnostic(result, member)
      assertTrue(
        diagnostic.contains("tier1.sealedposition.Shape"),
        "expected the $member diagnostic to name the sealed class; got: $diagnostic",
      )
      assertTrue(
        diagnostic.contains("not yet as a parameter") &&
            diagnostic.contains("accept a concrete subclass"),
        "expected the $member diagnostic to carry the sealed-position hint; got: $diagnostic",
      )
      assertTrue(diagnostic.contains("Skipping"), "expected a skip verb; got: $diagnostic")
    }
  }

  @Test
  fun `a nullable sealed parameter blames the position, not the nullability`() {
    val result = Tier1Harness.run(source)

    val outline: String = diagnostic(result, "outline")
    assertFalse(
      outline.contains("expose a non-nullable wrapper"),
      "expected the nullable sealed parameter to defer to the sealed position; got: $outline",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name) &&
            it.contains("outline")
      },
      "expected no NULLABLE-bucket diagnostic for outline; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `no sealed input is spelled in the generated C#`() {
    val result = Tier1Harness.run(source)

    listOf("draw", "outline", "render").forEach { name ->
      assertFalse(
        // The `@CName` spelling, not `export_$name`: `export_drawing_dispose` starts with
        // `export_draw`, so a prefix match would pass for the wrong reason.
        result.generated.contains("""@CName("$name")"""),
        "expected the $name export to be absent; generated=${result.generated}",
      )
    }
    assertFalse(
      result.generatedCSharp.contains("public Drawing("),
      "expected no public Drawing constructor; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Drawing") }}",
    )
  }

  @Test
  fun `a sealed return still binds`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generatedCSharp.contains("Shape Current()"),
      "expected the sealed return control to keep binding; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Current") }}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) && it.contains("current")
      },
      "expected no sealed-position skip for the return control; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}

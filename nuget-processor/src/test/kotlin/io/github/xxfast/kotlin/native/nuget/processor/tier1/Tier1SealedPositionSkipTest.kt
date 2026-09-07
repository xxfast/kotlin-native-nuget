package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 3, what is left of the sealed-position skip once ADR-105 scope (d) bound the sealed
 * *class* at every position and ADR-112 bound the eligible sealed *interface* too: a sealed type
 * with no generated ADR-009 discriminator. An INELIGIBLE sealed interface is the reachable spelling
 * of that (the classifier mints no `sealedHandle` for one, because the sealed route never declares
 * it), and C# has no way to reconstruct it, so every position it appears at must skip named as
 * `SKIPPED_SEALED_POSITION` and name the type rather than claiming a legacy-route deferral no route
 * re-emits, which is what left the member out of the C# API with no diagnostic at all.
 *
 * Every input spelling is covered (bare parameter, nullable parameter, collection component,
 * constructor parameter). `maybe` is the pointed one: it used to blame `NULLABLE`, whose hint
 * sends the author after a non-nullable wrapper that is just as undeclarable, exactly the trap
 * issue #54 fixed for undeclared types.
 *
 * `current()` is the control: a sealed **class** binds at every position (ADR-105 and its scope
 * (d)), so the skip must not widen from "no discriminator" to "sealed".
 */
class Tier1SealedPositionSkipTest {

  private val source: String = """
    package tier1.sealedposition

    sealed class Shape {
      data object Empty : Shape()
      data class Circle(val radius: Double) : Shape()
    }

    open class Haunting

    // ADR-112: INELIGIBLE on purpose. `Nobody` carries a second superclass, which no nested
    // `sealed class Nobody : Ghost` can express, so `Ghost` has no discriminator and this cell
    // keeps testing the skip. An eligible sealed interface now binds (Tier1SealedInterfaceTest).
    sealed interface Ghost {
      class Nobody : Haunting(), Ghost
    }

    class Drawing(ghost: Ghost, maybe: Ghost?, ghosts: List<Ghost>) {
      val label: String = "drawing"
    }

    fun draw(ghost: Ghost) {}
    fun outline(maybe: Ghost?) {}
    fun render(ghosts: List<Ghost>) {}
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
        diagnostic.contains("tier1.sealedposition.Ghost"),
        "expected the $member diagnostic to name the sealed type; got: $diagnostic",
      )
      assertTrue(
        diagnostic.contains("has no generated discriminator") &&
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
      "expected the nullable sealed parameter to blame the missing discriminator; got: $outline",
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

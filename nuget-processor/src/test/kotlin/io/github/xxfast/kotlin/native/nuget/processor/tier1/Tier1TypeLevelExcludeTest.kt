package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #53: `exclude(...)` matched package names only, so `exclude("pkg.Shape")` changed nothing
 * and the only working remedy for one unbridgeable member was dropping its whole package. It now
 * also matches a qualified declaration name and everything nested under it, at the root scan and
 * in the ADR-066 reachability closure alike. A member referencing the excluded type skips with a
 * named diagnostic; the rest of the package still bridges.
 */
class Tier1TypeLevelExcludeTest {

  private val fixture: String = """
    package tier1.typeexclude

    sealed class Shape {
      data class Circle(val radius: Double) : Shape()
    }

    data class Album(val shapes: List<Shape>, val title: String)

    data class Frame(val width: Int)

    fun album(): Album = Album(emptyList(), "Oreo")

    fun frame(): Frame = Frame(1)
  """.trimIndent()

  private fun run(vararg exclude: String): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf(
      "nuget.rootPackage" to "tier1.typeexclude",
      "nuget.excludePackages" to exclude.joinToString(","),
    ),
  )

  @Test
  fun `excluding a sealed base by qualified name drops it and its subclasses only`() {
    val result = run("tier1.typeexclude.Shape")

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertFalse(csharp.contains("class Shape"), "expected Shape gone; got: $csharp")
    assertFalse(csharp.contains("class Circle"), "expected Shape.Circle gone; got: $csharp")
    assertTrue(csharp.contains("class Album"), "expected Album kept; got: $csharp")
    assertTrue(csharp.contains("class Frame"), "expected Frame kept; got: $csharp")
    assertTrue(csharp.contains("string Title"), "expected Album.title kept; got: $csharp")
    assertFalse(
      result.generated.contains("export_album_get_shapes"),
      "expected Album.shapes skipped, not walked; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Album.shapes")
      },
      "expected a named skip for the member referencing the excluded type; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `excluding the owning type by qualified name leaves its siblings alone`() {
    val result = run("tier1.typeexclude.Album")

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertFalse(csharp.contains("class Album"), "expected Album gone; got: $csharp")
    assertTrue(csharp.contains("class Shape"), "expected Shape kept; got: $csharp")
    assertTrue(csharp.contains("class Frame"), "expected Frame kept; got: $csharp")
    assertTrue(
      result.generated.contains("export_frame"),
      "expected the top-level frame() to bind; generated=${result.generated}",
    )
  }

  @Test
  fun `excluding a top-level function by qualified name drops just that function`() {
    val result = run("tier1.typeexclude.frame")

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("export_frame("),
      "expected frame() gone; generated=${result.generated}",
    )
    assertTrue(result.generatedCSharp.contains("class Frame"), "expected the Frame class kept")
    assertTrue(
      result.generated.contains("export_album("),
      "expected album() kept; generated=${result.generated}",
    )
  }

  @Test
  fun `a qualified name that is only a prefix of another does not exclude it`() {
    // `tier1.typeexclude.Frame` must not take `tier1.typeexclude.FrameSet`-style names with it;
    // the match is exact or dotted-nested, never a raw string prefix.
    val result = Tier1Harness.run(
      """
      package tier1.typeexclude

      data class Frame(val width: Int)
      data class FrameSet(val frames: Int)
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.rootPackage" to "tier1.typeexclude",
        "nuget.excludePackages" to "tier1.typeexclude.Frame",
      ),
    )

    val csharp: String = result.generatedCSharp
    assertFalse(
      csharp.contains("class Frame\n") || csharp.contains("class Frame "),
      "expected Frame gone; got: $csharp",
    )
    assertTrue(csharp.contains("class FrameSet"), "expected FrameSet kept; got: $csharp")
  }
}

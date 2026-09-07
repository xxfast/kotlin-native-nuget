package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A sealed subclass declared *beside* its sealed base rather than inside it was collected
 * **twice**: once by the ordinary class route (a namespace-level `Label` with a public
 * constructor and `label_*` exports) and once by the ADR-009 sealed route (a nested `Shape.Label`
 * with `shape_label_*` exports). One Kotlin type, two C# types, so `is` checks disagree with
 * themselves depending on which of the two the caller happens to hold.
 *
 * The sealed route is the sole owner. A *sibling* subclass is declared at namespace level as
 * `public sealed class Label : Shape` (its Kotlin scope), a genuinely nested one stays nested
 * (`Shape.Circle`), and either way the exports keep the sealed prefix.
 *
 * `nuget.rootPackage` is set so `tier1.flat` lands in its own sub-namespace, matching the sibling
 * fixture's shape in the real library.
 */
class Tier1SiblingSealedSubclassTest {

  private val source: String =
    """
    package tier1.flat

    sealed class Shape {
      data class Circle(val radius: Int) : Shape()
    }

    data class Label(val text: String) : Shape()

    class Factory {
      fun label(text: String): Label = Label(text)
      val circle: Shape.Circle = Shape.Circle(3)
    }

    fun anyShape(): Shape = Label("any")
    """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
  )

  @Test
  fun `a sibling sealed subclass is declared once at namespace level`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertEquals(
      1,
      Regex("""class Label\b""").findAll(cs).count(),
      "expected exactly one C# declaration of Label; generated=$cs",
    )
    // Namespace level is four spaces of indentation; a nested subclass is rendered at eight.
    assertContains(cs, "\n    public sealed class Label : Shape")
    assertFalse(
      cs.contains("        public sealed class Label"),
      "expected Label at namespace level, not nested inside Shape; generated=$cs",
    )
    // The nested control keeps its nesting.
    assertContains(cs, "        public sealed class Circle : Shape")
  }

  @Test
  fun `a sibling sealed subclass is constructed only through the sealed route`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertContains(cs, "internal Label(IntPtr handle) : base(handle)")
    assertFalse(
      cs.contains("public Label("),
      "expected no public constructor on a sealed subclass; generated=$cs",
    )
    assertContains(cs, "=> new Label(handle),")
  }

  @Test
  fun `a sibling sealed subclass exports under the sealed prefix only`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated

    assertContains(kotlin, "shape_label_get_text")
    assertFalse(
      kotlin.contains("export_label_get_text"),
      "expected no plain-class getter export for the sibling subclass; generated=$kotlin",
    )
    assertFalse(
      kotlin.contains("export_label_create"),
      "expected no plain-class constructor export for the sibling subclass; generated=$kotlin",
    )
  }

  /**
   * The same duplicate, klib-origin half: a dependency-module sealed subclass is discovered by the
   * closure's `getSealedSubclasses()` walk, and bucketing it as an ordinary class declared it a
   * second time as a plain class exactly as the module-local route did.
   */
  @Test
  fun `a sibling sealed subclass from a dependency module is declared once`() {
    val dependencyJar: File = Tier1DependencyLibrary.compile(
      """
      package dep.flat

      sealed class Shape {
        data class Circle(val radius: Int) : Shape()
      }

      data class Label(val text: String) : Shape()
      """.trimIndent(),
      fileName = "Shape.kt",
    )

    val result = Tier1Harness.run(
      """
      package tier1.flatdep

      import dep.flat.Shape
      import dep.flat.Label

      class Factory {
        fun any(): Shape = Label("any")
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.includePackages" to "tier1.flatdep,dep.flat"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertEquals(
      1,
      Regex("""class Label\b""").findAll(cs).count(),
      "expected exactly one C# declaration of Label; generated=$cs",
    )
    assertContains(cs, "\n    public sealed class Label : Shape")
    assertFalse(
      result.generated.contains("export_label_create"),
      "expected no plain-class constructor export for the sibling subclass; " +
          "generated=${result.generated}",
    )
  }
}

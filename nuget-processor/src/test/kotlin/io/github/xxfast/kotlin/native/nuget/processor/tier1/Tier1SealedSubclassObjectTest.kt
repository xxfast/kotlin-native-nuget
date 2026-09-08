package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #110, the `object` half of [Tier1SiblingSealedSubclassTest]. A sealed subclass of kind
 * `CLASS` is declared exactly once, because `rootClasses` filters `isSealedSubclass()` out and the
 * closure buckets it `SEALED_SUBCLASS`. A sealed subclass of kind `OBJECT` is declared **twice**,
 * because neither object collector asks the same question.
 *
 * Route one, module-local: `rootObjects` is `classKind == OBJECT && parentDeclaration == null` with
 * no sealedness filter, so a *sibling* object subclass is emitted both as the sealed route's
 * `public sealed class Loaf : Shape` and as an empty `public static class Loaf { }` in the same
 * namespace. That is CS0101, plus the CS0713/CS0710/CS0708 cascade of the compiler merging the two
 * declarations and reading the sealed route's members as instance members of the static one.
 *
 * Route two, cross-module: the closure's nested-declaration refusal carves out `isSealedSubclass()`
 * on purpose, but `reachabilityBucket()` tests `classKind == OBJECT` *before* `isSealedSubclass()`,
 * so a **nested** object arm from a dependency module lands in `ForwardReachabilityBucket.OBJECT`
 * anyway. The residue static class does not collide there, since the real declaration is nested
 * inside the base's braces, so it compiles and merely leaves behind a public type that is not an
 * API. Non-fatal, still wrong.
 *
 * Both routes here because a fix to only one leaves the other emitting a type nothing declares a
 * use for. The third test is the control that keeps "exclude sealed subclasses" from sliding into
 * "stop declaring objects".
 *
 * Neither cat can be bothered having a shape, so they fold their paws under and become loaves.
 */
class Tier1SealedSubclassObjectTest {

  private val source: String =
    """
    package tier1.flatobject

    sealed class Shape {
      data class Circle(val radius: Int) : Shape()
      data object Curled : Shape()
    }

    data object Loaf : Shape()

    class Factory {
      fun loaf(): Loaf = Loaf
      fun any(): Shape = Loaf
    }

    object Registry {
      fun count(): Int = 2
    }
    """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
  )

  @Test
  fun `a sibling sealed subclass object is declared once at namespace level`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertEquals(
      1,
      Regex("""class Loaf\b""").findAll(cs).count(),
      "expected exactly one C# declaration of Loaf; generated=$cs",
    )
    // Namespace level is four spaces of indentation; a nested subclass is rendered at eight.
    assertContains(cs, "\n    public sealed class Loaf : Shape")
    assertFalse(
      cs.contains("public static class Loaf"),
      "expected no empty static class for a sealed subclass object; generated=$cs",
    )
  }

  @Test
  fun `a nested sealed subclass object gets no companion static class`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertEquals(
      1,
      Regex("""class Curled\b""").findAll(cs).count(),
      "expected exactly one C# declaration of Curled; generated=$cs",
    )
    assertContains(cs, "        public sealed class Curled : Shape")
  }

  @Test
  fun `a plain top level object still gets its static class`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // The control: `Registry` subclasses nothing, so the object route is still its only owner and
    // its members must still be there. A fix that drops every object passes the two tests above.
    assertContains(cs, "public static class Registry")
    assertContains(cs, "public static int Count()")
  }

  /**
   * Route two: a **nested** object arm reached across a compilation-unit boundary. The closure
   * admits it (sealed subclasses are carved out of the nested-declaration refusal) and then buckets
   * it `OBJECT`, so a namespace-level `public static class Nap { }` accompanies the real nested
   * `Nap`. It compiles, which is exactly why nothing has caught it.
   */
  @Test
  fun `a nested sealed subclass object from a dependency module is declared once`() {
    val dependencyJar: File = Tier1DependencyLibrary.compile(
      """
      package dep.naps

      sealed class Rest {
        data class Deep(val minutes: Int) : Rest()
        data object Nap : Rest()
      }
      """.trimIndent(),
      fileName = "Rest.kt",
    )

    val result = Tier1Harness.run(
      """
      package tier1.napdep

      import dep.naps.Rest

      class Factory {
        fun any(): Rest = Rest.Nap
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.includePackages" to "tier1.napdep,dep.naps"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertEquals(
      1,
      Regex("""class Nap\b""").findAll(cs).count(),
      "expected exactly one C# declaration of Nap; generated=$cs",
    )
    assertContains(cs, "        public sealed class Nap : Rest")
    assertFalse(
      cs.contains("public static class Nap"),
      "expected no empty static class for a cross-module sealed subclass object; generated=$cs",
    )
    // The `CLASS` arm of the same hierarchy is the control that has always been declared once.
    assertEquals(
      1,
      Regex("""class Deep\b""").findAll(cs).count(),
      "expected exactly one C# declaration of Deep; generated=$cs",
    )
  }
}

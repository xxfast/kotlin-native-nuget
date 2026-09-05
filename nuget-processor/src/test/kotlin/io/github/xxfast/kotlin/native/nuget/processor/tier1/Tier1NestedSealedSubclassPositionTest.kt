package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A sealed subclass declared inside its sealed base is *declared* in C# as a **nested** class
 * (ADR-009: `public sealed class Circle : Shape` inside `public abstract class Shape`), but every
 * member **type position** spelled the subclass from its simple name alone —
 * `global::Interop.Nest.Circle` — so `Interop.cs` failed with CS0234 on the whole file the moment
 * any exported member was typed as a subclass. Verified red in the field against the
 * `NestedShapeSample` fixture: three CS0234s at a property, a method return (plus its
 * `new T(nativeResult)`) and a parameter.
 *
 * The two spelling functions with that defect are asserted separately, because they are reached
 * from different callers and only one of them is on the plan route:
 * - `ForwardBridgeTypeClassifier.csharpTypeNameFor` — the ordinary property/return/parameter
 *   positions on a plain exported class ([`a nested sealed subclass...`]),
 * - `CirTypeMapping.qualifiedElementCsType` — the property types *of a sealed subclass itself*,
 *   the legacy sealed route (`CirClassTranslator`).
 *
 * `nuget.rootPackage` is set so `tier1.nest` lands in its own sub-namespace `Interop.Nest`: the
 * right answer `global::Interop.Nest.Shape.Circle` and the wrong one `global::Interop.Nest.Circle`
 * are then disjoint strings, which is what makes the negative assertion meaningful.
 */
class Tier1NestedSealedSubclassPositionTest {

  private val nested: String = "global::Interop.Nest.Shape.Circle"
  private val bare: String = "global::Interop.Nest.Circle"

  private fun run(source: String): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
  )

  @Test
  fun `a nested sealed subclass keeps its enclosing base at return property and parameter positions`() {
    val result = run(
      """
      package tier1.nest

      sealed class Shape {
        data class Circle(val radius: Double) : Shape()
        data object Empty : Shape()
      }

      class Factory {
        val unit: Shape.Circle = Shape.Circle(1.0)
        fun circle(radius: Double): Shape.Circle = Shape.Circle(radius)
        fun radiusOf(circle: Shape.Circle): Double = circle.radius
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // Property, return type + its construction site, and parameter.
    assertContains(cs, "public $nested Unit")
    assertContains(cs, "public $nested Circle(double radius)")
    assertContains(cs, "new $nested(nativeResult)")
    assertContains(cs, "RadiusOf($nested circle)")

    assertFalse(
      cs.contains(bare),
      "expected no bare `$bare` anywhere in Interop.cs; generated=$cs",
    )
  }

  @Test
  fun `a nested sealed subclass at a sealed subclass property keeps its enclosing base`() {
    val result = run(
      """
      package tier1.nest

      sealed class Shape {
        data class Circle(val radius: Double) : Shape()
        data class Wrapper(val inner: Circle) : Shape()
      }

      fun shape(): Shape = Shape.Circle(1.0)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // The `qualifiedElementCsType` route: a subclass property whose own type is a sibling subclass.
    assertContains(cs, "public $nested Inner")
    assertContains(cs, "new $nested(")

    assertFalse(
      cs.contains(bare),
      "expected no bare `$bare` anywhere in Interop.cs; generated=$cs",
    )
  }
}

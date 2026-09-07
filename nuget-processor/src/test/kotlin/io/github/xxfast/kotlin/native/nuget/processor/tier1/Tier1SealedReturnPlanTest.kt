package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #54, the return half of ADR-105. A sealed base at a *return* position on a class member,
 * an `object` member or a companion member was silently dropped: the callable planner had no arm
 * for a `SpecializedProtocol` result, so it recorded a sealed-protocol skip, and only the
 * top-level route re-emitted the same return through its named legacy adapter. The member routes
 * had no sealed arm at all, so the C# side reported CS1061/CS0117 on a member that was never
 * generated.
 *
 * The fix mirrors the property planner: the result type is rewritten through `sealedAsHandle()`
 * before a shape is taken, so every origin plans the sealed base as the
 * `ObjectHandle(viaDiscriminator = true)` the classifier already carries, and the CIR projection
 * reconstructs it through the ADR-009 `FromHandle` discriminator rather than a `new` on an
 * abstract type (CS0144).
 *
 * The top-level spelling is the parity cell: it used to ride the legacy adapter and now rides the
 * plan, and must still produce exactly one export under the same name.
 */
class Tier1SealedReturnPlanTest {

  private val source: String = """
    package tier1.sealedreturn

    sealed class Shape {
      data object Empty : Shape()
      data class Circle(val radius: Double) : Shape()
    }

    class Factory(val name: String) {
      fun shapeOf(radius: Double): Shape = Shape.Circle(radius)
      fun maybe(radius: Double): Shape? = if (radius > 0.0) Shape.Circle(radius) else null
      fun everyShape(): List<Shape> = listOf(Shape.Empty)
    }

    object Shapes {
      fun pick(n: Int): Shape = if (n == 0) Shape.Empty else Shape.Circle(n.toDouble())
    }

    fun anyShape(): Shape = Shape.Empty
  """.trimIndent()

  @Test
  fun `a class method returning a sealed base binds through the discriminator`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the sealed returns to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains("export_factory_shapeOf"),
      "expected Factory.shapeOf to export; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("public global::Interop.Shape ShapeOf(double radius)"),
      "expected the sealed base as the public return type; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("ShapeOf") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("return global::Interop.Shape.FromHandle(nativeResult);"),
      "expected the reconstruction through the ADR-009 discriminator; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("FromHandle") }}",
    )
  }

  @Test
  fun `an object method returning a sealed base binds`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_shapes_pick"),
      "expected Shapes.pick to export; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("public static global::Interop.Shape Pick(int n)"),
      "expected the object member to render as a static sealed return; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Pick") }}",
    )
  }

  @Test
  fun `a nullable sealed return rides the null pointer`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_factory_maybe"),
      "expected Factory.maybe to export; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "return nativeResult == IntPtr.Zero ? null : " +
            "global::Interop.Shape.FromHandle(nativeResult);",
      ),
      "expected the nullable sealed return to test the pointer; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("FromHandle") }}",
    )
  }

  @Test
  fun `a class member collection of the sealed base reads every element through the discriminator`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_factory_everyShape"),
      "expected Factory.everyShape to export; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "NugetMarshal.FromHandle<global::Interop.Shape>(NugetListNative.Get(listHandle, i))",
      ),
      "expected the List element to materialise through FromHandle<Shape>; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("FromHandle") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("public IReadOnlyList<global::Interop.Shape> EveryShape()"),
      "expected the sealed base as the element spelling; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("EveryShape") }}",
    )
  }

  @Test
  fun `the top-level sealed return still binds exactly once`() {
    val result = Tier1Harness.run(source)

    assertEquals(
      1,
      Regex("""@CName\("anyShape"\)""").findAll(result.generated).count(),
      "expected exactly one export for the top-level sealed return; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("public static global::Interop.Shape AnyShape()"),
      "expected the top-level sealed return to keep its bare name; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("AnyShape") }}",
    )
  }
}

package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-105 + ADR-077: a value class whose underlying type is a *sealed* class binds at a property
 * position. `sealedAsHandle()` recurses into `BridgeType.ValueClass.underlying`, so the underlying
 * sealed base becomes the ADR-009 handle it already is at a top-level return, and the C#
 * reconstruction composes the two steps: `new Wrapped(Shape.FromHandle(nativeResult))` (a sealed
 * base is `abstract`, so `new Shape(...)` would be CS0144).
 */
class Tier1ValueClassOverSealedPropertyTest {

  private val source: String = """
    package tier1.valueclasssealedprop

    sealed class Shape {
      data object Dot : Shape()
      data class Box(val size: Int) : Shape()
    }

    @JvmInline
    value class Wrapped(val shape: Shape)

    class Desk {
      val result: Wrapped = Wrapped(Shape.Dot)
      var maybe: Wrapped? = null
      var current: Wrapped = Wrapped(Shape.Dot)
      val many: List<Wrapped> = emptyList()

      fun take(w: Wrapped): Wrapped = w
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  @Test
  fun `a value class over a sealed type reads through the discriminator on both halves`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "get().result.shape")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.Wrapped Result")
    assertContains(
      cs,
      "return new global::Interop.Wrapped(global::Interop.Shape.FromHandle(nativeResult));",
    )
  }

  @Test
  fun `the nullable spelling rides the null pointer on both halves`() {
    val result = run()

    val kotlin: String = result.generated
    assertContains(kotlin, "get().maybe?.shape")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.Wrapped? Maybe")
    assertContains(
      cs,
      "return nativeResult == IntPtr.Zero ? null : " +
          "new global::Interop.Wrapped(global::Interop.Shape.FromHandle(nativeResult));",
    )
    assertContains(
      cs,
      "Native_Set_maybe(_handle, value?.Shape._handle ?? IntPtr.Zero, out IntPtr error)",
    )
  }

  @Test
  fun `the non-null setter passes the underlying handle`() {
    val result = run()

    assertContains(
      result.generatedCSharp,
      "Native_Set_current(_handle, value.Shape._handle, out IntPtr error)",
    )
  }

  /**
   * `sealedAsHandle()` is shared, so the callable route gets the same rewrite: the value class
   * binds at a parameter and at a return position, unwrapping to the underlying handle on the way
   * in and composing the discriminator on the way out.
   */
  @Test
  fun `the callable route binds the same value class at a parameter and a return`() {
    val result = run()

    assertContains(
      result.generated,
      "get().take(tier1.valueclasssealedprop.Wrapped(" +
          "w.asStableRef<tier1.valueclasssealedprop.Shape>().get())).shape",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.Wrapped Take(global::Interop.Wrapped w)")
    assertContains(cs, "Native_Take(_handle, w.Shape._handle, out IntPtr error)")
    assertContains(
      cs,
      "return new global::Interop.Wrapped(global::Interop.Shape.FromHandle(nativeResult));",
    )
  }

  /**
   * A `List` of the value class rides the same recursion through the collection element: the
   * element materializes through the generic `NugetMarshal.FromHandle<T>`, whose factory table
   * already routes a sealed base to its discriminator.
   */
  @Test
  fun `a list of the value class materializes element-wise`() {
    val result = run()

    assertContains(result.generated, "get().many.map { it.shape }")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public IReadOnlyList<global::Interop.Wrapped> Many")
    assertContains(
      cs,
      "(nativeResult, static h1 => new global::Interop.Wrapped(" +
          "NugetMarshal.FromHandle<global::Interop.Shape>(h1)))",
    )
  }
}

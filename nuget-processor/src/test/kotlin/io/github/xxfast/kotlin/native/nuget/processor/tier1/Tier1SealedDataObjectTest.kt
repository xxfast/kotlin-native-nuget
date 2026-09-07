package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #54: a `data object` subclass is a data class as far as Kotlin's generated members go, so
 * its C# wrapper binds the same `_equals` / `_hashcode` / `_tostring` exports a `data class`
 * subclass binds. It used to render a constant `ToString()` and no equality at all, which left the
 * three Kotlin exports orphaned and made two wrappers over the one Kotlin singleton unequal (every
 * read mints a fresh wrapper, so reference equality never held).
 */
class Tier1SealedDataObjectTest {

  private val source: String = """
    package tier1.dataobject

    sealed class Shape {
      data object Empty : Shape()

      data class Circle(val radius: Double) : Shape()
    }

    fun shape(): Shape = Shape.Empty
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  @Test
  fun `the generated Kotlin compiles`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a data object subclass exports equals hashcode and tostring`() {
    val result = run()

    assertContains(
      result.generated,
      "public fun export_shape_empty_equals(handle: COpaquePointer, other: COpaquePointer): Boolean",
    )
    assertContains(
      result.generated,
      "public fun export_shape_empty_hashcode(handle: COpaquePointer): Int",
    )
    assertContains(
      result.generated,
      "public fun export_shape_empty_tostring(handle: COpaquePointer): String",
    )
  }

  @Test
  fun `a data object subclass binds the data methods in C#`() {
    val result = run()

    assertContains(result.generatedCSharp, "EntryPoint = \"shape_empty_equals\"")
    assertContains(result.generatedCSharp, "EntryPoint = \"shape_empty_hashcode\"")
    assertContains(result.generatedCSharp, "EntryPoint = \"shape_empty_tostring\"")
    assertContains(result.generatedCSharp, "if (obj is Empty other) return Native_Equals(_handle, other._handle);")
    assertContains(result.generatedCSharp, "public override int GetHashCode() => Native_HashCode(_handle);")
  }

  /** The old constant literal is what made the wrapper's `ToString` disagree with Kotlin's. */
  @Test
  fun `a data object ToString is not a fixed literal`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("public override string ToString() => \"Empty\";"),
      "expected `ToString` over the native export, not a baked literal",
    )
    assertContains(
      result.generatedCSharp,
      "public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;",
    )
  }
}

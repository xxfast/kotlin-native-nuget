package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-083 on ADR-010's generic route: a generic class's property getter used to force `!!` on the
 * read, so a `T?` property holding null killed the host with a `kotlin.NullPointerException` before
 * C# ever saw a handle. The lift is unconditional (a non-null property never yields null, so it
 * never hits the null pointer), which the second cell guards.
 */
class Tier1NullableGenericPropertyTest {

  @Test
  fun `a nullable property getter returns a nullable pointer and lifts the read`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablegenericproperty

      class Slot<T>(val value: T) {
        val previous: T? = null
      }

      fun slotOf(value: String): Slot<String> = Slot(value)
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a nullable generic-class property to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "get().previous?.let { StableRef.create(it).asCPointer() }",
    )
    assertFalse(
      kotlin.contains("get().previous!!"),
      "the forcing read is what NPE'd; generated=$kotlin",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "internal static extern IntPtr Get_previous(IntPtr handle);")
    // NugetMarshal.FromHandle already returns `default!` for IntPtr.Zero, so the C# read needs no
    // guard of its own -- only the `T?` spelling that says a null can arrive.
    assertContains(cs, "public T? Previous => NugetMarshal.FromHandle<T>(SlotNative.Get_previous(_handle));")
  }

  @Test
  fun `a non-null property getter keeps its shape apart from the lift`() {
    val result = Tier1Harness.run(
      """
      package tier1.nonnullgenericproperty

      class Crate<T>(val value: T)

      fun crateOf(value: String): Crate<String> = Crate(value)
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a non-null generic-class property to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"crate_get_value\")")
    assertContains(
      kotlin,
      "get().value?.let { StableRef.create(it).asCPointer() }",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public T Value => NugetMarshal.FromHandle<T>(CrateNative.Get_value(_handle));")
  }
}

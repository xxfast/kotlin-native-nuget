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
    // ADR-147: the ADR-062 property plan's own nullable-handle body, which returns Kotlin null
    // before it ever mints a StableRef. Same null-pointer wire, one route fewer.
    assertContains(
      kotlin,
      "val result = handle.asStableRef<tier1.nullablegenericproperty.Slot<Any>>().get().previous",
    )
    assertContains(kotlin, "if (result == null) null else NugetHandles.retain(result)")
    assertFalse(
      kotlin.contains("get().previous!!"),
      "the forcing read is what NPE'd; generated=$kotlin",
    )

    val cs: String = result.generatedCSharp
    // ADR-147: on the plan, so the getter carries the ADR-032 error slot every other one has.
    // ADR-147: hoisted out of the generic carrier (CS7042), forwarded back into it.
    assertContains(
      cs,
      "internal static extern IntPtr Native_Get_previous(IntPtr handle, out IntPtr error);",
    )
    assertContains(cs, "=> SlotNative.Native_Get_previous(handle, out error);")
    // NugetMarshal.FromHandle already returns `default!` for IntPtr.Zero, so the C# read needs no
    // guard of its own -- only the `T?` spelling that says a null can arrive.
    assertContains(cs, "public T? Previous")
    assertContains(cs, "return NugetMarshal.FromHandle<T>(nativeResult);")
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
    assertContains(kotlin, "@CName(\"library_tier1_nonnullgenericproperty__crate_get_value\")")
    assertContains(
      kotlin,
      "NugetHandles.retain(handle.asStableRef<tier1.nonnullgenericproperty.Crate<Any>>()" +
          ".get().value)",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public T Value")
    assertContains(cs, "return NugetMarshal.FromHandle<T>(nativeResult);")
  }
}

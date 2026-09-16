package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An unconstrained generic class whose name is *not* `Box`. The C# constructor used to dispatch
 * through a shared `NugetMarshal.CreateBox<T>` helper that hardcoded the `box_create_*` entry
 * points, so it only resolved for a library that happened to declare a class named `Box`, and it
 * built the wrong Kotlin type for any other one. This cell is the regression guard: with no `Box`
 * in the corpus, a re-hardcoded helper has no Kotlin export to bind to and the ADR-055/078 forward
 * ABI contract check fails the run before these assertions are reached.
 */
class Tier1GenericClassPrefixTest {

  @Test
  fun `an unconstrained generic class constructs through its own native prefix`() {
    val result = Tier1Harness.run(
      """
      package tier1.genericprefix

      class Crate<T>(val value: T)

      fun packInt(value: Int): Crate<Int> = Crate(value)
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected generic class exports to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    // ADR-147: one boxed constructor, not the twelve widths plus an object fallback.
    assertContains(kotlin, "@CName(\"crate_create\")")
    assertFalse(kotlin.contains("crate_create_int"), "the per-width create variants are gone")
    assertFalse(kotlin.contains("crate_create_object"), "the object create fallback is gone")

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"crate_create\"")
    // ADR-147: CS7042 forbids a DllImport inside a generic type, so the extern lives in the
    // sibling `CrateNative` and the carrier holds a forwarder of the identical signature.
    assertContains(cs, "internal static class CrateNative")
    assertContains(cs, "internal static extern IntPtr Native_Create(IntPtr")
    assertContains(cs, "=> CrateNative.Native_Create(")
    assertContains(cs, "NugetMarshal.Wrap<T>(")
    assertFalse(cs.contains("box_create_"), "a generic class must not bind another class's exports")
    assertFalse(cs.contains("CreateBox"), "the shared boxing constructor helper is gone")
  }
}

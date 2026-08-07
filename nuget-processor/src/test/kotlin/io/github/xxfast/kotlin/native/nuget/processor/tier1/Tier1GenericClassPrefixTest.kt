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
    assertContains(kotlin, "@CName(\"crate_create_int\")")
    assertContains(kotlin, "@CName(\"crate_create_object\")")

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"crate_create_int\"")
    assertContains(cs, "internal static extern IntPtr Create_int(int value, out IntPtr error);")
    assertContains(cs, "CrateNative.Create_int((int)(object)value!, out error)")
    assertFalse(cs.contains("box_create_"), "a generic class must not bind another class's exports")
    assertFalse(cs.contains("CreateBox"), "the shared boxing constructor helper is gone")
  }
}

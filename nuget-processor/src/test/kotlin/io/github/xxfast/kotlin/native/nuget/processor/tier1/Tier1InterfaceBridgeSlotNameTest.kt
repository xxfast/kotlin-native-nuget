package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Generated-name hygiene on the two routes that hand a listener's members across as callback
 * slots: the ADR-039 `add*`/`remove*` subscription export and the ADR-084 bridge factory.
 */
class Tier1InterfaceBridgeSlotNameTest {

  private val dog: String =
    """
    class Dog {
      private val listeners = mutableListOf<BarkListener>()
      fun addBarkListener(l: BarkListener) { listeners.add(l) }
      fun removeBarkListener(l: BarkListener) { listeners.remove(l) }
      fun befriend(l: BarkListener): Int = 1
      fun size(): Int = listeners.size
    }
    """.trimIndent()

  /**
   * `val name` and `fun nameGet()` both took the prefix `nameGet`, so both exports declared
   * `nameGetPtr` twice (Kotlin `Conflicting declarations`) and both C# imports did the same. The
   * getter slot moves to `nameGet_2` on both routes; the function keeps its name.
   */
  @Test
  fun `a listener val and a function named like its getter slot take distinct slot names`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.slotnames

      interface BarkListener {
        val name: String
        fun nameGet(volume: Int)
      }

      $dog
      """.trimIndent(),
    )

    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    // The subscription export, then the bridge factory: getter slot first, then the function.
    assertContains(
      result.generated,
      "handle: COpaquePointer,\n  nameGet_2Ptr: COpaquePointer,\n" +
          "  nameGet_2Ctx: COpaquePointer,\n" +
          "  nameGetPtr: COpaquePointer,\n  nameGetCtx: COpaquePointer,\n  errorOut",
    )
    assertContains(
      result.generated,
      "(\n  nameGet_2Ptr: COpaquePointer,\n  nameGet_2Ctx: COpaquePointer,\n" +
          "  nameGetPtr: COpaquePointer,\n  nameGetCtx: COpaquePointer,\n  releasePtr",
    )
    assertContains(
      result.generatedCSharp,
      "Native_AddBarkListener(NugetKotlinHandle handle, IntPtr nameGet_2Ptr, " +
          "IntPtr nameGet_2Ctx, IntPtr nameGetPtr, IntPtr nameGetCtx, out IntPtr error)",
    )
    assertContains(
      result.generatedCSharp,
      "Native_Create(IntPtr nameGet_2Ptr, IntPtr nameGet_2Ctx, IntPtr nameGetPtr, " +
          "IntPtr nameGetCtx, IntPtr releasePtr",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using Interop;

      public sealed class Rex : IBarkListener
      {
          public string Name => "Rex";
          public void NameGet(int volume) { }
          public void Dispose() { }
      }

      public static class Probe
      {
          public static IDisposable Listen(Dog dog) => dog.AddBarkListener(new Rex());
          public static int Befriend(Dog dog) => dog.Befriend(new Rex());
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * The bridge factory's C# half declares each slot's delegate under the bare slot prefix, beside
   * its own `impl`, `state`, `token`, `release` and `error`; a slot spelled like one of them, or
   * like a C# keyword, failed the C# compile (and `release` the Kotlin one, against `releasePtr`).
   * Each moves to `_2`, and a slot that clashes with nothing keeps its name.
   */
  @Test
  fun `a bridge slot spelled like a generated name or a C# keyword takes a distinct name`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.slotfixed

      interface Pet {
        fun lock(volume: Int)
        fun release()
        fun token()
        fun state()
        fun impl()
        fun error()
        fun result(): String
        fun value0()
      }

      class Dog {
        fun befriend(pet: Pet): Int = 1
      }
      """.trimIndent(),
    )

    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    listOf("lock", "release", "token", "state", "impl", "error").forEach { slot ->
      assertContains(result.generated, "  ${slot}_2Ptr: COpaquePointer,")
      assertContains(result.generatedCSharp, "IntPtr ${slot}_2Ptr")
    }
    // A slot lambda's own locals may shadow a slot's delegate (C# 8+), so these keep their names.
    listOf("result", "value0").forEach { slot ->
      assertContains(result.generated, "  ${slot}Ptr: COpaquePointer,")
      assertContains(result.generatedCSharp, "IntPtr ${slot}Ptr")
    }
    // The member each slot calls keeps its own name.
    assertContains(result.generated, "override fun lock(volume: Int)")
    assertContains(result.generatedCSharp, "impl.Lock(value0)")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;

      public sealed class Rex : IPet
      {
          public void Lock(int volume) { }
          public void Release() { }
          public void Token() { }
          public void State() { }
          public void Impl() { }
          public void Error() { }
          public string Result() => "r";
          public void Value0() { }
          public void Dispose() { }
      }

      public static class Probe
      {
          public static int Befriend(Dog dog) => dog.Befriend(new Rex());
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * The Kotlin builder skipped the subscription export for a member-less listener while the C#
   * half still imported it; the ADR-055 contract check then failed the build as an internal
   * generator error. Both halves now bind the pair, as the plain parameter route binds the type.
   */
  @Test
  fun `a listener with no members binds the pair on both halves`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.emptylistener

      interface BarkListener

      $dog
      """.trimIndent(),
    )

    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(
      result.generated,
      "@CName(\"library_tier1_emptylistener__dog_addBarkListener\"",
    )
    assertContains(result.generated, "val bridge = object : tier1.emptylistener.BarkListener {")
    assertContains(
      result.generatedCSharp,
      "Native_AddBarkListener(NugetKotlinHandle handle, out IntPtr error)",
    )
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("Dog.addBarkListener") },
      "kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using Interop;

      public sealed class Quiet : IBarkListener
      {
          public void Dispose() { }
      }

      public static class Probe
      {
          public static IDisposable Listen(Dog dog) => dog.AddBarkListener(new Quiet());
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}

package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-136: every read of an interface-typed value resolves the ADR-084 bridge token before it
 * wraps, so a C#-implemented `Dog` stored by Kotlin comes back as the caller's own object on the
 * suspend and `Flow` routes too, not as a second wrapper over its own bridge.
 *
 * The synchronous route already had this (`interfaceReturnExpression`); the two legacy routes
 * spelled a bare `new Pet(resultPtr)` / `new Pet(h)`. Nothing pinned the emitted read *text* on
 * either one, only the declared signature (`Tier1NestedTypesTest`), which is why the asymmetry
 * survived two ADRs. These cells pin the text.
 */
class Tier1InterfaceAsyncIdentityReadTest {

  private val source: String = """
    package tier1.asyncidentity

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    interface Pet {
      fun speak(): String
    }

    // No Kotlin superclass: a suspend/Flow member on a class that has one does not generate a
    // usable scope handle today, which is a separate defect from the read this test is about.
    class PetSitter {
      private var ward: Pet? = null
      fun take(pet: Pet) { ward = pet }
      suspend fun handBackLater(): Pet = requireNotNull(ward)
      fun wards(): Flow<Pet> = flowOf(requireNotNull(ward))
    }

    fun strayPet(): Pet = object : Pet {
      override fun speak(): String = "Mrrp?"
    }

    suspend fun strayPetLater(): Pet = strayPet()
  """.trimIndent()

  /**
   * Both legacy reads, in one compilation: the completion's `resultPtr` (member and top-level
   * suspend) and the `KotlinFlow<T>` element delegate's `h`. The expression is byte-for-byte the
   * synchronous plan route's, with a different handle local, which is the whole of the fix.
   */
  @Test
  fun `the suspend and Flow reads of an interface resolve the C# original before wrapping`() {
    val result = Tier1Harness.run(
      source,
      fileName = "AsyncIdentity.kt",
      // Load-bearing: `Tier1Harness` puts only `kotlin-stdlib` on the KSP `libraries` path, so
      // without this a `Flow` return is `<ERROR TYPE: Flow>` and every Flow member drops.
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp

    val suspendRead =
      "(NugetMarshal.TryResolveCSharp(resultPtr, out global::Interop.IPet csharpOriginal) " +
          "? csharpOriginal : new global::Interop.Pet(resultPtr))"
    assertContains(
      csharp,
      "t.SetResult($suspendRead);",
      message = "expected the suspend completion to resolve before wrapping; csharp=" +
          "${csharp.lines().filter { it.contains("SetResult") }}",
    )

    val flowRead =
      "read: static h => (NugetMarshal.TryResolveCSharp(h, out global::Interop.IPet " +
          "csharpOriginal) ? csharpOriginal : new global::Interop.Pet(h))"
    assertContains(
      csharp,
      flowRead,
      message = "expected the Flow element delegate to resolve before wrapping; csharp=" +
          "${csharp.lines().filter { it.contains("read: static h") }}",
    )

    // The defect this replaces: a bare wrapper construction over the crossing handle. It made
    // `Assert.Same` fail and forced the consumer to dispose a wrapper over their own object.
    assertFalse(
      csharp.contains("t.SetResult(new global::Interop.Pet(resultPtr))") ||
          csharp.contains("read: static h => new global::Interop.Pet(h)"),
      "expected no bare wrapper read on either async route; csharp=" +
          "${csharp.lines().filter { it.contains("Pet(resultPtr)") || it.contains("Pet(h)") }}",
    )
  }
}

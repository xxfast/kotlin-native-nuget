package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-084 stage 1: a C# class implementing a Kotlin interface reaches Kotlin through a
 * per-interface bridge factory. These cells pin the three seams the integration tests can only
 * observe indirectly: the factory export's slot shape per member kind, the C# bridge-state class,
 * and the `HandleOf` fallback that replaces the ADR-040 `NotSupportedException`.
 */
class Tier1InterfaceBridgeFactoryTest {

  private val source: String = """
    package tier1.bridgefactory

    interface Pet {
      val name: String
      val legs: Int
      val nickname: String?
      fun speak(): String
      fun fetch(item: String): String
      fun nap()
    }

    class Cat(val title: String) : Pet {
      override val name: String = title
      override val legs: Int = 4
      override val nickname: String? = null
      override fun speak(): String = "Meow"
      override fun fetch(item: String): String = "${'$'}title fetches ${'$'}item"
      override fun nap() = Unit

      fun befriend(pet: Pet) = Unit
      fun closestFriend(): Pet = this
    }
  """.trimIndent()

  @Test
  fun `factory export carries one pointer-context pair per member plus the release pair`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.compiledClean, "expected the bridge factory to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"pet_bridge_create\")")
    // Slot order is properties in declaration order, then functions: name, legs, nickname, speak,
    // fetch, nap. Both projections read it off ForwardInterfaceBridgePlanner.
    assertContains(kotlin, "nameGetPtr")
    assertContains(kotlin, "nameGetCtx")
    assertContains(kotlin, "legsGetPtr")
    assertContains(kotlin, "nicknameGetPtr")
    assertContains(kotlin, "speakPtr")
    assertContains(kotlin, "fetchPtr")
    assertContains(kotlin, "napPtr")
    assertContains(kotlin, "releasePtr")
    assertContains(kotlin, "releaseCtx")
    assertContains(kotlin, "token")
  }

  @Test
  fun `the identity marker and its probe pair across both halves`() {
    val result = Tier1Harness.run(source)

    val kotlin: String = result.generated
    assertContains(kotlin, "internal interface NugetCSharpBridge")
    assertContains(kotlin, "public val nugetToken: COpaquePointer")
    assertContains(kotlin, "@CName(\"nuget_csharp_token\")")
    assertContains(kotlin, "(handle.asStableRef<Any>().get() as? NugetCSharpBridge)?.nugetToken")
    // The bridge answers with the GCHandle of the C# object behind it.
    assertContains(kotlin, "override val nugetToken: COpaquePointer = token")

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"nuget_csharp_token\"")
    assertContains(cs, "internal static bool TryResolveCSharp<T>(IntPtr handle, out T original) where T : class")
    assertContains(cs, "original = (T)GCHandle.FromIntPtr(token).Target!;")
    assertContains(cs, "IntPtr token = state.TokenFor(impl);")
    // The return position probes before wrapping, so a stored C#-implemented pet comes back as
    // itself rather than as a second wrapper over its own bridge.
    assertContains(
      cs,
      "return (NugetMarshal.TryResolveCSharp(nativeResult, " +
          "out global::Interop.IPet csharpOriginal) " +
          "? csharpOriginal : new global::Interop.Pet(nativeResult));",
    )
  }

  @Test
  fun `an interface argument disposes only a minted transfer handle`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    // ROADMAP:130: the transfer handle is disposed in a `finally`, so the locals are declared
    // before the `try` and the extraction is an assignment.
    assertContains(cs, "petHandle = NugetMarshal.HandleOf(pet, out petOwned);")
    assertContains(cs, "if (petOwned) { NugetMarshal.Dispose(petHandle); }")
    // A Kotlin-backed wrapper's own `_handle` is never disposed by the call site.
    assertContains(cs, "owned = false;")
  }

  @Test
  fun `each member kind marshals its own result back through the slot`() {
    val result = Tier1Harness.run(source)
    val kotlin: String = result.generated

    // A String getter and a String-returning method share one unwrap shape.
    assertContains(kotlin, "val speakFn = speakPtr.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>()")
    assertContains(kotlin, "override fun speak(): String {")
    assertContains(kotlin, "val ref = speakFn.invoke(speakCtx)!!")
    assertContains(kotlin, "val value = ref.asStableRef<String>().get()")
    // A primitive getter crosses unconverted.
    assertContains(kotlin, "val legsGetFn = legsGetPtr.reinterpret<CFunction<(COpaquePointer) -> Int>>()")
    assertContains(kotlin, "return legsGetFn.invoke(legsGetCtx)")
    // The nullable String getter reads the null pointer as null, never as the empty string.
    assertContains(kotlin, "val ref = nicknameGetFn.invoke(nicknameGetCtx) ?: return null")
    // A String argument is minted here and disposed by the C# reader.
    assertContains(kotlin, "val arg0Ref = StableRef.create(item as Any).asCPointer()")
    assertContains(kotlin, "fetchFn.invoke(arg0Ref, fetchCtx)")
    // A Unit method has no result to marshal.
    assertContains(kotlin, "override fun nap(): Unit {")
    assertContains(kotlin, "napFn.invoke(napCtx)")
  }

  @Test
  fun `the C# bridge state pins one delegate per slot and calls the factory`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "internal sealed class PetBridgeState : NugetBridgeState")
    assertContains(cs, "EntryPoint = \"pet_bridge_create\"")
    assertContains(
      cs,
      "NugetBridgeObjectCallback speak = _ => { string result = impl.Speak(); return NugetMarshal.WrapString(result); };"
    )
    assertContains(cs, "NugetBridgeIntCallback legsGet = _ => { return impl.Legs; };")
    assertContains(cs, "return result is null ? IntPtr.Zero : NugetMarshal.WrapString(result);")
    assertContains(cs, "NugetBridgeObjectObjectCallback fetch = (arg0, _) => {")
    assertContains(cs, "NugetMarshal.FromHandle<string>(arg0)")
    assertContains(cs, "NugetBridgeVoidCallback release = _ => state.FreeAll();")
    assertContains(cs, "state.Pin(nameGet, legsGet, nicknameGet, speak, fetch, nap, release);")
    assertContains(cs, "if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
  }

  @Test
  fun `HandleOf falls back to the bridge instead of throwing`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "return NugetBridge.HandleFor(value);")
    assertFalse(
      cs.contains("passing a C#-implemented interface is not supported yet"),
      "the ADR-040 boundary exception must be gone once a bridge layer is emitted",
    )
    assertContains(cs, "return PetBridgeState.Create(petImpl).KotlinHandle;")
    assertContains(cs, "implements no bridgeable Kotlin interface.")
  }

  @Test
  fun `the bridge object owns a cleaner holding only the release pointer and its context`() {
    val result = Tier1Harness.run(source)
    val kotlin: String = result.generated

    assertContains(kotlin, "val releaseFn = releasePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()")
    // The cleaner's argument is the fn/ctx pair and its block captures nothing: anything reaching
    // the bridge would root the object whose collection is the trigger.
    assertContains(kotlin, "private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) ->")
    assertContains(kotlin, "fn.invoke(ctx)")
    assertFalse(
      kotlin.contains("createCleaner(bridge"),
      "the cleaner must never hold the bridge object itself",
    )
    // The forced-collection support export ships with the factories.
    assertContains(kotlin, "@CName(\"nuget_gc_collect\")")
    assertContains(kotlin, "GC.collect()")
  }

  @Test
  fun `the C# release slot frees that object's handles and is observable`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "IntPtr ctx = state.Root();")
    assertContains(cs, "internal static int ReleasedCount;")
    assertContains(cs, "if (System.Threading.Interlocked.Exchange(ref _freed, 1) != 0) return;")
    assertContains(cs, "if (pin.IsAllocated) pin.Free();")
    assertContains(cs, "if (_self.IsAllocated) _self.Free();")
    assertContains(cs, "System.Threading.Interlocked.Increment(ref ReleasedCount);")
    assertContains(cs, "EntryPoint = \"nuget_gc_collect\"")
    assertContains(cs, "internal static void GcCollect() => Native_GcCollect();")
    assertContains(cs, "if (_token.IsAllocated) _token.Free();")
  }

  @Test
  fun `an interface with an out-of-scope member gets no factory and keeps the boundary throw`() {
    val result = Tier1Harness.run(
      """
      package tier1.bridgefactoryskip

      interface Pet {
        val name: String
        // A `var` needs a setter slot, which stage 1 does not model: the whole interface plans to
        // null rather than shipping a partial ABI.
        var mood: String
      }

      class Cat(val title: String) : Pet {
        override val name: String = title
        override var mood: String = "calm"

        fun befriend(pet: Pet) = Unit
        fun closestFriend(): Pet = this
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected the unplanned interface to still bind; got: ${result.compileErrors}")
    assertFalse(result.generated.contains("pet_bridge_create"), "no factory for an unplanned interface")
    assertFalse(result.generatedCSharp.contains("NugetBridge"), "no bridge layer for an unplanned interface")
    assertContains(result.generatedCSharp, "passing a C#-implemented interface is not supported yet")
  }
}

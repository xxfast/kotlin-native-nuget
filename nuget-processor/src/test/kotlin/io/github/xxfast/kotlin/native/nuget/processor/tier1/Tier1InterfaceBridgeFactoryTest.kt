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
    assertContains(cs, "NugetBridgeObjectCallback speak = _ => { string result = impl.Speak(); return NugetMarshal.WrapString(result); };")
    assertContains(cs, "NugetBridgeIntCallback legsGet = _ => { return impl.Legs; };")
    assertContains(cs, "return result is null ? IntPtr.Zero : NugetMarshal.WrapString(result);")
    assertContains(cs, "NugetBridgeObjectObjectCallback fetch = (arg0, _) => {")
    assertContains(cs, "NugetMarshal.FromHandle<string>(arg0)")
    assertContains(cs, "NugetBridgeVoidCallback release = _ => { };")
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
    assertContains(cs, "ConditionalWeakTable<object, NugetBridgeState> States")
    assertContains(cs, "implements no bridgeable Kotlin interface.")
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

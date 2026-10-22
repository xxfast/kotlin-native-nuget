package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-222: a C# class implementing a Kotlin generic interface (`class Diary : IKeepsake<string>`)
 * crosses to Kotlin at an input position through one erased bridge factory per interface. The
 * Kotlin half is a local generic function instantiated at `Nothing`; the C# half is a generic
 * `KeepsakeBridgeState<T>` minted by the named `NugetBridge.KeepsakeHandleOf<T>` entry. Output and
 * erased positions keep ADR-208's named skip.
 */
class Tier1GenericInterfaceBridgeTest {

  private fun run(pkg: String, body: String): Tier1Result {
    val result: Tier1Result = Tier1Harness.run("package tier1.$pkg\n\n${body.trimIndent()}")
    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    return result
  }

  private val keepsake: String = """
    interface Keepsake<T> {
      var item: T
      fun swap(next: T): T
    }
  """

  @Test
  fun `a closed instantiation binds through the named entry and one erased factory`() {
    val result: Tier1Result = run(
      "gibclosed",
      """
      $keepsake

      fun inspect(keepsake: Keepsake<String>): String = keepsake.item
      fun polish(keepsake: Keepsake<Int>): Int = keepsake.item
      """,
    )
    val kotlin: String = result.generated
    // One factory serves both instantiations.
    assertTrue(Regex("keepsake_bridge_create\"").findAll(kotlin).count() == 1, kotlin)
    assertContains(kotlin, "fun <T> bridge(): tier1.gibclosed.Keepsake<T> = object : tier1.gibclosed.Keepsake<T>, NugetCSharpBridge {")
    assertContains(kotlin, "NugetHandles.retain(bridge<Nothing>())")
    assertContains(kotlin, "override var item: T")
    assertContains(kotlin, "val value = ref.asStableRef<Any>().get() as T")
    assertContains(kotlin, ".asStableRef<tier1.gibclosed.Keepsake<kotlin.String>>().get()")
    assertContains(kotlin, ".asStableRef<tier1.gibclosed.Keepsake<kotlin.Int>>().get()")
    assertFalse("StableRef.create" in kotlin, "no bare StableRef.create in generated exports")

    val cs: String = result.generatedCSharp
    assertContains(cs, "IKeepsake<string> keepsake")
    assertContains(cs, "IKeepsake<int> keepsake")
    assertContains(cs, "NugetBridge.Tier1GibclosedKeepsakeHandleOf(keepsake, out keepsakeOwned)")
    assertContains(cs, "internal static IntPtr Tier1GibclosedKeepsakeHandleOf<T>(Interop.IKeepsake<T> value, out bool owned)")
    assertContains(cs, "internal sealed class Tier1GibclosedKeepsakeBridgeState<T> : NugetBridgeState")
    assertContains(cs, "IntPtr handle = Tier1GibclosedKeepsakeBridgeState<T>.Create(value).KotlinHandle;")
    // A P/Invoke cannot live in a generic class, so the import sits on NugetBridge.
    assertContains(cs, "internal static extern IntPtr Native_Tier1GibclosedKeepsakeBridgeStateCreate(")
    assertContains(cs, "T value0 = NugetMarshal.FromHandle<T>(arg0);")
    assertContains(cs, "return NugetMarshal.WrapOwned<T>(result!);")
    // `WrapOwned` needs the retain export even with no lambda in the module.
    assertContains(cs, "private static extern IntPtr Native_retain_handle(IntPtr handle);")
    // A generic interface has no `HandleFor` arm: an open `typeof` names nothing to test.
    assertFalse("typeof(Interop.IKeepsake" in cs, cs)
  }

  @Test
  fun `an open, a bounded and a trampolined T read the erased owner`() {
    val result: Tier1Result = run(
      "gibopen",
      """
      $keepsake

      interface Furry { val name: String }

      interface Podium<T : Comparable<T>> { val best: T }

      fun <T> trade(keepsake: Keepsake<T>, value: T): T = keepsake.swap(value)
      fun <T : Furry> petName(keepsake: Keepsake<T>): String = keepsake.item.name
      fun <T : Comparable<T>> crown(podium: Podium<T>): String = "crowned ${'$'}{podium.best}"
      interface Named { val title: String }
      fun <T> both(keepsake: Keepsake<T>): String where T : Furry, T : Named = keepsake.item.title
      """,
    )
    val kotlin: String = result.generated
    assertContains(kotlin, ".asStableRef<tier1.gibopen.Keepsake<Any?>>().get()")
    assertContains(kotlin, ".asStableRef<tier1.gibopen.Keepsake<tier1.gibopen.Furry>>().get()")
    // `Nothing` satisfies the self-referencing bound, so the bridge needs no trampoline.
    assertContains(kotlin, "fun <T> bridge(): tier1.gibopen.Podium<T> where T : kotlin.Comparable<T>")
    // A multi-bound `T` no parameter witnesses runs under the ADR-198 trampoline (ADR-217), where
    // the bridge reads through `Any` and casts to the type variable.
    assertContains(kotlin, ".asStableRef<Any>().get() as tier1.gibopen.Keepsake<T>)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "Trade<T>(global::Interop.IKeepsake<T> keepsake, T value)")
    assertContains(cs, "internal sealed class Tier1GibopenPodiumBridgeState<T> : NugetBridgeState where T : notnull")
  }

  @Test
  fun `an extension receiver over a generic interface binds`() {
    val result: Tier1Result = run(
      "gibreceiver",
      """
      $keepsake

      fun Keepsake<String>.shine(): String = "shining ${'$'}item"
      """,
    )
    assertContains(result.generated, "keepsake_bridge_create")
    val cs: String = result.generatedCSharp
    assertContains(cs, "this global::Interop.IKeepsake<string>")
    assertContains(cs, "NugetBridge.Tier1GibreceiverKeepsakeHandleOf(receiver, out receiverOwned)")
  }

  @Test
  fun `a nullable T slot carries null both ways`() {
    val result: Tier1Result = run(
      "gibnullable",
      """
      interface Album<T> { var bookmark: T? }

      fun clear(album: Album<String>) { album.bookmark = null }
      fun maybe(album: Album<String>?): String = album?.bookmark ?: "none"
      """,
    )
    val kotlin: String = result.generated
    assertContains(kotlin, "override var bookmark: T?")
    assertContains(kotlin, "?: return null as T?")
    assertContains(kotlin, "value?.let { NugetHandles.retain(it as Any) }")
    assertContains(
      result.generatedCSharp,
      "album is null ? IntPtr.Zero : NugetBridge.Tier1GibnullableAlbumHandleOf(album, out albumOwned)",
    )
  }

  @Test
  fun `a nested and a multi-parameter generic interface bridge`() {
    val result: Tier1Result = run(
      "gibshapes",
      """
      class Basket {
        interface Picker<T> { fun pick(): T }
      }

      interface Pairing<K, V> {
        val key: K
        fun value(): V
      }

      fun choose(picker: Basket.Picker<String>): String = picker.pick()
      fun describe(pairing: Pairing<String, Int>): String = "${'$'}{pairing.key}=${'$'}{pairing.value()}"
      """,
    )
    val kotlin: String = result.generated
    assertContains(kotlin, "NugetHandles.retain(bridge<Nothing, Nothing>())")
    assertContains(kotlin, ".asStableRef<tier1.gibshapes.Pairing<kotlin.String, kotlin.Int>>().get()")
    val cs: String = result.generatedCSharp
    assertContains(cs, "internal sealed class Tier1GibshapesPairingBridgeState<K, V> : NugetBridgeState")
    assertContains(cs, "NugetBridge.Tier1GibshapesBasketPickerHandleOf(picker, out pickerOwned)")
  }

  @Test
  fun `a member's own type parameter still refuses the bridge, by name`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.gibrefused

      interface Mapper<T> { fun <R> map(item: T): R }

      fun use(mapper: Mapper<String>): String = "used"
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertFalse("mapper_bridge_create" in result.generated, result.generated)
    assertFalse("MapperHandleOf" in result.generatedCSharp, "no entry for a refused bridge")
    val warning: String = result.kspWarnings.single { "tier1.gibrefused" in it && "use" in it }
    assertTrue("`fun map`" in warning && "type parameter" in warning, warning)
  }

  @Test
  fun `output and erased positions keep ADR-208's named skip and name the remedy`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.giboutput

      interface Keepsake<T> { var item: T }

      class Shelf {
        fun keep(): Keepsake<String> = TODO()
        fun all(keepsakes: List<Keepsake<String>>): Int = keepsakes.size
        var keeper: Keepsake<String>? = null
      }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val code: String = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name
    listOf("Shelf.keep", "Shelf.all", "Shelf.keeper").forEach { member ->
      val warning: String = result.kspWarnings.singleOrNull { "$member:" in it }
        ?: error("expected one named skip for $member; got ${result.kspWarnings}")
      assertTrue("non-generic interface" in warning, warning)
      assertTrue("direct parameter" in warning, warning)
    }
    assertFalse("keepsake_bridge_create" in result.generated, "no input position, no bridge")
  }
}

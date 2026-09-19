package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three render sites ADR-133's amendment left spelling an interface bare or with the ADR-040
 * backing wrapper. Every cell here is issue #41's one rule applied at one more position: a type
 * reference in `Interop.cs` carries its namespace, because the file's only usings are `System`
 * ones and nothing else resolves.
 *
 * - (a) the ADR-039 add/remove pair's listener parameter (`translateInterfaceBridgeMethod`), bare
 *   `I$simpleName`: CS0246 for a nested listener (declared `Owner.IWatcher`) and for a
 *   cross-package one.
 * - (b) a `suspend fun` returning `StateFlow<Interface>` (`suspendStateFlowMembers`), which spelled
 *   the wrapper at a declared position and read every `.Value` through `FromHandle<T>`.
 * - (c) a generic **bound** (`legacyBoundInterfaceCsName` and its class-bound arm), bare for a
 *   top-level bound: CS0246 for a cross-package interface bound, CS0118 for a class bound whose
 *   simple name is also a namespace segment.
 *
 * Every cell sets `nuget.rootPackage`, without which every package collapses onto `Interop` and
 * the cross-package half of each defect is unobservable (the trap `Tier1OutOfRootNamespaceTest`
 * documents).
 *
 * Oreo watches from the perch, Mylo taps the window, and both travel in their own crate.
 */
class Tier1InterfaceSpellingSitesTest {

  // --- (a) the add/remove pair's listener parameter ---

  private val pairSources: Map<String, String> = mapOf(
    "Cat.kt" to """
      package tier1.spell.cat

      interface CatEventListener {
        fun onMeow(message: String)
        fun onPurr()
      }
    """.trimIndent(),
    "Watchtower.kt" to """
      package tier1.spell

      import tier1.spell.cat.CatEventListener

      class Aviary(val name: String) {
        interface Watcher {
          fun onLand(perch: String)
          fun onFlyOff()
        }
      }

      class PerchWatch(val name: String) {
        private val watchers: MutableList<Aviary.Watcher> = mutableListOf()
        fun addWatcher(watcher: Aviary.Watcher) { watchers.add(watcher) }
        fun removeWatcher(watcher: Aviary.Watcher) { watchers.remove(watcher) }
        fun rustle() { watchers.forEach { it.onLand(name); it.onFlyOff() } }
      }

      class WindowSill(val name: String) {
        private val listeners: MutableList<CatEventListener> = mutableListOf()
        fun addListener(listener: CatEventListener) { listeners.add(listener) }
        fun removeListener(listener: CatEventListener) { listeners.remove(listener) }
        fun tap() { listeners.forEach { it.onMeow(name); it.onPurr() } }
      }
    """.trimIndent(),
  )

  @Test
  fun `an add-remove pair spells its listener parameter with the qualified interface`() {
    val result: Tier1Result = Tier1Harness.run(
      pairSources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.spell"),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp

    // Nested: `I` goes on the LAST segment only, so the parameter reads `Aviary.IWatcher`.
    assertContains(
      csharp,
      "public IDisposable AddWatcher(global::Interop.Aviary.IWatcher listener)",
      message = "expected the nested listener to carry its owner chain and namespace; csharp=" +
          "${csharp.lines().filter { it.contains("AddWatcher") }}",
    )
    // Cross-package: the sub-package maps to a sub-namespace, which `TestLibrary`-side has no
    // using for.
    assertContains(
      csharp,
      "public IDisposable AddListener(global::Interop.Cat.ICatEventListener listener)",
      message = "expected the cross-package listener to be qualified; csharp=" +
          "${csharp.lines().filter { it.contains("AddListener") }}",
    )
    assertFalse(
      csharp.contains("(IWatcher listener)") || csharp.contains("(ICatEventListener listener)"),
      "expected no bare listener parameter left; csharp=" +
          "${csharp.lines().filter { it.contains("listener)") }}",
    )
  }

  // --- (b) a suspend fun returning StateFlow<Interface> ---

  private val stateFlowSource: String = """
    package tier1.spell

    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.yield

    class Cat(val name: String)

    class Aviary(val name: String) {
      interface Keeper {
        fun greet(): String
      }

      private val onDuty: MutableStateFlow<Keeper> =
        MutableStateFlow(object : Keeper { override fun greet(): String = "on duty" })

      private val resident: MutableStateFlow<Cat> = MutableStateFlow(Cat("Oreo"))

      suspend fun keeperReport(): StateFlow<Keeper> { yield(); return onDuty }

      suspend fun catReport(): StateFlow<Cat> { yield(); return resident }

      fun book(keeper: Keeper) { onDuty.value = keeper }
    }
  """.trimIndent()

  @Test
  fun `a suspend StateFlow of an interface is typed with the interface and reads through a resolve`() {
    val result: Tier1Result = Tier1Harness.run(
      stateFlowSource,
      fileName = "Aviary.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1.spell"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp

    assertContains(
      csharp,
      "public Task<KotlinStateFlow<global::Interop.Aviary.IKeeper>> KeeperReportAsync(",
      message = "expected the interface element spelling on the suspend StateFlow route; csharp=" +
          "${csharp.lines().filter { it.contains("KeeperReport") }}",
    )
    assertFalse(
      csharp.contains("KotlinStateFlow<global::Interop.Aviary.Keeper>"),
      "expected no wrapper-typed StateFlow; csharp=" +
          "${csharp.lines().filter { it.contains("KotlinStateFlow<") }}",
    )
    // ADR-136's read, in ADR-123's `read:` slot: `FromHandle<T>` has no factory for an interface
    // and its Activator branch cannot construct one, so the corrected spelling needs this.
    assertContains(
      csharp,
      "read: static h => (NugetMarshal.TryResolveCSharp(h, out global::Interop.Aviary.IKeeper",
      message = "expected the resolve-then-wrap element read; csharp=" +
          "${csharp.lines().filter { it.contains("read:") }}",
    )
  }

  @Test
  fun `a suspend StateFlow of a class keeps the shipped construction with no element read`() {
    val result: Tier1Result = Tier1Harness.run(
      stateFlowSource,
      fileName = "Aviary.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1.spell"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val csharp: String = result.generatedCSharp

    assertContains(
      csharp,
      "public Task<KotlinStateFlow<global::Interop.Cat>> CatReportAsync(",
      message = "expected the class element to keep `qualifiedElementCsType`; csharp=" +
          "${csharp.lines().filter { it.contains("CatReport") }}",
    )
    // The twin in the same file is the interface one, so exactly one `read:` proves the class
    // element's construction is byte-identical to what shipped.
    assertEquals(
      1,
      csharp.lines().count { it.contains("read: static h =>") },
      "expected only the interface element to pass a read; csharp=" +
          "${csharp.lines().filter { it.contains("read:") }}",
    )
  }

  // --- (c) generic bounds ---

  private val crateSources: Map<String, String> = mapOf(
    "Cat.kt" to """
      package tier1.spell.cat

      interface Pet {
        fun speak(): String
      }

      class Cat(val name: String)
    """.trimIndent(),
    "Crates.kt" to """
      package tier1.spell

      import tier1.spell.cat.Cat
      import tier1.spell.cat.Pet

      class PetCrate<T : Pet>(val value: T) {
        fun label(): String = "crate for " + value.speak()
      }

      class CatCrate<T : Cat>(val value: T) {
        fun label(): String = "cat crate for " + value.name
      }
    """.trimIndent(),
  )

  @Test
  fun `a cross-package generic bound is qualified for an interface and for a class`() {
    val result: Tier1Result = Tier1Harness.run(
      crateSources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.spell"),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp

    assertContains(
      csharp,
      "where T : global::Interop.Cat.IPet",
      message = "expected the top-level interface bound to be qualified; csharp=" +
          "${csharp.lines().filter { it.contains("where T") }}",
    )
    // The class-bound twin is the harder half: `Cat` is also a namespace segment here, so the bare
    // spelling is CS0118 ("is a namespace but is used like a type"), not CS0246.
    assertContains(
      csharp,
      "where T : global::Interop.Cat.Cat",
      message = "expected the class bound to be qualified; csharp=" +
          "${csharp.lines().filter { it.contains("where T") }}",
    )
    assertFalse(
      csharp.lines().any { it.trim() == "where T : IPet" || it.trim() == "where T : Cat" },
      "expected no bare bound left; csharp=${csharp.lines().filter { it.contains("where T") }}",
    )
  }
}

package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-176: an interface is a collection component. The runtime failure this guards is "binds,
 * then throws": a member reading `FromHandle<IPet>` per element with no backing `Pet` wrapper and
 * no `Factories[typeof(IPet)]` key. `compiledClean` only says the generated KOTLIN compiled; every
 * cell here asserts the generated C# text that decides whether the element can materialise.
 *
 * Each reachability cell declares its interface in exactly ONE collection position, so the only
 * thing that can produce the factory key is the component walk under test.
 */
class Tier1InterfaceCollectionTest {

  private val petFactory: String =
    "[typeof(global::Interop.IPet)] = static handle => new global::Interop.Pet(handle, out _)"

  private fun Tier1Result.assertPetMaterialises(where: String) {
    assertTrue(compiledClean, "expected $where to compile; got: $compileErrors")
    val csharp: String = generatedCSharp
    assertContains(
      csharp,
      "public sealed class Pet : IPet",
      message = "no backing wrapper for $where:\n$csharp",
    )
    assertContains(csharp, petFactory, message = "no IPet factory key for $where:\n$csharp")
  }

  /** Finding 2: the getter binds on main but nothing makes `Pet` reachable. */
  @Test
  fun `collection property getter alone makes the interface element materialisable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollgetter

      interface Pet { val name: String }

      class Shelter {
        val residents: List<Pet> get() = listOf(object : Pet { override val name: String = "Oreo" })
      }
      """.trimIndent()
    )
    assertContains(result.generatedCSharp, "IReadOnlyList<global::Interop.IPet> Residents")
    result.assertPetMaterialises("Shelter.residents")
  }

  /** Parameter and property setter bind, writing through `CreateList`. */
  @Test
  fun `interface list parameter and property setter bind through CreateList`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollparam

      interface Pet { val name: String }

      class Shelter {
        var roster: List<Pet> = emptyList()
        fun count(pets: List<Pet>): Int = pets.size
        fun echo(pets: List<Pet>): List<Pet> = pets
      }
      """.trimIndent()
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public int Count(IReadOnlyList<global::Interop.IPet> pets)")
    assertContains(csharp, "NugetMarshal.CreateList(pets)")
    assertContains(
      csharp,
      "NugetMarshal.CreateList(value)",
      message = "Roster setter missing:\n$csharp",
    )
    assertFalse(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected no input skip; kspWarnings=${result.kspWarnings}",
    )
    result.assertPetMaterialises("Shelter.count/echo/roster")
  }

  /** Set, Map value, nullable element and top-level positions. */
  @Test
  fun `set map value nullable and top level interface collections bind`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollkinds

      interface Pet { val name: String }

      class Shelter {
        fun petSet(): Set<Pet> = emptySet()
        fun byName(): Map<String, Pet> = emptyMap()
        fun maybe(): List<Pet?> = emptyList()
      }

      fun lineup(): List<Pet> = emptyList()
      """.trimIndent()
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "IReadOnlySet<global::Interop.IPet> PetSet()")
    assertContains(csharp, "IReadOnlyDictionary<string, global::Interop.IPet> ByName()")
    assertContains(csharp, "IReadOnlyList<global::Interop.IPet?> Maybe()")
    assertContains(csharp, "IReadOnlyList<global::Interop.IPet> Lineup()")
    result.assertPetMaterialises("the Set/Map/nullable/top-level cells")
  }

  /** Finding 6: a Map KEY alone must be walked. */
  @Test
  fun `map key alone makes the interface materialisable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollkey

      interface Pet { val name: String }

      class Shelter {
        fun colours(): Map<Pet, String> = emptyMap()
      }
      """.trimIndent()
    )
    assertContains(
      result.generatedCSharp,
      "IReadOnlyDictionary<global::Interop.IPet, string> Colours()",
    )
    result.assertPetMaterialises("Map<Pet, String>")
  }

  /** Finding 6: a nested collection component must be walked recursively. */
  @Test
  fun `interface inside a nested collection is materialisable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollnested

      interface Pet { val name: String }

      class Shelter {
        fun wings(): List<Map<String, Pet>> = emptyList()
      }
      """.trimIndent()
    )
    result.assertPetMaterialises("List<Map<String, Pet>>")
  }

  /** Finding 6: the walk yields a SET: key and value interfaces both register. */
  @Test
  fun `map with interface key and interface value registers both`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecolltwo

      interface Pet { val name: String }
      interface Owner { val name: String }

      class Shelter {
        fun adoptions(): Map<Pet, Owner> = emptyMap()
      }
      """.trimIndent()
    )
    result.assertPetMaterialises("Map<Pet, Owner> (key)")
    assertContains(
      result.generatedCSharp,
      "[typeof(global::Interop.IOwner)] = " +
        "static handle => new global::Interop.Owner(handle, out _)",
    )
  }

  /** Finding 7: a legacy suspend member is the only position. */
  @Test
  fun `suspend member returning an interface list alone makes it materialisable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollsuspend

      interface Pet { val name: String }

      class Shelter {
        suspend fun pets(): List<Pet> = emptyList()
      }
      """.trimIndent()
    )
    assertContains(result.generatedCSharp, "Task<IReadOnlyList<global::Interop.IPet>> PetsAsync(")
    result.assertPetMaterialises("suspend Shelter.pets")
  }

  /** Finding 7: a legacy top-level suspend function is the only position. */
  @Test
  fun `top level suspend returning an interface list alone makes it materialisable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecolltopsuspend

      interface Pet { val name: String }

      suspend fun pets(): List<Pet> = emptyList()
      """.trimIndent()
    )
    assertContains(result.generatedCSharp, "Task<IReadOnlyList<global::Interop.IPet>> PetsAsync(")
    result.assertPetMaterialises("top-level suspend pets")
  }

  /** Finding 7: a Flow element component is the only position. */
  @Test
  fun `flow of interface list alone makes it materialisable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollflow

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.emptyFlow

      interface Pet { val name: String }

      class Shelter {
        fun stream(): Flow<List<Pet>> = emptyFlow()
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertContains(
      result.generatedCSharp,
      "KotlinFlow<IReadOnlyList<global::Interop.IPet>> Stream()",
    )
    result.assertPetMaterialises("Flow<List<Pet>>")
  }

  /** Finding 8: a nested interface element needs a nested factory key. */
  @Test
  fun `list of nested interface registers the nested factory key`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollnestediface

      class Aviary {
        interface Keeper { fun greet(): String }
        fun keepers(): List<Keeper> = emptyList()
      }
      """.trimIndent()
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "IReadOnlyList<global::Interop.Aviary.IKeeper> Keepers()")
    assertContains(
      csharp,
      "[typeof(global::Interop.Aviary.IKeeper)] = " +
        "static handle => new global::Interop.Aviary.Keeper(handle, out _)",
    )
  }

  /**
   * Finding 8, pre-existing on main: a collection of a nested CLASS binds with no factory key,
   * because `factoryEntries` only walks namespace-level declarations.
   */
  @Test
  fun `list of nested class registers the nested factory key`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecollnestedclass

      class Aviary {
        class Perch(val height: Int)
        fun perchRow(): List<Perch> = emptyList()
      }
      """.trimIndent()
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "IReadOnlyList<global::Interop.Aviary.Perch> PerchRow()")
    assertContains(
      csharp,
      "[typeof(global::Interop.Aviary.Perch)] = " +
        "static handle => new global::Interop.Aviary.Perch(",
    )
  }

  /**
   * Finding 10: the throw-path cleanup in `ReadList`/`ReadSet`/`ReadMap` must dispose only
   * marshaller-built wrappers, never a token-resolved C# original the consumer owns.
   */
  @Test
  fun `DisposeMaterialized disposes only INugetHandle items`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecolldispose

      interface Pet { val name: String }

      class Shelter {
        fun pets(): List<Pet> = emptyList()
      }
      """.trimIndent()
    )
    val csharp: String = result.generatedCSharp
    val start: Int = csharp.indexOf("private static void DisposeMaterialized<T>")
    assertTrue(start >= 0, "expected DisposeMaterialized to be emitted:\n$csharp")
    val body: String = csharp.substring(start, csharp.indexOf("\n        }", start))
    assertContains(body, "is INugetHandle")
    assertFalse("(item as IDisposable)?.Dispose()" in body, "unguarded dispose remains:\n$body")
  }

  /**
   * ADR-176 transitive reachability: `Brush` appears only inside collections returned by the
   * members (sync and suspend) of `Groomer`, which is itself reachable. The walk has to reach a
   * fixed point over reachable interfaces' own members, or `IGroomer.Brushes()` binds with no
   * `Brush` wrapper and throws at the first element.
   */
  @Test
  fun `interface reachable only through another interface's collection members is materialisable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecolltransitive

      interface Brush { val name: String }

      interface Groomer {
        fun brushes(): List<Brush>
        suspend fun brushesLater(): List<Brush>
      }

      class Salon {
        fun groomer(): Groomer = error("unused")
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.compiledClean, "expected the salon to compile; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "IReadOnlyList<global::Interop.IBrush> Brushes()")
    assertContains(
      csharp,
      "public sealed class Brush : IBrush",
      message = "no Brush wrapper:\n$csharp",
    )
    assertContains(
      csharp,
      "[typeof(global::Interop.IBrush)] = " +
        "static handle => new global::Interop.Brush(handle, out _)",
      message = "no IBrush factory key:\n$csharp",
    )
  }

  /**
   * ADR-174 import gate: a module whose ONLY suspend / Flow / StateFlow members are declared on a
   * reachable interface must still get the coroutine imports (`launchForCSharp`,
   * `CoroutineScope`, ...). `hasSuspendFunctions` / `needsFlowImports` used to consult classes,
   * sealed arms and top-level functions only, so this generated Kotlin failed to compile.
   * `compiledClean` is the load-bearing assertion here: it is the generated Kotlin that broke.
   */
  @Test
  fun `async members only on a reachable interface still get the coroutine imports`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifaceasynconly

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.StateFlow

      interface Groomer {
        suspend fun brushCount(): Int
        fun strokes(): Flow<Int>
        val mood: StateFlow<Int>
      }

      class Salon {
        fun groomer(): Groomer = error("unused")
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.kspSucceeded, "expected KSP to succeed; kspErrors=${result.kspErrors}")
    assertTrue(
      result.compiledClean,
      "expected the generated Kotlin to compile; got: ${result.compileErrors}",
    )
    assertContains(result.generated, "launchForCSharp(")
    assertContains(result.generated, "collectForCSharp(")
  }

  /**
   * ADR-176 x ADR-174 amendment: the collection closure and the async-super promotion reach a
   * JOINT fixed point. `Derived` is reached only as a collection element; its async-carrying super
   * `Base` is promoted from that; `Item` is reached only through `Base.items()`. Run either
   * closure once, in either order, and one of the three interfaces is left without a backing
   * wrapper and `Factories` key.
   */
  @Test
  fun `collection closure and async super promotion feed each other`() {
    val result = Tier1Harness.run(
      """
      package tier1.ifacecolljoint

      interface Item { val name: String }

      interface Base {
        suspend fun ping(): Int
        fun items(): List<Item>
      }

      interface Derived : Base { val label: String }

      class Shop {
        fun lineup(): List<Derived> = emptyList()
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.kspSucceeded, "expected KSP to succeed; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected the generated Kotlin to compile; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    listOf("Derived", "Base", "Item").forEach { name ->
      assertContains(
        csharp,
        "[typeof(global::Interop.I$name)] = static handle => new global::Interop.$name(handle, out _)",
        message = "no I$name factory key:\n$csharp",
      )
    }
  }
}

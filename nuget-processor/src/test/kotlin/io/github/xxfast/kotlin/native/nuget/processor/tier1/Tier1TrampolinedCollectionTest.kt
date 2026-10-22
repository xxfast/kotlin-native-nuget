package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-217: a `T` whose Kotlin spelling needs a type variable gets ADR-198's trampoline, and the
 * collection element cast (or the generic-class cast) happens inside it. Two kinds of `T` need
 * one: an unspellable bound (`Enum<T>`, an invariant `Node<T>`), and an intersection no bare
 * parameter witnesses (`Arena.team(pets: List<T>)`, `crown(basket: Basket<T>)`). Before this ADR
 * every one was refused by name, except `Arena.team`, whose generated Kotlin did not compile.
 *
 * Also pins the ADR-217 reachability fix: an interface reachable only as a `T` bound gets its
 * C# bridge arm, so a C#-implemented one crosses `Wrap<T>`.
 */
class Tier1TrampolinedCollectionTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val fixture: String =
    """
    package tier1.trampolined

    enum class Medal { BRONZE, SILVER, GOLD }

    object Judge {
      fun <T : Enum<T>> sorted(medals: List<T>): List<T> = medals.sortedDescending()
      fun <T : Enum<T>> pad(medals: List<T?>): List<T?> = medals + null
      fun <T : Enum<T>> unique(medals: Set<T>): Set<T> = medals
      fun <T : Enum<T>> scores(board: Map<T, Int>): Map<T, Int> = board
      fun <T : Enum<T>> heats(heats: List<List<T>>): List<List<T>> = heats
    }

    class Rosette<T : Enum<T>>(val winner: T) {
      fun rank(items: List<T>): List<T> = (items + winner).sortedDescending()
    }

    class Sash<T : Enum<T>>(val ribbons: List<T>) {
      val best: T get() = ribbons.max()
    }

    fun <T : Enum<T>> ordered(medals: List<T>): List<T> = medals.sortedBy { it.name }

    interface Node<T> { fun link(other: T): T }

    fun <T : Node<T>> braid(beads: List<T>): T = beads.reduce { a, b -> a.link(b) }

    interface Pet { val name: String }
    interface Trainable { val tricks: Int }

    class Arena<T>(val champion: T) where T : Pet, T : Trainable {
      fun team(pets: List<T>): Int = pets.sumOf { it.tricks } + champion.tricks
      fun mix(pets: List<T>, lead: T): String = (listOf(lead) + pets).joinToString { it.name }
      fun swap(basket: Basket<T>): Int = basket.item.tricks
    }

    class Basket<T>(val item: T)

    class Groomer {
      fun <T> crown(basket: Basket<T>): T where T : Pet, T : Trainable = basket.item
      fun <T : Enum<T>> maybe(basket: Basket<T>?): T? = basket?.item
    }

    interface Snoozer { val snore: String }

    fun <T : Snoozer> tuckIn(snoozer: T): String = snoozer.snore
    """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(fixture, processorOptions = options)

  @Test
  fun `every trampolined collection position binds and the Kotlin compiles`() {
    val result: Tier1Result = run()
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")

    val enumBound = "where T : struct, global::System.Enum"
    listOf(
      "public static IReadOnlyList<T> Sorted<T>(IReadOnlyList<T> medals) $enumBound",
      "public static IReadOnlyList<T?> Pad<T>(IReadOnlyList<T?> medals) $enumBound",
      "public static IReadOnlySet<T> Unique<T>(IReadOnlySet<T> medals) $enumBound",
      "public static IReadOnlyDictionary<T, int> Scores<T>(IReadOnlyDictionary<T, int> board) " +
        enumBound,
      "public static IReadOnlyList<IReadOnlyList<T>> Heats<T>(" +
        "IReadOnlyList<IReadOnlyList<T>> heats) $enumBound",
      "public static IReadOnlyList<T> Ordered<T>(IReadOnlyList<T> medals) $enumBound",
      "public IReadOnlyList<T> Rank(IReadOnlyList<T> items)",
      "public Sash(IReadOnlyList<T> ribbons)",
      "public int Team(IReadOnlyList<T> pets)",
      "public string Mix(IReadOnlyList<T> pets, T lead)",
      "public T Crown<T>(global::TestLibrary.Trampolined.Basket<T> basket)",
      "public static string TuckIn<T>(T snoozer) where T : global::TestLibrary.Trampolined.ISnoozer",
    ).forEach { signature ->
      assertTrue(
        result.generatedCSharp.contains(signature),
        "expected `$signature`; got: ${result.generatedCSharp.lines().filter { "public" in it }}",
      )
    }
  }

  @Test
  fun `a trampolined element is checked against its star-projected bound and cast to T`() {
    val kotlin: String = run().generated
    assertContains(kotlin, "bounded as kotlin.Enum<*>; bounded as T }")
    assertContains(kotlin, "bounded as kotlin.Enum<*>?; bounded as T? }")
    assertContains(kotlin, "bounded as tier1.trampolined.Node<*>; bounded as T }")
    assertContains(kotlin, "nugetTrampoline<Nothing>()")
    val bareCreate: Regex = Regex("""(?<!\.)StableRef\.create\(""")
    assertFalse(kotlin.contains(bareCreate), "no bare StableRef.create")
  }

  @Test
  fun `a nullable value-type element reads through FromHandle of T nullable`() {
    val cs: String = run().generatedCSharp
    assertContains(cs, "NugetMarshal.ReadList<T?>(")
    assertContains(cs, "=> NugetMarshal.FromHandle<T?>(")
  }

  /**
   * `Arena.team` sees `T` only inside a `List`, so no bare witness types `Arena<*>`; the
   * trampoline declares both bounds and casts the receiver and each element. `mix` has a bare
   * witness and keeps the ADR-147 `nugetTypedOwner` shape.
   */
  @Test
  fun `a multi-bound owner T only inside a list runs under the trampoline, a witnessed one does not`() {
    val kotlin: String = run().generated
    val team: String = kotlin.substringAfter("__arena_team(").substringBefore("@CName")
    assertContains(team, "where T : tier1.trampolined.Pet, T : tier1.trampolined.Trainable")
    assertContains(team, "as tier1.trampolined.Arena<T>).team(")
    assertContains(team, "bounded as tier1.trampolined.Trainable; bounded as T }")
    val mix: String = kotlin.substringAfter("__arena_mix(").substringBefore("@CName")
    assertContains(mix, "nugetTypedOwner")
    assertFalse(mix.contains("nugetTrampoline"), "a bare witness keeps the ADR-147 shape: $mix")
    // The same rule for an owner `T` only inside an open generic class.
    val swap: String = kotlin.substringAfter("__arena_swap(").substringBefore("@CName")
    assertContains(
      swap,
      "as tier1.trampolined.Arena<T>).swap((basket.asStableRef<Any>().get() as tier1.trampolined.Basket<T>))",
    )
  }

  @Test
  fun `a multi-bound member T only inside a generic class casts the box inside the trampoline`() {
    val kotlin: String = run().generated
    val crown: String = kotlin.substringAfter("__groomer_crown(").substringBefore("@CName")
    assertContains(crown, "where T : tier1.trampolined.Pet, T : tier1.trampolined.Trainable")
    assertContains(crown, "asStableRef<Any>().get() as tier1.trampolined.Basket<T>)")
    assertContains(crown, ".crown<T>(")
    val maybe: String = kotlin.substringAfter("__groomer_maybe(").substringBefore("@CName")
    assertContains(maybe, "(basket?.asStableRef<Any>()?.get() as tier1.trampolined.Basket<T>?)")
  }

  @Test
  fun `an interface reachable only as a T bound gets its C# bridge arm`() {
    val cs: String = run().generatedCSharp
    assertContains(cs, "if (declared == typeof(TestLibrary.Trampolined.ISnoozer))")
  }

  @Test
  fun `the generated C# compiles against a consumer`() {
    val result: Tier1Result = run()
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using TestLibrary.Trampolined;

      public static class Consumer
      {
          public static int Use(Rosette<Medal> rosette, Groomer groomer, Basket<Medal> medals)
          {
              IReadOnlyList<Medal> sorted = Judge.Sorted(new[] { Medal.Gold });
              IReadOnlyList<Medal?> padded = Judge.Pad(new Medal?[] { Medal.Gold, null });
              IReadOnlyDictionary<Medal, int> scores =
                  Judge.Scores(new Dictionary<Medal, int> { [Medal.Gold] = 1 });
              IReadOnlyList<Medal> ranked = rosette.Rank(new[] { Medal.Silver });
              IReadOnlyList<Medal> ordered = Fixture.Ordered(new[] { Medal.Bronze });
              using var sash = new Sash<Medal>(new[] { Medal.Gold });
              return sorted.Count + padded.Count + scores.Count + ranked.Count + ordered.Count
                  + (int)sash.Best;
          }
      }
      """.trimIndent(),
      // The `ISnoozer` bridge arm is an interface bridge, which uses function pointers.
      allowUnsafe = true,
    )
  }
}

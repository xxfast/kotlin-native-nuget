package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-198 against the routes that landed beside it: a member function's own type parameter
 * (ADR-197), nested and inner types of a generic owner (ADR-196), an abstract class materialised
 * through a backing wrapper (ADR-064), and the ADR-094 enum box written through each of them. Every
 * cell compiles both halves for real.
 */
class Tier1EnumSelfBoundInteractionTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private fun assertCleanKotlin(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertTrue(
      result.compileWarnings.none { it.contains("nchecked cast") },
      "${result.compileWarnings}",
    )
  }

  /**
   * A member function's own `T : Enum<T>` (or `T : Node<T>`) rides the same trampoline as a
   * class's: the call names the trampoline's type variable instead of an erased argument no type satisfies.
   * On a class, an object and a sealed arm.
   */
  @Test
  fun `a member function's own Enum-bound type parameter binds through the trampoline`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enummethod

      enum class Medal { BRONZE, SILVER, GOLD }

      interface Node<T> { fun link(other: T): T }

      class Bead(val name: String) : Node<Bead> {
        override fun link(other: Bead): Bead = Bead(name + other.name)
      }

      class Judge {
        fun <T : Enum<T>> rank(x: T): String = x.name + "#" + x.ordinal
        fun <T : Enum<T>> best(a: T, b: T): T = if (a > b) a else b
        fun <T : Enum<T>> maybe(x: T?): T? = x
        fun <T : Node<T>> join(a: T, b: T): T = a.link(b)
      }

      object Panel {
        fun <T : Enum<T>> rank(x: T): String = x.name
      }

      sealed class Round {
        class Final(val heat: Int) : Round() {
          fun <T : Enum<T>> rank(x: T): String = x.name + heat
        }
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertCleanKotlin(result)
    assertTrue(
      result.kspWarnings.none { it.contains("bounded by `Enum`") },
      "${result.kspWarnings}",
    )
    assertContains(result.generated, "where T : kotlin.Enum<T>")
    assertContains(result.generated, ".rank<T>(")
    assertContains(result.generated, "where T : tier1.enummethod.Node<T>")
    assertContains(
      result.generatedCSharp,
      "public string Rank<T>(T x) where T : struct, global::System.Enum",
    )
    assertContains(result.generatedCSharp, "NugetMarshal.FromHandle<T?>(nativeResult)")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Enummethod;

      public static class Probe
      {
          public static string Run()
          {
              using var judge = new Judge();
              Medal best = judge.Best(Medal.Silver, Medal.Gold);
              Medal? none = judge.Maybe<Medal>(null);
              using var final = new Round.Final(2);
              using var oreo = new Bead("Oreo");
              using var mylo = new Bead("Mylo");
              using Bead joined = judge.Join(oreo, mylo);
              return judge.Rank(Medal.Gold) + best + none + Panel.Rank(Medal.Bronze) +
                  final.Rank(Medal.Silver) + joined.Name;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * The ADR-094 enum box through the routes beside it: a generic method's `T` (`depot.Echo(Mood
   * .Calm)`, `Echo<Mood?>(null)`), a generic class nested in a generic owner (`Teapot.Cozy<Mood>`)
   * and an inner class capturing the owner's `T` (`Teapot.Infuser<Mood, Mood>`). All of them box
   * through the one `Wrap<T>`, so the enum's `Boxers` row serves every one.
   */
  @Test
  fun `an enum crosses a generic method and a holder-nested or inner generic type`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumholder

      enum class Mood { CALM, GRUMPY }

      class Depot {
        fun <T> echo(item: T): T = item
      }

      class Teapot<T>(val item: T) {
        class Cozy<U>(val pattern: U) {
          fun swap(next: U): U = next
        }

        inner class Infuser<U>(val leaf: U) {
          fun both(): String = "${'$'}item/${'$'}leaf"
        }
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertCleanKotlin(result)
    assertContains(
      result.generatedCSharp,
      "[typeof(global::TestLibrary.Enumholder.Mood)] = static value => NugetErrorNative.Check(",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Enumholder;

      public static class Probe
      {
          public static string Run()
          {
              using var depot = new Depot();
              Mood calm = depot.Echo(Mood.Calm);
              Mood? none = depot.Echo<Mood?>(null);
              using var cozy = new Teapot.Cozy<Mood>(Mood.Grumpy);
              Mood swapped = cozy.Swap(Mood.Calm);
              using var teapot = new Teapot<Mood>(Mood.Calm);
              using var infuser = new Teapot.Infuser<Mood, Mood>(teapot, Mood.Grumpy);
              return "" + calm + none + cozy.Pattern + swapped + infuser.Both();
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * An enum-bound owner with nested types: a plain nested class and enum and a nested generic
   * class under its own enum bound bind on the holder; an `inner` class capturing the
   * enum-bounded `T` has no erased spelling for the captured outer, so it is ADR-196's named skip.
   */
  @Test
  fun `an enum-bound owner with nested and inner types compiles`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumnested

      enum class Medal { BRONZE, GOLD }

      class Rosette<T : Enum<T>>(val winner: T) {
        class Ribbon(val colour: String)

        enum class Tier { LOW, HIGH }

        class Badge<U : Enum<U>>(val mark: U) {
          fun same(other: U): Boolean = mark == other
        }

        inner class Pin(val size: Int) {
          fun winnerName(): String = winner.name
          fun beats(other: T): Boolean = winner > other
        }
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertContains(
      result.generatedCSharp,
      "public class Badge<U> : IDisposable, INugetHandle where U : struct, global::System.Enum",
    )
    // The `inner` class has no erased type to read its outer instance back as: ADR-196's named
    // refusal, with the reason saying why.
    assertTrue(
      result.kspWarnings.any { warning ->
        warning.contains("SKIPPED_NESTED_DECLARATION") && warning.contains("Rosette.Pin") &&
            warning.contains("self-referencing-bounded type parameter `T`")
      },
      "${result.kspWarnings}",
    )
    assertFalse(result.generatedCSharp.lines().any { it.contains("class Pin") })
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Enumnested;

      public static class Probe
      {
          public static string Run()
          {
              using var ribbon = new Rosette.Ribbon("blue");
              using var badge = new Rosette.Badge<Medal>(Medal.Gold);
              return ribbon.Colour + badge.Same(Medal.Bronze) + Rosette.Tier.High;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * An abstract enum-bound class and its concrete subclass: the abstract member takes a `T`, so its
   * export rides the trampoline. The two do not combine further: the ADR-064 backing wrapper is
   * for non-generic abstract classes only (see the known-limit cell below).
   */
  @Test
  fun `an abstract enum-bound class with a backing wrapper compiles`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumabstract

      enum class Medal { BRONZE, GOLD }

      abstract class Trophy<T : Enum<T>>(val prize: T) {
        abstract fun engrave(other: T): String
        abstract val note: String
      }

      class Cup(prize: Medal) : Trophy<Medal>(prize) {
        override fun engrave(other: Medal): String = prize.name + ">" + other.name
        override val note: String = "cup"
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertCleanKotlin(result)
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Enumabstract;

      public static class Probe
      {
          public static string Run(Trophy<Medal> trophy)
          {
              using var cup = new Cup(Medal.Bronze);
              return trophy.Engrave(Medal.Bronze) + trophy.Note + cup.Engrave(Medal.Gold);
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * Known limit, independent of ADR-198 and pinned so a fix shows up here: the abstract backing
   * wrapper is for non-generic abstract classes only (`hasAbstractBacking`), so a function
   * RETURNING a generic abstract class at a closed type (`Shelf<String>`, or `Trophy<Medal>`)
   * reconstructs it with `new Shelf<string>(handle, out _)`, CS0144.
   */
  @Test
  fun `a generic abstract class at a return position still constructs the abstract type`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.abstractreturn

      abstract class Shelf<T>(val item: T) {
        abstract fun label(): String
      }

      class Plank(item: String) : Shelf<String>(item) {
        override fun label(): String = item
      }

      fun stock(): Shelf<String> = Plank("oak")
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    val build: Tier1CSharpBuild =
      Tier1CSharpCompile.compile(result, "public static class Probe { }")
    assertFalse(build.succeeded, "the known limit compiled; flip this cell")
    assertContains(build.log, "CS0144")
  }

  /**
   * The legacy generic-function route dispatches on a `T` parameter and emits a return of either
   * `T` or a generic class over `T`. A return that is neither (`String`, `Unit`) is refused on both
   * halves and named; it used to bind as a `T` return on the C# half and as a generic-class handle
   * on the Kotlin half.
   */
  @Test
  fun `a generic function returning neither T nor a generic class is a named skip`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.genericreturn

      fun <T> describe(value: T): String = value.toString()

      fun <T> touch(value: T) { value.hashCode() }

      fun <T> keep(value: T): T = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    val declared: List<String> = result.generatedCSharp.lines().map { it.trim() }
      .filter { it.startsWith("public static") }
    assertFalse(declared.any { it.contains(" Describe<") }, "$declared")
    assertFalse(declared.any { it.contains(" Touch<") }, "$declared")
    assertTrue(declared.any { it.contains(" Keep<") }, "$declared")
    assertTrue(
      result.kspWarnings.any { it.contains("describe") && it.contains("SKIPPED_") },
      "${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("touch") && it.contains("SKIPPED_") },
      "${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Genericreturn;

      public static class Probe
      {
          public static int Run() => Fixture.Keep(4);
      }
      """.trimIndent(),
    )
  }
}

package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-197 against the routes that landed beside it: an abstract class's (and an abstract sealed
 * arm's) backing wrapper, which overrides every abstract member with a call-through (ADR-064);
 * an inner class of a generic owner, flattened onto the owner's holder with the parameters it
 * captures, and a type nested in a generic owner's holder (ADR-196); and the nested-type
 * collision check, which compares rendered C# names (ADR-179).
 *
 * Oreo's teapot has a strainer and a lid; Mylo stamps whatever he is handed.
 */
class Tier1MemberGenericNestingTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private fun Tier1Result.csharpLines(fragment: String): List<String> =
    generatedCSharp.lines().map(String::trim).filter { line -> line.contains(fragment) }

  private val backed: String = """
    package tier1.genericnesting

    interface Pet { val name: String }
    class Cat(override val name: String) : Pet

    abstract class Groomer {
      abstract fun <T> groom(item: T): T
      abstract fun <T : Pet> pamper(pet: T): T
      abstract fun <T> spare(item: T?): T?
      fun <T> plain(item: T): T = item
    }

    class Brush : Groomer() {
      override fun <G> groom(item: G): G = item
      override fun <T : Pet> pamper(pet: T): T = pet
      override fun <T> spare(item: T?): T? = item
    }

    fun makeGroomer(): Groomer = Brush()

    sealed class Chore {
      abstract class Walk : Chore() {
        abstract fun <T> carry(item: T): T
        abstract fun <T : Pet> lead(pet: T): T
      }

      class Fetch : Chore() {
        fun <T> pick(item: T): T = item
      }
    }

    class Stroll : Chore.Walk() {
      override fun <U> carry(item: U): U = item
      override fun <T : Pet> lead(pet: T): T = pet
    }
  """.trimIndent()

  /**
   * The backing wrapper overrides the abstract generic members by the override rules: no `where`
   * (CS0460), `where T : default` for a `T?` (CS0115), and a call-through body (CS0534 without it).
   */
  @Test
  fun `a backing wrapper overrides an abstract generic member`() {
    val result: Tier1Result = Tier1Harness.run(backed, processorOptions = options)
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")

    val pet = "global::TestLibrary.Genericnesting.IPet"
    listOf(
      "public abstract T Groom<T>(T item);",
      "public abstract T Pamper<T>(T pet) where T : $pet;",
      "public abstract T? Spare<T>(T? item);",
      "public abstract T Carry<T>(T item);",
      "public abstract T Lead<T>(T pet) where T : $pet;",
    ).forEach { signature ->
      assertTrue(
        result.csharpLines(signature).isNotEmpty(),
        "expected `$signature`; got: ${result.csharpLines("<T>")}",
      )
    }
    // The wrapper (one per abstract owner) and the concrete subclass each override.
    listOf(
      "public override T Groom<T>(T item)" to 1,
      "public override G Groom<G>(G item)" to 1,
      "public override T Pamper<T>(T pet)" to 2,
      "public override T? Spare<T>(T? item) where T : default" to 2,
      "public override T Carry<T>(T item)" to 1,
      "public override U Carry<U>(U item)" to 1,
      "public override T Lead<T>(T pet)" to 2,
    ).forEach { (signature, count) ->
      assertEquals(
        count,
        result.csharpLines(signature).count { line -> line == signature },
        "expected `$signature` $count time(s); got: ${result.csharpLines("override")}",
      )
    }
    assertFalse(
      result.csharpLines("override").any { line -> line.contains(" : $pet") },
      "an override must not restate a constraint (CS0460); got: ${result.csharpLines("override")}",
    )
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("tier1.genericnesting") },
      "expected every member to bind; got: ${result.kspWarnings}",
    )

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Genericnesting;

      public static class Consumer
      {
          public static string Use(Cat cat, Stroll stroll)
          {
              using Groomer groomer = Fixture.MakeGroomer();
              Chore.Walk walk = stroll;
              return groomer.Groom("g") + groomer.Pamper(cat).Name + groomer.Spare<string>(null)
                  + groomer.Plain(1) + walk.Carry(2) + walk.Lead(cat).Name;
          }
      }
      """.trimIndent(),
    )
  }

  private val nested: String = """
    package tier1.genericnesting

    class Teapot<T>(val item: T) {
      inner class Strainer(val holes: Int) {
        fun <U> stamp(seed: U): U = seed
        fun <V> pair(first: T, second: V): T = first
        // Kotlin allows a method `T` to shadow the captured one; C# would not (CS0693).
        fun <T> clash(seed: T): T = seed
      }

      inner class Infuser<U>(val herb: U) {
        fun <V> blend(seed: V): U = herb
      }

      class Seal<S>(val mark: S) {
        fun <U> stamp(seed: U): U = seed
        // A nested (not inner) class captures nothing, so its own `T` shadows no owner.
        fun <T> lift(seed: T): T = seed
      }

      class Lid(val size: Int) {
        fun <T> lift(seed: T): T = seed
      }
    }
  """.trimIndent()

  @Test
  fun `a generic method binds on an inner class and a nested type of a generic owner`() {
    val result: Tier1Result = Tier1Harness.run(nested, processorOptions = options)
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")

    listOf(
      "public U Stamp<U>(U seed)",
      "public T Pair<V>(T first, V second)",
      "public U Blend<V>(V seed)",
      "public T Lift<T>(T seed)",
    ).forEach { signature ->
      assertTrue(
        result.csharpLines(signature).isNotEmpty(),
        "expected `$signature`; got: ${result.csharpLines("public")}",
      )
    }
    val clash: List<String> = result.kspWarnings.filter { it.contains("Strainer.clash") }
    assertEquals(1, clash.size, "expected the shadowing member named once; got: $clash")
    assertContains(clash.single(), "(here, its type parameter `T` shadows its owner's)")
    assertTrue(
      result.kspWarnings.none { warning ->
        warning.contains("genericnesting") && !warning.contains("Strainer.clash")
      },
      "expected every other member to bind; got: ${result.kspWarnings}",
    )

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Genericnesting;

      public static class Consumer
      {
          public static string Use(Teapot<string> teapot)
          {
              using var strainer = new Teapot.Strainer<string>(teapot, 2);
              using var infuser = new Teapot.Infuser<string, int>(teapot, 3);
              using var seal = new Teapot.Seal<long>(9L);
              using var lid = new Teapot.Lid(1);
              return strainer.Stamp(1) + strainer.Pair("a", true) + infuser.Blend("x")
                  + seal.Stamp(2.5) + seal.Lift("s") + lid.Lift('c');
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * ADR-133 surface 6 with ADR-179's rendered names: a generic method renders the same member
   * name as a non-generic one, so one named like a nested type collides the same way.
   */
  @Test
  fun `a generic method named like a nested type is the nested-type collision`() {
    val generic: Tier1Result = Tier1Harness.run(
      """
      package tier1.genericnesting

      class Shelf {
        class Stamp(val size: Int)
        fun <T> stamp(item: T): T = item
      }
      """.trimIndent(),
      processorOptions = options,
    )
    val plain: Tier1Result = Tier1Harness.run(
      """
      package tier1.genericnesting

      class Shelf {
        class Stamp(val size: Int)
        fun stamp(item: Int): Int = item
      }
      """.trimIndent(),
      processorOptions = options,
    )
    fun Tier1Result.collision(): List<String> =
      (kspErrors + kspWarnings).filter { message -> message.contains("Stamp") }
    assertTrue(plain.collision().isNotEmpty(), "control: ${plain.kspErrors} ${plain.kspWarnings}")
    assertEquals(
      plain.collision().map { message -> message.substringBefore(":") },
      generic.collision().map { message -> message.substringBefore(":") },
      "expected the generic member to collide exactly as the plain one does",
    )
  }
}

package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A type parameter with several upper bounds (`where T : Comparable<T>, T : Pet`). No single
 * Kotlin type names the intersection, so the generated Kotlin can no longer spell the owner by
 * its first bound (`Kennel<kotlin.Comparable<Any?>>` is not within `Pet`, and a decoded
 * `Comparable<Any?>` is not a `Pet`). Instead a `T` value is read as `Any` and cast, checked, to
 * each bound in turn, which Kotlin types as the intersection; a receiver that takes such a
 * value is typed by a local generic function carrying the owner's own bounds, the inferred `T`
 * being that intersection; a receiver that takes none is star-projected; and a constructor lets
 * inference pick the argument (`Kennel<_>`), or `Nothing` when no parameter mentions it.
 *
 * The C# half was already right (each exportable bound in the `where` clause, a builtin one
 * dropped by ADR-015's 2026-10-03 amendment); these cells pin it unchanged.
 */
class Tier1MultiBoundGenericTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private fun assertCompiles(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  @Test
  fun `two wrapper bounds compile on the class and the function route`() {
    val result = Tier1Harness.run(
      """
      package tier1.multibound

      interface Pet { val name: String }
      interface Trainable { fun train(): Int }

      class Arena<T>(val value: T) where T : Pet, T : Trainable {
        val label: String get() = value.name
        fun swap(next: T): T = next
        fun keep(next: T?): T? = next
      }

      class Shelf<T>() where T : Pet, T : Trainable {
        private val items: MutableList<T> = mutableListOf()
        fun put(item: T) { items.add(item) }
        fun last(): T = items.last()
      }

      data class Tag<T>(val value: T, val note: String) where T : Pet, T : Trainable

      fun <T> drill(value: T): T where T : Pet, T : Trainable = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertCompiles(result)
    // `copy` defaults every parameter, so its witness is the ADR-164 local an unset one leaves
    // null.
    assertContains(
      result.generated,
      "nugetTypedOwner(handle.asStableRef<Any>().get(), default_value_)" +
          ".copy(value = default_value_!!)",
    )
    assertContains(
      result.generatedCSharp,
      "public class Arena<T> : IDisposable, INugetHandle where T : " +
          "global::TestLibrary.Multibound.IPet, global::TestLibrary.Multibound.ITrainable",
    )
    assertContains(
      result.generatedCSharp,
      "public static T Drill<T>(T value) where T : " +
          "global::TestLibrary.Multibound.IPet, global::TestLibrary.Multibound.ITrainable",
    )
    // A read takes no `T`, so the receiver is star-projected.
    assertContains(result.generated, "handle.asStableRef<tier1.multibound.Arena<*>>().get().value")
    // A `T` is read as `Any` and cast, checked, to every bound; the smart casts type it as the
    // intersection.
    assertContains(
      result.generated,
      "next.asStableRef<Any>().get().let { bounded -> bounded as tier1.multibound.Pet; " +
          "bounded as tier1.multibound.Trainable; bounded }",
    )
    assertContains(
      result.generated,
      "where T : tier1.multibound.Pet, T : tier1.multibound.Trainable",
    )
    assertContains(result.generated, "tier1.multibound.Arena<_>(")
    // No constructor parameter mentions `T`, so nothing can infer it; `Nothing` is within every
    // bound and the constructor creates no `T` value.
    assertContains(result.generated, "tier1.multibound.Shelf<Nothing>()")
    assertContains(
      result.generated,
      "tier1.multibound.drill(value.asStableRef<Any>().get().let { bounded -> " +
          "bounded as tier1.multibound.Pet; bounded as tier1.multibound.Trainable; bounded })",
    )
  }

  @Test
  fun `a Comparable bound beside a wrapper bound compiles on both routes`() {
    val result = Tier1Harness.run(
      """
      package tier1.multibound

      interface Pet { val name: String }

      class Kennel<T>(val value: T) where T : Comparable<T>, T : Pet {
        fun swap(next: T): T = next
        fun outranks(other: T): Boolean = value > other
      }

      fun <T> pick(value: T): T where T : Comparable<T>, T : Pet = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertCompiles(result)
    assertContains(
      result.generatedCSharp,
      "public class Kennel<T> : IDisposable, INugetHandle " +
          "where T : global::TestLibrary.Multibound.IPet",
    )
    assertContains(
      result.generatedCSharp,
      "public static T Pick<T>(T value) where T : global::TestLibrary.Multibound.IPet",
    )
    assertContains(
      result.generated,
      "next.asStableRef<Any>().get().let { bounded -> bounded as kotlin.Comparable<Any?>; " +
          "bounded as tier1.multibound.Pet; bounded }",
    )
    assertContains(result.generated, "where T : kotlin.Comparable<T>, T : tier1.multibound.Pet")
    assertTrue(
      result.compileWarnings.none { it.contains("nchecked cast") },
      "${result.compileWarnings}",
    )
  }

  /**
   * The bound names `T` itself; a contravariant one has an erased spelling within it. The C# half
   * spells a generic interface bound with its arguments, single-bound or not; it used to drop them
   * (`where T : IRival` for the declared `IRival<in T>`, CS0305).
   */
  @Test
  fun `a recursive bound beside a wrapper bound compiles on both routes`() {
    val result = Tier1Harness.run(
      """
      package tier1.multibound

      interface Pet { val name: String }
      interface Rival<in T> { fun beats(other: T): Boolean }

      class Ladder<T>(val value: T) where T : Rival<T>, T : Pet {
        fun climb(next: T): T = if (next.beats(value)) next else value
      }

      class Duel<T : Rival<T>>(val value: T)

      fun <T> champion(value: T): T where T : Rival<T>, T : Pet = value

      fun <T : Rival<T>> referee(value: T): T = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertCompiles(result)
    assertContains(result.generatedCSharp, "public interface IRival<in T> : IDisposable")
    assertContains(
      result.generatedCSharp,
      "public class Ladder<T> : IDisposable, INugetHandle where T : " +
          "global::TestLibrary.Multibound.IRival<T>, global::TestLibrary.Multibound.IPet",
    )
    assertContains(
      result.generatedCSharp,
      "public class Duel<T> : IDisposable, INugetHandle where T : " +
          "global::TestLibrary.Multibound.IRival<T>",
    )
    assertContains(
      result.generatedCSharp,
      "public static T Champion<T>(T value) where T : " +
          "global::TestLibrary.Multibound.IRival<T>, global::TestLibrary.Multibound.IPet",
    )
    assertContains(
      result.generatedCSharp,
      "public static T Referee<T>(T value) where T : global::TestLibrary.Multibound.IRival<T>",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Multibound;

      public static class Probe
      {
          public static T Pick<T>(T value) where T : IRival<T>, IPet => Fixture.Champion(value);
      }
      """.trimIndent(),
    )
    assertContains(
      result.generated,
      "where T : tier1.multibound.Rival<T>, T : tier1.multibound.Pet",
    )
    assertContains(
      result.generated,
      "value.asStableRef<Any>().get().let { bounded -> bounded as tier1.multibound.Rival<Any?>; " +
          "bounded as tier1.multibound.Pet; bounded }",
    )
  }

  @Test
  fun `nullable bounds keep null on both routes`() {
    val result = Tier1Harness.run(
      """
      package tier1.multibound

      interface Pet { val name: String }
      interface Trainable { fun train(): Int }

      class Crate<T>(val value: T) where T : Pet?, T : Trainable? {
        fun swap(next: T): T = next
      }

      fun <T> carry(value: T): T where T : Pet?, T : Trainable? = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertCompiles(result)
    assertContains(
      result.generatedCSharp,
      "public class Crate<T> : IDisposable, INugetHandle where T : " +
          "global::TestLibrary.Multibound.IPet?, global::TestLibrary.Multibound.ITrainable?",
    )
    assertContains(
      result.generatedCSharp,
      "public static T Carry<T>(T value) where T : " +
          "global::TestLibrary.Multibound.IPet?, global::TestLibrary.Multibound.ITrainable?",
    )
    assertContains(
      result.generated,
      "next?.asStableRef<Any>()?.get().let { bounded -> bounded as tier1.multibound.Pet?; " +
          "bounded as tier1.multibound.Trainable?; bounded }",
    )
    assertContains(
      result.generated,
      "where T : tier1.multibound.Pet?, T : tier1.multibound.Trainable?",
    )
  }

  /**
   * Only the multi-bound parameter loses its erased spelling: a member that takes the other one
   * keeps a plain star-projected receiver, and one that takes the multi-bound one types only it.
   */
  @Test
  fun `a second type parameter keeps its own erased spelling`() {
    val result = Tier1Harness.run(
      """
      package tier1.multibound

      interface Pet { val name: String }
      interface Trainable { fun train(): Int }

      class Duo<A, B>(val first: A, val second: B) where A : Pet, A : Trainable {
        fun takeFirst(next: A): A = next
        fun takeSecond(next: B): B = next
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertCompiles(result)
    assertContains(result.generated, "handle.asStableRef<tier1.multibound.Duo<*, Any?>>()")
    assertContains(result.generated, "tier1.multibound.Duo<_, Any?>(")
    assertContains(result.generated, "): tier1.multibound.Duo<A, Any?> where A :")
  }

  /**
   * ADR-198: an invariant recursive bound (`T : Node<T>`) has no erased spelling within it,
   * multi-bound or not: `Node<Any?>` is not a `Node<Node<Any?>>`. It is the `T : Enum<T>` case
   * (Tier1EnumSelfBoundTest), and rides the same trampoline: each export that takes a `T`
   * re-declares the bounds on a local generic function called at `Nothing`.
   */
  @Test
  fun `an invariant recursive bound compiles through the trampoline on both routes`() {
    val result = Tier1Harness.run(
      """
      package tier1.multibound

      interface Pet { val name: String }
      interface Node<T> { fun link(other: T): T }

      class Chain<T>(val value: T) where T : Node<T>, T : Pet {
        val label: String get() = value.name
        fun attach(other: T): T = value.link(other)
      }

      class Link<T : Node<T>>(val value: T) {
        fun attach(other: T): T = value.link(other)
      }

      fun <T> head(value: T): T where T : Node<T>, T : Pet = value

      class Bead(override val name: String) : Node<Bead>, Pet {
        override fun link(other: Bead): Bead = Bead(name + "-" + other.name)
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertCompiles(result)
    assertTrue(
      result.compileWarnings.none { it.contains("nchecked cast") },
      "${result.compileWarnings}",
    )
    assertContains(result.generated, "where T : tier1.multibound.Node<T>, T : tier1.multibound.Pet")
    assertContains(result.generated, "where T : tier1.multibound.Node<T> {")
    assertContains(
      result.generated,
      "other.asStableRef<Any>().get().let { bounded -> bounded as tier1.multibound.Node<*>; " +
          "bounded as tier1.multibound.Pet; bounded as T }",
    )
    assertContains(
      result.generated,
      "(handle.asStableRef<Any>().get() as tier1.multibound.Chain<T>)",
    )
    assertContains(result.generated, "handle.asStableRef<tier1.multibound.Chain<*>>().get().label")
    assertContains(
      result.generatedCSharp,
      "public class Chain<T> : IDisposable, INugetHandle where T : " +
          "global::TestLibrary.Multibound.INode<T>, global::TestLibrary.Multibound.IPet",
    )
    assertContains(
      result.generatedCSharp,
      "public class Link<T> : IDisposable, INugetHandle where T : " +
          "global::TestLibrary.Multibound.INode<T>",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Multibound;

      public static class Probe
      {
          public static T Head<T>(T value) where T : INode<T>, IPet => Fixture.Head(value);

          public static string Run()
          {
              using var oreo = new Bead("Oreo");
              using var chain = new Chain<Bead>(oreo);
              using Bead linked = chain.Attach(new Bead("Mylo"));
              return linked.Name + chain.Label + Fixture.Head(oreo).Name;
          }
      }
      """.trimIndent(),
    )
  }
}

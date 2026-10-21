package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-197: a member function's own type parameter (`fun <T> echo(item: T): T`) binds on the
 * ADR-062 callable plan, on the ADR-147 boxed-handle wire a class's `T` already crosses on, for an
 * ordinary class, an object, a companion, a sealed base and a sealed arm. The C# member is a
 * generic method (`public T Echo<T>(T item)`) and the Kotlin call names its type arguments, so a
 * `T` that only the return mentions still resolves.
 *
 * Overrides follow the two rules the C# compiler imposes: an `override` restates no `where` clause
 * (CS0460), and one with a `T?` position says `where T : default` (CS0115 without it).
 *
 * Every shape the route does not carry keeps a named skip: a `T` nested in `kotlin.Array`, a
 * shadowed owner `T`, a `reified` one, a multi-bound `T` no parameter mentions, and the
 * interface, extension, enum and value-class owners.
 *
 * Rex the cat keeps whatever he is handed; Oreo knows more tricks than Mylo.
 */
class Tier1MemberGenericMethodTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val bound: String = """
    package tier1.membergeneric

    interface Pet { val name: String }
    interface Trainable { val tricks: Int }

    class Cat(override val name: String, override val tricks: Int) : Pet, Trainable

    class Depot {
      fun <T> echo(item: T): T = item
      fun <T : Pet> adopt(pet: T): T = pet
      fun <T> make(): T? = null
      fun <T : Any> keep(item: T): T = item
      fun <T, U> pair(first: T, second: U): U = second
      fun <T> rank(item: T): T where T : Pet, T : Trainable = item
      fun <T> count(item: T?): Int = if (item == null) 0 else 1
      fun <T> wrap(item: T): Result<T> = Result.success(item)
      fun <Hold> hold(item: Hold): Hold = item

      companion object {
        fun <T> twice(item: T): T = item
      }
    }

    object DepotRegistry {
      fun <T> echo(item: T): T = item
    }

    class Box<T>(val item: T) {
      fun <U> map(seed: U): U = seed
      fun <U> swap(seed: U, other: T): T = other
      fun <U> peek(seed: U): Result<U> = Result.success(seed)
      fun unwrap(): Result<T> = Result.success(item)
    }

    open class Shelter {
      open fun <T> echo(item: T): T = item
      open fun <T> maybe(item: T?): T? = item
      open fun <T : Pet> adopt(pet: T): T = pet
      open fun <T> relay(item: T): T = item
      open fun <T : Pet> comb(pet: T?): T? = pet
    }

    class Annex : Shelter() {
      override fun <T> echo(item: T): T = item
      override fun <T> maybe(item: T?): T? = item
      override fun <T : Pet> adopt(pet: T): T = pet
      // ADR-197: an override may rename the base's type parameter; C# allows that too.
      override fun <R> relay(item: R): R = item
      override fun <P : Pet> comb(pet: P?): P? = pet
    }

    abstract class Groomer {
      abstract fun <T> groom(item: T): T
      abstract fun <T> spare(item: T?): T?
    }

    class Brush : Groomer() {
      override fun <G> groom(item: G): G = item
      override fun <T> spare(item: T?): T? = item
    }

    sealed class Errand {
      abstract fun <T> carry(item: T): T
      open fun <T : Any> tag(item: T): T = item
      fun <T> hold(item: T): T = item
      open fun <T> toss(item: T?): T? = item

      class Fetch(val item: String) : Errand() {
        fun <T> pick(x: T): T = x
        override fun <T> carry(item: T): T = item
        override fun <K : Any> tag(item: K): K = item
        override fun <V> toss(item: V?): V? = item
      }

      data object Rest : Errand() {
        override fun <R> carry(item: R): R = item
      }
    }
  """.trimIndent()

  private fun runBound(): Tier1Result = Tier1Harness.run(bound, processorOptions = options)

  private fun assertKotlinCompiles(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  private fun Tier1Result.csharpLines(fragment: String): List<String> =
    generatedCSharp.lines().map(String::trim)
      .filter { line -> line.startsWith("public") && line.contains(fragment) }

  @Test
  fun `a member type parameter binds on every admitted owner`() {
    val result: Tier1Result = runBound()
    assertKotlinCompiles(result)

    val pet = "global::TestLibrary.Membergeneric.IPet"
    val trainable = "global::TestLibrary.Membergeneric.ITrainable"
    listOf(
      "public T Echo<T>(T item)",
      "public T Adopt<T>(T pet) where T : $pet",
      "public T? Make<T>()",
      "public T Keep<T>(T item) where T : notnull",
      "public U Pair<T, U>(T first, U second)",
      "public T Rank<T>(T item) where T : $pet, $trainable",
      "public int Count<T>(T? item)",
      // A type parameter spelled like its member gives way, as a class's does (CS0694).
      "public THold Hold<THold>(THold item)",
      "public static T Twice<T>(T item)",
      "public static T Echo<T>(T item)",
      "public U Map<U>(U seed)",
      "public T Swap<U>(U seed, T other)",
      "public T Pick<T>(T x)",
      "public T Hold<T>(T item)",
    ).forEach { signature ->
      assertTrue(
        result.generatedCSharp.contains(signature),
        "expected `$signature`; got: ${result.csharpLines("<")}",
      )
    }
    val warnings: List<String> =
      result.kspWarnings.filter { warning -> warning.contains("tier1.membergeneric") }
    assertTrue(warnings.isEmpty(), "expected every member to bind; got: $warnings")
  }

  /** The Kotlin call names its type arguments, so a return-only `T` resolves. */
  @Test
  fun `the Kotlin call spells each type argument`() {
    val result: Tier1Result = runBound()
    assertKotlinCompiles(result)

    listOf(
      ".echo<Any?>(",
      ".make<Any?>()",
      ".adopt<tier1.membergeneric.Pet>(",
      ".keep<Any>(",
      ".pair<Any?, Any?>(",
      // No single type spells a multi-bound `T`; inference reads it off the smart-cast argument.
      ".rank<_>(",
      "tier1.membergeneric.DepotRegistry.echo<Any?>(",
      ".map<Any?>(",
    ).forEach { call ->
      assertContains(result.generated, call)
    }
  }

  @Test
  fun `an override restates no constraint and a nullable T says default`() {
    val result: Tier1Result = runBound()

    val pet = "global::TestLibrary.Membergeneric.IPet"
    listOf(
      "public virtual T Echo<T>(T item)",
      "public virtual T? Maybe<T>(T? item)",
      "public virtual T Adopt<T>(T pet) where T : $pet",
      "public override T Echo<T>(T item)",
      "public override T? Maybe<T>(T? item) where T : default",
      "public override T Adopt<T>(T pet)",
      "public abstract T Groom<T>(T item);",
      "public abstract T? Spare<T>(T? item);",
      "public override G Groom<G>(G item)",
      "public override T? Spare<T>(T? item) where T : default",
      "public virtual T Carry<T>(T item)",
      "public override T Carry<T>(T item)",
      "public override R Carry<R>(R item)",
      "public virtual T Tag<T>(T item) where T : notnull",
      "public override K Tag<K>(K item)",
      // ADR-197: a renamed override matches its base by position, and keeps its own names.
      "public virtual T Relay<T>(T item)",
      "public override R Relay<R>(R item)",
      "public virtual T? Comb<T>(T? pet) where T : $pet",
      "public override P? Comb<P>(P? pet) where P : default",
      "public virtual T? Toss<T>(T? item)",
      "public override V? Toss<V>(V? item) where V : default",
    ).forEach { signature ->
      assertTrue(
        result.csharpLines(signature).any { line -> line.startsWith(signature) },
        "expected `$signature`; got: ${result.csharpLines("<T>")}",
      )
    }
    assertFalse(
      result.csharpLines("override").any { line -> line.contains(" : $pet") },
      "an override must not restate the base's constraint (CS0460); got: " +
          "${result.csharpLines("override")}",
    )
  }

  /**
   * ADR-015 amendment, on the member route: every bounded `T` read is the checked cast the class
   * and function routes use, on every owner, so a `T` outside a bound C# cannot see (a dropped
   * builtin `Number`) fails at the read rather than inside the body.
   */
  @Test
  fun `every bounded member type parameter is read through a checked cast`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.membergeneric

      interface Pet { val name: String }
      interface Trainable { val tricks: Int }

      class Depot {
        fun <T : Pet> adopt(pet: T): T = pet
        fun <T : Pet> spare(pet: T?): T? = pet
        fun <T> rank(item: T): Int where T : Pet, T : Trainable = item.tricks
        fun <T> rankMaybe(item: T?): Int where T : Pet, T : Trainable = item?.tricks ?: 0
        fun <T : Number> portion(amount: T): Int = amount.toInt()

        companion object {
          fun <T : Number> double(amount: T): Double = amount.toDouble() * 2
        }
      }

      object DepotRegistry {
        fun <T : Number> weigh(amount: T): Double = amount.toDouble()
      }

      class Box<T>(val item: T) {
        fun <U : Pet> map(pet: U): String = pet.name
      }

      sealed class Errand {
        open fun <T : Number> count(amount: T): Int = amount.toInt()

        class Fetch(val item: String) : Errand() {
          fun <T : Comparable<T>> best(first: T, second: T): T = maxOf(first, second)
        }
      }
      """.trimIndent(),
      processorOptions = options,
    )
    assertKotlinCompiles(result)

    val pet = "tier1.membergeneric.Pet"
    val trainable = "tier1.membergeneric.Trainable"
    listOf(
      // A single bound, and its nullable form.
      ".adopt<$pet>((pet.asStableRef<Any>().get() as $pet))",
      ".spare<$pet>((pet?.asStableRef<Any>()?.get() as $pet?))",
      // Several bounds: each cast checked, the first one first.
      ".rank<_>(item.asStableRef<Any>().get().let { bounded -> bounded as $pet; " +
          "bounded as $trainable; bounded })",
      ".rankMaybe<_>(item?.asStableRef<Any>()?.get().let { bounded -> bounded as $pet?; " +
          "bounded as $trainable?; bounded })",
      // A dropped builtin bound, on each owner.
      ".portion<kotlin.Number>((amount.asStableRef<Any>().get() as kotlin.Number))",
      ".double<kotlin.Number>((amount.asStableRef<Any>().get() as kotlin.Number))",
      "DepotRegistry.weigh<kotlin.Number>((amount.asStableRef<Any>().get() as kotlin.Number))",
      ".map<$pet>((pet.asStableRef<Any>().get() as $pet))",
      ".count<kotlin.Number>((amount.asStableRef<Any>().get() as kotlin.Number))",
      ".best<kotlin.Comparable<Any?>>((first.asStableRef<Any>().get() as " +
          "kotlin.Comparable<Any?>), (second.asStableRef<Any>().get() as kotlin.Comparable<Any?>))",
    ).forEach { read -> assertContains(result.generated, read) }
    // ADR-217: `Pet` is now reachable as a bound, so its own interface exports read
    // `asStableRef<Pet>()`; only the member calls are checked here.
    assertFalse(
      result.generated.lines().any { line ->
        val callsMember: Boolean = listOf(".adopt<", ".spare<", ".map<").any { call -> call in line }
        callsMember && line.contains("asStableRef<$pet>()")
      },
      "a bounded read must not be the unchecked generic `asStableRef<Bound>()`",
    )
  }

  /** The real compiler, with a consumer that calls each owner with builtin and wrapper `T`s. */
  @Test
  fun `the generated C# compiles against a consumer`() {
    val result: Tier1Result = runBound()

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using TestLibrary.Membergeneric;

      public static class Consumer
      {
          public static string Use(
              Depot depot, Cat cat, Annex annex, Errand.Fetch fetch, Box<string> box, Brush brush)
          {
              Groomer groomer = brush;
              string groomed = groomer.Groom("g");
              int? spared = groomer.Spare<int?>(null);
              int n = depot.Echo(4);
              string s = depot.Echo("x");
              Cat same = depot.Adopt(cat);
              string? none = depot.Make<string>();
              Cat kept = depot.Keep(cat);
              string paired = depot.Pair(1, "two");
              Cat ranked = depot.Rank(cat);
              int counted = depot.Count<string>(null);
              int wrapped = depot.Wrap(5);
              int kept3 = depot.Hold(3);
              bool tried = depot.TryWrap("w", out string? tryWrapped, out Exception? failure);
              bool peeked = box.TryPeek(2, out int peek, out Exception? peekFailure);
              bool unwrapped =
                  box.TryUnwrap(out string? unwrappedItem, out Exception? unwrapFailure);
              int twice = Depot.Twice(2);
              long registered = DepotRegistry.Echo(7L);
              double mapped = box.Map(1.5);
              string swapped = box.Swap(true, "other");
              Shelter shelter = annex;
              Cat adopted = shelter.Adopt(cat);
              string? maybe = shelter.Maybe<string>(null);
              int picked = fetch.Pick(7);
              Errand errand = fetch;
              int carried = errand.Carry(3);
              string tagged = errand.Tag("t");
              bool held = errand.Hold(true);
              string relayed = shelter.Relay("r");
              Cat? combed = shelter.Comb<Cat>(null);
              int? tossed = errand.Toss<int?>(null);
              string? tossedName = errand.Toss<string>("t");
              return n + s + same.Name + none + kept.Name + paired + ranked.Name + counted + wrapped
                  + twice + registered + mapped + swapped + adopted.Name + maybe + picked + carried
                  + tagged + held + groomed + spared + tried + tryWrapped + failure?.Message + kept3
                  + peeked + peek + peekFailure?.Message + unwrapped + unwrappedItem
                  + unwrapFailure?.Message + relayed + combed?.Name + tossed + tossedName;
          }
      }
      """.trimIndent(),
      // ADR-217: an interface reachable only as a `T` bound now gets its C# bridge, so a
      // C# implementation crosses `Wrap<T>`; the bridge uses function pointers.
      allowUnsafe = true,
    )
  }

  private val declined: String = """
    package tier1.membergeneric

    interface Pet { val name: String }
    interface Trainable { val tricks: Int }

    enum class Mood { CALM, LOUD }

    class Depot {
      fun ok(): Int = 1
      fun <T> lambda(item: T, block: suspend (Int) -> Unit): T = item
      inline fun <reified T> kind(item: Any?): Boolean = item is T
      fun <T : Enum<T>> first(item: T): T = item
      fun <T> conjure(): T? where T : Pet, T : Trainable = null
      fun <T, U : T> narrow(item: U): T = item
      fun <T> rank(crate: Crate<T>): T where T : Pet, T : Trainable = crate.item
      fun <T> sort(values: Array<T>): Int = values.size
    }

    class Crate<T>(val item: T) {
      fun <T> shadow(item: T): T = item
    }

    interface Kennel {
      fun <T> structural(item: T): T = item
    }

    class Run : Kennel

    fun makeKennel(): Kennel = Run()

    enum class Weather {
      SUN;
      fun <T> echo(item: T): T = item
    }

    @JvmInline
    value class Tag(val label: String) {
      fun <T> echo(item: T): T = item
    }

    fun <T> Depot.extended(item: T): T = item
  """.trimIndent()

  private fun runDeclined(): Tier1Result = Tier1Harness.run(declined, processorOptions = options)

  @Test
  fun `a shape the route does not carry is named once and absent from both halves`() {
    val result: Tier1Result = runDeclined()
    assertKotlinCompiles(result)

    // Each warning names the shape rule the member broke; the hint stays the general remedy.
    mapOf(
      "tier1.membergeneric.Depot.kind" to "(here, its type parameter `T` is reified)",
      "tier1.membergeneric.Depot.conjure" to
          "(here, its multi-bound type parameter `T` is mentioned by no parameter)",
      "tier1.membergeneric.Depot.narrow" to
          "(here, its type parameter `U` is bounded by another type parameter)",
      // ADR-216: `kotlin.Array` is no exported generic class, so it keeps the GENERIC text.
      "tier1.membergeneric.Depot.sort" to "(here, its type parameter is nested in `Array<T>`)",
      "tier1.membergeneric.Crate.shadow" to "(here, its type parameter `T` shadows its owner's)",
      "tier1.membergeneric.Kennel.structural" to "(here, it is declared on an interface)",
      "tier1.membergeneric.Weather.echo" to "(here, it is declared on an enum class)",
      "tier1.membergeneric.Tag.echo" to "(here, it is declared on a value class)",
      "tier1.membergeneric.extended" to "(here, it is an extension function)",
    ).forEach { (member, refusal) ->
      val named: List<String> = result.kspWarnings.filter { warning ->
        Regex("\\b${Regex.escape(member)}\\b").containsMatchIn(warning)
      }
      assertEquals(1, named.size, "expected exactly one warning naming $member; got: $named")
      assertContains(named.single(), ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name)
      // The hint names a shape that binds, and no longer claims a member never does.
      assertContains(named.single(), "a class, object, companion or sealed-class member")
      assertContains(named.single(), refusal)
    }

    val declarations: List<String> = result.generatedCSharp.lines()
      .map(String::trim)
      .filterNot { line -> line.startsWith("//") }
    // ADR-217: an invariant `Crate<Pet>` cannot infer `T : Pet, T : Trainable`, so `rank` runs
    // under the trampoline instead of the refusal ADR-216 gave it.
    assertTrue(
      declarations.any { line -> line.startsWith("public T Rank<T>(") },
      "expected the ADR-217 Rank binding",
    )
    assertContains(result.generated, ".rank<T>(")
    listOf(
      "Kind", "Conjure", "Narrow", "Sort", "Shadow", "Structural",
      "Extended",
    )
      .forEach { member ->
        assertFalse(
          declarations.any { line -> Regex("\\b$member\\s*[<(]").containsMatchIn(line) },
          "expected no C# member $member; got: " +
              "${declarations.filter { line -> line.contains(member) }}",
        )
      }
    assertTrue(
      declarations.any { line -> line.contains("public int Ok()") },
      "expected the control member to bind",
    )
    // ADR-198: an `Enum<T>` bound is no longer a refusal; it binds through the trampoline
    // (Tier1EnumSelfBoundInteractionTest covers it on a class, an object and a sealed arm).
    assertTrue(
      declarations.any { line ->
        line.contains("public T First<T>(T item) where T : struct, global::System.Enum")
      },
      "expected Depot.First to bind; got ${declarations.filter { it.contains("First") }}",
    )
    // ADR-215 lane C: a suspend lambda no longer refuses a generic member; this one binds on the
    // plan route, its `Func<int, CancellationToken, Task>` registered like any ADR-210 parameter.
    assertTrue(
      declarations.any { line -> Regex("\\bT Lambda<T>\\(T item, ").containsMatchIn(line) },
      "expected Depot.Lambda to bind; got ${declarations.filter { it.contains("Lambda") }}",
    )
  }

  private val collections: String = """
    package tier1.membergeneric

    interface Furry { val name: String }
    interface Trainable { val tricks: Int }

    class Deck {
      fun <T> shuffle(list: List<T>): List<T> = list.reversed()
      fun <T> pad(list: List<T?>): List<T?> = list + null
      fun <T : Furry> groom(pets: List<T>): List<T> = pets
      fun <T> drill(pets: List<T>): Int where T : Furry, T : Trainable = pets.size
      fun <T> dedupe(items: Set<T>): Set<T> = items
      fun <K : Any, V> tally(map: Map<K, V>): Int = map.size
      fun <K : Any, V> keep(map: Map<K, V>): Map<K, V> = map
      fun <K, V> loose(map: Map<K, V>): Int = map.size
      fun <T> stack(lists: List<List<T>>): List<List<T>> = lists
      fun <T : Comparable<T>> best(items: List<T>): T = items.max()
      fun <T> nested(values: List<T>): Int = values.size
      fun <T : Enum<T>> moods(items: List<T>): Int = items.size
      fun <T> gather(item: T): Result<List<T>> = Result.success(listOf(item))
      fun relist(list: List<Int>): List<Int> = list
    }

    class Crate<T>(val item: T) {
      val items: List<T> get() = listOf(item)
      fun fill(items: List<T>): List<T> = items
    }
  """.trimIndent()

  private fun runCollections(): Tier1Result =
    Tier1Harness.run(collections, processorOptions = options)

  /** ADR-212: a method's or a class's `T` binds as a collection element, key or value. */
  @Test
  fun `a type parameter binds as a collection element key or value`() {
    val result: Tier1Result = runCollections()
    assertKotlinCompiles(result)

    val furry = "global::TestLibrary.Membergeneric.IFurry"
    listOf(
      "public IReadOnlyList<T> Shuffle<T>(IReadOnlyList<T> list)",
      "public IReadOnlyList<T?> Pad<T>(IReadOnlyList<T?> list)",
      "public IReadOnlyList<T> Groom<T>(IReadOnlyList<T> pets) where T : $furry",
      "public IReadOnlySet<T> Dedupe<T>(IReadOnlySet<T> items)",
      "public int Tally<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull",
      "public IReadOnlyDictionary<K, V> Keep<K, V>(IReadOnlyDictionary<K, V> map) " +
        "where K : notnull",
      "public IReadOnlyList<IReadOnlyList<T>> Stack<T>(IReadOnlyList<IReadOnlyList<T>> lists)",
      "public int Nested<T>(IReadOnlyList<T> values)",
      "public IReadOnlyList<T> Items",
      "public IReadOnlyList<T> Fill(IReadOnlyList<T> items)",
      // ADR-216: a `Result<List<T>>` return, with its ADR-195 `Try` twin.
      "public IReadOnlyList<T> Gather<T>(T item)",
      "public bool TryGather<T>(T item, ",
    ).forEach { signature ->
      assertTrue(
        result.generatedCSharp.contains(signature),
        "expected `$signature`; got: ${result.csharpLines("IReadOnly")}",
      )
    }
    assertContains(result.generatedCSharp, "NugetMarshal.CreateList(list)")
    assertContains(result.generatedCSharp, "NugetMarshal.FromHandle<T>(")
    assertFalse(result.generatedCSharp.contains("(T)null"), "a T element never reads as (T)null")

    val furryKt = "tier1.membergeneric.Furry"
    val trainableKt = "tier1.membergeneric.Trainable"
    listOf(
      "as kotlin.Any?",
      "as $furryKt",
      "bounded as $furryKt; bounded as $trainableKt; bounded",
      "as kotlin.Comparable<Any?>",
      "@Suppress(\"UNCHECKED_CAST\")",
      ".shuffle<Any?>(",
      ".groom<$furryKt>(",
    ).forEach { fragment -> assertContains(result.generated, fragment) }
    assertFalse(result.generated.contains(" as T)"), "an element is never cast to the bare `T`")
  }

  /** An unconstrained key keeps its named refusal (ADR-217 binds the trampolined `moods`). */
  @Test
  fun `a collection of T the wire does not carry is refused by name`() {
    val result: Tier1Result = runCollections()
    assertKotlinCompiles(result)

    mapOf(
      "tier1.membergeneric.Deck.loose" to "key type K",
    ).forEach { (member, refusal) ->
      val named: List<String> = result.kspWarnings.filter { warning ->
        Regex("\\b${Regex.escape(member)}\\b").containsMatchIn(warning)
      }
      assertEquals(1, named.size, "expected exactly one warning naming $member; got: $named")
      assertContains(named.single(), refusal)
    }
    val declarations: List<String> = result.generatedCSharp.lines()
      .map(String::trim)
      .filterNot { line -> line.startsWith("//") }
    assertTrue(
      declarations.any { line -> line.startsWith("public int Moods<T>(IReadOnlyList<T> items)") },
      "expected the ADR-217 Moods binding",
    )
    listOf("Loose").forEach { member ->
      assertFalse(
        declarations.any { line -> Regex("\\b$member\\s*[<(]").containsMatchIn(line) },
        "expected no C# member $member",
      )
    }
  }

  @Test
  fun `the generated collection-of-T C# compiles against a consumer`() {
    val result: Tier1Result = runCollections()

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using TestLibrary.Membergeneric;

      public static class Consumer
      {
          public static int Use(Deck deck, Crate<string> crate)
          {
              IReadOnlyList<int> ints = deck.Shuffle(new[] { 1, 2, 3 });
              IReadOnlyList<string> names = deck.Shuffle(new List<string> { "Oreo", "Mylo" });
              IReadOnlyList<int?> padded = deck.Pad(new int?[] { 1, null });
              IReadOnlySet<long> set = deck.Dedupe<long>(new HashSet<long> { 1L });
              int tallied = deck.Tally(new Dictionary<string, int> { ["a"] = 1 });
              IReadOnlyDictionary<int, string?> kept =
                  deck.Keep(new Dictionary<int, string?> { [1] = null });
              IReadOnlyList<IReadOnlyList<int>> stacked =
                  deck.Stack(new[] { new[] { 1 } });
              IReadOnlyList<string> filled = crate.Fill(new[] { "x" });
              IReadOnlyList<int> relisted = deck.Relist(new[] { 1 });
              return ints.Count + names.Count + padded.Count + set.Count + tallied + kept.Count
                  + stacked.Count + filled.Count + crate.Items.Count + relisted.Count;
          }
      }
      """.trimIndent(),
      // ADR-217: an interface reachable only as a `T` bound now gets its C# bridge, so a
      // C# implementation crosses `Wrap<T>`; the bridge uses function pointers.
      allowUnsafe = true,
    )
  }
}

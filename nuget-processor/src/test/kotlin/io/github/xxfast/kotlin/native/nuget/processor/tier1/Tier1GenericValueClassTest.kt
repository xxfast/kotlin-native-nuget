package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-220: a generic value class renders as a positional `readonly record struct Sticker<T>(T
 * Value)`. A closed instantiation crosses as its substituted underlying (`Sticker<Int>` is a value
 * class over `Int` to every route), a phantom `Decal<T>(val serial: Long)` is generic only in its
 * C# spelling, and a member's own `T` inside `Sticker<T>` rides the bare-`T` wire. Neither struct
 * gets a `CreateChecked` or a `_create` export: Kotlin cannot infer a phantom `T` there, which used
 * to break the consumer's whole Kotlin compile.
 *
 * `@JvmInline` is only for the JVM harness (ADR-060's Tier 1 constraint).
 *
 * Oreo (black, white in the middle) collects the stickers. Mylo (brown and creamy) peels them off.
 */
class Tier1GenericValueClassTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.stickers

      import kotlin.jvm.JvmInline

      enum class Mood { HAPPY, SLEEPY }

      class Cat(val name: String)

      class Box<T>(val item: T)

      @JvmInline
      value class Sticker<T>(val value: T) {
        init { require(value != -1) { "peeled" } }
        fun shine(): String = "shiny"
      }

      @JvmInline
      value class Decal<T>(val serial: Long)

      /** A [Sticker] of three. */
      fun count(): Sticker<Int> = Sticker(3)
      fun bump(sticker: Sticker<Int>): Sticker<Int> = Sticker(sticker.value + 1)
      fun shout(sticker: Sticker<String>): String = sticker.value.uppercase()
      fun missing(): Sticker<String>? = null
      fun maybeCount(): Sticker<Int>? = Sticker(1)
      fun nap(): Sticker<Mood> = Sticker(Mood.SLEEPY)
      fun describe(sticker: Sticker<Mood>?): String = sticker?.value?.name ?: "none"
      fun nameOf(sticker: Sticker<Cat>): String = sticker.value.name
      fun adopt(name: String): Sticker<Cat>? = Sticker(Cat(name))
      fun tally(stickers: List<Sticker<Int>>): List<Sticker<Int>> = stickers
      fun oreoDecal(): Decal<Cat> = Decal(7L)
      fun serial(decal: Decal<Cat>): Long = decal.serial

      fun <T> stick(value: T): Sticker<T> = Sticker(value)
      fun <T> peel(sticker: Sticker<T>): T = sticker.value
      fun <T : Any> firm(sticker: Sticker<T>): T = sticker.value
      fun <T> blank(value: T?): Sticker<T?> = Sticker(value)

      fun <T> maybeStick(value: T): Sticker<T>? = Sticker(value)
      fun <T : Comparable<T>> ranked(sticker: Sticker<T>): T = sticker.value
      fun <T : Comparable<T>> higher(a: Sticker<T>, b: Sticker<T>): Sticker<T> =
        if (a.value >= b.value) a else b
      fun <T : Enum<T>> napOf(sticker: Sticker<T>): Sticker<T> = sticker
      fun <T> loudest(sticker: Sticker<T>): Sticker<T>
          where T : CharSequence, T : Comparable<T> = sticker
      fun initial(): Sticker<Char> = Sticker('o')
      fun starred(sticker: Sticker<*>): String = "star"
      fun <T : Any> firmly(value: T): Sticker<T> = Sticker(value)
      fun <T> stickAll(values: List<T>): List<Sticker<T>> = values.map { Sticker(it) }
      fun boxed(): Box<Sticker<Int>> = Box(Sticker(1))
      suspend fun later(): Sticker<Int> = Sticker(5)

      class StickerBook(val cover: Sticker<String>, var pages: Sticker<Int>) {
        var favourite: Sticker<Mood>? = null
        val decal: Decal<Cat> = Decal(9L)
        fun <T> stick(value: T): Sticker<T> = Sticker(value)
        fun <T> peel(sticker: Sticker<T>): T = sticker.value
        fun <T : Enum<T>> napOf(sticker: Sticker<T>): Sticker<T> = sticker
        fun <T : Comparable<T>> ranked(sticker: Sticker<T>): T = sticker.value
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val code: String by lazy { result.generatedCSharp.withoutDocComments() }

  private fun Tier1Result.skipped(member: String): Boolean =
    kspWarnings.any { it.contains("[nuget:") && Regex("""[ .]$member: """).containsMatchIn(it) }

  @Test
  fun `the generic value class is one positional record struct with no CreateChecked`() {
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertContains(code, "public readonly record struct Sticker<T>(T Value)")
    assertContains(code, "public readonly record struct Decal<T>(long Serial)")
    assertFalse(code.contains("CreateChecked"), "a generic struct has no per-instantiation export")
    // A generic struct has no bare cref spelling (CS1574 under GenerateDocumentationFile).
    assertFalse(
      Regex("""cref="[^"]*Sticker"""").containsMatchIn(result.generatedCSharp),
      "expected the [Sticker] link to fall back to <c>",
    )
    assertFalse(
      Regex("""@CName\("[^"]*_(sticker|decal)_create"""").containsMatchIn(result.generated),
      "expected no _create for a generic value class",
    )
    // ADR-228: one box pair per generic value class, shared by every instantiation.
    assertContains(result.generated, "_sticker_box\")")
    assertContains(result.generated, "_decal_unbox\")")
    assertContains(code, "public readonly record struct Sticker<T>(T Value) : INugetGenericValue")
  }

  @Test
  fun `a closed instantiation binds at every ordinary position`() {
    listOf(
      "public static Sticker<int> Count()",
      "public static Sticker<int> Bump(Sticker<int> sticker)",
      "public static string Shout(Sticker<string> sticker)",
      "public static Sticker<string>? Missing()",
      "public static Sticker<int>? MaybeCount()",
      "public static Sticker<global::Interop.Stickers.Mood> Nap()",
      "public static string Describe(Sticker<global::Interop.Stickers.Mood>? sticker)",
      "public static string NameOf(Sticker<global::Interop.Stickers.Cat> sticker)",
      "public static Sticker<global::Interop.Stickers.Cat>? Adopt(string name)",
      "public static Decal<global::Interop.Stickers.Cat> OreoDecal()",
      "public static long Serial(Decal<global::Interop.Stickers.Cat> decal)",
      "public StickerBook(Sticker<string> cover, Sticker<int> pages)",
      "public Sticker<int> Pages",
      "public Sticker<global::Interop.Stickers.Mood>? Favourite",
      "public Decal<global::Interop.Stickers.Cat> Decal",
    ).forEach { signature -> assertContains(code.replace("global::Interop.Stickers.Sticker", "Sticker").replace("global::Interop.Stickers.Decal<", "Decal<"), signature) }
    // The re-wrap is the source-level constructor, so `init` runs in Kotlin at the first crossing.
    assertContains(result.generated, "tier1.stickers.bump(tier1.stickers.Sticker(sticker))")
  }

  /** Open question 3 of the ADR: components project without a box, so the list binds. */
  @Test
  fun `a closed instantiation binds inside a List`() {
    assertContains(
      code.replace("global::Interop.Stickers.Sticker", "Sticker"),
      "public static IReadOnlyList<Sticker<int>> Tally(IReadOnlyList<Sticker<int>> stickers)",
    )
  }

  @Test
  fun `a member's own T inside the struct rides the bare-T wire`() {
    val spelled: String = code.replace("global::Interop.Stickers.Sticker", "Sticker")
    assertContains(spelled, "public static Sticker<T> Stick<T>(T value)")
    assertContains(spelled, "public static T Peel<T>(Sticker<T> sticker)")
    assertContains(spelled, "public static Sticker<T?> Blank<T>(T? value)")
    assertContains(spelled, "public static Sticker<T> Firmly<T>(T value) where T : notnull")
    assertContains(spelled, "public Sticker<T> Stick<T>(T value)")
    assertContains(spelled, "public T Peel<T>(Sticker<T> sticker)")
    assertContains(spelled, "return new Sticker<T>(NugetMarshal.FromHandle<T>(nativeResult));")
    // A bound `T` is a non-null slot, so `default(Sticker<T>)` throws before anything crosses.
    val firm: String = spelled.substringAfter("public static T Firm<T>(Sticker<T> sticker)")
      .substringBefore("public static")
    assertContains(firm, "where T : notnull")
    assertContains(firm, "default(Sticker) carries no Value")
  }

  /**
   * A member's own `T` inside the struct binds whatever its bound. `Comparable<T>` erases to
   * `Comparable<Any?>`; `Enum<T>` and an intersection run the unwrap, the call and the re-wrap
   * inside the trampoline, each read checked against every bound and cast to the trampoline's `T`.
   */
  @Test
  fun `a member's own bounded T inside the struct binds through the trampoline`() {
    val spelled: String = code.replace("global::Interop.Stickers.Sticker", "Sticker")
    assertContains(spelled, "public static T Ranked<T>(Sticker<T> sticker) where T : notnull")
    assertContains(
      spelled,
      "public static Sticker<T> Higher<T>(Sticker<T> a, Sticker<T> b) where T : notnull",
    )
    assertContains(
      spelled,
      "public static Sticker<T> NapOf<T>(Sticker<T> sticker) where T : struct, global::System.Enum",
    )
    assertContains(
      spelled,
      "public static Sticker<T> Loudest<T>(Sticker<T> sticker) where T : notnull",
    )
    assertContains(
      spelled,
      "public Sticker<T> NapOf<T>(Sticker<T> sticker) where T : struct, global::System.Enum",
    )
    assertContains(spelled, "public T Ranked<T>(Sticker<T> sticker) where T : notnull")
    assertContains(
      result.generated,
      "tier1.stickers.ranked<kotlin.Comparable<Any?>>(tier1.stickers.Sticker((sticker.asStableRef<Any>().get() as kotlin.Comparable<Any?>)))",
    )
    assertContains(
      result.generated,
      "tier1.stickers.napOf<T>(tier1.stickers.Sticker(sticker.asStableRef<Any>().get().let { bounded -> bounded as kotlin.Enum<*>; bounded as T })).value",
    )
    assertContains(
      result.generated,
      "tier1.stickers.loudest<T>(tier1.stickers.Sticker(sticker.asStableRef<Any>().get().let { bounded -> bounded as kotlin.Comparable<Any?>; bounded as kotlin.CharSequence; bounded as T })).value",
    )
    // `default(Sticker<Mood>)` is a real value (the first entry), so no null guard: `?? throw` on
    // a `struct` T does not compile.
    val nap: String = spelled
      .substringAfter("public static Sticker<T> NapOf<T>")
      .substringBefore("public static")
    assertFalse(nap.contains("carries no Value"), "expected no default guard on a struct T")
    val ranked: String = spelled
      .substringAfter("public static T Ranked<T>")
      .substringBefore("public static")
    assertContains(ranked, "carries no Value")
  }

  @Test
  fun `a generic value class outside the ordinary wire stays a named skip`() {
    listOf(
      "maybeStick", "stickAll", "initial", "starred",
    ).forEach { member ->
      assertTrue(result.skipped(member), "expected $member named; kspWarnings=${result.kspWarnings}")
    }
    val named = Regex("""\b(MaybeStick|StickAll|Initial|Starred)\(""")
    assertFalse(named.containsMatchIn(code))
    // ADR-228: an erased position crosses through the box pair.
    assertContains(code, "Sticker<int>> Boxed()")
    assertContains(code, "Sticker<int>> LaterAsync(")
  }

  /** ADR-225: the struct's own member binds on it, its receiver re-wrapped so `init` runs. */
  @Test
  fun `a generic value class own member binds on the struct`() {
    assertFalse(
      result.skipped("shine"),
      "expected shine to bind; kspWarnings=${result.kspWarnings}",
    )
    assertContains(code, "public string Shine()")
    assertContains(
      result.generated,
      "tier1.stickers.Sticker(value?.asStableRef<Any>()?.get()).shine()",
    )
  }

  @Test
  fun `the consumer constructs, compares and calls through every instantiation`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Stickers;
      public static class Consumer
      {
          public static void Run()
          {
              Sticker<int> three = Fixture.Count();
              bool same = three == new Sticker<int>(3);
              Sticker<int> four = Fixture.Bump(three with { Value = 3 });
              Sticker<string>? none = Fixture.Missing();
              string name = Fixture.NameOf(new Sticker<Cat>(new Cat("Mylo")));
              Sticker<string> stuck = Fixture.Stick("Oreo");
              string peeled = Fixture.Peel(stuck);
              Sticker<string?> blank = Fixture.Blank<string>(null);
              string firm = Fixture.Firm(new Sticker<string>("Mylo"));
              Decal<Cat> decal = Fixture.OreoDecal();
              long serial = Fixture.Serial(decal);
              System.Collections.Generic.IReadOnlyList<Sticker<int>> tally =
                  Fixture.Tally(new[] { three, four });
              int ranked = Fixture.Ranked(new Sticker<int>(3));
              Sticker<string> higher = Fixture.Higher(new Sticker<string>("Mylo"), new Sticker<string>("Oreo"));
              Sticker<Mood> napped = Fixture.NapOf(new Sticker<Mood>(Mood.Sleepy));
              Sticker<string> loudest = Fixture.Loudest(new Sticker<string>("Oreo"));
              using var book = new StickerBook(new Sticker<string>("Mylo's"), three);
              Sticker<Mood> booked = book.NapOf(napped);
              string bookRanked = book.Ranked(new Sticker<string>("Oreo"));
              string shine = three.Shine();
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}

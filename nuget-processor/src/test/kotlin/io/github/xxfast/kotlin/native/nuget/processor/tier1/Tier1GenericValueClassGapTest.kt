package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-226: a function's own `T` inside a generic value class under `?` and inside a `List`,
 * `Set` or `Map`, every value class whose underlying can itself be null at a `?` position, a
 * non-null value class over a nullable primitive, and a star-projected parameter. When the outer
 * is nullable and the underlying admits null, the slot is the value class's box handle; a
 * `T : Any` underlying keeps the null pointer in-band.
 *
 * `@JvmInline` is only for the JVM harness (ADR-060's Tier 1 constraint).
 *
 * Oreo (black, white in the middle) leaves gaps in the album. Mylo (brown and creamy) fills them
 * with blank stickers, which are not the same thing as no sticker.
 */
class Tier1GenericValueClassGapTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.gaps

      import kotlin.jvm.JvmInline

      @JvmInline
      value class Sticker<T>(val value: T) {
        init { require(value != -1) { "peeled" } }
      }

      @JvmInline
      value class Nick(val name: String?)

      @JvmInline
      value class TreatCount(val count: Int?)

      fun <T> maybeStick(value: T, present: Boolean): Sticker<T>? =
        if (present) Sticker(value) else null

      fun <T> isAbsent(sticker: Sticker<T>?): Boolean = sticker == null

      fun <T> passGap(sticker: Sticker<T>?): Sticker<T>? = sticker

      fun <T : Any> firmMaybe(value: T?): Sticker<T>? = value?.let { Sticker(it) }

      fun <T : Any> firmOr(sticker: Sticker<T>?, fallback: T): T = sticker?.value ?: fallback

      fun <T> stickAll(values: List<T>): List<Sticker<T>> = values.map { Sticker(it) }

      fun <T> peelAll(stickers: Set<Sticker<T>>): List<T> = stickers.map { it.value }

      fun <T> byLabel(stickers: Map<String, Sticker<T>>): Map<String, Sticker<T>> = stickers

      fun <T> keyedBy(stickers: Map<Sticker<T>, String>): List<String> = stickers.values.sorted()

      fun <T> restack(values: List<T>): MutableList<Sticker<T>> =
        values.map { Sticker(it) }.toMutableList()

      fun <T> stackRows(rows: List<List<T>>): List<List<Sticker<T>>> =
        rows.map { row -> row.map { Sticker(it) } }

      fun <T> gaps(stickers: List<Sticker<T>?>): List<Sticker<T>?> = stickers

      fun <T> countGaps(stickers: List<Sticker<T>?>): String =
        "${'$'}{stickers.count { it == null }} gaps, " +
          "${'$'}{stickers.count { it != null && it.value == null }} blanks"

      fun <T> gapPages(pages: Map<String, Sticker<T>?>): Map<String, Sticker<T>?> = pages

      fun blankLabel(): Sticker<String?> = Sticker(null)

      fun labelOr(sticker: Sticker<String?>): String = sticker.value ?: "blank"

      fun maybeLabel(present: Boolean): Sticker<String?>? = if (present) Sticker(null) else null

      fun labelState(sticker: Sticker<String?>?): String =
        if (sticker == null) "no sticker" else sticker.value ?: "blank sticker"

      fun albumPage(): List<Sticker<String?>?> = listOf(null, Sticker(null), Sticker("Oreo"))

      fun blankNick(): Nick = Nick(null)

      fun nickOrAnon(nick: Nick): String = nick.name ?: "anon"

      fun maybeNick(present: Boolean): Nick? = if (present) Nick(null) else null

      fun nickState(nick: Nick?): String = if (nick == null) "no nick" else nick.name ?: "nameless"

      fun nicknames(): List<Nick?> = listOf(null, Nick(null), Nick("Oreo"))

      fun countNicks(nicks: List<Nick?>): String =
        "${'$'}{nicks.count { it == null }} missing, " +
          "${'$'}{nicks.count { it != null && it.name == null }} nameless"

      fun uncounted(): TreatCount = TreatCount(null)

      fun treatsOr(treats: TreatCount): Int = treats.count ?: -1

      fun maybeTreats(present: Boolean): TreatCount? = if (present) TreatCount(null) else null

      fun treatState(treats: TreatCount?): String =
        if (treats == null) "no count" else treats.count?.toString() ?: "uncounted"

      fun starred(sticker: Sticker<*>): String = "star ${'$'}{sticker.value}"

      fun Nick?.callName(): String = if (this == null) "no nick" else name ?: "nameless"

      fun <T> Sticker<T>?.presence(): String =
        if (this == null) "gap" else if (value == null) "blank" else "stuck"

      class GapAlbum(var spare: Nick?, var cover: Sticker<String?>?) {
        var treats: TreatCount? = TreatCount(null)

        val roll: List<Nick?> get() = listOf(spare, null)

        fun <T> swapGap(sticker: Sticker<T>?): Sticker<T>? = sticker

        fun <T> fill(values: List<T>): List<Sticker<T>> = values.map { Sticker(it) }

        fun peek(sticker: Sticker<*>): String = "peek ${'$'}{sticker.value}"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  private val cs: String get() = result.generatedCSharp

  @Test
  fun `the generated C# builds strictly`() {
    Tier1CSharpCompile.assertCompiles(result, "public static class Consumer { }")
  }

  @Test
  fun `the fixture plans, compiles and its generated C# builds strictly`() {
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { it.contains("tier1.gaps.") },
      "expected nothing to skip; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(result, "public static class Consumer { }")
  }

  @Test
  fun `a nullable value class over a nullable underlying is its box handle`() {
    assertContains(
      cs,
      "public static global::Interop.Gaps.Sticker<T>? MaybeStick<T>(T value, bool present)",
    )
    assertContains(cs, "global::Interop.Gaps.Sticker<T>.NugetRegister();")
    assertContains(
      cs,
      "return NugetMarshal.FromHandle<global::Interop.Gaps.Sticker<T>?>(nativeResult);",
    )
    assertContains(
      cs,
      "NugetMarshal.Wrap<global::Interop.Gaps.Sticker<T>?>(sticker, out stickerOwned);",
    )
    assertContains(cs, "public static string NickState(global::Interop.Gaps.Nick? nick)")
    assertContains(cs, "public static global::Interop.Gaps.TreatCount? MaybeTreats(bool present)")
    assertContains(cs, "public static string CallName(this global::Interop.Gaps.Nick? receiver)")
    assertContains(
      cs,
      "public static string Presence<T>(this global::Interop.Gaps.Sticker<T>? receiver)",
    )
    assertContains(result.generated, "as tier1.gaps.Nick?)")
    assertContains(result.generated, "as tier1.gaps.Sticker<kotlin.Any?>?)")
  }

  @Test
  fun `a value class over a nullable primitive crosses on its has-value pair`() {
    assertContains(cs, "Native_TreatsOr(treats.Count.HasValue, treats.Count.GetValueOrDefault()")
    assertContains(cs, "new global::Interop.Gaps.TreatCount(hasValue ? valueOut : null)")
    assertContains(result.generated, "tier1.gaps.TreatCount(if (treatsHasValue) treats else null)")
  }

  @Test
  fun `an own T crosses inside a collection element by element`() {
    assertContains(
      cs,
      "public static IReadOnlyList<IReadOnlyList<global::Interop.Gaps.Sticker<T>>> StackRows<T>(",
    )
    assertContains(cs, "IReadOnlyDictionary<global::Interop.Gaps.Sticker<T>, string> stickers")
  }

  @Test
  fun `a star-projected parameter is a synthesized method type parameter`() {
    assertContains(
      cs,
      "public static string Starred<TSticker>(global::Interop.Gaps.Sticker<TSticker> sticker)",
    )
    assertContains(
      cs,
      "public string Peek<TSticker>(global::Interop.Gaps.Sticker<TSticker> sticker)",
    )
    assertContains(result.generated, "tier1.gaps.starred(tier1.gaps.Sticker(")
  }
}

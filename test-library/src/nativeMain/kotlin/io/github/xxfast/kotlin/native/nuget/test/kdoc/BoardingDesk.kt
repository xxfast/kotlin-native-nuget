package io.github.xxfast.kotlin.native.nuget.test.kdoc

// ADR-150 fixture ("KDoc on an exported Kotlin declaration becomes an XML doc comment on its
// generated C# declaration"). Everything in this package exists so
// `IntegrationTests/XmlDocTests.cs` can read the documentation XML the consumer build emits for the
// generated shim and assert the mapping seam by seam. The explanatory notes are `//` comments on
// purpose: a KDoc block here would become the asserted `<summary>`. The KDoc text is kept short and
// distinctive for the same reason.
//
// Seams crossed here, one member each:
//   - a class summary (BoardingDesk)
//   - `@param` on every parameter + `@return` + a MAPPED `@throws`, on a method with a trailing
//     default, so ADR-096 synthesizes an omitting overload that must drop the omitted `<param>`
//     (book)
//   - a PARTIAL `@param` set: one of two parameters documented, the other gets an empty tag (groom)
//   - an UNMAPPED `@throws` type, which crefs `KotlinException` (rehome)
//   - `@suppress`: no doc comment at all on that member (secretTreat)
//
// ADR-150 amendment (the deferred inline and tag scope), one seam each:
//   - body paragraphs after the first, as `<para>` elements of one `<remarks>` (book)
//   - an inline backtick span whose content needs XML escaping (book, paragraph three)
//   - a FENCED code block, which becomes a `<code>` element inside the same `<remarks>` with its
//     content XML-escaped and its `kotlin` language tag discarded (book, after paragraph three)
//   - a `[Link]` that RESOLVES to a type this generated file declares, so it becomes a
//     `<see cref>`, beside one that does not resolve (a parameter name) and falls back to `<c>`
//     (book, paragraph two)
//   - a tag section containing a BLANK LINE, which continues that tag rather than starting a body
//     paragraph, and ends only at the next tag (rehome's `@param home`)
//   - `@see` to a type that resolves (`<seealso cref>`) beside `@see` to a member that does not
//     (a closing "See also:" paragraph in `<remarks>`) (rehome)
//   - `@property` on a property with its own KDoc and on one without, plus `@constructor` and a
//     class-level `@param` (KdocFoodBowl, at the bottom of this file)
//
// The enum summary lives in `Volume.kt` and the `expect`/`actual` row in `SunSpot.kt`.

/** Boarding desk for Oreo and Mylo. */
class BoardingDesk(private val cat: String) {

  /** The cat this desk is keeping. */
  val guest: String get() = cat

  /**
   * A desk for an unnamed stray.
   *
   * @param stray the stray's number
   */
  constructor(stray: Int) : this("stray #" + stray)

  /**
   * Waits for the cat to settle.
   *
   * @param minutes how long to wait
   * @return what the cat did
   */
  suspend fun settle(minutes: Int): String = cat + " settled in " + minutes + " minutes"

  /**
   * Books a stay for the cat.
   *
   * Ask for [nights] up front; the cat sleeps through a [Snooze] either way.
   *
   * The reference reads `Pair<Oreo & Mylo>` when both cats check in.
   *
   * ```kotlin
   * val reference = desk.book(nights = 2) // Oreo & Mylo <both>
   * ```
   *
   * @param nights how many nights
   * @param suite which suite, defaults to the sunny one
   * @return the booking reference
   * @throws IllegalArgumentException when nights is not positive
   */
  fun book(nights: Int, suite: String = "sunny"): String {
    require(nights > 0) { "nights must be positive" }
    return "$cat/$suite/$nights"
  }

  /**
   * Grooms the cat.
   *
   * @param brush which brush to use
   */
  fun groom(brush: String, gentle: Boolean): String =
    "$cat groomed with the $brush brush${if (gentle) " gently" else ""}"

  /**
   * Rehomes the cat.
   *
   * @param home where the cat goes
   *
   * once the sunbeam moves
   * @throws RuntimeException when the boarding desk is closed
   * @see SunSpot
   * @see book
   */
  fun rehome(home: String): String {
    if (home.isBlank()) throw RuntimeException("the boarding desk is closed")
    return "$cat moved to $home"
  }

  /** @suppress */
  fun secretTreat(): String = "$cat gets a churu"
}

// ADR-150: the remaining declaration families, one documented member each, so the consumer-side
// documentation XML can pin every family. Same rule as above: explanatory notes are `//`, the KDoc
// below is the asserted text. Names are deliberately distinctive: an entry point derives from the
// simple name, not the package, so a collision with another exported class in this module is an
// ERROR_C_ENTRY_POINT_COLLISION.

/** The jar the treats live in. */
object KdocTreatJar {

  /**
   * Takes one out.
   *
   * @param flavour which treat
   * @return what came out
   */
  fun take(flavour: String): String = "a $flavour treat"
}

/** Anything a cat will sit on. */
interface Perchable {

  /**
   * Sits on it.
   *
   * @return where the cat ended up
   */
  fun perch(): String
}

/**
 * Weighs a boarding cat.
 *
 * @param name whose weight
 * @return the weight in grams
 */
fun weighBoardingCat(name: String): Int = name.length * 100

/**
 * Doubles a stay.
 *
 * @return the doubled night count
 */
fun BoardingDesk.doubleStay(nights: Int): String = book(nights * 2)

/** A weight in grams. */
value class Grams(val value: Int) {

  /**
   * The same weight in kilograms.
   *
   * @return the kilogram value
   */
  fun kilograms(): Int = value / 1000
}

// ADR-150 amendment: the `@property` / `@constructor` / class-level `@param` row. Per spike 1
// finding 4 a constructor property and a declared primary constructor both report
// `docString == null` through KSP and their text lives on the CLASS comment, so `flavour` and the
// primary constructor are documented here only if that class comment is read. `rinsed` carries its
// own KDoc, which must outrank the `@property rinsed` text below; `scoops` is named by a
// class-level `@param` only, which documents the constructor parameter and NOT the property.

/**
 * The bowl Mylo empties in one sitting.
 *
 * @property flavour what Mylo is eating
 * @property rinsed whether the bowl was hosed down first
 * @constructor Fills the bowl for one sitting.
 * @param scoops how many scoops went in
 */
class KdocFoodBowl(val flavour: String, val scoops: Int) {

  /** Whether Oreo licked it clean first. */
  val rinsed: Boolean get() = scoops > 1
}

/** A kind of nap. */
sealed class Snooze {

  /** The short kind. */
  data class Catnap(val minutes: Int) : Snooze() {

    /**
     * How it felt.
     *
     * @return the feeling
     */
    fun feels(): String = if (minutes < 20) "short" else "long"
  }
}

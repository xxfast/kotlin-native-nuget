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
   * This paragraph is dropped in v1.
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
   * @throws RuntimeException when the boarding desk is closed
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

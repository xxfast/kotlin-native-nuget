package io.github.xxfast.kotlin.native.nuget.test.grooming

/**
 * ADR-095 · overloads on the four *static* export routes.
 *
 * ADR-090 gave ordinary-class methods a numbering scheme (`CatNarrator`); it recorded, verified by
 * the same spike, that the other four same-name routes still crash the whole `packNuget` run with
 * the identical `planFor` duplicate-plans message. This file is the consumer-side red step for all
 * four, one route per carrier, deliberately in its own package: top-level and extension numbering
 * are *package*-scoped, so putting these cells anywhere else would entangle their counters with
 * unrelated fixtures.
 *
 * The four routes and the carrier each one lands on:
 *
 *  - **object member** ([Parlour]) · symbol `$owner.$name`, export `parlour_$name`
 *  - **companion member** ([Groomer.Companion]) · symbol `$owner.Companion.$name`, export
 *    `groomer_companion_$name`
 *  - **top-level function** ([bookGrooming], [waitTime]) · symbol `$package.$name`, bare export
 *  - **extension function** ([Mitten.pat], [Tomcat.pat]) · symbol `$package.$name`
 *    (**receiver-agnostic**), export `${receiver}_$name`
 *
 * Per route there is one overload whose parameter needs marshalling (`String`) and one that needs
 * none (`Int`, or no parameter at all), so no route can go green on the cheapest shape alone.
 *
 * Three cells beyond the plain arity ladder, each aimed at a mechanism ADR-095 names:
 *
 *  - [Parlour.rate], [Groomer.of] and [Mitten.brush] each pair an `Int` overload with a [Coat]
 *    overload. Both cross the C ABI as `int`, so the two generated `DllImport`s share one wire
 *    signature: this is the pair that emits CS0111 if the *private extern name* is left derived
 *    from the unsuffixed public name instead of the numbered symbol tail. They cover the three
 *    extern-name sites the ADR lists separately (`Native_$tail`, `Native_Companion_$tail`, and
 *    the extension's own `Native_$tail`).
 *  - [waitTime] returns `Int?`, which routes top-level overloads through
 *    `topLevelNullablePrimitivePlan` / `staticLegacyTwoCall` (`${name}_has_value` +
 *    `${name}_value`) instead of the single-call static shape the rest of this file uses. Without
 *    it, that planner entry point could keep its unnumbered symbol and never be noticed.
 *  - [Tomcat.pat] is the same name as [Mitten.pat] on a **different receiver** in the same
 *    package. The extension symbol omits the receiver, so this pair crashes generation today too,
 *    even though `mitten_pat` and `tomcat_pat` would never collide as C exports.
 *
 * A reference-nullability-only namesake pair belongs to `ERROR_CSHARP_SIGNATURE_COLLISION`, whose
 * correct outcome is a *failed* generation, so it is deliberately not here (same call as
 * `CatNarrator`'s).
 */

/** The coat an overload is grooming for. Exists only so an `Int` and an enum can share one wire. */
enum class Coat { TUXEDO, TABBY }

/**
 * Route 1 · object members. Numbering is per object: the first [describe] keeps `parlour_describe`,
 * the second becomes `parlour_describe_2`, and the C# surface stays `Parlour.Describe(...)`.
 */
object Parlour {
  /** First declared [describe]: no parameter, so nothing to marshal. */
  fun describe(): String = "the parlour is open"

  /** Second declared [describe]: `String` parameter, the marshalled half of the pair. */
  fun describe(cat: String): String = "$cat is booked in"

  /** First declared [rate]: `Int` parameter. */
  fun rate(stars: Int): String = "the parlour is rated $stars"

  /** Second declared [rate]: [Coat] parameter, the same `int` on the wire as [rate]'s `Int`. */
  fun rate(coat: Coat): String = "the parlour grooms ${coat.name.lowercase()} coats"
}

/**
 * Route 2 · companion members. Numbering is per companion; the exports are
 * `groomer_companion_of`, `_2`, `_3` and the C# surface is a static overload set on `Groomer`.
 */
class Groomer(val name: String) {
  companion object {
    /** First declared [of]: `String` parameter, the marshalled half. */
    fun of(name: String): Groomer = Groomer(name)

    /** Second declared [of]: `Int` parameter, nothing to marshal. */
    fun of(chairs: Int): Groomer = Groomer("groomer of $chairs chairs")

    /** Third declared [of]: [Coat] parameter, the same `int` wire as [of]'s `Int`. */
    fun of(coat: Coat): Groomer = Groomer("${coat.name.lowercase()} groomer")
  }
}

/** Route 4 · extension receiver. Oreo, black with white in the middle. */
class Mitten(val name: String)

/** Route 4 · the *second* receiver, so the cross-receiver namesake pair has somewhere to live. */
class Tomcat(val name: String)

/**
 * Route 3 · top-level functions, single-call shape. First declared [bookGrooming]: no parameter.
 * Exports bare `bookGrooming`; C# renders it on the file's static class, keeping camelCase.
 */
fun bookGrooming(): String = "the next slot is free"

/** Second declared [bookGrooming]: `String` parameter, the marshalled half. */
fun bookGrooming(cat: String): String = "$cat is groomed at noon"

/**
 * Route 3 · top-level functions, ADR-002 two-call shape (`waitTime_has_value` / `waitTime_value`).
 * First declared [waitTime]: no parameter, always present.
 */
fun waitTime(): Int? = 15

/**
 * Second declared [waitTime]: `String` parameter and a genuine `null` branch, so a mis-numbered
 * `_has_value` / `_value` pair shows up as the wrong presence answer rather than the wrong number.
 */
fun waitTime(cat: String): Int? = if (cat.isBlank()) null else cat.length

/** First declared [pat]: no parameter. Export `mitten_pat`. */
fun Mitten.pat(): String = "$name purrs"

/** Second declared [pat]: `String` parameter, the marshalled half. Export `mitten_pat_2`. */
fun Mitten.pat(style: String): String = "$name enjoys a $style pat"

/** First declared [brush]: `Int` parameter. Export `mitten_brush`. */
fun Mitten.brush(strokes: Int): String = "$name is brushed $strokes times"

/** Second declared [brush]: [Coat] parameter, the same `int` wire as [brush]'s `Int`. */
fun Mitten.brush(coat: Coat): String = "$name is brushed for a ${coat.name.lowercase()} coat"

/**
 * The cross-receiver namesake: same package and same name as [Mitten.pat], different receiver.
 * Package-scoped numbering makes this the third `pat` occurrence, so it exports `tomcat_pat_3`
 * even though `tomcat_pat` would already have been unique.
 */
fun Tomcat.pat(): String = "$name tolerates exactly one pat"

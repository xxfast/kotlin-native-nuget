package io.github.xxfast.kotlin.native.nuget.test.whiskers

/**
 * ADR-096 · function default parameters on the five *function* export routes.
 *
 * ADR-091 shipped the `@JvmOverloads` trailing-omitting rule for constructors and explicitly left
 * functions open (ROADMAP line 108). This file is the consumer-side red step for every route the
 * ADR names, one carrier per route, deliberately in its **own package**: top-level and extension
 * numbering are `(package, name)`-scoped (ADR-095), so putting these cells in an existing package
 * would entangle their counters with unrelated fixtures.
 *
 * The rule under test: for parameters `p1..pn`, `d` is the number of *trailing* parameters that all
 * have a default; for each `k` in `1..d` there is one extra C# overload taking `p1..p(n-k)`. The
 * full signature is always kept. A default followed by a required parameter synthesizes nothing.
 *
 * The routes and the carrier each one lands on:
 *
 *  - **class method** ([Announcer.announce], [Announcer.tally]) · per-`(class, name)` counter
 *  - **class method next to a *declared* overload** ([Narrator.rate]) · proves synthesized numbers
 *    continue after ADR-095's declared ones without colliding
 *  - **`object` member** ([Kibble.scoop]) · per-object counter
 *  - **companion member** ([Basket.Companion.of]) · per-companion counter
 *  - **top-level function** ([hail]) · per-`(package, name)` counter, and the route that also has
 *    to survive `planFor` becoming plural (`ForwardCallablePlanner`'s duplicate-plan `require`)
 *  - **extension function** ([Paw.knead]) · the other plural-`planFor` route, and the one where
 *    the receiver is a `ForwardReceiver.Value` rather than a plan parameter, so truncating
 *    **every** parameter must still leave `paw.Knead()` callable
 *  - **middle default** ([book]) · absent output, not wrong output
 *  - **`expect`/`actual`** · lives in `platform/PlatformApi.kt` next to ADR-091's `Beacon`,
 *    because the bit only exists on the `expect` and the actuals are per-target files
 *
 * [Announcer.announce] pairs a `String` (needs conversion at the seam) with a `Boolean` (needs
 * none), so no route can go green on the cheapest wire shape alone. Every default value here is
 * distinct and non-obvious, and every body returns a distinct string, so an overload wired to the
 * wrong Kotlin entry shows up as a wrong value rather than a plausible one.
 */

/**
 * Route 1 · class methods. [announce] has ONE trailing default (`k = 1`); [tally] has TWO
 * (`k = 1..2`), so both suffix lengths are exercised and the C# surface must be callable at all
 * three arities.
 */
class Announcer(val prefix: String) {
  /** `message` marshals, `loud` does not. Omitting `loud` must pick up `false` from Kotlin. */
  fun announce(message: String, loud: Boolean = false): String =
    if (loud) "$prefix: ${message.uppercase()}!" else "$prefix: $message"

  /** Two trailing defaults. `"cats"` and `false` are declared only here, never in C#. */
  fun tally(count: Int, label: String = "cats", excited: Boolean = false): String =
    "$prefix counted $count $label${if (excited) "!!" else ""}"
}

/**
 * Route 2 · a *declared* overload pair sitting next to a defaulted one. ADR-095 numbers the two
 * declared [rate]s `narrator_rate` / `narrator_rate_2`; the synthesized `(mood)` entry has to be
 * appended after them, continuing the same per-`(class, name)` counter. C# surface:
 * `Rate(int)`, `Rate(string, int)`, `Rate(string)` -- one natural overload set, no visible numbers.
 */
class Narrator(val name: String) {
  /** Declared #1. No defaults, so nothing is synthesized for it. */
  fun rate(count: Int): String = "$name rates $count naps"

  /** Declared #2, with one trailing default. Its omitting overload is `Rate(string)`. */
  fun rate(mood: String, boost: Int = 1): String = "$name rates $mood at ${mood.length + boost}"
}

/**
 * Route 3 · `object` member with a trailing default. Exports `kibble_scoop` and `kibble_scoop_2`.
 */
object Kibble {
  fun scoop(flavour: String, scoops: Int = 2): String = "$scoops scoops of $flavour"
}

/**
 * Route 4 · companion member with a trailing default. Exports `basket_companion_of` and
 * `basket_companion_of_2`; the C# surface is a static overload set on `Basket`.
 */
class Basket(val label: String) {
  companion object {
    fun of(city: String, capacity: Int = 4): Basket = Basket("$city basket for $capacity")
  }
}

/** Route 6 · the extension receiver. Oreo kneads; so does Mylo, harder. */
class Paw(val name: String)

/**
 * Route 6 · an extension whose parameters are **all** defaulted. The receiver is not a plan
 * parameter (`ForwardReceiver.Value`), so at `k = 2` the plan carries zero parameters and
 * `paw.Knead()` must still be callable with the receiver intact.
 */
fun Paw.knead(times: Int = 2, surface: String = "blanket"): String =
  "$name kneads the $surface $times times"

/**
 * Route 5 · top-level function with a trailing default. This is the ROADMAP item's own example
 * shape (`fun greet(name: String, loud: Boolean = false)`), renamed because a bare top-level export
 * is `toCName(name)` with no package qualifier and `greet` is already taken (ROADMAP line 109).
 */
fun hail(name: String, loud: Boolean = false): String =
  if (loud) "HI ${name.uppercase()}" else "hi $name"

/**
 * The middle-default negative: `capacity` has a required parameter after it, so a positional Kotlin
 * call can never skip it and NO omitting overload exists. Absent output, not wrong output.
 */
fun book(name: String, capacity: Int = 3, city: String): String =
  "$name booked $capacity spots in $city"

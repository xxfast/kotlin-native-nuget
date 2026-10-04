package io.github.xxfast.kotlin.native.nuget.test.mishaps

import io.github.xxfast.kotlin.native.nuget.hidden.HairballError

/**
 * Fixture for ADR-201: `Throwable` beyond the ADR-107 property getter. Its own package, clear of
 * the `issue56` export prefix ADR-107 recorded a collision against.
 *
 * Out of Kotlin every value is the ADR-107 envelope, so C# reads a constructed, unthrown
 * `System.Exception` with the ADR-029 mapping and the ADR-028 cause chain. Into Kotlin any
 * `System.Exception` arrives as the ADR-161 `NugetManagedException`, carrying the managed type
 * name and message only.
 *
 * The seams, once each:
 * - [latest] / [worst]: a nullable and a non-null sync return;
 * - [hairball]: a module-local, unexported subclass ([HairballError]) at a return;
 * - [all] / [timeline] / [byCat]: a `List` property, a `List` return with a null element, and a
 *   `Map` value;
 * - [report] / [reportOrSkip] / [lastMishap]: a `Throwable` parameter, a nullable `Exception`
 *   parameter and a `var` setter, each receiving a `NugetManagedException`;
 * - [worstOrThrow]: the return route's throw path;
 * - [strict]: a parameter declared narrower than `RuntimeException`, which skips named.
 *
 * Oreo ate the plant; Mochi coughs up a hairball; Mylo reports both.
 */
class MishapLog {
  private val seen: MutableList<Throwable> = mutableListOf()

  /** The last reported mishap, or null before any. */
  fun latest(): Throwable? = seen.lastOrNull()

  /** A mapped `IllegalArgumentException` whose cause is an unmapped `RuntimeException`. */
  fun worst(): Throwable =
    IllegalArgumentException("Oreo ate the plant", RuntimeException("the door was left open"))

  /** A module-local subclass of `IllegalStateException`, outside the export set. */
  fun hairball(): HairballError = HairballError("Mochi, 3am")

  /** Every reported mishap, oldest first. */
  val all: List<Throwable> get() = seen.toList()

  /** Three entries, the middle one null. */
  fun timeline(): List<Throwable?> =
    listOf(IllegalStateException("the bowl is empty"), null, HairballError("Mochi, 3am"))

  /** A `Map` value per cat. */
  fun byCat(): Map<String, Throwable> = mapOf(
    "Oreo" to IllegalArgumentException("Oreo ate the plant"),
    "Mochi" to HairballError("Mochi, 3am"),
  )

  /** Records any C# exception; Kotlin sees a `NugetManagedException`. */
  fun report(mishap: Throwable) {
    seen += mishap
  }

  /** Records a non-null mishap, and says whether it did. */
  fun reportOrSkip(mishap: Exception?): Boolean {
    if (mishap == null) return false
    seen += mishap
    return true
  }

  /** A `var` the C# side can write; a write also records the mishap. */
  var lastMishap: Throwable? = null
    set(value) {
      field = value
      if (value != null) seen += value
    }

  /** The Kotlin view of the last reported mishap: its simple class name and message. */
  fun describeLast(): String = seen.last().let { "${it::class.simpleName}: ${it.message}" }

  /** The return route's throw path: throws instead of returning when [fail] is set. */
  fun worstOrThrow(fail: Boolean): Throwable =
    if (fail) throw IllegalStateException("the vet is closed") else worst()

  /** Declared narrower than `RuntimeException`, so it cannot hold what C# sends: a named skip. */
  fun strict(mishap: IllegalStateException): String = mishap.message ?: ""
}

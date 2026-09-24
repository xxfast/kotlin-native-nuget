package io.github.xxfast.kotlin.native.nuget.test.issue297

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

// Issue #297 / ADR-164 fixture ("one widened signature, nullable means unset").
// Crosses every seam the Decision names, one shape each, rather than the fewest types:
//
//   - [Config]   : four trailing defaults, one per nullable encoding kind (Uuid string pointer,
//                  Duration ticks HasValue pair, primitive HasValue pair, enum ordinal HasValue
//                  pair), on the constructor AND the data-class `copy` route. `id` defaults to
//                  `Uuid.random()` so "Kotlin evaluated the default" is observable, not a constant.
//   - [Registry] : already-nullable defaults, so `Optional<T>` and its `IsSet` slot: one reference
//                  (`String?`) and one value (`Int?`). Non-null defaults, so unset, explicit null
//                  and a value are three distinct results.
//   - [Book]     : a MIDDLE default. Required-but-nullable, never optional.
//   - [greet]    : top-level route, reference-type defaults (String and an exported class handle),
//                  so null-pointer-as-unset is crossed for both.
//   - [Wide]     : 10 defaults, over the cap of 8. Only the last 8 widen; p0 and p1 stay required.
//   - [Base] / [Derived] : a defaulted member overridden without restating the default, so the
//                  override's C# signature must come from the root overridee.

/** Mylo's feeding schedule mode. */
enum class Mode { Never, Always }

/** Every field defaulted, one per nullable encoding. */
data class Config(
  val id: Uuid = Uuid.random(),
  val timeout: Duration = 1.minutes,
  val retries: Int = 3,
  val mode: Mode = Mode.Never,
)

/** Already-nullable defaulted parameters, with non-null defaults so unset differs from null. */
object Registry {
  fun describe(name: String, owner: String? = "nobody"): String = "$name belongs to ${owner ?: "(none)"}"

  fun treats(name: String, count: Int? = 7): String = "$name gets ${count ?: "no"} treats"
}

/** Middle default: `pages` has a required parameter after it. */
class Book(val title: String, val pages: Int = 100, val city: String) {
  fun describe(): String = "$title has $pages pages, printed in $city"
}

/** Top-level route with reference-type defaults. */
fun greet(name: String, greeting: String = "hi", cat: Cat = Cat("Momo")): String =
  "$greeting $name, from ${cat.name}"

/** Ten defaults, two over the cap. Each distinct so a mis-wired slot shows up in [sum]. */
class Wide(
  val p0: Int = 0,
  val p1: Int = 1,
  val p2: Int = 2,
  val p3: Int = 3,
  val p4: Int = 4,
  val p5: Int = 5,
  val p6: Int = 6,
  val p7: Int = 7,
  val p8: Int = 8,
  val p9: Int = 9,
) {
  fun sum(): Int = p0 + p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
}

/** The base declares the default. */
open class Base {
  open fun rate(score: Int = 5): Int = score
}

/** The override inherits the default without restating it, and must dispatch here. */
class Derived : Base() {
  override fun rate(score: Int): Int = score * 10
}

/** The interface declares the default, so `IGreeter` and every implementer widen alike. */
interface Greeter {
  fun greet(name: String, times: Int = 1): String
}

/** Implements the defaulted member without restating the default. */
class Parrot : Greeter {
  override fun greet(name: String, times: Int): String = List(times) { "hi $name" }.joinToString(" ")
}

/** Hands C# a [Greeter]-typed reference. */
fun parrot(): Greeter = Parrot()

/** A per-call defaulted lambda: omittable, and a passed one runs before this returns. */
fun notify(message: String, onDone: (String) -> Unit = {}): String {
  onDone(message)
  return "sent $message"
}

/** A constructor STORES its lambda, so ADR-160 keeps it off C#; the default is Kotlin's. */
class Button(val label: String = "ok", val onClick: () -> Unit = {}) {
  private var clicks: Int = 0

  fun click(): String {
    clicks++
    onClick()
    return "$label clicked $clicks"
  }
}

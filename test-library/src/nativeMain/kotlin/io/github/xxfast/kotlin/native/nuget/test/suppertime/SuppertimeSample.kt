/**
 * Fixture for Kotlin default arguments on the **legacy** `suspend` / `Flow` routes, the mirror of
 * ADR-164 (which covers the plan route only). A defaulted parameter must bind as a C# optional
 * parameter, and omitting it must make **Kotlin** evaluate its default, not pass a C# copy or a
 * zero filler. The legacy Kotlin bodies used to call positionally with every argument, so a
 * C#-only `= null` would have compiled and silently handed Kotlin `0`; the ADR-164 `when (mask)`
 * dispatch on both halves is what makes omitting a widened argument reach Kotlin's own default.
 *
 * Every default here is one C# cannot fake with a constant: it reads the receiver's state
 * ([Dinnerbell.bowl], [Course.Main.warmth]), bumps a per-instance counter
 * ([Dinnerbell.served]), or is computed from an earlier argument (`name.length * 100`). Every
 * callable returns its arguments joined with `|`, so a zero filler (`0`), a `null` filler, or a
 * swapped argument reads back as the wrong text.
 *
 * Routes, one cell each: class `suspend` member, class `suspend` returning `StateFlow` (ADR-068),
 * sealed-arm `suspend` (ADR-118), top-level `suspend`, and the class `Flow`, `StateFlow` and held
 * `MutableStateFlow` members. Each crosses a widened `Int` (nullable wire `HasValue` + value), a
 * widened `String` (`String?` slot), and an already-nullable `Int? = 5` (ADR-164 rule 2,
 * `Optional<int?>` plus an `IsSet` slot: omitted is 5, an explicit `null` is `null`).
 *
 * Decided scope, pinned here:
 * - a defaulted class (handle) or collection parameter stays **required** ([Dinnerbell.share]),
 * - a suspend overload pair whose shorter sibling is a strict prefix keeps the widened parameter
 *   required-but-nullable, so `CountAsync()` is not CS0121 ([Dinnerbell.count]),
 * - ADR-164 rule 5: a default followed by a required parameter stays required-but-nullable
 *   ([Dinnerbell.ration]).
 *
 * Enum parameters were a second broken shape on these routes, carried on members of their own
 * ([Dinnerbell.beg], [Dinnerbell.meows], [begAtTheTable]) so they fail apart from the rest: an enum
 * parameter used to be a named `SKIPPED_UNSUPPORTED_INPUT` (ADR-122 Alternative 6) on every legacy
 * route, dropping the whole member from C#; ADR-164 crosses it by ordinal instead. Each crosses a
 * required enum, a defaulted enum computed in Kotlin, and an already-nullable `Hunger? = STARVING`.
 *
 * Oreo (black, white in the middle) gets fed first; Mylo (brown and creamy) waits by the bowl.
 */
package io.github.xxfast.kotlin.native.nuget.test.suppertime

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** How hungry the cat is; crosses the legacy routes by ordinal. */
enum class Hunger { PECKISH, STARVING }

/** A placemat, carried by handle on the legacy routes. */
class Placemat(val owner: String)

/**
 * The class routes. [bowl] is the per-instance state the defaults read, so a C# copy of a
 * compile-time constant cannot produce the same text.
 */
class Dinnerbell(val bowl: Int) {
  /** Bumped by [feed]'s `portion` default each time Kotlin evaluates it. */
  private var served: Int = 0

  /**
   * Class **suspend member**. One required parameter, then a widened `Int` computed from the
   * receiver, a widened `String` computed from the earlier argument, and an already-nullable
   * `Int? = 5`.
   */
  suspend fun feed(
    cat: String,
    portion: Int = bowl + ++served,
    note: String = "for $cat",
    treats: Int? = 5,
  ): String = "$cat|$portion|$note|$treats"

  /**
   * A defaulted suspend member slow enough to cancel, so the trailing `CancellationToken` is
   * shown still to be the token and not swallowed by an optional before it.
   */
  suspend fun nap(minutes: Int = bowl): String {
    delay(minutes * 1_000L)
    return "napped $minutes"
  }

  /** Class **suspend returning StateFlow** (ADR-068). */
  suspend fun plate(grams: Int = bowl * 2, label: String? = "kibble"): StateFlow<String> =
    MutableStateFlow("$grams|$label")

  /** Class **Flow member**. Emits once, so the one element carries every argument. */
  fun purrs(
    cat: String,
    loudness: Int = bowl,
    tag: String = "purr-$cat",
    repeats: Int? = 5,
  ): Flow<String> =
    flow { emit("$cat|$loudness|$tag|$repeats") }

  /**
   * Class **StateFlow member**. Pure in its arguments and [bowl]: the route re-invokes this per
   * `.Value` read.
   */
  fun mood(level: Int = bowl, word: String = "content", extra: Int? = 5): StateFlow<String> =
    MutableStateFlow("$level|$word|$extra")

  /** Class **held MutableStateFlow member**. */
  fun dish(
    grams: Int = bowl,
    flavour: String = "salmon",
    topUp: Int? = 5,
  ): MutableStateFlow<String> =
    MutableStateFlow("$grams|$flavour|$topUp")

  /**
   * A defaulted **handle** and **collection** stay required (decided scope); the trailing
   * `grams` default still widens behind them.
   */
  suspend fun share(
    mat: Placemat = Placemat("house"),
    cats: List<String> = listOf("Oreo"),
    grams: Int = bowl,
  ): String = "${mat.owner}|${cats.joinToString(",")}|$grams"

  /** The CS0121 pair, shorter half. */
  suspend fun count(): Int = -1

  /**
   * The CS0121 pair, defaulted half: `limit` stays required-but-nullable because [count]'s C#
   * signature is a strict prefix of this one's.
   */
  suspend fun count(limit: Int = 3): Int = limit * 10

  /**
   * ADR-164 rule 5 on a legacy route, and the dispatcher's `mask` local beside a user `mask`:
   * `portion` is defaulted but followed by a required parameter, so it widens without a C#
   * default and `null` means "run the Kotlin default".
   */
  suspend fun ration(portion: Int = bowl, mask: Int): String = "$portion|$mask"

  /** The user's `mask` first, so the default after it keeps its C# `= null`. */
  suspend fun sprinkle(mask: Int, pinch: Int = bowl): String = "$mask|$pinch"

  /** The dispatcher's `default_grams` local beside a user `default_grams`. */
  suspend fun pour(default_grams: Int, grams: Int = bowl): String = "$default_grams|$grams"

  /** The same `mask` collision on the legacy **Flow** body. */
  fun drip(mask: Int, drops: Int = bowl): Flow<String> = flow { emit("$mask|$drops") }

  /**
   * The widened `Plain`'s newly minted `${name}HasValue` slot, and the `Optional`'s newly minted
   * `${name}IsSet` slot, each beside a user parameter of that spelling.
   */
  suspend fun weigh(
    gramsHasValue: Boolean,
    treatsIsSet: Boolean,
    grams: Int = bowl,
    treats: Int? = 5,
  ): String = "$gramsHasValue|$treatsIsSet|$grams|$treats"

  /**
   * **Enum** parameters on the class **suspend** route: one required, one defaulted from the
   * receiver's state, one already-nullable.
   */
  suspend fun beg(
    asked: Hunger,
    hunger: Hunger = if (bowl > 30) Hunger.PECKISH else Hunger.STARVING,
    fallback: Hunger? = Hunger.STARVING,
  ): String = "$asked|$hunger|$fallback"

  /** **Enum** parameters on the class **Flow** route. */
  fun meows(
    asked: Hunger,
    hunger: Hunger = if (bowl > 30) Hunger.PECKISH else Hunger.STARVING,
  ): Flow<String> =
    flow { emit("$asked|$hunger") }
}

/** The sealed-arm route (ADR-118). */
sealed class Course {
  /** Supper, served at [warmth]. */
  class Main(val warmth: Int) : Course() {
    /** Sealed-arm **suspend member**. */
    suspend fun serve(dish: String = "tuna", minutes: Int = warmth * 2, sides: Int? = 5): String =
      "$dish|$minutes|$sides"

    /** Sealed-arm **Flow member** (ADR-124), the arm's own `flowMembers` call site. */
    fun nibbles(pace: Int = warmth, bite: String = "small", crumbs: Int? = 5): Flow<String> =
      flow { emit("$pace|$bite|$crumbs") }
  }
}

/**
 * **Top-level suspend**. The `grams` default is computed from the required `name`, so it varies
 * per call and cannot be a C# constant.
 */
suspend fun weighIn(
  name: String,
  grams: Int = name.length * 100,
  note: String = "$name on the scale",
  treats: Int? = 5,
): String = "$name|$grams|$note|$treats"

/** **Enum** parameters on the **top-level suspend** route, the default computed from `name`. */
suspend fun begAtTheTable(
  name: String,
  hunger: Hunger = if (name.length > 3) Hunger.STARVING else Hunger.PECKISH,
  fallback: Hunger? = Hunger.STARVING,
): String = "$name|$hunger|$fallback"

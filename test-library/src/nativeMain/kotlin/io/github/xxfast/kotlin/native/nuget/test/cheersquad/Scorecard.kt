package io.github.xxfast.kotlin.native.nuget.test.cheersquad

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How a judge rates the squad's spirit, a callback result crossing as its ordinal. */
enum class Spirit {
  CALM,
  HYPED,
}

/**
 * ADR-239: a callback RESULT outside `Unit`, the primitives, `String` and a class handle: an enum,
 * `Char`, `Instant`, `Duration`, `Uuid`, a `List`, a `Set`, a `Map`, and a nullable of each. Every
 * result crosses the way the same type crosses at a method return, so a C# delegate returns the
 * C# spelling (`Spirit`, `char`, `DateTimeOffset`, `TimeSpan`, `Guid`, `IReadOnlyList<string>`,
 * `IReadOnlySet<int>`, `IReadOnlyDictionary<string, int>`) and Kotlin reads the value back.
 *
 * Three callback routes per result, each a separate member so one refusal cannot hide another:
 *  - a per-call lambda parameter on an ordinary member (`spirit(pick)`);
 *  - a `suspend` lambda parameter (`spiritLater(pick)`);
 *  - a lambda-typed `var` C# sets and Kotlin then calls (`onSpirit`, read by `readSpirit()`),
 *    whose Kotlin default also crosses the result the other way when C# invokes the getter.
 *
 * Every member describes what Kotlin read as a `String`, so a delivered-but-wrong value fails the
 * C# assertion rather than passing on arrival. Mylo judges; Oreo performs.
 */
class Scorecard(val judge: String) {
  // Enum.
  fun spirit(pick: (String) -> Spirit): String = "$judge saw ${pick(judge).name}"
  fun maybeSpirit(pick: (String) -> Spirit?): String = "$judge saw ${pick(judge)?.name ?: "nothing"}"
  suspend fun spiritLater(pick: suspend (String) -> Spirit): String = "$judge saw ${pick(judge).name}"
  suspend fun maybeSpiritLater(pick: suspend (String) -> Spirit?): String =
    "$judge saw ${pick(judge)?.name ?: "nothing"}"
  var onSpirit: (String) -> Spirit = { Spirit.CALM }
  var onMaybeSpirit: (String) -> Spirit? = { null }
  fun readSpirit(): String = spirit(onSpirit)
  fun readMaybeSpirit(): String = maybeSpirit(onMaybeSpirit)

  // Char.
  fun initial(pick: (String) -> Char): String = "$judge wrote ${pick(judge)}"
  fun maybeInitial(pick: (String) -> Char?): String = "$judge wrote ${pick(judge) ?: "nothing"}"
  suspend fun initialLater(pick: suspend (String) -> Char): String = "$judge wrote ${pick(judge)}"
  suspend fun maybeInitialLater(pick: suspend (String) -> Char?): String =
    "$judge wrote ${pick(judge) ?: "nothing"}"
  var onInitial: (String) -> Char = { who -> who.first() }
  var onMaybeInitial: (String) -> Char? = { null }
  fun readInitial(): String = initial(onInitial)
  fun readMaybeInitial(): String = maybeInitial(onMaybeInitial)

  // Instant, read as epoch milliseconds.
  fun stamp(pick: (String) -> Instant): String = "$judge stamped ${pick(judge).toEpochMilliseconds()}"
  fun maybeStamp(pick: (String) -> Instant?): String =
    "$judge stamped ${pick(judge)?.toEpochMilliseconds() ?: "nothing"}"
  suspend fun stampLater(pick: suspend (String) -> Instant): String =
    "$judge stamped ${pick(judge).toEpochMilliseconds()}"
  suspend fun maybeStampLater(pick: suspend (String) -> Instant?): String =
    "$judge stamped ${pick(judge)?.toEpochMilliseconds() ?: "nothing"}"
  var onStamp: (String) -> Instant = { Instant.fromEpochMilliseconds(1_000) }
  var onMaybeStamp: (String) -> Instant? = { null }
  fun readStamp(): String = stamp(onStamp)
  fun readMaybeStamp(): String = maybeStamp(onMaybeStamp)

  // Duration, read as whole milliseconds.
  fun timing(pick: (String) -> Duration): String = "$judge timed ${pick(judge).inWholeMilliseconds} ms"
  fun maybeTiming(pick: (String) -> Duration?): String =
    "$judge timed ${pick(judge)?.inWholeMilliseconds?.let { "$it ms" } ?: "nothing"}"
  suspend fun timingLater(pick: suspend (String) -> Duration): String =
    "$judge timed ${pick(judge).inWholeMilliseconds} ms"
  suspend fun maybeTimingLater(pick: suspend (String) -> Duration?): String =
    "$judge timed ${pick(judge)?.inWholeMilliseconds?.let { "$it ms" } ?: "nothing"}"
  var onTiming: (String) -> Duration = { 250.milliseconds }
  var onMaybeTiming: (String) -> Duration? = { null }
  fun readTiming(): String = timing(onTiming)
  fun readMaybeTiming(): String = maybeTiming(onMaybeTiming)

  // Uuid.
  fun ticket(pick: (String) -> Uuid): String = "$judge issued ${pick(judge)}"
  fun maybeTicket(pick: (String) -> Uuid?): String = "$judge issued ${pick(judge) ?: "nothing"}"
  suspend fun ticketLater(pick: suspend (String) -> Uuid): String = "$judge issued ${pick(judge)}"
  suspend fun maybeTicketLater(pick: suspend (String) -> Uuid?): String =
    "$judge issued ${pick(judge) ?: "nothing"}"
  var onTicket: (String) -> Uuid = { Uuid.parse("feedface-0a1e-4c0a-b0b0-0ff1ceb0bade") }
  var onMaybeTicket: (String) -> Uuid? = { null }
  fun readTicket(): String = ticket(onTicket)
  fun readMaybeTicket(): String = maybeTicket(onMaybeTicket)

  // List.
  fun lineup(pick: (String) -> List<String>): String = "$judge called ${pick(judge).joinToString()}"
  fun maybeLineup(pick: (String) -> List<String>?): String =
    "$judge called ${pick(judge)?.joinToString() ?: "nothing"}"
  suspend fun lineupLater(pick: suspend (String) -> List<String>): String =
    "$judge called ${pick(judge).joinToString()}"
  suspend fun maybeLineupLater(pick: suspend (String) -> List<String>?): String =
    "$judge called ${pick(judge)?.joinToString() ?: "nothing"}"
  var onLineup: (String) -> List<String> = { who -> listOf(who, "Oreo") }
  var onMaybeLineup: (String) -> List<String>? = { null }
  fun readLineup(): String = lineup(onLineup)
  fun readMaybeLineup(): String = maybeLineup(onMaybeLineup)

  // Set, read sorted so the C# set's order cannot matter.
  fun numbers(pick: (String) -> Set<Int>): String =
    "$judge counted ${pick(judge).sorted().joinToString()}"
  fun maybeNumbers(pick: (String) -> Set<Int>?): String =
    "$judge counted ${pick(judge)?.sorted()?.joinToString() ?: "nothing"}"
  suspend fun numbersLater(pick: suspend (String) -> Set<Int>): String =
    "$judge counted ${pick(judge).sorted().joinToString()}"
  suspend fun maybeNumbersLater(pick: suspend (String) -> Set<Int>?): String =
    "$judge counted ${pick(judge)?.sorted()?.joinToString() ?: "nothing"}"
  var onNumbers: (String) -> Set<Int> = { setOf(3, 1) }
  var onMaybeNumbers: (String) -> Set<Int>? = { null }
  fun readNumbers(): String = numbers(onNumbers)
  fun readMaybeNumbers(): String = maybeNumbers(onMaybeNumbers)

  // Map, read sorted by key.
  fun tally(pick: (String) -> Map<String, Int>): String = "$judge tallied ${pick(judge).described()}"
  fun maybeTally(pick: (String) -> Map<String, Int>?): String =
    "$judge tallied ${pick(judge)?.described() ?: "nothing"}"
  suspend fun tallyLater(pick: suspend (String) -> Map<String, Int>): String =
    "$judge tallied ${pick(judge).described()}"
  suspend fun maybeTallyLater(pick: suspend (String) -> Map<String, Int>?): String =
    "$judge tallied ${pick(judge)?.described() ?: "nothing"}"
  var onTally: (String) -> Map<String, Int> = { who -> mapOf(who to 9) }
  var onMaybeTally: (String) -> Map<String, Int>? = { null }
  fun readTally(): String = tally(onTally)
  fun readMaybeTally(): String = maybeTally(onMaybeTally)

  // A stored add/remove pair, the hand-written callback route, with an enum result.
  private val judges: MutableList<(String) -> Spirit> = mutableListOf()

  fun addJudge(pick: (String) -> Spirit) {
    judges.add(pick)
  }

  fun removeJudge(pick: (String) -> Spirit) {
    judges.remove(pick)
  }

  /** Every stored judge's verdict on [judge], joined. */
  fun verdicts(): String = judges.joinToString { pick -> pick(judge).name }
}

private fun Map<String, Int>.described(): String =
  entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }

/**
 * ADR-239: the same result vocabulary on a per-call lambda parameter at the top level, so the
 * static owner's callback route is not assumed from the class route.
 */
fun judgeSpirit(name: String, pick: (String) -> Spirit?): String = "$name is ${pick(name)?.name ?: "unjudged"}"

fun judgeInitial(name: String, pick: (String) -> Char?): String = "$name signs ${pick(name) ?: "nothing"}"

fun judgeLineup(name: String, pick: (String) -> List<String>?): String =
  "$name leads ${pick(name)?.joinToString() ?: "nobody"}"

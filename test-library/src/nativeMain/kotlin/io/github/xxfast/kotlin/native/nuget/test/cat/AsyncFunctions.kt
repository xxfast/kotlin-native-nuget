package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

suspend fun fetchGreeting(name: String): String {
  delay(1.seconds)
  return "Hello, $name!"
}

suspend fun saveGreeting(greeting: String) {
  delay(1.seconds)
}

// Issue #108: a `suspend fun` returning a NULLABLE type. `buildSuspendFunctionBody` branches only
// on `isUnit`, so every non-Unit return hits an unguarded `StableRef.create(result)`, which does
// not compile for a nullable `result`. Each of the three below returns non-null for one cat and
// null for the other, so a missing null guard is distinguishable from a correct one.

/** Nullable STRING return: Oreo's collar is on the hook, Mylo has chewed his off again. */
suspend fun findCollarTag(catName: String): String? {
  delay(100.milliseconds)
  return if (catName == "Oreo") "Oreo - black with a white middle" else null
}

/** Nullable OBJECT return: the shelter only has a record for Mylo. */
suspend fun findShelterCat(catName: String): Cat? {
  delay(100.milliseconds)
  return if (catName == "Mylo") Cat(catName) else null
}

/** Nullable PRIMITIVE return: only Oreo's treat jar has been counted this week. */
suspend fun countTreatsLeft(catName: String): Int? {
  delay(100.milliseconds)
  return if (catName == "Oreo") 7 else null
}

// Issue #299: the top-level twin of `AsyncCatService`'s nullable parameters. The top-level suspend
// route has its own Kotlin parameter builder (`addLegacySuspendParameters`), so it needs its own
// fixtures. `false` and `null` must stay distinct, which is why the Boolean? result has three arms.

/** Nullable Boolean parameter: nobody is sure whether Mylo is an indoor cat. */
suspend fun describeIndoor(indoor: Boolean?): String {
  delay(10.milliseconds)
  return when (indoor) {
    null -> "unknown"
    true -> "indoor"
    false -> "outdoor"
  }
}

/** Nullable String parameter on the top-level route: a collar with no name tag. */
suspend fun nameTagFor(name: String?): String {
  delay(10.milliseconds)
  return name?.let { "tag: $it" } ?: "tag: blank"
}

// ROADMAP line 29 / ADR-118 amendment: two top-level `suspend` overloads in one package. The
// top-level suspend route composed `${cname}_async` with no overload suffix, so each pair below
// collided on one C symbol and failed generation with ERROR_C_ENTRY_POINT_COLLISION. The C# side
// must stay one natural `...Async` overload set; only the native symbol and the private extern
// are numbered. Every body answers a string the other overload cannot produce, because a suffix
// that reaches the `[DllImport]` EntryPoint but not the extern's call site binds the wrong body
// silently.

/** Arity pair, first overload: Oreo gets his one treat. */
suspend fun fetchTreat(): String {
  delay(1.milliseconds)
  return "one treat for Oreo"
}

/** Arity pair, second overload: `test_cat__fetchTreat_2_async`. Mylo negotiates a count. */
suspend fun fetchTreat(count: Int): String {
  delay(1.milliseconds)
  return "$count treats for Mylo"
}

/**
 * Same-wire pair, first overload. Both parameters cross as one `IntPtr` to a boxed wire container
 * (ADR-114), so both extern calls have identical argument types: the cell that pins the suffix onto
 * the extern name and not only onto the EntryPoint.
 */
suspend fun serveTreats(portions: List<Int>): String {
  delay(1.milliseconds)
  return "served ${portions.sum()} portions: ${portions.joinToString("+")}"
}

/** Same-wire pair, second overload: `test_cat__serveTreats_2_async`. By name, not by count. */
suspend fun serveTreats(names: Set<String>): String {
  delay(1.milliseconds)
  return "called ${names.sorted().joinToString(" and ")} to the bowl"
}

// Mixed namesakes: one ordinary `ping` and one `suspend ping`. ADR-095 parity (human decision
// 2026-09-26): suspend and ordinary namesakes share one declaration-order counter, so this order
// yields `test_cat__ping` then `test_cat__ping_2_async`. DECLARATION ORDER IS LOAD-BEARING:
// swapping the two would yield `ping_async` / `ping_2`, and SuspendMethodOverloadTests pins
// `ping` / `ping_2_async`.

/** Ordinary namesake: Oreo answers the ping at once. */
fun ping(): String = "Oreo pinged back"

/** Suspend namesake: Mylo takes his time, and says how many times he was pinged. */
suspend fun ping(times: Int): String {
  delay(1.milliseconds)
  return "Mylo pinged back after $times pings"
}

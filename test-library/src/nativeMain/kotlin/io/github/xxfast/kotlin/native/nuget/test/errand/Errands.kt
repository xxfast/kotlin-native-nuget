package io.github.xxfast.kotlin.native.nuget.test.errand

import dev.other.bysuspend.Chipcode
import dev.other.bysuspend.Mousetoy
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// ROADMAP Phase 4 line 23: dependency types reached ONLY through a top-level `suspend fun`.
//
// `dev.other.bysuspend` is admitted (`admit("dev.other.bysuspend")`), but ADR-066's closure walk
// is handed `functions + genericFunctions` and never the non-generic top-level `suspendFunctions`,
// so nothing here reaches it: `Mousetoy`/`Chipcode` get neither an admission nor a refusal, are
// never declared in C#, and the suspend route spells them regardless. Asserted by
// `IntegrationTests/SuspendReachabilityTests.cs`.
//
// Nothing else in the export scope may mention `Mousetoy` or `Chipcode` at a non-suspend
// position, or the closure would reach them through that member and this fixture would prove
// nothing.

/** Dependency class at a top-level suspend RETURN: Oreo's favourite mouse, fetched from under the
 *  sofa. */
suspend fun fetchMousetoy(catName: String): Mousetoy {
  delay(10.milliseconds)
  return Mousetoy(if (catName == "Oreo") "black-and-white" else "milky-brown")
}

/** Dependency class at a top-level suspend PARAMETER: the handle travels back into Kotlin. */
suspend fun squeakOf(toy: Mousetoy): String {
  delay(10.milliseconds)
  return toy.batted("Mylo")
}

/** Dependency VALUE class at a top-level suspend return: the microchip the vet scanned. */
suspend fun scanChip(catName: String): Chipcode {
  delay(10.milliseconds)
  return Chipcode("${catName.lowercase()}-985112")
}

/** Module-local value class at a top-level suspend return, non-null. */
suspend fun fetchNametag(catName: String): Nametag {
  delay(10.milliseconds)
  return Nametag("$catName, if found please return to the sofa")
}

/**
 * Module-local value class at a NULLABLE top-level suspend return: Oreo still wears his tag, Mylo
 * has lost his in the garden again. One arm of each, so a missing null guard is distinguishable
 * from a correct one.
 */
suspend fun findNametag(catName: String): Nametag? {
  delay(10.milliseconds)
  return if (catName == "Oreo") Nametag("Oreo - black with a white middle") else null
}

/**
 * The LeakTests tight-loop twin of [fetchMousetoy]: a `suspend fun` with no suspension point, so
 * the completion can fire before the P/Invoke that started it has returned. A result handle
 * retained on the wrong side of that race leaks on a fraction of calls, not on every one.
 */
suspend fun grabMousetoyNow(catName: String): Mousetoy =
  Mousetoy(if (catName == "Oreo") "black-and-white" else "milky-brown")

/**
 * The tight-loop twin of [findNametag]: no suspension point, and both arms, so the null path
 * (which must mint nothing) shares the same completion race as the boxed value `NugetUnbox` frees.
 */
suspend fun findNametagNow(catName: String): Nametag? =
  if (catName == "Oreo") Nametag("Oreo - black with a white middle") else null

/** Module-local enum for [choreFor]: the suspend route's enum arm, which crosses by ordinal. */
enum class Chore { FETCH, NAP }

/**
 * An enum at a nullable top-level suspend return, all three outcomes: Oreo fetches, Mylo naps,
 * any other cat gets `null`. The C# completion casts the boxed ordinal back to `Chore`.
 */
suspend fun choreFor(catName: String): Chore? {
  delay(10.milliseconds)
  return when (catName) {
    "Oreo" -> Chore.FETCH
    "Mylo" -> Chore.NAP
    else -> null
  }
}

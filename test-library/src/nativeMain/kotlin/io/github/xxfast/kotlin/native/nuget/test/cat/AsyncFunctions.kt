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

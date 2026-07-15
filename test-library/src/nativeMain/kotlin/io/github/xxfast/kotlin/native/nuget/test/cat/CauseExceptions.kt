package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// sync, single-level cause: Oreo had a reaction to a treat
fun feedCatWithAllergy(catName: String): String {
  if (catName == "Oreo") throw IllegalArgumentException(
    "Oreo had a reaction",
    RuntimeException("Oreo is allergic to this treat"),
  )
  return "$catName ate happily"
}

// sync, two-level deep chain: Oreo's grooming failed with a chained cause
fun groomCat(catName: String): String {
  if (catName == "Oreo") {
    val root = RuntimeException("clippers jammed")
    val mid = IllegalStateException("grooming aborted", root)
    throw IllegalArgumentException("Oreo's grooming failed", mid)
  }
  return "$catName is fluffy"
}

// suspend, single-level cause: Oreo's vet report failed to fetch
suspend fun fetchVetReport(catName: String): String {
  delay(10.milliseconds)
  if (catName == "Oreo") throw IllegalArgumentException(
    "vet report failed",
    IllegalStateException("clinic offline"),
  )
  return "$catName is healthy"
}

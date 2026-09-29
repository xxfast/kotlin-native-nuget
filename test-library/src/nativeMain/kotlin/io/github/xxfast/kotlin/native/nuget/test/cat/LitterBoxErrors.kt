package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.milliseconds

// ADR-177 (issue #349): exception mapping by class hierarchy. Every row below is thrown by Oreo,
// who treats the litter box as a personal sandpit; Mylo always takes the happy path. Not to be
// confused with the unrelated `litterbox` package (TestLibrary.Litterbox).
//
// The custom exception classes are `internal` on purpose, like `OverfedCatException` and
// `CatNapException`: they are only ever thrown, never part of the exported surface.

// A subclass of kotlinx.io.IOException: must arrive as KotlinIOException with the concrete KotlinType.
internal class LitterBoxJammedException(message: String) : IOException(message)

// A null-message subclass of a mapped stdlib type: must arrive as KotlinInvalidOperationException,
// and with no Kotlin message the C# Message names this concrete class.
internal class LitterBoxGrumble : IllegalStateException()

// Not a subclass of any row: stays the base KotlinException.
internal class ScatteredLitterException(message: String) : Exception(message)

// --- kotlinx.io.IOException → KotlinIOException : System.IO.IOException ---
fun scoop(catName: String): String {
  if (catName == "Oreo") throw IOException("the bag split")
  return "$catName's litter box is spotless"
}

// --- IOException subclass → KotlinIOException, KotlinType stays the subclass ---
fun rake(catName: String): String {
  if (catName == "Oreo") throw LitterBoxJammedException("Oreo buried the rake")
  return "$catName's litter is raked into neat rows"
}

// --- suspend route, the issue's own case: kotlinx.io.EOFException (an IOException subclass) ---
suspend fun deliverLitter(catName: String): String {
  delay(10.milliseconds)
  if (catName == "Oreo") throw EOFException("the litter truck never came, Oreo chased it off")
  return "fresh litter delivered for $catName"
}

// --- IOException as a cause: the outer node maps by its own row, the inner node to KotlinIOException ---
fun scoopAll(catName: String): String {
  if (catName == "Oreo") throw IllegalStateException("scoop failed", IOException("the bag split"))
  return "$catName's whole house is scooped"
}

// --- user subclass of IllegalStateException (null message) → KotlinInvalidOperationException ---
fun sift(catName: String): String {
  if (catName == "Oreo") throw LitterBoxGrumble()
  return "$catName sat patiently while the litter was sifted"
}

// --- kotlin.NullPointerException (null message) → KotlinNullReferenceException ---
fun ownerName(catName: String): String {
  if (catName == "Oreo") throw NullPointerException()
  return "$catName belongs to the whole household"
}

// --- CancellationException on a synchronous call → KotlinOperationCanceledException ---
// Ordered ahead of IllegalStateException (its Kotlin/Native superclass).
fun cancelBathTime(catName: String): String {
  if (catName == "Oreo") throw CancellationException("Oreo cancelled bath time by hiding under the bed")
  return "$catName had a lovely bath"
}

// --- kotlin.NoWhenBranchMatchedException (internal class, matched by name) → KotlinInvalidOperationException ---
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
fun pickLitterBrand(catName: String): String {
  if (catName == "Oreo") throw NoWhenBranchMatchedException()
  return "$catName prefers the clumping one"
}

// --- unmapped custom exception → still the base KotlinException ---
fun sweepLitter(catName: String): String {
  if (catName == "Oreo") throw ScatteredLitterException("Oreo kicked litter across the hallway")
  return "$catName's hallway is litter-free"
}

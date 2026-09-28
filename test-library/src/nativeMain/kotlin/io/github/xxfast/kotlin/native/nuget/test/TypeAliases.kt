package io.github.xxfast.kotlin.native.nuget.test

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds

typealias Score = Int
typealias CatNames = List<String>
typealias CatScores = Map<String, Int>

fun topScore(): Score = 10

fun defaultNames(): CatNames = listOf("Oreo", "Mylo")

fun defaultScores(): CatScores = mapOf("Oreo" to 10, "Mylo" to 8)

// --- ADR-018 amendment: a typealias's use-site `?` is part of the type -------------------------
//
// `PetName?` must bind exactly like `String?` on every route. The plan route (sync functions,
// properties, constructors) already honoured it; the suspend and StateFlow routes read
// nullability off `expandAliases()`, which dropped the alias reference's own `?`. Each shape
// below comes in a converted (String) and an unconverted (Int) flavour, because the String arm
// goes through a handle and the Int arm does not, and a fixture with only one would prove half.

typealias PetName = String
typealias PetAge = Int
typealias MaybePetName = String?
typealias PetNames = List<String>
typealias MaybeTally = StateFlow<Int>
typealias Box<T> = List<T>

/** Top-level suspend, nullable alias param and return (converted). */
suspend fun greetLater(name: PetName?): PetName? {
  delay(1.milliseconds)
  return name?.let { "Hello, $it" }
}

/** Top-level suspend, nullable alias param and return (unconverted). */
suspend fun ageLater(age: PetAge?): PetAge? {
  delay(1.milliseconds)
  return age?.let { it + 1 }
}

/** Top-level suspend, an alias whose RHS is already nullable. Already correct; a guard. */
suspend fun greetMaybeLater(name: MaybePetName): MaybePetName {
  delay(1.milliseconds)
  return name?.let { "Maybe, $it" }
}

/**
 * `PetNames?` expands to `List<String>?`, which the suspend route refuses (ADR-119, a nullable
 * collection return), exactly as it refuses the written-out `AssignmentSample.maybe()`. Today this
 * one member takes down the whole KSP round with ERROR_INTERNAL_GENERATOR_FAILURE.
 */
suspend fun namesLater(): PetNames? = null

/** A generic alias at a sync param and return, unconverted element. */
fun boxedTallies(tallies: Box<Int>): Box<Int> = tallies.map { it * 2 }

/** A generic alias at a sync param and return, converted element. */
fun boxedNames(names: Box<String>): Box<String> = names.map { it.uppercase() }

/** A generic alias at a suspend return. */
suspend fun boxedLater(): Box<Int> {
  delay(1.milliseconds)
  return listOf(4, 2)
}

/** Class-member twins of the suspend and StateFlow shapes. */
class AliasCat(val name: PetName?) {
  /** Plan route, already correct; pins that it stays so. */
  val nick: PetName? = name?.let { "$it-bean" }

  /** Class suspend, nullable alias return (converted). */
  suspend fun nicknameLater(): PetName? {
    delay(1.milliseconds)
    return nick
  }

  /** Class suspend, nullable alias return (unconverted). */
  suspend fun lives(): PetAge? {
    delay(1.milliseconds)
    return if (name == null) null else 9
  }

  private var _tally: MutableStateFlow<Int>? = null

  /** A nullable alias to a StateFlow: absent until [startTally]. */
  val maybeTally: MaybeTally? get() = _tally?.asStateFlow()

  /** Brings [maybeTally] into existence with [initial]. */
  fun startTally(initial: Int) {
    _tally = MutableStateFlow(initial)
  }

  /** Sets [maybeTally]'s current value, once the tally has started. */
  fun bumpTally(value: Int) {
    _tally?.value = value
  }
}

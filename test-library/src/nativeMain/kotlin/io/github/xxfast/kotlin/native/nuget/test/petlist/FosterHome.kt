package io.github.xxfast.kotlin.native.nuget.test.petlist

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Pet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

/**
 * ADR-176: an interface is a collection component. [Pet] (ADR-040, `IPet` in C#) crosses inside a
 * `List`, `Set` and `Map` (value and key) at every position: property get and set, method return,
 * method parameter, top-level function, `suspend` return and `Flow` element.
 *
 * Kotlin-backed elements are real [Cat]s, so C# must read them as fresh `Pet` backing wrappers
 * (never as `Cat`); a C#-implemented element crossing in through [roll] or [echo] must come back as
 * the consumer's own object (the ADR-084 token probe that ADR-173 made `Materialize<T>` run).
 *
 * `cat.Pet` is already return-reachable elsewhere (`strayPet()`), so its `Factories` key exists
 * whatever this file does. The four interfaces below [FosterHome] are the reachability cells: each
 * appears in exactly ONE collection position and nowhere else, so a reachability walk that misses
 * that position leaves it with no backing wrapper and no factory key, and the first Kotlin-backed
 * element throws `NotSupportedException` at runtime.
 *
 * Oreo (black, white middle) and Mylo (brown, creamy) are fostering. They are not happy about it.
 */
class FosterHome {

  private val oreo: Cat = Cat("Oreo")
  private val mylo: Cat = Cat("Mylo")

  /** Property getter: binds on main already (`isReadableComponent` admits `Interface`). */
  val residents: List<Pet> get() = listOf(oreo, mylo)

  /** Property get and set: the setter is dropped on main (`isWrappableComponent`). */
  var roster: List<Pet> = listOf(oreo)

  /** Kotlin-side observation of [roster], so a C# write is checked by Kotlin, not echoed back. */
  fun rosterNames(): String = roster.joinToString(",") { it.name }

  /** Method return. */
  fun residentsNow(): List<Pet> = listOf(oreo, mylo)

  /** Nullable element (ADR-083 null arm). */
  fun maybeResidents(): List<Pet?> = listOf(oreo, null, mylo)

  /** Parameter: Kotlin calls every element, so a C# `Dog` answers from C#. */
  fun roll(pets: List<Pet>): String = pets.joinToString(" | ") { "${it.name}: ${it.speak()}" }

  /** Parameter and return: the caller's C# object must come back as the same instance. */
  fun echo(pets: List<Pet>): List<Pet> = pets

  /** Set return. */
  fun petSet(): Set<Pet> = setOf(oreo, mylo)

  /** Map value return. */
  fun byName(): Map<String, Pet> = mapOf("oreo" to oreo, "mylo" to mylo)

  /** Map key return: reference-equality keys on the C# side. */
  fun colours(): Map<Pet, String> =
    mapOf(oreo to "black with a white middle", mylo to "brown and creamy")

  /** Map key parameter and return: the caller's C# key must come back as the same instance. */
  fun echoColours(colours: Map<Pet, String>): Map<Pet, String> = colours

  /** Nested collection. */
  fun litters(): List<List<Pet>> = listOf(listOf(oreo), listOf(mylo))

  /** Legacy suspend route. */
  suspend fun residentsLater(): List<Pet> {
    yield()
    return listOf(oreo, mylo)
  }

  /** Legacy Flow route: two emissions, so reading only the first is distinguishable. */
  fun headcounts(): Flow<List<Pet>> = flowOf(listOf(oreo), listOf(oreo, mylo))
}

// ---- Reachability cells: each interface appears in exactly one collection position. ----

/** Reachable ONLY through [LodgerBoard.lodgers], a collection property getter. */
interface Lodger {
  val name: String
}

/** Getter-only host: Oreo and Mylo are the only lodgers, and they pay in purrs. */
class LodgerBoard {
  val lodgers: List<Lodger> get() = listOf(lodger("Oreo"), lodger("Mylo"))

  private fun lodger(called: String): Lodger = object : Lodger {
    override val name: String = called
  }
}

/** Reachable ONLY through [NapRoster.sleepersLater], a legacy suspend return. */
interface Sleeper {
  val name: String
}

/** Suspend-only host. */
class NapRoster {
  suspend fun sleepersLater(): List<Sleeper> {
    yield()
    return listOf(sleeper("Mylo"), sleeper("Oreo"))
  }

  private fun sleeper(called: String): Sleeper = object : Sleeper {
    override val name: String = called
  }
}

/** Reachable ONLY through [ZoomiesFeed.chasers], a Flow element. */
interface Chaser {
  val name: String
}

/** Flow-only host. */
class ZoomiesFeed {
  fun chasers(): Flow<List<Chaser>> = flowOf(listOf(chaser("Oreo"), chaser("Mylo")))

  private fun chaser(called: String): Chaser = object : Chaser {
    override val name: String = called
  }
}

/**
 * Reachable ONLY through [ScentMap.scents], as a Map KEY (the walk must cover keys, not values).
 */
interface Scent {
  val note: String
}

/** Map-key-only host. */
class ScentMap {
  fun scents(): Map<Scent, String> = mapOf(scent("tuna") to "Oreo", scent("catnip") to "Mylo")

  private fun scent(smell: String): Scent = object : Scent {
    override val note: String = smell
  }
}

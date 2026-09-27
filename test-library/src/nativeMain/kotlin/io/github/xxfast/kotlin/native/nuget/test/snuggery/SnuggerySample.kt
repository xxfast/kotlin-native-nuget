package io.github.xxfast.kotlin.native.nuget.test.snuggery

import dev.other.core.UnexportedBlanket
import dev.other.core.UnexportedPurring

/**
 * A sealed class over a supertype gets the ADR-101 treatment on the sealed route.
 *
 * Four sealed classes, one supertype kind each, because the class translator fills a type's
 * interface list only when it has no superclass, so a sealed class over both would let one shape
 * mask the other:
 *
 * - [Swaddle] over an **unexported open class** ([UnexportedBlanket]),
 * - [Purrito] over an **unexported interface** ([UnexportedPurring]),
 * - [Ottoman] over an **exported open class** ([Pouffe]),
 * - [Slumber] over an **exported interface** ([Dreamer]).
 *
 * An unexported supertype is dropped (`SKIPPED_UNEXPORTED_SUPERTYPE`) and its public members are
 * re-homed onto the sealed base, so C# can reach them through the base and, by inheritance, on
 * every arm. An exported supertype is listed in the sealed base's C# base list, so `is`/`as` work
 * and its members are reachable through the sealed value.
 *
 * Each sealed class has two `data class` arms: one overrides the overridable member, one inherits
 * it. The factories below return the sealed base type, so every C# receiver is base-typed.
 */
sealed class Swaddle : UnexportedBlanket() {
  /** Kotlin's own read of the re-homed members, so a C# write to `warmth` is observable. */
  fun describe(): String = "$fabric@$warmth"

  /** Oreo shakes off the swaddle once per turn he takes. */
  data class Wriggling(val turns: Int) : Swaddle() {
    override fun shake(): Int = turns
  }

  /** Mylo lies still and inherits the blanket's own shake. */
  data class Still(val limbs: Int) : Swaddle()
}

/** A cat folded into a loaf and wrapped: the interface-rooted sealed base. */
sealed class Purrito : UnexportedPurring {
  /** Overrides everything overridable, including the defaulted `purr`. */
  data class Tucked(val paws: Int) : Purrito() {
    override fun knead(paws: Int): Int = paws * this.paws

    override fun purr(): String = "Oreo purrs in a loaf"

    override val whiskers: Int get() = 24
  }

  /** Implements only the abstract members, inheriting `purr` and `rumble` from the interface. */
  data class Crouched(val tail: Int) : Purrito() {
    override fun knead(paws: Int): Int = paws + tail

    override val whiskers: Int = 12
  }
}

/** An exported open class, so the sealed base over it can list it in its C# base list. */
open class Pouffe {
  val stuffing: String = "beans"

  fun plump(times: Int): Int = times * 2

  open fun sink(): Int = 1
}

/** The sealed class over an exported open class. */
sealed class Ottoman : Pouffe() {
  /** Mylo sinks as deep as the shelf he jumped from. */
  data class Tall(val shelf: Int) : Ottoman() {
    override fun sink(): Int = shelf
  }

  /** Oreo steps on it lightly and inherits the pouffe's sink. */
  data class Squat(val step: Int) : Ottoman()
}

/** An exported interface with a defaulted method and a defaulted property. */
interface Dreamer {
  fun dream(): String = "chasing the red dot"

  val snores: Int get() = 3
}

/** The sealed class over an exported interface. */
sealed class Slumber : Dreamer {
  /** Mylo dreams of Milo, one mug per hour. */
  data class Heavy(val hours: Int) : Slumber() {
    override fun dream(): String = "Mylo dreams of $hours mugs of Milo"
  }

  /** Oreo twitches and inherits the default dream and snores. */
  data class Fitful(val twitches: Int) : Slumber()
}

fun wrigglingSwaddle(turns: Int): Swaddle = Swaddle.Wriggling(turns)

fun stillSwaddle(limbs: Int): Swaddle = Swaddle.Still(limbs)

fun tuckedPurrito(paws: Int): Purrito = Purrito.Tucked(paws)

fun crouchedPurrito(tail: Int): Purrito = Purrito.Crouched(tail)

fun tallOttoman(shelf: Int): Ottoman = Ottoman.Tall(shelf)

fun squatOttoman(step: Int): Ottoman = Ottoman.Squat(step)

fun heavySlumber(hours: Int): Slumber = Slumber.Heavy(hours)

fun fitfulSlumber(twitches: Int): Slumber = Slumber.Fitful(twitches)

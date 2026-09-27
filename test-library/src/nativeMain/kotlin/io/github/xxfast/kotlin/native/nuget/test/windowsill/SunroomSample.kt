package io.github.xxfast.kotlin.native.nuget.test.windowsill

import dev.other.core.UnexportedRadiator

/**
 * A sealed arm lists its own reachable interfaces in its C# base list, so `arm is ISunseeker` holds
 * and every interface member is callable through the arm.
 *
 * The interfaces cross one mechanism each:
 * - [Basker] is the **super-interface** (ADR-167): an abstract `val` and a defaulted `fun`, so an
 *   arm listing [Sunseeker] also owes [Basker]'s members, including a default it never declares.
 * - [Sunseeker] adds a `val`, a `var` and a defaulted `fun` whose body reads the `var`, so a C#
 *   write is observed by Kotlin rather than echoed by the C# getter.
 *
 * The arms:
 * - [Sunroom.Beam] is the `ivarseal` shape: [Sunroom]'s `open val naps` and [Sunseeker]'s
 *   `var naps` are satisfied by one `override var`, so its public C# `Naps` stays get-only (CS0546)
 *   and `ISunseeker.Naps` must be implemented **explicitly** (ADR-168 on an arm). It inherits both
 *   interface defaults.
 * - [Sunroom.Shade] is the same shape but overrides [Sunseeker.stretch], so the arm's own body
 *   wins.
 * - [Sunroom.Draught] implements the **unexported** [UnexportedRadiator] only: no interface in its
 *   base list, and the radiator's members re-homed onto the arm.
 * - [Nook.Box] is an arm of an eligible **sealed interface** implementing [Sunseeker] with a plain
 *   `var` (no read-only base), the implicit public-setter contrast to [Sunroom.Beam].
 * - [Nook.Bare] implements nothing: the control that the widening lists only what the arm declares.
 *
 * Every factory returns the sealed base type, so C# gets the arm through `FromHandle`. [napReport]
 * and [warmthOf] take the interfaces as parameters, so an arm crosses back as its own handle.
 *
 * Oreo (black, white middle) takes the sunbeam; Mylo (brown and creamy) prefers the shade.
 */
interface Basker {
  val warmth: Int

  fun bask(): String = "basking at $warmth degrees"
}

/** The exported interface every listed arm implements: a `val`, a `var`, a default method. */
interface Sunseeker : Basker {
  val spot: String

  var naps: Int

  fun stretch(): String = "stretches on the $spot after $naps naps"
}

/** The sealed class whose read-only [naps] makes an arm's interface `var` an explicit setter. */
sealed class Sunroom {
  open val naps: Int = 0

  /** Kotlin's own read through the base, so a C# write via `ISunseeker` is observable. */
  fun tally(): String = "$naps naps"

  /** Oreo in the sunbeam, inheriting both interface defaults. */
  class Beam(override val spot: String) : Sunroom(), Sunseeker {
    override var naps: Int = 0

    override val warmth: Int = 30
  }

  /** Mylo in the shade, stretching his own way. */
  class Shade(override val spot: String) : Sunroom(), Sunseeker {
    override var naps: Int = 1

    override val warmth: Int = 18

    override fun stretch(): String = "Mylo stretches in the shade of the $spot"
  }

  /** The draughty corner by the radiator: an unexported interface only. */
  data class Draught(val gap: Int) : Sunroom(), UnexportedRadiator {
    override val fins: Int get() = gap * 2
  }
}

/** An eligible sealed interface, so its arms take the sealed route too. */
sealed interface Nook {
  /** The cardboard box: a plain `var`, nothing read-only above it. */
  class Box(override val spot: String) : Nook, Sunseeker {
    override var naps: Int = 0

    override val warmth: Int = 25
  }

  /** The bare floor: no interface at all. */
  data object Bare : Nook
}

fun beamSunroom(spot: String): Sunroom = Sunroom.Beam(spot)

fun shadeSunroom(spot: String): Sunroom = Sunroom.Shade(spot)

fun draughtSunroom(gap: Int): Sunroom = Sunroom.Draught(gap)

fun boxNook(spot: String): Nook = Nook.Box(spot)

fun bareNook(): Nook = Nook.Bare

/** Takes the arm back as a [Sunseeker], reading every member through Kotlin's dispatch. */
fun napReport(seeker: Sunseeker): String =
  "${seeker.spot}: ${seeker.naps} naps, ${seeker.stretch()}"

/** Takes the arm back as the super-interface. */
fun warmthOf(basker: Basker): Int = basker.warmth

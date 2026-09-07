package io.github.xxfast.kotlin.native.nuget.test.issue54

/**
 * Fixture for the **sealed interface** hole: a `sealed interface` whose subclasses are nested
 * inside it is, today, collected by the ordinary interface route and declared as a bare
 * `public interface IPulse` with no members and no subclasses. `rootClasses` filters
 * `parentDeclaration == null`, so [Pulse.Beat] and [Pulse.Flat] are never collected at all, and
 * `ForwardBridgeTypeClassifier` classifies the sealed interface as a sealed helper with no
 * generated `FromHandle` discriminator, so every member typed with it is dropped with
 * `SKIPPED_SEALED_POSITION`. The result is a C# type that exists and can never be obtained,
 * implemented or passed.
 *
 * An **eligible** sealed interface (no type parameters, every subclass a nested class or object
 * with no other superclass, no sub-interfaces) carries exactly the information ADR-009 needs, so
 * after the fix [Pulse] maps like a sealed *class*: `public abstract class Pulse` with nested
 * `sealed` subclasses and a `Pulse.FromHandle(IntPtr)` discriminator, and every position below
 * binds through it. No `IPulse` may remain anywhere in the assembly.
 *
 * Every seam the sealed base travels through, once each, because a fixture trimmed to the return
 * position would go green against a fix that only opened the return route:
 * - [Monitor.current] carries it at a **`var` property**, so the scalar handle setter runs in both
 *   directions (read a discriminated arm out, write one back in),
 * - [Monitor.history] carries it as a read-only **collection component**, `List<Pulse>`, which
 *   materialises each element through the discriminator rather than the declared type,
 * - [Monitor.latest] carries it at a **class-method return**, the ordinary member plan rather than
 *   the property plan,
 * - [Monitor.record] carries it at a **parameter**, spelled on the way in and unwrapped back to
 *   Kotlin, returning a plain `Int` so the assertion reads the Kotlin side of the wire instead of
 *   another handle,
 * - [anyPulse] is the **top-level function return** (ADR-007 puts it on the static class
 *   `SealedInterfaceSample`), the one sealed-return position this repository already exercises for
 *   sealed classes, so a regression in the fix is distinguishable from the bug it repairs.
 *
 * [Mixed] is the **ineligible control** in the same file and the same namespace: eligibility is a
 * property of the hierarchy, not of the `sealed interface` keyword, so a fix that admits every
 * sealed interface has to be visibly wrong. [Mixed.Odd] carries a second superclass ([Rhythm]),
 * which no C# nested `sealed class Odd : Mixed` declaration can express, so [Mixed] must stay
 * ineligible and [Monitor.mixed] must keep skipping with a **named** `SKIPPED_SEALED_POSITION`
 * rather than the fix quietly widening to it.
 *
 * Disjoint from its neighbours in this package: [Issue54Shape] and [NestedShape] are sealed
 * *classes* (the shape that already works), [FlatShape] carries the sibling-subclass declaration
 * position. This one changes only the base's declaration keyword, so nothing else can mask it.
 *
 * The cats keep the time, as ever. Oreo (black with the white middle) purrs a steady beat you can
 * count; Mylo (brown and creamy) goes so limp in the sun he reads flat.
 */
sealed interface Pulse {
  /** The payload arm: Oreo, purring, counted in beats per minute. */
  data class Beat(val bpm: Int) : Pulse

  /** The payload-free arm: Mylo, asleep in the sun, registering nothing at all. */
  data object Flat : Pulse
}

/**
 * The cell under test: a plain exported class carrying the sealed interface at a `var` property, a
 * read-only collection property, a method return and a method parameter, plus the ineligible
 * control [mixed].
 */
class Monitor {
  /** `var` property position: the reading on the monitor right now. Starts on Mylo. */
  var current: Pulse = Pulse.Flat

  /**
   * Collection-component position, read-only. Fixed order so C# can index it: `0` is Oreo purring
   * at 60, `1` is Mylo, flat.
   */
  val history: List<Pulse> = listOf(Pulse.Beat(60), Pulse.Flat)

  /** Class-method return position: whatever [current] holds, discriminated on the way out. */
  fun latest(): Pulse = current

  /**
   * Parameter position: the sealed base crosses back as an instance handle and is unwrapped to a
   * real Kotlin [Pulse]. Returns the rate so the assertion reads the Kotlin side of the wire; a
   * handle that arrived as a raw pointer cannot answer this.
   */
  fun record(pulse: Pulse): Int = when (pulse) {
    is Pulse.Beat -> pulse.bpm
    Pulse.Flat -> 0
  }

  /**
   * The ineligible control: [Mixed] has a subclass with another superclass, so it has no C#
   * mapping and this member must keep skipping, by name, with `SKIPPED_SEALED_POSITION`.
   */
  fun mixed(): Mixed = Mixed.Odd()
}

/**
 * Control: the sealed base at a **top-level function** return, the sealed-return position that
 * already binds for a sealed class. Always answers with the payload arm, so C# can assert the
 * discriminator lands on [Pulse.Beat] and read its payload. Oreo, purring at 72.
 */
fun anyPulse(): Pulse = Pulse.Beat(72)

/**
 * The other superclass that makes [Mixed] ineligible. An ordinary exported `open class`, so the
 * ineligibility comes from the *shape of the hierarchy* rather than from an unexported supertype,
 * which is a different skip with a different diagnostic.
 */
open class Rhythm {
  /** Present so [Rhythm] is a real exported class rather than an empty marker. */
  fun tempo(): String = "steady"
}

/**
 * The ineligible sealed interface: [Odd] extends [Rhythm] as well as implementing [Mixed], so it
 * cannot be declared as a nested `sealed class Odd : Mixed` in C#, and no discriminator can be
 * generated. Stays skipped after the fix.
 */
sealed interface Mixed {
  /** The subclass with a second superclass. Neither cat claims this one. */
  class Odd : Rhythm(), Mixed
}

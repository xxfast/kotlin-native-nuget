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

/**
 * Fixture for the **top-level arm** widening (ADR-125, issue #130): a `sealed interface` whose arms
 * are declared *beside* it rather than nested inside it. ADR-112 refused this shape with
 * `SKIPPED_INELIGIBLE_SEALED_INTERFACE` ("subclass `Ping` is declared outside the sealed
 * interface"), which is a style rule rather than a C# constraint: arm discovery is
 * `getSealedSubclasses()` on both sealed routes, and `CirSealedRenderer` already re-emits a sibling
 * arm at namespace level for sealed *classes* ([FlatShape] / [Label] / [Loaf], right here in this
 * package). Only the eligibility predicate stood in the way.
 *
 * After the widening [Transmission] binds exactly as [Pulse] does, with the arms outdented:
 * `public abstract class Transmission` plus namespace-level `public sealed class Ping :
 * Transmission` and `public sealed class Silence : Transmission`, one
 * `Transmission.FromHandle(IntPtr)` discriminator, and no `ITransmission` anywhere in the assembly.
 *
 * Deliberately **not** named `Signal`: `TestLibrary.Platform.Signal` (the `expect sealed class` in
 * the `platform` package) already owns the `signal_get_type` C entry point, and export prefixes are
 * the simple name lowercased with no package component, so a second `Signal` hierarchy would fail
 * the ADR-117 forward ABI contract the instant it became eligible.
 *
 * The declaration position is the only difference from [Pulse], so the same four seams are carried
 * once each and any regression separates cleanly from the nested case:
 * - [Radio.current] is the **`var` property**, both directions through the scalar handle setter,
 * - [Radio.history] is the read-only **collection component**, `List<Transmission>`, materialising
 *   each element through the discriminator rather than the declared type,
 * - [Radio.latest] is the **class-method return**, the ordinary member plan,
 * - [Radio.heard] is the **parameter**, unwrapped back to a real Kotlin [Transmission] and
 *   answering with a plain `Int` so the assertion reads the Kotlin side of the wire. Base-typed on
 *   purpose: a concrete-arm parameter is issue #126's cell, and the two stay independent.
 *
 * [Packet] is the cell the issue actually cares about, the **cascade**. `planOrSkip` refuses a
 * whole callable when any input type is ineligible, and a `data class`'s `copy` is planned from the
 * same primary-constructor parameters, so one unbindable interface takes out `Packet`'s constructor
 * and its `Copy` as well as the property. Widening removes the cause; both must come back.
 *
 * [Ping] carries one converted member (`label: String`) and one unconverted member (`ms: Int`) on a
 * single arm, so a fix that opens the arm but not its payload conversion is visible.
 *
 * [Tone] is the **new ineligible control**, and it is ineligible for a reason C# can name rather
 * than a style rule: its arm [Pitch] is an `enum class`, and a C# enum admits only an integral base
 * (`error CS1008`), so there is no shape for `enum Pitch : Tone`. It sits in this same namespace as
 * a **declared top-level enum** on purpose. `rootEnums` carries no `!isSealedSubclass()` filter, so
 * an implementation that drops the nesting check without refusing enum arms declares both
 * `public enum Pitch` and `public sealed class Pitch : Tone` here and every consumer fails CS0101.
 * The trap is live, and it fails loudly at `packNuget` rather than silently.
 *
 * The cats work the radio. Oreo (black with the white middle) checks in with a short chirp you can
 * time; Mylo (brown and creamy) is asleep and transmits nothing, which is still a reading.
 */
sealed interface Transmission

/**
 * The payload arm, declared **top level** rather than nested: Oreo checking in, [ms] since the last
 * chirp and a [label] naming who it was. Two payload kinds on one arm, one converted and one not.
 */
data class Ping(val ms: Int, val label: String) : Transmission

/** The payload-free arm, also top level: Mylo, asleep, transmitting nothing at all. */
data object Silence : Transmission

/**
 * The cell under test: the same four positions [Monitor] carries for the nested [Pulse], carried
 * here for the sibling-armed [Transmission].
 */
class Radio {
  /** `var` property position: what the radio is hearing right now. Starts on Mylo. */
  var current: Transmission = Silence

  /**
   * Collection-component position, read-only. Fixed order so C# can index it: `0` is Oreo chirping
   * at 60ms, `1` is Mylo, silent.
   */
  val history: List<Transmission> = listOf(Ping(60, "oreo"), Silence)

  /** Class-method return position: whatever [current] holds, discriminated on the way out. */
  fun latest(): Transmission = current

  /**
   * Parameter position: the sealed base crosses back as an instance handle and is unwrapped to a
   * real Kotlin [Transmission]. Returns the interval so the assertion reads the Kotlin side of the
   * wire; a handle that arrived as a raw pointer cannot answer this.
   */
  fun heard(signal: Transmission): Int = when (signal) {
    is Ping -> signal.ms
    Silence -> 0
  }
}

/**
 * The cascade cell: a `data class` that merely *holds* the sealed interface. While [Transmission]
 * is ineligible this loses its constructor, its `copy` and its property together, which is the
 * twenty missing members issue #130 reports from one refused interface. After ADR-125 all three
 * bind: `public Packet(Transmission signal)`, `Signal { get; }` and `Copy(Transmission signal)`.
 */
data class Packet(val signal: Transmission)

/**
 * The new ineligible control: a sealed interface whose only arm is an `enum class`. Stays
 * ineligible after ADR-125, with a diagnostic naming the C# reason (a C# enum admits only an
 * integral base, `CS1008`) instead of the old style rule. Keeps binding as `ITone`, exactly as
 * [Mixed] does.
 */
sealed interface Tone

/**
 * The enum arm, declared **top level** in the same namespace so the CS0101 double-declaration trap
 * is live: this must appear exactly once, as `public enum Pitch`, and never also as a sealed arm
 * class. Oreo's chirp is [HIGH]; the rumble Mylo makes when moved is [LOW].
 */
enum class Pitch : Tone { HIGH, LOW }

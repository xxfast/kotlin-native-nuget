package io.github.xxfast.kotlin.native.nuget.test.metronome

/**
 * ADR-036 · a **primitive payload** on the per-call lambda parameter route (`(Int) -> Unit`).
 *
 * ADR-036's marshalling table says a primitive crosses by value in both directions, and the
 * interface-bridge route implements exactly that. The per-call lambda route never did: it boxes the
 * payload into a `StableRef` on the Kotlin side and renders `Int arg0 = NugetMarshal.FromHandle<Int>
 * (arg0Ptr)` against a `NugetIntVoidCallback(int, IntPtr)` delegate on the C# side, which does not
 * compile.
 *
 * Its own package, since the payload type is what is under test and an existing package would drag
 * unrelated callback delegates into the same first-registration-wins name pool.
 *
 * One cell per primitive family the by-value branch has to cover, because a fixture trimmed to
 * `Int` alone would go green against a fix that special-cased the one width the delegate suffix
 * already spells:
 *
 *  - [onTick] is the **word-sized integer** cell (`Int`), the payload the delegate name
 *    `NugetIntVoidCallback` is already derived from and the one that collides with the stored
 *    enum-ordinal route's registration of the same name,
 *  - [onBeat] is the **`Boolean`** cell, the only primitive that is not itself the wire type: it
 *    crosses as a `Byte` and needs a widening on the C# side, so it is the cell that catches a fix
 *    that forwards the argument verbatim,
 *  - [onVelocity] is the **signed 8-bit** cell (`Byte`), which shares `Boolean`'s one-byte wire but
 *    not its C# type (`sbyte` against `byte`). It is declared directly after [onBeat]: the
 *    `Boolean` payload binds as `NugetBoolVoidCallback` and the `Byte` payload as
 *    `NugetByteVoidCallback`, so the fixture pins the signed 8-bit family apart from `Boolean`'s.
 *    Its payload goes negative on purpose: read as unsigned, `-100` arrives as `156`, so the cell
 *    catches a sign misread as well as a name mix-up,
 *  - [onTempo] is the **floating point** cell (`Double`), which crosses in a different register
 *    class from the integers, so it is the cell that catches a by-value fix that only ever passes
 *    integer-shaped payloads.
 *
 * Every listener is invoked more than once with a changing payload: a single invocation would pass
 * against a thunk that delivered a constant, and the tests assert the exact sequence.
 *
 * Oreo is the tick-counter of this house. Mylo just follows the tempo.
 */
class Metronome(private val beats: Int) {
  /** Invokes [listener] with `1..beats`, in order. */
  fun onTick(listener: (Int) -> Unit) = repeat(beats) { listener(it + 1) }

  /** Invokes [listener] once per beat, `true` on the downbeats (even indices). */
  fun onBeat(listener: (Boolean) -> Unit) = repeat(beats) { listener(it % 2 == 0) }

  /**
   * Invokes [listener] once per beat with a velocity that climbs from `-100` in steps of `40`.
   *
   * Oreo lands his first pounce well below the line; Mylo's only ever creeps up from there.
   */
  fun onVelocity(listener: (Byte) -> Unit) = repeat(beats) { listener((it * 40 - 100).toByte()) }

  /** Invokes [listener] once per beat with a tempo that climbs by a half step each time. */
  fun onTempo(listener: (Double) -> Unit) = repeat(beats) { listener(60.0 + it * 0.5) }
}

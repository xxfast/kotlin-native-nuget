package io.github.xxfast.kotlin.native.nuget.test.metronome

/**
 * ADR-160, the two payload families on the per-call lambda-parameter plan that [Metronome] does
 * not carry: an **unsigned primitive** and a **Kotlin interface**.
 *
 *  - The unsigned cells hand over values ABOVE the signed range of their width (`4_000_000_000u`
 *    for `UInt`, `ULong.MAX_VALUE - 1` for `ULong`, the maxima of `UByte` and `UShort`). A value
 *    that fits the signed range round-trips through a sign misread unharmed, so only these catch a
 *    wire that reads `uint` as `int` somewhere along the way.
 *  - The interface cell is [Drummer], which appears in NO other position in this module: not a
 *    return, not a parameter, not a property, not a collection component. So the only thing that
 *    can give C# the backing wrapper and the `Factories` key `FromHandle<IDrummer>` materialises
 *    through is the reachability walk looking inside a callback payload.
 *
 * Its own file, not `Metronome.kt`, so the payload cells stay apart from the return-axis cells.
 *
 * Oreo keeps the beat on the big drum; Mylo only ever gets the triangle.
 */
class Bandstand {
  /** Invokes [listener] once per drummer, Kotlin-backed and not exported on its own. */
  fun eachDrummer(listener: (Drummer) -> Unit) {
    listener(Player("Oreo", "drum"))
    listener(Player("Mylo", "triangle"))
  }

  /** Invokes [listener] with a `UInt` above `Int.MAX_VALUE`. */
  fun eachCount(listener: (UInt) -> Unit) = listener(4_000_000_000u)

  /** Hands a `ULong` above `Long.MAX_VALUE` to [weigh] and adds one to what comes back. */
  fun sumBig(weigh: (ULong) -> ULong): ULong = weigh(ULong.MAX_VALUE - 1uL) + 1uL

  /** Invokes [listener] with `UByte.MAX_VALUE`, above `Byte.MAX_VALUE`. */
  fun eachByte(listener: (UByte) -> Unit) = listener(UByte.MAX_VALUE)

  /** Invokes [listener] with `UShort.MAX_VALUE`, above `Short.MAX_VALUE`. */
  fun eachShort(listener: (UShort) -> Unit) = listener(UShort.MAX_VALUE)
}

/** Someone on the bandstand. Reachable from C# only as [Bandstand.eachDrummer]'s payload. */
interface Drummer {
  val name: String
  val instrument: String
}

/** The one Kotlin-side [Drummer]. Private, so no exported class drags [Drummer] in. */
private class Player(override val name: String, override val instrument: String) : Drummer

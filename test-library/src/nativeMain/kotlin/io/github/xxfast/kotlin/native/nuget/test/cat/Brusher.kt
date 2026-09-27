package io.github.xxfast.kotlin.native.nuget.test.cat

// ROADMAP Phase 4, "The interface route (`interfaceEntries`) has no overload numbering at all".
// ADR-090's numbering, applied to an exported INTERFACE instead of an ordinary class. Kept off
// `Pet.kt` on purpose: `Pet` has many Kotlin and C# implementers, and every one of them would owe
// each new overload.
//
// The interface is REACHABLE both ways, which is what separates this from the shape that already
// works on the implementing class alone (an interface that is only implemented never reaches the
// name-keyed interface plan lookup):
//
//   - returned from [houseBrusher] (ADR-040), so C# calls every overload through the numbered
//     `brusher_*` interface-dispatch exports and the backing `Brusher` wrapper,
//   - accepted by [GroomingSalon]'s methods (ADR-084), so a C#-implemented `IBrusher` crosses the
//     bridge factory and every Kotlin overload call has to land on its own C# slot.
//
// It crosses every mechanism the numbering has to get right, not the fewest types:
//
//   - a zero-arity / `Int` pair (`brush()` / `brush(strokes)`): different arity, no conversion,
//   - an `Int` / enum pair (`brush(strokes)` / `brush(mood)`): the SAME `int` wire, so the private
//     extern name and the bridge slot are the only things telling them apart, and the enum side
//     needs the ordinal conversion the `Int` side does not. The salon passes `2` and `GRUMPY`
//     (ordinal 2) so a crossed wire cannot hide behind a different number,
//   - three `brush` overloads, so the `_3` suffix is exercised and not only `_2`,
//   - an ADR-164 defaulted pair (`trim(claws = 4)` / `trim(paw, claws = 1)`), where each overload
//     carries its own `HasValue` mask dispatch under its own numbered export.
//
// Every body returns a distinct string that embeds its argument, so an export or slot wired to the
// wrong overload shows up as a visibly wrong value rather than a plausible one.
interface Brusher {
  /** First declared `brush`: no parameters. */
  fun brush(): String

  /** Second declared `brush`: an `Int`, crossing as `int` with no conversion. */
  fun brush(strokes: Int): String

  /** Third declared `brush`: an enum on the same `int` wire as [brush]`(strokes)`. */
  fun brush(mood: Mood): String

  /** First declared `trim`: one defaulted parameter, so C# may omit it. */
  fun trim(claws: Int = 4): String

  /** Second declared `trim`: a required parameter, then a defaulted one with another default. */
  fun trim(paw: String, claws: Int = 1): String
}

/**
 * Oreo's house brush. An anonymous object has no generated C# class of its own, so C# can reach it
 * only through the `IBrusher` backing wrapper and the numbered `brusher_*` dispatch exports.
 */
fun houseBrusher(): Brusher = object : Brusher {
  override fun brush(): String = "Oreo is brushed"
  override fun brush(strokes: Int): String = "Oreo is brushed $strokes times"
  override fun brush(mood: Mood): String = "Oreo is brushed while ${mood.name.lowercase()}"
  override fun trim(claws: Int): String = "Oreo has $claws claws trimmed"
  override fun trim(paw: String, claws: Int): String =
    "Oreo has $claws claws trimmed on the $paw paw"
}

/**
 * An exported class implementing [Brusher]. Its own methods bind through ADR-090's class route;
 * passed into [GroomingSalon] it crosses as a Kotlin handle (unwrapped, not bridged).
 */
class SlickerBrush(val name: String) : Brusher {
  override fun brush(): String = "$name is slicked"
  override fun brush(strokes: Int): String = "$name is slicked $strokes times"
  override fun brush(mood: Mood): String = "$name is slicked while ${mood.name.lowercase()}"
  override fun trim(claws: Int): String = "$name has $claws claws clipped"
  override fun trim(paw: String, claws: Int): String =
    "$name has $claws claws clipped on the $paw paw"
}

/**
 * Takes a [Brusher] as a class-method parameter (ADR-084), and calls every overload on it from
 * Kotlin. With a C#-implemented brusher each call has to dispatch to the matching C# member.
 */
class GroomingSalon {
  /** Every `brush` overload, in declaration order. `2` and `Mood.GRUMPY` share the wire value 2. */
  fun brushAll(brusher: Brusher): String =
    listOf(brusher.brush(), brusher.brush(2), brusher.brush(Mood.GRUMPY)).joinToString(" / ")

  /** Every `trim` overload: both defaults taken by Kotlin, then one explicit. */
  fun trimAll(brusher: Brusher): String =
    listOf(brusher.trim(), brusher.trim("front-left"), brusher.trim("back-right", 2))
      .joinToString(" / ")
}

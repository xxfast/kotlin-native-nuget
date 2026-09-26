package io.github.xxfast.kotlin.native.nuget.test.objectprops

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Mood
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ROADMAP Phase 4: a Kotlin `object`'s own properties must reach C# as STATIC properties on the
 * generated static class (`TreatPantry.Count`), with a setter for a `var`, at the same type
 * coverage a class or companion property already has.
 *
 * The pantry is where Oreo and Mylo's treats live. There is exactly one of it in the house, which
 * is the whole point of an `object`: the C# side sees process-global state, so every consumer test
 * that writes to it puts the original value back.
 *
 * Each property crosses one seam once, and the object declares NO methods on purpose: `val x`
 * beside `fun x()` on one object is the fatal `ERROR_CSHARP_NAME_COLLISION` shape, which is pinned
 * in Tier 1, not here (it would break this build).
 */
object TreatPantry : Stockroom("kitchen"), Labelled {
  /** A `const val`. Expected to render as a C# `const`, the way a companion's `const val` does. */
  const val CAPACITY: Int = 12

  /**
   * `override val` on an object that implements an interface: declared in the object body, so it
   * is on the ordinary declared-only route.
   */
  override val label: String = "treats"

  /** An `Int` var: the get / set / get round trip. */
  var count: Int = 4

  /** A nullable primitive var: the value branch and the null branch of the ADR-076 fan-out. */
  var portion: Int? = null

  /** An enum-typed var, crossing the ABI as its ordinal (ADR-006). */
  var mood: Mood = Mood.SLEEPY

  /**
   * A collection-typed val: one type that needs conversion, mirroring a class collection
   * property.
   */
  val flavours: List<String> get() = listOf("tuna", "salmon")

  /**
   * A handle-typed val returning an exported class: every read mints a StableRef the wrapper
   * owns.
   */
  val favourite: Cat = Cat("Oreo", 9)

  /**
   * No adapter exists for a state flow on a static owner, so this is expected to be a NAMED skip:
   * a diagnostic and no C# member at all.
   */
  val level: StateFlow<Int> = MutableStateFlow(0)

  /** Read before assignment, this must surface in C# as an exception rather than a crash. */
  lateinit var keeper: String
}

/**
 * A second object in the SAME FILE declaring a `const val` with the SAME NAME and a DIFFERENT
 * value from [TreatPantry.CAPACITY]. The const literal lookup matches the first `const val
 * CAPACITY` in the source file, so without an anchor this one silently renders 12. Mylo's spare
 * pantry holds three treats, not twelve.
 */
object SparePantry {
  const val CAPACITY: Int = 3
}

/**
 * ROADMAP line 25: a ONE-LINE object body holding a semicolon-separated pair. The source-text
 * regex captures to end of line, so today the first value swallows the second declaration and
 * the closing brace, and the second value swallows the closing brace: both illegal C#. `TOP` ends
 * in a string then a semicolon, `BOTTOM` ends in an int then a brace, and `BOTTOM` has no type
 * annotation. The top shelf is Oreo's tuna; the bottom shelf holds two treats for Mylo.
 */
object PantryShelf { const val TOP: String = "tuna"; const val BOTTOM = 2 }

/**
 * ROADMAP line 25, the companion twin: a one-line `companion object` body inside a one-line class
 * body, so today the value captures BOTH closing braces. The name is already PascalCase in Kotlin
 * and stays `DefaultName` (ADR-006, issue #285).
 */
class PantryJar { companion object { const val DefaultName = "Oreo's jar" } }

/** ROADMAP line 25: a trailing comment and expressions over other consts, in an object body. */
object PantryTally {
  /** Today the generated terminating semicolon lands INSIDE the trailing comment. */
  const val TRAILING: Int = 5 // Mylo's five favourite treats

  /** An expression over a sibling const: evaluated to `6`, not the expression text. */
  const val REF: Int = TRAILING + 1

  /** An expression over ANOTHER owner's const: evaluated to `24`, not the expression text. */
  const val DOUBLED: Int = TreatPantry.CAPACITY * 2
}

/** The interface [TreatPantry] implements, so that its `label` is an `override val`. */
interface Labelled {
  val label: String
}

/**
 * The open base [TreatPantry] extends: `origin` and [restock] are INHERITED, not declared on the
 * object. A C# static class cannot extend anything, so both have to be bound as statics on
 * `TreatPantry` itself or neither is reachable at all.
 */
open class Stockroom(val origin: String) {
  /** An inherited METHOD: the method-route twin of the inherited `origin` property. */
  fun restock(): Int = origin.length
}

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

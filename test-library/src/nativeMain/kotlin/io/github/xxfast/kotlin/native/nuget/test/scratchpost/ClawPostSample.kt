package io.github.xxfast.kotlin.native.nuget.test.scratchpost

// ADR-075 amendment fixture ("a `var` with a narrower-than-public setter binds get-only").
// A setter that is not public Kotlin API is not C# API either: each property here reads from C#
// and has no set accessor, and no `_set_` export is generated for it on either side. The
// private-set class shape is `issue297.Button.clicks`; this package adds the other two
// narrowings, one setter mechanism each, plus the widened override:
//
//   - [ClawPost]    : `open var` + `protected set` on an open class (Direct `Int` setter shape).
//   - [ClawTower]   : overrides it with `public set`. The base binds get-only, so a C# override
//                     cannot add a set accessor (CS0546); the override must drop its setter too.
//   - [FeedingBowl] : `internal set` on a nullable primitive (the ADR-076 NullableDispatch pair,
//                     two setter exports). Internal is not public API, even module-locally.

/** Oreo's scratching post. Only the post (or a subclass) may change the tally. */
open class ClawPost(val owner: String) {
  /** How many times the post has been scratched. Protected to write. */
  open var scratches: Int = 0
    protected set

  /** One scratch from [owner]; returns the new tally. */
  fun scratch(): Int {
    scratches += 1
    return scratches
  }
}

/** Mylo's tall post: it arrives pre-scratched and widens the setter back to public in Kotlin. */
class ClawTower(owner: String) : ClawPost(owner) {
  /** Starts at 10, so a C# read shows this override dispatched, not the base field. */
  override var scratches: Int = 10
    public set
}

/** A cat's bowl. The last meal is recorded by the bowl itself, never assigned from outside. */
class FeedingBowl(val cat: String) {
  /** Grams served at the last meal, or `null` before the first one. Internal to write. */
  var lastMealGrams: Int? = null
    internal set

  /** Serves [grams] of kibble to [cat] and records it as the last meal. */
  fun serve(grams: Int): String {
    lastMealGrams = grams
    return "$cat ate ${grams}g"
  }
}

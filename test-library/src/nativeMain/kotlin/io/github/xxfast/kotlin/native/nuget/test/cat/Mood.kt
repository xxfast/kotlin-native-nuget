package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * The cat's mood, and the ADR-006 enum-member property fixture.
 *
 * [description] is the original single-lowercase-word property. The other three are camelCase, one
 * per declaration shape that used to abort generation. The C# entry point lowercased the Kotlin
 * name (`mood_get_issleepy`) while the Kotlin export kept it verbatim (`mood_get_isSleepy`):
 * - [displayName]: a constructor `val`, camelCase and NOT `is`-prefixed or `Boolean`, which is what
 *   shows the defect was never about `is`,
 * - [isCuddly]: a constructor `val` of type `Boolean`,
 * - [isSleepy]: a body getter of type `Boolean`.
 *
 * Both `Boolean` properties answer `false` for at least one entry, and in different patterns, so a
 * C# read that ignores the 1-byte `bool` return (no `[return: MarshalAs(UnmanagedType.I1)]`), or
 * that binds one getter's entry point to the other, cannot pass by accident.
 *
 * Oreo (black, white in the middle) is the grumpy one; Mylo (brown and creamy) is always napping.
 */
enum class Mood(val displayName: String, val isCuddly: Boolean) {
  HAPPY("Purring Oreo", true),
  SLEEPY("Snoozing Mylo", true),
  GRUMPY("Hissing Oreo", false);

  val description: String
    get() = when (this) {
      HAPPY -> "The cat is happy and content."
      SLEEPY -> "The cat is sleepy and ready for a nap."
      GRUMPY -> "The cat is grumpy and doesn't want to be disturbed."
    }

  val isSleepy: Boolean
    get() = this == SLEEPY

  /**
   * A throwing enum member getter: grumpy Oreo refuses to count his lives. Every other entry
   * answers `9`. The C# read must surface as a catchable mapped `KotlinInvalidOperationException`,
   * not abort the host process, and the route must still answer after the throw.
   */
  val nineLives: Int
    get() = if (this == GRUMPY) throw IllegalStateException("Oreo refuses to count") else 9

  /**
   * An enum member `var` of a type that needs conversion (`String`), stored on the entry
   * singleton's own backing field so a C# `SetNickname` followed by `Nickname()` round-trips
   * observably.
   */
  var nickname: String = displayName

  /**
   * A class-typed enum member: every read mints a fresh [Toy] handle the C# caller owns and must
   * dispose. The old enum route exported the raw Kotlin object as an `IntPtr`; the plan hands back
   * the owned `Toy` wrapper (pinned by `LeakTests`).
   */
  val favouriteToy: Toy
    get() = Toy(displayName, if (isCuddly) "cream" else "black")

  /**
   * An enum member `var` of a type that needs no conversion (`Int`), whose setter throws for a
   * negative count: Mylo cannot un-eat a treat. The rejected write must surface as a catchable
   * `KotlinArgumentException` and leave the stored value untouched.
   */
  var treatsEaten: Int = 0
    set(value) {
      require(value >= 0) { "Mylo cannot un-eat a treat" }
      field = value
    }

  // ---- ADR-006 amendment: enum member functions -----------------------------------------------
  // Each binds as `public static R Name(this Mood mood, ...)` in `MoodExtensions`. Names are chosen
  // to collide with no property above, no `Set<Prop>` setter, and no `Mood` extension function
  // elsewhere in the package (`rallyCry`).

  /** A no-argument, no-conversion member (1-byte `bool` return): only grumpy Oreo wakes Mylo. */
  fun isLoudNow(): Boolean = this == GRUMPY

  /** A member taking a `String` (needs conversion): greets [name] in this mood's voice. */
  fun greet(name: String): String = "$displayName greets $name"

  /** The overload partner, adding a plain `Int` parameter: greets [name] [times] times. */
  fun greet(name: String, times: Int): String = List(times) { greet(name) }.joinToString(" / ")

  /** A member taking another enum: two cats in the same mood share a sunbeam. */
  fun sharesSunbeamWith(other: Mood): Boolean = this == other

  /** A throwing member: grumpy Oreo refuses to be petted. Every other entry purrs once. */
  fun pet(): Int = if (this == GRUMPY) throw IllegalStateException("Oreo hisses") else 1

  /** A class-typed return: every call mints a fresh [Toy] the C# caller owns and must dispose. */
  fun toyFor(): Toy = Toy("$displayName's mouse", if (isCuddly) "cream" else "black")

  /** A nullable class-typed return: only a cuddly mood fetches a toy in [colour]. */
  fun fetchToy(colour: String): Toy? =
    if (isCuddly) Toy("$displayName's $colour yarn", colour) else null

  /** A nullable `String` return: Mylo only dreams when he is asleep. */
  fun dream(): String? = if (this == SLEEPY) "Mylo dreams of cream" else null

  companion object {
    /** A companion function: binds as the plain static `MoodExtensions.Fallback()`. */
    fun fallback(): Mood = SLEEPY

    /**
     * A companion function with a `String` parameter and a nullable enum return. Keyed on the
     * immutable [displayName], never on [nickname] (which other tests write).
     */
    fun byDisplayName(name: String): Mood? = entries.firstOrNull { it.displayName == name }

    /** A companion `val`: folds in as the static property `MoodExtensions.HouseFavourite`. */
    val houseFavourite: Mood = HAPPY

    /** A companion `var`: the static property `MoodExtensions.LastSeen`, read and written. */
    var lastSeen: Mood = SLEEPY
  }
}

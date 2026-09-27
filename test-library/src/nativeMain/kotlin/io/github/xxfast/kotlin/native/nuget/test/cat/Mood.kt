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
}

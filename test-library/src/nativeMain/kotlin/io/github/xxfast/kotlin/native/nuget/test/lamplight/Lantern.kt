/**
 * Fixture for an extension FUNCTION beside an applicable member of the same name. The extensions
 * live in `lamplight.ext` (`LanternExtensions.kt`); a member and an extension in ONE package would
 * claim the same C entry point. Every callable names which side ran, so the C# test reads back
 * "member" or "extension" and a call reaching the wrong one is the wrong text.
 */
package io.github.xxfast.kotlin.native.nuget.test.lamplight

/** Oreo's night light. */
class Lantern {
  /** The exact shape: `fun Lantern.glow()` has the same signature. */
  fun glow(): String = "member"

  /** The defaulted shape: `dim()` with no argument is applicable to this member too. */
  fun dim(level: Int = 0): String = "member:$level"

  /** The control: not applicable to a `Long`, so `flicker(5L)` already reaches the extension. */
  fun flicker(times: Int): String = "member:$times"
}

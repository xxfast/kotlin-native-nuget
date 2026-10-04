/**
 * Fixture for an extension FUNCTION beside an applicable member of the same name. Most extensions
 * live in `lamplight.ext` (`LanternExtensions.kt`); `shine`, `swing`, `addFlare` and `wick` below
 * are declared in this package, beside the same-named member, so the extension takes the marked
 * `lantern_ext_` entry point instead of the member's. Every callable names which side ran, so the
 * C# test reads back "member" or "extension" and a call reaching the wrong one is the wrong text.
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

  /** Shadowed exactly by the same-package `fun Lantern.shine()` below. */
  fun shine(): String = "member"

  /** Shadowed exactly by the same-package `fun Lantern.swing(arc: Int)` below. */
  fun swing(arc: Int): String = "member:$arc"

  /** Beside the same-package `var Lantern?.wick` below, which is not shadowed by it. */
  var wick: Int = 1

  /** A stored-callback pair; the same-package `fun Lantern.addFlare()` below shares its name. */
  fun addFlare(onFlare: (Int) -> Unit) {
    flares += onFlare
  }

  /** The other half of the `addFlare` pair. */
  fun removeFlare(onFlare: (Int) -> Unit) {
    flares -= onFlare
  }

  /** Fires every registered flare listener with [brightness]. */
  fun flare(brightness: Int) {
    flares.forEach { listener -> listener(brightness) }
  }

  private val flares: MutableList<(Int) -> Unit> = mutableListOf()
}

/** Same package as the member `shine()`, so both would derive `lantern_shine`. */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun Lantern.shine(): String = "extension"

/** Same package and signature as the member `swing(arc: Int)`. */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun Lantern.swing(arc: Int): String = "extension:$arc"

/** The extension overload no member takes: it keeps its unmarked `lantern_swing_2`. */
fun Lantern.swing(arc: Long): String = "extension:long:$arc"

/** Same package as the stored-callback member `addFlare`: both would derive `lantern_addFlare`. */
fun Lantern.addFlare(): String = "extension"

/**
 * A nullable receiver is never shadowed by the member `wick` (Kotlin's `receiver.wick` on a
 * `Lantern?` has no member candidate), so this binds beside it under `lantern_ext_get_wick` /
 * `lantern_ext_set_wick`. Reads 10 times the member's value, so a read reaching the member reads
 * the wrong number; the setter writes the member scaled back down.
 */
var Lantern?.wick: Int
  get() = (this?.wick ?: 0) * 10
  set(value) {
    this?.wick = value / 10
  }

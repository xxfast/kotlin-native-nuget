package io.github.xxfast.kotlin.native.nuget.test.skipremarks

/**
 * The all-dropped FILE, issue #249's own headline example (a lone same-named factory whose whole
 * file goes away): every top-level declaration here is skipped, so the ADR-007 file-named static
 * class `CatFlap` has an empty merged member set and the 2026-09-07 "an absent declaration leaves
 * no husk" amendment elides it -- taking the only place the skip could have been reported with
 * it.
 *
 * This item amends that elision for exactly this case: a husk with something to SAY is no longer
 * indistinguishable from "members still to come", because it says why it is empty. So
 * `TestLibrary.Skipremarks.CatFlap` must exist, with no members and one `<remarks>` naming
 * [latchFlap]. A file with nothing declared and nothing dropped still renders no holder, and the
 * `husk`/`chaff` fixtures next door keep pinning that half.
 *
 * Its namespace is held up regardless by `ClawStrip.kt`, so this cell is about the static class
 * alone, never about a namespace drop.
 *
 * Oreo has never once used the cat flap. He waits at the door and yells.
 */

/**
 * The only declaration in the file, dropped by name: `List<List<String>?>` is ADR-099's nullable
 * nested component (`SKIPPED_UNSUPPORTED_INPUT`), the same stably-unsupported shape
 * `husk/HuskOnly.scan` uses.
 */
fun latchFlap(litters: List<List<String>?>): Int = litters.size

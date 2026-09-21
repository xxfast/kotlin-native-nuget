package io.github.xxfast.kotlin.native.nuget.test.metronome

/**
 * ADR-160, the **position** cells of the per-call lambda-parameter route: the same
 * `(cb: (Int) -> Unit): Int` shape at a top-level function and at an extension function.
 *
 * Neither position has an emitter on the legacy route (the diagnostic says so in as many words: "a
 * lambda binds at a class-method parameter and a top-level function return, but not at this
 * position"), which is why the end state is the ADR-062 plan rather than a second hand-written
 * return matrix.
 *
 * Its own file, not `Metronome.kt`: ADR-007 puts a top-level function in a static class named after
 * its source file, and `Metronome` is already the class. So both cells land on
 * `TestLibrary.Metronome.Tallies`.
 *
 * Mylo counts from the windowsill; Oreo only counts every other beat.
 */

/** Top-level position. Fixed beat count, so the cell carries no second (mixed-parameter) seam. */
fun tallyTicks(listener: (Int) -> Unit): Int {
  repeat(3) { listener(it + 1) }
  return 3
}

/**
 * Extension position, on [Metronome]. Drives its own loop off [Metronome.beatCount] rather than
 * calling another callback member, so the cell does not nest one crossing inside another.
 */
fun Metronome.everyOtherTick(listener: (Int) -> Unit): Int {
  var fired = 0
  (1..beatCount).forEach { beat ->
    if (beat % 2 == 0) {
      listener(beat)
      fired++
    }
  }
  return fired
}

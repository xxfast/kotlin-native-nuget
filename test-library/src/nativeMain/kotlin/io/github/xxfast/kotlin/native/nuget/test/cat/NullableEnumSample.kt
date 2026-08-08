package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * ADR-080: a **bare** `Nullable(Enum)` (`Mood?`, no value-class wrapper) at every ordinary forward
 * position. This is ADR-079's `Temperament?` matrix with the box/unbox step deleted -- the wire is
 * already the `int` ordinal -- and the ADR-069 analogue one type up: same latency class, same
 * "an accepted plan whose emitter has no route" crash.
 *
 * There is deliberately no fixture for this today, which is exactly why the hole went unseen. Until
 * ADR-080 lands, [MoodJournal.currentMood] stops `packNuget` outright
 * (`IllegalStateException: Forward property direct nullable getter is invalid for ...`, because
 * `hasValueFanOutInner()` has no bare-`Enum` case so the getter takes the `Direct` route), and every
 * other member below is silently skipped with the mislabelled `NULLABLE` /
 * `SKIPPED_UNSUPPORTED_RETURN` diagnostic.
 *
 * [Mood] is the enum the crash was spiked against, and its `HAPPY = 0` is the mandatory
 * sentinel-catching cell: ordinal 0 must survive as non-null, distinguishably from `null`, or a
 * has-value/value mix-up passes for the wrong reason (ADR-069's lesson). `GRUMPY = 2` is the
 * non-first-ordinal cell, so a getter that always reads slot 0 cannot pass either.
 */
class MoodJournal(observed: Mood?) {
  /**
   * ADR-080 · constructor-parameter position. A plain constructor parameter, not a `val` (the
   * property facet is [currentMood]'s job), so this cell measures the constructor seam alone; the
   * has-value pair fans out here exactly as at [soothe]'s parameter. [firstEntry] is an ordinary
   * `String` property, which is what C# reads back to prove which branch actually crossed.
   */
  val firstEntry: String =
    if (observed != null) "journal opened while $observed" else "journal opened unobserved"

  /**
   * ADR-080 · property getter *and* setter position. The getter is ADR-002's `LegacyTwoCall`
   * (presence BOOLEAN + `_value` returning the `INT32` ordinal), the setter ADR-002's
   * `NullableDispatch` (`set(int)` + `set_null()`). [moodSummary] observes it Kotlin-side, so a C#
   * write only reads back correctly if it arrived as a genuine `null` or a genuine [Mood] entry
   * rather than a stray ordinal.
   */
  var currentMood: Mood? = null

  /** ADR-080 · the Kotlin-side observation for [currentMood]'s setter. */
  fun moodSummary(): String = currentMood?.description ?: "No mood recorded yet."

  /**
   * ADR-080 · method parameter AND nullable method return in one call (the ADR's own
   * `soothe(mood: Mood?): Mood?` sketch). `null` in means `null` out on both slots; `GRUMPY`
   * (ordinal 2) settles to `SLEEPY` (ordinal 1), so neither side can pass by echoing a zero; and
   * `HAPPY` (ordinal 0) comes back unchanged, which is the cell where an in-band sentinel would
   * read as `null`.
   */
  fun soothe(mood: Mood?): Mood? = if (mood == Mood.GRUMPY) Mood.SLEEPY else mood
}

/**
 * ADR-080 · extension-function parameter position. Object-handle receiver ([Cat], already
 * supported) x bare nullable enum parameter, so the only new seam is the parameter. `null` means
 * "no mood filter"; a real [Mood] must arrive as a genuine entry for the comparison against
 * [Cat.mood] to be meaningful rather than accidental.
 */
fun Cat.matchesMood(expected: Mood?): Boolean = expected == null || mood == expected

/**
 * ADR-080 · top-level-function return position, on ADR-002's two-call
 * `${export}_has_value` + `${export}_value` shape. A negative `hour` is the `null` cell; `hour == 0`
 * is the mandatory ordinal-0 cell (`HAPPY`, a legitimate entry rather than the in-band sentinel this
 * two-call shape exists to avoid confusing with `null`); anything else is `GRUMPY`, because a cat
 * woken at any other hour is.
 */
fun napMood(hour: Int): Mood? = when {
  hour < 0 -> null
  hour == 0 -> Mood.HAPPY
  else -> Mood.GRUMPY
}

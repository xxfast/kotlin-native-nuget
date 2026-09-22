package io.github.xxfast.kotlin.native.nuget.test.namesake.a

/**
 * Namesake fixture, half A: the enum shape (backlog shape 4), which the backlog calls inferred and
 * never reproduced because neither existing `Mood` fixture declares a colliding property.
 *
 * Both enum-property families are declared here on purpose, because they take DIFFERENT symbol
 * routes:
 *  - [Mood.chirp] is a MEMBER property on the enum, the `EnumExports.kt` route whose prefix is the
 *    enum's bare simple name (`mood_get_chirp`),
 *  - `Mood.pounce` below is an EXTENSION property whose receiver is an enum, the
 *    `ForwardPropertyPlanner` receiver-prefix fallback family (`mood_get_pounce`).
 *
 * Property names are kept clear of `cat.Mood.description` and `cat.Mood.emoji` so the failure this
 * fixture provokes names only the namesake pair.
 */
enum class Mood {
  CURIOUS,
  SMUG;

  /** Member property on the enum: the `EnumExports.kt` prefix route. */
  val chirp: String
    get() = when (this) {
      CURIOUS -> "a: Oreo chirps at the window"
      SMUG -> "a: Oreo owns the warmest chair"
    }
}

/**
 * Keeps [Mood] declared and reachable: an enum nothing exported mentions is gated out of the
 * generated surface, so the property cells above would have nowhere to live.
 */
fun moodOf(name: String): Mood = if (name == "Oreo") Mood.SMUG else Mood.CURIOUS

/** Extension property over an enum receiver: the receiver-prefix fallback route. */
val Mood.pounce: String
  get() = when (this) {
    Mood.CURIOUS -> "a: Oreo pounces on the blind cord"
    Mood.SMUG -> "a: Oreo cannot be bothered"
  }

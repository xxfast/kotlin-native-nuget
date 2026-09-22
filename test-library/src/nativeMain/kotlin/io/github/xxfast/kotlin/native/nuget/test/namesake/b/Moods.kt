package io.github.xxfast.kotlin.native.nuget.test.namesake.b

/**
 * Namesake fixture, half B: the twin enum. Same simple name, same arm names, same two property
 * names on the same two routes as half A, different package and different text.
 */
enum class Mood {
  CURIOUS,
  SMUG;

  /** Member property on the enum: the `EnumExports.kt` prefix route. */
  val chirp: String
    get() = when (this) {
      CURIOUS -> "b: Mylo chirps at the fridge"
      SMUG -> "b: Mylo has already been fed twice"
    }
}

/**
 * Keeps [Mood] declared and reachable, and mirrors half A: the SAME input takes the OTHER arm.
 */
fun moodOf(name: String): Mood = if (name == "Oreo") Mood.CURIOUS else Mood.SMUG

/** Extension property over an enum receiver: the receiver-prefix fallback route. */
val Mood.pounce: String
  get() = when (this) {
    Mood.CURIOUS -> "b: Mylo pounces on the milk jug"
    Mood.SMUG -> "b: Mylo naps through it"
  }

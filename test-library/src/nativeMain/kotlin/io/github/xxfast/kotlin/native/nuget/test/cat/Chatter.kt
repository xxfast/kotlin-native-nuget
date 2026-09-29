package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * ADR-006 amendment: an enum whose `abstract fun` has a body on each entry. The C# extension
 * `Sound(this Chatter chatter, int times)` must dispatch to the body of the entry it was called on, so
 * the forward call spelled `Chatter.entries[receiver].sound(times)` reaches each entry's anonymous
 * subclass rather than the declaration.
 *
 * Oreo (black, white in the middle) chirps at birds; Mylo (brown and creamy) trills for cream.
 */
enum class Chatter {
  CHIRP {
    override fun sound(times: Int): String = List(times) { "chirp" }.joinToString(" ")
  },
  TRILL {
    override fun sound(times: Int): String = List(times) { "trrrl" }.joinToString(" ")
  };

  /** Per-entry body, with a plain `Int` parameter so dispatch and marshalling cross together. */
  abstract fun sound(times: Int): String
}

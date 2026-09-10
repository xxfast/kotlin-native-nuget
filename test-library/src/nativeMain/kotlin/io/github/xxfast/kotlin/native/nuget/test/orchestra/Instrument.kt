package io.github.xxfast.kotlin.native.nuget.test.orchestra

/**
 * ADR-075: an exported base class whose *unimplemented* `abstract val` / `abstract var` must
 * render as a C# abstract property (`public abstract string Family { get; }`), so [Violin]'s
 * `override` compiles instead of failing CS0506.
 *
 * The property route has no abstract path today: the member is planned and rendered as a
 * concrete, non-virtual property with a live getter, which the method route already avoids.
 * The abstract-method walk is a separate hole and is deliberately not exercised here, so
 * [describe] is an ordinary final `fun`.
 *
 * [describe] reads both abstract members, so a C# write to `tuning` through an [Instrument]
 * typed reference is observable from Kotlin's own dispatch, not just echoed back by the C#
 * getter. Oreo supervises the tuning; he disagrees with it.
 */
abstract class Instrument(val name: String) {
  // Control: `name` is a concrete constructor property on the abstract base. It must keep its
  // ordinary non-abstract projection, `public string Name => ...`.

  /** Unimplemented here: must render `public abstract string Family { get; }`. */
  abstract val family: String

  /** Unimplemented and mutable: must render `public abstract string Tuning { get; set; }`. */
  abstract var tuning: String

  fun describe(): String = "$name ($family, tuned $tuning)"
}

/**
 * Overrides every abstract member of [Instrument]. Compiles in C# only once the base is abstract.
 */
class Violin : Instrument("violin") {
  override val family: String = "strings"
  override var tuning: String = "G-D-A-E"
}

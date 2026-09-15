package io.github.xxfast.kotlin.native.nuget.test.kdoc

// ADR-150 fixture: the enum row. `values`/`valueOf`/`entries` are SYNTHETIC and report the enum's
// own `docString` through KSP, so the summary below must land on the C# enum and on nothing else
// the enum generates. `loudness` is deliberately undocumented, so the generated
// `VolumeExtensions.Loudness` extension carries no doc entry at all unless the summary leaked.

/** How loud Mylo is right now. */
enum class Volume {
  PURR,
  CHIRP,
  YOWL;

  val loudness: Int
    get() = ordinal * 10
}

// ADR-150: the documented-enum-entry row lives on its own enum, not on `Volume`: `Volume` is the
// control that proves a type summary reaches the enum and NOTHING else the enum generates, and a
// documented entry there would be a second `TestLibrary.Kdoc.Volume*` documentation entry.

/** How Oreo's whiskers are sitting. */
enum class Whiskers {
  /** Pointing forward, interested. */
  FORWARD,
  BACK,
}

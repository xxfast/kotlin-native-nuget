package io.github.xxfast.kotlin.native.nuget.test.wardrobe

/**
 * ADR-084 amendment fixture: `var` property setters. Each `var` on a C#-implemented interface gets
 * a `<name>Set` slot ordered directly after its getter, so Kotlin can write through a C# object it
 * was handed and read the write back through the getter.
 *
 * Two `var`s, one per side of the conversion seam:
 *
 * - [Collar.tag] is a `String`, so every write mints a handle for C# to read and release,
 * - [Collar.bells] is an `Int`, which crosses as a plain scalar with nothing to release.
 *
 * [Collar] appears at a parameter position only, so it crosses through the ADR-084 bridge factory.
 * Its own package keeps it clear of the ADR-133 `nested.Collar`.
 *
 * Oreo's collar says "OREO" and jingles. Mylo lost his bell under the couch.
 */
interface Collar {
  var tag: String
  var bells: Int
}

/** Writes [tag] through the setter slot, then reads it back through the getter slot. */
fun retag(collar: Collar, tag: String): String {
  collar.tag = tag
  return collar.tag
}

/** One more bell: a getter read and a setter write of the same `Int` slot pair. */
fun addBell(collar: Collar): Int {
  collar.bells += 1
  return collar.bells
}

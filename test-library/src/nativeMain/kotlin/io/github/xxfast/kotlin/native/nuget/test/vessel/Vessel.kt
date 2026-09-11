package io.github.xxfast.kotlin.native.nuget.test.vessel

/**
 * ADR-101, transitive half: the exported grand-base of a chain
 * `Dinghy : Skiff : Vessel` whose middle link lives outside `rootPackage`.
 *
 * [Dinghy] must render `public class Dinghy : Vessel` in C#, so [sail] is reached through a
 * `Vessel`-typed reference and never re-homed onto [Dinghy] itself.
 *
 * The cats' bathtub navy: Mylo captains the [Vessel], Oreo bails.
 */
open class Vessel(val name: String) {
  /** Declared on the exported base only. Must stay inherited, not copied onto [Dinghy]. */
  fun sail(): String = "$name sails"
}

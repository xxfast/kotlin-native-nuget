package io.github.xxfast.kotlin.native.nuget.test.aviary

import io.github.xxfast.kotlin.native.nuget.hidden.Nesting

/**
 * ADR-075 amendment (2026-09-13): an exported abstract class inheriting abstract `val`/`var`
 * members from an UNEXPORTED interface it does not implement.
 *
 * The exported-interface twin is [Bird] : [Feathered], which stays untouched. Here ADR-101 drops
 * `: Nesting` from the C# base list (`SKIPPED_UNEXPORTED_SUPERTYPE`), and the ADR-113 interface
 * declaration catalog holds no plan for `Nesting.material` because an unexported interface is
 * never planned onto it -- so today the members are dropped silently while [Wren] still renders
 * `public override`, and the generated `Interop.cs` itself fails CS0115. `Nester` must declare
 * `public abstract string Material { get; }` and `public abstract int Height { get; set; }`
 * itself; `Nesting.lining` must be skipped named (`SKIPPED_UNSUPPORTED_PROPERTY`), not crash.
 *
 * [describe] reads both bridgeable members through Kotlin's own dispatch, so a C# write to
 * [Nesting.height] is observable rather than echoed back by the C# getter. Mylo is the brown and
 * creamy one; he is not a wren either.
 */
abstract class Nester : Nesting {
  fun describe(): String = "$material@$height"
}

/** Implements all three inherited members. Compiles in C# only once [Nester] declares them. */
class Wren : Nester() {
  override val material: String = "twig"
  override var height: Int = 3
  override val lining: Nesting.Lining = Nesting.Lining()
}

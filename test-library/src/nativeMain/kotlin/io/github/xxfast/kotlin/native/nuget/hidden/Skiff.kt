package io.github.xxfast.kotlin.native.nuget.hidden

import io.github.xxfast.kotlin.native.nuget.test.vessel.Vessel

/**
 * The dropped middle of the `Dinghy : Skiff : Vessel` chain. The package is
 * `io.github.xxfast.kotlin.native.nuget.hidden`, outside `rootPackage`
 * (`io.github.xxfast.kotlin.native.nuget.test`) and not a subpackage of it, so KSP never exports
 * it: the ADR-066 reachability closure admits returns, parameters and type arguments, never a
 * supertype.
 *
 * Same module on purpose. A base in a dependency module cannot extend a `test-library` class, and
 * the chain needs the grand-base to be exported.
 *
 * [oars] and [row] are the members that must re-home onto the derived class when this link is
 * dropped: an unexported *interface* carries nothing C# could call, an unexported base class does.
 */
open class Skiff(name: String) : Vessel(name) {
  /** Re-homes onto the derived class as a C# property. */
  val oars: Int = 2

  /** Re-homes onto the derived class as a C# method. */
  fun row(): String = "rowing with $oars oars"
}

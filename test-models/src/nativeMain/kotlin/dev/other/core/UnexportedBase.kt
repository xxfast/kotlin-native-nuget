package dev.other.core

/**
 * The base-class half of the issue #42 supertype guard, and the deliberate mirror image of
 * [Issue42Component] next door. Same trick -- declared in `dev.other.core`, outside
 * `:test-library`'s `rootPackage`, and reached only as a *supertype*, which the ADR-066
 * reachability closure never walks -- so today an exported subclass renders
 * `: UnexportedBase` and `Interop.cs` dies on CS0246.
 *
 * Where [Issue42Component] is deliberately empty (an unexported *interface* carries nothing the
 * C# side could call, so dropping it loses nothing), this class deliberately carries one public
 * property and one public concrete method. That is the whole point: a dropped base class must not
 * take its members with it. After the fix the subclass renders with no base at all, a
 * `SKIPPED_UNEXPORTED_SUPERTYPE` warning names this class, and [label] and [greet] are bound on
 * the subclass itself -- C# never sees a `UnexportedBase` type or any inheritance relation.
 *
 * Mylo is the base of this household: creamy, brown, and greets everyone at the door.
 */
open class UnexportedBase {
  val label: String = "base"

  fun greet(name: String): String = "hello $name from base"
}

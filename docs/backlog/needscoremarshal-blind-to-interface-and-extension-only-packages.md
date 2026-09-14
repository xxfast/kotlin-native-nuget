# A package holding only an interface plus an extension over it renders C# that calls a helper it never declares

**A package whose only exported declarations are an interface and an extension function or
extension property over it generates `Interop.cs` that calls `NugetMarshal.HandleOf` and
`NugetMarshal.Dispose`, but never declares `NugetMarshal` (or the interface's own
`NugetBridgeState` subclass) anywhere in the same output.** Nothing compiles the generated bindings
against that package in isolation today, so the gap is silent.

Root cause: `CirTranslator.needsCoreMarshal` (`cir/CirTranslator.kt` ~:648-652) decides whether to
emit `NugetMarshal` and its ADR-084 bridge helpers (~:674-687) by checking whether the package has
at least one top-level function, class, object, or sealed class. An `interface` declaration and an
extension function or extension property are not in that list, so a package consisting only of
`interface Sitter { fun house(): String }` plus `val Sitter.address: String get() = ...` trips the
check to false. The extension property itself still renders (`GetAddress(this ISitter receiver)`,
calling `NugetMarshal.HandleOf(receiver, out receiverOwned)`), because the property route's own
rendering does not consult `needsCoreMarshal`; only the file that would declare `NugetMarshal`
consults it.

Why it went unnoticed: every `test-library` package that declares an interface also declares at
least one top-level function, class, object, or sealed class alongside it, so `needsCoreMarshal`
has always returned true in practice. The ADR-132 receiver-lowering fixture's new
`Tier1ReceiverShapesExtensionPropertyTest` cell (the one proving a receiver-only interface is
reachable through the property plan's `RECEIVER`-slot arm) is the first source that isolates an
interface-plus-extension-only package, and it works around the gap with one unrelated top-level
function (`fun frontDoor(): String`) rather than exercising the true bug.

Affects both the extension-function and the extension-property route, since both call through
`NugetMarshal` for an interface receiver and neither is on `needsCoreMarshal`'s allow-list.

Discovered alongside [ADR-132](../adr/132-extension-receiver-shapes.md)'s 2026-09-14 amendment.

# An interface default with a `Flow` return or lambda parameter is invisible through the interface type

**An interface default member typed with a `Flow`/`StateFlow` return or a per-call lambda parameter
re-emits on every implementing class, through that class's own class-owner legacy route, but is
never declared on the generated C# `interface` itself.** A C# caller holding a class-typed reference
can call it; a caller holding only the interface-typed reference cannot, because the interface
declaration was never told about it.

Verified by execution (research H): `Manifest.flowReturnOnInterface` and
`Manifest.callbackParamOnInterface` both re-emit as `manifestdesk_flowReturnOnInterface_collect` /
`manifestdesk_callbackParamOnInterface` on the implementing class (`Interop.cs:25648`, `:25626`),
while `public interface IManifest : IDisposable` (`Interop.cs:25711`) declares neither member.
Nothing warns about the gap: both halves emit successfully, so ADR-064's unrouted-position
reclassification (which only fires when *no* route re-emits a member) correctly leaves these two
alone, and there is no separate diagnostic for "re-emitted somewhere, but not on the type a caller
might be holding."

This is a decision to make, not an omission to patch mechanically: either declare these two shapes
on the interface too (which needs the interface-declaration route to understand the class-owner
Flow/callback legacy routes it doesn't touch today), or document the asymmetry and leave it.
Discovered alongside [ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-13 amendment.

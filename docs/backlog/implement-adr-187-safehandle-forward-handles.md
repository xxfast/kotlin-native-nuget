# Implement ADR-187: SafeHandle-owned forward handles

[ADR-187](../adr/187-forward-finalizer-contract.md) decided the contract on 2026-10-02: every forward
wrapper that owns a Kotlin handle holds it in a generated `NugetKotlinHandle : SafeHandle` (one per
package, small subclasses for kinds whose release is not `NugetHandles.release`, such as the suspend
scope and the subscription token). `Dispose()` stays the prompt path; a wrapper dropped without
disposing is released when the .NET GC finalizes the handle. This item implements it and closes the
callback-payload wrapper item under Performance & Resource Hygiene
([details](callback-payload-wrapper-no-finalizer.md)). Size: L, roughly 12 to 15 processor files
plus fixtures and leak rows. The research memo is `docs/research/roadmap/forward-finalizer-contract.md`.

## Open questions for the implementation

1. **Ownership audit first.** Audit every wrapper construction site (`new T(handle, out _)` and the
   generated `Wrap`, `FromHandle` and `Materialize` paths) before changing the field type. A wrapper
   built over a handle something else frees would double-free once finalization exists. The memo read
   the borrowed-handle rule (`owned = false`) but did not audit the roughly 800 TestLibrary sites. If
   the audit finds a handle that cannot be kept alive, the ADR's fallback is "no finalizer,
   `NugetMarshal.LiveHandles` diagnoses".
2. **Completion closures root the wrapper.** The plan, `Flow` and `StateFlow` completion closures
   (and the suspend method renderer) must capture the wrapper so a dropped wrapper never releases its
   scope mid-flight. Confirm the CIR plan, `Flow` and `StateFlow` routes dereference the Kotlin object
   before launch, as the legacy suspend route does.
3. **Keep-alive at pointer users.** `INugetHandle.Handle` stays `IntPtr`; the code that consumes the
   pointer calls `GC.KeepAlive(wrapper)` after the native call (after the caller's `Add` or `Put`,
   not inside `NugetMarshal.Wrap`), including the `other._handle` read in generated `Equals`.
4. **GC-stress test.** Add an `IntegrationTests/` test that forces collections while crossings are in
   flight, plus `LeakTests/LiveHandleTests.cs` rows for an undisposed wrapper, an undisposed callback
   payload and an abandoned `Flow` item.
5. **Close-out.** Amend ADR-003 and ADR-121, delete
   `docs/backlog/callback-payload-wrapper-no-finalizer.md` with its ROADMAP item, and move ADR-187 to
   Accepted. The wrapper-typed half of the abandoned-`Flow`-item item closes with this; its
   collection-element half stays open.

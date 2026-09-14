# A `suspend fun`/`Flow` member on a class with a Kotlin superclass generates non-compiling C#

**Any class with a Kotlin superclass that declares its own `suspend fun` or `Flow<T>`-returning
member generates C# that does not compile.** The derived class gets `renderDispose`'s scope-aware
`Dispose()` override and `DisposeAsync()`, but never the `internal IntPtr _scopeHandle` field, the
`GetOrCreateScope()` method, or `IAsyncDisposable` on its base-list header, so `Interop.cs` fails
with four `CS0103` (undeclared `_scopeHandle`/`GetOrCreateScope`) for that class.

Root cause: `CirClassRenderer.kt`'s `implements`/field-emission block (~:208-231) branches only on
`cls.superClass != null`. When there is a superclass, it assumes "the base declares `_handle`,
implements `INugetHandle` and carries `IDisposable`... a derived class inherits all three" (the
comment at ~:205-207, from the ADR-101 amendment) and skips the whole `hasSuspendMethods` block
unconditionally, including the `IAsyncDisposable` base-list entry, the scope field, and
`GetOrCreateScope()`. That reasoning holds only when the *base* class is the one with the suspend
member (or shares the same one). It breaks the moment the *derived* class is the one with the
suspend member and the base has none: nothing upstream of the superclass ever renders the scope
machinery, but `renderDispose` still emits `DisposeAsync()` on the derived class because it reads
the derived class's own `hasSuspendMethods`.

Why it went unnoticed: no `test-library` fixture had a class with a Kotlin superclass and its own
async member until this feature's reproduction. Verified against `Cat : Animal` with a `String`
return, confirming the break is the general plain-superclass scope-owner path, not anything specific
to an interface return; a base-less class and a sealed arm (`Job.Idle : Job, IAsyncDisposable`) both
work today. `packNuget`'s `nugetCompileInterop` ([ADR-138](../adr/138-pack-time-interop-compile-check.md))
now catches this at pack time instead of it surfacing as a consumer-side `CS0103`.

Practical effect: no subclass in a consumer library can declare an async member today.

Discovered alongside [ADR-136](../adr/136-csharp-identity-on-async-interface-reads.md).

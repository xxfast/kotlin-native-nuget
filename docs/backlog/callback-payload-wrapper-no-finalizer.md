# A callback-payload wrapper has no finalizer, so an undisposed object payload leaks

**A generated wrapper handed to a C# → Kotlin callback as an object payload (a per-call lambda
parameter, a stored callback, or the interface-bridge route) has a `Dispose()` and no finalizer or
`SafeHandle`. A callback body that reads the payload without disposing it keeps the underlying
`StableRef` handle alive for the rest of the process.**

The cause is the ownership rule [ADR-036](../adr/036-reverse-interop-mechanism.md)'s 2026-09-11
amendment settled on: on a handle-passed callback payload, the C# side owns the free. For an
exported object, `NugetMarshal.Materialize<T>` hands the raw handle straight to the wrapper's
`new T(handle)` constructor with no other holder, so the wrapper's own `Dispose()` is the only free
there is. That is deliberate: the alternative (Kotlin frees, the wrapper does not own) would leave
the wrapper holding a dangling handle for the rest of the callback body, turning the documented
`using var t = toy;` pattern into a double free. A leaked handle is diagnosable through
`NugetMarshal.LiveHandles`; a use-after-free is not, which is why the fix chose the leak.

It stays invisible today because nothing forces a callback body to dispose the object it is handed:
`LeakTests` rows 8g through 8j (`LeakTests/LiveHandleTests.cs`) all `using` the payload correctly, so
they measure the fix's ownership rule, not this residual. No fixture calls a callback route with a
body that reads the object and drops it without disposing.

Closing it means giving the generated wrappers a finalizer (or a `SafeHandle`-backed release) that
frees an undisposed handle, which is a separate decision from the ownership fix: it puts native
frees on the finalizer thread for every wrapper the bridge mints, not only callback payloads, so it
needs its own cost/benefit pass rather than riding along with this one.

Discovered alongside [ADR-036](../adr/036-reverse-interop-mechanism.md)'s 2026-09-11 ownership
amendment, while fixing the callback route's double release. Verified by reading the fix and its
own ADR note; no fixture reproduces the leak itself.

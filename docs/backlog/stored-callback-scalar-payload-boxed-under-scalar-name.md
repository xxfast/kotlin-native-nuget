# The stored-callback route boxes a scalar payload under a scalar-shaped delegate name

**A stored `(Int) -> Unit` listener registers its delegate under a scalar-shaped name
(`NugetIntVoidCallback`) while still boxing the payload as a handle (`IntPtr arg0Ptr`), read back
with `NugetMarshal.FromHandle<int>`.** Name and wire agree, so there is no collision or compile
failure today; what remains is that a stored scalar payload pays for a `StableRef` box per
invocation it does not need.

`storedArgSuffix` (`CirClassTranslator.kt:3427-3435`) tests the QUALIFIED type name
(`kotlin.Int`) against `KOTLIN_TO_CSHARP_RETURN`, a map keyed by SIMPLE name
(`cir/CirTypeMapping.kt:28-42`). `kotlin.Int` never matches that map, so the suffix falls through to
`Object` for every primitive, and the delegate registered under that suffix
(`NugetObjectVoidCallback(IntPtr arg0Ptr, IntPtr _)`) is exactly what the boxed, handle-passed
implementation emits. A verified spike (a class with both a stored and a per-call `(Int) -> Unit`
member) confirmed the two delegates that actually register are distinct
(`NugetIntVoidCallback` for the per-call route, `NugetObjectVoidCallback` for the stored route) and
both compile clean; there is no `NugetIntVoidCallback` shape conflict on today's main.
[ADR-036](../adr/036-reverse-interop-mechanism.md)'s 2026-09-13 amendment already says the same: "a
primitive falls through to the `Object` suffix" (`036:515-519`).

Closing this means moving the stored route onto the by-value predicate the per-call route and
[ADR-160](../adr/160-callback-parameter-on-the-forward-plan.md) already use
(`isByValueCallbackScalar`), which would let a stored scalar payload cross by value instead of
through a `StableRef` box: a by-value optimisation, not a correctness fix. The trap to avoid:
renaming `storedArgSuffix`'s output to the simple name without also moving the wire to by-value
would create the very collision this item used to (incorrectly) describe as already present.

Discovered alongside [ADR-036](../adr/036-reverse-interop-mechanism.md)'s 2026-09-13 amendment;
reassessed and reworded alongside [ADR-160](../adr/160-callback-parameter-on-the-forward-plan.md)'s
research (spike confirmed no collision). Verified by reading and by spike; not pinned by a
committed Tier 1 test.

# A `Flow<T>` item whose materialisation fails leaks one `StableRef` per failed item

> Discovered while landing [ADR-161](../adr/161-csharp-callback-exception-into-kotlin.md) part A.

`LeakTests/LiveHandleTests.cs` records this as accepted residue rather than a passing row (see the
comment above the ADR-161 rows there). When `NugetMarshal.FromHandle<T>` throws while materialising
a `Flow<T>` element, Kotlin has already handed the item's handle over before the C# read failed, so
nothing on the failure path releases it. Fixing this needs per-branch ownership of the element
handle inside `FromHandle<T>` (the happy path's release and a new failure-path release must not
both fire, which would double-free); a shared `catch` around the whole read is not enough by itself.

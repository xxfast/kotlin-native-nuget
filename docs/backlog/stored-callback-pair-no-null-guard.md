# The stored-callback add/remove pair route has no `ArgumentNullException.ThrowIfNull` for a nullable-type listener

> Extracted verbatim from `ROADMAP.md` (Phase 4).

A stored-callback pair whose listener parameter type is itself nullable
(`addRinger(l: ((Int) -> Unit)?)`) has no null guard on the generated `AddX` method. The per-call
route's `CirCallbackMethod` model carries `rejectsNullDelegate` (`cir/CirModel.kt:680`, set from the
unexpanded parameter's own nullability at `cir/CirClassTranslator.kt:3553`), and its renderer emits
`ArgumentNullException.ThrowIfNull` ahead of the crossing (`cir/CirClassRenderer.kt:891-894`). The
stored-callback pair's own model, `CirStoredCallbackMethod` (`cir/CirModel.kt:647-657`), has no such
field at all, so `translateStoredCallbackPair` (the function immediately after
`translateCallbackMethod` in `CirClassTranslator.kt`) never reads the listener parameter's
nullability, and the pair's renderer path has nothing to guard with. A `null` argument to the
generated `AddX` reaches `GCHandle.Alloc` and defers the failure to a thunk that dereferences
`Target` inside `[UnmanagedCallersOnly]`, the same fail-fast the per-call route's guard exists to
prevent. Verified by reading; no fixture. Discovered alongside ADR-036's 2026-09-22 amendment
(boundary-nullability-gaps item).

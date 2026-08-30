# Class-typed (handle) components inside a struct: deferred on semantics, not cost.

> Extracted verbatim from `ROADMAP.md` (Phase 9: Reverse basic type support — C# objects in Kotlin) in the 2026-08-31 roadmap slim-down.

Class-typed (handle) components inside a struct: deferred on semantics, not cost. A `GCHandle` inside a value type does not compose, since `copy()`ing the Kotlin `data class` would duplicate a `Cleaner`-managed handle. Needs its own decision (ADR-056 Scope). Confirmed unaffected by nesting: a handle component would be a **leaf** on the wire (an `IntPtr`), so the flattening recursion never descends through it either way (see [ADR-059](docs/adr/059-nested-struct-components-in-kotlin.md) Scope)

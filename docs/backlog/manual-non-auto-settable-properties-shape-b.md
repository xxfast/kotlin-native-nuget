# Manual (non-auto) settable properties as Shape B components (a hand-written private field behind a settable property, e.g.

> Extracted verbatim from `ROADMAP.md` (Phase 9: Reverse basic type support — C# objects in Kotlin) in the 2026-08-31 roadmap slim-down.

Manual (non-auto) settable properties as Shape B components (a hand-written private field behind a settable property, e.g. `struct Manual { private int _a; public int A { get => _a; set => _a = value; } }`): metadata alone cannot prove the setter writes the field, so such a struct is skipped rather than bound. Deferred by [ADR-058](docs/adr/058-csharp-shape-b-structs-in-kotlin.md) Decision 2a; would need IL analysis or a heuristic

# `Nullable<T>` value types (`int?`) are a different, deferred feature (ADR-053 Decision 3): no `NullableAttribute` machinery applies.

> Extracted verbatim from `ROADMAP.md` (Phase 9: Reverse basic type support — C# objects in Kotlin) in the 2026-08-31 roadmap slim-down.

`Nullable<T>` value types (`int?`) are a different, deferred feature (ADR-053 Decision 3): no `NullableAttribute` machinery applies. **Unblocked** by [ADR-056](docs/adr/056-csharp-structs-in-kotlin.md): its struct out-pointer convention (`void` + one out-pointer per component, one crossing, one evaluation) is exactly the wire format this item was waiting on, so it needs no further ADR, only the reader work to stop `GetGenericInstantiation` dropping `System.Nullable<T>`, plus the generator work to emit the `byte hasValue` + out-pointer shape

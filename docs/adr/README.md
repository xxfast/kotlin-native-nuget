# Architecture Decision Records

- [001](001-csharp-codegen-in-consumer.md) — Moved ClangSharp from Gradle to .targets in NuGet (later replaced by KSP)
- [002](002-nullable-two-call-pattern.md) — `_has_value` + `_value` functions → C# `T?`
- [003](003-memory-management-across-bridge.md) — StableRef pins Kotlin objects; C# disposes; each GC manages its own side
- [004](004-cir-intermediate-representation.md) — Type-safe C# AST (aligned with Swift Export's SIR)
- [005](005-object-return-semantics.md) — New wrapper per access; identity not preserved (matches ObjC/Swift)
- [006](006-enum-mapping.md) — Ordinal-backed C# enum + extension methods for properties
- [007](007-top-level-function-class-naming.md) — File name as class name; `Kt` suffix only on conflict
- [013](013-extension-property-mapping.md) — `GetXxx()`/`SetXxx()` extension methods for Kotlin extension properties
- [014](014-value-class-mapping.md) — `readonly record struct` with unwrapped bridge for Kotlin value classes
- [015](015-generic-type-constraint-mapping.md) — C# `where` clauses from Kotlin upper bounds; `CirTypeParameter` model

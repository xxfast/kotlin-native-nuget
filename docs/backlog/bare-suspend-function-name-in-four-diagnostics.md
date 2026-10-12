# Four diagnostics still print a bare `kotlin.coroutines.SuspendFunction1`

Found with the 2026-10-12 function-type spelling fix ([ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md) amendment of that date). That fix made every diagnostic that holds a `KSType` print a function type as Kotlin source does (`suspend (Int) -> String`). Four messages never hold the `KSType`, only a name, so they still print the class name with no type arguments. Verified in Tier 1 output.

- `BridgeType.SpecializedProtocol.name` leaks through `diagnosticTypeName()`: "its type suspend lambda kotlin.coroutines.SuspendFunction1 has no property getter or setter shape". The planner keys on `name.startsWith("suspend lambda ")` at `ForwardCallablePlanner.kt` ~5874, so correcting the text is a model change, not a wording change.
- The value-class message at `NugetProcessor.kt` ~2411.
- The lambda type-argument message at `ForwardDiagnostic.kt` ~870/1222 and `CirTypeMapping.kt` ~1165.
- The builtin-bound message at `CirTypeMapping.kt` ~842.

An extension function type is also still printed `(Int) -> String` rather than `Int.() -> String`.

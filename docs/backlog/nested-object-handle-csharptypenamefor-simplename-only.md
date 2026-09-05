# `csharpTypeNameFor` spells a nested class as a bare simple name, not its enclosing scope

> Extracted verbatim from `ROADMAP.md` (Phase 2).

**A class nested inside another exported class (`class Outer { sealed class Inner }`) is spelled `global::NS.Inner` at every `ObjectHandle` position, dropping `Outer` from the name.** `ForwardBridgeTypeClassifier.kt`'s `csharpTypeNameFor` uses `simpleName` only, with no handling for an enclosing class scope. Pre-existing for every `ObjectHandle`, not introduced by sealed handling; it is visible for a sealed base too (`Outer.Inner` still spells `global::NS.Inner`), which is how [ADR-105](docs/adr/105-sealed-property-position.md) surfaced it (its "Inferred" claims list, not spiked or built). Discovered alongside ADR-105 (issue [#54](https://github.com/xxfast/kotlin-native-nuget/issues/54)).

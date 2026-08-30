# Nullable properties on a data class nested inside a plain class are not covered by the nested-class fix

> Extracted verbatim from `ROADMAP.md` (Phase 3).

The report's other nesting shape, a plain class nesting a `data class` (`class ProbeOuter { data class Nested(val x: String?) }`), is NOT covered by the fix above: such a class is never collected at all. Every root bucket in `NugetProcessor.kt` (~347-385) filters `parentDeclaration == null`, and `ForwardReachabilityClosure` walks member types, not declared nested classes, so a plain nested class is silently dropped with no `SKIPPED_*` diagnostic. This is a missing collection capability, not a nullability defect, and is separate from the fix above. Discovered alongside [#38](https://github.com/xxfast/kotlin-native-nuget/issues/38).

# A merged extension class's namespace is whichever extension KSP visits first, not deterministic

**Adding a `String` extension in a new package can silently relocate every existing `String`
extension's generated C# class into that new package's namespace**, moving members a consumer
already depends on out from under them with no diagnostic and no compile error on the Kotlin side.

`CirTranslator.kt` groups every extension function by its receiver's simple name into one merged
C# class (`StringExtensions`, `IntExtensions`, and so on). When the receiver type is itself outside
the export set (a stdlib type like `String`, not a Kotlin class this library declares), the merged
class has no natural home, so the translator falls back to the namespace of whichever extension
function KSP happens to visit first:

```kotlin
val namespace: String = if (receiverQualified in exportedTypes) {
  namespaceOf(receiverDecl.packageName.asString())
} else {
  namespaceOf(funcs.first().packageName.asString())
}
```

(`CirTranslator.kt:453-457`, `namespaceOf(funcs.first().packageName)`). `funcs` is grouped by
receiver simple name across the whole export set, so "first" depends on KSP's file-visitation
order, not on anything the library author declares or controls.

This went unnoticed because every fixture that extended `String` had lived in one package. It
surfaced only once a second `String` extension was added in a different package: building the
ADR-062 reserved-names fixture in `reserved/ReservedNamesSample.kt` moved the merged
`StringExtensions` class from `TestLibrary.Cat` to `TestLibrary.Reserved`, breaking every other
`String` extension already shipped there, until the new cell was moved into `test/cat/` instead
(see `ReservedExtensions.kt`'s own comment on why it lives beside the others rather than in
`reserved/`). Verified by a real `packNuget` run comparing the namespace before and after adding
the second package's extension.

Fix shape: either give each package its own extension class (`TestLibrary.Cat.StringExtensions`,
`TestLibrary.Reserved.StringExtensions`, both still valid C# with `using static` or explicit
qualification), or pick a single deterministic root namespace for every unexported-receiver merged
class regardless of which package first extends it. Either closes the ordering dependency; neither
has been decided.

Discovered alongside [ADR-062](../adr/062-forward-callable-plan.md)'s 2026-09-07 amendment.

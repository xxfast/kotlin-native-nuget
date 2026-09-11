# An abstract class inheriting an interface `val`/`var` from an unexported interface still drops the member entirely

> Extracted verbatim from `ROADMAP.md` (Phase 3: Basic type support).

**An exported abstract class that inherits a `val`/`var` from an interface which is itself
*unexported*, and does not implement it, has the member vanish from the generated C# entirely; a
further C# subclass whose `override` targets it then fails to compile (CS0115, "no suitable method
found to override").** `CirClassTranslator.kt`'s `inheritedAbstractProperty` (around line 217-229)
looks the C# type up in `interfaceDeclarationCatalog.propertyFor("<iface>.<name>")` and returns
`null` when nothing is found there. For an unexported interface there is nothing to find: ADR-101
drops `: IFoo` from the generated base list before the property walk runs, so the interface's own
declaration was never planned onto the catalog in the first place, and `inheritedAbstractProperty`
has no declaration to spell a type from. This is a narrower case of the same shape ADR-075's
2026-09-11 amendment fixed for the exported-interface case; the fix deliberately stops at the
export boundary, since there is no `IFoo` in C# at all to declare the member `public abstract` on.

Went unnoticed because the shipped fixture (`aviary/Bird.kt`, `Feathered`/`Bird`/`Finch`) exports
`Feathered`; nothing exercises the same shape with an unexported interface. Inferred from reading
only, no fixture. Discovered alongside
[ADR-075](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md)'s
2026-09-11 amendment.

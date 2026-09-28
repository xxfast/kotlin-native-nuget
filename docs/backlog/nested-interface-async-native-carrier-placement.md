# A nested interface's async `...Native` carrier placement is unverified

[ADR-174](../adr/174-interface-async-members.md) hoists an interface's `suspend`/`Flow`/`StateFlow`
`DllImport`s into an `internal static class {Name}Native` so a non-projecting generic implementer
can reach them (Rule 7). For an interface nested inside a `class`/`object` (a real nested C#
interface, see [Interfaces, abstract and sealed: Nested
interfaces](../topics/interfaces-abstract-sealed.md#nested-interfaces-skip-named)), the carrier is
expected to nest inside the same owner, beside the nested interface and its backing wrapper.

No `test-library` fixture combines a nested interface with an async member, so this placement has
not been exercised; a regression here would likely surface as a C# naming collision or an
unresolvable `{Name}Native` reference rather than a silent drop.

Discovered alongside [ADR-174](../adr/174-interface-async-members.md).

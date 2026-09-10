# ADR-126: One `{Receiver}Extensions` class per declaring package

## Status

Accepted

## Context

`CirTranslator` merges every extension on one receiver into a single `{Receiver}Extensions` static
class (ADR-095). Where that class lives depends on the receiver:

- an **exported** receiver (a class this library itself publishes, `Cat`) homes the class on the
  receiver's own package, `TestLibrary.Cat.CatExtensions`. Deterministic: the receiver has exactly
  one package,
- an **unexported** receiver (`String`, a primitive, any stdlib type) has no such home. The
  translator used `namespaceOf(funcs.first().packageName)`, the package of whichever extension KSP
  handed over first.

`first()` is visit order, so the class had no stable namespace. Two consequences were live in the
tree:

1. Adding a same-receiver extension in another package **relocates existing extensions**. The
   fixture's `meowify` / `isPurring` are declared in the root package and rendered inside
   `namespace TestLibrary.Cat`, because `cat/ReservedExtensions.kt`'s `String.tag` happened to be
   visited first and dragged the whole class over. During ADR-062's 2026-09-07 amendment the same
   effect moved `StringExtensions` from `TestLibrary.Cat` to `TestLibrary.Reserved` and back,
   purely as a side effect of where a new fixture cell was written. The workaround was to park the
   cell in `cat/`, away from the rest of its family, with a comment explaining why.
2. The function group and the property group each picked a namespace independently
   (`funcs.first()` vs `props.first()`), so one receiver could split across two `StringExtensions`
   classes in two namespaces. It did: `GetWordCount` sat alone in `TestLibrary` while the functions
   sat in `TestLibrary.Cat`. Consumer tests only compiled because they carried `using` directives
   for both.

Neither is a wire concern. A forward export name is derived from the receiver's simple name
(`string_meowify`, `ForwardCallablePlanner.kt`), never from a package or namespace, so
`CNameExports.kt` and the ADR-055 contract check are unaffected by any choice made here (verified:
`CNameExports.kt` is byte-identical before and after this change).

## Decision

For an unexported receiver, emit **one `{Receiver}Extensions` class per declaring package, in that
package's namespace**, holding both the extension functions and the extension properties that
package declares.

The rule is a group key rather than a lookup after grouping. `CirTranslator` keys both extension
loops on `(namespace, receiverSimpleName)`, where `namespace` is the receiver's package for an
exported receiver and the *declaring* function's or property's package otherwise. Keying is what
makes the result order-independent: nothing reads `first()`, so no declaration can influence any
other's placement. Both loops must key identically, or one package's functions and its properties
land in two classes again; the property loop's `mergeStaticClass` then folds them into one, and the
function loop switches from `addDeclaration` to `mergeStaticClass` too, so the two can never
produce two same-named partials in one namespace whichever loop runs first.

An exported receiver keeps the receiver's namespace, unchanged and out of scope. It is already
deterministic, and the receiver owning its own extensions matches the ObjC-category model that a
consumer of an exported type expects.

Precedent for the declaring-package rule (all inferred from documentation, not measured here):
Kotlin/JVM compiles a top-level or extension function in `org.example` to `org.example.AppKt`;
Swift Export emits per-package `extension` blocks; .NET's own `System.Linq.Enumerable` extends
`IEnumerable<T>` from the declaring namespace, not the receiver's, and `using System.Linq;` is what
a C# developer types. It is also the rule every other declaration this generator emits already
follows: `namespaceOf(decl.packageName)` for classes, objects, enums and per-file static classes.

## Alternatives

**One root namespace for every unexported-receiver extension class.** The same two edit sites, no
grouping change, about two lines: `namespaceOf(...)` becomes `context.rootNamespace`. Deterministic,
and a single `using TestLibrary;` reaches every stdlib-receiver extension. Rejected: it makes
extensions the one declaration kind that ignores its own package, so a consumer with only
`using TestLibrary.Cat;` silently loses that package's `String` extensions; a large library piles
every unrelated `String` extension into one root class; and two packages each declaring
`fun String.shout()` would merge into a single class and add a `ERROR_CSHARP_SIGNATURE_COLLISION`
on top of the ABI collision they already hit.

**Move the exported-receiver branch to declaring-package placement too**, for one uniform rule.
Output would be byte-identical today (every `Cat` extension is declared in `cat/`), but it is a
second decision with its own precedent pulling the other way, and it is not what the order-dependence
problem asks for. Deliberately unchanged.

**Leave placement alone and document the visit-order rule.** Rejected: the order is not stable
across a source-set change, so the documentation would be a description of an accident, and the
fixture already carried a comment apologising for one.

## Consequences

- **Consumer-breaking, source level.** `Meowify` and `IsPurring` move from `TestLibrary.Cat` to
  `TestLibrary`, and `GetWordCount` joins them there. Extension-method call syntax (`"Oreo".Meowify()`)
  keeps compiling for anyone who already has `using TestLibrary;`; a fully-qualified static call, or a
  file with only `using TestLibrary.Cat;`, does not. Needs an upgrade note.
- `Tag` moves from `TestLibrary.Cat` to `TestLibrary.Reserved`, and its fixture source moves back
  from `cat/ReservedExtensions.kt` to `reserved/ReservedExtensions.kt`, beside the rest of its family.
- One receiver can now render several `{Receiver}Extensions` classes across a library, one per
  declaring package. These are ordinary C#: same-namespace ones merge as `partial`, cross-namespace
  ones are unrelated types, and extension lookup resolves per namespace.
- No wire change. `CNameExports.kt` and the ADR-055 / ADR-117 checks are byte-identical, since export
  names are receiver-derived.
- `emitCsharpSignatureCollisions` now runs per `(namespace, receiver)` group instead of per receiver,
  which only narrows each check. Its `declaration` label is still `StringExtensions.Tag` with no
  namespace, so two groups can share a label. Cosmetic, unchanged.
- Namespace-*block* order within `Interop.cs` still follows KSP visit order, for every declaration
  kind. Not covered here, harmless.
- Two exported types sharing a simple name in different packages still merge on the receiver key
  (their ABI collision fires first). Pre-existing, unchanged.

## References

- [ADR-095](095-static-route-overloads.md): one `{Receiver}Extensions` class per receiver
- [ADR-062](062-forward-callable-plan.md): the 2026-09-07 reserved-slot amendment, where the
  order dependence was discovered
- [ADR-013](013-extension-property-mapping.md): extension properties, the second group that keys on the receiver
- [ADR-007](007-top-level-function-class-naming.md): per-file static class naming, the neighbouring placement rule
- `IntegrationTests/ExtensionNamespaceTests.cs`: fully-qualified static calls pin both namespaces
- `nuget-processor/src/test/.../tier1/Tier1ExtensionNamespaceTest.kt`: two packages in both map orders

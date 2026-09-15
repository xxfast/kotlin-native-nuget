# KDoc on an exported Kotlin declaration becomes an XML doc comment on its generated C# declaration

**A library author's KDoc never reaches the C# consumer. Every generated C# declaration renders
without a `///` comment, so IntelliSense on `new Foo(...)` or `foo.Bar()` shows the bridged
signature and nothing else.** The only doc comment the bridge emits today is
[ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md)'s `<remarks>` on an
unconstructible class, and that is bridge-authored prose, not the author's.

## What exists to build on

- **The sink.** `cir/CirDocRenderer.kt`'s `renderRemarks` is the one place text becomes C# doc
  markup, and owns the XML escaping. ADR-064's 2026-09-10 amendment shaped `CirClass.remarks` and
  the sealed-arm `remarks` field to widen into this item rather than block it. New tags
  (`<summary>`, `<param>`, `<returns>`, `<exception>`) are siblings of `renderRemarks`, never a
  second escaper.
- **The guard.** `GeneratedBindingsCheck` compiles the generated shim with
  `GenerateDocumentationFile` on and `TreatWarningsAsErrors`, so a malformed `///` is CS1570 and
  fails `scripts/verify.sh`. CS1591 (undocumented public member) is muted there and must stay
  muted: this item makes *some* members documented, never all.
- **The source.** KSP's `KSDeclaration.docString` carries the KDoc body. Verified by ADR-074's
  spike on KSP 2.3.10, which printed `docString=KDoc declared only on the native expect class...`
  for an `expect` and `docString=null` for its `actual`.
- **The delivery.** The shim ships as source ([ADR-050](../adr/050-end-to-end-packaging-integration.md)),
  so `///` comments in the generated `.cs` reach a consumer's IDE with no `lib/<tfm>/*.xml`
  documentation file to pack. `PackNugetTask` needs no change for forward.

## Mapping (forward, Kotlin → C#)

| KDoc                                   | C# XML doc                                                                                                                                               |
|----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| body, first paragraph                  | `<summary>`                                                                                                                                              |
| body, later paragraphs                 | `<remarks>`, merged *before* ADR-064's bridge-authored constraint text when both exist on one class                                                      |
| `@param name`                          | `<param name="name">`; a synthesized omitting overload ([ADR-096](../adr/096-function-default-parameters.md), [ADR-091](../adr/091-constructor-default-parameters.md)) inherits the doc minus the omitted parameters |
| `@return`                              | `<returns>`; on the `Async` projection of a `suspend` function the same text, since the `Task<T>` wraps the same value                                    |
| `@throws` / `@exception`               | `<exception cref="...">` spelled as the bridge's mapped C# type from `CirErrorRenderer.kt`'s `BuildMapped` table (`kotlin.IllegalArgumentException` → `KotlinArgumentException`, and so on); an unmapped Kotlin type crefs the fallback type that table's default arm throws |
| `@property name` on a class            | `<summary>` of the generated C# property, when the Kotlin property has no KDoc of its own                                                                |
| `@constructor`                         | `<summary>` of the primary constructor's C# `new`                                                                                                        |
| `[Foo]` / `[Foo.bar]` link             | `<see cref="..."/>` when the target is an exported declaration and the C# spelling is known (nested types and cross-namespace `global::` spellings included), else `<c>Foo</c>` |
| `@see Foo`                             | `<seealso cref="..."/>` under the same resolution rule                                                                                                   |
| inline code, fenced code               | `<c>`, `<code>`                                                                                                                                          |
| `@suppress`                            | no doc comment at all on that declaration, and nothing inherited from it                                                                                 |
| `@sample`, `@since`, `@author`, `@receiver` | dropped                                                                                                                                             |

Inferred, not verified: `docString` is the raw comment body with the `/**`, `*/` and per-line ` * `
prefixes stripped but the KDoc tags still inline, so the processor parses tags itself; there is no
KDoc parser on the KSP classpath. Markdown beyond code spans and paragraphs (emphasis, lists,
headings) passes through as escaped plain text.

## Where it attaches

Every C# declaration the CIR renders from a Kotlin declaration that had a `docString`: class,
object, constructor, method, property, extension function ([ADR-132](../adr/132-extension-receiver-shapes.md)),
interface member, enum entry, sealed arm ([ADR-116](../adr/116-sealed-subclass-methods-on-the-callable-plan.md)),
value-class struct member. Bridge-internal declarations (`NugetMarshal`, handle constructors, the
`FromHandle` factories, registration shims) get nothing.

## Known holes, decided up front

- **`expect`/`actual`.** The `actual` is the export root and carries `docString=null`
  ([ADR-074](../adr/074-expect-actual-declarations.md) point 4). `ExpectIndex` already resolves the
  `expect` half by qualified name and signature for defaults (ADR-091, ADR-096); KDoc rides the same
  lookup. Without it, every multiplatform author's docs, which live on the `expect`, are lost.
- **Cross-module (klib) declarations.** Inferred: a declaration reached through
  [ADR-066](../adr/066-forward-export-reachability-closure.md)'s closure from a dependency klib has
  no `docString`, since KSP reads it from metadata and Kotlin metadata does not carry KDoc. Those
  types render undocumented. Not a bug, a limit; say so in the feature doc.
- **Dollar identifiers.** A `[Foo]` cref into a name the `CirDollarIdentifierGuardTest` rejects
  must fall back to `<c>`, never emit a `cref` the C# compiler can't resolve (CS1574 is a warning,
  so an error under the consumer's `TreatWarningsAsErrors`).

## Verification

- Tier 1 fixture (a cell with KDoc on a class, a method with `@param`/`@return`/`@throws`, an
  `expect` with KDoc and a bare `actual`) asserting the rendered `///` block text.
- `GeneratedBindingsCheck` already executes the escaping and cref claims on every
  `scripts/verify.sh`. Add one assertion reading the check's generated `.xml` documentation file for
  an expected `M:` entry, so the claim "the comment survives compilation" is verified by execution,
  not by reading the `.cs`.
- `test-library` today has KDoc only on the platform residual files and `Billboards.kt`; the
  fixture gains KDoc on a handful of members the integration tests already touch.

## Reverse (C# → Kotlin), separate item

A NuGet package built with `GenerateDocumentationFile` ships `lib/<tfm>/<Assembly>.xml` beside the
DLL, keyed by doc IDs (`T:`, `M:`, `P:`). `NugetMetadataReader` reads assembly metadata only and
has no XML handling, so the reverse half is: read the sibling `.xml` when present, carry the text on
the RIR, render KDoc on the generated Kotlin bindings under the inverse of the table above. Its own
ADR, after forward ships. Inferred throughout; no reverse fixture has a documentation file today.

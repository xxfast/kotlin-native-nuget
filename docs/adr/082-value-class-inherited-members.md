# ADR-082: Value-class inherited members: ratify the export-set skip

## Status
Accepted

## Context

`value class ArticleUri(val value: String) : CharSequence by value` (NYTimes-KMP, BUG-009) puts
three inherited members on the value class: `length`, `get(index)`, `subSequence(start, end)`.
KSP's `getAllFunctions()` / `getAllProperties()` return supertype members at every call site, so
before ADR-064 these members bound silently (and, pre-ADR-062, with their parameters dropped,
which is what actually broke the exporter). ADR-062 fixed the parameter half; ADR-064 filtered
inherited members out with a per-member `SKIPPED_INHERITED_MEMBER` warning, and ADR-066 amended
the filter's signal from `origin != Origin.KOTLIN` (wrong cross-module) to "a supertype declares
a member with this simple name" (verified against a real klib in ADR-066, and the only signal
that also catches interface delegation, whose forwarders report `parentDeclaration == cls`).

ADR-064 deliberately left the product question open: should inherited members **ever** be in a
value class's export set? This ADR closes it.

### What ordinary classes do today (verified by source)

- `ForwardCallablePlanner.classEntries` filters methods with `method.parentDeclaration == cls`
  **unconditionally** (`ForwardCallablePlanner.kt:459`). Inherited methods on ordinary classes are
  never planned, and the filter runs before mapping, so they drop **silently**, with no `Skipped`
  entry and no diagnostic.
- `ForwardPropertyPlanner.classProperties` filters to declared-only whenever the class has any
  non-`Any` supertype (`ForwardPropertyPlanner.kt:83-88`).
- So the value-class filter under ADR-064 already matches ordinary-class behaviour, and is in fact
  *louder* about it (it emits a diagnostic where ordinary classes emit nothing).

### What exporting an inherited member would take (mechanics)

The receiver side is easy: a value-class member's receiver is the underlying value that already
crosses the wire (`String` for `ArticleUri`), and the Kotlin emitter re-wraps and dispatches, so
`length` (`Int`) and `get(index): Char` are bridgeable today (`BridgeType.Char` exists, wire
`CHAR16`; verified `ForwardCallablePlanner.kt:1747`). The costs are elsewhere:

- `subSequence(Int, Int): CharSequence` is not bridgeable: `kotlin.CharSequence` is a stdlib
  interface with no bridge mapping, so it would skip as unsupported regardless (inferred from
  `ForwardBridgeTypeClassifier`; not exercised by a fixture).
- Value-class export names carry **no overload disambiguator**: `exportName = "${prefix}_$name"`
  (verified `ForwardCallablePlanner.kt:379`). `CharSequence.get` next to any author-declared `get`
  overload collides at the C symbol level; exporting inherited members forces overload mangling
  onto the value-class path first.
- Cross-module, a delegated forwarder is **indistinguishable from a hand-written member** by
  declaration site (ADR-066, verified against a real klib): `parentDeclaration == cls` for both.
  Any "declared but not inherited" rule must therefore compare full signatures against every
  supertype member, not simple names.

### Prior art

- **ObjC/Swift export**: inline classes are in the *Unsupported* list; "arguments are mapped as
  either underlying primitive type or `id`"
  ([docs](https://kotlinlang.org/docs/native-objc-interop.html)). The wrapper type does not exist
  for ObjC consumers at all, so the inherited-member question never arises: consumers get the raw
  underlying and its native API. This project already goes further than any JetBrains exporter by
  surfacing the wrapper's *declared* members; surfacing inherited ones would be a second step
  beyond every existing precedent. (Verified via docs fetch, 2026-08.)
- **JVM/Java interop** (inferred from [inline-classes docs](https://kotlinlang.org/docs/inline-classes.html)):
  value classes erase to the underlying type; public members compile to mangled `-impl` statics
  Java cannot idiomatically call. Java consumers reach the underlying value and use *its* API.
- **C# idiom**: the strongly-typed-ID ecosystem (`readonly record struct` wrappers; e.g.
  [StronglyTypedId](https://github.com/andrewlock/StronglyTypedId),
  [Vogen](https://github.com/SteveDunn/Vogen)) exposes `.Value` and lets the consumer use the
  underlying type's own API. Wrappers do not re-surface `string`'s members; C# has no delegation
  construct, so a re-surfaced API reads as hand-rolled noise, and a *partial* one (which is what a
  bridgeability-filtered `CharSequence` yields: `Length` and `Get` but no `SubSequence`) reads as
  broken. (Inferred: ecosystem convention, not a spec.)

## Alternatives Considered

### 1. Ratify the skip permanently (chosen)

Inherited (including delegation-forwarded) members are never in a value class's export set.
Consumers use the always-present underlying property. Zero code; ADR-064's filter and
`SKIPPED_INHERITED_MEMBER` diagnostic become the permanent behaviour rather than a placeholder.

```csharp
var uri = new StoryUri("nyt://article/1234");
int n = uri.Value.Length;              // string's own, richer API
char c = uri.Value[3];
string sub = uri.Value.Substring(0, 5); // more than CharSequence could ever offer
string loud = uri.Shout();             // declared members still bind (ADR-062)
```

Pros: matches ordinary-class behaviour (declared-only, verified above); matches ObjC/JVM
precedent (underlying's native API, not a bridged subset); matches the C# wrapper-struct idiom;
`string`'s API is strictly richer than any bridged `CharSequence` subset; zero implementation
cost; the diagnostic plus its hint ("declare the member directly on the value class") already
tells an author the escape hatch.

Cons: a consumer must know to go through `.Value`; a Kotlin author who *meant* the delegation to
be part of the public surface gets a warning, not an export. Escape hatch: declare the member
explicitly with a non-colliding name (`fun shout()` in the fixture), which is exactly what the
diagnostic hint says.

### 2. Declared members plus bridgeable interface members

Export what the author wrote, plus supertype-interface members whose signatures bridge; drop the
rest with the existing diagnostic.

Cons: the "declared" half is not decidable cross-module by declaration site (ADR-066); it needs
signature-level supertype comparison, and the value-class export-name scheme needs overload
mangling before `get` can coexist with anything. The "bridgeable interface members" half yields
partial surfaces (`Get` without `SubSequence`), the least idiomatic outcome in C#. Highest cost,
worst consumer story.

### 3. Export everything bridgeable from `getAllFunctions()`

Drop the inherited-name filter, let `planOrSkip`'s type checks decide per member.

Cons: makes value classes *more* inclusive than ordinary classes (which are declared-only,
silently), inverting the consistency argument; same partial-surface and symbol-collision problems
as option 2 (no overload disambiguator on value-class export names, verified); duplicates the
underlying's API in worse form (`uri.Get(3)` next to `uri.Value[3]`).

## Decision

Option 1. Inherited and delegation-forwarded members stay out of the value-class export set,
permanently. The consumer path is the underlying property, which every generated value-class
struct exposes (verified: ADR-014/ADR-077 structs always surface the underlying as a public
property, for all four underlyings including `ObjectHandle`). `SKIPPED_INHERITED_MEMBER` remains
a per-member WARNING with the existing "declare the member directly on the value class" hint.

No code changes. Documentation: `docs/topics/value-classes.md` gains a short "inherited members"
note stating the rule and the `.Value` idiom; ROADMAP line 97 closes as decided-no.

Known, accepted limitation of the ratified filter (verified by source,
`ForwardCallablePlanner.kt:223-229, 319, 363`): the signal is **simple-name** matching, so an
author-declared member whose name merely collides with any supertype member (e.g. an explicit
`override`, or an unrelated overload `fun get(key: String)`) is also skipped, with the same
diagnostic. This is the price of the only cross-module-safe signal (ADR-066). The diagnostic
names the member, so the author sees it; the workaround is a non-colliding name.

## Consequences

- ROADMAP Phase 4's "decide whether `getAllFunctions()` supertype members are in the export set"
  closes as decided-no; ADR-064's carried-forward scope note resolves here.
- `StoryUri` (`test-models/.../StoryUri.kt`) and `Tier1NamedSkipDiagnosticsTest` become the
  permanent guard for this behaviour, not a stopgap.
- Deferred, unblocked by this decision: overload mangling for value-class export names, and a
  signature-level (rather than simple-name) inherited signal, would both be prerequisites if a
  future opt-in (e.g. an annotation to export a delegation) ever revisits this.
- Latent inconsistency, out of scope here but recorded: ordinary classes drop inherited members
  **silently** (no diagnostic), while value classes warn. If diagnostics parity is wanted,
  ordinary classes should gain the same `SKIPPED_INHERITED_MEMBER` entry, not the other way
  around.

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

Known limitation of the ratified filter (verified by source at time of writing,
`ForwardCallablePlanner.kt:223-229, 319, 363`): the signal is **simple-name** matching, so an
author-declared member whose name merely collides with any supertype member (e.g. an explicit
`override`, or an unrelated overload `fun get(key: String)`) is also skipped, with the same
diagnostic.

### Amendment (2026-08-08): approved follow-up narrowing the over-drop

This amends the limitation above only. The decision itself (inherited and delegation-forwarded
members never export; consumers use the underlying property) is unchanged and not reopened.

The over-drop half of the limitation is approved for fixing, in two coupled parts:

1. **Declared-vs-inherited becomes signature-level, not simple-name.** A value-class member is
   skipped as `SKIPPED_INHERITED_MEMBER` iff `parentDeclaration != cls` (genuine supertype
   inheritance; verified cross-module-safe against a real klib, ADR-066), **or** a supertype
   declares a member of the same kind with the same simple name, the same arity, and per-position
   matching parameter types (resolved qualified name plus nullability; a supertype-side type
   *parameter* in a position matches anything, conservatively). Properties compare by simple name
   alone (Kotlin properties cannot overload), and only against supertype *properties*. Under this
   rule an explicit `override` and a delegation forwarder still skip (each *is* the inherited
   signature), while an unrelated overload (`fun get(key: String)`) exports.
   - **Verified** (ADR-066, real-klib session): delegation forwarders report
     `parentDeclaration == cls` and `origin == KOTLIN_LIB` cross-module, so the supertype
     signature comparison is the only cross-module signal; `parentDeclaration != cls` alone
     already catches genuine supertype inheritance cross-module.
   - **Verified on implementation** (2026-08-08, `scripts/verify.sh`), upgrading what this bullet
     originally recorded as inferred: a klib-loaded supertype member's *parameter types* do
     resolve to the qualified names the comparison expects. `StoryUri` lives in `:test-models` and
     is consumed by `:test-library` as a klib; its delegated `CharSequence.get(index: Int)` still
     skips (the `Get(int)` absence guard in `NewsroomReachabilityTests` stays green) while the
     declared `get(key: String)` exports, which is only decidable if `Int` and `String` compare
     across the module boundary.
   - `KSFunctionDeclaration.findOverridee()` was considered and rejected as the signal: whether
     it resolves for klib-loaded synthetic delegation forwarders is unverified (ADR-066 did not
     exercise it, and no spike could run here), and the chosen rule does not depend on it.
2. **Value-class export names gain the secondary-constructor overload numbering.** Verified: no
   overload scheme exists anywhere for methods — value-class methods use `"${prefix}_$name"`
   (`ForwardCallablePlanner.kt:379`), ordinary-class methods use the same shape
   (`ForwardCallablePlanner.kt:484`), and two same-class overloads crash the catalog's
   duplicate-symbol guard (`ForwardKotlinPlanEmitter.kt:460-470`) or the ABI contract's
   duplicate-export guard (`ForwardAbiContract.kt:79-80`) rather than colliding silently. The only
   in-repo precedent is the secondary-constructor numbering (`${prefix}_create_${index + 2}` with
   symbol suffix `_${index + 2}`; verified `ForwardCallablePlanner.kt:276-279`,
   `ValueClassExports.kt:57-81`, `CirClassTranslator.kt:1615-1639`), and this follow-up mirrors
   it: the first declared same-name overload (in `getAllFunctions()` order) keeps
   `${prefix}_$name` and symbol `$owner.$name`; the n-th further one gets `${prefix}_${name}_$n`
   and symbol `$owner.${name}_$n` (n starting at 2). The C# surface stays natural overloads (one
   shared public name); the `@CName` symbols, the `DllImport` EntryPoints and the private extern
   *names* (`Native_Describe` / `Native_Describe_2`) carry the number. The extern name has to
   follow the numbering too: two overloads whose public C# signatures differ can still share a
   wire shape (`int` for both an `Int` and an enum parameter), and one extern name declared twice
   is CS0111. Because the Kotlin symbol now carries a suffix the Kotlin *call site* must not,
   `ForwardInvocation` gained a `member` slot holding the declared member name.

   The plan lookup sites did **not** re-derive the numbering, as this amendment first proposed.
   `ValueClassExports.kt` and `CirClassTranslator.kt`'s value-class member loops now iterate the
   catalog's VALUE_CLASS-origin plans for the owner
   (`ForwardCallablePlanCatalog.valueClassProperties` / `valueClassMethods`) instead of deriving
   `planFor("$qualifiedName.$name")` per `getAllFunctions()` entry. Re-derivation was not merely a
   three-site lockstep to maintain: keyed by simple name, it is *unable* to express the numbering
   at all, and a second same-name declaration re-emits the first one's plan. Constructors keep
   their existing per-declaration lookup, because the reference-underlying branch still needs the
   declaration for its legacy adapter.

   C# cannot overload on reference nullability, so value-class methods also gained the ADR-034
   `ERROR_CSHARP_SIGNATURE_COLLISION` check the duplicate-constructor path already had: two
   overloads rendering identical C# parameter types fail generation instead of emitting CS0111.

Ordinary-class overloads remain out of scope for this amendment and are recorded in Consequences
as a latent bug.

Residual (still accepted) limitations after the amendment: a same-arity overload whose colliding
position is a supertype type parameter over-drops conservatively (loud diagnostic, non-colliding
name as workaround), and a declared member that *is* the inherited signature still skips by
design.

## Consequences

- ROADMAP Phase 4's "decide whether `getAllFunctions()` supertype members are in the export set"
  closes as decided-no; ADR-064's carried-forward scope note resolves here.
- `StoryUri` (`test-models/.../StoryUri.kt`) and `Tier1NamedSkipDiagnosticsTest` become the
  permanent guard for this behaviour, not a stopgap.
- Overload mangling for value-class export names and a signature-level (rather than simple-name)
  inherited signal, originally deferred here, are now an **approved follow-up**: see the
  Amendment in the Decision section. The decision itself is unchanged.
- Latent inconsistency, out of scope here but recorded: ordinary classes drop inherited members
  **silently** (no diagnostic), while value classes warn. If diagnostics parity is wanted,
  ordinary classes should gain the same `SKIPPED_INHERITED_MEMBER` entry, not the other way
  around.
- Latent bug, out of scope here but recorded (verified by source): **ordinary classes have no
  overload scheme at all.** `classEntries` names every method export `"${prefix}_$name"`
  (`ForwardCallablePlanner.kt:484`) and keys every plan by `"$owner.$name"`, so two same-class
  overloads produce duplicate symbols and duplicate export names, tripping
  `planFor`'s `require(matches.size <= 1)` (`ForwardKotlinPlanEmitter.kt:465`) or the ABI
  contract's duplicate guards (`ForwardAbiContract.kt:79-80`) — a generation-time crash, not a
  silent collision. No fixture declares same-class overloads today, which is why the suite is
  green. The amendment's numbering scheme is the natural fix when ordinary-class overloads are
  taken up.

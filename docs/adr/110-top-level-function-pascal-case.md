# ADR-110: Forward, top-level functions PascalCase in C# like every other position

## Status
Accepted

## Context

Every forward position renders its C# member name in PascalCase except one. `object` methods
(`ForwardCallablePlanner.kt:1027`), companion methods (`:1067`), class methods (`:663`, `:716`,
`:790`), extension functions (`:1341`, legacy `CirTranslator.kt:636`), top-level properties
(`ForwardPropertyPlanner.kt:239`) and even top-level `suspend` functions
(`CirFunctionTranslator.kt:642`) all apply `replaceFirstChar { it.uppercase() }`. The plain
top-level function route does not: `topLevelEntry` (`ForwardCallablePlanner.kt:1002`) spells the
public name `toCName(name).csharpIdentifier()`, so `fun add(a: Int, b: Int)` in `Arithmetic.kt`
surfaces as `Arithmetic.add(3, 4)` next to `CatRegistry.Clear()` and `Properties.CatBreed`. All of
this is **verified** by reading the planner source; no build was run for this ADR (the Gradle
lock was held by another agent).

The split is old enough to be documented as a convention in six places
(`IntegrationTests/MethodParameterMarshallingTests.cs:141`, `PlatformTests.cs:18`,
`ValueClassParameterTests.cs:68`, `FunctionDefaultParameterTests.cs:169`,
`ExpectActualResidualsTests.cs:18`, `docs/topics/expect-actual.md:128`), and
[ADR-074](074-expect-actual-declarations.md) had to inherit it for `actual fun` rather than fix it
for one declaration kind. [ADR-007](007-top-level-function-class-naming.md) decided the *class*
name (file name, `Kt` on conflict) and said nothing about member casing; the camelCase is an
accident of the first implementation, not a decision. The `object`-method row of the roadmap
called the identical inconsistency "off-idiom" and fixed it. This ADR closes the last position.

.NET's own convention is PascalCase for every method
(<https://learn.microsoft.com/en-us/dotnet/standard/design-guidelines/capitalization-conventions>),
and Kotlin's other exports already do this translation where the host convention differs: ObjC
export keeps camelCase because ObjC is camelCase, Swift export likewise; JVM keeps camelCase because
Java is camelCase. C# is the one host whose convention differs, and the rest of this bridge already
follows it. There is no idiomatic-C# alternative to weigh, only the cost of the rename.

Constraints:

- The native `@CName` export is a wire-level contract shared with [ADR-054](054-reverse-bridge-registration-observability.md)'s
  contract hash and the ADR-095 duplicate-export guard. It must not change.
- Release 0.5.0 shipped at `0ef09d3`. This lands in 0.6.0 as a breaking rename of every C# call
  into a top-level function.

## Alternatives Considered

### 1. PascalCase the public C# name on every top-level function route (chosen)

`publicName = name.replaceFirstChar { it.uppercase() }`, byte-identical to `objectEntries` and
`companionEntries`. The export name, symbol, and ADR-095 numbering are untouched.

Pros: one convention across the whole forward surface; matches .NET guidelines; removes the last
consumer-visible "this is a wrapper around Kotlin" tell; deletes the only caller of the private
`csharpIdentifier()` helper (`ForwardCallablePlanner.kt:2649`).
Cons: breaking for every consumer of a top-level function (roughly 390 call sites in 47 files in
this repository's own `IntegrationTests` alone); introduces two new C# name-collision classes
(see Decision).

### 2. Keep camelCase

Zero migration. Cons: the inconsistency stays documented in six places as a convention, and every
new route (ADR-074 was the latest) has to inherit it deliberately. Rejected: the same argument
already lost for `object` methods.

### 3. Emit both names (PascalCase plus an `[Obsolete]` camelCase forwarder)

Softens the break for one release. Cons: doubles the top-level method surface, the camelCase
forwarder of `fun add()` next to a PascalCase `Add()` is exactly the shape C# developers read as
generated noise, `[Obsolete]` on hundreds of members produces warning storms in consumers, and the
forwarders must be removed in 0.7.0 anyway, so the break is delayed, not avoided. Rejected; the
project is pre-1.0 and has shipped hard renames before (`docs/topics/enums.md:482`).

## Decision

PascalCase the public C# name of every top-level function; leave the native export alone.

### Sites (all verified by reading source)

Plan route, `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCallablePlanner.kt`:

- `topLevelEntry` (`:995-1007`). Change `:1002` from
  `toCName(function.simpleName.asString()).csharpIdentifier()` to
  `function.simpleName.asString().replaceFirstChar { it.uppercase() }`. This is the single site
  for: declared top-level functions (`:415`), ADR-096 omitting overloads (`:427`, same builder
  with `omitted > 0`), ADR-095 numbered overloads (the `_$n` lives on `exportName` `:1003` and on
  the symbol, never on `publicName`; the C# projection spells the *public* method from
  `plan.publicSignature.name` (`ForwardCirPlanProjection.kt:218`, `:337`) and only the private
  extern carries the number via `overloadSuffix()` (`:196`, `:259`, `:441`, `:485-489`), so C#
  sees a natural overload set `Add(int, int)` / `Add(int, int, int)` exactly as ADR-095 describes
  for objects), ADR-074 `actual fun` (an `actual` rides the ordinary `functions` list; only its
  file-class grouping consults the expect index, `CirTranslator.kt:102-116`), and the top-level
  nullable-primitive two-call shape (`staticEntry` `:1113` hands the same `publicName` to
  `topLevelNullablePrimitivePlan`).
- Drop `toCName` from the public name deliberately. `toCName` suffixes C reserved words
  (`Reserved.kt:25`), so today `fun while()` cannot exist in Kotlin but `fun int()` renders
  `int_` in C#. `objectEntries` and `companionEntries` never applied it to the public name and
  the extension route (`:1341`) does; after this ADR the top-level route matches the object route.
  The export name keeps `toCName` (`:1003`) because that is the C symbol.
- Keyword escape order. `csharpIdentifier()` (`:2649`) prefixes `@` when the name is in the
  all-lowercase `CSHARP_KEYWORDS` set. A PascalCased name is never a C# keyword (every C# keyword
  is lowercase, **verified** by the set at `:2651` and `Reserved.kt:11-21`), so `fun lock()`
  goes from `@lock` to `Lock` with no escape, and the helper's only caller disappears. Delete
  `csharpIdentifier()` and the `CSHARP_KEYWORDS` companion set, or keep them only if a test pins
  them; do not run the escape *before* the case change, which would produce `@Lock`.

Legacy routes, `.../processor/cir/CirFunctionTranslator.kt`:

- `translateFunction` (`:46-56`), reached only through `translateSpecializedFunction` (`:26-44`,
  sealed-return and generic-instantiation-return top-level functions; caller
  `CirTranslator.kt:173`). `:56` becomes `toCSharpName(cname).replaceFirstChar { it.uppercase() }`,
  the same expression the suspend route already uses at `:642`. **Load-bearing, not spiked**:
  `:101` sets `entryPoint = if (csName != cname) cname else null`, and the `null` branch is
  presumably "use the C# extern's own name as the native symbol". After PascalCasing, `csName`
  always differs from `cname`, so the `entryPoint` is always explicit; that is the safe direction
  (the extern is always bound to the real C symbol), but the implementing agent must confirm the
  `null` branch's meaning before relying on it, because the failure mode is a `DllImport` that
  resolves `Add` against a library exporting `add` and throws `EntryPointNotFoundException` at
  first call.
- `translateGenericFunction` (`:691-696`, generic-declaration top-level functions; caller
  `CirTranslator.kt:197`). `:696` becomes `toCSharpName(funcName).replaceFirstChar { it.uppercase() }`.
  Check the same `entryPoint` question on this route.
- `translateSuspendFunction` (`:634-642`) already PascalCases (and suffixes `Async`,
  `AotThunkGenerationTests.cs:307` calls `AsyncFunctions.FetchGreetingAsync`). No change.
- `translateExtensionFunction` (`CirTranslator.kt:636`) already PascalCases. No change.

No other site decides a top-level function's C# name. `Flow`-returning and lambda-parameter
top-level functions have no route of their own; they ride `functions` and land either on the plan
or on `translateSpecializedFunction` (**inferred** from the partition at `CirTranslator.kt:39`,
which names only `suspendFunctions` and `genericFunctions` as separate lists).

### Name collisions this change creates

C# forbids two members of one type sharing a name unless both are methods (CS0102), and forbids a
member named like its enclosing type (CS0542). Today camelCase keeps top-level functions out of
both. After this ADR:

1. **`val name` + `fun name()` in one file** (Kotlin allows it; properties and functions are
   separate namespaces). Renders `Name { get; }` and `Name()` in one static class: CS0102.
2. **`fun greeting()` in `Greeting.kt`**. Renders `Greeting.Greeting()`: CS0542. This one is
   more likely in practice than (1), and ADR-007's `Kt` suffix does not fire because nothing
   else claims the class name.

A scan of every `.kt` in `test-library`, `sample`, `smoke-test`, `test-models` found no
instance of either shape today (script in this ADR's research thread), so no fixture needs
renaming. Both are real hazards for consumers, so the guard ships with the rename:

- The ADR-034 `emitCsharpSignatureCollisions` check (`CirClassTranslator.kt:1326`) runs per
  static class on `CirMethod` only (**verified**), so neither shape is caught today. Extend it,
  or add a sibling, to compare against the container's own name and its property names.
- **Shipped resolution diverges from the skip-the-function plan above, per shape:**
  - **CS0102** (`val name` + `fun name()`): fatal `ERROR_CSHARP_NAME_COLLISION`, naming both
    declarations, hint "rename the Kotlin function". Skipping was not implementable here either:
    a planned callable is projected into both the Kotlin `@CName` export and the C# half by
    ADR-055's contract, which requires the callable in each, so it cannot be dropped from only
    one side.
  - **CS0542** (`fun greeting()` in `Greeting.kt`): **not** a skip. The whole file class is
    renamed with ADR-007's existing `Kt` suffix (`GreetingKt`), exactly as if a same-named type
    had claimed it, and every member of that file moves with it. An `INFO_FILE_CLASS_RENAMED`
    note fires, naming the call site (`GreetingKt.Greeting(...)`) and citing both ADR-007 and
    this ADR. This was possible here (unlike CS0102) because the rename is a class-level
    decision made once, in `CirTranslator`, after ADR-007's own name resolution, rather than a
    per-callable skip; a skip was also not obviously better than a rename consumers can still
    call through.
  - **CS0119**, a third collision this ADR's research missed: a sealed-returning function whose
    PascalCased name equals its own return type (`fun issue38State(): Issue38State`) generates a
    method body whose unqualified `Issue38State.FromHandle(...)` call resolves to the method
    itself, not the type. Fixed by `global::`-qualifying the return type and the `FromHandle`
    call on that legacy branch, not by renaming or skipping anything.

### Consumer API

```csharp
// Kotlin: fun add(a: Int, b: Int): Int  in Arithmetic.kt
Arithmetic.Add(3, 4);                // was Arithmetic.add(3, 4)
Arithmetic.Add(3, 4, 5);             // ADR-095 overload, still no visible number
Mappings.NullableInt(true);          // two-call shape, same rename
ClinicSample.PatientNameLength(p);   // object-handle parameter, same rename
Platform.PlatformName();             // ADR-074 actual fun, same rename
Locks.Lock();                        // was Locks.@lock
```

Native side, unchanged: `@CName("add")`, `@CName("add_2")`, `@CName("nullableInt_value")`.

### Wire

Nothing crosses differently. `exportName`, symbol, ADR-054 contract hash, ADR-095 numbering:
all untouched. This is a C#-surface-only rename.

## Consequences

- **Breaking in 0.6.0.** Every C# call to a top-level function is renamed. In this repository:
  about 390 regex hits across 47 files under `IntegrationTests/*.cs` (pattern
  `\b[A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*\(`, includes a handful of non-top-level hits), 0 in
  `GeneratedBindingsCheck`, `AotSmokeTest`, `sample`; about 140 raw hits across 25 files in
  `docs/topics/*.md`, `README.md`, `FEATURES.md`, many of them Kotlin-side false positives
  (`StableRef.create`, `Result.failure`, reverse `Template.parse`) that must be triaged by hand.
- The six convention comments listed in Context are deleted or inverted;
  `docs/topics/top-level-declarations.md:147` ("the public name keeps today's camelCase") and
  `docs/topics/expect-actual.md:128` are rewritten.
- Tier 1 tests that pin camelCase flip (8 files): `Tier1OrdinarySurfaceTest.kt:323-324`,
  `Tier1StructuralInteropCsTest.kt:123` (and the doc comment at `:54`),
  `Tier1ValueClassParameterTest.kt:49,51`, `Tier1BareNullableEnumTest.kt:138`,
  `Tier1ExpectActualDeclarationsTest.kt:251`, `Tier1UuidMappingTest.kt:116`,
  `Tier1ValueClassEnumUnderlyingTest.kt:163`, `ForwardPhase10LegacyTwoCallTest.kt:37` (its
  hand-built plan at `:23` passes `publicName = "nullableInt"` directly, so that fixture is
  updated by hand, not by the planner). `ReservedTest.kt:53` pins `toCSharpName("struct")` and
  is unaffected (the helper stays for legacy routes).
- Two new diagnostics ship, one per collision shape, and neither is a skip: `INFO_FILE_CLASS_RENAMED`
  for CS0542 (the file class renames to `Kt`, the callable still binds) and fatal
  `ERROR_CSHARP_NAME_COLLISION` for CS0102 (build fails, naming both declarations). A third shape,
  CS0119 on a sealed-returning function whose name equals its own return type, needed a
  `global::`-qualification fix rather than a diagnostic. A Tier 1 fixture pins each of the three.
- `docs/archive/migration.md` is the internal marshalling-centralization checklist, not a consumer guide. The
  0.6.0 release notes carry the entry below; if a consumer-facing migration page is added, it
  goes there too.

### Release note / migration entry (0.6.0)

> **Top-level functions are PascalCase in C#.** `fun add(a: Int, b: Int)` is now
> `Arithmetic.Add(3, 4)`, not `Arithmetic.add(3, 4)`. Same for `actual fun`, overloads,
> default-parameter overloads and nullable-primitive returns. Native exports and the
> registration contract are unchanged, so a consumer that only calls through the generated
> C# needs a rename and a rebuild; nothing else moves. A Kotlin `fun lock()` that was
> `@lock` is now `Lock`. Two new build-time diagnostics: a top-level function whose PascalCase
> name equals a top-level property's in the same file (`val name` + `fun name()`) now fails the
> build (`ERROR_CSHARP_NAME_COLLISION`, rename the function); a top-level function whose
> PascalCase name equals its own file class (`fun greeting()` in `Greeting.kt`) still binds, under
> a renamed `Kt`-suffixed class (`GreetingKt.Greeting(...)`), with an `INFO_FILE_CLASS_RENAMED`
> note.

## Amendment (2026-09-20): an `object`'s own properties join its methods on the static class

An `object`'s own `val`/`var` properties reach C# as static properties on the same static class
its methods already land on (ROADMAP Phase 4, "object properties"). Before this, `CirObject`
carried methods only; `object Jar { val count: Int }` generated nothing on either side of the
bridge, and nothing said so.

**Mechanism.** A new `ForwardPropertyPosition.OBJECT`, receiver `ForwardPropertyReceiver.Static(owner)`
(the same static-owner receiver a companion property already uses; there is no singleton handle on
the wire), exports `${nativePrefix}_get_/_set_$name` with ADR-117 owner tags. The planned property is
projected into `CirObject`'s existing `methods: List<CirMember>` — no `CirObject` field, no CIR model
change; everything downstream already accepts a `CirProperty`/`CirConst` in that list. `OBJECT` is a
new position rather than a reuse of `COMPANION` so that a `Flow`/`StateFlow`/lambda-typed object
property is *named* skipped rather than silently dropped (see Consequences). All type coverage — the
ADR-075 getter/setter independence, nullable fan-out, enums, collections, and handle-typed properties
(a fresh owned wrapper per read) — comes from the one shared property plan every other static and
instance position already uses.

**`const val`.** Renders `public const int Capacity = 12;`, exactly like a companion `const val`.
This exposed and fixed a pre-existing bug: `extractConstValue` (`cir/CirTranslator.kt`) found a
`const val`'s literal by regex over the *whole source file*, matching the first declaration with that
bare name. A second object (or companion) in the same file with a same-named `const val` therefore
silently rendered the first one's value. The search now starts at the declaration's own file
location; a declaration with no location (a klib dependency) keeps the old, first-match behaviour, so
a klib `const val` on an object is still out of scope for this fix.

**Inherited members flatten, properties and methods alike.** `object Jar : Base("x")` exposes
`Jar.Origin` for `Base`'s own `origin` property and `Jar.Restock()` for `Base`'s own method: a C#
static class can neither extend a class nor implement an interface, so an inherited member has no
other carrier, and the class route's own `isForwardPlannableMemberOf` predicate (superclass = null)
already does this re-homing for every other base-less owner. Object methods used to be declared-only;
they now flatten too. `kotlin.Any`'s members stay excluded, as everywhere else.

**Every dropped supertype relation is named.** A new WARNING reuses the existing
`SKIPPED_UNEXPORTED_SUPERTYPE` kind (no new kind): the object renders as a static class that "cannot
extend"/"cannot implement" the declared supertype, its members still bind as statics, and the hint
says the `is`/`as` relation and any dispatch through the supertype are gone. Unlike the class-route
use of this kind, this fires **whether or not the supertype is itself exported** — a static class has
no base-list slot for *any* supertype, not only an out-of-scope one. Sealed `object` arms (ADR-009,
declared as a real `sealed class` extending its base) and `kotlin.Any` are excluded.

**Fatal name collision.** `val count` beside `fun count()` on one object — declared or inherited —
renders one C# name (`Count`) twice and fails the build with `ERROR_CSHARP_NAME_COLLISION` (CS0102),
naming the owner and both Kotlin declarations, the same fatal treatment this ADR's own top-level-name
collision gets and ADR-113 gives an interface. This is a knowing, accepted cost: a library with this
shape built silently before this amendment (the property was simply absent) and fails after it.

**`Flow`/`StateFlow`/lambda-typed object properties** are a named `SKIPPED_UNSUPPORTED_PROPERTY` skip:
no static-owner adapter exists to re-emit them the way the class route's adapters do. Fixing this
also exposed and closed a pre-existing, unrelated silent drop: `recordDropped`'s exemption for these
protocol types previously covered every position except `EXTENSION`, so a companion or a top-level
`Flow`/`StateFlow`/lambda property vanished from C# with no diagnostic at all, for the same "no
adapter" reason. The exemption is now `position == CLASS` only, so a library with such a companion or
top-level property now sees a warning it never saw before.

**`lateinit var` read before assignment** reaches C# as a `KotlinException` through the ordinary error
envelope; `UninitializedPropertyAccessException` is not in the ADR-029 mapping table, so it surfaces
as the base type.

**Leak coverage.** `LiveHandleTests.cs` gains row 11 (`ObjectHandleProperty_StaticGetter_ReturnsToBaseline`)
and row 11a (`ObjectCollectionProperty_StaticGetter_ReturnsToBaseline`): the first coverage of any
static-getter handle mint at all (a companion or top-level getter had none either).

**Cosmetic, known and not tracked further.** A generated `object`'s body no longer ends with a blank
line before its closing brace; every other container (class, interface, file static class) still
does.

**Declined, not deferred:** a member extension property declared inside an object (no route for a
member extension's two receivers); a klib-declared object's `const val` keeps the pre-existing
first-match literal lookup (no file location to anchor to).

## Amendment (2026-09-26): every class-family route and inherited members

ROADMAP line 32 recorded that the CS0102 property/method name-collision guard this ADR introduced
for the top-level file class, and the `object` amendment above extended to a static class, still had
no equivalent on the **ordinary class** route (a class, its companion, a sealed base and arms, a
`value class`, a generic class), and that a related but distinct shape was unguarded on every route:
a declared member taking the C# name of an **inherited** member of the other kind compiles as CS0108
hiding rather than failing.

**One shared guard, not a sixth copy.** `emitMemberNameCollisions`
(`nuget-processor/.../cir/CirMemberNameCollisions.kt`, new) now runs, over each route's own
*projected* member list, at every C# type that renders both properties and methods: `translateClass`
(instance, companion, async, Flow and callback members together, since they land on one C# type),
the sealed base and each arm, `translateValueClass` (its underlying property included), and
`translateInterfaceBackingClass`. The two existing copies (`object`, the file class above; the
interface route, Decision E of [ADR-113](113-interface-declaration-on-the-forward-plan.md)) migrate
onto the same helper, keeping their own reason and hint wording so the tests that pin it stay green.
The enum-entry guard (issue #285's amendment) stays separate; it compares entries against each other,
a different rule.

- **Rule 1 (CS0102), unchanged in shape, generalized in reach:** a C# name held by a property or
  `const` and by any other member of the same type. This also closes the folded backlog item
  `docs/backlog/const-val-no-cross-owner-collision-guard.md`: two `const val`s meeting after casing
  (`MAX_RETRIES` and `maxRetries` both to `MaxRetries`) collide under the same rule, whether on an
  `object`, a companion, or the top-level file class, and so does an instance property against a
  companion property of the same name.
- **Rule 2 (CS0108), new:** a declared member whose C# name equals an inherited member of the
  *other* kind, in either direction, checked by a post-pass (`CsMemberRegistry.emitInheritedCollisions`)
  over each kept-base chain once every class, sealed base and arm has translated (a base can
  translate after its own subclass). A method hiding an inherited property also makes the property
  unreadable through the derived type (`n.Value` is `CS0428`); the reverse direction (a property
  hiding an inherited method) leaves the method callable, but CS0108 still fails
  `nugetCompileInterop` (ADR-138) and any consumer build under `TreatWarningsAsErrors`, so both
  directions are fatal, not one warned and one failed.

**Post-projection is load-bearing, not incidental.** The guard runs over each route's rendered
member list, never over Kotlin declarations directly. The shipped `Issue112Sample.kt`
([ADR-113](113-interface-declaration-on-the-forward-plan.md) post-implementation note 4) keeps
`val collarTag` beside an unbridgeable `fun collarTag(code: Int): Sequence<Int>` on the class route
too; only `CollarTag` survives projection, so it stays green. A guard placed before the plan drops
unbridgeable members would fail that shipped, working fixture.

**Knowing cost, accepted the same way as the `object` amendment above.** A class-route CS0102
collision already failed the consumer's own C# compile before this change; the guard only moves the
failure earlier and names it. The CS0108 shape is the one that actually changes behavior: a library
with it used to build fine whenever `dotnet` was absent or `nugetCompileInterop` was skipped
(ADR-138's opt-in strict mode is still a separate ROADMAP item), and now fails generation instead.

**Message fix, no behavior change.** An overloaded interface method's collision hint used to name
the numbered exported symbol (e.g. `tag_2`) when two same-named overloads existed; it now reads the
plan's own Kotlin member name first, falling back to the symbol's tail only when the plan has none.

### Alternatives rejected

- **A `new` modifier on the derived member,** to silence CS0108 rather than fail: rejected by spike.
  The consumer's `n.Value` read still resolves to the method, not the inherited property (`CS0428`),
  so it papers over the exact break this guard exists to name.
- **Warn-only for the "benign" reverse direction** (a declared property hiding an inherited method,
  where the method stays callable): rejected. CS0108 still fails `nugetCompileInterop` and any
  consumer build under `TreatWarningsAsErrors`, so "benign" only holds for a caller who never invokes
  the hidden member; the build still breaks.
- **Rename or skip the colliding member:** rejected for the same reason as the original Decision:
  a rename is a silently different API, and ADR-055's both-halves contract forbids exporting a
  planned callable from Kotlin while dropping it from the C# side alone.
- **A pre-projection guard over `KSClassDeclaration`s** instead of the rendered member lists:
  rejected. It would fail the shipped `Issue112Sample.kt` fixture, which relies on one of the two
  colliding members already having been dropped by the plan.

### Files touched

`cir/CirMemberNameCollisions.kt` (new: the shared helper, `CsMemberRegistry`, and the Kotlin-spelling
bookkeeping); `cir/CirClassTranslator.kt` and `cir/CirTranslator.kt` (new call sites per route, the
two migrated copies deleted, and the merged-file-class check, which now also covers a suspend or
generic top-level function and a `{Receiver}Extensions` merge since it runs once over each finished
`CirNamespace` rather than per contributing loop); `forward/ForwardDiagnostic.kt` kdoc. No fixture
changes (every shape here is fatal, so it cannot live in `test-library`); new Tier 1 coverage in
`Tier1MemberNameCollisionTest.kt`.

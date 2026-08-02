# ADR-074: Narrow `List<T>` input components to what the C# write side can box

## Status

Accepted

## Context

Forward direction, Kotlin to C#. ROADMAP.md line 93, the first deferred item of
[ADR-073](073-map-and-set-parameters.md).

ADR-073 shipped `Map`/`Set` parameters gated on a new, deliberately narrow input-position predicate,
`isWrappableComponent()` (`forward/ForwardCallablePlanner.kt:1225-1234`, **Verified** by reading the
shipped source). It left the `LIST`/`MUTABLE_LIST` branch of `inputSkipReason()`
(`:1291-1305`, **Verified**) on the pre-existing, much wider `isBridgeableComponent()`
(`:1186-1211`, **Verified**), because narrowing it removes callables that bind today.

This ADR decides that narrowing.

### What this item is, and is not

It makes six `List<T>` parameter shapes **skip with a `SKIPPED_UNSUPPORTED_INPUT` diagnostic**
instead of binding. It does **not** make any of them work. Making them work is ROADMAP sub-bullets
94, 95 and 96 (nullable components, enum/narrow-primitive components, value-class/nested-collection
components) and is explicitly untouched here.

### The six shapes, and what each does today

The two gates disagree. `isBridgeableComponent()` admits a component; then two downstream sites
decide whether that admission survives.

Site 1, the Kotlin lowering, `elementKotlinTypeName`
(`forward/ForwardKotlinPlanEmitter.kt:398-405`, **Verified**, quoted in full):

```kotlin
private fun elementKotlinTypeName(type: BridgeType): String = when (type) {
  BridgeType.String -> "kotlin.String"
  BridgeType.Char -> "kotlin.Char"
  is BridgeType.Primitive -> "kotlin.${type.kind.simpleKotlinName()}"
  is BridgeType.ObjectHandle -> type.qualifiedName
  is BridgeType.Enum -> type.qualifiedName
  else -> error("Forward Kotlin plan emitter has no element type name for $type")
}
```

Note it **has** branches for `Char`, for every `PrimitiveKind` (`simpleKotlinName()` at `:407-419`
covers all eleven), and for `Enum`. It has none for `Nullable`, `ValueClass` or a nested
`Collection`. That is exactly the 3/3 split.

Site 2, the C# boxing switch, `Wrap<T>` (`cir/CirMarshalRenderer.kt:296-308`, **Verified**): six
`typeof(T) ==` branches (`string`, `int`, `long`, `float`, `double`, `bool`), then a reflective
`GetField("_handle")` fallback that `throw new NotSupportedException(...)` when the field is absent.

| Kotlin parameter | `isBridgeableComponent()` | `elementKotlinTypeName` | `Wrap<T>` | Net effect today |
|---|---|---|---|---|
| `List<String?>` | admits (Verified) | `error(...)` (Verified) | never reached | **`packNuget` crashes**, whole KSP run dies |
| `List<StoryCode>` (value class) | admits, via `underlying.isBridgeableComponent()` (Verified) | `error(...)` (Verified) | never reached | **`packNuget` crashes** |
| `List<List<String>>` (nested) | admits (Verified) | `error(...)` (Verified) | never reached | **`packNuget` crashes** |
| `List<Mood>` (enum) | admits (Verified) | `type.qualifiedName` (Verified) | no branch, no `_handle` on a C# `enum` | binds, compiles, **throws `NotSupportedException`** per element (Inferred) |
| `List<Short>` | admits (Verified) | `"kotlin.Short"` (Verified) | no branch, no `_handle` on `short` | binds, compiles, **throws** (Inferred) |
| `List<Char>` | admits (Verified) | `"kotlin.Char"` (Verified) | no branch, no `_handle` on `char` | binds, compiles, **throws** (Inferred) |

**The three crashing shapes crash before any C# is written.** `error(...)` inside the plan emitter is
not caught anywhere; the KSP run aborts and `packNuget` fails with a message about a plan emitter, not
about the consumer's `fun f(xs: List<String?>)`. That is the worst diagnostic in the repository.

**There is one shape where the current binding is usable, and it is degenerate.** `CreateList`'s body
(`cir/CirMarshalRenderer.kt:310-315`, **Verified**) is

```csharp
IntPtr listHandle = NugetListNative.Create();
foreach (T value in values) NugetListNative.Add(listHandle, Wrap(value));
return listHandle;
```

`Wrap` is only reached per element. So `patient.SetMoods(Array.Empty<Mood>())` succeeds today, end to
end: an empty `IReadOnlyList<Mood>` never calls `Wrap`, Kotlin's `.map { it as Mood }` over an empty
list is fine, and the callee sees an empty list. **Verified by source reading**, not executed. Anything
non-empty throws. Narrowing removes even the empty-list call. This is the only behaviour a real
consumer could conceivably be relying on, and relying on it means calling a method that only works
when you pass nothing.

### The claim that was inferred, now verified by execution

ADR-073 labelled the runtime-`NotSupportedException` rows Inferred, from reading both sites, not
spiked. This ADR's implementation settled it: a `csharp-dev` agent added a temporary
`Patient.addMoods(moods: List<Mood>)` fixture and an `IntegrationTests` call, ran it through
`scripts/verify.sh`, and read the real exception before deleting the fixture as the ADR instructed.

**Verified by execution.**

- `Patient.addMoods(List<Mood>)` called with a **non-empty** list throws
  `System.NotSupportedException: Cannot pass Mood to a Kotlin collection`.
- Called with an **empty** list, it **succeeds and returns 0**: `CreateList`'s per-element `foreach`
  never reaches `Wrap<T>` for an empty collection, so the degenerate empty-list case described above
  is real, not just a source-reading inference.

This confirms the option 1 framing: `List<Mood>` (and by the same `Wrap<T>` mechanism, `List<Short>`
and `List<Char>`) is a guaranteed-throwing binding for any real, non-empty call, and narrowing it away
trades that runtime throw for a compile error. The one thing narrowing removes is the empty-list
call, which is now confirmed to have worked.

Everything else in this ADR is Verified against repo source, cited inline.

## Alternatives Considered

### 1. Narrow all six via `isWrappableComponent()`, matching `Map`/`Set` exactly (chosen)

One clause change: the `LIST`/`MUTABLE_LIST` arm of `inputSkipReason()` starts consulting
`element?.isWrappableComponent()` the same way the `SET`/`MUTABLE_SET` arm already does.

**Pros.**

- One predicate governs every collection kind at an input position. `isWrappableComponent()` stops
  being "the map/set rule" and becomes "the rule", which is what it was always documented as being
  (its KDoc at `:1213-1224` already says it is deliberately not applied to `List`, i.e. it is already
  written as a temporary carve-out, **Verified**).
- Removes the only remaining `packNuget`-crashing forward input shape. A consumer who writes
  `fun f(xs: List<String?>)` gets a warning naming `f` and its source line, not a dead build.
- Removes three C# methods that are guaranteed to throw on any real call. GOALS #2 ("generated bridge
  should be C# idiomatic") is not served by IntelliSense offering `AddMoods(IReadOnlyList<Mood>)`
  when calling it always fails.
- The diagnostic is free. See "the (c) collapse" below.

**Cons.**

- It is a source-breaking change for any consumer who compiled against a `List<Mood>` /
  `List<Short>` / `List<Char>` parameter. Their C# stops compiling (CS1061, the method is gone)
  instead of throwing at runtime. Compile-time is the better failure, but it is still a break.
- It removes the empty-list call described above.
- The `Char`, narrow-primitive and `Enum` branches of `elementKotlinTypeName` become unreachable
  (nothing else calls it, **Verified**: all nine call sites at `:661-687` are input lowerings). Keep
  them; they are the ready-made half of ROADMAP items 95 and 96, and deleting them would just have to
  be undone.

### 2. Narrow only the three that crash `packNuget`

Add a crash-only predicate ("has an `elementKotlinTypeName` branch"), leave `List<Mood>`,
`List<Short>` and `List<Char>` binding.

**Pros.** Strictly smaller blast radius: nothing that compiles today stops compiling. It fixes the
loudest failure (a dead build) without touching a shipped C# surface.

**Cons.**

- It splits the feature family on an **observability accident**, not a capability boundary. The
  reason `List<Mood>` binds and `List<StoryCode>` does not is that someone wrote an `Enum` branch in
  `elementKotlinTypeName` and did not write a `ValueClass` one. Neither has a `nuget_wrap_*` export.
  Encoding that accident as a predicate makes it permanent.
- It requires inventing a *second* input-position predicate whose definition is "the set of things
  the Kotlin emitter happens to have a `when` branch for". `isWrappableComponent()` at least names a
  real capability (the write side can box it). A crash-only predicate names nothing.
- It leaves a generated C# API that lies. `IReadOnlyList<Mood>` in IntelliSense with a guaranteed
  `NotSupportedException` behind it is the exact failure ADR-064 was written to eliminate: "never an
  `IntPtr`/`"0"` fallback", member absent rather than member broken
  (`ForwardDiagnostic.kt:97-99`, **Verified**).
- The three shapes it preserves still have to be removed later when ROADMAP 95 lands the
  `nuget_wrap_*` exports, or rather they get *fixed* then, and until then they are a trap.

Rejected. If the human wants a smaller change, the honest smaller change is to ship option 1 and
accept the break, not to preserve three broken bindings.

### 3. Narrow all six but route the loss through a bespoke ADR-064 diagnostic surface

Same as option 1, plus a new diagnostic kind (e.g. `SKIPPED_UNWRAPPABLE_COMPONENT`) or an aggregate
"these members were removed in this version" manifest line.

**This is not a separate option. It collapses into option 1.** The diagnostic already fires, for
free, with no new code. **Verified** chain:

1. The narrowed clause returns `ForwardPlanSkipReason.COLLECTION` (`ForwardCallablePlanner.kt:1301`,
   `:1304`, the shape option 1 copies).
2. `plannedCallable` turns a non-null `inputSkipReason()` into a
   `ForwardCallableCatalogEntry.Skipped` carrying the callable symbol and its `KSNode`
   (`:779-785`, **Verified**).
3. `warnDroppedForwardCallables` maps every dropped callable through `toDiagnosticKind()`
   (`NugetProcessor.kt:102-116`, **Verified**).
4. `ForwardPlanSkipReason.COLLECTION -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT`
   (`ForwardDiagnostic.kt:130`, **Verified**).
5. `format()` renders `[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Patient.addMoods(...): its
   COLLECTION type combination is not supported. <hint>\n    at <file>:<line>`
   (`ForwardDiagnostic.kt:83-94`, **Verified**).

So the consumer already gets a named kind, the callable, and the Kotlin source location.

**One precision correction, load-bearing for whoever writes the test assertions.** ADR-073's Tier 1
test comments say the diagnostic "names the parameter". It does not. `reason` is the fixed string
`"its ${dropped.reason} type combination is not supported"` (`NugetProcessor.kt:112`, **Verified**)
and `declaration` is the callable symbol. The *callable* is named, the *parameter* is not. The
ROADMAP item's own wording ("naming the callable") is the accurate one. Do not write a test asserting
the parameter name appears; it will fail.

A bespoke new kind is rejected as noise: `SKIPPED_UNSUPPORTED_INPUT` is already the right kind, and
ADR-064 deliberately reserved `COLLECTION` for the input position.

## Decision

Adopt **option 1**. Apply `isWrappableComponent()` to the `LIST`/`MUTABLE_LIST` input branch, so one
predicate governs every collection kind at every input position.

### The exact planner change

`forward/ForwardCallablePlanner.kt`, `inputSkipReason()`'s `Collection` branch. Today (**Verified**,
`:1291-1305`):

```kotlin
is BridgeType.Collection -> when {
  !isBridgeableComponent() ->
    (element ?: key ?: value)?.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED

  kind == CollectionKind.LIST || kind == CollectionKind.MUTABLE_LIST -> null
  kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP ->
    if (key?.isWrappableComponent() == true && value?.isWrappableComponent() == true) null
    else ForwardPlanSkipReason.COLLECTION

  else ->
    if (element?.isWrappableComponent() == true) null else ForwardPlanSkipReason.COLLECTION
}
```

After:

```kotlin
// ADR-074: one predicate for every collection kind at an input position. The leading
// !isBridgeableComponent() arm is kept so an element with its own attributable reason (e.g.
// UNEXPORTED_DEPENDENCY_TYPE, ADR-066) still reports that reason rather than the generic
// COLLECTION bucket.
is BridgeType.Collection -> when {
  !isBridgeableComponent() ->
    (element ?: key ?: value)?.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED

  kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP ->
    if (key?.isWrappableComponent() == true && value?.isWrappableComponent() == true) null
    else ForwardPlanSkipReason.COLLECTION

  else ->
    if (element?.isWrappableComponent() == true) null else ForwardPlanSkipReason.COLLECTION
}
```

That is the whole change: delete one arm. `LIST`/`MUTABLE_LIST`/`SET`/`MUTABLE_SET` all fall to the
`else`, which is already correct for them.

Do **not** touch:

- `isBridgeableComponent()` itself (`:1186-1211`). It still governs the **return** position via
  `handleResultShape` (`:1029`, **Verified**) and `skipReason()` (`:1246`, **Verified**). A
  `fun scores(): List<Mood>` return must keep working. Narrowing there would be a different, much
  larger break.
- `elementKotlinTypeName`'s `Char`/`Primitive`/`Enum` branches. They become unreachable; leave them
  with a comment pointing at ROADMAP 95.
- The KDoc on `isWrappableComponent()` (`:1213-1224`) says "Deliberately *not* applied to `List`".
  That sentence becomes false. Update it.
- The KDoc on `toDiagnosticKind()` (`ForwardDiagnostic.kt:121-127`) says `COLLECTION` "only currently
  arises from an input-position skip (`Map`/`Set` method parameters — a `List`/`MutableList` element
  accepts them...)". Also becomes false in its parenthetical. The mapping stays correct; update the
  comment.

### The ABI question: no ABI change, and ADR-073 misstates the coupling

**Narrowing adds no exports and changes no signature.** It removes plans; when a plan is removed,
both its `CirDllImport` and its `@CName` export disappear, from the same KSP run
(`NugetProcessor.process()` generates both, **Verified** per ADR-055's own Context section).

**There is no forward ABI contract hash.** `grep -rni "hash" nuget-processor/src/main/kotlin/.../forward/`
returns only `hashCode` filters and `HashSet` in rendered C# (**Verified**). ADR-055 is a
*generation-time* symmetry assertion between the two generated representations, not a persisted
version stamp. The `contractHash`/`slotCount` startup check is **ADR-054, reverse direction only**.

So:

- ADR-073's Consequences ("Four new native exports change the ADR-055 forward ABI contract hash") and
  its deferred item 4 ("changes the forward ABI contract hash, so it belongs with item 1") are both
  **naming a mechanism that does not exist**. What actually happens when a forward export set changes
  is that the `.dylib`'s exported symbol set changes, and a stale C# shim paired with a fresh library
  fails at `DllImport` resolution. That is real, but it is not an ADR-055 hash and it is not a reason
  to couple two features.
- **ROADMAP item 94 (nullable components) can stay deferred. This ships without an ABI change.** The
  coupling ADR-073 asserted between item 1 and item 4 is not real. They touch the same *predicate*,
  not the same *ABI*, and item 4's `COpaquePointer?` signature change to `nuget_list_add` is
  independent of whether the predicate currently admits nullable elements. Sequencing note only: once
  this ADR lands, item 4's work is "widen `isWrappableComponent()` to admit `Nullable` of a wrappable
  type" plus the export change, in one place instead of two. That is a benefit of doing this first,
  not a gate.

### Prior art (context, not constraint)

- **Kotlin/JS `@JsExport`.** A non-exportable type in an exported signature is a **suppressible
  warning**, `NON_EXPORTABLE_TYPE` ("Exported declaration uses non-exportable ... type"), and the
  declaration is still emitted, typed loosely on the TypeScript side. `@Suppress("NON_EXPORTABLE_TYPE")`
  is the documented escape hatch. So JS chose warn-and-emit, not skip. See
  [kotlinx.collections.immutable#172](https://github.com/Kotlin/kotlinx.collections.immutable/issues/172)
  and [`DefaultErrorMessagesJs.kt`](https://github.com/JetBrains/kotlin/blob/master/js/js.frontend/src/org/jetbrains/kotlin/js/resolve/diagnostics/DefaultErrorMessagesJs.kt).
  (Inferred, from the issue and the compiler's message table; not spiked.) We cannot copy this: there
  is no C# equivalent of "emit it anyway, typed `any`" that would not be a lying signature.
- **Kotlin/Native ObjC export.** Documents its ceiling and provides opt-out
  (`@HiddenFromObjC`, `internal`) rather than per-declaration diagnostics; unrepresentable members
  are simply absent from the generated header. See
  [native-objc-interop](https://kotlinlang.org/docs/native-objc-interop.html). (Inferred, from docs.)
- **Swift Export.** Alpha. The docs list limitations (types inheriting from `List`/`Set`/`Map` are
  "ignored during export") but do not document the *mechanism* by which an unsupported declaration is
  reported. See [native-swift-export](https://kotlinlang.org/docs/native-swift-export.html).
  (Inferred, from docs; the fetch found no statement either way, so do not cite Swift Export as
  precedent for anything stronger than "ignored".)
- **Kotlin/JVM Java interop.** Not an analogue: every Kotlin type is representable on the JVM, so
  there is no "cannot represent this parameter" case. Its only comparable behaviour is name mangling
  for `internal`, which hides rather than skips. (Inferred, from
  [java-to-kotlin-interop](https://kotlinlang.org/docs/java-to-kotlin-interop.html).)

**None of the four narrowed an already-shipped permissive gate**, which is the specific thing this
ADR does, so there is no precedent for how to communicate the removal. We do it the way this
repository already does: a warning at the author's Kotlin source line, plus a MIGRATION/FEATURES note.

**One correction to ADR-073's prior-art section while we are here.** ADR-073 states, citing
[js-to-kotlin-interop](https://kotlinlang.org/docs/js-to-kotlin-interop.html), that `List`, `Map`,
`Set` and the mutable variants "are **not exportable at all**; a declaration using one is a compile
error." The current page instead documents Kotlin collections mapping to `KtList`/`KtMap`/`KtSet`
with conversions to JavaScript views, and the compiler diagnostic is a warning, not an error. ADR-073
was reading an older version of that page. It is prior-art context only and does not affect either
ADR's decision. (Inferred, from the current doc page.)

## Consequences

### What breaks

**Source-breaking for one narrow class of consumer.** Any C# that calls a Kotlin callable whose
parameter is `List<enum>`, `List<Short>`/`List<Byte>`/`List<UInt>`/... or `List<Char>` will stop
compiling (the method is gone) instead of throwing `NotSupportedException` at the first non-empty
call. Trading a runtime throw for a compile error is the right direction, but it is a break, and it
must be in MIGRATION.md and the release notes.

Nothing else breaks: the other three shapes crash `packNuget` today, so no consumer can have compiled
against them.

**Three positions to check for collateral, all of which route through `plannedCallable`
(`:779-785`, `:640-648`), so all three are affected**:

1. **Constructors.** A class whose only constructor takes an unwrappable list parameter becomes
   uninstantiable from C#. Today it either crashes the build or offers a throwing constructor. Prefer
   the skip, but the fixture should cover it so the outcome is observed rather than assumed.
2. **Data-class `copy()`.** Same mechanism.
3. **Companion / top-level / extension positions.** Same mechanism, no special handling.

**Not affected, confirmed rather than assumed**: property setters of a collection type. The two
`inputSkipReason()` call sites are both function-shaped plans (**Verified**, only `:640` and `:779`
call it); collection properties are projected by `ForwardCirPropertyProjection` on a separate path.
**Verified** by reading `ForwardPropertyPlanner.kt:139`: a mutable collection property (`prop
.isMutable && type.unwrapNullable() is BridgeType.Collection`) is excluded from planning entirely,
before `inputSkipReason()` is ever consulted, so this change cannot affect it either way.

### Fixture surface

Project rule: the fixture crosses every mechanism, not the fewest types. That means all six negative
shapes **plus** positive controls, so an over-narrowing regression (someone tightening
`isWrappableComponent()` further, or applying it at the return position by mistake) is caught.

Existing fixtures to reuse rather than invent (**Verified** they exist):

- `enum class Mood { CALM, ANXIOUS, PLAYFUL }`, `clinic/ClinicSample.kt:17` — same package as the
  `Patient` cells, no reachability work.
- `value class CatId(val id: String)`, `cat/CatId.kt` — a real, already-exported value class.
  The ROADMAP item names `StoryCode`, which is real too
  (`io.github.xxfast.kotlin.native.nuget.test.models.StoryCode`, used by `Newsroom.code()`), but it
  lives one Gradle module away and would drag the ADR-066 reachability closure into a cell that is
  not about reachability. Prefer a locally-declared value class in the Tier 1 cells and `CatId` in
  `test-library`.
- `Patient.addTags(tags: List<String>)` (`:102`) and `Patient.Companion.batchAdmit(names: List<String>)`
  (`:148`) — the existing positive `List` parameter cells, already asserted by
  `Tier1StructuralInteropCsTest` (`public void AddTags(IReadOnlyList<string> tags)`, `:310-325`) and
  `MethodParameterMarshallingTests`. These are the primary over-narrowing regression gate; do not
  touch them.

#### Tier 1 skip cells (the primary harness, alongside `Tier1NamedSkipDiagnosticsTest`)

Six cells, one per shape, each asserting `result.compiledClean`, the export symbol **absent** from
the generated Kotlin, and a `SKIPPED_UNSUPPORTED_INPUT` warning. Structure them exactly like the four
ADR-073 cells at `Tier1NamedSkipDiagnosticsTest.kt:30-156`.

```kotlin
// crashes packNuget today — these three are the RED, and the harness will currently die on the
// elementKotlinTypeName error(...) rather than fail an assertion.
class Patient(val name: String) { fun addAliases(aliases: List<String?>): Int = aliases.size }

@JvmInline value class Code(val value: String)
class Patient(val name: String) { fun addCodes(codes: List<Code>): Int = codes.size }

class Patient(val name: String) { fun addGroups(groups: List<List<String>>): Int = groups.size }

// binds today, must stop binding
enum class Mood { CALM, ANXIOUS }
class Patient(val name: String) { fun addMoods(moods: List<Mood>): Int = moods.size }

class Patient(val name: String) { fun addWeights(weights: List<Short>): Int = weights.size }

class Patient(val name: String) { fun addInitials(initials: List<Char>): Int = initials.size }
```

Plus two **still-binding controls** in the same file, asserting the export **is** present and that no
`SKIPPED_UNSUPPORTED_INPUT` fires (mirror `Tier1NamedSkipDiagnosticsTest.kt:164-196`):

```kotlin
class Buddy(val name: String)
class Patient(val name: String) {
  fun addTags(tags: List<String>): Int = tags.size          // String element
  fun addBuddies(buddies: List<Buddy>): Int = buddies.size  // ObjectHandle element, the reflective
                                                            // _handle path, which must survive
}
```

And one **return-position control**, because the single most likely over-narrowing regression is
applying `isWrappableComponent()` at `:1029` or `:1246` by mistake:

```kotlin
enum class Mood { CALM, ANXIOUS }
class Patient(val name: String) {
  fun moods(): List<Mood> = listOf(Mood.CALM)   // must STILL bind: return position is untouched
}
```

Plus one **mutable-list** cell (`fun addMoods(moods: MutableList<Mood>)`) so the deleted arm is proved
to have covered both kinds, and one **constructor** cell (`class Ward(val moods: List<Mood>)`) so the
collateral position in "What breaks" above is observed rather than assumed.

#### `test-library` cells

The Tier 1 cells above carry the diagnostic assertions. `test-library` should carry only what needs
to survive a real `konanc` + `dotnet test` round trip, i.e. the controls and the one claim this ADR
could not verify:

- Keep `Patient.addTags` and `batchAdmit` untouched.
- Add `fun addBuddies(buddies: List<Patient>): Int` to `Patient` if no object-handle `List` parameter
  exists in `test-library` today (**Verified**: none does; the object-handle list parameter is only
  covered by a Tier 1 C# *rendering* assertion at `Tier1StructuralInteropCsTest.kt:327-343`, which
  never executes the reflective `_handle` path for a list). This is the strongest over-narrowing gate
  and it is currently missing.
- **Before** the narrowing lands, temporarily add `fun addMoods(moods: List<Mood>): Int` and an
  `IntegrationTests` call with a non-empty list, to settle the Inferred `NotSupportedException`
  claim by execution. Record the real exception in this ADR, then delete the fixture as part of the
  narrowing commit. Do not skip this step: it is the one claim this ADR is asking the reader to take
  on faith.
- Do **not** add `List<String?>` / `List<CatId>` / `List<List<String>>` to `test-library` before the
  fix. They kill `packNuget` and therefore every other build in the repo.

### What stays deferred

Confirmed untouched by this ADR:

- **ROADMAP line 94** (nullable components, `Map<String,Int?>` / `Set<String?>` / `List<Foo?>`).
  Still deferred. This ADR makes `List<Foo?>` *skip cleanly* instead of crashing; it does not make it
  work. Its ABI coupling to this item is **not real** (see "The ABI question"), so it needs no
  co-scheduling.
- **ROADMAP line 95** (enum and narrow-primitive components). Still deferred. This ADR makes
  `List<Mood>` / `List<Short>` / `List<Char>` skip; closing them still needs `nuget_wrap_enum` and
  the seven narrow-primitive `nuget_wrap_*` exports, plus matching `Wrap<T>` branches. The half-built
  `elementKotlinTypeName` branches for those types are deliberately left in place for that work.
- **ROADMAP line 96** (value-class and nested-collection components). Still deferred. This ADR makes
  `List<StoryCode>` / `List<List<String>>` skip instead of crashing; the `Wrap<T>` story and the
  `elementKotlinTypeName` branches are still missing.
- ROADMAP lines 97 (mutable write-back), 98 (handle leak on a throwing callee) and 99 (unconditional
  `CreateList` emission). Unrelated; unchanged.

### Claim labelling

**Verified** (repo source read, cited inline): `isWrappableComponent()`'s definition and its
`List`-carve-out KDoc; `inputSkipReason()`'s current Collection branch and its two call sites;
`isBridgeableComponent()`'s admitted set including `Nullable`/`ValueClass`/nested `Collection`;
`elementKotlinTypeName`'s five branches and its `error(...)` else, and that all nine of its call
sites are input lowerings; `Wrap<T>`'s six branches plus the reflective `_handle` fallback that
throws; `CreateList`'s per-element `foreach` (hence the empty-list case); the
`COLLECTION → SKIPPED_UNSUPPORTED_INPUT` diagnostic chain end to end including the exact rendered
message and the fact that it names the callable and **not** the parameter; that
`isBridgeableComponent()` also governs the return position at `:1029`/`:1246`; that no forward ABI
hash exists anywhere in `nuget-processor/src/main`; the fixture inventory (`Mood`, `CatId`,
`StoryCode`, the three `List` parameters in `test-library`, the two `Tier1StructuralInteropCsTest`
list-parameter cells).

**Verified by execution** (settled during implementation, not by source reading alone):

1. `List<Mood>`, `List<Short>` and `List<Char>` parameters throw `NotSupportedException` on a
   non-empty call. The exact message, read from a real `dotnet test` run against a temporary
   `Patient.addMoods(moods: List<Mood>)` fixture: `System.NotSupportedException: Cannot pass Mood to
   a Kotlin collection`. An **empty** list, by contrast, succeeds and returns 0, since `CreateList`'s
   per-element loop never reaches `Wrap<T>`. The fixture was deleted after confirming this, per the
   ADR's own instruction.
2. That collection-typed **property setters** are on a separate path and unaffected. Confirmed by
   reading `ForwardPropertyPlanner.kt:139`: a mutable collection property (`prop.isMutable && type
   .unwrapNullable() is BridgeType.Collection`) is excluded from planning entirely, before
   `inputSkipReason()` is ever consulted. Not a bug, and not affected by this ADR's change.

**Inferred** (nobody ran it; this ADR's author has no toolchain access for it):

1. The four prior-art summaries (JS `NON_EXPORTABLE_TYPE` severity, ObjC export's silent omission,
   Swift Export's handling, JVM's non-analogy). Context only; none constrains the implementation.

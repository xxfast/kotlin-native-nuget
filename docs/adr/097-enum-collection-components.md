# ADR-097: Forward, enum collection components: the int ordinal in the component slot, and `List`'s input gate narrowed to what the write side can box

## Status
Accepted

## Context

A consumer writes

```kotlin
class ChartBook {
  fun logMoods(moods: List<Mood>): String = moods.joinToString(",")
}
```

and expects `book.LogMoods(new[] { Mood.Calm, Mood.Playful })` to work in C#. Today it compiles and
throws at the first call.

Three ROADMAP items overlap here, and two of them contradicted each other on what actually happens
today. Both were settled by execution, not by reading (spike below).

### What today actually does (all Verified by spike)

A temporary `SpikeProbe` fixture was added to `test-library/src/nativeMain/.../clinic/`, KSP was run,
the generated `Interop.cs` and `CNameExports.kt` were read, and the fixture was deleted (`git status`
clean afterwards).

```
$ ./gradlew :test-library:kspKotlinMacosArm64 --console=plain   # with fun moods(List<Mood>), shorts(List<Short>), chars(List<Char>)
BUILD SUCCESSFUL in 7s
```

**Verified**: `List<Mood>`, `List<Short>` and `List<Char>` parameters bind fully. The generated C# is

```csharp
public string Moods(IReadOnlyList<global::TestLibrary.Clinic.Mood> values)
{
    valuesHandle = NugetMarshal.CreateList(values);   // T = Mood
    ...
}
```

and the generated Kotlin is already complete and correct:

```kotlin
@CName("spikeprobe_moods")
public fun export_spikeprobe_moods(handle: COpaquePointer, values: COpaquePointer, errorOut: COpaquePointer?): String =
  handle.asStableRef<SpikeProbe>().get().moods(values.asStableRef<MutableList<Any?>>().get().map { it as Mood })
```

**Verified** (read from the *generated* `Interop.cs`, not the renderer): the shipped `Wrap<T>` has
branches for `string`, `int`, `long`, `float`, `double`, `bool`, then `value is INugetHandle`, then
`throw new NotSupportedException($"Cannot pass {typeof(T).Name} to a Kotlin collection")`. An enum,
a `short` and a `char` match none of them. So `ROADMAP.md:133`'s inferred claim is correct and
`ROADMAP.md:136`'s "correctly excluded, skips with `SKIPPED_UNSUPPORTED_INPUT`" is wrong **for
`List`**: it is true only for `Map`/`Set`, which run the narrower `isWrappableComponent()` gate.
`List` at a callable-parameter position is still on the wider, pre-existing
`isBridgeableComponent()` (`ForwardCallablePlanner.kt:2266-2271`). **This is a shipped runtime
landmine, not a missing capability.**

**Verified by spike**: the non-null nested shape crashes the whole KSP run.

```
$ ./gradlew :test-library:kspKotlinMacosArm64 --console=plain   # with fun nested(List<List<String>>)
e: [ksp] java.lang.IllegalStateException: Forward Kotlin plan emitter has no element type name for Collection(kind=LIST, element=String, key=null, value=null)
> Task :test-library:kspKotlinMacosArm64 FAILED
```

That is `elementKotlinTypeName`'s `error(...)` at `ForwardKotlinPlanEmitter.kt:506`, reached from
`componentLowering`. One unsupported member aborts `packNuget` for the whole library.

**Verified (repo code)**: the *read* side of a bare-enum collection is broken too, and the repo
already knows it. `IntegrationTests/CollectionPropertyIndependenceTests.cs:145-148` says, in a
comment on `Chart_Moods_EnumElement_HasNoPublicSetter`:

> Trap: reading `Moods` or `Aliases` is a pre-existing, unrelated bug (`NugetMarshal.FromHandle<T>`
> has no enum branch [...]), so these assert *shape only* via reflection and never call the getter.

The generated getter is `NugetMarshal.FromHandle<global::TestLibrary.Clinic.Mood>(...)`, which falls
to `Materialize<T>` → `Factories` lookup → no entry for an enum → `NotSupportedException`
(**Verified**: no `[typeof(global::TestLibrary.Clinic.Mood)]` entry exists in the generated
`Factories` initializer). So a bare enum collection component is bind-then-throw in **both**
directions.

### What already exists that this can ride

**Verified** (generated `Interop.cs` / `CNameExports.kt`, `test-library` corpus):

- ADR-081 already ships the exact wire for an enum inside a collection, one level down. For
  `fun recordMoods(moods: Set<Temperament>)` (`Temperament` is a value class over `Mood`) the C# is
  `NugetMarshal.CreateSet(Enumerable.Select(moods, x => (int)x.Mood))` and the Kotlin is
  `...mapTo(mutableSetOf()) { Temperament(Mood.entries[it as kotlin.Int]) }`. **The int ordinal is
  already a proven collection-component wire; only the bare-enum spelling is missing.**
- The generated C# enum carries explicit, declaration-order ordinals (`Happy = 0, Sleepy = 1,
  Grumpy = 2`), which is what makes `(int)x` ↔ `entries[i]` sound.
- The per-element projection seam is one file: `forward/ForwardCirCollectionComponents.kt`
  (`collectionCreateArgument`, `componentWireExpression`, `componentWireCsharpType`,
  `componentReadExpression`), shared by the callable and property CIR projections.
- The unwrap export family is already **wider** than the wrap family: `nuget_unwrap_byte`, `_ubyte`,
  `_short`, `_ushort`, `_int`, `_uint`, `_long`, `_ulong`, `_float`, `_double`, `_bool`, `_string`
  exist; `nuget_wrap_*` exists for only `string/int/long/float/double/bool`
  (`exports/GenericClassExports.kt:433-457`). There is **no** `nuget_unwrap_char` and no
  `nuget_wrap_char` at all.

### The reframe in `ROADMAP.md:136` is only half right

That item records the unblock path as "`Wrap<T>` is a single runtime-generic helper; a
type-specialized boxing call per element type is the fix". That is necessary for *narrow
primitives*, but **not** for enums, and it is not even the cheapest route for narrow primitives:

- For an **enum**, `typeof(T) == typeof(SomeUserEnum)` can never be enumerated in a shared helper
  (open set), so specialization does not help either; the projection-to-`int` at the call site is
  the only workable shape, and it is already implemented for value classes.
- For **narrow primitives**, the set is closed (six kinds), so six more `typeof(T)` branches in the
  existing `Wrap<T>` are enough, exactly mirroring `FromHandle<T>`'s existing ten branches. No
  call-site specialization is required.

## Alternatives Considered

### 1. Bare enum crosses as its int ordinal, projected per element at the C# call site, and `List`'s input gate narrows to `isWrappableComponent()` (chosen)

Add `is BridgeType.Enum -> true` to `isWrappableComponent()`, generalize
`ForwardCirCollectionComponents.kt`'s value-class-only projection to a "needs projection" predicate
covering value classes **and** bare enums, add the bare-enum branches to `componentLowering`
(write) and `componentReadExpression` (read), and narrow the `List` input gate from
`isBridgeableComponent()` to `isWrappableComponent()`.

Pros:
- Zero new native exports, zero new `Wrap<T>` branches, zero ABI surface change.
- Reuses a wire that a real C# compiler, a real Kotlin/Native link and a real integration test
  already exercise (`ChartBook.RecordMoods`, ADR-081).
- Fixes the read side (`Chart.Moods` getter) with the same projection, closing a verified shipped
  bug rather than only adding a capability.
- The gate narrowing converts every remaining bind-then-throw and KSP-crash `List` shape into a
  named `SKIPPED_UNSUPPORTED_INPUT`, which is ADR-083's template applied one level up.

Cons:
- Behaviour change: `List<Short>`, `List<Char>`, `List<List<String>>`, `List<Instant>`,
  `List<IPet>`-shaped parameters stop appearing in the generated C#. See "what is lost" below: the
  loss set is exactly the set that throws or crashes today.
- The write and read halves must change in lockstep with `componentLowering`. Changing the C#
  projection alone would send a boxed `Int` into `it as Mood` and produce a `ClassCastException` at
  runtime, not a compile error.

### 2. Bare enum crosses as a boxed enum object (a new `nuget_wrap_enum` / `nuget_unwrap_enum`)

Keep the enum as an enum all the way across, box a real Kotlin `Mood` on the Kotlin side.

Cons: the wrap export would need the enum's *type identity* on the wire to know which enum class to
box, which a shared helper does not have. It would degenerate into one generated export per enum
type (`nuget_wrap_mood`, …), multiplying the export surface for no gain over the ordinal, and it
would diverge from ADR-081's already-shipped value-class-over-enum wire (two different
representations for the same Kotlin enum depending on whether a value class wraps it). Rejected.

### 3. Widen `Wrap<T>` reflectively for enums (`typeof(T).IsEnum` → `nuget_wrap_int(Convert.ToInt32(value))`)

Pros: one line, no call-site change.
Cons: ADR-094 deliberately removed the two reflection shapes from this bridge for Mac Catalyst /
AOT; adding a runtime type test back into the hottest per-element path contradicts it. It also does
nothing for the read side, where the enum type must be named statically to cast back. Rejected.

### 4. Do the narrow primitives (`Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`) in the same change

Six entries in `addNugetWrapHelperExports`'s `types` list, six `typeof(T)` lines in
`CirMarshalRenderer`, six `PrimitiveKind`s in `isWrappableComponent()`. Genuinely small.

Cons: it adds six native exports, which is real ABI surface, for a capability the restatement does
not ask for; and it is strictly additive after this ADR (the gate narrowing here makes those shapes
a clean named skip in the meantime, so nothing is left broken by deferring). Deferred, not rejected;
see Scope.

### 5. Give nested collections a `Wrap<T>` story in the same change

Cons: a `List<List<String>>` element needs a recursive `CreateList` per element on the write side and
a recursive materialize per element on the read side, plus an `elementKotlinTypeName` branch that
returns a full nested Kotlin type. That is a capability with its own eager-copy and ownership
questions (who disposes the inner handles when the outer `CreateList` throws halfway). Deferred; the
**crash** is closed here by the gate narrowing, which is the part that cannot wait. Rejected for
this ADR.

## Decision

### 1. A bare enum collection component crosses as its `Int` ordinal

Write side, C#, per element at the call site (generalizing ADR-081's value-class projection):

```csharp
// fun logMoods(moods: List<Mood>): String
public string LogMoods(IReadOnlyList<global::TestLibrary.Clinic.Mood> moods)
{
    IntPtr moodsHandle = IntPtr.Zero;
    try
    {
        moodsHandle = NugetMarshal.CreateList(
            global::System.Linq.Enumerable.Select(moods, x => (int)x));
        ...
    }
    finally { if (moodsHandle != IntPtr.Zero) { NugetListNative.Dispose(moodsHandle); } }
}
```

`Wrap<T>` is therefore only ever instantiated at `T = int`, a branch it already has. **Verified**:
that is the same shape the shipped `ChartBook.RecordMoods` uses today for `Set<Temperament>`.

Write side, Kotlin, in `componentLowering` (`ForwardKotlinPlanEmitter.kt:516-541`):

```kotlin
is BridgeType.Enum -> "${type.qualifiedName}.entries[$name as kotlin.Int]"
```

**Verified**: the value-class-over-enum branch at line 519 already emits exactly
`Temperament(Mood.entries[it as kotlin.Int])`, so the bare form is that expression minus the
wrapper. The nullable spelling in the same function (line 527-538) needs the matching arm,
`($name as kotlin.Int?)?.let { v -> ${inner.qualifiedName}.entries[v] }`, because
`isWrappableComponent()`'s `Nullable` branch delegates to the inner type and will therefore admit
`List<Mood?>` automatically the moment `Enum` is added. **Shipping the `Enum` allow-list entry
without that nullable arm would admit `List<Mood?>` and then emit `it as Mood?` against a boxed
`Int`: a `ClassCastException` at the first call, not a compile error.**

**Verified** (`ForwardKotlinPlanEmitter.kt:505`, checked after the Step 3 red run raised the
possibility that this aborts KSP instead): the failure really is a runtime cast, not a build abort.
`elementKotlinTypeName` already carries an `is BridgeType.Enum -> type.qualifiedName` branch, so a
missing bare-enum arm in `componentLowering` never reaches that function's `error(...)`; it falls
through to `else -> "$name as ${elementKotlinTypeName(inner)}?"` and emits a well-formed cast to the
wrong type. Nothing fails loudly. That is what makes this the sharpest edge in the change.

Read side, C#, in `componentReadExpression` / `componentWireCsharpType`:

```csharp
result.Add((global::TestLibrary.Clinic.Mood)NugetMarshal.FromHandle<int>(NugetListNative.Get(nativeResult, i)));
```

**Verified**: this is the same cast `componentReadExpression` already applies for a value class over
an enum (`ForwardCirCollectionComponents.kt:103-105`), and `FromHandle<int>` has a real branch,
unlike today's `FromHandle<Mood>` which reaches `Materialize` and throws.

Out-of-range ordinals: a C# caller can construct `(Mood)99` (C# enums are open). It arrives as
`entries[99]` and throws `IndexOutOfBoundsException` on the Kotlin side, which surfaces through the
standard `errorOut` path as an exception at the call site. That is the same exposure ADR-081 already
has; no extra guard is added. **Inferred** (from the shared `errorOut` machinery, not spiked at
runtime for this specific throw).

### 2. `isWrappableComponent()` gains one branch, and every position gets it

```kotlin
is BridgeType.Enum -> true
```

`isWrappableComponent()` is the shared predicate for `Map`/`Set` callable inputs (ADR-073) and for
collection *property setters* (ADR-075). One branch therefore admits `List<Mood>`, `Set<Mood>`,
`Map<String, Mood>`, `Map<Mood, String>` and flips `Chart.moods` from get-only to settable.
Position-specific admission would need a second predicate for no benefit, and would leave
`Chart.moods` readable-but-broken. This answers scope fork 2: **one predicate, all three positions**.

### 3. `List`'s callable-input gate narrows to `isWrappableComponent()`

`ForwardCallablePlanner.collectionInputSkipReason`'s `LIST`/`MUTABLE_LIST` branch (currently a
nullable-only special case at `:2266-2271`) collapses into the same rule `MAP`/`SET` already use,
so the whole function reduces to "every component must be wrappable".

`List`'s gate today is a **split** gate, not a uniformly wide one (**Verified** by the Step 3 red
run, source at `ForwardCallablePlanner.kt:2261-2271`): ADR-083 already narrowed the *nullable*
element to `isWrappableComponent()` and left the comment "the non-null element gate stays on the
wider `isBridgeableComponent`". So this ADR **collapses two halves into one rule** rather than
narrowing a single wide one, and the loss below is the **non-null** components only. Every nullable
spelling was already lost in ADR-083. One consequence to expect at implementation time: `List<Mood?>`
does **not** bind today (its `Mood?` fails the ADR-083 nullable gate), so it is a missing C# member
right now, not a bind-then-throw.

What is lost, exhaustively (the whole of `isBridgeableComponent() \ isWrappableComponent()` at a
non-null component after branch 1 lands): `Char`, the six narrow primitive kinds, nested
`Collection`, `Unit`, and a value class over any of those. **Verified**: every one of them either throws
`NotSupportedException` from `Wrap<T>` at the first call (`Char`, narrow primitives, `Unit`) or
aborts `packNuget` in `elementKotlinTypeName` (nested `Collection`). **No shape that works today is
removed.** `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, `ObjectHandle`, value classes over
those, and (after branch 1) enums are in both predicates and are unaffected.

This is the ADR-083 crash-to-skip conversion applied one level up, and it is what closes the `List`
half of `ROADMAP.md:137` (`List<List<String>>` stops crashing `packNuget` and becomes a named
`SKIPPED_UNSUPPORTED_INPUT`). **Inferred** (source reading, not spiked): the skip routes to the same
named diagnostic `Map`/`Set` already emit, since the branch returns the identical
`ForwardPlanSkipReason.COLLECTION`.

### Consumer-side C# API

```csharp
using var book = new ChartBook();

// List input, enum element: the restatement's shape
Assert.Equal("CALM,PLAYFUL,CALM", book.LogMoods(new[] { Mood.Calm, Mood.Playful, Mood.Calm }));

// Set input
Assert.Equal("ANXIOUS,CALM", book.TallyMoods(new HashSet<Mood> { Mood.Calm, Mood.Anxious }));

// Map with an enum in the value slot and a String key: mixed projection in one collection
Assert.Equal("mylo=ANXIOUS;oreo=CALM", book.ChartMoods(
    new Dictionary<string, Mood> { ["oreo"] = Mood.Calm, ["mylo"] = Mood.Anxious }));

// Nullable enum element: admitted automatically by the Nullable branch
Assert.Equal("CALM,null,PLAYFUL", book.LogMoodTrail(new Mood?[] { Mood.Calm, null, Mood.Playful }));

// Read side: broken today (FromHandle<Mood> -> Materialize -> NotSupportedException)
Assert.Equal(new[] { Mood.Calm, Mood.Playful }, book.MoodsOnFile());

// Property: Chart.Moods flips from get-only to a round trip
using var chart = new Chart("Mylo");
chart.Moods = new[] { Mood.Anxious, Mood.Calm };
Assert.Equal("ANXIOUS,CALM", chart.MoodsSummary());
```

### Fixture

One new file, `test-library/src/nativeMain/.../clinic/EnumComponentCollectionsSample.kt`, plus one
promoted cell in the existing `ChartSample.kt`. Every cell crosses a seam this feature changes; no
speculative cells.

```kotlin
class MoodLedger {
  // List input, enum element -- the restatement's exact shape, and the C# call-site projection
  // `Select(x => (int)x)` + the Kotlin `Mood.entries[it as Int]` re-wrap.
  fun logMoods(moods: List<Mood>): String = moods.joinToString(",")

  // Set input -- a different native export and a different Kotlin lowering
  // (`mapTo(mutableSetOf())`), so it cannot ride logMoods's coverage.
  fun tallyMoods(moods: Set<Mood>): String = moods.map { it.name }.sorted().joinToString(",")

  // Map with an enum VALUE and a String KEY: the one collection that carries both an element type
  // needing conversion at the seam (Mood -> int) and one needing none (String), so the mixed
  // `new KeyValuePair<string, int>(x.Key, (int)x.Value)` projection is compiled by a real compiler.
  fun chartMoods(byPatient: Map<String, Mood>): String =
    byPatient.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

  // Enum KEY, String value: the key slot projects independently from the value slot.
  fun moodRoster(byMood: Map<Mood, String>): String =
    byMood.entries.sortedBy { it.key.ordinal }.joinToString(";") { "${it.key}=${it.value}" }

  // Nullable enum element: admitted automatically by isWrappableComponent's Nullable branch the
  // moment Enum is allowed, so it needs its own `(x == null ? (int?)null : (int)x)` write arm and
  // `(it as Int?)?.let { v -> Mood.entries[v] }` read arm. Without this cell the ADR ships an
  // admitted shape whose Kotlin lowering ClassCastExceptions at the first call.
  fun logMoodTrail(moods: List<Mood?>): String = moods.joinToString(",") { it?.name ?: "null" }

  // Read side: List<Mood> return. Binds today and throws NotSupportedException out of
  // FromHandle<Mood> -> Materialize at the first read.
  fun moodsOnFile(): List<Mood> = listOf(Mood.CALM, Mood.PLAYFUL)

  // Read side, Map with an enum value: distinguishes the value slot's read projection from the
  // element slot's.
  fun moodChart(): Map<String, Mood> = mapOf("oreo" to Mood.CALM, "mylo" to Mood.ANXIOUS)
}
```

Promoted cell, in `ChartSample.kt`: `Chart.moods` (`var moods: List<Mood>`) is already declared and
is already the repo's named example of a setter-ineligible collection property. It flips to settable
here, so `CollectionPropertyIndependenceTests.Chart_Moods_EnumElement_HasNoPublicSetter` becomes
`Chart_Moods_EnumElement_RoundTripsThroughItsSetter` and the "trap" comment at
`CollectionPropertyIndependenceTests.cs:145-148` is deleted (both of the bugs it names for `Moods`
are closed by this ADR). A Kotlin-side observer (`fun moodsSummary(): String`) is added to `Chart` so
the setter round trip is proved by Kotlin's own view of the list, not by reading back through the
same getter.

Gate-narrowing cell, one declaration whose C# member must be **absent** from the generated API:

```kotlin
  // ADR-097 §3: List's input gate narrowed to isWrappableComponent(), so this becomes a named
  // SKIPPED_UNSUPPORTED_INPUT instead of binding and throwing NotSupportedException at the first
  // call. Asserted absent from the generated C# (there is no callable to invoke).
  fun logSpans(spans: List<Short>): String = spans.joinToString(",")
```

`List<List<String>>` is **not** added as a fixture cell: it crashes KSP today, so it cannot live in
the corpus until the narrowing lands, and once it lands it is the same named skip as `List<Short>`
with no additional seam. Its coverage belongs in a Tier 1 planner test
(`Tier1EnumCollectionComponentTest`) asserting `SKIPPED_UNSUPPORTED_INPUT` for both
`List<List<String>>` and `List<Short>`, alongside the positive cells.

## Consequences

- **A shipped runtime landmine is removed.** `List<Mood>` goes from "binds, throws
  `NotSupportedException` at the first call" to working, both directions. `List<Short>`,
  `List<Char>`, `List<Unit>` and value classes over those go from "binds, throws" to a named skip.
  `List<List<String>>` goes from "aborts the whole `packNuget`" to a named skip.
- **Breaking, deliberately**: a consumer whose Kotlin API has a `List<Short>`-shaped parameter loses
  that C# member. It could never be called successfully, so no working consumer code breaks; a
  consumer who merely *compiled* against it does. FEATURES.md and the generated-API diff in
  `scripts/verify.sh` will show the removal.
- `Chart.Moods` gains a public setter. Every `Map`/`Set`/`List` position and every collection
  property setter accepts enum components from the same one-line predicate change.
- Files touched: `forward/ForwardCallablePlanner.kt` (one `when` branch added to
  `isWrappableComponent()`, one branch collapsed in `collectionInputSkipReason`),
  `forward/ForwardCirCollectionComponents.kt` (value-class-only projection generalized to
  value-class-or-enum across four functions), `forward/ForwardKotlinPlanEmitter.kt`
  (`componentLowering`: one non-null and one nullable bare-enum arm). No change to
  `cir/CirMarshalRenderer.kt`, no change to `exports/GenericClassExports.kt`, no new native export,
  no ABI surface change.

### Deferred, with reasons

- **Narrow-primitive components** (`Byte`, `UByte`, `Short`, `UShort`, `UInt`, `ULong`) at a
  `Map`/`Set`/`List` input position. Not needed by the restatement, and additive after this ADR
  (they are a clean named skip in the meantime). The follow-up is mechanical and now precisely
  priced: six entries in `addNugetWrapHelperExports`'s `types` list, six `typeof(T)` branches in
  `Wrap<T>` (mirroring `FromHandle<T>`'s existing ten), six `PrimitiveKind`s in
  `isWrappableComponent()`. `ROADMAP.md:136`'s "type-specialized boxing call per element type" is
  **not** required for this: the primitive set is closed, so `typeof(T)` branches suffice
  (**Verified**: `FromHandle<T>` already dispatches all ten narrow kinds that way in the shipped
  `Interop.cs`).
- **`Char` components**, separately from the six above. `Char` is the one member of
  `ROADMAP.md:136`'s list genuinely coupled to the `Char?` width item (`ROADMAP.md:145`): there is
  no `nuget_wrap_char` **and no `nuget_unwrap_char`** (**Verified**), so both halves would have to be
  minted, and minting them forces the UTF-16-vs-ANSI decision that `:145` owns. `ROADMAP.md:136`
  should be re-scoped: the other six are *not* coupled to `:145` and can ship without it.
- **Nested-collection components** (`List<List<String>>`, `Set<List<String>>`, …). The crash is
  closed here; the capability is not. It needs a recursive `CreateList` per element on the write
  side, a recursive materialize on the read side, an `elementKotlinTypeName` branch returning a full
  nested Kotlin type, and an answer to who disposes the inner handles when the outer factory throws
  halfway. `ROADMAP.md:137` stays open, minus its `List`-crash half.
- **`Map<String?, Int>` returns** (`ROADMAP.md:135`, the `CS8714` nullable-key warning) is untouched;
  unrelated position.

### Unverified claims that would silently produce wrong output if wrong

Named explicitly, because nobody re-checks these after this document:

1. **Inferred, not spiked**: that narrowing the `List` branch of `collectionInputSkipReason` emits
   the same named `SKIPPED_UNSUPPORTED_INPUT` diagnostic `Map`/`Set` already emit. It returns the
   identical `ForwardPlanSkipReason.COLLECTION`, so the routing is shared by construction, but the
   `List` path was not run through it. If wrong, the failure is a *worse diagnostic*, not wrong
   output.
2. **Inferred, not spiked**: that an out-of-range C# enum cast (`(Mood)99`) surfaces as a caught
   exception through `errorOut` rather than crashing the host. It rides the same `try/catch
   (e: Throwable)` wrapper every export has (**Verified** in the generated `CNameExports.kt`), so the
   throw is inside the guarded region; only the resulting message text is unproven.
3. **Verified but worth restating as the implementation's sharpest edge**: the C# write projection
   and `componentLowering` must change together. `Select(x => (int)x)` against an unchanged
   `it as Mood` is a `ClassCastException` at the first call, and neither compiler catches it. The
   Tier 1 generated-text tests must assert both sides in the same cell.
4. **Not re-verified here**: whether a bare non-null `Char` *parameter* (the already-shipped
   `Patient.Tag(char)`) marshals correctly for a non-ASCII char. `[DllImport]` defaults to
   `CharSet.Ansi`, and the only integration coverage is `Tag('O')` / `Tag('X')`, both ASCII
   (**Verified** by grep). This is out of scope here (this ADR touches no `Char` path) but it is a
   live, untested question that `ROADMAP.md:145` should own.

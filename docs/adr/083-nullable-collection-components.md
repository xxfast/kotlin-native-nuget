# ADR-083: Nullable collection components: the null pointer in the component slot, both directions

## Status
Proposed

## Context

ROADMAP ("Nullable collection components ... have no representation on the write side"): a null
map value, set element or list element cannot cross the bridge. Current state, all **verified in
source at HEAD** (`value-class-followups` branch):

1. **Write side**: `nuget_list_add(handle, element)`, `nuget_set_add(handle, element)` and
   `nuget_map_put(handle, key, value)` take non-nullable `COpaquePointer` and immediately
   `asStableRef<Any>().get()` (`exports/GenericClassExports.kt:172-181, 220-229, 282-293`), so
   there is no way to put a null in the backing store, even though the stores themselves are
   already null-capable: `mutableListOf<Any?>()`, `mutableSetOf<Any?>()`,
   `mutableMapOf<Any?, Any?>()`.
2. **Read side (adjacent verified bug, in scope)**: all four read exports force non-null:
   `nuget_list_get` does `list[index]!!` (`GenericClassExports.kt:152`), `nuget_set_element_at`
   does `toList()[index]!!` (`:201`), `nuget_map_key_at` / `nuget_map_value_at` do
   `keys.toList()[index]!!` / `values.toList()[index]!!` (`:249, :262`). Any *returned* collection
   containing a null element/key/value NPEs inside the export at first read. Returns with
   nullable components **bind today** (the result gate is `isBridgeableComponent()`, whose
   `Nullable` branch admits them, `forward/ForwardCallablePlanner.kt:1815`), so this is a shipped
   bind-then-break, the same class ADR-081's Context finding 3 closed for value-class components.
3. **Planner gates**: `Map`/`Set` inputs (and, via ADR-075, collection property setters) skip
   named: `isWrappableComponent()` has no `Nullable` branch, `else -> false`
   (`ForwardCallablePlanner.kt:1856-1874`). `List` inputs are gated on the wider
   `isBridgeableComponent()`, which *admits* `Nullable(bridgeable)`, and then
   `componentLowering` → `elementKotlinTypeName` has no `Nullable` branch and `error(...)`s
   (`forward/ForwardKotlinPlanEmitter.kt:483-508`), crashing the whole KSP run. So
   `List<String?>` as a parameter is one of the two remaining **crash** shapes of the sibling
   "`List<T>` input components are not narrowed" ROADMAP item. Note the ROADMAP's nullable-
   components item says the planner "correctly refuses these shapes with
   `SKIPPED_UNSUPPORTED_INPUT` (Verified)"; that is accurate for `Map`/`Set` only, and
   contradicted for `List<Foo?>` by the narrowing item's own (correct) crash claim one bullet
   above it.
4. **C# helpers**: `NugetMarshal.Wrap<T>` dispatches on `typeof(T) ==` checks for `string` and
   the five wide primitives, then a reflective `_handle` fallback, then `NotSupportedException`
   (`cir/CirMarshalRenderer.kt:226-238`). **Verified by spike** (see Decision): `typeof(int?) ==
   typeof(int)` is `false`, so `Wrap<int?>` today would miss every branch even for a non-null
   value; and a null `string` would reach `nuget_wrap_string` un-guarded.

The motivating consumer shape is `List<ChartId?>` (nullable value-class component), which rides
whatever this ADR builds on top of ADR-081's underlying-projection wire.

Key structural observation: unlike ordinary positions, where a nullable primitive has no pointer
to ride and needs the ADR-061/079 has-value pair, **a collection component slot is already
pointer-shaped**. Every element crosses as a boxed `StableRef` handle regardless of its type, so
the null pointer is available as an in-band, unambiguous null for *every* component kind,
including `Int?`. A valid `StableRef.asCPointer()` is never the null pointer (relied on by every
shipped nullable-handle crossing; **verified by shipped usage**, not by a runtime probe of the
Kotlin/Native runtime itself).

The raw crossing mechanism needs no new proof. C# `IntPtr.Zero` arriving as a genuine Kotlin
`null` in a `COpaquePointer?` parameter, and a Kotlin `null` returning as `IntPtr.Zero`, are both
shipped and integration-tested at ordinary positions: the nullable collection *parameter*
(`CreateList(...) : IntPtr.Zero` guard, `ForwardCirPlanProjection.kt:532`; Kotlin side
`loweredCollectionExpression(nullable = true)` with a `COpaquePointer?` parameter), the nullable
`ObjectHandle` parameter (`x?._handle ?? IntPtr.Zero`, `:530`), the nullable handle/collection
result (`Assert.Null(visit.Notes)` in `IntegrationTests/CollectionPropertyIndependenceTests.cs`,
which passes `null` in and reads `null` back through exactly this pointer wire), and the shared
helper exports that already take `COpaquePointer?` (`nuget_scope_cancel`, `nuget_job_cancel`).
**Verified** (shipped code + CI-run tests); no new spike needed for the pointer mechanism.

## Alternatives Considered

### 1. Null pointer in the component slot, uniformly for all component kinds (chosen)

`IntPtr.Zero` in an `add`/`put` slot means "this element is null"; a null pointer out of a read
export means the same. No has-value pairs at component positions, ever, because the slot is
already a pointer.

- Pros: zero new exports; the C-level signatures are unchanged (`void*` in C either way, and the
  C# `DllImport` spellings stay `IntPtr`); one rule for every component kind including `Int?`
  (which at *ordinary* positions needs the has-value pair); composes with ADR-081's value-class
  projection by simple `?.` lifting on both sides; the read-side fix (`!!` → `?.let`) falls out
  of the same signature change.
- Cons: `Wrap<T>` needs a null guard and a `Nullable.GetUnderlyingType` normalization (one line
  each, spike-verified below); C# read loops need a two-statement form (`IntPtr h = ...;` then a
  ternary) instead of the current single expression.

### 2. Per-kind null-sentinel exports (`nuget_list_add_null()` etc.)

Keep the three write exports non-nullable and add explicit "append a null" exports.

- Pros: no signature change to existing exports.
- Cons: three new exports and a branch at every C# write site for strictly less capability
  (the read side would *still* need the nullable-return change, so the "don't touch the
  signatures" benefit evaporates); no ambiguity problem exists that would justify the split,
  since the null pointer is in-band and unambiguous. Rejected.

### 3. Has-value pair per element (mirror ADR-061/079)

Fan each slot out to `(hasValue, valuePtr)`.

- Pros: symmetry with ordinary-position nullable primitives.
- Cons: that pattern exists *because* ordinary primitive positions have no pointer; here every
  slot is a pointer, so the pair is a second redundant channel, doubling the `put` arity and the
  read protocol (a `has_value_at` export per kind) for nothing. Rejected.

### 4. Dedicated "null box" sentinel handle

A singleton Kotlin object whose handle means null, obtained via a new export and compared by
value on the C# side.

- Cons: one more export, one more thing to registrar-cache on the C# side, and it is just a
  worse-performing spelling of the null pointer. Rejected.

## Decision

Alternative 1. The null pointer is the null representation in every collection component slot, in
both directions. Both the write side and the read side ship together (the read-side `!!` NPE is a
shipped bind-then-break, exactly the class ADR-081 chose to fix rather than defer, and it rides
the same signature change).

### Verification spike (Wrap dispatch semantics)

Scratch net10.0 console app (`dotnet run`, 2026-08-08; C# language/BCL semantics, not
runtime-version-sensitive), probing the three claims the `Wrap<T>` change rests on:

```
typeof(int?)==typeof(int): False
Wrap<int?>(3) = 1003          // Nullable.GetUnderlyingType normalization + (int)(object) unbox works
Wrap<int?>(null) = 0          // leading `if (value == null) return IntPtr.Zero;` works for T = int?
Wrap<string?>(null) = 0       // and for reference T
Wrap<int>(5) = 1005           // and non-nullable T still dispatches after normalization
```

**Verified**: (a) `typeof(T)` checks do not match the nullable spelling, so without normalization
`Wrap<int?>` mis-dispatches to the reflective fallback and throws even for non-null values;
(b) `Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T)` normalizes the dispatch and
`(int)(object)value!` unboxes a boxed `int?` correctly; (c) `value == null` on unconstrained `T`
is valid and false for non-nullable value types.

### Wire and codegen mechanism

Kotlin exports (`exports/GenericClassExports.kt`), all **signature-level** changes:

- `nuget_list_add`: `element: COpaquePointer?`, body
  `handle.asStableRef<MutableList<Any?>>().get().add(element?.asStableRef<Any>()?.get())`.
- `nuget_set_add`: same shape (`element?.asStableRef<Any>()?.get()`).
- `nuget_map_put`: `key: COpaquePointer?`, `value: COpaquePointer?`, both sides `?.`-lifted.
  (The key slot goes nullable at the wire even though the planner never admits a nullable key,
  see Scope; a defensive wire beats an NPE if a gate is ever wrong.)
- `nuget_list_get`, `nuget_set_element_at`, `nuget_map_key_at`, `nuget_map_value_at`: return
  `COpaquePointer?`, body `...[index]?.let { StableRef.create(it).asCPointer() }` (set/map keep
  their `toList()` step). This closes the `!!` NPE for **every** returned collection, including
  shapes admitted long before this ADR.

C ABI: unchanged. A `COpaquePointer?` is `void*` in the generated header exactly like
`COpaquePointer` (**verified by shipped precedent**: `nuget_scope_cancel` already exports a
`COpaquePointer?` parameter against a C# `IntPtr` import). The C# `DllImport` declarations in
`CirMarshalRenderer.kt` do not change at all.

Forward ABI contract (ADR-055/078): **nothing to do, verified in source.** The forward check is a
generation-time structural equality between the C# imports and Kotlin exports *of the same build*
(`ForwardAbiContract.assertMatches`), not a persisted hash (the persisted `contractHash` is the
reverse bridge, ADR-054). Its Kotlin-side normalizer strips nullability
(`kotlinType`'s `toString().removeSuffix("?")`, `ForwardAbiContract.kt:339`) and maps both
spellings to `POINTER`, and the C# side is `IntPtr` → `POINTER` throughout, so every signature in
the canonical text is byte-identical before and after this change. The ROADMAP item's "changes
the forward ABI contract hash" is wrong in both halves: no forward hash exists, and the C-level
signature does not change. Mixed shim/native versions cannot arise in the forward direction (the
shim and the native library ship in one nupkg from one generation).

C# marshal layer (`cir/CirMarshalRenderer.kt`):

- `Wrap<T>` gains, in order: `if (value == null) return IntPtr.Zero;` then
  `var type = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);` with the six existing checks
  comparing `type` instead of `typeof(T)` (**verified by the spike above**). The reflective
  `_handle` fallback and `NotSupportedException` are unchanged, so every currently-throwing shape
  still throws identically. `CreateList`/`CreateSet`/`CreateMap` bodies are unchanged;
  `Add`/`Put` receiving `IntPtr.Zero` is now a legal null element.

Planner gates (`forward/ForwardCallablePlanner.kt`):

- `isWrappableComponent()` gains
  `is BridgeType.Nullable -> type.isWrappableComponent()` (inner not itself `Nullable`;
  `isBridgeableComponent` already enforces no nested nullable). This admits `Map` **values**,
  `Set` elements and (via ADR-075's shared predicate) collection property setters with nullable
  components over the existing wrappable set: `String`, the five wide primitives,
  `ObjectHandle`, and (ADR-081) a value class over its four underlyings.
- `collectionInputSkipReason()`'s `MAP` branch additionally requires the **key** to be
  non-nullable: `key !is BridgeType.Nullable && key.isWrappableComponent()`. A C#
  `Dictionary<TKey, TValue>` cannot hold a null key (throws `ArgumentNullException`; inferred
  from documented BCL behaviour, not spiked), so a nullable-key map has no idiomatic C#
  projection and skips named.
- `collectionInputSkipReason()`'s `LIST`/`MUTABLE_LIST` branch: a `Nullable` element is admitted
  only when its inner `isWrappableComponent()`; every other nullable element
  (`Char?`, narrow primitives, bare `Enum?`, `Instant?`, nested `Collection?`) becomes a named
  `COLLECTION` skip instead of today's KSP crash. This narrowing has **zero backward-compat
  cost**: no nullable component binds at any input position today (Map/Set skip, List crashes),
  so unlike the deferred non-null `List` narrowing decision there is no working shape to remove.
  The non-null `List` element gate (`isBridgeableComponent()`) is untouched; that behaviour-change
  decision stays deferred with its own ROADMAP item.

Kotlin lowering (`forward/ForwardKotlinPlanEmitter.kt`):

- `componentLowering` gains `is BridgeType.Nullable ->`:
  - plain inner (String/wide primitive): `it as kotlin.String?` / `it as kotlin.Int?` (a Kotlin
    `as T?` cast accepts null; language semantics, not spiked);
  - `ObjectHandle` inner: `it?.let { v -> v as Patient }`, i.e. `it as Patient?`;
  - `ValueClass` inner: `(it as kotlin.String?)?.let { v -> ChartId(v) }`, enum underlying
    `(it as kotlin.Int?)?.let { v -> Temperament(Mood.entries[v]) }`, composing ADR-081's
    re-wrap under a `?.let` so the value class's `init` runs only for non-null elements.
- `componentRaising` (read side) `?.`-lifts for `Nullable(ValueClass)`: `it?.value`
  (`it?.mood?.ordinal` for the enum underlying). Plain nullable components stay identity: the
  box already holds `Any?` and the nullable read exports now carry the null through.

C# codegen (`forward/ForwardCirCollectionComponents.kt`, both CIR projections):

- `collectionCreateArgument`: for `Nullable(ValueClass)` components project with `?.`:
  `Select(ids, x => x?.Value)` (`string?`/`double?` wire), enum underlying
  `Select(ids, x => x == null ? (int?)null : (int)x.Value.Mood)`, ObjectHandle underlying
  `x?.Patient`. Plain nullable components pass through unchanged; `Wrap<T>`'s null guard and
  normalization do the rest.
- `collectionComponentRead`: for a `Nullable` component the read becomes two statements per slot:
  `IntPtr h = NugetListNative.Get(listHandle, i);` then
  `result.Add(h == IntPtr.Zero ? null : <existing non-null read of the inner>);` with the
  value-class re-wrap inside the non-null arm
  (`h == IntPtr.Zero ? (ChartId?)null : new ChartId(NugetMarshal.FromHandle<string>(h))`).
  Non-nullable components keep their exact current single-expression rendering.

The C# element spelling needs no work: `BridgeType.Nullable.csharpType()` renders `"${inner}?"`
(**verified**, relied on by ADR-077), and the generated file is `#nullable enable`
(**verified**, `cir/CirRenderer.kt:5`), so `List<int?>`, `IReadOnlySet<string?>`,
`IReadOnlyList<ChartId?>` (= `Nullable<ChartId>` over the `readonly record struct`) all mean
exactly what a C# consumer expects.

### Expected consumer C# surface

```csharp
// Kotlin: fun tag(labels: List<String?>): Int          (was: KSP crash)
public static int Tag(IReadOnlyList<string?> labels);

// Kotlin: fun observe(ids: Set<ChartId?>)              (was: named skip)
public static void Observe(IReadOnlySet<ChartId?> ids); // ChartId? is Nullable<ChartId>

// Kotlin: fun scores(): Map<String, Int?>              (was: bound, NPE at first null read)
public static IReadOnlyDictionary<string, int?> Scores();

// Kotlin: var notes: List<String?>                     (getter was NPE-on-null; setter was skip)
public IList<string?> Notes { get; set; }
```

`null` round-trips: a C# `null` element arrives in Kotlin as `null` inside the copied collection;
a Kotlin `null` element arrives in C# as `null` in the materialized `List`/`HashSet`/`Dictionary`
value slot. ADR-011 eager-copy semantics are unchanged.

## Consequences

- Closes the ROADMAP nullable-components item for: `List`/`MutableList`/`Set`/`MutableSet`
  elements and `Map`/`MutableMap` **values**, at method/constructor parameters, collection
  property setters, method returns and property getters, for nullable `String`, wide primitives
  (`Int?`, `Long?`, `Float?`, `Double?`, `Boolean?`), `ObjectHandle`, and value classes over the
  four ADR-081 underlyings (the motivating `List<ChartId?>`).
- Closes the read-side `!!` NPE for **all** returned collections, including previously-admitted
  shapes (`List<String?>` returns bound-and-broke; now correct end to end).
- **Sibling-item overlap, stated precisely**: of the "`List<T>` input components are not
  narrowed" item's crash shapes, this ADR closes `List<String?>` (and every
  `List<wrappable?>`), and converts the *other* nullable crash spellings (`List<Mood?>`,
  `List<Short?>`, `List<Char?>`, `List<List<String>?>`) into named `SKIPPED_UNSUPPORTED_INPUT`
  skips via the narrowed nullable-element gate. Remaining with that item, untouched: the
  `List<List<String>>` non-null nested crash, and the three non-null bind-then-throw shapes
  (`List<Mood>`, `List<Short>`, `List<Char>`). The deferred behaviour-change decision (narrowing
  the *non-null* `List` element gate, which would remove callables that bind today) **can stay
  deferred**: this ADR only narrows nullable spellings, none of which bind today.
- **Promotes an existing ADR-075 fixture cell**, which the sections above did not call out: the
  sample `Chart.aliases` (`var aliases: List<String?>`) was ADR-075's *ineligible* setter example
  precisely because its element is nullable, so it flips from get-only to settable here and its
  shape-only integration test (`Chart_Aliases_NullableElement_HasNoPublicSetter`) becomes a
  round-trip test. `Chart.moods` (an enum element) remains the ineligible-setter cell.
- Nullable **map keys** are excluded at input positions (named skip): `Dictionary` cannot hold a
  null key. Result-position gates are deliberately untouched: a `Map<String?, Int>` *return*
  still binds (as today); with the read fix, an actual null key now surfaces as a C#
  `ArgumentNullException` at `result[key] = value` instead of a Kotlin-side NPE inside the
  export. Whether to narrow that result gate is left with the narrowing family.
- Deferred: nullable `Char`/narrow-primitive/bare-enum components (ride the sibling
  enum/narrow-primitive item and ADR-075's type-specialized-boxing reframing); nested-collection
  components (sibling item); `Instant` components (ADR-076 deferral); nullable map keys as a
  supported shape; the reverse direction.
- Latent defects found during this research, **not** fixed here (recorded so they are not
  mistaken for regressions):
  1. Generic-class property getters (`export_<cls>_get_<prop>`) force `!!`
     (`GenericClassExports.kt:93`): a nullable property on an exported generic class NPEs at
     read. Same `!!` family, different export family; out of scope.
  2. `nuget_func1_invoke`/`func2`/`func3`/`suspend_func1` unconditionally
     `arg0.asStableRef<Any>().get()`: a null lambda argument cannot cross the callback bridge.
     Out of scope (lambda-parameter nullability is its own surface).
  3. A `Map<String?, Int>` return renders `Dictionary<string?, int>`, which violates
     `Dictionary`'s `TKey : notnull` constraint → CS8714 warning in the generated,
     `#nullable enable` file (inferred from C# rules, not compiled). Pre-existing.
  4. ROADMAP line 117's "planner correctly refuses these shapes ... (Verified)" is wrong for
     `List<Foo?>` (it crashes, per line 116); the ROADMAP text should be corrected when this
     ships.
- Fixture/test surface: a `test-library` sample with `List<String?>`, `Set<ChartId?>`,
  `Map<String, Int?>` parameters, a `Map<String, Int?>` return carrying an actual null, and a
  nullable-key `Map` input asserting the named skip; C# integration tests assert null round-trips
  and `Tier1` cells assert the new named skips.

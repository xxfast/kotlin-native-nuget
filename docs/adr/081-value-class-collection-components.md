# ADR-081: Value-class collection components: cross as the underlying, re-wrap per element

## Status
Accepted

## Context

ADR-077 closed value classes at ordinary positions (parameter, return, property, nullable) but
explicitly deferred "value class as a collection element" to its own ROADMAP item. Today
(all **verified by spike** in `test-library`, KSP run on `macosArm64`, 2026-08-08, spike fixture
reverted):

1. **`List<ChartId>` method parameter crashes the whole KSP run** (and therefore `packNuget`):

   ```
   e: [ksp] java.lang.IllegalStateException: Forward Kotlin plan emitter has no element type name
   for ValueClass(qualifiedName=...ChartId, underlying=String, underlyingPropertyName=value,
   csharpType=ChartId)
   ```

   Mechanism (**verified** in source): `collectionInputSkipReason()`'s `LIST`/`MUTABLE_LIST` branch
   admits any `isBridgeableComponent()` element, and `isBridgeableComponent()` recurses into a
   `ValueClass`'s underlying (`ForwardCallablePlanner.kt:1814`), so the callable plans; then
   `loweredCollectionExpression` calls `elementKotlinTypeName`, which has no `ValueClass` branch
   (`ForwardKotlinPlanEmitter.kt:472-479`) and `error(...)`s.

2. **`Map<String, ChartId>` / `Set<ChartId>` parameters skip, named** (**verified by spike**):

   ```
   w: [ksp] ...: [nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping ...ChartBook.sectioned: its COLLECTION
   type combination is not supported. expose a wrapper taking a List/MutableList (or individual
   key/value parameters) instead of a Map/Set at this position
   ```

   Mechanism: `isWrappableComponent()` (ADR-073) has no `ValueClass` branch, `else -> false`.
   Note the hint is actively wrong for this component: following it (a `List` of the same element)
   crashes the build per finding 1.

3. **`List<ChartId>` method return and property BIND today and are silently broken at runtime.**
   The spike produced no diagnostic; the generated C# (**verified**, `Interop.cs`) reads elements
   with `NugetMarshal.FromHandle<ChartId>(NugetListNative.Get(...))`, and the generated Kotlin
   export boxes each element as `StableRef.create(list[index]!!)`, i.e. a handle to the boxed
   Kotlin `ChartId` instance. `FromHandle<ChartId>` matches no primitive/string branch and falls to
   the `Activator.CreateInstance(typeof(ChartId), ..., new object[] { handle })` fallback; the
   generated `ChartId` struct's only constructor takes `string` (**verified** in generated
   `Interop.cs`), so the first element read throws. **Inferred (not executed):** the exception is
   `MissingMethodException`; the run stopped at codegen inspection, the runtime throw itself was
   not executed. The bind-then-break shape is verified either way. `Set`/`Map` returns with
   value-class components ride the same `isBridgeableComponent()` gate and the same `FromHandle`
   read loops, so they are **inferred** to be broken identically (not spiked separately).

Constraints inherited from shipped decisions:

- ADR-014/033/035/077: a value class surfaces in C# as a `readonly record struct` over its
  underlying; it never carries `_handle`; it crosses ordinary positions as its underlying wire
  value with box/unbox composed at the boundary.
- ADR-011: collections cross as an eager, detached copy in both directions; no write-back.
- ADR-073: `Map`/`Set` inputs cross by C#-side boxing (`NugetMarshal.Wrap<T>` +
  `nuget_map_put`/`nuget_set_add`); `List` inputs likewise via `CreateList`.
- ADR-075 reframing: `Wrap<T>` is a single runtime-generic helper (six `typeof(T)` checks, then a
  reflective `_handle` lookup, then `NotSupportedException`), even though codegen knows every
  element's static type (**verified**, `cir/CirMarshalRenderer.kt:226-238`).

## Alternatives Considered

### 1. Project to the underlying at the boundary, re-wrap per element (chosen)

The collection's wire representation carries the **underlying** values, never the value class:

- **Input (C# → Kotlin):** the C# call site lowers each element before boxing:
  `NugetMarshal.CreateList(ids.Select(x => x.Value))`. That instantiates `Wrap<T>` at the
  underlying type (`string`, `double`, an object-handle class), where branches already exist
  (**verified** against `Wrap<T>`'s body: `string` and the five wide primitives are explicit
  branches; an ObjectHandle underlying hits the existing reflective `_handle` fallback, which the
  underlying class does carry). The Kotlin lowering re-wraps per element:
  `it as kotlin.String` becomes `ChartId(it as kotlin.String)` composed in
  `loweredCollectionExpression`, with `elementKotlinTypeName` gaining a `ValueClass` branch that
  returns the *underlying's* name.
- **Result (Kotlin → C#):** the Kotlin result emission maps the collection to underlyings before
  creating the handle (`StableRef.create(result.map { it.value })`), so `nuget_list_get` boxes a
  `String`, which the existing `nuget_unwrap_string` path can read. The C# read loop re-wraps:
  `result.Add(new ChartId(NugetMarshal.FromHandle<string>(NugetListNative.Get(h, i))));`.

Pros:
- **Zero new native exports** for every underlying the wire can already box: String, the six
  wrappable primitives, ObjectHandle (**verified**: all needed `nuget_wrap_*`/`nuget_unwrap_*`
  exports and the `_handle` path exist today).
- Exactly ADR-077's wire design (underlying value + box/unbox composed at the seam) extended to
  the element position; the C# consumer surface stays `IReadOnlyList<ChartId>` /
  `IReadOnlyDictionary<string, ChartId>` etc., which is what already renders (`csharpType` has a
  `ValueClass` branch, **verified** by the spike's generated `IReadOnlyList<ChartId>`).
- The extra Kotlin-side `map { it.value }` copy on the read path is consistent with ADR-011's
  eager-copy semantics; there is no live view to preserve.
- C#-side re-wrap (`new ChartId(raw)`) re-runs the value class's `init` validation via
  `chartid_create`, identical to ADR-077's ordinary-position reconstruction cost: per element
  rather than per call, but the same shape, not a new mechanism.

Cons:
- Per-element `.Select(...)`/`.map { ... }` allocation on both sides (bounded by ADR-011's
  existing eager copy, which already iterates every element).
- The re-wrap constructor P/Invokes once per element on the C# read side (validation round-trip).

### 2. Box the value-class instance, teach C# to read the box

Keep `StableRef.create(boxedChartId)` element boxes (today's accidental read-side wire) and give
C# a way to consume them: either an `IntPtr` constructor on the struct for the `Activator` branch,
or per-value-class `nuget_unwrap_<vc>` exports.

- Pros: no Kotlin-side element mapping.
- Cons: an `IntPtr` ctor on a public `readonly record struct` pollutes the consumer surface and
  invites misuse (ADR-077 alternative 2 rejected the handle-backed shape for the same reason);
  per-value-class unwrap exports are new ABI surface for every declared value class; the input
  side would still need boxing of a struct that carries no `_handle`. Rejected.

### 3. Widen the runtime-generic `Wrap<T>`/`FromHandle<T>` with reflective `.Value` discovery

Add a reflection branch that finds a value-class struct's underlying property at runtime.

- Cons: doubles down on exactly the runtime-generic dispatch ADR-075's reframing identifies as
  the real blocker; reflection per element; the Kotlin side still needs the re-wrap branch, so
  this saves nothing structural. Rejected.

## Decision

Alternative 1, shipped as the **full option** (per the Step 2 human gate: feature complete, no
new deferrals within the value-class family). The value-class component crosses as its
**underlying**, with the box/unbox composed per element at the boundary, in codegen (static
types), not in the runtime-generic helpers. Concretely:

1. **Input positions**: `List`/`MutableList`/`Map`/`MutableMap`/`Set`/`MutableSet` parameters
   (every call position ADR-073 covers) and collection property setters (ADR-075's shared
   predicate), with a value class admitted at the **element, key and value** positions.
2. **All four ADR-077 underlyings admitted**: String, the wrappable primitives (`Int`, `Long`,
   `Float`, `Double`, `Boolean`), ObjectHandle, **and enum-underlying value classes**, the last
   riding the existing int-ordinal wire through `nuget_wrap_int` (C# pre-casts
   `(int)x.Mood` before boxing; **verified** `nuget_wrap_int` exists in
   `cir/CirMarshalRenderer.kt` and its Kotlin export). Bare enum (and narrow-primitive)
   components explicitly remain the sibling ROADMAP item; only the value-class wrapper over an
   enum is admitted here.
3. **The read side is in scope and fixed**: method returns and property getters map elements to
   underlyings on the Kotlin side before boxing, and C# re-wraps per element, closing the
   verified bind-then-break from Context finding 3. No interim "skip instead of bind" narrowing
   is needed; the fix ships with this ADR.

Relationship to ADR-075's type-specialized-boxing reframing: **orthogonal, not a prerequisite.**
Because the C# call site projects to the underlying (`x.Value`, or `(int)x.Mood` for the enum
underlying) before `Wrap<T>` is instantiated, `Wrap<T>` only ever sees types it already handles.
Type-specialized boxing remains the unblock path for the *sibling* bare enum/narrow-primitive
component item.

### Mechanism (all sites verified in source at HEAD unless labelled)

Input side, per collection kind:

- `elementKotlinTypeName` (`ForwardKotlinPlanEmitter.kt:472`): no change to its role, but the
  `ValueClass` composition happens in `loweredCollectionExpression`: the cast targets the
  underlying (`it as kotlin.String`) and wraps (`ChartId(...)`), reusing
  `valueClassUnderlyingLowering`'s inverse the way ADR-077's ordinary input lowering does
  (`loweredArgument`'s `"${type.qualifiedName}(${parameter.name})"`).
- `isWrappableComponent()` (`ForwardCallablePlanner.kt:1855`): add
  `is BridgeType.ValueClass ->` admitting the four underlyings above (String / wrappable
  primitive / ObjectHandle via `underlying.isWrappableComponent()`, plus `is BridgeType.Enum`).
  This admits `Map`/`Set` inputs (key and value positions independently) and (via ADR-075's
  reuse) collection **property setters** with value-class components; `List` inputs already pass
  the wider `isBridgeableComponent()` gate. Note the enum case must be scoped to the value-class
  branch only: `isWrappableComponent()`'s top-level `Enum` case stays `false` (bare enum
  components are the sibling item).
- Kotlin lowering per underlying, composed inside `loweredCollectionExpression`'s element lambda
  (map: both the key and value slots of the `associate` lambda), mirroring ADR-077's
  `valueClassUnderlyingLowering` (**verified**, `ForwardKotlinPlanEmitter.kt:916-921`):
  String/primitive `ChartId(it as kotlin.String)`; enum
  `Temperament(Mood.entries[it as kotlin.Int])`; ObjectHandle `ChartRef(it as Patient)` (the
  generic box already holds the underlying instance).
- C# projection (`ForwardCirPlanProjection` / `ForwardCirPropertyProjection` create-call
  arguments): when a component is a `ValueClass`, wrap the source sequence in a per-element
  projection to the underlying, e.g. `charts.Select(x => x.Value)` (String/primitive underlying),
  `charts.Select(x => (int)x.Mood)` (enum underlying, hitting the existing `Wrap<int>` branch),
  or `charts.Select(x => x.Patient)` (ObjectHandle underlying, letting `Wrap<T>`'s existing
  `_handle` path fire); for a map, project the `KeyValuePair` sides independently.
  **Verified:** `System.Linq` is *not* in the generated usings
  (`CirTranslator.kt:469-483` builds the list; no Linq entry), so either add `System.Linq` to
  `usings` when a value-class component input is rendered, or emit a `foreach` projection loop
  instead of `.Select`.

Result side (method return, property getter; the shapes that bind-and-break today):

- Kotlin result emission (`ForwardKotlinPlanEmitter`'s handle-result path, and the property
  getter equivalent): for a collection whose component is a `ValueClass`, box a projected copy:
  `StableRef.create(result.map { it.value })` (enum underlying: `it.mood.ordinal`, boxing an
  `Int`; ObjectHandle underlying: the underlying instance, which the generic `nuget_list_get`
  then boxes as an object handle; Map: `mapKeys`/`mapValues` as applicable).
- C# read loops (`ForwardCirPlanProjection.kt:1026-1075`, `ForwardCirPropertyProjection.kt:325-356`):
  `FromHandle<{underlyingCsType}>` then re-wrap, `new ChartId(raw)` / `new ChartRef(patient)` /
  `new Temperament((Mood)FromHandle<int>(...))`.

No forward-ABI change: no new exports, no signature change to `nuget_list_*`/`nuget_map_*`/
`nuget_set_*`, so the ADR-054/078 contract hash is unaffected by the wire itself (the generated
export *bodies* change, which is ordinary codegen).

### Out of scope, with their existing ROADMAP checkboxes

- **Nullable value-class components** (`List<ChartId?>`): the sibling nullable-collection-
  components item (ROADMAP "Nullable collection components ... have no representation on the
  write side"), which owns the `COpaquePointer?` export change. They keep their named skip.
- **Bare enum and narrow-primitive components** (`List<Mood>`, `List<Short>`): the sibling
  enum/narrow-primitive components item, with ADR-075's type-specialized-boxing reframing as its
  unblock path. Only the value-class *wrapper* over an enum is admitted by this ADR.
- **`List<String?>` and nested-list (`List<List<String>>`) input crashes**: the sibling
  "`List<T>` input components are not narrowed" item. This ADR removes only the value-class
  crash shape from that item's list.
- **The generic `COLLECTION` hint wording** ("expose a wrapper taking a List/MutableList ..."),
  which remains wrong for the nullable/nested component shapes above: stays with those two
  sibling checkboxes, whose fixes are what make the hint truthful.

## Consequences

- `List`/`MutableList`/`Map`/`MutableMap`/`Set`/`MutableSet` with value-class components (element,
  key or value) bind at input positions, property setters, method returns and property getters,
  for String, wrappable-primitive, ObjectHandle and enum underlyings; the verified
  bind-then-break on returns/getters is closed.
- The `List<StoryCode>`-element crash shape moves out of the sibling "`List<T>` input components
  are not narrowed" ROADMAP item (its `List<String?>` and `List<List<String>>` crash shapes
  remain there, untouched).
- Deferred (each to its named sibling checkbox above, none newly created by this ADR): nullable
  value-class components; bare enum/narrow-primitive components; nested-collection components;
  the reverse direction.
- The sibling items (`List<T>` narrowing, nullable components, enum/narrow-primitive components,
  ADR-075 type-specialized boxing) are all left open by design.
- **Found during implementation, out of scope:** `ForwardCallablePlanner.nullableResultShape()` has
  no `is BridgeType.Collection` branch and falls to its final `else -> null`, so a nullable
  collection **method return** (`fun x(): List<ChartId>?`) has no planner route at all and is
  silently skipped, for every component type, not only a value-class one. `Nullable(Collection)`
  already binds at a property and as an ordinary parameter ([ADR-075](075-collection-property-getter-setter-independence.md)),
  so only the method-return position is missing. This predates this ADR and is not something its
  own scope could have closed; recorded here so it is not mistaken for a regression this feature
  introduced. Tracked as its own ROADMAP item.

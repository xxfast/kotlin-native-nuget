# ADR-099: Forward, nested collection components: the inner handle in the component slot, disposed by whoever minted it

## Status
Accepted

> **Numbering note.** `docs/adr/` held ADRs up to `097` when this was written and `098` was unclaimed;
> another research agent was drafting concurrently and may take `098`. This ADR deliberately takes the
> higher free number. If `098` turns out unused, renumber before merge.

## Context

A consumer writing

```kotlin
fun logGrid(rows: List<List<String>>): String
```

gets **no** C# member today. [ADR-097](097-enum-collection-components.md) narrowed `List`'s callable-input
gate from `isBridgeableComponent()` to `isWrappableComponent()`, so this shape went from aborting the whole
KSP run (`elementKotlinTypeName`'s `error(...)`) to a clean named `SKIPPED_UNSUPPORTED_INPUT`. The crash is
closed; the capability is not. `Map`/`Set` inputs have skipped this shape since
[ADR-073](073-map-and-set-parameters.md).

The **return** position is a different and worse story, and it is not symmetric with the input position:

> **Verified (source reading, `ForwardCallablePlanner.kt:2358-2376`, `:1798`, `:2156`;
> `ForwardCirPlanProjection.kt:1305-1316`, `:1160-1180`; `CirMarshalRenderer.kt:235`, `:77-81`):** a nested
> collection *return* is admitted today. `isBridgeableComponent()` recurses through `Collection`, the result
> gate uses it, `csharpType()` recurses and renders `IReadOnlyList<IReadOnlyList<string>>`, and
> `collectionMaterializingCore` emits `NugetMarshal.FromHandle<IReadOnlyList<string>>(...)`. `FromHandle<T>`
> has no branch for that `T`, falls to `Materialize<T>`, finds no `Factories` entry, and throws
> `NotSupportedException`. So `fun grid(): List<List<String>>` **binds, compiles, and throws at the first
> read**: the same shipped landmine shape `List<Mood>` had before ADR-097, one position over.
>
> **Inferred (not runtime-reproduced this round):** the exception is thrown at the first element read, not at
> call time, so a consumer sees a partially-executed method. No `test-library` fixture declares a nested
> return (**Verified**: `grep` over `test-library/src` finds none), which is why nobody has hit it.

Three mechanisms constrain the design, all **Verified** by source reading:

1. `nuget_list_add` / `nuget_set_add` / `nuget_map_put` (`exports/GenericClassExports.kt:176-190`, `:229-238`,
   `:298-311`) each **dereference** the element pointer (`element?.asStableRef<Any>()?.get()`) and store the
   *object* in the Kotlin container. They never dispose the box, and they never keep the pointer.
2. `NugetMarshal.Wrap<T>` (`cir/CirMarshalRenderer.kt:242-259`) returns two ontologically different pointers
   from one function: a **freshly minted** `nuget_wrap_*` box for a primitive or string, and the **live,
   caller-owned** `wrapper.Handle` for an `INugetHandle`. It reports no ownership.
3. `nuget_list_get` / `nuget_set_element_at` / `nuget_map_key_at` / `nuget_map_value_at` mint a **fresh**
   `StableRef` per read (`StableRef.create(it).asCPointer()`), and `FromHandle<T>` disposes it on every
   primitive/string branch (`Native_dispose(handle)`, one per branch).

Fact 2 is why `ROADMAP.md:143`'s suggested fix for the happy-path box leak ("`nuget_list_add` disposing the
element handle it reads") is **wrong as written**: for an object-handle component that pointer is the C#
wrapper's own live handle, and disposing it Kotlin-side is a use-after-free on the next use of that wrapper.
Any ownership answer has to be made on the **C# side, where the mint happened**.

The load-bearing question this ADR must answer: on the write side, an outer `CreateList` over a nested element
mints an inner native handle per element. If the outer factory throws at element 5 of 10, who frees the five
inner handles already alive?

## Alternatives Considered

### 1. The inner handle is the component's wire value; `Wrap` reports ownership; the factory disposes what it owns (chosen)

A nested component's wire type is `IntPtr`: the inner collection is built first by the *same* `CreateList` /
`CreateSet` / `CreateMap` helpers, and its handle goes straight into the outer's component slot. `Wrap<T>`
gains an `IntPtr` identity branch and an `out bool owned`; the three factories dispose the element box after
`Add`/`Put` when they own it.

Pros:
- **Answers the ownership question by making the window one statement wide.** No inner handle is ever alive
  across more than one loop iteration, so "the outer throws at element 5" has no five live handles to clean
  up. Elements 0..4's boxes were disposed the instant each `Add` returned; their *objects* are owned by the
  outer Kotlin list, which the existing `catch { Dispose(listHandle); throw; }` guard
  (`CirMarshalRenderer.kt:378-392`) frees.
- **Closes `ROADMAP.md:143` (the happy-path `Wrap<T>` box leak) in the same three lines**, for every component
  kind at once, without the use-after-free that ROADMAP's stated fix would introduce.
- The recursion falls out of the four functions that already exist
  (`collectionCreateArgument` / `componentWireExpression` / `componentWireCsharpType` on the write side,
  `componentReadExpression` on the read side), so arbitrary depth and every outer/inner kind combination are
  the same code as depth 1.

Cons:
- Changes `Wrap<T>`'s signature, so all three factories change together (one file).
- The read side needs three new helpers (`ReadList`/`ReadSet`/`ReadMap`), because the shipped read is inlined
  codegen with fixed local names (`listHandle`, `count`, `result`, `i`) that cannot nest.

### 2. Kotlin-side disposal in `nuget_list_add` (rejected, unsafe)

Have the Kotlin export dispose the element box it just dereferenced. One line, no C# change, fixes the leak
for nesting and scalars alike.

Rejected: **Verified** that `Wrap<T>` returns `wrapper.Handle` for an `INugetHandle` component
(`CirMarshalRenderer.kt:257`), which is the C# wrapper's live handle, not a transfer box. Disposing it
Kotlin-side frees a handle the wrapper will use again. `List<Patient>` is a shipped, working shape; this would
silently corrupt it. Recorded here because it is the fix `ROADMAP.md:143` proposes, and the next agent to read
that line should not implement it.

### 3. Accumulate inner handles, dispose in a `finally` at the end of the factory (rejected)

`CreateList` keeps a `List<IntPtr>` of minted inner handles and disposes them all in a `finally`.

Rejected: allocates per call, keeps every inner handle alive for the whole build (so a 10k-element list roots
10k StableRefs at once instead of one), and still needs the owned/not-owned distinction to avoid alternative
2's bug. Strictly worse than disposing one iteration at a time.

### 4. Leave the boxes leaking, consistent with today (rejected)

Ship nesting on top of the existing leak: don't dispose anything, matching `ROADMAP.md:143`'s status quo.

Rejected: nesting does not merely inherit that leak, it **multiplies** it. `List<List<String>>` of n×m leaks
n leaked inner list handles **plus** n×m leaked leaf boxes, and each leaked inner handle roots an entire
Kotlin collection for process lifetime rather than a boxed scalar. Shipping a feature whose leak scales with
the product of the dimensions is a different decision from tolerating an existing one.

### 5. Inline nested loops in codegen with depth-suffixed locals (rejected)

Generate `for (int i0 …) { for (int i1 …) } }` with `listHandle0`/`listHandle1`, no runtime helper.

Rejected: the read-side codegen doubles per level; and it reproduces `ROADMAP.md:142`'s
"result handle leaks if materialization throws mid-loop" shape once **per nesting level**, which is exactly
the shape this ADR was told not to add a second instance of. A helper with a `finally` is leak-free by
construction at every inner level.

### 6. One level of nesting only (rejected on cost, not on scope)

Special-case depth 2 and keep `elementKotlinTypeName`'s `error(...)` for depth 3.

Rejected: costs *more* than the recursion. Every one of the four projection functions would need a depth guard
and a second skip reason, where the recursive form is a single `is BridgeType.Collection ->` arm that calls
the enclosing function. The type model (`BridgeType.Collection` holding `BridgeType` components) already
expresses arbitrary depth; refusing it takes extra code.

## Decision

A nested collection component crosses as **the inner collection's own native handle**, in the same
pointer-shaped component slot every other component already uses. No new native export, no ABI change.

### Write side

`componentWireCsharpType(Collection) = "IntPtr"`, and `componentWireExpression(access, Collection)` recurses
through the *existing* `collectionCreateArgument`:

```csharp
// Kotlin: fun logGrid(rows: List<List<String>>): String
public string LogGrid(IReadOnlyList<IReadOnlyList<string>> rows)
{
    IntPtr rowsHandle = IntPtr.Zero;
    try
    {
        rowsHandle = NugetMarshal.CreateList<IntPtr>(
            global::System.Linq.Enumerable.Select(rows, x => NugetMarshal.CreateList<string>(x)));
        IntPtr result = Native_logGrid(_handle, rowsHandle, out IntPtr error);
        // ... error check ...
    }
    finally { if (rowsHandle != IntPtr.Zero) NugetListNative.Dispose(rowsHandle); }
}
```

Composition with ADR-081/083/097 is free, because the inner argument is built by the same function that
builds a top-level one. `Map<String, List<Mood>>`:

```csharp
IntPtr runsHandle = NugetMarshal.CreateMap<string, IntPtr>(
    global::System.Linq.Enumerable.Select(runs, x => new KeyValuePair<string, IntPtr>(
        x.Key,
        NugetMarshal.CreateList<int>(global::System.Linq.Enumerable.Select(x.Value, y => (int)y)))));
```

`Wrap<T>` gains one branch and an ownership out-parameter:

```csharp
internal static IntPtr Wrap<T>(T value, out bool owned)
{
    owned = false;
    if (value == null) return IntPtr.Zero;
    var type = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);
    // ADR-099: a nested collection component arrives already boxed -- its handle IS the box, minted
    // by this call site's own projection, so this factory owns it.
    if (type == typeof(IntPtr)) { owned = true; return (IntPtr)(object)value!; }
    owned = true;
    if (type == typeof(string)) return nuget_wrap_string((string)(object)value!);
    // ... the five primitive branches, unchanged, all owned ...
    owned = false;
    if (value is INugetHandle wrapper) return wrapper.Handle;   // caller's live handle, never ours
    throw new NotSupportedException($"Cannot pass {typeof(T).Name} to a Kotlin collection");
}
```

and each factory disposes what it owns, one element at a time:

```csharp
foreach (T value in values)
{
    IntPtr element = Wrap(value, out bool owned);
    try { NugetListNative.Add(listHandle, element); }
    finally { if (owned) NugetListNative.Dispose(element); }
}
```

**Verified** that this is safe: `nuget_list_add` stores `element?.asStableRef<Any>()?.get()`, the
*dereferenced object*, into a `MutableList<Any?>` that is itself rooted by the outer `StableRef`
(`exports/GenericClassExports.kt:176-190`); `nuget_set_add` (`:229-238`) and `nuget_map_put` (`:298-311`) do
the same. Disposing the element's `StableRef` after the call releases a root, not the object.

**Inferred (not executed):** exception safety by induction over depth. `Select` is lazy, so an inner
`CreateList` runs inside the outer `foreach`, inside the outer's `try`. If an inner factory throws partway, its
own `catch { Dispose(inner); throw; }` frees the partial inner handle and the outer's `catch` frees the outer;
if `Add` throws, the loop's `finally` frees the element box. At every instant at most one inner handle is
alive and it is inside a `finally`'s protection. This is a reasoning claim about generated code, not a spike
result: see the spike request below. Its **premise** is no longer inferred, though. Spike 1 has since been run
and confirms by execution that the per-element dispose is safe, which is what makes "at most one inner handle
alive" the right invariant to aim for rather than a hazard. What stays unexecuted is the partial-failure path
itself: that a throw at element 5 of 10 actually unwinds through those `catch`/`finally` blocks as written.

### Kotlin write-side lowering

`componentLowering` gains a `is BridgeType.Collection ->` arm that casts to the **wire container** and
recurses:

```kotlin
// rows: List<List<String>>
rows.asStableRef<MutableList<Any?>>().get().map { e0 -> (e0 as MutableList<*>).map { e1 -> e1 as kotlin.String } }
```

Two details the implementer must not get wrong:

- **The `ROADMAP.md:137` framing is wrong about the seam.** It says this needs "a new `elementKotlinTypeName`
  branch for a collection-of-collection element". It does not: `elementKotlinTypeName` produces the *declared*
  Kotlin type used as an `as` cast target, and the cast target here is the **wire container**
  (`MutableList<*>` / `MutableSet<*>` / `MutableMap<*, *>`, whichever the C# side created), not
  `kotlin.collections.List<kotlin.String>`. `elementKotlinTypeName` gets no new branch; `componentLowering`
  gets a new arm ahead of its `else`. **Verified** by reading `componentLowering`
  (`ForwardKotlinPlanEmitter.kt:516-551`) and `loweredCollectionExpression` (`:1090-1129`).
- Use a **star projection** (`as MutableList<*>`), not `as MutableList<Any?>`: the star form is a checked cast
  with no `UNCHECKED_CAST` warning, and the elements are read as `Any?` regardless. **Inferred** (Kotlin cast
  rules, not compiled this round).
- Name each lambda parameter by depth (`e0`, `e1`) rather than relying on `it`. Nested implicit `it` shadows
  the enclosing lambda's, which is at best a compiler warning. **Inferred**, not compiled.

The inner conversion is `loweredCollectionExpression`'s per-kind body minus its `asStableRef(...).get()`
prefix, so the implementer should split that function into "dereference the handle" and "convert the
container", and call only the second half from `componentLowering`.

### Read side

Three new helpers in `NugetMarshal`, gated exactly like `CreateSet`/`CreateMap` are today
(`helper.includesSet` / `helper.includesMap`, plus the `List` gate `ROADMAP.md:141` says is missing, do not
copy `CreateList`'s unconditional emission):

```csharp
public static List<T> ReadList<T>(IntPtr handle, Func<IntPtr, T> read)
{
    try
    {
        int count = NugetListNative.Count(handle);
        var result = new List<T>(count);
        for (int i = 0; i < count; i++) result.Add(read(NugetListNative.Get(handle, i)));
        return result;
    }
    finally { NugetListNative.Dispose(handle); }   // the handle was minted by the Get/return that produced it
}
```

`ReadSet<T>` and `ReadMap<TKey, TValue>` mirror it (`HashSet<T>`, `Dictionary<TKey, TValue>`). The `finally`
means no inner level can reproduce `ROADMAP.md:142`'s mid-loop result-handle leak.

`componentReadExpression(handle, Collection)` becomes the recursive call, with `.AsReadOnly()` appended for
the immutable `LIST` kind so the inner matches the outer's shipped rendering (**Verified**:
`collectionMaterializingCore`'s LIST arm returns `result.AsReadOnly()` for `LIST` and `result` for
`MUTABLE_LIST`, `ForwardCirPlanProjection.kt:1160-1180`):

```csharp
// Kotlin: fun grid(): List<List<String>>
for (int i = 0; i < count; i++)
{
    result.Add(NugetMarshal.ReadList<string>(
        NugetListNative.Get(listHandle, i),
        static h => NugetMarshal.FromHandle<string>(h)).AsReadOnly());
}
```

When the inner component itself needs a declaration (ADR-083's nullable read, whose `CirComponentRead` carries
a `declaration`), the lambda is block-bodied rather than expression-bodied. **Inferred**: not exercised by any
current call site, since `collectionComponentRead` is only ever consumed at statement level today.

The **Kotlin** read side needs no change for a non-projecting inner component: `collectionResultProjection`
returns the invocation unchanged, `nuget_list_get` boxes the inner Kotlin `List` object, and
`nuget_list_count`'s `asStableRef<List<*>>()` reads it back (**Verified**). When the inner component *does*
project (`List<List<Mood>>`), `componentNeedsProjection()` must become recursive over `Collection` and
`componentRaising` gains a `Collection` arm mapping the inner elements: the same recursion, on the raising
side.

### Gate

`isWrappableComponent()` gains:

```kotlin
is BridgeType.Collection -> if (isMap) key/value both wrappable else element wrappable   // recursive
is BridgeType.Nullable -> type !is BridgeType.Nullable && type !is BridgeType.Collection && type.isWrappableComponent()
```

The second line is deliberate and load-bearing: **`List<List<String>?>` (a nullable *nested collection*) stays
a named `SKIPPED_UNSUPPORTED_INPUT`.** Without that guard, `isWrappableComponent`'s `Nullable` branch admits it
automatically the instant `Collection` becomes wrappable, and it would bind with a write projection that has no
null arm, the exact trap ADR-097 hit with `List<Mood?>`. A nullable *leaf* under nesting
(`List<List<String?>>`) **is** admitted, and rides the existing ADR-083 arms under the recursion.

Widening the predicate also, automatically:

- makes a collection **property setter** with a nested component eligible (`ForwardPropertyPlanner`'s
  `isSetterEligible()` uses the same predicate, **Verified** `ForwardPropertyPlanner.kt:321-330`), so
  `var grid: List<List<String>>` gains a public setter;
- reaches the `Map`/`Set` input positions and the constructor-parameter position with no further change.

## Consequences

- `List<List<String>>` and friends bind at every input position and at return positions; the shipped
  **bind-then-throw** nested *return* landmine is closed rather than left as a follow-up.
- **`ROADMAP.md:143` (happy-path `Wrap<T>` box leak) is closed here**, for scalars and nested handles alike,
  as a consequence of answering the ownership question rather than as a separate feature. Recommended
  explicitly: fixing it *is* the design, and leaving it open would mean nesting multiplies it by the product of
  the dimensions.
- `ROADMAP.md:142` (result handle leaks if materialization throws mid-loop) is **not** closed, but is no
  longer reproduced: every inner level goes through a `finally`-guarded helper. Routing the *outer* loop
  through the same three helpers afterwards makes that item a two-line change; recorded, not done here.
- Files touched: `forward/ForwardCirCollectionComponents.kt` (recursive `componentWireCsharpType`,
  `componentWireExpression`, `componentReadExpression`, `componentNeedsProjection`),
  `forward/ForwardKotlinPlanEmitter.kt` (`componentLowering` + `componentRaising` `Collection` arms,
  `loweredCollectionExpression` split), `forward/ForwardCallablePlanner.kt` (`isWrappableComponent` recursion
  + the `Nullable(Collection)` guard), `cir/CirMarshalRenderer.kt` (`Wrap` ownership + `IntPtr` branch, three
  factories dispose, three new `Read*` helpers), plus the emission gating for the new helpers. **No change to
  `exports/GenericClassExports.kt`, no new native export, no ABI change** (**Inferred** from the design; the
  component slot is already `COpaquePointer?` for every kind).

### Fixture: `test-library/.../clinic/NestedCollectionsSample.kt`

One class, five runtime cells, each crossing a seam none of the others reaches. One slot needing conversion at
the seam and one needing none appear together in cells 3 and 5.

| Cell | Shape | Seam it is the only cell for |
|---|---|---|
| `logGrid(rows: List<List<String>>): String` | input, List-in-List | the restatement; recursive `CreateList`, recursive `componentLowering`, no conversion at any level |
| `grid(): List<List<String>>` | return, List-in-List | recursive `ReadList`; closes the shipped bind-then-throw |
| `chartRuns(runs: Map<String, List<Mood>>): String` | input, outer Map | plain `String` key **beside** a nested value (one slot converting, one not), and an ADR-097 enum leaf *inside* the nesting |
| `tallyGroups(groups: Set<List<String>>): String` | input, outer Set + inner List | cross-kind helper emission: the file needs `NugetSetNative` **and** `NugetListNative`, the gating bug class `ROADMAP.md:141` already records |
| `runsByPatient(): Map<String, List<Mood>>` | return, outer Map | `ReadMap` + nested value + converting leaf; distinct code from cell 2's list read |

Tier 1 (generated-text) cells, no runtime cost, no new fixture crossing:

- `List<List<List<String>>>`: depth 3, proving the recursion is not depth-1-special-cased.
- `List<List<String>?>`: asserted `SKIPPED_UNSUPPORTED_INPUT` (the deliberate `Nullable(Collection)` guard).
- `List<List<String?>>`: nullable leaf under nesting; asserts the block-bodied read lambda.
- `var grid: List<List<String>>`: the property setter that becomes eligible for free.

### Deferred, with reasons

- **Nullable nested collections** (`List<List<String>?>`, `Map<String, List<Int>?>`). Named skip, guarded
  explicitly so it cannot become a silent bind-then-throw. Needs a null arm on the write projection
  (`x == null ? IntPtr.Zero : CreateList(x)`) and a zero-handle arm on the read; additive after this ADR.
- **Nested collections as `Instant`/interface/bound-interface components**: unchanged, still excluded by
  `isWrappableComponent`'s other branches.
- **`MutableList`/`MutableSet`/`MutableMap` write-back through a nesting level**: unchanged, inherits
  ADR-073's no-write-back decision at every depth.
- **Routing the outer materialization loop through the new `Read*` helpers** (which would close
  `ROADMAP.md:142`): mechanical, out of scope, and better done as that item so its own regression test lands
  with it.

### Spike requests

1. **Ownership: does disposing an element's `StableRef` immediately after `nuget_list_add` keep the object
   alive?** This is the single claim the whole design rests on. **Verified by execution** (spike run against
   the built `libtest.dylib` from a scratch C# console app, after this ADR was drafted): the answer is **yes,
   safe**. Disposing each element box immediately after `nuget_list_add` leaves the list intact. All three
   elements read back correctly through `nuget_list_get` / `nuget_unwrap_string`, they still read back
   correctly after 200,000 transient `nuget_wrap_string` boxes were minted and disposed to churn the Kotlin
   heap, and `auditor_audit` then received a well-formed Kotlin `List` across the ABI. A control run that kept
   the element boxes alive behaved identically, so the eager dispose changes nothing observable.

   This confirms what was previously only **Verified by source reading**: `nuget_list_add` stores the
   *dereferenced object* into a `MutableList` that the outer `StableRef` already roots, so disposing the
   element box releases a root, not the object. The per-element `finally` in the design stands, and
   alternative 3 (accumulate and dispose after the whole native call returns) is **not** needed.
2. **Nested `it` shadowing and star-projection casts in the generated Kotlin.** Still open, needs a Kotlin/Native
   compile and therefore the Gradle lock. Compile
   `rows.asStableRef<MutableList<Any?>>().get().map { e0 -> (e0 as MutableList<*>).map { e1 -> e1 as String } }`
   in a scratch Kotlin/Native module. Outcome that changes the design: an `UNCHECKED_CAST` warning escalated to
   an error, or a star-projection rejection, would force `MutableList<Any?>` plus a `@Suppress`.

# ADR-122: Handle parameters on the legacy Flow and suspend routes

## Status

Accepted

## Context

GitHub issue #126, found by consuming `main` from a real project:

```kotlin
sealed class Node

data class Remote(val host: String) : Node()
data class Nearby(val id: String) : Node()

class Hub {
  fun open(node: Remote): StateFlow<Node> = MutableStateFlow(node)
  fun open(node: Nearby): StateFlow<Node> = MutableStateFlow(node)
}
```

```csharp
// the entry points are correctly distinct (issue #97 / ADR-090 fixed that half)
[DllImport("demo", EntryPoint = "hub_open_collect")]
private static extern IntPtr Native_OpenCollect(IntPtr handle, IntPtr scopeHandle, IntPtr node, ...);
[DllImport("demo", EntryPoint = "hub_open_2_collect")]
private static extern IntPtr Native_Open_2Collect(IntPtr handle, IntPtr scopeHandle, IntPtr node, ...);

// the public signatures are not
public KotlinStateFlow<global::Demo.Node> Open(IntPtr node) { ... }
public KotlinStateFlow<global::Demo.Node> Open(IntPtr node) { ... }
```

```
Interop.cs(1309,94): error CS0111: Type 'Hub' already defines a member called 'Open' with the same parameter types
```

`packNuget` is green with this in it. The failure only shows when a consumer compiles the generated
`Interop.cs`, which is the same shape of miss as [ADR-119](119-collection-returns-on-the-legacy-suspend-route.md):
the package publishes and every consumer's build breaks.

**The collision is the symptom; `IntPtr` is the defect.** A single non-overloaded
`Open(IntPtr node)` is equally broken: the sealed arms only have `internal` handle constructors, so
a consumer has no legitimate way to produce that pointer. Making the signatures merely *distinct*
would ship four uncallable methods.

### The mechanical cause, per half

The defect is neither sealed-specific nor overload-specific. The legacy routes never ask the
classifier about a *non-generic* parameter at all, on either half.

- **Classification. Verified in source.** `forward/ForwardLegacyRouteCollections.kt:53-54`:
  `legacyParameterShape` returns `Plain` for any type with no type arguments, before any
  classification runs. A sealed subclass, a sealed base, an ordinary class, an `object`, an enum, an
  `Instant`/`Duration`/`Uuid`, a value class and an interface are all `Plain`.
- **C# half. Verified in source.** `cir/CirCollectionParameters.kt:70-88`, `legacyRouteParameters`:
  anything that is not `ForwardLegacyParameterShape.Marshalled` falls to
  `CirParameter(name, mapParamType(resolved.declaration.simpleName.asString()))`, and `mapParamType`
  returns `"IntPtr"` for everything outside its 13-entry primitive/`String` table
  (`cir/CirTypeMapping.kt:163-164`). Both the public type and the `DllImport` type come from that one
  call, so the public signature is `Open(IntPtr node)`.
- **Kotlin half is separately wrong. Verified in source and in generated output.** The `@CName`
  export declares the parameter with its *real Kotlin type*: `exports/ClassExports.kt:318-331`
  (`addFlowParameters`, shared by `_collect`, `_value`, `_has_value` and `_set_value`),
  `exports/SuspendFunctionExports.kt:136-146` (suspend class method, sealed arms included per
  ADR-118) and `:288-299` (top-level suspend, via `toBridgeTypeName`). So Kotlin says
  `observation: Observation.Alive` while C# says `IntPtr observation`, in the same generated pair.
- **The four C# call sites funnel through one function**, so one fix covers them:
  `cir/CirClassTranslator.kt` (flow methods, and the two suspend projections) and
  `cir/CirFunctionTranslator.kt` (top-level suspend) all build their parameters with
  `legacyRouteParameters`. **Verified in source.**
- **No fixture exercised this.** Before this change every parameter on every legacy-route fixture in
  `test-library/src/nativeMain` was `String`, `Int`, `List<String>` or `Set<String>`. That is why the
  gap shipped. **Verified.**

### What the ordinary route does (the mechanism being ported)

**Verified in source.** The ADR-062 plan route already binds exactly these types at a parameter
position, and a plain (non-flow, non-suspend) method with a sealed-arm parameter is correct today:

1. The planner rewrites a sealed base parameter with `sealedAsHandle()`
   (`forward/ForwardCallablePlanner.kt:1470`, `:1736`, definition at `:3119-3131`), which turns the
   base's `SpecializedProtocol` into its `ObjectHandle(viaDiscriminator = true)`. A sealed *subclass*
   needs none of this: it is already an ordinary `ObjectHandle`.
2. The public C# type is `BridgeType.ObjectHandle.csharpType` through `forwardPublicCsharpType()`
   (`forward/ForwardCsharpTypes.kt`), which the classifier has already qualified: nested arms render
   `global::Ns.Observation.Alive`, sibling arms `global::Ns.Label`
   (`forward/ForwardBridgeTypeClassifier.kt`, `cir/CirTypeMapping.kt`'s `nestedCsName`).
3. The native argument is `"${parameter.csharpName}._handle"`
   (`forward/ForwardCirPlanProjection.kt:568`). `_handle` is `internal` and declared both on the
   sealed base (`cir/CirSealedRenderer.kt:18`) and on every wrapper class
   (`cir/CirClassRenderer.kt:73`), so a sibling generated class can read it and an arm inherits it.
4. The Kotlin export slot is `COpaquePointer`, dereferenced with
   `x.asStableRef<Qualified>().get()` (`forward/ForwardKotlinPlanEmitter.kt:880-882`).

So the registry this fix needs is already emitted and already used by the *return* position of the
very same members. Only the legacy parameter position falls back.

[ADR-114](114-collection-parameters-on-legacy-flow-and-suspend-routes.md) is the structural
precedent (classify, marshal what you can, refuse the rest by name) and ADR-119 is its return-side
twin. This ADR is the third application of the same shape, to a handle at a parameter position.

## Alternatives Considered

### 1. A `Handle` shape on `ForwardLegacyParameterShape`, and refuse every other non-scalar (chosen)

Classify a non-generic legacy-route parameter after all. If it classifies (post-`sealedAsHandle()`)
to `BridgeType.ObjectHandle`, it is `Handle`: public C# type `forwardPublicCsharpType()`, native
slot `IntPtr`, argument `x._handle`, Kotlin slot `COpaquePointer` dereferenced eagerly. Every
remaining non-scalar, non-generic shape becomes `Refused` and skips the member named, instead of
silently rendering a public `IntPtr`.

Pros:

- One contract for a Kotlin object parameter across the whole generator: the same `_handle` the
  ordinary route writes, read back by the same `asStableRef<T>()` the ordinary route reads.
- The discriminator asymmetry falls out for free. C# only ever *writes* `x._handle`, and
  `viaDiscriminator` matters only for reconstruction (`forward/ForwardCirPlanProjection.kt`), so a
  sealed *base* parameter needs no extra machinery over an arm. Kotlin's `asStableRef<Observation>()`
  is correct for a base, and Kotlin is the side that discriminates.
- The refusal arm makes the issue's "no emitted public member has `IntPtr` in its signature"
  invariant true for arbitrary input rather than for this repository's fixtures. Enum,
  `Instant`/`Duration`/`Uuid`, value class, interface and *unexported* class parameters are all the
  same defect, and today all of them render a public `IntPtr` with no diagnostic at all (the
  ROADMAP "silently vanish" family, at a parameter position).

Cons:

- A member with, say, an enum parameter on a flow route disappears from the surface where it
  previously appeared as an uncallable `IntPtr`. That is the issue's own requirement: a
  present-and-broken member is worse than an absent-and-named one, because it ships.

### 2. Spell the parameter with its real Kotlin type on both halves

Make C# agree with what Kotlin already emits.

Rejected, and named explicitly because it is the fix a future implementer reaches for first. It is
ADR-114's "The trap" verbatim: a `@CName` export takes a Kotlin object as a `kref_<T>` struct
wrapping a *pinned* pointer, not as a `void*` (spike-verified there for `List`/`Set`; **inferred**
for a user class, which nobody has spiked). A single-pointer struct and a pointer share a register
under AArch64 and SysV, so the crossing would not fault on arity: it would hand whatever the C#
caller passed straight to the Kotlin runtime as a live object reference. That is a memory-safety
hazard, not a type error, and C# still could not produce the value.

### 3. Number or rename the C# overloads

`Open`, `Open2`, `Open3`, parameters left as `IntPtr`.

Rejected by the issue in as many words: it turns a compile error into four uncallable methods, which
is strictly worse because it ships.

### 4. Accept `IntPtr` and make the handle constructors public

Rejected by the issue. The handle constructors are `internal` deliberately (ADR-034/ADR-066), and a
public one would put a raw pointer into the supported surface of every wrapper.

### 5. Skip the members with a `SKIPPED_*` diagnostic as the *primary* fix

Rejected by the issue. Skipping is the right fallback for a shape that cannot be expressed; this one
is expressible, and both the mapped type and the marshalling helper already exist for the return
position on the same member. Retained as the arm for everything alternative 1 does not bind.

### 6. Also bind the enum arm (deferred)

An enum parameter is easy on paper (`(int)x` in C#, `Q.entries[x]` in Kotlin), but it widens the ABI
change to a second type family with no issue behind it, and the legacy routes have no per-parameter
projection plumbing beyond a native-argument expression. Deferred; alternative 1's refusal arm names
it loudly in the meantime, which is a strictly better state than today's silent `IntPtr`.

### 7. Move the legacy routes onto the ADR-062 callable plan

ADR-062's stated direction, rejected here on the same terms ADR-118 and ADR-119 rejected it: the
plan has no async result shape, no scope receiver and no completion callback, and none of that is
needed to close the issue.

## Decision

Alternative 1.

### Consumer-facing C# API

```csharp
public class ObservationRadio : IDisposable, IAsyncDisposable, INugetHandle
{
    public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Observation.Alive observation);
    public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Observation.Dead observation);
    public KotlinStateFlow<string> Watch(global::TestLibrary.Issue54.Label flat);
    public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Observation observation);
    public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Cat cat);
    public Task<string> LogAsync(global::TestLibrary.Cat.Observation.Alive observation,
        CancellationToken cancellationToken = default);
    public KotlinStateFlow<int> Tally(IReadOnlyList<string> kinds,
        global::TestLibrary.Cat.Observation.Alive observation);
}
```

Five distinct signatures where there was one, and every argument is a value the consumer already
holds from the bridge.

### Classification (`forward/ForwardLegacyRouteCollections.kt`)

`ForwardLegacyParameterShape` gains a third arm beside `Marshalled` and `Refused`:

```kotlin
data class Handle(val type: BridgeType.ObjectHandle) : ForwardLegacyParameterShape
```

`legacyParameterShape` classifies a non-generic parameter instead of returning `Plain` unread:

- a `BridgeType.Primitive`, `Char`, `String` or `Unit` stays `Plain`, the shipped spelling, so every
  scalar parameter renders byte for byte as before;
- anything that classifies (after `sealedAsHandle()`) to `BridgeType.ObjectHandle` is `Handle`. That
  covers a sealed arm, a sealed base, an ordinary exported class and an `object`;
- everything else non-generic is `Refused`, quoting the author's own spelling through the same
  `legacyDescription()` ADR-114 uses. The generic path above it is untouched.

`Plain` therefore narrows from "no type arguments" to "a scalar", which is the whole of alternative
1's second half.

### Kotlin half (`exports/ClassExports.kt`, `exports/SuspendFunctionExports.kt`)

A `Handle` parameter declares its ABI slot as `COpaquePointer`, exactly like a `Marshalled` one, in
all three parameter builders (`addFlowParameters`, the suspend class-method loop, and
`addLegacySuspendParameters` for top level).

The dereference is a **prelude local**, emitted before `scope.launch`, beside ADR-114's eager
collection copy:

```kotlin
val observationArg = observation.asStableRef<com.example.Observation.Alive>().get()
```

and the member is called with `observationArg`. The prelude placement is load-bearing, for ADR-114's
own reason turned around: the coroutine captures a strong Kotlin reference, so a C# consumer
disposing its wrapper mid-flow cannot invalidate what the coroutine is still reading. Inlining
`observation.asStableRef<Q>().get()` at the call site would evaluate it inside `launch`, that is,
after the export returned and after the consumer may have disposed. That is the exact lifetime
hazard ADR-114 designed out, arriving from the other direction.

The lowered-name helper (`legacyLoweredName`) is reused, so one member carrying both a collection
and a handle parameter gets both preludes in declaration order and one call site reading both
locals.

### C# half (`cir/CirCollectionParameters.kt`, `cir/CirModel.kt`)

`legacyRouteParameters` gains a `Handle` arm:

```kotlin
CirParameter(
  name,
  type = shape.type.forwardPublicCsharpType(),
  nativeType = "IntPtr",
  nativeArgumentExpression = "$name._handle",
)
```

`CirParameter` gains one field, `nativeArgumentExpression: String?`, which `nativeArgument` prefers
over the parameter name. `collectionCreate` is deliberately **not** reused for this: it is what
triggers the create-then-`finally`-dispose block, and a borrowed handle must never get one. The
wrapper owns that handle; the call site only reads it. `hasCollectionHandles()` and
`collectionScopedCall()` keep filtering on `collectionCreate`, so a member with only handle
parameters keeps its exact previous single-expression rendering.

Every rendering site already goes through `CirParameter.nativeArgument`
(`cir/CirFlowRenderer.kt`, `cir/CirConcurrencyRenderer.kt`, `cir/CirClassTranslator.kt`), so the
change reaches `_collect`, `_value`, `_has_value`, `_set_value` and `_async` without touching a
renderer.

### Refusal arm and diagnostics (`NugetProcessor.kt`)

A refused parameter already skips the member on both halves and is named once by
`warnRefusedLegacyRouteMembers` (ADR-114's walk, extended by ADR-119). The reason text widens from
"the generic type of" to cover a non-generic refusal, and keeps naming the parameter:

```
w: [nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping ObservationRadio.watch: a Flow-returning or suspend
member can take a collection or a handle parameter, but not mood: Mood. Pass a class, object or
sealed type, a List/Set/Map, or a primitive/String.
```

That is one skip per member, whichever gate it hits first, unchanged from ADR-119.

### The ABI slot, before and after

**Verified** by reading the generated `CNameExports.kt` for the `test-library` fixture across the
change. Before:

```kotlin
@CName("observationradio_watch_collect")
public fun export_observationradio_watch_collect(
  handle: COpaquePointer,
  scopeHandle: COpaquePointer,
  observation: Observation.Alive,
```

After:

```kotlin
@CName("observationradio_watch_collect")
public fun export_observationradio_watch_collect(
  handle: COpaquePointer,
  scopeHandle: COpaquePointer,
  observation: COpaquePointer,
```

So the slot moves from a Kotlin `kref_<T>` struct (what `@CName` compiles an object parameter into,
per ADR-114's `probe_api.h` spike) to a plain `void*`, and the C# `IntPtr` on the other side becomes
true rather than accidentally register-compatible.

The ADR-055 contract check needs no re-baseline: `ForwardAbiContract.kotlinType` maps every Kotlin
type outside its scalar table to `ForwardAbiType.POINTER`
(`ForwardAbiContract.kt:430-444`), and `csharpType` maps `IntPtr` to the same, so both the old and
the new spelling compare equal. **Verified in source.** The check was blind to this defect, which is
worth recording: it compares *widths*, not marshalling correctness, and a `kref` struct that fits in
a register is indistinguishable from a pointer at that resolution.

### Scope

Bound:

- A parameter typed as an ordinary exported class, an `object`, a sealed base, or a sealed subclass
  (class or eligible ADR-112 interface arm, nested or sibling), on:
  - a `Flow`/`StateFlow`-returning member (`_collect`, `_value`, `_has_value`, `_set_value`),
  - a `suspend` member of a class or a sealed arm (`_async`),
  - a top-level `suspend` function (`_async`).
- Mixed with a collection parameter on the same member, in either order.

Refused, named `SKIPPED_UNSUPPORTED_INPUT`:

- An enum parameter (alternative 6, deferred).
- `Instant` / `Duration` / `Uuid`, a value class, an interface, a `Throwable`, a function type.
- An *unexported* class (one outside the export scope), which previously rendered a public `IntPtr`
  with no diagnostic at all.
- A nullable object parameter (`Observation?`), keeping ADR-114's nullable deferral rule: nullable
  threading on the legacy routes is done once, or not at all.
- Every generic parameter that is not a supported collection, unchanged from ADR-114.

Not touched:

- The non-generic object *return* on the suspend route still spells a nested sealed arm by simple
  name (ROADMAP Phase 3). Different position, same file.
- Lambda, stored-callback and interface-bridge parameter routes, which have their own export
  builders and never reach `legacyRouteParameters`.

## Consequences

- Five overloads that could not compile in a consumer's build become five distinct, callable
  signatures, and the ordinary and legacy routes finally spell the same Kotlin type the same way in
  the same file. The consumer-side proof is
  `IntegrationTests/LegacyRouteHandleParameterTests.cs`, whose every argument is obtained from the
  bridge and never spelled as a pointer (the issue's requirement 4 as an executable assertion), plus
  `GeneratedBindingsCheck` compiling `Interop.cs` under `TreatWarningsAsErrors`.
- The crossing mints no handle on either side. C# passes a `_handle` its wrapper already owns and
  Kotlin takes no `StableRef` of its own, so the ADR-120 harness row
  (`HandleParameter_StateFlowValueRead_ReturnsToBaseline`) returns to baseline. That row is not
  ceremony: a fix that took ownership to keep the object alive across the flow would show up there
  as a per-crossing leak and nowhere else.
- Breaking for nobody who was compiling. Every member this ADR refuses, and every member it binds,
  previously rendered a public `IntPtr` that no consumer could call. The ABI slot for a bound
  parameter changes from `kref_<T>` to `void*`; nothing in this repository was passing one (no
  fixture existed), and a downstream consumer could only have been reaching it through unsafe code
  against an uncallable signature.
- `Interop.cs` gains the issue's structural invariant as a test rather than a claim:
  `Tier1StructuralInteropCsTest` fails on any emitted `public` member with `IntPtr` in its signature,
  outside a small allow-list of the runtime support surface. That guard is what makes the next
  instance of this defect a red test instead of a consumer's bug report.

### Inferred claims

Everything else is verified by source reading (file and line inline) or by reading generated output
before and after.

1. **A user class crosses a `@CName` boundary as a `kref_<T>` struct**, not as a `void*`. ADR-114
   spiked this for `List`/`Set` and read it out of `probe_api.h`; nobody has run the same spike for
   a user class. Load-bearing only for alternative 2's rejection, which is over-determined anyway
   (the C# caller cannot produce the value either way).
2. **No route reaches `legacyRouteParameters` other than the four named call sites.** Verified by
   reading the call sites that exist today; a fifth added later would inherit the fix silently,
   which is the intended direction, but a fifth that builds its parameters by hand would not.

# ADR-065: StateFlow<T> mapping — hot always-current-value streams → KotlinStateFlow<T> (KotlinFlow<T> + synchronous `.Value`)

## Status

Accepted

## Context

Kotlin `StateFlow<T>` is the standard hot, conflated, always-has-a-current-value stream. It is the
state-holder half of `kotlinx.coroutines.flow`: a `StateFlow<T>` always has a `value: T` readable
synchronously, replays exactly that current value to every new collector, and conflates
intermediate updates (a slow collector sees the latest, not every, value). Its type hierarchy is
`MutableStateFlow<T> : StateFlow<T> : SharedFlow<T> : Flow<T>` — a `StateFlow` **is-a** `Flow`.

ADR-026 mapped cold `Flow<T>` to `KotlinFlow<T> : IAsyncEnumerable<T>` and **explicitly deferred**
`StateFlow<T>` (see ADR-026 "Deferred" and its commented-out `FLOW_TYPES` entry). This ADR extends
that substrate. Everything ADR-026 built is reused verbatim: the `*_collect` export ABI, the
three-callback triple (`onNext`/`onComplete`/`onError`), `KotlinFlow<T>` / `KotlinFlowEnumerator<T>`,
the unbounded-`Channel<T>` push→pull bridge, `Job`-`StableRef` cancellation (ADR-022), and
`KotlinException` error propagation (ADR-023). The **one thing StateFlow adds** over Flow is the
synchronous, always-present `.value` — and that is the whole design question here.

This ADR covers `StateFlow<T>` (and, as a read-only view, `MutableStateFlow<T>`). `SharedFlow<T>`
(ROADMAP line 108) has **configurable** replay/buffering and is a distinct, separately-deferred
mapping — it is not folded in here.

### What Kotlin/Native produces for StateFlow

`StateFlow<out T>` is a generic interface, exactly as constrained as `Flow<T>` in ADR-026:

```kotlin
interface StateFlow<out T> : SharedFlow<T> {
    val value: T
}
```

It **cannot be exported with `@CName` directly** for the same two reasons as `Flow<T>` (ADR-026):
it is generic, and its inherited `collect` is a `suspend fun` taking a generic `FlowCollector<T>`.
The bridge must (a) wrap `collect` in a coroutine exposed through the ADR-026 callback triple, and
(b) additionally expose the synchronous `value` read as its own C-ABI export. Neither the generic
interface nor the `suspend` collect can cross the C boundary by value; the current-value read,
however, is an ordinary non-suspend property read of `T` and needs only a plain getter export.

### How other Kotlin interop targets handle StateFlow

#### SKIE (Touchlab — third-party Swift enhancement)

SKIE is again the closest reference implementation. It maps:

- `StateFlow<T>` → `SkieSwiftStateFlow<T>`
- `MutableStateFlow<T>` → `SkieSwiftMutableStateFlow<T>`
- (`SkieSwiftOptionalStateFlow<T>` for the nullable-element case)

**Verified from SKIE docs** (`skie.touchlab.co/features/flows`): these are "custom Swift classes
that implement `AsyncSequence`", and the type argument `T` of `StateFlow<T>` is **preserved** (a
`StateFlow<List<String>>` surfaces in Swift as an `AsyncSequence` with element `[String]`, not
erased). So SKIE surfaces a StateFlow as **both** an async iteration source **and** a distinct
type from a plain Flow.

**Inferred (not verified against SKIE source in this session):** that `SkieSwiftStateFlow<T>` also
exposes a **synchronous `value` property** (and `SkieSwiftMutableStateFlow<T>` a settable `value`),
mirroring Kotlin's `StateFlow.value`. This matches SKIE's stated goal of preserving StateFlow's
"always-current-value" nature and is the widely-understood SKIE behaviour, but the live docs page
retrieved for this ADR confirmed only the `AsyncSequence` conformance and type-argument
preservation, not the `.value` accessor by name. Treat the "SKIE exposes `.value`" claim as
inferred. The design below does **not** depend on SKIE's exact surface — it is justified
independently by Kotlin's own `StateFlow.value` and by idiomatic C# — but SKIE is the precedent for
"one type that is both an async sequence and a synchronous current-value holder."

#### ObjC / Swift Export (official, built-in)

As with `Flow<T>` (ADR-026), the built-in Kotlin/Native ObjC export does **not** treat `StateFlow`
specially: it appears as an opaque `id` (the Kotlin object) with no async or `.value` bridging.
Official Swift Export is Alpha and does not yet document StateFlow mapping. No usable precedent
beyond "it is not handled for you." *Inferred from the ADR-026 survey; unchanged for StateFlow.*

#### Kotlin/JS

`@JsExport` does not support `StateFlow<T>` directly (same as `Flow<T>`). Developers hand-wrap. No
standard treatment. *Inferred, per ADR-026.*

#### Java (JVM)

No built-in Java-facing StateFlow adapter. `StateFlow.getValue()` is callable from Java as an
ordinary getter (it is a non-suspend property), and `collect` requires a coroutine — so Java sees a
synchronous `getValue()` plus a suspend `collect` it cannot call idiomatically. This is a useful
data point: even on the JVM, the **value read is an ordinary synchronous getter** while collection
is the hard part. *Inferred (well-known kotlinx.coroutines Java-interop behaviour).*

### What's idiomatic in C#

There is no single built-in .NET type that is *both* an async stream *and* a synchronous
current-value holder, so the idiom is a composition:

- **Async updates** → `IAsyncEnumerable<T>` with `await foreach` (exactly ADR-026's choice; the
  standard C# 8.0+ async pull stream).
- **Synchronous current value** → a plain `T Value { get; }` property. C# developers expect a
  "current value that always exists" to be a synchronous property (cf. Rx.NET `BehaviorSubject<T>.Value`,
  `IObservable` + `.Value`), not something you must `await`.

.NET's own always-current-value push type is Rx.NET's `BehaviorSubject<T>`, which exposes both
`Subscribe(...)` **and** a synchronous `.Value`. That is the precise .NET analogue of StateFlow, and
it confirms the shape: **one object exposing both a current value and a subscription surface.** The
ROADMAP's own hint ("property + change notification") points the same way. We choose
`IAsyncEnumerable<T>` over `IObservable<T>` for the subscription half for all the reasons ADR-026
already settled (zero-dependency, `await foreach`, no Rx.NET requirement, SKIE precedent), and we
add the synchronous `.Value` on top.

## Alternatives Considered

### 1. `KotlinStateFlow<T> : KotlinFlow<T>` with synchronous `T Value { get; }` (chosen)

A generated `KotlinStateFlow<T>` that **inherits** `KotlinFlow<T>` (so it is-a `IAsyncEnumerable<T>`
and reuses the entire ADR-026 collect/enumerator/cancellation/error machinery unchanged) and adds a
synchronous `T Value { get; }` backed by a dedicated per-property value-getter export.

```csharp
// current value — synchronous, always present
int now = vm.Count.Value;

// updates — hot; emits the current value immediately on subscribe, then each change; never completes
await foreach (var n in vm.Count.WithCancellation(cts.Token))
    Console.WriteLine(n);
```

**Pros:**
- Mirrors Kotlin's own `StateFlow : Flow` subtyping: a `KotlinStateFlow<T>` is assignable wherever a
  `KotlinFlow<T>` / `IAsyncEnumerable<T>` is expected, exactly as a Kotlin `StateFlow<T>` upcasts to
  `Flow<T>`. Idiomatic and honest.
- Mirrors SKIE's `SkieSwiftStateFlow<T>` (both `.value`-style access and async iteration) and
  Rx.NET's `BehaviorSubject<T>` (`.Value` + subscription).
- Reuses `KotlinFlow<T>` / `KotlinFlowEnumerator<T>` **verbatim** — the collect subscription, the
  unbounded channel, cancellation, and error propagation need **zero change**. The only new C# is a
  ~6-line subclass and a value-getter delegate.
- The `.Value` read reuses `NugetMarshal.FromHandle<T>` (verified-in-repo, `CirMarshalRenderer.kt:104`),
  the *same* generic unwrap that `KotlinFlowEnumerator`'s `onNext` already uses per emitted item.
  Primitives/strings are unwrapped and the transport box disposed; objects become a fresh wrapper
  (`new T(handle)`) — ADR-005 "new wrapper per access" semantics, identical to an object property.

**Cons:**
- One extra native export per StateFlow member (the `_value` getter), in addition to the `_collect`
  export Flow already generates.
- `.Value` boxes the current value into a `StableRef` on the Kotlin side and disposes it on the C#
  side per read (see mechanism). For a primitive this is a small alloc+dispose per `.Value` access —
  identical to the cost every Flow item already pays through `onNext`.

### 2. Plain `T Value { get; }` property + `INotifyPropertyChanged`

Map `StateFlow<T>` to a synchronous property plus `INotifyPropertyChanged` (raise `PropertyChanged`
on each update), matching the ROADMAP's "property + change notification" phrasing literally and
WPF/MAUI data-binding expectations.

**Pros:**
- Directly data-bindable in XAML UI frameworks with no adapter.
- "Property + change notification" is a familiar C# pattern.

**Cons:**
- `INotifyPropertyChanged` requires an **always-running background collector** to raise
  `PropertyChanged`, with UI-thread/`SynchronizationContext` affinity concerns — a heavier,
  stateful, lifetime-bound subscription that must be started and stopped somewhere. It is a
  UI-framework adapter, not a neutral language mapping.
- Loses `await foreach` / `IAsyncEnumerable<T>` composability that ADR-026 established for the whole
  Flow family; a StateFlow would become the *only* stream type that is not an async enumerable,
  breaking uniformity.
- INPC + `.Value` can be layered **on top** of option 1 by a consumer (or a future opt-in adapter)
  without the core mapping paying its cost. Rejected as the v1 core; noted as a deferred convenience.

### 3. Reuse bare `KotlinFlow<T>` and drop `.value`

Add `StateFlow` to `FLOW_TYPES` and map it to `KotlinFlow<T>` like any Flow.

**Pros:** zero new code — StateFlow "just works" as a hot Flow that never completes.

**Cons:** **loses the defining feature of StateFlow** — the synchronous always-present current
value. A consumer could only get the current value by `await foreach`-ing and taking the first
emission, which is absurd for a state holder. Contradicts GOALS #2/#3 (idiomatic, comprehensive).
Rejected — this is exactly the trap the task warns about (StateFlow silently degrading to Flow).

### 4. `IObservable<T>` (BehaviorSubject-like)

Map to a push `IObservable<T>` with a synchronous `.Value` (a literal `BehaviorSubject<T>` shape).

**Pros:** `BehaviorSubject<T>` is the exact .NET semantic analogue.

**Cons:** all of ADR-026 Alternative 3's cons — `IObservable<T>` is near-useless without Rx.NET, is
not `await`-composable, and diverges from the `IAsyncEnumerable<T>` choice made for the rest of the
Flow family. Rejected for consistency with ADR-026; its one good idea (synchronous `.Value`) is
adopted in option 1.

## Decision

Use **option 1**: generate `KotlinStateFlow<T> : KotlinFlow<T>` with a synchronous `T Value { get; }`.
A `StateFlow<T>` (or `MutableStateFlow<T>`, as a read-only view — see Scope) property or non-suspend
function return maps to `KotlinStateFlow<T>`. Two native exports are generated per member: the
existing ADR-026 `_collect` export (unchanged), and a new synchronous `_value` getter.

### Kotlin export signatures

For a StateFlow **property** `val count: StateFlow<Int>` on class `CounterViewModel`:

```kotlin
// (A) collect subscription — IDENTICAL to ADR-026's Flow property export; unchanged.
@CName("counterviewmodel_get_count_collect")
fun export_counterviewmodel_get_count_collect(
    handle: COpaquePointer,
    scopeHandle: COpaquePointer,
    onNextPtr: COpaquePointer,      // (itemPtr, isCancelled: Byte, userData) -> Unit
    onCompletePtr: COpaquePointer,  // (userData) -> Unit   — NEVER fired for a StateFlow (hot, open)
    onErrorPtr: COpaquePointer,     // (errorPtr, userData) -> Unit
    userData: COpaquePointer,
): COpaquePointer {                 // Job StableRef, for cancellation via nuget_job_cancel
    val obj = handle.asStableRef<CounterViewModel>().get()
    val scope = scopeHandle.asStableRef<CoroutineScope>().get()
    // ... reinterpret callbacks ...
    val job = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            obj.count.collect { value ->                       // StateFlow.collect: emits current
                val itemRef = StableRef.create(value as Any).asCPointer()  // value immediately, then
                onNext.invoke(itemRef, 0.toByte(), userData)   // each update; conflates; never returns
            }
            onComplete.invoke(userData)                        // unreachable for StateFlow (documented)
        } catch (e: CancellationException) {
            onNext.invoke(null, 1.toByte(), userData); throw e
        } catch (e: Throwable) {
            onError.invoke(StableRef.create(buildError(e)).asCPointer(), userData)
        }
    }
    return StableRef.create(job).asCPointer()
}

// (B) NEW — synchronous current-value read. No scope, no coroutine, one crossing.
@CName("counterviewmodel_get_count_value")
fun export_counterviewmodel_get_count_value(handle: COpaquePointer): COpaquePointer {
    val obj = handle.asStableRef<CounterViewModel>().get()
    return StableRef.create(obj.count.value as Any).asCPointer()   // box, exactly like one onNext item
}
```

The `_value` export is the **only new export shape**. It boxes `stateFlow.value as Any` into a
`StableRef` and returns the pointer — structurally identical to a *single* `onNext` emission in the
collect body. It carries **no `errorOut`** in v1: `StateFlow.value` is a pure, non-suspending read
of a conflated atomic reference and does not throw by the `StateFlow` contract (see Consequences for
the deliberate divergence from ADR-030's wrap-all).

For a StateFlow **function return** `fun counter(): StateFlow<Int>`, the `_value` export takes the
method's parameters and calls `obj.counter(args).value` (mirrors ADR-026's flow-method form), and
the `_collect` export is the ADR-026 flow-method export unchanged.

### Generated C# helper — `KotlinStateFlow<T>`

Generated once (like `KotlinFlow<T>`), in the shared namespace, immediately after the
`KotlinFlow<T>` definition it extends:

```csharp
public class KotlinStateFlow<T> : KotlinFlow<T>
{
    private readonly Func<IntPtr> _readValue;

    internal KotlinStateFlow(NugetFlowCollectDelegate startCollect, Func<IntPtr> readValue)
        : base(startCollect)
    {
        _readValue = readValue;
    }

    // Synchronous, always-present current value. Reuses the same generic unwrap
    // KotlinFlowEnumerator.onNext uses per item (primitives/strings unwrapped + box disposed;
    // objects become a fresh wrapper — ADR-005 new-wrapper-per-access).
    public T Value => NugetMarshal.FromHandle<T>(_readValue());
}
```

`KotlinFlow<T>`'s constructor is `internal` and the class is a non-sealed `class`, so this subclass
compiles in the same generated `Interop.cs`. *Verified-in-repo:* `CirFlowRenderer.kt:15` declares
`public class KotlinFlow<T> : IAsyncEnumerable<T>` with an `internal KotlinFlow(...)` constructor,
and `NugetMarshal.FromHandle<T>` disposes the transport box for primitives/strings and hands the
handle to `new T(handle)` for objects (`CirMarshalRenderer.kt:104-183`).

### Generated C# property getter

```csharp
public KotlinStateFlow<int> Count
{
    get
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(CounterViewModel));
        return new KotlinStateFlow<int>(
            (onNext, onComplete, onError, userData) =>
                Native_GetCountCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),
            () => Native_GetCountValue(_handle));
    }
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "counterviewmodel_get_count_collect")]
private static extern IntPtr Native_GetCountCollect(
    IntPtr handle, IntPtr scopeHandle,
    IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "counterviewmodel_get_count_value")]
private static extern IntPtr Native_GetCountValue(IntPtr handle);
```

### Consumer API (the failing-test contract for Step 3)

```csharp
using var vm = new CounterViewModel();     // starts at 0, then ticks 1, 2, 3 ...

// 1. Synchronous current value — always exists, no await.
int current = vm.Count.Value;
Assert.Equal(0, current);

// 2. Await updates. StateFlow replays the CURRENT value immediately to a new collector,
//    then delivers subsequent (conflated) values. It is HOT and NEVER completes on its own —
//    the loop runs until cancelled or broken.
var seen = new List<int>();
var cts = new CancellationTokenSource();
await foreach (var n in vm.Count.WithCancellation(cts.Token))
{
    seen.Add(n);
    if (seen.Count >= 3) cts.Cancel();     // must cancel/break — a StateFlow foreach never ends by itself
}
Assert.Equal(0, seen[0]);                  // first emission == current value at subscription (replay-1)

// 3. It IS-A KotlinFlow<T> / IAsyncEnumerable<T> — matches Kotlin's StateFlow : Flow upcast.
IAsyncEnumerable<int> asFlow = vm.Count;
KotlinFlow<int> asKotlinFlow = vm.Count;
Assert.NotNull(asFlow);

// 4. Object element type — .Value returns a fresh wrapper (ADR-005 semantics).
using var cat = vm.CurrentCat.Value;       // KotlinStateFlow<Cat>.Value -> new Cat(handle)

// 5. After Dispose, the getter throws (parity with Flow, ADR-026).
vm.Dispose();
Assert.Throws<ObjectDisposedException>(() => { var _ = vm.Count; });
```

### Replay / conflation (question 3)

**No change to `KotlinFlowEnumerator<T>` is needed.** StateFlow's defining runtime behaviours are
produced entirely on the **Kotlin** side by `StateFlow.collect`:

- **Replay-1 / current-value-on-subscribe:** `StateFlow.collect` emits the current `value` to a new
  collector immediately. Our collect lambda forwards it as the first `onNext`, so the first item a
  C# `await foreach` sees is the current value. Falls out for free.
- **Conflation:** `StateFlow.collect` only delivers the latest value when the collector is slow.
  Intermediate values are conflated at the source, before they reach our `onNext`. The C# unbounded
  `Channel<T>` does **not** add conflation, but it does not need to — the source already conflated.
  A C# consumer therefore sees StateFlow's own conflated contract (always eventually the latest,
  possibly skipping intermediates), which is correct.
- **Never completes:** `StateFlow.collect` never returns normally, so `onComplete` is never fired
  and `MoveNextAsync()` never returns `false` on its own. The existing enumerator handles a
  never-completing stream correctly (it just keeps awaiting the channel until cancelled/disposed).
  This is a genuine behavioural difference from cold Flow and is documented for consumers (the
  `await foreach` must be bounded by cancellation/`break`).

`SharedFlow<T>` (configurable `replay`/`extraBufferCapacity`) is where a C#-side replay buffer would
actually be needed; it is **out of scope** and deferred (ROADMAP line 108).

### KSP detection (question 4)

`StateFlow` **is-a** `Flow` in Kotlin, so detection order is load-bearing. The current detector
matches on the **declared type's exact `qualifiedName`** (`qualifiedTypeName in FLOW_TYPES` where
`FLOW_TYPES = { "kotlinx.coroutines.flow.Flow" }`, `CirTypeMapping.kt:59`; and
`returnQualified in FLOW_TYPES`, `CirClassTranslator.kt:257`). A property declared
`val count: StateFlow<Int>` resolves to declaration qualifiedName
`kotlinx.coroutines.flow.StateFlow`, which does **not** match `FLOW_TYPES` today — so a StateFlow is
currently **skipped**, not mis-rendered as a Flow. *Verified-in-repo* (`CirTypeMapping.kt:59-61`,
`CirClassTranslator.kt:142,257`).

Add a **separate** set and check it **first**, and **do not** add StateFlow to `FLOW_TYPES`:

```kotlin
internal val STATE_FLOW_TYPES = setOf(
  "kotlinx.coroutines.flow.StateFlow",
  "kotlinx.coroutines.flow.MutableStateFlow",   // mapped to a read-only KotlinStateFlow<T> view in v1
)
// SharedFlow / MutableSharedFlow remain unlisted and deferred (ROADMAP line 108).
```

Every detection site becomes: check `in STATE_FLOW_TYPES` (→ `KotlinStateFlow<T>` + `_value` export)
**before** `in FLOW_TYPES` (→ `KotlinFlow<T>`). Because detection keys on the **declared** type's
exact qualified name (not supertype/assignability walking), there is no fall-through: a
`StateFlow<T>`-declared member never reaches the `FLOW_TYPES` branch, and a member declared
`Flow<T>` (even if the runtime instance is a StateFlow) stays a `KotlinFlow<T>` — correct, because
the author declared the read surface as `Flow`. **Do not switch detection to `isAssignableFrom`** —
that would make StateFlow match the Flow branch and silently lose `.Value` (Alternative 3's failure).
This is the exact hazard the task flagged.

### Claim labelling

- *Verified-in-repo:* the `_collect` export shape, callback triple, `KotlinFlow<T>` /
  `KotlinFlowEnumerator<T>` (`CirFlowRenderer.kt`), the `internal` non-sealed `KotlinFlow<T>` ctor
  (subclassable), `NugetMarshal.FromHandle<T>`'s dispose-box-for-primitives / `new T(handle)`-for-objects
  behaviour (`CirMarshalRenderer.kt:104-183`), and that StateFlow is presently skipped (not detected)
  because `FLOW_TYPES` holds only `...flow.Flow` and detection is exact-qualifiedName.
- *Inferred from SKIE docs:* SKIE maps StateFlow → `SkieSwiftStateFlow<T>` implementing
  `AsyncSequence` with `T` preserved (the docs page confirms `AsyncSequence` + type preservation).
- *Inferred, NOT verified this session:* that `SkieSwiftStateFlow<T>` exposes a synchronous `.value`
  property. The chosen design does not depend on this claim (it is justified by Kotlin's own
  `StateFlow.value` and Rx.NET `BehaviorSubject.Value`), but it is stated as a SKIE precedent and is
  unverified.
- *Inferred, load-bearing, NOT spiked:* that the generated Kotlin (`StableRef.create(x.value as Any)
  .asCPointer()` in a `@CName` `_value` export) and the C# subclass
  (`KotlinStateFlow<T> : KotlinFlow<T>`, `Value => NugetMarshal.FromHandle<T>(...)`) compile and
  round-trip on the real mingwX64 toolchain and in the Kotlin-dylib-in-.NET-host topology. Evidence
  is strong: the `_value` body is byte-for-byte the per-item boxing already shipped in the collect
  body, and `FromHandle<T>` is the already-shipped consumer of exactly such a box. A full
  Kotlin/Native spike was **not run** (konanc toolchain, high cost; consistent with ADR-026 which
  also relied on the walking-skeleton integration test as the real ABI validator). **If this is
  wrong, StateFlow `.Value` reads produce garbage or fail to compile**; the implementing agent MUST
  confirm via the walking-skeleton integration test (ADR-055/060) before relying on it.

## Consequences

### New / changed CIR + KSP nodes

- **`STATE_FLOW_TYPES`** set added to `CirTypeMapping.kt` (checked before `FLOW_TYPES` everywhere
  `FLOW_TYPES` is consulted: `CirClassTranslator.kt:142,257,545`; `ForwardBridgeTypeClassifier.kt:163`;
  `NugetProcessor.kt:484-498,778-798`).
- **`CirProperty` / `CirMethod`** gain `isStateFlow: Boolean` (reusing the existing `flowElementType`).
  When set, the getter/method emits `new KotlinStateFlow<T>(collectLambda, valueLambda)` instead of
  `new KotlinFlow<T>(collectLambda)`, and an extra `Native_Get{X}Value` `[DllImport]` is emitted.
- **`CirFlowRenderer.kt`** gains `KotlinStateFlow<T>` (the ~10-line subclass) after `KotlinFlow<T>`,
  and a `renderStateFlowMethod` counterpart to `renderFlowMethod`. Gated by a new
  `tracker.needsStateFlow` (which also implies `needsFlow`, since `KotlinStateFlow` extends
  `KotlinFlow`).
- **`ClassExports.kt`** flow-property loop (`:110`) and flow-method loop (`:212`) gain a StateFlow
  branch that emits **both** the ADR-026 `_collect` export (reused) **and** the new `_value` export
  (`buildStateFlowValueBody` / `buildStateFlowMethodValueBody`, siblings of the existing
  `buildFlowCollectBody` at `:314`).
- `needsFlow` import wiring (`NugetProcessor.kt:795` adds `kotlinx.coroutines.flow.collect`) is
  unchanged; StateFlow reuses the same `collect` import.

### Behavioural notes carried to docs

- A StateFlow `await foreach` **never terminates on its own** (hot/open). Consumers must bound it
  with `CancellationToken`/`break`. This is the one consumer-visible divergence from cold Flow and
  must be prominent in `coroutines-and-flow.md`.
- `.Value` follows ADR-005 object semantics for object element types: a fresh wrapper per read,
  identity not preserved. For an object-typed `.Value`, the returned wrapper owns its handle and is
  `IDisposable` (`using var cat = vm.CurrentCat.Value;`).
- The `_value` export omits `errorOut`, a deliberate narrowing of ADR-030's wrap-all-property-getters
  policy, justified because `StateFlow.value` cannot throw. If a future element-boxing path can throw
  (it cannot today), this is the first thing to revisit.

### v1 scope

- `StateFlow<T>` as a **class property** (`val count: StateFlow<Int>`) → `KotlinStateFlow<T>` with
  get-only `.Value` + `IAsyncEnumerable<T>`.
- `StateFlow<T>` as a **non-suspend function return** (`fun counter(): StateFlow<Int>`) → same.
- `MutableStateFlow<T>` at those positions → the **read-only** `KotlinStateFlow<T>` view (honest
  upcast; the settable `.Value` write is deferred). This maximises coverage without a lying API,
  since the idiomatic Kotlin pattern already exposes `MutableStateFlow` privately and a `StateFlow`
  publicly.
- Element types: primitives, `String`, and object types — the same set `KotlinFlow<T>` /
  `NugetMarshal.FromHandle<T>` already support.
- Cancellation (`CancellationToken` / `WithCancellation`), error propagation (`KotlinException`),
  and `ObjectDisposedException` after dispose — all inherited from ADR-026 unchanged.

### Deferred (each its own ROADMAP item)

- **Settable `.Value`** (true `MutableStateFlow<T>` write) — needs a C#→Kotlin value-**setter**
  export (a reverse-direction write of `T` into `stateFlow.value`), which is a Phase 7 bidirectional
  concern, not a forward-only mapping. Until then `MutableStateFlow` binds as the read-only view.
- **`INotifyPropertyChanged` adapter** — a deferred, opt-in convenience for XAML data-binding,
  layered on top of `KotlinStateFlow<T>` (Alternative 2); not the v1 core.
- **`SharedFlow<T>`** (ROADMAP line 108) — configurable replay/buffer; a distinct mapping (a C#-side
  replay buffer or `IObservable<T>`), explicitly **not** StateFlow. Kept narrowly separate.
- **Nullable element `StateFlow<T?>`** and **nullable `StateFlow<T>?`** — `StateFlow<T?>` makes
  `.Value` a `T?` (needs the nullable cascade of ADR-002/061 at the value getter); `StateFlow<T>?`
  needs the two-call nullable pattern around the whole member. Both deferred (mirror ROADMAP line 112
  for Flow).
- **`suspend fun` returning `StateFlow<T>`**: ~~treat as non-suspend returning StateFlow when it
  lands (mirror ROADMAP line 110 / ADR-026)~~. **Superseded/corrected by
  [ADR-068](068-suspend-returning-stateflow.md) (Accepted):** that literal reading is a compile
  error, a `suspend fun` cannot be called from a non-suspend `@CName` export. The outer suspend is
  kept as `Task`, composing ADR-019 over this mapping: `Task<KotlinStateFlow<T>> XxxAsync()`.
- **`StateFlow<T>` as a function parameter** (C# → Kotlin) and **as a generic type argument**
  (`Box<StateFlow<String>>`) — deferred, mirroring the corresponding Flow items (ROADMAP lines
  113-114).

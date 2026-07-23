# ADR-068: `suspend fun` returning `StateFlow<T>` — keep the outer suspend as `Task<KotlinStateFlow<T>>`, not a collapsed sync return

## Status

Proposed

## Context

Kotlin allows a `suspend fun` whose return type is a `StateFlow<T>`:

```kotlin
class CatMoodTracker(name: String) {
    suspend fun awaitMoodReport(): StateFlow<String> { /* may suspend, then hands back a holder */ }
}
```

ADR-065 mapped a **non-suspend** `StateFlow<T>` property or function return to `KotlinStateFlow<T> : KotlinFlow<T>`
(synchronous `.Value` + `IAsyncEnumerable<T>`). ADR-019 mapped a `suspend fun` to a `Task`-returning `…Async()`
method. This ADR covers the **combination**: a suspend function whose return is a `StateFlow<T>`.

ADR-065's "Deferred" section (line 473-474) and ADR-026's (line 615) both phrase this as "treat as non-suspend
returning StateFlow / Flow when it lands," on the intuition that "the outer suspend rarely carries meaning" — you
await once to get the state holder, then read `.Value` / `await foreach` on it. Taken literally, that phrasing
suggests emitting a **synchronous** `KotlinStateFlow<string> AwaitMoodReport()`. This ADR investigates whether
that literal collapse is correct, and concludes it is **not** — the honest mapping keeps the outer suspend as
`Task`, producing `Task<KotlinStateFlow<string>> AwaitMoodReportAsync()`.

### Current behaviour (verified-in-repo)

Detection partitions **suspend before flow**. In `CirClassTranslator.kt:277-284`:

```kotlin
val (suspendMethods, regularMethods) = filteredMethods.partition { it.modifiers.contains(Modifier.SUSPEND) }
val (flowMethods, nonFlowMethods) = regularMethods.partition { /* returnQualified in FLOW_TYPES || STATE_FLOW_TYPES */ }
```

A `suspend fun awaitMoodReport(): StateFlow<String>` therefore lands in `suspendMethods` and **never reaches the
StateFlow branch**. It is processed by the `asyncMembers` path (`CirClassTranslator.kt:398-446`), where
`asyncReturnType = KOTLIN_TO_CSHARP_PARAM[methodReturn] ?: methodReturn` resolves `methodReturn` to the simple
name `"StateFlow"` (not in the map), so it emits `Task<StateFlow>` — a `Task` over an **undefined C# type**
`StateFlow`, with no `_collect`/`_value` export and no `.Value`. *Verified-in-repo:* the partition order and the
`asyncReturnType` computation. *Inferred (not spiked):* that the resulting C# fails to compile (undefined
`StateFlow`) rather than silently degrading; either way the current output is broken, so this is a genuine gap,
not a working mapping.

### Why the mechanism is not a light mirror of ADR-065

ADR-065's non-suspend StateFlow method exports re-invoke the parent method inside each export:
`buildStateFlowValueMethodBody` emits `return StableRef.create(obj.moodReport().value as Any).asCPointer()`
and the collect body emits `obj.moodReport().collect { … }` (*verified-in-repo:* `ClassExports.kt:416,448`).
That re-invocation-per-access shape **cannot be reused for a suspend method**: `obj.awaitMoodReport()` is a
`suspend` call and cannot be invoked from the non-suspend `_value`/`_collect` `@CName` exports (it "can only be
called from a coroutine or another suspend function"). So the ADR-065 per-member export shape is structurally
unavailable here. Something new is required. That is what makes this a real decision rather than a trivial mirror.

### How other Kotlin interop targets handle it

- **SKIE (Touchlab, Swift):** a `suspend fun` is always mapped to a Swift `async func`; its return type is mapped
  independently. A suspend function returning `StateFlow<T>` surfaces as `async func … -> SkieSwiftStateFlow<T>` —
  the outer suspend is **kept as `async`**, the inner StateFlow becomes the state-flow wrapper. SKIE does **not**
  collapse the outer suspend. *Inferred from SKIE docs* (`skie.touchlab.co/features/suspend`,
  `skie.touchlab.co/features/flows`): the docs confirm suspend → Swift `async` and a "special StateFlow
  implementation" with get/set value; the precise composed signature is inferred from those two behaviours, not
  quoted verbatim.
- **Official ObjC / Swift Export:** suspend → completion-handler / `async`; StateFlow not specially handled.
  Composition would be `async` returning an opaque `id`. No collapse. *Inferred, per ADR-019/026 surveys.*
- **Kotlin/JS:** `suspend` → `Promise<T>` (Kotlin 2.3.0+); returning a Flow/StateFlow has no standard treatment,
  developers hand-wrap. The suspend half stays a `Promise`. *Inferred, per ADR-019/026.*
- **Java (JVM):** `suspend` compiles to a `Continuation`-taking method; the recommended bridge is a
  `CompletableFuture`-returning wrapper. The suspend half stays async; the StateFlow is returned by that future.
  *Inferred (well-known kotlinx.coroutines Java-interop behaviour).*

Every analogue keeps the outer suspend as its async primitive and maps the StateFlow return separately. None
collapses the suspend into a synchronous call. That is the cross-ecosystem precedent for the decision below.

### What's idiomatic in C#

`await`-once-then-read matches `Task<T>` composition exactly:

```csharp
KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();
string now = report.Value;                       // synchronous thereafter
await foreach (var m in report.WithCancellation(ct)) { … }
```

A synchronous `KotlinStateFlow<string> AwaitMoodReport()` would have to **block** to satisfy the suspend, which
C# reserves for genuinely synchronous work. `Task<KotlinStateFlow<T>>` is the composition a C# developer expects
from "an async operation that yields a state holder."

## Alternatives Considered

### 1. `Task<KotlinStateFlow<T>>` — keep the outer suspend, StateFlow as a first-class handle (chosen)

Compose the two existing ADRs orthogonally: ADR-019 (suspend → `Task`) wraps ADR-065 (StateFlow →
`KotlinStateFlow<T>`). The generated method is `Task<KotlinStateFlow<T>> XxxAsync()`. The suspend export hands the
awaited `StateFlow` **object** back as a `StableRef` handle (which the existing suspend-method body already does);
the C# completion wraps that handle in a `KotlinStateFlow<T>` whose `_collect`/`_value` are keyed on the **flow's
own handle**, via new shared generic exports.

**Consumer API:**

```csharp
using var tracker = new CatMoodTracker("Whiskers");
KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();
Assert.Equal("sleepy", report.Value);
tracker.SetMood("grumpy");
Assert.Equal("grumpy", report.Value);           // same underlying MutableStateFlow, observable
```

**Pros:**
- Honest: if the function genuinely suspends (IO to build the holder), the caller `await`s; **no thread is
  blocked**. This is the whole reason ADR-019 rejected `runBlocking` (its Alternative 3).
- Matches ADR-026's already-documented shape for the sibling case (line 563: `suspend fun streamResults(): Flow<Result>`
  → `Task<KotlinFlow<Result>> StreamResultsAsync()`), and SKIE's `async -> SkieSwiftStateFlow<T>`.
- The "await once, then sync `.Value` / `await foreach`" usage the task describes maps 1:1: the `await` is the
  outer suspend, everything after is ADR-065's `KotlinStateFlow<T>` unchanged.
- The new shared generic `_collect`/`_value`-on-a-flow-handle exports are exactly the infrastructure the deferred
  "StateFlow as a function parameter" and "StateFlow as a generic type argument" items (ROADMAP line 116) will
  also need. Forward-looking, not throwaway.

**Cons:**
- Requires new machinery (below): two shared generic exports keyed on a flow handle, and a handle-owning
  `KotlinStateFlow<T>` variant that disposes the flow handle. More than a per-member tweak.
- The awaited `StateFlow` `StableRef` must outlive the C# wrapper (owned-handle lifetime), a difference from the
  ADR-065 property case where the wrapper holds no handle.

### 2. Synchronous `KotlinStateFlow<T>` via `runBlocking` (the literal "collapse")

Emit `KotlinStateFlow<string> AwaitMoodReport()` and reuse ADR-065's per-member export shape verbatim, wrapping
the suspend call in `runBlocking`: `runBlocking { obj.awaitMoodReport() }.value` / `.collect { … }`.

**Pros:**
- Reuses ADR-065's per-member `_value`/`_collect` exports with a one-line `runBlocking` wrap. No new shared
  exports, no owned-handle lifetime. Smallest diff.
- Re-invocation-per-access is already ADR-065's accepted semantics for non-suspend StateFlow methods, so that
  part is not new.

**Cons:**
- `runBlocking` **blocks a thread** for the duration of the suspend, which may be a UI thread on the C# caller.
  This is exactly ADR-019 Alternative 3, explicitly rejected for suspend mapping. Applying it here would make
  StateFlow the one place the project reintroduces blocking bridge calls.
- Re-invokes the suspend function **per `.Value` read and per `await foreach`**. For a non-suspend `fun` that is
  cheap and ADR-065 accepts it; for a genuinely-suspending function that repeatedly performs IO/allocation, and
  can return a *different* `StateFlow` instance each call, it is semantically wrong (the C# object claims to be a
  stable state holder but silently re-runs an async operation behind every access).
- Misrepresents the API: hides that obtaining the holder is asynchronous.

Rejected. Its one virtue (small diff) does not outweigh reintroducing blocking + per-access re-invocation of a
suspend body.

### 3. Drop the suspend entirely and call synchronously (no `runBlocking`)

Not viable: a `suspend fun` cannot be called from a non-suspend `@CName` export at all. This is not an option, it
is a compile error. Listed only to record that the literal reading of "treat as non-suspend" is impossible without
either `runBlocking` (Alternative 2) or keeping an async boundary (Alternative 1).

## Decision

Use **Alternative 1**: a `suspend fun` returning `StateFlow<T>` maps to `Task<KotlinStateFlow<T>> XxxAsync()`.
The outer suspend is kept as `Task` (ADR-019 naming: `Async` suffix); the inner StateFlow is ADR-065's
`KotlinStateFlow<T>` unchanged on its public surface. **This supersedes the literal "treat as non-suspend"
wording** in ADR-065's and ADR-026's deferred notes: that wording is corrected to "compose ADR-019 over the
Flow/StateFlow mapping — `Task<KotlinFlow<T>>` / `Task<KotlinStateFlow<T>>`."

### Detection change

In `CirClassTranslator.kt`, the suspend partition (`:277`) must **peel StateFlow/Flow-returning suspend methods
into their own bucket** before the plain-async `asyncMembers` path claims them. A suspend method whose
`returnQualified in STATE_FLOW_TYPES` (checked before `FLOW_TYPES`, per ADR-065's ordering rule) routes to a new
`suspendStateFlowMembers` path that emits a `Task<KotlinStateFlow<T>>` method plus the async `_async` export and
wires the completion to build a handle-owning `KotlinStateFlow<T>`. The plain `asyncMembers` path stays for
non-Flow suspend returns. *This is the load-bearing code change; do not add StateFlow to the plain-async element
map.*

### Kotlin exports

**(A) The suspend `_async` export is REUSED UNCHANGED.** `buildSuspendMethodBody` (`SuspendFunctionExports.kt:120`)
already does `val result = obj.awaitMoodReport(args); val resultRef = StableRef.create(result).asCPointer();
fn.invoke(resultRef, null, 0, userData)`. For a `StateFlow<String>` return, `result` **is** the `StateFlow`
object and `StableRef.create(result)` boxes that object as a handle. *Verified-in-repo:* the body shape.
No change needed to hand the flow back as a handle.

**(B) Two NEW shared generic exports, keyed on a flow handle** (generated once, like the `nuget_func*` helpers),
because the awaited flow can no longer be re-derived from the parent + args (it is suspend):

```kotlin
// Collect an already-obtained StateFlow/Flow handle. Element boxing is generic (`value as Any`),
// identical to one onNext emission in ADR-026's collect body.
@CName("nuget_stateflow_collect")
fun export_nuget_stateflow_collect(
    flowHandle: COpaquePointer,     // StableRef<StateFlow<*>> from the await
    scopeHandle: COpaquePointer,    // parent class scope (owns/cancels the collect job)
    onNextPtr: COpaquePointer, onCompletePtr: COpaquePointer, onErrorPtr: COpaquePointer,
    userData: COpaquePointer,
): COpaquePointer {                 // Job StableRef, ADR-022 cancellation
    val flow = flowHandle.asStableRef<kotlinx.coroutines.flow.StateFlow<*>>().get()
    // ... reinterpret callbacks, then scope.launch(ATOMIC) { flow.collect { onNext(box(it)) } } ...
}

// Synchronous current value of an already-obtained StateFlow handle. No scope, no errorOut.
@CName("nuget_stateflow_value")
fun export_nuget_stateflow_value(flowHandle: COpaquePointer): COpaquePointer =
    StableRef.create(flowHandle.asStableRef<kotlinx.coroutines.flow.StateFlow<*>>().get().value as Any).asCPointer()
```

*Inferred, load-bearing, NOT spiked:* that `flowHandle.asStableRef<StateFlow<*>>().get().collect { … }` and
`.value` compile and round-trip on the mingwX64 toolchain with a star-projected `StateFlow<*>`. Evidence is
strong — the collect/box bodies are byte-for-byte ADR-026/065's already-shipped shapes, differing only in that
the receiver is a star-projected handle rather than `obj.member`. **If star-projection on the exported
`StateFlow<*>` fails to compile or erases the element wrongly, `.Value`/`await foreach` produce garbage.** The
implementing agent MUST confirm via the walking-skeleton integration test (ADR-055/060) before relying on it.
*(An `nuget_flow_collect` twin without `_value` would serve the deferred `suspend fun`-returning-`Flow` item,
ROADMAP line 118; out of scope here but the same shape.)*

### C# side

The async completion currently does `t.SetResult(new T(resultPtr))` for object returns (`CirClassRenderer.kt`
`renderAsyncMethod`). For this case it instead builds a **handle-owning** `KotlinStateFlow<T>`:

```csharp
public Task<KotlinStateFlow<string>> AwaitMoodReportAsync()
{
    var tcs = new TaskCompletionSource<KotlinStateFlow<string>>(TaskCreationOptions.RunContinuationsAsynchronously);
    // ... standard ADR-019 GCHandle + NugetAsyncCallback wiring, passing _handle + scope ...
    // on success (resultPtr = StableRef<StateFlow>):
    IntPtr flowHandle = resultPtr;
    IntPtr scope = GetOrCreateScope();
    tcs.SetResult(new KotlinStateFlow<string>(
        (onNext, onComplete, onError, userData) =>
            Native_StateFlowCollect(flowHandle, scope, onNext, onComplete, onError, userData),
        () => Native_StateFlowValue(flowHandle),
        ownedHandle: flowHandle));           // NEW: wrapper owns + disposes the awaited flow handle
    ...
}
```

`KotlinStateFlow<T>` gains an optional `ownedHandle` constructor overload that, when set, disposes the flow
`StableRef` (via `nuget_dispose`) on `DisposeAsync()`/`Dispose`. The ADR-065 property/method cases pass no owned
handle (they hold none) and are unchanged. The `_collect`/`_value` lambdas call the two shared generic
`Native_StateFlowCollect` / `Native_StateFlowValue` `[DllImport]`s instead of per-member exports.

*Verified-in-repo:* the ADR-019 async callback wiring and `new T(resultPtr)` object path (`CirClassRenderer.kt`),
and that `KotlinStateFlow<T> : KotlinFlow<T>` with an `internal` non-sealed ctor is subclassable/extensible
(ADR-065, `CirFlowRenderer.kt`). *Inferred (not spiked):* the exact generated C# above compiles and the
owned-handle dispose is correct; confirmed only by the integration test.

### Consumer API (the failing-test contract for Step 3)

```csharp
using var tracker = new CatMoodTracker("Whiskers");

// 1. Outer suspend is a Task — await once to get the state holder.
KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();

// 2. Thereafter it IS an ADR-065 KotlinStateFlow<T> — synchronous .Value.
Assert.Equal("sleepy", report.Value);

// 3. Same underlying MutableStateFlow as the `mood` property/`moodReport()` — mutation is observable.
tracker.SetMood("grumpy");
Assert.Equal("grumpy", report.Value);

// 4. Hot async iteration; replays current value, never completes on its own — bound with cancellation.
var seen = new List<string>();
var cts = new CancellationTokenSource();
await foreach (var m in report.WithCancellation(cts.Token))
{
    seen.Add(m);
    if (seen.Count >= 1) cts.Cancel();
}
Assert.Equal("grumpy", seen[0]);         // first emission == current value at subscription (replay-1)

// 5. It IS-A KotlinFlow<T> / IAsyncEnumerable<T> (ADR-065 upcast), unchanged.
IAsyncEnumerable<string> asFlow = report;

// 6. Object element type awaits to a KotlinStateFlow<Cat>; .Value is a fresh wrapper (ADR-005).
using var playmate = (await tracker.AwaitPlaymateReportAsync()).Value;
```

### Kotlin sample source (to add to `test-library/`)

Extend the existing ADR-065 fixture `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/CatMoodTracker.kt`
with a genuinely-suspending StateFlow return that shares the existing `_mood` holder (so mutation-visibility can
be asserted across the property, the non-suspend `moodReport()`, and the new suspend surface):

```kotlin
/**
 * ADR-068: `suspend fun` returning `StateFlow<T>`. Genuinely suspends (a real await) before handing
 * back the SAME underlying [_mood] MutableStateFlow as [mood]/[moodReport], so the outer suspend is
 * not vestigial and mutation is observable through all three surface positions.
 */
suspend fun awaitMoodReport(): StateFlow<String> {
    kotlinx.coroutines.delay(1)
    return mood
}

/** ADR-068: object-element variant — suspend fun returning StateFlow<Cat>. */
suspend fun awaitPlaymateReport(): StateFlow<Cat> {
    kotlinx.coroutines.delay(1)
    return playmate
}
```

## Consequences

### v1 scope

- `suspend fun` returning `StateFlow<T>` (and `MutableStateFlow<T>` as the read-only `KotlinStateFlow<T>` view,
  per ADR-065) as a **class method** → `Task<KotlinStateFlow<T>> XxxAsync()`.
- Element types: primitives, `String`, object types — the ADR-065/026 set unchanged.
- Cancellation, error propagation, `.Value`, `await foreach`, `IAsyncEnumerable<T>` upcast — all inherited from
  ADR-065/026 unchanged once the holder is awaited.

### Deferred

- **Top-level `suspend fun` returning `StateFlow<T>`** (no parent class scope). Needs a scope decision for the
  generic `nuget_stateflow_collect` (fresh `CoroutineScope(Dispatchers.Default)`, per ADR-022's top-level suspend
  handling). Deferred to keep v1 to class methods, mirroring how ADR-026 scoped its method form first.
- **`suspend fun` returning `Flow<T>`** (ROADMAP line 118) — the exact twin using an `nuget_flow_collect` shared
  export without `_value`. Not folded in here to keep this ADR StateFlow-scoped, but the machinery is deliberately
  shared-generic so that item becomes a near-trivial follow-up.
- **Nullable** `suspend fun (): StateFlow<T>?` / `StateFlow<T?>` — mirror ADR-065's deferred nullable items
  combined with ADR-019's deferred nullable-suspend two-call pattern.
- **Settable `.Value`** — unchanged from ADR-065 (read-only view in v1).

### Documentation

- Correct the "treat as non-suspend returning StateFlow/Flow" phrasing in ADR-065 (line 473-474) and ADR-026
  (line 615) to "compose ADR-019 over the mapping: `Task<KotlinStateFlow<T>>` / `Task<KotlinFlow<T>>`," and mark
  ROADMAP line 116's "treat as non-suspend returning StateFlow when it lands" as resolved by this ADR with the
  corrected decision.
- `coroutines-and-flow.md`: note that a suspend StateFlow return is awaited once to obtain the holder, after which
  it behaves exactly like a non-suspend `KotlinStateFlow<T>` (hot, `.Value`, bounded `await foreach`).

### Claim labelling summary

- *Verified-in-repo:* suspend-before-flow partition ordering and the resulting `Task<StateFlow>` (undefined-type)
  mis-routing (`CirClassTranslator.kt:277-284,398-446`); ADR-065's per-member `_value`/`_collect` re-invoke
  `obj.member(...)` and so cannot host a suspend receiver (`ClassExports.kt:416,448`); the suspend `_async` body
  already boxes the return via `StableRef` (`SuspendFunctionExports.kt:120-149`); the async callback's object
  path is `new T(resultPtr)` (`CirClassRenderer.kt` `renderAsyncMethod`); `KotlinStateFlow<T> : KotlinFlow<T>`
  is subclassable (ADR-065, `CirFlowRenderer.kt`).
- *Inferred from SKIE docs:* suspend → Swift `async`, StateFlow → a special get/set state-flow wrapper; the
  composed `async -> SkieSwiftStateFlow<T>` signature is inferred from those two behaviours.
- *Inferred, load-bearing, NOT spiked:* the two new `@CName` exports on a star-projected `StateFlow<*>` handle and
  the handle-owning C# `KotlinStateFlow<T>` compile and round-trip on the real toolchain. Strong evidence (byte
  reuse of shipped ADR-026/065 shapes) but no Kotlin/Native spike was run; confirm via the walking-skeleton
  integration test before relying on it.

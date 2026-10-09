# ADR-194: Suspend returning Flow, asynchronous acquisition followed by an owned stream

## Status

Accepted. Implemented and verified through the fresh-package pipeline on 2026-10-03. The composition
follows ADR-019/026/068; this ADR records the additional public disposal contract and typed
collector choice.

## Context

Restatement: Kotlin-declared `suspend fun …: Flow<T>` becomes C# `Task<KotlinFlow<T>>`, preserving
asynchronous acquisition then stream.

Before implementation at base `dd1b74fe`, ADR-068 kept a suspend StateFlow return asynchronous,
while the plain Flow sibling remained a named unsupported return. **Verified in that base's
source:** `ForwardLegacyRouteCollections.kt:557` refused the generic Flow return,
`CirClassTranslator.kt:1937` peeled only StateFlow returns, and `CirFunctionTranslator.kt:731` did
the same for top-level declarations.

Before implementation at base `dd1b74fe`, an acquired Flow had no owned handle to release.
**Verified in that base's source:** `CirFlowRenderer.kt:87`
declares KotlinFlow without IDisposable or owned-handle storage, while `:267` gives KotlinStateFlow
both. ADR-187 requires SafeHandle ownership and in-flight keep-alive. Returning Task over an
untyped interface or over a hidden owning subtype would conceal disposal from the consumer.

The ordinary Flow vocabulary includes nullable elements and collections whose components need
projection. **Verified by source:** `ForwardLegacyRouteCollections.kt:739` admits existing plain
Flow element shapes; `FlowExports.kt:460` uses null-pointer encoding and collection projections
before boxing; `CirClassTranslator.kt:1714` through `:1739` supplies matching reads. The generic
StateFlow collector (`NugetRuntime.kt:837`) boxes unchanged values and cannot carry that full
vocabulary. This feature must not silently inherit the narrower suspend-StateFlow element gate.

## Alternatives Considered

### 1. Owned KotlinFlow base and typed per-member acquired collector (chosen)

KotlinFlow gains optional SafeHandle ownership and IDisposable. StateFlow delegates its existing
ownership to the base. Each suspend Flow member has one collector that receives its acquired
Flow handle and reuses existing per-element projection helpers. It uses the runtime's existing
`collectForCSharp`, so no new runtime ABI entry point is required.

This preserves the promised consumer type, prompt explicit disposal, finalizer fallback, and the
current Flow element vocabulary. It adds one generated collector per supported declaration.

### 2. Hidden owned subtype of KotlinFlow

Keeps the base declaration unchanged but makes the Task's advertised type hide IDisposable.
A consumer must cast or learn a second public type to release the acquired handle. Rejected.

### 3. Shared generic `nuget_flow_collect` export

Small for primitive/object elements, but cannot project enum/value-class collection components
without an additional erased projection mechanism. Narrowing admission to the StateFlow set
would fail the requested composition over the shipped Flow vocabulary. Rejected.

### 4. Apply a Flow.map wire adapter during acquisition, then generic collection

Can preserve lazy projection but retains another adapter and moves projection to a second seam
instead of the current typed emitter. Rejected as unnecessary mechanism divergence.

### 5. Synchronous Flow return or merged async iterator

Collapsing the outer suspension loses asynchronous acquisition or would block. Deferring the
acquisition until each enumeration calls the Kotlin suspend function changes acquisition count
and side effects. Both contradict ADR-068 and the restatement. Rejected.

## Decision

Verified consumer contract:

```C#
await using var cafe = new SuspendFlowCafe();
using KotlinFlow<int> portions = await cafe.PortionsAsync();
await foreach (int portion in portions)
    Console.WriteLine(portion);
```

The suspend function runs exactly once per acquisition. Enumeration uses the same acquired
Flow object, with ordinary Kotlin Flow semantics; the binding adds no eager subscription.

### Ownership and startup

- KotlinFlow implements IDisposable and accepts an optional owned `NugetKotlinHandle` internally.
  Its Dispose atomically takes and disposes that handle. A non-owning ordinary Flow disposes as
  a no-op, matching current non-owning StateFlow behavior. StateFlow delegates its existing
  optional ownership to the base. Verified in generated declarations and integration tests.
- The success callback immediately wraps the result pointer in its owning SafeHandle. The cleanup
  branch for a later construction/handoff failure releases that handle and faults the Task, as
  verified by source inspection. Successful ownership transfer and Kotlin acquisition-fault cleanup
  are covered by integration and leak tests; managed holder-construction failure was not injected.
- Capture the instance scope once before launching acquisition and retain that same SafeHandle
  in the collector closure. Do not call GetOrCreateScope again in the callback. Top-level acquisition
  captures the null sentinel; its collector uses the existing ad-hoc scope convention. Verified by
  top-level acquisition and collection tests.
- Delegate capture keeps the Flow/scope SafeHandle objects reachable. The P/Invoke SafeHandle
  lease protects startup; the Kotlin export dereferences the Flow and scope synchronously before
  launch. After that, the coroutine holds the Kotlin Flow object directly. A separate native-handle
  lease for the full collection is unnecessary. **Verified precedent:** ADR-187's keep-alive audit,
  `NugetRuntime.kt:847`, `FlowExports.kt:484`; verified by active collection and owner-disposal tests.
- Disposing the wrapper before a new collection closes its owned handle and that collection fails
  safely. Disposing it after collection starts releases its handle without cancelling that collector.
  The enumerator/token and instance scope remain responsible for collection cancellation. GC
  fallback follows ADR-187. Verified by explicit disposal, active collection, and GC leak tests.

### Cancellation, errors and late completion

Acquisition and collection each use their own existing cancellation protocol. The acquisition
token cancels the Kotlin acquisition job; it must not complete the Task independently or reject
a successful result solely because the token is cancelled.

**Verified by source:** `NugetLaunch.kt:31` reports cancellation when the body throws
CancellationException and otherwise reports its successful pointer, including a body that ignores
cancellation. `CirConcurrencyRenderer.kt:187` frees the callback handle and completes the job cell
before materialising the managed result. Reuse this exactly-once path and its immediate-completion
ordering protection.

A cancellation-ignoring successful late result is returned as an owned Flow. If its instance owner
was explicitly disposed during acquisition, it retains the captured closed scope: disposing the
result releases it, and attempting a new collection throws ObjectDisposedException rather than
creating a replacement scope or passing a freed pointer. Verified by the owner-disposal race tests.

Cancelling the acquisition token after success does not cancel future collections. Collection
tokens and enumerator disposal retain the existing Flow behavior, including terminal callback
cleanup. Verified by the post-acquisition token and enumerator cancellation integration tests.

### Typed acquired collector

Generate a collector with Flow-handle, nullable scope-handle, callback-trio and userData slots.
It takes neither the owner handle nor the acquisition parameters. It dereferences the typed Flow,
starts `collectForCSharp`, projects each item with the same helper as ordinary Flow exports, and
emits the resulting retained pointer. The generated collector and its element projections were
verified through the fresh package (`FlowExports.kt:460`, `NugetLaunch.kt:66`).

Use `NugetHandles.retain`/release, not direct StableRef creation/disposal, so every owned handle
is observable by ADR-120. Declare/import the collector on every existing suspend owner path with
the same symbol numbering and namespace rules as acquisition.

## Consequences

- Supported owners are existing suspend routes: top-level, ordinary/non-generic abstract and
  derived classes, sealed bases and class/object-kind arms, carrying Kotlin-backed interfaces,
  and their existing explicit forwards on generic implementers. Generic classes' own suspend
  members and standalone object/companion/extension routing stay outside this item.
- Existing plain Flow element admission/spelling/projection is reused, including nullable scalar,
  string and handle elements, ByteArray, and admitted non-null collection elements. Preserve named
  refusals for nullable collections and unsupported generic elements. Do not widen StateFlow's
  narrower suspend-return element set.
- Nullable Flow containers (since mapped, see the amendment at the end), SharedFlow, Flow
  parameters/type arguments, and backpressure redesign
  stay deferred. This decision adds IDisposable to the fixed KotlinFlow type, an additive surface
  usable on the Task's actual advertised result type.
- Integration tests cover repeated enumeration of one acquired Flow, projected elements,
  class/top-level/sealed/interface routes, immediate completion, cancellation, mapped errors,
  explicit disposal and owner cancellation. Isolated LeakTests rows cover acquisition/emission
  faults, cancellation and late success, immediate completion stress, owner routes, and GC fallback.
  The full fresh-package `scripts/verify.sh` passed on macosArm64. Platforms outside ADR-187's
  existing evidence remain inferred; this ADR adds no cross-platform finalizer claim.

Prior art, **inferred from official documentation**: SKIE maps
[suspend functions](https://skie.touchlab.co/features/suspend) to Swift async and
[Flows](https://skie.touchlab.co/features/flows) to typed AsyncSequence wrappers. The composition
is consistent with retaining both phases. [.NET disposal guidance](https://learn.microsoft.com/en-us/dotnet/standard/garbage-collection/implementing-dispose)
supports explicit ownership plus SafeHandle fallback. No generated Swift signature or new native
header was spiked for this ADR.

## Amendment (2026-10-09): nullable Flow containers are mapped

"Nullable Flow containers ... stay deferred" in the Consequences no longer holds for the container.
`suspend fun (): Flow<T>?` awaits to `Task<KotlinFlow<T>?>` on the class and top-level routes, null
when Kotlin returns no flow; see [ADR-026](026-flow-mapping.md)'s 2026-10-09 amendment. The
`SharedFlow` deferral was closed by ADR-205; Flow parameters/type arguments and backpressure stay
deferred.

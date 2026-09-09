# ADR-114: Collection parameters on the Flow and suspend legacy routes: eager Kotlin copy, call-scoped handle

## Status

Accepted

> **Amended by [ADR-119](119-collection-returns-on-the-legacy-suspend-route.md) (2026-09-09).** This ADR covered the *parameter* position on the Flow and suspend legacy routes and left returns alone. ADR-119 applies the same classify-then-marshal-or-refuse shape to a `suspend` member's *return* (`ForwardLegacyReturnShape`), reusing this ADR's `legacyDescription()` and helper-gate walk, and names the refusal `SKIPPED_UNSUPPORTED_RETURN`.

## Context

GitHub issue #109, found by consuming `main` @ `6f09100` from a real project:

```kotlin
fun advertised(types: List<DeviceType> = DeviceType.entries): StateFlow<List<NearbyDevice>>
suspend fun delete(ids: Set<DeviceId>)
```

generates Kotlin that does not compile:

```kotlin
@CName("mobileevidenceicv_advertised_collect")
public fun export_mobileevidenceicv_advertised_collect(
  handle: COpaquePointer, scopeHandle: COpaquePointer, types: List, onNextPtr: COpaquePointer, ...
```

```
e: CNameExports.kt:230:10 One type argument expected for 'interface List<out E> : Collection<E>'.
e: CNameExports.kt:6648:8 One type argument expected for 'interface Set<out E> : Collection<E>'.
```

`packNuget` fails at the Kotlin compile of the generated `CNameExports.kt`, so the whole package
fails to build, not just the offending member.

### The mechanical cause, per route

Both legacy routes spell a parameter by pasting the declaration's own Kotlin type name through
`ClassName.bestGuess`, which drops type arguments. **Verified in source:**

- Flow / StateFlow methods: `exports/ClassExports.kt:283-290` (`addFlowParameters`, used by the
  `_collect`, `_value`, `_has_value` and `_set_value` builders at `:281`, `:317`, `:337`, `:363`).
- Suspend class methods: `exports/SuspendFunctionExports.kt:76-81`.
- Top-level suspend functions: `exports/SuspendFunctionExports.kt:39` calls `addParameters`, which
  goes through `exports/Helpers.kt:53-58`'s `toBridgeTypeName`. That one **does** preserve type
  arguments (it is the BUG-005 fix), so this route emits `types: kotlin.collections.List<DeviceType>`
  and compiles. It is broken differently, see "The trap" below.

The C# half of both routes maps the parameter through `mapParamType(simpleName)`
(`cir/CirClassTranslator.kt:788-792` for flow methods, `:718-722` for suspend-StateFlow methods,
`cir/CirFunctionTranslator.kt:84` and `:619` for the function routes), and `mapParamType` returns
`"IntPtr"` for anything outside the 13-entry primitive/`String` table
(`cir/CirTypeMapping.kt:35-49,158-159`). **Verified in source.** So the C# side already emits
`Advertised(IntPtr types)`: a public parameter no C# caller has any way to produce.

### What the ordinary synchronous route does (the mechanism being ported)

**Verified in source:**

1. The planner classifies the parameter as `BridgeType.Collection` and records
   `ForwardConversion.COLLECTION_TO_HANDLE` plus `ForwardHelperRequirement.COLLECTION`
   (`forward/ForwardCallablePlanner.kt:2337,1314-1315`, `forward/ForwardMarshallingModel.kt:688-692`).
2. The Kotlin export parameter is declared `COpaquePointer`, not the collection type
   (`forward/ForwardKotlinPlanEmitter.kt:905`).
3. The Kotlin body dereferences and converts:
   `types.asStableRef<MutableList<Any?>>().get().map { ... }`
   (`forward/ForwardKotlinPlanEmitter.kt:1163-1231`, `loweredCollectionExpression` +
   `loweringSuffix`). The wire container is always `MutableList<Any?>` / `MutableSet<Any?>` /
   `MutableMap<Any?, Any?>` of boxed components, never the declared element type, and each
   component is cast and re-wrapped per element (`componentLowering`).
4. The C# side builds that container before the call and disposes it in a `finally`:
   `IntPtr typesHandle = NugetMarshal.CreateList(types);` then
   `NugetMarshal.Dispose(typesHandle);`
   (`forward/ForwardCirPlanProjection.kt:670-691`, `:580`, `:627`), with each element boxed through
   a `nuget_wrap_*` export. The per-element projection is
   `collectionCreateArgument(name, type, depth, csharpType)`, an `internal` top-level function
   (`forward/ForwardCirCollectionComponents.kt:30`) that takes its C# type mapper as a lambda.
5. The `nuget_list_*` / `nuget_set_*` / `nuget_map_*` and `nuget_wrap_*` helper exports are
   **gated**: emitted only when some *plan* or *property plan* carries
   `ForwardHelperRequirement.COLLECTION`, or when one of the declaration scans finds a `List`/`Map`/
   `Set` at a *return* or *property* position (`NugetProcessor.kt:1160-1350`).
6. Public C# spelling is `IReadOnlyList<T>` for `List<T>`
   (`forward/ForwardCirPlanProjection.kt:1403`).

So a collection crosses as one opaque handle to a boxed wire container, with a synchronous
ownership window on the C# side. `IntegrationTests/CollectionParameterCleanupTests.cs` pins that the
window closes on the throwing path.

### The load-bearing problem: the argument outlives the call

**Verified in source.** Both async routes evaluate the argument expression *after* the export has
returned:

- `exports/ClassExports.kt:520-522`: the `_collect` body is
  `val job = scope.launch(start = CoroutineStart.ATOMIC) { ... obj.method($paramCall).collect { ... } }`.
- `exports/SuspendFunctionExports.kt:96-105` (`buildSuspendFunctionBody`) and `:120-129`
  (`buildSuspendMethodBody`): the same, `scope.launch { obj.$methodName($paramCall) }`.

A literal port of the sync route's `finally`-dispose is therefore a use-after-free: C# frees the
wire container while the coroutine has not yet read it.

The three synchronous siblings do **not** have this problem. `_value`, `_has_value` and `_set_value`
call the member and return within the export (`exports/ClassExports.kt:312-380` and the body
builders below them). **Verified in source.**

The C# side of both async routes calls native from inside a closure, not from the method body:

- `cir/CirFlowRenderer.kt:279-287`: `return new KotlinFlow<T>((onNext, onComplete, onError, userData) => Native_XCollect(<args>));`
  The collect delegate runs per subscription, not once per call.
- `cir/CirFlowRenderer.kt:299-325`: `KotlinStateFlow<T>` is constructed with the collect delegate
  *and* `() => Native_XValue(_handle, <params>)`, the comment at `:293-296` spelling it out:
  "the method's own parameters, re-read on each `.Value` access". A `MutableStateFlow` return adds a
  third `v => Native_XSetValue(...)` write lambda.
- `cir/CirConcurrencyRenderer.kt:57-82`: the async method allocates the completion closure's
  `GCHandle` and calls native synchronously, then awaits a `TaskCompletionSource`.

### The trap: fixing only the spelling

The obvious one-line fix is to swap `ClassName.bestGuess(type)` for `toBridgeTypeName()` on the flow
route, matching what `addParameters` already does on the top-level suspend route. **Do not do this.**
It makes the build go green and leaves an API that is silently wrong.

**Verified by spike** (konanc 2.4.10, the repo's pinned Kotlin version per
`gradle/libs.versions.toml:2`):

```bash
cd <scratch>/cname-spike
cat probe.kt
# @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
# @CName("probe_typed_list") fun probeTypedList(items: List<String>): Int = items.size
# @CName("probe_set")        fun probeSet(ids: Set<String>): Int = ids.size
# @CName("probe_handle")     fun probeHandle(handle: COpaquePointer): Int =
#   handle.asStableRef<MutableList<Any?>>().get().size
~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/kotlinc-native probe.kt -produce dynamic -o probe
```

It compiles cleanly, and the generated `probe_api.h` says:

```c
typedef struct { probe_KNativePtr pinned; } probe_kref_kotlin_collections_Set;
typedef struct { probe_KNativePtr pinned; } probe_kref_kotlin_collections_List;

extern probe_KInt probe_handle(void* handle);
extern probe_KInt probe_set(probe_kref_kotlin_collections_Set ids);
extern probe_KInt probe_typed_list(probe_kref_kotlin_collections_List items);
```

Three consequences, the first two verified by that output:

- `@CName` **does** accept a Kotlin collection parameter. The issue's second claim ("a `@CName`
  export cannot take a Kotlin collection at all") is **false as stated**: the only Kotlin compile
  error is the dropped type argument. The design consequence is unchanged, the reason is different.
- The parameter crosses as a `kref` struct-by-value wrapping a **pinned** Kotlin object pointer, not
  as a plain `void*`, while the C# side declares `IntPtr`.
- **Inferred** (not spiked): a single-pointer-field struct and a pointer occupy the same register
  under the AArch64 and SysV x64 calling conventions, so the call would not fault on arity. It would
  hand whatever `IntPtr` the C# caller passed straight to the Kotlin runtime as a live object
  reference: a memory-safety hazard, not a type error. If this inference is wrong the spelling-only
  fix fails loudly instead of quietly, which does not change this ADR's decision.

Even with a genuine `StableRef` pointer the declared element type would still be wrong: the wire
container is `MutableList<Any?>` of boxed components, so `List<DeviceType>` would
`ClassCastException` on first element access. **Verified in source** (step 3 above).

## Alternatives Considered

Refuse-versus-support is settled: the repo owner chose support. What follows is the ownership model,
which is the real decision.

### 1. Eager Kotlin copy at export entry, call-scoped handle (chosen)

The Kotlin export dereferences and converts the wire container **before** `scope.launch`, binding
the result to a local the coroutine captures. The handle is dead the moment the export returns, so
the C# side keeps the sync route's `create` then `finally`-dispose shape, moved inside whichever
closure makes the native call.

Pros:

- The lifetime question disappears rather than being managed. No callback owns a handle, no
  cancellation path can double-free or leak, and `IntPtr` never escapes the native call.
- Every C# call site keeps the shipped `NugetMarshal.CreateList` + `NugetMarshal.Dispose` pair,
  which `CollectionParameterCleanupTests.cs` already exercises for exception safety.
- Semantics match Kotlin's own: `List` is a read-only snapshot at call time. A C# caller mutating
  its list after the call cannot change what the coroutine sees.
- Works identically on the three synchronous StateFlow siblings, which need no hoist at all.

Cons:

- The conversion (`.map { it as kotlin.Int }`, etc.) runs on the caller's thread inside the export
  rather than on the coroutine dispatcher. For a large collection that is caller-visible work. It is
  the same work the sync route already does on the caller's thread.
- A conversion failure now throws *out of* the export instead of into the coroutine's `catch`, so it
  needs its own error arm (see Decision).

### 2. C# holds the handle until the flow or job completes

C# allocates the wire container, passes it, and disposes it from the terminal callback
(`onComplete` / `onError` / the cancellation `onNext(isCancelled: 1)` / the async completion).

Pros: no hoist in the Kotlin emitters; the collection is converted on the coroutine dispatcher.

Cons, decisive:

- Release-exactly-once across three callbacks plus disposal is the hardest problem in this
  codebase's C# runtime, and it is already solved once, painfully, for a different object. The
  comment at `cir/CirFlowRenderer.kt:311-317` records it: freeing the flow ctx in `DisposeAsync`
  alone "crashed the whole C# suite", because `NugetJobNative.Cancel` is asynchronous and Kotlin
  delivers the cancellation callback after `DisposeAsync` returns. The shipped fix is a two-party
  `Interlocked` rendezvous. **Verified in source.** Adding a second, differently-shaped lifetime to
  every flow would mean a second rendezvous per collection parameter.
- `_value` re-invokes the member per read and never completes, so there is no terminal event to
  release on. This alternative cannot cover the StateFlow siblings at all.
- The `KotlinFlow` collect delegate runs per subscription, so "the handle" is not one handle.

### 3. Refcount the wire container

A native refcount on the wire container, incremented on hand-off and decremented by both sides.

Cons: a third lifetime discipline alongside `StableRef` and `GCHandle`, a new pair of exports, and
it solves a problem alternative 1 does not have. Rejected on complexity, not on correctness.

### 4. Refuse with a named skip

Skip the member and report `SKIPPED_UNSUPPORTED_INPUT`. Rejected as the *general* answer by the repo
owner, but **retained as the fallback arm** for a parameter this ADR does not marshal (see Scope).
The refusal machinery is therefore still built, just narrower.

### 5. Fix the spelling only

Rejected: spike-verified above to compile into an unmarshalled `kref`-versus-`IntPtr` crossing. It
converts a loud build failure into a silent memory-safety hazard, and it is the fix a future
implementer is most likely to reach for. Named here so it is refused explicitly.

## Decision

**Alternative 1.** A collection parameter on the Flow / StateFlow and suspend legacy routes crosses
as a `COpaquePointer` handle to the same boxed wire container the synchronous route uses. Kotlin
converts it **eagerly, before `launch`**. C# creates the handle immediately before each native call
and disposes it immediately after that call returns.

### Kotlin half

The parameter is declared `COpaquePointer` (not the collection type), exactly as
`ForwardKotlinPlanEmitter.kt:905` does, and the export body binds a lowered local before the
existing `launch`:

```kotlin
@CName("deviceregistry_advertised_collect")
public fun export_deviceregistry_advertised_collect(
  handle: COpaquePointer,
  scopeHandle: COpaquePointer,
  types: COpaquePointer,          // was: types: List
  onNextPtr: COpaquePointer,
  onCompletePtr: COpaquePointer,
  onErrorPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val obj = handle.asStableRef<com.example.DeviceRegistry>().get()
  val scope = scopeHandle.asStableRef<CoroutineScope>().get()
  val onNext = onNextPtr.reinterpret<CFunction<(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val onComplete = onCompletePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()
  val onError = onErrorPtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> Unit>>()

  // ADR-114: eager, before launch. The C# side disposes `types` as soon as this export returns.
  val typesArg = try {
    types.asStableRef<MutableList<Any?>>().get().map { com.example.DeviceType.entries[it as kotlin.Int] }
  } catch (e: Throwable) {
    onError.invoke(StableRef.create(buildError(e)).asCPointer(), userData)
    return StableRef.create(scope.launch { }).asCPointer()
  }

  val job = scope.launch(start = CoroutineStart.ATOMIC) {
    try {
      obj.advertised(typesArg).collect { value -> /* unchanged */ }
      onComplete.invoke(userData)
    } catch (e: CancellationException) { /* unchanged */ }
      catch (e: Throwable) { /* unchanged */ }
  }
  return StableRef.create(job).asCPointer()
}
```

The lowering expression is `loweredCollectionExpression(name, type)` reused verbatim
(`forward/ForwardKotlinPlanEmitter.kt:1163`, already `internal` and free of plan types, so it is
callable from `exports/`). **Verified in source.**

The error arm exists because the conversion is now outside the coroutine's `try`, and an uncaught
Kotlin exception crossing a `@CName` boundary terminates the process with no diagnostic. It can only
fire on a generator bug (a component cast that disagrees with what the C# side boxed), never on user
data, so it is defence in depth rather than an expected path. Returning a `StableRef` to an
already-launched empty job keeps the non-null `COpaquePointer` return contract, and the C# side
disposes that job exactly as it disposes a real one. **Inferred** (not spiked): that
`scope.launch { }` is an acceptable stand-in job for the C# `KotlinFlow` subscription to cancel and
dispose. If it is wrong, the alternative is to hoist the lowering with no `try` and accept a process
abort on a generator bug, which is what every other legacy export body already does.

The suspend routes take the identical hoist, with the error arm invoking
`fn.invoke(null, errRef, 0.toByte(), userData)` instead (the shape their `catch (e: Throwable)` arm
already uses, `exports/SuspendFunctionExports.kt:110-113`). **Verified in source.**

The three synchronous StateFlow siblings (`_value`, `_has_value`, `_set_value`) need **no hoist**:
they have no `launch`, so the lowered expression is substituted inline at the call, exactly as the
sync route does.

### C# half

The public parameter is the collection (`IReadOnlyList<DeviceType>`), the native parameter is
`IntPtr`. `CirParameter` already carries both: `type` for the public signature and `nativeType` for
the `DllImport`, distinct precisely so a call site can project between them
(`cir/CirModel.kt:504-516`, whose comment gives the enum `Mood`/`int` precedent). `renderDllImport`
renders the extern from `it.nativeType`, not `it.type` (`cir/CirClassRenderer.kt:479-487`), and both
routes reuse the same `methodParams` list for the public signature *and* for the `DllImport`
(`cir/CirClassTranslator.kt:795-800`, `:725-733`), so one `CirParameter` carrying both spellings
covers both halves with no model change. **Verified in source.**

Each native call gets create-then-dispose around it, inside whichever closure makes the call:

```csharp
// Flow / StateFlow: collect delegate, once per subscription
public KotlinStateFlow<IReadOnlyList<NearbyDevice>> Advertised(IReadOnlyList<DeviceType> types)
{
    if (_handle == IntPtr.Zero) throw new ObjectDisposedException(nameof(DeviceRegistry));
    return new KotlinStateFlow<IReadOnlyList<NearbyDevice>>(
        (onNext, onComplete, onError, userData) =>
        {
            IntPtr typesHandle = NugetMarshal.CreateList(types.Select(x => (int)x));
            try { return Native_AdvertisedCollect(_handle, GetOrCreateScope(), typesHandle, onNext, onComplete, onError, userData); }
            finally { NugetMarshal.Dispose(typesHandle); }
        },
        () =>
        {
            IntPtr typesHandle = NugetMarshal.CreateList(types.Select(x => (int)x));
            try { return Native_AdvertisedValue(_handle, typesHandle); }
            finally { NugetMarshal.Dispose(typesHandle); }
        });
}

// suspend: the native call is synchronous, the await is not
public async Task DeleteAsync(IReadOnlySet<DeviceId> ids, CancellationToken cancellationToken = default)
{
    IntPtr idsHandle = NugetMarshal.CreateSet(ids.Select(x => x.Value));
    IntPtr job;
    try { job = Native_DeleteAsync(_handle, GetOrCreateScope(), idsHandle, NugetThunks.NugetAsyncCallbackPtr, GCHandle.ToIntPtr(callbackHandle)); }
    finally { NugetMarshal.Dispose(idsHandle); }
    // await unchanged
}
```

The per-element projection is `collectionCreateArgument(name, type, depth, csharpType)`, already
`internal` and top-level (`forward/ForwardCirCollectionComponents.kt:30`), so `cir/` can call it.
Its `csharpType` mapper argument is not: `BridgeType.csharpType()` is a `private` member duplicated
in `ForwardCirPlanProjection.kt:1377` and `ForwardCirPropertyProjection.kt:640`. A third consumer
means extracting it once rather than adding a third copy. **Verified in source.**

### Answers to the four mechanism questions

**1. Who owns the handle, and when is it released?** Nobody holds it past a single native call. C#
allocates immediately before the call and disposes in a `finally` immediately after it returns;
Kotlin has already copied out of it by then, because the copy happens before `launch`. This is the
sync route's ownership model unchanged, moved one lexical level inwards (into the collect delegate,
the value lambda, the write lambda, or around the async native call).

**2. Cancellation and exception paths.** None of them touch the handle, which is the point of
choosing alternative 1. Enumerated so the claim is checkable, all **verified in source**:

| Path | Site | Handle state |
| --- | --- | --- |
| `_collect` `catch (CancellationException)` then rethrow | `ClassExports.kt:527-529` | already disposed; runs inside `launch` |
| `_collect` `catch (Throwable)` then `onError` | `ClassExports.kt:530-533` | already disposed |
| `_collect` normal `onComplete` | `ClassExports.kt:526` | already disposed |
| `_async` `catch (CancellationException)` then `fn.invoke(..., 1, ...)` | `SuspendFunctionExports.kt:106-108` | already disposed |
| `_async` `catch (Throwable)` then `fn.invoke(null, errRef, ...)` | `SuspendFunctionExports.kt:109-113` | already disposed |
| C# `NugetJobNative.Cancel` after `DisposeAsync`, delivered late | `CirFlowRenderer.kt:311-317` | already disposed; the two-party rendezvous is unchanged and unaffected |
| Kotlin throws before the native call returns | any | `finally` disposes |
| Eager lowering throws | new arm above | C#'s `finally` disposes; Kotlin reports through `onError`/`fn` |

The one path that *would* have been hard, the late cancellation callback that forced the
`Interlocked` two-party release, is untouched: it fires long after the handle is gone.

**3. Does `_value` need the handle to live for the flow's lifetime?** No. `_value` is a synchronous
export that calls the member and returns (`ClassExports.kt:312-334`), so its handle is call-scoped
like every other. What *is* different is that the C# `() => Native_XValue(...)` lambda is re-invoked
on every `.Value` read (`CirFlowRenderer.kt:293-296`, verified), so **each read re-marshals the whole
collection**: allocate a wire container, box N elements through `nuget_wrap_*`, call, dispose. That
is O(n) per read where the parameterless case is O(1).

`_value` is therefore mechanically *easier* than `_collect`, not harder, and is not priced or scoped
separately. Two consequences to state in the docs rather than engineer around:

- Reading `.Value` in a tight loop on a StateFlow-returning method with a collection parameter is
  linear in the collection per read.
- The lambda captures the caller's `IReadOnlyList<T>` by reference, so if the caller mutates its own
  list after the call, later `.Value` reads marshal the mutated contents. The `_collect` path took a
  snapshot at subscription time. This divergence exists today for every captured parameter
  (`CirFlowRenderer.kt:293-296` re-reads all of them); collections just make it observable.

**4. The helper gate.** This is a correctness prerequisite: without it C# calls `nuget_list_create`
and `nuget_wrap_*` against a native library that never exported them. `needsListSupport`,
`needsMapSupport` and `needsSetSupport` are each a disjunction of declaration scans plus
`plannedCollectionKinds()` (`NugetProcessor.kt:1223-1227`, `:1250-1253`, `:1276-1279`), and
`needsCollectionParamWrap` reads `callableCatalog.plans` and `propertyPlans` only (`:1334-1348`).
**Verified in source.**

The gate learns about a legacy-route collection parameter by adding one more disjunct to each,
computed from the same scan that drives the emitters, along the lines of:

```kotlin
// Every collection kind at a parameter position on a Flow/StateFlow-returning or suspend member,
// on a class, object, companion, or at top level. These members carry no ForwardCallablePlan, so
// plannedCollectionKinds() cannot see them.
fun legacyRouteCollectionKinds(): Sequence<CollectionKind>
```

feeding all four gates: the three `needs*Support` disjunctions and `needsCollectionParamWrap` (the
`nuget_wrap_*` gate, since these are *write*-side collections). Nesting must recurse, for the reason
`NugetProcessor.kt:1196-1207` already gives: a `Set<List<String>>` parameter calls
`nuget_list_create` one level down, and reading only the outer kind leaves those exports unemitted.

The scan needs a `BridgeType`, which means the legacy routes need the classifier. It already exists
in the same function: `forwardClassifier` is constructed at `NugetProcessor.kt:716` and
`ForwardBridgeTypeClassifier.classify(type)` takes a bare `KSType`
(`forward/ForwardBridgeTypeClassifier.kt:44-57`). **Verified in source.** Passing that one instance
into the export builders and the translator is the plumbing this ADR adds; it is not a new
classification pass.

### Files touched

Read in source while writing this ADR:

| File | Change |
| --- | --- |
| `exports/ClassExports.kt` | `addFlowParameters` declares a collection param `COpaquePointer`; `buildFlowMethodCollectBody` hoists the lowering above `launch` and gains the error arm; `buildStateFlowValueMethodBody` / `buildStateFlowHasValueMethodBody` / `buildStateFlowSetValueMethodBody` substitute the lowered expression inline (these take `paramCall` as a pre-joined string today, so the signature changes to carry lowered arguments) |
| `exports/SuspendFunctionExports.kt` | same for `addSuspendClassMethodExports` (`:76-81`) and `buildSuspendMethodBody`; and for the top-level `addSuspendFunctionExports` (`:26-45`), which must stop using the shared `addParameters` for a collection param |
| `exports/Helpers.kt` | one shared "legacy route parameter" declaration helper, so the four builders cannot drift |
| `cir/CirClassTranslator.kt` | `methodParams` in `flowMembers` (`:788-792`), `asyncMembers` and `suspendStateFlowMembers` (`:718-722`) set `type` to the collection's C# spelling and `nativeType = "IntPtr"` |
| `cir/CirFunctionTranslator.kt` | the same at `:84` and `:619` for top-level suspend functions |
| `cir/CirFlowRenderer.kt` | `renderFlowMethod` (`:274-287`) and `renderStateFlowMethod` (`:299-333`) wrap each native call in create/`finally`-dispose: collect delegate, value lambda, `_has_value` probe, `_set_value` write lambda |
| `cir/CirConcurrencyRenderer.kt` | `renderAsyncMethod` (`:57-`) wraps the synchronous native call in create/`finally`-dispose |
| `NugetProcessor.kt` | the four helper gates gain the legacy-route disjunct; `forwardClassifier` (`:716`) is threaded into the export builders and the translator |
| `forward/ForwardCirPlanProjection.kt` and `forward/ForwardCirPropertyProjection.kt` | extract the duplicated private `BridgeType.csharpType()` (`:1377`, `:640`) so `cir/` can pass it to `collectionCreateArgument` |

Guessing rather than reading, flagged as the coordinator asked:

| File | Why I am unsure |
| --- | --- |
| `cir/CirModel.kt` | `CirParameter` already has `type` / `nativeType`, which may be enough. If the renderer needs the *element* shape to emit the `Select(...)` projection, it needs one more field (a nullable `BridgeType`, or a pre-rendered create-expression string). I read the model but not every renderer consumer of `nativeType`, so I cannot say which |
| `cir/CirClassRenderer.kt` | `renderDllImport` builds the extern parameter list; I read `:490-520` but did not confirm it reads `nativeType` rather than `type` for every route. If it reads `type`, this file changes too |
| `cir/CirNativeImports.kt` | filters out `isAsync`/`isFlow` methods at `:14` and `:107`, so flow and async `DllImport`s are built in the translator instead. I did not trace whether anything else re-derives their parameter types |
| `IntegrationTests/*.csproj` and the fixture wiring | new fixture, no design content |

### Expected consumer-side C# API, for the Step 3 failing tests

Fixture (new, `test-library/src/nativeMain/kotlin/.../cat/`), covering the two shapes from issue #109
plus the `_value` re-read and a `Set` on the suspend route:

```kotlin
class TreatBoard {
  private val _served: MutableStateFlow<List<String>> = MutableStateFlow(listOf("biscuit"))

  /** Issue #109: a List parameter on a StateFlow-returning member. */
  fun served(kinds: List<String> = listOf("biscuit")): StateFlow<List<String>> =
    MutableStateFlow(kinds.map { "$it x2" })

  /** Issue #109: a Set parameter on a suspend member. */
  suspend fun forget(ids: Set<String>): Int = ids.size

  /** A Flow (not StateFlow) return with a collection parameter. */
  fun servings(kinds: List<String>): Flow<String> = kinds.asFlow()

  /** Control: no collection parameter, must be unaffected. */
  fun servedAll(): StateFlow<List<String>> = _served
}
```

Expected C# surface, which is what the tests assert:

```csharp
using var board = new TreatBoard();

// 1. The public parameter is the collection, never IntPtr.
KotlinStateFlow<IReadOnlyList<string>> served = board.Served(["biscuit", "milo"]);
Assert.Equal(["biscuit x2", "milo x2"], served.Value);

// 2. `.Value` re-reads through the same parameters, so it stays correct on repeat access.
Assert.Equal(["biscuit x2", "milo x2"], served.Value);

// 3. The suspend route round-trips a Set.
Assert.Equal(2, await board.ForgetAsync(new HashSet<string> { "a", "b" }));

// 4. The Flow route collects with a collection parameter.
var seen = new List<string>();
await foreach (var s in board.Servings(["one", "two"])) seen.Add(s);
Assert.Equal(["one", "two"], seen);

// 5. Empty collection is not a special case.
Assert.Empty(board.Served([]).Value);

// 6. Exception safety, mirroring CollectionParameterCleanupTests: hammering a throwing
//    suspend/flow member with a collection argument must stay stable (no handle leak, no
//    use-after-free). 50 iterations, assert the mapped exception each time.
```

The signature assertion matters as much as the behaviour one: before this change `Served` takes
`IntPtr` (or the package does not build at all), so a test that merely calls `board.Served(list)`
fails to compile at the red step, which is the intended red.

### Scope

Supported in v1:

- `List`, `MutableList`, `Set`, `MutableSet`, `Map`, `MutableMap` at a parameter position on a
  `Flow`-returning, `StateFlow`-returning, `MutableStateFlow`-returning or `suspend` member, on a
  class, an `object`, a companion, and at top level.
- Components: whatever `isBridgeableComponent()` already admits for the sync route (ADR-097 enums,
  ADR-098 narrow primitives and `Char`, ADR-099 nested collections, value classes). No new component
  work; the classifier and `collectionCreateArgument` are reused as-is.

Refused with the alternative-4 fallback (`SKIPPED_UNSUPPORTED_INPUT`, existing kind,
`forward/ForwardDiagnostic.kt:62`, verb `Skipping` derived from the prefix at `:262-271`, rendered by
`format()` at `:294-301`, all **verified in source**):

- A collection whose component the classifier refuses.
- Any *other* generic-typed parameter on these routes: `Pair<A, B>`, `Array<T>`, a lambda parameter
  on a *flow-returning* method (`ClassExports.kt:223-227` partitions lambda-parameter methods out of
  the non-flow methods only, so a flow-returning method with a lambda parameter reaches
  `addFlowParameters` today), a user generic. These produce the same non-compiling Kotlin as
  issue #109 and must not be left emitting it. The gate is therefore "type has type arguments":
  marshal it if it is a supported collection, refuse it by name otherwise.

Message shape for the fallback:

```
w: [nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping TreatBoard.pair(p: Pair<String, Int>): a Flow-returning member can marshal a collection parameter, but not this generic type. Pass the values as a List/Set/Map, or as separate parameters.
    at /path/to/TreatBoard.kt:14
```

Deferred:

- Nullable collection parameters (`List<T>?`) on these routes. The sync route handles them with an
  `IntPtr.Zero` guard (`ForwardCirPlanProjection.kt:681-686`); nullable threading on the legacy flow
  routes is ADR-067 territory and is deliberately not widened here.
- The non-generic sibling defect: an *object*-typed parameter on these routes renders `IntPtr` in C#
  with no way for a caller to produce one. It compiles today, so it is not issue #109, and it should
  become a ROADMAP item.
- `docs/backlog/legacy-top-level-two-call-route-staticlegacytwocall.md` (ROADMAP line 67), a sibling
  of the same defect class on a *plan-driven* route that fails on the C# half with CS0103. Different
  route, different fix, not closed here.

## Consequences

- `packNuget` stops failing outright on a module that declares issue #109's shape, and the member
  binds with a real C# signature instead of `IntPtr`.
- Nothing existing changes shape: **verified** that no fixture in `test-library/src` declares a
  `suspend` or `Flow`-returning member with a generic-typed parameter (`grep` over every `suspend fun`
  with parameters and every `Flow`/`StateFlow`-returning function returns nothing), so there is no
  migration and no ABI churn for existing exports.
- Two legacy emitters gain a dependency on `ForwardBridgeTypeClassifier` and on the collection
  lowering/projection helpers. That is a deliberate, bounded step toward the plan, not a second
  implementation of it: no new `BridgeType`, no new wire shape, no new helper export.
- A collection argument on a StateFlow-returning method costs a full re-marshal per `.Value` read,
  and the C# lambda captures the caller's list by reference. Both need a line in the Writerside docs.

### Inferred claims

Everything not listed here is either verified by source reading (file and line inline) or by the
`kotlinc-native` spike quoted above.

1. **A single-pointer-field `kref` struct is register-compatible with `IntPtr`.** Load-bearing only
   for the claim that the spelling-only fix fails silently. If wrong, it fails loudly and the
   decision is unchanged.
2. **`StableRef.create(scope.launch { }).asCPointer()` is an acceptable stand-in job** for the C#
   subscription to cancel and dispose on the eager-lowering error arm. Not spiked. If wrong, the
   fallback is to hoist without a `try` and accept a process abort on what can only be a generator
   bug, matching every other legacy export body.
3. **`CirParameter`'s existing `type`/`nativeType` split is sufficient** to carry the collection
   parameter itself (now verified: `renderDllImport` reads `nativeType`). What stays inferred is
   whether the *renderer* can build the `NugetMarshal.CreateList(...)` projection from what
   `CirParameter` already holds, or needs the component `BridgeType` added as a field. If it needs
   it, `cir/CirModel.kt` gains one field and the file list grows by one; nothing about the ownership
   model changes.
4. **The helper-gate disjunct as specified is sufficient.** I verified what the four gates read and
   that the legacy routes are invisible to all four. I did not verify that no *fifth* gate exists
   downstream (for example inside `CirTranslator`'s core-marshal condition) that also needs the new
   disjunct. If one does, the symptom is a missing `nuget_*` export at load time, which the ADR-054
   registration observability makes diagnosable. **This is the one inferred claim that could waste an
   implementer's afternoon**: if collection helpers are silently absent, the failure surfaces as an
   `EntryPointNotFoundException` at first call, not at build time. Check every `needs*` computation
   in `NugetProcessor.process()` before assuming four is all of them.

## Post-implementation notes

Shipped as designed (`4dcbc0e`). Nine files touched, four of the eight guessed (`CirModel.kt`,
`CirClassRenderer.kt`, `CirNativeImports.kt`, the fixture wiring): three new (`forward/ForwardLegacyRouteCollections.kt`,
`forward/ForwardCsharpTypes.kt`, `cir/CirCollectionParameters.kt`), `CirParameter` gained exactly
one field (`collectionCreate`), matching the ADR's predicted worse case for inferred claim 3.

### Correct the gate count

Inferred claim 4 asked whether a fifth `needs*` gate existed beyond the four disjuncts this ADR
adds. It does, and it is **not** one of the four `process()` gates this ADR names:
`CollectionHelperTracker.needsList`/`needsMap`/`needsSet` (`cir/CirTypeMapping.kt:86-88`), fed only
by `trackPlan`/`trackProperty` and consumed by `cir/CirTranslator.kt` to decide whether
`NugetMarshal`'s collection half and `NugetListNative`/`NugetSetNative`/`NugetMapNative` are
emitted at all. Legacy-route members carry no plan, so it was blind to them exactly like the three
`needs*Support` gates in `process()`. Extended via `tracker.trackCollection(shape.type)` in
`legacyRouteParameters`.

So the correct count is **three plus the tracker, not four**: `needsListSupport` (`:1304`),
`needsMapSupport` (`:1334`), `needsSetSupport` (`:1363`) were extended; `needsCollectionParamWrap`
(`:1433`) needed nothing, since `nuget_wrap_*` already ships under `needsCoreMarshal`. Had the
tracker been missed, the failure mode would have been a C# compile error on the generated
bindings, not a silent runtime `EntryPointNotFoundException`, the same class of miss as the four
gates this ADR did anticipate.

### Two deliberate deviations from the Decision above

1. **No try/catch error arm around the eager lowering**, contrary to the sketch in the Kotlin half.
   Inferred claim 2's fallback was taken instead: hoist with no `try`, accept a process abort on a
   generator bug, matching every other legacy export body. It can only fire on a generator bug (a
   component cast disagreeing with what C# boxed), never on user data, and the alternative would
   have introduced an unspiked stand-in-job contract on the C# subscription path for zero coverage.
2. **Only one of the two duplicated `csharpType()` copies was extracted**, not both as sketched.
   `ForwardCirPropertyProjection.kt:640`'s private copy is untouched (different error message, no
   third consumer at the time). Two copies remain instead of three collapsing to one; see
   [ADR-113](113-interface-declaration-on-the-forward-plan.md)'s "two divergent public-C# type
   spellers" ROADMAP item, which this is one half of.

Nothing else in this ADR was disproved.

### Fixture deviation

The fixture uses `fun served(kinds: List<String>): StateFlow<String>`, not
`StateFlow<List<String>>` as sketched in the Decision's example. Nothing in `test-library` declares
a collection as a Flow/StateFlow **element** (zero grep hits for that shape), so returning one
would have been an untested seam that could mask the parameter signal. That is a genuine coverage
gap on its own; see ROADMAP.md. The parameter side is otherwise unchanged from the sketch.

### The top-level suspend route was in scope

`TreatRoutes.kt`'s top-level `forgetAllTreats(ids: Set<String>): Int` was added beyond the
three-route brief because it **compiles today** through `addParameters`/`toBridgeTypeName`, which
already preserves type arguments, and hands C# a public `IntPtr ids` parameter no caller can
produce. A fix touching only the class-method Flow/suspend routes would have left a live `IntPtr`
in the public API with a fully green build. Owner-approved to keep in scope.

### `_has_value` / `_set_value` were touched, deliberately left uncovered

Both halves share the parameter/call-args plumbing added here (Kotlin's `paramPrelude`, no hoist
needed since both are synchronous; C#'s `renderStateFlowMethod` gives `_has_value` its own
call-scoped handle and `_set_value` a hand-written variant, since `out IntPtr error` must be
declared outside the `try` to survive the dispose). No fixture reaches either arm: both need a
nullable or `MutableStateFlow` return **plus** a collection parameter, a combination nothing in
`test-library` declares. Cold on purpose, not untested by oversight; see ROADMAP.md.

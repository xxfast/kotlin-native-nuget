# ADR-128: Forward, `launchForCSharp` / `collectForCSharp`: two non-inline runtime helpers own the coroutine launch shape the suspend and Flow exports repeat

## Status

Proposed

## Context

Every forward export that starts a coroutine for C# is the same ~20 lines: reinterpret the
callback pointer(s), `launch(start = CoroutineStart.ATOMIC)`, `try` the body, mint the result
handle, invoke the callback, a `CancellationException` arm that signals `cancelled = 1` and
rethrows, a `Throwable` arm that mints a `NugetError` handle, and `return
NugetHandles.retain(job)`. ADR-127 moved the fixed `nuget_*` block into the `nuget-runtime` klib
and made `NugetHandles` and `buildError` public behind `@NugetRuntimeApi`, which is what makes a
shared helper possible. Phase 14 bullet 4 (ROADMAP.md) asks for that helper.

This is a generated-code shape change plus one new public runtime surface. It is **not** a
consumer-visible change: the 66 `nuget_*` C names, every per-declaration export's C signature and
the whole of `Interop.cs` stay byte-identical. The callback wire protocol
(`result, error, cancelled, userData`; `onNext(item, cancelled, userData)`, `onComplete(userData)`,
`onError(error, userData)`) is unchanged.

Constraints inherited from ADR-127: every public runtime declaration carries `@NugetRuntimeApi`;
the runtime adds **no** new `@CName` export for this; the runtime's Kotlin surface is for the
generator of the same version.

### The launch sites, from source (verified by reading, 2026-09-12)

The ROADMAP counts "14 copies in the fixture", but what matters is the number of *emitter
templates*. There are four in the processor and five more in the runtime itself:

| Template | File:line | Variant | What differs from the others |
|---|---|---|---|
| `buildSuspendFunctionBody` | `nuget-processor/.../exports/SuspendFunctionExports.kt:177` | suspend result, top-level function | ad-hoc `CoroutineScope(Dispatchers.Default)` (no `scopeHandle`); calls `funcName(params)`; result arms: Unit → `(null, null, 0)`, nullable → null-guarded `NugetHandles.retain`, collection → `legacyBoxedResult` projection, else bare `result` |
| `buildSuspendMethodBody` | `SuspendFunctionExports.kt:215` | suspend result, class method and sealed arm (ADR-118 `prefix`) | `scope = scopeHandle.asStableRef<CoroutineScope>().get()`; `obj.method(params)`; same result arms |
| `buildFlowCollectBody` | `nuget-processor/.../exports/FlowExports.kt:474` | Flow/StateFlow property, three callbacks | `obj.prop?.collect` when the member is nullable (`memberAccessor`); item box is one of `itemBoxExpr`'s three arms (nullable element, collection projection, bare `value as Any`) |
| `buildFlowMethodCollectBody` | `FlowExports.kt:519` | Flow-returning method | `obj.method(params)?.collect`; ADR-114 `paramPrelude` before `launch` |
| `export_nuget_suspend_func{0,1,2,3}_invoke` | `nuget-runtime/.../NugetRuntime.kt:322,354,388,424` | suspend lambda invoke (ADR-036) | ad-hoc scope; `if (result == Unit)` test instead of a static Unit decision |
| `export_nuget_stateflow_collect` | `NugetRuntime.kt:666` | StateFlow handle collect | `flow.collect` on a `StateFlow<*>` handle, bare `value as Any` box |

Two things the ROADMAP bullet lists that are **not** this shape:

- `nuget_scope_drain` (`NugetRuntime.kt:477`) launches with `ATOMIC` but has no `try`/`catch`: a
  cancelled drain never signals `cancelled = 1`. Folding it onto the helper would change that
  behaviour. It stays as it is.
- `*_bridge_create` (`nuget-processor/.../exports/InterfaceBridgeFactoryExports.kt:40-66`) does not
  launch anything. It is the synchronous `try { ... NugetHandles.retain(bridge) } catch (e:
  Throwable) { errorOut.pointed.value = NugetHandles.retain(buildError(e)); null }` shape, the same
  one every ordinary export uses. The ROADMAP mention is wrong; a helper for the `errorOut` shape
  would be a separate decision.

What is byte-identical across all nine sites, and what the helper must preserve exactly:

- `launch(start = CoroutineStart.ATOMIC)`.
- The result callback's C type is always
  `(COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit`; the Flow trio is always
  `(COpaquePointer?, Byte, COpaquePointer) -> Unit`, `(COpaquePointer) -> Unit`,
  `(COpaquePointer?, COpaquePointer) -> Unit`.
- `catch (e: CancellationException) { fn.invoke(null, null, 1.toByte(), userData); throw e }`
  (Flow: `onNext.invoke(null, 1.toByte(), userData); throw e`).
- `catch (e: Throwable) { val errRef = NugetHandles.retain(buildError(e)); fn.invoke(null,
  errRef, 0.toByte(), userData) }` (Flow: `onError.invoke(errRef, userData)`).
- The job handle is minted with `NugetHandles.retain(job)` (never bare `StableRef.create`), so it
  counts in `nuget_live_handles` (ADR-120). Result and error handles are minted with
  `NugetHandles.retain` *inside* the coroutine, after the body and before the callback.

What varies: which scope; the body producing the value; how the value becomes a handle (Unit,
nullable, projection); and, for Flow, how the source is reached (`?.`).

## Alternatives Considered

### 1. Two non-inline, non-generic helpers; the body returns an already-minted handle (chosen)

```kotlin
@NugetRuntimeApi
public fun launchForCSharp(
  scope: CoroutineScope,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
  body: suspend () -> COpaquePointer?,
): COpaquePointer

@NugetRuntimeApi
public fun collectForCSharp(
  scope: CoroutineScope,
  onNextPtr: COpaquePointer,
  onCompletePtr: COpaquePointer,
  onErrorPtr: COpaquePointer,
  userData: COpaquePointer,
  body: suspend (emit: (COpaquePointer?) -> Unit) -> Unit,
): COpaquePointer
```

The helper owns `reinterpret`, `launch(ATOMIC)`, the three arms and `NugetHandles.retain(job)`.
The generated body owns the call and the marshalling of its value into a handle (or `null`).

Pros: each variant's marshalling stays exactly where it is today, in generated text, so the
result-arm logic (`resultRefExpression`, `legacyBoxedResult`, `itemBoxExpr`) does not move and
the mint order is unchanged; the helpers are not generic over the callback type because that type
is fixed per helper; the runtime's own five sites can use them; the generated file drops
`reinterpret`, `CFunction`, `invoke`, `launch`, `CoroutineStart` and `CancellationException`
from these routes. Cons: two helpers rather than one; the Flow body still spells `.collect`.

### 2. One generic helper `launchForCSharp(scope, callbackPtr, userData, onResult, onCancel, onError) { body }` (rejected)

Passing the three arms as lambdas lets one function serve both shapes. Rejected: the arms are
byte-identical across every site and are the very thing the helper exists to own; a per-site
lambda for each arm is the current copy with extra indirection, and it invites drift (a site
that forgets `throw e` in the cancel arm). Two fixed helpers are narrower.

### 3. `collectForCSharp(scope, ..., flow: Flow<T>?, box: (T) -> COpaquePointer?)` (rejected)

Cleaner generated text (`collectForCSharp(scope, ..., obj.temperature) { NugetHandles.retain(it as
Any) }`), but the Flow argument is evaluated by the export on the caller's thread, **before**
`launch`. Today `obj.method(params)` and `obj.prop` are evaluated inside the coroutine: a
Flow-returning method that throws reaches C# as `onError`; with this signature it would escape a
`@CName` export as an uncaught Kotlin exception, and a method with call-time side effects would
run on the P/Invoke thread instead of the scope's dispatcher. A `source: suspend () -> Flow<T>?`
producer fixes that but adds a type parameter and a second lambda for no gain over alternative 1.

### 4. `inline` helpers (rejected)

An inline body is compiled into the consumer's `CNameExports.kt` at the consumer's compile time,
so a runtime bump can never change it, which defeats ADR-127's reason for versioning the runtime
(inferred from the Kotlin inline-function docs; not spiked). Inline would also require every
declaration the body touches to be public, which they already are, so it buys nothing. Non-inline
means the lambda is an ordinary Kotlin object crossing a klib boundary; `StableRef`/`CFunction`
calls inside it are ordinary Kotlin/Native code and need no special treatment (inferred).

### 5. Scope-less `launchForCSharp(callback, userData) { body }` as the ROADMAP spells it (rejected)

The top-level route launches on an ad-hoc `CoroutineScope(Dispatchers.Default)`; the method
route launches on the C#-owned scope from `scopeHandle`. Both must stay as they are (the method
route's scope is what `nuget_scope_cancel` cancels), so the scope is a parameter. A
`CoroutineScope.launchForCSharp(...)` extension is the same decision with different spelling;
a plain parameter is chosen so the top-level route's ad-hoc scope is visible at the call site.

## Decision

### PR-D: the runtime gains the two helpers

Shipped in a new sibling file `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetLaunch.kt`
rather than appended to `NugetRuntime.kt` (same package, same source set, so nothing about the
klib surface changes; `NugetRuntime.kt` stays the fixed `@CName` block and the helpers, which
carry no `@CName`, read as what they are). Both `@NugetRuntimeApi`, both `public`, neither
`inline`, neither carrying `@CName`:

```kotlin
@NugetRuntimeApi
public fun launchForCSharp(
  scope: CoroutineScope,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
  body: suspend () -> COpaquePointer?,
): COpaquePointer {
  val fn = callbackPtr.reinterpret<CFunction<
    (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val job = scope.launch(start = CoroutineStart.ATOMIC) {
    try {
      val resultRef: COpaquePointer? = body()
      fn.invoke(resultRef, null, 0.toByte(), userData)
    } catch (e: CancellationException) {
      fn.invoke(null, null, 1.toByte(), userData)
      throw e
    } catch (e: Throwable) {
      val errRef = NugetHandles.retain(buildError(e))
      fn.invoke(null, errRef, 0.toByte(), userData)
    }
  }
  return NugetHandles.retain(job)
}

@NugetRuntimeApi
public fun collectForCSharp(
  scope: CoroutineScope,
  onNextPtr: COpaquePointer,
  onCompletePtr: COpaquePointer,
  onErrorPtr: COpaquePointer,
  userData: COpaquePointer,
  body: suspend (emit: (COpaquePointer?) -> Unit) -> Unit,
): COpaquePointer {
  val onNext = onNextPtr.reinterpret<CFunction<(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val onComplete = onCompletePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()
  val onError = onErrorPtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> Unit>>()
  val job = scope.launch(start = CoroutineStart.ATOMIC) {
    try {
      body { itemRef -> onNext.invoke(itemRef, 0.toByte(), userData) }
      onComplete.invoke(userData)
    } catch (e: CancellationException) {
      onNext.invoke(null, 1.toByte(), userData)
      throw e
    } catch (e: Throwable) {
      val errRef = NugetHandles.retain(buildError(e))
      onError.invoke(errRef, userData)
    }
  }
  return NugetHandles.retain(job)
}
```

Wire-equivalence with today's generated text, arm by arm (verified by reading the four emitter
templates and the five runtime sites listed above; **not** verified by running, no build could be
run while this ADR was written):

- Success: today `val resultRef = <expr>; fn.invoke(resultRef, null, 0, userData)`; the Unit
  route calls `fn.invoke(null, null, 0, userData)`, and the nullable route's null case yields
  `resultRef = null`, so both are the body returning `null`. One callback invocation, same args.
- Cancel and error arms: textually identical to today's.
- Mint order: the result handle is minted inside the body (inside the coroutine, inside the
  `try`), the error handle in the error arm, the job handle after `launch` returns. Same three
  `NugetHandles.retain` calls in the same order as today, so `LeakTests`' deltas and
  `nuget_live_handles` baselines cannot move. Nothing is minted with bare `StableRef.create`.
- A body that throws after minting its result handle leaks that handle, exactly as today's text
  would (the `retain` is the last statement before `fn.invoke`; nothing runs between them).
- `ATOMIC` is preserved, so a scope cancelled between the export returning and the coroutine
  starting still runs the body far enough to hit the cancel arm and signal `cancelled = 1`.

PR-D also migrates the runtime's own five sites (`nuget_suspend_func{0..3}_invoke`,
`nuget_stateflow_collect`) onto the helpers. That is the dogfooding seam: `IntegrationTests` and
`LeakTests` exercise the helpers through the C ABI before PR-E exists, with no C#-side change.
`nuget_scope_drain` is left alone (see Context).

`NugetRuntimeAbi1` stays: an added function is a compatible runtime change (an older generator
against a newer runtime still compiles; a newer generator against an older runtime fails the
consumer compile with an unresolved `launchForCSharp`, which is the same early failure the anchor
exists for).

### PR-E: the generator emits the helpers

`buildSuspendFunctionBody` becomes:

```kotlin
<paramPrelude>
return launchForCSharp(CoroutineScope(Dispatchers.Default), callbackPtr, userData) {
  val result = funcName(<paramCall>)          // Unit route: just `funcName(<paramCall>)` then `null`
  <resultRefExpression>                        // e.g. `if (result == null) null else NugetHandles.retain(result)`
}
```

`buildSuspendMethodBody` keeps its `obj`/`scope` prelude locals and passes `scope`.
`buildFlowCollectBody` / `buildFlowMethodCollectBody` become:

```kotlin
val obj = handle.asStableRef<Q>().get()
val scope = scopeHandle.asStableRef<CoroutineScope>().get()
<paramPrelude>
return collectForCSharp(scope, onNextPtr, onCompletePtr, onErrorPtr, userData) { emit ->
  obj.<memberAccessor>.collect { value -> emit(<itemBoxExpr>) }
}
```

The ADR-114/ADR-122 rule that a prelude local is evaluated **before** the launch is unchanged: the
prelude precedes the helper call, and the helper launches before it returns. The Tier 1 ordering
assertion moves from `scope.launch` to the helper call (below).

Imports at `NugetProcessor.kt:1505-1523`: the suspend/Flow block drops `reinterpret`, `launch`,
`CoroutineStart` and `CancellationException`, and the generated file imports the two helpers from
`io.github.xxfast.kotlin.native.nuget.runtime`. One hazard, verified by reading: the callback-route
import block at `:1505` is gated `needsCallbackImports && !hasSuspendFunctions &&
!needsFlowImports`, i.e. it relies on the suspend block to supply `CFunction`, `invoke` and
`COpaquePointer`. PR-E must either keep those three in the suspend block or drop the
`!hasSuspendFunctions && !needsFlowImports` guard, otherwise a file with both a suspend function
and a lambda-parameter method loses its `CFunction` import silently. Leaving an unused import
behind is a warning only (no `allWarningsAsErrors`/`-Werror` anywhere in `test-library/build.gradle.kts`,
`nuget-runtime/build.gradle.kts`, the root build, `gradle.properties` or `nuget-plugin/src`, which
is what configures the consumer's compilations; verified by grep), so the safe order is: drop the
guard first, trim imports second. Outside the four emitter templates and the import block, no
test, resource or C# file pins `scope.launch`, `CoroutineStart` or the cancel arm as generated
text (repo-wide grep excluding `build/`; the remaining hits are comments and fixture-author code).

Processor files PR-E touches: `exports/SuspendFunctionExports.kt` (two templates),
`exports/FlowExports.kt` (two templates), `NugetProcessor.kt` (import block). `_bridge_create`,
`_value`, and every ordinary route are untouched.

## Test plan

1. **PR-D, runtime `nativeTest` (recommended; inferred, not run).** `nuget-runtime` gains a
   `nativeTest` source set (`dependsOn(commonTest)`, `implementation(libs.kotlin.test)`, which the
   catalog already has, plus the four `<target>Test` `dependsOn`s mirroring `nativeMain`). KGP
   creates a `<target>Test` task per native target and disables the ones the host cannot run, and
   `allTests` runs the enabled ones, so `./gradlew :nuget-runtime:allTests` runs `mingwX64Test` on
   Windows and `macosArm64Test` on an Apple Silicon Mac (inferred from the KMP docs on native
   test tasks; nobody has run it in this repo). One test class drives each helper through a
   `staticCFunction` callback: a `staticCFunction` cannot capture, so it records through `userData`,
   a `StableRef` to a small holder the test reads back. `CPointer<CFunction<F>>` is a
   `COpaquePointer` (`CFunction<F> : CPointed`), so the pointer passes to the helper's
   `callbackPtr` without a cast (inferred from `kotlinx.cinterop` declarations). Three arms each:
   result (a body returning a minted handle; the holder sees it and `cancelled == 0`), cancel
   (`awaitCancellation()` then `asStableRef<Job>().get().cancel()`; the holder sees `cancelled ==
   1`, nulls, and the rethrow leaves the job cancelled), error (a throwing body; the holder's error
   handle unwraps to a `NugetError` whose `type` names the exception). Every arm asserts the
   `NugetHandles.live` delta (job + result or error) and releases what it minted, which is the
   ADR-120 baseline in-process. Cost: one test-binary link per host per run, roughly the same as
   one `sharedLib` link. Wiring: `scripts/verify.sh` runs `./gradlew :nuget-runtime:allTests`
   before `packNuget`; `ci.yml`'s matrix step adds it beside `:nuget-processor:koverXmlReport`, so
   the Windows leg is where `mingwX64Test` first runs.
2. **PR-D, outer proof.** `IntegrationTests` and `LeakTests` unchanged: the migrated
   `nuget_suspend_func*_invoke` and `nuget_stateflow_collect` are covered by the existing suspend
   lambda (ADR-036) and StateFlow (ADR-065) cells. `scripts/verify-runtime-exports.sh` still sees
   exactly 66 names.
3. **PR-D, Tier 1 stub.** `Tier1RuntimeStub` gains the two helper signatures as a **third stub
   file**, appended by `Tier1Harness.compileGenerated` only when `coroutinesOnCompileClasspath`
   is true. Verified by reading: the harness adds `Tier1RuntimeStub.files` unconditionally and
   `Tier1CoroutineFreeModuleTest` compiles with the flag `false`, so a stub that names
   `CoroutineScope` in the existing file would break that test. Bodies are `TODO()`.
4. **PR-E, Tier 1.** `Tier1LegacyRouteHandleParameterTest:285` pins `body.indexOf("scope.launch")`
   for its before-launch ordering assertion; it pins `collectForCSharp(` instead (same
   assertion). The other six files that mention the routes (`Tier1EntryPointCollisionTest`,
   `Tier1LegacyRouteCollectionParameterTest`, `Tier1LegacySuspendCollectionReturnTest`,
   `Tier1SealedArmRefusedSuspendTest`, `Tier1SuspendMethodOverloadTest`,
   `Tier1SuspendOnlyFileTest`) pin `_async` C names and C# `EntryPoint`s only and are unaffected
   (verified by grep). A new structural assertion: no generated file contains
   `CoroutineStart.ATOMIC` or `catch (e: CancellationException)`. ADR-055's `ForwardAbiContract`
   is unaffected (C names unchanged).
5. **PR-E, outer proof.** `IntegrationTests` and `LeakTests` unchanged, including the
   cancellation cells and the leak baselines.

## Prior art

SKIE keeps its suspend/Flow bridging in a runtime module the generated Swift reaches through a
`Job`-backed handle, rather than emitting the launch plumbing per declaration (inferred from
[SKIE's suspend feature page](https://skie.touchlab.co/features/suspend) and the
[kotlin-swift-interopedia coroutines note](https://github.com/kotlin-hands-on/kotlin-swift-interopedia/blob/main/docs/coroutines/Suspend%20functions.md);
not read at source level). Same shape as this decision; it did not change it.

## Inferred claims, listed

None of the following was run; the project lock prevented any build while this ADR was written.

1. Non-inline lambdas crossing the klib boundary and calling `CFunction.invoke` / `StableRef`
   behave as ordinary Kotlin/Native code. If wrong, PR-D fails to compile or link, loudly.
2. A `CPointer<CFunction<F>>` from `staticCFunction` is assignable to `COpaquePointer`. If wrong,
   the `nativeTest` needs a `.reinterpret()`; the helper is unaffected.
3. KGP's `<target>Test`/`allTests` host gating and the `nativeTest` source set wiring. If wrong,
   `verify.sh` fails at the new step; nothing generated is affected.
4. **Load-bearing, silent if wrong:** the arm-by-arm wire-equivalence above, in particular that
   evaluating the Flow source inside the helper's coroutine (alternative 1's `suspend` body)
   preserves today's `onError` path for a throwing Flow-returning method. Checked by reading the
   emitter templates and the runtime sites only. If wrong, C# would see a different callback
   sequence, and only `IntegrationTests`' cancellation and error cells would catch it. Run those
   first when PR-D lands.

## Consequences

- Two new public runtime functions under `@NugetRuntimeApi`. No new `@CName`; 66 names remain.
- The four processor templates and five runtime sites collapse to a call each; the generated
  file loses six imports on these routes.
- `NugetRuntimeAbi1` unchanged: additive.
- Deferred: `nuget_scope_drain` (different semantics), a helper for the synchronous `errorOut`
  shape (`_bridge_create` and every ordinary route; a separate decision), and any change to the
  callback wire protocol.

### PR-D build results (2026-09-12)

Claims 1, 2 and 3 above are now run, not inferred: `:nuget-runtime:allTests` compiles, links and
runs `mingwX64Test` (the other three targets' test tasks are host-gated `SKIPPED`), the helper
lambdas call `CFunction.invoke`/`StableRef` across the klib boundary without special treatment,
and `staticCFunction`'s `CPointer<CFunction<F>>` passes to `callbackPtr: COpaquePointer` with no
cast. Seven tests, one per arm: launch result / null result / cancel / error, collect
items+complete / cancel / error, each asserting `NugetHandles.live` returns to its starting value
after the minted handles are released. One Kotlin/Native constraint worth recording: a backtick
test name may not contain a comma (`Name contains illegal characters: ","`), unlike on the JVM.

Claim 4 stays inferred for the generator (PR-E); PR-D's migration of the five runtime sites is the
part `IntegrationTests`/`LeakTests` cover today.

Two corrections to the counts above, from the same run: the linked library exports **67**
`nuget_*` names, not 66 — the text was written before ADR-129 added `nuget_runtime_version` — and
the four suspend sites plus `nuget_stateflow_collect` migrated with no change to any of them
(`verify.sh`: `OK: all 67 ...`, IntegrationTests 1723 passed, LeakTests 28 passed).

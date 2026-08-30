# ADR-102: AOT-safe forward callbacks — `[UnmanagedCallersOnly]` static thunks keyed off the echoed ctx pointer

## Status

Accepted

## Context

Every place the forward direction lets Kotlin call back into C# — per-call lambda parameters
(ADR-036), stored callbacks (ADR-037), C#-implemented interface bridges (ADR-039/084), the
`Flow`/`StateFlow` collection callbacks (ADR-026/065), and `suspend` continuation resumption
(ADR-019/020) — uses the same mechanism today: the generated C# creates a managed delegate
(usually a closure), pins it with `GCHandle.Alloc`, and hands Kotlin its address via
`Marshal.GetFunctionPointerForDelegate` (or, equivalently, a delegate-typed `DllImport` parameter,
which the marshaller converts the same way). That mechanism requires the runtime to build a
**native-to-managed thunk** for the delegate instance, and on JIT-less runtimes there is nothing to
build it with.

**Verified live** (ROADMAP "Tooling & Test Integrity", NYTimes-KMP sample): a Release-configuration
Mac Catalyst arm64 build (.NET 10 MAUI, plugin 0.2.0) is Mono full-AOT with no JIT fallback, and the
first collection of a generated `Flow` throws
`ExecutionEngineException: AOT NOT FOUND: (wrapper native-to-managed) KotlinFlowEnumerator...`.
Debug builds work because the JIT compiles the thunk at call time. iOS/tvOS run the same full-AOT
regime and are expected to fail identically (**inferred** — same Mono AOT mechanism, not re-run
individually). PeopleInSpace's LIMITATIONS item 2 is the second independent consumer report. The
verified consumer-side mitigation, `<MtouchInterpreter>-all</MtouchInterpreter>`, keeps the whole
callback surface on the Mono interpreter; this ADR's purpose is to delete the need for that flag.

**CoreCLR NativeAOT is a different story, measured this session (red step): all five callback
shapes PASS under `PublishAot=true` on win-x64 (ILC/.NET 10.0.9) with the current
`GetFunctionPointerForDelegate` mechanism unchanged** — the `AotSmokeTest` binary published with no
managed fallback (no `AotSmokeTest.dll` in the publish dir) and exited 0 on all five shapes. ILC
evidently pre-generates reverse-P/Invoke marshalling stubs for delegate types it can root
statically. That pre-generation is an ILC implementation detail, not a documented contract — the
documented NativeAOT contract for native→managed calls is `[UnmanagedCallersOnly]` — so this ADR
moves the generated code onto the contractual mechanism rather than relying on it, but the claim
"NativeAOT consumers stay broken today" is **falsified** for this configuration and must not be
repeated in docs. Caveats: one RID (win-x64), ILC trim/AOT warnings not audited, osx-arm64 and
Catalyst unmeasured on this lane.

The reverse direction already solved the same problem: ADR-041's generated registration shims (e.g.
`CatRegistration.cs` in the packed `test-library` fixture) expose managed methods to native code as
`[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]` static methods whose addresses
are taken with `(IntPtr)(delegate* unmanaged[Cdecl]<...>)(&Thunk)` — no runtime-built thunk, the
address is an ahead-of-time-compiled native entry point. **Verified in repo-generated output**
(`test-library/build/nuget/TestLibrary.1.0.0-fixture.*/contentFiles/cs/any/CatRegistration.cs`), and
the consumer-side compile prerequisite is already in place: the packed `build/TestLibrary.targets`
sets `<AllowUnsafeBlocks>true</AllowUnsafeBlocks>` for every consumer (**verified** in the same
package).

### The two facts this design rests on (both spiked, both verified)

**1. Every forward callback ABI already threads an echoed `userData`/`ctx` pointer.** Re-verified
this session against real generated output for mingwX64
(`test-library/build/generated/ksp/mingwX64/mingwX64Main/resources/Interop.cs` and
`.../kotlin/io/github/xxfast/kotlin/native/nuget/generated/CNameExports.kt`), all five shapes:

- Per-call lambdas: `Native_DescribeWith(handle, fnPtr, userData, out error)`; Kotlin invokes
  `formatFn.invoke(arg0Ref, formatUserData)` (`CNameExports.kt:3447`, and likewise 3474–3579 for
  every arity/return shape).
- Stored callbacks: `Native_AddListener(handle, onMeowPtr, onMeowCtx, onPurrPtr, onPurrCtx, out
  error)`; Kotlin invokes `onMeowFn.invoke(arg0Ref, onMeowCtx)` / `onPurrFn.invoke(onPurrCtx)`
  (`CNameExports.kt:3851/3855`).
- Interface bridges: `pet_bridge_create(nameGetPtr, nameGetCtx, legsGetPtr, legsGetCtx, ...)` — one
  ctx per slot; Kotlin invokes `nameGetFn.invoke(nameGetCtx)` etc. (`CNameExports.kt:10860–10900`).
- Flow/StateFlow: `onNext.invoke(itemRef, 0.toByte(), userData)` / `onComplete.invoke(userData)` /
  `onError.invoke(errRef, userData)` (`CNameExports.kt:3931+`, all flow exports).
- Suspend: `fn.invoke(resultRef, null, 0.toByte(), userData)` in all three completion arms
  (`CNameExports.kt:11000–11012`).

The only `invoke()` calls without a ctx are Kotlin-held Kotlin lambdas run inside Kotlin — 
`removeListener`'s `subscriptionHandle.asStableRef<() -> Unit>().get().invoke()` and the
`nuget_func{N}_invoke` exports' `handle.asStableRef<FunctionN<...>>().get().invoke(...)` (the
C#→Kotlin `KotlinFunc` direction) — which never cross into managed code; the
`nuget_suspend_func{N}_invoke` exports use the standard `NugetAsyncCallback` shape with `userData`
echoed in all three arms (**verified**, `CNameExports.kt:12393+`), so they are covered by the
suspend row below. So **the Kotlin side needs no signature change anywhere**.

Today the C# side *ignores* the slot in four of the five shapes (passes `IntPtr.Zero`, closures
capture the state instead); only suspend uses it (passes the `tcs` GCHandle). The slot exists at the
ABI everywhere, which is all this design needs.

**2. Every callback delegate signature is already blittable.** All 16 generated delegate types
(**verified**, `Interop.cs:11564–12034`) use only `IntPtr`, `byte`, `int`, and `void` returns:

| Delegate | Signature |
|---|---|
| `NugetStringCallback` | `IntPtr (IntPtr userData)` |
| `NugetStringStringCallback` | `IntPtr (IntPtr arg0Ptr, IntPtr userData)` |
| `NugetStringStringStringCallback` | `IntPtr (IntPtr, IntPtr, IntPtr userData)` |
| `NugetStringByteCallback` | `byte (IntPtr, IntPtr userData)` |
| `NugetStringStringByteCallback` | `byte (IntPtr, IntPtr, IntPtr userData)` |
| `NugetObjectVoidCallback` | `void (IntPtr, IntPtr userData)` |
| `NugetIntVoidCallback` | `void (int arg0Ord, IntPtr _)` |
| `NugetVoidCallback` | `void (IntPtr _)` |
| `NugetAsyncCallback` | `void (IntPtr result, IntPtr error, byte isCancelled, IntPtr userData)` |
| `NugetFlowOnNextCallback` | `void (IntPtr itemPtr, byte isCancelled, IntPtr userData)` |
| `NugetFlowOnCompleteCallback` | `void (IntPtr userData)` |
| `NugetFlowOnErrorCallback` | `void (IntPtr errorPtr, IntPtr userData)` |
| `NugetBridgeObjectCallback` | `IntPtr (IntPtr ctx)` |
| `NugetBridgeObjectObjectCallback` | `IntPtr (IntPtr arg0, IntPtr ctx)` |
| `NugetBridgeIntCallback` | `int (IntPtr ctx)` |
| `NugetBridgeVoidCallback` | `void (IntPtr ctx)` |

(`NugetFlowCollectDelegate` is a purely managed-side adapter around a `DllImport` and never crosses
native→managed; it is out of scope.) No `string`, `bool`, arrays, or non-blittable marshalling
anywhere, so every shape can be expressed as an `[UnmanagedCallersOnly]` signature **verbatim** —
no manual marshalling moves into the thunks.

### Toolchain proof

The exact mechanism — `[UnmanagedCallersOnly(CallConvs = ...Cdecl)]` static thunk, invoked through a
raw `delegate* unmanaged[Cdecl]` pointer, dispatching to a captured managed closure through a
`GCHandle` ctx — was spiked this session under CoreCLR NativeAOT:
`dotnet publish -r win-x64 -c Release -p:PublishAot=true` (SDK 10.0.301, win-x64), run the produced
native binary, output `result=42`, exit 0. **Verified.** (First attempt failed at link time only
because `vswhere.exe` was not on Git Bash's PATH; shell-specific, not an environment gap —
GitHub's `windows-latest` resolves it from the default shell.)

## Alternatives Considered

### 1. Per-signature `[UnmanagedCallersOnly]` static thunks, ctx = GCHandle to the existing delegate instance (chosen)

For each of the ~16 delegate shapes, emit **one** static thunk next to the delegate declaration
(same support region of `Interop.cs`, owned by `CirCallbackRenderer.kt` /
`CirConcurrencyRenderer.kt` / `CirFlowRenderer.kt` / `CirBridgeRenderer.kt`):

```csharp
internal static unsafe class NugetThunks
{
    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    internal static IntPtr StringStringThunk(IntPtr arg0Ptr, IntPtr ctx) =>
        ((NugetStringStringCallback)GCHandle.FromIntPtr(ctx).Target!)(arg0Ptr, ctx);

    internal static IntPtr StringStringPtr =>
        (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr>)&StringStringThunk;
    // ... one pair per delegate shape
}
```

Call sites change minimally — the existing closure stays exactly as it is; only the two lines that
obtain the pointer change:

```csharp
NugetStringStringCallback nativeCallback = (arg0Ptr, userData) => { ... unchanged ... };
GCHandle cbHandle = GCHandle.Alloc(nativeCallback);            // unchanged (now the ctx, not a pin)
IntPtr nativeResult = Native_DescribeWith(
    _handle, NugetThunks.StringStringPtr, GCHandle.ToIntPtr(cbHandle), out IntPtr error);
```

Kotlin echoes the ctx back into the thunk, the thunk recovers the delegate and invokes it as an
ordinary managed call — closures, generics (`KotlinFlowEnumerator<T>`'s captured `T` machinery,
`TaskCompletionSource<T>`), everything downstream is plain managed dispatch, which AOT handles.

**Pros:** no runtime-built thunk anywhere (the AOT compiler compiles the static thunks ahead of
time, **verified** by the probe above and by ADR-041's shipped shims); zero Kotlin-side changes
(**verified**, fact 1); zero marshalling moved into thunks (**verified**, fact 2); GCHandle
lifetime discipline is byte-for-byte where it is today for four shapes (free in `finally` for
per-call, in the subscription disposer for stored, in `NugetBridgeState.FreeAll()` for bridges, in
the completion callback for suspend). **Flow is the measured exception:** `NugetJobNative.Cancel`
is asynchronous, so Kotlin's terminal `onNext(isCancelled=1)` can arrive *after* `DisposeAsync`
returns; freeing the shared ctx handle in `DisposeAsync` (today's stale-call-tolerant behaviour) is
a use-after-free once the thunk dispatches through that handle — caught live by the FailFast policy
during implementation. The shipped design frees the flow ctx by rendezvous: whichever of
{enumerator disposal, Kotlin's terminal callback} arrives second releases it
(`Interlocked`-guarded, double-dispose safe); a never-disposed enumerator leaks exactly what it
leaked before; ~16 small emit sites in the shared support
region, not per-call-site sprawl.

**Cons:** requires `unsafe` + function pointers in generated code (already true of the reverse shims
in the same package; `AllowUnsafeBlocks` already packed, **verified**); one extra managed indirection
per callback invocation (delegate cast + invoke — negligible against the marshalling already done).

### 2. Keep `GetFunctionPointerForDelegate`, document `MtouchInterpreter`/interpreter flags

**Pros:** zero code change; verified to unblock Catalyst; and CoreCLR NativeAOT was measured
working without any flag (see Context). **Cons:** pushes a per-consumer, per-platform build flag
into every Mono-AOT downstream app; keeps the whole callback surface interpreted there (performance
and app-review surface); and on NativeAOT it leaves correctness resting on ILC's undocumented stub
pre-generation instead of the contractual `[UnmanagedCallersOnly]` mechanism. Rejected: this ADR's
primary value is deleting that flag on Mono full-AOT platforms.

### 3. Port ADR-041 literally: `[ModuleInitializer]` registration table of thunks

**Pros:** symmetrical with the reverse direction. **Cons:** solves a problem the forward direction
does not have. ADR-041 needs init-time registration because *Kotlin initiates* the call and has no
per-call opportunity to receive a pointer. In every forward shape the pointer is handed to Kotlin
**inside a C#-initiated call** (the call site itself, `AddListener`, `pet_bridge_create`, the flow
`startCollect`, the async invoke) — and `&Thunk` is a constant. A registration table plus new Kotlin
registry exports would be pure overhead and a real ABI change. Rejected; the ROADMAP's "port
ADR-041" phrasing means the *thunk mechanism*, and this ADR owns that deviation explicitly.

### 4. Per-call-site `[UnmanagedCallersOnly]` methods (one thunk per generated callback parameter)

**Pros:** ctx could point straight at user state, skipping the delegate hop. **Cons:**
`[UnmanagedCallersOnly]` methods must be static, non-generic, and outside generic types (**inferred**
from the documented attribute constraints, consistent with ADR-041; violation is a *compile-time*
error, not a silent one) — so they cannot live in `KotlinFlowEnumerator<T>` or capture anything,
meaning every call site would still need a ctx-carried state object *plus* its own thunk: hundreds
of emit sites for zero gain over ~16 shared ones. Rejected.

## Decision

Adopt alternative 1 for **all five shapes** in v1. Nothing found in the spikes justifies a narrower
cut: every shape echoes ctx (verified) and every signature is blittable (verified), so the
"narrowest scope covering only the verified Flow failure" contingency is moot.

### Per-shape plan

| Shape | ADR | Renderer (emit owner) | Delegate shape(s) | ctx echoed? | Blittable? | Thunk work |
|---|---|---|---|---|---|---|
| Per-call lambda params | 036 | `CirCallbackRenderer.kt` (delegates + thunks), `CirClassRenderer.kt`/`CirFunctionRenderer.kt` (call sites) | `NugetString*`, `NugetObjectVoid`, `NugetIntVoid`, `NugetVoid` (8 shapes) | **verified** (per-param `userData`; C# passes `IntPtr.Zero` today) | **verified** | cast ctx → delegate, invoke; call site passes `GCHandle.ToIntPtr(cbHandle)` instead of `IntPtr.Zero` |
| Stored callbacks | 037 | same as above (`AddListener` in `CirClassRenderer.kt`) | `NugetObjectVoidCallback`, `NugetVoidCallback` | **verified** (`onMeowCtx`/`onPurrCtx`) | **verified** | same; `h0`/`h1` become the ctx handles, freed in the existing subscription disposer |
| Interface bridges | 039/084 | `CirBridgeRenderer.kt` | `NugetBridge{Object,ObjectObject,Int,Void}Callback` | **verified** (per-slot ctx) | **verified** | ctx = GCHandle to the slot delegate (kept in `_pins`); `Pin()` semantics unchanged, `FreeAll()` unchanged |
| Flow/StateFlow collect | 026/065 | `CirFlowRenderer.kt` | `NugetFlowOn{Next,Complete,Error}Callback` | **verified** | **verified** | thunks are non-generic statics. **Corrected at the red step:** the flow ABI threads ONE shared trailing `userData` that Kotlin echoes into all three of `onNext`/`onComplete`/`onError` (there are no per-callback ctx slots), so that shared ctx must carry a real GCHandle to one state object holding all three delegates (or the enumerator itself), and each thunk pulls its own delegate out of it — it cannot stay `IntPtr.Zero` |
| Suspend resumption | 019/020 | `CirConcurrencyRenderer.kt` (delegate + thunk), suspend call sites in class/function renderers | `NugetAsyncCallback` | **verified** (already used: tcs handle) | **verified** | ctx becomes the GCHandle of the `callback` closure itself; the closure already captures `tcs`, so the separate `tcsHandle`-via-userData becomes redundant and collapses to one handle. **The `DllImport` parameter changes from `NugetAsyncCallback callback` to `IntPtr callback`** — a C#-side-only text change; the native symbol already receives a raw function pointer, Kotlin is untouched |

Mechanism claims in the table: ctx echo and blittability are **verified** (generated
mingwX64 output, this session); the statement that a delegate-typed `DllImport` parameter and
`GetFunctionPointerForDelegate` produce the same runtime-built thunk (hence suspend is equally
broken today) is **inferred** from the marshalling documentation — the live Catalyst failure was
observed on the Flow shape only.

### What does not change

- Kotlin-side exports, the C ABI, and the KSP Kotlin renderers: nothing. (**Verified** that every
  invocation site already echoes ctx.)
- GCHandle allocation/free points: identical to today, the handle just changes role from
  "pin so the marshaller's thunk stays alive" to "ctx key the thunk dispatches through".
- ADR-036 remains correct for the JIT-only rationale it recorded; this ADR supersedes its pointer
  acquisition choice for the whole forward callback surface.

### Exception discipline (decided at the gate: FailFast)

A managed exception escaping an `[UnmanagedCallersOnly]` method is undefined/process-fatal under
NativeAOT (**inferred** from the interop documentation; today's behaviour when a user lambda throws
mid-callback through the marshaller thunk is likewise **unverified**). User code *can* throw inside
per-call lambdas (`format(arg0)`) and bridge slot implementations, and most callback ABIs here have
no error channel (the bridge has none; flow/suspend consume errors Kotlin→C#, not the reverse).
Recommendation: wrap every thunk body in a catch-all; per-call lambda ABIs that can grow a
Kotlin-side error out-parameter later should, but v1 should choose between
`Environment.FailFast(ex)` (loud, honest) and swallow-and-default (silent corruption risk).
**Decided (human gate, 2026-08-31): every thunk body is wrapped in a catch-all that calls
`Environment.FailFast(ex)`** — loud and honest, matching today's de-facto behaviour of an exception
tearing through a native frame. A real error-channel ABI (out-parameters on callback signatures) is
deferred to its own ROADMAP item.

### AOT publish smoke-test lane (ADR-038 step 4; proof of done)

- New plain **console** project (NativeAOT cannot publish a test host; standard practice is a
  console app asserting via exit code), e.g. `AotSmokeTest/`, referencing the packed fixture
  package exactly as `IntegrationTests/` does.
- Exercises at minimum one callback per shape, Flow first (the verified failure): collect a
  generated `Flow` to completion, await one suspend call, run one per-call lambda, one stored
  callback round-trip, one C#-implemented interface bridge call. Exit 0 on success, non-zero with a
  message on any mismatch.
- Publish + run: `dotnet publish AotSmokeTest -r win-x64 -c Release -p:PublishAot=true` on
  `windows-latest`, `-r osx-arm64` on `macos-latest`, then execute the produced native binary and
  assert exit code — a new step in the existing `ci.yml` matrix job after the existing
  `dotnet test IntegrationTests` step. On Windows the step must run in the default shell
  (PowerShell/cmd), not bash: the local spike showed the ILC link step failing solely on
  `vswhere.exe` PATH resolution under Git Bash.
- This one lane closes ADR-038 step 4 and converts ADR-094's inferred AOT claims to verified.
  **Its role, corrected by the red-step measurement:** on NativeAOT the lane is a
  contract/regression lane, not a red-to-green proof — it passed *before* this change (via ILC's
  stub pre-generation) and must keep passing *through the new thunks* after it. The red-to-green
  proof of the verified failure is re-running the Mac Catalyst repro without `MtouchInterpreter`,
  which stays manual (no macOS+MAUI+device lane in CI).

## Consequences

- The generated `Interop.cs` gains one `internal static unsafe class NugetThunks` (~16 thunk/getter
  pairs) and loses every `Marshal.GetFunctionPointerForDelegate` call; call sites keep their
  closures and handle lifetimes. Consumers recompile transparently (`AllowUnsafeBlocks` already
  packed).
- `<MtouchInterpreter>-all</MtouchInterpreter>` stops being required on Catalyst/iOS Release;
  `PublishAot=true` becomes supportable and CI-verified. The docs' "AOT and trimming" section
  (`docs/topics/forward-overview.md`) flips from limitation to supported-with-lane.
- ADR-036's mechanism section is superseded for pointer acquisition. ADR-038's step 4 closes with
  this lane; that ADR stays Deferred while its steps 1/3 remain open.
- Deferred: exception-channel ABIs for callbacks (beyond the catch-all policy above); `Char`/narrow
  positions in callback signatures are unaffected (none exist in the delegate set today, verified);
  `[LibraryImport]`/`CSharpProfile` interactions (orthogonal, ADR-071/094 scope); the Mono
  interpreter fallback documentation stays until the Catalyst repro is re-verified clean.

# ADR-186: Forward, `nuget_live_handles` and `nuget_gc_collect` are frozen 1.0 `nuget_*` ABI with a narrow contract, no diagnostics switch

## Status

Accepted

## Context

ROADMAP "0.9.0: breaking changes" asks whether `nuget_gc_collect` and `nuget_live_handles` belong
in the `nuget_*` runtime ABI that 1.0.0 semver covers, or behind a diagnostics switch so they may
change in 1.x. Research memo: `docs/research/roadmap/runtime-diagnostics-abi.md`.

Constraints, each labelled:

- **Both symbols are unconditional in the prebuilt runtime klib** (verified by reading):
  `NugetRuntime.kt:73-75` (`nuget_live_handles`, returns `NugetHandles.live.value`) and
  `NugetRuntime.kt:809-814` (`nuget_gc_collect`, calls `kotlin.native.runtime.GC.collect()` under
  `@OptIn(NativeRuntimeApi::class)`). [ADR-127](127-nuget-runtime-library.md) has the plugin
  `export()` the whole klib. No Gradle or C# setting can remove them from a consumer's DLL short of
  publishing a second klib. A "switch" can only gate the C# `DllImport`s in the shim.
- **The C# side is already consumer-reachable.** `Interop.cs` ships as `contentFiles` source and
  compiles into the consumer's assembly (`PackNugetTask.kt:105,213`, verified), so the `internal`
  `NugetMarshal.LiveHandles` (`CirMarshalRenderer.kt:65-73`) and `NugetBridge.GcCollect()`
  (`CirBridgeRenderer.kt:60-65`) are callable by consumer code, and
  `docs/topics/registration-diagnostics.md:123-133` tells users to call both.
- **No generated production path calls either** (verified by grep of `nuget-processor/src/main`
  and `nuget-plugin/src/main`). Their callers are the ADR-120 leak harness (`LeakTests/`), four
  IntegrationTests call sites, and ADR-178's `AotSmokeTest/Program.cs:74-84`.
- **The counter costs nothing measurable** (verified, mingwX64, Kotlin/Native 2.4.10, `-opt`,
  5,000,000 iterations, scratch `konanc` spike): `StableRef.create`+`dispose` is 62-72 ns/op
  single-threaded without the counter and 65-68 ns/op with it; the bare atomic pair is about
  3 ns/op; with 4 workers the counted and uncounted aggregates swap order between rounds (34.7 vs
  27.7, then 23.7 vs 28.5 ns/op). Full output in the memo. Other targets inferred similar, not run.
- **`GcCollect()` is gated, `LiveHandles` is not** (verified by reading): `NugetBridge` renders only
  when `bridgePlans.isNotEmpty()` (`CirTranslator.kt:879`); `NugetMarshal` is unconditional since
  ADR-129's 2026-09-20 amendment. Inferred, not compiled: a consumer of a library with no
  C#-implementable interface who follows the docs gets CS0103.

## Alternatives Considered

### 1. Freeze both, narrow contract, no switch; move `GcCollect()` onto `NugetMarshal` (chosen)

Both stay in the covered `nuget_*` ABI. What is frozen is worded so that later ADRs that count a
new handle kind, or a Kotlin/Native change to `GC.collect()`, are minor changes. Pros: zero runtime
change, no new 1.0 contract (no define name), every harness and doc stays as is, the symbols that
exist in every DLL are exactly the symbols the policy covers. Cons: two test-only-looking exports
are now a semver commitment; mitigated by how little the contract says.

### 2. C# diagnostics switch (`#if` on a consumer `DefineConstants` symbol)

The shim wraps both imports in `#if KOTLIN_NATIVE_NUGET_DIAGNOSTICS` (name illustrative); LeakTests,
IntegrationTests and AotSmokeTest set the define in their csproj. Inferred, not spiked (not
chosen): this works because contentFiles compile in the consumer's build. Rejected: removes no
native symbol and no runtime cost (the counter is in the klib either way, and costs nothing
measurable), adds the define name itself as a new 1.0 surface, breaks the shipped "Checking for a
handle leak" docs, and touches every harness.

### 3. Exempt from semver as a "diagnostics tier", no switch

Document both as present but unversioned. Rejected: the symbols are in every DLL regardless, and
the leak harness and ADR-178's AOT smoke test pin their behaviour; an exemption only licenses
breaking those later.

### 4. A separate diagnostics klib, or a `nuget_diag_*` rename

Rejected: a second published artifact and plugin wiring for two trivial functions, or churn with
no consumer benefit (users see the C# names, not the C names).

## Decision

Alternative 1.

### Frozen contract, `nuget_live_handles`

- Name `nuget_live_handles`, C signature `int64_t nuget_live_handles(void)`.
- Contract: on a process where nothing else crosses concurrently, a balanced sequence of crossings
  (every minted handle disposed, pending cleaner rounds flushed) returns the value to its prior
  reading. Process-global per native library (two packages keep independent counters, ADR-178,
  verified there on Windows).
- **Not frozen**: the absolute value, and which handle kinds are counted. The counted set grew in a
  minor before (ADR-158 added reverse bridge ctxs) and is deliberately incomplete (ADR-130's error
  envelope is a raw `StableRef`; reverse `GCHandle`s are uncounted per ADR-153/155/156). A future
  ADR may count a new kind in a minor. Absolute values are never comparable across versions.

### Frozen contract, `nuget_gc_collect`

- Name `nuget_gc_collect`, C signature `void nuget_gc_collect(void)`.
- Contract: performs one Kotlin GC round before returning. Cleaner-driven releases (ADR-084) are
  **not** guaranteed complete on return; callers loop, as `LeakTests/CollectabilityTests.cs:32` and
  `LeakTests/LiveHandleTests.cs:104` already do.
- The body depends on `kotlin.native.runtime.GC.collect()`, an experimental `@NativeRuntimeApi`.
  **Nobody has verified what a future Kotlin/Native does with it.** If it is removed or renamed, the
  export stays and calls the replacement; if there is none, a no-op body is a documented minor
  change (the leak harness would then fail loudly, not silently). Removing the symbol is a major.

### C# surface (0.9.0 break)

- `NugetMarshal.LiveHandles` unchanged.
- `nuget_gc_collect`'s import moves from `NugetBridge` to `NugetMarshal`, rendered unconditionally
  beside `LiveHandles` in `CirMarshalRenderer.kt`:

  ```csharp
  [DllImport("<lib>", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_gc_collect")]
  private static extern void Native_GcCollect();

  /// <summary>Runs one Kotlin GC round. Cleaner-driven releases may land on a later round.</summary>
  internal static void GcCollect() => Native_GcCollect();
  ```

  and `CirBridgeRenderer.kt:60-65` drops its copy. Both stay `internal`: consumer-reachable through
  contentFiles, never leaked into the consumer's own public API.
- Call sites move from `NugetBridge.GcCollect()` to `NugetMarshal.GcCollect()`:
  `IntegrationTests/BidirectionalTests.cs:398,436,448`, `IntegrationTests/LambdaReturnParameterTests.cs:59`,
  `IntegrationTests/WorkshopRoundTripTests.cs:168`, `LeakTests/CollectabilityTests.cs:32` (plus doc
  comments `:11,124`), `LeakTests/LiveHandleTests.cs:104`.

The ADR-120 harness reaches both exports exactly as today, with no switch to turn on.
`scripts/verify-runtime-exports.sh` already derives the expected export list from
`NugetRuntime.kt`, so presence of both is linted with no change.

## Consequences

- 1.0.0 semver covers both symbols with the contracts above; the stability-policy topic (ROADMAP
  0.10.0/1.0.0) should quote them.
- Breaking in 0.9.0: `NugetBridge.GcCollect()` becomes `NugetMarshal.GcCollect()`. Consumer code
  that called the old name gets CS0117 and a one-word fix; a library without a C#-implementable
  interface gains `GcCollect()` for the first time.
- `docs/topics/registration-diagnostics.md` "Checking for a handle leak" names `NugetMarshal.GcCollect()`
  and states the delta-only contract.
- No runtime behaviour change, no `NUGET_RUNTIME_CONTRACT_HASH` change (the export set is
  unchanged), no performance change.
- Amends nothing in [ADR-120](120-live-stableref-counter-and-leak-harness.md) or
  [ADR-084](084-csharp-implemented-interfaces.md) beyond where the C# `GcCollect` import lives.

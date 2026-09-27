# ADR-170: A top-level function returning a nullable scalar calls Kotlin once, on the ADR-061 `valueOut` route

## Status

Accepted (2026-09-27). Partly supersedes [ADR-002](002-nullable-two-call-pattern.md) for
top-level functions; property getters keep ADR-002. Discharges the "separate versioned ADR"
[ADR-062](062-forward-callable-plan.md)'s compatibility section requires for this switch.

## Context

A top-level `fun f(...): T?`, where `T` is a primitive, `Char`, `Instant`, `Duration`, an enum or a
Primitive/Enum-underlying value class, is the last callable still on ADR-002's two-call shape.
`ForwardCallablePlanner` reroutes it on `origin == ForwardCallableOrigin.TOP_LEVEL`
(`forward/ForwardCallablePlanner.kt:1846`) into `topLevelNullablePrimitivePlan` (`:1894`, plan
built with `evaluation = ForwardEvaluation.LEGACY_TWO_CALL` at `:2064`). The C# half is rendered by
`staticLegacyTwoCall` (`forward/ForwardCirPlanProjection.kt:239`, body from `:284`) and the Kotlin
half by `addLegacyTwoCallKotlinExport` (`forward/ForwardKotlinPlanEmitter.kt:22`, body from `:245`).
Member, extension, `object` and companion functions with the same return already take ADR-061's
single call: one export returning `Boolean` with a `valueOut` out-parameter. All line numbers were
checked by reading at `be64fbf9`.

The route has two defects.

1. **Non-compiling C# for every parameter that needs a prelude (verified by spike).**
   `staticLegacyTwoCall` builds its call arguments with `inputArguments(parameter)`, which spells a
   collection as `itemsHandle`, a `ByteArray` as `bytesHandle`, a Kotlin interface as `gHandle` and
   a callback as `fCtx`. The body never renders the prelude that declares those locals (only
   `optionalPrelude`), never renders a cleanup, and never wraps the call in
   `forwardCirHandleScope`. The ordinary route builds exactly that chain in `resultProjection`
   (`forward/ForwardCirPlanProjection.kt:991-1015`). A scratch Tier 1 run of the real processor
   over top-level `countTags(List<String>)`, `maybeTags(List<Int>?)`, `mapCount(Map<String, Int>)`,
   `setCount(Set<Int>)`, `byteCount(ByteArray)`, `greetLen(Greeter)`, `viaCallback((Int) -> Int)` and
   `tagsWithDefault(List<String>, Int = 2)`, each returning a nullable scalar, followed by
   `dotnet build` of the generated `Interop.cs` in a `net10.0` classlib, gave 16 errors, every one
   of them on this route:

   ```
   (1139,57): error CS0103: The name 'itemsHandle' does not exist in the current context
   (1185,56): error CS0103: The name 'scoresHandle' does not exist in the current context
   (1208,56): error CS0103: The name 'idsHandle' does not exist in the current context
   (1231,57): error CS0103: The name 'bytesHandle' does not exist in the current context
   (1254,56): error CS0103: The name 'gHandle' does not exist in the current context
   (1277,95): error CS0103: The name 'fCtx' does not exist in the current context
   ```

   Each name fails twice, once in the `_has_value` call and once in the `_value` call. A bound C#
   interface parameter goes through the same missing `boundInterfacePrelude`, so it is
   **inferred** to fail the same way. It was not in the fixture. In the same run, the member,
   `object` and companion functions `(items: List<String>): Int?` compiled with the prelude, the
   `try/finally` and the list `Dispose` in place (**verified**).

2. **The Kotlin function runs twice (verified by reading the spike's generated Kotlin).**
   `export_<sym>_has_value` evaluates `f(...) != null` and `export_<sym>_value` evaluates
   `f(...)!!` again. A side-effecting function has its effect twice. A callback argument is invoked
   twice per public C# call, since both exports invoke the user's function. A function that returns
   non-null on the first call and null on the second surfaces as a Kotlin `NullPointerException`
   from the `!!`. That last point is **inferred** from the emitted `!!` and was not run. ADR-002
   recorded double invocation as a known limitation to revisit. ADR-061 rejected the two-call
   pattern for methods for this reason (its Alternative 3).

ADR-062 kept the top-level two-call ABI as a compatibility decision and said that switching it to
single-call `valueOut` "requires a separate versioned ADR". This is that ADR.

## Alternatives Considered

### 1. Retire the top-level reroute onto the ADR-061 single-call plan route (chosen)

Delete the `TOP_LEVEL` reroute so that a top-level nullable-scalar function is planned by
`planOrSkip` like every other callable, then delete `topLevelNullablePrimitivePlan`,
`staticLegacyTwoCall`, `addLegacyTwoCallKotlinExport` and `ForwardEvaluation.LEGACY_TWO_CALL`.

**Verified by spike.** In a scratch clone, changing `ForwardCallablePlanner.kt:1846` to
`if (false && origin == ForwardCallableOrigin.TOP_LEVEL && ...` sent every such function through
the ordinary route. The fixture from defect 1 was widened with `Char?`, `kotlin.time.Instant?`,
`Duration?`, an enum `Mood?`, a value class `Dose(Double)?`, `Boolean?` and a parameterless
`plain(): Int?`. The generated `Interop.cs` built with `Build succeeded.` and zero errors. The
generated bodies are the ADR-061 shapes already shipped for members:

- `hasValue ? (char)valueOut : (char?)null` over `out ushort`
- `new DateTimeOffset(valueOut, TimeSpan.Zero)` and `new TimeSpan(valueOut)`
- `(Mood)valueOut` and `new Dose(valueOut)`
- `[MarshalAs(UnmanagedType.I1)] out bool valueOut` for `Boolean?` (ADR-069)
- the callback's `fCtx` registered inside `try` and unregistered in `finally`

The ADR-164 optional default (`int? extra = null` with its `extra.HasValue` slot) carried over
unchanged.

The Tier 1 Kotlin compile of that output failed only with `Unresolved reference 'LongVar'` on the
`Long?`/`Instant?`/`Duration?` exports. The cause is a harness gap, **verified**:
`tier1/Tier1CinteropStub.kt` stubs only `IntVar`, `BooleanVar`, `DoubleVar` and `UShortVar`. It is
not a generator defect. The real emitter already writes `LongVar`
(`forward/ForwardKotlinPlanEmitter.kt:910`) on the shipped member route. That route's `Duration?`
and `Instant?` returns are exercised by `test-library`'s `cat/NapTracker.kt` and
`cat/SightingLog.kt`. **Verified during implementation:** `LongVar` works against the real
Kotlin/Native toolchain; `ScoopLedger.kt`'s `timeInTray(): Duration?` and `lastCleaned(): Instant?`
compile and run, exercised by `IntegrationTests/TopLevelNullableScalarSingleCallTests.cs`.

**Pros:**
- One change fixes every prelude-bearing input, not only collections.
- Release on throw comes for free from `forwardCirHandleScope`.
- Removes the double invocation.
- The processor diff is a net deletion.
- It is the end state ADR-062 names.

**Cons:**
- The C ABI changes for top-level functions (see Consequences).
- About nine processor test files assert the old `_has_value` shape and need re-baselining.

### 2. Add the prelude/cleanup chain to `staticLegacyTwoCall`

Render the same prelude, cleanup and `forwardCirHandleScope` that `resultProjection` uses, around
both calls.

**Pros:** no ABI change.
**Cons:** keeps calling the Kotlin function, and any C# callback argument, twice. That is a known
wrong result for exactly the new shapes this would admit. It also leaves a second copy of the
prelude chain that can drift from the first. Rejected.

### 3. Skip prelude-bearing parameters on the two-call route with a named diagnostic

**Pros:** smallest change.
**Cons:** removes API that the single-call route already carries correctly. Rejected.

### 4. Also move property getters (`ForwardPropertyGetter.LegacyTwoCall`) to single-call

Out of scope, by decision. A getter has no parameters, so no prelude can be missing. It is
idempotent by convention, which is the assumption ADR-002 relied on. Unifying it would be a
separate ADR.

## Decision

A top-level function whose return is a nullable primitive, `Char?`, `Instant?`, `Duration?`, a
nullable enum or a nullable Primitive/Enum-underlying value class is planned as `EXACTLY_ONCE` on
the ordinary static route, exactly like an `object` or companion function with the same signature
(ADR-061). Its native surface is one export, `<sym>(inputs..., valueOut, errorOut): Boolean`, in
place of `<sym>_has_value` and `<sym>_value`.

Remove:

- the reroute `if (origin == ForwardCallableOrigin.TOP_LEVEL && result is BridgeType.Nullable && ...)`
  and `topLevelNullablePrimitivePlan` in `ForwardCallablePlanner.kt`
- the `LEGACY_TWO_CALL` branch in `ForwardCirPlanProjection.static`, and `staticLegacyTwoCall`
- the `LEGACY_TWO_CALL` branch in `ForwardKotlinPlanEmitter`, and `addLegacyTwoCallKotlinExport`
- `ForwardEvaluation.LEGACY_TWO_CALL` in `ForwardMarshallingModel.kt:354`. Its only users are the
  four sites above (**verified** by grep). The property route's
  `ForwardPropertyGetter.LegacyTwoCall` is a separate type and is not touched.
- `translateNullableFunction` (`cir/CirFunctionTranslator.kt`), already unreachable before this
  change, deleted alongside its own now-pointless test, `CirFunctionTranslatorNullableTest`.

Generated C#, from the spike (**verified**):

```C#
public static int? CountTags(IReadOnlyList<string> items)
{
    IntPtr itemsHandle = IntPtr.Zero;
    try
    {
        itemsHandle = NugetMarshal.CreateList(items);
        bool hasValue = Native_CountTags(itemsHandle, out int valueOut, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return hasValue ? valueOut : null;
    }
    finally
    {
        if (itemsHandle != IntPtr.Zero) { NugetListNative.Dispose(itemsHandle); }
    }
}
```

The public C# signature (`int? CountTags(IReadOnlyList<string> items)`) is unchanged for every
function that compiled before. Only the private `DllImport`s and the body change.

### Claims checked during implementation

- **Verified at generation level, not run:** a bound C# interface parameter renders the same
  `GCHandle` prelude the member route uses (ADR-088). `Tier1TopLevelNullableScalarSingleCallTest`
  binds a manifest interface and asserts
  `IntPtr feedableHandle = GCHandle.ToIntPtr(GCHandle.Alloc(feedable));` ahead of the call. No
  integration test exercises a C#-implemented interface on a top-level nullable-scalar function.
- **Verified:** the ADR-095 overload suffix and the ADR-150 doc comment carry over unchanged.
  `Tier1TopLevelNullableScalarSingleCallTest` overloads a top-level nullable-scalar function by
  name and asserts both the numbered second export and each overload's own `<summary>`.
- **Verified:** a user parameter literally named `valueOut` is renamed `valueOut_` on this route,
  the same as `Tier1ReservedParameterNamesTest` already asserted for the retired two-call route.
- **Inferred:** no supported consumer binds the C symbols directly, because the C# bindings ship in
  the same package as the native library. This was not re-checked during implementation.

## Consequences

- **C ABI change, top-level functions only:** `<sym>_has_value` and `<sym>_value` disappear and
  `<sym>` appears, with a `valueOut` out-parameter. This takes a release-note line and no version
  bump: the implementing PR's title names the dropped symbols, so the line lands in the release
  notes (CONTRIBUTING.md). Property `_get_<name>_has_value` / `_get_<name>_value` exports are
  unchanged.
- Top-level nullable-scalar functions with `List`, `List?`, `Map`, `Set`, `ByteArray`, Kotlin
  interface, or callback parameters now compile and release their handles on every exit path,
  including a throw. A bound C# interface parameter also compiles now, but its transfer handle is
  never released on the C# side either way: `boundInterfacePrelude` hands ownership to Kotlin with
  no cleanup, by design (ADR-088/132), the same as the member-function route it now shares.
- Every top-level nullable-scalar function now invokes Kotlin, and any callback argument, exactly
  once per C# call. This closes ADR-002's recorded side-effect limitation for functions.
- `ForwardAbiContract` (ADR-055) compares the new single export on both halves with no change to
  the check itself (**inferred** from ADR-061 member routes, which pass the same check today).
- Tests, as implemented:
  - `ForwardPhase10LegacyTwoCallTest` and `CirFunctionTranslatorNullableTest` are deleted: both
    asserted only the retired route (the latter's subject, `translateNullableFunction`, was already
    unreachable and is deleted with it).
  - Re-baselined for the new single-export shape: `Tier1SealedParameterPositionTest`,
    `Tier1BareNullableCharTest`, `Tier1BareNullableEnumTest`, `Tier1ValueClassEnumUnderlyingTest`,
    `Tier1OptionalDefaultParameterTest`, `Tier1ReservedParameterNamesTest` and the comment in
    `Tier1ValueClassParameterTest`.
  - `LongVar` added to `Tier1CinteropStub.kt`.
  - Not affected: the property and Flow `_has_value` assertions in `ForwardAbiContractTest`,
    `Tier1SealedSubclassPropertyPlanTest` and `Tier1FlowMethodOverloadTest`, and the negative
    assertions in `Tier1ByteArrayMappingTest` and `Tier1UuidMappingTest`.
- New coverage:
  - `Tier1TopLevelNullableScalarSingleCallTest` over the defect-1 fixture, widened with `Char?`,
    `Instant?`, `Duration?`, an enum, a value class and `Boolean?`
  - `test-library`'s `litterbox/ScoopLedger.kt`: a top-level `fun countVisits(cats: List<String>): Int?`
    and a side-effecting `nextScoop(): Int?` with a call counter (`scoopsIssued()`)
  - `IntegrationTests/TopLevelNullableScalarSingleCallTests.cs`: null result, non-null result, a
    thrown exception with the list handle live, and a single-invocation assertion for the callback
    and side-effecting cases
  - `LeakTests/LiveHandleTests.cs` rows `TwoCallCollectionParam_ListArgument_ReturnsToBaseline` and
    `TwoCallCollectionParam_ThrowingListArgument_ReturnsToBaseline`, mirroring row 3a over a null
    return, a non-null return and a throw
- Comments that describe the old shape are updated: `IntegrationTests/NullableFunctionExceptionPropagationTests.cs`,
  `IntegrationTests/StaticRouteOverloadTests.cs` and `test-library`'s `grooming/GroomingSample.kt`.
  In `docs/topics/supported-features.md`, the `T?` (nullable primitive) row now says only property
  getters use the two-call pair.
- Deferred: property getters stay on ADR-002 (Alternative 4). The `${name}HasValue` parameter-slot
  collision (`docs/backlog/hasvalue-slot-name-collision-with-user-parameter.md`) is a different
  mechanism and is not addressed.

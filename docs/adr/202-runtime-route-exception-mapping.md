# ADR-202: Module exception rows on runtime-owned routes: the library installs its classifier into its own runtime copy

## Status

Accepted

Amends [ADR-177](177-exception-mapping-by-class-hierarchy.md) ("Scope of the IO row": the
runtime-owned forward routes no longer map stdlib rows only).

## Context

ADR-177 classifies each thrown exception on the Kotlin side with `buildError(e, mappedType)`. A
generated export passes the module's own `nugetMappedType`, which adds the `kotlinx.io.IOException`
row when KSP resolves that class. Five exports live in the `nuget-runtime` klib instead and passed
`::nugetStdlibMappedType`, because the runtime depends only on kotlinx-coroutines and cannot name
the module's `internal fun nugetMappedType`:

- `nuget_suspend_func0_invoke` to `nuget_suspend_func3_invoke`: C# invoking a Kotlin `suspend`
  lambda (`KotlinSuspendFunc<T>`, `KotlinSuspendAction<...>`).
- `nuget_stateflow_collect`: C# collecting a `StateFlow` it holds or awaited.

A `kotlinx.io.IOException` on those routes therefore stayed a bare `KotlinException`, while the same
exception on a generated route was a `KotlinIOException`.

Each Kotlin library statically links its own copy of the runtime (ADR-127, ADR-178), so runtime
state is one per library by construction.

## Decision

The runtime gains two klib functions, not exports (no `@CName`):

```kotlin
@NugetRuntimeApi
public fun nugetInstallMappedType(classifier: (Throwable) -> String?)

@NugetRuntimeApi
public fun nugetRuntimeMappedType(t: Throwable): String?
```

`nugetInstallMappedType` stores the classifier in an `AtomicReference`. `nugetRuntimeMappedType`
calls the installed classifier, or `nugetStdlibMappedType` while none is installed. The five
runtime sites above classify with `::nugetRuntimeMappedType`. `buildError`, `launchForCSharp` and
`collectForCSharp` keep their signatures.

The processor installs `::nugetMappedType` as the first statement of every generated export that
hands C# a handle one of those routes can later be invoked on:

- the class suspend-lambda property getter, the only place the generated C# builds a
  `KotlinSuspendFunc` or `KotlinSuspendAction`;
- a `suspend` export returning `StateFlow` or `MutableStateFlow` (class, sealed base, sealed arm,
  interface and top-level), through the two shared body builders;
- the ADR-071 held `MutableStateFlow` acquire export.

The install is synchronous on the caller's thread and idempotent, and it has run before C# holds
the handle, so no later call can reach a route first. Two libraries in one process each install
into their own runtime copy, so no keyed registry is needed.

A plain `Flow` needs nothing: its generated `_collect` already passes the module classifier.

Routes covered: `nuget_suspend_func{0..3}_invoke` and `nuget_stateflow_collect`. Not covered:

- the reverse envelope (`nugetKotlinError`), out of scope here and done in
  [ADR-203](203-reverse-envelope-shared-exception-mapper.md);
- `nuget_stateflow_value` and `nuget_func{0..3}_invoke`, which have no error slot;
- a handle transferred from one library to another, which would classify with the receiving
  library's rows (ADR-178 already defers cross-library wrapper transfer).

No ABI change: no new `@CName`, `NUGET_RUNTIME_EXPORTS` is unchanged, `NugetRuntimeAbi1` is not
bumped and the ADR-054 contract hash is untouched. A new generator against an old runtime fails to
compile on `nugetInstallMappedType`, the same skew guard ADR-177 relies on.

## Alternatives Considered

### 1. A C# static constructor on the native helper classes (rejected)

The research memo's first recommendation: a new per-library install export, called from a static
constructor on `NugetSuspendFuncNative` and `NugetStateFlowNative`. Rejected as unnecessary: every
handle that reaches those routes is minted by a generated Kotlin export, so installing there needs
no new export and no CLR or NativeAOT type-initialiser timing assumption.

### 2. Per-object capture (rejected)

Wrapping each lambda or flow with its classifier at mint. The runtime casts handles straight to
`SuspendFunction{N}` and `StateFlow<*>`, so every mint site would wrap, and a wrapped object loses
identity and `is MutableStateFlow` on the way back in.

### 3. A Kotlin file initializer (rejected)

A generated top-level property whose initializer installs the classifier. Kotlin/Native initialises
a file lazily, so the install could silently never run before the first route call.

### 4. `@EagerInitialization`, an extra argument on the frozen exports, a kotlinx-io dependency in the runtime (rejected)

`@EagerInitialization` is experimental and adds a load-time side effect. A classifier argument
changes five frozen signatures. The runtime depending on kotlinx-io is ADR-177's rejected
alternative 2.

## Consequences

- **Breaking in the ADR-177 sense for these routes.** A `kotlinx.io.IOException` thrown by a Kotlin
  suspend lambda invoked from C#, or surfacing from a collected `StateFlow`, arrives as
  `KotlinIOException : System.IO.IOException` instead of a bare `KotlinException`. This completes
  ADR-177's behaviour change on the remaining forward routes. Which release carries it is not
  decided here.
- One extra statement runs in each export listed above. No new export, handle or `LeakTests` row:
  the existing suspend-lambda row covers the path.
- The reverse envelope was left out of this ADR; ADR-203 maps it too.

## Evidence

Verified:

- `IntegrationTests/ExceptionTypeMappingTests.cs`:
  `Oreo_SplitBag_SuspendLambda_KotlinxIoIOException_IsKotlinIOException` (fixture `onSplitBag` on
  `CatFeeder`) asserts `KotlinIOException` with `KotlinType` `kotlinx.io.IOException`, and
  `Mylo_SplitBag_SuspendLambda_Succeeds` asserts the happy path is unchanged.
- `RuntimeMappedTypeTest` (runtime nativeTest) drives every `nuget_suspend_func` arity and
  `nuget_stateflow_collect` with a throwing body, with and without an installed classifier, and
  reads `NugetError.mappedType`.
- `Tier1RuntimeRouteMappedTypeTest` pins the install as the first statement of each mint export, and
  that a plain lambda getter, a non-`StateFlow` suspend return and a plain `Flow` install nothing.
- Native pipeline `IntegrationTests` 3189 passed, `LeakTests` 189 passed, 8 AOT shapes, processor
  suite 1782. The export-surface and ADR-054 contract checks stayed green without edits.

Inferred, not verified:

- Two libraries in one process do not cross-contaminate: one runtime copy per library, but
  `MultiPackageTests` has no suspend or `StateFlow` surface to prove it.
- The `StateFlow` route is pinned by the runtime nativeTest and the Tier 1 text only. There is no C#
  end-to-end fact: a stock `StateFlow` is inferred not to throw from `collect`, and a contrived
  library-authored `StateFlow` fixture was declined.

## Pointer 2026-10-05: the reverse envelope is done

[ADR-203](203-reverse-envelope-shared-exception-mapper.md) closes the exclusion above: the reverse
envelope (`nugetKotlinError`) classifies with the module's own `nugetMappedType`, so a Kotlin slot
that throws `kotlinx.io.IOException` reaches C# as `KotlinIOException`. It does not use
`nugetRuntimeMappedType`, because a reverse slot can throw before any install in this ADR has run.

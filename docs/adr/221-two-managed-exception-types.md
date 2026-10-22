# ADR-221: Two `NugetManagedException` types stay distinct: the reverse template's and the runtime's

## Status
Accepted

Closes residual 1 of [ADR-161](161-csharp-callback-exception-into-kotlin.md). Builds on
[ADR-104](104-reverse-thunk-error-channel.md) and [ADR-130](130-reverse-error-envelope-on-runtime.md).
ROADMAP Phase 7.

## Context

Two classes share the simple name `NugetManagedException`:

- The reverse template's `internal class NugetManagedException(managedType: String, message: String?)`
  (`NugetGenerateBindingsTask.kt`). It is emitted into the consumer's `INTERNAL_PKG` only when the
  reverse pipeline runs, and thrown when a bound C# member throws. Its `message` is the raw .NET
  `Message`.
- The runtime's public `NugetManagedException(managedType: String, managedMessage: String)` in
  `nuget-runtime`, thrown by `nugetCallbackCall` when a C# callback faults (ADR-161). Its message is
  `"$managedType: $managedMessage"`.

ADR-104 hoped a consumer would catch one thing regardless of direction, and ADR-161 left the fold
open. A fold would replace the template class with an `internal expect class` in `nativeMain` and an
`internal actual typealias` to the runtime class in `mingwMain` and `posixMain`, the seam ADR-130 used
for `nugetKotlinError`. `nativeMain` cannot name the runtime class directly (ADR-130, Variant 1).

## Decision

Keep both classes. Do not fold.

What an author writes:

- From `nativeMain`, catch `io.github.xxfast.kotlin.native.nuget.internal.NugetManagedException` for a
  reverse throw, and `Exception` for a forward callback fault.
- From a per-target source set or a downstream module you can also name the runtime class.
- The reverse message is the raw .NET `Message`. The forward message is `"<type>: <message>"`
  (ADR-161, unchanged).

Had the fold shipped, its `expect` would have carried whatever marker the runtime class carries, so
the runtime class being unmarked (ADR-127, 2026-10-22 amendment) was also the precondition for a
marker-free fold. That dependency is moot.

## Verified findings

All **verified** on 2026-10-22 on macOS arm64 by building `test-library` (the reverse fixture
`scripts/verify.sh` packs) with the fold applied, then running
`./gradlew :test-library:compileNativeMainKotlinMetadata :test-library:compileKotlinMacosArm64`
and `scripts/verify.sh`.

- The seam compiles only with the runtime's exact constructor shape.
  - Expect constructor `(managedType: String, message: String?)`: metadata green, platform red:
    `'actual typealias NugetManagedException = NugetManagedException' has no corresponding members for expected class members: expect constructor(managedType: String, message: String?) ... incompatible because parameter types are different: constructor(managedType: String, managedMessage: String)`.
  - `(managedType: String, message: String)`: red with `the parameter names are different`. A
    parameter name mismatch is an error for typealias actualisation, not a warning.
  - `(managedType: String, managedMessage: String)` plus `message.orEmpty()` at the one throw site
    (`nugetThrowManagedError`): both tasks green.
- Three Beta warnings come from generated code (none before): `'expect'/'actual' classes ... are in
  Beta. Consider using the '-Xexpect-actual-classes' flag`, twice in the `nativeMain` file (metadata
  and platform compile) and once in `posixMain/.../NugetKotlinErrors.kt`. With `allWarningsAsErrors`
  they are counted in the `-Werror` set. `@Suppress("EXPECT_ACTUAL_CLASSES_ARE_IN_BETA_WARNING")` on
  the declarations and as an `@file:Suppress` on both generated files suppresses none of them.
  `-Xexpect-actual-classes` silences all of them, but the plugin would have to inject it into every
  consumer compilation, which also hides the author's own Beta warnings.
- `e.message` changes from `boom` to `System.InvalidOperationException: boom`, and `scripts/verify.sh`
  went red with 15 `IntegrationTests` failures, all on message shape: 10 in
  `InfirmaryRoundTripTests`, one in `RosterRoundTripTests`, two in `KennelRoundTripTests`, and two
  Kotlin to C# to Kotlin round trips (`MenagerieRoundTripTests`, `WorkshopRoundTripTests`) where the
  message gained a `Kotlin.Native.Interop.KotlinInvalidOperationException: ` prefix.
  `docs/topics/reverse-delegates.md` promises the raw message at a reverse catch site, so every
  consumer's catch site would change the same way.
- A .NET exception with a `null` message collapses to `""`, since the runtime constructor is non-null.
- A plain `internal typealias NugetManagedException = io.github.xxfast.kotlin.native.nuget.runtime.NugetManagedException`
  in the `nativeMain` template fails `compileNativeMainKotlinMetadata`:
  `Unresolved reference 'runtime'`, cascading into every fixture file that catches the exception.

**Inferred, not observed:** `mingwX64` behaves like `posixMain` (the same template is emitted twice
and was not compiled on the macOS host). After a fold, an escaping reverse throw would reach C#
with the runtime's qualified `KotlinType`.

## Alternatives rejected

- **The fold over the expect/actual seam**, with `-Xexpect-actual-classes` injected and fixture
  `managedMessage` edits. It removes one `internal` class and adds a compiler-flag injection, a
  breaking message change for every consumer's reverse catch site, and a `null` message collapse.
- **A raw-message runtime class** (the forward message becomes raw instead). It would change
  `NugetErrorNative.BuildException`'s verbatim `"<type>: <message>"` match (`CirErrorRenderer.kt`)
  and drop the type from every `catch (e: Exception)` printout in a forward-only library. More
  change than the fold. Not built.
- **A per-target `internal expect fun` factory instead of an `expect class`.** Expect functions are
  stable, but `nativeMain` authors would have no name to `catch` and no `managedType`. Inferred
  from the unresolved-reference result above.
- **The plain typealias.** Red, above.
- **The runtime as `api` on `nativeMain`.** It has no iOS variant (`NugetPlugin.kt`).
- **A per-target source directory for the reverse files.** Rejected by ADR-130.
- **Renaming the `internal` class.** It breaks every author catch site for no gain.

## Consequences

- No code change. The standing proof is `MenagerieRoundTripTests`, `WorkshopRoundTripTests`,
  `InfirmaryRoundTripTests`, `RosterRoundTripTests` and `KennelRoundTripTests` (the raw reverse
  message) and `CallbackFaultTests` (the prefixed forward message).
- ADR-104's "a consumer catches one thing regardless of direction" holds for the wire shape and on
  the C# side. On the Kotlin side it holds per source set only. That is the accepted limit.
- A later fold needs a new ADR that supersedes this one.
- ADR-161 residual 1 is closed.

Side finding, **verified**: a consumer of `test-library` with `allWarningsAsErrors` is already red
today from 34 KSP `CNameExports.kt` warnings (`No cast needed`, `Unchecked cast`). It is unrelated to
this decision.

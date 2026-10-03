# ADR-193: Plugin unit tests compile the generated reverse Kotlin with kotlinc-native

## Status

Accepted

## Context

The ROADMAP item "Compile the generated Kotlin in the plugin unit tests" is the reverse half of a hole ADR-060 already closed for the forward half. `NugetGenerateBindingsTaskTest` and `NugetGenerateShimsTaskTest` assert on generated text. A generator can emit Kotlin that does not compile, and those tests stay green. Two times that has happened for real: the ADR-054 walking skeleton wrote literal backslashes into `NugetRegistry.kt`, and ADR-070's plugin tests passed while the generated interface files were missing `import NugetHandleOwner` and a settable property was rendered as a `val` plus a dangling `set`. Both were caught only by the Kotlin/Native compile at the end of `scripts/verify.sh`.

The contract is narrow. A maintainer running the plugin unit tests gets a failing test, with the compiler's error text, when the reverse generator emits Kotlin that does not compile. No new consumer API, no new diagnostic, no compile of the C# shims (`GeneratedBindingsCheck` and `nugetCompileInterop` already cover C#), no new `scripts/verify.sh` leg. `verify.sh` already compiles this Kotlin inside the consumer. The point is to fail in seconds inside the plugin tests.

What the generator actually emits (verified by reading `NugetGenerateBindingsTask.kt`):

- Consumer stubs under `nativeMain`, plus shared `NugetRuntime.kt`, `NugetRegistry.kt`, `NugetTrace.kt`, `NugetInterop.kt`.
- `expect` declarations in the shared `nativeMain` files (`nugetKotlinError`, `nugetRetainCtx`, `nugetReleaseCtx`, `nugetAwaitTask`, `nugetFlow`, `freeManagedString`, the slot helpers).
- `actual` declarations twice, under `posixMain` and `mingwMain`. `NugetKotlinErrors.kt` is one template written to both. `NugetInterop.kt` is not: POSIX calls `platform.posix.free`, mingw calls `platform.windows.CoTaskMemFree`.
- `NugetTrace.kt` calls `platform.posix` from `nativeMain` itself.
- `@CName` with no import.

The forward precedent compiles with an in-process `K2JVMCompiler` and a hand-written cinterop stub (ADR-060). That stub is the wrong tool here. The reverse files are multiplatform source, they name a native-only surface (`kotlinx.cinterop`, `kotlin.concurrent.AtomicInt`, `kotlin.native.identityHashCode`, `platform.posix`), and `@CName` resolves on Kotlin/Native without an import.

## Alternatives Considered

### 1. `kotlinc-native -p library` on `nativeMain` plus the host actual set, against the real runtime sources (chosen)

A new test, `NugetCompileGeneratedKotlinTest`, calls `generateKotlinStubs` on one interface fixture (a void method and a settable string property: the ADR-070 shape). It writes `nativeMain` and only the host actual directory (`posixMain` on macOS and Linux, `mingwMain` on Windows). It compiles them with the host `kotlinc-native`:

- `-Xmulti-platform`, and every `nativeMain` file also passed as `-Xcommon-sources`
- `-p library`, so the compiler typechecks and emits a klib. It does not link a binary and it does not start a .NET host
- `-l` a klib the test just built from `nuget-runtime/src/nativeMain`, plus a one-line harness `internal const val NUGET_RUNTIME_VERSION`
- `-l` the host `kotlinx-coroutines-core` klib at the plugin's pinned `1.10.2`
- the `ExperimentalNugetBindingApi` source passed as another common source

A second test deletes the generated `import NugetHandleOwner` and asserts a non-zero exit. The substring tests stay. `NugetGenerateBindingsTaskTest.kt` is not edited.

**Pros:** the spike compiled honest output this way in 2.3s, and a missing import failed. The classpath is the real cinterop, the real `platform.posix`, and the real runtime sources, so a drifted stub cannot turn a bad call green. No production generator change.

**Cons:** the test shells out. The first clean run has to resolve the Kotlin/Native distribution (the plugin tests run before `verify.sh` compiles anything native). The mingw-only interop template is not compiled on macOS.

### 2. In-process `K2JVMCompiler` plus a cinterop stub, the Tier 1 shape (rejected)

**Rejected.** Spiked. `K2JVMCompiler` 2.4.10 on the generated `NugetRuntime.kt` and `IFeedableBindings.kt`, classpath stdlib plus `kotlinx-coroutines-core-jvm` 1.10.2, exits with 327 errors: unresolved `kotlinx.cinterop`, `AtomicInt`, `identityHashCode`, `@CName`, and `'expect' and 'actual' declarations can be used only in multiplatform projects`. A stub wide enough to go green is the ADR-060 stub-drift hole, on a larger surface. `@CName` without an import is legal under `kotlinc-native` (spiked, exit 0) and unresolved under `K2JVMCompiler`, so a JVM harness would also be scoring a different language.

### 3. Compile one generated file (rejected)

**Rejected.** The handle file imports `NugetHandleOwner` from the generated internal package. The runtime file `expect`s functions whose `actual`s live in another source set. One file either fails to compile when it is honest, or, if the harness stubs those names into the same package, misses the missing-import bug this item exists to catch.

### 4. A new `scripts/verify.sh` leg, or a pack-time `konanc` task (rejected)

**Rejected.** Out of the restatement. The consumer compile at the end of `verify.sh` is the backstop this item is trying not to wait for. ADR-138's pack-time C# compile is the right shape for C#, and it already exists.

## Decision

Option 1. The plugin unit tests compile the reverse generator's Kotlin with the host `kotlinc-native`, as a klib, `nativeMain` as the common sources and exactly one actual source set.

Mechanism claims:

- **Verified** (spike, macOS arm64, `kotlinc-native` 2.4.10, scratch dir): `generateKotlinStubs` on an `IFeedable` with `Feed(): void` and settable `Label: string` produces 12 files. Compiling `nuget-runtime`'s `nativeMain` plus `internal const val NUGET_RUNTIME_VERSION = "spike"`, linked with `kotlinx-coroutines-core-macosarm64` 1.10.2, `-p library`, exits 0 in 2.35s. Without the constant it fails only on `NUGET_RUNTIME_VERSION` (`NugetRuntime.kt:624`). Compiling the generated `nativeMain` plus `posixMain`, with the annotation source as a common source, `-Xmulti-platform -p library -l` that runtime klib `-l` the coroutines klib, exits 0 in 2.26s and writes a klib. No `dotnet` process.
- **Verified** (same compiler): a three-line file using `@CName` with no import, `-p library`, exits 0.
- **Verified** (same compiler): adding the generated `mingwMain` files to the green command exits 1, `unresolved reference 'windows'` and `conflicting overloads` on the duplicate `actual`s.
- **Verified** (spike, earlier classpath that stubbed the runtime surface instead of the klib): deleting `import io.github.xxfast.kotlin.native.nuget.internal.NugetHandleOwner` from the generated handle file exits 1 with `unresolved reference 'NugetHandleOwner'`. **Inferred:** the real-klib classpath reports the same error, because that name is declared in generated `NugetRuntime.kt` (`NugetGenerateBindingsTask.kt:5717`) and the unmodified file compiled against the real klib.
- **Verified** (spike): `K2JVMCompiler` rejects the honest `NugetRuntime.kt` as described under alternative 2.
- **Inferred:** on a Windows host the mingw actual set compiles the way the posix set compiled on macOS, and `platform.windows` resolves there. Not spiked. The test should still pick the host set, and fail clearly on an unknown host, rather than hardcoding macOS.
- **Verified (Maven Central, 2026-10-03):** the published host archives are `macos-aarch64.tar.gz`, `macos-x86_64.tar.gz`, `linux-x86_64.tar.gz` and `windows-x86_64.zip`. The first draft inferred `macos-x64`, `linux-x64` and `mingw-x64@tar.gz`; all three 404 and the Windows CI row caught it. The test unpacks with plain `tar -xf`, which Windows bsdtar also accepts for the zip.

The test resolves that compiler archive and the host coroutines klib from Gradle configurations, so a clean plugin-test run does not depend on `~/.konan` having been filled by an earlier native build. `scripts/verify.sh:25-27` runs `:nuget-plugin:test` before `:nuget-runtime:allTests`.

## Consequences

- The plugin test suite grows by roughly five seconds once the compiler is cached (about 2.4s to compile the runtime sources, about 2.3s to compile the generated files). The first resolve of the native distribution is a download.
- Substring tests stay. They still pin text the compiler does not care about (export names, package paths).
- No generator change. The spike compiled the current generator.
- A bug that exists only in the mingw `NugetInterop.kt` string is not compiled on macOS. `NugetKotlinErrors.kt` is the same text on both targets, so it is covered. Accept the gap.
- Windows is not spiked. If the host mapping is wrong, the test fails at resolve or at `platform.windows`, which is loud, not a green lie.

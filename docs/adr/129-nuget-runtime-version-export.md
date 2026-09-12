# ADR-129: A 67th runtime export, `nuget_runtime_version`, traced from the forward `Interop.cs` at load

## Status

Accepted

## Context

[ADR-127](127-nuget-runtime-library.md) moved the fixed `nuget_*` ABI into the `nuget-runtime` klib
and pinned its surface at "66 names, nothing else", deferring one item to its Consequences: "a
`nuget_runtime_version(): String` export for `NUGET_INTEROP_TRACE=1` to print which runtime a
process loaded". ROADMAP Phase 14 carries it as a bullet. Adding a 67th name to a surface an ADR
pinned is a decision, so this is a short ADR rather than a silent extension.

The runtime now has a version (`io.github.xxfast:nuget-runtime:<version>`) but nothing at runtime
reports it. When a consumer's bridge misbehaves, "which runtime is actually in this `.dylib`/`.dll`"
is a question that today needs `nm` plus a maven cache walk. The bullet asks for the cheap answer:
one export, printed under the existing opt-in trace variable.

Three facts about the current code constrain the design (all verified by reading source,
2026-09-12):

- **The runtime is Kotlin/Native and has no resources.** The plugin already solves "bake the
  version in" for itself: `nuget-plugin/build.gradle.kts:41-62` registers `generateVersionConstant`,
  which writes `build/generated/source/version/main/.../NugetVersion.kt` containing
  `internal const val PLUGIN_VERSION: String = "$pluginVersion"` and adds the directory with
  `kotlin.sourceSets.named("main") { kotlin.srcDir(generateVersionConstant) }`.
- **The only `NUGET_INTEROP_TRACE` hook is the reverse bridge's** ([ADR-054](054-reverse-bridge-registration-observability.md)):
  Kotlin `NugetTrace.kt`/`NugetRegistry.kt` generated into `nativeMain` by
  `NugetGenerateBindingsTask.kt:715-725` and `4834-4885`, C# `NugetTrace.cs` by
  `NugetGenerateShimsTask.kt:2405-2445`. Both fire only from register exports, i.e. only when the
  reverse bridge is used. The forward `Interop.cs` has no trace hook and no module initializer
  (`grep ModuleInitializer nuget-processor/src/main` is empty).
- **Every forward helper class in `Interop.cs` is gated.** `CirTranslator.kt:592-619`: `NugetMarshal`,
  `NugetBridge`, `NugetListNative`, ... and even `NugetErrorNative` are all added inside
  `if (needsMarshalHelper)`. A DllImport that must exist for every library cannot live in any of
  them.

## Alternatives Considered

### 1. Forward-side: an always-emitted `NugetRuntime` class in `Interop.cs` with a `[ModuleInitializer]` trace (chosen)

The processor emits one more helper, unconditionally, outside the `needsMarshalHelper` gate:

```csharp
internal static class NugetRuntime
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_runtime_version")]
    private static extern IntPtr Native_version();

    internal static string Version => Marshal.PtrToStringUTF8(Native_version())!;

    // ADR-129: opt-in, same variables and accepted values as ADR-054's NugetTrace. Off: no
    // P/Invoke, so native-library load timing is unchanged. On: the first thing the process
    // prints about this library is which runtime it carries, before any bridge call.
    [System.Runtime.CompilerServices.ModuleInitializer]
    internal static void TraceLoaded()
    {
        if (Environment.GetEnvironmentVariable("NUGET_INTEROP_TRACE") is not ("1" or "true" or "all")) return;
        string line;
        try { line = $"[nuget:interop] runtime {Version} loaded from sample"; }
        catch (Exception e) { line = $"[nuget:interop] runtime version unavailable from sample: {e.GetType().Name}: {e.Message}"; }
        string? file = Environment.GetEnvironmentVariable("NUGET_INTEROP_TRACEFILE");
        if (file is null) Console.Error.WriteLine(line);
        else System.IO.File.AppendAllText(file, line + Environment.NewLine);
    }
}
```

Pros: satisfies the bullet literally. "A process" is the .NET host and the useful moment is the
host loading the bindings, which happens for every consumer, forward-only included. One P/Invoke,
one class, zero cost when the variable is unset. `Interop.cs` ships as source
(`contentFiles/cs/any/*.cs`, `buildAction="Compile"`, verified in `PackNugetTask.kt:221-223`), so
the initializer compiles into the *consumer's* assembly and fires when that assembly loads, and
`internal` is reachable from `IntegrationTests` without `InternalsVisibleTo` (the same reason
`LeakTests` can read `NugetMarshal.LiveHandles`).

Cons: five processor files touched (below); the forward side gains its first module initializer;
a second, tiny copy of the env-var gate beside ADR-054's (they live in different namespaces and
different generators, sharing would couple the forward processor to the reverse task for four
lines).

### 2. Reverse-side: the `nuget_runtime_register` trace line gains the runtime version (rejected)

Add the version to `NugetRegistry.record(...)`'s `[nuget] registered <runtime> ...` line
(`NugetGenerateBindingsTask.kt:4763-4775`). Fewest files (one), but it does not satisfy the
bullet: that line fires only inside `nuget_runtime_register`, which C# calls only when the
reverse bridge exists. A forward-only consumer, the common case, never prints it.

A second, independent constraint, **open**: the generated reverse Kotlin lives in `nativeMain`
(`relativePath = "nativeMain/$INTERNAL_DIR/NugetTrace.kt"`), while the plugin wires the runtime
as `${target.name}MainApi` per target (`NugetPlugin.kt:241`,
`NugetPluginRuntimeExportWiringTest.kt:68` pins `macosArm64MainApi`). Whether `nativeMain`
source can resolve a declaration from a per-target `api` dependency (it compiles into each
target's klib, but the shared-native metadata compilation may not see it) is being spiked by a
sibling agent; nothing here depends on the answer, and option 2 would.

### 3. No export: print the version the plugin knows at build time into a generated comment (rejected)

`Interop.cs` could carry `// nuget-runtime 0.6.0` as text. Free, but it reports what the
generator *expected*, not what the binary *contains*, which is exactly the skew ADR-127's
anchor exists for and exactly the case a trace is enabled to diagnose.

### 4. A public `KotlinRuntime.Version` in the consumer's API (rejected)

Surfacing the version as a public C# member makes a diagnostic into an API contract. `internal`,
like `NugetMarshal.LiveHandles` (ADR-120), is enough for tests and the trace; a public form can
come with bullet 5's C# twin if it ever earns it.

## Decision

### 1. Version baking in the runtime

`nuget-runtime/build.gradle.kts` mirrors `generateVersionConstant` from
`nuget-plugin/build.gradle.kts:44-62` (verified source of the pattern):

```kotlin
val generateRuntimeVersionConstant: TaskProvider<Task> = tasks.register("generateRuntimeVersionConstant") {
  val outputDir: Provider<Directory> = layout.buildDirectory.dir("generated/source/version/nativeMain")
  val runtimeVersion: String = version.toString()
  inputs.property("runtimeVersion", runtimeVersion)
  outputs.dir(outputDir)
  doLast {
    val packageDir: File = outputDir.get().asFile.resolve("io/github/xxfast/kotlin/native/nuget/runtime")
    packageDir.mkdirs()
    packageDir.resolve("NugetRuntimeVersion.kt").writeText(
      """
      package io.github.xxfast.kotlin.native.nuget.runtime

      internal const val NUGET_RUNTIME_VERSION: String = "$runtimeVersion"
      """.trimIndent() + "\n",
    )
  }
}

kotlin.sourceSets.named("nativeMain") { kotlin.srcDir(generateRuntimeVersionConstant) }
```

`version` is the root `gradle.properties` value (`version=0.6.0`) because `:nuget-runtime` is a
subproject of the root build; ADR-127 already relies on this for the maven coordinate. The
plugin's explicit `Properties.load` of `../gradle.properties` (`nuget-plugin/build.gradle.kts:12-18`)
is a composite-boundary workaround and is **not** mirrored. `internal` is deliberate: only the
export reads the constant, so ADR-127's "every public declaration is `@NugetRuntimeApi`" rule does
not grow a second name.

**Inferred** (not built; another agent holds the project lock): `kotlin.srcDir(TaskProvider)` on
a KMP `nativeMain` source set wires the task dependency the same way it does on the plugin's JVM
`main`. The API is the same `SourceDirectorySet.srcDir(Object)`; if it does not carry the task
dependency, the symptom is "unresolved reference NUGET_RUNTIME_VERSION" on a clean build, not
silent wrong output.

### 2. The export

```kotlin
@NugetRuntimeApi
@CName("nuget_runtime_version")
public fun export_nuget_runtime_version(): String = NUGET_RUNTIME_VERSION
```

Kotlin `String` return, the wire `nuget_error_message`, `nuget_error_stacktrace` and
`nuget_unwrap_string` already use (verified, `NugetRuntime.kt:68-70, 538-545`): Kotlin/Native
exports it as `const char*`, C# declares `private static extern IntPtr` and copies with
`Marshal.PtrToStringUTF8(...)!` (verified, `CirErrorRenderer.kt:9-10, 28`). No new free function;
the memory contract is [ADR-003](003-memory-management-across-bridge.md)'s (pointer valid for the
immediate call, C# copies before returning), which every error path exercises today. Not
`CPointer<ByteVar>?`: that would be a shape with no C# precedent in the repo.

`@NugetRuntimeApi`: yes, per ADR-127 every `@CName` function in the runtime is `public` (an
`internal @CName` is never exported from a dependency klib, verified in ADR-127's spike) and every
public declaration carries the marker. `export_nuget_runtime_version` follows the existing
`export_nuget_*` naming.

### 3. The trace, forward side

Alternative 1, exactly the class shown there. Emission:

| File | Change |
|---|---|
| `cir/CirModel.kt` | `data class CirRuntimeHelper(val libraryName: String) : CirDeclaration` |
| `cir/CirRenderer.kt` | dispatch arm `is CirRuntimeHelper -> renderRuntimeHelper(declaration)` |
| `cir/CirRuntimeRenderer.kt` (new) | the `NugetRuntime` class text |
| `cir/CirTranslator.kt` | `helpers.add(CirRuntimeHelper(context.libraryName))` **outside** the `if (needsMarshalHelper)` block at :592, so a scalar-only library still gets it |
| `ForwardAbiContract.kt` | `NUGET_RUNTIME_EXPORTS += "nuget_runtime_version"`, comment at :134 (`66` → `67`) |

`System.Runtime.CompilerServices` is only added to `usings` conditionally
(`CirTranslator.kt:680`), so the attribute is spelled fully qualified in the emitted text rather
than growing the using list.

Invariants the renderer must keep, each one a Tier 1 assertion:

- The variable gate is checked **before** the P/Invoke. Trace off means no native call from the
  initializer, so the moment the native library first loads is unchanged for every consumer
  today.
- The P/Invoke is wrapped in `try/catch`. A module initializer that throws surfaces as a load
  failure with a confusing stack; the first real bridge call throws the same
  `DllNotFoundException`/`EntryPointNotFoundException` anyway, so the trace prints the failure
  and returns.
- Tag `[nuget:interop]`, distinct from ADR-054's `[nuget]` (Kotlin) and `[nuget:shim]` (reverse
  C#), so an interleaved trace reads unambiguously. "loaded from" prints the `DllImport` library
  name; the resolved path is not cheaply available and the bullet does not ask for it.
- Same two variables, same accepted values (`1`/`true`/`all`), same stderr default and
  append-per-line file sink as ADR-054, so `docs/topics/registration-diagnostics.md`'s table stays
  true with one added row.

**Inferred** (docs, not spiked): `[ModuleInitializer]` on an `internal static void` parameterless
method in a non-generic class compiles on `net10.0` and runs before any other code in the
module, including under NativeAOT (`AotSmokeTest`). The reverse shims already ship the same
attribute on the same runtime (ADR-054, verified in production use), which is why it is not
spiked again here. If wrong, the symptom is a compile error in the consumer, not silent output.

ADR-054's own argument applies: nobody enables a trace for a bug they do not suspect, so this is
strictly weaker than an always-on check. Folding the runtime version into the message of an
`EntryPointNotFoundException`, or into a startup contract arm, is bullet 5's territory (the C# twin
gains a startup call there) and is not attempted here.

### 4. Test seams

Load-bearing: **`NUGET_RUNTIME_EXPORTS` must gain the name.** `ForwardAbiContract.assertMatches`
(`ForwardAbiContract.kt:140-142`) filters runtime names out of the C#-vs-Kotlin comparison; a C#
`DllImport` of `nuget_runtime_version` with the set unchanged is "an import with no Kotlin export"
and fails every generation. `ForwardAbiLegacyImportTest.kt:217` (`a runtime name imported by C sharp
needs no generated Kotlin export`) is the test that covers it; its comment at :211 says 66.

Files for the 67th name:

| File | Change |
|---|---|
| `nuget-runtime/src/nativeMain/.../NugetRuntime.kt` | the export |
| `nuget-runtime/build.gradle.kts` | the generated constant |
| `ForwardAbiContract.kt` | set + comment |
| `ForwardAbiLegacyImportTest.kt` | comment; optionally a case on the new name |
| `scripts/verify-runtime-exports.sh` | **no change**: it derives the list from `@CName("nuget_[a-z0-9_]*")` in the runtime source (verified) and the new name matches, so the outer `nm` proof extends for free |
| `tier1/Tier1RuntimeStub.kt` | **no change**: the generated Kotlin never references the export |
| `tier1/Tier1RuntimeVersionTest.kt` (new) | shipped as an **enum-only** fixture, not the scalar-only one first sketched here: any top-level function already opens `needsCoreMarshal`, so a scalar-only fixture cannot prove the helper survives outside that gate. The enum-only fixture asserts `Interop.cs` contains `EntryPoint = "nuget_runtime_version"` and `ModuleInitializer`, and that the env check precedes the P/Invoke textually; that is what pins the outside-the-gate placement |
| `test-library/build.gradle.kts` | `WriteFixtureVersions` gains a second `@Input` `runtimeVersion` set from `rootProject.version.toString()` (the root `gradle.properties` value, the same source ADR-127 names for the runtime coordinate; **not** the `nuget { publish { version = "1.0.0" } }` DSL field at :180, which is the fixture package's own version) and writes `<NugetRuntimeVersion>` beside `<TestLibraryVersion>` (verified: `build/FixtureVersions.props` is how `IntegrationTests.csproj` learns `$(TestLibraryVersion)` today, via `Directory.Build.props`) |
| `IntegrationTests/IntegrationTests.csproj` | `<ItemGroup><AssemblyMetadata Include="NugetRuntimeVersion" Value="$(NugetRuntimeVersion)" /></ItemGroup>` |
| `IntegrationTests/RuntimeVersionTests.cs` (new) | reads the expected value through `AssemblyMetadataAttribute` on the test assembly and asserts `NugetRuntime.Version` equals it |
| `scripts/verify.sh` | **superseded, not done**: the grep-the-trace-file approach sketched here was replaced by `IntegrationTests/RuntimeVersionTests.cs` calling `TraceLoaded()` directly and asserting the exact line in-process (see below); `verify.sh` itself is unchanged |

**Inferred** (not spiked): the `AssemblyMetadata` MSBuild item is consumed by the SDK's
`GenerateAssemblyInfo` into `[assembly: AssemblyMetadata("NugetRuntimeVersion", "...")]`. If
wrong, the test fails to find the attribute, loudly.

Why the trace assertion is an xunit test after all: the initializer has already run by the time any
test body executes, so nothing in-process can observe *that* firing, and the repo has no
child-process test precedent (`grep Process.Start IntegrationTests LeakTests` finds only framework
DLLs). What the shipped renderer does instead is read both variables inside `TraceLoaded()`, on
every call, rather than caching them: `TraceLoaded` is an ordinary `internal static void` that
happens to also carry `[ModuleInitializer]`, so `IntegrationTests/RuntimeVersionTests.cs` sets
`NUGET_INTEROP_TRACE`/`NUGET_INTEROP_TRACEFILE`, calls `NugetRuntime.TraceLoaded()` directly, and
asserts the exact line (and, with the variable unset, the absence of any line). Call-time reading
costs two environment lookups on a path taken once per module load, and in exchange the exact
message text, the `NUGET_INTEROP_TRACEFILE` sink and the off-path silence are covered by the same
suite that covers the rest of the bridge; `verify.sh` gains nothing here and stays unchanged. Two
things no test asserts: that the initializer actually *fires* at load (the Tier 1 test pins the
attribute's presence on the method; the attribute's semantics are the compiler's), and the
`Console.Error` default sink, since both xunit tests set the file variable to observe the output
at all.

## Consequences

- The runtime ABI is 67 names. ADR-127's "66 names, nothing else" is amended by this ADR; the
  `NugetRuntimeAbi1` anchor is unchanged (an added export is ABI-compatible).
- The forward `Interop.cs` gains its first `[ModuleInitializer]` and its first `NUGET_INTEROP_TRACE`
  reader. `docs/topics/registration-diagnostics.md` gains one row and one sample line.
- The shipped `NugetRuntime` class wraps the attribute in `#pragma warning disable CA2255` /
  `#pragma warning restore CA2255`. `Interop.cs` compiles into the *consumer's* library assembly, and
  `GeneratedBindingsCheck` (net8.0, warnings as errors) failed with `error CA2255: The
  'ModuleInitializer' attribute should not be used in libraries` without it. The reverse-shim
  `[ModuleInitializer]` sites carry no equivalent suppression; see the ROADMAP item this ADR's
  "Discovered alongside" note points at.
- `mingwX64` export of the new symbol inherits ADR-127's open Windows-leg caveat; not a new risk.
- Not verified, by construction of this session (project lock held, no build): the runtime and
  `Interop.cs` changes above are source-read only. The two places a wrong guess would show are
  both loud (unresolved constant at compile; missing attribute in the test), not silent.
- Deferred: a public C# `Version` member, a version in the `EntryPointNotFoundException` path,
  the reverse trace line carrying the version (blocked on the `nativeMain` visibility spike),
  and any always-on skew check (bullet 5).

# ADR-138: `packNuget` compiles the generated C# before it packs, through a `nugetCompileInterop` task that skips when `dotnet` is absent

## Status

Accepted

## Context

ROADMAP Phase 4: "`packNuget` does not compile the generated C# bindings, only verify's
`GeneratedBindingsCheck` does, so a CS0101/CS0128-class defect in `Interop.cs` (e.g. two nested
interfaces sharing a simple name naming the same bridge state class) ships silently through a green
`packNuget` until the next full verify. Discovered alongside [ADR-133](133-nested-types.md)."

The contract: a library author running `packNuget` gets a failing task, with the C# compiler's error
text, when the generated `Interop.cs` bindings do not compile.

What the repository does today (every claim here is **verified by reading source**):

- `packNuget` never compiles anything. `PackNugetTask.pack()` (`PackNugetTask.kt:62-119`) copies
  the native libraries into `runtimes/<rid>/native/`, copies every `.cs` file from
  `generatedCsDirs` into `contentFiles/cs/any/` (deduplicated by file name, last dir wins,
  `:94-103`), writes a `build/<id>.targets` that sets `AllowUnsafeBlocks` (`:177-184`), writes the
  `.nuspec` with `<contentFiles><files include="cs/any/**/*.cs" buildAction="Compile" />`
  (`:221-223`), and zips. The C# ships as **source** and is compiled inside the consumer's build.
  No `.dll` is produced by the plugin.
- `generatedCsDirs` is the KSP resources dir
  (`build/generated/ksp/<target>/<target>Main/resources`, `NugetPlugin.kt:428-436`, first supported
  target only) plus, when the project also binds a package, `nugetGenerateShims.csharpOutputDir`
  (`NugetPlugin.kt:494`). `dependencyVersions` is the exact per-bound-package version derived from
  `nugetRestore`'s `project.assets.json` (`NugetPlugin.kt:495-501`, `deriveResolvedVersions`).
- The only compile of that source is `scripts/verify.sh:71-72`: `dotnet build GeneratedBindingsCheck`.
  `GeneratedBindingsCheck/GeneratedBindingsCheck.csproj` has **no sources of its own**
  (`EnableDefaultCompileItems=false`), references the packed `TestLibrary` `.nupkg` at
  `$(TestLibraryVersion)` from `build/FixtureVersions.props`, and so compiles the package's
  `contentFiles` exactly as a consumer would, with these settings:

  | Setting | Value | Why it matters for a pack-time check |
  |---|---|---|
  | `TargetFramework` | `net8.0` | the lowest supported TFM; also what `NugetGenTask` and the `.nuspec` dependency group use |
  | `LangVersion` | `12.0` | pins the C# level the generated code may use (ADR-134 relies on C# 8 nested interface types) |
  | `Nullable` | `enable` | nullable annotation warnings (CS8600-class) surface |
  | `TreatWarningsAsErrors` | `true` | a warning we ship lands in every consumer's build |
  | `GenerateDocumentationFile` | `true`, `NoWarn` `CS1591` | ADR-064 amendment: malformed `<remarks>` (CS1570) is otherwise invisible |
  | `AllowUnsafeBlocks` | via the package's own `build/<id>.targets` | `Interop.cs` declares `internal static unsafe partial class NugetThunks` |
  | package references | `TestLibrary` only | so the bound dependencies (`MimeMapping 4.0.0`, `TestDependency`) come transitively through the `.nuspec` `<dependencies>` block; the generated C# needs nothing beyond the BCL and the bound packages |
  | `ImplicitUsings` | unset | `Interop.cs` carries its own `using` lines (`System`, `System.Runtime.InteropServices`, `System.Collections.Generic`, `System.Runtime.CompilerServices`, `System.Threading`, `System.Threading.Tasks`, `System.Threading.Channels`) |

- The .NET SDK is already a hard requirement for the **reverse** direction: `NugetRestoreTask.kt:27`
  and `NugetExtractApiTask.kt:52` both call `requireDotnet()` (`NugetTooling.kt:15-25`), which
  throws a `GradleException` naming `https://dot.net/download`. The forward direction has no such
  call, and the docs promise it: `docs/topics/prerequisites.md:7-12` says the .NET SDK is needed
  "**only if you bind a NuGet package into Kotlin**" and "Publishing Kotlin to NuGet needs no .NET
  SDK. `packNuget` writes the `.nupkg` itself with `java.util.zip` and never shells out to
  `dotnet`"; `docs/topics/getting-started.md:84-85` repeats "No .NET SDK is required for this step".
- A `scripts/verify.sh --fast` mode was proposed and rejected (`ROADMAP.md:234`,
  `docs/roadmap-archive.md:394`). This ADR is not that: it does not shorten verify, it moves one of
  verify's checks earlier, into the task every author already runs.
- Prior art, to the depth that settles it: Kotlin Swift Export and Kotlin/Native ObjC export emit
  headers and compile nothing on the consumer side. The Kotlin CocoaPods plugin does shell out
  (`pod install`, `xcodebuild` for the synthetic project), and the nearest in-repo precedent is
  `nugetReportDiagnostics` ([ADR-100](100-forward-diagnostic-delivery.md)): a separate task,
  registered in the same `afterEvaluate` block, that `packNuget` depends on and that exists only to
  surface something the pack itself would hide.

## Alternatives Considered

### 1. A separate `nugetCompileInterop` task that `packNuget` depends on, skipped with a warning when `dotnet` is not on `PATH` (chosen)

The task writes a throwaway `build/nuget-compile/interop-check.csproj` with `<Compile Include>`
items pointing at the same `.cs` files `packNuget` will stage, `GeneratedBindingsCheck`'s property
set, `AllowUnsafeBlocks`, and one exact-version `<PackageReference>` per bound dependency, then
runs `dotnet build` on it and fails with the compiler output when the exit code is non-zero.

**Pros:**

- Same verdict as `GeneratedBindingsCheck` (the spike below compiles the real `test-library`
  output clean under the identical property set, and a planted CS0101 fails it).
- Keeps the documented "no .NET SDK to publish" promise: a forward-only author without `dotnet`
  gets today's behaviour plus a warning, an author with it (every publisher of a NuGet package in
  practice, and this repository's CI) gets the failing task.
- Independently runnable (`./gradlew nugetCompileInterop`) and independently skippable
  (`-x nugetCompileInterop`), like `nugetReportDiagnostics`.
- No change to `PackNugetTask`'s inputs, outputs or `.nupkg` layout.

**Cons:**

- Adds roughly three seconds to every non-up-to-date `packNuget` when `dotnet` is present
  (measured below).
- The first `dotnet build` of a `net8.0` project on a newer SDK needs the `net8.0` reference pack;
  the SDK acquires it on demand. Observed, not proven by a fresh-machine run: the 10.0.300 SDK
  bundle at `/opt/homebrew/Cellar/dotnet/10.0.300/libexec/packs/Microsoft.NETCore.App.Ref` holds
  only `10.0.8`, and the `8.0.27` the spike compiled against lives in
  `~/.dotnet/packs/Microsoft.NETCore.App.Ref/`, which only the SDK's on-demand acquisition writes.
  `GeneratedBindingsCheck` has the same first-run dependency today.
- The check reads the generator's output directories, not the staged package, so it does not prove
  the `.nuspec` `contentFiles` route. `GeneratedBindingsCheck` keeps that job.

### 2. Compile inside `PackNugetTask.pack()` (rejected)

Fold the `dotnet build` into the pack action.

**Rejected:** `packNuget` becomes a task whose outcome depends on whether `dotnet` is installed,
which it cannot express as an input; an author cannot skip the compile without skipping the pack;
and the pack's own up-to-date state would be invalidated by a compile-only change. The
`nugetReportDiagnostics` precedent already chose a sibling task for the same reasons.

### 3. Hard-require `dotnet` for `packNuget`, through `requireDotnet()` (rejected for v1)

The same task, but failing instead of skipping when `dotnet` is absent.

**Rejected for v1:** it breaks a promise the docs make twice (`prerequisites.md:10`,
`getting-started.md:84`) for authors who publish only. The gain is small: an author with no .NET SDK
cannot consume the package either, so the defect surfaces at their first consumer build instead of
at pack. Revisit if the skip is found to hide a real defect in the field; an opt-in strict switch is
listed under open questions.

### 4. Reuse the reverse direction's `build/nuget-interop/interop.csproj` (rejected)

Add the `.cs` files as `<Compile>` items to the csproj `nugetGen` already writes and `nugetRestore`
already restores.

**Rejected:** that csproj only exists when `nuget { dependencies { } }` is non-empty
(`NugetPlugin.kt:42-43`), so the forward-only project, the one this item is about, has no csproj to
extend. It also carries `RuntimeIdentifiers` and no warnings-as-errors, and is the input of
`nugetRestore`'s `project.assets.json`, which `nugetExtractApi` reads; changing its contents changes
the reverse pipeline's inputs for a forward concern.

### 5. Invoke the compiler directly (`csc.dll` / Roslyn) instead of `dotnet build` (rejected)

**Rejected:** the reference-assembly set for `net8.0`, the bound packages' `lib/net8.0/*.dll`
paths and the implicit `global using` set are all things MSBuild resolves; reproducing that
resolution in Kotlin is a second NuGet client. `dotnet build` on a two-line csproj is the smallest
thing that gives the same verdict as the consumer's build.

### 6. Wire the task into `check` only, not into `packNuget` (rejected)

**Rejected:** the roadmap item is about the pack shipping a broken package; a `check` hook that
nobody runs before `packNuget` does not change that. `check` wiring is a possible addition (see
Consequences), not the mechanism.

## Decision

Register a `nugetCompileInterop` task (type `NugetCompileInteropTask`, group `nuget`) in the same
`afterEvaluate` block that registers `packNuget` (`NugetPlugin.kt:349-509`), and make `packNuget`
depend on it.

### The task

```kotlin
abstract class NugetCompileInteropTask : DefaultTask() {
  // The same producers packNuget stages: the KSP resources dir, plus nugetGenerateShims's
  // csharpOutputDir when the project binds a package.
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val generatedCsDirs: ConfigurableFileCollection

  // The exact resolved version per bound package, the same map packNuget writes into the
  // .nuspec <dependencies> block. Empty for a forward-only project.
  @get:Input
  abstract val dependencyVersions: MapProperty<String, String>

  // The extra feeds declared with dependency(id, source = ...), so a package that only exists on
  // a private feed restores here too. Same list nugetGen writes into RestoreSources.
  @get:Input
  abstract val dependencySources: ListProperty<String>

  // Where interop-check.csproj and its obj/ and bin/ land: build/nuget-compile/.
  @get:OutputDirectory
  abstract val projectDir: DirectoryProperty

  // Overridable so a unit test can point it at an empty directory and exercise the skip.
  @get:Internal
  abstract val dotnetSearchPath: Property<String>

  @get:Inject
  abstract val execOps: ExecOperations

  @TaskAction
  fun compile() {
    val dotnet: String? = findExecutable("dotnet", dotnetSearchPath.orNull)
    if (dotnet == null) {
      logger.warn(
        "w: [nuget] dotnet is not on PATH, so the generated C# bindings were not compiled " +
          "before packing. A binding that does not compile will only surface in a consumer's " +
          "build. Install the .NET SDK 8.0 or later from https://dot.net/download to check at pack."
      )
      return
    }

    val csFiles: List<File> = generatedCsFiles(generatedCsDirs.files)   // shared with PackNugetTask
    val dir: File = projectDir.get().asFile
    dir.mkdirs()
    val csproj = File(dir, "interop-check.csproj")
    csproj.writeText(generateCheckCsproj(csFiles, dependencyVersions.get(), dependencySources.get()))

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val result: ExecResult = execOps.exec { spec ->
      spec.commandLine(dotnet, "build", csproj.absolutePath, "--nologo", "-v", "quiet")
      spec.standardOutput = stdout
      spec.errorOutput = stderr
      spec.isIgnoreExitValue = true
    }

    if (result.exitValue != 0) {
      throw GradleException(
        "[nuget] The generated C# bindings do not compile (dotnet build exit code " +
          "${result.exitValue}). This is a generator defect: the package would fail in every " +
          "consumer's build. Compiler output:\n" +
          stdout.toString().trimEnd() + "\n" + stderr.toString().trimEnd()
      )
    }
  }
}
```

Two details in that action are load-bearing and were checked:

- **Capture `stdout`, not only `stderr`.** `NugetRestoreTask` captures stderr alone
  (`NugetRestoreTask.kt:29-33`). Copying that here would swallow the compiler errors: **verified**
  in the spike below, `dotnet build` writes `error CS0101 ...` and `Build FAILED.` to **stdout**;
  stderr is empty.
- **Exit code is non-zero on a compile error.** **Verified** in the spike: `exit=1`.

### The generated csproj

```kotlin
internal fun generateCheckCsproj(
  csFiles: List<File>,
  dependencyVersions: Map<String, String>,
  dependencySources: List<String>,
): String
```

renders, for the `test-library` fixture:

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net8.0</TargetFramework>
    <LangVersion>12.0</LangVersion>
    <Nullable>enable</Nullable>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
    <GenerateDocumentationFile>true</GenerateDocumentationFile>
    <NoWarn>$(NoWarn);CS1591</NoWarn>
    <RestoreSources>https://api.nuget.org/v3/index.json;/abs/test-library/build/nuget</RestoreSources>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="/abs/test-library/build/generated/ksp/macosArm64/macosArm64Main/resources/Interop.cs" />
    <Compile Include="/abs/test-library/build/nuget-interop/csharp/CatRegistration.cs" />
    <!-- one line per shim file -->
  </ItemGroup>
  <ItemGroup>
    <PackageReference Include="MimeMapping" Version="[4.0.0]" />
    <PackageReference Include="TestDependency" Version="[1.0.0-fixture.1789106759015]" />
  </ItemGroup>
</Project>
```

Rules for the rendering:

- Property set is `GeneratedBindingsCheck.csproj`'s, verbatim, plus `AllowUnsafeBlocks` (which the
  consumer gets from the package's `build/<id>.targets`, `PackNugetTask.kt:177-184`, and this
  csproj has no package to import it from). If `GeneratedBindingsCheck.csproj` changes, this
  function must change with it; a unit test pins each property so the drift is loud.
- One `<Compile Include>` per file, absolute path, after the same by-name deduplication
  `PackNugetTask.pack()` applies (`:97-99`). Extract that selection into an
  `internal fun generatedCsFiles(dirs: Iterable<File>): List<File>` in `PackNugetTask.kt` and call it
  from both tasks, so the check compiles exactly the set the pack stages.
- Exact-version `PackageReference` per bound package, `[v]` bracket syntax, the same string the
  `.nuspec` `<dependencies>` block uses (`PackNugetTask.kt:201`). A transitive dependency of a bound
  package (`TestDependency` for the fixture) is pulled by NuGet's restore, as it is for a consumer.
- `RestoreSources` only when `dependencySources` is non-empty, `nuget.org` first, same as
  `generateCsproj` in `NugetGenTask.kt:24-29`. Forward-only projects render no `RestoreSources` and
  no `PackageReference`.
- No `RuntimeIdentifier(s)`: the compile does not load the native library, so there is nothing to
  copy.

### Wiring (`NugetPlugin.kt`, inside the `packNuget` `afterEvaluate` block)

```kotlin
val compileInterop: TaskProvider<NugetCompileInteropTask> = project.tasks
  .register("nugetCompileInterop", NugetCompileInteropTask::class.java) { task ->
    task.group = "nuget"
    task.description = "Compiles the generated C# bindings with dotnet before packNuget stages them"
    task.generatedCsDirs.from(kspOutputDir)
    task.projectDir.set(project.layout.buildDirectory.dir("nuget-compile"))
    task.dotnetSearchPath.set(project.providers.environmentVariable("PATH"))
    task.dependencySources.set(extension.dependencies.mapNotNull { it.source }.distinct())
    task.dependsOn(kspTask)
    if (boundDeps.isNotEmpty()) {
      task.generatedCsDirs.from(nugetGenerateShims.flatMap { it.csharpOutputDir })
      task.dependencyVersions.set(/* the same nugetRestore.flatMap { deriveResolvedVersions } provider packNuget uses */)
      task.dependsOn(nugetGenerateShims)
    } else {
      task.dependencyVersions.set(emptyMap())
    }
  }

// in packNuget's configure block:
task.dependsOn(compileInterop)
```

The `boundDeps` branch already exists for `packNuget` (`NugetPlugin.kt:487-506`); hoist the
`nugetGenerateShims` / `nugetRestore` lookups and the `dependencyVersions` provider above both
`register` calls so the two tasks share one provider.

Ordering: `nugetCompileInterop` runs after `kspKotlin<Target>` and (when bound) `nugetGenerateShims`,
and before `packNuget`. It does not depend on the link tasks, so a broken binding fails before the
(slow) Kotlin/Native link when Gradle picks that order, and never after the `.nupkg` is written.

### `dotnet` absent

Warn and return, as shown. The warning is prefixed `w: [nuget]` like the plugin's other warnings
(`NugetPlugin.kt:363`, `PackNugetTask.kt:168`). The task is then neither `FAILED` nor `UP-TO-DATE`
next time (no outputs written), so installing the SDK later makes it run without `--rerun-tasks`.

### What was proven (spike, scratch directory, real `test-library` output)

A scratch csproj with the property set above, `<Compile Include>` on the real
`test-library/build/generated/ksp/macosArm64/macosArm64Main/resources/Interop.cs` (11k+ lines) and
`test-library/build/nuget-interop/csharp/*.cs` (47 shim files), `MimeMapping [4.0.0]` and
`TestDependency [1.0.0-fixture.1789106759015]` from `RestoreSources` `nuget.org;test-library/build/nuget`,
on SDK 10.0.300:

```
$ dotnet build check.csproj --nologo -v q
Build succeeded.
    0 Warning(s)
    0 Error(s)
Time Elapsed 00:00:02.90
```

**Verified:** the direct-`Compile` csproj reaches the same verdict as `GeneratedBindingsCheck` on the
same input, at three seconds.

Then with a copy of `Interop.cs` and a planted duplicate appended
(`namespace TestLibrary { public class Cat { } public class Cat { } }`):

```
$ dotnet build check.csproj --nologo -v q >out.txt 2>err.txt; echo exit=$?
exit=1
$ head out.txt
.../Broken.cs(29579,59): error CS0101: The namespace 'TestLibrary' already contains a definition for 'Cat'
.../Broken.cs(29579,38): error CS0101: The namespace 'TestLibrary' already contains a definition for 'Cat'
Build FAILED.
    0 Warning(s)
    2 Error(s)
$ cat err.txt
(empty)
```

**Verified:** the CS0101 class the roadmap item names fails the build, the exit code is 1, and the
error text is on stdout.

**Inferred, not proven by a fresh-machine run:** `dotnet build` of a `net8.0` project on a newer SDK
acquires the `net8.0` reference pack on first use and needs network for that once. The evidence is
the two `packs/` directories listed above; nobody has run the check on a machine without
`~/.dotnet/packs/Microsoft.NETCore.App.Ref/8.0.x`. If wrong, the check fails at restore with an
`NETSDK1045`/`NU1101`-class message on such a machine rather than compiling; that is a loud failure,
not a silent wrong verdict, and it is the failure `GeneratedBindingsCheck` would already give there.

**Inferred:** the spike's `Interop.cs` and shims in `build/` were produced by an earlier
`packNuget`, not by this session (the shared checkout was not built), so they may lag `main` by a
commit or two. That does not affect the mechanism claim; it affects only whether the specific text
compiled is the text `main` generates today, which `scripts/verify.sh` re-establishes.

## Test plan

Three seams, all on the plugin's JVM tests plus one line in verify:

1. **Pure function** (`NugetCompileInteropTaskTest`): `generateCheckCsproj` renders each
   `GeneratedBindingsCheck` property, `AllowUnsafeBlocks`, one absolute `<Compile Include>` per
   file, `[v]`-pinned `PackageReference`s, `RestoreSources` only when sources exist. Pins drift
   between the two csprojs.
2. **Wiring** (`ProjectBuilder`, same file or `NugetPluginCompileInteropWiringTest`):

   ```kotlin
   @Test
   fun `packNuget depends on nugetCompileInterop and both stage the same cs dirs`() {
     val project: Project = buildProject()   // KMP + plugin, one sharedLib target, as in NugetPluginPrebuiltRuntimesWiringTest
     project.extensions.getByType(NugetExtension::class.java).publish {
       packageId = "TestLibrary"; version = "1.0.0"; authors = "a"; description = "d"
     }
     project.evaluate()

     val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
     val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
     val deps: Set<Task> = packNuget.taskDependencies.getDependencies(packNuget)

     assertTrue(deps.contains(compile), "packNuget must depend on nugetCompileInterop")
     assertEquals(packNuget.generatedCsDirs.files, compile.generatedCsDirs.files)
     assertEquals(emptyMap(), compile.dependencyVersions.get())
   }
   ```

   A second case with `dependencies { dependency("TestDependency") { bind {} } }` asserts the
   shims dir is in `generatedCsDirs` and `nugetGenerateShims` is a dependency of the task.
3. **Skip** (task action, `@TempDir`): set `dotnetSearchPath` to an empty directory, call
   `compile()`, assert no exception and no `interop-check.csproj` written. Mirrors
   `NugetToolingTest`'s `requireDotnet` case.
4. **Real verdict**: `scripts/verify.sh` already runs `:test-library:packNuget`, which now runs the
   check against real generator output before `GeneratedBindingsCheck` does. No new script. A
   negative end-to-end (a fixture that plants a CS0101) is deliberately not added: it would have to
   break `test-library`, and seam 1 plus the spike cover the failure path.

## Consequences

- `packNuget` on a machine with `dotnet` fails, with the compiler's text, on any generated C# that
  `GeneratedBindingsCheck` would reject. The ADR-133 class of defect surfaces at pack, not at the
  next verify or at a consumer.
- `packNuget` on a machine without `dotnet` behaves as before plus one warning. The docs'
  "no .NET SDK to publish" promise holds; `docs/topics/prerequisites.md:7-12` and
  `getting-started.md:84-85` should say the check runs when the SDK is present.
- About three seconds added to a non-up-to-date `packNuget` with `dotnet` present; nothing when
  the generated C# and bound versions are unchanged (declared inputs and output).
- `GeneratedBindingsCheck` and `scripts/verify.sh` are unchanged. They still prove the
  `contentFiles` route through a real `.nupkg`, which this task does not.
- Files touched: `NugetCompileInteropTask.kt` (new), `NugetPlugin.kt` (register + `dependsOn`,
  hoist the bound-deps providers), `PackNugetTask.kt` (extract `generatedCsFiles`),
  `NugetCompileInteropTaskTest.kt` (new), plus the two doc pages and the ROADMAP tick.
- Deferred: an opt-in strict mode that fails instead of skipping when `dotnet` is absent; wiring
  the task into Gradle's `check` lifecycle; compiling against more than one TFM; a negative
  end-to-end fixture.

## Open questions

- Should the skip be opt-out (`nuget { publish { requireCompileCheck = true } }`, or a Gradle
  property) so a CI that forgot to install the SDK fails loudly instead of warning? Not decided
  here; the warning is the v1 answer.
- Does the check belong on the `check` lifecycle task as well as on `packNuget`? Harmless either
  way; left out to keep the wiring to one `dependsOn`.

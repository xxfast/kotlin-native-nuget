# ADR-093: Multi-RID package inputs: `prebuiltRuntimes` directory plus fail-fast native-lib validation

## Status

Accepted

## Context

`packNuget` produces one `.nupkg`, but only with the native libraries the packing host can link.
`test-library` declares both `mingwX64` and `macosArm64` (**verified**, `test-library/build.gradle.kts:92-107`);
a macOS host links both (Kotlin/Native cross-compiles mingwX64 from macOS, **verified**: CI packs
both `runtimes/osx-arm64` and `runtimes/win-x64` on `macos-latest`), but a Windows host cannot link
Apple targets, and no host links every RID in general. The PeopleInSpace 0.3.0 integration
(LIMITATIONS item 5) hit exactly this: a package packed on Windows carries only
`runtimes/win-x64/native/*.dll`, so the package is not publishable as one artifact and each CI host
verifies its own copy.

The task plumbing is already multi-RID (**verified**, read):

- `PackNugetTask.nativeLibDirs` is a `MapProperty<String, String>` keyed by RID
  (`PackNugetTask.kt:30-31`); the copy loop (`PackNugetTask.kt:61-72`) handles any number of RIDs.
- The plugin collects one lib dir per supported target (`NugetPlugin.kt:310-346`,
  `libDirs[rid] = sharedLib.outputDirectory.absolutePath`), adds the link-task dependency only when
  the link task is enabled (`NugetPlugin.kt:337`), and sets the whole map (`NugetPlugin.kt:374`).

Two gaps remain:

1. **No input path for a RID built elsewhere.** The DSL (`NugetExtension.kt`,
   `NugetPublishConfig.kt`) exposes nothing about RIDs or native inputs (**verified**, read). CI
   host B (say Windows) has no way to hand its `.dll` to host A (macOS) for a single pack.
2. **Silent skips.** The copy loop's `?: continue` (`PackNugetTask.kt:67`, **verified**) silently
   drops any RID whose directory has no `dll`/`dylib`/`so`. Today that one branch covers two very
   different situations: a target whose link task is disabled on this host (expected, the dir was
   never produced) and a target whose link ran but produced nothing where expected (a real
   failure). The consumer-visible symptom of the silent skip is the LIMITATIONS item itself.

### Prior art, to decision depth only

- NuGet has no first-class multi-arch merge: there is no `nuget merge`, `pack` assembles one
  package from staged files, and a `runtimes/{rid}/native/` tree with any set of RIDs is just a
  file layout inside one package (**inferred** from NuGet docs and the absence of any merge command
  in the CLI reference; not spiked, and not load-bearing: this ADR merges before packing, not
  after).
- Multi-RID native packages on nuget.org are assembled at pack time from per-RID artifacts staged
  by CI: each platform job builds its native library, uploads it as an artifact, and one packaging
  job downloads all artifacts into the layout and packs once. SQLitePCLRaw's
  `SQLitePCLRaw.lib.e_sqlite3` ships many RIDs in one package this way; SkiaSharp builds natives
  per platform in a CI matrix but chose the other shape, separate per-platform
  `SkiaSharp.NativeAssets.*` packages (**inferred** from the SkiaSharp build docs at
  https://deepwiki.com/mono/SkiaSharp/4.3-native-library-building and the nuget.org package pages;
  not spiked). Per-RID split packages are out of scope here: this plugin's package also carries the
  generated C# in `contentFiles/cs/any/`, and splitting it is a much larger design.

### Constraint from concurrent work

ADR-092 is adding `snapshot`/`versionPropsFile` to `NugetPublishConfig`. This ADR only adds
properties beside them; no interaction beyond both living in `publish { }`. When `snapshot = true`,
the two hosts mint different versions, so the uploaded artifact must be the `runtimes/` tree (which
is version-independent), not the versioned staging folder name; the consumer snippet below globs
accordingly.

## Alternatives Considered

### 1. `prebuiltRuntimes` directory in `publish { }` (chosen)

```kotlin
nuget {
  publish {
    packageId = "PeopleInSpace.Kotlin"
    version = "1.0.0"
    prebuiltRuntimes = file("build/prebuilt-runtimes")  // <dir>/<rid>/native/*.{dll,dylib,so}
  }
}
```

One optional `File` pointing at a directory laid out exactly like the `runtimes/` tree the task
already stages: `<dir>/<rid>/native/*.{dll,dylib,so}`.

**Pros:**
- Zero translation in CI: host B's `packNuget` (or just its link task) already produces this
  layout; host B uploads `build/nuget/<id>.<version>/runtimes`, host A downloads it to a directory
  and points at it. RID directory names are machine-produced by the other host's plugin run, never
  hand-typed, so the free-string typo risk of a per-RID DSL call mostly disappears.
- Scales to any number of RIDs with one declaration; adding a CI leg adds no DSL change.
- Matches how multi-RID native packages are assembled everywhere else (staged per-RID artifacts,
  one pack).

**Cons:** the layout contract must be validated and documented (done below); a user staging by
hand must reproduce the `<rid>/native/` nesting.

### 2. Repeated `nativeLib("win-x64", file(...))` calls (rejected)

Per-RID map-building calls in the DSL. Rejected for v1: every call hand-types a RID string (typo
surface the directory shape does not have), the CI scenario needs N declarations kept in sync with
the CI matrix, and it adds no capability over alternative 1 (a user with a single loose `.dll` can
stage `win-x64/native/` in one `Copy` task). Can be layered on later without breaking alternative 1
if a real need appears.

### 3. Both (rejected)

Two ways to say the same thing, two collision policies to define (map vs map, map vs dir), for no
v1 scenario alternative 1 does not cover. Narrowest option wins.

### 4. Merging finished `.nupkg`s (rejected, previously considered)

Unzip N host-built packages, union `runtimes/`, rezip. Rejected: the `.nupkg` is not reproducible
input (the psmdcp part name is a random UUID per pack, **verified** at `PackNugetTask.kt:169`), the
merged package's nuspec/contentFiles must be proven identical across hosts rather than assumed, and
NuGet has no supported merge tooling (**inferred**, above). Merging pre-built *inputs* before one
pack keeps a single authoritative nuspec and staging pass.

## Decision

### DSL

Add to `NugetPublishConfig` (a plain Kotlin class, ADR-044 pattern):

```kotlin
var prebuiltRuntimes: File? = null
```

Layout contract: `prebuiltRuntimes` names a directory whose immediate subdirectories are RIDs, each
containing `native/` with at least one `dll`/`dylib`/`so`. This is byte-for-byte the `runtimes/`
tree `packNuget` stages (**verified**: the staging loop writes `runtimes/$rid/native`,
`PackNugetTask.kt:62`).

### Task

Add to `PackNugetTask`:

```kotlin
@get:Optional
@get:InputDirectory
abstract val prebuiltRuntimesDir: DirectoryProperty
```

(**Inferred**, standard Gradle API, not spiked: `@Optional` on a `DirectoryProperty` input is the
stock way to model an absent optional input directory; the existing task uses the sibling
`@InputFiles`/`ConfigurableFileCollection` shape at `PackNugetTask.kt:33-34`.)

The pack action stages, in order: locally linked RIDs from `nativeLibDirs`, then prebuilt RIDs from
`prebuiltRuntimesDir`.

### Plugin wiring and the disabled-link distinction

Today `libDirs` gets every supported target's output dir whether or not its link task is enabled;
only the task *dependency* is gated on enabled-ness (`NugetPlugin.kt:335-339`, **verified**). That
is exactly what makes the silent skip necessary. Change: a target whose link task is **disabled**
on this host is excluded from `libDirs` at configuration time, with a lifecycle log line naming the
RID and pointing at `prebuiltRuntimes` as the way to still ship it. The existing
`sharedLib.linkTaskProvider.get().enabled` read at afterEvaluate is the mechanism already in use
one line below (**verified**). Consequently:

- `nativeLibDirs` now means "RIDs this host will actually produce", so validation on it can be
  strict.
- The current host-limited flows keep working unchanged: a Windows host with `macosArm64` declared
  logs and packs win-x64 only, exactly today's observable behaviour minus the silence.
- The `if (libDirs.isEmpty()) return@afterEvaluate` guard (`NugetPlugin.kt:346`, **verified**)
  becomes `if (libDirs.isEmpty() && pub.prebuiltRuntimes == null) return`, so a pack-only host
  (every local link disabled, everything prebuilt) still gets a `packNuget` task. The
  `supportedTargets.isEmpty()` guard above it is untouched, so the KSP wiring
  (`kspKotlin$firstTarget`) still has a target to hang off.

### Validation semantics (replacing the silent `continue`)

1. **Locally linked RID with zero native libs: error.** With disabled targets excluded upstream,
   every entry left in `nativeLibDirs` had its link task run as a dependency; an empty or missing
   dir means the build is broken, not host-limited. Message names the RID and the directory
   scanned. This is the repo's fail-fast convention applied to the exact silent skip that bit the
   PeopleInSpace integration.
2. **Prebuilt RID dir with zero native libs: error.** Every immediate subdirectory of
   `prebuiltRuntimes` must contain `native/` with at least one `dll`/`dylib`/`so`; a missing
   `native/` segment or an empty one fails with a message printing the expected layout
   (`<dir>/<rid>/native/*.dll|*.dylib|*.so`). Declared-but-empty is precisely the misconfiguration
   this feature exists to surface (a CI download step that fetched nothing).
   Non-directory entries at the top level (`.DS_Store` and friends) are ignored.
3. **Collision, same RID locally linked and prebuilt: error.** After the disabled-exclusion above,
   a collision means two hosts in the matrix are building the same RID, or a stale artifact from a
   previous run overlaps a fresh local build. Silently preferring either side ships a package whose
   provenance depends on iteration order; the message names the RID and both sources. The intended
   flow (local link disabled, prebuilt fills the RID) is not a collision because the disabled
   target never enters `nativeLibDirs`.
4. **Prebuilt RID name not in `KONAN_TO_RID` values: warning, not error.** The RID set NuGet
   accepts is open (runtime.json graph), and a prebuilt tree may legitimately carry a RID this
   plugin version cannot build (a newer plugin on the other host, or a hand-built artifact). A hard
   fail would need a plugin release to unblock; a typo'd RID still fails loudly at the consumer's
   restore/run, and the warning names the known set. (**Inferred** that the RID graph is open;
   from NuGet RID-catalog docs, not spiked, and not load-bearing: the choice of warning vs error
   does not change any staged byte.)

### Package output

The staged package and the `.nupkg` gain one `runtimes/<rid>/native/` folder per input RID, local
and prebuilt merged into the single existing nuspec/OPC pass. No nuspec changes: `runtimes/` assets
need no `<files>` entries beyond what packing already does today with multiple locally linked RIDs
(**verified**: the shipped multi-RID package from a macOS host already works this way; the nuspec
lists only contentFiles).

### Consumer API: the CI merge scenario

Windows leg (produces and uploads the artifact):

```yaml
# windows job
- run: ./gradlew :shared:packNuget
- uses: actions/upload-artifact@v4
  with:
    name: win-runtimes
    path: shared/build/nuget/*/runtimes/   # version-independent glob; contains win-x64/native/*.dll
```

macOS leg (packs the single publishable package):

```yaml
# macos job, needs: windows job
- uses: actions/download-artifact@v4
  with:
    name: win-runtimes
    path: shared/build/prebuilt-runtimes   # now: prebuilt-runtimes/win-x64/native/*.dll
- run: ./gradlew :shared:packNuget
```

```kotlin
nuget {
  publish {
    packageId = "PeopleInSpace.Kotlin"
    version = "1.0.0"
    prebuiltRuntimes = file("build/prebuilt-runtimes")
  }
}
```

Wrinkle when both hosts build win-x64 (macOS cross-compiles mingwX64): that is validation case 3,
an error by design. The CI matrix chooses one producer per RID; here the macOS leg would disable
its mingw target (or the Windows leg would ship a RID macOS cannot produce, e.g. a future
win-arm64). With `prebuiltRuntimes` unset, nothing changes for single-host multi-RID packs.

Expected nupkg layout:

```
PeopleInSpace.Kotlin.1.0.0.nupkg
├── runtimes/osx-arm64/native/libshared.dylib   (locally linked)
├── runtimes/win-x64/native/shared.dll          (prebuilt)
├── contentFiles/cs/any/*.cs
├── build/PeopleInSpace.Kotlin.targets
└── PeopleInSpace.Kotlin.nuspec
```

## Consequences

- One publishable artifact: a single host packs the union of every CI leg's native output; CI
  verification of the merged package can happen on any host that can run its own RID.
- The silent-skip behaviour is gone for produced targets; broken links and empty artifact
  downloads fail `packNuget` with the RID named, instead of shipping a package missing a platform.
- Host-limited targets (declared but not linkable here) keep working, now with a lifecycle log
  line instead of silence.
- `nuget-dsl.md` gains a `prebuiltRuntimes` row with the layout contract; `FEATURES.md`/
  `ROADMAP.md` updated on ship.
- `GeneratedBindingsCheck` is unchanged: the generated C# is host-independent and still comes from
  this host's KSP/shim runs.

### Implementation addendum

Two decisions the implementation made beyond what this ADR specifies:

- **An empty `prebuiltRuntimes` directory with no RID subdirectories at all is also an error**, not
  just a RID subdirectory whose `native/` is missing or empty. The ADR's validation semantics list
  covers a declared-but-empty *RID*; a declared-but-entirely-empty *root* (a CI download step that
  fetched nothing at all) is the same misconfiguration one level up, and fails the same way, naming
  the expected `<prebuiltRuntimes>/<rid>/native/*.dll|*.dylib|*.so` layout. Covered by
  `PackNugetMultiRidTest`'s `pack fails when the prebuilt tree has no rid subdirectory`.
- **One pre-existing test asserting the old silent skip was inverted to assert the new fail-fast.**
  `PackNugetTaskTest`'s `pack fails for a nativeLibDirs entry whose path does not exist` previously
  exercised the `?: continue` this ADR retires; it now asserts the `check(libs.isNotEmpty())` error
  instead, consistent with "locally linked RID with zero native libs: error" above.

### Out of scope

- Merging finished `.nupkg`s (rejected above; psmdcp non-reproducibility).
- Cross-compiling additional targets: the plugin packs what it is given, it never grows linking
  ability the Kotlin/Native toolchain does not have.
- Per-RID managed assemblies or per-RID split packages (`runtimes/<rid>/lib/`, SkiaSharp-style
  `NativeAssets.*` satellites).
- A first-class `stageRuntimes`/upload helper task; the staged `runtimes/` tree plus the CI glob
  above is sufficient for v1.
- A per-RID `nativeLib(rid, dir)` DSL (alternative 2); can be added later without breaking this.

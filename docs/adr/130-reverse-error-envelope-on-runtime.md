# ADR-130: Reverse, fold the reverse error envelope onto the runtime's `NugetError` by generating the reverse bindings into the per-target source set

## Status

Accepted

## Context

ADR-087's correction note records why the reverse bridge owns a second error envelope: KSP emits
`CNameExports.kt` into the per-target child source set (`macosArm64Main`), the reverse bindings
(`build/nuget-interop/kotlin/nativeMain`) are wired into `nativeMain`, the parent, and a parent
cannot see a child's declarations. So `NugetGenerateBindingsTask.kt` emits its own
`internal class NugetKotlinError` + `nugetKotlinError(Throwable)` (`NugetGenerateBindingsTask.kt:4501-4520`),
structurally identical to the forward `NugetError`/`buildError`, plus eight
`nuget_kotlin_error_*` accessor exports the C# `NugetKotlinErrors` shim reads.

ADR-127 moved the forward `NugetError`/`buildError` into the `nuget-runtime` klib, public behind
`@NugetRuntimeApi`, and left the fold as a follow-up (ROADMAP Phase 14, last bullet). The klib is
one module boundary away, not a child source set, so the "parent cannot see child" argument no
longer applies to the *class*. What still stands in the way is the plugin wiring: ADR-127 adds
the runtime as `api` on the **per-target** main source set (`${target.name}MainApi`,
`NugetPlugin.kt:241`), while the reverse bindings compile from `nativeMain` (`NugetPlugin.kt:192`).
Whether a `nativeMain` file can reference a declaration whose dependency is declared only on the
child is the load-bearing question, and it was spiked rather than assumed.

Constraints: the C# consumer surface (the `KotlinException` hierarchy, the `nuget_kotlin_error_*`
P/Invokes in `NugetRuntimeRegistration.cs`) stays unchanged; no new DSL; the plugin cannot assume
the author's script ever names `nativeMain`.

## The spike

**Verified**, 2026-09-12, Kotlin 2.4.10, Gradle 9.1.0, Windows 11, scratch two-module build
(`runtime`: `mingwX64()` + `linuxX64()`, `nativeMain` with `@RequiresOptIn(ERROR) SpikeApi`,
`@SpikeApi public data class SpikeError`, `@SpikeApi public fun buildSpikeError(Throwable)`;
`lib`: `mingwX64 { sharedLib }` + `linuxX64()`, default hierarchy template, one file that imports
`spike.runtime.SpikeError`/`buildSpikeError` under `@file:OptIn(SpikeApi::class)`; every
`SharedLibrary` `export(project(":runtime"))`). Tasks: `:lib:compileKotlinMingwX64
:lib:compileNativeMainKotlinMetadata :lib:linkReleaseSharedMingwX64`, `--continue`.

### Variant 1: dependency only on `mingwX64MainApi`, file in `nativeMain` (today's ADR-127 wiring)

```
===== variant=child
PROJECTS_EVALUATED(child) nativeMain=source set nativeMain dependsOn(mingwX64Main)=[mingwMain]
e: .../lib/src/nativeMain/kotlin/lib/Use.kt:1:13 Unresolved reference 'spike'.
e: .../lib/src/nativeMain/kotlin/lib/Use.kt:3:8 Unresolved reference 'spike'.
e: .../lib/src/nativeMain/kotlin/lib/Use.kt:4:8 Unresolved reference 'spike'.
e: .../lib/src/nativeMain/kotlin/lib/Use.kt:5:27 Unresolved reference 'SpikeError'.
e: .../lib/src/nativeMain/kotlin/lib/Use.kt:5:40 Unresolved reference 'buildSpikeError'.
e: .../lib/src/nativeMain/kotlin/lib/Use.kt:5:97 Unresolved reference 'type'.
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':lib:compileNativeMainKotlinMetadata'.
> Compilation finished with errors
```

`:lib:compileKotlinMingwX64` and `:lib:linkReleaseSharedMingwX64` **succeeded** in the same run
(the platform compilation folds `nativeMain` into the `mingwX64` compilation, whose classpath has
the child's `api`); only the shared-source-set **metadata** compilation fails. And that task is on
the `assemble` path:

```
===== assemble dry-run (child)
:lib:compileCommonMainKotlinMetadata SKIPPED
:lib:transformNativeMainDependenciesMetadata SKIPPED
:lib:compileNativeMainKotlinMetadata SKIPPED
:lib:allMetadataJar SKIPPED
:lib:linkDebugSharedMingwX64 SKIPPED
:lib:linkReleaseSharedMingwX64 SKIPPED
```

So "fold and keep the wiring" would pass `packNuget` (what `scripts/verify.sh` runs; it depends on
the link tasks only) and break `./gradlew build` / `assemble` for every consumer, and paint the
reverse bindings red in the IDE. The expected answer ("no, dependencies flow parent to child") is
right for the metadata compilation and wrong for the platform compilation; the split is what
makes the failure easy to miss.

### Variant 2: dependency on `nativeMain`'s `api`

```
===== variant=native   (sourceSets.getByName("nativeMain") inside kotlin {})
* What went wrong:
KotlinSourceSet with name 'nativeMain' not found.

===== variant=native-accessor   (sourceSets { nativeMain.dependencies { api(project(":runtime")) } })
AFTER_EVALUATE(native-accessor) nativeMain=source set nativeMain
PROJECTS_EVALUATED(native-accessor) nativeMain=source set nativeMain dependsOn(mingwX64Main)=[mingwMain]
EXIT=0

===== variant=native-maybe   (sourceSets.maybeCreate("nativeMain").dependencies { api(project(":runtime")) })
AFTER_EVALUATE(native-maybe) nativeMain=source set nativeMain
PROJECTS_EVALUATED(native-maybe) nativeMain=source set nativeMain dependsOn(mingwX64Main)=[mingwMain]
EXIT=0
```

All three tasks green when the source set exists. `nativeMain` does **not** exist at
configuration time and does not exist in a build-script `afterEvaluate` either, unless the script
itself named it (the typesafe accessor and `maybeCreate` both create it; the default hierarchy
template wires it later):

```
===== child AFTER_EVALUATE probe   (script never references nativeMain)
AFTER_EVALUATE(child) nativeMain=null
PROJECTS_EVALUATED(child) nativeMain=source set nativeMain dependsOn(mingwX64Main)=[mingwMain]
```

### Variant 3: dependency on `commonMain`'s `api`

```
===== variant=common      (all targets native)
EXIT=0

===== variant=common-jvm  (plus jvm())
          - Variant 'mingwX64ApiElements' declares a library, preferably optimized for non-jvm:
              - Incompatible because this component declares a component for use during 'kotlin-api', as well as attribute 'org.jetbrains.kotlin.platform.type' with value 'native' and the consumer needed a component for use during compile-time, as well as attribute 'org.jetbrains.kotlin.platform.type' with value 'jvm'
          - Variant 'metadataApiElements' declares a library, preferably optimized for non-jvm:
              - Incompatible because this component declares a component for use during 'kotlin-metadata', as well as attribute 'org.jetbrains.kotlin.platform.type' with value 'common' and the consumer needed a component for use during compile-time, as well as attribute 'org.jetbrains.kotlin.platform.type' with value 'jvm'
* Try:
> No matching variant errors are explained in more detail at https://docs.gradle.org/9.1.0/userguide/variant_model.html#sub:variant-no-match.
BUILD FAILED in 803ms
```

Green only while every target is native; a JVM target fails at dependency resolution.

### Variant 4: file in a per-target srcDir on both children, dependency on each child's `api`

```
===== variant=per-target (file in src/reverse, srcDir on BOTH mingwX64Main and linuxX64Main)
> Task :lib:compileKotlinMingwX64
> Task :runtime:compileKotlinLinuxX64
> Task :lib:compileKotlinLinuxX64
> Task :lib:compileCommonMainKotlinMetadata NO-SOURCE
> Task :lib:compileNativeMainKotlinMetadata
> Task :lib:linkReleaseSharedMingwX64
BUILD SUCCESSFUL in 8s
EXIT=0
lib/build/bin/mingwX64/releaseShared/: spike.def  spike.dll  spike_api.h
```

The same directory as `srcDir` of two sibling leaf source sets, both compilations and the
`nativeMain` metadata compilation green, no KGP warning printed, and `spike.dll` linked. This is
exactly the wiring ADR-127 already has for the runtime dependency, plus the reverse directory
moved one level down.

## Correction, 2026-09-12: Alternative 1 is infeasible; shipped as an expect/actual seam instead

Alternative 1 was implemented (plugin srcDir moved into the per-target loop; `nugetRuntimeContent()`
folded onto the runtime's `NugetError`/`buildError`), the plugin unit tests went green (512 passed,
including a new `NugetPluginReverseSrcDirWiringTest` and two `NugetGenerateBindingsTaskTest` cases),
and then `scripts/verify.sh --plugin` failed at the FIXTURE's own platform compile:

```
e: .../test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/structs/StructsSample.kt:122:17 Unresolved reference 'Profile'.
e: .../StructsSample.kt:122:27 Unresolved reference 'Cattery2'.
e: .../StructsSample.kt:144:9 Unresolved reference 'CatMood'.
e: .../StructsSample.kt:165:43 Unresolved reference 'Point'.
> Task :test-library:compileKotlinMingwX64 FAILED
```

**Root cause.** The spike's `lib` module had no author-written file in `nativeMain` that referenced
the generated code; it only asked whether generated code could reference the runtime. The reverse
bindings are **consumer-facing types** (`test.structs.Point`, `test.structs.Profile`, the generated
`object`s and `interface`s a bound package produces), and a consumer writes the Kotlin that calls
them in the shared `nativeMain` source set — `test-library/src/nativeMain/.../StructsSample.kt` does
exactly that, with `import test.structs.Profile`. Moving the generated directory down to
`macosArm64Main`/`mingwX64Main` makes those types invisible to the author's own parent-source-set
code. The "a parent cannot see a child" boundary is symmetric, and this ADR only measured one side
of it.

No placement variant rescues Alternative 1:

- the same directory as a `srcDir` of BOTH `nativeMain` and its children is a redeclaration in the
  platform compilation (`nativeMain` folds into it);
- splitting the generated files (stubs in `nativeMain`, `internal` helpers per-target) fails because
  the generated stubs reference the `internal` package throughout: in the fixture's own output, 43
  non-internal generated files name `nugetCall`, 41 name `NugetObjectHandle`, and 4-5 each name
  `nugetKotlinError`, `nugetTransferScope`, `nugetHandleOut` and `nugetKotlinString`.

So the fold requires the runtime's `NugetError`/`buildError` to be named from somewhere the
per-target `api` reaches, without moving any consumer-facing type. Alternative 2 (runtime `api` on
`nativeMain`) is not that place: its stated blocker is now **verified** rather than inferred —
`nuget-runtime/build.gradle.kts` declares exactly `macosArm64()`, `macosX64()`, `linuxX64()`,
`mingwX64()`, so an `iosArm64`-beside-`macosArm64` consumer with the runtime on `nativeMain`'s `api`
would fail dependency resolution the way Variant 3's `common-jvm` did.

### Shipped instead: Alternative (e), the expect/actual seam — zero plugin-wiring change

The generator already crosses this exact boundary: `internal expect fun freeManagedString`
(`NugetGenerateBindingsTask.kt:4159`) in the `nativeMain` file, with `actual`s in the `mingwMain`
and `posixMain` files (`:4170`, `:4183`) that the plugin already adds to `${target.name}Main` —
the source set where ADR-127 declares the runtime. The envelope rides the same seam:

- **`nativeMain/…/NugetRuntime.kt`** keeps one line, `internal expect fun
  nugetKotlinError(t: Throwable): COpaquePointer`. The class `NugetKotlinError`, the mint body,
  `at`, `error()`, the eight `nuget_kotlin_error_*` accessors and `nuget_kotlin_error_free` are
  deleted from it, and it names nothing from the runtime klib, so
  `compileNativeMainKotlinMetadata` stays clean (this ADR's Variant 1 failure cannot recur).
  Every generated call site keeps calling `nugetKotlinError(t)` unchanged.
- **`mingwMain|posixMain/…/NugetKotlinErrors.kt`** (one template emitted twice, as
  `NugetInterop.kt` is) carries `@file:OptIn(…NugetRuntimeApi::class)`, the `NugetError`/
  `buildError` imports, `internal actual fun nugetKotlinError(t) = StableRef.create(buildError(t))
  .asCPointer()`, and the eight accessors plus `nuget_kotlin_error_free` retyped to `NugetError`.
  It reads `nugetKotlinString` from `NugetRuntime.kt`, its parent, which is the direction that
  works. It is emitted under exactly the condition that emits the `expect`, so the pair cannot
  come apart.
- Allocation is unchanged: raw `StableRef`, never `NugetHandles.retain` (see "Allocation, pinned"
  below). `nuget_live_handles` and the `LeakTests` baselines do not move.
- **Not changed:** `NugetPlugin.kt` (no srcDir move, so this ADR's Decision paragraph on it does
  not ship), ADR-127's `api` + `export()` wiring, the C# `NugetKotlinErrors` shim, the
  `nugetKotlinString` wire, the `KotlinException` family, the runtime module.
- Cost: the `expect` has no `actual` for a native target outside `KONAN_TO_RID`, exactly as
  `freeManagedString` already has none — a pre-existing limitation of the reverse route, not a new
  one.

**Verified, 2026-09-12**: `./gradlew -p nuget-plugin test` 509 passed; `scripts/verify.sh --plugin`
green (`OK: all 67 nuget-runtime exports present`, IntegrationTests 1723 passed, LeakTests 28
passed — the outer proof is the unchanged reverse-exception coverage in
`IntegrationTests/MenagerieRoundTripTests.cs`); `./gradlew :test-library:compileNativeMainKotlinMetadata`
BUILD SUCCESSFUL; and in the fixture's generated output, zero `NugetHandles.retain(` anywhere and
one `buildError(` per per-target `NugetKotlinErrors.kt`.

**Separately observed, independent of the fold.** The latent wiring bug this ADR inferred at
`NugetPlugin.kt:192` has an observed symptom. **Verified**: in a `ProjectBuilder` project whose
script never names `nativeMain` (`macosArm64 { binaries { sharedLib {} } }` plus one `bind {}`),
after `evaluate()` the source set `nativeMain` **does** exist and does **not** carry
`build/nuget-interop/kotlin/nativeMain` as a `srcDir` — the shipped wiring leaves the reverse
bindings in no source set at all. **Inferred, not isolated**: the mechanism. Two explanations fit
the same observation — `findByName("nativeMain")` returned null when the plugin's `afterEvaluate`
ran (this ADR's guess), or it returned a source set whose `srcDirs` the default hierarchy template
later re-created/replaced. They have different fixes, so the next person should isolate before
choosing between the candidates (`sourceSets.configureEach`/`whenObjectAdded` on the name,
`maybeCreate`, or wiring per target as Alternative 1 does).

`test-library` hides the symptom either way because `test-library/build.gradle.kts:110` names
`nativeMain` itself. An author who does not gets no reverse sources and no error until C# startup
cannot find `nuget_runtime_register`. Not fixed here; it wants its own test.

## Alternatives Considered

### 1. Generate the reverse bindings into the per-target source set (rejected, see Correction)

Spiked and initially chosen; **infeasible**, per the Correction above: the fixture's own
`test-library:compileKotlinMingwX64` failed with `Unresolved reference 'Profile'` /
`'Cattery2'` / `'CatMood'` / `'Point'` once the reverse bindings' directory moved down to
`mingwX64Main`, because the reverse bindings are consumer-facing types an author's own
`nativeMain` code imports (`StructsSample.kt`'s `import test.structs.Profile`), and a parent
source set cannot see a child's declarations. Kept below verbatim for the spike record.

`NugetPlugin.kt:192-194` (the `nativeMain` srcDir) moves into the per-target loop at
`NugetPlugin.kt:196-201`: `kotlinOutputDirLiteral.map { it.dir("nativeMain") }` is added to
`${target.name}Main` beside the existing `mingwMain`/`posixMain` subdir, for every
`KONAN_TO_RID` target. The generated files keep their `nativeMain/...` relative paths (it is a
directory name; eight `NugetGenerateBindingsTaskTest` tests assert `startsWith("nativeMain/")`).
ADR-127's `${target.name}MainApi` + `export()` wiring stays byte for byte, so the reverse files
compile in the one source set where the runtime is already declared (Variant 4, verified).

Pros: one plugin file, five lines; keeps ADR-127; the "parent cannot see child" boundary stops
mattering for the reverse side at all (it could reference KSP output too, though nothing needs
that); a target outside `KONAN_TO_RID` never compiles the reverse bindings any more (today it
does, through the shared `nativeMain`, and those files reference `nugetKotlinString` which lives
in the `posixMain`/`mingwMain` subdir the plugin only adds to RID targets, so that path was
already broken: **inferred**, not spiked). Cons: with two RID targets the reverse Kotlin is
compiled twice instead of once plus a metadata pass, a few seconds; the IDE attributes the
directory to one leaf module per file.

Also fixes a latent wiring bug the spike surfaced. `NugetPlugin.kt:192` calls
`kotlin.sourceSets.findByName("nativeMain")` inside the `project.afterEvaluate` registered at
`NugetPlugin.kt:42`. The probe above shows a build-script `afterEvaluate`, registered later than
the plugin's, sees `nativeMain=null` unless the author's script names it. `test-library` does
(`nativeMain.dependencies { ... }`, `test-library/build.gradle.kts:110`), so the fixture never
exposed it; an author who does not gets no reverse srcDir at all, and no error until C# startup
cannot find `nuget_runtime_register`. **Inferred for the plugin** (the probe was the build
script's hook, not the plugin's; both are registered after the KMP plugin's own). Under (1) the
lookup is `findByName("${target.name}Main")`, which the per-target subdir wiring at line 199
already relies on and Variant 4 proves.

### 2. Move the runtime `api` to `nativeMain` (rejected)

`kotlin.sourceSets.maybeCreate("nativeMain").dependencies { api(runtimeDep) }` works (Variant 2,
`native-maybe`), but `nativeMain` is inherited by **every** native target that refines it. A
consumer with `iosArm64()` beside `macosArm64()` would have the runtime, which publishes no iOS
variant, on its compile classpath and fail at dependency resolution exactly as `common-jvm` did
(**inferred** from Variant 3's failure shape; the iOS case was not spiked, Windows cannot compile
Apple targets). `maybeCreate` also invents a `nativeMain` for an author who disabled the default
hierarchy template or wired `dependsOn` by hand, an orphan source set KGP warns about and no
compilation includes. Two files (`NugetPlugin.kt`, `NugetPluginRuntimeExportWiringTest.kt`), but
it changes an ADR-127 decision and widens the runtime's reach to targets it does not support.

### 3. Move the runtime `api` to `commonMain` (rejected)

Variant 3: breaks every consumer with a JVM (or JS) target at configuration time. Out.

### 4. Keep the copy (status quo, rejected)

Zero risk, but the ROADMAP bullet exists because two structurally identical envelope classes
are one more place for the error mapping to drift, and every reverse `nugetKotlinError` site
would keep a private cycle guard that `buildError` already owns.

### 5. Delete the `nuget_kotlin_error_*` accessors and P/Invoke the forward `nuget_error_*` (rejected)

ADR-127 made the forward accessors unconditional, so the eight reverse exports are redundant in
principle. In practice the two wires differ: the forward `nuget_error_*` return a Kotlin `String`
that `CirErrorRenderer.kt:27-33` reads with `Marshal.PtrToStringUTF8` and never frees; the reverse
`NugetKotlinErrors.Read` reads a `nugetKotlinString` CoTaskMem pointer and frees it through
`nuget_kotlin_string_free` (`NugetGenerateShimsTask.kt:2354-2359`). Deleting means rewriting the
`NugetKotlinErrors` block (`NugetGenerateShimsTask.kt:2319-2395`: eight `DllImport`s, `Read`,
and `Native_free` which would have to become `nuget_dispose`, dragging in the allocation question
below), roughly 40 emitter lines and the shim tests at `NugetKotlinBridgeGenerationTest.kt:1287-1290`,
for no consumer-visible change. Keep: zero C# lines.

## Decision

Alternative (e), the expect/actual seam described under "Shipped instead" above, **not** Alternative 1
(the per-target srcDir move the spike originally targeted; see "Alternatives Considered" for why it
does not ship). Concretely:

- **`nativeMain/…/NugetRuntime.kt`** keeps one line, `internal expect fun
  nugetKotlinError(t: Throwable): COpaquePointer`. The class `NugetKotlinError`, the mint body,
  `at`, `error()`, the eight `nuget_kotlin_error_*` accessors and `nuget_kotlin_error_free` are
  deleted from it, and it names nothing from the runtime klib, so `compileNativeMainKotlinMetadata`
  stays clean. Every generated call site keeps calling `nugetKotlinError(t)` unchanged.
- **`mingwMain|posixMain/…/NugetKotlinErrors.kt`** (one template emitted twice, as
  `NugetInterop.kt` is) carries `@file:OptIn(…NugetRuntimeApi::class)`, the `NugetError`/
  `buildError` imports from `io.github.xxfast.kotlin.native.nuget.runtime`, `internal actual fun
  nugetKotlinError(t) = StableRef.create(buildError(t)).asCPointer()`, and the eight accessors plus
  `nuget_kotlin_error_free` retyped to `NugetError`. The runtime's `NugetError` has the same four
  properties, `type`, `message`, `stackTrace`, `cause: NugetError?`, so the accessor bodies do not
  change. It reads `nugetKotlinString` from `NugetRuntime.kt`, its parent, which is the direction
  that works. It is emitted under exactly the condition that emits the `expect`, so the pair cannot
  come apart.

**Allocation, pinned.** The reverse envelope stays a raw `StableRef`, **not** `NugetHandles.retain`.
`NugetHandles.retain` would count it in `nuget_live_handles`, and `nuget_kotlin_error_free` would
then have to call `NugetHandles.release` (or the C# shim would have to switch `Native_free` to
`nuget_dispose`); either moves `LeakTests` baselines for a change that is supposed to be
invisible. Raw `StableRef` is what ships today.

**Not changed.** `NugetPlugin.kt` (no srcDir move); the eight `nuget_kotlin_error_*` exports and
`nuget_kotlin_error_free`; the `nugetKotlinString` wire; the whole C# `NugetKotlinErrors` shim; the
thrown `KotlinException` family; the runtime module; ADR-127's `api` + `export()` wiring.

## Test plan

Written against Alternative 1, before the Correction. Items 2 and 3 describe a plugin-wiring test
and an `NugetPlugin.kt` change that did not ship (`NugetPlugin.kt` is unchanged, per the Decision
above); left here as the record of what was planned, not what ran. Item 1's assertions on the
generated shape and item 4's outer proof are still accurate for the shipped design.

1. `NugetGenerateBindingsTaskTest`: a new test (`NugetRuntime kt reads the runtime's NugetError
   instead of owning a copy`) asserting the generated `NugetRuntime.kt` contains
   `import io.github.xxfast.kotlin.native.nuget.runtime.NugetError`,
   `import io.github.xxfast.kotlin.native.nuget.runtime.buildError`,
   `NugetRuntimeApi::class` inside `@file:OptIn(`, and does **not** contain `class NugetKotlinError`;
   and that the eight `@CName("nuget_kotlin_error_` strings are still present (the C# shim's
   contract). The existing tests at `NugetGenerateBindingsTaskTest.kt:460-526` asserting
   `relativePath.startsWith("nativeMain/")` stay as they are.
2. `NugetGenerateBindingsTaskWiringTest` (or a new `NugetPluginReverseSrcDirWiringTest` mirroring
   `NugetPluginKspArgsWiringTest.buildProjectWithSharedLib`): a `ProjectBuilder` project with
   `macosArm64 { sharedLib }` and one `bind {}` whose script **never** references `nativeMain`;
   after `evaluate()`, `macosArm64Main`'s `kotlin.srcDirs` contains `.../nuget-interop/kotlin/nativeMain`
   and `.../posixMain`; `nativeMain` (if it exists at all) does **not** carry the reverse srcDir;
   an `iosArm64()` target carries neither. This is the test that would have caught the
   `findByName("nativeMain")` timing bug.
3. `NugetPluginRuntimeExportWiringTest`: unchanged (the `api` stays on `macosArm64MainApi`).
4. Outer proof, unchanged: `IntegrationTests/MenagerieRoundTripTests.cs`
   `KotlinNoVacancy_DescribeThrows_ReachesCSharpAsACatchableException` and
   `KotlinNoVacancy_LegsOnly_NonThrowingSiblingSlotStillWorks` (the ADR-087 stage 2 slot path
   through `nuget_kotlin_error_*`), plus `HouseholdRoundTripTests.cs` and
   `ExceptionTypeMappingTests.cs` for the forward family the reverse shim throws. `LeakTests`
   unchanged because the allocation is unchanged. `scripts/verify.sh` (`packNuget`) never runs
   `compileNativeMainKotlinMetadata`, so a `./gradlew :test-library:build` (or `assemble`) run
   once, by hand, is the check that Variant 1's failure is really gone in the fixture.

## Consequences

- One envelope class on the Kotlin side; the reverse bridge's error read is `buildError`, the same
  function every forward export uses.
- **Superseded by the Correction, kept for the record of what Alternative 1 would have meant:** "the
  reverse bindings become per-target sources" and "`nativeMain` is no longer touched by the plugin at
  all" did not ship; `NugetPlugin.kt` is byte-for-byte unchanged (see Decision). The reverse bindings
  still compile from the shared `nativeMain`, same as before this ADR.
- ADR-087's correction note ("reverse bindings live in nativeMain, the parent") is **not** superseded:
  the shipped design keeps the reverse files in `nativeMain`, exactly as ADR-087 described; only the
  error envelope's own class definition moved, via the expect/actual seam, to the per-target files
  that already existed for `freeManagedString`.
- The `expect fun nugetKotlinError` has no `actual` for a native target outside `KONAN_TO_RID`, a
  pre-existing limitation of the reverse route (`freeManagedString` already has none), not a new one.
- Not verified: the `iosArm64`-beside-`macosArm64` consumer under Alternative 2 (the reason it is
  rejected is inferred from the JVM failure shape); Apple-target behaviour of the per-target-srcDir
  spike (spiked on `mingwX64` + `linuxX64` only, moot for the shipped design since it never ships that
  wiring).

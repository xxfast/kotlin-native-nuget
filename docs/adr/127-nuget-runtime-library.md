# ADR-127: Ship the fixed `nuget_*` ABI once, as an `export()`ed `nuget-runtime` Kotlin/Native library

## Status

Accepted

## Context

Every project that applies the plugin gets the same ~500 lines regenerated into its
`CNameExports.kt`: `NugetHandles`, `NugetError`/`buildError`, the scalar wrap/unwrap exports, the
collection, callback and coroutine exports, the .NET ticks conversions. In the fixture that is 64
`@CName("nuget_*")` exports out of a 22,718-line file (verified, `grep -o '@CName("nuget_[a-z0-9_]*")'`
on `test-library/build/generated/ksp/macosArm64/.../CNameExports.kt`, 2026-09-11). The C# side
declares the same 64 `DllImport`s (verified, `EntryPoint = "nuget_..."` count in the packed
`Interop.cs`). The emitter vocabulary is two larger: `nuget_func2_invoke` and `nuget_func3_invoke`
exist in `exports/GenericClassExports.kt` but no fixture declaration needs them.

The block is regenerated per project only because the generator has nowhere else to put it, and
that has a cost beyond size. The block is emitted **conditionally**
(`NugetProcessor.kt:1880-1990`: `needsHelpers`, `needsListSupport`, `needsCollectionParamWrap`,
`needsScopeHelpers`, `needsInstantSupport`, `needsDurationSupport`, the lambda arity sets), and
four ADRs so far exist because a gate missed a case and the consumer hit
`EntryPointNotFoundException` at first call: ADR-068, ADR-071, ADR-075, ADR-124. The gating is a
recurring defect class that a fixed, always-present ABI removes by construction.

Phase 14 (ROADMAP.md) asks for the block to become a `nuget-runtime` Kotlin/Native library that the
plugin adds and `export()`s. This ADR covers bullets 1 to 3: the `export()` spike, the module, the
plugin wiring and the skew guard. Bullet 4 (`launchForCSharp`) and bullet 5 (the C# twin) are out of
scope and are mentioned only where a decision here constrains them.

Constraints:

- The C# consumer must see the same P/Invoke surface as today: the same 66 C names (64 used by the fixture), same
  signatures. Nothing in `Interop.cs` changes.
- No new DSL for the author. Applying the plugin is the whole opt-in.
- The plugin already resolves the KSP processor as `findProject(":nuget-processor")
  ?: "io.github.xxfast:nuget-processor:$PLUGIN_VERSION"` (`NugetPlugin.kt:217-218`); the runtime
  must resolve the same way, in-repo when composite, published coordinate otherwise.
- `NugetHandles` is `internal` today. Generated code in the consumer module cannot call an
  `internal` declaration from another module, so the runtime has to commit to a public surface.

## The spike: `@CName` in a dependency klib reaches the `.dylib` only through `export()`

**Verified**, 2026-09-11, Kotlin 2.4.10, `macosArm64`, in a scratch two-module build
(`runtime` with `@CName("nuget_spike_ping") public fun ping()` and
`@CName("nuget_spike_internal") internal fun pingInternal()`; `lib` with `sharedLib { baseName =
"spike" }` calling `PublicHandles.peek()` from `runtime`). Four variants, `nm -gU` on the linked
`libspike.dylib`:

```
===== variant=none          (api(project(":runtime")), no export())
--- nm -gU lib/build/bin/macosArm64/releaseShared/libspike.dylib (none)
000000000003382c T _spike_own
--- header grep (none/release)
67:extern libspike_KInt spike_own();

===== variant=export        (api(project(":runtime")) + export(project(":runtime")))
--- nm -gU lib/build/bin/macosArm64/releaseShared/libspike.dylib (export)
00000000000338c0 T _nuget_spike_ping
0000000000033930 T _spike_own
--- header grep (export/release)
70:extern libspike_KInt nuget_spike_ping();
71:extern libspike_KInt spike_own();

===== variant=impl          (implementation(project(":runtime")), no export())
--- nm -gU lib/build/bin/macosArm64/releaseShared/libspike.dylib (impl)
000000000003382c T _spike_own

===== variant=implexport    (implementation(project(":runtime")) + export(project(":runtime")))
Execution failed for task ':lib:linkReleaseSharedMacosArm64'.
> Following dependencies exported in the releaseShared binary are not specified as API-dependencies of a corresponding source set:
  Project :runtime
  Please add them in the API-dependencies and rerun the build.
```

Debug and release binaries behaved identically. Four facts fall out, all verified:

1. A `@CName` export in a dependency klib does **not** reach the consumer's shared library on its
   own, not even when the dependency is `api` and the consumer calls into it. Bullet 1's fear was
   right: without the plugin adding the `export()`, every `nuget_*` P/Invoke fails with
   `EntryPointNotFoundException`.
2. With `export(project(":runtime"))` the symbol is in the binary and in the generated C header.
3. `export()` requires the dependency to be declared `api`; `implementation` fails at link time
   with the message above. The plugin must add the runtime as `api`, not `implementation`.
4. `nuget_spike_internal` is absent in every variant. An `internal` function carrying `@CName` is
   not exported from a dependency klib. Every `@CName` function in the runtime must be `public`.

One side effect, also verified: the exported module's public declarations enter the consumer's
C header and `api.cpp` (`PublicHandles` showed up as a struct initializer in `api.cpp` with a
`-Wc99-designator` warning). Harmless, nothing consumes that header, but the runtime's public
surface should stay small for that reason too.

**Inferred**, not spiked: the same holds for a published maven coordinate
(`export("io.github.xxfast:nuget-runtime:x")`), which is the documented canonical form of
`export()` (the Kotlin docs' own example is `export("org.jetbrains.kotlinx:kotlinx-coroutines-core:...")`,
[multiplatform-build-native-binaries](https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#export-dependencies-to-binaries)).
The `smoke-test` by-coordinate path is where this gets verified. Also inferred: `mingwX64` `.dll`
export behaves the same (the spike ran on `macosArm64` only); CI's Windows leg is the check.

## Alternatives Considered

### 1. A `nuget-runtime` KMP module the plugin adds as `api` and `export()`s (chosen)

`:nuget-runtime` in the root build beside `:nuget-processor`, targets `macosArm64`, `macosX64`,
`linuxX64`, `mingwX64`, `api(kotlinx-coroutines-core)`, published with
`com.vanniktech.maven.publish` like its two siblings. The plugin adds it to every
`KONAN_TO_RID` target's main source set as `api` and calls `export(...)` on every `SharedLibrary`
binary. The processor stops emitting the fixed block and imports the runtime package instead.

Pros: the ABI gets its own version; the gating in `NugetProcessor.kt` and its defect class go away;
every generated file shrinks by ~500 lines; the project gains a klib and becomes indexable on
klibs.io. Cons: `NugetHandles` and friends become a published (if opt-in-guarded) surface; the
Tier 1 harness needs a runtime stub beside its cinterop stub; a fifth artifact to publish.

### 2. Keep generating, deduplicate by hashing (rejected)

Emit the block once per Gradle *build* rather than per module. Solves nothing: a build has one
module applying the plugin in practice, the gating stays, and the ABI still has no version.

### 3. Runtime as a source jar the plugin unpacks into `nativeMain` (rejected)

Ships the fixed source and lets the consumer compile it. Avoids `export()` and the public-surface
question entirely, because the code lands in the consumer module and can stay `internal`. Rejected:
it is the same per-project compile, the ABI still has no version the consumer can see, nothing
qualifies for klibs.io, and it invents a distribution channel Kotlin does not have (a klib is the
one Kotlin has). Worth naming because it is the honest fallback if `export()` ever misbehaves on a
target.

### 4. Skew guard: startup contract arm like ADR-054 (rejected for v1)

Every register export in the reverse bridge carries `slotCount`/`contractHash` because C# calls
one export at startup. The forward side has no such call: C# goes straight to per-declaration
exports. Adding one would mean a new export the C# runtime must call first, which is exactly bullet
5's territory (the C# twin and its `DllImportResolver`). Deferred there.

## Decision

### The module

`:nuget-runtime`, `include(":nuget-runtime")` in `settings.gradle.kts`, Kotlin Multiplatform,
`macosArm64()`, `macosX64()`, `linuxX64()`, `mingwX64()`, `nativeMain` only. Dependencies:
`api(libs.kotlinx.coroutines.core)`. Publishing: the same `mavenPublishing {}` block as
`nuget-processor/build.gradle.kts` (Central Portal, conditional signing) plus the same `localTest`
file repository so `scripts/verify.sh --plugin` can publish it beside the processor and plugin.
`group`/`version` come from the root `gradle.properties`, so the coordinate is
`io.github.xxfast:nuget-runtime:<version>` and the version is always the plugin's.

Package: `io.github.xxfast.kotlin.native.nuget.runtime`. Every public declaration in it is
annotated with a `@RequiresOptIn(level = ERROR)` marker `NugetRuntimeApi` (the kotlinx
`@InternalCoroutinesApi` convention, inferred from the kotlinx source: a marker is how a library
publishes a surface that only its own tooling is meant to call). The generated file adds
`NugetRuntimeApi::class` to its existing `@file:OptIn(...)`. This keeps the stability commitment
honest: the **C names** are the versioned ABI; the Kotlin names are for the generator of the same
version and nobody else. `@PublishedApi internal` is not an option: it admits calls only from
public inline functions in the same module, and the generated code lives in another module
(inferred from the Kotlin language reference; the spike's `internal` result is the same boundary
one level down).

### What moves (byte-identical for every project today)

Moving, with the Kotlin declaration each carries:

| Group | Exports (C names) | Kotlin surface the generated code calls |
|---|---|---|
| Handles (ADR-120) | `nuget_live_handles`, `nuget_dispose` | `public object NugetHandles { val live: AtomicLong; fun retain(Any): COpaquePointer; fun release(COpaquePointer) }` |
| Errors | `nuget_error_type`, `nuget_error_message`, `nuget_error_stacktrace`, `nuget_error_cause_count`, `nuget_error_cause_type`, `nuget_error_cause_message`, `nuget_error_cause_stacktrace` | `public data class NugetError(type, message, stackTrace, cause)`, `public fun buildError(Throwable): NugetError` (called 1,357 times in the fixture) |
| Scalars | `nuget_unwrap_{bool,byte,ubyte,short,ushort,int,uint,long,ulong,float,double,char,string}` (13), `nuget_wrap_{same 13}` (13) | none |
| Collections | `nuget_list_{create,add,count,get}`, `nuget_map_{create,put,count,key_at,value_at}`, `nuget_set_{create,add,count,element_at}` | none |
| Callbacks | `nuget_func{0,1,2,3}_invoke`, `nuget_suspend_func{0,1,2,3}_invoke` | none |
| Coroutines | `nuget_scope_{create,cancel,dispose,drain}`, `nuget_job_{cancel,dispose}`, `nuget_stateflow_collect`, `nuget_stateflow_value` | none |
| Time (ADR-076, ADR-103) | none | `public fun Instant.toDotNetTicks(): Long`, `public fun Duration.toDotNetTicks(): Long`, `public fun instantFromDotNetTicks(Long): Instant`, `public fun durationFromDotNetTicks(Long): Duration` |
| C# bridge | `nuget_gc_collect`, `nuget_csharp_token` | `public interface NugetCSharpBridge { val nugetToken: COpaquePointer }` (implemented by generated bridge classes) |

66 exports (64 in the fixture, plus `nuget_func2_invoke`/`nuget_func3_invoke`). All 66 are
unconditional in the runtime. The `needs*` gating at `NugetProcessor.kt:1880-1990` and the
emitters it calls (`addNugetHelperExports`, `addNugetListHelperExports`, `addNugetSetHelperExports`,
`addNugetMapHelperExports`, `addNugetHandlesCounter`, `addNugetWrapHelperExports`,
`addNugetFunc{0..3}HelperExports`, `addNugetSuspendFuncHelperExports`, `addNugetScopeHelperExports`,
`addNugetScopeDrainExport`, `addNugetJobHelperExports`, `addNugetErrorHelperExports`,
`addNugetInstantHelperExports`, `addNugetDurationHelperExports`, all in
`exports/GenericClassExports.kt`) are deleted; their bodies become the runtime's source, with
`internal` dropped to `public` and the `export_nuget_*` Kotlin names kept. The C# side keeps its
own gating untouched: a `DllImport` it does not emit is never called, and one it emits now always
resolves.

Staying generated (varies per project or lives on the reverse side): every `export_<declaration>`
function, the `@file:OptIn` list (it carries the author's own markers per ADR-115), the imports,
and the whole reverse bridge in `build/nuget-interop/kotlin` (`nuget_runtime_register`,
`NugetRegistry.checkContract`, the per-type register exports). The reverse side keeps a second,
structurally identical `NugetError` (`NugetRuntime.kt:309`, comment only, no import of the forward
one); folding it onto the runtime's is a follow-up, not this move.

### Plugin wiring

In the `withPlugin("org.jetbrains.kotlin.multiplatform")` block beside the processor resolution
(`NugetPlugin.kt:217-226`):

```kotlin
val runtimeDep: Any = project.findProject(":nuget-runtime")
  ?: "io.github.xxfast:nuget-runtime:$PLUGIN_VERSION"

kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
  if (target.konanTarget.name !in KONAN_TO_RID) return@configureEach
  project.dependencies.add("${target.name}MainApi", runtimeDep)          // verified: must be api
  target.binaries.withType(SharedLibrary::class.java).configureEach { lib ->
    lib.export(runtimeDep)                                               // verified: required
  }
}
```

`AbstractNativeLibrary.export(Object)` is the API (verified by `javap` on
`kotlin-gradle-plugin-2.4.10-gradle813.jar`: `public final void export(java.lang.Object)`, plus
`Action` and `Closure` overloads; `SharedLibrary extends AbstractNativeLibrary`). The `api`
configuration name per target (`${target.name}MainApi`) is inferred from KGP's source-set
configuration naming; the `ProjectBuilder` test below is what pins it, and the spike proved the
rule with `nativeMain`, not a per-target set. If the per-target form does not satisfy KGP's
"API-dependencies of a corresponding source set" check, fall back to `nativeMain`'s `api`, which
the spike verified.

An author who already has `export(...)` entries keeps them: `export()` appends to the binary's
export configuration, it does not replace. An author who already exports the runtime themselves
gets the same coordinate added twice; Gradle deduplicates equal dependency declarations
(inferred, covered by the `ProjectBuilder` test). Nothing new in the `nuget {}` DSL.

`test-library/build.gradle.kts` needs no change. Its `implementation(libs.kotlinx.coroutines.core)`
(line 111) stays, because its own sources use `Flow` and `suspend`; the runtime's `api` on
coroutines makes that line redundant for a consumer that only needs the bridge, and Gradle's
conflict resolution picks the higher version if an author pins a newer one (inferred).

### Skew guard

The narrowest thing that makes a skewed pair fail before the first crossing: the plugin pins both
coordinates from the one generated `PLUGIN_VERSION` constant, so in the supported path generator
and runtime cannot skew. For an author who overrides the runtime version anyway (a dependency
constraint, a `resolutionStrategy`), the generator emits one compile-time anchor:

```kotlin
// runtime
@NugetRuntimeApi public object NugetRuntimeAbi1

// generated CNameExports.kt (one line)
@Suppress("unused") private val nugetRuntimeAbi: NugetRuntimeAbi1 = NugetRuntimeAbi1
```

An ABI-incompatible runtime renames the anchor (`NugetRuntimeAbi2`) and the consumer's compile
fails with an unresolved reference naming it, which is earlier and cheaper than any startup arm.
A newer runtime with the same ABI major compiles and runs, which is the point of giving the ABI a
version. The runtime adds no export beyond today's vocabulary: 66 names, nothing else. The
ADR-054-style startup arm is deferred to bullet 5, where C# gains a startup call.

## Test plan, three seams

1. **Processor, pinned text.** A new Tier 1 structural test asserts the generated
   `CNameExports.kt` contains zero `@CName("nuget_` and imports
   `io.github.xxfast.kotlin.native.nuget.runtime.*`. `Tier1Harness` gains a `Tier1RuntimeStub`
   beside `Tier1CinteropStub`: the runtime's public surface (`NugetHandles`, `NugetError`,
   `buildError`, the ticks functions, `NugetCSharpBridge`, `NugetRuntimeAbi1`, `NugetRuntimeApi`)
   as JVM-compilable source, so the fourteen Tier 1 tests that reference these names keep
   compiling. Same fidelity caveat as the cinterop stub: a stub that drifts from the runtime turns
   a real defect green, and the `clinic` corpus against real Kotlin/Native is the backstop. ADR-055's
   `ForwardAbiContract` currently expects the `nuget_*` exports in the generated file; its
   expectation moves to a pinned list of the runtime's 66 names, checked against the runtime source
   rather than the generated output.
2. **Plugin, `ProjectBuilder`.** Mirroring `NugetPluginKspArgsWiringTest.buildProjectWithSharedLib`:
   after `evaluate()`, the target's main `api` configuration contains the runtime (the
   `:nuget-runtime` project in-repo, the coordinate otherwise), the `SharedLibrary`'s
   `exportConfigurationName` configuration contains it, a target outside `KONAN_TO_RID` gets
   neither, and an author who already `export()`ed the runtime ends with one entry.
3. **Outer proof.** `IntegrationTests` and `LeakTests` unchanged: same 64 `DllImport`s, same
   `NugetMarshal.LiveHandles` through `nuget_live_handles`. Plus `scripts/verify-runtime-exports.sh`,
   run from `scripts/verify.sh` after `packNuget`: `nm -gU` on the built `libtest.dylib` (and
   `llvm-nm`/`dumpbin` on `test.dll` in CI's Windows leg) asserts every one of the 66 names is
   present. `scripts/verify.sh --plugin` publishes `:nuget-runtime` to `build/local-repo` beside the
   other two, and `smoke-test` gains `verifyRuntimeResolvesByCoordinate` next to
   `verifyProcessorResolvesByCoordinate`, resolving the sharedLib's export configuration and
   checking for a `nuget-runtime` klib. That is where the inferred "published coordinate exports
   like `project()`" claim gets verified.

## Consequences

- The 66-name `nuget_*` ABI has a version and lives in one place. The gating defect class
  (ADR-068, ADR-071, ADR-075, ADR-124) is gone by construction.
- `NugetHandles`, `NugetError`, `buildError`, the ticks conversions and `NugetCSharpBridge` become
  public, behind `@NugetRuntimeApi`. Renaming any of them is a runtime major bump
  (`NugetRuntimeAbi2`).
- The generated header gains the runtime's public declarations (verified side effect). Nobody
  reads the header; noted so nobody is surprised.
- Every consumer's `.dylib` carries all 66 exports whether or not it needs them. A few KB; the
  size gate under Performance & Resource Hygiene would show it.
- Deferred: bullet 4 (`launchForCSharp`, which the public `NugetHandles`/`buildError` make
  possible), bullet 5 (the C# twin and the startup contract arm), folding the reverse side's
  duplicate `NugetError` onto the runtime's, `linuxX64` in the fixture (the runtime targets it;
  nothing in the repo builds it), and a `nuget_runtime_version(): String` export for
  `NUGET_INTEROP_TRACE=1` to print which runtime a process loaded: an option for bullet 5, not
  shipped here.
- Verified on `macosArm64` by `smoke-test`'s `verifyRuntimeResolvesByCoordinate`: `export()` of the
  *published* coordinate behaves like `export(project(...))`. Still unverified and load-bearing:
  `mingwX64` exports the same way, checked only by CI's Windows leg. If that is wrong the symptom
  is `EntryPointNotFoundException` on the first `nuget_*` call on Windows, and Alternative 3 is the
  fallback.

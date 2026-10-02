# ADR-180: Gradle DSL on `Property<T>`: managed types, register-on-declare, one surviving `afterEvaluate`

## Status

Proposed

## Context

The `nuget {}` extension is a set of plain Kotlin classes with `var` fields and Kotlin lambda block functions:

- `NugetExtension.kt:3-19`
- `NugetPublishConfig.kt:5-82`
- `NugetDependency.kt`, `NugetBindConfig.kt`, `NugetRepository.kt`

`NugetPlugin.kt` reads those fields in four `afterEvaluate` blocks:

- `:31`: the ADR-178 `baseName` stamp.
- `:56`: the consume pipeline.
- `:294`: the KSP args.
- `:423`: pack/publish.

**Verified by reading** at `8e88c600`. The ROADMAP line counts three because it predates ADR-178. Other problems in the current DSL:

- Values are snapshotted at `afterEvaluate` time, so the DSL is not provider-friendly. A `Provider` cannot feed `packageId`.
- A second `publish {}` silently replaces the first (`NugetExtension.kt:10-14`), and so does a second `bind {}`.
- A second `dependency("X")` or `nuget("x")` appends a duplicate.
- A nested block can call an outer receiver's function. Today `include("x")` inside `repositories { nuget("feed") { } }` compiles and adds to `publish.include` (**inferred** from reading; the inverse is verified below).
- `versionPropsFile` and `prebuiltRuntimes` are `File?`.

0.9.0 is the last breaking window before the 1.0 semver surface, and the Gradle DSL is part of it.

The constraints that shape the design:

- **KGP's `NativeBinary.baseName` is `open var baseName: String`, not a `Property`.** **Verified by reading** `kotlin-gradle-plugin-2.4.10-gradle813-sources.jar`: `NativeBinaries.kt:43`. The lazy form at `:48` is `internal`, and `KotlinNativeLink.kt:134` reads `binary.baseName` through `lazyConvention`. A packageId-derived stem can only be written as a `String`, after the script has run.
- **KSP takes provider-valued args lazily.** **Verified by reading** `symbol-processing-gradle-plugin-2.3.10-sources.jar`: `KspExtension.kt:70-74` declares `arg(String, Provider<String>)`, which does `apOptions.put(k, v)` into a `MapProperty`. `KspAATask.kt:338-339` does `putAll(kspExtension.apOptions)`.
- **ADR-050 Alternative 6:** a consume-only project has no `packNuget`, so task registration must still depend on which blocks were declared.
- **`kotlin.sourceSets.findByName("nativeMain")` is null at plugin-apply time.** **Verified**, per the repo comment at `NugetPlugin.kt:262-265`. Any wiring that leaves `afterEvaluate` must use `matching { }.configureEach { }` or it silently drops the srcDir.

## Alternatives Considered

### 1. Managed `Property` types, registration on first DSL call, everything else a `Provider`; keep only the ADR-178 block (chosen)

The DSL types become abstract, `ObjectFactory`-created, `@NugetDsl`-marked types. Block functions take `Action<in T>`. Tasks whose existence depends on a block being declared are registered by the first call to that block (the plugin installs internal one-shot hooks on the extension). Everything that depends on a value is a `Provider`. Three `afterEvaluate` blocks go; `:31` stays.

- Pro: configuration-cache friendly and order independent. Merge comes for free (one nested instance). ADR-050's task surface is kept. Kotlin DSL scripts are unchanged (spike below).
- Con: the largest diff of the options. Configuration-time checks become execution-time failures. One `afterEvaluate` survives, as a KGP limitation.

### 2. Register everything unconditionally at apply; gate with `onlyIf` / empty inputs

- Pro: the textbook Gradle lazy-configuration shape, with no hooks.
- Con: every project applying the plugin lists `packNuget`, `nugetRestore` and `publishNuget`, contradicting ADR-050 Alternative 6 and its tests. A consume-only project's `packNuget` would have to no-op or fail.

### 3. Keep the `afterEvaluate` blocks; only change the field types

- Pro: a small diff.
- Con: values are still read eagerly in `afterEvaluate`, so a `Provider` read there is a snapshot. It does not deliver the restatement (laziness, ordering), only the type names.

### 4. Drop all four blocks

- Not possible: `baseName` is a `String` in KGP 2.4.10 (above). Writing it inside the `publish {}` call would read `packageId` before the script finishes setting it, which is silently wrong for `publish { version = ...; packageId = ... }` ordering across two blocks.

## Decision

Alternative 1.

### Model

```kotlin
@DslMarker
annotation class NugetDsl

@NugetDsl
abstract class NugetExtension @Inject constructor(objects: ObjectFactory) {
  val publish: NugetPublishConfig = objects.newInstance(NugetPublishConfig::class.java)
  val dependencies: NamedDomainObjectContainer<NugetDependency> =
    objects.domainObjectContainer(NugetDependency::class.java)

  fun publish(action: Action<in NugetPublishConfig>)          // marks declared, configures `publish`
  fun dependencies(action: Action<in NugetDependencyScope>)   // explicit fun: Project.dependencies exists
}

@NugetDsl
abstract class NugetPublishConfig @Inject constructor(objects: ObjectFactory) {
  abstract val packageId: Property<String>
  abstract val version: Property<String>
  abstract val authors: Property<String>
  abstract val description: Property<String>
  abstract val rootPackage: Property<String>
  abstract val snapshot: Property<Boolean>               // convention(false)
  abstract val versionPropsFile: RegularFileProperty      // convention(<root>/build/<packageId>Versions.props)
  abstract val prebuiltRuntimes: DirectoryProperty
  abstract val strictDependencyTypes: Property<Boolean>   // convention(false)
  abstract val include: ListProperty<String>              // + fun include(vararg)
  abstract val exclude: ListProperty<String>              // + fun exclude(vararg)
  abstract val admit: ListProperty<String>                // + fun admit(vararg)
  abstract val exportMarkers: ListProperty<String>        // + fun exportMarkers(vararg)
  val repositories: NamedDomainObjectContainer<NugetRepository>
  fun repositories(action: Action<in NugetRepositoriesScope>)  // explicit fun: Project.repositories exists
}
```

- `NugetRepository` (`url`, `apiKey`, `username`, `password`, `skipDuplicate`) becomes a named managed type. `nuget("feed") { }` becomes `maybeCreate` plus configure.
- `NugetDependency` (keyed by id: `version`, `source`, `bind(Action)`) and `NugetBindConfig` (`packageName`, `include`, `exclude`, `alias` into a `MapProperty`) follow the same pattern.
- `NugetDependencyScope` and `NugetRepositoriesScope` stay, as `@NugetDsl` wrappers with internal constructors over the two containers. A `NamedDomainObjectContainer` cannot carry the `dependency(id, version) { }` and `nuget("feed") { }` functions every fixture calls.
- A second `publish {}`, `bind {}`, `dependency("X")` or `nuget("x")` configures the same instance: it **merges**.
- Both containers iterate in name order, not declaration order. No consumer of the order depends on it (a `PackageReference` list and a set of push tasks).
- `bind {}` is a declaration as well as configuration: `NugetDependency.bind` is never null, and an internal flag records whether the block was called. An empty `bind { }` still binds.

### Wiring

- **Kept:** `NugetPlugin.kt:31-41` (ADR-178), unchanged.
- **Consume pipeline (`:56`):** the first `dependency(...)` call registers `nugetGen`, `nugetRestore` and `nugetImport` (through `whenObjectAdded`, so an empty `dependencies {}` registers nothing). The first `bind {}` call registers the reverse tasks. Values become providers over the container:
  - `dependencyIds`, versions and sources.
  - The `bind` maps.
  - RIDs, a provider over `kotlin.targets`.
  - srcDirs, via `kotlin.sourceSets.matching { it.name == "nativeMain" }.configureEach` and `kotlin.targets.withType(KotlinNativeTarget).configureEach`, never `findByName`.
- **KSP args (`:294`):** registered once in `withPlugin("com.google.devtools.ksp")` through `arg(k, Provider<String>)`, every one of them. `nuget.libraryName` reads the first shared lib's `baseName` in a provider, so it sees ADR-178's stamp. `nuget.publishedScopes` filters sibling extensions by their internal "publish declared" flag instead of `publish != null`.
- **Pack/publish (`:423`):**
  - The first `publish {}` call registers `nugetReportDiagnostics`, `nugetCompileInterop`, `packNuget`, `nugetSnapshotVersion`, `nugetSnapshotVersionProps` and `publishNuget`.
  - `packageVersion` is `snapshot.flatMap { if (it) mintedVersion else version }`, and the snapshot tasks are pulled in through a `Callable` `dependsOn` only when `snapshot` is true. So both snapshot tasks now exist on every `publish {}` project; with `snapshot = false` nothing depends on them.
  - The reverse shims and the resolved bound-package versions reach `packNuget` and `nugetCompileInterop` through `tasks.withType(...).configureEach` from the `bind {}` side, so `publish {}` and `bind {}` can come in either order. The resolved-versions provider is a `flatMap` over `nugetRestore`, not a `zip`: a `zip` was read when the configuration cache stored the entry, before `dotnet restore` had written `project.assets.json` (**verified**, `test-library:packNuget` failed that way during implementation).
  - `nativeLibDirs` is a provider over targets and link tasks.
  - **Verified by reading:** `enabled` is set once from the host, in `KotlinNativeConfigureBinariesSideEffect.kt:123-124`. **Inferred:** reading it at graph time equals today's configuration-time read.
  - Per-repository tasks come from `publish.repositories.all { }`.
  - These configuration-time checks become execution-time failures with the same text: missing `url` (`publishNugetTo...Repository`), snapshot without version or packageId (`nugetSnapshotVersion`), no supported targets, and no lib dirs and no `prebuiltRuntimes` (`packNuget`). The "Skipping RID" lifecycle line moves into `packNuget`'s action. The no-lib-dirs check applies only to a plugin-wired `packNuget`, so a hand-configured task can still stage C# alone.
- **mingw `linkerOpts`:** move to the unconditional `binaries.withType(SharedLibrary).configureEach` in `withPlugin`. This fixes a pre-existing bug: a consume-only mingw project never got `-lole32`, though the reverse `freeManagedString` needs it. It lands as its own commit after the main one.

### Gate decisions

1. A second `dependency("X")` merges into one entry.
2. `packNuget` is registered even with no supported targets, and fails at execution.
3. Blocks take `Action<in T>`.
4. `-lole32` applies to consume-only projects too, as a separate commit (a split-out pre-existing bug).
5. `targetFramework` is not added here; the sibling item adds it on top.
6. `@NugetDsl` is public API.

### Verified by spike (Gradle 9.1.0, scratch `buildSrc` plugin with the model above, no `afterEvaluate`)

Script:

```kotlin
nugetx {
  publish {
    packageId = "MyLib"; snapshot = true; prebuiltRuntimes = file("pre"); include("a.b")
    repositories { register("local") { url = "https://example/v3/index.json" } }
  }
  publish { include("a.c") }
}
```

`gradle --configuration-cache -q show publishToLocal` printed:

```
SPIKE at-apply packageId.isPresent=false
SPIKE repo publishToLocal url=https://example/v3/index.json
SPIKE id=MyLib incl=[a.b, a.c] ver=1.0.0-snapshot pre=pre props=MyLibVersions.props
```

A rerun printed `Configuration cache entry stored.`

Adding `include("leak")` inside the repository block failed compilation:

```
'fun include(vararg p: String): Unit' cannot be called in this context with an implicit receiver.
```

So the following are **verified**:

- `=` assignment on `Property` and `DirectoryProperty` from `File` needs no script change.
- Merge works.
- The derived `versionPropsFile` convention works.
- Per-repository registration works without `afterEvaluate`.
- The configuration cache stores.
- `@DslMarker` blocks outer receivers through `Action` and a `NamedDomainObjectContainer`.

A `repositories {}` block with no explicit function on the marked type resolved to `Project.repositories` and failed with `Unresolved reference 'url'` (**verified**). Hence the explicit `repositories(...)` and `dependencies(...)` functions. That `dependencies` behaves the same way is **inferred**.

### Not verified (inferred), and what breaks if wrong

- **Register-on-first-call:** the one-shot hook must fire after KMP is applied. **Inferred** safe, because `plugins {}` applies every plugin before the script body runs. If a script applies KMP imperatively after `nuget {}`, the hook must defer through `withPlugin`, or `packNuget` silently misses its link dependencies.
- **KSP provider resolution timing:** **inferred** that nothing resolves `apOptions` before evaluation ends. The real-build fixture `scripts/verify-forward-diagnostics.sh` covers one such provider. **Verified at implementation:** two `test-library:packNuget --configuration-cache` runs stored and then reused the entry, and the reused run's `.nuspec` pinned both bound packages at their resolved versions. The two-publisher fixture still delivered the ADR-109 `WARNING_DUPLICATED_DEPENDENCY_TYPE` for `TopStory` naming the sibling package, in both manifests.
- **Minimum Gradle for lazy property assignment:** **inferred** to be 8.2 from Gradle release notes. It was spiked only on 9.1.0.

## Consequences

- **Breaking for script authors:**
  - Reading values back now returns `Provider` / `Property`, and `nuget.publish` is non-null.
  - A repeated block merges instead of replacing or duplicating.
  - An outer-receiver call from a nested block is a compile error.
  - `packNuget` exists on every `publish {}` project and fails at execution instead of being silently absent.
  - Assignment-only scripts do not change. One in-repo fixture read the model back: `test-library/build.gradle.kts` checked `companion?.publish == null` for ADR-109's ordering, and now checks that the companion's `packageId` is not yet present.
  - `nugetSnapshotVersion` and `nugetSnapshotVersionProps` exist on every `publish {}` project, not only snapshot ones.
  - Repositories and dependencies iterate in name order.
- **Breaking for tests:** 13 ProjectBuilder test files construct or read the model directly. `NugetExtension()` becomes `objects.newInstance(...)` / `extensions.getByType(...)`. Lazy assignment and SAM-with-receiver are Kotlin DSL *script* compiler plugins, so plain-Kotlin tests write `publish { it.packageId.set("x") }`. Enabling those plugins module-wide would change the meaning of every `register("x") { task -> }` in the plugin's own sources.
- Groovy build scripts become usable (`Action`), though untested.
- Deferred:
  - Removing `:31`, which waits for a public lazy `baseName` in KGP.
  - Project isolation for `publishedScopes` (ADR-109's gap).
- **Sibling 0.9.0 items rebase onto this one:**
  - Target framework: `:88` becomes a `Property<String>` with convention `"net8.0"`.
  - The `include`/`exclude` rename.
  - Task-name unification.
  - The reverse opt-in switch, which is the `dependencies` / `bind` registration trigger.
- Research memo: [docs/research/roadmap/gradle-dsl-property.md](../research/roadmap/gradle-dsl-property.md).

# ADR-044: NuGet dependency DSL — shape, opt-in binding selection, aliasing, and downstream data contract

## Status

Accepted

## Context

ROADMAP Phase 8 (line 125) requires a Gradle DSL for declaring NuGet packages to consume from
Kotlin. The prior-art synthesis (decision D4 in `docs/research/nuget-plugin-architecture-synthesis.md`)
prescribes the `nuget { dependencies { ... } }` nesting and the opt-in binding model but defers the
exact DSL surface to this ADR.

### Extension restructure opportunity

`NugetPlugin` currently registers a `"nuget"` extension (`NugetExtension`) whose root properties
(`packageId`, `version`, `authors`, `description`, `rootPackage`) all belong to the forward-direction
publish flow. Adding a `dependencies {}` block at the same root level would create an asymmetry:
publish configuration lives flat at the root while consume configuration lives in a nested block.

Because the library is **pre-release and unreleased**, breaking the existing extension API is
acceptable. This ADR adopts a symmetric restructure:

```
nuget {
    publish { ... }       ← all forward-direction (packNuget) configuration
    dependencies { ... }  ← all reverse-direction (Phase 8) configuration
}
```

This symmetry carries three concrete benefits:

1. **Disambiguation of `version`.** The existing root `version` property names the published package
   version. Inside `dependencies {}`, each `dependency()` also carries a `version` for the resolved
   package. Keeping them in separate nested scopes (`publish.version` vs `dependency.version`)
   eliminates any ambiguity about which `version` is which.

2. **Extension root left free for cross-cutting config.** A future `sources { url("...") }` block
   (shared NuGet feed URLs applying to both publish and consume) belongs at the root, not inside
   either nested block. The restructure reserves the root for that.

3. **Conceptual symmetry with the maven-publish mental model.** `publish {}` matches the name
   used by Gradle's own `maven-publish` plugin (`publishing { publications { ... } }`) and the
   broader Kotlin ecosystem (`cocoapods { }` wraps both publish and consume in one extension named
   after the tool). `publish` names the direction, not the task.

Migration of the existing extension properties into the `publish {}` block is in-scope for this
feature; the `packNuget` task wiring in `NugetPlugin` follows the properties.

### Constraints on the dependency DSL

**Constraint — resolve the transitive closure by default, bind selectively.**
NuGet packages routinely pull in ten or more transitive packages. Generating Kotlin bindings for the
entire transitive closure would produce overwhelming APIs and slow builds. The synthesis document
(D4) is explicit: "do not bind a package's whole transitive closure by default — resolve
transitively, bind selectively." This is the opposite of CocoaPods, which generates bindings for
every declared pod (with `linkOnly = true` to suppress them).

**Constraint — be idiomatic Gradle (lazy configuration, configuration-cache safe).**
Task inputs must be declared as Gradle `Property`/`ListProperty`/`MapProperty` so they participate
in configuration-cache serialisation. The model objects exposed from the extension are plain Kotlin
classes (not abstract Gradle-managed types) mirroring the CocoaPods pattern, and tasks copy the
values they need into annotated `@Input` properties at wiring time.

### Prior-art DSLs studied

**CocoaPods plugin (`pod()`):**
```kotlin
cocoapods {
    pod("SDWebImage") { version = "5.20.0" }
    pod("MyPod") { source = path(project.file("../MyPod")) }
    pod("LinkedOnly") { linkOnly = true }
    pod("FirebaseAuth") {
        packageName = "FirebaseAuthWrapper"
        extraOpts += listOf("-compiler-option", "-fmodules")
    }
}
```
Key axes: `version`, `source` (registry / `path()` / `git()`), `linkOnly`, `packageName`,
`extraOpts`. Bindings generated for every pod unless `linkOnly = true` (opt-out).

**spm4Kmp (`swiftPackageConfig`):**
```kotlin
target.swiftPackageConfig {
    remotePackage(url = "...", version = from("12.5.0")) {
        product("FirebaseAnalytics", exportToKotlin = true)
        product("FirebaseFirestoreInternal", alias = "FirebaseFirestore", exportToKotlin = true)
    }
}
```
Key ideas borrowed: `exportToKotlin = true` per product (our `bind {}` block) and per-product
`alias` rename (our `alias()` inside `bind {}`).

**JetBrains official SwiftPM import (Alpha):**
```kotlin
swiftPMDependencies {
    swiftPackage(url = url("..."), version = from("12.5.0"),
        products = listOf(product("FirebaseAnalytics")))
}
```
Shows that Kotlin DSLs for foreign packages use function-call form, not infix operators on strings.

### The binding opt-in problem in detail

The "resolve but do not bind" model requires a clear per-package gate. Three shapes were considered:

- **Gate at the presence of a nested `bind {}` block** inside the `dependency {}` declaration —
  absence means no bindings; presence means opt-in with configuration.
- **Gate at a boolean flag** on the dependency — `generate = true` alongside flat binding
  properties on the same object.
- **Gate in a separate top-level `bindings {}` block** that cross-references dependency IDs.

The `bind {}` block approach cleanly separates resolution configuration (version, source) from
binding configuration (packageName, includes, aliases), makes the opt-in visible at a glance, and
allows the implementation to check `dependency.bind != null` without reading additional state.

### Where the DSL data flows downstream

ADR-042 defines the task graph:

```
nugetGen      reads: all declared dependencies (id, version, source)
              writes: interop.csproj (<PackageReference> per dependency)

nugetRestore  reads: interop.csproj
              writes: obj/project.assets.json

nugetExtractApi  reads: project.assets.json + bind configurations (which packages/namespaces)
                 writes: reverse-ir.json

nugetGenerateBindings  reads: reverse-ir.json + bind configs (packageName, aliases)
                       writes: Kotlin stubs + C# registration shims

nugetImport   umbrella IDE-sync task; dependsOn all of the above
```

The `nugetGen` task needs the full dependency list. The `nugetExtractApi` and
`nugetGenerateBindings` tasks need only the subset with `bind != null` and their bind
configurations. The `packNuget` task reads from `NugetPublishConfig` (the `publish {}` block).

## Alternatives Considered

### 1. `publish {}` + `dependency()` + `bind {}` opt-in (chosen)

The `nuget {}` extension holds two nested scopes: `publish {}` owns all forward-direction
properties and `dependencies {}` owns all reverse-direction declarations. Inside `dependencies {}`,
each package is declared with `dependency(id)` (mirroring `pod(name)`). Bindings are opt-in via a
nested `bind {}` block. Absence of `bind {}` means "resolve but do not bind."

**Full DSL surface:**

```kotlin
nuget {
    publish {
        packageId = "MyLib"
        version = "1.0.0"
        authors = "Me"
        description = "A Kotlin/Native library"
        rootPackage = "io.github.me.mylib"
    }

    dependencies {
        // resolve only — no Kotlin bindings generated
        dependency("Serilog") {
            version = "3.1.1"
        }

        // shorthand for resolve-only with no config block
        dependency("Microsoft.Extensions.Logging", version = "8.0.0")

        // opt-in binding generation — binds all public namespaces
        dependency("Newtonsoft.Json") {
            version = "13.0.3"
            bind {
                packageName = "json"            // import json.* in Kotlin
            }
        }

        // full example: custom feed, namespace filtering, aliasing
        dependency("Acme.Utilities") {
            version = "2.0.0"
            source = "https://pkgs.dev.azure.com/myorg/myfeed/nuget/v3/index.json"
            bind {
                packageName = "acme"
                include("Acme.Utilities.Core")   // whitelist; empty = all namespaces
                include("Acme.Utilities.Math")
                exclude("Acme.Utilities.Internal") // blacklist, applied after include filter
                alias("Acme.Utilities.Core", kotlinPackage = "acme.core")
                alias("Acme.Utilities.Math", kotlinPackage = "acme.math")
            }
        }
    }
}
```

**Model objects:**

```kotlin
// publish {} configuration (replaces the existing flat root properties)
class NugetPublishConfig {
    var packageId: String? = null
    var version: String? = null
    var authors: String? = null
    var description: String? = null
    var rootPackage: String? = null
}

// dependencies {} scope
class NugetDependencyScope(private val dependencies: MutableList<NugetDependency>) {
    fun dependency(id: String, version: String? = null, configure: NugetDependency.() -> Unit = {}) {
        val dep = NugetDependency(id)
        if (version != null) dep.version = version
        dep.configure()
        dependencies.add(dep)
    }
}

class NugetDependency(val id: String) {
    var version: String? = null
    var source: String? = null   // null = NuGet.org public feed
    var bind: NugetBindConfig? = null
        private set

    fun bind(configure: NugetBindConfig.() -> Unit) {
        val config = NugetBindConfig()
        config.configure()
        bind = config
    }
}

class NugetBindConfig {
    var packageName: String? = null  // null = snake_case of the NuGet package ID
    private val _include = mutableListOf<String>()
    private val _exclude = mutableListOf<String>()
    private val _aliases = mutableMapOf<String, String>()

    val include: List<String> get() = _include.toList()
    val exclude: List<String> get() = _exclude.toList()
    val aliases: Map<String, String> get() = _aliases.toMap()

    fun include(vararg namespace: String) { _include.addAll(namespace) }
    fun exclude(vararg namespace: String) { _exclude.addAll(namespace) }
    fun alias(csharpNamespace: String, kotlinPackage: String) {
        _aliases[csharpNamespace] = kotlinPackage
    }
}
```

**Extension — replaces the existing flat-property `NugetExtension`:**

```kotlin
abstract class NugetExtension {
    // no root-level properties; the root is reserved for future cross-cutting config

    var publish: NugetPublishConfig? = null
        private set

    private val _dependencies = mutableListOf<NugetDependency>()
    val dependencies: List<NugetDependency> get() = _dependencies.toList()

    fun publish(configure: NugetPublishConfig.() -> Unit) {
        val config = NugetPublishConfig()
        config.configure()
        publish = config
    }

    fun dependencies(configure: NugetDependencyScope.() -> Unit) {
        NugetDependencyScope(_dependencies).configure()
    }
}
```

**`packNuget` task wiring — updated to read from `extension.publish`:**

```kotlin
// inside NugetPlugin.kt afterEvaluate, replacing the existing extension.packageId.get() reads
val pub = requireNotNull(extension.publish) {
    "nuget { publish { ... } } block is required to run packNuget"
}
task.packageId.set(pub.packageId)
task.packageVersion.set(pub.version)
task.authors.set(pub.authors)
task.packageDescription.set(pub.description)
```

**Gradle task input contract for Phase 8 tasks:**

```kotlin
// nugetGen task (generates interop.csproj)
abstract class NugetGenTask : DefaultTask() {
    @get:Input abstract val dependencyIds: ListProperty<String>
    @get:Input abstract val dependencyVersions: MapProperty<String, String>
    @get:Input abstract val dependencySources: MapProperty<String, String>
    @get:Input abstract val targetFramework: Property<String>        // "net8.0"
    @get:Input abstract val runtimeIdentifiers: ListProperty<String> // from Kotlin/Native targets
    @get:OutputFile abstract val csprojFile: RegularFileProperty
}

// nugetExtractApi task (reads DLLs → reverse-ir.json)
// reads only dependencies where bind != null
abstract class NugetExtractApiTask : DefaultTask() {
    @get:Input abstract val boundPackageIds: ListProperty<String>
    @get:Input abstract val packageNameOverrides: MapProperty<String, String>  // id -> packageName
    @get:Input abstract val namespaceIncludes: MapProperty<String, List<String>> // id -> list
    @get:Input abstract val namespaceExcludes: MapProperty<String, List<String>> // id -> list
    @get:Input abstract val namespaceAliases: MapProperty<String, Map<String, String>> // id -> map
    @get:InputFile abstract val assetsFile: RegularFileProperty   // project.assets.json
    @get:OutputFile abstract val reverseIrFile: RegularFileProperty // reverse-ir.json
}
```

**Pros:**
- `publish {}` and `dependencies {}` are symmetric: one block per direction, easy to scan.
- Disambiguates the two `version` properties: `publish.version` is the package version;
  `dependency.version` is the resolved package version.
- Opt-in is visible at a glance: `bind {}` present → bindings generated; absent → no bindings.
- Separation of resolution config (version, source) from binding config (packageName, includes,
  aliases) avoids accidental coupling.
- `dependency.bind != null` is a zero-ambiguity check for whether to include a package in the
  `nugetExtractApi` stage.
- Extension root is left free for future cross-cutting config (e.g. shared feed sources).

**Cons:**
- Breaking change to the existing `nuget {}` DSL (existing `nuget { packageId = ... }` becomes
  `nuget { publish { packageId = ... } }`). Acceptable because the library is unreleased.
- Two levels of nesting (`dependency { bind { } }`) is deeper than a flat `linkOnly` flag.
- `NugetDependency` and `NugetBindConfig` are plain Kotlin classes (not abstract Gradle-managed
  types), so the extension itself is not configuration-cache safe — the same pattern CocoaPods uses;
  tasks extract values into `@Input` properties.

#### Rejected names for the publish block

**`pack {}`** — `packNuget` is the task name, but the block names a direction (produce vs consume),
not a task. `pack` also connotes compression/archiving more than package publishing. The `maven-
publish` plugin uses `publishing { publications { ... } }`, establishing `publish` as the idiomatic
Gradle vocabulary for the "send to a registry" direction.

**`package {}`** — `package` is a reserved keyword in Kotlin and cannot be used as a function name
in a DSL lambda receiver context without backtick escaping, which is unacceptable for a user-facing
DSL.

### 2. Opt-out via `generate = false` flag (rejected)

Mirror CocoaPods exactly: all declared dependencies get bindings by default; set `generate = false`
(the `linkOnly` analog) to suppress them.

```kotlin
dependencies {
    dependency("Newtonsoft.Json") { version = "13.0.3" }   // bindings generated by default
    dependency("Serilog") { version = "3.1.1"; generate = false }  // suppress
}
```

**Rejected.** CocoaPods defaults to binding because a pod declaration is already a deliberate choice
of a focused dependency. NuGet's transitive closure is far larger: a single direct dependency such as
`Microsoft.Extensions.DependencyInjection` pulls in a dozen transitive packages. Generating bindings
for all of them by default would produce hundreds of Kotlin stubs from a single `dependency()`
call, most of which the Kotlin developer does not need. Opt-in avoids this entirely.

### 3. Separate `bindings {}` block cross-referencing dependency IDs (rejected)

Split resolution declarations from binding declarations into two sibling scopes:

```kotlin
nuget {
    dependencies {
        dependency("Newtonsoft.Json", version = "13.0.3")
        dependency("Serilog", version = "3.1.1")
    }
    bindings {
        bind("Newtonsoft.Json") { packageName = "json" }
    }
}
```

**Rejected.** Split declaration is error-prone: a user can write `bind("Foo")` without a matching
`dependency("Foo")`, leaving the plugin to either silently ignore or fail loudly. Co-locating the
`bind {}` block inside the dependency declaration makes the correspondence structurally enforced: you
can only bind what you have declared as a dependency.

## Decision

Use **Alternative 1: `publish {}` + `dependency()` + `bind {}` opt-in** as the full `nuget {}`
extension shape.

### DSL summary

| Concept | Syntax | Notes |
|---|---|---|
| Publish package ID | `packageId = "..."` inside `publish {}` | Replaces root-level `packageId` |
| Publish version | `version = "..."` inside `publish {}` | Replaces root-level `version` |
| Publish authors / description | `authors`, `description` inside `publish {}` | Replaces root-level properties |
| Kotlin root package (forward) | `rootPackage = "..."` inside `publish {}` | Replaces root-level `rootPackage` |
| Declare a dependency (resolve only) | `dependency("Foo") { version = "1.0.0" }` | No `bind {}` → no bindings |
| Declare a dependency (shorthand) | `dependency("Foo", version = "1.0.0")` | Equivalent to above |
| Opt into binding generation | `dependency("Foo") { version = "1.0.0"; bind { ... } }` | `bind {}` absent → no bindings |
| Kotlin root package for stubs | `packageName = "foo"` inside `bind {}` | Null → snake_case of package ID |
| Namespace whitelist | `include("Foo.Bar")` inside `bind {}` | Empty → all public namespaces |
| Namespace blacklist | `exclude("Foo.Internal")` inside `bind {}` | Applied after include filter |
| Namespace rename | `alias("Foo.Bar", kotlinPackage = "bar")` inside `bind {}` | Overrides `packageName` prefix for one namespace |
| Custom NuGet feed | `source = "https://..."` on the dependency | Null → NuGet.org public feed |

### Namespace filtering semantics

When `include()` calls are present: only the listed namespaces are forwarded to binding generation.
When no `include()` calls are present: all public namespaces are included (subject to `exclude()`
calls). `exclude()` is applied after `include()` (never adds back excluded entries).

`alias()` renames a specific C# namespace to a different Kotlin package segment, overriding the
`packageName` prefix for that namespace only. Example: `packageName = "acme"` with
`alias("Acme.Math", kotlinPackage = "math")` → classes in `Acme.Math` land in the `math` package;
classes in other included namespaces land in `acme`.

### `nugetGen` task input wiring

At `afterEvaluate`, the plugin wires the extension data into the `nugetGen` task inputs:

```kotlin
task.dependencyIds.set(extension.dependencies.map { it.id })
task.dependencyVersions.set(extension.dependencies
    .filter { it.version != null }
    .associate { it.id to it.version!! })
task.dependencySources.set(extension.dependencies
    .filter { it.source != null }
    .associate { it.id to it.source!! })
task.runtimeIdentifiers.set(supportedTargets.map { KONAN_TO_RID[it.konanTarget.name]!! })
```

The `nugetGen` task emits an `interop.csproj` with:
- `<PackageReference Include="{id}" Version="{version}"/>` for each dependency that has a version.
- `<RuntimeIdentifiers>{rid};{rid};...</RuntimeIdentifiers>` from the Kotlin/Native targets (so
  `dotnet restore` fetches `runtimes/{rid}/native/` payloads for all configured targets).
- A `<RestoreSources>` element for each dependency with a custom `source`, merged with
  `https://api.nuget.org/v3/index.json`.

### `nugetExtractApi` task input wiring

Only dependencies with `bind != null` are forwarded:

```kotlin
val bound = extension.dependencies.filter { it.bind != null }
task.boundPackageIds.set(bound.map { it.id })
task.packageNameOverrides.set(bound
    .filter { it.bind!!.packageName != null }
    .associate { it.id to it.bind!!.packageName!! })
task.namespaceIncludes.set(bound.associate { it.id to it.bind!!.include })
task.namespaceExcludes.set(bound.associate { it.id to it.bind!!.exclude })
task.namespaceAliases.set(bound.associate { it.id to it.bind!!.aliases })
```

The `nugetExtractApi` task passes the `include`/`exclude` lists to the bundled dotnet dump tool
(ADR-042) via CLI arguments, so only the requested namespaces appear in `reverse-ir.json`. This
keeps the reverse-IR model focused and avoids generating bindings for types the Kotlin developer
will never use.

### `packageName` default derivation

When `packageName` is null, the generator derives the Kotlin package from the NuGet package ID by:
1. Lower-casing the entire ID.
2. Keeping `.` as-is (package segments).
3. Replacing any `-` with `_`.

Example: `Newtonsoft.Json` → `newtonsoft.json`; `My-Package` → `my_package`.

This default can always be overridden with an explicit `packageName`.

## Consequences

### Breaking change to the existing `nuget {}` extension

The existing root-level properties (`packageId`, `version`, `authors`, `description`, `rootPackage`)
move into the `publish {}` block. This is a breaking change to the public DSL. It is accepted
because the library is unreleased; no external consumers exist to migrate. The `packNuget` task
wiring in `NugetPlugin` is updated to read from `extension.publish` in the same change.

### New classes in the `nuget` Gradle module

- `NugetPublishConfig` — publish block configuration; replaces the abstract Gradle-managed
  properties on `NugetExtension`. Plain Kotlin class with `var` fields for `packageId`, `version`,
  `authors`, `description`, `rootPackage`.
- `NugetDependencyScope` — the `dependencies {}` receiver; holds the `dependency()` function.
- `NugetDependency` — one declared NuGet package; mutable POJO with `id`, `version`, `source`,
  `bind` fields.
- `NugetBindConfig` — binding configuration; holds `packageName`, `include`, `exclude`, `aliases`.

These are plain Kotlin classes (no abstract Gradle machinery) matching the CocoaPods extension
pattern. Tasks extract the values they need into `@Input`-annotated Gradle properties at wiring
time, which is how configuration-cache safety is achieved.

### New Gradle tasks (stubs for now; implemented in subsequent ROADMAP items)

- `nugetGen` — reads `NugetExtension.dependencies`, writes `interop.csproj`. Cacheable when
  inputs are unchanged.
- `nugetRestore` — runs `dotnet restore interop.csproj`, writes `obj/project.assets.json`.
  `@DisableCachingByDefault` (external process; mirrors `PodInstallSyntheticTask`).
- `nugetExtractApi` — invokes the bundled dotnet dump tool (ADR-042), writes `reverse-ir.json`.
  Cacheable (deterministic given the same DLLs and bind configs).
- `nugetGenerateBindings` — reads `reverse-ir.json` + bind configs, writes Kotlin stubs and C#
  shims. Cacheable.
- `nugetImport` — umbrella IDE-sync task; `dependsOn` all of the above (mirrors `podImport`).

The task names follow the `nuget*` prefix convention established in the synthesis pipeline sketch
(ADR-042) and match the ROADMAP description at lines 126–131.

### Scope

**In v1:**
- `publish { packageId; version; authors; description; rootPackage }` — replaces flat root
  properties; `packNuget` wiring updated in the same change.
- `dependency(id) { version; source; bind { packageName; include; exclude; alias } }` — the full
  dependency DSL shape described in this ADR.
- A single `source` override per dependency for custom NuGet feed URLs.
- Extension-level `nugetGen`, `nugetRestore`, `nugetExtractApi`, `nugetGenerateBindings`,
  `nugetImport` task stubs with correct input/output wiring (implementations completed by
  subsequent ROADMAP items for the resolution pipeline and binding generation).
- `packageName` default derivation (snake_case of package ID).
- `include()`/`exclude()` filtering forwarded to the dump tool as CLI arguments.

**Deferred:**
- Local path / `.nupkg` file source (`source = file("...")`) for the KMMBridge-style local-vs-
  published dev loop (synthesis D6; tracked under Pre-Launch Checklist).
- Extension-level custom feed URL applied to all dependencies (a `sources { url("...") }` block
  at the `nuget {}` root); v1 supports per-dependency `source` only.
- Multiple `bind {}` calls per dependency (multiple namespace groups with distinct `packageName`
  values in one package); v1 allows only one `bind {}` per `dependency()`.
- Transitive-package binding shorthand — binding a transitive package without promoting it to a
  direct `dependency()` declaration.

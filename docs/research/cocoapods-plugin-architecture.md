# Kotlin CocoaPods Gradle plugin — architecture study

> Prior-art research for Phase 8 (consuming NuGet packages from Kotlin) and, secondarily, for
> the publish direction. The CocoaPods plugin (`kotlin("native.cocoapods")`) is the closest
> analog in the Kotlin ecosystem because it works **both** ways: it publishes a Kotlin framework
> as a pod *and* consumes external pods into Kotlin via cinterop.

## Executive summary

The CocoaPods plugin is applied on top of the Kotlin Multiplatform plugin and, in `afterEvaluate`,
translates a declarative `cocoapods {}` DSL into a chain of Gradle tasks that shell out to the
external `pod` (CocoaPods) and `xcodebuild` command-line tools. For **consumption**
(`pod("AFNetworking")` → Kotlin), it does *not* parse binaries: it generates a **synthetic Xcode
project + Podfile**, runs `pod install` to download and lay out the pod's sources/headers, invokes
`xcodebuild` to compile the pod and to harvest its build settings (framework/header search paths),
generates a cinterop `.def` file that points at the pod's Objective-C **module**, and runs the
standard `cinterop` tool to produce a `.klib` of Kotlin externals — **once per Apple target family**.
For **publishing** (Kotlin → pod), it generates a `.podspec` whose `vendored_frameworks` points at the
Kotlin framework and whose `script_phases` block calls **back into Gradle** (`./gradlew … syncFramework`)
during the Xcode build — the same "build tool calls back into the producing build system" pattern that
an MSBuild ↔ Gradle bridge would need. Everything hinges on external native tooling being present
(`pod`, `xcodebuild`, Ruby), and Kotlin/Native's ability to read **Objective-C** headers — which is
also the source of the plugin's biggest limitation (pure-Swift pods are unsupported).

Source tree studied: `libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/`
([directory](https://github.com/JetBrains/kotlin/tree/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods)),
with the task implementations under [`tasks/`](https://github.com/JetBrains/kotlin/tree/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks).

### Source-file map

| File | Role |
|---|---|
| [`KotlinCocoapodsPlugin.kt`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/KotlinCocoapodsPlugin.kt) | Plugin entry point; registers all tasks and wires the graph |
| [`CocoapodsExtension.kt`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/CocoapodsExtension.kt) | The `cocoapods {}` / `pod()` DSL model |
| `CocoapodsPluginDiagnostics.kt` | User-facing diagnostics/warnings |
| `PodBuildSettingsProperties.kt` | Parses/serializes `xcodebuild -showBuildSettings` output |
| `missingPodfileInfoUtils.kt` | Podfile-related diagnostics |
| `tasks/PodspecTask.kt` | Publish: generates the `.podspec` |
| `tasks/DummyFrameworkTask.kt` | Publish: generates a placeholder framework so `pod install` succeeds pre-build |
| `tasks/PodGenTask.kt` | Consume: generates the synthetic Podfile |
| `tasks/AbstractPodInstallTask.kt` / `PodInstallTask.kt` / `PodInstallSyntheticTask.kt` | Consume: run `pod install` |
| `tasks/PodSetupBuildTask.kt` | Consume: harvest build settings via `xcodebuild -showBuildSettings` |
| `tasks/PodBuildTask.kt` | Consume: compile the pod via `xcodebuild` |
| `tasks/DefFileTask.kt` | Consume: generate the cinterop `.def` file |
| `tasks/CocoapodsTask.kt` | Base task class |

---

## Consumption direction (pod → Kotlin)

### User-facing DSL

The extension lives on the `kotlin {}` block as `cocoapods {}`. Dependencies are declared with `pod()`
([CocoapodsExtension.kt](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/CocoapodsExtension.kt)):

```kotlin
@JvmOverloads
fun pod(
    name: String,
    version: String? = null,
    path: File? = null,
    moduleName: String = name.asModuleName(),
    headers: String? = null,
    linkOnly: Boolean = false,
)
fun pod(name: String, configure: CocoapodsDependency.() -> Unit)   // block form
```

The dependency model:

```kotlin
abstract class CocoapodsDependency {
    var moduleName: String                          // ObjC module to import
    var version: String? = null
    var source: PodLocation? = null                 // git(...) or path(...); null → CocoaPods CDN
    var headers: String? = null                     // explicit header file(s) instead of a module
    var extraOpts: List<String> = listOf()          // extra cinterop/clang options
    var packageName: String = "cocoapods.$moduleName" // Kotlin package the bindings land in
    var linkOnly: Boolean = false                    // link the pod but generate no bindings
    val useClangModules: Property<Boolean>
    val interopBindingDependencies: MutableList<String>

    fun path(podspecDirectory: File): PodLocation
    fun git(url: String, configure: (Git.() -> Unit)? = null): PodLocation
    fun useInteropBindingFrom(podName: String)       // share bindings across inter-dependent pods
}

sealed class PodLocation {
    data class Path(val dir: File) : PodLocation()
    data class Git(val url: URI, var branch: String?, var tag: String?, var commit: String?) : PodLocation()
}
```

DSL variants, from the docs
([multiplatform-cocoapods-libraries](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-libraries.html)):

- **Versioned, from the CocoaPods CDN:** `pod("SDWebImage") { version = "5.20.0" }` → `import cocoapods.SDWebImage.*`
- **Local path:** `pod("pod_dependency") { source = path(project.file("../pod_dependency")) }`
- **Subspec:** `pod("subspec_dependency/Core") { … }` — the `/Core` suffix selects a subspec
- **Git:** `pod("SDWebImage") { source = git("…/SDWebImage") { tag = "5.20.0" } }`; also `branch`/`commit`. Priority: `commit` > `tag` > `branch` > HEAD.
- **Custom spec repo:** `specRepos { url("https://github.com/Kotlin/kotlin-cocoapods-spec.git") }` then `pod("example")`
- **Custom cinterop opts / package:** `pod("FirebaseAuth") { packageName = "FirebaseAuthWrapper"; extraOpts += listOf("-compiler-option", "-fmodules") }`
- **`linkOnly = true`** — link the pod (dynamic framework) without generating Kotlin bindings.
- **`useInteropBindingFrom("OtherPod")`** — when pods share types, reuse another pod's cinterop klib to avoid duplicate type definitions.

### Full task graph: `pod("AFNetworking")` → usable Kotlin

Registered in [`KotlinCocoapodsPlugin.kt`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/KotlinCocoapodsPlugin.kt). Task-name constants:

```kotlin
const val COCOAPODS_EXTENSION_NAME  = "cocoapods"
const val POD_SPEC_TASK_NAME        = "podspec"
const val DUMMY_FRAMEWORK_TASK_NAME = "generateDummyFramework"
const val POD_INSTALL_TASK_NAME     = "podInstall"
const val POD_GEN_TASK_NAME         = "podGen"
const val POD_SETUP_BUILD_TASK_NAME = "podSetupBuild"
const val POD_BUILD_TASK_NAME       = "podBuild"
const val POD_IMPORT_TASK_NAME      = "podImport"
const val SYNC_TASK_NAME            = "syncFramework"
```

The consumption chain (task names are suffixed by pod / platform-family / target):

```
podGen[Family]                 (PodGenTask)              generate synthetic Podfile for the platform family
        │
        ▼
podInstall[Family]Synthetic    (PodInstallSyntheticTask) run `pod install` on the synthetic project
        │
        ▼
podSetupBuild[Pod][Target]     (PodSetupBuildTask)       xcodebuild -showBuildSettings → build-settings props file
        │
        ▼
podBuild[Pod][Target]          (PodBuildTask)            xcodebuild compiles the pod (produces .framework/.a)
        │
        ▼
cinterop<moduleName>[Target]   (CInteropProcess)         runs cinterop over the generated .def → .klib
        │        ▲
        │        └── generateDefFile (DefFileTask) produces the .def consumed here
        ▼
podImport                      (DefaultTask)             umbrella; dependsOn podInstall + every cinterop task
```

Notes on wiring:
- `podImport` is the IDE-sync entry point — "Called on Gradle sync, depends on Cinterop tasks for every used pod."
- Each `CInteropProcess` task `dependsOn` the pod's `podBuild` task, and in a `doFirst` it reads the harvested build settings and injects `-framework <name>` and `-F <search path>` compiler options before cinterop runs.
- Transitive pod dependencies are wired recursively via `addPodDependencyToInterop()`.

### Headers → def file → Kotlin bindings

The plugin obtains headers by generating a **synthetic Xcode project**, not by touching the user's real one.
[`PodGenTask`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/PodGenTask.kt)
writes a Podfile at `cocoapodsBuildDirs.synthetic(<family>)/Podfile` containing:

- `spec_repos` source declarations,
- `platform :ios, '<deploymentTarget>'`,
- `target '<family.platformLiteral>' do … use_frameworks! … end`,
- one `pod '<name>', …` line per declared dependency (with version or `:path`/`:git` source, subspecs),
- a `post_install` hook that disables code-signing and applies Xcode-14.3+ deployment-target workarounds.

`pod install` on that synthetic Podfile downloads pods and lays out their sources/headers under a
`Pods/` tree. `xcodebuild` then compiles them ([`PodBuildTask`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/PodBuildTask.kt)),
and [`PodSetupBuildTask`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/PodSetupBuildTask.kt)
runs `xcodebuild -showBuildSettings -project … -scheme … -sdk <sdk>` and parses the output
(`PodBuildSettingsProperties`) into a properties file — this is where `FRAMEWORK_SEARCH_PATHS`,
`HEADER_SEARCH_PATHS`, `PODS_ROOT`, `CONFIGURATION`, etc. come from.

The `.def` file is generated on the fly by
[`DefFileTask`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/DefFileTask.kt).
Its contents are deliberately minimal:

```
language = Objective-C
# then either:
modules = <moduleName>
linkerOpts = -framework <moduleName>
# or, if the pod exposes explicit headers instead of a clang module:
headers = <headers>
```

Notably the `.def` does **not** carry `compilerOpts`, `package`, or `-F` search paths — those are
injected into the `CInteropProcess` task at configuration time (from the harvested build settings and
`extraOpts`/`packageName`), not baked into the def file. The comment in the source explains that the
`linkerOpts = -framework <name>` line exists so the linker flag is recorded in the produced klib's
manifest, so that downstream consumers who depend on the cinterop library also get the flag.

`cinterop` then reads the Objective-C module/headers and emits Kotlin externals into the configured
`packageName` (default `cocoapods.<moduleName>`), which the user imports via `import cocoapods.<Pod>.*`.

### Multiple targets, transitive deps, deployment targets

- **Per Apple target family.** `podGen` and `podInstallSynthetic` are keyed by platform *family*
  (ios/osx/tvos/watchos), while `podSetupBuild`/`podBuild`/cinterop are keyed by concrete target
  (`iosArm64`, `iosSimulatorArm64`, …). Binding generation is effectively **per target** — cinterop
  runs for each target's `MAIN_COMPILATION_NAME` compilation:
  ```kotlin
  kotlinExtension.supportedAppleTargets().all { target ->
      val cinterops = target.compilations.getByName(MAIN_COMPILATION_NAME).cinterops
      cinterops.create(pod.moduleName) { … interopTask.dependsOn(podBuildTaskProvider) }
  }
  ```
- **Deployment targets** come from `ios.deploymentTarget`/`osx.deploymentTarget`/… on the extension
  (`PodspecPlatformSettings`) and are written into the synthetic Podfile's `platform` line. If a pod
  requires a higher minimum than declared, `pod install` fails with an explicit error.
- **Transitive pods** are resolved by CocoaPods itself during `pod install`; the plugin wires cinterop
  dependencies recursively (`addPodDependencyToInterop`), and `useInteropBindingFrom` lets one pod's
  bindings reference another's to avoid duplicate ObjC type definitions.

### Artifact locations & caching

- Everything lives under a `cocoapodsBuildDirs` root in the Gradle build dir: `synthetic(<family>)/`
  (Podfile + generated `.xcodeproj`/`.xcworkspace` + `Pods/`), build-settings properties files
  (`buildSettings(pod, target)`), compiled pod outputs, generated `.def` files.
- `PodBuildTask` and `PodSetupBuildTask` are annotated `@DisableCachingByDefault` — the `xcodebuild`
  steps are **not** build-cacheable, only incrementally up-to-date-checked via declared inputs/outputs
  (pod source dir, build-settings file, target device id from env). `DefFileTask` is a normal cacheable
  task keyed on the `@Nested` `pod` model. The heavyweight `pod install` / `xcodebuild` steps are the
  slow, non-cacheable part of the graph.

### Missing / wrong external tooling — error UX

[`AbstractPodInstallTask`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/AbstractPodInstallTask.kt):

- Detects the tool with `which pod`. If not found → explicit error: *"CocoaPods executable not found in
  your PATH…"* with Homebrew/RubyGems install instructions and a hint to set the path in `local.properties`.
- Runs `pod install` in the Podfile's directory with `LC_ALL=en_US.UTF-8` (CocoaPods needs UTF-8).
- **Self-healing fallback:** if `pod install` fails complaining the spec repos are out of date, it
  automatically retries with `--repo-update`.
- Targeted diagnostics for common failure classes: Ruby-version problems (suggests updating Ruby),
  CocoaPods too old (recommends v1.14+), and `Xcodeproj` errors (recommends upgrading CocoaPods).

### Interop semantics of generated bindings (summary)

cinterop over an Objective-C module yields Kotlin externals where ObjC classes become Kotlin classes,
ObjC types surface as **platform types** (`T!`) with unspecified nullability unless the headers carry
`_Nullable`/`_Nonnull` audits, selectors map to Kotlin function names, and everything lands in the
`cocoapods.<moduleName>` (or custom `packageName`) package. The *mechanism* — reading ObjC headers and
mapping them to externals — is the important part here; the type-mapping detail is well-trodden ObjC
interop and less relevant to the NuGet analog than the resolution/build pipeline.

---

## Publish direction (Kotlin → pod)

### Podspec generation & framework packaging

[`PodspecTask`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/PodspecTask.kt)
generates a `.podspec` with `spec.name`, `spec.version`, `homepage`/`source`/`authors`/`license`/`summary`,
`spec.libraries = 'c++'`, per-platform deployment targets, and `spec.vendored_frameworks` pointing at
either a `.framework` (local integration) or a `.xcframework` (publishing). A `version` must be set
(not the Gradle default).

The **dummy-framework trick**: because `pod install` needs a framework to exist *before* the Kotlin code
is compiled, [`DummyFrameworkTask`](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/DummyFrameworkTask.kt)
(`generateDummyFramework`) produces a placeholder framework so `podInstall` (which `dependsOn` both
`podspec` and `generateDummyFramework`) can succeed. The real framework is later built and copied into the
CocoaPods build dir by `syncFramework` (`FrameworkCopy`, `SYNC_TASK_NAME`). For distribution, a separate
`podPublishXCFramework` task produces Release and Debug `.xcframework`s plus matching podspecs.

### Xcode-side integration (the MSBuild analog)

The generated podspec embeds a `script_phases` block that runs **before compile** and calls back into
Gradle. Paraphrasing the generated Ruby:

```ruby
spec.script_phases = [
  {
    :name => 'Build <framework>',
    :execution_position => :before_compile,
    :shell_path => '/bin/sh',
    :script => <<-SCRIPT
      "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :<projectPath>:syncFramework \
        -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
        -Pkotlin.native.cocoapods.archs="$ARCHS" \
        -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
    SCRIPT
  }
]
```

So when Xcode builds the app, CocoaPods runs this script phase, which invokes `./gradlew syncFramework`,
passing the Xcode build environment (`PLATFORM_NAME`, `ARCHS`, `CONFIGURATION`) back to Gradle as `-P`
properties (`PLATFORM_PROPERTY`, `ARCHS_PROPERTY`, `CONFIGURATION_PROPERTY`). Gradle builds the framework
for exactly the platform/arch/config Xcode asked for and copies it into place. This is the reverse of
consumption: **the foreign build tool drives the Gradle build via a script hook**. (There is also a
non-CocoaPods "direct integration" path, `embedAndSignAppleFrameworkForXcode`, which is mutually
exclusive with CocoaPods integration.)

---

## Meta / architecture

- **Layered on KMP.** The plugin activates only when the multiplatform plugin is present:
  ```kotlin
  pluginManager.withPlugin("kotlin-multiplatform") {
      enableCInteropCommonizationSetByExternalPlugin()
      …
  }
  ```
- **Deferred configuration.** Because target/pod configuration isn't known until the build script is
  fully evaluated, task creation and wiring happen inside `project.whenEvaluated { … }` /
  `runProjectConfigurationHealthCheckWhenEvaluated { … }` (KGP's `afterEvaluate` equivalents). Per-target
  cinterop tasks are registered by iterating `kotlinExtension.supportedAppleTargets().all { … }`.
- **Extension + task-per-unit-of-work model.** One `CocoapodsExtension` holds the DSL; the plugin fans it
  out into many small, single-responsibility tasks (`PodGenTask`, `PodInstallTask`, `PodBuildTask`,
  `DefFileTask`, …), each shelling out to one external command and declaring precise inputs/outputs.
  `PodBuildSettingsProperties` is a serialize/parse helper passed between tasks via a properties file
  (task-to-task data handoff through the filesystem, which keeps tasks independently up-to-date-checkable).
- **Diagnostics** are centralized in `CocoapodsPluginDiagnostics.kt` / `missingPodfileInfoUtils.kt`.

### Known limitations & pain points

- **Pure-Swift pods are unsupported.** Kotlin/Native's cinterop reads **Objective-C** headers only; it
  doesn't parse Swift modules. Swift APIs are only visible if the pod also exposes a generated ObjC header
  (`-Swift.h`, i.e. `@objc`-annotated, `NSObject`-derived API). Docs and community consistently recommend
  ObjC alternatives; third-party workarounds exist (e.g. a "Swift klib" plugin, or wrapping SPM as an
  XCFramework). ([libraries doc](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-libraries.html),
  [dev.to: Swift-only libraries in KMP](https://dev.to/ttypic/going-swiftly-using-a-swift-only-libraries-in-your-kotlin-multiplatform-app-1ml9))
- **External toolchain required.** Needs a working Ruby + CocoaPods (v1.14+) + Xcode; Homebrew-installed
  CocoaPods is explicitly *not recommended* due to Xcodeproj/Xcode incompatibilities. This is macOS-only.
  ([overview doc](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-overview.html))
- **Slow, non-cacheable steps.** `pod install` and `xcodebuild` dominate build time and are
  `@DisableCachingByDefault`.
- **Mutually exclusive with `embedAndSignAppleFrameworkForXcode`** direct integration.
- **Config-time coupling** to `afterEvaluate` and synthetic-project layout makes the graph fragile across
  CocoaPods/Xcode version bumps (hence the version-specific `post_install` workarounds and targeted error
  messages).

---

## Transferable patterns for a NuGet plugin

| CocoaPods mechanism | NuGet-plugin analog / recommendation | Confidence / open question |
|---|---|---|
| `cocoapods { pod("Name") { version/source=git()/path()/subspec/extraOpts/packageName } }` | Mirror with `nuget { dependency("Name") { version = …; source = …; packageName = … } }` on the `kotlin {}` block (ROADMAP already sketches `nuget { dependencies { … } }`). Keep the same "version | git | local path | custom feed | link-only" axis. | Direct — DSL shape transfers cleanly. |
| Synthetic Podfile + `pod install` to resolve & download | **Generate a throwaway `.csproj` (or `packages.config`) + `dotnet restore`** to resolve NuGet v3 feeds and materialize the package tree under the build dir. This is the single closest analog. | Recommended. Open: whether to require a `dotnet` SDK prerequisite (as CocoaPods requires `pod`) or ship a pure-JVM NuGet v3 client. |
| Headers obtained by *compiling* the pod, not reading binaries | We **must** read binaries: extract the public API from **.NET assembly (ECMA-335) metadata**. No compile step needed to "get headers" — the DLL *is* the header. | Different, and simpler: no synthetic-build/`xcodebuild` equivalent needed just to get the API surface. Open (ROADMAP): pure-JVM ECMA-335 reader vs bundled `dotnet` dump tool. |
| `DefFileTask` generates a minimal `.def`; search paths/opts injected into the cinterop task | Generate a **reverse-IR model** (mirror of CIR) from assembly metadata, then a KotlinPoet emitter for the externals. Keep binding-generation options (package name, extra opts) as task config, not baked into an intermediate file — matches how CocoaPods keeps `-F`/`compilerOpts` out of the def. | Recommended pattern: thin generated artifact + rich task config. |
| Per-target-family cinterop; bindings per Kotlin target | NuGet packages carry RID-specific `runtimes/<rid>/native/` payloads. Generate/resolve **per Kotlin/Native target ↔ RID** (e.g. `macosArm64` ↔ `osx-arm64`, `mingwX64` ↔ `win-x64`). The metadata (managed) API is target-independent; only the native payload and P/Invoke entry points are per-RID. | Open: managed-only packages need no per-RID split; native-bearing packages do. Decide the RID mapping table. |
| Build settings harvested via `xcodebuild -showBuildSettings` into a props file passed between tasks | If a resolve step is needed, pass resolved paths/asset info between tasks via a serialized manifest file (like `PodBuildSettingsProperties`) so each task stays independently up-to-date-checkable. `dotnet restore` already emits `project.assets.json` — reuse it as that manifest. | Recommended: consume `obj/project.assets.json` instead of inventing a format. |
| `which pod` detection + explicit install-guidance errors + `--repo-update` self-heal | Detect `dotnet` on PATH (or a configured `local.properties` path); on absence emit an explicit "install the .NET SDK 8+" message. Consider a restore-retry with cache-clear on transient feed failures. | Recommended. Fail-fast per project conventions (CLAUDE.md). |
| Publish: `.podspec` with `vendored_frameworks` + `script_phases` calling back `./gradlew syncFramework` | Already solved differently in this repo (pre-generated `Interop.cs` shipped in the `.nupkg`, no consumer-side callback). Note the divergence: CocoaPods needs a **callback into Gradle at Xcode build time** because it ships source-driven frameworks; our forward direction deliberately avoids that by pre-generating. **If** we ever need per-consumer-config native builds, an **MSBuild `.targets`/`.props` script that invokes Gradle** would be the exact analog of the podspec `script_phases`. | Divergent by design (see README/ADR-001). Keep the callback pattern in the back pocket for any future "build native on the consumer's RID" feature. |
| Dummy-framework trick so `pod install` succeeds before real build | If we ever generate a `.csproj` that must reference a not-yet-built artifact, a placeholder assembly/`.nupkg` may be needed so `dotnet restore`/build succeeds ahead of Kotlin compilation. | Open — only relevant if we adopt a two-phase restore/build. |
| Layered-on-KMP, `afterEvaluate` per-target registration, extension + small tasks | Same architecture: apply on top of the multiplatform plugin, register resolve/generate tasks in `whenEvaluated`, one task per external-tool invocation with precise IO. | Direct — matches existing plugin structure. |
| Pure-Swift unsupported because cinterop reads ObjC only | Our analog constraint: we read **.NET managed metadata**, which is uniform across C#/F#/VB — so **no equivalent "language subset" gap** for managed APIs. The real gaps are unmanaged/`unsafe` exports and generics/`Task`/collections mapping fidelity (already tracked in Phase 8). | Advantage over CocoaPods: managed metadata is a stronger, language-agnostic contract than ObjC headers. |

---

## Sources

- Plugin source directory: <https://github.com/JetBrains/kotlin/tree/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods>
- `KotlinCocoapodsPlugin.kt`, `CocoapodsExtension.kt`, and `tasks/*` (linked inline above)
- Docs: [CocoaPods overview & setup](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-overview.html) ·
  [Add dependencies on a Pod library](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-libraries.html) ·
  [DSL reference](https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-dsl-reference.html)
- Swift-only limitation: [dev.to — Going Swiftly](https://dev.to/ttypic/going-swiftly-using-a-swift-only-libraries-in-your-kotlin-multiplatform-app-1ml9)

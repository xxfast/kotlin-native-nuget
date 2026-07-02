# Swift Package Manager (SPM) integrations for Kotlin Multiplatform — architecture research

> Prior-art study for the `kotlin-native-nuget` Gradle plugin. SPM integrations are the closest analogue
> to what we are building in **both** directions: publishing Kotlin/Native as a consumable package (our
> forward NuGet direction, [ROADMAP](../../ROADMAP.md) Phases 1–7) and consuming an existing third-party
> package back into Kotlin (our reverse direction, [ROADMAP](../../ROADMAP.md) Phase 8). This document
> studies three integrations — the community **spm4Kmp** plugin (consumption), Touchlab **KMMBridge**
> (publish), and **JetBrains' official** tooling — and closes with concrete transferable patterns.

## Executive summary

The SPM ecosystem splits cleanly into a *publish* side and a *consume* side, and neither is a
single-artifact story the way NuGet is. On the **consume** side, the decisive architectural fact is that
SPM distributes either *source* or a *binary XCFramework* — there is no header/API-metadata registry — so
to turn a Swift package into Kotlin bindings you must first **compile it into a linkable artifact and then
run cinterop over an Objective-C-visible surface**. spm4Kmp does exactly this: it generates a local
"bridge" Swift package, compiles it per Apple target into a static library, synthesizes cinterop `.def`
files, and invokes Kotlin/Native's cinterop — while forcing the developer to hand-write `@objc`/`public`
bridge code because Kotlin/Native can only see the Objective-C-compatible slice of Swift. On the
**publish** side, KMMBridge packages the Kotlin compiler's XCFramework output into a zip, "parks" it in
some storage backend, and **generates a `Package.swift` binary-target manifest** pointing at the hosted
zip plus checksum, with a local-dev-vs-published dual flow. JetBrains officially owns the publish path
(XCFramework + `Package.swift`, plus the emerging idiomatic *Swift export*), owns an Alpha *SwiftPM
import* consume path that is still Objective-C-only, and leaves the richer consume ergonomics to the
community. For our NuGet plugin the single most important contrast is that **NuGet already ships
machine-readable API metadata (ECMA-335 assemblies), so unlike SPM we do not need to compile a package to
recover its API surface** — the reverse direction is closer to CocoaPods (metadata shipped) than to SPM,
but the *export boundary* problem (only part of the API is directly callable across the ABI) maps directly
onto spm4Kmp's `@objc` boundary.

---

## 1. spm4Kmp (`io.github.frankois944.spmForKmp`) — the consumption direction

Repository: <https://github.com/frankois944/spm4Kmp> · Docs: <https://spmforkmp.eu/> ·
Maintainer: François Dabonot (frankois944).

### 1.1 Positioning and user-facing DSL

spm4Kmp is a **Gradle plugin** that lets a KMP project consume Apple Swift packages, positioned as "a
modern alternative to the deprecated CocoaPods Plugin" that "uses the native Swift Package Manager, so no
third-party dependencies are required"
(<https://spmforkmp.eu/>, <https://github.com/frankois944/spm4Kmp/blob/main/README.md>).

Application and required property:

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.frankois944.spmForKmp") version "<version>"
}
```

It requires `kotlin.mpp.enableCInteropCommonization=true` in `gradle.properties`
(<https://github.com/frankois944/spm4Kmp/blob/main/README.md>).

The DSL is attached **per Apple target** via a `swiftPackageConfig` extension keyed by a `cinteropName`;
that name selects a source directory `src/swift/[cinteropName]` (defaulting to the target name if omitted)
(<https://spmforkmp.eu/bridge/>):

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.swiftPackageConfig(cinteropName = "[cinteropName]") {
            // declare dependencies + products here
            // remote and local Swift packages, plus which products to export to Kotlin
        }
    }
}
```

Products are exported to Kotlin with an explicit opt-in (`exportToKotlin = true`), and because some
libraries publish under an internal product name there is a **`ProductName` alias** mechanism — e.g.
`ProductName("FirebaseFirestoreInternal", alias = "FirebaseFirestore")`
(<https://github.com/frankois944/spm4Kmp/releases>,
<https://github.com/frankois944/spm4Kmp/blob/main/README.md>). Both remote (URL + version) and local
(filesystem path) Swift package dependencies are supported, mirroring SPM's own `Package.swift` dependency
kinds (<https://spmforkmp.eu/>).

### 1.2 The full pipeline: declared package → Kotlin bindings

This is the load-bearing part. On sync, the plugin runs roughly these steps
(<https://spmforkmp.eu/bridge/>; maintainer's own step list reproduced on the Kotlin Slack,
<https://slack-chats.kotlinlang.org/t/26808224/>):

1. **Bridge folder creation** — generate the bridge directory `src/swift/[cinteropName]` (or
   `src/swift/[targetName]` if unnamed), seeded with a `StartYourBridgeHere.swift` template (suppressible
   via `gradle.properties`).
2. **Manifest generation** — synthesize a local Swift package **`Package.swift` manifest** from the Gradle
   declaration (the declared remote/local dependencies and exported products become that manifest's
   dependencies/targets).
3. **Per-target compilation** — compile the bridge package **for each declared Apple target**
   (`iosArm64`, `iosSimulatorArm64`, `macosArm64`, …) into a **single static library (`.a`)** using the
   native Swift toolchain (`swift build`-style compilation, not a full `xcodebuild` app build).
4. **`.def` file synthesis** — create a cinterop `.def` file for the manifest's **main product** (the
   easy case), and additionally a `.def` for **each SPM dependency marked as exported** (described by the
   maintainer as "the hardest" part).
5. **cinterop invocation** — call Kotlin/Native's **cinterop** task for each `.def` file, producing Kotlin
   bindings. Net effect: the plugin "is basically a wrapper for cinterop"
   (<https://medium.com/@math.perroud/yes-calling-swift-from-kotlin-multiplatform-is-easy-no-plugins-no-magic-just-cinterop-e4bf37caf5bf>).

Data-flow summary: **Swift code (`@objc`) → Objective-C bridge → C headers → cinterop → Kotlin bindings**
(<https://medium.com/@shivathapaa/bridging-swift-to-kotlin-multiplatform-a-complete-guide-to-cinterop-in-multi-module-projects-af54d88f4b4f>).
Build output lands in the target's intermediate build directory hierarchy; caching is per-target under the
Gradle build dir (<https://spmforkmp.eu/bridge/>).

### 1.3 The `@objc` boundary — how non-Objective-C-compatible Swift is surfaced

This is the most important design constraint and the tightest analogy to our own export problem.
Kotlin/Native's Apple interop is **Objective-C-based**, so only the Objective-C-visible slice of a Swift
API is reachable. spm4Kmp makes this explicit: **"Swift code must be annotated with `@objc` /
`@objcMembers` and declared `public` to be visible in Kotlin"** — and, crucially, **"Pure Swift packages
cannot be exported directly to Kotlin"**; the plugin's job is to help you *hand-write a bridge* that works
around that limitation (<https://spmforkmp.eu/bridge/>, <https://spmforkmp.eu/>). Typical bridge code:

```swift
@objcMembers public class MySwiftBridge: NSObject {
    public func exportedMethod() -> String { return "value" }
}
```

consumed from Kotlin as:

```kotlin
import [cinteropName].MySwiftBridge
val content = MySwiftBridge().exportedMethod()
```

Only `NSObject`-rooted, `@objc`, `public` declarations cross; Swift-only constructs (generics, enums with
associated values, structs, protocol extensions, `async`) do **not** and must be re-expressed in the bridge
as Objective-C-compatible signatures (bridged types like `String`↔`NSString`, `Bool`↔`BOOL`, etc.)
(<https://kotlinlang.org/docs/native-objc-interop.html>,
<https://medium.com/@shivathapaa/bridging-swift-to-kotlin-multiplatform-a-complete-guide-to-cinterop-in-multi-module-projects-af54d88f4b4f>).
The plugin communicates the boundary structurally: the generated `src/swift/[cinteropName]` folder *is*
where you are expected to write that `@objc` surface, and anything not placed there / not `@objc` simply
never appears in the cinterop output.

### 1.4 External tooling, artifacts, per-target handling, limitations

- **Tooling**: relies on the native Swift toolchain (`swift build`-style compilation) rather than
  `xcodebuild` app builds; requires a macOS host with Xcode/Swift installed. It shells out to compile the
  bridge package and then hands `.def` files to cinterop.
- **Per-target**: everything (compile → `.a` → `.def` → cinterop) is repeated **per Apple target**;
  `enableCInteropCommonization=true` lets the resulting cinterops commonize across the Apple source set.
- **Artifacts/caching**: compiled static libs, `.def` files, and cinterop klibs live under the Gradle
  build directory per target (<https://spmforkmp.eu/bridge/>).
- **Known limitations**: pure-Swift APIs need a hand-written `@objc` bridge; some products require the
  `ProductName` alias to export at all (e.g. Firebase); macOS/Xcode required; dependency `.def`
  generation for transitive exported products is the fragile part (<https://spmforkmp.eu/>,
  <https://github.com/frankois944/spm4Kmp/blob/main/README.md>,
  <https://github.com/frankois944/spm4Kmp/issues/163>).

### 1.5 Contrast with the CocoaPods plugin

The Kotlin CocoaPods plugin runs `pod install` and cinterops the pod's **shipped headers/vendored
frameworks** — a podspec carries source and/or prebuilt frameworks *plus headers*, so a module is
available without the plugin compiling anything itself. SPM has no header-in-registry equivalent: a Swift
package is source or an XCFramework, with no exported C/ObjC header set, so **spm4Kmp must compile the
package to obtain a linkable module and then still synthesize `.def` files** — the extra compile-and-bridge
stage is a direct consequence of SPM's distribution model, not an implementation choice.

---

## 2. KMMBridge (Touchlab) — the publish direction

Docs: <https://kmmbridge.touchlab.co/docs/> · Repo: <https://github.com/touchlab/KMMBridge>.

### 2.1 What it does and how it packages

KMMBridge is "a set of Gradle tooling that facilitates publishing and consuming pre-built Kotlin
Multiplatform Xcode Framework binaries" (<https://kmmbridge.touchlab.co/docs/>). The Kotlin compiler emits
Xcode Frameworks per architecture; KMMBridge converts these into an **XCFramework**, zips it, uploads the
zip to a storage backend, then generates and publishes SPM/CocoaPods configuration pointing at it
(<https://kmmbridge.touchlab.co/docs/WHAT_ARE_WE_DOING/>). Core tasks: `zipXCFramework`,
`uploadXCFramework`, and `kmmBridgePublish` (<https://kmmbridge.touchlab.co/docs/WHAT_ARE_WE_DOING/>,
<https://kmmbridge.touchlab.co/docs/artifacts/MAVEN_REPO_ARTIFACTS/>).

### 2.2 `Package.swift` generation and how the manifest points at the binary

For SPM, KMMBridge generates a **`Package.swift` at the repo root** whose target is a **`binaryTarget`**
referencing the hosted zip **URL + checksum**, then commits and tags it. The maintainers describe the
publish sequence as: upload the XCFramework zip → get the URL → create `Package.swift` pointing at that zip
→ commit it → tag with the version (<https://kmmbridge.touchlab.co/docs/WHAT_ARE_WE_DOING/>). This is the
same manifest shape JetBrains documents for manual export (`.binaryTarget(name, url, checksum)`,
<https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html>) — KMMBridge just automates it.

### 2.3 Artifact hosting strategies and versioning

KMMBridge deliberately **decouples binary storage from dependency resolution**: it ships artifact managers
for **AWS S3** and **Maven repositories**, but "the URL where we host the zip file can be anywhere" as long
as the package manager can reach it (<https://kmmbridge.touchlab.co/docs/WHAT_ARE_WE_DOING/>). The Maven
path is notable: it does **not** use Maven dependency metadata — it "uses the maven repo as a standard
place to 'park' our zip file," extracting only the direct artifact URL to inject downstream; GitHub
Packages / Artifactory / JetBrains Space all work through this
(<https://kmmbridge.touchlab.co/docs/artifacts/MAVEN_REPO_ARTIFACTS/>), configured simply via
`addGithubPackagesRepository()`. Versioning strategies include **timestamp** (`timestampVersions()` →
`2.3.<epochMillis>`), **manual** (`manualVersions()`), and auto-increment; strict semver via git tags is
used for the SPM manifest (<https://kmmbridge.touchlab.co/docs/general/CONFIGURATION_OVERVIEW/>,
<https://kmmbridge.touchlab.co/docs/artifacts/MAVEN_REPO_ARTIFACTS/>).

### 2.4 Local-dev vs published-consumption dual flow

A first-class concern: during **local development** `Package.swift` references the *locally built*
XCFramework (fast iteration, `ENABLE_PUBLISHING=false` skips publish-plugin wiring); for **published**
versions it points at the *remote zip URL + checksum*. Because publishing mutates the git repo (rewrite
`Package.swift`, add a tag), the CI workflow does this on a **temporary build branch**, tags the commit,
then deletes the branch — keeping tagged commits without polluting `main`
(<https://kmmbridge.touchlab.co/docs/WHAT_ARE_WE_DOING/>,
<https://kmmbridge.touchlab.co/docs/general/CONFIGURATION_OVERVIEW/>).

### 2.5 Gradle architecture / DSL

Applied to the framework-producing module (`id("co.touchlab.kmmbridge")`), configured through a
`kmmbridge {}` block backed by `KMMBridgeExtension`. Every config needs **one ArtifactManager** plus **at
least one DependencyManager** (`SpmDependencyManager` and/or `CocoapodsDependencyManager`). Options include
`buildType` (defaults to Release), `frameworkName`, and the versioning selectors above. It layers directly
on top of the standard KMP framework/XCFramework tasks rather than replacing them
(<https://kmmbridge.touchlab.co/docs/general/CONFIGURATION_OVERVIEW/>).

---

## 3. JetBrains' official direction

JetBrains splits its official story into three tracks: local integration, package export, and the newer
idiomatic Swift export — plus an Alpha SwiftPM *import* (consume) track.

### 3.1 Local integration — direct integration / `embedAndSign` (the default recommendation)

For app-local iOS integration JetBrains recommends **direct integration** via the
`embedAndSignAppleFrameworkForXcode` Gradle task wired into an Xcode "Run Script" build phase (the
`embedAndSign` flow). This is the low-friction default for a single codebase and involves no package
manager at all (<https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html>).

### 3.2 Swift package export (publish) — official but manual

JetBrains documents exporting a KMP module as a SwiftPM binary dependency: build an **XCFramework**
(`assembleSharedXCFramework`), zip it, upload it to storage, compute a checksum
(`swift package compute-checksum Shared.xcframework.zip`), and author a `Package.swift` with a
`.binaryTarget(name, url, checksum)`. They recommend a **two-repository** model — one for the binary zip
(GitHub Releases / S3 / Maven), one git repo for the versioned `Package.swift` manifest. This page is
**not marked experimental** (<https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html>).
KMMBridge is precisely the automation over this manual recipe.

### 3.3 SwiftPM import (consume) — official, Alpha, Objective-C-only

As of Kotlin **2.4.0-RC2** JetBrains ships official **SwiftPM import** (Alpha). It imports **Clang modules
/ Objective-C APIs** from SwiftPM dependencies declared for Apple targets, generating an intermediary
**synthetic package** for dependency tracking. DSL:

```kotlin
kotlin {
    iosArm64(); iosSimulatorArm64()
    swiftPMDependencies {
        swiftPackage(
            url = url("https://github.com/firebase/firebase-ios-sdk.git"),
            version = from("12.5.0"),
            products = listOf(product("FirebaseAnalytics")),
        )
    }
}
```

with `localSwiftPackage()` for filesystem deps, `iosMinimumDeploymentTarget`, and implicit Clang-module
discovery. A one-time `./gradlew :lib:integrateLinkagePackage` (with `XCODEPROJ_PATH`) generates the
synthetic package and links the Xcode project; imports appear under
`swiftPMImport.<group>.<project>.<Module>`. Limitations: **only Objective-C-compatible APIs** are exposed
(same `@objc` boundary as spm4Kmp); a module using SwiftPM import **cannot itself be exported** as a Swift
package (KT-84420); dynamic-framework symbol clashes need `isStatic = true`; `Package.resolved` must be
committed (<https://kotlinlang.org/docs/multiplatform/multiplatform-spm-import.html>).

### 3.4 Swift export — idiomatic Kotlin→Swift without Objective-C

Separately, JetBrains is building **Swift export**: exporting Kotlin **directly as native Swift modules**,
eliminating the Objective-C intermediary and enabling idiomatic Swift calls. It is **experimental in
Kotlin 2.2.20** and **moving to Alpha in 2.4**; each Kotlin module becomes a separate Swift module.
Architecturally it is "a lazy Analysis-API-to-Swift translator" that generates **thin Kotlin bridges with a
C header** (a C API over the Kotlin binary), compiled by Kotlin/Native like ordinary sources. Current
limits: **only final classes directly inheriting `Any`**, and **no cross-language inheritance** (Swift
classes cannot subclass Kotlin-exported types)
(<https://kotlinlang.org/docs/native-swift-export.html>,
<https://github.com/JetBrains/kotlin/blob/master/docs/swift-export/architecture.md>,
<https://github.com/Kotlin/swift-export-sample>,
<https://kotlinlang.org/docs/whatsnew2220.html>).

### 3.5 Where the official gaps are (what the community fills)

- **Publish**: official recipe exists but is manual → **KMMBridge** automates hosting, versioning,
  `Package.swift`, and the local-vs-published flow.
- **Consume**: official SwiftPM import is Alpha and **Objective-C-only** → **spm4Kmp** predates it and adds
  the ergonomic bridge-package + `.def` + cinterop pipeline and product-export/alias handling. Both remain
  bounded by the same `@objc` visibility ceiling until Swift export matures in the reverse (Swift→Kotlin)
  direction, which is not yet its focus.

---

## 4. Transferable patterns for a NuGet plugin

Each row maps an SPM-ecosystem finding to a concrete recommendation or open question for
`kotlin-native-nuget` (see [ROADMAP](../../ROADMAP.md) Phase 8 for the reverse direction).

### Consume direction (our Phase 8 — the primary analogy)

| SPM finding | NuGet plugin implication |
| --- | --- |
| SPM ships **source or a binary XCFramework, no API-metadata registry**, so spm4Kmp must *compile the package* to recover a module. | **Key divergence in our favor**: a NuGet package ships **.NET assemblies with ECMA-335 metadata** — the public API surface is already machine-readable. We do **not** need to compile the package to recover its API; we read metadata directly. Our reverse direction is closer to **CocoaPods** (metadata shipped) than to SPM. This validates the ROADMAP Phase 8 "extract the C# public API surface from .NET assembly metadata" task and lets us skip an entire compile stage spm4Kmp is forced into. |
| spm4Kmp generates a **local bridge Swift package**, compiles it to a `.a`, synthesizes `.def` files, runs cinterop. | Analogue: generate a **shim `.csproj`** that registers **`[UnmanagedCallersOnly]` function-pointer thunks** for the consumed NuGet API (ROADMAP "Generate C#-side registration shims"). Our `.csproj`+thunks ≈ their bridge package + `.def`; our reverse-IR modeling ≈ their `.def` synthesis. |
| **`@objc`/`public` visibility ceiling**: pure-Swift APIs are invisible; only the ObjC-compatible slice crosses, and the plugin makes users hand-write the bridge. | Our equivalent boundary is **what is directly callable across the C ABI / AOT-exportable**: generics dispatch, `ref struct`s, `Span<T>`, some async shapes won't cross cleanly. Decide early **which slice of a C# assembly we can auto-bridge** and, like spm4Kmp, **communicate the boundary structurally** (fail-fast KSP/Gradle diagnostics naming the unsupported member) rather than silently dropping it — consistent with our [CLAUDE.md](../../CLAUDE.md) fail-fast principle. Open question: do we require a hand-written adapter for non-mappable members (spm4Kmp's model), or restrict v1 to the auto-mappable subset (ROADMAP "v1: static methods, primitives, strings, void")? |
| spm4Kmp needs a **macOS + Swift toolchain** at Gradle build time (like CocoaPods needs `pod`). | Open question already flagged in ROADMAP: **pure-JVM ECMA-335 reader** (no extra prerequisite) **vs bundling a `dotnet` metadata-dump tool** (adds a .NET SDK prerequisite to the Kotlin-side build). SPM/CocoaPods normalize a native-tool prerequisite; a pure-JVM reader would be a genuine ergonomic edge for us — recommend pursuing it if feasible. |
| Per-Apple-target repetition (`iosArm64`, `iosSimulatorArm64`, …), commonized via `enableCInteropCommonization`. | Our analogue is **per-RID** handling (`win-x64`, `osx-arm64`, `linux-x64`) — we already do `runtimes/{rid}/native/`. The consumed C# API surface is RID-independent (managed metadata), so unlike spm4Kmp we can model the API **once** and only vary the native runtime payload. |
| `ProductName(alias = …)` and explicit `exportToKotlin = true` opt-in per product. | Provide a DSL to **select which NuGet packages/namespaces to bind** and optionally rename, e.g. `nuget { dependencies { … } }` (ROADMAP), rather than binding a package's entire transitive closure by default. |
| Kotlin's runtime call always in-process with the native code. | We have a structural advantage spm4Kmp lacks: the Kotlin/Native lib **already runs inside a .NET host process**, so Kotlin→managed-C# needs **no CLR hosting** — just an init-time **function-pointer registration table** (`[ModuleInitializer]` + `[UnmanagedCallersOnly]`), reusing the Phase 7 reverse-interop machinery. Confirm the standalone-Kotlin-host case (no .NET host) is out of scope for v1 or requires `hostfxr`. |

### Publish direction (our Phases 1–7 — already working; KMMBridge as a maturity checklist)

| SPM finding | NuGet plugin implication |
| --- | --- |
| KMMBridge **generates `Package.swift`** with a `binaryTarget(url, checksum)` pointing at a hosted zip. | Our analogue is **`.nuspec`/`.csproj` + `runtimes/{rid}/native/` layout generation** — the manifest that points a consumer at our native payload. Mature this to match KMMBridge's automation (ROADMAP pre-launch: publish to GitHub Packages / mavenCentral → for us, **push `.nupkg` to NuGet.org / GitHub Packages**). |
| **Storage decoupled from resolution**: zip can live anywhere; Maven repo used only to "park" the binary and hand back a URL. | NuGet has a **real package registry with metadata + hosting in one** — we do **not** need KMMBridge's "park the zip in Maven and extract a URL" hack. This is a genuine simplification: a `.nupkg` on a NuGet v3 feed is both the manifest and the payload. |
| **Local-dev vs published dual flow** (local `Package.swift` path vs remote URL; temp build branch to tag). | Provide the same ergonomics: a **local `ProjectReference`/local-feed** dev loop vs a published **`PackageReference`**, so consumers can iterate against a locally built package before it's published. Worth an explicit task/flag (KMMBridge's `ENABLE_PUBLISHING=false` analogue). |
| Versioning strategies: `timestampVersions()`, `manualVersions()`, git-tag semver. | Offer comparable version selectors for the generated `.nupkg` (we already have `version.set(...)`); consider a timestamp/CI-snapshot mode for pre-release feeds. |
| KMMBridge **layers on top of** standard KMP framework/XCFramework tasks rather than replacing them; `kmmbridge {}` extension. | Confirms our architecture: keep the `nuget {}` extension **layered on** the KMP shared-lib/link tasks (which we already do) rather than forking the KMP build. |

### Cross-cutting: idiomatic bindings ambition

Swift export's goal — **idiomatic Swift, not raw C/ObjC** — is exactly our KotlinPoet-generated idiomatic
C# ambition ([GOALS.md](../../GOALS.md) goal 2). Its architecture (thin Kotlin bridges exposing a **C API
over the binary**, compiled as ordinary Kotlin/Native sources) mirrors our `@CName`/`Interop.cs` bridge
layer. Its current limits (only final `Any`-rooted classes, no cross-language inheritance) are a useful
**early warning**: cross-runtime inheritance is hard, matching our own Phase 7 findings on implementing
interfaces across the boundary (ADR-039/040). Recommendation: treat cross-runtime inheritance/subclassing
as an explicitly scoped, later-phase capability in the reverse direction, not a v1 promise.

---

## Sources

- spm4Kmp README & repo — <https://github.com/frankois944/spm4Kmp>, <https://github.com/frankois944/spm4Kmp/blob/main/README.md>, <https://github.com/frankois944/spm4Kmp/releases>, <https://github.com/frankois944/spm4Kmp/issues/163>
- spm4Kmp docs — <https://spmforkmp.eu/>, <https://spmforkmp.eu/bridge/>
- spm4Kmp maintainer pipeline description (Kotlin Slack) — <https://slack-chats.kotlinlang.org/t/26808224/>
- cinterop / ObjC-bridging background — <https://kotlinlang.org/docs/native-objc-interop.html>, <https://medium.com/@math.perroud/yes-calling-swift-from-kotlin-multiplatform-is-easy-no-plugins-no-magic-just-cinterop-e4bf37caf5bf>, <https://medium.com/@shivathapaa/bridging-swift-to-kotlin-multiplatform-a-complete-guide-to-cinterop-in-multi-module-projects-af54d88f4b4f>
- KMMBridge — <https://kmmbridge.touchlab.co/docs/>, <https://kmmbridge.touchlab.co/docs/WHAT_ARE_WE_DOING/>, <https://kmmbridge.touchlab.co/docs/general/CONFIGURATION_OVERVIEW/>, <https://kmmbridge.touchlab.co/docs/artifacts/MAVEN_REPO_ARTIFACTS/>, <https://github.com/touchlab/KMMBridge>
- JetBrains official — <https://kotlinlang.org/docs/multiplatform/multiplatform-spm-import.html>, <https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html>, <https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html>, <https://kotlinlang.org/docs/native-swift-export.html>, <https://github.com/JetBrains/kotlin/blob/master/docs/swift-export/architecture.md>, <https://github.com/Kotlin/swift-export-sample>, <https://kotlinlang.org/docs/whatsnew2220.html>, YouTrack KT-84420

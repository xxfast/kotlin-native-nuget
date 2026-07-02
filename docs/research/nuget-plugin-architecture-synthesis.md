# Synthesis: what CocoaPods & SPM integrations teach the NuGet plugin

> Consolidates [cocoapods-plugin-architecture.md](cocoapods-plugin-architecture.md) and
> [spm-plugins-architecture.md](spm-plugins-architecture.md) into a decision-oriented view for
> Phase 8 ([ROADMAP](../../ROADMAP.md)) — consuming NuGet packages from Kotlin — and, secondarily,
> for maturing the publish direction. Each open decision below is a candidate ADR.

## The landscape at a glance

| | CocoaPods plugin (JetBrains) | spm4Kmp (community) | Official SwiftPM import (Alpha) | KMMBridge (publish) | **kotlin-native-nuget** |
|---|---|---|---|---|---|
| Direction | both | consume | consume | publish | both (Phase 8 adds consume) |
| API surface obtained from | ObjC headers, shipped in pod (but pod is *compiled* first) | must **compile** the package, then cinterop the `@objc` slice | Clang modules only | n/a | **ECMA-335 assembly metadata — shipped in the package, machine-readable, no compile step** |
| Resolution | synthetic Podfile + `pod install` | SPM manifest + `swift build` | SPM + synthetic package | n/a | generated `.csproj` + `dotnet restore` (or pure-JVM NuGet v3 client) |
| Registry has metadata + hosting in one | partially (podspecs + source/vendored frameworks) | no | no | no (parks zip anywhere, e.g. Maven) | **yes — a `.nupkg` on a v3 feed is manifest + payload** |
| Visibility ceiling | ObjC headers only (pure-Swift pods unsupported) | `@objc`/`public`/`NSObject`-rooted slice only | same | n/a | the **C-ABI-bridgeable slice** of the managed API (to be defined — Decision 2) |
| Toolchain prerequisite | Ruby + CocoaPods + Xcode (macOS-only) | Swift toolchain + Xcode (macOS-only) | same | none extra | `dotnet` SDK **or** none, if pure-JVM (Decision 1) |
| Host-side integration | podspec `script_phases` → calls back into Gradle | n/a | Xcode linkage package | `Package.swift` binaryTarget(url, checksum) | pre-generated `Interop.cs` in the `.nupkg` — **no consumer-side callback** (ADR-001) |

## The central insight

Every prior-art consumer pays a heavy price to *recover the API surface* of a foreign package:
CocoaPods compiles the pod with `xcodebuild` before cinterop can run; spm4Kmp compiles a bridge Swift
package because SPM ships no headers at all. **NuGet is the only ecosystem here whose packages carry a
complete, machine-readable API description (ECMA-335 metadata) inside the artifact itself.** Our
consumption pipeline therefore skips the entire compile-to-get-headers stage: resolve → read metadata →
generate bindings. Structurally we inherit CocoaPods' resolution pattern and skip its slowest,
non-cacheable step.

The second structural advantage: the Kotlin/Native library always runs **inside a .NET host process**,
so Kotlin → C# calls need no CLR hosting — an init-time function-pointer registration table
(`[ModuleInitializer]` + `[UnmanagedCallersOnly]` thunks) reuses the Phase 7 reverse-interop machinery.
spm4Kmp and CocoaPods have no equivalent shortcut.

## Proposed consumption pipeline (mirrors the CocoaPods task graph)

CocoaPods' architecture of many small, single-responsibility tasks — each wrapping one external tool
invocation, handing data to the next via a serialized manifest file — transfers directly:

```
nugetGen                    generate a synthetic/throwaway .csproj from the nuget { dependencies { } } DSL
        │                     (↔ PodGenTask's synthetic Podfile)
        ▼
nugetRestore                dotnet restore → materialized package tree + obj/project.assets.json
        │                     (↔ PodInstallSyntheticTask; project.assets.json IS our PodBuildSettingsProperties —
        │                      reuse it, don't invent a manifest format)
        ▼
nugetExtractApi             read ECMA-335 metadata from resolved assemblies → reverse-IR model
        │                     (↔ nothing in CocoaPods/SPM — the stage they need a compile for, we don't)
        ▼
nugetGenerateBindings       reverse IR → Kotlin stubs (KotlinPoet) + C#-side registration shims
        │                     (↔ DefFileTask + cinterop; keep binding options as task config,
        │                      not baked into intermediate files — the .def lesson)
        ▼
compileKotlin{Target}       stubs participate in the normal Kotlin/Native compilation
        ▼
nugetImport                 umbrella task for IDE sync (↔ podImport)
```

Key divergence from both prior arts: **the managed API is modeled once**, not per target. Only native
payloads (`runtimes/{rid}/native/`) vary per RID; the Kotlin-target ↔ RID mapping table
(`macosArm64` ↔ `osx-arm64`, `mingwX64` ↔ `win-x64`, …) applies only to packages that carry native
assets. spm4Kmp repeats its entire compile→def→cinterop pipeline per Apple target; we should not.

## Decisions the findings force (candidate ADRs)

### D1 — Metadata access & toolchain prerequisite

Two intertwined choices: how to **resolve** packages (generated `.csproj` + `dotnet restore` vs a
pure-JVM NuGet v3 client) and how to **read** the API (pure-JVM ECMA-335 reader vs a bundled `dotnet`
dump tool). CocoaPods and spm4Kmp both normalize a native-tool prerequisite (`pod`, Swift toolchain),
so requiring the .NET SDK on the Kotlin author's machine has precedent and is the pragmatic default —
`dotnet restore` also gives us `project.assets.json` and transitive resolution for free. A pure-JVM
path (both halves) would be a genuine ergonomic edge over every prior art — no plugin in this space
runs prerequisite-free — but is a bigger investment. Recommendation: **v1 requires the .NET SDK with
CocoaPods-grade tooling UX** (see "patterns to steal"), keep the pure-JVM reader as a tracked
improvement.

### D2 — The bridgeable-subset boundary (our `@objc` problem)

Every consumer hits a visibility ceiling: cinterop sees only ObjC headers; spm4Kmp sees only the
`@objc`/`NSObject`-rooted slice and makes users **hand-write bridge code** in a dedicated folder.
Our ceiling is different but real: overload sets, `ref struct`/`Span<T>`, open generics, `dynamic`,
default interface methods, and some async shapes won't cross the C ABI cleanly. The decision:

- **v1: auto-mappable subset only** (static methods, primitives, strings, void — per ROADMAP), with
  **fail-fast diagnostics naming each skipped member and why** (structural communication of the
  boundary, the spm4Kmp lesson, aligned with our fail-fast convention).
- **Escape hatch, later**: an spm4Kmp-style hand-written adapter model (user writes a small C# shim
  project the plugin also binds) for members outside the subset.

### D3 — Call mechanism & host assumption

Init-time registration table, host process is always .NET — already the Phase 8 working assumption,
now validated: it is strictly simpler than anything CocoaPods/spm4Kmp can do. The ADR should record
explicitly that **a standalone Kotlin host (no .NET runtime in-process) is out of scope for v1**, and
note `hostfxr` hosting as the future path if that scope ever changes.

### D4 — DSL shape

Mirror the `pod()` axis, which transfers cleanly, plus spm4Kmp's two good ideas:

```kotlin
nuget {
  dependencies {
    "Newtonsoft.Json" version "13.0.3"            // version | feed | local path | linkOnly axis (CocoaPods)
    "SomeLib" version "2.0" bind {
      packageName = "somelib"                      // Kotlin package for bindings (pod.packageName)
      include("SomeLib.Core")                      // opt-in namespace selection (spm4Kmp exportToKotlin)
      // alias(...)                                 // product-rename escape hatch (spm4Kmp ProductName)
    }
  }
}
```

Opt-in binding selection matters: **do not bind a package's whole transitive closure by default** —
resolve transitively (NuGet does this), bind selectively.

### D5 — Cross-runtime inheritance is explicitly deferred

Swift export — JetBrains' own idiomatic-export effort, architecturally a mirror of our
`@CName`/`Interop.cs` layer — currently allows only final `Any`-rooted classes and forbids
cross-language subclassing. That's a strong signal from a well-resourced team: subclassing across a
runtime boundary is disproportionately hard. Kotlin implementing a C# **interface** (via Phase 7
machinery) is in scope; Kotlin **subclassing** a C# class is not a v1 promise.

### D6 — Dev-loop ergonomics (publish direction, from KMMBridge)

KMMBridge's first-class local-vs-published dual flow (local `Package.swift` → locally built framework;
published → remote URL + checksum) should be mirrored: a **local-feed / `ProjectReference` dev loop**
so a C# consumer can iterate against a locally built `.nupkg` before publishing, then a published
`PackageReference`. What we can skip: KMMBridge's "park the zip in Maven and extract a URL" hack and
its temporary-CI-branch tagging dance — NuGet's registry is metadata + hosting in one.

## Patterns to steal wholesale

1. **Layered plugin, small tasks, filesystem handoff** — apply on top of KMP via
   `pluginManager.withPlugin`, register per-target work in `whenEvaluated`, one task per external-tool
   invocation with precise inputs/outputs, data passed between tasks via serialized manifest files
   (for us: `project.assets.json`). This matches our existing plugin structure.
2. **Tooling detection UX** — `which dotnet` (or `local.properties` override); on absence, an explicit
   "install .NET SDK 8+" message with instructions (CocoaPods' `which pod` pattern). Version-check the
   SDK and give targeted diagnostics for known failure classes.
3. **Self-healing retry** — CocoaPods auto-retries `pod install --repo-update` on stale specs; analog:
   retry restore with `--no-cache`/force-evaluate on transient feed failures.
4. **Umbrella IDE-sync task** — `nugetImport` ↔ `podImport`, the single entry point Gradle sync hits.
5. **Thin generated intermediates, rich task config** — the `.def` lesson: keep package names, extra
   options, search paths as task configuration, not baked into generated files.
6. **Deployment-target validation up front** — CocoaPods writes the platform floor into the synthetic
   Podfile and lets `pod install` fail early; analog: put `<TargetFramework>` in the synthetic
   `.csproj` so `dotnet restore` fails fast on packages above our supported floor (net8.0 — GOALS 5.1).

## What we deliberately do differently

- **No consumer-side build callback.** CocoaPods' podspec `script_phases` → `./gradlew syncFramework`
  is its defining publish-side trick; we deliberately pre-generate `Interop.cs` into the `.nupkg`
  (ADR-001) so consumers need no Gradle. Keep the callback pattern in the back pocket only for a
  hypothetical "build native on the consumer's RID" feature (an MSBuild `.targets` → Gradle hook).
- **No compile step to obtain the API surface** (the central insight above).
- **No per-target binding generation for managed APIs** — model once, vary native payloads per RID.
- **No dummy-artifact trick** unless a two-phase restore/build materializes (CocoaPods needs a dummy
  framework only because `pod install` runs before the Kotlin build; our restore has no such circular
  dependency in the consume direction).

## Mapping to ROADMAP Phase 8

| Phase 8 item | Informed by | Candidate ADR |
|---|---|---|
| Kotlin → managed C# call mechanism | D3 (registration table validated; standalone host out of scope) | ADR: reverse-consumption call mechanism |
| Extracting the C# API surface | D1 (dotnet-SDK-based v1, pure-JVM as tracked edge) | ADR: metadata access & toolchain |
| Gradle DSL for NuGet dependencies | D4 (pod() axis + opt-in binding + alias) | ADR: consumption DSL |
| Reverse IR | pipeline sketch (`nugetExtractApi` stage) | likely no ADR — mirrors CIR |
| Kotlin-idiomatic stubs | D2 (auto-mappable subset + fail-fast diagnostics) | ADR: bridgeable-subset boundary |
| C#-side registration shims | D3 + spm4Kmp bridge-package analogy | folded into call-mechanism ADR |
| Opaque handles / lifetime / exceptions / Task / collections | forward-direction ADR mirrors (ADR-003/019/023/010/011) | per-feature, existing research agent flow |

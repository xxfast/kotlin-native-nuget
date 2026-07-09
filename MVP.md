# MVP: v0.1.0

What has to be true before the plugin is usable by anyone who isn't this repo.

## What v0.1.0 claims

- **Kotlin → C# (forward): stable.** Everything through Phase 6. OOP constructs, generics, collections, lambdas, exceptions, coroutines and `Flow`.
- **C# → Kotlin (reverse): preview.** Resolve and bind a NuGet package, call static methods, construct objects. Labelled experimental, ceiling stated up front, no compatibility promise.
- **Distribution:** plugin marker on the Gradle Plugin Portal, `nuget-processor` on mavenCentral.

Anything not on this page is post-launch. See [ROADMAP.md](ROADMAP.md).

## P0: blocks launch

Nothing here is optional. Today the plugin only works via `includeBuild`; no consumer can apply it.

### Publishing

- [ ] Add a `LICENSE` file. There isn't one. Both the Plugin Portal and mavenCentral reject a POM without a license, and until it exists nobody can legally use the plugin.
- [ ] `com.gradle.plugin-publish` on `nuget/` → Plugin Portal, so `plugins { id("io.github.xxfast.kotlin.native.nuget") }` resolves with no `pluginManagement` block.
- [ ] `maven-publish` + Sonatype signing on `nuget-processor` → mavenCentral. It has no `group` and no `version` today; it inherits from a root build that sets neither.
- [ ] Single source of truth for the version. `NugetPlugin.kt:14` hardcodes `PLUGIN_VERSION = "0.1.0"` and `nuget/build.gradle.kts:8` independently sets `version = "0.1.0"`. `NugetPlugin.kt:217` resolves `io.github.xxfast:nuget-processor:$PLUGIN_VERSION` from that constant. Bump one and forget the other and every consumer gets an unresolvable processor. `nuget/` is an included build, so the two versions live in separate Gradle builds. Generate the constant, or assert equality in a test.
- [ ] Release workflow: tag → publish both artifacts.

### Consumer path is never exercised

- [ ] Smoke test that consumes the plugin **by coordinate**, not `includeBuild`. `NugetPlugin.kt:216` prefers `findProject(":nuget-processor")` and falls back to the maven coordinate. In this repo the project always exists, so the fallback branch, the one every real consumer takes, has never run. Publish to a local maven repo and build a fixture project against it.

### Generated code correctness

These two are one item, and CI currently proves nothing about them.

- [ ] Pin `<LangVersion>` in the generated project so a consumer's newer SDK can't reinterpret generated code under a different language version.
- [ ] CI smoke test that compiles the generated `Interop.cs` against the pinned `LangVersion` and the lowest supported TFM. Right now `SampleApp.Tests.csproj` targets `net10.0` while ADR-045 pins `net8.0` for restore and the README promises .NET 8+. Nothing in CI compiles the generated bindings as a `net8.0` consumer would. Escaping, reserved-word and invalid-construct regressions surface at consumer build time.

### Documentation

- [ ] README: state the reverse-direction ceiling explicitly (see below), add a version compatibility table (Kotlin `2.4.0`, KSP `2.3.9`, .NET `8.0`+), and correct the .NET-SDK prerequisite wording.
- [ ] Mark the reverse-direction DSL experimental. An opt-in annotation is nicer, a documented ceiling is the floor.

## P1: strongly recommended

- [ ] **Make the reverse preview coherent: instance methods and instance properties** ([ROADMAP.md](ROADMAP.md) Phase 9). As it stands you can construct a C# object (ADR-052) and then call nothing on it. Instance methods are unchecked. A constructor with no callable members is a hollow feature and reads as broken rather than as preview. This is the one reverse item worth pulling forward, and it's the largest single piece of work on this page. If it doesn't fit, cut object construction from the preview surface too and ship reverse as *static methods only*, which is at least internally consistent.
- [ ] `dotnet` detection on PATH with explicit install guidance (Phase 8, currently deferred). First-run failure mode for a reverse-direction user is otherwise an opaque subprocess error.

## Cut from MVP

Deliberately not blocking launch. Each moves to [ROADMAP.md](ROADMAP.md).

| Item | Why it's cut |
|---|---|
| KDoc → C# XML doc comments | Ergonomics. Generated bindings work without it. |
| Writerside documentation | A complete README covers v0.1.0. Writerside is worth it once the API stops moving. |
| Richer sample app | `sample-library` already exercises generics, collections and async. The gap is documentation, not samples. |
| Local-feed dev loop for C# consumers (synthesis D6) | Developer ergonomics for plugin *users*. The P0 by-coordinate smoke test covers this repo's own need to verify the published path. |
| GitHub Packages | Superseded. It requires an auth token even for public reads, so it's a worse channel than the two we're shipping. |
| SharedFlow / StateFlow, remaining Flow edge cases | Phase 6 tail. |
| Phase 7 remainder (interface returns, C#-implemented Kotlin interfaces) | Forward direction is stable without them. |
| Phases 9–13 beyond the P1 item | The reverse direction ships as preview precisely so these don't block. |

## Launch order

1. `LICENSE`, version single-sourcing, publishing config.
2. By-coordinate smoke test. Do not skip this before the first publish. It is the only thing standing between you and a broken `0.1.0` on the Plugin Portal.
3. `LangVersion` pin + CI compile check.
4. README.
5. P1 if it fits, otherwise narrow the preview claim to match what works.
6. Tag, publish, announce.

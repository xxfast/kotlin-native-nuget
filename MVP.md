# MVP: v0.1.0

What has to be true before the plugin is usable by anyone who isn't this repo.

## What v0.1.0 does

At `0.x` the whole plugin is unstable and the README already carries the experimental badge, so there is no per-direction stability label to hand out. What a user needs is not a compatibility promise but an honest capability ceiling: which constructs cross the bridge today, and which silently don't.

- **Kotlin → C# (forward).** Everything through Phase 6. OOP constructs, generics, collections, lambdas, exceptions, coroutines and `Flow`.
- **C# → Kotlin (reverse).** Resolve and bind a NuGet package, call static methods, construct objects. The ceiling is the point: a bound type's instance members are unreachable until Phase 9 line 151 lands, so the README must state the subset rather than imply the direction works.
- **Distribution:** plugin marker on the Gradle Plugin Portal, `nuget-processor` on mavenCentral.

Anything not on this page is post-launch. See [ROADMAP.md](ROADMAP.md).

## P0: blocks launch

Nothing here is optional. Today the plugin only works via `includeBuild`; no consumer can apply it.

### Publishing

- [ ] Add a `LICENSE` file. There isn't one. Both the Plugin Portal and mavenCentral reject a POM without a license, and until it exists nobody can legally use the plugin.
- [ ] `com.gradle.plugin-publish` on `nuget-plugin/` → Plugin Portal, so `plugins { id("io.github.xxfast.kotlin.native.nuget") }` resolves with no `pluginManagement` block.
- [ ] `maven-publish` + Sonatype signing on `nuget-processor` → mavenCentral. It has no `group` and no `version` today; it inherits from a root build that sets neither.
- [ ] Single source of truth for the version. `NugetPlugin.kt:14` hardcodes `PLUGIN_VERSION = "0.1.0"` and `nuget-plugin/build.gradle.kts:8` independently sets `version = "0.1.0"`. `NugetPlugin.kt:217` resolves `io.github.xxfast:nuget-processor:$PLUGIN_VERSION` from that constant. Bump one and forget the other and every consumer gets an unresolvable processor. `nuget-plugin/` is an included build, so the two versions live in separate Gradle builds. Generate the constant, or assert equality in a test.
- [ ] Release workflow: tag → publish both artifacts.

### Consumer path is never exercised

- [ ] Smoke test that consumes the plugin **by coordinate**, not `includeBuild`. `NugetPlugin.kt:216` prefers `findProject(":nuget-processor")` and falls back to the maven coordinate. In this repo the project always exists, so the fallback branch, the one every real consumer takes, has never run. Publish to a local maven repo and build a fixture project against it.

### Generated code correctness

These two are one item, and CI currently proves nothing about them.

- [ ] Pin `<LangVersion>` in the generated project so a consumer's newer SDK can't reinterpret generated code under a different language version.
- [ ] Emit `#nullable enable` in the generated C# registration shims. They already contain nullable reference annotations (`Template? result = ...`), which raise `CS8669` in a consumer build because the file has no `#nullable` directive: *"Auto-generated code requires an explicit `#nullable` directive in source."* Confirmed live in `sample-library/build/nuget-interop/csharp/TemplateRegistration.cs`. Predates the Phase 9 instance-member work, which merely doubled the number of occurrences. Every consumer sees these warnings and they come from our generated code, not theirs.
- [ ] CI smoke test that compiles the generated `Interop.cs` against the pinned `LangVersion` and the lowest supported TFM. Right now `SampleApp.Tests.csproj` targets `net10.0` while ADR-045 pins `net8.0` for restore and the README promises .NET 8+. Nothing in CI compiles the generated bindings as a `net8.0` consumer would. Escaping, reserved-word and invalid-construct regressions surface at consumer build time.

### Documentation

- [ ] README: state the reverse-direction capability ceiling explicitly (which constructs bind, which are skipped), add a version compatibility table (Kotlin `2.4.0`, KSP `2.3.9`, .NET `8.0`+), and correct the .NET-SDK prerequisite wording.

No `@ExperimentalNugetApi` opt-in annotation. It would be ceremony: the whole plugin is `0.x` and badged experimental, so an opt-in gate on one part of it signals a stability gradient that doesn't exist. Revisit at `1.0.0`, when the rest of the surface has something to be stable *relative to*.

## P1: strongly recommended

- [x] **Instance methods and instance properties** ([ROADMAP.md](ROADMAP.md) Phase 9 line 151). Landed as a confirmed mirror of ADR-051, no new ADR. A bound C# object's instance methods and properties are now callable from Kotlin, so constructing one is no longer a dead end. Verified end to end through the real `.nupkg` round trip.
- [ ] `dotnet` detection on PATH with explicit install guidance. First-run failure mode for a reverse-direction user is otherwise an opaque subprocess error.

## Cut from MVP

Deliberately not blocking launch. Each moves to [ROADMAP.md](ROADMAP.md).

| Item                                                                    | Why it's cut                                                                                                                       |
|-------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| KDoc → C# XML doc comments                                              | Ergonomics. Generated bindings work without it.                                                                                    |
| Writerside documentation                                                | A complete README covers v0.1.0. Writerside is worth it once the API stops moving.                                                 |
| Richer sample app                                                       | `sample-library` already exercises generics, collections and async. The gap is documentation, not samples.                         |
| Local-feed dev loop for C# consumers (synthesis D6)                     | Developer ergonomics for plugin *users*. The P0 by-coordinate smoke test covers this repo's own need to verify the published path. |
| GitHub Packages                                                         | Superseded. It requires an auth token even for public reads, so it's a worse channel than the two we're shipping.                  |
| SharedFlow / StateFlow, remaining Flow edge cases                       | Phase 6 tail.                                                                                                                      |
| Phase 7 remainder (interface returns, C#-implemented Kotlin interfaces) | Forward direction is complete and useful without them.                                                                             |
| Phases 9–13 beyond the P1 item                                          | The README states the reverse ceiling, so these are absent capabilities rather than broken promises.                               |

## Launch order

1. `LICENSE`, version single-sourcing, publishing config.
2. By-coordinate smoke test. Do not skip this before the first publish. It is the only thing standing between you and a broken `0.1.0` on the Plugin Portal.
3. `LangVersion` pin + CI compile check.
4. README.
5. ~~P1~~ done: instance methods and properties landed, so the README's reverse-direction section can describe a bound object you can actually call. Remaining P1 is `dotnet` detection.
6. Tag, publish, announce.

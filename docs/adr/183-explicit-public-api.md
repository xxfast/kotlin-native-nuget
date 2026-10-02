# ADR-183: Explicit API mode in every published module, with the Gradle DSL, task types and the KSP provider as the public surface

## Status

Accepted

## Context

ROADMAP 0.9.0 item: the published modules leak implementation API. `nuget-plugin` exposes the whole
reverse IR (`rir` package), the generators (`generateKotlinStubs`, `generateCSharpShims`,
`generateCsproj`, `GeneratedFile`), `NugetPusher` and its request and result types;
`nuget-processor` exposes its `cir` model. 1.0 semver, and the binary-compatibility-validator
baseline that follows, would freeze all of it. Research memo:
[internalise-plugin-api.md](../research/roadmap/internalise-plugin-api.md).

Measured (**verified**, spike: `explicitApi()` added to each build script, compiled, reverted):
`nuget-plugin` 257 errors over 22 of 24 main files; `nuget-processor` 81 errors over 8 files;
`nuget-runtime` 0 errors (ADR-127 already made it explicit); `nuget-annotations` already calls
`explicitApi()` (`nuget-annotations/build.gradle.kts:10`, ADR-179). At implementation, on top of
ADR-180/181/182/184: `nuget-plugin` 272 errors over 22 files, `nuget-processor` 82 (80 visibility,
2 missing return types in `Reserved.kt`), `nuget-runtime` 0.

Two constraints shape what may become `internal`:

- **Gradle-managed members must stay public (verified, scratch spike, Gradle 9.1.0, Kotlin
  2.4.10).** A Kotlin-`internal` plugin class and task class are decorated and run fine
  (`class=p.HelloTask_Decorated`), but an `@get:Input internal abstract val secret` is tracked as
  input `secret$plug` (`INPUTS=[msg, secret$plug]`): the module-mangled JVM name becomes the input
  key. Silent, not a build failure.
- **Task types are configured by class (verified by reading).** This repo's own fixtures do
  `tasks.withType<NugetCompileInteropTask>()`, `tasks.named("packNuget", PackNugetTask::class.java)`,
  `tasks.named("nugetGenerateRestoreProject", NugetGenerateRestoreProjectTask::class.java)` and
  `extensions.findByType(NugetExtension::class.java)` (`test-library/build.gradle.kts:1-4`, `:177`,
  `:216`, `:225`, `:236`; `test-companion/build.gradle.kts:1-3`, `:36`, `:44`, `:48`; task names as
  renamed by ADR-182).

## Alternatives Considered

### 1. `explicitApi()` everywhere; public = plugin class, DSL types, every registered task type, KSP provider (chosen)

Pros: compiler-enforced; matches KGP, which keeps its task types public; every existing
build-script idiom (`withType`, typed `named`, `findByType`) keeps working. Cons: task types and
their Gradle properties enter the semver surface, so renaming a task property becomes a breaking
change.

### 2. Same, but internalise task classes too

Smallest surface. Works mechanically (internal classes decorate fine, verified), but breaks
`withType<T>`/typed lookups in this repo's fixtures and in consumers, leaving only name-based
`tasks.named("packNuget")` with untyped properties. Rejected.

### 3. Internalise the ROADMAP-named declarations only, no explicit API mode

Fixes today's list, misses `msbuildVersionPropertyName` and the processor `cir` model, and nothing
prevents the next leak before the BCV baseline. Rejected. `explicitApiWarning()` rejected for the
same reason.

## Decision

1. `kotlin { explicitApi() }` in `nuget-plugin`, `nuget-processor`, `nuget-runtime` (already in
   `nuget-annotations`).
2. Public surface:
   - `nuget-plugin`: `NugetPlugin`; `NugetExtension` and every DSL type reachable from its public
     members (as reshaped by ADR-180); every `DefaultTask` subclass the plugin registers, with its
     Gradle-annotated properties, `@get:Inject` services and `@TaskAction` method.
   - `nuget-processor`: `NugetProcessorProvider` only (named in
     `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`).
   - `nuget-runtime`: unchanged; ADR-127's `@NugetRuntimeApi` declarations, which generated
     consumer code calls.
3. Everything else `internal`: the `rir` package (including ADR-182's `RirDiagnosticSeverity`,
   `REVERSE_IR_SCHEMA_VERSION` and `requireCurrentSchema`), the generator entry points and `GeneratedFile`,
   `NugetPusher`/`NugetPushRequest`/`NugetPushResult`, `msbuildVersionPropertyName`, `NugetTaskNames` (already internal), and the
   processor's `NugetProcessor`, `cir` model, `Reserved.kt` and `forward` helpers.
4. A Gradle-annotated property or `@TaskAction` is never `internal` (the mangled-input finding
   above). A public task's non-Gradle helpers are `private` or `internal`.
5. New code follows the same rule; the compiler enforces it from here on.

Same-module tests keep access: plugin tests already call `internal` functions
(`NugetToolingTest`, `NugetPackageIdentityTest`; verified by reading). No other module compiles
against the internalised names (verified by grep). Explicit API mode skipping test source sets is
verified at implementation: both test suites compiled and passed unchanged with it on.

## Consequences

- Breaking for any consumer that imported `rir`, the generators or `NugetPusher` (none intended;
  0.9.0 is the breaking release).
- Implemented last on the 0.9.0 stack, as a rule-based pass (memo, Recommendation), so it does not
  conflict with ADR-180/181/182 file rewrites.
- The ROADMAP line's "no build script calls `explicitApi()`" is stale (nuget-annotations does);
  correct it when closing.
- Deferred: the BCV baseline (separate 1.0.0 item); `protected` injected services; trimming
  read-side DSL accessors (follows ADR-180).

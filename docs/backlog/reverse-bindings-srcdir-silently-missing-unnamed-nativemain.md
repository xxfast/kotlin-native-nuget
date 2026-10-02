# The reverse bindings srcDir silently lands in no source set when a build script never names `nativeMain`

A consumer whose build script binds a C# dependency (`nuget { dependencies { bind { } } }`) but never
itself writes `kotlin.sourceSets.nativeMain { ... }` (for example `macosArm64 { binaries { sharedLib {} } }`
plus one `bind {}`, relying entirely on the default hierarchy template) gets no reverse Kotlin sources
compiled into the library at all, and no build-time error. The generated stubs
(`build/nuget-interop/kotlin/nativeMain/...`, the Kotlin classes and registration objects a bound C#
package produces) exist on disk but are attached to nothing. The first symptom a consumer sees is at
C# process startup, when `[ModuleInitializer]` calls the expected `nuget_*_register` export and it is
missing (`EntryPointNotFoundException`), with nothing in the Gradle log pointing back at the cause.

**Verified cause.** `NugetPlugin.kt` calls `findByName("nativeMain")` before KGP hierarchy creation,
so the nullable lookup silently omits the generated source directory. Explicitly naming
`nativeMain` in the fixture creates the source set early and masks the issue. Companion compiler
output also missed `expect`, registry and `Template` imports; cover both failures with a reverse
pipeline fixture that leaves `nativeMain` unnamed and compiles generated output.

**Why it went unnoticed.** `test-library/build.gradle.kts:110` names `nativeMain` explicitly
(`nativeMain.dependencies { ... }`), which is enough to make the source set exist with the plugin's
srcDir intact by the time the plugin's own `afterEvaluate` runs, so the project's own fixture never
exercises the failure path. Nothing else in the repo binds a C# dependency from a build script that
omits a `nativeMain` block.

**Discovered alongside** [ADR-130](../adr/130-reverse-error-envelope-on-runtime.md), while spiking
whether the reverse bindings' srcDir could move to a per-target source set to let them reference the
runtime klib's `NugetRuntimeApi`-gated declarations directly (Alternative 1, rejected for an unrelated
reason: a consumer's own `nativeMain` code needs to see the reverse bindings' consumer-facing types,
which per-target placement would break the other way). The srcDir timing bug this ADR's spike
surfaced is independent of that decision and was not fixed by it.

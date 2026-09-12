# The reverse bindings srcDir silently lands in no source set when a build script never names `nativeMain`

A consumer whose build script binds a C# dependency (`nuget { dependencies { bind { } } }`) but never
itself writes `kotlin.sourceSets.nativeMain { ... }` (for example `macosArm64 { binaries { sharedLib {} } }`
plus one `bind {}`, relying entirely on the default hierarchy template) gets no reverse Kotlin sources
compiled into the library at all, and no build-time error. The generated stubs
(`build/nuget-interop/kotlin/nativeMain/...`, the Kotlin classes and registration objects a bound C#
package produces) exist on disk but are attached to nothing. The first symptom a consumer sees is at
C# process startup, when `[ModuleInitializer]` calls the expected `nuget_*_register` export and it is
missing (`EntryPointNotFoundException`), with nothing in the Gradle log pointing back at the cause.

**Root cause.** `NugetPlugin.kt:192` adds the reverse bindings srcDir with
`kotlin.sourceSets.findByName("nativeMain")` inside the plugin's own `project.afterEvaluate` block
(registered at `NugetPlugin.kt:42`). **Verified** by a `ProjectBuilder` probe: in a project whose
script never references `nativeMain` by name, `evaluate()` does eventually create a `nativeMain`
source set (Kotlin's default hierarchy template wires it), but its `kotlin.srcDirs` never gain
`build/nuget-interop/kotlin/nativeMain`. The reverse bindings are generated but attached to no
compilation.

**Not isolated: the mechanism.** Two candidate explanations fit the same observation, and they have
different fixes:

- `findByName("nativeMain")` returns `null` at the point the plugin's `afterEvaluate` callback runs
  (registered early, at `NugetPlugin.kt:42`), before the default hierarchy template has created the
  source set yet, so the `?.kotlin?.srcDir(...)` call silently no-ops.
- `findByName("nativeMain")` returns a real source set, but Kotlin's default hierarchy template later
  re-creates or replaces its `srcDirs`, dropping the plugin's addition.

The first points at a run-order fix (`sourceSets.configureEach`/`whenObjectAdded` keyed on the name,
or `maybeCreate`); the second points at moving the srcDir wiring later, or onto a target-specific
source set the template does not touch. The probe that established the symptom did not go far enough
to distinguish them.

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

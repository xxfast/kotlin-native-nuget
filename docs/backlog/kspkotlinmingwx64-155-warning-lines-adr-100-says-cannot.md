# `kspKotlinMingwX64` prints about 155 `w: [ksp] ... [nuget:*]` warning lines at default verbosity, which ADR-100 says cannot happen

> Discovered by an [ADR-162](../adr/162-per-declaration-error-containment.md) research spike
> (2026-09-21, Windows 11, Gradle 9.1.0, `./gradlew :test-library:kspKotlinMingwX64 --console=plain`),
> not chased there (out of scope, budget).

A passing build against this repository's own `test-library` fixture on Windows shows about 155
`w: [ksp] ... [nuget:SKIPPED_*/WARNING_*]` lines on the console at default verbosity. ADR-100's own
S1/S2 finding (measured on macOS, `kspKotlinMacosArm64`) and `forward/ForwardDiagnostic.kt`'s source
comment (corrected by ADR-162 to describe `ERROR_*` visibility, not this) both say a KSP worker's
`KSPLogger` output never reaches the Gradle console at all. One known difference: the ADR-100 spike
ran on macOS, this one on Windows; `scripts/verify.sh` passes no verbosity flag that would explain a
platform difference either way.

Unresolved question: is the ADR-100 console re-emit (`NugetReportDiagnosticsTask`, which reads
`NugetDiagnostics.json` and re-emits through Gradle's own `logger.warn`) now printing every warning
*twice* in a normal `packNuget` — once from the KSP worker's own `w: [ksp] ...` line (if that channel
is not actually silent on Windows) and once from the task's re-emit? Or is this specific to a direct
`kspKotlinMingwX64` invocation, which `packNuget` does not itself run except as an upstream
dependency of `nugetReportDiagnostics`? Verified by spike that the lines appear; not verified whether
they duplicate, whether the platform matters, or whether a `packNuget` run (rather than the bare KSP
task) is affected at all.

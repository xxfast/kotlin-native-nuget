# The reverse C# shims' `[ModuleInitializer]` has no CA2255 suppression

A bound package's reverse registration shim compiles today only because every reverse consumer in
this repo is a test/exe project. A library consumer that turns on `TreatWarningsAsErrors` or raises
its `AnalysisLevel`, the same posture `GeneratedBindingsCheck` (net8.0) already runs under on the
forward side, gets `error CA2255: The 'ModuleInitializer' attribute should not be used in libraries`
the moment it references a bound package, because a `[ModuleInitializer]`-carrying type compiles
into *their* assembly, not this repo's.

**Root cause.** `NugetGenerateShimsTask.kt` emits three unsuppressed `[ModuleInitializer]` sites:

- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateShimsTask.kt:957`
  (`{Tag}Registration.Register()`, the static-class registration shim)
- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateShimsTask.kt:1172`
  (`{cls.name}Registration.Initialize()`, the bound-class registration shim)
- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateShimsTask.kt:1337`
  (`{iface.name}Registration.Initialize()`, the bound-interface registration shim)

None wraps the attribute in `#pragma warning disable CA2255` / `restore`.

**Why it went unnoticed.** Nothing in this repo compiles a bound package's generated shims under
`TreatWarningsAsErrors` or a raised `AnalysisLevel`. `IntegrationTests` and `LeakTests` are exe/test
projects, and CA2255 does not fire in an application assembly, only in one that ships as a library
for someone else to reference. `GeneratedBindingsCheck` runs that stricter posture, but only over the
forward `Interop.cs`.

**Discovered alongside** [ADR-129](../adr/129-nuget-runtime-version-export.md): the forward side's
new `NugetRuntime` class hit exactly this failure in `GeneratedBindingsCheck` and was fixed by
wrapping its own `[ModuleInitializer]` in the same pragma pair. The reverse shims are a mirror of
that class (same attribute, same "compiles into the consumer's assembly" placement) and carry the
identical, still-unfixed defect.

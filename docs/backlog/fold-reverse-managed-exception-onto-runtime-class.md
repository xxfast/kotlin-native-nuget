# Fold the reverse template's internal `NugetManagedException` onto the runtime class

> Discovered while landing [ADR-161](../adr/161-csharp-callback-exception-into-kotlin.md).

The reverse pipeline emits its own `internal class NugetManagedException` into the consumer's
`INTERNAL_PKG` (`NugetGenerateBindingsTask.kt`, only present once the reverse pipeline runs). ADR-161
ships a second, public `NugetManagedException` in `nuget-runtime`, nameable from `nativeMain`. The
two currently coexist as distinct types sharing one simple name. The recommended fold is the ADR-130
expect/actual seam: an `internal expect class NugetManagedException(...)` in the reverse template's
`nativeMain` file, `internal actual typealias NugetManagedException = <runtime package>.NugetManagedException`
in each per-target file the plugin already emits. Not spiked in this pass (the research memo's spike
(c) was never run): whether the Beta expect/actual-classes warning trips a consumer's
`allWarningsAsErrors`, and whether `compileNativeMainKotlinMetadata` accepts the seam at all, are
open. Fallback if it fails: leave the two types separate.

Research finding (2026-10-02): inside the consuming module the generated class is already catchable, and a fold cannot make the runtime class nameable from `nativeMain` (ADR-130's spike: a declaration reachable only through a per-target `api` is unresolved there), so a `nativeMain` author would still catch the `internal` expect name; the fold would also change `e.message` at every reverse catch site, because the runtime class prefixes the managed type. Not cheap, so it is not a 0.9.0 item.

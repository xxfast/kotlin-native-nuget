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

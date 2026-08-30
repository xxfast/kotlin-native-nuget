# The reverse interop output directory does not clean orphaned files from a prior run

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**The reverse interop output directory does not clean orphaned files from a prior run** (`NugetGenerateBindingsTask`/`NugetGenerateShimsTask`, `build/nuget-interop/`). The tasks write the current run's generated files but are reported not to clear the directory first, so a `.kt`/`.cs` file generated for a type later renamed or removed from a bound namespace can persist alongside the fresh output. Reported by `csharp-dev` while building the [ADR-056](docs/adr/056-csharp-structs-in-kotlin.md) struct fixture; **not independently verified here**. Check whether the tasks declare their output directory the standard Gradle way, which gives stale-output cleanup for free, or write directly and bypass it, before deciding this needs code and not just a report

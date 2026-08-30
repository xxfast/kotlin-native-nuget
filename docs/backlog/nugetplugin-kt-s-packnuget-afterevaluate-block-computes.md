# `NugetPlugin.kt`'s `packNuget` `afterEvaluate` block computes a local `baseName` (`NugetPlugin.kt:314`, assigned at `NugetPlugin.kt:351-353` from each…

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**`NugetPlugin.kt`'s `packNuget` `afterEvaluate` block computes a local `baseName` (`NugetPlugin.kt:314`, assigned at `NugetPlugin.kt:351-353` from each linked target's `SharedLibrary.baseName`) that is never read afterwards.** Verified by grep: the only other `baseName` binding in the file, a few lines above at `NugetPlugin.kt:245-248`, is what actually feeds `nuget.libraryName` for KSP; this one is dead. Found while reading `NugetPlugin.kt` for the ADR-093 multi-RID wiring change, not caused by it. Harmless today (unused `val`, no behavioural effect), but worth deleting so a future reader does not assume it does something.

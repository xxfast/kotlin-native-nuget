# Nothing measures the size of the built shared library, and nothing tunes it

> Added 2026-09-09 as part of the Performance & Resource Hygiene section.

**The test library's `releaseShared` binary is 6.3M per target (`libtest.dylib`, `test.dll`), and that number is not recorded, tracked, or gated anywhere.** Both `sharedLib {}` blocks in `test-library/build.gradle.kts` set only `baseName`; no `binaryOption`, no `freeCompilerArgs`, no linker flags, so what ships in the `runtimes/{rid}/native/` folder of every `.nupkg` is stock Kotlin/Native output. A consumer pulls one copy per RID they target.

Two halves:
1. **Measure first.** A CI step after `:test-library:packNuget` that records the size of each `runtimes/*/native/*` asset and the `.nupkg` itself, prints them in the job summary, and fails on a regression past a committed baseline (a `build/size-baseline.txt` checked in, updated deliberately). Cheap, no ADR, and it gives the second half a number to beat. The source shim's volume (generated `.cs` line count per fixture class) belongs in the same table, since the C# side of "bundle size" is compile time in the consumer, not bytes on disk.
2. **Then tune, with an ADR.** Which Kotlin/Native options are safe for a P/Invoked shared library is not obvious and needs spiking, not doc-reading: `-Xbinary=stripDebugInfoFromNativeLibs`, symbol visibility (every `@CName` export must survive), `-opt` vs the default, `-Xbinary=gc=...` choice, whether dead-code elimination removes anything given the export surface pins the whole reachable graph ([ADR-066](../adr/066-forward-export-reachability-closure.md)). Each option gets a before/after row in the table from half 1 and a `scripts/verify.sh` run, since a stripped export that P/Invoke can no longer resolve fails as `EntryPointNotFoundException` at first call, not at build.

Related: the opt-in compiled-assembly packaging mode under Future Improvements changes what "bundle size" means on the C# side (a prebuilt `.dll` instead of source) and should reuse the same measurement step.

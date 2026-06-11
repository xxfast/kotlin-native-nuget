# ADR-001: Move C# binding generation from Gradle to consumer build

## Status

Accepted

## Context

The initial implementation ran `ClangSharpPInvokeGenerator` from a Gradle task (`GenerateBindingsTask`) using `ProcessBuilder`. This required:

- The .NET SDK installed on the Gradle build machine
- `ClangSharpPInvokeGenerator` installed as a global dotnet tool
- `DYLD_LIBRARY_PATH` set to point at `libclang` and `libClangSharp` native libraries (which macOS SIP strips from child processes)
- Manual configuration of tool paths and native lib paths in `build.gradle.kts`

This was fragile — the environment setup was complex, platform-specific, and couldn't be reproduced reliably across machines.

## Decision

Move ClangSharp invocation to a `.targets` file shipped inside the NuGet package. The Gradle plugin only compiles, links, and packages — it no longer runs any .NET tooling.

The NuGet package now contains:
- `runtimes/{rid}/native/` — native shared libraries
- `content/` — the C header file + `NativeTypeName.cs` attribute stub
- `build/{PackageId}.targets` — MSBuild target that auto-runs ClangSharp at consumer build time
- `build/run-clangsharp.sh` — wrapper script to set `DYLD_LIBRARY_PATH` (works around macOS SIP)

## Consequences

**Positive:**
- Gradle side has zero .NET dependencies — only needs Kotlin/Native toolchain
- Consumer machines already have the .NET SDK (they're building C# projects)
- ClangSharp's native library resolution is handled via NuGet package dependencies
- Binding generation is incremental — only re-runs when the header changes
- Aligns with how other .NET ecosystem tools work (gRPC, CsWin32)

**Negative:**
- Consumer still needs `ClangSharpPInvokeGenerator` installed as a global tool (for now)
- macOS requires the wrapper script due to SIP stripping `DYLD_*` vars
- This approach will be replaced entirely in Phase 2 when we switch to a custom Source Generator reading Kotlin metadata

## Alternatives Considered

1. **Keep ProcessBuilder in Gradle** — rejected due to fragile cross-platform env setup
2. **Ship ClangSharp binary inside the NuGet package** (like gRPC ships protoc) — viable but unnecessary since we plan to replace ClangSharp in Phase 2
3. **Generate bindings offline and commit them** — rejected because it breaks the "plug and play" goal; consumers shouldn't need to regenerate manually

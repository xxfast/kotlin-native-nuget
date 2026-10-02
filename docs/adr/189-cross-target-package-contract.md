# ADR-189: One forward contract across every packaged native target

## Status

Accepted

## Context

The package contains one generated C# binding surface and multiple native implementations. A
Kotlin author can declare different exported members in target source sets, including additional
members on `actual` declarations. A successful per-target build does not prove the selected C#
bindings work with every packaged native implementation.

**Verified in the implementation:** the plugin collects locally enabled native libraries and
their matching KSP output directories, and makes KSP run for every locally packaged target. The
pack task selects its binding source from the same producers it checks.

The processor writes a `ForwardAbi.json` manifest for each target. It contains the canonical
forward ABI signatures, sorted by native symbol. This complements the generated `Interop.cs`,
which captures managed differences that erase to the same native signature.

Prebuilt runtimes retain both files beside `native/`, so a packing host can validate the original
producer's contract without regenerating it from the packing host's sources.

**Inferred from official documentation:** Kotlin merges expect/actual declarations separately for
each platform, and .NET cross-platform targeting alone does not guarantee runtime portability.
These are context, not new load-bearing toolchain assumptions. See
[Kotlin expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
and [.NET cross-platform targeting](https://learn.microsoft.com/en-us/dotnet/standard/library-guidance/cross-platform-targeting).
Other export backends do not settle this packaging invariant; no new language mapping is proposed.

## Alternatives Considered

### Chosen: native ABI and generated C# source with producer sidecars

The shipped check compares the ABI signatures and tokenized generated C# source for every local and
prebuilt RID. Producer files are staged as `runtimes/<rid>/ForwardAbi.json` and
`runtimes/<rid>/Interop.cs` beside `native/`. The check runs before package output is written.
Comparison ignores comments and whitespace outside literals, while preserving declaration order,
literal contents, identifiers, operators, and preprocessor directive lines. It is conservative:
generated implementation changes or reordered declarations may fail even when public APIs are
semantically equivalent. Unsupported syntax fails rather than being normalized lossily.

### ABI manifests only (rejected)

Smallest change, but enum order/value and managed-only changes remain invisible. This does not
satisfy the roadmap's same-C#-API requirement.

### Compile every binding and compare reflection metadata (rejected)

Offers a semantic managed API comparison, but adds per-target builds and a required .NET SDK to a
guard that should work wherever packaging works. Metadata alone still omits native binding body
semantics, such as which native export a generated method calls.

### Compare locals and warn for unverified prebuilt libraries (rejected)

Leaves the same runtime failure possible in a supported packaging path. Missing contract inputs
must fail with migration guidance.

## Decision

### Producer outputs

KSP writes schema-1 `ForwardAbi.json` beside `Interop.cs`, with canonical signatures sorted by
native symbol. Missing, malformed, or unsupported manifests fail packing, including for a single
packaged target.

Each packaged runtime carries the original producing target's contract files outside `native/`:

```text
runtimes/osx-arm64/ForwardAbi.json
runtimes/osx-arm64/Interop.cs
runtimes/osx-arm64/native/libkn_....dylib
```

`packNuget` stages these for local libraries and preserves them when merging prebuilt inputs. Upload
the complete staged `runtimes/` tree between hosts. Binary-only prebuilt artifacts must be rebuilt
with the updated plugin by their original producer. Missing or invalid sidecars fail with the RID
and migration guidance. Unknown RIDs remain permitted under ADR-093 when their contracts validate.

### Pack invariant

Select the first enabled local packaged RID in sorted order as the binding baseline. If there are no
local producers, select the first prebuilt RID in sorted order. That producer's `Interop.cs` is the
source-shipped binding input. Disabled local targets do not participate. Reverse shims remain
shared package inputs.

Compare **every** local and prebuilt producer against that baseline before staging/writing a
package. Equality requires:

1. Identical canonical ABI signatures, including symbols, returns and ordered wire slots.
2. Identical generated C# token streams after removing comments and insignificant whitespace.

The C# comparison retains literal contents, identifiers, operators, declaration order and
preprocessor directive lines. Comments and whitespace outside literals are ignored. Unsupported
syntax, including raw interpolated strings and unsupported nested interpolation forms, fails
clearly rather than normalizing lossily. This compares generated source contracts, not general C#
semantic equivalence. Source-file paths are not comparison inputs.

ABI differences report missing/extra symbols and changed signatures. Managed differences report
the first differing token region, both RIDs and their sidecar paths. Both failure modes tell the
author to align exported declarations (or package the incompatible targets separately). A hash
alone is not an actionable diagnostic.

Task inputs track all compared files, and dependencies require KSP for every locally packaged
target. Prebuilt targets use their original sidecars and do not require local KSP/link tasks.
Missing contracts are errors.

### Consumer behavior

No new DSL or generated C# API is required:

```kotlin
nuget {
  publish {
    packageId = "Example.Library"
    // Optional: complete runtimes tree produced by another host's packNuget.
    prebuiltRuntimes = file("build/prebuilt-runtimes")
  }
}
```

Instead of a package whose selected bindings call a missing native symbol on another platform,
`packNuget` fails with both producers and the differing symbol/signature or managed declaration.
Even an API-only enum value change with an unchanged native ABI must fail.

## Consequences

- Binary-only `prebuiltRuntimes` inputs require migration. This closes an unsafe packaging path
  rather than preserving an opt-out. Contract files live outside native asset directories.
- `Interop.cs` is duplicated per RID in package tooling sidecars as well as once in contentFiles.
  This deliberate footprint permits straightforward artifact transfer and prebuilt-only packing.
- Declaration order and generated implementation differences may conservatively reject equivalent
  public APIs. Aligning generation is the initial policy; semantic equivalence is deferred.
- No changes to native calling signatures, consumer lifetime rules or runtime overhead. No new
  live-handle test row is needed: the feature creates no bridge route or handle.
- The invariant compares generated source contracts; it does not prove final machine-code ABI
  lowering or detect a malicious/manually substituted binary paired with another build's sidecars.
  Native execution and honest producer artifact provenance remain the existing integration boundary.
- ABI checks retain ADR-055's admitted-export scope. Extra native symbols that no generated C#
  imports need not be identical across platforms.

## Verification

Verified in the feature run (`scripts/verify.sh --plugin`): 2,954 IntegrationTests, 151 LeakTests,
9 MultiPackageTests, 2 SharedExceptionTests, 3 ContractTests, and all six NativeAOT shapes passed.
The processor manifest output and plugin package comparison were exercised by real target output
and package-path tests. No new C# API or bridge route was added, so no leak-test row is needed.

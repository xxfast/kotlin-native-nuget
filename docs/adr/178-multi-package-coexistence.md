# ADR-178: Two generated packages in one .NET project: internal types per package, shared public types in a compiled contract assembly

## Status

Proposed

## Context

A generated package ships its C# as source (`contentFiles`, `buildAction="Compile"`,
[ADR-050](050-end-to-end-packaging-integration.md), `PackNugetTask.kt:228-230`), so it compiles into
the consumer's own assembly. Three families of generated text are the same in every package and
land where a second package collides with them. Research memo:
`docs/research/roadmap/multi-package-coexistence.md`.

Verified by reading:

- `INugetHandle` and `NugetHandleTag` are emitted in the global namespace (`cir/CirRenderer.kt:20,32`,
  [ADR-094](094-reflection-free-generic-dispatch.md)).
- The reverse runtime shims (`NugetRuntimeRegistration`, `NugetTrace` and eight more internal types)
  are emitted in a hardcoded namespace, `IoGithubXxfast.KotlinNativeNuget`
  (`NugetGenerateShimsTask.kt:2634,3184`), although `NugetRuntimeRegistration` carries a
  `DllImport` that names one library.
- A per-type `<Type>Registration` shim is emitted in the bound C# dependency's own namespace
  (`NugetGenerateShimsTask.kt:1119,1408,1573`), so two packages binding the same dependency type
  declare the same class.
- The public fixed types (`IKotlinException`, `KotlinException`, nine mapped exceptions,
  `Optional<T>`, the Flow and Func families) are emitted in each package's root namespace
  (`cir/CirErrorRenderer.kt:202-260`, `cir/CirMarshalRenderer.kt:802`, `cir/CirFlowRenderer.kt:79`,
  `cir/CirFunctionRenderer.kt:35`).
- Every generated namespace nests under the root namespace (`cir/CirTypeMapping.kt:300-328`).
- `nuget.libraryName` is the shared library's `baseName` (`NugetPlugin.kt:285-311`) and the native
  file is packed under its own name (`PackNugetTask.kt:131-134`).

Verified by spike (scratch projects, .NET SDK 10.0.301, real compiler output, recorded in the memo):

- Today's shape in one project: `CS0101` on `INugetHandle`, `NugetHandleTag` and `NugetTrace`, plus
  `CS0535` on every class implementing `INugetHandle`.
- With the internal types moved into each package namespace the project compiles, and then
  `catch (KotlinException)` is `CS0104` when both namespaces are imported; qualified as
  `LibA.KotlinException` it does not catch `LibB.KotlinException`.
- A public type shipped as source has one identity per compiling assembly. The same
  `Kotlin.Interop.KotlinException` source compiled into two referenced libraries is `CS0433` in the
  app; compiled into a library and the app it is `warning CS0436` and the catch misses at run time.
- A compiled assembly holding `KotlinException`, referenced by two source packages, gives one catch
  type: both packages' failures were caught by one `catch (KotlinException)`.
- Two packages shipping the same `runtimes/win-x64/native/shared.dll`: no warning, one file in the
  output, the first package's.
- Two packages pinning the contract exactly, at `[1.0.0]` and `[1.0.1]`, fail the consumer's restore
  with `NU1107`. As lower bounds they resolve to 1.0.1 and both failures are caught. Exact pins are
  what `PackNugetTask.kt:208` writes for every dependency today.
- `file`-scoped `NugetHandleTag` is `CS9051` in the internal constructor's signature.

Inferred, not verified by anyone: two Kotlin/Native shared libraries loaded into one process keep
separate runtime state and never bind each other's `nuget_*` symbols
([ADR-109](109-duplicate-type-hazard.md) inferred claim 1 is the same assumption). If it is wrong on
some platform, one library's calls run against the other's runtime and corrupt handles silently.
This ADR does not depend on the claim to compile, but its "isolated runtimes" consequence does.

ROADMAP Phase 14 calls a shared C# runtime package blocked on a per-library `DllImportResolver`.
That holds only for a compiled assembly that contains P/Invokes. `SetDllImportResolver` admits one
resolver per assembly (inferred from the
[API reference](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.nativelibrary.setdllimportresolver)),
and generated source compiles into the consumer's assembly, so a generated package must never set
one.

## Alternatives Considered

### 1. Internal types per package, plus a compiled contract assembly for the public types that are not library-bound (chosen)

Every internal generated type lives under its package's root namespace. `IKotlinException`,
`KotlinException`, the nine mapped exceptions and `Optional<T>` move to one compiled assembly with
no P/Invoke, which every generated package depends on.

Pros: one catch type across packages and across assemblies; no resolver; library-bound code stays
per library, so the two native runtimes stay apart by construction. Cons: the project publishes and
versions a NuGet package; the exception family's namespace moves once; the consumer's restore needs
that package.

### 2. Everything per package, internal types moved into the package namespace

Compiles, breaks no consumer. No shared catch type, `CS0104` on every fixed public name when both
namespaces are imported. This is what `uniffi-bindgen-cs` does, and its cross-crate
[issue #184](https://github.com/NordSecurity/uniffi-bindgen-cs/issues/184) is the cost (inferred
from the issue).

### 3. A source-shipped shared contract package

Rejected on the spike: each assembly that compiles the source mints its own type, and a shared
namespace turns that into `CS0433` or a silent catch miss.

### 4. The full compiled C# twin

All fixed lines, P/Invokes included, in one assembly. Needs a per-library indirection for every
native call and makes every internal helper public API. Deferred with Phase 14; nothing in
coexistence needs it.

### 5. One shared `INugetHandle`

Rejected: `NugetMarshal.HandleOf` extracts a handle from anything that implements the interface
(`cir/CirMarshalRenderer.kt:390`), so a shared interface lets package A's handle reach package B's
runtime through an erased generic. Per package, that value misses the type test.

## Decision

Three parts.

**1. Internal types per package.** `INugetHandle` and `NugetHandleTag` are emitted inside the root
namespace. Unqualified references keep resolving because every generated namespace nests under the
root (verified by spike for a class three namespaces deep). The reverse runtime shims move to a
namespace under the root; per-type registration shims move to a namespace under the root and
import the dependency's namespace. The exact namespace names are open.

**2. The contract assembly.** A net8.0 assembly with no P/Invoke and no reference to generated code,
holding `IKotlinException`, `KotlinException`, the nine mapped exceptions and `Optional<T>` (renamed
per B7 of the 1.0.0 plan). The generator stops emitting them; `PackNugetTask` adds the dependency to
the nuspec as a **lower bound** (`version="x"`), never the exact pin (`version="[x]"`) it writes for
other dependencies, and names the lowest contract version the generated text needs. The contract's
public surface is additive only for the whole 1.x line: a contract major would split generated
packages into two sets that cannot be referenced together.

Generated code compiles in another assembly than the contract, and `InternalsVisibleTo` cannot name
unknown consumer assemblies, so a construction path for `KotlinException` stays reachable: B7's
"close the public constructor" can only hide it. The shape (a `protected` constructor with a
per-package internal subclass, verified by spike, or a public factory hidden from IntelliSense, not
spiked) is open.

**3. Native file name guard.** Packing reports a native file stem that is not derived from the
package id. Severity is open.

The Flow and Func families are library-bound and have internal constructors; they stay per package
unless the open question below is answered the other way.

## Consequences

- Breaking, 0.9.0: the exception family and `Optional<T>` change namespace; consumers add one
  `using`. 30 test files and 51 lines of topic docs in this repo name them.
- The release publishes a NuGet package, and `scripts/verify.sh` packs it into the local feed.
- A two-publisher fixture and a `MultiPackageTests` project are added; they also close ADR-109's
  inferred claim 2.
- ADR-094's "global namespace" paragraph is superseded by part 1.
- ROADMAP Phase 14's C# twin line is reworded: the resolver blocks only the full twin.
- Not addressed: one package referenced from two projects of a solution compiles its
  per-declaration types twice (the `CS0436` case). That is the compiled-assembly packaging mode's
  problem.
- NativeAOT for the contract assembly is inferred (ILC stage clean in the spike, link step not run
  on the research machine). `AotSmokeTest` is the check.

## Open questions

1. Contract delivery: a package dependency (recommended) or a DLL embedded in each package
   (verified for identical copies only).
2. Whether the Flow and Func families move into the contract as abstract classes. Recommended: no.
3. Names: contract package id and namespace, reverse shim namespace, `Optional<T>`.
4. Construction of a closed `KotlinException`. Recommended: hidden public factory.
5. Native file name guard: warning (recommended) or error.
6. Contract version: its own (recommended), or the plugin's as `nuget-runtime` does.

# ADR-178: Multi-package coexistence: package-local native bridges and a shared compiled contract

## Status

Accepted

Implemented and verified 2026-09-30. Windows `scripts/verify.sh --plugin` passed the full consumer pipeline and Windows NativeAOT executed all six supported shapes. The macOS CI lane is wired but was not run locally; Linux remains separate platform-support work.

## Context

Target: A C# consumer can reference two Kotlin-built NuGet packages, catch failures from either with one exception type, and keep each library’s handles in its own runtime.

The common KotlinException catch covers unmapped/custom failures. Existing mapped exceptions inherit BCL types, not KotlinException, and retain that behavior. Each existing exception type instead gains one identity across publishers. IKotlinException permits uniform inspection but cannot be used as a C# catch type.

**Verified by current source reading:**

- Packages ship generated C# as contentFiles/Compile (PackNugetTask.kt:103-110,228-230).
- INugetHandle/NugetHandleTag are global (cir/CirRenderer.kt:20,32); every forward namespace otherwise nests beneath its package root (cir/CirTypeMapping.kt:300-327).
- Reverse runtime and trace helpers use IoGithubXxfast.KotlinNativeNuget (NugetGenerateShimsTask.kt:2634,3184); class/witness/interface/struct registrations use the dependency namespace (:1119,1408,1573,2371).
- Public exception/presence types are emitted per root (cir/CirErrorRenderer.kt:202-260; cir/CirMarshalRenderer.kt:802). Nine mapped exceptions inherit BCL classes (:230-240).
- Flow/Func implementations and native helper state are library-bound; INugetHandle gates erased-generic pointer extraction (cir/CirMarshalRenderer.kt:390).
- Forward and reverse imports read shared binary baseName (NugetPlugin.kt:285-311,145-150). Pack copies every native DLL/SO/DYLIB from local/prebuilt inputs unchanged (PackNugetTask.kt:98,131-134,180).
- Nuspec and hermetic pre-pack C# compilation currently exact-pin dependencies (PackNugetTask.kt:208; NugetCompileInteropTask.kt:74-79). Both paths must learn about the shared contract.

**Verified by historical scratch compiler/NuGet spikes**, detailed in the memo:

- Global and reverse helpers collide with CS0101.
- Namespace-only fix compiles, but KotlinException is ambiguous or a qualified catch misses the other package.
- Source-shipped common public types gain separate assembly identities, causing CS0433 or a catch miss with CS0436.
- One compiled contract dependency gives shared catch identity across source packages.
- Two packages shipping shared.dll yield one output file with no warning.
- Differing exact contract pins fail NU1107; differing minimum versions coalesce.
- File-local NugetHandleTag in a generated class constructor fails CS9051.

## Alternatives Considered

### 1. Package-local bridges plus a small compiled public contract (recommended)

Only exception types and the presence value need common identity. Runtime state, handles and P/Invokes stay per package. Requires one independently versioned NuGet dependency and consumer import migration.

### 2. Move all helpers per package, keep all public contracts duplicated

Fixes compilation but does not deliver a common exception identity. Consumers must qualify names and cannot catch another package's unmapped failure with their package's exception type.

### 3. Shared contract shipped as source

Rejected by actual CLR identity/compiler spikes: every assembly compiling the source defines its own type.

### 4. Full compiled C# runtime twin

Requires a per-library backend choice for library-bound implementation; much larger dispatch/API migration than this item. A resolver can route several distinct names, but one fixed import name in a shared implementation still needs library identity supplied per operation/instance. Not every helper inherently needs to become public; only actual cross-assembly entry points do. This broader design remains Phase 14/compiled-binding work.

### 5. Share INugetHandle too

Rejected: the existing type test would admit foreign pointers through erased generics. Per-package interfaces keep that path closed.

### 6. Warn on unqualified native stems

Only reports risk and leaves the proved silent overwrite possible. An independently published package cannot know every other package a consumer will reference. Enforced unique identities are required for the promised primary-binary coexistence.

## Decision implemented

### Package-local implementation

Move forward handle helpers into their existing package root. Move reverse runtime helpers to <PackageRoot>.NugetReverse and registrations to <PackageRoot>.NugetReverse.<DependencyNamespace>. Include struct and generic witness branches. Preserve dependency namespaces through imports/qualified global:: references.

The consumer fixture verifies root-nested handle lookup and reverse namespace migration, including registration of the shared dependency from both publishers.

Keep Flow/Func wrappers, handles, native imports, managed callbacks and live state per package. The full compiled twin is not needed. Do not install a generated resolver into the consumer assembly. **Inferred from the official [resolver API](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.nativelibrary.setdllimportresolver):** one resolver can be registered per assembly; no runtime resolver spike is needed by this design.

### Compiled contract and construction

Shipped package/assembly/namespace: **Kotlin.Native.Interop**, targeting net8.0. Types: IKotlinException, KotlinException, nine mapped exception classes and **KotlinOptional<T>**, the renamed presence struct. No P/Invoke, library reference, handle interface or native state.

**Verified by the refreshed scratch spike:** a separate consumer assembly can construct the exact common exception through a public factory while the constructor is internal:

~~~csharp
public class KotlinException : Exception, IKotlinException
{
    internal KotlinException(string type, string message, string stack, Exception? inner)
        : base(message, inner) { /* existing properties */ }

    [EditorBrowsable(EditorBrowsableState.Never)]
    public static KotlinException Create(
        string type, string message, string stack, Exception? inner = null)
        => new(type, message, stack, inner);
}
~~~

Keep the existing generated mapping table; fallback construction changes to KotlinException.Create. Mapped exception constructors remain public and their BCL inheritance/sealed status remain intact. Closing mapped constructors is not this proposal.

Command: DOTNET_ROLL_FORWARD=Major, dotnet run --project <scratch>/App/App.csproj.
net8.0 projects compiled and executed on installed .NET 9, not the absent .NET 8 runtime.

~~~text
caught shared Kotlin.Native.Interop.KotlinException: PkgA
caught BCL Kotlin.Native.Interop.KotlinArgumentException: PkgA; IKotlinException=True
caught shared Kotlin.Native.Interop.KotlinException: PkgB
caught BCL Kotlin.Native.Interop.KotlinArgumentException: PkgB; IKotlinException=True
public KotlinException constructors=0
factory EditorBrowsable=Never; mapped sealed=True
~~~

**Inferred from [EditorBrowsable documentation](https://learn.microsoft.com/en-us/dotnet/api/system.componentmodel.editorbrowsableattribute?view=net-10.0):** an editor may hide the factory. IntelliSense was not tested. It is callable public API, not protection against fabricated exceptions.

### Delivery and compatibility

Shipped as the `Xxfast.Kotlin.Native.Interop` NuGet dependency, versioned in lockstep with the plugin. Each generator declares the range from its own version to its next major (plugin 0.9.0 emits **[0.9.0,1.0.0)**); keep the API compatible throughout a major. See the 2026-10-05 amendment below.

The full consumer verification resolves both publishers against the compatible range. Application-forced overrides can interact with NuGet's direct-dependency rules; do not promise an unconditional hard restore failure for all overrides.

Local verification packs/restores the contract before dependent packages and supplies its feed to the hermetic pre-pack compiler. Publish it before dependent generated packages; production consumers use nuget.org or a private-feed mirror.

### Native identity and supported assets

The plugin derives the published SharedLibrary.baseName from the case-folded package id. The stem is `kn_` plus invariant-lowercase(packageId) with each `.`, `-` and `_` written as `_`. For example, TestLibrary becomes `kn_testlibrary` and MobileEvidence.Kotlin becomes `kn_mobileevidence_kotlin`.

**Amended 2026-10-05 ([#469](https://github.com/xxfast/kotlin-native-nuget/issues/469)), before 0.9.0 shipped.** The stem was first hex(UTF-8(invariant-lowercase(packageId))), which was unique for every id but unreadable in a crash dump, a `DllNotFoundException`, an allowlist or an exports table. The readable stem gives up one case: package ids that differ only in their separators (`Foo.Bar`, `Foo-Bar`, `Foo_Bar`) share a stem and would overwrite each other in one consumer. That was accepted as too rare to cost every package its name. The same amendment changed two behaviours: an explicit `baseName` that differs from the derived stem fails the build instead of being replaced silently, and packing takes the expected primary library by name from local link output, so a library left behind under an old `baseName` no longer fails the pack.

Generated imports and packaged filenames agree in the verified Windows consumer fixture. The macOS CI lane is wired but was not run locally; Linux filenames remain outside current platform support.

The v1 guarantee covers plugin-produced Kotlin primary binaries. Each prebuilt RID input must contain exactly the expected primary native library; unexpected native files fail packing. Local link output must contain the expected primary native library, and only that file is packed. This excludes arbitrary auxiliary native dependency relocation.

Existing baseName/prebuilt assets must migrate consistently. Namespace validity and distinct package root namespaces remain prerequisites; this ADR does not implicitly close the separate namespace-validation item.

## Verification evidence

**Verified on Windows scratch only:** Kotlin/Native 2.4.10 independently linked two mingw_x64 DLLs exporting the same nuget_live_handles/nuget_dispose names. net10.0 P/Invoke through distinct DLL paths kept counters independent and read/disposed each own StableRef.

Commands:

~~~text
<konan-2.4.10>/bin/konanc.bat <scratch>/Native.kt -produce dynamic -target mingw_x64 -o <scratch>/pkg_a
<konan-2.4.10>/bin/konanc.bat <scratch>/Native.kt -produce dynamic -target mingw_x64 -o <scratch>/pkg_b
dotnet run --project <scratch>/NativeApp/NativeApp.csproj
~~~

Real output:

~~~text
live A=0, B=0
live A=1, B=0
live A=1, B=1
own StableRef reads A=5, B=5
live A=0, B=1
live A=0, B=0
~~~

Scratch directory: %TEMP%/nuget-coexistence-refresh-29314e2639614259a4106707e3d68b12. All compiler processes completed; no repo build or source edits.

The real consumer fixture verifies both publishers against the same managed dependency, shared exception identity across helper assemblies, mapped BCL catches, each runtime's own allocate/read/dispose counter transitions, erased-generic foreign-handle rejection, distinct package-derived native names, strict extra/mismatched asset rejection, and compatible contract resolution. LeakTests remains a separate process. `scripts/verify.sh --plugin` passed on Windows; processor tests passed 1504/1504, plugin tests 667 total with two existing skips and zero failures, contract tests 3, IntegrationTests 2907, LeakTests 138, MultiPackageTests 8, and SharedExceptionTests 2. Windows NativeAOT executed all six supported shapes. The macOS CI lane is wired but was not run locally; Linux platform support remains separate work.

The two-publisher fixture does not close ADR-109's Provider timing assumption: it has no dedicated assertion that an earlier publisher sees the later publisher's scope/diagnostic.

## Consequences

- Breaking in 0.9.0: exception namespace move; shared presence type namespace/name; KotlinException constructor closure; published native filename identity and stricter prebuilt/extra-file rules.
- Contract is additive within a compatible major and published with every plugin release, at the plugin's version.
- ADR-094 global-helper choice is superseded. ADR-109's object-transfer remedy remains; its Provider timing assumption stays open because the fixture has no dedicated later-publisher assertion.
- Deferred: cross-library wrapper transfer, shared Flow/Func bases, compiled binding mode/full twin, namespace validation and general native dependency relocation.

## Human decisions

1. End state and scope: accepted compiled dependency, package-local Flow/Func/handles, real two-publisher fixture, enforced primary identity and strict v1 auxiliary-native policy; preserve mapped BCL catches.
2. Names/defaults: Kotlin.Native.Interop, KotlinOptional<T>, NugetReverse dependency suffix, independent compatible-major contract version; sibling presence rename/base constructor closure shipped with dedicated coverage.

**Accepted and shipped 2026-09-30:** the compiled `Kotlin.Native.Interop` contract (compatible range `[1.0.0,2.0.0)`), package-local wrappers and handle state, package-derived native names, strict native-asset validation, and `KotlinOptional<T>` rename/base-constructor closure are covered by the two-publisher consumer fixture. Mapped exceptions retain their BCL inheritance and public constructors. Both native runtimes independently allocate, read and dispose their own handles. The separate ADR-109 Provider timing assertion remains open. Windows verification passed; the macOS CI lane is wired but not run locally, and Linux support remains separate work.

## Amendment 2026-10-05: package id and lockstep version

Decided before the first publish, so nothing shipped under the earlier id or version.

- **Package id is `Xxfast.Kotlin.Native.Interop`.** A bare `Kotlin.` prefix reads as an official JetBrains package and cannot be reserved by this project. The owner-first id follows `Xamarin.Kotlin.StdLib` and mirrors the Maven group `io.github.xxfast`. The assembly name and the C# namespace stay `Kotlin.Native.Interop`, so generated code and consumer `using` and `catch` sites are unchanged.
- **The contract version is the plugin version.** `Kotlin.Native.Interop.csproj` reads `version` from the root `gradle.properties`, and every release publishes the contract at that version, to nuget.org and to GitHub Packages. One version covers both ecosystems, and a contract change can no longer be skipped as a duplicate of an already published version.
- **The dependency range is `[<plugin version>,<next major>)`** (`contractRange` in `NugetPackageIdentity.kt`). The floor differs per plugin release, which is the "differing minimum versions coalesce" case above, not the exact-pin case that fails `NU1107`.
- **Release order.** `release.yml` pushes the contract first and waits until nuget.org lists that exact version before it publishes anything to Maven Central or the Plugin Portal.

**Verified:** on 2026-10-05 the id was unclaimed on nuget.org (flat-container 404) and no package existed under the `Xxfast.` prefix. `scripts/verify-contract-version-ranges.sh` restores two publishers with differing floors under one ceiling onto a single contract; its versions are illustrative, not the release version.

**Inferred, not run:** the nuget.org Trusted Publishing login and the GitHub Packages push in `release.yml` first execute on the 0.9.0 tag. A package built by a 0.x plugin (`[0.x,1.0.0)`) and one built by a 1.x plugin (`[1.x,2.0.0)`) have disjoint ranges and are expected to fail restore together; that pair was not restored.

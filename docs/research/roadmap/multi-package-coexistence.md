# Two Kotlin-built NuGet packages in one .NET project (1.0.0 plan item B1)

- ROADMAP: line text as of 2026-09-30, under "First stable release (1.0.0)", 0.9.0: "Two packages built by this plugin cannot be referenced from one .NET project". Researched a day earlier from the 1.0.0 release plan's item B1, before the line existed. The nearest older ROADMAP line is Phase 14's "The C# twin: `Interop.cs`'s roughly 1.8k fixed lines as a runtime NuGet package. Blocked on a per-library `DllImportResolver`" (`ROADMAP.md:188`).
- Researched: 2026-09-29, about 30 minutes, no budget given. Seven spike cells run in a scratch directory (`%TEMP%\tmp.1xwicETJiD`, .NET SDK 10.0.301, Windows x64). No Gradle build, no Kotlin/Native link.
- Restatement: forward direction, the C# consumer declares nothing. A consumer adds two `PackageReference`s to packages built by this plugin, the project compiles, `catch (KotlinException)` catches a failure from either library, and neither library's native runtime is reached with the other's handles.
- Verdict: fix, in 0.9.0. ADR-178 drafted (Proposed, `docs/adr/178-multi-package-coexistence.md`).

## Findings

### 1. What is fixed, and where it lands

All **verified by reading** unless marked. "Library-bound" means the text carries a `[DllImport("<libraryName>")]` or calls a type that does, so the copy in package A is not interchangeable with the copy in package B even when the rest of the text is identical.

Forward, emitted by `nuget-processor` into `Interop.cs`:

| Declaration | Namespace | Visibility | Library-bound | Emitted when | Source |
|---|---|---|---|---|---|
| `INugetHandle` | global | internal | no | always | `cir/CirRenderer.kt:20` |
| `NugetHandleTag` | global | internal | no | always | `cir/CirRenderer.kt:32` |
| `NugetRuntime` | root | internal | yes | always | `cir/CirRuntimeRenderer.kt:20`, `cir/CirTranslator.kt:953` |
| `NugetMarshal` | root | internal | yes, and carries a per-library factory table and boxer list | always | `cir/CirMarshalRenderer.kt:4,125-146`, `cir/CirTranslator.kt:862-873` |
| `Optional<T>` | root | **public** | no | always | `cir/CirMarshalRenderer.kt:802`, `cir/CirTranslator.kt:875` |
| `NugetErrorNative` | root | internal | yes | always | `cir/CirErrorRenderer.kt:65`, `cir/CirTranslator.kt:891` |
| `IKotlinException` | root | **public** | no | always | `cir/CirErrorRenderer.kt:202` |
| `KotlinException` (public ctor) | root | **public** | no | always | `cir/CirErrorRenderer.kt:208` |
| `KotlinArgumentException`, `KotlinInvalidOperationException`, `KotlinNotSupportedException`, `KotlinInvalidCastException`, `KotlinArithmeticException`, `KotlinFormatException`, `KotlinIOException`, `KotlinNullReferenceException`, `KotlinOperationCanceledException` (sealed, public ctor) | root | **public** | no | always | `cir/CirErrorRenderer.kt:230-240,243` |
| `NugetBytesNative`, `NugetListNative`, `NugetMapNative`, `NugetSetNative` | root | internal | yes | tracker flag each | `cir/CirMarshalRenderer.kt:709,727,748,772`, `cir/CirTranslator.kt:877-880` |
| `NugetFuncNative`, `NugetSuspendFuncNative` | root | internal | yes | any lambda arity seen | `cir/CirFunctionRenderer.kt:8,171` |
| `KotlinFunc<...>`, `KotlinAction<...>`, `KotlinSuspendFunc<...>`, `KotlinSuspendAction<...>` (internal ctor) | root | **public** | yes (`NugetFuncNative.Invoke/Dispose`, `NugetMarshal.FromHandle`) | **only the arities the library uses** | `cir/CirFunctionRenderer.kt:35,64,105,137,199,252,306,356`, `cir/CirTranslator.kt:920-945` |
| `NugetAsyncCallback`, `NugetScopeNative`, `NugetJobNative`, `NugetJobCell` | root | internal | yes | `needsAsync` | `cir/CirConcurrencyRenderer.kt:7,36,57,83` |
| `NugetFlow*` delegates, `NugetFlowCallbacks`, `KotlinFlowEnumerator<T>` | root | internal | yes (`NugetJobNative`, `NugetThunks`, `NugetErrorNative`, `cir/CirFlowRenderer.kt:143,161,176-178,238`) | `needsFlow` | `cir/CirFlowRenderer.kt:25-38,94` |
| `KotlinFlow<T>`, `KotlinStateFlow<T>`, `KotlinMutableStateFlow<T>` (internal ctor) | root | **public** | yes, through the enumerator and `NugetMarshal` | `needsFlow`, `needsStateFlow`, `needsMutableStateFlow` | `cir/CirFlowRenderer.kt:79,259,290` |
| `NugetStateFlowNative` | root | internal | yes | `needsSuspendStateFlow` | `cir/CirFlowRenderer.kt:319` |
| `NugetThunks` (partial), `NugetSubscription` | root | internal | yes | callbacks, subscription | `cir/CirCallbackRenderer.kt:77,240` |
| `NugetBridge`, `NugetBridgeState` | root | internal | yes | any interface bridge | `cir/CirBridgeRenderer.kt:21,70` |

"root" is `nuget.namespace`, which is `packageId`, or `Interop` when no `publish {}` sets one (`NugetPublishConfig.kt:89-90`, `NugetPlugin.kt:315`). Every other generated namespace is nested under it: `mapPackageToNamespace` returns `rootNamespace` or `"$rootNamespace.$suffix"` on every branch, including the not-under-`rootPackage` branch (`cir/CirTypeMapping.kt:300-328`).

So "identical across packages" is true only for the rows marked not library-bound: the two global types, `Optional<T>`, `IKotlinException` and the ten exception classes. Everything else differs at least by the `DllImport` library name, and the `KotlinFunc` family differs by arity set.

Reverse, emitted by `nuget-plugin` into the same package's `contentFiles` when the library binds a NuGet dependency (not in the task statement, found while enumerating):

| Declaration | Namespace | Visibility | Library-bound | Source |
|---|---|---|---|---|
| `NugetRuntimeRegistration`, `NugetAsyncEnumeration`, `NugetAsyncEnumeration<T>`, `NugetTasks`, `NugetCollections`, `NugetKotlinNative`, `INugetKotlinBridge`, `KotlinRefHandle`, `NugetKotlinErrors` | **hardcoded `IoGithubXxfast.KotlinNativeNuget`** | internal | yes (`[DllImport("test")]` in the fixture) | `NugetGenerateShimsTask.kt:2634`; usings at `:1108,1246,1479` |
| `NugetTrace` | **hardcoded `IoGithubXxfast.KotlinNativeNuget`** | internal | no | `NugetGenerateShimsTask.kt:3184` |
| `<Type>Registration` per bound type | **the C# dependency's own namespace** (`Test.Household`, `Test.Kennel`) | internal | yes | `NugetGenerateShimsTask.kt:196,228,1119,1408,1573` |

Confirmed against a generated fixture, `~/.nuget/packages/testlibrary/1.0.0-fixture.1790654289660/contentFiles/cs/any/` (packed 2026-09-29 13:58 by the other worktree, so it is evidence of that build, not of this worktree's source; it agrees with the source read above): `Interop.cs:11,20` are the two global types, `NugetRuntimeRegistration.cs:7` and `NugetTrace.cs:8` open `namespace IoGithubXxfast.KotlinNativeNuget`, `CatRegistration.cs:7` opens `namespace Test.Household`.

Packaging: `contentFiles/cs/any/*.cs` with `buildAction="Compile"` (`PackNugetTask.kt:103-110,228-230`). Both packages ship a file called `Interop.cs`; that is harmless (**verified by spike**, cell D: two packages each with `contentFiles/cs/any/Interop.cs` compile into one project with no warning).

### 2. The collision is real (verified by spike)

All cells: scratch projects, `dotnet build`, real compiler output. Cell sources mirror the generated shape, they are not the 72k-line fixture file.

**Cell A, today's shape.** One project, two `Interop.cs` files (global `INugetHandle` + `NugetHandleTag`, then `namespace LibA` / `namespace LibB` with the public types) and two `NugetTrace.cs` files:

```
LibB\Interop.cs(5,20):  error CS0101: The namespace '<global namespace>' already contains a definition for 'INugetHandle'
LibB\Interop.cs(10,26): error CS0101: The namespace '<global namespace>' already contains a definition for 'NugetHandleTag'
LibA\Interop.cs(28,46): error CS0535: 'Thing' does not implement interface member 'INugetHandle.Handle'
LibA\Interop.cs(31,29): warning CS0473: Explicit interface implementation 'Thing.INugetHandle.Handle' matches more than one interface member.
LibB\NugetTrace.cs(3,27): error CS0101: The namespace 'IoGithubXxfast.KotlinNativeNuget' already contains a definition for 'NugetTrace'
LibB\NugetTrace.cs(3,61): error CS0111: Type 'NugetTrace' already defines a member called 'Write' with the same parameter types
```

CS0535 repeats for every generated class that implements `INugetHandle`, so the real count scales with the library's class count.

**Cell A2, option (a) shape** (the two global types moved inside each root namespace, reverse namespace made per package). Compiles. A class in `LibA.Sub.Deeper` still resolves `INugetHandle` and `NugetHandleTag` unqualified. Then:

```
Consumer.cs(8,16): error CS0104: 'KotlinException' is an ambiguous reference between 'LibA.KotlinException' and 'LibB.KotlinException'
```

and with the catch qualified as `LibA.KotlinException`, a failure thrown by LibB is not caught (observed output: `NOT caught by LibA.KotlinException; type=LibB.KotlinException`).

**Cell B, source-shipped public types across assemblies.** This is the cell that prices option (b). One `Shared.cs` declaring `Kotlin.Interop.KotlinException`, compiled into `LibX`, `LibY`, and optionally the app:

- App compiles the source and references `LibX`: builds with `warning CS0436: The type 'KotlinException' in '...Shared.cs' conflicts with the imported type 'KotlinException' in 'LibX ...'. Using the type defined in '...Shared.cs'`, and at run time the catch **misses**: `MISSED: Kotlin.Interop.KotlinException, LibX, Version=1.0.0.0`.
- App references `LibX` and `LibY`, compiles no source: `error CS0433: The type 'KotlinException' exists in both 'LibX ...' and 'LibY ...'`.

Consequence: a type shipped as source has one identity per compiling assembly. A shared namespace makes that worse, not better, because today's per-package namespaces at least keep the two copies apart. The first bullet is also an **existing 0.8.0 hazard with a single package**: a class library and an app that both reference `TestLibrary` each compile their own `TestLibrary.KotlinException` (and their own `TestLibrary.Cat`). Inferred for the real package, verified for the mechanism.

**Cell C, `file`-scoped types as a cheap fix.** `file interface INugetHandle` as the interface of a public class compiles, but `file readonly struct NugetHandleTag` in the internal constructor's signature does not: `error CS9051: File-local type 'NugetHandleTag' cannot be used in a member signature in non-file-local type 'Thing'`. Rejected.

**Cell D, the recommended shape, as real packages.** A compiled `Kotlin.Interop.Contract.dll` (net8.0, `KotlinException` with a `protected` constructor), two source packages `PkgA`/`PkgB` (nuspec `<dependency>` on the contract, `contentFiles` `Interop.cs`, internal types inside the package namespace, an `internal sealed class NugetKotlinException : KotlinException`), one consumer with two `PackageReference`s, `using Kotlin.Interop; using PkgA; using PkgB;` and one `catch (KotlinException e)`. Builds with no warning and prints:

```
caught PkgA.NugetKotlinException: from PkgA (kotlin.IllegalStateException)
caught PkgB.NugetKotlinException: from PkgB (kotlin.IllegalStateException)
```

**Cell D, native file collision (not in the task statement).** Both spike packages shipped `runtimes/win-x64/native/shared.dll` with different contents. The build printed **no warning**, the output directory holds one `shared.dll`, and its content is PkgA's. `nuget.libraryName` is the first `SharedLibrary`'s `baseName` (`NugetPlugin.kt:285-288,311`) and `PackNugetTask` copies the linked file under its own name (`PackNugetTask.kt:131-134`), so two authors whose `baseName` is the same ship the same file name, one library silently wins, and every `DllImport` of the losing package binds to the wrong library (`EntryPointNotFoundException` at first call for per-declaration exports, since ADR-163 prefixes them with the library name, but the shared `nuget_*` names resolve and run against the wrong runtime).

**Cell E, the contract DLL embedded in each package instead of a dependency.** `PkgA` and `PkgB` each carrying an identical `lib/net8.0/Kotlin.Interop.Contract.dll`, no dependency: builds with no warning, same output as cell D. Not spiked: two packages carrying different versions of that DLL (inferred: the SDK's file conflict resolution keeps the higher assembly version).

**Cell F, the contract dependency's version range.** `PackNugetTask.kt:208` writes every nuspec dependency as an exact pin, `version="[x]"`. With `PkgA` pinning the contract at `[1.0.0]` and `PkgB` at `[1.0.1]` (two authors on two plugin versions), the consumer's restore fails:

```
error NU1107: Version conflict detected for Kotlin.Interop.Contract. Install/reference Kotlin.Interop.Contract 1.0.1 directly to project App4 to resolve this issue.
  App4 -> PkgA 3.0.0 -> Kotlin.Interop.Contract (= 1.0.0).
  App4 -> PkgB 3.0.0 -> Kotlin.Interop.Contract (= 1.0.1)
```

With both written as lower bounds (`version="1.0.0"`, `version="1.0.1"`) the restore resolves `Kotlin.Interop.Contract/1.0.1` and both failures are caught by the one catch. So the contract dependency must be a lower bound, and the contract must stay additive for as long as packages built against an older one are in use.

**NativeAOT.** `dotnet publish -p:PublishAot=true` on cell D's consumer: restore and the ILC stage produced no IL2xxx/IL3xxx warning, then the link step failed because this machine has no `vswhere.exe` on PATH. **Not verified end to end.** The contract types are plain managed classes with no reflection and no P/Invoke, so the claim is inferred, low risk; `AotSmokeTest/` on CI is where it gets verified.

### 3. What prior ADRs decided or assumed

- **ADR-094** (`docs/adr/094-reflection-free-generic-dispatch.md:101-105`): put `INugetHandle` in the global namespace "so every generated namespace sees it unqualified without threading a namespace string through the renderers". Its spike was one package. It never considered a second package. Since every generated namespace nests under the root (finding 1), the root namespace gives the same unqualified lookup (verified, cell A2).
- **ADR-109** (`docs/adr/109-duplicate-type-hazard.md`, Context and "Inferred claims" 1): two published modules are two native libraries; "each native library carries its own Kotlin runtime and heap; an ADR-003 `StableRef` handle minted inside `TestLibrary`'s runtime is meaningless to `TestModels`'s exports". That claim is **inferred, never spiked**, and this memo did not spike it either (no Kotlin/Native link was run). ADR-109 rejects a shared models package on it and ships only a build-time warning. It also records that the repo has no two-publisher fixture (`settings.gradle.kts` includes `:test-models` without the plugin). Nothing in ADR-109 looks at the C# compile.
- **ADR-127** (`docs/adr/127-nuget-runtime-library.md`): the 66 (now 67) `nuget_*` exports are `export()`ed into every `SharedLibrary`, unconditionally. It never mentions two libraries in a process. It defers "bullet 5 (the C# twin and the startup contract arm)" and says a startup arm is "exactly bullet 5's territory (the C# twin and its `DllImportResolver`)".
- **ADR-129** (`129-nuget-runtime-version-export.md`): `NugetRuntime.TraceLoaded` prints which runtime a library carries, per library, so with two packages the trace shows two lines, one per library. Compatible with coexistence once the type is per package, which it already is.
- **ADR-130**: the reverse error envelope rides the runtime's `NugetError` inside one library. Per library, no cross-library path.
- **ADR-163** (`163-export-symbol-package-qualification.md`): every per-declaration export is `<library>_<package>__<name>`, and a library whose sanitised name is `nuget` is refused because that prefix is the runtime's. So per-declaration symbols differ between two libraries, and the `nuget_*` symbols are deliberately the same 67 names in both. It also fixed one C# CS0101 inside a single package (`{Iface}BridgeState`), which is the same defect class one level down.
- **ROADMAP Phase 14** (`ROADMAP.md:188`): calls the C# twin "blocked on a per-library `DllImportResolver`". That premise holds only for a compiled assembly that contains the P/Invokes. Under source shipping each `DllImport` already names its own library and compiles into the consumer's assembly, so no resolver is involved. The project sets no resolver anywhere (verified: `grep SetDllImportResolver` finds one ADR-054 remark and no code).
- **Can A's handle or exception reach B's runtime today?** Exceptions: no, a C# exception object never crosses back into Kotlin as a handle. Handles: the generated API is typed, so `PkgB.Api(PkgA.Cat)` is CS1503. The one untyped door is erased generics: `NugetMarshal.HandleOf` does `if (value is INugetHandle wrapper) return wrapper.Handle;` (`cir/CirMarshalRenderer.kt:390,588,604`). With `INugetHandle` per package, `new PkgB.Box<object>(pkgACat)` misses the type test and takes the existing miss path. With one **shared** `INugetHandle` it would pass A's `StableRef` to B's runtime. Verified by reading; the consequence (memory corruption) rests on ADR-109's inferred claim.

### 4. How comparable tools solve it

| Tool | What it does | Source | Label |
|---|---|---|---|
| Kotlin/Native ObjC export | Each framework carries its own runtime and its own copies of shared types under its own prefix. "Usage of several Kotlin/Native frameworks in a Swift application is limited, but you can create an umbrella framework and export all these modules to it." | [Build final native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html) | inferred from docs |
| Same, observed by Touchlab | "Any common dependencies are present in the frameworks under different Swift modules (and with different Obj-C prefixes)." "Custom classes described in one library are not identical, at a binary level, to that same class referenced in the second library." Boxed `Int` in a collection becomes `Lib1.KotlinInt` vs `Lib2.KotlinInt`. | [Multiple Kotlin Frameworks in an Application](https://touchlab.co/multiple-kotlin-frameworks-in-application) | inferred from article |
| UniFFI C# (`uniffi-bindgen-cs`) | One namespace per crate; `RustBuffer`, `BigEndianStream`, `FfiConverter`, `UniffiException` are duplicated in each. Cross-crate use then fails to compile: "cannot convert from 'uniffi.crate_b.BigEndianStream' to 'uniffi.crate_a.BigEndianStream'". This is option (a) and its known cost. | [issue #184](https://github.com/NordSecurity/uniffi-bindgen-cs/issues/184) | inferred from issue |
| `NativeLibrary.SetDllImportResolver` | "Only one resolver can be registered per assembly." "The callers of this method should register the resolver for their own assemblies only." A generated package compiled into the consumer's assembly must therefore never set one: it would take the consumer's single slot, and two packages would race for it. | [API reference](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.nativelibrary.setdllimportresolver) | inferred from docs |
| CsWin32 | Source-generated per project; emitting interop types as `public` or `internal` is a setting, internal by default, and sharing between assemblies is a separate "layered composition" design. | [Getting started](https://microsoft.github.io/CsWin32/docs/getting-started.html) | inferred from docs; the page fetched does not state the default, that part is from recollection |
| .NET for Android / Xamarin bindings | Every binding library references one compiled runtime assembly (`Mono.Android.dll`, `Java.Interop.dll`) that owns `Java.Lang.Object`, `Java.Lang.Throwable` and the peer machinery; the per-library part is generated. | not fetched | inferred from recollection, unverified |
| Swift export | A shared `KotlinRuntime` module next to the per-module Swift. | not fetched | inferred from recollection, unverified |
| SKIE | Not consulted. It post-processes one framework and does not change the multi-framework model. | | skipped |

The pattern that holds across them: per-library generated code stays per library and hidden; whatever consumers must share by identity lives in one compiled unit. No tool ships a shared public type as source.

### 5. Options

Columns: one catch type across packages; survives NativeAOT; two native runtimes; what an existing 0.8.0 consumer sees.

| Option | One catch type | NativeAOT | Two native runtimes | 0.8.0 consumer | Files |
|---|---|---|---|---|---|
| (a) Everything per package; globals and reverse shims moved into the package namespace | **No.** CS0104 when both namespaces are imported, silent catch miss when qualified (cell A2) | Yes, nothing changes at run time | Isolated, each `DllImport` names its library | Nothing. No public name moves | about 6 source, about 30 test expectations |
| (b) Source-shipped shared contract package | **Only inside one assembly.** CS0433 or a silent catch miss as soon as two assemblies compile it (cell B) | Yes | Isolated | `KotlinException` and friends change namespace | (a) plus a packed source package |
| (c) Full compiled C# twin (all 1.8k fixed lines, P/Invokes included) | Yes | Inferred yes, but a resolver-based design needs a runtime library lookup per call site | **Needs a per-library indirection**: a compiled `DllImport` names one library. Either a resolver (one per assembly, cannot serve two libraries under one name) or function-pointer tables handed in by each package | Namespace move, plus every internal helper becomes public API of the twin | large: every renderer in `cir/` that emits a fixed type, plus a new .NET project |
| **(d) Compiled contract of the types that are not library-bound; everything library-bound stays generated source, per package, internal (recommended)** | **Yes**, across packages and across assemblies (cell D) | Inferred yes; ILC stage clean, link not run | Isolated, unchanged | Namespace of the exception family and of `Optional<T>` moves once | (a) plus a small .NET project, `CirErrorRenderer.kt`, `CirMarshalRenderer.kt`, `PackNugetTask.kt`, release and verify scripts |

Option (d) is (a) plus the smallest part of (c). It needs no `DllImportResolver`, because the contract assembly contains no P/Invoke.

Breaking surface of (d), named:
- `<PackageId>.KotlinException`, `IKotlinException`, the nine mapped exceptions and `Optional<T>` move to the contract namespace. A consumer adds one `using`. 30 files in `IntegrationTests/`, `LeakTests/`, `AotSmokeTest/` name an exception type, 5 name `Optional<`; 51 lines in `docs/topics/` mention one of them.
- The consumer's restore now needs the contract package (nuget.org, or mirrored to a private feed).
- `KotlinException`'s constructor closes here (B7), since the contract type is the one place it is declared.

### 6. The Flow and Func families

`KotlinFlow<T>`, `KotlinStateFlow<T>`, `KotlinMutableStateFlow<T>` and the four `KotlinFunc` families are public and library-bound, with internal constructors. They cannot move into a compiled contract as they are. Two end states:

- Stay per package for good (`PkgA.KotlinFlow<T>`). CS0104 appears only when a consumer imports both root namespaces and spells the type; `var`, `IAsyncEnumerable<T>` and `await foreach` avoid it. No object of these types is ever accepted by the other package, so there is no shared-identity use case like there is for a catch.
- Become abstract classes in the contract with per-package internal implementations (the same shape cell D verified for the exception). Not spiked for generics or for the three-level StateFlow hierarchy.

Whichever is chosen is fixed at 1.0.0, since moving a public type's namespace later is a major.

## Recommendation

Option (d), in 0.9.0, in three parts that can land as separate PRs:

1. **Every internal generated type lives under the package's own root namespace.** `INugetHandle` and `NugetHandleTag` move from global into the root namespace; the reverse runtime shims move from `IoGithubXxfast.KotlinNativeNuget` to a namespace under the root; per-type `<Type>Registration` shims move from the dependency's namespace to one under the root and gain a `using` for the dependency namespace. Invisible to consumers. Keeps `INugetHandle` per package on purpose (finding 3, last bullet).
2. **A compiled contract assembly** (working name `Kotlin.Interop`, net8.0, no P/Invoke) holds `IKotlinException`, `KotlinException`, the nine mapped exceptions and the renamed `Optional<T>`. Every packed nuspec declares a dependency on it as a lower bound, never an exact pin (cell F); the generator stops emitting those types and references them.
3. **A pack-time guard on the native file name**, so two packages cannot silently ship the same `runtimes/<rid>/native/<name>`.

Priced: part 1 about 6 source files; part 2 about 5 source files, one new .NET project, two scripts, one workflow; part 3 one file.

Alternatives rejected:
- (a) alone: compiles, but no shared catch type and CS0104 (cell A2).
- (b): cannot give one type identity across assemblies (cell B).
- (c): turns every internal helper into public API and needs a per-library indirection for no consumer benefit over (d).
- `file`-scoped types: CS9051 (cell C).
- A shared `INugetHandle`: opens the erased-generic path from A's handle into B's runtime.
- Embedding the contract DLL in every package (cell E): works for identical copies, relies on SDK conflict resolution once versions differ (not spiked).

## Files an implementation touches

Part 1:
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirRenderer.kt` (drop the global block)
- `.../cir/CirModel.kt`, `.../cir/CirTranslator.kt` (a root-namespace helper declaration for the two types, prepended like `CirRuntimeHelper`)
- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateShimsTask.kt` (`:196,228,1108,1119,1246,1408,1479,1573,2634,3184`; needs the forward namespace it already receives as `forwardNamespace`, `:3232`)
- tests: 26 files under `nuget-processor/src/test` and `nuget-plugin/src/test` mention `INugetHandle` or `NugetHandleTag`, 1 mentions the reverse namespace; ADR-094's text

Part 2:
- new `.NET` project for the contract (location to decide, for example `nuget-runtime-csharp/`), added to `kotlin-native-nuget.sln`
- `.../cir/CirErrorRenderer.kt` (`:202-260`), `.../cir/CirMarshalRenderer.kt` (`:795-821`), `.../cir/CirTranslator.kt` (`:875`, usings at `:968`)
- `NugetGenerateShimsTask.kt` (`:2620-2626,3164`, the `errorNamespace` wiring)
- `nuget-plugin/.../PackNugetTask.kt` (`:193-232`, the dependency entry; `:208` emits every dependency as an exact pin `[version]`, which the contract must not use, see cell F; `:212` hardcodes `net8.0`, which B2 also touches)
- `scripts/verify.sh`, `.github/workflows/release.yml`, `.github/workflows/ci.yml` (pack the contract into the local feed; push it on release)
- `IntegrationTests/`, `LeakTests/`, `AotSmokeTest/` (usings), `GeneratedBindingsCheck/`
- docs: `docs/topics/exceptions.md`, `docs/topics/architecture.md`, `docs/topics/supported-features.md`, `ROADMAP.md:186-188`

Part 3:
- `nuget-plugin/.../PackNugetTask.kt` or `NugetPlugin.kt:285-311`

Fixture for the sample test:
- a second publisher module (for example `:test-companion`, `packageId = "TestCompanion"`, its own `baseName`), `settings.gradle.kts`, `build/FixtureVersions.props` writer, a new `MultiPackageTests/` xunit project. It also closes ADR-109's open inferred claim 2, which needs a two-publisher real fixture.

## Sample test

`MultiPackageTests/MultiPackageTests.csproj` references `TestLibrary` and `TestCompanion`. The project compiling at all is the first assertion.

```csharp
using Kotlin.Interop;      // the contract namespace, name to be decided
using TestLibrary;
using TestCompanion;

public class MultiPackageTests
{
    [Fact]
    public void BothPackagesCompileIntoOneProject()
    {
        using var cat = new TestLibrary.Cat.Kitten("Oreo");
        using var probe = new TestCompanion.Probe("Biscuit");

        Assert.Equal("Oreo", cat.Name);
        Assert.Equal("Biscuit", probe.Name);
    }

    [Theory]
    [MemberData(nameof(Failures))]
    public void OneCatchTypeCoversBothPackages(Action fail, string message)
    {
        KotlinException caught = Assert.ThrowsAny<KotlinException>(fail);

        Assert.Equal(message, caught.Message);
    }

    public static TheoryData<Action, string> Failures => new()
    {
        { () => TestLibrary.Errors.Fail("from library"), "from library" },
        { () => TestCompanion.Errors.Fail("from companion"), "from companion" },
    };

    [Fact]
    public void EachPackageCountsItsOwnHandles()
    {
        long before = TestCompanion.Diagnostics.LiveHandles;

        using var cat = new TestLibrary.Cat.Kitten("Oreo");

        Assert.Equal(before, TestCompanion.Diagnostics.LiveHandles);
    }
}
```

The third test is the only run-time evidence on offer for "two runtimes, two states"; it needs a public probe in the fixture because `NugetMarshal.LiveHandles` is internal. Fixture member names above are placeholders.

## Deferred scope

- The full compiled C# twin (Phase 14 bullet 5) and the opt-in compiled-assembly packaging mode (`ROADMAP.md:283`). Neither is needed for coexistence. The single-package, two-assembly hazard from cell B is what the compiled mode would fix for per-declaration types; it belongs on that ROADMAP line.
- Cross-library object passing (ADR-109's umbrella-module remedy stays the answer).
- A startup contract arm between the contract assembly and a package (ADR-127 alternative 4).

## Unverified claims, and what breaks if wrong

1. **Two Kotlin/Native shared libraries in one process keep separate runtime state and do not interpose each other's `nuget_*` symbols.** Nobody has verified this, in this repo or in this memo. Expected to hold on Windows (exports are per module, inferred) and macOS (two-level namespace, inferred); on Linux it depends on .NET loading native libraries without `RTLD_GLOBAL` (inferred, not checked against the runtime source). The closest evidence is that Kotlin 1.3.70 made several dynamic Kotlin/Native frameworks in one app a supported case, after runtime classes "coming from different instances of runtime" used to conflict (inferred from a search summary of the Touchlab article and Kotlin release notes). If wrong, library B's calls bind to library A's `nuget_*` functions and handles are corrupted silently. The `MultiPackageTests` third test on a Linux leg (plan item G2) is the check.
2. NativeAOT end to end for the contract assembly: link step not run here.
3. The real `TestLibrary` package shows the same errors as cell A. The cell mirrors the emitted shape; the 72k-line file was not compiled twice.
4. Different versions of an embedded contract DLL resolve to the higher one through SDK conflict resolution. The package route is verified (cell F).
5. .NET for Android, Swift export and the CsWin32 default visibility are from recollection.

## Open what-questions

1. **Accept option (d)?** Recommendation: yes. It is the only option that gives one catch type across assemblies (cells B and D).
2. **Where does the contract reach the consumer from: a package on nuget.org that every generated package depends on, or a DLL embedded in each package?** Recommendation: a package. It is what NuGet is built to deduplicate and version. Cost: the release gains a `dotnet pack` and push, a package id must be reserved, and a consumer on a private feed must mirror it.
3. **Do the Flow and Func families move into the contract as abstract classes in 0.9.0, or stay per package for good?** Recommendation: stay per package. Nothing needs their shared identity, and the abstract-base design is unspiked. This must be answered before 0.9.0 either way.
4. **Names**: the contract package id and namespace, the per-package namespace for reverse shims, and `Optional<T>`'s new name (B7). Recommendation: decide with B7; this memo uses `Kotlin.Interop` as a placeholder only.
5. **How does generated code construct a `KotlinException` once its constructor is closed (B7)?** Generated code compiles in another assembly than the contract and `InternalsVisibleTo` cannot name unknown consumer assemblies, so **some public or protected construction path always remains**; B7's "close the public constructor" can only mean hiding it. Two shapes: a `protected` constructor plus a per-package internal subclass (spiked in cell D; the run-time type name then reads `PkgA.NugetKotlinException` in logs, and the nine mapped classes lose `sealed`), or a public factory hidden with `[EditorBrowsable(Never)]` (exact type names, stays `sealed`, not spiked). Recommendation: the hidden factory.
6. **Native file name guard**: error or warning, and on what rule? Recommendation: a pack-time warning when the native file stem is not derived from `packageId`, with the remedy (`baseName = ...`) in the message; an error would break existing single-package authors for a hazard they may never meet.
7. **Does the two-publisher fixture ship in 0.9.0 with the fix, or in 0.10.0 as hardening?** Recommendation: with the fix. Without it the fix is proven only by scratch spikes.
8. **Contract versioning.** The dependency must be a lower bound, not the exact pin `PackNugetTask` uses for every other dependency (cell F, NU1107), and the contract's public surface is then additive only for the whole 1.x line: a contract major would split packages into two sets that cannot be referenced together. Does the contract version follow the plugin version, as `nuget-runtime` does, or its own? Recommendation: its own version, bumped only when a type is added, with each generated package declaring the lowest contract version its generated text needs.

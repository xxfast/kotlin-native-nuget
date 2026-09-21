# Prove both directions against real published packages (NuGet census + real-klib `admit(...)`)

- ROADMAP: three lines as of 2026-09-21. `ROADMAP.md:301` "Reverse-bridge integration tests against real published NuGet packages (not just the controlled `TestDependency` fixture)". `ROADMAP.md:302` "ADR-154's `admit(...)` was never exercised against a real published klib (ktor's `Url`/`LogLevel`, kermit's `Severity`, the shapes it was designed around); only the `:test-models` fixture proves it." `ROADMAP.md:266` "How common a C# overload pair that collapses onto one Kotlin collection signature (`skipped_overload_set`, ADR-155) actually is in real-world NuGet packages was never measured".
- Researched: 2026-09-21, two passes. Pass 1 (about 20 of 25 minutes): source reading plus web lookups of published `.nuspec` files, no tool run. Pass 2 (same day, about 15 of 25 minutes): the reverse-side predictions were measured by spike over nine real packages (restore, the real `NugetMetadataReader`, then the plugin's own `parseReverseIr` / `bridgeable*` / `diagnosticWarnings` / `generateKotlinStubs` / `generateCSharpShims` in a scratch JVM test in a throwaway worktree at main `651cb7d0`). Everything measured is marked **verified by spike**; see `## Spikes run (2026-09-21)`. The forward (`admit(...)`, real klib) side was NOT spiked and stays **inferred**. Claims marked **verified by reading** are unchanged.
- Restatement: a maintainer gets a repeatable, pinned harness that runs the real plugin over real published packages and records, per package, how many members bind, how many skip and under which diagnostic kind, as a committed golden file whose diff shows up in review. Test infrastructure, both directions: reverse (NuGet package into Kotlin) and forward (a published klib type admitted into the C# surface). Nothing a consumer of the plugin sees changes.
- Verdict: **fix, in three layers; first slice is the reverse diagnostics census only.** No ADR needed for the first slice (it adds a test task, a golden-file format and one internal refactor; no bridge decision). Layer 3 (forward, real klibs) may produce ADR-154 amendments once it runs, since ADR-154 itself says "Not verified by anyone: behaviour against the real ktor and kermit klibs" (`docs/adr/154-forward-dependency-type-admission.md:206`, verified by reading).
- Verdict note, 2026-09-21 after the spikes: the verdict stands and the load-bearing claim holds (**verified by spike**: the existing restore, `deriveDllPaths`, reader, `parseReverseIr` pipeline runs over real packages today, 8 of 9 complete, generation does not throw on any of the 8). The first slice changed in three ways, detailed under Recommendation: it ships all nine packages rather than three (the whole run costs seconds, not minutes), it must record a reader failure as a row (CsvHelper crashes the reader, finding 9), and it needs an independent public-member denominator from day one (the RIR-only ratio overstates the bind share by up to 4x, finding 5).

## Findings

### 1. There is already a real-package precedent inside `:nuget-plugin:test`, and it reaches nuget.org (verified by reading)

`nuget-plugin/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/NugetExtractApiIntegrationTest.kt:100-175` restores `Newtonsoft.Json` 13.0.3 with a real `dotnet restore`, runs `deriveDllPaths`, unpacks the bundled reader (`unpackMetadataReader`), runs it as a subprocess (`metadataReaderCommand`) and parses the result with `parseReverseIr`. It asserts only that `JsonConvert` exists and that no `SKIPPED_OVERLOAD_SET` came from the reader. It silently `return`s when `dotnet` is absent (`findDotnet() ?: return`, `:101`). `NugetRestoreIntegrationTest.kt:27` and `:62` do the same for restore. `test-library/build.gradle.kts:228-232` binds the real `MimeMapping` 4.0.0 end to end (ADR-050's sample package). So: the feed is already a CI dependency, the census can reuse this exact pipeline, and no new mechanism is needed to get from a package id to a `RirFile`.

### 2. The census needs the plugin-side diagnostics, not just the reader's, and they are only available as formatted strings today (verified by reading)

`diagnosticWarnings(rir)` (`NugetGenerateBindingsTask.kt:6599-6664`) concatenates seven sources: reader diagnostics, `collisionDiagnostics`, `arityLimitDiagnostics`/`structArityLimitDiagnostics`, `ambiguousGenericConstructorDiagnostics`, `asyncDeferredDiagnostics`, `collapsedOverloadDiagnostics`, `collectionPositionDiagnostics`, then maps every entry through `formatDiagnostic` to a `String`. Item 3's number (`skipped_overload_set` from an ADR-155 collapse) is plugin-derived: `collapsedOverloadSets` (`rir/RirBridging.kt:1000-1018`) groups bridgeable methods by a Kotlin collapse key and keeps groups of size > 1 where some parameter is a `RirCollectionType`; `collapsedOverloadDiagnostics` (`NugetGenerateBindingsTask.kt:6669-6698`) emits one diagnostic per dropped member. The reader never emits it. A census built on `RirAssembly.diagnostics` alone would therefore report zero for item 3 forever. The fix is a small refactor: split `diagnosticWarnings` into `allDiagnostics(rir): List<Pair<String, RirDiagnostic>>` plus the existing format step.

Note 2026-09-21, **verified by spike**: the reader's own output IS structured. Every entry of `assemblies[].diagnostics` in a real `reverse-ir.json` has exactly `kind`, `typeName`, `memberName`, `memberSignature`, `reason`, `hint` (quoted from MimeMapping: `"kind": "skipped_array", "typeName": "MimeUtility", "memberName": "GetExtensions", "memberSignature": "GetExtensions(string)"`). What is unstructured is only the plugin's merged list: `formatDiagnostic` (`NugetGenerateBindingsTask.kt:6797-6806`) renders `w: [nuget:<id>] Skipping <Type>.<member>(<sig>): <reason>. <hint>` and drops `kind` entirely, so the kind cannot be recovered from the string. `skipped_overload_set` is absent from all eight reader outputs and `Program.cs` has no emit site for it (grep for `"skipped_overload_set"` returns nothing), so the comment at `rir/RirBridging.kt:247-250` ("multiple public `.ctor`s are grouped and skipped as an overload set upstream") is stale. Plugin-derived diagnostics measured as `diagnosticWarnings(rir).size` minus reader diagnostics: Markdig 11 (7 of them `collisionDiagnostics`), Newtonsoft.Json 8 (all 8 name collisions), NodaTime 1, every other package 0. They are real but rare, so the refactor is still required for a correct census and is small.

### 3. A non-collection Kotlin signature collapse is a HARD generation failure, so "zero bindings for the whole package" is a first-class census outcome (verified by reading; frequency inferred)

`collapsedOverloadSets`'s own comment (`RirBridging.kt:994-996`): "every other collision shape keeps the pre-existing hard generation failure (validateKotlinSignatures)". `validateDiagnostics` (`NugetGenerateBindingsTask.kt:6700-6712`) `require`s no `ERROR*` kind, and `diagnosticWarnings` calls it first (`:6600`). On a messy real package one colliding pair anywhere in the included namespaces fails the whole `nugetGenerateBindings` task. Inferred: this is the single most likely way a real package produces nothing at all, and nobody has measured how often it happens. The census must catch the `IllegalArgumentException`, record `generation: "failed"` with the error kinds, and still write the rest of the row.

Note 2026-09-21, **verified by spike**, contradicts the frequency guess above: `generateKotlinStubs(rir)` and `generateCSharpShims(rir, ...)` both returned normally for all eight packages the reader completed on, whole assembly, no namespace includes, including Newtonsoft.Json (205 Kotlin files, 1.07 M chars; 87 C# files) and Markdig (533 Kotlin files, 1.84 M chars). Zero `validateKotlinSignatures` failures, zero `error_*` kinds. The hard-failure path exists but was not hit once in roughly 1,950 bound members, so "whole package produces nothing because of one colliding pair" is NOT the most likely real-world failure; a reader crash (finding 9) and non-compiling output (finding 10) are. Keep the `generation: "failed"` cell, it is cheap, but do not design the slice around it. "Does not throw" is all that was measured: whether the generated Kotlin or C# compiles was not spiked.

### 4. Multi-TFM is resolved by NuGet, and the plugin reads exactly one target (verified by reading)

`deriveDllPaths` (`rir/RirParsing.kt:11-39`) reads `assets.targets["net8.0"]` and each library's `runtime` keys. NuGet picks the best `lib/<tfm>` for net8.0, so a package shipping `netstandard2.0` + `net8.0` binds its `net8.0` DLL and a `netstandard2.0`-only package (NuGet.Versioning) binds that one. Inferred: a `netstandard2.0` DLL references `netstandard.dll` type-forwarders rather than `System.Runtime`, which is a different `TypeReference` resolution scope for every BCL type the reader matches by name; ADR-053's `MemberReferenceHandle` lesson is the same class of hazard. This is the strongest reason to include at least one netstandard-only package. `NugetRestoreTask.kt:42` hints at the other half: a package needing a TFM above net8.0 fails restore (NU1202).

Note 2026-09-21, **verified by spike**: the plugin's own `deriveDllPaths` over each real `obj/project.assets.json` chose `lib/netstandard2.0` for MimeMapping and NuGet.Versioning, `lib/net6.0` for Humanizer.Core and Newtonsoft.Json, `lib/net8.0` for NodaTime, Polly.Core, Serilog (net9.0 group correctly ignored), Markdig and CsvHelper. The netstandard hazard did NOT materialise: NuGet.Versioning (netstandard2.0 only) has the best result of the set, 108 of 147 public members bound, with `string`, `bool`, `int`, `IEnumerable<string>` all resolved; its 26 `skipped_unbound_type_reference` are genuine unmapped types (`IVersionComparer` 8, `System.IFormatProvider` 4, `ITypeDescriptorContext` 4, `IVersionRangeComparer` 4, `System.Version` 3, `System.Type` 2, `StringBuilder` 1), not mis-resolved BCL primitives. The deferred-scope line about type-forwarders can be dropped.

### 5. What the reader drops with no diagnostic at all (verified by reading for the first two, inferred for the rest)

- Operators and every other `SpecialName` method: `NugetMetadataReader/Program.cs:954` "SpecialName: .ctor/.cctor, get_/set_ accessors, op_* operators, all skipped" with a bare `continue`. ROADMAP Phase 10 has the operator item; today they vanish unnamed.
- Base-class inheritance: `RirClass` (`rir/RirModel.kt:36-58`) carries `interfaces` but no base type, so members a class inherits from a bound base class are not on the derived Kotlin wrapper. Inferred, not checked in the reader.
- Extension methods: no `ExtensionAttribute` handling anywhere in `Program.cs` (grep, verified), so `static string Humanize(this string s)` binds as a plain static on its static class. It binds, just not idiomatically (ROADMAP Phase 10 "Map C# extension methods").
- Inferred: non-public, nested-type and generic-method handling was not read; generic methods are named (`skipped_open_generic`, `Program.cs:3623-3634`).

Consequence for the census: a bound/skipped ratio computed from the RIR has a blind spot, the silently dropped members are in neither bucket. See open question 3.

Note 2026-09-21, **verified by spike**, this blind spot is much larger than pass 1 assumed. An independent 40-line `System.Reflection.Metadata` probe counted public methods (no accessors, no `op_*`), public constructors and public properties on public and nested-public types, and compared that with "members in the RIR plus member-level reader skips" (the "visible" total):

| Package | True public members | Visible to the RIR census | Invisible |
|---|---|---|---|
| Humanizer.Core | 1477 | 386 | 74 percent |
| NodaTime | 1051 | 541 | 49 percent |
| Polly.Core | 336 | 155 | 54 percent |
| Markdig | 1164 | 941 | 19 percent |
| Newtonsoft.Json | 1199 | 1067 | 11 percent |
| NuGet.Versioning | 147 | 135 | 8 percent |
| Serilog | 429 | 409 | 5 percent |
| MimeMapping | 4 | 4 | 0 |

Two silent drops, neither named in pass 1, account for almost all of it:
- **Nested public types are dropped with no diagnostic** (`Program.cs:160-165`, verified by reading: "v1 scope: top-level public types only", bare `continue`). Humanizer has 47 nested public types (the `On.January.The1st` / `In.TheYear` date DSL), which carry most of its 897 public properties.
- **A struct that fails ADR-056 gets no type-level diagnostic, and none of its own members are counted.** NodaTime has 13 public structs carrying 495 of its 1051 public members (`Instant`, `LocalDate`, `Duration`, ...); the RIR has `struct=0` and only 2 type-level skips in the whole assembly (both interfaces). The struct shows up only indirectly, as `skipped_unsupported_struct` on OTHER types' members (125 of them). Polly.Core: 35 structs, 113 members, 1 struct in the RIR.
- Also measured: operators silently dropped: NodaTime 102, Newtonsoft.Json 72, Humanizer 11; protected members (irrelevant until Kotlin subclassing): Markdig 116, Newtonsoft 81.

So open question 3's recommendation is reversed: the denominator is needed in slice 1, see Recommendation.

### 9. The reader CRASHES on CsvHelper 33.0.1: a generic method whose signature never mentions its type parameter collides with its non-generic sibling (**verified by spike**; root cause verified by reading plus the package's XML doc)

Command: `dotnet NugetMetadataReader.dll --package CsvHelper <nuget cache>/csvhelper/33.0.1/lib/net8.0/CsvHelper.dll`. Exit code 1, empty stdout, stderr in full:

```
error: failed to read 'C:/Users/isuru/.nuget/packages/csvhelper/33.0.1/lib/net8.0/CsvHelper.dll': type `CsvContext` contains duplicate canonical managed signature `method|instance|CsvHelper.CsvContext|UnregisterClassMap|()|System.Void`
```

`CsvContext` declares `UnregisterClassMap()`, `UnregisterClassMap(Type)` and `UnregisterClassMap<TMap>()` (`CsvHelper.xml:3872-3884`). `Program.cs` checks generic parameters only on TYPES (`:1090`, the only `GetGenericParameters()` call, verified by grep); a generic METHOD is rejected only when the signature decoder meets its type parameter (`skipped_open_generic`, `:3623-3634`). `UnregisterClassMap<TMap>()` mentions `TMap` nowhere in its signature, so it maps as if it were non-generic, produces the same canonical managed signature as its sibling, and `ValidateManagedSignatures` (`Program.cs:2567-2576`, called at `:1729`) throws. In the plugin this surfaces as `nugetExtractApi` failing with "metadata reader failed (exit code 1)" (`NugetExtractApiTask.kt:69-75`): one method makes the whole package unbindable, and `bind { exclude(...) }` cannot help: the filter is per namespace and runs before type mapping (`Program.cs:172`, `IsNamespaceIncluded` `:2449`, verified by reading), and `CsvContext` sits in the root `CsvHelper` namespace, so excluding it excludes the package's main surface. The probe's rough count of public generic methods with no method type parameter in the signature: CsvHelper 18, Serilog 12, Markdig 9, Polly.Core 4, Newtonsoft.Json 1 (byte-scan heuristic for `ELEMENT_TYPE_MVAR`, approximate).

### 10. The same root cause is a SILENT wrong binding when there is no sibling to collide with (**verified by spike** for the generated text; the compile failure is inferred)

Serilog declares `Log.ForContext<TSource>()` and `ILogger.ForContext<TSource>()`; no parameterless non-generic `ForContext()` exists (verified by reading `Serilog.xml` in the 4.2.0 package: the only parameterless entries are ``M:Serilog.Log.ForContext``1``, ``M:Serilog.ILogger.ForContext``1`` and ``M:Serilog.Core.Logger.ForContext``1``). The real RIR contains `method|static|Serilog.Log|ForContext|()|Serilog.ILogger` and `method|instance|Serilog.Core.Logger|ForContext|()|Serilog.ILogger`, and `generateCSharpShims` emits, verbatim: `ILogger? result = Log.ForContext();` and `ILogger? result = receiver.ForContext();`. Inferred, not compiled: that is CS0411 (type argument cannot be inferred) at `nugetCompileInterop`, so a consumer binding Serilog gets a build failure in generated code with no diagnostic naming the member. This is exactly the class of bug the harness exists to find, and it is invisible to a census that stops at "generation did not throw".

### 11. Nullability census is per member, not per assembly (**verified by spike**)

Pass 1 predicted Humanizer as a legacy no-NRT assembly with one assembly-level `info_oblivious_nullability`. Measured: 84 on Humanizer.Core 2.14.1 and 43 on Newtonsoft.Json 13.0.3, all per member. `Program.cs:238-256` collapses to one assembly-level entry only when the assembly has NO nullable annotation anywhere; both packages are partly annotated, so they are "oblivious islands" and keep per-member entries. NuGet.Versioning, NodaTime, Polly.Core, Serilog, Markdig and MimeMapping: zero. The census format's scalar `"nullability": "oblivious"` was wrong and is replaced below.

### 12. Interfaces a consumer is meant to implement mostly census as `skipped_empty_interface` (**verified by spike**)

`NodaTime.IClock` (its one member returns the unsupported struct `Instant`), `NuGet.Versioning.IVersionComparer` and `IVersionRangeComparer` (members only inherited from `IComparer<T>` / `IEqualityComparer<T>`), Serilog's `ILogEventEnricher`, `ILogEventPropertyFactory`, `IDestructuringPolicy`, `IBatchedLogEventSink`, `ILoggingFailureListener` (8 in Serilog), Newtonsoft's `IContractResolver`, `ISerializationBinder`, `IReferenceResolver` (6), Humanizer's strategy interfaces (9). Each then cascades: every member mentioning the interface becomes `skipped_unbound_type_reference` (NuGet.Versioning: 12 of its 26). Pass 1 predicted `IClock` as a Kotlin-implementable ADR-085 case; it is not bound at all. Serilog also has 79 `skipped_default_interface_method` (the `ILogger` surface is almost entirely DIMs), a kind pass 1 did not anticipate.

### 6. The diagnostic vocabulary the census buckets by (verified by reading)

`RirDiagnosticKind` (`rir/RirModel.kt:341-512`): 25 `skipped_*`, 6 `info_*`, 2 `error_*` kinds. Reader-emitted sites are in `Program.cs` (for example `skipped_event` `:1746`, `skipped_indexer` `:1797`, `skipped_array` `:3678`, `skipped_dynamic` `:3648`, `skipped_ref_struct` `:3272`, `skipped_unbound_type_reference` `:3340`/`:3398`, `skipped_unbound_generic_instantiation` `:3550`, `info_async_not_yet_mapped` `:2014` and five more sites, `info_oblivious_nullability` `:247`). Plugin-derived kinds: `skipped_member_name_collision`, `skipped_abi_arity_limit`, `skipped_ambiguous_generic_constructor`, `skipped_kotlin_bridge`, `skipped_collection_position`, and the ADR-155 half of `skipped_overload_set`.

### 7. Existing CI and scripts (verified by reading)

`.github/workflows/ci.yml`: a `test` matrix job (macos-latest, windows-latest) that runs plugin tests, `:test-library:packNuget` (a Kotlin/Native link), four dotnet suites and an AOT publish; and `smoke-test` ("Consume plugin by coordinate", `:131`), macOS only, which publishes plugin and processor to `build/local-repo` and runs `./gradlew -p smoke-test verifyProcessorResolvesByCoordinate`. `smoke-test/` is a standalone Gradle build with its own `settings.gradle.kts`, targets `macosArm64` only, and never compiles or links: it resolves configurations. `scripts/verify.sh --plugin` runs the same two steps plus `verifyRuntimeResolvesByCoordinate`. Per user memory, `verify.sh --plugin` skips processor tests. `nuget-plugin/build.gradle.kts:80-82` has a single `tasks.test { useJUnitPlatform() }`, no tags, no second test task.

### 8. Forward side: what ADR-154 proved and did not (verified by reading)

ADR-154 `:31-36`: spike verified on a real klib, but the klib was `:test-models`; a top-level klib `enum class`, plain or with constructor properties and a companion, was admitted; "Runtime execution was not run". `:206`: real ktor and kermit are unverified; the predicted failure mode is non-compiling C# caught by `nugetCompileInterop` (ADR-138), "not silent wrong output". `test-library/build.gradle.kts:126` takes `:test-models` as `implementation(project(...))`. `test-library` targets `mingwX64` and `macosArm64` (`:102`, `:110`). Inferred: ktor, kermit, kotlinx-datetime, kotlinx-serialization-json and okio all publish both targets.

## Reverse shortlist (seven packages)

TFM and dependency data: **verified by reading** each `https://api.nuget.org/v3-flatcontainer/<id>/<version>/<id>.nuspec` on 2026-09-21. API shape and bind predictions: **inferred** from general knowledge of each library, never measured. The predictions are bands on purpose; replacing them with numbers is what the census is for. "Binds" means: a public member reaches `bridgeableRegistrables`.

| # | Package, pinned | TFM groups (net8.0 closure) | Hazards it covers | Pass 1 prediction (SUPERSEDED, see measured table below) | ROADMAP items exercised |
|---|---|---|---|---|---|
| 1 | `NuGet.Versioning` 6.12.1 | net472, netstandard2.0 only (no deps) | netstandard-only reference scope (finding 4), small pure-managed surface, `IComparable`/`IEquatable` generic interfaces, operators, static `Parse`/`TryParse(out)`, `IEnumerable<string>` properties | 40 to 60 percent | `ref`/`out`, operators, generic interfaces, collections |
| 2 | `Humanizer.Core` 2.14.1 | netstandard1.0, netstandard2.0, net6.0 (no deps) | extension-method-heavy static classes, overload-rich, enums, `DateTime`/`TimeSpan`/`CultureInfo` parameters, likely legacy no-NRT (assembly-level `info_oblivious_nullability`) | 35 to 55 percent; `string`/`int`/enum statics bind, date and culture overloads skip `skipped_unbound_type_reference`, `Humanize<T>` style skip `skipped_open_generic` | extension methods, default parameter values, `TimeSpan` mapping, ADR-053 oblivious path |
| 3 | `NodaTime` 3.2.2 | net6.0, net8.0 (no deps), netstandard2.0 | struct-dominated (`Instant`, `LocalDate`, `Duration`, `Offset`), NRT-annotated, operators everywhere, static factories, `IClock` interface (Kotlin-implementable, ADR-085) | 15 to 35 percent; most structs fail ADR-056 Shape A (private state, no covering public ctor) so `skipped_unsupported_struct` cascades into every member mentioning them | structs beyond Shape A, operators, generic structs, interface bridge |
| 4 | `Polly.Core` 8.5.2 | net462, net472, net6.0, net8.0 (no deps), netstandard2.0 | async + `CancellationToken` + `ValueTask`, delegate parameters (`Func<CancellationToken, ValueTask<T>>`), generic builders (`ResiliencePipelineBuilder<T>`), struct `Outcome<T>`, options classes with property setters | 10 to 25 percent; options classes and enums bind, every `ExecuteAsync` skips (`ValueTask`, delegates) | `ValueTask`, delegate parameters, generic structs, ADR-153 token rules |
| 5 | `Serilog` 4.2.0 | net462, net471, net6.0, net8.0, net9.0 (no deps), netstandard2.0 | `params object[]`, generic method overloads `Information<T0,T1>(...)`, interfaces meant to be implemented by the consumer (`ILogEventSink`, `ILogEventEnricher`), fluent config via nested config objects, static mutable `Log.Logger`, `IDisposable` | 20 to 40 percent | `params`, arrays, open generics, `skipped_kotlin_bridge`, net9.0 group ignored correctly |
| 6 | `Markdig` 0.40.0 | net462, net8.0, net9.0 (no deps), netstandard2.0, netstandard2.1 | one tiny happy path (`Markdown.ToHtml(string, MarkdownPipeline)`) on top of a very large AST: deep class inheritance (finding 5), `ref struct`/`Span`-based parsers, `StringSlice` struct, indexers, events-as-delegates (`TryOpen` delegates) | 25 to 45 percent by member count, but the smoke call binds | `ref struct`, inheritance, indexers, delegates; good scale test (arity limits, name collisions, finding 3) |
| 7 | `CsvHelper` 33.0.1 | net462 to net48, net6.0, net7.0, net8.0 (no deps), netstandard2.0, netstandard2.1 | `IAsyncEnumerable<T>` returns (`GetRecordsAsync<T>`), `IEnumerable<T>` generic methods, `dynamic` (`GetRecord<dynamic>`, `Microsoft.CSharp`), `TextReader`/`Stream` BCL handles, `Expression<Func<>>` class maps, events/delegates in configuration | 10 to 25 percent | `skipped_dynamic`, ADR-156 on a real surface, open generics, unbound BCL handles |

Already in the repo and kept as the controls: `MimeMapping` 4.0.0 (binds cleanly, ADR-050) and `Newtonsoft.Json` 13.0.3 (finding 1; the richest overload surface, and ADR-050 recorded it as "blocked by overload sets" before ADR-057). Both get a census row at no extra cost.

### Measured census, 2026-09-21 (**verified by spike**; replaces the prediction column)

Whole assembly, no `--include` filter (the real harness will carry namespace includes, which moves these numbers). "Bound" = class members passing `bridgeableStaticMethods` + `bridgeableInstanceMethods` + `bridgeableProperties` + `bridgeableConstructors`, plus interface and struct members present in the RIR. "True share" divides by the independent probe's public-member total (finding 5); "visible share" divides by RIR members plus member-level reader skips, which is what a RIR-only census would report. Both are approximate to a few members (the reader folds `CancellationToken` overload pairs, the probe does not).

| Package | Asset chosen by `deriveDllPaths` | Reader | RIR types (class / static / iface / enum / struct) | Bound / true public | True share | Visible share | Pass 1 band | Verdict on the prediction |
|---|---|---|---|---|---|---|---|---|
| MimeMapping 4.0.0 | `lib/netstandard2.0` | ok, 132 ms, 3 KB | 0 / 2 / 0 / 0 / 0 | 1 / 4 | 25 percent | 25 percent | "binds cleanly" | wrong as a share: 1 of 4; the other 3 are `skipped_array` 1, `skipped_indexer` 2 |
| NuGet.Versioning 6.12.1 | `lib/netstandard2.0` | ok, 143 ms, 101 KB | 10 / 1 / 1 / 2 / 0 | 108 / 147 | 73 percent | 80 percent | 40 to 60 | above the band |
| Humanizer.Core 2.14.1 | `lib/net6.0` | ok, 162 ms, 329 KB | 18 / 31 / 5 / 14 / 0 | 111 / 1477 | 7.5 percent | 29 percent | 35 to 55 | far below; nested types (finding 5) |
| NodaTime 3.2.2 | `lib/net8.0` | ok, 162 ms, 374 KB | 43 / 22 / 3 / 1 / 0 | 224 / 1051 | 21 percent | 41 percent | 15 to 35 | in band on the true share only |
| Polly.Core 8.5.2 | `lib/net8.0` | ok, 157 ms, 128 KB | 28 / 13 / 0 / 3 / 1 | 44 / 336 | 13 percent | 29 percent | 10 to 25 | in band |
| Serilog 4.2.0 | `lib/net8.0` | ok, 145 ms, 247 KB | 30 / 8 / 4 / 4 / 1 | 117 / 429 | 27 percent | 29 percent | 20 to 40 | in band, but output would not compile (finding 10) |
| Markdig 0.40.0 | `lib/net8.0` | ok, 159 ms, 691 KB | 236 / 19 / 10 / 3 / 1 | 757 / 1164 | 65 percent | 81 percent | 25 to 45 | far above; the AST is mostly plain classes and properties |
| CsvHelper 33.0.1 | `lib/net8.0` | **CRASH, exit 1** (finding 9) | none | 0 / 1104 | 0 | n/a | 10 to 25 | unbindable today |
| Newtonsoft.Json 13.0.3 | `lib/net6.0` | ok, 154 ms, 699 KB | 99 / 3 / 1 / 24 / 0 | 588 / 1199 | 49 percent | 55 percent | none given | generation succeeds, finding 3's fear did not happen |

Reader times are the prebuilt reader (`dotnet NugetMetadataReader.dll`), warm machine, stderr empty on all eight successes. Generation over the real RIR: `generateKotlinStubs` 0 to 274 ms, `generateCSharpShims` 0 to 94 ms per package.

Reader diagnostics census by kind, quoted from the spike output:

```
MimeMapping      {skipped_array=1, skipped_indexer=2}
NuGet.Versioning {skipped_collection_element=1, skipped_empty_interface=2, skipped_unbound_type_reference=26}
Humanizer.Core   {info_oblivious_nullability=84, skipped_array=4, skipped_collection_element=12, skipped_empty_interface=9, skipped_generic_type_argument=4, skipped_open_generic=1, skipped_unbound_generic_instantiation=11, skipped_unbound_type_reference=184, skipped_unsupported_enum=1, skipped_unsupported_struct=54}
NodaTime         {info_uninstantiated_generic_type=1, skipped_collection_element=2, skipped_empty_interface=1, skipped_generic_interface=1, skipped_generic_type_argument=13, skipped_indexer=6, skipped_unbound_generic_instantiation=21, skipped_unbound_type_reference=70, skipped_unsupported_enum=7, skipped_unsupported_struct=125}
Polly.Core       {info_async_not_yet_mapped=2, info_cancellation_token_not_yet_mapped=12, info_uninstantiated_generic_type=15, skipped_collection_element=1, skipped_generic_type_argument=9, skipped_indexer=5, skipped_open_generic=14, skipped_unbound_generic_instantiation=28, skipped_unbound_type_reference=17, skipped_unsupported_struct=35}
Serilog          {info_async_not_yet_mapped=3, info_uninstantiated_generic_type=2, skipped_array=21, skipped_collection_element=3, skipped_default_interface_method=79, skipped_empty_interface=8, skipped_event=1, skipped_open_generic=43, skipped_unbound_generic_instantiation=26, skipped_unbound_type_reference=113}
Markdig          {info_uninstantiated_generic_type=11, skipped_array=7, skipped_collection_element=12, skipped_empty_interface=1, skipped_event=10, skipped_generic_interface=3, skipped_indexer=5, skipped_open_generic=10, skipped_unbound_generic_instantiation=15, skipped_unbound_type_reference=36, skipped_unsupported_enum=24, skipped_unsupported_struct=60}
Newtonsoft.Json  {info_oblivious_nullability=43, info_uninstantiated_generic_type=3, skipped_array=38, skipped_collection_element=16, skipped_empty_interface=6, skipped_event=7, skipped_generic_interface=2, skipped_indexer=25, skipped_open_generic=10, skipped_unbound_generic_instantiation=132, skipped_unbound_type_reference=233, skipped_unsupported_enum=16, skipped_unsupported_struct=1}
```

Demand ranking of unmapped types, lower bounds (summed from each package's top eight only) over the eight packages, from the backticked name in `reason` (regex, as the format section proposes; it worked on every entry sampled):
- `skipped_unbound_type_reference`: `System.Type` 91, `System.Exception` 90, `System.TimeSpan` 76, `System.Globalization.CultureInfo` 60, `System.DateTime` 48, `System.DateOnly` 33, `System.IO.TextWriter` 28, `System.DateTimeOffset` 20, `System.IFormatProvider` 13. Pass 1 did not list `System.Type` or `System.Exception` at all; they lead.
- `skipped_unbound_generic_instantiation`: `System.Nullable` 136 (119 in Newtonsoft alone), `System.Func` 53, `System.Action` 13. `Nullable<T>` in member position is the single largest generic gap.
- `skipped_unsupported_struct`: package-own structs dominate (`Markdig.Helpers.StringSlice` 55, `Humanizer.Bytes.ByteSize` 53, NodaTime's `LocalDateTime` 19, `LocalDate` 17, `Instant` 17, `ZonedDateTime` 14). BCL value types (`TimeSpan`, `DateTime`) land under `skipped_unbound_type_reference`, not here, so the histogram does not need to union the two kinds for BCL ranking. Some `reason` strings lead with a member or field name rather than a type (`Context` 14 in Polly, `_text`, `Lines` in Markdig), so the regex histogram needs a `subject` field to be trustworthy for this kind.
- Pass 1 hazard predictions confirmed: Humanizer's date and culture overloads skip as predicted; Polly's delegates (`System.Func` 19) and structs skip as predicted. Not confirmed: Humanizer `skipped_open_generic` is 1, not a major bucket.

Considered and not shortlisted: `FluentValidation` 11.11.0 (net5.0 to net8.0, netstandard2.0/2.1, verified; almost entirely `AbstractValidator<T>` subclassing plus `Expression<Func<T,TProp>>`, which is ROADMAP "Kotlin subclassing C# classes, explicitly deferred", so it would census at near zero and teach little); `Semver` 3.0.0 (net5.0, netstandard2.0/2.1, verified; drags `Microsoft.Extensions.Primitives`, and NuGet.Versioning covers the same ground with an empty closure).

Collapsed-overload prior (item 3), inferred: the `IEnumerable<T>` beside `List<T>`/`IList<T>` pair is a BCL idiom (`List<T>.AddRange`, `string.Join`) but uncommon in third-party public APIs, which usually take the widest interface once. Most likely hits in this shortlist: Newtonsoft.Json (`JArray`/`JContainer` adds), Serilog enricher and sink configuration, CsvHelper `WriteRecords`. Expected rate: under 1 percent of bridgeable methods, zero for most packages. That is a guess and the census replaces it.

Note 2026-09-21, **verified by spike**, this is the measurement `ROADMAP.md:266` asks for: `collapsedOverloadSets` returned **0 sets, 0 members dropped, over 845 bridgeable methods in eight packages** (MimeMapping 1, NuGet.Versioning 45, Humanizer.Core 65, NodaTime 102, Polly.Core 8, Serilog 66, Markdig 279, Newtonsoft.Json 279). The predicted hits in Newtonsoft.Json and Serilog did not occur. Caveats: CsvHelper (a predicted hit, `WriteRecords`) could not be measured because the reader crashes on it; and a method only reaches the collapse check if it is already bridgeable, so array and `Nullable<T>` siblings never get there. On this evidence "drop the whole set" costs real packages nothing today, and `:266` can close on the census roll-up with no policy review. The adjacent plugin-derived kind that DOES occur is `skipped_member_name_collision`: Newtonsoft.Json 8, Markdig 7.

## Forward shortlist (real klibs for `admit(...)` and the ADR-066 closure)

All API shape claims **inferred**; versions to be pinned at implementation time against the repo's Kotlin 2.4.10 (`smoke-test/build.gradle.kts:5`, verified).

| Klib | Admit | What it stresses |
|---|---|---|
| `co.touchlab:kermit` | `co.touchlab.kermit.Severity` | The exact ADR-154 spiked shape (plain top-level enum) from a published artifact instead of a project dependency: klib origin resolution by coordinate, plus runtime execution, which ADR-154 never ran. Smallest possible first forward case. |
| `io.ktor:ktor-client-logging` | `io.ktor.client.plugins.logging.LogLevel` | Enum with constructor properties (`info`, `headers`, `body`), the second spiked shape. Large transitive closure (ktor-client-core, ktor-io, coroutines) stresses closure walk time and proves nothing else leaks in. |
| `io.ktor:ktor-http` | `io.ktor.http.Url` | A class with a non-public constructor, many `String`/`Int` properties, and members mentioning unadmitted siblings (`URLProtocol`, `Parameters`). Proves the per-type unit: the class is kept, those members skip named with an `add admit("...")` hint. Companion and `toString`. |
| `org.jetbrains.kotlinx:kotlinx-datetime` | `kotlinx.datetime.LocalDate` | `Comparable<T>` supertype edge (ADR-101 deferral), companion factories, `@Serializable(with=...)` annotations, expect/actual in a published klib (ADR-074 trap), and the `Instant` move to `kotlin.time` (a stdlib type reached through a dependency). |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `kotlinx.serialization.json.JsonElement` and arms | A sealed hierarchy read from a klib: `JsonObject : Map<String, JsonElement>` and `JsonArray : List<JsonElement>` by delegation, `JsonNull` object arm, `JsonPrimitive` itself sealed. Stresses sealed arm discovery across a module boundary and collection-implementing classes. Most likely to find a real bug. |
| `com.squareup.okio:okio` | `okio.ByteString` | `ByteArray` in and out (ADR-151 blit), `operator get`, `Comparable`, companion extension functions (`String.encodeUtf8()`), `internal` and `@JvmField` noise. |

## Recommendation

Three layers, cheapest first. Each is its own PR.

**Layer 1 (first slice): reverse diagnostics census, in `:nuget-plugin`, no Kotlin/Native.**
- A new Gradle `Test` task `dogfoodCensus` in `nuget-plugin/build.gradle.kts` over a JUnit tag (`@Tag("dogfood")`), excluded from `tasks.test` so `verify.sh` and the PR matrix are unaffected by feed flake.
- One parameterised test class `DogfoodCensusTest` reusing finding 1's pipeline (extract its helpers from `NugetExtractApiIntegrationTest` into a shared test fixture file rather than copying them). Input: `nuget-plugin/src/test/resources/dogfood/packages.json` (id, exact version, namespace includes). Unlike the existing tests it FAILS when `dotnet` is absent instead of returning.
- Refactor `diagnosticWarnings` into `allDiagnostics(rir)` + format (finding 2). Add a pure `census(rir): Census` beside it in the plugin main source set (it is useful to users later as a `nugetCensus` report task; keep it internal for now).
- Golden files `nuget-plugin/src/test/resources/dogfood/<Id>.<version>.census.json`, compared exactly; `-Pdogfood.update=true` rewrites them. A diff in review is the whole signal: a PR that maps `ValueTask` shows Polly's bound count going up.
- First slice ships three packages: `MimeMapping` 4.0.0 (control), `NuGet.Versioning` 6.12.1 (netstandard-only), `Humanizer.Core` 2.14.1 (extension-heavy, legacy nullability). The other five are one JSON entry plus one golden each, added in a follow-up once the format has survived review.
- CI: a new `dogfood` job in `.github/workflows/ci.yml` on `ubuntu-latest` (JDK 17 + .NET 10, no Kotlin/Native toolchain), `./gradlew -p nuget-plugin dogfoodCensus`. Triggers: weekly `schedule`, `workflow_dispatch`, and PRs whose paths touch `NugetMetadataReader/**`, `nuget-plugin/src/main/**/rir/**`, `NugetGenerateBindingsTask.kt` or `dogfood/**`. Not a required check. Cache `~/.nuget/packages` with `actions/cache` keyed on the hash of `packages.json`, so a warm run never touches the feed.
- Cost (inferred, nothing timed): reader compile once about 20 to 40 s, restore plus extract about 5 to 10 s per package cold; about 2 to 3 minutes for three packages, about 4 to 5 for nine, on the cheapest runner class.
- Corrections to this layer, 2026-09-21, from the spikes (each contradicts a bullet above; the bullets are kept for the reasoning, these win):
  - **Ship all nine packages in slice 1, not three.** Measured cost (**verified by spike**, warm SDK, 8-core Windows dev box, nuget.org reachable): `dotnet restore` of a one-package project 0.14 to 0.86 s each ("Restored ... in N ms" lines; MimeMapping and Newtonsoft were already in the global cache, the other seven were downloaded); reader compile plus run through `dotnet run --project` (what `metadataReaderCommand` really does) 1.8 s from deleted `bin`/`obj`, 1.3 s warm; the prebuilt reader itself 0.12 to 0.16 s per package; `parseReverseIr` under 0.3 s; both generators under 0.4 s per package. The whole nine-package run is under 30 s of work. The Gradle side dominated: `./gradlew -p nuget-plugin test --tests <one class>` took 26 s from a cold `build/` (compile main and test), 4 s warm. The "2 to 3 minutes for three, 4 to 5 for nine" estimate was wrong by an order of magnitude; on a CI runner the job is runner setup plus Gradle and JDK/.NET provisioning, inferred at 2 to 3 minutes total regardless of package count. CI cold cost on `ubuntu-latest` is still **inferred**, bounded below by these numbers. Holding six packages back for a follow-up PR buys nothing.
  - **`reader: "failed"` is a first-class row**, alongside `generation: "failed"`. CsvHelper exits 1 today (finding 9). The harness must capture exit code and stderr, write the row, and not abort the other packages. It must run one reader invocation per package: the plugin passes all packages to one process, where one crash loses every package.
  - **The denominator moves into slice 1** (was deferred). Without it Humanizer reports 29 percent when the truth is 7.5, and a fix that starts binding nested types would not move the ratio's denominator honestly. Cheapest form: a `--count-public` mode in `NugetMetadataReader/Program.cs` that emits `publicSurface: { types, nestedTypes, structs, methods, constructors, properties, operators, events, genericMethods }` into the RIR assembly (the spike's probe is about 40 lines of `System.Reflection.Metadata`). That is a reader change, so add `NugetMetadataReader/Program.cs` and `rir/RirModel.kt` to the files touched; about 14 files.
  - **"Generation did not throw" is not a pass.** Finding 10 shows output that cannot compile. The census row should say `generation: "ok"` only for what it checked; compile status belongs to layer 2, which therefore should not be deferred far. If layer 2 slips, a cheap intermediate is `dotnet build` of the generated shims against the restored package inside the census job (no Kotlin/Native needed for the C# half); unspiked.
  - The `dogfoodCensus` tag split and the path-filtered job stand. `excludeTags` matters less for time than for feed flake.
- Priced (pass 1, before the corrections above): 1 production file touched (`NugetGenerateBindingsTask.kt`, refactor only), 1 new production file (`rir/RirCensus.kt`), 1 build file, 2 to 3 test files, 1 `packages.json`, 3 goldens, 1 workflow, 1 script, 2 docs. About 12 files.

**Layer 2: reverse end to end, standalone `dogfood/` build.** Mirrors `smoke-test/`: own `settings.gradle.kts`, consumes the plugin by coordinate from `build/local-repo` (reuses the smoke-test job's publish step, so it also proves the by-coordinate path with a real `bind {}`), binds two packages (NuGet.Versioning, Humanizer.Core), has one Kotlin function per package that calls the bound API, runs `packNuget`, and a `DogfoodTests` xunit project calls those Kotlin functions from a .NET host. That is one smoke round trip per package: C# host to Kotlin to C# package and back. Asserts: generated Kotlin compiles (`compileKotlin<Target>`), `nugetCompileInterop` compiles the shims, the smoke call returns the right string. Cost inferred at 8 to 12 minutes on one macOS leg (one Kotlin/Native link); nightly plus `workflow_dispatch` only.

**Layer 3: forward real klibs, same `dogfood/` build.** Add `publish { admit(...) }` for kermit `Severity` first, then ktor `LogLevel` and `Url`. Asserts: `packNuget` succeeds, `GeneratedBindingsCheck`-style net8.0 warnings-as-errors compile of the generated `Interop.cs`, a golden of `NugetDiagnostics.json` (the forward census: ADR-100's file already is one, `NugetReportDiagnosticsTask.kt` parses it, verified by reading), and one xunit round trip (`Severity.Warn` crosses and comes back). datetime, serialization-json and okio follow, one per PR, because each is expected to surface a bug.

Alternatives rejected:
- Put real packages in `test-library`'s `nuget { dependencies { } }`: every PR's required `test` job would then depend on more of nuget.org and on third-party surfaces the change did not touch; MimeMapping is enough there.
- Run the census inside `verify.sh`: feed flake in the local gate, and minutes added to a script already run many times a day. A separate `scripts/verify-dogfood.sh` wrapper is the right seam.
- Vendor `.nupkg` files into the repo for full offline runs: licence and size cost; an exact-version pin plus the Actions cache gives the same determinism. A yanked or unlisted version still restores by exact pin (inferred from NuGet's unlist semantics).
- Assert on skip counts with thresholds instead of goldens: hides movement inside the band, which is the thing worth reviewing.
- Float versions to catch upstream drift: makes a red build mean "they released" rather than "we broke something". Bump pins deliberately, one PR each.

## Census output format (answers item 3 by measurement)

One file per package and version, stable key order, sorted arrays, no timestamps, no absolute paths, so the golden diff is minimal:

```json
{
  "package": "Humanizer.Core", "version": "2.14.1",
  "asset": "lib/net6.0/Humanizer.dll",
  "includes": ["Humanizer"],
  "reader": "ok",
  "generation": "ok",
  "nullability": { "assemblyOblivious": false, "obliviousMembers": 84 },
  "publicSurface": { "types": 126, "nestedTypes": 47, "structs": 1, "methods": 535, "constructors": 45, "properties": 897, "operators": 11, "events": 0, "genericMethods": 13 },
  "types": { "class": 41, "staticClass": 28, "interface": 9, "enum": 12, "struct": 1, "genericDefinition": 0 },
  "members": {
    "constructor": { "seen": 30, "bound": 22 },
    "staticMethod": { "seen": 310, "bound": 140 },
    "instanceMethod": { "seen": 95, "bound": 60 },
    "property": { "seen": 120, "bound": 101 }
  },
  "diagnostics": {
    "skipped_unbound_type_reference": 97,
    "skipped_open_generic": 14,
    "info_oblivious_nullability": 1
  },
  "unboundTypeReferences": { "System.DateTime": 31, "System.Globalization.CultureInfo": 44, "System.TimeSpan": 22 },
  "collapsedOverloadSets": {
    "sets": 0, "membersDropped": 0, "bridgeableMethods": 200,
    "examples": []
  },
  "errors": []
}
```

(Numbers above are placeholders to show the shape, not predictions.) Notes:
- Format corrections 2026-09-21 (**verified by spike**): `reader` is a new field (`"ok"` or `"failed"` with `readerError` holding the first stderr line; finding 9). `nullability` is an object, not a scalar, because partly annotated packages carry per-member entries (finding 11). `publicSurface` is the independent denominator (finding 5; the sample shows Humanizer's real probe numbers). `bound` must be counted with `bridgeableStaticMethods` / `bridgeableInstanceMethods` / `bridgeableProperties` / `bridgeableConstructors`, NOT `bridgeableRegistrables(...).size`: registrables count a property's getter and setter separately, so the spike saw "bound" exceed "in RIR" (Markdig 863 against 725, Newtonsoft 718 against 586). `types.struct` is nearly always 0 or 1 on real packages while `publicSurface.structs` is 13 (NodaTime) or 35 (Polly.Core); show both.
- `asset` is the path relative to the package root that `deriveDllPaths` chose; it is the multi-TFM assertion.
- `seen` is members present in the RIR plus members named by a reader diagnostic; `bound` is what `bridgeableConstructors` / `bridgeableStaticMethods` / `bridgeableRegistrables` keep. Silent drops (finding 5) are in neither; see open question 3.
- `unboundTypeReferences` is a histogram parsed from nothing: it needs the type name as data. Today the name lives only inside `reason` text (`Program.cs:3340`). First slice can regex the backticked name out of `reason`; the clean fix is an optional `subject` field on `RirDiagnostic`, deferred. This histogram is the most useful planning output of the whole harness: it ranks which BCL type to map next (`TimeSpan` to `Duration`, `DateTime`, `Guid`, `decimal`, `Stream`, `CultureInfo`) by real demand.
- `collapsedOverloadSets` is item 3's answer. `sets` and `membersDropped` come straight from `collapsedOverloadSets(cls, ...)` summed over classes; `bridgeableMethods` is the denominator; `examples` lists up to five `Type.member(sig) | Type.member(sig)` strings so a reviewer can judge whether "drop the whole set" hurt. A roll-up `SUMMARY.md` (generated, also committed) sums the row across packages: that single table is the measurement ROADMAP `:266` asks for, and closing that line needs only the nine-package run, not layers 2 or 3.
- `generation: "failed"` plus `errors: [{kind, type, member, reason}]` covers finding 3.

## Files an implementation touches

First slice (layer 1):
- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateBindingsTask.kt` (split `diagnosticWarnings`, `:6599-6664`; no behaviour change, existing `NugetGenerateBindingsTaskTest` pins it)
- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/rir/RirCensus.kt` (new: `Census` model, `census(rir)`, stable JSON writer)
- Added 2026-09-21 (denominator in slice 1): `NugetMetadataReader/Program.cs` (emit a `publicSurface` count per assembly, counted before the top-level-only filter at `:160-165`), `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/rir/RirModel.kt` (optional `publicSurface` on `RirAssembly`, default null so old IR still parses), and nine goldens rather than three
- `nuget-plugin/build.gradle.kts` (`dogfoodCensus` task, `excludeTags("dogfood")` on `test`)
- `nuget-plugin/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/DogfoodCensusTest.kt` (new), `RirCensusTest.kt` (new, pure, runs in the normal `test` task against a hand-built `RirFile`), a shared `RealPackageFixture.kt` extracted from `NugetExtractApiIntegrationTest.kt`
- `nuget-plugin/src/test/resources/dogfood/packages.json`, three `*.census.json` goldens, generated `SUMMARY.md`
- `.github/workflows/ci.yml` (new `dogfood` job), `scripts/verify-dogfood.sh` (new; `--update` flag)
- Docs: `CONTRIBUTING.md` (how to update a golden), `ROADMAP.md` lines 266, 301 (documenter, on close)

Layer 2 and 3: `dogfood/settings.gradle.kts`, `dogfood/build.gradle.kts`, `dogfood/src/nativeMain/kotlin/...`, `dogfood/DogfoodTests/DogfoodTests.csproj` + tests, `dogfood/nuget.config`, workflow job, `scripts/verify-dogfood.sh --e2e`. No generator file is expected to change except to fix what the harness finds.

## Sample test (proposed harness layout)

```
nuget-plugin/
  build.gradle.kts                     tasks.register<Test>("dogfoodCensus") { useJUnitPlatform { includeTags("dogfood") } }
  src/main/kotlin/.../rir/RirCensus.kt
  src/test/kotlin/.../DogfoodCensusTest.kt
  src/test/resources/dogfood/
    packages.json
    MimeMapping.4.0.0.census.json
    NuGet.Versioning.6.12.1.census.json
    Humanizer.Core.2.14.1.census.json
    SUMMARY.md
dogfood/                               layer 2 and 3, standalone build like smoke-test/
scripts/verify-dogfood.sh
```

```kotlin
@Tag("dogfood")
class DogfoodCensusTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("packages")
  fun `census matches the committed golden`(pkg: DogfoodPackage) {
    val dotnet: String = requireNotNull(findDotnet()) { "dogfoodCensus needs dotnet on PATH" }
    val rir: RirFile = RealPackageFixture.extract(dotnet, pkg)   // restore, deriveDllPaths, reader
    val actual: String = census(rir, pkg).toStableJson()
    val golden: File = goldenFor(pkg)
    if (System.getProperty("dogfood.update") == "true") golden.writeText(actual)
    assertEquals(golden.readText(), actual, "census drifted for ${pkg.id}; rerun with -Pdogfood.update=true and review the diff")
  }
}
```

## Spikes run (2026-09-21)

All on Windows 11, .NET SDK 10.0.301, scratch project per package (`<TargetFramework>net8.0</TargetFramework>`, one `PackageReference`, exact version), nuget.org reachable, every `dotnet restore` exit 0. Reader: the repo's `NugetMetadataReader` at main `651cb7d0`, copied to a temp dir, built once, run as `dotnet NugetMetadataReader.dll --package <Id> <dll>` with no include or exclude. Plugin side: a scratch JUnit class in the throwaway worktree `kn-w5` (deleted afterwards) calling the plugin's real functions on each `reverse-ir.json`. No Kotlin/Native link, no `verify.sh`.

| Package | Version | TFM asset chosen | Restore | Reader | `reverse-ir.json` | Plugin parse + both generators | Result |
|---|---|---|---|---|---|---|---|
| MimeMapping | 4.0.0 | `lib/netstandard2.0` | 137 ms (cached) | 132 ms | 3,251 B | ok | 1 of 4 members bound |
| NuGet.Versioning | 6.12.1 | `lib/netstandard2.0` | 860 ms | 143 ms | 103,266 B | ok | 108 of 147, best of the set |
| Humanizer.Core | 2.14.1 | `lib/net6.0` | 508 ms | 162 ms | 337,254 B | ok (105 Kotlin files) | 111 of 1477; 47 nested types silently dropped |
| NodaTime | 3.2.2 | `lib/net8.0` | 757 ms | 162 ms | 382,627 B | ok | 224 of 1051; 13 structs, 495 members silently dropped |
| Polly.Core | 8.5.2 | `lib/net8.0` | 520 ms | 157 ms | 131,377 B | ok | 44 of 336 |
| Serilog | 4.2.0 | `lib/net8.0` | 596 ms | 145 ms | 253,324 B | ok, but emits `Log.ForContext();` (finding 10) | 117 of 429 |
| Markdig | 0.40.0 | `lib/net8.0` | 506 ms | 159 ms | 707,285 B | ok (533 Kotlin files, 1.84 M chars) | 757 of 1164 |
| CsvHelper | 33.0.1 | `lib/net8.0` | 780 ms | **exit 1, 116 ms** | 0 B | not reached | reader crash, finding 9 |
| Newtonsoft.Json | 13.0.3 | `lib/net6.0` | 156 ms (cached) | 154 ms | 716,066 B | ok (205 Kotlin files) | 588 of 1199 |

Other spikes the same day:
- Plugin's `deriveDllPaths` over each real `project.assets.json`: returned exactly one DLL per package, the asset in the table. **Verified.**
- `parseReverseIr` over all eight real files: no unknown diagnostic kind, no decode failure; slowest 255 ms (first call, JIT), the rest under 35 ms. **Verified.**
- `dotnet run --project <reader>` (the plugin's real launch path): 1.8 s after deleting `bin`/`obj`, 1.3 s warm. **Verified** on a warm SDK; a first-ever run on a clean CI runner is inferred to be slower.
- Gradle: one test class in `:nuget-plugin`, 26 s from a cold `build/`, 4 s warm. **Verified.**
- Independent public-member probe (40 lines, `System.Reflection.Metadata`, scratch dir): numbers in finding 5. **Verified**, approximate (the generic-method-without-T count uses a byte scan).
- No hang anywhere; no multi-TFM failure; stderr empty on every successful reader run.

## Candidate ROADMAP lines from the spikes (for the documenter; each is a what-question for the human first)

1. **Reader crash, whole package unbindable**: a generic method whose signature does not mention its own type parameter (`CsvContext.UnregisterClassMap<TMap>()`) is mapped as non-generic and trips `ValidateManagedSignatures` (finding 9). Fix shape: check `MethodDefinition.GetGenericParameters().Count > 0` and emit `skipped_open_generic`. Verified crash on CsvHelper 33.0.1.
2. **Silent non-compiling shim from the same cause**: `Serilog.Log.ForContext<TSource>()` binds as `Log.ForContext()` (finding 10). Same fix as line 1; listed separately because the symptom differs (no crash, build failure in generated C#). Generated text verified; the compile error is inferred.
3. **One bad type kills every package in the build**: the reader throws out of the whole run (`Program.cs:43-46`, verified by reading) and the plugin passes every package to one process (`metadataReaderCommand`, `NugetExtractApiTask.kt:107-135`, verified by reading). Per-type containment (catch, emit a diagnostic, continue) is the ADR-043 contract. Overlaps the untracked memo `per-declaration-error-containment.md`; check it before filing.
4. **Nested public types are dropped with no diagnostic** (`Program.cs:160-165`); 47 types and most of Humanizer's surface (finding 5). At minimum a named skip kind; binding them is a separate feature.
5. **A struct that fails ADR-056 gets no type-level diagnostic and its own members are uncounted**; NodaTime loses 495 of 1051 members this way (finding 5). A type-level `skipped_unsupported_struct` naming the failed shape rule would make it visible and rank ADR-056 extensions by demand.
6. **Demand-ranked BCL gaps** (measured census above): `System.Type`, `System.Exception` as a parameter or property, `Nullable<T>` in member position, `TimeSpan`, `CultureInfo`, `DateTime` / `DateOnly` / `DateTimeOffset`, `TextWriter`, `Func` / `Action` (the last is `reverse-delegate-parameters.md`). Not new lines where ROADMAP already has them; the census gives the order.
7. **Consumer-implementable interfaces census as empty** (finding 12): interfaces whose only members are inherited from a generic BCL interface (`IVersionComparer`), and the cascade into `skipped_unbound_type_reference`.
8. Stale comment at `rir/RirBridging.kt:247-250` (the multi-ctor "overload set" skip no longer exists upstream). Trivial; fold into the slice.

## Spike first

Still unspiked; do these before or during Step 3, in this order:
1. **Does the generated output COMPILE for a real package?** Only "does not throw" was measured. Nobody has verified it; if it is wrong, layer 2's first run is red for every package and the census `generation: "ok"` is misleading. Serilog is known bad (finding 10, compile error inferred). Cheapest check: `dotnet build` the `generateCSharpShims` output against the restored package; the Kotlin half needs a Kotlin/Native compile and is layer 2.
2. **The forward direction, entirely.** `admit(...)` against published kermit, ktor, kotlinx-datetime, kotlinx-serialization-json, okio klibs needs Kotlin/Native builds and was deliberately not run. Every claim in the forward shortlist stays **inferred**; nobody has verified that a klib resolved by coordinate (rather than `project(...)`) is found by the ADR-154 origin resolution at all. If that is wrong, layer 3 produces nothing, loudly (no admitted type), not silently.
3. Namespace includes: all numbers above are whole-assembly. The real `packages.json` carries includes, so the goldens will differ from this memo's counts. Not load-bearing.
4. CI cold timing on `ubuntu-latest`, and `actions/cache` of `~/.nuget/packages`: inferred.
5. Whether `-Pdogfood.update=true` reaches the test JVM (needs `systemProperty` wiring in `build.gradle.kts`): inferred, trivial.
6. The generic-method-without-T count per package (CsvHelper 18, Serilog 12, Markdig 9, Polly.Core 4, Newtonsoft 1) is a byte-scan heuristic; an exact count needs a signature decoder. Only the two named instances (CsvHelper, Serilog) are verified.

## Deferred scope

- The five remaining reverse packages and the two controls' goldens (follow-up to slice 1, data only).
- Layer 2 (`dogfood/` end to end) and layer 3 (forward klibs), in that order; kermit `Severity` first.
- `subject` field on `RirDiagnostic` so the unbound-type histogram stops parsing `reason` text.
- (Moved INTO slice 1 on 2026-09-21, see the Recommendation corrections.) A reflection-independent "total public members" denominator to expose silent drops (finding 5).
- A user-facing `nugetCensus` report task built on `census(rir)`.
- A Windows leg for layer 2 (path separators in `asset`, `mingwX64` link).
- Transitive binding: every shortlisted package has an empty net8.0 dependency closure on purpose; a package whose public API mentions types from another package (for example `Microsoft.Extensions.Logging.Abstractions`) is a separate hazard, not covered here.
- (Closed 2026-09-21, **verified by spike**, finding 4 note: netstandard2.0 assemblies resolve BCL types correctly.) netstandard2.0 type-forwarder behaviour needs no dedicated assertion.

## Open what-questions

1. Should the census job be a required PR check? Recommendation: no. Path-filtered on PRs, weekly on a schedule, never required; the golden diff is a review aid, and a feed outage must not block merges. Decision: pending.
2. Goldens exact, or tolerant? Recommendation: exact match with an update flag. Every bridge PR that changes a count then carries a visible golden diff, which is the point. The cost is one extra command for contributors who touch the reader. Decision: pending.
3. Is a bound/skipped ratio acceptable while silent drops (operators, inherited members) are invisible to it? Recommendation: yes for slice 1, stated in `SUMMARY.md`'s header; turning those silent drops into named diagnostics is the better fix and is already implied by ADR-043's contract, so file it as a ROADMAP line rather than building a second metadata counter.
   Update 2026-09-21: the spike reverses this recommendation. Silent drops hide 49 to 74 percent of the public surface on NodaTime, Polly.Core and Humanizer.Core (finding 5), so a RIR-only ratio is misleading, not merely incomplete. New recommendation: the denominator ships in slice 1 as a reader `publicSurface` count, AND the named-diagnostic ROADMAP lines are still filed. Decision: pending.
4. Does closing ROADMAP `:266` require a policy review of "drop the whole set" once the number exists, or only the measurement? Recommendation: only the measurement; the line asks for frequency data. If the nine-package rate is non-trivial, open a new item citing the `examples`.
   Update 2026-09-21: measured 0 collapsed sets in 845 bridgeable methods (eight packages). No policy review is warranted. Decision: pending.
5. Layer 2 and 3: one `dogfood/` build for both directions, or two? Recommendation: one, since a real consumer does both in one module and that composition (`bind {}` plus `publish { admit() }` together, by coordinate) is itself untested outside `test-library`.
6. Include `Newtonsoft.Json` as a census row given finding 3 (it may hard-fail generation)? Recommendation: yes, precisely because a committed `generation: "failed"` golden on the most downloaded package on nuget.org is the most honest status line the project can carry, and it turns green in a diff when fixed.
   Update 2026-09-21: moot in the feared form. Newtonsoft.Json generates without throwing (49 percent true share). The honest red row is CsvHelper with `reader: "failed"`; include both.

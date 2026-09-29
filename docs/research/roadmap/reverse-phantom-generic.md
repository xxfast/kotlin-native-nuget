# A generic method whose type parameter never appears in its own signature maps as non-generic

- ROADMAP: line 246 as of 2026-09-29, "A generic method whose type parameter never appears in its own signature maps as non-generic, crashing the whole package's reader run when a non-generic sibling collides with it (CsvHelper 33.0.1's `CsvContext.UnregisterClassMap<TMap>()`, verified by execution) and silently binding wrong, uncompilably, when there is no sibling (Serilog's `Log.ForContext<TSource>()`, generated text verified, compile failure inferred)." Details: `docs/backlog/csvhelper-open-generic-method-signature-collision.md`.
- Researched: 2026-09-29, about 20 minutes of the 20 minute budget.
- Restatement: reverse (C# declares, Kotlin consumes). The reader never crashes a package run on a generic method whose type parameter is "phantom" (appears in no parameter or return type), and never emits uncompilable Kotlin or C# for one; each such method is a named `skipped_open_generic` skip, and its package binds everything else.
- Verdict: **fix**. No new ADR (ADR-178 not drafted). The fix brings the reader into line with what ADR-043 and ADR-072 already decided: generic methods are skipped, "permanently unless a caller can pin the type argument" (`docs/adr/072-closed-constructed-generics-in-kotlin.md:725-726`, verified by reading). An optional one-sentence clarification for ADR-072 is given below.

## Findings

1. **Generic methods never bind today; the only guard is in the decoder.** `SignatureDecoder.GetGenericMethodParameter` returns a `skipped_open_generic` diagnostic (`NugetMetadataReader/Program.cs:4169-4176`, verified by reading). `TryMapMethod` (`Program.cs:2241`) never checks `methodDef.GetGenericParameters()`; it only rejects a generic method if decoding meets `!!n` in a parameter or the return type (`Program.cs:2285-2311`, verified by reading). So a method whose type parameter is phantom sails through as an ordinary method. The backlog's `:3767`/`:3777` citations are stale; the current lines are `4163`/`4173`.
2. **Both struct and class/interface paths share `TryMapMethod`**: callers at `Program.cs:1096` (struct) and `Program.cs:1807` (class, interface, generic-type definitions via `declaringTypeParameters`), verified by reading. One guard in `TryMapMethod` covers every path, including ADR-072 per-instantiation members (`Markdig.Helpers.OrderedList\`1.InsertBefore<T>(!0)`).
3. **The canonical managed signature omits generic arity.** `BuildManagedSignature` (`Program.cs:2650-2672`) renders `kind|receiver|type|name|(params)|return` with no method arity, so `UnregisterClassMap()` and `UnregisterClassMap<TMap>()` render identically, and `ValidateManagedSignatures` (`Program.cs:2725-2735`, called at `:1184` and `:1859`) throws `InvalidDataException`, which `Main` turns into exit 1 (`Program.cs:38-46`) and `NugetExtractApiTask.kt:69-75` into a `GradleException`. Verified by reading.
4. **Crash reproduced; guard removes it (verified by spike).** A scratch copy of `NugetMetadataReader` was built twice (baseline, and patched with a `GetGenericParameters().Count > 0` guard emitting `skipped_open_generic` just before `new SignatureDecoder(` in `TryMapMethod`), then run on the nine census DLLs from the local NuGet cache:
   ```
   base CsvHelper exit=1 error: failed to read '.../csvhelper/33.0.1/lib/net8.0/CsvHelper.dll': type `CsvContext` contains duplicate canonical managed signature `method|instance|CsvHelper.CsvContext|UnregisterClassMap|()|System.Void`
   fixed CsvHelper exit=0
   ```
   All eight other packages exit 0 on both builds. No second collision hides behind the first in CsvHelper.
5. **The Serilog compile failure is now verified, not inferred.** A scratch classlib referencing Serilog 4.2.0 and CsvHelper 33.0.1 compiling the exact generated call shape:
   ```
   Class1.cs(3,54): error CS0411: The type arguments for method 'Log.ForContext<TSource>()' cannot be inferred from the usage.
   Class1.cs(5,51): error CS0411: The type arguments for method 'CsvWriter.WriteHeader<T>()' cannot be inferred from the usage.
   ```
   `c.UnregisterClassMap()` on `CsvContext` compiled (it resolves to the non-generic sibling), so for CsvHelper the crash was the only defect on that member. Verified by spike.
6. **Phantom generic methods in the nine census packages (verified by a System.Reflection.Metadata scan of public methods on top-level public types whose decoded signature has no `!!n`):** CsvHelper 18, Serilog 12, Markdig 9, Polly.Core 4, Newtonsoft.Json 1, Humanizer/NodaTime/MimeMapping/NuGet.Versioning 0. Examples: Serilog `ILogger.ForContext<T>()`, `Log.ForContext<T>()`, `Logger.ForContext<T>()`, `LoggerSinkConfiguration.Sink<T>(...)`, `LoggerEnrichmentConfiguration.With<T>()`; CsvHelper `CsvContext.UnregisterClassMap<T>()`, `CsvWriter.WriteHeader<T>()`, `TypeConverterCache.GetConverter<T>()`, `CsvConfiguration.FromAttributes<T>()`; Newtonsoft `JToken.RemoveAnnotations<T>()`; Markdig `MarkdownExtensions.Use<T>(MarkdownPipelineBuilder)`.
7. **Members that currently bind and would become skips (verified by the spike's total `skipped_*` count delta, fixed minus base):** Serilog +11, Markdig +25 (per-instantiation members of `OrderedList\`1` count once per bound instantiation), Polly.Core +2, Newtonsoft.Json +1, others 0. Each of these is today generated as an uncompilable call (finding 5 shape), so every one is a correctness gain, not a loss.
8. **Placement changes diagnostic-kind attribution (verified by spike).** With the guard placed before decoding, generic methods already skipped for another reason are re-labelled `skipped_open_generic`. Reader-level `skipped_open_generic` counts, base to fixed: Serilog 43 to 99, Polly.Core 14 to 47, Markdig 10 to 40, Newtonsoft 10 to 26, Humanizer 1 to 13, NodaTime 0 to 2, NuGet.Versioning 0 to 1, CsvHelper n/a to 99; with matching drops in `skipped_unbound_type_reference` (Serilog 114 to 72), `skipped_delegate_signature` (Polly 28 to 12, Serilog 4 to 1), `skipped_collection_element` (Newtonsoft 20 to 7, Humanizer 12 to 0), `skipped_generic_type_argument`, `skipped_unbound_generic_instantiation`, `skipped_array`. The guard sits after the two interface checks (`skipped_default_interface_method`, `skipped_interface_static_member`), whose counts did not move.
9. **Nothing downstream can see method genericity.** The RIR has no generic-method field (`RirModel.kt`; the reader only counts generic methods for `publicSurface`, `Program.cs:280,328`), so the plugin cannot detect or repair this, and a `NugetGenerateBindingsTaskTest`-level test cannot express the defect: once mapped, a phantom method is indistinguishable from a non-generic one. Verified by reading.
10. **Census goldens will change** (`nuget-plugin/src/test/resources/dogfood/*.census.json`, `SUMMARY.md`), but the exact plugin-side numbers (bound counts, share, CsvHelper's generation result) were **not** measured: `scripts/verify-dogfood.sh --update` runs the repo Gradle build, which this research did not run. **Unverified and load-bearing for the PR only:** whether CsvHelper's `generation` becomes `ok`, or a generator throws on some other CsvHelper shape now that the reader succeeds. If it throws, the golden records `generation: failed` and a new ROADMAP line is needed; the reader fix stands regardless.

## Recommendation

Add the guard to `TryMapMethod`, after the DIM and static-interface checks and before `new SignatureDecoder(` (`Program.cs:~2285`):

```csharp
// ADR-043 / ADR-072: a generic METHOD never binds, whether or not its type parameter appears in
// its signature. A phantom type parameter (UnregisterClassMap<TMap>(), ForContext<TSource>())
// otherwise decodes as an ordinary method: it collides with a non-generic sibling's canonical
// signature, or generates a CS0411 call.
if (methodDef.GetGenericParameters().Count > 0)
{
    return (null, new RirDiagnostic(
        kind: "skipped_open_generic",
        typeName: typeName,
        memberName: methodName,
        memberSignature: BuildSignatureString(mr, methodDef, methodName),
        reason: "generic method: its type parameter has no concrete type at code-generation time",
        hint: "Expose a concrete overload in a C# adapter shim."), null);
}
```

This is one file on the reader, satisfies the restatement for every phantom shape, and matches ADR-072:725. Keep `ValidateManagedSignatures` throwing: after the guard no generic method reaches it, constructors are never generic, and the canonical decoder is otherwise faithful (byref, modreq/modopt, return type all rendered, `Program.cs:3297-3360`), so a duplicate would mean a reader bug and should stay loud.

Alternatives rejected:
- **Bind phantom generic methods** (e.g. Kotlin `inline reified` forwarding a type token to a C# `MakeGenericMethod` thunk): needs reflection in the thunk (not AOT-safe, against ADR-102's direction) and a Kotlin-to-.NET type token scheme that does not exist; ADR-072 defers generic methods "unless a caller can pin the type argument". Out of scope; worth its own ROADMAP line only on demand.
- **Make the collision non-fatal** (downgrade the throw to a per-type skip): hides the root cause, still emits CS0411 for the Serilog case, and turns a future genuine reader bug into silent member loss.
- **Add generic arity to the canonical signature only**: fixes the crash, not the Serilog uncompilable binding.
- **Guard after decoding** (only phantom methods flip): smallest census diff, but keeps misleading kinds such as `skipped_collection_element` on `WriteRecords<T>(IEnumerable<T>)` that suggest a collection fix would bind it. Listed as open question 1.
- **New diagnostic kind** (`skipped_generic_method`): needs an ADR-046 contract change and `RirModel.kt` enum entry for no consumer-visible gain; ADR-072 already names `skipped_open_generic` as the kind for generic methods. Open question 2.

Optional ADR-072 clarification (append to the "Generic methods" bullet at `:725`): "This includes a generic method whose type parameter appears in no parameter or return type (`void Reset<T>()`); the reader skips on `GetGenericParameters().Count > 0`, not on meeting `!!n` while decoding."

## Files an implementation touches

- `NugetMetadataReader/Program.cs`: the guard in `TryMapMethod` (~`:2285`).
- `TestDependency/Boxes.cs`: add a phantom pair next to `Identity<T>` (`:73`), e.g. `public static void Reset() {}` and `public static void Reset<T>() {}`, plus a sibling-less `public static string Describe<T>() => typeof(T).Name;`. Without the fix this makes the whole TestDependency reader run fail, so every reverse integration test goes red (the intended failing state).
- `IntegrationTests/BoxesRoundTripTests.cs`: a fact next to `Identity_GenericMethod_IsSkippedWithDiagnostic` (`:266-274`).
- `nuget-plugin/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/NugetExtractApiIntegrationTest.kt`: an inline-fixture test (style of `:1045`).
- `nuget-plugin/src/test/resources/dogfood/*.census.json` and `SUMMARY.md`: regenerate with `scripts/verify-dogfood.sh --update`, put the diff in the PR.
- `docs/adr/072-closed-constructed-generics-in-kotlin.md:725` (optional clarification), `docs/topics/generic-types.md:93` (one clause), ROADMAP line 246 and `docs/backlog/csvhelper-open-generic-method-signature-collision.md` deleted at close, this memo deleted at close.
- No generator (`nuget-plugin/src/main/kotlin/.../codegen`) change and no `RirModel.kt` change.

## Sample test

Reader-level, `NugetExtractApiIntegrationTest` (fails today with the reader's exit 1, i.e. `runMetadataReader` throwing):

```kotlin
@Test
fun `a generic method with a phantom type parameter is skipped and never collides with its sibling`() {
  val dotnet: String = findDotnet() ?: return
  val source: String = """
    namespace Probe.Phantom;

    public sealed class Registry
    {
        public Registry() { }
        public void Unregister() { }
        public void Unregister<TMap>() { }          // collides today: same canonical signature
        public string Describe<TSource>() => "";   // no sibling: binds today as `Describe()`, CS0411
        public int Count() => 0;                     // control: still binds
    }
  """.trimIndent()

  val dll: File = compileFixture(dotnet, source, "PhantomGenericReaderFixture")
  val toolDir: File = Files.createTempDirectory("NugetMetadataReader-phantom-fixture").toFile()
  unpackMetadataReader(toolDir, javaClass.classLoader)
  val file: RirFile = parseReverseIr(
    runMetadataReader(dotnet, toolDir, mapOf("PhantomFixture" to listOf(dll.absolutePath))),
  )
  val registry: RirClass = file.assemblies.single().namespaces.single { it.name == "Probe.Phantom" }
    .types.filterIsInstance<RirClass>().single { it.name == "Registry" }

  assertEquals(listOf("Count", "Unregister"), registry.methods.map { it.name }.sorted())
  assertEquals(1, registry.methods.count { it.name == "Unregister" })
  val skipped: List<String> = file.assemblies.single().diagnostics
    .filter { it.kind == RirDiagnosticKind.SKIPPED_OPEN_GENERIC && it.typeName == "Registry" }
    .map { it.memberName }
  assertEquals(listOf("Describe", "Unregister"), skipped.sorted())
}
```

xunit, `IntegrationTests/BoxesRoundTripTests.cs` (after the `Boxes.cs` fixture additions):

```csharp
[Fact]
public void PhantomGenericMethods_AreSkippedWithDiagnostic_AndTheSiblingBinds()
{
    JsonElement diagnostics = Diagnostics(TestDependencyAssembly());
    Assert.True(HasDiagnostic(diagnostics, "Boxes", "Reset", "skipped_open_generic"));
    Assert.True(HasDiagnostic(diagnostics, "Boxes", "Describe", "skipped_open_generic"));
    // plus: the non-generic Reset() is present in the Boxes class's bound methods
}
```

Generator level: no `NugetGenerateBindingsTaskTest` can fail for this defect (finding 9); the RIR a buggy reader emits is a valid non-generic method. The existing behaviour that a `SKIPPED_OPEN_GENERIC` diagnostic surfaces as a named warning is already covered by the diagnostic-reporting tests and needs no new case.

## Deferred scope

- Binding generic methods at all (reified Kotlin type token to a closed C# instantiation). ADR-072:725 keeps this deferred; no ROADMAP line added.
- Generic methods on nested public types are counted in `publicSurface` but nested types are not bound at all; unaffected.

## Open what-questions

1. **Guard before or after decoding?** Before (recommended): every generic method reports `skipped_open_generic`, the honest root cause, at the cost of re-labelling roughly 180 existing census skips from other kinds (finding 8). After: only the phantom members flip (+11 Serilog, +25 Markdig, +2 Polly, +1 Newtonsoft, CsvHelper newly readable). Main-thread recommendation: before.
2. **Reuse `skipped_open_generic` or add `skipped_generic_method`?** Recommendation: reuse; ADR-072:639 and :725 already name it for generic methods, and a new kind is an ADR-046 contract change.
3. **Does CsvHelper generate cleanly once the reader succeeds?** Not measured (finding 10). Recommendation: the implementer runs `scripts/verify-dogfood.sh --update`; if a generator throws, record it as a new ROADMAP line rather than widening this item.

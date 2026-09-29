# Map a Kotlin exception to its .NET analog by class hierarchy, not exact name (issue #349)

- ROADMAP: GitHub issue #349 ("`BuildMapped` only matches exact `kotlin.*` type names, so a `kotlinx.io.IOException` thrown from a suspend function (or any subclass, like ktor's `ConnectTimeoutException`) comes out as a plain `KotlinException`."), folded with Phase 5 ROADMAP.md:67 as of 2026-09-29 ("`KOTLIN_EXCEPTION_TYPES` (`cir/CirErrorRenderer.kt` ~:9-18) has no entry for `kotlin.NullPointerException` (or e.g. `NoWhenBranchMatchedException`), so it maps to the bare `KotlinException`, and `KotlinException`'s constructor never puts `KotlinType` into the exception message ...").
- Researched: 2026-09-29, about 20 of 20 minutes.
- Restatement: forward (Kotlin declares, C# consumes). A C# consumer can `catch (System.IO.IOException)` for a Kotlin `kotlinx.io.IOException` or any subclass of it, and every existing ADR-029 mapping (`catch (InvalidOperationException)` and friends) also catches Kotlin *subclasses* of the mapped Kotlin type. `NullPointerException` and `NoWhenBranchMatchedException` get mappings. A Kotlin exception with a null message no longer reads `Kotlin error`.
- Verdict: fix. ADR-177 drafted (Proposed): `docs/adr/177-exception-mapping-by-class-hierarchy.md`.

## Findings

1. **The map is an exact-string switch on the C# side.** `CirErrorRenderer.kt:9-18` (`KOTLIN_EXCEPTION_TYPES`, 8 rows) drives `BuildMapped` at `CirErrorRenderer.kt:147-155`, a `kotlinType switch` on the thrown class's FQN; `_ => new KotlinException(...)`. **Verified** by reading.
2. **The Kotlin side sends only the concrete class name.** `NugetRuntime.kt:516-536`: `NugetError(type, message, stackTrace, cause)`, `type = t::class.qualifiedName ?: t::class.simpleName ?: "UnknownException"`, `message = t.message ?: "Kotlin error"` (line 530). Exports `nuget_error_*` at `NugetRuntime.kt:622-660`; `nuget_error_cause_*(handle, 0)` is the top node itself (`at(0)` returns `this`, line 538-539). **Verified** by reading.
3. **`buildError` is called from three places the design must all reach:** (a) ~30 generated forward call sites (`exports/*.kt`, `forward/ForwardKotlinPlanEmitter.kt:1097-1202`, `forward/ForwardPropertyKotlinEmitter.kt:134,179,544-583`; grep `buildError(`); (b) **inside the runtime**, on the suspend and Flow routes, `NugetLaunch.kt:47` (`launchForCSharp`) and `NugetLaunch.kt:83` (`collectForCSharp`), which is exactly the issue's suspend-function case; (c) the reverse ADR-087/130 envelope, plugin-generated `nugetKotlinError(t) = StableRef.create(buildError(t))` at `NugetGenerateBindingsTask.kt:5147-5148`. **Verified** by reading.
4. **The reverse shim duplicates the switch.** `NugetGenerateShimsTask.kt:3151-3165` (`NugetKotlinErrors.Map`, same eight rows, "If the forward table gains a row, add it here too"), fed by plugin-generated `nuget_kotlin_error_*` exports (`NugetGenerateBindingsTask.kt:5225-5260`). ROADMAP.md:221 tracks retiring the duplicate. **Verified** by reading.
5. **Suspend/Flow cancellation never reaches `BuildException`.** `NugetLaunch.kt:43` and `:79` catch `CancellationException` first and report `cancelled = 1`. Only sync exports can hand C# a `CancellationException` envelope. **Verified** by reading.
6. **The runtime cannot `is`-test `kotlinx.io.IOException`.** `nuget-runtime/build.gradle.kts:18-22` has only `api(libs.kotlinx.coroutines.core)`; a type reference needs the klib on the compile classpath, and Kotlin/Native has no `KClass.supertypes` to walk a hierarchy by name (inferred from the `KClass` API reference, https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.reflect/-k-class/, where `supertypes` is JVM-only and Native exposes `simpleName`, `qualifiedName`, `isInstance`). **Verified** that no stdlib `IOException` exists: `klib dump-metadata` of Kotlin/Native 2.4.10 `klib/common/stdlib` has zero `IOException` matches (spike S1).
7. **Kotlin/Native hierarchy facts the ordering rests on** (spike S1 and S2, Kotlin/Native 2.4.10, mingwX64, **verified**):
   - `kotlin.NumberFormatException : kotlin.IllegalArgumentException` (so NFE must be tested before IAE).
   - `kotlin.coroutines.cancellation.CancellationException : kotlin.IllegalStateException` (so a hierarchy match would turn every sync-path cancellation into `KotlinInvalidOperationException` unless it has its own row first).
   - `NoSuchElementException`, `ConcurrentModificationException`, `UnsupportedOperationException`, `ClassCastException`, `ArithmeticException`, `NullPointerException`, `IndexOutOfBoundsException` all extend `kotlin.RuntimeException` directly, none of them `IllegalStateException`.
   - `kotlin.NoWhenBranchMatchedException` and `kotlin.UninitializedPropertyAccessException` are **`internal`** classes. User or generated code cannot write `t is NoWhenBranchMatchedException`; the row must match by `qualifiedName`, which at runtime is `kotlin.NoWhenBranchMatchedException`.
   - `s!!` on a null produces `qualifiedName = kotlin.NullPointerException` with `message = null` (the ROADMAP.md:67 `Kotlin error` case).
   - `kotlinx.io.IOException` (kotlinx-io-core 0.9.1, mingwX64) is `public open class kotlinx/io/IOException : kotlin/Exception`, a real class not a typealias; `EOFException` and `files.FileNotFoundException` extend it. ktor-client-core 3.5.2 mingwX64: `io.ktor.client.network.sockets.ConnectTimeoutException : kotlinx.io.IOException`, `io.ktor.client.plugins.HttpRequestTimeoutException : kotlinx.io.IOException`.
   - A user subclass reports its own FQN (`MyIse` reported `qn=MyIse`, `isISE=true`), so `KotlinType` keeps naming the concrete class.
8. **KSP can see a klib dependency's class by name on a native target.** Spike S3 (KSP 2.3.10, Kotlin 2.4.10, `kspKotlinMingwX64`, `commonMain` depends on kotlinx-io-core 0.9.1): `resolver.getClassDeclarationByName("kotlinx.io.IOException")` returns the declaration with `origin=KOTLIN_LIB`, `okio.IOException` (not on classpath) returns `null`, `kotlin.NoWhenBranchMatchedException` resolves with `[OPEN, INTERNAL]`. **Verified.** The processor has never looked a class up by name before (grep `getClassDeclarationByName` in `nuget-processor/src/main`: no hits), so this is new code.
9. **.NET targets** (spike S4, .NET SDK 10.0.301 default TFM, **verified** there; the net8.0 BCL shape is **inferred** identical): `System.IO.IOException`, `NullReferenceException`, `OperationCanceledException` are unsealed `SystemException`s with a `(string, Exception)` ctor. `System.Runtime.CompilerServices.SwitchExpressionException` (what a C# non-exhaustive `switch` expression throws) derives from `InvalidOperationException`, the precedent for `NoWhenBranchMatchedException -> KotlinInvalidOperationException`. `EndOfStreamException` and `FileNotFoundException` derive from `IOException`.
10. **Existing tests pin raw `Message` on unmapped exceptions.** `IntegrationTests/ConstructorExceptionPropagationTests.cs:156`, `ExceptionPropagationTests.cs:17`, `PropertyExceptionPropagationTests.cs:254`, `ExceptionCauseTests.cs:57,175` assert `ex.Message` equals the Kotlin message verbatim. Prefixing the type into every `Message` breaks them and every consumer doing the same. **Verified** by reading.
11. **Runtime skew guard.** `NugetRuntimeApi.kt:22-31` `NugetRuntimeAbi1` anchor, referenced by `NugetProcessor.kt:2525,2533`; `ForwardAbiContract.kt:616-660` `NUGET_RUNTIME_EXPORTS` pins the export set (`scripts/verify-runtime-exports.sh` checks it against source). The plugin pins runtime version = plugin version (ADR-127 "Skew guard"). **Verified** by reading.
12. **Spikes S1 to S4** were run in scratch dirs (`mktemp -d`), never in the repo; commands and real output are in ADR-177 "Spikes". S1: `klib dump-metadata` on the Kotlin/Native 2.4.10 stdlib, kotlinx-io-core 0.9.1 and ktor-client-core 3.5.2 mingwX64 klibs. S2: a `kotlinc-native` mingwX64 probe executable. S3: a two-module Gradle 9.1.0 build with a KSP 2.3.10 processor. S4: a .NET SDK 10.0.301 console app.
13. **Precedents consulted.** ADR-029 (the table, exact names, "no Kotlin target does per-type mapping"); ADR-153/161 (`ex is OperationCanceledException ? 1 : 0`, `CirErrorRenderer.kt:92`: the reverse direction already classifies by `is` on the *throwing* side and sends a kind, which is this ADR's mirror); IKVM remaps `java.lang.NullPointerException` to `System.NullReferenceException` (inferred from IKVM's `map.xml`, https://github.com/ikvmnet/ikvm/blob/main/src/IKVM.Java/map.xml, not re-read in this run). Java interop, ObjC/Swift, JS, Wasm skipped: ADR-029 already recorded that none maps per type, so they cannot inform the hierarchy question.

## Recommendation

**Classify on the Kotlin side with `is`, send the matched Kotlin base FQN as a new per-node string, switch on it in C#.** (ADR-177, alternative 1.)

- `NugetError` gains `mappedType: String?`. `buildError(e, mappedType: (Throwable) -> String?)` takes the classifier as a **required** parameter (no default), so every one of the ~30 generated sites and the two runtime sites fails to compile until it passes one: an omitted classifier cannot silently fall back to stdlib-only and drop `IOException`.
- The runtime owns the stdlib rows as `nugetStdlibMappedType(t)`, most specific first: `NumberFormatException`, `IllegalArgumentException`, `CancellationException` (own row, see what-question 2), `IllegalStateException`, `NoSuchElementException`, `ConcurrentModificationException`, `UnsupportedOperationException`, `ClassCastException`, `ArithmeticException`, `NullPointerException`, then `t::class.qualifiedName == "kotlin.NoWhenBranchMatchedException"` (by name, the class is `internal`). Returns the row's Kotlin FQN, or `null`.
- The processor emits one `internal fun nugetMappedType(t: Throwable): String?` per module: `if (t is kotlinx.io.IOException) "kotlinx.io.IOException" else nugetStdlibMappedType(t)` when `resolver.getClassDeclarationByName("kotlinx.io.IOException")` resolves (spike S3), else just the stdlib call. Every generated `buildError(e)` becomes `buildError(e, ::nugetMappedType)`; suspend/Flow exports pass it into `launchForCSharp`/`collectForCSharp`, which gain the same parameter.
- New runtime export `nuget_error_cause_mapped_type(handle, index)` returning `""` for no row (index 0 is the top node, finding 2). Added to `NUGET_RUNTIME_EXPORTS`.
- C#: `BuildException` reads the mapped type per node; `BuildMapped(kotlinType, mappedType, ...)` switches on `mappedType`; `KotlinType` stays the concrete class. New C# classes `KotlinIOException : System.IO.IOException`, `KotlinNullReferenceException : NullReferenceException`, `KotlinOperationCanceledException : OperationCanceledException`, emitted unconditionally (like the other six).
- `KOTLIN_EXCEPTION_TYPES` stays the single processor table (C# switch, ADR-150 crefs); rows gain `(match: IS | NAME, requiresClass: Boolean)`. A Tier 1 test pins that the runtime's row keys equal the processor's non-optional keys, the same honesty trick `NUGET_RUNTIME_EXPORTS` uses.
- Null message: `buildError` uses the Kotlin FQN instead of `"Kotlin error"`. Non-null messages stay verbatim (finding 10). `ToString()` gains a `Kotlin type: <fqn>` line.
- Reverse slot envelope (finding 3c/4): plugin `nugetKotlinError(t)` passes `::nugetStdlibMappedType`; a new plugin-generated `nuget_kotlin_error_cause_mapped_type` export and the shim `Map` switch on it, so stdlib subclasses map identically in both directions. Reverse `IOException` rows deferred (plugin cannot see the processor's per-target classifier without new wiring).
- ADR-150 `@throws T`: resolve the cref by walking `T`'s KSP supertypes to the first table row it IS-A, so the documented exception stays the caught one.

Priced: runtime 3 files, processor ~10 files (the `buildError` emitters plus renderer, KDoc, contract), plugin 2 files, fixtures, tests, docs.

Rejected:
- **Runtime depends on kotlinx-io-core and owns every row.** One place, zero generated-site churn, reverse gets IO for free; but adds a dependency to every consumer's native library, silently upgrades a consumer's kotlinx-io to the runtime's pinned version under highest-wins resolution (inferred, ADR-127:228 says the same of coroutines), and cannot add Okio or ktor-2 bases without adding those too. Kept as the fallback if the human prefers simplicity over no-new-dependency (what-question 1).
- **Keep exact names, add more rows** (`kotlinx.io.IOException`, known ktor subclasses). Does not satisfy the restatement: a user subclass of `IllegalStateException` still falls through.
- **Send the whole supertype chain as strings.** Not implementable on Kotlin/Native: no `KClass.supertypes` (finding 6).
- **Int kind instead of string.** Needs a shared enum on both sides; a string keeps the C# switch keyed by the ADR-029/150 table and reads in a debugger.
- **Prefix the Kotlin type into every `Message`.** Breaks finding 10's assertions and consumers' equivalents for no gain on mapped types, whose C# type already names the category.

## Files an implementation touches

- `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt` (`NugetError.mappedType`, `buildError` signature and null-message fallback, `nugetStdlibMappedType`, `nuget_error_cause_mapped_type` export)
- `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetLaunch.kt` (`launchForCSharp`, `collectForCSharp` gain the classifier)
- `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntimeApi.kt` (only if the human wants `NugetRuntimeAbi2`, what-question 5)
- `nuget-runtime/src/nativeTest/...` new `MappedTypeTest.kt` (ordering: NFE, CancellationException, user ISE subclass, NoWhenBranch by name)
- `nuget-processor/.../cir/CirErrorRenderer.kt` (table rows with match mode, `BuildException` reads mapped type, `BuildMapped` switch key, three new classes, `ToString`)
- `nuget-processor/.../ForwardAbiContract.kt` (`NUGET_RUNTIME_EXPORTS` + `nuget_error_cause_mapped_type`)
- `nuget-processor/.../NugetProcessor.kt` (resolve `kotlinx.io.IOException` once, emit `nugetMappedType`)
- `nuget-processor/.../exports/FunctionExports.kt`, `GenericFunctionExports.kt`, `FlowExports.kt`, `InterfaceBridgeExports.kt`, `InterfaceBridgeFactoryExports.kt`, `LambdaParameterExports.kt`, `StoredCallbackExports.kt`, `Helpers.kt:187`, `forward/ForwardKotlinPlanEmitter.kt`, `forward/ForwardPropertyKotlinEmitter.kt` (every `buildError(e)` and every `launchForCSharp`/`collectForCSharp` call)
- `nuget-processor/.../forward/ForwardKdoc.kt:261-269` (supertype-walking cref)
- `nuget-processor/src/test/.../tier1/Tier1RuntimeStub.kt` (stub `buildError` signature, new export) and new Tier 1 cells (classifier emitted with and without kotlinx-io, runtime-vs-processor row parity)
- `nuget-plugin/.../NugetGenerateBindingsTask.kt:5147-5260` (reverse `nugetKotlinError` classifier, `nuget_kotlin_error_cause_mapped_type`)
- `nuget-plugin/.../NugetGenerateShimsTask.kt:3095-3165` (shim reads mapped type, `Map` switches on it, three new rows) and `NugetKotlinBridgeGenerationTest.kt`
- `test-library/build.gradle.kts` (add kotlinx-io-core to `nativeMain`), new fixture `test-library/src/nativeMain/kotlin/.../cat/LitterBoxErrors.kt` (IOException, IOException subclass, suspend IOException, ISE subclass, `!!` NPE, NoWhenBranch via `@Suppress("INVISIBLE_REFERENCE")`)
- `IntegrationTests/ExceptionTypeMappingTests.cs` (new facts below), `PassersBy.kt:44` comment, `IntegrationTests/XmlDocTests.cs` if a `@throws` subclass fixture is added
- `docs/adr/029-exception-type-mapping.md` (amendment pointer), `docs/topics/supported-features.md` exception rows, the exceptions topic page, `ROADMAP.md:67` deleted, `CHANGELOG`/release notes (breaking change)

## Sample test

```csharp
// IntegrationTests/ExceptionTypeMappingTests.cs
[Fact]
public void KotlinxIoIOException_MapsToIOException()
{
    var ex = Assert.ThrowsAny<IOException>(() => LitterBox.Scoop("clumping"));
    var ke = Assert.IsType<KotlinIOException>(ex);
    Assert.Equal("kotlinx.io.IOException", ke.KotlinType);
    Assert.Equal("the bag split", ex.Message);
}

[Fact]
public void IOExceptionSubclass_MapsToIOException_KeepsConcreteKotlinType()
{
    // Kotlin: class LitterBoxJammedException(m: String) : kotlinx.io.IOException(m)
    var ex = Assert.ThrowsAny<IOException>(() => LitterBox.Rake());
    Assert.Equal("io.github.xxfast.kotlin.native.nuget.test.cat.LitterBoxJammedException",
        ((IKotlinException)ex).KotlinType);
}

[Fact]
public async Task SuspendIOException_MapsToIOException()
{
    // Kotlin: suspend fun deliverLitter(): Unit = throw kotlinx.io.EOFException("truck never came")
    await Assert.ThrowsAnyAsync<IOException>(() => LitterBox.DeliverLitterAsync());
}

[Fact]
public void IllegalStateSubclass_MapsToInvalidOperation()
{
    // Kotlin: class KennelFullException : IllegalStateException("no vacancy")
    var ex = Assert.ThrowsAny<InvalidOperationException>(() => Kennel.CheckIn("Oreo"));
    Assert.IsType<KotlinInvalidOperationException>(ex);
    Assert.EndsWith("KennelFullException", ((IKotlinException)ex).KotlinType);
}

[Fact]
public void NumberFormat_StillMapsToFormat_NotArgument()
{
    var ex = Assert.ThrowsAny<FormatException>(() => Microchip.Parse("not-a-number"));
    Assert.IsType<KotlinFormatException>(ex);
}

[Fact]
public void BangBangNull_MapsToNullReference_MessageNamesKotlinType()
{
    var ex = Assert.Throws<KotlinNullReferenceException>(() => LitterBox.OwnerName());
    Assert.Equal("kotlin.NullPointerException", ex.KotlinType);
    Assert.Equal("kotlin.NullPointerException", ex.Message); // was "Kotlin error"
}

[Fact]
public void NoWhenBranchMatched_MapsToInvalidOperation()
{
    var ex = Assert.ThrowsAny<InvalidOperationException>(() => LitterBox.Sift());
    Assert.Equal("kotlin.NoWhenBranchMatchedException", ((IKotlinException)ex).KotlinType);
}

[Fact]
public void UnmappedCustomException_StillKotlinException()
{
    // OverfedCatException : Exception, not a subclass of any row
    var ex = Assert.ThrowsAny<KotlinException>(() => new Cat("Oreo", 25.0));
    Assert.IsType<KotlinException>(ex);
}

[Fact]
public void IOExceptionAsCause_MapsInnerException()
{
    // Kotlin: throw IllegalStateException("scoop failed", kotlinx.io.IOException("bag split"))
    var ex = Assert.ThrowsAny<InvalidOperationException>(() => LitterBox.ScoopAll());
    Assert.IsType<KotlinIOException>(ex.InnerException);
}
```

Runtime nativeTest (ordering):

```kotlin
@Test fun numberFormatBeatsIllegalArgument() =
  assertEquals("kotlin.NumberFormatException", nugetStdlibMappedType(NumberFormatException("x")))
@Test fun cancellationBeatsIllegalState() =
  assertEquals("kotlin.coroutines.cancellation.CancellationException",
    nugetStdlibMappedType(CancellationException("c")))
@Test fun userSubclassOfIllegalState() {
  class Full : IllegalStateException("full")
  assertEquals("kotlin.IllegalStateException", nugetStdlibMappedType(Full()))
}
```

## Deferred scope

- IOException rows on the reverse slot envelope (ADR-087/130): stdlib rows reach it in v1, IO rows do not.
- `okio.IOException` and ktor 2's `io.ktor.utils.io.errors.IOException` as further optional rows: same mechanism, not spiked (Okio is not in the local Gradle cache). Inferred: `okio.IOException` is an `expect open class` on Native.
- Finer IO rows (`kotlinx.io.EOFException -> EndOfStreamException`, `kotlinx.io.files.FileNotFoundException -> FileNotFoundException`), `IndexOutOfBoundsException -> ArgumentOutOfRangeException`, `UninitializedPropertyAccessException -> InvalidOperationException`: cheap follow-ups on the same table, not requested.
- A forward-direction error trace (ROADMAP.md:67 last clause): unrelated mechanism, stays on the ROADMAP line or becomes its own line.
- Making `BuildMapped` shared with the reverse shim (ROADMAP.md:221): not required here; the shim's switch is updated in lockstep instead.

## Open what-questions

1. **Where the kind is computed.** Recommendation: runtime stdlib rows + processor-emitted optional rows, classifier passed as a required parameter (no new runtime dependency, Okio-extensible). Alternative: `nuget-runtime` takes `implementation(kotlinx-io-core)` and owns every row (far less churn, reverse gets IO, but a new transitive dependency and a possible silent kotlinx-io upgrade for consumers). Human decision needed.
2. **`CancellationException` on sync paths.** Hierarchy matching makes it `KotlinInvalidOperationException` unless it has its own row. Recommendation: own row mapping to `KotlinOperationCanceledException : OperationCanceledException` (mirrors ADR-153/161's `OperationCanceledException <-> CancellationException`). Alternative: a row that forces `null` (stays `KotlinException`, the smallest behaviour change).
3. **`NullPointerException` target.** Recommendation: `KotlinNullReferenceException : NullReferenceException` (IKVM precedent, closest meaning). Alternative: `KotlinInvalidOperationException`, honouring the .NET guideline that public APIs never throw `NullReferenceException`.
4. **Message.** Recommendation: null message becomes the Kotlin FQN; non-null messages verbatim; `ToString()` names the Kotlin type. Alternative: prefix `"<fqn>: "` into every unmapped `KotlinException.Message` (what ROADMAP.md:67 literally asks for; breaks finding 10).
5. **Runtime ABI anchor.** Recommendation: no `NugetRuntimeAbi2` bump; the required `buildError` parameter already makes a new generator fail to compile against an old runtime. Alternative: bump anyway for a clearer error.
6. **Breaking change acceptance.** Every Kotlin exception that is a subclass of a row (including `CancellationException` family, `IOException` family, NPE) stops being catchable as `KotlinException`. Consumers who wanted "any Kotlin exception" must use `catch (Exception e) when (e is IKotlinException)`. Pre-1.0, recommendation: accept, call it out in release notes.

# Map exported Kotlin exception classes to generated C# exception types (issue #612)

- ROADMAP: no ROADMAP line as of 2026-11-09; the item is GitHub issue #612, "Map exported Kotlin exception classes to generated C# exception types".
- Researched: 2026-11-09, about 9 of 20 minutes, two .NET spikes run, no Kotlin/Native spike.
- Restatement: Kotlin to C# (forward). The Kotlin library declares the exception hierarchy; the C# consumer catches it. Each exported `Throwable` subclass gets a C# exception type mirroring the Kotlin hierarchy, so both `catch (StoreException.Unauthorized)` and `catch (StoreException)` fire. This holds on sync, suspend, Flow and runtime-owned routes. An unexported subclass is caught as its nearest exported ancestor. `KotlinType`, `KotlinStackTrace` and the cause chain are unchanged.
- Verdict: fix. ADR-241 drafted (Proposed), `docs/adr/241-exported-kotlin-exception-types.md`.

## Findings

1. An exported `class LookupError : Exception()` binds as a plain handle class (ADR-107 decision 3). Open, abstract and final shapes are pinned in `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1ThrowablePositionsTest.kt:32-59`. **Verified** by reading.
2. A root handle class has no base class and lists `IDisposable, INugetHandle`; a derived one lists its superclass first: `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassRenderer.kt:252-258`, `_handle` at `:277-285`, handle constructors at `:308-329`. **Verified** by reading.
3. `NugetError` and `buildError` live at `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt:615-656`. `KotlinType` is `t::class.qualifiedName` (`:639`). The stdlib rows are at `:694-708`, the ADR-202 installed module classifier at `:715-737`, and `nuget_error_cause_mapped_type` at `:862-874`. **Verified** by reading.
4. Every generated forward site passes `::nugetMappedType`, including `launchForCSharp`/`collectForCSharp`. Runtime-owned routes use the classifier installed by `INSTALL_MODULE_MAPPED_TYPE` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/exports/ExceptionMappingExports.kt:20-31`; the function is at `:33-60`, emitted from `NugetProcessor.kt:2340` and `:3424`). So changing what `nugetMappedType` returns changes every forward route, runtime-side ones included (ADR-177 constraint 2). **Verified** by reading and grep.
5. The only forward caller of `KotlinException.CreateMapped` is the per-library `NugetErrorNative.BuildException`: `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirErrorRenderer.kt:197-238`, with cause loop `:203-211` and top node `:212-237`. Every C# route reaches it: `CirTranslator.kt:1903-2024`, `CirClassTranslator.kt:2128,2248,2296,4548,4587,5911,6231`, `CirFlowRenderer.kt:222,924`, `CirMarshalRenderer.kt:351`, `CirBridgeRenderer.kt:214,349`. **Verified** by grep.
6. The other `CreateMapped` caller is the reverse shim (`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateShimsTask.kt:3106`). Its Kotlin half classifies with the module's `::nugetMappedType` (`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateBindingsTask.kt:5263-5275`). If `nugetMappedType` returns library keys, the reverse envelope must switch to a row-only twin; otherwise an exported `IllegalStateException` subclass silently drops from `KotlinInvalidOperationException` to `KotlinException` there. **Verified** by reading; the regression is inferred.
7. `KotlinException`'s only ctor is `internal` (`Kotlin.Native.Interop/KotlinException.cs:16`), and the mapped types are `public sealed` (`Kotlin.Native.Interop/KotlinInvalidOperationException.cs:3`). **Verified** by reading. A subclass in another assembly fails with `CS1729` (**verified**, spike S1 in ADR-241).
8. A protected-ctor contract base, a snapshot-chaining ctor, `IDisposable`, nested sealed subclasses, and an unsealed `KotlinInvalidOperationException` base give the catch behaviour the issue asks for (**verified**, spike S2, .NET 10.0.14; output in ADR-241).
9. Precedent for a Kotlin-side per-library type id resolved into a C# `new` switch is the sealed-interface discriminator, `CirClassRenderer.kt:106-127`. **Verified** by reading.
10. `NugetKotlinHandle` is a `SafeHandle` (`CirRenderer.kt:79`). **Verified** by reading. That a thrown and never-disposed exception releases its handle at finalization is **inferred**.
11. ADR-177 already maps an exported subclass of a row (`FinalError : IllegalStateException` is a `KotlinInvalidOperationException` today). A generated type rooted only at `KotlinException` would regress `catch (InvalidOperationException)`. **Inferred** from ADR-177 spike S2 plus reading `CreateMapped`.
12. Prior art: ObjC export and Kotlin/JS both keep one type per Kotlin exception class. The handle class is the exception. **Inferred** from the Kotlin docs, not re-fetched this session.
13. `nugetError.throwable` is not kept today: `NugetError` has no reference to the source throwable (`NugetRuntime.kt:615-625`). **Verified**. A thrown instance with members therefore needs a new export.

## Recommendation

ADR-241 Alternative 1, unify:

- The exported class's existing C# handle type becomes the exception. Its root derives from `KotlinException`, or from the matched row's unsealed `Kotlin*` type when the class IS-A a row.
- The per-module `nugetMappedType` gains `is` arms for this module's exported `Throwable` classes, deepest first, ahead of the optional and stdlib rows. The reverse envelope gets a row-only `nugetRowType`.
- `BuildException` (top node and causes) tries a generated `MapExported` key switch first, then falls back to `CreateMapped`. Each arm wraps a handle minted by one new runtime export, `nuget_error_cause_throwable`.
- Value positions build the same type through a snapshot read from the borrowed handle.

Priced at about 14 files in production code, plus tests, fixture and docs (listed below). No runtime Kotlin signature change. No change at the ~30 `buildError` sites.

Alternatives rejected:
- A second data-only exception type: two types per class, an invented name, and properties later need a handle anyway.
- Replacing the handle binding: drops members and inputs, breaking ADR-107 decision 3.
- A separate `exportedType` wire field: about 15 emitter files and a runtime signature change for statically derivable information. This is the fallback if Q3 says the wire must stay row-only.
- An int type id: equivalent, less readable in traces.
- An exact `KotlinType` switch or a constants class: forbidden by the issue.

## Files an implementation touches

- `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt`: `NugetError.throwable` and the `nuget_error_cause_throwable` export.
- A runtime nativeTest beside `RuntimeMappedTypeTest.kt`.
- `nuget-processor/.../ForwardAbiContract.kt:648` (`NUGET_RUNTIME_EXPORTS`).
- `nuget-processor/.../exports/ExceptionMappingExports.kt`: exported arms and `nugetRowType`.
- `nuget-processor/.../NugetProcessor.kt:2340,3424`: pass the exported `Throwable` classes, depth-ordered.
- `nuget-processor/.../cir/CirErrorRenderer.kt`: `MapExported`, the `CauseThrowable` DllImport, `NugetErrorSnapshot`, `Snapshot(handle)`.
- `nuget-processor/.../cir/CirClassRenderer.kt`: exception base, snapshot ctors, `ToString` suppression, `Exception`-member skip.
- `nuget-processor/.../cir/CirSealedRenderer.kt` and the sealed-base header at `CirClassTranslator.kt:3503`: the same changes on the sealed route.
- A CIR model flag (`CirClass.exceptionBase: String?`) set in the translator from KSP supertypes and `KOTLIN_EXCEPTION_TYPES`.
- `nuget-processor/.../forward/ForwardKdoc.kt`: the `@throws` cref names the generated type.
- `Kotlin.Native.Interop/KotlinException.cs` plus the eleven mapped `Kotlin*.cs` files: protected ctor, unsealed. `ContractTests/ContractTests.cs` gets a subclassing check.
- `nuget-plugin/.../NugetGenerateBindingsTask.kt:5263-5275`: switch to `::nugetRowType`. Plus `NugetKotlinBridgeGenerationTest.kt`.
- Tier 1:
  - update `Tier1ThrowablePositionsTest.kt:32-59`;
  - new `Tier1ExportedExceptionTypesTest.kt` (open, abstract, final, sealed, nested, row-derived base, member collision, `object` arm);
  - extend `Tier1ExceptionHierarchyMappingTest.kt` to pin that `nugetRowType` keeps the row keys.
- Fixture: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/store/StoreSample.kt`, in a new package to avoid ADR-163 collisions.
- `IntegrationTests/ExportedExceptionTypeTests.cs` and a `LeakTests/` row.
- Docs: a topic page on exceptions, `docs/topics/supported-features.md`, the ADR-107 and ADR-201 amendment pointers, ROADMAP deferral lines.

## Sample test

Fixture:

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.store

sealed class StoreException(message: String) : Exception(message) {
  class Unauthorized : StoreException("not signed in")
  data class NotFound(val key: String) : StoreException("no $key")
}
class StaleCache(message: String) : IllegalStateException(message)
private class TokenExpired : StoreException("token expired")   // unexported subclass

class Store {
  fun fetch(key: String): String = throw StoreException.NotFound(key)
  fun signIn(): Unit = throw TokenExpired()
  fun stale(): Unit = throw StaleCache("cache is stale")
  suspend fun fetchAsync(key: String): String = throw StoreException.Unauthorized()
  fun watch(): Flow<String> = flow { throw StoreException.NotFound("feed") }
}
```

xunit:

```csharp
public class ExportedExceptionTypeTests
{
    [Fact]
    public void Sync_ThrowsMostDerivedExportedType_WithMembers()
    {
        using var store = new Store();
        var e = Assert.Throws<StoreException.NotFound>(() => store.Fetch("oreo"));
        Assert.Equal("oreo", e.Key);
        Assert.Equal("io.github.xxfast.kotlin.native.nuget.test.store.StoreException.NotFound", e.KotlinType);
        Assert.IsAssignableFrom<StoreException>(e);
        Assert.IsAssignableFrom<KotlinException>(e);
    }

    [Fact]
    public void UnexportedSubclass_IsCaughtAsNearestExportedAncestor()
    {
        using var store = new Store();
        var e = Assert.Throws<StoreException>(() => store.SignIn());   // exact type: the abstract backing
        Assert.EndsWith(".TokenExpired", e.KotlinType);
    }

    [Fact]
    public void RowDerivedExportedType_StillAnInvalidOperationException()
    {
        using var store = new Store();
        Assert.IsAssignableFrom<InvalidOperationException>(Assert.Throws<StaleCache>(() => store.Stale()));
    }

    [Fact]
    public async Task Suspend_ThrowsExportedType()
    {
        using var store = new Store();
        await Assert.ThrowsAsync<StoreException.Unauthorized>(() => store.FetchAsync("x"));
    }

    [Fact]
    public async Task Flow_ThrowsExportedType()
    {
        using var store = new Store();
        await Assert.ThrowsAsync<StoreException.NotFound>(async () =>
        {
            await foreach (var _ in store.Watch()) { }
        });
    }
}
```

Leak row (`LeakTests/LiveHandleTests.cs`): `ExportedException_ThrownAndDropped_ReturnsToBaseline`.
- Throw and catch `StoreException.NotFound` 100 times without disposing, read `.Key` each time, drop the references, then collect and wait for pending finalizers.
- Assert that `NugetMarshal.LiveHandles` returns to baseline.
- Add a `CollectabilityTests.cs` twin on the async route, with the `await Task.Yield()` that CLAUDE.md requires under NativeAOT.
- Add an explicit-`Dispose()` variant as a second row.

## Deferred scope

Each of these needs a ROADMAP line:

- The reverse envelope mapping to generated types: a Kotlin-implemented C# slot that throws an exported exception still maps by row only.
- Constructing and throwing a generated exception type from C# into Kotlin. A `Throwable` parameter still receives a `NugetManagedException` (ADR-201), and the generated type's public ctor stays whatever the Kotlin constructor binds.
- An exception class exported by a dependency library being mapped in the consuming library's bindings.
- Renaming, rather than skipping, a member whose C# name collides with an `Exception` member.

## Open what-questions

- Q1. Base of a root exported class that IS-A a mapped row (`StaleCache : IllegalStateException`). Recommendation: the row's `Kotlin*` type, unsealed. This keeps ADR-177's `catch (InvalidOperationException)` and deviates from the issue's literal "deriving from `KotlinException`". `catch (Exception e) when (e is IKotlinException)` still catches everything. Human decision: pending.
- Q2. Unify or a second type (the issue's named decision). Recommendation: unify. One type, no name collision, and members bind on a thrown instance, so `NotFound.Key` costs nothing later. The price is a handle per thrown exported exception, covered by the leak row. Human decision: pending.
- Q3. Fold the exported key into `mappedType`, or a separate `exportedType` wire field. Recommendation: fold. No runtime signature change, and the reverse envelope gets a row-only twin. Human decision: pending.
- Q4. Members whose C# name collides with an `Exception` member (`Data`, `Source`, `HelpLink`, `HResult`, `StackTrace`, `TargetSite`). Recommendation: a named skip in v1; renaming is deferred. Human decision: pending.
- Q5. A data-class exception's `ToString`. Recommendation: keep `KotlinException.ToString` (it carries the Kotlin trace) and drop the data-class override. `Equals` and `GetHashCode` stay. Human decision: pending.
- Q6. An unexported subclass of an abstract or sealed exported base needs a concrete C# instance. Recommendation: reuse or emit a private concrete backing of the abstract class. The implementing agent must confirm the class and sealed renderers provide one (ADR-216's `backingName` is for generics; inferred). Human decision: pending.

### Decided at the gate (2026-11-09, human)

- Q2: unify. The exported handle class is the C# exception type, so its members still bind.
- Q1: the C# base is the matched mapped `Kotlin*` type when the Kotlin class extends a mapped stdlib exception, otherwise `KotlinException`.
- Q3 to Q6: the memo's recommendations stand (fold into `mappedType`, named skip for members colliding with `Exception` members, keep `KotlinException.ToString`, private concrete backing for abstract and sealed bases).

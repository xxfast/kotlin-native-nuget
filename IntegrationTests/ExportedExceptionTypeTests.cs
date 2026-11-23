using TestLibrary.Store;

namespace IntegrationTests;

/// <summary>
/// ADR-241: an exported Kotlin exception class binds as a C# exception type. The handle class IS
/// the exception (unified), so it is caught by its own generated type, by its exported base, and as
/// a <see cref="KotlinException"/> (or, for a subclass of a mapped stdlib row like <c>StaleCache</c>,
/// as that row's <c>Kotlin*Exception</c> and its BCL base), and its members (<c>NotFound.Key</c>)
/// still read through the live handle. <c>KotlinType</c> and <c>KotlinStackTrace</c> are unchanged.
/// Every error-building route maps, and an exported arm read at a value or erased position is the
/// same generated type.
///
/// Oreo raids the treat cupboard at 3am; Mylo left the door open and gets the blame.
/// </summary>
public class ExportedExceptionTypeTests
{
    private const string Package = "io.github.xxfast.kotlin.native.nuget.test.store";

    // --- sync member route ---

    [Fact]
    public void Oreo_FetchesMissingTreat_ThrowsMostDerivedExportedType_WithMembers()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<StoreException.NotFound>(() => store.Fetch("oreo-biscuit"));
        Assert.Equal("oreo-biscuit", e.Key);
        Assert.Equal("no oreo-biscuit in the cupboard", e.Message);
        Assert.Equal($"{Package}.StoreException.NotFound", e.KotlinType);
        Assert.IsAssignableFrom<StoreException>(e);
        Assert.IsAssignableFrom<KotlinException>(e);
        Assert.IsAssignableFrom<IKotlinException>(e);
    }

    [Fact]
    public void Oreo_FetchesMissingTreat_IsCaughtByExportedBase()
    {
        using var store = new TreatStore();
        string caught;
        try
        {
            store.Fetch("milo-malt");
            caught = "nothing";
        }
        catch (StoreException.Unauthorized)
        {
            caught = "unauthorized";
        }
        catch (StoreException e)
        {
            caught = e.GetType().Name;
        }
        Assert.Equal("NotFound", caught);
    }

    [Fact]
    public void Oreo_FetchesMissingTreat_KotlinStackTraceUnchanged()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<StoreException.NotFound>(() => store.Fetch("tuna"));
        Assert.False(string.IsNullOrEmpty(e.KotlinStackTrace));
        Assert.Contains("NotFound", e.KotlinStackTrace);
        // ADR-241 Q5: KotlinException.ToString wins over the data-class toString.
        Assert.Contains(e.KotlinStackTrace, e.ToString());
    }

    [Fact]
    public void Mylo_SignsInWithExpiredToken_UnexportedSubclass_IsCaughtAsNearestExportedAncestor_NotTheRoot()
    {
        using var store = new TreatStore();
        // Exact type: the generated private concrete backing of the abstract arm, so ThrowsAny.
        var e = Assert.ThrowsAny<StoreException.Locked>(() => store.SignIn());
        Assert.IsNotType<StoreException.NotFound>(e);
        Assert.IsNotType<StoreException.Unauthorized>(e);
        Assert.EndsWith(".TokenExpired", e.KotlinType);
        Assert.Equal("Mylo's paw token expired", e.Message);
        Assert.IsAssignableFrom<KotlinException>(e);
    }

    [Fact]
    public void Mylo_ReadsStaleKibbleCache_RowDerivedExportedType_IsStillAnInvalidOperationException()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<StaleCache>(() => store.Stale());
        Assert.IsAssignableFrom<InvalidOperationException>(e);
        Assert.IsAssignableFrom<KotlinInvalidOperationException>(e);
        // Row-derived: its base is KotlinInvalidOperationException, so it is not a KotlinException.
        Assert.IsAssignableFrom<IKotlinException>(e);
        Assert.Equal($"{Package}.StaleCache", e.KotlinType);
        Assert.Equal("the kibble cache is stale", e.Message);
    }

    [Fact]
    public void Mylo_ReadsStaleKibbleCache_CatchInvalidOperationException_StillFires()
    {
        using var store = new TreatStore();
        Exception? caught = null;
        try
        {
            store.Stale();
        }
        catch (InvalidOperationException e)
        {
            caught = e;
        }
        Assert.IsType<StaleCache>(caught);
    }

    // --- cause loop ---

    [Fact]
    public void Oreo_CollapsesTheShelf_CauseIsTheExportedType()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<KotlinInvalidOperationException>(() => store.Collapse("salmon"));
        Assert.Equal("the shelf collapsed", e.Message);
        var inner = Assert.IsType<StoreException.NotFound>(e.InnerException);
        Assert.Equal("salmon", inner.Key);
        Assert.Equal($"{Package}.StoreException.NotFound", inner.KotlinType);
    }

    // --- static route ---

    [Fact]
    public void Oreo_OpensCupboard_StaticRoute_ThrowsUnauthorized()
    {
        var e = Assert.Throws<StoreException.Unauthorized>(() => StoreSample.OpenCupboard());
        Assert.Equal($"{Package}.StoreException.Unauthorized", e.KotlinType);
        Assert.Equal("Oreo is not signed in to the treat cupboard", e.Message);
    }

    // --- value position ---

    [Fact]
    public void Mylo_RemembersLastMiss_ValuePosition_IsTheArmWithMessageAndKey()
    {
        using var store = new TreatStore();
        using StoreException miss = store.LastMiss();
        var notFound = Assert.IsType<StoreException.NotFound>(miss);
        Assert.Equal("tuna", notFound.Key);
        Assert.Equal("no tuna in the cupboard", notFound.Message);
        Assert.Equal($"{Package}.StoreException.NotFound", notFound.KotlinType);
    }

    // --- suspend route ---

    [Fact]
    public async Task Oreo_FetchesAfterANap_Suspend_ThrowsExportedType()
    {
        using var store = new TreatStore();
        var e = await Assert.ThrowsAsync<StoreException.Unauthorized>(() => store.FetchLaterAsync("kibble"));
        Assert.Equal($"{Package}.StoreException.Unauthorized", e.KotlinType);
    }

    [Fact]
    public async Task Mylo_FindsNothing_SuspendWithoutSuspending_ThrowsArmWithKey()
    {
        using var store = new TreatStore();
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(() => store.FindAsync("catnip"));
        Assert.Equal("catnip", e.Key);
    }

    // --- Flow route ---

    [Fact]
    public async Task Oreo_WatchesTheFeed_Flow_ThrowsExportedTypeAfterEmission()
    {
        using var store = new TreatStore();
        var seen = new List<string>();
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(async () =>
        {
            await foreach (string treat in store.Watch()) seen.Add(treat);
        });
        Assert.Equal(["salmon"], seen);
        Assert.Equal("feed", e.Key);
        Assert.IsAssignableFrom<StoreException>(e);
    }

    // --- C#-constructed instances (risk 9a) ---

    [Fact]
    public void Oreo_BuildsHisOwnNotFound_CarriesTheKotlinMessageTypeAndKey()
    {
        using var miss = new StoreException.NotFound("tuna");
        Assert.Equal("no tuna in the cupboard", miss.Message);
        Assert.EndsWith(".StoreException.NotFound", miss.KotlinType);
        Assert.Equal("tuna", miss.Key);
    }

    [Fact]
    public void Mylo_BuildsHisOwnStaleCache_CarriesTheGivenMessageAndIsAnInvalidOperationException()
    {
        using var stale = new StaleCache("the kibble cache is stale");
        Assert.Equal("the kibble cache is stale", stale.Message);
        Assert.EndsWith(".StaleCache", stale.KotlinType);
        Assert.IsAssignableFrom<InvalidOperationException>(stale);
    }

    [Fact]
    public void Oreo_BuildsAParameterlessUnauthorized_CarriesTheKotlinMessageAndType()
    {
        using var unauthorized = new StoreException.Unauthorized();
        Assert.Equal("Oreo is not signed in to the treat cupboard", unauthorized.Message);
        Assert.EndsWith(".StoreException.Unauthorized", unauthorized.KotlinType);
    }

    // --- one test per remaining error route (C) ---

    [Fact]
    public void Mylo_RaidsTheStore_ExtensionRoute_ThrowsNotFound()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<StoreException.NotFound>(() => store.Raid("salmon"));
        Assert.Equal("salmon", e.Key);
    }

    [Fact]
    public void Oreo_OpensATreatBin_ConstructorRoute_ThrowsNotFound()
    {
        var e = Assert.Throws<StoreException.NotFound>(() => new TreatBin("kibble"));
        Assert.Equal("kibble", e.Key);
    }

    [Fact]
    public void Mylo_ReadsTheTopShelf_PropertyGetterRoute_ThrowsNotFound()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<StoreException.NotFound>(() => store.TopShelf);
        Assert.Equal("top-shelf", e.Key);
    }

    [Fact]
    public void Oreo_RelabelsTheJar_PropertySetterRoute_ThrowsNotFound()
    {
        using var store = new TreatStore();
        Assert.Equal("treats", store.Label);
        var e = Assert.Throws<StoreException.NotFound>(() => store.Label = "catnip");
        Assert.Equal("catnip", e.Key);
    }

    [Fact]
    public void Mylo_AsksToRestock_CompanionRoute_ThrowsNotFound()
    {
        var e = Assert.Throws<StoreException.NotFound>(() => TreatStore.Restock("tuna"));
        Assert.Equal("tuna", e.Key);
    }

    [Fact]
    public void Oreo_AsksTheGatekeeper_ObjectRoute_ThrowsNotFound()
    {
        var e = Assert.Throws<StoreException.NotFound>(() => Gatekeeper.LetIn("milo-malt"));
        Assert.Equal("milo-malt", e.Key);
    }

    [Fact]
    public void Mylo_RetriesTheMiss_SealedArmMemberRoute_ThrowsNotFound()
    {
        using var store = new TreatStore();
        using StoreException miss = store.LastMiss();
        var notFound = Assert.IsType<StoreException.NotFound>(miss);
        var e = Assert.Throws<StoreException.NotFound>(() => notFound.Retry());
        Assert.Equal("tuna", e.Key);
    }

    [Fact]
    public void Oreo_UnscrewsTheJar_GenericOwnerRoute_ThrowsNotFound()
    {
        using var jar = new Jar<string>("salmon");
        var e = Assert.Throws<StoreException.NotFound>(() => jar.Unscrew());
        Assert.Equal("jar lid", e.Key);
    }

    [Fact]
    public void Mylo_PeeksBehindTheCupboard_InterfaceMemberOnAKotlinObject_ThrowsNotFound()
    {
        using var store = new TreatStore();
        using IHidey hidey = store.BackOfTheCupboard();
        var e = Assert.Throws<StoreException.NotFound>(() => hidey.Peek("catnip"));
        Assert.Equal("catnip", e.Key);
    }

    [Fact]
    public void Oreo_FetchesOrFails_ResultFailureRoute_ThrowsAndTryReturnsNotFound()
    {
        using var store = new TreatStore();
        var thrown = Assert.Throws<StoreException.NotFound>(() => store.FetchOrFail("tuna"));
        Assert.Equal("tuna", thrown.Key);
        Assert.False(store.TryFetchOrFail("salmon", out _, out Exception? failure));
        var notFound = Assert.IsType<StoreException.NotFound>(failure);
        Assert.Equal("salmon", notFound.Key);
    }

    [Fact]
    public async Task Mylo_OpensTheCupboardLater_TopLevelSuspendRoute_ThrowsNotFound()
    {
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(() => StoreSample.OpenCupboardLaterAsync("tuna"));
        Assert.Equal("tuna", e.Key);
    }

    [Fact]
    public async Task Oreo_WatchesTheFeedProperty_FlowPropertyRoute_ThrowsAfterEmission()
    {
        using var store = new TreatStore();
        var seen = new List<string>();
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(async () =>
        {
            await foreach (string treat in store.Feed) seen.Add(treat);
        });
        Assert.Equal(["salmon"], seen);
        Assert.Equal("feed", e.Key);
    }

    [Fact]
    public async Task Mylo_WatchesTheBowlProperty_StateFlowPropertyRoute_ThrowsNotFound()
    {
        // The property's own generated `get_bowl_collect` export (collectForCSharp with
        // `::nugetMappedType`), not the runtime's `nuget_stateflow_collect`.
        using var store = new TreatStore();
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(async () =>
        {
            await foreach (string _ in store.Bowl) { }
        });
        Assert.Equal("bowl", e.Key);
    }

    // --- runtime-owned routes ---
    // These throw inside `nuget-runtime` (`nuget_suspend_func1_invoke`, `nuget_stateflow_collect`),
    // which classifies with whatever `nugetInstallMappedType` installed. The install is
    // process-global and idempotent, so any earlier test that crossed an install site masks a
    // missing one: these prove the C# half and the classifier, not that THIS mint site installs.

    [Fact]
    public async Task Oreo_UsesTheFetcherProperty_SuspendLambdaPropertyRoute_ThrowsNotFound()
    {
        using var store = new TreatStore();
        Func<string, CancellationToken, Task<string>> fetcher = store.Fetcher;
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(() => fetcher("tuna", CancellationToken.None));
        Assert.Equal("tuna", e.Key);
    }

    [Fact]
    public async Task Mylo_UsesAReturnedFetcher_ReturnedSuspendLambdaRoute_ThrowsNotFound()
    {
        // The returned-lambda export (`treatstore_fetcherLater`) is not an ADR-202 install site
        // today, so this is green only when another test installed first: order-dependent until
        // the install lands there (or this test is cut).
        using var store = new TreatStore();
        Func<string, CancellationToken, Task<string>> fetcher = store.FetcherLater();
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(() => fetcher("salmon", CancellationToken.None));
        Assert.Equal("salmon", e.Key);
    }

    [Fact]
    public async Task Mylo_TastesASample_SuspendLambdaHandedToACSharpSlot_ThrowsNotFound()
    {
        // `StoreSample.TastingAsync` hands the C# slot a Kotlin suspend lambda as an argument; the
        // bridge factory that carries it is not an ADR-202 install site today, so this is green only
        // when another test installed first.
        var taster = new CuriousTaster();
        Assert.Equal("spat out", await StoreSample.TastingAsync(taster));
        var notFound = Assert.IsType<StoreException.NotFound>(taster.Seen);
        Assert.Equal("catnip", notFound.Key);
    }

    private sealed class CuriousTaster : ITaster
    {
        public Exception? Seen;

        public async Task<string> TasteAsync(
            Func<string, CancellationToken, Task<string>> sample, CancellationToken cancellationToken = default)
        {
            try
            {
                return await sample("catnip", cancellationToken);
            }
            catch (Exception e)
            {
                Seen = e;
                return "spat out";
            }
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Oreo_WaitsForTheBowl_SuspendStateFlowRoute_CollectThrowsNotFound()
    {
        using var store = new TreatStore();
        using var bowl = await store.BowlLaterAsync();
        var e = await Assert.ThrowsAsync<StoreException.NotFound>(async () =>
        {
            await foreach (string _ in bowl) { }
        });
        Assert.Equal("bowl-later", e.Key);
    }

    // --- hierarchy cells (D) ---

    [Fact]
    public void Mylo_RummagesAnEmptyCupboard_DataObjectArm_IsItsOwnTypeAndCaughtByTheRoot()
    {
        // A sealed `data object` arm binds as its own nested sealed class (`Gait.Rest`, `Chore.Rest`).
        using var store = new TreatStore();
        StoreException? caught = null;
        try
        {
            store.Rummage();
        }
        catch (StoreException e)
        {
            caught = e;
        }
        var empty = Assert.IsType<StoreException.Empty>(caught);
        Assert.EndsWith(".StoreException.Empty", empty.KotlinType);
        Assert.Equal("the cupboard is bare", empty.Message);
    }

    [Fact]
    public void Oreo_SpillsTheBag_ExportedCauseUnderAnExportedException()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<Spillage>(() => store.Spill("salmon"));
        Assert.Equal("Oreo knocked the bag over", e.Message);
        Assert.EndsWith(".Spillage", e.KotlinType);
        var inner = Assert.IsType<StoreException.NotFound>(e.InnerException);
        Assert.Equal("salmon", inner.Key);
        Assert.Equal($"{Package}.StoreException.NotFound", inner.KotlinType);
    }

    [Fact]
    public void Mylo_SniffsAMouldyBowl_UnexportedSubclassOfAnOpenRoot_IsTheRootsExactType()
    {
        using var store = new TreatStore();
        var e = Assert.Throws<Spoiled>(() => store.SniffBowl());
        Assert.EndsWith(".MouldyKibble", e.KotlinType);
        Assert.Equal("the kibble went mouldy", e.Message);
        Assert.IsAssignableFrom<KotlinException>(e);
    }

    // --- value and erased positions (risk 9b) ---
    // `Jar<T>.Item` is read by `NugetMarshal.FromHandle<T>`. Its exception branch
    // (CirMarshalRenderer.kt:346-352) reads the box's envelope and calls
    // `NugetErrorNative.BuildException`, so an exported arm reaches `MapExported`. That branch is
    // emitted only because the module's classifier `boxesThrowable` is on (CirTranslator.kt:1320).

    [Fact]
    public void Oreo_OpensTheMissJar_ErasedPosition_IsTheArmWithKeyAndKotlinIdentity()
    {
        using var store = new TreatStore();
        using Jar<StoreException> jar = store.MissJar();
        using StoreException item = jar.Item;
        var notFound = Assert.IsType<StoreException.NotFound>(item);
        Assert.Equal("tuna", notFound.Key);
        Assert.True(store.IsTheMiss(notFound));
    }

    [Fact]
    public async Task Mylo_SniffsTheStaleKibbleLater_ErasedPosition_StdlibThrowableStillMapsByRow()
    {
        using var store = new TreatStore();
        Func<string, CancellationToken, Task<Exception>> later = store.StaleLater();
        var stale = Assert.IsType<KotlinInvalidOperationException>(await later("kibble", CancellationToken.None));
        Assert.Equal("the kibble went stale", stale.Message);
    }

    [Fact]
    public void Oreo_OpensTheKibbleJar_ErasedPosition_NonExceptionIsUnaffected()
    {
        using var store = new TreatStore();
        using Jar<string> jar = store.KibbleJar();
        Assert.Equal("salmon", jar.Item);
    }

    [Fact]
    public void Mylo_HoldsTheMissAsThrowable_TypedThrowableReturn_IsTheArmWithKotlinIdentity()
    {
        // ForwardCirPlanProjection.kt:1831: an unthrown `BuildException(nativeResult)`.
        using var store = new TreatStore();
        var notFound = Assert.IsType<StoreException.NotFound>(store.MissAsThrowable());
        using (notFound)
        {
            Assert.Equal("tuna", notFound.Key);
            Assert.True(store.IsTheMiss(notFound));
        }
    }

    [Fact]
    public void Oreo_SniffsTheMiss_CallbackThrowableArgument_IsTheArmWithKotlinIdentity()
    {
        // ForwardCirCallbackProjection.kt:296: the callback argument is read with `BuildException`.
        // Captured and asserted after the call, so a failed assert cannot ride the managed-fault path.
        using var store = new TreatStore();
        Exception? seen = null;
        Assert.Equal("sniffed", store.Sniff(miss =>
        {
            seen = miss;
            return "sniffed";
        }));
        var notFound = Assert.IsType<StoreException.NotFound>(seen);
        using (notFound)
        {
            Assert.Equal("tuna", notFound.Key);
            Assert.True(store.IsTheMiss(notFound));
        }
    }

    [Fact]
    public void Mylo_EchoesTheMiss_ErasedInputAndBack_KeepsKotlinIdentity()
    {
        // The write half: `NugetMarshal.Wrap<T>` tests `value is Exception` (CirMarshalRenderer.kt:600)
        // before `value is INugetHandle` (:644), so today the exported arm crosses as its managed
        // text and comes back as a different Kotlin object.
        using var store = new TreatStore();
        using Jar<StoreException> jar = store.MissJar();
        using StoreException miss = jar.Item;
        using StoreException back = store.Echo(miss);
        var notFound = Assert.IsType<StoreException.NotFound>(back);
        Assert.Equal("tuna", notFound.Key);
        Assert.True(store.IsTheMiss(notFound));
    }

    [Fact]
    public void Oreo_EchoesATreat_ErasedInputAndBack_NonExceptionIsUnaffected()
    {
        using var store = new TreatStore();
        Assert.Equal("salmon", store.Echo("salmon"));
        Assert.Equal(9, store.Echo(9));
    }
}

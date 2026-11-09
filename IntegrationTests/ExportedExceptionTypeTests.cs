using TestLibrary.Store;

namespace IntegrationTests;

/// <summary>
/// ADR-241: an exported Kotlin exception class binds as a C# exception type. The handle class IS
/// the exception (unified), so it is caught by its own generated type, by its exported base, and as
/// a <see cref="KotlinException"/>, and its members (<c>NotFound.Key</c>) still read through the
/// live handle. <c>KotlinType</c> and <c>KotlinStackTrace</c> are unchanged.
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
        Assert.IsAssignableFrom<KotlinException>(e);
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
        var e = await Assert.ThrowsAsync<StoreException.Unauthorized>(() => store.FetchAsync("kibble"));
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
}

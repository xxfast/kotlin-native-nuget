using System.Reflection;
using System.Runtime.InteropServices;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-118 / ROADMAP line 54: two <c>suspend</c> overloads on one exported class. The legacy suspend
/// route composes <c>${prefix}_${name}_async</c> off the <em>bare</em> method name at three sites
/// (<c>SuspendFunctionExports.kt</c>, and both C# projections — <c>asyncMembers</c> and
/// <c>suspendStateFlowMembers</c>), so a same-named pair collides on one C symbol exactly as the
/// Flow route did in #97. The fix is #97's: read
/// <c>ForwardCallablePlanCatalog.overloadSuffix(method)</c> on every one of them.
///
/// <para>
/// The C# surface stays one natural overload set (<c>FetchCatAsync</c>, <c>FeedAsync</c>,
/// <c>AwaitMoodReportAsync</c>) with no visible numbering; only the native symbol and the private
/// extern carry <c>_2</c>.
/// </para>
///
/// <para>
/// Every body answers something the other body cannot produce, because the failure mode here is
/// silent, not loud. <c>CirDllImport.name</c> and <c>CirMethod.nativeName</c> are separate fields:
/// if the suffix lands on the import's EntryPoint but not on <c>nativeName</c>, the second
/// overload's body calls <c>Native_FeedAsync</c>, resolves it by argument type to the <em>first</em>
/// overload's extern, compiles, runs, and returns the first overload's result. Presence assertions
/// would pass; value assertions do not.
/// </para>
///
/// <para>
/// Oreo counts his portions, Mylo insists on being called by name, and both of them are still
/// sleepy.
/// </para>
/// </summary>
public class SuspendMethodOverloadTests
{
    // ---- (a) The arity-distinct pair: C# resolves the public call by arity, the native symbol
    // ---- must still be numbered or the Kotlin round fails on a duplicate @CName.

    [Fact]
    public async Task FetchCatAsync_FirstSuspendOverload_AnswersWithTheDefaultLives()
    {
        using var service = new AsyncCatService("toys");

        using Cat oreo = await service.FetchCatAsync("Oreo");

        Assert.Equal("Oreo", oreo.Name);
        Assert.Equal(9, oreo.Lives);
    }

    /// <summary>
    /// The second overload sets <c>lives</c> explicitly, which the one-parameter body can never do
    /// (it takes Kotlin's default 9). <c>Lives</c> is therefore the tell that the call reached the
    /// two-parameter Kotlin body.
    /// </summary>
    [Fact]
    public async Task FetchCatAsync_SecondSuspendOverload_DispatchesToTheTwoParameterBody()
    {
        using var service = new AsyncCatService("toys");

        using Cat mylo = await service.FetchCatAsync("Mylo", 3);

        Assert.Equal("Mylo", mylo.Name);
        Assert.Equal(3, mylo.Lives);
    }

    // ---- (b) The same-arity, same-wire-shape pair: this is the cell that pins the suffix onto
    // ---- `CirMethod.nativeName` and not only onto the [DllImport] EntryPoint. Both parameters
    // ---- cross as one IntPtr to a boxed wire container, so both native call sites have the
    // ---- identical argument types and a missing `nativeName` suffix binds silently.

    [Fact]
    public async Task FeedAsync_ListOverload_ServesByCount()
    {
        using var sitter = new AsyncCatSitter("The human");

        Assert.Equal(
            "The human served 5 portions to Oreo",
            await sitter.FeedAsync(new[] { 2, 3 }));
    }

    [Fact]
    public async Task FeedAsync_SetOverload_CallsByNameAndNotByCount()
    {
        using var sitter = new AsyncCatSitter("The human");

        // If the `_2` suffix missed `nativeName`, this call reaches the List body instead and
        // answers "...served N portions to Oreo" — same type, same shape, wrong Kotlin method.
        Assert.Equal(
            "The human called Mylo+Oreo to the bowl",
            await sitter.FeedAsync(new HashSet<string> { "Oreo", "Mylo" }));
    }

    [Fact]
    public async Task FeedAsync_BothSameArityOverloads_ShareOneReceiverAndStayDistinct()
    {
        using var sitter = new AsyncCatSitter("The human");

        Assert.Equal("The human served 7 portions to Oreo", await sitter.FeedAsync(new[] { 7 }));
        Assert.Equal(
            "The human called Oreo to the bowl",
            await sitter.FeedAsync(new HashSet<string> { "Oreo" }));
        Assert.Equal("The human served 1 portions to Oreo", await sitter.FeedAsync(new[] { 1 }));
    }

    // ---- (c) The StateFlow-returning pair: `suspendStateFlowMembers` is a separate flatMap with
    // ---- its own composition, over a Kotlin export half shared with `asyncMembers` (ADR-068), so
    // ---- it has to number along or this pair still collides on the C# extern name.

    [Fact]
    public async Task AwaitMoodReportAsync_FirstStateFlowOverload_HandsBackTheSharedMood()
    {
        using var tracker = new CatMoodTracker("Oreo");

        KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();

        Assert.Equal("sleepy", report.Value);
    }

    [Fact]
    public async Task AwaitMoodReportAsync_SecondStateFlowOverload_PrefixesTheCurrentMood()
    {
        using var tracker = new CatMoodTracker("Mylo");
        tracker.SetMood("grumpy");

        KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync("Mylo says ");

        Assert.Equal("Mylo says grumpy", report.Value);
    }

    [Fact]
    public async Task AwaitMoodReportAsync_BothStateFlowOverloads_StayDistinctFromOneTracker()
    {
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetMood("zoomy");

        KotlinStateFlow<string> shared = await tracker.AwaitMoodReportAsync();
        KotlinStateFlow<string> prefixed = await tracker.AwaitMoodReportAsync("Oreo is ");

        Assert.Equal("zoomy", shared.Value);
        Assert.Equal("Oreo is zoomy", prefixed.Value);
    }

    // ---- The surface: one overload set each, numbering confined to the native symbols. ----

    [Fact]
    public void SuspendOverloads_AreOneNaturalSetWithoutNumberedPublicNames()
    {
        Assert.NotNull(typeof(AsyncCatService).GetMethod(
            "FetchCatAsync", new[] { typeof(string), typeof(CancellationToken) }));
        Assert.NotNull(typeof(AsyncCatService).GetMethod(
            "FetchCatAsync", new[] { typeof(string), typeof(int), typeof(CancellationToken) }));
        Assert.NotNull(typeof(AsyncCatSitter).GetMethod(
            "FeedAsync", new[] { typeof(IReadOnlyList<int>), typeof(CancellationToken) }));
        Assert.NotNull(typeof(AsyncCatSitter).GetMethod(
            "FeedAsync", new[] { typeof(IReadOnlySet<string>), typeof(CancellationToken) }));
        Assert.NotNull(typeof(CatMoodTracker).GetMethod(
            "AwaitMoodReportAsync", new[] { typeof(CancellationToken) }));
        Assert.NotNull(typeof(CatMoodTracker).GetMethod(
            "AwaitMoodReportAsync", new[] { typeof(string), typeof(CancellationToken) }));

        foreach (Type type in new[] { typeof(AsyncCatService), typeof(AsyncCatSitter), typeof(CatMoodTracker) })
        {
            Assert.DoesNotContain(
                type.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly),
                method => method.Name.Contains("_2", StringComparison.Ordinal));
        }
    }

    /// <summary>
    /// The numbering itself, read off the generated <c>[DllImport]</c>s. The value assertions above
    /// prove the right Kotlin body ran; this one proves it ran through a distinctly-named symbol
    /// rather than through anything the linker happened to resolve.
    /// <para>
    /// The unnumbered entry points are the canary: pseudo-custom attributes are reconstructed by
    /// the runtime, so if that reconstruction came back empty this walk would pass vacuously.
    /// </para>
    /// </summary>
    [Fact]
    public void SuspendOverloads_TakeNumberedNativeEntryPoints()
    {
        string[] service = EntryPointsOf(typeof(AsyncCatService));
        string[] sitter = EntryPointsOf(typeof(AsyncCatSitter));
        string[] tracker = EntryPointsOf(typeof(CatMoodTracker));

        Assert.Contains("asynccatservice_fetchCat_async", service);
        Assert.Contains("asynccatservice_fetchCat_2_async", service);
        Assert.Contains("asynccatsitter_feed_async", sitter);
        Assert.Contains("asynccatsitter_feed_2_async", sitter);
        Assert.Contains("catmoodtracker_awaitMoodReport_async", tracker);
        Assert.Contains("catmoodtracker_awaitMoodReport_2_async", tracker);
    }

    /// <summary>Every <c>[DllImport]</c> EntryPoint declared on <paramref name="type"/>.</summary>
    private static string[] EntryPointsOf(Type type) => type
        .GetMethods(
            BindingFlags.Public | BindingFlags.NonPublic |
            BindingFlags.Static | BindingFlags.Instance | BindingFlags.DeclaredOnly)
        .Select(method => method.GetCustomAttribute<DllImportAttribute>()?.EntryPoint)
        .Where(entryPoint => entryPoint is not null)
        .Select(entryPoint => entryPoint!)
        .ToArray();
}

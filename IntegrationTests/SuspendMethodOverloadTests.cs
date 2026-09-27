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
/// Section (d) is ROADMAP line 29, the top-level twin: overloaded top-level <c>suspend fun</c>s in
/// <c>AsyncFunctions.kt</c>, which reach C# through <c>translateSuspendFunction</c> rather than the
/// class route and so need the same number read on their own three literals.
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

    // ---- (d) ROADMAP line 29: the TOP-LEVEL suspend route. `addSuspendFunctionExports` and
    // ---- `translateSuspendFunction` composed `${cname}_async` with no overload suffix, so each
    // ---- pair in `AsyncFunctions.kt` collided on one C symbol and the build failed with
    // ---- ERROR_C_ENTRY_POINT_COLLISION. Same cells as the class route (arity, same wire), plus
    // ---- the mixed ordinary/suspend namesake that the shared ADR-095 counter now numbers.

    [Fact]
    public async Task FetchTreatAsync_TopLevelFirstOverload_GivesOreoHisOneTreat()
    {
        Assert.Equal("one treat for Oreo", await AsyncFunctions.FetchTreatAsync());
    }

    [Fact]
    public async Task FetchTreatAsync_TopLevelSecondOverload_DispatchesToTheCountBody()
    {
        Assert.Equal("3 treats for Mylo", await AsyncFunctions.FetchTreatAsync(3));
    }

    [Fact]
    public async Task ServeTreatsAsync_TopLevelListOverload_ServesByCount()
    {
        Assert.Equal(
            "served 5 portions: 2+3",
            await AsyncFunctions.ServeTreatsAsync(new[] { 2, 3 }));
    }

    [Fact]
    public async Task ServeTreatsAsync_TopLevelSetOverload_CallsByNameAndNotByCount()
    {
        // Both overloads cross one IntPtr, so if `_2` missed the extern name this call would bind
        // the List extern by argument type and answer "served ... portions" instead.
        Assert.Equal(
            "called Mylo and Oreo to the bowl",
            await AsyncFunctions.ServeTreatsAsync(new HashSet<string> { "Oreo", "Mylo" }));
    }

    [Fact]
    public async Task ServeTreatsAsync_BothTopLevelSameWireOverloads_StayDistinctWhenInterleaved()
    {
        Assert.Equal("served 7 portions: 7", await AsyncFunctions.ServeTreatsAsync(new[] { 7 }));
        Assert.Equal(
            "called Oreo to the bowl",
            await AsyncFunctions.ServeTreatsAsync(new HashSet<string> { "Oreo" }));
        Assert.Equal("served 1 portions: 1", await AsyncFunctions.ServeTreatsAsync(new[] { 1 }));
    }

    [Fact]
    public async Task Ping_OrdinaryAndSuspendNamesakes_EachAnswerTheirOwnBody()
    {
        // The ordinary `ping()` shares the counter with the suspend `ping(Int)` (ADR-095 parity),
        // so the native symbols become `ping` and `ping_2_async`. Neither public name changes:
        // `Ping()` and `PingAsync(int)`.
        Assert.Equal("Oreo pinged back", AsyncFunctions.Ping());
        Assert.Equal("Mylo pinged back after 4 pings", await AsyncFunctions.PingAsync(4));
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

    [Fact]
    public void TopLevelSuspendOverloads_AreOneNaturalSetWithoutNumberedPublicNames()
    {
        Assert.NotNull(typeof(AsyncFunctions).GetMethod(
            "FetchTreatAsync", new[] { typeof(CancellationToken) }));
        Assert.NotNull(typeof(AsyncFunctions).GetMethod(
            "FetchTreatAsync", new[] { typeof(int), typeof(CancellationToken) }));
        Assert.NotNull(typeof(AsyncFunctions).GetMethod(
            "ServeTreatsAsync", new[] { typeof(IReadOnlyList<int>), typeof(CancellationToken) }));
        Assert.NotNull(typeof(AsyncFunctions).GetMethod(
            "ServeTreatsAsync", new[] { typeof(IReadOnlySet<string>), typeof(CancellationToken) }));
        Assert.NotNull(typeof(AsyncFunctions).GetMethod("Ping", Type.EmptyTypes));
        Assert.NotNull(typeof(AsyncFunctions).GetMethod(
            "PingAsync", new[] { typeof(int), typeof(CancellationToken) }));

        Assert.DoesNotContain(
            typeof(AsyncFunctions).GetMethods(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly),
            method => method.Name.Contains("_2", StringComparison.Ordinal));
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

        Assert.Contains("test_cat__asynccatservice_fetchCat_async", service);
        Assert.Contains("test_cat__asynccatservice_fetchCat_2_async", service);
        Assert.Contains("test_cat__asynccatsitter_feed_async", sitter);
        Assert.Contains("test_cat__asynccatsitter_feed_2_async", sitter);
        Assert.Contains("test_cat__catmoodtracker_awaitMoodReport_async", tracker);
        Assert.Contains("test_cat__catmoodtracker_awaitMoodReport_2_async", tracker);
    }

    /// <summary>
    /// ROADMAP line 29, the top-level half. <c>fetchGreeting</c> is the unnumbered canary. The
    /// <c>ping</c> pair encodes the shared-counter decision (ADR-095 parity): the ordinary
    /// <c>ping()</c> is declared first and keeps <c>ping</c>, the suspend <c>ping(Int)</c> takes
    /// number 2. A suspend-only counter would give <c>ping_async</c> instead, which is asserted
    /// absent so the two policies are distinguishable from C#.
    /// </summary>
    [Fact]
    public void TopLevelSuspendOverloads_TakeNumberedNativeEntryPoints()
    {
        string[] functions = EntryPointsOf(typeof(AsyncFunctions));

        Assert.Contains("test_cat__fetchGreeting_async", functions);
        Assert.Contains("test_cat__fetchTreat_async", functions);
        Assert.Contains("test_cat__fetchTreat_2_async", functions);
        Assert.Contains("test_cat__serveTreats_async", functions);
        Assert.Contains("test_cat__serveTreats_2_async", functions);
        Assert.Contains("test_cat__ping", functions);
        Assert.Contains("test_cat__ping_2_async", functions);
        Assert.DoesNotContain("test_cat__ping_async", functions);
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

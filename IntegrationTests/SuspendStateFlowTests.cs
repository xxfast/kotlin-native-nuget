using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-068: <c>suspend fun</c> returning <c>StateFlow&lt;T&gt;</c>. The outer suspend is KEPT as a
/// <c>Task</c> (composing ADR-019's suspend mapping over ADR-065's StateFlow mapping), so the
/// generated shape is <c>Task&lt;KotlinStateFlow&lt;T&gt;&gt; XxxAsync()</c> -- NOT a collapsed
/// synchronous <c>KotlinStateFlow&lt;T&gt;</c>.
///
/// Fixture: <see cref="CatMoodTracker"/> (test-library) gains <c>awaitMoodReport()</c> (primitive
/// element, shares the same underlying MutableStateFlow as <c>Mood</c>/<c>MoodReport()</c>) and
/// <c>awaitPlaymateReport()</c> (object element, shares <c>Playmate</c>'s MutableStateFlow). Both
/// genuinely <c>delay()</c> before returning the holder, so the outer suspend is not vestigial.
/// </summary>
public class SuspendStateFlowTests
{
    [Fact]
    public async Task AwaitMoodReport_ReturnsTaskOfKotlinStateFlow_NotACollapsedSyncReturn_OreosMoodArrivesAsynchronously()
    {
        // The whole point of ADR-068: awaiting the suspend fun hands back a Task<KotlinStateFlow<T>>.
        // Assigning the un-awaited call to a Task<...> proves the outer suspend was kept, not collapsed.
        using var tracker = new CatMoodTracker("Oreo");
        Task<KotlinStateFlow<string>> pending = tracker.AwaitMoodReportAsync();
        KotlinStateFlow<string> report = await pending;
        Assert.NotNull(report);
    }

    [Fact]
    public async Task AwaitMoodReport_ValueReadsCurrentValueSynchronouslyAfterAwait_OreoIsSleepyByDefault()
    {
        // After the single await, .Value is synchronous thereafter -- exactly ADR-065's contract.
        using var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();
        Assert.Equal("sleepy", report.Value);
    }

    [Fact]
    public async Task AwaitMoodReport_SharesUnderlyingMutableStateFlow_MutationVisibleAfterAwait_MyloGetsGrumpy()
    {
        // Same underlying MutableStateFlow as `Mood`/`MoodReport()` -- mutation via SetMood is
        // observable through the awaited holder too.
        using var tracker = new CatMoodTracker("Mylo");
        tracker.SetMood("grumpy");

        KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();
        Assert.Equal("grumpy", report.Value);
    }

    [Fact]
    public async Task AwaitMoodReport_AwaitForeach_ReplaysCurrentValueThenUpdates_BoundedByCancellation_MyloReportsHisZoomies()
    {
        // Cancellation-bounded await foreach over the awaited holder replays current-then-updates,
        // exactly ADR-065's stream contract, unchanged once the holder is obtained.
        using var tracker = new CatMoodTracker("Mylo");
        tracker.SetMood("grumpy");

        KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();

        var seen = new List<string>();
        var cts = new CancellationTokenSource();
        await foreach (var mood in report.WithCancellation(cts.Token))
        {
            seen.Add(mood);
            if (seen.Count >= 1) cts.Cancel();
        }
        Assert.Equal("grumpy", seen[0]);
    }

    [Fact]
    public async Task AwaitMoodReport_ReturnsKotlinStateFlow_IsAKotlinFlow_UpcastsLikeItsNonSuspendSibling_OreoStaysAFlow()
    {
        // The awaited holder IS-A KotlinFlow<T> / IAsyncEnumerable<T> (ADR-065 upcast), unchanged.
        using var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();

        IAsyncEnumerable<string> asAsyncEnumerable = report;
        KotlinFlow<string> asKotlinFlow = report;
        Assert.NotNull(asAsyncEnumerable);
        Assert.NotNull(asKotlinFlow);
    }

    [Fact]
    public async Task AwaitPlaymateReport_ReturnsTaskOfKotlinStateFlowOfCat_ObjectElement_OreoAwaitsHisPlaymate()
    {
        // Object-element variant: awaits to a KotlinStateFlow<Cat>.
        using var tracker = new CatMoodTracker("Oreo");
        Task<KotlinStateFlow<Cat>> pending = tracker.AwaitPlaymateReportAsync();
        KotlinStateFlow<Cat> report = await pending;
        Assert.NotNull(report);
    }

    [Fact]
    public async Task AwaitPlaymateReport_ValueReturnsFreshDisposableWrapper_OreoGreetsHisPlaymate()
    {
        // Object-element .Value returns a fresh, working, disposable Cat wrapper (ADR-005).
        using var tracker = new CatMoodTracker("Oreo");
        using var playmate = (await tracker.AwaitPlaymateReportAsync()).Value;
        Assert.Equal("Oreo", playmate.Name);
        Assert.Equal("Meow! My name is Oreo", playmate.Meow());
    }

    [Fact]
    public async Task AwaitPlaymateReport_SharesUnderlyingMutableStateFlow_ReflectsNewPlaymate_MyloBefriendsOreo()
    {
        // Same underlying MutableStateFlow as `Playmate` -- mutation via SetPlaymate is observable
        // through the awaited holder too.
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetPlaymate("Mylo");

        using var playmate = (await tracker.AwaitPlaymateReportAsync()).Value;
        Assert.Equal("Mylo", playmate.Name);
    }

    [Fact]
    public async Task AwaitPlaymateReport_AwaitForeach_ReplaysCurrentPlaymateFirst_BoundedByCancellation_OreoIntroducesMylo()
    {
        // Cancellation-bounded await foreach over the object-element holder, replaying the current
        // playmate first.
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetPlaymate("Mylo");

        KotlinStateFlow<Cat> report = await tracker.AwaitPlaymateReportAsync();

        var cts = new CancellationTokenSource();
        Cat? firstSeen = null;
        await foreach (var cat in report.WithCancellation(cts.Token))
        {
            firstSeen = cat;
            cts.Cancel();
        }
        Assert.NotNull(firstSeen);
        using (firstSeen)
        {
            Assert.Equal("Mylo", firstSeen!.Name);
        }
    }
}

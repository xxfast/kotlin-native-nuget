using System.Diagnostics;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-065: StateFlow&lt;T&gt; mapping. Kotlin <c>StateFlow&lt;T&gt;</c> (and the read-only view of
/// <c>MutableStateFlow&lt;T&gt;</c>) surfaces as a generated <c>KotlinStateFlow&lt;T&gt; : KotlinFlow&lt;T&gt;</c>
/// -- a hot, always-current-value stream. It IS-A <c>KotlinFlow&lt;T&gt;</c> / <c>IAsyncEnumerable&lt;T&gt;</c>
/// (mirroring Kotlin's own <c>StateFlow : Flow</c> upcast) and adds a synchronous, always-present
/// <c>T Value { get; }</c>.
///
/// Fixture: <see cref="CatMoodTracker"/> (test-library) tracks a cat's energy, mood, and playmate as
/// StateFlows, crossing every element-type marshalling seam: <c>StateFlow&lt;int&gt;</c> (no conversion),
/// <c>StateFlow&lt;string&gt;</c> (box unwrap), and <c>StateFlow&lt;Cat&gt;</c> (object handle wrapper).
/// </summary>
public class StateFlowTests
{
    [Fact]
    public void StateFlowProperty_IntType_ValueReturnsCurrentValueSynchronously_OreoStartsFullOfBeans()
    {
        // Oreo starts at full energy -- no awaiting required to find out how zoomy he is
        using var tracker = new CatMoodTracker("Oreo");
        int current = tracker.EnergyLevel.Value;
        Assert.Equal(100, current);
    }

    [Fact]
    public void StateFlowProperty_StringType_ValueReturnsCurrentValueSynchronously_MyloIsSleepyByDefault()
    {
        // Mylo's default mood is "sleepy" -- read synchronously, no await foreach needed
        using var tracker = new CatMoodTracker("Mylo");
        string current = tracker.Mood.Value;
        Assert.Equal("sleepy", current);
    }

    [Fact]
    public async Task StateFlowProperty_AwaitForeach_ReplaysCurrentValueAsFirstElement_OreosEnergyReadingStartsCurrent()
    {
        // A brand-new subscriber immediately sees Oreo's CURRENT energy level, replay-1 semantics
        using var tracker = new CatMoodTracker("Oreo");
        var seen = new List<int>();
        var cts = new CancellationTokenSource();
        await foreach (var level in tracker.EnergyLevel.WithCancellation(cts.Token))
        {
            seen.Add(level);
            cts.Cancel(); // StateFlow never completes on its own -- must bound with cancellation
        }
        Assert.Equal(100, seen[0]);
    }

    [Fact]
    public async Task StateFlowProperty_AfterMutation_ValueAndStreamBothObserveLatest_MyloWakesUpAndZooms()
    {
        // Mylo wakes up, gets zoomies, and both .Value and the live stream must agree on the latest mood
        using var tracker = new CatMoodTracker("Mylo");
        tracker.SetMood("zoomies");

        Assert.Equal("zoomies", tracker.Mood.Value);

        var seen = new List<string>();
        var cts = new CancellationTokenSource();
        await foreach (var mood in tracker.Mood.WithCancellation(cts.Token))
        {
            seen.Add(mood);
            cts.Cancel();
        }
        // Conflation is fine -- we only assert we observe the latest value, not every intermediate one
        Assert.Equal("zoomies", seen[^1]);
    }

    [Fact]
    public async Task StateFlowProperty_MultipleMutations_ConflatesToLatestValue_OreoBurnsThroughEnergyFast()
    {
        // Oreo zooms around burning energy in quick bursts -- a slow collector should see the latest
        // reading, not necessarily every intermediate burst (conflation is fine).
        using var tracker = new CatMoodTracker("Oreo");
        tracker.BumpEnergy(-10);
        tracker.BumpEnergy(-10);
        tracker.BumpEnergy(-10);

        Assert.Equal(70, tracker.EnergyLevel.Value);

        var seen = new List<int>();
        var cts = new CancellationTokenSource();
        await foreach (var level in tracker.EnergyLevel.WithCancellation(cts.Token))
        {
            seen.Add(level);
            cts.Cancel();
        }
        Assert.Equal(70, seen[^1]);
    }

    [Fact]
    public void StateFlowProperty_ReturnsKotlinStateFlow_IsAKotlinFlow_UpcastsLikeKotlinsOwn()
    {
        // KotlinStateFlow<T> IS-A KotlinFlow<T> / IAsyncEnumerable<T> -- mirrors Kotlin's StateFlow : Flow
        using var tracker = new CatMoodTracker("Oreo");
        IAsyncEnumerable<int> asAsyncEnumerable = tracker.EnergyLevel;
        KotlinFlow<int> asKotlinFlow = tracker.EnergyLevel;
        Assert.NotNull(asAsyncEnumerable);
        Assert.NotNull(asKotlinFlow);
    }

    [Fact]
    public void StateFlowFunction_ReturnsCurrentValueSynchronously_MyloReportsHisOwnMood()
    {
        // moodReport() is a non-suspend function returning StateFlow<string> -- same underlying
        // MutableStateFlow as the `Mood` property.
        using var tracker = new CatMoodTracker("Mylo");
        Assert.Equal("sleepy", tracker.MoodReport().Value);
    }

    [Fact]
    public void StateFlowFunction_AfterMutation_ObservesSameUpdateAsProperty_MyloReportsHisZoomies()
    {
        // Mutating through the `Mood` property must be visible via the moodReport() function
        // return, too -- they share the same underlying Kotlin MutableStateFlow.
        using var tracker = new CatMoodTracker("Mylo");
        tracker.SetMood("playful");
        Assert.Equal("playful", tracker.MoodReport().Value);
        Assert.Equal("playful", tracker.Mood.Value);
    }

    [Fact]
    public void StateFlowProperty_ObjectElement_ValueReturnsWorkingDisposableWrapper_OreoHasAPlaymate()
    {
        // StateFlow<Cat>.Value must yield a fresh, working Cat wrapper -- ADR-005 new-wrapper-per-access
        using var tracker = new CatMoodTracker("Oreo");
        using var playmate = tracker.Playmate.Value;
        Assert.Equal("Oreo", playmate.Name);
        Assert.Equal("Meow! My name is Oreo", playmate.Meow());
    }

    [Fact]
    public void StateFlowProperty_ObjectElement_AfterMutation_ValueReflectsNewPlaymate_OreoMakesAFriend()
    {
        // Oreo makes a new friend -- .Value must re-read and hand back the NEW cat, not the old one
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetPlaymate("Mylo");
        using var playmate = tracker.Playmate.Value;
        Assert.Equal("Mylo", playmate.Name);
        Assert.Equal("Meow! My name is Mylo", playmate.Meow());
    }

    [Fact]
    public async Task StateFlowProperty_ObjectElement_AwaitForeach_ReplaysCurrentPlaymateFirst_MyloIsOreosFirstFriend()
    {
        // The stream's very first element must be the CURRENT playmate at subscription time
        using var tracker = new CatMoodTracker("Oreo");
        tracker.SetPlaymate("Mylo");

        var cts = new CancellationTokenSource();
        Cat? firstSeen = null;
        await foreach (var cat in tracker.Playmate.WithCancellation(cts.Token))
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

    [Fact]
    public void StateFlowProperty_AfterDispose_ReadingValueThrowsObjectDisposedException_OreosTrackerIsPutAway()
    {
        // Oreo's mood tracker has been put away -- no more synchronous reads
        var tracker = new CatMoodTracker("Oreo");
        tracker.Dispose();
        Assert.Throws<ObjectDisposedException>(() => { var _ = tracker.EnergyLevel; });
    }

    [Fact]
    public async Task StateFlowProperty_AfterDispose_SubscribingThrowsObjectDisposedException_MylosTrackerIsPutAway()
    {
        // Mylo's mood tracker has been put away -- no more subscriptions either
        var tracker = new CatMoodTracker("Mylo");
        tracker.Dispose();
        await Assert.ThrowsAsync<ObjectDisposedException>(async () =>
        {
            await foreach (var _ in tracker.Mood)
            {
                // Mylo never sees this -- tracker is gone
            }
        });
    }

    [Fact]
    public async Task StateFlowProperty_AwaitForeach_NeverCompletesOnItsOwn_MustBeBoundedByCancellation()
    {
        // A StateFlow is hot and open -- it never self-completes. This await-foreach is only
        // bounded by a short-timeout CancellationTokenSource. StateFlow reuses the shared
        // KotlinFlowEnumerator<T> UNCHANGED (ADR-065 item 4), whose established ADR-026
        // cancellation contract is a CLEAN exit -- no exception: onNext(null, isCancelled=1) ->
        // channel completes -> MoveNextAsync() returns false -> the loop ends normally (exactly
        // Flow_WithCancellation_ExitsCleanlyAfterFirstItem's contract in FlowTests.cs). The proof
        // that Oreo's energy reading never completes ON ITS OWN is that the loop only ends once
        // the token fires -- bounded, and quickly, not hanging forever waiting on the never-ending
        // hot stream.
        using var tracker = new CatMoodTracker("Oreo");
        var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        var seen = new List<int>();

        var stopwatch = Stopwatch.StartNew();
        await foreach (var level in tracker.EnergyLevel.WithCancellation(cts.Token))
        {
            seen.Add(level);
        }
        stopwatch.Stop();

        // The loop exited cleanly (no exception reached this point) precisely because the token
        // fired -- not because the StateFlow ran out of elements (it never does).
        Assert.True(cts.IsCancellationRequested);
        Assert.True(stopwatch.Elapsed < TimeSpan.FromSeconds(5), "await foreach must be bounded by cancellation, not hang forever on a StateFlow that never completes on its own");

        // The stream was genuinely live: we saw at least the replayed current value before
        // cancellation closed it, confirming this stopped because of the token, not a no-op stream.
        Assert.NotEmpty(seen);
        Assert.Equal(100, seen[0]);
    }
}

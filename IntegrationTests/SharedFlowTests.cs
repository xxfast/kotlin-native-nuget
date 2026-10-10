using System.Diagnostics;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-205: SharedFlow&lt;T&gt; mapping. Kotlin <c>SharedFlow&lt;T&gt;</c> surfaces as
/// <c>KotlinSharedFlow&lt;T&gt;</c> (ADR-209), a <c>KotlinFlow&lt;T&gt;</c> and so an
/// <c>IAsyncEnumerable&lt;T&gt;</c> whose enumeration subscribes to the hot stream; a declared
/// <c>MutableSharedFlow&lt;T&gt;</c> surfaces as <c>KotlinMutableSharedFlow&lt;T&gt;</c>. Kotlin's own
/// <c>SharedFlow.collect</c> replays the replay cache first and never completes, so every test here
/// publishes BEFORE enumerating and bounds the loop with <c>break</c> or cancellation.
///
/// Fixture: <see cref="CatBulletin"/> (test-library) publishes a cat's headlines, editions and
/// sightings, crossing the <c>int</c> (no conversion), <c>string</c> (box unwrap) and <c>Cat</c>
/// (object handle wrapper) element seams.
/// </summary>
public class SharedFlowTests
{
    [Fact]
    public async Task MutableSharedFlowProperty_StringElement_ReplaysTheCacheInOrderFirst_OreosTwoHeadlines()
    {
        // Oreo makes the paper twice before anyone subscribes; the replay cache holds both
        using var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("found the treat jar", 1);
        bulletin.Publish("knocked the treat jar off the shelf", 2);

        var seen = new List<string>();
        await foreach (string headline in bulletin.Headlines)
        {
            seen.Add(headline);
            if (seen.Count == 2) break;
        }

        Assert.Equal(
            new[] { "Oreo: found the treat jar", "Oreo: knocked the treat jar off the shelf" },
            seen);
    }

    [Fact]
    public async Task SharedFlowProperty_IntElement_ReplaysLatestEditionToLateSubscriber_MylosSeventhEdition()
    {
        using var bulletin = new CatBulletin("Mylo");
        bulletin.Publish("napped", 6);
        bulletin.Publish("napped again", 7);

        int? first = null;
        await foreach (int edition in bulletin.Editions)
        {
            first = edition;
            break;
        }

        // replay = 1: only the latest edition is in the cache
        Assert.Equal(7, first);
    }

    [Fact]
    public async Task SharedFlowMethod_IntElement_ReplaysLatestEdition_MylosEditionReport()
    {
        using var bulletin = new CatBulletin("Mylo");
        bulletin.Publish("napped", 7);

        int? first = null;
        await foreach (int edition in bulletin.EditionReport())
        {
            first = edition;
            break;
        }

        Assert.Equal(7, first);
    }

    [Fact]
    public async Task SuspendSharedFlowReturn_ObjectElement_ReplaysLatestSighting_OreoWasSeen()
    {
        using var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("spotted on the fence", 3);

        using KotlinSharedFlow<Cat> sightings = await bulletin.LatestSightingsAsync();
        Cat? first = null;
        await foreach (Cat cat in sightings)
        {
            first = cat;
            break;
        }

        Assert.NotNull(first);
        using (first)
        {
            Assert.Equal("Oreo", first!.Name);
        }
    }

    [Fact]
    public async Task SharedFlowProperty_ObjectElement_CancelledAfterReplay_ExitsCleanly_MyloOnTheWindowsill()
    {
        using var bulletin = new CatBulletin("Mylo");
        bulletin.Publish("on the windowsill", 4);

        var cts = new CancellationTokenSource();
        Cat? first = null;
        await foreach (Cat cat in bulletin.Sightings.WithCancellation(cts.Token))
        {
            first = cat;
            cts.Cancel();
        }

        Assert.NotNull(first);
        using (first)
        {
            Assert.Equal("Mylo", first!.Name);
        }
    }

    [Fact]
    public async Task SharedFlowProperty_NeverCompletesOnItsOwn_MustBeBoundedByCancellation()
    {
        // A SharedFlow is hot and open: the loop only ends because the token fires. The same
        // clean-exit cancellation contract as the StateFlow case (ADR-026, ADR-065).
        using var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("still napping", 9);
        var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        var seen = new List<int>();

        var stopwatch = Stopwatch.StartNew();
        await foreach (int edition in bulletin.Editions.WithCancellation(cts.Token))
        {
            seen.Add(edition);
        }
        stopwatch.Stop();

        Assert.True(cts.IsCancellationRequested);
        Assert.True(stopwatch.Elapsed < TimeSpan.FromSeconds(5), "await foreach must be bounded by cancellation, not hang on a SharedFlow that never completes");
        Assert.Equal(new[] { 9 }, seen);
    }

    // ADR-209 from here on: the members KotlinSharedFlow<T> / KotlinMutableSharedFlow<T> add.

    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    /// <summary>Polls <paramref name="condition"/> until it holds, failing after <see cref="Patience"/>.</summary>
    private static async Task WaitUntilAsync(Func<bool> condition, string what)
    {
        var stopwatch = Stopwatch.StartNew();
        while (!condition())
        {
            Assert.True(stopwatch.Elapsed < Patience, $"timed out waiting for {what}");
            await Task.Delay(10);
        }
    }

    [Fact]
    public async Task MutableSharedFlowProperty_EmitThenTryEmit_LandsInKotlinReplayCache_OreosHeadlines()
    {
        using var bulletin = new CatBulletin("Oreo");
        KotlinMutableSharedFlow<string> desk = bulletin.Headlines;

        await desk.EmitAsync("found the treat jar");
        // replay = 2 and no collector: a SharedFlow emit never suspends, so TryEmit accepts it.
        Assert.True(desk.TryEmit("napped on the keyboard"));

        Assert.Equal(new[] { "found the treat jar", "napped on the keyboard" }, desk.ReplayCache);
        // The write reached Kotlin's own flow, not a copy.
        Assert.Equal(desk.ReplayCache, bulletin.LatestHeadlines());
    }

    [Fact]
    public void MutableSharedFlowProperty_EnumElement_TryEmitCrossesAsOrdinal_OreoTurnsGrumpy()
    {
        // Grumpy is ordinal 2: a write that always sends 0 (Happy) cannot pass.
        using var bulletin = new CatBulletin("Oreo");

        Assert.Empty(bulletin.Moods.ReplayCache);
        Assert.True(bulletin.Moods.TryEmit(Mood.Grumpy));

        Assert.Equal(Mood.Grumpy, Assert.Single(bulletin.Moods.ReplayCache));
        Assert.Equal(Mood.Grumpy, bulletin.LatestMood());
    }

    [Fact]
    public async Task MutableSharedFlowProperty_ObjectElement_EmitAsyncPassesTheHandle_MyloVisits()
    {
        using var bulletin = new CatBulletin("Oreo");
        using var mylo = new Cat("Mylo");

        await bulletin.Visitors.EmitAsync(mylo);

        Assert.Equal("Mylo", bulletin.LatestVisitor());
        IReadOnlyList<Cat> visitors = bulletin.Visitors.ReplayCache;
        using (Cat visitor = Assert.Single(visitors))
        {
            Assert.Equal("Mylo", visitor.Name);
        }
    }

    [Fact]
    public void MutableSharedFlowProperty_NullObjectElement_IsRejectedBeforeCrossing()
    {
        using var bulletin = new CatBulletin("Oreo");

        Assert.Throws<ArgumentNullException>(() => bulletin.Visitors.TryEmit(null!));
        Assert.Empty(bulletin.Visitors.ReplayCache);
    }

    [Fact]
    public async Task MutableSharedFlowProperty_ValueClassElement_EmitsCrossAsTheUnderlying_OreoIsTagged()
    {
        // ADR-209 over the ADR-071 value-class arm: C# sends `v.Id`, Kotlin re-wraps it as CatId.
        using var bulletin = new CatBulletin("Oreo");
        KotlinMutableSharedFlow<CatId> tags = bulletin.Tags;
        Assert.Empty(tags.ReplayCache);
        Assert.Null(bulletin.LatestTag());

        Assert.True(tags.TryEmit(new CatId("oreo-1")));
        // The write reached Kotlin's own flow: Kotlin reads the id back before C# does.
        Assert.Equal("oreo-1", bulletin.LatestTag());
        await tags.EmitAsync(new CatId("oreo-2"));

        // replay = 2: both emissions, oldest first, each read back as its record struct.
        Assert.Equal(new[] { new CatId("oreo-1"), new CatId("oreo-2") }, tags.ReplayCache);
        Assert.Equal("oreo-2", bulletin.LatestTag());
    }

    [Fact]
    public async Task MutableSharedFlowProperty_ValueClassElement_CollectsWhatCSharpEmitted_MyloIsTagged()
    {
        using var bulletin = new CatBulletin("Mylo");
        Assert.True(bulletin.Tags.TryEmit(new CatId("mylo-7")));

        using var cts = new CancellationTokenSource(Patience);
        CatId? replayed = null;
        await foreach (CatId tag in bulletin.Tags.WithCancellation(cts.Token))
        {
            replayed = tag;
            break;
        }

        Assert.Equal(new CatId("mylo-7"), replayed);
    }

    [Fact]
    public async Task MutableSharedFlowProperty_DefaultOfAStringValueClass_ThrowsBeforeItCrosses()
    {
        // default(CatId) carries a null Id, which the non-null Kotlin String slot cannot take.
        using var bulletin = new CatBulletin("Oreo");
        Assert.True(bulletin.Tags.TryEmit(new CatId("oreo-1")));

        ArgumentException thrown =
            Assert.Throws<ArgumentException>(() => bulletin.Tags.TryEmit(default(CatId)));
        Assert.Contains("default(CatId)", thrown.Message);
        await Assert.ThrowsAsync<ArgumentException>(
            () => bulletin.Tags.EmitAsync(default(CatId)));

        // Neither refused write reached the flow.
        Assert.Equal(new[] { new CatId("oreo-1") }, bulletin.Tags.ReplayCache);
        Assert.Equal("oreo-1", bulletin.LatestTag());
    }

    [Fact]
    public void SharedFlowProperty_ReplayCache_ReadsTheCacheWithoutSubscribing_MylosEditions()
    {
        using var bulletin = new CatBulletin("Mylo");
        Assert.Empty(bulletin.Editions.ReplayCache);

        bulletin.Publish("napped", 6);
        bulletin.Publish("napped again", 7);

        // replay = 1: only the latest edition is in the cache.
        Assert.Equal(new[] { 7 }, bulletin.Editions.ReplayCache);
        Assert.Equal(new[] { 7 }, bulletin.EditionReport().ReplayCache);
        using (Cat sighting = Assert.Single(bulletin.Sightings.ReplayCache))
        {
            Assert.Equal("Mylo", sighting.Name);
        }
    }

    [Fact]
    public void MutableSharedFlowMethod_IsHeld_WritesLandInTheSameFlow_OreosDesk()
    {
        using var bulletin = new CatBulletin("Oreo");
        using KotlinMutableSharedFlow<string> desk = bulletin.HeadlineDesk();

        Assert.True(desk.TryEmit("held the desk"));

        Assert.Equal(new[] { "held the desk" }, bulletin.Headlines.ReplayCache);
        Assert.Equal(new[] { "held the desk" }, desk.ReplayCache);
    }

    [Fact]
    public async Task SuspendMutableSharedFlowReturn_EmitAsyncAndReplay_OreosAwaitedDesk()
    {
        using var bulletin = new CatBulletin("Oreo");
        using KotlinMutableSharedFlow<string> desk = await bulletin.AwaitHeadlineDeskAsync();

        await desk.EmitAsync("awaited the desk");

        Assert.Equal(new[] { "awaited the desk" }, desk.ReplayCache);
        Assert.Equal(new[] { "awaited the desk" }, bulletin.LatestHeadlines());
    }

    [Fact]
    public async Task SuspendSharedFlowReturn_ReplayCache_ObjectElement_OreoWasSeen()
    {
        using var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("spotted on the fence", 3);

        using KotlinSharedFlow<Cat> sightings = await bulletin.LatestSightingsAsync();

        using (Cat sighting = Assert.Single(sightings.ReplayCache))
        {
            Assert.Equal("Oreo", sighting.Name);
        }
    }

    [Fact]
    public async Task MutableSharedFlowProperty_SubscriptionCount_RisesWhileACollectorIsLive()
    {
        await using var bulletin = new CatBulletin("Oreo");
        using KotlinStateFlow<int> subscribers = bulletin.Pulses.SubscriptionCount;
        Assert.Equal(0, subscribers.Value);

        IAsyncEnumerator<int> pulses = bulletin.Pulses.GetAsyncEnumerator();
        await WaitUntilAsync(() => subscribers.Value == 1, "the collector to subscribe");

        await pulses.DisposeAsync();
        await WaitUntilAsync(() => subscribers.Value == 0, "the collector to leave");
    }

    [Fact]
    public async Task MutableSharedFlowProperty_EmitAsync_AlreadyCancelledToken_IsCancelledWithoutEmitting()
    {
        using var bulletin = new CatBulletin("Oreo");
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => bulletin.Headlines.EmitAsync("never sent", cts.Token));

        Assert.Empty(bulletin.Headlines.ReplayCache);
    }

    /// <summary>
    /// Parks an <c>EmitAsync</c> on <see cref="CatBulletin.Pulses"/> (no replay, no buffer). A
    /// SharedFlow emit only suspends while a subscriber is busy, so the subscriber is a C#
    /// enumerator that never asks for a second item: ADR-207's credit gate parks its Kotlin
    /// collector inside the second item, and the third emit then has nowhere to go.
    /// </summary>
    private static async Task<(IAsyncEnumerator<int> Stalled, Task Parked)> ParkAnEmitAsync(
        CatBulletin bulletin, CancellationToken token = default)
    {
        KotlinMutableSharedFlow<int> pulses = bulletin.Pulses;
        using KotlinStateFlow<int> subscribers = pulses.SubscriptionCount;
        IAsyncEnumerator<int> stalled = pulses.GetAsyncEnumerator();
        await WaitUntilAsync(() => subscribers.Value == 1, "the stalled collector to subscribe");

        await pulses.EmitAsync(1).WaitAsync(Patience);
        await pulses.EmitAsync(2).WaitAsync(Patience);
        Task parked = pulses.EmitAsync(3, token);

        // Deterministic, not a timing guess: the slot is taken, so a non-suspending emit is refused.
        Assert.False(pulses.TryEmit(4));
        Assert.False(parked.IsCompleted);
        return (stalled, parked);
    }

    [Fact]
    public async Task MutableSharedFlowProperty_ParkedEmitAsync_CancelledByItsToken()
    {
        using var bulletin = new CatBulletin("Oreo");
        using var cts = new CancellationTokenSource();
        var (stalled, parked) = await ParkAnEmitAsync(bulletin, cts.Token);

        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => parked.WaitAsync(Patience));
        await stalled.DisposeAsync();
    }

    [Fact]
    public async Task MutableSharedFlowProperty_ParkedEmitAsync_CancelledWhenTheOwnerIsDisposed()
    {
        // ADR-209: EmitAsync launches on the owner's scope, the one its collect uses, so disposing
        // the owner cancels a parked emit instead of leaving its Task pending forever.
        var bulletin = new CatBulletin("Oreo");
        var (stalled, parked) = await ParkAnEmitAsync(bulletin);

        bulletin.Dispose();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => parked.WaitAsync(Patience));
        await stalled.DisposeAsync();
        // The owner's drain has nothing left to wait for.
        await bulletin.DisposeAsync().AsTask().WaitAsync(Patience);
    }

    [Fact]
    public async Task MutableSharedFlowProperty_ParkedEmitAsync_OwnerDisposeAsyncDrainsWithoutHanging()
    {
        // The ADR-207 hang class: the drain waits for the emit (a suspend call on the owner's
        // scope), and the emit waits for the stalled collector. The drain cancels the collection
        // first, which frees the emit, so DisposeAsync returns and the emit is settled.
        var bulletin = new CatBulletin("Oreo");
        var (stalled, parked) = await ParkAnEmitAsync(bulletin);

        await bulletin.DisposeAsync().AsTask().WaitAsync(Patience);

        await WaitUntilAsync(() => parked.IsCompleted, "the parked emit to settle");
        await stalled.DisposeAsync();
    }
}

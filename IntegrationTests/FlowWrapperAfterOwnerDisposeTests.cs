using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// A flow wrapper taken from a property, or from a method that returns a read-only flow, keys its
/// delegates on its OWNER's handle, read on each use. One that outlives its owner used to pass the
/// disposed owner's zero handle to Kotlin. Every such use now throws
/// <see cref="ObjectDisposedException"/> naming the owner, before anything crosses, the way a
/// method call on a disposed owner already does.
///
/// The wrappers that hold the flow's OWN handle are immune and keep working: a held
/// <c>MutableStateFlow</c> / <c>MutableSharedFlow</c> method return and an awaited <c>suspend</c>
/// return. Only what needs the owner's scope (a new collect, <c>EmitAsync</c>) is refused there.
///
/// Oreo's owners are put away first in every test; the wrapper in hand is what is exercised.
/// </summary>
public class FlowWrapperAfterOwnerDisposeTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    private static void AssertOwner(string owner, ObjectDisposedException thrown) =>
        Assert.Equal(owner, thrown.ObjectName);

    private static async Task AssertCollectThrowsAsync<T>(string owner, KotlinFlow<T> flow)
    {
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => flow.GetAsyncEnumerator()));
        AssertOwner(owner, await Assert.ThrowsAsync<ObjectDisposedException>(async () =>
        {
            await foreach (T _ in flow)
            {
            }
        }));
    }

    [Fact]
    public async Task FlowProperty_CollectedAfterOwnerDispose_ThrowsNamingTheOwner()
    {
        var feeder = new CatFeeder("Oreo");
        KotlinFlow<int> portions = feeder.PortionSizes;
        feeder.Dispose();

        await AssertCollectThrowsAsync(nameof(CatFeeder), portions);
    }

    [Fact]
    public async Task FlowMethodReturn_CollectedAfterOwnerDispose_ThrowsNamingTheOwner()
    {
        var feeder = new CatFeeder("Oreo");
        KotlinFlow<string> treats = feeder.Treats(2);
        feeder.Dispose();

        await AssertCollectThrowsAsync(nameof(CatFeeder), treats);
    }

    [Fact]
    public async Task FlowMethodReturn_CollectionArgument_CollectedAfterOwnerDispose_ThrowsNamingTheOwner()
    {
        var board = new TreatBoard();
        KotlinFlow<string> servings = board.Servings(["tuna", "chicken"]);
        board.Dispose();

        await AssertCollectThrowsAsync(nameof(TreatBoard), servings);
    }

    [Fact]
    public async Task StateFlowProperty_ValueAndCollectAfterOwnerDispose_ThrowNamingTheOwner()
    {
        var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlow<int> energy = tracker.EnergyLevel;
        tracker.Dispose();

        AssertOwner(nameof(CatMoodTracker), Assert.Throws<ObjectDisposedException>(() => energy.Value));
        await AssertCollectThrowsAsync(nameof(CatMoodTracker), energy);
    }

    [Fact]
    public async Task StateFlowMethodReturn_ValueAndCollectAfterOwnerDispose_ThrowNamingTheOwner()
    {
        var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlow<string> report = tracker.MoodReport();
        tracker.Dispose();

        AssertOwner(nameof(CatMoodTracker), Assert.Throws<ObjectDisposedException>(() => report.Value));
        await AssertCollectThrowsAsync(nameof(CatMoodTracker), report);
    }

    [Fact]
    public void StateFlowMethodReturn_CollectionArgument_ValueAfterOwnerDispose_ThrowsNamingTheOwner()
    {
        var board = new TreatBoard();
        KotlinStateFlow<int> rations = board.Rations([3, 5]);
        Assert.Equal(8, rations.Value);
        board.Dispose();

        AssertOwner(nameof(TreatBoard), Assert.Throws<ObjectDisposedException>(() => rations.Value));
    }

    [Fact]
    public async Task MutableStateFlowProperty_EveryMemberAfterOwnerDispose_ThrowsNamingTheOwner()
    {
        var tracker = new CatMoodTracker("Oreo");
        KotlinMutableStateFlow<int> treats = tracker.TreatCount;
        treats.Value = 3;
        tracker.Dispose();

        const string owner = nameof(CatMoodTracker);
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => treats.Value));
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => treats.Value = 4));
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => treats.CompareAndSet(3, 4)));
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => treats.Update(count => count + 1)));
        await AssertCollectThrowsAsync(owner, treats);
    }

    [Fact]
    public async Task SharedFlowProperty_ReplayCacheAndCollectAfterOwnerDispose_ThrowNamingTheOwner()
    {
        var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("napped", 7);
        KotlinSharedFlow<int> editions = bulletin.Editions;
        KotlinSharedFlow<int> report = bulletin.EditionReport();
        Assert.Equal(new[] { 7 }, editions.ReplayCache);
        bulletin.Dispose();

        const string owner = nameof(CatBulletin);
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => editions.ReplayCache));
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => report.ReplayCache));
        await AssertCollectThrowsAsync(owner, editions);
        await AssertCollectThrowsAsync(owner, report);
    }

    [Fact]
    public async Task MutableSharedFlowProperty_EveryMemberAfterOwnerDispose_ThrowsNamingTheOwner()
    {
        var bulletin = new CatBulletin("Oreo");
        KotlinMutableSharedFlow<string> headlines = bulletin.Headlines;
        Assert.True(headlines.TryEmit("still here"));
        bulletin.Dispose();

        const string owner = nameof(CatBulletin);
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => headlines.ReplayCache));
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => headlines.SubscriptionCount));
        AssertOwner(owner, Assert.Throws<ObjectDisposedException>(() => headlines.TryEmit("gone")));
        // The disposed-owner path of every suspend member: thrown, never a Task left pending.
        AssertOwner(owner, await Assert.ThrowsAsync<ObjectDisposedException>(
            () => headlines.EmitAsync("gone").WaitAsync(Patience)));
        await AssertCollectThrowsAsync(owner, headlines);
    }

    [Fact]
    public async Task DisposedOwner_WinsOverTheArgumentGuards_OnEveryWrite()
    {
        // Receiver state is checked first: on a disposed owner a null object or a default(V)
        // value class answers ObjectDisposedException, never the argument's own exception.
        var tracker = new CatMoodTracker("Oreo");
        KotlinMutableStateFlow<CatId> chip = tracker.ChipId;
        var bulletin = new CatBulletin("Oreo");
        KotlinMutableSharedFlow<CatId> tags = bulletin.Tags;
        KotlinMutableSharedFlow<Cat> visitors = bulletin.Visitors;
        tracker.Dispose();
        bulletin.Dispose();

        const string trackerName = nameof(CatMoodTracker);
        const string bulletinName = nameof(CatBulletin);
        AssertOwner(trackerName, Assert.Throws<ObjectDisposedException>(() => chip.Value = default));
        AssertOwner(trackerName, Assert.Throws<ObjectDisposedException>(
            () => chip.CompareAndSet(default, default)));
        AssertOwner(bulletinName, Assert.Throws<ObjectDisposedException>(
            () => tags.TryEmit(default(CatId))));
        AssertOwner(bulletinName, await Assert.ThrowsAsync<ObjectDisposedException>(
            () => tags.EmitAsync(default(CatId)).WaitAsync(Patience)));
        AssertOwner(bulletinName, Assert.Throws<ObjectDisposedException>(
            () => visitors.TryEmit(null!)));
    }

    [Fact]
    public async Task SubscriptionCount_TakenBeforeOwnerDispose_StillReadsButCollectsOnNoScope()
    {
        // The count is its own StateFlow handle, so `.Value` needs nothing of the owner. A collect
        // would launch on the owner's scope, which is gone: refused, and no scope is minted for it.
        var bulletin = new CatBulletin("Oreo");
        using KotlinStateFlow<int> subscribers = bulletin.Headlines.SubscriptionCount;
        bulletin.Dispose();

        Assert.Equal(0, subscribers.Value);
        await AssertCollectThrowsAsync(nameof(CatBulletin), subscribers);
    }

    [Fact]
    public async Task HeldAndAwaitedFlows_AfterOwnerDispose_KeepWorkingOnTheirOwnHandle()
    {
        var tracker = new CatMoodTracker("Oreo");
        using KotlinMutableStateFlow<Mood> dial = tracker.OutlookDial();
        var bulletin = new CatBulletin("Oreo");
        using KotlinMutableSharedFlow<string> held = bulletin.HeadlineDesk();
        using KotlinMutableSharedFlow<string> awaited = await bulletin.AwaitHeadlineDeskAsync();
        tracker.Dispose();
        bulletin.Dispose();

        // Immune: every synchronous seam keys on the flow's own handle.
        dial.Value = Mood.Grumpy;
        Assert.Equal(Mood.Grumpy, dial.Value);
        Assert.True(held.TryEmit("held"));
        Assert.True(awaited.TryEmit("awaited"));
        Assert.Equal(new[] { "held", "awaited" }, held.ReplayCache);
        Assert.Equal(held.ReplayCache, awaited.ReplayCache);

        // What needs the owner's scope is refused by the closed scope handle, as it always was.
        await Assert.ThrowsAsync<ObjectDisposedException>(() => held.EmitAsync("late").WaitAsync(Patience));
        await Assert.ThrowsAsync<ObjectDisposedException>(() => awaited.EmitAsync("late").WaitAsync(Patience));
        Assert.Throws<ObjectDisposedException>(() => held.GetAsyncEnumerator());
        Assert.Throws<ObjectDisposedException>(() => awaited.GetAsyncEnumerator());
        Assert.Throws<ObjectDisposedException>(() => dial.GetAsyncEnumerator());
    }

    [Fact]
    public async Task InFlightCollection_WhenTheOwnerIsDisposed_EndsCleanlyAsBefore()
    {
        // Unchanged: the guard is on STARTING a collection. One already running is cancelled with
        // the owner's scope and the loop ends without an exception.
        var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("napped", 7);
        await using IAsyncEnumerator<int> editions = bulletin.Editions.GetAsyncEnumerator();
        Assert.True(await editions.MoveNextAsync().AsTask().WaitAsync(Patience));
        Assert.Equal(7, editions.Current);

        bulletin.Dispose();

        Assert.False(await editions.MoveNextAsync().AsTask().WaitAsync(Patience));
    }
}

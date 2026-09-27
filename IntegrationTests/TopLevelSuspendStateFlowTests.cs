using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-068 (2026-09-27 amendment): a TOP-LEVEL <c>suspend fun</c> returning <c>StateFlow&lt;T&gt;</c>
/// awaits to the same handle-owning <c>KotlinStateFlow&lt;T&gt;</c> a class method's does. There is
/// no parent class scope, so collection launches on the runtime's ad-hoc scope; the enumerator still
/// cancels its own job, and the holder still owns and releases the awaited flow's handle.
///
/// Fixture: <c>CatWatch.kt</c> (test-library). The purr counter is process-wide state shared across
/// tests, so every assertion reads a delta, never an absolute count.
/// </summary>
public class TopLevelSuspendStateFlowTests
{
    [Fact]
    public async Task WatchPurrCount_ReturnsTaskOfKotlinStateFlow_OreoStartsWatching()
    {
        Task<KotlinStateFlow<int>> pending = CatWatch.WatchPurrCountAsync();
        using KotlinStateFlow<int> purrs = await pending;
        Assert.NotNull(purrs);
        KotlinFlow<int> asFlow = purrs;
        IAsyncEnumerable<int> asEnumerable = purrs;
        Assert.NotNull(asFlow);
        Assert.NotNull(asEnumerable);
    }

    [Fact]
    public async Task WatchPurrCount_ValueObservesAnUpdateMadeAfterTheAwait_MyloPurrsThreeTimes()
    {
        using KotlinStateFlow<int> purrs = await CatWatch.WatchPurrCountAsync();
        int before = purrs.Value;

        CatWatch.PurrMore(3);

        Assert.True(purrs.Value >= before + 3, $"expected at least {before + 3}, got {purrs.Value}");
    }

    [Fact]
    public async Task WatchPurrCount_AwaitForeach_ReplaysThenObservesAnUpdate_OreoPurrsWhileWatched()
    {
        using KotlinStateFlow<int> purrs = await CatWatch.WatchPurrCountAsync();

        var seen = new List<int>();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await foreach (int count in purrs.WithCancellation(cts.Token))
        {
            seen.Add(count);
            if (seen.Count == 1)
            {
                CatWatch.PurrMore(1);
            }
            else if (count > seen[0])
            {
                break;
            }
        }

        Assert.True(seen.Count >= 2, "expected the replayed value and one update");
        Assert.True(seen[^1] > seen[0]);
    }

    [Fact]
    public async Task WatchNapBuddy_ObjectElement_ValueIsAFreshWrapperThatFollowsTheSwap_OreoThenMylo()
    {
        using KotlinStateFlow<global::TestLibrary.Cat.Cat> buddies = await CatWatch.WatchNapBuddyAsync();

        CatWatch.SwapNapBuddy("Mylo");
        using global::TestLibrary.Cat.Cat buddy = buddies.Value;
        Assert.Equal("Mylo", buddy.Name);
        Assert.Equal("Meow! My name is Mylo", buddy.Meow());
    }

    [Fact]
    public async Task WatchPurrCount_DisposeIsIdempotent_OreoStopsWatchingTwice()
    {
        KotlinStateFlow<int> purrs = await CatWatch.WatchPurrCountAsync();
        _ = purrs.Value;
        purrs.Dispose();
        purrs.Dispose();
    }

    [Fact]
    public void WatchNickname_NullableElementIsRefusedNotBound()
    {
        // The shared `nuget_stateflow_value` this route reads through has no null arm, so the
        // nullable element is skipped by name rather than bound to a holder that dies on null.
        Assert.Null(typeof(CatWatch).GetMethod("WatchNicknameAsync"));
    }
}

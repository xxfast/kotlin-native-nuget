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

    // Nullable element: `.Value` reads through the runtime's null-aware export and `await foreach`
    // yields the null as an item, never a fault. The nap streak is process-wide state, so each
    // test sets it before reading.
    [Fact]
    public async Task WatchNapStreak_NullableValueElement_ValueAndCollectCarryNull_MyloBreaksTheStreak()
    {
        using KotlinStateFlow<int?> streak = await CatWatch.WatchNapStreakAsync();
        CatWatch.CountNapStreak(null);
        Assert.Null(streak.Value);
        CatWatch.CountNapStreak(3);
        Assert.Equal(3, streak.Value);

        var seen = new List<int?>();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await foreach (int? days in streak.WithCancellation(cts.Token))
        {
            seen.Add(days);
            if (seen.Count == 1)
            {
                CatWatch.CountNapStreak(null);
            }
            else if (days == null)
            {
                break;
            }
        }

        Assert.Equal(3, seen[0]);
        Assert.Null(seen[^1]);
    }

    [Fact]
    public async Task WatchNickname_NullableReferenceElement_ValueIsNullThenAString_OreoBecomesMylo()
    {
        using KotlinStateFlow<string?> nickname = await CatWatch.WatchNicknameAsync();
        CatWatch.CallNickname(null);
        Assert.Null(nickname.Value);
        CatWatch.CallNickname("Mylo");
        Assert.Equal("Mylo", nickname.Value);
    }

    [Fact]
    public async Task WatchLapCat_NullableObjectElement_ValueIsNullThenAFreshWrapper_OreoClimbsUp()
    {
        using KotlinStateFlow<global::TestLibrary.Cat.Cat?> lap = await CatWatch.WatchLapCatAsync();
        CatWatch.AdoptLapCat(null);
        Assert.Null(lap.Value);

        CatWatch.AdoptLapCat("Oreo");
        using global::TestLibrary.Cat.Cat? oreo = lap.Value;
        Assert.NotNull(oreo);
        Assert.Equal("Oreo", oreo!.Name);
    }

    // Nullable member: the awaited holder itself is null while no den is open; the completion tests
    // the wire pointer before it owns or wraps anything.
    [Fact]
    public async Task WatchDen_NullableMember_AwaitsNullThenAHolder_OreoOpensTheDen()
    {
        CatWatch.CloseDen();
        KotlinStateFlow<global::TestLibrary.Cat.Cat>? none = await CatWatch.WatchDenAsync();
        Assert.Null(none);

        CatWatch.OpenDen("Oreo");
        using KotlinStateFlow<global::TestLibrary.Cat.Cat>? den = await CatWatch.WatchDenAsync();
        Assert.NotNull(den);
        using global::TestLibrary.Cat.Cat cat = den!.Value;
        Assert.Equal("Oreo", cat.Name);
        CatWatch.CloseDen();
    }
}

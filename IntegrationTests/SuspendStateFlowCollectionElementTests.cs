using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-068, collection element: a <c>suspend fun</c> returning a read-only
/// <c>StateFlow&lt;List&lt;T&gt;&gt;</c> was skipped (<c>SKIPPED_UNSUPPORTED_RETURN</c>), because the
/// awaited holder read every element through the runtime's shared <c>nuget_stateflow_value</c> /
/// <c>nuget_stateflow_collect</c>, which box the value unprojected. It now awaits to a
/// <c>KotlinStateFlow&lt;IReadOnlyList&lt;T&gt;&gt;</c> whose <c>.Value</c> and enumeration read
/// through a per-member pair keyed on the awaited flow, the seam ADR-202 gave the acquired
/// <c>Flow</c>.
///
/// Two components, because the per-member pair exists for the one that converts:
/// <see cref="CatMoodTracker.AwaitLitterSizesAsync"/> carries <c>Int</c> (no conversion) and
/// <see cref="CatMoodTracker.AwaitHousematesAsync"/> carries <c>CatId</c>, a value class that leaves
/// Kotlin as its underlying string. Through the shared runtime pair the second would reach C# as
/// boxed value-class instances <c>ReadList</c> cannot decode.
/// </summary>
public class SuspendStateFlowCollectionElementTests
{
    [Fact]
    public async Task AwaitLitterSizes_IntElement_ValueReadsTheWholeList()
    {
        using var tracker = new CatMoodTracker("Oreo");

        using KotlinStateFlow<IReadOnlyList<int>> litters = await tracker.AwaitLitterSizesAsync();

        Assert.Equal([3, 5], litters.Value);
    }

    [Fact]
    public async Task AwaitLitterSizes_SharesTheKotlinFlow_MutationVisibleOnTheNextRead()
    {
        using var tracker = new CatMoodTracker("Mylo");
        using KotlinStateFlow<IReadOnlyList<int>> litters = await tracker.AwaitLitterSizesAsync();

        tracker.RecordLitter(4);

        Assert.Equal([3, 5, 4], litters.Value);
    }

    [Fact]
    public async Task AwaitHousemates_ValueClassElement_ProjectsEachElementToItsUnderlying()
    {
        using var tracker = new CatMoodTracker("oreo");

        using KotlinStateFlow<IReadOnlyList<CatId>> housemates = await tracker.AwaitHousematesAsync();

        Assert.Equal([new CatId("oreo"), new CatId("mylo")], housemates.Value);
    }

    [Fact]
    public async Task AwaitHousemates_RepeatedValueReads_StayCorrect()
    {
        // Every `.Value` read mints a fresh collection handle on the per-member `_value` export and
        // `ReadList` disposes it; a read lambda wired to the wrong export shows up on repetition.
        using var tracker = new CatMoodTracker("oreo");
        using KotlinStateFlow<IReadOnlyList<CatId>> housemates = await tracker.AwaitHousematesAsync();

        for (int i = 0; i < 50; i++)
        {
            Assert.Equal(2, housemates.Value.Count);
        }
    }

    [Fact]
    public async Task AwaitHousemates_AwaitForeach_ReplaysTheCurrentListThenUpdates()
    {
        using var tracker = new CatMoodTracker("oreo");
        using KotlinStateFlow<IReadOnlyList<CatId>> housemates = await tracker.AwaitHousematesAsync();

        var seen = new List<IReadOnlyList<CatId>>();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await foreach (IReadOnlyList<CatId> page in housemates.WithCancellation(cts.Token))
        {
            seen.Add(page);
            if (seen.Count == 1) tracker.WelcomeHousemate("biscuit");
            if (seen.Count == 2) cts.Cancel();
        }

        Assert.Equal([new CatId("oreo"), new CatId("mylo")], seen[0]);
        Assert.Equal(
            [new CatId("oreo"), new CatId("mylo"), new CatId("biscuit")],
            seen[1]);
    }

    [Fact]
    public async Task AwaitLitterSizes_AwaitForeach_ReadsEachEmissionAsAList()
    {
        using var tracker = new CatMoodTracker("Mylo");
        using KotlinStateFlow<IReadOnlyList<int>> litters = await tracker.AwaitLitterSizesAsync();

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await foreach (IReadOnlyList<int> page in litters.WithCancellation(cts.Token))
        {
            Assert.Equal([3, 5], page);
            cts.Cancel();
        }
    }
}

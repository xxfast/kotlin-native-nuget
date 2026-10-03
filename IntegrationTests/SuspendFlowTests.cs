using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class SuspendFlowTests
{
    private static async Task<List<T>> Read<T>(IAsyncEnumerable<T> flow)
    {
        var values = new List<T>();
        await foreach (T value in flow) values.Add(value);
        return values;
    }

    private static async Task WaitFor(Func<bool> signal)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        while (!signal()) await Task.Delay(1, timeout.Token);
    }

    [Fact]
    public async Task AwaitOnce_ThenEnumerateColdFlowTwice()
    {
        await using var cafe = new SuspendFlowCafe();
        Task<KotlinFlow<int>> pending = cafe.PortionsAsync();
        using KotlinFlow<int> portions = await pending;
        Assert.Equal(1, cafe.AcquisitionCount);
        Assert.Equal(0, cafe.SubscriptionCount);
        Assert.Equal(new[] { 17, 29, 43 }, await Read(portions));
        Assert.Equal(new[] { 17, 29, 43 }, await Read(portions));
        Assert.Equal(1, cafe.AcquisitionCount);
        Assert.Equal(2, cafe.SubscriptionCount);
        using KotlinFlow<int?> optional = await cafe.OptionalPortionsAsync();
        Assert.Equal(new int?[] { 17, null, 29 }, await Read(optional));
    }

    [Fact]
    public async Task SuspendedAcquisition_CompletesBeforeCollectionStarts()
    {
        await using var cafe = new SuspendFlowCafe();
        Task<KotlinFlow<int>> pending = cafe.GatedAsync();
        await WaitFor(() => cafe.AcquisitionStarted);
        Assert.False(pending.IsCompleted);
        cafe.ReleaseAcquisition();
        using KotlinFlow<int> flow = await pending;
        Assert.Equal(new[] { 17, 29 }, await Read(flow));
    }

    [Fact]
    public async Task TopLevelAcquisition_UsesAnIndependentFlowHolder()
    {
        using KotlinFlow<int> portions = await SuspendFlowSample.CafePortionsAsync();
        Assert.Equal(new[] { 71, 83 }, await Read(portions));
        using KotlinFlow<string?> names = await SuspendFlowSample.CafeNicknamesAsync();
        Assert.Equal(new string?[] { "Oreo", null, "Mylo" }, await Read(names));
    }

    [Fact]
    public async Task NullableElements_KeepNullsAndWorkingCatWrappers()
    {
        await using var cafe = new SuspendFlowCafe();
        using KotlinFlow<string?> names = await cafe.NicknamesAsync();
        Assert.Equal(new string?[] { "Oreo", null, "Mylo" }, await Read(names));
        using KotlinFlow<Cat?> companions = await cafe.CompanionsAsync();
        var seen = new List<string?>();
        await foreach (Cat? cat in companions)
        {
            using (cat) seen.Add(cat?.Name);
        }
        Assert.Equal(new string?[] { "Oreo", null, "Mylo" }, seen);
        using var observations = await cafe.ObservationsAsync();
        var causes = new List<string?>();
        await foreach (Observation? observation in observations)
        {
            using (observation)
            {
                if (observation is Observation.Dead dead) causes.Add(dead.Cause);
                else if (observation is null) causes.Add(null);
                else
                {
                    Assert.IsType<Observation.Superposition>(observation);
                    causes.Add("superposition");
                }
            }
        }
        Assert.Equal(new string?[] { "Mylo stole the tuna", null, "superposition" }, causes);
    }

    [Fact]
    public async Task CollectionElements_ProjectNonzeroEnumsAndValueClasses()
    {
        await using var cafe = new SuspendFlowCafe();
        using var moods = await cafe.MoodsAsync();
        var seenMoods = await Read(moods);
        Assert.Equal(new[] { Mood.Grumpy, Mood.Sleepy }, seenMoods[0]);
        Assert.Equal(new[] { Mood.Sleepy }, seenMoods[1]);
        using var tags = await cafe.TagsAsync();
        var seenTags = await Read(tags);
        Assert.Equal(new[] { new CatId("oreo-17"), new CatId("mylo-29") }, seenTags[0]);
        Assert.Empty(seenTags[1]);
        using var moodSet = await cafe.MoodSetAsync();
        var seenSet = await Read(moodSet);
        Assert.True(seenSet[0].SetEquals(new[] { Mood.Grumpy, Mood.Sleepy }));
        using var taggedMoods = await cafe.TaggedMoodsAsync();
        var seenMap = await Read(taggedMoods);
        Assert.Equal(Mood.Grumpy, seenMap[0][new CatId("oreo-17")]);
        Assert.Equal(Mood.Sleepy, seenMap[0][new CatId("mylo-29")]);
    }

    private sealed class HousePet : IPet
    {
        public string Name => "Oreo";
        public int Legs => 4;
        public string? Nickname => null;
        public string Vibe => "sleepy";
        public string Speak() => "Purr";
        public string Greet() => "Oreo is home";
        public string Fetch(string item) => item;
        public void Nap() { }
        public void Dispose() { }
    }

    [Fact]
    public async Task InterfaceElements_PreserveManagedIdentityAndKotlinDispatch()
    {
        await using var cafe = new SuspendFlowCafe();
        var oreo = new HousePet();
        cafe.InstallPet(oreo);
        using var pets = await cafe.PetsAsync();
        var seen = await Read(pets);
        Assert.Same(oreo, seen[0]);
        Assert.Null(seen[1]);
        using IPet stray = seen[2]!;
        Assert.Equal("Whiskers the Stray", stray.Name);
        Assert.Equal("Mrrp?", stray.Speak());
    }

    [Fact]
    public async Task ByteArrayElements_CopySignedBitsAndDistinguishNullFromEmpty()
    {
        await using var cafe = new SuspendFlowCafe();
        using KotlinFlow<byte[]?> markings = await cafe.MarkingsAsync();
        var seen = await Read(markings);
        Assert.Equal(new byte[] { 0, 127, 128, 255 }, seen[0]);
        Assert.Null(seen[1]);
        Assert.Empty(seen[2]!);
        Assert.Equal(new byte[] { 29 }, seen[3]);
    }

    [Fact]
    public async Task SealedBaseAndArm_AndKotlinBackedInterface_AcquireTheirOwnFlows()
    {
        await using SuspendFlowNap nap = new SuspendFlowNap.Loaf("Oreo");
        using var dreams = await nap.DreamsAsync();
        Assert.Equal(new[] { "Oreo naps", "Oreo wakes" }, await Read(dreams));
        using var purrs = await ((SuspendFlowNap.Loaf)nap).PurrsAsync();
        Assert.Equal(new[] { 3, 7 }, await Read(purrs));
        await using ISuspendFlowMenu menu = SuspendFlowSample.HouseFlowMenu();
        using var specials = await menu.SpecialsAsync();
        Assert.Equal(new[] { "Oreo's tuna", "Mylo's salmon" }, await Read(specials));
    }

    [Fact]
    public async Task AcquisitionAndEmissionFaults_AppearAtTheirRespectiveAwait()
    {
        await using var cafe = new SuspendFlowCafe();
        var acquisition = await Assert.ThrowsAnyAsync<Exception>(() => cafe.AcquisitionFaultAsync());
        Assert.Contains("Oreo's acquisition failed", acquisition.Message);
        using var flow = await cafe.EmissionFaultAsync();
        await using var iterator = flow.GetAsyncEnumerator();
        Assert.True(await iterator.MoveNextAsync());
        Assert.Equal(17, iterator.Current);
        var emission = await Assert.ThrowsAnyAsync<Exception>(async () =>
            await iterator.MoveNextAsync());
        Assert.Contains("Mylo's emission failed", emission.Message);
    }

    [Fact]
    public async Task DisposedHolder_RejectsNewEnumeration_ButActiveCollectionFinishes()
    {
        await using var cafe = new SuspendFlowCafe();
        var flow = await cafe.HeldAsync();
        await using var iterator = flow.GetAsyncEnumerator();
        Assert.True(await iterator.MoveNextAsync());
        await WaitFor(() => cafe.CollectionStarted);
        flow.Dispose();
        flow.Dispose();
        await Assert.ThrowsAsync<ObjectDisposedException>(async () => await Read(flow));
        cafe.ReleaseCollection();
        Assert.True(await iterator.MoveNextAsync());
        Assert.Equal(29, iterator.Current);
        Assert.False(await iterator.MoveNextAsync());
    }

    [Fact]
    public async Task OwnerDisposal_CancelsActiveCollectionAndRejectsNewEnumeration()
    {
        var cafe = new SuspendFlowCafe();
        using var flow = await cafe.HeldAsync();
        await using var iterator = flow.GetAsyncEnumerator();
        Assert.True(await iterator.MoveNextAsync());
        await WaitFor(() => cafe.CollectionStarted);
        cafe.Dispose();
        Assert.False(await iterator.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10)));
        await Assert.ThrowsAsync<ObjectDisposedException>(async () => await Read(flow));
    }

    [Fact]
    public async Task AcquisitionCancellation_IsSeparateFromEnumerationCancellation()
    {
        await using var cafe = new SuspendFlowCafe();
        using var acquisition = new CancellationTokenSource();
        var pending = cafe.GatedAsync(acquisition.Token);
        await WaitFor(() => cafe.AcquisitionStarted);
        acquisition.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            pending.WaitAsync(TimeSpan.FromSeconds(10)));
        using var flow = await cafe.HeldAsync();
        using var collection = new CancellationTokenSource();
        await using var iterator = flow.GetAsyncEnumerator(collection.Token);
        Assert.True(await iterator.MoveNextAsync());
        await WaitFor(() => cafe.CollectionStarted);
        collection.Cancel();
        Assert.False(await iterator.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10)));
    }

    [Fact]
    public async Task AlreadyCanceledAcquisition_DoesNotReturnAHolder()
    {
        await using var cafe = new SuspendFlowCafe();
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            cafe.GatedAsync(cancellation.Token));
    }

    [Fact]
    public async Task CancellationIgnoringAcquisition_ReturnsOwnedFlowAfterCancellation()
    {
        await using var cafe = new SuspendFlowCafe();
        using var cancellation = new CancellationTokenSource();
        var pending = cafe.StubbornAsync(cancellation.Token);
        await WaitFor(() => cafe.AcquisitionStarted);
        cancellation.Cancel();
        cafe.ReleaseAcquisition();
        using var flow = await pending.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(new[] { 17, 29 }, await Read(flow));
    }

    [Fact]
    public async Task AcquisitionTokenCanceledAfterSuccess_DoesNotCancelCollection()
    {
        await using var cafe = new SuspendFlowCafe();
        using var cancellation = new CancellationTokenSource();
        using var flow = await cafe.PortionsAsync(cancellation.Token);
        cancellation.Cancel();
        Assert.Equal(new[] { 17, 29, 43 }, await Read(flow));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<KotlinFlow<int>> AcquireWithoutKeepingOwner()
    {
        var cafe = new SuspendFlowCafe();
        return await cafe.PortionsAsync();
    }

    private static void RunFinalizers()
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
    }

    [Fact]
    public async Task AcquiredHolder_KeepsOriginalOwnerScopeUsableAcrossGc()
    {
        using var flow = await AcquireWithoutKeepingOwner();
        RunFinalizers();
        Assert.Equal(new[] { 17, 29, 43 }, await Read(flow));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<IAsyncEnumerator<int>> EnumerateWithoutKeepingHolder()
    {
        KotlinFlow<int> flow = await SuspendFlowSample.CafePortionsAsync();
        return flow.GetAsyncEnumerator();
    }

    [Fact]
    public async Task Enumerator_KeepsAcquiredFlowUsableAfterHolderIsUnreferenced()
    {
        await using var iterator = await EnumerateWithoutKeepingHolder();
        RunFinalizers();
        Assert.True(await iterator.MoveNextAsync());
        Assert.Equal(71, iterator.Current);
        RunFinalizers();
        Assert.True(await iterator.MoveNextAsync());
        Assert.Equal(83, iterator.Current);
        Assert.False(await iterator.MoveNextAsync());
    }

    [Fact]
    public async Task LateAcquisitionAfterOwnerDisposal_DoesNotCreateAReplacementScope()
    {
        var cafe = new SuspendFlowCafe();
        using var release = SuspendFlowSample.CafeAcquisitionRelease(cafe);
        var pending = cafe.StubbornAsync();
        await WaitFor(() => cafe.AcquisitionStarted);
        cafe.Dispose();
        release.Invoke();
        using var flow = await pending.WaitAsync(TimeSpan.FromSeconds(10));
        await Assert.ThrowsAsync<ObjectDisposedException>(async () => await Read(flow));
    }

    [Fact]
    public void NullableCollectionElement_IsRefused()
    {
        Assert.Null(typeof(SuspendFlowCafe).GetMethod("NullableCollectionsAsync"));
    }

    [Fact]
    public async Task NonowningOrdinaryFlow_DisposeIsANoOp()
    {
        await using var feeder = new CatFeeder("Oreo");
        var flow = feeder.Treats(2);
        flow.Dispose();
        Assert.Equal(new[] { "Oreo ate treat #1", "Oreo ate treat #2" }, await Read(flow));
    }
}

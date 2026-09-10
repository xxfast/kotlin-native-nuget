using TestLibrary.Cat;

namespace IntegrationTests;

public class AsyncDisposableTests
{
    [Fact]
    public async Task DisposeAsync_WaitsForOreoQuickNap_ThenCompletes()
    {
        // Oreo settles in for a quick nap — DisposeAsync should wait for him to finish
        Task<string> oreoNap;
        var service = new CatNapService();
        oreoNap = service.QuickNapAsync();
        await service.DisposeAsync();
        string result = await oreoNap;
        Assert.Equal("quick nap done", result);
    }

    [Fact]
    public async Task AwaitUsing_WaitsForMyloAndOreoNaps()
    {
        // Both Mylo and Oreo curl up — await using waits for both to finish before we leave
        Task<string> oreoNap, myloNap;
        await using (var service = new CatNapService())
        {
            oreoNap = service.QuickNapAsync();
            myloNap = service.QuickNapAsync();
            await Task.Delay(10);
        }
        string oreoResult = await oreoNap;
        string myloResult = await myloNap;
        Assert.Equal("quick nap done", oreoResult);
        Assert.Equal("quick nap done", myloResult);
    }

    [Fact]
    public async Task DisposeAsync_WithNoAsyncCalls_CompletesImmediately()
    {
        // Neither Oreo nor Mylo started napping — DisposeAsync has nothing to wait for
        var service = new CatNapService();
        await service.DisposeAsync();
    }

    [Fact]
    public async Task DisposeAsync_ThenAsyncCall_ThrowsObjectDisposedException()
    {
        // Oreo is done for the day — no more naps after disposal
        var service = new CatNapService();
        await service.DisposeAsync();
        await Assert.ThrowsAsync<ObjectDisposedException>(
            () => service.QuickNapAsync());
    }

    [Fact]
    public async Task Dispose_StillCancels_DisposeAsync_Drains()
    {
        // Dispose() yanks Mylo off the couch (cancels), DisposeAsync() lets Oreo finish his nap (drains)
        Task<string> myloNap;
        using (var cancelService = new CatNapService())
        {
            myloNap = cancelService.LongNapAsync();
            await Task.Delay(50);
        } // Dispose() — Mylo's nap is cancelled
        await Assert.ThrowsAsync<TaskCanceledException>(() => myloNap);

        Task<string> oreoNap;
        var drainService = new CatNapService();
        oreoNap = drainService.QuickNapAsync();
        await Task.Delay(10);
        await drainService.DisposeAsync(); // DisposeAsync() — waits for Oreo to wake up naturally
        string result = await oreoNap;
        Assert.Equal("quick nap done", result);
    }

    [Fact]
    public async Task DoubleDisposeAsync_DoesNotThrow()
    {
        // Calling Oreo twice when he's already been put to bed — should be fine
        var service = new CatNapService();
        await service.DisposeAsync();
        await service.DisposeAsync();
    }

    [Fact]
    public async Task ConcurrentDisposeAsync_DoesNotThrow()
    {
        // Mylo and Oreo both trying to claim the same spot on the couch — no chaos allowed
        var service = new CatNapService();
        var tasks = Enumerable.Range(0, 10)
            .Select(_ => service.DisposeAsync().AsTask())
            .ToArray();
        await Task.WhenAll(tasks);
    }

    // ADR-094 / ADR-040: `CirClassRenderer`'s `implements` `when` puts the `interfaces.isNotEmpty()`
    // arm above the disposable arms, so a class that implements an exported interface never names
    // `IDisposable`/`IAsyncDisposable` in its own base list even though `renderDispose` gives it
    // both bodies. `NapPod : Napper` with a `suspend fun doze()` is the shape that exposes it.

    [Fact]
    public void NapPod_ImplementsIDisposable_OnItsOwnBaseList()
    {
        // Oreo's pod is a handle like any other: it has to be disposable in its own right,
        // not only by way of INapper.
        Assert.True(typeof(IDisposable).IsAssignableFrom(typeof(NapPod)));
    }

    [Fact]
    public void NapPod_ImplementsIAsyncDisposable()
    {
        // The pod owns a coroutine scope (doze suspends), so it gets DisposeAsync(). Nothing
        // reaches that body through the interface, so the class has to advertise it.
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(NapPod)));
    }

    [Fact]
    public async Task NapPod_DisposesThroughAnIAsyncDisposableReference()
    {
        // The capability the base list actually unlocks: holding the pod as IAsyncDisposable.
        // `await using` binds the DisposeAsync *pattern* and compiles either way, so this cast is
        // what a DI container or an IAsyncDisposable-typed field would do to Mylo's pod.
        object pod = new NapPod();
        await ((IAsyncDisposable)pod).DisposeAsync();
    }

    [Fact]
    public async Task AwaitUsing_OnNapPod_DrainsMylosDoze()
    {
        // Mylo climbs on top of the pod for twenty minutes. `await using` only compiles if the
        // class itself is IAsyncDisposable (CS8410 otherwise).
        Task<int> myloDoze;
        await using (var pod = new NapPod())
        {
            myloDoze = pod.DozeAsync();
        }
        Assert.Equal(20, await myloDoze);
    }

    [Fact]
    public void NapPod_StillGreetsThroughItsInterface()
    {
        // The interface members survive the base-list change: Oreo still gets the pod first.
        using var pod = new NapPod();
        Assert.Equal("Oreo curls up in the pod", pod.Nap());
        Assert.IsAssignableFrom<INapper>(pod);
    }
}

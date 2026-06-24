using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class AsyncDisposableTests
{
    [Fact]
    public async Task DisposeAsync_WaitsForOreoQuickNap_ThenCompletes()
    {
        // Oreo settles in for a quick nap — DisposeAsync should wait for her to finish
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
        // Dispose() yanks Mylo off the couch (cancels), DisposeAsync() lets Oreo finish her nap (drains)
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
        // Calling Oreo twice when she's already been put to bed — should be fine
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
}

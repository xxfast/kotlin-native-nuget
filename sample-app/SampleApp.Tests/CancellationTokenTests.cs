using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class CancellationTokenTests
{
    [Fact]
    public async Task CancelCatNap_ViaToken_OreoNapInterrupted()
    {
        // Oreo's long nap gets rudely interrupted
        using var service = new CatNapService();
        var cts = new CancellationTokenSource();
        Task<string> napTask = service.LongNapAsync(cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => napTask);
    }

    [Fact]
    public async Task CancelTopLevelSuspend_ViaToken_GreetingCancelled()
    {
        // Cancel a greeting mid-flight — rude, but necessary
        var cts = new CancellationTokenSource();
        Task<string> greetingTask = AsyncFunctions.FetchGreetingAsync("Oreo", cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => greetingTask);
    }

    [Fact]
    public async Task AlreadyCancelledToken_CancelsImmediately()
    {
        // Even Mylo can't nap with a pre-cancelled token
        using var service = new CatNapService();
        var cts = new CancellationTokenSource();
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(
            () => service.LongNapAsync(cts.Token));
    }

    [Fact]
    public async Task Timeout_CancelsCatNap()
    {
        // Oreo's nap must end within 100ms — timeout!
        using var service = new CatNapService();
        var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));
        await Assert.ThrowsAsync<TaskCanceledException>(
            () => service.LongNapAsync(cts.Token));
    }

    [Fact]
    public async Task DefaultToken_MyloNapsInPeace()
    {
        // No token = Mylo naps in peace (existing behavior preserved)
        using var service = new CatNapService();
        string result = await service.QuickNapAsync();
        Assert.Equal("quick nap done", result);
    }

    [Fact]
    public async Task UnitSuspend_CancelsOnToken()
    {
        // Silent nap can be cancelled too
        using var service = new CatNapService();
        var cts = new CancellationTokenSource();
        Task napTask = service.SilentNapAsync(cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => napTask);
    }

    [Fact]
    public async Task SaveGreeting_CancelsOnToken()
    {
        // Top-level Unit suspend function also accepts token
        var cts = new CancellationTokenSource();
        Task saveTask = AsyncFunctions.SaveGreetingAsync("Meow", cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => saveTask);
    }

    [Fact]
    public async Task CancelOneNap_SiblingNapCompletes()
    {
        // Oreo's nap is cancelled, but Mylo's quick nap on the same scope finishes fine
        using var service = new CatNapService();
        var cts = new CancellationTokenSource();
        Task<string> longNapTask = service.LongNapAsync(cts.Token);
        Task<string> quickNapTask = service.QuickNapAsync();
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => longNapTask);
        string result = await quickNapTask;
        Assert.Equal("quick nap done", result);
    }
}

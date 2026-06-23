using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class SuspendLambdaTests
{
    [Fact]
    public async Task CatFeeder_OnFeed_InvokeAsync_ReturnsExpectedString()
    {
        using var feeder = new CatFeeder("Oreo");
        using var onFeed = feeder.OnFeed;
        string result = await onFeed.InvokeAsync();
        Assert.Equal("Oreo gobbled up the food!", result);
    }

    [Fact]
    public async Task CatFeeder_OnFeedWith_InvokeAsync_ReturnsExpectedString()
    {
        using var feeder = new CatFeeder("Mylo");
        using var onFeedWith = feeder.OnFeedWith;
        string result = await onFeedWith.InvokeAsync("salmon");
        Assert.Equal("Mylo devoured the salmon!", result);
    }

    [Fact]
    public async Task CatFeeder_OnCleanup_InvokeAsync_CompletesWithoutError()
    {
        using var feeder = new CatFeeder("Oreo");
        using var onCleanup = feeder.OnCleanup;
        await onCleanup.InvokeAsync();
    }

    [Fact]
    public async Task CatFeeder_OnFeed_MultipleInvocations_ReturnSameResult()
    {
        using var feeder = new CatFeeder("Mylo");
        using var onFeed = feeder.OnFeed;
        string first = await onFeed.InvokeAsync();
        string second = await onFeed.InvokeAsync();
        Assert.Equal("Mylo gobbled up the food!", first);
        Assert.Equal("Mylo gobbled up the food!", second);
    }

    [Fact]
    public async Task CatFeeder_OnFeed_CancelViaToken_OreoFeedingInterrupted()
    {
        // Oreo was about to get fed, but someone cancelled the token mid-mealtime
        using var feeder = new CatFeeder("Oreo");
        using var onFeed = feeder.OnFeed;
        var cts = new CancellationTokenSource();
        Task<string> feedTask = onFeed.InvokeAsync(cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => feedTask);
    }

    [Fact]
    public async Task CatFeeder_OnFeedWith_CancelViaToken_MyloLeftHungry()
    {
        // Mylo was promised salmon, but the token said no — tragic
        using var feeder = new CatFeeder("Mylo");
        using var onFeedWith = feeder.OnFeedWith;
        var cts = new CancellationTokenSource();
        Task<string> feedTask = onFeedWith.InvokeAsync("salmon", cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => feedTask);
    }

    [Fact]
    public async Task CatFeeder_OnCleanup_CancelViaToken_OreoEscapesChores()
    {
        // Oreo was supposed to clean up, but the token let him off the hook
        using var feeder = new CatFeeder("Oreo");
        using var onCleanup = feeder.OnCleanup;
        var cts = new CancellationTokenSource();
        Task cleanupTask = onCleanup.InvokeAsync(cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => cleanupTask);
    }

    [Fact]
    public async Task CatFeeder_OnFeed_PreCancelledToken_MyloNeverGetsFed()
    {
        // Token was cancelled before Mylo even smelled the food
        using var feeder = new CatFeeder("Mylo");
        using var onFeed = feeder.OnFeed;
        var cts = new CancellationTokenSource();
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(
            () => onFeed.InvokeAsync(cts.Token));
    }
}

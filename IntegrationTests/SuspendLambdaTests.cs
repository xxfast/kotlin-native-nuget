using TestLibrary.Cat;

namespace IntegrationTests;

public class SuspendLambdaTests
{
    [Fact]
    public async Task CatFeeder_OnFeed_InvokeAsync_ReturnsExpectedString()
    {
        using var feeder = new CatFeeder("Oreo");
        var onFeed = feeder.OnFeed;
        string result = await onFeed.Invoke(CancellationToken.None);
        Assert.Equal("Oreo gobbled up the food!", result);
    }

    [Fact]
    public async Task CatFeeder_OnFeedWith_InvokeAsync_ReturnsExpectedString()
    {
        using var feeder = new CatFeeder("Mylo");
        var onFeedWith = feeder.OnFeedWith;
        string result = await onFeedWith.Invoke("salmon", CancellationToken.None);
        Assert.Equal("Mylo devoured the salmon!", result);
    }

    [Fact]
    public async Task CatFeeder_OnCleanup_InvokeAsync_CompletesWithoutError()
    {
        using var feeder = new CatFeeder("Oreo");
        var onCleanup = feeder.OnCleanup;
        await onCleanup.Invoke(CancellationToken.None);
    }

    [Fact]
    public async Task CatFeeder_OnFeedPortion_Arity2_InvokeAsync_ReturnsExpectedString()
    {
        using var feeder = new CatFeeder("Mylo");
        var onFeedPortion = feeder.OnFeedPortion;
        string result = await onFeedPortion.Invoke("salmon", 40, CancellationToken.None);
        Assert.Equal("Mylo devoured 40g of salmon!", result);
    }

    [Fact]
    public async Task CatFeeder_OnFeedSchedule_Arity3_InvokeAsync_ReturnsExpectedString()
    {
        using var feeder = new CatFeeder("Oreo");
        var onFeedSchedule = feeder.OnFeedSchedule;
        string result = await onFeedSchedule.Invoke("tuna", 25, "dawn", CancellationToken.None);
        Assert.Equal("Oreo devoured 25g of tuna at dawn!", result);
    }

    [Fact]
    public async Task CatFeeder_OnLogMeal_Arity2Unit_InvokeAsync_CompletesWithoutError()
    {
        using var feeder = new CatFeeder("Oreo");
        var onLogMeal = feeder.OnLogMeal;
        await onLogMeal.Invoke("tuna", "dawn", CancellationToken.None);
    }

    [Fact]
    public async Task CatFeeder_OnFeedPortion_CancelViaToken_MyloLeftHungry()
    {
        using var feeder = new CatFeeder("Mylo");
        var onFeedPortion = feeder.OnFeedPortion;
        var cts = new CancellationTokenSource();
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(
            () => onFeedPortion.Invoke("salmon", 40, cts.Token));
    }

    [Fact]
    public async Task CatFeeder_OnFeed_MultipleInvocations_ReturnSameResult()
    {
        using var feeder = new CatFeeder("Mylo");
        var onFeed = feeder.OnFeed;
        string first = await onFeed.Invoke(CancellationToken.None);
        string second = await onFeed.Invoke(CancellationToken.None);
        Assert.Equal("Mylo gobbled up the food!", first);
        Assert.Equal("Mylo gobbled up the food!", second);
    }

    [Fact]
    public async Task CatFeeder_OnFeed_CancelViaToken_OreoFeedingInterrupted()
    {
        // Oreo was about to get fed, but someone cancelled the token mid-mealtime
        using var feeder = new CatFeeder("Oreo");
        var onFeed = feeder.OnFeed;
        var cts = new CancellationTokenSource();
        Task<string> feedTask = onFeed.Invoke(cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => feedTask);
    }

    [Fact]
    public async Task CatFeeder_OnFeedWith_CancelViaToken_MyloLeftHungry()
    {
        // Mylo was promised salmon, but the token said no — tragic
        using var feeder = new CatFeeder("Mylo");
        var onFeedWith = feeder.OnFeedWith;
        var cts = new CancellationTokenSource();
        Task<string> feedTask = onFeedWith.Invoke("salmon", cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => feedTask);
    }

    [Fact]
    public async Task CatFeeder_OnCleanup_CancelViaToken_OreoEscapesChores()
    {
        // Oreo was supposed to clean up, but the token let him off the hook
        using var feeder = new CatFeeder("Oreo");
        var onCleanup = feeder.OnCleanup;
        var cts = new CancellationTokenSource();
        Task cleanupTask = onCleanup.Invoke(cts.Token);
        await Task.Delay(50);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => cleanupTask);
    }

    [Fact]
    public async Task CatFeeder_OnFeed_PreCancelledToken_MyloNeverGetsFed()
    {
        // Token was cancelled before Mylo even smelled the food
        using var feeder = new CatFeeder("Mylo");
        var onFeed = feeder.OnFeed;
        var cts = new CancellationTokenSource();
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(
            () => onFeed.Invoke(cts.Token));
    }
}

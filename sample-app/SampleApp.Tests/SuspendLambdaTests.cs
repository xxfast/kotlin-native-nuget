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
}

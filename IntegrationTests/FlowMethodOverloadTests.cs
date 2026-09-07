using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue #97: two overloads returning a flow shape must each bind to their own native entry
/// point. Before the fix both asked for <c>catradio_nowplaying_collect</c> and the build failed.
/// </summary>
public class FlowMethodOverloadTests
{
    [Fact]
    public void StateFlowOverloads_EachReadTheirOwnValue()
    {
        using var radio = new CatRadio();
        Assert.Equal("purr fm: purring hour", radio.NowPlaying("purr fm").Value);
        Assert.Equal("channel 7: nap time", radio.NowPlaying(7).Value);
    }

    [Fact]
    public async Task FlowOverloads_EachCollectTheirOwnEmissions()
    {
        using var radio = new CatRadio();
        var byStation = new List<string>();
        await foreach (var item in radio.Schedule("purr fm"))
            byStation.Add(item);
        var byChannel = new List<string>();
        await foreach (var item in radio.Schedule(7))
            byChannel.Add(item);
        Assert.Equal(new[] { "purr fm: morning purrs", "purr fm: evening naps" }, byStation);
        Assert.Equal(new[] { "channel 7: morning zoomies" }, byChannel);
    }

    [Fact]
    public void MutableStateFlowOverloads_EachWriteThroughTheirOwnSetter()
    {
        using var radio = new CatRadio();
        radio.Volume("purr fm").Value = 9;
        Assert.Equal(9, radio.Volume("purr fm").Value);
        Assert.Equal(5, radio.Volume(7).Value);
    }

    [Fact]
    public void NullableStateFlowOverloads_EachProbeTheirOwnPresence()
    {
        using var radio = new CatRadio();
        Assert.Null(radio.MaybeNowPlaying("static"));
        Assert.Null(radio.MaybeNowPlaying(0));
        Assert.Equal("purr fm: purring hour", radio.MaybeNowPlaying("purr fm")!.Value);
        Assert.Equal("channel 7: nap time", radio.MaybeNowPlaying(7)!.Value);
    }
}

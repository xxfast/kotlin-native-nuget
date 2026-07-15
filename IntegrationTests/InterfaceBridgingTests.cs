using TestLibrary.Cat;

namespace IntegrationTests;

public class InterfaceBridgingTests
{
    private class RecordingCatListener : ICatEventListener
    {
        public List<string> Meows { get; } = new();
        public int Purrs { get; private set; }
        public void OnMeow(string message) => Meows.Add(message);
        public void OnPurr() => Purrs++;
        public void Dispose() { }
    }

    [Fact]
    public void CatEventSource_AddListener_TriggerFiresBothOnMeowAndOnPurr()
    {
        using var source = new CatEventSource("Oreo");
        var listener = new RecordingCatListener();
        using IDisposable sub = source.AddListener(listener);

        source.Trigger();

        Assert.Equal(new[] { "Oreo says meow!" }, listener.Meows);
        Assert.Equal(1, listener.Purrs);
    }

    [Fact]
    public void CatEventSource_AddListener_NoCallbackAfterDispose()
    {
        using var source = new CatEventSource("Mylo");
        var listener = new RecordingCatListener();
        IDisposable sub = source.AddListener(listener);

        sub.Dispose();
        source.Trigger();

        Assert.Empty(listener.Meows);
        Assert.Equal(0, listener.Purrs);
    }

    [Fact]
    public void CatEventSource_AddListener_MultipleListeners_AllReceiveCallbacks()
    {
        using var source = new CatEventSource("Oreo");
        var oreoListener = new RecordingCatListener();
        var myloListener = new RecordingCatListener();
        using IDisposable sub1 = source.AddListener(oreoListener);
        using IDisposable sub2 = source.AddListener(myloListener);

        source.Trigger();

        Assert.Equal(new[] { "Oreo says meow!" }, oreoListener.Meows);
        Assert.Equal(new[] { "Oreo says meow!" }, myloListener.Meows);
        Assert.Equal(1, oreoListener.Purrs);
        Assert.Equal(1, myloListener.Purrs);
    }

    [Fact]
    public void CatEventSource_AddListener_CapturingListener_AccumulatesStateAcrossMultipleTriggers()
    {
        using var source = new CatEventSource("Mylo");
        var listener = new RecordingCatListener();
        using IDisposable sub = source.AddListener(listener);

        source.Trigger();
        source.Trigger();

        Assert.Equal(2, listener.Meows.Count);
        Assert.Equal(2, listener.Purrs);
    }

    [Fact]
    public void CatEventSource_AddListener_ArityOneAndArityZero_BothMethodsDispatchCorrectly()
    {
        using var source = new CatEventSource("Oreo");
        var listener = new RecordingCatListener();
        using IDisposable sub = source.AddListener(listener);

        source.Trigger();

        // arity-1: OnMeow(string) received the expected message
        Assert.Single(listener.Meows);
        Assert.Equal("Oreo says meow!", listener.Meows[0]);
        // arity-0: OnPurr() was invoked with no arguments
        Assert.Equal(1, listener.Purrs);
    }
}

using TestLibrary.Listenerprops;

namespace IntegrationTests;

/// <summary>
/// ADR-039: a listener interface with properties on an <c>add</c>/<c>remove</c> subscription pair.
/// The C# implementation supplies each property, and Kotlin reads it back through a getter slot,
/// the same slot the ADR-084 bridge factory gives a <c>val</c>. Before this route carried them, the
/// generated Kotlin did not compile. Against the <c>PurrBox.kt</c> fixture.
/// </summary>
public class ListenerPropertyTests
{
    private sealed class RecordingPurrListener(
        string name,
        string? nickname,
        int lives,
        bool sleepy) : IPurrListener
    {
        public string Name => name;
        public string? Nickname => nickname;
        public int Lives => lives;
        public bool Sleepy => sleepy;
        public PurrMood Temper { get; set; } = PurrMood.Calm;
        public List<int> Purrs { get; } = new();
        public void OnPurr(int volume) => Purrs.Add(volume);
        public void Dispose() { }
    }

    [Fact]
    public void PurrBox_RollCall_ReadsEveryListenerPropertyFromCSharp()
    {
        using var box = new PurrBox();
        var oreo = new RecordingPurrListener("Oreo", "Oz", 9, false) { Temper = PurrMood.Grumpy };
        var mylo = new RecordingPurrListener("Mylo", null, 7, true);
        using IDisposable oreoSub = box.AddPurrListener(oreo);
        using IDisposable myloSub = box.AddPurrListener(mylo);

        Assert.Equal("Oreo/Oz/9/false/GRUMPY; Mylo/-/7/true/CALM", box.RollCall());
    }

    [Fact]
    public void PurrBox_Purr_KotlinReadsTheBoolGetterBeforeCallingBack()
    {
        using var box = new PurrBox();
        var oreo = new RecordingPurrListener("Oreo", null, 9, false);
        var mylo = new RecordingPurrListener("Mylo", null, 7, true);
        using IDisposable oreoSub = box.AddPurrListener(oreo);
        using IDisposable myloSub = box.AddPurrListener(mylo);

        box.Purr(3);

        Assert.Equal(new[] { 3 }, oreo.Purrs);
        Assert.Empty(mylo.Purrs);
    }

    [Fact]
    public void PurrBox_GetterReadsTheLiveValue_NotASnapshotAtSubscribe()
    {
        using var box = new PurrBox();
        var oreo = new RecordingPurrListener("Oreo", null, 9, false);
        using IDisposable sub = box.AddPurrListener(oreo);

        oreo.Temper = PurrMood.Grumpy;

        Assert.Equal("Oreo/-/9/false/GRUMPY", box.RollCall());
    }

    [Fact]
    public void PurrBox_DisposedSubscription_IsNoLongerRead()
    {
        using var box = new PurrBox();
        var oreo = new RecordingPurrListener("Oreo", null, 9, false);
        IDisposable sub = box.AddPurrListener(oreo);

        sub.Dispose();

        Assert.Equal("", box.RollCall());
    }
}

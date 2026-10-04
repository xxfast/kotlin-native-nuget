using TestLibrary.Charms;

namespace IntegrationTests;

/// <summary>
/// A member-less (marker) Kotlin interface implemented in C#. At a plain parameter it crosses
/// through an ADR-084 bridge factory with no slots; before that factory existed,
/// <c>NugetMarshal.HandleOf</c> threw for it. On the ADR-039 <c>add</c>/<c>remove</c> pair it
/// crosses with no callback slots. Against the <c>CharmBracelet.kt</c> fixture.
/// </summary>
public class MarkerInterfaceTests
{
    private sealed class Clover : ICharm
    {
        public void Dispose() { }
    }

    [Fact]
    public void CSharpCharm_AtAPlainParameter_ComesBackAsTheSameInstance()
    {
        using var bracelet = new CharmBracelet();
        using var clover = new Clover();

        bracelet.Clip(clover);

        Assert.Same(clover, bracelet.Unclip());
    }

    [Fact]
    public void CSharpCharm_OnTheAddRemovePair_SubscribesAndUnsubscribes()
    {
        using var bracelet = new CharmBracelet();
        using var clover = new Clover();

        IDisposable worn = bracelet.AddCharm(clover);
        Assert.Equal(1, bracelet.WornCount());

        worn.Dispose();
        Assert.Equal(0, bracelet.WornCount());
    }
}

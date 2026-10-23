using TestLibrary;
using TestLibrary.Purrclock;

namespace IntegrationTests;

/// <summary>
/// ADR-210 amendment: a nullable <c>Flow&lt;T&gt;?</c> parameter is an
/// <c>IAsyncEnumerable&lt;T&gt;?</c>. A C# <c>null</c> reaches Kotlin as <c>null</c>; a present
/// source is enumerated as the ordinary cold flow. Every owner, a <c>suspend</c> member and a
/// generic <c>T</c>.
/// Oreo (black with the white middle) and Mylo (brown and creamy) come in through the cat flap.
/// </summary>
public class NullableFlowParameterTests
{
    private static async IAsyncEnumerable<T> Of<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    [Fact]
    public async Task TopLevel_CountVisitorsAsync_NullAndPresent()
    {
        Assert.Equal(-1, await Doorways.CountVisitorsAsync(null));
        Assert.Equal(6, await Doorways.CountVisitorsAsync(Of(1, 2, 3)));
    }

    [Fact]
    public void TopLevel_HasFlap_NullAndPresent()
    {
        Assert.False(Doorways.HasFlap(null));
        Assert.True(Doorways.HasFlap(Of("Oreo")));
    }

    [Fact]
    public async Task TopLevel_FirstOrAsync_GenericNullAndPresent()
    {
        Assert.Equal("Mylo", await Doorways.FirstOrAsync<string>(null, "Mylo"));
        Assert.Equal("Oreo", await Doorways.FirstOrAsync(Of("Oreo", "Mylo"), "nobody"));
    }

    [Fact]
    public async Task Doorman_GreetAndIsOpen_ObjectNullAndPresent()
    {
        Assert.Equal("nobody", await Doorman.GreetAsync(null));
        Assert.Equal("Oreo, Mylo", await Doorman.GreetAsync(Of("Oreo", "Mylo")));
        Assert.False(Doorman.IsOpen(null));
        Assert.True(Doorman.IsOpen(Of(1)));
    }

    [Fact]
    public async Task Porch_ClimbAndIsLit_ClassAndCompanionNullAndPresent()
    {
        using var porch = new Porch("front");
        Assert.Equal(0, await porch.ClimbAsync(null));
        Assert.Equal(2, await porch.ClimbAsync(Of(1, 2)));
        Assert.False(Porch.IsLit(null));
        Assert.True(Porch.IsLit(Of(1)));
    }

    [Fact]
    public async Task Gate_SwingAsync_NamedCompanionNullAndPresent()
    {
        Assert.Equal(-1, await Gate.SwingAsync(null));
        Assert.Equal(3, await Gate.SwingAsync(Of(1, 1, 1)));
    }

    [Fact]
    public async Task Hinge_CreakAsync_EnumNullAndPresent()
    {
        Assert.Equal(1, await Hinge.Rusty.CreakAsync(null));
        Assert.Equal(2, await Hinge.Oiled.CreakAsync(Of(1, 1)));
    }

    [Fact]
    public async Task Threshold_StepAsync_ValueClassNullAndPresent()
    {
        Assert.Equal(5, await new Threshold(5).StepAsync(null));
        Assert.Equal(8, await new Threshold(5).StepAsync(Of(1, 2)));
    }
}

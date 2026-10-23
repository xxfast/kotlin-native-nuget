using TestLibrary;
using Cattery = TestLibrary.Cattery;

namespace IntegrationTests;

/// <summary>
/// ADR-233 amendment: a <c>var</c> flow property binds its setter on every owner and every flow
/// type, not only a cold <c>Flow</c> on a non-generic, non-sealed class. Each row sets the property
/// from C# and has Kotlin read it back, so a setter that kept the old flow shows up.
/// <para>
/// Mylo swaps the bowls when nobody is looking. Oreo notices.
/// </para>
/// </summary>
public class FlowVarSetterOwnerTests
{
    private static async IAsyncEnumerable<T> Each<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    [Fact]
    public async Task Gauge_StateFlowVarsSetFromCSharp()
    {
        using var gauge = new Cattery.Gauge();
        using var other = new Cattery.Gauge();
        using KotlinMutableStateFlow<int> dial = KotlinMutableStateFlow<int>.Create(9);
        other.Dial.Value = 5;

        gauge.Level = other.Dial;
        gauge.Dial = dial;

        Assert.StartsWith("level=5 dial=9 ", await gauge.ReadAsync());
        dial.Value = 10;
        Assert.Equal(10, gauge.Dial.Value);
    }

    [Fact]
    public async Task Gauge_SharedFlowVarsSetFromCSharp()
    {
        using var gauge = new Cattery.Gauge();
        using KotlinMutableSharedFlow<string> news = KotlinMutableSharedFlow<string>.Create(replay: 1);
        using KotlinMutableSharedFlow<string> bell = KotlinMutableSharedFlow<string>.Create(replay: 1);
        Assert.True(news.TryEmit("Oreo napped"));
        Assert.True(bell.TryEmit("ding"));

        gauge.News = news;
        gauge.Bell = bell;

        Assert.Equal("level=1 dial=2 news=Oreo napped bell=ding", await gauge.ReadAsync());
    }

    [Fact]
    public async Task Bowl_SealedOwnerVarFlowsSetFromCSharp()
    {
        using Cattery.Bowl.Wet bowl = Cattery.Catteries.WetBowl(80);
        using Cattery.Bowl.Wet other = Cattery.Catteries.WetBowl(40);

        bowl.Portions = KotlinFlow<int>.From(Each(2, 3));
        bowl.Toppings = other.Toppings;

        Assert.Equal(5, await bowl.CountAsync());
        Assert.Equal("gravy", await Cattery.Catteries.GarnishAsync(bowl));

        bowl.Toppings = KotlinFlow<string>.From(Each("tuna flakes"));
        Assert.Equal("tuna flakes", await Cattery.Catteries.GarnishAsync(bowl));
    }

    [Fact]
    public async Task Trough_GenericOwnerVarFlowSetFromCSharp()
    {
        using Cattery.Trough<string> trough = Cattery.Catteries.NameTrough();

        trough.Source = KotlinFlow<string>.From(Each("Mylo", "Oreo"));

        Assert.Equal(["Mylo", "Oreo"], await trough.AllAsync());
    }
}

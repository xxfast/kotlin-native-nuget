using TestLibrary;
// The enum extension members on `MealExtensions`, without importing the whole namespace.
using static TestLibrary.Cattery.MealExtensions;
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

    // ADR-230 amendment: the owners with no C# instance scope bind the setter on the static route.

    [Fact]
    public async Task TopLevel_FlowVarsSetFromCSharp()
    {
        Cattery.Feedings.FeedingTimes = KotlinFlow<int>.From(Each(6, 18));
        Assert.Equal("6, 18", await Cattery.Feedings.FeedingsAsync());

        Cattery.Feedings.LateSnack = KotlinFlow<string>.From(Each("tuna"));
        Assert.Equal("tuna", await Cattery.Feedings.SnackAsync());
        Cattery.Feedings.LateSnack = null;
        Assert.Equal("no snack", await Cattery.Feedings.SnackAsync());
    }

    [Fact]
    public async Task Object_EveryFlowVarKindSetFromCSharp()
    {
        using KotlinMutableStateFlow<int> dial = KotlinMutableStateFlow<int>.Create(9);
        using KotlinMutableStateFlow<int> level = KotlinMutableStateFlow<int>.Create(5);
        using KotlinMutableSharedFlow<string> news = KotlinMutableSharedFlow<string>.Create(replay: 1);
        using KotlinMutableSharedFlow<string> bell = KotlinMutableSharedFlow<string>.Create(replay: 1);
        Assert.True(news.TryEmit("Oreo napped"));
        Assert.True(bell.TryEmit("ding"));

        Cattery.Pantry.Stock = KotlinFlow<string>.From(Each("salmon", "duck"));
        Cattery.Pantry.Level = level;
        Cattery.Pantry.Dial = dial;
        Cattery.Pantry.News = news;
        Cattery.Pantry.Bell = bell;

        Assert.Equal("stock=salmon, duck level=5 dial=9 news=Oreo napped bell=ding", await Cattery.Pantry.ReadAsync());
        dial.Value = 10;
        Assert.Equal(10, Cattery.Pantry.Dial.Value);
    }

    [Fact]
    public async Task Companions_FlowVarsSetFromCSharp()
    {
        Cattery.Bowlful.Refills = KotlinFlow<int>.From(Each(2, 3));
        Assert.Equal(5, await Cattery.Bowlful.TotalAsync());
        // A Kotlin-made flow re-read from its owner mints its own handle on the way back in.
        Cattery.Feedings.FeedingTimes = KotlinFlow<int>.From(Each(4));
        Cattery.Bowlful.Refills = Cattery.Feedings.FeedingTimes;
        Assert.Equal(4, await Cattery.Bowlful.TotalAsync());

        using KotlinMutableStateFlow<int> tins = KotlinMutableStateFlow<int>.Create(12);
        Cattery.Larder.Tins = tins;
        Assert.Equal(12, Cattery.Larder.Count());
    }

    [Fact]
    public async Task Enum_BodyAndCompanionFlowVarsSetFromCSharp()
    {
        Cattery.Meal.Dinner.SetMenu(KotlinFlow<string>.From(Each("chicken")));
        Assert.Equal("chicken", await Cattery.Meal.Dinner.DishAsync());
        Assert.Equal("breakfast", await Cattery.Meal.Breakfast.DishAsync());
        Cattery.Meal.Breakfast.SetMenu(Cattery.Meal.Dinner.Menu);
        Assert.Equal("chicken", await Cattery.Meal.Breakfast.DishAsync());

        Cattery.MealExtensions.Special = KotlinFlow<string>.From(Each("prawns"));
        Assert.Equal("prawns", await Cattery.MealExtensions.TodaysAsync());
    }

    [Fact]
    public async Task SealedObjectArm_FlowVarSetFromCSharp()
    {
        using Cattery.Tray.Empty tray = Cattery.Feedings.EmptyTray();

        tray.Crumbs = KotlinFlow<string>.From(Each("tuna flakes", "kibble dust"));

        Assert.Equal("tuna flakes, kibble dust", await Cattery.Feedings.CrumbsAsync());
    }
}

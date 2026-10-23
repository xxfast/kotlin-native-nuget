using TestLibrary;
using TestLibrary.Purrclock;

namespace IntegrationTests;

/// <summary>
/// ADR-230 amendment: a nullable flow member (<c>Flow&lt;T&gt;?</c>, <c>StateFlow&lt;T&gt;?</c>,
/// <c>SharedFlow&lt;T&gt;?</c>) binds on every owner as the nullable holder, <c>null</c> when
/// Kotlin returns null and an ordinary holder otherwise: the file facade, an <c>object</c>, a
/// default-named and a named companion, an enum body and its companion, a value class, a class,
/// and a <c>suspend</c> result.
/// Oreo (black with the white middle) and Mylo (brown and creamy) are sometimes not home.
/// </summary>
public class NullableStaticFlowTests
{
    private static async Task<List<T>> Drain<T>(IAsyncEnumerable<T>? flow)
    {
        Assert.NotNull(flow);
        var seen = new List<T>();
        await foreach (T item in flow!) seen.Add(item);
        return seen;
    }

    [Fact]
    public async Task TopLevel_PurrsIfHome_PresentOrNull()
    {
        Assert.Equal([1, 2], await Drain(Absences.PurrsIfHome(true)));
        Assert.Null(Absences.PurrsIfHome(false));
    }

    [Fact]
    public async Task TopLevel_EchoIfHome_GenericPresentOrNull()
    {
        Assert.Equal(["Oreo"], await Drain(Absences.EchoIfHome("Oreo", true)));
        Assert.Null(Absences.EchoIfHome(3, false));
    }

    [Fact]
    public void TopLevel_MoodIfHome_NullableStateFlow()
    {
        using KotlinStateFlow<string>? mood = Absences.MoodIfHome(true);
        Assert.Equal("purring", mood!.Value);
        Assert.Null(Absences.MoodIfHome(false));
    }

    [Fact]
    public async Task TopLevel_DoorbellAndCatFlap_NullableProperties()
    {
        Assert.Equal(["ding"], await Drain(Absences.Doorbell));
        Assert.Null(Absences.CatFlap);
    }

    [Fact]
    public async Task TopLevel_LaterIfHomeAsync_NullableSuspendFlowResult()
    {
        using KotlinFlow<int>? later = await Absences.LaterIfHomeAsync(true);
        Assert.Equal([7], await Drain(later));
        Assert.Null(await Absences.LaterIfHomeAsync(false));
    }

    [Fact]
    public async Task Snoozer_NapsAndDream_ObjectPresentOrNull()
    {
        Assert.Equal([3], await Drain(Snoozer.Naps(true)));
        Assert.Null(Snoozer.Naps(false));
        Assert.Equal(["Mylo", "Mylo"], await Drain(Snoozer.Dream("Mylo", true)));
        Assert.Null(Snoozer.Dream("Mylo", false));
    }

    [Fact]
    public void Snoozer_Snores_NullableSharedFlowReplays()
    {
        using KotlinSharedFlow<int>? snores = Snoozer.Snores(true);
        Assert.Equal([9], snores!.ReplayCache);
        Assert.Null(Snoozer.Snores(false));
    }

    [Fact]
    public void Snoozer_PillowAndAlarm_ObjectNullableProperties()
    {
        using KotlinStateFlow<int>? pillow = Snoozer.Pillow;
        Assert.Equal(4, pillow!.Value);
        Assert.Null(Snoozer.Alarm);
    }

    [Fact]
    public async Task Snoozer_DozeIfTiredAsync_NullableSuspendStateFlowResult()
    {
        using KotlinStateFlow<int>? doze = await Snoozer.DozeIfTiredAsync(true);
        Assert.Equal(5, doze!.Value);
        Assert.Null(await Snoozer.DozeIfTiredAsync(false));
    }

    [Fact]
    public async Task Companions_OccupantLiningAndCreases_PresentOrNull()
    {
        Assert.Equal(["Mylo"], await Drain(NapBasket.Occupant(true)));
        Assert.Null(NapBasket.Occupant(false));
        Assert.Null(NapBasket.Lining);
        Assert.Equal([1, 2, 3], await Drain(NapBlanket.Creases(true)));
        Assert.Null(NapBlanket.Creases(false));
    }

    [Fact]
    public async Task Disposition_PurrHissAndHousehold_EnumPresentOrNull()
    {
        Assert.Equal([1], await Drain(Humour.Content.Purr()));
        Assert.Null(Humour.Grumpy.Purr());
        Assert.Equal(["hiss"], await Drain(Humour.Grumpy.Hiss));
        Assert.Null(Humour.Content.Hiss);
        Assert.Equal([Humour.Content], await Drain(HumourExtensions.Household(true)));
        Assert.Null(HumourExtensions.Household(false));
    }

    [Fact]
    public async Task Feeler_TwitchAndSway_ValueClassPresentOrNull()
    {
        Assert.Equal([4], await Drain(new Feeler(4).Twitch()));
        Assert.Null(new Feeler(0).Twitch());
        Assert.Equal([2, 2], await Drain(new Feeler(2).Sway));
        Assert.Null(new Feeler(0).Sway);
    }

    [Fact]
    public async Task Kittens_NamesAndTag_ClassPresentOrNull()
    {
        using var two = new Kittens(2);
        using var none = new Kittens(0);
        Assert.Equal(["Oreo", "Mylo"], await Drain(two.Names()));
        Assert.Null(none.Names());
        Assert.Equal(["Oreo"], await Drain(two.Tag("Oreo")));
        Assert.Null(none.Tag("Oreo"));
    }
}

using TestLibrary.Plushie;

namespace IntegrationTests;

/// <summary>
/// ADR-242: the shapes that bound while <c>Plushie</c> was a handle class and must keep binding
/// now it is a value. Each cell crosses one route a value can reach: a value class over a value,
/// an extension property and function, the <c>suspend</c>, <c>Flow</c> and lambda members of an
/// ordinary handle class (<c>Nursery</c>), a top-level <c>suspend</c> function and a <c>suspend</c>
/// extension taking the value, and a qualifying data class (<c>NappingPlushie</c>) whose own members
/// sit on the <c>suspend</c>, <c>Flow</c> and callback routes.
/// Bun naps in the nursery; Oreo hugs him, Mylo visits.
/// </summary>
public class PlushieShapeTests
{
    private static Plushie Bun() => new("Bun", ["pink", "white"], Mood.Happy, 3);

    [Fact]
    public void ValueClassOverAValue_RoundTrips()
    {
        Plushie bun = Bun();
        PlushieTicket ticket = PlushieSample.TicketFor(bun);
        Assert.Equal(bun, ticket.Plushie);
        Assert.Equal(bun, PlushieSample.Redeem(ticket));
        Assert.Equal(bun, PlushieSample.Redeem(new PlushieTicket(bun)));
    }

    [Fact]
    public void ExtensionPropertyAndFunction_RunKotlinOnTheValue()
    {
        Plushie bun = Bun();
        Assert.Equal("BUN SQUEAKS 3 TIMES", bun.Shout);
        Assert.Equal("Oreo hugs Bun", bun.Hug());
    }

    [Fact]
    public async Task SuspendExtensionAndTopLevelSuspend_TakeTheValue()
    {
        Plushie bun = Bun();
        Assert.Equal("Bun naps", await bun.NapAsync());
        Assert.Equal(30, await PlushieSample.WeighAsync(bun));
    }

    [Fact]
    public async Task HandleClassMembers_TakeTheValue_OnTheSuspendFlowAndLambdaRoutes()
    {
        using var nursery = new Nursery(); // an ordinary class: it stays IDisposable
        Plushie bun = Bun();

        Assert.Equal(1, await nursery.StashAsync(bun));
        Assert.Equal(2, await nursery.StashAsync(bun.Copy(name: "Milo")));

        var seen = new List<int>();
        await foreach (int squeak in nursery.Watch(bun)) seen.Add(squeak);
        Assert.Equal([3, 4], seen);

        string? visited = null;
        nursery.Visit(bun, line => visited = line);
        Assert.Equal("Mylo visits Bun", visited);
    }

    [Fact]
    public async Task NappingPlushie_IsAValue_WhoseOwnMembersUseTheSpecialisedRoutes()
    {
        Assert.False(typeof(IDisposable).IsAssignableFrom(typeof(NappingPlushie)));

        NappingPlushie milo = PlushieSample.NappingPlushie();
        Assert.Equal(new NappingPlushie("Milo"), milo);
        Assert.Equal("Milo snoozes", await milo.SnoozeAsync());

        var fidgets = new List<int>();
        await foreach (int fidget in milo.Fidget()) fidgets.Add(fidget);
        Assert.Equal([1, 2, 3], fidgets);

        var letters = new List<char>();
        milo.ForEachLetter(letters.Add);
        Assert.Equal("Milo", new string(letters.ToArray()));
    }
}

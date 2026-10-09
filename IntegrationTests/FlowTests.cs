using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class FlowTests
{
    [Fact]
    public async Task FlowProperty_CollectsAllMealAnnouncements_OreoEatsCycle()
    {
        // Oreo is a drama king — he narrates every meal in three acts
        using var feeder = new CatFeeder("Oreo");
        var items = new List<string>();
        await foreach (var item in feeder.MealAnnouncements)
            items.Add(item);
        Assert.Equal(3, items.Count);
        Assert.Equal("Oreo is hungry", items[0]);
        Assert.Equal("Oreo is eating", items[1]);
        Assert.Equal("Oreo is full", items[2]);
    }

    [Fact]
    public async Task FlowFunction_CollectsAllTreats_MyloDevoursThem()
    {
        // Mylo inhales treats like a tiny furry vacuum cleaner
        using var feeder = new CatFeeder("Mylo");
        var treats = new List<string>();
        await foreach (var treat in feeder.Treats(3))
            treats.Add(treat);
        Assert.Equal(3, treats.Count);
        Assert.Equal("Mylo ate treat #1", treats[0]);
        Assert.Equal("Mylo ate treat #2", treats[1]);
        Assert.Equal("Mylo ate treat #3", treats[2]);
    }

    [Fact]
    public async Task FlowProperty_IntType_CollectsAllPortionSizes_OreoGetsPortions()
    {
        // Oreo's portions grow over time — he's very persuasive
        using var feeder = new CatFeeder("Oreo");
        var portions = new List<int>();
        await foreach (var portion in feeder.PortionSizes)
            portions.Add(portion);
        Assert.Equal(new List<int> { 100, 150, 200 }, portions);
    }

    [Fact]
    public async Task FlowFunction_ZeroTreats_EmitsNothing()
    {
        // Mylo gets zero treats — tragic but builds character
        using var feeder = new CatFeeder("Mylo");
        var treats = new List<string>();
        await foreach (var treat in feeder.Treats(0))
            treats.Add(treat);
        Assert.Empty(treats);
    }

    [Fact]
    public async Task FlowFunction_OneTreat_EmitsSingleItem()
    {
        // Just one treat for Oreo — he'll make it last approximately 0.3 seconds
        using var feeder = new CatFeeder("Oreo");
        var treats = new List<string>();
        await foreach (var treat in feeder.Treats(1))
            treats.Add(treat);
        Assert.Single(treats);
        Assert.Equal("Oreo ate treat #1", treats[0]);
    }

    [Fact]
    public async Task Flow_WithCancellation_ExitsCleanlyAfterFirstItem()
    {
        // Oreo's meal gets cancelled after the first announcement — the audacity
        using var feeder = new CatFeeder("Oreo");
        var cts = new CancellationTokenSource();
        var items = new List<string>();
        await foreach (var item in feeder.MealAnnouncements.WithCancellation(cts.Token))
        {
            items.Add(item);
            if (items.Count == 1) cts.Cancel();
        }
        // Clean exit after cancellation — no exception, at least the hunger was logged
        Assert.True(items.Count >= 1);
        Assert.Equal("Oreo is hungry", items[0]);
    }

    [Fact]
    public async Task Flow_BreakOnEnumerator_StopsCollection()
    {
        // Oreo's feeder is disposed mid-announcement — break calls DisposeAsync on the enumerator
        var items = new List<string>();
        using (var feeder = new CatFeeder("Oreo"))
        {
            await foreach (var item in feeder.MealAnnouncements)
            {
                items.Add(item);
                if (items.Count == 1) break; // break disposes the enumerator — Oreo's story ends here
            }
        }
        Assert.Single(items);
        Assert.Equal("Oreo is hungry", items[0]);
    }

    [Fact]
    public async Task Flow_MultipleSubscriptions_EachGetsFullSequence()
    {
        // Flow is cold — Mylo gets the full announcement cycle every time we ask
        using var feeder = new CatFeeder("Mylo");
        var first = new List<string>();
        await foreach (var item in feeder.MealAnnouncements)
            first.Add(item);
        var second = new List<string>();
        await foreach (var item in feeder.MealAnnouncements)
            second.Add(item);
        Assert.Equal(first, second);
        Assert.Equal(3, first.Count); // both subscribers get the complete trilogy
    }

    [Fact]
    public async Task Flow_DifferentCats_ProduceDifferentAnnouncements()
    {
        // Oreo and Mylo each narrate their own meals — no cross-contamination
        using var oreoFeeder = new CatFeeder("Oreo");
        using var myloFeeder = new CatFeeder("Mylo");
        var oreoItems = new List<string>();
        var myloItems = new List<string>();
        await foreach (var item in oreoFeeder.MealAnnouncements)
            oreoItems.Add(item);
        await foreach (var item in myloFeeder.MealAnnouncements)
            myloItems.Add(item);
        Assert.Equal("Oreo is hungry", oreoItems[0]);
        Assert.Equal("Mylo is hungry", myloItems[0]);
        Assert.NotEqual(oreoItems[0], myloItems[0]);
    }

    [Fact]
    public async Task Flow_AfterDispose_ThrowsObjectDisposedException()
    {
        // Oreo's feeder has been put away — no more meals today
        var feeder = new CatFeeder("Oreo");
        feeder.Dispose();
        await Assert.ThrowsAsync<ObjectDisposedException>(async () =>
        {
            await foreach (var item in feeder.MealAnnouncements)
            {
                // Oreo never sees this — feeder is gone
            }
        });
    }

    [Fact]
    public async Task FlowProperty_ReturnsKotlinFlow_ImplementsIAsyncEnumerable()
    {
        // MealAnnouncements should be a KotlinFlow<string> which is IAsyncEnumerable<string>
        using var feeder = new CatFeeder("Mylo");
        IAsyncEnumerable<string> announcements = feeder.MealAnnouncements;
        Assert.NotNull(announcements);
    }

    [Fact]
    public async Task FlowFunction_ReturnsKotlinFlow_ImplementsIAsyncEnumerable()
    {
        // Treats(n) should return a KotlinFlow<string> which is IAsyncEnumerable<string>
        using var feeder = new CatFeeder("Mylo");
        IAsyncEnumerable<string> treats = feeder.Treats(5);
        Assert.NotNull(treats);
    }

    // ADR-026 amendment (2026-10-09): a nullable `Flow<T>?` member is `KotlinFlow<T>?`, null when
    // the Kotlin member is null, on the property, method and suspend positions.

    [Fact]
    public async Task NullableFlowProperty_StringElement_OreoHasADietMyloDoesNot()
    {
        using var oreo = new CatFeeder("Oreo");
        KotlinFlow<string>? diet = oreo.SpecialDiet;
        Assert.NotNull(diet);
        var items = new List<string>();
        await foreach (var item in diet!)
            items.Add(item);
        Assert.Equal(new[] { "Oreo: salmon", "Oreo: tuna" }, items);

        using var mylo = new CatFeeder("Mylo");
        Assert.Null(mylo.SpecialDiet);
    }

    [Fact]
    public async Task NullableFlowMethod_ObjectElement_NoMealsNoVisitors()
    {
        using var feeder = new CatFeeder("Oreo");
        Assert.Null(feeder.VisitorsAfter(0));

        KotlinFlow<Cat>? visitors = feeder.VisitorsAfter(2);
        Assert.NotNull(visitors);
        var names = new List<string>();
        await foreach (Cat cat in visitors!)
        {
            using (cat) names.Add(cat.Name);
        }
        Assert.Equal(new[] { "Mylo", "Oreo" }, names);
    }

    [Fact]
    public async Task NullableSuspendFlow_StringElement_NullAndPresent()
    {
        await using var cafe = new SuspendFlowCafe();
        Assert.Null(await cafe.MaybeNicknamesAsync(0));

        using KotlinFlow<string>? names = await cafe.MaybeNicknamesAsync(2);
        Assert.NotNull(names);
        var seen = new List<string>();
        await foreach (string name in names!)
            seen.Add(name);
        Assert.Equal(new[] { "Oreo", "Mylo" }, seen);
    }

    [Fact]
    public async Task NullableSuspendFlow_ObjectElement_NullAndPresent()
    {
        await using var cafe = new SuspendFlowCafe();
        Assert.Null(await cafe.MaybeCompanionsAsync(0));

        using KotlinFlow<Cat>? companions = await cafe.MaybeCompanionsAsync(2);
        Assert.NotNull(companions);
        var seen = new List<string>();
        await foreach (Cat cat in companions!)
        {
            using (cat) seen.Add(cat.Name);
        }
        Assert.Equal(new[] { "Oreo", "Mylo" }, seen);
    }

    [Fact]
    public async Task NullableSuspendFlow_TopLevel_NullAndPresent()
    {
        Assert.Null(await SuspendFlowSample.CafeMaybePortionsAsync(0));

        using KotlinFlow<int>? portions = await SuspendFlowSample.CafeMaybePortionsAsync(2);
        Assert.NotNull(portions);
        var seen = new List<int>();
        await foreach (int portion in portions!)
            seen.Add(portion);
        Assert.Equal(new[] { 71, 83 }, seen);
    }
}

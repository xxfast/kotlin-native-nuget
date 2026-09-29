using TestLibrary;
using TestLibrary.Catfeed;

namespace IntegrationTests;

/// <summary>
/// ADR-174: a Kotlin interface's <c>suspend</c>, <c>Flow</c> and <c>StateFlow</c> members are
/// declared on <c>I&lt;Name&gt;</c> with the class route's own signatures (<c>Task&lt;T&gt;</c> with a
/// defaulted <c>CancellationToken</c>, <c>KotlinFlow&lt;T&gt;</c>, <c>KotlinStateFlow&lt;T&gt;</c>), and
/// the interface is <c>IAsyncDisposable</c> because it projects a scope-using member.
///
/// Every call here goes through an <c>IFeed</c>-typed reference. <see cref="Feeds.MakeFeed"/> hands
/// back the backing wrapper around an ordinary implementer; <see cref="Feeds.MakeCrate"/> hands back
/// the wrapper around a GENERIC implementer (<c>Crate&lt;Int&gt;</c>), which ADR-147 refuses the class
/// async routes on, so the interface's own dispatch is the only way in.
///
/// The <c>Pantry.IBowl</c> block repeats that over an interface NESTED in a class, whose
/// <c>BowlNative</c> carrier lives inside <c>Pantry</c>: <c>Open</c> hands back an ordinary
/// implementer, <c>OpenTin</c> a generic one.
///
/// Oreo eats from the RSS feed, Mylo from the crate, and both insist on the doubled portion.
/// </summary>
public class InterfaceAsyncMemberTests
{
    private static async Task<List<int>> Collect(KotlinFlow<int> flow)
    {
        var seen = new List<int>();
        await foreach (int value in flow) seen.Add(value);
        return seen;
    }

    // --- The ordinary implementer, held as IFeed. ----------------------------------------------

    [Fact]
    public async Task Feed_SuspendMember_WithASuspensionPoint_AwaitsThroughTheInterface()
    {
        // Oreo asks for bowl 3. `fetch` delays before answering, so this is a real suspension.
        await using IFeed feed = Feeds.MakeFeed();
        Assert.Equal("rss-3", await feed.FetchAsync(3));
    }

    [Fact]
    public async Task Feed_SuspendMember_WithNoSuspensionPoint_AwaitsThroughTheInterface()
    {
        // Mylo counts the kibble. `count` returns before the P/Invoke that started it does.
        await using IFeed feed = Feeds.MakeFeed();
        Assert.Equal(3, await feed.CountAsync());
    }

    [Fact]
    public async Task Feed_FlowMember_CollectsThroughTheInterface()
    {
        await using IFeed feed = Feeds.MakeFeed();
        Assert.Equal(new[] { 1, 2, 3 }, await Collect(feed.Ticks()));
    }

    [Fact]
    public async Task Feed_StateFlowProperty_ValueReadsThroughTheInterface()
    {
        await using IFeed feed = Feeds.MakeFeed();
        Assert.Equal(1, feed.Level.Value);
    }

    [Fact]
    public async Task Feed_DefaultFlowMember_CollectsThroughTheInterface()
    {
        // `doubled` has its body on the interface, not on RssFeed: the default member must still be
        // declared on IFeed and reach Kotlin's own `ticks().map { it * 2 }`.
        await using IFeed feed = Feeds.MakeFeed();
        Assert.Equal(new[] { 2, 4, 6 }, await Collect(feed.Doubled()));
    }

    [Fact]
    public async Task Feed_SyncMember_StillAnswersBesideTheAsyncOnes()
    {
        await using IFeed feed = Feeds.MakeFeed();
        Assert.Equal("rss", feed.Name());
    }

    [Fact]
    public async Task Feed_Instance_IsAsyncDisposable()
    {
        await using IFeed feed = Feeds.MakeFeed();
        Assert.IsAssignableFrom<IAsyncDisposable>(feed);
    }

    [Fact]
    public void IFeed_Interface_AdvertisesAsyncDisposal()
    {
        // Rule 6: the interface's own base list, not just whatever wrapper happens to be behind it.
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(IFeed)));
    }

    // --- The generic implementer, held as IFeed. -----------------------------------------------

    [Fact]
    public async Task Crate_SuspendMember_WithASuspensionPoint_DispatchesIntoTheGenericImplementer()
    {
        await using IFeed crate = Feeds.MakeCrate();
        Assert.Equal("crate-3", await crate.FetchAsync(3));
    }

    [Fact]
    public async Task Crate_SuspendMember_WithNoSuspensionPoint_DispatchesIntoTheGenericImplementer()
    {
        await using IFeed crate = Feeds.MakeCrate();
        Assert.Equal(7, await crate.CountAsync());
    }

    [Fact]
    public async Task Crate_FlowMember_CollectsFromTheGenericImplementer()
    {
        await using IFeed crate = Feeds.MakeCrate();
        Assert.Equal(new[] { 4, 5 }, await Collect(crate.Ticks()));
    }

    [Fact]
    public async Task Crate_StateFlowProperty_ValueReadsFromTheGenericImplementer()
    {
        await using IFeed crate = Feeds.MakeCrate();
        Assert.Equal(2, crate.Level.Value);
    }

    [Fact]
    public async Task Crate_DefaultFlowMember_CollectsThroughTheInterface()
    {
        // The default body maps the generic implementer's own `ticks`, so Mylo's portion doubles.
        await using IFeed crate = Feeds.MakeCrate();
        Assert.Equal(new[] { 8, 10 }, await Collect(crate.Doubled()));
    }

    [Fact]
    public async Task Crate_SyncMember_StillAnswersBesideTheAsyncOnes()
    {
        await using IFeed crate = Feeds.MakeCrate();
        Assert.Equal("crate", crate.Name());
    }

    [Fact]
    public async Task Crate_Instance_IsAsyncDisposable()
    {
        await using IFeed crate = Feeds.MakeCrate();
        Assert.IsAssignableFrom<IAsyncDisposable>(crate);
    }

    // --- The implementers constructed in C#, held as IFeed. ----------------------------------
    // Feeds.MakeFeed/MakeCrate hand back the Feed backing wrapper. Constructing in C# puts the
    // implementer's own class behind IFeed: RssFeed's projected members, and Crate<T>'s ADR-174
    // Rule 7 explicit implementations over FeedNative, which the factory facts never reach.
    // (`Crate<T>` is qualified: another namespace in the fixture declares one too.)

    [Fact]
    public async Task RssFeed_ConstructedInCSharp_AnswersEveryMemberThroughTheInterface()
    {
        await using IFeed feed = new RssFeed();
        Assert.Equal("rss-5", await feed.FetchAsync(5));
        Assert.Equal(3, await feed.CountAsync());
        Assert.Equal(new[] { 1, 2, 3 }, await Collect(feed.Ticks()));
        Assert.Equal(1, feed.Level.Value);
        Assert.Equal(new[] { 2, 4, 6 }, await Collect(feed.Doubled()));
        Assert.Equal("rss", feed.Name());
    }

    [Fact]
    public async Task Crate_ConstructedInCSharp_SuspendMembers_ReachTheCarrierThroughRule7()
    {
        // Mylo's crate, built on the C# side: no wrapper in between, only the explicit IFeed forwards.
        await using IFeed crate = new TestLibrary.Catfeed.Crate<int>(1);
        Assert.Equal("crate-6", await crate.FetchAsync(6));
        Assert.Equal(7, await crate.CountAsync());
    }

    [Fact]
    public async Task Crate_ConstructedInCSharp_FlowMembers_ReachTheCarrierThroughRule7()
    {
        await using IFeed crate = new TestLibrary.Catfeed.Crate<int>(1);
        Assert.Equal(new[] { 4, 5 }, await Collect(crate.Ticks()));
        Assert.Equal(new[] { 8, 10 }, await Collect(crate.Doubled()));
    }

    [Fact]
    public async Task Crate_ConstructedInCSharp_StateFlowAndSyncMembers_ReachTheCarrierThroughRule7()
    {
        await using IFeed crate = new TestLibrary.Catfeed.Crate<string>("oreo");
        Assert.Equal(2, crate.Level.Value);
        Assert.Equal("crate", crate.Name());
    }

    // --- A NESTED interface (Pantry.IBowl), whose BowlNative carrier lives inside Pantry. --------
    // Oreo eats from his own bowl; Mylo, being Mylo, eats straight from the generic tin.

    [Fact]
    public async Task OreoBowl_SuspendMember_WithASuspensionPoint_AwaitsThroughTheNestedInterface()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl bowl = pantry.Open();
        Assert.Equal("oreo-3", await bowl.FillAsync(3));
    }

    [Fact]
    public async Task OreoBowl_SuspendMember_WithNoSuspensionPoint_AwaitsThroughTheNestedInterface()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl bowl = pantry.Open();
        Assert.Equal(2, await bowl.ScoopsAsync());
    }

    [Fact]
    public async Task OreoBowl_FlowMember_CollectsThroughTheNestedInterface()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl bowl = pantry.Open();
        Assert.Equal(new[] { 1, 2 }, await Collect(bowl.Kibbles()));
    }

    [Fact]
    public async Task OreoBowl_StateFlowProperty_ValueReadsThroughTheNestedInterface()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl bowl = pantry.Open();
        Assert.Equal(5, bowl.Level.Value);
    }

    [Fact]
    public async Task OreoBowl_DefaultFlowMember_CollectsThroughTheNestedInterface()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl bowl = pantry.Open();
        Assert.Equal(new[] { 2, 4 }, await Collect(bowl.Doubled()));
    }

    [Fact]
    public async Task MyloTin_SuspendMember_WithASuspensionPoint_DispatchesIntoTheGenericImplementer()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl tin = pantry.OpenTin();
        Assert.Equal("mylo-4", await tin.FillAsync(4));
    }

    [Fact]
    public async Task MyloTin_SuspendMember_WithNoSuspensionPoint_DispatchesIntoTheGenericImplementer()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl tin = pantry.OpenTin();
        Assert.Equal(9, await tin.ScoopsAsync());
    }

    [Fact]
    public async Task MyloTin_FlowMember_CollectsFromTheGenericImplementer()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl tin = pantry.OpenTin();
        Assert.Equal(new[] { 3, 4, 5 }, await Collect(tin.Kibbles()));
    }

    [Fact]
    public async Task MyloTin_StateFlowProperty_ValueReadsFromTheGenericImplementer()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl tin = pantry.OpenTin();
        Assert.Equal(8, tin.Level.Value);
    }

    [Fact]
    public async Task MyloTin_DefaultFlowMember_CollectsThroughTheNestedInterface()
    {
        using var pantry = new Pantry();
        await using Pantry.IBowl tin = pantry.OpenTin();
        Assert.Equal(new[] { 6, 8, 10 }, await Collect(tin.Doubled()));
    }

    // The factories above hand back the Pantry.Bowl backing wrapper, whatever Kotlin object is
    // behind it. Constructing the implementers in C# puts the IMPLEMENTER's own C# class behind
    // the Pantry.IBowl reference: OreoBowl's projected members, and MyloTin<T>'s ADR-174 Rule 7
    // explicit implementations, the one path that spells the carrier from outside Pantry
    // (`Pantry.BowlNative`).

    [Fact]
    public async Task OreoBowl_ConstructedInCSharp_AnswersEveryAsyncMemberThroughTheNestedInterface()
    {
        await using Pantry.IBowl bowl = new OreoBowl();
        Assert.Equal("oreo-1", await bowl.FillAsync(1));
        Assert.Equal(2, await bowl.ScoopsAsync());
        Assert.Equal(new[] { 1, 2 }, await Collect(bowl.Kibbles()));
        Assert.Equal(5, bowl.Level.Value);
        Assert.Equal(new[] { 2, 4 }, await Collect(bowl.Doubled()));
    }

    [Fact]
    public async Task MyloTin_ConstructedInCSharp_SuspendMembers_ReachTheNestedCarrierFromOutside()
    {
        await using Pantry.IBowl tin = new MyloTin<string>("tuna");
        Assert.Equal("mylo-2", await tin.FillAsync(2));
        Assert.Equal(9, await tin.ScoopsAsync());
    }

    [Fact]
    public async Task MyloTin_ConstructedInCSharp_FlowMembers_ReachTheNestedCarrierFromOutside()
    {
        await using Pantry.IBowl tin = new MyloTin<string>("salmon");
        Assert.Equal(new[] { 3, 4, 5 }, await Collect(tin.Kibbles()));
        Assert.Equal(new[] { 6, 8, 10 }, await Collect(tin.Doubled()));
    }

    [Fact]
    public async Task MyloTin_ConstructedInCSharp_StateFlowProperty_ReachesTheNestedCarrierFromOutside()
    {
        await using Pantry.IBowl tin = new MyloTin<int>(7);
        Assert.Equal(8, tin.Level.Value);
    }

    [Fact]
    public void PantryIBowl_NestedInterface_AdvertisesAsyncDisposal()
    {
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Pantry.IBowl)));
    }
}

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
}

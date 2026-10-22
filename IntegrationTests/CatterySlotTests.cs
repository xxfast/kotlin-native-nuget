using TestLibrary;
using Cattery = TestLibrary.Cattery;

namespace IntegrationTests;

/// <summary>
/// ADR-233 amendment: the C#-implemented slot shapes beyond the first vocabulary widening. A generic
/// interface in no position inherited at a closed instantiation, a Kotlin-backed hot flow returned by
/// a member function and handed back, a Kotlin-backed and a class <c>var</c> flow set from C#, a
/// <c>var</c> flow slot whose element no other position wraps, a <c>Flow</c> of a <c>Throwable</c>, and a
/// class member returning a lambda.
/// <para>
/// Oreo runs the cattery and Mylo checks every bowl. Neither of them trusts the chute.
/// </para>
/// </summary>
public class CatterySlotTests
{
    private static async IAsyncEnumerable<T> Each<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    private static async Task<List<T>> Collect<T>(IAsyncEnumerable<T> flow)
    {
        List<T> items = [];
        await foreach (T item in flow) items.Add(item);
        return items;
    }

    // -----------------------------------------------------------------------------------------
    // A generic interface in no position, inherited at `Int`.
    // -----------------------------------------------------------------------------------------

    internal sealed class OreosBag : Cattery.IDuffel
    {
        public int Own() => 1;

        public async Task<int> FetchAsync(int id, CancellationToken cancellationToken = default)
        {
            await Task.Delay(1, cancellationToken);
            return id + 1;
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Rummage_CSharpDuffel_InheritedSuspendMemberAtAClosedInt() =>
        Assert.Equal("own=1 fetch=5", await Cattery.Catteries.RummageAsync(new OreosBag()));

    [Fact]
    public async Task Rummage_GenericKotlinImplementer_ForwardsTheSubstitutedInt()
    {
        await using Cattery.IDuffel carryall = Cattery.Catteries.Carryall();

        Assert.Equal(400, await carryall.FetchAsync(4));
        Assert.Equal("own=6 fetch=400", await Cattery.Catteries.RummageAsync(carryall));
    }

    // -----------------------------------------------------------------------------------------
    // A Kotlin-backed member function's hot flow handed back through C#.
    // -----------------------------------------------------------------------------------------

    internal sealed class Relay(Cattery.IIntercom wall) : Cattery.IIntercom
    {
        public KotlinStateFlow<int> Chime() => wall.Chime();
        public KotlinMutableStateFlow<int> Alert() => wall.Alert();
        public KotlinSharedFlow<string> Gossip() => wall.Gossip();
        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Buzz_KotlinBackedMemberFunctionHotFlowsHandedBack()
    {
        await using Cattery.IIntercom wall = Cattery.Catteries.WallIntercom();
        using var relay = new Relay(wall);

        Assert.Equal("chime=4 gossip=Oreo is in the cattery", await Cattery.Catteries.BuzzAsync(relay));
        Assert.Equal(4, wall.Chime().Value);
    }

    // -----------------------------------------------------------------------------------------
    // `var` flow setters from C#: Kotlin-backed interface and class owner.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public async Task Drain_KotlinBackedChuteSetToACSharpFlow()
    {
        using Cattery.IChute chute = Cattery.Catteries.SteelChute();

        chute.Feed = KotlinFlow<string>.From(Each("salmon", "tuna"));

        Assert.Equal("salmon, tuna", await Cattery.Catteries.DrainAsync(chute));
    }

    [Fact]
    public async Task Hopper_SourceSetToAnotherHoppersKotlinFlow()
    {
        using Cattery.Hopper oreos = Cattery.Catteries.Hopper(["kibble"]);
        using Cattery.Hopper mylos = Cattery.Catteries.Hopper(["tuna", "salmon"]);

        oreos.Source = mylos.Source;

        Assert.Equal("tuna, salmon", await oreos.DrainAsync());
    }

    [Fact]
    public async Task Hopper_SourceSetToACSharpFlow()
    {
        using Cattery.Hopper hopper = Cattery.Catteries.Hopper(["kibble"]);

        hopper.Source = KotlinFlow<string>.From(Each("chicken", "duck"));

        Assert.Equal("chicken, duck", await hopper.DrainAsync());
        Assert.Equal(["chicken", "duck"], await Collect(hopper.Source));
    }

    // -----------------------------------------------------------------------------------------
    // A `var` flow slot whose `KotlinFlow<Paw>` factory entry no other position sights.
    // -----------------------------------------------------------------------------------------

    internal sealed class SnowyPorch : Cattery.IPawprints
    {
        public KotlinFlow<Cattery.Paw> Trail { get; set; } =
            KotlinFlow<Cattery.Paw>.From(Each(new Cattery.Paw(3), new Cattery.Paw(4)));

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Retrace_VarFlowSlotOfAnUnsightedElement()
    {
        var porch = new SnowyPorch();

        Assert.Equal("3, 4 -> 7", await Cattery.Catteries.RetraceAsync(porch, 7));
        List<Cattery.Paw> trail = await Collect(porch.Trail);
        Assert.Equal([7], trail.Select(paw => paw.Size));
    }

    // -----------------------------------------------------------------------------------------
    // A Flow of a `Throwable`, the element the class route carries and the slot did not.
    // -----------------------------------------------------------------------------------------

    internal sealed class Furball : Cattery.IHairballs
    {
        public KotlinFlow<Exception> Coughs() =>
            KotlinFlow<Exception>.From(
                Each<Exception>(new InvalidOperationException("Oreo at 3am"), new ArgumentException("Mylo on the rug")));

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Inspect_FlowOfThrowableFromCSharp() =>
        Assert.Equal("Oreo at 3am, Mylo on the rug", await Cattery.Catteries.InspectAsync(new Furball()));

    // -----------------------------------------------------------------------------------------
    // A class member returning a lambda.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Purrer_ClassMemberReturningALambda()
    {
        using var box = new Cattery.Purrbox("Oreo");

        Func<int, string> purrer = box.Purrer();

        Assert.Equal("Oreo purrs x2", purrer(2));
    }
}

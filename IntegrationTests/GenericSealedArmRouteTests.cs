using TestLibrary.Outcome;

namespace IntegrationTests;

/// <summary>
/// The legacy routes keyed to the arms of a generic sealed hierarchy (ADR-199): ADR-124's flow
/// route and ADR-116's three callback routes. A generic arm has no bare spelling on either half:
/// the Kotlin export reads <c>Dish.Hot&lt;Any?&gt;</c>, and the C# disposed guard names
/// <c>Dish.Hot&lt;T&gt;</c>. The generic base projects no flow member (ADR-147), so <c>Served</c>
/// is each arm's.
///
/// Oreo's dinner is served hot. Mylo licked his plate bare.
/// </summary>
public class GenericSealedArmRouteTests
{
    [Fact]
    public async Task GenericArm_FlowDeclaredOnTheGenericBase_Collects()
    {
        await using var oreo = new Dish.Hot<string>("tuna");

        var served = new List<int>();
        await foreach (int course in oreo.Served) served.Add(course);

        Assert.Equal([1, 2, 3], served);
    }

    [Fact]
    public async Task GenericArm_FlowOfItsTypeParameter_YieldsTheClosedElement()
    {
        await using var oreo = new Dish.Hot<string>("tuna");
        await using var counted = new Dish.Hot<int>(7);

        var portions = new List<string>();
        await foreach (string portion in oreo.Portions) portions.Add(portion);
        var numbers = new List<int>();
        await foreach (int number in counted.Portions) numbers.Add(number);

        Assert.Equal(["tuna", "tuna"], portions);
        Assert.Equal([7, 7], numbers);
    }

    [Fact]
    public async Task GenericArm_MutableStateFlow_ReadsWritesAndCollects()
    {
        await using var oreo = new Dish.Hot<string>("tuna");

        Assert.Equal(60, oreo.Heat.Value);
        oreo.Heat.Value = 45;
        Assert.Equal(45, oreo.Heat.Value);
        Assert.True(oreo.Heat.CompareAndSet(45, 30));

        var cts = new CancellationTokenSource();
        var seen = new List<int>();
        await foreach (int heat in oreo.Heat.WithCancellation(cts.Token))
        {
            seen.Add(heat);
            cts.Cancel();
        }

        Assert.Equal([30], seen);
    }

    [Fact]
    public async Task GenericArm_FlowReturningMethod_Collects()
    {
        await using var oreo = new Dish.Hot<string>("tuna");

        var wafts = new List<int>();
        await foreach (int waft in oreo.Wafts(3)) wafts.Add(waft);

        Assert.Equal([0, 1, 2], wafts);
    }

    [Fact]
    public async Task PhantomArm_FlowAndSuspendMembers_Bind()
    {
        // `Bare : Dish<Nothing>` is not generic in Kotlin; C# declares the phantom `Dish.Bare<T>`.
        await using var mylo = new Dish.Bare<string>(4);

        Assert.Equal(4, mylo.Drips.Value);
        Assert.Equal(4, await mylo.WipeAsync());
        var served = new List<int>();
        await foreach (int course in mylo.Served) served.Add(course);

        Assert.Equal([1, 2, 3], served);
    }

    [Fact]
    public async Task DisposedGenericArm_FlowProperty_ThrowsObjectDisposedNamingTheArm()
    {
        var oreo = new Dish.Hot<string>("tuna");
        await oreo.DisposeAsync();

        ObjectDisposedException thrown = Assert.Throws<ObjectDisposedException>(() => oreo.Served);

        Assert.Equal("Hot", thrown.ObjectName);
    }

    [Fact]
    public void GenericBase_CarriesNoFlowMember()
    {
        Assert.Null(typeof(Dish<int>).GetProperty("Served", System.Reflection.BindingFlags.Public |
            System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.DeclaredOnly));
        Assert.NotNull(typeof(Dish.Hot<int>).GetProperty("Served"));
        Assert.NotNull(typeof(Dish.Bare<int>).GetProperty("Served"));
    }

    // --- Callback routes --------------------------------------------------------------------

    private sealed class Regular : IDiner
    {
        public List<int> Courses { get; } = [];
        public void OnPlated(int course) => Courses.Add(course);
        public void Dispose() { }
    }

    [Fact]
    public async Task GenericArm_PerCallLambda_InvokesTheCallbackPerElement()
    {
        await using var oreo = new Dish.Hot<string>("tuna");

        var letters = new List<char>();
        oreo.Spell(letters.Add);

        Assert.Equal(['t', 'u', 'n', 'a'], letters);
    }

    [Fact]
    public async Task GenericArm_StoredCallbackPair_FiresUntilTheSubscriptionIsDisposed()
    {
        await using var oreo = new Dish.Hot<string>("tuna");
        var rung = new List<int>();

        IDisposable bell = oreo.AddBell(rung.Add);
        Assert.Equal(1, oreo.Plate(1));
        bell.Dispose();
        Assert.Equal(0, oreo.Plate(2));

        Assert.Equal([1], rung);
    }

    [Fact]
    public async Task GenericArm_InterfaceBridgePair_FiresUntilTheSubscriptionIsDisposed()
    {
        await using var oreo = new Dish.Hot<int>(7);
        var mylo = new Regular();

        IDisposable seat = oreo.AddDiner(mylo);
        Assert.Equal(1, oreo.Plate(1));
        seat.Dispose();
        Assert.Equal(0, oreo.Plate(2));

        Assert.Equal([1], mylo.Courses);
    }

    [Fact]
    public async Task DisposedGenericArm_Subscribe_ThrowsObjectDisposedNamingTheArm()
    {
        var oreo = new Dish.Hot<string>("tuna");
        await oreo.DisposeAsync();

        ObjectDisposedException bell = Assert.Throws<ObjectDisposedException>(() => oreo.AddBell(_ => { }));
        ObjectDisposedException seat =
            Assert.Throws<ObjectDisposedException>(() => oreo.AddDiner(new Regular()));

        Assert.Equal("Hot", bell.ObjectName);
        Assert.Equal("Hot", seat.ObjectName);
    }
}

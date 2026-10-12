using TestLibrary.Outcome;

namespace IntegrationTests;

/// <summary>
/// ADR-124's flow route on the arms of a generic sealed hierarchy (ADR-199). A generic arm has no
/// bare spelling on either half: the Kotlin export reads <c>Dish.Hot&lt;Any?&gt;</c>, and the C#
/// disposed guard names <c>Dish.Hot&lt;T&gt;</c>. The generic base projects no flow member
/// (ADR-147), so <c>Served</c> is each arm's.
///
/// Oreo's dinner is served hot. Mylo licked his plate bare.
/// </summary>
public class GenericSealedFlowTests
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
}

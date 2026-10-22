using System.Reflection;
using TestLibrary.Cat;
using TestLibrary.Suspendgeneric;

namespace IntegrationTests;

/// <summary>
/// ADR-214: a <c>suspend fun &lt;T&gt;</c> binds on the suspend route as
/// <c>Task&lt;T&gt; FetchLaterAsync&lt;T&gt;(T value, CancellationToken cancellationToken = default)</c>,
/// each <c>T</c> position on ADR-147's boxed-handle wire. A builtin, a string, a generated wrapper
/// and a C#-implemented interface each reach Kotlin as themselves and come back.
/// <para>
/// Red before ADR-214: <c>fetchLater</c> (one <c>T</c> parameter, <c>T</c> return) lands on the
/// width-variant route and the Kotlin compile fails; every other shape is skipped, so each call
/// below is CS0117/CS1061.
/// </para>
/// Oreo (black with the white middle) and Mylo (brown and creamy) get everything delivered late.
/// </summary>
public class SuspendGenericTests
{
    // Mylo's cardboard box. Kotlin calls `Murmur()` only after a suspension, so after the `_async`
    // extern returned and the `Wrap<T>` transfer handle was disposed.
    private sealed class CardboardBox(string sleeper) : ISnoozer
    {
        public int Murmurs { get; private set; }

        public string Murmur()
        {
            Murmurs++;
            return $"{sleeper} in the box";
        }

        public void Dispose() { }
    }

    [Fact]
    public async Task FetchLaterAsync_IntArgument_RoundTrips()
    {
        // Oreo's age in years.
        Assert.Equal(4, await SuspendGenericSample.FetchLaterAsync(4));
    }

    [Fact]
    public async Task FetchLaterAsync_StringArgument_RoundTrips()
    {
        Assert.Equal("Mylo", await SuspendGenericSample.FetchLaterAsync("Mylo"));
    }

    [Fact]
    public async Task FetchLaterAsync_WrapperArgument_ComesBackAsTheSameKotlinObject()
    {
        using var toy = new Toy("Oreo's Biscuit Ball", "Black");

        using Toy back = await SuspendGenericSample.FetchLaterAsync(toy);

        Assert.Equal("Oreo's Biscuit Ball", back.Name);
        Assert.Equal("Black", back.Color);
    }

    [Fact]
    public async Task FetchLaterAsync_CSharpInterfaceImplementation_ComesBackAsTheSameInstance()
    {
        using var box = new CardboardBox("Mylo");

        Assert.Same(box, await SuspendGenericSample.FetchLaterAsync<ISnoozer>(box));
    }

    [Fact]
    public void FetchLaterAsync_SpellsTheSuspendRouteShape()
    {
        MethodInfo fetch = typeof(SuspendGenericSample).GetMethod("FetchLaterAsync")!;

        Assert.True(fetch.IsGenericMethodDefinition);
        Type t = fetch.GetGenericArguments().Single();
        Assert.Equal(typeof(Task<>).MakeGenericType(t), fetch.ReturnType);
        ParameterInfo[] parameters = fetch.GetParameters();
        Assert.Equal(2, parameters.Length);
        Assert.Equal("value", parameters[0].Name);
        Assert.Equal(t, parameters[0].ParameterType);
        Assert.Equal(typeof(CancellationToken), parameters[1].ParameterType);
        Assert.True(parameters[1].IsOptional);
        // One `_async` export, no width variants left behind.
        Assert.Null(typeof(SuspendGenericSample).GetMethod("FetchLater"));
    }

    [Fact]
    public async Task DescribeLaterAsync_BoundedTypeParameter_ReturnsAString()
    {
        Assert.Equal("4: Int", await SuspendGenericSample.DescribeLaterAsync(4));
        Assert.Equal("Oreo: String", await SuspendGenericSample.DescribeLaterAsync("Oreo"));
        using var toy = new Toy("Mylo's Milo Mouse", "Brown");
        Assert.Equal(
            "Toy(name=Mylo's Milo Mouse, color=Brown): Toy",
            await SuspendGenericSample.DescribeLaterAsync(toy));
    }

    [Fact]
    public async Task PickLaterAsync_TwoTypeParameterArguments_ReturnsSecond()
    {
        // Mylo always goes for the second bowl.
        Assert.Equal(9, await SuspendGenericSample.PickLaterAsync(7, 9));
        Assert.Equal("b", await SuspendGenericSample.PickLaterAsync("a", "b"));
        using var oreoToy = new Toy("Oreo's Biscuit Ball", "Black");
        using var myloToy = new Toy("Mylo's Milo Mouse", "Brown");
        using Toy picked = await SuspendGenericSample.PickLaterAsync(oreoToy, myloToy);
        Assert.Equal("Mylo's Milo Mouse", picked.Name);
    }

    [Fact]
    public async Task PerhapsLaterAsync_NullableTypeParameter_KeepsNull()
    {
        Assert.Null(await SuspendGenericSample.PerhapsLaterAsync<string>(null));
        Assert.Equal("tuna", await SuspendGenericSample.PerhapsLaterAsync<string>("tuna"));
    }

    [Fact]
    public async Task WeighLaterAsync_InBoundNumber_Weighs()
    {
        Assert.Equal(4.0, await SuspendGenericSample.WeighLaterAsync(4));
        Assert.Equal(2.5, await SuspendGenericSample.WeighLaterAsync(2.5));
    }

    // ADR-015's checked cast at the read of the box runs in the suspend prelude, on the caller's
    // thread before `launchForCSharp`. The throw must come back as a faulted Task, never escape the
    // call itself (and never cross the C ABI as an unhandled Kotlin exception).
    [Fact]
    public async Task WeighLaterAsync_OutOfBoundTypeArgument_IsAFaultedTask()
    {
        Task<double>? weighing = null;
        Exception? thrown = Record.Exception(() => { weighing = SuspendGenericSample.WeighLaterAsync("tuna"); });

        Assert.Null(thrown);
        Assert.NotNull(weighing);
        await Assert.ThrowsAsync<KotlinInvalidCastException>(() => weighing!);
        Assert.True(weighing!.IsFaulted);
    }

    [Fact]
    public async Task SnoozeLaterAsync_CSharpInterfaceImplementation_IsCalledBackAfterTheExternReturned()
    {
        var box = new CardboardBox("Mylo");

        Task<string> snoozing = SuspendGenericSample.SnoozeLaterAsync(box);
        // Kotlin is suspended in `delay` here; the extern has returned and its transfer handle is
        // disposed, so the callback below runs against the bridge object alone.
        Assert.Equal(0, box.Murmurs);

        Assert.Equal("zzz Mylo in the box", await snoozing);
        Assert.Equal(1, box.Murmurs);
    }

    [Fact]
    public async Task HoldLaterAsync_Cancellation_CancelsTheTask()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => SuspendGenericSample.HoldLaterAsync("Mylo", cts.Token));
    }

    [Fact]
    public async Task HoldLaterAsync_PreCancelledToken_CancelsTheTask()
    {
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        using var toy = new Toy("Oreo's Biscuit Ball", "Black");

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => SuspendGenericSample.HoldLaterAsync(toy, cts.Token));
    }

    // ADR-212's collections of the function's own `T`, at a suspend parameter and return.
    [Fact]
    public async Task ShuffleLaterAsync_ListOfTypeParameter_ReturnsReversed()
    {
        Assert.Equal(new[] { 3, 2, 1 }, await SuspendGenericSample.ShuffleLaterAsync(new[] { 1, 2, 3 }));
        Assert.Equal(
            new[] { "Mylo", "Oreo" },
            await SuspendGenericSample.ShuffleLaterAsync(new List<string> { "Oreo", "Mylo" }));

        using var oreoToy = new Toy("Oreo's Biscuit Ball", "Black");
        using var myloToy = new Toy("Mylo's Milo Mouse", "Brown");
        IReadOnlyList<Toy> shuffled = await SuspendGenericSample.ShuffleLaterAsync(new[] { oreoToy, myloToy });
        Assert.Equal(2, shuffled.Count);
        using Toy first = shuffled[0];
        using Toy second = shuffled[1];
        Assert.Equal("Mylo's Milo Mouse", first.Name);
        Assert.Equal("Oreo's Biscuit Ball", second.Name);
    }

    [Fact]
    public async Task DedupeLaterAsync_SetOfTypeParameter_RoundTrips()
    {
        IReadOnlySet<string> naps = await SuspendGenericSample.DedupeLaterAsync(new HashSet<string> { "Oreo", "Mylo" });

        Assert.True(naps.SetEquals(new[] { "Mylo", "Oreo" }));
    }

    [Fact]
    public async Task KeepLaterAsync_MapOfTypeParameters_RoundTrips()
    {
        var treats = new Dictionary<string, int> { ["Oreo"] = 2, ["Mylo"] = 3 };
        IReadOnlyDictionary<string, int> kept = await SuspendGenericSample.KeepLaterAsync(treats);
        Assert.Equal(2, kept["Oreo"]);
        Assert.Equal(3, kept["Mylo"]);

        var ages = new Dictionary<int, string?> { [4] = "Oreo", [3] = null };
        IReadOnlyDictionary<int, string?> keptAges = await SuspendGenericSample.KeepLaterAsync(ages);
        Assert.Equal("Oreo", keptAges[4]);
        Assert.Null(keptAges[3]);
    }

    // A non-generic class's own suspend generics: instance methods on the class route.
    [Fact]
    public async Task Courier_FerryAsync_RoundTripsEveryKind()
    {
        await using var courier = new Courier();
        using var toy = new Toy("Mylo's Milo Mouse", "Brown");
        using var box = new CardboardBox("Oreo");

        Assert.Equal(4, await courier.FerryAsync(4));
        Assert.Equal("Mylo", await courier.FerryAsync("Mylo"));
        using Toy back = await courier.FerryAsync(toy);
        Assert.Equal("Mylo's Milo Mouse", back.Name);
        Assert.Same(box, await courier.FerryAsync<ISnoozer>(box));
    }

    [Fact]
    public async Task Courier_FerryAllAsync_ListOfTypeParameter_ReturnsReversed()
    {
        await using var courier = new Courier();

        Assert.Equal(new[] { "Mylo", "Oreo" }, await courier.FerryAllAsync(new[] { "Oreo", "Mylo" }));
    }

    [Fact]
    public async Task Courier_StallAsync_Cancellation_CancelsTheTask()
    {
        await using var courier = new Courier();
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => courier.StallAsync(7, cts.Token));
    }

    // A non-generic sealed arm's own suspend generic.
    [Fact]
    public async Task Porch_LeaveAsync_RoundTripsEveryKind()
    {
        await using var porch = new Delivery.Porch("welcome mat");
        using var toy = new Toy("Oreo's Biscuit Ball", "Black");

        Assert.Equal(9, await porch.LeaveAsync(9));
        Assert.Equal("Oreo", await porch.LeaveAsync("Oreo"));
        using Toy back = await porch.LeaveAsync(toy);
        Assert.Equal("Oreo's Biscuit Ball", back.Name);
    }
}

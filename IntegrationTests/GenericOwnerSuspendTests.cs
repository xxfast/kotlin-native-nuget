using System.Reflection;
using TestLibrary;
using TestLibrary.Membergeneric;
using TestLibrary.Rankings;

namespace IntegrationTests;

/// <summary>
/// ADR-219: the <c>suspend</c> members of a generic class and a generic sealed arm bind on the
/// suspend route over the erased receiver, so <c>Platter&lt;T&gt;</c> gets
/// <c>Task&lt;T&gt; TakeAsync(CancellationToken cancellationToken = default)</c> and every owner
/// <c>T</c> crosses on ADR-214's boxed shapes. An <c>int</c>, a <c>string</c> and a <c>Tabby</c>
/// wrapper each come out as themselves; a bounded owner reads through its first bound, a
/// multi-bound owner through <c>&lt;*&gt;</c> and ADR-217's trampoline.
/// <para>
/// Red before ADR-219: ADR-147's early return in <c>forwardSuspendRouteMethods</c> projects no
/// suspend member for a generic owner, so every member below is CS1061, and
/// <c>Buffet.Cart&lt;T&gt;</c> (a generic arm implementing <c>IGrazer</c>) is the ROADMAP CS0535
/// build break. <c>Pantries.RateLaterAsync</c> is the trampolined suspend <c>T</c> parameter ADR-214
/// left refused (CS0117).
/// </para>
/// Oreo (black with the white middle) and Mylo (brown and creamy) raid the pantry, eventually.
/// </summary>
public class GenericOwnerSuspendTests
{
    // A furry guest written in C#. Kotlin reads `Name` only after a suspension, so after the
    // `_async` extern returned and the `Wrap<T>` transfer handle was disposed.
    private sealed class PlushMylo : IFurry
    {
        public int Reads { get; private set; }

        public string Name
        {
            get
            {
                Reads++;
                return "Plush Mylo";
            }
        }

        public void Dispose() { }
    }

    // (1) An unbounded owner `T`: return, parameter, nullable, untouched, member-own `U`.

    [Fact]
    public async Task Platter_TakeAsync_IntServing_RoundTrips()
    {
        // Oreo's age in years.
        await using var platter = new Platter<int>(4);

        Assert.Equal(4, await platter.TakeAsync());
    }

    [Fact]
    public async Task Platter_TakeAsync_StringServing_RoundTrips()
    {
        await using var platter = new Platter<string>("tuna");

        Assert.Equal("tuna", await platter.TakeAsync());
    }

    [Fact]
    public async Task Platter_TakeAsync_WrapperServing_ComesBackAsTheSameKotlinObject()
    {
        using var oreo = new Tabby("Oreo", 3);
        await using var platter = new Platter<Tabby>(oreo);

        using Tabby back = await platter.TakeAsync();

        Assert.Equal("Oreo", back.Name);
        Assert.Equal(3, back.Tricks);
    }

    [Fact]
    public async Task Platter_PutAsync_TypeParameterArgument_IsWhatTakeReadsBack()
    {
        await using var ints = new Platter<int>(4);
        await ints.PutAsync(3);
        Assert.Equal(3, await ints.TakeAsync());

        await using var strings = new Platter<string>("tuna");
        await strings.PutAsync("salmon");
        Assert.Equal("salmon", await strings.TakeAsync());

        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        await using var cats = new Platter<Tabby>(oreo);
        await cats.PutAsync(mylo);
        using Tabby back = await cats.TakeAsync();
        Assert.Equal("Mylo", back.Name);
    }

    [Fact]
    public async Task Platter_PerhapsAsync_NullableTypeParameter_KeepsNull()
    {
        await using var platter = new Platter<string>("tuna");

        Assert.Equal("tuna", await platter.PerhapsAsync(true));
        Assert.Null(await platter.PerhapsAsync(false));
    }

    [Fact]
    public async Task Platter_CountAsync_MemberNotMentioningT_Binds()
    {
        await using var platter = new Platter<string>("Mylo");

        Assert.Equal(4, await platter.CountAsync());
    }

    [Fact]
    public async Task Platter_SwapAsync_MemberOwnTypeParameter_RoundTripsEveryKind()
    {
        await using var platter = new Platter<int>(4);
        using var mylo = new Tabby("Mylo", 1);

        Assert.Equal("Oreo", await platter.SwapAsync("Oreo"));
        Assert.Equal(2.5, await platter.SwapAsync(2.5));
        using Tabby back = await platter.SwapAsync(mylo);
        Assert.Equal("Mylo", back.Name);
    }

    [Fact]
    public void Platter_TakeAsync_SpellsTheSuspendRouteShape()
    {
        MethodInfo take = typeof(Platter<string>).GetMethod("TakeAsync")!;

        Assert.Equal(typeof(Task<string>), take.ReturnType);
        ParameterInfo parameter = take.GetParameters().Single();
        Assert.Equal(typeof(CancellationToken), parameter.ParameterType);
        Assert.True(parameter.IsOptional);
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Platter<string>)));
    }

    // (2) Collections of the owner `T` at suspend positions.

    [Fact]
    public async Task Platter_StackAsync_ListOfTypeParameter_AppendsTheServing()
    {
        await using var ints = new Platter<int>(3);
        Assert.Equal(new[] { 1, 2, 3 }, await ints.StackAsync(new[] { 1, 2 }));

        await using var strings = new Platter<string>("Mylo");
        Assert.Equal(new[] { "Oreo", "Mylo" }, await strings.StackAsync(new List<string> { "Oreo" }));

        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        await using var cats = new Platter<Tabby>(mylo);
        IReadOnlyList<Tabby> stacked = await cats.StackAsync(new[] { oreo });
        Assert.Equal(2, stacked.Count);
        using Tabby first = stacked[0];
        using Tabby second = stacked[1];
        Assert.Equal("Oreo", first.Name);
        Assert.Equal("Mylo", second.Name);
    }

    [Fact]
    public async Task Platter_SortAsync_SetOfTypeParameter_AddsTheServing()
    {
        await using var platter = new Platter<string>("Mylo");

        IReadOnlySet<string> sorted = await platter.SortAsync(new HashSet<string> { "Oreo" });

        Assert.True(sorted.SetEquals(new[] { "Oreo", "Mylo" }));
    }

    // (3) A trampolined member-own `M : Enum<M>` on the suspend route.

    [Fact]
    public async Task Platter_RateAsync_EnumBoundParameter_ComesBackAsTheEnum()
    {
        await using var platter = new Platter<string>("tuna");

        Assert.Equal(Medal.Gold, await platter.RateAsync(Medal.Gold));
        Assert.Equal(Medal.Silver, await platter.RateAsync(Medal.Silver));
    }

    [Fact]
    public async Task Platter_RateAsync_Bronze_IsAFaultedTask()
    {
        await using var platter = new Platter<string>("tuna");

        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(() => platter.RateAsync(Medal.Bronze));

        Assert.Equal("BRONZE is no prize", ex.Message);
        Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public async Task Pantries_RateLaterAsync_TopLevelEnumBoundParameter_ComesBackAsTheEnum()
    {
        Assert.Equal(Medal.Gold, await Pantries.RateLaterAsync(Medal.Gold));

        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(() => Pantries.RateLaterAsync(Medal.Bronze));
        Assert.Equal("BRONZE is no prize", ex.Message);
    }

    // (4) Cancellation with the owner `T` in flight.

    [Fact]
    public async Task Platter_LingerAsync_Cancellation_CancelsTheTask()
    {
        await using var platter = new Platter<string>("Mylo");
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => platter.LingerAsync(cts.Token));
    }

    [Fact]
    public async Task Platter_LingerAsync_PreCancelledToken_CancelsTheTask()
    {
        using var oreo = new Tabby("Oreo", 3);
        await using var platter = new Platter<Tabby>(oreo);
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => platter.LingerAsync(cts.Token));
    }

    // (5) A bounded owner `T : Furry`, read through its first bound.

    [Fact]
    public async Task Banquet_SeatAsync_BoundedOwner_ReturnsTheWrapper()
    {
        using var oreo = new Tabby("Oreo", 3);
        await using var banquet = new Banquet<Tabby>(oreo);

        using Tabby seated = await banquet.SeatAsync();

        Assert.Equal("Oreo", seated.Name);
    }

    [Fact]
    public async Task Banquet_GreetAsync_BoundedParameter_ReadsTheBound()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        await using var banquet = new Banquet<Tabby>(oreo);

        Assert.Equal("Oreo greets Mylo", await banquet.GreetAsync(mylo));
        using Tabby replaced = await banquet.ReplaceAsync(mylo);
        Assert.Equal("Mylo", replaced.Name);
    }

    [Fact]
    public async Task Banquet_GreetAsync_CSharpImplementation_IsCalledBackAfterTheExternReturned()
    {
        using var oreo = new Tabby("Oreo", 3);
        await using var banquet = new Banquet<IFurry>(oreo);
        var plush = new PlushMylo();

        Task<string> greeting = banquet.GreetAsync(plush);
        // Kotlin is suspended in `delay`; the extern has returned and its transfer handle is gone.
        Assert.Equal(0, plush.Reads);

        Assert.Equal("Oreo greets Plush Mylo", await greeting);
        Assert.Equal(1, plush.Reads);
    }

    // (6) A multi-bound owner (`where T : Furry, T : Tricky`).

    [Fact]
    public async Task Picnic_HostAsync_MultiBoundOwner_ReturnsTheWrapper()
    {
        using var oreo = new Tabby("Oreo", 3);
        await using var picnic = new Picnic<Tabby>(oreo);

        using Tabby host = await picnic.HostAsync();

        Assert.Equal("Oreo", host.Name);
    }

    [Fact]
    public async Task Picnic_InviteAsync_MultiBoundParameter_UsesBothBounds()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        await using var picnic = new Picnic<Tabby>(oreo);

        Assert.Equal("Mylo:1", await picnic.InviteAsync(mylo));
    }

    [Fact]
    public async Task Picnic_OutshineAsync_MultiBoundParameterAndReturn_PicksTheTrickier()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        using var tricksterMylo = new Tabby("Mylo", 5);
        await using var picnic = new Picnic<Tabby>(oreo);

        using Tabby stays = await picnic.OutshineAsync(mylo);
        Assert.Equal(3, stays.Tricks);
        using Tabby wins = await picnic.OutshineAsync(tricksterMylo);
        Assert.Equal(5, wins.Tricks);
    }

    // (7) A generic sealed arm's own suspend members.

    [Fact]
    public async Task Tureen_ServeAsync_RoundTripsEveryKind()
    {
        await using var ints = new Buffet.Tureen<int>(4);
        Assert.Equal(4, await ints.ServeAsync());
        Assert.Equal(3, await ints.PourAsync(3));

        await using var strings = new Buffet.Tureen<string>("broth");
        Assert.Equal("broth", await strings.ServeAsync());
        Assert.Equal("gravy", await strings.PourAsync("gravy"));

        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        await using var cats = new Buffet.Tureen<Tabby>(oreo);
        using Tabby served = await cats.ServeAsync();
        Assert.Equal("Oreo", served.Name);
        using Tabby poured = await cats.PourAsync(mylo);
        Assert.Equal("Mylo", poured.Name);
    }

    [Fact]
    public async Task Tureen_GarnishAsync_MemberOwnTypeParameter_OnAGenericArm()
    {
        await using var tureen = new Buffet.Tureen<string>("broth");

        Assert.Equal("broth with 4", await tureen.GarnishAsync(4));
        Assert.Equal("broth with catnip", await tureen.GarnishAsync("catnip"));
    }

    [Fact]
    public async Task Tureen_SimmerAsync_Cancellation_CancelsTheTask()
    {
        await using var tureen = new Buffet.Tureen<string>("broth");
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => tureen.SimmerAsync(cts.Token));
    }

    // (8) The ROADMAP Phase 6 CS0535 shape: a generic arm implementing an exported interface with a
    // `suspend` member (`IGrazer`, bound because `Pantries.CartOf` returns it). Through the class
    // and through the interface.

    [Fact]
    public async Task Cart_GrazeAsync_GenericArmImplementingASuspendInterface_Binds()
    {
        await using var cart = new Buffet.Cart<string>("kibble");

        Assert.Equal(6, await cart.GrazeAsync());
        IGrazer grazer = cart;
        Assert.Equal(6, await grazer.GrazeAsync());
    }

    [Fact]
    public async Task Pantries_CartOf_ReturnsTheArmAsTheInterface()
    {
        // Mylo's kibble trolley, handed back as the interface it implements.
        using IGrazer grazer = Pantries.CartOf("kibble");

        Assert.Equal(6, await grazer.GrazeAsync());
    }
}

using TestLibrary;
using TestLibrary.Membergeneric;

namespace IntegrationTests;

/// <summary>
/// ADR-215 lane A: a function's own <c>T</c>, or its owner's <c>T</c>, inside a non-suspend
/// lambda. A <c>T</c> payload reaches the C# lambda as itself (a builtin or a fresh wrapper the
/// lambda owns), and a <c>T</c> the C# lambda returns reaches Kotlin as an owned box, including a
/// wrapper created inside the lambda that nothing in C# holds once the lambda returns.
/// </summary>
public class MemberGenericLambdaTests
{
    // --- fun <T> each(items: List<T>, action: (T) -> Unit) ---

    [Fact]
    public void Each_BuiltinPayloads_ArriveInOrder()
    {
        using var dealer = new Dealer();

        var ages = new List<int>();
        dealer.Each(new[] { 4, 6 }, age => ages.Add(age));
        Assert.Equal(new[] { 4, 6 }, ages);

        var names = new List<string>();
        dealer.Each(new[] { "Oreo", "Mylo" }, name => names.Add(name));
        Assert.Equal(new[] { "Oreo", "Mylo" }, names);
    }

    [Fact]
    public void Each_WrapperPayload_IsOwnedByTheLambda()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        var tricks = new List<string>();
        dealer.Each(new[] { oreo, mylo }, cat =>
        {
            using (cat) tricks.Add($"{cat.Name}:{cat.Tricks}");
        });
        Assert.Equal(new[] { "Oreo:3", "Mylo:5" }, tricks);
    }

    [Fact]
    public void Each_NullableTypeArgument_HandsTheLambdaANull()
    {
        using var dealer = new Dealer();

        var seats = new List<string?>();
        dealer.Each(new string?[] { "Oreo", null, "Mylo" }, seat => seats.Add(seat));
        Assert.Equal(new string?[] { "Oreo", null, "Mylo" }, seats);
    }

    [Fact]
    public void Each_IsSpelledAsAGenericMethodTakingAnAction()
    {
        var each = typeof(Dealer).GetMethod("Each")!;
        Assert.True(each.IsGenericMethodDefinition);
        var t = each.GetGenericArguments().Single();
        var parameters = each.GetParameters();
        Assert.Equal(typeof(IReadOnlyList<>).MakeGenericType(t), parameters[0].ParameterType);
        Assert.Equal(typeof(Action<>).MakeGenericType(t), parameters[1].ParameterType);
    }

    // --- fun <T> map(value: T, transform: (T) -> T): T ---

    [Fact]
    public void Map_BuiltinResults_ComeBackFromTheLambda()
    {
        using var dealer = new Dealer();

        // Oreo is 4; Mylo is twice as much trouble.
        Assert.Equal(8, dealer.Map(4, age => age * 2));
        Assert.Equal("Oreo and Mylo", dealer.Map("Oreo", name => $"{name} and Mylo"));
        Assert.Equal(2.5, dealer.Map(1.25, scoop => scoop * 2));
    }

    [Fact]
    public void Map_LambdaReturnsItsOwnPayload_ComesBackAsTheSameCat()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);

        using Tabby same = dealer.Map(oreo, cat => cat);
        Assert.Equal("Oreo", same.Name);
        Assert.Equal(3, same.Tricks);
    }

    [Fact]
    public void Map_LambdaReturnsABorrowedWrapper_StaysAliveAfterKotlinDisposesItsBox()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        // Mylo is a live wrapper C# still owns, so the box handed to Kotlin is a retain of it.
        using Tabby swapped = dealer.Map(oreo, cat =>
        {
            cat.Dispose();
            return mylo;
        });
        Assert.Equal("Mylo", swapped.Name);
        // Kotlin disposed its own box, not Mylo's handle.
        Assert.Equal(5, mylo.Tricks);
    }

    [Fact]
    public void Map_LambdaReturnsAFreshWrapper_ComesBackAsThatCat()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);

        using Tabby fresh = dealer.Map(oreo, cat =>
        {
            cat.Dispose();
            return new Tabby("Mylo", 5);
        });
        Assert.Equal("Mylo", fresh.Name);
        Assert.Equal(5, fresh.Tricks);
    }

    // The finalizer race the always-owned transfer exists for (ADR-215 pitfall 1): the fresh Tabby
    // is unreachable the instant the lambda returns, and `Rename` hands back only a string, so
    // nothing in C# ever holds it again. A background thread keeps the GC and finalizer busy so a
    // borrowed-handle transfer would have its handle released before Kotlin read the box.
    [Fact]
    public async Task Rename_LambdaReturnsAFreshWrapperUnderGcPressure_KotlinStillReadsIt()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);
        using var stop = new CancellationTokenSource();
        var pressure = Task.Run(() =>
        {
            while (!stop.IsCancellationRequested)
            {
                GC.Collect();
                GC.WaitForPendingFinalizers();
            }
        });

        try
        {
            for (int i = 0; i < 500; i++)
            {
                string name = dealer.Rename(oreo, cat =>
                {
                    cat.Dispose();
                    return new Tabby($"Mylo {i}", i);
                });
                Assert.Equal($"Mylo {i}", name);
            }
        }
        finally
        {
            stop.Cancel();
            await pressure;
        }
    }

    [Fact]
    public void Map_NullableTypeArgument_NullCrossesBothWays()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);

        Assert.Null(dealer.Map<string?>(null, seat => seat));
        Assert.Null(dealer.Map<string?>("Oreo", _ => null));
        Assert.Equal("Mylo", dealer.Map<string?>(null, seat => seat ?? "Mylo"));
        Assert.Null(dealer.Map<Tabby?>(oreo, cat =>
        {
            cat?.Dispose();
            return null;
        }));
    }

    [Fact]
    public void Map_IsSpelledAsAGenericMethodTakingAFunc()
    {
        var map = typeof(Dealer).GetMethod("Map")!;
        var t = map.GetGenericArguments().Single();
        Assert.Equal(t, map.ReturnType);
        Assert.Equal(typeof(Func<,>).MakeGenericType(t, t), map.GetParameters()[1].ParameterType);
    }

    // --- fun <T> fold(items: List<T>, initial: T, combine: (T, T) -> T): T ---

    [Fact]
    public void Fold_TwoPayloadsAndAResult_PerInvocation()
    {
        using var dealer = new Dealer();

        Assert.Equal(10, dealer.Fold(new[] { 1, 2, 3, 4 }, 0, (sum, age) => sum + age));
        Assert.Equal("Oreo>Mylo", dealer.Fold(new[] { "Mylo" }, "Oreo", (a, b) => $"{a}>{b}"));
    }

    [Fact]
    public void Fold_WrapperAccumulator_KeepsTheTrickiestCat()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        using Tabby best = dealer.Fold(new[] { mylo }, oreo, (a, b) =>
        {
            if (a.Tricks >= b.Tricks)
            {
                b.Dispose();
                return a;
            }
            a.Dispose();
            return b;
        });
        Assert.Equal("Mylo", best.Name);
    }

    // --- fun <T : Furry> rename(pet: T, pick: (T) -> T): String ---

    [Fact]
    public void Rename_BoundedResult_IsCastToTheBoundOnKotlinsSide()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);

        Assert.Equal("Oreo", dealer.Rename(oreo, cat => cat));
    }

    // --- fun <T : Any> peek(seat: T?, look: (T?) -> Unit) ---

    [Fact]
    public void Peek_ExplicitNullablePayload_ArrivesAsNullOrAsItself()
    {
        using var dealer = new Dealer();

        string? seen = "unset";
        dealer.Peek<string>(null, seat => seen = seat);
        Assert.Null(seen);
        dealer.Peek<string>("Mylo", seat => seen = seat);
        Assert.Equal("Mylo", seen);
    }

    // --- fun <T : Any> fill(seat: T?, fallback: () -> T): T ---

    [Fact]
    public void Fill_ResultOnlyLambda_IsCalledOnlyForAnEmptySeat()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);

        int asked = 0;
        Assert.Equal("Oreo", dealer.Fill<string>("Oreo", () => { asked++; return "Mylo"; }));
        Assert.Equal(0, asked);
        Assert.Equal("Mylo", dealer.Fill<string>(null, () => { asked++; return "Mylo"; }));
        Assert.Equal(1, asked);

        using Tabby sitting = dealer.Fill(oreo, () => new Tabby("Mylo", 5));
        Assert.Equal("Oreo", sitting.Name);
        using Tabby fetched = dealer.Fill<Tabby>(null, () => new Tabby("Mylo", 5));
        Assert.Equal("Mylo", fetched.Name);
    }

    // --- fun <T : Enum<T>> restyle(tint: T, step: (T) -> T): String (ADR-198 trampoline) ---

    [Fact]
    public void Restyle_TrampolinedEnumTypeParameter_CrossesBothWays()
    {
        using var dealer = new Dealer();

        Tint seen = Tint.Cream;
        Assert.Equal("MOCHA", dealer.Restyle(Tint.Tuxedo, tint =>
        {
            seen = tint;
            return Tint.Mocha;
        }));
        Assert.Equal(Tint.Tuxedo, seen);
    }

    // --- fun <T> deal(item: T, count: Int, done: (Int) -> Unit): T ---

    [Fact]
    public void Deal_LambdaThatDoesNotMentionT_BindsOnAGenericMember()
    {
        using var dealer = new Dealer();
        using var oreo = new Tabby("Oreo", 3);

        var dealt = new List<int>();
        Assert.Equal("Mylo", dealer.Deal("Mylo", 3, index => dealt.Add(index)));
        Assert.Equal(new[] { 0, 1, 2 }, dealt);
        using Tabby back = dealer.Deal(oreo, 1, _ => { });
        Assert.Equal("Oreo", back.Name);
    }

    // --- fun <T> echoer(): (T) -> T (ADR-211 top-level, ReturnedLambda) ---

    [Fact]
    public void Echoer_ReturnedLambda_EchoesBuiltinsAndWrappers()
    {
        Func<int, int> ages = Decks.Echoer<int>();
        Assert.Equal(4, ages.Invoke(4));

        Func<string, string> names = Decks.Echoer<string>();
        Assert.Equal("Oreo", names.Invoke("Oreo"));

        using var mylo = new Tabby("Mylo", 5);
        Func<Tabby, Tabby> cats = Decks.Echoer<Tabby>();
        using Tabby back = cats.Invoke(mylo);
        Assert.Equal("Mylo", back.Name);
    }

    // --- class Serving<T>: the generic class's own T in a callback member ---

    [Fact]
    public void Serving_GenericClassCallbacks_SeeTheClassTypeArgument()
    {
        using var tuna = new Serving<string>("tuna");
        var bites = new List<string>();
        tuna.Each(bite => bites.Add(bite));
        Assert.Equal(new[] { "tuna", "tuna" }, bites);
        Assert.Equal("TUNA", tuna.Map(portion => portion.ToUpperInvariant()));
        Assert.Equal("tuna for Mylo", tuna.Pair("Mylo", (portion, cat) => $"{portion} for {cat}"));

        using var scoops = new Serving<int>(2);
        Assert.Equal(6, scoops.Map(n => n * 3));
        Assert.Equal("2x4", scoops.Pair(4, (n, m) => $"{n}x{m}"));
    }

    [Fact]
    public void Serving_WrapperTypeArgument_PayloadsAndResultsCross()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var forOreo = new Serving<Tabby>(oreo);

        var names = new List<string>();
        forOreo.Each(cat =>
        {
            using (cat) names.Add(cat.Name);
        });
        Assert.Equal(new[] { "Oreo", "Oreo" }, names);

        using Tabby fresh = forOreo.Map(cat =>
        {
            cat.Dispose();
            return new Tabby("Mylo", 5);
        });
        Assert.Equal("Mylo", fresh.Name);
    }

    [Fact]
    public void Serving_NullableTypeArgument_NullPortionReachesTheLambda()
    {
        using var empty = new Serving<string?>(null);

        var bites = new List<string?>();
        empty.Each(bite => bites.Add(bite));
        Assert.Equal(new string?[] { null, null }, bites);
        Assert.Equal("Mylo", empty.Map(portion => portion ?? "Mylo"));
    }

    // --- sealed class Helping<out T> { class Seconds<T> }: a generic sealed arm's callbacks ---

    [Fact]
    public void Seconds_GenericSealedArmCallbacks_Bind()
    {
        using var seconds = new Helping.Seconds<string>("tuna");
        string? bite = null;
        seconds.Each(portion => bite = portion);
        Assert.Equal("tuna", bite);
        Assert.Equal("tuna again", seconds.Map(portion => $"{portion} again"));

        using var mylo = new Tabby("Mylo", 5);
        using var forMylo = new Helping.Seconds<Tabby>(mylo);
        using Tabby served = forMylo.Map(cat => cat);
        Assert.Equal("Mylo", served.Name);
    }
}

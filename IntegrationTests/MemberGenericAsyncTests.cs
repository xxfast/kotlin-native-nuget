using System.Runtime.CompilerServices;
using TestLibrary.Membergeneric;

namespace IntegrationTests;

/// <summary>
/// ADR-215 lane C: a function's own <c>T</c>, or its owner's <c>T</c>, flowing INTO Kotlin
/// asynchronously. A <c>Flow&lt;T&gt;</c> parameter takes an <c>IAsyncEnumerable&lt;T&gt;</c>
/// whose every element reaches Kotlin as an owned box (<c>CurrentBoxed()</c>), a
/// <c>suspend (T) -&gt; T</c> lambda's result reaches Kotlin as an owned box (<c>EndBoxed()</c>),
/// and a <c>suspend (T) -&gt; Unit</c> payload reaches the C# lambda as itself. Every Kotlin member
/// hands its <c>T</c>s back, so a wrapper proves it is still the same cat.
/// <para>
/// <c>DribbleAsync</c> is the control: a <c>suspend fun &lt;T&gt;</c> with no protocol at all.
/// </para>
/// Oreo drinks from the siphon. Mylo knocks the cistern over.
/// </summary>
public class MemberGenericAsyncTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(30);

    /// <summary>What a source did, read once the Kotlin call is over.</summary>
    private sealed class Tap
    {
        public int Yielded;
        public int Finalised;
        public readonly TaskCompletionSource Closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly TaskCompletionSource FirstYield = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly TaskCompletionSource Parked = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly TaskCompletionSource<bool> TokenCancelled = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    /// <summary>Really suspends before every element, and records its <c>finally</c>.</summary>
    private static async IAsyncEnumerable<T> Pour<T>(
        Tap tap, T[] items, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            foreach (T item in items)
            {
                await Task.Delay(5, token);
                Interlocked.Increment(ref tap.Yielded);
                tap.FirstYield.TrySetResult();
                yield return item;
            }
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    private static IAsyncEnumerable<T> Pour<T>(params T[] items) => Pour(new Tap(), items);

    /// <summary>One element, then parks on nothing but its own token.</summary>
    private static async IAsyncEnumerable<T> PourThenWaitOnTheToken<T>(
        Tap tap, T first, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            Interlocked.Increment(ref tap.Yielded);
            yield return first;

            tap.Parked.TrySetResult();
            try
            {
                await Task.Delay(Timeout.Infinite, token);
            }
            catch (OperationCanceledException)
            {
                tap.TokenCancelled.TrySetResult(token.IsCancellationRequested);
                throw;
            }
            yield return first;
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    // --- suspend fun <T> dribble(item: T): T, the control ---

    [Fact]
    public async Task Dribble_SuspendGenericMemberWithoutAProtocol_RoundTrips()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);

        Assert.Equal(4, await siphon.DribbleAsync(4));
        Assert.Equal("Mylo", await siphon.DribbleAsync("Mylo"));
        using Tabby same = await siphon.DribbleAsync(oreo);
        Assert.Equal("Oreo", same.Name);
    }

    // --- suspend fun <T> gulp(source: Flow<T>): List<T> ---

    [Fact]
    public void Gulp_IsSpelledAsAGenericMethodTakingAnAsyncEnumerable()
    {
        var gulp = typeof(Siphon).GetMethod("GulpAsync")!;
        Assert.True(gulp.IsGenericMethodDefinition);
        var t = gulp.GetGenericArguments().Single();
        var parameters = gulp.GetParameters();
        Assert.Equal(typeof(IAsyncEnumerable<>).MakeGenericType(t), parameters[0].ParameterType);
        Assert.Equal(typeof(CancellationToken), parameters[1].ParameterType);
        Assert.Equal(
            typeof(Task<>).MakeGenericType(typeof(IReadOnlyList<>).MakeGenericType(t)),
            gulp.ReturnType);
    }

    [Fact]
    public async Task Gulp_BuiltinElements_ComeBackInOrder()
    {
        using var siphon = new Siphon();
        var tap = new Tap();

        // Oreo drinks 3 laps, Mylo 5, Oreo 2 more.
        Assert.Equal(new[] { 3, 5, 2 }, await siphon.GulpAsync(Pour(tap, new[] { 3, 5, 2 })));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(3, tap.Yielded);
        Assert.Equal(1, tap.Finalised);

        Assert.Equal(new[] { "Oreo", "Mylo" }, await siphon.GulpAsync(Pour("Oreo", "Mylo")));
        Assert.Equal(new[] { 1.5, 0.25 }, await siphon.GulpAsync(Pour(1.5, 0.25)));
    }

    [Fact]
    public async Task Gulp_WrapperElements_ComeBackAsTheSameCats()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        IReadOnlyList<Tabby> drank = await siphon.GulpAsync(Pour(oreo, mylo));
        try
        {
            Assert.Equal(new[] { "Oreo:3", "Mylo:5" }, drank.Select(cat => $"{cat.Name}:{cat.Tricks}"));
        }
        finally
        {
            foreach (Tabby cat in drank) cat.Dispose();
        }
        // The source's wrappers were borrowed, so they are still Oreo's and Mylo's own.
        Assert.Equal("Oreo", oreo.Name);
        Assert.Equal("Mylo", mylo.Name);
    }

    [Fact]
    public async Task Gulp_NullableTypeArgument_CarriesTheNullsThrough()
    {
        using var siphon = new Siphon();

        Assert.Equal(
            new string?[] { "Oreo", null, "Mylo" },
            await siphon.GulpAsync<string?>(Pour<string?>("Oreo", null, "Mylo")));
    }

    [Fact]
    public async Task Skim_ExplicitNullableElementUnderANonNullBound_DropsTheNulls()
    {
        using var siphon = new Siphon();

        Assert.Equal(
            new[] { "Oreo", "Mylo" },
            await siphon.SkimAsync<string>(Pour<string?>(null, "Oreo", null, "Mylo")));
    }

    [Fact]
    public async Task Names_BoundedElement_IsCastToTheBoundBeforeKotlinReadsIt()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        Assert.Equal("Oreo,Mylo", await siphon.NamesAsync(Pour(oreo, mylo)));
    }

    [Fact]
    public async Task Tints_TrampolinedElement_CrossesIntoKotlin()
    {
        using var siphon = new Siphon();

        Assert.Equal("TUXEDO,MOCHA", await siphon.TintsAsync(Pour(Tint.Tuxedo, Tint.Mocha)));
    }

    // --- cancellation mid-way through a Flow<T> parameter ---

    [Fact]
    public async Task Sip_KotlinStopsHalfWay_DisposesTheSourceOnce()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);
        var tap = new Tap();

        IReadOnlyList<Tabby> sipped = await siphon.SipAsync(Pour(tap, new[] { oreo, mylo, oreo, mylo }), 2);
        try
        {
            Assert.Equal(new[] { "Oreo", "Mylo" }, sipped.Select(cat => cat.Name));
        }
        finally
        {
            foreach (Tabby cat in sipped) cat.Dispose();
        }
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(2, tap.Yielded);
        Assert.Equal(1, tap.Finalised);
    }

    [Fact]
    public async Task Gulp_OuterTokenCancelledMidStep_CancelsTheSourcesToken()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var cts = new CancellationTokenSource();
        var tap = new Tap();

        Task<IReadOnlyList<Tabby>> gulping = siphon.GulpAsync(PourThenWaitOnTheToken(tap, oreo), cts.Token);
        await tap.Parked.Task.WaitAsync(Patience);
        Assert.False(tap.TokenCancelled.Task.IsCompleted, "the source's token was cancelled before the outer one");
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => gulping);
        Assert.True(await tap.TokenCancelled.Task.WaitAsync(Patience));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
        Assert.Equal("Oreo", oreo.Name);
    }

    [Fact]
    public async Task Lap_OuterTokenCancelledBetweenPulls_DisposesTheSource()
    {
        using var siphon = new Siphon();
        using var cts = new CancellationTokenSource();
        var tap = new Tap();

        // A minute per element on the Kotlin side: Mylo is still on his first lap when the cancel lands.
        Task<IReadOnlyList<string>> lapping =
            siphon.LapAsync(60_000, Pour(tap, new[] { "Mylo", "Oreo", "Mylo" }), cts.Token);
        await tap.FirstYield.Task.WaitAsync(Patience);
        await Task.Delay(50);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => lapping);
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Yielded);
        Assert.Equal(1, tap.Finalised);
    }

    // --- suspend fun <T> brew(value: T, step: suspend (T) -> T): T ---

    [Fact]
    public void Brew_IsSpelledAsAGenericMethodTakingASuspendFunc()
    {
        var brew = typeof(Siphon).GetMethod("BrewAsync")!;
        Assert.True(brew.IsGenericMethodDefinition);
        var t = brew.GetGenericArguments().Single();
        var parameters = brew.GetParameters();
        Assert.Equal(t, parameters[0].ParameterType);
        Assert.Equal(
            typeof(Func<,,>).MakeGenericType(t, typeof(CancellationToken), typeof(Task<>).MakeGenericType(t)),
            parameters[1].ParameterType);
        Assert.Equal(typeof(Task<>).MakeGenericType(t), brew.ReturnType);
    }

    [Fact]
    public async Task Brew_BuiltinResults_ComeBackFromTheLambda()
    {
        using var siphon = new Siphon();

        Assert.Equal(8, await siphon.BrewAsync(4, async (age, token) =>
        {
            await Task.Delay(5, token);
            return age * 2;
        }));
        Assert.Equal("Oreo and Mylo", await siphon.BrewAsync("Oreo", (name, _) => Task.FromResult($"{name} and Mylo")));
    }

    [Fact]
    public async Task Brew_LambdaReturnsABorrowedCat_KotlinGetsItsOwnBox()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        using Tabby swapped = await siphon.BrewAsync(oreo, async (cat, token) =>
        {
            cat.Dispose();
            await Task.Delay(5, token);
            return mylo;
        });
        Assert.Equal("Mylo", swapped.Name);
        // Mylo was only lent to Kotlin: this test still owns him.
        Assert.Equal(5, mylo.Tricks);
    }

    [Fact]
    public async Task Brew_LambdaReturnsAFreshCat_SurvivesTheTripIntoKotlin()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);

        // Nothing in C# holds the fresh Mylo once the lambda's Task completes.
        using Tabby fresh = await siphon.BrewAsync(oreo, async (cat, token) =>
        {
            cat.Dispose();
            await Task.Yield();
            return new Tabby("Mylo", 5);
        });
        Assert.Equal("Mylo:5", $"{fresh.Name}:{fresh.Tricks}");
    }

    [Fact]
    public async Task Brew_NullableTypeArgument_CarriesANullBothWays()
    {
        using var siphon = new Siphon();

        string? seen = "unset";
        Assert.Null(await siphon.BrewAsync<string?>(null, (seat, _) =>
        {
            seen = seat;
            return Task.FromResult<string?>(null);
        }));
        Assert.Null(seen);
        Assert.Equal("Mylo", await siphon.BrewAsync<string?>(null, (seat, _) => Task.FromResult<string?>(seat ?? "Mylo")));
    }

    [Fact]
    public async Task Retint_TrampolinedTypeParameter_CrossesBothWays()
    {
        using var siphon = new Siphon();

        Tint seen = Tint.Cream;
        Assert.Equal("MOCHA", await siphon.RetintAsync(Tint.Tuxedo, (tint, _) =>
        {
            seen = tint;
            return Task.FromResult(Tint.Mocha);
        }));
        Assert.Equal(Tint.Tuxedo, seen);
    }

    [Fact]
    public async Task Brew_OuterTokenCancelledWhileTheLambdaWaits_CancelsTheLambdasToken()
    {
        using var siphon = new Siphon();
        using var cts = new CancellationTokenSource();
        var waiting = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

        Task<string> brewing = siphon.BrewAsync("Oreo", async (name, token) =>
        {
            waiting.TrySetResult();
            try
            {
                await Task.Delay(Timeout.Infinite, token);
            }
            catch (OperationCanceledException)
            {
                cancelled.TrySetResult(token.IsCancellationRequested);
                throw;
            }
            return name;
        }, cts.Token);
        await waiting.Task.WaitAsync(Patience);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => brewing);
        Assert.True(await cancelled.Task.WaitAsync(Patience));
    }

    // --- suspend fun <T> tip(items: List<T>, cup: suspend (T) -> Unit): Int ---

    [Fact]
    public async Task Tip_PayloadsArriveInOrder_TheWrapperIsTheLambdas()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        var ages = new List<int>();
        Assert.Equal(2, await siphon.TipAsync(new[] { 4, 6 }, async (age, token) =>
        {
            await Task.Delay(5, token);
            ages.Add(age);
        }));
        Assert.Equal(new[] { 4, 6 }, ages);

        var tricks = new List<string>();
        Assert.Equal(2, await siphon.TipAsync(new[] { oreo, mylo }, (cat, _) =>
        {
            using (cat) tricks.Add($"{cat.Name}:{cat.Tricks}");
            return Task.CompletedTask;
        }));
        Assert.Equal(new[] { "Oreo:3", "Mylo:5" }, tricks);

        var seats = new List<string?>();
        Assert.Equal(3, await siphon.TipAsync(new string?[] { "Oreo", null, "Mylo" }, (seat, _) =>
        {
            seats.Add(seat);
            return Task.CompletedTask;
        }));
        Assert.Equal(new string?[] { "Oreo", null, "Mylo" }, seats);
    }

    // --- suspend fun <T> slosh(source: Flow<T>, step: suspend (T) -> T): List<T> ---

    [Fact]
    public async Task Slosh_FlowElementsThroughASuspendLambda_ComeBackChanged()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        Assert.Equal(new[] { 2, 4 }, await siphon.SloshAsync(Pour(1, 2), (n, _) => Task.FromResult(n * 2)));

        // Every Oreo that goes in comes out as Mylo.
        IReadOnlyList<Tabby> sloshed = await siphon.SloshAsync(Pour(oreo, oreo), (cat, _) =>
        {
            cat.Dispose();
            return Task.FromResult(mylo);
        });
        try
        {
            Assert.Equal(new[] { "Mylo", "Mylo" }, sloshed.Select(cat => cat.Name));
        }
        finally
        {
            foreach (Tabby cat in sloshed) cat.Dispose();
        }
    }

    // --- suspend fun <T> decant(value: T, pour: (T) -> T): T, a plain lambda on the suspend route ---

    [Fact]
    public async Task Decant_PlainLambdaOverT_RunsInsideTheSuspendCall()
    {
        using var siphon = new Siphon();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        Assert.Equal(6, await siphon.DecantAsync(3, n => n * 2));
        Assert.Equal("Oreo and Mylo", await siphon.DecantAsync("Oreo", name => $"{name} and Mylo"));
        Assert.Null(await siphon.DecantAsync<string?>(null, seat => seat));
        using Tabby swapped = await siphon.DecantAsync(oreo, cat =>
        {
            cat.Dispose();
            return mylo;
        });
        Assert.Equal("Mylo", swapped.Name);
        // Mylo was only lent to Kotlin: this test still owns him.
        Assert.Equal(5, mylo.Tricks);
    }

    // --- class Cistern<T>(seed: T): the owner's T ---

    [Fact]
    public async Task Cistern_GenericClassAsyncMembers_Bind()
    {
        using var water = new Cistern<int>(1);
        Assert.Equal(new[] { 1, 2, 3 }, await water.GulpAsync(Pour(2, 3)));
        Assert.Equal(10, await water.BrewAsync((n, _) => Task.FromResult(n * 10)));

        using var oreo = new Tabby("Oreo", 3);
        using var forOreo = new Cistern<Tabby>(oreo);
        int sips = 0;
        await forOreo.TipAsync((cat, _) =>
        {
            using (cat) sips += cat.Tricks;
            return Task.CompletedTask;
        });
        Assert.Equal(6, sips);

        using var empty = new Cistern<string?>(null);
        Assert.Equal(new string?[] { null, "Mylo", null }, await empty.GulpAsync(Pour<string?>("Mylo", null)));
        Assert.Equal("Mylo", await empty.BrewAsync((seat, _) => Task.FromResult<string?>(seat ?? "Mylo")));
    }

    // --- sealed class Spill<out T> { class Puddle<T> }: a generic sealed arm ---

    [Fact]
    public async Task Puddle_GenericSealedArmAsyncMembers_Bind()
    {
        using var puddle = new Spill.Puddle<string>("Oreo");
        Assert.Equal(new[] { "Oreo", "Mylo" }, await puddle.GulpAsync(Pour("Mylo")));
        Assert.Equal("Oreo lapped it up", await puddle.BrewAsync((name, _) => Task.FromResult($"{name} lapped it up")));

        using var mylo = new Tabby("Mylo", 5);
        using var forMylo = new Spill.Puddle<Tabby>(mylo);
        using Tabby mopped = await forMylo.BrewAsync((cat, _) => Task.FromResult(cat));
        Assert.Equal("Mylo", mopped.Name);
    }
}

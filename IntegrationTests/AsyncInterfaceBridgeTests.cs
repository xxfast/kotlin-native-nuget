using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Feedingtime;

namespace IntegrationTests;

/// <summary>
/// ADR-232: a C# class implementing a Kotlin interface whose members are <c>suspend</c> or return a
/// <c>Flow</c> crosses to Kotlin. Kotlin awaits the C# <c>FeedAsync(cat, cancellationToken)</c> and
/// collects the C# <c>KotlinFlow&lt;string&gt;</c>, either one the implementation got from Kotlin or
/// one it made from an <c>IAsyncEnumerable&lt;string&gt;</c> with <c>KotlinFlow&lt;T&gt;.From</c>.
/// <para>
/// One fact per result wire the bridge carries, one per direction of fault and cancellation, one
/// per way a flow ends (completed, stopped early, faulted, cancelled), the generic interface
/// (<c>ILarder&lt;T&gt;</c>) over a reference and a value <c>T</c>, and the interface nothing
/// returns (<c>IAlarmClock</c>). Every implementation records what it was handed, so a slot that
/// delivered a constant, or never reached C#, cannot pass on what Kotlin returns alone.
/// </para>
/// Oreo's automatic feeder is very punctual. Mylo's is mostly a bowl he knocks over.
/// </summary>
public class AsyncInterfaceBridgeTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(30);

    /// <summary>
    /// A C# feeder. <see cref="FeedAsync"/> really suspends unless <see cref="Feeding"/> replaces
    /// it; every other result member answers per cat so a swapped or constant slot shows up.
    /// </summary>
    private sealed class Bowl(string name) : IFeeder
    {
        public readonly List<string> Fed = [];
        public int Refills;
        public Func<string, CancellationToken, Task<int>>? Feeding;
        public Func<KotlinFlow<string>>? Menu;

        public string Name { get; } = name;

        public async Task<int> FeedAsync(string cat, CancellationToken cancellationToken = default)
        {
            lock (Fed) Fed.Add(cat);
            if (Feeding != null) return await Feeding(cat, cancellationToken);
            await Task.Delay(5, cancellationToken);
            return cat == "Oreo" ? 3 : 5;
        }

        public async Task RefillAsync(CancellationToken cancellationToken = default)
        {
            await Task.Delay(1, cancellationToken);
            Interlocked.Increment(ref Refills);
        }

        public Task<bool> HungryAsync(string cat, CancellationToken cancellationToken = default) =>
            Task.FromResult(cat == "Mylo");

        public async Task<long> LastMealAsync(CancellationToken cancellationToken = default)
        {
            await Task.Delay(1, cancellationToken);
            return 1_792_656_000_000L;
        }

        public Task<float> PortionAsync(CancellationToken cancellationToken = default) => Task.FromResult(0.25f);

        public async Task<double> WeighAsync(CancellationToken cancellationToken = default)
        {
            await Task.Yield();
            return 4.75;
        }

        public Task<Craving> CravingAsync(string cat, CancellationToken cancellationToken = default) =>
            Task.FromResult(cat == "Mylo" ? Craving.Ravenous : Craving.Peckish);

        public Task<string?> FavouriteAsync(string cat, CancellationToken cancellationToken = default) =>
            Task.FromResult<string?>(cat == "Oreo" ? "tuna" : null);

        public Task<Exception> SpillAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult<Exception>(new InvalidOperationException("Mylo knocked the bowl over"));

        public KotlinFlow<string> Meals() => Menu != null ? Menu() : KotlinFlow<string>.From(Courses());

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    /// <summary>What a C# iterator did, read after the Kotlin call is over.</summary>
    private sealed class Tap
    {
        public int Yielded;
        public int Finalised;
        public readonly TaskCompletionSource Closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly TaskCompletionSource Parked = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly TaskCompletionSource<bool> TokenCancelled = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private static async IAsyncEnumerable<string> Courses(
        Tap? tap = null, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            foreach (string course in new[] { "kibble", "tuna", "salmon" })
            {
                await Task.Delay(5, token);
                if (tap != null) Interlocked.Increment(ref tap.Yielded);
                yield return course;
            }
        }
        finally
        {
            if (tap != null)
            {
                Interlocked.Increment(ref tap.Finalised);
                tap.Closed.TrySetResult();
            }
        }
    }

    /// <summary>A meal every few milliseconds, for as long as anyone pulls.</summary>
    private static async IAsyncEnumerable<string> Buffet(
        Tap tap, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            for (int course = 1; ; course++)
            {
                await Task.Delay(2, token);
                Interlocked.Increment(ref tap.Yielded);
                yield return $"course{course}";
            }
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    /// <summary>Two meals, then the bowl tips over.</summary>
    private static async IAsyncEnumerable<string> CoursesThenSpill(
        Tap tap, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            foreach (string course in new[] { "kibble", "tuna" })
            {
                await Task.Delay(5, token);
                Interlocked.Increment(ref tap.Yielded);
                yield return course;
            }
            await Task.Delay(5, token);
            throw new InvalidOperationException("Mylo tipped the bowl mid-meal");
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    /// <summary>One meal, then waits on nothing but its own token.</summary>
    private static async IAsyncEnumerable<string> CourseThenWaitOnTheToken(
        Tap tap, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            Interlocked.Increment(ref tap.Yielded);
            yield return "kibble";

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
            yield return "never";
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Suspend members: results.
    // -----------------------------------------------------------------------------------------

    /// <summary>The headline: Kotlin awaits a C# <c>Task&lt;int&gt;</c> that really suspends.</summary>
    [Fact]
    public async Task Serve_CSharpFeeder_AwaitsFeedAsyncAndReadsTheInt()
    {
        using var bowl = new Bowl("Oreo's bowl");

        Assert.Equal("Oreo's bowl gave Oreo 3 scoops", await Feeders.ServeAsync(bowl, "Oreo"));
        Assert.Equal("Oreo's bowl gave Mylo 5 scoops", await Feeders.ServeAsync(bowl, "Mylo"));
        Assert.Equal(new List<string> { "Oreo", "Mylo" }, bowl.Fed);
    }

    /// <summary>A <c>Task</c> result (<c>Unit</c>), awaited twice in one Kotlin call.</summary>
    [Fact]
    public async Task TopUp_UnitMember_IsAwaitedEachTime()
    {
        using var bowl = new Bowl("Mylo's bowl");

        Assert.Equal("Mylo's bowl topped up twice", await Feeders.TopUpAsync(bowl));
        Assert.Equal(2, bowl.Refills);
    }

    /// <summary>
    /// Every other result wire in one call: <c>bool</c>, <c>long</c>, <c>float</c>, <c>double</c>,
    /// an enum, a nullable <c>string</c> both ways, and an <c>Exception</c>. A mix of already
    /// completed and really suspending tasks.
    /// </summary>
    [Fact]
    public async Task CheckUp_EveryResultWire_ArrivesInKotlin()
    {
        using var bowl = new Bowl("Oreo's bowl");

        Assert.Equal(
            "hungry=false, last=1792656000000, portion=0.25, weight=4.75, craving=PECKISH, favourite=tuna, spill=Mylo knocked the bowl over",
            await Feeders.CheckUpAsync(bowl, "Oreo"));
        Assert.Equal(
            "hungry=true, last=1792656000000, portion=0.25, weight=4.75, craving=RAVENOUS, favourite=null, spill=Mylo knocked the bowl over",
            await Feeders.CheckUpAsync(bowl, "Mylo"));
    }

    /// <summary>The Kotlin-backed feeder still crosses at the same parameter, as its own handle.</summary>
    [Fact]
    public async Task Serve_KotlinFeeder_AtTheSameParameter()
    {
        using IFeeder auto = Feeders.AutoFeeder("Oreo's bowl");

        Assert.Equal(4, await auto.FeedAsync("Oreo"));
        Assert.Equal("Oreo's bowl gave Mylo 4 scoops", await Feeders.ServeAsync(auto, "Mylo"));
    }

    // -----------------------------------------------------------------------------------------
    // Suspend members: cancellation, in each direction.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// Kotlin gives up (<c>withTimeoutOrNull</c>) and carries on: the token <c>FeedAsync</c>
    /// received is cancelled. The feeder waits on nothing but its token, so the only way it ends is
    /// the bridge cancelling it.
    /// </summary>
    [Fact]
    public async Task KotlinTimeout_CancelsTheTokenFeedAsyncReceived()
    {
        var tokenCancelled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var bowl = new Bowl("Mylo's bowl")
        {
            Feeding = async (_, token) =>
            {
                try
                {
                    await Task.Delay(Timeout.Infinite, token);
                }
                catch (OperationCanceledException)
                {
                    tokenCancelled.TrySetResult(token.IsCancellationRequested);
                    throw;
                }
                return -1;
            },
        };

        Assert.Equal("Mylo gave up waiting", await Feeders.ServeOrGiveUpAsync(bowl, "Mylo", 50));
        Assert.True(await tokenCancelled.Task.WaitAsync(Patience));
    }

    /// <summary>The control: a feeder that beats the timeout is simply read.</summary>
    [Fact]
    public async Task KotlinTimeout_NotReached_ReturnsTheFeederResult()
    {
        using var bowl = new Bowl("Oreo's bowl");

        Assert.Equal("Oreo got 3 scoops", await Feeders.ServeOrGiveUpAsync(bowl, "Oreo", 30_000));
    }

    /// <summary>
    /// Cancelling the C# caller's token while <c>FeedAsync</c> is pending cancels the token
    /// <c>FeedAsync</c> received, and the outer <c>Task</c> ends cancelled. The token starts out
    /// live, so this is not a pre-cancelled token passing by luck.
    /// </summary>
    [Fact]
    public async Task OuterTokenCancelled_CancelsTheTokenFeedAsyncReceived()
    {
        using var cts = new CancellationTokenSource();
        var entered = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var tokenCancelled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var bowl = new Bowl("Oreo's bowl")
        {
            Feeding = async (_, token) =>
            {
                entered.TrySetResult(token.IsCancellationRequested);
                try
                {
                    await Task.Delay(Timeout.Infinite, token);
                }
                catch (OperationCanceledException)
                {
                    tokenCancelled.TrySetResult(token.IsCancellationRequested);
                    throw;
                }
                return -1;
            },
        };

        Task<string> serving = Feeders.ServeAsync(bowl, "Oreo", cts.Token);
        Assert.False(await entered.Task.WaitAsync(Patience), "FeedAsync's token was already cancelled on entry");
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => serving);
        Assert.True(await tokenCancelled.Task.WaitAsync(Patience));
    }

    /// <summary>
    /// A cancelled C# <c>Task</c> is a cancellation in Kotlin, not a fault: the host that reports
    /// faults rethrows it, so the outer <c>Task</c> ends cancelled and never says "recovered".
    /// </summary>
    [Fact]
    public async Task CancelledFeedTask_IsACancellationInKotlin_NotAFault()
    {
        using var bowl = new Bowl("Mylo's bowl")
        {
            Feeding = (_, _) => Task.FromCanceled<int>(new CancellationToken(canceled: true)),
        };

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Feeders.ServeOrRecoverAsync(bowl, "Mylo"));
    }

    // -----------------------------------------------------------------------------------------
    // Suspend members: faults, C# to Kotlin and back.
    // -----------------------------------------------------------------------------------------

    /// <summary>A synchronous throw, a faulted task, and a fault after a real suspension, each caught in Kotlin.</summary>
    [Theory]
    [InlineData("sync")]
    [InlineData("faulted")]
    [InlineData("after-await")]
    public async Task CSharpFault_IsCatchableInKotlin(string shape)
    {
        string complaint = $"the bowl is empty ({shape})";
        using var bowl = new Bowl("Oreo's bowl") { Feeding = Throwing(shape, complaint) };

        string report = await Feeders.ServeOrRecoverAsync(bowl, "Oreo");

        Assert.StartsWith("recovered ", report);
        Assert.Contains(complaint, report);
    }

    /// <summary>The control for the row above: a healthy feeder is not "recovered".</summary>
    [Fact]
    public async Task ServeOrRecover_HealthyFeeder_ReturnsItsResult()
    {
        using var bowl = new Bowl("Oreo's bowl");

        Assert.Equal("served 3", await Feeders.ServeOrRecoverAsync(bowl, "Oreo"));
    }

    /// <summary>
    /// Kotlin does not catch it: the C# fault crosses into Kotlin and back out, faulting the C#
    /// caller's <c>Task</c> with the feeder's message, and the bridge keeps working afterwards.
    /// </summary>
    [Theory]
    [InlineData("sync")]
    [InlineData("faulted")]
    [InlineData("after-await")]
    public async Task CSharpFault_Uncaught_FaultsTheOuterTask(string shape)
    {
        string complaint = $"Mylo sat in the bowl ({shape})";
        using var bowl = new Bowl("Mylo's bowl") { Feeding = Throwing(shape, complaint) };

        Task<string> serving = Feeders.ServeAsync(bowl, "Mylo");

        Exception ex = await Assert.ThrowsAnyAsync<Exception>(() => serving);
        Assert.IsNotAssignableFrom<Xunit.Sdk.XunitException>(ex);
        Assert.IsNotAssignableFrom<OperationCanceledException>(ex);
        Assert.True(serving.IsFaulted);
        Assert.Contains(complaint, ex.ToString());

        bowl.Feeding = null;
        Assert.Equal("Mylo's bowl gave Mylo 5 scoops", await Feeders.ServeAsync(bowl, "Mylo"));
    }

    private static Func<string, CancellationToken, Task<int>> Throwing(string shape, string complaint) => shape switch
    {
        "sync" => (_, _) => throw new InvalidOperationException(complaint),
        "faulted" => (_, _) => Task.FromException<int>(new InvalidOperationException(complaint)),
        _ => async (_, token) =>
        {
            await Task.Delay(5, token);
            throw new InvalidOperationException(complaint);
        },
    };

    // -----------------------------------------------------------------------------------------
    // Flow members.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// A C#-made flow (<c>KotlinFlow&lt;T&gt;.From</c> over a really suspending iterator) collected
    /// to the end in Kotlin. The iterator ran once and its <c>finally</c> ran once.
    /// </summary>
    [Fact]
    public async Task Menu_CSharpMadeFlow_IsCollectedInKotlin()
    {
        var tap = new Tap();
        using var bowl = new Bowl("Oreo's bowl") { Menu = () => KotlinFlow<string>.From(Courses(tap)) };

        Assert.Equal("kibble, tuna, salmon", await Feeders.MenuAsync(bowl));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(3, tap.Yielded);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>A cold C# flow: each Kotlin collection enumerates afresh.</summary>
    [Fact]
    public async Task Menu_CSharpMadeFlow_CollectedTwice_EnumeratesTwice()
    {
        using var bowl = new Bowl("Oreo's bowl");

        Assert.Equal("kibble, tuna, salmon", await Feeders.MenuAsync(bowl));
        Assert.Equal("kibble, tuna, salmon", await Feeders.MenuAsync(bowl));
    }

    /// <summary>A flow the implementation got from Kotlin is handed back as Kotlin's own.</summary>
    [Fact]
    public async Task Menu_KotlinMadeFlow_PassesThroughTheCSharpFeeder()
    {
        using IFeeder auto = Feeders.AutoFeeder("Oreo's bowl");
        using var bowl = new Bowl("Mylo's bowl") { Menu = () => auto.Meals() };

        Assert.Equal("tuna, salmon", await Feeders.MenuAsync(bowl));
    }

    /// <summary>
    /// Kotlin stops after two meals of an endless buffet: the C# enumeration is disposed (its
    /// <c>finally</c> runs once) and nothing past what Kotlin asked for is surfaced.
    /// </summary>
    [Fact]
    public async Task FirstMeals_KotlinStopsEarly_DisposesTheCSharpEnumeration()
    {
        var tap = new Tap();
        using var bowl = new Bowl("Mylo's bowl") { Menu = () => KotlinFlow<string>.From(Buffet(tap)) };

        Assert.Equal("course1, course2", await Feeders.FirstMealsAsync(bowl, 2));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>A C# flow that faults mid-stream, caught in Kotlin after the two meals it delivered.</summary>
    [Fact]
    public async Task MenuOrRecover_FlowFaultsMidStream_IsCatchableInKotlin()
    {
        var tap = new Tap();
        using var bowl = new Bowl("Mylo's bowl") { Menu = () => KotlinFlow<string>.From(CoursesThenSpill(tap)) };

        string report = await Feeders.MenuOrRecoverAsync(bowl);

        Assert.StartsWith("ate kibble, tuna then ", report);
        Assert.Contains("Mylo tipped the bowl mid-meal", report);
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>The same fault, uncaught: it crosses Kotlin and faults the C# caller's <c>Task</c>.</summary>
    [Fact]
    public async Task Menu_FlowFaultsMidStream_Uncaught_FaultsTheOuterTask()
    {
        var tap = new Tap();
        using var bowl = new Bowl("Mylo's bowl") { Menu = () => KotlinFlow<string>.From(CoursesThenSpill(tap)) };

        Task<string> listing = Feeders.MenuAsync(bowl);

        Exception ex = await Assert.ThrowsAnyAsync<Exception>(() => listing);
        Assert.IsNotAssignableFrom<OperationCanceledException>(ex);
        Assert.True(listing.IsFaulted);
        Assert.Contains("Mylo tipped the bowl mid-meal", ex.ToString());
    }

    /// <summary>
    /// Cancelling the C# caller's token while Kotlin is parked on the second pull cancels the token
    /// the C# enumeration received, and the outer <c>Task</c> ends cancelled.
    /// </summary>
    [Fact]
    public async Task Menu_OuterTokenCancelledMidCollection_CancelsTheEnumerationToken()
    {
        var tap = new Tap();
        using var cts = new CancellationTokenSource();
        using var bowl = new Bowl("Oreo's bowl") { Menu = () => KotlinFlow<string>.From(CourseThenWaitOnTheToken(tap)) };

        Task<string> listing = Feeders.MenuAsync(bowl, cts.Token);
        await tap.Parked.Task.WaitAsync(Patience);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => listing);
        Assert.True(await tap.TokenCancelled.Task.WaitAsync(Patience));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>The C# flow comes back out of Kotlin at a <c>Flow</c> return and C# collects it.</summary>
    [Fact]
    public async Task Relay_CSharpMadeFlow_ComesBackToCSharp()
    {
        using var bowl = new Bowl("Oreo's bowl");
        using var station = new FeedingStation();
        var seen = new List<string>();

        using KotlinFlow<string> relayed = station.Relay(bowl);
        await foreach (string meal in relayed)
        {
            seen.Add(meal);
        }

        Assert.Equal(new List<string> { "kibble", "tuna", "salmon" }, seen);
    }

    // -----------------------------------------------------------------------------------------
    // The generic interface: `ILarder<T>` over a reference and a value `T`.
    // -----------------------------------------------------------------------------------------

    private sealed class Shelf<T>(params T[] items) : ILarder<T>
    {
        public readonly List<int> Fetched = [];

        public async Task<T> FetchAsync(int shelf, CancellationToken cancellationToken = default)
        {
            lock (Fetched) Fetched.Add(shelf);
            await Task.Delay(1, cancellationToken);
            return items[shelf];
        }

        public KotlinFlow<T> Stock() => KotlinFlow<T>.From(Items());

        private async IAsyncEnumerable<T> Items([EnumeratorCancellation] CancellationToken token = default)
        {
            foreach (T item in items)
            {
                await Task.Delay(1, token);
                yield return item;
            }
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Raid_OpenT_String_AwaitsTheBoxedResult()
    {
        using var shelf = new Shelf<string>("tuna", "salmon");

        Assert.Equal("salmon", await Feeders.RaidAsync(shelf, 1));
        Assert.Equal(new List<int> { 1 }, shelf.Fetched);
    }

    [Fact]
    public async Task Tally_ClosedInt_SumsTheFetchAndTheFlow()
    {
        using var shelf = new Shelf<int>(3, 4, 5);

        Assert.Equal(3 + 3 + 4 + 5, await Feeders.TallyAsync(shelf));
        Assert.Equal(new List<int> { 0 }, shelf.Fetched);
    }

    [Fact]
    public async Task Inventory_OpenT_CollectsTheGenericFlow()
    {
        using var shelf = new Shelf<string>("Oreo's tuna", "Mylo's salmon");

        Assert.Equal(new[] { "Oreo's tuna", "Mylo's salmon" }, await Feeders.InventoryAsync(shelf));
    }

    /// <summary>The Kotlin-backed larder at the same open parameter.</summary>
    [Fact]
    public async Task Raid_KotlinLarder_AtTheSameParameter()
    {
        using ILarder<string> cupboard = Feeders.Cupboard(new List<string> { "kibble", "tuna" });

        Assert.Equal("tuna", await Feeders.RaidAsync(cupboard, 1));
        Assert.Equal(new[] { "kibble", "tuna" }, await Feeders.InventoryAsync(cupboard));
    }

    // -----------------------------------------------------------------------------------------
    // The interface nothing returns.
    // -----------------------------------------------------------------------------------------

    private sealed class Rooster : IAlarmClock
    {
        public readonly List<string> Woken = [];

        public async Task<string> RingAsync(string cat, CancellationToken cancellationToken = default)
        {
            lock (Woken) Woken.Add(cat);
            await Task.Delay(1, cancellationToken);
            return $"{cat}, wake up";
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Breakfast_UnreachableInterface_AwaitsTheCSharpSuspendMember()
    {
        using var rooster = new Rooster();

        Assert.Equal("breakfast after Mylo, wake up", await Feeders.BreakfastAsync(rooster));
        Assert.Equal(new List<string> { "Mylo" }, rooster.Woken);
    }

    // -----------------------------------------------------------------------------------------
    // The interface no position reaches, and a suspend member returning a Flow.
    // -----------------------------------------------------------------------------------------

    private sealed class Alarm : ISnooze
    {
        public async Task<string> SnoozeAsync(int minutes, CancellationToken cancellationToken = default)
        {
            await Task.Yield();
            return $"{minutes} more minutes";
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    /// <summary>Nothing takes or returns a <c>Snooze</c>, and <c>ISnooze</c> still declares its suspend member.</summary>
    [Fact]
    public async Task UnreachableInterface_DeclaresItsSuspendMember()
    {
        var member = typeof(ISnooze).GetMethod(nameof(ISnooze.SnoozeAsync));
        Assert.NotNull(member);
        Assert.Equal(typeof(Task<string>), member!.ReturnType);

        using var alarm = new Alarm();
        Assert.Equal("5 more minutes", await alarm.SnoozeAsync(5));
    }

    private sealed class Larder(Func<KotlinFlow<string>> deliver) : IPantry
    {
        public int Deliveries;

        public async Task<KotlinFlow<string>> DeliveryAsync(CancellationToken cancellationToken = default)
        {
            Interlocked.Increment(ref Deliveries);
            await Task.Delay(1, cancellationToken);
            return deliver();
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    /// <summary>Kotlin awaits the C# <c>Task&lt;KotlinFlow&lt;string&gt;&gt;</c>, then collects the flow it completed with.</summary>
    [Fact]
    public async Task Unpack_SuspendMemberReturningAFlow_IsAwaitedThenCollected()
    {
        using var larder = new Larder(() => KotlinFlow<string>.From(Courses()));

        Assert.Equal("kibble, tuna, salmon", await Feeders.UnpackAsync(larder));
        Assert.Equal(1, larder.Deliveries);
    }

    /// <summary>A Kotlin-made flow is awaited and collected through the same member.</summary>
    [Fact]
    public async Task Unpack_KotlinMadeFlow_PassesThroughTheAwaitedMember()
    {
        using IFeeder auto = Feeders.AutoFeeder("Oreo's bowl");
        using var larder = new Larder(() => auto.Meals());

        Assert.Equal("tuna, salmon", await Feeders.UnpackAsync(larder));
    }

    // -----------------------------------------------------------------------------------------
    // Hot flows: C# creates them, Kotlin reads and writes them.
    // -----------------------------------------------------------------------------------------

    private sealed class Boiler : IThermostat
    {
        public KotlinMutableStateFlow<int> LevelFlow { get; } = KotlinMutableStateFlow<int>.Create(18);
        public KotlinMutableStateFlow<int> Dial { get; } = KotlinMutableStateFlow<int>.Create(0);
        public KotlinMutableSharedFlow<string> Headlines { get; } = KotlinMutableSharedFlow<string>.Create(replay: 1);
        public Func<KotlinStateFlow<int>>? Level_;

        public KotlinStateFlow<int> Level => Level_ != null ? Level_() : LevelFlow;
        KotlinMutableStateFlow<int> IThermostat.Dial => Dial;
        public KotlinSharedFlow<string> News() => Headlines;
        public KotlinMutableSharedFlow<string> Bell() => Headlines;

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    /// <summary>Kotlin reads a C#-made state flow, and sees a value C# writes afterwards.</summary>
    [Fact]
    public void Reading_CSharpMadeStateFlow_KotlinSeesEachCSharpWrite()
    {
        using var dial = new Boiler();

        Assert.Equal(18, Feeders.Reading(dial));
        dial.LevelFlow.Value = 23;
        Assert.Equal(23, Feeders.Reading(dial));
    }

    /// <summary>Kotlin writes a C#-made mutable state flow, and C# sees the write.</summary>
    [Fact]
    public void TurnUp_KotlinWritesTheCSharpMutableStateFlow()
    {
        using var dial = new Boiler();

        Assert.Equal(1, Feeders.TurnUp(dial));
        Assert.Equal(2, Feeders.TurnUp(dial));
        Assert.Equal(2, dial.Dial.Value);
    }

    /// <summary>Kotlin collects a C#-made state flow until C# writes past the floor.</summary>
    [Fact]
    public async Task WarmerThan_KotlinCollectsUntilCSharpWrites()
    {
        using var dial = new Boiler();

        Task<int> waiting = Feeders.WarmerThanAsync(dial, 20);
        await Task.Delay(20);
        Assert.False(waiting.IsCompleted);
        dial.LevelFlow.Value = 25;

        Assert.Equal(25, await waiting.WaitAsync(Patience));
    }

    /// <summary>A C#-made shared flow: a C# emit replays to Kotlin, a Kotlin emit reaches C#.</summary>
    [Fact]
    public async Task SharedFlow_EmitsCrossBothWays()
    {
        using var dial = new Boiler();

        Assert.True(dial.Headlines.TryEmit("tuna on sale"));
        Assert.Equal("tuna on sale", await Feeders.HeadlineAsync(dial));
        Assert.True(Feeders.Ring(dial, "ding"));
        Assert.Equal(new[] { "ding" }, dial.Headlines.ReplayCache);
    }

    /// <summary>A C#-made state flow collected in C# sees a Kotlin write.</summary>
    [Fact]
    public async Task CSharpCollect_SeesAKotlinWrite()
    {
        using var dial = new Boiler();
        using var cts = new CancellationTokenSource(Patience);

        await using var values = dial.Dial.GetAsyncEnumerator(cts.Token);
        Assert.True(await values.MoveNextAsync());
        Assert.Equal(0, values.Current);
        Feeders.TurnUp(dial);
        Assert.True(await values.MoveNextAsync());
        Assert.Equal(1, values.Current);
    }

    /// <summary>A hot flow wrapping no Kotlin flow of its own fails at the slot, by name.</summary>
    [Fact]
    public void Reading_AWrapperWithNoKotlinFlow_FailsWithArgumentException()
    {
        using IThermostat wall = Feeders.WallThermostat();
        using var dial = new Boiler { Level_ = () => wall.Level };

        Exception ex = Assert.ThrowsAny<Exception>(() => Feeders.Reading(dial));
        Assert.Contains("ArgumentException", ex.ToString());
        Assert.Contains("wraps no Kotlin flow of its own", ex.ToString());
    }
}

using System.Runtime.CompilerServices;
using TestLibrary.Cat;
using TestLibrary.Trickle;

namespace IntegrationTests;

/// <summary>
/// ADR-210 slice (b): a Kotlin <c>Flow&lt;T&gt;</c> <b>parameter</b> binds as
/// <c>IAsyncEnumerable&lt;T&gt;</c>. This is the opposite direction from <c>FlowTests</c> (a
/// <c>Flow</c> Kotlin hands out).
/// <para>
/// Kotlin sees a cold flow: <c>GetAsyncEnumerator</c> once per <c>collect</c>, one pull per
/// element, and the enumerator disposed exactly once however the collection ends. One fact per host
/// (class suspend member, Flow-returning member, sealed-arm suspend member, sealed-arm non-suspend
/// member that stores, constructor that stores, top-level suspend function) and one per promise
/// about enumeration, disposal, faults and cancellation. Every source records what was done to it:
/// how often it was enumerated, how far it got, and whether its <c>finally</c> ran.
/// </para>
/// Until the route lands every member used here is absent from the bindings, so this file does not
/// compile. Oreo only drinks running water. Mylo counts the drops.
/// </summary>
public class FlowParameterTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(30);

    /// <summary>
    /// A hand-written source that never suspends (every <c>MoveNextAsync</c> is an already
    /// completed <c>ValueTask</c>) and counts what the bridge did to it. Hand-written rather than
    /// an iterator method because the counts are the assertion: an iterator cannot say how many
    /// times <c>GetAsyncEnumerator</c> or <c>DisposeAsync</c> ran.
    /// </summary>
    private sealed class CountedDrops(params int[] drops) : IAsyncEnumerable<int>
    {
        public readonly int[] Drops = drops;
        public int Enumerations;
        public int Disposals;
        public int Pulls;

        public IAsyncEnumerator<int> GetAsyncEnumerator(CancellationToken cancellationToken = default)
        {
            Interlocked.Increment(ref Enumerations);
            return new Enumerator(this);
        }

        /// <summary>
        /// The disposal count once it has reached <paramref name="expected"/>. The bridge disposes
        /// an enumerator on a queued work item (ADR-210: cancel, await the pending step, then
        /// <c>DisposeAsync</c>, inside <c>Task.Run</c>), so the count settles shortly after the
        /// Kotlin call completes, not before it. The short pause after the count is reached is
        /// what lets a second, wrong disposal show up in the number.
        /// </summary>
        public async Task<int> SettledDisposalsAsync(int expected)
        {
            DateTime deadline = DateTime.UtcNow + Patience;
            while (Volatile.Read(ref Disposals) < expected && DateTime.UtcNow < deadline)
            {
                await Task.Delay(5);
            }
            await Task.Delay(25);
            return Volatile.Read(ref Disposals);
        }

        private sealed class Enumerator(CountedDrops owner) : IAsyncEnumerator<int>
        {
            private int _index = -1;

            public int Current => owner.Drops[_index];

            public ValueTask<bool> MoveNextAsync()
            {
                Interlocked.Increment(ref owner.Pulls);
                return new ValueTask<bool>(++_index < owner.Drops.Length);
            }

            public ValueTask DisposeAsync()
            {
                Interlocked.Increment(ref owner.Disposals);
                return default;
            }
        }
    }

    /// <summary>What an iterator method did, read by the test after the Kotlin call is over.</summary>
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
    private static async IAsyncEnumerable<int> Drip(
        Tap tap, int count, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            for (int drop = 1; drop <= count; drop++)
            {
                await Task.Delay(5, token);
                Interlocked.Increment(ref tap.Yielded);
                tap.FirstYield.TrySetResult();
                yield return drop;
            }
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    private static async IAsyncEnumerable<string> Sips(
        [EnumeratorCancellation] CancellationToken token = default)
    {
        foreach (string sip in new[] { "sip", "lap", "slurp" })
        {
            await Task.Delay(5, token);
            yield return sip;
        }
    }

    /// <summary>Two elements, then the tap splutters.</summary>
    private static async IAsyncEnumerable<int> DripThenSplutter(
        Tap tap, string complaint, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            for (int drop = 1; drop <= 2; drop++)
            {
                await Task.Delay(5, token);
                Interlocked.Increment(ref tap.Yielded);
                yield return drop;
            }
            await Task.Delay(5, token);
            throw new InvalidOperationException(complaint);
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    /// <summary>
    /// One element, then parks on nothing but its own token: the only way the second pull ends is
    /// the bridge cancelling the token it passed to <c>GetAsyncEnumerator</c>.
    /// </summary>
    private static async IAsyncEnumerable<int> DripThenWaitOnTheToken(
        Tap tap, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            Interlocked.Increment(ref tap.Yielded);
            yield return 1;

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
            yield return 2;
        }
        finally
        {
            Interlocked.Increment(ref tap.Finalised);
            tap.Closed.TrySetResult();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Host 1: a class `suspend fun`. Element types and the shape of the source.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// The headline cell: <c>Flow&lt;Int&gt;</c>, a source that really suspends, collected to the
    /// end. The iterator ran to completion exactly once and its <c>finally</c> ran exactly once.
    /// </summary>
    [Fact]
    public async Task Fountain_TotalDrops_IntFlow_CollectsEveryElement()
    {
        using var fountain = new WaterFountain("Oreo");
        var tap = new Tap();

        int total = await fountain.TotalDropsAsync(Drip(tap, 4));

        Assert.Equal(10, total);
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(4, tap.Yielded);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary><c>Flow&lt;String&gt;</c>: each element converted at <c>Current</c>, in order.</summary>
    [Fact]
    public async Task Fountain_Describe_StringFlow_ConvertsEachElementInOrder()
    {
        using var fountain = new WaterFountain("Mylo");

        Assert.Equal("sip|lap|slurp", await fountain.DescribeAsync(Sips()));
    }

    /// <summary>
    /// A source that completes without any real suspension: every pull is already complete when it
    /// returns, so each completion can land before the thunk that started it has returned.
    /// </summary>
    [Fact]
    public async Task Fountain_TotalDrops_SourceThatNeverSuspends_CollectsEveryElement()
    {
        using var fountain = new WaterFountain("Oreo");
        var drops = new CountedDrops(Enumerable.Range(1, 500).ToArray());

        Assert.Equal(500 * 501 / 2, await fountain.TotalDropsAsync(drops));
        Assert.Equal(1, drops.Enumerations);
        Assert.Equal(501, drops.Pulls);
        Assert.Equal(1, await drops.SettledDisposalsAsync(1));
    }

    /// <summary>An empty stream: one enumeration, one pull that says no, one disposal.</summary>
    [Fact]
    public async Task Fountain_EmptySource_CollectsNothingAndIsStillDisposed()
    {
        using var fountain = new WaterFountain("Oreo");
        var dry = new CountedDrops();

        Assert.Equal(0, await fountain.TotalDropsAsync(dry));
        Assert.Equal(1, dry.Enumerations);
        Assert.Equal(1, dry.Pulls);
        Assert.Equal(1, await dry.SettledDisposalsAsync(1));
    }

    /// <summary>
    /// Cold: Kotlin collects the same parameter twice, so <c>GetAsyncEnumerator</c> runs twice and
    /// each enumerator is disposed. A bridge that enumerated once and replayed a buffer would give
    /// the same string and one enumeration.
    /// </summary>
    [Fact]
    public async Task Fountain_MeasuredTwice_CallsGetAsyncEnumeratorOncePerCollect()
    {
        using var fountain = new WaterFountain("Mylo");
        var drops = new CountedDrops(3, 1, 2);

        Assert.Equal("3,1,2|3,1,2", await fountain.MeasuredTwiceAsync(drops));
        Assert.Equal(2, drops.Enumerations);
        Assert.Equal(2, await drops.SettledDisposalsAsync(2));
    }

    /// <summary>
    /// Early abort, the guard for ADR-210's Dispose-order rule (inferred claim 2, a SILENT failure
    /// if wrong: a leaked running iterator). Kotlin takes two of five and stops; the enumerator must
    /// be disposed with the iterator parked at its second <c>yield</c>, which is what runs the
    /// <c>finally</c>. Pull-based, so the third element is never produced.
    /// </summary>
    [Fact]
    public async Task Fountain_FirstTwo_EarlyAbort_RunsTheIteratorsFinally()
    {
        using var fountain = new WaterFountain("Oreo");
        var tap = new Tap();

        Assert.Equal("1,2", await fountain.FirstTwoAsync(Drip(tap, 5)));

        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
        Assert.Equal(2, tap.Yielded);
    }

    /// <summary>The same abort against the counting source: exactly one disposal, two pulls.</summary>
    [Fact]
    public async Task Fountain_FirstTwo_EarlyAbort_DisposesTheEnumeratorExactlyOnce()
    {
        using var fountain = new WaterFountain("Oreo");
        var drops = new CountedDrops(7, 8, 9, 10);

        Assert.Equal("7,8", await fountain.FirstTwoAsync(drops));
        Assert.Equal(1, drops.Enumerations);
        Assert.Equal(2, drops.Pulls);
        Assert.Equal(1, await drops.SettledDisposalsAsync(1));
    }

    /// <summary>
    /// The <b>collector</b> throws: a Kotlin exception raised inside <c>collect { }</c> on the
    /// second element, neither a source fault nor a cancellation. The outer <c>Task</c> faults with
    /// Kotlin's own exception, and the enumerator is still disposed, with the iterator parked at
    /// its second <c>yield</c>, so its <c>finally</c> runs exactly once and no third element is
    /// ever produced.
    /// </summary>
    [Fact]
    public async Task Fountain_ChokeOnSecond_CollectorThrows_FaultsTheOuterTaskAndRunsTheIteratorsFinally()
    {
        using var fountain = new WaterFountain("Oreo");
        var tap = new Tap();

        Task<int> choking = fountain.ChokeOnSecondAsync(Drip(tap, 5));

        KotlinInvalidOperationException ex =
            await Assert.ThrowsAsync<KotlinInvalidOperationException>(() => choking);
        Assert.Equal("kotlin.IllegalStateException", ex.KotlinType);
        Assert.Contains("Oreo choked on drop 2", ex.Message);
        Assert.True(choking.IsFaulted);

        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
        Assert.Equal(2, tap.Yielded);
    }

    /// <summary>The control: a source too short to reach the throw is simply counted.</summary>
    [Fact]
    public async Task Fountain_ChokeOnSecond_SingleElement_ReturnsNormally()
    {
        using var fountain = new WaterFountain("Oreo");

        Assert.Equal(1, await fountain.ChokeOnSecondAsync(new CountedDrops(4)));
    }

    // -----------------------------------------------------------------------------------------
    // Hosts 2 to 6.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// A <c>Flow</c>-returning member that takes a <c>Flow</c>: nothing is enumerated by the call
    /// itself, only once C# collects what came back.
    /// </summary>
    [Fact]
    public async Task Fountain_Doubled_FlowReturningHost_EnumeratesOnlyWhenCollected()
    {
        using var fountain = new WaterFountain("Mylo");
        var drops = new CountedDrops(1, 2, 3);

        using var doubled = fountain.Doubled(drops);
        Assert.Equal(0, drops.Enumerations);

        var seen = new List<int>();
        await foreach (int drop in doubled) seen.Add(drop);

        Assert.Equal(new List<int> { 2, 4, 6 }, seen);
        Assert.Equal(1, drops.Enumerations);
        Assert.Equal(1, await drops.SettledDisposalsAsync(1));
    }

    /// <summary>A <c>suspend fun</c> on a sealed arm.</summary>
    [Fact]
    public async Task SealedArm_Fill_SuspendMember_CollectsTheFlow()
    {
        using var bowl = new Bowl.Ceramic(capacity: 9);
        var tap = new Tap();

        Assert.Equal("1+2+3/9", await bowl.FillAsync(Drip(tap, 3)));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>
    /// A <b>non-suspend</b> member on a sealed arm can only store the flow (today this shape is
    /// dropped with no diagnostic at all). Nothing may be enumerated during <c>Attach</c>, and the
    /// stored flow must still be collectable, more than once, after <c>Attach</c> has returned.
    /// </summary>
    [Fact]
    public async Task SealedArm_Attach_StoredFlow_IsCollectedAfterTheStoringCallReturned()
    {
        using var bowl = new Bowl.Ceramic(capacity: 5);
        var drops = new CountedDrops(4, 5);

        Assert.Equal("nothing attached", await bowl.FillFromAttachedAsync());

        Assert.Equal(5, bowl.Attach(drops));
        Assert.Equal(0, drops.Enumerations);

        Assert.Equal("4+5/5", await bowl.FillFromAttachedAsync());
        Assert.Equal(1, drops.Enumerations);
        Assert.Equal(1, await drops.SettledDisposalsAsync(1));

        Assert.Equal("4+5/5", await bowl.FillFromAttachedAsync());
        Assert.Equal(2, drops.Enumerations);
        Assert.Equal(2, await drops.SettledDisposalsAsync(2));
    }

    /// <summary>
    /// A <b>constructor</b> stores the flow and a later suspend member collects it, across .NET
    /// collections in between: once the constructor has returned nothing but the bridge keeps the
    /// source reachable from Kotlin.
    /// </summary>
    [Fact]
    public async Task Constructor_Dripper_StoredFlow_IsCollectedAfterTheConstructorReturned()
    {
        var drops = new CountedDrops(6, 7, 8);
        using var dripper = new Dripper(drops);

        Assert.Equal(0, drops.Enumerations);
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();

        Assert.Equal("6,7,8", await dripper.DrainAsync());
        Assert.Equal("6,7,8", await dripper.DrainAsync());
        Assert.Equal(2, drops.Enumerations);
        Assert.Equal(2, await drops.SettledDisposalsAsync(2));
    }

    /// <summary>The top-level suspend route, on the ADR-007 static class.</summary>
    [Fact]
    public async Task TopLevel_CountDrops_CollectsTheFlow()
    {
        var tap = new Tap();

        Assert.Equal(6, await TrickleSample.CountDropsAsync(Drip(tap, 6)));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>
    /// The most natural thing a consumer will try: a <c>Flow</c> Kotlin handed out, passed straight
    /// back into a <c>Flow</c> parameter. <c>KotlinFlow&lt;int&gt;</c> is an
    /// <c>IAsyncEnumerable&lt;int&gt;</c>, so it must work like any other source.
    /// </summary>
    [Fact]
    public async Task KotlinProducedFlow_PassedStraightBackIn_IsCollected()
    {
        using var feeder = new CatFeeder("Oreo");
        using var fountain = new WaterFountain("Oreo");

        Assert.Equal(100 + 150 + 200, await fountain.TotalDropsAsync(feeder.PortionSizes));
    }

    // -----------------------------------------------------------------------------------------
    // Faults. A throw from the C# source reaches the Kotlin collection site as an exception.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// The iterator throws mid-stream and Kotlin catches it: the report names what Kotlin saw, and
    /// the two elements before the throw arrived first. The iterator's <c>finally</c> still ran.
    /// </summary>
    [Fact]
    public async Task IteratorThrowsMidStream_IsCatchableInKotlin()
    {
        using var fountain = new WaterFountain("Oreo");
        var tap = new Tap();

        string report = await fountain.RecoverAsync(DripThenSplutter(tap, "the fountain ran dry"));

        Assert.StartsWith("recovered after 2 ", report);
        Assert.Contains("NugetManagedException", report);
        Assert.Contains("the fountain ran dry", report);
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>The control for the one above: nothing thrown, nothing recovered.</summary>
    [Fact]
    public async Task Recover_WithAHealthySource_ReportsWhatItDrank()
    {
        using var fountain = new WaterFountain("Oreo");

        Assert.Equal("drank 3", await fountain.RecoverAsync(new CountedDrops(1, 2, 3)));
    }

    /// <summary>
    /// Kotlin does <b>not</b> catch: the outer <c>Task</c> faults, carrying the iterator's message.
    /// Which exception object arrives is ADR-210's inferred claim 6, so the type is not pinned.
    /// </summary>
    [Fact]
    public async Task IteratorThrowsMidStream_Uncaught_FaultsTheOuterTask()
    {
        using var fountain = new WaterFountain("Mylo");
        var tap = new Tap();

        Task<int> counting = fountain.TotalDropsAsync(DripThenSplutter(tap, "Mylo chewed the hose"));

        Exception ex = await Assert.ThrowsAnyAsync<Exception>(() => counting);
        Assert.IsNotAssignableFrom<Xunit.Sdk.XunitException>(ex);
        Assert.IsNotAssignableFrom<OperationCanceledException>(ex);
        Assert.True(counting.IsFaulted);
        Assert.Contains("Mylo chewed the hose", ex.ToString());
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(2, tap.Yielded);
        Assert.Equal(1, tap.Finalised);
    }

    // -----------------------------------------------------------------------------------------
    // Cancellation.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// The outer token is cancelled while a pull is pending. The iterator waits on nothing but the
    /// token it received through <c>[EnumeratorCancellation]</c>, so the observation is that token
    /// reading cancelled; the outer <c>Task</c> ends cancelled and the <c>finally</c> runs.
    /// </summary>
    [Fact]
    public async Task OuterTokenCancelled_MidStep_CancelsTheEnumeratorsToken()
    {
        using var fountain = new WaterFountain("Oreo");
        using var cts = new CancellationTokenSource();
        var tap = new Tap();

        Task<int> counting = fountain.TotalDropsAsync(DripThenWaitOnTheToken(tap), cts.Token);
        await tap.Parked.Task.WaitAsync(Patience);
        Assert.False(tap.TokenCancelled.Task.IsCompleted, "the enumerator's token was cancelled before the outer one");
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => counting);
        Assert.True(await tap.TokenCancelled.Task.WaitAsync(Patience));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Yielded);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>
    /// The outer token is cancelled while Kotlin's collector is busy and <b>no</b> pull is pending:
    /// the iterator sits at a <c>yield</c>, exactly as on an early abort, and disposing it must
    /// still run its <c>finally</c>.
    /// </summary>
    [Fact]
    public async Task OuterTokenCancelled_BetweenPulls_DisposesTheEnumerator()
    {
        using var fountain = new WaterFountain("Mylo");
        using var cts = new CancellationTokenSource();
        var tap = new Tap();

        // A minute per element on the Kotlin side: the collector is still inside its first lap
        // when the cancel lands.
        Task<int> lapping = fountain.LapSlowlyAsync(60_000, Drip(tap, 5), cts.Token);
        await tap.FirstYield.Task.WaitAsync(Patience);
        await Task.Delay(50);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => lapping);
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Yielded);
        Assert.Equal(1, tap.Finalised);
    }

    /// <summary>The control for the slow collector: left alone, it drinks everything.</summary>
    [Fact]
    public async Task LapSlowly_NotCancelled_CollectsEveryElement()
    {
        using var fountain = new WaterFountain("Mylo");

        Assert.Equal(6, await fountain.LapSlowlyAsync(1, new CountedDrops(1, 2, 3)));
    }

    /// <summary>
    /// The stored flow sees cancellation too: the token its enumerator receives belongs to the call
    /// that collects it (<c>DrainAsync</c>), not to the constructor that stored it.
    /// </summary>
    [Fact]
    public async Task Constructor_Dripper_OuterTokenOfTheCollectingCall_ReachesTheStoredFlow()
    {
        using var cts = new CancellationTokenSource();
        var tap = new Tap();
        using var dripper = new Dripper(DripThenWaitOnTheToken(tap));

        Task<string> draining = dripper.DrainAsync(cts.Token);
        await tap.Parked.Task.WaitAsync(Patience);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => draining);
        Assert.True(await tap.TokenCancelled.Task.WaitAsync(Patience));
        await tap.Closed.Task.WaitAsync(Patience);
        Assert.Equal(1, tap.Finalised);
    }
}

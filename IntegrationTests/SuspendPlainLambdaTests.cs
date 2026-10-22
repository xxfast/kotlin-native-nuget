using System.Collections.Concurrent;
using TestLibrary;
using TestLibrary.Dash;

namespace IntegrationTests;

/// <summary>
/// ADR-231: a plain (non-suspend) lambda parameter on a <c>suspend</c> member binds as a C# delegate
/// parameter of <c>Task&lt;T&gt; XAsync(..., Action&lt;int&gt; onStep, CancellationToken cancellationToken = default)</c>,
/// and on a <c>Flow</c>-returning member as a delegate parameter of the <c>KotlinFlow&lt;T&gt;</c>
/// method. The delegate's callback context lives until the coroutine (or the collection) ends.
/// Every owner with a suspend or Flow route is crossed: a class, a named and a default-named
/// companion, an <c>object</c>, top level, extensions, an enum, a value class and a generic class.
/// <para>
/// Red before ADR-231: every member below is a named skip (the plain lambda falls to the refused
/// legacy shape), so none of the <c>XAsync</c> or Flow members exists (CS1061 / CS0117).
/// </para>
/// Oreo (black with the white middle) and Mylo (brown and creamy) run laps at 3am.
/// </summary>
public class SuspendPlainLambdaTests
{
    [ThreadStatic] private static bool _insideTheCall;

    private static async Task<List<T>> CollectAll<T>(IAsyncEnumerable<T> flow)
    {
        var seen = new List<T>();
        await foreach (T item in flow) seen.Add(item);
        return seen;
    }

    // (1) A class owner: every payload kind, every terminal arm.

    [Fact]
    public async Task Run_InvokesTheDelegateDuringTheSuspension()
    {
        await using var treadmill = new Treadmill("Oreo");
        var seen = new List<int>();

        Assert.Equal(3, await treadmill.RunAsync(3, seen.Add));
        Assert.Equal([0, 1, 2], seen);
    }

    [Fact]
    public async Task Quick_NeverSuspends_StillCallsTheDelegate()
    {
        await using var treadmill = new Treadmill("Mylo");
        int seen = 0;

        Assert.Equal(1, await treadmill.QuickAsync(step => seen = step));
        Assert.Equal(7, seen);
    }

    [Fact]
    public async Task Label_ReturnsTheDelegateResult()
    {
        await using var treadmill = new Treadmill("Oreo");

        Assert.Equal("#4", await treadmill.LabelAsync(4, n => $"#{n}"));
    }

    [Fact]
    public async Task Cheer_StringPayloadCrossesBothWays()
    {
        await using var treadmill = new Treadmill("Mylo");

        Assert.Equal("go Mylo go", await treadmill.CheerAsync(name => $"go {name} go"));
    }

    [Fact]
    public async Task Pace_PrimitivePayloadsOfEveryWidth()
    {
        await using var treadmill = new Treadmill("Oreo");
        (long Meters, double Speed, bool Night) seen = default;

        Assert.True(await treadmill.PaceAsync((meters, speed, night) =>
        {
            seen = (meters, speed, night);
            return night;
        }));
        Assert.Equal((3_000_000_000L, 2.5, true), seen);
    }

    [Fact]
    public async Task Inspect_ClassHandlePayloadReachesTheDelegate()
    {
        await using var treadmill = new Treadmill("Oreo");
        using var baton = new Baton("Mylo");

        Assert.Equal("Mylo has it", await treadmill.InspectAsync(baton, held => $"{held.Holder} has it"));
        // The baton was only lent to Kotlin: this test still owns it.
        Assert.Equal("Mylo", baton.Holder);
    }

    [Fact]
    public async Task Recruit_ClassHandleReturnedFromTheDelegate()
    {
        await using var treadmill = new Treadmill("Oreo");

        Assert.Equal("Mylo", await treadmill.RecruitAsync("Mylo", name => new Baton(name)));
    }

    [Fact]
    public async Task RecruitNow_ClassHandleReturnedFromTheDelegate_OnAnOrdinaryMember()
    {
        await using var treadmill = new Treadmill("Oreo");
        using var mylo = new Baton("Mylo");

        Assert.Equal("Oreo", treadmill.RecruitNow("Oreo", name => new Baton(name)));
        // A borrowed wrapper handed back stays the caller's: Kotlin released only its own retain.
        Assert.Equal("Mylo", treadmill.RecruitNow("ignored", _ => mylo));
        Assert.Equal("Mylo", mylo.Holder);
    }

    [Fact]
    public async Task Relay_GenericTThroughTheDelegate()
    {
        await using var treadmill = new Treadmill("Oreo");
        using var oreo = new Baton("Oreo");
        using var mylo = new Baton("Mylo");

        Assert.Equal(6, await treadmill.RelayAsync(3, n => n * 2));
        Assert.Equal("Oreo and Mylo", await treadmill.RelayAsync("Oreo", name => $"{name} and Mylo"));
        Assert.Null(await treadmill.RelayAsync<string?>(null, seat => seat));
        using Baton passed = await treadmill.RelayAsync(oreo, _ => mylo);
        Assert.Equal("Mylo", passed.Holder);
    }

    [Fact]
    public async Task Swarm_ManyCallsFromAnotherThread()
    {
        await using var treadmill = new Treadmill("Mylo");
        var seen = new ConcurrentBag<int>();
        int onTheCallingThread = 0;

        // A thread-static marker rather than a managed thread id: ids are reused, so a Kotlin thread
        // entering managed code, or this test's continuation, may carry the caller's old id. The
        // marker is set only on the thread making the call, and only while it makes it.
        _insideTheCall = true;
        Task<int> swarming;
        try
        {
            swarming = treadmill.SwarmAsync(500, step =>
            {
                seen.Add(step);
                if (_insideTheCall) Interlocked.Increment(ref onTheCallingThread);
            });
        }
        finally
        {
            _insideTheCall = false;
        }

        Assert.Equal(500, await swarming);
        Assert.Equal(Enumerable.Range(0, 500), seen.OrderBy(n => n));
        Assert.Equal(0, onTheCallingThread);
    }

    [Fact]
    public async Task Crash_FaultsTheTaskWithTheKotlinException()
    {
        await using var treadmill = new Treadmill("Oreo");
        var seen = new List<int>();

        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => treadmill.CrashAsync(seen.Add));

        Assert.Equal("Oreo jammed the treadmill", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ((IKotlinException)ex).KotlinType);
        Assert.Equal([0], seen);
    }

    [Fact]
    public async Task Run_DelegateThrows_FaultsWithTheOriginalException()
    {
        await using var treadmill = new Treadmill("Mylo");
        var boom = new InvalidOperationException("Mylo tripped the safety key");

        var ex = await Assert.ThrowsAsync<InvalidOperationException>(() => treadmill.RunAsync(2, _ => throw boom));

        Assert.Same(boom, ex);
    }

    [Fact]
    public async Task Stall_CancelsThroughTheToken()
    {
        await using var treadmill = new Treadmill("Oreo");
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        var seen = new List<int>();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => treadmill.StallAsync(seen.Add, cts.Token));
        Assert.Equal([0], seen);
    }

    [Fact]
    public async Task Tag_LaterArgumentNull_ThrowsBeforeTheCall()
    {
        await using var treadmill = new Treadmill("Oreo");
        bool called = false;

        var ex = Assert.Throws<ArgumentNullException>(() => { _ = treadmill.TagAsync(_ => called = true, null!); });

        Assert.Equal("name", ex.ParamName);
        Assert.False(called);
        Assert.Equal("Mylo", await treadmill.TagAsync(n => Assert.Equal(4, n), "Mylo"));
    }

    [Fact]
    public async Task Tag_NullDelegate_ThrowsBeforeTheCall()
    {
        await using var treadmill = new Treadmill("Oreo");

        var ex = Assert.Throws<ArgumentNullException>(() => { _ = treadmill.TagAsync(null!, "Mylo"); });

        Assert.Equal("onStep", ex.ParamName);
    }

    // (2) A stored lambda called after the Task ended: ADR-160's late-use behaviour.

    [Fact]
    public async Task Stash_UnitLambdaCalledAfterTheTask_IsDropped()
    {
        await using var treadmill = new Treadmill("Oreo");
        var seen = new List<int>();

        Assert.Equal(1, await treadmill.StashAsync(seen.Add));
        Assert.Equal("called", treadmill.CallStashedStep());

        Assert.Equal([1], seen);
    }

    [Fact]
    public async Task StashFormat_ReturningLambdaCalledAfterTheTask_ReportsObjectDisposed()
    {
        await using var treadmill = new Treadmill("Mylo");

        Assert.Equal("lap 1", await treadmill.StashFormatAsync(n => $"lap {n}"));

        string report = treadmill.CallStashedFormatSafely();
        Assert.StartsWith("stale ", report);
        Assert.Contains("ObjectDisposedException", report);
    }

    // (3) Flow-returning members: the context lives as long as the collection.

    [Fact]
    public async Task Watch_PlainLambdaOnAFlowMember_RunsPerItem()
    {
        await using var treadmill = new Treadmill("Oreo");
        var seen = new List<int>();

        using KotlinFlow<int> watching = treadmill.Watch(3, seen.Add);
        List<int> items = await CollectAll(watching);

        Assert.Equal([0, 1, 2], items);
        Assert.Equal(items, seen);
    }

    [Fact]
    public async Task Watch_ColdFlowCollectedTwice_CallsTheDelegateEachTime()
    {
        await using var treadmill = new Treadmill("Mylo");
        var seen = new List<int>();

        using KotlinFlow<int> watching = treadmill.Watch(2, seen.Add);
        await CollectAll(watching);
        await CollectAll(watching);

        Assert.Equal([0, 1, 0, 1], seen);
    }

    [Fact]
    public async Task Commentate_ReturningLambdaPerElement()
    {
        await using var treadmill = new Treadmill("Oreo");

        using KotlinFlow<string> commentary = treadmill.Commentate(2, n => $"lap {n}");

        Assert.Equal(["lap 0", "lap 1"], await CollectAll(commentary));
    }

    [Fact]
    public async Task WatchForever_CancelsThroughTheEnumerator()
    {
        await using var treadmill = new Treadmill("Oreo");
        using var cts = new CancellationTokenSource();
        var seen = new List<int>();

        using KotlinFlow<int> watching = treadmill.WatchForever(seen.Add);
        // A cancelled KotlinFlow enumeration ends cleanly, the shipped Flow behaviour (no exception).
        await foreach (int n in watching.WithCancellation(cts.Token)) cts.Cancel();

        Assert.Equal([0], seen);
    }

    [Fact]
    public async Task WatchCrash_FaultsTheEnumeration()
    {
        await using var treadmill = new Treadmill("Mylo");
        var seen = new List<int>();

        using KotlinFlow<int> watching = treadmill.WatchCrash(seen.Add);
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => CollectAll(watching));

        Assert.Equal("Mylo fell off the treadmill", ex.Message);
        Assert.Equal([0], seen);
    }

    [Fact]
    public async Task Watch_EarlyBreak_EndsTheCollection()
    {
        await using var treadmill = new Treadmill("Oreo");
        var seen = new ConcurrentQueue<int>();

        using (KotlinFlow<int> watching = treadmill.Watch(5, seen.Enqueue))
        {
            await foreach (int n in watching) break;
        }

        // ADR-207's one credit lets the flow run ahead by one element and park inside the next
        // `emit`, so the delegate saw at most items 0 to 2, never all five.
        int[] calls = seen.ToArray();
        Assert.InRange(calls.Length, 1, 3);
        Assert.Equal(Enumerable.Range(0, calls.Length), calls);
    }

    // (4) Static owners.

    [Fact]
    public async Task Sprint_Object_LapsAsync()
    {
        var seen = new List<int>();

        Assert.Equal(2, await Sprint.LapsAsync(2, seen.Add));
        Assert.Equal([0, 1], seen);
    }

    [Fact]
    public async Task Sprint_Object_FetchAsync_GenericT()
    {
        Assert.Equal(9, await Sprint.FetchAsync(3, n => n * 3));
        Assert.Equal("Mylo!", await Sprint.FetchAsync("Mylo", name => name + "!"));
    }

    [Fact]
    public async Task Sprint_Object_HoldAsync_Cancels()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Sprint.HoldAsync(_ => { }, cts.Token));
    }

    [Fact]
    public async Task Sprint_Object_TripAsync_Faults()
    {
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => Sprint.TripAsync(_ => { }));

        Assert.Equal("Mylo tripped on the rug", ex.Message);
    }

    [Fact]
    public async Task Sprint_Object_Stream_FlowWithAPlainLambda()
    {
        var seen = new List<int>();

        using KotlinFlow<int> stream = Sprint.Stream(3, seen.Add);

        Assert.Equal([0, 1, 2], await CollectAll(stream));
        Assert.Equal([0, 1, 2], seen);
    }

    [Fact]
    public async Task Baton_NamedCompanion_HandAsync_ClassHandlePayload()
    {
        string? handedTo = null;

        using Baton baton = await Baton.HandAsync("Oreo", held => handedTo = held.Holder);

        Assert.Equal("Oreo", baton.Holder);
        Assert.Equal("Oreo", handedTo);
    }

    [Fact]
    public async Task Chase_DefaultCompanion_BeginAsync()
    {
        var seen = new List<int>();

        Assert.Equal(2, await Chase.BeginAsync(2, seen.Add));
        Assert.Equal([0, 1], seen);
    }

    [Fact]
    public async Task Chase_DefaultCompanion_Calls_FlowWithAReturningLambda()
    {
        using KotlinFlow<string> calls = Chase.Calls(2, n => $"psst {n}");

        Assert.Equal(["psst 0", "psst 1"], await CollectAll(calls));
    }

    [Fact]
    public async Task Chase_InstanceBesideTheCompanion_PounceAsync()
    {
        await using var chase = new Chase("the red dot");
        string? leapt = null;

        Assert.Equal("the red dot", await chase.PounceAsync(prey => leapt = prey));
        Assert.Equal("the red dot", leapt);
    }

    [Fact]
    public async Task TopLevel_DashAsync()
    {
        var seen = new List<int>();

        Assert.Equal(3, await DashSample.DashAsync(3, seen.Add));
        Assert.Equal([0, 1, 2], seen);
    }

    [Fact]
    public async Task TopLevel_BounceAsync_GenericT()
    {
        Assert.Equal(8, await DashSample.BounceAsync(4, n => n * 2));
        Assert.Equal("Oreo", await DashSample.BounceAsync("Mylo", _ => "Oreo"));
    }

    [Fact]
    public async Task TopLevel_Dashes_FlowWithAPlainLambda()
    {
        var seen = new List<int>();

        using KotlinFlow<int> dashes = DashSample.Dashes(2, seen.Add);

        Assert.Equal([0, 1], await CollectAll(dashes));
        Assert.Equal([0, 1], seen);
    }

    [Fact]
    public async Task Extension_ClassReceiver_WarmUpAsync()
    {
        await using var treadmill = new Treadmill("Mylo");
        int seen = 0;

        Assert.Equal("Mylo warmed up", await treadmill.WarmUpAsync(n => seen = n));
        Assert.Equal(4, seen);
    }

    [Fact]
    public async Task Extension_StringReceiver_ZoomAsync()
    {
        Assert.Equal("OREO", await "Oreo".ZoomAsync(name => name.ToUpperInvariant()));
    }

    [Fact]
    public async Task Enum_SwatAsync()
    {
        string? swatted = null;

        Assert.Equal(1, await ChaseToy.Laser.SwatAsync(label => swatted = label));
        Assert.Equal("the laser dot", swatted);
    }

    [Fact]
    public async Task ValueClass_MeasureAsync()
    {
        Assert.Equal(0 + 2 + 4, await new Strides(3).MeasureAsync(n => n * 2));
    }

    [Fact]
    public async Task GenericClass_ServeAsync()
    {
        await using var kibble = new Bowlful<string>("salmon");
        string? served = null;

        Assert.Equal("salmon", await kibble.ServeAsync(food => served = food));
        Assert.Equal("salmon", served);
    }

    [Fact]
    public async Task SealedArm_StrideAsync()
    {
        await using var trot = new Gait.Trot(3);
        var seen = new List<int>();

        Assert.Equal(3, await trot.StrideAsync(seen.Add));
        Assert.Equal([0, 1, 2], seen);
    }

    [Fact]
    public async Task EnumCompanion_PickAsync()
    {
        Assert.Equal("2 lanes", await LaneExtensions.PickAsync(n => $"{n} lanes"));
    }

    [Fact]
    public async Task ValueClassOverAHandle_TugAsync()
    {
        using var baton = new Baton("Oreo");
        string? tugged = null;

        Assert.Equal("Oreo", await new Leash(baton).TugAsync(holder => tugged = holder));
        Assert.Equal("Oreo", tugged);
        // The baton was only lent to Kotlin: this test still owns it.
        Assert.Equal("Oreo", baton.Holder);
    }

    // (5) A suspend member returning a Flow: the delegate lives as long as the returned KotlinFlow.

    [Fact]
    public async Task Feed_ReturnedFlowCallsTheDelegateOnEveryCollection()
    {
        await using var bowl = new FoodBowl("Oreo");
        var seen = new List<int>();

        using KotlinFlow<int> feeding = await bowl.FeedAsync(2, seen.Add);
        Assert.Equal([0, 1], await CollectAll(feeding));
        Assert.Equal([0, 1], await CollectAll(feeding));

        Assert.Equal([0, 1, 0, 1], seen);
    }

    [Fact]
    public async Task Menu_ReturningLambdaPerElementOfTheReturnedFlow()
    {
        await using var bowl = new FoodBowl("Mylo");

        using KotlinFlow<string> menu = await bowl.MenuAsync(2, n => $"course {n}");

        Assert.Equal(["course 0", "course 1"], await CollectAll(menu));
    }

    [Fact]
    public async Task Spill_FaultsTheTask()
    {
        await using var bowl = new FoodBowl("Oreo");
        var seen = new List<int>();

        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => bowl.SpillAsync(seen.Add));

        Assert.Equal("Oreo knocked the bowl over", ex.Message);
        Assert.Equal([0], seen);
    }

    [Fact]
    public async Task Wait_CancelsThroughTheToken()
    {
        await using var bowl = new FoodBowl("Mylo");
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => bowl.WaitAsync(_ => { }, cts.Token));
    }

    [Fact]
    public async Task MaybeFeed_NullFlow_AndARealOne()
    {
        await using var bowl = new FoodBowl("Oreo");
        var seen = new List<int>();

        Assert.Null(await bowl.MaybeFeedAsync(false, seen.Add));
        using KotlinFlow<int>? feeding = await bowl.MaybeFeedAsync(true, seen.Add);
        Assert.Equal([0], await CollectAll(feeding!));

        Assert.Equal([0], seen);
    }

    [Fact]
    public async Task Sprint_Object_WarmUpLapsAsync_ReturnedFlowCallsTheDelegate()
    {
        var seen = new List<int>();

        using KotlinFlow<int> laps = await Sprint.WarmUpLapsAsync(3, seen.Add);

        Assert.Equal([0, 1, 2], await CollectAll(laps));
        Assert.Equal([0, 1, 2], seen);
    }
}

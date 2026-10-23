using System.Diagnostics;
using TestLibrary;
using TestLibrary.Purrclock;

namespace IntegrationTests;

/// <summary>
/// ADR-230: a <c>Flow</c> family return or property on an owner with no C# instance scope (the
/// file facade, an <c>object</c>, a default-named and a named companion) binds as a static
/// <c>KotlinFlow&lt;T&gt;</c>, <c>KotlinStateFlow&lt;T&gt;</c>, <c>KotlinMutableStateFlow&lt;T&gt;</c>,
/// <c>KotlinSharedFlow&lt;T&gt;</c> or <c>KotlinMutableSharedFlow&lt;T&gt;</c>, collected on an ad-hoc
/// root scope per collection; the enumerator's own job is the only cancellation handle. A static
/// <c>suspend</c> member with a flow result is a <c>Task</c> of the same wrapper.
/// <para>
/// Red before ADR-230: every member below is a named skip (<c>SKIPPED_UNSUPPORTED_RETURN</c>,
/// <c>SKIPPED_UNSUPPORTED_PROPERTY</c>, or the suspend sentence "with a Flow result on an owner with
/// no instance scope"), so none of them exists on the static surface (CS0117).
/// </para>
/// Oreo (black with the white middle) and Mylo (brown and creamy) keep their own time.
/// </summary>
public class StaticFlowTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    private static async Task<List<T>> Drain<T>(IAsyncEnumerable<T> flow)
    {
        var seen = new List<T>();
        await foreach (T item in flow) seen.Add(item);
        return seen;
    }

    private static async Task<T> First<T>(IAsyncEnumerable<T> flow)
    {
        await foreach (T item in flow) return item;
        throw new InvalidOperationException("the flow completed without an element");
    }

    /// <summary>Cancels after the fourth element; the loop must exit cleanly and promptly.</summary>
    private static async Task<List<T>> CancelAfterFour<T>(IAsyncEnumerable<T> flow)
    {
        using var cts = new CancellationTokenSource();
        var seen = new List<T>();
        var stopwatch = Stopwatch.StartNew();
        await foreach (T item in flow.WithCancellation(cts.Token))
        {
            seen.Add(item);
            if (seen.Count == 4) cts.Cancel();
            Assert.True(stopwatch.Elapsed < Patience, "the token did not stop the static flow");
        }
        Assert.True(cts.IsCancellationRequested);
        return seen;
    }

    // (1) The top level, on the file facade.

    [Fact]
    public async Task TopLevel_Purrs_CollectsOnTheFileFacade()
    {
        using KotlinFlow<int> purrs = Chimes.Purrs(3);

        Assert.Equal([0, 1, 2], await Drain(purrs));
    }

    [Fact]
    public async Task TopLevel_Purrs_ColdFlowCollectsAgainFromTheStart()
    {
        using KotlinFlow<int> purrs = Chimes.Purrs(2);

        Assert.Equal([0, 1], await Drain(purrs));
        Assert.Equal([0, 1], await Drain(purrs));
    }

    [Fact]
    public async Task TopLevel_Echoes_GenericElementCrossesBoxed()
    {
        using KotlinFlow<string> mylo = Chimes.Echoes("Mylo", 2);
        using KotlinFlow<int> four = Chimes.Echoes(4, 3);

        Assert.Equal(["Mylo", "Mylo"], await Drain(mylo));
        Assert.Equal([4, 4, 4], await Drain(four));
    }

    [Fact]
    public async Task TopLevel_DroppedPurrs_FaultsMidStreamAfterTheFirstElement()
    {
        var seen = new List<int>();

        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(async () =>
        {
            await foreach (int n in Chimes.DroppedPurrs()) seen.Add(n);
        });

        Assert.Equal([1], seen);
        Assert.Equal("Mylo knocked the clock off the shelf", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public async Task TopLevel_EndlessPurrs_BreakDisposesTheEnumeratorAndStops()
    {
        var seen = new List<int>();
        await foreach (int n in Chimes.EndlessPurrs())
        {
            seen.Add(n);
            if (seen.Count == 3) break;
        }

        Assert.Equal([0, 1, 2], seen);
    }

    [Fact]
    public async Task TopLevel_EndlessPurrs_CancelsThroughTheToken()
    {
        List<int> seen = await CancelAfterFour(Chimes.EndlessPurrs());

        Assert.Equal([0, 1, 2, 3], seen.Take(4));
    }

    [Fact]
    public async Task TopLevel_DinnerBell_StateFlowValueAndCollection()
    {
        using KotlinStateFlow<string> bell = Chimes.DinnerBell();

        Assert.Equal("Oreo, dinner", bell.Value);
        Assert.Equal("Oreo, dinner", await First(bell));
    }

    [Fact]
    public async Task TopLevel_Chorus_ColdFlowPropertyIsReReadEachCollection()
    {
        Assert.Equal(["Oreo", "Mylo"], await Drain(Chimes.Chorus));
        Assert.Equal(["Oreo", "Mylo"], await Drain(Chimes.Chorus));
    }

    [Fact]
    public async Task TopLevel_Bedtime_MutableStateFlowPropertyWriteReachesKotlin()
    {
        using KotlinMutableStateFlow<int> bedtime = Chimes.Bedtime;
        bedtime.Value = 22;

        using KotlinMutableStateFlow<int> again = Chimes.Bedtime;
        Assert.Equal(22, again.Value);
        Assert.Equal(22, await First(again));
        bedtime.Value = 21;
    }

    [Fact]
    public void TopLevel_Yowls_MutableSharedFlowPropertyTryEmitLandsInTheReplayCache()
    {
        using KotlinMutableSharedFlow<string> yowls = Chimes.Yowls;

        Assert.True(yowls.TryEmit("Oreo wants out"));
        Assert.True(yowls.TryEmit("Mylo wants in"));

        using KotlinMutableSharedFlow<string> again = Chimes.Yowls;
        Assert.Equal(["Oreo wants out", "Mylo wants in"], again.ReplayCache);
    }

    // (2) An `object`.

    [Fact]
    public async Task PurrClock_Ticks_CollectsOnTheStaticClass()
    {
        using KotlinFlow<int> ticks = PurrClock.Ticks(3);

        Assert.Equal([0, 1, 2], await Drain(ticks));
        Assert.Equal([0, 1, 2], await Drain(ticks));
    }

    [Fact]
    public async Task PurrClock_Echo_GenericElementCrossesEverySeam()
    {
        using var oreo = new Cuckoo("Oreo");

        Assert.Equal(["Mylo", "Mylo"], await Drain(PurrClock.Echo("Mylo", 2)));
        Assert.Equal([Posture.Sprawled], await Drain(PurrClock.Echo(Posture.Sprawled, 1)));
        List<Cuckoo> same = await Drain(PurrClock.Echo(oreo, 2));
        Assert.Equal(["Oreo", "Oreo"], same.Select(c => c.Sitter));
        foreach (Cuckoo c in same) c.Dispose();
    }

    [Fact]
    public async Task PurrClock_ClockCats_ClassElementIsAFreshHandlePerItem()
    {
        List<ClockCat> clockCats = await Drain(PurrClock.ClockCats());

        Assert.Equal(["Oreo", "Mylo"], clockCats.Select(k => k.Name));
        foreach (ClockCat k in clockCats) k.Dispose();
    }

    [Fact]
    public async Task PurrClock_Broken_FaultsMidStream()
    {
        var seen = new List<int>();

        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(async () =>
        {
            await foreach (int n in PurrClock.Broken()) seen.Add(n);
        });

        Assert.Equal([1], seen);
        Assert.Equal("Oreo stopped the clock", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public async Task PurrClock_Forever_BreakDisposesTheEnumerator()
    {
        var seen = new List<int>();
        await foreach (int n in PurrClock.Forever())
        {
            seen.Add(n);
            if (seen.Count == 2) break;
        }

        Assert.Equal([0, 1], seen);
        // The collection is gone, so a fresh one starts from zero again.
        Assert.Equal(0, await First(PurrClock.Forever()));
    }

    [Fact]
    public async Task PurrClock_Forever_CancelsThroughTheToken()
    {
        List<int> seen = await CancelAfterFour(PurrClock.Forever());

        Assert.Equal([0, 1, 2, 3], seen.Take(4));
    }

    [Fact]
    public async Task PurrClock_Beats_ColdFlowProperty()
    {
        Assert.Equal([1, 2], await Drain(PurrClock.Beats));
    }

    [Fact]
    public async Task PurrClock_PoseAndStance_StateFlowOfAnEnum()
    {
        using KotlinStateFlow<Posture> pose = PurrClock.Pose;
        using KotlinStateFlow<Posture> stance = PurrClock.Stance();

        Assert.Equal(Posture.Loaf, pose.Value);
        Assert.Equal(Posture.Loaf, stance.Value);
        Assert.Equal(Posture.Loaf, await First(stance));
    }

    [Fact]
    public async Task PurrClock_HourAndAlarm_MutableStateFlowWriteIsSharedStorage()
    {
        using KotlinMutableStateFlow<int> hour = PurrClock.Hour;
        using KotlinMutableStateFlow<int> alarm = PurrClock.Alarm();
        int before = hour.Value;

        alarm.Value = 6;
        Assert.Equal(6, hour.Value);
        Assert.Equal(6, await First(hour));

        hour.Value = before;
        Assert.Equal(before, alarm.Value);
    }

    [Fact]
    public async Task PurrClock_BulletinAndNews_SharedFlowReplaysTheLatestEdition()
    {
        using KotlinSharedFlow<int> bulletin = PurrClock.Bulletin;
        using KotlinSharedFlow<int> news = PurrClock.News();

        Assert.Equal(7, await First(bulletin));
        Assert.Equal(7, await First(news));
        Assert.Equal([7], news.ReplayCache);
    }

    [Fact]
    public async Task PurrClock_SightingsAndGossip_MutableSharedFlowEmitReachesKotlin()
    {
        using KotlinMutableSharedFlow<string> gossip = PurrClock.Gossip();
        await gossip.EmitAsync("Oreo on the fence");
        Assert.True(gossip.TryEmit("Mylo in the laundry"));

        using KotlinMutableSharedFlow<string> sightings = PurrClock.Sightings;
        Assert.Equal(["Oreo on the fence", "Mylo in the laundry"], sightings.ReplayCache);
        Assert.Equal(["Oreo on the fence", "Mylo in the laundry"], PurrClock.LatestSightings());
    }

    [Fact]
    public async Task PurrClock_WarmUpAsync_SuspendMemberWithAColdFlowResult()
    {
        using KotlinFlow<int> warm = await PurrClock.WarmUpAsync(3);

        Assert.Equal([0, 10, 20], await Drain(warm));
    }

    [Fact]
    public async Task PurrClock_DialAndWinderAsync_SuspendMembersWithHotFlowResults()
    {
        using KotlinStateFlow<int> dial = await PurrClock.DialAsync();
        using KotlinMutableStateFlow<int> winder = await PurrClock.WinderAsync();
        int before = dial.Value;

        winder.Value = 12;
        Assert.Equal(12, dial.Value);
        winder.Value = before;
    }

    [Fact]
    public async Task PurrClock_LatestNewsAsync_SuspendMemberWithASharedFlowResult()
    {
        using KotlinSharedFlow<int> news = await PurrClock.LatestNewsAsync();

        Assert.Equal(7, await First(news));
    }

    // (3) A default-named companion, static beside the class's own instance members.

    [Fact]
    public async Task Cuckoo_Calls_CompanionFlowIsStaticOnTheClass()
    {
        using var mylo = new Cuckoo("Mylo");

        Assert.Equal(["cuckoo", "cuckoo"], await Drain(Cuckoo.Calls()));
        Assert.Equal(["Mylo wobbles"], await Drain(mylo.Wobbles()));
    }

    [Fact]
    public async Task Cuckoo_Repeat_CompanionGenericElement()
    {
        Assert.Equal(["Oreo", "Oreo"], await Drain(Cuckoo.Repeat("Oreo")));
        Assert.Equal([9, 9], await Drain(Cuckoo.Repeat(9)));
    }

    [Fact]
    public async Task Cuckoo_ChirpsAndPerched_CompanionFlowProperties()
    {
        Assert.Equal([3, 2, 1], await Drain(Cuckoo.Chirps));
        using KotlinStateFlow<string> perched = Cuckoo.Perched;
        Assert.False(string.IsNullOrEmpty(perched.Value));
    }

    [Fact]
    public async Task Cuckoo_Perch_MutableStateFlowWriteShowsOnTheStateFlowProperty()
    {
        using KotlinMutableStateFlow<string> perch = Cuckoo.Perch();
        using KotlinStateFlow<string> perched = Cuckoo.Perched;

        perch.Value = "Mylo";
        Assert.Equal("Mylo", perched.Value);
        Assert.Equal("Mylo", await First(perched));
        perch.Value = "Oreo";
    }

    [Fact]
    public async Task Cuckoo_Jammed_FaultsMidStream()
    {
        var seen = new List<string>();

        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(async () =>
        {
            await foreach (string s in Cuckoo.Jammed()) seen.Add(s);
        });

        Assert.Equal(["cuck"], seen);
        Assert.Equal("Mylo sat on the cuckoo", ex.Message);
        Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)ex).KotlinType);
    }

    [Fact]
    public async Task Cuckoo_Endless_CancelsThroughTheToken()
    {
        List<string> seen = await CancelAfterFour(Cuckoo.Endless());

        Assert.All(seen, s => Assert.Equal("cuckoo", s));
    }

    [Fact]
    public async Task Cuckoo_WarbleAndRoostAsync_CompanionSuspendMembersWithFlowResults()
    {
        using KotlinFlow<string> warble = await Cuckoo.WarbleAsync();
        using KotlinMutableStateFlow<string> roost = await Cuckoo.RoostAsync();

        Assert.Equal(["warble"], await Drain(warble));
        roost.Value = "Mylo";
        using KotlinStateFlow<string> perched = Cuckoo.Perched;
        Assert.Equal("Mylo", perched.Value);
        roost.Value = "Oreo";
    }

    // (4) A NAMED companion: the Kotlin call is `Hourglass.Sand.grains`, the C# surface is static
    // on `Hourglass`.

    [Fact]
    public async Task Hourglass_Grains_NamedCompanionFlow()
    {
        Assert.Equal([1, 2, 3], await Drain(Hourglass.Grains(3)));
    }

    [Fact]
    public void Hourglass_Level_NamedCompanionStateFlowProperty()
    {
        using KotlinStateFlow<int> level = Hourglass.Level;

        Assert.Equal(100, level.Value);
    }

    // (5) An enum body (the entry crosses as its ordinal) and an enum companion, both on
    // `ChimeExtensions`.

    [Fact]
    public async Task Chime_Play_EnumBodyFlowTakesTheEntryAsThis()
    {
        Assert.Equal([1, 2, 3, 4], await Drain(Chime.Westminster.Play()));
        Assert.Equal([1], await Drain(Chime.Cuckoo.Play()));
    }

    [Fact]
    public async Task Chime_Hum_EnumBodyGenericElement()
    {
        Assert.Equal(["Oreo", "Oreo"], await Drain(Chime.Whittington.Hum("Oreo")));
    }

    [Fact]
    public async Task Chime_CurrentAndEcho_EnumBodyStateFlowAndFlowProperty()
    {
        using KotlinStateFlow<string> current = Chime.Cuckoo.Current();

        Assert.Equal("CUCKOO", current.Value);
        Assert.Equal(["WHITTINGTON echoes"], await Drain(Chime.Whittington.Echo));
    }

    [Fact]
    public async Task Chime_RingAndJam_EnumBodyCancelsAndFaults()
    {
        List<int> seen = await CancelAfterFour(Chime.Westminster.Ring());
        Assert.Equal([0, 1, 2, 3], seen.Take(4));

        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => Drain(Chime.Cuckoo.Jam()));
        Assert.Equal("Mylo jammed the CUCKOO chime", ex.Message);
    }

    [Fact]
    public async Task Chime_PreludeAsync_EnumBodySuspendMemberWithAFlowResult()
    {
        using KotlinFlow<int> prelude = await Chime.Whittington.PreludeAsync();

        Assert.Equal([20], await Drain(prelude));
    }

    [Fact]
    public async Task ChimeExtensions_AllLoudestAndSampler_EnumCompanionFlows()
    {
        Assert.Equal([Chime.Westminster, Chime.Whittington, Chime.Cuckoo], await Drain(ChimeExtensions.All()));
        Assert.Equal([4, 2, 1], await Drain(ChimeExtensions.Sampler));

        using KotlinMutableStateFlow<Chime> loudest = ChimeExtensions.Loudest();
        loudest.Value = Chime.Cuckoo;
        using KotlinMutableStateFlow<Chime> again = ChimeExtensions.Loudest();
        Assert.Equal(Chime.Cuckoo, again.Value);
        loudest.Value = Chime.Westminster;
    }

    // (6) A value class: instance methods of the record struct, the underlying crossing first.

    [Fact]
    public async Task Pendulum_Beats_ValueClassFlowPassesTheUnderlying()
    {
        Assert.Equal([250, 500, 750], await Drain(new Pendulum(250).Beats(3)));
    }

    [Fact]
    public async Task Pendulum_Pulse_ValueClassFlowProperty()
    {
        Assert.Equal([5, 10], await Drain(new Pendulum(5).Pulse));
        Assert.Equal([5, 10], await Drain(new Pendulum(5).Pulse));
    }

    [Fact]
    public async Task Pendulum_CarryAndReading_ValueClassGenericAndStateFlow()
    {
        Assert.Equal(["Mylo"], await Drain(new Pendulum(1).Carry("Mylo")));
        using KotlinStateFlow<int> reading = new Pendulum(42).Reading();
        Assert.Equal(42, reading.Value);
    }

    [Fact]
    public async Task Pendulum_StallAndWarmAsync_ValueClassFaultAndSuspendFlow()
    {
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => Drain(new Pendulum(9).Stall()));
        Assert.Equal("Oreo stopped a 9 ms swing", ex.Message);

        using KotlinFlow<int> warm = await new Pendulum(7).WarmAsync();
        Assert.Equal([7, 7], await Drain(warm));
    }
}

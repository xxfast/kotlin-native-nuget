using TestLibrary.Cat;
using TestLibrary.Issue115;
using Cadence = TestLibrary.Metronome.Cadence;
using Tallies = TestLibrary.Metronome.Tallies;

namespace IntegrationTests;

/// <summary>
/// Stored-callback pair null guard (ROADMAP line 24, memo
/// <c>docs/research/roadmap/stored-callback-pair-null-guard.md</c>). Every generated C# entry point
/// that takes a delegate or a listener rejects <c>null</c> with <c>ArgumentNullException</c> at the
/// call site, whatever the Kotlin nullability and whatever the route:
/// <list type="bullet">
///   <item>the ADR-037 stored-callback lambda pair (<c>IDisposable AddX(Action&lt;T&gt; listener)</c>),</item>
///   <item>the ADR-039 interface-bridge pair (<c>IDisposable AddX(IFoo listener)</c>),</item>
///   <item>both on an ordinary class and on a sealed arm,</item>
///   <item>the per-call lambda on the ADR-062 plan route (class member, top-level, sealed arm) and on
///     the legacy route (nullable Kotlin spelling, already guarded: the regression anchor).</item>
/// </list>
/// <para>
/// Before the fix a pair's <c>AddX(null!)</c> succeeds and returns a live subscription; the null is
/// dereferenced only when Kotlin next fires the listener, surfacing as a <c>NugetManagedException</c>
/// out of whatever C# call triggered the emission. A plan-route per-call <c>null!</c> surfaces as a
/// <c>NullReferenceException</c> rethrown from inside the callback. Neither names the argument.
/// </para>
/// <para>
/// Each pair fact then fires the emission: nothing was subscribed, so it runs cleanly. Each per-call
/// fact then makes a real call on the same receiver, since for that route the call IS the emission.
/// </para>
/// <para>
/// No LeakTests row: the guard is the first statement, ahead of <c>RegisterCtx</c> and the native
/// subscribe, so a rejected call mints no ctx key and no Kotlin handle.
/// </para>
/// <para>Oreo tries to subscribe nobody to everything. Mylo has to live with the consequences.</para>
/// </summary>
public class CallbackNullGuardTests
{
    private sealed class RecordingCatListener : ICatEventListener
    {
        public List<string> Meows { get; } = new();
        public void OnMeow(string message) => Meows.Add(message);
        public void OnPurr() { }
        public void Dispose() { }
    }

    private sealed class RecordingWatcher : IJobWatcher
    {
        public List<string> Reasons { get; } = new();
        public void OnWake(string reason) => Reasons.Add(reason);
        public void Dispose() { }
    }

    // ---------------------------------------------------------------------------------------
    // ADR-037 stored-callback lambda pair
    // ---------------------------------------------------------------------------------------

    /// <summary>Non-null Kotlin spelling, ordinary class: <c>Cat.addMoodListener((Mood) -> Unit)</c>.</summary>
    [Fact]
    public void AddMoodListener_Null_ThrowsArgumentNullAtTheCallSite()
    {
        using var oreo = new Cat("Oreo", 9);

        var ex = Assert.Throws<ArgumentNullException>(() => oreo.AddMoodListener(null!));
        Assert.Equal("listener", ex.ParamName);

        // Nothing was subscribed: the emission runs cleanly.
        oreo.TriggerMoodChange(Mood.Happy);
    }

    /// <summary>Nullable Kotlin spelling <c>((Int) -> Unit)?</c>: still no null on the C# side.</summary>
    [Fact]
    public void AddPounceListener_NullableKotlinType_Null_ThrowsArgumentNullAtTheCallSite()
    {
        using var source = new CatEventSource("Oreo");

        var ex = Assert.Throws<ArgumentNullException>(() => source.AddPounceListener(null!));
        Assert.Equal("listener", ex.ParamName);

        source.Pounce(2);
    }

    /// <summary>The nullable-spelled pair still binds and delivers for a real listener.</summary>
    [Fact]
    public void AddPounceListener_NullableKotlinType_RealListener_StillDelivers()
    {
        using var source = new CatEventSource("Mylo");
        var pounces = new List<int>();

        using (source.AddPounceListener(n => pounces.Add(n)))
        {
            source.Pounce(3);
        }
        source.Pounce(1);

        Assert.Equal(new[] { 1, 2, 3 }, pounces);
    }

    /// <summary>Sealed <c>data class</c> arm: <c>Job.Running.addTicker((String) -> Unit)</c>.</summary>
    [Fact]
    public void AddTicker_SealedArm_Null_ThrowsArgumentNullAtTheCallSite()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(40);

        var ex = Assert.Throws<ArgumentNullException>(() => oreo.AddTicker(null!));
        Assert.Equal("listener", ex.ParamName);

        oreo.Tick();
    }

    /// <summary>
    /// A lambda pair on a disposed receiver throws <c>ObjectDisposedException</c>, like its
    /// interface-bridge sibling, instead of handing <c>IntPtr.Zero</c> to Kotlin.
    /// </summary>
    [Fact]
    public void AddMoodListener_DisposedReceiver_ThrowsObjectDisposed()
    {
        var oreo = new Cat("Oreo", 9);
        oreo.Dispose();

        var ex = Assert.Throws<ObjectDisposedException>(() => oreo.AddMoodListener(_ => { }));
        Assert.Equal(nameof(Cat), ex.ObjectName);
    }

    /// <summary>Same check on the nullable-spelled lambda pair.</summary>
    [Fact]
    public void AddPounceListener_DisposedReceiver_ThrowsObjectDisposed()
    {
        var mylo = new CatEventSource("Mylo");
        mylo.Dispose();

        var ex = Assert.Throws<ObjectDisposedException>(() => mylo.AddPounceListener(_ => { }));
        Assert.Equal(nameof(CatEventSource), ex.ObjectName);
    }

    /// <summary>
    /// Argument validation precedes receiver state: a null listener on a disposed receiver names the
    /// argument, the interface-bridge ordering the memo recommends.
    /// </summary>
    [Fact]
    public void AddMoodListener_NullOnDisposedReceiver_ThrowsArgumentNullFirst()
    {
        var oreo = new Cat("Oreo", 9);
        oreo.Dispose();

        var ex = Assert.Throws<ArgumentNullException>(() => oreo.AddMoodListener(null!));
        Assert.Equal("listener", ex.ParamName);
    }

    // ---------------------------------------------------------------------------------------
    // ADR-039 interface-bridge pair
    // ---------------------------------------------------------------------------------------

    /// <summary>Non-null Kotlin spelling, ordinary class: <c>CatEventSource.addListener(CatEventListener)</c>.</summary>
    [Fact]
    public void AddListener_Null_ThrowsArgumentNullAtTheCallSite()
    {
        using var source = new CatEventSource("Mylo");

        var ex = Assert.Throws<ArgumentNullException>(() => source.AddListener(null!));
        Assert.Equal("listener", ex.ParamName);

        source.Trigger();
    }

    /// <summary>
    /// Argument validation precedes receiver state on the interface-bridge pair too: the guard goes
    /// ahead of its existing <c>_handle == IntPtr.Zero</c> check.
    /// </summary>
    [Fact]
    public void AddListener_NullOnDisposedReceiver_ThrowsArgumentNullFirst()
    {
        var mylo = new CatEventSource("Mylo");
        mylo.Dispose();

        var ex = Assert.Throws<ArgumentNullException>(() => mylo.AddListener(null!));
        Assert.Equal("listener", ex.ParamName);
    }

    /// <summary>Nullable Kotlin spelling <c>CatEventListener?</c>.</summary>
    [Fact]
    public void AddMaybeListener_NullableKotlinType_Null_ThrowsArgumentNullAtTheCallSite()
    {
        using var source = new CatEventSource("Oreo");

        var ex = Assert.Throws<ArgumentNullException>(() => source.AddMaybeListener(null!));
        Assert.Equal("listener", ex.ParamName);

        source.Trigger();
    }

    /// <summary>The nullable-spelled interface pair still binds and delivers for a real listener.</summary>
    [Fact]
    public void AddMaybeListener_NullableKotlinType_RealListener_StillDelivers()
    {
        using var source = new CatEventSource("Mylo");
        var listener = new RecordingCatListener();

        using (source.AddMaybeListener(listener))
        {
            source.Trigger();
        }
        source.Trigger();

        Assert.Equal(new[] { "Mylo says meow!" }, listener.Meows);
    }

    /// <summary>
    /// Sealed <c>data object</c> arm: <c>Job.Idle.addWatcher(JobWatcher)</c>. <c>Job.Idle</c> is a
    /// process-global singleton, so if the guard is missing the leaked subscription is captured and
    /// disposed here; otherwise it would poison every later <c>Wake</c> in the run.
    /// </summary>
    [Fact]
    public void AddWatcher_SealedDataObjectArm_Null_ThrowsArgumentNullAtTheCallSite()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();
        IDisposable? leaked = null;
        try
        {
            var ex = Assert.Throws<ArgumentNullException>(() => leaked = mylo.AddWatcher(null!));
            Assert.Equal("listener", ex.ParamName);
        }
        finally
        {
            leaked?.Dispose();
        }

        var watcher = new RecordingWatcher();
        using (mylo.AddWatcher(watcher))
        {
            mylo.Wake("oreo-sat-on-the-keyboard");
        }
        Assert.Equal(new[] { "oreo-sat-on-the-keyboard" }, watcher.Reasons);
    }

    // ---------------------------------------------------------------------------------------
    // Per-call lambda, ADR-062 plan route (non-null Kotlin spelling)
    // ---------------------------------------------------------------------------------------

    /// <summary>Class member. The Kotlin parameter is <c>pouncer</c>, so the guard names it.</summary>
    [Fact]
    public void CountPounces_Null_ThrowsArgumentNullNamingTheParameter()
    {
        using var source = new CatEventSource("Oreo");

        var ex = Assert.Throws<ArgumentNullException>(() => source.CountPounces(null!));
        Assert.Equal("pouncer", ex.ParamName);

        var seen = new List<int>();
        Assert.Equal(3, source.CountPounces(n => seen.Add(n)));
        Assert.Equal(new[] { 1, 2, 3 }, seen);
    }

    /// <summary>Top-level position (ADR-007 file class).</summary>
    [Fact]
    public void TallyTicks_TopLevel_Null_ThrowsArgumentNullAtTheCallSite()
    {
        var ex = Assert.Throws<ArgumentNullException>(() => Tallies.TallyTicks(null!));
        Assert.Equal("listener", ex.ParamName);

        Assert.Equal(3, Tallies.TallyTicks(_ => { }));
    }

    /// <summary>Sealed arm receiver.</summary>
    [Fact]
    public void CountTicks_SealedArm_Null_ThrowsArgumentNullAtTheCallSite()
    {
        using var steady = new Cadence.Steady(span: 2);

        var ex = Assert.Throws<ArgumentNullException>(() => steady.CountTicks(null!));
        Assert.Equal("listener", ex.ParamName);

        Assert.Equal(2, steady.CountTicks(_ => { }));
    }

    // ---------------------------------------------------------------------------------------
    // Per-call lambda, legacy route (nullable Kotlin spelling): green before the fix, anchors
    // ---------------------------------------------------------------------------------------

    /// <summary>
    /// Nullable Kotlin spelling on the legacy route: already guarded by <c>rejectsNullDelegate</c>.
    /// Must stay green when the guard goes unconditional.
    /// </summary>
    [Fact]
    public void OnMaybePounce_NullableKotlinType_Null_ThrowsArgumentNullNamingTheParameter()
    {
        using var source = new CatEventSource("Mylo");

        var ex = Assert.Throws<ArgumentNullException>(() => source.OnMaybePounce(null!));
        Assert.Equal("pouncer", ex.ParamName);

        var seen = new List<int>();
        source.OnMaybePounce(n => seen.Add(n));
        Assert.Equal(new[] { 1, 2, 3 }, seen);
    }
}

using TestLibrary.Issue115;

namespace IntegrationTests;

/// <summary>
/// Issue #115 / ADR-116's 2026-09-13 amendment, the <b>pair</b> row. A stored-callback
/// <c>addX</c>/<c>removeX</c> pair (ADR-037) and an interface-bridge pair (ADR-039) declared on a
/// <b>sealed arm</b> are absent from C# and named <c>SKIPPED_UNSUPPORTED_COMBINATION</c>
/// (<c>SEALED_SUBCLASS_UNROUTED</c>), where the same pair on an ordinary class binds today:
/// <c>Cat.AddMoodListener(mood =&gt; ...)</c> and <c>CatEventSource.AddListener(listener)</c> both
/// hand back an <c>IDisposable</c> subscription. ADR-118 re-keyed the suspend route onto the arms,
/// ADR-124 the Flow route, and the amendment the per-call lambda route; this is the fourth row and
/// the same shape of fix — the pair builders are already prefix-keyed.
/// <para>
/// Every <c>Add*</c> call below is CS1061 today: <c>Job.Running</c> has no <c>AddTicker</c> and
/// <c>Job.Idle</c> has no <c>AddWatcher</c>. That is the red. Once the route re-keys, they arrive
/// off <c>job_running_addTicker</c>/<c>job_running_removeTicker</c> and
/// <c>job_idle_addWatcher</c>/<c>job_idle_removeWatcher</c>, through the same
/// <c>NugetSubscription</c> the ordinary-class routes return.
/// </para>
/// <para>
/// Two owner kinds, not one. Oreo's <c>AddTicker</c> is the stored-callback half on a per-instance
/// <c>data class</c> arm; Mylo's <c>AddWatcher</c> is the interface-bridge half on a
/// <c>data object</c> arm, where the receiver is the singleton's handle rather than a static. A
/// route that binds only the lambda-storage shape, or only the <c>data class</c> receiver, cannot
/// pass both.
/// </para>
/// <para>
/// <b>Singleton caveat</b>: <c>Job.Idle</c> is a Kotlin <c>data object</c>, so its watcher list is
/// process-global and outlives each test. Every subscription on it is <c>using</c>-disposed here,
/// and every assertion reads its own recorder rather than a total, so a later <c>Wake</c> cannot
/// fire a stale bridge into a freed <c>GCHandle</c>.
/// </para>
/// </summary>
public class SealedArmCallbackPairTests
{
    /// <summary>A C#-side <c>IJobWatcher</c>, the <c>RecordingCatListener</c> shape one arity down.</summary>
    private sealed class RecordingWatcher : IJobWatcher
    {
        public List<string> Reasons { get; } = new();
        public void OnWake(string reason) => Reasons.Add(reason);
        public void Dispose() { }
    }

    /// <summary>
    /// The stored-callback subscribe half on a <c>data class</c> arm. The lambda lives in C#, and
    /// Oreo's <c>Tick()</c> hands it <c>"tick:40"</c> through the thunk.
    /// </summary>
    [Fact]
    public void AddTicker_StoredCallbackPairOnASealedArm_FiresOnTick()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(40);
        var ticks = new List<string>();
        using IDisposable sub = oreo.AddTicker(s => ticks.Add(s));

        oreo.Tick();

        Assert.Equal(new[] { "tick:40" }, ticks);
    }

    /// <summary>
    /// Disposing the subscription must reach <c>removeTicker</c> on the arm's own export, not just
    /// drop the C# side: a <c>Tick()</c> afterwards delivers nothing.
    /// </summary>
    [Fact]
    public void AddTicker_StoredCallbackPairOnASealedArm_NoDeliveryAfterDispose()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(7);
        var ticks = new List<string>();
        IDisposable sub = oreo.AddTicker(s => ticks.Add(s));

        sub.Dispose();
        oreo.Tick();

        Assert.Empty(ticks);
    }

    /// <summary>
    /// Two live subscriptions on one arm instance: the arm's storage is per-receiver, so both
    /// lambdas hear the same tick and a route that overwrites rather than appends loses one.
    /// </summary>
    [Fact]
    public void AddTicker_TwoSubscriptionsOnOneArm_BothFire()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(12);
        var first = new List<string>();
        var second = new List<string>();
        using IDisposable subOne = oreo.AddTicker(s => first.Add(s));
        using IDisposable subTwo = oreo.AddTicker(s => second.Add(s));

        oreo.Tick();

        Assert.Equal(new[] { "tick:12" }, first);
        Assert.Equal(new[] { "tick:12" }, second);
    }

    /// <summary>
    /// The interface-bridge half on the <c>data object</c> arm: a C#-implemented
    /// <c>IJobWatcher</c> receives <c>OnWake("x")</c> across the bridged slot, with the singleton's
    /// handle as the receiver.
    /// </summary>
    [Fact]
    public void AddWatcher_InterfaceBridgePairOnADataObjectArm_ReceivesWake()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();
        var watcher = new RecordingWatcher();
        using IDisposable sub = mylo.AddWatcher(watcher);

        mylo.Wake("x");

        Assert.Equal(new[] { "x" }, watcher.Reasons);
    }

    /// <summary>
    /// The dispose half on the singleton arm. Mandatory rather than symmetric: an undisposed
    /// bridge on a process-wide <c>data object</c> would outlive this test and fire into a freed
    /// <c>GCHandle</c> on the next <c>Wake</c>.
    /// </summary>
    [Fact]
    public void AddWatcher_InterfaceBridgePairOnADataObjectArm_NoDeliveryAfterDispose()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();
        var watcher = new RecordingWatcher();
        IDisposable sub = mylo.AddWatcher(watcher);

        sub.Dispose();
        mylo.Wake("after-dispose");

        Assert.Empty(watcher.Reasons);
    }

    /// <summary>
    /// Each subscription owns its own recorder, so a second watcher registered while the first is
    /// live reads only what it was told — never a process-wide total on the singleton arm.
    /// </summary>
    [Fact]
    public void AddWatcher_TwoWatchersOnTheSingletonArm_EachReadsItsOwnRecording()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();
        var oreoWatcher = new RecordingWatcher();
        var myloWatcher = new RecordingWatcher();
        using IDisposable subOne = mylo.AddWatcher(oreoWatcher);
        using IDisposable subTwo = mylo.AddWatcher(myloWatcher);

        mylo.Wake("breakfast");

        Assert.Equal(new[] { "breakfast" }, oreoWatcher.Reasons);
        Assert.Equal(new[] { "breakfast" }, myloWatcher.Reasons);
    }

    /// <summary>
    /// The control arm: <c>Job.Done</c> declares no pair, so the re-key must hand it nothing.
    /// </summary>
    [Fact]
    public void Done_TheControlArm_GainsNoSubscriptionMembers()
    {
        string[] members = typeof(Job.Done).GetMethods()
            .Select(method => method.Name)
            .Where(name => name.StartsWith("Add") || name.StartsWith("Remove"))
            .ToArray();

        Assert.Empty(members);
    }
}

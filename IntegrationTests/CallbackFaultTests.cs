using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-161: a forward callback that fails must not take the host process with it.
///
/// <para><b>Every throwing cell in this file is process-fatal against the build of 2026-09-22.</b>
/// The generated thunk catches the managed exception and calls
/// <c>Environment.FailFast("nuget: unhandled exception in &lt;delegate&gt;", ex)</c>
/// (<c>CirCallbackRenderer.appendThunkBody</c>), so the whole <c>dotnet test</c> host aborts before
/// any assertion is reached. That includes the cells whose Kotlin fixture member wraps the call in
/// <c>try/catch</c>: the FailFast happens inside the thunk, below the Kotlin frame, so the Kotlin
/// <c>catch</c> never runs. Because a FailFast aborts the whole suite rather than reporting one
/// failure, those fifteen cells carry <c>Skip</c> until parts B and C land; removing the attributes
/// is the first step of the PR that lands the error channel. The one live cell is the part A flow
/// cell, which passes.</para>
///
/// Three mechanisms run user C# code inline and each one gets its own cells, because a fix applied
/// to one emitter leaves the other two fail-fasting:
/// <list type="bullet">
///   <item><b>per-call lambda</b> (ADR-036/160): <c>DescribeWith</c>, <c>RecoverWith</c>,
///     <c>RecoverWithCount</c>, <c>WrapWith</c>,</item>
///   <item><b>stored callback</b> (ADR-037): <c>AddFaultListener</c> / <c>Emit</c> and the
///     <c>Int</c>-payload twin <c>AddTickListener</c> / <c>EmitTick</c>,</item>
///   <item><b>C#-implemented interface member</b> (ADR-084 bridge slots): <c>GreetVia</c>,
///     <c>CountVia</c>; plus the ADR-039 add/remove pair through <see cref="CatEventSource"/>.</item>
/// </list>
/// Each mechanism is crossed with both payload kinds, one that needs conversion at the seam
/// (<c>String</c>) and one that does not (<c>Int</c>), so a fix that only routed the boxed payload
/// cannot go green.
///
/// <para>Oreo throws the tantrum. Mylo is the one who has to survive it.</para>
/// </summary>
public class CallbackFaultTests
{
    // ---------------------------------------------------------------------------------------
    // Part B, per-call lambda (ADR-036/160)
    // ---------------------------------------------------------------------------------------

    /// <summary>
    /// The headline cell. Kotlin does not catch, so the throw has to come back out of the outer
    /// export to the C# caller as a catchable exception, and the process has to still be usable
    /// afterwards: the second <c>DescribeWith</c> is the liveness half and is not decoration.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void PerCallLambdaThrow_ReachesTheCSharpCaller_AndTheHostSurvives()
    {
        using var faults = new CallbackFaults();

        var ex = Assert.ThrowsAny<Exception>(
            () => faults.DescribeWith(_ => throw new InvalidOperationException("Oreo knocked the vase")));
        Assert.Contains("Oreo knocked the vase", ex.Message);

        Assert.Equal("Oreo!", faults.DescribeWith(name => name + "!"));
    }

    /// <summary>
    /// What-question 3, approved: on the per-call route the C# call site still owns the closure, so
    /// when the escaping Kotlin error IS the managed-exception type the ORIGINAL C# exception is
    /// rethrown (<c>ExceptionDispatchInfo</c>), not a <c>KotlinException</c> wrapper. This is the
    /// stronger form of the cell above and the one a C# consumer actually writes.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void PerCallLambdaThrow_RethrowsTheOriginalManagedException()
    {
        using var faults = new CallbackFaults();

        var ex = Assert.Throws<InvalidOperationException>(
            () => faults.DescribeWith(_ => throw new InvalidOperationException("Mylo bit the cable")));
        Assert.Equal("Mylo bit the cable", ex.Message);
    }

    /// <summary>
    /// Kotlin catches the managed exception at its own call site of the lambda and reports what it
    /// caught. <c>String</c> payload, so the argument handle is minted before the callback runs.
    /// The report has to name the managed-exception type, which is how this cell tells "Kotlin saw
    /// the C# exception" apart from "Kotlin saw some unrelated bridge failure".
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void PerCallLambdaThrow_StringPayload_IsCatchableInKotlin()
    {
        using var faults = new CallbackFaults();

        string report = faults.RecoverWith(_ => throw new InvalidOperationException("no vase left"));

        Assert.StartsWith("recovered ", report);
        Assert.Contains("NugetManagedException", report);
        Assert.Contains("no vase left", report);
    }

    /// <summary>
    /// The same, with an <c>Int</c> payload and an <c>Int</c> lambda result: both cross by value, so
    /// this cell stays red against a fix that only reached the handle-passed payload shape.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void PerCallLambdaThrow_IntPayload_IsCatchableInKotlin()
    {
        using var faults = new CallbackFaults();

        string report = faults.RecoverWithCount(_ => throw new InvalidOperationException("cannot count treats"));

        Assert.StartsWith("recovered ", report);
        Assert.Contains("NugetManagedException", report);
        Assert.Contains("cannot count treats", report);

        Assert.Equal("weighed 14", faults.RecoverWithCount(t => t * 2));
    }

    /// <summary>
    /// The discriminating cell for the rethrow-the-original optimisation: Kotlin caught the managed
    /// exception and threw its OWN <c>IllegalStateException</c> instead. The C# caller must see the
    /// Kotlin author's wrapper, so the rethrow is only allowed when the escaping Kotlin error is the
    /// managed-exception type. A fix that rethrows a stashed original unconditionally fails here.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void PerCallLambdaThrow_KotlinWrapper_IsNotReplacedByTheOriginalException()
    {
        using var faults = new CallbackFaults();

        var ex = Assert.ThrowsAny<Exception>(
            () => faults.WrapWith(_ => throw new InvalidOperationException("shelf cleared")));

        Assert.IsNotType<InvalidOperationException>(ex);
        var ke = Assert.IsAssignableFrom<IKotlinException>(ex);
        Assert.Contains("IllegalStateException", ke.KotlinType);
        Assert.Contains("Mylo knocked it over: ", ex.Message);
        Assert.Contains("shelf cleared", ex.Message);
    }

    // ---------------------------------------------------------------------------------------
    // Part B, stored callback (ADR-037)
    // ---------------------------------------------------------------------------------------

    /// <summary>
    /// A stored listener that throws surfaces where Kotlin invokes its listeners. Kotlin's guarded
    /// invocation (<c>emitSafely</c>, a <c>runCatching</c> per listener) must be able to count the
    /// failure, and the unguarded one (<c>Emit</c>) must hand it to the C# caller of <c>Emit</c>.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void StoredListenerThrow_StringPayload_SurfacesAtKotlinsInvocationSite()
    {
        using var faults = new CallbackFaults();
        using IDisposable sub = faults.AddFaultListener(
            _ => throw new ArgumentException("Oreo refuses to be notified"));

        Assert.Equal(1, faults.EmitSafely("dinner"));

        var ex = Assert.ThrowsAny<Exception>(() => faults.Emit("dinner"));
        Assert.Contains("Oreo refuses to be notified", ex.Message);
    }

    /// <summary>
    /// The <c>Int</c>-payload stored listener: same route, by-value payload, so it is a separate
    /// cell from the <c>String</c> one for the same reason the per-call pair is.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void StoredListenerThrow_IntPayload_SurfacesAtKotlinsInvocationSite()
    {
        using var faults = new CallbackFaults();
        using IDisposable sub = faults.AddTickListener(
            _ => throw new ArgumentException("Mylo lost count"));

        var ex = Assert.ThrowsAny<Exception>(() => faults.EmitTick(3));
        Assert.Contains("Mylo lost count", ex.Message);

        // Liveness: a non-throwing listener on the same object still works afterwards.
        var seen = new List<string>();
        using IDisposable ok = faults.AddFaultListener(seen.Add);
        Assert.Equal(1, faults.EmitSafely("breakfast"));
        Assert.Equal(new List<string> { "breakfast" }, seen);
    }

    // ---------------------------------------------------------------------------------------
    // Part B, C#-implemented interface member (ADR-084 bridge slots, and ADR-039's pair)
    // ---------------------------------------------------------------------------------------

    /// <summary>
    /// A C# class implementing a Kotlin interface whose member throws. The slot has to produce a
    /// <c>string</c>, so there is no default it could return: the failure must become an exception
    /// at the Kotlin call site rather than a FailFast or a null the Kotlin side dereferences.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void InterfaceMemberThrow_StringResult_ReachesTheCSharpCaller()
    {
        using var faults = new CallbackFaults();
        using var listener = new ThrowingFaultListener("Oreo will not answer to that");

        var ex = Assert.ThrowsAny<Exception>(() => faults.GreetVia(listener));
        Assert.Contains("Oreo will not answer to that", ex.Message);
    }

    /// <summary>The <c>Int</c>-returning slot on the same interface: by-value result.</summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void InterfaceMemberThrow_IntResult_ReachesTheCSharpCaller()
    {
        using var faults = new CallbackFaults();
        using var listener = new ThrowingFaultListener("Mylo cannot count that high");

        var ex = Assert.ThrowsAny<Exception>(() => faults.CountVia(listener));
        Assert.Contains("Mylo cannot count that high", ex.Message);
    }

    /// <summary>The interface-slot throw, caught on the Kotlin side and reported.</summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void InterfaceMemberThrow_IsCatchableInKotlin()
    {
        using var faults = new CallbackFaults();
        using var listener = new ThrowingFaultListener("the collar is too tight");

        string report = faults.GreetViaSafely(listener);

        Assert.StartsWith("recovered ", report);
        Assert.Contains("NugetManagedException", report);
        Assert.Contains("the collar is too tight", report);
    }

    /// <summary>
    /// The ADR-039 add/remove listener pair, whose thunks come from the same emitter but whose
    /// GCHandles are owned by the subscription. One uncaught cell covers that fourth route.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void ListenerBridgeThrow_ReachesTheCSharpCaller()
    {
        using var source = new CatEventSource("Oreo");
        using var listener = new ThrowingCatEventListener();
        using IDisposable sub = source.AddListener(listener);

        var ex = Assert.ThrowsAny<Exception>(() => source.Trigger());
        Assert.Contains("Oreo is not taking calls", ex.Message);
    }

    // ---------------------------------------------------------------------------------------
    // Part A, a bridge-internal materialisation failure on the Flow route
    // ---------------------------------------------------------------------------------------

    /// <summary>
    /// <c>Flow&lt;Mood&gt;</c>: the flow route admits an enum element, but the generated
    /// <c>onNext</c> reads it through <c>NugetMarshal.FromHandle&lt;T&gt;</c>, which has no enum
    /// branch (<c>docs/backlog/fromhandle-no-enum-branch.md</c>). The read throws inside the thunk,
    /// so the failure has to fault the <c>IAsyncEnumerable&lt;Mood&gt;</c> and cancel the Kotlin
    /// collector; today it fails fast. The materialisation bug itself stays open: this cell asserts
    /// only that the failure is a fault the consumer can catch, not that the element materialises.
    /// </summary>
    [Fact]
    public async Task FlowItemMaterialisationFailure_FaultsTheStream_AndTheHostSurvives()
    {
        using var faults = new CallbackFaults();

        var seen = new List<Mood>();
        var ex = await Assert.ThrowsAnyAsync<Exception>(async () =>
        {
            await foreach (Mood mood in faults.MoodStream())
            {
                seen.Add(mood);
            }
        });

        // The failure must be the stream's, not an assertion of this test leaking out of the loop:
        // an enum element that materialised fine would collect two moods and prove nothing.
        Assert.IsNotAssignableFrom<Xunit.Sdk.XunitException>(ex);
        Assert.Empty(seen);

        // Liveness on the same object after the stream faulted.
        Assert.Equal("Oreo?", faults.DescribeWith(name => name + "?"));
    }

    // ---------------------------------------------------------------------------------------
    // Part C, a Kotlin invocation landing after C# disposed the subscription
    // ---------------------------------------------------------------------------------------

    /// <summary>
    /// The deterministic version of the race, void shape. The Kotlin fixture keeps the listener
    /// reference handed to <c>removeFaultListener</c>, so <c>CallLastRemoved</c> invokes a delegate
    /// whose <c>GCHandle</c> the subscription already freed. What-question 4, approved: for a
    /// <c>void</c> listener the invocation is DROPPED. Today the freed slot is reused by the next
    /// allocation (memo spike (b)), so this either fails fast or silently runs a foreign delegate.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void LateInvocationAfterDispose_VoidListener_IsDropped()
    {
        using var faults = new CallbackFaults();
        var seen = new List<string>();

        IDisposable sub = faults.AddFaultListener(seen.Add);
        sub.Dispose();

        // A second subscription, so the freed handle's slot has been taken by another delegate of
        // the same type: this is the shape that silently invokes the WRONG listener today.
        var other = new List<string>();
        using IDisposable live = faults.AddFaultListener(other.Add);

        faults.CallLastRemoved("after dispose");

        Assert.Empty(seen);
        Assert.Empty(other);
        Assert.Equal(1, faults.ListenerCount());
    }

    /// <summary>
    /// The value-returning shape: a per-call lambda invoked after the member that received it
    /// returned and its <c>GCHandle</c> was freed. There is no value to invent, so Kotlin must see
    /// an <c>ObjectDisposedException</c> reported through the error channel and be able to catch it.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void LateInvocationAfterCallReturned_ValueReturning_ReportsObjectDisposed()
    {
        using var faults = new CallbackFaults();

        Assert.Equal("Oreo!", faults.StashFormatter(name => name + "!"));

        string report = faults.CallStashedSafely();
        Assert.StartsWith("stale ", report);
        Assert.Contains("ObjectDisposedException", report);
    }

    /// <summary>Uncaught, the same late call: the C# caller gets the disposal error, not a crash.</summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public void LateInvocationAfterCallReturned_Uncaught_ReachesTheCSharpCaller()
    {
        using var faults = new CallbackFaults();

        Assert.Equal("Oreo!", faults.StashFormatter(name => name + "!"));

        var ex = Assert.ThrowsAny<Exception>(() => faults.CallStashed());
        Assert.Contains("ObjectDisposedException", ex.ToString());
    }

    /// <summary>
    /// The race itself: one thread emits to the subscribed listeners while the other subscribes and
    /// disposes ten thousand times. Statistical, not deterministic (it proves the absence of a crash
    /// only for the interleavings it happened to hit), which is why the two cells above exist; it is
    /// still the only cell that exercises a genuinely concurrent free.
    /// </summary>
    [Fact(Skip = "ADR-161 parts B and C are not implemented yet: this cell fails fast the whole test host (the thunk catch-all is Environment.FailFast). Remove the Skip in the PR that lands the forward callback error channel.")]
    public async Task DisposeRacingEmit_NeverKillsTheHost()
    {
        using var faults = new CallbackFaults();
        using var stop = new CancellationTokenSource();
        long emitted = 0;

        Task emitter = Task.Run(() =>
        {
            while (!stop.IsCancellationRequested)
            {
                faults.Emit("zoomies");
                Interlocked.Increment(ref emitted);
            }
        });

        for (int i = 0; i < 10_000; i++)
        {
            faults.AddFaultListener(_ => { }).Dispose();
        }

        stop.Cancel();
        await emitter;

        Assert.True(Volatile.Read(ref emitted) > 0, "the emitter thread never ran, so nothing raced");
        Assert.Equal(0, faults.ListenerCount());
        Assert.Equal("Oreo.", faults.DescribeWith(name => name + "."));
    }

    // ---------------------------------------------------------------------------------------

    /// <summary>A C# implementation of the Kotlin interface whose every member throws.</summary>
    private sealed class ThrowingFaultListener(string message) : IFaultListener
    {
        public string OnName(string name) => throw new InvalidOperationException(message);

        public int OnCount(int count) => throw new InvalidOperationException(message);

        public void Dispose() { }
    }

    /// <summary>A throwing implementation of the ADR-039 listener interface.</summary>
    private sealed class ThrowingCatEventListener : ICatEventListener
    {
        public void OnMeow(string message) => throw new InvalidOperationException("Oreo is not taking calls");

        public void OnPurr() { }

        public void Dispose() { }
    }
}

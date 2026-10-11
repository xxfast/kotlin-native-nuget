using TestLibrary.Treatprep;

namespace IntegrationTests;

/// <summary>
/// ADR-210 slice (a): a Kotlin <c>suspend (T) -&gt; R</c> <b>parameter</b> binds as
/// <c>Func&lt;T, CancellationToken, Task&lt;R&gt;&gt;</c> (<c>Task</c> for <c>Unit</c>). This is the
/// opposite direction from <c>SuspendLambdaTests</c> (ADR-020, a suspend lambda Kotlin hands out).
/// <para>
/// One fact per host (class suspend member, sealed-arm suspend member, sealed-arm non-suspend
/// member that stores, constructor that stores, top-level suspend function), one per lambda shape
/// (String, Int and Unit results, arity 0 and 2, String payload), and one per promise the ADR makes
/// about faults and cancellation. Every lambda records what it was handed: a thunk that delivered a
/// constant, or that ran the invocations out of order, passes on arity alone otherwise.
/// </para>
/// Until the route lands every member used here is a named skip, so this file does not compile.
/// Oreo supervises the treat prep. Mylo waits by the bowl.
/// </summary>
public class SuspendLambdaParameterTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(30);

    // -----------------------------------------------------------------------------------------
    // Host 1: a class `suspend fun`. One fact per lambda shape.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// The headline cell. The lambda really suspends (<c>Task.Delay</c> on the token it was given),
    /// returns a <c>string</c>, and Kotlin invokes it three times one after the other: an invocation
    /// that started before the previous one's <c>Task</c> completed shows up as overlap.
    /// </summary>
    [Fact]
    public async Task Chef_Plate_SuspendingLambda_IsInvokedSequentiallyInOrder()
    {
        using var chef = new TreatChef("Oreo");
        var seen = new List<int>();
        int inFlight = 0;
        int maxInFlight = 0;

        string plated = await chef.PlateAsync(3, async (treat, token) =>
        {
            lock (seen)
            {
                maxInFlight = Math.Max(maxInFlight, ++inFlight);
                seen.Add(treat);
            }
            await Task.Delay(10, token);
            lock (seen) inFlight--;
            return $"tuna#{treat}";
        });

        Assert.Equal("tuna#1,tuna#2,tuna#3", plated);
        Assert.Equal(new List<int> { 1, 2, 3 }, seen);
        Assert.Equal(1, maxInFlight);
    }

    /// <summary>
    /// <c>Int</c> result by value, and a lambda that completes synchronously
    /// (<c>Task.FromResult</c>): the completion can land before the Begin thunk has returned.
    /// </summary>
    [Fact]
    public async Task Chef_Weigh_AlreadyCompletedLambda_ReturnsIntByValue()
    {
        using var chef = new TreatChef("Mylo");
        var seen = new List<int>();

        int grams = await chef.WeighAsync(4, (treat, _) =>
        {
            seen.Add(treat);
            return Task.FromResult(treat * 10);
        });

        Assert.Equal(100, grams);
        Assert.Equal(new List<int> { 1, 2, 3, 4 }, seen);
    }

    /// <summary><c>Unit</c> result: the delegate returns a plain <c>Task</c>.</summary>
    [Fact]
    public async Task Chef_Serve_UnitLambda_BindsAsTaskReturningFunc()
    {
        using var chef = new TreatChef("Mylo");
        var bowl = new List<int>();

        int served = await chef.ServeAsync(3, async (treat, token) =>
        {
            await Task.Delay(1, token);
            lock (bowl) bowl.Add(treat);
        });

        Assert.Equal(3, served);
        Assert.Equal(new List<int> { 1, 2, 3 }, bowl);
    }

    /// <summary>Arity 0: the delegate takes only the token.</summary>
    [Fact]
    public async Task Chef_Announce_Arity0Lambda_ReceivesOnlyTheToken()
    {
        using var chef = new TreatChef("Oreo");
        int calls = 0;

        string heard = await chef.AnnounceAsync(async token =>
        {
            calls++;
            await Task.Delay(1, token);
            return "dinner!";
        });

        Assert.Equal("Oreo hears: dinner!", heard);
        Assert.Equal(1, calls);
    }

    /// <summary>
    /// Arity 2: both payloads arrive, and in declaration order. Subtraction, so swapped arguments
    /// give a different answer.
    /// </summary>
    [Fact]
    public async Task Chef_Mix_Arity2Lambda_ReceivesBothPayloadsInOrder()
    {
        using var chef = new TreatChef("Oreo");

        int blended = await chef.MixAsync(9, 2, (wet, dry, _) => Task.FromResult(wet * 10 - dry));

        Assert.Equal(88, blended);
    }

    /// <summary><c>String</c> payload and result: conversion at the seam in both directions.</summary>
    [Fact]
    public async Task Chef_Label_StringPayloadLambda_RoundTripsTheString()
    {
        using var chef = new TreatChef("Oreo");
        string? handed = null;

        string label = await chef.LabelAsync(async (name, token) =>
        {
            handed = name;
            await Task.Delay(1, token);
            return $"{name}'s salmon bites";
        });

        Assert.Equal("Oreo", handed);
        Assert.Equal("Oreo's salmon bites", label);
    }

    // -----------------------------------------------------------------------------------------
    // Hosts 2 to 5.
    // -----------------------------------------------------------------------------------------

    /// <summary>A <c>suspend fun</c> on a sealed arm (ADR-118's arm suspend route).</summary>
    [Fact]
    public async Task SealedArm_Cook_SuspendMember_InvokesTheLambda()
    {
        using var batch = new Prep.Batch(size: 3);
        var seen = new List<int>();

        string cooked = await batch.CookAsync(async (treat, token) =>
        {
            lock (seen) seen.Add(treat);
            await Task.Delay(1, token);
            return $"bite{treat}";
        });

        Assert.Equal("bite1+bite2+bite3", cooked);
        Assert.Equal(new List<int> { 1, 2, 3 }, seen);
    }

    /// <summary>
    /// A <b>non-suspend</b> member on a sealed arm can only store the lambda, so nothing may run
    /// during <c>Keep</c>, and the stored lambda must still be invocable after <c>Keep</c> has
    /// returned: the delegate's lifetime is Kotlin's, not the call's.
    /// </summary>
    [Fact]
    public async Task SealedArm_Keep_StoredLambda_IsInvokedAfterTheStoringCallReturned()
    {
        using var batch = new Prep.Batch(size: 2);
        int calls = 0;

        Assert.Equal("nothing kept", await batch.CookKeptAsync());

        int size = batch.Keep(async (treat, token) =>
        {
            Interlocked.Increment(ref calls);
            await Task.Delay(1, token);
            return $"kept{treat}";
        });

        Assert.Equal(2, size);
        Assert.Equal(0, calls);

        Assert.Equal("kept1+kept2", await batch.CookKeptAsync());
        Assert.Equal(2, calls);

        // A stored lambda is not single-use.
        Assert.Equal("kept1+kept2", await batch.CookKeptAsync());
        Assert.Equal(4, calls);
    }

    /// <summary>
    /// A <b>constructor</b> stores the lambda and a later suspend member invokes it, across .NET
    /// collections in between: nothing on the C# side roots the delegate once the constructor has
    /// returned except the bridge.
    /// </summary>
    [Fact]
    public async Task Constructor_Timer_StoredLambda_IsInvokedAfterTheConstructorReturned()
    {
        int calls = 0;
        using var timer = new TreatTimer(async (treat, token) =>
        {
            Interlocked.Increment(ref calls);
            await Task.Delay(1, token);
            return $"ding{treat}";
        });

        Assert.Equal(0, calls);
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();

        Assert.Equal("ding1,ding2", await timer.RingAsync(2));
        Assert.Equal(2, calls);
        Assert.Equal("ding1,ding2,ding3", await timer.RingAsync(3));
        Assert.Equal(5, calls);
    }

    /// <summary>The top-level suspend route, on the ADR-007 static class.</summary>
    [Fact]
    public async Task TopLevel_PrepOnce_InvokesTheLambda()
    {
        int handed = 0;

        string prepped = await TreatPrepSample.PrepOnceAsync(7, async (seed, token) =>
        {
            handed = seed;
            await Task.Delay(1, token);
            return $"kibble{seed}";
        });

        Assert.Equal(7, handed);
        Assert.Equal("once:kibble7", prepped);
    }

    // -----------------------------------------------------------------------------------------
    // Faults. A C# throw reaches the Kotlin invocation site as an exception.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// A <b>synchronous</b> throw: the lambda is not <c>async</c>, so the exception leaves the
    /// delegate before any <c>Task</c> exists. Kotlin catches it and reports what it saw.
    /// </summary>
    [Fact]
    public async Task SynchronousThrow_BeforeAnyTask_IsCatchableInKotlin()
    {
        using var chef = new TreatChef("Oreo");

        string report = await chef.RecoverAsync(
            (_, _) => throw new InvalidOperationException("the tuna tin is empty"));

        Assert.StartsWith("recovered ", report);
        Assert.Contains("NugetManagedException", report);
        Assert.Contains("the tuna tin is empty", report);
    }

    /// <summary>A <b>faulted</b> <c>Task</c>, already completed when it is handed back.</summary>
    [Fact]
    public async Task FaultedTask_IsCatchableInKotlin()
    {
        using var chef = new TreatChef("Oreo");

        string report = await chef.RecoverAsync((_, _) =>
            Task.FromException<string>(new InvalidOperationException("Mylo ate the garnish")));

        Assert.StartsWith("recovered ", report);
        Assert.Contains("NugetManagedException", report);
        Assert.Contains("Mylo ate the garnish", report);
    }

    /// <summary>A <c>Task</c> that faults only after it really suspended.</summary>
    [Fact]
    public async Task TaskFaultingAfterASuspension_IsCatchableInKotlin()
    {
        using var chef = new TreatChef("Oreo");

        string report = await chef.RecoverAsync(async (_, token) =>
        {
            await Task.Delay(10, token);
            throw new InvalidOperationException("the oven went cold");
        });

        Assert.StartsWith("recovered ", report);
        Assert.Contains("the oven went cold", report);
    }

    /// <summary>The control for the three above: nothing thrown, nothing recovered.</summary>
    [Fact]
    public async Task Recover_WithAHealthyLambda_ReturnsItsResult()
    {
        using var chef = new TreatChef("Oreo");

        Assert.Equal("prepared pate", await chef.RecoverAsync((_, _) => Task.FromResult("pate")));
    }

    /// <summary>
    /// Kotlin does <b>not</b> catch a synchronous throw: the outer <c>Task</c> faults, carrying the
    /// lambda's message, and the invocations after the throwing one never run. Which exception
    /// object arrives is ADR-210's inferred claim 6, so the type is not pinned here.
    /// </summary>
    [Fact]
    public async Task SynchronousThrow_Uncaught_FaultsTheOuterTask()
    {
        using var chef = new TreatChef("Oreo");
        var seen = new List<int>();

        Task<string> plating = chef.PlateAsync(3, (treat, _) =>
        {
            seen.Add(treat);
            if (treat == 2) throw new InvalidOperationException("Oreo swatted plate two off the bench");
            return Task.FromResult($"plate{treat}");
        });

        Exception ex = await Assert.ThrowsAnyAsync<Exception>(() => plating);
        Assert.IsNotAssignableFrom<Xunit.Sdk.XunitException>(ex);
        Assert.IsNotAssignableFrom<OperationCanceledException>(ex);
        Assert.True(plating.IsFaulted);
        Assert.Contains("Oreo swatted plate two off the bench", ex.ToString());
        Assert.Equal(new List<int> { 1, 2 }, seen);
    }

    /// <summary>The same through a faulted <c>Task</c>.</summary>
    [Fact]
    public async Task FaultedTask_Uncaught_FaultsTheOuterTask()
    {
        using var chef = new TreatChef("Oreo");
        var seen = new List<int>();

        Task<string> plating = chef.PlateAsync(3, async (treat, token) =>
        {
            lock (seen) seen.Add(treat);
            await Task.Delay(1, token);
            if (treat == 2) throw new InvalidOperationException("Mylo sat on plate two");
            return $"plate{treat}";
        });

        Exception ex = await Assert.ThrowsAnyAsync<Exception>(() => plating);
        Assert.IsNotAssignableFrom<Xunit.Sdk.XunitException>(ex);
        Assert.IsNotAssignableFrom<OperationCanceledException>(ex);
        Assert.True(plating.IsFaulted);
        Assert.Contains("Mylo sat on plate two", ex.ToString());
        Assert.Equal(new List<int> { 1, 2 }, seen);
    }

    /// <summary>The wrapper survives a faulted call: the next call on it works.</summary>
    [Fact]
    public async Task AfterAFaultedCall_TheSameWrapperStillWorks()
    {
        using var chef = new TreatChef("Oreo");

        await Assert.ThrowsAnyAsync<Exception>(() => chef.PlateAsync(
            1, (_, _) => throw new InvalidOperationException("dropped it")));

        Assert.Equal("ok1,ok2", await chef.PlateAsync(2, (treat, _) => Task.FromResult($"ok{treat}")));
    }

    // -----------------------------------------------------------------------------------------
    // Cancellation, in each direction.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// The lambda throws <c>OperationCanceledException</c>, so its <c>Task</c> is cancelled rather
    /// than faulted. That cancels the invoking coroutine: the outer <c>Task</c> ends cancelled, not
    /// faulted, and Kotlin never reaches the next invocation.
    /// </summary>
    [Fact]
    public async Task LambdaThrowsOperationCanceled_CancelsTheOuterTask()
    {
        using var chef = new TreatChef("Mylo");
        var seen = new List<int>();

        Task<string> plating = chef.PlateAsync(3, async (treat, token) =>
        {
            lock (seen) seen.Add(treat);
            await Task.Delay(1, token);
            throw new OperationCanceledException("Mylo walked off mid-prep");
        });

        // Whether the outer Task is Canceled or Faulted-with-a-cancellation is the suspend route's
        // own choice (ADR-161); what a consumer relies on is `catch (OperationCanceledException)`.
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => plating);
        Assert.Equal(new List<int> { 1 }, seen);
    }

    /// <summary>
    /// The same through a host whose catch-all would have reported a fault: a cancelled C#
    /// <c>Task</c> arrives in Kotlin as a cancellation, which the fixture rethrows, so the answer is
    /// a cancelled outer <c>Task</c> and never a <c>"recovered ..."</c> string.
    /// </summary>
    [Fact]
    public async Task CancelledLambdaTask_IsACancellationInKotlin_NotAFault()
    {
        using var chef = new TreatChef("Mylo");

        Task<string> recovering = chef.RecoverAsync((_, _) =>
            Task.FromCanceled<string>(new CancellationToken(canceled: true)));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => recovering);
    }

    /// <summary>
    /// Kotlin cancels the invocation (<c>withTimeoutOrNull</c>) and carries on: the token the
    /// lambda received is cancelled, and the outer call still completes normally. The lambda waits
    /// on nothing but its token, so the only way it ends is the bridge cancelling that token; the
    /// observation is awaited rather than read, because Kotlin returns on its own timeout, not when
    /// C# noticed.
    /// </summary>
    [Fact]
    public async Task KotlinTimeout_CancelsTheTokenTheLambdaReceived()
    {
        using var chef = new TreatChef("Oreo");
        var tokenCancelled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

        string verdict = await chef.ImpatientAsync(50, async (_, token) =>
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
            return "never";
        });

        Assert.Equal("Oreo gave up waiting", verdict);
        Assert.True(await tokenCancelled.Task.WaitAsync(Patience));
    }

    /// <summary>The control: a lambda that beats the timeout is simply returned.</summary>
    [Fact]
    public async Task KotlinTimeout_NotReached_ReturnsTheLambdaResult()
    {
        using var chef = new TreatChef("Oreo");

        string verdict = await chef.ImpatientAsync(30_000, async (treat, token) =>
        {
            await Task.Delay(1, token);
            return $"treat{treat} in time";
        });

        Assert.Equal("treat1 in time", verdict);
    }

    /// <summary>
    /// Cancelling the <b>outer</b> token while the lambda is pending cancels the token the lambda
    /// received, and the outer <c>Task</c> ends cancelled. The lambda's token starts out live, so
    /// this is not a pre-cancelled token passing by luck.
    /// </summary>
    [Fact]
    public async Task OuterTokenCancelled_CancelsTheTokenTheLambdaReceived()
    {
        using var chef = new TreatChef("Oreo");
        using var cts = new CancellationTokenSource();
        var entered = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var tokenCancelled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

        Task<string> plating = chef.PlateAsync(3, async (_, token) =>
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
            return "never";
        }, cts.Token);

        Assert.False(await entered.Task.WaitAsync(Patience), "the lambda's token was already cancelled on entry");
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => plating);
        Assert.True(await tokenCancelled.Task.WaitAsync(Patience));
    }

    /// <summary>
    /// The stored lambda sees cancellation too: the token it receives belongs to the call that
    /// invokes it (<c>RingAsync</c>), not to the constructor that stored it.
    /// </summary>
    [Fact]
    public async Task Constructor_Timer_OuterTokenOfTheInvokingCall_ReachesTheStoredLambda()
    {
        using var cts = new CancellationTokenSource();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var tokenCancelled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

        using var timer = new TreatTimer(async (_, token) =>
        {
            entered.TrySetResult();
            try
            {
                await Task.Delay(Timeout.Infinite, token);
            }
            catch (OperationCanceledException)
            {
                tokenCancelled.TrySetResult(token.IsCancellationRequested);
                throw;
            }
            return "never";
        });

        Task<string> ringing = timer.RingAsync(1, cts.Token);
        await entered.Task.WaitAsync(Patience);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => ringing);
        Assert.True(await tokenCancelled.Task.WaitAsync(Patience));
    }
}

using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Catnap;
using Purrclock = TestLibrary.Purrclock;
using TestLibrary.Roster;
using Playroom = TestLibrary.Playroom;
using Stamps = TestLibrary.Stamps;
using Treatprep = TestLibrary.Treatprep;
using Trickle = TestLibrary.Trickle;

namespace LeakTests;

/// <summary>
/// ADR-121: ADR-120's counter proves the <c>StableRef</c> goes; this proves the *object* goes.
/// Kotlin's <see cref="Morgue"/> holds one weak reference, C# disposes the last wrapper and drives
/// Kotlin's GC through <c>NugetMarshal.GcCollect()</c> until the weak reference reads dead.
///
/// Oreo checks in, and the point of the exercise is that Oreo checks out.
/// </summary>
public class CollectabilityTests
{
    private sealed class QuietListener : ICatEventListener
    {
        public void OnMeow(string message) { }
        public void OnPurr() { }
        public void Dispose() { }
    }

    // Poll, not a fixed round count: BidirectionalTests.ReleaseFiredWithin is the precedent.
    private static bool CollectedWithin(TimeSpan budget)
    {
        DateTime deadline = DateTime.UtcNow + budget;
        while (DateTime.UtcNow < deadline)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            NugetMarshal.GcCollect();
            if (!Morgue.IsAlive()) return true;
            Thread.Sleep(25);
        }
        return false;
    }

    // The wrapper lives in its own frame, the same reason BidirectionalTests.AssignFriendAndDrop
    // exists: a live stack slot in the asserting frame roots the object even after Dispose.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void CreateWatchAndDispose()
    {
        using var oreo = new Cat("Oreo", 9);
        Morgue.WatchCat(oreo);
    }

    // Shape 1: a plain class, no seam. Red only if the bridge itself retains.
    [Fact]
    public void PlainClass_LastDispose_IsCollected()
    {
        try
        {
            CreateWatchAndDispose();
            Assert.True(
                CollectedWithin(TimeSpan.FromSeconds(5)),
                "Cat stayed reachable after its last Dispose");
        }
        finally
        {
            Morgue.Forget();
        }
    }

    // Shape 2: the stored-callback seam. The unsubscribe closure captures the source; once the
    // subscription token and the wrapper are both disposed nothing may reach it.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void SubscribeUnsubscribeAndDispose()
    {
        using var source = new CatEventSource("Oreo");
        Morgue.WatchSource(source);
        using IDisposable sub = source.AddListener(new QuietListener());
        source.Trigger();
    }

    [Fact]
    public void StoredCallbackReceiver_AfterUnsubscribeAndDispose_IsCollected()
    {
        try
        {
            SubscribeUnsubscribeAndDispose();
            Assert.True(
                CollectedWithin(TimeSpan.FromSeconds(5)),
                "CatEventSource stayed reachable after unsubscribe + Dispose");
        }
        finally
        {
            Morgue.Forget();
        }
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static IDisposable SubscribeAndDisposeTheSourceOnly()
    {
        using var source = new CatEventSource("Mylo");
        Morgue.WatchSource(source);
        return source.AddListener(new QuietListener());
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static CatEventSource SubscribeAndDiscardTheToken()
    {
        var source = new CatEventSource("Oreo");
        Morgue.WatchSource(source);
        Assert.NotNull(source.AddListener(new QuietListener()));
        return source;
    }

    // ------------------------------------------------------------------------------------------
    // ADR-155: the same shape, mirrored for a REVERSE collection's handle ELEMENTS. Each element
    // slot of a `IList<Tag>` return is a fresh strong GCHandle owned by the Kotlin wrapper built
    // from it, and reverse handles are uncounted by NugetMarshal.LiveHandles (so ADR-120's counter
    // cannot see a leak here). A leaked slot handle is a strong root, so the C# fixture's own
    // weak-reference alive-count is the proof: it reaches zero only if every wrapper's close()
    // freed its handle.
    //
    // This row lives in LeakTests and not in IntegrationTests for the reason the whole project
    // exists: Roster's issued-tag list is process-global fixture state, and any other test that
    // calls Tags() moves it.
    // ------------------------------------------------------------------------------------------

    // Enough tags that a per-slot leak cannot hide behind one unlucky collection, few enough that
    // the poll below stays cheap. Each round issues one tag through one collection crossing.
    private const int TagRounds = 50;

    // "chip".Length per issued tag: a run where the collection came back empty must not read as a
    // clean one.
    private const int LabelLength = 4;

    // Deliberately NOT NugetMarshal.GcCollect(): the Kotlin wrappers are closed explicitly by
    // RosterSample.IssueTags, so the element handles must be gone without waiting on Kotlin's GC
    // to run a Cleaner. Only the managed side needs collecting, and Roster.AliveTags() does that
    // itself before counting.
    private static bool TagsCollectedWithin(TimeSpan budget)
    {
        DateTime deadline = DateTime.UtcNow + budget;
        while (DateTime.UtcNow < deadline)
        {
            if (RosterSample.AliveTags() == 0) return true;
            Thread.Sleep(25);
        }
        return false;
    }

    // Its own frame, for the reason CreateWatchAndDispose has one: a live stack slot in the
    // asserting frame roots what the assertion is about.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void IssueAndCloseTags() =>
        Assert.Equal(LabelLength * TagRounds, RosterSample.IssueTags(TagRounds));

    [Fact]
    public void CollectionHandleElements_AfterTheKotlinWrappersClose_AreCollected()
    {
        IssueAndCloseTags();
        Assert.True(
            TagsCollectedWithin(TimeSpan.FromSeconds(5)),
            "a Tag issued as a collection element stayed reachable after its Kotlin wrapper closed");
    }

    // Negative control: a DISCARDED subscription on a live owner keeps delivering (ADR-187 gate),
    // so its token, which captures the Kotlin source, must keep the source alive. Disposing the
    // owner then has to unregister that token too, or the source stays rooted by a subscription
    // nobody can reach. Proves the assertions here can go red in both directions.
    [Fact]
    public void StoredCallbackReceiver_DiscardedToken_IsReleasedWithItsOwner()
    {
        CatEventSource source = SubscribeAndDiscardTheToken();
        try
        {
            Assert.False(
                CollectedWithin(TimeSpan.FromSeconds(1)),
                "a live owner's discarded subscription must keep the source alive");

            source.Dispose();
            Assert.True(
                CollectedWithin(TimeSpan.FromSeconds(5)),
                "CatEventSource stayed reachable after its owner's Dispose: the discarded token was not unregistered");
        }
        finally
        {
            source.Dispose();
            Morgue.Forget();
        }
    }

    // A token the consumer still HOLDS past its owner's Dispose no longer roots the source: the
    // owner's release unregistered it, and the consumer's own later Dispose is a silent no-op.
    [Fact]
    public void StoredCallbackReceiver_TokenHeldPastItsOwner_DoesNotRootTheSource()
    {
        IDisposable sub = SubscribeAndDisposeTheSourceOnly();
        try
        {
            Assert.True(
                CollectedWithin(TimeSpan.FromSeconds(5)),
                "CatEventSource stayed reachable after its owner's Dispose while the consumer held a token");
            sub.Dispose();
        }
        finally
        {
            sub.Dispose();
            Morgue.Forget();
        }
    }

    // ------------------------------------------------------------------------------------------
    // ADR-210 slice (a): a suspend lambda PARAMETER. The C# delegate is parked in the generated
    // context table (`NugetThunks._ctxTable`) under a key a Kotlin cleaner releases, and that table
    // has no observable: it is private, and NugetMarshal.LiveHandles counts Kotlin handles only. So
    // a key the cleaner never released is invisible to LiveHandleTests rows 17a to 17f, and the
    // proof lives here instead: the lambda captures a marker, and once the call is over, the
    // wrapper disposed and every reference dropped, a `WeakReference` to the marker must go dead.
    // A table entry that outlives its Kotlin holder is a strong root on the closure, and so on
    // the marker.
    // ------------------------------------------------------------------------------------------

    private sealed class TreatMarker
    {
        public int Touches;
    }

    // Both collectors every round, in this order: the Kotlin round runs the cleaner that removes
    // the table entry, and only the .NET round after it can then collect the closure.
    private static bool MarkerCollectedWithin(WeakReference marker, TimeSpan budget)
    {
        DateTime deadline = DateTime.UtcNow + budget;
        while (DateTime.UtcNow < deadline)
        {
            NugetMarshal.GcCollect();
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            if (!marker.IsAlive) return true;
            Thread.Sleep(25);
        }
        return false;
    }

    // The marker and the closure over it exist in this frame only, for the reason
    // CreateWatchAndDispose has its own: a live stack slot in the asserting frame is a root. Not
    // `async`, so no state machine hoists the marker either.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<string> Plating, WeakReference Marker) StartPlating(Treatprep.TreatChef chef)
    {
        var marker = new TreatMarker();
        Task<string> plating = chef.PlateAsync(2, async (treat, token) =>
        {
            await Task.Delay(1, token);
            return $"tuna#{treat}:{Interlocked.Increment(ref marker.Touches)}";
        });
        return (plating, new WeakReference(marker));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<WeakReference> PlateAndDispose()
    {
        using var chef = new Treatprep.TreatChef("Oreo");
        (Task<string> plating, WeakReference marker) = StartPlating(chef);

        // The touch counts prove the closure that ran is the one holding the marker.
        Assert.Equal("tuna#1:1,tuna#2:2", await plating);
        return marker;
    }

    // The suspend-fun host: the lambda is used during the call and never stored, so nothing on
    // the Kotlin side reaches its holder once the coroutine has finished.
    [Fact]
    public async Task SuspendLambdaParameter_AfterTheCallCompletes_ItsClosureIsCollected()
    {
        WeakReference marker = await PlateAndDispose();

        Assert.True(
            MarkerCollectedWithin(marker, TimeSpan.FromSeconds(5)),
            "the lambda passed to PlateAsync stayed reachable after the call completed and its owner was disposed: its C# context key was never released");
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Treatprep.TreatTimer Timer, WeakReference Marker) BuildTimer()
    {
        var marker = new TreatMarker();
        var timer = new Treatprep.TreatTimer(async (_, token) =>
        {
            await Task.Delay(1, token);
            return $"ding{Interlocked.Increment(ref marker.Touches)}";
        });
        return (timer, new WeakReference(marker));
    }

    // The constructor (stored) host, with its negative control in the same row: while the owner
    // lives, the stored lambda must survive both collectors and still be invocable, which is the
    // whole point of the Kotlin-owned lifetime; once the owner is disposed and dropped it must go.
    // Proves the assertion can go red in both directions.
    [Fact]
    public async Task SuspendLambdaParameter_StoredByAConstructor_IsReleasedWithItsOwner()
    {
        (Treatprep.TreatTimer? timer, WeakReference marker) = BuildTimer();
        try
        {
            Assert.False(
                MarkerCollectedWithin(marker, TimeSpan.FromSeconds(1)),
                "a live TreatTimer must keep the lambda its constructor stored");
            Assert.Equal("ding1,ding2", await timer.RingAsync(2));

            timer.Dispose();
            timer = null;

            Assert.True(
                MarkerCollectedWithin(marker, TimeSpan.FromSeconds(5)),
                "the lambda stored by TreatTimer's constructor stayed reachable after its owner's Dispose: its C# context key was never released");
        }
        finally
        {
            timer?.Dispose();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The call that FAILS BEFORE KOTLIN TAKES OWNERSHIP. The lambda's context key is only ever
    // released by a Kotlin cleaner, so a wrapper that registers the key and then throws before the
    // P/Invoke has handed it over leaves an entry nobody will ever release: a permanent root on the
    // delegate and everything it captured. Two ways for a call to fail that early, one row each.
    // Both exceptions are thrown synchronously, as on every neighbouring suspend member (the
    // `...Async` wrapper is not an `async` method), so no Task is ever returned.
    // ------------------------------------------------------------------------------------------

    // Way 1: the wrapper is already disposed.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference PlateOnADisposedChef()
    {
        var marker = new TreatMarker();
        var chef = new Treatprep.TreatChef("Oreo");
        chef.Dispose();

        Assert.Throws<ObjectDisposedException>(() =>
        {
            _ = chef.PlateAsync(2, async (treat, token) =>
            {
                await Task.Delay(1, token);
                return $"tuna#{treat}:{Interlocked.Increment(ref marker.Touches)}";
            });
        });

        Assert.Equal(0, marker.Touches);
        return new WeakReference(marker);
    }

    [Fact]
    public void SuspendLambdaParameter_CallOnADisposedWrapper_ItsClosureIsCollected()
    {
        WeakReference marker = PlateOnADisposedChef();

        Assert.True(
            MarkerCollectedWithin(marker, TimeSpan.FromSeconds(5)),
            "the lambda passed to PlateAsync on a disposed TreatChef stayed reachable after the call threw ObjectDisposedException: its C# context key was registered before the throw and never released");
    }

    // Way 2: an argument AFTER the lambda fails its null guard. `GarnishAsync(prepare, topping)`
    // exists for this row: arguments are evaluated left to right, so by the time the non-null
    // `String` is refused the lambda's argument has already been prepared.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference GarnishWithANullTopping()
    {
        var marker = new TreatMarker();
        using var chef = new Treatprep.TreatChef("Mylo");

        ArgumentNullException refused = Assert.Throws<ArgumentNullException>(() =>
        {
            _ = chef.GarnishAsync(
                async (treat, token) =>
                {
                    await Task.Delay(1, token);
                    return $"pate#{treat}:{Interlocked.Increment(ref marker.Touches)}";
                },
                null!);
        });

        Assert.Equal("topping", refused.ParamName);
        Assert.Equal(0, marker.Touches);
        return new WeakReference(marker);
    }

    [Fact]
    public void SuspendLambdaParameter_LaterArgumentFailsItsNullGuard_ItsClosureIsCollected()
    {
        WeakReference marker = GarnishWithANullTopping();

        Assert.True(
            MarkerCollectedWithin(marker, TimeSpan.FromSeconds(5)),
            "the lambda passed to GarnishAsync stayed reachable after a later argument threw ArgumentNullException: its C# context key was registered before the throw and never released");
    }

    // The control for way 2: the same member with a real topping works, and its closure goes.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<string> Garnishing, WeakReference Marker) StartGarnishing(Treatprep.TreatChef chef)
    {
        var marker = new TreatMarker();
        Task<string> garnishing = chef.GarnishAsync(
            async (treat, token) =>
            {
                await Task.Delay(1, token);
                return $"pate#{treat}:{Interlocked.Increment(ref marker.Touches)}";
            },
            "catnip");
        return (garnishing, new WeakReference(marker));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<WeakReference> GarnishAndDispose()
    {
        using var chef = new Treatprep.TreatChef("Mylo");
        (Task<string> garnishing, WeakReference marker) = StartGarnishing(chef);

        Assert.Equal("pate#1:1 with catnip", await garnishing);
        return marker;
    }

    [Fact]
    public async Task SuspendLambdaParameter_LambdaBeforeAString_AfterTheCallCompletes_ItsClosureIsCollected()
    {
        WeakReference marker = await GarnishAndDispose();

        Assert.True(
            MarkerCollectedWithin(marker, TimeSpan.FromSeconds(5)),
            "the lambda passed to GarnishAsync stayed reachable after the call completed and its owner was disposed");
    }

    // ------------------------------------------------------------------------------------------
    // ADR-210 slice (b): a `Flow<T>` PARAMETER. The same blind spot as the suspend lambda rows
    // above, one type over: the C# `IAsyncEnumerable<T>` sits in the generated context table under
    // a key a Kotlin cleaner releases, and nothing counts that table. The source captures a marker;
    // once the collection is over, the wrapper disposed and every reference dropped, the marker
    // must be collected. It also catches an enumeration handle that was never freed: the
    // enumerator is the iterator's state machine, which holds the source and so the marker.
    // ------------------------------------------------------------------------------------------

    private sealed class MarkedDrops(TreatMarker marker, int count) : IAsyncEnumerable<int>
    {
        public async IAsyncEnumerator<int> GetAsyncEnumerator(CancellationToken cancellationToken = default)
        {
            for (int drop = 1; drop <= count; drop++)
            {
                await Task.Delay(1, cancellationToken);
                Interlocked.Increment(ref marker.Touches);
                yield return drop;
            }
        }
    }

    // Not `async`, and its own frame: the marker and the source over it exist here only.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<int> Counting, WeakReference Marker) StartCounting(Trickle.WaterFountain fountain)
    {
        var marker = new TreatMarker();
        Task<int> counting = fountain.TotalDropsAsync(new MarkedDrops(marker, 3));
        return (counting, new WeakReference(marker));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<WeakReference> CountAndDispose()
    {
        using var fountain = new Trickle.WaterFountain("Oreo");
        (Task<int> counting, WeakReference marker) = StartCounting(fountain);

        Assert.Equal(6, await counting);
        return marker;
    }

    // The suspend-fun host: the flow is collected during the call and never stored, so nothing on
    // the Kotlin side reaches its holder once the coroutine has finished.
    [Fact]
    public async Task FlowParameter_AfterTheCallCompletes_ItsSourceIsCollected()
    {
        WeakReference marker = await CountAndDispose();

        Assert.True(
            MarkerCollectedWithin(marker, TimeSpan.FromSeconds(5)),
            "the IAsyncEnumerable passed to TotalDropsAsync stayed reachable after the call completed and its owner was disposed: its C# context key, or an enumeration handle, was never released");
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Trickle.Dripper Dripper, WeakReference Marker) BuildDripper()
    {
        var marker = new TreatMarker();
        var dripper = new Trickle.Dripper(new MarkedDrops(marker, 2));
        return (dripper, new WeakReference(marker));
    }

    // The constructor (stored) host, with its negative control in the same row: a live owner must
    // keep the stored source through both collectors and still be able to enumerate it; a disposed
    // and dropped owner must let it go.
    [Fact]
    public async Task FlowParameter_StoredByAConstructor_IsReleasedWithItsOwner()
    {
        (Trickle.Dripper? dripper, WeakReference marker) = BuildDripper();
        try
        {
            Assert.False(
                MarkerCollectedWithin(marker, TimeSpan.FromSeconds(1)),
                "a live Dripper must keep the flow its constructor stored");
            Assert.Equal("1,2", await dripper.DrainAsync());

            dripper.Dispose();
            dripper = null;

            Assert.True(
                MarkerCollectedWithin(marker, TimeSpan.FromSeconds(5)),
                "the IAsyncEnumerable stored by Dripper's constructor stayed reachable after its owner's Dispose: its C# context key, or an enumeration handle, was never released");
        }
        finally
        {
            dripper?.Dispose();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The COMPLETION CALLBACK of a suspend member that takes NO suspend lambda. Every `...Async`
    // wrapper allocates a `GCHandle` on its completion closure before the P/Invoke, and only the
    // completion callback frees it. A wrapper that throws between the allocation and the P/Invoke
    // (a non-null `String` refused by its null guard, which is evaluated inline in the native
    // call's argument list) never hands the handle to Kotlin, so no completion will ever arrive to
    // free it: a permanent strong root on the closure. ADR-210 added the catch that frees it, but
    // only to members that take a suspend lambda.
    //
    // The handle is a C# `GCHandle`, invisible to NugetMarshal.LiveHandles, so the proof is
    // collectability again. There is no consumer lambda to capture a marker here, so the marker is
    // what the completion closure itself captures: the `CancellationToken` the caller passed (the
    // closure needs it for `TrySetCanceled`), which holds its `CancellationTokenSource`, and on an
    // instance member the wrapper (`GC.KeepAlive(this)`).
    //
    // Mylo runs the stamp desk. The leash is how Oreo gets called back before the stamp dries.
    // ------------------------------------------------------------------------------------------

    // The controls first: a call that reaches Kotlin frees the handle in its completion callback,
    // so the leash goes. If these do not collect, the marker is wrong, not the bridge. The leash is
    // deliberately not disposed: its token has to stay usable until the call completes, and whether
    // the source can be collected is the row's whole subject.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<string> Stamping, WeakReference Leash) StartStamping(Stamps.StampDesk desk)
    {
        var leash = new CancellationTokenSource();
        Task<string> stamping = desk.StampLaterAsync("Oreo's parcel", leash.Token);
        return (stamping, new WeakReference(leash));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<WeakReference> StampAndDispose()
    {
        using var desk = new Stamps.StampDesk("Mylo");
        (Task<string> stamping, WeakReference leash) = StartStamping(desk);

        Assert.Equal("Mylo stamped Oreo's parcel later", await stamping);
        return leash;
    }

    [Fact]
    public async Task SuspendCompletion_MemberCallCompletes_ItsStateIsCollected()
    {
        WeakReference leash = await StampAndDispose();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to StampLaterAsync stayed reachable after the call completed and its owner was disposed");
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<string> Greeting, WeakReference Leash) StartGreeting()
    {
        var leash = new CancellationTokenSource();
        Task<string> greeting = AsyncFunctions.FetchGreetingAsync("Oreo", leash.Token);
        return (greeting, new WeakReference(leash));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<WeakReference> GreetAndDrop()
    {
        (Task<string> greeting, WeakReference leash) = StartGreeting();

        Assert.Equal("Hello, Oreo!", await greeting);
        return leash;
    }

    [Fact]
    public async Task SuspendCompletion_TopLevelCallCompletes_ItsStateIsCollected()
    {
        WeakReference leash = await GreetAndDrop();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to FetchGreetingAsync stayed reachable after the call completed");
    }

    // The instance route: `StampDesk.stampLater(text: String)`. Both things the completion closure
    // captures are watched, because either one staying alive is the same stranded handle.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (WeakReference Leash, WeakReference Desk) StampLaterWithANullText()
    {
        using var leash = new CancellationTokenSource();
        using var desk = new Stamps.StampDesk("Mylo");

        ArgumentNullException refused = Assert.Throws<ArgumentNullException>(() =>
        {
            _ = desk.StampLaterAsync(null!, leash.Token);
        });

        Assert.Equal("text", refused.ParamName);
        return (new WeakReference(leash), new WeakReference(desk));
    }

    [Fact]
    public void SuspendCompletion_MemberArgumentFailsItsNullGuard_ItsStateIsCollected()
    {
        (WeakReference leash, WeakReference desk) = StampLaterWithANullText();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to StampLaterAsync stayed reachable after the call threw ArgumentNullException: the completion callback's GCHandle was allocated before the null guard and never freed");
        Assert.True(
            MarkerCollectedWithin(desk, TimeSpan.FromSeconds(5)),
            "the disposed StampDesk stayed reachable after StampLaterAsync threw ArgumentNullException: the completion callback's GCHandle was allocated before the null guard and never freed");
    }

    // The static route: top-level `fetchGreeting(name: String)`. No wrapper and no scope, so the
    // leash is the only thing to watch, and a fix made on the instance shape alone stays red here.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference FetchGreetingWithANullName()
    {
        using var leash = new CancellationTokenSource();

        ArgumentNullException refused = Assert.Throws<ArgumentNullException>(() =>
        {
            _ = AsyncFunctions.FetchGreetingAsync(null!, leash.Token);
        });

        Assert.Equal("name", refused.ParamName);
        return new WeakReference(leash);
    }

    [Fact]
    public void SuspendCompletion_TopLevelArgumentFailsItsNullGuard_ItsStateIsCollected()
    {
        WeakReference leash = FetchGreetingWithANullName();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to FetchGreetingAsync stayed reachable after the call threw ArgumentNullException: the completion callback's GCHandle was allocated before the null guard and never freed");
    }

    // The disposed wrapper. A guard, not a reproduction: on a member with no suspend lambda the
    // disposed check is the wrapper's first statement, ahead of the allocation, so nothing is
    // stranded today. It pins that ordering for whoever moves the allocation or the check.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (WeakReference Leash, WeakReference Desk) StampLaterOnADisposedDesk()
    {
        using var leash = new CancellationTokenSource();
        var desk = new Stamps.StampDesk("Mylo");
        desk.Dispose();

        Assert.Throws<ObjectDisposedException>(() =>
        {
            _ = desk.StampLaterAsync("Oreo's parcel", leash.Token);
        });

        return (new WeakReference(leash), new WeakReference(desk));
    }

    [Fact]
    public void SuspendCompletion_MemberCallOnADisposedWrapper_ItsStateIsCollected()
    {
        (WeakReference leash, WeakReference desk) = StampLaterOnADisposedDesk();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to StampLaterAsync on a disposed StampDesk stayed reachable after the call threw ObjectDisposedException: the completion callback's GCHandle was allocated before the throw and never freed");
        Assert.True(
            MarkerCollectedWithin(desk, TimeSpan.FromSeconds(5)),
            "the disposed StampDesk stayed reachable after StampLaterAsync threw ObjectDisposedException: the completion callback's GCHandle was allocated before the throw and never freed");
    }

    // ------------------------------------------------------------------------------------------
    // The MANAGED-FAULT STASH on a Kotlin dispatcher thread. A callback thunk that catches a C#
    // throw stashes the exception in the `[ThreadStatic]` `NugetErrorNative._lastManagedFault` of
    // the thread it ran on, so the C# caller can get the original object back. Every synchronous
    // export clears that slot when it returns, on the thread that called it. The synchronous
    // prefix of a suspend lambda runs on a Kotlin dispatcher thread instead, and no C# export call
    // site ever runs there, so when Kotlin catches the throw and the call succeeds, nothing is left
    // that will clear the slot: the exception stays rooted for the life of that thread.
    //
    // A thread-static of a thread the test does not own cannot be read, so the proof is
    // collectability. The exception is built INSIDE the lambda, which captures only the report it
    // fills in, so the closure (released by the context key's cleaner) never holds it. Kotlin's
    // `recover` catches and returns a string, so no mapped exception, faulted `Task` or Kotlin
    // holder carries the object either. The stash is the only root there can be.
    //
    // Oreo throws the hairball. Mylo, as ever, just watches.
    // ------------------------------------------------------------------------------------------

    private sealed class HairballException(string message) : Exception(message);

    private sealed class HairballReport
    {
        public WeakReference? Hairball;
        public int CallerThread;
        public int ThrowerThread;
        public bool ThrowerIsPoolThread;
    }

    // The control: the same throw on the CALLING thread, through a synchronous export whose success
    // arm clears the stash (`CallbackFaults.recoverWith` catches too). Proves a caught hairball can
    // be collected at all, so the row below cannot be red only because the marker is wrong.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static HairballReport RecoverOnTheCallingThread()
    {
        var report = new HairballReport { CallerThread = Environment.CurrentManagedThreadId };
        using var faults = new CallbackFaults();

        string recovered = faults.RecoverWith(name =>
        {
            var hairball = new HairballException($"{name} coughed up a hairball");
            report.Hairball = new WeakReference(hairball);
            report.ThrowerThread = Environment.CurrentManagedThreadId;
            throw hairball;
        });

        Assert.StartsWith("recovered ", recovered);
        Assert.Contains("Oreo coughed up a hairball", recovered);
        return report;
    }

    [Fact]
    public void ManagedFault_ThrownOnTheCallingThread_AndCaughtByKotlin_IsCollected()
    {
        HairballReport report = RecoverOnTheCallingThread();

        Assert.NotNull(report.Hairball);
        Assert.Equal(report.CallerThread, report.ThrowerThread);
        Assert.True(
            MarkerCollectedWithin(report.Hairball, TimeSpan.FromSeconds(5)),
            "the exception a callback threw on the calling thread stayed reachable after Kotlin caught it and RecoverWith returned");
    }

    // Not `async`, and it throws before its first `await` would be: the throw happens inside the
    // Begin thunk, on whichever thread Kotlin invoked the lambda from.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static Task<string> StartRecovering(Treatprep.TreatChef chef, HairballReport report)
    {
        report.CallerThread = Environment.CurrentManagedThreadId;
        return chef.RecoverAsync((treat, token) =>
        {
            var hairball = new HairballException($"Oreo coughed up treat {treat}");
            report.Hairball = new WeakReference(hairball);
            report.ThrowerThread = Environment.CurrentManagedThreadId;
            report.ThrowerIsPoolThread = Thread.CurrentThread.IsThreadPoolThread;
            throw hairball;
        });
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<HairballReport> RecoverOnADispatcherThread()
    {
        var report = new HairballReport();
        using var chef = new Treatprep.TreatChef("Oreo");

        string recovered = await StartRecovering(chef, report);

        Assert.StartsWith("recovered ", recovered);
        Assert.Contains("Oreo coughed up treat 1", recovered);
        return report;
    }

    [Fact]
    public async Task ManagedFault_ThrownOnAKotlinDispatcherThread_AndCaughtByKotlin_IsCollected()
    {
        HairballReport report = await RecoverOnADispatcherThread();

        // The precondition, asserted so a green row cannot be a row that never left the caller's
        // thread: the lambda threw on a thread that is neither the caller's nor the .NET pool's.
        Assert.NotNull(report.Hairball);
        Assert.NotEqual(report.CallerThread, report.ThrowerThread);
        Assert.False(report.ThrowerIsPoolThread, "the lambda's synchronous prefix ran on a .NET pool thread, not a Kotlin dispatcher thread");

        Assert.True(
            MarkerCollectedWithin(report.Hairball, TimeSpan.FromSeconds(5)),
            "the exception a suspend lambda threw on a Kotlin dispatcher thread stayed reachable after Kotlin caught it and RecoverAsync completed: NugetErrorNative._lastManagedFault still holds it on that thread, which never runs a C# export call site that would clear it");
    }

    // ------------------------------------------------------------------------------------------
    // The same stash, through the OTHER thunks that can fill it. The rows above throw inside a
    // suspend lambda's Begin thunk. A fault can also arrive as a faulted `Task` (the End thunk
    // rethrows it with `GetAwaiter().GetResult()` and stashes what it catches), and a `Flow<T>`
    // parameter has a Begin and an End of its own (`NugetAsyncMoveNextBegin` / `...MoveNextEnd`).
    //
    // An End thunk never runs test code: the hairball is THROWN on a .NET pool thread, after a real
    // `await`, and STASHED on whichever thread Kotlin resumed the coroutine on. So the stashing
    // thread is observed through the one thing the catching thunk does with the exception besides
    // stashing it, which is read its `Message` to build the Kotlin-side error. The first reader of
    // `Message` is the stasher: nothing reads it between the `throw` and the thunk's `catch`.
    //
    // So these hairballs tattle on whoever picks them up first. Oreo and Mylo take turns coughing
    // them up through one helper, so no lambda, iterator or state machine has a slot to hold one.
    // ------------------------------------------------------------------------------------------

    private sealed class StashReport
    {
        public WeakReference? Hairball;
        public int CallerThread;
        public int ThrowerThread;
        public bool ThrowerIsPoolThread;
        public int StashThread;
        public bool StashIsPoolThread;
        public bool CaughtWasOriginal;

        public void NoteMessageRead()
        {
            int reader = Environment.CurrentManagedThreadId;
            if (Interlocked.CompareExchange(ref StashThread, reader, 0) != 0) return;
            StashIsPoolThread = Thread.CurrentThread.IsThreadPoolThread;
        }
    }

    // Holds the report, never the other way round: the report's only reference back is weak.
    private sealed class TattlingHairballException(string message, StashReport report) : Exception(message)
    {
        public override string Message
        {
            get
            {
                report.NoteMessageRead();
                return base.Message;
            }
        }
    }

    // The hairball exists in this frame only, and this frame ends in the throw.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void CoughUp(StashReport report, string message)
    {
        var hairball = new TattlingHairballException(message, report);
        report.Hairball = new WeakReference(hairball);
        report.ThrowerThread = Environment.CurrentManagedThreadId;
        report.ThrowerIsPoolThread = Thread.CurrentThread.IsThreadPoolThread;
        throw hairball;
    }

    // What every End-route row has to show before its collectability means anything: the throw
    // came after a real `await` (a pool thread, so it could only reach Kotlin as a faulted `Task`),
    // and the thunk that caught and stashed it ran on a thread that is neither the thrower's, nor
    // the caller's, nor the .NET pool's. That is a Kotlin dispatcher thread, the one the fix is for.
    private static void AssertStashedByAnEndThunkOnADispatcherThread(StashReport report)
    {
        Assert.NotNull(report.Hairball);
        Assert.True(report.ThrowerIsPoolThread, "the hairball was not thrown on a .NET pool thread, so it did not come after the await and did not travel as a faulted Task");
        Assert.NotEqual(0, report.StashThread);
        Assert.NotEqual(report.ThrowerThread, report.StashThread);
        Assert.NotEqual(report.CallerThread, report.StashThread);
        Assert.False(report.StashIsPoolThread, "the End thunk stashed the hairball on a .NET pool thread, not a Kotlin dispatcher thread");
    }

    // The Begin-route twin: the throw is synchronous, so the thrower IS the stasher, and it is a
    // Kotlin dispatcher thread.
    private static void AssertStashedByABeginThunkOnADispatcherThread(StashReport report)
    {
        Assert.NotNull(report.Hairball);
        Assert.Equal(report.ThrowerThread, report.StashThread);
        Assert.NotEqual(report.CallerThread, report.StashThread);
        Assert.False(report.StashIsPoolThread, "the Begin thunk ran on a .NET pool thread, not a Kotlin dispatcher thread");
    }

    // The FAULTED TASK of a suspend lambda. `async`, and the throw is after a real `await`, so the
    // Begin thunk returns normally and the fault reaches Kotlin through `NugetAsyncStringEnd`.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static Task<string> StartRecoveringAFaultedTask(Treatprep.TreatChef chef, StashReport report)
    {
        report.CallerThread = Environment.CurrentManagedThreadId;
        return chef.RecoverAsync(async (treat, token) =>
        {
            await Task.Delay(1, token);
            CoughUp(report, $"Mylo coughed up treat {treat}");
            return "unreachable";
        });
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<StashReport> RecoverAFaultedTask()
    {
        var report = new StashReport();
        using var chef = new Treatprep.TreatChef("Mylo");

        string recovered = await StartRecoveringAFaultedTask(chef, report);

        Assert.StartsWith("recovered ", recovered);
        Assert.Contains("Mylo coughed up treat 1", recovered);
        return report;
    }

    [Fact]
    public async Task ManagedFault_FromAFaultedTask_AndCaughtByKotlin_IsCollected()
    {
        StashReport report = await RecoverAFaultedTask();

        AssertStashedByAnEndThunkOnADispatcherThread(report);
        Assert.True(
            MarkerCollectedWithin(report.Hairball!, TimeSpan.FromSeconds(5)),
            "the exception a suspend lambda's Task faulted with stayed reachable after Kotlin caught it and RecoverAsync completed: the End thunk stashed it in NugetErrorNative._lastManagedFault on a Kotlin dispatcher thread and nothing cleared it there");
    }

    // The UNCAUGHT variant: Kotlin's `plate` lets the fault go, so the completion's fault arm takes
    // the stashed original back (ADR-161) and the outer `Task` faults with that very object. This
    // frame catches it, records only whether it WAS the original, and ends: its state machine is
    // cleared once its last `MoveNext` has returned, so neither the faulted `Task` nor the caught
    // exception outlives it. The row has to get off that `MoveNext`'s stack first; see its yield.
    // `afterAwait` picks the thunk that stashes: End for a faulted `Task`, Begin for a lambda that
    // throws before it has a `Task` to return.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static Task<string> StartPlatingAHairball(Treatprep.TreatChef chef, StashReport report, bool afterAwait)
    {
        report.CallerThread = Environment.CurrentManagedThreadId;
        if (afterAwait)
        {
            return chef.PlateAsync(1, async (treat, token) =>
            {
                await Task.Delay(1, token);
                CoughUp(report, $"Oreo coughed up treat {treat}");
                return "unreachable";
            });
        }
        return chef.PlateAsync(1, (treat, token) =>
        {
            CoughUp(report, $"Oreo coughed up treat {treat}");
            return Task.FromResult("unreachable");
        });
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<StashReport> PlateAHairball(bool afterAwait)
    {
        var report = new StashReport();
        using var chef = new Treatprep.TreatChef("Oreo");

        bool faulted = false;
        try
        {
            await StartPlatingAHairball(chef, report, afterAwait);
        }
        catch (Exception caught)
        {
            faulted = true;
            report.CaughtWasOriginal = ReferenceEquals(caught, report.Hairball?.Target);
        }
        Assert.True(faulted, "PlateAsync completed although its lambda faulted");
        return report;
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ManagedFault_NotCaughtByKotlin_IsCollectedOnceTheFaultedTaskIsDropped(bool afterAwait)
    {
        StashReport report = await PlateAHairball(afterAwait);
        // Load-bearing. Without a synchronization context (the NativeAOT runner has none) this
        // continuation runs INLINE, inside the helper's last `MoveNext`, whose frame still has the
        // slot its `catch` put the hairball in. The yield moves the rest of the row to a fresh
        // stack, so that frame unwinds and the helper's state machine is cleared behind it.
        await Task.Yield();

        if (afterAwait) AssertStashedByAnEndThunkOnADispatcherThread(report);
        else AssertStashedByABeginThunkOnADispatcherThread(report);
        // The original came back, so the completion read the stash on the thread that filled it:
        // a green row cannot be a row whose hairball is parked on some other thread's slot.
        Assert.True(report.CaughtWasOriginal, "PlateAsync faulted with something other than the exception the lambda threw");
        Assert.True(
            MarkerCollectedWithin(report.Hairball!, TimeSpan.FromSeconds(5)),
            "the exception a suspend lambda threw stayed reachable after PlateAsync faulted with it and the caller dropped both the Task and the exception");
    }

    // The `Flow<T>` PARAMETER route, End half. A compiler-written async iterator never throws out
    // of `MoveNextAsync`: whatever its body throws, before or after an `await`, is captured into
    // the `ValueTask<bool>` it returns. So the fault always crosses in `NugetAsyncMoveNextEnd`. The
    // `await` before the throw is still there, so the thrower is provably not the stasher.
    private static async IAsyncEnumerable<int> DripThenCoughUp(
        StashReport report,
        TaskCompletionSource closed,
        [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            await Task.Delay(1, token);
            yield return 1;
            await Task.Delay(1, token);
            CoughUp(report, "Mylo coughed into the fountain");
        }
        finally
        {
            closed.TrySetResult();
        }
    }

    // The Begin half needs a hand-written enumerator, the only kind whose `MoveNextAsync` can throw
    // on the thread that called it: the second pull throws inside `NugetAsyncMoveNextBegin` itself.
    // A fresh enumerator per call, and no field anywhere that could hold the hairball.
    private sealed class ChokedFountain(StashReport report, TaskCompletionSource closed) : IAsyncEnumerable<int>
    {
        public IAsyncEnumerator<int> GetAsyncEnumerator(CancellationToken cancellationToken = default) =>
            new Spout(report, closed);

        private sealed class Spout(StashReport report, TaskCompletionSource closed) : IAsyncEnumerator<int>
        {
            public int Current { get; private set; }

            public ValueTask<bool> MoveNextAsync()
            {
                if (Current == 1) CoughUp(report, "Oreo coughed into the fountain");
                Current++;
                return new ValueTask<bool>(true);
            }

            public ValueTask DisposeAsync()
            {
                closed.TrySetResult();
                return default;
            }
        }
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<string> Recovering, Task Closed) StartRecoveringAFountain(
        Trickle.WaterFountain fountain, StashReport report, bool synchronousThrow)
    {
        var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        report.CallerThread = Environment.CurrentManagedThreadId;
        IAsyncEnumerable<int> drops = synchronousThrow
            ? new ChokedFountain(report, closed)
            : DripThenCoughUp(report, closed);
        return (fountain.RecoverAsync(drops), closed.Task);
    }

    // Waits for the enumerator's own end as well as the call's: the queued dispose holds the
    // enumeration, and with it the faulted step, until it has run. A bounded hold, not the stash.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<StashReport> RecoverAFountain(bool synchronousThrow, string hairball)
    {
        var report = new StashReport();
        using var fountain = new Trickle.WaterFountain("Mylo");

        (Task<string> recovering, Task closed) = StartRecoveringAFountain(fountain, report, synchronousThrow);
        string recovered = await recovering;
        await closed.WaitAsync(TimeSpan.FromSeconds(30));

        Assert.StartsWith("recovered after 1 ", recovered);
        Assert.Contains(hairball, recovered);
        return report;
    }

    [Fact]
    public async Task ManagedFault_FromAFlowParameterIteratorThatThrowsMidStream_AndCaughtByKotlin_IsCollected()
    {
        StashReport report = await RecoverAFountain(synchronousThrow: false, "Mylo coughed into the fountain");

        AssertStashedByAnEndThunkOnADispatcherThread(report);
        Assert.True(
            MarkerCollectedWithin(report.Hairball!, TimeSpan.FromSeconds(5)),
            "the exception an IAsyncEnumerable iterator threw mid-stream stayed reachable after Kotlin caught it and RecoverAsync completed: NugetAsyncMoveNextEnd stashed it in NugetErrorNative._lastManagedFault on a Kotlin dispatcher thread and nothing cleared it there");
    }

    [Fact]
    public async Task ManagedFault_FromAFlowParameterMoveNextThatThrowsSynchronously_AndCaughtByKotlin_IsCollected()
    {
        StashReport report = await RecoverAFountain(synchronousThrow: true, "Oreo coughed into the fountain");

        AssertStashedByABeginThunkOnADispatcherThread(report);
        Assert.True(
            MarkerCollectedWithin(report.Hairball!, TimeSpan.FromSeconds(5)),
            "the exception a hand-written MoveNextAsync threw stayed reachable after Kotlin caught it and RecoverAsync completed: NugetAsyncMoveNextBegin stashed it in NugetErrorNative._lastManagedFault on a Kotlin dispatcher thread and nothing cleared it there");
    }

    // ADR-222: a C#-implemented generic interface releases exactly like a non-generic one. The
    // Diary crosses once in its own frame; the call site disposes the transfer handle, so Kotlin's
    // GC owns the bridge's only root. Once the Kotlin cleaner runs, the generic
    // `KeepsakeBridgeState<string>` frees its pins (`ReleasedCount` moves) and nothing on either
    // side keeps the Diary alive (its weak reference dies). A generic state that skipped `FreeAll`
    // would keep the token `GCHandle`, and the Diary with it.
    private sealed class GenericDiary : Playroom.IKeepsake<string>
    {
        public string Item { get; set; } = "Oreo";
        public void Dispose() { }
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference InspectAndDropADiary()
    {
        var diary = new GenericDiary();
        Assert.Equal("curator sees Oreo", Playroom.Keepsakes.Inspect(diary));
        return new WeakReference(diary);
    }

    [Fact]
    public void CSharpGenericKeepsake_ReleasedAfterKotlinGc()
    {
        int before = NugetBridgeState.ReleasedCount;

        WeakReference diary = InspectAndDropADiary();

        DateTime deadline = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        while (DateTime.UtcNow < deadline && (diary.IsAlive || NugetBridgeState.ReleasedCount <= before))
        {
            NugetMarshal.GcCollect();
            GC.Collect();
            GC.WaitForPendingFinalizers();
            Thread.Sleep(25);
        }

        Assert.True(
            NugetBridgeState.ReleasedCount > before,
            $"expected the Kotlin cleaner to release the generic Keepsake bridge; ReleasedCount stayed at {before}");
        Assert.False(diary.IsAlive, "the C# Diary stayed reachable after Kotlin collected its bridge");
    }

    // ADR-229: the static suspend route. An `object`'s member and a suspend extension allocate the
    // same completion `GCHandle` as the top-level route, and only the completion (or the catch
    // around the P/Invoke) frees it. The marker is the `CancellationTokenSource` the closure
    // captures; on the extension row the borrowed receiver is watched too, because the C# wrapper
    // passes its handle and must not be kept alive by the finished call.
    // Oreo naps on the swing; Mylo yawns at the alarm.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<int> Napping, WeakReference Leash) StartSiesta()
    {
        var leash = new CancellationTokenSource();
        Task<int> napping = Siesta.MinutesForAsync("Oreo", leash.Token);
        return (napping, new WeakReference(leash));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<WeakReference> NapAndDrop()
    {
        (Task<int> napping, WeakReference leash) = StartSiesta();

        Assert.Equal(40, await napping);
        await Task.Yield();
        return leash;
    }

    [Fact]
    public async Task StaticSuspend_ObjectCallCompletes_ItsStateIsCollected()
    {
        WeakReference leash = await NapAndDrop();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to Siesta.MinutesForAsync stayed reachable after the call completed");
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (Task<string> Rocking, WeakReference Leash, WeakReference Swing) StartRocking()
    {
        var leash = new CancellationTokenSource();
        var swing = new Swing("Oreo");
        Task<string> rocking = swing.RockAsync(2, leash.Token);
        return (rocking, new WeakReference(leash), new WeakReference(swing));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<(WeakReference Leash, WeakReference Swing)> RockAndDrop()
    {
        (Task<string> rocking, WeakReference leash, WeakReference swing) = StartRocking();

        Assert.Equal("Oreo rocked 2 times", await rocking);
        await Task.Yield();
        return (leash, swing);
    }

    [Fact]
    public async Task StaticSuspend_ExtensionCallCompletes_ItsStateAndReceiverAreCollected()
    {
        (WeakReference leash, WeakReference swing) = await RockAndDrop();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to RockAsync stayed reachable after the call completed");
        Assert.True(
            MarkerCollectedWithin(swing, TimeSpan.FromSeconds(5)),
            "the Swing receiver of RockAsync stayed reachable after the call completed");
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference MinutesForANullName()
    {
        using var leash = new CancellationTokenSource();

        ArgumentNullException refused = Assert.Throws<ArgumentNullException>(() =>
        {
            _ = Siesta.MinutesForAsync(null!, leash.Token);
        });

        Assert.Equal("name", refused.ParamName);
        return new WeakReference(leash);
    }

    [Fact]
    public void StaticSuspend_ArgumentFailsItsNullGuard_ItsStateIsCollected()
    {
        WeakReference leash = MinutesForANullName();

        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to Siesta.MinutesForAsync stayed reachable after the call threw ArgumentNullException: the completion callback's GCHandle was never freed");
    }

    // ADR-230: a flow on an owner with no instance scope. No owner scope tracks the collection, so
    // once the enumeration ends (by completion or by the token) nothing may keep the returned
    // `KotlinFlow` or the token's source reachable: the job's completion callback is the only
    // thing that held them. Neither flow is disposed, on purpose: a static flow owns no scope, so
    // an undisposed one must not pin anything.
    // Oreo listens to the purrs to the end; Mylo stops the clock with the token.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<(WeakReference Flow, WeakReference Leash)> PurrToTheEndAndDrop()
    {
        var leash = new CancellationTokenSource();
        KotlinFlow<int> purrs = Purrclock.Chimes.Purrs(3);
        var seen = new List<int>();
        await foreach (int n in purrs.WithCancellation(leash.Token)) seen.Add(n);
        Assert.Equal([0, 1, 2], seen);
        var refs = (new WeakReference(purrs), new WeakReference(leash));
        purrs = null!;
        leash = null!;
        await Task.Yield();
        return refs;
    }

    [Fact]
    public async Task StaticFlow_CollectedToCompletion_TheFlowAndTokenAreCollected()
    {
        (WeakReference flow, WeakReference leash) = await PurrToTheEndAndDrop();

        Assert.True(
            MarkerCollectedWithin(flow, TimeSpan.FromSeconds(5)),
            "the KotlinFlow returned by Chimes.Purrs stayed reachable after its enumeration completed");
        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to Chimes.Purrs stayed reachable after its enumeration completed");
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<(WeakReference Flow, WeakReference Leash)> StopTheClockAndDrop()
    {
        var leash = new CancellationTokenSource();
        KotlinFlow<int> forever = Purrclock.PurrClock.Forever();
        int seen = 0;
        await foreach (int _ in forever.WithCancellation(leash.Token))
        {
            if (++seen == 2) leash.Cancel();
        }
        var refs = (new WeakReference(forever), new WeakReference(leash));
        forever = null!;
        leash = null!;
        await Task.Yield();
        return refs;
    }

    [Fact]
    public async Task StaticFlow_CancelledByTheToken_TheFlowAndTokenAreCollected()
    {
        (WeakReference flow, WeakReference leash) = await StopTheClockAndDrop();

        Assert.True(
            MarkerCollectedWithin(flow, TimeSpan.FromSeconds(5)),
            "the KotlinFlow returned by PurrClock.Forever stayed reachable after its token cancelled the enumeration");
        Assert.True(
            MarkerCollectedWithin(leash, TimeSpan.FromSeconds(5)),
            "the CancellationTokenSource passed to PurrClock.Forever stayed reachable after it cancelled the enumeration");
    }
}

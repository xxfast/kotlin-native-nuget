using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Cat;

namespace LeakTests;

/// <summary>
/// ADR-121: ADR-120's counter proves the <c>StableRef</c> goes; this proves the *object* goes.
/// Kotlin's <see cref="Morgue"/> holds one weak reference, C# disposes the last wrapper and drives
/// Kotlin's GC through <c>NugetBridge.GcCollect()</c> until the weak reference reads dead.
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
            NugetBridge.GcCollect();
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

    // Negative control: a live subscription token is the only remaining root and must keep the
    // source alive. Proves the assertion above can go red, and documents that a leaked token roots
    // the receiver.
    [Fact]
    public void StoredCallbackReceiver_TokenStillHeld_StaysAlive()
    {
        IDisposable sub = SubscribeAndDisposeTheSourceOnly();
        try
        {
            Assert.False(
                CollectedWithin(TimeSpan.FromSeconds(1)),
                "a live subscription token must root the source");

            sub.Dispose();
            Assert.True(
                CollectedWithin(TimeSpan.FromSeconds(5)),
                "CatEventSource stayed reachable after its last token was disposed");
        }
        finally
        {
            sub.Dispose();
            Morgue.Forget();
        }
    }
}

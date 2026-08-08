using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-084 stage 1: C# classes implementing a Kotlin interface become passable to Kotlin, via a
/// per-interface bridge factory export and a `HandleOf` dispatch fallback (no `_handle` field on
/// the C# object -> route through the bridge instead of throwing). Lifetime release and identity
/// round-trip are later stages (2 and 3); stage 1 only proves the slots dispatch.
/// </summary>
public class BidirectionalTests
{
    private class Dog : IPet
    {
        public string Name { get; }
        public int Legs => 4;
        public string? Nickname { get; }
        public Dog(string name, string? nickname = null) { Name = name; Nickname = nickname; }
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
        public void Nap() { }
        public void Dispose() { }
    }

    [Fact]
    public void CSharpDog_ImplementsIPet()
    {
        using IPet dog = new Dog("Rex");
        Assert.Equal("Rex", dog.Name);
        Assert.Equal("Woof!", dog.Speak());
        Assert.Equal("Hi, I'm Rex the dog", dog.Greet());

        // The bridge crossing: `dog` has no `_handle`, so `Befriend` must route through
        // `NugetMarshal.HandleOf`'s bridge fallback rather than throwing. `ClosestFriend().Speak()`
        // and `Interview(dog)` only return their sentinel values if Kotlin actually dispatched
        // back into this `Dog` instance through the generated function-pointer slots - an echo of
        // the C# call or a Kotlin-side default cannot produce "Woof!" or "Rex says: Woof!".
        using var oreo = new Cat("Oreo", 9);
        oreo.Befriend(dog);
        Assert.Equal("Woof!", oreo.ClosestFriend().Speak());
        Assert.Equal("Rex says: Woof!", oreo.Interview(dog));
    }

    // ADR-084 removes the ADR-040 v1 boundary this test used to pin: a C#-implemented `IPet` (no
    // `_handle`) now crosses via the bridge-factory fallback in `NugetMarshal.HandleOf`, instead
    // of throwing `NotSupportedException`.
    [Fact]
    public void Cat_Befriend_CSharpImplementedPet_CrossesBridge()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet dog = new Dog("Rex");

        oreo.Befriend(dog);

        Assert.Equal("Woof!", oreo.ClosestFriend().Speak());
    }

    // Facets 1+2 (non-Unit method return, property getter) with values chosen so marshalling bugs
    // surface: a non-ASCII name (multi-byte UTF-8) and an empty-string nickname (distinct from the
    // `null` nickname already covered above - IntPtr.Zero must mean "null", not "empty").
    [Fact]
    public void CSharpDog_PropertyGetterAndMethodReturn_CrossBridgeWithNonAsciiAndEmptyString()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet dog = new Dog("Röver 🐕", nickname: "");

        oreo.Befriend(dog);
        using IPet friend = oreo.ClosestFriend();

        Assert.Equal("Röver 🐕", friend.Name);
        Assert.Equal("", friend.Nickname);
        Assert.Equal("Röver 🐕 says: Woof!", oreo.Interview(dog));
    }

    // The other half of the nullable-String getter slot: `IntPtr.Zero` must mean "null", not just
    // "not empty". The empty-string case above already proves a non-null pointer round-trips; this
    // proves the null branch itself crosses (the getter returning a nullable `COpaquePointer?`,
    // unwrapped to `null` on the C# side) rather than only being pinned at compile time.
    [Fact]
    public void CSharpDog_NullNickname_CrossesBridgeAsNull()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet dog = new Dog("Rex", nickname: null);

        oreo.Befriend(dog);
        using IPet friend = oreo.ClosestFriend();

        Assert.Null(friend.Nickname);
        // Thorough: the null nickname doesn't collateral-damage the other slots on the same
        // dispatched instance.
        Assert.Equal("Rex", friend.Name);
        Assert.Equal("Woof!", friend.Speak());
    }

    // ADR-084 stage 2 (facet 4): the bridge object owns a Kotlin `createCleaner` holding only the
    // release function pointer and its context, so when Kotlin's GC collects the bridge the C# side
    // is called back and frees every GCHandle it pinned for that object.
    //
    // The spike behind the ADR proved a live stack frame roots the bridge even after `= null`, so
    // the object is created and dropped inside a separate method frame, and the assertion polls a
    // bounded number of forced collections rather than sleeping once.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void BefriendAndDrop(Cat oreo) => oreo.Befriend(new Dog("Rex"));

    [Fact]
    public void BridgedPet_ReleasesItsCSharpHandles_AfterKotlinDropsTheBridge()
    {
        using var oreo = new Cat("Oreo", 9);
        // Settle anything an earlier test in this class left pending, so the delta below can only
        // be Rex's bridge: without this, "some bridge was released" would pass vacuously.
        NugetBridge.ReleaseTransferHandles();
        SettleReleases();
        int before = NugetBridgeState.ReleasedCount;

        BefriendAndDrop(oreo);
        // Kotlin's own reference to the bridge (`friend = pet`) is the thing that must go for the
        // bridge to become collectible.
        oreo.Friend = null;
        // ...and the transfer StableRef the factory minted is C#'s reference to it.
        NugetBridge.ReleaseTransferHandles();

        Assert.True(
            ReleaseFiredWithin(before, TimeSpan.FromSeconds(5)),
            $"expected the Kotlin cleaner to invoke the release slot; ReleasedCount stayed at {before}");
    }

    // The other half of the release contract: a bridge Kotlin still holds must survive a collection
    // untouched. A premature release would free the delegate GCHandles under a live bridge, so the
    // next dispatch would call through a dead function pointer instead of returning "Woof!".
    [Fact]
    public void LiveBridgedPet_SurvivesACollection_AndStillDispatches()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet dog = new Dog("Rex");
        oreo.Befriend(dog);

        NugetBridge.GcCollect();
        GC.Collect();
        GC.WaitForPendingFinalizers();

        Assert.Equal("Woof!", oreo.ClosestFriend().Speak());
        Assert.Equal("Rex says: Woof!", oreo.Interview(dog));
    }

    private static void SettleReleases()
    {
        for (int round = 0; round < 5; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            NugetBridge.GcCollect();
            Thread.Sleep(25);
        }
    }

    private static bool ReleaseFiredWithin(int before, TimeSpan budget)
    {
        DateTime deadline = DateTime.UtcNow + budget;
        while (DateTime.UtcNow < deadline)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            NugetBridge.GcCollect();
            if (NugetBridgeState.ReleasedCount > before) return true;
            Thread.Sleep(25);
        }
        return false;
    }
}

using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Menagerie;
using Test.Menagerie;

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

    // ADR-084 stage 3 (facet 5): a stored C#-implemented pet handed back to C# must resolve to the
    // original instance. Without the token probe, `ClosestFriend()` wraps the bridge handle in a
    // second `Pet` wrapper, so every read round-trips through two bridges and `Assert.Same` fails.
    [Fact]
    public void StoredCSharpPet_RoundTripsToTheOriginalInstance()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet dog = new Dog("Rex");

        oreo.Befriend(dog);

        Assert.Same(dog, oreo.ClosestFriend());
        Assert.Same(dog, oreo.Friend);
    }

    [Fact]
    public void RepeatedCrossings_ResolveToTheOneCSharpInstance()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet dog = new Dog("Rex");

        oreo.Befriend(dog);
        Assert.Same(dog, oreo.ClosestFriend());

        // A second crossing of the same object: the identity must survive a fresh bridge, and the
        // slots must still dispatch (a stale handle would fault or return the wrong value here).
        oreo.Befriend(dog);
        Assert.Same(dog, oreo.ClosestFriend());
        Assert.Equal("Woof!", oreo.ClosestFriend().Speak());
    }

    // The probe must not hijack a Kotlin-backed pet: its handle carries no bridge token, so the
    // return position still constructs the generated wrapper.
    [Fact]
    public void KotlinBackedPet_StillResolvesToItsWrapper()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mochi = new Cat("Mochi", 7);

        oreo.Befriend(mochi);
        using IPet friend = oreo.ClosestFriend();

        Assert.IsNotType<Dog>(friend);
        Assert.Equal("Mochi", friend.Name);
    }

    [Fact]
    public void MixedStores_ResolveIndependently()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mochi = new Cat("Mochi", 7);
        using IPet dog = new Dog("Rex");

        oreo.Befriend(dog);
        Assert.Same(dog, oreo.ClosestFriend());

        oreo.Befriend(mochi);
        using IPet kotlinFriend = oreo.ClosestFriend();
        Assert.IsNotType<Dog>(kotlinFriend);
        Assert.Equal("Mochi", kotlinFriend.Name);

        oreo.Befriend(dog);
        Assert.Same(dog, oreo.ClosestFriend());
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
        SettleReleases();
        int before = NugetBridgeState.ReleasedCount;

        BefriendAndDrop(oreo);
        // Kotlin's own reference to the bridge (`friend = pet`) is the only root left: ADR-084
        // stage 3 disposes the factory's transfer handle at the call site, so dropping this makes
        // the bridge collectible with no explicit hand-back from the host.
        oreo.Friend = null;

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

    // The property-setter position of the same crossing: `Friend = dog` mints a transfer handle
    // exactly like `Befriend(dog)` does, and must dispose it after the native call. Until it did,
    // that handle rooted the bridge forever and the cleaner could never fire.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void AssignFriendAndDrop(Cat oreo) => oreo.Friend = new Dog("Rex");

    [Fact]
    public void PetAssignedThroughTheSetter_DispatchesAndThenReleases()
    {
        using var oreo = new Cat("Oreo", 9);
        SettleReleases();
        int before = NugetBridgeState.ReleasedCount;

        AssignFriendAndDrop(oreo);
        Assert.Equal("Woof!", oreo.ClosestFriend().Speak());
        Assert.Equal("Rex", oreo.Friend!.Name);

        oreo.Friend = null;

        Assert.True(
            ReleaseFiredWithin(before, TimeSpan.FromSeconds(5)),
            $"expected the setter's transfer handle to be released; ReleasedCount stayed at {before}");
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

    // ADR-088: a bound C# interface (`Test.Menagerie.IFeedable`, the ADR-070 stub) at ORDINARY
    // FORWARD parameter/return positions on a public Kotlin class (`Farm`). Unlike ADR-084's
    // `IPet` (a Kotlin-declared interface the C# side implements against a purely forward-facing
    // contract), `IFeedable` here is the ORIGINAL type the consumer already has from the
    // TestDependency package -- not a re-projected duplicate. `Test.Menagerie` resolves in this
    // project without a direct PackageReference: TestLibrary's own nuspec declares TestDependency
    // as a package dependency (no `exclude` attribute), so NuGet's transitive restore surfaces
    // `TestDependency.dll` as a compile asset here too (confirmed via
    // `IntegrationTests/obj/project.assets.json`: `TestDependency/<version>` appears under
    // `net10.0`'s `compile` assets even though `IntegrationTests.csproj` never references it).
    //
    // EXPECTED TO FAIL TODAY: `Farm.Adopt`/`Farm.Resident` do not exist because the Kotlin
    // fixture (`Farm.kt`) does not compile yet -- the ADR-070 stub interface is `internal`, and a
    // public Kotlin declaration exposing an internal type in its signature is a compile error
    // (`EXPOSED_PARAMETER_TYPE`/`EXPOSED_FUNCTION_RETURN_TYPE`). That failure aborts
    // `nugetGenerateBindings`/the native build before `TestLibrary.Menagerie.Farm` is ever
    // generated, so this whole test assembly fails to build -- the same shape as the Wave 1
    // enum-import round.
    private class CSharpGoat : IFeedable
    {
        public int MealsFed { get; private set; }

        public string Describe() => "Nibbles the C#-side goat";

        public int Legs => 4;

        public void Feed(string food) => MealsFed++;

        public string? Nickname { get; set; }
    }

    [Fact]
    public void Farm_Adopt_StoresTheCSharpImplementedResident_SameInstanceBack()
    {
        var farm = new Farm();
        var goat = new CSharpGoat();

        farm.Adopt(goat);

        // ADR-088's identity promise for a C#-originated object: the return-side GCHandle
        // duplicate must hand back the SAME managed instance, not a fresh wrapper over it.
        Assert.Same(goat, farm.Resident());
    }

    [Fact]
    public void Farm_ResidentLegs_DispatchesIntoTheCSharpImplementedResident()
    {
        var farm = new Farm();
        var goat = new CSharpGoat();

        farm.Adopt(goat);

        // Proves the forward parameter crossing did not just stash a copy or a null-op stub: the
        // Kotlin side actually called back into `CSharpGoat.Legs` through the bound interface.
        Assert.Equal(4, farm.ResidentLegs());
    }

    [Fact]
    public void Farm_Resident_ComposesWithTheBoundReverseApi()
    {
        // The consumer-experience claim from the ADR: the value Farm hands back is the REAL
        // `Test.Menagerie.IFeedable`, so it must be usable wherever the bound reverse API (a
        // Kotlin type implementing the same interface, e.g. `Ferret`) is usable -- no conversion
        // layer, just one type.
        var farm = new Farm();
        var goat = new CSharpGoat();
        farm.Adopt(goat);

        IFeedable resident = farm.Resident();

        Assert.Equal("Nibbles the C#-side goat", resident.Describe());
        Assert.Equal(0, goat.MealsFed);
        resident.Feed("hay");
        Assert.Equal(1, goat.MealsFed);
    }
}

using System.Reflection;
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Nested;

namespace IntegrationTests;

/// <summary>
/// Three generated-C# sites still spell an interface bare (<c>IKeeper</c>) or as its ADR-040
/// backing wrapper, instead of going through the one qualifier issue #41 requires of every render
/// site. Each has been invisible because every existing fixture sits in the same namespace as its
/// interface, or has no fixture at all.
///
/// (a) the ADR-039 add/remove pair renders <c>I$simpleName</c> bare, so a nested
/// (<see cref="Aviary.IWatcher"/>) or cross-package (<see cref="ICatEventListener"/>) listener is
/// CS0246 in the generated file;
/// (b) <c>suspend fun</c> returning <c>StateFlow&lt;Interface&gt;</c> spells the wrapper and passes
/// no <c>read:</c>, so the consumer sees <c>Task&lt;KotlinStateFlow&lt;Aviary.Keeper&gt;&gt;</c>;
/// (c) a generic bound on a top-level type from another package is spelled bare, both for the
/// interface bound (<c>where T : IPet</c>) and the class bound (<c>where T : Cat</c>).
///
/// Oreo lands on things, Mylo watches the window, and someone has to be on keeper duty.
/// </summary>
public class InterfaceSpellingSiteTests
{
    // --- (a) add/remove pair, NESTED listener interface ---

    private sealed class RecordingWatcher : Aviary.IWatcher
    {
        public List<string> Landings { get; } = new();
        public int FlyOffs { get; private set; }
        public void OnLand(string perch) => Landings.Add(perch);
        public void OnFlyOff() => FlyOffs++;
        public void Dispose() { }
    }

    [Fact]
    public void NestedListener_SubscribedThroughAddPair_IsCalledBackAndStopsAfterDispose()
    {
        using var watch = new PerchWatch("Oreo");
        var watcher = new RecordingWatcher();

        IDisposable subscription = watch.AddWatcher(watcher);
        watch.Rustle();

        Assert.Equal(new[] { "Oreo the perch creaks" }, watcher.Landings);
        Assert.Equal(1, watcher.FlyOffs);

        subscription.Dispose();
        watch.Rustle();

        Assert.Single(watcher.Landings);
        Assert.Equal(1, watcher.FlyOffs);
    }

    [Fact]
    public void NestedListener_AddPairParameter_IsTypedWithTheNestedInterface()
    {
        // The declaration seam itself: a bare `IWatcher` would not resolve to the nested type.
        MethodInfo add = typeof(PerchWatch).GetMethod(nameof(PerchWatch.AddWatcher))!;
        Assert.Equal(typeof(Aviary.IWatcher), add.GetParameters().Single().ParameterType);
    }

    // --- (a) add/remove pair, CROSS-PACKAGE listener interface ---

    private sealed class RecordingSillListener : ICatEventListener
    {
        public List<string> Meows { get; } = new();
        public int Purrs { get; private set; }
        public void OnMeow(string message) => Meows.Add(message);
        public void OnPurr() => Purrs++;
        public void Dispose() { }
    }

    [Fact]
    public void CrossPackageListener_SubscribedThroughAddPair_IsCalledBackAndStopsAfterDispose()
    {
        using var sill = new WindowSill("Mylo");
        var listener = new RecordingSillListener();

        IDisposable subscription = sill.AddListener(listener);
        sill.Tap();

        Assert.Equal(new[] { "Mylo spots a bird" }, listener.Meows);
        Assert.Equal(1, listener.Purrs);

        subscription.Dispose();
        sill.Tap();

        Assert.Single(listener.Meows);
        Assert.Equal(1, listener.Purrs);
    }

    [Fact]
    public void CrossPackageListener_AddPairParameter_IsTypedWithTheOtherNamespacesInterface()
    {
        MethodInfo add = typeof(WindowSill).GetMethod(nameof(WindowSill.AddListener))!;
        Assert.Equal(typeof(ICatEventListener), add.GetParameters().Single().ParameterType);
    }

    // --- (b) suspend fun returning StateFlow<Interface> ---

    private sealed class DutyKeeper : Aviary.IKeeper
    {
        public string Greet() => "the C# keeper reporting";
        public void Dispose() { }
    }

    [Fact]
    public async Task KeeperReport_AwaitsToAStateFlowOfTheInterface_NotTheBackingWrapper()
    {
        using var aviary = new Aviary("Oreo");
        using KotlinStateFlow<Aviary.IKeeper> report = await aviary.KeeperReportAsync();

        Aviary.IKeeper keeper = report.Value;
        Assert.IsAssignableFrom<Aviary.IKeeper>(keeper);
        Assert.Equal("on duty keeper of Oreo", keeper.Greet());
    }

    [Fact]
    public void KeeperReport_ElementTypeIsTheInterface_NotTheWrapper()
    {
        // The reflection cell: the wrapper spelling is assignable to the interface-typed local
        // above, so only the declared element type distinguishes the two.
        MethodInfo report = typeof(Aviary).GetMethod("KeeperReportAsync")!;
        Type element = report.ReturnType.GetGenericArguments().Single().GetGenericArguments().Single();
        Assert.Equal(typeof(Aviary.IKeeper), element);
    }

    [Fact]
    public async Task KeeperReport_ResolvesAStoredCSharpKeeperToTheOriginalInstance()
    {
        // ADR-136 identity, over the StateFlow-of-interface read: the element read needs an
        // explicit `read:` that resolves the bridge token, not a `new Aviary.Keeper(h)`.
        using var aviary = new Aviary("Mylo");
        using Aviary.IKeeper booked = new DutyKeeper();
        aviary.Book(booked);

        using KotlinStateFlow<Aviary.IKeeper> report = await aviary.KeeperReportAsync();

        Assert.Same(booked, report.Value);
        Assert.Equal("the C# keeper reporting", report.Value.Greet());
    }

    // --- (c) generic bound on a top-level type from another package ---

    [Fact]
    public void PetCrate_BoundIsTheQualifiedInterface_AndAcceptsACat()
    {
        using var oreo = new Cat("Oreo", 9);
        using var crate = new PetCrate<Cat>(oreo);

        // Constructor and `Value` are the whole generic-class surface (ADR-032); the bound is
        // what this cell is about.
        Assert.Equal("Oreo", crate.Value.Name);
        Type[] constraints = typeof(PetCrate<>).GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Contains(typeof(IPet), constraints);
    }

    [Fact]
    public void CatCrate_ClassBoundIsTheQualifiedClass_AndAcceptsACat()
    {
        using var mylo = new Cat("Mylo", 4);
        using var crate = new CatCrate<Cat>(mylo);

        Assert.Equal("Mylo", crate.Value.Name);
        Type[] constraints = typeof(CatCrate<>).GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Contains(typeof(Cat), constraints);
    }
}

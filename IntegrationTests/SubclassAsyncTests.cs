using TestLibrary.Lounge;

namespace IntegrationTests;

/// <summary>
/// One coroutine scope per instance, owned by the first class in the kept chain that projects an
/// async member, wherever in the chain that is. Everything below the owner reuses that scope and
/// inherits <c>DisposeAsync</c>; a class whose only async member was refused owns nothing.
///
/// Every cell here is red today: the scope emission only fires on a base-less class, so the
/// generated <c>Interop.cs</c> does not compile for most of the fixture (CS0103, CS0108, CS0122,
/// CS0535). Oreo and Mylo have to be able to nap anywhere in the hierarchy.
/// </summary>
public class SubclassAsyncTests
{
    // --- The first async member is on the DERIVED class: the subclass owns the scope. -----------

    [Fact]
    public async Task DerivedOwner_AwaitsAStringReturningSuspendMember_ThroughAwaitUsing()
    {
        // Oreo naps on the windowsill. `await using` only compiles if NapLounge itself is
        // IAsyncDisposable (CS8410 otherwise), which is the half that never fires today.
        await using var lounge = new NapLounge("the windowsill");
        Assert.Equal("Oreo napped on the windowsill", await lounge.RestAsync("Oreo"));
        Assert.Equal("the windowsill is warm", lounge.DescribeLounge());
    }

    [Fact]
    public async Task DerivedOwner_AwaitsAnIntReturningSuspendMember()
    {
        // The raw-result twin of the cell above: no string conversion on the way back.
        await using var lounge = new NapLounge("the radiator");
        Assert.Equal(12, await lounge.RestMinutesAsync());
    }

    [Fact]
    public void DerivedOwner_IsAsyncDisposable_AndTheBaseIsNot()
    {
        // The scope lives on exactly one level. SunShelf projects no async member, so handing it
        // IAsyncDisposable would be the bug in the other direction.
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(NapLounge)));
        Assert.False(typeof(IAsyncDisposable).IsAssignableFrom(typeof(SunShelf)));
        Assert.True(typeof(IDisposable).IsAssignableFrom(typeof(SunShelf)));
    }

    [Fact]
    public async Task DerivedOwner_DisposeAsync_ThroughAnIAsyncDisposableReference()
    {
        // What the base list unlocks over the `await using` pattern: an IAsyncDisposable-typed
        // reference, as a DI container would hold Mylo's lounge.
        object lounge = new NapLounge("the bookshelf");
        Assert.Equal(12, await ((NapLounge)lounge).RestMinutesAsync());
        await ((IAsyncDisposable)lounge).DisposeAsync();
    }

    [Fact]
    public async Task DerivedOwner_CollectsItsOwnFlow_AndDrains()
    {
        // The Flow route on the same owner: a Flow-returning member is scope-using too, so it must
        // reach the derived class's own scope rather than a field nobody declared.
        await using var lounge = new NapLounge("the sofa");
        var seen = new List<string>();
        await foreach (string napper in lounge.Nappers()) seen.Add(napper);
        Assert.Equal(new[] { "Oreo", "Mylo" }, seen);
    }

    // --- The first async member is on the BASE: the subclass reuses the base's scope. -----------

    [Fact]
    public async Task BaseOwner_DerivedInstance_AwaitsThroughTheDerivedType()
    {
        // Mylo settles on the cushioned perch. The member is declared on WindowSeat and called through
        // the subclass, which owns no scope of its own and must not declare one.
        await using var perch = new PaddedWindowSeat(9);
        Assert.Equal("Mylo settled at 9", await perch.SettleAsync("Mylo"));
        Assert.Equal("9 cushioned", perch.Cushion());
    }

    [Fact]
    public async Task BaseOwner_DerivedInstance_AwaitsThroughTheBaseReference()
    {
        // Same call, base-typed reference: one scope, reached from either spelling.
        await using WindowSeat perch = new PaddedWindowSeat(4);
        Assert.Equal("Oreo settled at 4", await perch.SettleAsync("Oreo"));
    }

    [Fact]
    public void BaseOwner_SubclassDeclaresNoSecondScope()
    {
        // The subclass inherits DisposeAsync from the owner rather than hiding it with a second
        // one (CS0108 today). `DeclaringType` is the assertion: a `new`-hidden method would name
        // PaddedWindowSeat here, and the drain would then run against the wrong level.
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(PaddedWindowSeat)));
        Assert.Equal(
            typeof(WindowSeat),
            typeof(PaddedWindowSeat).GetMethod(nameof(IAsyncDisposable.DisposeAsync))!.DeclaringType);
    }

    [Fact]
    public async Task BaseOwner_SyncDisposeThroughTheDerivedType_CancelsTheInheritedScope()
    {
        // The shape that compiles today and is silently wrong: the subclass's `override Dispose()`
        // drops the scope block, so a call started through a PaddedWindowSeat is never cancelled.
        // `doze` is thirty seconds precisely so the dispose lands while it is still in flight:
        // `settle` and `watchers` finish too fast to tell a cancelled scope from a completed one.
        // Oreo is yanked off the window seat, and the Task has to notice.
        var perch = new PaddedWindowSeat(12);
        Task<string> dozing = perch.DozeAsync(30);
        await Task.Delay(50);
        perch.Dispose();
        await Assert.ThrowsAsync<TaskCanceledException>(() => dozing);
    }

    [Fact]
    public async Task BaseOwner_CollectsTheBasesFlowThroughTheSubclass()
    {
        // The Flow twin of the shape: the enumeration is started through the subclass and has to
        // reach the owner's scope. This is the one hierarchy shape that compiles today, so this cell
        // is green now and its leak row (LiveHandleTests row 13) is the one that is red.
        await using var perch = new PaddedWindowSeat(3);
        var watchers = new List<string>();
        await foreach (string watcher in perch.Watchers()) watchers.Add(watcher);
        Assert.Equal(new[] { "Oreo", "Mylo" }, watchers);
    }

    [Fact]
    public async Task BaseOwner_DisposeAsync_DrainsAnInFlightCallStartedThroughTheSubclass()
    {
        // The drain half: DisposeAsync inherited from WindowSeat lets Mylo finish settling, unlike
        // Dispose above which cancels him.
        var perch = new PaddedWindowSeat(6);
        Task<string> settling = perch.SettleAsync("Mylo");
        await perch.DisposeAsync();
        Assert.Equal("Mylo settled at 6", await settling);
    }

    // --- Both levels declare async members, and one of them is an override. ---------------------

    [Fact]
    public async Task BothLevelsAsync_OneScope_DrainedThroughTheBaseReference()
    {
        // Mylo's feeder: base and subclass both suspend, one scope, drained via the base type.
        Feeder feeder = new TimedFeeder(2, 7);
        Task<string> fill = feeder.FillAsync();
        Task<int> plan = ((TimedFeeder)feeder).ScheduleAsync();
        Assert.Equal("2 bowls filled at 7", await fill);
        Assert.Equal(7, await plan);
        await feeder.DisposeAsync();
    }

    [Fact]
    public async Task OverriddenSuspendMember_DispatchesToTheOverride_ThroughABaseReference()
    {
        // The override is not re-projected: `FillAsync` is declared once, on Feeder, and Kotlin's
        // own dynamic dispatch reaches TimedFeeder.fill. The return value is the proof, and
        // `DeclaringType` is the proof that there is only one of it.
        await using Feeder plain = new Feeder(3);
        Assert.Equal("3 bowls filled", await plain.FillAsync());

        await using Feeder timed = new TimedFeeder(3, 18);
        Assert.Equal("3 bowls filled at 18", await timed.FillAsync());
        Assert.Equal(
            typeof(Feeder),
            typeof(TimedFeeder).GetMethod(nameof(Feeder.FillAsync))!.DeclaringType);
    }

    // --- An abstract owner with two concrete subclasses. ---------------------------------------

    [Fact]
    public async Task AbstractOwner_ConcreteSubclass_AwaitsAndDrainsThroughAwaitUsing()
    {
        // The abstract class owns the scope and declares `abstract ValueTask DisposeAsync()`; the
        // concrete class carries the body with its own Native_Dispose. Oreo gets brushed.
        await using var brush = new MittBrusher();
        Assert.Equal("Oreo groomed with a brush", await brush.GroomAsync("Oreo"));
    }

    [Fact]
    public async Task AbstractOwner_SecondConcreteSubclass_AwaitsThroughTheAbstractReference()
    {
        // Two subclasses, so the per-concrete-class override is not a single-instance accident, and
        // the call goes through the abstract type. Mylo gets combed.
        Brusher groomer = new CombBrusher();
        Assert.Equal("Mylo groomed with a comb", await groomer.GroomAsync("Mylo"));
        await ((IAsyncDisposable)groomer).DisposeAsync();
    }

    [Fact]
    public void AbstractOwner_DeclaresDisposeAsyncAbstractly()
    {
        // The rule: DisposeAsync follows Dispose's spelling. Abstract on the owner, override on
        // each concrete class, which is what makes the interface satisfiable at all (CS0535 today).
        System.Reflection.MethodInfo onOwner =
            typeof(Brusher).GetMethod(nameof(IAsyncDisposable.DisposeAsync))!;
        Assert.True(onOwner.IsAbstract);
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Brusher)));

        System.Reflection.MethodInfo onConcrete =
            typeof(MittBrusher).GetMethod(nameof(IAsyncDisposable.DisposeAsync))!;
        Assert.Equal(typeof(MittBrusher), onConcrete.DeclaringType);
        Assert.False(onConcrete.IsAbstract);
    }

    // --- The negative control: every async member refused. -------------------------------------

    [Fact]
    public void RefusedOnlyAsyncMember_GetsNoScope()
    {
        // `NapRegistry.pair` takes a Pair and is refused, so nothing on this class uses a scope. A raw
        // scan of the declarations hands it IAsyncDisposable and a DisposeAsync that drains a scope
        // no call ever creates.
        Assert.False(typeof(IAsyncDisposable).IsAssignableFrom(typeof(NapRegistry)));
        Assert.Null(typeof(NapRegistry).GetMethod("PairAsync"));

        using var registry = new NapRegistry();
        Assert.Equal(3, registry.Cushions());
    }
}

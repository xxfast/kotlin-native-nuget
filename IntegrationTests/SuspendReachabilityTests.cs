using TestLibrary.Dev.Other.Bysuspend;
using TestLibrary.Errand;

namespace IntegrationTests;

/// <summary>
/// ROADMAP Phase 4 line 23 (research memo <c>docs/research/roadmap/top-level-suspend-reachability.md</c>):
/// a dependency type reachable only through a top-level <c>suspend fun</c> never reaches ADR-066's
/// reachability closure, so it is neither admitted nor refused, and the legacy suspend route spells
/// it anyway. Separately, a value class at a suspend return (module-local or dependency) is
/// completed as a handle class, <c>new T(resultPtr, out _)</c>, which does not compile against
/// the <c>readonly record struct</c> the value class is declared as.
///
/// The fixture is the real klib dependency <c>test-models/.../dev/other/bysuspend/Mousetoy.kt</c>
/// (admitted by <c>admit("dev.other.bysuspend")</c>), reached from
/// <c>test-library/.../test/errand/Errands.kt</c> (top-level) and <c>ErrandRunner.kt</c>
/// (class-level). The dependency types render under <c>TestLibrary.Dev.Other.Bysuspend</c> and the
/// suspend owners under <c>TestLibrary.Errand</c>, so a bare simple-name spelling of a dependency
/// type on the suspend route cannot resolve by accident.
///
/// Oreo fetches the mouse. Mylo audits the fetching.
/// </summary>
public class SuspendReachabilityTests
{
    private const string DepNs = "TestLibrary.Dev.Other.Bysuspend";

    // ---- dependency class, reached only through top-level suspend --------------------------

    /// <summary>
    /// Top-level suspend RETURN of a dependency class: the closure must admit it, so it is a
    /// declared, disposable handle class in its own namespace and its members work.
    /// </summary>
    [Fact]
    public async Task TopLevelSuspend_ReturnsAdmittedDependencyClass_AsAWorkingHandle()
    {
        using Mousetoy oreos = await Errands.FetchMousetoyAsync("Oreo");
        using Mousetoy mylos = await Errands.FetchMousetoyAsync("Mylo");

        Assert.Equal("black-and-white", oreos.Squeak);
        Assert.Equal("milky-brown", mylos.Squeak);
        Assert.Equal("Oreo bats the black-and-white mouse under the sofa", oreos.Batted("Oreo"));

        Assert.Equal(DepNs, typeof(Mousetoy).Namespace);
        Assert.True(typeof(IDisposable).IsAssignableFrom(typeof(Mousetoy)));
    }

    /// <summary>
    /// Top-level suspend PARAMETER of a dependency class: constructed from C#, the handle travels
    /// back into Kotlin. Today this member is a <c>SKIPPED_UNSUPPORTED_INPUT</c> because the type
    /// was never admitted.
    /// </summary>
    [Fact]
    public async Task TopLevelSuspend_TakesAdmittedDependencyClass_AsAParameter()
    {
        using var toy = new Mousetoy("catnip-stuffed");

        Assert.Equal(
            "Mylo bats the catnip-stuffed mouse under the sofa",
            await Errands.SqueakOfAsync(toy));
    }

    /// <summary>
    /// The handle a suspend return mints and the handle C# constructs are the same kind of thing:
    /// one fetched from Kotlin goes straight back in as a parameter.
    /// </summary>
    [Fact]
    public async Task TopLevelSuspend_DependencyHandleReturned_RoundTripsBackAsAParameter()
    {
        using Mousetoy toy = await Errands.FetchMousetoyAsync("Oreo");

        Assert.Equal(
            "Mylo bats the black-and-white mouse under the sofa",
            await Errands.SqueakOfAsync(toy));
    }

    // ---- dependency value class, reached only through top-level suspend --------------------

    /// <summary>
    /// Top-level suspend return of a dependency <b>value class</b> (the klib <c>Modifier.INLINE</c>
    /// bucket): admitted, declared as a value type, and completed by unboxing rather than by a
    /// handle constructor the struct does not have.
    /// </summary>
    [Fact]
    public async Task TopLevelSuspend_ReturnsAdmittedDependencyValueClass_ByValue()
    {
        Chipcode chip = await Errands.ScanChipAsync("Mylo");

        Assert.Equal(new Chipcode("mylo-985112"), chip);
        Assert.Equal("mylo-985112", chip.Digits);
        Assert.Equal(DepNs, typeof(Chipcode).Namespace);
        Assert.True(typeof(Chipcode).IsValueType);
    }

    // ---- module-local value class at a suspend return ---------------------------------------

    /// <summary>Top-level suspend, non-null module-local value class.</summary>
    [Fact]
    public async Task TopLevelSuspend_ReturnsModuleLocalValueClass()
    {
        Nametag tag = await Errands.FetchNametagAsync("Oreo");

        Assert.Equal(new Nametag("Oreo, if found please return to the sofa"), tag);
        Assert.Equal("Oreo, if found please return to the sofa", tag.Label);
        Assert.True(typeof(Nametag).IsValueType);
    }

    /// <summary>
    /// Top-level suspend, NULLABLE module-local value class: a real <c>Nullable&lt;Nametag&gt;</c>
    /// with a value for Oreo and null for Mylo, so a missing null guard cannot pass.
    /// </summary>
    [Fact]
    public async Task TopLevelSuspend_ReturnsNullableModuleLocalValueClass_ValueAndNull()
    {
        Nametag? oreos = await Errands.FindNametagAsync("Oreo");
        Nametag? mylos = await Errands.FindNametagAsync("Mylo");

        Assert.Equal(new Nametag("Oreo - black with a white middle"), oreos);
        Assert.Null(mylos);
    }

    /// <summary>Class-level suspend, non-null module-local value class.</summary>
    [Fact]
    public async Task ClassLevelSuspend_ReturnsModuleLocalValueClass()
    {
        using var runner = new ErrandRunner("Mylo");

        Nametag tag = await runner.CollectNametagAsync();

        Assert.Equal(new Nametag("Mylo's tag, collected from the engraver"), tag);
    }

    /// <summary>Class-level suspend, NULLABLE module-local value class: Oreo has a spare, Mylo does not.</summary>
    [Fact]
    public async Task ClassLevelSuspend_ReturnsNullableModuleLocalValueClass_ValueAndNull()
    {
        using var oreo = new ErrandRunner("Oreo");
        using var mylo = new ErrandRunner("Mylo");

        Assert.Equal(new Nametag("Oreo's spare tag"), await oreo.LookUpNametagAsync());
        Assert.Null(await mylo.LookUpNametagAsync());
    }

    /// <summary>
    /// The value-class suspend completion consumes the boxed handle exactly once. Awaiting many in
    /// a row would surface a double dispose or a leaked box as a crash or a wrong value long
    /// before a leak counter would.
    /// </summary>
    [Fact]
    public async Task ValueClassSuspendReturns_AwaitedRepeatedly_StayCorrect()
    {
        using var oreo = new ErrandRunner("Oreo");

        for (int i = 0; i < 50; i++)
        {
            Assert.Equal(new Nametag("Oreo's spare tag"), await oreo.LookUpNametagAsync());
            Assert.Equal(new Chipcode("oreo-985112"), await Errands.ScanChipAsync("Oreo"));
        }
    }

    // ---- dependency class as a Flow element on a class --------------------------------------

    /// <summary>
    /// An admitted dependency class as a <c>Flow</c> element on a class: each element is its own
    /// handle, disposed as it is read.
    /// </summary>
    [Fact]
    public async Task ClassLevelFlow_OfAdmittedDependencyClass_StreamsWorkingHandles()
    {
        using var runner = new ErrandRunner("Oreo");
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));

        var colours = new List<string>();
        await foreach (Yarnball ball in runner.Yarn().WithCancellation(cts.Token))
        {
            using (ball)
            {
                colours.Add(ball.Colour);
            }
        }

        Assert.Equal(new[] { "black-and-white", "milky-brown" }, colours);
        Assert.Equal(DepNs, typeof(Yarnball).Namespace);
    }

    // ---- dependency interface as a Flow element on an admitted dependency class --------------

    /// <summary>
    /// Issue #487: a bare dependency interface reached ONLY as the element of a <c>Flow</c> result
    /// on a dependency class that is itself admitted through the closure
    /// (<c>ErrandRunner.lookout(): Spotter</c>). The element walk alone has to give
    /// <c>Sighting</c> its backing class, or the element read constructs a type that is never
    /// declared (CS0234). Kotlin-backed elements materialise as that backing class, typed as the
    /// interface, and the nullable <c>Name</c> crosses both ways round, null included.
    /// </summary>
    [Fact]
    public async Task ClassLevelFlow_OfInterfaceReachedOnlyAsTheElement_StreamsBackingWrappers()
    {
        using var runner = new ErrandRunner("Oreo");
        using Spotter spotter = runner.Lookout();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));

        // No C#-implemented identity check here: that needs an input position, which would make
        // `Sighting` reachable another way and defeat the fixture.
        var names = new List<string?>();
        await foreach (ISighting sighting in spotter.Watch().WithCancellation(cts.Token))
        {
            using (sighting)
            {
                Assert.IsType<Sighting>(sighting);
                names.Add(sighting.Name);
            }
        }

        Assert.Equal(
            new string?[]
            {
                "Oreo saw Oreo on the fence, black with a white middle",
                "Oreo saw Mylo under the hedge, milky-brown",
                null,
            },
            names);
        Assert.Equal(DepNs, typeof(ISighting).Namespace);
        Assert.Equal(DepNs, typeof(Sighting).Namespace);
        Assert.Equal(DepNs, typeof(Spotter).Namespace);
        Assert.True(typeof(ISighting).IsInterface);

        // The declared element type is the interface, not the wrapper: an `ISighting` local accepts
        // either spelling, so only reflection tells the two apart.
        Type element = typeof(Spotter).GetMethod("Watch")!.ReturnType.GetGenericArguments().Single();
        Assert.Equal(typeof(ISighting), element);
    }

    // ---- enum at a suspend return ------------------------------------------------------------

    /// <summary>
    /// An enum at a nullable top-level suspend return crosses by ordinal and is awaited as the enum
    /// itself (never an <c>int</c>): both named values and the null arm.
    /// </summary>
    [Fact]
    public async Task TopLevelSuspend_ReturnsNullableEnum_AsTheEnum()
    {
        Chore? oreos = await Errands.ChoreForAsync("Oreo");
        Chore? mylos = await Errands.ChoreForAsync("Mylo");
        Chore? strays = await Errands.ChoreForAsync("Stray");

        Assert.Equal(Chore.Fetch, oreos);
        Assert.Equal(Chore.Nap, mylos);
        Assert.Null(strays);
        Assert.True(typeof(Chore).IsEnum);
    }
}

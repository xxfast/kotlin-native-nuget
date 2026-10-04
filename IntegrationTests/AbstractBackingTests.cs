using TestLibrary.Torpor;

namespace IntegrationTests;

/// <summary>
/// C# never constructs an abstract class: a Kotlin handle that materialises as one (an abstract
/// sealed arm, or an ordinary abstract class) constructs the class's internal backing wrapper,
/// whose overrides call through exports Kotlin dispatches virtually. So the value comes back typed
/// as the abstract class, and every member answers as the Kotlin subclass behind it.
/// </summary>
public class AbstractBackingTests
{
    [Fact]
    public void AbstractArm_IsAbstractNotSealed()
    {
        Assert.True(typeof(Torpor.Dormant).IsAbstract);
        Assert.False(typeof(Torpor.Dormant).IsSealed);
        Assert.True(typeof(DeepTorpor).IsSubclassOf(typeof(Torpor.Dormant)));
    }

    [Fact]
    public void SubclassOfAbstractArm_ReturnedAsBase_IsTheArm()
    {
        using var den = new Den();
        using Torpor torpor = den.Deepest(3);

        Torpor.Dormant deep = Assert.IsAssignableFrom<Torpor.Dormant>(torpor);
        // Materialised as the arm's wrapper, not as the exported subclass.
        Assert.IsNotType<DeepTorpor>(torpor);
        Assert.Equal(3, deep.Depth());
        Assert.Equal("dreaming", deep.Mood);
        Assert.Equal("deeper", deep.Label());
        Assert.Equal(30, deep.Minutes());
    }

    [Fact]
    public void SubclassOfAbstractArm_ReturnedAsArm_CallsThrough()
    {
        using var den = new Den();
        using Torpor.Dormant deep = den.Deep(4);

        Assert.Equal(4, deep.Depth());
        Assert.Equal("deeper", deep.Label());
        Assert.Equal(40, deep.Minutes());
    }

    [Fact]
    public void ConstructedSubclass_OverridesTheArmsAbstractMembers()
    {
        using var torpor = new DeepTorpor(5);
        Torpor.Dormant deep = torpor;

        Assert.Equal(5, deep.Depth());
        Assert.Equal("dreaming", deep.Mood);
        Assert.Equal("deeper", deep.Label());
    }

    [Fact]
    public void AbstractArmWrapper_TryTwin_ReportsSuccess()
    {
        using var den = new Den();
        using Torpor torpor = den.Deepest(6);
        Torpor.Dormant deep = Assert.IsAssignableFrom<Torpor.Dormant>(torpor);

        Assert.True(deep.TryWeigh(out int value, out Exception? failure));
        Assert.Equal(6, value);
        Assert.Null(failure);
    }

    [Fact]
    public void AbstractArmWrapper_TryTwin_ReportsFailureWithoutThrowing()
    {
        using var den = new Den();
        using Torpor.Dormant deep = den.Deep(0);

        Assert.False(deep.TryWeigh(out _, out Exception? failure));
        Assert.NotNull(failure);
        Assert.Contains("awake", failure.Message);
    }

    [Fact]
    public void AbstractClassWrapper_TryTwin_ReportsSuccess()
    {
        using var den = new Den();
        using Hibernator hibernator = den.Hibernator();

        Assert.True(hibernator.TryWeigh(out int value, out Exception? failure));
        Assert.Equal(4, value);
        Assert.Null(failure);
    }

    [Fact]
    public void FinalArm_StillDiscriminates()
    {
        using var den = new Den();
        using Torpor torpor = den.Brief(7);

        Torpor.Brief brief = Assert.IsType<Torpor.Brief>(torpor);
        Assert.Equal(7, brief.Minutes);
    }

    [Fact]
    public void AbstractArmWrapper_DisposedTwice_DoesNotThrow()
    {
        using var den = new Den();
        Torpor.Dormant deep = den.Deep(2);

        Assert.Equal(2, deep.Depth());
        // The wrapper inherits the arm's idempotent `Dispose`; LeakTests proves the release.
        Assert.Null(Record.Exception(() => { deep.Dispose(); deep.Dispose(); }));
    }

    [Fact]
    public void AbstractClass_ReturnedAsItself_CallsThrough()
    {
        using var den = new Den();
        using Hibernator hibernator = den.Hibernator();

        Assert.True(typeof(Hibernator).IsAbstract);
        Assert.Equal("Oreo", hibernator.Name);
        Assert.Equal(3, hibernator.Snores());
        Assert.Equal("Oreo snores 3 times", hibernator.Describe());
    }

    [Fact]
    public void AbstractClass_AsListElement_Materialises()
    {
        using var den = new Den();
        IReadOnlyList<Hibernator> hibernators = den.Hibernators;

        Assert.Equal(2, hibernators.Count);
        foreach (Hibernator hibernator in hibernators)
        {
            using (hibernator)
            {
                Assert.Equal(3, hibernator.Snores());
            }
        }
    }

    [Fact]
    public void AbstractClassWrapper_DisposedTwice_DoesNotThrow()
    {
        using var den = new Den();
        Hibernator hibernator = den.Hibernator();

        Assert.Equal(3, hibernator.Snores());
        // The wrapper implements the abstract `Dispose`; LeakTests proves the release.
        Assert.Null(Record.Exception(() => { hibernator.Dispose(); hibernator.Dispose(); }));
    }

    [Fact]
    public void AbstractClassBelowAbstractClass_ReturnedAsItself_CallsThrough()
    {
        using var den = new Den();
        using Napper napper = den.Napper();

        Assert.True(typeof(Napper).IsAbstract);
        Assert.IsAssignableFrom<Hibernator>(napper);
        // `Name` and `Weigh` are left open by `Hibernator` and answered over its exports.
        Assert.Equal("Mylo", napper.Name);
        Assert.Equal(1, napper.Snores());
        Assert.Equal(2, napper.Dreams());
        Assert.Equal("Mylo snores 1 times", napper.Describe());
        Assert.Equal(6, napper.Weigh());
        Assert.True(napper.TryWeigh(out int value, out Exception? failure));
        Assert.Equal(6, value);
        Assert.Null(failure);
    }

    [Fact]
    public void OverriddenLambdaProperty_ReadThroughTheBase_DispatchesToTheOverride()
    {
        using var den = new Den();
        using Napper napper = den.Napper();
        using Hibernator oreo = den.Hibernator();

        // One `OnWake`, declared on `Hibernator`; Kotlin's dispatch picks each subclass's lambda.
        using var stretch = napper.OnWake;
        using var wake = oreo.OnWake;
        Assert.Equal("Mylo stretches", stretch.Invoke("Mylo"));
        Assert.Equal("Oreo wakes", wake.Invoke("Oreo"));
    }

    [Fact]
    public void SubclassMemberNamedLikeTheWrapper_Binds()
    {
        using var mylo = new MyloNapper();

        Assert.Equal(5, mylo.Backing());
        Assert.Equal(2, mylo.Dreams());
    }

    [Fact]
    public async Task AbstractFlowMember_CollectsThroughTheWrapper()
    {
        using var den = new Den();
        await using Napper napper = den.Napper();

        var breaths = new List<int>();
        await foreach (int breath in napper.Breaths()) breaths.Add(breath);

        Assert.Equal(new[] { 4, 5, 6 }, breaths);
    }

    [Fact]
    public void AbstractClassBelowAbstractArm_ReturnedAsItself_CallsThrough()
    {
        using var den = new Den();
        using Slumber slumber = den.Slumber();

        Assert.True(typeof(Slumber).IsAbstract);
        Torpor.Dormant dormant = Assert.IsAssignableFrom<Torpor.Dormant>(slumber);
        // The arm's abstract members, answered over the arm's exports.
        Assert.Equal(7, dormant.Depth());
        Assert.Equal("sighing", dormant.Mood);
        Assert.Equal(70, dormant.Minutes());
        Assert.Equal("deep", dormant.Label());
        Assert.Equal(2, slumber.Sighs());
    }
}

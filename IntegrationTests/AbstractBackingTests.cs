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
}

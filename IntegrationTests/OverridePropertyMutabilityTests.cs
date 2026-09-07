using System.Reflection;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// A Kotlin class may widen an inherited read-only property to `var`. C# has no such freedom
/// once the base member is projected get-only, so the two shapes land differently.
///
/// Case A: the read-only declaration is on an interface (`Counter.count`). `Clicker` renders
/// `virtual`, not `override`, and C# is happy to let it add a setter.
///
/// Case B: the read-only declaration is on an exported base class (`Animal.vibe`, itself an
/// `override val` of `Pet.vibe`, projected as a get-only `public virtual string Vibe`). Oreo's
/// `override var vibe` cannot become `public override string Vibe { get; set; }` against that
/// base, so the derived setter is dropped from the projection and C# sees a get-only override.
/// </summary>
public class OverridePropertyMutabilityTests
{
    [Fact]
    public void Clicker_OverridesInterfaceValWithVar_ExposesSetter()
    {
        using var clicker = new Clicker();

        Assert.Equal(0, clicker.Count);

        // Oreo demands five treats before breakfast.
        clicker.Count = 5;

        Assert.Equal(5, clicker.Count);
        Assert.Equal(5, ((ICounter)clicker).Count);
    }

    [Fact]
    public void Cat_OverridesBaseValWithVar_ReadsDerivedValueThroughBothStaticTypes()
    {
        using var mylo = new Cat("Mylo", 9);

        Assert.Equal("curious", mylo.Vibe);
        Assert.Equal("curious", ((Animal)mylo).Vibe);
    }

    [Fact]
    public void Cat_OverridesBaseValWithVar_DoesNotExposeDerivedSetter()
    {
        PropertyInfo? vibe = typeof(Cat).GetProperty("Vibe");

        Assert.NotNull(vibe);
        Assert.NotNull(vibe!.GetMethod);
        Assert.Null(vibe.SetMethod);
    }

    [Fact]
    public void Animal_Vibe_StaysGetOnlyOnTheBase()
    {
        PropertyInfo? vibe = typeof(Animal).GetProperty("Vibe");

        Assert.NotNull(vibe);
        Assert.Null(vibe!.SetMethod);
    }

    [Fact]
    public void StrayPet_Vibe_ReachesInterfaceDispatch()
    {
        using IPet stray = PetKt.StrayPet();

        Assert.Equal("aloof", stray.Vibe);
    }
}

using System.Reflection;
using TestLibrary.Roost;

namespace IntegrationTests;

/// <summary>
/// ADR-009 amendment: an `open` arm of a sealed class renders `public class` (not `sealed`), its
/// `open` members render `virtual`, and a Kotlin subclass of the arm takes the ordinary class
/// route with the arm spelled by its nested C# name (`Roost.Perch`).
///
/// The cats' roosting spots: `Roost.Perch` is `open` (Mylo builds a taller one on top of it),
/// `Roost.Ground` is a `data object` and stays final. `HighPerch` extends the arm and overrides
/// both `height` and `describe()`.
///
/// The compile is the real proof (a `sealed` arm is CS0509 for the subclass and CS0549 for any
/// `virtual` member; a `simpleName` base spelling is CS0246). The reflection facts make the rule
/// visible, and the dispatch rows show a `Roost.Perch`-typed reference reaching the override.
///
/// `RoostKt.Roost()` and `RoostKt.HighRoost()` put an arm at a sealed return position, so
/// `Roost.FromHandle` and the arm's own `Height`/`Describe` bodies are exercised too. The flat
/// discriminator is over direct arms only, so a `HighPerch` handle comes back as a `Roost.Perch`
/// wrapper while Kotlin dispatch still reaches the override.
/// </summary>
public class OpenSealedArmTests
{
    [Fact]
    public void OpenArm_IsNotSealed()
    {
        Assert.False(typeof(Roost.Perch).IsSealed);
    }

    [Fact]
    public void FinalArm_StaysSealed()
    {
        Assert.True(typeof(Roost.Ground).IsSealed);
    }

    [Fact]
    public void OpenArm_OpenVal_RendersVirtual()
    {
        PropertyInfo? height = typeof(Roost.Perch).GetProperty("Height");

        Assert.NotNull(height);
        Assert.NotNull(height!.GetMethod);
        Assert.True(height.GetMethod!.IsVirtual);
    }

    [Fact]
    public void OpenArm_OpenFun_RendersVirtual()
    {
        MethodInfo? describe = typeof(Roost.Perch).GetMethod("Describe");

        Assert.NotNull(describe);
        Assert.True(describe!.IsVirtual);
    }

    [Fact]
    public void Subclass_ExtendsTheArmByItsNestedName()
    {
        Assert.Equal(typeof(Roost.Perch), typeof(HighPerch).BaseType);
    }

    [Fact]
    public void Subclass_Describe_OverridesRatherThanHides()
    {
        MethodInfo describe = typeof(HighPerch).GetMethod("Describe")!;

        Assert.Equal(typeof(Roost.Perch), describe.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Subclass_Height_OverridesRatherThanHides()
    {
        MethodInfo height = typeof(HighPerch).GetProperty("Height")!.GetMethod!;

        Assert.Equal(typeof(Roost.Perch), height.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Subclass_ReadsItsOwnOverrides()
    {
        using var high = new HighPerch();

        Assert.Equal("high", high.Describe());
        Assert.Equal(99, high.Height);
    }

    [Fact]
    public void Subclass_DispatchesThroughAnArmTypedReference()
    {
        using var high = new HighPerch();

        // Oreo looks up at the perch and only sees a `Roost.Perch`; Mylo is still on the high one.
        Roost.Perch perch = high;

        Assert.Equal("high", perch.Describe());
        Assert.Equal(99, perch.Height);
    }

    [Fact]
    public void Roost_YieldsTheArmWrapperItself()
    {
        using Roost roost = RoostKt.Roost();

        // The discriminator says arm 1, so the wrapper is exactly the arm, not the base.
        Assert.IsType<Roost.Perch>(roost);
    }

    [Fact]
    public void Roost_ReadsTheArmsOwnMembers()
    {
        using Roost roost = RoostKt.Roost();

        Roost.Perch perch = Assert.IsType<Roost.Perch>(roost);

        // Oreo's usual perch, three shelves up.
        Assert.Equal(3, perch.Height);
        Assert.Equal("perch 3", perch.Describe());
    }

    [Fact]
    public void HighRoost_ReconstructsAsTheArm_NotTheFurtherSubclass()
    {
        using Roost roost = RoostKt.HighRoost();

        // ADR-009's discriminator is flat, over direct arms only, so a `HighPerch` handle comes
        // back wrapped as a `Roost.Perch`. Exact type, not assignable-from.
        Assert.IsType<Roost.Perch>(roost);
        Assert.False(roost is HighPerch);
    }

    [Fact]
    public void HighRoost_DispatchesThroughKotlinToTheOverride()
    {
        using Roost roost = RoostKt.HighRoost();

        Roost.Perch perch = Assert.IsType<Roost.Perch>(roost);

        // The C# static type is the arm, but the handle is Mylo's high perch and Kotlin dispatch
        // is still virtual, so the arm's shim reaches the override.
        Assert.Equal("high", perch.Describe());
        Assert.Equal(99, perch.Height);
    }
}

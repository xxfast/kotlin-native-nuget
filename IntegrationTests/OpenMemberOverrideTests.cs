using System.Reflection;
using TestLibrary.Bed;

namespace IntegrationTests;

/// <summary>
/// ADR-101 / ADR-075: a Kotlin base class's own `open val` / `open var` must render `virtual`
/// in C#, so a subclass's `override` compiles instead of failing CS0506.
///
/// `Bed` declares `open val softness`, `open var occupant` and a final `val brand`. `Hammock`
/// overrides the two open ones. Existing fixtures only ever reach `virtual` through the
/// `override && !final` arm (`Animal.vibe` overriding `Pet.vibe`), so a *declared* `open`
/// member has no coverage anywhere.
///
/// The compile is the real proof; the reflection facts make the rule visible. `IsVirtual` is
/// also true for an `override` accessor, so the virtual/final split is asserted on `Bed`, and
/// `Hammock` is pinned by `GetBaseDefinition()` (an override resolves back to `Bed`; a `new`
/// member would resolve to itself).
/// </summary>
public class OpenMemberOverrideTests
{
    [Fact]
    public void Bed_ReadsItsOwnDefaults()
    {
        using var bed = new Bed();

        Assert.Equal(1, bed.Softness);
        Assert.Equal("Oreo", bed.Occupant);
        Assert.Equal("Catnap", bed.Brand);
    }

    [Fact]
    public void Hammock_OverridesOpenVal_ReadsDerivedValueThroughBothStaticTypes()
    {
        using var hammock = new Hammock();

        Assert.Equal(9, hammock.Softness);
        Assert.Equal(9, ((Bed)hammock).Softness);
    }

    [Fact]
    public void Hammock_OverridesOpenVar_KeepsItsSetter()
    {
        using var hammock = new Hammock();

        Assert.Equal("Mylo", hammock.Occupant);

        // Oreo shoves his brother off the hammock, through the base static type.
        ((Bed)hammock).Occupant = "Oreo";

        Assert.Equal("Oreo", hammock.Occupant);
        Assert.Equal("Oreo", ((Bed)hammock).Occupant);
    }

    [Fact]
    public void Hammock_InheritsFinalBrandFromTheBase()
    {
        using var hammock = new Hammock();

        Assert.Equal("Catnap", hammock.Brand);
    }

    [Fact]
    public void Hammock_Describe_DispatchesThroughTheOverriddenProperties()
    {
        using var hammock = new Hammock();

        // `describe()` is declared on Bed and is not open, so this is Kotlin's own dynamic
        // dispatch through the overrides, observed from the base's method.
        Assert.Equal("Catnap, softness 9, occupied by Mylo", hammock.Describe());
        Assert.Equal("Catnap, softness 9, occupied by Mylo", ((Bed)hammock).Describe());

        ((Bed)hammock).Occupant = "Oreo";

        Assert.Equal("Catnap, softness 9, occupied by Oreo", hammock.Describe());
    }

    [Fact]
    public void Bed_Describe_ReadsTheBasesOwnProperties()
    {
        using var bed = new Bed();

        Assert.Equal("Catnap, softness 1, occupied by Oreo", bed.Describe());
    }

    [Fact]
    public void Bed_OpenVal_RendersVirtual()
    {
        PropertyInfo? softness = typeof(Bed).GetProperty("Softness");

        Assert.NotNull(softness);
        Assert.NotNull(softness!.GetMethod);
        Assert.True(softness.GetMethod!.IsVirtual);
    }

    [Fact]
    public void Bed_OpenVar_RendersVirtualOnBothAccessors()
    {
        PropertyInfo? occupant = typeof(Bed).GetProperty("Occupant");

        Assert.NotNull(occupant);
        Assert.NotNull(occupant!.GetMethod);
        Assert.NotNull(occupant.SetMethod);
        Assert.True(occupant.GetMethod!.IsVirtual);
        Assert.True(occupant.SetMethod!.IsVirtual);
    }

    [Fact]
    public void Bed_FinalVal_StaysNonVirtual()
    {
        PropertyInfo? brand = typeof(Bed).GetProperty("Brand");

        Assert.NotNull(brand);
        Assert.NotNull(brand!.GetMethod);
        Assert.False(brand.GetMethod!.IsVirtual);
    }

    [Fact]
    public void Hammock_Overrides_RatherThanHidesTheBaseMembers()
    {
        MethodInfo softness = typeof(Hammock).GetProperty("Softness")!.GetMethod!;
        MethodInfo occupant = typeof(Hammock).GetProperty("Occupant")!.SetMethod!;

        Assert.Equal(typeof(Bed), softness.GetBaseDefinition().DeclaringType);
        Assert.Equal(typeof(Bed), occupant.GetBaseDefinition().DeclaringType);
    }
}

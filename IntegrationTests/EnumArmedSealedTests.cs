using System;
using System.Linq;
using System.Reflection;
using TestLibrary.Issue236;

namespace IntegrationTests;

/// <summary>
/// Issue #236 / ADR-157: a Kotlin <c>sealed interface</c> whose arms are <c>enum class</c>es is
/// refused today, because a C# enum admits only an integral base (<c>CS1008</c>), so
/// <c>enum Patch : Marking</c> has no shape. The interface then classifies as a bare protocol with
/// no discriminator and every member typed with it is dropped with <c>SKIPPED_SEALED_POSITION</c>,
/// which takes the holder's constructor and <c>copy</c> with it. This file cannot compile until the
/// feature ships, which is the red signal.
/// <para>
/// After the fix each enum arm binds as a boxed arm, <c>{Enum}Arm</c>, an ordinary sealed arm whose
/// handle is a <c>StableRef</c> to the enum entry, with a public constructor from the C# enum and a
/// <c>Value</c> getter back to it. The enum keeps being declared exactly once as a C# <c>enum</c>
/// (ADR-006), so its own members stay reachable as extension methods: <c>swirl.Patch()</c>, never a
/// second spelling on the box. No <c>IMarking</c> and no <c>ISnack</c> may survive.
/// </para>
/// <para>
/// <c>Patch.Socks</c> and <c>Swirl.Cream</c> are both ordinal 1 on purpose. That is the issue's
/// second prohibition made testable: an implementation that maps the arm to the bare enum value
/// cannot tell them apart, and every discrimination assertion here fails.
/// </para>
/// <para>
/// The diagnostic half of requirement 6 (<c>SKIPPED_INELIGIBLE_SEALED_INTERFACE</c> asserted
/// absent) is not observable from C#; this repository asserts KSP warnings in the Tier 1 tests
/// (<c>Tier1SealedInterfaceTest.kt</c>). What is observable here is the consequence: the types
/// exist, the interface spelling is gone and the holder has its constructor back.
/// </para>
/// <para>
/// Oreo is black with the white bib in the middle; Mylo is cocoa on top and cream underneath.
/// </para>
/// </summary>
public class EnumArmedSealedTests
{
    /// <summary>
    /// The declaration shape. The base must be an abstract <em>class</em> with namespace-level
    /// <c>sealed</c> boxed arms, no <c>IMarking</c> may survive, and <c>Patch</c> must still be a
    /// C# <c>enum</c> declared exactly once: an implementation that turns the arm into a class
    /// instead of boxing it declares <c>Patch</c> twice and every consumer fails CS0101.
    /// </summary>
    [Fact]
    public void Marking_IsDeclaredAsAnAbstractClass_WithBoxedEnumArms()
    {
        Assembly assembly = typeof(Patch).Assembly;

        Assert.True(typeof(Marking).IsClass);
        Assert.True(typeof(Marking).IsAbstract);
        Assert.False(typeof(Marking).IsInterface);

        Assert.True(typeof(Patch).IsEnum);
        Assert.True(typeof(Swirl).IsEnum);
        Assert.Equal(1, assembly.GetTypes().Count(type => type.Name == "Patch"));
        Assert.Equal(1, assembly.GetTypes().Count(type => type.Name == "Swirl"));

        Assert.Equal(typeof(Marking), typeof(PatchArm).BaseType);
        Assert.Equal(typeof(Marking), typeof(SwirlArm).BaseType);
        Assert.True(typeof(PatchArm).IsSealed);
        Assert.True(typeof(SwirlArm).IsSealed);

        Assert.DoesNotContain(assembly.GetTypes(), type => type.Name == "IMarking");
        Assert.DoesNotContain(assembly.GetTypes(), type => type.Name == "ISnack");
    }

    /// <summary>
    /// Requirement 3: a Kotlin function returning the interface gives C# something it can switch on
    /// and recover the original enum value from. Kotlin answers with <c>Swirl.CREAM</c>, ordinal 1,
    /// which is also <c>Patch.Socks</c>'s ordinal, so only a surviving discriminator lands this on
    /// <c>SwirlArm</c>.
    /// </summary>
    [Fact]
    public void PaintedMarking_EnumArmAtATopLevelReturn_RecoversTheEnumValue()
    {
        using Marking painted = EnumArmedSealedSample.PaintedMarking();

        string text = painted switch
        {
            PatchArm { Value: Patch.Bib } => "bib",
            PatchArm patch => $"patch {patch.Value}",
            SwirlArm swirl => $"swirl {swirl.Value}",
            _ => throw new InvalidOperationException("unknown arm"),
        };

        Assert.Equal("swirl Cream", text);
        Assert.Equal(Swirl.Cream, Assert.IsType<SwirlArm>(painted).Value);
    }

    /// <summary>
    /// The issue's second prohibition, directly: two enum arms of one interface holding the same
    /// ordinal must still discriminate. <c>Patch.Socks</c> and <c>Swirl.Cream</c> are both 1, and
    /// the boxes built from them must be different arms.
    /// </summary>
    [Fact]
    public void OverlappingOrdinals_DiscriminateByArmNotByValue()
    {
        using var socks = new PatchArm(Patch.Socks);
        using var cream = new SwirlArm(Swirl.Cream);

        Assert.Equal(1, (int)Patch.Socks);
        Assert.Equal(1, (int)Swirl.Cream);

        Assert.IsType<PatchArm>(socks);
        Assert.IsType<SwirlArm>(cream);
        Assert.Equal(Patch.Socks, socks.Value);
        Assert.Equal(Swirl.Cream, cream.Value);
    }

    /// <summary>
    /// Requirement 2: the enum's own members stay reachable. <c>Swirl.patch</c> is the ADR-006
    /// extension method on the value the box carries, <c>arm.Value.Patch()</c>, not a projection
    /// onto the box. Mylo's cream sits over Oreo's socks.
    /// </summary>
    [Fact]
    public void SwirlArm_Value_ReachesTheEnumsOwnMember()
    {
        using var cream = new SwirlArm(Swirl.Cream);

        Assert.Equal(Patch.Socks, cream.Value.Patch());
        Assert.Equal(Patch.Bib, Swirl.Cocoa.Patch());
    }

    /// <summary>
    /// Requirement 4, both ADR-105 input positions in one round trip: the interface at a
    /// constructor parameter and at a property. While the interface is ineligible the holder loses
    /// both together, which is the cascade the issue counts.
    /// </summary>
    [Fact]
    public void Portrait_ConstructorAndProperty_RoundTripTheArm()
    {
        using var socks = new PatchArm(Patch.Socks);

        using var portrait = new Portrait(socks);

        using Marking carried = portrait.Marking;
        Assert.Equal(Patch.Socks, Assert.IsType<PatchArm>(carried).Value);
    }

    /// <summary>
    /// The other half of the cascade: <c>copy</c> is planned from the same primary-constructor
    /// parameters, so it is refused and recovered with the constructor. Oreo's portrait is repainted
    /// as Mylo's swirl.
    /// </summary>
    [Fact]
    public void Portrait_Copy_ReplacesTheArm()
    {
        using var socks = new PatchArm(Patch.Socks);
        using var portrait = new Portrait(socks);
        using var cream = new SwirlArm(Swirl.Cream);

        using var repainted = portrait.Copy(cream);

        using Marking carried = repainted.Marking;
        Assert.Equal(Swirl.Cream, Assert.IsType<SwirlArm>(carried).Value);
    }

    /// <summary>
    /// The parameter position at a top-level function. The handle crosses back and is unwrapped to
    /// the real Kotlin entry, and the return is a plain string built inside Kotlin, so the assertion
    /// reads the Kotlin side of the wire: a box that lost the arm or the value cannot answer this.
    /// </summary>
    [Fact]
    public void DescribeMarking_Parameter_UnwrapsToTheRealKotlinEntry()
    {
        using var socks = new PatchArm(Patch.Socks);
        using var cream = new SwirlArm(Swirl.Cream);

        Assert.Equal("patch SOCKS", EnumArmedSealedSample.DescribeMarking(socks));
        Assert.Equal("swirl CREAM over SOCKS", EnumArmedSealedSample.DescribeMarking(cream));
    }

    /// <summary>
    /// Requirement 5: a mixed hierarchy, one enum arm beside one <c>data class</c> arm, switches
    /// onto both. Index 0 is Oreo's biscuits (the boxed enum arm), anything else is Mylo's tuna
    /// pouch (the ordinary data arm with its payload).
    /// </summary>
    [Fact]
    public void SnackAt_MixedHierarchy_SwitchesOnBothArms()
    {
        using Snack crunchy = EnumArmedSealedSample.SnackAt(0);
        using Snack wet = EnumArmedSealedSample.SnackAt(1);

        Assert.Equal(Crunch.Biscuit, Assert.IsType<CrunchArm>(crunchy).Value);
        Assert.Equal("tuna", Assert.IsType<Pouch>(wet).Flavour);

        string text = wet switch
        {
            CrunchArm crunch => crunch.Value.ToString(),
            Pouch pouch => pouch.Flavour,
            _ => throw new InvalidOperationException("unknown arm"),
        };
        Assert.Equal("tuna", text);
    }

    /// <summary>
    /// Managed value equality over <c>Value</c>, so a box compares like the data arms beside it
    /// rather than by handle. Two boxes of one entry are two distinct handles over one Kotlin
    /// singleton, so identity equality would read false here; and the same ordinal on the other arm
    /// must not compare equal.
    /// </summary>
    [Fact]
    public void PatchArm_ValueEquality_ComparesOverValue()
    {
        using var socks = new PatchArm(Patch.Socks);
        using var alsoSocks = new PatchArm(Patch.Socks);
        using var bib = new PatchArm(Patch.Bib);
        using var cream = new SwirlArm(Swirl.Cream);

        Assert.Equal(socks, alsoSocks);
        Assert.Equal(socks.GetHashCode(), alsoSocks.GetHashCode());
        Assert.NotEqual(socks, bib);
        Assert.NotEqual<object>(socks, cream);
    }
}

using System;
using System.Linq;
using TestLibrary;
using TestLibrary.Issue54;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// ADR-133 flips shapes (a) and (b) of the undeclared-enum gate from absence to presence: an
/// <c>enum class</c> nested inside an exported class is now declared as the C# nested enum
/// <c>NestedModeOwner.Mode</c> / <c>Broadcast.AdBand</c>, so every member typed with one binds
/// instead of skipping.
///
/// The three shapes stay separate, because they reach the classifier's enum branch by three
/// different routes and only two of them flip:
/// <list type="bullet">
/// <item>(a) a module-local enum nested in an exported class: declared by the owner walk;</item>
/// <item>(b) a dependency module's nested enum whose owner the ADR-066 closure admits: declared
/// once, by the owner walk, never a second time by the dependency merge;</item>
/// <item>(c) a top-level enum in the never-admitted <c>dev.other.core</c>
/// (<c>Newsroom.airwave()</c>): unchanged by ADR-133 and still skipped named. Nesting has nothing
/// to do with why it is undeclarable, which is exactly why this cell stays red-adjacent here.</item>
/// </list>
///
/// Oreo is either ON or OFF. Mylo still lobbies for a third mode, and still loses.
/// </summary>
public class NestedEnumGateTests
{
    // --- Shape (a): module-local nested enum ---

    [Fact]
    public void NestedModeOwner_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        // The control half: an implementation that drops the owning class to make its nested enum
        // fit would also make this pass-looking test fail, which is the point.
        using var owner = new NestedModeOwner();

        Assert.Equal("owner", owner.Name);
    }

    [Fact]
    public void NestedModeOwner_PropertyPosition_Binds()
    {
        // Was: Assert.Null(GetProperty("Mode"), before the property was renamed to Setting to dodge CS0102).
        using var owner = new NestedModeOwner();

        Assert.Equal(NestedModeOwner.Mode.On, owner.Setting);
        owner.Setting = NestedModeOwner.Mode.Off;
        Assert.Equal(NestedModeOwner.Mode.Off, owner.Setting);
    }

    [Fact]
    public void NestedModeOwner_ParameterPosition_Binds()
    {
        // Was: Assert.Null(GetMethod("Set")). The ordinal wire is what actually crosses.
        using var owner = new NestedModeOwner();

        owner.Set(NestedModeOwner.Mode.Off);

        Assert.Equal(NestedModeOwner.Mode.Off, owner.Current());
    }

    [Fact]
    public void NestedModeOwner_ReturnPosition_Binds()
    {
        // Was: Assert.Null(GetMethod("Current")).
        using var owner = new NestedModeOwner();

        Assert.Equal(NestedModeOwner.Mode.On, owner.Current());
    }

    [Fact]
    public void NestedModeOwner_NestedEnum_IsDeclaredAsANestedEnum()
    {
        // Was: Assert.Null(GetNestedType("Mode")). This is the ADR-133 pin for the enum kind.
        Type? mode = typeof(NestedModeOwner).GetNestedType("Mode");

        Assert.NotNull(mode);
        Assert.True(mode!.IsEnum);
        Assert.Same(typeof(NestedModeOwner), mode.DeclaringType);
        Assert.Equal(0, (int)NestedModeOwner.Mode.On);
        Assert.Equal(1, (int)NestedModeOwner.Mode.Off);
    }

    // --- Shape (b): admitted dependency class, nested enum ---

    [Fact]
    public void Broadcast_AdmittedDependencyClass_StillConstructs_AndItsUnrelatedMemberStillBinds()
    {
        using var newsroom = new Newsroom();
        using Broadcast broadcast = newsroom.Broadcast();

        Assert.Equal("Radio Mylo 101.1", broadcast.Station);
    }

    [Fact]
    public void Broadcast_NestedDependencyEnumProperty_Binds()
    {
        // Was: Assert.Null(GetProperty("Band")) and Assert.Null(GetNestedType("AdBand")). The
        // closure admits a nested dependency declaration whose enclosing chain is admitted, and
        // Broadcast's own walk is the sole declarer of AdBand.
        using var newsroom = new Newsroom();
        using Broadcast broadcast = newsroom.Broadcast();

        Assert.NotNull(typeof(Broadcast).GetNestedType("AdBand"));
        Assert.Equal(Broadcast.AdBand.Fm, broadcast.Band);
    }

    // --- Shape (c): unadmitted top-level dependency enum, unchanged ---

    [Fact]
    public void Airwave_UnadmittedDependencyEnum_MemberIsSkipped_AndNewsroomSurvives()
    {
        Assert.Null(typeof(Newsroom).GetMethod("Airwave"));

        // Same guarantee Sponsor() pins for an unadmitted class: the skip must not take the rest
        // of the facade with it.
        Assert.NotNull(typeof(Newsroom).GetMethod("Latest"));
        Assert.NotNull(typeof(Newsroom).GetMethod("Broadcast"));
    }

    // --- Assembly-wide: nested, once, and shape (c) still nowhere ---

    [Fact]
    public void TheTwoNestedEnums_AreNestedAndDeclaredOnce_AndAirwaveIsStillAbsent()
    {
        // Was: Assert.Empty over all three names. A namespace-root `Mode`/`AdBand` would be the old
        // flattening coming back (CS0426 against every reference), and a second declaration would
        // be CS0101; Airwave stays absent because no closure edge reaches dev.other.core.
        var types = typeof(NestedModeOwner).Assembly
            .GetTypes()
            .Where(type => type.Name is "Mode" or "AdBand" or "Airwave")
            .ToArray();

        Assert.Empty(types.Where(type => type.Name == "Airwave").Select(type => type.FullName));
        Assert.Empty(types.Where(type => !type.IsNested).Select(type => type.FullName));
        Assert.Single(types.Where(type => type.Name == "AdBand"));
        // `Mode` is a common name: at least the one under NestedModeOwner must exist, and no
        // namespace-root twin of it may (asserted above).
        Assert.Contains(types, type => type.Name == "Mode" && type.DeclaringType == typeof(NestedModeOwner));
    }
}

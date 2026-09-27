using System.Reflection;
using TestLibrary.Windowsill;

namespace IntegrationTests;

/// <summary>
/// A sealed arm lists its own reachable interfaces in its C# base list. Today every arm renders
/// <c>public sealed class Arm : Base</c> and nothing else, so <c>arm is ISunseeker</c> is false for
/// an arm that genuinely implements <c>Sunseeker</c> in Kotlin, and an interface default the arm
/// does not override never reaches the arm at all.
/// <para>
/// <c>Sunroom.Beam</c> and <c>Sunroom.Shade</c> are the <c>ivarseal</c> shape: the sealed base's
/// <c>open val naps</c> and <c>Sunseeker</c>'s <c>var naps</c> are satisfied by one
/// <c>override var</c>, so the public <c>Naps</c> stays get-only and <c>ISunseeker.Naps</c> needs
/// an explicit implementation with a setter (ADR-168 on an arm). <c>Sunroom.Draught</c> implements
/// an unexported interface, which stays out of C# while its members are re-homed onto the arm.
/// <c>Nook.Box</c> is an eligible sealed interface's arm with a plain <c>var</c>.
/// </para>
/// <para>
/// Every receiver comes from a Kotlin factory typed as the sealed base, so C# holds the arm the
/// discriminator minted. Oreo takes the sunbeam; Mylo takes the shade.
/// </para>
/// </summary>
public class SealedArmInterfaceTests
{
    // ---- Sealed class arm: the base list and is/as. ----

    [Fact]
    public void Beam_ListsItsOwnInterfaceAndTheSuperInterfaceInItsBaseList()
    {
        Type[] faces = typeof(Sunroom.Beam).GetInterfaces();

        Assert.Contains(typeof(ISunseeker), faces);
        Assert.Contains(typeof(IBasker), faces);
    }

    [Fact]
    public void Beam_ValueFromKotlin_IsASunseekerAndABasker()
    {
        using Sunroom room = SunroomSample.BeamSunroom("windowsill");

        Assert.IsType<Sunroom.Beam>(room);
        Assert.True(room is ISunseeker);
        Assert.True(room is IBasker);
        Assert.NotNull(room as ISunseeker);
    }

    [Fact]
    public void Beam_InterfaceMembers_ReadThroughTheInterface()
    {
        using Sunroom room = SunroomSample.BeamSunroom("windowsill");

        ISunseeker oreo = (ISunseeker)room;

        Assert.Equal("windowsill", oreo.Spot);
        Assert.Equal(0, oreo.Naps);
        Assert.Equal(30, oreo.Warmth);
    }

    // ---- Interface default methods reachable through the arm. ----

    [Fact]
    public void Beam_InheritedDefaultMethod_CallsKotlinsDefaultThroughTheArm()
    {
        using var room = SunroomSample.BeamSunroom("windowsill");
        Sunroom.Beam oreo = Assert.IsType<Sunroom.Beam>(room);

        Assert.Equal("stretches on the windowsill after 0 naps", oreo.Stretch());
        Assert.Equal("stretches on the windowsill after 0 naps", ((ISunseeker)oreo).Stretch());
    }

    [Fact]
    public void Beam_SuperInterfaceDefaultMethod_CallsKotlinsDefaultThroughTheArm()
    {
        using var room = SunroomSample.BeamSunroom("windowsill");
        Sunroom.Beam oreo = Assert.IsType<Sunroom.Beam>(room);

        Assert.Equal("basking at 30 degrees", oreo.Bask());
        Assert.Equal("basking at 30 degrees", ((IBasker)oreo).Bask());
    }

    [Fact]
    public void Shade_OverriddenDefaultMethod_DispatchesToTheArmsOwnBody()
    {
        using var room = SunroomSample.ShadeSunroom("sofa");
        Sunroom.Shade mylo = Assert.IsType<Sunroom.Shade>(room);

        Assert.Equal("Mylo stretches in the shade of the sofa", mylo.Stretch());
        Assert.Equal("Mylo stretches in the shade of the sofa", ((ISunseeker)mylo).Stretch());
        Assert.Equal("basking at 18 degrees", ((IBasker)mylo).Bask());
    }

    // ---- ADR-168 on an arm: the explicit interface setter. ----

    [Fact]
    public void Beam_ExplicitInterfaceSetter_WritesReachKotlin()
    {
        using Sunroom room = SunroomSample.BeamSunroom("windowsill");
        Sunroom.Beam oreo = Assert.IsType<Sunroom.Beam>(room);

        // Oreo dozed off five times in the sunbeam before Mylo noticed.
        ((ISunseeker)room).Naps = 5;

        Assert.Equal(5, oreo.Naps);
        Assert.Equal(5, room.Naps);
        Assert.Equal(5, ((ISunseeker)room).Naps);
        Assert.Equal("5 naps", room.Tally());
        Assert.Equal("stretches on the windowsill after 5 naps", oreo.Stretch());
    }

    /// <summary>
    /// The public <c>Naps</c> is the sealed base's get-only slot (a C# <c>override</c> cannot add a
    /// setter, CS0546), so the setter lives only on the explicit <c>ISunseeker.Naps</c>.
    /// </summary>
    [Fact]
    public void Beam_PublicNaps_StaysGetOnly_AndTheInterfaceSetterIsExplicit()
    {
        PropertyInfo naps = typeof(Sunroom.Beam).GetProperty(
            "Naps",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)!;
        Assert.False(naps.CanWrite);

        InterfaceMapping map = typeof(Sunroom.Beam).GetInterfaceMap(typeof(ISunseeker));
        MethodInfo setter = typeof(ISunseeker).GetProperty(nameof(ISunseeker.Naps))!.SetMethod!;
        MethodInfo target = map.TargetMethods[Array.IndexOf(map.InterfaceMethods, setter)];

        Assert.True(target.IsPrivate, $"expected an explicit implementation, got {target.Name}");
        Assert.Equal(typeof(Sunroom.Beam), target.DeclaringType);
    }

    // ---- The arm crosses back to Kotlin as the interface. ----

    [Fact]
    public void Beam_PassedToKotlinAsASunseeker_CrossesAsItsOwnHandle()
    {
        using var room = SunroomSample.BeamSunroom("windowsill");
        Sunroom.Beam oreo = Assert.IsType<Sunroom.Beam>(room);

        ((ISunseeker)oreo).Naps = 2;

        Assert.Equal(
            "windowsill: 2 naps, stretches on the windowsill after 2 naps",
            SunroomSample.NapReport(oreo));
        Assert.Equal(30, SunroomSample.WarmthOf(oreo));
    }

    // ---- Unexported interface on an arm: named skip, members re-homed. ----

    [Fact]
    public void Draught_UnexportedInterface_StaysOutOfTheBaseList()
    {
        Assert.DoesNotContain(
            typeof(Sunroom.Draught).GetInterfaces(),
            face => face.Name.Contains("Radiator"));
    }

    [Fact]
    public void Draught_UnexportedInterfaceMembers_ReHomedOntoTheArm()
    {
        using var room = SunroomSample.DraughtSunroom(4);
        Sunroom.Draught draught = Assert.IsType<Sunroom.Draught>(room);

        Assert.Equal(8, draught.Fins);
        Assert.Equal(7, draught.Ticks);
        Assert.Equal("radiator hums through 8 fins", draught.Hum());
    }

    [Fact]
    public void Draught_ValueFromKotlin_IsNotASunseeker()
    {
        using Sunroom room = SunroomSample.DraughtSunroom(4);

        Assert.False(room is ISunseeker);
        Assert.False(room is IBasker);
    }

    // ---- Eligible sealed interface arm: a plain var, implicit setter. ----

    [Fact]
    public void Box_SealedInterfaceArm_IsASunseeker()
    {
        using Nook nook = SunroomSample.BoxNook("cardboard box");

        Assert.IsType<Nook.Box>(nook);
        Assert.True(nook is ISunseeker);
        Assert.True(nook is IBasker);
    }

    [Fact]
    public void Box_PlainVar_WritesThroughTheArmAndTheInterface()
    {
        using var nook = SunroomSample.BoxNook("cardboard box");
        Nook.Box box = Assert.IsType<Nook.Box>(nook);

        // Mylo climbs in the box once...
        box.Naps = 1;
        Assert.Equal(1, ((ISunseeker)box).Naps);

        // ...and Oreo climbs in on top of him three more times.
        ((ISunseeker)box).Naps = 4;
        Assert.Equal(4, box.Naps);
        Assert.Equal("cardboard box: 4 naps, stretches on the cardboard box after 4 naps",
            SunroomSample.NapReport(box));
    }

    [Fact]
    public void Box_DefaultMethods_ReachableThroughTheArm()
    {
        using var nook = SunroomSample.BoxNook("cardboard box");
        Nook.Box box = Assert.IsType<Nook.Box>(nook);

        Assert.Equal("stretches on the cardboard box after 0 naps", box.Stretch());
        Assert.Equal("basking at 25 degrees", box.Bask());
        Assert.Equal(25, SunroomSample.WarmthOf(box));
    }

    [Fact]
    public void Bare_ArmImplementingNothing_ListsNoInterface()
    {
        using Nook nook = SunroomSample.BareNook();

        Assert.False(nook is ISunseeker);
        Assert.False(nook is IBasker);
    }
}

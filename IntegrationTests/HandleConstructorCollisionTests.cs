using TestLibrary.Cat;
using TestLibrary.Ctorcollision;
using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// ROADMAP line 26: a consumer can never reach the generated internal handle constructor by
/// accident. The generated <c>Interop.cs</c> compiles into this assembly, so <c>internal</c> does not
/// hide <c>X(IntPtr handle)</c> from overload resolution, and a single-parameter public constructor
/// given a bare literal either collides with it (CS0121) or, worse, silently loses to it and wraps
/// Kotlin handle <c>0x5</c>. The fix gives every handle constructor a trailing
/// <c>out NugetHandleTag</c>, which no ordinary call can bind.
/// <para>
/// One fact per constructor route, because the route is what the emitter renders differently: a
/// root class (<see cref="LitterTray"/>, <see cref="Pillow"/>, <see cref="FoodBowl"/>,
/// <see cref="WaterFountain"/>, <see cref="Scratcher"/>), a derived class chaining
/// <c>: base(IntPtr.Zero)</c> (<see cref="TallScratcher"/>), a sealed arm
/// (<see cref="Issue54Shape.Circle"/>) and a generic class (<see cref="Box{T}"/>).
/// </para>
/// <para>
/// Red is compile-time on purpose. The CS0121 rows make this whole assembly fail to build today;
/// the silent rows would otherwise compile and dereference handle <c>0x5</c> on first member access,
/// which takes the test host down rather than failing one fact.
/// </para>
/// </summary>
public class HandleConstructorCollisionTests
{
    // --- loud today: CS0121 between the public `T?` constructor and the internal handle one ---

    [Fact]
    public void LitterTray_NullableInt_BareIntLiteral_BindsThePublicConstructor()
    {
        // Before the fix: CS0121, `int` -> `int?` (lifted) versus `int` -> `nint`, neither better.
        using var tray = new LitterTray(5);
        Assert.Equal(5, tray.Scoops);
    }

    [Fact]
    public void LitterTray_NullableInt_Null_StillBindsThePublicConstructor()
    {
        // `null` has no conversion to `nint`, so this was never ambiguous; it pins that the fix
        // does not break the one call that already worked. Oreo's tray, freshly emptied.
        using var tray = new LitterTray(null);
        Assert.Null(tray.Scoops);
    }

    [Fact]
    public void Pillow_NullableShort_TypedShortLiteral_BindsThePublicConstructor()
    {
        // Before the fix: CS0121 even with the argument correctly typed as `short`.
        using var pillow = new Pillow((short)7);
        Assert.Equal((short)7, pillow.Loft);
    }

    // --- silent today: the handle constructor is the better target and wins with no diagnostic ---

    [Fact]
    public void FoodBowl_Long_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x5. Mylo's breakfast, in grams.
        using var bowl = new FoodBowl(5);
        Assert.Equal(5L, bowl.Grams);
    }

    [Fact]
    public void WaterFountain_NullableDouble_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x2.
        using var fountain = new WaterFountain(2);
        Assert.Equal(2.0, fountain.Litres);
    }

    [Fact]
    public void Scratcher_Double_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x3.
        using var scratcher = new Scratcher(3);
        Assert.Equal(3.0, scratcher.Height);
    }

    [Fact]
    public void TallScratcher_DerivedDouble_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x9 through the derived handle
        // constructor. Oreo only climbs the tall one.
        using var scratcher = new TallScratcher(9);
        Assert.Equal(9.0, scratcher.Height);
    }

    [Fact]
    public void Circle_SealedArmDouble_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x5. Oreo, curled up at radius five.
        using var circle = new Issue54Shape.Circle(5);
        Assert.Equal(5.0, circle.Radius);
    }

    [Fact]
    public void Box_GenericLong_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x5 (`nint` beats `long`).
        using var box = new Box<long>(5);
        Assert.Equal(5L, box.Value);
    }

    [Fact]
    public void Box_GenericDouble_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x4 (`nint` beats `double`).
        using var box = new Box<double>(4);
        Assert.Equal(4.0, box.Value);
    }

    [Fact]
    public void Box_GenericUInt_BareIntLiteral_BindsThePublicConstructor_NotTheHandle()
    {
        // Before the fix: compiled, and wrapped Kotlin handle 0x6: against an unsigned target the
        // signed-over-unsigned rule picks `nint`. Six treats, one each, ideally.
        using var box = new Box<uint>(6);
        Assert.Equal(6u, box.Value);
    }
}

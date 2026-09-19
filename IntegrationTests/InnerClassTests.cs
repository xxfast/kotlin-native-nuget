using System;
using System.Linq;
using System.Reflection;
using TestLibrary;
using TestLibrary.Nested;

namespace IntegrationTests;

/// <summary>
/// ADR-141: a public Kotlin <c>inner class</c> is declared as a C# nested type whose constructor
/// takes the outer instance <em>first</em> (<c>new Hearth.Sunbather(hearth, 3)</c>, export
/// <c>hearth_sunbather_create(outer, minutes, error)</c>). Before this ADR an <c>inner class</c>
/// was a named <c>SKIPPED_NESTED_DECLARATION</c>: the type did not exist in C# at all.
///
/// <see cref="NestedTypesTests"/> already covers everything an inner class shares with a plain
/// nested class (declaration, handle, dispose, return and parameter positions). What only an inner
/// class can be asked is here:
/// <list type="bullet">
/// <item>the outer instance is the <em>first</em> constructor parameter, and is named
/// <c>outer</c>,</item>
/// <item>a primitive-only inner constructor still routes the outer through the handle slot
/// (<c>Sunbather</c>, the ADR-141 silent-loss hazard: the trivial projection path would emit
/// <c>Native_Create(outer, ...)</c> with <c>outer</c> typed <c>Hearth</c> against an
/// <c>IntPtr</c>),</item>
/// <item>an inner constructor whose own parameter needs conversion keeps the outer at the head of
/// the same list the prelude walks (<c>Cushion</c>, a <c>string</c>),</item>
/// <item>a member reading <c>this@Hearth</c> answers, including after C# has disposed the outer
/// handle: the inner's own reference keeps the outer alive on the Kotlin heap.</item>
/// </list>
///
/// Oreo owns the hearth. Mylo gets the cushion.
/// </summary>
public class InnerClassTests
{
    [Fact]
    public void InnerClass_ConstructsWithTheOuterInstanceFirst()
    {
        using var hearth = new Hearth("The bay window");
        using var sunbather = new Hearth.Sunbather(hearth, 3);

        Assert.Equal(3, sunbather.Minutes);
        Assert.Equal("sunbather@3", sunbather.Describe());
    }

    [Fact]
    public void InnerClass_MemberReadingTheOuter_SeesTheOuterConstructorArgument()
    {
        using var hearth = new Hearth("The bay window");
        using var sunbather = new Hearth.Sunbather(hearth, 3);

        // `this@Hearth.room` on the Kotlin side: nothing at the ABI carries it, the inner
        // instance's reference to its outer does.
        Assert.Equal("The bay window warms Oreo for 3 min", sunbather.Basking);
    }

    [Fact]
    public void InnerClass_ConvertedParameterConstructor_TakesTheOuterFirstToo()
    {
        // The other projection path: `fabric` needs marshalling, so the constructor cannot take
        // the trivial route, and the outer has to sit at the head of the converted list.
        using var hearth = new Hearth("The bay window");
        using var cushion = new Hearth.Cushion(hearth, "velvet");

        Assert.Equal("velvet", cushion.Fabric);
        Assert.Equal("cushion/velvet", cushion.Describe());
        Assert.Equal("velvet cushion in The bay window", cushion.Label);
    }

    [Fact]
    public void InnerClass_ReturnedFromTheOwner_IsTheSameTypeAsAConstructedOne()
    {
        using var hearth = new Hearth("The bay window");
        using var constructed = new Hearth.Sunbather(hearth, 2);
        using var returned = hearth.SunbatherAt(2);

        Assert.IsType<Hearth.Sunbather>(returned);
        Assert.Equal(constructed.Describe(), returned.Describe());
        Assert.Equal(constructed.Basking, returned.Basking);
    }

    [Fact]
    public void InnerClass_PassedBackIntoTheOwner_RoundTrips()
    {
        using var hearth = new Hearth("The bay window");
        using var sunbather = new Hearth.Sunbather(hearth, 7);
        using var returned = hearth.CushionOf("wool");

        Assert.Equal(7, hearth.MinutesOf(sunbather));
        Assert.Equal("wool cushion in The bay window", returned.Label);
        Assert.Equal("hearth in The bay window", hearth.Label());
    }

    [Fact]
    public void InnerClass_FirstConstructorParameter_IsTheOuterTypeNamedOuter()
    {
        Type? sunbather = typeof(Hearth).GetNestedType("Sunbather");
        Assert.NotNull(sunbather);
        Assert.Same(typeof(Hearth), sunbather!.DeclaringType);

        ConstructorInfo ctor = sunbather
            .GetConstructors()
            .Single(c => c.GetParameters().Length == 2);
        ParameterInfo[] parameters = ctor.GetParameters();

        Assert.Same(typeof(Hearth), parameters[0].ParameterType);
        Assert.Equal("outer", parameters[0].Name);
        Assert.Equal("minutes", parameters[1].Name);
    }

    [Fact]
    public void InnerClass_NegativeArgument_ThrowsThroughTheReceiverCarryingConstructor()
    {
        // The receiver-carrying constructor keeps the ADR-005 error envelope: `require` inside the
        // inner's `init` has to come back as an exception, not a null handle.
        using var hearth = new Hearth("The bay window");

        var exception = Assert.ThrowsAny<ArgumentException>(
            () => new Hearth.Sunbather(hearth, -1));

        Assert.Equal("A cat cannot bask for negative minutes", exception.Message);
    }

    /// <summary>
    /// ADR-141's lifetime claim, which is inferred there and pinned here: disposing the C# outer
    /// only drops the outer's StableRef, and the inner instance's own reference keeps the Kotlin
    /// object alive, so a <c>this@Hearth</c> read still answers afterwards. No <c>using</c> on
    /// either handle, because the dispose order is the point.
    /// </summary>
    [Fact]
    public void InnerClass_OuterDisposedFirst_StillReadsTheOuter()
    {
        var hearth = new Hearth("The bay window");
        var sunbather = new Hearth.Sunbather(hearth, 5);

        try
        {
            hearth.Dispose();

            Assert.Equal("The bay window warms Oreo for 5 min", sunbather.Basking);
            Assert.Equal(5, sunbather.Minutes);
        }
        finally
        {
            sunbather.Dispose();
        }
    }
}

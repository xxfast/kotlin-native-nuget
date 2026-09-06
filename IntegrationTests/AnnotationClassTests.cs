using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// A public <c>annotation class</c> (<c>Tagged</c>) skips, and today it skips silently:
/// <c>ClassKind.ANNOTATION_CLASS</c> has no route in the forward direction, so the declaration
/// clears the export gate and is then matched by no root bucket. The ADR-064 amendment adds a
/// <c>SKIPPED_ANNOTATION_CLASS</c> warning so the skip is at least named.
///
/// The C# surface is identical either way, so there is nothing here for xunit to catch going
/// green: these tests pin the two halves that must stay true while the Kotlin side gains the
/// diagnostic. No type is generated for the annotation, and applying it to an exported class is
/// inert. The warning itself is asserted at Tier 1, where the KSP log is readable.
///
/// Oreo's tag says "menace"; Mylo's says "loaf". Neither tag crosses the bridge.
/// </summary>
public class AnnotationClassTests
{
    [Fact]
    public void NoType_IsEmittedForThePublicAnnotationClass()
    {
        // Interop.cs compiles into IntegrationTests.dll, so that assembly is the right haystack
        // (precedent: Issue42Tests). Both spellings, because the plausible-but-rejected fix is a
        // .NET attribute, which would land as `TaggedAttribute`. Nothing applies it on this side,
        // so it would be pure noise on the public surface.
        var emitted = typeof(Toy).Assembly
            .GetTypes()
            .Where(t => t.Name is "Tagged" or "TaggedAttribute")
            .ToList();

        Assert.Empty(emitted);
    }

    [Fact]
    public void Toy_Constructor_IsUnaffectedByTheAnnotationUsage()
    {
        // The usage half: `@Tagged("plaything")` on Toy must cost Toy nothing. The forward
        // pipeline reads no annotation but kotlin.native.CName, so the primary constructor and
        // both properties bind exactly as before.
        using var toy = new Toy("Catnip Banana", "Green");

        Assert.Equal("Catnip Banana", toy.Name);
        Assert.Equal("Green", toy.Color);
    }

    [Fact]
    public void Toy_DataClassMembers_AreUnaffectedByTheAnnotationUsage()
    {
        // The generated members an annotation-driven regression would take out first: copy,
        // equals, hashCode, toString. Oreo hoards the banana; Mylo settles for the identical one.
        using var oreos = new Toy("Catnip Banana", "Green");
        using var mylos = oreos.Copy("Catnip Banana", "Green");
        using var recoloured = oreos.Copy("Catnip Banana", "Blue");

        Assert.True(oreos.Equals(mylos));
        Assert.Equal(oreos.GetHashCode(), mylos.GetHashCode());
        Assert.False(oreos.Equals(recoloured));
        Assert.Equal("Toy(name=Catnip Banana, color=Green)", oreos.ToString());
    }
}

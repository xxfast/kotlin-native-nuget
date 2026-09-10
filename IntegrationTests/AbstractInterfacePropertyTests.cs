using System.Reflection;
using TestLibrary.Aviary;

namespace IntegrationTests;

/// <summary>
/// ADR-075: an exported interface property that an exported abstract class inherits but never
/// implements must reach C# as an abstract property on the abstract class.
///
/// `Feathered` declares `val plumage` and `var perch`; `Bird` implements neither. The generated
/// `Bird : IFeathered` therefore has to declare `public abstract string Plumage { get; }` and
/// `public abstract string Perch { get; set; }` itself. Today the property walk drops an
/// inherited member with no implementation on the promise that "the abstract path picks it up",
/// which is only true of methods, so `Bird` does not satisfy `IFeathered` and the whole
/// generated file fails to compile with CS0535.
///
/// The consumer compile is the real proof: none of this file builds until the shape is right.
/// The reflection facts pin it so a concrete or `virtual` base property, which would also make
/// the compile pass, does not quietly satisfy the item. Oreo is the black one with the white
/// middle, so he is not the finch.
/// </summary>
public class AbstractInterfacePropertyTests
{
    [Fact]
    public void Bird_RendersAbstract()
    {
        Assert.True(typeof(Bird).IsAbstract);
    }

    [Fact]
    public void Bird_InheritedVal_IsAbstractAndGetOnly()
    {
        PropertyInfo? plumage = typeof(Bird).GetProperty("Plumage");

        Assert.NotNull(plumage);
        Assert.NotNull(plumage!.GetGetMethod());
        Assert.True(plumage.GetGetMethod()!.IsAbstract);
        Assert.Null(plumage.GetSetMethod());
    }

    [Fact]
    public void Bird_InheritedVar_IsAbstractOnBothAccessors()
    {
        PropertyInfo? perch = typeof(Bird).GetProperty("Perch");

        Assert.NotNull(perch);
        Assert.NotNull(perch!.GetGetMethod());
        Assert.NotNull(perch.GetSetMethod());
        Assert.True(perch.GetGetMethod()!.IsAbstract);
        Assert.True(perch.GetSetMethod()!.IsAbstract);
    }

    [Fact]
    public void Bird_ImplementsTheExportedInterface()
    {
        Assert.True(typeof(IFeathered).IsAssignableFrom(typeof(Bird)));
    }

    [Fact]
    public void Finch_Overrides_RatherThanHidesTheInheritedMembers()
    {
        MethodInfo plumage = typeof(Finch).GetProperty("Plumage")!.GetGetMethod()!;
        MethodInfo perch = typeof(Finch).GetProperty("Perch")!.GetSetMethod()!;

        Assert.False(plumage.IsAbstract);
        Assert.False(perch.IsAbstract);
        Assert.Equal(typeof(Bird), plumage.GetBaseDefinition().DeclaringType);
        Assert.Equal(typeof(Bird), perch.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Finch_ReadThroughABirdTypedReference_SeesTheOverride()
    {
        using var finch = new Finch();

        Bird bird = finch;

        Assert.Equal("brown", bird.Plumage);
        Assert.Equal("twig", bird.Perch);
    }

    [Fact]
    public void Finch_PerchWrittenThroughTheBase_IsSeenByKotlinDispatch()
    {
        using var finch = new Finch();

        Bird bird = finch;

        Assert.Equal("finch: brown on twig", bird.Describe());

        // Mylo knocked the twig off, so the finch moved to the curtain rail.
        bird.Perch = "curtain rail";

        // `describe()` is Kotlin's own dispatch through the overrides, so this proves the write
        // reached the Kotlin object rather than being echoed by the C# getter.
        Assert.Equal("curtain rail", finch.Perch);
        Assert.Equal("finch: brown on curtain rail", bird.Describe());
        Assert.Equal("finch: brown on curtain rail", finch.Describe());
    }
}

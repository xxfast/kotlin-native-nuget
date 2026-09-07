using System.Linq;
using System.Reflection;
using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// A sealed subclass declared <em>beside</em> its sealed base, not inside it, must produce exactly
/// one C# type. Today <c>Label</c> is collected twice: once by the ordinary class route as a
/// namespace-level <c>TestLibrary.Issue54.Label</c> with <c>label_*</c> exports, and once by the
/// ADR-009 sealed route as a nested <c>TestLibrary.Issue54.FlatShape.Label</c> with
/// <c>flatshape_label_*</c> exports. One Kotlin type, two C# types, so <c>is</c> checks disagree
/// with themselves. After the fix the sealed route is the sole owner and a sibling subclass is
/// declared at namespace level as <c>public sealed class Label : FlatShape</c>, while a genuinely
/// nested subclass stays nested (<c>FlatShape.Circle</c>).
/// <para>
/// Mylo declines to be a shape and settles for a label; Oreo is the circle, three deep on the sill.
/// </para>
/// </summary>
public class FlatSealedSubclassTests
{
    /// <summary>
    /// The sealed base at a <em>class method</em> return, discriminating to the sibling arm. Mylo
    /// declines geometry, so a non-positive radius hands back a <c>Label</c>.
    /// </summary>
    [Fact]
    public void Of_SealedBaseAtAClassMethodReturn_DiscriminatesToTheSiblingSubclass()
    {
        using var factory = new FlatShapeFactory();

        using FlatShape flat = factory.Of(0);

        var label = Assert.IsType<Label>(flat);
        Assert.Equal("flat", label.Text);
    }

    /// <summary>
    /// The same class method, other arm: Oreo curls, so a positive radius hands back the nested
    /// <c>FlatShape.Circle</c>.
    /// </summary>
    [Fact]
    public void Of_SealedBaseAtAClassMethodReturn_DiscriminatesToTheNestedSubclass()
    {
        using var factory = new FlatShapeFactory();

        using FlatShape flat = factory.Of(2);

        var circle = Assert.IsType<FlatShape.Circle>(flat);
        Assert.Equal(2, circle.Radius);
    }

    [Fact]
    public void AnyFlat_SealedBaseAtATopLevelReturn_DiscriminatesToTheSiblingSubclass()
    {
        using FlatShape any = FlatShapeSample.AnyFlat();

        var label = Assert.IsType<Label>(any);
        Assert.Equal("any", label.Text);
    }

    [Fact]
    public void Label_SiblingSubclassAtAReturnPosition_ReadsItsPayload()
    {
        using var factory = new FlatShapeFactory();

        using Label mylo = factory.Label("x");

        Assert.Equal("x", mylo.Text);
    }

    [Fact]
    public void Label_SiblingSubclassAtAReturnPosition_IsStillTheSealedBaseUnderneath()
    {
        using var factory = new FlatShapeFactory();

        using Label mylo = factory.Label("x");

        Assert.IsAssignableFrom<FlatShape>(mylo);
    }

    [Fact]
    public void Circle_NestedSubclassAtAPropertyPosition_StaysNestedAndReadsItsPayload()
    {
        using var factory = new FlatShapeFactory();

        using FlatShape.Circle oreo = factory.Circle;

        Assert.Equal(3, oreo.Radius);
        Assert.Equal(typeof(FlatShape), typeof(FlatShape.Circle).DeclaringType);
    }

    /// <summary>
    /// The duplicate itself, asserted directly: exactly one type in the assembly is named
    /// <c>Label</c>, it is declared at namespace level rather than nested inside <c>FlatShape</c>,
    /// and it derives from <c>FlatShape</c>.
    /// </summary>
    [Fact]
    public void Label_IsDeclaredOnceAtNamespaceLevel_AndDerivesFromTheSealedBase()
    {
        Assembly assembly = typeof(FlatShape).Assembly;

        Type[] labels = assembly
            .GetTypes()
            .Where(type => type.Name == "Label" && type.Namespace == "TestLibrary.Issue54")
            .ToArray();

        Assert.Single(labels);
        Assert.Null(labels[0].DeclaringType);
        Assert.Null(typeof(Label).DeclaringType);
        Assert.Equal(typeof(FlatShape), typeof(Label).BaseType);
    }
}

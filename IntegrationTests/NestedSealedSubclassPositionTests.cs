using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// A sealed subclass declared nested inside its sealed base is <em>declared</em> in C# as a nested
/// class (<c>NestedShape.Circle</c>, ADR-009), but at a member type position — a method return, a
/// property, a parameter — the forward classifier spells it from the simple name only,
/// <c>global::TestLibrary.Issue54.Circle</c>, dropping the enclosing base. No such type exists, so
/// the generated <c>Interop.cs</c> does not compile (CS0246) and neither does this file. That is
/// the red signal; after the fix the spelling is
/// <c>global::TestLibrary.Issue54.NestedShape.Circle</c> and every assertion below holds.
/// <para>
/// Four seams, one member each: return, property, parameter, and the sealed <em>base</em> at a
/// return position as the control that already works today.
/// </para>
/// <para>
/// Oreo curls into a circle of whatever radius the sunbeam allows; Mylo declines to be a shape.
/// </para>
/// </summary>
public class NestedSealedSubclassPositionTests
{
    [Fact]
    public void Circle_NestedSubclassAtAReturnPosition_KeepsTheEnclosingBaseInItsCsharpName()
    {
        using var factory = new NestedShapeFactory();

        using var oreo = factory.Circle(2.0);

        var circle = Assert.IsType<NestedShape.Circle>(oreo);
        Assert.Equal(2.0, circle.Radius);
    }

    [Fact]
    public void Circle_NestedSubclassReturn_IsStillTheSealedBaseUnderneath()
    {
        using var factory = new NestedShapeFactory();

        using var oreo = factory.Circle(4.5);

        Assert.IsAssignableFrom<NestedShape>(oreo);
    }

    [Fact]
    public void Unit_NestedSubclassAtAPropertyPosition_ReadsTheSubclassPayload()
    {
        using var factory = new NestedShapeFactory();

        using var unit = factory.Unit;

        Assert.Equal(1.0, unit.Radius);
    }

    [Fact]
    public void RadiusOf_NestedSubclassAtAParameterPosition_UnwrapsTheHandleBackToKotlin()
    {
        using var factory = new NestedShapeFactory();
        using var oreo = factory.Circle(3.0);

        double radius = factory.RadiusOf(oreo);

        Assert.Equal(3.0, radius);
    }

    /// <summary>
    /// Control: the sealed <em>base</em> at a top-level function return, the sealed-return position
    /// this repository already exercises, must stay green through the fix.
    /// </summary>
    [Fact]
    public void AnyShape_SealedBaseAtATopLevelReturn_StillDiscriminatesToTheNestedSubclass()
    {
        using NestedShape any = NestedShapeSample.AnyShape(1.0);

        var circle = Assert.IsType<NestedShape.Circle>(any);
        Assert.Equal(1.0, circle.Radius);
    }

    /// <summary>
    /// The same sealed base at a <em>class method</em> return, the position that is silently dropped
    /// today. It has to plan on the ordinary member plan and read back through the ADR-009
    /// <c>FromHandle</c> discriminator, exactly as the top-level control above does. Oreo curls to
    /// whatever radius the sunbeam allows.
    /// </summary>
    [Fact]
    public void ShapeOf_SealedBaseAtAClassMethodReturn_DiscriminatesToTheNestedSubclass()
    {
        using var factory = new NestedShapeFactory();

        using NestedShape shape = factory.ShapeOf(2.0);

        var circle = Assert.IsType<NestedShape.Circle>(shape);
        Assert.Equal(2.0, circle.Radius);
    }
}

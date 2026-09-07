using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// Issue <a href="https://github.com/xxfast/kotlin-native-nuget/issues/54">#54</a> / ADR-105 scope
/// (c): a property whose type is a sealed class must bind in C# as the sealed <em>base</em>,
/// materialised through the generated <c>FromHandle</c> discriminator, so the consumer can pattern
/// match on it. Today the forward property planner has no arm for
/// <c>SpecializedProtocol("sealed helper ...")</c>, so <c>Shape</c>, <c>Maybe</c>, <c>Shapes</c> and
/// <c>Current</c> are dropped with <c>SKIPPED_UNSUPPORTED_PROPERTY</c> and do not exist on the
/// generated <see cref="Issue54Drawing"/>. These tests therefore cannot compile until the feature
/// ships, which is the red signal.
/// <para>
/// The four property positions are four seams: bare sealed, nullable sealed, sealed collection
/// component (read-only), and a scalar sealed setter. Two more seams belong to the ordinary member
/// plan rather than the property plan: a scalar sealed return and a sealed <em>collection</em>
/// return on a class member (<c>Issue54Shapes.Pick(int)</c> / <c>Issue54Shapes.EveryShape()</c>), with the
/// same collection return at a top-level function (<c>Issue54Sample.Shapes()</c>) as the control
/// that already binds. The cats: Oreo curls into a circle, Mylo sprawls into nothing.
/// </para>
/// </summary>
public class Issue54Tests
{
    [Fact]
    public void Shape_BareSealedProperty_DiscriminatesToTheRightSubclass()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();

        using Issue54Shape shape = drawing.Shape;

        var circle = Assert.IsType<Issue54Shape.Circle>(shape);
        Assert.Equal(2.0, circle.Radius);
    }

    [Fact]
    public void Maybe_NullableSealedProperty_IsNullWhenKotlinSaysNull()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();

        Issue54Shape? maybe = drawing.Maybe;

        Assert.Null(maybe);
    }

    [Fact]
    public void Maybe_NullableSealedProperty_DiscriminatesWhenPresent()
    {
        using Issue54Drawing drawing = Issue54Sample.CurledCats();

        Issue54Shape? maybe = drawing.Maybe;

        Assert.NotNull(maybe);
        using Issue54Shape owned = maybe;
        Assert.Equal(3.5, Assert.IsType<Issue54Shape.Circle>(owned).Radius);
    }

    [Fact]
    public void Shapes_SealedCollectionComponent_IsReadOnlyListWithBothArmsInOrder()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();

        IReadOnlyList<Issue54Shape> shapes = drawing.Shapes;

        Assert.IsAssignableFrom<IReadOnlyList<Issue54Shape>>(shapes);
        Assert.Collection(
            shapes,
            mylo => Assert.IsType<Issue54Shape.Empty>(mylo),
            oreo => Assert.Equal(1.0, Assert.IsType<Issue54Shape.Circle>(oreo).Radius));
    }

    [Fact]
    public void Shape_PatternMatchingSwitch_ReadsTheSubclassPayload()
    {
        using Issue54Drawing drawing = Issue54Sample.CurledCats();

        using Issue54Shape shape = drawing.Shape;

        string description = shape switch
        {
            Issue54Shape.Circle c => $"Oreo curled at r={c.Radius}",
            Issue54Shape.Empty => "Mylo sprawled",
            _ => throw new InvalidOperationException(),
        };

        Assert.Equal("Oreo curled at r=7.5", description);
    }

    /// <summary>
    /// Control: a sealed <em>collection</em> at a top-level function return, the position that binds
    /// today. Same order as the property: Mylo sprawled first, Oreo curled at <c>1.0</c> second.
    /// </summary>
    [Fact]
    public void Shapes_SealedCollectionAtATopLevelReturn_YieldsBothArmsInOrder()
    {
        IReadOnlyList<Issue54Shape> shapes = Issue54Sample.Shapes();

        Assert.Collection(
            shapes,
            mylo => Assert.IsType<Issue54Shape.Empty>(mylo),
            oreo => Assert.Equal(1.0, Assert.IsType<Issue54Shape.Circle>(oreo).Radius));
    }

    /// <summary>
    /// The same sealed collection return on a <em>class member</em> rather than a top-level
    /// function, which is the spelling that is dropped today. Both cats, same order.
    /// </summary>
    [Fact]
    public void EveryShape_SealedCollectionAtAClassMemberReturn_YieldsBothArmsInOrder()
    {
        IReadOnlyList<Issue54Shape> shapes = Issue54Shapes.EveryShape();

        Assert.Collection(
            shapes,
            mylo => Assert.IsType<Issue54Shape.Empty>(mylo),
            oreo => Assert.Equal(1.0, Assert.IsType<Issue54Shape.Circle>(oreo).Radius));
    }

    /// <summary>
    /// A scalar sealed return on an <c>object</c> method, so the discriminator picks an arm from a
    /// static call site. Zero is Mylo, refusing to be a shape.
    /// </summary>
    [Fact]
    public void Pick_SealedBaseAtAnObjectMethodReturn_DiscriminatesToThePayloadFreeArm()
    {
        using Issue54Shape mylo = Issue54Shapes.Pick(0);

        Assert.IsType<Issue54Shape.Empty>(mylo);
    }

    /// <summary>
    /// The other arm of the same <c>object</c> method: Oreo, curled to the radius asked for.
    /// </summary>
    [Fact]
    public void Pick_SealedBaseAtAnObjectMethodReturn_DiscriminatesToThePayloadArm()
    {
        using Issue54Shape oreo = Issue54Shapes.Pick(3);

        Assert.Equal(3.0, Assert.IsType<Issue54Shape.Circle>(oreo).Radius);
    }

    [Fact]
    public void Current_ScalarSealedSetter_RoundTripsThroughTheHandleWire()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();
        using Issue54Drawing other = Issue54Sample.CurledCats();

        using (Issue54Shape before = drawing.Current)
        {
            Assert.IsType<Issue54Shape.Empty>(before);
        }

        // The sealed subclasses expose only an internal handle constructor (ADR-009), so the value
        // written back comes from another drawing's getter rather than from `new Circle(7.5)`.
        drawing.Current = other.Shape;

        using Issue54Shape after = drawing.Current;
        Assert.Equal(7.5, Assert.IsType<Issue54Shape.Circle>(after).Radius);
    }
}

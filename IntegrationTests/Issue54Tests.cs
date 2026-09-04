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
/// The four positions are the four seams: bare sealed, nullable sealed, sealed collection component
/// (read-only), and a scalar sealed setter. The cats: Oreo curls into a circle, Mylo sprawls into
/// nothing.
/// </para>
/// </summary>
public class Issue54Tests
{
    [Fact]
    public void Shape_BareSealedProperty_DiscriminatesToTheRightSubclass()
    {
        using Issue54Drawing drawing = Issue54Sample.sleepingCats();

        using Issue54Shape shape = drawing.Shape;

        var circle = Assert.IsType<Issue54Shape.Circle>(shape);
        Assert.Equal(2.0, circle.Radius);
    }

    [Fact]
    public void Maybe_NullableSealedProperty_IsNullWhenKotlinSaysNull()
    {
        using Issue54Drawing drawing = Issue54Sample.sleepingCats();

        Issue54Shape? maybe = drawing.Maybe;

        Assert.Null(maybe);
    }

    [Fact]
    public void Maybe_NullableSealedProperty_DiscriminatesWhenPresent()
    {
        using Issue54Drawing drawing = Issue54Sample.curledCats();

        Issue54Shape? maybe = drawing.Maybe;

        Assert.NotNull(maybe);
        using Issue54Shape owned = maybe;
        Assert.Equal(3.5, Assert.IsType<Issue54Shape.Circle>(owned).Radius);
    }

    [Fact]
    public void Shapes_SealedCollectionComponent_IsReadOnlyListWithBothArmsInOrder()
    {
        using Issue54Drawing drawing = Issue54Sample.sleepingCats();

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
        using Issue54Drawing drawing = Issue54Sample.curledCats();

        using Issue54Shape shape = drawing.Shape;

        string description = shape switch
        {
            Issue54Shape.Circle c => $"Oreo curled at r={c.Radius}",
            Issue54Shape.Empty => "Mylo sprawled",
            _ => throw new InvalidOperationException(),
        };

        Assert.Equal("Oreo curled at r=7.5", description);
    }

    [Fact]
    public void Current_ScalarSealedSetter_RoundTripsThroughTheHandleWire()
    {
        using Issue54Drawing drawing = Issue54Sample.sleepingCats();
        using Issue54Drawing other = Issue54Sample.curledCats();

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

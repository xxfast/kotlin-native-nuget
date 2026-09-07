using System.Reflection;

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
/// <para>
/// The remaining half of ADR-105 scope (d) is the <em>parameter</em> position, and the tests below
/// it are the second red signal: a bare sealed parameter
/// (<c>Issue54Shapes.Describe(Issue54Shape)</c>), a nullable one (<c>DescribeMaybe</c>), a sealed
/// collection component (<c>Count</c> / <c>Radii</c>), the four-parameter
/// <see cref="Issue54Drawing"/> constructor that today skips whole and leaves the class with no
/// public constructor, and the <c>var shapes: MutableList&lt;Issue54Shape&gt;</c> setter on
/// <see cref="Issue54Board"/> that scope (c) left get-only behind the
/// <c>viaDiscriminator</c> gate. C# cannot construct a sealed subclass (ADR-009 gives them internal
/// handle constructors only), so every sealed argument below is sourced from an existing getter.
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

    /// <summary>
    /// Scope (d): a bare sealed <em>parameter</em>. The instance handle goes back the way it came
    /// (<c>shape._handle</c>, the abstract base implements <c>INugetHandle</c>) and Kotlin answers
    /// with a string, so nothing about the assertion depends on the return side.
    /// </summary>
    [Fact]
    public void Describe_BareSealedParameter_PassesTheInstanceHandleToKotlin()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();
        using Issue54Shape oreo = drawing.Shape;

        Assert.Equal("circle:2.0", Issue54Shapes.Describe(oreo));
    }

    /// <summary>
    /// The payload-free arm across the same parameter: Mylo, sprawled, is still a shape that has to
    /// arrive.
    /// </summary>
    [Fact]
    public void Describe_BareSealedParameter_CarriesThePayloadFreeArm()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();
        using Issue54Shape mylo = drawing.Current;

        Assert.Equal("empty", Issue54Shapes.Describe(mylo));
    }

    /// <summary>
    /// Scope (d), nullable sealed parameter, null half: <c>null</c> crosses in-band on the pointer
    /// (<c>IntPtr.Zero</c>), and Kotlin must tell "no cat" apart from "a cat of no shape".
    /// </summary>
    [Fact]
    public void DescribeMaybe_NullableSealedParameter_SendsNullAsTheZeroHandle()
    {
        Assert.Equal("none", Issue54Shapes.DescribeMaybe(null));
    }

    /// <summary>
    /// The non-null half of the same parameter, with Mylo on the wire so the answer is distinct from
    /// the <c>null</c> one above.
    /// </summary>
    [Fact]
    public void DescribeMaybe_NullableSealedParameter_SendsThePresentHandle()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();
        using Issue54Shape mylo = drawing.Current;

        Assert.Equal("some:empty", Issue54Shapes.DescribeMaybe(mylo));
    }

    /// <summary>
    /// Scope (d), sealed collection component at a parameter: each element boxes through
    /// <c>NugetMarshal.Wrap&lt;T&gt;</c>'s <c>INugetHandle</c> arm, the ADR-073 write path that has
    /// never run for an abstract C# base. Length first.
    /// </summary>
    [Fact]
    public void Count_SealedCollectionParameter_BoxesEveryElementIntoTheKotlinList()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();
        using Issue54Shape oreo = drawing.Shape;
        using Issue54Shape mylo = drawing.Current;

        Assert.Equal(2, Issue54Shapes.Count(new List<Issue54Shape> { oreo, mylo }));
    }

    /// <summary>
    /// The same collection parameter read for its payload, in order: Oreo's radius, then Mylo's
    /// nothing. A handle that arrived as a raw pointer rather than a shape cannot answer this.
    /// </summary>
    [Fact]
    public void Radii_SealedCollectionParameter_ArrivesAsRealShapesInOrder()
    {
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();
        using Issue54Shape oreo = drawing.Shape;
        using Issue54Shape mylo = drawing.Current;

        IReadOnlyList<double> radii = Issue54Shapes.Radii(new List<Issue54Shape> { oreo, mylo });

        Assert.Equal(new[] { 2.0, 0.0 }, radii);
    }

    /// <summary>
    /// The densest parameter cell: the <see cref="Issue54Drawing"/> constructor takes bare, nullable,
    /// collection and <c>var</c> sealed parameters at once. Today it skips whole
    /// (<c>SKIPPED_SEALED_POSITION</c>) and the class has no public constructor at all, so this does
    /// not compile. The arguments are Oreo curled at <c>7.5</c> and Mylo, borrowed from the two
    /// existing producers.
    /// </summary>
    [Fact]
    public void Constructor_SealedParametersOnEveryComponent_RoundTripsThroughTheProperties()
    {
        using Issue54Drawing sleeping = Issue54Sample.SleepingCats();
        using Issue54Drawing curled = Issue54Sample.CurledCats();

        using Issue54Drawing built = new Issue54Drawing(
            curled.Shape,
            curled.Maybe,
            sleeping.Shapes,
            sleeping.Current);

        using Issue54Shape shape = built.Shape;
        Assert.Equal(7.5, Assert.IsType<Issue54Shape.Circle>(shape).Radius);

        Issue54Shape? maybe = built.Maybe;
        Assert.NotNull(maybe);
        using Issue54Shape owned = maybe;
        Assert.Equal(3.5, Assert.IsType<Issue54Shape.Circle>(owned).Radius);

        Assert.Collection(
            built.Shapes,
            mylo => Assert.IsType<Issue54Shape.Empty>(mylo),
            oreo => Assert.Equal(1.0, Assert.IsType<Issue54Shape.Circle>(oreo).Radius));

        using Issue54Shape current = built.Current;
        Assert.IsType<Issue54Shape.Empty>(current);
    }

    /// <summary>
    /// The mutable sealed collection setter, which ADR-105 scope (c) left get-only behind the
    /// <c>viaDiscriminator</c> gate on <c>isWrappableComponent</c>, named by the ADR-075 read-only
    /// diagnostic. Assigning is the parameter-position write path under another name, so it lands
    /// with scope (d). The board starts as Mylo alone on the windowsill.
    /// </summary>
    [Fact]
    public void Shapes_MutableSealedCollectionSetter_WritesBothArmsBackIntoKotlin()
    {
        using Issue54Board board = Issue54Sample.Windowsill();
        using Issue54Drawing drawing = Issue54Sample.SleepingCats();
        using Issue54Drawing other = Issue54Sample.CurledCats();

        Assert.Equal("empty", board.Summary());

        board.Shapes = new List<Issue54Shape> { other.Shape, drawing.Current };

        IList<Issue54Shape> shapes = board.Shapes;
        Assert.Collection(
            shapes,
            oreo => Assert.Equal(7.5, Assert.IsType<Issue54Shape.Circle>(oreo).Radius),
            mylo => Assert.IsType<Issue54Shape.Empty>(mylo));

        // Kotlin-side observation: the handles landed as real shapes, not as an echo of the getter.
        Assert.Equal("circle:7.5,empty", board.Summary());
    }
    /// <summary>
    /// The <c>data object</c> arm going <em>back into</em> Kotlin at the scalar sealed setter. Every
    /// existing setter fact writes a <c>Circle</c>; the payload-free singleton is the one whose write
    /// path is unpinned, because a subclass with no state has nothing on the wire except the handle
    /// itself. The target drawing is built with Oreo curled at <c>7.5</c> as its <c>Current</c>, so
    /// the transition Circle -&gt; Empty is observable rather than a no-op (both fixture producers
    /// already start on <see cref="Issue54Shape.Empty"/>).
    /// </summary>
    [Fact]
    public void Current_ScalarSealedSetter_WritesTheDataObjectSingletonBackIntoKotlin()
    {
        using Issue54Drawing sleeping = Issue54Sample.SleepingCats();
        using Issue54Drawing curled = Issue54Sample.CurledCats();

        using Issue54Drawing built = new Issue54Drawing(
            curled.Shape,
            curled.Maybe,
            sleeping.Shapes,
            curled.Shape);

        using (Issue54Shape oreo = built.Current)
        {
            Assert.Equal(7.5, Assert.IsType<Issue54Shape.Circle>(oreo).Radius);
        }

        built.Current = curled.Current;

        using Issue54Shape mylo = built.Current;
        Assert.IsType<Issue54Shape.Empty>(mylo);

        // Kotlin-side observation: `describe`'s `Empty` branch is an equality test against the
        // singleton, so it only answers "empty" if the handle dereferenced to the real object.
        Assert.Equal("empty", Issue54Shapes.Describe(mylo));
    }

    /// <summary>
    /// The singleton at a bare sealed parameter, sourced from <c>curledCats()</c> so the handle is
    /// minted by a different producer than the one the existing payload-free parameter fact uses.
    /// Mylo sprawls identically in either drawing; a <c>data object</c> has exactly one instance, so
    /// both handles must deref to it.
    /// </summary>
    [Fact]
    public void Describe_DataObjectAtABareParameter_ArrivesAsTheSingleton()
    {
        using Issue54Drawing curled = Issue54Sample.CurledCats();
        using Issue54Shape mylo = curled.Current;

        Assert.Equal("empty", Issue54Shapes.Describe(mylo));
    }

    /// <summary>
    /// Aliasing: two <em>separate</em> C# wrappers over the same Kotlin singleton, in one collection
    /// parameter. Every existing collection-parameter fact passes two distinct shapes, so nothing
    /// pins that the boxing path tolerates the same underlying object twice. Mylo cannot be in two
    /// places at once, but two handles to him can.
    /// </summary>
    [Fact]
    public void Count_TheSameDataObjectTwice_BoxesBothHandlesIntoTheKotlinList()
    {
        using Issue54Drawing curled = Issue54Sample.CurledCats();

        Assert.Equal(2, Issue54Shapes.Count(new List<Issue54Shape> { curled.Current, curled.Current }));
    }

    /// <summary>
    /// Equality on a <c>data object</c> arm. Every read of <c>Current</c> mints a fresh C# wrapper
    /// over the one Kotlin singleton, so the two wrappers are never reference-equal. Equality
    /// therefore has to come from the <c>issue54shape_empty_equals</c> / <c>_hashcode</c> exports,
    /// which the sealed renderer binds for a <c>data object</c> exactly as for a <c>data class</c>.
    /// Mylo is Mylo whichever hand you read him with.
    /// </summary>
    [Fact]
    public void Empty_TwoSeparateReadsOfTheSingleton_AreEqual()
    {
        using Issue54Drawing curled = Issue54Sample.CurledCats();

        using Issue54Shape first = curled.Current;
        using Issue54Shape second = curled.Current;

        Issue54Shape.Empty mylo = Assert.IsType<Issue54Shape.Empty>(first);
        Issue54Shape.Empty alsoMylo = Assert.IsType<Issue54Shape.Empty>(second);

        Assert.NotSame(mylo, alsoMylo);
        Assert.Equal(mylo, alsoMylo);
        Assert.Equal(mylo.GetHashCode(), alsoMylo.GetHashCode());
    }

    /// <summary>
    /// <c>ToString</c> is the one member the renderer does emit for a <c>data object</c>, as a fixed
    /// literal rather than a call to <c>issue54shape_empty_tostring</c>. Kept as the control beside
    /// the equality facts above so a regression in either is distinguishable.
    /// </summary>
    [Fact]
    public void Empty_ToString_NamesTheDataObject()
    {
        using Issue54Drawing curled = Issue54Sample.CurledCats();
        using Issue54Shape mylo = curled.Current;

        Assert.Contains("Empty", mylo.ToString());
    }

    /// <summary>
    /// Control for the equality facts: the <c>data class</c> arm of the same sealed base already
    /// binds <c>Equals</c> / <c>GetHashCode</c> through the native exports, so two wrappers over
    /// equal payloads compare equal. Oreo, read twice, is curled to the same radius both times.
    /// </summary>
    [Fact]
    public void Circle_TwoReadsWithEqualPayloads_AreEqual()
    {
        using Issue54Drawing curled = Issue54Sample.CurledCats();

        using Issue54Shape first = curled.Shape;
        using Issue54Shape second = curled.Shape;

        Issue54Shape.Circle oreo = Assert.IsType<Issue54Shape.Circle>(first);
        Issue54Shape.Circle alsoOreo = Assert.IsType<Issue54Shape.Circle>(second);

        Assert.NotSame(oreo, alsoOreo);
        Assert.Equal(oreo, alsoOreo);
        Assert.Equal(oreo.GetHashCode(), alsoOreo.GetHashCode());
    }

    /// <summary>
    /// Cross-arm inequality, which is the <c>return false</c> tail of the generated
    /// <c>Equals(object)</c>: Oreo curled is not Mylo sprawled. Coverage showed that tail cold on
    /// <c>Circle</c>, and it is the same line the renderer must emit for <c>Empty</c>, so pinning it
    /// here says what the fix has to preserve on both arms.
    /// </summary>
    [Fact]
    public void Shapes_OnDifferentArms_AreNotEqual()
    {
        using Issue54Drawing curled = Issue54Sample.CurledCats();

        using Issue54Shape oreo = curled.Shape;
        using Issue54Shape mylo = curled.Current;

        Assert.NotEqual<Issue54Shape>(oreo, mylo);
        Assert.NotEqual<Issue54Shape>(mylo, oreo);
    }

    /// <summary>
    /// <c>Circle.ToString()</c> goes through <c>issue54shape_circle_tostring</c> and
    /// <c>Marshal.PtrToStringUTF8</c>, unlike <c>Empty</c>'s fixed literal. Coverage showed this
    /// export never called; it is the third member of the data-class family the renderer owes
    /// <c>Empty</c>, so the control belongs beside the other two.
    /// </summary>
    [Fact]
    public void Circle_ToString_ComesFromTheNativeExport()
    {
        using Issue54Drawing curled = Issue54Sample.CurledCats();
        using Issue54Shape oreo = curled.Shape;

        Assert.Contains("7.5", oreo.ToString());
    }

    /// <summary>
    /// The shape of the renderer bug without running the wire: <c>Equals(object)</c> must be
    /// <em>declared</em> on the generated <see cref="Issue54Shape.Empty"/>, not inherited from
    /// <see cref="object"/>. Today <c>GetMethod</c> resolves <c>System.Object.Equals</c>, which is
    /// the reference-equality behaviour the docs assert is "enough for a singleton".
    /// </summary>
    [Fact]
    public void Empty_EqualsObject_IsDeclaredOnTheGeneratedSubclass()
    {
        MethodInfo? equals = typeof(Issue54Shape.Empty).GetMethod("Equals", new[] { typeof(object) });

        Assert.NotNull(equals);
        Assert.Equal(typeof(Issue54Shape.Empty), equals.DeclaringType);
    }

    /// <summary>
    /// Same for <c>GetHashCode</c>: a type that overrides <c>Equals</c> without it is a hashing bug
    /// waiting in any <c>HashSet&lt;Issue54Shape&gt;</c>.
    /// </summary>
    [Fact]
    public void Empty_GetHashCode_IsDeclaredOnTheGeneratedSubclass()
    {
        MethodInfo? hashCode = typeof(Issue54Shape.Empty).GetMethod("GetHashCode", Type.EmptyTypes);

        Assert.NotNull(hashCode);
        Assert.Equal(typeof(Issue54Shape.Empty), hashCode.DeclaringType);
    }
}

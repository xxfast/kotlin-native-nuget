using System.Linq;
using System.Reflection;
using TestLibrary;
using TestLibrary.Issue54;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// Issue #110. A Kotlin <c>object</c> that is a subclass of an exported sealed type is declared
/// twice in the generated C#: once properly by the ADR-009 sealed route, and once again as an empty
/// <c>public static class</c> at namespace level. Two collectors feed that second declaration and
/// neither of them asks whether the object already has an owner.
///
/// <para>
/// Route one, <c>rootObjects</c>: <c>classKind == OBJECT &amp;&amp; parentDeclaration == null</c>,
/// with no <c>isSealedSubclass()</c> filter, unlike its <c>rootClasses</c> neighbour. A
/// <em>sibling</em> object subclass therefore lands at namespace level twice under the same name.
/// That is CS0101, and the reported CS0713/CS0710/CS0708 are the cascade of the compiler merging
/// the two declarations and then reading the sealed route's real members as instance members of the
/// static one. <c>Loaf</c> is this cell.
/// </para>
///
/// <para>
/// Route two, the ADR-066 reachability closure: its nested-declaration refusal carves out
/// <c>isSealedSubclass()</c> (correctly, because the sealed route does declare a subclass nested),
/// but <c>reachabilityBucket()</c> then tests <c>classKind == OBJECT</c> <em>before</em>
/// <c>isSealedSubclass()</c>, so a nested object arm falls into the OBJECT bucket anyway. That one
/// does not collide, since the sealed route puts the real declaration inside the base's braces, so
/// it compiles and just leaves a public type behind that is not an API and that nothing references.
/// <c>Nap.Zoomies</c> is this cell, and it lives in <c>:test-models</c> because only a klib
/// declaration is ever <em>admitted</em> by the closure. A module-local nested arm
/// (<c>Issue54Shape.Empty</c>, <c>NestedShape.Empty</c>, <c>Pulse.Flat</c>) reaches neither bucket
/// and is clean today.
/// </para>
///
/// <para>
/// The control cell is <c>Issue54Shapes</c>, an ordinary top-level object that is nobody's
/// subclass. It must keep its normal static class and keep working, so a fix cannot be
/// "stop emitting static classes for objects".
/// </para>
///
/// <para>
/// Oreo folds his paws under and becomes a loaf: no payload, exactly one of him, forever. Mylo does
/// not nap so much as stop moving at speed, then start again.
/// </para>
/// </summary>
public class SealedSubclassObjectTests
{
    private static Type[] TypesNamed(string name) =>
        typeof(FlatShape).Assembly.GetTypes().Where(type => type.Name == name).ToArray();

    // A C# static class is `abstract sealed` in metadata. That pair is how an empty residue static
    // class is told apart from the sealed route's real `public sealed class` declaration.
    private static bool IsStaticClass(Type type) => type.IsAbstract && type.IsSealed;

    // ---- Route one: the sibling object subclass. Fatal, CS0101. ----

    /// <summary>
    /// The duplicate itself. Exactly one type in <c>TestLibrary.Issue54</c> is called <c>Loaf</c>,
    /// it belongs to the sealed route, and it is not a static class.
    /// </summary>
    [Fact]
    public void Loaf_SiblingObjectSubclass_IsDeclaredExactlyOnce()
    {
        Type[] loaves = TypesNamed("Loaf")
            .Where(type => type.Namespace == "TestLibrary.Issue54")
            .ToArray();

        Assert.Single(loaves);
        Assert.False(IsStaticClass(loaves[0]), "Loaf must not be emitted as an empty static class");
        Assert.Equal(typeof(FlatShape), loaves[0].BaseType);
    }

    /// <summary>
    /// A sibling stays at namespace level, exactly as the <c>Label</c> sibling data class does. The
    /// sealed route owning it must not push it inside <c>FlatShape</c>.
    /// </summary>
    [Fact]
    public void Loaf_SiblingObjectSubclass_StaysAtNamespaceLevel()
    {
        Assert.Null(typeof(Loaf).DeclaringType);
    }

    /// <summary>
    /// Return position, spelled as the concrete arm. With the duplicate present there are two
    /// candidates called <c>Loaf</c> and this reference does not resolve at all.
    /// </summary>
    [Fact]
    public void Loaf_SiblingObjectSubclassAtAReturnPosition_IsStillTheSealedBaseUnderneath()
    {
        using var factory = new FlatShapeFactory();

        using Loaf oreo = factory.Loaf();

        Assert.IsAssignableFrom<FlatShape>(oreo);
    }

    /// <summary>
    /// Sealed base at a top-level return: the discriminator has one arm to land on, and it is the
    /// sealed route's declaration.
    /// </summary>
    [Fact]
    public void FlatLoaf_SealedBaseAtATopLevelReturn_DiscriminatesToTheSiblingObjectArm()
    {
        using FlatShape flat = FlatShapeSample.FlatLoaf();

        Assert.IsType<Loaf>(flat);
    }

    /// <summary>
    /// ADR-009 gives a <c>data object</c> arm value equality, and the singleton has to compare
    /// equal to itself across two separate crossings. This is also the member set the CS0708 half of
    /// the reported cascade complains about, so it must live on a real instance type.
    /// </summary>
    [Fact]
    public void Loaf_DataObjectArm_ComparesEqualAcrossTwoCrossings()
    {
        using var factory = new FlatShapeFactory();

        using Loaf first = factory.Loaf();
        using Loaf second = factory.Loaf();

        Assert.Equal(first, second);
    }

    /// <summary>
    /// The sibling <c>data class</c> arm in the same hierarchy. Already correct today, and a fix
    /// that trades one sibling kind for the other has to be visible.
    /// </summary>
    [Fact]
    public void Label_SiblingDataClassArm_IsStillDeclaredExactlyOnce()
    {
        Type[] labels = TypesNamed("Label")
            .Where(type => type.Namespace == "TestLibrary.Issue54")
            .ToArray();

        Assert.Single(labels);
        Assert.False(IsStaticClass(labels[0]));
        Assert.Equal(typeof(FlatShape), labels[0].BaseType);
    }

    // ---- Route two: the cross-module nested object subclass. Non-fatal residue. ----

    /// <summary>
    /// The bogus empty static class, asserted as an absence. One <c>Zoomies</c> exists, the sealed
    /// route's, nested inside <c>Nap</c>. Nothing named <c>Zoomies</c> sits at namespace level.
    /// </summary>
    [Fact]
    public void Zoomies_NestedObjectSubclassAcrossTheKlibBoundary_IsDeclaredExactlyOnceAndStaysNested()
    {
        Type[] zoomies = TypesNamed("Zoomies");

        Assert.Single(zoomies);
        Assert.Equal(typeof(Nap), zoomies[0].DeclaringType);
        Assert.False(IsStaticClass(zoomies[0]), "Zoomies must not be emitted as an empty static class");
    }

    /// <summary>
    /// Same absence, said name-agnostically at the namespace: no residue static class may be left
    /// behind in <c>TestLibrary.Models</c> by the OBJECT bucket, whatever it ends up called.
    /// </summary>
    [Fact]
    public void ModelsNamespace_HoldsNoEmptyStaticClass()
    {
        string[] residue = typeof(FlatShape).Assembly
            .GetTypes()
            .Where(type => type.Namespace == "TestLibrary.Models")
            .Where(type => type.DeclaringType == null && IsStaticClass(type))
            .Where(type => type.GetMembers(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly).Length == 0)
            .Select(type => type.Name)
            .OrderBy(name => name)
            .ToArray();

        Assert.Empty(residue);
    }

    /// <summary>
    /// The cross-module sealed base still discriminates onto the object arm, so the surviving
    /// declaration is the usable one rather than merely the only one.
    /// </summary>
    [Fact]
    public void Nap_CrossModuleSealedBaseAtAReturnPosition_DiscriminatesToTheObjectArm()
    {
        using var newsroom = new Newsroom();

        using Nap nap = newsroom.Nap();

        Assert.IsType<Nap.Zoomies>(nap);
    }

    /// <summary>
    /// Control arm of the same cross-module hierarchy: a nested <c>CLASS</c> subclass takes the
    /// SEALED_SUBCLASS bucket and has always been declared once. Its payload proves the sealed route
    /// really owns this hierarchy rather than the type merely existing.
    /// </summary>
    [Fact]
    public void Deep_NestedDataClassArmAcrossTheKlibBoundary_IsDeclaredOnceAndReadsItsPayload()
    {
        using var newsroom = new Newsroom();

        using Nap.Deep deep = newsroom.DeepNap();

        Assert.Single(TypesNamed("Deep"));
        Assert.Equal(720, deep.Minutes);
    }

    // ---- Control: an ordinary top-level object is untouched. ----

    /// <summary>
    /// <c>Issue54Shapes</c> is a top-level object that subclasses nothing, so it keeps the ordinary
    /// ADR-007 static class. The fix must exclude sealed subclasses specifically, not objects.
    /// </summary>
    [Fact]
    public void Issue54Shapes_PlainTopLevelObject_KeepsItsStaticClass()
    {
        Assert.True(IsStaticClass(typeof(Issue54Shapes)));
        Assert.Null(typeof(Issue54Shapes).DeclaringType);
    }

    /// <summary>
    /// And it still calls through. An empty surviving static class would pass the shape assertion
    /// above on its own.
    /// </summary>
    [Fact]
    public void Issue54Shapes_PlainTopLevelObject_StillCallsThrough()
    {
        using Issue54Shape shape = Issue54Shapes.Pick(0);

        Assert.Equal("empty", Issue54Shapes.Describe(shape));
    }
}

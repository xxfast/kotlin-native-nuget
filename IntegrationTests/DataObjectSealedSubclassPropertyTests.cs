using TestLibrary.Issue54;

namespace IntegrationTests;

/// <summary>
/// A <c>data object</c> arm of a sealed class drops <em>every</em> C# property, while the ADR-062
/// property planner and <c>SealedClassExports</c> both keep it. The Kotlin side therefore exports
/// <c>nestedshape_empty_get_sides</c> with nothing on the C# half to call it, and
/// <c>ForwardAbiContract</c> aborts the KSP run before anything is packed. That abort is the red
/// signal for this file; after the fix a <c>data object</c> arm binds its properties exactly as a
/// <c>data class</c> arm does.
/// <para>
/// Three cells: the primitive property on the <c>data object</c> arm (no conversion at the seam),
/// a reference-typed property on the same arm (conversion at the seam), and the same property on
/// the <c>data class</c> arm as the control that already binds today.
/// </para>
/// <para>
/// Assertions reach through the concrete arm, never the base. <c>CirSealedClass</c> carries no
/// properties at all, so the abstract <c>sides</c> on <c>NestedShape</c> renders no C# member. That
/// is a separate, pre-existing gap; <c>((NestedShape)x).Sides</c> would fail for the wrong reason.
/// </para>
/// <para>
/// Mylo sprawls: no shape, zero sides, and an opinion about it. Oreo curls into a circle, which has
/// no side count at all.
/// </para>
/// </summary>
public class DataObjectSealedSubclassPropertyTests
{
    /// <summary>
    /// The bug: a primitive property on the <c>data object</c> arm. Mylo's sprawl has zero sides.
    /// </summary>
    [Fact]
    public void Empty_PrimitivePropertyOnADataObjectArm_BindsAndReadsBack()
    {
        using NestedShape shape = NestedShapeSample.EmptyShape();

        var empty = Assert.IsType<NestedShape.Empty>(shape);
        Assert.Equal(0, empty.Sides);
    }

    /// <summary>
    /// The same arm at a reference type, the cell that needs conversion at the seam.
    /// </summary>
    [Fact]
    public void Empty_ReferencePropertyOnADataObjectArm_RoundTripsItsValue()
    {
        using NestedShape shape = NestedShapeSample.EmptyShape();

        var empty = Assert.IsType<NestedShape.Empty>(shape);
        Assert.Equal("sprawled", empty.Note);
    }

    /// <summary>
    /// Control: the same property on the <c>data class</c> arm binds today and must keep binding, so
    /// a fix that restores parity is distinguishable from one that trades one broken arm for
    /// another. Oreo is a circle, so his side count is null.
    /// </summary>
    [Fact]
    public void Circle_NullablePropertyOnADataClassArm_StillBindsAndReadsNull()
    {
        using NestedShape shape = NestedShapeSample.AnyShape(2.0);

        var circle = Assert.IsType<NestedShape.Circle>(shape);
        Assert.Null(circle.Sides);
        Assert.Equal(2.0, circle.Radius);
    }

    /// <summary>
    /// The <c>data object</c> arm keeps the members it already had. Guards against a fix that swaps
    /// the empty property list for a plan lookup and loses <c>ToString</c> on the way.
    /// </summary>
    [Fact]
    public void Empty_DataObjectArm_StillCarriesItsDataMembers()
    {
        using NestedShape shape = NestedShapeSample.EmptyShape();

        var empty = Assert.IsType<NestedShape.Empty>(shape);
        Assert.Equal("Empty", empty.ToString());
    }
}

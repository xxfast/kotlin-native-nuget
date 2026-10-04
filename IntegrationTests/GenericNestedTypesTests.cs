using System;
using TestLibrary.Nested;

namespace IntegrationTests;

/// <summary>
/// ADR-196: a generic class nested in a non-generic owner is <c>Tote.Purse&lt;T&gt;</c>, and every
/// declaration nested in a generic class lives on a non-generic <c>static class Teapot</c> holder
/// beside <c>Teapot&lt;T&gt;</c>, the Kotlin scope spelled letter for letter. An <c>inner</c> class
/// of the generic owner captures <c>T</c>, so it is flattened onto the holder with the captured
/// parameter first and constructed from a <c>Teapot&lt;T&gt;</c>.
///
/// Oreo carries the tote; Mylo minds the teapot.
/// </summary>
public class GenericNestedTypesTests
{
    [Fact]
    public void GenericNestedInNonGenericOwner_RoundTripsAConvertedAndAPlainT()
    {
        using var oreo = new Tote.Purse<string>("Oreo");
        Assert.Equal("purse:Oreo", oreo.Describe());
        Assert.Equal("Mylo", oreo.Swap("Mylo"));
        Assert.Equal("Oreo", oreo.Item);

        using var count = new Tote.Purse<int>(3);
        Assert.Equal(3, count.Item);
        Assert.Equal(7, count.Swap(7));
    }

    [Fact]
    public void GenericInnerInNonGenericOwner_TakesTheOuterFirst()
    {
        using var tote = new Tote("Oreo");
        using var charm = new Tote.Charm<int>(tote, 7);
        Assert.Equal("Oreo:7", charm.Label());
        Assert.Equal(7, charm.Tag);
    }

    [Fact]
    public void NestedOfGenericOwner_LivesOnTheNonGenericHolder()
    {
        using var lid = new Teapot.Lid(7);
        Assert.Equal("lid#7", lid.Describe());
        Assert.Equal(3, Teapot.Defaults.Cups);
        Assert.Equal(Teapot.Blend.Black, Enum.Parse<Teapot.Blend>("Black"));
        using var cozy = new Teapot.Cozy<string>("Mylo");
        Assert.Equal("Mylo", cozy.Pattern);

        Assert.Null(typeof(Teapot<>).GetNestedType("Lid"));
        Assert.True(typeof(Teapot).IsAbstract && typeof(Teapot).IsSealed);
        Assert.Same(typeof(Teapot), typeof(Teapot.Lid).DeclaringType);
    }

    [Fact]
    public void GenericOwnerAndOutsiders_ReturnTheSameNestedType()
    {
        using var teapot = new Teapot<int>(5);
        using Teapot.Lid own = teapot.LidAt(1);
        using Teapot.Lid spare = TeaShop.Spare();
        Assert.Equal(1, own.Number);
        Assert.Equal(9, spare.Number);
        Assert.Equal(9, TeaShop.NumberOf(spare));
        Assert.Equal(Teapot.Blend.Black, teapot.BlendOf());
    }

    [Fact]
    public void InnerOfGenericOwner_IsFlattenedOntoTheHolder_AndReadsTheCapturedT()
    {
        using var teapot = new Teapot<string>("Oreo");
        using var strainer = new Teapot.Strainer<string>(teapot, 2);
        Assert.Equal("Oreo", strainer.Peek());
        Assert.Equal(4, strainer.Twice());

        using var counted = new Teapot<int>(5);
        using var infuser = new Teapot.Infuser<int, string>(counted, "Mylo");
        Assert.Equal("5/Mylo", infuser.Both());
        Assert.Same(typeof(Teapot), typeof(Teapot.Strainer<>).DeclaringType);
    }
}

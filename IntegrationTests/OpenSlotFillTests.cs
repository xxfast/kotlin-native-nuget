using TestLibrary;
using TestLibrary.Membergeneric;

namespace IntegrationTests;

/// <summary>
/// ADR-216 (second amendment): an open exported generic instantiation nested inside another one
/// (<c>Basket&lt;Basket&lt;T&gt;&gt;</c>, <c>List&lt;Basket&lt;T&gt;&gt;</c>,
/// <c>Basket&lt;Pickup&lt;T&gt;&gt;</c>, <c>Basket&lt;Cabinet&lt;T&gt;&gt;</c>) reaches C# through
/// a position other than a member's own result, before this process ever held the inner
/// instantiation. Reading the inner value needs <c>NugetFactory&lt;Basket&lt;X&gt;&gt;</c>, and the
/// generated body that brought the value across fills it.
///
/// Factory slots are process-global, and a wrapper's static constructor fills its own slot. So each
/// row uses a type argument no other test in this assembly puts in a <c>Basket</c>, a
/// <c>Pickup</c> or a <c>Cabinet</c>; a shared one could go green because another test filled the
/// slot first.
///
/// Oreo (black with the white middle) packs the satchel; Mylo (brown and creamy) unpacks it again.
/// </summary>
public class OpenSlotFillTests
{
    [Fact]
    public void Getter_BasketInABasket_ReadsTheInnerBasket()
    {
        using var satchel = new Satchel<short>(7);
        using Basket<Basket<short>> nested = satchel.Nested;
        using Basket<short> inner = nested.Item;
        Assert.Equal((short)7, inner.Item);
    }

    [Fact]
    public void Getter_ThreeLevels_ReadsEveryLevel()
    {
        using var satchel = new Satchel<char>('o');
        using Basket<Basket<Basket<char>>> deep = satchel.Deep;
        using Basket<Basket<char>> middle = deep.Item;
        using Basket<char> inner = middle.Item;
        Assert.Equal('o', inner.Item);
    }

    [Fact]
    public void Getter_ListElement_ReadsEachBasket()
    {
        using var satchel = new Satchel<sbyte>(3);
        IReadOnlyList<Basket<sbyte>> baskets = satchel.Baskets;
        Assert.Equal(new sbyte[] { 3, 3 }, baskets.Select(b => b.Item));
        foreach (Basket<sbyte> b in baskets) b.Dispose();
    }

    [Fact]
    public void Getter_MapValue_ReadsOreosBasket()
    {
        using var satchel = new Satchel<byte>(2);
        IReadOnlyDictionary<string, Basket<byte>> keyed = satchel.Keyed;
        using Basket<byte> oreos = keyed["Oreo"];
        Assert.Equal((byte)2, oreos.Item);
    }

    [Fact]
    public void Getter_NullableBasketInABasket_ReadsTheInnerBasket()
    {
        using var feather = new Feather("black and white");
        using var satchel = new Satchel<Feather>(feather);
        using Basket<Basket<Feather>>? maybe = satchel.Maybe;
        Assert.NotNull(maybe);
        using Basket<Feather> inner = maybe!.Item;
        using Feather inside = inner.Item;
        Assert.Equal("black and white", inside.Colour);
    }

    [Fact]
    public void Getter_GenericSealedInABasket_ReadsTheArm()
    {
        using var satchel = new Satchel<uint>(9);
        using Basket<Pickup<uint>> picked = satchel.Picked;
        using Pickup<uint> pickup = picked.Item;
        Assert.Equal(9u, Assert.IsType<Pickup.Fluffed<uint>>(pickup).Pet);
    }

    [Fact]
    public void Getter_GenericAbstractClassInABasket_ReadsTheCabinet()
    {
        using var satchel = new Satchel<ulong>(5);
        using Basket<Cabinet<ulong>> cabinet = satchel.Cabinet;
        using Cabinet<ulong> inner = cabinet.Item;
        Assert.Equal(5UL, inner.Item);
        Assert.Equal("dresser", inner.Label);
    }

    [Fact]
    public void ClassCallbackArgument_ReadsTheInnerBasket()
    {
        using var yarn = new Yarn("brown and creamy");
        using var satchel = new Satchel<Yarn>(yarn);
        string? colour = null;
        satchel.Visit(outer =>
        {
            using Basket<Yarn> inner = outer.Item;
            using Yarn inside = inner.Item;
            colour = inside.Colour;
        });
        Assert.Equal("brown and creamy", colour);
    }

    [Fact]
    public void MemberCallbackArgument_ReadsTheInnerBasket()
    {
        using var porter = new Porter();
        bool seen = false;
        porter.Visit(true, outer =>
        {
            using Basket<bool> inner = outer.Item;
            seen = inner.Item;
        });
        Assert.True(seen);
    }

    [Fact]
    public async Task FlowElement_ReadsTheInnerBasket()
    {
        using var porter = new Porter();
        int count = 0;
        await foreach (Basket<Basket<float>> outer in porter.Stream(1.5f))
        {
            using (outer)
            using (Basket<float> inner = outer.Item)
            {
                Assert.Equal(1.5f, inner.Item);
            }
            count++;
        }
        Assert.Equal(1, count);
    }

    [Fact]
    public void ReturnedLambdaResult_ReadsTheInnerBasket()
    {
        using KotlinFunc<Basket<Basket<ushort>>> later = Satchels.Later((ushort)4);
        using Basket<Basket<ushort>> outer = later.Invoke();
        using Basket<ushort> inner = outer.Item;
        Assert.Equal((ushort)4, inner.Item);
    }

    [Fact]
    public async Task SuspendResult_ReadsTheInnerBasket()
    {
        using var porter = new Porter();
        using var pebble = new Pebble(12);
        using Basket<Basket<Pebble>> outer = await porter.FetchAsync(pebble);
        using Basket<Pebble> inner = outer.Item;
        using Pebble inside = inner.Item;
        Assert.Equal(12, inside.Weight);
    }

    [Fact]
    public void ResultPayload_ReadsTheInnerBasket()
    {
        using var porter = new Porter();
        using var acorn = new Acorn(3);
        using Basket<Basket<Acorn>> outer = porter.Nest(acorn);
        using Basket<Acorn> inner = outer.Item;
        using Acorn inside = inner.Item;
        Assert.Equal(3, inside.Size);
    }

    [Fact]
    public void TryResultPayload_ReadsTheInnerBasket()
    {
        using var porter = new Porter();
        using var bobbin = new Bobbin(40);
        Assert.True(porter.TryNest(bobbin, out Basket<Basket<Bobbin>>? outer, out _));
        using (outer)
        using (Basket<Bobbin> inner = outer!.Item)
        using (Bobbin inside = inner.Item)
        {
            Assert.Equal(40, inside.Length);
        }
    }

    [Fact]
    public void MemberResult_ConsumerHeldAbstractCabinet_ReadsItBack()
    {
        using var porter = new Porter();
        using var thimble = new Thimble("Mylo");
        using var dresser = new Dresser<Thimble>(thimble);
        using Basket<Cabinet<Thimble>> wrapped = porter.WrapCabinet<Thimble>(dresser);
        using Cabinet<Thimble> inner = wrapped.Item;
        using Thimble inside = inner.Item;
        Assert.Equal("Mylo", inside.Owner);
        Assert.Equal("dresser", inner.Label);
    }

    [Fact]
    public void ResultOnlyTypeParameter_ConjuredNull_ReadsTheInnerBasket()
    {
        using var porter = new Porter();
        using Basket<Basket<Porter?>> outer = porter.Conjure<Porter?>();
        using Basket<Porter?> inner = outer.Item;
        Assert.Null(inner.Item);
    }
}

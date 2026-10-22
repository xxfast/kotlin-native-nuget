using System.Reflection;
using TestLibrary.Membergeneric;

namespace IntegrationTests;

/// <summary>
/// ADR-197: a member function's own type parameter (<c>fun &lt;T&gt; echo(item: T): T</c>) is a
/// C# generic method on every owner that routes one: an ordinary class, its companion, an object,
/// a generic class, an abstract class, a sealed base and a sealed arm. Each <c>T</c> crosses as a
/// boxed handle, so a builtin argument and a generated wrapper both come back as themselves.
/// </summary>
public class MemberGenericMethodTests
{
    [Fact]
    public void OrdinaryClass_BuiltinTypeArguments_RoundTrip()
    {
        using var groomer = new Groomer();

        Assert.Equal(4, groomer.Echo(4));
        Assert.Equal("x", groomer.Echo("x"));
        Assert.Equal(2.5, groomer.Echo(2.5));
        Assert.True(groomer.Echo(true));
        Assert.Null(groomer.Echo<string?>(null));
        Assert.Equal("1+two", groomer.Pair(1, "two"));
    }

    [Fact]
    public void OrdinaryClass_WrapperTypeArgument_ComesBackAsTheSameKotlinObject()
    {
        using var groomer = new Groomer();
        using var oreo = new Tabby("Oreo", 3);

        using Tabby echoed = groomer.Echo(oreo);
        Assert.Equal("Oreo", echoed.Name);
        using Tabby adopted = groomer.Adopt(oreo);
        Assert.Equal(3, adopted.Tricks);
        Assert.Equal(3, groomer.Rank(oreo));
    }

    [Fact]
    public void OrdinaryClass_ReturnOnlyTypeParameter_ReadsNull()
    {
        using var groomer = new Groomer();

        Assert.Null(groomer.Nothing<string>());
        Assert.Null(groomer.Nothing<Tabby>());
    }

    [Fact]
    public void OrdinaryClass_ThrowingMember_SurfacesTheKotlinException()
    {
        using var groomer = new Groomer();

        Exception thrown = Assert.ThrowsAny<Exception>(() => groomer.Refuse(4));
        Assert.Contains("Mylo refuses to be brushed: 4", thrown.Message);
    }

    [Fact]
    public void OrdinaryClass_ResultReturn_ThrowsAndTries()
    {
        using var groomer = new Groomer();

        Assert.Equal("comb", groomer.Wrap("comb"));
        Assert.ThrowsAny<Exception>(() => groomer.Wrap("knot"));

        Assert.True(groomer.TryWrap(7, out int value, out Exception? none));
        Assert.Equal(7, value);
        Assert.Null(none);
        Assert.False(groomer.TryWrap("knot", out _, out Exception? failure));
        Assert.Contains("a knot", failure!.Message);
    }

    [Fact]
    public void CompanionAndObject_StaticGenericMembers_RoundTrip()
    {
        Assert.Equal(7, Groomer.First(7));
        Assert.Equal("Mylo", Groomer.First("Mylo"));
        Assert.Equal(9L, Salon.Echo(9L));
        using var oreo = new Tabby("Oreo", 3);
        using Tabby styled = Salon.Echo(oreo);
        Assert.Equal("Oreo", styled.Name);
    }

    [Fact]
    public void GenericClass_MethodTypeParameterBesideTheClassOne_RoundTrips()
    {
        using var basket = new Basket<string>("yarn");

        Assert.Equal(3, basket.Swap(3));
        Assert.Equal("yarn&2", basket.Describe(2));
        using var oreo = new Tabby("Oreo", 3);
        using Tabby swapped = basket.Swap(oreo);
        Assert.Equal("Oreo", swapped.Name);
    }

    [Fact]
    public void AbstractClass_OverrideDispatchesThroughTheBase()
    {
        using var soft = new SoftBrush();
        Brush brush = soft;

        Assert.Equal(5, brush.Stroke(5));
        Assert.Equal("fur", brush.Stroke("fur"));
    }

    [Fact]
    public void SealedBaseAndArm_GenericMembers_RoundTrip()
    {
        using var fetch = new Chore.Fetch("ball");

        Assert.Equal(7, fetch.Pick(7));
        Assert.Equal("ball", fetch.Pick("ball"));
        Chore chore = fetch;
        Assert.Equal(3, chore.Carry(3));
        using var oreo = new Tabby("Oreo", 3);
        using Tabby carried = chore.Carry(oreo);
        Assert.Equal("Oreo", carried.Name);
    }

    // ADR-015 amendment on the member route: `Number` and `Comparable` have no C# spelling, so the
    // `where` clause drops them and C# can pass a T outside them. The Kotlin read of the box is a
    // checked cast to the bound, so the call fails there, before the body calls `toInt()`.
    [Fact]
    public void BuiltinBound_SatisfyingTypeArgument_UsesTheBoundsApi()
    {
        using var groomer = new Groomer();
        Assert.Equal(4, groomer.Portion(4));
        Assert.Equal(2, groomer.Portion(2.5));
        Assert.Throws<KotlinArgumentException>(() => groomer.Portion(-1));
        Assert.Equal(1.5, Salon.Weigh(1.5f));
        using var basket = new Basket<string>("yarn");
        Assert.Equal(3.0, basket.Measure(3L));
        using var fetch = new Chore.Fetch("ball");
        Assert.Equal("yarn", fetch.Best("ball", "yarn"));
        Assert.Equal(9, fetch.Best(9, 4));
    }

    [Fact]
    public void BuiltinBound_TypeArgumentOutsideTheKotlinBound_IsRejectedAtTheRead()
    {
        using var groomer = new Groomer();
        Assert.Throws<KotlinInvalidCastException>(() => groomer.Portion(3u));
        Assert.Throws<KotlinInvalidCastException>(() => groomer.Portion("tuna"));
        Assert.Throws<KotlinInvalidCastException>(() => Salon.Weigh("tuna"));
        using var basket = new Basket<string>("yarn");
        Assert.Throws<KotlinInvalidCastException>(() => basket.Measure(3u));
        using var fetch = new Chore.Fetch("ball");
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);
        Assert.Throws<KotlinInvalidCastException>(() => fetch.Best(oreo, mylo));
    }

    [Fact]
    public void RenamedOverride_DispatchesThroughTheBase()
    {
        using var fetch = new Chore.Fetch("ball");
        Chore chore = fetch;
        Assert.Equal(5, chore.Carry(5));
        MethodInfo carry = typeof(Chore.Fetch).GetMethod("Carry")!;
        Assert.Equal(typeof(Chore), carry.GetBaseDefinition().DeclaringType);
        MethodInfo stroke = typeof(SoftBrush).GetMethod("Stroke")!;
        Assert.Equal(typeof(Brush), stroke.GetBaseDefinition().DeclaringType);
    }

    [Fact]
    public void Constraints_SpellTheKotlinBounds()
    {
        MethodInfo adopt = typeof(Groomer).GetMethod("Adopt")!;
        Assert.Equal(
            new[] { typeof(IFurry) },
            adopt.GetGenericArguments()[0].GetGenericParameterConstraints());

        MethodInfo rank = typeof(Groomer).GetMethod("Rank")!;
        Type[] ranked = rank.GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Contains(typeof(IFurry), ranked);
        Assert.Contains(typeof(ITricky), ranked);

        Assert.Empty(
            typeof(Groomer).GetMethod("Echo")!.GetGenericArguments()[0]
                .GetGenericParameterConstraints());
    }

    // ADR-212: a member's own T as a collection element. `Shuffle` reverses, so the order proves
    // the list was rebuilt on the Kotlin side and every element came back through its box.
    [Fact]
    public void CollectionElement_BuiltinList_RoundTrips()
    {
        using var deck = new Deck();

        Assert.Equal(new[] { 3, 2, 1 }, deck.Shuffle(new[] { 1, 2, 3 }));
        Assert.Equal(new[] { "Mylo", "Oreo" }, deck.Shuffle(new[] { "Oreo", "Mylo" }));
        Assert.Empty(deck.Shuffle(Array.Empty<int>()));
    }

    [Fact]
    public void CollectionElement_WrapperList_ComesBackAsTheSameKotlinObjects()
    {
        using var deck = new Deck();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        IReadOnlyList<Tabby> dealt = deck.Shuffle(new[] { oreo, mylo });
        Assert.Equal(2, dealt.Count);
        using (Tabby first = dealt[0]) Assert.Equal("Mylo", first.Name);
        using (Tabby second = dealt[1]) Assert.Equal("Oreo", second.Name);

        foreach (Tabby cat in deck.Groom(new[] { oreo }))
        {
            using (cat) Assert.Equal(3, cat.Tricks);
        }
    }

    [Fact]
    public void CollectionElement_NullableElement_PadsWithNull()
    {
        using var deck = new Deck();

        Assert.Equal(new int?[] { 1, null, null }, deck.Pad(new int?[] { 1, null }));
        Assert.Equal(new string?[] { "Oreo", null }, deck.Pad(new string?[] { "Oreo" }));
    }

    [Fact]
    public void CollectionElement_SetAndMap_RoundTrip()
    {
        using var deck = new Deck();

        IReadOnlySet<string> naps = deck.Dedupe(new HashSet<string> { "Oreo", "Mylo" });
        Assert.True(naps.SetEquals(new[] { "Mylo", "Oreo" }));

        var treats = new Dictionary<string, int> { ["Oreo"] = 2, ["Mylo"] = 3 };
        Assert.Equal(2, deck.Tally(treats));
        IReadOnlyDictionary<string, int> kept = deck.Keep(treats);
        Assert.Equal(3, kept["Mylo"]);
        Assert.Equal(2, kept["Oreo"]);

        var ages = new Dictionary<int, string?> { [2] = "Oreo", [5] = null };
        IReadOnlyDictionary<int, string?> keptAges = deck.Keep(ages);
        Assert.Equal("Oreo", keptAges[2]);
        Assert.Null(keptAges[5]);
    }

    [Fact]
    public void CollectionElement_BoundedAndUnconstrainedKey_SpellTheKotlinShape()
    {
        MethodInfo groom = typeof(Deck).GetMethod("Groom")!;
        Assert.Equal(
            new[] { typeof(IFurry) },
            groom.GetGenericArguments()[0].GetGenericParameterConstraints());

        // `fun <K, V> loose(map: Map<K, V>)`: K may be null, so it stays a NULLABLE_MAP_KEY refusal.
        Assert.Null(typeof(Deck).GetMethod("Loose"));
    }

    // ADR-216: a member's own T inside an exported generic class. A returned `Basket<T>` is owned
    // by the caller; a passed one is borrowed, so Oreo's basket is still usable after `Unpack`.
    [Fact]
    public void BoxedTypeParameter_BuiltinBaskets_RoundTrip()
    {
        using var groomer = new Groomer();

        using Basket<int> treats = groomer.Basket(3);
        Assert.Equal(3, treats.Item);
        Assert.Equal(3, groomer.Unpack(treats));
        Assert.Equal(3, treats.Item);

        using Basket<string> toy = groomer.Basket("Mylo's Milo Mouse");
        Assert.Equal("Mylo's Milo Mouse", groomer.Unpack(toy));
        Assert.Equal("Mylo's Milo Mouse", toy.Item);

        using var handMade = new Basket<double>(2.5);
        Assert.Equal(2.5, groomer.Unpack(handMade));
    }

    [Fact]
    public void BoxedTypeParameter_WrapperBasket_ComesBackAsTheSameKotlinObject()
    {
        using var groomer = new Groomer();
        using var oreo = new Tabby("Oreo", 3);

        using Basket<Tabby> cat = groomer.Basket(oreo);
        using (Tabby inside = cat.Item) Assert.Equal("Oreo", inside.Name);
        using (Tabby back = groomer.Unpack(cat)) Assert.Equal(3, back.Tricks);
        using Tabby brushed = groomer.BrushBasket(cat);
        Assert.Equal("Oreo", brushed.Name);
    }

    [Fact]
    public void BoxedTypeParameter_Bound_SpellsTheKotlinBound()
    {
        MethodInfo brush = typeof(Groomer).GetMethod("BrushBasket")!;
        Type t = brush.GetGenericArguments()[0];
        Assert.Equal(new[] { typeof(IFurry) }, t.GetGenericParameterConstraints());
        Assert.Equal(typeof(Basket<>).MakeGenericType(t), brush.GetParameters()[0].ParameterType);
        Assert.Equal(t, brush.ReturnType);

        MethodInfo basket = typeof(Groomer).GetMethod("Basket")!;
        Type u = basket.GetGenericArguments()[0];
        Assert.Equal(typeof(Basket<>).MakeGenericType(u), basket.ReturnType);
        Assert.Empty(u.GetGenericParameterConstraints());
    }

    [Fact]
    public void BoxedTypeParameter_NullableBasket_PassesNullThrough()
    {
        using var groomer = new Groomer();

        Assert.Null(groomer.MaybeBasket<int>(null));
        using Basket<string> yarn = groomer.Basket("yarn");
        using Basket<string>? same = groomer.MaybeBasket(yarn);
        Assert.NotNull(same);
        Assert.Equal("yarn", same!.Item);
    }

    [Fact]
    public void BoxedTypeParameter_NestedBaskets_PackAndUnpack()
    {
        using var groomer = new Groomer();

        using Basket<Basket<int>> doubled = groomer.DoubleBasket(1);
        using (Basket<int> inner = doubled.Item) Assert.Equal(1, inner.Item);
        using Basket<int> unpacked = groomer.NestBasket(doubled);
        Assert.Equal(1, unpacked.Item);

        using var oreo = new Tabby("Oreo", 3);
        using Basket<Basket<Tabby>> nested = groomer.DoubleBasket(oreo);
        using Basket<Tabby> once = groomer.NestBasket(nested);
        using (Tabby inside = once.Item) Assert.Equal("Oreo", inside.Name);
    }

    [Fact]
    public void BoxedTypeParameter_ListOfBaskets_RoundTrips()
    {
        using var groomer = new Groomer();

        IReadOnlyList<Basket<int>> treats = groomer.Baskets(new[] { 1, 2, 3 });
        Assert.Equal(3, treats.Count);
        Assert.Equal(new[] { 3, 2, 1 }, groomer.UnpackAll(treats));
        Assert.Equal(new[] { 1, 2, 3 }, treats.Select(b => b.Item));
        foreach (Basket<int> b in treats) b.Dispose();

        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);
        IReadOnlyList<Basket<Tabby>> cats = groomer.Baskets(new[] { oreo, mylo });
        IReadOnlyList<Tabby> unpacked = groomer.UnpackAll(cats);
        Assert.Equal(new[] { "Mylo", "Oreo" }, unpacked.Select(c => c.Name));
        foreach (Tabby c in unpacked) c.Dispose();
        foreach (Basket<Tabby> b in cats) b.Dispose();
    }

    [Fact]
    public void BoxedTypeParameter_GenericSealedReturn_PicksTheArm()
    {
        using var groomer = new Groomer();
        using var oreo = new Tabby("Oreo", 3);

        using Pickup<Tabby> ready = groomer.PickUp(oreo, true);
        using (Tabby pet = Assert.IsType<Pickup.Fluffed<Tabby>>(ready).Pet)
        {
            Assert.Equal("Oreo", pet.Name);
        }

        using Pickup<int> waiting = groomer.PickUp(5, false);
        Assert.Equal("not yet: 5", Assert.IsType<Pickup.Waitlisted<int>>(waiting).Note);
    }

    [Fact]
    public void BoxedTypeParameter_EveryOwner_ReturnsABasket()
    {
        using (Basket<int> first = Groomer.FirstBasket(7)) Assert.Equal(7, first.Item);
        using (Basket<string> shown = Salon.Exhibit("Oreo")) Assert.Equal("Oreo", shown.Item);

        using var fetch = new Chore.Fetch("ball");
        using (Basket<long> stowed = fetch.Stow(9L)) Assert.Equal(9L, stowed.Item);

        using var yarn = new Basket<string>("yarn");
        using (Basket<int> regifted = yarn.Regift(3)) Assert.Equal(3, regifted.Item);
        using var oreo = new Tabby("Oreo", 3);
        using Basket<Tabby> gift = yarn.Regift(oreo);
        using (Tabby inside = gift.Item) Assert.Equal("Oreo", inside.Name);
    }

    // ADR-216: a member's own T inside a `Result` payload, with ADR-195's `Try` twin.
    [Fact]
    public void ResultPayload_ListOfT_ThrowsAndTries()
    {
        using var groomer = new Groomer();

        Assert.Equal(new[] { "Oreo", "Mylo" }, groomer.Gather(new List<string> { "Oreo", "Mylo" }));
        Assert.True(groomer.TryGather(new List<int> { 1, 2 }, out IReadOnlyList<int>? value, out Exception? none));
        Assert.Equal(new[] { 1, 2 }, value);
        Assert.Null(none);

        Exception thrown = Assert.ThrowsAny<Exception>(() => groomer.Spill(new List<string> { "Oreo" }));
        Assert.Contains("Mylo spilled 1", thrown.Message);
        Assert.False(groomer.TrySpill(new List<int> { 1 }, out _, out Exception? failure));
        Assert.Contains("Mylo spilled 1", failure!.Message);
    }

    [Fact]
    public void ResultPayload_WrapperList_ComesBackAsTheSameKotlinObjects()
    {
        using var groomer = new Groomer();
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);

        IReadOnlyList<Tabby> gathered = groomer.Gather(new[] { oreo, mylo });
        Assert.Equal(new[] { "Oreo", "Mylo" }, gathered.Select(c => c.Name));
        foreach (Tabby c in gathered) c.Dispose();
    }

    [Fact]
    public void ResultPayload_NullableSetMapAndNestedList_RoundTrip()
    {
        using var groomer = new Groomer();

        Assert.Equal(new int?[] { 1, null, null }, groomer.Queue(new int?[] { 1, null }));
        Assert.True(groomer.TryQueue(new string?[] { "Oreo" }, out IReadOnlyList<string?>? seats, out _));
        Assert.Equal(new string?[] { "Oreo", null }, seats);

        IReadOnlySet<string> herd = groomer.Herd(new HashSet<string> { "Oreo", "Mylo" });
        Assert.True(herd.SetEquals(new[] { "Mylo", "Oreo" }));

        var treats = new Dictionary<string, int> { ["Oreo"] = 2, ["Mylo"] = 3 };
        IReadOnlyDictionary<string, int> ledger = groomer.Ledger(treats);
        Assert.Equal(2, ledger["Oreo"]);
        Assert.Equal(3, ledger["Mylo"]);

        IReadOnlyList<IReadOnlyList<int>> litters = groomer.Litters(new[] { new[] { 1, 2 }, new[] { 3 } });
        Assert.Equal(new[] { 2, 1 }, litters[0]);
        Assert.Equal(new[] { 3 }, litters[1]);
    }

    [Fact]
    public void ResultPayload_Basket_ThrowsAndTries()
    {
        using var groomer = new Groomer();

        using (Basket<int> stashed = groomer.Stash(4)) Assert.Equal(4, stashed.Item);
        Assert.ThrowsAny<Exception>(() => groomer.Stash("knot"));
        Assert.False(groomer.TryStash("knot", out _, out Exception? failure));
        Assert.Contains("a knot in the basket", failure!.Message);
        Assert.True(groomer.TryStash("comb", out Basket<string>? comb, out _));
        using (comb) Assert.Equal("comb", comb!.Item);
    }

    // ADR-216 amendment: a class `T` inside a basket in a basket. Pre-existing on main: the inner
    // `Basket<int>` had no factory, so reading `.Item` threw `NotSupportedException`.
    [Fact]
    public void BoxedTypeParameter_ClassTypeParameterRebasket_ReadsTheInnerBasket()
    {
        using var treats = new Basket<int>(4);
        using Basket<Basket<int>> twice = treats.Rebasket();
        using Basket<int> inner = twice.Item;
        Assert.Equal(4, inner.Item);
    }
}

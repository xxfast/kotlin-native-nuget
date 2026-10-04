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
}

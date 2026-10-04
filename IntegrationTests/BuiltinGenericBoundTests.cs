using System.Reflection;
using TestLibrary.Rankings;

namespace IntegrationTests;

/// <summary>
/// Generic declarations bounded by Kotlin builtins (<c>T : Comparable&lt;T&gt;</c>,
/// <c>T : Number</c>). The bound has no C# spelling, so it is dropped from the <c>where</c> clause
/// (only <c>notnull</c> remains) and the declarations bind; this file compiling at all is the
/// first assertion, since <c>where T : Number</c> was CS0246 and the Comparable bound CS0234.
/// </summary>
public class BuiltinGenericBoundTests
{
    [Fact]
    public void Ranked_String_ComparesOnTheKotlinSide()
    {
        using var tuna = new Ranked<string>("tuna");
        Assert.Equal("tuna", tuna.Value);
        Assert.True(tuna.Outranks("salmon"));
        Assert.False(tuna.Outranks("whitefish"));
    }

    [Fact]
    public void Ranked_Int_ComparesOnTheKotlinSide()
    {
        using var oreo = new Ranked<int>(9);
        Assert.Equal(9, oreo.Value);
        Assert.True(oreo.Outranks(4));
    }

    [Fact]
    public void Tally_Int_ReadsTheNumberBack()
    {
        using var mylo = new Tally<int>(4);
        Assert.Equal(4, mylo.Value);
        Assert.Equal(4.0, mylo.AsDouble);
    }

    [Fact]
    public void Tally_Double_ReadsTheNumberBack()
    {
        using var oreo = new Tally<double>(2.5);
        Assert.Equal(2.5, oreo.Value);
        Assert.Equal(2.5, oreo.AsDouble);
    }

    // The generic-function route crosses T as a handle either way: a builtin as a box it unwraps
    // on the way back (Weigh<int> used to throw NotSupportedException, no factory for
    // System.Int32), a generated wrapper as its own handle.
    [Fact]
    public void Weigh_Int_ReturnsTheNumber()
    {
        Assert.Equal(4, Treats.Weigh(4));
    }

    [Fact]
    public void Weigh_Double_ReturnsTheNumber()
    {
        Assert.Equal(2.5, Treats.Weigh(2.5));
    }

    [Fact]
    public void Favourite_String_ReturnsTheString()
    {
        Assert.Equal("tuna", Treats.Favourite("tuna"));
    }

    [Fact]
    public void Nickname_String_ReturnsTheString()
    {
        Assert.Equal("Oreo", Treats.Nickname("Oreo"));
    }

    // The dropped bound lets C# pass a T outside it (Kotlin's unsigned types and String are not a
    // Number). The Kotlin read of the box is a checked cast to the bound, so the call fails there,
    // as the mapped ClassCastException, before the body ever sees the value. It used to be a
    // generic, unchecked cast: an identity function handed the value back, and a body calling
    // `toDouble()` would have dispatched on an object that is not a Number.
    [Fact]
    public void Weigh_UInt_IsRejectedByKotlinAtTheRead()
    {
        Assert.Throws<KotlinInvalidCastException>(() => Treats.Weigh(3u));
    }

    [Fact]
    public void Weigh_String_IsRejectedByKotlinAtTheRead()
    {
        Assert.Throws<KotlinInvalidCastException>(() => Treats.Weigh("tuna"));
    }

    [Fact]
    public void Portion_Int_UsesTheNumberApi()
    {
        Assert.Equal(4, Treats.Portion(4));
        Assert.Throws<KotlinArgumentException>(() => Treats.Portion(-1));
    }

    [Fact]
    public void Portion_UInt_IsRejectedBeforeTheBodyDispatches()
    {
        Assert.Throws<KotlinInvalidCastException>(() => Treats.Portion(3u));
    }

    // The class route reads its constructor argument through the same checked cast.
    [Fact]
    public void Tally_UInt_IsRejectedAtConstruction()
    {
        Assert.Throws<KotlinInvalidCastException>(() => new Tally<uint>(3u));
    }

    [Fact]
    public void Favourite_Treat_ReturnsTheSameKotlinObject()
    {
        using var tuna = new Treat("tuna");
        using Treat favourite = Treats.Favourite<Treat>(tuna);
        Assert.Equal("tuna", favourite.Name);
        using var salmon = new Treat("salmon");
        Assert.True(favourite.CompareTo(salmon) > 0);
    }

    [Fact]
    public void BuiltinBounds_LeaveNoTypeConstraint()
    {
        Assert.Empty(typeof(Ranked<>).GetGenericArguments()[0].GetGenericParameterConstraints());
        Assert.Empty(typeof(Tally<>).GetGenericArguments()[0].GetGenericParameterConstraints());

        MethodInfo weigh = typeof(Treats).GetMethod("Weigh")!;
        Assert.Empty(weigh.GetGenericArguments()[0].GetGenericParameterConstraints());
    }
}

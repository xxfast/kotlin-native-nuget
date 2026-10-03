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

    // The generic-function route crosses T as a handle and materialises the result through a
    // generated factory, so its type argument must be a generated wrapper: Weigh<int> compiles but
    // throws NotSupportedException (no factory for System.Int32), a separate known limit.
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

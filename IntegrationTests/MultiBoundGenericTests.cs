using System.Reflection;
using TestLibrary.Multibound;

namespace IntegrationTests;

/// <summary>
/// Generic declarations whose type parameter has several upper bounds
/// (<c>where T : Pet, T : Trainable</c>, <c>where T : Comparable&lt;T&gt;, T : Pet</c>). The
/// Kotlin half used to spell the owner by its first bound only, which no instance satisfies, so
/// the generated Kotlin did not compile; each test passes a <c>T</c> in and reads one back.
/// </summary>
public class MultiBoundGenericTests
{
    [Fact]
    public void Arena_TwoWrapperBounds_TakesAndReturnsT()
    {
        using var oreo = new Performer("Oreo", 3);
        using var mylo = new Performer("Mylo", 5);
        using var arena = new Arena<Performer>(oreo);

        using Performer champion = arena.Champion;
        Assert.Equal("Oreo", champion.Name);
        Assert.Equal("Oreo knows 3 tricks", arena.Summary);

        using Performer winner = arena.Challenge(mylo);
        Assert.Equal("Mylo", winner.Name);
        Assert.Equal(5, winner.Tricks);
    }

    [Fact]
    public void Podium_ComparableBesideWrapperBound_ComparesOnTheKotlinSide()
    {
        using var oreo = new Performer("Oreo", 3);
        using var mylo = new Performer("Mylo", 5);
        using var podium = new Podium<Performer>(mylo);

        using Performer first = podium.First;
        Assert.Equal("Mylo", first.Name);

        using Performer best = podium.Best(oreo);
        Assert.Equal("Mylo", best.Name);
    }

    [Fact]
    public void Hamper_NullableBounds_CarryNullAndAValue()
    {
        using var empty = new Hamper<Performer?>(null);
        Assert.Null(empty.Occupant);
        Assert.Equal("empty", empty.Label);

        using var oreo = new Performer("Oreo", 3);
        using Performer? swapped = empty.Swap(oreo);
        Assert.NotNull(swapped);
        Assert.Equal("Oreo", swapped!.Name);
        Assert.Null(empty.Swap(null));
    }

    [Fact]
    public void Rehearse_TwoWrapperBounds_ReturnsTheSameKotlinObject()
    {
        using var oreo = new Performer("Oreo", 3);
        using Performer rehearsed = Rehearsals.Rehearse<Performer>(oreo);
        Assert.Equal("Oreo", rehearsed.Name);
        Assert.Equal(3, rehearsed.Tricks);
    }

    [Fact]
    public void Headline_ComparableBesideWrapperBound_ReturnsTheSameKotlinObject()
    {
        using var mylo = new Performer("Mylo", 5);
        using Performer headliner = Rehearsals.Headline<Performer>(mylo);
        Assert.Equal("Mylo", headliner.Name);
        using var oreo = new Performer("Oreo", 3);
        Assert.True(headliner.CompareTo(oreo) > 0);
    }

    [Fact]
    public void WhereClauses_ListEveryExportableBound()
    {
        Type[] arena = typeof(Arena<>).GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Contains(typeof(IPet), arena);
        Assert.Contains(typeof(ITrainable), arena);

        // ADR-015: the builtin Comparable bound is dropped; the wrapper bound stays.
        Type[] podium = typeof(Podium<>).GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Equal(new[] { typeof(IPet) }, podium);

        MethodInfo rehearse = typeof(Rehearsals).GetMethod("Rehearse")!;
        Type[] rehearsed = rehearse.GetGenericArguments()[0].GetGenericParameterConstraints();
        Assert.Contains(typeof(IPet), rehearsed);
        Assert.Contains(typeof(ITrainable), rehearsed);
    }
}

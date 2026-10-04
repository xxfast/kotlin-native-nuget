using System.Reflection;
using TestLibrary.Multibound;
using TestLibrary.Rankings;

namespace IntegrationTests;

/// <summary>
/// ADR-198: a type parameter whose bound has no closed Kotlin spelling. <c>T : Enum&lt;T&gt;</c>
/// binds as <c>where T : struct, global::System.Enum</c> on the class route
/// (<see cref="Rosette{T}"/>) and the function route (<c>Medals.RequirePrize</c>), and
/// <c>T : Node&lt;T&gt;, T : Pet</c> as <c>where T : INode&lt;T&gt;, IPet</c>. The generated Kotlin
/// for both used not to compile at all.
///
/// Every Kotlin body uses the bound's own API (<c>name</c>, <c>ordinal</c>, <c>compareTo</c>,
/// <c>link</c>), so a crossing that handed Kotlin anything but the real object would answer
/// wrongly or throw. Every asserted medal has a non-zero ordinal unless the order is the point.
///
/// Oreo wins gold; Mylo keeps taking silver.
/// </summary>
public class EnumSelfBoundTests
{
    [Fact]
    public void Rosette_Enum_RoundTripsAndComparesOnTheKotlinSide()
    {
        using var oreo = new Rosette<Medal>(Medal.Gold);

        Assert.Equal(Medal.Gold, oreo.Winner);
        Assert.True(oreo.Outranks(Medal.Silver));
        Assert.False(oreo.Outranks(Medal.Gold));
        Assert.Equal("GOLD#2 over SILVER#1", oreo.Describe(Medal.Silver));
        Assert.Equal(Medal.Gold, oreo.Better(Medal.Silver));
    }

    [Fact]
    public void Rosette_NullableT_NullReadsBackAsNull_NotTheFirstEntry()
    {
        using var mylo = new Rosette<Medal>(Medal.Silver);

        // Under `struct`, `T?` is `Nullable<T>`: reading the null pointer through `FromHandle<T>`
        // would answer `default(Medal)`, which is Bronze.
        Assert.Null(mylo.Vacancy);
        Assert.Null(mylo.Claim(null));
        Assert.Equal(Medal.Gold, mylo.Claim(Medal.Gold));
    }

    [Fact]
    public void RequirePrize_FunctionRoute_ReadsTheEntryOnTheKotlinSide()
    {
        Assert.Equal(Medal.Gold, Medals.RequirePrize(Medal.Gold));
        Assert.Equal(Medal.Silver, Medals.RequirePrize(Medal.Silver));
        // `ordinal` and `name` ran on the real Kotlin entry: Bronze is refused by name.
        var refused = Assert.ThrowsAny<ArgumentException>(() => Medals.RequirePrize(Medal.Bronze));
        Assert.Contains("BRONZE is no prize", refused.Message);
    }

    [Fact]
    public void EnumBound_IsARealConstraint()
    {
        foreach (Type definition in new[]
                 {
                     typeof(Rosette<>).GetGenericArguments()[0],
                     typeof(Medals)
                         .GetMethod(nameof(Medals.RequirePrize))!
                         .GetGenericArguments()[0],
                 })
        {
            Assert.Contains(typeof(Enum), definition.GetGenericParameterConstraints());
            Assert.True(definition.GenericParameterAttributes
                .HasFlag(GenericParameterAttributes.NotNullableValueTypeConstraint));
        }
    }

    [Fact]
    public void ForeignEnum_SatisfiesTheConstraint_AndThrowsCatchably()
    {
        // C# cannot say "a generated enum": `DayOfWeek` compiles, has no box, and is refused at
        // the argument before anything crosses. The route still answers afterwards.
        Assert.ThrowsAny<NotSupportedException>(() => new Rosette<DayOfWeek>(DayOfWeek.Friday));
        Assert.ThrowsAny<NotSupportedException>(() => Medals.RequirePrize(DayOfWeek.Friday));

        using var oreo = new Rosette<Medal>(Medal.Gold);
        Assert.True(oreo.Outranks(Medal.Silver));
    }

    [Fact]
    public void Chain_InvariantRecursiveBound_LinksOnTheKotlinSide()
    {
        using var oreo = new Bead("Oreo");
        using var mylo = new Bead("Mylo");
        using var chain = new Chain<Bead>(oreo);

        Assert.Equal("chain of Oreo", chain.Label);
        using Bead linked = chain.Attach(mylo);
        Assert.Equal("Oreo-Mylo", linked.Name);
        using Bead head = chain.Head;
        Assert.Equal("Oreo", head.Name);
        using Bead lead = Chains.Lead(mylo);
        Assert.Equal("Mylo", lead.Name);
    }

    [Fact]
    public void Chain_IsConstrainedByTheGenericInterface()
    {
        Type[] constraints =
            typeof(Chain<>).GetGenericArguments()[0].GetGenericParameterConstraints();

        Assert.Contains(constraints, constraint =>
            constraint.IsGenericType && constraint.GetGenericTypeDefinition() == typeof(INode<>));
        Assert.Contains(typeof(IPet), constraints);
    }
}

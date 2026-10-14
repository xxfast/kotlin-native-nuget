using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-211: a top-level generic function (<c>fun &lt;T : Any&gt; describe(value: T): String</c>)
/// is a static generic method on its file facade. The bare <c>T</c> crosses as a boxed handle, so
/// a builtin argument and a generated wrapper both reach Kotlin as themselves. Arguments are
/// passed positionally: the parameter name is still <c>value_</c> in this lane.
/// </summary>
public class TopLevelGenericMethodTests
{
    [Fact]
    public void Describe_IntArgument_NamesKotlinInt()
    {
        // Oreo's age in years.
        Assert.Equal("4: Int", Descriptions.Describe(4));
    }

    [Fact]
    public void Describe_StringArgument_NamesKotlinString()
    {
        Assert.Equal("Oreo: String", Descriptions.Describe("Oreo"));
    }

    [Fact]
    public void Describe_WrapperArgument_ReachesKotlinAsTheSameObject()
    {
        using var toy = new Toy("Mylo's Milo Mouse", "Brown");

        Assert.Equal("Toy(name=Mylo's Milo Mouse, color=Brown): Toy", Descriptions.Describe(toy));
    }

    [Fact]
    public void Label_UnboundedTypeParameter_BindsWithoutAWhereClause()
    {
        Assert.Equal("bowl of 2", Descriptions.Label(2));
        Assert.Equal("bowl of Mylo", Descriptions.Label("Mylo"));
    }

    [Fact]
    public void Pick_IntArguments_ReturnsSecond()
    {
        // Mylo always goes for the second bowl.
        Assert.Equal(9, Descriptions.Pick(7, 9));
    }

    [Fact]
    public void Pick_StringArguments_ReturnsSecond()
    {
        Assert.Equal("b", Descriptions.Pick("a", "b"));
    }

    [Fact]
    public void Pick_WrapperArguments_ReturnsSecond()
    {
        using var oreoToy = new Toy("Oreo's Biscuit Ball", "Black");
        using var myloToy = new Toy("Mylo's Milo Mouse", "Brown");

        using var picked = Descriptions.Pick(oreoToy, myloToy);

        Assert.Equal("Mylo's Milo Mouse", picked.Name);
    }

    [Fact]
    public void Tally_TypeParameterBesideInt_FormatsBoth()
    {
        // Oreo, four years old, two treats.
        Assert.Equal("4 x2", Descriptions.Tally(4, 2));
    }

    // ADR-212: a `T` as a list element at top level. Kotlin reverses the list, so the order shows
    // it was rebuilt on the other side.
    [Fact]
    public void Shuffle_IntElements_ReturnsReversed()
    {
        Assert.Equal(new[] { 3, 2, 1 }, Descriptions.Shuffle(new[] { 1, 2, 3 }));
    }

    [Fact]
    public void Shuffle_StringElements_ReturnsReversed()
    {
        Assert.Equal(new[] { "Mylo", "Oreo" }, Descriptions.Shuffle(new List<string> { "Oreo", "Mylo" }));
    }

    [Fact]
    public void Shuffle_WrapperElements_ReturnsTheSameObjectsReversed()
    {
        using var oreoToy = new Toy("Oreo's Biscuit Ball", "Black");
        using var myloToy = new Toy("Mylo's Milo Mouse", "Brown");

        IReadOnlyList<Toy> shuffled = Descriptions.Shuffle(new[] { oreoToy, myloToy });

        Assert.Equal(2, shuffled.Count);
        using var first = shuffled[0];
        using var second = shuffled[1];
        Assert.Equal("Mylo's Milo Mouse", first.Name);
        Assert.Equal("Oreo's Biscuit Ball", second.Name);
    }
}

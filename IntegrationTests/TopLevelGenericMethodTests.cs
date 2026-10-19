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
}

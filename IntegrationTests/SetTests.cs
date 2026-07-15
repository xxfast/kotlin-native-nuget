using TestLibrary.Cat;

namespace IntegrationTests;

public class SetTests
{
    [Fact]
    public void Cat_Traits_Count()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlySet<string> traits = cat.Traits;
        Assert.Equal(3, traits.Count);
    }

    [Fact]
    public void Cat_Traits_Contains()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlySet<string> traits = cat.Traits;
        Assert.True(traits.Contains("Playful"));
        Assert.True(traits.Contains("Curious"));
        Assert.True(traits.Contains("Fluffy"));
        Assert.False(traits.Contains("Lazy"));
    }

    [Fact]
    public void Cat_Traits_SetEquality()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlySet<string> traits = cat.Traits;
        var expected = new HashSet<string> { "Fluffy", "Playful", "Curious" };
        Assert.True(traits.SetEquals(expected));
    }

    [Fact]
    public void Cat_Traits_Enumeration()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlySet<string> traits = cat.Traits;
        var list = new List<string>();
        foreach (var trait in traits)
        {
            list.Add(trait);
        }
        Assert.Equal(3, list.Count);
        Assert.Contains("Playful", list);
        Assert.Contains("Curious", list);
        Assert.Contains("Fluffy", list);
    }
}

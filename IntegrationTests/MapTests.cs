using TestLibrary.Cat;

namespace IntegrationTests;

public class MapTests
{
    [Fact]
    public void Cat_Accessories_Count()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyDictionary<string, Toy> accessories = cat.Accessories;
        Assert.Equal(2, accessories.Count);
    }

    [Fact]
    public void Cat_Accessories_ContainsKey()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyDictionary<string, Toy> accessories = cat.Accessories;
        Assert.True(accessories.ContainsKey("collar"));
        Assert.True(accessories.ContainsKey("tag"));
        Assert.False(accessories.ContainsKey("hat"));
    }

    [Fact]
    public void Cat_Accessories_GetByKey()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyDictionary<string, Toy> accessories = cat.Accessories;
        using var collar = accessories["collar"];
        Assert.Equal("Bell Collar", collar.Name);
        Assert.Equal("Gold", collar.Color);
    }

    [Fact]
    public void Cat_Accessories_Keys()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyDictionary<string, Toy> accessories = cat.Accessories;
        Assert.Contains("collar", accessories.Keys);
        Assert.Contains("tag", accessories.Keys);
    }

    [Fact]
    public void Cat_Accessories_Enumeration()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyDictionary<string, Toy> accessories = cat.Accessories;
        var keys = new List<string>();
        foreach (var kvp in accessories)
        {
            using var toy = kvp.Value;
            keys.Add(kvp.Key);
        }
        Assert.Equal(2, keys.Count);
        Assert.Contains("collar", keys);
        Assert.Contains("tag", keys);
    }
}

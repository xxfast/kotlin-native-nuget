using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class ListTests
{
    [Fact]
    public void Cat_Nicknames_Count()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<string> nicknames = cat.Nicknames;
        Assert.Equal(2, nicknames.Count);
    }

    [Fact]
    public void Cat_Nicknames_GetByIndex()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<string> nicknames = cat.Nicknames;
        Assert.Equal("Oreoy", nicknames[0]);
        Assert.Equal("Little Oreo", nicknames[1]);
    }

    [Fact]
    public void Cat_Nicknames_ListEquality()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<string> nicknames = cat.Nicknames;
        Assert.Equal(new List<string> { "Oreoy", "Little Oreo" }, nicknames);
        Assert.NotEqual(new List<string> { "Little Oreo", "Oreoy" }, nicknames);
    }

    [Fact]
    public void Cat_Nicknames_Enumeration()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<string> nicknames = cat.Nicknames;
        var list = new List<string>();
        foreach (var name in nicknames)
        {
            list.Add(name);
        }
        Assert.Equal(2, list.Count);
        Assert.Equal("Oreoy", list[0]);
        Assert.Equal("Little Oreo", list[1]);
    }

    [Fact]
    public void Cat_Toys_Count()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<Toy> toys = cat.Toys;
        Assert.Equal(2, toys.Count);
    }

    [Fact]
    public void Cat_Toys_GetByIndex()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<Toy> toys = cat.Toys;
        using var first = toys[0];
        Assert.Equal("Mouse", first.Name);
        Assert.Equal("Gray", first.Color);
    }

    [Fact]
    public void Cat_Toys_Enumeration()
    {
        using var cat = new Cat("Oreo", 9);
        IReadOnlyList<Toy> toys = cat.Toys;
        var names = new List<string>();
        foreach (var toy in toys)
        {
            using var t = toy;
            names.Add(t.Name);
        }
        Assert.Equal(new List<string> { "Mouse", "Ball" }, names);
    }
}

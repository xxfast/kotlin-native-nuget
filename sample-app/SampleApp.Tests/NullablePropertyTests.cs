using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class NullablePropertyTests
{
    [Fact]
    public void Cat_Owner_DefaultNull()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Null(cat.Owner);
    }

    [Fact]
    public void Cat_Owner_SetAndGet()
    {
        using var cat = new Cat("Oreo", 9);
        cat.Owner = "Isuru";
        Assert.Equal("Isuru", cat.Owner);
    }

    [Fact]
    public void Cat_Owner_SetToNull()
    {
        using var cat = new Cat("Oreo", 9);
        cat.Owner = "Isuru";
        Assert.Equal("Isuru", cat.Owner);
        cat.Owner = null;
        Assert.Null(cat.Owner);
    }

    [Fact]
    public void Cat_Age_DefaultNull()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Null(cat.Age);
    }

    [Fact]
    public void Cat_Age_SetAndGet()
    {
        using var cat = new Cat("Oreo", 9);
        cat.Age = 3;
        Assert.Equal(3, cat.Age);
    }

    [Fact]
    public void Cat_Age_SetToNull()
    {
        using var cat = new Cat("Oreo", 9);
        cat.Age = 3;
        Assert.Equal(3, cat.Age);
        cat.Age = null;
        Assert.Null(cat.Age);
    }
}

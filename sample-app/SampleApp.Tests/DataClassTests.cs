using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class DataClassTests
{
    [Fact]
    public void Toy_Constructor_CreatesInstance()
    {
        using var toy = new Toy("Mouse", "Gray");
        Assert.Equal("Mouse", toy.Name);
        Assert.Equal("Gray", toy.Color);
    }

    [Fact]
    public void Toy_Equals_SameValues_ReturnsTrue()
    {
        using var toy1 = new Toy("Mouse", "Gray");
        using var toy2 = new Toy("Mouse", "Gray");
        Assert.True(toy1.Equals(toy2));
    }

    [Fact]
    public void Toy_Equals_DifferentValues_ReturnsFalse()
    {
        using var toy1 = new Toy("Mouse", "Gray");
        using var toy2 = new Toy("Ball", "Red");
        Assert.False(toy1.Equals(toy2));
    }

    [Fact]
    public void Toy_GetHashCode_SameValues_SameHash()
    {
        using var toy1 = new Toy("Mouse", "Gray");
        using var toy2 = new Toy("Mouse", "Gray");
        Assert.Equal(toy1.GetHashCode(), toy2.GetHashCode());
    }

    [Fact]
    public void Toy_ToString_ReturnsKotlinFormat()
    {
        using var toy = new Toy("Mouse", "Gray");
        Assert.Equal("Toy(name=Mouse, color=Gray)", toy.ToString());
    }

    [Fact]
    public void Toy_Copy_ReturnsNewInstance()
    {
        using var toy = new Toy("Mouse", "Gray");
        using var copy = toy.Copy("Ball", "Red");

        Assert.Equal("Ball", copy.Name);
        Assert.Equal("Red", copy.Color);
        Assert.Equal("Mouse", toy.Name);
    }

    [Fact]
    public void Toy_Copy_IsEqualWhenSameValues()
    {
        using var toy = new Toy("Mouse", "Gray");
        using var copy = toy.Copy("Mouse", "Gray");

        Assert.True(toy.Equals(copy));
        Assert.NotSame(toy, copy);
    }
}

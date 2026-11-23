using TestLibrary.Cat;

namespace IntegrationTests;

public class DataClassTests
{
    [Fact]
    public void Toy_Constructor_CreatesInstance()
    {
        var toy = new Toy("Mouse", "Gray");
        Assert.Equal("Mouse", toy.Name);
        Assert.Equal("Gray", toy.Color);
    }

    [Fact]
    public void Toy_Equals_SameValues_ReturnsTrue()
    {
        var toy1 = new Toy("Mouse", "Gray");
        var toy2 = new Toy("Mouse", "Gray");
        Assert.True(toy1.Equals(toy2));
    }

    [Fact]
    public void Toy_Equals_DifferentValues_ReturnsFalse()
    {
        var toy1 = new Toy("Mouse", "Gray");
        var toy2 = new Toy("Ball", "Red");
        Assert.False(toy1.Equals(toy2));
    }

    [Fact]
    public void Toy_GetHashCode_SameValues_SameHash()
    {
        var toy1 = new Toy("Mouse", "Gray");
        var toy2 = new Toy("Mouse", "Gray");
        Assert.Equal(toy1.GetHashCode(), toy2.GetHashCode());
    }

    [Fact]
    public void Toy_ToString_ReturnsKotlinFormat()
    {
        var toy = new Toy("Mouse", "Gray");
        Assert.Equal("Toy(name=Mouse, color=Gray)", toy.ToString());
    }

    [Fact]
    public void Toy_Copy_ReturnsNewInstance()
    {
        var toy = new Toy("Mouse", "Gray");
        var copy = toy.Copy("Ball", "Red");

        Assert.Equal("Ball", copy.Name);
        Assert.Equal("Red", copy.Color);
        Assert.Equal("Mouse", toy.Name);
    }

    [Fact]
    public void Toy_Copy_IsEqualWhenSameValues()
    {
        var toy = new Toy("Mouse", "Gray");
        var copy = toy.Copy("Mouse", "Gray");

        Assert.True(toy.Equals(copy));
        Assert.NotSame(toy, copy);
    }
}

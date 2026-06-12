using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class CatTests
{
    [Fact]
    public void Cat_Constructor_CreatesInstance()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.NotNull(cat);
    }

    [Fact]
    public void Cat_Name_ReturnsCorrectValue()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo", cat.Name);
    }

    [Fact]
    public void Cat_Lives_ReturnsCorrectValue()
    {
        using var cat = new Cat("Mylo", 7);
        Assert.Equal(7, cat.Lives);
    }

    [Fact]
    public void Cat_Meow_ReturnsGreeting()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Meow! My name is Oreo", cat.Meow());
    }

    [Fact]
    public void Cat_Pet_ReturnsPurr()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo purrs contentedly", cat.Pet());
    }
}

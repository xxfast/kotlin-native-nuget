using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class CompanionObjectTests
{
    [Fact]
    public void CompanionConstVal()
    {
        Assert.Equal("Felis catus", Cat.Species);
    }

    [Fact]
    public void CompanionProperty()
    {
        Assert.Equal("Domestic Shorthair", Cat.DefaultBreed);
    }

    [Fact]
    public void CompanionFactoryMethod()
    {
        using var cat = Cat.FromName("Whiskers");
        Assert.Equal("Whiskers", cat.Name);
    }
}

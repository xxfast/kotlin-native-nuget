using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class AbstractClassTests
{
    [Fact]
    public void Cat_IsAnimal()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.IsAssignableFrom<Animal>(cat);
    }

    [Fact]
    public void Cat_IsIPet_ThroughAnimal()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.IsAssignableFrom<IPet>(cat);
    }

    [Fact]
    public void Animal_Introduce_ReturnsValue()
    {
        using var cat = new Cat("Oreo", 9);
        Animal animal = cat;
        Assert.Equal("My name is Oreo", animal.Introduce());
    }

    [Fact]
    public void Animal_Greet_OverridesPetDefault()
    {
        using var cat = new Cat("Oreo", 9);
        Animal animal = cat;
        Assert.Equal("Hi, I'm Oreo", animal.Greet());
    }

    [Fact]
    public void Animal_CannotBeInstantiated()
    {
        // Animal is abstract — this verifies the C# class is also abstract
        Assert.True(typeof(Animal).IsAbstract);
    }
}

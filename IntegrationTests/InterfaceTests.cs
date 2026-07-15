using TestLibrary.Cat;

namespace IntegrationTests;

public class InterfaceTests
{
    [Fact]
    public void Cat_ImplementsIPet()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.IsAssignableFrom<IPet>(cat);
    }

    [Fact]
    public void IPet_Name_ReturnsValue()
    {
        using IPet pet = new Cat("Oreo", 9);
        Assert.Equal("Oreo", pet.Name);
    }

    [Fact]
    public void IPet_Speak_ReturnsValue()
    {
        using IPet pet = new Cat("Oreo", 9);
        Assert.Equal("Meow! My name is Oreo", pet.Speak());
    }

    [Fact]
    public void IPet_Greet_UsesDefaultImplementation()
    {
        using IPet pet = new Cat("Oreo", 9);
        Assert.Equal("Hi, I'm Oreo", pet.Greet());
    }

    [Fact]
    public void IPet_CanBeUsedPolymorphically()
    {
        using IPet pet = new Cat("Mylo", 9);
        string greeting = Greet(pet);
        Assert.Equal("Hi, I'm Mylo", greeting);
    }

    private static string Greet(IPet pet) => pet.Greet();
}

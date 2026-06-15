using SampleLibrary;
using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class ExtensionFunctionTests
{
    [Fact]
    public void String_Meowify_AppendsMeow()
    {
        Assert.Equal("Oreo meow!", "Oreo".Meowify());
    }

    [Fact]
    public void String_IsPurring_ReturnsTrueWhenPurring()
    {
        Assert.True("purrfect".IsPurring());
    }

    [Fact]
    public void String_IsPurring_ReturnsFalseWhenNotPurring()
    {
        Assert.False("meow".IsPurring());
    }

    [Fact]
    public void Cat_SayName()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("My name is Oreo", cat.SayName());
    }

    [Fact]
    public void Cat_GreetWith()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Hello, Oreo!", cat.GreetWith("Hello"));
    }
}

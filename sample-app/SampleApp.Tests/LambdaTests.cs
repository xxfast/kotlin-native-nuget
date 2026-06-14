using SampleLibrary;
using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class LambdaTests
{
    [Fact]
    public void Cat_OnMeow_Invoke()
    {
        using var cat = new Cat("Oreo", 9);
        using var onMeow = cat.OnMeow;
        string result = onMeow.Invoke();
        Assert.Equal("Meow! My name is Oreo", result);
    }

    [Fact]
    public void Cat_OnPet_Invoke()
    {
        using var cat = new Cat("Oreo", 9);
        using var onPet = cat.OnPet;
        string result = onPet.Invoke("purrs");
        Assert.Equal("Oreo purrs contentedly", result);
    }

    [Fact]
    public void Cat_OnMeow_MultipleInvocations()
    {
        using var cat = new Cat("Oreo", 9);
        using var onMeow = cat.OnMeow;
        Assert.Equal("Meow! My name is Oreo", onMeow.Invoke());
        Assert.Equal("Meow! My name is Oreo", onMeow.Invoke());
    }

    [Fact]
    public void Cat_CountLives_ReturnsInt()
    {
        using var cat = new Cat("Oreo", 9);
        using var countLives = cat.CountLives;
        int result = countLives.Invoke();
        Assert.Equal(9, result);
    }

    [Fact]
    public void Cat_IsAlive_ReturnsBool()
    {
        using var cat = new Cat("Oreo", 9);
        using var isAlive = cat.IsAlive;
        bool result = isAlive.Invoke();
        Assert.True(result);
    }

    [Fact]
    public void Cat_FavoriteToy_ReturnsObject()
    {
        using var cat = new Cat("Oreo", 9);
        using var favoriteToy = cat.FavoriteToy;
        using var toy = favoriteToy.Invoke();
        Assert.Equal("Mouse", toy.Name);
        Assert.Equal("Gray", toy.Color);
    }

    [Fact]
    public void Greeter_ReturnsInvocableLambda()
    {
        using var greet = Mappings.greeter("Hello");
        string result = greet.Invoke("World");
        Assert.Equal("Hello, World!", result);
    }

    [Fact]
    public void Greeter_DifferentGreetings()
    {
        using var hi = Mappings.greeter("Hi");
        using var hey = Mappings.greeter("Hey");
        Assert.Equal("Hi, Alice!", hi.Invoke("Alice"));
        Assert.Equal("Hey, Bob!", hey.Invoke("Bob"));
    }
}

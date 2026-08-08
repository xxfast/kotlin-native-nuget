using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// A class that implements an interface without overriding its defaulted members must still carry
/// them in C#: the generated class declares the interface, so omitting them is CS0535. The fact
/// that this file compiles at all is half the assertion; the rest proves the defaults actually
/// execute across the bridge, through both the class and the interface reference.
/// </summary>
public class DefaultedInterfaceMemberTests
{
    [Fact]
    public void Parrot_ImplementsIGreeter()
    {
        using var parrot = new Parrot("macaw");
        Assert.IsAssignableFrom<IGreeter>(parrot);
    }

    [Fact]
    public void Parrot_Greeting_UsesDefaultProperty()
    {
        using var parrot = new Parrot("macaw");
        Assert.Equal("hello", parrot.Greeting);
    }

    [Fact]
    public void Parrot_Greet_UsesDefaultMethod()
    {
        using var parrot = new Parrot("macaw");
        Assert.Equal("hello from a macaw", parrot.Greet());
    }

    [Fact]
    public void IGreeter_Greet_ReachesTheDefaultThroughTheInterface()
    {
        using IGreeter greeter = new Parrot("cockatoo");
        Assert.Equal("hello", greeter.Greeting);
        Assert.Equal("hello from a cockatoo", greeter.Greet());
    }
}

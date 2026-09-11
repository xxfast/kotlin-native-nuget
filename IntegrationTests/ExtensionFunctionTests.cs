using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

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

    // ADR-105 amendment: `fun Cat?.nameOrStray()` binds on a nullable receiver. Mylo is home, so
    // the handle crosses and Kotlin reads his name off it.
    [Fact]
    public void NullableReceiver_LiveCat_ReturnsName()
    {
        using var mylo = new Cat("Mylo", 9);
        Assert.Equal("Mylo", mylo.NameOrStray());
    }

    // No cat at all: null crosses as IntPtr.Zero, `this?.name` is null on the Kotlin side, and the
    // call site is static dispatch so there is no NullReferenceException.
    [Fact]
    public void NullableReceiver_NullCat_ReturnsStray()
    {
        Cat? none = null;
        Assert.Equal("stray", none.NameOrStray());
    }
}

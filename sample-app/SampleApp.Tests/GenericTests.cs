using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class GenericTests
{
    [Fact]
    public void Box_String_ConstructorAndGetter()
    {
        using var box = new Box<string>("hello");
        Assert.Equal("hello", box.Value);
    }

    [Fact]
    public void Box_Int_ConstructorAndGetter()
    {
        using var box = new Box<int>(42);
        Assert.Equal(42, box.Value);
    }

    [Fact]
    public void Box_Cat_ConstructorAndGetter()
    {
        using var oreo = new Cat("Oreo", 9);
        using var box = new Box<Cat>(oreo);
        using Cat cat = box.Value;
        Assert.Equal("Oreo", cat.Name);
    }

    [Fact]
    public void Box_IsGenericType()
    {
        using var box = new Box<string>("test");
        Assert.True(box.GetType().IsGenericType);
    }
}

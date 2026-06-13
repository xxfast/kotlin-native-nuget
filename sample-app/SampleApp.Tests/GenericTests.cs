using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class GenericTests
{
    [Fact]
    public void Box_String_ReturnsValue()
    {
        using Box<string> box = BoxKt.stringBox();
        Assert.Equal("hello", box.Value);
    }

    [Fact]
    public void Box_Int_ReturnsValue()
    {
        using Box<int> box = BoxKt.intBox();
        Assert.Equal(42, box.Value);
    }

    [Fact]
    public void Box_Cat_ReturnsValue()
    {
        using Box<Cat> box = BoxKt.catBox();
        using Cat cat = box.Value;
        Assert.Equal("Oreo", cat.Name);
    }

    [Fact]
    public void Box_IsGenericType()
    {
        using Box<string> box = BoxKt.stringBox();
        Assert.True(box.GetType().IsGenericType);
    }
}

using TestLibrary.Cat;

namespace IntegrationTests;

public class GenericFunctionTests
{
    [Fact]
    public void Identity_String()
    {
        string result = Helpers.Identity<string>("hello");
        Assert.Equal("hello", result);
    }

    [Fact]
    public void Identity_Int()
    {
        int result = Helpers.Identity<int>(42);
        Assert.Equal(42, result);
    }

    [Fact]
    public void Identity_Bool()
    {
        bool result = Helpers.Identity<bool>(true);
        Assert.True(result);
    }

    [Fact]
    public void WrapInBox_String()
    {
        using Box<string> box = Helpers.WrapInBox<string>("world");
        Assert.Equal("world", box.Value);
    }

    [Fact]
    public void WrapInBox_Int()
    {
        using Box<int> box = Helpers.WrapInBox<int>(99);
        Assert.Equal(99, box.Value);
    }
}

using TestLibrary.Cat;

namespace IntegrationTests;

public class GenericFunctionTests
{
    [Fact]
    public void Identity_String()
    {
        string result = Helpers.identity<string>("hello");
        Assert.Equal("hello", result);
    }

    [Fact]
    public void Identity_Int()
    {
        int result = Helpers.identity<int>(42);
        Assert.Equal(42, result);
    }

    [Fact]
    public void Identity_Bool()
    {
        bool result = Helpers.identity<bool>(true);
        Assert.True(result);
    }

    [Fact]
    public void WrapInBox_String()
    {
        using Box<string> box = Helpers.wrapInBox<string>("world");
        Assert.Equal("world", box.Value);
    }

    [Fact]
    public void WrapInBox_Int()
    {
        using Box<int> box = Helpers.wrapInBox<int>(99);
        Assert.Equal(99, box.Value);
    }
}

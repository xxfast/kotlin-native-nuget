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

    // `short` has no width variant (only string, int, long, float, double and bool do), so it rides
    // the object variant as a box and is unwrapped on the way back; it used to throw
    // NotSupportedException looking for a generated factory for System.Int16.
    [Fact]
    public void Identity_Short()
    {
        short result = Helpers.Identity<short>(7);
        Assert.Equal(7, result);
    }

    [Fact]
    public void Identity_NullableShort_CarriesAValueAndNull()
    {
        Assert.Equal((short)7, Helpers.Identity<short?>(7));
        Assert.Null(Helpers.Identity<short?>(null));
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

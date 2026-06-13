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
    public void Box_Long_ConstructorAndGetter()
    {
        using var box = new Box<long>(9_223_372_036_854_775_807L);
        Assert.Equal(9_223_372_036_854_775_807L, box.Value);
    }

    [Fact]
    public void Box_Float_ConstructorAndGetter()
    {
        using var box = new Box<float>(3.14f);
        Assert.Equal(3.14f, box.Value, 0.001f);
    }

    [Fact]
    public void Box_Double_ConstructorAndGetter()
    {
        using var box = new Box<double>(2.718);
        Assert.Equal(2.718, box.Value, 3);
    }

    [Fact]
    public void Box_Bool_ConstructorAndGetter()
    {
        using var box = new Box<bool>(true);
        Assert.True(box.Value);
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
    public void Box_Byte_ConstructorAndGetter()
    {
        using var box = new Box<sbyte>(42);
        Assert.Equal((sbyte)42, box.Value);
    }

    [Fact]
    public void Box_UByte_ConstructorAndGetter()
    {
        using var box = new Box<byte>(255);
        Assert.Equal((byte)255, box.Value);
    }

    [Fact]
    public void Box_Short_ConstructorAndGetter()
    {
        using var box = new Box<short>(1024);
        Assert.Equal((short)1024, box.Value);
    }

    [Fact]
    public void Box_UShort_ConstructorAndGetter()
    {
        using var box = new Box<ushort>(65535);
        Assert.Equal((ushort)65535, box.Value);
    }

    [Fact]
    public void Box_UInt_ConstructorAndGetter()
    {
        using var box = new Box<uint>(4294967295u);
        Assert.Equal(4294967295u, box.Value);
    }

    [Fact]
    public void Box_ULong_ConstructorAndGetter()
    {
        using var box = new Box<ulong>(18446744073709551615UL);
        Assert.Equal(18446744073709551615UL, box.Value);
    }

    [Fact]
    public void Box_IsGenericType()
    {
        using var box = new Box<string>("test");
        Assert.True(box.GetType().IsGenericType);
    }
}

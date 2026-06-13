using SampleLibrary;

namespace SampleApp.Tests;

public class MappingTests
{
    [Fact]
    public void String_ReturnsExpectedValue()
    {
        string result = Mappings.@string();
        Assert.Equal("Kotlin/Native!", result);
    }

    [Fact]
    public void Byte_ReturnsExpectedValue()
    {
        sbyte result = Mappings.@byte();
        Assert.Equal(42, result);
    }

    [Fact]
    public void UByte_ReturnsExpectedValue()
    {
        byte result = Mappings.ubyte();
        Assert.Equal(255, result);
    }

    [Fact]
    public void Short_ReturnsExpectedValue()
    {
        short result = Mappings.@short_();
        Assert.Equal(1024, result);
    }

    [Fact]
    public void UShort_ReturnsExpectedValue()
    {
        ushort result = Mappings.@ushort();
        Assert.Equal(65535, result);
    }

    [Fact]
    public void Int_ReturnsExpectedValue()
    {
        int result = Mappings.@int_();
        Assert.Equal(2_147_483_647, result);
    }

    [Fact]
    public void UInt_ReturnsExpectedValue()
    {
        uint result = Mappings.@uint();
        Assert.Equal(4_294_967_295u, result);
    }

    [Fact]
    public void Long_ReturnsExpectedValue()
    {
        long result = Mappings.@long_();
        Assert.Equal(9_223_372_036_854_775_807L, result);
    }

    [Fact]
    public void ULong_ReturnsExpectedValue()
    {
        ulong result = Mappings.@ulong();
        Assert.Equal(18_446_744_073_709_551_615UL, result);
    }

    [Fact]
    public void Float_ReturnsExpectedValue()
    {
        float result = Mappings.@float_();
        Assert.Equal(3.14f, result, 0.001f);
    }

    [Fact]
    public void Double_ReturnsExpectedValue()
    {
        double result = Mappings.@double_();
        Assert.Equal(2.718281828459045, result, 10);
    }

    [Fact]
    public void NullableInt_WithValue_ReturnsValue()
    {
        int? result = Mappings.nullableInt(true);
        Assert.Equal(42, result);
    }

    [Fact]
    public void NullableInt_WithoutValue_ReturnsNull()
    {
        int? result = Mappings.nullableInt(false);
        Assert.Null(result);
    }

    [Fact]
    public void NullableString_WithValue_ReturnsValue()
    {
        string? result = Mappings.nullableString(true);
        Assert.Equal("hello", result);
    }

    [Fact]
    public void NullableString_WithoutValue_ReturnsNull()
    {
        string? result = Mappings.nullableString(false);
        Assert.Null(result);
    }

}

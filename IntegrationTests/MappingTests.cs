using TestLibrary;

namespace IntegrationTests;

public class MappingTests
{
    [Fact]
    public void String_ReturnsExpectedValue()
    {
        string result = Mappings.String();
        Assert.Equal("Kotlin/Native!", result);
    }

    [Fact]
    public void Byte_ReturnsExpectedValue()
    {
        sbyte result = Mappings.Byte();
        Assert.Equal(42, result);
    }

    [Fact]
    public void UByte_ReturnsExpectedValue()
    {
        byte result = Mappings.Ubyte();
        Assert.Equal(255, result);
    }

    [Fact]
    public void Short_ReturnsExpectedValue()
    {
        short result = Mappings.Short();
        Assert.Equal(1024, result);
    }

    [Fact]
    public void UShort_ReturnsExpectedValue()
    {
        ushort result = Mappings.Ushort();
        Assert.Equal(65535, result);
    }

    [Fact]
    public void Int_ReturnsExpectedValue()
    {
        int result = Mappings.Int();
        Assert.Equal(2_147_483_647, result);
    }

    [Fact]
    public void UInt_ReturnsExpectedValue()
    {
        uint result = Mappings.Uint();
        Assert.Equal(4_294_967_295u, result);
    }

    [Fact]
    public void Long_ReturnsExpectedValue()
    {
        long result = Mappings.Long();
        Assert.Equal(9_223_372_036_854_775_807L, result);
    }

    [Fact]
    public void ULong_ReturnsExpectedValue()
    {
        ulong result = Mappings.Ulong();
        Assert.Equal(18_446_744_073_709_551_615UL, result);
    }

    [Fact]
    public void Float_ReturnsExpectedValue()
    {
        float result = Mappings.Float();
        Assert.Equal(3.14f, result, 0.001f);
    }

    [Fact]
    public void Double_ReturnsExpectedValue()
    {
        double result = Mappings.Double();
        Assert.Equal(2.718281828459045, result, 10);
    }

    [Fact]
    public void NullableInt_WithValue_ReturnsValue()
    {
        int? result = Mappings.NullableInt(true);
        Assert.Equal(42, result);
    }

    [Fact]
    public void NullableInt_WithoutValue_ReturnsNull()
    {
        int? result = Mappings.NullableInt(false);
        Assert.Null(result);
    }

    [Fact]
    public void NullableString_WithValue_ReturnsValue()
    {
        string? result = Mappings.NullableString(true);
        Assert.Equal("hello", result);
    }

    [Fact]
    public void NullableString_WithoutValue_ReturnsNull()
    {
        string? result = Mappings.NullableString(false);
        Assert.Null(result);
    }

    [Fact]
    public void NullableIntOrThrow_PositiveInput_ReturnsValue()
    {
        int? result = Mappings.NullableIntOrThrow(5);
        Assert.Equal(5, result);
    }

    [Fact]
    public void NullableIntOrThrow_ZeroInput_ReturnsNull()
    {
        int? result = Mappings.NullableIntOrThrow(0);
        Assert.Null(result);
    }

    [Fact]
    public void NullableStringOrThrow_PositiveInput_ReturnsValue()
    {
        string? result = Mappings.NullableStringOrThrow(5);
        Assert.Equal("value-5", result);
    }

    [Fact]
    public void NullableStringOrThrow_ZeroInput_ReturnsNull()
    {
        string? result = Mappings.NullableStringOrThrow(0);
        Assert.Null(result);
    }

}

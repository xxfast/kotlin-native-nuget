using System.Runtime.InteropServices;
using SampleLibrary.Interop;

namespace SampleApp.Tests;

public class MappingTests
{
    [Fact]
    public unsafe void String_ReturnsExpectedValue()
    {
        sbyte* result = SampleLibraryNative.get_string();
        string? message = Marshal.PtrToStringUTF8((nint)result);
        Assert.Equal("Kotlin/Native!", message);
    }

    [Fact]
    public void Byte_ReturnsExpectedValue()
    {
        sbyte result = SampleLibraryNative.get_byte();
        Assert.Equal(42, result);
    }

    [Fact]
    public void UByte_ReturnsExpectedValue()
    {
        byte result = SampleLibraryNative.get_ubyte();
        Assert.Equal(255, result);
    }

    [Fact]
    public void Short_ReturnsExpectedValue()
    {
        short result = SampleLibraryNative.get_short();
        Assert.Equal(1024, result);
    }

    [Fact]
    public void UShort_ReturnsExpectedValue()
    {
        ushort result = SampleLibraryNative.get_ushort();
        Assert.Equal(65535, result);
    }

    [Fact]
    public void Int_ReturnsExpectedValue()
    {
        int result = SampleLibraryNative.get_int();
        Assert.Equal(2_147_483_647, result);
    }

    [Fact]
    public void UInt_ReturnsExpectedValue()
    {
        uint result = SampleLibraryNative.get_uint();
        Assert.Equal(4_294_967_295u, result);
    }

    [Fact]
    public void Long_ReturnsExpectedValue()
    {
        long result = SampleLibraryNative.get_long();
        Assert.Equal(9_223_372_036_854_775_807L, result);
    }

    [Fact]
    public void ULong_ReturnsExpectedValue()
    {
        ulong result = SampleLibraryNative.get_ulong();
        Assert.Equal(18_446_744_073_709_551_615UL, result);
    }

    [Fact]
    public void Float_ReturnsExpectedValue()
    {
        float result = SampleLibraryNative.get_float();
        Assert.Equal(3.14f, result, 0.001f);
    }

    [Fact]
    public void Double_ReturnsExpectedValue()
    {
        double result = SampleLibraryNative.get_double();
        Assert.Equal(2.718281828459045, result, 10);
    }
}

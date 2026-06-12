using SampleLibrary.Math;

namespace SampleApp.Tests;

public class ArithmeticTests
{
    [Fact]
    public void Add_ReturnsSum()
    {
        int result = SampleLibraryNative.add(3, 4);
        Assert.Equal(7, result);
    }

    [Fact]
    public void Multiply_ReturnsProduct()
    {
        int result = SampleLibraryNative.multiply(3, 4);
        Assert.Equal(12, result);
    }

    [Fact]
    public void Divide_ReturnsQuotient()
    {
        int? result = SampleLibraryNative.divide(10, 2);
        Assert.Equal(5, result);
    }

    [Fact]
    public void Divide_ByZero_ReturnsNull()
    {
        int? result = SampleLibraryNative.divide(10, 0);
        Assert.Null(result);
    }
}

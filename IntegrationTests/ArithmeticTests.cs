using TestLibrary.Math;

namespace IntegrationTests;

public class ArithmeticTests
{
    [Fact]
    public void Add_ReturnsSum()
    {
        int result = Arithmetic.Add(3, 4);
        Assert.Equal(7, result);
    }

    [Fact]
    public void Multiply_ReturnsProduct()
    {
        int result = Arithmetic.Multiply(3, 4);
        Assert.Equal(12, result);
    }

    [Fact]
    public void Divide_ReturnsQuotient()
    {
        int? result = Arithmetic.Divide(10, 2);
        Assert.Equal(5, result);
    }

    [Fact]
    public void Divide_ByZero_ReturnsNull()
    {
        int? result = Arithmetic.Divide(10, 0);
        Assert.Null(result);
    }

    [Fact]
    public void Square_ReturnsSquaredValue()
    {
        int result = Arithmetic.Square(5);
        Assert.Equal(25, result);
    }
}

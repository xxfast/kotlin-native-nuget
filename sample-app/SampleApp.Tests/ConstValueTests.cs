using SampleLibrary;

namespace SampleApp.Tests;

public class ConstValueTests
{
    [Fact]
    public void MaxLivesIsNine()
    {
        Assert.Equal(9, Constants.MaxLives);
    }

    [Fact]
    public void GreetingIsHelloWorld()
    {
        Assert.Equal("Hello, world!", Constants.Greeting);
    }

    [Fact]
    public void PiApproxIsThreePointOneFour()
    {
        Assert.Equal(3.14, Constants.PiApprox);
    }

    [Fact]
    public void IsDebugIsFalse()
    {
        Assert.False(Constants.IsDebug);
    }
}

using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class ReferenceValueClassTests
{
    [Fact]
    public void CatResult_Constructor_WrapsClass()
    {
        using var oreo = new Cat("Oreo", 9);
        var result = new CatResult(oreo);
        Assert.Equal("Oreo", result.Cat.Name);
    }

    [Fact]
    public void CatResult_Name_ReturnsUnderlyingCatName()
    {
        using var mylo = new Cat("Mylo", 9);
        var result = new CatResult(mylo);
        Assert.Equal("Mylo", result.Name);
    }

    [Fact]
    public void CatResult_IsAlive_WhenCatHasLives_ReturnsTrue()
    {
        using var oreo = new Cat("Oreo", 9);
        var result = new CatResult(oreo);
        Assert.True(result.IsAlive());
    }

    [Fact]
    public void CatResult_Equality_SameUnderlying_AreEqual()
    {
        using var oreo = new Cat("Oreo", 9);
        var result1 = new CatResult(oreo);
        var result2 = new CatResult(oreo);
        Assert.Equal(result1, result2);
    }

    [Fact]
    public void CatResult_ToString_ContainsCatInfo()
    {
        using var oreo = new Cat("Oreo", 9);
        var result = new CatResult(oreo);
        Assert.Contains("CatResult", result.ToString());
    }

    [Fact]
    public void ObservationResult_Describe_WhenAlive()
    {
        using var observation = ObservationKt.openBox("Oreo");
        var result = new ObservationResult(observation);
        Assert.Equal("Alive: Oreo", result.Describe());
    }

    [Fact]
    public void ObservationResult_Describe_WhenDead()
    {
        using var observation = ObservationKt.openBox("Rex");
        var result = new ObservationResult(observation);
        Assert.Equal("Dead: The cat was not Rex", result.Describe());
    }

    [Fact]
    public void ObservationResult_Describe_WhenSuperposition()
    {
        using var observation = ObservationKt.peekBox();
        var result = new ObservationResult(observation);
        Assert.Equal("Unknown", result.Describe());
    }
}

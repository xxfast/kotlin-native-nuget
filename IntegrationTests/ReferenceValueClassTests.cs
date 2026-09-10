using TestLibrary.Cat;

namespace IntegrationTests;

public class ReferenceValueClassTests
{
    [Fact]
    public void CatResult_Constructor_WrapsClass()
    {
        using var oreo = new Cat("Oreo", 9);
        var result = new CatResult(oreo);
        Assert.Equal("Oreo", result.Cat.Name);
    }

    // ADR-035 amendment: the secondary constructor runs in Kotlin, mints the underlying Cat and
    // hands the handle back wrapped in the struct. Mylo arrives with one life and his own name.
    [Fact]
    public void CatResult_SecondaryConstructor_MintsUnderlyingCat()
    {
        var result = new CatResult("Mylo");
        using var mylo = result.Cat;
        Assert.Equal("Mylo", mylo.Name);
    }

    // The positional primary is unchanged by the secondary: Oreo is still wrapped, not re-minted.
    [Fact]
    public void CatResult_PrimaryConstructor_StillWrapsExistingCat()
    {
        using var oreo = new Cat("Oreo", 9);
        var result = new CatResult(oreo);
        Assert.Equal("Oreo", result.Cat.Name);
        Assert.True(result.IsAlive());
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
        using var observation = ObservationKt.OpenBox("Oreo");
        var result = new ObservationResult(observation);
        Assert.Equal("Alive: Oreo", result.Describe());
    }

    [Fact]
    public void ObservationResult_Describe_WhenDead()
    {
        using var observation = ObservationKt.OpenBox("Rex");
        var result = new ObservationResult(observation);
        Assert.Equal("Dead: The cat was not Rex", result.Describe());
    }

    [Fact]
    public void ObservationResult_Describe_WhenSuperposition()
    {
        using var observation = ObservationKt.PeekBox();
        var result = new ObservationResult(observation);
        Assert.Equal("Unknown", result.Describe());
    }
}

using TestLibrary.Cat;

namespace IntegrationTests;

public class SealedClassTests
{
    [Fact]
    public void Observation_IsAbstract()
    {
        Assert.True(typeof(Observation).IsAbstract);
    }

    [Fact]
    public void Observation_Alive_IsSealed()
    {
        Assert.True(typeof(Observation.Alive).IsSealed);
    }

    [Fact]
    public void Observation_Dead_IsSealed()
    {
        Assert.True(typeof(Observation.Dead).IsSealed);
    }

    [Fact]
    public void OpenBox_WhenAlive_ReturnsAlive()
    {
        using Observation result = ObservationKt.openBox("Oreo");
        Assert.IsType<Observation.Alive>(result);

        var alive = (Observation.Alive)result;
        using Cat? cat = alive.Cat;
        Assert.NotNull(cat);
        Assert.Equal("Oreo", cat!.Name);
    }

    [Fact]
    public void OpenBox_WhenDead_ReturnsDead()
    {
        using Observation result = ObservationKt.openBox("Rex");
        Assert.IsType<Observation.Dead>(result);

        var dead = (Observation.Dead)result;
        Assert.Equal("The cat was not Rex", dead.Cause);
    }

    [Fact]
    public void Observation_Superposition_IsSealed()
    {
        Assert.True(typeof(Observation.Superposition).IsSealed);
    }

    [Fact]
    public void PeekBox_ReturnsSuperposition()
    {
        using Observation result = ObservationKt.peekBox();
        Assert.IsType<Observation.Superposition>(result);
    }

    [Fact]
    public void Observation_Superposition_ToString()
    {
        using Observation result = ObservationKt.peekBox();
        Assert.Equal("Superposition", result.ToString());
    }

    [Fact]
    public void Observation_WorksWithPatternMatching()
    {
        using Observation result = ObservationKt.openBox("Oreo");

        string message = result switch
        {
            Observation.Superposition => "Unknown - cat is in superposition",
            Observation.Alive a => $"Alive: {a.Cat!.Name}",
            Observation.Dead d => $"Dead: {d.Cause}",
            _ => throw new InvalidOperationException(),
        };

        Assert.Equal("Alive: Oreo", message);
    }

    [Fact]
    public void Observation_Superposition_PatternMatching()
    {
        using Observation result = ObservationKt.peekBox();

        string message = result switch
        {
            Observation.Superposition => "Cat is in superposition",
            Observation.Alive a => $"Alive: {a.Cat!.Name}",
            Observation.Dead d => $"Dead: {d.Cause}",
            _ => throw new InvalidOperationException(),
        };

        Assert.Equal("Cat is in superposition", message);
    }
}

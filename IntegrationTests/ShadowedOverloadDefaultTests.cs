using System.Reflection;
using TestLibrary.Shadowed;

namespace IntegrationTests;

/// <summary>
/// A Kotlin default a real shorter overload shadows (ADR-164): beside <c>Kitten(name)</c>, Kotlin
/// resolves <c>Kitten(name)</c> to that real overload, so <c>Kitten(name, lives = 9)</c>'s default is
/// unreachable. The defaulted parameter stays required in C#, and each C# call reaches the Kotlin
/// overload a Kotlin caller writing the same call would reach.
/// <para>Oreo (black, white in the middle) and Mylo (brown and creamy) take turns.</para>
/// </summary>
public class ShadowedOverloadDefaultTests
{
    [Fact]
    public void Kitten_NameOnly_ReachesTheRealShorterConstructor()
    {
        using var oreo = new Kitten("Oreo");

        Assert.Equal(1, oreo.Lives);
    }

    [Fact]
    public void Kitten_NameAndLives_ReachesTheDefaultedConstructor()
    {
        using var mylo = new Kitten("Mylo", 9);

        Assert.Equal(9, mylo.Lives);
    }

    [Fact]
    public void Kitten_ShadowedLives_IsRequiredAndNotNullable()
    {
        ParameterInfo lives = typeof(Kitten).GetConstructors()
            .SelectMany(c => c.GetParameters())
            .Single(p => p.Name == "lives");

        Assert.Equal(typeof(int), lives.ParameterType);
        Assert.False(lives.IsOptional);
    }

    [Fact]
    public void Greet_EachCallReachesItsOwnOverload()
    {
        using var oreo = new Kitten("Oreo");

        Assert.Equal("short:Mylo", oreo.Greet("Mylo"));
        Assert.Equal("long:Mylo:3", oreo.Greet("Mylo", 3));
    }

    [Fact]
    public void Greet_ShadowedTimes_IsRequiredAndNotNullable()
    {
        ParameterInfo times = typeof(Kitten).GetMethods()
            .Where(m => m.Name == nameof(Kitten.Greet))
            .SelectMany(m => m.GetParameters())
            .Single(p => p.Name == "times");

        Assert.Equal(typeof(int), times.ParameterType);
        Assert.False(times.IsOptional);
    }

    [Fact]
    public void Summon_TopLevel_EachCallReachesItsOwnOverload()
    {
        Assert.Equal("short:Oreo", ShadowedOverloadSample.Summon("Oreo"));
        Assert.Equal("long:Oreo:false", ShadowedOverloadSample.Summon("Oreo", false));
    }

    [Fact]
    public void Purr_Extension_EachCallReachesItsOwnOverload()
    {
        using var mylo = new Kitten("Mylo");

        Assert.Equal("short:Mylo", mylo.Purr());
        Assert.Equal("long:Mylo:false", mylo.Purr(false));
    }
}

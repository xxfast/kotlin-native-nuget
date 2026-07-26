using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// Phase 5: Bidirectional support — C# implements Kotlin interfaces and passes them back.
/// These tests compile but are skipped until reverse P/Invoke is implemented.
/// </summary>
public class BidirectionalTests
{
    private class Dog : IPet
    {
        public string Name { get; }
        public int Legs => 4;
        public string? Nickname => null;
        public Dog(string name) { Name = name; }
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
        public void Nap() { }
        public void Dispose() { }
    }

    [Fact(Skip = "Phase 5: requires reverse P/Invoke")]
    public void CSharpDog_ImplementsIPet()
    {
        using IPet dog = new Dog("Rex");
        Assert.Equal("Rex", dog.Name);
        Assert.Equal("Woof!", dog.Speak());
        Assert.Equal("Hi, I'm Rex the dog", dog.Greet());
    }

    // ADR-040 v1 boundary: only a Kotlin-backed `IPet` (one of the generated wrapper classes,
    // which carry `_handle`) can be passed to an interface-typed parameter. A C#-implemented
    // `IPet` (like `Dog`) has no `_handle`, so `NugetMarshal.HandleOf` must throw rather than
    // crash or silently misbehave. Passing a C#-implemented `IPet` all the way through to Kotlin
    // is the deferred ROADMAP line 145+ item.
    [Fact]
    public void Cat_Befriend_CSharpImplementedPet_ThrowsNotSupportedException()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet dog = new Dog("Rex");
        Assert.Throws<NotSupportedException>(() => oreo.Befriend(dog));
    }
}

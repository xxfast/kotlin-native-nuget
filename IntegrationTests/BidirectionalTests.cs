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
        public Dog(string name) { Name = name; }
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
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

    // [Fact(Skip = "Phase 5: requires reverse P/Invoke")]
    // public void Cat_CanBefriendAnyPet()
    // {
    //     using var oreo = new Cat("Oreo", 9);
    //     using IPet dog = new Dog("Rex");
    //     oreo.Befriend(dog);
    //     using IPet? friend = oreo.Friend;
    //     Assert.NotNull(friend);
    //     Assert.Equal("Rex", friend!.Name);
    // }
}

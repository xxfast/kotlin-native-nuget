using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class ExtensionPropertyTests
{
    [Fact]
    public void Cat_GetIsKitten_ReturnsTrueForNewCatWithNineLives()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.True(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetIsKitten_ReturnsFalseForCatWithFewLivesLeft()
    {
        using var cat = new Cat("Mylo", 3);
        Assert.False(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetIsKitten_ReturnsFalseAtExactlySevenLives()
    {
        using var cat = new Cat("Oreo", 7);
        Assert.False(cat.GetIsKitten());
    }

    [Fact]
    public void Cat_GetLabel_ReturnsNameWithMood()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Oreo (sleepy)", cat.GetLabel());
    }

    [Fact]
    public void Cat_GetLabel_ReturnsMyloWithMood()
    {
        using var cat = new Cat("Mylo", 9);
        Assert.Equal("Mylo (sleepy)", cat.GetLabel());
    }

    [Fact]
    public void String_GetWordCount_ReturnsTwoForTwoWords()
    {
        Assert.Equal(2, "hello world".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_ReturnsOneForSingleWord()
    {
        Assert.Equal(1, "Oreo".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_IgnoresLeadingAndTrailingSpaces()
    {
        Assert.Equal(2, "  Oreo Mylo  ".GetWordCount());
    }

    [Fact]
    public void String_GetWordCount_CountsCatNames()
    {
        Assert.Equal(3, "Oreo and Mylo".GetWordCount());
    }

    // ---- ADR-132 receiver lowering at the extension-property position ----

    // A C#-implemented `Pet`, so an interface receiver has to route through the ADR-084 bridge
    // factory (`NugetMarshal.HandleOf`, no `_handle` field on this object) and dispose the minted
    // transfer handle afterwards. Rex belongs to the neighbours; Oreo tolerates him.
    private sealed class Dog(string name) : IPet
    {
        public string Name { get; } = name;
        public int Legs => 4;
        public string? Nickname => null;
        public string Vibe => "waggy";
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
        public void Nap() { }
        public void Dispose() { }
    }

    // Interface receiver, Kotlin-backed wrapper: `Cat` is an `Animal` is a `Pet`, so the receiver
    // crosses as Oreo's own StableRef handle (nothing minted, nothing to dispose).
    [Fact]
    public void PetReceiver_GetSummary_KotlinBackedCat()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Equal("Oreo/4/Meow! My name is Oreo", oreo.GetSummary());
    }

    // Interface receiver, C#-implemented: Kotlin composes the string from three slot invocations
    // back into this `Dog`, so an echo or a Kotlin-side default cannot pass.
    [Fact]
    public void PetReceiver_GetSummary_CSharpImplementedDog_DispatchesAllThreeSlots()
    {
        using IPet rex = new Dog("Rex");
        Assert.Equal("Rex/4/Woof!", rex.GetSummary());
    }

    // Nullable handle receiver, absent: null crosses as IntPtr.Zero, `this?.name` is null on the
    // Kotlin side, and the call site is static dispatch so there is no NullReferenceException.
    [Fact]
    public void NullableCatReceiver_GetNameOrStray_NullCat()
    {
        Cat? none = null;
        Assert.Equal("stray", none.GetNameOrStray());
    }

    // Nullable handle receiver, present: Mylo is home, so the handle crosses and Kotlin reads his
    // name off it.
    [Fact]
    public void NullableCatReceiver_GetNameOrStray_LiveCat()
    {
        using var mylo = new Cat("Mylo", 3);
        Assert.Equal("Mylo", mylo.GetNameOrStray());
    }
}

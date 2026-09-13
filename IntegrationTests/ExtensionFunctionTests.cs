using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class ExtensionFunctionTests
{
    [Fact]
    public void String_Meowify_AppendsMeow()
    {
        Assert.Equal("Oreo meow!", "Oreo".Meowify());
    }

    [Fact]
    public void String_IsPurring_ReturnsTrueWhenPurring()
    {
        Assert.True("purrfect".IsPurring());
    }

    [Fact]
    public void String_IsPurring_ReturnsFalseWhenNotPurring()
    {
        Assert.False("meow".IsPurring());
    }

    [Fact]
    public void Cat_SayName()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("My name is Oreo", cat.SayName());
    }

    [Fact]
    public void Cat_GreetWith()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal("Hello, Oreo!", cat.GreetWith("Hello"));
    }

    // ADR-105 amendment: `fun Cat?.nameOrStray()` binds on a nullable receiver. Mylo is home, so
    // the handle crosses and Kotlin reads his name off it.
    [Fact]
    public void NullableReceiver_LiveCat_ReturnsName()
    {
        using var mylo = new Cat("Mylo", 9);
        Assert.Equal("Mylo", mylo.NameOrStray());
    }

    // No cat at all: null crosses as IntPtr.Zero, `this?.name` is null on the Kotlin side, and the
    // call site is static dispatch so there is no NullReferenceException.
    [Fact]
    public void NullableReceiver_NullCat_ReturnsStray()
    {
        Cat? none = null;
        Assert.Equal("stray", none.NameOrStray());
    }

    // ---- New ADR: receiver shapes beyond handle / non-null value class ----

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
    // crosses as the cat's own StableRef handle (nothing minted, nothing to dispose). `legs` comes
    // from `Animal` (4) and `speak()` from `Cat`'s override.
    [Fact]
    public void InterfaceReceiver_KotlinCat_DescribesThroughInterfaceMembers()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Equal("Oreo has 4 legs and says Meow! My name is Oreo", oreo.Describe());
    }

    // Interface receiver, anonymous Kotlin object: `strayPet()` has no generated wrapper class of
    // its own, so the extension can only reach `name`/`legs`/`speak()` through `pet_*` dispatch.
    // Three legs is the value no `Cat` can produce.
    [Fact]
    public void InterfaceReceiver_StrayPet_DispatchesThroughTheAnonymousObject()
    {
        using IPet stray = PetKt.StrayPet();
        Assert.Equal("Whiskers the Stray has 3 legs and says Mrrp?", stray.Describe());
    }

    // Interface receiver, C#-implemented: the ADR-084 case. Kotlin composes the string from three
    // slot invocations back into this `Dog`, so an echo or a Kotlin-side default cannot pass.
    [Fact]
    public void InterfaceReceiver_CSharpImplementedDog_DispatchesBackIntoCSharp()
    {
        using IPet rex = new Dog("Rex");
        Assert.Equal("Rex has 4 legs and says Woof!", rex.Describe());
    }

    // Nullable value-class receiver, present: the underlying `String` rides the wire and Kotlin
    // re-wraps it as a `CatId` before calling the extension.
    [Fact]
    public void NullableValueClassReceiver_Value_ReturnsTheId()
    {
        CatId? id = new CatId("Oreo-1");
        Assert.Equal("Oreo-1", id.OrAnonymous());
    }

    // Nullable value-class receiver, absent: null crosses as a null pointer, so `this?.id` is null
    // on the Kotlin side and the elvis branch answers.
    [Fact]
    public void NullableValueClassReceiver_Null_ReturnsAnonymous()
    {
        CatId? id = null;
        Assert.Equal("anonymous", id.OrAnonymous());
    }

    // ADR-132 sub-decision (a), settled with a standalone `dotnet build` probe (2026-09-13):
    // `CatId` generates as a `readonly record struct`, so `this CatId? receiver` is a
    // `Nullable<CatId>`, and C# extension-receiver binding admits only identity, implicit-reference
    // and boxing conversions - `CatId -> CatId?` is an implicit *nullable* conversion, which is
    // none of those. `new CatId("x").OrAnonymous()` is therefore CS1929 ("'CatId' does not contain
    // a definition for 'OrAnonymous' and the best extension method overload ... requires a receiver
    // of type 'CatId?'"), unlike Kotlin, where `fun CatId?.orAnonymous()` IS callable on a non-null
    // `CatId`. Only the `CatId?` overload is emitted, so from C# the call goes through a `CatId?`
    // local (the two tests above); the asymmetry is documented in ADR-132 rather than papered over
    // with a forwarding non-null overload. A test asserting the non-null call site was deleted here.
}

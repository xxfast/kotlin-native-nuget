using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Kdoc;

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

    // ADR-096/ADR-074: `expect fun SunSpot.stretchFor(minutes: Int = 5)` declares its default on
    // the `expect` half only, because Kotlin forbids the `actual` from restating one. The extension
    // route reads the exported declaration's own `hasDefault` bits and never consults the expect
    // index (ADR-096: "class/object/companion/extension read the exported declaration's own bit
    // only", a boundary ADR-164 leaves in place), so `minutes` is NOT widened to `int?` and the C#
    // caller always supplies it. The parameterless call is deliberately absent, not forgotten: a
    // `perch.StretchFor()` here would be CS7036.
    [Fact]
    public void SunSpot_StretchFor_TakesItsMinutesFromTheCaller()
    {
        using var perch = new SunSpot();
        Assert.Contains("stretched 7 min on ", perch.StretchFor(7));
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

    // ADR-132 listed `Enum` among the receivers the function route binds "by construction, no
    // fixture", and nothing in test-library declared one until now. This is that missing fixture,
    // and it is the control for the extension-PROPERTY parity item beside it: `Mood` has a property
    // of its own (`description`), so the generator already emits a `MoodExtensions` class for the
    // enum, and any extension over `Mood` has to merge into that same class rather than declare a
    // second one. If this goes red on a build with no extension PROPERTY over an enum, the defect
    // is older than the parity item.
    [Fact]
    public void MoodReceiver_RallyCry_BindsAnExtensionFunctionOverAnEnum()
    {
        Assert.Equal("grumpy cats of the world, unite", Mood.Grumpy.RallyCry());
    }
}

using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-173: a C#-implemented interface crosses each of the three erased generic routes (the
/// lambda <c>KotlinFunc</c>, the legacy <c>fun &lt;T&gt;</c>, the ADR-147 generic class) and comes
/// back as the SAME instance, not a fresh backing wrapper. A Kotlin-backed pet read at
/// <c>T = IPet</c> materialises as an <c>IPet</c> through the interface's <c>Factories</c> entry.
///
/// Rex the dog visits Oreo and Mylo, goes through every relay in the house, and has to walk out
/// the other side as the very same dog.
/// </summary>
public class ErasedInterfaceIdentityTests
{
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

    // Mylo's squeaky mouse. `ISqueaker` appears in Kotlin ONLY as a lambda type argument
    // (`squeakerRelay(): (Squeaker) -> Squeaker`), so this is the cell that proves an erased
    // position alone is enough to generate its backing wrapper, factory entry and bridge.
    private sealed class SqueakyMouse : ISqueaker
    {
        public string Squeak => "eek";
        public void Dispose() { }
    }

    // Mylo's chew toy. `IChewer` appears in Kotlin ONLY as the type argument of `Box<Chewer>` at a
    // top-level return, the generic-class twin of `ISqueaker`.
    private sealed class RopeToy : IChewer
    {
        public string Chew => "rope";
        public void Dispose() { }
    }

    // --- Lambda route: KotlinFunc<IPet, IPet> ---

    [Fact]
    public void Lambda_PetRelay_IsSpelledWithTheInterface()
    {
        // The explicit type pins the ADR-173 respell: `KotlinFunc<Pet, Pet>` named the ADR-040
        // backing wrapper, which a consumer's own `Dog` can never be.
        using KotlinFunc<IPet, IPet> relay = PetRelayKt.PetRelay();
        Assert.Equal(typeof(KotlinFunc<IPet, IPet>), typeof(PetRelayKt).GetMethod("PetRelay")!.ReturnType);
    }

    [Fact]
    public void Lambda_CSharpDog_ReturnsTheSameInstance()
    {
        using IPet rex = new Dog("Rex");
        using KotlinFunc<IPet, IPet> relay = PetRelayKt.PetRelay();
        Assert.Same(rex, relay.Invoke(rex));
    }

    [Fact]
    public void Lambda_KotlinBackedOreo_MaterialisesAsIPet()
    {
        using var oreo = new Cat("Oreo", 9);
        using KotlinFunc<IPet, IPet> relay = PetRelayKt.PetRelay();
        using IPet relayed = relay.Invoke(oreo);
        Assert.NotNull(relayed);
        Assert.Equal("Oreo", relayed.Name);
    }

    // --- Legacy `fun <T>` route ---

    [Fact]
    public void LegacyConstrained_AdoptPet_CSharpDog_ReturnsTheSameInstance()
    {
        using IPet rex = new Dog("Rex");
        Assert.Same(rex, Helpers.AdoptPet<IPet>(rex));
    }

    [Fact]
    public void LegacyUnconstrained_RelayPet_CSharpDogAtIPet_ReturnsTheSameInstance()
    {
        using IPet rex = new Dog("Rex");
        Assert.Same(rex, PetRelayKt.RelayPet<IPet>(rex));
    }

    [Fact]
    public void LegacyUnconstrained_RelayPet_CSharpDogAtConcreteType_ReturnsTheSameInstance()
    {
        // `T = Dog`: `HandleFor` bridges on the runtime type and the read probes on the
        // `Factories[typeof(Dog)]` miss.
        using var rex = new Dog("Rex");
        Assert.Same(rex, PetRelayKt.RelayPet<Dog>(rex));
    }

    [Fact]
    public void LegacyConstrained_AdoptPet_KotlinBackedOreoAtIPet_MaterialisesAsIPet()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet adopted = Helpers.AdoptPet<IPet>(oreo);
        Assert.NotNull(adopted);
        Assert.Equal("Oreo", adopted.Name);
        Assert.Equal("Hi, I'm Oreo", adopted.Greet());
    }

    [Fact]
    public void LegacyConstrained_AdoptPet_StrayAtIPet_MaterialisesAsIPet()
    {
        // Whiskers has no generated class of her own, so only the interface factory can build her.
        using IPet stray = PetKt.StrayPet();
        using IPet adopted = Helpers.AdoptPet<IPet>(stray);
        Assert.Equal("Whiskers the Stray", adopted.Name);
        Assert.Equal(3, adopted.Legs);
        Assert.Equal("Mrrp?", adopted.Speak());
    }

    // --- ADR-147 generic class route: PetBox<T : Pet> ---

    [Fact]
    public void GenericClass_PetBox_CSharpDog_ReturnsTheSameInstance()
    {
        using IPet rex = new Dog("Rex");
        using var box = new PetBox<IPet>(rex);
        Assert.Same(rex, box.Value);
    }

    [Fact]
    public void GenericClass_PetBox_KotlinBackedMyloAtIPet_MaterialisesAsIPet()
    {
        using var mylo = new Cat("Mylo", 4);
        using var box = new PetBox<IPet>(mylo);
        using IPet boxed = box.Value;
        Assert.NotNull(boxed);
        Assert.Equal("Mylo", boxed.Name);
    }

    // --- Interface reached only through an erased position ---

    [Fact]
    public void ErasedOnlyInterface_SqueakerRelay_IsSpelledWithTheInterface()
    {
        Assert.Equal(
            typeof(KotlinFunc<ISqueaker, ISqueaker>),
            typeof(PetRelayKt).GetMethod("SqueakerRelay")!.ReturnType);
    }

    [Fact]
    public void ErasedOnlyInterface_CSharpSqueaker_ReturnsTheSameInstance()
    {
        using ISqueaker mouse = new SqueakyMouse();
        using KotlinFunc<ISqueaker, ISqueaker> relay = PetRelayKt.SqueakerRelay();
        ISqueaker back = relay.Invoke(mouse);
        Assert.Same(mouse, back);
        Assert.Equal("eek", back.Squeak);
    }

    // --- Interface reached only as a generic-class type argument ---

    [Fact]
    public void ErasedOnlyGenericClassArgument_ChewerBox_IsSpelledWithTheInterface()
    {
        Assert.Equal(
            typeof(Box<IChewer>),
            typeof(PetRelayKt).GetMethod("ChewerBox")!.ReturnType);
    }

    [Fact]
    public void ErasedOnlyGenericClassArgument_KotlinBackedChewer_MaterialisesAsIChewer()
    {
        using Box<IChewer> box = PetRelayKt.ChewerBox();
        using IChewer toy = box.Value;
        Assert.Equal("nom", toy.Chew);
    }

    [Fact]
    public void ErasedOnlyGenericClassArgument_CSharpChewer_ReturnsTheSameInstance()
    {
        using IChewer rope = new RopeToy();
        using var box = new Box<IChewer>(rope);
        IChewer back = box.Value;
        Assert.Same(rope, back);
        Assert.Equal("rope", back.Chew);
    }
}

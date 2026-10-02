using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ROADMAP line 51, ADR-160 amendment: a top-level Kotlin function that takes ordinary value
/// parameters and returns a lambda binds in C# as a callable <c>KotlinFunc&lt;...&gt;</c>, for every
/// parameter mechanism an ordinary top-level function already accepts: an interface (including a
/// C#-implemented one the returned lambda keeps alive), an exported class, a collection, an enum,
/// and a nullable primitive whose null has to survive the crossing.
///
/// Oreo and Mylo hand things to suppliers, walk away, and come back later to ask for them.
/// </summary>
public class LambdaReturnParameterTests
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

    // --- Interface parameter: petSupplier(pet: Pet): () -> Pet ---

    [Fact]
    public void PetSupplier_IsSpelledWithTheInterface()
    {
        var method = typeof(PetRelayKt).GetMethod("PetSupplier")!;
        Assert.Equal(typeof(KotlinFunc<IPet>), method.ReturnType);
        Assert.Equal(typeof(IPet), Assert.Single(method.GetParameters()).ParameterType);
    }

    // Rex hands himself to the supplier, and the supplier hands exactly Rex back, every time.
    [Fact]
    public void PetSupplier_CSharpImplementedPet_ComesBackAsTheSameDog()
    {
        using IPet rex = new Dog("Rex");
        using KotlinFunc<IPet> supplier = PetRelayKt.PetSupplier(rex);
        Assert.Same(rex, supplier.Invoke());
        Assert.Same(rex, supplier.Invoke());
    }

    // The supplier outlives the call that captured Rex: a GC round between creating it and asking
    // it must not have released the bridge the lambda still holds.
    [Fact]
    public void PetSupplier_CSharpImplementedPet_SurvivesAGcAfterTheCall()
    {
        using IPet rex = new Dog("Rex");
        using KotlinFunc<IPet> supplier = PetRelayKt.PetSupplier(rex);
        GC.Collect();
        GC.WaitForPendingFinalizers();
        NugetMarshal.GcCollect();
        IPet back = supplier.Invoke();
        Assert.Same(rex, back);
        Assert.Equal("Woof!", back.Speak());
    }

    // Whiskers the Stray has no generated class of her own, so the supplier can only hand her
    // back through the interface factory.
    [Fact]
    public void PetSupplier_KotlinBackedStray_ComesBackAsAPet()
    {
        using IPet stray = PetKt.StrayPet();
        using KotlinFunc<IPet> supplier = PetRelayKt.PetSupplier(stray);
        using IPet back = supplier.Invoke();
        Assert.Equal("Whiskers the Stray", back.Name);
        Assert.Equal(3, back.Legs);
        Assert.Equal("Mrrp?", back.Speak());
    }

    // --- Exported class parameter: catSupplier(cat: Cat): () -> Cat ---

    [Fact]
    public void CatSupplier_HandsBackOreo()
    {
        Assert.Equal(typeof(KotlinFunc<Cat>), typeof(PetRelayKt).GetMethod("CatSupplier")!.ReturnType);

        using Cat oreo = new Cat("Oreo", 9);
        using KotlinFunc<Cat> supplier = PetRelayKt.CatSupplier(oreo);
        using Cat first = supplier.Invoke();
        using Cat second = supplier.Invoke();
        Assert.Equal("Oreo", first.Name);
        Assert.Equal(9, first.Lives);
        Assert.Equal("Oreo", second.Name);
    }

    // The lambda holds Oreo, not a copy: renaming his owner after the call shows through it.
    [Fact]
    public void CatSupplier_HoldsTheSameKotlinCat()
    {
        using Cat oreo = new Cat("Oreo", 9);
        using KotlinFunc<Cat> supplier = PetRelayKt.CatSupplier(oreo);
        oreo.Owner = "Isuru";
        using Cat back = supplier.Invoke();
        Assert.Equal("Isuru", back.Owner);
    }

    // --- Collection parameter: listSupplier(xs: List<Int>): () -> Int ---

    [Fact]
    public void ListSupplier_SumsMylosTreats()
    {
        var method = typeof(PetRelayKt).GetMethod("ListSupplier")!;
        Assert.Equal(typeof(KotlinFunc<int>), method.ReturnType);
        Assert.Equal(typeof(IReadOnlyList<int>), Assert.Single(method.GetParameters()).ParameterType);

        using KotlinFunc<int> supplier = PetRelayKt.ListSupplier(new List<int> { 3, 4, 5 });
        Assert.Equal(12, supplier.Invoke());
    }

    [Fact]
    public void ListSupplier_EmptyBowl_IsZero()
    {
        using KotlinFunc<int> supplier = PetRelayKt.ListSupplier(new List<int>());
        Assert.Equal(0, supplier.Invoke());
    }

    // --- Enum parameter (side finding A): moodSupplier(m: Mood): () -> Int ---

    [Fact]
    public void MoodSupplier_CarriesTheMoodIntoTheLambda()
    {
        var method = typeof(PetRelayKt).GetMethod("MoodSupplier")!;
        Assert.Equal(typeof(KotlinFunc<int>), method.ReturnType);
        Assert.Equal(typeof(Mood), Assert.Single(method.GetParameters()).ParameterType);

        using KotlinFunc<int> happy = PetRelayKt.MoodSupplier(Mood.Happy);
        using KotlinFunc<int> grumpy = PetRelayKt.MoodSupplier(Mood.Grumpy);
        Assert.Equal((int)Mood.Happy, happy.Invoke());
        Assert.Equal((int)Mood.Grumpy, grumpy.Invoke());
    }

    // --- Nullable primitive parameter (side finding B): nullableSupplier(n: Int?): () -> Int ---

    [Fact]
    public void NullableSupplier_DeclaresANullableInt()
    {
        var method = typeof(PetRelayKt).GetMethod("NullableSupplier")!;
        Assert.Equal(typeof(KotlinFunc<int>), method.ReturnType);
        Assert.Equal(typeof(int?), Assert.Single(method.GetParameters()).ParameterType);
    }

    // Mylo forgot how many naps he took: null crosses as null, and the sentinel says so.
    [Fact]
    public void NullableSupplier_Null_CrossesAsNull()
    {
        using KotlinFunc<int> supplier = PetRelayKt.NullableSupplier(null);
        Assert.Equal(-1, supplier.Invoke());
    }

    [Fact]
    public void NullableSupplier_Value_CrossesAsTheValue()
    {
        using KotlinFunc<int> supplier = PetRelayKt.NullableSupplier(5);
        Assert.Equal(5, supplier.Invoke());
    }

    // Zero is the value a narrowed `int` default would hide behind; it must not read as null.
    [Fact]
    public void NullableSupplier_Zero_IsNotNull()
    {
        using KotlinFunc<int> supplier = PetRelayKt.NullableSupplier(0);
        Assert.Equal(0, supplier.Invoke());
    }

    // --- Arity-1 return with a parameter: adder(n: Int): (Int) -> Int ---

    // Oreo gets two extra treats on top of whatever he's given.
    [Fact]
    public void Adder_AddsTheCapturedTreats()
    {
        Assert.Equal(typeof(KotlinFunc<int, int>), typeof(PetRelayKt).GetMethod("Adder")!.ReturnType);

        using KotlinFunc<int, int> twoMore = PetRelayKt.Adder(2);
        Assert.Equal(5, twoMore.Invoke(3));
        Assert.Equal(2, twoMore.Invoke(0));
    }

    // --- Throw path: pickyPetSupplier throws before making a lambda ---

    [Fact]
    public void PickyPetSupplier_GrumpyPet_ThrowsAMappedException()
    {
        using IPet grumpy = new Dog("Grumpy");
        Assert.Throws<KotlinArgumentException>(() => PetRelayKt.PickyPetSupplier(grumpy));

        using IPet rex = new Dog("Rex");
        using KotlinFunc<IPet> supplier = PetRelayKt.PickyPetSupplier(rex);
        Assert.Same(rex, supplier.Invoke());
    }

    // --- Regression guards: the routes that bind today keep binding ---

    [Fact]
    public void PetRelay_And_Greeter_StillBind()
    {
        Assert.Equal(typeof(KotlinFunc<IPet, IPet>), typeof(PetRelayKt).GetMethod("PetRelay")!.ReturnType);

        using KotlinFunc<string, string> greet = Mappings.Greeter("Hello");
        Assert.Equal("Hello, Mylo!", greet.Invoke("Mylo"));
    }
}

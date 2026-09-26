using System.Reflection;
using TestLibrary.Lineage;

namespace IntegrationTests;

/// <summary>
/// ROADMAP Phase 4, "<c>interface Derived : Base</c> flattens". A Kotlin interface that extends
/// another exported interface must render as C# interface inheritance
/// (<c>IPet : INamed, IAged, IDisposable</c>, <c>IHouseCat : IPet, IDisposable</c>,
/// <c>IIntHolder : IHolder&lt;int&gt;, IDisposable</c>), declaring its own members only and
/// inheriting the rest.
///
/// Today none of this file builds. <c>renderInterface</c> hardcodes <c>: IDisposable</c>, so
/// <c>IPet</c> has neither <c>Name</c> nor <c>Age</c>, yet the ADR-084 bridge for the
/// <c>Doorstep</c> parameters reads <c>impl.Name</c> off an <c>IPet</c>: the generated
/// <c>Interop.cs</c> fails CS1061 before a consumer line compiles. <c>KibbleJar : Holder&lt;Int&gt;</c>
/// renders <c>: IHolder</c> (CS0305), and <c>ShowCat</c>'s unexported <c>Pedigree</c> members
/// vanish with no diagnostic. So the expected red is a compile failure of
/// <c>nugetCompileInterop</c> and of this project, not N failed assertions.
///
/// The reflection facts are there so a compile that goes green for the wrong reason cannot pass:
/// a flattened interface that re-declares every inherited member would also let the runtime
/// cells compile, but it is not an <see cref="INamed"/>, and <see cref="IIntHolder"/> redeclaring
/// the substituted <c>Peek</c> is the owner-filter bug the memo found.
///
/// Oreo (black, white middle) is the Kotlin cat. Mylo (brown and creamy) is the C# one, and he
/// keeps a list of everything Kotlin asked him.
/// </summary>
public class InterfaceSuperInterfaceTests
{
    // --- The base lists: C# interface inheritance, transitively ---

    [Theory]
    [InlineData(typeof(INamed))]
    [InlineData(typeof(IAged))]
    [InlineData(typeof(IDisposable))]
    public void IPet_ExtendsBothSupers(Type ancestor)
    {
        Assert.True(ancestor.IsAssignableFrom(typeof(IPet)), $"IPet is not assignable to {ancestor.Name}");
    }

    [Theory]
    [InlineData(typeof(IPet))]
    [InlineData(typeof(INamed))]
    [InlineData(typeof(IAged))]
    [InlineData(typeof(IDisposable))]
    public void IHouseCat_IsAssignableToEveryAncestor(Type ancestor)
    {
        Assert.True(ancestor.IsAssignableFrom(typeof(IHouseCat)), $"IHouseCat is not assignable to {ancestor.Name}");
    }

    [Fact]
    public void IIntHolder_ExtendsTheClosedGenericSuper()
    {
        Assert.True(typeof(IHolder<int>).IsAssignableFrom(typeof(IIntHolder)));
        Assert.Contains(typeof(IHolder<int>), typeof(IIntHolder).GetInterfaces());
    }

    [Fact]
    public void IShowCat_KeepsTheExportedSuper()
    {
        Assert.True(typeof(INamed).IsAssignableFrom(typeof(IShowCat)));
    }

    // --- Own members only: an interface's GetMethods / GetProperties list declared members ---

    private static string[] OwnMethods(Type type) =>
        type.GetMethods().Where(m => !m.IsSpecialName).Select(m => m.Name).OrderBy(n => n).ToArray();

    private static string[] OwnProperties(Type type) =>
        type.GetProperties().Select(p => p.Name).OrderBy(n => n).ToArray();

    [Fact]
    public void INamed_DeclaresTheRootMembers()
    {
        Assert.Equal(new[] { "Greet" }, OwnMethods(typeof(INamed)));
        Assert.Equal(new[] { "Name", "Nickname" }, OwnProperties(typeof(INamed)));
    }

    [Fact]
    public void IPet_DeclaresOnlyFeed()
    {
        Assert.Equal(new[] { "Feed" }, OwnMethods(typeof(IPet)));
        Assert.Empty(OwnProperties(typeof(IPet)));
    }

    [Fact]
    public void IHouseCat_DeclaresOnlyPurr_AndDoesNotRedeclareTheGreetOverride()
    {
        // Kotlin's HouseCat restates `greet()` with a default body. With `: IPet` present, a
        // second `string Greet()` here is CS0108, which the warnings-as-errors consumer build
        // turns into a break, so the identical-signature override must be omitted.
        Assert.Equal(new[] { "Purr" }, OwnMethods(typeof(IHouseCat)));
        Assert.Empty(OwnProperties(typeof(IHouseCat)));
    }

    [Fact]
    public void IIntHolder_DeclaresOnlyShake_NotTheSubstitutedPeek()
    {
        // KSP reports the substituted `peek(): Int` as owned by IntHolder, so an owner filter
        // renders `int Peek()` on IIntHolder today (and hides IHolder<int>.Peek, CS0108).
        Assert.Equal(new[] { "Shake" }, OwnMethods(typeof(IIntHolder)));
        Assert.Empty(OwnProperties(typeof(IIntHolder)));
    }

    [Fact]
    public void IShowCat_ReHomesTheUnexportedPedigreeMembers()
    {
        // `Pedigree` is outside rootPackage: no IPedigree exists, and its `breed` / `registry`
        // are declared on IShowCat itself (the ADR-101 mirror), so a C# implementer can satisfy
        // the bridge that reads them.
        Assert.Equal(new[] { "Pose", "Registry" }, OwnMethods(typeof(IShowCat)));
        Assert.Equal(new[] { "Breed" }, OwnProperties(typeof(IShowCat)));
        Assert.DoesNotContain(typeof(IShowCat).Assembly.GetTypes(), t => t.Name == "IPedigree");
    }

    // --- Classes carry the full interface set ---

    [Theory]
    [InlineData(typeof(IHouseCat))]
    [InlineData(typeof(IPet))]
    [InlineData(typeof(INamed))]
    [InlineData(typeof(IAged))]
    public void Tuxedo_ImplementsTheDeepestInterfaceAndEveryAncestor(Type ancestor)
    {
        Assert.True(ancestor.IsAssignableFrom(typeof(Tuxedo)), $"Tuxedo is not assignable to {ancestor.Name}");
    }

    [Fact]
    public void KibbleJar_ImplementsTheClosedGenericInterface()
    {
        Assert.True(typeof(IHolder<int>).IsAssignableFrom(typeof(KibbleJar)));
    }

    // --- Kotlin-implemented, returned as the derived interface (ADR-040 backing wrapper) ---

    [Fact]
    public void ReturnedHouseCat_ThroughINamed_CallsTheRootMembers()
    {
        using IHouseCat oreo = Lineage.AdoptHouseCat();
        INamed named = oreo;

        Assert.Equal("Oreo", named.Name);
        Assert.Equal("Cookie", named.Nickname);
        // HouseCat's default body, reached through the ROOT interface's slot.
        Assert.Equal("Purr, I'm Oreo", named.Greet());
    }

    [Fact]
    public void ReturnedHouseCat_ThroughIAged_CallsTheSecondSupersMember()
    {
        using IHouseCat oreo = Lineage.AdoptHouseCat();
        IAged aged = oreo;

        Assert.Equal(5, aged.Age);
    }

    [Fact]
    public void ReturnedHouseCat_ThroughIPet_CallsTheMiddleMember()
    {
        using IHouseCat oreo = Lineage.AdoptHouseCat();
        IPet pet = oreo;

        Assert.Equal("Oreo crunches the tuna", pet.Feed("tuna"));
        Assert.Equal("Oreo", pet.Name);
        Assert.Equal(5, pet.Age);
    }

    [Fact]
    public void ReturnedHouseCat_OwnMember_StillDispatches()
    {
        using IHouseCat oreo = Lineage.AdoptHouseCat();

        Assert.Equal("Oreo purrs x2", oreo.Purr(2));
    }

    [Fact]
    public void ReturnedPet_MiddleLevel_CallsInheritedMembersIncludingANullNickname()
    {
        using IPet mylo = Lineage.AdoptPet();

        Assert.Equal("Mylo", ((INamed)mylo).Name);
        Assert.Null(((INamed)mylo).Nickname);
        Assert.Equal("Mylo says hi", ((INamed)mylo).Greet());
        Assert.Equal(4, ((IAged)mylo).Age);
        Assert.Equal("Mylo laps up the milk", mylo.Feed("milk"));
    }

    [Fact]
    public void ReturnedShowCat_CallsKeptAndReHomedSuperMembers()
    {
        using IShowCat oreo = Lineage.AdoptShowCat();
        INamed named = oreo;

        Assert.Equal("Oreo", named.Name);
        Assert.Equal("Sir Cookie", named.Nickname);
        Assert.Equal("Oreo bows", named.Greet());
        Assert.Equal("Tuxedo Shorthair", oreo.Breed);
        Assert.Equal("Oreo is registered with the Biscuit Fanciers", oreo.Registry());
        Assert.Equal("Oreo poses on the white bit", oreo.Pose());
    }

    [Fact]
    public void ReturnedIntHolder_ThroughIHolderOfInt_CallsTheSubstitutedMembers()
    {
        using IIntHolder tin = Lineage.TreatTin();
        IHolder<int> holder = tin;

        Assert.Equal(12, holder.Size);
        Assert.Equal(7, holder.Peek());
        Assert.Equal("Mylo hears 12 treats rattle", tin.Shake());
    }

    // --- Kotlin-implemented exported classes ---

    [Fact]
    public void Tuxedo_ThroughEachAncestor_CallsInheritedMembers()
    {
        using var oreo = new Tuxedo("Oreo", 5);
        INamed named = oreo;
        IAged aged = oreo;
        IPet pet = oreo;
        IHouseCat houseCat = oreo;

        Assert.Equal("Oreo", named.Name);
        Assert.Null(named.Nickname);
        // Tuxedo does not override greet: the HouseCat default answers through INamed.
        Assert.Equal("Purr, I'm Oreo", named.Greet());
        Assert.Equal(5, aged.Age);
        Assert.Equal("Oreo the tuxedo nibbles the salmon", pet.Feed("salmon"));
        Assert.Equal("Oreo the tuxedo purrs x4", houseCat.Purr(4));
    }

    [Fact]
    public void KibbleJar_ThroughIHolderOfInt_CallsTheConcreteMembers()
    {
        using var jar = new KibbleJar(10);
        IHolder<int> holder = jar;

        Assert.Equal(10, holder.Size);
        Assert.Equal(9, holder.Peek());
    }

    // --- Kotlin-implemented instances passed back to Kotlin (handle unwrap, not the bridge) ---

    [Fact]
    public void Tuxedo_PassedAtEveryLevel_KotlinCallsInheritedMembersOnTheOriginal()
    {
        using var doorstep = new Doorstep();
        using var oreo = new Tuxedo("Oreo", 5);

        Assert.Equal(
            "Oreo / no nickname / age 5 / Purr, I'm Oreo / Oreo the tuxedo nibbles the tuna / Oreo the tuxedo purrs x3",
            doorstep.LetIn(oreo));
        Assert.Equal("Oreo is 5 and Oreo the tuxedo nibbles the kibble", doorstep.Weigh(oreo));
        // Compiles only once Tuxedo is transitively an INamed.
        Assert.Equal("Purr, I'm Oreo (Oreo)", doorstep.CallOut(oreo));
    }

    [Fact]
    public void ReturnedHouseCat_PassedBack_KotlinCallsInheritedMembersOnTheOriginal()
    {
        using var doorstep = new Doorstep();
        using IHouseCat oreo = Lineage.AdoptHouseCat();

        Assert.Equal(
            "Oreo / Cookie / age 5 / Purr, I'm Oreo / Oreo crunches the tuna / Oreo purrs x3",
            doorstep.LetIn(oreo));
        Assert.Equal("Purr, I'm Oreo (Oreo)", doorstep.CallOut(oreo));
    }

    [Fact]
    public void ReturnedPet_PassedAtTheRootParameter_KotlinCallsGreetOnTheOriginal()
    {
        using var doorstep = new Doorstep();
        using IPet mylo = Lineage.AdoptPet();

        Assert.Equal("Mylo says hi (Mylo)", doorstep.CallOut(mylo));
        Assert.Equal("Mylo is 4 and Mylo laps up the kibble", doorstep.Weigh(mylo));
    }

    [Fact]
    public void ReturnedShowCat_PassedBack_KotlinCallsReHomedMembersOnTheOriginal()
    {
        using var doorstep = new Doorstep();
        using IShowCat oreo = Lineage.AdoptShowCat();

        Assert.Equal(
            "Oreo the Tuxedo Shorthair: Oreo is registered with the Biscuit Fanciers, Oreo poses on the white bit",
            doorstep.Judge(oreo));
    }

    // --- C#-implemented, passed to Kotlin through the ADR-084 bridge factory ---

    /// <summary>
    /// Mylo, implemented in C# at the deepest level. Implementing <see cref="IHouseCat"/> alone
    /// obliges every inherited member, which is exactly what the Kotlin bridge reads. Records each
    /// call so the tests see which members Kotlin reached and in what order.
    /// </summary>
    private sealed class MyloHouseCat : IHouseCat
    {
        public List<string> Calls { get; } = new();

        public string Name
        {
            get
            {
                Calls.Add("Name");
                return "Mylo";
            }
        }

        public string? Nickname
        {
            get
            {
                Calls.Add("Nickname");
                return "Creamy";
            }
        }

        public int Age
        {
            get
            {
                Calls.Add("Age");
                return 4;
            }
        }

        public string Greet()
        {
            Calls.Add("Greet()");
            return "Mylo blinks slowly";
        }

        public string Feed(string food)
        {
            Calls.Add($"Feed({food})");
            return $"Mylo laps up the {food}";
        }

        public string Purr(int times)
        {
            Calls.Add($"Purr({times})");
            return $"Mylo purrs x{times}";
        }

        public void Dispose() { }
    }

    [Fact]
    public void CSharpHouseCat_AtTheDeepestParameter_KotlinReachesEveryInheritedMember()
    {
        using var doorstep = new Doorstep();
        using var mylo = new MyloHouseCat();

        string result = doorstep.LetIn(mylo);

        Assert.Equal(new[] { "Name", "Nickname", "Age", "Greet()", "Feed(tuna)", "Purr(3)" }, mylo.Calls);
        Assert.Equal("Mylo / Creamy / age 4 / Mylo blinks slowly / Mylo laps up the tuna / Mylo purrs x3", result);
    }

    [Fact]
    public void CSharpHouseCat_AtTheMiddleParameter_KotlinReachesBothSupers()
    {
        using var doorstep = new Doorstep();
        using var mylo = new MyloHouseCat();

        string result = doorstep.Weigh(mylo);

        Assert.Equal(new[] { "Name", "Age", "Feed(kibble)" }, mylo.Calls);
        Assert.Equal("Mylo is 4 and Mylo laps up the kibble", result);
    }

    [Fact]
    public void CSharpHouseCat_AtTheRootParameter_KotlinReachesTheRootMembers()
    {
        using var doorstep = new Doorstep();
        using var mylo = new MyloHouseCat();

        string result = doorstep.CallOut(mylo);

        Assert.Equal(new[] { "Greet()", "Name" }, mylo.Calls);
        Assert.Equal("Mylo blinks slowly (Mylo)", result);
    }

    [Fact]
    public void CSharpHouseCat_RepeatedAtEveryLevel_KeepsEachSlotOnItsOwnMember()
    {
        // ADR-084 caches bridge state per implementer; the same Mylo crosses as IHouseCat, IPet
        // and INamed in turn, so each crossing must build (or reuse) the slot table for ITS
        // parameter type, not the first one's.
        using var doorstep = new Doorstep();
        using var mylo = new MyloHouseCat();

        doorstep.LetIn(mylo);
        doorstep.CallOut(mylo);
        doorstep.Weigh(mylo);
        doorstep.LetIn(mylo);

        Assert.Equal(
            new[]
            {
                "Name", "Nickname", "Age", "Greet()", "Feed(tuna)", "Purr(3)",
                "Greet()", "Name",
                "Name", "Age", "Feed(kibble)",
                "Name", "Nickname", "Age", "Greet()", "Feed(tuna)", "Purr(3)",
            },
            mylo.Calls);
    }

    /// <summary>
    /// Mylo entering a show: <c>Breed</c> and <c>Registry</c> come from Kotlin's unexported
    /// <c>Pedigree</c> and are declared on <see cref="IShowCat"/> itself.
    /// </summary>
    private sealed class MyloShowCat : IShowCat
    {
        public List<string> Calls { get; } = new();

        public string Name
        {
            get
            {
                Calls.Add("Name");
                return "Mylo";
            }
        }

        public string? Nickname => null;

        public string Greet() => "Mylo nods";

        public string Breed
        {
            get
            {
                Calls.Add("Breed");
                return "Creamy Longhair";
            }
        }

        public string Registry()
        {
            Calls.Add("Registry()");
            return "Mylo is registered with the Malted Milk Society";
        }

        public string Pose()
        {
            Calls.Add("Pose()");
            return "Mylo poses mid-yawn";
        }

        public void Dispose() { }
    }

    [Fact]
    public void CSharpShowCat_AtItsParameter_KotlinReachesTheReHomedMembers()
    {
        using var doorstep = new Doorstep();
        using var mylo = new MyloShowCat();

        string result = doorstep.Judge(mylo);

        Assert.Equal(new[] { "Name", "Breed", "Registry()", "Pose()" }, mylo.Calls);
        Assert.Equal(
            "Mylo the Creamy Longhair: Mylo is registered with the Malted Milk Society, Mylo poses mid-yawn",
            result);
    }
}

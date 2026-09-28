using TestLibrary.Cat;
using TestLibrary.Nested;
using TestLibrary.Petlist;

namespace IntegrationTests;

/// <summary>
/// ADR-176: an interface is a collection component. <c>List&lt;Pet&gt;</c> reaches C# as
/// <c>IReadOnlyList&lt;IPet&gt;</c> (and <c>Set</c>, <c>Map</c> value, <c>Map</c> key) at every
/// position. The failure this item exists to prevent is "binds, then throws at runtime" (no backing
/// wrapper or no <c>Factories</c> key for the element), which no Tier 1 text assertion can see, so
/// every fact below reads at least one Kotlin-backed element and asserts its value.
///
/// Oreo and Mylo are fostering. Rex the dog is the C# guest who has to leave as the same dog.
/// </summary>
public class InterfaceCollectionTests
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

    private static void DisposeAll(IEnumerable<IPet?> pets)
    {
        foreach (IPet? pet in pets) pet?.Dispose();
    }

    // ---- Kotlin-backed reads, one per position ----

    [Fact]
    public void Residents_PropertyGetter_ReadsKotlinBackedElementsAsPetWrappers()
    {
        using var home = new FosterHome();
        IReadOnlyList<IPet> residents = home.Residents;
        Assert.Equal(new[] { "Oreo", "Mylo" }, residents.Select(p => p.Name));
        Assert.All(residents, p => Assert.IsType<Pet>(p));   // the backing wrapper, never Cat
        DisposeAll(residents);
    }

    [Fact]
    public void Lodgers_GetterIsTheOnlyPosition_StillMaterialisesTheElement()
    {
        // `Lodger` appears in Kotlin ONLY as this property's element. On main the getter binds but
        // nothing makes the interface reachable: no `Lodger` backing class, no `ILodger` factory key.
        using var board = new LodgerBoard();
        IReadOnlyList<ILodger> lodgers = board.Lodgers;
        Assert.Equal(new[] { "Oreo", "Mylo" }, lodgers.Select(l => l.Name));
        foreach (ILodger lodger in lodgers) lodger.Dispose();
    }

    [Fact]
    public void PerchRow_ListOfNestedClass_MaterialisesEachPerch()
    {
        // Pre-existing bug, independent of interfaces: binds on main with no `Aviary.Perch`
        // factory key (factoryEntries skips nested declarations), so the first element throws.
        using var aviary = new Aviary("foster");
        IReadOnlyList<Aviary.Perch> perches = aviary.PerchRow();
        Assert.Equal(new[] { 9, 1 }, perches.Select(p => p.Height));
        foreach (Aviary.Perch perch in perches) perch.Dispose();
    }

    [Fact]
    public void Roster_PropertySetter_KotlinSeesEveryElement()
    {
        using var home = new FosterHome();
        using var mylo = new Cat("Mylo");
        var rex = new Dog("Rex");
        home.Roster = new IPet[] { mylo, rex };
        Assert.Equal("Mylo,Rex", home.RosterNames());

        IReadOnlyList<IPet> back = home.Roster;
        Assert.Equal("Mylo", back[0].Name);
        Assert.Same(rex, back[1]);
        back[0].Dispose();
    }

    [Fact]
    public void ResidentsNow_ListReturn_ReadsKotlinBackedElements()
    {
        using var home = new FosterHome();
        IReadOnlyList<IPet> pets = home.ResidentsNow();
        Assert.Equal(new[] { "Oreo", "Mylo" }, pets.Select(p => p.Name));
        Assert.All(pets, p => Assert.IsNotType<Cat>(p));
        Assert.Equal("Oreo fetches the yarn", pets[0].Fetch("yarn"));
        DisposeAll(pets);
    }

    [Fact]
    public void MaybeResidents_NullableElement_KeepsTheGap()
    {
        using var home = new FosterHome();
        IReadOnlyList<IPet?> pets = home.MaybeResidents();
        Assert.Equal(3, pets.Count);
        Assert.Equal("Oreo", pets[0]!.Name);
        Assert.Null(pets[1]);
        Assert.Equal("Mylo", pets[2]!.Name);
        DisposeAll(pets);
    }

    [Fact]
    public void FosterLineup_TopLevelFunction_ReadsKotlinBackedElements()
    {
        IReadOnlyList<IPet> pets = Lineup.FosterLineup();
        Assert.Equal(new[] { "Mylo", "Oreo" }, pets.Select(p => p.Name));
        DisposeAll(pets);
    }

    [Fact]
    public void Roll_MixedKotlinAndCSharpElements_KotlinCallsEachOne()
    {
        using var home = new FosterHome();
        using var oreo = new Cat("Oreo");
        var rex = new Dog("Rex");
        string rollCall = home.Roll(new IPet[] { oreo, rex });
        Assert.StartsWith("Oreo: ", rollCall);
        Assert.EndsWith(" | Rex: Woof!", rollCall);   // Kotlin called back into the C# Dog
    }

    [Fact]
    public void Echo_CSharpElementComesBackAsTheSameObject_KotlinElementAsAWrapper()
    {
        using var home = new FosterHome();
        using var oreo = new Cat("Oreo");
        var rex = new Dog("Rex");
        IReadOnlyList<IPet> back = home.Echo(new IPet[] { rex, oreo });
        Assert.Same(rex, back[0]);                     // ADR-084 token probe, ADR-173
        Assert.Equal("Oreo", back[1].Name);
        Assert.IsType<Pet>(back[1]);                   // a fresh backing wrapper, not the Cat
        back[1].Dispose();
    }

    [Fact]
    public void PetSet_SetReturn_ReadsKotlinBackedElements()
    {
        using var home = new FosterHome();
        IReadOnlySet<IPet> pets = home.PetSet();
        Assert.Equal(new[] { "Mylo", "Oreo" }, pets.Select(p => p.Name).OrderBy(n => n));
        DisposeAll(pets);
    }

    [Fact]
    public void ByName_MapValue_ReadsKotlinBackedElements()
    {
        using var home = new FosterHome();
        IReadOnlyDictionary<string, IPet> byName = home.ByName();
        Assert.Equal("Oreo", byName["oreo"].Name);
        Assert.Equal("Mylo", byName["mylo"].Name);
        DisposeAll(byName.Values);
    }

    [Fact]
    public void Colours_MapKey_ReadsKotlinBackedKeys()
    {
        using var home = new FosterHome();
        IReadOnlyDictionary<IPet, string> colours = home.Colours();
        Dictionary<string, string> byName = colours.ToDictionary(kv => kv.Key.Name, kv => kv.Value);
        Assert.Equal("black with a white middle", byName["Oreo"]);
        Assert.Equal("brown and creamy", byName["Mylo"]);
        DisposeAll(colours.Keys);
    }

    [Fact]
    public void EchoColours_CSharpKey_ComesBackAsTheSameObject()
    {
        using var home = new FosterHome();
        var rex = new Dog("Rex");
        IReadOnlyDictionary<IPet, string> back =
            home.EchoColours(new Dictionary<IPet, string> { [rex] = "golden" });
        Assert.Same(rex, Assert.Single(back.Keys));
        Assert.Equal("golden", back[rex]);             // reference-equality key lookup
    }

    [Fact]
    public void Litters_NestedList_ReadsKotlinBackedElements()
    {
        using var home = new FosterHome();
        IReadOnlyList<IReadOnlyList<IPet>> litters = home.Litters();
        Assert.Equal("Oreo", Assert.Single(litters[0]).Name);
        Assert.Equal("Mylo", Assert.Single(litters[1]).Name);
        DisposeAll(litters.SelectMany(l => l));
    }

    [Fact]
    public void KeeperRoster_ListOfNestedInterface_MaterialisesEachKeeper()
    {
        using var aviary = new Aviary("foster");
        IReadOnlyList<Aviary.IKeeper> keepers = aviary.KeeperRoster();
        Assert.Equal(new[] { "morning keeper of foster", "evening keeper of foster" },
            keepers.Select(k => k.Greet()));
        foreach (Aviary.IKeeper keeper in keepers) keeper.Dispose();
    }

    // ---- Legacy suspend and Flow routes ----

    [Fact]
    public async Task ResidentsLater_SuspendReturn_ReadsKotlinBackedElements()
    {
        using var home = new FosterHome();
        IReadOnlyList<IPet> pets = await home.ResidentsLaterAsync();
        Assert.Equal(new[] { "Oreo", "Mylo" }, pets.Select(p => p.Name));
        DisposeAll(pets);
    }

    [Fact]
    public async Task Headcounts_FlowOfList_ReadsEveryEmission()
    {
        using var home = new FosterHome();
        var seen = new List<string>();
        await foreach (IReadOnlyList<IPet> pets in home.Headcounts())
        {
            seen.Add(string.Join("+", pets.Select(p => p.Name)));
            DisposeAll(pets);
        }
        Assert.Equal(new[] { "Oreo", "Oreo+Mylo" }, seen);
    }

    [Fact]
    public async Task SleepersLater_SuspendIsTheOnlyPosition_StillMaterialisesTheElement()
    {
        // `Sleeper` is reachable ONLY through this suspend return (memo Finding 7).
        using var roster = new NapRoster();
        IReadOnlyList<ISleeper> sleepers = await roster.SleepersLaterAsync();
        Assert.Equal(new[] { "Mylo", "Oreo" }, sleepers.Select(s => s.Name));
        foreach (ISleeper sleeper in sleepers) sleeper.Dispose();
    }

    [Fact]
    public async Task Chasers_FlowIsTheOnlyPosition_StillMaterialisesTheElement()
    {
        // `Chaser` is reachable ONLY as this Flow's element component (memo Finding 7).
        using var feed = new ZoomiesFeed();
        var names = new List<string>();
        await foreach (IReadOnlyList<IChaser> chasers in feed.Chasers())
        {
            names.AddRange(chasers.Select(c => c.Name));
            foreach (IChaser chaser in chasers) chaser.Dispose();
        }
        Assert.Equal(new[] { "Oreo", "Mylo" }, names);
    }

    [Fact]
    public void Scents_MapKeyIsTheOnlyPosition_StillMaterialisesTheKey()
    {
        // `Scent` is reachable ONLY as a Map KEY: a walk that yields one name, or only values,
        // leaves it without a factory key (memo Finding 6).
        using var map = new ScentMap();
        IReadOnlyDictionary<IScent, string> scents = map.Scents();
        Dictionary<string, string> byNote = scents.ToDictionary(kv => kv.Key.Note, kv => kv.Value);
        Assert.Equal("Oreo", byNote["tuna"]);
        Assert.Equal("Mylo", byNote["catnip"]);
        foreach (IScent scent in scents.Keys) scent.Dispose();
    }

    // ---- Transitive reachability: an interface found only through another interface's members ----

    [Fact]
    public void Brushes_InterfaceMemberIsTheOnlyPosition_StillMaterialisesTheElement()
    {
        // `Brush` appears ONLY in `Stylist`'s own members; `Stylist` is reachable through
        // `GroomingParlour.stylist()`. Without the fixed-point walk this throws NotSupportedException.
        using var parlour = new GroomingParlour();
        using IStylist stylist = parlour.Stylist();
        IReadOnlyList<IBrush> brushes = stylist.Brushes();
        Assert.Equal(new[] { "slicker", "soft" }, brushes.Select(b => b.Name));
        foreach (IBrush brush in brushes) brush.Dispose();
    }

    [Fact]
    public async Task BrushesLater_InterfaceSuspendMemberIsTheOnlyPosition_StillMaterialisesTheElement()
    {
        using var parlour = new GroomingParlour();
        await using IStylist stylist = parlour.Stylist();
        IReadOnlyList<IBrush> brushes = await stylist.BrushesLaterAsync();
        Assert.Equal(new[] { "soft", "slicker" }, brushes.Select(b => b.Name));
        foreach (IBrush brush in brushes) brush.Dispose();
    }
}

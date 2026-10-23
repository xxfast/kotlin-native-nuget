using TestLibrary;
using TestLibrary.Hoard;

namespace IntegrationTests;

/// <summary>
/// ADR-237: <c>kotlin.Any</c> crosses as <c>object?</c>. A Kotlin value reaches C# as its runtime C#
/// value (a boxed primitive, a <c>string</c>, an exported class wrapper, the original object of a
/// C#-implemented interface, a value class struct, a collection), and a C# object reaches Kotlin
/// as the matching Kotlin value. <c>Hoarding.Kind</c> names the Kotlin type that arrived, so a value
/// that crossed as the wrong type shows up.
/// <para>
/// Oreo hoards toy mice under the couch. Mylo steals them, one runtime type at a time.
/// </para>
/// </summary>
public class AnyWireTests
{
    /// <summary>A C# catnip toy, so an <c>object</c> can carry a C#-implemented interface.</summary>
    internal sealed class Catnip(string scent) : ICatnip
    {
        public string Sniff() => $"{scent} catnip";
        public void Dispose() { }
    }

    /// <summary>A C# cache whose <c>Any</c> slots record and echo what Kotlin hands them.</summary>
    internal sealed class CouchCushion : ICache
    {
        public readonly List<object?> Seen = [];
        public object Favourite_ = "Oreo";

        public object? Hide(object? value)
        {
            Seen.Add(value);
            return value;
        }

        public object Favourite() => Favourite_;

        public void Dispose() { }
    }

    /// <summary>A C# sniffer over a name, with the member-level <c>R</c> erased to <c>object</c>.</summary>
    internal sealed class Whiskers(string item) : ISniffer<string>
    {
        public string Item { get; } = item;

        public object? Sniff(Func<string, object?> f) => f(Item);

        public void Dispose() { }
    }

    /// <summary>A C# pouncer that hands its own name to Kotlin's lambda.</summary>
    internal sealed class Ambush(string name) : IPouncer
    {
        public object? Pounce(Func<string, object?> f) => f(name);

        public void Dispose() { }
    }

    // -----------------------------------------------------------------------------------------
    // C# to Kotlin: each runtime kind arrives as the matching Kotlin type.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Kind_Int_ArrivesAsKotlinInt() => Assert.Equal("Int 5", Hoarding.Kind(5));

    [Fact]
    public void Kind_Long_ArrivesAsKotlinLong() => Assert.Equal("Long 6000000000", Hoarding.Kind(6_000_000_000L));

    [Fact]
    public void Kind_Double_ArrivesAsKotlinDouble() => Assert.Equal("Double 2.5", Hoarding.Kind(2.5));

    [Fact]
    public void Kind_Bool_ArrivesAsKotlinBoolean() => Assert.Equal("Boolean true", Hoarding.Kind(true));

    [Fact]
    public void Kind_String_ArrivesAsKotlinString() => Assert.Equal("String Oreo", Hoarding.Kind("Oreo"));

    [Fact]
    public void Kind_Null_ArrivesAsKotlinNull() => Assert.Equal("null", Hoarding.Kind(null));

    [Fact]
    public void Kind_ExportedClass_ArrivesAsTheKotlinObject()
    {
        using var mouse = new Mouse("Mylo");

        Assert.Equal("Mouse Mylo", Hoarding.Kind(mouse));
    }

    [Fact]
    public void Kind_CSharpImplementedInterface_ArrivesAsItsBridge() =>
        Assert.Equal("Catnip Oreo catnip", Hoarding.Kind(new Catnip("Oreo")));

    [Fact]
    public void Kind_Enum_ArrivesAsTheKotlinEntry() => Assert.Equal("Fur TUXEDO", Hoarding.Kind(Fur.Tuxedo));

    [Fact]
    public void Kind_ValueClass_ArrivesAsTheKotlinValueClass() => Assert.Equal("Paw 4", Hoarding.Kind(new Paw(4)));

    [Fact]
    public void Kind_List_ArrivesAsAKotlinListOfEachKind() =>
        Assert.Equal("List [Int 1, String Mylo, null]", Hoarding.Kind(new List<object?> { 1, "Mylo", null }));

    [Fact]
    public void Kind_Map_ArrivesAsAKotlinMap() =>
        Assert.Equal("Map {oreo=Int 5}", Hoarding.Kind(new Dictionary<string, object?> { ["oreo"] = 5 }));

    // -----------------------------------------------------------------------------------------
    // Kotlin to C#: each Kotlin-made value arrives as its C# runtime type.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Fetch_Int_IsABoxedInt() => Assert.Equal(5, Assert.IsType<int>(Hoarding.Fetch("int")));

    [Fact]
    public void Fetch_Long_IsABoxedLong() => Assert.Equal(6_000_000_000L, Assert.IsType<long>(Hoarding.Fetch("long")));

    [Fact]
    public void Fetch_Double_IsABoxedDouble() => Assert.Equal(2.5, Assert.IsType<double>(Hoarding.Fetch("double")));

    [Fact]
    public void Fetch_Bool_IsABoxedBool() => Assert.True(Assert.IsType<bool>(Hoarding.Fetch("bool")));

    [Fact]
    public void Fetch_String_IsAString() => Assert.Equal("Oreo", Assert.IsType<string>(Hoarding.Fetch("string")));

    [Fact]
    public void Fetch_Null_IsNull() => Assert.Null(Hoarding.Fetch("nothing"));

    [Fact]
    public void Fetch_ExportedClass_IsItsWrapper()
    {
        using Mouse mouse = Assert.IsType<Mouse>(Hoarding.Fetch("mouse"));

        Assert.Equal("Mylo squeaks", mouse.Squeak());
    }

    [Fact]
    public void Fetch_Enum_IsTheCSharpEnum() => Assert.Equal(Fur.Caramel, Assert.IsType<Fur>(Hoarding.Fetch("fur")));

    [Fact]
    public void Fetch_ValueClass_IsTheStruct() => Assert.Equal(4, Assert.IsType<Paw>(Hoarding.Fetch("paw")).Size);

    [Fact]
    public void Fetch_List_IsAReadOnlyListOfEachKind()
    {
        var list = Assert.IsAssignableFrom<IReadOnlyList<object?>>(Hoarding.Fetch("list"));

        Assert.Equal(new object?[] { 1, "Mylo", null }, list);
    }

    // -----------------------------------------------------------------------------------------
    // Round trips: what C# hands Kotlin as `Any` comes back as the same C# value.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Stash_EachPrimitiveKind_RoundTripsByType()
    {
        Assert.Equal(5, Assert.IsType<int>(Hoarding.Stash(5)));
        Assert.Equal(6_000_000_000L, Assert.IsType<long>(Hoarding.Stash(6_000_000_000L)));
        Assert.Equal(2.5, Assert.IsType<double>(Hoarding.Stash(2.5)));
        Assert.False(Assert.IsType<bool>(Hoarding.Stash(false)));
        Assert.Equal("Mylo", Assert.IsType<string>(Hoarding.Stash("Mylo")));
        Assert.Equal(Fur.Caramel, Assert.IsType<Fur>(Hoarding.Stash(Fur.Caramel)));
        Assert.Equal(3, Assert.IsType<Paw>(Hoarding.Stash(new Paw(3))).Size);
    }

    [Fact]
    public void StashOrNull_Null_RoundTripsAsNull() => Assert.Null(Hoarding.StashOrNull(null));

    [Fact]
    public void Stash_ExportedClass_ComesBackAsAWrapperOfTheSameKotlinObject()
    {
        using var mouse = new Mouse("Oreo");

        using Mouse back = Assert.IsType<Mouse>(Hoarding.Stash(mouse));
        Assert.Equal("Oreo squeaks", back.Squeak());
    }

    [Fact]
    public void Stash_CSharpImplementedInterface_ComesBackAsTheSameObject()
    {
        var catnip = new Catnip("Mylo");

        Assert.Same(catnip, Hoarding.StashOrNull(catnip));
    }

    [Fact]
    public void Stash_List_ComesBackWithEachElementByType()
    {
        var back = Assert.IsAssignableFrom<IReadOnlyList<object?>>(Hoarding.Stash(new List<object?> { 7, "Oreo", null }));

        Assert.Equal(new object?[] { 7, "Oreo", null }, back);
        Assert.IsType<int>(back[0]);
    }

    [Fact]
    public void Pile_ListOfAny_InAndOut()
    {
        IReadOnlyList<object?> pile = Hoarding.Pile([1, "Oreo", null]);

        Assert.Equal(new object?[] { 1, "Oreo", null, "List [Int 1, String Oreo, null]" }, pile);
    }

    [Fact]
    public void Ledger_MapOfAny_InAndOut()
    {
        IReadOnlyDictionary<string, object?> ledger = Hoarding.Ledger(new Dictionary<string, object?> { ["oreo"] = 5 });

        Assert.Equal(5, Assert.IsType<int>(ledger["oreo"]));
        Assert.Equal("Map {oreo=Int 5}", ledger["seen"]);
    }

    // -----------------------------------------------------------------------------------------
    // Every owner: class constructor, `var`, member, and a Kotlin-backed interface.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Hoard_ConstructorVarAndMember_CarryAny()
    {
        using var hoard = new Hoard(5);
        Assert.Equal(5, Assert.IsType<int>(hoard.Last));

        hoard.Last = "Oreo";
        Assert.Equal("Oreo", hoard.Last);

        Assert.Equal(Fur.Tuxedo, hoard.Keep(Fur.Tuxedo));
        Assert.Equal(new object?[] { Fur.Tuxedo }, hoard.All());
    }

    [Fact]
    public void Loot_KotlinBackedInterface_CarriesAny()
    {
        using ILoot thief = Hoarding.Thief();

        var grabbed = Assert.IsAssignableFrom<IReadOnlyList<object?>>(thief.Grab(2.5));
        Assert.Equal(new object?[] { 2.5, "Double 2.5" }, grabbed);
    }

    [Fact]
    public void Hiding_CSharpImplementedSlot_EachKindRoundTrips()
    {
        var cushion = new CouchCushion();

        Assert.Equal("Int 5", Hoarding.Hiding(cushion, 5));
        Assert.Equal("String Mylo", Hoarding.Hiding(cushion, "Mylo"));
        Assert.Equal("null", Hoarding.Hiding(cushion, null));
        Assert.Equal("Fur CARAMEL", Hoarding.Hiding(cushion, Fur.Caramel));
        Assert.Equal(new object?[] { 5, "Mylo", null, Fur.Caramel }, cushion.Seen);
    }

    [Fact]
    public void Favourite_CSharpImplementedSlotResult_CSharpObjectComesBackAsItself()
    {
        var catnip = new Catnip("Oreo");
        var cushion = new CouchCushion { Favourite_ = catnip };

        Assert.Same(catnip, Hoarding.Favourite(cushion));
    }

    // -----------------------------------------------------------------------------------------
    // A member-level `<R>` on an interface, erased to `object`.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Sniffing_CSharpImplementedGenericInterface_MemberLevelRErasedToObject() =>
        Assert.Equal("5 MYLO", Hoarding.Sniffing(new Whiskers("Mylo")));

    [Fact]
    public void Snout_KotlinBackedGenericInterface_MemberLevelRErasedToObject()
    {
        using ISniffer<string> snout = Hoarding.Snout("Oreo");

        Assert.Equal(4, Assert.IsType<int>(snout.Sniff(name => name.Length)));
        Assert.Equal("OREO", snout.Sniff(name => name.ToUpperInvariant()));
    }

    [Fact]
    public void Pouncing_NonGenericInterface_MemberLevelRCarriesAKotlinHandle() =>
        Assert.Equal("Oreo squeaks", Hoarding.Pouncing(new Ambush("Oreo")));

    // -----------------------------------------------------------------------------------------
    // Star projections: `Flow<*>` and `Satchel<*>` read their elements through `Any`.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public async Task Spoils_StarProjectedClassMemberFlow_EachElementByType()
    {
        using var hoard = new Hoard(null);
        using KotlinFlow<object?> spoils = hoard.Spoils();

        var seen = new List<object?>();
        await foreach (object? item in spoils) seen.Add(item);

        Assert.Equal(4, seen.Count);
        Assert.Equal(1, Assert.IsType<int>(seen[0]));
        Assert.Equal("Oreo", seen[1]);
        Assert.Null(seen[2]);
        using Mouse mouse = Assert.IsType<Mouse>(seen[3]);
        Assert.Equal("Mylo squeaks", mouse.Squeak());
    }

    [Fact]
    public async Task Haul_ClassFlowOfAny_EachElementByType()
    {
        using var hoard = new Hoard(Fur.Caramel);
        using KotlinFlow<object?> haul = hoard.Haul();

        var seen = new List<object?>();
        await foreach (object? item in haul) seen.Add(item);

        Assert.Equal(new object?[] { Fur.Caramel, "tuna" }, seen);
    }

    [Fact]
    public async Task Everything_KotlinBackedInterfaceStarFlow_EachElementByType()
    {
        using ILoot thief = Hoarding.Thief();
        using KotlinFlow<object?> everything = thief.Everything();

        var seen = new List<object?>();
        await foreach (object? item in everything) seen.Add(item);

        Assert.Equal(new object?[] { Fur.Tuxedo, 2.5 }, seen);
    }

    [Fact]
    public async Task Sift_StarProjectedFlowParameter_EachElementArrivesByKotlinType() =>
        Assert.Equal(
            "Int 1, String Oreo, null, Fur CARAMEL",
            await Hoarding.SiftAsync(Each<object?>(1, "Oreo", null, Fur.Caramel)));

    [Fact]
    public void Rummage_StarProjectedGenericClassResult_ItemReadsAsItsRuntimeType()
    {
        using Satchel<object?> satchel = Hoarding.Rummage();

        using Mouse mouse = Assert.IsType<Mouse>(satchel.Item);
        Assert.Equal("Oreo squeaks", mouse.Squeak());
    }

    [Fact]
    public void Bag_StarProjectedGenericClassProperty_ItemReadsAsItsRuntimeType()
    {
        using var hoard = new Hoard(6_000_000_000L);
        using Satchel<object?> bag = hoard.Bag;

        Assert.Equal(6_000_000_000L, Assert.IsType<long>(bag.Item));
    }

    // -----------------------------------------------------------------------------------------
    // A generic value class through `Any`: its own struct, read back at `object?`.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Kind_GenericValueClass_ArrivesAsTheKotlinValueClass()
    {
        Assert.Equal("Bow String red", Hoarding.Kind(new Bow<string>("red")));
        Assert.Equal("Bow Int 3", Hoarding.Kind(new Bow<int>(3)));
    }

    [Fact]
    public void Tie_GenericValueClass_IsTheStructAtObject() =>
        Assert.Equal("red", Assert.IsType<Bow<object?>>(Hoarding.Tie("red")).Ribbon);

    [Fact]
    public void Stash_GenericValueClass_RoundTripsWithItsUnderlyingByType()
    {
        Bow<object?> back = Assert.IsType<Bow<object?>>(Hoarding.Stash(new Bow<long>(6_000_000_000L)));

        Assert.Equal(6_000_000_000L, Assert.IsType<long>(back.Ribbon));
    }

    // ADR-228: a phantom shares the same box pair, so it reads back over `object?` too.
    [Fact]
    public void Stash_PhantomGenericValueClass_ReadsBackOverObject()
    {
        var back = Assert.IsType<TestLibrary.Stickers.Decal<object?>>(
            Hoarding.Stash(new TestLibrary.Stickers.Decal<string>(7L)));

        Assert.Equal(7L, back.Serial);
    }

    [Fact]
    public void GenericValueClass_NullUnderlyingAndNullValue_StayDistinct()
    {
        Assert.Equal("Bow null", Hoarding.Kind(new Bow<object?>(null)));
        Assert.Null(Assert.IsType<Bow<object?>>(Hoarding.StashOrNull(new Bow<string?>(null))).Ribbon);
        Assert.Equal("not a bow: null", Hoarding.Untie(null));
    }

    [Fact]
    public void Untie_WrongType_ArrivesAsWhatItIs()
    {
        Assert.Equal("not a bow: String red", Hoarding.Untie("red"));
        Assert.Equal("Bow String red", Hoarding.Untie(new Bow<string>("red")));
        Assert.IsNotType<Bow<string>>(Hoarding.Tie("red"));
    }

    [Fact]
    public void GenericValueClass_Init_RunsInKotlinWhenItCrosses()
    {
        ArgumentException ex = Assert.ThrowsAny<ArgumentException>(() => Hoarding.Kind(new Bow<string>("torn")));

        Assert.Contains("Mylo tore this bow", ex.Message);
    }

    [Fact]
    public void BoxOfObject_HoldingAGenericValueClass_ReadsItBack()
    {
        using var box = new TestLibrary.Cat.Box<object>(new Bow<string>("red"));

        Assert.Equal("red", Assert.IsType<Bow<object?>>(box.Value).Ribbon);
    }

    [Fact]
    public void TieMouse_GenericValueClassAroundAHandle_UnwrapsToTheWrapper()
    {
        Bow<object?> bow = Assert.IsType<Bow<object?>>(Hoarding.TieMouse("Mylo"));
        using Mouse mouse = Assert.IsType<Mouse>(bow.Ribbon);

        Assert.Equal("Mylo squeaks", mouse.Squeak());
    }

    private static async IAsyncEnumerable<T> Each<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }
}

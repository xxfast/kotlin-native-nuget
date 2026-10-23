using System.Runtime.CompilerServices;
using TestLibrary;
using TestLibrary.Catsitter;
using Feedingtime = TestLibrary.Feedingtime;

namespace IntegrationTests;

/// <summary>
/// ADR-233: a C# class implementing a Kotlin interface crosses to Kotlin whatever types its members
/// take and return, as long as an ordinary member plan carries them: class handles (generic or
/// not), collections, value classes, nullable primitives and enums, <c>char</c>, time, Guid and byte
/// array values, lambdas in and out, a generic interface result, any arity, and every flow shape.
/// Each implementation records what Kotlin handed it, so a slot that delivered a constant shows up.
/// <para>
/// Oreo's sitter knows exactly where the tuna is. Mylo's sitter mostly counts him, twice.
/// </para>
/// </summary>
public class InterfaceSlotVocabularyTests
{
    internal sealed class CatSitter : ISitter
    {
        public readonly List<string> Seen = [];
        public Exception? Fault;

        public Kitten Adopt(string name)
        {
            if (Fault != null) throw Fault;
            Seen.Add($"adopt {name}");
            return new Kitten(name);
        }

        public string Cuddle(Kitten kitten) => $"cuddled {kitten.Name}";

        public Kitten? Foster(Kitten? kitten) => kitten == null ? null : new Kitten($"{kitten.Name} jr");

        public Crate<string> Pack(string item) => new($"crate of {item}");

        public int Unpack(Crate<int> crate) => crate.Item * 2;

        public IReadOnlyList<string> Names()
        {
            if (Fault != null) throw Fault;
            return ["Oreo", "Mylo"];
        }

        public int Weigh(IReadOnlyDictionary<string, int> weights) => weights.Values.Sum();

        public IReadOnlySet<string> Litter(IReadOnlyList<Kitten> kittens) =>
            kittens.Select(kitten => kitten.Name.ToUpperInvariant()).ToHashSet();

        public Whisker Trim(Whisker whisker) => new(whisker.Length * 2);

        public int? Treats(string cat) => cat == "Oreo" ? 3 : null;

        public bool? Indoor(string cat) => cat == "Oreo" ? true : null;

        public global::TestLibrary.Catsitter.Coat? Coat(string cat) =>
            cat == "Oreo" ? global::TestLibrary.Catsitter.Coat.Tuxedo : null;

        public char Initial(string cat) => cat[0];

        public DateTimeOffset LastFed(DateTimeOffset at)
        {
            Seen.Add($"fed {at.ToUnixTimeMilliseconds()}");
            return at.AddHours(1);
        }

        public TimeSpan Nap(TimeSpan length) => length * 2;

        public Guid Chip(Guid id)
        {
            Seen.Add($"chip {id}");
            return Guid.Parse("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        }

        public byte[] Photo(byte[] snapshot) => snapshot.Reverse().ToArray();

        public int OnPurr(Func<string, int> callback) => callback("Mylo") + 1;

        // Not on ISitter yet: the interface member returning a lambda is skipped there today.
        public Func<int, string> Purrer() => times => $"purr x{times}";

        public async Task<int> WhenHungryAsync(
            Func<string, CancellationToken, Task<int>> callback, CancellationToken cancellationToken = default) =>
            await callback("Oreo", cancellationToken);

        public Func<ITrinket<string>> Favourite_ = () => Sitters.Bauble("tuna");

        public ITrinket<string> Favourite() => Favourite_();

        public string Groom(string cat, int strokes, double minutes, bool gentle) =>
            $"groomed {cat} {strokes} strokes for {minutes} min{(gentle ? " gently" : "")}";

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    /// <summary>A C#-implemented generic interface a C# slot hands back.</summary>
    internal sealed class Pebble(string item) : ITrinket<string>
    {
        public string Item { get; } = item;
        public void Dispose() { }
    }

    [Fact]
    public void Adoption_ClassHandleReturnedFromCSharp_KotlinCallsIt()
    {
        using var sitter = new CatSitter();

        Assert.Equal("Oreo purrs", Sitters.Adoption(sitter, "Oreo"));
        Assert.Contains("adopt Oreo", sitter.Seen);
    }

    [Fact]
    public void Cuddling_KotlinHandlePassedToCSharp_CSharpReadsIt() =>
        Assert.Equal("cuddled Mylo", Sitters.Cuddling(new CatSitter(), "Mylo"));

    [Fact]
    public void Fostering_NullableHandleBothWays() =>
        Assert.Equal("Oreo jr/null", Sitters.Fostering(new CatSitter(), "Oreo"));

    [Fact]
    public void Crating_GenericClassHandleBothWays() =>
        Assert.Equal("crate of tuna/6", Sitters.Crating(new CatSitter(), "tuna", 3));

    [Fact]
    public void Census_CollectionsOfStringsNumbersAndHandles() =>
        Assert.Equal("names=Oreo, Mylo; weight=11; litter=MYLO, OREO", Sitters.Census(new CatSitter()));

    [Fact]
    public void Details_ValueClassNullablesEnumAndChar() =>
        Assert.Equal(
            "Oreo trim=8 treats=3 indoor=true coat=TUXEDO initial=O; " +
            "Mylo trim=8 treats=null indoor=null coat=null initial=M",
            Sitters.Details(new CatSitter()));

    [Fact]
    public void Records_InstantDurationUuidAndByteArray()
    {
        using var sitter = new CatSitter();

        Assert.Equal(
            "fed=1792659600000 nap=40 chip=f47ac10b-58cc-4372-a567-0e02b2c3d479 photo=3,2,1",
            Sitters.Records(sitter));
        Assert.Contains("fed 1792656000000", sitter.Seen);
        Assert.Contains("chip 6ba7b810-9dad-11d1-80b4-00c04fd430c8", sitter.Seen);
    }

    [Fact]
    public void Purring_LambdaIntoCSharpAndLambdaOutOfCSharp() =>
        Assert.Equal("41/purr x3", Sitters.Purring(new CatSitter()));

    [Fact]
    public async Task HungerPangs_SuspendLambdaIntoASuspendSlot() =>
        Assert.Equal(104, await Sitters.HungerPangsAsync(new CatSitter()));

    [Fact]
    public void Treasure_KotlinBackedGenericInterfaceReturnedFromCSharp() =>
        Assert.Equal("favourite tuna", Sitters.Treasure(new CatSitter()));

    [Fact]
    public void Treasure_CSharpGenericInterfaceReturnedFromCSharp() =>
        Assert.Equal(
            "favourite sunbeam",
            Sitters.Treasure(new CatSitter { Favourite_ = () => new Pebble("sunbeam") }));

    [Fact]
    public void Grooming_FourParametersInOrder() =>
        Assert.Equal("groomed Mylo 4 strokes for 2.5 min gently", Sitters.Grooming(new CatSitter()));

    [Fact]
    public void AdoptionOrRecover_CSharpFaultFromAHandleSlot_CaughtInKotlin() =>
        Assert.Contains(
            "Oreo hid under the bed",
            Sitters.AdoptionOrRecover(
                new CatSitter { Fault = new InvalidOperationException("Oreo hid under the bed") }, "Oreo"));

    [Fact]
    public void CensusOrRecover_CSharpFaultFromACollectionSlot_CaughtInKotlin() =>
        Assert.Contains(
            "Mylo ran off",
            Sitters.CensusOrRecover(new CatSitter { Fault = new InvalidOperationException("Mylo ran off") }));

    // -----------------------------------------------------------------------------------------
    // Flow shapes.
    // -----------------------------------------------------------------------------------------

    internal sealed class Lookout : ITracker
    {
        public bool Empty;
        public KotlinFlow<Kitten>? Kittens_;

        public KotlinFlow<Kitten> Kittens() => Kittens_ ?? KotlinFlow<Kitten>.From(Each(new Kitten("Oreo"), new Kitten("Mylo")));

        public KotlinFlow<global::TestLibrary.Catsitter.Coat> Coats() =>
            KotlinFlow<global::TestLibrary.Catsitter.Coat>.From(
                Each(global::TestLibrary.Catsitter.Coat.Tuxedo, global::TestLibrary.Catsitter.Coat.Caramel));

        public KotlinFlow<string?> Sightings() => KotlinFlow<string?>.From(Each<string?>("Oreo", null));

        public KotlinFlow<Whisker> Whiskers() => KotlinFlow<Whisker>.From(Each(new Whisker(3), new Whisker(4)));

        public KotlinFlow<string> Feed { get; set; } = KotlinFlow<string>.From(Each("kibble"));

        public KotlinFlow<string>? Maybe() => Empty ? null : KotlinFlow<string>.From(Each("tuna"));

        public KotlinMutableStateFlow<int> MoodFlow { get; } = KotlinMutableStateFlow<int>.Create(7);

        public KotlinStateFlow<int>? Mood => Empty ? null : MoodFlow;

        public async Task<KotlinStateFlow<int>> LevelAsync(CancellationToken cancellationToken = default)
        {
            await Task.Delay(1, cancellationToken);
            return MoodFlow;
        }

        public void Dispose() => MoodFlow.Dispose();
        public ValueTask DisposeAsync()
        {
            Dispose();
            return default;
        }
    }

    internal static async IAsyncEnumerable<T> Each<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    internal static async IAsyncEnumerable<Kitten> KittensThenBolt(
        [EnumeratorCancellation] CancellationToken token = default)
    {
        await Task.Delay(1, token);
        yield return new Kitten("Oreo");
        throw new InvalidOperationException("Mylo bolted");
    }

    [Fact]
    public async Task Sightings_FlowsOfHandleEnumNullableStringAndValueClass()
    {
        using var lookout = new Lookout();

        Assert.Equal(
            "kittens=Oreo, Mylo; coats=TUXEDO, CARAMEL; sightings=Oreo, null; whiskers=3, 4",
            await Sitters.SightingsAsync(lookout));
    }

    [Fact]
    public async Task Sightings_HandleFlowFaultsMidStream_FaultReachesCSharp()
    {
        using var lookout = new Lookout { Kittens_ = KotlinFlow<Kitten>.From(KittensThenBolt()) };

        Exception fault = await Assert.ThrowsAnyAsync<Exception>(() => Sitters.SightingsAsync(lookout));
        Assert.Contains("Mylo bolted", fault.ToString());
    }

    [Fact]
    public async Task Refeed_KotlinReplacesTheCSharpFlowVar()
    {
        using var lookout = new Lookout();

        Assert.Equal("kibble -> salmon", await Sitters.RefeedAsync(lookout, "salmon"));
        List<string> fed = [];
        await foreach (string meal in lookout.Feed) fed.Add(meal);
        Assert.Equal(["salmon"], fed);
    }

    [Fact]
    public async Task Maybes_NullableFlowsAValueAndANull()
    {
        using var full = new Lookout();
        using var empty = new Lookout { Empty = true };

        Assert.Equal("maybe=tuna mood=7", await Sitters.MaybesAsync(full));
        Assert.Equal("maybe=null mood=null", await Sitters.MaybesAsync(empty));
    }

    [Fact]
    public async Task Levelling_SuspendMemberReturningAHotFlow()
    {
        using var lookout = new Lookout();
        lookout.MoodFlow.Value = 9;

        Assert.Equal(9, await Sitters.LevellingAsync(lookout));
    }

    // -----------------------------------------------------------------------------------------
    // Inherited super-interface `T`, a backticked member, and a Kotlin-backed hot flow handed back.
    // -----------------------------------------------------------------------------------------

    internal sealed class WickerHamper : IHamper
    {
        public string Item => "kibble";
        public string Put(string item) => $"{item}!";
        public string Label() => "Mylo's hamper";
        public void Dispose() { }
    }

    [Fact]
    public void UnpackHamper_SlotsTypedByAnInheritedSuperInterfaceT() =>
        Assert.Equal("Mylo's hamper: kibble + tuna!", Sitters.UnpackHamper(new WickerHamper()));

    internal sealed class CatFlap : IDoorman
    {
        public string In(string @object) => $"in:{@object}";
        public void Dispose() { }
    }

    [Fact]
    public void Knock_BacktickedMemberWithTheClassRouteName() =>
        Assert.Equal("in:Oreo", Sitters.Knock(new CatFlap()));

    internal sealed class Radiator(Feedingtime.IThermostat wall) : Feedingtime.IThermostat
    {
        public KotlinStateFlow<int> Level => wall.Level;
        public KotlinMutableStateFlow<int> Dial => wall.Dial;
        public KotlinSharedFlow<string> News() => wall.News();
        public KotlinMutableSharedFlow<string> Bell() => wall.Bell();
        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    /// <summary>A Kotlin-backed member's hot flow handed back through a C# implementation.</summary>
    [Fact]
    public void Reading_KotlinBackedMemberHotFlowHandedBack()
    {
        using Feedingtime.IThermostat wall = Feedingtime.Feeders.WallThermostat();
        using var radiator = new Radiator(wall);

        Assert.Equal(21, Feedingtime.Feeders.Reading(radiator));
        Assert.Equal(1, Feedingtime.Feeders.TurnUp(radiator));
        Assert.Equal(1, wall.Dial.Value);
    }

    // -----------------------------------------------------------------------------------------
    // Generator collisions and generic owners.
    // -----------------------------------------------------------------------------------------

    [Fact]
    public void Echo_ByteArrayParameterNamedBytesWithAByteArrayResult()
    {
        using var kitten = new Kitten("Oreo");

        Assert.Equal(new byte[] { 3, 2, 1 }, kitten.Echo([1, 2, 3]));
    }

    [Fact]
    public async Task Head_SuspendFlowOfTheGenericOwnersT_ReadThroughTheErasedOwner()
    {
        using ILitter<string> litter = Sitters.Roll();

        Assert.Equal("Oreo", await Sitters.HeadAsync(litter));
        List<string> names = [];
        await foreach (string name in await litter.KittensAsync()) names.Add(name);
        Assert.Equal(["Oreo", "Mylo"], names);
    }
}

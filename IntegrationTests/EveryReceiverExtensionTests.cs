using System.Reflection;
using Test.Menagerie;
using TestLibrary;
using TestLibrary.Bindle;

namespace IntegrationTests;

/// <summary>
/// ADR-238: an extension binds on every receiver, sync and <c>suspend</c>, generic or not. Each
/// receiver kind in <c>test-library/.../bindle/Bindles.kt</c> is crossed as a sync extension
/// function, an extension property and a <c>suspend</c> extension:
/// <list type="bullet">
/// <item>a generic class at the extension's own <c>T</c> binds as a generic C# extension,
/// <c>Unpack&lt;T&gt;(this Bindle&lt;T&gt; receiver)</c> and <c>extension&lt;T&gt;(Bindle&lt;T&gt; receiver)</c>;
/// a star projection gets a synthesized method type parameter;</item>
/// <item>a bare <c>T</c> homes on <c>{FirstBound}Extensions</c> (<c>DozerExtensions</c>,
/// <c>where T : IDozer</c>), or on <c>ObjectExtensions</c> when unbounded;</item>
/// <item>a collection, interface, bound C# interface, value class, enum, <c>ByteArray</c> and the
/// stdlib types, each nullable too, bind at all three positions;</item>
/// <item>a <c>suspend</c> <c>Result&lt;T&gt;</c> result is <c>Task&lt;T&gt;</c> and a failure faults it;</item>
/// <item>a member extension is an instance method with the receiver first, <c>hand.Brush(loafer, 3)</c>.</item>
/// </list>
/// The bindle namespace is imported only here: its <c>ObjectExtensions</c> puts <c>Tag</c> and
/// <c>Label</c> on every C# type.
/// Oreo (black, white middle) rides in the bindle; Mylo (brown and creamy) loafs beside it.
/// </summary>
public class EveryReceiverExtensionTests
{
    private static readonly Guid OreoChip = Guid.Parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a");
    private static readonly DateTimeOffset OreoBirthday = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000);

    // Mylo as a C#-side dozer, so the interface bridge crosses from C# at every position.
    private sealed class MyloDozer : IDozer
    {
        public string Name => "Mylo";
        public void Dispose() { }
    }

    // A C#-side bound interface (ADR-088): the Kotlin bodies dispatch back into `describe()`
    // and `legs`, so an echo cannot produce these strings.
    private sealed class Goat : IFeedable
    {
        public string Describe() => "Nibbles the goat";
        public int Legs => 4;
        public void Feed(string food) { }
        public string? Nickname { get; set; }
    }

    // ---- sync functions: generic receivers --------------------------------------------------

    [Fact]
    public void Bindle_Unpack_GenericReceiverInfersT()
    {
        using var kibble = new Bindle<string>("kibble");
        using var nine = new Bindle<int>(9);
        Assert.Equal("kibble", kibble.Unpack());
        Assert.Equal(9, nine.Unpack());
    }

    [Fact]
    public void Bindle_Unpack_HandleTypeArgumentComesBackAsTheSameCat()
    {
        using var oreo = new Loafer("Oreo");
        using var bindle = new Bindle<Loafer>(oreo);
        using Loafer back = bindle.Unpack();
        Assert.Equal("Oreo", back.Name);
    }

    [Fact]
    public void Bindle_Unpack_SpellsAGenericExtensionMethod()
    {
        MethodInfo unpack = typeof(BindleExtensions).GetMethod("Unpack")!;
        Assert.True(unpack.IsGenericMethodDefinition);
        Assert.True(unpack.IsDefined(typeof(System.Runtime.CompilerServices.ExtensionAttribute)));
        Type receiver = unpack.GetParameters()[0].ParameterType;
        Assert.Equal(typeof(Bindle<>), receiver.GetGenericTypeDefinition());
        Assert.Equal(unpack.GetGenericArguments()[0], receiver.GetGenericArguments()[0]);
        Assert.Equal(unpack.GetGenericArguments()[0], unpack.ReturnType);
    }

    [Fact]
    public void Bindle_Swap_OwnTypeParameterAtReceiverAndParameter()
    {
        using var bindle = new Bindle<string>("Oreo");
        Assert.Equal("Oreo", bindle.Swap("Mylo"));
        Assert.Equal("Mylo", bindle.Top);
    }

    [Fact]
    public void Bindle_Doubled_ClosedGenericReceiver()
    {
        using var lives = new Bindle<int>(21);
        Assert.Equal(42, lives.Doubled());
    }

    [Fact]
    public void Bindle_IsPacked_StarProjectionGetsASynthesizedTypeParameter()
    {
        using var empty = new Bindle<string?>(null);
        using var full = new Bindle<int>(1);
        Assert.False(empty.IsPacked());
        Assert.True(full.IsPacked());

        MethodInfo isPacked = typeof(BindleExtensions).GetMethod("IsPacked")!;
        Assert.True(isPacked.IsGenericMethodDefinition);
        Assert.Equal(typeof(Bindle<>), isPacked.GetParameters()[0].ParameterType.GetGenericTypeDefinition());
    }

    [Fact]
    public void List_Headcount_StarProjectedCollectionAcceptsAnyElement()
    {
        Assert.Equal(2, new List<string> { "Oreo", "Mylo" }.Headcount());
        Assert.Equal(3, new[] { 1, 2, 3 }.Headcount());

        MethodInfo headcount = typeof(ListExtensions).GetMethod("Headcount")!;
        Assert.True(headcount.IsGenericMethodDefinition);
        Assert.Equal(typeof(IReadOnlyList<>), headcount.GetParameters()[0].ParameterType.GetGenericTypeDefinition());
    }

    // ---- sync functions: bare `T` receivers ------------------------------------------------

    [Fact]
    public void Dozer_Introduce_BareBoundedTypeParameterHomesOnTheBound()
    {
        using var oreo = new Loafer("Oreo");
        Assert.Equal("I am Oreo", oreo.Introduce());
        Assert.Equal("I am Mylo", new MyloDozer().Introduce());

        MethodInfo introduce = typeof(DozerExtensions).GetMethod("Introduce")!;
        Assert.Equal("TestLibrary.Bindle", typeof(DozerExtensions).Namespace);
        Type t = introduce.GetGenericArguments().Single();
        Assert.Equal(t, introduce.GetParameters()[0].ParameterType);
        Assert.Contains(typeof(IDozer), t.GetGenericParameterConstraints());
    }

    [Fact]
    public void Dozer_IntroduceOrNobody_NullableBareTypeParameter()
    {
        using var mylo = new Loafer("Mylo");
        Loafer? none = null;
        Assert.Equal("Mylo", mylo.IntroduceOrNobody());
        Assert.Equal("nobody", none.IntroduceOrNobody());
    }

    [Fact]
    public void Dozer_Primp_IntersectionBoundHomesOnTheFirstBound()
    {
        using var mylo = new Loafer("Mylo");
        Assert.Equal("Mylo: Mylo preens", mylo.Primp());

        Type t = typeof(DozerExtensions).GetMethod("Primp")!.GetGenericArguments().Single();
        Assert.Equal(new[] { typeof(IDozer), typeof(IPreener) }, t.GetGenericParameterConstraints().OrderBy(c => c.Name));
    }

    [Fact]
    public void Object_Tag_UnboundedTypeParameterHomesOnObjectExtensions()
    {
        Assert.Equal("tag:42", 42.Tag());
        Assert.Equal("tag:Oreo", "Oreo".Tag());
        Assert.Equal("tag!:7", 7.NonNullTag());

        MethodInfo tag = typeof(ObjectExtensions).GetMethod("Tag")!;
        Assert.Equal("TestLibrary.Bindle", typeof(ObjectExtensions).Namespace);
        Assert.Empty(tag.GetGenericArguments().Single().GetGenericParameterConstraints());
    }

    // ---- sync functions: every other receiver -----------------------------------------------

    [Fact]
    public void SyncExtension_CollectionReceivers()
    {
        List<int>? none = null;
        Assert.Equal(6, new List<int> { 1, 2, 3 }.TallyUp());
        Assert.Equal(9, new[] { 4, 5 }.TallyOrNone());
        Assert.Equal(-1, none.TallyOrNone());
    }

    [Fact]
    public void SyncExtension_InterfaceReceivers()
    {
        using var oreo = new Loafer("Oreo");
        IDozer? none = null;
        Assert.Equal("Oreo snores", oreo.SnoreLine());
        Assert.Equal("Mylo snores", new MyloDozer().SnoreLine());
        Assert.Equal("silence", none.SnoreOrSilence());
    }

    [Trait("Direction", "Reverse")]
    [Fact]
    public void SyncExtension_BoundInterfaceReceivers()
    {
        IFeedable nibbles = new Goat();
        IFeedable? none = null;
        Assert.Equal("Nibbles the goat gets 4 bowls", nibbles.BowlLine());
        Assert.Equal(4, nibbles.BowlsOrNone());
        Assert.Equal(-1, none.BowlsOrNone());
    }

    [Fact]
    public void SyncExtension_ValueClassReceivers()
    {
        Paws? noPaws = null;
        BindleTag? noTag = null;
        Assert.Equal(4, new Paws(3).Tuck());
        Assert.Equal(-1, noPaws.TuckOrNone());
        Assert.Equal("[oreo-1]", new BindleTag("oreo-1").Stamp());
        Assert.Equal("blank", noTag.StampOrBlank());
    }

    [Fact]
    public void SyncExtension_EnumReceivers()
    {
        Drowse? none = null;
        Assert.Equal(2, Drowse.Deep.Depth());
        Assert.Equal("awake", none.DepthOrAwake());
        Assert.Equal("CATNAP", ((Drowse?)Drowse.Catnap).DepthOrAwake());
    }

    [Fact]
    public void SyncExtension_ByteArrayReceivers()
    {
        byte[]? none = null;
        Assert.Equal(3, new byte[] { 1, 2 }.Crumbs());
        Assert.Equal(-1, none.CrumbsOrNone());
    }

    [Fact]
    public void SyncExtension_StdlibReceivers()
    {
        DateTimeOffset? never = null;
        Assert.Equal("1b2c3d4e", OreoChip.ChipHead());
        Assert.Equal(0L, never.DayOrEpoch());
        Assert.Equal(1_700_000_000L / 86_400L, ((DateTimeOffset?)OreoBirthday).DayOrEpoch());
        Assert.Equal(90L, TimeSpan.FromMinutes(90).NapMinutes());
    }

    // ---- extension properties -------------------------------------------------------------

    [Fact]
    public void Bindle_Peek_GenericExtensionPropertyReadsTheOwnT()
    {
        using var kibble = new Bindle<string>("kibble");
        using var nine = new Bindle<int>(9);
        Assert.Equal("kibble", kibble.Peek);
        Assert.Equal(9, nine.Peek);
    }

    [Fact]
    public void Bindle_Lid_GenericExtensionPropertyRoundTrips()
    {
        using var bindle = new Bindle<int>(1);
        bindle.Lid = 9;
        Assert.Equal(9, bindle.Lid);
        Assert.Equal(9, bindle.Peek);
    }

    [Fact]
    public void Bindle_Lid_HandleTypeArgumentRoundTripsThroughTheSetter()
    {
        using var oreo = new Loafer("Oreo");
        using var mylo = new Loafer("Mylo");
        using var bindle = new Bindle<Loafer>(oreo);
        bindle.Lid = mylo;
        using Loafer back = bindle.Lid;
        Assert.Equal("Mylo", back.Name);
    }

    [Fact]
    public void Bindle_Packed_StarProjectedExtensionProperty()
    {
        using var empty = new Bindle<string?>(null);
        using var full = new Bindle<string>("Oreo");
        Assert.False(empty.Packed);
        Assert.True(full.Packed);
    }

    [Fact]
    public void BareTypeParameter_ExtensionProperties()
    {
        using var oreo = new Loafer("Oreo");
        Assert.Equal("badge:Oreo", oreo.Badge);
        Assert.Equal("badge:Mylo", new MyloDozer().Badge);
        Assert.Equal("label:42", 42.Label);
        Assert.Equal("label:Mylo", "Mylo".Label);
    }

    [Fact]
    public void ExtensionProperty_CollectionReceivers()
    {
        List<int>? none = null;
        Assert.Equal(6, new List<int> { 1, 2, 3 }.Tally);
        Assert.Equal(9, new[] { 4, 5 }.TallyOrZero);
        Assert.Equal(0, none.TallyOrZero);
        Assert.Equal(2, new List<string> { "Oreo", "Mylo" }.RollCall);
    }

    [Trait("Direction", "Reverse")]
    [Fact]
    public void ExtensionProperty_InterfaceAndBoundInterfaceReceivers()
    {
        using var oreo = new Loafer("Oreo");
        IFeedable nibbles = new Goat();
        IFeedable? none = null;
        Assert.Equal("Oreo zzz", oreo.Snore);
        Assert.Equal("Mylo zzz", new MyloDozer().Snore);
        Assert.Equal(4, nibbles.Bowls);
        Assert.Equal(4, nibbles.BowlsOrZero);
        Assert.Equal(0, none.BowlsOrZero);
    }

    [Fact]
    public void ExtensionProperty_ValueClassAndEnumReceivers()
    {
        Paws? noPaws = null;
        BindleTag? noTag = null;
        Drowse? awake = null;
        Assert.Equal(12, new Paws(3).ToeBeans);
        Assert.Equal(-1, noPaws.ToeBeansOrNone);
        Assert.Equal(8, ((Paws?)new Paws(2)).ToeBeansOrNone);
        Assert.Equal("blank", noTag.TagOrBlank);
        Assert.Equal("oreo-1", ((BindleTag?)new BindleTag("oreo-1")).TagOrBlank);
        Assert.Equal("awake", awake.Drowsiness);
        Assert.Equal("DEEP", ((Drowse?)Drowse.Deep).Drowsiness);
    }

    [Fact]
    public void ExtensionProperty_ByteArrayReceivers()
    {
        byte[]? none = null;
        Assert.Equal(3, new byte[] { 1, 2 }.CrumbTotal);
        Assert.True(none.IsBlank);
        Assert.True(Array.Empty<byte>().IsBlank);
        Assert.False(new byte[] { 1 }.IsBlank);
    }

    [Fact]
    public void ExtensionProperty_StdlibReceivers()
    {
        TimeSpan? none = null;
        Assert.Equal("b5c6d7e8f90a", OreoChip.ChipTail);
        Assert.Equal(-1L, none.NapOrNone);
        Assert.Equal(45L, ((TimeSpan?)TimeSpan.FromMinutes(45)).NapOrNone);
    }

    // ---- suspend functions -----------------------------------------------------------------

    [Fact]
    public async Task Bindle_UnpackLaterAsync_GenericReceiverOnTheSuspendRoute()
    {
        using var kibble = new Bindle<string>("kibble");
        using var oreo = new Loafer("Oreo");
        using var carried = new Bindle<Loafer>(oreo);
        Assert.Equal("kibble", await kibble.UnpackLaterAsync());
        using Loafer back = await carried.UnpackLaterAsync();
        Assert.Equal("Oreo", back.Name);

        MethodInfo later = typeof(BindleExtensions).GetMethod("UnpackLaterAsync")!;
        Assert.True(later.IsGenericMethodDefinition);
        Assert.Equal(typeof(CancellationToken), later.GetParameters()[^1].ParameterType);
    }

    [Fact]
    public async Task Bindle_ClosedAndStarProjectedReceiversOnTheSuspendRoute()
    {
        using var lives = new Bindle<int>(21);
        using var empty = new Bindle<string?>(null);
        Assert.Equal(42, await lives.DoubledLaterAsync());
        Assert.True(await lives.IsPackedLaterAsync());
        Assert.False(await empty.IsPackedLaterAsync());
    }

    [Fact]
    public async Task BareTypeParameter_SuspendReceivers()
    {
        using var oreo = new Loafer("Oreo");
        Assert.Equal("Oreo yawns", await oreo.YawnLaterAsync());
        Assert.Equal("Mylo yawns", await new MyloDozer().YawnLaterAsync());
        Assert.Equal("tag:42", await 42.TagLaterAsync());
        Assert.Equal("tag:Mylo", await "Mylo".TagLaterAsync());

        Type t = typeof(DozerExtensions).GetMethod("YawnLaterAsync")!.GetGenericArguments().Single();
        Assert.Contains(typeof(IDozer), t.GetGenericParameterConstraints());
        Assert.NotNull(typeof(ObjectExtensions).GetMethod("TagLaterAsync"));
    }

    [Fact]
    public async Task List_TotalAsync_CollectionReceiverAcceptsAnyReadOnlyList()
    {
        IReadOnlyList<int>? none = null;
        Assert.Equal(3, await new List<int> { 1, 2 }.TotalAsync());
        Assert.Equal(9, await new[] { 4, 5 }.TotalAsync());
        Assert.Equal(7, await new[] { 3, 4 }.TotalOrZeroAsync());
        Assert.Equal(0, await none.TotalOrZeroAsync());

        MethodInfo total = typeof(ListExtensions).GetMethod("TotalAsync")!;
        Assert.Equal(typeof(IReadOnlyList<int>), total.GetParameters()[0].ParameterType);
        Assert.Equal(typeof(Task<int>), total.ReturnType);
    }

    [Fact]
    public async Task List_TallyForeverAsync_CancelsWithACollectionReceiverInFlight()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => new List<int> { 1, 2 }.TallyForeverAsync(cts.Token));
    }

    [Fact]
    public async Task Dozer_GreetAsync_InterfaceReceiverOnTheSuspendRoute()
    {
        using var oreo = new Loafer("Oreo");
        IDozer? none = null;
        Assert.Equal("hi Oreo", await oreo.GreetAsync());
        Assert.Equal("hi Mylo", await new MyloDozer().GreetAsync());
        Assert.Equal("hi Oreo", await ((IDozer?)oreo).GreetOrNobodyAsync());
        Assert.Equal("nobody", await none.GreetOrNobodyAsync());
    }

    [Trait("Direction", "Reverse")]
    [Fact]
    public async Task Feedable_BowlsLaterAsync_BoundInterfaceReceiverOnTheSuspendRoute()
    {
        IFeedable nibbles = new Goat();
        IFeedable? none = null;
        Assert.Equal(4, await nibbles.BowlsLaterAsync());
        Assert.Equal(4, await nibbles.BowlsLaterOrNoneAsync());
        Assert.Equal(-1, await none.BowlsLaterOrNoneAsync());
    }

    [Fact]
    public async Task ValueClass_SuspendReceivers()
    {
        Paws? noPaws = null;
        BindleTag? noTag = null;
        Assert.Equal(4, await new Paws(3).TuckLaterAsync());
        Assert.Equal(2, await ((Paws?)new Paws(2)).TuckLaterOrNoneAsync());
        Assert.Equal(-1, await noPaws.TuckLaterOrNoneAsync());
        Assert.Equal("oreo-1", await new BindleTag("oreo-1").BadgeLaterAsync());
        Assert.Equal("stray", await noTag.BadgeOrStrayAsync());
    }

    [Fact]
    public async Task EnumAndNullableScalar_SuspendReceivers()
    {
        Drowse? quiet = null;
        int? noLives = null;
        char? noPaw = null;
        string? stray = null;
        Assert.Equal(Drowse.Deep, await Drowse.Catnap.DeepenAsync());
        Assert.Equal("quiet", await quiet.LoudlyAsync());
        Assert.Equal("DEEP", await ((Drowse?)Drowse.Deep).LoudlyAsync());
        Assert.Equal(-1, await noLives.OrNoneAsync());
        Assert.Equal(9, await ((int?)9).OrNoneAsync());
        Assert.Equal("no paw", await noPaw.PawOrNoneAsync());
        Assert.Equal("O-paw", await ((char?)'O').PawOrNoneAsync());
        Assert.Equal("stray", await stray.NameOrStrayAsync());
        Assert.Equal("Oreo", await ((string?)"Oreo").NameOrStrayAsync());
    }

    [Fact]
    public async Task Stdlib_SuspendReceivers()
    {
        Guid? noChip = null;
        DateTimeOffset? never = null;
        TimeSpan? noNap = null;
        Assert.Equal("1b2c3d4e", await OreoChip.ShortAsync());
        Assert.Equal("1b2c3d4e", await ((Guid?)OreoChip).ShortOrNoneAsync());
        Assert.Equal("none", await noChip.ShortOrNoneAsync());
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(0), await never.LaterOrEpochAsync());
        Assert.Equal(OreoBirthday.AddMinutes(1), await ((DateTimeOffset?)OreoBirthday).LaterOrEpochAsync());
        Assert.Equal(TimeSpan.Zero, await noNap.LongerOrZeroAsync());
        Assert.Equal(TimeSpan.FromMinutes(6), await ((TimeSpan?)TimeSpan.FromMinutes(5)).LongerOrZeroAsync());
    }

    [Fact]
    public async Task ByteArray_SuspendReceivers()
    {
        byte[]? none = null;
        Assert.Equal(3, await new byte[] { 1, 2 }.ChecksumAsync());
        Assert.Equal(6, await ((byte[]?)new byte[] { 1, 2, 3 }).ChecksumOrNoneAsync());
        Assert.Equal(-1, await none.ChecksumOrNoneAsync());
    }

    // ---- suspend Result<T>, Flow and member extensions ---------------------------------------

    [Fact]
    public async Task Dozer_WakeOrFailAsync_ResultSuccessIsTheTaskValue()
    {
        using var oreo = new Loafer("Oreo");
        Assert.Equal("Oreo is awake", await oreo.WakeOrFailAsync(true));
        Assert.Equal(typeof(Task<string>), typeof(DozerExtensions).GetMethod("WakeOrFailAsync")!.ReturnType);
    }

    [Fact]
    public async Task Dozer_WakeOrFailAsync_ResultFailureFaultsTheTask()
    {
        using var mylo = new Loafer("Mylo");
        var refused = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => mylo.WakeOrFailAsync(false));
        Assert.Contains("Mylo refuses to wake", refused.Message);
    }

    [Fact]
    public async Task Dozer_RecallAsync_ReifiedTypeParameterReifiesAtItsBound()
    {
        using var oreo = new Loafer("Oreo");
        Assert.Equal("kibble", await oreo.RecallAsync("kibble"));
        Assert.Equal(3, await oreo.RecallAsync(3));
    }

    [Fact]
    public async Task Bindles_FetchKibbleAsync_TopLevelResultOnTheSuspendRoute()
    {
        Assert.Equal("kibble", await Bindles.FetchKibbleAsync(true));
        var empty = await Assert.ThrowsAnyAsync<ArgumentException>(() => Bindles.FetchKibbleAsync(false));
        Assert.Contains("the bowl is empty", empty.Message);
    }

    [Fact]
    public async Task Loafer_WatchAsync_FlowResultOfASuspendExtension()
    {
        using var mylo = new Loafer("Mylo");
        using KotlinFlow<int> ticks = await mylo.WatchAsync(3);
        var seen = new List<int>();
        await foreach (int tick in ticks) seen.Add(tick);
        Assert.Equal([0, 1, 2], seen);
    }

    // ---- Flow results and Flow parameters on sync extensions ---------------------------------

    private static async Task<List<T>> Drain<T>(KotlinFlow<T> flow)
    {
        var seen = new List<T>();
        await foreach (T item in flow) seen.Add(item);
        return seen;
    }

    private static async IAsyncEnumerable<T> Events<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    [Fact]
    public async Task Loafer_Ticks_FlowResultOfASyncExtension()
    {
        using var mylo = new Loafer("Mylo");
        using KotlinFlow<int> ticks = mylo.Ticks(3);
        Assert.Equal([0, 1, 2], await Drain(ticks));
    }

    [Fact]
    public async Task Loafer_TicksOrNone_NullableReceiverOnTheStaticFlowRoute()
    {
        using var oreo = new Loafer("Oreo");
        Loafer? nobody = null;
        using (KotlinFlow<string> named = oreo.TicksOrNone()) Assert.Equal(["Oreo"], await Drain(named));
        using (KotlinFlow<string> none = nobody.TicksOrNone()) Assert.Equal(["nobody"], await Drain(none));
    }

    [Fact]
    public async Task Bindle_Drip_GenericReceiverOnTheStaticFlowRoute()
    {
        using var kibble = new Bindle<string>("kibble");
        using KotlinFlow<string> drip = kibble.Drip();
        Assert.Equal(["kibble", "kibble"], await Drain(drip));
        Assert.True(typeof(BindleExtensions).GetMethod("Drip")!.IsGenericMethodDefinition);
    }

    [Fact]
    public async Task CollectionAndNullableEnumReceivers_OnTheStaticFlowRoute()
    {
        Drowse? awake = null;
        using (KotlinFlow<int> stream = new[] { 4, 5 }.Stream()) Assert.Equal([4, 5], await Drain(stream));
        using (KotlinFlow<string> drift = awake.Drift()) Assert.Equal(["awake"], await Drain(drift));
        using (KotlinFlow<string> deep = ((Drowse?)Drowse.Deep).Drift()) Assert.Equal(["DEEP"], await Drain(deep));
    }

    [Fact]
    public void Loafer_PurrAndChatter_HotFlowResultsOfASyncExtension()
    {
        using var mylo = new Loafer("Mylo");
        using (KotlinStateFlow<int> purr = mylo.Purr()) Assert.Equal(4, purr.Value);
        using (KotlinSharedFlow<string> chatter = mylo.Chatter()) Assert.Equal(new[] { "Mylo" }, chatter.ReplayCache);
    }

    [Fact]
    public async Task Bindle_Relay_FlowParameterOnAGenericReceiver()
    {
        using var bindle = new Bindle<int>(1);
        using KotlinFlow<int> relay = bindle.Relay(Events(2, 3));
        Assert.Equal([1, 2, 3], await Drain(relay));
    }

    [Fact]
    public void Loafer_Hears_FlowParameterOnANullableReceiver()
    {
        using var oreo = new Loafer("Oreo");
        Loafer? nobody = null;
        Assert.Equal("Oreo listens", oreo.Hears(Events(1)));
        Assert.Equal("nobody", nobody.Hears(Events(1)));
    }

    [Fact]
    public void Groomhand_Brush_MemberExtensionTakesTheReceiverFirst()
    {
        using var hand = new Groomhand("Oreo");
        using var mylo = new Loafer("Mylo");
        Assert.Equal("Oreo brushes Mylo 3 times", hand.Brush(mylo, 3));

        MethodInfo brush = typeof(Groomhand).GetMethod("Brush")!;
        Assert.False(brush.IsStatic);
        Assert.Equal(typeof(Loafer), brush.GetParameters()[0].ParameterType);
        Assert.Equal("receiver", brush.GetParameters()[0].Name);
        Assert.Equal("receiver", typeof(Groomhand).GetMethod("BrushLaterAsync")!.GetParameters()[0].Name);
    }

    [Fact]
    public void Groomhand_MemberExtensionProperties_AreAccessorMethodsTakingTheReceiver()
    {
        using var hand = new Groomhand("Oreo");
        using var mylo = new Loafer("Mylo");
        Loafer? nobody = null;
        Assert.Equal("Oreo's Mylo", hand.GetNick(mylo));
        Assert.Equal("Mylo", hand.GetNickOrNone(mylo));
        Assert.Equal("nobody", hand.GetNickOrNone(nobody));
        Assert.Equal(0, hand.GetMood(mylo));
        hand.SetMood(mylo, 7);
        Assert.Equal(7, hand.GetMood(mylo));
        using var bindle = new Bindle<string>("kibble");
        Assert.Equal("kibble", hand.GetPeekInside(bindle));

        MethodInfo getNick = typeof(Groomhand).GetMethod("GetNick")!;
        Assert.False(getNick.IsStatic);
        Assert.Equal("receiver", getNick.GetParameters()[0].Name);
        Assert.True(typeof(Groomhand).GetMethod("GetPeekInside")!.IsGenericMethodDefinition);
        Assert.Null(typeof(Groomhand).GetProperty("Nick"));
    }

    [Fact]
    public async Task Groomhand_Stream_FlowTypedMemberExtensionPropertyIsAFlowMethod()
    {
        using var hand = new Groomhand("Oreo");
        using var mylo = new Loafer("Mylo");
        using KotlinFlow<string> stream = hand.Stream(mylo);
        Assert.Equal(["Oreo", "Mylo"], await Drain(stream));
    }

    // ---- member extensions on every owner kind -----------------------------------------------

    [Fact]
    public async Task Napkin_ObjectMemberExtensions_AreStaticMethodsTakingTheReceiver()
    {
        using var mylo = new Loafer("Mylo");
        Assert.Equal("Mylo snoozes on the napkin", Napkin.GetSnooze(mylo));
        Napkin.SetDozeCount(mylo, 4);
        Assert.Equal(4, Napkin.GetDozeCount(mylo));
        Assert.Equal("Mylo curls up on the napkin for 5", await Napkin.CurlUpAsync(mylo, 5));
        using (KotlinFlow<int> purrs = Napkin.Purrs(mylo, 2)) Assert.Equal([2, 3], await Drain(purrs));
        using (KotlinFlow<string> snores = Napkin.Snores(mylo)) Assert.Equal(["napkin", "Mylo"], await Drain(snores));
    }

    [Fact]
    public async Task Cradle_CompanionMemberExtensions_AreStaticMethodsOnTheOwner()
    {
        using var oreo = new Loafer("Oreo");
        Assert.Equal("Oreo snoozes in the cradle", Cradle.GetSnooze(oreo));
        Assert.Equal("Oreo curls up in the cradle for 3", await Cradle.CurlUpAsync(oreo, 3));
        using (KotlinFlow<int> purrs = Cradle.Purrs(oreo, 4)) Assert.Equal([8], await Drain(purrs));
        using (KotlinFlow<string> snores = Cradle.Snores(oreo)) Assert.Equal(["cradle", "Oreo"], await Drain(snores));
    }

    [Fact]
    public async Task Drowse_EnumMemberExtensions_TakeTheReceiverAfterTheEnumValue()
    {
        using var mylo = new Loafer("Mylo");
        Assert.Equal("Mylo mylos on DEEP", Drowse.Deep.GetSnooze(mylo));
        Assert.Equal("Mylo curls up for 2 on CATNAP", await Drowse.Catnap.CurlUpAsync(mylo, 2));
        using (KotlinFlow<int> purrs = Drowse.Deep.Purrs(mylo, 7)) Assert.Equal([1, 7], await Drain(purrs));
        using (KotlinFlow<string> snores = Drowse.Catnap.Snores(mylo)) Assert.Equal(["CATNAP", "Mylo"], await Drain(snores));
    }

    [Fact]
    public async Task Paws_ValueClassMemberExtensions_AreInstanceMethodsOnTheStruct()
    {
        using var oreo = new Loafer("Oreo");
        var paws = new Paws(4);
        Assert.Equal("Oreo with 4 paws", paws.GetPawPrint(oreo));
        Assert.Equal("Oreo stretches 4 paws for 6", await paws.StretchAsync(oreo, 6));
        using (KotlinFlow<int> kneads = paws.Kneads(oreo, 3)) Assert.Equal([12], await Drain(kneads));
        using (KotlinFlow<int> steps = paws.PawSteps(oreo)) Assert.Equal([4, 4], await Drain(steps));
    }

    [Fact]
    public async Task Slumber_SealedMemberExtensionProperties_AreAccessorMethods()
    {
        using var mylo = new Loafer("Mylo");
        using var deep = new Slumber.Reverie();
        Assert.Equal("Mylo slumbers", deep.GetSnooze(mylo));
        Assert.Equal("Mylo dreams deeply", deep.GetDream(mylo));
        using KotlinFlow<string> snores = deep.Snores(mylo);
        Assert.Equal(["slumber", "Mylo"], await Drain(snores));
    }

    private sealed class CSharpChorus : IChorus
    {
        public string GetShout(string receiver) => receiver + "!";
        public string Echo(string receiver, int times) => string.Concat(Enumerable.Repeat(receiver, times));
        public void Dispose() { }
    }

    [Fact]
    public void Chorus_InterfaceMemberExtensions_BindOnBothSides()
    {
        using var choir = new Choir();
        Assert.Equal("MEOW", choir.GetShout("meow"));
        Assert.Equal("MEOW|meowmeow", Bindles.Sing(choir, "meow"));
        Assert.Equal("purr!|purrpurr", Bindles.Sing(new CSharpChorus(), "purr"));
    }

    // ADR-233: a bridge slot carries a class handle, so a member extension over a class receiver
    // is a slot a C# implementation fills, the receiver its first argument.
    private sealed class CSharpHideaway : IHideaway
    {
        public string GetHideout(Loafer receiver) => receiver.Name + " hides under the sofa";
        public string Peek(Loafer receiver, int times) => string.Concat(Enumerable.Repeat(receiver.Name, times));
        public void Dispose() { }
    }

    [Fact]
    public void Hideaway_ClassReceiverMemberExtensions_BindOnBothSides()
    {
        using var mylo = new Loafer("Mylo");
        using var cubby = new Cubby();
        Assert.Equal("Mylo hides in the cubby", cubby.GetHideout(mylo));
        Assert.Equal("Mylo hides in the cubby|Mylo peeksMylo peeks", Bindles.Scout(cubby, mylo));
        Assert.Equal("Mylo hides under the sofa|MyloMylo", Bindles.Scout(new CSharpHideaway(), mylo));
    }

    [Fact]
    public async Task Groomhand_BrushLaterAsync_SuspendMemberExtension()
    {
        using var hand = new Groomhand("Oreo");
        using var mylo = new Loafer("Mylo");
        Assert.Equal("Oreo brushed Mylo", await hand.BrushLaterAsync(mylo));
    }

    [Fact]
    public async Task Groomhand_AppointmentAsync_ClassResultOnTheSuspendRoute()
    {
        using var hand = new Groomhand("Mylo");
        Assert.Equal("booked with Mylo", await hand.AppointmentAsync(true));
        var outOfOffice = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => hand.AppointmentAsync(false));
        Assert.Contains("Mylo is out", outOfOffice.Message);
    }
}

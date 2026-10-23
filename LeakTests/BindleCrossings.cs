using Test.Menagerie;
using TestLibrary;
using TestLibrary.Bindle;

namespace LeakTests;

/// <summary>
/// ADR-238: the extension crossings <see cref="LiveHandleTests"/> hammers, one per row. They live
/// in their own file because the bindle namespace's <c>ObjectExtensions</c> puts <c>Tag</c> and
/// <c>Label</c> on every C# type; importing it into <c>LiveHandleTests.cs</c> would make that
/// file's other <c>Label</c> reads ambiguous.
/// Oreo rides in the bindle; Mylo is the C#-side dozer and Nibbles the C#-side goat.
/// </summary>
internal static class BindleCrossings
{
    internal sealed class MyloDozer : IDozer
    {
        public string Name => "Mylo";
        public void Dispose() { }
    }

    internal sealed class Goat : IFeedable
    {
        public string Describe() => "Nibbles the goat";
        public int Legs => 4;
        public void Feed(string food) { }
        public string? Nickname { get; set; }
    }

    // Generic and bare-`T` receivers on the sync plan route. Each Bindle is a wrapper +1/-1; a
    // boxed `T` argument or result (a string, an int, a Loafer) is a box +1/-1; an unpacked Loafer
    // is a fresh wrapper the `using` releases. `oreo` outlives the loop.
    public static void GenericReceivers(Loafer oreo)
    {
        using (var kibble = new Bindle<string>("kibble"))
        {
            Assert.Equal("kibble", kibble.Unpack());
            Assert.Equal("kibble", kibble.Swap("treat"));
            Assert.True(kibble.IsPacked());
        }
        using (var carried = new Bindle<Loafer>(oreo))
        {
            using Loafer back = carried.Unpack();
            Assert.Equal("Oreo", back.Name);
        }
        using (var lives = new Bindle<int>(21)) Assert.Equal(42, lives.Doubled());
        Assert.Equal(2, new List<string> { "Oreo", "Mylo" }.Headcount());
        Assert.Equal(2, new List<string> { "Oreo", "Mylo" }.RollCall);
        Assert.Equal("I am Oreo", oreo.Introduce());
        Assert.Equal("I am Mylo", new MyloDozer().Introduce());
        Assert.Equal("Oreo: Oreo preens", oreo.Primp());
        Assert.Equal("tag:42", 42.Tag());
        Assert.Equal("tag!:Oreo", "Oreo".NonNullTag());
    }

    // Generic extension properties: the getter's boxed `T` result and the setter's boxed `T`
    // value, including a Loafer handle through the setter. Each Bindle +1/-1.
    public static void GenericProperties(Loafer oreo, Loafer mylo)
    {
        using (var bindle = new Bindle<int>(1))
        {
            bindle.Lid = 9;
            Assert.Equal(9, bindle.Peek);
            Assert.True(bindle.Packed);
        }
        using (var carried = new Bindle<Loafer>(oreo))
        {
            carried.Lid = mylo;
            using Loafer back = carried.Peek;
            Assert.Equal("Mylo", back.Name);
        }
        Assert.Equal("badge:Oreo", oreo.Badge);
        Assert.Equal("label:Mylo", "Mylo".Label);
    }

    // Every other sync receiver that mints a handle for the crossing: a list StableRef, a bytes
    // handle, a C#-side dozer's bridge and the goat's transfer GCHandle, each null too. The C#
    // `finally` releases the containers; Kotlin drops the bridges.
    public static void HandleMintingReceivers(IFeedable goat)
    {
        List<int>? noList = null;
        byte[]? noBytes = null;
        IFeedable? noGoat = null;
        Assert.Equal(6, new List<int> { 1, 2, 3 }.TallyUp());
        Assert.Equal(-1, noList.TallyOrNone());
        Assert.Equal(3, new List<int> { 1, 2 }.Tally);
        Assert.Equal(0, noList.TallyOrZero);
        Assert.Equal("Mylo snores", new MyloDozer().SnoreLine());
        Assert.Equal("Mylo zzz", new MyloDozer().Snore);
        Assert.Equal("Nibbles the goat gets 4 bowls", goat.BowlLine());
        Assert.Equal(4, goat.Bowls);
        Assert.Equal(0, noGoat.BowlsOrZero);
        Assert.Equal(4, goat.BowlsOrZero);
        Assert.Equal(4, goat.BowlsOrNone());
        Assert.Equal(-1, noGoat.BowlsOrNone());
        Assert.Equal(3, new byte[] { 1, 2 }.Crumbs());
        Assert.Equal(3, new byte[] { 1, 2 }.CrumbTotal);
        Assert.True(noBytes.IsBlank);
        Assert.Equal("[oreo-1]", new BindleTag("oreo-1").Stamp());
    }

    // A C#-built list container is disposed by C# once the `_async` export returns; Kotlin copied
    // it in the prelude, so completion and cancellation both end on baseline. Each call's job +1/-1.
    public static async Task SuspendCollectionReceivers()
    {
        IReadOnlyList<int>? none = null;
        Assert.Equal(3, await new List<int> { 1, 2 }.TotalAsync());
        Assert.Equal(9, await new[] { 4, 5 }.TotalOrZeroAsync());
        Assert.Equal(0, await none.TotalOrZeroAsync());
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(5));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => new List<int> { 1, 2 }.TallyForeverAsync(cts.Token));
    }

    // The goat crosses as a fresh transfer GCHandle that Kotlin's cleaner owns. Each job +1/-1.
    public static async Task SuspendBoundInterfaceReceivers(IFeedable goat)
    {
        IFeedable? none = null;
        Assert.Equal(4, await goat.BowlsLaterAsync());
        Assert.Equal(4, await goat.BowlsLaterOrNoneAsync());
        Assert.Equal(-1, await none.BowlsLaterOrNoneAsync());
    }

    // Every other suspend receiver kind on completion: generic, closed, star, bare `T`, interface
    // (Kotlin-backed and C#-side), value classes, enums, nullable scalars, stdlib types, bytes, a
    // `Result<T>` success, a `Flow` result and the member extensions. A returned Loafer and the
    // awaited flow are wrappers the `using`s release. Each job +1/-1. `oreo` and `hand` outlive
    // the loop.
    public static async Task SuspendEveryReceiver(Loafer oreo, Groomhand hand)
    {
        Paws? noPaws = null;
        Drowse? quiet = null;
        int? noLives = null;
        string? stray = null;
        Guid? noChip = null;
        DateTimeOffset? never = null;
        byte[]? noBytes = null;
        IDozer? nobody = null;
        using (var kibble = new Bindle<string>("kibble")) Assert.Equal("kibble", await kibble.UnpackLaterAsync());
        using (var carried = new Bindle<Loafer>(oreo))
        {
            using Loafer back = await carried.UnpackLaterAsync();
            Assert.Equal("Oreo", back.Name);
            Assert.True(await carried.IsPackedLaterAsync());
        }
        using (var lives = new Bindle<int>(21)) Assert.Equal(42, await lives.DoubledLaterAsync());
        Assert.Equal("Oreo yawns", await oreo.YawnLaterAsync());
        Assert.Equal("tag:42", await 42.TagLaterAsync());
        Assert.Equal("hi Oreo", await oreo.GreetAsync());
        Assert.Equal("hi Mylo", await new MyloDozer().GreetAsync());
        Assert.Equal("nobody", await nobody.GreetOrNobodyAsync());
        Assert.Equal(4, await new Paws(3).TuckLaterAsync());
        Assert.Equal(-1, await noPaws.TuckLaterOrNoneAsync());
        Assert.Equal("oreo-1", await new BindleTag("oreo-1").BadgeLaterAsync());
        Assert.Equal("quiet", await quiet.LoudlyAsync());
        Assert.Equal(-1, await noLives.OrNoneAsync());
        Assert.Equal("stray", await stray.NameOrStrayAsync());
        Assert.Equal("none", await noChip.ShortOrNoneAsync());
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(0), await never.LaterOrEpochAsync());
        Assert.Equal(3, await new byte[] { 1, 2 }.ChecksumAsync());
        Assert.Equal(-1, await noBytes.ChecksumOrNoneAsync());
        Assert.Equal("Oreo is awake", await oreo.WakeOrFailAsync(true));
        Assert.Equal("kibble", await oreo.RecallAsync("kibble"));
        using (var mylo = new Loafer("Mylo"))
        {
            Assert.Equal("Oreo brushes Mylo 2 times", hand.Brush(mylo, 2));
            Assert.Equal("Oreo brushed Mylo", await hand.BrushLaterAsync(mylo));
            using KotlinFlow<int> ticks = await mylo.WatchAsync(2);
            await foreach (int _ in ticks) { }
        }
    }

    // A `Result<T>` failure on every suspend owner: the job on the faulted arm and the Kotlin
    // throwable as one envelope the C# exception owns.
    public static async Task SuspendResultFailures(Loafer oreo, Groomhand hand)
    {
        await Assert.ThrowsAnyAsync<InvalidOperationException>(() => oreo.WakeOrFailAsync(false));
        await Assert.ThrowsAnyAsync<ArgumentException>(() => Bindles.FetchKibbleAsync(false));
        await Assert.ThrowsAnyAsync<InvalidOperationException>(() => hand.AppointmentAsync(false));
    }

    // A `Flow` family result on a sync extension, over a handle, a nullable handle, a generic, a
    // collection and a nullable-enum receiver, collected and disposed: each flow wrapper +1/-1, each
    // collection its job +1/-1, the list container released by C# once the extern returns.
    public static async Task FlowResultReceivers(Loafer oreo)
    {
        Loafer? nobody = null;
        Drowse? awake = null;
        using (KotlinFlow<int> ticks = oreo.Ticks(2)) await foreach (int _ in ticks) { }
        using (KotlinFlow<string> none = nobody.TicksOrNone()) await foreach (string _ in none) { }
        using (var bindle = new Bindle<string>("kibble"))
        using (KotlinFlow<string> drip = bindle.Drip()) await foreach (string _ in drip) { }
        using (KotlinFlow<int> stream = new List<int> { 1, 2 }.Stream()) await foreach (int _ in stream) { }
        using (KotlinFlow<string> drift = awake.Drift()) await foreach (string _ in drift) { }
        using (KotlinStateFlow<int> purr = oreo.Purr()) Assert.Equal(4, purr.Value);
        using (KotlinSharedFlow<string> chatter = oreo.Chatter()) Assert.Single(chatter.ReplayCache);
    }

    private static async IAsyncEnumerable<int> Events(params int[] items)
    {
        foreach (int item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    // A `Flow` parameter on a sync extension: the C# enumerable's key is held by Kotlin's source and
    // released with it, on a generic receiver (collected) and a nullable one (dropped uncollected).
    public static async Task FlowParameterReceivers(Loafer oreo)
    {
        Loafer? nobody = null;
        using (var bindle = new Bindle<int>(1))
        using (KotlinFlow<int> relay = bindle.Relay(Events(2, 3))) await foreach (int _ in relay) { }
        Assert.Equal("Oreo listens", oreo.Hears(Events(1)));
        Assert.Equal("nobody", nobody.Hears(Events(1)));
    }

    // Member extension properties as accessor methods: a borrowed receiver each, the `Flow` typed
    // one collected and disposed (its job and wrapper +1/-1). Net zero.
    public static async Task MemberExtensionProperties(Groomhand hand, Loafer oreo)
    {
        Loafer? nobody = null;
        Assert.Equal("Oreo's Oreo", hand.GetNick(oreo));
        Assert.Equal("nobody", hand.GetNickOrNone(nobody));
        hand.SetMood(oreo, 3);
        Assert.Equal(3, hand.GetMood(oreo));
        using (var bindle = new Bindle<string>("kibble")) Assert.Equal("kibble", hand.GetPeekInside(bindle));
        using (KotlinFlow<string> stream = hand.Stream(oreo)) await foreach (string _ in stream) { }
    }

    // ADR-238: member extensions on each owner kind, every route the owner carries: a borrowed
    // receiver each, a job +1/-1 per `suspend` call, a flow wrapper and its collection +1/-1.
    public static async Task ObjectMemberExtensions(Loafer oreo)
    {
        Assert.Equal("Oreo snoozes on the napkin", Napkin.GetSnooze(oreo));
        Napkin.SetDozeCount(oreo, 2);
        Assert.Equal(2, Napkin.GetDozeCount(oreo));
        Assert.StartsWith("Oreo", await Napkin.CurlUpAsync(oreo, 1));
        using (KotlinFlow<int> purrs = Napkin.Purrs(oreo, 1)) await foreach (int _ in purrs) { }
        using (KotlinFlow<string> snores = Napkin.Snores(oreo)) await foreach (string _ in snores) { }
    }

    public static async Task CompanionMemberExtensions(Loafer oreo)
    {
        Assert.Equal("Oreo snoozes in the cradle", Cradle.GetSnooze(oreo));
        Assert.StartsWith("Oreo", await Cradle.CurlUpAsync(oreo, 1));
        using (KotlinFlow<int> purrs = Cradle.Purrs(oreo, 1)) await foreach (int _ in purrs) { }
        using (KotlinFlow<string> snores = Cradle.Snores(oreo)) await foreach (string _ in snores) { }
    }

    public static async Task EnumMemberExtensions(Loafer oreo)
    {
        Assert.EndsWith("DEEP", Drowse.Deep.GetSnooze(oreo));
        Assert.StartsWith("Oreo", await Drowse.Deep.CurlUpAsync(oreo, 1));
        using (KotlinFlow<int> purrs = Drowse.Deep.Purrs(oreo, 1)) await foreach (int _ in purrs) { }
        using (KotlinFlow<string> snores = Drowse.Deep.Snores(oreo)) await foreach (string _ in snores) { }
    }

    public static async Task ValueClassMemberExtensions(Loafer oreo)
    {
        var paws = new Paws(4);
        Assert.Equal("Oreo with 4 paws", paws.GetPawPrint(oreo));
        Assert.StartsWith("Oreo", await paws.StretchAsync(oreo, 1));
        using (KotlinFlow<int> kneads = paws.Kneads(oreo, 1)) await foreach (int _ in kneads) { }
        using (KotlinFlow<int> steps = paws.PawSteps(oreo)) await foreach (int _ in steps) { }
    }

    public static async Task SealedMemberExtensions(Slumber.Reverie deep, Loafer oreo)
    {
        Assert.Equal("Oreo slumbers", deep.GetSnooze(oreo));
        Assert.Equal("Oreo dreams deeply", deep.GetDream(oreo));
        using KotlinFlow<string> snores = deep.Snores(oreo);
        await foreach (string _ in snores) { }
    }

    internal sealed class CSharpChorus : IChorus
    {
        public string GetShout(string receiver) => receiver + "!";
        public string Echo(string receiver, int times) => string.Concat(Enumerable.Repeat(receiver, times));
        public void Dispose() { }
    }

    // Both sides of an interface's member extensions: the Kotlin-backed Choir, and a C# one
    // crossing as a fresh transfer handle each call that Kotlin's bridge releases.
    public static void InterfaceMemberExtensions(Choir choir, IChorus chorus)
    {
        Assert.Equal("MEOW", choir.GetShout("meow"));
        Assert.Equal("purr!|purrpurr", Bindles.Sing(chorus, "purr"));
    }

    // Warm-up: the Groomhand mints its coroutine scope on its first `suspend` call
    // (`GetOrCreateScope()`) and holds it until disposed, so it is called once before the baseline.
    public static async Task WarmGroomhand(Groomhand hand) =>
        Assert.Equal("booked with Oreo", await hand.AppointmentAsync(true));
}

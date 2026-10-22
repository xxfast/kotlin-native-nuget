using TestLibrary;
using TestLibrary.Pawpost;

namespace IntegrationTests;

/// <summary>
/// ADR-240: the legacy <c>suspend</c> and <c>Flow</c> routes carry every type the ordinary route
/// already carries. A bare <c>Instant</c>, <c>Duration</c>, <c>Uuid</c>, <c>ByteArray</c> or
/// plain interface crosses as <c>DateTimeOffset</c>, <c>TimeSpan</c>, <c>Guid</c>,
/// <c>byte[]</c> or <c>IDoorbell</c>, and a value class over any underlying crosses as its record
/// struct, as a parameter and result of a <c>suspend</c> member (class, top level, extension
/// receiver, value-class member) and as a parameter and element of a <c>Flow</c>-returning member
/// (class and top level).
/// <para>
/// Red before ADR-240: the parameter side of each route refuses these types by name
/// (<c>SKIPPED_UNSUPPORTED_PARAMETER</c>), and the static suspend receiver refuses them as a
/// receiver, so none of the members below exists (CS1061 / CS0117).
/// </para>
/// Oreo (black, white middle) and Mylo (brown and creamy) run the household mail slot.
/// </summary>
public class AsyncWireUnderlyingTests
{
    private static readonly DateTimeOffset OreoPostmark = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000);
    private static readonly Guid MyloPostcode = Guid.Parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a");

    // Mylo answers the door himself, so the interface bridge is crossed from the C# side too.
    private sealed class MyloDoorbell : IDoorbell
    {
        public string Owner => "Mylo";
        public string Ring(int times) => "Mylo: " + string.Concat(Enumerable.Repeat("mrrp", times));
        public void Dispose() { }
    }

    private static async Task<List<T>> Drain<T>(IAsyncEnumerable<T> flow)
    {
        var seen = new List<T>();
        await foreach (T item in flow) seen.Add(item);
        return seen;
    }

    private static async Task<int> TakeThenCancel<T>(IAsyncEnumerable<T> flow, int take)
    {
        using var cts = new CancellationTokenSource();
        int seen = 0;
        await foreach (T _ in flow.WithCancellation(cts.Token))
        {
            if (++seen == take) cts.Cancel();
        }
        return seen;
    }

    // --- (1) bare types on the class suspend route ----------------------------------------------

    [Fact]
    public async Task Mailslot_Instant_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Oreo");
        Assert.Equal(OreoPostmark.AddMinutes(1), await slot.StampAsync(OreoPostmark));
        Assert.Equal(OreoPostmark, await slot.StampOrNullAsync(OreoPostmark));
        Assert.Null(await slot.StampOrNullAsync(null));
    }

    [Fact]
    public async Task Mailslot_Duration_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Mylo");
        Assert.Equal(TimeSpan.FromMinutes(90), await slot.HoldAsync(TimeSpan.FromMinutes(45)));
        Assert.Equal(TimeSpan.FromSeconds(3), await slot.HoldOrNullAsync(TimeSpan.FromSeconds(3)));
        Assert.Null(await slot.HoldOrNullAsync(null));
    }

    [Fact]
    public async Task Mailslot_Uuid_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Mylo");
        Assert.Equal(MyloPostcode, await slot.CodeAsync(MyloPostcode));
        Assert.Equal(MyloPostcode, await slot.CodeOrNullAsync(MyloPostcode));
        Assert.Null(await slot.CodeOrNullAsync(null));
    }

    [Fact]
    public async Task Mailslot_ByteArray_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Oreo");
        Assert.Equal(new byte[] { 4, 1, 9 }, await slot.ScanAsync(new byte[] { 4, 1 }));
        Assert.Equal(new byte[] { 2, 2 }, await slot.ScanOrNullAsync(new byte[] { 2, 2 }));
        Assert.Null(await slot.ScanOrNullAsync(null));
    }

    [Fact]
    public async Task Mailslot_Interface_SuspendParamAndResult_KotlinBackedAndCSharpImplemented()
    {
        await using var slot = new Mailslot("Oreo");
        using IDoorbell oreo = PawpostSample.OreoBell();
        var mylo = new MyloDoorbell();

        Assert.Equal("Oreo: dingding", await slot.HearAsync(oreo, 2));
        Assert.Equal("Mylo: mrrp", await slot.HearAsync(mylo, 1));
        using (IDoorbell back = await slot.AnswerAsync(oreo)) Assert.Equal("Oreo", back.Owner);
        Assert.Same(mylo, await slot.AnswerAsync(mylo));
        Assert.Same(mylo, await slot.AnswerOrNullAsync(mylo));
        Assert.Null(await slot.AnswerOrNullAsync(null));
    }

    // --- (2) value classes on the class suspend route -------------------------------------------

    [Fact]
    public async Task Mailslot_ValueClassOverStringAndInt_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Mylo");
        Assert.Equal(new PostName("Mylo x"), await slot.SignAsync(new PostName("Mylo")));
        Assert.Equal(new PostName("Oreo"), await slot.SignOrNullAsync(new PostName("Oreo")));
        Assert.Null(await slot.SignOrNullAsync(null));
        Assert.Equal(new PostCount(3), await slot.TallyAsync(new PostCount(2)));
        Assert.Equal(new PostCount(2), await slot.TallyOrNullAsync(new PostCount(2)));
        Assert.Null(await slot.TallyOrNullAsync(null));
    }

    [Fact]
    public async Task Mailslot_ValueClassOverInstantDurationUuid_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Oreo");
        Assert.Equal(OreoPostmark.AddMinutes(1), (await slot.FrankAsync(new PostStamp(OreoPostmark))).At);
        Assert.Equal(new PostStamp(OreoPostmark), await slot.FrankOrNullAsync(new PostStamp(OreoPostmark)));
        Assert.Null(await slot.FrankOrNullAsync(null));
        Assert.Equal(TimeSpan.FromMinutes(20), (await slot.LingerAsync(new PostDelay(TimeSpan.FromMinutes(10)))).Span);
        Assert.Equal(MyloPostcode, (await slot.RouteAsync(new PostCode(MyloPostcode))).Id);
    }

    [Fact]
    public async Task Mailslot_ValueClassOverByteArrayAndList_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Mylo");
        Assert.Equal(new byte[] { 3, 1 }, (await slot.PressAsync(new PostPrint(new byte[] { 3 }))).Pads);
        Assert.Equal(new byte[] { 5 }, (await slot.PressOrNullAsync(new PostPrint(new byte[] { 5 })))!.Value.Pads);
        Assert.Null(await slot.PressOrNullAsync(null));
        Assert.Equal(new[] { "bill", "postcard" },
            (await slot.PackAsync(new PostBundle(new List<string> { "postcard", "bill" }))).Letters);
        Assert.Equal(new[] { "catalogue" },
            (await slot.PackOrNullAsync(new PostBundle(new List<string> { "catalogue" })))!.Value.Letters);
        Assert.Null(await slot.PackOrNullAsync(null));
    }

    [Fact]
    public async Task Mailslot_ValueClassOverInterface_SuspendParamAndResult_RoundTrips()
    {
        await using var slot = new Mailslot("Oreo");
        var mylo = new MyloDoorbell();
        Assert.Same(mylo, (await slot.RelayAsync(new PostKnock(mylo))).Bell);
        Assert.Same(mylo, (await slot.RelayOrNullAsync(new PostKnock(mylo)))!.Value.Bell);
        Assert.Null(await slot.RelayOrNullAsync(null));
    }

    [Fact]
    public async Task Mailslot_ValueClass_InitRunsAtTheSuspendCrossing()
    {
        await using var slot = new Mailslot("Mylo");
        await Assert.ThrowsAnyAsync<ArgumentException>(() => slot.PressAsync(new PostPrint(Array.Empty<byte>())));
        await Assert.ThrowsAnyAsync<ArgumentException>(() => slot.PackAsync(
            new PostBundle(new List<string> { "a", "b", "c", "d" })));
        await Assert.ThrowsAnyAsync<ArgumentException>(() => slot.SignAsync(new PostName("")));
        await Assert.ThrowsAnyAsync<ArgumentException>(() => slot.PressAsync(default));
    }

    // --- cancellation and faults with a handle-backed argument in flight -------------------------

    [Fact]
    public async Task Mailslot_HandleBackedArguments_CancelThroughTheToken()
    {
        await using var slot = new Mailslot("Mylo");
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        using IDoorbell oreo = PawpostSample.OreoBell();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => slot.AwaitStampAsync(OreoPostmark, cts.Token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => slot.AwaitScanAsync(new byte[] { 1 }, cts.Token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => slot.AwaitAnswerAsync(oreo, cts.Token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => slot.AwaitPressAsync(new PostPrint(new byte[] { 1 }), cts.Token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => slot.AwaitPackAsync(new PostBundle(new List<string> { "bill" }), cts.Token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => slot.AwaitRelayAsync(new PostKnock(oreo), cts.Token));
    }

    [Fact]
    public async Task Mailslot_HandleBackedArguments_FaultWithTheKotlinException()
    {
        await using var slot = new Mailslot("Mylo");
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => slot.JamScanAsync(new byte[] { 1, 2 }));
        Assert.Equal("Mylo chewed 2 pads", ex.Message);
        ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(() => slot.JamAnswerAsync(new MyloDoorbell()));
        Assert.Equal("Mylo hid from Mylo", ex.Message);
        ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(
            () => slot.JamPackAsync(new PostBundle(new List<string> { "bill", "card" })));
        Assert.Equal("Mylo sat on 2 letters", ex.Message);
        ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(
            () => slot.JamRelayAsync(new PostKnock(new MyloDoorbell())));
        Assert.Equal("Mylo ignored Mylo", ex.Message);
    }

    // --- Flow-returning class members taking each type -------------------------------------------

    [Fact]
    public async Task Mailslot_BareTypes_FlowParamAndElement_RoundTrip()
    {
        await using var slot = new Mailslot("Oreo");
        using (KotlinFlow<DateTimeOffset> ticks = slot.StampTicks(OreoPostmark, 2))
            Assert.Equal(new[] { OreoPostmark, OreoPostmark.AddMinutes(1) }, await Drain(ticks));
        using (KotlinFlow<TimeSpan> holds = slot.HoldTicks(TimeSpan.FromMinutes(5), 2))
            Assert.Equal(new[] { TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(10) }, await Drain(holds));
        using (KotlinFlow<Guid> codes = slot.CodeEchoes(MyloPostcode, 2))
            Assert.Equal(new[] { MyloPostcode, MyloPostcode }, await Drain(codes));
        using (KotlinFlow<byte[]> scans = slot.ScanEchoes(new byte[] { 4 }, 2))
            Assert.Equal(new[] { new byte[] { 4, 0 }, new byte[] { 4, 1 } }, await Drain(scans));
    }

    [Fact]
    public async Task Mailslot_Interface_FlowParamAndElement_RoundTrip()
    {
        await using var slot = new Mailslot("Mylo");
        var mylo = new MyloDoorbell();
        using (KotlinFlow<string> rings = slot.Rings(mylo, 2))
            Assert.Equal(new[] { "Mylo: mrrp", "Mylo: mrrpmrrp" }, await Drain(rings));
        using (KotlinFlow<IDoorbell> bells = slot.Bells(mylo, 2))
            Assert.All(await Drain(bells), bell => Assert.Same(mylo, bell));
    }

    [Fact]
    public async Task Mailslot_ValueClasses_FlowParamAndElement_RoundTrip()
    {
        await using var slot = new Mailslot("Oreo");
        using (KotlinFlow<PostName> names = slot.SignEchoes(new PostName("Oreo"), 2))
            Assert.Equal(new[] { new PostName("Oreo0"), new PostName("Oreo1") }, await Drain(names));
        using (KotlinFlow<PostCount> tallies = slot.TallyTicks(new PostCount(7), 2))
            Assert.Equal(new[] { new PostCount(7), new PostCount(8) }, await Drain(tallies));
        using (KotlinFlow<PostStamp> stamps = slot.FrankTicks(new PostStamp(OreoPostmark), 2))
            Assert.Equal(OreoPostmark.AddMinutes(1), (await Drain(stamps))[1].At);
        using (KotlinFlow<PostPrint> prints = slot.PressEchoes(new PostPrint(new byte[] { 6 }), 2))
            Assert.Equal(new byte[] { 6, 1 }, (await Drain(prints))[1].Pads);
        using (KotlinFlow<PostBundle> bundles = slot.PackEchoes(new PostBundle(new List<string> { "bill" }), 2))
            Assert.Equal(new[] { "bill" }, (await Drain(bundles))[0].Letters);
        var mylo = new MyloDoorbell();
        using (KotlinFlow<PostKnock> knocks = slot.RelayEchoes(new PostKnock(mylo), 1))
            Assert.Same(mylo, (await Drain(knocks))[0].Bell);
    }

    [Fact]
    public async Task Mailslot_EndlessFlowsOverHandleArguments_StopOnCancellation()
    {
        await using var slot = new Mailslot("Mylo");
        using (KotlinFlow<byte[]> scans = slot.EndlessScans(new byte[] { 1, 2 }))
            Assert.Equal(4, await TakeThenCancel(scans, 4));
        using (KotlinFlow<string> rings = slot.EndlessRings(new MyloDoorbell()))
            Assert.Equal(4, await TakeThenCancel(rings, 4));
    }

    // --- top-level suspend ------------------------------------------------------------------------

    [Fact]
    public async Task TopLevel_BareTypes_SuspendParamAndResult_RoundTrip()
    {
        Assert.Equal(OreoPostmark.AddMinutes(1), await PawpostSample.PostmarkAsync(OreoPostmark));
        Assert.Equal(TimeSpan.FromMinutes(2), await PawpostSample.PostWaitAsync(TimeSpan.FromMinutes(1)));
        Assert.Equal(MyloPostcode, await PawpostSample.PostRouteAsync(MyloPostcode));
        Assert.Equal(new byte[] { 1, 7 }, await PawpostSample.PostPadsAsync(new byte[] { 1 }));
        Assert.Equal(new byte[] { 3 }, await PawpostSample.PostPadsOrNullAsync(new byte[] { 3 }));
        Assert.Null(await PawpostSample.PostPadsOrNullAsync(null));
        var mylo = new MyloDoorbell();
        Assert.Equal("Mylo: mrrpmrrp", await PawpostSample.PostRingAsync(mylo, 2));
        Assert.Same(mylo, await PawpostSample.PostBellAsync(mylo));
        Assert.Null(await PawpostSample.PostBellOrNullAsync(null));
        using IDoorbell oreo = PawpostSample.OreoBell();
        using (IDoorbell back = await PawpostSample.PostBellAsync(oreo)) Assert.Equal("Oreo", back.Owner);
    }

    [Fact]
    public async Task TopLevel_ValueClasses_SuspendParamAndResult_RoundTrip()
    {
        Assert.Equal(new PostName("Oreo x"), await PawpostSample.SignPostAsync(new PostName("Oreo")));
        Assert.Equal(new PostCount(8), await PawpostSample.TallyPostAsync(new PostCount(4)));
        Assert.Equal(OreoPostmark.AddMinutes(1), (await PawpostSample.FrankPostAsync(new PostStamp(OreoPostmark))).At);
        Assert.Equal(TimeSpan.FromMinutes(6), (await PawpostSample.DelayPostAsync(new PostDelay(TimeSpan.FromMinutes(5)))).Span);
        Assert.Equal(MyloPostcode, (await PawpostSample.RoutePostAsync(new PostCode(MyloPostcode))).Id);
        Assert.Equal(new byte[] { 8, 2 }, (await PawpostSample.PressPostAsync(new PostPrint(new byte[] { 8 }))).Pads);
        Assert.Equal(new[] { "bill", "card" },
            (await PawpostSample.PackPostAsync(new PostBundle(new List<string> { "card", "bill" }))).Letters);
        Assert.Null(await PawpostSample.PackPostOrNullAsync(null));
        var mylo = new MyloDoorbell();
        Assert.Same(mylo, (await PawpostSample.RelayPostAsync(new PostKnock(mylo))).Bell);
    }

    [Fact]
    public async Task TopLevel_HandleBackedArguments_CancelAndFault()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => PawpostSample.LingerPadsAsync(new byte[] { 1 }, cts.Token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => PawpostSample.LingerBundleAsync(new PostBundle(new List<string> { "bill" }), cts.Token));
        var ex = await Assert.ThrowsAnyAsync<InvalidOperationException>(
            () => PawpostSample.JamBundleAsync(new PostBundle(new List<string> { "bill" })));
        Assert.Equal("Mylo chewed 1 letters", ex.Message);
    }

    // --- top-level Flow (the static route) ------------------------------------------------------

    [Fact]
    public async Task TopLevel_FlowParamAndElement_RoundTrip()
    {
        using (KotlinFlow<byte[]> pads = PawpostSample.PadStream(new byte[] { 2 }, 2))
            Assert.Equal(new[] { new byte[] { 2, 0 }, new byte[] { 2, 1 } }, await Drain(pads));
        using (KotlinFlow<DateTimeOffset> stamps = PawpostSample.StampStream(OreoPostmark, 2))
            Assert.Equal(new[] { OreoPostmark, OreoPostmark.AddMinutes(1) }, await Drain(stamps));
        using (KotlinFlow<string> rings = PawpostSample.BellStream(new MyloDoorbell(), 1))
            Assert.Equal(new[] { "Mylo: mrrp" }, await Drain(rings));
        using (KotlinFlow<PostPrint> prints = PawpostSample.PrintStream(new PostPrint(new byte[] { 3 }), 2))
            Assert.Equal(new byte[] { 3, 1 }, (await Drain(prints))[1].Pads);
        using (KotlinFlow<PostBundle> bundles = PawpostSample.BundleStream(new PostBundle(new List<string> { "card" }), 1))
            Assert.Equal(new[] { "card" }, (await Drain(bundles))[0].Letters);
        using (KotlinFlow<PostName> names = PawpostSample.NameStream(new PostName("Mylo"), 1))
            Assert.Equal(new[] { new PostName("Mylo0") }, await Drain(names));
    }

    // --- suspend receivers: extensions and value-class members ---------------------------------

    [Fact]
    public async Task Extension_BareTypeReceivers_CrossOnTheirWire()
    {
        Assert.Equal(OreoPostmark.AddMinutes(5), await OreoPostmark.NudgeAsync(5));
        Assert.Equal(TimeSpan.FromMinutes(3), await TimeSpan.FromMinutes(1).StretchAsync());
        Assert.Equal(MyloPostcode.ToString().ToUpperInvariant(), await MyloPostcode.ShoutAsync());
        Assert.Equal(6, await new byte[] { 1, 2, 3 }.PadTotalAsync());
        IDoorbell mylo = new MyloDoorbell();
        Assert.Equal("Mylo: mrrpmrrp", await mylo.ChimeAsync());
        Assert.Equal("Mylo: mrrp", await mylo.ChimeOrSilenceAsync());
        IDoorbell? nobody = null;
        Assert.Equal("silence", await nobody.ChimeOrSilenceAsync());
    }

    [Fact]
    public async Task Extension_ValueClassReceivers_CrossOnTheirUnderlyingWire()
    {
        Assert.Equal("Dear Oreo", await new PostName("Oreo").GreetAsync());
        Assert.Equal(new PostCount(6), await new PostCount(3).DoubledAsync());
        Assert.Equal("postmarked 1700000000", await new PostStamp(OreoPostmark).PostmarkedAsync());
        Assert.Equal(new byte[] { 1, 0 }, (await new PostPrint(new byte[] { 1 }).SmudgeAsync()).Pads);
        Assert.Equal(2, await new PostBundle(new List<string> { "bill", "card" }).LetterCountAsync());
        Assert.Equal("answered Mylo", await new PostKnock(new MyloDoorbell()).AnsweredAsync());
    }

    [Fact]
    public async Task ValueClassMembers_ReferenceAndStdlibUnderlyings_AreInstanceAsyncMethods()
    {
        Assert.Equal(OreoPostmark.AddMinutes(30), (await new PostStamp(OreoPostmark).LaterAsync(30)).At);
        Assert.Equal(3, await new PostPrint(new byte[] { 1, 1, 1 }).PadCountAsync());
        Assert.Equal("bill", await new PostBundle(new List<string> { "bill", "card" }).TopAsync());
        Assert.Equal("Mylo: mrrp", await new PostKnock(new MyloDoorbell()).KnockAsync(1));
        await Assert.ThrowsAnyAsync<ArgumentException>(() => new PostPrint(Array.Empty<byte>()).PadCountAsync());
        await Assert.ThrowsAsync<ArgumentException>(() => default(PostBundle).TopAsync());
    }

    [Fact]
    public async Task Extension_ByteArrayReceiver_CancelsThroughTheToken()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new byte[] { 1 }.LingerOnAsync(cts.Token));
    }
}

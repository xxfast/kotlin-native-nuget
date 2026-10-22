using TestLibrary;
using TestLibrary.Pawpost;

namespace IntegrationTests;

/// <summary>
/// ADR-240: the result side of the async routes on its own (nothing goes in, each type comes back),
/// nullable and value-class receivers, members on value classes over <c>Duration</c> and
/// <c>Uuid</c>, a <c>StateFlow</c> member taking these types, and <c>object</c> and companion
/// owners. Oreo and Mylo run the post office.
/// </summary>
public class AsyncWireUnderlyingResultTests
{
    private static readonly DateTimeOffset OfficePostmark = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000);
    private static readonly Guid OfficeCode = Guid.Parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a");

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

    [Fact]
    public async Task ResultOnly_SuspendMembers_ReturnEachType()
    {
        await using var counter = new PostCounter();
        Assert.Equal(OfficePostmark, await counter.LastStampAsync());
        Assert.Equal(TimeSpan.FromMinutes(90), await counter.LastWaitAsync());
        Assert.Equal(OfficeCode, await counter.LastCodeAsync());
        Assert.Null(await counter.NoStampAsync());
        Assert.Equal(new PostPrint(new byte[] { 1, 2, 3 }), await counter.LastPrintAsync());
        Assert.Equal(new[] { "bill", "card" }, (await counter.LastBundleAsync()).Letters);
        PostKnock knock = await counter.LastKnockAsync();
        using (knock.Bell) Assert.Equal("Oreo", knock.Bell.Owner);
        Assert.Equal(new PostSeal(new PostPrint(new byte[] { 5 })), await counter.LastSealAsync());
    }

    [Fact]
    public async Task ResultOnly_FlowMembers_EmitEachType()
    {
        await using var counter = new PostCounter();
        using (KotlinFlow<DateTimeOffset> stamps = counter.Stamps())
            Assert.Equal(new[] { OfficePostmark, OfficePostmark.AddMinutes(1) }, await Drain(stamps));
        using (KotlinFlow<TimeSpan> waits = counter.Waits())
            Assert.Equal(new[] { TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(2) }, await Drain(waits));
        using (KotlinFlow<Guid> codes = counter.Codes())
            Assert.Equal(new[] { OfficeCode }, await Drain(codes));
        using (KotlinFlow<DateTimeOffset?> maybe = counter.MaybeStamps())
            Assert.Equal(new DateTimeOffset?[] { OfficePostmark, null }, await Drain(maybe));
        using (KotlinFlow<PostPrint> prints = counter.Prints())
            Assert.Equal(new[] { new PostPrint(new byte[] { 7 }), new PostPrint(new byte[] { 7, 7 }) }, await Drain(prints));
        using (KotlinFlow<PostBundle> bundles = counter.Bundles())
            Assert.Equal(new[] { "bill" }, (await Drain(bundles))[0].Letters);
        using (KotlinFlow<PostKnock> knocks = counter.Knocks())
        {
            PostKnock knock = (await Drain(knocks))[0];
            using (knock.Bell) Assert.Equal("Mylo", knock.Bell.Owner);
        }
        using (KotlinFlow<PostSeal> seals = counter.Seals())
            Assert.Equal(new PostSeal(new PostPrint(new byte[] { 4, 4 })), (await Drain(seals))[0]);
    }

    [Fact]
    public async Task AcquiredFlow_OfANarrowElement_Reads()
    {
        await using var counter = new PostCounter();
        using KotlinFlow<DateTimeOffset> stamps = await counter.AcquiredStampsAsync();
        Assert.Equal(new[] { OfficePostmark }, await Drain(stamps));
    }

    [Fact]
    public async Task StateFlowMembers_TakeAHandleBackedAndANarrowArgument()
    {
        await using var counter = new PostCounter();
        using (KotlinStateFlow<int> pads = counter.PadState(new byte[] { 1, 2, 3 }))
            Assert.Equal(3, pads.Value);
        using (KotlinStateFlow<long> epoch = counter.EpochState(OfficePostmark))
            Assert.Equal(1_700_000_000L, epoch.Value);
    }

    [Fact]
    public async Task ValueClassesOverDurationAndUuid_HaveInstanceAsyncMembers()
    {
        Assert.Equal(TimeSpan.FromMinutes(15), (await new PostWindow(TimeSpan.FromMinutes(10)).WidenedAsync(5)).Span);
        Assert.Equal("1b2c3d4e", await new PostTicket(OfficeCode).StubAsync());
        await Assert.ThrowsAnyAsync<ArgumentException>(() => new PostTicket(Guid.Empty).StubAsync());
        using KotlinFlow<PostWindow> windows = new PostWindow(TimeSpan.FromMinutes(1)).Widenings(2);
        Assert.Equal(new[] { new PostWindow(TimeSpan.FromMinutes(1)), new PostWindow(TimeSpan.FromMinutes(2)) }, await Drain(windows));
    }

    [Fact]
    public async Task NullableAndValueClassExtensionReceivers_Cross()
    {
        Assert.Equal(2, await new byte[] { 1, 2 }.PadsOrNoneAsync());
        byte[]? none = null;
        Assert.Equal(-1, await none.PadsOrNoneAsync());
        DateTimeOffset? stamp = OfficePostmark;
        Assert.Equal(OfficePostmark, await stamp.StampOrEpochAsync());
        DateTimeOffset? missing = null;
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(0), await missing.StampOrEpochAsync());
        PostPrint? print = new PostPrint(new byte[] { 1, 1 });
        Assert.Equal(2, await print.PrintOrBlankAsync());
        PostPrint? blank = null;
        Assert.Equal(0, await blank.PrintOrBlankAsync());
        Assert.Equal(TimeSpan.FromMinutes(5), (await new PostWindow(TimeSpan.FromMinutes(10)).HalvedAsync()).Span);
        Assert.Equal(new PostTicket(OfficeCode), await new PostTicket(OfficeCode).PunchedAsync());
        Assert.Equal(3, await new PostSeal(new PostPrint(new byte[] { 1, 2, 3 })).SealedAsync());
    }

    [Fact]
    public async Task ObjectAndCompanionOwners_CarryTheseTypes()
    {
        Assert.Equal(OfficePostmark.AddMinutes(2), await PostDesk.StampAtAsync(OfficePostmark, new byte[] { 1, 2 }));
        var mylo = new MyloDoorbell();
        Assert.Same(mylo, (await PostDesk.KnockWithAsync(mylo)).Bell);
        using (KotlinFlow<Guid> codes = PostDesk.CodeStream(OfficeCode, 2))
            Assert.Equal(new[] { OfficeCode, OfficeCode }, await Drain(codes));
        Assert.Equal(TimeSpan.FromMinutes(3), await PostBox.OpenAsync(new PostWindow(TimeSpan.FromMinutes(3))));
        using (KotlinFlow<PostTicket> tickets = PostBox.TicketStream(new PostTicket(OfficeCode), 1))
            Assert.Equal(new[] { new PostTicket(OfficeCode) }, await Drain(tickets));
    }

    [Fact]
    public async Task SettableHolders_OfNarrowTypesAndWireValueClasses_ReadAndWrite()
    {
        using var board = new PostBoard();
        using (KotlinMutableStateFlow<DateTimeOffset> stamp = board.LastStamp)
        {
            Assert.Equal(OfficePostmark, stamp.Value);
            stamp.Value = OfficePostmark.AddMinutes(10);
            Assert.Equal(OfficePostmark.AddMinutes(10), stamp.Value);
            Assert.True(stamp.CompareAndSet(OfficePostmark.AddMinutes(10), OfficePostmark));
        }
        using (KotlinMutableStateFlow<DateTimeOffset?> maybe = board.MaybeStamp)
        {
            Assert.Null(maybe.Value);
            maybe.Value = OfficePostmark;
            Assert.Equal(OfficePostmark, maybe.Value);
        }
        using (KotlinMutableStateFlow<TimeSpan> wait = board.LastWait)
        {
            wait.Value = TimeSpan.FromMinutes(7);
            Assert.Equal(TimeSpan.FromMinutes(7), wait.Value);
        }
        using (KotlinMutableStateFlow<Guid> code = board.LastCode)
        {
            Assert.Equal(OfficeCode, code.Value);
            code.Value = Guid.Parse("00000000-0000-0000-0000-000000000042");
        }
        using (KotlinMutableStateFlow<PostPrint> print = board.LastPrint)
        {
            print.Value = new PostPrint(new byte[] { 1, 2, 3, 4 });
            Assert.Equal(new PostPrint(new byte[] { 1, 2, 3, 4 }), print.Value);
            Assert.Throws<ArgumentException>(() => print.Value = default);
        }
        using (KotlinMutableSharedFlow<DateTimeOffset> feed = board.StampFeed)
        {
            Assert.True(feed.TryEmit(OfficePostmark.AddMinutes(1)));
            await feed.EmitAsync(OfficePostmark.AddMinutes(2));
        }
        Assert.Equal("1700000000|1700000000|7|00000000-0000-0000-0000-000000000042|4|1700000120", board.Summary());
    }

    [Fact]
    public async Task AwaitedStateFlows_OfNarrowTypesAndWireValueClasses_Read()
    {
        await using var clock = new PostClock();
        using (KotlinStateFlow<DateTimeOffset> stamp = await clock.AwaitStampAsync())
            Assert.Equal(OfficePostmark, stamp.Value);
        using (KotlinStateFlow<DateTimeOffset?> maybe = await clock.AwaitMaybeStampAsync())
            Assert.Null(maybe.Value);
        using (KotlinStateFlow<PostPrint> print = await clock.AwaitPrintAsync())
            Assert.Equal(new PostPrint(new byte[] { 3, 3 }), print.Value);
        using (KotlinStateFlow<Guid> code = await PostOffice.AwaitCodeAsync())
            Assert.Equal(OfficeCode, code.Value);
    }

    [Fact]
    public async Task ValueClassOverANullableUnderlying_KeepsNullAndAbsentApart()
    {
        PostNote blank = await PostOffice.BlankNoteAsync();
        Assert.Null(blank.Text);
        Assert.Null(await PostOffice.NoNoteAsync());
        using KotlinFlow<PostNote> notes = PostOffice.Notes();
        Assert.Equal(new[] { new PostNote("bill"), new PostNote(null) }, await Drain(notes));
    }

    [Fact]
    public async Task SettableHolders_OfBytesCollectionsAndInterfaces_CSharpWritesKotlinSees()
    {
        using var rack = new PostRack();
        var mylo = new MyloDoorbell();
        using (KotlinMutableStateFlow<byte[]> pads = rack.Pads)
        {
            Assert.Equal(new byte[] { 1 }, pads.Value);
            pads.Value = new byte[] { 2, 3 };
            Assert.Equal(new byte[] { 2, 3 }, pads.Value);
        }
        using (KotlinMutableStateFlow<byte[]?> maybe = rack.MaybePads)
        {
            Assert.Null(maybe.Value);
            maybe.Value = new byte[] { 7, 7, 7, 7 };
            Assert.Equal(new byte[] { 7, 7, 7, 7 }, maybe.Value);
        }
        using (KotlinMutableStateFlow<IReadOnlyList<string>> letters = rack.Letters)
        {
            letters.Value = new List<string> { "card", "bill" };
            Assert.Equal(new[] { "card", "bill" }, letters.Value);
            Assert.True(letters.CompareAndSet(new List<string> { "card", "bill" }, new List<string> { "parcel" }));
        }
        using (KotlinMutableStateFlow<IDoorbell> bell = rack.Bell)
        {
            bell.Value = mylo;
            Assert.Same(mylo, bell.Value);
        }
        using (KotlinMutableSharedFlow<byte[]> feed = rack.PadFeed)
        {
            Assert.True(feed.TryEmit(new byte[] { 4 }));
            await feed.EmitAsync(new byte[] { 5, 5 });
            Assert.Equal(new byte[] { 5, 5 }, feed.ReplayCache.Single());
        }
        Assert.Equal("5|4|parcel|Mylo: mrrp|10", rack.Summary());
    }

    [Fact]
    public void SettableHolders_OfBytesCollectionsAndInterfaces_KotlinWritesCSharpSees()
    {
        using var rack = new PostRack();
        rack.KotlinWrites();
        using (KotlinMutableStateFlow<byte[]> pads = rack.Pads) Assert.Equal(new byte[] { 9, 9, 9 }, pads.Value);
        using (KotlinMutableStateFlow<IReadOnlyList<string>> letters = rack.Letters)
            Assert.Equal(new[] { "card", "parcel" }, letters.Value);
        using (KotlinMutableStateFlow<IDoorbell> bell = rack.Bell)
        using (IDoorbell seen = bell.Value)
            Assert.Equal("Mylo", seen.Owner);
    }

    [Fact]
    public async Task BytesAndListArguments_AreCopiedForTheCall()
    {
        var pads = new byte[] { 1, 2, 3 };
        Task<int> sum = PostOffice.SumLaterAsync(pads);
        pads[0] = 100;
        Assert.Equal(6, await sum);
        var letters = new List<string> { "bill", "card" };
        Task<string> joined = PostOffice.JoinLaterAsync(letters);
        letters.Add("parcel");
        letters[0] = "flyer";
        Assert.Equal("bill,card", await joined);
    }

    [Fact]
    public async Task FailingValueClassInit_OnAnAsyncParameter_ThrowsExactlyKotlinArgumentException()
    {
        await using var slot = new Mailslot("Mylo");
        // The struct is boxed, running `init`, before the extern: the call throws, no Task is returned.
        Assert.Throws<KotlinArgumentException>(() => { _ = slot.PressAsync(new PostPrint(Array.Empty<byte>())); });
        Assert.Throws<KotlinArgumentException>(() => { _ = PawpostSample.PackPostAsync(new PostBundle(new List<string> { "a", "b", "c", "d" })); });
        // A receiver crosses on its underlying's wire and is re-wrapped inside the launch, so its
        // failing `init` faults the Task instead.
        Task<int> count = new PostPrint(Array.Empty<byte>()).PadCountAsync();
        await Assert.ThrowsAsync<KotlinArgumentException>(() => count);
    }

    [Fact]
    public async Task AwaitedSettableHolders_OfBytesCollectionsAndInterfaces_CSharpWritesKotlinSees()
    {
        await using var shelf = new PostShelf();
        var mylo = new MyloDoorbell();
        using (KotlinMutableStateFlow<IReadOnlyList<string>> letters = await shelf.AwaitLettersAsync())
        {
            Assert.Equal(new[] { "bill" }, letters.Value);
            letters.Value = new List<string> { "card", "parcel" };
            Assert.Equal(new[] { "card", "parcel" }, letters.Value);
            Assert.True(letters.CompareAndSet(new List<string> { "card", "parcel" }, new List<string> { "flyer" }));
        }
        using (KotlinMutableStateFlow<byte[]> pads = await shelf.AwaitPadsAsync())
        {
            Assert.Equal(new byte[] { 1 }, pads.Value);
            pads.Value = new byte[] { 4, 4 };
            Assert.Equal(new byte[] { 4, 4 }, pads.Value);
        }
        using (KotlinMutableStateFlow<IDoorbell> bell = await shelf.AwaitBellAsync())
        {
            bell.Value = mylo;
            Assert.Same(mylo, bell.Value);
        }
        using (KotlinMutableSharedFlow<IReadOnlyList<string>> feed = await shelf.AwaitLetterFeedAsync())
        {
            Assert.True(feed.TryEmit(new List<string> { "bill" }));
            await feed.EmitAsync(new List<string> { "card", "bill" });
            Assert.Equal(new[] { "card", "bill" }, feed.ReplayCache.Single());
        }
        using (KotlinMutableSharedFlow<byte[]> feed = await shelf.AwaitPadFeedAsync())
        {
            await feed.EmitAsync(new byte[] { 3, 3, 3 });
            Assert.Equal(new byte[] { 3, 3, 3 }, feed.ReplayCache.Single());
        }
        Assert.Equal("flyer|8|Mylo: mrrp|card,bill|9", shelf.Summary());
    }
}

using TestLibrary.Stickers;

namespace IntegrationTests;

/// <summary>
/// ADR-226: a function's own <c>T</c> inside a generic value class under <c>?</c>
/// (<c>Sticker&lt;T&gt;?</c>) and inside a <c>List</c>, <c>Set</c> or <c>Map</c>, and every value
/// class whose underlying can itself be null (<c>Nick(val name: String?)</c>,
/// <c>TreatCount(val count: Int?)</c>, the closed <c>Sticker&lt;string?&gt;</c>). A <c>null</c>
/// stays distinct from <c>Sticker(null)</c> in both directions, at a top-level position and as a
/// collection element.
///
/// Each open-instantiation row uses a type argument (<c>uint?</c>, <c>ushort</c>, <c>byte</c>,
/// <c>sbyte</c>, <c>ushort?</c>, <c>byte?</c>, <c>uint</c>, <c>sbyte?</c>) that no other test reads
/// as a <c>Sticker</c>, so the per-instantiation registration has to come from the call site.
///
/// The Kotlin fixture is <c>test-library/.../test/stickers/StickerGaps.kt</c>.
///
/// Oreo (black, white in the middle) leaves gaps in the album. Mylo (brown and creamy) fills them
/// with blank stickers, which are not the same thing as no sticker.
/// </summary>
public class GenericValueClassGapTests
{
    // --- List<Nick?> keeps null and Nick(null) apart ----------------------------------------

    [Fact]
    public void NullableUnderlyingElement_Result_KeepsNullAndNickOfNullApart()
    {
        IReadOnlyList<Nick?> nicks = StickerGaps.Nicknames();

        Assert.Equal(3, nicks.Count);
        Assert.Null(nicks[0]);
        Assert.Equal(new Nick(null), nicks[1]);
        Assert.Equal("Oreo", nicks[2]!.Value.Name);
    }

    [Fact]
    public void NullableUnderlyingElement_Parameter_KeepsNullAndNickOfNullApart()
    {
        Assert.Equal(
            "1 missing, 2 nameless",
            StickerGaps.CountNicks(new Nick?[] { null, new Nick(null), new Nick(null), new Nick("Mylo") }));
    }

    // --- own T under ? ------------------------------------------------------------------------

    [Fact]
    public void NullableSticker_OfAnUnconstrainedT_Result_KeepsNullAndStickerOfNullApart()
    {
        Assert.Null(StickerGaps.MaybeStick<uint?>(null, present: false));
        Sticker<uint?>? blank = StickerGaps.MaybeStick<uint?>(null, present: true);
        Assert.NotNull(blank);
        Assert.Null(blank!.Value.Value);
        Assert.Equal(7u, StickerGaps.MaybeStick<uint?>(7u, present: true)!.Value.Value);
    }

    [Fact]
    public void NullableSticker_OfAnUnconstrainedT_Parameter_KeepsNullAndStickerOfNullApart()
    {
        Assert.True(StickerGaps.IsAbsent<byte?>(null));
        Assert.False(StickerGaps.IsAbsent<byte?>(new Sticker<byte?>(null)));
        Assert.False(StickerGaps.IsAbsent<byte?>(new Sticker<byte?>(3)));
    }

    [Fact]
    public void NullableSticker_OfAnUnconstrainedT_RoundTripsBothWays()
    {
        Assert.Null(StickerGaps.PassGap<sbyte?>(null));
        Assert.Equal(new Sticker<sbyte?>(null), StickerGaps.PassGap<sbyte?>(new Sticker<sbyte?>(null)));
        Assert.Equal(new Sticker<sbyte?>(-2), StickerGaps.PassGap<sbyte?>(new Sticker<sbyte?>(-2)));
    }

    [Fact]
    public void NullableSticker_OfAnUnconstrainedT_RunsInitInKotlin_WhenTheBoxIsMinted()
    {
        // Mylo peeled this one already: Kotlin's init refuses -1 the moment the box is minted.
        Assert.ThrowsAny<ArgumentException>(() => StickerGaps.IsAbsent<int>(new Sticker<int>(-1)));
    }

    [Fact]
    public void NullableSticker_OfABoundT_RidesTheNullPointer()
    {
        Assert.Null(StickerGaps.FirmMaybe<string>(null));
        Assert.Equal("Oreo", StickerGaps.FirmMaybe("Oreo")!.Value.Value);
        Assert.Equal("Mylo", StickerGaps.FirmOr<string>(null, "Mylo"));
        Assert.Equal("Oreo", StickerGaps.FirmOr(new Sticker<string>("Oreo"), "Mylo"));
    }

    // --- own T inside a collection ------------------------------------------------------------

    [Fact]
    public void OwnTypeParameter_InsideAList_CrossesPerElement()
    {
        Assert.Equal(
            new[] { new Sticker<ushort>(1), new Sticker<ushort>(2) },
            StickerGaps.StickAll(new ushort[] { 1, 2 }));
    }

    [Fact]
    public void OwnTypeParameter_InsideASet_CrossesPerElement()
    {
        Assert.Equal(new byte[] { 4 }, StickerGaps.PeelAll(new HashSet<Sticker<byte>> { new(4) }));
    }

    [Fact]
    public void OwnTypeParameter_InsideASet_RunsInitInKotlin_OnTheElementRewrap()
    {
        Assert.ThrowsAny<ArgumentException>(
            () => StickerGaps.PeelAll(new HashSet<Sticker<int>> { new(2), new(-1) }));
    }

    [Fact]
    public void OwnTypeParameter_AsAMapValue_CrossesPerEntry()
    {
        IReadOnlyDictionary<string, Sticker<sbyte>> back = StickerGaps.ByLabel(
            new Dictionary<string, Sticker<sbyte>> { ["oreo"] = new(9), ["mylo"] = new(-9) });

        Assert.Equal(new Sticker<sbyte>(9), back["oreo"]);
        Assert.Equal(new Sticker<sbyte>(-9), back["mylo"]);
    }

    [Fact]
    public void OwnTypeParameter_AsAMapKey_CrossesPerEntry()
    {
        Assert.Equal(
            new[] { "Mylo", "Oreo" },
            StickerGaps.KeyedBy(new Dictionary<Sticker<uint>, string> { [new(1)] = "Oreo", [new(2)] = "Mylo" }));
    }

    [Fact]
    public void OwnTypeParameter_InsideAMutableList_CrossesPerElement()
    {
        IList<Sticker<string>> stack = StickerGaps.Restack(new[] { "Oreo", "Mylo" });

        Assert.Equal(new[] { new Sticker<string>("Oreo"), new Sticker<string>("Mylo") }, stack);
    }

    [Fact]
    public void OwnTypeParameter_InsideNestedLists_CrossesPerElement()
    {
        IReadOnlyList<IReadOnlyList<Sticker<string>>> rows = StickerGaps.StackRows(
            new[] { new[] { "Oreo" }, new[] { "Mylo", "Oreo" } });

        Assert.Equal(new Sticker<string>("Oreo"), rows[0][0]);
        Assert.Equal(new Sticker<string>("Mylo"), rows[1][0]);
        Assert.Equal(2, rows[1].Count);
    }

    [Fact]
    public void NullableStickerElement_KeepsNullAndStickerOfNullApart()
    {
        IReadOnlyList<Sticker<ushort?>?> back = StickerGaps.Gaps(
            new Sticker<ushort?>?[] { null, new Sticker<ushort?>(null), new Sticker<ushort?>(5) });

        Assert.Null(back[0]);
        Assert.Equal(new Sticker<ushort?>(null), back[1]);
        Assert.Equal(new Sticker<ushort?>(5), back[2]);
        Assert.Equal(
            "2 gaps, 1 blanks",
            StickerGaps.CountGaps(new Sticker<ushort?>?[] { null, new Sticker<ushort?>(null), null }));
    }

    [Fact]
    public void NullableStickerMapValue_KeepsNullAndStickerOfNullApart()
    {
        IReadOnlyDictionary<string, Sticker<byte?>?> pages = StickerGaps.GapPages(
            new Dictionary<string, Sticker<byte?>?> { ["one"] = null, ["two"] = new Sticker<byte?>(null) });

        Assert.Null(pages["one"]);
        Assert.Equal(new Sticker<byte?>(null), pages["two"]);
    }

    // --- closed Sticker<string?> ---------------------------------------------------------------

    [Fact]
    public void ClosedNullableUnderlying_AtANonNullOuter_BindsWithItsOwnNull()
    {
        Assert.Null(StickerGaps.BlankLabel().Value);
        Assert.Equal("blank", StickerGaps.LabelOr(new Sticker<string?>(null)));
        Assert.Equal("Oreo", StickerGaps.LabelOr(new Sticker<string?>("Oreo")));
    }

    [Fact]
    public void ClosedNullableUnderlying_UnderQuestionMark_KeepsNullAndStickerOfNullApart()
    {
        Assert.Equal(new Sticker<string?>(null), StickerGaps.MaybeLabel(true));
        Assert.Null(StickerGaps.MaybeLabel(false));
        Assert.Equal("no sticker", StickerGaps.LabelState(null));
        Assert.Equal("blank sticker", StickerGaps.LabelState(new Sticker<string?>(null)));
        Assert.Equal("Mylo", StickerGaps.LabelState(new Sticker<string?>("Mylo")));
    }

    [Fact]
    public void ClosedNullableUnderlyingElement_KeepsNullAndStickerOfNullApart()
    {
        IReadOnlyList<Sticker<string?>?> page = StickerGaps.AlbumPage();

        Assert.Null(page[0]);
        Assert.Equal(new Sticker<string?>(null), page[1]);
        Assert.Equal(new Sticker<string?>("Oreo"), page[2]);
    }

    // --- Nick and TreatCount ------------------------------------------------------------------

    [Fact]
    public void NullableUnderlying_AtANonNullOuter_BindsWithItsOwnNull()
    {
        Assert.Null(StickerGaps.BlankNick().Name);
        Assert.Equal("anon", StickerGaps.NickOrAnon(new Nick(null)));
        Assert.Equal("Oreo", StickerGaps.NickOrAnon(new Nick("Oreo")));
        Assert.Null(StickerGaps.Uncounted().Count);
        Assert.Equal(-1, StickerGaps.TreatsOr(new TreatCount(null)));
        Assert.Equal(3, StickerGaps.TreatsOr(new TreatCount(3)));
    }

    [Fact]
    public void NullableUnderlying_UnderQuestionMark_KeepsNullAndValueOfNullApart()
    {
        Assert.Equal(new Nick(null), StickerGaps.MaybeNick(true));
        Assert.Null(StickerGaps.MaybeNick(false));
        Assert.Equal("no nick", StickerGaps.NickState(null));
        Assert.Equal("nameless", StickerGaps.NickState(new Nick(null)));
        Assert.Equal("Mylo", StickerGaps.NickState(new Nick("Mylo")));

        Assert.Equal(new TreatCount(null), StickerGaps.MaybeTreats(true));
        Assert.Null(StickerGaps.MaybeTreats(false));
        Assert.Equal("no count", StickerGaps.TreatState(null));
        Assert.Equal("uncounted", StickerGaps.TreatState(new TreatCount(null)));
        Assert.Equal("4", StickerGaps.TreatState(new TreatCount(4)));
    }

    // --- extension receivers, star projection, class positions --------------------------------

    [Fact]
    public void ExtensionReceiver_UnderQuestionMark_KeepsNullAndValueOfNullApart()
    {
        Assert.Equal("no nick", NickExtensions.CallName(null));
        Assert.Equal("nameless", ((Nick?)new Nick(null)).CallName());
        Assert.Equal("gap", StickerExtensions.Presence<sbyte?>(null));
        Assert.Equal("blank", ((Sticker<sbyte?>?)new Sticker<sbyte?>(null)).Presence());
        Assert.Equal("stuck", ((Sticker<sbyte?>?)new Sticker<sbyte?>(1)).Presence());
    }

    [Fact]
    public void StarProjectedParameter_TakesAnyInstantiation()
    {
        Assert.Equal("star 3", StickerGaps.Starred(new Sticker<int>(3)));
        Assert.Equal("star Oreo", StickerGaps.Starred(new Sticker<string>("Oreo")));
        using var album = new GapAlbum(null, null);
        Assert.Equal("peek Mylo", album.Peek(new Sticker<string>("Mylo")));
    }

    [Fact]
    public void ClassPositions_KeepNullAndValueOfNullApart()
    {
        using var album = new GapAlbum(new Nick(null), new Sticker<string?>(null));

        Assert.Equal(new Nick(null), album.Spare);
        Assert.Equal(new Sticker<string?>(null), album.Cover);
        Assert.Equal(new TreatCount(null), album.Treats);
        Assert.Equal(new Nick?[] { new Nick(null), null }, album.Roll);

        album.Spare = null;
        album.Cover = null;
        album.Treats = null;
        Assert.Null(album.Spare);
        Assert.Null(album.Cover);
        Assert.Null(album.Treats);

        album.Treats = new TreatCount(2);
        Assert.Equal(new TreatCount(2), album.Treats);
    }

    [Fact]
    public void ClassMembers_OwnTypeParameter_UnderQuestionMarkAndInsideAList()
    {
        using var album = new GapAlbum(null, null);

        Assert.Null(album.SwapGap<string?>(null));
        Assert.Equal(new Sticker<string?>(null), album.SwapGap<string?>(new Sticker<string?>(null)));
        Assert.Equal(new[] { new Sticker<string>("Oreo") }, album.Fill(new[] { "Oreo" }));
    }
}

using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Issue463;
using TestLibrary.Stickers;

namespace IntegrationTests;

/// <summary>
/// ADR-234: the value class underlyings the earlier value class rules left out. A star- or
/// <c>out</c>-projected generic value class at a result takes a caller-supplied method type
/// parameter; a value class over a sealed type (<c>Clue</c> over the ineligible
/// <c>IConnectableDevice</c>, <c>Dossier</c> over the eligible <c>EvidenceDevice</c>) crosses at
/// every erased position; a value class over <c>Char</c> and <c>Char?</c>, and the closed
/// <c>Sticker&lt;char&gt;</c>, cross everywhere; and <c>TreatCount</c>, over a nullable primitive,
/// crosses at every erased position.
/// <para>
/// Oreo signs his stickers <c>O</c>. Mylo cannot spell yet, so his monogram is blank.
/// </para>
/// </summary>
public class ValueClassUnderlyingWideningTests
{
    // --- Star and out-projected results -----------------------------------------------------------

    [Fact]
    public void StarProjectedResult_TheCallerSuppliesTheTypeArgument()
    {
        Sticker<int> sticker = StickerWidenings.AnySticker<int>();

        Assert.Equal(new Sticker<int>(7), sticker);
    }

    [Fact]
    public void OutProjectedResult_TheCallerSuppliesTheTypeArgument()
    {
        Sticker<Cat> sticker = StickerWidenings.AnimalSticker<Cat>("Mylo");
        using Cat mylo = sticker.Value;

        Assert.Equal("Mylo", mylo.Name);
    }

    [Fact]
    public void StarProjectedNullableResult_PresentOrAbsent()
    {
        Assert.Equal(new Sticker<string>("Oreo"), StickerWidenings.AnyStickerOrNone<string>(true));
        Assert.Null(StickerWidenings.AnyStickerOrNone<string>(false));
    }

    [Fact]
    public void StarProjectedMemberResult_TheCallerSuppliesTheTypeArgument()
    {
        using var courier = new WideningCourier("Oreo's desk");

        Assert.Equal(new Sticker<string>("Oreo's desk"), courier.AnyPeel<string>());
    }

    // --- Char underlyings at ordinary positions ----------------------------------------------------

    [Fact]
    public void CharUnderlying_ReturnAndParameter()
    {
        Monogram oreo = StickerWidenings.OreoMonogram();

        Assert.Equal('O', oreo.Letter);
        Assert.Equal("signed M", StickerWidenings.MonogramOf(new Monogram('M')));
    }

    [Fact]
    public void CharUnderlying_InitRunsOnTheWayIn()
    {
        Assert.ThrowsAny<ArgumentException>(() => StickerWidenings.MonogramOf(new Monogram(' ')));
    }

    [Fact]
    public void CharUnderlying_Members()
    {
        var oreo = new Monogram('O');

        Assert.Equal("O!", oreo.Shout());
        Assert.Equal('o', oreo.Lower);
    }

    [Fact]
    public void CharUnderlying_Nullable_AndInCollections()
    {
        Assert.Equal(new Monogram('O'), StickerWidenings.MaybeSign(new Monogram('O')));
        Assert.Null(StickerWidenings.MaybeSign(null));
        Assert.Equal(
            new[] { new Monogram('M'), new Monogram('O') },
            StickerWidenings.SignAll(new[] { new Monogram('O'), new Monogram('M') }));
        Assert.Equal(
            "Mylo, Oreo",
            StickerWidenings.SignedBy(new Dictionary<Monogram, string>
            {
                [new Monogram('O')] = "Oreo",
                [new Monogram('M')] = "Mylo",
            }));
    }

    [Fact]
    public void NullableCharUnderlying_OrdinaryPositions()
    {
        Assert.Equal(new MaybeMonogram(null), StickerWidenings.Unsigned());
        Assert.Equal('?', StickerWidenings.MonogramOr(new MaybeMonogram(null)));
        Assert.Equal('O', StickerWidenings.MonogramOr(new MaybeMonogram('O')));
        Assert.Equal(new MaybeMonogram(null), StickerWidenings.MaybeUnsigned(true));
        Assert.Null(StickerWidenings.MaybeUnsigned(false));
        Assert.Equal("no monogram", StickerWidenings.UnsignedState(null));
        Assert.Equal("unsigned", StickerWidenings.UnsignedState(new MaybeMonogram(null)));
        Assert.Equal("M", StickerWidenings.UnsignedState(new MaybeMonogram('M')));
        Assert.Equal(
            new[] { new MaybeMonogram('O'), new MaybeMonogram(null) },
            StickerWidenings.MonogramRoll(new[] { new MaybeMonogram('O'), new MaybeMonogram(null) }));
    }

    [Fact]
    public void ClosedCharSticker_OrdinaryPositions()
    {
        Assert.Equal(new Sticker<char>('M'), StickerWidenings.CharSticker());
        Assert.Equal('O', StickerWidenings.CharOf(new Sticker<char>('O')));
        Assert.Equal(
            new[] { new Sticker<char>('O'), new Sticker<char>('M') },
            StickerWidenings.CharStickers());
    }

    // --- Sealed underlyings at ordinary positions --------------------------------------------------

    [Fact]
    public void IneligibleSealedInterfaceUnderlying_OrdinaryPositions()
    {
        Clue clue = StickerWidenings.ClueAt("aa:bb");
        using (IConnectableDevice device = clue.Device)
        {
            Assert.Equal("aa:bb", device.Address);
            Assert.IsType<NearbyDevice>(device);
        }

        using var nearby = new NearbyDevice("attic", "cc:dd");
        Assert.Equal("cc:dd", StickerWidenings.ClueAddress(new Clue(nearby)));

        IReadOnlyList<Clue> clues = StickerWidenings.Clues();
        Assert.Equal(2, clues.Count);
        Assert.IsType<NearbyDevice>(clues[0].Device);
        Assert.IsType<SavedDevice>(clues[1].Device);
    }

    [Fact]
    public void EligibleSealedClassUnderlying_OrdinaryPositions()
    {
        using var nearby = new NearbyDevice("desk", "aa:bb");
        Dossier dossier = StickerWidenings.FileDossier(nearby);

        Assert.IsType<NearbyDevice>(dossier.Evidence);
        Assert.Equal("NearbyDevice", StickerWidenings.DossierKind(dossier));
    }

    // --- Erased positions: Box<V> ------------------------------------------------------------------

    [Fact]
    public void CharUnderlying_InsideABox()
    {
        using Box<Monogram> boxed = StickerWidenings.BoxedMonogram();
        Assert.Equal(new Monogram('O'), boxed.Value);

        using var built = new Box<Monogram>(new Monogram('M'));
        Assert.Equal('M', StickerWidenings.MonogramInBox(built));

        // A `Char` underlying is a VALUE one, so `init` runs when C# builds the struct, as it does
        // for every scalar value class; a blank monogram never reaches the box.
        Assert.ThrowsAny<ArgumentException>(() => new Box<Monogram>(new Monogram(' ')));
    }

    [Fact]
    public void NullableCharUnderlying_InsideABox_NullAndUnsignedStayDistinct()
    {
        using Box<MaybeMonogram?> present = StickerWidenings.BoxedMaybeMonogram(true);
        using Box<MaybeMonogram?> absent = StickerWidenings.BoxedMaybeMonogram(false);
        Assert.Equal(new MaybeMonogram(null), present.Value);
        Assert.Null(absent.Value);

        using var unsigned = new Box<MaybeMonogram?>(new MaybeMonogram(null));
        using var none = new Box<MaybeMonogram?>(null);
        using var oreo = new Box<MaybeMonogram?>(new MaybeMonogram('O'));
        Assert.Equal("unsigned", StickerWidenings.MaybeMonogramInBox(unsigned));
        Assert.Equal("no monogram", StickerWidenings.MaybeMonogramInBox(none));
        Assert.Equal("O", StickerWidenings.MaybeMonogramInBox(oreo));
    }

    [Fact]
    public void ClosedCharSticker_InsideABox()
    {
        using Box<Sticker<char>> boxed = StickerWidenings.BoxedCharSticker();

        Assert.Equal(new Sticker<char>('M'), boxed.Value);
    }

    [Fact]
    public void IneligibleSealedInterfaceUnderlying_InsideABox()
    {
        using Box<Clue> boxed = StickerWidenings.BoxedClue("ee:ff");
        using (IConnectableDevice device = boxed.Value.Device)
        {
            Assert.IsType<NearbyDevice>(device);
            Assert.Equal("ee:ff", device.Address);
        }

        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var built = new Box<Clue>(new Clue(nearby));
        Assert.Equal("aa:bb", StickerWidenings.ClueInBox(built));
    }

    [Fact]
    public void EligibleSealedClassUnderlying_InsideABox()
    {
        using Box<Dossier> boxed = StickerWidenings.BoxedDossier();
        Assert.IsType<SavedDevice>(boxed.Value.Evidence);

        using var nearby = new NearbyDevice("desk", "aa:bb");
        using var built = new Box<Dossier>(new Dossier(nearby));
        Assert.Equal("NearbyDevice", StickerWidenings.DossierInBox(built));
    }

    [Fact]
    public void NullablePrimitiveUnderlying_InsideABox()
    {
        using Box<TreatCount> uncounted = StickerWidenings.BoxedTreats(null);
        using Box<TreatCount> counted = StickerWidenings.BoxedTreats(4);
        Assert.Equal(new TreatCount(null), uncounted.Value);
        Assert.Equal(new TreatCount(4), counted.Value);

        using var built = new Box<TreatCount>(new TreatCount(2));
        using var blank = new Box<TreatCount>(new TreatCount(null));
        Assert.Equal("2", StickerWidenings.TreatsInBox(built));
        Assert.Equal("uncounted", StickerWidenings.TreatsInBox(blank));
    }

    [Fact]
    public void NullablePrimitiveUnderlying_InsideABox_NullAndUncountedStayDistinct()
    {
        using Box<TreatCount?> present = StickerWidenings.BoxedTreatsOrNone(true);
        using Box<TreatCount?> absent = StickerWidenings.BoxedTreatsOrNone(false);
        Assert.Equal(new TreatCount(null), present.Value);
        Assert.Null(absent.Value);

        using var uncounted = new Box<TreatCount?>(new TreatCount(null));
        using var none = new Box<TreatCount?>(null);
        Assert.Equal("uncounted", StickerWidenings.TreatsOrNoneInBox(uncounted));
        Assert.Equal("no count", StickerWidenings.TreatsOrNoneInBox(none));
    }

    // --- Erased positions: Flow elements -----------------------------------------------------------

    [Fact]
    public async Task CharUnderlying_FlowElement()
    {
        using var courier = new WideningCourier("Oreo's desk");
        var letters = new List<Monogram>();
        await foreach (Monogram monogram in courier.Monograms())
            letters.Add(monogram);

        Assert.Equal(new[] { new Monogram('O'), new Monogram('M') }, letters);
    }

    [Fact]
    public async Task NullableCharUnderlying_FlowElement_NullAndUnsignedStayDistinct()
    {
        using var courier = new WideningCourier("Oreo's desk");
        var letters = new List<MaybeMonogram?>();
        await foreach (MaybeMonogram? monogram in courier.MaybeMonograms())
            letters.Add(monogram);

        Assert.Equal(new MaybeMonogram?[] { new MaybeMonogram('O'), new MaybeMonogram(null), null }, letters);
    }

    [Fact]
    public async Task ClosedCharSticker_FlowElement()
    {
        using var courier = new WideningCourier("Oreo's desk");
        var stickers = new List<Sticker<char>>();
        await foreach (Sticker<char> sticker in courier.CharTrail())
            stickers.Add(sticker);

        Assert.Equal(new[] { new Sticker<char>('O'), new Sticker<char>('M') }, stickers);
    }

    [Fact]
    public async Task IneligibleSealedInterfaceUnderlying_FlowElement()
    {
        using var courier = new WideningCourier("Oreo's desk");
        var kinds = new List<string>();
        await foreach (Clue clue in courier.CluesFlow())
        {
            using IConnectableDevice device = clue.Device;
            kinds.Add($"{device.GetType().Name} {device.Address}");
        }

        Assert.Equal("NearbyDevice aa:bb", kinds[0]);
        Assert.StartsWith("SavedDevice", kinds[1]);
    }

    [Fact]
    public async Task EligibleSealedClassUnderlying_FlowElement()
    {
        using var courier = new WideningCourier("Oreo's desk");
        var kinds = new List<Type>();
        await foreach (Dossier dossier in courier.Dossiers())
            kinds.Add(dossier.Evidence.GetType());

        Assert.Equal(new[] { typeof(SavedDevice) }, kinds);
    }

    [Fact]
    public async Task NullablePrimitiveUnderlying_FlowElement_NullAndUncountedStayDistinct()
    {
        using var courier = new WideningCourier("Oreo's desk");
        var counts = new List<TreatCount?>();
        await foreach (TreatCount? count in courier.TreatLog())
            counts.Add(count);

        Assert.Equal(new TreatCount?[] { new TreatCount(3), new TreatCount(null), null }, counts);
    }

    // --- Erased positions: MutableStateFlow writes --------------------------------------------------

    [Fact]
    public void NullablePrimitiveUnderlying_MutableStateFlowWrite()
    {
        using var courier = new WideningCourier("Oreo's desk");

        Assert.Equal(new TreatCount(null), courier.TreatBoard.Value);
        courier.TreatBoard.Value = new TreatCount(5);
        Assert.Equal(new TreatCount(5), courier.TreatBoard.Value);
        courier.TreatBoard.Value = new TreatCount(null);
        Assert.Equal(new TreatCount(null), courier.TreatBoard.Value);
    }

    [Fact]
    public void CharUnderlying_MutableStateFlowWrite_AndInitRunsOnTheRewrap()
    {
        using var courier = new WideningCourier("Oreo's desk");

        Assert.Equal(new Monogram('O'), courier.MonogramBoard.Value);
        courier.MonogramBoard.Value = new Monogram('M');
        Assert.Equal(new Monogram('M'), courier.MonogramBoard.Value);
        Assert.ThrowsAny<ArgumentException>(() => courier.MonogramBoard.Value = new Monogram(' '));
        Assert.Equal(new Monogram('M'), courier.MonogramBoard.Value);
    }

    [Fact]
    public void IneligibleSealedInterfaceUnderlying_MutableStateFlowWrite()
    {
        using var courier = new WideningCourier("Oreo's desk");
        Assert.IsType<SavedDevice>(courier.ClueBoard.Value.Device);

        using var nearby = new NearbyDevice("desk", "aa:bb");
        courier.ClueBoard.Value = new Clue(nearby);
        using IConnectableDevice device = courier.ClueBoard.Value.Device;
        Assert.Equal("aa:bb", device.Address);
    }

    // --- Erased positions: suspend results ---------------------------------------------------------

    [Fact]
    public async Task CharUnderlyings_SuspendResults()
    {
        using var courier = new WideningCourier("Oreo's desk");

        Assert.Equal(new Monogram('M'), await courier.MonogramLaterAsync());
        Assert.Equal(new MaybeMonogram(null), await courier.MaybeMonogramLaterAsync(true));
        Assert.Null(await courier.MaybeMonogramLaterAsync(false));
        Assert.Equal(new Sticker<char>('O'), await courier.CharLaterAsync());
    }

    [Fact]
    public async Task SealedUnderlyings_SuspendResults()
    {
        using var courier = new WideningCourier("Oreo's desk");

        Clue clue = await courier.ClueLaterAsync("aa:bb");
        using (IConnectableDevice device = clue.Device)
            Assert.Equal("aa:bb", device.Address);
        Dossier dossier = await courier.DossierLaterAsync();
        Assert.IsType<SavedDevice>(dossier.Evidence);
    }

    [Fact]
    public async Task NullablePrimitiveUnderlying_SuspendResults()
    {
        using var courier = new WideningCourier("Oreo's desk");

        Assert.Equal(new TreatCount(null), await courier.TreatsLaterAsync(true));
        Assert.Null(await courier.TreatsLaterAsync(false));
        Assert.Equal(new TreatCount(6), await courier.CountLaterAsync(6));
    }
}

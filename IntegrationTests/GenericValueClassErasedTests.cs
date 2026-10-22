using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Stickers;

namespace IntegrationTests;

/// <summary>
/// ADR-228: a generic value class (<c>Sticker&lt;T&gt;</c>, the phantom <c>Decal&lt;T&gt;</c>) and a
/// non-generic value class over a nullable underlying (<c>Nick(val name: String?)</c>) cross at
/// every erased position: inside a generic class (<c>Box&lt;Sticker&lt;int&gt;&gt;</c>), as a lambda
/// payload and result, as a <c>Flow</c>/<c>StateFlow</c> element and as an awaited <c>suspend</c>
/// result or parameter, in both directions, for closed instantiations and a member's own
/// <c>T</c>. Kotlin boxes every instantiation as one runtime class, so one box/unbox pair per value
/// class serves them all, and a <c>null</c> stays distinct from <c>Sticker(null)</c>.
///
/// Rows whose closed struct type (<c>Sticker&lt;long&gt;</c>, <c>Sticker&lt;short&gt;</c>,
/// <c>Sticker&lt;double&gt;</c>, <c>Sticker&lt;float&gt;</c>, <c>Sticker&lt;bool&gt;</c>) appears
/// nowhere else read a Kotlin-made value before C# constructs one, which is what proves the
/// per-instantiation registration comes from the generated call site and not from the struct's
/// static constructor. Keep each of those type arguments in its one row.
///
/// The Kotlin fixture is <c>test-library/.../test/stickers/StickerSwaps.kt</c>, and the
/// <c>Flow</c> members declared on the struct itself are in <c>Stickers.kt</c>.
///
/// Oreo (black, white in the middle) trades stickers. Mylo (brown and creamy) trades them back.
/// </summary>
public class GenericValueClassErasedTests
{
    // --- inside a generic class ---------------------------------------------------------------

    [Fact]
    public void ClosedInstantiation_InsideABox_ReadsBack()
    {
        using Box<Sticker<int>> box = StickerSwaps.Boxed();

        Assert.Equal(new Sticker<int>(1), box.Value);
    }

    [Fact]
    public void ClosedInstantiation_InsideABox_ReadsBack_BeforeCSharpEverBuiltOne()
    {
        using Box<Sticker<long>> box = StickerSwaps.BoxedLong();

        Sticker<long> read = box.Value;

        Assert.Equal(7L, read.Value);
    }

    [Fact]
    public void CSharpBuiltBox_CarriesTheStruct_IntoKotlin()
    {
        using var box = new Box<Sticker<int>>(new Sticker<int>(4));

        Assert.Equal(4, StickerSwaps.PeelBox(box));
        Assert.Equal(new Sticker<int>(4), box.Value);
    }

    [Fact]
    public void CSharpBuiltBox_RunsInitInKotlin_OnTheRewrap()
    {
        // Mylo peeled this one already: the box export re-wraps -1 and Sticker's init refuses it.
        Assert.ThrowsAny<ArgumentException>(() => new Box<Sticker<int>>(new Sticker<int>(-1)));
    }

    [Fact]
    public void NullAndStickerOfNull_StayDistinct_InsideABox()
    {
        using Box<Sticker<string?>?> blank = StickerSwaps.BoxedBlank();
        using Box<Sticker<string?>?> none = StickerSwaps.BoxedNone();

        Assert.Equal(new Sticker<string?>(null), blank.Value);
        Assert.Null(none.Value);
        Assert.Equal("blank sticker", StickerSwaps.Blankness(blank));
        Assert.Equal("no sticker", StickerSwaps.Blankness(none));

        using var built = new Box<Sticker<string?>?>(new Sticker<string?>(null));
        Assert.Equal("blank sticker", StickerSwaps.Blankness(built));
        using var oreo = new Box<Sticker<string?>?>(new Sticker<string?>("Oreo"));
        Assert.Equal("Oreo", StickerSwaps.Blankness(oreo));
    }

    [Fact]
    public void OwnTypeParameter_InsideABox_RoundTripsPerInstantiation()
    {
        using Box<Sticker<int>> treats = StickerSwaps.BoxedOf(3);
        using Box<Sticker<string>> names = StickerSwaps.BoxedOf("Oreo");

        Assert.Equal(3, treats.Value.Value);
        Assert.Equal("Oreo", names.Value.Value);
        Assert.Equal(3, StickerSwaps.UnboxOf(treats));
        Assert.Equal("Mylo", StickerSwaps.UnboxOf(new Box<Sticker<string>>(new Sticker<string>("Mylo"))));
    }

    [Fact]
    public void ObjectHandleInstantiation_InsideABox_ReadsAFreshCat()
    {
        using Box<Sticker<Cat>> box = StickerSwaps.BoxedCat("Mylo");
        using Cat mylo = box.Value.Value;

        Assert.Equal("Mylo", mylo.Name);
    }

    [Fact]
    public void PhantomInstantiation_InsideABox_RoundTrips()
    {
        using Box<Decal<Cat>> decal = StickerSwaps.BoxedDecal();

        Assert.Equal(9L, decal.Value.Serial);
        Assert.Equal(9L, StickerSwaps.DecalSerial(decal));
        using var built = new Box<Decal<Cat>>(new Decal<Cat>(11L));
        Assert.Equal(11L, StickerSwaps.DecalSerial(built));
    }

    [Fact]
    public void NullableUnderlying_InsideABox_KeepsANullName()
    {
        using Box<Nick> nameless = StickerSwaps.NickBox(null);
        using Box<Nick> oreo = StickerSwaps.NickBox("Oreo");

        Assert.Null(nameless.Value.Name);
        Assert.Equal("Oreo", oreo.Value.Name);
    }

    [Fact]
    public void NullAndNickOfNull_StayDistinct_InsideABox()
    {
        using Box<Nick?> present = StickerSwaps.NickBoxOrNone(true);
        using Box<Nick?> absent = StickerSwaps.NickBoxOrNone(false);

        Assert.Equal(new Nick(null), present.Value);
        Assert.Null(absent.Value);
        Assert.Equal("nameless", StickerSwaps.NameOn(present));
        Assert.Equal("no nick", StickerSwaps.NameOn(absent));
        using var built = new Box<Nick?>(new Nick(null));
        Assert.Equal("nameless", StickerSwaps.NameOn(built));
    }

    // --- lambdas ------------------------------------------------------------------------------

    [Fact]
    public void KotlinLambda_PayloadAndResult_CrossBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");
        using KotlinFunc<Sticker<string>, Sticker<int>> onSticker = courier.OnSticker;

        Assert.Equal(new Sticker<int>(4), onSticker.Invoke(new Sticker<string>("Oreo")));
    }

    [Fact]
    public void KotlinLambda_Result_ReadsBack_BeforeCSharpEverBuiltOne()
    {
        using var courier = new StickerCourier("Mylo");
        using KotlinFunc<Sticker<short>> nextShort = courier.NextShort;

        Sticker<short> read = nextShort.Invoke();

        Assert.Equal((short)4, read.Value);
    }

    [Fact]
    public void KotlinLambda_NullAndStickerOfNull_StayDistinct()
    {
        using var courier = new StickerCourier("Oreo's desk");
        using KotlinFunc<Sticker<string?>?, string> onBlank = courier.OnBlank;

        Assert.Equal("no sticker", onBlank.Invoke(null));
        Assert.Equal("blank sticker", onBlank.Invoke(new Sticker<string?>(null)));
        Assert.Equal("Mylo", onBlank.Invoke(new Sticker<string?>("Mylo")));
    }

    [Fact]
    public void KotlinLambda_NullableUnderlying_PayloadAndResult()
    {
        using var courier = new StickerCourier("Oreo's desk");
        using KotlinFunc<Nick?, string> onNick = courier.OnNick;
        using KotlinFunc<Nick> nextNick = courier.NextNick;

        Assert.Equal("no nick", onNick.Invoke(null));
        Assert.Equal("nameless", onNick.Invoke(new Nick(null)));
        Assert.Equal("Oreo", onNick.Invoke(new Nick("Oreo")));
        Assert.Equal(new Nick(null), nextNick.Invoke());
    }

    [Fact]
    public async Task KotlinSuspendLambda_PayloadAndResult_CrossBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");
        using KotlinSuspendFunc<Sticker<int>, Sticker<int>> refileLater = courier.RefileLater;

        Assert.Equal(new Sticker<int>(6), await refileLater.InvokeAsync(new Sticker<int>(5)));
    }

    [Fact]
    public void CSharpLambda_KotlinCallsIt_PayloadOutAndResultIn()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Assert.Equal(20, courier.Relay(new Sticker<int>(2), sticker => new Sticker<int>(sticker.Value * 10)));
        Assert.Equal("nameless", courier.RelayNick(nick => nick));
        Assert.Equal("no nick", courier.RelayNick(_ => null));
        Assert.Equal("Mylo", courier.RelayNick(_ => new Nick("Mylo")));
    }

    [Fact]
    public void CSharpLambda_ResultRunsInitInKotlin_OnTheRewrap()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Assert.ThrowsAny<ArgumentException>(() => courier.Relay(new Sticker<int>(2), _ => new Sticker<int>(-1)));
    }

    // --- flows --------------------------------------------------------------------------------

    [Fact]
    public async Task FlowElement_ObjectHandleInstantiation_CrossesBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");
        var names = new List<string>();

        await foreach (Sticker<Cat> sticker in courier.Stream())
        {
            using Cat cat = sticker.Value;
            names.Add(cat.Name);
        }

        Assert.Equal(new[] { "Oreo", "Mylo" }, names);
    }

    [Fact]
    public async Task FlowElement_ReadsBack_BeforeCSharpEverBuiltOne()
    {
        using var courier = new StickerCourier("Oreo's desk");
        var values = new List<double>();

        await foreach (Sticker<double> sticker in courier.Doubles())
            values.Add(sticker.Value);

        Assert.Equal(new[] { 1.5, 2.5 }, values);
    }

    [Fact]
    public async Task FlowElement_NullAndNickOfNull_StayDistinct()
    {
        using var courier = new StickerCourier("Oreo's desk");
        var nicks = new List<Nick?>();

        await foreach (Nick? nick in courier.Nicks())
            nicks.Add(nick);

        Assert.Equal(new Nick?[] { new Nick("Oreo"), new Nick(null), null }, nicks);
    }

    [Fact]
    public async Task FlowElement_OwnTypeParameter_CrossesBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");
        var stickers = new List<Sticker<string>>();

        await foreach (Sticker<string> sticker in courier.StreamOf("Mylo"))
            stickers.Add(sticker);

        Assert.Equal(new[] { new Sticker<string>("Mylo"), new Sticker<string>("Mylo") }, stickers);
    }

    [Fact]
    public void StateFlowElement_ReadsBack_BeforeCSharpEverBuiltOne()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Sticker<float> read = courier.Latest.Value;

        Assert.Equal(0.5f, read.Value);
    }

    [Fact]
    public void MutableStateFlowElement_WritesFromCSharp_AndInitRunsOnTheRewrap()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Assert.Equal(new Sticker<int>(2), courier.Board.Value);
        courier.Board.Value = new Sticker<int>(8);
        Assert.Equal(new Sticker<int>(8), courier.Board.Value);
        Assert.ThrowsAny<ArgumentException>(() => courier.Board.Value = new Sticker<int>(-1));
        Assert.Equal(new Sticker<int>(8), courier.Board.Value);
    }

    [Fact]
    public async Task FlowParameter_EachCSharpElementCrossesInBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Assert.Equal(6, await courier.TotalAsync(Drip(1, 2, 3)));
    }

    private static async IAsyncEnumerable<Sticker<int>> Drip(params int[] values)
    {
        foreach (int value in values)
        {
            await Task.Yield();
            yield return new Sticker<int>(value);
        }
    }

    // --- suspend ------------------------------------------------------------------------------

    [Fact]
    public async Task SuspendResult_ReadsBack_BeforeCSharpEverBuiltOne()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Sticker<bool> read = await courier.LaterAsync();

        Assert.True(read.Value);
    }

    [Fact]
    public async Task SuspendParameterAndResult_CrossBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Assert.Equal(new Sticker<int>(6), await courier.BumpLaterAsync(new Sticker<int>(5)));
    }

    [Fact]
    public async Task SuspendResult_NullAndNickOfNull_StayDistinct()
    {
        using var courier = new StickerCourier("Oreo's desk");

        Assert.Equal(new Nick(null), await courier.NickLaterAsync(true));
        Assert.Null(await courier.NickLaterAsync(false));
    }

    [Fact]
    public async Task SuspendResult_OwnTypeParameter_CrossesBoxed()
    {
        using var courier = new StickerCourier("Oreo's desk");
        using var oreo = new Cat("Oreo");

        Assert.Equal(new Sticker<string>("Mylo"), await courier.LaterOfAsync("Mylo"));
        Sticker<Cat> stuck = await courier.LaterOfAsync(oreo);
        using Cat back = stuck.Value;
        Assert.Equal("Oreo", back.Name);
    }

    // --- flow members declared on the generic value class --------------------------------------

    [Fact]
    public async Task FlowMember_OverTheClassOwnT_Binds()
    {
        var trail = new List<string>();

        await foreach (string value in new Sticker<string>("Oreo").Trail())
            trail.Add(value);

        Assert.Equal(new[] { "Oreo", "Oreo" }, trail);
    }

    [Fact]
    public async Task FlowMember_WhoseElementIsTheStruct_Binds()
    {
        var echoes = new List<Sticker<int>>();

        await foreach (Sticker<int> echo in new Sticker<int>(3).Echoes())
            echoes.Add(echo);

        Assert.Equal(new[] { new Sticker<int>(3), new Sticker<int>(3) }, echoes);
    }

    [Fact]
    public void StateFlowProperty_OverTheClassOwnT_Binds()
    {
        Assert.Equal("Mylo", new Sticker<string>("Mylo").Glow.Value);
    }

    [Fact]
    public async Task FlowMember_OnAPhantom_Binds()
    {
        var serials = new List<long>();

        await foreach (long serial in new Decal<Cat>(7L).Serials())
            serials.Add(serial);

        Assert.Equal(new[] { 7L, 8L }, serials);
    }

    [Fact]
    public async Task FlowMember_OverAnEnumSelfBoundT_ReadsTheReceiverAsT()
    {
        var naps = new List<Mood>();

        await foreach (Mood mood in new Doze<Mood>(Mood.Sleepy).Naps())
            naps.Add(mood);

        Assert.Equal(new[] { Mood.Sleepy, Mood.Sleepy }, naps);
    }

    [Fact]
    public void SharedFlowProperty_OverTheClassOwnT_ReadsItsReplayCache()
    {
        Assert.Equal(new[] { "Oreo" }, new Sticker<string>("Oreo").Chirps.ReplayCache);
    }

    [Fact]
    public void OwnTypeParameter_InsideABox_RegistersAnInstantiationNothingElseConstructs()
    {
        // `Sticker<ulong>` appears in no other signature, so only the call site's registration
        // makes the Kotlin-made value readable.
        using Box<Sticker<ulong>> treats = StickerSwaps.BoxedOf(7UL);

        Assert.Equal(7UL, treats.Value.Value);
    }
}

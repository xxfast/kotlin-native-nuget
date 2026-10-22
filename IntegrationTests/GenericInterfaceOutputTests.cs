using TestLibrary;
using TestLibrary.Playroom;
using TestLibrary.Cat;
using TestLibrary.Membergeneric;

namespace IntegrationTests;

/// <summary>
/// ADR-224: a Kotlin generic interface reference (<c>Keepsake&lt;String&gt;</c>) binds at every
/// position as <c>IKeepsake&lt;string&gt;</c>, not only at ADR-222's input positions. Two sides per
/// position:
/// <list type="bullet">
///   <item>a C# implementation Kotlin stored comes back as the same C# object (<c>Assert.Same</c>);</item>
///   <item>a Kotlin implementation comes back as the generic backing wrapper <c>Keepsake&lt;T&gt;</c>,
///   whose getter and setter dispatch over the erased receiver.</item>
/// </list>
/// Open instantiations use type arguments no other test names (<c>double</c>, <c>float</c>,
/// <c>bool</c>, <c>short</c>, <c>byte</c>, <c>long</c>), because the open factory slots they fill
/// are process-global: a row that another test pre-filled would pass for the wrong reason.
///
/// Oreo hides his treasures in the trove; Mylo keeps knocking them off the mantel.
/// </summary>
public class GenericInterfaceOutputTests
{
    private sealed class Diary(string item = "Oreo") : IKeepsake<string>
    {
        public string Item { get; set; } = item;
        public void Dispose() { }
    }

    // A consumer-chosen instantiation: no Kotlin signature names `Keepsake<Short>`, so only the
    // erased mint can hand it to Kotlin.
    private sealed class Tally(short item) : IKeepsake<short>
    {
        public short Item { get; set; } = item;
        public void Dispose() { }
    }

    // A nested implementation: Kotlin reads `outer.item.item`, so the inner Diary crosses back to
    // Kotlin as a getter-slot result.
    private sealed class Pouch(IKeepsake<string> inner) : IKeepsake<IKeepsake<string>>
    {
        public IKeepsake<string> Item { get; set; } = inner;
        public void Dispose() { }
    }

    private static async Task<List<IKeepsake<string>>> Collect(KotlinFlow<IKeepsake<string>> flow)
    {
        var seen = new List<IKeepsake<string>>();
        await foreach (IKeepsake<string> keepsake in flow) seen.Add(keepsake);
        return seen;
    }

    // --- The C#-implemented side: identity at every output. -----------------------------------

    [Fact]
    public void CSharpKeepsake_StoredInAClassProperty_ComesBackAsTheSameObject()
    {
        var diary = new Diary();
        using var trove = new Trove();

        trove.Kept = diary;

        Assert.Same(diary, trove.Kept);
        Assert.Same(diary, trove.Keep());
    }

    [Fact]
    public void CSharpKeepsake_NullableProperty_NullThenTheSameObject()
    {
        var diary = new Diary("Mylo");
        using var trove = new Trove();

        Assert.Null(trove.Spare);
        Assert.Null(trove.KeepOrNull());

        trove.Spare = diary;

        Assert.Same(diary, trove.Spare);
        Assert.Same(diary, trove.KeepOrNull());
    }

    [Fact]
    public void CSharpKeepsake_CollectionComponents_ComeBackAsTheSameObjects()
    {
        var oreo = new Diary("Oreo");
        var mylo = new Diary("Mylo");
        using var trove = new Trove();

        Assert.Equal(2, trove.StockAll(new List<IKeepsake<string>> { oreo, mylo }));

        IReadOnlyList<IKeepsake<string>> all = trove.All();
        Assert.Equal(2, all.Count);
        Assert.Same(oreo, all[0]);
        Assert.Same(mylo, all[1]);
        Assert.Same(oreo, trove.ByName()["kept"]);
    }

    [Fact]
    public async Task CSharpKeepsake_SuspendResult_IsTheSameObject()
    {
        var diary = new Diary();
        using var trove = new Trove();
        trove.Kept = diary;

        Assert.Same(diary, await trove.LaterAsync());
    }

    [Fact]
    public async Task CSharpKeepsake_FlowAndStateFlowElements_AreTheSameObject()
    {
        var diary = new Diary();
        using var trove = new Trove();
        trove.Kept = diary;

        IKeepsake<string> only = Assert.Single(await Collect(trove.Updates()));
        Assert.Same(diary, only);

        using KotlinStateFlow<IKeepsake<string>> current = trove.Current();
        Assert.Same(diary, current.Value);
    }

    [Fact]
    public void CSharpKeepsake_StaticProperties_ComeBackAsTheSameObject()
    {
        var showcase = new Diary("sunbeam");
        var ornament = new Diary("yarn");

        Trove.Showcase = showcase;
        Mantelpiece.Ornament = ornament;

        Assert.Same(showcase, Trove.Showcase);
        Assert.Same(ornament, Mantelpiece.Ornament);
    }

    [Fact]
    public void CSharpKeepsake_ThroughAClosedBox_IsTheSameObject()
    {
        var diary = new Diary();
        using var box = new Box<IKeepsake<string>>(diary);

        using Box<IKeepsake<string>> back = KeepsakeTrove.Boxed(box);

        Assert.Same(diary, back.Value);
    }

    [Fact]
    public void CSharpKeepsake_ConsumerChosenInstantiation_RoundTripsThroughAnOpenBox()
    {
        // `IKeepsake<short>` is named by no Kotlin signature: `Wrap<T>` inside the Box constructor
        // has only the erased mint to reach Kotlin with.
        var tally = new Tally(7);
        using var box = new Box<IKeepsake<short>>(tally);

        using Box<IKeepsake<short>> back = KeepsakeTrove.BoxedOpen(box);

        Assert.Same(tally, back.Value);
    }

    [Fact]
    public void CSharpKeepsake_OpenCollection_ComesBackReversedAndTheSame()
    {
        var first = new Tally(1);
        var second = new Tally(2);

        IReadOnlyList<IKeepsake<short>> back = KeepsakeTrove.Stash(new List<IKeepsake<short>> { first, second });

        Assert.Same(second, back[0]);
        Assert.Same(first, back[1]);
    }

    [Fact]
    public void CSharpKeepsake_LambdaResult_IsTheSameObject()
    {
        var diary = new Diary("Mylo");
        string? seen = null;

        IKeepsake<string> back = KeepsakeTrove.Visit(payload =>
        {
            seen = payload.Item;
            return diary;
        });

        Assert.Equal("Oreo", seen);
        Assert.Same(diary, back);
        Assert.Null(KeepsakeTrove.VisitOrNull(() => null));
        Assert.Same(diary, KeepsakeTrove.VisitOrNull(() => diary));
    }

    [Fact]
    public void CSharpKeepsake_Nested_KotlinReadsTheInnerItem()
    {
        using var pouch = new Pouch(new Diary("Mylo"));

        Assert.Equal("Mylo", KeepsakeTrove.Unwrap(pouch));
    }

    [Fact]
    public void CSharpKeepsake_GenericClassMember_SwapsAndComesBackTheSame()
    {
        var first = new Tally(3);
        var second = new Tally(4);
        using var mantel = new Mantel<short>(0);

        using IKeepsake<short> original = mantel.Swap(first);
        Assert.Equal((short)0, original.Item);
        Assert.Same(first, mantel.Shelved);

        mantel.Shelved = second;
        Assert.Same(second, mantel.Swap(first));
    }

    // --- The Kotlin-implemented side: the generic backing wrapper. -----------------------------

    [Fact]
    public void KotlinKeepsake_ClassProperty_IsTheGenericBackingWrapper()
    {
        using var trove = new Trove();

        using IKeepsake<string> kept = trove.Kept;

        Assert.IsType<Keepsake<string>>(kept);
        Assert.Equal("Oreo", kept.Item);
        kept.Item = "Mylo";
        using IKeepsake<string> again = trove.Keep();
        Assert.Equal("Mylo", again.Item);
    }

    [Fact]
    public void KotlinKeepsake_OpenReturn_IsTheGenericBackingWrapper()
    {
        using IKeepsake<double> locket = KeepsakeTrove.LocketOf(4.5);

        Assert.IsType<Keepsake<double>>(locket);
        Assert.Equal(4.5, locket.Item);
        locket.Item += 1;
        Assert.Equal(5.5, locket.Item);
    }

    [Fact]
    public async Task KotlinKeepsake_OpenSuspendReturn_IsTheGenericBackingWrapper()
    {
        using IKeepsake<float> locket = await KeepsakeTrove.LocketLaterAsync(2.5f);

        Assert.IsType<Keepsake<float>>(locket);
        Assert.Equal(2.5f, locket.Item);
    }

    [Fact]
    public void KotlinKeepsake_TopLevelProperty_ReadsAndWritesTheClosedLongInstantiation()
    {
        using IKeepsake<long> last = KeepsakeTrove.LastKeepsake;

        Assert.IsType<Keepsake<long>>(last);
        last.Item = 42L;
        using IKeepsake<long> again = KeepsakeTrove.LastKeepsake;
        Assert.Equal(42L, again.Item);
    }

    [Fact]
    public void KotlinKeepsake_Nested_BothLayersAreBackingWrappers()
    {
        using IKeepsake<IKeepsake<string>> outer = KeepsakeTrove.Nested();

        using IKeepsake<string> inner = outer.Item;

        Assert.IsType<Keepsake<IKeepsake<string>>>(outer);
        Assert.IsType<Keepsake<string>>(inner);
        Assert.Equal("Mylo", inner.Item);
    }

    [Fact]
    public void KotlinKeepsake_GenericClassMember_ReadsTheClassesOwnT()
    {
        using var mantel = new Mantel<bool>(true);

        using IKeepsake<bool> kept = mantel.Keep();

        Assert.IsType<Keepsake<bool>>(kept);
        Assert.True(kept.Item);
    }

    [Fact]
    public void KotlinKeepsake_ConsumerConstructedLocket_ReadsBackThroughAnOpenBox()
    {
        // The implementer's own static constructor fills the `IKeepsake<byte>` slot, so a Box the
        // consumer filled with a Kotlin `Locket<byte>` materialises on the way back.
        using var locket = new Locket<byte>(9);
        using var box = new Box<IKeepsake<byte>>(locket);

        using Box<IKeepsake<byte>> back = KeepsakeTrove.BoxedOpen(box);
        using IKeepsake<byte> value = back.Value;

        Assert.Equal((byte)9, value.Item);
    }

    // --- Constrained generic interfaces at an erased input. --------------------------------------

    // `IPerch<T> where T : IFurry`: the interface restates its bridge's constraint, so its default
    // mint compiles and the C# perch crosses inside a list.
    private sealed class Windowsill(Tabby sitter) : IPerch<Tabby>
    {
        public Tabby Sitter { get; } = sitter;
        public void Dispose() { }
    }

    private sealed class Ribbon(int best) : IPodium<int>
    {
        public int Best { get; } = best;
        public void Dispose() { }
    }

    [Fact]
    public void CSharpPerch_ConstrainedInterface_CrossesInsideAList()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);

        string names = KeepsakeTrove.PerchNames(new List<IPerch<Tabby>> { new Windowsill(oreo), new Windowsill(mylo) });

        Assert.Equal("Oreo, Mylo", names);
    }

    [Fact]
    public void CSharpPodium_SelfBoundedInterface_CrossesInsideAList()
    {
        string crowned = KeepsakeTrove.CrownAll(new List<IPodium<int>> { new Ribbon(1), new Ribbon(2) });

        Assert.Equal("crowned 1, crowned 2", crowned);
    }

    private sealed class Counter(int item) : IKeepsake<int>
    {
        public int Item { get; set; } = item;
        public void Dispose() { }
    }

    // A C# `IKeepsake<int>` read back at an `IKeepsake<string>` position (Kotlin's unchecked cast)
    // fails at the token cast in `TryResolveCSharp`, as a catchable managed exception, and the
    // process carries on: the next crossing still works.
    [Fact]
    public void CSharpKeepsake_ReadBackAtTheWrongInstantiation_ThrowsInvalidCast()
    {
        var counter = new Counter(41);

        Assert.Throws<InvalidCastException>(() => KeepsakeTrove.Launder(counter));

        var diary = new Diary("Mylo");
        using var trove = new Trove();
        trove.Kept = diary;
        Assert.Same(diary, trove.Keep());
    }

    // --- Async members of a generic interface (the lifted skip). -------------------------------

    [Fact]
    public async Task KotlinHeirloom_SuspendFlowAndGetter_DispatchOverTheErasedReceiver()
    {
        await using IHeirloom<string> heirloom = KeepsakeTrove.Heirloom();

        Assert.Equal("Oreo's whisker", heirloom.Label);
        Assert.Equal("Oreo's whisker", await heirloom.PolishAsync());

        var glints = new List<string>();
        await foreach (string glint in heirloom.Glints()) glints.Add(glint);
        Assert.Equal(new[] { "Oreo's whisker", "Oreo's whisker" }, glints);
    }

    [Fact]
    public async Task KotlinHeirloom_OpenInstantiation_SuspendMemberReturnsTheLabel()
    {
        await using IHeirloom<int> heirloom = KeepsakeTrove.HeirloomOf(9);

        Assert.IsType<Heirloom<int>>(heirloom);
        Assert.Equal(9, await heirloom.PolishAsync());
    }
}

using TestLibrary.Cat;
using TestLibrary.Stickers;

namespace IntegrationTests;

/// <summary>
/// A generic value class over a collection of its own type parameter
/// (<c>value class Bundle&lt;T&gt;(val items: List&lt;T&gt;)</c>, and over <c>Set&lt;T&gt;</c>,
/// <c>Map&lt;String, T&gt;</c>, <c>List&lt;T&gt;?</c> and <c>List&lt;T?&gt;</c>) is one
/// <c>readonly record struct Bundle&lt;T&gt;(IReadOnlyList&lt;T&gt; Items)</c>, and binds at every
/// position: parameter, result, property, constructor, nullable outer, collection element, own
/// members and secondary constructors, a function's own <c>T</c>, and the erased positions (a
/// <c>Box</c>, a lambda, a <c>Flow</c> element, an awaited result, a <c>MutableStateFlow</c>
/// write). Kotlin's <c>init</c> runs when the struct crosses.
///
/// The Kotlin fixture is <c>test-library/.../test/stickers/Bundles.kt</c>.
///
/// Oreo (black, white in the middle) bundles his toys. Mylo (brown and creamy) never carries more
/// than three.
/// </summary>
public class GenericValueClassCollectionUnderlyingTests
{
    // --- ordinary positions -------------------------------------------------------------------

    [Fact]
    public void ClosedList_ParamAndResult_RoundTrip()
    {
        Bundle<int> packed = Bundles.Pack(new[] { 1, 2 });

        Assert.Equal(new[] { 1, 2 }, packed.Items);
        Assert.Equal(3, Bundles.Total(packed));
        Assert.Equal(new[] { "OREO", "MYLO" }, Bundles.ShoutNames(new Bundle<string>(new[] { "oreo", "mylo" })).Items);
    }

    [Fact]
    public void ClosedList_OfAnExportedClass_CrossesItsHandles()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 2);

        Assert.Equal("Oreo, Mylo", Bundles.CatNames(new Bundle<Cat>(new[] { oreo, mylo })));
    }

    [Fact]
    public void Init_RunsAtTheCrossing_BothWays()
    {
        Bundle<int> tooMany = new Bundle<int>(new[] { 1, 2, 3, 4 });

        Assert.Throws<KotlinArgumentException>(() => Bundles.Total(tooMany));
        Assert.Throws<KotlinArgumentException>(() => Bundles.Pack(new[] { 1, 2, 3, 4 }));
    }

    [Fact]
    public void Default_IsRefusedBeforeItCrosses()
    {
        Assert.ThrowsAny<ArgumentException>(() => Bundles.Total(default));
    }

    [Fact]
    public void SetAndMap_ParamAndResult_RoundTrip()
    {
        Pile<string> pile = Bundles.PileOf(new HashSet<string> { "mouse", "ball" });

        Assert.True(pile.Items.SetEquals(new[] { "ball", "mouse" }));
        Assert.Equal(2, Bundles.PileSize(pile));
        Ledger<int> ledger = Bundles.LedgerOf(new Dictionary<string, int> { ["Oreo"] = 2, ["Mylo"] = 1 });
        Assert.Equal(2, ledger.Entries["Oreo"]);
        Assert.Equal(3, Bundles.LedgerSum(ledger));
    }

    [Fact]
    public void NullableUnderlying_KeepsNoListApartFromAnEmptyOne()
    {
        Assert.Null(Bundles.MaybeBundle(false).Items);
        Assert.Equal(new[] { 1 }, Bundles.MaybeBundle(true).Items);
        Assert.Equal(-1, Bundles.MaybeBundleSize(new MaybeBundle<int>(null)));
        Assert.Equal(0, Bundles.MaybeBundleSize(new MaybeBundle<int>(Array.Empty<int>())));
    }

    [Fact]
    public void NullableOuter_OverANullableUnderlying_KeepsAbsentAndEmptyApart()
    {
        Assert.Null(Bundles.MaybeMaybe(0));
        MaybeBundle<int>? empty = Bundles.MaybeMaybe(1);
        Assert.NotNull(empty);
        Assert.Null(empty!.Value.Items);
        Assert.Equal(new[] { 2 }, Bundles.MaybeMaybe(2)!.Value.Items);
        Assert.Equal("absent", Bundles.MaybeMaybeState(null));
        Assert.Equal("empty", Bundles.MaybeMaybeState(new MaybeBundle<int>(null)));
        Assert.Equal("full", Bundles.MaybeMaybeState(new MaybeBundle<int>(new[] { 1 })));
    }

    [Fact]
    public void NullableOuter_OverANullableUnderlying_AsElementAndProperty_KeepsAbsentAndEmptyApart()
    {
        IReadOnlyList<MaybeBundle<int>?> maybes = Bundles.Maybes(new MaybeBundle<int>?[]
        {
            null,
            new MaybeBundle<int>(null),
            new MaybeBundle<int>(new[] { 3 }),
        });

        Assert.Null(maybes[0]);
        Assert.Null(maybes[1]!.Value.Items);
        Assert.Equal(new[] { 3 }, maybes[2]!.Value.Items);
        using var bundler = new Bundler(new Bundle<string>(new[] { "Oreo" }));
        Assert.Null(bundler.MaybeSpare);
        bundler.MaybeSpare = new MaybeBundle<int>(null);
        Assert.NotNull(bundler.MaybeSpare);
        Assert.Null(bundler.MaybeSpare!.Value.Items);
        bundler.MaybeSpare = null;
        Assert.Null(bundler.MaybeSpare);
    }

    [Fact]
    public void NullableElements_KeepTheirGaps()
    {
        Gappy<int?> gappy = Bundles.Gappy();

        Assert.Equal(new int?[] { 1, null, 3 }, gappy.Items);
        Assert.Equal(2, Bundles.Gaps(new Gappy<int?>(new int?[] { null, 4, null })));
    }

    [Fact]
    public void NullableOuter_RoundTrips()
    {
        Assert.Null(Bundles.MaybePack(false));
        Assert.Equal(new[] { 1, 2 }, Bundles.MaybePack(true)!.Value.Items);
        Assert.Equal(-1, Bundles.MaybeTotal(null));
        Assert.Equal(5, Bundles.MaybeTotal(new Bundle<int>(new[] { 5 })));
    }

    [Fact]
    public void CollectionElement_RoundTrips()
    {
        IReadOnlyList<Bundle<int>> repacked = Bundles.Repack(new[]
        {
            new Bundle<int>(new[] { 1, 2 }),
            new Bundle<int>(new[] { 3 }),
        });

        Assert.Equal(new[] { 2, 1 }, repacked[0].Items);
        Assert.Equal(new[] { 3 }, repacked[1].Items);
        Assert.Throws<KotlinArgumentException>(() => Bundles.Repack(new[] { new Bundle<int>(new[] { 1, 2, 3, 4 }) }));
    }

    [Fact]
    public void OwnTypeParameter_RoundTrips()
    {
        Bundle<string> wrapped = Bundles.Wrap<string>(new[] { "mouse" });

        Assert.Equal(new[] { "mouse" }, wrapped.Items);
        Assert.Equal(new[] { "mouse" }, Bundles.Unwrap<string>(wrapped));
        Assert.Equal(new long[] { 7, 8 }, Bundles.Unwrap(new Bundle<long>(new long[] { 7, 8 })));
        Assert.Throws<KotlinArgumentException>(() => Bundles.Wrap<string>(new[] { "a", "b", "c", "d" }));
    }

    [Fact]
    public void PropertyAndConstructor_ReadAndWrite()
    {
        using var bundler = new Bundler(new Bundle<string>(new[] { "Oreo" }));

        Assert.Equal(new[] { "Oreo" }, bundler.Current.Items);
        bundler.Current = new Bundle<string>(new[] { "Mylo" });
        Assert.Equal(new[] { "Mylo" }, bundler.Current.Items);
        Assert.Null(bundler.Spare);
        bundler.Spare = new Bundle<int>(new[] { 4 });
        Assert.Equal(new[] { 4 }, bundler.Spare!.Value.Items);
        Assert.Throws<KotlinArgumentException>(() => bundler.Current = new Bundle<string>(new[] { "a", "b", "c", "d" }));
    }

    [Fact]
    public void ContentEquality_ComparesTheItems()
    {
        Assert.Equal(new Bundle<int>(new[] { 1, 2 }), Bundles.Pack(new[] { 1, 2 }));
        Assert.NotEqual(new Bundle<int>(new[] { 2, 1 }), Bundles.Pack(new[] { 1, 2 }));
    }

    // --- own members and secondary constructors -------------------------------------------------

    [Fact]
    public void Members_ReadTheReceiver()
    {
        Bundle<string> toys = new Bundle<string>(new[] { "mouse", "ball" });

        Assert.Equal(2, toys.Count);
        Assert.Equal("mouse", toys.First());
        Assert.Equal(new[] { "mouse", "ball", "yarn" }, toys.Plus("yarn").Items);
        Assert.Throws<KotlinArgumentException>(() => toys.Plus("yarn").Plus("bell"));
        Assert.Throws<KotlinArgumentException>(() => new Bundle<int>(new[] { 1, 2, 3, 4 }).First());
    }

    [Fact]
    public void SecondaryConstructor_RunsInitAtConstruction()
    {
        Assert.Equal(new[] { "ball", "ball" }, new Bundle<string>("ball", 2).Items);
        Assert.Throws<KotlinArgumentException>(() => new Bundle<string>("ball", 4));
    }

    [Fact]
    public async Task SuspendAndFlowMembers_ReadTheReceiver()
    {
        Bundle<string> toys = new Bundle<string>(new[] { "mouse", "ball" });

        Assert.Equal(2, await toys.CountLaterAsync());
        var seen = new List<string>();
        await foreach (string toy in toys.Each())
        {
            seen.Add(toy);
        }
        Assert.Equal(new[] { "mouse", "ball" }, seen);
    }

    // --- erased positions ---------------------------------------------------------------------

    [Fact]
    public void Box_RoundTrips()
    {
        using Box<Bundle<int>> boxed = Bundles.BoxedBundle();

        Assert.Equal(new[] { 1, 2 }, boxed.Value.Items);
        Assert.Equal(3, Bundles.BoxedTotal(boxed));
        using var made = new Box<Bundle<int>>(new Bundle<int>(new[] { 5, 6 }));
        Assert.Equal(11, Bundles.BoxedTotal(made));
        Assert.ThrowsAny<ArgumentException>(() => new Box<Bundle<int>>(new Bundle<int>(new[] { 1, 2, 3, 4 })));
    }

    [Fact]
    public void Lambda_PayloadAndResult_RoundTrip()
    {
        Bundle<int> mapped = Bundles.MapBundle(new Bundle<int>(new[] { 1 }), bundle => new Bundle<int>(bundle.Items.Append(2).ToArray()));

        Assert.Equal(new[] { 1, 2 }, mapped.Items);
    }

    [Fact]
    public async Task FlowElement_Reads()
    {
        var seen = new List<IReadOnlyList<int>>();
        await foreach (Bundle<int> bundle in Bundles.BundleStream())
        {
            seen.Add(bundle.Items);
        }

        Assert.Equal(2, seen.Count);
        Assert.Equal(new[] { 2, 3 }, seen[1]);
    }

    [Fact]
    public async Task Suspend_ResultAndParameter_RoundTrip()
    {
        Assert.Equal(new[] { 4 }, (await Bundles.BundleLaterAsync()).Items);
        Assert.Equal(6, await Bundles.TotalLaterAsync(new Bundle<int>(new[] { 1, 2, 3 })));
        Assert.Throws<KotlinArgumentException>(() => { _ = Bundles.TotalLaterAsync(new Bundle<int>(new[] { 1, 2, 3, 4 })); });
    }

    [Fact]
    public void MutableStateFlow_CSharpWritesKotlinSees()
    {
        using var bundler = new Bundler(new Bundle<string>(new[] { "Oreo" }));

        Assert.Equal(new[] { 0 }, bundler.Board.Value.Items);
        bundler.Board.Value = new Bundle<int>(new[] { 2, 3 });
        Assert.Equal(5, bundler.BoardTotal());
        Assert.ThrowsAny<ArgumentException>(() => bundler.Board.Value = new Bundle<int>(new[] { 1, 2, 3, 4 }));
        Assert.Equal(5, bundler.BoardTotal());
    }
}

using TestLibrary;
using TestLibrary.Purrclock;

namespace IntegrationTests;

/// <summary>
/// A <c>MutableStateFlow</c> or <c>MutableSharedFlow</c> of a nullable collection or
/// <c>ByteArray</c> element binds settable on a class, an <c>object</c> and the top level, and a
/// plain <c>Flow</c> of one yields null as well as a collection. Null crosses as a null handle both
/// ways: C# reads <c>null</c> rather than an empty collection, and Kotlin sees a C# <c>null</c>
/// write as null rather than an empty pile.
/// <para>
/// Red before: each collection member was a named skip (<c>SKIPPED_UNSUPPORTED_PROPERTY</c>, "a
/// Flow or StateFlow element can be a List/Set/Map, but not the generic type List&lt;Int&gt;?"), so
/// none of them existed in C# (CS1061 / CS0117), and an awaited one was read-only.
/// </para>
/// Oreo hoards socks; Mylo only counts them.
/// </summary>
public class NullableCollectionFlowTests
{
    private static async Task<T> First<T>(IAsyncEnumerable<T> flow)
    {
        await foreach (T item in flow) return item;
        throw new InvalidOperationException("the flow completed without an element");
    }

    private static async Task<List<T>> Drain<T>(IAsyncEnumerable<T> flow)
    {
        var seen = new List<T>();
        await foreach (T item in flow) seen.Add(item);
        return seen;
    }

    [Fact]
    public async Task Class_Socks_ReadsNullThenACollection_AndKotlinSeesEachWrite()
    {
        var drawer = new SockDrawer();
        using KotlinMutableStateFlow<IReadOnlyList<int>?> socks = drawer.Socks;

        Assert.Null(socks.Value);
        Assert.Null(await First(socks));

        socks.Value = [3, 4];
        Assert.Equal([3, 4], socks.Value);
        Assert.Equal(7, drawer.SockTotal());
        Assert.Equal([3, 4], await First(socks));

        socks.Value = [];
        Assert.Equal(0, drawer.SockTotal());

        socks.Value = null;
        Assert.Null(socks.Value);
        Assert.Equal(-1, drawer.SockTotal());
    }

    [Fact]
    public void Class_Socks_CompareAndSetTellsNullFromEmpty()
    {
        var drawer = new SockDrawer();
        using KotlinMutableStateFlow<IReadOnlyList<int>?> socks = drawer.Socks;

        Assert.False(socks.CompareAndSet([], [1]));
        Assert.True(socks.CompareAndSet(null, [1]));
        Assert.True(socks.CompareAndSet([1], null));
        Assert.Equal(-1, drawer.SockTotal());
    }

    [Fact]
    public void Class_Photo_NullableBytesAreSettable()
    {
        var drawer = new SockDrawer();
        using KotlinMutableStateFlow<byte[]?> photo = drawer.Photo;

        Assert.Null(photo.Value);
        photo.Value = [1, 2, 3];
        Assert.Equal(3, drawer.PhotoSize());
        Assert.Equal([1, 2, 3], photo.Value);
        photo.Value = null;
        Assert.Equal(-1, drawer.PhotoSize());
    }

    [Fact]
    public async Task Class_Labels_EmitsNullAndASet_ReplayingTheLast()
    {
        var drawer = new SockDrawer();
        using KotlinMutableSharedFlow<IReadOnlySet<string>?> labels = drawer.Labels;

        await labels.EmitAsync(new HashSet<string> { "Oreo", "Mylo" });
        Assert.Equal("Mylo,Oreo", drawer.LastLabels());
        Assert.Equal(["Mylo", "Oreo"], Assert.Single(labels.ReplayCache)!.Order());

        Assert.True(labels.TryEmit(null));
        Assert.Equal("none", drawer.LastLabels());
        Assert.Null(Assert.Single(labels.ReplayCache));
        Assert.Null(await First(labels));
    }

    [Fact]
    public void Class_Sleepers_AProjectedComponentCrossesBothWays()
    {
        var drawer = new SockDrawer();
        using KotlinMutableStateFlow<IReadOnlyList<Posture>?> sleepers = drawer.Sleepers;

        sleepers.Value = [Posture.Loaf, Posture.Curled];
        Assert.Equal("LOAF,CURLED", drawer.SleeperNames());
        Assert.Equal([Posture.Loaf, Posture.Curled], sleepers.Value);
        sleepers.Value = null;
        Assert.Equal("none", drawer.SleeperNames());
    }

    [Fact]
    public async Task Class_Tallies_AColdFlowYieldsNullThenAMap()
    {
        var drawer = new SockDrawer();
        using KotlinFlow<IReadOnlyDictionary<string, int>?> tallies = drawer.Tallies;

        List<IReadOnlyDictionary<string, int>?> seen = await Drain(tallies);

        Assert.Equal(2, seen.Count);
        Assert.Null(seen[0]);
        Assert.Equal(3, seen[1]!["Oreo"]);
    }

    [Fact]
    public async Task Class_HeldAndAwaitedHolders_ShareTheProperty()
    {
        var drawer = new SockDrawer();
        using KotlinMutableStateFlow<IReadOnlyList<int>?> jar = drawer.SockJar();
        using KotlinMutableStateFlow<IReadOnlyList<int>?> awaited = await drawer.AwaitSocksAsync();
        using KotlinMutableStateFlow<byte[]?> photo = await drawer.AwaitPhotoAsync();

        jar.Value = [5];
        Assert.Equal([5], awaited.Value);
        awaited.Value = null;
        Assert.Null(jar.Value);
        Assert.Equal(-1, drawer.SockTotal());

        photo.Value = [9, 9];
        Assert.Equal(2, drawer.PhotoSize());
        photo.Value = null;
        Assert.Null(photo.Value);
    }

    [Fact]
    public async Task Object_Laundry_IsSettableWithNull()
    {
        using KotlinMutableStateFlow<IReadOnlyList<int>?> socks = Laundry.Socks;
        using KotlinMutableSharedFlow<IReadOnlySet<string>?> labels = Laundry.Labels;

        socks.Value = [2, 2];
        Assert.Equal(4, Laundry.SockTotal());
        socks.Value = null;
        Assert.Equal(-1, Laundry.SockTotal());
        Assert.Null(socks.Value);

        await labels.EmitAsync(null);
        Assert.Null(Assert.Single(labels.ReplayCache));
        await labels.EmitAsync(new HashSet<string> { "Oreo" });
        Assert.Equal(["Oreo"], Assert.Single(labels.ReplayCache)!);
    }

    [Fact]
    public void TopLevel_LooseSocks_IsSettableWithNull()
    {
        using KotlinMutableStateFlow<IReadOnlyList<int>?> socks = SockDrawers.LooseSocks;
        using KotlinMutableSharedFlow<IReadOnlySet<string>?> labels = SockDrawers.LooseLabels;

        socks.Value = [1, 1, 1];
        Assert.Equal(3, SockDrawers.LooseSockTotal());
        socks.Value = null;
        Assert.Equal(-1, SockDrawers.LooseSockTotal());

        Assert.True(labels.TryEmit(null));
        Assert.Null(Assert.Single(labels.ReplayCache));
    }
}

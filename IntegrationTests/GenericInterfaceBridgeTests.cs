using TestLibrary.Cat;
using TestLibrary.Playroom;
using Tabby = TestLibrary.Membergeneric.Tabby;

namespace IntegrationTests;

/// <summary>
/// ADR-222: a C# class implementing a Kotlin generic interface (<c>class Diary : IKeepsake&lt;string&gt;</c>)
/// crosses to Kotlin at an input position, and Kotlin reads, writes and calls its members. One
/// erased bridge factory per interface serves every instantiation C# picks, so the rows walk the
/// payload kinds a <c>T</c> slot carries: a builtin reference (<c>string</c>), a value type
/// (<c>int</c>), a Kotlin-backed wrapper (<c>Cat</c>), a bounded wrapper (<c>Tabby</c>), and a
/// Kotlin-backed implementation (<c>Locket&lt;string&gt;</c>) at the same member.
///
/// Every write is read back on the C# side, so a setter slot that dropped the value cannot pass on
/// what Kotlin returns alone.
///
/// Oreo keeps a diary of every sunbeam; Mylo keeps a tally of every treat.
/// </summary>
public class GenericInterfaceBridgeTests
{
    private sealed class Diary : IKeepsake<string>
    {
        public string Item { get; set; } = "Oreo";
        public void Dispose() { }
    }

    private sealed class Counter : IKeepsake<int>
    {
        public int Item { get; set; } = 41;
        public void Dispose() { }
    }

    // Generic on purpose: a `Basket<Cat>` is a class static type, which is the shape where an
    // overload of `HandleOf` loses to the erased one (ADR-222 spike), so the call site has to name
    // the per-interface entry.
    private sealed class Basket<T>(T item) : IKeepsake<T>
    {
        public T Item { get; set; } = item;
        public void Dispose() { }
    }

    private sealed class Scrapbook : IAlbum<string>
    {
        public string Page { get; private set; } = "page 1";
        public string Cover => "Oreo";
        public string? Bookmark { get; set; } = "p1";

        public string Turn(string next)
        {
            string previous = Page;
            Page = next;
            return previous;
        }

        public string Title() => "Scraps";
        public void Dispose() { }
    }

    private sealed class Medals : IPodium<string>
    {
        public string Best => "Mylo";
        public void Dispose() { }
    }

    [Fact]
    public void Inspect_CSharpKeepsakeOfString_ReadsTheItem()
    {
        using var diary = new Diary();

        Assert.Equal("curator sees Oreo", Keepsakes.Inspect(diary));
    }

    [Fact]
    public void Polish_CSharpKeepsakeOfInt_WritesThroughTheSetter()
    {
        using var counter = new Counter();

        Assert.Equal(42, Keepsakes.Polish(counter));
        Assert.Equal(42, counter.Item);
    }

    [Fact]
    public void Trade_OpenT_String_SwapsAndReturnsTheOldItem()
    {
        using var diary = new Diary();

        Assert.Equal("Oreo", Keepsakes.Trade(diary, "Mylo"));
        Assert.Equal("Mylo", diary.Item);
    }

    [Fact]
    public void Trade_OpenT_Int_SwapsAndReturnsTheOldItem()
    {
        using var counter = new Counter();

        Assert.Equal(41, Keepsakes.Trade(counter, 7));
        Assert.Equal(7, counter.Item);
    }

    [Fact]
    public void PetName_BoundedT_ReadsTheBorrowedCat()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var basket = new Basket<Tabby>(oreo);

        Assert.Equal("Oreo", Keepsakes.PetName(basket));
        // The slot handed Kotlin a borrowed wrapper: Oreo is still the consumer's after the call.
        Assert.Equal("Oreo", oreo.Name);
        Assert.Same(oreo, basket.Item);
    }

    [Fact]
    public void Trade_OpenT_Cat_HandsKotlinTheCatAndBackTheOldOne()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);
        using var basket = new Basket<Cat>(oreo);

        using Cat old = Keepsakes.Trade(basket, mylo);

        Assert.Equal("Oreo", old.Name);
        using Cat stored = basket.Item;
        Assert.Equal("Mylo", stored.Name);
        // Both cats the consumer handed in are still usable.
        Assert.Equal("Oreo", oreo.Name);
        Assert.Equal("Mylo", mylo.Name);
    }

    [Fact]
    public void Crown_SelfBoundedT_ReadsTheBest()
    {
        using var medals = new Medals();

        Assert.Equal("crowned Mylo", Keepsakes.Crown(medals));
    }

    [Fact]
    public void Leaf_CSharpAlbum_MethodWithTInAndOut_NullableTSetter()
    {
        using var curator = new Curator();
        using var scrapbook = new Scrapbook();

        Assert.Equal("Scraps: Oreo then page 1, bookmark null", curator.Leaf(scrapbook));
        Assert.Equal("page 2", scrapbook.Page);
        Assert.Null(scrapbook.Bookmark);
    }

    [Fact]
    public void Shine_ReceiverOverGenericInterface()
    {
        using var diary = new Diary { Item = "Mylo" };

        Assert.Equal("shining Mylo", diary.Shine());
    }

    [Fact]
    public void Inspect_KotlinBackedLocket_BindsTheSameMember()
    {
        using var locket = new Locket<string>("Oreo");
        using var tally = new Locket<int>(0);

        Assert.Equal("curator sees Oreo", Keepsakes.Inspect(locket));
        Assert.Equal(1, Keepsakes.Polish(tally));
        Assert.Equal(1, tally.Item);
    }

    [Fact]
    public void Inspect_SameDiaryTwice_EachCrossingWorks()
    {
        using var diary = new Diary();

        Assert.Equal("curator sees Oreo", Keepsakes.Inspect(diary));
        diary.Item = "Mylo";
        Assert.Equal("curator sees Mylo", Keepsakes.Inspect(diary));
    }
}

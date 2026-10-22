using TestLibrary.Cat;
using TestLibrary.Playroom;
using Rankings = TestLibrary.Rankings;
using Tabby = TestLibrary.Membergeneric.Tabby;

namespace IntegrationTests;

/// <summary>
/// ADR-218: a <c>var</c> whose type mentions a class's own type parameter binds with a C# setter.
/// The bare <c>T</c> and <c>T?</c> setters box the value (<c>Wrap&lt;T&gt;</c>) and dispose only
/// the box they minted, so a borrowed wrapper is still the consumer's after the call. The
/// collection setters (ADR-212) compiled before this ADR but had never run; these rows pin them.
///
/// Every row reads the stored value back twice: through the C# getter, and through a Kotlin
/// member (<c>Describe</c>, <c>Names</c>, <c>Lookup</c>, <c>Peek</c>) that reads it on the Kotlin
/// side, so a setter that wrote the wrong thing, or nothing, cannot pass on a cached read.
///
/// Oreo claims the playpen first; Mylo moves in whenever Oreo naps.
/// </summary>
public class TypeParameterPropertySetterTests
{
    [Fact]
    public void Playpen_Int_CurrentSetter_RoundTrips()
    {
        using var pen = new Playpen<int>(9);

        pen.Current = 7;

        Assert.Equal(7, pen.Current);
        Assert.StartsWith("current=7 ", pen.Describe());
    }

    [Fact]
    public void Playpen_String_CurrentSetter_RoundTrips()
    {
        using var pen = new Playpen<string>("Oreo");

        pen.Current = "Mylo";

        Assert.Equal("Mylo", pen.Current);
        Assert.StartsWith("current=Mylo ", pen.Describe());
    }

    [Fact]
    public void Playpen_NullableT_MaybeSetter_StoresAValueThenNull()
    {
        using var pen = new Playpen<int>(9);

        pen.Maybe = 4;
        Assert.Equal(4, pen.Maybe);
        Assert.Contains("maybe=4 ", pen.Describe());

        // An unconstrained `T?` is spelled `T` in C# (ADR-147), so `Playpen<int>.Maybe` cannot take
        // null; a reference type argument carries it.
        using var names = new Playpen<string>("Oreo");
        names.Maybe = "Mylo";
        names.Maybe = null;
        Assert.Null(names.Maybe);
        Assert.Contains("maybe=null ", names.Describe());
    }

    [Fact]
    public void Playpen_NullableStringT_MaybeSetter_StoresNull()
    {
        using var pen = new Playpen<string?>("Oreo");

        pen.Maybe = "Mylo";
        Assert.Equal("Mylo", pen.Maybe);

        pen.Maybe = null;
        Assert.Null(pen.Maybe);

        // A nullable type argument lets the bare `T` carry null too.
        pen.Current = null;
        Assert.Null(pen.Current);
        Assert.StartsWith("current=null ", pen.Describe());
    }

    [Fact]
    public void Playpen_Cat_CurrentSetter_StoresTheCatAndLeavesTheConsumersWrapperAlive()
    {
        using var mylo = new Cat("Mylo", 9);
        using var oreo = new Cat("Oreo", 9);
        using var pen = new Playpen<Cat>(mylo);

        pen.Current = oreo;

        // Borrowed: the setter crossed oreo's handle and disposed nothing of the consumer's.
        Assert.Equal("Oreo", oreo.Name);
        Assert.Equal(9, oreo.Lives);
        using Cat back = pen.Current;
        Assert.Equal("Oreo", back.Name);

        // The stored object is oreo itself, not a copy: a write through it shows through the pen.
        oreo.Owner = "Isuru";
        using Cat again = pen.Current;
        Assert.Equal("Isuru", again.Owner);
    }

    [Fact]
    public void Playpen_Cat_MaybeSetter_StoresTheCatThenNull()
    {
        using var mylo = new Cat("Mylo", 9);
        using var pen = new Playpen<Cat>(mylo);

        pen.Maybe = mylo;
        Assert.Equal("Mylo", mylo.Name);
        using (Cat? back = pen.Maybe)
        {
            Assert.NotNull(back);
            Assert.Equal("Mylo", back!.Name);
        }

        pen.Maybe = null;
        Assert.Null(pen.Maybe);
    }

    [Fact]
    public void Playpen_Int_CollectionSetters_RoundTrip()
    {
        using var pen = new Playpen<int>(9);

        pen.Items = new[] { 1, 2, 3 };
        pen.Bag = new List<int> { 4, 5 };
        pen.Unique = new HashSet<int> { 6 };
        pen.Tags = new Dictionary<string, int> { ["oreo"] = 7, ["mylo"] = 8 };

        Assert.Equal(new[] { 1, 2, 3 }, pen.Items);
        Assert.Equal(new[] { 4, 5 }, pen.Bag);
        Assert.Equal(new[] { 6 }, pen.Unique);
        Assert.Equal(2, pen.Tags.Count);
        Assert.Equal(7, pen.Tags["oreo"]);
        Assert.Equal(8, pen.Tags["mylo"]);
        Assert.EndsWith("items=[1, 2, 3] bag=[4, 5] unique=[6] tags={oreo=7, mylo=8}", pen.Describe());
    }

    [Fact]
    public void Playpen_String_CollectionSetters_RoundTrip()
    {
        using var pen = new Playpen<string>("Oreo");

        pen.Items = new[] { "Oreo", "Mylo" };
        pen.Bag = new List<string> { "Mylo" };
        pen.Unique = new HashSet<string> { "Oreo" };
        pen.Tags = new Dictionary<string, string> { ["black"] = "Oreo", ["brown"] = "Mylo" };

        Assert.Equal(new[] { "Oreo", "Mylo" }, pen.Items);
        Assert.Equal(new[] { "Mylo" }, pen.Bag);
        Assert.Equal(new[] { "Oreo" }, pen.Unique);
        Assert.Equal("Mylo", pen.Tags["brown"]);
        Assert.EndsWith("items=[Oreo, Mylo] bag=[Mylo] unique=[Oreo] tags={black=Oreo, brown=Mylo}", pen.Describe());
    }

    [Fact]
    public void Playpen_Cat_ItemsSetter_BorrowsEveryElement()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);
        using var pen = new Playpen<Cat>(oreo);

        pen.Items = new[] { mylo, oreo };
        pen.Tags = new Dictionary<string, Cat> { ["brown"] = mylo };

        // The list handle was the setter's to dispose; its elements were not.
        Assert.Equal("Mylo", mylo.Name);
        Assert.Equal("Oreo", oreo.Name);

        IReadOnlyList<Cat> items = pen.Items;
        try
        {
            Assert.Equal(new[] { "Mylo", "Oreo" }, items.Select(cat => cat.Name));
        }
        finally
        {
            foreach (Cat cat in items) cat.Dispose();
        }
        using Cat brown = pen.Tags["brown"];
        Assert.Equal("Mylo", brown.Name);
    }

    [Fact]
    public void Cradle_BoundedT_SettersCastToTheBoundOnTheKotlinSide()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 5);
        using var cradle = new Cradle<Tabby>(oreo);

        cradle.Current = mylo;
        cradle.Pets = new[] { oreo, mylo };

        Assert.Equal("Mylo, Oreo, Mylo", cradle.Names());
        using Tabby back = cradle.Current;
        Assert.Equal(5, back.Tricks);
        Assert.Equal(3, oreo.Tricks);
    }

    [Fact]
    public void Keyring_TwoTypeParameters_MapSetterRoundTrips()
    {
        using var ring = new Keyring<string, int>("oreo", 5);

        ring.Index = new Dictionary<string, int> { ["mylo"] = 4, ["oreo"] = 6 };

        Assert.Equal(4, ring.Lookup("mylo"));
        Assert.Equal(6, ring.Lookup("oreo"));
        Assert.Equal(2, ring.Index.Count);
    }

    [Fact]
    public void Sash_EnumSelfBound_TrampolinedSetterHandsKotlinTheRealEntry()
    {
        using var sash = new Sash<Rankings.Medal>(Rankings.Medal.Silver);

        sash.Current = Rankings.Medal.Gold;
        Assert.Equal(Rankings.Medal.Gold, sash.Current);
        Assert.Equal("GOLD#2 then nobody", sash.Describe());

        sash.RunnerUp = Rankings.Medal.Silver;
        Assert.Equal(Rankings.Medal.Silver, sash.RunnerUp);
        Assert.Equal("GOLD#2 then SILVER", sash.Describe());

        // Under `struct`, a null `T?` must reach Kotlin as null, not as `default(Medal)` (Bronze).
        sash.RunnerUp = null;
        Assert.Null(sash.RunnerUp);
        Assert.Equal("GOLD#2 then nobody", sash.Describe());
    }

    [Fact]
    public void Keepsake_InterfaceOwnedT_DeclaresASetter()
    {
        var item = typeof(IKeepsake<>).GetProperty("Item");

        Assert.NotNull(item);
        Assert.True(item!.CanWrite);
    }

    [Fact]
    public void Locket_InterfaceOwnedT_SetsThroughTheClassAndTheInterface()
    {
        using var locket = new Locket<string>("Oreo");

        locket.Item = "Mylo";
        Assert.Equal("Mylo", locket.Item);
        Assert.Equal("locket holds Mylo", locket.Peek());

        IKeepsake<string> keepsake = locket;
        keepsake.Item = "Oreo";
        Assert.Equal("Oreo", keepsake.Item);
        Assert.Equal("locket holds Oreo", locket.Peek());
    }

    [Fact]
    public void Locket_InterfaceOwnedT_CatThroughTheInterfaceIsBorrowed()
    {
        using var oreo = new Cat("Oreo", 9);
        using var mylo = new Cat("Mylo", 9);
        using var locket = new Locket<Cat>(oreo);

        IKeepsake<Cat> keepsake = locket;
        keepsake.Item = mylo;

        Assert.Equal("Mylo", mylo.Name);
        using Cat back = keepsake.Item;
        Assert.Equal("Mylo", back.Name);
    }
}

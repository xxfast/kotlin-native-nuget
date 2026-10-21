using TestLibrary;
using TestLibrary.Membergeneric;
using TestLibrary.Outcome;
using TestLibrary.Rankings;

namespace IntegrationTests;

/// <summary>
/// ADR-215 lane B: a type parameter as the element of a <c>Flow</c> Kotlin hands to C#. A generic
/// class's own <c>T</c> (<c>Brook&lt;T&gt;.Updates : KotlinFlow&lt;T&gt;</c>), a member's own
/// <c>T</c> (<c>KotlinFlow&lt;T&gt; Stream&lt;T&gt;(T value)</c>), a trampolined
/// <c>T : Enum&lt;T&gt;</c> and a multi-bound owner <c>T</c> each cross on the boxed-handle wire,
/// so an <c>int</c>, a <c>string</c> and a <c>Tabby</c> wrapper come out as themselves, and a null
/// element of an unconstrained <c>T</c> comes out as null.
/// <para>
/// Red before ADR-215 lane B: a generic class's flow property is dropped from C# with no
/// diagnostic, and every Flow-returning member of a generic class or with its own <c>T</c> is
/// skipped, so each member below is CS1061. <c>Dish.Hot&lt;string?&gt;</c> already binds and is the
/// runtime red: its null element is boxed <c>retain(value as Any)</c>.
/// </para>
/// Oreo (black with the white middle) and Mylo (brown and creamy) sit by the brook watching it run.
/// </summary>
public class MemberGenericFlowTests
{
    private static async Task<List<T>> Drain<T>(IAsyncEnumerable<T> flow)
    {
        var items = new List<T>();
        await foreach (T item in flow) items.Add(item);
        return items;
    }

    // (1) A generic class's own `T` as a flow property.

    [Fact]
    public async Task Brook_Updates_IntElement_Collects()
    {
        // Oreo's age in years, twice.
        await using var brook = new Brook<int>(4);

        Assert.Equal([4, 4], await Drain(brook.Updates));
    }

    [Fact]
    public async Task Brook_Updates_StringElement_Collects()
    {
        await using var brook = new Brook<string>("Mylo");

        Assert.Equal(["Mylo", "Mylo"], await Drain(brook.Updates));
    }

    [Fact]
    public async Task Brook_Updates_WrapperElement_ComesBackAsTheSameKotlinObject()
    {
        using var oreo = new Tabby("Oreo", 3);
        await using var brook = new Brook<Tabby>(oreo);

        var seen = new List<string>();
        await foreach (Tabby cat in brook.Updates)
        {
            using (cat) seen.Add($"{cat.Name}:{cat.Tricks}");
        }

        Assert.Equal(["Oreo:3", "Oreo:3"], seen);
    }

    [Fact]
    public void Brook_Updates_IsAKotlinFlowOfTheClosedElement()
    {
        Assert.Equal(typeof(KotlinFlow<string>), typeof(Brook<string>).GetProperty("Updates")?.PropertyType);
    }

    [Fact]
    public async Task Brook_Flowing_MethodHalf_Collects()
    {
        await using var brook = new Brook<string>("Oreo");

        Assert.Equal(["Oreo", "Oreo", "Oreo"], await Drain(brook.Flowing(3)));
    }

    // (3) A null element of an unconstrained `T`.

    [Fact]
    public async Task Brook_Updates_NullSeed_YieldsNullElements()
    {
        await using var brook = new Brook<string?>(null);

        Assert.Equal([null, null], await Drain(brook.Updates));
    }

    [Fact]
    public async Task Brook_Gaps_NullableElement_YieldsTheGap()
    {
        await using var brook = new Brook<string>("Mylo");

        Assert.Equal(["Mylo", null, "Mylo"], await Drain(brook.Gaps));
    }

    [Fact]
    public async Task GenericArm_Portions_NullItem_YieldsNullElements()
    {
        // Already bound before ADR-215. Mylo licked the plate clean, so there is nothing on it.
        await using var mylo = new Dish.Hot<string?>(null);

        Assert.Equal([null, null], await Drain(mylo.Portions));
    }

    // (4) A flow on a generic class that does not mention `T`.

    [Fact]
    public async Task Brook_Ripples_FlowWithoutTypeParameter_Collects()
    {
        using var mylo = new Tabby("Mylo", 1);
        await using var brook = new Brook<Tabby>(mylo);

        Assert.Equal([1, 2, 3], await Drain(brook.Ripples));
    }

    // (5) `MutableStateFlow<T>` on a generic owner.

    [Fact]
    public async Task Brook_Level_ReadsWritesAndCollectsAString()
    {
        await using var brook = new Brook<string>("Oreo");

        Assert.Equal("Oreo", brook.Level.Value);
        brook.Level.Value = "Mylo";
        Assert.Equal("Mylo", brook.Level.Value);

        using var cts = new CancellationTokenSource();
        var seen = new List<string>();
        await foreach (string name in brook.Level.WithCancellation(cts.Token))
        {
            seen.Add(name);
            cts.Cancel();
        }

        Assert.Equal(["Mylo"], seen);
    }

    [Fact]
    public async Task Brook_Level_WriteOfAWrapper_ReadsBackTheSameCat()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        await using var brook = new Brook<Tabby>(oreo);

        brook.Level.Value = mylo;

        using Tabby current = brook.Level.Value;
        Assert.Equal("Mylo", current.Name);
        Assert.Equal(1, current.Tricks);
    }

    [Fact]
    public async Task Brook_Level_IntWrite_RoundTrips()
    {
        await using var brook = new Brook<int>(4);

        brook.Level.Value = 5;

        Assert.Equal(5, brook.Level.Value);
    }

    // Cancellation mid-stream, then dispose.

    [Fact]
    public async Task Brook_Babble_CancelledMidStream_StopsAndDisposes()
    {
        var brook = new Brook<string>("splash");
        using var cts = new CancellationTokenSource();
        int seen = 0;

        // A cancelled KotlinFlow ends the enumeration cleanly, as every shipped flow does
        // (FlowTests.Flow_WithCancellation_ExitsCleanlyAfterFirstItem); the endless flow stops.
        await foreach (string splash in brook.Babble().WithCancellation(cts.Token))
        {
            Assert.Equal("splash", splash);
            if (++seen == 3) cts.Cancel();
        }

        Assert.True(seen >= 3);
        await brook.DisposeAsync();
    }

    [Fact]
    public async Task Brook_Babble_BreakOutOfAwaitForeach_DisposesTheEnumerator()
    {
        await using var brook = new Brook<int>(7);
        int seen = 0;

        await foreach (int splash in brook.Babble())
        {
            Assert.Equal(7, splash);
            if (++seen == 2) break;
        }

        Assert.Equal(2, seen);
    }

    // (2) A member's own `T` on a Flow-returning method.

    [Fact]
    public async Task Creek_Stream_IntStringAndWrapper_RoundTrip()
    {
        using var creek = new Creek();
        using var oreo = new Tabby("Oreo", 3);

        Assert.Equal([4], await Drain(creek.Stream(4)));
        Assert.Equal(["Mylo"], await Drain(creek.Stream("Mylo")));
        var cats = await Drain(creek.Stream(oreo));
        using Tabby back = Assert.Single(cats);
        Assert.Equal("Oreo", back.Name);
    }

    [Fact]
    public async Task Creek_Echo_TypeParameterAndTimes_RepeatsTheValue()
    {
        using var creek = new Creek();

        Assert.Equal(["Mylo", "Mylo", "Mylo"], await Drain(creek.Echo("Mylo", 3)));
        Assert.Equal([2, 2], await Drain(creek.Echo(2, 2)));
        Assert.Empty(await Drain(creek.Echo("Oreo", 0)));
    }

    [Fact]
    public async Task Creek_Stream_NullArgument_YieldsNull()
    {
        using var creek = new Creek();

        Assert.Equal([null], await Drain(creek.Stream<string?>(null)));
    }

    [Fact]
    public async Task Creek_Hush_NullableElement_YieldsValueThenNull()
    {
        using var creek = new Creek();

        Assert.Equal(["Oreo", null], await Drain(creek.Hush("Oreo")));
    }

    [Fact]
    public async Task Creek_Ticks_FlowWithoutTypeParameterOnAGenericMember_Counts()
    {
        using var creek = new Creek();

        Assert.Equal([4, 3, 2, 1], await Drain(creek.Ticks("Mylo")));
        Assert.Equal([2, 1], await Drain(creek.Ticks(42)));
    }

    [Fact]
    public async Task Creek_Echo_CancelledMidStream_StopsCollecting()
    {
        using var creek = new Creek();
        using var cts = new CancellationTokenSource();
        var seen = new List<string>();

        // A cancelled KotlinFlow ends the enumeration cleanly (see Brook_Babble above).
        await foreach (string name in creek.Echo("Oreo", 1000).WithCancellation(cts.Token))
        {
            seen.Add(name);
            if (seen.Count == 2) cts.Cancel();
        }

        Assert.InRange(seen.Count, 2, 3);
    }

    // (6) A trampolined `T : Enum<T>` parameter.

    [Fact]
    public async Task Creek_Cheer_EnumBoundParameter_ReachesKotlinAsTheRealEntry()
    {
        using var creek = new Creek();

        Assert.Equal(["GOLD", "#2"], await Drain(creek.Cheer(Medal.Gold)));
    }

    [Fact]
    public async Task Creek_Crown_EnumBoundElement_ComesBackAsTheEnum()
    {
        using var creek = new Creek();

        Assert.Equal([Medal.Silver], await Drain(creek.Crown(Medal.Silver)));
    }

    [Fact]
    public async Task Creek_Crown_Bronze_ThrowsOnTheStream()
    {
        using var creek = new Creek();

        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(() => Drain(creek.Crown(Medal.Bronze)));

        Assert.Equal("BRONZE is no prize", ex.Message);
        Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)ex).KotlinType);
    }

    // (7) A multi-bound `T`.

    [Fact]
    public async Task Creek_Rally_MultiBoundParameter_UsesBothBounds()
    {
        using var creek = new Creek();
        using var mylo = new Tabby("Mylo", 1);

        Assert.Equal(["Mylo:1"], await Drain(creek.Rally(mylo)));
    }

    [Fact]
    public async Task Pride_Members_MultiBoundOwnerElement_Collects()
    {
        using var oreo = new Tabby("Oreo", 3);
        await using var pride = new Pride<Tabby>(oreo);

        var members = await Drain(pride.Members);
        using Tabby leader = Assert.Single(members);
        Assert.Equal("Oreo", leader.Name);
        Assert.Equal(3, leader.Tricks);
    }

    [Fact]
    public async Task Pride_Join_MultiBoundOwnerParameterAndElement_YieldsLeaderThenNewcomer()
    {
        using var oreo = new Tabby("Oreo", 3);
        using var mylo = new Tabby("Mylo", 1);
        await using var pride = new Pride<Tabby>(oreo);

        var seen = new List<string>();
        await foreach (Tabby cat in pride.Join(mylo))
        {
            using (cat) seen.Add(cat.Name);
        }

        Assert.Equal(["Oreo", "Mylo"], seen);
    }
}

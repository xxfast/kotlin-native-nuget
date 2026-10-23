using TestLibrary;
using TestLibrary.Purrclock;

namespace IntegrationTests;

/// <summary>
/// ADR-230: a flow family property declared on an enum (in the constructor, in the class body, or
/// abstract and overridden in each entry's own body) is a C# extension property on
/// <c>TailExtensions</c>, the entry crossing as its ordinal. A held <c>MutableStateFlow</c> or
/// <c>MutableSharedFlow</c> returns the holder of that entry's own flow, so a write through one
/// entry leaves the other alone.
/// Oreo flicks the tip of hers; Mylo thumps his on the floor.
/// </summary>
public class EnumFlowPropertyTests
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
    public async Task Mood_AnAbstractPropertyDispatchesToEachEntrysOverride()
    {
        using KotlinFlow<string> oreo = Tail.Oreo.Mood;
        using KotlinFlow<string> mylo = Tail.Mylo.Mood;

        Assert.Equal(["Oreo flicks"], await Drain(oreo));
        Assert.Equal(["Mylo thumps", "Mylo thumps again"], await Drain(mylo));
    }

    [Fact]
    public async Task Wags_AColdBodyPropertyIsReadPerEntry()
    {
        using KotlinFlow<int> oreo = Tail.Oreo.Wags;
        using KotlinFlow<int> mylo = Tail.Mylo.Wags;

        Assert.Equal([0, 1], await Drain(oreo));
        Assert.Equal([1, 2], await Drain(mylo));
    }

    [Fact]
    public async Task Height_EachEntryHoldsItsOwnSettableFlow()
    {
        using KotlinMutableStateFlow<int> oreo = Tail.Oreo.Height;
        using KotlinMutableStateFlow<int> mylo = Tail.Mylo.Height;
        int myloBefore = mylo.Value;

        oreo.Value = 42;

        Assert.Equal(42, Tails.TailHeight(Tail.Oreo));
        Assert.Equal(myloBefore, mylo.Value);
        Assert.Equal(myloBefore, Tails.TailHeight(Tail.Mylo));
        using KotlinStateFlow<int> level = Tail.Oreo.Level;
        Assert.Equal(42, level.Value);
        Assert.Equal(42, await First(level));
    }

    [Fact]
    public void Swishes_AConstructorPropertyIsHeldPerEntry()
    {
        using KotlinMutableStateFlow<int> oreo = Tail.Oreo.Swishes;
        using KotlinMutableStateFlow<int> again = Tail.Oreo.Swishes;

        oreo.Value = 5;

        Assert.Equal(5, again.Value);
        Assert.True(again.CompareAndSet(5, 1));
        Assert.Equal(1, oreo.Value);
    }

    [Fact]
    public async Task Twitches_EmitsThroughOneEntryAndReplaysOnItsReadOnlyView()
    {
        using KotlinMutableSharedFlow<string> twitches = Tail.Mylo.Twitches;
        using KotlinSharedFlow<string> echoes = Tail.Mylo.Echoes;

        await twitches.EmitAsync("thump");
        Assert.True(twitches.TryEmit("thump thump"));

        Assert.Equal("thump thump", Assert.Single(echoes.ReplayCache));
        Assert.Equal("thump thump", await First(echoes));
    }
}

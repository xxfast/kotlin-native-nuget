using TestLibrary;
using TestLibrary.Keywordmembers;

namespace IntegrationTests;

/// <summary>
/// ADR-179 rules 2 and 5: a C# keyword passed to <c>@CSharpName</c> renders <c>@</c>-escaped on
/// every route that renders a member, and each private extern keeps the Kotlin-derived stem the
/// generated body calls. Tier 1 reads the generated text only, which is how a class <c>Flow</c>
/// property once shipped an extern declared under one name and called under another; this file
/// compiling at all is the first assertion. One fact per route, against the
/// <c>Lantern.kt</c> fixture.
/// <para>
/// Oreo lights the lantern in the hallway; Mylo watches it flicker.
/// </para>
/// </summary>
public class KeywordMemberNamesTests
{
    private sealed class RecordingKeeper : ILanternKeeper
    {
        public List<int> Levels { get; } = new();
        public void @event(int level) => Levels.Add(level);
        public void Dispose() { }
    }

    [Fact]
    public void PlannedProperty_KeywordName_ReadsAndWrites()
    {
        using var lantern = new Lantern("hallway");

        Assert.Equal("hallway", lantern.@checked);
        lantern.@checked = "porch";
        Assert.Equal("porch", lantern.@checked);
    }

    [Fact]
    public async Task FlowProperty_KeywordName_CollectsThroughItsExtern()
    {
        using var lantern = new Lantern("hallway");
        var seen = new List<int>();

        await foreach (var item in lantern.@event)
        {
            seen.Add(item);
        }

        Assert.Equal(new[] { 1, 2, 3 }, seen);
    }

    [Fact]
    public void MutableStateFlowProperty_KeywordName_ReadsAndWritesTheValue()
    {
        using var lantern = new Lantern("hallway");
        using KotlinMutableStateFlow<int> brightness = lantern.@object;

        Assert.Equal(4, brightness.Value);
        brightness.Value = 7;
        Assert.Equal(7, brightness.Value);
    }

    [Fact]
    public async Task FlowMethod_KeywordName_TakesTheDeclaredName()
    {
        using var lantern = new Lantern("hallway");
        var seen = new List<int>();

        await foreach (var item in lantern.@lock(times: 3))
        {
            seen.Add(item);
        }

        Assert.Equal(new[] { 1, 2, 3 }, seen);
    }

    [Fact]
    public void StateFlowMethod_KeywordName_TakesTheDeclaredName()
    {
        using var lantern = new Lantern("hallway");
        using KotlinStateFlow<int> glow = lantern.@fixed(level: 6);

        Assert.Equal(6, glow.Value);
    }

    [Fact]
    public void StoredCallbackAdd_KeywordName_SubscribesUntilDisposed()
    {
        using var lantern = new Lantern("hallway");
        var sparks = new List<int>();

        using (lantern.@namespace(level => sparks.Add(level)))
        {
            lantern.Light(2);
        }
        lantern.Light(3);

        Assert.Equal(new[] { 2 }, sparks);
    }

    [Fact]
    public void InterfaceBridgeAdd_KeywordNames_CallTheKeeperByItsDeclaredName()
    {
        using var lantern = new Lantern("hallway");
        var keeper = new RecordingKeeper();

        using (lantern.@operator(keeper))
        {
            lantern.Light(5);
        }
        lantern.Light(8);

        Assert.Equal(new[] { 5 }, keeper.Levels);
    }

    [Fact]
    public void DeclaredNames_AreTheCSharpMemberNames()
    {
        Type lantern = typeof(Lantern);

        Assert.NotNull(lantern.GetProperty("event"));
        Assert.NotNull(lantern.GetProperty("checked"));
        Assert.NotNull(lantern.GetMethod("lock"));
        Assert.NotNull(lantern.GetMethod("namespace"));
        Assert.Null(lantern.GetMethod("Flicker"));
        Assert.Null(lantern.GetMethod("AddSpark"));
        Assert.Null(lantern.GetMethod("AddKeeper"));
    }
}

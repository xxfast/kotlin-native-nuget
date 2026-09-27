using TestLibrary.Litterbox;

namespace IntegrationTests;

/// <summary>
/// ADR-170: a top-level function returning a nullable scalar takes the ADR-061 single-call
/// <c>valueOut</c> route. Every function here takes a parameter with a C#-side prelude (list,
/// nullable list, map, set, byte array, Kotlin interface, callback), which the retired two-call
/// route referenced without declaring, and the callback cells prove Kotlin runs exactly once per
/// C# call.
///
/// Mylo digs, Oreo inspects, Rex the dog is refused at the door.
/// </summary>
public class TopLevelNullableScalarSingleCallTests
{
    // A C#-side Visitor, so ScoopsFor has to route through the ADR-084 bridge factory.
    private sealed class Neighbour(string name, int visits) : IVisitor
    {
        public string Name { get; } = name;
        public int Visits() => visits;
        public void Dispose() { }
    }

    // --- List<String> -> Int? ---

    [Fact]
    public void CountVisits_OreoAndMylo_ReturnsTwo() =>
        Assert.Equal(2, ScoopLedger.CountVisits(new[] { "Oreo", "Mylo" }));

    [Fact]
    public void CountVisits_EmptyTray_ReturnsNull() =>
        Assert.Null(ScoopLedger.CountVisits(Array.Empty<string>()));

    [Fact]
    public void CountVisits_Rex_ThrowsKotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => ScoopLedger.CountVisits(new[] { "Mylo", "Rex" }));
        Assert.Contains("Rex is not allowed", ex.Message);
    }

    // --- List<Int>? -> Int? ---

    [Fact]
    public void SumDigs_MylosDigs_ReturnsSum() =>
        Assert.Equal(9, ScoopLedger.SumDigs(new[] { 2, 3, 4 }));

    [Fact]
    public void SumDigs_NullList_ReturnsNull() =>
        Assert.Null(ScoopLedger.SumDigs(null));

    [Fact]
    public void SumDigs_EmptyList_ReturnsZeroNotNull() =>
        Assert.Equal(0, ScoopLedger.SumDigs(Array.Empty<int>()));

    // --- List<String> + optional default -> Int? ---

    [Fact]
    public void CountVisitsWithBonus_DefaultBonus_AddsTwo() =>
        Assert.Equal(4, ScoopLedger.CountVisitsWithBonus(new[] { "Oreo", "Mylo" }));

    [Fact]
    public void CountVisitsWithBonus_ExplicitBonus_AddsIt() =>
        Assert.Equal(12, ScoopLedger.CountVisitsWithBonus(new[] { "Oreo", "Mylo" }, 10));

    [Fact]
    public void CountVisitsWithBonus_EmptyTray_ReturnsNull() =>
        Assert.Null(ScoopLedger.CountVisitsWithBonus(Array.Empty<string>(), 10));

    // --- Map<String, Int> -> Duration? ---

    [Fact]
    public void TimeInTray_BothCats_ReturnsTotalMinutes() =>
        Assert.Equal(TimeSpan.FromMinutes(7),
            ScoopLedger.TimeInTray(new Dictionary<string, int> { ["Oreo"] = 3, ["Mylo"] = 4 }));

    [Fact]
    public void TimeInTray_Nobody_ReturnsNull() =>
        Assert.Null(ScoopLedger.TimeInTray(new Dictionary<string, int>()));

    // --- Set<String> -> Boolean? ---

    [Fact]
    public void MyloDug_MyloInSet_ReturnsTrue() =>
        Assert.True(ScoopLedger.MyloDug(new HashSet<string> { "Oreo", "Mylo" }));

    [Fact]
    public void MyloDug_OnlyOreo_ReturnsFalseNotNull()
    {
        bool? dug = ScoopLedger.MyloDug(new HashSet<string> { "Oreo" });
        Assert.NotNull(dug);
        Assert.False(dug.Value);
    }

    [Fact]
    public void MyloDug_Nobody_ReturnsNull() =>
        Assert.Null(ScoopLedger.MyloDug(new HashSet<string>()));

    // --- List<String> -> Char? ---

    [Fact]
    public void FirstDigger_MyloFirst_ReturnsM() =>
        Assert.Equal('M', ScoopLedger.FirstDigger(new[] { "Mylo", "Oreo" }));

    [Fact]
    public void FirstDigger_EmptyTray_ReturnsNull() =>
        Assert.Null(ScoopLedger.FirstDigger(Array.Empty<string>()));

    // --- ByteArray -> enum? ---

    [Fact]
    public void GritFromLabel_PineOrdinal_ReturnsPine() =>
        Assert.Equal(Grit.Pine, ScoopLedger.GritFromLabel(new byte[] { 2, 0 }));

    [Fact]
    public void GritFromLabel_ClumpingOrdinalZero_ReturnsClumpingNotNull() =>
        Assert.Equal(Grit.Clumping, ScoopLedger.GritFromLabel(new byte[] { 0 }));

    [Fact]
    public void GritFromLabel_NoBytes_ReturnsNull() =>
        Assert.Null(ScoopLedger.GritFromLabel(Array.Empty<byte>()));

    // --- Kotlin interface -> value class? ---

    [Fact]
    public void ScoopsFor_KotlinRegular_ReturnsDoubleVisits()
    {
        using var oreo = new Regular("Oreo", 3);
        Assert.Equal(new Scoops(6), ScoopLedger.ScoopsFor(oreo));
    }

    [Fact]
    public void ScoopsFor_KotlinRegularWhoNeverWent_ReturnsNull()
    {
        using var mylo = new Regular("Mylo", 0);
        Assert.Null(ScoopLedger.ScoopsFor(mylo));
    }

    [Fact]
    public void ScoopsFor_CSharpNeighbour_ReturnsDoubleVisits()
    {
        using var ginger = new Neighbour("Ginger", 5);
        Assert.Equal(10, ScoopLedger.ScoopsFor(ginger)?.Count);
    }

    [Fact]
    public void ScoopsFor_CSharpNeighbourWhoNeverWent_ReturnsNull()
    {
        using var ghost = new Neighbour("Ghost", 0);
        Assert.Null(ScoopLedger.ScoopsFor(ghost));
    }

    // --- List<Int> -> Instant? ---

    [Fact]
    public void LastCleaned_TwoCleanings_ReturnsLatest() =>
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(1_700_000_000),
            ScoopLedger.LastCleaned(new[] { 1_600_000_000, 1_700_000_000 }));

    [Fact]
    public void LastCleaned_NeverCleaned_ReturnsNull() =>
        Assert.Null(ScoopLedger.LastCleaned(Array.Empty<int>()));

    // --- Callback -> Int?, and the single-call guarantee ---

    [Fact]
    public void DigDepth_MyloDigsDeep_ReturnsDepthAndInvokesCallbackOnce()
    {
        int calls = 0;
        int? depth = ScoopLedger.DigDepth(seed =>
        {
            calls++;
            return seed * 4;
        });

        Assert.Equal(12, depth);
        Assert.Equal(1, calls);
    }

    [Fact]
    public void DigDepth_OreoRefusesToDig_ReturnsNullAndInvokesCallbackOnce()
    {
        int calls = 0;
        int? depth = ScoopLedger.DigDepth(_ =>
        {
            calls++;
            return 0;
        });

        Assert.Null(depth);
        Assert.Equal(1, calls);
    }

    [Fact]
    public void NextScoop_EveryCall_RunsKotlinExactlyOnce()
    {
        int before = ScoopLedger.ScoopsIssued();
        ScoopLedger.NextScoop();
        ScoopLedger.NextScoop();
        Assert.Equal(before + 2, ScoopLedger.ScoopsIssued());
    }
}

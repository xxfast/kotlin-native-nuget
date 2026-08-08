using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-083 ("Nullable collection components: the null pointer in the component slot, both
/// directions"). Every member below targets <c>ChartLedger</c> in
/// <c>NullableComponentCollectionsSample.kt</c>, one test per mechanism cell: input positions
/// spread across a reference-typed element (<c>string?</c>), a boxed-primitive element
/// (<c>int?</c>), a <c>Set</c> element, a <c>Map</c> VALUE (never a key -- nullable keys are a
/// named skip, no cell here), the motivating nullable value-class element (<c>ChartId?</c>) over
/// its String underlying, and its Enum-underlying sibling (<c>Temperament?</c>), which proves the
/// C# write side's enum pre-cast spelling actually compiles and not just as generated text; plus
/// the three read-side shapes (method return, <c>var</c> property getter/setter, and a
/// <c>Map</c> return whose value is null for a present key).
///
/// Every assertion pins the null to a specific, non-first position and checks the surrounding
/// non-null elements too, so a null that silently collapsed the collection, or shifted every
/// later index, cannot pass by coincidence.
/// </summary>
public class NullableComponentCollectionTests
{
    [Fact]
    public void ChartLedger_TagRoll_ListOfNullableStringParameter_NullRidesReferenceNullNoConversion()
    {
        using var ledger = new ChartLedger();

        string joined = ledger.TagRoll(new List<string?> { "OREO", null, "MYLO" });

        Assert.Equal("OREO,null,MYLO", joined);
    }

    [Fact]
    public void ChartLedger_LogAges_MutableListOfNullableIntParameter_NullRidesBoxedPrimitiveSlot()
    {
        using var ledger = new ChartLedger();

        string joined = ledger.LogAges(new List<int?> { 3, null, 7 });

        Assert.Equal("3,null,7", joined);
    }

    [Fact]
    public void ChartLedger_TagSet_SetOfNullableStringParameter_RoundTripsTheNullElement()
    {
        using var ledger = new ChartLedger();

        // Kotlin sorts before joining, so the observed order proves the null crossed as an actual
        // set element rather than being silently dropped (2 distinct non-null values + null = 3).
        string joined = ledger.TagSet(new HashSet<string?> { "MYLO", null, "OREO" });

        Assert.Equal("MYLO,OREO,null", joined);
    }

    [Fact]
    public void ChartLedger_ScoreBoard_MapWithNullableIntValueParameter_NullRidesTheValueSlot()
    {
        using var ledger = new ChartLedger();

        string joined = ledger.ScoreBoard(new Dictionary<string, int?>
        {
            ["mylo"] = 9,
            ["oreo"] = null,
        });

        Assert.Equal("mylo=9;oreo=null", joined);
    }

    [Fact]
    public void ChartLedger_FileCharts_ListOfNullableChartIdParameter_RoundTripsNullAndRealElement()
    {
        using var ledger = new ChartLedger();

        // The motivating shape: a nullable value-class element composes ADR-081's underlying
        // projection with this ADR's null-pointer component slot in the same list.
        string joined = ledger.FileCharts(new List<ChartId?>
        {
            new ChartId("CH-OREO-1"),
            null,
            new ChartId("CH-MYLO-2"),
        });

        Assert.Equal("CH-OREO-1,null,CH-MYLO-2", joined);
    }

    [Fact]
    public void ChartLedger_MoodTrail_ListOfNullableTemperamentParameter_RoundTripsNullAndRealElement()
    {
        using var ledger = new ChartLedger();

        // Enum-underlying sibling of FileCharts's String-underlying cell: proves the C# write
        // side's `x == null ? (int?)null : (int)x.Value.Mood` pre-cast actually compiles and
        // crosses correctly, not just as generated text. Playful (ordinal 2, not the first Mood
        // entry) rules out a pre-cast that always sends 0 (Calm); null sits at a non-first index.
        string joined = ledger.MoodTrail(new List<Temperament?>
        {
            new Temperament(Mood.Playful),
            null,
            new Temperament(Mood.Anxious),
        });

        Assert.Equal("PLAYFUL,null,ANXIOUS", joined);
    }

    [Fact]
    public void ChartLedger_MissingTags_MethodReturn_NullAtNonFirstIndexDoesNotThrow()
    {
        using var ledger = new ChartLedger();

        IReadOnlyList<string?> tags = ledger.MissingTags();

        Assert.Equal(3, tags.Count);
        Assert.Equal("OREO", tags[0]);
        Assert.Null(tags[1]);
        Assert.Equal("MYLO", tags[2]);
    }

    [Fact]
    public void ChartLedger_VisitCounts_PropertyGetter_NullAtNonFirstIndexDoesNotThrow()
    {
        using var ledger = new ChartLedger();

        IReadOnlyList<int?> counts = ledger.VisitCounts;

        Assert.Equal(3, counts.Count);
        Assert.Equal(3, counts[0]);
        Assert.Null(counts[1]);
        Assert.Equal(5, counts[2]);
    }

    [Fact]
    public void ChartLedger_VisitCounts_PropertySetter_ArrivesAsGenuineNullNotAStrayZero()
    {
        using var ledger = new ChartLedger();

        ledger.VisitCounts = new List<int?> { 11, null, 13 };

        // Only readable Kotlin-side (ChartLedger.VisitCountsSummary) if the setter's null element
        // arrived as a genuine `null`, not a stray boxed zero or a dropped slot.
        Assert.Equal("11,null,13", ledger.VisitCountsSummary());
        Assert.Equal(3, ledger.VisitCounts.Count);
        Assert.Null(ledger.VisitCounts[1]);
    }

    [Fact]
    public void ChartLedger_ChartAssignments_MapReturn_DistinguishesPresentNullValueFromMissingKey()
    {
        using var ledger = new ChartLedger();

        IReadOnlyDictionary<string, ChartId?> assignments = ledger.ChartAssignments();

        Assert.Equal(2, assignments.Count);
        Assert.Equal("CH-OREO-1", assignments["oreo"]!.Value.Value);

        // Present key, null value.
        Assert.True(assignments.ContainsKey("mylo"));
        Assert.Null(assignments["mylo"]);

        // Missing key entirely -- must not be confused with the null-value case above.
        Assert.False(assignments.ContainsKey("felix"));
    }
}

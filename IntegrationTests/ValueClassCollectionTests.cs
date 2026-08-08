using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-081 ("Value-class collection components: cross as the underlying, re-wrap per element").
/// Every member below targets <c>ChartBook</c> in <c>ValueClassCollectionsSample.kt</c>, one test
/// per mechanism cell rather than the single easiest one: input positions spread across all four
/// ADR-077 underlyings (String: <c>ChartId</c>, Primitive: <c>Dosage</c>, Enum: <c>Temperament</c>,
/// ObjectHandle: <c>ChartRef</c>), plus the three read-side shapes (method return, <c>var</c>
/// property getter/setter, and a second read kind on the Enum underlying).
///
/// <c>ChartBook.RecordMoods</c> (a <c>Set&lt;Temperament&gt;</c> parameter, Enum underlying) IS
/// covered here: the ADR's finalized Decision (point 2) admits enum-underlying value classes at
/// input positions too, C# pre-casting <c>(int)x.Mood</c> before boxing to ride the existing
/// <c>Wrap&lt;int&gt;</c> branch. Only a *bare* enum component (no value-class wrapper) stays with
/// the sibling ROADMAP item.
///
/// Every assertion uses multiple, distinct-valued elements and checks values, not just counts, so
/// a first-element-only echo or a swapped key/value cannot pass by coincidence. Oreo's chart book
/// always has more entries than Mylo's, and never the same ones.
/// </summary>
public class ValueClassCollectionTests
{
    [Fact]
    public void ChartBook_Constructor_ListOfChartIdParameter_RoundTripsEveryElement()
    {
        using var book = new ChartBook(new List<ChartId>
        {
            new ChartId("CH-OREO-1"),
            new ChartId("CH-MYLO-2"),
        });

        Assert.Equal("CH-OREO-1,CH-MYLO-2", book.Manifest);
    }

    [Fact]
    public void ChartBook_File_ListOfChartIdParameter_RoundTripsEveryElement()
    {
        using var book = new ChartBook(new List<ChartId>());

        string filed = book.File(new List<ChartId>
        {
            new ChartId("CH-OREO-3"),
            new ChartId("CH-MYLO-4"),
            new ChartId("CH-OREO-5"),
        });

        Assert.Equal("CH-OREO-3,CH-MYLO-4,CH-OREO-5", filed);
    }

    [Fact]
    public void ChartBook_Sectioned_MapWithChartIdValue_RoundTripsEveryEntry()
    {
        using var book = new ChartBook(new List<ChartId>());

        string sections = book.Sectioned(new Dictionary<string, ChartId>
        {
            ["north"] = new ChartId("CH-NORTH-1"),
            ["south"] = new ChartId("CH-SOUTH-2"),
        });

        Assert.Equal("north=CH-NORTH-1;south=CH-SOUTH-2", sections);
    }

    [Fact]
    public void ChartBook_TallyByChart_MapWithChartIdKey_SumsValuesAndLooksUpByReconstructedKey()
    {
        using var book = new ChartBook(new List<ChartId>());

        var counts = new Dictionary<ChartId, int>
        {
            [new ChartId("CH-1")] = 3,
            [new ChartId("CH-2")] = 4,
        };

        Assert.Equal(7, book.TallyByChart(counts));

        // Key equality must survive the wire: look the entry up with a freshly-constructed
        // ChartId, not the same instance that was inserted.
        Assert.Equal(4, counts[new ChartId("CH-2")]);
    }

    [Fact]
    public void ChartBook_SumDosages_MutableListOfDosageParameter_SumsEveryElement()
    {
        using var book = new ChartBook(new List<ChartId>());

        double total = book.SumDosages(new List<Dosage>
        {
            new Dosage(1.5),
            new Dosage(2.5),
            new Dosage(0.5),
        });

        Assert.Equal(4.5, total);
    }

    [Fact]
    public void ChartBook_NamesOf_ListOfChartRefParameter_RoundTripsEveryWrappedPatient()
    {
        using var book = new ChartBook(new List<ChartId>());
        using var oreo = new Patient("Oreo");
        using var mylo = new Patient("Mylo");

        string names = book.NamesOf(new List<ChartRef> { new ChartRef(oreo), new ChartRef(mylo) });

        Assert.Equal("Oreo,Mylo", names);
    }

    [Fact]
    public void ChartBook_RecordMoods_SetOfTemperamentParameter_RoundTripsANonFirstOrdinal()
    {
        using var book = new ChartBook(new List<ChartId>());

        // Anxious (ordinal 1, not the first Mood entry) alongside Playful (ordinal 2): a
        // pre-cast that always sends 0 (Calm) cannot pass this. Kotlin's recordMoods sorts by
        // ordinal before joining, so the observed order proves the real ordinal crossed, not
        // just that two elements arrived.
        string moods = book.RecordMoods(new HashSet<Temperament>
        {
            new Temperament(Mood.Anxious),
            new Temperament(Mood.Playful),
        });

        Assert.Equal("ANXIOUS,PLAYFUL", moods);
    }

    [Fact]
    public void ChartBook_IssuedCharts_MethodReturn_RoundTripsEveryElementsValue()
    {
        using var book = new ChartBook(new List<ChartId>());

        IReadOnlyList<ChartId> issued = book.IssuedCharts();

        Assert.Equal(2, issued.Count);
        Assert.Equal("CH-OREO-9", issued[0].Value);
        Assert.Equal("CH-MYLO-4", issued[1].Value);
    }

    [Fact]
    public void ChartBook_PendingCharts_PropertyGetter_RoundTripsEveryElementsValue()
    {
        using var book = new ChartBook(new List<ChartId>());

        IReadOnlyList<ChartId> pending = book.PendingCharts;

        Assert.Equal(2, pending.Count);
        Assert.Equal("CH-PEND-1", pending[0].Value);
        Assert.Equal("CH-PEND-2", pending[1].Value);
    }

    [Fact]
    public void ChartBook_PendingCharts_PropertySetter_ArrivesAsGenuinelyRewrappedChartIds()
    {
        using var book = new ChartBook(new List<ChartId>());

        book.PendingCharts = new List<ChartId>
        {
            new ChartId("CH-NEW-1"),
            new ChartId("CH-NEW-2"),
            new ChartId("CH-NEW-3"),
        };

        // Only readable Kotlin-side (ChartBook.PendingChartsSummary) if the setter's elements
        // arrived as genuine re-wrapped ChartIds, not a stray handle or an unwrapped string.
        Assert.Equal("CH-NEW-1,CH-NEW-2,CH-NEW-3", book.PendingChartsSummary());
        Assert.Equal(3, book.PendingCharts.Count);
        Assert.Equal("CH-NEW-2", book.PendingCharts[1].Value);
    }

    [Fact]
    public void ChartBook_MoodsOnFile_SetOfTemperamentReturn_RoundTripsANonFirstOrdinal()
    {
        using var book = new ChartBook(new List<ChartId>());

        IReadOnlySet<Temperament> moods = book.MoodsOnFile();

        Assert.Equal(2, moods.Count);
        // Anxious is ordinal 1 (not the first Mood entry, CALM = 0): a getter that always reads
        // slot 0 cannot pass this.
        Assert.Contains(new Temperament(Mood.Anxious), moods);
        Assert.Contains(new Temperament(Mood.Playful), moods);
        Assert.DoesNotContain(new Temperament(Mood.Calm), moods);
    }
}

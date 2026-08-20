using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-097 ("Forward, enum collection components: the int ordinal in the component slot, and
/// <c>List</c>'s input gate narrowed to what the write side can box"). Every member below targets
/// <c>MoodLedger</c> in <c>EnumComponentCollectionsSample.kt</c>, one test per mechanism cell
/// rather than the single easiest one: a <c>List</c> input, a <c>Set</c> input, a <c>Map</c> with
/// the enum in the VALUE slot beside a conversion-free <c>string</c> key, a <c>Map</c> with the
/// enum in the KEY slot, a nullable enum element, and both read-side shapes (method return and a
/// <c>Map</c> return).
///
/// A bare <c>Mood</c> component is bind-then-throw in BOTH directions today: the write side has no
/// enum branch in <c>Wrap&lt;T&gt;</c>, the read side falls through <c>FromHandle&lt;Mood&gt;</c>
/// to <c>Materialize</c> with no <c>Factories</c> entry. ADR-081 already ships this exact wire one
/// level down (<c>Set&lt;Temperament&gt;</c>, a value class over <c>Mood</c>), so every cell here
/// is that wire minus the wrapper.
///
/// Every assertion uses multiple distinct moods and pins at least one non-zero ordinal, so a
/// projection that always sends <c>0</c> (<c>Calm</c>, the first entry) cannot pass by coincidence.
/// Oreo is the calm one; Mylo is not.
///
/// The last cell, <c>LogSpans</c>, was ADR-097's asserted-absent gate-narrowing cell and is
/// inverted by ADR-098 part A into a working round trip. It stays here rather than moving to
/// <c>NarrowComponentCollectionTests</c> so the inversion sits where the assertion it replaces was.
/// </summary>
public class EnumComponentCollectionTests
{
    [Fact]
    public void MoodLedger_LogMoods_ListOfEnumParameter_RoundTripsEveryElement()
    {
        using var ledger = new MoodLedger();

        // The restatement's exact shape. Calm repeats at a non-adjacent index, so a projection
        // that de-duplicated or reordered would not produce this string.
        string logged = ledger.LogMoods(new[] { Mood.Calm, Mood.Playful, Mood.Calm });

        Assert.Equal("CALM,PLAYFUL,CALM", logged);
    }

    [Fact]
    public void MoodLedger_TallyMoods_SetOfEnumParameter_RoundTripsEveryElement()
    {
        using var ledger = new MoodLedger();

        // Different native export and a different Kotlin lowering (`mapTo(mutableSetOf())`) than
        // LogMoods. Kotlin sorts by name before joining, so the observed order proves both
        // elements crossed rather than one echoing twice.
        string tally = ledger.TallyMoods(new HashSet<Mood> { Mood.Calm, Mood.Anxious });

        Assert.Equal("ANXIOUS,CALM", tally);
    }

    [Fact]
    public void MoodLedger_ChartMoods_MapWithEnumValueAndStringKey_RoundTripsEveryEntry()
    {
        using var ledger = new MoodLedger();

        // Mixed projection in one collection: the value slot needs conversion (Mood -> int), the
        // key slot does not. Oreo's Calm and Mylo's Anxious are distinct ordinals, and the Kotlin
        // sort by key puts Mylo first, so a swapped key/value or a copied value cannot pass.
        string chart = ledger.ChartMoods(new Dictionary<string, Mood>
        {
            ["oreo"] = Mood.Calm,
            ["mylo"] = Mood.Anxious,
        });

        Assert.Equal("mylo=ANXIOUS;oreo=CALM", chart);
    }

    [Fact]
    public void MoodLedger_MoodRoster_MapWithEnumKey_RoundTripsEveryEntry()
    {
        using var ledger = new MoodLedger();

        // KEY position, which projects independently of the value slot ChartMoods covers. Kotlin
        // sorts by ordinal, so Calm (0) precedes Playful (2) regardless of insertion order here.
        string roster = ledger.MoodRoster(new Dictionary<Mood, string>
        {
            [Mood.Playful] = "mylo",
            [Mood.Calm] = "oreo",
        });

        Assert.Equal("CALM=oreo;PLAYFUL=mylo", roster);
    }

    [Fact]
    public void MoodLedger_LogMoodTrail_ListOfNullableEnumParameter_RoundTripsNullAndRealElement()
    {
        using var ledger = new MoodLedger();

        // isWrappableComponent's Nullable branch admits this automatically the moment Enum is
        // allowed, so it needs its own write and read arms. Without them the Kotlin lowering casts
        // a boxed Int to `Mood?` and ClassCastExceptions -- a runtime failure neither compiler
        // catches. The null sits at a non-first index and Playful (ordinal 2) is not the default.
        string trail = ledger.LogMoodTrail(new Mood?[] { Mood.Calm, null, Mood.Playful });

        Assert.Equal("CALM,null,PLAYFUL", trail);
    }

    [Fact]
    public void MoodLedger_MoodsOnFile_ListOfEnumReturn_MaterializesEveryElement()
    {
        using var ledger = new MoodLedger();

        // Read side. Binds today and throws NotSupportedException out of FromHandle<Mood> ->
        // Materialize at the first read -- a shipped bug, not a missing capability.
        IReadOnlyList<Mood> moods = ledger.MoodsOnFile();

        Assert.Equal(new[] { Mood.Calm, Mood.Playful }, moods);
    }

    [Fact]
    public void MoodLedger_MoodChart_MapWithEnumValueReturn_MaterializesEveryEntry()
    {
        using var ledger = new MoodLedger();

        // Second read kind: the enum sits in the VALUE slot, whose read projection is distinct
        // from MoodsOnFile's element slot.
        IReadOnlyDictionary<string, Mood> chart = ledger.MoodChart();

        Assert.Equal(2, chart.Count);
        Assert.Equal(Mood.Calm, chart["oreo"]);
        Assert.Equal(Mood.Anxious, chart["mylo"]);
    }

    [Fact]
    public void MoodLedger_LogSpans_ListOfShortParameter_RoundTripsEveryElement()
    {
        using var ledger = new MoodLedger();

        // ADR-097 section 3 asserted this member ABSENT: narrowing List's callable-input gate to
        // isWrappableComponent() turned a `List<Short>` parameter from bind-then-throw into a named
        // SKIPPED_UNSUPPORTED_INPUT. ADR-098 part A adds SHORT to that same predicate and mints
        // nuget_wrap_short, so the member comes back, this time callable. The inversion is the
        // point: the gate is one predicate, and widening it is how a skip becomes a capability.
        //
        // The matching Tier 1 assertion inverts with this one
        // (Tier1EnumCollectionComponentTest.kt, the `"export_moodledger_logSpans" !in kotlin` and
        // SKIPPED_UNSUPPORTED_INPUT cell).
        string spans = ledger.LogSpans(new short[] { 7, -1, short.MinValue });

        Assert.Equal("7,-1,-32768", spans);
    }
}

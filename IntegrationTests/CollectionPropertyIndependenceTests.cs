using TestLibrary.Clinic;

namespace IntegrationTests;

// ADR-075 ("Forward — plan collection property getters and setters independently"). Two
// ROADMAP items land together here: a `var` collection property used to lose its getter
// entirely, and a nullable collection property (`val` or `var`) used to crash `packNuget`
// outright. Oreo and Mylo are, as ever, the patients on file.
public class CollectionPropertyIndependenceTests
{
    // --- Getter facet: Visit.Notes, a nullable List<String> with a conversion-free element ---
    // (Visit.Symptoms is the non-null regression guard riding along for free.)

    [Fact]
    public void Visit_Symptoms_NonNullCollection_StillMaterializes()
    {
        using var visit = new Visit("Oreo", new List<string> { "purring", "hungry" }, null);

        Assert.Equal(new[] { "purring", "hungry" }, visit.Symptoms);
    }

    [Fact]
    public void Visit_Notes_WhenProvided_MaterializesElements()
    {
        using var visit = new Visit(
            "Oreo",
            new List<string> { "purring" },
            new List<string> { "watch weight" });

        Assert.NotNull(visit.Notes);
        Assert.Equal(new[] { "watch weight" }, visit.Notes);
    }

    [Fact]
    public void Visit_Notes_WhenNull_IsNullNotAStrayHandle()
    {
        using var visit = new Visit("Mylo", new List<string> { "sneezing" }, null);

        // This is the actual reported bug: a null Kotlin handle must come back as C# `null`,
        // not a boxed IntPtr or an empty list.
        Assert.Null(visit.Notes);
    }

    // --- Getter facet: Roster.Staff, a nullable List<Nurse> whose element itself needs
    // conversion through FromHandle<T>, unlike Visit.Notes' raw String element ---

    [Fact]
    public void Roster_Staff_WithAttendingNurse_MaterializesTheObjectHandleElement()
    {
        using var nightingale = new Nurse("Nightingale");
        using var roster = new Roster(nightingale);

        Assert.NotNull(roster.Staff);
        using Nurse attending = Assert.Single(roster.Staff!);
        Assert.Equal("Nightingale", attending.Name);
    }

    [Fact]
    public void Roster_Staff_WithNoAttendingNurse_IsNull()
    {
        using var roster = new Roster(null);

        Assert.Null(roster.Staff);
    }

    // --- Setter facet: Chart, the `needsCollectionParamWrap` regression cell. Every eligible
    // setter lowering shape, once each. ---

    [Fact]
    public void Chart_Tags_ListOfString_RoundTrips()
    {
        using var chart = new Chart("Oreo");

        chart.Tags = new List<string> { "black", "white", "biscuit" };

        Assert.Equal(new[] { "black", "white", "biscuit" }, chart.Tags);
    }

    [Fact]
    public void Chart_Counts_MapOfStringToInt_RoundTrips()
    {
        using var chart = new Chart("Mylo");

        chart.Counts = new Dictionary<string, int> { ["visits"] = 3, ["treats"] = 12 };

        Assert.Equal(3, chart.Counts["visits"]);
        Assert.Equal(12, chart.Counts["treats"]);
    }

    [Fact]
    public void Chart_Codes_SetOfString_RoundTrips()
    {
        using var chart = new Chart("Oreo");

        chart.Codes = new HashSet<string> { "creamy", "purrs-loud" };

        Assert.Equal(2, chart.Codes.Count);
        Assert.Contains("creamy", chart.Codes);
        Assert.Contains("purrs-loud", chart.Codes);
    }

    [Fact]
    public void Chart_Seen_MutableListOfNurse_ReadModifyWrite_RoundTrips()
    {
        using var chart = new Chart("Mylo");
        using var nightingale = new Nurse("Nightingale");
        using var barton = new Nurse("Barton");

        chart.Seen = new List<Nurse> { nightingale };

        // The getter returns a detached copy per ADR-011: mutating it in place would never reach
        // the Kotlin var. Read, extend on the C# side, and reassign instead.
        var attending = new List<Nurse>(chart.Seen) { barton };
        chart.Seen = attending;

        IList<Nurse> current = chart.Seen;
        Assert.Equal(2, current.Count);
        Assert.Equal("Nightingale", current[0].Name);
        Assert.Equal("Barton", current[1].Name);
    }

    [Fact]
    public void Chart_Notes_AssignedList_RoundTrips()
    {
        using var chart = new Chart("Oreo");

        chart.Notes = new List<string> { "watch weight" };

        Assert.NotNull(chart.Notes);
        Assert.Equal(new[] { "watch weight" }, chart.Notes);
    }

    [Fact]
    public void Chart_Notes_AssignedNull_RoundTrips()
    {
        using var chart = new Chart("Mylo");
        chart.Notes = new List<string> { "temporary" };
        Assert.NotNull(chart.Notes);

        chart.Notes = null;

        Assert.Null(chart.Notes);
    }

    // --- Setter facet: ineligible shapes fall back to a get-only C# property. Trap: reading
    // Moods or Aliases is a pre-existing, unrelated bug (NugetMarshal.FromHandle<T> has no enum
    // branch, and the element itself is what makes Aliases ineligible in the first place), so
    // these assert *shape only* via reflection and never call the getter. ---

    [Fact]
    public void Chart_Moods_EnumElement_HasNoPublicSetter()
    {
        var property = typeof(Chart).GetProperty(nameof(Chart.Moods));

        Assert.NotNull(property);
        Assert.NotNull(property!.GetGetMethod());
        Assert.Null(property.GetSetMethod());
    }

    [Fact]
    public void Chart_Aliases_NullableElement_HasNoPublicSetter()
    {
        var property = typeof(Chart).GetProperty(nameof(Chart.Aliases));

        Assert.NotNull(property);
        Assert.NotNull(property!.GetGetMethod());
        Assert.Null(property.GetSetMethod());
    }

    // --- Setter facet: an extension property whose receiver crosses the bridge by value
    // (ChartId's underlying string), not by ObjectHandle. Same eligibility predicate as Chart's
    // setters, on ground nobody had exercised before. ---

    [Fact]
    public void ChartId_SymptomTags_RoundTrips()
    {
        var chartId = new ChartId("oreo-chart-1");

        chartId.SetSymptomTags(new List<string> { "sneezing", "purring" });

        Assert.Equal(new[] { "sneezing", "purring" }, chartId.GetSymptomTags());
    }

    [Fact]
    public void ChartId_SymptomTags_DefaultsToEmpty()
    {
        var chartId = new ChartId("mylo-chart-1");

        Assert.Empty(chartId.GetSymptomTags());
    }
}

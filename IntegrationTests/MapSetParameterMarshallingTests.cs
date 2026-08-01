using TestLibrary.Clinic;

namespace IntegrationTests;

// ADR-073 ("Map/Set (and mutable variants) as method parameters"). Forward direction: a Kotlin
// `Map<K,V>`, `MutableMap<K,V>`, `Set<T>` or `MutableSet<T>` *parameter*, mirroring the return
// position mapping already shipped (`csharpType()`): Map -> IReadOnlyDictionary<K,V>,
// MutableMap -> IDictionary<K,V>, Set -> IReadOnlySet<T>, MutableSet -> ISet<T>. Covers all five
// call positions (class method, companion method, top-level function, extension function,
// constructor) plus the object-handle map-value cell and the two no-write-back regressions the
// ADR calls out explicitly. Oreo and Mylo are the patients doing the heavy lifting, as usual.
public class MapSetParameterMarshallingTests
{
    // Class method x Map<String, Int> parameter: String key, Int value.
    [Fact]
    public void Patient_RecordScores_SumsTheValues()
    {
        using var oreo = new Patient("Oreo");

        int total = oreo.RecordScores(new Dictionary<string, int> { ["agility"] = 3, ["cuddles"] = 4 });

        Assert.Equal(7, total);
    }

    [Fact]
    public void Patient_RecordScores_WithEmptyDictionary_ReturnsZero()
    {
        using var oreo = new Patient("Oreo");

        Assert.Equal(0, oreo.RecordScores(new Dictionary<string, int>()));
    }

    // Class method x MutableMap<String, Int> parameter. ADR-073 no-write-back regression #1: the
    // Kotlin body puts "total" into its copy, but Oreo's own Dictionary must come back untouched.
    [Fact]
    public void Patient_TallyScores_MutatesKotlinsCopyOnly_CallersDictionaryIsUnchanged()
    {
        using var oreo = new Patient("Oreo");
        var scores = new Dictionary<string, int> { ["agility"] = 3 };

        int tally = oreo.TallyScores(scores);

        Assert.Equal(2, tally);                    // "agility" + the "total" Kotlin added to its copy
        Assert.Single(scores);                      // still just "agility", no write-back
        Assert.False(scores.ContainsKey("total"));
        Assert.Equal(3, scores["agility"]);
    }

    // Class method x Map<String, Patient> parameter: the object-handle map-value cell. This is the
    // one path the ADR flags as unverified on the C# side (CreateMap's reflective `_handle`
    // fallback for the *value* position of a map).
    [Fact]
    public void Patient_LinkWard_CountsPatientsWithNonBlankNames()
    {
        using var oreo = new Patient("Oreo");
        using var mylo = new Patient("Mylo");
        using var stray = new Patient("");

        int linked = oreo.LinkWard(new Dictionary<string, Patient>
        {
            ["north"] = mylo,
            ["stray"] = stray,
        });

        Assert.Equal(1, linked);
    }

    // Class method x Set<String> parameter.
    [Fact]
    public void Patient_AddLabels_ReturnsTheCount()
    {
        using var oreo = new Patient("Oreo");

        Assert.Equal(2, oreo.AddLabels(new HashSet<string> { "black", "white" }));
    }

    [Fact]
    public void Patient_AddLabels_WithEmptySet_ReturnsZero()
    {
        using var oreo = new Patient("Oreo");

        Assert.Equal(0, oreo.AddLabels(new HashSet<string>()));
    }

    // Class method x MutableSet<Int> parameter. ADR-073 no-write-back regression #2: Kotlin adds 0
    // to its copy, but Mylo's own HashSet must come back untouched.
    [Fact]
    public void Patient_AddCodes_MutatesKotlinsCopyOnly_CallersSetIsUnchanged()
    {
        using var mylo = new Patient("Mylo");
        var codes = new HashSet<int> { 7, 8 };

        int count = mylo.AddCodes(codes);

        Assert.Equal(3, count);       // 7, 8, plus the 0 Kotlin added to its own copy
        Assert.Equal(2, codes.Count); // still just 7 and 8, no write-back
        Assert.DoesNotContain(0, codes);
    }

    // Companion method x Map<String, Int> parameter, mirroring Patient.BatchAdmit's coverage of
    // the static call position.
    [Fact]
    public void Patient_BatchScore_ReturnsTheCount()
    {
        Assert.Equal(2, Patient.BatchScore(new Dictionary<string, int> { ["Oreo"] = 9, ["Mylo"] = 7 }));
    }

    // Top-level function x Set<String> parameter.
    [Fact]
    public void ClinicSample_CountLabels_ReturnsTheCount()
    {
        Assert.Equal(2, ClinicSample.countLabels(new HashSet<string> { "indoor", "calm" }));
    }

    // Extension function x Map<String, Int> parameter, over an object-handle receiver.
    [Fact]
    public void Patient_MergeScores_ReturnsTheCount()
    {
        using var oreo = new Patient("Oreo");

        Assert.Equal(2, oreo.MergeScores(new Dictionary<string, int> { ["a"] = 1, ["b"] = 2 }));
    }

    // Constructor x Set<String> parameter.
    [Fact]
    public void Ward_Constructor_MarshalsSetParameter()
    {
        using var ward = new Ward("East Wing", new HashSet<string> { "quiet", "sunny" });

        Assert.Equal("East Wing", ward.Name);
        Assert.Equal(2, ward.Tags.Count);
        Assert.Contains("quiet", ward.Tags);
    }

    // A caller holding a plain BCL Dictionary/HashSet must be directly assignable at every kind
    // (ADR-073 Decision table). This test is really a compile-time assertion: if it compiles and
    // runs, the assignability claim holds for all four call shapes exercised above.
    [Fact]
    public void PlainDictionaryAndHashSet_AreAssignableAtEveryParameterKind()
    {
        using var oreo = new Patient("Oreo");

        Dictionary<string, int> readOnlyMapArg = new() { ["x"] = 1 };
        Dictionary<string, int> mutableMapArg = new() { ["x"] = 1 };
        HashSet<string> readOnlySetArg = new() { "x" };
        HashSet<int> mutableSetArg = new() { 1 };

        Assert.Equal(1, oreo.RecordScores(readOnlyMapArg));
        Assert.Equal(2, oreo.TallyScores(mutableMapArg));
        Assert.Equal(1, oreo.AddLabels(readOnlySetArg));
        Assert.Equal(2, oreo.AddCodes(mutableSetArg));
    }
}

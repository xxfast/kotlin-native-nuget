using TestLibrary.Cat;
using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// MIGRATION.md consumer-side coverage gate: null/non-null paths, disposal, enum, Char,
/// nullable parameters, Map, Set, and value-class method parameters. Individual suites own the
/// deep cases; this class is the checklist that each category is exercised end-to-end.
/// </summary>
public class MarshallingCoverageTests
{
    [Fact]
    public void Disposal_PatientUsingBlock_ReleasesHandle()
    {
        Patient patient = new Patient("Oreo");
        patient.Dispose();
        // A second dispose must be safe (IDisposable convention).
        patient.Dispose();
    }

    [Fact]
    public void NullableProperty_String_NullAndNonNullPaths()
    {
        using var patient = new Patient("Oreo");
        Assert.Null(patient.Nickname);
        patient.Nickname = "O";
        Assert.Equal("O", patient.Nickname);
        patient.Nickname = null;
        Assert.Null(patient.Nickname);
    }

    [Fact]
    public void NullableProperty_Int_NullAndNonNullPaths()
    {
        using var patient = new Patient("Oreo");
        Assert.Null(patient.Weight);
        patient.Weight = 9;
        Assert.Equal(9, patient.Weight);
        patient.Weight = null;
        Assert.Null(patient.Weight);
    }

    [Fact]
    public void NullableParameter_String_NullAndNonNullPaths()
    {
        using var patient = new Patient("Oreo");
        // rename does not mutate `name`; null falls back to the original constructor name.
        Assert.Equal("Mylo", patient.Rename("Mylo"));
        Assert.Equal("Oreo", patient.Rename(null));
    }

    [Fact]
    public void Char_PropertyParameterAndReturn()
    {
        using var patient = new Patient("Oreo");
        Assert.Equal('A', patient.Grade);
        Assert.Equal('O', patient.Initial());
        Assert.Equal("X-Oreo", patient.Tag('X'));
    }

    [Fact]
    public void Enum_PropertyParameterAndMethodReturn()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Equal(TestLibrary.Cat.Mood.Sleepy, cat.Mood);
        cat.Mood = TestLibrary.Cat.Mood.Happy;
        Assert.Equal(TestLibrary.Cat.Mood.Happy, cat.Mood);

        using var patient = new Patient("Oreo");
        Assert.Equal(TestLibrary.Clinic.Mood.Calm, patient.Mood());
        Assert.Equal(0, patient.DescribeMood(TestLibrary.Clinic.Mood.Calm));
    }

    [Fact]
    public void Map_PropertyAndMethodReturn()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.True(cat.Accessories.ContainsKey("collar"));

        using var patient = new Patient("Oreo");
        patient.AdjustWeight(4);
        Assert.Equal(4, patient.Scores()["weight"]);
    }

    [Fact]
    public void Set_PropertyAndMethodReturn()
    {
        using var cat = new Cat("Oreo", 9);
        Assert.Contains("Playful", cat.Traits);

        using var patient = new Patient("Oreo");
        Assert.Contains("Oreo", patient.Labels());
    }

    [Fact]
    public void List_Parameter()
    {
        using var patient = new Patient("Oreo");
        Assert.Equal(2, patient.AddTags(new List<string> { "a", "b" }));
    }

    [Fact]
    public void ValueClass_MethodParameters_BothUnderlyingBranches()
    {
        var id = new ChartId("abc");
        Assert.True(id.Matches("abc"));
        Assert.False(id.Matches("xyz"));

        using var patient = new Patient("Rex");
        var chart = new ChartRef(patient);
        Assert.Equal("Rex-ward", chart.Label("-ward"));
    }

    [Fact]
    public void ObjectHandle_NullableParameter_NullAndNonNullPaths()
    {
        using var patient = new Patient("Oreo");
        using var buddy = new Patient("Mylo");
        Assert.Equal(1, patient.Attach(buddy));
        Assert.NotNull(patient.Buddy);
        Assert.Equal(0, patient.Attach(null));
        Assert.Null(patient.Buddy);
    }
}

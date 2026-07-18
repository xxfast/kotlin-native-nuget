using TestLibrary.Clinic;

// MIGRATION.md Phase 8 — remaining synchronous categories: Char/enum returns, Map/Set materialization,
// and Char properties that previously vanished from the C# API surface.
public class ReturnAndPropertyMarshallingTests
{
    [Fact]
    public void Patient_Grade_IsCharProperty()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal('A', patient.Grade);
    }

    [Fact]
    public void Patient_Initial_ReturnsFirstCharacter()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal('O', patient.Initial());
    }

    [Fact]
    public void Patient_Mood_ReturnsCalm()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal(Mood.Calm, patient.Mood());
    }

    [Fact]
    public void Patient_Scores_ReturnsWeightMap()
    {
        using var patient = new Patient("Oreo");
        patient.AdjustWeight(7);

        var scores = patient.Scores();

        Assert.Single(scores);
        Assert.Equal(7, scores["weight"]);
    }

    [Fact]
    public void Patient_Labels_ReturnsNameSet()
    {
        using var patient = new Patient("Oreo");

        var labels = patient.Labels();

        Assert.Single(labels);
        Assert.Contains("Oreo", labels);
    }
}

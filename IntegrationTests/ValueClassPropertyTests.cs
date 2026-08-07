using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-077 sub-item 2: a String-underlying value-class-typed <c>val</c>/<c>var</c> property
/// surfaces as a C# property of the generated record struct type. Today
/// <c>ForwardPropertyPlanner.isPlannable</c> has no <c>ValueClass</c> branch, so both properties
/// here are silently dropped and these tests fail with CS1061 on <c>Id</c> / <c>CurrentChart</c>.
///
/// Expected generated surface per the ADR: the getter reconstructs with
/// <c>new ChartId(Marshal.PtrToStringUTF8(...))</c>; the setter passes <c>value.Value</c> over the
/// string wire and Kotlin re-wraps with <c>ChartId(raw)</c>.
///
/// Scope ceiling: non-null String-underlying only. <c>ChartId?</c> is sub-item 3 (the ADR keeps
/// <c>Nullable(ValueClass)</c> guarded out in this slice), non-String underlyings are sub-item 4,
/// so nothing here asserts on either. Oreo keeps losing his chart; Mylo files his promptly.
/// </summary>
public class ValueClassPropertyTests
{
    [Fact]
    public void ChartEntry_Id_ValGetter_ReturnsTheRecordStructWithTheConstructedValue()
    {
        using var entry = new ChartEntry(new ChartId("CH-OREO-4"), "stuck in a vase");

        ChartId id = entry.Id;

        Assert.Equal("CH-OREO-4", id.Value);
        Assert.Equal(new ChartId("CH-OREO-4"), id);
    }

    [Fact]
    public void Patient_CurrentChart_Getter_ReturnsTheKotlinInitializerValue()
    {
        using var mylo = new Patient("Mylo");

        Assert.Equal("CH-0", mylo.CurrentChart.Value);
    }

    [Fact]
    public void Patient_CurrentChart_VarSetterAndGetter_RoundTripTheRecordStruct()
    {
        using var oreo = new Patient("Oreo");

        oreo.CurrentChart = new ChartId("CH-2");

        Assert.Equal("CH-2", oreo.CurrentChart.Value);
    }

    [Fact]
    public void Patient_CurrentChart_Setter_IsObservedByKotlinAsARewrappedChartId()
    {
        using var oreo = new Patient("Oreo");

        // chartStatus() calls isValid() on the stored ChartId, so both branches only make sense
        // if the C# write arrived re-wrapped as a genuine value class, not a smuggled raw string.
        oreo.CurrentChart = new ChartId("CH-9");
        Assert.Equal("Oreo charted at CH-9", oreo.ChartStatus());

        oreo.CurrentChart = new ChartId("   ");
        Assert.Equal("Oreo uncharted", oreo.ChartStatus());
    }
}

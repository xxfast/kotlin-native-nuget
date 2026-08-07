using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-077 sub-item 1: a String-underlying value class at an ordinary *parameter* position. Today
/// every callable below is dropped whole (<c>ForwardPlanSkipReason.VALUE_CLASS</c>), so none of
/// these members exist in the generated C# yet.
///
/// One test per parameter position, because all five funnel through <c>planOrSkip</c> →
/// <c>nativeInputParameters</c> and a fixture trimmed to the easiest one would prove nothing about
/// the other four. The consumer always passes <c>new ChartId("...")</c>; the generated wrapper
/// hands <c>id.Value</c> to the string wire and Kotlin re-wraps it with <c>ChartId(raw)</c>.
///
/// The value-class *property* facet is sub-item 2 and stays skipped, so nothing here reads a
/// generated <c>ChartEntry.Id</c>: every assertion goes through an ordinary <c>string</c> member.
///
/// Oreo and Mylo are, as always, the clinic's most-charted patients.
/// </summary>
public class ValueClassParameterTests
{
    [Fact]
    public void Patient_Retag_ClassMethodParameter_RoundTripsTheUnwrappedChartId()
    {
        using var oreo = new Patient("Oreo");

        // Kotlin's `retag` calls `id.isValid()`, so a blank underlying takes the other branch:
        // proof the parameter arrived as a re-wrapped ChartId, not just a raw string.
        Assert.Equal("Oreo@CH-OREO-1", oreo.Retag(new ChartId("CH-OREO-1")));
        Assert.Equal("Oreo@untagged", oreo.Retag(new ChartId("   ")));
    }

    [Fact]
    public void Admission_Constructor_ValueClassParameter_RoundTripsTheUnwrappedChartId()
    {
        // Plain (non-`val`) constructor parameter, so this is the constructor seam only.
        using var admission = new Admission(new ChartId("CH-MYLO-7"), "sunbeam");

        Assert.Equal("CH-MYLO-7/sunbeam", admission.Label);
        Assert.Equal("sunbeam", admission.Ward);
    }

    [Fact]
    public void ChartEntry_PrimaryConstructorAndCopy_RoundTripTheUnwrappedChartId()
    {
        using var entry = new ChartEntry(new ChartId("CH-OREO-2"), "chipped a claw");

        Assert.Equal("CH-OREO-2: chipped a claw", entry.Label());

        // The generated copy() carries the same value-class parameter as the primary constructor.
        using ChartEntry rechecked = entry.Copy(new ChartId("CH-MYLO-3"), "still purring");

        Assert.Equal("CH-MYLO-3: still purring", rechecked.Label());
        Assert.Equal("CH-OREO-2: chipped a claw", entry.Label());
    }

    [Fact]
    public void Patient_ChartLabel_ExtensionFunctionParameter_RoundTripsTheUnwrappedChartId()
    {
        using var mylo = new Patient("Mylo");

        Assert.Equal("Mylo reads CH-MYLO-9", mylo.ChartLabel(new ChartId("CH-MYLO-9")));
    }

    [Fact]
    public void ClinicSample_ChartSummary_TopLevelFunctionParameter_RoundTripsTheUnwrappedChartId()
    {
        // Top-level functions keep Kotlin camelCase (ADR-007), unlike the extension above.
        Assert.Equal("Chart CH-CLINIC-0 filed", ClinicSample.chartSummary(new ChartId("CH-CLINIC-0")));
        Assert.Equal("Chart missing", ClinicSample.chartSummary(new ChartId("")));
    }
}

using TestLibrary;
using TestLibrary.Clinic;

namespace IntegrationTests;

// Phase 6 characterisation: these callables have independent legacy routes today. The consumer
// surface stays fixed while their Kotlin exports and C# wrappers move to shared planning.
public class OrdinaryCallableFamilyMarshallingTests
{
    [Fact]
    public void Clinic_Intake_ReturnsMarshalledPatient()
    {
        using Patient patient = Clinic.Intake("Oreo");

        Assert.Equal("Oreo", patient.Name);
    }

    [Fact]
    public void ClinicSample_Admit_ReturnsMarshalledPatient()
    {
        using Patient patient = ClinicSample.Admit("Mylo");

        Assert.Equal("Mylo", patient.Name);
    }

    [Fact]
    public void Mappings_NullableIntProbe_CallsKotlinExactlyOnce()
    {
        Mappings.ResetNullableIntProbe();

        // ADR-170: the top-level nullable scalar is one native call now, not ADR-002's
        // `_has_value` + `_value` pair that ran the Kotlin function twice.
        Assert.Equal(42, Mappings.NullableIntProbe());
        Assert.Equal(1, Mappings.NullableIntProbeCallCount());
    }
}

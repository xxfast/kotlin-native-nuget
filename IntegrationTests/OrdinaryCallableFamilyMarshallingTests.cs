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
        using Patient patient = ClinicSample.admit("Mylo");

        Assert.Equal("Mylo", patient.Name);
    }

    [Fact]
    public void Mappings_NullableIntProbe_PreservesTopLevelTwoCallConvention()
    {
        Mappings.resetNullableIntProbe();

        Assert.Equal(42, Mappings.nullableIntProbe());
        Assert.Equal(2, Mappings.nullableIntProbeCallCount());
    }
}

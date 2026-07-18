using TestLibrary.Clinic;

namespace IntegrationTests;

// MIGRATION.md Phase 7 ("Input positions"). One case per newly-migrated input BridgeType, each
// with a null and non-null (or otherwise-varying) path per the test gate: "Consumer-side tests
// cover null and non-null paths, disposal, enum, Char, nullable parameters... before each
// corresponding behavior fix." See test-library's clinic/ClinicSample.kt for the Kotlin fixtures
// these exercise, each commented with the seam it crosses.
public class MethodParameterMarshallingTests
{
    [Fact]
    public void Patient_EchoName_MarshalsStringParameterAndReturn()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal("Mylo", patient.EchoName("Mylo"));
    }

    // Cell 8: nullable String parameter, class method.
    [Fact]
    public void Patient_Rename_WithValue_ReturnsTheNewName()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal("Mylo", patient.Rename("Mylo"));
    }

    [Fact]
    public void Patient_Rename_WithNull_ReturnsTheOriginalName()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal("Oreo", patient.Rename(null));
    }

    // Cell 12: Char parameter, class method.
    [Fact]
    public void Patient_Tag_MarshalsCharParameter()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal("O-Oreo", patient.Tag('O'));
    }

    // Nullable Int parameter, object method.
    [Fact]
    public void Clinic_SetCapacity_WithValue_ReturnsTheValue()
    {
        Assert.Equal(5, Clinic.SetCapacity(5));
    }

    [Fact]
    public void Clinic_SetCapacity_WithNull_ReturnsZero()
    {
        Assert.Equal(0, Clinic.SetCapacity(null));
    }

    // Nullable object-handle parameter, class method.
    [Fact]
    public void Patient_Attach_WithBuddy_SetsBuddyAndReturnsOne()
    {
        using var patient = new Patient("Oreo");
        using var buddy = new Patient("Mylo");

        Assert.Equal(1, patient.Attach(buddy));
        Assert.NotNull(patient.Buddy);
    }

    [Fact]
    public void Patient_Attach_WithNull_ClearsBuddyAndReturnsZero()
    {
        using var patient = new Patient("Oreo");
        using var buddy = new Patient("Mylo");
        patient.Attach(buddy);

        Assert.Equal(0, patient.Attach(null));
        Assert.Null(patient.Buddy);
    }

    // Nullable Int parameter, class method — also proves the mutation accumulates across calls.
    [Fact]
    public void Patient_AdjustWeight_WithValue_AccumulatesAcrossCalls()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal(4, patient.AdjustWeight(4));
        Assert.Equal(9, patient.AdjustWeight(5));
    }

    [Fact]
    public void Patient_AdjustWeight_WithNull_LeavesWeightUnchanged()
    {
        using var patient = new Patient("Oreo");
        patient.AdjustWeight(4);

        Assert.Equal(4, patient.AdjustWeight(null));
    }

    // Enum parameter, class method.
    [Fact]
    public void Patient_DescribeMood_ReturnsTheOrdinal()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal(0, patient.DescribeMood(Mood.Calm));
        Assert.Equal(1, patient.DescribeMood(Mood.Anxious));
        Assert.Equal(2, patient.DescribeMood(Mood.Playful));
    }

    // List<string> parameter, class method.
    [Fact]
    public void Patient_AddTags_ReturnsTheCount()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal(3, patient.AddTags(new List<string> { "calm", "friendly", "indoor" }));
    }

    [Fact]
    public void Patient_AddTags_WithEmptyList_ReturnsZero()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal(0, patient.AddTags(new List<string>()));
    }

    // List<string> parameter, companion method.
    [Fact]
    public void Patient_BatchAdmit_ReturnsTheCount()
    {
        Assert.Equal(2, Patient.BatchAdmit(new List<string> { "Oreo", "Mylo" }));
    }

    // Non-nullable object-handle parameter, top-level function.
    [Fact]
    public void ClinicSample_PatientNameLength_ReturnsTheNameLength()
    {
        using var patient = new Patient("Oreo");

        // Top-level functions keep Kotlin camelCase (see ClinicSample.admit, Mappings.nullableInt).
        Assert.Equal(4, ClinicSample.patientNameLength(patient));
    }

    // Non-nullable object-handle parameter, primary constructor (alongside a String).
    [Fact]
    public void Escort_Constructor_MarshalsObjectHandleParameter()
    {
        using var patient = new Patient("Oreo");
        using var escort = new Escort(patient, "referred by the front desk");

        Assert.Equal("Oreo", escort.Patient.Name);
        Assert.Equal("referred by the front desk", escort.Note);
    }

    // Nullable object-handle parameter and return, extension function.
    [Fact]
    public void Patient_PairWith_WithOther_SetsAndReturnsBuddy()
    {
        using var patient = new Patient("Oreo");
        using var other = new Patient("Mylo");

        var result = patient.PairWith(other);

        Assert.NotNull(result);
        Assert.Equal("Mylo", result!.Name);
        Assert.NotNull(patient.Buddy);
    }

    [Fact]
    public void Patient_PairWith_WithNull_ClearsBuddyAndReturnsNull()
    {
        using var patient = new Patient("Oreo");
        using var other = new Patient("Mylo");
        patient.PairWith(other);

        var result = patient.PairWith(null);

        Assert.Null(result);
        Assert.Null(patient.Buddy);
    }
}

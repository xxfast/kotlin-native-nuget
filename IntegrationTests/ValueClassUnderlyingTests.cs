using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-077 sub-item 4: Primitive-, Enum-, and ObjectHandle-underlying value classes at ordinary
/// positions, surfacing as their record structs in C# the same way <c>ChartId</c> (String) and
/// <c>StoryCode</c> (cross-module String) already do. Today <c>valueClassResultShape</c> only
/// dispatches String, <c>inputSkipReason</c>'s <c>ValueClass</c> branch only admits String, and
/// <c>ForwardPropertyPlanner.isPlannable</c>'s <c>ValueClass</c> branch requires
/// <c>underlying == String</c>, so every member below is silently dropped: these tests fail with
/// CS1061 on the missing member, not on an assertion.
///
/// Per-underlying kind, one fixture cell each for method param, property, and method return
/// (shared machinery was already proven by sub-items 1-3, so this file does not repeat the
/// constructor / extension-function / top-level-function positions):
///
///  - Primitive (<c>Dosage(val milligrams: Double)</c>): <see cref="Clinic.Prescribe"/> (param +
///    return in one call, mirroring the ADR's own consumer-API sketch) and
///    <see cref="Patient.Dosage"/> (property).
///  - Enum (<c>Temperament(val mood: Mood)</c>): <see cref="Patient.Temperament"/> (property) and
///    <see cref="Patient.Soothe"/> (param + return in one call). Declaring this fixture originally
///    crashed <c>packNuget</c> outright on both KSP targets (<c>java.lang.IllegalArgumentException:
///    Forward ABI missing C# projection for temperament_create; expected temperament_create(in
///    int, out pointer) -&gt; int</c>, verified with a real build) -- a broken
///    <c>CirClassTranslator</c> struct, not a missing ordinary-position case, per the ADR's own
///    warning ("if the struct itself is wrong, that is a prerequisite fix, not part of this
///    table"). That is now fixed and the fixture un-quarantined; <see cref="Patient.Soothe"/>'s
///    test asserts the ordinal survives the wire with two *different* <c>Mood</c> values each
///    direction, not a same-value echo that would pass by coincidence.
///  - ObjectHandle (<c>ChartRef(val patient: Patient)</c>, already shipped for its own-member
///    facet): <see cref="Patient.ChartRef"/> (property), <see cref="Patient.Reassign"/>
///    (parameter, returning the already-supported <c>string</c>), <see cref="Patient.OwnReferral"/>
///    (return), and <see cref="Patient.BackupReferral"/> (the one
///    <c>Nullable(ValueClass(ObjectHandle))</c> cell -- rides the null pointer exactly like
///    sub-item 3's <c>ChartId?</c>, since an ObjectHandle underlying is already pointer-shaped).
///
/// Nullable(Primitive) and Nullable(Enum) underlyings stay out of scope entirely (deferred by the
/// ADR; the wire has no in-band null for either).
///
/// Oreo's dosage is always exactly what the vet prescribed and his temperament worsens before it
/// improves; Mylo keeps a spare referral in his file just in case and stays playful throughout.
/// </summary>
public class ValueClassUnderlyingTests
{
    [Fact]
    public void Clinic_Prescribe_PrimitiveUnderlyingValueClass_ParamAndReturnRoundTripTheDoubledAmount()
    {
        Dosage doubled = Clinic.Prescribe(new Dosage(2.5));

        Assert.Equal(5.0, doubled.Milligrams);
    }

    [Fact]
    public void Patient_Dosage_PrimitiveUnderlyingValueClass_PropertyRoundTripsTheRecordStruct()
    {
        using var oreo = new Patient("Oreo");

        Assert.Equal(1.0, oreo.Dosage.Milligrams);

        oreo.Dosage = new Dosage(7.5);

        Assert.Equal(7.5, oreo.Dosage.Milligrams);
    }

    [Fact]
    public void Patient_Temperament_EnumUnderlyingValueClass_PropertyRoundTripsTheRecordStruct()
    {
        using var oreo = new Patient("Oreo");

        Assert.Equal(Mood.Calm, oreo.Temperament.Mood);

        oreo.Temperament = new Temperament(Mood.Playful);

        Assert.Equal(Mood.Playful, oreo.Temperament.Mood);
    }

    [Fact]
    public void Patient_Soothe_EnumUnderlyingValueClass_ParamAndReturnSurviveTheOrdinalWireBothDirections()
    {
        using var oreo = new Patient("Oreo");

        // Anxious (1) in, Calm (0) out: a different Mood on each side of the call, not a
        // same-value echo that would pass even if the ordinal never actually crossed the wire.
        Temperament soothed = oreo.Soothe(new Temperament(Mood.Anxious));
        Assert.Equal(Mood.Calm, soothed.Mood);

        // Playful (2) in, Playful (2) out: the other branch, proving a non-Anxious ordinal also
        // round-trips intact rather than always collapsing to Calm's ordinal by accident.
        Temperament unchanged = oreo.Soothe(new Temperament(Mood.Playful));
        Assert.Equal(Mood.Playful, unchanged.Mood);
    }

    [Fact]
    public void Patient_ChartRef_ObjectHandleUnderlyingValueClass_PropertyWrapsThePatientItself()
    {
        using var mylo = new Patient("Mylo");

        ChartRef reference = mylo.ChartRef;

        Assert.Equal("Mylo", reference.Patient.Name);
    }

    [Fact]
    public void Patient_Reassign_ObjectHandleUnderlyingValueClass_ParameterRoundTripsTheUnwrappedChartRef()
    {
        using var oreo = new Patient("Oreo");
        using var mylo = new Patient("Mylo");

        Assert.Equal("Oreo reassigned to Mylo", oreo.Reassign(new ChartRef(mylo)));
    }

    [Fact]
    public void Patient_OwnReferral_ObjectHandleUnderlyingValueClass_ReturnWrapsThePatientItself()
    {
        using var mylo = new Patient("Mylo");

        ChartRef reference = mylo.OwnReferral();

        Assert.Equal("Mylo", reference.Patient.Name);
    }

    [Fact]
    public void Patient_BackupReferral_NullableObjectHandleUnderlyingValueClass_StartsNull()
    {
        using var oreo = new Patient("Oreo");

        Assert.Null(oreo.BackupReferral);
    }

    [Fact]
    public void Patient_BackupReferral_NullableObjectHandleUnderlyingValueClass_RoundTripsAValueThenBackToNull()
    {
        using var oreo = new Patient("Oreo");
        using var mylo = new Patient("Mylo");

        oreo.BackupReferral = new ChartRef(mylo);

        Assert.Equal("Mylo", oreo.BackupReferral!.Value.Patient.Name);

        oreo.BackupReferral = null;

        Assert.Null(oreo.BackupReferral);
    }
}

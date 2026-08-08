using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// ADR-079: <c>Nullable(ValueClass)</c> over a Primitive- or Enum-underlying value class, at every
/// ordinary position. ADR-077 shipped this combination for the String and ObjectHandle underlyings
/// (<c>ChartId?</c>, <c>ChartRef?</c>: <see cref="ValueClassNullableTests"/>,
/// <see cref="ValueClassUnderlyingTests"/>), where Kotlin <c>null</c> rides the underlying's own
/// null pointer. A <c>double</c> or an enum's <c>int</c> ordinal has no null pointer, so today
/// <c>inputSkipReason</c>'s <c>Nullable</c>/<c>ValueClass</c> case only admits the String/
/// ObjectHandle underlyings, <c>nullableResultShape</c>'s <c>ValueClass</c> case has no Primitive/
/// Enum arm, and <c>ForwardPropertyPlanner.isNullableLegacyPrimitive</c> doesn't recognise a
/// Primitive/Enum-underlying value class either -- every member below is silently skipped (the
/// planner's named <c>VALUE_CLASS</c> skip), so these tests are expected to fail with a C# compile
/// error naming the missing member (CS1061 / CS1729 / CS0117), not an assertion failure.
///
/// Mandatory cells (ADR-079 "Consumer API" + the sentinel-catching lesson from ADR-069): a
/// <c>null</c> round trip, <c>Mood.Calm</c> (ordinal 0) surviving as non-null, <c>0.0</c> surviving
/// as non-null, and a Boolean-underlying <c>false</c> surviving as non-null and distinct from
/// <c>null</c> -- each is exactly where an in-band sentinel or a has-value/value mix-up would pass
/// for the wrong reason.
///
/// Oreo's dosage taper always halves cleanly and his temperament is tracked religiously; Mylo
/// starts every clinic visit as a blank slate -- no dosage, no mood, no quarantine flag at all.
/// </summary>
public class ValueClassNullableUnderlyingTests
{
    [Fact]
    public void Patient_LastDosage_NullablePrimitiveUnderlyingProperty_StartsNull()
    {
        using var mylo = new Patient("Mylo");

        Assert.Null(mylo.LastDosage);
        Assert.False(mylo.HasDosage());
    }

    [Fact]
    public void Patient_LastDosage_NullablePrimitiveUnderlyingProperty_RoundTripsAValueThenBackToNull()
    {
        using var oreo = new Patient("Oreo");

        oreo.LastDosage = new Dosage(3.5);

        Assert.Equal(3.5, oreo.LastDosage!.Value.Milligrams);
        Assert.True(oreo.HasDosage());

        oreo.LastDosage = null;

        Assert.Null(oreo.LastDosage);
        Assert.False(oreo.HasDosage());
    }

    [Fact]
    public void Patient_MaybeTemperament_NullableEnumUnderlyingProperty_StartsNull()
    {
        using var mylo = new Patient("Mylo");

        Assert.Null(mylo.MaybeTemperament);
        Assert.Equal("Mylo has no recorded temperament", mylo.TemperamentStatus());
    }

    [Fact]
    public void Patient_MaybeTemperament_NullableEnumUnderlyingProperty_CalmOrdinalZeroSurvivesAsNonNull()
    {
        using var oreo = new Patient("Oreo");

        // Mood.Calm is ordinal 0: the sentinel-catching cell for the has-value/value mix-up.
        oreo.MaybeTemperament = new Temperament(Mood.Calm);

        Assert.NotNull(oreo.MaybeTemperament);
        Assert.Equal(Mood.Calm, oreo.MaybeTemperament!.Value.Mood);
        Assert.Equal("Oreo is CALM", oreo.TemperamentStatus());

        oreo.MaybeTemperament = null;

        Assert.Null(oreo.MaybeTemperament);
    }

    [Fact]
    public void Patient_Taper_NullablePrimitiveUnderlyingParameterAndReturn_NullInIsNullOut()
    {
        using var mylo = new Patient("Mylo");

        Assert.Null(mylo.Taper(null));
    }

    [Fact]
    public void Patient_Taper_NullablePrimitiveUnderlyingParameterAndReturn_ValueHalves()
    {
        using var oreo = new Patient("Oreo");

        Dosage? tapered = oreo.Taper(new Dosage(4.0));

        Assert.Equal(2.0, tapered!.Value.Milligrams);
    }

    [Fact]
    public void Patient_QuarantineFlag_BooleanUnderlyingNullableReturn_FalseSurvivesDistinctFromNull()
    {
        using var oreo = new Patient("Oreo");

        // known = false: never checked, so the return is a genuine null.
        Assert.Null(oreo.QuarantineFlag(false));

        // known = true: checked and cleared, so the return is Flag(false) -- not null, and not
        // silently read back as a truthy 4-byte value.
        Flag? flag = oreo.QuarantineFlag(true);

        Assert.NotNull(flag);
        Assert.False(flag!.Value.Value);
    }

    [Fact]
    public void Patient_MatchesTemperament_NullableEnumUnderlyingExtensionParameter_NullMeansNoFilter()
    {
        using var mylo = new Patient("Mylo");

        Assert.True(mylo.MatchesTemperament(null));
    }

    [Fact]
    public void Patient_MatchesTemperament_NullableEnumUnderlyingExtensionParameter_ValueComparesTheOrdinal()
    {
        using var oreo = new Patient("Oreo");

        Assert.True(oreo.MatchesTemperament(new Temperament(Mood.Calm)));
        Assert.False(oreo.MatchesTemperament(new Temperament(Mood.Playful)));
    }

    [Fact]
    public void ClinicSample_DescribeTemperament_NullableEnumUnderlyingTopLevelParameter_NullIsUnknown()
    {
        Assert.Equal("Mood: unknown", ClinicSample.describeTemperament(null));
    }

    [Fact]
    public void ClinicSample_DescribeTemperament_NullableEnumUnderlyingTopLevelParameter_ValueIsDescribed()
    {
        Assert.Equal("Mood: ANXIOUS", ClinicSample.describeTemperament(new Temperament(Mood.Anxious)));
    }

    [Fact]
    public void ClinicSample_StandardDosage_NullablePrimitiveUnderlyingTopLevelReturn_NegativeKindIsNull()
    {
        Assert.Null(ClinicSample.standardDosage(-1));
    }

    [Fact]
    public void ClinicSample_StandardDosage_NullablePrimitiveUnderlyingTopLevelReturn_ZeroSurvivesAsNonNull()
    {
        // 0.0 is a legitimate Dosage, not the in-band sentinel this two-call shape exists to avoid
        // confusing with null.
        Dosage? dosage = ClinicSample.standardDosage(0);

        Assert.NotNull(dosage);
        Assert.Equal(0.0, dosage!.Value.Milligrams);
    }

    [Fact]
    public void ClinicSample_StandardDosage_NullablePrimitiveUnderlyingTopLevelReturn_PositiveKindReturnsARealDose()
    {
        Assert.Equal(2.5, ClinicSample.standardDosage(5)!.Value.Milligrams);
    }

    [Fact]
    public void Prescription_NullablePrimitiveUnderlyingConstructorParameter_NullIsRecordedAsNoDosage()
    {
        using var prescription = new Prescription(null, "Mylo");

        Assert.Equal("Mylo: no dosage prescribed", prescription.Label);
    }

    [Fact]
    public void Prescription_NullablePrimitiveUnderlyingConstructorParameter_ValueIsRecorded()
    {
        using var prescription = new Prescription(new Dosage(1.5), "Oreo");

        Assert.Equal("Oreo: 1.5mg", prescription.Label);
    }
}

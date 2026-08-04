using TestLibrary.Time;

namespace IntegrationTests;

// ADR-076: `kotlin.time.Instant` → `System.DateTimeOffset` (UTC, Offset = Zero).
// Wire: epochSeconds (long) + nanosecondsOfSecond (int). C# truncates sub-100ns
// (`nanos % 100`), so every fixed sample uses nanos divisible by 100.
//
// Oreo carries the known implant time; Mylo covers the later value and null branches.
// Expected RED until Instant is classified and planned as a first-class built-in: today Instant
// is SKIPPED_UNEXPORTED_DEPENDENCY_TYPE and these members vanish from Interop.cs (compile
// errors / missing types), not assertion mismatches.
public class InstantMappingTests
{
    // Instant.fromEpochSeconds(1_704_067_200, 123_456_700) → 2024-01-01T00:00:00.1234567Z
    private static readonly DateTimeOffset OreoMicrochippedAt =
        DateTimeOffset.UnixEpoch.AddTicks(
            1_704_067_200L * TimeSpan.TicksPerSecond + 123_456_700L / 100);

    // Instant.fromEpochSeconds(1_704_154_800, 500_000_000) → 2024-01-02T00:20:00.5000000Z
    private static readonly DateTimeOffset MyloMicrochippedAt =
        DateTimeOffset.UnixEpoch.AddTicks(
            1_704_154_800L * TimeSpan.TicksPerSecond + 500_000_000L / 100);

    // ---- Cell 1: data-class ctor param + property getter (NYTimes published_date shape) ----

    [Fact]
    public void CatPassport_MicrochippedAt_ConstructorAndGetter()
    {
        using var passport = new CatPassport("Oreo", OreoMicrochippedAt);

        Assert.Equal("Oreo", passport.CatName);
        Assert.Equal(OreoMicrochippedAt, passport.MicrochippedAt);
        Assert.Equal(TimeSpan.Zero, passport.MicrochippedAt.Offset);
    }

    [Fact]
    public void CatPassport_MicrochippedAt_MyloValue()
    {
        using var passport = new CatPassport("Mylo", MyloMicrochippedAt);

        Assert.Equal("Mylo", passport.CatName);
        Assert.Equal(MyloMicrochippedAt, passport.MicrochippedAt);
    }

    // ---- Cell 2: mutable Instant? property (ADR-002 two-call getter + setter) ----

    [Fact]
    public void VetAppointment_ArrivedAt_DefaultNull()
    {
        using var appt = new VetAppointment(null);
        Assert.Null(appt.ArrivedAt);
    }

    [Fact]
    public void VetAppointment_ArrivedAt_SetAndClear()
    {
        using var appt = new VetAppointment(null);

        appt.ArrivedAt = OreoMicrochippedAt;
        Assert.Equal(OreoMicrochippedAt, appt.ArrivedAt);
        Assert.Equal(TimeSpan.Zero, appt.ArrivedAt!.Value.Offset);

        appt.ArrivedAt = null;
        Assert.Null(appt.ArrivedAt);
    }

    [Fact]
    public void VetAppointment_ArrivedAt_ConstructorNonNull()
    {
        using var appt = new VetAppointment(MyloMicrochippedAt);
        Assert.Equal(MyloMicrochippedAt, appt.ArrivedAt);
    }

    // ---- Cell 3: non-null Instant method return ----

    [Fact]
    public void VetAppointment_NextSlot_ReturnsOreoTime()
    {
        using var appt = new VetAppointment(null);
        DateTimeOffset slot = appt.NextSlot();

        Assert.Equal(OreoMicrochippedAt, slot);
        Assert.Equal(TimeSpan.Zero, slot.Offset);
    }

    // ---- Cell 4: Instant? method return ----

    [Fact]
    public void VetAppointment_MaybeCheckout_NullWhenNotCheckedOut()
    {
        using var appt = new VetAppointment(null);
        Assert.Null(appt.MaybeCheckout(false));
    }

    [Fact]
    public void VetAppointment_MaybeCheckout_ValueWhenCheckedOut()
    {
        using var appt = new VetAppointment(null);
        Assert.Equal(OreoMicrochippedAt, appt.MaybeCheckout(true));
    }

    // ---- Cell 5: non-null Instant method parameter ----

    [Fact]
    public void VetAppointment_SecondsSinceEpoch_MarshalsInstantParameter()
    {
        using var appt = new VetAppointment(null);
        Assert.Equal(1_704_067_200L, appt.SecondsSinceEpoch(OreoMicrochippedAt));
        Assert.Equal(1_704_154_800L, appt.SecondsSinceEpoch(MyloMicrochippedAt));
    }

    // ---- Cell 5b: Instant param + return round-trip (within 100 ns tick precision) ----

    [Fact]
    public void VetAppointment_Echo_RoundTripsDateTimeOffset()
    {
        using var appt = new VetAppointment(null);
        DateTimeOffset echoed = appt.Echo(OreoMicrochippedAt);

        Assert.Equal(OreoMicrochippedAt, echoed);
        Assert.Equal(TimeSpan.Zero, echoed.Offset);
    }

    // ---- Cell 6: Instant? method parameter ----

    [Fact]
    public void VetAppointment_DescribeDeparture_NullMeansStillInClinic()
    {
        using var appt = new VetAppointment(null);
        Assert.Equal("still in clinic", appt.DescribeDeparture(null));
    }

    [Fact]
    public void VetAppointment_DescribeDeparture_ValueReportsEpochSeconds()
    {
        using var appt = new VetAppointment(null);
        Assert.Equal("left at 1704067200", appt.DescribeDeparture(OreoMicrochippedAt));
    }

    // ---- Cell 6b: Instant? param + Instant? return ----

    [Fact]
    public void VetAppointment_MaybeEcho_Null()
    {
        using var appt = new VetAppointment(null);
        Assert.Null(appt.MaybeEcho(null));
    }

    [Fact]
    public void VetAppointment_MaybeEcho_Value()
    {
        using var appt = new VetAppointment(null);
        Assert.Equal(MyloMicrochippedAt, appt.MaybeEcho(MyloMicrochippedAt));
    }

    // ---- Cell 7: object methods (static export path) ----

    [Fact]
    public void PassportOffice_DefaultMicrochipDate_ReturnsOreoTime()
    {
        DateTimeOffset date = PassportOffice.DefaultMicrochipDate();

        Assert.Equal(OreoMicrochippedAt, date);
        Assert.Equal(TimeSpan.Zero, date.Offset);
    }

    [Fact]
    public void PassportOffice_IsAfterOreo_FalseForOreoTime()
    {
        Assert.False(PassportOffice.IsAfterOreo(OreoMicrochippedAt));
    }

    [Fact]
    public void PassportOffice_IsAfterOreo_TrueForMyloTime()
    {
        Assert.True(PassportOffice.IsAfterOreo(MyloMicrochippedAt));
    }
}

using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-076: kotlin.time.Instant maps to System.DateTimeOffset over a single INT64 of .NET
/// ticks. Oreo and Mylo are camera-shy, so the neighbourhood watch program's SightingLog only
/// ever tracks one cat at a time -- but it still has to cross every position the ADR asks for.
/// </summary>
public class InstantMappingTests
{
    // --- Boundary values (all verified as the real edges by the ADR's spike) ---

    [Fact]
    public void UnixEpoch_RoundTrips_ThroughConstructorAndValProperty()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        Assert.Equal(621355968000000000L, log.FirstSeen.UtcTicks);
        Assert.Equal(TimeSpan.Zero, log.FirstSeen.Offset);
    }

    [Fact]
    public void PreUnixEpoch_RoundTrips_ThroughVarNullableProperty()
    {
        // 1969-12-31T23:59:59.999999900Z -- exercises the negative-remainder path in the
        // Kotlin ticks -> epochSeconds/nanosecondsOfSecond conversion.
        var preEpoch = new DateTimeOffset(621355967999999999L, TimeSpan.Zero);
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        log.LastSeen = preEpoch;

        Assert.Equal(preEpoch, log.LastSeen);
        Assert.Equal(621355967999999999L, log.LastSeen!.Value.UtcTicks);
        Assert.Equal(TimeSpan.Zero, log.LastSeen!.Value.Offset);
    }

    [Fact]
    public void NYTimesShapedDate_RoundTrips_ThroughConstructor()
    {
        // 2024-03-08T07:04:56.123Z, the shape of NYTimes-KMP's Article.published_date.
        var published = new DateTimeOffset(638454782961230000L, TimeSpan.Zero);
        using var log = new SightingLog(published, null);

        Assert.Equal(published, log.FirstSeen);
        Assert.Equal(638454782961230000L, log.FirstSeen.UtcTicks);
    }

    [Fact]
    public void DateTimeOffsetMinValue_RoundTrips_AtTicksZero()
    {
        using var log = new SightingLog(DateTimeOffset.MinValue, null);

        Assert.Equal(0L, log.FirstSeen.UtcTicks);
        Assert.Equal(DateTimeOffset.MinValue, log.FirstSeen);
    }

    [Fact]
    public void DateTimeOffsetMaxValue_RoundTrips_AtMaxTicks()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, DateTimeOffset.MaxValue);

        Assert.Equal(3155378975999999999L, log.LastSeen!.Value.UtcTicks);
        Assert.Equal(DateTimeOffset.MaxValue, log.LastSeen!.Value);
    }

    [Fact]
    public void SubHundredNanosecondKotlinValue_TruncatesTowardEpochOrigin_DoesNotRound()
    {
        // Kotlin's sightingEpoch() carries a 123_456_789 ns adjustment; 89 ns of that is below
        // the wire form's 100ns tick resolution. Truncation floors to ...4567, not ...4568.
        var result = SightingLogKt.sightingEpoch();

        Assert.Equal(638355968001234567L, result.UtcTicks);
    }

    [Fact]
    public void NonUtcDateTimeOffset_RoundTripsAsEqual_AndComesBackUtcNormalized()
    {
        var at = new DateTimeOffset(2024, 3, 8, 12, 34, 56, 123, new TimeSpan(5, 30, 0));
        using var log = new SightingLog(at, null);

        // DateTimeOffset equality is instant-based, so this holds even though log.FirstSeen's
        // Offset differs from `at`'s.
        Assert.Equal(at, log.FirstSeen);
        Assert.Equal(TimeSpan.Zero, log.FirstSeen.Offset);
    }

    // --- Positional coverage: ctor params, method parameter, method return, valueOut,
    //     top-level return, top-level nullable return ---

    [Fact]
    public void Constructor_NullableLastSeenParameter_DefaultsToNull()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        Assert.Null(log.LastSeen);
    }

    [Fact]
    public void ElapsedSeconds_MethodParameter_ComputesDifference()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        long elapsed = log.ElapsedSeconds(DateTimeOffset.UnixEpoch.AddHours(1));

        Assert.Equal(3600L, elapsed);
    }

    [Fact]
    public void NextExpected_MethodReturn_IsADayAfterFirstSeenWhenNeverSeenAgain()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        Assert.Equal(DateTimeOffset.UnixEpoch.AddDays(1), log.NextExpected());
    }

    [Fact]
    public void EarliestUnconfirmed_NullableMethodReturn_NullWhenNoSightingReported()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        Assert.Null(log.EarliestUnconfirmed());
    }

    [Fact]
    public void EarliestUnconfirmed_NullableMethodReturn_ReturnsFirstSeenWhenLastSeenSet()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch);

        Assert.Equal(DateTimeOffset.UnixEpoch, log.EarliestUnconfirmed());
    }

    [Fact]
    public void SightingEpoch_TopLevelReturn_IsWellKnownDate()
    {
        var result = SightingLogKt.sightingEpoch();

        Assert.Equal(TimeSpan.Zero, result.Offset);
    }

    [Fact]
    public void ParseSighting_TopLevelNullableReturn_ReturnsEpochWhenOreoMentioned()
    {
        var result = SightingLogKt.parseSighting("Oreo was seen near the fence at dawn");

        Assert.Equal(SightingLogKt.sightingEpoch(), result);
    }

    [Fact]
    public void ParseSighting_TopLevelNullableReturn_NullWhenOreoNotMentioned()
    {
        // Mylo never causes trouble, so a note that doesn't mention Oreo isn't a sighting.
        var result = SightingLogKt.parseSighting("Mylo was napping on the windowsill");

        Assert.Null(result);
    }

    // --- Out-of-range Instant: the throwing contract, not a wrapped/clamped value ---

    [Fact]
    public void EscapedForEternity_OutOfRangeInstant_ThrowsArgumentException()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        Assert.ThrowsAny<ArgumentException>(() => log.EscapedForEternity());
    }

    [Fact]
    public void EscapedForEternity_OutOfRangeInstant_IsExactType_KotlinArgumentException()
    {
        using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

        var ex = Assert.ThrowsAny<ArgumentException>(() => log.EscapedForEternity());

        Assert.IsType<KotlinArgumentException>(ex);
    }
}

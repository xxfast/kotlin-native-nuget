using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-103: kotlin.time.Duration maps to System.TimeSpan over a single INT64 of TimeSpan ticks.
/// Mylo naps professionally and Oreo supervises, so the household nap tracker has to cross every
/// position the ADR asks for: properties, ctor and method parameters, returns, nullable forms,
/// the static object path, and the two Kotlin-side throws.
/// </summary>
public class DurationMappingTests
{
    // --- Boundary values (all verified as the real edges by the ADR's konanc spike) ---

    [Fact]
    public void Zero_RoundTrips_ThroughConstructorAndValProperty()
    {
        using var tracker = new NapTracker(TimeSpan.Zero, null);

        Assert.Equal(TimeSpan.Zero, tracker.LongestNap);
        Assert.Equal(0L, tracker.LongestNap.Ticks);
    }

    [Fact]
    public void NegativeDuration_RoundTrips_ThroughVarNullableProperty()
    {
        // -2.5s. Oreo woke up before the nap officially started.
        var owedNap = new TimeSpan(-25000000L);
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        tracker.LastNap = owedNap;

        Assert.Equal(owedNap, tracker.LastNap);
        Assert.Equal(-25000000L, tracker.LastNap!.Value.Ticks);
    }

    [Fact]
    public void OrdinaryNap_RoundTrips_ThroughConstructor()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Equal(TimeSpan.FromMinutes(90), tracker.LongestNap);
        Assert.Equal(54000000000L, tracker.LongestNap.Ticks);
    }

    [Fact]
    public void TimeSpanMaxValue_EchoesBack_AtMostOneMillisecondShort()
    {
        // C# -> Kotlin is total, but MaxValue lands in Duration's millisecond band, so the way
        // back loses 5807 ticks (well under 1ms). Documented contract, not a defect.
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        var result = tracker.Echo(TimeSpan.MaxValue);

        Assert.Equal(9223372036854770000L, result.Ticks);
        Assert.True(TimeSpan.MaxValue - result < TimeSpan.FromMilliseconds(1));
    }

    [Fact]
    public void TimeSpanMinValue_EchoesBack_AtMostOneMillisecondShort()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        var result = tracker.Echo(TimeSpan.MinValue);

        Assert.Equal(-9223372036854770000L, result.Ticks);
        Assert.True(result - TimeSpan.MinValue < TimeSpan.FromMilliseconds(1));
    }

    [Fact]
    public void NanosecondBandEdge_EchoesBack_Exactly()
    {
        // 46116860184269999 ticks is the largest TimeSpan that stays inside Duration's exact
        // nanosecond band (about 146 years), so this one round trips with no loss at all.
        var edge = new TimeSpan(46116860184269999L);
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        var result = tracker.Echo(edge);

        Assert.Equal(edge, result);
        Assert.Equal(46116860184269999L, result.Ticks);
    }

    [Fact]
    public void SubHundredNanosecondKotlinValue_TruncatesTowardZero_DoesNotRound()
    {
        // Kotlin's napEpsilon() is 150 ns; 50 ns of that is below the wire form's 100ns tick
        // resolution. Truncation gives 1 tick, rounding would give 2.
        var result = NapTrackerKt.napEpsilon();

        Assert.Equal(1L, result.Ticks);
    }

    // --- Positional coverage: ctor params, method parameter, method return, valueOut,
    //     top-level return, top-level nullable return ---

    [Fact]
    public void Constructor_NullableLastNapParameter_DefaultsToNull()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Null(tracker.LastNap);
    }

    [Fact]
    public void Constructor_NullableLastNapParameter_CarriesAValue()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), TimeSpan.FromMinutes(20));

        Assert.Equal(TimeSpan.FromMinutes(20), tracker.LastNap);
    }

    [Fact]
    public void Extend_MethodParameterAndReturn_AddsToTheLongestNap()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        var result = tracker.Extend(TimeSpan.FromMinutes(30));

        Assert.Equal(TimeSpan.FromHours(2), result);
    }

    [Fact]
    public void ShortestNap_NullableMethodReturn_NullWhenNoNapRecorded()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Null(tracker.ShortestNap());
    }

    [Fact]
    public void ShortestNap_NullableMethodReturn_ReturnsTheShorterOfTheTwo()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), TimeSpan.FromMinutes(20));

        Assert.Equal(TimeSpan.FromMinutes(20), tracker.ShortestNap());
    }

    [Fact]
    public void NapEpsilon_TopLevelReturn_IsTheSmallestRecordedNap()
    {
        var result = NapTrackerKt.napEpsilon();

        Assert.True(result > TimeSpan.Zero);
        Assert.True(result < TimeSpan.FromMilliseconds(1));
    }

    [Fact]
    public void ParseNap_TopLevelNullableReturn_ReturnsTwentyMinutesWhenOreoMentioned()
    {
        var result = NapTrackerKt.parseNap("Oreo dozed off on the keyboard");

        Assert.Equal(TimeSpan.FromMinutes(20), result);
    }

    [Fact]
    public void ParseNap_TopLevelNullableReturn_NullWhenOreoNotMentioned()
    {
        // Mylo naps off the record, so a note that doesn't mention Oreo isn't a tracked nap.
        var result = NapTrackerKt.parseNap("Mylo was asleep in the laundry basket");

        Assert.Null(result);
    }

    // --- Duration? as a plain method parameter (adjacent-pair HasValue + INT64 shape), not just
    //     a ctor param ---

    [Fact]
    public void Describe_NullableMethodParameter_NullBranch_ReportsNoNap()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Equal("no nap recorded", tracker.Describe(null));
    }

    [Fact]
    public void Describe_NullableMethodParameter_ValueBranch_ReportsMilliseconds()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Equal("napped for 5400000ms", tracker.Describe(TimeSpan.FromMinutes(90)));
    }

    // --- Echo round trips: Duration? both ways, and non-null Duration both ways ---

    [Fact]
    public void MaybeEcho_NullableInAndOut_NullBranch_StaysNull()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Null(tracker.MaybeEcho(null));
    }

    [Fact]
    public void MaybeEcho_NullableInAndOut_ValueBranch_RoundTripsExactly()
    {
        var nap = new TimeSpan(0, 21, 37, 4, 250);
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        var result = tracker.MaybeEcho(nap);

        Assert.Equal(nap, result);
        Assert.Equal(nap.Ticks, result!.Value.Ticks);
    }

    [Fact]
    public void MaybeEcho_NullableInAndOut_NegativeValueBranch_RoundTripsExactly()
    {
        var owed = new TimeSpan(-25000000L);
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Equal(owed, tracker.MaybeEcho(owed));
    }

    [Fact]
    public void Echo_NonNullInAndOut_RoundTripsExactly()
    {
        var nap = new TimeSpan(0, 21, 37, 4, 250);
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        var result = tracker.Echo(nap);

        Assert.Equal(nap, result);
        Assert.Equal(nap.Ticks, result.Ticks);
    }

    [Fact]
    public void Echo_NonNullInAndOut_ZeroRoundTripsExactly()
    {
        using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

        Assert.Equal(TimeSpan.Zero, tracker.Echo(TimeSpan.Zero));
    }

    // --- Duration on a Kotlin object (static export path, ForwardCallableOrigin.OBJECT) ---

    [Fact]
    public void NapClock_DefaultNap_StaticNonNullReturn_IsNinetyMinutes()
    {
        Assert.Equal(TimeSpan.FromMinutes(90), NapClock.DefaultNap());
    }

    [Fact]
    public void NapClock_IsLong_StaticNonNullParameter_TrueWhenOverAnHour()
    {
        Assert.True(NapClock.IsLong(TimeSpan.FromMinutes(90)));
    }

    [Fact]
    public void NapClock_IsLong_StaticNonNullParameter_FalseWhenUnderAnHour()
    {
        Assert.False(NapClock.IsLong(TimeSpan.FromMinutes(20)));
    }

    // --- The two throwing paths: infinite and out-of-range finite, not wrapped/clamped values ---

    [Fact]
    public void NapClock_InfiniteNap_ThrowsArgumentException()
    {
        Assert.ThrowsAny<ArgumentException>(() => NapClock.InfiniteNap());
    }

    [Fact]
    public void NapClock_InfiniteNap_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => NapClock.InfiniteNap());

        Assert.IsType<KotlinArgumentException>(ex);
    }

    [Fact]
    public void NapClock_AeonNap_OutOfTimeSpanRangeButFinite_ThrowsArgumentException()
    {
        Assert.ThrowsAny<ArgumentException>(() => NapClock.AeonNap());
    }

    [Fact]
    public void NapClock_AeonNap_OutOfTimeSpanRangeButFinite_IsExactType_KotlinArgumentException()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => NapClock.AeonNap());

        Assert.IsType<KotlinArgumentException>(ex);
    }
}

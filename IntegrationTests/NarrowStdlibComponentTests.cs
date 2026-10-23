using TestLibrary.Furcoat;

namespace IntegrationTests;

/// <summary>
/// ADR-235: <c>Instant</c>, <c>Duration</c> and <c>Uuid</c> as collection components, bare
/// (<c>DateTimeOffset</c>, <c>TimeSpan</c>, <c>Guid</c>) and inside a value class, at a parameter
/// and a result, in an <c>IReadOnlyList</c>, an <c>IReadOnlySet</c> and on both sides of an
/// <c>IReadOnlyDictionary</c>. Each element crosses on its own parameter wire.
///
/// Oreo's nap log: when each sunbeam nap started, how long it lasted, and whose chip was scanned.
/// </summary>
public class NarrowStdlibComponentTests
{
    private static readonly Guid OreoChipId = Guid.Parse("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");
    private static readonly Guid MyloChipId = Guid.Parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a");

    [Fact]
    public void Instant_ListResult_ReadsAsDateTimeOffsets()
    {
        IReadOnlyList<DateTimeOffset> starts = NapLog.NapStarts(2);
        Assert.Equal(new[]
        {
            DateTimeOffset.FromUnixTimeSeconds(1_700_000_060),
            DateTimeOffset.FromUnixTimeSeconds(1_700_000_120),
        }, starts);
    }

    [Fact]
    public void Instant_ListParam_ReachesKotlin()
    {
        var latest = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        Assert.Equal(latest, NapLog.LatestNap(new List<DateTimeOffset> { DateTimeOffset.FromUnixTimeSeconds(5), latest }));
    }

    [Fact]
    public void Instant_NullableListElement_CountsTheNulls()
    {
        Assert.Equal(2, NapLog.MissingStarts(new List<DateTimeOffset?> { null, DateTimeOffset.UnixEpoch, null }));
    }

    [Fact]
    public void Duration_SetResultAndParam_RoundTrips()
    {
        IReadOnlySet<TimeSpan> lengths = NapLog.NapLengths();
        Assert.True(lengths.SetEquals(new[] { TimeSpan.FromMinutes(20), TimeSpan.FromMinutes(45) }));
        Assert.Equal(65L, NapLog.TotalNapMinutes(lengths));
    }

    [Fact]
    public void Duration_NullableSetElement_RoundTrips()
    {
        IReadOnlySet<TimeSpan?> echoed = NapLog.EchoMaybeLengths(new HashSet<TimeSpan?> { null, TimeSpan.FromMinutes(3) });
        Assert.True(echoed.SetEquals(new TimeSpan?[] { null, TimeSpan.FromMinutes(3) }));
    }

    [Fact]
    public void Uuid_ListResultAndParam_RoundTrips()
    {
        IReadOnlyList<Guid> ids = NapLog.ChipIds();
        Assert.Equal(new[] { OreoChipId, MyloChipId }, ids);
        Assert.Equal(MyloChipId, NapLog.FirstChip(new List<Guid> { MyloChipId, OreoChipId }));
    }

    [Fact]
    public void Uuid_NullableListElement_RoundTrips()
    {
        Assert.Equal(new Guid?[] { null, OreoChipId }, NapLog.EchoMaybeChips(new List<Guid?> { null, OreoChipId }));
    }

    [Fact]
    public void Map_UuidKeyDurationValue_ReadsBack()
    {
        IReadOnlyDictionary<Guid, TimeSpan> naps = NapLog.NapsByChip();
        Assert.Equal(TimeSpan.FromMinutes(20), naps[OreoChipId]);
        Assert.Equal(TimeSpan.FromMinutes(45), naps[MyloChipId]);
    }

    [Fact]
    public void Map_InstantKeyUuidValue_ReachesKotlin()
    {
        var naps = new Dictionary<DateTimeOffset, Guid>
        {
            [DateTimeOffset.FromUnixTimeSeconds(20)] = MyloChipId,
            [DateTimeOffset.FromUnixTimeSeconds(10)] = OreoChipId,
        };
        Assert.Equal($"10={OreoChipId},20={MyloChipId}", NapLog.ChipsByNap(naps));
    }

    [Fact]
    public void ValueClassOverInstant_ListElement_RoundTrips()
    {
        IReadOnlyList<SunbeamStart> sunbeams = NapLog.Sunbeams();
        Assert.Equal(DateTimeOffset.UnixEpoch, sunbeams[1].At);
        Assert.Equal(new SunbeamStart(DateTimeOffset.UnixEpoch), NapLog.EarliestSunbeam(sunbeams));
    }

    [Fact]
    public void ValueClassOverInstant_ListElement_RunsInitInKotlin()
    {
        Assert.ThrowsAny<ArgumentException>(() => NapLog.EarliestSunbeam(
            new List<SunbeamStart> { new(DateTimeOffset.FromUnixTimeSeconds(-1)) }));
    }

    [Fact]
    public void ValueClassOverDuration_SetElement_RoundTrips()
    {
        IReadOnlySet<SnoozeSpan> snoozes = NapLog.Snoozes();
        Assert.Equal(new SnoozeSpan(TimeSpan.FromMinutes(30)), NapLog.LongestSnooze(snoozes));
    }

    [Fact]
    public void ValueClassOverUuid_MapKeyAndNestedValue_RoundTrips()
    {
        IReadOnlyDictionary<ChipSerial, ShelterTag> tags = NapLog.TagsByChip();
        Assert.Equal(MyloChipId, tags[new ChipSerial(OreoChipId)].Serial.Id);
        Assert.Equal(1, NapLog.CountTags(tags));
    }

    [Fact]
    public void ValueClassOverUuid_NullableListElement_RoundTrips()
    {
        Assert.Equal(
            new ChipSerial?[] { null, new ChipSerial(MyloChipId) },
            NapLog.MaybeChips(new List<ChipSerial?> { null, new ChipSerial(MyloChipId) }));
    }
}

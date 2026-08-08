using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-080: a <b>bare</b> <c>Nullable(Enum)</c> (<c>Mood?</c>, no value-class wrapper) at every
/// ordinary forward position. ADR-079 shipped the strictly harder wrapped case
/// (<c>Temperament?</c>: <see cref="ValueClassNullableUnderlyingTests"/>), so today's support matrix
/// is incoherent -- <c>Mood?</c> binds nowhere. Worse, it does not merely skip: a bare <c>Mood?</c>
/// <i>property</i> crashes <c>packNuget</c> (<c>hasValueFanOutInner()</c> has no bare-<c>Enum</c>
/// case, so the getter takes the <c>Direct</c> route and
/// <c>ForwardPropertyKotlinEmitter.addGetter</c> has no route for it), while bare <c>Mood?</c>
/// parameters and returns skip the whole callable with the mislabelled <c>NULLABLE</c> reason.
///
/// So these are expected to fail as a KSP/<c>packNuget</c> abort for <see cref="MoodJournal"/>'s
/// property, and -- once that is fixed but the callable path is not -- as C# compile errors naming
/// the missing members (CS1061 / CS1729 / CS0117), not as assertion failures.
///
/// Mandatory cells (ADR-069's lesson, restated by ADR-080): <c>Mood.Happy</c> is ordinal 0, so it
/// must survive as non-null and distinguishable from <c>null</c>; <c>Mood.Grumpy</c> is ordinal 2,
/// so a value slot that always reads back zero cannot pass either. Every position asserts both.
///
/// Oreo keeps a mood journal religiously and is grumpy at every hour that is not the crack of dawn;
/// Mylo's journal is a blank page until somebody actually watches him.
/// </summary>
public class NullableEnumTests
{
    // ---- Constructor parameter ----

    [Fact]
    public void MoodJournal_ConstructorParameter_NullIsRecordedAsUnobserved()
    {
        using var mylo = new MoodJournal(null);

        Assert.Equal("journal opened unobserved", mylo.FirstEntry);
    }

    [Fact]
    public void MoodJournal_ConstructorParameter_OrdinalZeroIsRecordedAsAValue()
    {
        // Mood.Happy is ordinal 0: the sentinel-catching cell for a has-value/value mix-up.
        using var oreo = new MoodJournal(Mood.Happy);

        Assert.Equal("journal opened while HAPPY", oreo.FirstEntry);
    }

    [Fact]
    public void MoodJournal_ConstructorParameter_NonFirstOrdinalIsRecorded()
    {
        using var oreo = new MoodJournal(Mood.Grumpy);

        Assert.Equal("journal opened while GRUMPY", oreo.FirstEntry);
    }

    // ---- var property: LegacyTwoCall getter + NullableDispatch setter ----

    [Fact]
    public void MoodJournal_CurrentMood_StartsNull()
    {
        using var mylo = new MoodJournal(null);

        Assert.Null(mylo.CurrentMood);
        Assert.Equal("No mood recorded yet.", mylo.MoodSummary());
    }

    [Fact]
    public void MoodJournal_CurrentMood_NonFirstOrdinalRoundTrips()
    {
        using var oreo = new MoodJournal(null);

        // Mood.Grumpy is ordinal 2: a getter that always reads slot 0 fails here.
        oreo.CurrentMood = Mood.Grumpy;

        Assert.Equal(Mood.Grumpy, oreo.CurrentMood);
        Assert.Equal("The cat is grumpy and doesn't want to be disturbed.", oreo.MoodSummary());
    }

    [Fact]
    public void MoodJournal_CurrentMood_OrdinalZeroSurvivesAsNonNull()
    {
        using var oreo = new MoodJournal(null);

        oreo.CurrentMood = Mood.Happy;

        Assert.NotNull(oreo.CurrentMood);
        Assert.Equal(Mood.Happy, oreo.CurrentMood);
        Assert.Equal("The cat is happy and content.", oreo.MoodSummary());
    }

    [Fact]
    public void MoodJournal_CurrentMood_SetsBackToNull()
    {
        using var mylo = new MoodJournal(null);

        mylo.CurrentMood = Mood.Sleepy;
        mylo.CurrentMood = null;

        Assert.Null(mylo.CurrentMood);
        Assert.Equal("No mood recorded yet.", mylo.MoodSummary());
    }

    // ---- Method parameter + nullable method return, in one call ----

    [Fact]
    public void MoodJournal_Soothe_NullInIsNullOut()
    {
        using var mylo = new MoodJournal(null);

        Assert.Null(mylo.Soothe(null));
    }

    [Fact]
    public void MoodJournal_Soothe_GrumpySettlesToSleepy()
    {
        using var oreo = new MoodJournal(null);

        // Ordinal 2 in, ordinal 1 out: neither slot can pass by echoing a zero.
        Mood? soothed = oreo.Soothe(Mood.Grumpy);

        Assert.NotNull(soothed);
        Assert.Equal(Mood.Sleepy, soothed);
    }

    [Fact]
    public void MoodJournal_Soothe_OrdinalZeroSurvivesUnchanged()
    {
        using var oreo = new MoodJournal(null);

        Mood? soothed = oreo.Soothe(Mood.Happy);

        Assert.NotNull(soothed);
        Assert.Equal(Mood.Happy, soothed);
    }

    // ---- Extension-function parameter ----

    [Fact]
    public void Cat_MatchesMood_NullMeansNoFilter()
    {
        using var mylo = new Cat("Mylo", 9);

        Assert.True(mylo.MatchesMood(null));
    }

    [Fact]
    public void Cat_MatchesMood_ValueComparesTheOrdinal()
    {
        using var oreo = new Cat("Oreo", 9);

        // Cat.mood defaults to Mood.Sleepy (ordinal 1).
        Assert.True(oreo.MatchesMood(Mood.Sleepy));
        Assert.False(oreo.MatchesMood(Mood.Happy));
        Assert.False(oreo.MatchesMood(Mood.Grumpy));
    }

    // ---- Top-level function nullable return (ADR-002 two-call) ----

    [Fact]
    public void NullableEnumSample_NapMood_NegativeHourIsNull()
    {
        Assert.Null(NullableEnumSample.napMood(-1));
    }

    [Fact]
    public void NullableEnumSample_NapMood_ZeroHourReturnsOrdinalZeroAsNonNull()
    {
        // Mood.Happy is a legitimate entry, not the in-band sentinel this two-call shape exists to
        // avoid confusing with null.
        Mood? mood = NullableEnumSample.napMood(0);

        Assert.NotNull(mood);
        Assert.Equal(Mood.Happy, mood);
    }

    [Fact]
    public void NullableEnumSample_NapMood_OtherHoursReturnANonFirstOrdinal()
    {
        Assert.Equal(Mood.Grumpy, NullableEnumSample.napMood(3));
    }
}

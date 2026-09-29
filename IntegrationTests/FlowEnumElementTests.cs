using TestLibrary.Cat;
using Xunit;

namespace IntegrationTests;

/// <summary>
/// ROADMAP line 74 (<c>docs/research/roadmap/fromhandle-enum.md</c>): an ENUM element at an erased
/// generic position. The Kotlin shim retains the enum object itself and the C# side reads it
/// through <c>NugetMarshal.FromHandle&lt;Mood&gt;</c> with no per-member read delegate, so every
/// cell here needs a <c>NugetMarshal.Factories</c> entry for the enum. Four positions:
/// <c>StateFlow&lt;Mood&gt;.Value</c>, <c>StateFlow&lt;Mood?&gt;.Value</c>, <c>await foreach</c>
/// over <c>Flow&lt;Mood&gt;</c> / <c>Flow&lt;Mood?&gt;</c>, and <c>Box&lt;Mood&gt;.Value</c> on an
/// ADR-147 generic class (the one position a per-member delegate cannot reach).
///
/// Every asserted mood has a NON-ZERO ordinal (<c>Sleepy</c> = 1, <c>Grumpy</c> = 2) except where
/// the order of several emissions is itself the assertion, so a read that always answers ordinal 0
/// cannot pass (ADR-097's rule).
///
/// The last two cells are value-class regression pins (memo what-question 1): ADR-171 already
/// registers <c>CatId</c> in <c>Factories</c>, so they are expected to pass before and after.
///
/// Mylo naps (Sleepy); Oreo sulks (Grumpy).
/// </summary>
public class FlowEnumElementTests
{
    [Fact]
    public void StateFlowOfEnum_Value_ReadsMyloAsleep_ThenOreoSulking()
    {
        using var tracker = new CatMoodTracker("Mylo");

        Assert.Equal(Mood.Sleepy, tracker.Temper.Value);

        tracker.Sulk();
        Assert.Equal(Mood.Grumpy, tracker.Temper.Value);
    }

    [Fact]
    public void StateFlowOfEnum_Value_RepeatedReads_AnswerTheSameMoodEveryTime()
    {
        using var tracker = new CatMoodTracker("Oreo");
        tracker.Sulk();

        for (int i = 0; i < 25; i++)
        {
            Assert.Equal(Mood.Grumpy, tracker.Temper.Value);
        }
    }

    [Fact]
    public void StateFlowOfNullableEnum_Value_NullUntilOreoSulks_ThenGrumpy()
    {
        using var tracker = new CatMoodTracker("Oreo");

        // Null crosses as IntPtr.Zero and short-circuits; this half passes with or without the fix.
        Assert.Null(tracker.MaybeTemper.Value);

        tracker.Sulk();
        Mood? mood = tracker.MaybeTemper.Value;
        Assert.True(mood.HasValue);
        Assert.Equal(Mood.Grumpy, mood.Value);
    }

    [Fact]
    public async Task FlowOfEnum_AwaitForeach_YieldsOreosMoodsInOrder()
    {
        using var faults = new CallbackFaults();

        var seen = new List<Mood>();
        await foreach (Mood mood in faults.MoodStream())
        {
            seen.Add(mood);
        }

        Assert.Equal(new[] { Mood.Happy, Mood.Grumpy }, seen);
    }

    [Fact]
    public async Task FlowOfNullableEnum_AwaitForeach_KeepsTheNullBetweenMoods()
    {
        using var tracker = new CatMoodTracker("Mylo");

        var seen = new List<Mood?>();
        await foreach (Mood? mood in tracker.MoodSwings())
        {
            seen.Add(mood);
        }

        Assert.Equal(new Mood?[] { Mood.Sleepy, null, Mood.Grumpy }, seen);
    }

    [Fact]
    public void GenericClassAtEnum_Value_MaterialisesTheSulk()
    {
        using Box<Mood> box = CatMoodTrackerKt.SulkBox();

        Assert.Equal(Mood.Grumpy, box.Value);
        // A second read mints a fresh handle; it must answer the same.
        Assert.Equal(Mood.Grumpy, box.Value);
    }

    // ---- Regression pins: value-class element (ADR-171), expected green already ----

    [Fact]
    public void StateFlowOfValueClass_Value_ReadsTheTag_ThenTheRetag()
    {
        using var tracker = new CatMoodTracker("Oreo");

        Assert.Equal(new CatId("oreo-1"), tracker.Tag.Value);

        tracker.Retag("mylo-2");
        Assert.Equal(new CatId("mylo-2"), tracker.Tag.Value);
        Assert.Equal("mylo-2", tracker.Tag.Value.Id);
    }

    [Fact]
    public async Task FlowOfNullableValueClass_AwaitForeach_KeepsTheNullBetweenTags()
    {
        using var tracker = new CatMoodTracker("Mylo");

        var seen = new List<CatId?>();
        await foreach (CatId? id in tracker.Tags())
        {
            seen.Add(id);
        }

        Assert.Equal(new CatId?[] { new CatId("oreo-1"), null, new CatId("mylo-2") }, seen);
    }
}

using TestLibrary.Cat;
using Xunit;

namespace IntegrationTests;

/// <summary>
/// An enum parameter on a top-level function that returns a generic class at a closed type
/// (<c>fun treatsFor(mood: Mood): Box&lt;Int&gt;</c>). The build used to fail with
/// <c>ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE</c>; the C# enum now crosses as its ordinal.
///
/// Every mood boxes a distinct count, so an uncast or misordered ordinal cannot pass.
/// </summary>
public class EnumParameterGenericReturnTests
{
    // Mylo naps (ordinal 1) and earns two treats; grumpy Oreo (ordinal 2) earns three.
    [Fact]
    public void TreatsFor_TwoMoods_BoxDistinctCounts()
    {
        using Box<int> sleepy = CatMoodTrackerKt.TreatsFor(Mood.Sleepy);
        using Box<int> grumpy = CatMoodTrackerKt.TreatsFor(Mood.Grumpy);
        Assert.Equal(2, sleepy.Value);
        Assert.Equal(3, grumpy.Value);
    }

    // Two enum parameters keep their order: Oreo grumpy (2), Mylo sleepy (1), and back again.
    [Fact]
    public void Standoff_TwoEnumParameters_KeepTheirOrder()
    {
        using Box<int> oreoGrumpy = CatMoodTrackerKt.Standoff(Mood.Grumpy, Mood.Sleepy);
        using Box<int> myloGrumpy = CatMoodTrackerKt.Standoff(Mood.Sleepy, Mood.Grumpy);
        Assert.Equal(22, oreoGrumpy.Value);
        Assert.Equal(13, myloGrumpy.Value);
    }

    // The string overload of TreatsFor on the same route, beside the enum overload: two names of
    // different lengths rule out a constant.
    [Fact]
    public void TreatsFor_StringOverload_BindsBesideTheEnumOverload()
    {
        using Box<int> mylo = CatMoodTrackerKt.TreatsFor("Mylo");
        using Box<int> stray = CatMoodTrackerKt.TreatsFor("Whiskers");
        using Box<int> sleepy = CatMoodTrackerKt.TreatsFor(Mood.Sleepy);
        Assert.Equal(4, mylo.Value);
        Assert.Equal(8, stray.Value);
        Assert.Equal(2, sleepy.Value);
    }

    // A null mood and null naps reach Kotlin as null; a value, including 0 naps, crosses as itself.
    [Fact]
    public void SnackPlan_NullableEnumAndInt_NullAndValueCross()
    {
        using Box<int> unknown = CatMoodTrackerKt.SnackPlan(null, null);
        using Box<int> grumpyFasting = CatMoodTrackerKt.SnackPlan(Mood.Grumpy, 0);
        using Box<int> unknownMood = CatMoodTrackerKt.SnackPlan(null, 4);
        using Box<int> sleepyUnknownNaps = CatMoodTrackerKt.SnackPlan(Mood.Sleepy, null);
        Assert.Equal(-1, unknown.Value);
        Assert.Equal(300, grumpyFasting.Value);
        Assert.Equal(4, unknownMood.Value);
        Assert.Equal(199, sleepyUnknownNaps.Value);
    }
}

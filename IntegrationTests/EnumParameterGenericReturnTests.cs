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
}

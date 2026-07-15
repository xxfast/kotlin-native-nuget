using TestLibrary.Enums;
using TestLibrary.Test.Enums;

namespace IntegrationTests;

public class ReverseEnumTests
{
    [Fact]
    public void CatMoodRoundTrip_AdvancesHungryCatToPlayful()
    {
        // Oreo wakes hungry, Kotlin calls the C# enum service, and the enum returns forward.
        CatMood result = CatMoodSample.catMoodRoundTrip();

        Assert.Equal(CatMood.Playful, result);
    }

    [Fact]
    public void AdvanceMood_PassesTheEnumArgumentThroughToKotlin()
    {
        // The enum goes out as an ordinal and comes back as one, on a top-level function.
        CatMood result = CatMoodSample.advanceMood(CatMood.Sleepy);

        Assert.Equal(CatMood.Hungry, result);
    }
}

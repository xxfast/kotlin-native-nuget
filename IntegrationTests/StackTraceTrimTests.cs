using System.Text.RegularExpressions;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

// ADR-200: KotlinStackTrace keeps the Kotlin frames only. Host frames print on mingwX64 as
// `0x0 + <address>` or as the nearest symbol with an offset in the millions. The assertions hold on
// every host; only the export-frame check is Windows-only, as macOS/linux frame text is not pinned.
public class StackTraceTrimTests
{
    private const long OneMebibyte = 1_048_576;

    private static string[] Frames(Exception ex) =>
        ((IKotlinException)ex).KotlinStackTrace
            .Split('\n')
            .Select(line => line.Trim())
            .Where(line => line.StartsWith("at "))
            .ToArray();

    private static void AssertNoHostFrames(Exception ex)
    {
        string[] frames = Frames(ex);
        Assert.NotEmpty(frames);
        Assert.All(frames, frame =>
        {
            Assert.DoesNotContain(" 0x0 + ", frame);
            Match offset = Regex.Match(frame, @" \+ (\d+)");
            if (offset.Success)
            {
                Assert.True(
                    ulong.TryParse(offset.Groups[1].Value, out ulong value) && value < OneMebibyte,
                    frame);
            }
        });
        Assert.DoesNotContain("coreclr", ((IKotlinException)ex).KotlinStackTrace);
        Assert.DoesNotContain("Caused by", ((IKotlinException)ex).KotlinStackTrace);
    }

    [Fact]
    public void Oreo_OnDiet_KotlinStackTrace_HasNoHostFrames()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(() => SyncExceptions.FeedCatTreat("Oreo"));
        AssertNoHostFrames(ex);
        Assert.Contains("feedCatTreat", Frames(ex)[0]);
    }

    [Fact]
    public void Oreo_OnDiet_KotlinStackTrace_EndsAtTheExportFrame()
    {
        if (!OperatingSystem.IsWindows()) return;
        var ex = Assert.ThrowsAny<ArgumentException>(() => SyncExceptions.FeedCatTreat("Oreo"));
        Assert.Matches(@"\skn_[a-z0-9_]+_cat__feedCatTreat \+ \d+$", Frames(ex)[^1]);
    }

    [Fact]
    public async Task Oreo_OnDiet_Suspend_KotlinStackTrace_HasNoHostFrames()
    {
        var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
            () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
        AssertNoHostFrames(ex);
        Assert.Contains("fetchCatTreat", Frames(ex)[0]);
    }

    [Fact]
    public void Oreo_Allergy_OuterTrace_DoesNotRepeatTheCause_AndTheCauseIsTrimmed()
    {
        var ex = Assert.ThrowsAny<ArgumentException>(
            () => CauseExceptions.FeedCatWithAllergy("Oreo"));
        AssertNoHostFrames(ex);
        Assert.DoesNotContain("Oreo is allergic", ((IKotlinException)ex).KotlinStackTrace);
        AssertNoHostFrames(ex.InnerException!);
    }
}

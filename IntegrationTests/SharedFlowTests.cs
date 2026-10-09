using System.Diagnostics;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-205: SharedFlow&lt;T&gt; mapping. Kotlin <c>SharedFlow&lt;T&gt;</c> (and the read-only view of a
/// declared <c>MutableSharedFlow&lt;T&gt;</c>) surfaces as <c>KotlinFlow&lt;T&gt;</c>, an
/// <c>IAsyncEnumerable&lt;T&gt;</c> whose enumeration subscribes to the hot stream. Kotlin's own
/// <c>SharedFlow.collect</c> replays the replay cache first and never completes, so every test here
/// publishes BEFORE enumerating and bounds the loop with <c>break</c> or cancellation.
///
/// Fixture: <see cref="CatBulletin"/> (test-library) publishes a cat's headlines, editions and
/// sightings, crossing the <c>int</c> (no conversion), <c>string</c> (box unwrap) and <c>Cat</c>
/// (object handle wrapper) element seams.
/// </summary>
public class SharedFlowTests
{
    [Fact]
    public async Task MutableSharedFlowProperty_StringElement_ReplaysTheCacheInOrderFirst_OreosTwoHeadlines()
    {
        // Oreo makes the paper twice before anyone subscribes; the replay cache holds both
        using var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("found the treat jar", 1);
        bulletin.Publish("knocked the treat jar off the shelf", 2);

        var seen = new List<string>();
        await foreach (string headline in bulletin.Headlines)
        {
            seen.Add(headline);
            if (seen.Count == 2) break;
        }

        Assert.Equal(
            new[] { "Oreo: found the treat jar", "Oreo: knocked the treat jar off the shelf" },
            seen);
    }

    [Fact]
    public async Task SharedFlowProperty_IntElement_ReplaysLatestEditionToLateSubscriber_MylosSeventhEdition()
    {
        using var bulletin = new CatBulletin("Mylo");
        bulletin.Publish("napped", 6);
        bulletin.Publish("napped again", 7);

        int? first = null;
        await foreach (int edition in bulletin.Editions)
        {
            first = edition;
            break;
        }

        // replay = 1: only the latest edition is in the cache
        Assert.Equal(7, first);
    }

    [Fact]
    public async Task SharedFlowMethod_IntElement_ReplaysLatestEdition_MylosEditionReport()
    {
        using var bulletin = new CatBulletin("Mylo");
        bulletin.Publish("napped", 7);

        int? first = null;
        await foreach (int edition in bulletin.EditionReport())
        {
            first = edition;
            break;
        }

        Assert.Equal(7, first);
    }

    [Fact]
    public async Task SuspendSharedFlowReturn_ObjectElement_ReplaysLatestSighting_OreoWasSeen()
    {
        using var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("spotted on the fence", 3);

        using KotlinFlow<Cat> sightings = await bulletin.LatestSightingsAsync();
        Cat? first = null;
        await foreach (Cat cat in sightings)
        {
            first = cat;
            break;
        }

        Assert.NotNull(first);
        using (first)
        {
            Assert.Equal("Oreo", first!.Name);
        }
    }

    [Fact]
    public async Task SharedFlowProperty_ObjectElement_CancelledAfterReplay_ExitsCleanly_MyloOnTheWindowsill()
    {
        using var bulletin = new CatBulletin("Mylo");
        bulletin.Publish("on the windowsill", 4);

        var cts = new CancellationTokenSource();
        Cat? first = null;
        await foreach (Cat cat in bulletin.Sightings.WithCancellation(cts.Token))
        {
            first = cat;
            cts.Cancel();
        }

        Assert.NotNull(first);
        using (first)
        {
            Assert.Equal("Mylo", first!.Name);
        }
    }

    [Fact]
    public async Task SharedFlowProperty_NeverCompletesOnItsOwn_MustBeBoundedByCancellation()
    {
        // A SharedFlow is hot and open: the loop only ends because the token fires. The same
        // clean-exit cancellation contract as the StateFlow case (ADR-026, ADR-065).
        using var bulletin = new CatBulletin("Oreo");
        bulletin.Publish("still napping", 9);
        var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        var seen = new List<int>();

        var stopwatch = Stopwatch.StartNew();
        await foreach (int edition in bulletin.Editions.WithCancellation(cts.Token))
        {
            seen.Add(edition);
        }
        stopwatch.Stop();

        Assert.True(cts.IsCancellationRequested);
        Assert.True(stopwatch.Elapsed < TimeSpan.FromSeconds(5), "await foreach must be bounded by cancellation, not hang on a SharedFlow that never completes");
        Assert.Equal(new[] { 9 }, seen);
    }
}

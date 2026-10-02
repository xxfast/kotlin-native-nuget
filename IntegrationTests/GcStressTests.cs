using System.Diagnostics;
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Issue54;
using TestLibrary.Kdoc;
using TestLibrary.Models;

namespace IntegrationTests;

/// <summary>
/// ADR-187's use-after-free gate. Once a generated wrapper's Kotlin handle is released by a
/// finalizer, every place that reads <c>_handle</c> and then stops referencing the wrapper becomes a
/// window in which the GC can free the handle under a native call that is still using it. Each test
/// here makes calls on wrappers that nothing else references (the receiver, an argument, the
/// <c>other</c> of an <c>Equals</c>, a collection element, a wrapper whose <c>suspend</c> call or
/// <c>Flow</c> is still in flight) while another thread forces collections and drains the
/// finalizer queue as fast as it can.
///
/// These are green before ADR-187 lands, trivially, because nothing is ever finalized. They exist
/// so they stay green after it: a red here is a freed handle reaching Kotlin, or a scope released
/// mid-flight cancelling a call the consumer is still awaiting.
///
/// The undisposed wrappers are the point, not an oversight: Oreo and Mylo knock things off the
/// table and walk away, and the bridge has to cope.
/// </summary>
public class GcStressTests
{
    // Each test stops early at this budget rather than letting a slow host turn 100k crossings
    // into a minute; the pressure, not the count, is what the gate needs.
    private static readonly TimeSpan Budget = TimeSpan.FromSeconds(2);
    private const int Iterations = 100_000;

    private static async Task UnderGcPressure(Func<Task> body)
    {
        using var stop = new CancellationTokenSource();
        Task collector = Task.Factory.StartNew(() =>
        {
            while (!stop.IsCancellationRequested)
            {
                GC.Collect();
                GC.WaitForPendingFinalizers();
            }
        }, TaskCreationOptions.LongRunning);

        try
        {
            await body();
        }
        finally
        {
            stop.Cancel();
            await collector;
        }
    }

    private static Task Hammer(Action crossing) => UnderGcPressure(() =>
    {
        var clock = Stopwatch.StartNew();
        for (int i = 0; i < Iterations && clock.Elapsed < Budget; i++) crossing();
        return Task.CompletedTask;
    });

    // The receiver is dead the moment its `_handle` has been read into the P/Invoke frame.
    [Fact]
    public Task ShortLivedReceiver_MemberCallsUnderGcPressure_NeverUseAFreedHandle() =>
        Hammer(() => Assert.Equal("Oreo", new Cat("Oreo", 9).Name));

    // A NON-receiver handle: the `Nap.Deep` argument (and the newsroom receiver) are both
    // unreferenced once their handles are on the stack.
    [Fact]
    public Task ShortLivedArgument_HandleParameterUnderGcPressure_NeverUsesAFreedHandle() =>
        Hammer(() => Assert.Equal(12, new Newsroom().NapMinutes(new Nap.Deep(minutes: 12))));

    // Generated `Equals` reads `other._handle` raw; `other` is unreferenced after that read.
    [Fact]
    public Task ShortLivedOther_EqualsUnderGcPressure_NeverUsesAFreedHandle() =>
        Hammer(() => Assert.True(new Toy("Mouse", "Gray").Equals(new Toy("Mouse", "Gray"))));

    // `NugetMarshal.Wrap` reads each element's `INugetHandle.Handle` raw before the `Add`, and the
    // list itself is unreferenced once `CreateList` has walked it. `Radii` reads every element back
    // inside Kotlin, so a freed element shows up as a wrong radius or a crash, not just a count.
    [Fact]
    public Task ShortLivedCollectionElements_CollectionParameterUnderGcPressure_NeverUseAFreedHandle() =>
        Hammer(() =>
        {
            IReadOnlyList<double> radii = Issue54Shapes.Radii(
                new List<Issue54Shape> { new Issue54Shape.Circle(1), new Issue54Shape.Circle(2) });
            Assert.Equal(new[] { 1.0, 2.0 }, radii);
        });

    // ADR-187's in-flight hazard: the desk is referenced by nothing once `SettleAsync` has started,
    // and its lazily created scope is what the coroutine runs in. A scope released by a finalizer
    // mid-flight cancels the call, which surfaces here as a `TaskCanceledException`. `settle` has no
    // suspension point, so the second half drives a call that genuinely suspends (`findToyName`
    // waits 100ms) with twenty of them in flight at once.
    [Fact]
    public Task DroppedWrapper_InFlightSuspendCall_CompletesAndIsNotCancelled() => UnderGcPressure(async () =>
    {
        for (int i = 0; i < 200; i++)
        {
            Assert.Equal("Oreo settled in 5 minutes", await new BoardingDesk("Oreo").SettleAsync(5));
        }

        for (int round = 0; round < 5; round++)
        {
            Task<string?>[] inFlight = Enumerable.Range(0, 20)
                .Select(_ => new AsyncCatService("toys").FindToyNameAsync("Oreo"))
                .ToArray();
            foreach (string? toy in await Task.WhenAll(inFlight))
            {
                Assert.Equal("toys mouse", toy);
            }
        }
    });

    // The Flow twin: neither the feeder nor the newsroom is referenced once enumeration has started,
    // and `Treats` suspends 50ms between items, so a scope or receiver freed mid-collection either
    // cuts the stream short or faults it. `Stream()` adds a wrapper-typed element on the same route.
    [Fact]
    public Task DroppedWrapper_InFlightFlow_YieldsEveryItem() => UnderGcPressure(async () =>
    {
        for (int round = 0; round < 3; round++)
        {
            var treats = new List<string>();
            await foreach (string treat in new CatFeeder("Mylo").Treats(5))
            {
                treats.Add(treat);
            }
            Assert.Equal(Enumerable.Range(1, 5).Select(i => $"Mylo ate treat #{i}"), treats);
        }

        for (int round = 0; round < 50; round++)
        {
            var titles = new List<string>();
            await foreach (TopStory story in new Newsroom().Stream())
            {
                using (story) titles.Add(story.Title);
            }
            Assert.Equal(
                new[] { "Oreo escapes the cardboard box (again)", "Mylo naps in a sunbeam for six hours straight" },
                titles);
        }
    });
}

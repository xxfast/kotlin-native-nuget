using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Clinic;
using TestLibrary.Models;
using TestLibrary.Routes;

namespace LeakTests;

/// <summary>
/// ADR-120: the forward bridge counts every live Kotlin <c>StableRef</c> behind one
/// <c>NugetHandles.retain</c>/<c>release</c> pair, and C# reads it as
/// <c>NugetMarshal.LiveHandles</c>. One test per crossing family: hammer the crossing N times,
/// settle, and the count must be exactly where it started. A leak is a number now, not an agent
/// noticing a <c>Dispose</c> that sits after a <c>throw</c>.
///
/// Oreo and Mylo cross the bridge fifty times each and are expected to come back every time.
/// The one test that says otherwise is the last one, and it is red on purpose.
/// </summary>
public class LiveHandleTests
{
    // A C#-side IPet, so `Interview` has to route through the ADR-084 bridge factory and dispose
    // the transfer StableRef after the native call. Rex is a dog, which Oreo tolerates.
    private sealed class Dog(string name) : IPet
    {
        public string Name { get; } = name;
        public int Legs => 4;
        public string? Nickname => null;
        public string Vibe => "waggy";
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
        public void Nap() { }
        public void Dispose() { }
    }

    /// <summary>
    /// Settle until the live count stops moving, not for a fixed number of rounds. The ADR-084
    /// cleaner frees handles asynchronously, so releases owed by earlier work land mid-window and
    /// show up as deltas that do not scale with the iteration count (some of them negative).
    /// Quiescence is <see cref="RequiredStableRounds"/> consecutive rounds reading the same value;
    /// the cap stops a genuinely leaking run from spinning here instead of failing. No tolerance
    /// band on the assertion side, because a band hides exactly the per-call leak this exists for.
    /// </summary>
    private const int MaxSettleRounds = 60;
    private const int RequiredStableRounds = 6;

    // Drain any backlog once, with a much longer quiet requirement, before the first measurement.
    // This mattered when the harness shared a process with the other 1500-odd tests: their
    // cleaners returned handles in spurts separated by gaps longer than a per-test settle window,
    // so a per-test settle could not tell that backlog apart from a leak of this class's own
    // making, and Windows CI went red on rows that mint nothing. The counter is process-global, so
    // the fix was to give it its own process: this assembly holds only the leak harness. The drain
    // now finds nothing to do and returns in its minimum rounds. Kept as cheap insurance, since
    // anything added to this assembly later brings its backlog back.
    static LiveHandleTests() => Settle(stableRounds: 20, maxRounds: 120);

    private static void Settle() => Settle(RequiredStableRounds, MaxSettleRounds);

    private static void Settle(int stableRounds, int maxRounds)
    {
        long previous = long.MinValue;
        int stable = 0;
        for (int round = 0; round < maxRounds; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            NugetBridge.GcCollect();   // ADR-084 cleaner round; harmless when nothing is pending
            Thread.Sleep(50);

            long current = NugetMarshal.LiveHandles;
            if (current == previous)
            {
                if (++stable >= stableRounds) return;
            }
            else
            {
                stable = 1;
                previous = current;
            }
        }
    }

    // A negative delta is not a leak of the crossing under test: it is a release owed by earlier
    // work landing inside the measurement window, and Kotlin's GC only runs it once this class
    // starts allocating, so no amount of up-front draining can move it out of the way. Re-measure
    // when that happens. Like the drain above, this was contamination insurance from when the
    // harness shared a process with the whole suite, and it now almost never fires. A positive
    // delta fails on the spot, with no tolerance band: that is the shape of the leak this harness
    // exists to catch, and that rule is unchanged.
    private const int MeasurementAttempts = 3;

    private static void AssertNoLeak(Action crossing, int iterations = 50)
    {
        for (int attempt = 1; ; attempt++)
        {
            Settle();
            long before = NugetMarshal.LiveHandles;

            for (int i = 0; i < iterations; i++) crossing();

            Settle();
            long after = NugetMarshal.LiveHandles;
            if (after == before) return;
            if (after < before && attempt < MeasurementAttempts) continue;

            Assert.Fail(
                $"expected {before} live handles after {iterations} crossings, got {after} (delta {after - before}) on attempt {attempt}");
        }
    }

    private static async Task AssertNoLeakAsync(Func<Task> crossing, int iterations = 10)
    {
        for (int attempt = 1; ; attempt++)
        {
            Settle();
            long before = NugetMarshal.LiveHandles;

            for (int i = 0; i < iterations; i++) await crossing();

            Settle();
            long after = NugetMarshal.LiveHandles;
            if (after == before) return;
            if (after < before && attempt < MeasurementAttempts) continue;

            Assert.Fail(
                $"expected {before} live handles after {iterations} crossings, got {after} (delta {after - before}) on attempt {attempt}");
        }
    }

    // Row 1. Class handle via using/Dispose: `cat_create` mints, `cat_dispose` releases.
    [Fact]
    public void ClassHandle_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 9);
            Assert.Equal("Oreo", oreo.Name);
        });
    }

    // Row 2. String parameter and string return on the ordinary route: no StableRef at all
    // (UTF-8 wire), so the count must not move even once.
    [Fact]
    public void StringParameterAndReturn_OrdinaryRoute_ReturnsToBaseline()
    {
        AssertNoLeak(() => Assert.Equal("Hello, Mylo", Greetings.Greet("Mylo")));
    }

    // Row 3a. List<String> parameter: CreateList handle plus one nuget_wrap_string box per
    // element, all released in the shim's finally (ADR-073/099).
    [Fact]
    public void ListParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Patient("Oreo");
            Assert.Equal(2, oreo.AddTags(new[] { "fluffy", "loud" }));
        });
    }

    // Row 3b. Map<String, Int> parameter: boxed key and boxed value per entry.
    [Fact]
    public void MapParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var mylo = new Patient("Mylo");
            Assert.Equal(7, mylo.RecordScores(new Dictionary<string, int> { ["agility"] = 3, ["cuddles"] = 4 }));
        });
    }

    // Row 3c. Set<String> parameter: the third collection kind, same finally.
    [Fact]
    public void SetParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Patient("Oreo");
            Assert.Equal(2, oreo.AddLabels(new HashSet<string> { "biscuit", "milo" }));
        });
    }

    // Row 4. List return, happy path: the list's own handle is disposed by the materialising
    // loop, and each element box is owned by the wrapper this test disposes.
    [Fact]
    public void ListReturn_HappyPath_ElementsDisposed_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var newsroom = new Newsroom();
            IReadOnlyList<TopStory> archive = newsroom.Archive();
            Assert.Equal(2, archive.Count);
            foreach (TopStory story in archive) story.Dispose();
        });
    }

    // Row 5. Callback subscribe/unsubscribe: StableRef.create(unregister) on subscribe,
    // ref.dispose() on Dispose (StoredCallbackExports.kt:216,238).
    [Fact]
    public void Callback_SubscribeUnsubscribe_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 9);
            var moods = new List<string>();
            IDisposable subscription = oreo.AddMoodListener(mood => moods.Add(mood.ToString()));
            oreo.TriggerMoodChange(TestLibrary.Cat.Mood.Happy);
            subscription.Dispose();
            Assert.Equal(new[] { "Happy" }, moods);
        });
    }

    // Row 6. ADR-084 C#-implemented interface as an argument: the transfer StableRef must be
    // disposed after the native call. The bridge object itself is GC-owned and not counted.
    [Fact]
    public void CSharpImplementedInterface_Argument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 9);
            using IPet rex = new Dog("Rex");
            Assert.Equal("Rex says: Woof!", oreo.Interview(rex));
        });
    }

    // Row 7. Flow enumerated to completion: per-item box disposed by the enumerator, job handle
    // disposed when the flow completes.
    [Fact]
    public async Task Flow_EnumeratedToCompletion_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var feeder = new CatFeeder("Oreo");
            var announcements = new List<string>();
            await foreach (string announcement in feeder.MealAnnouncements)
            {
                announcements.Add(announcement);
            }
            Assert.Equal(3, announcements.Count);
        });
    }

    // Row 8. Flow abandoned: one item taken, then the enumerator is disposed explicitly, which
    // cancels the job. Strict after `await DisposeAsync()` (dropping without disposing would be
    // the finalizer-driven, eventual case, which this row deliberately does not test).
    [Fact]
    public async Task Flow_AbandonedViaDisposeAsync_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var feeder = new CatFeeder("Mylo");
            IAsyncEnumerator<string> treats = feeder.Treats(100).GetAsyncEnumerator();
            Assert.True(await treats.MoveNextAsync());
            Assert.Equal("Mylo ate treat #1", treats.Current);
            await treats.DisposeAsync();
        });
    }

    // Row 9. Suspend call completing: the result box is unwrapped and owned by the returned
    // wrapper, and the job handle goes on completion. The two-argument overload is the 100ms
    // one, so fifty crossings do not take five minutes.
    [Fact]
    public async Task Suspend_Completes_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var service = new AsyncCatService("toys");
            using Cat oreo = await service.FetchCatAsync("Oreo", 9);
            Assert.Equal("Oreo", oreo.Name);
        });
    }

    // Row 9b. The ordering hole in ADR-019's suspend route. The generated wrapper's completion
    // callback disposes a `jobHandle` local that is assigned only after the P/Invoke returns, and
    // a body started with `CoroutineStart.ATOMIC` that never suspends can finish first: the
    // callback then disposes IntPtr.Zero and the job's own handle is leaked for good.
    // `fetch(ref) = ref + 1` (KeywordRoutesSample.kt:79) has no suspension point, so it drives
    // that window directly. Measured at roughly one leak per thousand crossings, hence 5000.
    [Fact]
    public async Task Suspend_NoSuspensionPoint_CompletesBeforeNativeReturns_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () => Assert.Equal(2, await KeywordRoutesSample.FetchAsync(1)),
            iterations: 5000);
    }

    // Row 9c. The cancellation-registration half of the same ADR-019 ordering hole: `reg` is
    // assigned after the native call too, so a callback that wins the race calls `reg.Dispose()`
    // on a default registration and the real one is never disposed. Whether that costs a Kotlin
    // handle is unknown, so this row may well stay green; it is here to pin the path.
    [Fact]
    public async Task Suspend_NoSuspensionPoint_WithCancellationToken_ReturnsToBaseline()
    {
        using var cts = new CancellationTokenSource();
        await AssertNoLeakAsync(
            async () => Assert.Equal(2, await KeywordRoutesSample.FetchAsync(1, cts.Token)),
            iterations: 5000);
    }

    /// <summary>
    /// The red case. `NugetMarshal.Factories` is a mutable dictionary (ADR-120 documents that
    /// mutability as a load-bearing test seam), so swapping the `TopStory` entry for a factory
    /// that throws on its second call is a deterministic mid-loop throw inside the outer
    /// returned-collection loop, with no fixture change.
    ///
    /// The replacement factory disposes the element box it was handed before throwing, so the
    /// only handle left unreleased is the list's own: today the `listHandle` Dispose sits after
    /// the loop, so the delta is expected to be exactly +1. This goes green with no edit once
    /// the outer loop is routed through ADR-099's finally-guarded ReadList/ReadSet/ReadMap.
    /// </summary>
    [Fact]
    public void ListReturn_ThrowingElementFactory_ReleasesTheListHandle()
    {
        Func<IntPtr, object> original = NugetMarshal.Factories[typeof(TopStory)];
        int calls = 0;
        NugetMarshal.Factories[typeof(TopStory)] = handle =>
        {
            if (++calls == 2)
            {
                NugetMarshal.Dispose(handle);
                throw new InvalidOperationException("Oreo swatted the archive off the desk");
            }
            return new TopStory(handle);
        };

        try
        {
            Settle();
            long before = NugetMarshal.LiveHandles;

            // The Newsroom handle is minted after `before` is read, so it has to be released
            // before `after` is read. A method-scoped `using var` would still be alive at the
            // assertion and would read as a +1 that is not a leak.
            using (Newsroom newsroom = new Newsroom())
            {
                Assert.Throws<InvalidOperationException>(() => newsroom.Archive());
            }

            Settle();
            long after = NugetMarshal.LiveHandles;
            Assert.True(
                after == before,
                $"expected {before} live handles after 1 throwing Archive() crossing, got {after} (delta {after - before}); the returned list's own handle is the one that leaks");
        }
        finally
        {
            NugetMarshal.Factories[typeof(TopStory)] = original;
        }
    }
}

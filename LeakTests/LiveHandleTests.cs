using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Clinic;
using TestLibrary.Dispenser;
using TestLibrary.Issue115;
using TestLibrary.Issue126;
using TestLibrary.Issue127;
using TestLibrary.Issue131;
using TestLibrary.Models;
using TestLibrary.Nested;
using TestLibrary.Parcel;
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

    // A C#-side nested-interface implementation for the StateFlow-of-interface row: `Book` crosses
    // it into Kotlin over the ADR-084 bridge and every `.Value` read resolves it back.
    private sealed class BookedKeeper : Aviary.IKeeper
    {
        public string Greet() => "booked";
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

    // Row 1a. ADR-133: a nested class mints through `aviary_perch_create` into the same
    // NugetHandles StableRef route Row 1 measures, and releases through
    // `aviary_perch_dispose`. No new mint path, so this is the happy-path checklist row, not
    // a new mechanism: the one thing it can catch is a nested export wired to a retain without the
    // matching release. Oreo takes the perch fifty times and comes down fifty times.
    [Fact]
    public void NestedClass_CreateAndDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var aviary = new Aviary("Oreo");
            using var perch = aviary.PerchAt(3);
            Assert.Equal(3, aviary.HeightOf(perch));
        });
    }

    // Row 1b. The ADR-035 value-class secondary constructor mints a Cat StableRef inside Kotlin
    // and hands it to the struct's underlying property, so the consumer disposes it like any other
    // `new Cat(...)`. Mylo is created fifty times from his name alone and must come back every time.
    [Fact]
    public void ReferenceValueClassSecondaryConstructor_DisposeUnderlying_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var result = new CatResult("Mylo");
            using var mylo = result.Cat;
            Assert.Equal("Mylo", result.Name);
        });
    }

    // Row 1c. Issue #222: a sealed subclass's exported constructor. A new mint path, because
    // before #222 no arm had a public constructor at all, and it is the one that chains
    // `: base(IntPtr.Zero)` and then stores the handle on the *base*, so a release wired to the
    // arm instead of the inherited field would show up here and nowhere else. Oreo takes a
    // twelve-minute nap fifty times and wakes up every time.
    [Fact]
    public void SealedSubclassConstructor_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            // Named argument on purpose: `int` converts to `IntPtr` implicitly and the generated
            // internal handle constructor compiles into this assembly, so a positional `Deep(12)`
            // binds to that instead and dereferences pointer 12.
            using var deep = new Nap.Deep(minutes: 12);
            Assert.Equal(12, deep.Minutes);
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

    // Row 4b. Nullable collection return, populated path (ADR-061's 2026-09-16 amendment). The
    // null pointer is the null, so the non-null branch mints exactly the same collection handle
    // Row 4 does and the materialising loop must still dispose it. A primitive element keeps the
    // row about the collection's own handle: there is no element wrapper for the caller to own.
    [Fact]
    public void NullableCollectionReturn_Populated_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var dispensary = new Dispensary(true);
            IReadOnlySet<int>? counts = dispensary.Counts();
            Assert.Equal(3, counts!.Count);
        });
    }

    // Row 4c. ADR-151 ByteArray result: the materialized `byte[]` route mints one StableRef per
    // crossing (`nuget_bytes_create` / `NugetHandles.retain`), and `NugetMarshal.ReadBytes`
    // disposes it in its finally after the count and the memcpy. Same ownership as Row 4, with no
    // element boxes at all, so any drift here is the array handle itself.
    [Fact]
    public void ByteArrayReturn_MaterializedResult_ReturnsToBaseline()
    {
        AssertNoLeak(() => Assert.Equal(new byte[] { 3, 2, 1 }, PayloadKt.Reverse(new byte[] { 1, 2, 3 })));
    }

    // Row 4d. ADR-151 empty ByteArray result: the zero-length branch skips the memcpy, so its
    // handle is disposed on a different path through ReadBytes. Oreo's collar sends three bytes,
    // Mylo's sends none, and neither may leak.
    [Fact]
    public void EmptyByteArrayReturn_ReturnsToBaseline()
    {
        AssertNoLeak(() => Assert.Empty(PayloadKt.Empty()));
    }

    // Row 3d. ADR-151 ByteArray parameter: `NugetMarshal.CreateBytes` mints the Kotlin-side array
    // handle before the call and the shim's finally disposes it, exactly as the collection
    // parameter rows above. The property setter takes the same row on the SETTER_VALUE slot.
    [Fact]
    public void ByteArrayParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var payload = new Payload(7, new byte[] { 1, 2, 3 });
            payload.Data = new byte[] { 4, 5 };
            Assert.Equal(new byte[] { 4, 5 }, payload.Data);
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

    // Row 6b. The same ADR-084 transfer handle, one slot to the left: a C#-implemented `IPet` as
    // the RECEIVER of an extension function. `HandleOf` mints a StableRef per crossing because
    // `Dog` has no `_handle`, so the receiver needs the same `finally`-dispose the argument
    // position got - the receiver used to bypass `interfaceCleanup` entirely, which is what makes
    // this a real leak surface rather than a duplicate of Row 6. (The nullable value-class
    // receiver, `CatId?.OrAnonymous()`, mints nothing on either side, so it gets no row.)
    [Fact]
    public void InterfaceReceiverExtension_CSharpImplementedPet_ReleasesTransferHandle()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            Assert.Equal("Rex has 4 legs and says Woof!", rex.Describe());
        });
    }

    // Row 6c. ADR-135: the same ADR-084 transfer handle, minted for an interface reached only at a
    // PARAMETER position. No new handle kind, so the lifecycle Row 6 measures is the one this has
    // to land on once the reachability walk is widened. The row exists because that widening is
    // what first makes a StableRef get minted here at all, and a fix that mints without disposing
    // is indistinguishable from a working one on the IntegrationTests side.
    private sealed class DeskClerk : Boarding.IClerk
    {
        public string Stamp() => "stamped";
        public void Dispose() { }
    }

    [Fact]
    public void ParameterOnlyInterface_Argument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var clerk = new DeskClerk();
            Assert.Equal("stamped filed at boarding", Boarding.FileVia(clerk));
        });
    }

    // Row 6d. The fault-injection twin, and the reason ADR-135 asks for a row rather than reusing
    // Row 6: an interface with a `var` member plans to null, so `NugetBridge.HandleFor` throws
    // before any handle is minted. The `finally` still runs, and today it disposes `IntPtr.Zero`
    // with no zero guard, which kills the process. This row pins that a FAILED mint neither leaks
    // nor disposes anything. The throw is asserted inside `AssertNoLeak`, not around it.
    private sealed class ClawMarks : IScratchLog
    {
        public int Scratches { get; set; } = 7;
        public void Dispose() { }
    }

    [Fact]
    public void UnbridgeableInterface_Argument_ThrowsAndReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var log = new ClawMarks();
            Assert.Throws<NotSupportedException>(() => CatteryDesk.CountScratches(log));
        });
    }

    // Row 6e. ADR-136: a C#-implemented `IPet` stored by Kotlin and read back over the SUSPEND
    // route. The handle the completion is handed is a transfer StableRef over the bridge, and the
    // read resolves it to the original `Dog` instead of wrapping it, so the resolve is what has to
    // release that handle now (on the sync route the consumer's `using` on the wrapper did it, and
    // here there is no wrapper to dispose). A resolve that returns the object and forgets the
    // handle leaks exactly once per completion while `Assert.Same` stays green.
    [Fact]
    public async Task SuspendReturn_ResolvedCSharpInterface_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var sitter = new PetSitter();
            using IPet rex = new Dog("Rex");
            sitter.Take(rex);

            IPet later = await sitter.HandBackLaterAsync();
            Assert.Equal("Woof!", later.Speak());
        });
    }

    // Row 6f. The Flow twin of Row 6e: one handle per emission, resolved per element rather than
    // per completion. Separate row because the freeing site is the `KotlinFlow<T>` `read:`
    // delegate, not the completion callback, and the enumerator's own box/job handles ride along
    // (a resolve that leaks here scales with elements, not with calls).
    [Fact]
    public async Task FlowElement_ResolvedCSharpInterface_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var sitter = new PetSitter();
            using IPet rex = new Dog("Rex");
            sitter.Take(rex);

            var seen = new List<IPet>();
            await foreach (IPet pet in sitter.Wards()) seen.Add(pet);
            Assert.Equal("Woof!", Assert.Single(seen).Speak());
        });
    }

    // Row 6g. The `suspend fun` returning `StateFlow<Interface>` read (site (b) of the interface
    // spelling sweep). Three handles ride on one call and each is freed at a different place: the
    // awaited StateFlow's own StableRef (owned by the returned `KotlinStateFlow<T>`, released by
    // the consumer's `using`), the `nuget_stateflow_value` read per `.Value`, and the ADR-136
    // resolve of the stored C# keeper. The other async rows all read a handle per completion or
    // per emission; this one reads a fresh element handle per `.Value` on a holder that outlives
    // the call, so a value read that forgets its handle leaks per read rather than per call.
    //
    // Mylo books a C# keeper and then checks the roster twice.
    [Fact]
    public async Task SuspendStateFlowOfInterface_ValueReads_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var aviary = new Aviary("Mylo");
            using Aviary.IKeeper booked = new BookedKeeper();
            aviary.Book(booked);

            using KotlinStateFlow<Aviary.IKeeper> report = await aviary.KeeperReportAsync();
            Assert.Equal("booked", report.Value.Greet());
            Assert.Equal("booked", report.Value.Greet());
        });
    }

    // Row 6h. The same minted receiver handle as Row 6b, but read through an extension PROPERTY
    // getter rather than an extension function. The getter body is the new surface: the setter
    // route already owns a handle scope, the getter body is flat, so without a `finally`-dispose
    // around it a read on a C#-implemented receiver leaks one StableRef per call, invisible to
    // IntegrationTests. (The nullable handle receiver, `Cat?.GetNameOrStray()`, mints nothing on
    // either side, so it gets no row.)
    [Fact]
    public void InterfaceReceiverExtensionProperty_CSharpImplementedPet_ReleasesTransferHandle()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            Assert.Equal("Rex/4/Woof!", rex.GetSummary());
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

    // Row 8b. Issue #131: a top-level factory taking a *borrowed* nullable handle. The Kotlin
    // thunk reads it with `logger?.asStableRef<Logger>()?.get()`, which must not take ownership:
    // if it disposed the ref, the caller's own `logger` would go with it. Both spellings run in
    // one crossing, so a leak on either the null or the non-null path shows up here.
    [Fact]
    public void NullableHandleParameter_TopLevelFactory_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var settings = new Settings(3);
            using var logger = new Logger("Oreo");
            using Hub withLogger = HubSample.Hub(settings, logger, "n");
            using Hub withoutLogger = HubSample.Hub(settings, null, null);
            Assert.Equal("3/Oreo/n", withLogger.Describe());
            Assert.Equal("3/none/-", withoutLogger.Describe());
        });
    }

    // Row 8c. Issue #126: a *borrowed* handle at a parameter on the legacy StateFlow route. The
    // C# side passes `observation._handle` without minting anything, and the Kotlin export
    // dereferences it into a local rather than taking a StableRef of its own, so the whole
    // crossing must mint no handle beyond the ones the wrapper and the read already own. The
    // eager dereference is what makes that non-obvious: a fix that took ownership to keep the
    // object alive across the flow would show up here as a per-crossing leak, and nowhere else.
    [Fact]
    public void HandleParameter_StateFlowValueRead_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var radio = new ObservationRadio();
            using Observation observation = ObservationKt.OpenBox("Oreo");
            Observation.Alive alive = Assert.IsType<Observation.Alive>(observation);
            Assert.Equal("alive:Oreo", radio.Watch(alive).Value);
        });
    }

    // Row 8d. Issue #127 / ADR-123: a *collection* element on the Flow and StateFlow routes. Each
    // emission and each `.Value` read mints a fresh StableRef for the collection itself plus one
    // box per element, and none of it is disposed by the flow enumerator: the collection handle
    // goes in `ReadList`/`ReadSet`'s finally, and the element boxes are owned by whatever the read
    // returns. So a read lambda wired wrong leaks one handle per emission, not one per crossing,
    // which is why the count is high. Both halves in one crossing: `Ticks` emits three lists of
    // handle elements, `Items` reads a set whose elements project to their underlying.
    [Fact]
    public async Task CollectionFlowElement_EnumerationAndValueRead_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var hub = new NodeHub();
                await foreach (IReadOnlyList<Kind> page in hub.Ticks)
                {
                    foreach (Kind kind in page) kind.Dispose();
                }
                Assert.Equal(3, hub.Items.Value.Count);
            },
            iterations: 200);
    }

    // Row 8e. Issue #129 / ADR-124: the same flow route as Row 7, with a *sealed arm* as the owner.
    // The arm mints nothing new (the per-item box, the job handle and the subscription all come
    // from the same builders), but it owns its scope through the arm's own `_scopeHandle` and
    // drains it in the arm's `DisposeAsync`, so `await using` is the spelling under test: a scope
    // created per collect and never drained, or a `DisposeAsync` that disposes the handle without
    // draining, shows up here as a per-crossing leak and nowhere else.
    [Fact]
    public async Task Flow_OnASealedArm_EnumeratedToCompletion_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var factory = new JobFactory();
            await using Job.Watching mylo = factory.Watching("Mylo");
            var labels = new List<string>();
            await foreach (string label in mylo.Labels("tick", 3))
            {
                labels.Add(label);
            }
            Assert.Equal(3, labels.Count);
        });
    }

    // Row 8g. Issue #115 / ADR-036 on a sealed arm: the lambda-parameter route re-keyed onto the
    // arm's own export prefix. Every crossing mints handles on both sides of the thunk: Kotlin
    // retains the `String` argument it hands the callback and releases it after the invoke, and
    // C#'s answer comes back as a `WrapString` box Kotlin releases once the outer return is read.
    // So a route that forgets either release leaks one or two handles *per call*, not per wrapper,
    // and the arm receiver makes it its own row: the arm's handle is the one under the callback.
    [Fact]
    public void LambdaParameter_OnASealedArm_StringInAndOut_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var factory = new JobFactory();
            using Job.Running oreo = factory.Running(40);
            Assert.Equal("running-40!", oreo.Relabel(s => s + "!"));
        });
    }

    // Row 8h. The same per-call lambda-parameter route (ADR-036) on an ordinary class, so the
    // sealed arm above is not the only witness: `Cat.describeWith` takes a `(String) -> String`
    // and hands Kotlin's own `name` across as a retained handle. One row per receiver kind,
    // because a fix that keys the payload's ownership off the arm's export prefix would leave this
    // one red. Mylo gets described fifty times and the count has to land where it started.
    [Fact]
    public void LambdaParameter_OnAnOrdinaryClass_StringInAndOut_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var mylo = new Cat("Mylo", 9);
            Assert.Equal("This cat is called Mylo", mylo.DescribeWith(name => $"This cat is called {name}"));
        });
    }

    // Row 8i. The other payload kind on the same route: an exported object rather than a `String`.
    // `Cat.forEachToy` hands a `Toy` handle to the callback once per toy, and C#'s `Materialize`
    // does not dispose it, so the wrapper the lambda takes ownership of is the only disposer. The
    // `String` rows above cannot see that branch at all: they exercise the marshalled kind, whose
    // C#-side unwrap disposes for you. Two toys per crossing, so a per-payload miscount shows up
    // at twice the rate of the rows above.
    [Fact]
    public void LambdaParameter_OnAnOrdinaryClass_ObjectPayload_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 9);
            var toyNames = new List<string>();
            oreo.ForEachToy(toy =>
            {
                using var t = toy;
                toyNames.Add(t.Name);
            });
            Assert.Equal(new List<string> { "Mouse", "Ball" }, toyNames);
        });
    }

    // Row 8j. The *other* callback family with a handle payload: the interface-bridge route
    // (ADR-036 amendment, 2026-09-11). `CatEventSource.trigger()` calls `onMeow(msg)` on every
    // registered listener, minting one handle per crossing. This route freed that handle three
    // times, not two: `FromHandle<string>` disposes as it reads, the generated thunk spelled an
    // explicit `NugetMarshal.Dispose(arg0Ptr)` after it, and Kotlin released once more after the
    // invoke. Measured at -2 per crossing (-100 over the 50 below) before the fix, which is why
    // this row exists rather than a note in the backlog: one owner is `FromHandle`, and this is
    // the row that says so.
    private sealed class ProbeListener : ICatEventListener
    {
        public int Meows { get; private set; }
        public void OnMeow(string message) => Meows++;
        public void OnPurr() { }
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceBridge_StringPayload_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var source = new CatEventSource("Oreo");
            var listener = new ProbeListener();
            using IDisposable sub = source.AddListener(listener);
            source.Trigger();
            Assert.Equal(1, listener.Meows);
        });
    }

    // Rows 8k and 8l. ADR-116's 2026-09-13 amendment: the same two callback families, one owner
    // kind to the left. A stored-callback pair (`StableRef.create(unregister)` on subscribe,
    // `ref.dispose()` on `Dispose`) and an interface-bridge pair (that, plus one retained handle
    // per `String` payload the C# thunk owns) declared on a **sealed arm**, so the receiver is a
    // `StableRef<Job.Running>` / `StableRef<Job.Idle>` rather than an ordinary class. The pair's
    // own handles are unchanged; the arm receiver is the new thing, and a re-key that retains the
    // receiver per subscription rather than borrowing it shows up here as +N and nowhere else.
    private sealed class ProbeWatcher : IJobWatcher
    {
        public int Wakes { get; private set; }
        public void OnWake(string reason) => Wakes++;
        public void Dispose() { }
    }

    [Fact]
    public void SealedArm_StoredCallbackPair_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var factory = new JobFactory();
            using Job.Running oreo = factory.Running(40);
            var ticks = new List<string>();
            IDisposable subscription = oreo.AddTicker(tick => ticks.Add(tick));
            oreo.Tick();
            subscription.Dispose();
            Assert.Equal(new[] { "tick:40" }, ticks);
        });
    }

    // `Job.Idle` is a process-wide `data object`, so an undisposed bridge here would outlive the
    // iteration and be fired again by the next one: the `using` is load-bearing, not stylistic.
    [Fact]
    public void SealedArm_InterfaceBridgePair_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var factory = new JobFactory();
            using Job.Idle mylo = factory.Idle();
            var watcher = new ProbeWatcher();
            using IDisposable sub = mylo.AddWatcher(watcher);
            mylo.Wake("hallway");
            Assert.Equal(1, watcher.Wakes);
        });
    }

    // Row 8f. ADR-071: a `MutableStateFlow<T>` returned from a function, held by the wrapper. The
    // fix mints a StableRef for the flow itself on every call (the wrapper's `ownedHandle`, freed
    // in `Dispose()`), which is a handle no other flow route owns: the property half re-reads a
    // field and the read-only routes mint nothing per call. So a `Dispose()` that forgets the
    // owned handle, or a fix that retains the flow on each `.Value` access instead of once per
    // call, shows up here as a per-crossing leak and nowhere else.
    [Fact]
    public void MutableStateFlowFunctionReturn_WriteReadDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var dispenser = new CatSnackDispenser();
            using var level = dispenser.Level();
            level.Value = 7;
            Assert.Equal(7, level.Value);
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

    // Row 9d. A suspend call whose result is a *nested sealed arm*. The result box is a
    // `StableRef` to a `Job.Running` rather than an ordinary class, so the completion callback
    // unwraps it into the arm wrapper and the arm's own `DisposeAsync` has to release both the
    // handle and the scope it gained from its suspend members. A completion that constructs the
    // wrapper without transferring ownership, or an arm scope drained by nobody, shows up here.
    [Fact]
    public async Task Suspend_ReturningANestedSealedArm_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var factory = new JobFactory();
            await using Job.Running oreo = await factory.RunningLaterAsync(9);
            Assert.Equal(9, oreo.Progress);
        });
    }

    // Row 9e. A suspend call whose result is the sealed *base* rather than an arm. Kotlin mints
    // the StableRef on the concrete arm, and the completion has to hand that same handle to
    // `Job.FromHandle`, which discriminates and constructs the arm wrapper that then owns it. A
    // completion that reads the discriminator through a second handle, or mints one to read the
    // type and forgets it, shows up here and nowhere in the functional tests: those assert the
    // payload, which a double mint answers correctly.
    [Fact]
    public async Task Suspend_ReturningTheSealedBase_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var factory = new JobFactory();
            await using Job.Running oreo = factory.Running(9);
            using Job next = await oreo.NextLaterAsync();
            Assert.Equal(10, Assert.IsType<Job.Done>(next).Code);
        });
    }

    // Row 9h. A suspend call whose result is an *interface*. No interface-return suspend row
    // existed at all: Rows 9d/9e return a class and a sealed base, both of which the completion
    // constructs directly. An interface return puts a second object in play -- the ADR-040 backing
    // wrapper the completion mints around `resultPtr` and hands out as `IKeeper` -- so a
    // completion that constructs the wrapper without taking ownership of the handle, or reads the
    // handle a second time on the way to choosing a spelling, leaks per call while the functional
    // cells (which assert only `Greet()`) stay green. Pattern of Row 9e.
    [Fact]
    public async Task Suspend_ReturningAnInterface_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var aviary = new Aviary("Oreo");
            using Aviary.IKeeper keeper = await aviary.CurrentKeeperLaterAsync();
            Assert.Equal("hi from Oreo", keeper.Greet());
        });
    }

    // Row 9f. The nullable twin. Both branches inside one crossing: the null return mints no
    // handle at all (a guard that releases something it never received goes negative here), and
    // the arm return goes through the same discriminated read as Row 9e.
    [Fact]
    public async Task Suspend_ReturningTheNullableSealedBase_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var factory = new JobFactory();
            await using Job.Running finished = factory.Running(100);
            Assert.Null(await finished.NextOrNullLaterAsync());

            await using Job.Running oreo = factory.Running(12);
            using Job? next = await oreo.NextOrNullLaterAsync();
            Assert.Equal(13, Assert.IsType<Job.Done>(next).Code);
        });
    }

    // Row 9i. The ADR-025 drain has the same ADR-019 ordering hole the suspend call sites had:
    // `nuget_scope_drain` launches its job `ATOMIC` on `Dispatchers.Default`, and a scope with no
    // live children (the normal case, the awaited call has already finished) completes and fires
    // the completion callback before the `Drain` P/Invoke has returned the job handle. The
    // callback then disposed a still-zero `drainJobHandle` local and the drain job's own
    // `StableRef` leaked. That is the +1 Rows 9f and 8e went red with on CI, five first attempts
    // over four days, both OSes, never on a synchronous row. One suspend call per iteration so
    // the scope exists (a scope-less `DisposeAsync` skips the drain), then `await using` drains
    // it idle. Measured at roughly one leak per thousand drains, hence 5000.
    [Fact]
    public async Task DisposeAsync_IdleScopeDrainCompletesBeforeNativeReturns_ReturnsToBaseline()
    {
        using var factory = new JobFactory();
        await AssertNoLeakAsync(
            async () =>
            {
                await using Job.Running finished = factory.Running(100);
                Assert.Null(await finished.NextOrNullLaterAsync());
            },
            iterations: 5000);
    }

    // Row 9g. The throw path of the same route, which no row covered for a suspend call at all
    // (the only `Throws` row before this one is the synchronous `Archive` at the bottom). When the
    // body throws, no result is minted and the ADR-128/130 error envelope crosses instead, so this
    // pins that `NugetErrorNative.BuildException` releases what it was handed. `nextOrThrowLater`
    // has no suspension point, so the body completes before the P/Invoke returns and the ADR-019
    // ordering window is open on the error path too: hence the tight loop rather than the default
    // ten, on the `Suspend_NoSuspensionPoint_...` precedent.
    [Fact]
    public async Task Suspend_ReturningTheSealedBase_Throws_ReturnsToBaseline()
    {
        using var factory = new JobFactory();
        await using Job.Running oreo = factory.Running(4);

        // The receiver is hoisted out of the measured window so the loop crosses nothing but the
        // throwing call, and the arm's scope is minted lazily (`GetOrCreateScope()` on the first
        // suspend call), so it has to be warmed up *before* the baseline is read. Without this the
        // scope handle is retained inside the loop and released only when `oreo` is disposed, long
        // after the measurement: a +1 that is the harness's own doing, and one the negative-delta
        // retry would not absorb.
        await Assert.ThrowsAnyAsync<InvalidOperationException>(
            async () => await oreo.NextOrThrowLaterAsync(-1));

        await AssertNoLeakAsync(
            async () => await Assert.ThrowsAnyAsync<InvalidOperationException>(
                async () => await oreo.NextOrThrowLaterAsync(-1)),
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

    // Row 10. ADR-147: a `T` parameter and a `T` return on a generic-class method. `Wrap<int>`
    // mints one `nuget_wrap_int` box per T argument, the Kotlin export borrows it, and the C#
    // `finally` disposes it; the `String` return of Describe mints nothing; the `T` return of Pick
    // mints one retain that `FromHandle<int>` unwraps and disposes. The constructor's `T` argument
    // is the same box shape, which is new under ADR-147 sub-option (b): a primitive used to be
    // passed by value through `crate_create_int`.
    // Ledger per iteration: wrap +1/-1 (ctor), crate_create +1, wrap +1/-1 (Describe),
    // wrap +1/-1 and retain +1/-1 (Pick), crate_dispose -1. Net zero.
    [Fact]
    public void GenericClassMethod_BoxedTypeParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var crate = new Crate<int>(3);
            Assert.Equal("7:3", crate.Describe(7));
            Assert.Equal(7, crate.Pick(7));
        });
    }

    // Row 10a. The other half of the `T` wire, which Row 10 cannot reach: an exported class at
    // `T`. `Wrap<Cat>` contributes the wrapper's own live handle with `owned = false`, so the
    // ctor and the parameter mint *nothing* and the `finally` must not dispose anything either;
    // the `T` return of Pick still mints one retain, which becomes the `picked` wrapper's handle
    // and is released by its own `using`. A double-free here reads as a negative delta, an
    // over-owned box as a positive one.
    [Fact]
    public void GenericClassMethod_ExportedClassTypeParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 9);
            using var crate = new Crate<Cat>(oreo);
            using Cat picked = crate.Pick(oreo);
            Assert.Equal("Oreo", picked.Name);
        });
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

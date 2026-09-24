using Test.Menagerie;
using TestLibrary;
using TestLibrary.Admission;
using TestLibrary.Cat;
using TestLibrary.Dev.Other.Bytype;
using TestLibrary.Clinic;
using TestLibrary.Dispenser;
using TestLibrary.Issue115;
using TestLibrary.Issue126;
using TestLibrary.Issue127;
using TestLibrary.Issue131;
using TestLibrary.Issue236;
using Issue297 = TestLibrary.Issue297;
using TestLibrary.Kennel;
using TestLibrary.Lounge;
using TestLibrary.Metronome;
using TestLibrary.Models;
using TestLibrary.Nested;
using TestLibrary.Objectprops;
using TestLibrary.Parcel;
using TestLibrary.Routes;
using TestLibrary.Workshop;

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

    // Row 1d. ADR-141: an `inner class` constructor, the new forward route. Unlike Row 1a it takes
    // a borrowed outer handle in and mints a fresh one out (`hearth_sunbather_create(outer,
    // minutes, error)`), so the borrowed receiver must not be retained a second time on the way
    // in, and the inner must release on the way out. Either mistake is a rising count here and
    // nowhere else. Oreo takes the hearth fifty times and gives it back fifty times.
    [Fact]
    public void InnerClassConstructor_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var hearth = new Hearth("The bay window");
            using var sunbather = new Hearth.Sunbather(hearth, 3);
            Assert.Equal(3, hearth.MinutesOf(sunbather));
        });
    }

    // Row 1e. The fault-injection half of Row 1d: `require(minutes >= 0)` inside the inner's `init`
    // throws after the outer handle has crossed but before any inner handle exists. The outer is
    // borrowed, so the count has to be flat; an implementation that retains the receiver on the way
    // in and releases it only on the success path leaks exactly one handle per throw. Mylo asks for
    // negative sunbathing fifty times and is refused every time.
    [Fact]
    public void InnerClassConstructor_ThrowingInit_DoesNotLeakTheOuterHandle()
    {
        AssertNoLeak(() =>
        {
            using var hearth = new Hearth("The bay window");
            Assert.ThrowsAny<ArgumentException>(() => new Hearth.Sunbather(hearth, -1));
        });
    }

    // Row 1f. ADR-157 (issue #236): the boxed enum arm's constructor, the one new mint path of the
    // feature. `new PatchArm(Patch.Socks)` mints a StableRef to a Kotlin enum *entry*, a permanent
    // singleton, so nothing about the Kotlin object's lifetime can hide a missed release: the count
    // is the only signal there is. Two boxes of one entry are two independent handles, so the row
    // takes both and each must come back. Oreo puts his socks on fifty times.
    [Fact]
    public void BoxedEnumArmConstructor_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var socks = new PatchArm(Patch.Socks);
            using var alsoSocks = new PatchArm(Patch.Socks);
            Assert.Equal(Patch.Socks, socks.Value);
            Assert.Equal(socks, alsoSocks);
        });
    }

    // Row 1g. The read half of Row 1f: an arm handed *out* through the discriminator rather than
    // minted by C#. `Portrait.Marking` retains the entry on the Kotlin side and C# reconstructs it
    // through `Marking.FromHandle`, so the box the getter hands back owns a second handle that only
    // `Dispose` releases (no finalizers anywhere in the generated wrappers). Holder and arm are
    // separate handles and both are counted. Mylo is painted, read back and put away fifty times.
    [Fact]
    public void BoxedEnumArmRead_ThroughAHolderProperty_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var cream = new SwirlArm(Swirl.Cream);
            using var portrait = new Portrait(cream);
            using Marking read = portrait.Marking;
            Assert.Equal(Swirl.Cream, Assert.IsType<SwirlArm>(read).Value);
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

    // Row 4e. `ByteArray` as a collection COMPONENT at a return (ROADMAP Phase 4, the position
    // ADR-151 deferred). Every element mints its own StableRef inside `nuget_list_get`, exactly
    // as a standalone `byte[]` return does, and `NugetMarshal.ReadBytes` disposes each one after
    // its memcpy. N elements per crossing instead of one, so a missing per-element dispose shows
    // up here N times faster than on Row 4c.
    [Fact]
    public void ListOfByteArrayReturn_ElementHandlesDisposed_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            IReadOnlyList<byte[]> chunks = PayloadKt.BurstChunks(new byte[] { 1, 2, 3, 4, 5 }, 2);
            Assert.Equal(3, chunks.Count);
        });
    }

    // Row 3e. The same component at a PARAMETER: `CreateBytes` mints one handle per element before
    // the `Add`, and the fill loop must dispose each owned box right after storing it, plus the
    // list's own handle in the shim's finally. Two ownership rules on one crossing.
    [Fact]
    public void ListOfByteArrayParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            byte[] spliced = PayloadKt.SpliceBursts(
                new[] { new byte[] { 1, 2 }, Array.Empty<byte>(), new byte[] { 0x00, 0xFF } });
            // 2 + 0 + 2. The row's point is the handle accounting; the length check is only here so
            // a crossing that quietly dropped an element could not pass by leaking nothing.
            Assert.Equal(4, spliced.Length);
        });
    }

    // Row 4f. The MAP VALUE slot, both directions on one call: a bytes handle per value on the way
    // in (disposed after `Put`) and a fresh one per value on the way out (disposed by `ReadBytes`).
    // The key is a string, so any drift here is the value slot's.
    [Fact]
    public void MapOfByteArrayValues_RoundTrip_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            IReadOnlyDictionary<string, byte[]> rewound = PayloadKt.RewindCollars(
                new Dictionary<string, byte[]> { ["oreo"] = new byte[] { 1, 2, 3 }, ["mylo"] = new byte[] { 4 } });
            Assert.Equal(new byte[] { 3, 2, 1 }, rewound["oreo"]);
        });
    }

    // Row 4g. NULLABLE components, both directions. The null element rides a null pointer, which
    // nothing may dispose and nothing may leak: on the read side `ReadBytes` is skipped entirely
    // for that slot, on the write side `Wrap` must report the zero handle as unowned. A row that
    // only crossed non-null elements could not tell either mistake apart from correct code.
    [Fact]
    public void NullableByteArrayElements_BothDirections_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            IReadOnlyList<byte[]?> signals = PayloadKt.PatchySignals();
            Assert.Null(signals[1]);

            IReadOnlyList<int> missing = PayloadKt.MissingSignals(
                new List<byte[]?> { new byte[] { 1 }, null, Array.Empty<byte>(), null });
            Assert.Equal(new[] { 1, 3 }, missing);
        });
    }

    // Row 3f. THROW PATH on the write side: a `null` inside a NON-null `List<byte[]>` argument
    // blows up mid-fill (a NullReferenceException off `value.Length`, or an ArgumentNullException
    // if the arm guards instead - the exception type is the implementer's, so this asserts only
    // that it throws). The elements already minted before the throw, and the half-built list's own
    // handle, must still be released: the leak counter is the assertion here, not the exception.
    [Fact]
    public void NullInsideANonNullListOfByteArray_ThrowsMidFill_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.ThrowsAny<Exception>(() =>
                PayloadKt.SpliceBursts(new List<byte[]> { new byte[] { 1, 2 }, null!, new byte[] { 3 } }));
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

    // Row 6e-null. The NULL half of Row 6e: a nullable suspend interface return that completes with
    // `IntPtr.Zero`. Kotlin mints no result handle here, so the only handles in flight are the
    // callback `GCHandle` and the job cell, and this row is what says the null path frees those
    // anyway. A completion path that only disposes its bookkeeping on the non-null branch leaks
    // exactly on the branch no other row walks.
    //
    // Nobody drops a cat off, fifty nights running.
    [Fact]
    public async Task NullableSuspendReturn_NullResult_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var sitter = new PetSitter();
            Assert.Null(await sitter.HandBackLaterOrNullAsync());
            Assert.Null(await PetKt.StrayPetLaterOrNullAsync(false));
        });
    }

    // Row 6e-resolved. Row 6e through the NULLABLE read: the same transfer handle, but the read now
    // tests the pointer before probing the bridge. The handle still has to be released by the
    // resolve, and a nullable read that returns the original and forgets the handle leaks once per
    // completion with `Assert.Same` staying green, exactly as on the non-null arm.
    [Fact]
    public async Task NullableSuspendReturn_ResolvedCSharpInterface_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var sitter = new PetSitter();
            using IPet rex = new Dog("Rex");
            sitter.Take(rex);

            IPet? later = await sitter.HandBackLaterOrNullAsync();
            Assert.Same(rex, later);
            Assert.Equal("Woof!", later!.Speak());
        });
    }

    // Row 6f-null. The nullable ELEMENT arm on a `StateFlow<Pet?>`, both of the ways it is read:
    // `.Value` (a fresh element handle per read, on a holder that outlives the call) and one
    // collected emission. The null read mints nothing, the resolved read mints a transfer handle
    // the `read:` delegate must free, and the property and the method are separate generated call
    // sites over the same underlying flow, so all four reads ride in one crossing.
    [Fact]
    public async Task NullableStateFlowInterfaceElement_ValueAndCollect_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var sitter = new PetSitter();
            Assert.Null(sitter.Watching.Value);

            using IPet rex = new Dog("Rex");
            sitter.Take(rex);
            Assert.Same(rex, sitter.Watching.Value);

            using KotlinStateFlow<IPet?> now = sitter.WatchingNow();
            Assert.Same(rex, now.Value);

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            await foreach (IPet? seen in sitter.Watching.WithCancellation(cts.Token))
            {
                Assert.Same(rex, seen);
                break;
            }
        });
    }

    // Row 6f-race. The TIGHT LOOP row. `strayPetLaterOrNull` is a `suspend fun` with no suspension
    // point, so the coroutine can finish and invoke the completion callback before the P/Invoke
    // that started it has returned its job handle to C#. A job cell freed on the wrong side of that
    // race, or a result handle retained by a completion that beat its own registration, leaks on a
    // fraction of calls: it reads as +1 per thousand, not +1 per call, so fifty crossings cannot
    // see it. Both branches alternate so the null path shares the same window.
    //
    // Oreo and Mylo check the cat flap two thousand times in a row.
    [Fact]
    public async Task NullableTopLevelSuspendInterface_TightLoop_ReturnsToBaseline()
    {
        int round = 0;
        await AssertNoLeakAsync(
            async () =>
            {
                bool found = (round++ % 2) == 0;
                IPet? stray = await PetKt.StrayPetLaterOrNullAsync(found);
                if (found)
                {
                    Assert.NotNull(stray);
                    stray!.Dispose();
                }
                else
                {
                    Assert.Null(stray);
                }
            },
            iterations: 2000);
    }

    // Row 6f-plain. The same nullable element on a PLAIN `Flow<Pet?>` rather than a StateFlow: one
    // handle per emission, and the null emission in the middle must mint and free nothing. Its own
    // row because the freeing site is the flow enumerator's `read:` delegate and a null element on
    // that route is not threaded today (the consumer-side fact in `BidirectionalTests` measures
    // what it does); whatever makes that fact green has to keep the count flat here too.
    [Fact]
    public async Task NullablePlainFlowInterfaceElement_Collected_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var window = new PassersBy();

            var seen = new List<IPet?>();
            await foreach (IPet? pet in window.PetsPassingBy()) seen.Add(pet);

            Assert.Equal(3, seen.Count);
            Assert.Null(seen[1]);
            seen[0]!.Dispose();
            seen[2]!.Dispose();
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

    // Row 6i. ADR-132 parity at the extension-PROPERTY receiver: a COLLECTION receiver. This is the
    // first receiver shape on either route whose C# prelude *builds* a Kotlin object for the
    // crossing - `NugetListNative` mints one StableRef for the list and the `finally` has to
    // dispose it. Unlike Rows 6b/6h the mint is unconditional (there is no "the object already has
    // a handle" branch), so a getter body rendered without a handle scope leaks exactly once per
    // read, and IntegrationTests still reads the right name every time.
    //
    // Oreo, Mylo and the neighbour's Whiskers, counted fifty times over.
    [Fact]
    public void CollectionReceiverExtensionProperty_ReleasesTheListHandle()
    {
        AssertNoLeak(() =>
        {
            var basket = new List<string> { "Oreo", "Mylo", "Whiskers" };
            Assert.Equal("Whiskers", basket.GetLongestName());
        });
    }

    // Row 6j. The setter half of the same shape, and the other half of Row 6h's receiver: a `var`
    // of NULLABLE-PRIMITIVE type over an INTERFACE receiver. The setter's has-value fan-out arm is
    // a different body from the getter's, with its own receiver mint (`HandleOf` on a
    // C#-implemented `Pet`) and its own `finally`. Both branches of the fan-out run here, because a
    // null value takes the arm that skips the value slot and could just as easily skip the
    // receiver's dispose.
    [Fact]
    public void InterfaceReceiverExtensionPropertySetter_CSharpImplementedPet_ReleasesTransferHandle()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            rex.SetNapQuota(2);
            Assert.Equal(2, rex.GetNapQuota());
            rex.SetNapQuota(null);
        });
    }

    // Row 6k. The BOUND-INTERFACE receiver (ADR-088). Caveat, stated rather than left implied: the
    // handle minted on the C# side for this shape is a managed `GCHandle`, and this harness counts
    // Kotlin `StableRef`s (`NugetMarshal.LiveHandles`), so the row can only observe the Kotlin half
    // - the wrapper StableRef the ADR-070 cleaner owns, and any StableRef minted by a token-probe
    // hit. A pure GCHandle leak on the C# side would leave this row green. It is here because the
    // Kotlin half is real and because a receiver-position regression that mints a wrapper per
    // crossing without ever releasing it is exactly what it would catch.
    private sealed class LeakGoat : IFeedable
    {
        public string Describe() => "Nibbles the C#-side goat";
        public int Legs => 4;
        public void Feed(string food) { }
        public string? Nickname { get; set; }
    }

    [Fact]
    public void BoundInterfaceReceiverExtensionProperty_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            IFeedable nibbles = new LeakGoat();
            Assert.Equal("Nibbles the C#-side goat needs 4 bowls", nibbles.GetFeedingNote());
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

    // Row 9. ADR-152: the reverse async crossing. Each awaited call mints a pending-continuation
    // StableRef through `NugetHandles.retain` (open question 3: counted on purpose, so this row
    // can exist at all) plus a GCHandle on the C# Task, and both have to be gone once the
    // continuation resumed: the ctx in the completion callback, the Task handle in `End`'s
    // `finally`. The first reverse-side handles `nuget_live_handles` has ever counted, so a
    // non-zero delta here is unambiguous: nothing else in this assembly mints one.
    [Fact]
    public async Task ReverseAsync_AwaitedCall_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal("Oreo & Mylo", await KennelSample.KennelNameAsync());
        });
    }

    // Row 9a. The already-completed task (Task.FromResult), driven in a tight loop INSIDE one
    // Kotlin coroutine. Separate row from 9 because the leak it can find is different: when the
    // completion callback lands before `suspendCancellableCoroutine`'s block returns, the ctx is
    // released on a path that row 9's genuinely-delayed task almost never takes, and at this
    // iteration count a per-call leak on that path is a five-figure delta rather than a rounding
    // error.
    [Fact]
    public async Task ReverseAsync_AlreadyCompletedTask_TightLoop_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () => Assert.Equal(7 * 1_000, await KennelSample.SeatedRepeatedlyAsync(1_000)),
            iterations: 5);
    }

    // Row 9b. The faulting task. The fault path never reaches `End`'s success return, so the Task
    // GCHandle is freed in its `finally` or not at all, and the ctx is released by a callback that
    // fired for a task that threw. A channel that only cleans up on success leaks exactly here,
    // and nowhere in rows 9 or 9a.
    [Fact]
    public async Task ReverseAsync_FaultedTask_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            Assert.StartsWith("System.InvalidOperationException|", await KennelSample.OreoEscapesAsync());
        });
    }

    // Rows 9j to 9l. ADR-153: the reverse async crossing with a bridge-owned
    // CancellationTokenSource riding on it. (Labelled 9j onward because the forward suspend rows
    // already own 9b to 9i; these three sit with the reverse rows above.)
    //
    // WHAT THESE ROWS CAN AND CANNOT SEE. `nuget_live_handles` counts Kotlin StableRefs only, and
    // the CTS handle is a .NET GCHandle: it is NOT counted here, on either path. So these rows
    // prove the `ctx` (pending-continuation) and Task-handle baseline on the cancellation paths,
    // and they say nothing about whether the CTS handle itself was freed exactly once. That half
    // lives in the runtime's AwaitForKotlinTest with a counting fake, and a C#-side handle counter
    // is a deferred ROADMAP item (ADR-153 open question 6). Worth having anyway: the cancelled
    // path frees the ctx from a DIFFERENT place than every row above it (the coroutine's own
    // cancellation, with `End` never called), which is precisely where a ctx can be dropped.

    // Row 9j. The CANCELLED path: the Kotlin wait ends on its own cancellation and `End` is never
    // called, so the ctx released by rows 9/9a/9b in the completion callback has to be released by
    // `resume`'s onCancellation here instead. A channel that only cleans up when a completion
    // arrives leaks exactly one ctx per cancelled call. The returned count is asserted so a run in
    // which nothing was actually cancelled cannot pass as a clean one.
    [Fact]
    public async Task ReverseAsync_CancelledTokenCall_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () => Assert.Equal(20, await KennelSample.StayCancelledRepeatedlyAsync(20)),
            iterations: 3);
    }

    // Row 9k. The COMPLETED path of the same token-taking route: the coroutine is never cancelled,
    // so the CTS handle is released by the `finally` rather than the cancellation handler, and the
    // ctx and Task handle go exactly as in row 9. Here as the control for 9j: a delta on both rows
    // is the ordinary async route, a delta on 9j alone is the cancellation path.
    [Fact]
    public async Task ReverseAsync_CompletedTokenCall_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () => Assert.Equal(6, await KennelSample.DozeWithADefaultTokenAsync()));
    }

    // Row 9l. An ALREADY-COMPLETED token-taking task, tight loop inside one Kotlin coroutine. The
    // race class ADR-019 has bitten three times now: the completion callback can land before
    // `suspendCancellableCoroutine`'s block returns, which is the window in which the CTS handle
    // is minted but not yet stored (ADR-153's inferred claim B, unrun). If the claim is wrong the
    // .NET handle leaks (invisible here) and the ctx released on that ordering may go with it,
    // which IS visible, at a five-figure delta rather than a rounding error.
    [Fact]
    public async Task ReverseAsync_AlreadyCompletedTokenCall_TightLoop_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () => Assert.Equal(2 * 2_000, await KennelSample.PounceRepeatedlyAsync(2_000)),
            iterations: 3);
    }

    // Rows 9m to 9o. ADR-156: the reverse async-ENUMERABLE crossing. A collect of N elements is
    // N+1 `MoveNextBegin`/`MoveNextEnd` pairs, and each pair is an ordinary ADR-152 await: one
    // counted pending-continuation `ctx` StableRef plus one .NET GCHandle on the step's Task. So
    // the per-element repetition is inside the flow, not in the iteration count, and a ctx dropped
    // once per ELEMENT shows up here at N+1 times the rate a per-CALL leak would.
    //
    // WHAT THESE ROWS CAN AND CANNOT SEE, as with 9j to 9l: `nuget_live_handles` counts Kotlin
    // StableRefs only. The enumeration GCHandle and its CancellationTokenSource are .NET handles
    // and are NOT counted on any of these rows (ADR-156 Consequences, ROADMAP line 268), so a C#
    // enumeration leaked per collect is invisible here and is pinned only by a counting fake in
    // the runtime's nativeTest. What these rows do pin is that the per-step ctx returns to
    // baseline on all three release sites, which are genuinely different code paths.

    // Row 9m. The COMPLETED path: whole enumerations, run to the end-of-stream `MoveNextEnd` that
    // returns false. Each element's ctx is released by its completion callback, as in row 9.
    [Fact]
    public async Task ReverseAsyncEnumerable_FullCollect_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () => Assert.Equal(20, await KennelSample.BarksRepeatedlyAsync(20)),
            iterations: 3);
    }

    // Row 9n. The CANCELLED path, and a different release site: the collector is cancelled while a
    // `MoveNextAsync` is in flight over a source that ignores the token, so the step completes
    // AFTER the coroutine was cancelled (ADR-152's cancel-then-complete window) and the ctx has to
    // be released by `onCancellation` rather than by the callback. A channel that only cleans up
    // when a completion is consumed leaks exactly one ctx per cancelled collect and nowhere else.
    // The returned finally-count is asserted so a run in which nothing was actually cancelled, or
    // in which the C# enumerations were abandoned rather than disposed, cannot pass as clean.
    [Fact]
    public async Task ReverseAsyncEnumerable_CancelledMidStep_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () => Assert.Equal(5, await KennelSample.BarksCancelledRepeatedlyAsync(5)),
            iterations: 2);
    }

    // Row 9o. The FAULTED path: the stream throws mid-enumeration, so the elements already
    // delivered took the ordinary path and the final step leaves through `MoveNextEnd`'s error
    // channel. That step never reaches a success return, so its Task handle is freed in a
    // `finally` or not at all, and the flow's own `finally` still has to dispose the enumeration
    // while an exception is in flight.
    [Fact]
    public async Task ReverseAsyncEnumerable_ThrowsMidStream_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () => Assert.Equal(10, await KennelSample.HowlsFaultRepeatedlyAsync(10)),
            iterations: 3);
    }

    // Row 11. ROADMAP Phase 4 (object properties): a handle-typed getter on a STATIC owner. No
    // static-property getter row existed before this one, so it is also the first row covering the
    // companion and top-level getter mint. `TreatPantry.Favourite` hands back a fresh StableRef on
    // every read — the singleton keeps its own Oreo, the wrapper owns only the ref — so fifty
    // reads with fifty disposes have to come back to exactly the baseline. A getter that retains
    // without the wrapper's `Dispose` releasing shows up here as a delta of fifty.
    [Fact]
    public void ObjectHandleProperty_StaticGetter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using Cat favourite = TreatPantry.Favourite;
            Assert.Equal("Oreo", favourite.Name);
        });
    }

    // Row 11a. The collection half of the same static route: `TreatPantry.Flavours` materialises a
    // `List<String>`, which mints a list handle plus one string box per element on the way out.
    // Those are owned by the marshalling code, not by a C# wrapper the test can dispose, so a
    // missing `finally` on the static-property read path is invisible in row 11 and visible here.
    [Fact]
    public void ObjectCollectionProperty_StaticGetter_ReturnsToBaseline()
    {
        AssertNoLeak(() => Assert.Equal(new[] { "tuna", "salmon" }, TreatPantry.Flavours));
    }

    // Rows 13 to 13b. The scope owner sitting on a SUPERCLASS. Every other async row in this file
    // has its scope on the class the consumer holds, so none of them can see a scope that is
    // created at one level of the chain and cleaned up (or not) at another.

    // Row 13. The shape that ships today and leaks: `WindowSeat` owns the scope (it declares the Flow
    // member), `PaddedWindowSeat` declares no async member, so the subclass renders an
    // `override Dispose()` that REPLACES the owner's body and drops the `_scopeHandle` cancel and
    // dispose entirely. Collect the base's Flow through a derived instance, then take the SYNC
    // dispose path: the scope StableRef created by the collect is never released. Red today
    // (text-verified in the research pass; this row is the runtime proof). The async path is Row
    // 8-style and stays green, which is why the row must use `Dispose()` and not `await using`.
    //
    // Oreo and Mylo take the cushioned perch fifty times, and the perch has to come back empty.
    [Fact]
    public async Task SuperclassOwnedScope_SyncDisposeThroughTheSubclass_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            var perch = new PaddedWindowSeat(9);
            var watchers = new List<string>();
            await foreach (string watcher in perch.Watchers()) watchers.Add(watcher);
            Assert.Equal(2, watchers.Count);
            perch.Dispose();
        });
    }

    // Row 13a. The mirror image: the owner is the SUBCLASS (`NapLounge` declares the first async
    // member in a chain whose base declares none), measured after a real async call so the scope
    // exists by the time `DisposeAsync` drains it. A scope minted on the derived level and drained
    // on a level that does not own it would show here and not on Row 13.
    [Fact]
    public async Task SubclassOwnedScope_AfterAnAsyncCall_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var lounge = new NapLounge("the windowsill");
            Assert.Equal("Oreo napped on the windowsill", await lounge.RestAsync("Oreo"));
            Assert.Equal(12, await lounge.RestMinutesAsync());
        });
    }

    // Row 13b. The TIGHT LOOP row for the inherited scope, and the same ADR-019 race as Row 9b one
    // level up the chain: `WindowSeat.settleHeight` is a `suspend fun` with NO suspension point, so the
    // coroutine can finish and fire its completion callback before the P/Invoke that launched it has
    // returned the job handle to C#. When the scope that launched it belongs to the BASE and the
    // handle bookkeeping is spelled on the subclass, that window is a fresh one: it reads as +1 per
    // thousand rather than +1 per call, so the fifty crossings of Row 13 cannot see it. Called
    // through the derived type on purpose.
    [Fact]
    public async Task SuperclassOwnedScope_NoSuspensionPoint_TightLoop_ReturnsToBaseline()
    {
        var perch = new PaddedWindowSeat(7);
        try
        {
            // Warm the scope OUTSIDE the window. `GetOrCreateScope()` is lazy and its StableRef is
            // counted, so a first call inside the window reads as +1 and fails on attempt 1 (a
            // positive delta is never retried). This row measures the per-call race, not the
            // one-off scope creation.
            Assert.Equal(7, await perch.SettleHeightAsync());

            await AssertNoLeakAsync(
                async () => Assert.Equal(7, await perch.SettleHeightAsync()),
                iterations: 5000);
        }
        finally
        {
            await perch.DisposeAsync();
        }
    }

    // Row 12. ADR-154: the admitted-dependency-class route. `dev.other.bytype.Waterbowl` reaches
    // C# through `admit("dev.other.bytype.Waterbowl")` alone — no `include(...)` entry covers its
    // package — and it is a handle type, so every `Storeroom.Bowl()` mints a StableRef that the
    // wrapper's `Dispose` has to release. A klib type admitted BY NAME takes a different planning
    // path from a module-local class and from the `include`-admitted `dev.other.admitted.Billboard`
    // (which mints no handle in any existing row), so a missing release on the per-type admission
    // route is invisible everywhere else in this file. The String read is in the window on purpose:
    // it is the member that survived while its siblings were dropped, and it boxes on the way out.
    [Fact]
    public void AdmittedDependencyClass_ReturnedHandle_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var storeroom = new Storeroom("Oreo");
            using Waterbowl bowl = storeroom.Bowl();
            Assert.Equal("Oreo's bowl", bowl.Label);
        });
    }

    // Rows 13 to 15. The reverse DELEGATE-PARAMETER crossing: a Kotlin lambda handed to C# behind
    // a one-slot bridge, whose ctx is a Kotlin `StableRef` on the lambda.
    //
    // WHAT THESE ROWS CAN AND CANNOT SEE, stated rather than implied (the row 6k and 9j caveat
    // again, and it bites harder here). `nuget_live_handles` counts Kotlin StableRefs reached
    // through `NugetHandles.retain`/`release`. The bridge ctx is a `StableRef.create(impl)`, and no
    // existing row in this file passes a KOTLIN-implemented object into C#, so whether that ctx is
    // counted here is not yet established by anything: if it is not, these rows observe only the
    // transfer-scope traffic and the boxed payloads, and the ctx half is pinned by
    // `kotlinBridgeReleaseCount` in `WorkshopRoundTripTests` instead. The .NET GCHandle on the
    // holder is a managed handle and is NOT counted on any of these rows, on any path. They are
    // here regardless, because a per-crossing mint that is never released at all is exactly what
    // row 13 would catch, and because the throw path in row 15 frees from a different place.

    // Row 13. PER-CALL, fifty crossings, each with a FRESHLY CAPTURING lambda. The capture is
    // load-bearing: a non-capturing lambda is a singleton in Kotlin, so the ADR-089 reuse table
    // would hand back the same bridge every time and a per-crossing leak would never be sampled.
    // The factor varies per iteration for the same reason.
    [Fact]
    public void ReverseDelegate_PerCallCapturingLambda_ReturnsToBaseline()
    {
        int factor = 0;
        AssertNoLeak(() =>
        {
            factor++;
            // Corrected 2026-09-22: `WorkshopScale` goes through `Workshop.Apply`, which invokes the
            // lambda TWICE (`step(step(seed))`), so the answer is seed * factor^2, not seed * factor.
            // The original `7 * factor` failed with 28 against an expected 14 on the first green
            // build of the crossing, which is the fixture's own semantics, not a marshalling fault.
            Assert.Equal(7 * factor * factor, WorkshopSample.WorkshopScale(7, factor));
        });
    }

    // Row 14. The STORED delegate, released when its OWNER is disposed rather than at the end of
    // the crossing that passed it. This is the lifetime metadata cannot distinguish from the
    // per-call one, so it has to come back to baseline over the same harness: keep, invoke it once
    // to prove it is genuinely alive across the gap, then dispose the owner. Fewer iterations
    // because each one waits on .NET collection to get the release it is measuring.
    [Fact]
    public void ReverseDelegate_StoredThenOwnerDisposed_ReturnsToBaseline()
    {
        AssertNoLeak(
            () =>
            {
                WorkshopSample.WorkshopKeep(3);
                Assert.Equal(21, WorkshopSample.WorkshopRunKept(7));
                WorkshopSample.WorkshopDisposeHeld();
            },
            iterations: 10);
    }

    // Row 15. The THROWING lambda. The fault leaves the slot through the ADR-087 envelope, which
    // mints a StableRef of its own for the `NugetError`, and comes out of the reverse thunk through
    // the ADR-104 channel, which mints a .NET GCHandle for the managed exception. So this path
    // frees from two places neither of the rows above touches, and a channel that only cleans up on
    // success leaks exactly here. Whether the TRANSFER handle is freed on the throw path is the
    // C#-side half that this harness cannot see at all (the same unverified question ADR-088 owns).
    [Fact]
    public void ReverseDelegate_ThrowingLambda_ReturnsToBaseline()
    {
        int factor = 0;
        AssertNoLeak(() =>
        {
            factor++;
            Assert.Equal($"boom x{factor}", WorkshopSample.WorkshopScaleThrowing(factor));
        });
    }

    // Row 13. ADR-160: a per-call lambda-parameter member whose METHOD return is an exported object.
    // The only cell on that route that mints a handle, and it mints two kinds per call: one per
    // candidate handed to the C# predicate (`Func<Chime,bool>`) and one for the chime the member
    // returns, which the consumer disposes. The row measures both kinds; which side owns the payload
    // handle of the predicate is ADR-036's, restated by ADR-160: the C# side owns the payload
    // wrapper, so the predicate below disposes each candidate it is handed (`using (c)`), exactly as
    // ADR-036's documented `using var t = toy;` callback body does. That is what makes this row a
    // measurement of the ROUTE rather than of consumer discipline: a consumer lambda that does not
    // dispose leaks one handle per invocation, since a generated wrapper has `Dispose()` and no
    // finalizer (the residual ADR-036 names, measured here at +4 handles per iteration before the
    // `using` was added).
    // The predicate fires synchronously before the P/Invoke returns, so a handle minted per
    // invocation and never released is a per-iteration leak rather than a per-call one: the
    // iteration count is deliberately in the thousands, because three candidates per call means a
    // 50-iteration row could hide a single-handle-per-invocation leak inside the settle noise while
    // this row cannot. Mylo is picked out of the chime rack five thousand times and put back each
    // time.
    [Fact]
    public void CallbackMemberObjectReturn_PredicateAndResult_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var metronome = new Metronome(4);
            using Chime chime = metronome.FirstChime(c => { using (c) { return c.Weight >= 2; } });
            Assert.Equal("Mylo", chime.Name);
        }, iterations: 5000);
    }

    // Row 13a. The nullable twin (`firstOrNull`). Both branches inside one crossing on purpose: the
    // null branch walks every candidate and returns IntPtr.Zero, so it mints the borrowed predicate
    // handles and no result, while the matching branch mints the result too. A release wired only to
    // the success path leaks on the null branch, and a guard that returns early before freeing the
    // GCHandle leaks on both; row 13 alone sees neither. Oreo is looked for and found, then a chime
    // that does not exist is looked for and is not.
    [Fact]
    public void CallbackMemberNullableObjectReturn_BothBranches_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var metronome = new Metronome(4);
            using Chime? oreo = metronome.FirstChimeOrNull(c => { using (c) { return c.Name == "Oreo"; } });
            Assert.NotNull(oreo);
            Assert.Null(metronome.FirstChimeOrNull(c => { using (c) { return c.Weight == 99; } }));
        }, iterations: 5000);
    }

    // Row 14. ADR-161, the throwing-callback paths. Fault injection on the pattern of row 8's
    // `ListReturn_ThrowingElementFactory_ReleasesTheListHandle`, except the throw comes from the
    // consumer's own callback rather than a swapped factory, so no `NugetMarshal.Factories` entry
    // has to be restored.
    //
    // What can leak on a throwing callback is the argument handle Kotlin minted BEFORE invoking the
    // callback (`LambdaParameterExports.kt:100-105`): on the happy path the C# side reads and
    // disposes it, on the throwing path the read may never happen. So every row below uses the
    // `String` payload, which is the handle-passed one; the `Int` payload has no handle to leak and
    // is deliberately not measured here.
    //
    // All three rows are process-fatal against the build of 2026-09-22: the throw inside the thunk
    // reaches `Environment.FailFast` and kills the test host before the count is read. A FailFast
    // aborts the whole harness rather than reporting one failure, so all four rows carry `Skip`
    // until the error channel lands; removing the attributes is that PR's first step.
    //
    // Not measured, accepted residue named by the ADR-161 research memo: a `Flow<T>` item whose
    // materialisation throws leaks one StableRef per failed item, because the Kotlin side already
    // handed the item handle over when the C# read failed. That is a known cost of PR A's
    // fault-the-stream answer, not something a row here should pin as correct.

    // Row 14. Per-call lambda, `String` payload, the throw uncaught in Kotlin. Fifty crossings, each
    // minting one payload handle that the callback never reads because it throws first. Oreo objects
    // to being described, fifty times.
    [Fact]
    public void PerCallLambdaThrow_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var faults = new CallbackFaults();
            Assert.ThrowsAny<Exception>(
                () => faults.DescribeWith(_ => throw new InvalidOperationException("Oreo objects")));
        });
    }

    // Row 14a. The same payload handle where KOTLIN catches the managed exception and returns
    // normally. Separate row from 14 because the release happens on a different path: 14 unwinds out
    // of the export through the ADR-024 error arm, while here the export returns a value, so a
    // release wired only into the error arm passes 14 and leaks here. The error holder the channel
    // mints per throw has to be gone too, which is what makes a +1-per-iteration delta here readable
    // as "the managed-error holder is never disposed".
    [Fact]
    public void PerCallLambdaThrowCaughtInKotlin_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var faults = new CallbackFaults();
            Assert.Contains("recovered", faults.RecoverWith(_ => throw new InvalidOperationException("nope")));
        });
    }

    // Row 14b. Stored callback (ADR-037). The subscription's GCHandle and the per-emission payload
    // handle are owned by different scopes here: the subscription outlives the throwing emission, so
    // a fix that frees the subscription on the error path would show as a crash rather than a leak,
    // and a payload handle abandoned per emission shows as a per-iteration delta. Mylo is told about
    // dinner fifty times and refuses every time.
    [Fact]
    public void StoredCallbackThrow_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var faults = new CallbackFaults();
            using IDisposable sub = faults.AddFaultListener(_ => throw new ArgumentException("Mylo refuses"));
            Assert.ThrowsAny<Exception>(() => faults.Emit("dinner"));
        });
    }

    // Row 14c. The ADR-084 interface bridge slot, whose ctx is a per-slot GCHandle in `_pins` and
    // whose result is a `string` the Kotlin side would otherwise wrap. The throwing slot has to
    // release the payload handle AND leave the bridge state intact for the next call, so the row
    // makes a second, non-throwing call inside the same crossing.
    [Fact]
    public void InterfaceBridgeSlotThrow_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var faults = new CallbackFaults();
            using var throwing = new LeakThrowingFaultListener();
            Assert.ThrowsAny<Exception>(() => faults.GreetVia(throwing));
            using var polite = new LeakPoliteFaultListener();
            Assert.Equal("hello Oreo", faults.GreetVia(polite));
        });
    }

    /// <summary>ADR-161 row 14c: every member throws.</summary>
    private sealed class LeakThrowingFaultListener : IFaultListener
    {
        public string OnName(string name) => throw new InvalidOperationException("not answering");

        public int OnCount(int count) => throw new InvalidOperationException("not counting");

        public void Dispose() { }
    }

    /// <summary>ADR-161 row 14c: the same interface, answering, to prove the bridge still works.</summary>
    private sealed class LeakPoliteFaultListener : IFaultListener
    {
        public string OnName(string name) => "hello " + name;

        public int OnCount(int count) => count;

        public void Dispose() { }
    }

    // Row 13. Boundary nullability part A1: the returned-lambda route with a NULLABLE argument.
    // This is the first row of any kind on `KotlinAction`/`KotlinFunc`, and the route has two
    // separate handle debts per call, which is why the row exists at all rather than riding row 1's
    // coverage:
    //
    //  - the lambda itself is a StableRef, released by the wrapper's `Dispose` (the `using`), and
    //  - EVERY argument is boxed into its own StableRef by the generated `WrapArg<T>`, which the
    //    generated `Invoke` never disposes and `nuget_funcN_invoke` only `get()`s. That is one
    //    leaked handle per call today, so fifty calls are a delta of fifty and no amount of
    //    settling hides it. Delegating `Invoke` to `NugetMarshal.Wrap<T>(arg, out bool owned)` and
    //    disposing on `owned` in a `finally` is the fix, and this row is how it is measured.
    //
    // Both payloads are driven in the window on purpose. A null argument must mint NO box at all
    // (`Wrap<T>` short circuits to `IntPtr.Zero`), a non-null one mints exactly one that has to come
    // back, and the null return leg (`Finder()` on a name that is not Oreo) must not retain a
    // handle for the absent result. A fix that made null tolerable by leaking the non-null case, or
    // that boxed `IntPtr.Zero` into a handle nobody owns, fails here.
    //
    // Note for whoever reads a red here first: until A1's null tolerance lands, the null `Invoke`
    // does not leak, it terminates the process. This row is only measurable after that change.
    //
    // Oreo signs out and back in fifty times without leaving a paw print on the register.
    [Fact]
    public void NullableLambdaArgument_InvokeAndDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using KotlinAction<string?> record = Recorder.SignIn();
            record.Invoke(null);
            record.Invoke("Oreo");
            Assert.Equal("Oreo", Recorder.LastSeen());

            using KotlinFunc<string, string?> find = Recorder.Finder();
            Assert.Null(find.Invoke("Mylo"));
        });
    }

    // Row 1f. Issue #297 / ADR-164: the widened constructor and `Copy` dispatch through a Kotlin
    // `when (mask)` to one named-argument call per subset. Every arm mints exactly one handle, so a
    // mask arm that mints twice (or a `Copy` that retains the receiver) is a rising count here.
    // Oreo reconfigures his feeder fifty times, only ever naming the field he cares about.
    [Fact]
    public void WidenedDefaultConstructorAndCopy_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var config = new Issue297.Config(mode: Issue297.Mode.Always);
            using var copy = config.Copy(retries: 7);
            Assert.Equal(7, copy.Retries);
            Assert.Equal(Issue297.Mode.Always, copy.Mode);
        });
    }

    // Row 1g. The same widening at a handle parameter: an unset `cat` crosses as a null pointer and
    // Kotlin mints its own default `Cat("Momo")` (never retained on the bridge), a set `cat` crosses
    // as a borrowed handle. Both paths must leave the count flat. Mylo gets greeted by Momo, then by
    // Oreo, fifty times each.
    [Fact]
    public void WidenedDefaultHandleParameter_UnsetAndSet_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.Equal("hi Mylo, from Momo", Issue297.Issue297Sample.Greet("Mylo"));

            using var oreo = new Cat("Oreo", 9);
            Assert.Equal("hi Mylo, from Oreo", Issue297.Issue297Sample.Greet("Mylo", cat: oreo));
        });
    }

    [Fact]
    public void WidenedDefaultLambda_UnsetAndSet_ReturnsToBaseline()
    {
        // ADR-164: unset mints no ctx (nothing registered, IntPtr.Zero); set registers one and the
        // `finally` removes it.
        AssertNoLeak(() =>
        {
            for (int i = 0; i < 50; i++)
            {
                Assert.Equal("sent purr", Issue297.Issue297Sample.Notify("purr"));
                int calls = 0;
                Assert.Equal("sent meow", Issue297.Issue297Sample.Notify("meow", onDone: _ => calls++));
                Assert.Equal(1, calls);
            }
        });
    }
}

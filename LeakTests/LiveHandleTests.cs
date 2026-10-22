using System.Runtime.CompilerServices;
using Mood = TestLibrary.Cat.Mood;
using Test.Menagerie;
using TestLibrary;
using TestLibrary.Admission;
using TestLibrary.Cat;
using TestLibrary.Charms;
using Catfeed = TestLibrary.Catfeed;
using Curlup = TestLibrary.Curlup;
using TestLibrary.Dev.Other.Bytype;
using TestLibrary.Dev.Other.Bysuspend;
using TestLibrary.Errand;
using TestLibrary.Clinic;
using TestLibrary.Dispenser;
using TestLibrary.Issue115;
using TestLibrary.Issue126;
using TestLibrary.Issue127;
using TestLibrary.Issue131;
using TestLibrary.Issue236;
using TestLibrary.Issue365;
using Issue297 = TestLibrary.Issue297;
using Issue54 = TestLibrary.Issue54;
using Issue463 = TestLibrary.Issue463;
using Issue122 = TestLibrary.Issue122;
using Perchvar = TestLibrary.Perchvar;
using Multibound = TestLibrary.Multibound;
using Rankings = TestLibrary.Rankings;
using TestLibrary.Kennel;
using Lineage = TestLibrary.Lineage;
using Torpor = TestLibrary.Torpor;
using Treatprep = TestLibrary.Treatprep;
using Trickle = TestLibrary.Trickle;
using Outcomes = TestLibrary.Outcome;
using Cubby = TestLibrary.Cubby;
using TestLibrary.Boxshelf;

using Membergeneric = TestLibrary.Membergeneric;
using Litterbox = TestLibrary.Litterbox;
using Playroom = TestLibrary.Playroom;
using TestLibrary.Listenerprops;
using TestLibrary.Lounge;
using TestLibrary.Metronome;
using TestLibrary.Mishaps;
using TestLibrary.Models;
using TestLibrary.Nested;
using Wardrobe = TestLibrary.Wardrobe;
using TestLibrary.Objectprops;
using TestLibrary.Parcel;
using TestLibrary.Petlist;
using TestLibrary.Routes;
using TestLibrary.Workshop;
using Suspendgeneric = TestLibrary.Suspendgeneric;
using TestLibrary.Catnap;
using Dash = TestLibrary.Dash;
using Zoomrun = TestLibrary.Zoomrun;
using Cheersquad = TestLibrary.Cheersquad;
using Purrclock = TestLibrary.Purrclock;
using Feedingtime = TestLibrary.Feedingtime;
using Catsitter = TestLibrary.Catsitter;
using Cattery = TestLibrary.Cattery;
using Hoards = TestLibrary.Hoard;

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
            NugetMarshal.GcCollect();   // ADR-084 cleaner round; harmless when nothing is pending
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

    // The async twin of `Settle`, for crossings that end on .NET thread-pool work. A reverse await's
    // ctx is released by the completion callback, a `ContinueWith` work item queued at the same
    // instant the crossing's own continuation is (row 18d: the pending pull's completion runs the
    // enumerator's `DisposeAsync`, and so the iterator's `finally`, inline). `Thread.Sleep` would
    // park the very worker holding that item in its local queue, and with every worker busy the
    // pool only injects another after the settle window has already read the count as stable.
    // Awaiting the delay hands the worker back to the pool between rounds, so the item runs.
    private static async Task SettleAsync()
    {
        long previous = long.MinValue;
        int stable = 0;
        for (int round = 0; round < MaxSettleRounds; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            NugetMarshal.GcCollect();
            await Task.Delay(50);

            long current = NugetMarshal.LiveHandles;
            if (current == previous)
            {
                if (++stable >= RequiredStableRounds) return;
            }
            else
            {
                stable = 1;
                previous = current;
            }
        }
    }

    private static async Task AssertNoLeakAsync(Func<Task> crossing, int iterations = 10)
    {
        for (int attempt = 1; ; attempt++)
        {
            await SettleAsync();
            long before = NugetMarshal.LiveHandles;

            for (int i = 0; i < iterations; i++) await crossing();

            await SettleAsync();
            long after = NugetMarshal.LiveHandles;
            if (after == before) return;
            if (after < before && attempt < MeasurementAttempts) continue;

            Assert.Fail(
                $"expected {before} live handles after {iterations} crossings, got {after} (delta {after - before}) on attempt {attempt}");
        }
    }

    [Fact]
    public void AbstractBacking_BaseArmAndClassTypedReturns_ReturnToBaseline()
    {
        // Every handle that materialises as an abstract class constructs its backing wrapper; the
        // wrapper's inherited (arm) or implemented (ordinary class) Dispose has to release it.
        AssertNoLeak(() =>
        {
            using var den = new Torpor.Den();
            using (Torpor.Torpor torpor = den.Deepest(3))
            {
                Assert.Equal(3, ((Torpor.Torpor.Dormant)torpor).Depth());
            }
            using (Torpor.Torpor.Dormant deep = den.Deep(4))
            {
                Assert.Equal("dreaming", deep.Mood);
            }
            using (Torpor.Hibernator hibernator = den.Hibernator())
            {
                Assert.Equal("Oreo", hibernator.Name);
            }
            foreach (Torpor.Hibernator hibernator in den.Hibernators)
            {
                using (hibernator) Assert.Equal(3, hibernator.Snores());
            }
        });
    }

    [Fact]
    public void AbstractBacking_ClassesBelowAbstractBases_ReturnToBaseline()
    {
        // An abstract class below an abstract class, and one below the abstract arm, each
        // materialise as their own wrapper, which overrides the base's open members over the base's
        // exports and must release the handle on Dispose.
        AssertNoLeak(() =>
        {
            using var den = new Torpor.Den();
            using (Torpor.Napper napper = den.Napper())
            {
                Assert.Equal("Mylo", napper.Name);
                Assert.Equal(2, napper.Dreams());
            }
            using (Torpor.Slumber slumber = den.Slumber())
            {
                Assert.Equal(7, slumber.Depth());
                Assert.Equal(2, slumber.Sighs());
            }
        });
    }

    [Fact]
    public void GenericAbstractBacking_ClosedReturnRoutes_ReturnToBaseline()
    {
        // A generic abstract class returned at a closed type constructs the wrapper on the
        // non-generic holder; each route (both type arguments, a concrete generic subclass, two
        // parameters, the abstract class below, passed back in) must release it.
        AssertNoLeak(() =>
        {
            using (Cubby.Trove<string> stock = Cubby.CubbySample.Stock())
            {
                Assert.Equal("tuna flake", stock.Pick());
            }
            using (Cubby.Trove<int> rations = Cubby.CubbySample.Rations())
            {
                Assert.Equal(6, rations.Pick());
            }
            using (Cubby.Trove<string> coffer = Cubby.CubbySample.Coffer())
            {
                Assert.Equal("ribbon", coffer.Pick());
            }
            using (Cubby.Reckoner<string, int> reckoner = Cubby.CubbySample.Reckoner())
            {
                Assert.Equal(6, reckoner.Count("dinner"));
            }
            using var hutch = new Cubby.Hutch();
            using (Cubby.Alcove alcove = hutch.Alcove())
            {
                Assert.Equal("Oreo: catnip mouse at 2", Cubby.CubbySample.Peek(alcove));
            }
        });
    }

    [Fact]
    public void GenericSealed_ReturnsAndListElements_ReturnToBaseline()
    {
        // ADR-199: every closed generic sealed handle Kotlin hands back is reconstructed by
        // `Outcome<T>.FromHandle` (a forwarding arm, a phantom arm, the abstract arm's backing
        // wrapper, an invariant closed arm, an intermediate arm, a `KotlinNothing` arm) and must be
        // released by that arm's Dispose. Reading an erased `T` (`Ok<int>.Value`) mints a box too.
        AssertNoLeak(() =>
        {
            using (Outcomes.Outcome<int> oreo = Outcomes.OutcomeDesk.Fetch(3))
            {
                Assert.Equal(3, ((Outcomes.Outcome.Ok<int>)oreo).Value);
            }
            using (Outcomes.Outcome<int> mylo = Outcomes.OutcomeDesk.Fetch(0))
            {
                Assert.Equal("no 0", ((Outcomes.Outcome.Err<int>)mylo).Message);
            }
            using (Outcomes.Outcome<int> later = Outcomes.OutcomeDesk.Eventually())
            {
                Assert.Equal(3, ((Outcomes.Outcome.Pending<int>)later).Eta());
            }
            using (Outcomes.Cell<int> cell = Outcomes.OutcomeDesk.NumberCell())
            {
                Assert.Equal(4, ((Outcomes.Cell.IntCell)cell).Number);
            }
            using (Outcomes.Outcome<int> stall = Outcomes.OutcomeDesk.Stall())
            {
                Assert.Equal(15, ((Outcomes.Outcome.Lapse.Stall<int>)stall).Minutes);
            }
            using (Outcomes.Outcome.Err<KotlinNothing> boom = Outcomes.OutcomeDesk.Fail())
            {
                Assert.Equal("boom", boom.Message);
            }
            foreach (Outcomes.Outcome<int> dinner in Outcomes.OutcomeDesk.All())
            {
                using (dinner) Assert.NotEmpty(dinner.Label());
            }
        });
    }

    [Fact]
    public void GenericInstance_ReturnedBoxes_ReturnToBaseline()
    {
        // ADR-208: every closed `Box<T>` Kotlin hands back at a member position (a property read, a
        // member, companion, object and top-level return, a nullable one, a list element) is a
        // fresh `StableRef` in a fresh wrapper the caller disposes. `Box<Box<int>>.Value` mints a
        // second wrapper through the nested `Factories` line, and `Box<Cat>.Value` a `Cat`.
        AssertNoLeak(() =>
        {
            using var shelf = new BoxShelf();
            using (Box<string> label = shelf.Label)
            {
                Assert.Equal("Oreo", label.Value);
            }
            using (Box<Cat> favourite = shelf.Favourite)
            using (Cat mylo = favourite.Value)
            {
                Assert.Equal("Mylo", mylo.Name);
            }
            using (Box<Box<int>> outer = shelf.Nested())
            using (Box<int> inner = outer.Value)
            {
                Assert.Equal(7, inner.Value);
            }
            Assert.Null(shelf.Maybe(false));
            using (Box<string>? here = shelf.Maybe(true))
            {
                Assert.Equal("here", here!.Value);
            }
            using (Box<Mood> mood = shelf.MoodBox())
            {
                Assert.Equal(Mood.Grumpy, mood.Value);
            }
            foreach (Box<string> box in shelf.Stack())
            {
                using (box) Assert.NotEmpty(box.Value);
            }
            using (Box<int> spare = BoxShelf.Spare())
            {
                Assert.Equal(9, spare.Value);
            }
            using (Box<string> fresh = BoxDepot.Fresh())
            {
                Assert.Equal("fresh", fresh.Value);
            }
            using (Box<string> top = BoxShelves.TopBox)
            {
                Assert.Equal("top shelf", top.Value);
            }
        });
    }

    [Fact]
    public void GenericInstance_PassedBoxes_ReturnToBaseline()
    {
        // ADR-208: a `Box<T>` the consumer passes (a member, object and top-level parameter, a
        // nullable one, a `var` setter, a constructor parameter, an extension receiver) is a
        // borrow: `box._handle` crosses, Kotlin reads through it and releases nothing, and the
        // caller's own Dispose is the only release.
        AssertNoLeak(() =>
        {
            using var shelf = new BoxShelf();
            using var words = new Box<string>("catnip");
            Assert.Equal("catnip", shelf.Peek(words));
            Assert.Equal("catnip", shelf.PeekMaybe(words));
            Assert.Equal("none", shelf.PeekMaybe(null));

            using var oreo = new Cat("Oreo", 9);
            using var boxed = new Box<Cat>(oreo);
            shelf.Favourite = boxed;
            Assert.Equal(4, BoxDepot.Weigh(boxed));

            using var nine = new Box<int>(9);
            Assert.Equal(9, BoxShelves.Unbox(nine));
            Assert.Equal(18, nine.Doubled);

            using var plinth = new BoxPlinth(words);
            Assert.Equal("plinth of catnip", plinth.Engraving);
        });
    }

    [Fact]
    public async Task GenericInstance_SuspendAndFlow_ReturnToBaseline()
    {
        // ADR-208: the suspend and Flow routes hand a closed `Box<T>` out as an awaited result, a
        // collected element and a `StateFlow.Value` read (one owned wrapper each), and borrow one
        // as a suspend parameter across the suspension.
        await AssertNoLeakAsync(async () =>
        {
            using var courier = new BoxCourier();
            using (Box<string> later = await courier.LaterAsync())
            {
                Assert.Equal("later", later.Value);
            }
            using (var feather = new Box<string>("feather"))
            {
                Assert.Equal("peeked feather", await courier.LaterPeekAsync(feather));
            }
            using (Box<string> bell = await BoxShelves.LaterBoxAsync("bell"))
            {
                Assert.Equal("bell", bell.Value);
            }
            await foreach (Box<string> box in courier.Stream)
            {
                using (box) Assert.NotEmpty(box.Value);
            }
            using (Box<int> latest = courier.Latest.Value)
            {
                Assert.Equal(1, latest.Value);
            }
        });
    }

    [Fact]
    public async Task BoxedFlow_CollectAndDispose_ReturnsToBaseline()
    {
        // ADR-208 part E: `box.Value` over a `Flow` / `StateFlow` type argument is a holder that
        // owns the flow's handle AND the scope its collections run on. A collection run to
        // completion, a `StateFlow.Value` read, an enum element per emission, and an enumerator
        // disposed mid-collection ahead of its holder must each come back to baseline.
        await AssertNoLeakAsync(async () =>
        {
            using var radio = new BoxRadio();
            using (Box<KotlinFlow<int>> box = radio.Ticks())
            using (KotlinFlow<int> ticks = box.Value)
            {
                int sum = 0;
                await foreach (int tick in ticks) sum += tick;
                Assert.Equal(6, sum);
            }
            using (Box<KotlinFlow<Mood>> box = radio.Moods())
            using (KotlinFlow<Mood> moods = box.Value)
            {
                var seen = new List<Mood>();
                await foreach (Mood mood in moods) seen.Add(mood);
                Assert.Equal(new[] { Mood.Sleepy, Mood.Grumpy }, seen);
            }
            using (Box<KotlinStateFlow<Mood>> box = radio.Mood())
            using (KotlinStateFlow<Mood> mood = box.Value)
            {
                Assert.Equal(Mood.Sleepy, mood.Value);
            }
            using (Box<KotlinFlow<int>> box = radio.Endless())
            using (KotlinFlow<int> endless = box.Value)
            {
                await using IAsyncEnumerator<int> listener = endless.GetAsyncEnumerator();
                Assert.True(await listener.MoveNextAsync());
            }
        });
    }

    [Fact]
    public void BoxedFlow_EachValueReadMintsAFreshHolder_ReturnsToBaseline()
    {
        // ADR-208 part E: every `box.Value` read mints a fresh holder (a fresh flow `StableRef`
        // and a fresh scope), so N reads need N disposes and nothing is shared between them. No
        // collection is started: the scope is created with the holder, not with the first collect.
        AssertNoLeak(() =>
        {
            using var radio = new BoxRadio();
            using Box<KotlinFlow<int>> ticks = radio.Ticks();
            using Box<KotlinStateFlow<int>> volume = radio.Volume;
            for (int read = 0; read < 8; read++)
            {
                using KotlinFlow<int> flow = ticks.Value;
                using KotlinStateFlow<int> level = volume.Value;
                Assert.Equal(3, level.Value);
            }
        });
    }

    [Fact]
    public async Task BoxedFlow_OwnerOrHolderDisposedMidCollection_ReturnsToBaseline()
    {
        // ADR-208 part E: a never-completing boxed flow, abandoned two ways. First the PRODUCING
        // owner is disposed mid-collection: the collection is no child of the owner's scope, so
        // the owner's drain returns and the collection is ended by its enumerator. Then the
        // HOLDER is disposed mid-collection: its own scope is cancelled, which ends the
        // collection, and the enumerator is disposed last.
        await AssertNoLeakAsync(async () =>
        {
            var radio = new BoxRadio();
            Assert.Equal(3, await radio.WarmUpAsync());
            using (Box<KotlinFlow<int>> box = radio.Endless())
            using (KotlinFlow<int> endless = box.Value)
            {
                IAsyncEnumerator<int> listener = endless.GetAsyncEnumerator();
                Assert.True(await listener.MoveNextAsync());
                await radio.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10));
                await listener.DisposeAsync();
            }

            using var second = new BoxRadio();
            using (Box<KotlinFlow<int>> box = second.Endless())
            {
                KotlinFlow<int> endless = box.Value;
                IAsyncEnumerator<int> listener = endless.GetAsyncEnumerator();
                Assert.True(await listener.MoveNextAsync());
                endless.Dispose();
                Task<bool> ended = listener.MoveNextAsync().AsTask();
                Assert.False(await ended.WaitAsync(TimeSpan.FromSeconds(10)));
                await listener.DisposeAsync();
            }
        });
    }

    [Fact]
    public async Task MutableStateFlowGenericElement_Writes_ReturnToBaseline()
    {
        // ADR-071 over ADR-208: a box written into a `MutableStateFlow<Box<T>>` (`.Value` set and
        // both `CompareAndSet` slots, on the property, held and awaited routes, a nullable element
        // set to a box and back to null, and a generic sealed arm) is BORROWED: `v._handle`
        // crosses, Kotlin keeps its own reference to the object and releases nothing, and the
        // caller's Dispose is the only release. Every `.Value` read back is a wrapper of its own.
        await AssertNoLeakAsync(async () =>
        {
            using var display = new BoxDisplay();
            using var second = new Box<string>("second");
            using var third = new Box<string>("third");
            display.Front.Value = second;
            Assert.True(display.Front.CompareAndSet(second, third));
            using (Box<string> front = display.Front.Value)
            {
                Assert.Equal("third", front.Value);
            }

            display.Spare.Value = second;
            Assert.Equal("second", display.SpareLabel());
            display.Spare.Value = null;
            Assert.Null(display.Spare.Value);

            using var seven = new Box<int>(7);
            using var eight = new Box<int>(8);
            using (KotlinMutableStateFlow<Box<int>> window = display.Window())
            {
                window.Value = seven;
                Assert.True(window.CompareAndSet(seven, eight));
            }
            using (KotlinMutableStateFlow<Box<int>> window = await display.UnveiledAsync())
            {
                window.Value = seven;
                Assert.Equal(7, display.WindowCount());
            }

            using var refused = new Outcomes.Outcome.Err<int>("no dinner");
            display.Verdict.Value = refused;
            using (Outcomes.Outcome<int> verdict = display.Verdict.Value)
            {
                Assert.Equal("no dinner", ((Outcomes.Outcome.Err<int>)verdict).Message);
            }
        });
    }

    [Fact]
    public void GenericSealed_CSharpBuiltArmsPassedBack_ReturnToBaseline()
    {
        // ADR-199: an arm the consumer constructs mints its handle in the C# constructor and lends
        // it to Kotlin at a base, an arm-typed, a list and a permuted-arm parameter.
        AssertNoLeak(() =>
        {
            using (var seven = new Outcomes.Outcome.Ok<int>(7))
            {
                Assert.Equal("ok 7", Outcomes.OutcomeDesk.Describe(seven));
                Assert.Equal(7, Outcomes.OutcomeDesk.Unwrap(seven));
            }
            using (var mine = new Outcomes.Outcome.Err<int>("Mylo ate it"))
            {
                Assert.Equal("err Mylo ate it", Outcomes.OutcomeDesk.Describe(mine));
            }
            using (var nine = new Outcomes.Cell.IntCell(9))
            {
                Assert.Equal(9, Outcomes.OutcomeDesk.Peek(nine));
            }
            using (var last = new Outcomes.Cell.Spare.Last(2))
            {
                Assert.Equal(2, Outcomes.OutcomeDesk.Peek(last));
            }
            using (var one = new Outcomes.Outcome.Ok<int>(1))
            using (var no = new Outcomes.Outcome.Err<int>("no"))
            {
                Assert.Equal(1, Outcomes.OutcomeDesk.OkCount(new List<Outcomes.Outcome<int>> { one, no }));
            }
            using (var flip = new Outcomes.Duel.Flip<string, int>(2, "Mylo"))
            {
                Assert.Equal("Mylo beat 2", Outcomes.OutcomeDesk.Referee(flip));
            }
        });
    }

    [Fact]
    public void GenericSealed_ConsumerChosenErasedSlot_ReturnsToBaseline()
    {
        // ADR-199: an instantiation no Kotlin signature names is read back from an erased `T`
        // through the `NugetFactory<T>` slot, once typed as the base and once as the arm. The
        // reconstructed wrapper owns a second handle to the same Kotlin object.
        AssertNoLeak(() =>
        {
            using (var treats = new Outcomes.Outcome.Ok<long>(9_000_000_000L))
            using (var hamper = new Outcomes.Hamper<Outcomes.Outcome<long>>(treats))
            using (Outcomes.Outcome<long> item = hamper.Item)
            {
                Assert.Equal(9_000_000_000L, ((Outcomes.Outcome.Ok<long>)item).Value);
            }
            using (var four = new Outcomes.Outcome.Ok<short>(4))
            using (var pouch = new Outcomes.Hamper<Outcomes.Outcome.Ok<short>>(four))
            using (Outcomes.Outcome.Ok<short> item = pouch.Item)
            {
                Assert.Equal((short)4, item.Value);
            }
        });
    }

    [Fact]
    public async Task AbstractBacking_AbstractFlowMember_CollectedThroughWrapper_ReturnsToBaseline()
    {
        // The abstract `Flow` member collected through the member its owner declares, then the
        // wrapper's scope drained by `DisposeAsync`.
        await AssertNoLeakAsync(async () =>
        {
            using var den = new Torpor.Den();
            await using Torpor.Napper napper = den.Napper();
            var breaths = new List<int>();
            await foreach (int breath in napper.Breaths()) breaths.Add(breath);
            Assert.Equal(new[] { 4, 5, 6 }, breaths);
        });
    }

    [Fact]
    public void DependencyGenericOwner_PrimitiveAndStringPayloadsReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new TestLibrary.Models.Parcel<int>(7);
            Assert.Equal(7, oreo.Value);
            using var mylo = new TestLibrary.Models.Parcel<string>("Mylo");
            Assert.Equal("Mylo", mylo.Value);
        });
    }

    [Fact]
    public async Task SuspendFlow_AcquireCollectDispose_AllOwnerRoutesReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var cafe = new SuspendFlowCafe();
            using var portions = await cafe.PortionsAsync();
            var seen = new List<int>();
            await foreach (int portion in portions) seen.Add(portion);
            Assert.Equal(new[] { 17, 29, 43 }, seen);

            using var companions = await cafe.CompanionsAsync();
            await foreach (Cat? cat in companions) cat?.Dispose();
            using var moods = await cafe.MoodsAsync();
            await foreach (var batch in moods) Assert.NotEmpty(batch);
            using var tags = await cafe.TagsAsync();
            await foreach (var batch in tags) Assert.True(batch.Count <= 2);
            using var markings = await cafe.MarkingsAsync();
            await foreach (byte[]? marking in markings) Assert.True(marking is null || marking.Length <= 4);

            using var top = await SuspendFlowSample.CafePortionsAsync();
            int total = 0;
            await foreach (int portion in top) total += portion;
            Assert.Equal(154, total);

            await using SuspendFlowNap nap = new SuspendFlowNap.Loaf("Oreo");
            using var dreams = await nap.DreamsAsync();
            await foreach (string dream in dreams) Assert.StartsWith("Oreo", dream);
            using var purrs = await ((SuspendFlowNap.Loaf)nap).PurrsAsync();
            await foreach (int purr in purrs) Assert.True(purr > 0);
            await using ISuspendFlowMenu menu = SuspendFlowSample.HouseFlowMenu();
            using var specials = await menu.SpecialsAsync();
            await foreach (string special in specials) Assert.NotEmpty(special);
        });
    }

    [Fact]
    public async Task SuspendFlow_AcquisitionAndEmissionFaults_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var cafe = new SuspendFlowCafe();
            var acquisition = await Assert.ThrowsAnyAsync<Exception>(() => cafe.AcquisitionFaultAsync());
            Assert.Contains("Oreo's acquisition failed", acquisition.Message);
            using var flow = await cafe.EmissionFaultAsync();
            var emission = await Assert.ThrowsAnyAsync<Exception>(async () =>
            {
                await foreach (int portion in flow) Assert.Equal(17, portion);
            });
            Assert.Contains("Mylo's emission failed", emission.Message);
        });
    }

    private static async Task WaitForSuspendFlowAcquisition(SuspendFlowCafe cafe)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        while (!cafe.AcquisitionStarted) await Task.Delay(1, timeout.Token);
    }

    [Fact]
    public async Task SuspendFlow_CanceledAndLateSuccessfulAcquisitions_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using (var cafe = new SuspendFlowCafe())
            using (var cancellation = new CancellationTokenSource())
            {
                var pending = cafe.GatedAsync(cancellation.Token);
                await WaitForSuspendFlowAcquisition(cafe);
                cancellation.Cancel();
                await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
            }

            await using (var cafe = new SuspendFlowCafe())
            using (var cancellation = new CancellationTokenSource())
            {
                var pending = cafe.StubbornAsync(cancellation.Token);
                await WaitForSuspendFlowAcquisition(cafe);
                cancellation.Cancel();
                cafe.ReleaseAcquisition();
                using var late = await pending;
                int sum = 0;
                await foreach (int portion in late) sum += portion;
                Assert.Equal(46, sum);
            }

            var disposed = new SuspendFlowCafe();
            Action release = SuspendFlowSample.CafeAcquisitionRelease(disposed);
            var abandoned = disposed.StubbornAsync();
            await WaitForSuspendFlowAcquisition(disposed);
            disposed.Dispose();
            release.Invoke();
            using var holder = await abandoned.WaitAsync(TimeSpan.FromSeconds(10));
            await Assert.ThrowsAsync<ObjectDisposedException>(async () =>
            {
                await foreach (int portion in holder) Assert.Fail("closed scope emitted a portion");
            });
        });
    }

    [Fact]
    public async Task SuspendFlow_ImmediateAcquisitionAndCompletion_ThousandsReturnToBaseline()
    {
        await using var cafe = new SuspendFlowCafe();
        using (var warm = await cafe.PortionsAsync())
        {
            await foreach (int portion in warm) Assert.True(portion > 0);
        }

        await AssertNoLeakAsync(async () =>
        {
            using var flow = await cafe.PortionsAsync();
            int sum = 0;
            await foreach (int portion in flow) sum += portion;
            Assert.Equal(89, sum);
        }, iterations: 5000);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task DropUndisposedSuspendFlow()
    {
        KotlinFlow<int> flow = await SuspendFlowSample.CafePortionsAsync();
        int sum = 0;
        await foreach (int portion in flow) sum += portion;
        Assert.Equal(154, sum);
        flow = null!;
        // Let acquisition and collection continuations unwind before the caller forces GC.
        await Task.Yield();
    }

    [Fact]
    public async Task UndisposedSuspendFlowHolder_IsReleasedByTheGc() =>
        await AssertReleasedByTheGcAsync("suspend-acquired Flow holders", DropUndisposedSuspendFlow);

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

    // Row 1c-read. The read half of Row 1c: a sealed arm handed OUT through the base
    // discriminator. `Observation.FromHandle` takes the one handle Kotlin minted, reads the type
    // and gives that same handle to the arm wrapper, so the arm's `Dispose` is the only release.
    // Mylo's box is opened once per arm kind per read: `PeekBox` is the object arm
    // (`Superposition`, a process-wide `data object`, so its Kotlin lifetime can never hide a
    // missed release and the count is the only signal), `OpenBox("Mylo")` the class arm (`Dead`,
    // chosen over `Alive` so no `Cat` handle rides along). Each read mints a fresh handle, so a
    // release owed per read and not per wrapper type shows up at three times the crossing rate.
    [Fact]
    public void SealedArm_ReadThroughTheBaseDiscriminator_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            for (int read = 0; read < 3; read++)
            {
                using Observation unknown = ObservationKt.PeekBox();
                Assert.IsType<Observation.Superposition>(unknown);
                using Observation dead = ObservationKt.OpenBox("Mylo");
                Assert.Equal("The cat was not Mylo", Assert.IsType<Observation.Dead>(dead).Cause);
            }
        });
    }

    // Row 1c-sibling. ADR-125's sibling arms: `Ping` and `Silence` are top-level types implementing
    // the sealed interface `Transmission`, discriminated by `Transmission.FromHandle`. One radio
    // is read repeatedly, through both the method (`Latest`) and the property (`Current`), first
    // while Mylo transmits `Silence` (object arm) and then after Oreo's `Ping` is tuned in (class
    // arm). Every read is its own handle on the same Kotlin value, so each must come back alone.
    // The `Ping` C# built is disposed straight after the setter, which only borrows it.
    [Fact]
    public void SealedSiblingArm_RepeatedReadsOfOneHolder_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var radio = new Issue54.Radio();
            for (int read = 0; read < 3; read++)
            {
                using Issue54.Transmission heard = radio.Latest();
                Assert.IsType<Issue54.Silence>(heard);
                using Issue54.Transmission current = radio.Current;
                Assert.IsType<Issue54.Silence>(current);
            }

            using (var chirp = new Issue54.Ping(60, "Oreo")) radio.Current = chirp;
            for (int read = 0; read < 3; read++)
            {
                using Issue54.Transmission heard = radio.Latest();
                Assert.Equal("Oreo", Assert.IsType<Issue54.Ping>(heard).Label);
                using Issue54.Transmission current = radio.Current;
                Assert.Equal(60, Assert.IsType<Issue54.Ping>(current).Ms);
            }
        });
    }

    // Row 1c-arm. The concrete-arm return, which skips the discriminator altogether: a member
    // typed as the arm itself renders `new Job.Running(nativeResult, out _)`, so the arm wrapper
    // owns the handle from its own constructor. Isolated here from the callback pairs (Rows 8g,
    // 8k, 8l) that also take these arms, so a failure names this route alone. Oreo is the class arm
    // (`Running`), Mylo the object arm (`Idle`). No suspend member is touched, so neither arm ever
    // creates its lazy scope and the synchronous `Dispose` is the complete release.
    [Fact]
    public void SealedArm_ConcreteArmReturn_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var factory = new JobFactory();
            using Job.Running oreo = factory.Running(40);
            Assert.Equal(40, oreo.Progress);
            using Job.Idle mylo = factory.Idle();
            Assert.Equal("job", mylo.Kind);
        });
    }

    // Row 1c-iface. ADR-204 (issue #463): a sealed interface over arms that already have a C# class
    // base, the RETURN half. `IConnectableDevice.FromHandle` takes the one handle Kotlin minted,
    // reads the type through the interface's own `get_type` export, and hands that same handle to
    // the arm's `(NugetKotlinHandle, out _)` constructor, so the arm's `Dispose` is the only
    // release. Every admitted arm kind crosses once per iteration: the data-class arm (`Preferred`),
    // the dual arm (`DeviceAt(1)`), the `data object` arm (`DeviceAt(2)`), the plain-superclass arm
    // through the second interface (`ChargerAt(0)`), and the `List` component, whose list ref the
    // read releases and whose element refs the test disposes. A discriminator that minted a second
    // handle to read the type shows here at five times the crossing rate.
    [Fact]
    public void SealedInterfaceOverDeclaredArms_Returns_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using Issue463.IConnectableDevice oreo = Issue463.Devices.Preferred();
            Assert.IsType<Issue463.NearbyDevice>(oreo);
            using Issue463.IConnectableDevice mylo = Issue463.Devices.DeviceAt(1);
            Assert.IsType<Issue463.RemoteDevice>(mylo);
            using Issue463.IConnectableDevice saved = Issue463.Devices.DeviceAt(2);
            Assert.IsType<Issue463.SavedDevice>(saved);
            using Issue463.IChargeable laser = Issue463.Devices.ChargerAt(0);
            Assert.IsType<Issue463.LaserPointer>(laser);

            IReadOnlyList<Issue463.IConnectableDevice> all = Issue463.Devices.Connectable();
            Assert.Equal(3, all.Count);
            foreach (Issue463.IConnectableDevice device in all) device.Dispose();
        });
    }

    // Row 1c-iface-in. The PARAMETER half of Row 1c-iface: C#-built arms are lowered through the
    // interface's `_handle` (borrowed, never retained a second time), and the value Kotlin hands
    // back is a fresh handle the test owns. Mylo's tracker is the dual arm, so it crosses both
    // interfaces' parameters in one iteration; Oreo's tag crosses the constructor parameter and
    // comes back out through the `var` setter and getter.
    [Fact]
    public void SealedInterfaceOverDeclaredArms_Parameters_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Issue463.NearbyDevice("desk", "aa:bb");
            using var mylo = new Issue463.RemoteDevice(host: "attic", battery: 80);

            using (Issue463.EvidenceDevice connected = Issue463.Devices.Connect(mylo))
            {
                Assert.IsType<Issue463.RemoteDevice>(connected);
            }
            Assert.Equal("remote 80", Issue463.Devices.Charge(mylo));

            using var dock = new Issue463.CollarDock(oreo);
            dock.Current = mylo;
            using Issue463.IConnectableDevice current = dock.Current;
            Assert.IsType<Issue463.RemoteDevice>(current);
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

    // Row 1l. ADR-108's Try twin, false path: a modelled `Result.failure` mints an error handle
    // that `TryAdopt` hands back as an exception instead of throwing it, and the success half mints
    // the Cat the caller owns. Both have to come back. Oreo declines adoption fifty times; Mylo
    // accepts and is let go again.
    [Fact]
    public void ResultTry_ModelledFailureAndSuccess_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var service = ResultSample.Service();
            Assert.False(service.TryAdopt("Oreo", out Cat? none, out Exception? failure));
            Assert.Null(none);
            Assert.IsType<KotlinArgumentException>(failure);
            Assert.True(service.TryAdopt("Mylo", out Cat? adopted, out _));
            adopted!.Dispose();
        });
    }

    // Row 1m. The fault-injection half of Row 1l: Ghost's body throws instead of returning a
    // failure, so the Try rethrows the built exception. The error handle is released on that path
    // too, or the count climbs by one per throw.
    [Fact]
    public void ResultTry_ThrownException_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var service = ResultSample.Service();
            Assert.Throws<KotlinInvalidOperationException>(
                () => service.TryWeigh("Ghost", out _, out _));
        });
    }

    // ADR-201 rows. Every Throwable read out of Kotlin is one fresh ADR-107 envelope handle that
    // `NugetErrorNative.BuildException` disposes while reading, so the count must not move: at a
    // property getter (the ADR-107 position, unmeasured until now), at a sync return, null and
    // non-null, on the return route's throw path, and per element of a List and a Map value (each
    // element box is the envelope; `ReadList`/`ReadMap` dispose the container). A Throwable
    // parameter or setter crosses as one string, so it mints nothing at all.
    [Fact]
    public void ThrowableProperty_Read_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var failure = TestLibrary.Issue56.Issue56Sample.DietViolation();
            Assert.Equal("Oreo is on a diet!", failure.Error!.Message);
            Assert.Equal("the treat jar is empty", failure.Fatal.Message);
        });
    }

    [Fact]
    public void ThrowableReturn_NullAndNonNull_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var log = new MishapLog();
            Assert.Null(log.Latest());
            Assert.Equal("Oreo ate the plant", log.Worst().Message);
            Assert.Equal("Mochi, 3am", log.Hairball().Message);
        });
    }

    [Fact]
    public void ThrowableReturn_ThrowPath_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var log = new MishapLog();
            Assert.Throws<KotlinInvalidOperationException>(() => log.WorstOrThrow(true));
        });
    }

    [Fact]
    public void ThrowableList_ReadEveryElement_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var log = new MishapLog();
            log.Report(new InvalidOperationException("the bowl is empty"));
            Assert.Single(log.All);
            IReadOnlyList<Exception?> timeline = log.Timeline();
            Assert.Null(timeline[1]);
            Assert.Equal(2, log.ByCat().Count);
        });
    }

    [Fact]
    public void ThrowableParameter_ReportAndSet_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var log = new MishapLog();
            log.Report(new ArgumentException("Oreo ate the plant"));
            Assert.True(log.ReportOrSkip(new TimeoutException("Mylo is late")));
            Assert.False(log.ReportOrSkip(null));
            log.LastMishap = new InvalidOperationException("the bowl is empty");
            log.LastMishap = null;
        });
    }

    // ADR-201 amendment, group A: a Throwable List element or Map value at an input. C# boxes each
    // element's text with `Wrap<string>` and the fill loop disposes every box it owns, on the
    // happy path, when Kotlin throws after receiving the list, and when the per-element projection
    // itself throws mid-fill (an exception whose `Message` throws).
    [Fact]
    public void ThrowableListInput_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var stream = new MishapStream();
            stream.ReportAll(new Exception[] { new("a"), new InvalidOperationException("b") });
            Assert.Equal(1, stream.ReportSome(new Exception?[] { null, new("c") }));
            stream.ReportByCat(new Dictionary<string, Exception> { ["Oreo"] = new("d") });
            stream.Pending = new List<Exception> { new("e") };
            Assert.Single(stream.Pending);
        });
    }

    private sealed class SilentMishap() : Exception
    {
        public override string Message => throw new InvalidOperationException("no comment");
    }

    [Fact]
    public void ThrowableListInput_ThrowPaths_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var stream = new MishapStream();
            Assert.Throws<KotlinInvalidOperationException>(
                () => stream.ReportAllOrThrow(new Exception[] { new("a"), new("b") }, true));
            Assert.Throws<InvalidOperationException>(
                () => stream.ReportAll(new Exception[] { new("a"), new SilentMishap(), new("c") }));
        });
    }

    // ADR-201 amendment, group E: a C#-implemented interface slot. The parameter crosses out as one
    // ADR-107 envelope that the slot body's `BuildException` disposes before the C# member runs, so
    // a member that then throws leaves nothing behind; the result crosses in as one string box
    // Kotlin releases after reading.
    private sealed class MishapBin(bool throwOnAccept) : IMishapSink
    {
        public void Accept(Exception mishap)
        {
            if (throwOnAccept) throw new InvalidOperationException("the bin is full");
        }

        public Exception? Last() => new TimeoutException("Mylo is late");
        public void Dispose() { }
    }

    [Fact]
    public void ThrowableBridgeSlot_AcceptAndLast_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var stream = new MishapStream();
            Assert.Equal("System.TimeoutException: Mylo is late", stream.DrainTo(new MishapBin(false)));
            Assert.Throws<InvalidOperationException>(() => stream.DrainTo(new MishapBin(true)));
        });
    }

    // ADR-201 amendment, group B: a suspend parameter crosses as text and mints no handle; the
    // `maybeLater` row completes before the P/Invoke returns (no suspension point), the
    // `reportLater` one after a `yield`.
    [Fact]
    public async Task ThrowableSuspendParameter_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var stream = new MishapStream();
            Assert.True(await stream.MaybeLaterAsync(new Exception("fever")));
            Assert.False(await stream.MaybeLaterAsync(null));
            await stream.ReportLaterAsync(new Exception("fleas"));
        });
    }

    // ADR-201 amendment, group D: a synchronous lambda payload is one ADR-107 envelope the thunk's
    // `BuildException` disposes before the C# lambda runs, so a lambda that throws leaves nothing;
    // a lambda result is one string box Kotlin releases; a listener call is one envelope per
    // listener, disposed the same way.
    [Fact]
    public void ThrowableCallbackPayload_InvokedAndThrowing_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var stream = new MishapStream();
            stream.OnMishap(_ => { });
            Assert.Throws<InvalidOperationException>(
                () => stream.OnMishap(_ => throw new InvalidOperationException("not now")));
            stream.Recover(() => new Exception("fever"));
        });
    }

    private sealed class QuietListener : IMishapListener
    {
        public void OnMishap(Exception mishap) { }
        public void Dispose() { }
    }

    [Fact]
    public void ThrowableListenerParameter_Announce_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var stream = new MishapStream();
            using IDisposable sub = stream.AddMishapListener(new QuietListener());
            Assert.Equal(1, stream.Announce());
        });
    }

    // ADR-201 amendment, group C: a bare suspend result is one envelope per completion, disposed by
    // the completion's `BuildException`. `LatestLater` has no suspension point, so its body
    // completes before the P/Invoke returns (the tight loop); `WorstLater` resumes after a `yield`;
    // the throw path mints the error envelope only.
    [Fact]
    public async Task ThrowableSuspendResult_NullAndNonNull_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var stream = new MishapStream();
            Assert.Null(await stream.LatestLaterAsync());
            stream.Record(new Exception("fever"));
            Assert.NotNull(await stream.LatestLaterAsync());
            Assert.NotNull(await stream.WorstLaterAsync());
            await Assert.ThrowsAsync<KotlinInvalidOperationException>(
                () => stream.WorstOrThrowLaterAsync(true));
        });
    }

    // Group C, the Flow route: one envelope per item, read and disposed per item, on a full drain,
    // a nullable element, an early break (the remaining items are never minted), and a flow that
    // fails after its first item.
    [Fact]
    public async Task ThrowableFlow_CollectEveryItem_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var stream = new MishapStream();
            await foreach (Exception _ in stream.Each()) { }
            await foreach (Exception? _ in stream.MaybeEach()) { }
            await foreach (Exception _ in stream.Each()) break;
            await Assert.ThrowsAnyAsync<Exception>(async () =>
            {
                await foreach (Exception _ in stream.EachThenFail()) { }
            });
            using KotlinFlow<Exception> later = await stream.StreamLaterAsync();
            await foreach (Exception _ in later) { }
        });
    }

    // Group C, the StateFlow route: each `.Value` read is one envelope, disposed by the read.
    [Fact]
    public void ThrowableStateFlow_ValueReads_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var stream = new MishapStream();
            Assert.Null(stream.Current.Value);
            stream.Record(new Exception("fever"));
            Assert.NotNull(stream.Current.Value);
        });
    }

    // A value class's own members returning a handle-minting result, one row per arm: an object
    // (the wrapper owns the handle), a list (`ReadList` disposes it) and a `Throwable`
    // (`BuildException` disposes the ADR-107 envelope). The null half mints nothing; the object
    // and list rows also read the non-null twins, which used to hand back the raw handle.
    [Fact]
    public void ValueClassMemberObjectResult_NullAndNonNull_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var mylo = new TestLibrary.Collartag.CollarTag("Mylo");
            using (TestLibrary.Collartag.Bell? bell = mylo.Bell()) Assert.NotNull(bell);
            Assert.Null(new TestLibrary.Collartag.CollarTag("").Bell());
            using (TestLibrary.Collartag.Bell own = mylo.OwnBell())
            {
                Assert.Equal("Mylo-dong", own.Tone);
            }
        });
    }

    [Fact]
    public void ValueClassMemberListResult_NullAndNonNull_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var mylo = new TestLibrary.Collartag.CollarTag("Mylo");
            Assert.Equal(2, mylo.Names()!.Count);
            Assert.Null(new TestLibrary.Collartag.CollarTag("").Names());
            Assert.Single(mylo.AllNames());
        });
    }

    [Fact]
    public void ValueClassMemberThrowableResult_NullAndNonNull_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.NotNull(new TestLibrary.Collartag.CollarTag("Mylo").Mishap());
            Assert.Null(new TestLibrary.Collartag.CollarTag("").Mishap());
        });
    }

    // Row 1j. Row 1d one level down: an inner class owning an inner class
    // (`hearth_sunbather_paw_create(outer, toes, error)`). The borrowed receiver is itself an inner
    // handle, and the intermediate Sunbather is disposed BEFORE the Paw, so a release order that
    // only holds when outers outlive their inners shows up here as a rising count. Oreo's paw
    // touches the hearth fifty times.
    [Fact]
    public void InnerOfInnerConstructor_IntermediateOuterDisposedFirst_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var hearth = new Hearth("The bay window");
            var sunbather = new Hearth.Sunbather(hearth, 3);
            using var paw = new Hearth.Sunbather.Paw(sunbather, 2);
            sunbather.Dispose();
            Assert.Equal("The bay window/3/2", paw.Trail());
        });
    }

    // Row 1k. Row 1d with a SEALED outer: the receiver of `purr_whisker_create` and
    // `purr_on_echo_create` is a handle whose field lives on the abstract sealed base, read through
    // an arm obtained from a factory rather than constructed. Mylo purrs fifty times.
    [Fact]
    public void InnerUnderSealedOwnerConstructor_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            // Qualified: `TestLibrary.Models.Purr` (the ADR-066 cycle fixture) is also in scope.
            using TestLibrary.Nested.Purr purr = Deferred.PurringPurr(4);
            using var whisker = new TestLibrary.Nested.Purr.Whisker(purr, 1);
            var on = (TestLibrary.Nested.Purr.On)purr;
            using var echo = new TestLibrary.Nested.Purr.On.Echo(on, 5);
            Assert.Equal("whisker#1 at level 4", whisker.Describe());
            Assert.Equal(9, echo.Both());
        });
    }

    // Row 1l. ADR-196: a generic class nested in a non-generic owner mints through
    // `tote_purse_create` with a boxed `T` in, so both the box minted for the argument and the
    // handle minted for the result have to come back. A `string` T needs conversion, an `int` T
    // does not. Oreo fills the purse fifty times.
    [Fact]
    public void GenericNestedConstructor_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Tote.Purse<string>("Oreo");
            Assert.Equal("Mylo", oreo.Swap("Mylo"));
            using var count = new Tote.Purse<int>(3);
            Assert.Equal(3, count.Item);
        });
    }

    // Row 1m. ADR-196: a class nested in a generic owner, on the non-generic holder. Constructed
    // (`teapot_lid_create`) and read back from inside the generic owner (`teapot.LidAt`) and
    // from outside it (`TeaShop.Spare`), then handed back in. Mylo lifts the lid fifty times.
    [Fact]
    public void HolderNestedConstructor_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var lid = new Teapot.Lid(7);
            using var teapot = new Teapot<int>(5);
            using Teapot.Lid own = teapot.LidAt(1);
            using Teapot.Lid spare = TeaShop.Spare();
            Assert.Equal(7, TeaShop.NumberOf(lid));
        });
    }

    // Row 1n. ADR-196, Row 1d's order: a generic inner class of a non-generic owner, the outer
    // disposed BEFORE the inner. The outer is borrowed on the way in, so the count has to be flat
    // whatever order the two handles are released in. Oreo hangs a charm fifty times.
    [Fact]
    public void GenericInnerConstructor_OuterDisposedFirst_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var tote = new Tote("Oreo");
            using var charm = new Tote.Charm<int>(tote, 7);
            tote.Dispose();
            Assert.Equal("Oreo:7", charm.Label());
        });
    }

    // Row 1o. ADR-196: an inner class of a GENERIC owner, flattened onto the holder. Its outer is a
    // `Teapot<T>` read back applied (`asStableRef<Teapot<Any?>>`), and `Peek` boxes the captured
    // `T` out, so the borrowed outer, the inner's own handle and the returned box all have to come
    // back. The outer goes first, as in Row 1n. Mylo strains the pot fifty times.
    [Fact]
    public void InnerOfGenericOwnerConstructor_OuterDisposedFirst_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var teapot = new Teapot<string>("Oreo");
            using var strainer = new Teapot.Strainer<string>(teapot, 2);
            using var infuser = new Teapot.Infuser<string, int>(teapot, 4);
            teapot.Dispose();
            Assert.Equal("Oreo", strainer.Peek());
            Assert.Equal("Oreo/4", infuser.Both());
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

    // Row 1h. ADR-006 amendment: an enum member property typed as an exported class rides the
    // forward property plan, so each read retains a fresh `Toy` handle through `NugetHandles` and
    // hands C# the owned wrapper; only `Dispose` releases it. Oreo picks his favourite toy fifty
    // times and puts it back fifty times.
    [Fact]
    public void EnumMemberClassTypedGetter_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using Toy toy = TestLibrary.Cat.Mood.Happy.FavouriteToy();
            Assert.Equal("Purring Oreo", toy.Name);
        });
    }

    // Row 1i. The function twin of Row 1h: an enum member FUNCTION returning an exported class
    // (ADR-006 amendment). Each call retains a fresh `Toy` handle and hands C# the owned wrapper;
    // the nullable overload of the same shape hands back either a wrapper or null. Oreo fetches a
    // mouse and Mylo a cream yarn fifty times, and both go back in the basket each time.
    [Fact]
    public void EnumMemberFunctionClassTypedReturn_UsingDispose_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using Toy mouse = TestLibrary.Cat.Mood.Happy.ToyFor();
            Assert.Equal("Purring Oreo's mouse", mouse.Name);
            using Toy? yarn = TestLibrary.Cat.Mood.Sleepy.FetchToy("cream");
            Assert.Equal("Snoozing Mylo's cream yarn", yarn!.Name);
            Assert.Null(TestLibrary.Cat.Mood.Grumpy.FetchToy("black"));
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

    // Row 3a-bis. ADR-170: the same List<String> parameter on a *top-level* function returning
    // `Int?`, which takes the ADR-061 single-call route. Both the null and the non-null return
    // must release the list handle and its element boxes in the shim's finally.
    [Fact]
    public void TwoCallCollectionParam_ListArgument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.Equal(2, Litterbox.ScoopLedger.CountVisits(new[] { "Oreo", "Mylo" }));
            Assert.Null(Litterbox.ScoopLedger.CountVisits(Array.Empty<string>()));
        });
    }

    // Row 3a-ter. ADR-170 fault injection: Kotlin throws with the list handle live (Rex is not
    // allowed in the litter box), and the finally still releases the list and its boxes.
    [Fact]
    public void TwoCallCollectionParam_ThrowingListArgument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
            Assert.ThrowsAny<ArgumentException>(
                () => Litterbox.ScoopLedger.CountVisits(new[] { "Mylo", "Rex" })));
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
    // Row 6: an interface with a `Unit` property plans to null, so `NugetBridge.HandleFor` throws
    // before any handle is minted. The `finally` still runs, and it used to dispose `IntPtr.Zero`
    // with no zero guard, which killed the process. This row pins that a FAILED mint neither leaks
    // nor disposes anything. The throw is asserted inside `AssertNoLeak`, not around it.
    private sealed class SnappedGauge : IClawGauge
    {
        public void Dispose() { }
    }

    [Fact]
    public void UnbridgeableInterface_Argument_ThrowsAndReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var gauge = new SnappedGauge();
            Assert.Throws<NotSupportedException>(() => CatteryDesk.GaugeClaws(gauge));
        });
    }

    // Row 6d2. ADR-237: an interface whose member has its own `R` bridges, erased to `object?`, so
    // the C# implementation crosses and its bridge is released with the call.
    private sealed class ClawMarks : IScratchLog
    {
        public int Scratches { get; set; } = 7;
        public object? Tally(object? entry) => entry;
        public void Dispose() { }
    }

    [Fact]
    public void ErasedTypeParameterInterface_Argument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var log = new ClawMarks();
            Assert.Equal(7, CatteryDesk.CountScratches(log));
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

    // Rows 6g-6i. ADR-173: a C#-implemented `IPet` through each of the three ERASED generic
    // routes. The write mints a bridge transfer handle (`Wrap<T>` falls back to `HandleOf`, disposed
    // on `owned`), and the returned handle resolves to the original `Dog` through the token probe in
    // `Materialize<T>`, which is what must release it: there is no wrapper for a `using` to dispose.
    // A probe that returns `rex` and forgets the handle leaks once per crossing with `Assert.Same`
    // still green, so these rows are the only place that leak shows.
    //
    // Rex goes through the lambda relay fifty times and Oreo keeps count.
    [Fact]
    public void ErasedLambdaRoute_CSharpImplementedPet_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            Func<IPet, IPet> relay = PetRelayKt.PetRelay();
            Assert.Same(rex, relay.Invoke(rex));
        });
    }

    [Fact]
    public void ErasedLegacyGenericFunction_CSharpImplementedPet_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            Assert.Same(rex, Helpers.AdoptPet<IPet>(rex));
        });
    }

    [Fact]
    public void ErasedGenericClass_CSharpImplementedPet_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            using var box = new PetBox<IPet>(rex);
            Assert.Same(rex, box.Value);
        });
    }

    // ADR-015 amendment: a builtin T through a generic top-level function.s boxed export (one per
    // function since ADR-213, bounded or not). Two boxes per call: the argument's `Wrap<T>` box, disposed on `owned`, and the result
    // box, which `FromHandle<T>` unwraps and must dispose: no wrapper holds it.
    [Fact]
    public void BuiltinGenericFunction_BoxedPrimitiveAndString_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.Equal(4, TestLibrary.Rankings.Treats.Weigh(4));
            Assert.Equal("tuna", TestLibrary.Rankings.Treats.Favourite("tuna"));
            Assert.Equal((short)7, Helpers.Identity<short>(7));
        });
    }

    // The same crossings' fault path: a T outside the dropped bound fails the checked cast at the
    // Kotlin read. The argument box was minted by `Wrap<T>` and must still be released in the
    // `finally` when the call throws; the class route's constructor takes the same path, with no
    // wrapper ever built to own a handle.
    [Fact]
    public void BuiltinGenericFunction_BoundCastFails_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.Throws<KotlinInvalidCastException>(() => TestLibrary.Rankings.Treats.Weigh(3u));
            Assert.Throws<KotlinInvalidCastException>(
                () => TestLibrary.Rankings.Treats.Portion("tuna"));
            Assert.Throws<KotlinInvalidCastException>(
                () => new TestLibrary.Rankings.Tally<uint>(3u));
        });
    }

    // Rows 6j-6n. ROADMAP line 51 (ADR-160 amendment): a top-level lambda RETURN with a value
    // parameter. Unlike Row 6g, the parameter bridge is not released when the call returns: the
    // returned lambda captures it, so it lives until the `KotlinFunc` is disposed AND Kotlin's GC
    // runs the ADR-084 cleaner on the lambda. `Settle` drives that cleaner round, so a lambda that
    // still pins the bridge after `Dispose` reads as +1 per crossing here.
    //
    // Oreo hands Rex to the supplier fifty times and asks for him back each time.
    [Fact]
    public void LambdaReturn_CapturedCSharpImplementedPet_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            Func<IPet> supplier = PetRelayKt.PetSupplier(rex);
            Assert.Same(rex, supplier.Invoke());
            Assert.Same(rex, supplier.Invoke());
        });
    }

    // The Kotlin-backed twin: the returned lambda handle itself, plus one owned `Cat` wrapper per
    // `Invoke`, each of which the crossing disposes.
    [Fact]
    public void LambdaReturn_CapturedKotlinCat_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using Cat oreo = new Cat("Oreo", 9);
            Func<Cat> supplier = PetRelayKt.CatSupplier(oreo);
            using Cat back = supplier.Invoke();
            Assert.Equal("Oreo", back.Name);
        });
    }

    // A value-only capture: the returned lambda handle is the only handle in play, so any leak here
    // is the new result shape's own OWNED handle. The nullable and collection parameters ride along
    // so their marshalling cannot leak a transfer handle either.
    [Fact]
    public void LambdaReturn_ValueParameters_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Func<int> naps = PetRelayKt.NullableSupplier(null);
            Func<int> treats = PetRelayKt.ListSupplier(new List<int> { 1, 2, 3 });
            Func<int> mood = PetRelayKt.MoodSupplier(TestLibrary.Cat.Mood.Sleepy);
            Func<int, int> twoMore = PetRelayKt.Adder(2);
            Assert.Equal(-1, naps.Invoke());
            Assert.Equal(6, treats.Invoke());
            Assert.Equal((int)TestLibrary.Cat.Mood.Sleepy, mood.Invoke());
            Assert.Equal(5, twoMore.Invoke(3));
        });
    }

    // ADR-177 fault path: every throw mints a `NugetError` handle, and hierarchy mapping adds a
    // per-node mapped-type read on it. Oreo splitting the litter bag fifty times must still hand
    // every error handle back. The base `System.IO.IOException` is caught on purpose, so this row
    // compiles before `KotlinIOException` exists and fails on the exception type, not the build.
    [Fact]
    public void KotlinxIoIOException_Throws_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
            Assert.ThrowsAny<System.IO.IOException>(() => LitterBoxErrors.Scoop("Oreo")));
    }

    // Kotlin throws after receiving the bridge and before making the lambda: the transfer handle
    // the C# half minted for Grumpy must still be released on the error path.
    [Fact]
    public void LambdaReturn_KotlinThrowsBeforeTheLambda_ReleasesTheParameterBridge()
    {
        AssertNoLeak(() =>
        {
            using IPet grumpy = new Dog("Grumpy");
            Assert.Throws<KotlinArgumentException>(() => PetRelayKt.PickyPetSupplier(grumpy));
        });
    }

    // Fault injection on the `ListReturn_ThrowingElementFactory_ReleasesTheListHandle` pattern: the
    // returned lambda hands back a Kotlin-backed stray, which only the `IPet` factory can build.
    // Swap it to dispose-and-throw; neither the lambda handle nor the element handle may leak.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void InvokeThrowingPetSupplier(IPet stray)
    {
        Func<IPet> supplier = PetRelayKt.PetSupplier(stray);
        Assert.Throws<InvalidOperationException>(() => supplier.Invoke());
    }

    [Fact]
    public void LambdaReturn_ThrowingInterfaceFactory_ReleasesTheLambdaHandle()
    {
        Func<NugetKotlinHandle, object> original = NugetMarshal.Factories[typeof(IPet)];
        NugetMarshal.Factories[typeof(IPet)] = handle =>
        {
            handle.Dispose();
            throw new InvalidOperationException("Mylo sat on the stray's paperwork");
        };

        try
        {
            using IPet stray = PetKt.StrayPet();
            Settle();
            long before = NugetMarshal.LiveHandles;

            // ADR-239: the supplier is a delegate minted after `before` in its own frame, so the
            // settle below collects it, and its target releases the lambda handle, before `after`.
            InvokeThrowingPetSupplier(stray);

            Settle();
            long after = NugetMarshal.LiveHandles;
            Assert.True(
                after == before,
                $"expected {before} live handles after 1 throwing Invoke() crossing, got {after} (delta {after - before}); the returned lambda or its element handle is the one that leaks");
        }
        finally
        {
            NugetMarshal.Factories[typeof(IPet)] = original;
        }
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

    // Row 6g-toplevel. Row 6g's three-owner shape on a TOP-LEVEL `suspend fun` returning
    // `StateFlow<T>` (ADR-068, 2026-09-27 amendment): the awaited flow's StableRef (owned by the
    // holder, freed by `using`), one element handle per `.Value`, and one per collected emission.
    // No parent scope exists, so the collection launches on the runtime's ad-hoc scope and the
    // enumerator's own job cancel is the only thing that ends it; a collect that outlived its
    // `await foreach`, or a scope minted per collection and never freed, shows up here.
    //
    // Oreo purrs and swaps his nap buddy, ten times over.
    [Fact]
    public async Task TopLevelSuspendStateFlow_AwaitReadCollectDispose_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using (KotlinStateFlow<int> purrs = await CatWatch.WatchPurrCountAsync())
            {
                int before = purrs.Value;
                CatWatch.PurrMore(1);
                Assert.True(purrs.Value > before);

                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                await foreach (int _ in purrs.WithCancellation(cts.Token)) break;
            }

            using KotlinStateFlow<Cat> buddies = await CatWatch.WatchNapBuddyAsync();
            CatWatch.SwapNapBuddy("Oreo");
            using Cat buddy = buddies.Value;
            Assert.Equal("Oreo", buddy.Name);
        });
    }

    // Row 6g-nullable. Row 6g-toplevel's owners on the two nullable shapes. A nullable ELEMENT
    // (`StateFlow<Int?>`) reads `.Value` through `nuget_stateflow_value_or_null`: a null read must
    // mint nothing, a present read mints one boxed handle `FromHandle<int?>` frees, and a null
    // emission crosses as a null item that frees nothing. A nullable MEMBER (`StateFlow<Cat>?`)
    // awaited while null mints nothing on either side; once the den is open it is the usual holder
    // plus one wrapper per `.Value`.
    //
    // Mylo's nap streak breaks and resumes, and Oreo moves into the den, ten times over.
    [Fact]
    public async Task SuspendStateFlowNullableElementAndMember_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using (KotlinStateFlow<int?> streak = await CatWatch.WatchNapStreakAsync())
            {
                CatWatch.CountNapStreak(null);
                Assert.Null(streak.Value);
                CatWatch.CountNapStreak(2);
                Assert.Equal(2, streak.Value);

                CatWatch.CountNapStreak(null);
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                await foreach (int? days in streak.WithCancellation(cts.Token))
                {
                    Assert.Null(days);
                    break;
                }
            }

            CatWatch.CloseDen();
            Assert.Null(await CatWatch.WatchDenAsync());
            CatWatch.OpenDen("Oreo");
            using (KotlinStateFlow<Cat>? den = await CatWatch.WatchDenAsync())
            {
                using Cat cat = den!.Value;
                Assert.Equal("Oreo", cat.Name);
            }
            CatWatch.CloseDen();
        });
    }

    // Row 6g-notifying. ADR-206: `AsNotifying()` starts ONE collection behind the adapter (a job
    // cell, a rooted callbacks GCHandle, the enumerator) plus one seed `.Value` read, and
    // `Dispose()` is the only thing that ends it. The adapter's `Completion` finishing is what runs
    // the enumerator's `DisposeAsync`, so it is awaited before the count is taken. An adapter whose
    // Dispose does not reach `NugetJobNative.Cancel`, or a seed read that keeps its handle, leaks
    // per crossing here. The element is `int` on purpose: a wrapper minted between the cancel and
    // Kotlin's cancelled callback falls to the GC (ADR-187) and would make the count noisy.
    // Built off any SynchronizationContext so delivery is inline and nothing outlives the crossing.
    //
    // Oreo gets two snacks while somebody is watching, then the watcher looks away.
    [Fact]
    public async Task StateFlowNotifyingAdapter_UpdatesThenDispose_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var tracker = new CatMoodTracker("Oreo");
            KotlinStateFlowObservable<int> energy =
                await Task.Run(() => tracker.EnergyLevel.AsNotifying());
            var snacked = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            energy.PropertyChanged += (_, _) =>
            {
                if (energy.Value == 102) snacked.TrySetResult();
            };

            tracker.BumpEnergy(1);
            tracker.BumpEnergy(1);
            await snacked.Task.WaitAsync(TimeSpan.FromSeconds(10));

            energy.Dispose();
            await energy.Completion.WaitAsync(TimeSpan.FromSeconds(10));
            Assert.Equal(102, energy.Value);
        });
    }

    // Row 9-nullable-flow. The nullable MEMBER on the three plain-Flow positions (ADR-026
    // amendment). The property and method probes (`_has_value`) mint nothing, so an absent read a
    // thousand times over must leave no handle behind; the present reads are the usual lazy
    // `KotlinFlow<T>` with one wrapper per object emission. On the suspend route the null
    // completion is the one new path: the callback `GCHandle` and job cell are freed and no owned
    // flow handle is minted; the present arm is the usual acquired holder.
    //
    // Mylo's bowl stays plain a thousand times, Oreo's diet and visitors are served once.
    [Fact]
    public async Task NullableFlowMember_AbsentAndPresent_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using (var mylo = new CatFeeder("Mylo"))
            {
                for (int i = 0; i < 1000; i++)
                {
                    Assert.Null(mylo.SpecialDiet);
                    Assert.Null(mylo.VisitorsAfter(0));
                }
            }

            using (var oreo = new CatFeeder("Oreo"))
            {
                await foreach (string meal in oreo.SpecialDiet!) Assert.StartsWith("Oreo", meal);
                await foreach (Cat cat in oreo.VisitorsAfter(2)!) cat.Dispose();
            }

            await using var cafe = new SuspendFlowCafe();
            for (int i = 0; i < 50; i++)
            {
                Assert.Null(await cafe.MaybeNicknamesAsync(0));
                Assert.Null(await cafe.MaybeCompanionsAsync(0));
                Assert.Null(await SuspendFlowSample.CafeMaybePortionsAsync(0));
            }
            using (KotlinFlow<Cat>? companions = await cafe.MaybeCompanionsAsync(2))
            {
                await foreach (Cat cat in companions!) cat.Dispose();
            }
            using (KotlinFlow<int>? portions = await SuspendFlowSample.CafeMaybePortionsAsync(2))
            {
                int total = 0;
                await foreach (int portion in portions!) total += portion;
                Assert.Equal(154, total);
            }
        });
    }

    // Row 6h. The same minted receiver handle as Row 6b, but read through an extension PROPERTY
    // getter rather than an extension function. The getter body is the new surface: the setter
    // route already owns a handle scope, the getter body is flat, so without a `finally`-dispose
    // around it a read on a C#-implemented receiver leaks one StableRef per call, invisible to
    // IntegrationTests. (The nullable handle receiver, the `Cat?.nameOrStray()` function, mints nothing on
    // either side, so it gets no row.)
    [Fact]
    public void InterfaceReceiverExtensionProperty_CSharpImplementedPet_ReleasesTransferHandle()
    {
        AssertNoLeak(() =>
        {
            using IPet rex = new Dog("Rex");
            Assert.Equal("Rex/4/Woof!", rex.Summary);
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
            Assert.Equal("Whiskers", basket.LongestName);
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
            rex.NapQuota = 2;
            Assert.Equal(2, rex.NapQuota);
            rex.NapQuota = null;
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
            Assert.Equal("Nibbles the C#-side goat needs 4 bowls", nibbles.FeedingNote);
        });
    }

    // Row 6l. ADR-090's numbering on the INTERFACE route: `houseBrusher()` mints one handle for an
    // anonymous Kotlin `Brusher` behind the ADR-040 backing wrapper, and every overload then goes
    // through its own `brusher_*` dispatch export. No new handle kind, but five dispatch exports,
    // three of them numbered (`_2`, `_3`, `trim_2`), each borrowing the receiver handle; one that
    // retains instead of borrowing (or a defaulted overload whose mask arm does) leaks per call
    // while every functional cell stays green. Oreo is brushed fifty times and keeps his coat.
    [Fact]
    public void InterfaceOverloads_ReturnedKotlinInterface_EveryOverload_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using IBrusher brusher = BrusherKt.HouseBrusher();
            Assert.Equal("Oreo is brushed", brusher.Brush());
            Assert.Equal("Oreo is brushed 2 times", brusher.Brush(2));
            Assert.Equal("Oreo is brushed while grumpy", brusher.Brush(TestLibrary.Cat.Mood.Grumpy));
            Assert.Equal("Oreo has 4 claws trimmed", brusher.Trim());
            Assert.Equal("Oreo has 1 claws trimmed on the front-left paw", brusher.Trim("front-left"));
        });
    }

    // Row 6m. The C#-implemented half of Row 6l: the ADR-084 transfer StableRef of Row 6, minted
    // for a bridge factory whose slot list now carries numbered same-name slots. Kotlin calls every
    // overload through those slots inside one crossing, so the row pins that the wider factory
    // still disposes the one transfer handle it minted and nothing per slot call.
    private sealed class LeakMyloBrusher : IBrusher
    {
        public string Brush() => "Mylo is brushed";
        public string Brush(int strokes) => $"Mylo is brushed {strokes} times";
        public string Brush(TestLibrary.Cat.Mood mood) => $"Mylo is brushed while {mood.ToString().ToLowerInvariant()}";
        public string Trim(int? claws = null) => $"Mylo has {claws} claws trimmed";
        public string Trim(string paw, int? claws = null) => $"Mylo has {claws} claws trimmed on the {paw} paw";
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceOverloads_CSharpImplementedArgument_EveryOverload_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var salon = new GroomingSalon();
            using var mylo = new LeakMyloBrusher();
            Assert.Equal(
                "Mylo is brushed / Mylo is brushed 2 times / Mylo is brushed while grumpy",
                salon.BrushAll(mylo));
            Assert.Equal(
                "Mylo has 4 claws trimmed / Mylo has 1 claws trimmed on the front-left paw / "
                    + "Mylo has 2 claws trimmed on the back-right paw",
                salon.TrimAll(mylo));
        });
    }

    // Row 6n. Interface inheritance (`HouseCat : Pet : Named, Aged`) at the RETURN position:
    // `adoptHouseCat()` mints one handle behind the ADR-040 backing wrapper, which now has to carry
    // every inherited member as its own `housecat_*` dispatch export, not only `purr`. Each
    // inherited export borrows the receiver handle; one that retains instead leaks per call while
    // the functional cells stay green. The same wrapper is then passed back to Kotlin at the
    // deepest and the root parameter, which must unwrap it rather than bridge it. Oreo is adopted
    // fifty times and is still one cat.
    [Fact]
    public void InterfaceInheritance_ReturnedDerivedInterface_EveryInheritedMember_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var doorstep = new Lineage.Doorstep();
            using Lineage.IHouseCat oreo = Lineage.Lineage.AdoptHouseCat();
            Lineage.INamed named = oreo;
            Lineage.IAged aged = oreo;
            Lineage.IPet pet = oreo;
            Assert.Equal("Oreo", named.Name);
            Assert.Equal("Cookie", named.Nickname);
            Assert.Equal("Purr, I'm Oreo", named.Greet());
            Assert.Equal(5, aged.Age);
            Assert.Equal("Oreo crunches the tuna", pet.Feed("tuna"));
            Assert.Equal("Oreo purrs x2", oreo.Purr(2));
            Assert.Equal(
                "Oreo / Cookie / age 5 / Purr, I'm Oreo / Oreo crunches the tuna / Oreo purrs x3",
                doorstep.LetIn(oreo));
            Assert.Equal("Purr, I'm Oreo (Oreo)", doorstep.CallOut(oreo));
        });
    }

    // Row 6o. The C#-implemented half of Row 6n: the ADR-084 transfer StableRef of Row 6, minted
    // for a DERIVED interface whose bridge slots include every inherited member. The same Mylo
    // crosses at the deepest, middle and root parameter in one iteration, so three bridge
    // factories (IHouseCat, IPet, INamed) each mint and must dispose exactly one transfer handle,
    // and nothing per inherited-slot call.
    private sealed class LeakMyloHouseCat : Lineage.IHouseCat
    {
        public string Name => "Mylo";
        public string? Nickname => "Creamy";
        public int Age => 4;
        public string Greet() => "Mylo blinks slowly";
        public string Feed(string food) => $"Mylo laps up the {food}";
        public string Purr(int times) => $"Mylo purrs x{times}";
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceInheritance_CSharpImplementedDerivedArgument_EveryLevel_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var doorstep = new Lineage.Doorstep();
            using var mylo = new LeakMyloHouseCat();
            Assert.Equal(
                "Mylo / Creamy / age 4 / Mylo blinks slowly / Mylo laps up the tuna / Mylo purrs x3",
                doorstep.LetIn(mylo));
            Assert.Equal("Mylo is 4 and Mylo laps up the kibble", doorstep.Weigh(mylo));
            Assert.Equal("Mylo blinks slowly (Mylo)", doorstep.CallOut(mylo));
        });
    }

    // Row 6p. An interface `var` written through `ITally`. `AsTally()` mints one
    // ADR-040 backing handle per crossing over the same Kotlin `TrainingClicker`, and the case D
    // explicit `ITally.X` setters on the class itself borrow the receiver and, for `Toy`, the
    // `Pompom` handle. A setter consumes handles and returns none, so a count that rises here is
    // either the backing wrapper not releasing or a setter retaining what it should borrow. Mylo
    // gets fifty clicks, Oreo steals the pompom fifty times.
    [Fact]
    public void InterfaceVarSetters_BackingWrapperAndExplicitCaseD_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var clicker = new Perchvar.TrainingClicker();
            using var pompom = new Perchvar.Pompom("orange");

            using (Perchvar.ITally wrapper = clicker.AsTally())
            {
                wrapper.Count = 1;
                wrapper.Toy = pompom;
                wrapper.Names = new[] { "Mylo" };
                wrapper.Toy = null;
            }

            Perchvar.ITally explicitTally = clicker;
            explicitTally.Count = 2;
            explicitTally.Label = "clicker";
            explicitTally.Toy = pompom;
            explicitTally.Names = new[] { "Mylo", "Oreo" };
            explicitTally.Toy = null;

            Assert.Equal("clicker: 2 clicks for Mylo, Oreo with no pompom", clicker.Describe());
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

    // Row 8l. ADR-207: Row 8's abandoned shape with the producer PARKED. The reader takes one crate
    // and waits, so crate 1 sits unread on the C# side and the Kotlin body is parked inside the
    // next `emit` on a credit nobody returns, holding the crate it already minted. Disposing the
    // enumerator cancels the parked producer, which releases that minted crate on the Kotlin side;
    // the unread crate is released by the enumerator's drain (row 16l).
    [Fact]
    public async Task Flow_AbandonedWhileProducerParked_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var conveyor = new TreatConveyor();
            IAsyncEnumerator<IReadOnlyList<string>> crates = conveyor.Crates.GetAsyncEnumerator();
            Assert.True(await crates.MoveNextAsync());
            Assert.Equal(new[] { "treat 0" }, crates.Current);
            await Task.Delay(100);
            await crates.DisposeAsync();
        });
    }

    // Row 8k. ADR-205: a `SharedFlow<T>` never completes, so Row 7 cannot apply; this is Row 8's
    // abandoned shape on the hot stream. The replayed headline (a per-item box) and the replayed
    // sighting (a per-item object handle, disposed by the caller) are taken, then each enumerator
    // is disposed, cancelling its never-ending collection. The `suspend` return adds the acquired
    // flow handle, disposed by `using`.
    [Fact]
    public async Task SharedFlow_AbandonedAfterReplay_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var bulletin = new CatBulletin("Oreo");
            bulletin.Publish("found the treat jar", 1);
            IAsyncEnumerator<string> headlines = bulletin.Headlines.GetAsyncEnumerator();
            Assert.True(await headlines.MoveNextAsync());
            Assert.Equal("Oreo: found the treat jar", headlines.Current);
            await headlines.DisposeAsync();

            using KotlinSharedFlow<Cat> sightings = await bulletin.LatestSightingsAsync();
            IAsyncEnumerator<Cat> cats = sightings.GetAsyncEnumerator();
            Assert.True(await cats.MoveNextAsync());
            using (Cat oreo = cats.Current)
            {
                Assert.Equal("Oreo", oreo.Name);
            }
            await cats.DisposeAsync();
        });
    }

    // Row 8m. ADR-209: `ReplayCache`. One export call mints a list handle and `ReadList` mints one
    // box per item, consuming each with the flow's own element read and disposing the list. The
    // object element's wrappers are the caller's: one read disposes them, the other drops them
    // for the GC (ADR-187). The `suspend` return's acquired flow handle is disposed by `using`.
    [Fact]
    public async Task SharedFlow_ReplayCache_ObjectElement_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var bulletin = new CatBulletin("Oreo");
            bulletin.Publish("on the fence", 4);
            foreach (Cat sighting in bulletin.Sightings.ReplayCache) sighting.Dispose();
            DropReplayCache(bulletin);
            Assert.Equal(new[] { 4 }, bulletin.Editions.ReplayCache);
            Assert.Equal(new[] { "Oreo: on the fence" }, bulletin.Headlines.ReplayCache);
            using KotlinSharedFlow<Cat> latest = await bulletin.LatestSightingsAsync();
            foreach (Cat sighting in latest.ReplayCache) sighting.Dispose();
        });
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropReplayCache(CatBulletin bulletin) =>
        Assert.Single(bulletin.Sightings.ReplayCache);

    // Row 8n. ADR-209: `SubscriptionCount` mints an owned `StateFlow<Int>` handle per read. A
    // disposed one releases it; an abandoned one is released by the GC (ADR-187). Each `.Value`
    // read is one box `FromHandle<int>` consumes.
    [Fact]
    public void MutableSharedFlow_SubscriptionCount_DisposedAndAbandoned_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var bulletin = new CatBulletin("Oreo");
            using (KotlinStateFlow<int> subscribers = bulletin.Headlines.SubscriptionCount)
            {
                Assert.Equal(0, subscribers.Value);
            }
            DropSubscriptionCount(bulletin);
        });
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropSubscriptionCount(CatBulletin bulletin) =>
        Assert.Equal(0, bulletin.Headlines.SubscriptionCount.Value);

    // Row 8o. ADR-209: `EmitAsync`, completed and cancelled. A completed emit mints the emit job
    // handle (released by the completion) and borrows the object argument's handle; the
    // pre-cancelled one crosses nothing at all.
    [Fact]
    public async Task MutableSharedFlow_EmitAsync_CompletedAndCancelled_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var bulletin = new CatBulletin("Oreo");
            using (var mylo = new Cat("Mylo"))
            {
                await bulletin.Visitors.EmitAsync(mylo);
            }
            await bulletin.Moods.EmitAsync(Mood.Grumpy);
            Assert.Equal("Mylo", bulletin.LatestVisitor());
            using var cancelled = new CancellationTokenSource();
            cancelled.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(
                () => bulletin.Headlines.EmitAsync("never sent", cancelled.Token));
        });
    }

    // Row 8p. ADR-209's cancel cell: an emit PARKED behind a stalled collector (no replay, no
    // buffer, and an enumerator that never asks for a second item, so ADR-207's credit gate holds
    // the collector), then cancelled by its owner's `Dispose()`, which cancels the scope the emit
    // was launched on. The emit job, the collection and the scope all return to the baseline.
    [Fact]
    public async Task MutableSharedFlow_ParkedEmitAsync_CancelledByOwnerDispose_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            var bulletin = new CatBulletin("Oreo");
            // The stalled collector runs on a second owner's scope, so the bulletin's `Dispose()`
            // cancels the emit alone; on one scope the collector leaving can free the emit first.
            using var monitor = new CatBulletinMonitor(bulletin);
            KotlinMutableSharedFlow<int> pulses = bulletin.Pulses;
            using (KotlinStateFlow<int> subscribers = pulses.SubscriptionCount)
            {
                IAsyncEnumerator<int> stalled = monitor.Pulses.GetAsyncEnumerator();
                await WaitUntilAsync(() => subscribers.Value == 1);
                await pulses.EmitAsync(1).WaitAsync(TimeSpan.FromSeconds(10));
                await pulses.EmitAsync(2).WaitAsync(TimeSpan.FromSeconds(10));
                Task parked = pulses.EmitAsync(3);
                Assert.False(pulses.TryEmit(4));

                bulletin.Dispose();

                await Assert.ThrowsAnyAsync<OperationCanceledException>(
                    () => parked.WaitAsync(TimeSpan.FromSeconds(10)));
                await stalled.DisposeAsync();
            }
            await bulletin.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10));
        });
    }

    private static async Task WaitUntilAsync(Func<bool> condition)
    {
        DateTime deadline = DateTime.UtcNow.AddSeconds(10);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < deadline, "timed out waiting for the condition");
            await Task.Delay(10);
        }
    }

    // Row 8q. ADR-209: `TryEmit`, synchronous. The object argument's handle is borrowed (the
    // Kotlin export dereferences it before `tryEmit`) and the enum crosses as its ordinal.
    [Fact]
    public void MutableSharedFlow_TryEmit_ObjectAndEnumElements_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var bulletin = new CatBulletin("Oreo");
            using (var mylo = new Cat("Mylo"))
            {
                Assert.True(bulletin.Visitors.TryEmit(mylo));
            }
            Assert.True(bulletin.Moods.TryEmit(Mood.Sleepy));
            Assert.Equal(Mood.Sleepy, bulletin.LatestMood());
        });
    }

    // Row 8s. ADR-209 over the ADR-071 value-class arm: a `MutableSharedFlow<CatId>` written by
    // `TryEmit` and `EmitAsync`. The String underlying mints nothing on either write (Kotlin
    // re-wraps it), so the handles are the emit job and, on the `ReplayCache` read, the list plus
    // one box per element, which `CatId.NugetUnbox` releases. The refused `default(CatId)`
    // crosses nothing.
    [Fact]
    public async Task MutableSharedFlow_ValueClassElement_EmitsAndReplayCache_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var bulletin = new CatBulletin("Oreo");
            Assert.True(bulletin.Tags.TryEmit(new CatId("oreo-1")));
            await bulletin.Tags.EmitAsync(new CatId("oreo-2"));
            Assert.Equal(
                new[] { new CatId("oreo-1"), new CatId("oreo-2") }, bulletin.Tags.ReplayCache);
            Assert.Equal("oreo-2", bulletin.LatestTag());
            Assert.Throws<ArgumentException>(() => bulletin.Tags.TryEmit(default(CatId)));
        });
    }

    // Row 8r. ADR-209: a held `MutableSharedFlow<T>` method return (the ADR-071 held-row mirror).
    // The acquire mints the flow's own handle, which the wrapper owns: disposed here, and dropped
    // for the GC in the second read.
    [Fact]
    public void MutableSharedFlow_HeldMethodReturn_DisposedAndAbandoned_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var bulletin = new CatBulletin("Oreo");
            using (KotlinMutableSharedFlow<string> desk = bulletin.HeadlineDesk())
            {
                Assert.True(desk.TryEmit("held"));
                Assert.Equal(new[] { "held" }, desk.ReplayCache);
            }
            DropHeldDesk(bulletin);
        });
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropHeldDesk(CatBulletin bulletin) =>
        Assert.True(bulletin.HeadlineDesk().TryEmit("dropped"));

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

    // Row 8c-null. Issue #365: a NULLABLE handle parameter on the legacy suspend route. Null crosses
    // as IntPtr.Zero and must not mint anything; a value is borrowed exactly like the non-null row
    // above. Both halves in one crossing, plain class and sealed base, because a fix that wrapped
    // the nullable dereference in a StableRef of its own would leak on the value arm only, and a
    // fix that minted a placeholder for null would leak on the null arm only.
    [Fact]
    public async Task NullableHandleParameter_SuspendNullAndValue_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var checkup = new Checkup("Dr Purr");
                using var oreo = new Cat("Oreo");
                using Observation observation = ObservationKt.OpenBox("Mylo");
                Assert.Equal("Dr Purr in room 2: none", await checkup.ExamineAsync("room 2", null));
                Assert.Equal("Dr Purr in room 2: Oreo", await checkup.ExamineAsync("room 2", oreo));
                Assert.Equal("Dr Purr triage: unobserved", await checkup.TriageAsync(null));
                Assert.Equal("Dr Purr triage: dead:The cat was not Mylo", await checkup.TriageAsync(observation));
            },
            iterations: 50);
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

    // Row 8d-suspend. ADR-068, collection element: an awaited read-only `StateFlow<List<T>>` reads
    // through its own per-member `_value` / `_collect` pair keyed on the awaited flow. Each `.Value`
    // read and each emission mints a collection handle (`ReadList` disposes it in its finally)
    // plus, for `CatId`, one boxed underlying per element; the awaited flow's own StableRef is the
    // holder's `ownedHandle`, freed by `Dispose()`. A read wired to the wrong export, or a holder
    // that forgets its handle, leaks per crossing.
    [Fact]
    public async Task SuspendStateFlowCollectionElement_AwaitReadEnumerateDispose_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var tracker = new CatMoodTracker("oreo");
                using (KotlinStateFlow<IReadOnlyList<int>> litters = await tracker.AwaitLitterSizesAsync())
                {
                    Assert.Equal(2, litters.Value.Count);
                }

                using KotlinStateFlow<IReadOnlyList<CatId>> housemates =
                    await tracker.AwaitHousematesAsync();
                Assert.Equal(2, housemates.Value.Count);
                using var cts = new CancellationTokenSource();
                await foreach (IReadOnlyList<CatId> page in housemates.WithCancellation(cts.Token))
                {
                    Assert.Equal(2, page.Count);
                    cts.Cancel();
                }
            },
            iterations: 50);
    }

    // Row 8d-enum. ROADMAP line 74 (fromhandle-enum): an ENUM element on the StateFlow route. Each
    // `.Value` read mints one StableRef of the Kotlin enum object, which the `Factories` enum
    // entry must release after it reads the ordinal. A factory that forgets the release leaks one
    // handle per read, so the crossing reads many times: repeated non-nullable and nullable
    // `.Value` reads after Oreo sulks, and a generic `Box<Mood>.Value` read (ADR-147). The ADR-071
    // enum element write crosses by ordinal and mints nothing, so one settable `Outlook` write and
    // its read-back ride along: the write must not leak and the read must still release its entry.
    // The NULLABLE enum element write (`Hunch`) is the same ordinal behind a has-value slot: an
    // entry written and read back, a `CompareAndSet` to null, and the null read (which mints
    // nothing) ride along on the property and on the held `HunchDial()`.
    [Fact]
    public void EnumStateFlowElement_RepeatedValueReads_ReturnToBaseline()
    {
        AssertNoLeak(
            () =>
            {
                using var tracker = new CatMoodTracker("Oreo");
                Assert.Equal(Mood.Sleepy, tracker.Temper.Value);
                tracker.Sulk();
                tracker.Outlook.Value = Mood.Grumpy;
                for (int i = 0; i < 5; i++)
                {
                    Assert.Equal(Mood.Grumpy, tracker.Temper.Value);
                    Assert.Equal(Mood.Grumpy, tracker.MaybeTemper.Value);
                    Assert.Equal(Mood.Grumpy, tracker.Outlook.Value);
                }
                tracker.Hunch.Value = Mood.Grumpy;
                Assert.Equal(Mood.Grumpy, tracker.Hunch.Value);
                Assert.True(tracker.Hunch.CompareAndSet(Mood.Grumpy, null));
                Assert.Null(tracker.Hunch.Value);
                using (KotlinMutableStateFlow<Mood?> dial = tracker.HunchDial())
                {
                    dial.Value = Mood.Sleepy;
                    Assert.Equal(Mood.Sleepy, dial.Value);
                    dial.Value = null;
                    Assert.Null(dial.Value);
                }
                using Box<Mood> box = CatMoodTrackerKt.SulkBox();
                Assert.Equal(Mood.Grumpy, box.Value);
                Assert.Equal(Mood.Grumpy, box.Value);
            },
            iterations: 200);
    }

    // Row 8d-enum-flow. The same enum entry on the cold Flow route: one StableRef per emission,
    // non-nullable (`MoodStream`) and nullable with a null in the middle (`MoodSwings`).
    [Fact]
    public async Task EnumFlowElement_Emissions_ReturnToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var faults = new CallbackFaults();
                var moods = new List<Mood>();
                await foreach (Mood mood in faults.MoodStream()) moods.Add(mood);
                Assert.Equal(new[] { Mood.Happy, Mood.Grumpy }, moods);

                using var tracker = new CatMoodTracker("Mylo");
                var swings = new List<Mood?>();
                await foreach (Mood? mood in tracker.MoodSwings()) swings.Add(mood);
                Assert.Equal(new Mood?[] { Mood.Sleepy, null, Mood.Grumpy }, swings);
            },
            iterations: 200);
    }

    // Row 8d-valueclass. Regression pin beside the enum rows: a VALUE CLASS element (ADR-171) on
    // the same two routes, released by `CatId.NugetUnbox`. Expected green already.
    // ADR-071 amendment (value-class element write): extended with writes of a
    // `MutableStateFlow<CatId>` on the property, its compare-and-set and the held twin (whose
    // flow handle the reader owns), each read back. A String underlying mints nothing on the write,
    // so the only handles are the property reads' boxes and the held reader's flow.
    [Fact]
    public async Task ValueClassFlowElement_ValueReadsAndEmissions_ReturnToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var tracker = new CatMoodTracker("Oreo");
                for (int i = 0; i < 5; i++) Assert.Equal(new CatId("oreo-1"), tracker.Tag.Value);
                var ids = new List<CatId?>();
                await foreach (CatId? id in tracker.Tags()) ids.Add(id);
                Assert.Equal(3, ids.Count);

                for (int i = 0; i < 5; i++)
                {
                    tracker.ChipId.Value = new CatId($"oreo-{i}");
                    Assert.Equal(new CatId($"oreo-{i}"), tracker.ChipId.Value);
                }
                Assert.True(tracker.ChipId.CompareAndSet(new CatId("oreo-4"), new CatId("mylo-1")));
                using var reader = tracker.ChipReader();
                reader.Value = new CatId("mylo-2");
                Assert.Equal("mylo-2", tracker.CurrentChipId());
            },
            iterations: 200);
    }

    // Row 8d-valueclass-default. A `default` record struct over a String is refused in C# while
    // the call's arguments are evaluated, which is AFTER the call has built what precedes it: the
    // `names` list handle on `RegisterNamed`, and the half-filled list on `RegisterAll` (the
    // default is its second element). Both are released on the way out, the first by the call's
    // own `finally` and the second by `CreateList`'s catch.
    [Fact]
    public void DefaultValueClass_RefusedAfterAHandleWasBuilt_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var ledger = new ChipLedger(new CatId("oreo-1"));
            var names = new List<string> { "Oreo", "Mylo" };
            Assert.Throws<ArgumentException>(() => ledger.RegisterNamed(names, default));
            var ids = new List<CatId> { new("oreo-1"), default };
            Assert.Throws<ArgumentException>(() => ledger.RegisterAll(ids));
        });
    }

    // Row 8d-nullstring. The string twin of the row above: a `null!` string is refused in C# while
    // the call's arguments are evaluated, AFTER the `names` list handle was built on `StampNamed`
    // and with the list half filled on `StampAll` (the null is its second element). Both are
    // released on the way out, by the call's own `finally` and by `CreateList`'s catch.
    [Fact]
    public void NullString_RefusedAfterAHandleWasBuilt_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var desk = new TestLibrary.Stamps.StampDesk("Mylo");
            var names = new List<string> { "Oreo", "Mylo" };
            Assert.Throws<ArgumentNullException>(() => desk.StampNamed(names, null!));
            var texts = new List<string> { "Oreo", null! };
            Assert.Throws<ArgumentNullException>(() => desk.StampAll(texts));
        });
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

    // Row 8j-val. The same interface-bridge route, the other direction: a listener `val` read from
    // Kotlin. Each `name` read is one getter-slot crossing whose `String` result C# mints
    // (`NugetMarshal.WrapString`) and Kotlin releases after reading, the ADR-084 bridge factory's
    // ownership. `ReadNames(5)` is five crossings per iteration; a getter whose result Kotlin
    // forgot to release shows up as +250 over the 50 below.
    private sealed class ProbePurrListener : IPurrListener
    {
        public string Name => "Oreo";
        public string? Nickname => null;
        public int Lives => 9;
        public bool Sleepy => false;
        public PurrMood Temper => PurrMood.Calm;
        public void OnPurr(int volume) { }
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceBridge_ListenerStringProperty_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var box = new PurrBox();
            using IDisposable sub = box.AddPurrListener(new ProbePurrListener());
            Assert.Equal(20, box.ReadNames(5));
        });
    }

    // Row 8j-listener-var. The listener `var` on the ADR-039 pair: each `RetagAll` is one getter
    // crossing (C# mints, Kotlin releases), one `tagSet` crossing whose `String` argument Kotlin
    // mints and C# releases after reading, and one more getter read-back. A setter that forgets its
    // argument handle shows up as +50 over the iterations below.
    private sealed class ProbeNameTagListener : INameTagListener
    {
        public string Tag { get; set; } = "Oreo";
        public void OnRetag() { }
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceBridge_ListenerSetterSlot_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var board = new NameTagBoard();
            using IDisposable sub = board.AddNameTagListener(new ProbeNameTagListener());
            Assert.Equal("cat:Oreo", board.RetagAll("cat"));
        });
    }

    // Row 8j-var. The ADR-084 amendment's setter slot: each `tag` write is one `TagSet` crossing
    // whose `String` argument Kotlin mints and C# releases after reading, then one getter crossing
    // whose result C# mints and Kotlin releases. `AddBell` is the scalar pair, which mints nothing
    // but still rides the transfer handle every call. A setter that forgets its argument handle
    // shows up as +50 over the iterations below.
    private sealed class ProbeCollar : Wardrobe.ICollar
    {
        public string Tag { get; set; } = "Mylo";
        public int Bells { get; set; }
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceBridge_SetterSlot_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var collar = new ProbeCollar();
            Assert.Equal("OREO", Wardrobe.Retagging.Retag(collar, "OREO"));
            Assert.Equal(1, Wardrobe.Retagging.AddBell(collar));
        });
    }

    // Row 8j-marker. A C#-implemented member-less interface: at a plain parameter it crosses
    // through an ADR-084 bridge factory with no slots (one transfer handle per crossing, disposed
    // after the call), comes back resolved to the same instance (the returned handle is disposed by
    // the token probe), and rides the ADR-039 pair with no callback slots (one retained unregister
    // closure, released on Dispose).
    private sealed class ProbeCharm : ICharm
    {
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceBridge_CSharpMarkerInterface_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var bracelet = new CharmBracelet();
            using var charm = new ProbeCharm();
            bracelet.Clip(charm);
            Assert.Same(charm, bracelet.Unclip());
            using IDisposable worn = bracelet.AddCharm(charm);
            Assert.Equal(1, bracelet.WornCount());
        });
    }

    // Row 8j-generic. ADR-222: a C#-implemented generic interface at an input position. One erased
    // bridge factory per interface serves `IKeepsake<string>` and `IKeepsake<int>` alike; each
    // crossing mints one transfer handle the call site disposes, and every `T` slot rides ADR-215
    // lane A's box: a Kotlin-to-C# payload is retained by Kotlin and disposed by `FromHandle<T>`,
    // a C#-to-Kotlin result is minted by `WrapOwned<T>` and released by Kotlin after the read. The
    // row crosses every slot kind once per iteration: getter, setter, method with `T` in and out,
    // a `T?` setter fed null, a self-bounded owner and an extension receiver. A generic state that
    // kept one handle more than the non-generic one shows up as +50 per slot.
    private sealed class ProbeDiary : Playroom.IKeepsake<string>
    {
        public string Item { get; set; } = "Oreo";
        public void Dispose() { }
    }

    private sealed class ProbeCounter : Playroom.IKeepsake<int>
    {
        public int Item { get; set; } = 41;
        public void Dispose() { }
    }

    private sealed class ProbeScrapbook : Playroom.IAlbum<string>
    {
        private string page = "page 1";
        public string Cover => "Oreo";
        public string? Bookmark { get; set; } = "p1";

        public string Turn(string next)
        {
            string previous = page;
            page = next;
            return previous;
        }

        public string Title() => "Scraps";
        public void Dispose() { }
    }

    private sealed class ProbeMedals : Playroom.IPodium<string>
    {
        public string Best => "Mylo";
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceBridge_CSharpGenericKeepsake_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var diary = new ProbeDiary();
            using var counter = new ProbeCounter();
            using var scrapbook = new ProbeScrapbook();
            using var medals = new ProbeMedals();
            using var curator = new Playroom.Curator();

            Assert.Equal("curator sees Oreo", Playroom.Keepsakes.Inspect(diary));
            Assert.Equal(42, Playroom.Keepsakes.Polish(counter));
            Assert.Equal("Oreo", Playroom.Keepsakes.Trade(diary, "Mylo"));
            Assert.Equal(42, Playroom.Keepsakes.Trade(counter, 7));
            Assert.Equal("shining Mylo", Playroom.KeepsakeExtensions.Shine(diary));
            Assert.Equal("crowned Mylo", Playroom.Keepsakes.Crown(medals));
            Assert.Equal("Scraps: Oreo then page 1, bookmark null", curator.Leaf(scrapbook));
        });
    }

    // Row 8j-generic-cat. The same factory with a Kotlin-backed wrapper in the `T` slot. `Trade`
    // reads the basket's Cat (C# hands Kotlin a `WrapOwned` retain of a *borrowed* wrapper, which
    // Kotlin releases after the read, never the consumer's own handle), writes Mylo back (Kotlin
    // retains, C# wraps), and returns the old Cat (a fresh wrapper the caller disposes). `PetName`
    // reads a bounded `T : Furry` the same way. Both cats the consumer passed in are disposed by
    // the consumer, and the only extra wrappers are the two the row disposes itself.
    private sealed class ProbeBasket<T>(T item) : Playroom.IKeepsake<T>
    {
        public T Item { get; set; } = item;
        public void Dispose() { }
    }

    [Fact]
    public void InterfaceBridge_GenericSlotCatPayload_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 9);
            using var mylo = new Cat("Mylo", 9);
            using var basket = new ProbeBasket<Cat>(oreo);
            using (Cat old = Playroom.Keepsakes.Trade(basket, mylo))
            {
                Assert.Equal("Oreo", old.Name);
            }
            using (Cat stored = basket.Item)
            {
                Assert.Equal("Mylo", stored.Name);
            }

            using var tabby = new Membergeneric.Tabby("Oreo", 3);
            using var cradle = new ProbeBasket<Membergeneric.Tabby>(tabby);
            Assert.Equal("Oreo", Playroom.Keepsakes.PetName(cradle));
        });
    }

    // Row 8j-generic-output. ADR-224: the same generic interface at OUTPUT positions. A Kotlin
    // `Locket` returned as `IKeepsake<T>` is a generic backing wrapper the caller disposes; a stored
    // C# `Diary` read back through a return, a property, a collection component, a nested read and
    // a lambda result resolves to itself, and each `TryResolveCSharp` hit disposes its transfer
    // handle. A backing wrapper that kept its handle, or a hit that skipped the dispose, is +50.
    [Fact]
    public void GenericInterfaceOutput_ReturnedKeepsakes_ReturnToBaseline()
    {
        var diary = new ProbeDiary();
        AssertNoLeak(() =>
        {
            using (Playroom.IKeepsake<double> locket = Playroom.KeepsakeTrove.LocketOf(4.5))
            {
                locket.Item += 1;
                Assert.Equal(5.5, locket.Item);
            }

            using var trove = new Playroom.Trove();
            using (Playroom.IKeepsake<string> kotlin = trove.Keep())
            {
                Assert.Equal("Oreo", kotlin.Item);
            }

            trove.Kept = diary;
            Assert.Same(diary, trove.Keep());
            Assert.Same(diary, trove.Kept);
            Assert.Same(diary, Assert.Single(trove.All()));
            Assert.Same(diary, trove.ByName()["kept"]);
            Assert.Same(diary, Playroom.KeepsakeTrove.Visit(_ => diary));

            using (Playroom.IKeepsake<Playroom.IKeepsake<string>> outer = Playroom.KeepsakeTrove.Nested())
            using (Playroom.IKeepsake<string> inner = outer.Item)
            {
                Assert.Equal("Mylo", inner.Item);
            }

            using var mantel = new Playroom.Mantel<bool>(true);
            using (Playroom.IKeepsake<bool> kept = mantel.Keep())
            {
                Assert.True(kept.Item);
            }
        });
    }

    // Row 8j-generic-erased. ADR-224: a C# implementation at ERASED inputs, minted by the
    // `INugetBridgeable<TSelf>` default method inside `Wrap<T>`: a `Box<IKeepsake<string>>`
    // constructor, an open `Box<IKeepsake<short>>` over an instantiation only the consumer picks,
    // and an open collection. A mint marked owned that no one disposed is +50 per crossing.
    private sealed class ProbeTally(short item) : Playroom.IKeepsake<short>
    {
        public short Item { get; set; } = item;
        public void Dispose() { }
    }

    [Fact]
    public void GenericInterfaceOutput_ErasedInputs_ReturnToBaseline()
    {
        var diary = new ProbeDiary();
        var first = new ProbeTally(1);
        var second = new ProbeTally(2);
        AssertNoLeak(() =>
        {
            using (var box = new Box<Playroom.IKeepsake<string>>(diary))
            using (Box<Playroom.IKeepsake<string>> back = Playroom.KeepsakeTrove.Boxed(box))
            {
                Assert.Same(diary, back.Value);
            }

            using (var box = new Box<Playroom.IKeepsake<short>>(first))
            using (Box<Playroom.IKeepsake<short>> back = Playroom.KeepsakeTrove.BoxedOpen(box))
            {
                Assert.Same(first, back.Value);
            }

            IReadOnlyList<Playroom.IKeepsake<short>> stashed =
                Playroom.KeepsakeTrove.Stash(new List<Playroom.IKeepsake<short>> { first, second });
            Assert.Same(second, stashed[0]);
            Assert.Same(first, stashed[1]);
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

    // Row 8f-collection. ADR-114 on Row 8f's held route and on ADR-067's `_has_value` probe: the
    // only crossings that wrap a call-scoped collection wire handle around a minting acquire.
    // `Menu` builds the list's wire container, acquires (mints the held flow's StableRef, owned by
    // the holder) and disposes the container; `MaybeRations` does the same around its probe, then
    // again for each `.Value`. A container left undisposed on either arm, or a held handle the
    // holder forgets, leaks per crossing.
    [Fact]
    public void CollectionParameterOnHeldAcquireAndHasValueProbe_ReturnsToBaseline()
    {
        string[] kinds = ["biscuit", "milo"];
        AssertNoLeak(() =>
        {
            using var board = new TreatBoard();
            using (KotlinMutableStateFlow<string> menu = board.Menu(kinds))
            {
                menu.Value = "tuna";
                Assert.Equal("tuna", menu.Value);
            }

            Assert.Null(board.MaybeRations([]));
            using KotlinStateFlow<int>? rations = board.MaybeRations([3, 5]);
            Assert.Equal(8, rations!.Value);
        });
    }

    // Row 8f-suspend. ADR-071's held-route amendment: a `suspend fun` returning
    // `MutableStateFlow<T>` awaits to a settable holder that owns the flow's StableRef (the `_async`
    // export mints it once per call, `Dispose()` frees it). The write goes through the flow-keyed
    // `_set_value`, which must mint nothing that outlives the call: an object element's write
    // borrows the C# wrapper's handle, and each `.Value` read mints one wrapper `using` frees.
    //
    // Mylo fills his treat jar and swaps his favourite toy, ten times over.
    [Fact]
    public async Task SuspendMutableStateFlow_AwaitWriteReadDispose_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var tracker = new CatMoodTracker("Mylo");
            using (KotlinMutableStateFlow<int> jar = await tracker.AwaitTreatJarAsync())
            {
                jar.Value = 4;
                Assert.Equal(4, jar.Value);
            }

            using KotlinMutableStateFlow<Cat> toys = await tracker.AwaitFavouriteToyAsync();
            using var yarn = new Cat("Yarn", 1);
            toys.Value = yarn;
            using Cat toy = toys.Value;
            Assert.Equal("Yarn", toy.Name);
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

    // Row 9p. A suspend call returning a List of the sealed BASE (ADR-119 amendment), and its
    // nullable twin. Kotlin pins one StableRef on the list; `ReadList`'s finally disposes it; each
    // `nuget_list_get` mints one element ref that `Issue54Shape.FromHandle` hands to the arm
    // wrapper, which the test disposes. The null arm mints nothing: a completion that reached
    // `ReadList(IntPtr.Zero)` would throw, and a guard that minted a list anyway would show here.
    // Ledger per iteration: list +1/-1 (ReadList finally), element +1/-1 per arm (Dispose).
    [Fact]
    public async Task Suspend_ReturningAListOfTheSealedBase_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var studio = new Issue54.Issue54Studio();

            IReadOnlyList<Issue54.Issue54Shape> shapes = await studio.SketchAsync();
            Assert.Equal(2, shapes.Count);
            foreach (Issue54.Issue54Shape shape in shapes) shape.Dispose();

            Assert.Null(await studio.SketchOrNullAsync(present: false));

            IReadOnlyList<Issue54.Issue54Shape>? present = await studio.SketchOrNullAsync(present: true);
            Assert.NotNull(present);
            foreach (Issue54.Issue54Shape shape in present) shape.Dispose();

            IReadOnlyList<Issue54.Issue54Shape> later = await Issue54.Issue54Sample.ShapesLaterAsync();
            foreach (Issue54.Issue54Shape shape in later) shape.Dispose();
        });
    }

    // Row 9q. A suspend call returning a *nullable* collection of values (ADR-119 amendment), null
    // and non-null, across all three kinds and an enum element that is projected per element.
    // Nothing escapes the read as a handle, so the only ref in play is the collection itself:
    // present, `ReadList`/`ReadSet`/`ReadMap`'s finally releases it; absent, Kotlin pins nothing
    // (`if (result == null) null else retain(...)`) and the guarded read must not mint or read one.
    [Fact]
    public async Task Suspend_ReturningANullableCollection_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var headcount = new Issue122.Headcount(["Oreo", "Mylo"]);

            Assert.Null(await headcount.MaybeAsync());
            Assert.Equal(["Oreo", "Mylo"], await headcount.MaybeTagsAsync(present: true));
            Assert.Null(await headcount.MaybeTagsAsync(present: false));
            Assert.Equal(2, (await headcount.MaybeIdsAsync(present: true))!.Count);
            Assert.Null(await headcount.MaybeIdsAsync(present: false));
            Assert.Equal(4, (await headcount.MaybeAgesAsync(present: true))!["Mylo"]);
            Assert.Null(await headcount.MaybeAgesAsync(present: false));
            Assert.Equal(2, (await headcount.MaybeTempersAsync(present: true))!.Count);
            Assert.Null(await headcount.MaybeTempersAsync(present: false));
            Assert.Null(await Issue122.AssignmentSample.NobodyAsync(present: false));
        });
    }

    // Row 9r. A nullable collection PARAMETER on the legacy routes (ADR-114 amendment), null and
    // non-null, a `List<String>?` and a `List<Cat>?`, on the suspend and the Flow route. The first
    // leak row for ADR-114's parameter route at all. C# builds one wire container per non-null
    // argument and its `finally` disposes it; a null argument is `IntPtr.Zero`, mints nothing, and
    // the guarded dispose must skip it. The `Cat` handles are borrowed by the elements and disposed
    // here, so the baseline is the board alone.
    [Fact]
    public async Task LegacyRoute_NullableCollectionParameter_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var board = new TreatBoard();
            using var oreo = new Cat("Oreo", 9);

            Assert.Equal("no kinds for no cats", await board.TallyForAsync(null, null));
            Assert.Equal("2 for Oreo", await board.TallyForAsync(["biscuit", "milo"], [oreo]));
            Assert.Equal("nothing", await board.NibbleAsync(null));
            Assert.Equal("salmon", await board.NibbleAsync());

            var seen = new List<string>();
            await foreach (var s in board.ServingsFor(null, null)) seen.Add(s);
            await foreach (var s in board.ServingsFor(["biscuit"], [oreo])) seen.Add(s);
            Assert.Equal(["the whole board", "nobody", "biscuit", "Oreo"], seen);
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

    // ADR-147 amendment: a null argument at an unbounded `T`. `Wrap<string?>` sends the null pointer
    // with `owned = false`, so the ctor, Describe and Pick arguments mint nothing and the `finally`
    // disposes nothing; the null `T` return of Pick takes the nullable result body and retains
    // nothing either. Only the crate itself is live.
    // Ledger per iteration: crate_create +1, Describe 0, Pick 0, crate_dispose -1. Net zero. A
    // positive delta is a box minted for null; a negative one is a dispose of a handle never owned.
    [Fact]
    public void GenericCtorNullableArg_NullArgument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var crate = new Crate<string?>(null);
            Assert.Equal("null:null", crate.Describe(null));
            Assert.Null(crate.Pick(null));
        });
    }

    // Row 10b. ADR-197: a member function's own `T`, on the same boxed wire as Row 10, on every
    // owner it routes on. A builtin `T` mints one `Wrap<T>` box per argument, which the C#
    // `finally` disposes, and one retain per `T` return, which `FromHandle<T>` unwraps and
    // disposes; a wrapper `T` mints nothing on the way in and one retain on the way out, released
    // by the returned wrapper's own `using`.
    // Ledger per iteration: groomer +1/-1, Echo(4) box +1/-1 and retain +1/-1, Echo(oreo)
    // retain +1/-1, Salon.Echo(7) box and retain, fetch +1/-1 with Pick(7) box and retain,
    // basket +1/-1 (its `string` box +1/-1) with Swap(3) box and retain. Net zero.
    [Fact]
    public void MemberGenericMethod_BoxedTypeParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var groomer = new Membergeneric.Groomer();
            using var oreo = new Membergeneric.Tabby("Oreo", 3);
            Assert.Equal(4, groomer.Echo(4));
            Assert.Equal("x", groomer.Echo("x"));
            using (Membergeneric.Tabby echoed = groomer.Echo(oreo))
            {
                Assert.Equal("Oreo", echoed.Name);
            }
            Assert.Equal(7, Membergeneric.Salon.Echo(7));
            using var fetch = new Membergeneric.Chore.Fetch("ball");
            Assert.Equal(7, fetch.Pick(7));
            using var basket = new Membergeneric.Basket<string>("yarn");
            Assert.Equal(3, basket.Swap(3));
        });
    }

    // ADR-212: a member's own T as a list element. Each element crosses in as a box and comes back
    // as a freshly retained wrapper, and the list handle itself is released after the read.
    // Ledger per iteration: deck, oreo, mylo +1/-1 each, each returned element +1/-1. Net zero.
    [Fact]
    public void MemberGeneric_CollectionElements_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var deck = new Membergeneric.Deck();
            using var oreo = new Membergeneric.Tabby("Oreo", 3);
            using var mylo = new Membergeneric.Tabby("Mylo", 5);
            for (int i = 0; i < 5; i++)
            {
                foreach (Membergeneric.Tabby cat in deck.Shuffle(new[] { oreo, mylo }))
                {
                    using (cat) Assert.NotEmpty(cat.Name);
                }
            }
            Assert.Equal(new[] { 3, 2, 1 }, deck.Shuffle(new[] { 1, 2, 3 }));
        });
    }

    // Row 10c. ADR-197 fault injection: Mylo refuses to be brushed after the `T` box crossed, so
    // the Kotlin export throws with the argument live and no result minted. The C# `finally`
    // still disposes the box, and the error handle is released when the exception is built.
    // Ledger per iteration: groomer +1/-1, Refuse(4) box +1/-1, error +1/-1. Net zero.
    [Fact]
    public void MemberGenericMethod_ThrowingMember_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var groomer = new Membergeneric.Groomer();
            Assert.ThrowsAny<Exception>(() => groomer.Refuse(4));
        });
    }

    // Row 10e. ADR-211: a top-level function's own `T`, on the same boxed wire as Row 10b, from a
    // static method on the file facade. A builtin `T` mints one `Wrap<T>` box the C# `finally`
    // disposes; a wrapper `T` (Mylo's toy) mints nothing and is released by its own `using`. The
    // `string` return is not a handle.
    // Pick(oreoToy, toy) (issue #556) passes two wrappers, minting nothing, and returns the second
    // as a fresh wrapper its own `using` releases.
    // Ledger per iteration: toy +1/-1, oreoToy +1/-1, Describe(4) box +1/-1, Describe(toy) nothing,
    // picked +1/-1. Net zero.
    [Fact]
    public void TopLevelGenericMethod_BoxedTypeParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var toy = new Toy("Mylo's Milo Mouse", "Brown");
            Assert.Equal("4: Int", Descriptions.Describe(4));
            Assert.Equal("Toy(name=Mylo's Milo Mouse, color=Brown): Toy", Descriptions.Describe(toy));
            using var oreoToy = new Toy("Oreo's Biscuit Ball", "Black");
            using var picked = Descriptions.Pick(oreoToy, toy);
            Assert.Equal("Mylo's Milo Mouse", picked.Name);
            // ADR-212: the passed toys are borrowed; each returned element is minted and disposed here.
            foreach (Toy shuffled in Descriptions.Shuffle(new[] { oreoToy, toy }))
            {
                using (shuffled) Assert.NotNull(shuffled.Name);
            }
        });
    }

    // Row 10d. ADR-197 with ADR-015's checked read: a T outside a dropped builtin bound fails the
    // cast at the Kotlin read of a member's own T, on a class, an object, a generic class and a
    // sealed arm. Each `Wrap<T>` box was minted before the call and must still be disposed in the
    // `finally`; a wrapper argument (Oreo, at `Comparable`) mints nothing and must not be
    // released. Ledger per iteration: groomer, basket and fetch +1/-1 each, one box +1/-1 per
    // builtin argument, the error handle +1/-1 per throw. Net zero.
    [Fact]
    public void MemberGenericMethod_BoundCastFails_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var groomer = new Membergeneric.Groomer();
            Assert.Throws<KotlinInvalidCastException>(() => groomer.Portion(3u));
            Assert.Throws<KotlinInvalidCastException>(() => Membergeneric.Salon.Weigh("tuna"));
            using var basket = new Membergeneric.Basket<string>("yarn");
            Assert.Throws<KotlinInvalidCastException>(() => basket.Measure(3u));
            using var fetch = new Membergeneric.Chore.Fetch("ball");
            using var oreo = new Membergeneric.Tabby("Oreo", 3);
            Assert.Throws<KotlinInvalidCastException>(() => fetch.Best(oreo, oreo));
        });
    }

    // A concretely typed property on a generic class (ADR-147): `Slot<int>.Keeper` is a `Cat`,
    // not `T`, so each read takes the plain exported-class getter, retaining once, and the
    // wrapper's own `using` releases it. `Label`, `Count` and the `Note` round trip are converted
    // or pass-through scalars and mint nothing. Mylo is read five times per iteration so a
    // per-read leak scales past any settle noise.
    // Ledger per iteration: wrap +1/-1 (ctor), slot_create +1, getter retain +1/-1 (x5),
    // slot_dispose -1. Net zero. A positive delta is a Keeper read nobody owns.
    [Fact]
    public void GenericClassConcreteProperty_KeeperRead_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var slot = new Slot<int>(42);
            for (var i = 0; i < 5; i++)
            {
                using Cat keeper = slot.Keeper;
                Assert.Equal("Mylo", keeper.Name);
            }
            Assert.Equal("window sill", slot.Label);
            Assert.Equal(2, slot.Count);
            slot.Note = "Oreo took the sill";
            Assert.Equal("Oreo took the sill", slot.Note);
        });
    }

    // ADR-171: a value class at the generic-class `T`. Unlike an exported class (borrowed, mints
    // nothing), a record struct has no handle of its own, so `Wrap<ChartId>` mints one boxed
    // `ChartId` through the per-value-class box export with `owned = true`, and the ctor's
    // `finally` disposes it. The `.Value` read retains the boxed slot once and the value-class
    // factory unboxes and disposes it.
    // Ledger per iteration: value-class box +1/-1 (ctor), box_create +1, getter retain +1/-1
    // (factory unbox), box_dispose -1. Net zero. A positive delta is an owned box the `finally`
    // never released, or a factory that read the underlying and forgot the handle.
    [Fact]
    public void WrapValueClass_GenericClassArgument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            var id = new ChartId("CH-OREO-1");
            using var box = new Box<ChartId>(id);
            Assert.Equal(id, box.Value);
        });
    }

    // ADR-171: the box export runs the value class's `init` (a positional record struct never
    // ran it in C#), so a nameless `WardBand` fails inside the box export, before anything is
    // minted and before `box_create` is reached. Net zero; a positive delta means the error path
    // minted a box (or a half-built Box<WardBand>) and nobody owns it. (`CatId` cannot drive
    // this row: its String-underlying constructor runs `init` in C# already.)
    [Fact]
    public void WrapValueClass_GenericClassArgumentInitThrows_ReturnsToBaseline()
    {
        using var nameless = new Patient("");
        var band = new WardBand(nameless);
        AssertNoLeak(() =>
        {
            Assert.ThrowsAny<ArgumentException>(() => new Box<WardBand>(band));
        });
    }

    // ADR-171: arity 2 on the lambda route, value classes in both slots. The first argument's
    // box is minted, then the second argument's box export throws on `WardBand`'s `init`.
    // `KotlinFunc.Invoke` used to dispose its owned argument boxes after the native call with no
    // `finally`, so a throw from the second `Wrap` stranded the first box: +1 per iteration
    // unless the dispose loop sits in a `finally`.
    // Ledger per iteration: ChartId box +1/-1, WardBand box throws (0). Net zero.
    [Fact]
    public void WrapValueClass_LambdaSecondArgumentThrows_ReturnsToBaseline()
    {
        using var courier = new ChartCourier("Ward 9");
        using var nameless = new Patient("");
        var band = new WardBand(nameless);
        using KotlinFunc<ChartId, WardBand, string> onChartForBand = courier.OnChartForBand;
        AssertNoLeak(() =>
        {
            Assert.ThrowsAny<ArgumentException>(() =>
                onChartForBand.Invoke(new ChartId("CH-MYLO-2"), band));
        });
    }

    // ADR-094 (write side): an enum at the generic-class and generic-function `T`. An enum has no
    // handle of its own, so `Wrap<Mood>` mints one over the Kotlin entry through the enum's box
    // export with `owned = true`, and the caller's `finally` disposes it. Every read back (the
    // `.Value` getter, `Identity`'s result) retains the entry once and the enum's `Factories`
    // entry reads the ordinal and disposes it.
    // Ledger per iteration: ctor box +1/-1, box_create +1, getter +1/-1, Describe box +1/-1,
    // Identity box +1/-1 and result +1/-1, null Identity 0, box_dispose -1. Net zero.
    [Fact]
    public void EnumErasedWrite_GenericClassAndFunction_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var crate = new Crate<Mood>(Mood.Grumpy);
            Assert.Equal(Mood.Grumpy, crate.Item);
            Assert.Equal("SLEEPY:GRUMPY", crate.Describe(Mood.Sleepy));
            Assert.Equal(Mood.Sleepy, Helpers.Identity(Mood.Sleepy));
            Assert.Null(Helpers.Identity<Mood?>(null));
        });
    }

    // ADR-094 (write side): an out-of-range ordinal fails inside the box export (`entries[99]`),
    // before anything is minted and before `box_create` is reached. Net zero; a positive delta
    // means the error path minted a handle, or the caller disposed one it never received.
    [Fact]
    public void EnumErasedWrite_OutOfRangeOrdinalThrows_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.ThrowsAny<Exception>(() => new Crate<Mood>((Mood)99));
            Assert.ThrowsAny<Exception>(() => Helpers.Identity((Mood)99));
        });
    }

    // ADR-198: an enum under `T : Enum<T>` (`where T : struct, Enum`), class and function route.
    // Every write mints one enum box through `Wrap<Medal>` (owned, disposed by the caller's
    // `finally`); every `T` read back retains the entry once and the enum's `Factories` entry
    // disposes it; a null `T?` crosses as the null pointer and mints nothing. The trampoline adds
    // no handle of its own: it is a local function around the same body.
    // Ledger per iteration: ctor box +1/-1, rosette +1, Outranks box +1/-1, Better box +1/-1 and
    // result +1/-1, Claim(null) 0, Claim(Gold) box +1/-1 and result +1/-1, RequirePrize box +1/-1
    // and result +1/-1, rosette dispose -1. Net zero.
    [Fact]
    public void EnumSelfBound_ClassAndFunctionRoute_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Rankings.Rosette<Rankings.Medal>(Rankings.Medal.Gold);
            Assert.True(oreo.Outranks(Rankings.Medal.Silver));
            Assert.Equal(Rankings.Medal.Gold, oreo.Better(Rankings.Medal.Silver));
            Assert.Null(oreo.Claim(null));
            Assert.Equal(Rankings.Medal.Gold, oreo.Claim(Rankings.Medal.Gold));
            Assert.Equal(
                Rankings.Medal.Silver,
                Rankings.Medals.RequirePrize(Rankings.Medal.Silver));
        });
    }

    // ADR-198 throw paths: a Kotlin `require` refusing Bronze inside the trampoline (the box was
    // minted and must still be disposed by the caller's `finally`), and a foreign C# enum the
    // constraint admits but `Wrap<T>` refuses before minting anything. Net zero; a positive delta
    // is a box the throw stranded.
    [Fact]
    public void EnumSelfBound_ThrowPaths_ReturnToBaseline()
    {
        using var oreo = new Rankings.Rosette<Rankings.Medal>(Rankings.Medal.Gold);
        AssertNoLeak(() =>
        {
            Assert.ThrowsAny<ArgumentException>(() =>
                Rankings.Medals.RequirePrize(Rankings.Medal.Bronze));
            Assert.ThrowsAny<NotSupportedException>(() =>
                new Rankings.Rosette<DayOfWeek>(DayOfWeek.Friday));
            Assert.ThrowsAny<NotSupportedException>(() =>
                Rankings.Medals.RequirePrize(DayOfWeek.Friday));
        });
    }

    // ADR-198: `T : Node<T>, T : Pet` through the same trampoline. A `Bead` is a Kotlin-backed
    // wrapper, so writing one borrows its own handle and mints nothing; each `T` read back mints
    // one wrapper the caller disposes.
    // Ledger per iteration: chain +1, Attach result +1/-1, Lead result +1/-1, chain dispose -1.
    [Fact]
    public void InvariantRecursiveBound_ReturnsToBaseline()
    {
        using var oreo = new Multibound.Bead("Oreo");
        using var mylo = new Multibound.Bead("Mylo");
        AssertNoLeak(() =>
        {
            using var chain = new Multibound.Chain<Multibound.Bead>(oreo);
            using Multibound.Bead linked = chain.Attach(mylo);
            Assert.Equal("Oreo-Mylo", linked.Name);
            using Multibound.Bead lead = Multibound.Chains.Lead(mylo);
            Assert.Equal("Mylo", lead.Name);
        });
    }

    // The second box has no wrapper when the factory fails. Materialization must release
    // it as well as the first wrapper and the outer list, preserving the original exception.
    [Fact]
    public void ListReturn_ThrowingElementFactory_ReleasesTheListHandle()
    {
        Func<NugetKotlinHandle, object> original = NugetMarshal.Factories[typeof(TopStory)];
        var fault = new InvalidOperationException("Oreo swatted the archive off the desk");
        int calls = 0;
        NugetMarshal.Factories[typeof(TopStory)] = handle =>
        {
            if (++calls == 2) throw fault;
            return new TopStory(handle, out _);
        };

        try
        {
            Settle();
            long before = NugetMarshal.LiveHandles;
            using (Newsroom newsroom = new Newsroom())
            {
                Assert.Same(fault, Assert.Throws<InvalidOperationException>(() => newsroom.Archive()));
            }

            Settle();
            long after = NugetMarshal.LiveHandles;
            Assert.True(
                after == before,
                $"expected {before} live handles after 1 throwing Archive() crossing, got {after} (delta {after - before}); the second element box must be released before a wrapper exists");
        }
        finally
        {
            NugetMarshal.Factories[typeof(TopStory)] = original;
        }
    }

    // A factory can construct a wrapper before throwing. Disposing that saved wrapper must
    // remain safe when materialization also cleans up the failed element's owning handle.
    [Fact]
    public void ListReturn_ConstructThenThrowFactory_SavedWrapperDisposalReturnsToBaseline()
    {
        Func<NugetKotlinHandle, object> original = NugetMarshal.Factories[typeof(TopStory)];
        var fault = new InvalidOperationException("Mylo vetoed the headline after it was filed");
        TopStory? saved = null;
        int calls = 0;
        NugetMarshal.Factories[typeof(TopStory)] = handle =>
        {
            var story = new TopStory(handle, out _);
            if (++calls == 2)
            {
                saved = story;
                throw fault;
            }
            return story;
        };

        try
        {
            Settle();
            long before = NugetMarshal.LiveHandles;
            using (Newsroom newsroom = new Newsroom())
            {
                Assert.Same(fault, Assert.Throws<InvalidOperationException>(() => newsroom.Archive()));
            }

            Assert.NotNull(saved);
            saved.Dispose();
            saved.Dispose();
            Settle();
            long after = NugetMarshal.LiveHandles;
            Assert.True(
                after == before,
                $"expected {before} live handles after saved wrapper disposal, got {after} (delta {after - before})");
        }
        finally
        {
            saved?.Dispose();
            NugetMarshal.Factories[typeof(TopStory)] = original;
        }
    }

    // ---- ADR-176: interface collection components ----

    // A C#-side IPet that counts its Dispose calls, for the throwing-factory row below: the
    // marshaller's throw-path cleanup must never dispose an object the consumer owns.
    private sealed class CountingDog(string name) : IPet
    {
        public int Disposals { get; private set; }
        public string Name { get; } = name;
        public int Legs => 4;
        public string? Nickname => null;
        public string Vibe => "counting";
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} fetches the {item}";
        public void Nap() { }
        public void Dispose() => Disposals++;
    }

    // ADR-176 row 1. `IReadOnlyList<IPet>` return of two Kotlin-backed Cats: the list handle plus
    // one element box per element; each element becomes a `Pet` wrapper the test disposes.
    [Fact]
    public void InterfaceListReturn_ElementsDisposed_ReturnsToBaseline()
    {
        using var home = new FosterHome();
        AssertNoLeak(() =>
        {
            IReadOnlyList<IPet> pets = home.ResidentsNow();
            Assert.Equal(2, pets.Count);
            foreach (IPet pet in pets) pet.Dispose();
        });
    }

    // ADR-176 row 2. Mixed parameter: the list handle (owned, released in the ADR-073 finally),
    // the Cat's own handle (not owned: `Wrap` hands back the wrapper's handle), and for Rex one
    // owned bridge transfer handle the fill loop disposes after `nuget_list_add`, plus the Kotlin
    // bridge object the ADR-084 cleaner releases (hence `Settle`).
    [Fact]
    public void InterfaceListParameter_MixedElements_ReturnsToBaseline()
    {
        using var home = new FosterHome();
        using var oreo = new Cat("Oreo");
        AssertNoLeak(() =>
        {
            string rollCall = home.Roll(new IPet[] { new Dog("Rex"), oreo });
            Assert.Contains("Rex: Woof!", rollCall);
        });
    }

    // ADR-176 row 3. Echo of a C#-implemented element: the returned element's box hits the
    // ADR-084 token probe, resolves to the caller's own Dog and must be disposed on that path.
    [Fact]
    public void InterfaceListEcho_CSharpElementResolved_ReturnsToBaseline()
    {
        using var home = new FosterHome();
        var rex = new Dog("Rex");
        AssertNoLeak(() =>
        {
            IReadOnlyList<IPet> back = home.Echo(new IPet[] { rex });
            Assert.Same(rex, Assert.Single(back));
        });
    }

    // ADR-176 row 4. `[countingDog, oreo, oreo]` echoed back with the `IPet` factory swapped for
    // one that disposes its box and throws on its SECOND call. Element 0 resolves through the
    // token probe (no factory call), element 1 is factory call 1, element 2 throws. The
    // `DisposeMaterialized` cleanup then runs over the elements already built: the Pet wrapper
    // must be disposed, the caller's own CountingDog must NOT be. The list handle is released.
    [Fact]
    public void InterfaceListReturn_ThrowingElementFactory_DoesNotDisposeCSharpElement()
    {
        Func<NugetKotlinHandle, object> original = NugetMarshal.Factories[typeof(IPet)];
        int calls = 0;
        NugetMarshal.Factories[typeof(IPet)] = handle =>
        {
            if (++calls == 2)
            {
                handle.Dispose();
                throw new InvalidOperationException("Mylo knocked the foster roster off the fridge");
            }
            return new Pet(handle, out _);
        };

        try
        {
            var countingDog = new CountingDog("Rex");
            Settle();
            long before = NugetMarshal.LiveHandles;

            using (var home = new FosterHome())
            using (var oreo = new Cat("Oreo"))
            {
                Assert.Throws<InvalidOperationException>(() =>
                    home.Echo(new IPet[] { countingDog, oreo, oreo }));
            }

            Settle();
            long after = NugetMarshal.LiveHandles;
            Assert.Equal(0, countingDog.Disposals);
            Assert.True(
                after == before,
                $"expected {before} live handles after 1 throwing Echo() crossing, got {after} (delta {after - before})");
        }
        finally
        {
            NugetMarshal.Factories[typeof(IPet)] = original;
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

    // Row 13-iface. ADR-160: an INTERFACE payload on the same per-call route. Kotlin retains one
    // handle per invocation for the `Drummer` it hands over, and C# materialises it through the
    // `IDrummer` factory into a backing wrapper the consumer disposes (`using (d)`, the ADR-036
    // ownership row 13 restates). A wrapper that does not release on Dispose, or a thunk that
    // retains twice, leaks two handles per call. Thousands of iterations for the reason row 13
    // gives. Oreo and Mylo take the stand five thousand times each.
    [Fact]
    public void CallbackMemberInterfacePayload_EachInvocation_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var bandstand = new Bandstand();
            int played = 0;
            bandstand.EachDrummer(d => { using (d) { played += d.Name.Length; } });
            Assert.Equal(8, played);
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

    // Row 14d. ADR-161's flow residue, now a measured row: a `Flow<T>` item whose materialisation
    // throws. Kotlin has already handed the item's handle over when the C# read fails, so the only
    // release is the one the read owns on its failure branch (`Materialize<T>`'s single owner,
    // which a factory that did construct a wrapper shares, so the two never both fire). The swapped
    // factory throws WITHOUT disposing the handle it was given: `CallbackFaultTests`' variant
    // disposes it, which would hide the very handle this row measures. Oreo's tantrum, ten times.
    [Fact]
    public async Task FlowItemMaterialisationFailure_ReleasesTheItemHandle()
    {
        Func<NugetKotlinHandle, object> original = NugetMarshal.Factories[typeof(Tantrum)];
        NugetMarshal.Factories[typeof(Tantrum)] =
            _ => throw new InvalidOperationException("Oreo hid under the sofa mid-tantrum");
        try
        {
            await AssertNoLeakAsync(async () =>
            {
                using var faults = new CallbackFaults();
                await Assert.ThrowsAsync<InvalidOperationException>(async () =>
                {
                    await foreach (Tantrum tantrum in faults.TantrumStream()) tantrum.Dispose();
                });
            });
        }
        finally
        {
            NugetMarshal.Factories[typeof(Tantrum)] = original;
        }
    }

    // Row 14e. ADR-161 part C's late call, its argument. `CallLastRemoved` invokes the listener
    // the disposed subscription removed, so the thunk's key lookup misses and the call is dropped.
    // Kotlin minted the `String` payload's StableRef before it called, and the delegate that would
    // have read and freed it never runs: the miss branch has to free it. Fifty late calls, each
    // one handle that used to stay live for good.
    [Fact]
    public void DroppedLateCallbackArgument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var faults = new CallbackFaults();
            IDisposable sub = faults.AddFaultListener(_ => { });
            sub.Dispose();
            faults.CallLastRemoved("Mylo, after the door closed");
        });
    }

    // Row 14f. The value-returning shape of the same miss: the stashed per-call formatter is
    // invoked after `StashFormatter` returned, the thunk reports `ObjectDisposedException` through
    // the error channel instead of running, and Kotlin catches it. The `"Mylo"` argument handle is
    // the one the miss branch frees; the error holder is row 14a's path.
    [Fact]
    public void ReportedLateCallbackArgument_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var faults = new CallbackFaults();
            Assert.Equal("Oreo!", faults.StashFormatter(name => name + "!"));
            Assert.StartsWith("stale ", faults.CallStashedSafely());
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
            Action<string?> record = Recorder.SignIn();
            record.Invoke(null);
            record.Invoke("Oreo");
            Assert.Equal("Oreo", Recorder.LastSeen());

            Func<string, string?> find = Recorder.Finder();
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

    // Row 9i. ROADMAP Phase 4 line 23: an admitted DEPENDENCY class returned from a top-level
    // `suspend fun` (the type reached the closure only through that member). The completion
    // constructs the `global::`-qualified wrapper over the handle Kotlin retained; `using` frees it.
    // The parameter half hands a C#-built one straight back in, which must borrow, not mint.
    [Fact]
    public async Task TopLevelSuspendDependencyClass_ReturnAndParameter_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using Mousetoy toy = await Errands.FetchMousetoyAsync("Oreo");
            Assert.Equal("black-and-white", toy.Squeak);
            Assert.Equal("Mylo bats the black-and-white mouse under the sofa", await Errands.SqueakOfAsync(toy));
        });
    }

    // Row 9j. The value-class suspend return: Kotlin retains a BOXED value class, and the
    // completion's `NugetUnbox` reads the underlying and disposes the box in its `finally`. The
    // struct itself owns nothing, so there is nothing for the caller to dispose; a box left behind
    // is +1 per call. Top-level (dependency and module-local) and class-level, non-null and null.
    [Fact]
    public async Task SuspendValueClassReturn_UnboxedOnce_ReturnsToBaseline()
    {
        using var oreo = new ErrandRunner("Oreo");
        using var mylo = new ErrandRunner("Mylo");
        // Warm-up: each runner creates its coroutine scope ONCE, on its first suspend call
        // (`GetOrCreateScope`), and holds that scope handle until it is disposed. Without these two
        // calls the window counts those two one-off scopes (a flat +2 measured, not per call) instead
        // of the completions it is here to measure.
        await oreo.LookUpNametagAsync();
        await mylo.LookUpNametagAsync();
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal(new Chipcode("oreo-985112"), await Errands.ScanChipAsync("Oreo"));
            Assert.Equal(new Nametag("Oreo, if found please return to the sofa"), await Errands.FetchNametagAsync("Oreo"));
            Assert.Null(await Errands.FindNametagAsync("Mylo"));
            Assert.Equal(new Nametag("Oreo's spare tag"), await oreo.LookUpNametagAsync());
            Assert.Null(await mylo.LookUpNametagAsync());
            // The enum arm: a boxed Int ordinal, freed by `FromHandle<int>`; null mints nothing.
            Assert.Equal(Chore.Fetch, await Errands.ChoreForAsync("Oreo"));
            Assert.Null(await Errands.ChoreForAsync("Stray"));
        });
    }

    // Row 9j-race. The TIGHT LOOP twin of Rows 9i and 9j (see Row 6f-race for why fifty cannot
    // see it): `grabMousetoyNow` and `findNametagNow` never suspend, so the completion can beat the
    // P/Invoke's return. Alternates the value and null arms so the null path shares the window.
    //
    // Oreo and Mylo fetch the mouse and check their tags two thousand times in a row.
    [Fact]
    public async Task SuspendDependencyClassAndValueClass_TightLoop_ReturnsToBaseline()
    {
        int round = 0;
        await AssertNoLeakAsync(
            async () =>
            {
                string cat = (round++ % 2) == 0 ? "Oreo" : "Mylo";
                using Mousetoy toy = await Errands.GrabMousetoyNowAsync(cat);
                Assert.Equal(cat == "Oreo" ? "black-and-white" : "milky-brown", toy.Squeak);
                Nametag? tag = await Errands.FindNametagNowAsync(cat);
                Assert.Equal(cat == "Oreo", tag.HasValue);
            },
            iterations: 2000);
    }

    // Row 9k. ADR-174: a suspend call owned by an INTERFACE, made through the `IFeed`-typed
    // reference `Feeds.MakeFeed()` hands back (the ADR-040 backing wrapper around an `RssFeed`).
    // The wrapper mints its own scope on the first suspend call and `await using` drains it, so
    // one wrapper per crossing covers the handle, the scope and the completion together. Both the
    // ordinary and the generic implementer (`Crate<Int>`, reached only through the interface's own
    // dispatch export) cross in the same iteration.
    [Fact]
    public async Task InterfaceOwnedSuspend_ThroughIFeed_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using Catfeed.IFeed feed = Catfeed.Feeds.MakeFeed();
            Assert.Equal("rss-3", await feed.FetchAsync(3));

            await using Catfeed.IFeed crate = Catfeed.Feeds.MakeCrate();
            Assert.Equal("crate-3", await crate.FetchAsync(3));
        });
    }

    // Row 9k-flow. The Flow twin of Row 9k: an interface-owned `Flow` collected to completion
    // through `IFeed`, the abstract member and the interface's DEFAULT member (`doubled`, whose body
    // is `ticks().map { ... }` on the interface) both, then the wrapper's scope drained.
    [Fact]
    public async Task InterfaceOwnedFlow_CollectedThroughIFeed_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using Catfeed.IFeed feed = Catfeed.Feeds.MakeFeed();
            var ticks = new List<int>();
            await foreach (int tick in feed.Ticks()) ticks.Add(tick);
            Assert.Equal(new[] { 1, 2, 3 }, ticks);

            var doubled = new List<int>();
            await foreach (int portion in feed.Doubled()) doubled.Add(portion);
            Assert.Equal(new[] { 2, 4, 6 }, doubled);
        });
    }

    // Row 9k-state. An interface-owned `StateFlow` property read through `IFeed`. On the class
    // route a StateFlow PROPERTY getter hands back a `KotlinStateFlow<T>` that owns no handle (its
    // value read and collect go through the owner's `_handle`), so the read is spelled as
    // `StateFlowTests` spells it, with no `using`. A getter on the interface route that mints a ref
    // to read `.Value` and never releases it is +1 per crossing here.
    [Fact]
    public async Task InterfaceOwnedStateFlow_ValueReadThroughIFeed_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using Catfeed.IFeed feed = Catfeed.Feeds.MakeFeed();
            Assert.Equal(1, feed.Level.Value);

            await using Catfeed.IFeed crate = Catfeed.Feeds.MakeCrate();
            Assert.Equal(2, crate.Level.Value);
        });
    }

    // Row 9k-race. The TIGHT LOOP twin of Row 9k (see Row 9b for the window): `count()` has no
    // suspension point, so the completion can beat the P/Invoke that started it, on the interface
    // owner's route. The receiver is hoisted and its lazy scope warmed before the baseline, as in
    // Row 9g, so the loop measures nothing but the completions.
    //
    // Mylo counts the kibble five thousand times and gets three every time.
    [Fact]
    public async Task InterfaceOwnedSuspend_NoSuspensionPoint_TightLoop_ReturnsToBaseline()
    {
        await using Catfeed.IFeed feed = Catfeed.Feeds.MakeFeed();
        Assert.Equal(3, await feed.CountAsync());

        await AssertNoLeakAsync(
            async () => Assert.Equal(3, await feed.CountAsync()),
            iterations: 5000);
    }

    // Row 9l. ADR-175: the scope moves from the sealed ARM to the sealed BASE, a new owner path.
    // A base-typed `Shape` (a `Shape.Loaf` from `ShapeSample.LoafShape`) awaits a base-dispatched
    // override, an arm-declared member on the inherited scope, and reads the base's StateFlow
    // property; then the arm's `DisposeAsync` override has to drain the base-owned scope. The enum
    // arm (`CurlArm`, whose handle is a StableRef to the entry) crosses in the same iteration, since
    // it is the arm whose box owns no member of its own.
    [Fact]
    public async Task SealedBaseOwnedSuspend_ThroughTheBase_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using Curlup.Shape oreo = Curlup.ShapeSample.LoafShape(2);
            Assert.Equal(6, await oreo.AreaAsync());
            Assert.Equal(7, await ((Curlup.Shape.Loaf)oreo).KneadAsync(3));
            Assert.Equal(2, oreo.Level.Value);

            await using Curlup.Shape curl = Curlup.ShapeSample.CurlShape(false);
            Assert.Equal(100, await curl.AreaAsync());
            Assert.Equal(9, await curl.FallbackAsync());
        });
    }

    // Row 9l-race. The TIGHT LOOP twin of Row 9l (see Row 9b for the window): `Loaf.area()` has no
    // suspension point, so the completion can beat the P/Invoke that started it, now on the sealed
    // base's route. Receiver hoisted and the base scope warmed before the baseline, as in Row 9k-race.
    //
    // Oreo measures his loaf five thousand times and it is six every time.
    [Fact]
    public async Task SealedBaseOwnedSuspend_NoSuspensionPoint_TightLoop_ReturnsToBaseline()
    {
        await using Curlup.Shape oreo = Curlup.ShapeSample.LoafShape(2);
        Assert.Equal(6, await oreo.AreaAsync());

        await AssertNoLeakAsync(
            async () => Assert.Equal(6, await oreo.AreaAsync()),
            iterations: 5000);
    }

    // Row 9-iface. ADR-204 on the suspend route: `scanLater` returns the sealed INTERFACE and has no
    // suspension point, so the completion can beat the P/Invoke that started it (Row 9b's window),
    // and it must still hand its one result handle to `IConnectableDevice.FromHandle`, which gives
    // it to the arm. The parameter half crosses in the same iteration (`connectLater` borrows the
    // C#-built tracker's handle on the async route). Top-level, so there is no receiver scope to
    // hoist or warm, on the `Suspend_NoSuspensionPoint_...` precedent.
    //
    // Mylo's tracker is found in the attic five thousand times.
    [Fact]
    public async Task SealedInterfaceOverDeclaredArms_Suspend_TightLoop_ReturnsToBaseline()
    {
        using var mylo = new Issue463.RemoteDevice(host: "attic", battery: 80);

        await AssertNoLeakAsync(
            async () =>
            {
                using Issue463.IConnectableDevice found = await Issue463.Devices.ScanLaterAsync(1);
                Assert.IsType<Issue463.RemoteDevice>(found);
                Assert.Equal("connected remote://attic", await Issue463.Devices.ConnectLaterAsync(mylo));
            },
            iterations: 5000);
    }

    // Rows 16 to 16k. ADR-187: a wrapper dropped WITHOUT `Dispose()` has its Kotlin handle released
    // when the .NET GC finalizes the generated `NugetKotlinHandle : SafeHandle`. Every other row in
    // this file disposes and measures the prompt path; these rows deliberately do not dispose, and
    // measure the eventual one. Each row drops `Drops` undisposed instances from a non-inlined
    // helper (so no local in the test method keeps them reachable), checks the drop really minted
    // handles (a row that minted nothing would go green without crossing the seam it names), then
    // runs bounded GC + finalizer rounds until the count is back at the baseline.
    //
    // Before ADR-187 lands there is no finalizer anywhere in the generated code, so every row here
    // except 16i and 16k is red by design: the count never comes back. 16i pins the subscription
    // exception (a discarded subscription keeps delivering and keeps its token) and 16k is the
    // double-free guard; both are green before and after.
    //
    // Oreo and Mylo leave their toys all over the house, and the GC tidies up after them.
    private const int Drops = 10;
    private const int FinalizerRounds = 50;

    private static long CollectUntilBaseline(long baseline)
    {
        for (int round = 0; round < FinalizerRounds && NugetMarshal.LiveHandles > baseline; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
        return NugetMarshal.LiveHandles;
    }

    private static void AssertReleasedByTheGc(string what, Action dropUndisposed)
    {
        Settle();
        long baseline = NugetMarshal.LiveHandles;

        for (int i = 0; i < Drops; i++) dropUndisposed();

        AssertDroppedHandlesComeBack(what, baseline);
    }

    private static async Task AssertReleasedByTheGcAsync(string what, Func<Task> dropUndisposed)
    {
        Settle();
        long baseline = NugetMarshal.LiveHandles;

        for (int i = 0; i < Drops; i++) await dropUndisposed();

        AssertDroppedHandlesComeBack(what, baseline);
    }

    private static void AssertDroppedHandlesComeBack(string what, long baseline)
    {
        long dropped = NugetMarshal.LiveHandles;
        Assert.True(
            dropped > baseline,
            $"dropping {Drops} undisposed {what} minted no live handle ({baseline} before, {dropped} after), so this row does not cross the ADR-187 seam");

        long after = CollectUntilBaseline(baseline);
        if (after == baseline) return;

        Assert.Fail(
            $"expected {baseline} live handles once the GC finalized {Drops} undisposed {what}, got {after} " +
            $"({after - baseline} of the {dropped - baseline} dropped handles still live after {FinalizerRounds} GC rounds)");
    }

    // Row 16. The headline: a class wrapper constructed from C# and never disposed. Oreo is named
    // and forgotten.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedCat() => Assert.Equal("Oreo", new Cat("Oreo", 9).Name);

    [Fact]
    public void UndisposedClassWrapper_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("Cat wrappers", DropUndisposedCat);

    // Row 16a. The callback-payload leak ADR-187 closed:
    // the per-call lambda-parameter route of Row 8i, with a callback body that reads each `Toy` and
    // does NOT dispose it. The payload wrapper is the only owner of its handle (ADR-036's 2026-09-11
    // amendment), so without a finalizer two handles per call stay live for the process. The
    // receiver is disposed, so the payloads are the only thing that can still be counted.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedToyPayloads()
    {
        using var mylo = new Cat("Mylo", 9);
        var toyNames = new List<string>();
        mylo.ForEachToy(toy => toyNames.Add(toy.Name));
        Assert.Equal(new List<string> { "Mouse", "Ball" }, toyNames);
    }

    [Fact]
    public void UndisposedCallbackObjectPayload_LambdaParameterRoute_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("Toy callback payloads", DropUndisposedToyPayloads);

    // Row 16b. The same payload residual on ADR-160's route (Row 13 without its `using (c)`): every
    // `Chime` candidate handed to the predicate is read and dropped. The member's returned chime is
    // disposed, so only the predicate payloads are left for the GC.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedChimePayloads()
    {
        using var metronome = new Metronome(4);
        using Chime chime = metronome.FirstChime(c => c.Weight >= 2);
        Assert.Equal("Mylo", chime.Name);
    }

    [Fact]
    public void UndisposedCallbackObjectPayload_CallbackMemberRoute_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("Chime predicate payloads", DropUndisposedChimePayloads);

    // Row 16c. ROADMAP's abandoned-Flow item, wrapper-typed half only: `Newsroom.Stream()` emits two
    // `TopStory` wrappers with no suspension point between them. The consumer takes the first
    // (and disposes it), waits long enough for the second to have been delivered, then
    // `DisposeAsync`s the enumerator. The second item's box was minted by `FromHandle<TopStory>`
    // into a wrapper that nobody will ever read: either it sits in the abandoned channel or, if it
    // landed after `DisposeAsync`, `TryWrite` returned false and the wrapper was dropped on the
    // spot. Both are the same leak (an undisposed wrapper over an owned box), and the GC is the only
    // thing that can return it. The newsroom itself is disposed. The ADR-123 collection-element half
    // is row 16l, released by the enumerator rather than the GC.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task DropAbandonedTopStory()
    {
        using var newsroom = new Newsroom();
        IAsyncEnumerator<TopStory> stories = newsroom.Stream().GetAsyncEnumerator();
        Assert.True(await stories.MoveNextAsync());
        using (TopStory first = stories.Current)
        {
            Assert.Equal("Oreo escapes the cardboard box (again)", first.Title);
        }
        await Task.Delay(100);   // let Mylo's sunbeam story arrive before the reader walks away
        await stories.DisposeAsync();
    }

    [Fact]
    public async Task AbandonedWrapperTypedFlowItem_AfterDisposeAsync_IsReleasedByTheGc() =>
        await AssertReleasedByTheGcAsync("abandoned TopStory flow items", DropAbandonedTopStory);

    // Row 16l. The ADR-123 collection-element half of row 16c's abandoned-Flow item. A
    // `Flow<List<TopStory>>` item read but never handed to the consumer is a list nobody can
    // reach, holding one owned wrapper per element; the enumerator, not the GC, has to release
    // them. `Newsroom.Editions()` (`LateEditions`) sends edition 1 and 2 back to back and edition 3
    // after a non-cancellable pause, so one crossing covers both abandoned positions: edition 2 is
    // still queued when the reader walks away, edition 3 is minted after `DisposeAsync` and, with
    // the one-credit gate (ADR-207), parks on the credit and is released Kotlin-side. Measured
    // WITHOUT a GC on purpose: under `AssertNoLeakAsync` the dropped wrappers' finalizers
    // (ADR-187) would hide a release the enumerator never made.
    [Fact]
    public async Task AbandonedCollectionFlowItems_AreReleasedByTheEnumerator()
    {
        for (int attempt = 1; ; attempt++)
        {
            Settle();
            long before = NugetMarshal.LiveHandles;

            for (int i = 0; i < 5; i++) await AbandonEditions();

            long after = NugetMarshal.LiveHandles;   // no Settle: a finalizer must not count
            if (after == before) return;
            if (after < before && attempt < MeasurementAttempts) continue;

            Assert.Fail(
                $"expected {before} live handles after 5 abandoned edition streams, got {after} (delta {after - before}) on attempt {attempt}; the queued and the late edition's wrappers must be released by the enumerator");
        }
    }

    private static async Task AbandonEditions()
    {
        using var newsroom = new Newsroom();
        IAsyncEnumerator<IReadOnlyList<TopStory>> editions = newsroom.Editions().GetAsyncEnumerator();
        Assert.True(await editions.MoveNextAsync());
        foreach (TopStory story in editions.Current) story.Dispose();
        await Task.Delay(100);   // edition 2 is queued before the reader walks away
        await editions.DisposeAsync();
        await Task.Delay(500);   // edition 3 parks on the credit, released Kotlin-side
    }

    // Row 16d. A sealed ARM, both ways one is obtained: constructed from C# (Row 1c's
    // `new Nap.Deep(minutes: 12)`, whose handle lives on the BASE's field) and returned from Kotlin
    // through the arm's own `FromHandle` path (`Newsroom.DeepNap()`). The newsroom is disposed.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedSealedArms()
    {
        Assert.Equal(12, new Nap.Deep(minutes: 12).Minutes);
        using var newsroom = new Newsroom();
        Assert.Equal(720, newsroom.DeepNap().Minutes);
    }

    [Fact]
    public void UndisposedSealedArm_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("Nap.Deep sealed arms", DropUndisposedSealedArms);

    // Row 16e. An INTERFACE-typed return: `houseBrusher()` hands back an anonymous Kotlin `Brusher`
    // behind the ADR-040 backing wrapper (Row 6l's fixture), the interface container kind.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedInterfaceReturn() =>
        Assert.Equal("Oreo is brushed", BrusherKt.HouseBrusher().Brush());

    [Fact]
    public void UndisposedInterfaceTypedReturn_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("IBrusher interface returns", DropUndisposedInterfaceReturn);

    // Row 16f. `KotlinFunc` and `KotlinAction` returned from Kotlin, value-only captures so the
    // lambda's own StableRef is the only handle in play (no ADR-084 bridge for the cleaner to owe).
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedKotlinFuncAndAction()
    {
        Assert.Equal(5, PetRelayKt.Adder(2).Invoke(3));
        Recorder.SignIn().Invoke("Mylo");
        Assert.Equal("Mylo", Recorder.LastSeen());
    }

    [Fact]
    public void UndisposedKotlinFuncAndAction_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("KotlinFunc/KotlinAction returns", DropUndisposedKotlinFuncAndAction);

    // Row 16g. The suspend twin of 16f: a `KotlinSuspendFunc` read off a property (`onFeedPortion`)
    // and awaited to completion, then dropped. The feeder is disposed.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task DropUndisposedKotlinSuspendFunc()
    {
        await using var feeder = new CatFeeder("Oreo");
        Assert.Equal("Oreo devoured 30g of tuna!", await feeder.OnFeedPortion.Invoke("tuna", 30, CancellationToken.None));
    }

    [Fact]
    public async Task UndisposedKotlinSuspendFunc_IsReleasedByTheGc() =>
        await AssertReleasedByTheGcAsync("KotlinSuspendFunc returns", DropUndisposedKotlinSuspendFunc);

    // Row 16h. A `KotlinStateFlow<T>` that owns a handle: Row 8f's `CatSnackDispenser.Level()`, whose
    // `KotlinMutableStateFlow<int>` holds the StableRef to the flow itself in `_ownedHandle`. The
    // dispenser is disposed; the flow wrapper is dropped.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedStateFlow()
    {
        using var dispenser = new CatSnackDispenser();
        KotlinMutableStateFlow<int> level = dispenser.Level();
        level.Value = 7;
        Assert.Equal(7, level.Value);
    }

    [Fact]
    public void UndisposedKotlinStateFlow_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("KotlinStateFlow returns", DropUndisposedStateFlow);

    // Row 16i. The one kind ADR-187 deliberately does NOT release on the GC while its owner lives
    // (human gate, 2026-10-02): a discarded subscription keeps delivering, as a discarded .NET event
    // subscription does. So the alive half pins the opposite of its neighbours: Oreo stays alive,
    // the `IDisposable`s from `AddMoodListener` are dropped, the GC and finalizers run, and every
    // dropped listener must still hear the mood change.
    //
    // The disposed half: the token is the `NugetHandles.retain(unregister)` ref Kotlin mints on
    // subscribe (StoredCallbackExports.kt), and the owner's handle now unregisters every
    // subscription still attached to it, through the same remove export `Dispose()` calls, before
    // it releases itself. So disposing Oreo returns the count to the baseline, not to one token
    // per dropped subscription. A subscription the consumer kept and disposes AFTER the owner is a
    // silent no-op: its unregister already ran, and running it twice would release the token twice.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropSubscriptions(Cat oreo, StrongBox<int> heard)
    {
        for (int i = 0; i < Drops; i++)
        {
            Assert.NotNull(oreo.AddMoodListener(_ => Interlocked.Increment(ref heard.Value)));
        }
    }

    [Fact]
    public void DiscardedSubscription_KeepsDeliveringAfterTheGc_AndIsReleasedWithItsOwner()
    {
        Settle();
        long baseline = NugetMarshal.LiveHandles;
        var heard = new StrongBox<int>(0);
        var keptHeard = new StrongBox<int>(0);

        var oreo = new Cat("Oreo", 9);
        DropSubscriptions(oreo, heard);
        IDisposable kept = oreo.AddMoodListener(_ => Interlocked.Increment(ref keptHeard.Value));
        Assert.Equal(baseline + 1 + Drops + 1, NugetMarshal.LiveHandles);

        for (int round = 0; round < FinalizerRounds / 5; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }

        oreo.TriggerMoodChange(Mood.Happy);
        Assert.Equal(Drops, heard.Value);
        Assert.Equal(1, keptHeard.Value);

        oreo.Dispose();
        Settle();
        Assert.Equal(baseline, NugetMarshal.LiveHandles);

        kept.Dispose();
        kept.Dispose();
        Settle();
        Assert.Equal(baseline, NugetMarshal.LiveHandles);
    }

    // Row 16n. The GC half of row 16i: Oreo herself is dropped undisposed, with subscriptions the
    // consumer discarded and whose listeners capture only a `StrongBox`, so nothing C#-side roots
    // the wrapper. Finalizing Oreo's handle unregisters each subscription and frees its token before
    // the handle itself goes. A listener that captured Oreo would keep her reachable through the
    // thunk key table, which is why these capture nothing of hers.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropOwnerWithDiscardedSubscriptions()
    {
        var heard = new StrongBox<int>(0);
        var oreo = new Cat("Oreo", 9);
        Assert.NotNull(oreo.AddMoodListener(_ => Interlocked.Increment(ref heard.Value)));
        Assert.NotNull(oreo.AddMoodListener(_ => Interlocked.Increment(ref heard.Value)));
        oreo.TriggerMoodChange(Mood.Happy);
        Assert.Equal(2, heard.Value);
    }

    [Fact]
    public void UndisposedOwnerWithDiscardedSubscriptions_IsReleasedByTheGc() =>
        AssertReleasedByTheGc("Cats with discarded subscriptions", DropOwnerWithDiscardedSubscriptions);

    // Row 16j. The suspend SCOPE handle: a wrapper whose first `suspend` call lazily created
    // `_scopeHandle`, awaited to completion and then dropped, so there is no call in flight and
    // both the object handle and the scope handle are the GC's to release.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task DropUndisposedWrapperWithAScope() =>
        Assert.Equal("Oreo settled in 5 minutes", await new TestLibrary.Kdoc.BoardingDesk("Oreo").SettleAsync(5));

    [Fact]
    public async Task UndisposedWrapperWithASuspendScope_IsReleasedByTheGc() =>
        await AssertReleasedByTheGcAsync("BoardingDesks with a live suspend scope", DropUndisposedWrapperWithAScope);

    // Row 16k. The double-free guard. Every kind the rows above drop, disposed explicitly this time
    // and then dropped, so once ADR-187 lands each one's `SafeHandle` is both disposed AND
    // finalizable. After the GC and finalizers have run, the count must be EXACTLY the baseline:
    // below it means a finalizer released a handle `Dispose()` had already released (a `StableRef`
    // freed twice, which can also take the process down rather than miscount). Green before and
    // after the feature.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task DropDisposedOfEveryKind()
    {
        using (var oreo = new Cat("Oreo", 9))
        {
            Assert.Equal("Oreo", oreo.Name);
            oreo.ForEachToy(toy => { using (toy) { Assert.NotEmpty(toy.Name); } });
            oreo.AddMoodListener(_ => { }).Dispose();
        }

        using (var newsroom = new Newsroom())
        {
            await foreach (TopStory story in newsroom.Stream())
            {
                story.Dispose();
            }
            using Nap.Deep deep = newsroom.DeepNap();
            Assert.Equal(720, deep.Minutes);
        }

        using (var deep = new Nap.Deep(minutes: 12)) Assert.Equal(12, deep.Minutes);
        using (IBrusher brusher = BrusherKt.HouseBrusher()) Assert.Equal("Oreo is brushed", brusher.Brush());
        // ADR-239: a returned lambda is a delegate with nothing to dispose; its target releases it.
        Assert.Equal(5, PetRelayKt.Adder(2).Invoke(3));

        using (var dispenser = new CatSnackDispenser())
        using (KotlinMutableStateFlow<int> level = dispenser.Level())
        {
            level.Value = 3;
            Assert.Equal(3, level.Value);
        }

        await using (var desk = new TestLibrary.Kdoc.BoardingDesk("Mylo"))
        {
            Assert.Equal("Mylo settled in 5 minutes", await desk.SettleAsync(5));
        }
    }

    [Fact]
    public async Task DisposedWrappers_ThenFinalized_AreNotReleasedTwice()
    {
        Settle();
        long baseline = NugetMarshal.LiveHandles;

        for (int i = 0; i < Drops; i++) await DropDisposedOfEveryKind();

        for (int round = 0; round < FinalizerRounds / 5; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
        Settle();

        Assert.Equal(baseline, NugetMarshal.LiveHandles);
    }

    // ------------------------------------------------------------------------------------------
    // Rows 17a to 17f. ADR-210 slice (a): a suspend lambda PARAMETER
    // (`suspend (T) -> R` as `Func<T, CancellationToken, Task<R>>`).
    //
    // Each invocation of the C# lambda is an `awaitForKotlin` flight, so it mints the same counted
    // pending-continuation ctx and Task handle rows 9 to 9l watch on the reverse bridge, plus
    // whatever the forward half mints to carry a `String` payload across. The C# delegate itself is
    // released by a Kotlin cleaner (the ADR-084 rule) rather than when the call returns, so every
    // row here depends on `Settle`'s cleaner rounds.
    //
    // WHAT THESE ROWS CANNOT SEE. `nuget_live_handles` counts Kotlin handles only, and the C#
    // context table (`NugetThunks._ctxTable`) has no observable: a C# key the cleaner never
    // released is silent here. `CollectabilityTests` carries that half, through a `WeakReference`
    // on an object the lambda captured.
    // ------------------------------------------------------------------------------------------

    // Row 17a. The happy path with a lambda that really suspends, three flights per crossing, so
    // the completion callback always arrives from the thread pool after Begin returned.
    [Fact]
    public async Task SuspendLambdaParameter_SuspendingLambda_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var chef = new Treatprep.TreatChef("Oreo");
            string plated = await chef.PlateAsync(3, async (treat, token) =>
            {
                await Task.Delay(1, token);
                return $"tuna#{treat}";
            });
            Assert.Equal("tuna#1,tuna#2,tuna#3", plated);
        });
    }

    // Row 17b. The TIGHT LOOP row, the forward mirror of Row 9a: `Task.FromResult`, thousands of
    // flights inside one Kotlin coroutine. The completion can land before the Begin thunk has
    // returned, which releases the ctx on a path Row 17a's delayed task almost never takes, and at
    // this count a per-flight leak is a five-figure delta. The sum is asserted so a run in which
    // the lambda was not really invoked that many times cannot pass as a clean one.
    [Fact]
    public async Task SuspendLambdaParameter_AlreadyCompletedTask_TightLoop_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var chef = new Treatprep.TreatChef("Mylo");
                Assert.Equal(7 * 2_000, await chef.WeighAsync(2_000, (_, _) => Task.FromResult(7)));
            },
            iterations: 5);
    }

    // Row 17c. The SYNCHRONOUS THROW: the lambda throws before any Task exists, so Begin reports
    // through `errOut` and no task handle was ever minted, while `awaitForKotlin` has to release a
    // ctx whose completion callback will never fire. Driven through the host that CATCHES in
    // Kotlin, so the outer call completes normally and only the invocation's own cleanup is in
    // play.
    [Fact]
    public async Task SuspendLambdaParameter_SynchronousThrow_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var chef = new Treatprep.TreatChef("Oreo");
            string report = await chef.RecoverAsync(
                (_, _) => throw new InvalidOperationException("the tuna tin is empty"));
            Assert.StartsWith("recovered ", report);
            Assert.Contains("the tuna tin is empty", report);
        });
    }

    // Row 17d. The FAULTED TASK, through the host that lets it PROPAGATE: the task handle is freed
    // in End's `finally` or not at all, and the outer suspend call then fails, so the ADR-128/130
    // error envelope crosses on the same call. The first invocation succeeds on purpose: its
    // `String` result is minted and must be released even though the call as a whole throws.
    [Fact]
    public async Task SuspendLambdaParameter_FaultedTask_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var chef = new Treatprep.TreatChef("Oreo");
            Exception ex = await Assert.ThrowsAnyAsync<Exception>(() => chef.PlateAsync(
                3,
                (treat, _) => treat == 1
                    ? Task.FromResult("plate1")
                    : Task.FromException<string>(new InvalidOperationException("Mylo sat on plate two"))));
            Assert.Contains("Mylo sat on plate two", ex.ToString());
        });
    }

    // Row 17e. The CANCEL row: the outer token is cancelled while the C# Task is pending, and the
    // lambda IGNORES its token. Kotlin's wait ends on its own cancellation and End is never
    // called, but the ctx is released only by the completion callback (ADR-210, "Lifetime"), so
    // one counted handle stays held until the lambda's Task really completes. That is a bounded
    // hold, not a leak: the crossing therefore awaits the lambda's own Task before it returns, and
    // only then may the baseline be asserted. A channel that drops the ctx on the cancelled path,
    // or one that never frees the task handle of an abandoned flight, shows up here and nowhere in
    // rows 17a to 17d.
    [Fact]
    public async Task SuspendLambdaParameter_OuterTokenCancelledWhilePending_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var chef = new Treatprep.TreatChef("Mylo");
                using var cts = new CancellationTokenSource();
                var started = new TaskCompletionSource<Task<string>>(TaskCreationOptions.RunContinuationsAsynchronously);
                var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

                async Task<string> Dawdle()
                {
                    await release.Task;   // deliberately not the token: Mylo finishes when Mylo finishes
                    return "late";
                }

                Task<string> plating = chef.PlateAsync(
                    1,
                    (_, _) =>
                    {
                        Task<string> dawdling = Dawdle();
                        started.TrySetResult(dawdling);
                        return dawdling;
                    },
                    cts.Token);

                Task<string> pending = await started.Task.WaitAsync(TimeSpan.FromSeconds(30));
                cts.Cancel();
                await Assert.ThrowsAnyAsync<OperationCanceledException>(() => plating);
                Assert.False(pending.IsCompleted, "the lambda finished before the cancel, so nothing was abandoned");

                release.SetResult();
                Assert.Equal("late", await pending);
            },
            iterations: 5);
    }

    // Row 17f. The STORED lambda, through the constructor host: the delegate outlives the call
    // that supplied it, is invoked by a later suspend member, and is released by the cleaner only
    // once the Kotlin object that stored it is unreachable, which is after this wrapper's Dispose.
    // A holder that is never collected, or a key released twice, moves the count here.
    [Fact]
    public async Task SuspendLambdaParameter_StoredByAConstructor_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var timer = new Treatprep.TreatTimer(async (treat, token) =>
            {
                await Task.Delay(1, token);
                return $"ding{treat}";
            });
            Assert.Equal("ding1,ding2", await timer.RingAsync(2));
            Assert.Equal("ding1", await timer.RingAsync(1));
        });
    }

    // ------------------------------------------------------------------------------------------
    // Rows 18a to 18f. ADR-210 slice (b): a `Flow<T>` PARAMETER (`IAsyncEnumerable<T>` in C#).
    //
    // Every pull is an `awaitForKotlin` flight (a counted ctx plus a Task handle, as in rows 17),
    // and each Kotlin `collect` additionally holds one enumeration handle from `GetAsyncEnumerator`
    // until the enumerator is disposed. That disposal has four ways to happen (completion, early
    // abort, a throw, cancellation) and each row below ends the collection a different way. The C#
    // source itself is released by a Kotlin cleaner, so every row depends on `Settle`.
    //
    // As for rows 17: the C# context table has no observable, so a source the cleaner never
    // released is silent here and `CollectabilityTests` carries that half.
    // ------------------------------------------------------------------------------------------

    // A source that never suspends: every MoveNextAsync is an already completed ValueTask.
    private sealed class SteadyDrops(int count) : IAsyncEnumerable<int>
    {
        public IAsyncEnumerator<int> GetAsyncEnumerator(CancellationToken cancellationToken = default) =>
            new Enumerator(count);

        private sealed class Enumerator(int count) : IAsyncEnumerator<int>
        {
            private int _pulled;
            public int Current => 7;
            public ValueTask<bool> MoveNextAsync() => new(++_pulled <= count);
            public ValueTask DisposeAsync() => default;
        }
    }

    // Really suspends before every element; `closed` completes when the iterator's `finally` ran,
    // which is the iterator's REAL completion and what a row has to await before it measures.
    private static async IAsyncEnumerable<int> Drip(
        int count, TaskCompletionSource closed, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            for (int drop = 1; drop <= count; drop++)
            {
                await Task.Delay(1, token);
                yield return drop;
            }
        }
        finally
        {
            closed.TrySetResult();
        }
    }

    private static async IAsyncEnumerable<string> Sips(
        [EnumeratorCancellation] CancellationToken token = default)
    {
        foreach (string sip in new[] { "sip", "lap", "slurp" })
        {
            await Task.Delay(1, token);
            yield return sip;
        }
    }

    private static async IAsyncEnumerable<int> DripThenSplutter(
        TaskCompletionSource closed, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            await Task.Delay(1, token);
            yield return 1;
            await Task.Delay(1, token);
            throw new InvalidOperationException("the fountain ran dry");
        }
        finally
        {
            closed.TrySetResult();
        }
    }

    // One element, then a pull that IGNORES the token it was given and waits on `release` instead.
    private static async IAsyncEnumerable<int> DripThenDawdle(
        TaskCompletionSource parked, Task release, TaskCompletionSource closed)
    {
        try
        {
            yield return 1;
            parked.TrySetResult();
            await release;   // deliberately not the token: Mylo finishes when Mylo finishes
            yield return 2;
        }
        finally
        {
            closed.TrySetResult();
        }
    }

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private static readonly TimeSpan FlowPatience = TimeSpan.FromSeconds(30);

    // Row 18a. The FULL COLLECT, one row per element kind: `Int` crosses by value, `String` is
    // minted at `Current` once per element and has to be released once per element.
    [Fact]
    public async Task FlowParameter_FullCollect_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var fountain = new Trickle.WaterFountain("Oreo");
            TaskCompletionSource closed = NewSignal();
            Assert.Equal(10, await fountain.TotalDropsAsync(Drip(4, closed)));
            await closed.Task.WaitAsync(FlowPatience);
            Assert.Equal("sip|lap|slurp", await fountain.DescribeAsync(Sips()));
        });
    }

    // Row 18b. The EARLY ABORT: Kotlin takes two of five, so the enumeration handle is released
    // by a dispose that finds the iterator parked at a `yield` rather than by a final pull that
    // said no. The abort also ends Kotlin's side with its own internal exception, the path on
    // which a pending-flight ctx or the enumeration handle is easiest to drop.
    [Fact]
    public async Task FlowParameter_EarlyAbort_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var fountain = new Trickle.WaterFountain("Oreo");
            TaskCompletionSource closed = NewSignal();
            Assert.Equal("1,2", await fountain.FirstTwoAsync(Drip(5, closed)));
            await closed.Task.WaitAsync(FlowPatience);
        });
    }

    // Row 18c. The iterator THROWS MID-STREAM, both ways out: caught in Kotlin (the outer call
    // completes normally) and propagated (the ADR-128/130 error envelope crosses on the same call).
    // The faulted pull never reaches a `Current`, and the enumerator still has to be disposed.
    [Fact]
    public async Task FlowParameter_IteratorThrowsMidStream_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var fountain = new Trickle.WaterFountain("Mylo");

            TaskCompletionSource caught = NewSignal();
            string report = await fountain.RecoverAsync(DripThenSplutter(caught));
            Assert.StartsWith("recovered after 1 ", report);
            await caught.Task.WaitAsync(FlowPatience);

            TaskCompletionSource escaped = NewSignal();
            Exception ex = await Assert.ThrowsAnyAsync<Exception>(
                () => fountain.TotalDropsAsync(DripThenSplutter(escaped)));
            Assert.Contains("the fountain ran dry", ex.ToString());
            await escaped.Task.WaitAsync(FlowPatience);
        });
    }

    // Row 18d. The CANCEL row: the outer token is cancelled while a pull is pending, and the
    // iterator IGNORES its token. Kotlin's wait ends on its own cancellation, but the flight's ctx
    // is released only by the completion callback, and the enumerator can only be disposed once
    // that pending pull has finished (ADR-210: queue cancel, await pending, then DisposeAsync). So
    // handles stay held until the iterator really completes: a bounded hold, not a leak. The
    // crossing therefore releases the iterator and awaits its `finally` before it returns, and
    // only then may the baseline be asserted. Even then the pull's ctx can still be held: its
    // completion callback is a thread-pool item queued as the `finally` runs, which is why
    // `AssertNoLeakAsync` settles without parking a worker (`SettleAsync`).
    [Fact]
    public async Task FlowParameter_OuterTokenCancelledMidStep_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var fountain = new Trickle.WaterFountain("Mylo");
                using var cts = new CancellationTokenSource();
                TaskCompletionSource parked = NewSignal();
                TaskCompletionSource release = NewSignal();
                TaskCompletionSource closed = NewSignal();

                Task<int> counting = fountain.TotalDropsAsync(
                    DripThenDawdle(parked, release.Task, closed), cts.Token);

                await parked.Task.WaitAsync(FlowPatience);
                cts.Cancel();
                await Assert.ThrowsAnyAsync<OperationCanceledException>(() => counting);
                Assert.False(closed.Task.IsCompleted, "the iterator finished before the cancel, so nothing was abandoned");

                release.SetResult();
                await closed.Task.WaitAsync(FlowPatience);
            },
            iterations: 5);
    }

    // Row 18e. The TIGHT LOOP row, the mirror of rows 9a and 17b: a source with no suspension at
    // all, thousands of pulls inside one Kotlin collect, each of which can complete before the
    // thunk that started it has returned. The sum is asserted so a short run cannot pass as clean.
    [Fact]
    public async Task FlowParameter_SourceThatNeverSuspends_TightLoop_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var fountain = new Trickle.WaterFountain("Oreo");
                Assert.Equal(7 * 2_000, await fountain.TotalDropsAsync(new SteadyDrops(2_000)));
            },
            iterations: 5);
    }

    // Row 18f. The STORED flow, through the constructor host: the source outlives the call that
    // supplied it, is enumerated twice by a later suspend member (two enumeration handles, one
    // after the other), and is released by the cleaner only after this wrapper's Dispose.
    [Fact]
    public async Task FlowParameter_StoredByAConstructor_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            TaskCompletionSource closed = NewSignal();
            using var dripper = new Trickle.Dripper(Drip(3, closed));
            Assert.Equal("1,2,3", await dripper.DrainAsync());
            await closed.Task.WaitAsync(FlowPatience);
            Assert.Equal("1,2,3", await dripper.DrainAsync());
        });
    }

    // Row 18g. The COLLECTOR THROWS: a Kotlin exception raised inside `collect { }` on the second
    // element. The fourth way a collection ends, and the only one where the failure starts on the
    // Kotlin side with no pull pending: the enumeration handle has to be released by a dispose
    // that runs while Kotlin's own exception is unwinding, and the ADR-128/130 error envelope then
    // crosses on the same call. The iterator's `finally` is awaited so the row measures after the
    // enumerator is really gone, not before.
    [Fact]
    public async Task FlowParameter_CollectorThrows_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var fountain = new Trickle.WaterFountain("Oreo");
            TaskCompletionSource closed = NewSignal();
            KotlinInvalidOperationException ex = await Assert.ThrowsAsync<KotlinInvalidOperationException>(
                () => fountain.ChokeOnSecondAsync(Drip(5, closed)));
            Assert.Contains("Oreo choked on drop 2", ex.Message);
            await closed.Task.WaitAsync(FlowPatience);
        });
    }

    // ADR-124 on a generic sealed arm (ADR-199): the arm owns the scope its flow members collect
    // on, and each collected element is one owned crossing. A flow the generic base declares, one
    // whose element is the arm's type parameter (a boxed `String` per emission), a StateFlow read
    // and write, the method half, and the phantom arm's StateFlow and suspend member must each
    // come back to baseline once the arm is drained.
    [Fact]
    public async Task GenericSealedArm_FlowMembers_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using (var oreo = new Outcomes.Dish.Hot<string>("tuna"))
            {
                int courses = 0;
                await foreach (int course in oreo.Served) courses += course;
                Assert.Equal(6, courses);
                var portions = new List<string>();
                await foreach (string portion in oreo.Portions) portions.Add(portion);
                Assert.Equal(new[] { "tuna", "tuna" }, portions);
                oreo.Heat.Value = 45;
                Assert.Equal(45, oreo.Heat.Value);
                int wafts = 0;
                await foreach (int waft in oreo.Wafts(3)) wafts += waft;
                Assert.Equal(3, wafts);
            }
            await using (var mylo = new Outcomes.Dish.Bare<string>(4))
            {
                Assert.Equal(4, mylo.Drips.Value);
                Assert.Equal(4, await mylo.WipeAsync());
            }
        });
    }

    private sealed class ProbeDiner : Outcomes.IDiner
    {
        public int Courses { get; private set; }
        public void OnPlated(int course) => Courses += course;
        public void Dispose() { }
    }

    // ADR-116's per-call lambda route on a generic sealed arm (ADR-199): one `Char` crossing per
    // invocation, nothing retained once the call returns.
    [Fact]
    public async Task GenericSealedArm_PerCallLambda_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var oreo = new Outcomes.Dish.Hot<string>("tuna");
            int letters = 0;
            oreo.Spell(_ => letters++);
            Assert.Equal(4, letters);
        });
    }

    // The stored-callback pair on the same arm: subscribe, fire, unsubscribe. The Kotlin bridge
    // lambda the subscription holds must be released by `Dispose()`, and a fire after it must not
    // reach the listener.
    [Fact]
    public async Task GenericSealedArm_StoredCallbackPair_SubscribeFireUnsubscribe_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var oreo = new Outcomes.Dish.Hot<string>("tuna");
            int rung = 0;
            IDisposable bell = oreo.AddBell(course => rung += course);
            Assert.Equal(1, oreo.Plate(3));
            bell.Dispose();
            Assert.Equal(0, oreo.Plate(5));
            Assert.Equal(3, rung);
        });
    }

    // And the interface-bridge pair: the bridge object Kotlin holds for the C# listener goes with
    // the subscription.
    [Fact]
    public async Task GenericSealedArm_InterfaceBridgePair_SubscribeFireUnsubscribe_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using var oreo = new Outcomes.Dish.Hot<int>(7);
            var mylo = new ProbeDiner();
            IDisposable seat = oreo.AddDiner(mylo);
            Assert.Equal(1, oreo.Plate(3));
            seat.Dispose();
            Assert.Equal(0, oreo.Plate(5));
            Assert.Equal(3, mylo.Courses);
        });
    }

    // ADR-216
    // A member's own `T` inside an exported generic class. A returned `Basket<T>` is owned by the
    // caller and released by its `using`; a passed one is borrowed (the export reads it, the
    // caller still owns it). `DoubleBasket(1)` returns the outer basket only; its inner basket is
    // a fresh wrapper per `Item` read, released by its own `using`.
    // Ledger per iteration: treats +1/-1, cat +1/-1, Unpack(treats) nothing, Unpack(cat) back
    // +1/-1, brushed +1/-1, doubled +1/-1, inner +1/-1, unpacked +1/-1. Net zero.
    [Fact]
    public void MemberGeneric_BoxedTypeParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var groomer = new Membergeneric.Groomer();
            using var oreo = new Membergeneric.Tabby("Oreo", 3);
            for (int i = 0; i < 5; i++)
            {
                using Membergeneric.Basket<int> treats = groomer.Basket(3);
                Assert.Equal(3, groomer.Unpack(treats));
                using Membergeneric.Basket<Membergeneric.Tabby> cat = groomer.Basket(oreo);
                using (Membergeneric.Tabby back = groomer.Unpack(cat)) Assert.Equal("Oreo", back.Name);
                using (Membergeneric.Tabby brushed = groomer.BrushBasket(cat)) Assert.Equal(3, brushed.Tricks);
                using Membergeneric.Basket<Membergeneric.Basket<int>> doubled = groomer.DoubleBasket(1);
                using (Membergeneric.Basket<int> inner = doubled.Item) Assert.Equal(1, inner.Item);
                using Membergeneric.Basket<int> unpacked = groomer.NestBasket(doubled);
                Assert.Equal(1, unpacked.Item);
            }
        });
    }

    // ADR-216 (second amendment): an open nested instantiation read through a getter before this
    // process held the inner one. The getter's fill stores a factory and mints no handle; the
    // inner basket is a fresh wrapper per `Item` read, released by its own `using`.
    // Ledger per iteration: nested +1/-1, inner +1/-1. Net zero.
    [Fact]
    public void OpenSlot_NestedThroughGetter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var satchel = new Membergeneric.Satchel<short>(7);
            for (int i = 0; i < 5; i++)
            {
                using Membergeneric.Basket<Membergeneric.Basket<short>> nested = satchel.Nested;
                using (Membergeneric.Basket<short> inner = nested.Item) Assert.Equal((short)7, inner.Item);
            }
        });
    }

    // A member's own `T` inside a `Result<List<T>>` payload. Each returned `Tabby` element is a
    // fresh wrapper the caller disposes; the list handle is released by the read. `TrySpill`
    // takes the failure path: no list is minted and the error handle is released when the
    // exception is built.
    // Ledger per iteration: gathered elements +2/-2, list +1/-1, TrySpill error +1/-1. Net zero.
    [Fact]
    public void MemberGeneric_ResultListOfT_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var groomer = new Membergeneric.Groomer();
            using var oreo = new Membergeneric.Tabby("Oreo", 3);
            using var mylo = new Membergeneric.Tabby("Mylo", 5);
            for (int i = 0; i < 5; i++)
            {
                foreach (Membergeneric.Tabby cat in groomer.Gather(new[] { oreo, mylo }))
                {
                    using (cat) Assert.NotEmpty(cat.Name);
                }
                Assert.False(groomer.TrySpill(new[] { oreo }, out _, out Exception? failure));
                Assert.Contains("Mylo spilled 1", failure!.Message);
            }
        });
    }

    // ADR-213
    // A top-level `fun <T>` on the plan: a `WrapInBox<int>` mints a box handle the `using` releases,
    // and `UnwrapBox` borrows it and hands the primitive back through a disposed result box.
    // Ledger per iteration: box +1/-1, argument and result boxes +1/-1 each. Net zero.
    [Fact]
    public void TopLevelGenericMethod_BoxedBox_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            for (int i = 0; i < 5; i++)
            {
                using TestLibrary.Cat.Box<int> box = TestLibrary.Cat.Helpers.WrapInBox(i);
                Assert.Equal(i, TestLibrary.Cat.Helpers.UnwrapBox(box));
                Assert.Equal("Oreo", TestLibrary.Cat.Helpers.Identity("Oreo"));
            }
        });
    }

    // ADR-214
    // A `suspend fun <T>` on the suspend route, in a tight loop. Each primitive or string `T` argument
    // is an owned `Wrap<T>` box the C# `finally` disposes once the `_async` extern returns (Kotlin
    // read it in the prelude, before `launchForCSharp`); each result is one handle `FromHandle<T>`
    // consumes. The wrapper argument is borrowed across the suspension, as ADR-208's `Box<T>` is.
    // The pre-cancelled call proves the owned box goes when the job never completes, since the
    // `finally` runs after the extern, not on completion.
    // Ledger per iteration: FetchLater(i) box +1/-1, result +1/-1; FetchLater("Oreo") the same;
    // FetchLater(toy) result wrapper +1/-1 (toy outside the loop); HoldLater("late") box +1/-1.
    // Net zero.
    [Fact]
    public async Task TopLevelSuspendGeneric_BoxedTypeParameter_ReturnsToBaseline()
    {
        using var toy = new Toy("Mylo's Milo Mouse", "Brown");
        int i = 0;
        await AssertNoLeakAsync(async () =>
        {
            i++;
            Assert.Equal(i, await Suspendgeneric.SuspendGenericSample.FetchLaterAsync(i));
            Assert.Equal("Oreo", await Suspendgeneric.SuspendGenericSample.FetchLaterAsync("Oreo"));
            using Toy back = await Suspendgeneric.SuspendGenericSample.FetchLaterAsync(toy);
            Assert.Equal("Mylo's Milo Mouse", back.Name);
            using var cts = new CancellationTokenSource();
            cts.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(
                () => Suspendgeneric.SuspendGenericSample.HoldLaterAsync("late", cts.Token));
        }, iterations: 200);
    }

    // ADR-215
    // Lane A, payload: a member's own `T` handed Kotlin to C# inside a lambda. Kotlin retains one
    // box per invocation, and C#'s `FromHandle<T>` consumes it: a builtin is read and disposed, a
    // wrapper is adopted and released by the lambda's `using`. A null `T` mints nothing.
    // Ledger per iteration: dealer, oreo, mylo +1/-1 each; Each over ints and strings: one box per
    // element +1/-1; Each over wrappers: one wrapper per element +1/-1 (the lambda's `using`);
    // Each over `string?` with a null: one box per non-null element +1/-1; Serving<Tabby>.Each:
    // the serving +1/-1 and one wrapper per invocation +1/-1. Net zero.
    [Fact]
    public void MemberGeneric_LambdaPayload_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var dealer = new Membergeneric.Dealer();
            using var oreo = new Membergeneric.Tabby("Oreo", 3);
            using var mylo = new Membergeneric.Tabby("Mylo", 5);
            int seen = 0;
            dealer.Each(new[] { 4, 6 }, age => seen += age);
            dealer.Each(new[] { "Oreo", "Mylo" }, name => seen += name.Length);
            dealer.Each(new[] { oreo, mylo }, cat =>
            {
                using (cat) seen += cat.Tricks;
            });
            dealer.Each(new string?[] { "Oreo", null }, seat => seen += seat?.Length ?? 0);
            dealer.Peek<string>(null, seat => seen += seat?.Length ?? 0);
            using var forOreo = new Membergeneric.Serving<Membergeneric.Tabby>(oreo);
            forOreo.Each(cat =>
            {
                using (cat) seen += cat.Tricks;
            });
            Assert.Equal(4 + 6 + 4 + 4 + 3 + 5 + 4 + 3 + 3, seen);
        });
    }

    // Lane A, result: a `T` the C# lambda hands back to Kotlin. `WrapOwned<T>` always gives Kotlin
    // an owned box: a builtin mints one, a borrowed wrapper (Mylo, still owned by this row) is
    // retained through the generated `<lib>__retain_handle`, and a fresh wrapper created inside the
    // lambda and dropped on return is retained the same way before its finalizer can run. Kotlin
    // disposes exactly one box per result.
    // Ledger per iteration: dealer, oreo, mylo +1/-1 each; Map(4): argument box +1/-1, payload box
    // +1/-1, result box +1/-1, returned box +1/-1; Map(oreo, borrowed mylo): payload wrapper +1/-1
    // (disposed in the lambda), retained box +1/-1 (Kotlin), returned wrapper +1/-1 (`using`);
    // Rename(oreo, fresh): payload +1/-1, the fresh Tabby's own handle +1/-1 (its finalizer, or
    // Kotlin's GC once the cast object is unreachable), retained box +1/-1; Map<string?>(null):
    // nothing minted for either null; Fill<Tabby>(null, fresh): the fresh Tabby +1/-1, its box
    // +1/-1, the returned wrapper +1/-1. Net zero.
    [Fact]
    public void MemberGeneric_LambdaResult_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var dealer = new Membergeneric.Dealer();
            using var oreo = new Membergeneric.Tabby("Oreo", 3);
            using var mylo = new Membergeneric.Tabby("Mylo", 5);
            Assert.Equal(8, dealer.Map(4, age => age * 2));
            Assert.Equal("Oreo!", dealer.Map("Oreo", name => name + "!"));
            using (Membergeneric.Tabby swapped = dealer.Map(oreo, cat =>
            {
                cat.Dispose();
                return mylo;
            }))
            {
                Assert.Equal("Mylo", swapped.Name);
            }
            Assert.Equal("Mylo", dealer.Rename(oreo, cat =>
            {
                cat.Dispose();
                return new Membergeneric.Tabby("Mylo", 5);
            }));
            Assert.Null(dealer.Map<string?>(null, seat => seat));
            using (Membergeneric.Tabby fetched = dealer.Fill<Membergeneric.Tabby>(null, () => new Membergeneric.Tabby("Mylo", 5)))
            {
                Assert.Equal("Mylo", fetched.Name);
            }
            Assert.Equal(5, mylo.Tricks);
        });
    }

    // ADR-218
    // A type-parameter setter mints one box per non-null assignment and disposes it in its
    // `finally`; a borrowed wrapper (and every element of a borrowed collection) is the
    // consumer's, so only the consumer's `using` releases it. Ledger per iteration: pen, oreo,
    // mylo, names, tabby, cradle, sash, locket +1/-1 each; Current = i: box +1/-1; Current =
    // "Mylo": box +1/-1; Maybe = null: nothing minted; Current = oreo: borrowed, its returned
    // wrapper +1/-1 (`using`); Items/Tags over cats: list and map handle +1/-1 each, elements
    // borrowed; Sash's trampolined setter: box +1/-1; Locket through IKeepsake<Cat>: borrowed.
    // Net zero, baseline equality only.
    [Fact]
    public void GenericInstance_SetterBoxes_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 9);
            using var mylo = new Cat("Mylo", 9);

            using var pen = new Playroom.Playpen<int>(9);
            pen.Current = 7;
            pen.Maybe = 4;
            pen.Items = new[] { 1, 2 };
            pen.Bag = new List<int> { 3 };
            pen.Unique = new HashSet<int> { 4 };
            pen.Tags = new Dictionary<string, int> { ["oreo"] = 5 };
            Assert.Equal(7, pen.Current);

            using var names = new Playroom.Playpen<string?>("Oreo");
            names.Current = "Mylo";
            names.Maybe = null;
            Assert.Equal("Mylo", names.Current);

            using var cats = new Playroom.Playpen<Cat>(mylo);
            cats.Current = oreo;
            cats.Maybe = mylo;
            cats.Items = new[] { oreo, mylo };
            cats.Tags = new Dictionary<string, Cat> { ["brown"] = mylo };
            using (Cat back = cats.Current)
            {
                Assert.Equal("Oreo", back.Name);
            }
            Assert.Equal("Mylo", mylo.Name);

            using var tabby = new Membergeneric.Tabby("Mylo", 5);
            using var cradle = new Playroom.Cradle<Membergeneric.Tabby>(tabby);
            cradle.Current = tabby;
            cradle.Pets = new[] { tabby };
            Assert.Equal("Mylo, Mylo", cradle.Names());

            using var sash = new Playroom.Sash<Rankings.Medal>(Rankings.Medal.Silver);
            sash.Current = Rankings.Medal.Gold;
            sash.RunnerUp = Rankings.Medal.Silver;
            sash.RunnerUp = null;
            Assert.Equal("GOLD#2 then nobody", sash.Describe());

            using var locket = new Playroom.Locket<Cat>(oreo);
            Playroom.IKeepsake<Cat> keepsake = locket;
            keepsake.Item = mylo;
            Assert.StartsWith("locket holds ", locket.Peek());
        });
    }

    // ADR-215 lane B
    // A type parameter as a Flow element Kotlin hands to C#. Each element is one boxed handle
    // `FromHandle<T>` consumes (a `Tabby` element is a fresh wrapper the loop `using`s); a null
    // element of an unconstrained `T` is no handle at all. The `Level` write is a `Wrap<T>` box C#
    // disposes after the setter returns, the member's own `T` argument the same. The endless
    // `Babble` is cancelled mid-stream, so the in-flight element and the producer go with it.
    // Ledger per iteration: Brook<Tabby> Updates 2 wrappers +2/-2, Level write box +1/-1, Level read
    // wrapper +1/-1; Brook<string?> Updates two nulls, zero; Gaps 2 boxes and a null; Babble
    // cancelled after 3; Creek Stream(oreo) box +1/-1 and wrapper +1/-1; Hush one box and a null;
    // Pride Join(mylo) box +1/-1, 2 wrappers +2/-2; Dish.Hot<string?> null Portions, zero. The
    // owners go inside the body and the two cats outlive the loop, so net zero.
    [Fact]
    public async Task GenericClass_FlowElements_ReturnToBaseline()
    {
        using var oreo = new Membergeneric.Tabby("Oreo", 3);
        using var mylo = new Membergeneric.Tabby("Mylo", 1);
        await AssertNoLeakAsync(async () =>
        {
            await using (var brook = new Membergeneric.Brook<Membergeneric.Tabby>(oreo))
            {
                await foreach (Membergeneric.Tabby cat in brook.Updates)
                {
                    using (cat) Assert.Equal("Oreo", cat.Name);
                }
                brook.Level.Value = mylo;
                using (Membergeneric.Tabby current = brook.Level.Value) Assert.Equal("Mylo", current.Name);
            }
            await using (var hush = new Membergeneric.Brook<string?>(null))
            {
                await foreach (string? gap in hush.Updates) Assert.Null(gap);
            }
            await using (var brook = new Membergeneric.Brook<string>("splash"))
            {
                int gaps = 0;
                await foreach (string? gap in brook.Gaps) if (gap is null) gaps++;
                Assert.Equal(1, gaps);
                using var cts = new CancellationTokenSource();
                int splashes = 0;
                // A cancelled KotlinFlow ends the enumeration cleanly, as every shipped flow does.
                await foreach (string splash in brook.Babble().WithCancellation(cts.Token))
                {
                    if (++splashes == 3) cts.Cancel();
                }
            }
            using (var creek = new Membergeneric.Creek())
            {
                await foreach (Membergeneric.Tabby cat in creek.Stream(oreo))
                {
                    using (cat) Assert.Equal("Oreo", cat.Name);
                }
                await foreach (string? name in creek.Hush("Mylo")) Assert.True(name is null or "Mylo");
            }
            await using (var pride = new Membergeneric.Pride<Membergeneric.Tabby>(oreo))
            {
                await foreach (Membergeneric.Tabby cat in pride.Join(mylo))
                {
                    using (cat) Assert.NotNull(cat.Name);
                }
            }
            await using (var plate = new Outcomes.Dish.Hot<string?>(null))
            {
                await foreach (string? portion in plate.Portions) Assert.Null(portion);
            }
        });
    }

    // ADR-217
    // A trampolined `T : Enum<T>` per collection element. `CreateList`/`CreateSet`/`CreateMap`
    // mint one `Wrap<Medal>` box per passed element and dispose it in their loop; each returned
    // element is read through `FromHandle<T>` (or `FromHandle<T?>`), which must release its box,
    // since an enum is a value type and the caller has nothing to dispose. A null element crosses
    // as the null pointer and mints nothing. The trampoline adds no handle of its own.
    // Ledger per iteration: rosette +1, sash +1, every collection handle and element box +1/-1,
    // rosette and sash dispose -1 each. Net zero; a positive delta is an element box nobody freed.
    [Fact]
    public void EnumSelfBound_CollectionElements_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            for (int i = 0; i < 5; i++)
            {
                Assert.Equal(
                    new[] { Rankings.Medal.Gold, Rankings.Medal.Bronze },
                    Rankings.Judge.Sorted(new[] { Rankings.Medal.Bronze, Rankings.Medal.Gold }));
                Assert.Equal(
                    new Rankings.Medal?[] { Rankings.Medal.Silver, null, null },
                    Rankings.Judge.Pad(new Rankings.Medal?[] { Rankings.Medal.Silver, null }));
                Assert.Single(Rankings.Judge.Unique(
                    new HashSet<Rankings.Medal> { Rankings.Medal.Bronze, Rankings.Medal.Gold }));
                Assert.Equal(
                    4,
                    Rankings.Judge.Scores(
                        new Dictionary<Rankings.Medal, int> { [Rankings.Medal.Gold] = 2 })
                        [Rankings.Medal.Gold]);
                Assert.Single(Rankings.Judge.Heats(
                    new IReadOnlyList<Rankings.Medal>[] { new[] { Rankings.Medal.Silver } }));
                Assert.Equal(
                    new[] { Rankings.Medal.Gold, Rankings.Medal.Silver },
                    Rankings.Medals.Ordered(new[] { Rankings.Medal.Silver, Rankings.Medal.Gold }));
                using var oreo = new Rankings.Rosette<Rankings.Medal>(Rankings.Medal.Gold);
                Assert.Equal(2, oreo.Rank(new[] { Rankings.Medal.Silver }).Count);
                using var mylo = new Rankings.Sash<Rankings.Medal>(
                    new[] { Rankings.Medal.Bronze, Rankings.Medal.Silver });
                Assert.Equal(Rankings.Medal.Silver, mylo.Best);
                Assert.Equal(2, mylo.Ribbons.Count);
            }
        });
    }

    // ADR-219
    // A generic owner's suspend members on the suspend route, in a tight loop. Each primitive or
    // string `T` argument is an owned `Wrap<T>` box the C# `finally` disposes once the `_async`
    // extern returns; each `T` result is one handle `FromHandle<T>` consumes (a `Tabby` result is a
    // fresh wrapper the loop `using`s). The wrapper arguments are borrowed across the suspension.
    // The pre-cancelled calls prove nothing stays behind when the job never completes.
    // Ledger per iteration: Platter<int> PutAsync(i) box +1/-1, TakeAsync result +1/-1; Platter<Tabby>
    // TakeAsync wrapper +1/-1, StackAsync two wrappers +2/-2; SwapAsync("Oreo") box +1/-1, result
    // +1/-1; RateAsync(Gold) trampolined box +1/-1, result +1/-1; Banquet<Tabby> ReplaceAsync wrapper
    // +1/-1; Picnic<Tabby> OutshineAsync wrapper +1/-1; Buffet.Tureen<string> PourAsync box +1/-1,
    // result +1/-1; LingerAsync and SimmerAsync pre-cancelled, zero. The owners go inside the body
    // and the two cats outlive the loop, so net zero.
    [Fact]
    public async Task GenericOwner_SuspendMembers_ReturnToBaseline()
    {
        using var oreo = new Membergeneric.Tabby("Oreo", 3);
        using var mylo = new Membergeneric.Tabby("Mylo", 1);
        int i = 0;
        await AssertNoLeakAsync(async () =>
        {
            i++;
            await using (var ints = new Membergeneric.Platter<int>(0))
            {
                await ints.PutAsync(i);
                Assert.Equal(i, await ints.TakeAsync());
                Assert.Equal("Oreo", await ints.SwapAsync("Oreo"));
                Assert.Equal(Rankings.Medal.Gold, await ints.RateAsync(Rankings.Medal.Gold));
            }
            await using (var cats = new Membergeneric.Platter<Membergeneric.Tabby>(oreo))
            {
                using (Membergeneric.Tabby back = await cats.TakeAsync()) Assert.Equal("Oreo", back.Name);
                IReadOnlyList<Membergeneric.Tabby> stacked = await cats.StackAsync(new[] { mylo });
                foreach (Membergeneric.Tabby cat in stacked) cat.Dispose();
                using var cts = new CancellationTokenSource();
                cts.Cancel();
                await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cats.LingerAsync(cts.Token));
            }
            await using (var banquet = new Membergeneric.Banquet<Membergeneric.Tabby>(oreo))
            {
                using (Membergeneric.Tabby seated = await banquet.ReplaceAsync(mylo)) Assert.Equal("Mylo", seated.Name);
            }
            await using (var picnic = new Membergeneric.Picnic<Membergeneric.Tabby>(oreo))
            {
                using (Membergeneric.Tabby host = await picnic.OutshineAsync(mylo)) Assert.Equal("Oreo", host.Name);
            }
            await using (var tureen = new Membergeneric.Buffet.Tureen<string>("broth"))
            {
                Assert.Equal("gravy", await tureen.PourAsync("gravy"));
                using var cts = new CancellationTokenSource();
                cts.Cancel();
                await Assert.ThrowsAnyAsync<OperationCanceledException>(() => tureen.SimmerAsync(cts.Token));
            }
        }, iterations: 200);
    }

    // ADR-215 lane C
    // A `T` flowing INTO Kotlin asynchronously. Each `Flow<T>` element is read as an owned box
    // (`CurrentBoxed()`: a builtin mints one, a borrowed wrapper is retained through
    // `<lib>__retain_handle`), and each `suspend (T) -> T` result as an owned box (`EndBoxed()`).
    // Kotlin disposes exactly one box per element and per result. Half-way through, Kotlin stops
    // the source itself (`Sip` takes two of four), and the outer token cancels a source parked on
    // its own token, so the enumeration handle and the parked pull's flight are released on both
    // abort paths. Ledger per iteration: siphon, oreo, mylo +1/-1 each; Gulp over ints and over
    // `string?` with a null: one box per non-null element +1/-1, one returned box per non-null
    // element +1/-1; Sip over four wrappers: two retained boxes +1/-1, two returned wrappers +1/-1
    // (`Dispose`), the enumeration handle +1/-1; the cancelled Gulp: one retained box +1/-1, the
    // enumeration handle and the pending flight +1/-1; Brew(oreo, borrowed mylo): payload wrapper
    // +1/-1 (disposed in the lambda), retained box +1/-1, returned wrapper +1/-1; Brew(oreo, fresh):
    // payload +1/-1, the fresh Tabby +1/-1, its box +1/-1, the returned wrapper +1/-1;
    // Brew<string?>(null): nothing minted. Net zero.
    private static async IAsyncEnumerable<T> PourForKotlin<T>(
        T[] items, TaskCompletionSource closed, [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            foreach (T item in items)
            {
                await Task.Delay(1, token);
                yield return item;
            }
        }
        finally
        {
            closed.TrySetResult();
        }
    }

    private static async IAsyncEnumerable<T> PourOneThenWaitOnTheToken<T>(
        T first, TaskCompletionSource parked, TaskCompletionSource closed,
        [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            yield return first;
            parked.TrySetResult();
            await Task.Delay(Timeout.Infinite, token);
            yield return first;
        }
        finally
        {
            closed.TrySetResult();
        }
    }

    [Fact]
    public async Task MemberGeneric_FlowParameterAndSuspendResult_ReturnToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                using var siphon = new Membergeneric.Siphon();
                using var oreo = new Membergeneric.Tabby("Oreo", 3);
                using var mylo = new Membergeneric.Tabby("Mylo", 5);

                TaskCompletionSource drank = NewSignal();
                Assert.Equal(new[] { 3, 5 }, await siphon.GulpAsync(PourForKotlin(new[] { 3, 5 }, drank)));
                await drank.Task.WaitAsync(FlowPatience);

                TaskCompletionSource seated = NewSignal();
                Assert.Equal(
                    new string?[] { "Oreo", null },
                    await siphon.GulpAsync<string?>(PourForKotlin(new string?[] { "Oreo", null }, seated)));
                await seated.Task.WaitAsync(FlowPatience);

                // Half-way, Kotlin's side: two of four, then the source is disposed.
                TaskCompletionSource sipped = NewSignal();
                IReadOnlyList<Membergeneric.Tabby> two =
                    await siphon.SipAsync(PourForKotlin(new[] { oreo, mylo, oreo, mylo }, sipped), 2);
                foreach (Membergeneric.Tabby cat in two) cat.Dispose();
                await sipped.Task.WaitAsync(FlowPatience);

                // Half-way, C#'s side: the outer token lands while the source waits on its own.
                using (var cts = new CancellationTokenSource())
                {
                    TaskCompletionSource parked = NewSignal();
                    TaskCompletionSource closed = NewSignal();
                    Task<IReadOnlyList<Membergeneric.Tabby>> gulping =
                        siphon.GulpAsync(PourOneThenWaitOnTheToken(oreo, parked, closed), cts.Token);
                    await parked.Task.WaitAsync(FlowPatience);
                    cts.Cancel();
                    await Assert.ThrowsAnyAsync<OperationCanceledException>(() => gulping);
                    await closed.Task.WaitAsync(FlowPatience);
                }

                using (Membergeneric.Tabby swapped = await siphon.BrewAsync(oreo, async (cat, token) =>
                {
                    cat.Dispose();
                    await Task.Yield();
                    return mylo;
                }))
                {
                    Assert.Equal("Mylo", swapped.Name);
                }
                using (Membergeneric.Tabby fresh = await siphon.BrewAsync(oreo, async (cat, token) =>
                {
                    cat.Dispose();
                    await Task.Yield();
                    return new Membergeneric.Tabby("Mylo", 5);
                }))
                {
                    Assert.Equal("Mylo", fresh.Name);
                }
                Assert.Null(await siphon.BrewAsync<string?>(null, (seat, _) => Task.FromResult(seat)));
                Assert.Equal(5, mylo.Tricks);
            },
            iterations: 5);
    }

    // ADR-220
    // A generic value class crosses as its substituted underlying, so a closed `Sticker<Cat>`
    // borrows the cat's handle at a parameter and mints one fresh wrapper at a return, and a
    // member's own `T` inside `Sticker<T>` rides the bare-`T` wire (one box in, one box out, both
    // released). The struct itself owns nothing. Ledger per iteration: mylo +1, the stuck and
    // adopted cats +1 each, the book +1, every box +1/-1, four disposes -1 each. Net zero.
    [Fact]
    public void GenericValueClass_Instantiations_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var mylo = new Cat("Mylo", 9);
            Assert.Equal("Mylo", TestLibrary.Stickers.Stickers.NameOf(
                new TestLibrary.Stickers.Sticker<Cat>(mylo)));
            using (Cat stuck = TestLibrary.Stickers.Stickers.Stick(mylo).Value)
                Assert.Equal("Mylo", stuck.Name);
            Assert.Equal("Oreo", TestLibrary.Stickers.Stickers.Peel(
                new TestLibrary.Stickers.Sticker<string>("Oreo")));
            using (Cat adopted = TestLibrary.Stickers.Stickers.Adopt("Oreo")!.Value.Value)
                Assert.Equal("Oreo", adopted.Name);
            using var book = new TestLibrary.Stickers.StickerBook(
                new TestLibrary.Stickers.Sticker<string>("Oreo's"),
                new TestLibrary.Stickers.Sticker<int>(1));
            Assert.Equal(1, book.Pages.Value);
            Assert.Equal(3, book.Peel(book.Stick(3)));
        });
    }

    // ADR-229
    // The static suspend route: an `object`, a default-named and a named companion, suspend
    // extensions, an enum body and companion, and value-class members, each on an ad-hoc root
    // scope per call. Every completed call releases its job handle; a generic `T` argument is an
    // owned `Wrap<T>` box the C# `finally` disposes once the extern returns, and each `T` result is
    // one handle `FromHandle<T>` consumes. Class arguments and receivers are borrowed across the
    // suspension. Ledger per iteration: Siesta MinutesFor job +1/-1; Echo(i) box +1/-1, result
    // +1/-1, job +1/-1; Echo(oreo) result wrapper +1/-1, job +1/-1; Wake(oreo) job +1/-1;
    // Settle job +1/-1; Swing.Hang wrapper +1/-1, job +1/-1; Swing.Carry("Mylo") box +1/-1, result
    // +1/-1; Quilt.Knit wrapper +1/-1; Rock, Occupant (null and not), Yawn, NapMinutes, Stirring jobs +1/-1
    // each;
    // Tuck(oreo) wrapper +1/-1; NapSpot Claim, Next, Stash("treat") box/result +1/-1, Favourite;
    // NapLength Doubled, Label("Mylo") box +1/-1; NapTag Read job +1/-1. oreo outlives the loop.
    // Net zero.
    [Fact]
    public async Task StaticSuspend_EveryOwner_Completion_ReturnsToBaseline()
    {
        await using var oreo = new Swing("Oreo");
        int i = 0;
        await AssertNoLeakAsync(async () =>
        {
            i++;
            Assert.Equal(40, await Siesta.MinutesForAsync("Mylo"));
            Assert.Equal(i, await Siesta.EchoAsync(i));
            await using (Swing same = await Siesta.EchoAsync(oreo)) Assert.Equal("Oreo", same.Sleeper);
            Assert.Equal("Oreo is up", await Siesta.WakeAsync(oreo));
            await Siesta.SettleAsync();
            await using (Swing hung = await Swing.HangAsync("Mylo")) Assert.Equal("Mylo", hung.Sleeper);
            Assert.Equal("Mylo", await Swing.CarryAsync("Mylo"));
            Assert.Equal("swayed 2 times", await Swing.SwayAsync(2));
            using (Quilt quilt = await Quilt.KnitAsync("black")) Assert.Equal("black", quilt.Colour);
            Assert.Equal("Oreo rocked 1 times", await oreo.RockAsync(1));
            Assert.Equal("Oreo", await oreo.OccupantAsync());
            Swing? none = null;
            Assert.Equal("empty", await none.OccupantAsync());
            Assert.Equal("Mylo yawns", await "Mylo".YawnAsync());
            Assert.Equal("Oreo naps for 3 minutes", await 3.NapMinutesAsync("Oreo"));
            Assert.Equal("Mylo is stirring", await true.StirringAsync());
            await using (Swing tucked = await oreo.TuckAsync(oreo)) Assert.Equal("Oreo", tucked.Sleeper);
            Assert.Equal("Oreo claims a cardboard box", await NapSpot.Box.ClaimAsync("Oreo"));
            Assert.Equal(NapSpot.Laundry, await NapSpot.Windowsill.NextAsync());
            Assert.Equal("treat", await NapSpot.Box.StashAsync("treat"));
            Assert.Equal(NapSpot.Box, await NapSpotExtensions.FavouriteAsync());
            Assert.Equal(new NapLength(20), await new NapLength(10).DoubledAsync());
            Assert.Equal("Mylo: 10", await new NapLength(10).LabelAsync("Mylo"));
            Assert.Equal("tag: Oreo", await new NapTag(oreo).ReadAsync());
        }, iterations: 200);
    }

    // ADR-229
    // A fault on every static suspend owner. The job handle is released on the faulted arm and the
    // Kotlin throwable crosses as one ADR-107 envelope the C# exception owns and disposes.
    // Ledger per iteration: each of Alarm, Snap, Fray, Evict, Cut: job +1/-1, envelope +1/-1.
    // oreo outlives the loop. Net zero.
    [Fact]
    public async Task StaticSuspend_Fault_ReturnsToBaseline()
    {
        await using var oreo = new Swing("Oreo");
        await AssertNoLeakAsync(async () =>
        {
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => Siesta.AlarmAsync());
            await Assert.ThrowsAnyAsync<ArgumentException>(() => Swing.SnapAsync());
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => oreo.FrayAsync());
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => NapSpot.Laundry.EvictAsync());
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => new NapLength(5).CutAsync());
        }, iterations: 200);
    }

    // ADR-229
    // Cancellation on every static suspend owner, two ways: a pre-cancelled token (the job may be
    // cancelled before it ever runs) and a token that fires while Kotlin is parked in
    // `awaitCancellation`. Each call is awaited to completion before the count, so the job handle
    // and the completion `GCHandle` are both released on the cancelled arm, and LingerWith's owned
    // box goes in the C# `finally` though the job never completes normally.
    // Ledger per iteration: Linger, LingerWith (box +1/-1), Creak, Dangle, Guard, Oversleep: job
    // +1/-1 each, on both tokens. oreo outlives the loop. Net zero.
    [Fact]
    public async Task StaticSuspend_Cancellation_ReturnsToBaseline()
    {
        await using var oreo = new Swing("Oreo");
        await AssertNoLeakAsync(async () =>
        {
            using (var cancelled = new CancellationTokenSource())
            {
                cancelled.Cancel();
                await CancelEveryOwner(oreo, cancelled.Token);
            }
            using (var later = new CancellationTokenSource(TimeSpan.FromMilliseconds(1)))
            {
                await CancelEveryOwner(oreo, later.Token);
            }
        }, iterations: 50);
    }

    private static async Task CancelEveryOwner(Swing swing, CancellationToken token)
    {
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Siesta.LingerAsync(token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Siesta.LingerWithAsync("Mylo", token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Swing.CreakAsync(token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => swing.DangleAsync(token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => NapSpot.Box.GuardAsync(token));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new NapLength(60).OversleepAsync(token));
    }

    // ADR-229
    // `Siesta.now()` and `Swing.ready()` never suspend, so `launchForCSharp`'s completion callback
    // often runs on a Kotlin worker before the `_async` P/Invoke has returned its job handle to C#.
    // ADR-019's job cell has to release the job handle and the completion `GCHandle` on either
    // ordering. Ledger per iteration: two jobs +1/-1. Net zero over 1000 iterations.
    [Fact]
    public async Task StaticSuspend_TightLoop_CompletionBeforeReturn_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal(1, await Siesta.NowAsync());
            Assert.Equal(2, await Swing.ReadyAsync());
        }, iterations: 1000);
    }

    // ADR-230
    // A flow on an owner with no instance scope: the file facade, an `object`, a default-named
    // and a named companion. Each collection runs on its own ad-hoc root scope, so the only handle
    // a cold collection mints is its job, which completion releases. A generic `T` argument is an
    // owned box the call disposes, a class element is one handle per item the consumer disposes,
    // and a returned or read `KotlinFlow` holder is disposed by its `using`.
    // Ledger per iteration: Purrs, Beats, Chorus, Calls, Chirps, Grains, Ticks(0) job +1/-1 each;
    // Echo("Mylo") and Repeat(i) box +1/-1, job +1/-1, items boxed +1/-1 each; ClockCats job +1/-1,
    // two cat handles +1/-1; WarmUp suspend job +1/-1, collection job +1/-1. Net zero.
    [Fact]
    public async Task StaticFlow_EveryOwner_Completion_ReturnsToBaseline()
    {
        int i = 0;
        await AssertNoLeakAsync(async () =>
        {
            i++;
            using (KotlinFlow<int> purrs = Purrclock.Chimes.Purrs(3)) Assert.Equal([0, 1, 2], await DrainFlow(purrs));
            using (KotlinFlow<string> echo = Purrclock.PurrClock.Echo("Mylo", 2)) Assert.Equal(["Mylo", "Mylo"], await DrainFlow(echo));
            Assert.Equal([1, 2], await DrainFlow(Purrclock.PurrClock.Beats));
            Assert.Equal(["Oreo", "Mylo"], await DrainFlow(Purrclock.Chimes.Chorus));
            foreach (Purrclock.ClockCat cat in await DrainFlow(Purrclock.PurrClock.ClockCats()))
            {
                using (cat) Assert.False(string.IsNullOrEmpty(cat.Name));
            }
            Assert.Equal(["cuckoo", "cuckoo"], await DrainFlow(Purrclock.Cuckoo.Calls()));
            Assert.Equal([i, i], await DrainFlow(Purrclock.Cuckoo.Repeat(i)));
            Assert.Equal([3, 2, 1], await DrainFlow(Purrclock.Cuckoo.Chirps));
            Assert.Equal([1, 2], await DrainFlow(Purrclock.Hourglass.Grains(2)));
            using (KotlinFlow<int> warm = await Purrclock.PurrClock.WarmUpAsync(2)) Assert.Equal([0, 10], await DrainFlow(warm));
        }, iterations: 200);
    }

    // ADR-230
    // `Ticks(0)` and `Purrs(0)` are empty: `onComplete` can fire on a Kotlin worker before the
    // `_collect` P/Invoke has returned its job handle (the ADR-161 ordering window), and the
    // enumerator has to release the job on either ordering. Ledger per iteration: two jobs +1/-1.
    [Fact]
    public async Task StaticFlow_TightLoop_SynchronousCompletion_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            Assert.Empty(await DrainFlow(Purrclock.PurrClock.Ticks(0)));
            Assert.Empty(await DrainFlow(Purrclock.Chimes.Purrs(0)));
        }, iterations: 1000);
    }

    // ADR-230
    // A fault mid-stream on every static owner: the job is released on the faulted arm and the
    // Kotlin throwable crosses as one ADR-107 envelope the C# exception owns.
    // Ledger per iteration: Broken, DroppedPurrs, Jammed: job +1/-1, envelope +1/-1. Net zero.
    [Fact]
    public async Task StaticFlow_Fault_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => DrainFlow(Purrclock.PurrClock.Broken()));
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => DrainFlow(Purrclock.Chimes.DroppedPurrs()));
            await Assert.ThrowsAnyAsync<ArgumentException>(() => DrainFlow(Purrclock.Cuckoo.Jammed()));
        }, iterations: 200);
    }

    // ADR-230
    // Cancellation on every static owner, two ways: `break` (the enumerator's `DisposeAsync`
    // cancels the job) and a token cancelled mid-stream. Each enumeration is awaited to its end
    // before the count. Ledger per iteration: Forever, EndlessPurrs, Endless: job +1/-1 on each
    // path. Net zero.
    [Fact]
    public async Task StaticFlow_Cancellation_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await foreach (int _ in Purrclock.PurrClock.Forever()) break;
            await foreach (int _ in Purrclock.Chimes.EndlessPurrs()) break;
            await foreach (string _ in Purrclock.Cuckoo.Endless()) break;

            using var cts = new CancellationTokenSource();
            int seen = 0;
            await foreach (int _ in Purrclock.PurrClock.Forever().WithCancellation(cts.Token))
            {
                if (++seen == 2) cts.Cancel();
            }
            using var later = new CancellationTokenSource();
            await foreach (string _ in Purrclock.Cuckoo.Endless().WithCancellation(later.Token))
            {
                later.Cancel();
            }
        }, iterations: 200);
    }

    // ADR-230
    // The held hot flows: each `MutableStateFlow` / `MutableSharedFlow` read or return is a fresh
    // holder over the Kotlin flow's own handle, which its `using` releases; a `.Value` read of a
    // read-only `StateFlow` mints nothing. Ledger per iteration: Hour, Alarm, Bedtime, Perch,
    // Gossip, Sightings, Yowls holder +1/-1 each; Pose, Stance, Bulletin, News, DinnerBell, Level,
    // Perched holder +1/-1 each if one is minted; Dial, Winder, LatestNews, Roost and Warble suspend
    // job +1/-1 and holder +1/-1 each. Net zero.
    [Fact]
    public async Task StaticFlow_HotHolders_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using (KotlinMutableStateFlow<int> hour = Purrclock.PurrClock.Hour)
            using (KotlinMutableStateFlow<int> alarm = Purrclock.PurrClock.Alarm())
            {
                alarm.Value = hour.Value;
            }
            using (KotlinMutableStateFlow<int> bedtime = Purrclock.Chimes.Bedtime) bedtime.Value = 21;
            using (KotlinMutableStateFlow<string> perch = Purrclock.Cuckoo.Perch()) perch.Value = "Oreo";
            using (KotlinMutableSharedFlow<string> gossip = Purrclock.PurrClock.Gossip()) gossip.TryEmit("Mylo");
            using (KotlinMutableSharedFlow<string> sightings = Purrclock.PurrClock.Sightings) Assert.NotEmpty(sightings.ReplayCache);
            using (KotlinMutableSharedFlow<string> yowls = Purrclock.Chimes.Yowls) yowls.TryEmit("Oreo");
            using (KotlinStateFlow<Purrclock.Posture> pose = Purrclock.PurrClock.Pose) Assert.Equal(Purrclock.Posture.Loaf, pose.Value);
            using (KotlinStateFlow<Purrclock.Posture> stance = Purrclock.PurrClock.Stance()) Assert.Equal(Purrclock.Posture.Loaf, stance.Value);
            using (KotlinSharedFlow<int> bulletin = Purrclock.PurrClock.Bulletin) Assert.Equal(7, Assert.Single(bulletin.ReplayCache));
            using (KotlinSharedFlow<int> news = Purrclock.PurrClock.News()) Assert.Equal(7, Assert.Single(news.ReplayCache));
            using (KotlinStateFlow<string> bell = Purrclock.Chimes.DinnerBell()) Assert.Equal("Oreo, dinner", bell.Value);
            using (KotlinStateFlow<int> level = Purrclock.Hourglass.Level) Assert.Equal(100, level.Value);
            using (KotlinStateFlow<string> perched = Purrclock.Cuckoo.Perched) Assert.Equal("Oreo", perched.Value);
            using (KotlinStateFlow<int> dial = await Purrclock.PurrClock.DialAsync())
            using (KotlinMutableStateFlow<int> winder = await Purrclock.PurrClock.WinderAsync())
            {
                winder.Value = dial.Value;
            }
            using (KotlinMutableStateFlow<string> roost = await Purrclock.Cuckoo.RoostAsync()) roost.Value = "Oreo";
            using (KotlinSharedFlow<int> latest = await Purrclock.PurrClock.LatestNewsAsync()) Assert.Equal(7, Assert.Single(latest.ReplayCache));
            using (KotlinFlow<string> warble = await Purrclock.Cuckoo.WarbleAsync()) Assert.Equal(["warble"], await DrainFlow(warble));
        }, iterations: 200);
    }

    // ADR-230 amendment
    // A nullable flow member on every static owner: an absent one mints nothing (the probe is a
    // bool), a present cold one only its collection job, a present hot one its holder, which the
    // `using` releases. Ledger per iteration: each present collection job +1/-1, Pillow and Snores
    // holders +1/-1, LaterIfHome suspend job +1/-1 and acquired flow +1/-1. Net zero.
    [Fact]
    public async Task NullableStaticFlow_PresentAndAbsent_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal([1, 2], await DrainFlow(Purrclock.Absences.PurrsIfHome(true)!));
            Assert.Null(Purrclock.Absences.PurrsIfHome(false));
            Assert.Null(Purrclock.Absences.CatFlap);
            Assert.Equal([3], await DrainFlow(Purrclock.Snoozer.Naps(true)!));
            Assert.Null(Purrclock.Snoozer.Naps(false));
            using (KotlinStateFlow<int>? pillow = Purrclock.Snoozer.Pillow) Assert.Equal(4, pillow!.Value);
            using (KotlinSharedFlow<int>? snores = Purrclock.Snoozer.Snores(true)) Assert.Single(snores!.ReplayCache);
            Assert.Null(Purrclock.NapBlanket.Creases(false));
            Assert.Equal([1], await DrainFlow(Purrclock.HumourExtensions.Purr(Purrclock.Humour.Content)!));
            Assert.Null(new Purrclock.Feeler(0).Sway);
            using (KotlinFlow<int>? later = await Purrclock.Absences.LaterIfHomeAsync(true)) Assert.Equal([7], await DrainFlow(later!));
        }, iterations: 200);
    }

    // ADR-210 amendment
    // A nullable `Flow<T>?` parameter: a null source registers nothing and mints nothing; a present
    // one registers its key, which the Kotlin holder's cleaner releases once the flow is collected
    // and dropped. Ledger per iteration: each suspend job +1/-1, each present key +1/-1. Net zero.
    [Fact]
    public async Task NullableFlowParameter_NullAndPresent_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal(-1, await Purrclock.Doorways.CountVisitorsAsync(null));
            Assert.Equal(3, await Purrclock.Doorways.CountVisitorsAsync(Yield(1, 2)));
            Assert.False(Purrclock.Doorways.HasFlap(null));
            Assert.Equal("nobody", await Purrclock.Doorman.GreetAsync(null));
            Assert.Equal("Oreo", await Purrclock.Doorman.GreetAsync(Yield("Oreo")));
        }, iterations: 200);
    }

    private static async IAsyncEnumerable<T> Yield<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    private static async Task<List<T>> DrainFlow<T>(IAsyncEnumerable<T> flow)
    {
        var seen = new List<T>();
        await foreach (T item in flow) seen.Add(item);
        return seen;
    }

    // ADR-231
    // A plain lambda on a suspend member. The delegate's callback context is a key in the context
    // table, which `LiveHandles` does not count (the collectability rows pin that); these rows pin
    // the handles that do cross beside it. Ledger per iteration: Run, Quick, Label, Cheer, Pace jobs
    // +1/-1 each; Inspect(baton) job +1/-1, the baton lent to the delegate as a borrowed wrapper
    // +1/-1; Recruit: the delegate's new Baton is consumed by Kotlin +1/-1, job +1/-1;
    // Relay(i) box +1/-1, result +1/-1, job +1/-1; Relay(baton) result wrapper +1/-1;
    // Baton.Hand: the delegate's borrowed wrapper +1/-1, result wrapper +1/-1; Sprint, Chase, top
    // level, extensions, enum, value class, generic class members: job +1/-1 each, generic `T`
    // boxes +1/-1. treadmill, baton and kibble outlive the loop. Net zero.
    [Fact]
    public async Task SuspendPlainLambda_EveryOwnerAndPayload_Completion_ReturnsToBaseline()
    {
        await using var treadmill = new Dash.Treadmill("Oreo");
        using var baton = new Dash.Baton("Mylo");
        await using var kibble = new Dash.Bowlful<Dash.Baton>(baton);
        await using var chase = new Dash.Chase("the red dot");
        int i = 0;
        // Warm each lazy owner scope OUTSIDE the window: `GetOrCreateScope()` is counted.
        Assert.Equal(1, await treadmill.QuickAsync(_ => { }));
        using (Dash.Baton warmed = await kibble.ServeAsync(_ => { })) Assert.Equal("Mylo", warmed.Holder);
        Assert.Equal("the red dot", await chase.PounceAsync(_ => { }));
        await AssertNoLeakAsync(async () =>
        {
            i++;
            int steps = 0;
            Assert.Equal(2, await treadmill.RunAsync(2, _ => steps++));
            Assert.Equal(1, await treadmill.QuickAsync(_ => steps++));
            Assert.Equal("#3", await treadmill.LabelAsync(3, n => $"#{n}"));
            Assert.Equal("go Oreo", await treadmill.CheerAsync(name => $"go {name}"));
            Assert.True(await treadmill.PaceAsync((_, _, night) => night));
            Assert.Equal("Mylo", await treadmill.InspectAsync(baton, held => held.Holder));
            Assert.Equal("Oreo", await treadmill.RecruitAsync("Oreo", name => new Dash.Baton(name)));
            Assert.Equal("Oreo", treadmill.RecruitNow("Oreo", name => new Dash.Baton(name)));
            Assert.Equal("Mylo", treadmill.RecruitNow("ignored", _ => baton));
            Assert.Equal(i * 2, await treadmill.RelayAsync(i, n => n * 2));
            using (Dash.Baton passed = await treadmill.RelayAsync(baton, held => held)) Assert.Equal("Mylo", passed.Holder);
            Assert.Equal(20, await treadmill.SwarmAsync(20, _ => Interlocked.Increment(ref steps)));
            using (Dash.Baton handed = await Dash.Baton.HandAsync("Oreo", held => Assert.Equal("Oreo", held.Holder))) Assert.Equal("Oreo", handed.Holder);
            Assert.Equal(1, await Dash.Sprint.LapsAsync(1, _ => steps++));
            Assert.Equal("Mylo", await Dash.Sprint.FetchAsync("Mylo", name => name));
            Assert.Equal(1, await Dash.Chase.BeginAsync(1, _ => steps++));
            Assert.Equal("the red dot", await chase.PounceAsync(_ => steps++));
            Assert.Equal(1, await Dash.DashSample.DashAsync(1, _ => steps++));
            Assert.Equal(i, await Dash.DashSample.BounceAsync(i, n => n));
            Assert.Equal("Oreo warmed up", await Dash.TreadmillExtensions.WarmUpAsync(treadmill, _ => steps++));
            Assert.Equal("MYLO", await Dash.StringExtensions.ZoomAsync("Mylo", name => name.ToUpperInvariant()));
            Assert.Equal(0, await Dash.ChaseToyExtensions.SwatAsync(Dash.ChaseToy.Feather, _ => steps++));
            Assert.Equal(2, await new Dash.Strides(2).MeasureAsync(_ => 1));
            using (Dash.Baton served = await kibble.ServeAsync(held => Assert.Equal("Mylo", held.Holder))) Assert.Equal("Mylo", served.Holder);
        }, iterations: 200);
    }

    // ADR-231
    // A fault on the plain-lambda suspend route: a Kotlin throw after the delegate ran, and the
    // delegate itself throwing (ADR-161's stash, cleared by the completion closure). Ledger per
    // iteration: Crash, Trip: job +1/-1, envelope +1/-1; Run with a throwing delegate: job +1/-1,
    // the stashed managed exception's envelope +1/-1. Net zero.
    [Fact]
    public async Task SuspendPlainLambda_Fault_ReturnsToBaseline()
    {
        await using var treadmill = new Dash.Treadmill("Oreo");
        // Warm each lazy owner scope OUTSIDE the window: `GetOrCreateScope()` is counted.
        Assert.Equal(1, await treadmill.QuickAsync(_ => { }));
        await AssertNoLeakAsync(async () =>
        {
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => treadmill.CrashAsync(_ => { }));
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => Dash.Sprint.TripAsync(_ => { }));
            await Assert.ThrowsAsync<ArgumentException>(
                () => treadmill.RunAsync(2, _ => throw new ArgumentException("Mylo hit the stop button")));
        }, iterations: 200);
    }

    // ADR-231
    // Cancellation on the plain-lambda suspend route, with a pre-canceled token and with a timeout
    // that fires while the coroutine is suspended. Ledger per iteration: Stall, Hold: job +1/-1.
    // Net zero.
    [Fact]
    public async Task SuspendPlainLambda_Cancel_ReturnsToBaseline()
    {
        await using var treadmill = new Dash.Treadmill("Oreo");
        // Warm each lazy owner scope OUTSIDE the window: `GetOrCreateScope()` is counted.
        Assert.Equal(1, await treadmill.QuickAsync(_ => { }));
        await AssertNoLeakAsync(async () =>
        {
            using (var canceled = new CancellationTokenSource())
            {
                canceled.Cancel();
                await Assert.ThrowsAnyAsync<OperationCanceledException>(() => treadmill.StallAsync(_ => { }, canceled.Token));
            }
            using (var soon = new CancellationTokenSource(TimeSpan.FromMilliseconds(1)))
            {
                await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Dash.Sprint.HoldAsync(_ => { }, soon.Token));
            }
        }, iterations: 50);
    }

    // ADR-231
    // `Quick` never suspends, so Kotlin's release of the callback context and the completion
    // callback routinely run before the `_async` P/Invoke returns. Ledger per iteration: job
    // +1/-1. Net zero.
    [Fact]
    public async Task SuspendPlainLambda_TightLoop_CompletionBeforeReturn_ReturnsToBaseline()
    {
        await using var treadmill = new Dash.Treadmill("Mylo");
        // Warm each lazy owner scope OUTSIDE the window: `GetOrCreateScope()` is counted.
        Assert.Equal(1, await treadmill.QuickAsync(_ => { }));
        await AssertNoLeakAsync(async () =>
        {
            int seen = 0;
            Assert.Equal(1, await treadmill.QuickAsync(step => seen = step));
            Assert.Equal(7, seen);
        }, iterations: 1000);
    }

    // ADR-231
    // A plain lambda on a Flow-returning member: complete, fault, cancel and an early break. Ledger
    // per iteration: each collection's Flow wrapper +1/-1 and its collect job +1/-1; WatchCrash's
    // envelope +1/-1; Sprint.Stream, Chase.Calls and DashSample.Dashes the same. Net zero.
    [Fact]
    public async Task FlowPlainLambda_EveryArm_ReturnsToBaseline()
    {
        await using var treadmill = new Dash.Treadmill("Oreo");
        // Warm each lazy owner scope OUTSIDE the window: `GetOrCreateScope()` is counted.
        Assert.Equal(1, await treadmill.QuickAsync(_ => { }));
        await AssertNoLeakAsync(async () =>
        {
            int seen = 0;
            using (KotlinFlow<int> watching = treadmill.Watch(3, _ => seen++)) Assert.Equal([0, 1, 2], await DrainFlow(watching));
            using (KotlinFlow<string> commentary = treadmill.Commentate(2, n => $"lap {n}")) Assert.Equal(["lap 0", "lap 1"], await DrainFlow(commentary));
            using (KotlinFlow<int> crashing = treadmill.WatchCrash(_ => seen++))
            {
                await Assert.ThrowsAnyAsync<InvalidOperationException>(() => DrainFlow(crashing));
            }
            using (var cts = new CancellationTokenSource())
            using (KotlinFlow<int> forever = treadmill.WatchForever(_ => seen++))
            {
                // A cancelled KotlinFlow enumeration ends cleanly, the shipped Flow behaviour (no exception).
                await foreach (int _ in forever.WithCancellation(cts.Token)) cts.Cancel();
            }
            using (KotlinFlow<int> watching = treadmill.Watch(5, _ => seen++))
            {
                await foreach (int _ in watching) break;
            }
            using (KotlinFlow<int> stream = Dash.Sprint.Stream(2, _ => seen++)) Assert.Equal([0, 1], await DrainFlow(stream));
            using (KotlinFlow<string> calls = Dash.Chase.Calls(1, n => $"psst {n}")) Assert.Equal(["psst 0"], await DrainFlow(calls));
            using (KotlinFlow<int> dashes = Dash.DashSample.Dashes(1, _ => seen++)) Assert.Equal([0], await DrainFlow(dashes));
        }, iterations: 100);
    }

    // ADR-231
    // A sealed arm, an enum companion and a value class over a handle. Ledger per iteration:
    // Stride, Pick, Tug jobs +1/-1 each; Tug's borrowed baton handle crosses lent. trot and baton
    // outlive the loop. Net zero.
    [Fact]
    public async Task SuspendPlainLambda_SealedArmEnumCompanionValueClass_ReturnsToBaseline()
    {
        await using var trot = new Dash.Gait.Trot(1);
        using var baton = new Dash.Baton("Mylo");
        // Warm the arm's lazy scope OUTSIDE the window: `GetOrCreateScope()` is counted.
        Assert.Equal(1, await trot.StrideAsync(_ => { }));
        await AssertNoLeakAsync(async () =>
        {
            int steps = 0;
            Assert.Equal(1, await trot.StrideAsync(_ => steps++));
            Assert.Equal("2 lanes", await Dash.LaneExtensions.PickAsync(n => $"{n} lanes"));
            Assert.Equal("Mylo", await new Dash.Leash(baton).TugAsync(_ => steps++));
        }, iterations: 200);
    }

    // ADR-231
    // A `suspend` member returning a Flow with a plain lambda: the returned KotlinFlow owns the
    // delegate's key. Ledger per iteration: Feed job +1/-1, its flow handle +1/-1 (disposed with
    // the KotlinFlow), each collect job +1/-1; Spill job +1/-1, envelope +1/-1; MaybeFeed(false)
    // job +1/-1 and no flow; Sprint.WarmUpLaps the same as Feed. Net zero.
    [Fact]
    public async Task SuspendReturningFlowPlainLambda_ReturnsToBaseline()
    {
        await using var bowl = new Dash.FoodBowl("Oreo");
        // Warm the lazy owner scope OUTSIDE the window: `GetOrCreateScope()` is counted.
        Assert.Null(await bowl.MaybeFeedAsync(false, _ => { }));
        await AssertNoLeakAsync(async () =>
        {
            int bites = 0;
            using (KotlinFlow<int> feeding = await bowl.FeedAsync(2, _ => bites++))
            {
                Assert.Equal([0, 1], await DrainFlow(feeding));
                Assert.Equal([0, 1], await DrainFlow(feeding));
            }
            await Assert.ThrowsAnyAsync<InvalidOperationException>(() => bowl.SpillAsync(_ => bites++));
            Assert.Null(await bowl.MaybeFeedAsync(false, _ => bites++));
            using (KotlinFlow<int> laps = await Dash.Sprint.WarmUpLapsAsync(1, _ => bites++)) Assert.Equal([0], await DrainFlow(laps));
        }, iterations: 100);
    }

    // ADR-236
    // Lambda-typed properties on every owner the class route did not reach. A read of a `val`
    // hands C# a delegate bound to the Kotlin lambda; a write hands Kotlin a lambda minted over a C#
    // delegate, whose `GCHandle` key the lambda's cleaner frees (not counted, the collectability
    // rows pin it). Ledger per iteration: each read +1, released once the wrapper is collected -1;
    // each write of a C# delegate +1 (the minted lambda the temporary wrapper owns), released once
    // that wrapper is collected -1; the replaced or cleared lambda is Kotlin's to drop;
    // Relay<Skein>.OnPass: the borrowed argument wrapper +1/-1, the result wrapper +1/-1; YarnBin
    // Unwind: three result wrappers +1/-1 each. relay, bin and the skeins outlive the loop. Net zero.
    // Oreo laps the landing, Mylo resets the stopwatch.
    [Fact]
    public void LambdaProperty_EveryOwner_ReadAndWrite_ReturnsToBaseline()
    {
        using var red = new Zoomrun.Skein("red");
        using var relay = new Zoomrun.Relay<Zoomrun.Skein>(red);
        using var bin = new Zoomrun.YarnBin("Oreo");
        Func<double, string> format = Zoomrun.Landing.Format;
        Func<string, string> announce = Zoomrun.Racetrack.Announce;
        Func<int, string> measure = Zoomrun.Burrow.Measure;
        Func<string, string> cheer = Zoomrun.StrideExtensions.Cheer(Zoomrun.Stride.Trot);
        AssertNoLeak(() =>
        {
            Zoomrun.Landing.OnLap.Invoke(1);
            Zoomrun.Landing.Format = metres => $"Mylo timed {metres}";
            Assert.Equal("Mylo timed 2", Zoomrun.Landing.Describe(2.0));
            Zoomrun.Landing.Format = format;
            Zoomrun.Landing.OnBump = who => who;
            Assert.Equal("Oreo", Zoomrun.Landing.Bump("Oreo"));
            Zoomrun.Landing.OnBump = null;
            Zoomrun.ZoomrunSample.OnZoom.Invoke(1);
            Zoomrun.ZoomrunSample.OnZoomOut = who => who;
            Assert.Equal("Mylo", Zoomrun.ZoomrunSample.ZoomOut("Mylo"));
            Zoomrun.ZoomrunSample.OnZoomOut = null;
            Assert.Equal("on your marks", Zoomrun.Racetrack.OnStart.Invoke());
            Zoomrun.Racetrack.Announce = runner => runner;
            Assert.Equal("Oreo", Zoomrun.Racetrack.Call("Oreo"));
            Zoomrun.Racetrack.Announce = announce;
            Assert.Equal(3, Zoomrun.Burrow.OnDig.Invoke(1));
            Zoomrun.Burrow.Measure = depth => $"{depth}";
            Assert.Equal("1", Zoomrun.Burrow.Survey(1));
            Zoomrun.Burrow.Measure = measure;
            Assert.Equal(2, Zoomrun.StrideExtensions.OnStride(Zoomrun.Stride.Trot).Invoke(1));
            Zoomrun.StrideExtensions.SetCheer(Zoomrun.Stride.Trot, who => who);
            Assert.Equal("Mylo", Zoomrun.StrideExtensions.Shout(Zoomrun.Stride.Trot, "Mylo"));
            Zoomrun.StrideExtensions.SetCheer(Zoomrun.Stride.Trot, cheer);
            Zoomrun.StrideExtensions.Fallback = stride => $"{stride}";
            Assert.Equal("Gallop", Zoomrun.StrideExtensions.Recover(Zoomrun.Stride.Gallop));
            Zoomrun.StrideExtensions.Fallback = null;
            using (Zoomrun.Skein previous = relay.OnPass.Invoke(red)) Assert.Equal("red", previous.Colour);
            relay.Inspect = held => held.Colour;
            Assert.Equal("red", relay.Describe());
            relay.OnDrop = _ => { };
            Assert.Equal("dropped", relay.Drop());
            relay.OnDrop = null;
            Assert.Equal("Oreo's red", bin.Unravel());
            using (Zoomrun.Tote<Zoomrun.Tote<Zoomrun.Skein>> outer = bin.Unwind.Invoke())
            using (Zoomrun.Tote<Zoomrun.Skein> inner = outer.Item)
            using (Zoomrun.Skein skein = inner.Item)
            {
                Assert.Equal("Oreo's red", skein.Colour);
            }
        }, iterations: 200);
    }

    // ADR-236
    // The interface halves. Ledger per iteration: LaserDot() wrapper +1/-1 (disposed); its
    // OnPounce read +1/-1; Teach writes a Kotlin lambda into the C# Cue, handing C# a delegate over
    // it +1, released when the wand drops it -1; the wand itself crosses into Kotlin as a bridge
    // handle +1/-1 per call. Net zero.
    private sealed class LeakyWand : Zoomrun.IPouncer
    {
        public Func<int, string> OnPounce { get; } = times => $"{times}";

        public Func<string, string>? Cue { get; set; }

        public Func<int, CancellationToken, Task<int>> OnLeap { get; } = (height, _) => Task.FromResult(height);

        public void Dispose() { }
    }

    [Fact]
    public void LambdaProperty_Interface_BothHalves_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Zoomrun.IPouncer dot = Zoomrun.ZoomrunSample.LaserDot();
            Assert.Equal("Mylo pounced 1 times", dot.OnPounce.Invoke(1));
            dot.Cue = word => word;
            Assert.Equal("treats", Zoomrun.ZoomrunSample.Cue(dot, "treats"));
            dot.Cue = null;
            (dot as IDisposable)?.Dispose();
            var wand = new LeakyWand();
            Assert.Equal("2", Zoomrun.ZoomrunSample.Pounce(wand, 2));
            Zoomrun.ZoomrunSample.Teach(wand, "Oreo");
            Assert.Equal("Oreo sit", wand.Cue!.Invoke("sit"));
            wand.Cue = null;
        }, iterations: 200);
    }

    // ADR-236
    // A suspend member returning a StateFlow/SharedFlow with a plain lambda. Ledger per iteration:
    // the job +1/-1, the returned flow +1, released on Dispose -1; the delegate is a context key
    // released with the flow; the tower +1/-1 and the coroutine scope its first suspend call mints
    // +1, released with the tower -1. Net zero.
    [Fact]
    public async Task LambdaProperty_SuspendStateAndSharedFlowResult_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var tower = new Zoomrun.LaserTower("Oreo");
            using (KotlinStateFlow<int> glow = await tower.BeamAsync(level => level + 1))
            {
                Assert.Equal(3, tower.Pulse(2));
                Assert.Equal(3, glow.Value);
            }
            using (KotlinSharedFlow<string> flashes = await tower.FlashAsync(times => $"{times}"))
            {
                Assert.True(tower.Flick(1));
            }
            using (KotlinStateFlow<int> beam = await Zoomrun.Lighthouse.ShineAsync(angle => angle))
            {
                Assert.Equal(5, Zoomrun.Lighthouse.Turn(5));
            }
        }, iterations: 50);
    }

    // ADR-239: a returned lambda is a BCL delegate whose target releases the Kotlin lambda handle
    // when it is collected, on every owner. Ledger per iteration: each returned delegate +1, released
    // by the settle's GC -1; the tassel, megaphone and tumble outlive the loop; the C# Pompom crosses
    // into Kotlin as a bridge handle +1/-1 per call, and the delegate it returns is minted into a
    // Kotlin lambda that Kotlin drops when the call ends. Net zero. Oreo shakes the pompoms; Mylo
    // counts.
    private sealed class LeakyPompom : Cheersquad.IChanter
    {
        public Func<string, string> Cheer() => who => who;

        public Func<string, CancellationToken, Task<string>> Chant() => (who, _) => Task.FromResult(who);

        public Func<string, string>? MaybeCheer(bool loud) => loud ? who => who : null;

        public void Dispose() { }
    }

    [Fact]
    public void ReturnedLambda_EveryOwner_ReturnsToBaseline()
    {
        using var tassel = new Cheersquad.Tassel("black");
        using var megaphone = new Cheersquad.Megaphone<string>("bell");
        using var tumble = new Cheersquad.Routine.Tumble(2);
        AssertNoLeak(() =>
        {
            Assert.Equal("go Oreo", Cheersquad.CheersquadSample.Cheer().Invoke("Oreo"));
            Cheersquad.CheersquadSample.Clap().Invoke(1);
            Assert.Null(Cheersquad.CheersquadSample.MaybeCheer(false));
            Assert.Equal("GO Mylo", Cheersquad.CheersquadSample.MaybeCheer(true)!.Invoke("Mylo"));
            Assert.Equal("black tassel for Oreo", tassel.Cheer().Invoke("Oreo"));
            tassel.Clap().Invoke(1);
            Assert.Equal("Oreo x2 in black", tassel.Pair().Invoke("Oreo", 2));
            Assert.Equal("the bleachers cheer Mylo", Cheersquad.Bleachers.Cheer().Invoke("Mylo"));
            Assert.Equal("the mascot cheers Oreo", Cheersquad.Mascot.Cheer().Invoke("Oreo"));
            Assert.Equal("raised 1 paws high", Cheersquad.Pennant.Raise().Invoke(1));
            Assert.Equal("Oreo", megaphone.Echo().Invoke("Oreo"));
            Assert.Equal("bell", megaphone.Hold().Invoke());
            Assert.Equal("3 flips", tumble.Flip().Invoke(1));
            Assert.Equal("tumble for Mylo", tumble.Cheer().Invoke("Mylo"));
            Assert.Equal(6, new Cheersquad.Squad(3).Count().Invoke(2));
            Assert.Equal("Oreo into the line", Cheersquad.FormationExtensions.Call(Cheersquad.Formation.Line).Invoke("Oreo"));
            Assert.Equal("black encore for Mylo", Cheersquad.TasselExtensions.Encore(tassel).Invoke("Mylo"));
            using (Cheersquad.IChanter horn = Cheersquad.CheersquadSample.Megaphone())
            {
                Assert.Equal("Mylo cheers Oreo", horn.Cheer().Invoke("Oreo"));
            }
            var pompom = new LeakyPompom();
            Assert.Equal("Oreo", Cheersquad.CheersquadSample.Rally(pompom, "Oreo"));
            Assert.Equal("silence for Mylo", Cheersquad.CheersquadSample.RallyMaybe(pompom, false, "Mylo"));
            Assert.Equal("Mylo", Cheersquad.CheersquadSample.RallyMaybe(pompom, true, "Mylo"));
        }, iterations: 200);
    }

    [Fact]
    public async Task ReturnedLambda_SuspendResults_ReturnsToBaseline()
    {
        using var tassel = new Cheersquad.Tassel("brown");
        using var tumble = new Cheersquad.Routine.Tumble(2);
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal("Oreo, Oreo, Oreo", await Cheersquad.CheersquadSample.Chant().Invoke("Oreo", CancellationToken.None));
            await Cheersquad.CheersquadSample.Stomp().Invoke(1, CancellationToken.None);
            Assert.Equal("brown chant for Mylo", await tassel.Chant().Invoke("Mylo", CancellationToken.None));
            Assert.Equal(4, await tumble.Spin().Invoke(2, CancellationToken.None));
        }, iterations: 50);
    }

    // ADR-239: Kotlin awaits the `suspend` lambda a C# implementation returned, through a `Cheerleader`.
    // The Cheerleader's first `RallyLaterAsync` mints its coroutine scope handle (ADR-128: created
    // lazily per owner on the first `suspend` call, released when the owner is disposed), so the
    // count rises once at the first crossing. Ledger: the Cheerleader +1, its scope +1 on the first
    // call, flat from the first crossing to the Nth (each returned delegate is minted into a Kotlin
    // lambda Kotlin drops), and both -1 at Dispose.
    [Fact]
    public async Task ReturnedAsyncDelegate_FromACSharpImplementation_IsFlatAfterTheOwnerScope()
    {
        await SettleAsync();
        long baseline = NugetMarshal.LiveHandles;
        var cheerleader = new Cheersquad.Cheerleader(new LeakyPompom());
        Assert.Equal("Mylo", await cheerleader.RallyLaterAsync("Mylo"));
        await SettleAsync();
        long afterFirst = NugetMarshal.LiveHandles;
        Assert.Equal(baseline + 2, afterFirst);

        for (int i = 0; i < 50; i++) Assert.Equal("Mylo", await cheerleader.RallyLaterAsync("Mylo"));
        await SettleAsync();
        Assert.Equal(afterFirst, NugetMarshal.LiveHandles);

        cheerleader.Dispose();
        await SettleAsync();
        Assert.Equal(baseline, NugetMarshal.LiveHandles);
    }

    // ADR-239 with ADR-236: a delegate on a sealed arm, both directions. Ledger per iteration: the
    // OnLand write mints a Kotlin lambda over the C# delegate's callback key (not counted), Kotlin
    // calls it, and C# clears it (a C# null) or Kotlin clears it (`Stick`); the OnLand read hands C#
    // a delegate over the Kotlin-held lambda +1, released by the settle's GC -1; the arm's own
    // returned `Flip` delegate +1/-1. The tumble outlives the loop. Net zero. Oreo sticks the
    // landing, Mylo scores it.
    [Fact]
    public void SealedArmDelegate_SetCallClearAndReturn_ReturnsToBaseline()
    {
        using var tumble = new Cheersquad.Routine.Tumble(3);
        AssertNoLeak(() =>
        {
            tumble.OnLand = who => $"{who} stuck it";
            Assert.Equal("Oreo stuck it", tumble.Land("Oreo"));
            Assert.Equal("Mylo stuck it", tumble.OnLand!.Invoke("Mylo"));
            tumble.OnLand = null;
            Assert.Equal("Oreo wobbled", tumble.Land("Oreo"));
            tumble.OnLand = who => who;
            tumble.Stick();
            Assert.Null(tumble.OnLand);
            Assert.Equal("4 flips", tumble.Flip().Invoke(1));
        }, iterations: 200);
    }

    // ADR-239 with ADR-236: a listener-style delegate a Kotlin `object` stores and later clears
    // itself. `Buzzer` is touched once before the baseline so its lazily initialised object instance
    // is not counted against the first iteration. Ledger per iteration: the write mints a Kotlin
    // lambda over the delegate's callback key (not counted); the read hands C# a delegate over it +1,
    // released by the settle's GC -1; `Unplug` drops it in Kotlin. Net zero. Mylo rings for dinner.
    [Fact]
    public void ListenerDelegate_KotlinObjectStoresAndClearsIt_ReturnsToBaseline()
    {
        Assert.Equal("nobody answered Mylo", Cheersquad.Buzzer.Buzz("Mylo"));
        AssertNoLeak(() =>
        {
            Cheersquad.Buzzer.OnBuzz = who => $"{who} came running";
            Assert.Equal("Oreo came running", Cheersquad.Buzzer.Buzz("Oreo"));
            Assert.Equal("Mylo came running", Cheersquad.Buzzer.OnBuzz!.Invoke("Mylo"));
            Cheersquad.Buzzer.Unplug();
            Assert.Null(Cheersquad.Buzzer.OnBuzz);
            Assert.Equal("nobody answered Oreo", Cheersquad.Buzzer.Buzz("Oreo"));
        }, iterations: 200);
    }

    // ADR-239: a stored listener with a result is minted into a Kotlin lambda over the C# delegate
    // and handed to the subscribe export, which consumes the minted handle. Ledger per iteration:
    // the subscription token +1, released by Dispose -1; each verdict's enum result is a box Kotlin
    // owns and releases. Net zero. Mylo judges; Oreo is always hyped.
    [Fact]
    public void StoredListenerWithAResult_SubscribeAndDispose_ReturnsToBaseline()
    {
        using var card = new Cheersquad.Scorecard("Mylo");
        AssertNoLeak(() =>
        {
            using (IDisposable judge = card.AddJudge(_ => Cheersquad.Spirit.Hyped))
            {
                Assert.Equal("HYPED", card.Verdicts());
            }
            Assert.Equal("", card.Verdicts());
        }, iterations: 200);
    }

    // ADR-239: the widened callback vocabulary. Ledger per iteration: each boxed payload Kotlin
    // retains is read and disposed by `FromHandle` (+1/-1); each boxed result C# mints is read and
    // released by Kotlin (+1/-1); each subscription token +1, released by its Dispose -1; the
    // managed listener's minted handle is consumed by the subscribe export. Net zero.
    [Fact]
    public void WidenedCallbackPayloadsAndResults_ReturnToBaseline()
    {
        using var card = new Cheersquad.Scorecard("Mylo");
        using var shaker = new Cheersquad.Shaker<string>("pompom");
        AssertNoLeak(() =>
        {
            Assert.Equal("got M", card.InitialOf(c => $"got {c}"));
            Assert.Equal("none then 7", card.Scores(n => n?.ToString() ?? "none"));
            Assert.Equal(2, card.Roster(names => names.Count));
            Assert.Equal(3, card.BytesOf(bytes => bytes.Length));
            Assert.Equal("Mylo pinned 1 bytes from 5", card.Badge(_ => new byte[] { 5 }));
            using (card.AddWatcher(_ => { })) Assert.Equal(1, card.Announce());
            using (shaker.AddShake(_ => { }))
            using (shaker.AddCounter(() => 1))
            {
                Assert.Equal(1, shaker.Shake());
                Assert.Equal(1, shaker.Count());
            }
        }, iterations: 100);
    }

    // ADR-239: the widened suspend lambda payloads, a nullable suspend lambda, a stored pair on a
    // generic sealed arm and a subscription pair on a generic owner. Ledger per iteration: each
    // boxed payload +1/-1 (Kotlin retains, `FromHandle` disposes); each C# delegate key is released
    // by its holder's cleaner (not counted); each subscription token +1, Dispose -1; the Countdown's
    // coroutine scope is minted by the warm call below the baseline. Net zero.
    private sealed class QuietWave : Cheersquad.IWave
    {
        public void OnWave(int times) { }

        public void Dispose() { }
    }

    [Fact]
    public async Task WidenedSuspendPayloadsAndGenericPairs_ReturnToBaseline()
    {
        using var countdown = new Cheersquad.Countdown("Mylo");
        using var tier = Cheersquad.ScorecardKt.Tier("Oreo");
        using var stand = new Cheersquad.Stand<string>("Mylo");
        var wave = new QuietWave();
        // The Countdown's coroutine scope is created on its first `suspend` call (ADR-128).
        Assert.Equal("nobody for Mylo", await countdown.MaybeLaterAsync(null));
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal("got M", await countdown.InitialLaterAsync((c, _) => Task.FromResult($"got {c}")));
            Assert.Equal(2, await countdown.RosterLaterAsync((names, _) => Task.FromResult(names.Count)));
            Assert.Equal("none then 7", await countdown.ScoresLaterAsync((n, _) => Task.FromResult(n?.ToString() ?? "none")));
            Assert.Equal(3, await countdown.BytesLaterAsync((bytes, _) => Task.FromResult(bytes.Length)));
            Assert.Equal("nobody for Mylo", await countdown.MaybeLaterAsync(null));
            Assert.Equal("hi Mylo", await countdown.MaybeLaterAsync((who, _) => Task.FromResult($"hi {who}")));
            Assert.True(countdown.HasLater((who, _) => Task.FromResult(who)));
            using (tier.AddClimber(_ => { })) Assert.Equal(1, tier.Climb());
            using (stand.AddWave(wave)) Assert.Equal(1, stand.Wave(1));
        }, iterations: 50);
    }

    // ADR-239: a bound C# interface as a callback payload and result, plain and `suspend`. It
    // crosses as a GCHandle, never a `NugetHandles` StableRef: Kotlin's handle-out duplicates or
    // mints one that the C# thunk frees, and a result's GCHandle is taken by Kotlin's wrapper. The
    // Pen's coroutine scope is minted by the warm call below the baseline. Net zero.
    private sealed class PenGoat(int legs) : IFeedable
    {
        public string Describe() => "a pen goat";

        public int Legs => legs;

        public void Feed(string food) { }

        public string? Nickname { get; set; }
    }

    [Fact]
    public async Task BoundInterfaceCallbacks_ReturnToBaseline()
    {
        using var pen = new TestLibrary.Menagerie.Pen();
        pen.Adopt(new PenGoat(4));
        // The Pen's coroutine scope is created on its first `suspend` call (ADR-128).
        Assert.Equal(4, await pen.InspectLaterAsync((feedable, _) => Task.FromResult(feedable.Legs)));
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal(4, pen.Inspect(feedable => feedable.Legs));
            Assert.Equal(3, pen.Fetch(() => new PenGoat(3)));
            Assert.Equal(4, await pen.InspectLaterAsync((feedable, _) => Task.FromResult(feedable.Legs)));
            Assert.Equal(2, await pen.FetchLaterAsync(_ => Task.FromResult<IFeedable>(new PenGoat(2))));
        }, iterations: 50);
    }

    // ADR-239: a bound C# interface at the erased positions. Ledger per iteration: each crossing
    // through `NugetMarshal`'s bound tables mints a Kotlin handle (box) or a GCHandle (unbox) that
    // the reading side disposes or takes; the subscription token +1, Dispose -1; each returned
    // delegate +1, released by the settle's GC -1; the property setter's Kotlin lambda holds a
    // callback key, not a handle. Net zero.
    [Fact]
    public void BoundInterfaceErasedPositions_ReturnToBaseline()
    {
        using var pen = new TestLibrary.Menagerie.Pen();
        pen.Adopt(new PenGoat(4));
        AssertNoLeak(() =>
        {
            using (pen.AddKeeper(feedable => feedable.Legs)) Assert.Equal(4, pen.RollCall());
            Assert.Equal(3, pen.LegCounter()(new PenGoat(3)));
            Assert.Equal(4, pen.ResidentSupplier()().Legs);
            pen.OnSwap = () => new PenGoat(5);
            Assert.Equal(5, pen.SwapLegs());
        }, iterations: 100);
    }

    // ADR-239: a `Throwable` as a `suspend` lambda result (a managed-exception text box Kotlin
    // releases), a nullable plain payload (its envelope, which `BuildException` disposes) and a
    // returned `suspend` lambda's result (the envelope over the generated export, the Kotlin handle
    // disposed by `FromHandle`). The Referee's coroutine scope is minted by the warm call. Net zero.
    [Fact]
    public async Task ThrowableCallbacks_ReturnToBaseline()
    {
        using var referee = new Cheersquad.Referee();
        Assert.Equal("System.Exception: warm", await referee.VerdictAsync((_, _) => Task.FromResult<Exception>(new Exception("warm"))));
        await AssertNoLeakAsync(async () =>
        {
            Assert.Equal("System.Exception: late", await referee.VerdictAsync((_, _) => Task.FromResult<Exception>(new Exception("late"))));
            Assert.Equal("clean / offside", referee.Whistle(foul => foul?.Message ?? "clean"));
            Exception verdict = await referee.VerdictLater()(3, CancellationToken.None);
            Assert.Contains("code 3", verdict.Message);
        }, iterations: 50);
    }

    // A C#-implemented `IFeeder` for the ADR-232 rows, here and in `CollectabilityTests`.
    // `Feeding` and `Menu` replace the defaults per row; everything else answers a constant.
    internal sealed class LeakBowl(string name) : Feedingtime.IFeeder
    {
        public Func<string, CancellationToken, Task<int>>? Feeding;
        public Func<KotlinFlow<string>>? Menu;

        public string Name { get; } = name;

        public async Task<int> FeedAsync(string cat, CancellationToken cancellationToken = default)
        {
            if (Feeding != null) return await Feeding(cat, cancellationToken);
            await Task.Delay(1, cancellationToken);
            return cat.Length;
        }

        public Task RefillAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task<bool> HungryAsync(string cat, CancellationToken cancellationToken = default) => Task.FromResult(true);
        public Task<long> LastMealAsync(CancellationToken cancellationToken = default) => Task.FromResult(1L);
        public Task<float> PortionAsync(CancellationToken cancellationToken = default) => Task.FromResult(0.5f);
        public Task<double> WeighAsync(CancellationToken cancellationToken = default) => Task.FromResult(4.5);
        public Task<Feedingtime.Craving> CravingAsync(string cat, CancellationToken cancellationToken = default) =>
            Task.FromResult(Feedingtime.Craving.Peckish);
        public Task<string?> FavouriteAsync(string cat, CancellationToken cancellationToken = default) =>
            Task.FromResult<string?>("tuna");
        public Task<Exception> SpillAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult<Exception>(new InvalidOperationException("kibble everywhere"));

        public KotlinFlow<string> Meals() => Menu != null ? Menu() : KotlinFlow<string>.From(Courses());

        internal static async IAsyncEnumerable<string> Courses(
            [EnumeratorCancellation] CancellationToken token = default)
        {
            foreach (string course in new[] { "kibble", "tuna" })
            {
                await Task.Delay(1, token);
                yield return course;
            }
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    // Row 19a. ADR-232: a C#-implemented interface with `suspend` and `Flow` members, completing.
    // Every result wire crosses (`CheckUpAsync` reads nine suspend slots, the `String?` and
    // `Throwable` results minting a handle each), the `Unit` slot twice, a C#-made flow
    // (`KotlinFlow<T>.From`) collected to the end and a Kotlin-made one passed back through the
    // C# feeder. The slot's delegate is the bridge object's own method, so no per-call key is
    // owed; anything minted per await or per element and not released is a positive delta.
    [Fact]
    public async Task CSharpAsyncInterface_Completion_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var bowl = new LeakBowl("Oreo's bowl");
            Assert.Equal("Oreo's bowl gave Oreo 4 scoops", await Feedingtime.Feeders.ServeAsync(bowl, "Oreo"));
            Assert.Equal("Oreo's bowl topped up twice", await Feedingtime.Feeders.TopUpAsync(bowl));
            Assert.Contains("spill=kibble everywhere", await Feedingtime.Feeders.CheckUpAsync(bowl, "Mylo"));
            Assert.Equal("kibble, tuna", await Feedingtime.Feeders.MenuAsync(bowl));
            Assert.Equal("kibble", await Feedingtime.Feeders.FirstMealsAsync(bowl, 1));

            using Feedingtime.IFeeder auto = Feedingtime.Feeders.AutoFeeder("Mylo's bowl");
            bowl.Menu = () => auto.Meals();
            Assert.Equal("tuna, salmon", await Feedingtime.Feeders.MenuAsync(bowl));

            using var shelf = new LeakShelf(3, 4);
            Assert.Equal(3 + 3 + 4, await Feedingtime.Feeders.TallyAsync(shelf));
            Assert.Equal(4, await Feedingtime.Feeders.RaidAsync(shelf, 1));
        });
    }

    // Row 19b. The FAULT rows: a C# fault caught in Kotlin, the same fault uncaught (the
    // ADR-128/130 envelope crosses on the same call), a synchronous throw before any Task exists,
    // and a flow that faults mid-stream after two elements were minted.
    [Fact]
    public async Task CSharpAsyncInterface_Fault_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var bowl = new LeakBowl("Mylo's bowl")
            {
                Feeding = async (_, token) =>
                {
                    await Task.Delay(1, token);
                    throw new InvalidOperationException("the bowl is empty");
                },
            };
            Assert.Contains("the bowl is empty", await Feedingtime.Feeders.ServeOrRecoverAsync(bowl, "Mylo"));
            Exception uncaught = await Assert.ThrowsAnyAsync<Exception>(() => Feedingtime.Feeders.ServeAsync(bowl, "Mylo"));
            Assert.Contains("the bowl is empty", uncaught.ToString());

            bowl.Feeding = (_, _) => throw new InvalidOperationException("Mylo sat in the bowl");
            Assert.Contains("Mylo sat in the bowl", await Feedingtime.Feeders.ServeOrRecoverAsync(bowl, "Mylo"));

            bowl.Menu = () => KotlinFlow<string>.From(SpillMidMeal());
            Assert.Contains("tipped mid-meal", await Feedingtime.Feeders.MenuOrRecoverAsync(bowl));
            Exception spilled = await Assert.ThrowsAnyAsync<Exception>(() => Feedingtime.Feeders.MenuAsync(bowl));
            Assert.Contains("tipped mid-meal", spilled.ToString());
        });
    }

    private static async IAsyncEnumerable<string> SpillMidMeal(
        [EnumeratorCancellation] CancellationToken token = default)
    {
        yield return "kibble";
        await Task.Delay(1, token);
        yield return "tuna";
        throw new InvalidOperationException("tipped mid-meal");
    }

    // Row 19c. The CANCEL rows: Kotlin's own timeout cancels a pending C# call, the C# caller's
    // token cancels a pending C# call, and the C# caller's token cancels a parked C# enumeration.
    // Each pending C# Task is awaited to its end before the row returns, so a hold that lasts only
    // until the abandoned Task completes is not read as a leak.
    [Fact]
    public async Task CSharpAsyncInterface_Cancellation_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(
            async () =>
            {
                var feeding = new TaskCompletionSource<Task<int>>(TaskCreationOptions.RunContinuationsAsynchronously);
                using var bowl = new LeakBowl("Oreo's bowl")
                {
                    Feeding = (_, token) =>
                    {
                        Task<int> pending = Task.Delay(Timeout.Infinite, token).ContinueWith(_ => -1, TaskScheduler.Default);
                        feeding.TrySetResult(pending);
                        return pending;
                    },
                };
                Assert.Equal("Oreo gave up waiting", await Feedingtime.Feeders.ServeOrGiveUpAsync(bowl, "Oreo", 20));
                await (await feeding.Task.WaitAsync(TimeSpan.FromSeconds(30))).WaitAsync(TimeSpan.FromSeconds(30));

                feeding = new TaskCompletionSource<Task<int>>(TaskCreationOptions.RunContinuationsAsynchronously);
                using (var cts = new CancellationTokenSource())
                {
                    Task<string> serving = Feedingtime.Feeders.ServeAsync(bowl, "Oreo", cts.Token);
                    Task<int> pending = await feeding.Task.WaitAsync(TimeSpan.FromSeconds(30));
                    cts.Cancel();
                    await Assert.ThrowsAnyAsync<OperationCanceledException>(() => serving);
                    await pending.WaitAsync(TimeSpan.FromSeconds(30));
                }

                var parked = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                bowl.Menu = () => KotlinFlow<string>.From(ParkAfterOne(parked, closed));
                using (var cts = new CancellationTokenSource())
                {
                    Task<string> listing = Feedingtime.Feeders.MenuAsync(bowl, cts.Token);
                    await parked.Task.WaitAsync(TimeSpan.FromSeconds(30));
                    cts.Cancel();
                    await Assert.ThrowsAnyAsync<OperationCanceledException>(() => listing);
                    await closed.Task.WaitAsync(TimeSpan.FromSeconds(30));
                }
            },
            iterations: 5);
    }

    private static async IAsyncEnumerable<string> ParkAfterOne(
        TaskCompletionSource parked, TaskCompletionSource closed,
        [EnumeratorCancellation] CancellationToken token = default)
    {
        try
        {
            yield return "kibble";
            parked.TrySetResult();
            await Task.Delay(Timeout.Infinite, token);
            yield return "never";
        }
        finally
        {
            closed.TrySetResult();
        }
    }

    // The generic `ILarder<int>` of row 19a: a boxed `T` per suspend result and per element.
    private sealed class LeakShelf(params int[] items) : Feedingtime.ILarder<int>
    {
        public async Task<int> FetchAsync(int shelf, CancellationToken cancellationToken = default)
        {
            await Task.Delay(1, cancellationToken);
            return items[shelf];
        }

        public KotlinFlow<int> Stock() => KotlinFlow<int>.From(Items());

        private async IAsyncEnumerable<int> Items([EnumeratorCancellation] CancellationToken token = default)
        {
            foreach (int item in items)
            {
                await Task.Delay(1, token);
                yield return item;
            }
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    // A C#-implemented `IThermostat` for row 19d, over hot flows C# created.
    private sealed class LeakBoiler : Feedingtime.IThermostat
    {
        public KotlinMutableStateFlow<int> LevelFlow { get; } = KotlinMutableStateFlow<int>.Create(18);
        public KotlinMutableStateFlow<int> DialFlow { get; } = KotlinMutableStateFlow<int>.Create(0);
        public KotlinMutableSharedFlow<string> Headlines { get; } = KotlinMutableSharedFlow<string>.Create(replay: 1);

        public KotlinStateFlow<int> Level => LevelFlow;
        public KotlinMutableStateFlow<int> Dial => DialFlow;
        public KotlinSharedFlow<string> News() => Headlines;
        public KotlinMutableSharedFlow<string> Bell() => Headlines;

        public void Dispose()
        {
            LevelFlow.Dispose();
            DialFlow.Dispose();
            Headlines.Dispose();
        }

        public ValueTask DisposeAsync()
        {
            Dispose();
            return default;
        }
    }

    private sealed class LeakPantry : Feedingtime.IPantry
    {
        public async Task<KotlinFlow<string>> DeliveryAsync(CancellationToken cancellationToken = default)
        {
            await Task.Delay(1, cancellationToken);
            return KotlinFlow<string>.From(LeakBowl.Courses());
        }

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    // Row 19d. ADR-232: hot flows a C# implementation created (`KotlinMutableStateFlow<T>.Create`,
    // `KotlinMutableSharedFlow<T>.Create`) read, written, collected and emitted from both sides,
    // each slot handing back a retained handle Kotlin releases; and a suspend member returning a
    // Flow, awaited then collected. Every flow is disposed, so its own handle and scope go too.
    [Fact]
    public async Task CSharpHotFlowInterface_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using (var boiler = new LeakBoiler())
            {
                Assert.Equal(18, Feedingtime.Feeders.Reading(boiler));
                boiler.LevelFlow.Value = 25;
                Assert.Equal(25, await Feedingtime.Feeders.WarmerThanAsync(boiler, 20));
                Assert.Equal(1, Feedingtime.Feeders.TurnUp(boiler));
                Assert.Equal(1, boiler.DialFlow.Value);
                Assert.True(boiler.Headlines.TryEmit("tuna"));
                Assert.Equal("tuna", await Feedingtime.Feeders.HeadlineAsync(boiler));
                Assert.True(Feedingtime.Feeders.Ring(boiler, "ding"));
                Assert.Equal(new[] { "ding" }, boiler.Headlines.ReplayCache);
            }

            using var pantry = new LeakPantry();
            Assert.Equal("kibble, tuna", await Feedingtime.Feeders.UnpackAsync(pantry));
        });
    }

    // A C#-implemented `ISitter` for the ADR-233 rows, here and in `CollectabilityTests`. Every slot
    // kind that carries a handle answers with a fresh one, so a handle the bridge minted and never
    // released is a positive delta. `Fault` makes the handle and collection slots throw.
    internal sealed class LeakSitter : Catsitter.ISitter
    {
        public Exception? Fault;

        public Catsitter.Kitten Adopt(string name) => Fault != null ? throw Fault : new Catsitter.Kitten(name);
        public string Cuddle(Catsitter.Kitten kitten) => Fault != null ? throw Fault : kitten.Name;
        public Catsitter.Kitten? Foster(Catsitter.Kitten? kitten) => kitten == null ? null : new Catsitter.Kitten(kitten.Name);
        public Catsitter.Crate<string> Pack(string item) => new(item);
        public int Unpack(Catsitter.Crate<int> crate) => crate.Item;
        public IReadOnlyList<string> Names() => Fault != null ? throw Fault : ["Oreo", "Mylo"];
        public int Weigh(IReadOnlyDictionary<string, int> weights) => weights.Count;
        public IReadOnlySet<string> Litter(IReadOnlyList<Catsitter.Kitten> kittens) =>
            kittens.Select(kitten => kitten.Name).ToHashSet();
        public Catsitter.Whisker Trim(Catsitter.Whisker whisker) => whisker;
        public int? Treats(string cat) => null;
        public bool? Indoor(string cat) => true;
        public Catsitter.Coat? Coat(string cat) => Catsitter.Coat.Caramel;
        public char Initial(string cat) => cat[0];
        public DateTimeOffset LastFed(DateTimeOffset at) => at;
        public TimeSpan Nap(TimeSpan length) => length;
        public Guid Chip(Guid id) => id;
        public byte[] Photo(byte[] snapshot) => snapshot;
        public int OnPurr(Func<string, int> callback) => callback("Oreo");
        public Func<int, string> Purrer() => times => $"purr x{times}";
        public Task<int> WhenHungryAsync(
            Func<string, CancellationToken, Task<int>> callback, CancellationToken cancellationToken = default) =>
            callback("Mylo", cancellationToken);
        public Catsitter.ITrinket<string> Favourite() => Fault != null ? throw Fault : Catsitter.Sitters.Bauble("tuna");
        public string Groom(string cat, int strokes, double minutes, bool gentle) => cat;
        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    // A C#-implemented `ITracker` whose flows carry a handle, an enum, a nullable string and a value
    // class. `Bolt` makes the handle flow fault after its first element.
    internal sealed class LeakLookout : Catsitter.ITracker
    {
        public bool Bolt;
        public Catsitter.Kitten Oreo = null!;

        public KotlinFlow<Catsitter.Kitten> Kittens() =>
            KotlinFlow<Catsitter.Kitten>.From(Bolt ? KittensThenBolt() : Each(new Catsitter.Kitten("Oreo")));
        public KotlinFlow<Catsitter.Coat> Coats() => KotlinFlow<Catsitter.Coat>.From(Each(Catsitter.Coat.Tuxedo));
        public KotlinFlow<string?> Sightings() => KotlinFlow<string?>.From(Each<string?>(null, "Mylo"));
        public KotlinFlow<Catsitter.Whisker> Whiskers() => KotlinFlow<Catsitter.Whisker>.From(Each(new Catsitter.Whisker(2)));
        public KotlinFlow<string> Feed { get; set; } = KotlinFlow<string>.From(Each("kibble"));
        public KotlinFlow<string>? Maybe() => KotlinFlow<string>.From(Each("tuna"));
        public KotlinFlow<object?> Anything() =>
            KotlinFlow<object?>.From(Each<object?>(new Catsitter.Kitten("Mylo"), 5, null));
        public KotlinMutableStateFlow<int> MoodFlow { get; } = KotlinMutableStateFlow<int>.Create(7);
        public KotlinStateFlow<int>? Mood => MoodFlow;
        public Task<KotlinStateFlow<int>> LevelAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult<KotlinStateFlow<int>>(MoodFlow);
        public void Dispose() => MoodFlow.Dispose();
        public ValueTask DisposeAsync()
        {
            Dispose();
            return default;
        }

        private static async IAsyncEnumerable<T> Each<T>(params T[] items)
        {
            foreach (T item in items)
            {
                await Task.Yield();
                yield return item;
            }
        }

        private static async IAsyncEnumerable<Catsitter.Kitten> KittensThenBolt(
            [EnumeratorCancellation] CancellationToken token = default)
        {
            await Task.Delay(1, token);
            yield return new Catsitter.Kitten("Oreo");
            throw new InvalidOperationException("Mylo bolted");
        }
    }

    // Row 20a. ADR-233: every handle-carrying slot kind of a C#-implemented interface, completing.
    // A class handle returned (`WrapOwned` through the retain export) and passed (borrowed), a
    // nullable handle, a generic handle each way, collections of strings and handles, a Kotlin
    // lambda in, a C# lambda out, a suspend lambda, a generic interface result, and `Uuid` and
    // `ByteArray` buffers. A handle a slot minted and never released is a positive delta.
    [Fact]
    public async Task CSharpInterfaceSlotVocabulary_Completion_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var sitter = new LeakSitter();
            Assert.Equal("Oreo purrs", Catsitter.Sitters.Adoption(sitter, "Oreo"));
            Assert.Equal("Mylo", Catsitter.Sitters.Cuddling(sitter, "Mylo"));
            Assert.Equal("Oreo/null", Catsitter.Sitters.Fostering(sitter, "Oreo"));
            Assert.Equal("tuna/3", Catsitter.Sitters.Crating(sitter, "tuna", 3));
            Assert.Equal("names=Oreo, Mylo; weight=2; litter=Mylo, Oreo", Catsitter.Sitters.Census(sitter));
            Assert.Contains("photo=1,2,3", Catsitter.Sitters.Records(sitter));
            Assert.Equal("40/purr x3", Catsitter.Sitters.Purring(sitter));
            Assert.Equal(104, await Catsitter.Sitters.HungerPangsAsync(sitter));
            Assert.Equal("favourite tuna", Catsitter.Sitters.Treasure(sitter));
        });
    }

    // Row 20b. The FAULT rows: a C# throw from a handle-returning slot, a passed-handle slot, a
    // collection slot and a generic-interface slot, each caught in Kotlin or surfacing to C#.
    [Fact]
    public async Task CSharpInterfaceSlotVocabulary_Fault_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(() =>
        {
            using var sitter = new LeakSitter { Fault = new InvalidOperationException("Oreo hid") };
            Assert.Contains("Oreo hid", Catsitter.Sitters.AdoptionOrRecover(sitter, "Oreo"));
            Assert.Contains("Oreo hid", Catsitter.Sitters.CensusOrRecover(sitter));
            Assert.Contains("Oreo hid", Assert.ThrowsAny<Exception>(() => Catsitter.Sitters.Cuddling(sitter, "Oreo")).ToString());
            Assert.Contains("Oreo hid", Assert.ThrowsAny<Exception>(() => Catsitter.Sitters.Treasure(sitter)).ToString());
            return Task.CompletedTask;
        });
    }

    // Row 20c. ADR-233 flow slots, completing: flows of a handle, an enum, a nullable string and a
    // value class collected to the end, a flow `var` replaced by Kotlin, nullable flows, and a
    // suspend member returning a hot flow.
    [Fact]
    public async Task CSharpInterfaceFlowSlots_Completion_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var lookout = new LeakLookout();
            Assert.Equal(
                "kittens=Oreo; coats=TUXEDO; sightings=null, Mylo; whiskers=2",
                await Catsitter.Sitters.SightingsAsync(lookout));
            Assert.Equal("kibble -> salmon", await Catsitter.Sitters.RefeedAsync(lookout, "salmon"));
            Assert.Equal("maybe=tuna mood=7", await Catsitter.Sitters.MaybesAsync(lookout));
            Assert.Equal(7, await Catsitter.Sitters.LevellingAsync(lookout));
        });
    }

    // Row 20d. The FAULT row for flow slots: a handle flow faulting after one element was minted.
    [Fact]
    public async Task CSharpInterfaceFlowSlots_Fault_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var lookout = new LeakLookout { Bolt = true };
            Exception fault = await Assert.ThrowsAnyAsync<Exception>(() => Catsitter.Sitters.SightingsAsync(lookout));
            Assert.Contains("Mylo bolted", fault.ToString());
        });
    }

    // Row 20e. ADR-233: `KotlinMutableStateFlow<T>.Create` and `KotlinMutableSharedFlow<T>.Create`
    // own the scope handle they mint, and `Dispose` releases it. Measured without a GC or a settle
    // on purpose: a scope only the finalizer releases stays live here.
    [Fact]
    public void CreatedHotFlows_DisposeReleasesTheirScope_WithoutAGc()
    {
        long before = NugetMarshal.LiveHandles;
        for (int i = 0; i < 20; i++)
        {
            using (KotlinMutableStateFlow<int> state = KotlinMutableStateFlow<int>.Create(i))
            {
                state.Value = i + 1;
                Assert.Equal(i + 1, state.Value);
            }

            using (KotlinMutableSharedFlow<string> shared = KotlinMutableSharedFlow<string>.Create(replay: 1))
            {
                Assert.True(shared.TryEmit("Mylo"));
            }
        }

        Assert.Equal(before, NugetMarshal.LiveHandles);
    }

    // Row 20f. ADR-233 amendment: a class member returning a lambda hands C# a delegate over a
    // Kotlin function handle. The box is disposed and the delegate dropped undisposed, so the GC
    // releasing that handle is the only thing that brings the count back.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void DropUndisposedPurrer()
    {
        using var box = new Cattery.Purrbox("Mylo");
        Func<int, string> purrer = box.Purrer();
        Assert.Equal("Mylo purrs x2", purrer(2));
    }

    [Fact]
    public void ClassMemberReturnedLambda_DroppedDelegate_ReleasedByTheGc() =>
        AssertReleasedByTheGc("class-member returned lambda delegates", DropUndisposedPurrer);

    private static async IAsyncEnumerable<T> Drip<T>(params T[] items)
    {
        foreach (T item in items)
        {
            await Task.Yield();
            yield return item;
        }
    }

    private sealed class LeakRelay(Cattery.IIntercom wall) : Cattery.IIntercom
    {
        public KotlinStateFlow<int> Chime() => wall.Chime();
        public KotlinMutableStateFlow<int> Alert() => wall.Alert();
        public KotlinSharedFlow<string> Gossip() => wall.Gossip();
        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    private sealed class LeakPorch : Cattery.IPawprints
    {
        public KotlinFlow<Cattery.Paw> Trail { get; set; } = KotlinFlow<Cattery.Paw>.From(Drip(new Cattery.Paw(3)));
        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    private sealed class LeakFurball : Cattery.IHairballs
    {
        public KotlinFlow<Exception> Coughs() =>
            KotlinFlow<Exception>.From(Drip<Exception>(new InvalidOperationException("Oreo at 3am")));
        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    // Row 20g. ADR-233 amendment, completing: a Kotlin-backed member function's hot flows handed
    // back, a Kotlin-backed and a class `var` flow set to a C#-made flow, a `var` flow slot of an
    // unsighted element, and a Flow of a `Throwable`.
    [Fact]
    public async Task CSharpInterfaceSlotVocabularyAmendment_Completion_ReturnsToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            await using (Cattery.IIntercom wall = Cattery.Catteries.WallIntercom())
            {
                using var relay = new LeakRelay(wall);
                Assert.StartsWith("chime=", await Cattery.Catteries.BuzzAsync(relay));
            }

            using (Cattery.IChute chute = Cattery.Catteries.SteelChute())
            {
                chute.Feed = KotlinFlow<string>.From(Drip("salmon"));
                Assert.Equal("salmon", await Cattery.Catteries.DrainAsync(chute));
            }

            using (Cattery.Hopper hopper = Cattery.Catteries.Hopper(["kibble"]))
            using (Cattery.Hopper other = Cattery.Catteries.Hopper(["tuna"]))
            {
                hopper.Source = other.Source;
                Assert.Equal("tuna", await hopper.DrainAsync());
                hopper.Source = KotlinFlow<string>.From(Drip("duck"));
                Assert.Equal("duck", await hopper.DrainAsync());
            }

            Assert.Equal("3 -> 7", await Cattery.Catteries.RetraceAsync(new LeakPorch(), 7));
            Assert.Equal("Oreo at 3am", await Cattery.Catteries.InspectAsync(new LeakFurball()));
        });
    }

    // ADR-237: a C# catnip toy, so an `object` crossing into Kotlin carries a C#-implemented
    // interface through its bridge and comes back as the same object.
    internal sealed class LeakCatnip : Hoards.ICatnip
    {
        public string Sniff() => "Oreo catnip";
        public void Dispose() { }
    }

    // ADR-237: a C# cache whose `Any` slots echo, and a sniffer and pouncer with the member-level
    // `R` erased to `object`.
    internal sealed class LeakCache : Hoards.ICache
    {
        public object Favourite_ = "Mylo";
        public object? Hide(object? value) => value;
        public object Favourite() => Favourite_;
        public void Dispose() { }
    }

    internal sealed class LeakSniffer : Hoards.ISniffer<string>
    {
        public string Item => "Mylo";
        public object? Sniff(Func<string, object?> f) => f(Item);
        public void Dispose() { }
    }

    internal sealed class LeakPouncer : Hoards.IPouncer
    {
        public object? Pounce(Func<string, object?> f) => f("Oreo");
        public void Dispose() { }
    }

    // Row 21a. ADR-237: every handle-backed value on the `Any` wire, in both directions. An
    // exported class wrapper passed in (borrowed) and minted out (owned), a C#-implemented
    // interface's bridge, a list holding a handle, a class owner's `var` and member, a
    // Kotlin-backed interface, and a star-projected generic class result. A handle the wire minted
    // for an `object` and never released is a positive delta.
    [Fact]
    public void AnyWire_HandleBackedValues_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using (var mouse = new Hoards.Mouse("Oreo"))
            {
                Assert.Equal("Mouse Oreo", Hoards.Hoarding.Kind(mouse));
                using var back = Assert.IsType<Hoards.Mouse>(Hoards.Hoarding.Stash(mouse));
            }

            using (var minted = Assert.IsType<Hoards.Mouse>(Hoards.Hoarding.Fetch("mouse")))
                Assert.Equal("Mylo squeaks", minted.Squeak());

            var catnip = new LeakCatnip();
            Assert.Same(catnip, Hoards.Hoarding.StashOrNull(catnip));
            Assert.Equal("Catnip Oreo catnip", Hoards.Hoarding.Kind(catnip));

            var listed = Assert.IsAssignableFrom<IReadOnlyList<object?>>(Hoards.Hoarding.Fetch("list"));
            Assert.Equal(3, listed.Count);

            using (var hoard = new Hoards.Hoard(new Hoards.Mouse("Mylo")))
            {
                (hoard.Last as IDisposable)?.Dispose();
                hoard.Last = catnip;
                Assert.Same(catnip, hoard.Keep(catnip));
                Assert.Single(hoard.All());
                using Hoards.Satchel<object?> bag = hoard.Bag;
                Assert.Same(catnip, bag.Item);
            }

            using (Hoards.ILoot thief = Hoards.Hoarding.Thief())
                Assert.IsAssignableFrom<IReadOnlyList<object?>>(thief.Grab(catnip));

            using (Hoards.Satchel<object?> satchel = Hoards.Hoarding.Rummage())
            using (var item = Assert.IsType<Hoards.Mouse>(satchel.Item))
                Assert.Equal("Oreo squeaks", item.Squeak());

            var cache = new LeakCache { Favourite_ = catnip };
            Assert.Equal("Catnip Oreo catnip", Hoards.Hoarding.Hiding(cache, catnip));
            Assert.Same(catnip, Hoards.Hoarding.Favourite(cache));
            Assert.Equal("5 MYLO", Hoards.Hoarding.Sniffing(new LeakSniffer()));
            Assert.Equal("Oreo squeaks", Hoards.Hoarding.Pouncing(new LeakPouncer()));
        });
    }

    // Row 21c. ADR-237: a generic value class on the `Any` wire. Each crossing mints a Kotlin box
    // of the struct and one of its underlying, both released by the call; a Kotlin-made one around
    // a handle hands C# that handle's wrapper inside the struct, released by its `using`.
    [Fact]
    public void AnyWire_GenericValueClass_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            using (var mouse = new Hoards.Mouse("Oreo"))
            {
                Assert.Equal("Bow Mouse Oreo", Hoards.Hoarding.Kind(new Hoards.Bow<Hoards.Mouse>(mouse)));
                var back = Assert.IsType<Hoards.Bow<object?>>(Hoards.Hoarding.Stash(new Hoards.Bow<Hoards.Mouse>(mouse)));
                using var held = Assert.IsType<Hoards.Mouse>(back.Ribbon);
            }

            Assert.Equal("red", Assert.IsType<Hoards.Bow<object?>>(Hoards.Hoarding.Tie("red")).Ribbon);
            var tied = Assert.IsType<Hoards.Bow<object?>>(Hoards.Hoarding.TieMouse("Mylo"));
            using (var mylo = Assert.IsType<Hoards.Mouse>(tied.Ribbon))
                Assert.Equal("Mylo squeaks", mylo.Squeak());

            using (var box = new TestLibrary.Cat.Box<object>(new Hoards.Bow<string>("red")))
                Assert.Equal("red", Assert.IsType<Hoards.Bow<object?>>(box.Value).Ribbon);

            Assert.ThrowsAny<ArgumentException>(() => Hoards.Hoarding.Kind(new Hoards.Bow<string>("torn")));
        });
    }

    // Row 21b. ADR-237: star-projected flows whose elements are handles, read from Kotlin to C#
    // and handed from C# to Kotlin. Each element's wrapper, and each flow, is released.
    [Fact]
    public async Task AnyWire_StarProjectedFlows_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using (var hoard = new Hoards.Hoard(null))
            using (KotlinFlow<object?> spoils = hoard.Spoils())
            {
                await foreach (object? item in spoils) (item as IDisposable)?.Dispose();
            }

            using (Hoards.ILoot thief = Hoards.Hoarding.Thief())
            using (KotlinFlow<object?> everything = thief.Everything())
            {
                await foreach (object? _ in everything) { }
            }

            Assert.Equal("Mouse Mylo", await Hoards.Hoarding.SiftAsync(OneMouse()));

            using var lookout = new LeakLookout();
            Assert.Equal("Kitten Mylo, Int 5, null", await Catsitter.Sitters.AnythingsAsync(lookout));
        });
    }

    private static async IAsyncEnumerable<object?> OneMouse()
    {
        await Task.Yield();
        yield return new Hoards.Mouse("Mylo");
    }

    // ADR-220
    // A bounded own `T` inside `Sticker<T>`: `Comparable<T>` erases with no trampoline, `Enum<T>` and
    // the intersection run in the trampoline. Each string and enum box is +1 in and -1 once
    // read, and the book is +1/-1 through its dispose. Net zero.
    [Fact]
    public void GenericValueClass_BoundedOwnTypeParameter_ReturnsToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.Equal("Oreo", TestLibrary.Stickers.Stickers.Higher(
                new TestLibrary.Stickers.Sticker<string>("Mylo"),
                new TestLibrary.Stickers.Sticker<string>("Oreo")).Value);
            Assert.Equal(Mood.Sleepy, TestLibrary.Stickers.Stickers.NapOf(
                new TestLibrary.Stickers.Sticker<Mood>(Mood.Sleepy)).Value);
            Assert.Equal("Oreo", TestLibrary.Stickers.Stickers.Loudest(
                new TestLibrary.Stickers.Sticker<string>("Oreo")).Value);
            using var book = new TestLibrary.Stickers.StickerBook(
                new TestLibrary.Stickers.Sticker<string>("Mylo's"),
                new TestLibrary.Stickers.Sticker<int>(1));
            Assert.Equal(Mood.Happy, book.NapOf(
                new TestLibrary.Stickers.Sticker<Mood>(Mood.Happy)).Value);
        });
    }

    // ADR-225
    // A generic value class member: the receiver crosses on the class's own `T` wire, so a
    // `Sticker<string>` or `Sticker<Cat>` receiver mints one box per call and releases it, a `T`
    // parameter does the same, and a `T` result is read back once. A secondary constructor returns
    // the underlying, and a phantom receiver is a plain `long` that owns nothing. Ledger per
    // iteration: oreo and mylo +1 each, every box +1/-1, every returned cat +1 and its dispose -1,
    // the two disposes -1 each. Net zero.
    [Fact]
    public void GenericValueClassMembers_ReceiverParameterAndResultBoxes_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using var oreo = new Cat("Oreo", 4);
            using var mylo = new Cat("Mylo", 5);
            Assert.Equal("sticker of Oreo", new TestLibrary.Stickers.Sticker<string>("Oreo").Describe());
            Assert.True(new TestLibrary.Stickers.Sticker<Cat>(oreo).Shiny);
            using (Cat peeled = new TestLibrary.Stickers.Sticker<Cat>(oreo).Peeled)
                Assert.Equal("Oreo", peeled.Name);
            using (Cat swapped = new TestLibrary.Stickers.Sticker<Cat>(oreo).Swap(mylo).Value)
                Assert.Equal("Mylo", swapped.Name);
            using (Cat handed = new TestLibrary.Stickers.Sticker<string>("x").Hand(mylo))
                Assert.Equal("Mylo", handed.Name);
            Assert.Equal("abab", new TestLibrary.Stickers.Sticker<string>("ab", 2).Value);
            using (Cat picked = new TestLibrary.Stickers.Sticker<Cat>(oreo, mylo, false).Value)
                Assert.Equal("Oreo", picked.Name);
            Assert.Equal("decal #7 on Oreo", new TestLibrary.Stickers.Decal<Cat>(7L).StickOn("Oreo"));
            Assert.Equal(9L, new TestLibrary.Stickers.Decal<Cat>("9").Serial);
            using (Cat worn = TestLibrary.Stickers.Stickers.Collar("t1").Wear(oreo))
                Assert.Equal("Oreo", worn.Name);
            Assert.Equal("dozing SLEEPY", TestLibrary.Stickers.Stickers.Doze().Label());
        });
    }

    // ADR-225
    // A faulting generic value class member, constructor and default receiver: the receiver box is
    // released on the throw path, the secondary constructor's argument boxes too, and a default
    // `Badge<string>` is refused in C# before any box is minted. Net zero.
    [Fact]
    public void GenericValueClassMembers_FaultPaths_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            Assert.Throws<KotlinInvalidOperationException>(
                () => new TestLibrary.Stickers.Sticker<string>("torn").Tear());
            Assert.Throws<KotlinArgumentException>(
                () => new TestLibrary.Stickers.Sticker<int>(3, -1, true));
            Assert.Throws<KotlinArgumentException>(
                () => new TestLibrary.Stickers.Sticker<int>(3).Swap(-1));
            Assert.Throws<KotlinFormatException>(() => new TestLibrary.Stickers.Decal<Cat>("Oreo"));
            Assert.Throws<ArgumentException>(
                () => default(TestLibrary.Stickers.Badge<string>).Describe());
        });
    }

    // ADR-225
    // A `suspend` member of a generic value class: the receiver box lives until the export reads it,
    // and the `T` result is read back once through the task. Net zero.
    [Fact]
    public async Task GenericValueClassSuspendMembers_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var oreo = new Cat("Oreo", 4);
            using (Cat later = await new TestLibrary.Stickers.Sticker<Cat>(oreo).PeelLaterAsync())
                Assert.Equal("Oreo", later.Name);
            Assert.Equal("sticker of Mylo",
                await new TestLibrary.Stickers.Sticker<string>("Mylo").DescribeLaterAsync());
            await Assert.ThrowsAsync<KotlinArgumentException>(
                () => new TestLibrary.Stickers.Sticker<int>(-1).PeelLaterAsync());
        });
    }

    // ADR-228
    // A generic value class (and `Nick`, a value class over a nullable `String?`) at an erased
    // position crosses as one Kotlin box per value. `Wrap<T>` mints the box through the value
    // class's box export with `owned = true`, and the caller's `finally` disposes it; a read takes
    // one retained box, which the registered factory unboxes and disposes. Ledger per iteration:
    // C#-built Box<Sticker<int>> sticker box +1/-1, box_create +1, `.Value` read +1/-1, box_dispose
    // -1; Kotlin-made Box<Sticker<string>> box_create +1, read +1/-1 (plus its inner string box
    // +1/-1), dispose -1; Box<Sticker<string?>?> blank read +1/-1, none read 0; Box<Nick?>
    // present read +1/-1; the courier +1/-1; the `OnSticker` delegate read +1, released once
    // collected -1, its payload box +1/-1 and result +1/-1; the C#
    // lambda's payload +1/-1 and result +1/-1. Net zero.
    [Fact]
    public void GenericValueClass_ErasedPositions_ReturnToBaseline()
    {
        AssertNoLeak(() =>
        {
            using (var box = new Box<TestLibrary.Stickers.Sticker<int>>(new TestLibrary.Stickers.Sticker<int>(4)))
                Assert.Equal(4, box.Value.Value);
            using (var box = TestLibrary.Stickers.StickerSwaps.BoxedOf("Oreo"))
                Assert.Equal("Oreo", box.Value.Value);
            using (var box = TestLibrary.Stickers.StickerSwaps.BoxedBlank())
                Assert.Null(box.Value!.Value.Value);
            using (var box = TestLibrary.Stickers.StickerSwaps.BoxedNone())
                Assert.Null(box.Value);
            using (var box = TestLibrary.Stickers.StickerSwaps.NickBoxOrNone(true))
                Assert.Null(box.Value!.Value.Name);
            using var courier = new TestLibrary.Stickers.StickerCourier("Oreo's desk");
            Assert.Equal(4, courier.OnSticker.Invoke(new TestLibrary.Stickers.Sticker<string>("Oreo")).Value);
            Assert.Equal(20, courier.Relay(
                new TestLibrary.Stickers.Sticker<int>(2),
                sticker => new TestLibrary.Stickers.Sticker<int>(sticker.Value * 10)));
        });
    }

    // ADR-228
    // The fault paths: Sticker's `init` refuses -1 when Kotlin re-wraps it. The box export throws
    // before anything is minted (C#-built Box), the C# lambda's result throws inside Kotlin after
    // its payload box was minted and released, and the MutableStateFlow write throws before the
    // element is stored. Net zero; a positive delta is a box the error path minted and nobody owns.
    [Fact]
    public void GenericValueClass_ErasedPositionInitThrows_ReturnsToBaseline()
    {
        using var courier = new TestLibrary.Stickers.StickerCourier("Mylo's desk");
        AssertNoLeak(() =>
        {
            Assert.ThrowsAny<ArgumentException>(() =>
                new Box<TestLibrary.Stickers.Sticker<int>>(new TestLibrary.Stickers.Sticker<int>(-1)));
            Assert.ThrowsAny<ArgumentException>(() => courier.Relay(
                new TestLibrary.Stickers.Sticker<int>(2),
                _ => new TestLibrary.Stickers.Sticker<int>(-1)));
            Assert.ThrowsAny<ArgumentException>(() =>
                courier.Board.Value = new TestLibrary.Stickers.Sticker<int>(-1));
        });
    }

    // ADR-228
    // The async erased positions: every awaited result is one retained box the factory unboxes
    // and disposes, every awaited parameter one owned box the `finally` disposes, every flow
    // element one box per emission, and each completed call releases its job handle. Ledger per
    // iteration: courier +1/-1; BumpLater box +1/-1, result +1/-1, job +1/-1; NickLater(true)
    // result +1/-1, job +1/-1; NickLater(false) job +1/-1; LaterOf("Mylo") argument +1/-1, result
    // +1/-1 (plus the inner string), job +1/-1; Stream two elements +1/-1 each and their cats
    // +1/-1; Nicks two present elements +1/-1; Total three C# elements +1/-1 each, job +1/-1;
    // the `RefileLater` delegate read +1, released once collected -1, its payload +1/-1, result
    // +1/-1. Net zero.
    [Fact]
    public async Task GenericValueClass_AsyncErasedPositions_ReturnToBaseline()
    {
        await AssertNoLeakAsync(async () =>
        {
            using var courier = new TestLibrary.Stickers.StickerCourier("Oreo's desk");
            Assert.Equal(6, (await courier.BumpLaterAsync(new TestLibrary.Stickers.Sticker<int>(5))).Value);
            Assert.Null((await courier.NickLaterAsync(true))!.Value.Name);
            Assert.Null(await courier.NickLaterAsync(false));
            Assert.Equal("Mylo", (await courier.LaterOfAsync("Mylo")).Value);
            await foreach (TestLibrary.Stickers.Sticker<Cat> sticker in courier.Stream())
            {
                using Cat cat = sticker.Value;
                Assert.NotEmpty(cat.Name);
            }
            int nicks = 0;
            await foreach (TestLibrary.Stickers.Nick? _ in courier.Nicks())
                nicks++;
            Assert.Equal(3, nicks);
            Assert.Equal(6, await courier.TotalAsync(StickerDrip(1, 2, 3)));
            Assert.Equal(6, (await courier.RefileLater(
                new TestLibrary.Stickers.Sticker<int>(5), CancellationToken.None)).Value);
        });
    }

    private static async IAsyncEnumerable<TestLibrary.Stickers.Sticker<int>> StickerDrip(params int[] values)
    {
        foreach (int value in values)
        {
            await Task.Yield();
            yield return new TestLibrary.Stickers.Sticker<int>(value);
        }
    }
}

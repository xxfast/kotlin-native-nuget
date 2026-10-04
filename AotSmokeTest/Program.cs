using TestLibrary.Cat;
using Kotlin.Native.Interop;

namespace AotSmokeTest;

/// <summary>
/// ADR-102 proof-of-done lane. One step per forward callback shape - every one of them makes
/// Kotlin call back into managed code, which today needs a runtime-built native-to-managed thunk.
/// Under the JIT all eight pass (the same paths IntegrationTests covers); the question this app
/// exists to answer is what happens with no JIT at all:
///
///     dotnet publish AotSmokeTest -r win-x64 -c Release -p:PublishAot=true
///     ./bin/Release/net10.0/win-x64/publish/AotSmokeTest.exe
///
/// Every step is labelled and flushed BEFORE it runs, because an AOT failure in a native->managed
/// frame can be process-fatal (ExecutionEngineException / FailFast) and unwind nothing: the last
/// flushed label is then the per-shape evidence. Exit code 0 only if all eight report PASS.
///
/// Step 7 is not a callback: it is the ADR-098 `Char` wire (by-value U2 and the `Char?`
/// `out ushort` slot), on this lane because the .NET marshaller's `char` handling differs between
/// the JIT and NativeAOT, so a JIT-only measurement does not cover a `PublishAot` consumer.
///
/// Step 8 is not a callback either: it is the ADR-199 `NugetFactory<T>` slot, a generic sealed
/// instantiation only the consumer chose (`Hamper<Outcome<long>>`), read back through an erased
/// `T`. The slot is a static field set by a generic static constructor, which ADR-199 only infers
/// survives NativeAOT.
///
/// Cast: Oreo (black with a white middle, drama king at dinner) and Mylo (brown and creamy, treat
/// vacuum), plus Rex the C#-implemented dog, who exists only to be dispatched back into.
/// </summary>
internal static class Program
{
    private static int _failures;

    private static async Task<int> Main()
    {
        Console.WriteLine("== ADR-102 AOT forward-callback smoke test ==");
        Console.WriteLine($"runtime: {System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription}");
        Console.Out.Flush();

        // Measure exact counters before callbacks can leave asynchronous cleaner work behind.
        await Step("1/8 coexistence  (Oreo and Mylo have separate native runtimes)", CoexistenceStep);
        await Step("2/8 flow          (Oreo narrates dinner)", FlowStep);
        await Step("3/8 suspend       (greeting Oreo asynchronously)", SuspendStep);
        await Step("4/8 percall-lambda(describing Oreo through a C# lambda)", PerCallLambdaStep);
        await Step("5/8 stored-cb     (Mylo's mood listener)", StoredCallbackStep);
        await Step("6/8 iface-bridge  (Rex the C# dog crosses into Kotlin)", InterfaceBridgeStep);
        await Step("7/8 char-wire     (Mylo's Hangul name tag, by value and Char?)", CharWireStep);
        await Step("8/8 generic-sealed(Oreo's dinner in a consumer-chosen hamper slot)", GenericSealedSlotStep);

        Console.WriteLine(_failures == 0
            ? "== ALL 8 SHAPES PASS =="
            : $"== {_failures} SHAPE(S) FAILED ==");
        Console.Out.Flush();
        return _failures == 0 ? 0 : 1;
    }

    /// <summary>Runs one labelled step; a throwing step is a FAIL, not an abort - the remaining
    /// shapes still get measured.</summary>
    private static async Task Step(string label, Func<Task> body)
    {
        Console.WriteLine($"-- {label}: running...");
        Console.Out.Flush();
        try
        {
            await body();
            Console.WriteLine($"PASS {label}");
        }
        catch (Exception ex)
        {
            _failures++;
            Console.WriteLine($"FAIL {label}: {ex.GetType().FullName}: {ex.Message}");
            Console.WriteLine(ex.StackTrace);
        }
        Console.Out.Flush();
    }

    private static void Expect(bool condition, string what)
    {
        if (!condition) throw new InvalidOperationException($"expectation failed: {what}");
    }

    private static Task CoexistenceStep()
    {
        long first = TestLibrary.NugetMarshal.LiveHandles;
        long second = TestCompanion.NugetMarshal.LiveHandles;
        using (var oreo = new TestLibrary.Coexistence.Probe("Oreo"))
        using (var mylo = new TestCompanion.Coexistence.Probe("Mylo"))
        {
            Expect(oreo.Name == "Oreo" && mylo.Name == "Mylo", "each publisher reads its own wrapper");
            Expect(TestLibrary.NugetMarshal.LiveHandles == first + 1, "first publisher owns one handle");
            Expect(TestCompanion.NugetMarshal.LiveHandles == second + 1, "second publisher owns one handle");
        }
        Expect(TestLibrary.NugetMarshal.LiveHandles == first, "first publisher releases its handle");
        Expect(TestCompanion.NugetMarshal.LiveHandles == second, "second publisher releases its handle");
        Expect(TestLibrary.Coexistence.CoexistenceSample.ReverseRoundTrip("Mylo") == "Oreo meets Mylo",
            "first reverse registration");
        Expect(TestCompanion.Coexistence.CoexistenceSample.ReverseRoundTrip("Oreo") == "Mylo meets Oreo",
            "second reverse registration");
        foreach (Action fail in new Action[] { TestLibrary.Coexistence.CoexistenceSample.FailCustom,
                     TestCompanion.Coexistence.CoexistenceSample.FailCustom })
        {
            bool caught = false;
            try { fail(); }
            catch (KotlinException error)
            {
                caught = error.InnerException is KotlinArgumentException;
            }
            Expect(caught, "shared custom exception identity and mapped cause");
        }
        return Task.CompletedTask;
    }

    // Shape 4 in ADR-102's table, first here: the only failure verified live on a JIT-less
    // runtime (Mac Catalyst arm64 Release, "AOT NOT FOUND: (wrapper native-to-managed)").
    private static async Task FlowStep()
    {
        using var feeder = new CatFeeder("Oreo");
        var announcements = new List<string>();
        await foreach (string item in feeder.MealAnnouncements)
        {
            announcements.Add(item);
        }

        Expect(announcements.Count == 3, $"3 meal announcements, got {announcements.Count}");
        Expect(announcements[0] == "Oreo is hungry", $"first is '{announcements[0]}'");
        Expect(announcements[2] == "Oreo is full", $"last is '{announcements[2]}'");
    }

    // Shape 5: suspend continuation resumption - Kotlin calls the NugetAsyncCallback to resume us.
    private static async Task SuspendStep()
    {
        string greeting = await AsyncFunctions.FetchGreetingAsync("Oreo");
        Expect(greeting == "Hello, Oreo!", $"greeting was '{greeting}'");
    }

    // Shape 1: a per-call lambda parameter. Kotlin invokes the C# lambda mid-call, so the value
    // returned can only be right if the callback actually crossed back into managed code.
    private static Task PerCallLambdaStep()
    {
        using var cat = new Cat("Oreo", 9);
        bool lambdaRan = false;
        string described = cat.DescribeWith(name =>
        {
            lambdaRan = true;
            return $"This cat is called {name}";
        });

        Expect(lambdaRan, "the C# lambda body never ran");
        Expect(described == "This cat is called Oreo", $"described as '{described}'");
        return Task.CompletedTask;
    }

    // Shape 2: a stored callback - subscribe, trigger, verify, dispose, verify silence.
    private static Task StoredCallbackStep()
    {
        using var mylo = new Cat("Mylo", 9);
        var moods = new List<string>();
        IDisposable subscription = mylo.AddMoodListener(mood => moods.Add(mood.ToString()));

        mylo.TriggerMoodChange(Mood.Happy);
        Expect(moods.Count == 1, $"1 mood after trigger, got {moods.Count}");
        Expect(moods[0] == "Happy", $"mood was '{moods[0]}'");

        subscription.Dispose();
        mylo.TriggerMoodChange(Mood.Grumpy);
        Expect(moods.Count == 1, $"no further moods after dispose, got {moods.Count}");
        return Task.CompletedTask;
    }

    // Shape 3: a C#-implemented Kotlin interface. "Woof!" cannot come from Kotlin or from an echo
    // of our own call - only from Kotlin dispatching through the bridge slot into Rex.
    private static Task InterfaceBridgeStep()
    {
        using var oreo = new Cat("Oreo", 9);
        using IPet rex = new Dog("Rex");

        oreo.Befriend(rex);
        using IPet friend = oreo.ClosestFriend();
        string spoken = friend.Speak();
        string interview = oreo.Interview(rex);

        Expect(spoken == "Woof!", $"closest friend said '{spoken}'");
        Expect(interview == "Rex says: Woof!", $"interview returned '{interview}'");
        return Task.CompletedTask;
    }

    // Shape 7: the ADR-098 Char wire with no JIT. Every non-null payload is above U+00FF on
    // purpose: under NativeAOT a bare (unattributed) `char` widens one byte and so reads Latin-1
    // back correctly, while '한' (U+D55C) still loses its high byte. U+D55C also has the top bit
    // set, so a signed 16-bit slot where the wire wants `ushort` cannot pass either.
    private static Task CharWireStep()
    {
        // Char? property, both directions: Mylo's tag starts blank, gets his Hangul initial, and
        // is wiped again (null after non-null catches a setter that ignores the has-value flag).
        using var tag = new TestLibrary.Clinic.Tag(null);
        Expect(tag.Initial == null, $"blank tag reads {Describe(tag.Initial)}");
        tag.Initial = '한';
        Expect(tag.Initial == '한', $"Char? property reads {Describe(tag.Initial)}");
        tag.Initial = null;
        Expect(tag.Initial == null, $"wiped tag reads {Describe(tag.Initial)}");

        // Char? parameter and return in one call: the has-value pair plus the `out ushort` slot.
        Expect(tag.Echo(null) == null, $"Echo(null) is {Describe(tag.Echo(null))}");
        char? echoed = tag.Echo('한');
        Expect(echoed == '한', $"Echo('한') is {Describe(echoed)}");

        // By-value `[MarshalAs(U2)] char` parameter and `[return: U2]` char return.
        using var oreo = new TestLibrary.Clinic.Patient("Oreo");
        string engraved = oreo.Tag('한');
        Expect(engraved == "한-Oreo", $"Tag('한') engraved '{engraved}'");
        using var mylo = new TestLibrary.Clinic.Patient("한마일로");
        char initial = mylo.Initial();
        Expect(initial == '한', $"Initial() is {Describe(initial)}");

        // By-value char at the property-getter position, and the List<Char> read
        // (nuget_wrap_char's element wire), both on fixtures that already exist.
        using var readings = new TestLibrary.Clinic.Readings();
        char glyph = readings.Glyph;
        Expect(glyph == 'Ω', $"Glyph is {Describe(glyph)}");
        IReadOnlyList<char> marks = readings.Marks();
        Expect(marks.Count == 2 && marks[0] == 'é' && marks[1] == '日',
            $"Marks() is [{string.Join(", ", marks.Select(mark => Describe(mark)))}]");
        return Task.CompletedTask;
    }

    // Shape 8: ADR-199's consumer-chosen instantiation at an erased slot. Outcome<string> has a
    // Kotlin position (so it may hit a static Factories entry); Outcome<long> has none, so its
    // read can only succeed through the NugetFactory<T> slot, and Outcome.Ok<short> only through
    // the arm's own slot.
    private static Task GenericSealedSlotStep()
    {
        using var dinner = new TestLibrary.Outcome.Outcome.Ok<string>("Oreo");
        using var hamper = new TestLibrary.Outcome.Hamper<TestLibrary.Outcome.Outcome<string>>(dinner);
        using (TestLibrary.Outcome.Outcome<string> item = hamper.Item)
        {
            Expect(item is TestLibrary.Outcome.Outcome.Ok<string> { Value: "Oreo" },
                $"Hamper<Outcome<string>>.Item is {item.GetType().Name}");
        }

        using var treats = new TestLibrary.Outcome.Outcome.Ok<long>(9_000_000_000L);
        using var stash = new TestLibrary.Outcome.Hamper<TestLibrary.Outcome.Outcome<long>>(treats);
        using (TestLibrary.Outcome.Outcome<long> item = stash.Item)
        {
            Expect(item is TestLibrary.Outcome.Outcome.Ok<long> { Value: 9_000_000_000L },
                $"Hamper<Outcome<long>>.Item is {item.GetType().Name}");
        }

        // Typed as the arm: only the arm's own slot can answer, not the base's.
        using var four = new TestLibrary.Outcome.Outcome.Ok<short>(4);
        using var pouch = new TestLibrary.Outcome.Hamper<TestLibrary.Outcome.Outcome.Ok<short>>(four);
        using (TestLibrary.Outcome.Outcome.Ok<short> item = pouch.Item)
        {
            Expect(item.Value == 4, $"Hamper<Outcome.Ok<short>>.Item.Value is {item.Value}");
        }
        return Task.CompletedTask;
    }

    /// <summary>The observed UTF-16 code unit as hex, so a failure tells a truncated high byte
    /// (U+005C for '한') apart from a lost character (U+FFFD).</summary>
    private static string Describe(char? value) =>
        value is char c ? $"U+{(int)c:X4}" : "null";

    private sealed class Dog : IPet
    {
        public string Name { get; }
        public int Legs => 4;
        public string? Nickname => null;
        public string Vibe => "waggy";
        public Dog(string name) => Name = name;
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
        public void Nap() { }
        public void Dispose() { }
    }
}

using TestLibrary.Cat;

namespace AotSmokeTest;

/// <summary>
/// ADR-102 proof-of-done lane. One step per forward callback shape - every one of them makes
/// Kotlin call back into managed code, which today needs a runtime-built native-to-managed thunk.
/// Under the JIT all five pass (the same paths IntegrationTests covers); the question this app
/// exists to answer is what happens with no JIT at all:
///
///     dotnet publish AotSmokeTest -r win-x64 -c Release -p:PublishAot=true
///     ./bin/Release/net10.0/win-x64/publish/AotSmokeTest.exe
///
/// Every step is labelled and flushed BEFORE it runs, because an AOT failure in a native->managed
/// frame can be process-fatal (ExecutionEngineException / FailFast) and unwind nothing: the last
/// flushed label is then the per-shape evidence. Exit code 0 only if all five report PASS.
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

        await Step("1/5 flow          (Oreo narrates dinner)", FlowStep);
        await Step("2/5 suspend       (greeting Oreo asynchronously)", SuspendStep);
        await Step("3/5 percall-lambda(describing Oreo through a C# lambda)", PerCallLambdaStep);
        await Step("4/5 stored-cb     (Mylo's mood listener)", StoredCallbackStep);
        await Step("5/5 iface-bridge  (Rex the C# dog crosses into Kotlin)", InterfaceBridgeStep);

        Console.WriteLine(_failures == 0
            ? "== ALL 5 SHAPES PASS =="
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

    private sealed class Dog : IPet
    {
        public string Name { get; }
        public int Legs => 4;
        public string? Nickname => null;
        public Dog(string name) => Name = name;
        public string Speak() => "Woof!";
        public string Greet() => $"Hi, I'm {Name} the dog";
        public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
        public void Nap() { }
        public void Dispose() { }
    }
}

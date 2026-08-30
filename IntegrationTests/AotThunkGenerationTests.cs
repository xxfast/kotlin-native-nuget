using System.Text.RegularExpressions;

namespace IntegrationTests;

/// <summary>
/// ADR-102: the generated forward callback machinery must stop asking the runtime to build
/// native-to-managed thunks (<c>Marshal.GetFunctionPointerForDelegate</c> / delegate-typed
/// <c>DllImport</c> parameters) and instead hand Kotlin the address of an ahead-of-time compiled
/// <c>[UnmanagedCallersOnly]</c> static thunk, dispatching through the GCHandle ctx that every
/// forward callback ABI already echoes.
///
/// These tests assert on the TEXT of the generated <c>Interop.cs</c> that the consumer compiles,
/// not on behaviour: Oreo and Mylo already meow, purr, eat and get fetched correctly under the JIT
/// (FlowTests, SuspendFunctionTests, LambdaTests, StoredCallbackTests, BidirectionalTests all pass
/// today). What is broken is the *mechanism*: on a JIT-less runtime (Mono full-AOT on Catalyst,
/// CoreCLR NativeAOT) there is nothing to build the thunk with, so Oreo's dinner-time Flow throws
/// `ExecutionEngineException: AOT NOT FOUND: (wrapper native-to-managed) ...`. Behaviour coverage
/// therefore cannot see this feature at all; the generated source is the only observable.
///
/// The runtime proof lives in <c>AotSmokeTest/</c> (a NativeAOT-publishable console app), because
/// NativeAOT cannot publish an xunit test host.
/// </summary>
public class AotThunkGenerationTests
{
    // ---- locating the generated source ------------------------------------------------------

    /// <summary>Repo root: the directory containing both test-library/ and TestDependency/,
    /// walking up from the test assembly (same precedent as BoxesRoundTripTests).</summary>
    private static string FindRepoRoot()
    {
        DirectoryInfo? dir = new(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "test-library")) &&
                Directory.Exists(Path.Combine(dir.FullName, "TestDependency")))
            {
                return dir.FullName;
            }
            dir = dir.Parent;
        }

        throw new InvalidOperationException(
            "could not find the repo root (a directory containing both test-library/ and " +
            $"TestDependency/) walking up from {AppContext.BaseDirectory}");
    }

    /// <summary>
    /// The exact Interop.cs this test run's consumer compiles against. Fixture packages are no
    /// longer pinned at 1.0.0 (every pack mints 1.0.0-fixture.&lt;epoch-ms&gt;), so the version is
    /// read from build/FixtureVersions.props rather than globbed - globbing the newest directory
    /// would silently read a different package than the one PackageReference resolved.
    /// Falls back to the KSP-generated resource copy if the pack output is not present.
    /// </summary>
    private static string InteropSourcePath()
    {
        string root = FindRepoRoot();

        string propsPath = Path.Combine(root, "build", "FixtureVersions.props");
        if (File.Exists(propsPath))
        {
            Match m = Regex.Match(
                File.ReadAllText(propsPath), @"<TestLibraryVersion>([^<]+)</TestLibraryVersion>");
            if (m.Success)
            {
                string packed = Path.Combine(
                    root, "test-library", "build", "nuget", $"TestLibrary.{m.Groups[1].Value}",
                    "contentFiles", "cs", "any", "Interop.cs");
                if (File.Exists(packed)) return packed;
            }
        }

        string generated = Path.Combine(
            root, "test-library", "build", "generated", "ksp", "mingwX64", "mingwX64Main",
            "resources", "Interop.cs");
        if (File.Exists(generated)) return generated;

        throw new InvalidOperationException(
            "no generated Interop.cs found. Run `./gradlew :test-library:packNuget` (or " +
            "scripts/verify.sh) first: this test reads the generated artifact, it does not " +
            $"produce it. Looked under {Path.Combine(root, "test-library", "build")}.");
    }

    private static string Interop() => File.ReadAllText(InteropSourcePath());

    // ---- text helpers -----------------------------------------------------------------------

    private static string[] Lines(string text) =>
        text.Split('\n').Select(l => l.TrimEnd('\r')).ToArray();

    /// <summary>
    /// The balanced-paren argument text of the first call whose opening `anchor` (which must end
    /// in '(') is not part of a `private static extern` declaration. Scoping IntPtr.Zero checks to
    /// the argument list matters: every generated call is followed by
    /// `if (error != IntPtr.Zero) throw ...`, so a line- or window-scoped check would false-fail.
    /// </summary>
    private static string CallArguments(string text, string anchor)
    {
        int from = 0;
        while (true)
        {
            int open = text.IndexOf(anchor, from, StringComparison.Ordinal);
            Assert.True(
                open >= 0,
                $"the generated Interop.cs has no call site matching '{anchor}'. The fixture " +
                "surface this test pins may have been renamed; re-anchor the test rather than " +
                "deleting it.");

            int lineStart = text.LastIndexOf('\n', open) + 1;
            string line = text[lineStart..open];
            if (line.Contains("extern", StringComparison.Ordinal))
            {
                from = open + anchor.Length;
                continue;
            }

            int i = open + anchor.Length;
            int depth = 1;
            while (i < text.Length && depth > 0)
            {
                if (text[i] == '(') depth++;
                else if (text[i] == ')') depth--;
                if (depth == 0) break;
                i++;
            }
            return text[(open + anchor.Length)..i];
        }
    }

    /// <summary>The `window` lines starting at the line containing `anchor`.</summary>
    private static string WindowAfter(string text, string anchor, int window)
    {
        string[] lines = Lines(text);
        int idx = Array.FindIndex(lines, l => l.Contains(anchor, StringComparison.Ordinal));
        Assert.True(idx >= 0, $"the generated Interop.cs contains no '{anchor}'.");
        return string.Join('\n', lines.Skip(idx).Take(window));
    }

    // ---- the mechanism itself ---------------------------------------------------------------

    [Fact]
    public void Interop_DeclaresANugetThunksClassOfUnmanagedCallersOnlyFunctionPointers()
    {
        string interop = Interop();

        Assert.Contains("NugetThunks", interop);
        Assert.Contains("[UnmanagedCallersOnly", interop);
        Assert.Contains("delegate* unmanaged[Cdecl]", interop);
    }

    [Fact]
    public void Interop_NeverAsksTheRuntimeToBuildADelegateThunk()
    {
        string interop = Interop();

        // The single assertion that fails on every JIT-less runtime today. `&Thunk` on an
        // [UnmanagedCallersOnly] static is a link-time constant; GetFunctionPointerForDelegate
        // needs a runtime code generator that Catalyst Release and NativeAOT do not have.
        int occurrences = Regex.Matches(interop, @"GetFunctionPointerForDelegate").Count;
        Assert.True(
            occurrences == 0,
            $"generated Interop.cs still calls Marshal.GetFunctionPointerForDelegate " +
            $"{occurrences} time(s); ADR-102 requires every forward callback pointer to come " +
            "from an [UnmanagedCallersOnly] static thunk in NugetThunks.");
    }

    [Fact]
    public void EveryThunkBodyFailsFastRatherThanUnwindingThroughANativeFrame()
    {
        string interop = Interop();
        string[] lines = Lines(interop);

        var thunkStarts = new List<int>();
        for (int i = 0; i < lines.Length; i++)
        {
            if (lines[i].Contains("[UnmanagedCallersOnly", StringComparison.Ordinal))
                thunkStarts.Add(i);
        }

        Assert.True(
            thunkStarts.Count > 0,
            "generated Interop.cs declares no [UnmanagedCallersOnly] thunks at all.");

        // ADR-102's decided exception discipline (human gate, 2026-08-31): a managed exception
        // escaping an [UnmanagedCallersOnly] frame is process-fatal-undefined under NativeAOT, so
        // when Oreo's C# lambda throws mid-callback the failure must be loud, not silent
        // corruption.
        foreach (int start in thunkStarts)
        {
            string body = string.Join('\n', lines.Skip(start).Take(25));
            Assert.True(
                body.Contains("Environment.FailFast", StringComparison.Ordinal),
                "an [UnmanagedCallersOnly] thunk at generated Interop.cs line " +
                $"{start + 1} has no Environment.FailFast catch-all within 25 lines:\n{body}");
        }
    }

    [Fact]
    public void EveryNonFlowCallbackDelegateShapeHasAThunk()
    {
        string interop = Interop();
        string[] lines = Lines(interop);

        // The flow trio is deliberately excluded: `startCollect` echoes ONE shared userData into
        // all three flow callbacks, so a correct implementation must dispatch them through a
        // single shared state object and may legitimately never name the delegate types in the
        // thunk bodies. See FlowCollection_PassesThunkPointersAndARealSharedCtx below.
        var shapes = Regex.Matches(interop, @"internal delegate\s+[^\s]+\s+(Nuget\w*Callback)\(")
            .Select(m => m.Groups[1].Value)
            .Where(n => !n.StartsWith("NugetFlowOn", StringComparison.Ordinal))
            .Distinct()
            .ToList();

        Assert.True(
            shapes.Count > 0,
            "no Nuget*Callback delegate declarations found in the generated Interop.cs - the " +
            "regex this test scrapes shapes with needs re-anchoring.");

        var thunkWindows = new List<string>();
        for (int i = 0; i < lines.Length; i++)
        {
            if (lines[i].Contains("[UnmanagedCallersOnly", StringComparison.Ordinal))
                thunkWindows.Add(string.Join('\n', lines.Skip(i).Take(25)));
        }

        var missing = shapes
            .Where(s => !thunkWindows.Any(w => w.Contains(s, StringComparison.Ordinal)))
            .ToList();

        Assert.True(
            missing.Count == 0,
            "these callback delegate shapes have no [UnmanagedCallersOnly] thunk dispatching to " +
            $"them: {string.Join(", ", missing)}. (If the implementation dispatches without " +
            "naming the delegate type, re-anchor this assertion rather than dropping the shape.)");
    }

    // ---- one spot-check per shape (ADR-102's per-shape table) -------------------------------

    /// <summary>
    /// Shape 1, per-call lambda parameter (ADR-036): `cat.DescribeWith(name =&gt; ...)`. Today the
    /// call site passes the marshaller-built pointer and `IntPtr.Zero` for the ctx slot, with the
    /// closure capturing the state. ADR-102: thunk pointer + the real GCHandle ctx.
    /// </summary>
    [Fact]
    public void PerCallLambda_PassesAThunkPointerAndTheRealCbHandleCtx()
    {
        string interop = Interop();

        string args = CallArguments(interop, "Native_DescribeWith(");
        Assert.DoesNotContain("IntPtr.Zero", args);
        Assert.Contains("GCHandle.ToIntPtr", args);
        Assert.Contains("NugetThunks", WindowAfter(interop, "Native_DescribeWith(_handle", 6));
    }

    /// <summary>
    /// Shape 2, stored callback (ADR-037): `cat.AddMoodListener(mood =&gt; ...)`. Kotlin echoes
    /// `onMeowCtx`/`onPurrCtx` per slot; C# passes IntPtr.Zero for both today.
    /// </summary>
    [Fact]
    public void StoredCallback_AddListener_PassesThunkPointersAndPerSlotCtxHandles()
    {
        string interop = Interop();

        string args = CallArguments(interop, "Native_AddListener(");
        Assert.DoesNotContain("IntPtr.Zero", args);
        Assert.Contains("NugetThunks", args);
        Assert.Contains("GCHandle.ToIntPtr", args);
    }

    /// <summary>
    /// Shape 3, C#-implemented interface bridge (ADR-039/084): `oreo.Befriend(new Dog("Rex"))`.
    /// Eight slots, one ctx each; the slot delegates are already pinned in `_pins`.
    /// </summary>
    [Fact]
    public void InterfaceBridge_Create_PassesThunkPointersForEverySlot()
    {
        string interop = Interop();

        // Anchored on the bridge-state assignment, not a bare `Native_Create(`: ordinary class
        // constructors generate that name too.
        string args = CallArguments(interop, "state.KotlinHandle = Native_Create(");
        Assert.Contains("NugetThunks", args);
        Assert.DoesNotContain("GetFunctionPointerForDelegate", args);
    }

    /// <summary>
    /// Shape 4, Flow/StateFlow collection (ADR-026/065) - the live-verified Catalyst failure:
    /// `await foreach (var item in feeder.MealAnnouncements)`.
    ///
    /// Note for the implementer: `NugetFlowCollectDelegate` takes ONE trailing userData that Kotlin
    /// echoes into all three of onNext/onComplete/onError, so ADR-102's per-shape table remark
    /// ("the trailing shared userData may stay IntPtr.Zero") cannot hold for this shape - with a
    /// zero ctx the static thunks have nothing to dispatch through. This test pins only what the
    /// mechanism requires: thunk pointers in, and a non-zero ctx.
    /// </summary>
    [Fact]
    public void FlowCollection_PassesThunkPointersAndARealSharedCtx()
    {
        string interop = Interop();

        string args = CallArguments(interop, "startCollect(");
        Assert.DoesNotContain("IntPtr.Zero", args);
        Assert.Contains(
            "NugetThunks", WindowAfter(interop, "internal KotlinFlowEnumerator(", 40));
    }

    /// <summary>
    /// Shape 5, suspend resumption (ADR-019/020): `await AsyncFunctions.FetchGreetingAsync("Oreo")`.
    /// A delegate-typed DllImport parameter makes the marshaller build the same runtime thunk
    /// `GetFunctionPointerForDelegate` does, so the extern signature itself has to change - the
    /// native symbol already receives a raw pointer, Kotlin is untouched.
    /// </summary>
    [Fact]
    public void SuspendResumption_ExternTakesARawPointerNotADelegate()
    {
        string interop = Interop();

        var offenders = Lines(interop)
            .Where(l => l.Contains("extern", StringComparison.Ordinal) &&
                        l.Contains("NugetAsyncCallback", StringComparison.Ordinal))
            .ToList();

        Assert.True(
            offenders.Count == 0,
            $"{offenders.Count} suspend DllImport declaration(s) still take a delegate-typed " +
            "callback parameter; ADR-102 requires `IntPtr callback`. First:\n" +
            (offenders.Count > 0 ? offenders[0].Trim() : ""));
    }
}

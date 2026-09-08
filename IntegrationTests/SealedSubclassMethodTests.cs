using System.Linq;
using System.Reflection;
using System.Text.Json;
using TestLibrary.Issue115;

namespace IntegrationTests;

/// <summary>
/// Issue #115 / ADR-116: a public member function declared on a sealed subclass is never exported.
/// The sealed route only ever reads <c>getAllProperties()</c>, <c>CirSealedSubclass</c> has no
/// <c>methods</c> at all, and the planner never sees the member, so the arm's C# class arrives
/// populated with properties, data methods and <c>Dispose()</c>, and inert — with no
/// <c>[nuget:SKIPPED_...]</c> line to say a member went missing.
/// <para>
/// This file cannot compile until the methods bind: every call below is CS1061 today. That is the
/// red. After ADR-116 lands, <c>Job.Running</c> carries <c>Cancel</c>, <c>Label</c>, both
/// <c>Step</c> overloads, <c>Next</c>, <c>Finish</c> and <c>Pick</c> as
/// <c>job_running_*</c> exports beside the <c>job_running_get_progress</c> getter it already has.
/// </para>
/// <para>
/// Three members must stay absent, and absence is asserted by reflection because a missing member
/// is invisible to the compiler in the other direction: <c>Pause</c> (<c>suspend</c>, deferred to
/// ROADMAP line 54), <c>PickNested</c> (a nested interface, which is never declared in C#), and
/// <c>Describe</c> on <c>Running</c> (declared-only: an arm exports what it declares, and the
/// inherited base body is not it).
/// </para>
/// <para>
/// Oreo runs the hallway; Mylo declines to and is poked about it.
/// </para>
/// </summary>
public class SealedSubclassMethodTests
{
    // ---- The plannable shapes: one test per marshalling seam the new route has to cross. ----

    /// <summary>
    /// <c>Int</c> return, no conversion at the seam at all. A route that open-codes a conversion
    /// fails here first.
    /// </summary>
    [Fact]
    public void Cancel_PrimitiveReturnOnASealedArm_BindsAndCallsThrough()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(42);

        Assert.Equal(42, oreo.Cancel());
    }

    /// <summary>
    /// <c>String</c> in and out on one member: the UTF8 marshalling pair on a parameter and a
    /// return at the same time.
    /// </summary>
    [Fact]
    public void Label_StringInAndOutOnASealedArm_MarshalsBothDirections()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(80);

        Assert.Equal("hallway:80", oreo.Label("hallway:"));
    }

    /// <summary>
    /// The property the arm already binds today. It must keep binding beside the new methods, so a
    /// regression in the property half of the sealed route is distinguishable from the feature.
    /// </summary>
    [Fact]
    public void Progress_ExistingArmProperty_StillBinds()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(7);

        Assert.Equal(7, oreo.Progress);
    }

    /// <summary>
    /// ADR-090 overloads on an arm: both keep the C# name <c>Step</c>, and the second takes the
    /// <c>_2</c> native symbol. A route that forgets the <c>occurrences</c> counter collides at the
    /// export name long before this asserts.
    /// </summary>
    [Fact]
    public void Step_FirstOverloadOnASealedArm_Binds()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(10);

        Assert.Equal(13, oreo.Step(3));
    }

    /// <summary>The second arm of the same overload pair, dispatched by arity.</summary>
    [Fact]
    public void Step_SecondOverloadOnASealedArm_BindsUnderTheSamePublicName()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(10);

        Assert.Equal(16, oreo.Step(3, 2));
    }

    /// <summary>
    /// Both overloads exist as two distinct declared methods rather than one binding that happens
    /// to answer both calls (a defaulted-parameter shortcut would satisfy the two tests above).
    /// </summary>
    [Fact]
    public void Step_IsDeclaredTwiceOnTheArm_OnceForEachArity()
    {
        int[] arities = typeof(Job.Running)
            .GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
            .Where(method => method.Name == "Step")
            .Select(method => method.GetParameters().Length)
            .OrderBy(arity => arity)
            .ToArray();

        Assert.Equal([1, 2], arities);
    }

    /// <summary>
    /// The sealed <em>base</em> at a return position (ADR-105 <c>sealedAsHandle</c>): the handle
    /// comes back through <c>Job.FromHandle</c> and has to discriminate onto the right arm.
    /// </summary>
    [Fact]
    public void Next_SealedBaseReturnFromAnArmMethod_DiscriminatesToTheOtherArm()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(5);

        using Job next = oreo.Next();

        Job.Done done = Assert.IsType<Job.Done>(next);
        Assert.Equal(5, done.Code);
    }

    /// <summary>
    /// A sibling nested arm spelled as its own concrete C# type: the <c>new Job.Done(handle)</c>
    /// construction site, which needs the enclosing base in the name.
    /// </summary>
    [Fact]
    public void Finish_NestedArmReturnFromAnArmMethod_IsSpelledAsTheConcreteArm()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(9);

        using Job.Done done = oreo.Finish();

        Assert.Equal(9, done.Code);
        Assert.IsAssignableFrom<Job>(done);
    }

    /// <summary>
    /// A nullable top-level interface return (ADR-040). The runtime object is an anonymous
    /// <c>object : JobListener</c> with no generated C# wrapper of its own, so the only way through
    /// is <c>IJobListener</c> dispatch.
    /// </summary>
    [Fact]
    public void Pick_TopLevelInterfaceReturnOnASealedArm_DispatchesThroughTheInterface()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(60);

        using IJobListener? listener = oreo.Pick();

        Assert.NotNull(listener);
        Assert.Equal("Oreo is 60% of the way to the bowl", listener!.OnEvent());
    }

    /// <summary>The null branch of the same member: <c>IntPtr.Zero</c> has to come back as null.</summary>
    [Fact]
    public void Pick_NullableInterfaceReturn_IsNullAtZeroProgress()
    {
        using var factory = new JobFactory();
        using Job.Running stalled = factory.Running(0);

        Assert.Null(stalled.Pick());
    }

    // ---- The object arm. A `data object` arm carries declared methods too. ----

    /// <summary>
    /// A method on a <c>data object</c> arm reached through the concrete return, the access path
    /// <c>Loaf</c> already has. It takes the handle receiver like any other arm rather than
    /// becoming a static.
    /// </summary>
    [Fact]
    public void Poke_MethodOnADataObjectArm_BindsThroughTheHandle()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();

        Assert.Equal("idle", mylo.Poke());
    }

    /// <summary>
    /// The same arm reached through the sealed base at a top-level return, the discriminator path.
    /// Two ways in, so a failure in the concrete-return spelling is distinguishable from a failure
    /// in the method itself.
    /// </summary>
    [Fact]
    public void Poke_OnTheObjectArmReachedThroughTheDiscriminator_BindsTheSameWay()
    {
        using Job job = JobSample.IdleJob();

        Job.Idle mylo = Assert.IsType<Job.Idle>(job);
        Assert.Equal("idle", mylo.Poke());
    }

    /// <summary>
    /// A declared <c>override</c> of the base's <c>open fun</c> binds as an ordinary method on the
    /// arm and answers with the arm's own body.
    /// </summary>
    [Fact]
    public void Describe_DeclaredOverrideOnTheObjectArm_BindsAndAnswersTheArmsBody()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();

        Assert.Equal("idle", mylo.Describe());
    }

    /// <summary>
    /// And it is a plain public method: not <c>override</c> (the C# sealed base declares nothing to
    /// override — CS0115) and not <c>virtual</c> (a virtual member on a <c>public sealed class</c>
    /// is CS0549). ADR-116 pins both planner flags to false; this is the observable consequence.
    /// </summary>
    [Fact]
    public void Describe_OnTheObjectArm_IsPlainPublicNeitherVirtualNorOverride()
    {
        MethodInfo? describe = typeof(Job.Idle).GetMethod(
            "Describe",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);

        Assert.NotNull(describe);
        Assert.False(describe!.IsVirtual, "Describe must not be virtual on a sealed arm (CS0549)");
    }

    // ---- Absences. Asserted by reflection: the compiler cannot see a member that is not there. ----

    /// <summary>
    /// ADR-116's declared-only gate. An arm exports the functions it declares itself; the base's
    /// <c>open fun describe()</c> body is not one of them, so <c>Running</c> — which does not
    /// override it — has no <c>Describe</c> at all.
    /// </summary>
    [Fact]
    public void Describe_InheritedBaseBody_IsNotRenderedOnTheArmThatDoesNotDeclareIt()
    {
        Assert.Null(typeof(Job.Running).GetMethod(
            "Describe",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly));
    }

    /// <summary>
    /// ...and it is not on the C# sealed base either. <c>CirSealedClass</c> carries no methods, so
    /// a consumer holding a <c>Job</c> must pattern-match to an arm first (deferred with the
    /// abstract-<c>val</c> gap, ROADMAP line 47).
    /// </summary>
    [Fact]
    public void Describe_IsNotOnTheSealedBaseEither()
    {
        Assert.Null(typeof(Job).GetMethod(
            "Describe",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly));
    }

    /// <summary>
    /// ROADMAP line 39's literal example: a nested interface return. <c>rootInterfaces</c> never
    /// declares <c>NestedListenerOwner.Listener</c>, so binding it would emit a dangling
    /// <c>TestLibrary.Issue54.IListener</c> and fail the consumer compile with CS0246.
    /// </summary>
    [Fact]
    public void PickNested_NestedInterfaceReturnOnASealedArm_IsAbsent()
    {
        Assert.Null(typeof(Job.Running).GetMethod("PickNested"));
    }

    /// <summary>
    /// The ADR-116 deferral: <c>suspend</c> members on an arm stay absent in v1 (they join the
    /// suspend route migration, ROADMAP line 54). Both the sync and the ADR-020 <c>...Async</c>
    /// spelling are asserted, because "absent" has to mean absent under either name.
    /// </summary>
    [Fact]
    public void Pause_SuspendMemberOnASealedArm_IsAbsentUnderEitherSpelling()
    {
        Assert.Null(typeof(Job.Running).GetMethod("Pause"));
        Assert.Null(typeof(Job.Running).GetMethod("PauseAsync"));
    }

    /// <summary>
    /// The control arm: <c>Done</c> declares no functions, so it gains nothing but must keep its
    /// property and its data methods exactly as today.
    /// </summary>
    [Fact]
    public void Done_ArmWithNoDeclaredFunctions_IsUnchanged()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(3);
        using Job.Done done = oreo.Finish();

        Assert.Equal(3, done.Code);
        Assert.Equal("Done(code=3)", done.ToString());
    }
}

/// <summary>
/// The other half of #115: the members that cannot bind must be <em>named</em>. A skipped member is
/// invisible from compiled C# (it simply is not there), so the only seam that can see <em>why</em>
/// is the ADR-100 diagnostics artifact the KSP round writes. These read it directly, the way
/// <c>BoxesDiagnosticsTests</c> reads <c>reverse-ir.json</c>.
/// <para>
/// Today this file contains no <c>issue115</c> entry at all — that silence is the defect. After
/// ADR-116, <c>pause</c> is a <c>SKIPPED_UNSUPPORTED_COMBINATION</c> (the new
/// <c>SEALED_SUBCLASS_UNROUTED</c> reason) and <c>pickNested</c> is the ordinary
/// <c>SKIPPED_UNSUPPORTED_TYPE</c> the nested-interface gate already emits on an ordinary class.
/// </para>
/// </summary>
public class SealedSubclassMethodDiagnosticsTests
{
    private const string Package = "io.github.xxfast.kotlin.native.nuget.test.issue115";

    private sealed record Diagnostic(string Severity, string Kind, string Declaration, string Message);

    private static string FindRepoRoot()
    {
        DirectoryInfo? dir = new(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "test-library")) &&
                Directory.Exists(Path.Combine(dir.FullName, "IntegrationTests")))
            {
                return dir.FullName;
            }
            dir = dir.Parent;
        }

        throw new InvalidOperationException(
            "could not find the repo root (a directory containing both test-library/ and " +
            $"IntegrationTests/) walking up from {AppContext.BaseDirectory}");
    }

    /// <summary>
    /// Every <c>NugetDiagnostics.json</c> the KSP round wrote, one per Kotlin target. They are
    /// regenerated together by <c>:test-library:clean :test-library:packNuget</c> and must agree,
    /// so a per-target regression cannot hide behind a sibling target's file.
    /// </summary>
    private static (string Path, IReadOnlyList<Diagnostic> Entries)[] DiagnosticFiles()
    {
        string kspRoot = Path.Combine(FindRepoRoot(), "test-library", "build", "generated", "ksp");
        Assert.True(
            Directory.Exists(kspRoot),
            $"{kspRoot} does not exist. Run `scripts/verify.sh` (or at least " +
            "`./gradlew :test-library:packNuget`) first: this test reads the KSP artifact, it " +
            "does not produce it.");

        string[] files = Directory
            .GetFiles(kspRoot, "NugetDiagnostics.json", SearchOption.AllDirectories)
            .OrderBy(path => path, StringComparer.Ordinal)
            .ToArray();

        Assert.True(files.Length > 0, $"no NugetDiagnostics.json found under {kspRoot}");

        return files.Select(path =>
        {
            using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(path));
            List<Diagnostic> entries = doc.RootElement.EnumerateArray().Select(entry =>
                new Diagnostic(
                    entry.GetProperty("severity").GetString() ?? "",
                    entry.GetProperty("kind").GetString() ?? "",
                    entry.GetProperty("declaration").GetString() ?? "",
                    entry.GetProperty("message").GetString() ?? "")).ToList();
            return (path, (IReadOnlyList<Diagnostic>)entries);
        }).ToArray();
    }

    /// <summary>
    /// Asserts <paramref name="declaration"/> is named with <paramref name="kind"/> in every
    /// target's diagnostics file. The failure message dumps every <c>issue115</c> entry that
    /// <em>is</em> present, so a wrong kind reads as a wrong kind rather than as nothing.
    /// </summary>
    private static void AssertNamed(string declaration, string kind)
    {
        foreach ((string path, IReadOnlyList<Diagnostic> entries) in DiagnosticFiles())
        {
            bool named = entries.Any(entry =>
                entry.Declaration == declaration && entry.Kind == kind);

            string present = string.Join(
                "\n  ",
                entries
                    .Where(entry => entry.Declaration.Contains(Package, StringComparison.Ordinal))
                    .Select(entry => $"{entry.Kind} {entry.Declaration}")
                    .DefaultIfEmpty("(no issue115 entries at all)"));

            Assert.True(
                named,
                $"expected `{kind} {declaration}` in {path}, but the issue115 entries there are:" +
                $"\n  {present}");
        }
    }

    /// <summary>
    /// A <c>suspend</c> member on a sealed arm has no legacy route to fall back on (the suspend
    /// route is keyed to the ordinary <c>classes</c> list, which excludes sealed subclasses), so
    /// the silent-because-a-legacy-route-re-emits-it skip has to become a named drop.
    /// </summary>
    [Fact]
    public void Pause_SuspendMemberOnASealedArm_IsNamedAsAnUnsupportedCombination()
    {
        AssertNamed($"{Package}.Job.Running.pause", "SKIPPED_UNSUPPORTED_COMBINATION");
    }

    /// <summary>
    /// The nested-interface return keeps the diagnostic an ordinary class already gets for the same
    /// shape: the type is unsupported, not the routing. This is the row of ADR-116's table that the
    /// post-process must leave alone.
    /// </summary>
    [Fact]
    public void PickNested_NestedInterfaceReturnOnASealedArm_IsNamedAsAnUnsupportedType()
    {
        AssertNamed($"{Package}.Job.Running.pickNested", "SKIPPED_UNSUPPORTED_TYPE");
    }

    /// <summary>
    /// The members that do bind must not also be named as skipped. A post-process that reclassifies
    /// too eagerly would report <c>cancel</c> or <c>pick</c> as dropped while still emitting them,
    /// which is worse than silence: every consumer build would carry a false warning.
    /// </summary>
    [Fact]
    public void TheBindingMembersOfTheArm_AreNotReportedAsSkipped()
    {
        string[] binding =
        [
            $"{Package}.Job.Running.cancel",
            $"{Package}.Job.Running.label",
            $"{Package}.Job.Running.step",
            // ADR-090 numbers the second overload's symbol `step_2`, so the negative has to cover
            // both arms of the pair: a post-process that reclassified only the numbered one would
            // otherwise slip through.
            $"{Package}.Job.Running.step_2",
            $"{Package}.Job.Running.next",
            $"{Package}.Job.Running.finish",
            $"{Package}.Job.Running.pick",
            $"{Package}.Job.Idle.poke",
            $"{Package}.Job.Idle.describe",
        ];

        foreach ((string path, IReadOnlyList<Diagnostic> entries) in DiagnosticFiles())
        {
            string[] falseAlarms = entries
                .Where(entry => entry.Kind.StartsWith("SKIPPED", StringComparison.Ordinal))
                .Where(entry => binding.Contains(entry.Declaration))
                .Select(entry => $"{entry.Kind} {entry.Declaration}")
                .OrderBy(line => line, StringComparer.Ordinal)
                .ToArray();

            Assert.True(
                falseAlarms.Length == 0,
                $"{path} reports bound members as skipped:\n  {string.Join("\n  ", falseAlarms)}");
        }
    }
}

using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
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
/// ADR-118 adds the suspend half. A <c>suspend fun</c> an arm <em>declares</em> now binds as
/// <c>Task&lt;T&gt; ...Async(..., CancellationToken)</c> off the arm's own export prefix
/// (<c>job_running_pause_async</c>), the arm gains <c>_scopeHandle</c>, <c>IAsyncDisposable</c> and
/// <c>DisposeAsync</c>, and a second same-named overload takes <c>_2</c> on <em>both</em> the
/// <c>[DllImport]</c> EntryPoint and the private extern's C# name.
/// </para>
/// <para>
/// ADR-124 adds the flow half. A <c>Flow&lt;T&gt;</c> or <c>StateFlow&lt;T&gt;</c> an arm declares,
/// at a property getter or at a method return, binds as <c>KotlinFlow&lt;T&gt;</c> /
/// <c>KotlinStateFlow&lt;T&gt;</c> off the arm's own export prefix
/// (<c>job_watching_get_ticks_collect</c>, <c>job_watching_labels_collect</c>), through the same
/// collect and value thunks the ordinary-class route uses. The method form is
/// <c>SKIPPED_UNSUPPORTED_COMBINATION</c> today and the property form is dropped in silence, so
/// <c>Ticks</c>, <c>Labels</c> and <c>Beats</c> are all CS1061 until it lands.
/// </para>
/// <para>
/// The absences are asserted by reflection because a missing member is invisible to the compiler in
/// the other direction: <c>PickNested</c> (a nested interface, which is never declared in C#),
/// <c>Describe</c> on <c>Running</c>, and <c>RestAsync</c> on every arm (declared-only, on the sync
/// loop and on the suspend loop alike: an arm exports what it declares, and an inherited base body
/// — <c>open fun describe()</c>, <c>open suspend fun rest()</c> — is not it).
/// </para>
/// <para>
/// Oreo runs the hallway; Mylo declines to and is poked about it, then settles on the windowsill
/// to watch birds and tick.
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

    // ---- ADR-116's 2026-09-13 amendment: an override whose base member the planner declined. ----

    /// <summary>
    /// <c>Job.tag</c> is <c>@Unstable</c> (an ADR-115 <c>@RequiresOptIn</c> marker), so the base's
    /// own plan is structurally declined and the base binds no <c>Tag</c> at all. The arm's
    /// <c>@OptIn</c> override binds, and because nothing on the base carries the ADR-096 omitting
    /// overload for it, the arm owes its own: the short-arity call has to compile on a
    /// <c>Job.Running</c>-typed reference and answer exactly what the full-arity call with the
    /// declared default answers.
    /// <para>
    /// This is CS1501 today — <c>sealedSubclassEntries</c> returns early on any <c>override</c>
    /// whose overridee is declared on the sealed base, planned or not, and it reads raw
    /// <c>hasDefault</c> off the override's parameters, which is always <c>false</c>.
    /// </para>
    /// </summary>
    [Fact]
    public void Tag_ShortArityOnAnArmWhoseBaseMemberWasDeclined_AppliesTheDeclaredDefault()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(42);

        Assert.Equal(oreo.Tag("hallway", "!"), oreo.Tag("hallway"));
        Assert.Equal("hallway42!", oreo.Tag("hallway"));
    }

    /// <summary>
    /// The declared arity, which binds today: the pair is what tells "the overload was synthesized"
    /// apart from "the declared binding silently changed shape".
    /// </summary>
    [Fact]
    public void Tag_FullArityOnTheArm_PassesBothArgumentsThrough()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(42);

        Assert.Equal("hallway42?", oreo.Tag("hallway", "?"));
    }

    /// <summary>
    /// The absence half, asserted by reflection because a base-typed call could not compile either
    /// way: an opt-in-marked base member is not part of the C# surface, so <c>Job</c> declares no
    /// <c>Tag</c> in any arity — and neither does <c>Job.Idle</c>, which inherits the declined
    /// member without overriding it. Enumerated rather than <c>GetMethod</c> so a wrong
    /// implementation that puts <em>two</em> <c>Tag</c> overloads on the base fails here instead of
    /// throwing <c>AmbiguousMatchException</c>.
    /// </summary>
    [Fact]
    public void Tag_OnTheDecliningBaseAndOnANonOverridingArm_IsAbsentEntirely()
    {
        string[] onBase = typeof(Job)
            .GetMethods(BindingFlags.Public | BindingFlags.Instance)
            .Where(method => method.Name == "Tag")
            .Select(method => method.ToString() ?? "Tag")
            .OrderBy(signature => signature, StringComparer.Ordinal)
            .ToArray();

        Assert.True(onBase.Length == 0, $"Job must declare no Tag: {string.Join(", ", onBase)}");

        string[] onIdle = typeof(Job.Idle)
            .GetMethods(BindingFlags.Public | BindingFlags.Instance)
            .Where(method => method.Name == "Tag")
            .Select(method => method.ToString() ?? "Tag")
            .OrderBy(signature => signature, StringComparer.Ordinal)
            .ToArray();

        Assert.True(onIdle.Length == 0, $"Job.Idle must declare no Tag: {string.Join(", ", onIdle)}");
    }

    /// <summary>
    /// Both arities exist as two declared methods on the arm, not one binding with a C#-side
    /// default parameter answering both calls.
    /// </summary>
    [Fact]
    public void Tag_IsDeclaredTwiceOnTheArm_OnceForEachArity()
    {
        int[] arities = typeof(Job.Running)
            .GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
            .Where(method => method.Name == "Tag")
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
    /// And since item 35 it is an <c>override</c>: the sealed base carries its own
    /// <c>open fun describe()</c> as a <c>virtual</c> member, so the arm that declares a Kotlin
    /// <c>override</c> spells one in C# too. It was a plain public method for as long as the C#
    /// base declared nothing to override (ADR-116 pinned both planner flags to false); the base is
    /// the carrier now, and a call through a <c>Job</c> reference has to reach the arm's body.
    /// </summary>
    [Fact]
    public void Describe_OnTheObjectArm_OverridesTheBasesVirtualMember()
    {
        MethodInfo? describe = typeof(Job.Idle).GetMethod(
            "Describe",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);

        Assert.NotNull(describe);
        Assert.True(describe!.IsVirtual, "an arm overriding a base member is virtual in C#");
        Assert.Equal(typeof(Job), describe.GetBaseDefinition().DeclaringType);
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
    /// ...because it is on the C# sealed base instead, which is item 35's whole point.
    /// <c>CirSealedClass</c> carries its base's own declared methods now, rendered <c>virtual</c>
    /// (never <c>abstract</c>: the export dispatches in Kotlin, and an abstract member would
    /// oblige every arm to declare an override). A consumer holding a <c>Job</c> no longer has to
    /// pattern-match to an arm to call it.
    /// </summary>
    [Fact]
    public void Describe_IsOnTheSealedBaseAsAVirtualMember()
    {
        MethodInfo? describe = typeof(Job).GetMethod(
            "Describe",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);

        Assert.NotNull(describe);
        Assert.True(describe!.IsVirtual, "the base member must be virtual for an arm to override");
        Assert.False(describe.IsAbstract, "the base member is concrete: Kotlin dispatches");
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
    /// ADR-118's declared-only gate on the <em>suspend</em> loop. <c>Job.rest()</c> is an
    /// <c>open suspend fun</c> with a body on the base that no arm overrides, and the Kotlin suspend
    /// builder reads <c>getAllFunctions()</c>: without the filter every arm would export
    /// <c>job_&lt;arm&gt;_rest_async</c>. No arm may carry <c>RestAsync</c>, under any spelling.
    /// </summary>
    [Fact]
    public void RestAsync_InheritedBaseSuspendBody_IsOnNoArmAndNotOnTheBase()
    {
        foreach (Type type in new[] { typeof(Job), typeof(Job.Running), typeof(Job.Idle), typeof(Job.Done) })
        {
            Assert.Null(type.GetMethod("RestAsync"));
            Assert.Null(type.GetMethod("Rest"));
        }
    }

    /// <summary>
    /// The same gate read off the generated <c>[DllImport]</c>s rather than off the public surface:
    /// an arm could export <c>job_running_rest_async</c> from Kotlin and never render a C# method,
    /// and <c>ForwardAbiContract.kotlin</c> filters Kotlin exports down to the C# import set, so the
    /// stray export would vanish from the comparison instead of being flagged. The private externs
    /// are the closest a compiled consumer can get to the export list.
    /// <para>
    /// The <c>job_running_get_progress</c> assertion is the canary: <c>DllImportAttribute</c> is a
    /// pseudo-custom attribute reconstructed by the runtime, so if that reconstruction ever came
    /// back empty the whole walk would pass vacuously.
    /// </para>
    /// </summary>
    [Fact]
    public void NoArm_ExportsAnEntryPointForTheInheritedBaseSuspendBody()
    {
        string[] entryPoints = EntryPointsOfTheSealedFamily();

        Assert.Contains("job_running_get_progress", entryPoints);
        Assert.DoesNotContain(
            entryPoints,
            entryPoint => entryPoint.EndsWith("_rest_async", StringComparison.Ordinal));
    }

    /// <summary>
    /// The numbering read off the entry points themselves: two <c>suspend</c> overloads on one arm
    /// take <c>job_running_pause_async</c> and <c>job_running_pause_2_async</c>, composed off the
    /// arm's prefix rather than the base's. This fails loudly if the planner's occurrence counter
    /// never reaches the suspend route's composition site — the awaited call above would still
    /// return <em>a</em> number.
    /// </summary>
    [Fact]
    public void PauseAsync_BothArmOverloads_TakeArmPrefixedNumberedEntryPoints()
    {
        string[] entryPoints = EntryPointsOfTheSealedFamily();

        Assert.Contains("job_running_pause_async", entryPoints);
        Assert.Contains("job_running_pause_2_async", entryPoints);
        Assert.Contains("job_running_resume_async", entryPoints);
        Assert.Contains("job_idle_nap_async", entryPoints);
    }

    /// <summary>
    /// Every <c>[DllImport]</c> EntryPoint declared on <c>Job</c> or on one of its nested arms.
    /// </summary>
    private static string[] EntryPointsOfTheSealedFamily() =>
        new[] { typeof(Job) }
            .Concat(typeof(Job).GetNestedTypes(BindingFlags.Public | BindingFlags.NonPublic))
            .SelectMany(type => type.GetMethods(
                BindingFlags.Public | BindingFlags.NonPublic |
                BindingFlags.Static | BindingFlags.Instance | BindingFlags.DeclaredOnly))
            .Select(method => method.GetCustomAttribute<DllImportAttribute>()?.EntryPoint)
            .Where(entryPoint => entryPoint is not null)
            .Select(entryPoint => entryPoint!)
            .ToArray();

    // ---- ADR-118: the suspend route, re-keyed so a sealed arm is a valid owner. ----

    /// <summary>
    /// ADR-116's deferral, inverted. A <c>suspend fun</c> the arm declares binds as
    /// <c>Task&lt;int&gt; PauseAsync()</c> off the arm's own prefix, exactly the shape an ordinary
    /// class's suspend method has had since ADR-019.
    /// </summary>
    [Fact]
    public async Task PauseAsync_SuspendMemberOnASealedArm_BindsAndAwaitsThrough()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(42);

        Assert.Equal(42, await oreo.PauseAsync());
    }

    /// <summary>
    /// The second arm of the <c>suspend</c> overload pair. <c>progress + millis</c> is unreachable
    /// from <c>pause()</c>'s body, so this asserts a <em>value</em> and not a presence: if the
    /// <c>_2</c> suffix lands on the <c>[DllImport]</c> EntryPoint but not on
    /// <c>CirMethod.nativeName</c>, the second body calls <c>Native_PauseAsync</c>, resolves by
    /// arity to the first overload's extern, compiles, runs, and answers 42.
    /// </summary>
    [Fact]
    public async Task PauseAsync_SecondSuspendOverloadOnAnArm_DispatchesToTheTwoParameterBody()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(42);

        Assert.Equal(292, await oreo.PauseAsync(250));
    }

    /// <summary>
    /// One receiver, both overloads, in one scope: the numbered exports must reach the same Kotlin
    /// object and stay distinct from each other.
    /// </summary>
    [Fact]
    public async Task PauseAsync_BothSuspendOverloads_ShareOneArmAndStayDistinct()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(10);

        Assert.Equal(10, await oreo.PauseAsync());
        Assert.Equal(15, await oreo.PauseAsync(5));
        Assert.Equal(10, await oreo.PauseAsync());
    }

    /// <summary>
    /// <c>String</c> in and out on the suspend route: the UTF8 pair riding the async result protocol
    /// (a <c>StableRef</c> to the result, unwrapped in the completion callback) rather than the plan
    /// route's synchronous return.
    /// </summary>
    [Fact]
    public async Task ResumeAsync_StringInAndOutOnASealedArm_MarshalsBothDirections()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(80);

        Assert.Equal("hallway:80", await oreo.ResumeAsync("hallway:"));
    }

    /// <summary>
    /// A <c>suspend fun</c> on a <c>data object</c> arm. Mylo naps on the same handle receiver a
    /// <c>data class</c> arm uses; an object arm must not collapse into a static route.
    /// </summary>
    [Fact]
    public async Task NapAsync_SuspendMemberOnADataObjectArm_BindsThroughTheHandle()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();

        Assert.Equal("napping", await mylo.NapAsync());
    }

    /// <summary>The same object arm reached through the sealed base's discriminator.</summary>
    [Fact]
    public async Task NapAsync_OnTheObjectArmReachedThroughTheDiscriminator_BindsTheSameWay()
    {
        using Job job = JobSample.IdleJob();

        Job.Idle mylo = Assert.IsType<Job.Idle>(job);
        Assert.Equal("napping", await mylo.NapAsync());
    }

    /// <summary>
    /// A suspending arm carries the ordinary class's async lifetime shape: <c>IAsyncDisposable</c>
    /// and a <c>DisposeAsync</c> that drains the arm's own scope before disposing the handle, so
    /// <c>await using</c> is the natural spelling for a receiver whose methods suspend.
    /// </summary>
    [Fact]
    public async Task Running_WithSuspendMembers_IsAsyncDisposableUnderAwaitUsing()
    {
        using var factory = new JobFactory();

        await using Job.Running oreo = factory.Running(11);

        Assert.IsAssignableFrom<IAsyncDisposable>(oreo);
        Assert.Equal(11, await oreo.PauseAsync());
    }

    /// <summary>The <c>data object</c> arm suspends too, so it gets the same lifetime shape.</summary>
    [Fact]
    public async Task Idle_WithASuspendMember_IsAsyncDisposableUnderAwaitUsing()
    {
        using var factory = new JobFactory();

        await using Job.Idle mylo = factory.Idle();

        Assert.IsAssignableFrom<IAsyncDisposable>(mylo);
        Assert.Equal("napping", await mylo.NapAsync());
    }

    /// <summary>
    /// The control, on the async half: <c>Done</c> declares no <c>suspend</c> member, so it gains no
    /// scope, no <c>DisposeAsync</c> and no <c>IAsyncDisposable</c>. Putting the interface on the
    /// sealed base instead would advertise a drain that <c>Done</c> has nothing to drain.
    /// </summary>
    [Fact]
    public void Done_ArmWithNoSuspendMembers_IsNotAsyncDisposable()
    {
        Assert.False(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Done)));
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Running)));
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Idle)));
    }

    /// <summary>
    /// The pair is one natural C# overload set: two declarations, no <c>Pause_2</c> leaking onto the
    /// public surface. Each takes its trailing <c>CancellationToken</c>, so the arities are 1 and 2.
    /// </summary>
    [Fact]
    public void PauseAsync_IsDeclaredTwiceOnTheArm_AsOneOverloadSetWithoutNumberedPublicNames()
    {
        Type running = typeof(Job.Running);

        Assert.NotNull(running.GetMethod("PauseAsync", new[] { typeof(CancellationToken) }));
        Assert.NotNull(running.GetMethod("PauseAsync", new[] { typeof(int), typeof(CancellationToken) }));
        Assert.DoesNotContain(
            running.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly),
            method => method.Name.Contains("Pause_", StringComparison.Ordinal));
    }

    /// <summary>
    /// Declared-only holds on the suspend loop the way it holds on the sync one: <c>Running</c>
    /// declares neither <c>describe</c> nor <c>rest</c>, so it renders neither <c>Describe</c> nor
    /// any <c>...Async</c> spelling of either.
    /// </summary>
    [Fact]
    public void DescribeAsync_IsOnNoArm_TheSuspendLoopIsDeclaredOnlyToo()
    {
        Assert.Null(typeof(Job.Running).GetMethod("DescribeAsync"));
        Assert.Null(typeof(Job.Idle).GetMethod("DescribeAsync"));
        Assert.Null(typeof(Job.Done).GetMethod("DescribeAsync"));
        Assert.Null(typeof(Job.Running).GetMethod(
            "Describe",
            BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly));
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

    // ---- A nested arm at a suspend return, spelled with its enclosing base. ----

    /// <summary>
    /// The defect this section pins: on the suspend route the return type is spelled from the
    /// <em>simple</em> name, so a nested arm renders as <c>Task&lt;Running&gt;</c> completing with
    /// <c>new Running(resultPtr)</c>. ADR-009 nests the arm inside <c>Job</c>, so at namespace
    /// scope that is CS0246 and the whole of <c>Interop.cs</c> fails to compile. The synchronous
    /// twin, <c>factory.Running(9)</c> above, already spells <c>Job.Running</c>.
    /// <para>
    /// Oreo starts the hallway sprint a beat later than usual, 9% of the way to the bowl.
    /// </para>
    /// </summary>
    [Fact]
    public async Task RunningLaterAsync_NestedArmAtASuspendReturn_IsSpelledWithItsEnclosingBase()
    {
        using var factory = new JobFactory();

        await using Job.Running oreo = await factory.RunningLaterAsync(9);

        Assert.Equal(9, oreo.Progress);
        Assert.IsAssignableFrom<Job>(oreo);
    }

    /// <summary>
    /// The same spelling site with a <c>data object</c> arm, so a fix that only handles the
    /// <c>data class</c> kind still fails. Mylo loafs, asynchronously.
    /// </summary>
    [Fact]
    public async Task IdleLaterAsync_NestedObjectArmAtASuspendReturn_IsSpelledWithItsEnclosingBase()
    {
        using var factory = new JobFactory();

        await using Job.Idle mylo = await factory.IdleLaterAsync();

        Assert.IsAssignableFrom<Job>(mylo);
        Assert.Equal("idle", mylo.Poke());
    }

    /// <summary>
    /// The arm-declared half: a sibling arm at a suspend return. C#'s enclosing-type lookup
    /// resolves a bare <c>Done</c> from inside <c>Job</c>, so this cell is what separates "the
    /// speller is wrong everywhere" from "the speller is wrong only outside the base".
    /// </summary>
    [Fact]
    public async Task FinishLaterAsync_SiblingArmAtASuspendReturnOnAnArm_ConstructsTheArm()
    {
        using var factory = new JobFactory();
        await using Job.Running oreo = factory.Running(4);

        using Job.Done done = await oreo.FinishLaterAsync();

        Assert.Equal(4, done.Code);
        Assert.IsAssignableFrom<Job>(done);
    }

    /// <summary>
    /// The second spelling site: a <em>top-level</em> <c>suspend fun</c> on the ADR-007 static
    /// class. It has its own return speller, so a fix applied to the class route alone leaves this
    /// one emitting <c>new Running(resultPtr)</c>.
    /// </summary>
    [Fact]
    public async Task AnyRunningLaterAsync_TopLevelSuspendReturningANestedArm_IsSpelledWithItsBase()
    {
        await using Job.Running oreo = await JobSample.AnyRunningLaterAsync();

        Assert.Equal(33, oreo.Progress);
        Assert.IsAssignableFrom<Job>(oreo);
    }

    // ---- The sealed *base* (not an arm) at a suspend return: Task<Job> via Job.FromHandle. ----

    /// <summary>
    /// The defect this section pins: when a <c>suspend fun</c> returns the sealed <em>base</em>,
    /// the return type is spelled correctly as <c>Task&lt;Job&gt;</c> but the completion renders
    /// <c>t.SetResult(new Job(resultPtr))</c>, and <c>Job</c> is <c>public abstract class</c>, so
    /// the consumer's <c>Interop.cs</c> fails CS0144 ("cannot create an instance of the abstract
    /// type") and nothing in the assembly compiles. The Kotlin half already mints the StableRef on
    /// the concrete arm, so the completion only has to read it through
    /// <c>Job.FromHandle(resultPtr)</c>, the discriminator the synchronous <c>Next()</c> above
    /// already goes through.
    /// <para>
    /// The answer is a <em>different</em> arm than the receiver on purpose: a completion that
    /// constructs the receiver's type by name, or reads the discriminator off the wrong handle,
    /// fails on <c>Code</c> rather than passing by luck. Oreo is 4% down the hallway and the next
    /// job is the finished one at 5.
    /// </para>
    /// <para>
    /// <c>using</c>, not <c>await using</c>: the base is only <c>IDisposable</c>
    /// (<c>CirSealedRenderer</c> puts <c>IAsyncDisposable</c> on the suspending arms alone), so
    /// <c>await using</c> on a <c>Job</c>-typed local is CS8410. The arm's synchronous
    /// <c>Dispose()</c> cancels and disposes its scope before releasing the handle, so a
    /// suspending arm held as the base still cleans up both.
    /// </para>
    /// </summary>
    [Fact]
    public async Task NextLaterAsync_SealedBaseAtASuspendReturnOnAnArm_DiscriminatesToTheOtherArm()
    {
        using var factory = new JobFactory();
        await using Job.Running oreo = factory.Running(4);

        using Job next = await oreo.NextLaterAsync();

        Job.Done done = Assert.IsType<Job.Done>(next);
        Assert.Equal(5, done.Code);
    }

    /// <summary>
    /// The ordinary-class owner, dispatching across all three arm shapes from one
    /// <c>Task&lt;Job&gt;</c> completion: a <c>data object</c> arm, a payload <c>data class</c>
    /// arm, and a second <c>data class</c> arm. A completion pinned to a single constructor cannot
    /// pass all three, and the payloads are distinct so a wrong discriminator is a wrong value.
    /// </summary>
    [Fact]
    public async Task NextLaterAsync_SealedBaseOnAnOrdinaryClass_DispatchesPerArm()
    {
        using var factory = new JobFactory();

        using Job idle = await factory.NextLaterAsync(-1);
        using Job running = await factory.NextLaterAsync(7);
        using Job done = await factory.NextLaterAsync(100);

        Assert.IsType<Job.Idle>(idle);
        Assert.Equal(7, Assert.IsType<Job.Running>(running).Progress);
        Assert.Equal(100, Assert.IsType<Job.Done>(done).Code);
    }

    /// <summary>
    /// The nullable twin, <c>Task&lt;Job?&gt;</c>: the branch one step up in the same renderer,
    /// which spells <c>resultPtr == IntPtr.Zero ? null : new Job(resultPtr)</c> and carries the
    /// identical CS0144. Both branches in one test, because a fix that reads the null guard right
    /// and the handle wrong (or the reverse) passes on one of them alone. Oreo at 100% is already
    /// at the bowl, so there is no next job for him.
    /// </summary>
    [Fact]
    public async Task NextOrNullLaterAsync_NullableSealedBase_ReadsNullAsNullAndAnArmAsTheArm()
    {
        using var factory = new JobFactory();
        await using Job.Running finished = factory.Running(100);
        await using Job.Running oreo = factory.Running(12);

        Assert.Null(await finished.NextOrNullLaterAsync());

        using Job? next = await oreo.NextOrNullLaterAsync();
        Assert.Equal(13, Assert.IsType<Job.Done>(next).Code);
    }

    /// <summary>
    /// The throw path of the same route. When the body throws, no result handle is minted, so the
    /// discriminated read is never reached and only the ADR-128/130 error envelope crosses:
    /// Kotlin's <c>IllegalStateException</c> arrives as
    /// <c>KotlinInvalidOperationException : InvalidOperationException</c>. The non-throwing input
    /// is asserted beside it so the sentinel is a branch rather than the whole member.
    /// </summary>
    [Fact]
    public async Task NextOrThrowLaterAsync_SealedBaseSuspendThatThrows_PropagatesAndStillBinds()
    {
        using var factory = new JobFactory();
        await using Job.Running oreo = factory.Running(4);

        using Job next = await oreo.NextOrThrowLaterAsync(3);
        Assert.Equal(7, Assert.IsType<Job.Done>(next).Code);

        InvalidOperationException failure =
            await Assert.ThrowsAnyAsync<InvalidOperationException>(
                async () => await oreo.NextOrThrowLaterAsync(-1));
        Assert.Contains("backwards down the hallway", failure.Message);
    }

    /// <summary>
    /// The second spelling site: the sealed base at a <em>top-level</em> <c>suspend fun</c> on the
    /// ADR-007 static class. It has its own return speller, so a fix applied to the class route
    /// alone leaves this one emitting <c>new Job(resultPtr)</c> and the assembly still fails to
    /// compile.
    /// </summary>
    [Fact]
    public async Task AnyNextLaterAsync_TopLevelSuspendReturningTheSealedBase_UsesFromHandle()
    {
        using Job next = await JobSample.AnyNextLaterAsync(3);

        Assert.Equal(3, Assert.IsType<Job.Done>(next).Code);
    }

    // ---- ADR-124: the Flow / StateFlow route, re-keyed so a sealed arm is a valid owner. ----

    /// <summary>
    /// The issue's own shape: a <c>StateFlow&lt;Int&gt;</c> property getter on an arm. It binds as
    /// <c>KotlinStateFlow&lt;int&gt; Ticks</c> off the arm's own prefix
    /// (<c>job_watching_get_ticks_collect</c> / <c>job_watching_get_ticks_value</c>), where today
    /// the property half is dropped with no diagnostic at all.
    /// <para>
    /// Both halves of the route in one test: the synchronous <c>.Value</c> read goes through
    /// <c>_value</c>, the bounded <c>await foreach</c> goes through <c>_collect</c>, and the arm
    /// holds the <c>MutableStateFlow</c> behind both, so a route that reads one of them off a
    /// different receiver disagrees with the other. <c>StateFlow</c> never completes, hence the
    /// cancellation after the replayed current value.
    /// </para>
    /// </summary>
    [Fact]
    public async Task Ticks_OnASealedArm_ReadsValueAndCollects()
    {
        using var factory = new JobFactory();

        // Mylo watches the window. His name is four letters long, so the tick count is 4.
        await using Job.Watching mylo = factory.Watching("Mylo");

        Assert.Equal(4, mylo.Ticks.Value);

        var seen = new List<int>();
        var cts = new CancellationTokenSource();
        await foreach (int tick in mylo.Ticks.WithCancellation(cts.Token))
        {
            seen.Add(tick);
            cts.Cancel();
        }

        Assert.Equal(4, seen[^1]);
    }

    /// <summary>
    /// The method half: a plain <c>Flow&lt;String&gt;</c> returned by a function the arm declares,
    /// which is the half named <c>SKIPPED_UNSUPPORTED_COMBINATION</c> today. <c>String</c> in and
    /// out, so the UTF8 pair rides the collect protocol on the parameter and on the element at once,
    /// and a plain <c>Flow</c> completes on its own, so no cancellation is needed to bound it.
    /// </summary>
    [Fact]
    public async Task Labels_OnASealedArm_Collects()
    {
        using var factory = new JobFactory();
        await using Job.Watching mylo = factory.Watching("Mylo");

        var labels = new List<string>();
        await foreach (string label in mylo.Labels("windowsill:"))
        {
            labels.Add(label);
        }

        Assert.Equal(["windowsill:Mylo"], labels);
    }

    /// <summary>
    /// The second arm of the <c>Flow</c> overload pair, asserted on its <em>values</em> rather than
    /// on its presence. Every emission here is unreachable from the one-parameter body, so if the
    /// <c>_2</c> suffix lands on the <c>[DllImport]</c> EntryPoint but not on
    /// <c>CirMethod.nativeName</c>, the two-parameter body resolves by arity to the first overload's
    /// extern, compiles, runs, and yields the single <c>"tick:Mylo"</c> instead.
    /// </summary>
    [Fact]
    public async Task Labels_SecondOverloadOnAnArm_ReturnsItsOwnEmissions()
    {
        using var factory = new JobFactory();
        await using Job.Watching mylo = factory.Watching("Mylo");

        var labels = new List<string>();
        await foreach (string label in mylo.Labels("tick", 3))
        {
            labels.Add(label);
        }

        Assert.Equal(["tick#0", "tick#1", "tick#2"], labels);
    }

    /// <summary>
    /// A <em>flow-only</em> arm takes the same async lifetime shape a suspending arm does: the
    /// collect protocol needs a scope of the arm's own, so <c>Watching</c> gains
    /// <c>IAsyncDisposable</c> and a <c>DisposeAsync</c> that drains it, exactly as an ordinary
    /// class whose only async member is a flow already does. <c>Done</c>, with neither a suspend nor
    /// a flow member, stays the arm without a scope, which is what keeps the interface off the
    /// sealed base.
    /// </summary>
    [Fact]
    public void Watching_IsAsyncDisposable_AndDoneIsNot()
    {
        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Watching)));
        Assert.False(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Job.Done)));
    }

    /// <summary>
    /// Coexistence: <c>Running</c> already carries suspend members, and a flow member on the same
    /// arm has to share their scope rather than emit a second one. Both routes in one receiver, so a
    /// duplicated <c>_scopeHandle</c> or a second <c>DisposeAsync</c> would fail the generated
    /// compile long before this asserts, and a scope wired to the wrong field fails it here.
    /// </summary>
    [Fact]
    public async Task Beats_OnASuspendingArm_Collects()
    {
        using var factory = new JobFactory();

        // Oreo, 60% of the way down the hallway, purring at sixty too.
        await using Job.Running oreo = factory.Running(60);

        Assert.Equal(60, oreo.Beats.Value);

        var seen = new List<int>();
        var cts = new CancellationTokenSource();
        await foreach (int beat in oreo.Beats.WithCancellation(cts.Token))
        {
            seen.Add(beat);
            cts.Cancel();
        }

        Assert.Equal(60, seen[^1]);
        Assert.Equal(60, await oreo.PauseAsync());
    }
}

/// <summary>
/// The other half of #115: the members that cannot bind must be <em>named</em>. A skipped member is
/// invisible from compiled C# (it simply is not there), so the only seam that can see <em>why</em>
/// is the ADR-100 diagnostics artifact the KSP round writes. These read it directly, the way
/// <c>BoxesDiagnosticsTests</c> reads <c>reverse-ir.json</c>.
/// <para>
/// ADR-116 made <c>pause</c> a <c>SKIPPED_UNSUPPORTED_COMBINATION</c> (the
/// <c>SEALED_SUBCLASS_UNROUTED</c> reason) and left <c>pickNested</c> on the ordinary
/// <c>SKIPPED_UNSUPPORTED_TYPE</c> the nested-interface gate already emits on an ordinary class.
/// ADR-118 routes the suspend half, so <c>pause</c> — and its numbered sibling <c>pause_2</c>,
/// <c>resume</c> and <c>Idle.nap</c> — must fall silent again while <c>pickNested</c> stays named.
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
    /// ADR-116's row, inverted by ADR-118: the suspend route is keyed to sealed arms now, so
    /// <c>pause</c> is <em>routed</em>, and a routed member must be silent. Nothing in the
    /// <c>issue115</c> package may name it under any symbol — neither the bare <c>pause</c> nor the
    /// numbered <c>pause_2</c> the planner mints for the second overload. Reporting a member that
    /// also binds is worse than silence: every consumer build would carry a false warning.
    /// </summary>
    [Fact]
    public void Pause_SuspendMemberOnASealedArm_IsNoLongerNamedAsAnUnsupportedCombination()
    {
        foreach ((string path, IReadOnlyList<Diagnostic> entries) in DiagnosticFiles())
        {
            string[] named = entries
                .Where(entry => entry.Declaration.StartsWith(Package, StringComparison.Ordinal))
                .Where(entry => entry.Declaration
                    .Split('.')
                    .Last()
                    .StartsWith("pause", StringComparison.Ordinal))
                .Select(entry => $"{entry.Kind} {entry.Declaration}")
                .OrderBy(line => line, StringComparer.Ordinal)
                .ToArray();

            Assert.True(
                named.Length == 0,
                $"{path} still names a suspend member the arm now binds:\n  " +
                string.Join("\n  ", named));
        }
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
            // ADR-116's 2026-09-13 amendment: the arm's override of a declined base member
            // binds at its declared arity, so a SKIPPED_ line naming it is a false alarm. The
            // base's own `Job.tag` is legitimately skipped (`@Unstable`) and stays off this list.
            $"{Package}.Job.Running.tag",
            $"{Package}.Job.Idle.poke",
            $"{Package}.Job.Idle.describe",
            // ADR-118: the suspend members the arms now bind. `pause_2` is the planner's numbered
            // symbol for the second overload, and the sealed post-process copies that symbol
            // through, so a post-process that reclassified only the numbered arm of the pair would
            // otherwise slip past the negative above.
            $"{Package}.Job.Running.pause",
            $"{Package}.Job.Running.pause_2",
            $"{Package}.Job.Running.resume",
            $"{Package}.Job.Idle.nap",
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

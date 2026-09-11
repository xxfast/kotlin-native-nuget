using TestLibrary.Issue115;

namespace IntegrationTests;

/// <summary>
/// Issue #115 / ADR-116, the lambda-parameter row. A method taking a function parameter (ADR-036)
/// declared on a <b>sealed arm</b> is absent from C# and named
/// <c>SKIPPED_UNSUPPORTED_COMBINATION</c> (<c>SEALED_SUBCLASS_UNROUTED</c>), where the same method
/// on an ordinary class binds: <c>Cat.DescribeWith(name =&gt; ...)</c> works today. ADR-118 closed
/// the <c>suspend</c> row and ADR-124 the <c>Flow</c> row by re-keying the legacy route onto the
/// arm's own export prefix; this is the third row, and the same shape of fix.
/// <para>
/// Both facts below are CS1061 today: <c>Job.Running</c> has no <c>Relabel</c> and
/// <c>Job.Idle</c> has no <c>PokeWith</c>. That is the red. Once the route re-keys, they arrive off
/// <c>job_running_relabel</c> and <c>job_idle_poke_with</c>, through the same thunk and
/// <c>GCHandle</c> pair the ordinary-class route already uses.
/// </para>
/// <para>
/// Two cells, not one, because the renderer branches on the outer return: Oreo's
/// <c>Relabel</c> is the <c>Func&lt;string, string&gt;</c> half with a <c>string</c> back out, and
/// Mylo's <c>PokeWith</c> is the <c>Action&lt;string&gt;</c> half with a <c>void</c> return on a
/// <c>data object</c> arm. A route that binds only the value-returning shape, or only the
/// <c>data class</c> receiver, cannot pass both.
/// </para>
/// </summary>
public class SealedArmLambdaTests
{
    /// <summary>
    /// The <c>Func</c> half on a <c>data class</c> arm. <c>string</c> in and out across the
    /// callback protocol: the lambda runs in C#, is handed Kotlin's <c>"running-9"</c> through the
    /// thunk, and its answer comes back out as the outer return.
    /// </summary>
    [Fact]
    public void Relabel_LambdaParameterOnASealedArm_StringInAndOutThroughTheCallback()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(9);

        Assert.Equal("RUNNING-9", oreo.Relabel(s => s.ToUpper()));
    }

    /// <summary>
    /// The same crossing reached through the sealed <b>base</b> and discriminated back onto the
    /// arm, so the binding cannot depend on the concrete-return spelling of
    /// <see cref="JobFactory.Running"/>. Oreo is always mid-hallway.
    /// </summary>
    [Fact]
    public void Relabel_OnAnArmDiscriminatedFromTheBase_StillCallsThrough()
    {
        using Job job = JobSample.AnyJob(40);
        Job.Running oreo = Assert.IsType<Job.Running>(job);

        Assert.Equal("running-40!", oreo.Relabel(s => s + "!"));
    }

    /// <summary>
    /// A <b>capturing</b> lambda on an arm: the closure state lives on the C# side of the
    /// <c>GCHandle</c>, so a route that marshals the delegate without keeping its target alive
    /// fails here rather than on the stateless case above.
    /// </summary>
    [Fact]
    public void Relabel_CapturingLambdaOnASealedArm_SeesTheCapturedState()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(50);
        string bowl = "bowl";

        Assert.Equal("running-50 to the bowl", oreo.Relabel(s => $"{s} to the {bowl}"));
    }

    /// <summary>
    /// The <c>Action</c> half on a <c>data object</c> arm: a <c>void</c> outer return, so nothing
    /// comes back except what the lambda collects. Mylo says exactly one thing when nudged.
    /// </summary>
    [Fact]
    public void PokeWith_UnitReturningLambdaOnAnObjectArm_CollectsTheArgument()
    {
        using var factory = new JobFactory();
        using Job.Idle mylo = factory.Idle();
        var said = new List<string>();

        mylo.PokeWith(said.Add);

        Assert.Equal(new List<string> { "idle" }, said);
    }
}

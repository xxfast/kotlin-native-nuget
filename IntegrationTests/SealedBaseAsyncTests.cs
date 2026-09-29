using TestLibrary.Curlup;
using TestLibrary.Issue115;

namespace IntegrationTests;

/// <summary>
/// ADR-175: a sealed base's own <c>suspend</c> / <c>Flow</c> / <c>StateFlow</c> members are reachable
/// through the <em>base-typed</em> reference. Every factory in <c>ShapeSample</c> returns a
/// <c>Shape</c>, never an arm, and every call below is made on that base.
/// <para>
/// This file cannot compile until the base carries the members: <c>AreaAsync</c>,
/// <c>FallbackAsync</c>, <c>DozeAsync</c>, <c>RollAsync</c>, <c>Ticks</c> and <c>Level</c> are
/// CS1061 on <c>Shape</c>, <c>RestAsync</c> is CS1061 on <c>Job</c>, and <c>await using</c> on a
/// base-typed local is CS8410 while the base is only <c>IDisposable</c>. That is the red.
/// </para>
/// <para>
/// Each arm answers with a different number, so a base export that dispatches to the wrong arm, or
/// never reaches Kotlin's virtual dispatch, fails on a value rather than passing by luck. The enum
/// arm is the load-bearing inferred claim: <c>asStableRef&lt;Shape&gt;()</c> on the boxed
/// <c>CurlArm</c>'s handle has to reach the entry's own override.
/// </para>
/// Oreo curls into a loaf or a tight ring; Mylo sprawls the length of the sofa or rings into a donut.
/// </summary>
public class SealedBaseAsyncTests
{
    public static TheoryData<string, int> AreaPerArm => new()
    {
        { "loaf", 6 },      // nested data class: Oreo, 2 paws wide, 2 * 3
        { "donut", 11 },    // nested data object: Mylo, ringed
        { "sprawl", 50 },   // sibling arm: Mylo, 5 cushions long, 5 * 10
        { "tight", 100 },   // enum arm, entry 0: Oreo, nose to tail
        { "loose", 101 },   // enum arm, entry 1
    };

    private static Shape ShapeFor(string arm) => arm switch
    {
        "loaf" => ShapeSample.LoafShape(2),
        "donut" => ShapeSample.DonutShape(),
        "sprawl" => ShapeSample.SprawlShape(5),
        "tight" => ShapeSample.CurlShape(false),
        "loose" => ShapeSample.CurlShape(true),
        _ => throw new ArgumentOutOfRangeException(nameof(arm)),
    };

    /// <summary>
    /// The restatement: <c>await shape.AreaAsync()</c> on the base, one arm kind per row, each
    /// answering with its own override through the base's own export.
    /// </summary>
    [Theory]
    [MemberData(nameof(AreaPerArm))]
    public async Task AreaAsync_ThroughTheSealedBase_DispatchesToEveryArmKind(string arm, int expected)
    {
        await using Shape shape = ShapeFor(arm);

        Assert.Equal(expected, await shape.AreaAsync());
    }

    /// <summary>
    /// The enum arm on its own, with the ADR-157 cross-check that the discriminator really landed on
    /// the boxed arm: the answer has to come from <c>Curl.TIGHT.area()</c>, which no C# type carries.
    /// </summary>
    [Fact]
    public async Task AreaAsync_OnAnEnumArmHeldAsTheBase_ReachesTheEntryOverride()
    {
        await using Shape oreo = ShapeSample.CurlShape(false);

        Assert.Equal(Curl.Tight, Assert.IsType<CurlArm>(oreo).Value);
        Assert.Equal(100, await oreo.AreaAsync());
    }

    /// <summary>
    /// A default body no arm overrides is on no C# type today. Through the base it answers the same
    /// nine minutes for every arm kind, the enum included.
    /// </summary>
    [Theory]
    [MemberData(nameof(AreaPerArm))]
    public async Task FallbackAsync_UnoverriddenDefaultBody_AnswersThroughTheBaseForEveryArm(string arm, int _)
    {
        await using Shape shape = ShapeFor(arm);

        Assert.Equal(9, await shape.FallbackAsync());
    }

    /// <summary>
    /// The Flow member collected through the base, dispatched per arm.
    /// </summary>
    [Fact]
    public async Task Ticks_CollectedThroughTheSealedBase_DispatchesPerArm()
    {
        await using Shape oreo = ShapeSample.LoafShape(2);
        await using Shape mylo = ShapeSample.SprawlShape(5);
        await using Shape curl = ShapeSample.CurlShape(true);

        Assert.Equal(new[] { 2, 3 }, await Collect(oreo.Ticks()));
        Assert.Equal(new[] { 5, 10 }, await Collect(mylo.Ticks()));
        Assert.Equal(new[] { 101 }, await Collect(curl.Ticks()));
    }

    /// <summary>
    /// The <c>StateFlow</c> property, silently dropped on the base today, read through the base.
    /// </summary>
    [Fact]
    public async Task Level_StateFlowPropertyThroughTheSealedBase_ReadsEachArmsValue()
    {
        await using Shape oreo = ShapeSample.LoafShape(2);
        await using Shape donut = ShapeSample.DonutShape();
        await using Shape mylo = ShapeSample.SprawlShape(5);
        await using Shape curl = ShapeSample.CurlShape(false);

        Assert.Equal(2, oreo.Level.Value);
        Assert.Equal(11, donut.Level.Value);
        Assert.Equal(5, mylo.Level.Value);
        Assert.Equal(100, curl.Level.Value);
    }

    /// <summary>
    /// A default body returning the sealed base: the base route's completion goes through
    /// <c>Shape.FromHandle</c>, and the donut it answers with is a different arm than Oreo's loaf.
    /// </summary>
    [Fact]
    public async Task RollAsync_SealedBaseReturnOnTheBaseRoute_Discriminates()
    {
        await using Shape oreo = ShapeSample.LoafShape(2);

        await using Shape rolled = await oreo.RollAsync();

        Assert.IsType<Shape.Donut>(rolled);
        Assert.Equal(11, await rolled.AreaAsync());
    }

    /// <summary>
    /// Cancellation reaches a base-dispatched member whose default body never completes.
    /// </summary>
    [Fact]
    public async Task DozeAsync_CancellationReachesTheBaseDispatch()
    {
        await using Shape mylo = ShapeSample.DonutShape();
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => mylo.DozeAsync(cts.Token));
    }

    /// <summary>
    /// An arm-declared member the base does not declare stays on the arm (ADR-118 unchanged) and runs
    /// on the base-owned scope beside a base member on the same instance.
    /// </summary>
    [Fact]
    public async Task KneadAsync_ArmOnlyMember_SharesTheBaseScopeWithABaseMember()
    {
        await using Shape shape = ShapeSample.LoafShape(2);
        Shape.Loaf oreo = Assert.IsType<Shape.Loaf>(shape);

        Assert.Equal(7, await oreo.KneadAsync(3));
        Assert.Equal(6, await shape.AreaAsync());
    }

    /// <summary>
    /// ADR-159 rule 4: an arm's override of a base-projected member is not re-projected, so every arm
    /// inherits the one declaration on <c>Shape</c>, and the base is the <c>IAsyncDisposable</c>.
    /// </summary>
    [Fact]
    public void BaseDeclaresTheAsyncMembers_ArmsInheritThem()
    {
        foreach (Type arm in new[] { typeof(Shape.Loaf), typeof(Shape.Donut), typeof(Sprawl), typeof(CurlArm) })
        {
            Assert.Same(typeof(Shape), arm.GetMethod("AreaAsync")!.DeclaringType);
            Assert.Same(typeof(Shape), arm.GetMethod("FallbackAsync")!.DeclaringType);
            Assert.Same(typeof(Shape), arm.GetMethod("Ticks")!.DeclaringType);
            Assert.Same(typeof(Shape), arm.GetProperty("Level")!.DeclaringType);
        }

        Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Shape)));
        Assert.Same(typeof(Shape.Loaf), typeof(Shape.Loaf).GetMethod("KneadAsync")!.DeclaringType);
    }

    /// <summary>
    /// Source compatibility: an arm-typed <c>using var</c> and an arm-typed call to a member that
    /// moved to the base both still compile and answer. The arm keeps <c>IDisposable</c> beside the
    /// new <c>IAsyncDisposable</c>.
    /// </summary>
    [Fact]
    public async Task ArmTypedCallers_StillCompileAndAnswer()
    {
        using var oreo = (Shape.Loaf)ShapeSample.LoafShape(4);

        Assert.Equal(12, await oreo.AreaAsync());
        Assert.IsAssignableFrom<IDisposable>(oreo);
    }

    /// <summary>
    /// The sealed-<em>class</em> half (<c>issue115</c>'s <c>Job</c>): <c>open suspend fun rest()</c>
    /// is a default body no arm overrides. Through the base it answers for an arm with suspend
    /// members of its own (<c>Running</c>, whose <c>PauseAsync</c> now shares the base scope) and for
    /// the arm with none (<c>Done</c>).
    /// </summary>
    [Fact]
    public async Task RestAsync_SealedClassBaseDefaultBody_AnswersThroughTheBase()
    {
        await using Job oreo = JobSample.AnyJob(4);
        Assert.Equal(0, await oreo.RestAsync());
        Assert.Equal(4, await Assert.IsType<Job.Running>(oreo).PauseAsync());

        await using Job done = ((Job.Running)oreo).Finish();
        Assert.Equal(0, await done.RestAsync());
    }

    private static async Task<int[]> Collect(IAsyncEnumerable<int> flow)
    {
        var values = new List<int>();
        await foreach (int value in flow) values.Add(value);
        return values.ToArray();
    }
}

using TestLibrary.Issue54;
using TestLibrary.Issue115;

namespace IntegrationTests;

/// <summary>
/// Item 35: a sealed base's <em>own</em> <c>abstract</c>/<c>open</c> members render no C# member on
/// the base at all. <c>CirSealedClass</c> carries neither properties nor methods
/// (<c>CirSealedRenderer</c> emits the handle, the constructor, <c>FromHandle</c> and
/// <c>Dispose()</c>, and nothing else), and both planners are arm-only, so a consumer holding a
/// <c>NestedShape</c> or a <c>Job</c> has to pattern-match to an arm before it can read anything.
/// <para>
/// Every call below is CS1061 today, and that is the red: <c>NestedShape</c> has no <c>Sides</c>
/// and no <c>Outline</c>, <c>Job</c> has no <c>Kind</c>, and <c>Job.Running</c> has no
/// <c>Describe</c> (the base declares it, the arm does not, and the declared-only gate drops it on
/// both sides). After the fix, a base-declared member lives on the C# base and Kotlin's own virtual
/// dispatch picks the arm's body.
/// </para>
/// <para>
/// The cells are one per mechanism the base carrier has to cross, not the fewest types:
/// an <c>abstract val</c> read through the base on two different arms (one of which narrows the
/// type covariantly, <c>Empty.sides: Int</c> over <c>Int?</c>, which C# cannot spell as an
/// <c>override</c>), an <c>abstract fun</c> called through the base on an arm that overrides it,
/// an <c>open val</c> with a base body read on an overriding arm and on an inheriting one, and an
/// <c>open fun</c> with a base body called on an arm that does not override it.
/// </para>
/// <para>
/// Oreo curls into a circle and always has an outline; Mylo sprawls into no shape at all, has zero
/// sides about it, and is the arm that inherits everything rather than overriding it.
/// </para>
/// </summary>
public class SealedBaseMemberTests
{
    // ---- The base's own abstract members: NestedShape. ----

    /// <summary>
    /// The reported repro shape: <c>abstract val sides: Int?</c> read through a <c>NestedShape</c>
    /// reference, on the <c>data class</c> arm. Oreo's circle has no side count.
    /// </summary>
    [Fact]
    public void Sides_AbstractBaseProperty_ReadsThroughTheBaseOnTheDataClassArm()
    {
        using NestedShape shape = NestedShapeSample.AnyShape(2.0);

        Assert.Null(shape.Sides);
    }

    /// <summary>
    /// The same read on the <c>data object</c> arm, which is also the <em>covariant</em> cell:
    /// <c>Empty</c> narrows <c>Int?</c> to <c>Int</c>. Whatever the arm's own member ends up spelled
    /// as, the base-typed read must still answer with the arm's value, boxed into the base's
    /// nullable type by the base's own getter. Mylo's sprawl: zero sides.
    /// </summary>
    [Fact]
    public void Sides_AbstractBaseProperty_ReadsThroughTheBaseOnTheCovariantObjectArm()
    {
        using NestedShape shape = NestedShapeSample.EmptyShape();

        Assert.Equal(0, shape.Sides);
    }

    /// <summary>
    /// The arm's own narrowed member must keep reading as a non-nullable <c>int</c>, whether it
    /// stays an <c>override</c> or becomes <c>new</c>: the base carrier may not cost the arm the
    /// covariant type Kotlin declared.
    /// </summary>
    [Fact]
    public void Sides_OnTheCovariantArmItself_StaysNonNullable()
    {
        using NestedShape shape = NestedShapeSample.EmptyShape();

        var empty = Assert.IsType<NestedShape.Empty>(shape);
        int sides = empty.Sides;
        Assert.Equal(0, sides);
    }

    /// <summary>
    /// The method half: <c>abstract fun outline()</c> called through a <c>NestedShape</c> reference.
    /// Both arms override it with different bodies, so the value is what proves Kotlin dispatched
    /// into the arm rather than answering from some base default.
    /// </summary>
    [Fact]
    public void Outline_AbstractBaseMethod_DispatchesToTheArmThroughTheBase()
    {
        using NestedShape oreo = NestedShapeSample.AnyShape(3.0);
        using NestedShape mylo = NestedShapeSample.EmptyShape();

        Assert.Equal("circle", oreo.Outline());
        Assert.Equal("sprawl", mylo.Outline());
    }

    // ---- The base's own open members with bodies: Job. ----

    /// <summary>
    /// <c>open val kind</c> read through a <c>Job</c> reference on the one arm that overrides it.
    /// Oreo, running.
    /// </summary>
    [Fact]
    public void Kind_OpenBaseProperty_ReadsTheOverridingArmsValueThroughTheBase()
    {
        using Job job = JobSample.AnyJob(40);

        Assert.Equal("running", job.Kind);
    }

    /// <summary>
    /// The same read on an arm that does <em>not</em> override it: the base's own body answers, and
    /// the arm needs no member of its own. Mylo, loafing.
    /// </summary>
    [Fact]
    public void Kind_OpenBaseProperty_ReadsTheBaseBodyOnTheInheritingArm()
    {
        using Job job = JobSample.IdleJob();

        Assert.Equal("job", job.Kind);
    }

    /// <summary>
    /// <c>open fun describe()</c> has a body on the base and <c>Job.Running</c> does not override
    /// it, so today it renders on neither the base nor that arm. Once the base carries it, calling
    /// it on a <c>Running</c> receiver answers with the base's body.
    /// </summary>
    [Fact]
    public void Describe_InheritedBaseMethodBody_CallsThroughFromTheArmThatDoesNotOverrideIt()
    {
        using var factory = new JobFactory();
        using Job.Running oreo = factory.Running(40);

        Assert.Equal("job", oreo.Describe());
    }

    /// <summary>
    /// And the arm that <em>does</em> override it still answers with its own body when it is
    /// reached through the base, which is the pair that separates "the base member exists" from
    /// "the base member dispatches".
    /// </summary>
    [Fact]
    public void Describe_OverriddenOnTheObjectArm_StillAnswersTheArmsBodyThroughTheBase()
    {
        using Job job = JobSample.IdleJob();

        Assert.Equal("idle", job.Describe());
    }
}

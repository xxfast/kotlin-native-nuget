using System.Linq;
using System.Reflection;
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

    // ---- A base member whose trailing default lives on the interface it overrides: Pose. ----

    /// <summary>
    /// The subject. <c>Pose.squish</c> overrides <c>Squishy.squish</c>, whose <c>factor</c> carries
    /// the default, and Kotlin forbids the override from restating it. The sealed base pass reads
    /// the raw <c>hasDefault</c> bit off the override's own parameters, and KSP 2.3.10 reports
    /// that bit as <c>true</c> there, so under ADR-164 the C# base's <c>Squish</c> takes an optional
    /// <c>double? factor = null</c> and every arm inherits or overrides that one signature. The short call below pins that on the static types in the hierarchy.
    /// <para>
    /// Read through the <em>base</em> static type, on the arm that overrides the member. The
    /// unset argument must agree with the explicit-default call, and Oreo's curl must be what
    /// answers, not some base default.
    /// </para>
    /// </summary>
    [Fact]
    public void Squish_OmittedFactorIsInheritedFromTheBase_OnTheOverridingArm()
    {
        using Pose oreo = PoseSample.CurledPose(2);

        Assert.Equal(oreo.Squish(1.0), oreo.Squish());
        Assert.Equal(2.0, oreo.Squish());
    }

    /// <summary>
    /// The same short call through the <em>arm</em> static type. The arm owes no member of its own
    /// (ADR-116 makes the base the carrier); C# inheritance is what has to supply this.
    /// </summary>
    [Fact]
    public void Squish_OmittedFactorIsInheritedFromTheBase_ThroughTheArmStaticType()
    {
        using Pose pose = PoseSample.CurledPose(3);

        Pose.Croissant oreo = Assert.IsType<Pose.Croissant>(pose);

        Assert.Equal(oreo.Squish(1.0), oreo.Squish());
        Assert.Equal(3.0, oreo.Squish());
    }

    /// <summary>
    /// The arm that overrides nothing: the base's own body answers, through the base reference and
    /// through the <c>data object</c> arm's static type alike. Mylo squishes by exactly what you
    /// ask, which for the omitted argument is the interface's <c>1.0</c>.
    /// </summary>
    [Fact]
    public void Squish_OmittedFactorReachesTheInheritingObjectArm()
    {
        using Pose mylo = PoseSample.AnyPose();

        Assert.Equal(mylo.Squish(1.0), mylo.Squish());
        Assert.Equal(1.0, mylo.Squish());

        Pose.Splat splat = Assert.IsType<Pose.Splat>(mylo);

        Assert.Equal(1.0, splat.Squish());
    }

    /// <summary>
    /// The control, and the one cell here that does not depend on reading defaults through the
    /// override chain: <c>Pose.settle</c>'s default is declared on the base itself, so the raw bit
    /// is already <c>true</c>. This pins that the sealed base pass widens its own default end to
    /// end. If this is red, that is a second defect.
    /// </summary>
    [Fact]
    public void Settle_BaseOwnedDefault_WidensTheBasesOwnParameter()
    {
        using Pose mylo = PoseSample.AnyPose();

        Assert.Equal(mylo.Settle(3), mylo.Settle());
        Assert.Equal(6, mylo.Settle());
    }

    /// <summary>
    /// The shape of the fix, not just its effect: the base declares one <c>Squish(double?)</c>
    /// with an optional <c>factor</c>, there is no zero-argument overload anywhere, and any arm
    /// that declares <c>Squish</c> itself declares the same widened signature (C# overriding
    /// demands it), never a separate full arity beside it.
    /// </summary>
    [Fact]
    public void Squish_WidenedSignature_IsTheOnlyShape_OnBaseAndArm()
    {
        Assert.Null(typeof(Pose.Croissant).GetMethod("Squish", Type.EmptyTypes));

        MethodInfo? widened = typeof(Pose).GetMethod("Squish", [typeof(double?)]);
        Assert.NotNull(widened);
        Assert.True(widened!.GetParameters()[0].IsOptional);

        MethodInfo[] onTheArm = typeof(Pose.Croissant)
            .GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
            .Where(method => method.Name == "Squish")
            .ToArray();

        Assert.All(onTheArm, method =>
            Assert.Equal(typeof(double?), Assert.Single(method.GetParameters()).ParameterType));
    }

    // ---- The same base member, with the default rooted one module away: Biscuit, Burrito. ----

    /// <summary>
    /// The klib-rooted twin of the <c>Pose</c> cells above. <c>Biscuit.fluff</c> overrides
    /// <c>dev.other.core.UnexportedFluffy.fluff</c>, whose <c>pats</c> carries the default and which
    /// resolves out of <c>:test-models</c>' klib rather than out of source. The sealed base pass
    /// reads the raw bit, and this pins that klib metadata carries it onto the override just as
    /// source does, so <c>Fluff()</c> compiles for the same reason <c>Squish()</c> does.
    /// <para>
    /// Through the base static type, on the arm that overrides the member. Oreo, making biscuits.
    /// </para>
    /// </summary>
    [Fact]
    public void Fluff_KlibRootedInterfaceDefault_InheritsTheOptionalParameterFromTheBase()
    {
        using Biscuit oreo = QuiltSample.AnyBiscuit(3);

        Assert.Equal(oreo.Fluff(2), oreo.Fluff());
        Assert.Equal(6, oreo.Fluff());
    }

    /// <summary>The same short call through the arm static type.</summary>
    [Fact]
    public void Fluff_KlibRootedInterfaceDefault_ThroughTheArmStaticType()
    {
        using Biscuit biscuit = QuiltSample.AnyBiscuit(4);

        Biscuit.Shortbread oreo = Assert.IsType<Biscuit.Shortbread>(biscuit);

        Assert.Equal(oreo.Fluff(2), oreo.Fluff());
        Assert.Equal(8, oreo.Fluff());
    }

    /// <summary>
    /// The superclass shape of the same boundary: <c>Burrito</c> extends the klib open class
    /// <c>dev.other.core.UnexportedQuilt</c>, which ADR-101 drops as an unexported supertype, and
    /// overrides its defaulted <c>tuck</c>. Mylo, rolled up and staying there.
    /// </summary>
    [Fact]
    public void Tuck_KlibRootedSuperclassDefault_InheritsTheOptionalParameterFromTheBase()
    {
        using Burrito mylo = QuiltSample.AnyBurrito(2);

        Assert.Equal(mylo.Tuck(3), mylo.Tuck());
        Assert.Equal(5, mylo.Tuck());
    }

    /// <summary>The same short call through the arm static type.</summary>
    [Fact]
    public void Tuck_KlibRootedSuperclassDefault_ThroughTheArmStaticType()
    {
        using Burrito burrito = QuiltSample.AnyBurrito(5);

        Burrito.Snug mylo = Assert.IsType<Burrito.Snug>(burrito);

        Assert.Equal(mylo.Tuck(3), mylo.Tuck());
        Assert.Equal(8, mylo.Tuck());
    }

    /// <summary>
    /// Same shape assertion as the <c>Pose</c> cell: no zero-argument overload exists, and the
    /// widened signature with an optional <c>int?</c> is reachable from the arm's static type.
    /// </summary>
    [Fact]
    public void KlibRootedDefaults_AreOneWidenedSignature()
    {
        Assert.Null(typeof(Biscuit.Shortbread).GetMethod("Fluff", Type.EmptyTypes));
        Assert.Null(typeof(Burrito.Snug).GetMethod("Tuck", Type.EmptyTypes));

        MethodInfo? fluff = typeof(Biscuit.Shortbread).GetMethod("Fluff", [typeof(int?)]);
        MethodInfo? tuck = typeof(Burrito.Snug).GetMethod("Tuck", [typeof(int?)]);

        Assert.NotNull(fluff);
        Assert.NotNull(tuck);
        Assert.True(fluff!.GetParameters()[0].IsOptional);
        Assert.True(tuck!.GetParameters()[0].IsOptional);
    }
}

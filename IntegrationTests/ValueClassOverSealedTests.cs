using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// A Kotlin <c>value class</c> whose underlying type is a <em>sealed</em> class, at a property
/// position: <c>value class ObservationResult(val observation: Observation)</c> on
/// <see cref="ObservationDesk"/>.
/// <para>
/// <c>ForwardPropertyPlanner</c> rewrites the property type through <c>sealedAsHandle()</c>, which
/// recurses through <c>Nullable</c> and collection components but not through
/// <c>BridgeType.ValueClass.underlying</c>. So <c>isPlannable</c> still sees a
/// <c>SpecializedProtocol</c> underlying and drops <c>Result</c>, <c>Maybe</c> and <c>Current</c>
/// with <c>SKIPPED_UNSUPPORTED_PROPERTY</c>. These tests cannot compile until the recursion lands,
/// which is the red signal.
/// </para>
/// <para>
/// Expected shape once it does: the getter reconstructs
/// <c>new ObservationResult(Observation.FromHandle(p))</c>, exactly how a bare
/// <c>val o: Observation</c> reads today, and the setter hands over
/// <c>value.Observation._handle</c>. The record struct
/// <c>ObservationResult(global::TestLibrary.Cat.Observation Observation)</c> already exists and
/// already has a public positional constructor over the base, pinned by
/// <see cref="ReferenceValueClassTests"/>, so nothing about the wrapper itself needs to change.
/// </para>
/// <para>
/// Oreo owns the desk: alive on the windowsill, every time you look. Mylo is whatever the box says.
/// </para>
/// </summary>
public class ValueClassOverSealedTests
{
    [Fact]
    public void Result_ValueClassOverSealed_DiscriminatesToTheRightArm()
    {
        using ObservationDesk desk = ObservationDeskKt.ObservationDesk();

        ObservationResult result = desk.Result;

        var alive = Assert.IsType<Observation.Alive>(result.Observation);
        using Cat oreo = alive.Cat;
        Assert.Equal("Oreo", oreo.Name);
    }

    [Fact]
    public void Result_ValueClassOverSealed_KeepsItsOwnMethods()
    {
        using ObservationDesk desk = ObservationDeskKt.ObservationDesk();

        ObservationResult result = desk.Result;

        Assert.Equal("Alive: Oreo", result.Describe());
    }

    [Fact]
    public void Maybe_NullableValueClassOverSealed_IsNullWhenKotlinSaysNull()
    {
        using ObservationDesk desk = ObservationDeskKt.ObservationDesk();

        ObservationResult? maybe = desk.Maybe;

        Assert.Null(maybe);
    }

    [Fact]
    public void Maybe_NullableValueClassOverSealed_RoundTripsTheSameArm()
    {
        using ObservationDesk desk = ObservationDeskKt.ObservationDesk();

        desk.Maybe = desk.Result;

        Assert.NotNull(desk.Maybe);
        ObservationResult roundTripped = desk.Maybe!.Value;
        Assert.IsType<Observation.Alive>(roundTripped.Observation);
        Assert.Equal("Alive: Oreo", roundTripped.Describe());
    }

    [Fact]
    public void Current_NonNullSetter_AcceptsAWrapperBuiltInCSharp()
    {
        using ObservationDesk desk = ObservationDeskKt.ObservationDesk();

        desk.Current = new ObservationResult(desk.Observe());

        Assert.Equal("Alive: Oreo", desk.CurrentDescription());
    }

    [Fact]
    public void Current_NonNullValueClassOverSealed_ReadsTheSuperpositionArm()
    {
        using ObservationDesk desk = ObservationDeskKt.ObservationDesk();

        ObservationResult current = desk.Current;

        Assert.IsType<Observation.Superposition>(current.Observation);
        Assert.Equal("Unknown", current.Describe());
    }

    [Fact]
    public void Observe_BareSealedMemberReturn_StillBinds()
    {
        using ObservationDesk desk = ObservationDeskKt.ObservationDesk();

        using Observation observation = desk.Observe();

        Assert.IsType<Observation.Alive>(observation);
    }
}

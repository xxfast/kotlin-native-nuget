using TestLibrary;
using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// Cross-namespace value-class underlying: the value-class mirror of issue
/// <a href="https://github.com/xxfast/kotlin-native-nuget/issues/41">#41</a>.
/// <para>
/// <see cref="Disposition"/> and <see cref="PatientRef"/> live in the root Kotlin package and so
/// land in the bare <c>TestLibrary</c> namespace, while their underlyings — <c>Mood</c> (enum) and
/// <c>Patient</c> (class) — live in <c>...test.clinic</c> and so land in
/// <c>TestLibrary.Clinic</c>. The generated record structs spell the underlying member type bare
/// (<c>public Mood Mood { get; }</c>, <c>record struct PatientRef(Patient Patient)</c>), which
/// does not resolve from inside <c>namespace TestLibrary</c>: CS0246. Only a
/// <c>global::TestLibrary.Clinic.*</c> spelling resolves across that hop.
/// </para>
/// <para>
/// Both underlying kinds are covered because they are two distinct render sites — the
/// hand-written struct for enum/primitive underlyings and the positional record struct for
/// ObjectHandle underlyings. These tests cannot compile until the generated file does; the
/// failure is in the generated code, not here, which is exactly the red signal.
/// </para>
/// <para>
/// Oreo runs the desk and cannot hold a mood for a whole test; Mylo is content to be a referral.
/// </para>
/// </summary>
public class CrossNamespaceValueClassTests
{
    [Fact]
    public void Disposition_Constructor_WrapsTheCrossNamespaceEnumAndReadsItBack()
    {
        // Constructor + the struct's own underlying member, the two sites that carry the
        // cross-namespace spelling. Anxious is ordinal 1, so a zero-initialised struct cannot
        // pass this by accident.
        var disposition = new Disposition(Mood.Anxious);

        Assert.Equal(Mood.Anxious, disposition.Mood);
    }

    [Fact]
    public void DispositionDesk_Current_EnumUnderlyingProperty_RoundTripsTwoDistinctNonDefaultOrdinals()
    {
        using var desk = new DispositionDesk("Oreo");

        // The Kotlin default is Mood.CALM (ordinal 0), so both values written here are
        // non-default and distinct from each other: neither a stuck default nor a same-value
        // echo can make this pass.
        desk.Current = new Disposition(Mood.Playful);
        Assert.Equal(Mood.Playful, desk.Current.Mood);

        desk.Current = new Disposition(Mood.Anxious);
        Assert.Equal(Mood.Anxious, desk.Current.Mood);
    }

    [Fact]
    public void DispositionDesk_Flip_EnumUnderlyingParameterAndReturn_CrossTheWireBothWays()
    {
        using var desk = new DispositionDesk("Oreo");

        // Different ordinal in from out in each direction, so the wire is proven, not echoed.
        Assert.Equal(Mood.Playful, desk.Flip(new Disposition(Mood.Calm)).Mood);
        Assert.Equal(Mood.Calm, desk.Flip(new Disposition(Mood.Playful)).Mood);
    }

    [Fact]
    public void PatientRef_Constructor_WrapsTheCrossNamespaceClassAndReadsItBack()
    {
        using var mylo = new Patient("Mylo");

        var referral = new PatientRef(mylo);

        Assert.Equal("Mylo", referral.Patient.Name);
    }

    [Fact]
    public void DispositionDesk_Describe_ObjectHandleUnderlyingParameter_CrossesTheWire()
    {
        using var desk = new DispositionDesk("Oreo");
        using var mylo = new Patient("Mylo");

        Assert.Equal("Oreo is minding Mylo", desk.Describe(new PatientRef(mylo)));
    }
}

using TestLibrary.Clinic;

namespace IntegrationTests;

/// <summary>
/// Value-class receivers on extensions — two folded halves, no ADR (a clean mirror of ADR-077's
/// parameter lowering at the receiver slot).
///
/// Half 1, extension-property receiver widening: <c>ForwardPropertyPlanner.extensionProperty</c>'s
/// <c>supportedReceiver</c> check admits a value-class receiver only over a
/// <c>Primitive</c>/<c>String</c> underlying (<c>ChartId.symptomTags</c>,
/// <see cref="CollectionPropertyIndependenceTests"/>, is the <c>String</c> precedent). An
/// enum-underlying (<see cref="Temperament"/>) or ObjectHandle-underlying (<see cref="ChartRef"/>)
/// value-class receiver still hits the named <c>SKIPPED_UNSUPPORTED_PROPERTY</c> receiver
/// diagnostic and the whole property vanishes: today, <see cref="DosageExtensions"/>,
/// <see cref="TemperamentExtensions"/> and <see cref="ChartRefExtensions"/> below do not exist —
/// these tests fail with CS1061 ("does not contain a definition for ...") until the receiver
/// widening lands. <c>Dosage</c>'s primitive underlying is already admitted by the planner but has
/// no fixture (ROADMAP.md: "A primitive-underlying value-class extension-property receiver is also
/// untested, only the String-underlying case (ChartId) is covered") — that member is expected to
/// bind on its own, but the C# side never generated because <c>packNuget</c> never completes (see
/// half 2 below), so today it fails exactly the same CS1061 way as the other two.
///
/// Half 2, extension-FUNCTION value-class receivers: <c>ForwardCallablePlanner.extensionEntry</c>
/// has *admitted* a value-class receiver of any underlying since ADR-077, but nothing downstream
/// lowers it correctly. Verified with a real KSP run: the generated Kotlin wrapper types its
/// <c>receiver</c> parameter as the wire (<c>String</c> for <see cref="ChartId"/>, <c>Int</c> for
/// <see cref="Temperament"/>'s ordinal) and then calls <c>receiver.abbreviate(...)</c> /
/// <c>receiver.escalate()</c> directly on that wire value — <c>konanc</c> rejects both with
/// "Unresolved reference. None of the following candidates is applicable because of a receiver
/// type mismatch", so <c>test-library:compileKotlinMacosArm64</c> (and mingwX64) fail outright and
/// <c>packNuget</c> never reaches the C# generation step at all. Zero fixtures declared one before
/// this file, so this shape has never actually run.
///
/// Oreo keeps losing his chart notes; Mylo's temperament keeps escalating past reasonable limits.
/// </summary>
public class ValueClassReceiverTests
{
    // --- Half 1: extension-property receiver widening ---

    [Fact]
    public void Dosage_Label_RoundTrips()
    {
        var dosage = new Dosage(2.5);

        dosage.SetLabel("Oreo's evening dose");

        Assert.Equal("Oreo's evening dose", dosage.GetLabel());
    }

    [Fact]
    public void Dosage_Label_DefaultsToEmpty()
    {
        var dosage = new Dosage(1.0);

        Assert.Empty(dosage.GetLabel());
    }

    [Fact]
    public void Temperament_Note_RoundTrips()
    {
        // Non-first Mood ordinal on purpose: Calm (ordinal 0) would not distinguish a genuine
        // re-wrapped Temperament from a stray zeroed wire.
        var temperament = new Temperament(Mood.Anxious);

        temperament.SetNote("hisses at the vacuum");

        Assert.Equal("hisses at the vacuum", temperament.GetNote());
    }

    [Fact]
    public void Temperament_Note_DefaultsToEmpty()
    {
        var temperament = new Temperament(Mood.Playful);

        Assert.Empty(temperament.GetNote());
    }

    [Fact]
    public void ChartRef_Annotation_RoundTrips()
    {
        using var oreo = new Patient("Oreo");
        var chartRef = new ChartRef(oreo);

        chartRef.SetAnnotation("microchipped");

        Assert.Equal("microchipped", chartRef.GetAnnotation());
    }

    [Fact]
    public void ChartRef_Annotation_SurvivesKeyedByTheSamePatient()
    {
        // ChartRef's equality delegates to the underlying Patient instance the StableRef
        // resolves to, so a second ChartRef wrapping the same handle must see the first's write:
        // the Kotlin-side map is keyed by Patient identity, not by which C# ChartRef built it.
        using var mylo = new Patient("Mylo");
        var first = new ChartRef(mylo);
        var second = new ChartRef(mylo);

        first.SetAnnotation("due for a checkup");

        Assert.Equal("due for a checkup", second.GetAnnotation());
    }

    [Fact]
    public void ChartRef_Annotation_DefaultsToEmpty()
    {
        using var oreo = new Patient("Oreo");
        var chartRef = new ChartRef(oreo);

        Assert.Empty(chartRef.GetAnnotation());
    }

    // --- Half 2: extension-FUNCTION value-class receivers ---

    [Fact]
    public void ChartId_Abbreviate_ReturnsThePrefixOfTheUnderlyingString()
    {
        var chartId = new ChartId("CH-OREO-42");

        Assert.Equal("CH-", chartId.Abbreviate(3));
    }

    [Fact]
    public void ChartId_Abbreviate_ReturnsTheWholeValueWhenLengthExceedsIt()
    {
        var chartId = new ChartId("CH-9");

        Assert.Equal("CH-9", chartId.Abbreviate(20));
    }

    [Fact]
    public void Temperament_Escalate_ReturnsADifferentTemperamentNotAnEcho()
    {
        var calm = new Temperament(Mood.Calm);

        Temperament escalated = calm.Escalate();

        Assert.Equal(Mood.Anxious, escalated.Mood);
        Assert.NotEqual(calm, escalated);
    }

    [Fact]
    public void Temperament_Escalate_StopsAtPlayful()
    {
        var playful = new Temperament(Mood.Playful);

        Temperament escalated = playful.Escalate();

        Assert.Equal(Mood.Playful, escalated.Mood);
    }
}

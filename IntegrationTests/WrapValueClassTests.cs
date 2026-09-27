using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Clinic;
using TestLibrary.Parcel;

namespace IntegrationTests;

/// <summary>
/// ADR-171: a Kotlin value class (a C# <c>readonly record struct</c>) at an erased generic
/// position crosses as a real boxed value class in both directions, for every underlying kind.
///
/// Two routes share the one <c>NugetMarshal.Wrap&lt;T&gt;</c> / <c>FromHandle&lt;T&gt;</c> pair:
/// <list type="bullet">
/// <item>the ADR-147 generic class, where the consumer picks <c>T</c>
/// (<c>new Box&lt;ChartId&gt;(id)</c>). This compiles today and throws
/// <c>NotSupportedException</c> at the argument, because <c>Wrap&lt;T&gt;</c> has no branch for a
/// record struct and <c>Factories</c> has no value-class entry for the read;</item>
/// <item>the lambda route (<c>KotlinFunc</c>, <c>KotlinSuspendFunc</c>), where the member is
/// skipped today because a value class is not an admitted lambda type argument, so this file does
/// not compile until it is admitted.</item>
/// </list>
///
/// Equality is the assertion wherever the underlying is a value (String, primitive, enum): a
/// record struct compares by its underlying, so <c>Assert.Equal(id, box.Value)</c> proves the
/// round trip. The ObjectHandle underlying (<see cref="ChartRef"/>) reads back a fresh
/// <see cref="Patient"/> wrapper, so it asserts the patient's name and disposes the wrapper.
///
/// Oreo (black, white middle) and Mylo (brown and creamy) are both on the ward for eating a hair
/// tie, and every chart below is theirs.
/// </summary>
public class WrapValueClassTests
{
    // --- ADR-147 generic class, one test per underlying kind -------------------------------

    [Fact]
    public void WrapValueClass_GenericClass_StringUnderlying_RoundTrips()
    {
        var id = new ChartId("CH-OREO-1");
        using var box = new Box<ChartId>(id);

        Assert.Equal(id, box.Value);
        Assert.Equal("CH-OREO-1", box.Value.Value);
    }

    [Fact]
    public void WrapValueClass_GenericClass_PrimitiveUnderlying_RoundTrips()
    {
        // Mylo's hair-tie antidote, by weight.
        var dose = new Dosage(2.5);
        using var box = new Box<Dosage>(dose);

        Assert.Equal(dose, box.Value);
        Assert.Equal(2.5, box.Value.Milligrams);
    }

    [Fact]
    public void WrapValueClass_GenericClass_EnumUnderlying_RoundTrips()
    {
        var temperament = new Temperament(TestLibrary.Clinic.Mood.Anxious);
        using var box = new Box<Temperament>(temperament);

        Assert.Equal(temperament, box.Value);
        Assert.Equal(TestLibrary.Clinic.Mood.Anxious, box.Value.Mood);
    }

    [Fact]
    public void WrapValueClass_GenericClass_ObjectHandleUnderlying_RoundTrips()
    {
        using var mylo = new Patient("Mylo");
        using var box = new Box<ChartRef>(new ChartRef(mylo));

        using Patient readBack = box.Value.Patient;
        Assert.Equal("Mylo", readBack.Name);
    }

    [Fact]
    public void WrapValueClass_GenericClass_SealedClassUnderlying_RoundTrips()
    {
        // A sealed base is a handle-backed underlying too, rebuilt through its `FromHandle`
        // discriminator, so the arm Kotlin holds is the arm C# reads back.
        using var observation = ObservationKt.OpenBox("Oreo");
        using var box = new Box<ObservationResult>(new ObservationResult(observation));

        ObservationResult readBack = box.Value;
        using Observation arm = readBack.Observation;
        Assert.IsType<Observation.Alive>(arm);
        Assert.Equal("Alive: Oreo", readBack.Describe());
    }

    [Fact]
    public void WrapValueClass_GenericClass_KotlinSeesTheBoxedValueClass_NotItsUnderlying()
    {
        // `describe(tag: T)` is `"$tag:$item"`, so Kotlin's own toString shows what it was handed.
        // A raw underlying smuggled into the slot prints bare ("CH-OREO-1") and would pass an
        // equality round trip; only a real boxed ChartId prints its class name.
        var oreo = new ChartId("CH-OREO-1");
        var mylo = new ChartId("CH-MYLO-2");
        using var crate = new Crate<ChartId>(oreo);

        Assert.Equal("ChartId(value=CH-MYLO-2):ChartId(value=CH-OREO-1)", crate.Describe(mylo));
        Assert.Equal(mylo, crate.Pick(mylo));
        Assert.Equal(oreo, crate.Item);
    }

    [Fact]
    public void WrapValueClass_GenericClass_NullableTypeArgument_CarriesAValue()
    {
        // `FromHandle<ChartId?>` looks up `typeof(Nullable<ChartId>)` unless it normalises first.
        ChartId? id = new ChartId("CH-MYLO-2");
        using var slot = new Slot<ChartId?>(id);

        Assert.Equal(id, slot.Value);
        Assert.Equal(id, slot.Current);
        Assert.Null(slot.Previous);
    }

    // The null half of the sibling above: a null `ChartId?` has no box to mint, so it crosses as
    // the null pointer and every getter reads null back.
    [Fact]
    public void WrapValueClass_GenericClass_NullableTypeArgument_CarriesNull()
    {
        using var slot = new Slot<ChartId?>(null);

        Assert.Null(slot.Value);
        Assert.Null(slot.Current);
        Assert.Null(slot.Previous);
    }

    [Fact]
    public void WrapValueClass_GenericClass_ValueClassInit_ThrowsAtTheBoundary()
    {
        // A positional record struct (object-handle underlying) never ran WardBand's `init` in C#;
        // the box export is the first place it runs, so the rejection must surface exactly as the
        // ordinary route surfaces it. (CatId cannot show this: its String underlying renders a
        // hand-written record struct whose own constructor already runs `init` through Kotlin.)
        using var nameless = new Patient("");
        var band = new WardBand(nameless);

        Assert.ThrowsAny<ArgumentException>(() => new Box<WardBand>(band));
    }

    // --- Lambda route: value class as payload ----------------------------------------------

    [Fact]
    public void WrapValueClass_LambdaPayload_StringUnderlying_Invokes()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<ChartId, string> onChart = courier.OnChart;

        Assert.Equal("Ward 9 filed CH-OREO-1", onChart.Invoke(new ChartId("CH-OREO-1")));
    }

    [Fact]
    public void WrapValueClass_LambdaPayload_PrimitiveUnderlying_Invokes()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<Dosage, double> onDose = courier.OnDose;

        Assert.Equal(5.0, onDose.Invoke(new Dosage(2.5)));
    }

    [Fact]
    public void WrapValueClass_LambdaPayload_EnumUnderlying_Invokes()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<Temperament, string> onMood = courier.OnMood;

        Assert.Equal("Ward 9 saw CALM", onMood.Invoke(new Temperament(TestLibrary.Clinic.Mood.Calm)));
    }

    [Fact]
    public void WrapValueClass_LambdaPayload_ObjectHandleUnderlying_Invokes()
    {
        using var courier = new ChartCourier("Ward 9");
        using var oreo = new Patient("Oreo");
        using KotlinFunc<ChartRef, string> onChartRef = courier.OnChartRef;

        Assert.Equal("Ward 9 paged Oreo", onChartRef.Invoke(new ChartRef(oreo)));
    }

    [Fact]
    public void WrapValueClass_LambdaPayload_Nullable_CarriesNullAndAValue()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<ChartId?, string> onMaybeChart = courier.OnMaybeChart;

        Assert.Equal("Ward 9: no chart", onMaybeChart.Invoke(null));
        Assert.Equal("CH-MYLO-2", onMaybeChart.Invoke(new ChartId("CH-MYLO-2")));
    }

    [Fact]
    public void WrapValueClass_LambdaPayload_ArityTwo_BothSlotsCross()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<ChartId, CatId, string> onChartForCat = courier.OnChartForCat;

        Assert.Equal("CH-OREO-1 for oreo-7",
            onChartForCat.Invoke(new ChartId("CH-OREO-1"), new CatId("oreo-7")));
    }

    [Fact]
    public void WrapValueClass_LambdaPayload_ValueClassInit_ThrowsAtTheBoundary()
    {
        using var courier = new ChartCourier("Ward 9");
        using var nameless = new Patient("");
        var band = new WardBand(nameless);
        using KotlinFunc<ChartId, WardBand, string> onChartForBand = courier.OnChartForBand;

        Assert.ThrowsAny<ArgumentException>(() =>
            onChartForBand.Invoke(new ChartId("CH-OREO-1"), band));
    }

    [Fact]
    public void WrapValueClass_LambdaPayload_TopLevelFunctionReturn_Invokes()
    {
        using KotlinFunc<ChartId, string> picker = ChartCourierSample.ChartPicker();

        Assert.Equal("picked CH-MYLO-2", picker.Invoke(new ChartId("CH-MYLO-2")));
    }

    // --- Lambda route: value class as result -----------------------------------------------

    [Fact]
    public void WrapValueClass_LambdaResult_StringUnderlying_Reads()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<ChartId> nextChart = courier.NextChart;

        Assert.Equal(new ChartId("Ward 9-next"), nextChart.Invoke());
    }

    [Fact]
    public void WrapValueClass_LambdaResult_PrimitiveUnderlying_Reads()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<Dosage> nextDose = courier.NextDose;

        Assert.Equal(new Dosage(9.0), nextDose.Invoke());
    }

    [Fact]
    public void WrapValueClass_LambdaResult_EnumUnderlying_Reads()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<Temperament> nextMood = courier.NextMood;

        Assert.Equal(new Temperament(TestLibrary.Clinic.Mood.Playful), nextMood.Invoke());
    }

    [Fact]
    public void WrapValueClass_LambdaResult_ObjectHandleUnderlying_Reads()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<ChartRef> nextChartRef = courier.NextChartRef;

        using Patient patient = nextChartRef.Invoke().Patient;
        Assert.Equal("Ward 9 patient", patient.Name);
    }

    [Fact]
    public void WrapValueClass_LambdaPayloadAndResult_OneCall()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinFunc<ChartId, ChartId> refile = courier.Refile;

        Assert.Equal(new ChartId("CH-OREO-1-refiled"), refile.Invoke(new ChartId("CH-OREO-1")));
    }

    [Fact]
    public async Task WrapValueClass_SuspendLambdaPayloadAndResult_OneCall()
    {
        using var courier = new ChartCourier("Ward 9");
        using KotlinSuspendFunc<ChartId, ChartId> refileAsync = courier.RefileAsync;

        Assert.Equal(new ChartId("CH-MYLO-2-refiled-later"),
            await refileAsync.InvokeAsync(new ChartId("CH-MYLO-2")));
    }
}

using TestLibrary.Cat;
using TestLibrary.Reserved;

namespace IntegrationTests;

/// <summary>
/// The rest of the generator-owned identifier family that
/// <a href="https://github.com/xxfast/kotlin-native-nuget/issues/66">#66</a> left out. <c>error</c>
/// was one name in a set: the ordinary forward callable plan also owns <c>handle</c>,
/// <c>receiver</c>, <c>value</c>, <c>errorOut</c> and <c>valueOut</c> on the ABI, and
/// <c>nativeResult</c> / <c>hasValue</c> as C# wrapper-body locals. A user Kotlin parameter spelled
/// like any of them collides today, so the red here is "everything red": generation itself aborts on
/// the ADR-055 forward ABI check, and behind that <c>Interop.cs</c> (and for three of these names the
/// Kotlin <c>@CName</c> export as well) does not compile, so these tests cannot be built at all.
/// <para>
/// Expected once fixed: a plan-time rename with #66's chain rule (<c>handle</c> -&gt;
/// <c>handle_</c>, <c>handle_</c> -&gt; <c>handle__</c>) for <c>handle</c>, <c>receiver</c>,
/// <c>value</c>, <c>errorOut</c> and <c>valueOut</c> on both sides, plus a C#-only rename for the
/// wrapper locals <c>nativeResult</c> and <c>hasValue</c>. <c>value</c> moves on every callable,
/// even where it would not collide. Properties are untouched, because they are PascalCased.
/// </para>
/// <para>
/// Unlike <c>Issue66Tests</c>, every argument below is passed <em>by name</em>. That is deliberate:
/// the point of this feature is the exact spelling a C# caller has to type, so a fix that renamed
/// to something else would still be red here.
/// </para>
/// <para>
/// The cats supervise the hardware bench. Oreo (black, white in the middle) bats every handle off
/// the table; Mylo (brown and creamy) is what the meter reads.
/// </para>
/// </summary>
public class ReservedNamesTests
{
    [Fact]
    public void Constructor_HandleParameter_RoundTripsThroughTheHandleProperty()
    {
        // `IntPtr handle = Native_Create(handle, out IntPtr error)` in the ctor body: CS0136.
        using var widget = new Widget(handle_: 1);

        Assert.Equal(1, widget.Handle);
    }

    [Fact]
    public void Copy_HandleParameter_CarriesTheNewValueAndLeavesTheOriginal()
    {
        // `Copy` is an instance callable, so its extern already leads with the `IntPtr handle`
        // receiver slot; a second `handle` beside it is CS0100.
        using var oreo = new Widget(handle_: 1);
        using var mylo = oreo.Copy(handle_: 2);

        Assert.Equal(2, mylo.Handle);
        Assert.Equal(1, oreo.Handle);
    }

    [Fact]
    public void Describe_HandleValueAndNativeResultParameters_AllReachNative()
    {
        // Three collisions in one signature: `handle` duplicates the receiver slot on both sides,
        // `nativeResult` rebinds the C# body local the string return declares, and `value` collides
        // with nothing here and pins the uniform rule.
        using var gadget = new Gadget();

        Assert.Equal("1/Oreo/2", gadget.Describe(handle_: 1, value_: "Oreo", nativeResult_: 2));
    }

    [Fact]
    public void Describe_ArgumentsArriveInOrder()
    {
        using var gadget = new Gadget();

        // Distinct numbers on either side of the string: a swapped pair reads back "9/Mylo/7".
        Assert.Equal("7/Mylo/9", gadget.Describe(handle_: 7, value_: "Mylo", nativeResult_: 9));
    }

    [Fact]
    public void Tag_ExtensionReceiverParameter_JoinsBothSides()
    {
        // A Kotlin extension on a built-in receiver surfaces as a real C# extension method on a
        // `StringExtensions` partial class named after the *receiver*, not after the source file.
        // That class is per declaring package, so this cell's Kotlin source sits in `reserved`
        // with the rest of its family and the method arrives in `TestLibrary.Reserved`
        // (`ExtensionNamespaceTests` pins that). Its `this` parameter is already named `receiver`,
        // and so is the Kotlin export's first parameter, so the user's `receiver` duplicates at
        // the same position on both sides.
        Assert.Equal("Oreo:Mylo", "Oreo".Tag(receiver_: "Mylo"));
    }

    [Fact]
    public void Probe_AbiContractCollision_ReturnsNullOnTheAbsentPath()
    {
        // The cell that fails earliest: the forward ABI check reads a parameter's direction off its
        // name, so `errorOut` and `valueOut` here are projected `out` on the Kotlin side and `in` on
        // the C# side and KSP aborts before anything renders. `errorOut == 0` is the absent path.
        Assert.Null(ReservedNamesSample.Probe(errorOut_: 0, valueOut_: 1));
    }

    [Fact]
    public void Probe_AbiContractCollision_SumsBothArgumentsOnThePresentPath()
    {
        // 1 + 2: an argument that never arrived, or one read back out of the exception slot the
        // Kotlin export declares under the same name, would not come back as 3.
        Assert.Equal(3, ReservedNamesSample.Probe(errorOut_: 1, valueOut_: 2));
    }

    [Fact]
    public void Read_ValueOutSlotAndHasValueLocal_ReturnNullOnTheAbsentPath()
    {
        // A class member keeps the single-export nullable shape, so this is where the `out int
        // valueOut` slot and the `bool hasValue` local actually exist. A top-level function fans out
        // into two exports with `__nuget_`-prefixed locals and produces neither name.
        using var dial = new Dial();

        Assert.Null(dial.Read(valueOut_: 5, hasValue_: 0));
    }

    [Fact]
    public void Read_ValueOutSlotAndHasValueLocal_SumBothArgumentsOnThePresentPath()
    {
        // 40 + 2: an argument read back out of the generator's own slot instead of the user's would
        // not come back as 42.
        using var dial = new Dial();

        Assert.Equal(42, dial.Read(valueOut_: 40, hasValue_: 2));
    }

    [Fact]
    public void Scale_ValueClassReceiverSlot_MultipliesThroughTheStruct()
    {
        // The one place `value` really collides: a value class passes its receiver in a slot named
        // `value`, so `Native_Scale(int value, int value)` is CS0100. 3 * 4, so a swapped or dropped
        // argument reads as the wrong number.
        Assert.Equal(12, new Ratio(3).Scale(value_: 4));
    }

    [Fact]
    public void Meter_PropertyKeepsItsPascalCaseName_AndTheConstructorParameterMoves()
    {
        // The control. `value` as a property renders `Value` and must not move; the same word as a
        // constructor parameter does move under the uniform rule.
        using var meter = new Meter(value_: "Mylo");

        Assert.Equal("Mylo", meter.Value);
    }
}

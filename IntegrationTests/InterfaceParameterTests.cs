using System;
using TestLibrary.Nested;
using Wardrobe = TestLibrary.Wardrobe;

namespace IntegrationTests;

/// <summary>
/// ADR-135: an interface reached only at a <em>parameter</em> position gets a working ADR-084
/// bridge, and an interface whose bridge plans to null fails with a managed
/// <see cref="NotSupportedException"/> instead of killing the host.
///
/// Both halves are broken today. The set the bridge plan is built from is the <em>return</em>
/// reachable one, so a parameter-only interface has no bridge factory at all;
/// <c>NugetBridge.HandleFor</c> throws, and the <c>finally</c> then runs
/// <c>NugetMarshal.Dispose(IntPtr.Zero)</c> with no zero guard, which reaches a non-nullable
/// <c>COpaquePointer</c> export and takes the process down with an unlocated Kotlin
/// <c>NullPointerException</c>. So the expected red here is "Test host process crashed", not a
/// failed assertion.
///
/// Four cells, one per position the reachability walk has to learn:
/// <list type="bullet">
/// <item><see cref="Boarding.IClerk"/>, nested, parameter only,</item>
/// <item><see cref="IDoorman"/>, top-level, parameter only: nothing in the walk looks at nesting,
/// so an implementation that fixes only the nested path fails here,</item>
/// <item><see cref="ISitter"/>, reached only as an ADR-132 extension receiver, which settles
/// ADR-135's open question about whether a receiver's type is in <c>publicSignature.parameters</c>
/// or only on the native call's RECEIVER slot,</item>
/// <item><see cref="IScratchLog"/>, a member with its own type parameter, which no ADR-084 slot
/// can carry: it plans to null even once the walk is widened, and its contract is the managed
/// throw.</item>
/// </list>
///
/// Cell (e), <see cref="Wardrobe.ICollar"/>, is the ADR-084 amendment that ships <c>var</c>
/// setters: a <c>String</c> and an <c>Int</c> <c>var</c>, each written by Kotlin through its setter
/// slot.
///
/// Oreo boards under protest. Mylo holds the door.
/// </summary>
public class InterfaceParameterTests
{
    // --- (a) nested interface, parameter position only ---

    private sealed class DeskClerk : Boarding.IClerk
    {
        public int Stamps { get; private set; }

        public string Stamp()
        {
            Stamps++;
            return "stamped";
        }

        public void Dispose() { }
    }

    [Fact]
    public void ParameterOnlyNestedInterface_CSharpImplementation_IsCalledBackFromKotlin()
    {
        var clerk = new DeskClerk();

        Assert.Equal("stamped filed at boarding", Boarding.FileVia(clerk));
        Assert.Equal(1, clerk.Stamps);
    }

    // --- (b) top-level interface, parameter position only ---

    private sealed class NightPorter : IDoorman
    {
        public int Buzzes { get; private set; }

        public string Buzz()
        {
            Buzzes++;
            return "buzzed";
        }

        public void Dispose() { }
    }

    [Fact]
    public void ParameterOnlyTopLevelInterface_CSharpImplementation_IsCalledBackFromKotlin()
    {
        // The discriminator against a nesting-shaped fix: the walk is return-only regardless of
        // nesting, so this one is unbridged for exactly the same reason the nested one is.
        var porter = new NightPorter();

        Assert.Equal("buzzed, come in", CatteryDesk.BuzzIn(porter));
        Assert.Equal(1, porter.Buzzes);
    }

    // --- (c) interface reached only as an ADR-132 extension receiver ---

    private sealed class HouseSitter : ISitter
    {
        public int Visits { get; private set; }

        public string House()
        {
            Visits++;
            return "number 9";
        }

        public void Dispose() { }
    }

    [Fact]
    public void ReceiverOnlyInterface_CSharpImplementation_IsCalledBackFromKotlin()
    {
        // ADR-135's open question 1. If a receiver's type never lands in
        // `publicSignature.parameters`, widening the parameter walk alone leaves this red while
        // the two cells above go green.
        using ISitter sitter = new HouseSitter();

        Assert.Equal("checked in at number 9", sitter.CheckIn());
        Assert.Equal(1, ((HouseSitter)sitter).Visits);
    }

    [Fact]
    public void ReceiverOnlyInterface_ExtensionProperty_IsCalledBackFromKotlin()
    {
        // The same receiver one slot to the right: `val Sitter.address` reaches `ISitter` through
        // the property plan's RECEIVER arm of the reachability walk, the arm no other fixture
        // touches. The read has to dispatch back into this instance, so the visit counter moving
        // is what separates a real crossing from a Kotlin-side constant.
        using ISitter sitter = new HouseSitter();

        Assert.Equal("at number 9", sitter.Address);
        Assert.Equal(1, ((HouseSitter)sitter).Visits);
    }

    // --- (d) ADR-237: a member with its own `R` is erased to `object`, so the interface bridges ---

    private sealed class ClawMarks : IScratchLog
    {
        public int Scratches { get; set; } = 7;

        public object? Tally(object? entry) => entry;

        public void Dispose() { }
    }

    [Fact]
    public void ErasedTypeParameterInterface_CSharpImplementation_CrossesToKotlin()
    {
        // `fun <R> tally` used to keep this interface from planning a bridge (a managed throw). Its
        // `R` is erased to `object?` now, so the C# implementation crosses and Kotlin reads it.
        var log = new ClawMarks();

        Assert.Equal(7, CatteryDesk.CountScratches(log));
    }

    // --- (d2) interface whose bridge plans to null: managed throw, not a dead host ---

    private sealed class SnappedGauge : IClawGauge
    {
        public void Dispose() { }
    }

    [Fact]
    public void UnbridgeableInterface_CSharpImplementation_ThrowsNotSupported()
    {
        // A `Unit` property has no slot, so this interface plans to null whatever the reachability
        // walk does. The contract is the managed throw that `NugetBridge.HandleFor` raises,
        // surviving the `finally` instead of being overwritten by `NugetMarshal.Dispose(IntPtr.Zero)`.
        var gauge = new SnappedGauge();

        NotSupportedException ex =
            Assert.Throws<NotSupportedException>(() => CatteryDesk.GaugeClaws(gauge));

        Assert.Contains("SnappedGauge", ex.Message);
    }

    // --- (e) ADR-084 amendment: a `var` writes through its `<name>Set` slot ---

    private sealed class BreakawayCollar : Wardrobe.ICollar
    {
        public string Tag { get; set; } = "Mylo";
        public int Bells { get; set; } = 1;

        public void Dispose() { }
    }

    [Fact]
    public void Retag_NonGenericVar_WritesThroughTheSetterSlot()
    {
        // The `String` var: Kotlin's write mints a handle the C# setter body reads, and the
        // read-back goes through the getter slot, so the C# object is the only place the new tag
        // can come from.
        var collar = new BreakawayCollar();

        Assert.Equal("OREO", Wardrobe.Retagging.Retag(collar, "OREO"));
        Assert.Equal("OREO", collar.Tag);
    }

    [Fact]
    public void AddBell_NonGenericIntVar_ReadsAndWritesThroughTheSlotPair()
    {
        // The scalar var: no conversion at the seam, so a setter slot that only works for the
        // handle-carrying case still fails here. Two calls, so a write that never lands reads 2
        // the second time instead of 3.
        var collar = new BreakawayCollar();

        Assert.Equal(2, Wardrobe.Retagging.AddBell(collar));
        Assert.Equal(3, Wardrobe.Retagging.AddBell(collar));
        Assert.Equal(3, collar.Bells);
    }
}

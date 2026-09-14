using System;
using TestLibrary.Nested;

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
/// <item><see cref="IScratchLog"/>, a <c>var</c> member and therefore out of ADR-084's v1 slot
/// vocabulary: it plans to null even once the walk is widened, and its contract is the managed
/// throw.</item>
/// </list>
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

    // --- (d) interface whose bridge plans to null: managed throw, not a dead host ---

    private sealed class ClawMarks : IScratchLog
    {
        public int Scratches { get; set; } = 7;

        public void Dispose() { }
    }

    [Fact]
    public void UnbridgeableInterface_CSharpImplementation_ThrowsNotSupported()
    {
        // A `var` member is out of ADR-084's v1 slot vocabulary, so this interface plans to null
        // whatever the reachability walk does. The contract is the managed throw that
        // `NugetBridge.HandleFor` already raises, surviving the `finally` instead of being
        // overwritten by `NugetMarshal.Dispose(IntPtr.Zero)`.
        var log = new ClawMarks();

        NotSupportedException ex =
            Assert.Throws<NotSupportedException>(() => CatteryDesk.CountScratches(log));

        Assert.Contains("ClawMarks", ex.Message);
    }
}

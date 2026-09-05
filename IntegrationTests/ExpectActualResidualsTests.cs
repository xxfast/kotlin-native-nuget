using System.Runtime.InteropServices;
using TestLibrary.Platform;

namespace IntegrationTests;

/// <summary>
/// ADR-074 amendment: the expect/actual shapes the ADR's "What is deferred" list never exercised —
/// an <c>expect sealed class</c> (item 1) and <c>expect interface</c> / <c>expect enum class</c> /
/// <c>expect value class</c> (item 6). Fixture: <c>PlatformResiduals.kt</c> plus the two per-target
/// actual files, which declare an identical public surface and differ only in returned values.
///
/// Oreo and Mylo wear radio collars. Only one target's actual bodies ever ship in the running
/// package (macosArm64 on macos-latest, mingwX64 elsewhere), so — exactly like
/// <see cref="PlatformTests"/> — every value expectation is selected by RID. A collar that reports
/// the other platform's numbers means the wrong body ran.
///
/// The static class is <c>PlatformResiduals</c> (Decision 3: the EXPECT's file name), and top-level
/// functions keep their Kotlin camelCase, as everywhere else in this fixture library.
/// </summary>
public class ExpectActualResidualsTests
{
    private static readonly bool IsMacOs = RuntimeInformation.IsOSPlatform(OSPlatform.OSX);

    private static readonly int ExpectedBoost = IsMacOs ? 42 : 24;
    private static readonly string ExpectedPong = IsMacOs ? "pong from macos" : "pong from mingw";
    private static readonly Band ExpectedBand = IsMacOs ? Band.Low : Band.High;
    private static readonly int ExpectedHertz = IsMacOs ? 2400 : 5800;

    // --- Item 1: `expect sealed class Signal`, subclasses declared on the actual side ---

    [Fact]
    public void Signal_IsAbstract()
    {
        // The hierarchy must render as a hierarchy, not as a flat handle class: the sealed route
        // ran over the ACTUAL declaration (the expect never reaches any route).
        Assert.True(typeof(Signal).IsAbstract);
    }

    [Fact]
    public void Signal_Strong_IsSealed()
    {
        Assert.True(typeof(Signal.Strong).IsSealed);
    }

    [Fact]
    public void Signal_WhenCollarReports_DiscriminatesAsStrongWithRunningActualsBoost()
    {
        // Oreo's collar is in range. `getSealedSubclasses()` on the actual must have found
        // `Strong`, or `signal_get_type`'s exhaustive `when` would not have compiled at all.
        using Signal reading = PlatformResiduals.collarSignal(10);
        var strong = Assert.IsType<Signal.Strong>(reading);
        Assert.Equal(10 + ExpectedBoost, strong.Dbm);
    }

    [Fact]
    public void Signal_WhenCollarDropsOut_DiscriminatesAsLost()
    {
        // Mylo went under the deck. The other branch of the same `when`: discrimination has to
        // work both ways or the type tag is meaningless.
        using Signal reading = PlatformResiduals.collarSignal(-1);
        Assert.IsType<Signal.Lost>(reading);
    }

    // --- Item 6a: `expect interface Transponder` at an ADR-040 return position ---

    [Fact]
    public void Transponder_Ping_ReturnsRunningActualsAnswer()
    {
        // The runtime type behind this handle is the target's `internal` implementing class, which
        // is deliberately NOT exported: ADR-040 dispatches through the interface's own handle-based
        // exports, so a non-exported implementer is expected to work here.
        using ITransponder collar = PlatformResiduals.transponder();
        Assert.Equal(ExpectedPong, collar.Ping());
    }

    // --- Item 6b: `expect enum class Band` ---

    [Fact]
    public void Band_ReturnsRunningActualsEntry()
    {
        Assert.Equal(ExpectedBand, PlatformResiduals.band());
    }

    [Fact]
    public void Band_EntriesAreOrdinalBacked()
    {
        // Entries come from the ACTUAL's declarations, and the wire is the ordinal, so the two
        // halves agreeing on the entry ORDER is what keeps the mapping honest.
        Assert.Equal(0, (int)Band.Low);
        Assert.Equal(1, (int)Band.High);
    }

    // --- Item 6c: `expect value class Frequency(val hertz: Int)` ---

    [Fact]
    public void Frequency_Hertz_ReturnsRunningActualsValue()
    {
        // The value class at a return position. Note this already crosses the ACTUAL's primary
        // constructor as well: the generated `frequency()` shim builds its result through the
        // public `Frequency(int)` ctor, which calls the `frequency_create` export.
        Assert.Equal(ExpectedHertz, PlatformResiduals.frequency().Hertz);
    }
}

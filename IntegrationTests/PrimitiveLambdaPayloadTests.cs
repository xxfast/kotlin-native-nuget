using TestLibrary.Metronome;

namespace IntegrationTests;

/// <summary>
/// ADR-036: a primitive payload on a per-call lambda parameter crosses by value.
/// Each callback collects what it was handed, and the assert is the exact sequence, so a thunk
/// that delivers a truncated pointer or a constant fails rather than passing on arity alone.
/// </summary>
public class PrimitiveLambdaPayloadTests
{
    [Fact]
    public void Metronome_OnTick_DeliversIntPayloadByValue()
    {
        using var metronome = new Metronome(4);
        var ticks = new List<int>();
        metronome.OnTick(tick => ticks.Add(tick));
        Assert.Equal(new List<int> { 1, 2, 3, 4 }, ticks);
    }

    [Fact]
    public void Metronome_OnBeat_DeliversBooleanPayloadByValue()
    {
        using var metronome = new Metronome(4);
        var beats = new List<bool>();
        metronome.OnBeat(beat => beats.Add(beat));
        Assert.Equal(new List<bool> { true, false, true, false }, beats);
    }

    /// <summary>
    /// ADR-036 amendment: Kotlin `Byte` is `sbyte` in C#, a different wire from `Boolean`'s `byte`,
    /// so the pair may not share one internal delegate. The velocities are deliberately negative
    /// for the first three beats: read through an unsigned wire, -100 would arrive as 156, so this
    /// asserts a signed read and not merely arrival.
    ///
    /// Oreo's first pounce of the night registers well below the line.
    /// </summary>
    [Fact]
    public void Metronome_OnVelocity_DeliversSignedBytePayloadByValue()
    {
        using var metronome = new Metronome(4);
        var velocities = new List<sbyte>();
        metronome.OnVelocity(velocity => velocities.Add(velocity));
        Assert.Equal(new List<sbyte> { -100, -60, -20, 20 }, velocities);
    }

    /// <summary>
    /// The contract in one place: both lambda shapes on a single instance, each receiving its own
    /// correctly typed values. `Boolean` is registered first; before the fix the `Byte` payload then
    /// reused its delegate name and the generated bindings failed to compile (CS1678).
    ///
    /// Mylo keeps time; Oreo keeps hitting the meter.
    /// </summary>
    [Fact]
    public void Metronome_OnBeatAndOnVelocity_CoexistOnOneInstance()
    {
        using var metronome = new Metronome(2);
        var beats = new List<bool>();
        var velocities = new List<sbyte>();

        metronome.OnBeat(beat => beats.Add(beat));
        metronome.OnVelocity(velocity => velocities.Add(velocity));

        Assert.Equal(new List<bool> { true, false }, beats);
        Assert.Equal(new List<sbyte> { -100, -60 }, velocities);
    }

    [Fact]
    public void Metronome_OnTempo_DeliversDoublePayloadByValue()
    {
        using var metronome = new Metronome(3);
        var tempos = new List<double>();
        metronome.OnTempo(tempo => tempos.Add(tempo));
        Assert.Equal(new List<double> { 60.0, 60.5, 61.0 }, tempos);
    }

    [Fact]
    public void Metronome_OnTick_CapturingLambdaAccumulates()
    {
        using var metronome = new Metronome(5);
        int total = 0;
        metronome.OnTick(tick => total += tick);
        Assert.Equal(15, total);
    }
}

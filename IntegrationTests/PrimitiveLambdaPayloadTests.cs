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

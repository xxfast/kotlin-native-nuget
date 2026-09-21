using TestLibrary.Metronome;

namespace IntegrationTests;

/// <summary>
/// ADR-160: a per-call lambda-parameter member on the ADR-062 forward callable plan. Two returns are
/// in play and every test below names which one it is about:
/// <list type="bullet">
///   <item>the <b>METHOD (outer) return</b>, the member's own result:
///     <c>int total = metronome.CountTicks(t =&gt; seen.Add(t))</c>. These cells take an
///     <c>Action&lt;int&gt;</c>, so nothing but the outer return can fail them,</item>
///   <item>the <b>LAMBDA (inner) return</b>, what the C# lambda hands back:
///     <c>metronome.SumWeights(t =&gt; t * 2)</c>, a <c>Func&lt;int,int&gt;</c> whose result crosses
///     by value (ADR-036's table said so and this route never implemented it).</item>
/// </list>
/// Today every cell here is unreachable: <c>packNuget</c> throws on the first scalar-outer member in
/// processing order (<c>Forward ABI mismatch for metronome_countAbove; expected ... -&gt; pointer,
/// actual ... -&gt; int</c> on 2026-09-22), so the red is a build failure of the whole package rather
/// than a CS1061 per member. Once the route generates, the red becomes these assertions.
/// <para>
/// The cells past the two scalar axes are the ones the legacy route could not have carried at all,
/// which is why the end state is the plan: an exported-object outer return and its nullable twin, an
/// enum outer return, a mixed parameter list, the top-level and extension positions, and a sealed
/// arm receiver. Every callback here fires synchronously, before the P/Invoke returns, and each
/// asserts the exact sequence it was handed: a thunk that delivered a constant, or a truncated
/// pointer read as an int, passes on arity alone otherwise.
/// </para>
/// Oreo counts the ticks. Mylo weighs them.
/// </summary>
public class CallbackParameterReturnTests
{
    // -----------------------------------------------------------------------------------------
    // METHOD (outer) return axis. Inner lambda is Action<int> throughout.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// The headline cell: the member's own <c>int</c> comes back while the lambda still ran four
    /// times in order. Before ADR-160 the C# half declared the extern as <c>IntPtr</c> and the
    /// public return as the Kotlin simple name (<c>public Int CountTicks</c>), which is what the
    /// forward ABI contract rejected.
    /// </summary>
    [Fact]
    public void Metronome_CountTicks_ReturnsTheMembersOwnInt()
    {
        using var metronome = new Metronome(4);
        var seen = new List<int>();
        int total = metronome.CountTicks(t => seen.Add(t));
        Assert.Equal(4, total);
        Assert.Equal(new List<int> { 1, 2, 3, 4 }, seen);
    }

    /// <summary>
    /// Outer <c>bool</c>: the one scalar that is not its own wire, so the extern needs
    /// <c>[return: MarshalAs(UnmanagedType.I1)]</c>. A widening bug here reads any non-zero byte as
    /// true and passes by luck, so the false branch is asserted too.
    /// </summary>
    [Fact]
    public void Metronome_RanAnyTick_ReturnsTheMembersOwnBool()
    {
        using var four = new Metronome(4);
        var seen = new List<int>();
        Assert.True(four.RanAnyTick(t => seen.Add(t)));
        Assert.Equal(4, seen.Count);

        using var silent = new Metronome(0);
        Assert.False(silent.RanAnyTick(_ => Assert.Fail("a silent metronome must not tick")));
    }

    /// <summary>Outer <c>double</c>: a floating result crosses in its own register class.</summary>
    [Fact]
    public void Metronome_RunSeconds_ReturnsTheMembersOwnDouble()
    {
        using var metronome = new Metronome(5);
        int ticks = 0;
        Assert.Equal(2.5, metronome.RunSeconds(_ => ticks++));
        Assert.Equal(5, ticks);
    }

    /// <summary>
    /// Outer <c>string</c>, green before ADR-160 (both halves already agreed on a pointer): the
    /// regression anchor of the outer axis.
    /// </summary>
    [Fact]
    public void Metronome_TickLabel_StillReturnsTheMembersOwnString()
    {
        using var metronome = new Metronome(3);
        int ticks = 0;
        Assert.Equal("3 beats for Oreo", metronome.TickLabel(_ => ticks++));
        Assert.Equal(3, ticks);
    }

    // -----------------------------------------------------------------------------------------
    // LAMBDA (inner) return axis. Func<int, T> by value.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// <c>Func&lt;int,int&gt;</c>: Kotlin sums what the C# lambda returns. Before ADR-160 the
    /// delegate returned <c>IntPtr</c> and the body was <c>NugetMarshal.WrapString(weigh(arg0))</c>
    /// on the C# side and <c>resultRef.asStableRef&lt;String&gt;().get()</c> on the Kotlin side.
    /// </summary>
    [Fact]
    public void Metronome_SumWeights_LambdaReturnsIntByValue()
    {
        using var metronome = new Metronome(4);
        Assert.Equal(20, metronome.SumWeights(t => t * 2));
    }

    /// <summary>
    /// <c>Func&lt;int,long&gt;</c>: the 64-bit wire. The values are past <c>int.MaxValue</c> on
    /// purpose, so a lambda return narrowed to 32 bits cannot produce this sum.
    /// </summary>
    [Fact]
    public void Metronome_TotalMicros_LambdaReturnsLongByValue()
    {
        using var metronome = new Metronome(3);
        Assert.Equal(3L * 3_000_000_000L, metronome.TotalMicros(_ => 3_000_000_000L));
    }

    /// <summary>
    /// <c>Func&lt;int,double&gt;</c>: a fractional result an integer-shaped return would flatten.
    /// </summary>
    [Fact]
    public void Metronome_TotalTempo_LambdaReturnsDoubleByValue()
    {
        using var metronome = new Metronome(4);
        Assert.Equal(5.0, metronome.TotalTempo(t => t * 0.5));
    }

    /// <summary>
    /// <c>Func&lt;int,string&gt;</c>, green before ADR-160: the regression anchor of the inner axis,
    /// and the cell that keeps the by-value fix from swallowing the pointer-returning lambda.
    /// </summary>
    [Fact]
    public void Metronome_JoinTicks_LambdaStillReturnsStringByPointer()
    {
        using var metronome = new Metronome(3);
        Assert.Equal("beat1-beat2-beat3", metronome.JoinTicks(t => $"beat{t}"));
    }

    /// <summary>
    /// Both axes on one instance, non-trivially: the inner lambda's answers decide the outer result.
    /// A fix that classified one return by reading the other cannot pass this beside the cells above.
    /// </summary>
    [Fact]
    public void Metronome_SumWeights_CapturingLambdaDecidesTheOuterResult()
    {
        using var metronome = new Metronome(4);
        var weighed = new List<int>();
        int total = metronome.SumWeights(t =>
        {
            weighed.Add(t);
            return t == 3 ? 100 : 1;
        });
        Assert.Equal(103, total);
        Assert.Equal(new List<int> { 1, 2, 3, 4 }, weighed);
    }

    // -----------------------------------------------------------------------------------------
    // Outer returns the legacy route refused: exported object, nullable object, enum.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// Outer <b>exported object</b>: the member returns a <c>Chime</c> handle chosen by the C#
    /// predicate. The lambda is a <c>Func&lt;Chime,bool&gt;</c>, so each candidate crosses out as a
    /// handle too and the returned one must be retained (the legacy route returned a bare Kotlin
    /// object from a <c>@CName</c> function with no <c>NugetHandles.retain</c>, which did not even
    /// compile). Mylo is the heavy one.
    /// <para>
    /// The predicate disposes each candidate it is handed (<c>using (c)</c>) because ADR-036 puts
    /// the payload wrapper's free on this side: Kotlin retains the handle for the crossing and never
    /// releases it, and a generated wrapper has a <c>Dispose()</c> and no finalizer. A consumer
    /// lambda that omits the <c>using</c> is correct but leaks one handle per invocation, which is
    /// the residual ADR-036 names and what <c>LeakTests</c> rows 13/13a measure.
    /// </para>
    /// </summary>
    [Fact]
    public void Metronome_FirstChime_ReturnsAnExportedObjectHandle()
    {
        using var metronome = new Metronome(4);
        using Chime chime = metronome.FirstChime(c => { using (c) { return c.Weight >= 2; } });
        Assert.Equal("Mylo", chime.Name);
        Assert.Equal(2, chime.Weight);
    }

    /// <summary>
    /// Outer <b>nullable exported object</b>, the <c>firstOrNull</c> shape. Both branches, because a
    /// nullable return whose null is never produced leaves the null path cold: no chime weighs 99.
    /// </summary>
    [Fact]
    public void Metronome_FirstChimeOrNull_ReturnsNullWhenNothingMatches()
    {
        using var metronome = new Metronome(4);
        Assert.Null(metronome.FirstChimeOrNull(c => { using (c) { return c.Weight == 99; } }));
    }

    /// <summary>The non-null branch of the same member, disposed by the consumer.</summary>
    [Fact]
    public void Metronome_FirstChimeOrNull_ReturnsAHandleWhenSomethingMatches()
    {
        using var metronome = new Metronome(4);
        using Chime? chime = metronome.FirstChimeOrNull(c => { using (c) { return c.Name == "Oreo"; } });
        Assert.NotNull(chime);
        Assert.Equal(1, chime.Weight);
    }

    /// <summary>
    /// Outer <b>enum</b>: an ordinal, not a handle. The legacy route emitted
    /// <c>return new Mood(nativeHandle);</c> (CS1729). Two arms, so a constant zero cannot pass.
    /// </summary>
    [Fact]
    public void Metronome_MoodAfter_ReturnsAnEnumByOrdinal()
    {
        using var brisk = new Metronome(4);
        int ticks = 0;
        Assert.Equal(Mood.Brisk, brisk.MoodAfter(_ => ticks++));
        Assert.Equal(4, ticks);

        using var frantic = new Metronome(7);
        Assert.Equal(Mood.Frantic, frantic.MoodAfter(_ => { }));

        using var calm = new Metronome(1);
        Assert.Equal(Mood.Calm, calm.MoodAfter(_ => { }));
    }

    // -----------------------------------------------------------------------------------------
    // Mixed parameter list, positions, sealed arm.
    // -----------------------------------------------------------------------------------------

    /// <summary>
    /// A non-lambda parameter next to the lambda. The legacy route dropped it from the extern, the
    /// public method and the Kotlin call site alike (<c>No value passed for parameter 'min'</c>), so
    /// the value passed here has to reach Kotlin and change both the callbacks and the result.
    /// </summary>
    [Fact]
    public void Metronome_CountAbove_CarriesTheNonLambdaParameterToo()
    {
        using var metronome = new Metronome(5);
        var seen = new List<int>();
        int fired = metronome.CountAbove(3, t => seen.Add(t));
        Assert.Equal(2, fired);
        Assert.Equal(new List<int> { 4, 5 }, seen);
    }

    /// <summary>
    /// The <b>top-level</b> position (ADR-007 static class <c>Tallies</c>), which today's diagnostic
    /// refuses outright: "a lambda binds at a class-method parameter and a top-level function
    /// return, but not at this position".
    /// </summary>
    [Fact]
    public void Tallies_TallyTicks_TopLevelPositionReturnsItsOwnInt()
    {
        var seen = new List<int>();
        Assert.Equal(3, Tallies.TallyTicks(t => seen.Add(t)));
        Assert.Equal(new List<int> { 1, 2, 3 }, seen);
    }

    /// <summary>
    /// The <b>extension</b> position on the same receiver, called as an extension method: only the
    /// even beats reach the lambda and the count comes back as the outer return.
    /// </summary>
    [Fact]
    public void Metronome_EveryOtherTick_ExtensionPositionReturnsItsOwnInt()
    {
        using var metronome = new Metronome(5);
        var seen = new List<int>();
        Assert.Equal(2, metronome.EveryOtherTick(t => seen.Add(t)));
        Assert.Equal(new List<int> { 2, 4 }, seen);
    }

    /// <summary>
    /// The <b>sealed arm</b> receiver: the route has to key its export prefix off the arm, the way
    /// ADR-116, ADR-118 and ADR-124 re-keyed the plain, suspend and flow rows.
    /// </summary>
    [Fact]
    public void Cadence_Steady_CountTicks_OnASealedArmReturnsItsOwnInt()
    {
        using var steady = new Cadence.Steady(span: 3);
        var seen = new List<int>();
        Assert.Equal(3, steady.CountTicks(t => seen.Add(t)));
        Assert.Equal(new List<int> { 1, 2, 3 }, seen);
    }
}

// `KotlinAction<T>` and `KotlinFunc<T, TResult>` are declared in the root `TestLibrary` namespace,
// beside the other generated helpers, not in the fixture's own namespace -- the same `using` pair
// `LambdaTests.cs` opens with.
using TestLibrary;
using TestLibrary.Cat;
using TestLibrary.Metronome;

namespace IntegrationTests;

/// <summary>
/// Boundary nullability, part A: a lambda that crosses the erased callback boundary carrying a
/// <c>null</c>.
///
/// A1 is the returned-lambda route (<c>nuget_funcN_invoke</c>): Kotlin hands C# a lambda, C# calls
/// <c>Invoke</c>. Two things are wrong today and both are asserted here, because fixing either one
/// alone leaves the feature unusable:
///
/// <list type="number">
/// <item>The SPELLING. <c>csTypeArgument</c> never reads <c>isMarkedNullable</c>, so
/// <c>(String?) -&gt; Unit</c> and <c>(String) -&gt; Unit</c> are rendered identically as
/// <c>KotlinAction&lt;string&gt;</c>, and <c>(Int?) -&gt; Unit</c> as <c>KotlinFunc&lt;int, ...&gt;</c>
/// where <c>null</c> is not expressible at all. The value-payload cell below is therefore red at C#
/// COMPILE time (CS0029: <c>KotlinFunc&lt;int, string&gt;</c> is not
/// <c>KotlinFunc&lt;int?, string&gt;</c>), which is the honest first failure: a consumer cannot even
/// write the call.</item>
/// <item>The CROSSING. <c>WrapArg&lt;T&gt;</c> passes a null string straight into
/// <c>export_nuget_wrap_string(value: String)</c>, whose parameter is non-null, and the invoke
/// exports take a non-null <c>COpaquePointer</c> and do <c>fn.invoke(...) as Any</c> on the way
/// back. Each of those is an uncaught Kotlin <c>NullPointerException</c> inside an export with no
/// error slot.</item>
/// </list>
///
/// <para><b>Sequencing warning for the implementation.</b> The reference-payload and null-result
/// cells here do not throw a catchable exception today: they terminate the test host with exit code
/// 3 (verified at a scratch mirror of the shipped exports). So this file cannot be used as a
/// "red then green" pin by running it: it must go green in the same change that makes the exports
/// null tolerant. Do not add a "today it throws" variant of these.</para>
///
/// <para>The route binds only at a top-level function return, so the fixture is the top-level
/// <c>Recorder.kt</c> and the C# holder is the ADR-007 file class <c>Recorder</c>.</para>
///
/// <para>Oreo comes and goes without signing the register; Mylo signs in every time.</para>
/// </summary>
public class NullableLambdaArgumentTests
{
    /// <summary>
    /// The REFERENCE payload: a null string argument reaches the Kotlin lambda as null. The
    /// assertion is on what Kotlin OBSERVED, not on <c>Invoke</c> returning, because a wrapper that
    /// quietly swallowed the call would satisfy the latter. <c>SignedInAtAll</c> separates "the lambda
    /// saw null" from "the lambda never ran and the sentinel is still unset".
    /// </summary>
    [Fact]
    public void NullReferenceArgument_ReachesTheKotlinLambdaAsNull()
    {
        using KotlinAction<string?> record = Recorder.SignIn();

        record.Invoke(null);

        Assert.True(Recorder.SignedInAtAll(), "the Kotlin lambda must have been invoked at all");
        Assert.Null(Recorder.LastSeen());
    }

    /// <summary>
    /// The same lambda with a real payload, so the null cell cannot pass against a wrapper that
    /// hands every argument over as null. Oreo is the one who checks in.
    /// </summary>
    [Fact]
    public void NonNullReferenceArgument_StillCrossesAfterTheNullFix()
    {
        using KotlinAction<string?> record = Recorder.SignIn();

        record.Invoke("Oreo");

        Assert.Equal("Oreo", Recorder.LastSeen());
    }

    /// <summary>
    /// The VALUE payload (<c>Int?</c> to <c>int?</c>). Unlike the reference cell there is no
    /// conversion to get wrong: the failure is that <c>int?</c> cannot be spelled at all today, so
    /// the declared type below does not compile. Both a null and a non-null call are asserted
    /// because the boxing branch for <c>Nullable&lt;int&gt;</c> is a different line from the null
    /// short circuit.
    /// </summary>
    [Fact]
    public void NullableValueArgument_CrossesBothWays()
    {
        using KotlinFunc<int?, string> describe = Recorder.Describer();

        Assert.Equal("no naps recorded", describe.Invoke(null));
        Assert.Equal("napped 9 times", describe.Invoke(9));
    }

    /// <summary>
    /// The RESULT leg: a lambda that returns null. Mylo is not in the register, so the lookup comes
    /// back empty rather than as a handle to nothing.
    /// </summary>
    [Fact]
    public void NullLambdaResult_ComesBackAsNull()
    {
        using KotlinFunc<string, string?> find = Recorder.Finder();

        Assert.Equal("Oreo", find.Invoke("Oreo"));
        Assert.Null(find.Invoke("Mylo"));
    }

    /// <summary>
    /// A2, the shape that KEEPS binding: the lambda's own type is nullable, its payload is not.
    /// Kotlin can express "no listener"; the erased C# delegate slot cannot carry one, so the
    /// wrapper owes the caller an <c>ArgumentNullException</c> at the managed boundary. Without it
    /// the null reaches a <c>[UnmanagedCallersOnly]</c> thunk that dereferences
    /// <c>GCHandle.Target</c>, which is a fail-fast that no <c>catch</c> anywhere can turn into a
    /// test failure.
    /// </summary>
    [Fact]
    public void NullDelegateForANullableLambdaType_ThrowsArgumentNullException()
    {
        using var metronome = new Metronome(4);

        Assert.Throws<ArgumentNullException>(() => metronome.OnMaybeTick(null!));
    }

    /// <summary>
    /// The other half of the same member: it binds, and it works. A "fix" that resolved the
    /// self-contradiction by dropping the member instead of suppressing the false diagnostic would
    /// fail here, which is the point of asserting it beside the throw.
    /// </summary>
    [Fact]
    public void NullableLambdaType_WithARealDelegate_DeliversEveryTick()
    {
        using var metronome = new Metronome(4);
        var ticks = new List<int>();

        metronome.OnMaybeTick(tick => ticks.Add(tick));

        Assert.Equal(new List<int> { 1, 2, 3, 4 }, ticks);
    }
}

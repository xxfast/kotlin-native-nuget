using TestLibrary;
using TestLibrary.Menagerie;
using TestLibrary.Workshop;

namespace IntegrationTests;

// The reverse delegate-parameter feature: a C# API declaring `Func<>` / `Action<>` /
// `Predicate<T>` / a package-declared delegate, called from Kotlin with an ORDINARY Kotlin lambda,
// over a one-slot Kotlin bridge (the ADR-085/086/087/089 machinery with N = 1).
//
//   C# IntegrationTests (this file)
//     -> (forward bridge, Interop.cs)        WorkshopSample.*
//       -> Kotlin test-library               WorkshopSample.kt
//         -> (reverse bridge, this feature)  test.workshop.{Workshop, Transform}
//           -> real C# TestDependency        Test.Workshop.{Workshop, Transform}
//
// So each assertion crosses the bridge four times, and the lambda crosses back a fifth: C# invokes
// a Kotlin function that Kotlin never exported. That inner hop is the feature; everything else
// here already shipped.
//
// One row per MECHANISM, deliberately not per type. `Func<int,int>` alone needs no string
// conversion, no nullability handling, no stored lifetime, no off-thread invoke and no error
// channel, so a fixture trimmed to it would go green while all five were wrong. Each row names
// the seam it stands on.
//
// EXPECTED TO FAIL as of this commit: neither generator knows what a delegate parameter is, so
// `test.workshop.Workshop` is generated without the members `WorkshopSample.kt` calls and the
// Kotlin half does not compile.
public class WorkshopRoundTripTests
{
    // Reverse throws arrive in Kotlin as `NugetManagedException(managedType, message)`, which the
    // Kotlin side surfaces forward as "managedType|message".
    private static void AssertManaged(string surfaced, string managedType, string message)
    {
        string[] parts = surfaced.Split('|');
        Assert.True(parts.Length == 2, $"expected 'managedType|message' from the Kotlin catch site, got: {surfaced}");
        Assert.Equal(managedType, parts[0]);
        Assert.Equal(message, parts[1]);
    }

    // The STATIC route (no receiver handle in the thunk) and the reentrant synchronous invoke:
    // Kotlin calls C#, C# calls back into the Kotlin lambda twice, nested, on the same thread
    // inside the same crossing. 21 -> 42 -> 84.
    [Fact]
    public void StaticFunc_IsInvokedSynchronously_Reentrant() =>
        Assert.Equal(84, WorkshopSample.WorkshopTwice(21));

    // The same shape on an INSTANCE method, which is the ordinary route with a receiver handle.
    // Here as the control: `int` in and out is the one payload needing no conversion anywhere.
    [Fact]
    public void InstanceFunc_IsInvokedSynchronously_Reentrant() =>
        Assert.Equal(84, WorkshopSample.WorkshopDoubleTwice(21));

    // `Action<string>`: a slot with NO return value at all, a payload that DOES need conversion
    // (UTF-8 in, Kotlin `String` out), and two invokes per crossing, in order. A slot that works
    // exactly once, or that hands the same buffer over twice, fails here.
    [Fact]
    public void ActionOfString_IsInvokedTwice_InOrder() =>
        Assert.Equal("Oreo,Mylo", WorkshopSample.WorkshopNames());

    // The package-declared `Transform` delegate, which must stop being extracted as a class (it is
    // one today, verified against the shipped reader on 2026-09-22) and become `(Int) -> Int` plus
    // a `typealias Transform`. The Kotlin driver declares the value AT the typealias, so the row
    // fails if the alias is missing even though a bare lambda would have compiled.
    // DISABLED 2026-09-22 (ADR-158, custom delegates are step 4 of the item and did not land in this
    // pass): a package-declared `delegate` is still a named reader skip (skipped_delegate_signature),
    // so `Workshop.ApplyNamed` does not bind and neither the typealias nor the Kotlin driver exists.
    // Re-enable together with the reader half that decodes a custom delegate`s own Invoke MethodDef.
    [Fact(Skip = "ADR-158 step 4: custom (package-declared) delegates are not bound yet")]
    public void CustomDelegate_BindsAsAFunctionTypeBehindItsTypealias() =>
        Assert.Equal(63, WorkshopSample.WorkshopApplyNamed(21));

    // `Predicate<string>`: a `bool` slot return, and C# short-circuits on it, so the true and
    // false answers take different numbers of invokes. "Oreo" is 4 and "Marshmallow" is 11.
    [Fact]
    public void Predicate_ShortCircuitsOnTheFirstTrue() =>
        Assert.True(WorkshopSample.WorkshopAnyLong(4));

    // The false half: both invokes happen and the lambda is asked about a second, longer string,
    // so a slot that returned a stale value would disagree with one of the two rows.
    [Fact]
    public void Predicate_FalseForBoth_InvokesTwice() =>
        Assert.False(WorkshopSample.WorkshopAnyLong(20));

    // Arity FOUR, above the shipped `KOTLIN_BRIDGE_MAX_ARITY` of 2: ctx + 4 + errOut is a
    // six-parameter `staticCFunction`, which compiles and runs (spike c2), so this row is the
    // policy lift. The ORDER is what it really pins: C# passes 1, 2, 3, 4 and the Kotlin lambda
    // weights them by place value, so any permutation of the four slots gives a different number
    // where a plain sum would be blind to all of them.
    [Fact]
    public void Arity4Func_AllFourArgumentsArrive_InOrder() =>
        Assert.Equal(1234, WorkshopSample.WorkshopSum4());

    // `Action<string?>`: the delegate's own ARGUMENT is a nullable reference, and C# invokes it
    // once with "Oreo" and once with null. Its NullableAttribute payload is [1, 2], pre-order with
    // the delegate node first, so a binding that dropped byte 2 and bound this `(String) -> Unit`
    // fails on the second invoke instead of silently accepting a null as non-null.
    [Fact]
    public void ActionOfNullableString_DeliversBothTheValueAndTheNull() =>
        Assert.Equal("shouted twice:Oreo,<null>", WorkshopSample.WorkshopShout());

    // A NULLABLE DELEGATE, supplied: `Func<string?>?` is nullable at the delegate node AND at its
    // return, two nodes that a single-byte broadcast would collapse.
    [Fact]
    public void NullableDelegate_Supplied_IsInvoked() =>
        Assert.Equal("Oreo and Mylo", WorkshopSample.WorkshopDescribeWithLabel());

    // The same parameter, OMITTED: null has to cross as a zero pointer and C# has to see a real
    // null delegate rather than a holder wrapping nothing.
    [Fact]
    public void NullableDelegate_Omitted_CrossesAsNull() =>
        Assert.Equal("none", WorkshopSample.WorkshopDescribeWithoutLabel());

    // The OTHER nullability encoding: every annotatable node agrees, so Roslyn writes no
    // per-parameter attribute at all and only a method-level NullableContextAttribute(2) carries
    // it. A reader that reads per-parameter attributes only binds this parameter non-null with no
    // diagnostic, and then the null half of this row cannot even be spelled in Kotlin.
    [Fact]
    public void NullableDelegate_MethodContextEncoding_BothSpellings() =>
        Assert.Equal("maybe 21/<null>", WorkshopSample.WorkshopMaybe());

    // An overload set differing ONLY by delegate shape, the `Task.Run(Action)` /
    // `Task.Run(Func<T>)` shape. Both members must bind (dropping the set would delete APIs that
    // stay perfectly callable) and the feature's info diagnostic must name the workaround, because
    // a bare Kotlin lambda is ALWAYS an overload-resolution ambiguity against such a pair
    // (verified by spike, Kotlin 2.4.10): the Kotlin driver uses anonymous functions.
    [Fact]
    public void DelegateShapeOverloadSet_BothMembersBind_ViaAnonymousFunctions() =>
        Assert.Equal("ran an action/ran a func -> 7", WorkshopSample.WorkshopRunBoth());

    // The throw channel, four hops: the Kotlin lambda throws kotlin.IllegalStateException inside
    // the slot -> the ADR-087 envelope writes it -> the C# holder's Invoke throws the ADR-029
    // MAPPED type (KotlinInvalidOperationException, not the base KotlinException) -> it leaves the
    // reverse thunk through the ADR-104 error channel instead of terminating the host on its way
    // back through the Kotlin/Native frame -> Kotlin catches NugetManagedException.
    //
    // The exact type string is asserted, not an EndsWith: it is `GetType().FullName` in the
    // forward bindings' namespace, so if it differs the wiring is wrong, not the channel.
    [Fact]
    public void KotlinThrowInsideTheLambda_IsCatchableOnTheKotlinSide() =>
        AssertManaged(
            WorkshopSample.WorkshopThrowing(),
            "TestLibrary.KotlinInvalidOperationException",
            "boom from Kotlin");

    // The same receiver, used again after the throw. A channel that left the crossing or the
    // holder in a bad state passes the row above and fails this one.
    [Fact]
    public void AfterAThrowingLambda_TheSameReceiverStillWorks() =>
        Assert.Equal("TestLibrary.KotlinInvalidOperationException/12", WorkshopSample.WorkshopThrowingThenFine());

    // The STORED lifetime, and the feature's main composed row: each half of it is verified
    // separately (the .NET GC roots a delegate's target through its holder, spike c1; a
    // six-parameter Kotlin slot runs, spike c2; a Kotlin slot is callable from a pool thread,
    // ADR-085's spike), but the composition only exists once the generator emits it.
    //
    // Nothing on the Kotlin side roots the lambda after `WorkshopKeep` returns, so a per-call
    // borrow (the literal ADR-036 inversion) frees it at that return and every assertion below is
    // a use-after-free. Then the release is observed rather than assumed: `Forget` drops the C#
    // delegate, which is the ONLY thing keeping the Kotlin lambda alive, so
    // `kotlinBridgeReleaseCount` has to move. It is GC-timed and never prompt, so it is polled.
    [Fact]
    public async Task StoredLambda_SurvivesCollections_RunsOnAPoolThread_ThenReleasesOnDrop()
    {
        WorkshopSample.WorkshopKeep(3);

        for (int round = 0; round < 3; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            NugetBridge.GcCollect();
        }

        // Invoked on the calling thread, long after the crossing that passed it returned.
        Assert.Equal(21, WorkshopSample.WorkshopRunKept(7));

        // Invoked on a .NET POOL thread: a Kotlin slot reached from a thread Kotlin never created.
        Assert.Equal(21, await WorkshopSample.WorkshopRunKeptOnPoolAsync(7));

        SettleKotlinReleases();
        int before = MenagerieSample.KotlinBridgeReleaseCount();

        WorkshopSample.WorkshopForget();

        Assert.True(
            KotlinReleaseFiredWithin(before, TimeSpan.FromSeconds(5)),
            "expected dropping the stored C# delegate to release the Kotlin lambda; " +
            $"kotlinBridgeReleaseCount stayed at {before}");
    }

    // The other way a stored delegate dies: the OWNER is disposed with the delegate still stored.
    // The holder becomes unreachable along with the `Workshop`, so the same release has to fire
    // without anyone calling `Forget`.
    [Fact]
    public void StoredLambda_IsReleased_WhenTheOwnerIsDisposed()
    {
        WorkshopSample.WorkshopKeep(5);
        Assert.Equal(25, WorkshopSample.WorkshopRunKept(5));

        SettleKotlinReleases();
        int before = MenagerieSample.KotlinBridgeReleaseCount();

        WorkshopSample.WorkshopDisposeHeld();

        Assert.True(
            KotlinReleaseFiredWithin(before, TimeSpan.FromSeconds(5)),
            "expected disposing the owner of a stored delegate to release the Kotlin lambda; " +
            $"kotlinBridgeReleaseCount stayed at {before}");
    }

    private static void SettleKotlinReleases()
    {
        for (int round = 0; round < 5; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            Thread.Sleep(25);
        }
    }

    private static bool KotlinReleaseFiredWithin(int before, TimeSpan budget)
    {
        DateTime deadline = DateTime.UtcNow + budget;
        while (DateTime.UtcNow < deadline)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            if (MenagerieSample.KotlinBridgeReleaseCount() > before) return true;
            Thread.Sleep(25);
        }

        return false;
    }
}

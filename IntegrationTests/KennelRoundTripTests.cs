using TestLibrary.Kennel;

namespace IntegrationTests;

// ADR-152: a C# `Task` / `Task<T>` member consumed from Kotlin as a `suspend fun`, over the
// begin/end thunk pair and one uniform completion callback.
//
//   C# IntegrationTests (this file)
//     -> (forward bridge, ADR-019)      KennelSample.*Async   (a Kotlin suspend fun IS a C# Task)
//       -> Kotlin test-library          KennelSample.kt
//         -> (reverse bridge, ADR-152)  test.kennel.{Kennel, Kitten}
//           -> real C# TestDependency   Test.Kennel.{Kennel, Kitten}
//
// So every assertion below crosses the bridge FOUR times, and the async shape twice: once forward
// as ADR-019's `Task`, once back as ADR-152's `suspend fun`. A row that only proved "a Task came
// back" would prove the forward half only, which already shipped; each row here names the reverse
// seam it is standing on.
//
// One row per mechanism, deliberately not per type: `Task` with no result, `Task<int>` with no
// conversion at all, `Task<string>` with one, `Task<Kitten>` with a handle, `Task<string?>` with a
// nullable reference, a fault AFTER a real await, a synchronous throw BEFORE any Task exists, an
// already-completed task hammered in a tight loop, a static member, the `Read`/`ReadAsync` suffix
// pair, and a Kotlin-implemented bound interface used after the await. A fixture trimmed to
// `Task<int>` needs no marshalling anywhere and would go green while `End` was still wrong for
// every other return shape.
public class KennelRoundTripTests
{
    // The already-completed rows run the reverse call this many times inside ONE Kotlin coroutine.
    // ADR-019's race class (the completion callback landing before `suspendCancellableCoroutine`'s
    // block returns) reads about +1 per thousand, so a handful of iterations proves nothing: a
    // resume-before-suspend bug would simply not be sampled.
    private const int TightLoop = 5_000;

    // Reverse throws arrive as `NugetManagedException(managedType, message)`; the Kotlin side
    // surfaces them forward as "managedType|message".
    private static void AssertManaged(string surfaced, string managedType, string message)
    {
        string[] parts = surfaced.Split('|');
        Assert.True(parts.Length == 2, $"expected 'managedType|message' from the Kotlin catch site, got: {surfaced}");
        Assert.Equal(managedType, parts[0]);
        Assert.Equal(message, parts[1]);
    }

    // Non-generic `Task`: the shape the reader used to misfile as `skipped_unbound_type_reference`
    // because `GetTypeFromReference` knew only `System.String`. Nothing comes back but the
    // resumption itself, so this row fails as a hang or a wrong value, never as a marshalling bug.
    [Fact]
    public async Task Nap_NonGenericTask_Resumes() =>
        Assert.Equal("rested", await KennelSample.OreoNapsAsync());

    // `Task<int>`: the ONE return shape that needs no conversion in `End`. Here as the control,
    // not as the feature.
    [Fact]
    public async Task Count_PassThroughScalar() =>
        Assert.Equal(2, await KennelSample.KennelCountAsync());

    // `Task<string>`: `End` allocates with StringToCoTaskMemUTF8 and Kotlin frees it. The ampersand
    // is there so a naive round trip through anything XML- or shell-shaped shows up.
    [Fact]
    public async Task Name_StringResult() =>
        Assert.Equal("Oreo & Mylo", await KennelSample.KennelNameAsync());

    // `Task<Kitten>`: a bound-class HANDLE return resolved after the await, from a method that
    // also takes a string ARGUMENT, whose `memScoped` buffer dies when `Begin` returns.
    [Fact]
    public async Task Adopt_HandleResult_WithStringArgument() =>
        Assert.Equal("Mylo", await KennelSample.AdoptMyloAsync());

    // `Task<string?>`, the non-null half. Its NullableAttribute bytes are [1, 2], pre-order over
    // the WHOLE tree with the Task node first, so a reader that unwrapped before resolving would
    // read byte 1 here and bind the whole thing non-null.
    [Fact]
    public async Task Whisper_NullableReferenceResult_Present() =>
        Assert.Equal("Oreo purrs back", await KennelSample.WhisperToAsync("Oreo"));

    // The null half of the same member: the row a binding that lost byte 2 fails on.
    [Fact]
    public async Task Whisper_NullableReferenceResult_Null() =>
        Assert.Equal("<null>", await KennelSample.WhisperToAsync("Rex"));

    // The single-byte NullableAttribute encoding (three nullable parameters put the method in a
    // NullableContext(2), so an all-non-null two-node return tree is written as ONE byte). ADR-152
    // inferred that byte expands to the node count; it did not, and this member was skipped.
    [Fact]
    public async Task Ledger_SingleByteNullableEncoding_Binds() =>
        Assert.Equal("kibble/-/tuna", await KennelSample.KennelLedgerAsync());

    // Faults AFTER a real suspension point, so the exception arrives on the continuation rather
    // than on the `Begin` call. `End` must use GetAwaiter().GetResult(), which rethrows the
    // ORIGINAL exception: `.Result` would surface System.AggregateException and this row is what
    // says so.
    [Fact]
    public async Task Escape_FaultedTask_SurfacesTheOriginalExceptionNotAggregate() =>
        AssertManaged(
            await KennelSample.OreoEscapesAsync(),
            "System.InvalidOperationException",
            "Oreo slipped the latch");

    // The synchronous throw, before any Task exists: it leaves through `Begin`'s ADR-104 `errOut`
    // on the calling thread and the completion callback NEVER fires, so the pending-continuation
    // ctx has exactly one owner on this path and it is the Kotlin side.
    [Fact]
    public async Task Reject_SynchronousThrowBeforeTheTask_SurfacesThroughBegin() =>
        Assert.Equal("System.ArgumentNullException", await KennelSample.RejectNullAsync());

    // Already completed before `Begin` returns (Task.FromResult), hammered: the callback can land
    // before the suspend block returns, and that ordering is a race, not a guarantee.
    [Fact]
    public async Task Seated_AlreadyCompletedTask_TightLoop() =>
        Assert.Equal(7 * TightLoop, await KennelSample.SeatedRepeatedlyAsync(TightLoop));

    // Task.CompletedTask: the same race on the `void` return half, where a lost resumption is a
    // hang rather than a wrong number.
    [Fact]
    public async Task Settle_AlreadyCompletedVoidTask_TightLoop() =>
        Assert.Equal($"settled {TightLoop} times", await KennelSample.SettledRepeatedlyAsync(TightLoop));

    // A STATIC async member: its `Begin` has no selfHandle, a different registration and call site
    // from every instance row above.
    [Fact]
    public async Task RollCall_StaticAsyncMethod() =>
        Assert.Equal("Oreo, Mylo", await KennelSample.RollCallAsync());

    // The `Async` suffix rule, both halves at once: `read()` (no suffix to strip) and
    // `readAsync()` (suffix KEPT, because the declaring type already has `Read` and Kotlin cannot
    // overload on `suspend` alone). Stripping unconditionally here is an
    // ERROR_KOTLIN_SIGNATURE_COLLISION, so this row is red in the generator, not in the assertion.
    [Fact]
    public async Task Read_AndReadAsync_SuffixKeptForTheSyncSibling() =>
        Assert.Equal("the kennel ledger~the kennel ledger, read slowly", await KennelSample.ReadBothWaysAsync());

    // A Kotlin-implemented bound interface (ADR-085) passed to an async member that calls back
    // into it AFTER the await, when the transfer scope that minted the bridge GCHandle is long
    // gone (ADR-152's inferred claim C, which is SILENT if wrong: a use-after-free on the Kotlin
    // implementation). The trailing meal count is read back off the Kotlin object, so this asserts
    // the post-await dispatch REACHED Kotlin rather than that a string came back.
    [Fact]
    public async Task Board_KotlinImplementedInterface_UsedAfterTheAwait() =>
        Assert.Equal("boarded Nibbles the goat with 4 legs~1", await KennelSample.BoardNibblesAsync());
}

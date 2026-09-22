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

    // ----------------------------------------------------------------------------------------
    // ADR-153: cancellation, in both directions, which are NOT the same mechanism.
    //
    //   Kotlin cancels -> the bridge cancels the CancellationToken it supplied -> C# is told to
    //                     stop. `End` is never reached, so nothing is mapped and the ONLY
    //                     observable is C#-side state, read back off the same object.
    //   C# cancels     -> the task ends Canceled, `End` rethrows, and the mapping turns that into
    //                     a Kotlin CancellationException carrying the NugetManagedException.
    //
    // The two token-taking members that must stay SKIPPED (the sync `Wait(CancellationToken)` and
    // the two-token `HerdAsync`) cannot be asserted from here: their absence is a property of the
    // generated Kotlin surface, pinned by the `info_cancellation_token_not_yet_mapped` diagnostic
    // on the generator side. If they ever bind, KennelSample.kt is where it shows.
    // ----------------------------------------------------------------------------------------

    // KOTLIN CANCELS, via withTimeout. Three facts in one string, in order: the Kotlin wait ended
    // (it did not hang on a method that waits forever), C# SAW the cancellation on its token, and
    // it saw it exactly once. Only the first of those is true today, which is the feature: a
    // bridge that supplies a token it never cancels passes the first and fails the other two.
    [Fact]
    public async Task Stay_CoroutineTimeout_CancelsTheCSharpToken() =>
        Assert.Equal("true|true|1", await KennelSample.StayTimesOutAsync());

    // The same cancellation from `job.cancel()` on another coroutine, so the thunk that cancels
    // the token is entered from a thread the CLR did not create (ADR-153's inferred claim C,
    // silent if wrong on the happy path and a crash if wrong at all).
    [Fact]
    public async Task Stay_JobCancel_CancelsTheCSharpToken() =>
        Assert.Equal("true|1", await KennelSample.StayJobCancelledAsync());

    // A method that IGNORES its token: the Kotlin wait still ends promptly (C# is never obliged
    // to stop), and the C# work still runs to completion afterwards. Both halves, because "ended
    // promptly" alone is also what a dropped call looks like.
    [Fact]
    public async Task Dawdle_IgnoredToken_KotlinResumesAndTheCSharpWorkRunsOn() =>
        Assert.Equal("true|true", await KennelSample.DawdleIgnoresTheTokenAsync());

    // C# CANCELS ITSELF, Kotlin did not: the only path that reaches the mapping site at all, so
    // this row is the feature and not an edge case. Today it arrives as a plain
    // NugetManagedException and is not caught as a CancellationException at all. The cause is
    // asserted rather than the type alone: the mapping must not lose `managedType`.
    [Fact]
    public async Task Bolt_CSharpSelfCancel_SurfacesAsCancellationExceptionWithTheManagedCause() =>
        Assert.StartsWith(
            "System.Threading.Tasks.TaskCanceledException|",
            await KennelSample.BoltSurfacesAsCancellationAsync());

    // The same self-cancel through a user SUBCLASS of OperationCanceledException. This is the row
    // that decides the mapping's implementation: a Kotlin-side name match over the two well-known
    // type names is green on Bolt and red here, an `is OperationCanceledException` test on the C#
    // side is green on both.
    [Fact]
    public async Task Scarper_UserOceSubclass_SurfacesAsCancellationException() =>
        Assert.Equal("Test.Kennel.BoltedException|Oreo scarpered", await KennelSample.ScarperSurfacesAsCancellationAsync());

    // The SYNC route: one managed-throw site serves every thunk, so an OperationCanceledException
    // out of an ordinary (non-async, token-less) call maps too. Pinned as a decision, not left as
    // an accident of where the mapping happens to live.
    [Fact]
    public async Task Startle_SynchronousOce_SurfacesAsCancellationException() =>
        Assert.Equal(
            "System.OperationCanceledException|Mylo startled off the sill",
            await KennelSample.StartleSurfacesAsCancellationAsync());

    // The token in a MID position, with a string that needs conversion on one side and an int
    // that does not on the other. An implementation that assumes "the token is last" marshals the
    // count into the string slot, which is a wrong answer here rather than a compile error.
    [Fact]
    public async Task Fetch_TokenInAMiddlePosition_MarshalsTheRemainingArgumentsInOrder() =>
        Assert.Equal("Mouse x3", await KennelSample.FetchWithAMidTokenAsync());

    // The `CallAsync()` / `CallAsync(CancellationToken)` fold: after elision both project to
    // `suspend fun call()`, which does not compile in the consumer, so the reader keeps one. This
    // row says WHICH one, and it must be the token overload: keeping the token-less sibling also
    // compiles, also binds, and ships the feature with no effect on the commonest .NET shape.
    [Fact]
    public async Task Call_OverloadPair_KeepsTheTokenOverload() =>
        Assert.Equal("called with a token", await KennelSample.CallKeepsTheTokenOverloadAsync());

    // A `= default` token. The bridge always supplies its own, so the default is never consulted;
    // this pins that `Optional, HasDefault` on the Param row does not change the decision to bind.
    [Fact]
    public async Task Doze_DefaultedToken_StillBindsWithTheTokenElided() =>
        Assert.Equal(6, await KennelSample.DozeWithADefaultTokenAsync());

    // ----------------------------------------------------------------------------------------
    // ADR-156: a C# `IAsyncEnumerable<T>` member as a COLD Kotlin `Flow<T>`, pulled one
    // `MoveNextAsync` at a time over the same begin/end pair. One row per mechanism again, and
    // deliberately not one per element type: the element vocabulary is ADR-152's (already proved
    // above), whereas coldness, stopping and the mid-stream fault are new and are where a
    // plausible-looking implementation is wrong.
    //
    // The stopping rows read C#-side counters back, because "the collector saw one element" is
    // equally true of a bridge that stopped the C# enumeration, one that abandoned it still
    // running, and one that never started it. Disposal is fire-and-forget (ADR-156 open question
    // 2), so the Kotlin side POLLS for the iterator's `finally` rather than assuming it has run by
    // the time `collect` returned.
    // ----------------------------------------------------------------------------------------

    // Elements in order, for the CONVERTING element (`string`): the ordinary happy path, and the
    // control for every row below it.
    [Fact]
    public async Task Barks_CollectsEveryElementInOrder() =>
        Assert.Equal("woof0,woof1", await KennelSample.KennelBarksAsync(2));

    // COLDNESS, and with it ADR-156's open question 1. ONE Kotlin `Flow` value collected TWICE:
    // both collections must see the full stream, and BOTH C# counters must read 2 — the method was
    // called once per collect (so its per-collect CancellationToken is real) and its iterator body
    // ran once per collect. A flow that eagerly called the C# method and shared one enumeration
    // still delivers both lists correctly — a compiler-generated iterator re-enumerates from the
    // start on a second GetAsyncEnumerator (ADR-156 ledger (e)) — and reads "1|2" here: called
    // once, enumerated twice, which is the one fact only this shape can see.
    [Fact]
    public async Task Barks_OneFlowCollectedTwice_IsColdAndCallsTheMethodPerCollect() =>
        Assert.Equal("woof0~woof0|2|2", await KennelSample.BarksCollectedTwiceAsync());

    // CANCEL MID-STEP against a source that IGNORES the token. The collector's cancellation lands
    // while C# is inside an uninterruptible wait, so ADR-156's documented behaviour is: C# finishes
    // that step and yields once more, the collector receives NOTHING after the cancel, and the
    // enumeration is then disposed. Read in order: 1 delivered, 2 yielded by C#, the iterator's
    // `finally` observed, and it ran exactly once. `delivered == 2` would mean an element was
    // handed to the collector after cancellation; `finally` never running means the C# iterator was
    // abandoned mid-stream, which is the leak this whole dispose sequence exists to prevent.
    [Fact]
    public async Task Barks_CollectorCancelledMidStep_StopsAtTheNextElementAndRunsTheFinally() =>
        Assert.Equal("1|2|true|1", await KennelSample.BarksCancelledMidStepAsync());

    // The same source aborted while it is SUSPENDED AT A YIELD (`take(1)`), with no step in flight:
    // here C# never produces a second element at all (`yields == 1`), which is what distinguishes
    // the two stopping paths. A bridge that disposed the enumerator during a pending step would
    // throw NotSupportedException (ADR-156 ledger (a)) rather than reaching this assertion.
    [Fact]
    public async Task Barks_TakeOne_DisposesAtTheYieldWithoutAnotherElement() =>
        Assert.Equal("woof0|1|true", await KennelSample.FirstBarkOnlyAsync());

    // The bound-class HANDLE element, resolved once per element rather than once per call: each
    // `Kitten` is a fresh handle the Kotlin collector owns and closes.
    [Fact]
    public async Task Litter_HandleElements_CollectsEveryElementInOrder() =>
        Assert.Equal("Oreo,Mylo", await KennelSample.KennelLitterAsync());

    // CANCEL MID-STEP against a source that HONOURS the token ([EnumeratorCancellation]). Same
    // collector cancellation as the barks row, different C# outcome: the pending step is aborted
    // and `litterCancelled` proves the per-collect token the bridge owns actually reached the C#
    // method. A bridge that passed `CancellationToken.None` to `GetAsyncEnumerator` delivers one
    // element and runs the finally exactly as here, and reads `false` in the middle field.
    [Fact]
    public async Task Litter_CollectorCancelledMidStep_CancelsTheCSharpToken() =>
        Assert.Equal("1|true|true", await KennelSample.LitterCancelledMidStepAsync());

    // STATIC: no selfHandle in the generated `Enumerate`, the method is called off the TYPE. The
    // nullable VALUE element this row used to carry (`IAsyncEnumerable<int?>`) is a split-out
    // item — System.Nullable<int> has no reverse mapping at all — and lives on as
    // `Kennel.NullableTicks`. That it stays a NAMED skip is asserted against an inline probe
    // assembly in NugetExtractApiIntegrationTest; here it simply must not appear on the binding.
    [Fact]
    public async Task Ticks_StaticSource_CollectsEveryElement() =>
        Assert.Equal("1,2,3", await KennelSample.KennelTicksAsync());

    // The MID-STREAM THROW, on the element needing no conversion at all. Three facts in order: the
    // two good elements arrived BEFORE the fault (a stream that delivered nothing is a different
    // bug), the fault surfaced on the collector as a catchable ADR-104 NugetManagedException
    // carrying the managed type name, and the C# iterator's `finally` still ran.
    [Fact]
    public async Task Howls_ThrowsMidStream_SurfacesAsACatchableManagedException()
    {
        string surfaced = await KennelSample.HowlsFaultAsync();
        string[] parts = surfaced.Split('|');
        Assert.True(parts.Length == 4, $"expected 'elements|managedType|message|cleaned', got: {surfaced}");
        Assert.Equal("1,2", parts[0]);
        Assert.Equal("System.InvalidOperationException", parts[1]);
        Assert.Equal("Mylo howled the roof off", parts[2]);
        Assert.Equal("true", parts[3]);
    }

    // The other half of "not a host abort": the fault above is thrown from inside a generated
    // unmanaged callback path, and the failure mode that row cannot see is the process dying just
    // after it passed. Any reverse call that still works afterwards says the host survived; this
    // one is the cheapest.
    [Fact]
    public async Task Howls_ThrowsMidStream_DoesNotAbortTheHost()
    {
        await KennelSample.HowlsFaultAsync();
        Assert.Equal(2, await KennelSample.KennelCountAsync());
    }

    // ------------------------------------------------------------------------------------------
    // The reverse compile-break rows (memo sub-items A, B and E). For these the GREEN BUILD is
    // most of the assertion: each row's Kotlin or C# counterpart does not compile today, and the
    // value only proves the wiring once it does. Three separate mechanisms, none of which the other
    // two would catch:
    //
    //   A  an `init`-only property is read-only in metadata but the reader calls it writable, so the
    //      generated C# thunk assigns it (CS8852) and the Kotlin surface is a `var`. On an interface
    //      the same mistake makes the ADR-085 Kotlin-implementable bridge CS8854.
    //   B  an interface-typed parameter with a struct return emits `handleOf(...)` outside any
    //      `nugetTransferScope`, an unresolved reference in `compileKotlin`.
    //   E  an interface declared in another bound namespace, in a READ position, lowers through
    //      `nugetIFeedableValue(...)` without importing it: also unresolved.
    // ------------------------------------------------------------------------------------------

    // A, class half: both `init`-only properties read back. One needs UTF-8 marshalling, one is a
    // pass-through `int` — the int is here because its setter thunk would compile cleanly in C#
    // while still being illegal, so a string-only fixture proves less than it looks.
    [Fact]
    public void Motto_InitOnlyProperties_BindAsReadableValues() =>
        Assert.Equal("sit, stay|2", KennelSample.KennelMotto());

    // A, interface half, INBOUND: a Kotlin object implementing `{ get; init; }` with two `val`s,
    // read by C# through the bridge. This is the row the decision "init becomes a Kotlin val" is
    // for: if the bridge could not carry an `init` accessor, Kotlin could not implement IBadge at
    // all and this member would be unreachable.
    [Fact]
    public void Inspect_KotlinImplementedInitOnlyInterface_ReadsBothMembers() =>
        Assert.Equal("Oreo's rosette#1", KennelSample.InspectAKotlinBadge());

    // A, interface half, OUTBOUND: the same interface handed back from C#, read through the
    // handle-backed implementation. Getter slots only — a setter slot on an `init` member is the
    // thing that must NOT be generated.
    [Fact]
    public void Issue_CSharpOriginatedInitOnlyInterface_ReadsThroughTheHandle() =>
        Assert.Equal("Oreo's rosette#1", KennelSample.IssuedBadge());

    // B: interface parameter + struct return, with a KOTLIN-implemented argument, so the bridge
    // handle has to stay valid for the duration of the call (`Fit` reads `guest.Legs` inside). All
    // five Collar components come back: `girth` is read off the Kotlin goat (4), the rest are C#
    // literals, so a dropped string or a swapped out-pointer is visible here rather than implied.
    [Fact]
    public void Fit_InterfaceParameterWithStructReturn_MarshalsEveryComponent() =>
        Assert.Equal("4:red:true:O:PLAYFUL", KennelSample.FitAKotlinGuest());

    // B coupled to E: the argument is the C#-originated IFeedable from `Resident()`, so one Kotlin
    // statement crosses the read path (E's resolver) and then the argument path (B's handleOf).
    [Fact]
    public void Fit_ResidentAsArgument_CrossesBothInterfacePositions() =>
        Assert.Equal("4:red", KennelSample.FitTheResident());

    // E, METHOD RETURN: the cross-namespace interface resolved through
    // `test.menagerie.nugetIFeedableValue` from inside the `test.kennel` file. Dispatches a method,
    // a property and a NULLABLE property on the result, so a resolver that returned a handle Kotlin
    // cannot actually call would fail here rather than pass on construction alone.
    [Fact]
    public void Resident_CrossNamespaceInterfaceReturn_Dispatches() =>
        Assert.Equal("a ferret|4|Bandit", KennelSample.ResidentDescribed());

    // E, NULLABLE PROPERTY: a different generated line from the method return above
    // (`ptr?.let { ... }` rather than `requireNotNull`). The setter direction uses `handleOf` and
    // never needed the resolver, so the round trip pins that the import fix did not disturb it.
    [Fact]
    public void Favourite_NullableCrossNamespaceInterfaceProperty_RoundTrips() =>
        Assert.Equal("true|a ferret|Mylo|true", KennelSample.FavouriteRoundTrip());
}

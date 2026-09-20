using Test.Menagerie;

namespace Test.Kennel;

/// <summary>
/// ADR-152 fixture (reverse `Task` / `Task&lt;T&gt;` → Kotlin `suspend fun`): a bound handle class
/// returned from an async member, so <see cref="Kennel.AdoptAsync"/> has a real HANDLE return
/// shape to resolve after the await. Oreo and Mylo already own half this repository's fixtures, so
/// the kennel's residents are whoever <see cref="Kennel.AdoptAsync"/> is handed.
/// </summary>
public class Kitten
{
    public Kitten(string name) => Name = name;

    /// <summary>The kitten's name, e.g. "Oreo" or "Mylo".</summary>
    public string Name { get; }
}

/// <summary>
/// ADR-152 fixture: every seam a `Task`-returning C# member has to cross to become a Kotlin
/// `suspend fun`, and no more. Each member is here for exactly one mechanism:
///
/// <list type="bullet">
///   <item><see cref="NapAsync"/>: non-generic <c>Task</c>, no result at all (the reader bug this
///         ADR fixes: today it lands on <c>skipped_unbound_type_reference</c> rather than the
///         async path, because <c>GetTypeFromReference</c> knows only <c>System.String</c>).</item>
///   <item><see cref="CountAsync"/>: <c>Task&lt;int&gt;</c>, a pass-through scalar needing NO
///         conversion in <c>End</c>.</item>
///   <item><see cref="NameAsync"/>: <c>Task&lt;string&gt;</c>, needing the
///         <c>StringToCoTaskMemUTF8</c> conversion Kotlin then frees.</item>
///   <item><see cref="AdoptAsync"/>: <c>Task&lt;Kitten&gt;</c>, a bound-class HANDLE return, with
///         a string ARGUMENT that <c>Begin</c> copies before the <c>memScoped</c> buffer dies.</item>
///   <item><see cref="WhisperAsync"/>: <c>Task&lt;string?&gt;</c>, a NULLABLE reference result.
///         Its <c>NullableAttribute</c> bytes are <c>[1, 2]</c>, pre-order over the WHOLE tree
///         with the <c>Task</c> node included, so a reader that unwraps to <c>T</c> before
///         resolving nullability hands a 2-byte array to a 1-node tree and either fails or, worse,
///         reads byte 1 and binds this non-null.</item>
///   <item><see cref="EscapeAsync"/>: faults AFTER a real suspension point, so the exception
///         arrives on the continuation, not on the <c>Begin</c> call.</item>
///   <item><see cref="RejectAsync"/>: deliberately NOT <c>async</c>: throws synchronously, before
///         any <c>Task</c> exists, so the throw takes the ordinary ADR-104 <c>errOut</c> path out
///         of <c>Begin</c> and the completion callback never fires. The one row where releasing
///         the pending-continuation <c>ctx</c> is Kotlin's job.</item>
///   <item><see cref="SeatedAsync"/> / <see cref="SettleAsync"/>: ALREADY COMPLETED before
///         <c>Begin</c> returns (<c>Task.FromResult</c> / <c>Task.CompletedTask</c>), the ADR-019
///         race class: the callback can land before the <c>suspendCancellableCoroutine</c> block
///         returns.</item>
///   <item><see cref="RollCallAsync"/>: a STATIC async method, a different registration and call
///         site from every instance member above (no <c>selfHandle</c> in <c>Begin</c>).</item>
///   <item><see cref="Read"/> + <see cref="ReadAsync"/>: the ubiquitous .NET pairing. The
///         <c>Async</c> suffix is STRIPPED everywhere else here, and KEPT on this one, because the
///         declaring type already has a member named <c>Read</c> and Kotlin cannot overload on
///         <c>suspend</c> alone.</item>
///   <item><see cref="BoardAsync"/>: takes a Kotlin-implementable bound interface
///         (<see cref="IFeedable"/>, the ADR-070/085 fixture) and calls back into it AFTER the
///         await, when the transfer scope that minted the bridge <c>GCHandle</c> is long gone.
///         ADR-152's inferred claim C, which is SILENT if wrong.</item>
///   <item><see cref="PurrsAsync"/>: <c>ValueTask&lt;int&gt;</c>, deliberately OUT of v1 scope.
///         It must stay skipped with a named <c>info_async_not_yet_mapped</c> diagnostic rather
///         than binding or disappearing silently.</item>
/// </list>
///
/// A mixed vocabulary on purpose: a fixture built only from <c>Task&lt;int&gt;</c> would go green
/// while the string, handle, nullable and fault paths were all still wrong.
/// </summary>
public class Kennel
{
    private const int Delay = 10;

    // Long enough that a Kotlin `withTimeoutOrNull` around `DawdleAsync` is guaranteed to give up
    // first, so the row proves the Kotlin wait ends promptly while the C# work runs on.
    private const int Dawdle = 300;

    /// <summary>Non-generic <c>Task</c>, genuinely asynchronous (<c>Task.Delay</c>).</summary>
    public async Task NapAsync() => await Task.Delay(Delay);

    /// <summary><c>Task&lt;int&gt;</c>: a pass-through scalar, no conversion in <c>End</c>.</summary>
    public async Task<int> CountAsync()
    {
        await Task.Delay(Delay);
        return 2;
    }

    /// <summary><c>Task&lt;string&gt;</c>: the CoTaskMem string conversion, after a real yield.</summary>
    public async Task<string> NameAsync()
    {
        await Task.Yield();
        return "Oreo & Mylo";
    }

    /// <summary><c>Task&lt;Kitten&gt;</c>: a bound-class handle return, with a string argument.</summary>
    public async Task<Kitten> AdoptAsync(string name)
    {
        await Task.Delay(Delay);
        return new Kitten(name);
    }

    /// <summary>
    /// <c>Task&lt;string?&gt;</c>: a NULLABLE reference result, null for anyone but the two
    /// residents. Its nullability lives at the SECOND annotatable node of the return tree.
    /// </summary>
    public async Task<string?> WhisperAsync(string name)
    {
        await Task.Delay(Delay);
        return name is "Oreo" or "Mylo" ? $"{name} purrs back" : null;
    }

    /// <summary>Faults AFTER a real suspension point, so the fault rides the continuation.</summary>
    public async Task EscapeAsync(string name)
    {
        await Task.Yield();
        throw new InvalidOperationException($"{name} slipped the latch");
    }

    /// <summary>
    /// NOT <c>async</c>: throws synchronously, before any <c>Task</c> exists, so this lands in
    /// <c>Begin</c>'s <c>errOut</c> and the callback never fires.
    /// </summary>
    public Task<int> RejectAsync(string? name)
    {
        ArgumentNullException.ThrowIfNull(name);
        return Task.FromResult(name.Length);
    }

    /// <summary>Already completed before <c>Begin</c> returns (<c>Task.FromResult</c>).</summary>
    public Task<int> SeatedAsync() => Task.FromResult(7);

    /// <summary>Already completed, with no result (<c>Task.CompletedTask</c>).</summary>
    public Task SettleAsync() => Task.CompletedTask;

    /// <summary>STATIC async method: no <c>selfHandle</c> in the generated <c>Begin</c>.</summary>
    public static async Task<string> RollCallAsync()
    {
        await Task.Delay(Delay);
        return "Oreo, Mylo";
    }

    /// <summary>The SYNC sibling that makes <see cref="ReadAsync"/> keep its suffix.</summary>
    public string Read() => "the kennel ledger";

    /// <summary>
    /// Keeps its <c>Async</c> suffix in Kotlin, because <see cref="Read"/> already owns the
    /// stripped name and Kotlin cannot overload on <c>suspend</c>.
    /// </summary>
    public async Task<string> ReadAsync()
    {
        await Task.Delay(Delay);
        return "the kennel ledger, read slowly";
    }

    /// <summary>
    /// Calls back into a (possibly Kotlin-implemented) <see cref="IFeedable"/> AFTER the await,
    /// long after <c>Begin</c> returned and its transfer scope freed the bridge handle.
    /// </summary>
    public async Task<string> BoardAsync(IFeedable guest)
    {
        await Task.Delay(Delay);
        guest.Feed("kibble");
        return $"boarded {guest.Describe()} with {guest.Legs} legs";
    }

    /// <summary>
    /// The SINGLE-BYTE <c>NullableAttribute</c> encoding for a multi-node return tree. Three
    /// nullable parameters push this method to <c>NullableContext(2)</c>, so its non-null
    /// <c>Task&lt;string&gt;</c> return (nodes [1, 1], all equal) is written as
    /// <c>NullableAttribute(1)</c>, one byte, not a two-element array. ADR-152's inferred claim D
    /// says that byte expands to the node count; it did not, and a reader that only compares
    /// lengths skips this member outright.
    /// </summary>
    public async Task<string> LedgerAsync(string? morning, string? noon, string? night)
    {
        await Task.Delay(Delay);
        return $"{morning ?? "-"}/{noon ?? "-"}/{night ?? "-"}";
    }

    /// <summary>
    /// <c>ValueTask&lt;int&gt;</c>: out of v1 scope. Must stay SKIPPED with a named
    /// <c>info_async_not_yet_mapped</c> diagnostic, never bound and never silently dropped.
    /// </summary>
    public ValueTask<int> PurrsAsync() => ValueTask.FromResult(3);

    // ----------------------------------------------------------------------------------------
    // ADR-153: the CancellationToken half. Cancelling the Kotlin coroutine has to reach the token
    // the bridge supplies here, and a task that ends CANCELLED has to surface in Kotlin as a
    // CancellationException rather than NugetManagedException. Every member below is one seam of
    // that, and the observable state (StayCancelled / StayCancellations / DawdleCompleted) exists
    // because "the Kotlin wait ended" proves only that Kotlin stopped waiting: it says nothing
    // about whether C# was ever told to stop. Oreo stays until told otherwise, Mylo dawdles.
    // ----------------------------------------------------------------------------------------

    /// <summary>True once <see cref="StayAsync"/> has SEEN a cancellation on its token.</summary>
    public bool StayCancelled { get; private set; }

    /// <summary>How many <see cref="StayAsync"/> calls saw their token cancelled.</summary>
    public int StayCancellations { get; private set; }

    /// <summary>True once a <see cref="DawdleAsync"/> call ran to completion, cancelled or not.</summary>
    public bool DawdleCompleted { get; private set; }

    /// <summary>
    /// HONOURS the token: waits forever until the bridge-owned <c>CancellationTokenSource</c> is
    /// cancelled, then records that it was told to stop before rethrowing. The recording is the
    /// point: without it a bridge that never cancels anything looks identical from Kotlin, because
    /// the coroutine ends on its own cancellation either way. Token LAST, the common .NET shape.
    /// </summary>
    public async Task<int> StayAsync(string name, CancellationToken ct)
    {
        try
        {
            await Task.Delay(Timeout.Infinite, ct);
            return name.Length;
        }
        catch (OperationCanceledException)
        {
            StayCancelled = true;
            StayCancellations++;
            throw;
        }
    }

    /// <summary>
    /// IGNORES the token, and takes a while. The Kotlin wait must still end promptly on cancel
    /// (the coroutine resumes with its own exception, the C# work is simply not obliged to stop),
    /// and <see cref="DawdleCompleted"/> is how the test sees that C# carried on regardless.
    /// </summary>
    public async Task<int> DawdleAsync(CancellationToken ct)
    {
        await Task.Delay(Dawdle);
        DawdleCompleted = true;
        return 9;
    }

    /// <summary>
    /// A <c>= default</c> token. The default is irrelevant to the bridge (it always supplies its
    /// own), so this row exists to pin that <c>Optional, HasDefault</c> on the Param row does not
    /// change the decision, and that the method still binds with the token elided.
    /// </summary>
    public async Task<int> DozeAsync(int minutes, CancellationToken ct = default)
    {
        await Task.Delay(Delay, ct);
        return minutes * 2;
    }

    /// <summary>
    /// The token in a MID position, between a parameter that needs conversion (<c>string</c>) and
    /// one that does not (<c>int</c>). The shim inserts <c>cts.Token</c> at the C# index while the
    /// Kotlin stub passes its two remaining arguments in their own order, so an implementation
    /// that assumes "the token is last" marshals <c>count</c> into the string slot.
    /// </summary>
    public async Task<string> FetchAsync(string toy, CancellationToken ct, int count)
    {
        await Task.Delay(Delay, ct);
        return $"{toy} x{count}";
    }

    /// <summary>
    /// Already completed BEFORE <c>Begin</c> returns, and token-taking: the ADR-019 race class
    /// with a CTS handle riding on it. The completion callback can land before
    /// <c>suspendCancellableCoroutine</c>'s block returns, which is exactly the window in which
    /// the CTS handle is minted but not yet stored (ADR-153's inferred claim B).
    /// </summary>
    public Task<int> PounceAsync(int height, CancellationToken ct) => Task.FromResult(height);

    /// <summary>
    /// The <c>FooAsync()</c> / <c>FooAsync(CancellationToken)</c> pair every .NET library ships.
    /// After elision both project to <c>suspend fun call()</c>, so the reader folds them and KEEPS
    /// the token overload. The two bodies return DIFFERENT strings on purpose: a fold that kept
    /// the wrong sibling still compiles, still binds, and is only visible here.
    /// </summary>
    public Task<string> CallAsync() => Task.FromResult("called without a token");

    /// <inheritdoc cref="CallAsync()"/>
    public Task<string> CallAsync(CancellationToken ct) => Task.FromResult("called with a token");

    /// <summary>
    /// CANCELS ITSELF: Kotlin never cancels anything, and this still ends
    /// <c>TaskStatus.Canceled</c> with a <c>TaskCanceledException</c>. The only path on which the
    /// mapping site is reached at all (a Kotlin-side cancel never calls <c>End</c>), so this is
    /// the feature's main row, not an edge case.
    /// </summary>
    public async Task BoltAsync()
    {
        using CancellationTokenSource own = new();
        Task running = Task.Delay(Timeout.Infinite, own.Token);
        own.Cancel();
        await running;
    }

    /// <summary>
    /// The same self-cancel, ending in a USER SUBCLASS of <c>OperationCanceledException</c> rather
    /// than <c>TaskCanceledException</c>. A Kotlin-side name match over the two well-known names
    /// leaves this one an ordinary <c>NugetManagedException</c> and the test goes red here and
    /// nowhere else, which is the whole argument for an <c>is</c> test on the C# side.
    /// </summary>
    public async Task ScarperAsync(string name)
    {
        await Task.Yield();
        throw new BoltedException($"{name} scarpered");
    }

    /// <summary>
    /// SYNC, no token, throws an <c>OperationCanceledException</c>. There is one managed-throw
    /// site in the generated Kotlin, so the mapping applies to ordinary calls too: this row says
    /// whether that was a decision or an accident.
    /// </summary>
    public int Startle() => throw new OperationCanceledException("Mylo startled off the sill");

    /// <summary>
    /// SYNC and token-taking: OUT of scope, must be absent from the Kotlin surface with the named
    /// <c>info_cancellation_token_not_yet_mapped</c> diagnostic rather than the misleading
    /// <c>skipped_unbound_type_reference</c> hint that tells the user to bind the BCL.
    /// </summary>
    public int Wait(CancellationToken ct) => 0;

    /// <summary>
    /// TWO tokens: also out of scope (the bridge owns exactly one source, and picking one of two
    /// would be a guess), same named diagnostic, also absent from Kotlin.
    /// </summary>
    public async Task<int> HerdAsync(CancellationToken first, CancellationToken second)
    {
        await Task.Delay(Delay);
        return 2;
    }
}

/// <summary>
/// A user subclass of <see cref="OperationCanceledException"/>, which a cancelled <c>async</c>
/// method rethrows verbatim (verified by spike, ADR-153). Deliberately <c>internal</c>: it is a
/// throw shape, not bound surface, so it must not turn up as a bound type in the Kotlin bindings.
/// </summary>
internal sealed class BoltedException(string message) : OperationCanceledException(message);

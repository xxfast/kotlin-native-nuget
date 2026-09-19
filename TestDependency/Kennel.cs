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
}

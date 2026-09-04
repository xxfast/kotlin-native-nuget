using Test.Enums;
using Test.Structs;

namespace Test.Infirmary;

/// <summary>
/// ADR-104 fixture (reverse thunk error channel): a bound handle class returned by a member that
/// always throws. Nothing here is ever constructed successfully across the bridge. It exists so
/// <see cref="Infirmary.Admit"/> has a real handle RETURN shape to fail in. Oreo never actually
/// gets a bed.
/// </summary>
public class Patient
{
    public Patient(string name) => Name = name;

    /// <summary>The patient's name, e.g. "Oreo" or "Mylo".</summary>
    public string Name { get; }
}

/// <summary>
/// ADR-104 fixture (reverse thunk error channel): the vet's back room, where every examination
/// goes wrong. A managed exception thrown here has to escape an <c>[UnmanagedCallersOnly]</c>
/// reverse thunk and reach the Kotlin call site as a catchable
/// <c>NugetManagedException(managedType, message)</c>. Today it terminates the host instead.
///
/// The members are chosen for one thing only: the <b>RETURN SHAPE</b> of the generated thunk,
/// because ADR-104's ledger flags the error path's <c>return default</c> (with out-pointers left
/// unwritten) as INFERRED, not verified, and calls for a test per return shape. One throwing
/// member each:
/// <list type="bullet">
///   <item><see cref="Discharge"/>: <c>void</c> (nothing to return).</item>
///   <item><see cref="Temperature"/>: <c>int</c>, a pass-through scalar needing NO conversion.</item>
///   <item><see cref="Chart"/>: <c>string</c>, needing a native <c>StringToCoTaskMemUTF8</c>
///         allocation the error path must NOT make and Kotlin must NOT free.</item>
///   <item><see cref="Admit"/>: a bound-class HANDLE return, whose Kotlin stub wraps the pointer
///         in <c>requireNotNull(...)</c>; a stub that checks the handle before the error slot
///         reports the wrong exception entirely.</item>
///   <item><see cref="Occupancy"/>: a property GETTER, a different emission site from a method.</item>
///   <item><see cref="WardSign"/>: a property SETTER, the one seam where a missed error check is
///         SILENT rather than visibly wrong (a setter has no result to guard).</item>
///   <item><see cref="Examine"/>: a STRUCT return via out-pointers (ADR-056/058 Shape A,
///         <see cref="Profile"/>), the specific shape ADR-104 names as unverified: the thunk
///         returns with four out-pointers unwritten, one of them the string component, and Kotlin
///         must never read them.</item>
/// </list>
/// A deliberately mixed vocabulary: <see cref="Temperature"/> needs no marshalling while
/// <see cref="Chart"/> and <see cref="Examine"/> do, so a channel that only ever handles the easy
/// scalar cannot pass this fixture.
///
/// <see cref="ChartFor"/> and <see cref="ExamineCalm"/> are the NON-THROWING siblings (the string
/// and struct shapes respectively): the error channel must not tax the happy path, and calling
/// one on the SAME instance right after a throw proves the receiver handle survived.
/// </summary>
public class Infirmary
{
    /// <summary>Throwing <c>void</c> return shape.</summary>
    public void Discharge(string patient) =>
        throw new InvalidOperationException($"{patient} is not cleared for discharge");

    /// <summary>Throwing pass-through scalar return shape (no marshalling conversion at all).</summary>
    public int Temperature(string patient) =>
        throw new OverflowException($"{patient} ran too hot for the thermometer");

    /// <summary>
    /// Throwing string return shape: the success path allocates with
    /// <c>Marshal.StringToCoTaskMemUTF8</c>, so the error path must return <c>IntPtr.Zero</c>
    /// without allocating, and the Kotlin stub must not try to free it.
    /// </summary>
    public string Chart(string patient) =>
        throw new FormatException($"{patient} wrote his chart in claw marks");

    /// <summary>
    /// Throwing bound-class HANDLE return shape. The generated Kotlin stub reads this as
    /// <c>requireNotNull(ptr) { "... annotates it non-null." }</c>, so a stub that inspects the
    /// returned handle before the error slot surfaces an <c>IllegalStateException</c> about a null
    /// handle instead of the real managed exception.
    /// </summary>
    public Patient Admit(string name) =>
        throw new NotSupportedException($"the ward is full, {name} has to wait");

    /// <summary>Throwing property GETTER (a distinct thunk emission site from a method).</summary>
    public int Occupancy => throw new TimeoutException("the ward count is still being taken");

    /// <summary>
    /// Throwing property SETTER. Fork A puts setters in the channel's scope, and this is the one
    /// seam where a missed error check is SILENT: a setter has no result to guard, so a Kotlin
    /// call site that forgets to read the error slot swallows the managed exception whole and the
    /// caller sees a write that appears to have succeeded. Every other shape here at least fails
    /// visibly. Its own thunk on the C# side and its own call site on the Kotlin side, both
    /// distinct emitters from <see cref="Discharge"/>'s despite sharing the <c>void</c> return.
    ///
    /// The getter exists only because a settable property needs one to stay inside the bridgeable
    /// subset; it never throws and nothing calls it.
    /// </summary>
    public string WardSign
    {
        get => _wardSign;
        set => throw new UnauthorizedAccessException($"only the vet renames the ward, not {value}");
    }

    private readonly string _wardSign = "Oreo and Mylo ward";

    /// <summary>
    /// Throwing STRUCT return shape (ADR-056/058 Shape A). Four out-pointers stay unwritten on
    /// the error path, one of them <see cref="Profile.Tag"/>'s string pointer, which the Kotlin
    /// stub currently reads through <c>requireNotNull(outTag.value)</c>. Reading any of them after
    /// a throw is uninitialised <c>memScoped</c> memory.
    /// </summary>
    public Profile Examine(string patient) =>
        throw new ArgumentException($"{patient} is not a registered patient");

    /// <summary>
    /// NON-THROWING sibling of <see cref="Chart"/>: the string return shape on the happy path.
    /// </summary>
    public string ChartFor(string patient) => $"{patient} is purring, chart clean";

    /// <summary>
    /// NON-THROWING sibling of <see cref="Examine"/>: the struct out-pointer return shape on the
    /// happy path, with every converted component (string, bool, char, enum) actually written.
    /// </summary>
    public Profile ExamineCalm(string patient) => new Profile(patient, true, 'A', CatMood.Calm);
}

/// <summary>
/// ADR-104 fixture: the throwing CONSTRUCTOR return shape, on its own type because a bound type
/// gets exactly one constructor under ADR-043 (a second one would be an overload set, and
/// <see cref="Infirmary"/> has to stay constructible for every other member here).
///
/// The generated Kotlin constructor helper ends in
/// <c>requireNotNull(ptr) { "... a C# constructor never returns null." }</c>, so this is the
/// return shape where a stub that checks the handle before the error slot is most visibly wrong.
/// </summary>
public class Quarantine
{
    public Quarantine(string reason) =>
        throw new ArgumentException($"no free quarantine pen: {reason}");

    /// <summary>Unreachable: no <see cref="Quarantine"/> is ever constructed.</summary>
    public string Reason => "unreachable";
}

using Test.Enums;
using Test.Structs;

namespace Test.Roster;

/// <summary>
/// ADR-155 fixture: a bound interface with nothing in it but one string getter, so
/// <see cref="Roster.Pick(List{int}, ILabelled)"/> has a second parameter that is a DIFFERENT
/// Kotlin type from <see cref="Tag"/> while still accepting a <see cref="Tag"/> in C#. That is
/// what keeps the overload pair from collapsing to one Kotlin signature (finding 18) while
/// leaving both overloads applicable to the same argument list in C# (finding 19).
/// </summary>
public interface ILabelled
{
    /// <summary>What the tag reads, e.g. "chip".</summary>
    string Label { get; }
}

/// <summary>
/// ADR-155 fixture: the HANDLE element. One of these is minted per <see cref="Roster.Tags"/>
/// call and remembered weakly by the roster, so the ADR-121 collectability shape can be mirrored
/// for a collection: every element slot crosses as a fresh strong <c>GCHandle</c>, and if the
/// Kotlin wrapper built from that slot does not free it on <c>close()</c>, this object stays
/// rooted and <see cref="Roster.AliveTags"/> never reaches zero.
/// </summary>
public class Tag : ILabelled
{
    public Tag(string label) => Label = label;

    /// <inheritdoc />
    public string Label { get; }
}

/// <summary>
/// ADR-155 fixture (a BCL collection crossing C# → Kotlin as an eagerly copied Kotlin
/// collection). One member per MECHANISM, not per type: a fixture built only from
/// <c>IReadOnlyList&lt;int&gt;</c> needs no per-element conversion anywhere and would go green
/// while the string, handle, enum, nullable, map, set, parameter and throw paths were all still
/// wrong.
///
/// <list type="bullet">
///   <item><see cref="Ages"/>: <c>int</c> elements, the ONE element kind whose slot needs no
///         conversion at all. The control, not the feature. Backed by an <c>int[]</c> on purpose,
///         so the shim's <c>ToArray()</c> write path sees a source that is already an array.</item>
///   <item><see cref="Names"/>: <c>string</c> elements, each slot a
///         <c>StringToCoTaskMemUTF8</c> pointer Kotlin reads and frees per element.</item>
///   <item><see cref="Nicknames"/>: a NULLABLE element (<c>[1, 2]</c> pre-order, collection node
///         first), where the null element is the <c>0</c> slot. A reader that resolves
///         nullability after unwrapping to the element binds this non-null and the null slot
///         then reads as an empty string or crashes.</item>
///   <item><see cref="MaybeNames"/>: a NULLABLE COLLECTION (<c>[2, 1]</c>, the other order), which
///         crosses as <c>IntPtr.Zero</c>. Distinct from a null element and from an empty list.</item>
///   <item><see cref="Scores"/>: a MAP, whose buffer is interleaved <c>[count][k][v]...</c>, with
///         a key that needs a string conversion and a <c>double</c> value that is BIT-CAST into
///         its slot rather than widened.</item>
///   <item><see cref="Labels"/>: bound-INTERFACE elements, the handle slot kind whose Kotlin value
///         is the interface handle wrapper rather than the concrete class.</item>
///   <item><see cref="Moods"/>: a SET, whose element is a bound ENUM declared in ANOTHER bound
///         namespace (<see cref="Test.Enums"/>) and used nowhere else on this type. The element
///         type of a collection is reachable only by recursing into the type arguments, so an
///         import collector that does not is a compile error in the generated Kotlin here and
///         nowhere else (finding 12).</item>
///   <item><see cref="Tags"/>: HANDLE elements, and the one member whose C# declared type is
///         MUTABLE (<c>IList&lt;T&gt;</c>). It must still bind as a read-only Kotlin
///         <c>List&lt;Tag&gt;</c> (Q2, decided against ADR-011's mirror): the Kotlin value is a
///         copy, so a <c>MutableList</c> would let <c>tags.add(...)</c> compile and change
///         nothing in C#.</item>
///   <item><see cref="Rankings"/>: a collection PROPERTY, get AND set, so the same conversion has
///         to work off the property paths rather than a method's.</item>
///   <item><see cref="Roster(IEnumerable{string})"/>: a collection CONSTRUCTOR parameter, the
///         other position ADR-155 infers (rather than verifies) rides the shared conversions.</item>
///   <item><see cref="Enroll"/>: an <c>IEnumerable&lt;T&gt;</c> PARAMETER, built in Kotlin's
///         <c>memScoped</c> and materialized by the thunk before the member runs.</item>
///   <item><see cref="Roll"/> and <see cref="Weigh"/>: the SET and MAP parameter directions, which
///         a list parameter does not reach: the thunk rebuilds a <c>HashSet</c> / a
///         <c>Dictionary</c>, and the map's Kotlin-side write is the interleaved one.</item>
///   <item><see cref="Pick(IList{int}, Tag)"/> / <see cref="Pick(List{int}, ILabelled)"/>: the
///         overload pair that pins the shim's cast to the DECLARED type. The shim builds a
///         <c>List&lt;int&gt;</c> either way; uncast, this call is <c>CS0121</c> ambiguous
///         (finding 19), and for a pair the compiler CAN resolve it silently dispatches to the
///         <c>List&lt;int&gt;</c> overload (finding 16). The two bodies answer differently, so a
///         wrong dispatch is a wrong string here rather than an invisible success.</item>
///   <item><see cref="Broken"/>: a LAZY <c>IEnumerable&lt;T&gt;</c> that throws mid-enumeration.
///         User code runs inside the shim's <c>ToArray()</c>, before the buffer exists, so the
///         throw has to reach the ADR-104 <c>errOut</c> with nothing allocated and nothing to
///         free.</item>
///   <item><see cref="AliveTags"/>: the collectability probe, the mirror of ADR-121 for elements.
///         Reverse handles are uncounted by <c>NugetMarshal.LiveHandles</c>, so a leaked element
///         slot is invisible to the ADR-120 counter; a leaked slot is a strong root, so it is
///         visible here as a count that never reaches zero.</item>
///   <item><see cref="AddRange(IEnumerable{string})"/> beside
///         <see cref="AddRange(List{string})"/>: a C# overload pair that collapses to ONE Kotlin
///         signature, because both parameter types render the same read-only Kotlin type. The
///         whole set is skipped with <c>skipped_overload_set</c>, never all but one, and the rest
///         of this type keeps binding around it.</item>
///   <item><see cref="Kennels"/> and <see cref="Beds"/>: deliberately OUT of v1. They must be
///         SKIPPED with a named diagnostic (<c>skipped_array</c>, <c>skipped_collection_element</c>)
///         rather than bound or, as today for the array, vanishing with no diagnostic at all.</item>
/// </list>
///
/// Oreo and Mylo are on the roster, as they are on everything else in this repository.
/// </summary>
public class Roster
{
    // Weak, never strong: this list must not be what keeps a Tag alive. Static because the probe
    // has to outlive the Roster the tags were issued from, and because the LeakTests process is
    // the only one that reads it.
    private static readonly List<WeakReference> Issued = new();

    /// <summary>The roster nobody named: <see cref="Rankings"/> starts empty.</summary>
    public Roster() => Rankings = new List<string>();

    /// <summary>
    /// A collection CONSTRUCTOR parameter. The names are readable afterwards through
    /// <see cref="Rankings"/>, so a constructor that received an empty or garbled buffer is a
    /// wrong string rather than a silent success.
    /// </summary>
    public Roster(IEnumerable<string> names) => Rankings = names.ToList();

    /// <summary>A collection PROPERTY, readable and writable.</summary>
    public IReadOnlyList<string> Rankings { get; set; }

    /// <summary><c>int</c> elements: the one slot kind that needs no conversion.</summary>
    public IReadOnlyList<int> Ages() => new[] { 9, 7 };

    /// <summary><c>string</c> elements: one CoTaskMem allocation per slot, freed by Kotlin.</summary>
    public IReadOnlyList<string> Names() => new List<string> { "Oreo", "Mylo" };

    /// <summary>A NULLABLE element, present and absent in one collection.</summary>
    public IReadOnlyList<string?> Nicknames() => new[] { "O", null };

    /// <summary>A NULLABLE COLLECTION: <c>IntPtr.Zero</c>, not an empty buffer.</summary>
    public IReadOnlyList<string>? MaybeNames() => null;

    /// <summary>
    /// A MAP: interleaved key and value slots, with a key that needs a string conversion and a
    /// <c>double</c> value that crosses BIT-CAST into its 8-byte slot
    /// (<c>BitConverter.DoubleToInt64Bits</c> / <c>Double.fromBits</c>). Non-integral on purpose:
    /// a slot that widened rather than bit-cast reads 9 here, and 9 is also what a correct
    /// integral value looks like.
    /// </summary>
    public IReadOnlyDictionary<string, double> Scores() => new Dictionary<string, double>
    {
        ["Oreo"] = 9.5,
        ["Mylo"] = 7.25,
    };

    /// <summary>A SET of a bound ENUM from another namespace: ordinal slots, and an import.</summary>
    public IReadOnlySet<CatMood> Moods() => new HashSet<CatMood> { CatMood.Playful, CatMood.Hungry };

    /// <summary>
    /// HANDLE elements, from a MUTABLE declared type that must still bind read-only in Kotlin.
    /// Every tag issued is remembered weakly for <see cref="AliveTags"/>.
    /// </summary>
    public IList<Tag> Tags()
    {
        Tag tag = new("chip");
        Issued.Add(new WeakReference(tag));
        return new List<Tag> { tag };
    }

    /// <summary>
    /// BOUND-INTERFACE elements: the other handle-shaped slot kind, whose Kotlin value is the
    /// interface's own handle wrapper dispatching through its slot table, not the concrete
    /// <see cref="Tag"/>. Deliberately NOT recorded in the weak list: the collectability probe is
    /// about <see cref="Tags"/> only, and a second issuing member would muddy its count.
    /// </summary>
    public IReadOnlyList<ILabelled> Labels() => new List<ILabelled> { new Tag("chip"), new Tag("bell") };

    /// <summary>An <c>IEnumerable&lt;T&gt;</c> PARAMETER, eagerly materialized by the thunk.</summary>
    public int Enroll(IEnumerable<string> names) => names.Count();

    /// <summary>
    /// A SET PARAMETER: Kotlin writes the slot buffer, the thunk reads it back into a
    /// <c>HashSet&lt;string&gt;</c>. Sorted on the way out, because a set has no order to promise,
    /// and joined rather than counted, so a buffer whose slots were written but never read is a
    /// different answer from one that arrived.
    /// </summary>
    public string Roll(IReadOnlySet<string> names) => string.Join(",", names.OrderBy(n => n, StringComparer.Ordinal));

    /// <summary>
    /// A MAP PARAMETER whose value needs the BIT-CAST slot: Kotlin writes the interleaved
    /// <c>[count][k][v]...</c> buffer, the thunk rebuilds a <c>Dictionary&lt;string, double&gt;</c>.
    /// Summed, so a map whose keys and values were interleaved in the wrong order reads as a
    /// wrong number rather than as a missing key.
    /// </summary>
    public double Weigh(IReadOnlyDictionary<string, double> weights) => weights.Values.Sum();

    /// <summary>
    /// The <c>IList&lt;int&gt;</c> half of the overload pair. Reached only if the shim casts the
    /// <c>List&lt;int&gt;</c> it built to <c>IList&lt;int&gt;</c>.
    /// </summary>
    public string Pick(IList<int> xs, Tag tag) => "IList";

    /// <summary>The <c>List&lt;int&gt;</c> half, reached through the <see cref="ILabelled"/> arm.</summary>
    public string Pick(List<int> xs, ILabelled labelled) => "List";

    /// <summary>
    /// A LAZY sequence that throws after one element. The shim's <c>ToArray()</c> is where user
    /// code runs, before any buffer or string exists, so this throw must arrive in Kotlin as an
    /// ordinary managed exception with nothing leaked behind it.
    /// </summary>
    public IEnumerable<string> Broken()
    {
        yield return "one";
        throw new InvalidOperationException("boom");
    }

    /// <summary>
    /// How many issued <see cref="Tag"/>s are still reachable. Collects first, so a caller polls
    /// this rather than reasoning about GC timing. Zero means every element slot handed to Kotlin
    /// was released.
    /// </summary>
    public static int AliveTags()
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        return Issued.Count(w => w.IsAlive);
    }

    /// <summary>
    /// OUT of v1, as a PAIR: both overloads project to <c>fun addRange(names: List&lt;String&gt;)</c>
    /// in Kotlin, because every list-like C# definition renders the same read-only Kotlin type
    /// (Q1/Q2). That is a hard generation failure today (<c>validateKotlinSignatures</c>,
    /// finding 18), so the whole set must be dropped with <c>skipped_overload_set</c>: never all
    /// but one, per ADR-072 Decision 5. This is ordinary C# (<c>List&lt;T&gt;.AddRange</c> is
    /// exactly this shape), so it is reachable the moment collections bind at all, and the rest of
    /// <see cref="Roster"/> must keep binding around it.
    /// </summary>
    public void AddRange(IEnumerable<string> names) => Rankings = Rankings.Concat(names).ToList();

    /// <inheritdoc cref="AddRange(IEnumerable{string})"/>
    public void AddRange(List<string> names) => Rankings = Rankings.Concat(names).ToList();

    /// <summary>
    /// OUT of v1: an ARRAY. Must be skipped with the new <c>skipped_array</c> diagnostic. Today
    /// it is skipped with NO diagnostic at all, which is the part of this row that is a fix.
    /// </summary>
    public string[] Kennels() => new[] { "front", "back" };

    /// <summary>
    /// OUT of v1: a STRUCT element (ADR-056 Scope, multi-slot elements). Must be skipped with the
    /// new <c>skipped_collection_element</c> diagnostic naming the element, not with
    /// <c>skipped_unbound_generic_instantiation</c> blaming the BCL definition.
    /// </summary>
    public IReadOnlyList<Point> Beds() => new List<Point> { new(1, 2) };
}

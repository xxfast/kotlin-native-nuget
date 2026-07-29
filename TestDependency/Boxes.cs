using Test.Enums;      // CatMood: cross-namespace enum type argument
using Test.Menagerie;  // Ferret: cross-namespace bound HANDLE type argument (optional seam)

namespace Test.Boxes;

/// <summary>
/// ADR-072 fixture: the generic type under test. A T-typed property (the marshalling seam), a
/// T-FREE method that still needs a per-instantiation thunk (CS8895 forces every member through
/// the witness, even <see cref="Describe"/>), a self-instantiating method reached only through
/// Decision 2 phase B substitution, and a bare-type-parameter nullable return that cannot be
/// represented per instantiation.
/// </summary>
public class Box<T>
{
    public Box(T value) { Value = value; }
    public T Value { get; }                        // T at a property position: the marshalling seam
    public string Describe() => $"box[{Value}]";    // T-FREE member: still needs a per-instantiation thunk
    public Box<T> Rewrap() => new(Value);            // instantiation reached by substitution (Decision 2 phase B)
    public T? Peek() => Value;                       // -> skipped_nullable_type_parameter
}

/// <summary>
/// Arity 2 with distinct parameter names (TKey/TValue reach Kotlin verbatim). Deliberately NOT
/// named <c>Pair</c>: <c>kotlin.Pair</c> is a default import.
/// </summary>
public class Pairing<TKey, TValue>
{
    public Pairing(TKey key, TValue value) { Key = key; Value = value; }
    public TKey Key { get; }
    public TValue Value { get; }
}

/// <summary>
/// <c>ReferenceTypeConstraint</c>, which v1 reads and deliberately does not emit (Decision 8).
/// </summary>
public class Crate<T> where T : class
{
    public Crate(T item) { Item = item; }
    public T Item { get; }
}

/// <summary>
/// Never instantiated anywhere. MUST produce no Kotlin type, no export, no C# class, and exactly
/// one <c>info_uninstantiated_generic_type</c>. This is the regression test for the <c>Box`1</c>
/// leak (ADR-072 Context item 2 / Decision 10).
/// </summary>
public class Unused<T>
{
    public Unused(T value) { Value = value; }
    public T Value { get; }
}

public static class Boxes
{
    public static Box<int> OfNumber(int value) => new(value);            // no conversion needed
    public static Box<string> OfText(string value) => new(value);        // UTF8 conversion needed
    public static Box<string?> OfMaybeText(string? value) => new(value); // Decision 7: blob [1,2]
    public static Box<CatMood> OfMood(CatMood mood) => new(mood);        // enum arg, cross-namespace import
    public static Box<Ferret> OfFerret(Ferret ferret) => new(ferret);    // bound handle arg across a package boundary
    public static int Unwrap(Box<int> box) => box.Value;                 // instantiation at a PARAMETER
    public static Pairing<string, int> Tally(string label, int count) => new(label, count);
    public static Crate<string> CrateOfText(string item) => new(item);

    // Each of the next four must be SKIPPED, each with its own named diagnostic, not silently:
    public static List<int> Numbers() => new() { 1, 2, 3 };              // skipped_unbound_generic_instantiation
    public static Dictionary<string, int> Counts() => new();             // ditto, arity 2
    public static Box<Box<int>> Nested() => new(new Box<int>(1));        // skipped_generic_type_argument
    public static T Identity<T>(T value) => value;                       // skipped_open_generic (ADR-043 survives)
}

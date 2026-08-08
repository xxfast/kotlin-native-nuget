namespace Test.Menagerie;

/// <summary>
/// ADR-070 fixture: a C#-declared interface that must surface in Kotlin as a Kotlin
/// <c>interface</c>, with a handle-backed implementation class dispatching through its own
/// per-interface slot table (the "Invoker" shape). Deliberately mixes three seams in one
/// interface rather than three single-purpose interfaces:
///   - <see cref="Describe"/> — a string RETURN that needs UTF8 marshalling;
///   - <see cref="Legs"/> — an int property that needs NO marshalling (pass-through);
///   - <see cref="Feed"/> — a string PARAMETER that needs marshalling, void return;
///   - <see cref="Nickname"/> — a nullable, SETTABLE string property (ADR-053 nullability x a
///     getter AND a setter slot). A generator that open-codes any one conversion instead of
///     reusing the shared marshalling path must not pass this fixture anyway.
/// </summary>
public interface IFeedable
{
    string Describe();
    int Legs { get; }
    void Feed(string food);
    string? Nickname { get; set; }
}

/// <summary>
/// Interface inheritance (Decision 5): <see cref="Flagship"/> below returns this derived
/// interface, so the reader must resolve <see cref="Tag"/> (declared here) alongside
/// <see cref="IFeedable"/>'s own members when it binds <c>ITagged</c>.
/// </summary>
public interface ITagged : IFeedable
{
    string Tag { get; }
}

/// <summary>
/// Every member is a public, identically-signed implementation of <see cref="IFeedable"/>, so
/// per Decision 5 the bound Kotlin class declares the <c>IFeedable</c> supertype.
/// </summary>
public class Ferret : IFeedable
{
    private readonly List<string> _meals = new();

    public string Describe() => "a ferret";

    public int Legs => 4;

    public void Feed(string food) => _meals.Add(food);

    public string? Nickname { get; set; }

    /// <summary>How many times <see cref="Feed"/> has been called. Not part of any interface —
    /// exercises a plain bound member coexisting with interface-satisfying ones.</summary>
    public int MealCount => _meals.Count;
}

/// <summary>
/// Implements every <see cref="IFeedable"/> member EXPLICITLY, so per Decision 5 the bound
/// Kotlin class must skip the <c>IFeedable</c> supertype and emit
/// <c>SKIPPED_INTERFACE_SUPERTYPE</c>: explicit implementation is non-public in metadata, so the
/// reader emits none of these members on the class itself.
/// </summary>
public class Shy : IFeedable
{
    private string? _nickname;

    string IFeedable.Describe() => "something shy, hiding";

    int IFeedable.Legs => 2;

    void IFeedable.Feed(string food) { /* eats when nobody is looking */ }

    string? IFeedable.Nickname
    {
        get => _nickname;
        set => _nickname = value;
    }
}

/// <summary>
/// Implements <see cref="ITagged"/> publicly and identically, so the derived-interface return
/// (<see cref="Sanctuary.Flagship"/>) has a real runtime type to construct, even though Decision
/// 3 means Kotlin only ever sees it as <c>ITaggedHandle</c>, never as <c>TaggedFerret</c>.
/// </summary>
public class TaggedFerret : ITagged
{
    public string Describe() => "the flagship ferret";

    public int Legs => 4;

    public void Feed(string food) { }

    public string? Nickname { get; set; } = "Flagship";

    public string Tag => "flagship";
}

/// <summary>
/// Never bound (internal): <see cref="Sanctuary.HiddenResident"/> returns one of these purely as
/// <see cref="IFeedable"/>. Proves the verified mechanism — interface dispatch through a
/// handle needs no bound, public, or even named runtime type on the Kotlin side.
/// </summary>
internal class Nocturnal : IFeedable
{
    public string Describe() => "something nocturnal, unseen";

    public int Legs => 4;

    public void Feed(string food) { }

    public string? Nickname { get; set; }
}

/// <summary>
/// Every interface-typed seam in one class: return, parameter, nullable settable property, and
/// a derived-interface return.
/// </summary>
public class Sanctuary
{
    private IFeedable? _featured;

    /// <summary>Interface-typed RETURN of a publicly bound implementation.</summary>
    public IFeedable Star() => new Ferret { Nickname = "Bandit" };

    /// <summary>Interface-typed RETURN whose runtime type is unbound and internal.</summary>
    public IFeedable HiddenResident() => new Nocturnal();

    /// <summary>Interface-typed PARAMETER.</summary>
    public string Introduce(IFeedable feedable) =>
        $"introduced {feedable.Describe()} with {feedable.Legs} legs";

    /// <summary>Interface-typed PROPERTY, nullable and settable.</summary>
    public IFeedable? Featured
    {
        get => _featured;
        set => _featured = value;
    }

    /// <summary>
    /// ADR-085: C# calling the string PARAMETER member (<see cref="IFeedable.Feed"/>) on an
    /// interface-typed argument whose runtime type may be a Kotlin-implemented bridge. Exercises
    /// the void-returning, arity-1 string-input slot from the C# side of the crossing.
    /// </summary>
    public void FeedAnimal(IFeedable feedable, string food) => feedable.Feed(food);

    /// <summary>
    /// ADR-085: C# writing then reading <see cref="IFeedable.Nickname"/> on an interface-typed
    /// argument, exercising both the setter AND getter slots (not just the getter, as
    /// <see cref="Introduce"/> does) on a possibly Kotlin-implemented bridge. <paramref
    /// name="nickname"/> may be null, so the null-vs-empty-string distinction rides the same
    /// nullable-string slot as <see cref="IFeedable.Nickname"/> itself.
    /// </summary>
    public string? Rename(IFeedable feedable, string? nickname)
    {
        feedable.Nickname = nickname;
        return feedable.Nickname;
    }

    /// <summary>Derived-interface RETURN (Decision 5's interface-inheritance case).</summary>
    public ITagged Flagship() => new TaggedFerret();
}

// Negative cases, one per Decision 6 diagnostic. None of these is implemented by any class in
// this fixture: each exists purely to prove the reader skips it cleanly, with a named diagnostic,
// rather than emitting broken source.

/// <summary>Open generic interface -&gt; SKIPPED_GENERIC_INTERFACE: the arity-mangled CLR name
/// (<c>IBox`1</c>) is not valid Kotlin.</summary>
public interface IBox<T>
{
    T Get();
}

/// <summary>Static abstract interface member -&gt; SKIPPED_INTERFACE_STATIC_MEMBER: calling it
/// through the interface itself is CS8926.</summary>
public interface IWithStatics
{
    static abstract int Rank();
}

/// <summary>Default interface method -&gt; skipped_default_interface_method (pre-existing
/// diagnostic, unchanged by this ADR).</summary>
public interface IWithDim
{
    string Greeting() => "hi";
}

/// <summary>Indexer -&gt; SKIPPED_INDEXER: reaches the reader as a parameterless property
/// literally named <c>Item</c>, which would emit an uncompilable <c>receiver.Item</c>.</summary>
public interface IIndexedThing
{
    string this[int i] { get; }
}

/// <summary>Zero admissible members -&gt; SKIPPED_EMPTY_INTERFACE: nothing to generate, must not
/// emit an empty registration export.</summary>
public interface IEmpty
{
}

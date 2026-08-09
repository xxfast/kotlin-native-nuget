using Test.Wellness;

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
/// ADR-085 follow-up fixture (multi-interface dispatch bug): a second admissible bound
/// interface, deliberately INDEPENDENT of <see cref="IFeedable"/> — it does not derive from it
/// (a derived interface, like <see cref="ITagged"/>, is a named skip today, not a candidate for
/// this bug). A Kotlin class implementing both this and <see cref="IFeedable"/> exercises
/// `nugetMintBridge`'s interface dispatch at two distinct crossing positions.
/// </summary>
public interface IPerformer
{
    string Perform();

    /// <summary>
    /// ADR-085 follow-up fixture (cross-package enum slot import): an enum-typed, SETTABLE
    /// property whose type (<see cref="EnergyLevel"/>) is declared in a namespace other than this
    /// interface's own (<see cref="Test.Wellness"/> vs <see cref="Test.Menagerie"/>). The setter
    /// is the INBOUND crossing the bug hits; the getter alone would not (enum returns don't hit
    /// it).
    /// </summary>
    EnergyLevel Energy { get; set; }
}

/// <summary>
/// ADR-086 fixture (Phase 13 Wave 2, item 2): object- and interface-typed slots on a
/// Kotlin-implemented bound interface. Reuses <see cref="Ferret"/> and <see cref="IFeedable"/>
/// per the ADR's guidance ("adapt the consumer sample to the real Menagerie types") rather than
/// inventing parallel bound types.
/// </summary>
public interface IKeeper
{
    /// <summary>Nullable bound-object PROPERTY slot: getter AND setter.</summary>
    Ferret? Favorite { get; set; }

    /// <summary>
    /// Bound-object PARAMETER (ownership transfers to Kotlin on this crossing, so the
    /// implementation may safely STORE <paramref name="pet"/>) and RETURN (a fresh transfer
    /// handle; C#-side identity is promised for a C#-originated object per ADR-086's identity
    /// table, so a caller handed back the same reference it passed in).
    /// </summary>
    Ferret Groom(Ferret pet);

    /// <summary>
    /// Bound-INTERFACE PARAMETER and RETURN, round-tripping through the identity mechanism
    /// ADR-086 specifies per origin: the token probe for a Kotlin-implemented
    /// <paramref name="other"/>, plain <c>GCHandle.Target</c> resolution for a C#-implemented one.
    /// </summary>
    IFeedable Pair(IFeedable other);
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
    private readonly List<IFeedable> _remembered = new();

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

    /// <summary>
    /// ADR-086 fixture (Phase 13 Wave 2, item 1): derived-interface FLATTENING. Takes an
    /// <see cref="ITagged"/>-typed PARAMETER and calls BOTH its own member (<see cref="ITagged.Tag"/>)
    /// and a member it inherits from <see cref="IFeedable"/> (<see cref="IFeedable.Legs"/>), so a
    /// Kotlin-implemented <c>ITagged</c> must satisfy both slot tables through one flattened
    /// factory rather than the base interface's factory alone.
    /// </summary>
    public string Showcase(ITagged tagged) => $"{tagged.Tag}: {tagged.Legs} legs";

    /// <summary>
    /// ADR-086/087 fixture: calls ONLY <see cref="IFeedable.Legs"/>, never
    /// <see cref="IFeedable.Describe"/>, so a Kotlin implementation whose <c>Describe()</c> throws
    /// cannot block exercising a non-throwing sibling slot on the same interface.
    /// </summary>
    public int LegsOnly(IFeedable feedable) => feedable.Legs;

    /// <summary>
    /// ADR-086 fixture (Phase 13 Wave 2, item 2): drives every <see cref="IKeeper"/> crossing from
    /// the C# side of a Kotlin-implemented object/interface slot. <paramref name="pet"/> is a
    /// C#-created <see cref="Ferret"/>, so the bound-object param+return identity check
    /// (<see cref="IKeeper.Groom"/>) is a plain <see cref="object.ReferenceEquals(object?, object?)"/>
    /// right here, where C# still holds the original reference — the Kotlin side of that same
    /// crossing only ever sees a fresh wrapper per crossing (ADR-086's identity table promises
    /// Kotlin-side identity is NOT guaranteed there, only the C#-side return is). Likewise
    /// <paramref name="partner"/>'s round trip through <see cref="IKeeper.Pair"/> resolves per
    /// ADR-086's identity table by origin: true here (C#-side <c>ReferenceEquals</c>) when
    /// <paramref name="partner"/> is a real C# object, not promised when it is a bridge for a
    /// Kotlin-implemented one (a fresh bridge mints per return crossing there instead).
    /// </summary>
    public string KeeperRoundTrip(IKeeper keeper, Ferret pet, IFeedable partner)
    {
        bool favoriteStartsNull = keeper.Favorite is null;

        Ferret groomed = keeper.Groom(pet);
        bool groomedIsSamePet = ReferenceEquals(groomed, pet);

        keeper.Favorite = pet;
        bool favoriteIsSamePet = ReferenceEquals(keeper.Favorite, pet);

        IFeedable paired = keeper.Pair(partner);
        bool pairedIsSamePartner = ReferenceEquals(paired, partner);

        return $"{favoriteStartsNull}|{groomedIsSamePet}|{favoriteIsSamePet}|{pairedIsSamePartner}|{paired.Describe()}";
    }

    /// <summary>
    /// ADR-085 follow-up fixture: interface-typed PARAMETER through the SECOND admissible
    /// interface, <see cref="IPerformer"/>, independent of <see cref="Introduce"/>'s
    /// <see cref="IFeedable"/> position. A dual-interface Kotlin object crossing here must
    /// dispatch into its <see cref="IPerformer"/> slot table, not its <see cref="IFeedable"/>
    /// one.
    /// </summary>
    public string Applaud(IPerformer performer) => performer.Perform();

    /// <summary>
    /// ADR-085 follow-up fixture: writes then reads <see cref="IPerformer.Energy"/> (enum getter
    /// AND setter) on an interface-typed argument, exercising the cross-package enum slot
    /// (<see cref="EnergyLevel"/> lives in <see cref="Test.Wellness"/>, not
    /// <see cref="Test.Menagerie"/>) on a possibly Kotlin-implemented bridge.
    /// </summary>
    public EnergyLevel Recharge(IPerformer performer, EnergyLevel level)
    {
        performer.Energy = level;
        return performer.Energy;
    }

    /// <summary>
    /// Phase 13 Wave 3 fixture (bridge reuse per Kotlin object): HOLDS every
    /// <see cref="IFeedable"/> reference it receives, so an earlier crossing's bridge stays alive
    /// here across a REPEAT crossing of the same Kotlin object. Today's mint-per-crossing
    /// mechanism gives the two most recent entries distinct C#-side instances even when the
    /// underlying Kotlin object is identical; <see cref="RememberedAreSame"/> is the probe that
    /// observes it.
    /// </summary>
    public void Remember(IFeedable feedable) => _remembered.Add(feedable);

    /// <summary>
    /// Whether the two most recently <see cref="Remember"/>-ed references are
    /// <see cref="object.ReferenceEquals(object?, object?)"/>. Two crossings of the SAME live
    /// Kotlin object should report <c>true</c> once bridge reuse lands; two crossings of
    /// DIFFERENT Kotlin objects must report <c>false</c> regardless.
    /// </summary>
    public bool RememberedAreSame() =>
        _remembered.Count >= 2 && ReferenceEquals(_remembered[^1], _remembered[^2]);
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

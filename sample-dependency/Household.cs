namespace Sample.Household;

/// <summary>
/// A cat's plaything. Cross-referenced from <see cref="Cat.FavoriteToy"/> (a settable,
/// handle-typed property) and returned by <see cref="Household.FindToy"/>. Shares its
/// <see cref="Describe"/> method name with <see cref="Cat.Describe"/> — the same name on two
/// different bound types, deliberately not an overload set (each type declares it exactly once).
/// </summary>
public class Toy
{
    public Toy(string label) => Label = label;

    /// <summary>The toy's label, e.g. "feather wand".</summary>
    public string Label { get; }

    /// <summary>Describes this toy. Shares its name with <see cref="Cat.Describe"/>.</summary>
    public string Describe() => $"a toy called {Label}";
}

/// <summary>
/// A cat living in the <see cref="Household"/>. Second of the 3+ bound classes this namespace
/// deliberately combines — guards the ADR-053 bug hunt's registration-var collision, where two
/// bound classes in one namespace both emitted a top-level `internal var ctorFn`.
/// </summary>
public class Cat
{
    public Cat(string name, int age)
    {
        Name = name;
        Age = age;
    }

    /// <summary>The cat's name, e.g. "Oreo" or "Mylo".</summary>
    public string Name { get; }

    /// <summary>The cat's age in years.</summary>
    public int Age { get; }

    /// <summary>
    /// Settable, nullable handle-typed property: the cat's current favourite toy, if any.
    /// Cross-references <see cref="Toy"/> from the same namespace and guards the ADR-053
    /// "rule 4" fix — a handle-typed settable property is no longer collapsed to a read-only
    /// Kotlin <c>val</c>.
    /// </summary>
    public Toy? FavoriteToy { get; set; }

    /// <summary>Describes this cat. Shares its name with <see cref="Toy.Describe"/>.</summary>
    public string Describe() => $"{Name}, age {Age}";
}

/// <summary>
/// The household the cats and toys belong to. Third of the 3+ bound classes in this namespace.
/// <see cref="FindToy"/> exercises a many-parameter method with a nullable annotated return —
/// this guards the metadata reader's positional (not <c>SequenceNumber</c>-keyed) parameter
/// lookup, which used to shift every parameter after a <c>[return: Nullable]</c> attribute by
/// one slot.
/// </summary>
public class Household
{
    public Household(string name) => Name = name;

    /// <summary>The household's name.</summary>
    public string Name { get; }

    /// <summary>
    /// Finds a toy for the named cat, gated on several unrelated parameters that all sit ahead
    /// of the nullable annotated return in the metadata's parameter list. If the metadata
    /// reader's parameter lookup were still positional instead of <c>SequenceNumber</c>-keyed,
    /// one of <paramref name="minAge"/>/<paramref name="indoorOnly"/>/<paramref name="label"/>
    /// would silently bind under the wrong name.
    /// </summary>
    public Toy? FindToy(string catName, int minAge, bool indoorOnly, string label)
    {
        if (catName.Length > 0 && minAge >= 0 && indoorOnly)
            return new Toy(label);
        return null;
    }

    /// <summary>
    /// A plain nullable handle lookup. Deliberately never throws: reverse exception propagation
    /// from inside a C# thunk is Phase 11 (ADR-049 "let it crash" — a thrown C# exception
    /// escapes the <c>[UnmanagedCallersOnly]</c> thunk uncaught and fast-fails the host process
    /// today). The "nullable return that throws" half of this fixture lives entirely on the
    /// Kotlin-exported side instead (see <c>fetchFavoriteToyLabelOrThrow</c> in
    /// sample-library), which validates its own input before ever reaching this reverse call.
    /// </summary>
    public Toy? FetchFavorite(string catName) => catName == "Oreo" ? new Toy("feather wand") : null;
}

#nullable disable
/// <summary>
/// Oblivious island inside the household namespace: every reference type here is byte 0 (no
/// <see cref="System.Runtime.CompilerServices.NullableAttribute"/> anywhere), so it binds
/// non-null under ADR-053 decision 1a, with one <c>info_oblivious_nullability</c> diagnostic per
/// member. <see cref="Announce"/> deliberately returns a genuine C# <c>null</c> for an
/// unrecognised name — even though its oblivious signature reads <c>string</c>, not
/// <c>string?</c> — so the round trip exercises decision 1a's fail-fast guard, not just its
/// happy path.
/// </summary>
public class StrayCat
{
    public StrayCat(string name) => Name = name;

    public string Name { get; }

    /// <summary>
    /// Announces this stray only when its name matches <paramref name="recognisedName"/>;
    /// otherwise returns null, which the generated Kotlin stub's fail-fast guard must turn into
    /// an <c>IllegalStateException</c> naming the member, rather than corrupting memory or
    /// silently returning garbage.
    /// </summary>
    public string Announce(string recognisedName) =>
        Name == recognisedName ? $"a stray named {Name}" : null;
}
#nullable restore

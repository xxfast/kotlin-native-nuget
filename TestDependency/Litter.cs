using Test.Enums;

namespace Test.Structs;

/// <summary>
/// ADR-059: Shape A OUTER with a Shape A nested component (<see cref="Profile"/>: the CONVERTED
/// vocabulary, string/bool/char/enum) and a Shape B nested component (<see cref="Extent"/>:
/// DIRECT ints), plus a direct <c>int</c> and a converted enum at the outer level. This is
/// A-in-A and B-in-A in one type, and it is the type that fails if conversion is open-coded at
/// the wrong nesting level. Flattens to 8 leaves: Mother{Tag,Active,Grade,Mood},
/// Basket{Width,Height}, Count, Mood.
/// </summary>
public readonly struct Litter
{
    public Litter(Profile mother, Extent basket, int count, CatMood mood)
    {
        Mother = mother;
        Basket = basket;
        Count = count;
        Mood = mood;
    }

    public Profile Mother { get; }   // nested Shape A, converted components
    public Extent Basket { get; }    // nested Shape B, direct components
    public int Count { get; }
    public CatMood Mood { get; }

    /// <summary>
    /// Computed property on a NESTING struct: the receiver is reconstructed from 8 flattened
    /// leaves before the getter runs.
    /// </summary>
    public string Summary => $"{Mother.Tag}x{Count}@{Basket.Width}x{Basket.Height}/{Mood}";

    /// <summary>
    /// Instance method on a nesting struct: nested receiver in (8 leaves), nested struct out
    /// (8 out-pointers), plus an ordinary parameter. 8 + 1 + 8 = 17 args, under the ceiling.
    /// </summary>
    public Litter Grow(int by) =>
        new Litter(Mother, new Extent { Width = Basket.Width + by, Height = Basket.Height + by },
                   Count + by, Mood);

    /// <summary>Static factory on a nesting struct -> Kotlin companion object.</summary>
    public static Litter Single(string tag) =>
        new Litter(Profile.Resting(tag), Extent.Unit(), 1, CatMood.Sleepy);
}

/// <summary>
/// ADR-059: Shape B OUTER with struct components arriving through ALL THREE Shape B component
/// sources: a public FIELD, a settable auto-property, and an INIT-only auto-property. A-in-B and
/// B-in-B. Flattens to 5 + 2 + 2 + 1 = 10 leaves.
/// </summary>
public struct Nest
{
    public Collar Collar;                  // nested Shape B via a public FIELD          (B-in-B)
    public Point Centre { get; set; }      // nested Shape A via a settable auto-prop    (A-in-B)
    public Extent Bounds { get; init; }    // nested Shape B via an INIT-only auto-prop  (B-in-B)
    public bool Lined { get; set; }        // converted scalar at the outer level

    /// <summary>Computed property on a Shape B nesting struct (object-initializer receiver reconstruction).</summary>
    public string Tag => $"{Collar.Colour}/{Centre.X},{Centre.Y}/{Bounds.Area}/{Lined}";
}

public static class Litters
{
    /// <summary>Nested struct in, string out.</summary>
    public static string Describe(Litter l) => l.Summary;

    /// <summary>Nested struct in AND out (8 + 1 in, 8 out-pointers).</summary>
    public static Litter Grow(Litter l, int by) => l.Grow(by);

    /// <summary>
    /// TWO struct parameters with DIFFERENT nesting depths in one signature (8 + 2 = 10 leaves):
    /// where an abiArgs expansion bug shows up as a misaligned argument rather than a type error.
    /// </summary>
    public static string Compare(Litter a, Extent b) => $"{a.Basket.Area}vs{b.Area}";

    /// <summary>
    /// ADVERSARIAL, and the reason ADR-059 Decision 5 exists: 8 + 8 in, 8 out = 24 ABI arguments,
    /// over the verified 22-argument CFunction ceiling. Must be SKIPPED with
    /// <c>skipped_abi_arity_limit</c>, naming this method and the count. It must NOT appear in the
    /// generated Kotlin, and the rest of <see cref="Litters"/> must still bind.
    /// </summary>
    public static Litter Merge(Litter a, Litter b) =>
        new Litter(a.Mother, b.Basket, a.Count + b.Count, b.Mood);
}

/// <summary>
/// A bound handle CLASS (ADR-051) with a nested struct on both member kinds: a settable
/// struct-typed PROPERTY whose components nest (getter = out-pointers, setter = in-args), and an
/// instance method taking and returning a nesting struct.
/// </summary>
public class Shelter
{
    public Nest Current { get; set; } = new Nest
    {
        Collar = Collar.Plain("none"), Centre = new Point(0, 0),
        Bounds = Extent.Unit(), Lined = false,
    };

    public Litter Admit(Litter l) => l.Grow(1);
}

using Test.Enums;

namespace Test.Structs;

/// <summary>
/// Shape B, pure public fields, all-direct (<c>int</c>) components. The canonical ROADMAP shape,
/// and the type that used to be <c>UnsupportedStructs.Unsupported</c> (ADR-058: a struct with
/// public fields and no constructor becomes supported). Deliberately has NO properties, so the
/// public-field path is proven in isolation. Same component list as the Shape A <see cref="Size"/>
/// — two structs, identical components, different shapes.
/// </summary>
public struct Extent
{
    public int Width;
    public int Height;

    /// <summary>Computed property, not a component (components are <see cref="Width"/> / <see cref="Height"/>).</summary>
    public int Area => Width * Height;

    /// <summary>Instance method returning a Shape B struct: object-initializer reconstruct on the receiver.</summary>
    public Extent Grow(int by) => new Extent { Width = Width + by, Height = Height + by };

    /// <summary>Static factory on a Shape B struct -> Kotlin <c>companion object</c>.</summary>
    public static Extent Unit() => new Extent { Width = 1, Height = 1 };
}

/// <summary>
/// Shape B, MIXED component sources in one struct: a public field, a settable auto-property, and
/// two <c>init</c>-only auto-properties. Spans the full v1 vocabulary: <c>int</c> (direct),
/// <c>string</c>/<c>bool</c>/<c>char</c>/enum (converted). Component order is the C# declaration
/// order, recovered from the FieldDef table.
/// </summary>
public struct Collar
{
    public int Girth;                            // public field           -> int, direct
    public string Colour { get; set; }           // settable auto-prop     -> IntPtr / UTF-8
    public bool Belled { get; init; }             // INIT-only auto-prop    -> byte
    public char Initial { get; init; }            // INIT-only auto-prop    -> ushort
    public CatMood Mood { get; set; }             // settable auto-prop     -> int ordinal

    /// <summary>Computed string property (not a component).</summary>
    public string Label => $"{Colour}:{Girth}{(Belled ? "*" : "")}";

    /// <summary>Computed bool property (not a component).</summary>
    public bool IsLoud => Belled && Mood == CatMood.Playful;

    /// <summary>
    /// Instance method returning a Shape B struct: object-initializer reconstruct on the receiver,
    /// out-pointers on the way back, full vocabulary on both.
    /// </summary>
    public Collar Resize(int by) =>
        new Collar { Girth = Girth + by, Colour = Colour, Belled = Belled, Initial = Initial, Mood = Mood };

    /// <summary>Static factory on a Shape B struct -> Kotlin <c>companion object</c>.</summary>
    public static Collar Plain(string colour) =>
        new Collar { Girth = 1, Colour = colour, Belled = false, Initial = 'P', Mood = CatMood.Calm };
}

/// <summary>
/// Static methods taking/returning Shape B structs (the mirror of <see cref="Geometry"/> /
/// <see cref="Cattery2"/> for Shape A): struct param + non-struct return, struct param + struct
/// return, and TWO struct params of different sub-shapes in one signature.
/// </summary>
public static class Collars
{
    public static string Describe(Collar c) => $"{c.Colour} {c.Girth} {c.Initial} {c.Mood} {c.Belled}";

    public static Collar Loosen(Collar c) => c.Resize(1);

    /// <summary>
    /// Deliberately takes TWO Shape B structs of *different* shapes-of-Shape-B (one field-only,
    /// one mixed) in one signature, which is where an <c>abiArgs</c> expansion bug would show up.
    /// </summary>
    public static string Pair(Collar a, Extent b) => $"{a.Label}/{b.Width}x{b.Height}";
}

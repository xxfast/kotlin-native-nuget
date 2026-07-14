using Sample.Structs;

namespace Sample.Nested;

/// <summary>
/// ADR-059, DEPTH 2, ACROSS A PACKAGE BOUNDARY: a struct whose component is itself a nesting
/// struct (Nursery -> Litter -> Profile -> string), declared in a different namespace from the
/// struct it nests. Proves (a) the recursion is a real tree walk, not a special-cased single
/// level, and (b) both generators import/<c>using</c> a nested struct declared elsewhere.
/// Flattens to 9 leaves.
/// </summary>
public readonly struct Nursery
{
    public Nursery(Litter litter, int room) { Litter = litter; Room = room; }

    public Litter Litter { get; }
    public int Room { get; }
}

public static class Nurseries
{
    /// <summary>Depth-2 struct in AND out (9 leaves each way, 18 args, under the ceiling).</summary>
    public static Nursery Rehome(Nursery n) => new Nursery(n.Litter.Grow(1), n.Room + 1);

    /// <summary>Depth-2 struct in, string out: proves the deep read path (<c>n.Litter.Mother.Tag</c>).</summary>
    public static string Trace(Nursery n) => $"{n.Litter.Mother.Tag}@{n.Room}";
}

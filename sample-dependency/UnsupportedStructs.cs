namespace Sample.Structs;

/// <summary>
/// Adversarial: fails Decision 3a rule 2 (no public instance constructor — Shape B, public
/// fields with no constructor, is deferred). Must be skipped with a
/// <c>skipped_unsupported_struct</c> diagnostic and must NOT generate a Kotlin type or an
/// ADR-051 handle wrapper (the verified Constraint 3 bug this ADR fixes).
/// </summary>
public struct Unsupported
{
    public int A;
    public int B;
}

/// <summary>
/// Adversarial: fails Decision 3a rule 5. One constructor parameter, but TWO stored instance
/// fields — a hand-written extra private field (<see cref="_hidden"/>) the constructor never
/// derives from a parameter. If this struct were bound, its third field's value would be
/// silently dropped on every crossing; rule 5 exists to skip and diagnose it instead.
/// </summary>
public readonly struct Overstuffed
{
    private readonly int _hidden;

    public Overstuffed(int visible)
    {
        Visible = visible;
        _hidden = visible;
    }

    public int Visible { get; }
}

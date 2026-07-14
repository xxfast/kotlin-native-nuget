namespace Sample.Structs;

/// <summary>
/// Adversarial (ADR-056 rule 5 / ADR-058 Shape B rule 3): fails Shape A rule 5 as it always has
/// (one constructor parameter, two stored instance fields). Under ADR-058 it ALSO falls through
/// to Shape B and fails there too: its only public member is a get-only property (not settable),
/// so it has zero components and an uncovered private field (<see cref="_hidden"/>). Its
/// <c>skipped_unsupported_struct</c> diagnostic reason text changes under ADR-058 — it used to
/// name only the Shape A rule; a test asserting the exact reason string must be updated.
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

/// <summary>
/// Adversarial (ADR-058 Shape B rule 3): a settable auto-property (<see cref="Visible"/>) covers
/// its own backing field, but the hand-written private field <see cref="_hidden"/> is separate,
/// uncovered stored state — no component writes it. Must be skipped with a
/// <c>skipped_unsupported_struct</c> diagnostic naming the uncovered field.
/// </summary>
public struct PartlyHidden
{
    private int _hidden;
    public int Visible { get; set; }
}

/// <summary>
/// Adversarial (ADR-058 Shape B rule 3 / the <c>InitOnly</c> row): a public <c>readonly</c> field
/// cannot be assigned by an object initializer (verified: CS0191), so it can never be a Shape B
/// component. Must be skipped with a <c>skipped_unsupported_struct</c> diagnostic.
/// </summary>
public struct Frozen
{
    public readonly int A;
    public int B;
}

/// <summary>
/// Adversarial (ADR-058 Decision 2a's accepted cost): <see cref="A"/> is settable, but it is a
/// MANUAL (hand-written) property, not an auto-property — there is no <c>&lt;A&gt;k__BackingField</c>
/// carrying <c>[CompilerGenerated]</c>, so nothing in metadata proves that <see cref="A"/>'s setter
/// actually writes <see cref="_a"/>. This is the negative a count-based coverage implementation
/// (number of components == number of fields) would wrongly accept: 1 settable property, 1 field,
/// "coverage passes" — yet nothing proves it. Must be skipped with a
/// <c>skipped_unsupported_struct</c> diagnostic naming the non-auto-property rule.
/// </summary>
public struct Manual
{
    private int _a;

    public int A
    {
        get => _a;
        set => _a = value;
    }
}

/// <summary>
/// Adversarial (ADR-058 Shape B rule 5): zero stored state, zero components. Must be skipped with
/// a <c>skipped_unsupported_struct</c> diagnostic.
/// </summary>
public struct Nothing
{
}

/// <summary>
/// ADVERSARIAL (ADR-059 Decision 6c): an outer struct whose nested component is itself
/// unsupported (<see cref="Manual"/> is the existing manual-settable-property negative). The
/// WHOLE outer struct must be skipped, and the diagnostic must name the offending component PATH
/// and the inner reason, not one of Kennel's own shape rules. It must generate no Kotlin type and
/// no handle wrapper.
/// </summary>
public readonly struct Kennel
{
    public Kennel(Manual manual, int n) { Manual = manual; N = n; }
    public Manual Manual { get; }
    public int N { get; }
}

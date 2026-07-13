using Sample.Enums;

namespace Sample.Structs;

/// <summary>
/// The v1 component vocabulary that was previously broken in the generator: <c>string</c>,
/// <c>bool</c>, <c>char</c>, and a bound enum (<see cref="CatMood"/>) — now fixed by
/// <c>kotlin-dev</c> (five gaps closed; see the walking-skeleton report for the real compiler
/// diagnostics captured before the fix). Ctor parameters camelCase vs. properties PascalCase
/// again exercises the case-insensitive component match (Decision 3a rule 3).
/// </summary>
public readonly struct Profile
{
    public Profile(string tag, bool active, char grade, CatMood mood)
    {
        Tag = tag;
        Active = active;
        Grade = grade;
        Mood = mood;
    }

    public string Tag { get; }
    public bool Active { get; }
    public char Grade { get; }
    public CatMood Mood { get; }
}

/// <summary>
/// Static methods taking/returning <see cref="Profile"/> — the previously-broken component
/// vocabulary, on STATIC members (mirrors <see cref="Geometry"/>'s static-only surface, but with
/// the risky component types instead of Point's plain ints).
/// </summary>
public static class Cattery2
{
    public static string Announce(Profile p) =>
        $"{p.Tag} ({p.Mood}) grade {p.Grade}, {(p.Active ? "active" : "resting")}";

    public static Profile Promote(Profile p) => new Profile(p.Tag, true, p.Grade, CatMood.Playful);
}

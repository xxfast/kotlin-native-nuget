namespace Sample.Enums;

/// <summary>
/// A cat's current disposition. This is the reverse enum bridge fixture: the values are
/// deliberately default-<see cref="int"/> backed, unique, and contiguous from zero.
/// </summary>
public enum CatMood
{
    Playful,
    Sleepy,
    Hungry,
    Calm,
}

/// <summary>
/// Exercises enum arguments, return values, and both static and instance enum properties
/// for the C# to Kotlin reverse bridge.
/// </summary>
public class CatMoodService
{
    /// <summary>Shared default disposition for every cat mood service.</summary>
    public static CatMood DefaultMood { get; set; } = CatMood.Sleepy;

    /// <summary>Disposition carried by this particular service instance.</summary>
    public CatMood CurrentMood { get; set; }

    public CatMoodService(CatMood mood) => CurrentMood = mood;

    /// <summary>
    /// Returns the next mood in Oreo's nap-to-snack-to-play cycle.
    /// </summary>
    public CatMood Advance(CatMood mood) => mood switch
    {
        CatMood.Playful => CatMood.Sleepy,
        CatMood.Sleepy => CatMood.Hungry,
        CatMood.Hungry => CatMood.Playful,
        _ => throw new ArgumentOutOfRangeException(nameof(mood), mood, "Unknown cat mood"),
    };

    /// <summary>Returns the static default through an instance method.</summary>
    public CatMood ReadDefault() => DefaultMood;
}

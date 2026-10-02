namespace Kotlin.Native.Interop;

/// <summary>
/// A parameter that may be left unset so Kotlin evaluates its default. Omitting it, or passing
/// <c>default</c>, leaves it unset; any value, including <c>null</c>, sets it.
/// </summary>
public readonly struct KotlinOptional<T>
{
    private KotlinOptional(T value)
    {
        HasValue = true;
        Value = value;
    }

    /// <summary>Whether a value, possibly <c>null</c>, was given.</summary>
    public bool HasValue { get; }

    /// <summary>The given value; meaningful when <see cref="HasValue"/> is true.</summary>
    public T Value { get; }

    /// <summary>The unset value.</summary>
    public static KotlinOptional<T> None => default;

    public static implicit operator KotlinOptional<T>(T value) => new(value);
}

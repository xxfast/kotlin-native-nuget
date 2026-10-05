using System.ComponentModel;

namespace Kotlin.Native.Interop;

public interface IKotlinException
{
    string KotlinType { get; }
    string KotlinStackTrace { get; }
}

public class KotlinException : Exception, IKotlinException
{
    public string KotlinType { get; }
    public string KotlinStackTrace { get; }

    internal KotlinException(string kotlinType, string message, string kotlinStackTrace,
        Exception? innerException = null) : base(message, innerException)
    {
        KotlinType = kotlinType;
        KotlinStackTrace = kotlinStackTrace;
    }

    [EditorBrowsable(EditorBrowsableState.Never)]
    public static KotlinException Create(string kotlinType, string message, string kotlinStackTrace,
        Exception? innerException = null) => new(kotlinType, message, kotlinStackTrace, innerException);

    // ADR-203: the one Kotlin-to-.NET exception map, shared by every generated package and by both
    // bridge directions (the forward NugetErrorNative and the reverse NugetKotlinErrors shim).
    // Keyed on the row the Kotlin side matched with `is` (ADR-177), not the concrete class, so a
    // Kotlin subclass of a row maps to that row. The rows, their order and their types are pinned
    // to the processor's KOTLIN_EXCEPTION_TYPES by a processor test that reads this switch.
    [EditorBrowsable(EditorBrowsableState.Never)]
    public static Exception CreateMapped(string kotlinType, string? mappedType, string message,
        string kotlinStackTrace, Exception? innerException = null) =>
        mappedType switch
        {
            "kotlinx.io.IOException" => new KotlinIOException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.NumberFormatException" => new KotlinFormatException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.IllegalArgumentException" => new KotlinArgumentException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.coroutines.cancellation.CancellationException" =>
                new KotlinOperationCanceledException(
                    kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.IllegalStateException" => new KotlinInvalidOperationException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.NoSuchElementException" => new KotlinInvalidOperationException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.ConcurrentModificationException" => new KotlinInvalidOperationException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.UnsupportedOperationException" => new KotlinNotSupportedException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.ClassCastException" => new KotlinInvalidCastException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.ArithmeticException" => new KotlinArithmeticException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.NullPointerException" => new KotlinNullReferenceException(
                kotlinType, message, kotlinStackTrace, innerException),
            "kotlin.NoWhenBranchMatchedException" => new KotlinInvalidOperationException(
                kotlinType, message, kotlinStackTrace, innerException),
            _ => new KotlinException(kotlinType, message, kotlinStackTrace, innerException),
        };

    public override string ToString() => base.ToString()
        + Environment.NewLine + "Kotlin type: " + KotlinType
        + Environment.NewLine + " ---> Kotlin stack trace:"
        + Environment.NewLine + KotlinStackTrace
        + Environment.NewLine + " --- End of Kotlin stack trace ---";
}


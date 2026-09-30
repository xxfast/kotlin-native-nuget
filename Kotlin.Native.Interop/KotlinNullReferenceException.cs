namespace Kotlin.Native.Interop;

public sealed class KotlinNullReferenceException : NullReferenceException, IKotlinException
{
    public string KotlinType { get; }
    public string KotlinStackTrace { get; }

    public KotlinNullReferenceException(string kotlinType, string message, string kotlinStackTrace,
        Exception? innerException = null) : base(message, innerException)
    {
        KotlinType = kotlinType;
        KotlinStackTrace = kotlinStackTrace;
    }

    public override string ToString() => base.ToString()
        + Environment.NewLine + "Kotlin type: " + KotlinType
        + Environment.NewLine + " ---> Kotlin stack trace:"
        + Environment.NewLine + KotlinStackTrace
        + Environment.NewLine + " --- End of Kotlin stack trace ---";
}


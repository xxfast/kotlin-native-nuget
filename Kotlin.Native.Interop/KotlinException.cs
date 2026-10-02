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

    public override string ToString() => base.ToString()
        + Environment.NewLine + "Kotlin type: " + KotlinType
        + Environment.NewLine + " ---> Kotlin stack trace:"
        + Environment.NewLine + KotlinStackTrace
        + Environment.NewLine + " --- End of Kotlin stack trace ---";
}


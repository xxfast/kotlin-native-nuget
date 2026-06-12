using System.Runtime.InteropServices;
using SampleLibrary.Interop;

namespace SampleApp.Tests;

public class GreetingTests
{
    [Fact]
    public unsafe void Greeting_ReturnsExpectedString()
    {
        sbyte* result = SampleLibraryNative.greeting();
        string? message = Marshal.PtrToStringUTF8((nint)result);

        Assert.Equal("Hello from Kotlin/Native!", message);
    }
}

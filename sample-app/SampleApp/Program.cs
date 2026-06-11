using System.Runtime.InteropServices;
using SampleLibrary.Interop;

unsafe
{
    sbyte* result = SampleLibraryNative.greeting();
    string? message = Marshal.PtrToStringUTF8((nint)result);
    Console.WriteLine(message);
}

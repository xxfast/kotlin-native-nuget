using System.Runtime.InteropServices;
using SampleLibrary.Interop;

unsafe
{
    sbyte* result = SampleLibraryNative.get_string();
    string? message = Marshal.PtrToStringUTF8((nint)result);
    Console.WriteLine(message);
}

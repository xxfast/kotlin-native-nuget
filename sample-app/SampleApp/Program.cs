using System.Runtime.InteropServices;
using SampleLibrary.Interop;

IntPtr ptr = SampleLibraryNative.@string();
string? message = Marshal.PtrToStringUTF8(ptr);
Console.WriteLine(message);

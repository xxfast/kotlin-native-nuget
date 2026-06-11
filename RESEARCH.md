# Prior Research

## Summary

While Kotlin/Native tooling can successfully export common declarations to `.dll`, `.h`s and `.def`s, consuming these artifacts in a C# environment is cumbersome and requires additional bridging work to be done.

## Premise

Consider the following piece of code written in Kotlin

```kotlin
@CName("hello") fun hello() = println("Hello Kotlin/Native")
```

When we target this to mingwX64, it generates
```c
extern void hello();
```

amongst the remaining .h declarations and corresponding implementation in .dll

Calling these from C# isn't that straightforward; you need to bridge into this API

```csharp
[DllImport(KTLIB, CallingConvention = CallingConvention.Cdecl)] public static extern void hello();
```

After which, you can use this from C# with

```csharp
hello();
```

Passing in and out primitives can be done similarly

```kotlin
@CName("greet") fun greet(name: String) = println("Hello $name!") @CName("sum") fun sum(a: Int, b: Int): Int = a + b
```

With similar bridging

```csharp
[DllImport(KTLIB, CallingConvention = CallingConvention.Cdecl)] public static extern void greet(string name); [DllImport(KTLIB, CallingConvention = CallingConvention.Cdecl)] public static extern int sum(int a, int b);
```

Which can then be used in C#

```csharp
greet("Isuru"); sum(1,2);
```

## Problem

Given that Kotlin interop is via C, any higher abstractions won't get translated over

For example, Lets say you have this Kotlin class

```kotlin
class Name( val first: String, val middle: String?, val last: String ) { 
  val full: String = buildString { 
    append(first) 
    if (!middle.isNullOrBlank()) append(" $middle") 
    append(" $last") 
  } 
}
```

This is represented in C as

```c
typedef struct { libkmp_KNativePtr pinned; } libkmp_kref_org_example_Name;
```

Any non-primitive types get masked as opaques with just a pointer in the typedefs, which is less usefull

To get around this, we need to flatten any classes down to functional primitives with opaque pointers

```kotlin
@CName("createName") fun createName(first: String, middle: String?, last: String): COpaquePointer { val name = Name(first, middle, last) return StableRef.create(name).asCPointer() } @CName("getFullName") fun getFullName(handle: COpaquePointer): CPointer<ByteVar> { val name: Name = handle.asStableRef<Name>().get() return name.full.cstr.placeTo(Arena()) } @CName("disposeName") fun disposeName(handle: COpaquePointer) { handle.asStableRef<Name>().dispose() }
```

And more boilerplate on C# use-sites to complete the bridging

```csharp
public class Name: IDisposable { 
    private IntPtr _handle; [DllImport(KTLIB)]
    private static extern IntPtr createName(string first, string? middle, string last); 
    [DllImport(KTLIB)] private static extern IntPtr getFullName(IntPtr handle); 
    [DllImport(KTLIB)] private static extern IntPtr disposeName(IntPtr handle); 
    
    public Name(string first, string middle, string last) { 
        _handle = createName(first, middle, last); 
    } 
    
    public string? FullName 
    { 
        get 
        { 
            IntPtr ptr = getFullName(_handle); 
            string? fullName = Marshal.PtrToStringAnsi(ptr); 
            return fullName; 
        } 
    } 
        
    public void Dispose() 
    { 
        if (_handle != IntPtr.Zero) { 
            disposeName(_handle);
             _handle = IntPtr.Zero; 
        } 
    }
```

Only after that we can use these classes in C#

```csharp
Name wicked = new Name("Wicked", null, "Chin"); 
Console.WriteLine(wicked.FullName); 
Name isuru = new Name("Isuru", "Kusumal", "Rajapakse"); 
Console.WriteLine(isuru.FullName);
```

This approach creates more boilerplate and doubles up any classes that we write on either side of the bridge. Any language constructs like collections, generics, lambdas, extensions, or higher abstractions like coroutines will somehow need to be bottlenecked through this C bridge, which won't be scalable in the long run.

### Potential Solutions

Use tooling to help with the bridging

There are a few tools out there that seem to help us do this translation. (like ClangSharpPInvokeGenerator)


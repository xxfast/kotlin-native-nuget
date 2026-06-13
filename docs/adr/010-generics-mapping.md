# ADR-010: Generics mapping

## Status

Accepted

## Context

Kotlin generics need to cross a C bridge where no type system exists — everything is `void*` / `IntPtr`. C# has reified generics (type info preserved at runtime), Kotlin has erased generics on JVM but KSP has full type resolution at compile time.

### How other platforms handle this

- **Java interop**: Type erasure. `Box<String>` and `Box<Int>` are the same class at runtime. Casts inserted by compiler.
- **ObjC Export**: Lightweight generics (`__covariant` type params). Compile-time hints only — runtime is fully erased.
- **Swift Export**: Not fully supported. Generic type parameters are skipped or exported as `Any`.

None of these platforms have solved generics across a native boundary cleanly.

### The challenge

A generic class `Box<T>` with `val value: T`:
- When `T = String`: bridge returns `const char*`, C# marshals to `string`
- When `T = Int`: bridge returns `int` directly
- When `T = Cat`: bridge returns `void*` (StableRef handle), C# wraps in `Cat`

The bridge function signature changes based on `T`. But the C bridge has no polymorphism — each concrete type needs its own function.

### What KSP gives us

KSP resolves type arguments at every usage site. When it sees:
```kotlin
fun createStringBox(): Box<String>
```
It knows the return type is `Box<String>` — the type argument is `String`. We can generate type-specific bridge code.

## Alternatives Considered

### 1. Monomorphization (generate per concrete usage)

KSP scans the codebase for all concrete usages of generic types. For each unique specialization found, generate dedicated bridge functions:

```
box_string_get_value(handle) → const char*
box_int_get_value(handle) → int
box_cat_get_value(handle) → void*
```

C# gets one class per specialization:
```csharp
public class BoxString : IDisposable { public string Value => ...; }
public class BoxInt : IDisposable { public int Value => ...; }
public class BoxCat : IDisposable { public Cat Value => ...; }
```

**Pros:** Type-safe, no runtime dispatch, each specialization is self-contained.
**Cons:** No actual C# generic — loses `Box<T>` polymorphism. Combinatorial explosion with multiple type params. Can't handle types from other modules.

### 2. Type-erased bridge + generic C# class

Single set of bridge functions using `void*` for all reference types, with separate primitives variants. C# has a real generic class:

```csharp
public class Box<T> : IDisposable
{
    public T Value => NugetMarshal.FromHandle<T>(Native_Get_value(_handle));
}
```

With a marshal helper that dispatches based on `typeof(T)`:
```csharp
internal static class NugetMarshal
{
    public static T FromHandle<T>(IntPtr handle)
    {
        if (typeof(T) == typeof(string)) return (T)(object)Marshal.PtrToStringUTF8(handle)!;
        if (typeof(T) == typeof(int)) return (T)(object)(int)handle;
        if (typeof(T).IsAssignableTo(typeof(IDisposable))) return (T)Activator.CreateInstance(typeof(T), handle)!;
        throw new NotSupportedException($"Type {typeof(T)} is not supported");
    }
}
```

**Pros:** Real C# generic type. `Box<T>` works polymorphically. Single bridge function for all reference types.
**Cons:** Runtime dispatch (`typeof(T)` checks). Requires convention that all bridged types have an `IntPtr` constructor. Primitives need variant bridge functions.

### 3. Hybrid — generic C# class + KSP-resolved bridge variants

Combine options 1 and 2:
- C# generates a real generic `Box<T>`
- KSP scans usages and generates bridge function variants for the types actually used
- The generic class dispatches to the correct variant based on `T`
- Unrecognized types fall back to opaque pointer pattern

```csharp
public class Box<T> : IDisposable
{
    internal IntPtr _handle;

    public T Value
    {
        get
        {
            if (typeof(T) == typeof(string))
                return (T)(object)Marshal.PtrToStringUTF8(Native_Get_value_string(_handle))!;
            if (typeof(T) == typeof(int))
                return (T)(object)Native_Get_value_int(_handle);
            // For reference types, create wrapper from handle
            return (T)NugetMarshal.CreateWrapper<T>(Native_Get_value_object(_handle));
        }
    }
}
```

**Pros:** Real generics in C#. Type-safe. KSP only generates what's needed.
**Cons:** Complex dispatch logic. Bridge function naming convention for variants.

## Decision

Use **Option 2: Type-erased bridge + generic C# class** as the starting point.

Rationale:
- Simplest bridge layer — reference types are all `void*`, primitives get one variant each
- Real C# generics — consumers get `Box<T>` with full type safety
- The marshal helper centralizes type dispatch — adding new types means adding one line
- Aligns with how ObjC export works (erased at native layer, typed at consumer layer)
- Avoids combinatorial explosion of monomorphization

### Bridge convention

For a generic property `val value: T`:
- If `T` is a primitive → dedicated bridge: `box_get_value_int(handle)`, `box_get_value_string(handle)`
- If `T` is a reference type → universal bridge: `box_get_value(handle)` returns `void*` (StableRef to the value)

KSP determines which primitive variants to generate by inspecting type arguments at usage sites.

### Marshal helper

A shared `NugetMarshal` utility class handles `IntPtr` → `T` conversion:
- Primitives: direct cast
- Strings: `Marshal.PtrToStringUTF8`
- Reference types: construct wrapper via internal `IntPtr` constructor (convention from ADR-005)

### Generic functions

`fun <T> identity(value: T): T` is deferred. Generic functions require callers to specify type arguments — the bridge can't dispatch without knowing `T` at the call site. Handle in a future iteration.

## Consequences

- Generic classes work across the bridge with real C# type parameters
- The bridge layer remains simple (erased)
- Marshal helper is a single point of truth for type conversion
- Adding new supported types to generics = adding a case to the marshal helper
- Generic functions are not yet supported
- Primitives in type arguments require variant bridge functions (bounded number)
- Types from other modules can be supported if they follow the internal handle constructor convention

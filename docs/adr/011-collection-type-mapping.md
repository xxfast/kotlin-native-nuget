# ADR-011: Collection type mapping

## Status

Accepted

## Context

Kotlin collection types (`List<T>`, `MutableList<T>`, `Map<K,V>`) need to cross a C bridge into C#. Unlike primitives or single objects, collections contain multiple elements that each need individual marshalling. The element type `T` can be a primitive, string, or bridged object — each with different marshalling rules.

### How other platforms handle this

- **Java interop**: No conversion needed. `kotlin.collections.List` IS `java.util.List` at runtime (same JVM types). Consumer sees standard `ArrayList`, `LinkedList`, etc. Zero overhead.
- **ObjC Export**: `List<T>` → `NSArray`, `MutableList<T>` → `NSMutableArray`, `Map<K,V>` → `NSDictionary`. **Eagerly copied** at the boundary. Consumer gets standard Foundation types. The Swift compiler then copies again into Swift-native `Array`/`Dictionary` when bridging from ObjC.
- **Swift Export** (experimental): Same as ObjC — maps to `NSArray`/`NSDictionary`. Eager copy semantics.

Every platform provides **standard collection types** to the consumer. No platform exposes a lazy wrapper or custom collection class.

### The challenge

Our bridge is C-level (`void*` / `IntPtr`). We can't pass a collection by value — we need accessor functions. The question is *when* and *how* we materialize the elements on the C# side:

1. **Eagerly** on property access — copy all elements into a C# collection, dispose the Kotlin handle
2. **Lazily** via a wrapper — keep the Kotlin handle alive, call back into Kotlin on each element access

Each approach has different trade-offs for API ergonomics, memory management, and performance.

### Bridge functions available

Regardless of which option we choose, the Kotlin side exposes:
- `nuget_list_count(handle)` → `int` — list size
- `nuget_list_get(handle, index)` → `void*` — element at index (new StableRef per call)
- `nuget_dispose(handle)` — disposes the list's StableRef

Element handles are unwrapped via `NugetMarshal.FromHandle<T>()` (ADR-010), which handles primitives, strings, and object types uniformly.

## Alternatives Considered

### 1. Lazy wrapper (`NugetList<T>`)

A custom `NugetList<T>` class implementing `IReadOnlyList<T>` and `IDisposable`. Holds the Kotlin list handle and calls bridge functions on demand:

```csharp
public class NugetList<T> : IReadOnlyList<T>, IDisposable
{
    internal IntPtr _handle;
    public int Count => NugetListNative.Count(_handle);
    public T this[int index] => NugetMarshal.FromHandle<T>(NugetListNative.Get(_handle, index));
    // IEnumerable<T>, Dispose...
}
```

Property getter:
```csharp
public NugetList<string> Nicknames => new NugetList<string>(Native_Get_nicknames(_handle));
```

**Pros:**
- Lazy — no upfront cost for large lists if only a few elements are accessed
- Single bridge call on property access (just pins the list)

**Cons:**
- Custom type leaks into consumer API — `NugetList<T>` is not a standard C# collection
- Consumer must remember to `Dispose()` the list (and individual object elements)
- Every `this[i]` crosses the bridge — repeated access is expensive
- Each element access creates a new StableRef that must be tracked/disposed (for object elements)
- Breaks consumer expectations — no other Kotlin export platform uses a lazy wrapper
- `IReadOnlyList<T>` doesn't extend `IDisposable`, so the disposal requirement is hidden when used through the interface

### 2. Eager copy into `List<T>`

On property access, immediately copy all elements into a standard `System.Collections.Generic.List<T>`, then dispose the Kotlin list handle:

```csharp
public List<string> Nicknames
{
    get
    {
        IntPtr listHandle = Native_Get_nicknames(_handle);
        int count = NugetListNative.Count(listHandle);
        var result = new List<string>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<string>(NugetListNative.Get(listHandle, i)));
        }
        NugetListNative.Dispose(listHandle);
        return result;
    }
}
```

**Pros:**
- Standard C# type — consumer sees `List<T>`, no custom types in the API
- No `IDisposable` on the list itself — the list handle is disposed immediately after copy
- Aligns with how every other Kotlin export platform handles collections (ObjC, Swift — all eager copy)
- No bridge calls after initial copy — element access is pure C# memory
- Works naturally with LINQ, serialization, and all `IList<T>` consumers

**Cons:**
- Upfront cost — all elements materialized even if only a few are needed
- For `List<Cat>`, each element is a new C# `Cat` wrapper holding a StableRef — consumer must dispose individual elements
- Memory spike for large lists (all elements + all StableRefs alive simultaneously)
- Repeated property access creates a new copy each time (same issue as current object properties)

### 3. Eager copy into `IReadOnlyList<T>` (backed by `List<T>.AsReadOnly()`)

Same as option 2, but return `IReadOnlyList<T>` to signal immutability:

```csharp
public IReadOnlyList<string> Nicknames
{
    get
    {
        // ... same copy logic ...
        return result.AsReadOnly();
    }
}
```

**Pros:** Same as option 2, plus enforces read-only semantics matching Kotlin's `List<T>`.
**Cons:** Same as option 2, plus `ReadOnlyCollection<T>` wraps the inner list (minor overhead). Consumer can't downcast to `List<T>` for mutation (which is the point).

### 4. Hybrid — lazy wrapper with eager `ToList()` method

Provide both: a lazy `NugetList<T>` with a `ToList()` that eagerly copies. Property returns the lazy wrapper, consumer can opt into eager copy:

```csharp
using var toys = cat.Toys;           // lazy, must dispose
List<Toy> copied = cat.Toys.ToList(); // eager, standard type
```

**Pros:** Consumer chooses the trade-off.
**Cons:** Still exposes `NugetList<T>` as the default API. LINQ's `ToList()` would conflict with our own. More complexity for unclear benefit.

## Decision

Use **eager copy** (option 2) with **interface return types** that mirror Kotlin's mutability semantics:

- `kotlin.collections.List<T>` → `IReadOnlyList<T>` (backed by `List<T>.AsReadOnly()`)
- `kotlin.collections.MutableList<T>` → `IList<T>` (backed by `List<T>`)

Both are eagerly copied into a concrete `List<T>` on property access. The return type is the interface, not the concrete class.

Rationale:
- **Mutability semantics are preserved** — `IReadOnlyList<T>` prevents mutation (matching Kotlin's read-only `List<T>`), `IList<T>` allows it (matching `MutableList<T>`)
- **Consistency with other platforms** — Java, ObjC, and Swift all eagerly convert to standard platform collections. C# consumers should get the same experience.
- **No custom types in the API** — the consumer never sees `NugetList` or any bridge-internal type. The API is pure standard C#.
- **Simpler memory model** — the Kotlin list handle is disposed immediately. No dangling `IDisposable` on the collection itself. Only object elements (e.g., `List<Cat>`) require disposal, which is consistent with how individual object properties already work.
- **Performance is acceptable** — most collections crossing an FFI boundary are small (config, results, UI models). For the rare case of large collections, the eager copy matches what ObjC/Swift already do. If a performance-sensitive lazy path is needed later, it can be added as an opt-in without changing the default API.

### Mutability and one-way copy

The eager copy is **one-way**: Kotlin → C#. Mutations on a C# `IList<T>` (from `MutableList<T>`) do **not** propagate back to the Kotlin side. This matches how ObjC/Swift export works — `NSMutableArray` is a copy, not a live view. If bidirectional mutation is needed, that's a separate feature requiring a different bridge pattern (e.g., callback-based synchronisation).

### Element ownership for object types

When eagerly copying `List<Cat>`:
- Each element gets its own StableRef via `nuget_list_get`
- Each `Cat` wrapper in the C# list owns its StableRef
- Consumer is responsible for disposing individual `Cat` instances (same as any other object property)
- The list itself is a standard C# collection with no disposal requirement

## Consequences

- `List<T>` → `IReadOnlyList<T>`, `MutableList<T>` → `IList<T>` — standard C# interfaces
- Kotlin's read-only vs mutable distinction is preserved at the type level
- No custom collection types in the generated API
- Every property access eagerly copies the full list (N+1 bridge calls: 1 for count, N for elements)
- Object elements in lists follow the same ownership model as standalone object properties
- The `NugetMarshal` helper (ADR-010) handles element type dispatch transparently
- Large lists have upfront materialization cost — acceptable for FFI boundary crossing
- Mutations on `IList<T>` are local to C# and do not propagate back to Kotlin

# ADR-005: Object return semantics — new wrapper per access

## Status

Accepted

## Context

When a Kotlin object returns another Kotlin object (e.g., `Cat.buddy: Cat?`), the C# bridge needs to decide how to represent that returned object on the consumer side.

We researched how Kotlin/Native's ObjC Export and Swift Export handle this same problem.

### How ObjC/Swift Export works

- Each property access creates a **new wrapper** around the same underlying Kotlin object
- Identity is NOT preserved (`cat.buddy === cat.buddy` is `false` in Swift)
- Each wrapper holds a strong reference to the Kotlin object via ARC
- When the wrapper is deallocated, it releases its Kotlin reference
- The Kotlin object stays alive as long as any Swift/ObjC wrapper references it

## Alternatives Considered

### 1. New wrapper per access (ObjC/Swift approach)

Each call to a property getter creates a new `StableRef`, returns a new opaque handle, and the C# side wraps it in a new `Cat` instance. The caller must dispose each instance independently.

**Pros:** Matches proven ObjC/Swift semantics. Simple implementation. No caching complexity.
**Cons:** Identity not preserved (`oreo.Buddy != oreo.Buddy`). Each access allocates. Caller must dispose every returned object.

### 2. Cached wrapper

The C# parent object caches child wrappers internally. Same property access returns the same C# instance. Parent dispose cascades to children.

**Pros:** Identity preserved. Automatic cascading disposal.
**Cons:** Complex — need weak references, invalidation on mutation. Doesn't match how the Kotlin runtime works. Risk of stale references if Kotlin side mutates.

### 3. Raw handle

Property returns `IntPtr`, consumer wraps manually.

**Pros:** Maximum flexibility.
**Cons:** Terrible ergonomics. Defeats the purpose of code generation.

## Decision

Use **new wrapper per access** (option 1), aligning with ObjC/Swift Export behaviour.

For each object-typed property/return:
- Kotlin side: creates a new `StableRef` for the returned object, returns the opaque pointer
- C# side: wraps the pointer in a new instance of the corresponding C# class
- Each wrapper is independently disposable
- The Kotlin object remains alive as long as any `StableRef` references it

```kotlin
// Generated Kotlin bridge
@CName("cat_get_buddy")
fun export_cat_get_buddy(handle: COpaquePointer): COpaquePointer =
    StableRef.create(
        handle.asStableRef<Cat>().get().buddy!!
    ).asCPointer()
```

```csharp
// Generated C#
public Cat? Buddy
{
    get
    {
        IntPtr ptr = Native_Get_buddy(_handle);
        return ptr == IntPtr.Zero ? null : new Cat(ptr);
    }
}
```

## GC interaction

The bridge does not alter how either runtime's garbage collector works internally:

- **Kotlin side**: Objects are normal heap objects managed by Kotlin's tracing GC. Cycles between Kotlin objects are handled by the GC as usual. A `StableRef` simply adds an additional GC root — it prevents collection but doesn't change object relationships.
- **C# side**: Wrappers are normal managed objects. .NET's GC collects them when unreachable. Cycles between C# wrappers (if any) are handled by .NET's GC as usual.
- **The bridge**: `StableRef` is the only connection between the two worlds. It says "don't collect this Kotlin object — someone outside still needs it." Once all `StableRef`s for an object are disposed AND no Kotlin-side references remain, the Kotlin GC can collect it.

In short: each GC manages its own side. The bridge only adds/removes GC roots via `StableRef`.

## Consequences

**Positive:**
- Aligns with how Kotlin/Native already works for Apple platforms
- Simple, stateless implementation — no caching or invalidation logic
- Each wrapper independently manages its own lifetime
- Safe with Kotlin's GC — StableRef keeps the object alive

**Negative:**
- Identity not preserved: `cat.Buddy != cat.Buddy` (new C# object each time)
- Every property access that returns an object allocates (StableRef + C# object)
- Consumer must dispose every returned object (or use `using`)
- Risk of leaks if consumer forgets to dispose returned objects

**Mitigations:**
- Generated classes implement `IDisposable` — enables `using` pattern
- Can add a finalizer as a safety net (calls dispose if consumer forgot)
- Document that object-returning properties allocate and must be disposed

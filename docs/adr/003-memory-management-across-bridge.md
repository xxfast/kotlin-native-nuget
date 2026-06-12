# ADR-003: Memory management across the C bridge

## Status

Accepted

## Context

Kotlin/Native's GC manages heap objects automatically, but objects passed across the C boundary are not GC roots unless explicitly pinned. The C# runtime has its own GC with no awareness of Kotlin's. We need a clear ownership model for each type crossing the bridge.

### How Kotlin/Native handles the C boundary

- **Strings:** `@CName` functions returning `String` produce a `const char*` allocated in a per-call scope. The pointer is only valid during the immediate call — the Kotlin runtime may free it after returning.
- **Objects:** Must be pinned with `StableRef.create()` to survive beyond a single call. Returns a `COpaquePointer`. Must be explicitly disposed with `StableRef.dispose()`.
- **Primitives:** Value types copied by value. No ownership concern.

### How the ObjC/Swift bridge handles this

Kotlin `String` → `NSString` (managed by ARC). Objects map to ObjC classes with reference counting. No explicit dispose needed — ARC handles lifetime. We cannot replicate this over a C bridge.

## Decision

### Primitives (Phase 2 — implemented)

Copied by value across the bridge. No memory management needed.

### Strings (Phase 2 — implemented)

- Kotlin returns `const char*` (temporary, owned by Kotlin runtime)
- C# copies immediately via `Marshal.PtrToStringUTF8(ptr)` — produces a managed .NET string
- The `IntPtr` is never cached or reused

This is safe because `Marshal.PtrToStringUTF8` copies the bytes before returning control to the native side.

### Objects (Phase 3 — planned)

Pattern: opaque handle with explicit dispose.

**Kotlin side:**
```kotlin
@CName("cat_create")
fun catCreate(name: String): COpaquePointer =
    StableRef.create(Cat(name)).asCPointer()

@CName("cat_get_name")
fun catGetName(handle: COpaquePointer): String =
    handle.asStableRef<Cat>().get().name

@CName("cat_dispose")
fun catDispose(handle: COpaquePointer) =
    handle.asStableRef<Cat>().dispose()
```

**C# side (generated):**
```csharp
public class Cat : IDisposable
{
    private IntPtr _handle;

    public Cat(string name) { _handle = CatNative.cat_create(name); }
    public string Name => Marshal.PtrToStringUTF8(CatNative.cat_get_name(_handle));
    public void Dispose() { CatNative.cat_dispose(_handle); _handle = IntPtr.Zero; }
}
```

### Collections (Phase 3 — planned)

Opaque handle + accessor functions:
- `list_count(handle)` → `int`
- `list_get(handle, index)` → element (copied)
- `list_dispose(handle)` → frees the pinned list

## Consequences

**Positive:**
- Clear ownership: Kotlin pins, C# disposes
- Safe string handling (immediate copy, no dangling pointers)
- Aligns with .NET's `IDisposable` pattern — familiar to C# developers
- Analyzers can warn on undisposed handles

**Negative:**
- Forgetting to call `Dispose()` leaks memory (pinned objects never collected)
- No shared reference counting — can't have multiple C# references to the same Kotlin object without a custom ref-count layer
- Every property/method access on an object crosses the bridge (no local caching)

**Mitigations:**
- Generated C# classes implement `IDisposable` with a destructor/finalizer as safety net
- Consider `SafeHandle` for automatic cleanup if the process exits without dispose

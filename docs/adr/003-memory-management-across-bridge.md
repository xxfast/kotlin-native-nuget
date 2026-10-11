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
  - *Amended 2026-10-02:* never emitted as a destructor; [ADR-187](187-forward-finalizer-contract.md) ships the safety net as a `SafeHandle` instead, so an undisposed wrapper is released when the .NET GC finalizes its handle.
- Consider `SafeHandle` for automatic cleanup if the process exits without dispose

## Amendment (2026-10-10): a null `string` for a non-null Kotlin `String` is refused in C#

**Defect.** C# can always pass `null!` where a `string` is declared. The marshaller turned it into
a null pointer, and Kotlin then held a null in a `String` slot it believes is non-null. The
original Strings decision above only covered what Kotlin hands back; this is the other direction.

**Pre-fix behaviour, measured per route family in separate `dotnet test` processes.**

- Process crash (`0xC0000005`): a companion, `object` or top-level parameter, an extension-property
  receiver, and a value-class constructor taking a raw string.
- The null silently stored in a non-null Kotlin `String`, no exception: a constructor, a property
  setter, a method parameter, a `suspend` member parameter, a `Flow`-returning member parameter, an
  extension-function receiver, a sealed-arm constructor and member, and an interface member.
- `KotlinNullReferenceException` (Kotlin `NullPointerException` out of the collection read): a null
  element in a `List`, `Set` or `Map` of strings.
- Process crash on an uncaught `kotlin.NullPointerException`: a C# callback or a C#-implemented
  interface member that RETURNS null to Kotlin.

**Rule.** Every forward crossing of a `string` into a non-null Kotlin `String` slot reads it as
`(x ?? throw new ArgumentNullException(nameof(x)))` at the unwrap, in C#, before anything crosses.
One helper owns the spelling, `nonNullStringOrThrow` (`ValueClassDefaultGuard.kt`), beside
`valueClassUnderlyingOrThrow` from [ADR-077](077-value-classes-at-ordinary-positions.md), and for
the same reason it is an expression: it serves argument lists, `Select` lambdas, expression-bodied
members and write lambdas. The `MutableStateFlow` and `MutableSharedFlow` string element writes,
guarded earlier in this stack with a statement form (see [ADR-071](071-mutable-stateflow-mapping.md)),
now use the helper.

- `ParamName` is the public parameter the caller wrote. A null element of a `List`, `Set` or `Map`
  of strings is reported against the collection the caller passed, so `ParamName` is the
  collection parameter.
- **Behaviour change.** That null element used to throw `KotlinNullReferenceException`; it now
  throws `ArgumentNullException`, so a consumer catching the old type will notice.
- On a `Flow`-returning member the guard fires when the flow is collected, not when the method is
  called, because that is where the argument is read.
- A callback or C#-implemented interface member returning null is refused inside the generated
  `NugetMarshal.WrapString` helper (its `value` parameter) and travels the existing callback fault
  path of [ADR-161](161-csharp-callback-exception-into-kotlin.md), so the C# caller of the Kotlin
  function gets the `ArgumentNullException` back. For a lambda that is the exact type; for an
  interface member only an exception whose message contains `value` is asserted.

**Deliberately unguarded (pinned).**

- A `string?` slot, and a defaulted `string? x = null` (where null means "use the default").
- A generic `T` position: `new Box<string>(null!)` legitimately holds null, because `T` is
  unbounded and C# cannot tell `Box<string>` from `Box<string?>` at run time.
- A value-class string underlying stays on the value-class helper and throws `ArgumentException`.

**Cost.** A scalar string argument is one null check, no allocation. A collection of non-null
strings now passes through one `Enumerable.Select`, one iterator object per call. The guard runs
in argument order, after earlier arguments have built their handles; those are released on the
throw (the call's own `finally`, or `CreateList`'s catch for a half-filled list).

**Evidence.**

- Verified: `Tier1NullStringGuardTest` (8 cells, one with a real `dotnet build`);
  `IntegrationTests/NullStringGuardTests.cs` (22 facts, run natively and under AOT); fixture
  `test-library/.../stamps/NullStringSample.kt`; `LeakTests/LiveHandleTests.cs` row 8d-nullstring,
  `NullString_RefusedAfterAHandleWasBuilt_ReturnsToBaseline`. Across the regenerated fixture
  library, 494 native imports take a non-null `string` and none is left unguarded outside the
  categories above. The pre-fix behaviours above were observed directly.
- Inferred: only that the interface-return cell asserts the message rather than the exception type.

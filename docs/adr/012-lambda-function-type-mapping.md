# ADR-012: Lambda/function type mapping

## Status

Proposed

## Context

Kotlin function types (`(T) -> R`, `() -> Unit`) need to cross the C bridge into C#. Unlike primitives or objects, lambdas are heap-allocated closures with captured state — they can't be passed as raw values through the C ABI.

There are two directions to consider:
- **Kotlin → C#**: A Kotlin property or function return is a lambda. C# needs to invoke it.
- **C# → Kotlin**: A Kotlin function accepts a lambda parameter. C# needs to pass a delegate/closure.

### Key constraint: `staticCFunction`

Kotlin/Native's `staticCFunction` converts a Kotlin function literal into a `CPointer<CFunction<...>>`, but **only for non-capturing lambdas**. Any lambda that captures state (e.g., `this`, local variables) will fail at compile time. This rules out direct function pointer export for most real-world lambdas.

```kotlin
// Works — no capture
val fnPtr = staticCFunction { x: Int -> x * 2 }

// FAILS at compile time — captures `this`
val fnPtr = staticCFunction { speak() }
```

### Prior art: how other Kotlin targets handle lambdas

Every Kotlin target uses an **indirect invocation pattern** — no target passes lambdas as naked C function pointers. The critical difference between targets is whether they have a shared or bridged memory manager.

#### Kotlin/Native → ObjC/Swift (closest analogue)

Kotlin lambdas map directly to **ObjC blocks** / **Swift closures**. The ObjC block ABI is a `(function_pointer, context_pointer)` pair — the runtime handles pairing automatically. Lifecycle is automatic because Kotlin's tracing GC and ObjC's ARC have a built-in integration bridge.

Pain points:
- **Primitive boxing in function types**: Inside lambda signatures, primitives are boxed to `KotlinInt`, `KotlinFloat`, etc. (NSNumber subclasses). A `(Int) -> Unit` in Kotlin becomes `(KotlinInt) -> KotlinUnit` in Swift, forcing casts.
- **Unit return**: `Unit`-returning lambdas require `return KotlinUnit()` in Swift instead of `Void`. A major ergonomic issue.
- **Retain cycles**: When closures capture both Kotlin and ObjC/Swift objects, retain cycles can form that neither GC nor ARC can break. Swift callers must use `[weak self]`.

JetBrains is developing **Swift Export** (Alpha) which generates native Swift modules directly, bypassing the ObjC bridge — this eliminates primitive boxing and maps `Unit` to `Void`.

#### Kotlin/JVM → Java

Kotlin lambdas compile to `kotlin.jvm.functions.Function0<R>` through `Function22<...>` interfaces. Java callers invoke via `.invoke()` — the closest existing analogue to our `KotlinFunc<T>` approach. Lifecycle is automatic (shared JVM GC). Pain point: Java callers must `return Unit.INSTANCE` for `Unit`-returning lambdas. SAM interfaces (`fun interface`) are the recommended workaround.

#### Kotlin/JS and Kotlin/Wasm → JavaScript

Lambdas become **plain JS functions**. No wrappers, no lifecycle concerns — both sides are garbage-collected. The simplest mapping, but also the least constrained runtime.

#### SKIE (Touchlab)

A compiler plugin that generates additional Swift wrappers on top of the ObjC headers. Does not change the fundamental lambda/closure mapping — primitive boxing and `KotlinUnit` persist. Main contribution is bridging `suspend` functions to Swift `async` with real two-way cancellation, and `Flow<T>` to `AsyncSequence`. Known limitation: lambda types as generic type arguments (e.g., `A<() -> Unit>`) are not supported due to a Swift compiler limitation.

#### Why C# requires wrapper types

Every other target has a **shared or bridged memory manager**: JVM GC, JS GC, or the Kotlin GC↔ARC integration for Apple. C# over P/Invoke has none of that — the FFI boundary is raw `IntPtr` handles to `StableRef` pointers. There is no mechanism for C#'s GC to tell Kotlin's GC "I'm done with this object." This is why `KotlinFunc<T>` with `IDisposable` is necessary — it makes the ownership cost explicit rather than hiding it behind `Func<T>` which doesn't carry that lifecycle contract. The `KotlinAction`/`KotlinAction<T>` split for `Unit`-returning lambdas addresses the ergonomic lesson from ObjC/Swift, where forcing a dummy return value is a well-known pain point.

## Alternatives Considered

### 1. Opaque handle with invoke/dispose exports (Kotlin → C#)

Same pattern as classes: store the lambda in a `StableRef`, export `invoke` and `dispose` functions. C# wraps the handle in a typed `IDisposable` class.

**Kotlin side (generated):**
```kotlin
@CName("cat_get_onMeow")
fun cat_get_onMeow(handle: COpaquePointer): COpaquePointer =
    StableRef.create(handle.asStableRef<Cat>().get().onMeow).asCPointer()

@CName("cat_onMeow_invoke")
fun cat_onMeow_invoke(lambdaHandle: COpaquePointer, arg0: COpaquePointer): COpaquePointer {
    val fn = lambdaHandle.asStableRef<(String) -> String>().get()
    val param = arg0.asStableRef<String>().get()
    return StableRef.create(fn(param)).asCPointer()
}

@CName("cat_onMeow_dispose")
fun cat_onMeow_dispose(lambdaHandle: COpaquePointer) =
    lambdaHandle.asStableRef<(String) -> String>().dispose()
```

**C# side (generated):**
```csharp
public class KotlinFunc<T, TResult> : IDisposable
{
    internal IntPtr _handle;

    [DllImport("sample", EntryPoint = "cat_onMeow_invoke")]
    private static extern IntPtr Native_Invoke(IntPtr handle, IntPtr arg0);

    [DllImport("sample", EntryPoint = "cat_onMeow_dispose")]
    private static extern void Native_Dispose(IntPtr handle);

    public TResult Invoke(T arg)
    {
        // marshal arg, call native, unmarshal result
    }

    public void Dispose() { ... }
}
```

**Pros:**
- Consistent with existing class/collection bridge pattern
- Supports capturing lambdas (the StableRef holds the full closure)
- Clean `IDisposable` lifecycle

**Cons:**
- Every invocation crosses the bridge (no way to "copy" a lambda to C#)
- Consumer must dispose the lambda handle
- Need per-arity wrapper types (`KotlinFunc<R>`, `KotlinFunc<T, R>`, `KotlinFunc<T1, T2, R>`, etc.)

### 2. C function pointer + context pair (C# → Kotlin)

For Kotlin functions that accept lambda parameters, use the standard C pattern: a function pointer paired with a context/userData pointer. C# marshals a delegate to a function pointer via `Marshal.GetFunctionPointerForDelegate` and pins it with `GCHandle`.

**Kotlin side (generated):**
```kotlin
@CName("transform")
fun export_transform(
    listHandle: COpaquePointer,
    fnPtr: CPointer<CFunction<(COpaquePointer) -> COpaquePointer>>,
): COpaquePointer {
    val list = listHandle.asStableRef<List<String>>().get()
    val kotlinFn: (String) -> String = { str ->
        memScoped {
            val cStr = str.cstr.ptr
            val resultPtr = fnPtr.invoke(cStr.reinterpret())
            resultPtr.asStableRef<String>().get()
        }
    }
    return StableRef.create(list.map(kotlinFn)).asCPointer()
}
```

**C# side (generated):**
```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
private delegate IntPtr StringTransformDelegate(IntPtr input);

[DllImport("sample", EntryPoint = "transform")]
private static extern IntPtr Native_Transform(IntPtr listHandle, IntPtr fnPtr);

public static IReadOnlyList<string> Transform(IReadOnlyList<string> names, Func<string, string> fn)
{
    StringTransformDelegate nativeDelegate = (inputPtr) =>
    {
        string input = Marshal.PtrToStringUTF8(inputPtr)!;
        string output = fn(input);
        return Marshal.StringToCoTaskMemUTF8(output);
    };
    GCHandle pinned = GCHandle.Alloc(nativeDelegate);
    IntPtr fnPtr = Marshal.GetFunctionPointerForDelegate(nativeDelegate);
    try
    {
        IntPtr resultHandle = Native_Transform(marshalList(names), fnPtr);
        return unmarshalList(resultHandle);
    }
    finally { pinned.Free(); }
}
```

**Pros:**
- Standard C interop pattern — well-documented, proven
- C# delegate is automatically GC-pinned for the call duration
- No Kotlin-side overhead — the function pointer is invoked directly

**Cons:**
- Each unique lambda signature requires a concrete delegate type
- Marshalling arguments/returns inside the callback adds complexity
- If Kotlin stores the function pointer beyond the call (e.g., in a field), the `GCHandle` must stay alive — lifetime management becomes the caller's responsibility

### 3. Expose as `Func<T, R>` / `Action<T>` directly

Instead of custom `KotlinFunc` types, expose Kotlin → C# lambdas as standard `Func`/`Action` delegates. Internally, wrap the native invoke call in a delegate factory:

```csharp
public Func<string, string> OnMeow
{
    get
    {
        IntPtr lambdaHandle = Native_Get_onMeow(_handle);
        return (arg) =>
        {
            IntPtr result = Native_onMeow_invoke(lambdaHandle, Marshal.StringToCoTaskMemUTF8(arg));
            return Marshal.PtrToStringUTF8(result)!;
        };
    }
}
```

**Pros:**
- Standard C# types — consumer sees `Func<T, R>`, no custom types in the API
- Natural to use — `cat.OnMeow("hello")` works directly

**Cons:**
- **Leaks the StableRef** — no obvious place to dispose the lambda handle. The `Func` delegate is a managed object; when it's GC'd, the Kotlin StableRef is orphaned.
- Could add a weak reference + destructor to clean up, but adds complexity and non-deterministic cleanup
- Breaks the `IDisposable` contract established for all other bridge types

## Decision

Use **option 1 (opaque handle)** for Kotlin → C# direction (Phase 3). Defer **option 2 (function pointer + context)** for C# → Kotlin direction to Phase 5 (bidirectional support).

### Kotlin → C# (returning/exposing lambdas) — Phase 3

Kotlin lambda properties and returns are bridged using the same `StableRef` opaque handle pattern as classes. Generate per-site `invoke` and `dispose` exports on the Kotlin side. On the C# side, wrap in arity-specific types:

| Kotlin type | C# type |
|---|---|
| `() -> R` | `KotlinFunc<TResult>` |
| `(T) -> R` | `KotlinFunc<T, TResult>` |
| `(T1, T2) -> R` | `KotlinFunc<T1, T2, TResult>` |
| `() -> Unit` | `KotlinAction` |
| `(T) -> Unit` | `KotlinAction<T>` |

All implement `IDisposable`. The `Kotlin` prefix distinguishes them from `System.Func`/`System.Action` and signals they hold a native resource.

### C# → Kotlin (accepting lambda parameters) — deferred to Phase 5

Kotlin functions with lambda parameters accept a `CPointer<CFunction<...>>` at the C boundary. C# marshals a `Func`/`Action` delegate to a function pointer, pins it for the call, and frees after return. This requires fundamentally new plumbing (delegate marshalling, `GCHandle` pinning, `CPointer<CFunction<...>>` on the Kotlin side) that aligns with the bidirectional support work in Phase 5. Storing function pointers across calls (async, callbacks) adds further lifetime complexity best addressed alongside reverse P/Invoke.

### Arity limit

Support arities 0–3 initially (`() -> R` through `(T1, T2, T3) -> R`). Higher arities are rare in practice and can be added later.

### Why not `Func<T, R>` directly

Option 3 is tempting but fundamentally incompatible with deterministic resource management. Every other bridge type (`Cat`, `KotlinFunc`, collections) follows `IDisposable`. Leaking StableRefs behind a `Func` delegate breaks this contract and introduces non-deterministic cleanup that could exhaust Kotlin/Native's stable ref table under load.

## Consequences

- New CIR types: `CirLambdaProperty`, `CirLambdaParameter` for lambda-typed declarations
- New Kotlin export pattern: per-lambda `invoke`/`dispose` functions alongside the getter
- New C# types: `KotlinFunc<...>` and `KotlinAction<...>` (arity 0–3) implementing `IDisposable`
- Lambda parameters (C# → Kotlin) require concrete delegate types per unique signature
- The `KotlinFunc`/`KotlinAction` types are bridge-specific — unlike collections (which map to standard `IList<T>`), there is no standard C# equivalent that supports `IDisposable` + native invocation
- Synchronous-only initially — async lambda callbacks (e.g., stored for later invocation) deferred to Phase 5

### Future implications of the asymmetric approach

The two-direction asymmetry (opaque handle for Kotlin→C#, function pointer for C#→Kotlin) is inherent to the C boundary — not a design choice that could be avoided. However, it creates pressure points as lambda support evolves:

- **Receivers (`Cat.() -> Unit`)**: Not a concern. A receiver is syntactic sugar for a first parameter — `(Cat) -> Unit` at the bytecode level. The opaque handle captures the receiver in the Kotlin closure; the function pointer pattern passes it as an explicit `IntPtr` argument.
- **Stored callbacks**: The current C#→Kotlin pattern scopes the `GCHandle` to the call duration. If Kotlin stores the function pointer for later invocation (e.g., event handlers, observers), the `GCHandle` must outlive the call with no obvious signal for when to free it. This will likely require a persistent callback registry pattern in Phase 5.
- **Lambda round-tripping**: If Kotlin returns a lambda that was originally passed from C#, the two representations clash — a `Func<T,R>` goes in as a function pointer but comes back as a `KotlinFunc<T,R>` wrapping a StableRef. No identity preservation. A unified callback registry could address this but adds significant complexity.
- **Suspend lambdas (`suspend () -> R`)**: Will need to compose with the async bridge (Phase 4). A `suspend` lambda returns via continuation, not a direct return value. The opaque handle pattern still works — the `invoke` export would need to accept a completion callback or return a handle to a `Deferred`/`Task`. This is a natural extension of both the lambda bridge and the async bridge, but the interaction between the two patterns needs careful design. Likely maps to `KotlinFunc<..., Task<R>>` or a dedicated `KotlinSuspendFunc<..., R>` type on the C# side.

# ADR-036: Reverse interop mechanism — C# callbacks invoked from Kotlin via delegate function pointers

## Status

Proposed

## Context

All existing bridge directions are Kotlin → C#: Kotlin exports functions and C# calls them via P/Invoke. Phase 7 requires the **reverse**: C# provides callbacks that Kotlin invokes. This is needed for:

- `fun transform(fn: (T) -> R)` — Kotlin accepts a lambda from C#
- Event/observer registration — C# passes a handler that Kotlin stores and calls later
- Implementing a Kotlin interface from C# — passing a C#-side object into a Kotlin function
- `Flow<T>` as a function parameter — C# provides an `IAsyncEnumerable<T>` consumed as a `Flow`

ADR-012 sketched this problem as its **Alternative 2** and deferred it, citing:

> Each unique lambda signature requires a concrete delegate type. If Kotlin stores the function pointer beyond the call the `GCHandle` must stay alive — lifetime management becomes the caller's responsibility.

ADR-026 (Flow) and ADR-019 (suspend) have since **already established working reverse callbacks** in this codebase: C# creates a delegate, pins it with `GCHandle`, gets its pointer via `Marshal.GetFunctionPointerForDelegate`, passes the `IntPtr` to Kotlin, and Kotlin `reinterpret`s it as `CPointer<CFunction<...>>` before invoking it. This ADR decides how to generalise that pattern for user-facing lambda parameters and documents the alternatives and their tradeoffs.

### Key constraint: `staticCFunction` (Kotlin side, does NOT affect this use case)

Kotlin/Native's `staticCFunction` converts a Kotlin lambda to a `CPointer<CFunction<...>>`, but only for **non-capturing** lambdas. This constraint is a concern when Kotlin creates a function pointer. In the C#→Kotlin direction, **C# creates the function pointer** and passes it to Kotlin. The constraint does not apply here.

Source: [Kotlin/Native interop — Callbacks](https://kotlinlang.org/docs/native-c-interop.html#callbacks)

### How Kotlin/Native accepts a C function pointer

A `@CName` export takes the pointer as `COpaquePointer` and reinterprets it into a typed `CPointer<CFunction<...>>` at the call site:

```kotlin
@CName("repository_find_all")
fun export_repository_findAll(
    fnPtr: COpaquePointer,     // C# delegate → function pointer
    userData: COpaquePointer,  // context carried alongside the pointer
    errorOut: COpaquePointer?,
): COpaquePointer? = try {
    val fn = fnPtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> COpaquePointer?>>()
    val kotlinPredicate: (String) -> Boolean = { item ->
        val itemRef = StableRef.create(item).asCPointer()
        val result = fn.invoke(itemRef, userData)
        itemRef.asStableRef<String>().dispose()
        result != null
    }
    StableRef.create(repository.findAll(kotlinPredicate)).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null)
        errorOut.reinterpret<COpaquePointerVar>().pointed.value =
            StableRef.create(buildError(e)).asCPointer()
    null
}
```

This pattern is identical to what the Flow export generator (`ClassExports.kt`) and `SuspendFunctionExports.kt` already emit: `reinterpret<CFunction<...>>()` + `.invoke(...)`.

Source: [Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html)

### Existing precedent in this codebase

`CirFlowRenderer.kt` already generates the C# side of reverse callbacks for Flow subscriptions:

```csharp
NugetFlowOnNextCallback onNext = (itemPtr, isCancelled, userData) => { ... };
_onNextHandle = GCHandle.Alloc(onNext);
IntPtr onNextPtr = Marshal.GetFunctionPointerForDelegate(onNext);
_jobHandle = startCollect(onNextPtr, onCompletePtr, onErrorPtr, IntPtr.Zero);
```

The suspend function wrappers (`NugetAsyncCallback`) pass the delegate directly to the `[DllImport]` entry point and also use `GCHandle.Alloc` to keep the delegate alive for the duration of the async call.

Both patterns are **delegate + GCHandle**: C# creates a managed delegate (which can capture state), pins it so the GC cannot move or collect it, obtains its raw function pointer, and passes that pointer to Kotlin.

### How other Kotlin interop targets handle reverse callbacks (C# → Kotlin direction)

#### Java/JVM

Kotlin lambdas compile to SAM-compatible function interfaces (`Function1<T, R>`). A Java caller passes an instance of the functional interface (which is just a managed object on the shared JVM heap). No function pointer is involved. Lifetime is automatic (shared GC). Not replicable across a C boundary.

#### ObjC/Swift (built-in)

Kotlin/Native's ObjC export represents Kotlin function types as **ObjC blocks** (`void (^)(NSString *)`). Blocks are heap objects under ARC. An ObjC/Swift caller passes a block directly; the Kotlin runtime bridges ARC retain/release to Kotlin's tracing GC via a built-in integration. Not available at a raw C boundary.

#### SKIE (Touchlab)

SKIE generates additional Swift wrappers but does not change the fundamental mechanism. Lambda types as generic type arguments (`A<() -> Unit>`) are explicitly not supported due to a Swift compiler limitation. Swift closures are passed as ObjC blocks.

#### Swift Export (official, Alpha)

Swift Export maps Kotlin function types to Swift closures. Closures are reference-counted. No public documentation on the ABI; the mechanism is compiler-managed and not inspectable for this purpose.

#### Kotlin/JS

Both sides share the JS GC. JavaScript functions are first-class objects; no function pointer or pinning is needed. Not applicable to a C/P-Invoke boundary.

#### Summary for C boundary

Every other target benefits from a **shared or bridged memory manager**. The C boundary has neither. The only way to cross it is with raw function pointers (`void (*)(...)`) paired with a context pointer. C# must produce a stable function pointer from a managed delegate and keep the delegate alive for the duration of Kotlin's use.

### What's idiomatic in C#

C# expresses callbacks as `Func<T, TResult>` / `Action<T>` delegates. Passing one to a native function requires either:

1. Letting P/Invoke create a thunk implicitly (when the delegate is declared as a `[DllImport]` parameter type directly)
2. Calling `Marshal.GetFunctionPointerForDelegate` explicitly to get an `IntPtr` + `GCHandle.Alloc` to pin it

Option 2 is what existing Flow and async callbacks already use in this codebase.

The `[UnmanagedFunctionPointer(CallingConvention.Cdecl)]` attribute on a delegate type explicitly declares its calling convention; without it, the runtime defaults to `Winapi` / `StdCall` on Windows and `Cdecl` elsewhere, which can diverge. For cross-platform safety and clarity, the attribute should be applied.

Source: [Marshal.GetFunctionPointerForDelegate](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.marshal.getfunctionpointerfordelegate)
Source: [GCHandle](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.gchandle)
Source: [UnmanagedFunctionPointerAttribute](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.unmanagedfunctionpointerattribute)

## Alternatives Considered

### 1. Delegate + GCHandle + `Marshal.GetFunctionPointerForDelegate` (chosen for v1)

C# creates a managed delegate closure, pins it with `GCHandle.Alloc`, obtains its function pointer via `Marshal.GetFunctionPointerForDelegate`, and passes the `IntPtr` to the Kotlin export. For per-call callbacks the `GCHandle` is freed in a `finally` block. For stored callbacks it is kept alive until Kotlin calls a matching `_unregister` export.

**Kotlin side (generated, for `fun findAll(predicate: (String) -> Boolean): List<String>`):**

```kotlin
@CName("repository_findAll")
fun export_repository_findAll(
    handle: COpaquePointer,
    predicatePtr: COpaquePointer,
    userData: COpaquePointer,
    errorOut: COpaquePointer?,
): COpaquePointer? = try {
    val obj = handle.asStableRef<Repository>().get()
    val fn = predicatePtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> Byte>>()
    val predicate: (String) -> Boolean = { item ->
        val itemRef = StableRef.create(item).asCPointer()
        val result = fn.invoke(itemRef, userData)
        itemRef.asStableRef<String>().dispose()
        result != 0.toByte()
    }
    StableRef.create(obj.findAll(predicate)).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null)
        errorOut.reinterpret<COpaquePointerVar>().pointed.value =
            StableRef.create(buildError(e)).asCPointer()
    null
}
```

**C# side (generated):**

```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate byte PredicateStringCallback(IntPtr itemPtr, IntPtr userData);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "repository_findAll")]
private static extern IntPtr Native_FindAll(IntPtr handle, IntPtr predicatePtr, IntPtr userData, out IntPtr error);

public IReadOnlyList<string> FindAll(Func<string, bool> predicate)
{
    PredicateStringCallback nativeCallback = (itemPtr, userData) =>
    {
        string item = NugetMarshal.FromHandle<string>(itemPtr);
        NugetMarshal.Dispose(itemPtr);
        return predicate(item) ? (byte)1 : (byte)0;
    };
    GCHandle callbackHandle = GCHandle.Alloc(nativeCallback);
    IntPtr fnPtr = Marshal.GetFunctionPointerForDelegate(nativeCallback);
    try
    {
        IntPtr result = Native_FindAll(_handle, fnPtr, IntPtr.Zero, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return NugetMarshal.FromListHandle<string>(result);
    }
    finally
    {
        callbackHandle.Free();
    }
}
```

**Consumer API (what C# developers write):**

```csharp
var longNames = repository.FindAll(name => name.Length > 10);
var starts = repository.FindAll(s => s.StartsWith("cat-"));
```

**Pros:**
- Consistent with the established pattern for Flow callbacks (`CirFlowRenderer.kt`) and async callbacks (ADR-019). Zero new infrastructure.
- Works for **capturing lambdas** and instance method closures — the most common real-world use case.
- Compatible with all .NET versions supported by this project (net8.0+).
- `GCHandle.Alloc` without `GCHandleType.Pinned` is a **normal GC handle** (not a pinned handle) — the GC can still compact the heap; the delegate object is tracked, not physically pinned. This is lighter weight than pinning a struct/array.
- `Marshal.GetFunctionPointerForDelegate` creates a managed-to-unmanaged thunk that survives GC moves automatically.
- Per-call callbacks (the v1 scope) require no registry: pin for call duration, free in `finally`.
- The calling convention is `Cdecl` (explicit via `[UnmanagedFunctionPointer]`), matching what Kotlin/Native expects.

**Cons:**
- Thunk overhead per callback invocation (one managed-to-unmanaged transition). Acceptable for business-logic callbacks; not suitable for tight inner loops.
- Every unique callback signature needs a concrete delegate type. For the generator, this means one delegate type per `(T1, T2, ...) -> R` shape. Three shapes cover most real-world use (`() -> R`, `(T) -> R`, `(T1, T2) -> R`).
- Stored callbacks (event handlers, observers) require keeping `GCHandle` alive and coordinating with Kotlin for cleanup. This is the hard part; deferred to a follow-on ADR.

### 2. `[UnmanagedCallersOnly]` + `delegate* unmanaged<...>` (.NET 5+)

Since .NET 5, the `[UnmanagedCallersOnly]` attribute marks a `static` method as directly callable from native code without a managed thunk. `delegate* unmanaged<T1, R>` is the corresponding function pointer type.

```csharp
// Static helper — cannot capture state
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static byte StaticPredicate(IntPtr itemPtr, IntPtr userData)
{
    // Cannot use 'predicate' from enclosing scope — it's not accessible here
    // userData carries a GCHandle to the actual delegate
    GCHandle h = GCHandle.FromIntPtr(userData);
    var fn = (Func<string, bool>)h.Target!;
    string item = NugetMarshal.FromHandle<string>(itemPtr);
    NugetMarshal.Dispose(itemPtr);
    return fn(item) ? (byte)1 : (byte)0;
}

public IReadOnlyList<string> FindAll(Func<string, bool> predicate)
{
    GCHandle delegateHandle = GCHandle.Alloc(predicate);
    try
    {
        delegate* unmanaged[Cdecl]<IntPtr, IntPtr, byte> fnPtr = &StaticPredicate;
        IntPtr result = Native_FindAll(_handle, (IntPtr)fnPtr,
            GCHandle.ToIntPtr(delegateHandle), out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return NugetMarshal.FromListHandle<string>(result);
    }
    finally
    {
        delegateHandle.Free();
    }
}
```

**Pros:**
- **No thunk**: `[UnmanagedCallersOnly]` methods are called directly — zero managed-to-unmanaged transition overhead.
- `delegate* unmanaged<...>` is a compile-time-safe function pointer type.
- NativeAOT-friendly when parameters are blittable.

**Cons:**
- `[UnmanagedCallersOnly]` methods **must be `static`** and **cannot capture state**. Every capturing lambda must route state through the `userData` context pointer (GCHandle to a delegate). This is more complex than Alternative 1 and defeats the simplicity goal.
- Cannot be applied to lambdas or anonymous methods — requires a named `static` method per callback signature. The generated code would need per-callback-shape static trampolines.
- Requires `unsafe` context or `AllowUnsafeBlocks = true` in the project (`delegate* unmanaged<...>` is an unsafe type).
- Not compatible with .NET Framework; the project targets net8.0 so this is not a hard blocker, but limits portability.
- The `[UnmanagedFunctionPointer]` attribute is required on delegate types when using `Marshal.GetFunctionPointerForDelegate`; with `[UnmanagedCallersOnly]` it is unnecessary. Mixing both patterns in the same codebase adds cognitive overhead.
- No concrete advantage for the v1 use case (per-call callbacks with user lambdas), where the thunk overhead is negligible.

Source: [UnmanagedCallersOnlyAttribute](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.unmanagedcallersonlyattribute)
Source: [Function pointers (C# reference)](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/unsafe-code#function-pointers)

### 3. Vtable-of-function-pointers for interface bridging

For Kotlin interfaces (not just single-function lambdas), passing a C#-implemented object to Kotlin requires representing the full interface as a set of callable function pointers. The standard C pattern is a **vtable struct**: a struct containing one function pointer per method plus a shared context pointer.

```c
// Generated C vtable for `interface Listener { fun onEvent(item: String); fun onDone() }`
typedef struct {
    void (*onEvent)(const char* item, void* context);
    void (*onDone)(void* context);
} ListenerVtable;
```

```kotlin
@CName("subscribe")
fun export_subscribe(
    vtablePtr: COpaquePointer,   // pointer to ListenerVtable struct
    context: COpaquePointer,
) {
    // extract each function pointer from the vtable...
}
```

```csharp
// C# side — structs of function pointers
[StructLayout(LayoutKind.Sequential)]
internal struct ListenerVtable
{
    public IntPtr OnEvent;
    public IntPtr OnDone;
}
```

**Pros:**
- A single Kotlin export handles any implementation of the interface — no per-method exports.
- Closer to how JNI (`JNINativeInterface_`) and COM represent interfaces across a C boundary.

**Cons:**
- Requires generating a C-compatible struct type for every Kotlin interface — a new category of generated types.
- The vtable must be allocated, pinned, and kept alive (with GCHandles per method). More complex lifetime management than a single function pointer.
- Kotlin cannot use the vtable as a real interface object internally — it must be wrapped in a Kotlin class implementing the interface, with each method forwarding to the corresponding function pointer. This is a non-trivial adapter pattern.
- The struct layout must match Kotlin/Native's expectations exactly (alignment, padding). Fragile to change.
- This is a genuinely separate design problem from single-lambda parameters; it warrants its own ADR when interface bridging is implemented.

**Decision**: Deferred. Interface bridging is not the minimal v1 use case and introduces disproportionate complexity. The single-lambda case covers the overwhelming majority of callback use patterns and is the natural starting point.

### 4. Stored callbacks via opaque registry handle

For callbacks that Kotlin stores beyond the call (event handlers, `Observable.subscribe`-style), a persistent registration mechanism is required. The simplest design is a **callback registry**: C# maintains a `ConcurrentDictionary<int, GCHandle>`, issues integer keys, and exposes `nuget_callback_free(key)` as a Kotlin-callable export so Kotlin can release the GCHandle when it unregisters the callback.

Kotlin side:
```kotlin
// When Kotlin stores the callback
val registrationKey: Int = ...  // integer token from C#
// When Kotlin is done with the callback
nuget_callback_free(registrationKey)  // exported from C#
```

**Pros:**
- Kotlin controls when the GCHandle is freed, preventing premature release or leaks.
- Registry is a shared helper (generated once), not per-callback-type.

**Cons:**
- Requires a "free" call from Kotlin into C# — a C# exported function (`[UnmanagedCallersOnly]` or a delegate registered at startup). This is another reverse-interop mechanism.
- Kotlin must be disciplined about calling `nuget_callback_free` — requires explicit lifecycle management on the Kotlin side.
- Out of scope for v1 where the per-call case is the target.

**Decision**: Deferred. Stored callbacks warrant their own ADR alongside the Phase 7 event/observer mapping work.

## Decision

Use **Alternative 1: delegate + `GCHandle.Alloc` + `Marshal.GetFunctionPointerForDelegate`** for all v1 reverse interop callback parameters.

This is consistent with the existing codebase (Flow callbacks in `CirFlowRenderer.kt`, async callbacks in `CirClassRenderer.kt`) and requires zero new infrastructure. The pattern is:

1. Generate a `[UnmanagedFunctionPointer(CallingConvention.Cdecl)]` delegate type per unique callback signature (same arity-based approach as `KotlinFunc<T, R>` / `KotlinAction<T>`).
2. C# wrapper creates a capturing lambda matching the delegate type, wrapping the user's `Func<T, R>` / `Action<T>`.
3. `GCHandle.Alloc(nativeCallback)` pins the delegate.
4. `Marshal.GetFunctionPointerForDelegate(nativeCallback)` produces the `IntPtr` passed to Kotlin.
5. For per-call callbacks: `callbackHandle.Free()` in a `finally` block.
6. The Kotlin export takes the pointer as `COpaquePointer`, reinterprets as `CPointer<CFunction<...>>`, wraps in a Kotlin lambda, and passes that lambda to the underlying Kotlin function.

### Kotlin export signature (per-call lambda parameter)

```kotlin
@CName("{prefix}_{methodName}")
fun export_{prefix}_{methodName}(
    handle: COpaquePointer,         // object StableRef (if class member)
    {regularParams...},
    {paramName}Ptr: COpaquePointer, // one per lambda parameter; carries function pointer
    {paramName}UserData: COpaquePointer, // context pointer paired with function pointer
    errorOut: COpaquePointer?,      // ADR-024 error out-parameter
): {returnType} = try {
    val obj = handle.asStableRef<{ClassName}>().get()
    val {paramName}Fn = {paramName}Ptr.reinterpret<CFunction<({argTypes}) -> {returnType}>>()
    val {paramName}: ({KotlinArgTypes}) -> {KotlinReturnType} = { {args} ->
        // marshal args to COpaquePointer, invoke fn, unmarshal result
        {paramName}Fn.invoke({marshaledArgs}, {paramName}UserData)
        // unmarshal and return
    }
    // call underlying Kotlin function with the lambda
    StableRef.create(obj.{methodName}({params})).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null)
        errorOut.reinterpret<COpaquePointerVar>().pointed.value =
            StableRef.create(buildError(e)).asCPointer()
    {dummyReturn}
}
```

### C# generated wrapper

```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate {NativeReturn} {DelegateName}({nativeParams}, IntPtr userData);

[DllImport("{lib}", CallingConvention = CallingConvention.Cdecl, EntryPoint = "{cname}")]
private static extern {nativeReturnType} Native_{MethodName}(
    IntPtr handle,
    {regularParams},
    IntPtr {paramName}Ptr,
    IntPtr {paramName}UserData,
    out IntPtr error);

public {ReturnType} {MethodName}({params}, {FuncType} {paramName})
{
    {DelegateName} nativeCallback = ({nativeArgs}, userData) =>
    {
        // unmarshal nativeArgs to managed types, call paramName, marshal result
    };
    GCHandle callbackHandle = GCHandle.Alloc(nativeCallback);
    IntPtr fnPtr = Marshal.GetFunctionPointerForDelegate(nativeCallback);
    try
    {
        var result = Native_{MethodName}(_handle, {args}, fnPtr, IntPtr.Zero, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return {unmarshalResult};
    }
    finally
    {
        callbackHandle.Free();
    }
}
```

### Argument marshalling conventions inside the callback

| Kotlin type  | C# callback parameter     | Kotlin wraps as            | C# unwraps with                   |
|--------------|---------------------------|----------------------------|------------------------------------|
| `String`     | `IntPtr`                  | `StableRef.create(str)`    | `NugetMarshal.FromHandle<string>(ptr)` + `NugetMarshal.Dispose(ptr)` |
| Primitive    | Corresponding primitive   | Passed by value            | Passed by value                    |
| Object `T`   | `IntPtr`                  | `StableRef.create(obj)`    | `NugetMarshal.FromHandle<T>(ptr)`  |
| `Boolean`    | `byte` (0 or 1)           | `if (b != 0.toByte())`     | `b != 0`                           |
| `Unit`       | `void`                    | (no return)                | (no return)                        |

When Kotlin creates a `StableRef` for an argument passed INTO the callback (e.g., a `String` item passed from Kotlin to C#), C# is responsible for disposing the `StableRef` before returning from the callback. This mirrors how Flow's `onNext` callback handles items: the Kotlin side creates the `StableRef`, passes the pointer, and expects C# to dispose it.

### Naming convention for generated delegate types

Delegate types are named per their signature shape, following the same convention as `NugetAsyncCallback`, `NugetFlowOnNextCallback`, etc.:

```
Nuget{Arg1}{Arg2}...{Return}Callback
// e.g.:
NugetStringByteCallback   // (IntPtr item, IntPtr userData) -> byte   for (String) -> Boolean
NugetStringCallback       // (IntPtr userData) -> IntPtr              for () -> String
NugetIntIntCallback       // (int a, IntPtr userData) -> int          for (Int) -> Int
```

The generator emits one delegate type per unique signature shape (across all classes), placed in the shared helpers namespace alongside `NugetAsyncCallback`.

### V1 scope

**In v1:**
- Kotlin functions whose **lambda parameters** are `(T) -> R`, `(T) -> Unit`, `() -> R`, `() -> Unit` (arities 0 and 1)
- Element types: primitives (`Int`, `Long`, `Float`, `Double`, `Boolean`, `String`) and object handles
- Per-call callbacks only: the `GCHandle` lives for the duration of the call; no Kotlin-side storage of the function pointer beyond return

**Deferred:**
- Arity 2+ lambda parameters (`(T1, T2) -> R`) — addable once arity-1 is proven; follow the same pattern
- **Stored callbacks** (event handlers, observer registrations) — require the registry pattern (Alternative 4); warrants a dedicated ADR
- **Interface bridging** (vtable-of-function-pointers for full Kotlin interfaces) — Alternative 3; warrants a dedicated ADR
- **`Flow<T>` as a function parameter** — C# provides `IAsyncEnumerable<T>` consumed as Kotlin `Flow`; requires stored callbacks + a more complex push/pull bridge; explicitly deferred in ROADMAP.md
- **Suspend lambda parameters** (`suspend (T) -> R`) — composes with ADR-020 suspend lambda machinery; deferred
- **Exception propagation from the C# callback into Kotlin** — when the C# lambda throws while Kotlin is mid-call, how the failure unwinds across the boundary (mirror of the forward-direction story in ADR-024/028/029) is its own design problem; deferred to a dedicated ADR. v1 callbacks are expected not to throw.

## Consequences

### New CIR nodes needed

- `CirLambdaParameter` — represents a lambda-typed function parameter with its arity, arg types, and return type
- `CirCallbackDelegateHelper` — emits one `[UnmanagedFunctionPointer]` delegate type per unique callback signature in the shared namespace

### New Kotlin export pattern

Exports with lambda parameters are generated by a new `LambdaParameterExports.kt` (or extension to `ClassExports.kt`). They follow the same overall structure as `SuspendFunctionExports.kt` but wrap the function pointer invocation in a Kotlin lambda that forwards the call.

### New C# patterns

- `[UnmanagedFunctionPointer(CallingConvention.Cdecl)]` delegate types in the shared namespace (one per unique arity/type signature, generated once)
- `GCHandle.Alloc` + `Marshal.GetFunctionPointerForDelegate` inside each wrapper method — already used for Flow, now generalised to user-facing callback parameters

### Consistency with existing code

The Flow and async callbacks already use this mechanism. Generalising it to user-facing lambda parameters means:
- `CirFlowRenderer.kt`'s `NugetFlowOnNextCallback` / `NugetFlowOnCompleteCallback` / `NugetFlowOnErrorCallback` are existing examples of generated delegate types
- `GCHandle.Alloc` + `Marshal.GetFunctionPointerForDelegate` usage in `KotlinFlowEnumerator` is the established pattern this ADR formalises

### Breaking changes

None. This adds new exports for functions with lambda parameters. Existing exports are unaffected.

### What the directional asymmetry means for round-tripping

If a Kotlin function returns a lambda (`KotlinFunc<T, R>`) and the same lambda is later passed back into a Kotlin function as a `Func<T, R>` parameter, the two representations do not compose: the `KotlinFunc<T, R>` holds a `StableRef` (opaque handle) but the parameter side expects a `Func<T, R>` (which becomes a C# delegate function pointer). Round-tripping identity is lost. This is a known limitation documented in ADR-012. A callback registry mapping function pointer tokens back to `StableRef` handles would be required to solve this; it is out of scope for v1.

## Amendment (2026-09-11): the primitive row was never implemented on the per-call route

The marshalling table above has said since this ADR was written that a **primitive crosses by
value**, in both directions. The per-call lambda parameter route never did that. Both halves boxed:

- Kotlin (`LambdaParameterExports.kt`) spelled `COpaquePointer?` for every argument of the
  `CFunction<...>` it reinterprets, and `NugetHandles.retain(it0)`-ed the payload into a `StableRef`
  before each invocation, releasing it after.
- C# (`CirClassTranslator.translateCallbackMethod`) declared `IntPtr arg0Ptr` for every delegate
  parameter and read it back with `NugetMarshal.FromHandle<Int>(arg0Ptr)`, naming a Kotlin type that
  does not exist in C#.

Neither half compiled, so no consumer ever reached the boxing:

- `(Boolean) -> Unit` failed the **Kotlin** compile of the generated file. The wrapper body already
  bound a `Byte` for a Boolean payload (the table's `byte` row) while the `CFunction` type said
  `COpaquePointer?`: `Argument type mismatch: actual type is 'Byte'`.
- `(Int) -> Unit` failed the **C#** compile at the lambda's shape, not its body: CS1661/CS1678,
  `nint` against `int`, because a lambda's convertibility to a delegate is checked before the body
  that would have produced CS0246 on `FromHandle<Int>`.

Implemented per the table. The precedent is in this repository and predates the fix: the
interface-bridge route (`InterfaceBridgeExports.kt`, `CirClassTranslator.translateBridgeMethod`)
already emits `CFunction<(Int, ..., COpaquePointer) -> Unit>` on the Kotlin side and
`int arg0` with no unmarshal on the C# side. The per-call route now mirrors it.

`Boolean` is folded into the same branch rather than left as a sibling defect: it is a by-value
payload whose wire type is not itself, so the rewritten branch has to answer for it either way. It
crosses as `byte arg0Byte` and the C# body widens it, `bool arg0 = arg0Byte != 0;`. The wire
parameter is deliberately named `arg0Byte` and not `arg0`: declaring `bool arg0` from a parameter
already called `arg0` is CS0128.

`String` stays on the handle path (it is not a C ABI scalar) and so does `Char`, which has no
crossing convention on any route.

### The delegate shapes are shared on purpose

`Nuget{Int,Byte,Double,...}VoidCallback` is registered first-wins across routes, so the per-call
route's `Int` payload lands on exactly the `(int, IntPtr)` shape the ADR-037 stored-callback route
already registers for an enum ordinal, and the interface-bridge route registers for a primitive
parameter. One declaration, one `[UnmanagedCallersOnly]` thunk, three routes. Had the per-call route
picked any other shape, whichever route lost the registration race would emit a lambda that cannot
convert to the delegate the winner declared. `Tier1PrimitiveLambdaParameterTest` puts both routes in
one fixture for that reason.

### Left as a named sibling, not fixed here

The **stored**-callback route's primitive payload (`fun addTickListener(listener: (Int) -> Unit)`)
still boxes: its `storedArgSuffix` tests the *qualified* type name against a simple-name keyed table,
so a primitive falls through to the `Object` suffix and a `StableRef`. Its own route, its own item.
(The triple release this paragraph originally predicted for that boxed handle is fixed by the
amendment below; what is left is the boxing itself, which costs a handle round trip per call.)

## Amendment (2026-09-11): one owner per handle-passed callback payload

A callback argument that crosses as a handle was freed by **both** sides. The count each route left
behind, per crossing, measured by `LeakTests` (`NugetMarshal.LiveHandles`, ADR-120):

| Route | Retains | Frees | Net per crossing |
|---|---|---|---|
| Per-call lambda parameter | Kotlin, once | C# (`FromHandle` or the wrapper) + Kotlin, after the invoke | -1 |
| Stored callback (ADR-037), interface bridge | Kotlin, once | `FromHandle` + the thunk's explicit `NugetMarshal.Dispose(argPtr)` + Kotlin | -2 |

A negative delta is a use-after-free, not a leak: the second free runs against a `StableRef` that no
longer exists, and the third against one already freed twice.

**The rule: on a handle-passed callback payload, the C# side owns the free. Kotlin retains for the
duration of the crossing and never releases.** It falls out of what the C# unwrap already does, and
it is the same sentence for both payload kinds:

- a **`String`** (and every other marshalled kind) is read by `NugetMarshal.FromHandle<T>`, whose
  branches each call `Native_dispose(handle)` immediately after reading the value. The handle is
  gone before the thunk returns, so there is nothing left for Kotlin to release.
- an **exported object** falls through to `NugetMarshal.Materialize<T>`, which hands the raw handle
  to the wrapper's `new T(handle)` constructor. The wrapper is the object the consumer's lambda
  receives, and its `Dispose()` frees the handle. That is exactly the `using var t = toy;` this ADR
  documents in a callback body.

The explicit `NugetMarshal.Dispose(argPtr)` the stored-callback and interface-bridge thunks emitted
after `FromHandle` is removed for the same reason: `FromHandle` is the owner, on every route.

A **by-value primitive** is untouched. It never had a handle, so it has no owner to pick.

The callback's *return* box goes the other way and is unchanged: nothing on the C# side frees the
`WrapString` handle a callback hands back, so Kotlin still releases it after reading it.

### Residual: an undisposed object payload leaks one handle

A generated wrapper has a `Dispose()` and **no** finalizer or `SafeHandle`, so a consumer whose
callback body does not dispose the object it is handed keeps that handle alive for the life of the
process. That is a deliberate choice of the lesser fault: the alternative rule (Kotlin frees, the
wrapper does not own) makes the wrapper hold a dangling handle for the rest of the callback body and
turns the documented `using` into a double free. Leaking one handle is diagnosable through
`NugetMarshal.LiveHandles`; a use-after-free is not. Giving the wrappers a finalizer that frees an
undisposed handle would close the residual and is a separate decision, since it puts native frees on
the finalizer thread for every wrapper the bridge mints, not only callback payloads.

### Verification

`LeakTests` rows 8g/8h/8i (per-call route: sealed-arm `String`, ordinary-class `String`,
object payload) and row 8j (interface-bridge `String`) hammer 50 crossings each and assert the
tracked count returns to its baseline. Before the fix 8j read `delta -100` over 50 crossings, which
is the -2 the table above predicts. `Tier1CallbackPayloadOwnershipTest` pins the generated shape on
all three routes: no `NugetHandles.release(arg...)` after an invoke, no `NugetMarshal.Dispose(arg...)`
after a `FromHandle`, both retains and the result-box release still in place, and the by-value
primitive row unchanged.

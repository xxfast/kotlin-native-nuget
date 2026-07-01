# ADR-039: Interface bridging — C# implementing Kotlin interfaces via N flat function pointers

## Status

Accepted

## Context

ADR-036 introduced reverse interop for single-lambda parameters (`(T) -> R`) and explicitly deferred
its **Alternative 3** — "vtable-of-function-pointers for interface bridging":

> *"For Kotlin interfaces (not just single-function lambdas), passing a C#-implemented object to Kotlin
> requires representing the full interface as a set of callable function pointers. [...] This is a
> genuinely separate design problem from single-lambda parameters; it warrants its own ADR when
> interface bridging is implemented."*

ADR-037 added stored single-lambda callbacks via `add*/remove*` pair detection and the
`IDisposable`-subscription pattern.

### What is missing

When a Kotlin API has a non-generic interface parameter — not `T : Listener` (generic constraint)
but a plain `fun register(listener: Listener)` — and the consumer wants to pass a **C#-implemented
object** that satisfies the interface, neither the existing handle-extraction path (for Kotlin-backed
objects) nor the single-lambda path (ADR-036/037) applies. The consumer needs to write a C# class
implementing the generated `IListener` and hand it to Kotlin, which then calls multiple methods on it
over its lifetime.

The `BidirectionalTests.cs` file already shows this intent with a `Dog : IPet` class that compiles
but cannot currently be passed to any Kotlin function that stores the object, because the generated
bridge only extracts `_handle` (which `Dog` does not have).

### How Kotlin/Native represents interfaces at the C boundary

Kotlin/Native's `@CName` export mechanism only supports flat C-compatible parameter types:
`COpaquePointer`, primitives, and `CPointer<CFunction<...>>`. There is no way to pass a struct
pointer typed to a Kotlin interface — Kotlin has no ABI type for "an object implementing Listener"
at the C level. Everything goes through `COpaquePointer`.

A Kotlin export can receive any number of `COpaquePointer` parameters and `reinterpret` each as
`CPointer<CFunction<...>>` to obtain a callable function pointer. This is the same mechanism already
used by `addMoodListener` in ADR-037 (one function pointer per stored-callback pair) and by Flow
and async exports in ADR-026/019 (three function pointers for `onNext`/`onComplete`/`onError`).

Source: [Kotlin/Native C interop — Callbacks](https://kotlinlang.org/docs/native-c-interop.html#callbacks)

### How other Kotlin interop targets handle this direction

#### ObjC/Swift Export (built-in)

Kotlin interfaces are exported as Objective-C `@protocol` declarations. An Objective-C class can
conform to the protocol and be passed back to Kotlin — the ObjC runtime dispatches method calls via
`objc_msgSend` (its own vtable mechanism). ARC handles lifetime. No explicit function pointers are
involved: the ObjC runtime IS the vtable and dispatch table.

Source: [Kotlin/Native ObjC interop — Subclassing](https://kotlinlang.org/docs/native-objc-interop.html)

This is the closest analogue: N function pointers exposed through `objc_msgSend` per method. At a
raw C boundary we must make these explicit.

#### JVM / Java

Kotlin interfaces compile to JVM interfaces. A Java class can implement the interface and be passed
to Kotlin because both sides share the JVM heap and GC. No function pointer or pinning is needed.
Not applicable at a C boundary.

#### Kotlin/JS

Uses `external interface` for structural typing of JavaScript objects. The JS GC manages both sides.
No vtable or function pointer at all.

#### Kotlin/Wasm

`JsReference<T>` wraps Kotlin objects as opaque references for JS consumption; the reverse
(JS-implemented Kotlin interface) is not supported — only `external` interfaces describe JS objects
structurally.

#### SKIE (Touchlab)

SKIE generates additional Swift wrappers on top of ObjC headers. The reverse direction (Swift
implementing Kotlin protocol) is handled the same way as plain ObjC/Swift export: via `objc_msgSend`.
SKIE does not add a custom vtable mechanism.

#### Summary for C boundary

Every other target benefits from a shared runtime (JVM GC, JS GC) or a compiler-managed dispatch
mechanism (ObjC `objc_msgSend`). The C boundary has neither. The only way to cross it is with raw
function pointers per method. The N-flat-function-pointers design is the direct equivalent of
`objc_msgSend` but made explicit.

### What's idiomatic in C#

C# consumers expect to write a class implementing a generated interface and pass an instance of that
class. This mirrors the COM Callable Wrapper (CCW) pattern: when a .NET object is exposed to COM,
the CLR generates a CCW that exposes the object's interface as a vtable — one function pointer per
method. The bridge generator does the same thing by hand, generating one delegate per method and
pinning each with `GCHandle.Alloc`.

The `IDisposable` subscription token (introduced in ADR-037) is the standard .NET pattern for
controlling the lifetime of a registered observer. It extends naturally to interface registrations.

Source: [COM callable wrapper (CCW)](https://learn.microsoft.com/en-us/dotnet/standard/native-interop/com-callable-wrapper)
Source: [IDisposable](https://learn.microsoft.com/en-us/dotnet/api/system.idisposable)

### The reconciliation question: same `IListener` for both directions?

The existing Kotlin→C# interface mapping produces `public interface ICatEventListener : IDisposable`
backed by a Kotlin handle. When Kotlin returns a `CatEventListener`, C# wraps it in an
`ICatEventListener`-implementing class that holds `_handle`.

A C#-implemented class like `class MyCatListener : ICatEventListener` does NOT have `_handle`. The
generated bridge for generic-constrained parameters (`fun <T : CatEventListener> process(listener: T)`)
currently uses reflection to extract `_handle`, which fails for `MyCatListener`.

This ADR resolves the split by keeping the **same generated `ICatEventListener` interface for both
directions** but generating two distinct export paths:

- `fun <T : CatEventListener> process(listener: T)` → **handle-extraction path** (existing): generic
  constraint; the bridge extracts `_handle` via reflection. Only Kotlin-backed objects should be
  passed via this path.
- `fun addListener(listener: CatEventListener)` / `fun removeListener(listener: CatEventListener)` →
  **function-pointer path** (new): non-generic interface parameter with paired add/remove naming;
  detects C#-implemented objects and creates per-method delegates.

The two paths are **different Kotlin function signatures** and therefore different generated C# methods.
The consumer passes their `MyCatListener` to `AddListener()` (function-pointer path), not to the
generic `Process<T>()` (handle-extraction path). No runtime dispatch between the two paths is needed
in v1.

A C#-implemented `MyCatListener` CAN be passed to the function-pointer path. A Kotlin-backed
`ICatEventListener` wrapper technically can also be passed, but this creates double-bridging (Kotlin
calls C# thunk which calls Kotlin object). This is correct but inefficient. In v1, document it as a
known limitation and recommend the generic-constraint path for Kotlin-backed objects.

## Alternatives Considered

### 1. N flat function pointers + shared context per method (chosen)

For a Kotlin interface with M methods, the generated Kotlin export takes 2M flat `COpaquePointer`
parameters (one fnPtr + one context per method) alongside the usual object handle and error-out
parameters.

```kotlin
// For interface CatEventListener { fun onMeow(message: String); fun onPurr() }
@CName("catEventSource_addListener")
fun export_catEventSource_addListener(
    handle: COpaquePointer,
    onMeowPtr: COpaquePointer,
    onMeowCtx: COpaquePointer,
    onPurrPtr: COpaquePointer,
    onPurrCtx: COpaquePointer,
    errorOut: COpaquePointer?,
): COpaquePointer? = try {
    val obj = handle.asStableRef<CatEventSource>().get()
    val onMeowFn = onMeowPtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> Unit>>()
    val onPurrFn = onPurrPtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()

    val bridge = object : CatEventListener {
        override fun onMeow(message: String) {
            val ref = StableRef.create(message).asCPointer()
            onMeowFn.invoke(ref, onMeowCtx)
            ref.asStableRef<String>().dispose()
        }
        override fun onPurr() {
            onPurrFn.invoke(onPurrCtx)
        }
    }

    obj.addListener(bridge)
    val unregister: () -> Unit = { obj.removeListener(bridge) }
    StableRef.create(unregister).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null)
        errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(buildError(e)).asCPointer()
    null
}

@CName("catEventSource_removeListener")
fun export_catEventSource_removeListener(handle: COpaquePointer, subscriptionHandle: COpaquePointer) {
    val ref = subscriptionHandle.asStableRef<() -> Unit>()
    ref.get().invoke()
    ref.dispose()
}
```

The Kotlin bridge object is an anonymous `object : CatEventListener` — a real Kotlin object that
satisfies the interface and can be passed to `addListener`/`removeListener` by reference. The
`unregister` lambda closes over the bridge object (preserving referential identity for list removal)
and is stored as a `StableRef` returned as the subscription handle. This is identical to the pattern
generated for `addMoodListener` in ADR-037, extended from one function pointer to M function pointers.

**C# side (generated):**

```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate void NugetStringVoidCallback(IntPtr arg0Ptr, IntPtr userData);

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate void NugetVoidCallback(IntPtr userData);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catEventSource_addListener")]
private static extern IntPtr Native_AddListener(
    IntPtr handle,
    IntPtr onMeowPtr, IntPtr onMeowCtx,
    IntPtr onPurrPtr, IntPtr onPurrCtx,
    out IntPtr error);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catEventSource_removeListener")]
private static extern void Native_RemoveListener(IntPtr handle, IntPtr subscriptionHandle);

public IDisposable AddListener(ICatEventListener listener)
{
    if (_handle == IntPtr.Zero) throw new ObjectDisposedException(nameof(CatEventSource));

    NugetStringVoidCallback onMeowCb = (msgPtr, _) =>
    {
        string message = NugetMarshal.FromHandle<string>(msgPtr);
        NugetMarshal.Dispose(msgPtr);
        listener.OnMeow(message);
    };
    NugetVoidCallback onPurrCb = _ => listener.OnPurr();

    GCHandle h1 = GCHandle.Alloc(onMeowCb);
    GCHandle h2 = GCHandle.Alloc(onPurrCb);

    IntPtr sub = Native_AddListener(
        _handle,
        Marshal.GetFunctionPointerForDelegate(onMeowCb), IntPtr.Zero,
        Marshal.GetFunctionPointerForDelegate(onPurrCb), IntPtr.Zero,
        out IntPtr error);

    if (error != IntPtr.Zero) { h1.Free(); h2.Free(); throw NugetErrorNative.BuildException(error); }

    return new NugetSubscription(() =>
    {
        Native_RemoveListener(_handle, sub);
        h1.Free();
        h2.Free();
    });
}
```

**Consumer API (what C# developers write):**

```csharp
private class ConsoleCatListener : ICatEventListener
{
    public void OnMeow(string message) => Console.WriteLine(message);
    public void OnPurr() => Console.WriteLine("Purr...");
    public void Dispose() { } // no-op; this is a C# object
}

using var source = new CatEventSource("Oreo");
using IDisposable sub = source.AddListener(new ConsoleCatListener());
source.Trigger(); // Kotlin calls OnMeow then OnPurr via function pointers
// sub.Dispose() → unregisters the listener
```

**Pros:**
- Direct extension of the existing `addMoodListener` stored-callback pattern (ADR-037). The only new
  element is M function pointers instead of 1.
- No struct layout definition, no struct pinning. Each function pointer is an independent
  `GCHandle.Alloc` with the same well-understood lifetime as existing callbacks.
- Kotlin bridge is a real anonymous `object` — idiomatic Kotlin, no raw pointer manipulation inside
  the Kotlin body.
- Detection logic for `add*/remove*` pairing reuses the same KSP detector as ADR-037.
- The delegate type naming convention (`NugetStringVoidCallback`, `NugetVoidCallback`) reuses the
  existing convention from ADR-036 — if the same signature is already generated for a lambda
  parameter, no new delegate type is needed.
- Per-call interface parameters (no `remove*` pair) also work: treat as per-call callbacks, free all
  GCHandles in `finally`, return the method's actual return type.

**Cons:**
- The Kotlin export parameter count grows as 2M (function count of the interface grows). An interface
  with 5 methods generates a 13-parameter export (handle + 10 fn+ctx pairs + errorOut). This is
  verbose but not incorrect — C does not limit the number of parameters.
- When M > ~4 the flat-parameter list becomes harder to read in the generated Kotlin. Acceptable as
  generated code.

### 2. Vtable struct (a C struct with one function pointer per method)

A C-compatible struct is defined with one `IntPtr` field per interface method plus a shared context
field. The Kotlin export takes a single `COpaquePointer` (pointer to the struct) and reinterprets
each field as a `CPointer<CFunction<...>>`.

```c
// Conceptual C definition (not actually generated, Kotlin side reads fields by offset)
typedef struct {
    void (*onMeow)(const char* message, void* ctx);
    void (*onPurr)(void* ctx);
    void* ctx;
} CatEventListenerVtable;
```

```csharp
[StructLayout(LayoutKind.Sequential)]
internal struct CatEventListenerVtable
{
    public IntPtr OnMeow;
    public IntPtr OnPurr;
    public IntPtr Context;
}
```

The struct must be pinned in memory (`GCHandle.Alloc(vtable, GCHandleType.Pinned)`) so Kotlin can
hold a stable pointer to it for the lifetime of the subscription.

**Pros:**
- Single extra parameter on the export regardless of method count (vtablePtr + one possible context).
- More COM-like: mirrors how the CLR's COM callable wrapper exposes .NET objects to native code.

**Cons:**
- The C# struct layout must match exactly what Kotlin reads by pointer arithmetic. Changing method
  order or adding fields breaks binary compatibility silently. The N-flat-params approach has no such
  fragility: each function pointer is an explicit named parameter.
- Pinning a struct with `GCHandleType.Pinned` is a heavier operation than a normal `GCHandle.Alloc`
  (it physically pins the struct in the managed heap). Each delegate still needs its own
  `GCHandle.Alloc(delegate)` to keep the delegates alive. Total: 1 struct pin + M delegate pins.
  The N-flat-params approach has M delegate pins only.
- A new C# struct type per interface is a new category of generated types. The N-flat-params approach
  reuses the existing `[UnmanagedFunctionPointer]` delegate types.
- Kotlin must read the struct fields by index using `reinterpret` + pointer arithmetic or a cinterop
  def-file struct mapping. Without a def-file, field access by offset requires manual `pointed.memberX`
  navigation that is fragile and not verifiable at compile time.
- Not simpler for the common case (M = 2–4 methods). The simplicity argument applies only for very
  large interfaces which are rare.

### 3. Separate adapter class (`ListenerAdapter`) — consumer wraps delegates explicitly

Generate a concrete `CatEventListenerAdapter` class (not the `ICatEventListener` interface) whose
constructor takes one `Action<string>` and one `Action`:

```csharp
// Generated
public sealed class CatEventListenerAdapter : IDisposable
{
    private readonly Action<string> _onMeow;
    private readonly Action _onPurr;
    internal CatEventListenerAdapter(Action<string> onMeow, Action onPurr)
    {
        _onMeow = onMeow; _onPurr = onPurr;
    }
    internal void OnMeow(string message) => _onMeow(message);
    internal void OnPurr() => _onPurr();
    public void Dispose() { }
}

// Consumer
using IDisposable sub = source.AddListener(new CatEventListenerAdapter(
    onMeow: msg => Console.WriteLine(msg),
    onPurr: () => Console.WriteLine("Purr...")));
```

**Pros:**
- Adapter class holds the delegates cleanly; no C#-class-implementing-interface needed.
- Clear ownership: the adapter IS the lifetime token (or wraps it).

**Cons:**
- Consumer cannot use their own class hierarchy. A `class MyService : ICatEventListener` cannot be
  passed directly; it must be wrapped in an adapter. This is a worse API than Alternative 1.
- Introduces a second distinct type (`CatEventListenerAdapter`) alongside the generated `ICatEventListener`.
  Two types for one Kotlin interface is confusing for IntelliSense.
- The existing `BidirectionalTests.cs` expectation (`class Dog : IPet`) is not satisfied — Dog cannot
  be passed to a function that takes `CatEventListenerAdapter`.
- Delegates inside the adapter still need `GCHandle.Alloc` to stay alive, so the lifetime complexity
  is the same as Alternative 1 without the API benefit.

## Decision

Use **Alternative 1: N flat function pointers + shared context per method**.

This extends the existing stored-callback pattern from ADR-037 (which already uses 1 fnPtr + 1 context
for single-lambda callbacks) to M function pointers for M-method interfaces. The Kotlin bridge
generates an anonymous `object : Interface` that delegates each method to its function pointer. The
C# bridge creates one `[UnmanagedFunctionPointer]` delegate per interface method, pins each with
`GCHandle.Alloc`, and keeps them alive via `NugetSubscription`.

### Detection rule (parallel to ADR-037)

The KSP processor identifies an interface-bridging pair when all three conditions hold in the same
class:

1. A public method `add{X}` (or `subscribe{X}`) with exactly one parameter of a Kotlin interface type
   `I` and return type `Unit`.
2. A public method `remove{X}` (or `unsubscribe{X}`) with exactly one parameter of the same interface
   type `I` and return type `Unit`.
3. `{X}` is the same suffix.

If no matching `remove*` method exists, the interface parameter is treated as **per-call** (all GCHandles
freed in `finally`, method returns the Kotlin function's actual return type rather than `IDisposable`).

Libraries that do not follow this naming convention can opt in explicitly with the
`@NugetStoredCallback(removeFunction = "...")` annotation (same as ADR-037).

### Kotlin export pattern (generated, for `add{X}` / `remove{X}` pair)

```kotlin
// Bridge object anonymous class — created once per registration
// Stored-callback export (returns subscription handle StableRef)
@CName("{prefix}_add{X}")
fun export_{prefix}_add{X}(
    handle: COpaquePointer,
    // For each method m with arity-0: mPtr: COpaquePointer, mCtx: COpaquePointer
    // For each method m with arity-1 (String/object): mPtr: COpaquePointer, mCtx: COpaquePointer
    // For each method m with arity-1 (primitive): mPtr: COpaquePointer, mCtx: COpaquePointer
    errorOut: COpaquePointer?,
): COpaquePointer? = try {
    val obj = handle.asStableRef<{ClassName}>().get()
    // Reinterpret each fnPtr to its typed CFunction
    val bridge = object : {Interface} {
        // override each method: marshal args → invoke fn → unmarshal
    }
    obj.add{X}(bridge)
    val unregister: () -> Unit = { obj.remove{X}(bridge) }
    StableRef.create(unregister).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null)
        errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(buildError(e)).asCPointer()
    null
}

@CName("{prefix}_remove{X}")
fun export_{prefix}_remove{X}(handle: COpaquePointer, subscriptionHandle: COpaquePointer) {
    val ref = subscriptionHandle.asStableRef<() -> Unit>()
    ref.get().invoke()
    ref.dispose()
}
```

### Argument marshalling for interface method parameters (callback direction: Kotlin→C#)

Same table as ADR-036 and ADR-037:

| Kotlin type | C# callback param  | Kotlin wraps as          | C# unwraps with                          |
|-------------|-------------------|--------------------------|------------------------------------------|
| `String`    | `IntPtr`          | `StableRef.create(str)`  | `NugetMarshal.FromHandle<string>(ptr)` + `NugetMarshal.Dispose(ptr)` |
| Primitive   | Corresponding primitive | Passed by value      | Passed by value                          |
| Object `T`  | `IntPtr`          | `StableRef.create(obj)`  | `NugetMarshal.FromHandle<T>(ptr)`        |
| `Boolean`   | `byte`            | `if (b != 0.toByte())`   | `b != 0`                                 |
| `Unit`      | (void, no return) | (no return)              | (no return)                              |

### Reconciliation with existing Kotlin→C# interface mapping

The generated `ICatEventListener` interface remains **unchanged** in its declaration. It continues to
be implemented by Kotlin-backed wrapper classes (the existing Kotlin→C# direction).

The distinction is at the **method signature level** in the Kotlin source:

- `fun <T : CatEventListener> wrap(l: T): T` (generic constraint) → handle-extraction path exists,
  unchanged.
- `fun addListener(l: CatEventListener)` + `fun removeListener(l: CatEventListener)` (non-generic,
  paired) → function-pointer path (this ADR).

C# consumers implementing `ICatEventListener` and passing instances to `AddListener` are the primary
target. Passing a Kotlin-backed `ICatEventListener` wrapper to `AddListener` works but incurs
double-bridging (Kotlin → C# thunk → Kotlin object); this is a documented v1 limitation.

### Sample Kotlin API (to add to sample-library)

New file `CatEventListener.kt` in `sample/cat`:

```kotlin
interface CatEventListener {
    fun onMeow(message: String)
    fun onPurr()
}
```

New file `CatEventSource.kt` in `sample/cat`:

```kotlin
class CatEventSource(val name: String) {
    private val listeners: MutableList<CatEventListener> = mutableListOf()

    fun addListener(listener: CatEventListener) { listeners.add(listener) }
    fun removeListener(listener: CatEventListener) { listeners.remove(listener) }

    fun trigger() {
        val msg = "$name says meow!"
        listeners.forEach { it.onMeow(msg) }
        listeners.forEach { it.onPurr() }
    }
}
```

The `add*/remove*` naming follows the convention already established in `Cat.kt` for
`addMoodListener`/`removeMoodListener`. The interface has two methods (arity-0 and arity-1) to
exercise the multi-method case that single-lambda callbacks cannot cover.

### Expected C# consumer API

```csharp
// Consumer's C#-implemented class
private class ConsoleCatListener : ICatEventListener
{
    private readonly string _prefix;
    public ConsoleCatListener(string prefix) { _prefix = prefix; }
    public void OnMeow(string message) => Console.WriteLine($"{_prefix}: {message}");
    public void OnPurr() => Console.WriteLine($"{_prefix}: Purr...");
    public void Dispose() { } // no-op: C# object, no native resource
}

// Usage
using var source = new CatEventSource("Oreo");

// Register with IDisposable subscription
using IDisposable sub = source.AddListener(new ConsoleCatListener("Listener1"));
source.Trigger();  // → "Listener1: Oreo says meow!" + "Listener1: Purr..."

sub.Dispose();     // unregisters; no more callbacks
source.Trigger();  // → (no output)
```

## Consequences

### New CIR nodes needed

- `CirInterfaceMethod` already exists in `CirModel.kt`. No new model types are needed.
- `CirInterfaceBridgeMethod` — represents one side of an interface-bridging pair with fields:
  `interfaceName`, `addEntryPoint`, `removeEntryPoint`, `methods: List<CirInterfaceMethod>`.
  Similar to `CirStoredCallbackMethod` (ADR-037) but iterates over the interface's method list
  rather than a single function pointer.
- No new C# shared helper types are needed: `NugetSubscription` (ADR-037) is reused.

### New Kotlin export pattern

`InterfaceBridgeExports.kt` (new file, parallel to `StoredCallbackExports.kt`):
- Detects `add*/remove*` pairs where the parameter type is a Kotlin interface (not a lambda type).
- Emits 2 exports per pair: `_add{X}` (returns StableRef to unregister lambda) and `_remove{X}`
  (identical to ADR-037's `_remove`).
- The bridge anonymous object is an `object : {Interface}` that reinterprets each `COpaquePointer`
  parameter as the appropriate `CFunction<...>` type.
- Per-call interface parameters (no `remove*`) emit a single export with all GCHandles freed in the
  try-finally block.

### New C# patterns

- `public IDisposable Add{X}(I{Interface} listener)` — generated per detected bridge pair.
- Delegate type reuse: the delegate types for interface methods (`NugetStringVoidCallback`,
  `NugetVoidCallback`, etc.) follow the same naming convention as ADR-036/037 and may reuse
  already-generated types if the signatures match.
- The `Native_Remove{X}` pattern is identical to ADR-037's `Native_Remove*` — the subscription
  handle is the opaque StableRef from the add export.

### Breaking changes

None. All existing generated outputs are unchanged. Interface-bridging pairs produce new C# methods
(`AddListener`) only for Kotlin functions that follow the `add*/remove*` convention with an interface
parameter type (previously not mapped at all).

### Scope

**In v1:**
- Stored interface bridging with `add{X}(listener: I)` / `remove{X}(listener: I)` pairing.
- `subscribe{X}` / `unsubscribe{X}` naming variant (parallel to ADR-037).
- Per-call interface parameters (no paired remove): treated as per-call; GCHandles freed in `finally`.
- Interface methods: arity 0 and 1, Unit-returning only.
- Method parameter types: `String`, primitives (`Int`, `Long`, `Float`, `Double`, `Boolean`), and
  object handles — same set as ADR-036/037.
- Generated `ICatEventListener` serves both directions (Kotlin→C# wrapping and C#→Kotlin bridging).

**Deferred:**
- Interface methods returning non-Unit values — requires marshalling the return value from C# back to
  Kotlin through the function pointer (reverse of the forward direction); adds complexity and a new
  "C#-to-Kotlin return" marshalling path; deferred.
- Interface methods with 2+ parameters — straightforward extension of the N-flat-params pattern but
  widens the tested matrix; deferred until arity-1 is proven.
- Interface properties (getter/setter) — each property generates 1–2 function pointers; the
  detection rule needs to be extended to cover property-bearing interfaces; deferred.
- `suspend` interface methods — require composing with the coroutine bridge (ADR-019); deferred.
- Interfaces with no paired `remove*` where Kotlin stores the object indefinitely — requires an
  explicit annotation (`@NugetInterfaceParam(lifetime = Stored)`) so the generator knows it cannot
  use the per-call pattern; deferred.
- C# passing a Kotlin-backed `ICatEventListener` wrapper to `AddListener` without double-bridging
  — requires detecting `_handle` at runtime and using a separate export path; deferred.
- Exception propagation from C# interface method back to Kotlin — mirror of ADR-024 for callbacks;
  explicitly tracked on ROADMAP line 103; deferred.
- `@NugetStoredCallback(removeFunction = "...")` annotation support for non-standard naming —
  straightforward once the base case is proven; deferred.

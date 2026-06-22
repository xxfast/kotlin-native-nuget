# ADR-024: Synchronous exception propagation — error out-parameter for non-suspend functions

## Status

Proposed

## Context

ADR-023 established how exceptions propagate across the bridge for **async (suspend) functions**: the Kotlin side catches exceptions inside the coroutine and passes a `StableRef<Pair<String, String>>` (type name + message) through the callback's error pointer. The C# side extracts the error via `NugetErrorNative.Type/Message` and throws `KotlinException`.

**Synchronous functions have no such mechanism.** When a non-suspend `@CName` export throws a Kotlin exception, Kotlin/Native calls `terminateWithUnhandledException` and the process aborts. There is no C-level interception — Kotlin/Native does not use C++ exceptions, so `catch (...)` on the C side does not help.

### How ObjC/Swift Export handles this

Kotlin/Native's ObjC export uses an **opt-in model**: only functions annotated with `@Throws` get exception propagation. The compiler generates an `NSError**` out-parameter for annotated functions. Without `@Throws`, exceptions terminate the process.

This is a deliberate API contract — the Kotlin library author declares which functions may throw, and the ObjC/Swift consumer handles the `NSError` accordingly.

### Should we mirror the `@Throws` opt-in?

The question is whether this project should require `@Throws` annotations for sync exception propagation, matching ObjC export's behavior.

**Arguments for mirroring ObjC (opt-in with `@Throws`):**
- Consistent with Kotlin/Native's existing interop philosophy.
- Forces the library author to be intentional about the exception contract.
- Smaller generated code — only annotated functions get the try/catch wrapper.

**Arguments against (wrap all functions):**
- Most Kotlin developers do not use `@Throws` — it exists primarily for ObjC/Swift interop. Requiring it for C# interop adds a new annotation burden.
- This project's Goal #1 is **"Plug and play — just add the plugin, configure the target and publish your library as a NuGet package."** Requiring manual annotations breaks this.
- The KSP processor already generates all `@CName` wrappers automatically — there is no manual annotation step today. Adding an `@Throws` requirement would be a regression in developer experience.
- A process crash from an unhandled Kotlin exception is a terrible experience for C# consumers who have no control over the Kotlin source code.
- The try/catch overhead on the happy path is negligible in Kotlin/Native — cost is only paid on the exception path.

### Error out-parameter pattern

The standard C-level pattern for propagating errors from a callee to a caller is an **error out-parameter** — the caller passes a pointer, and the callee writes the error through it on failure. This is exactly what ObjC's `NSError**` does, and it maps cleanly to C#'s `out IntPtr`.

Three alternatives were evaluated:

**Option A — Error out-parameter** (chosen)

Every sync export gets a trailing `COpaquePointer?` parameter. The body is wrapped in try/catch. On catch, a `StableRef<Pair<String, String>>` is written through the pointer. The return value is a dummy (zero/null) when the error is set.

**Pros:**
- Mirrors ObjC's `NSError**` ABI — the most well-understood native error propagation pattern.
- The error channel is explicit in the function signature — no hidden state.
- Reuses the existing `StableRef<Pair<String, String>>` encoding and `NugetErrorNative` infrastructure from ADR-023.
- Thread-safe: no shared mutable state between calls.

**Cons:**
- Every P/Invoke declaration gains an extra `out IntPtr error` parameter.
- Every wrapper method has a 3-line error-check block. This is generated code, so verbosity is acceptable.

**Option B — Thread-local error**

Store the error in a Kotlin `@ThreadLocal` variable. The C# side calls a `nuget_get_last_error()` export after each call.

**Rejected:** Fragile. Requires an extra native call after every function invocation (even on the success path). The "remember to check" contract is error-prone. Thread-local semantics in Kotlin/Native interact poorly with .NET's thread pool scheduler.

**Option C — Wrapper struct return**

Return a C struct `{ void* result; void* error; }` for all functions.

**Rejected:** Kotlin/Native's `@CName` functions cannot return structs by value — only primitives and pointers. Returning a struct pointer requires heap allocation on every call (even the success path), which is worse than the out-parameter.

## Decision

**Wrap all sync function exports with try/catch and an error out-parameter. Do not require `@Throws`.**

This departs from ObjC export's opt-in model in favor of safety-by-default, consistent with the project's plug-and-play design goal.

### Kotlin export pattern

All sync `@CName` exports change from:

```kotlin
@CName("add")
fun export_add(a: Int, b: Int): Int = add(a, b)
```

to:

```kotlin
@CName("add")
fun export_add(a: Int, b: Int, errorOut: COpaquePointer?): Int = try {
    add(a, b)
} catch (e: Throwable) {
    if (errorOut != null) {
        errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(
            Pair(
                e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
                e.message ?: "Kotlin error"
            )
        ).asCPointer()
    }
    0 // dummy return — ignored when errorOut is set
}
```

For `Unit`-returning functions, the return type stays `Unit` (void in C) and the dummy return is omitted:

```kotlin
@CName("greet")
fun export_greet(name: String, errorOut: COpaquePointer?): Unit = try {
    greet(name)
} catch (e: Throwable) {
    if (errorOut != null) {
        errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(
            Pair(
                e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
                e.message ?: "Kotlin error"
            )
        ).asCPointer()
    }
}
```

The `errorOut` null-check ensures that if a caller passes `null` (e.g., a raw C caller that doesn't care about errors), the exception is silently swallowed rather than crashing on a null pointer write. All generated C# code always passes a real `out IntPtr`, so this case does not occur in practice.

### C# P/Invoke declaration

```csharp
[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "add")]
private static extern int Native_Add(int a, int b, out IntPtr error);
```

### C# wrapper method

```csharp
public static int Add(int a, int b)
{
    int result = Native_Add(a, b, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        string kotlinType = NugetErrorNative.Type(error);
        string msg = NugetErrorNative.Message(error);
        NugetMarshal.Dispose(error);
        throw new KotlinException(kotlinType, msg);
    }
    return result;
}
```

The `KotlinException`, `NugetErrorNative`, and `NugetMarshal.Dispose` are already generated by ADR-023's infrastructure. No new C# helper classes are needed.

### What about `KotlinException` when there are no suspend functions?

ADR-023's `KotlinException` and `NugetErrorNative` are currently emitted only when the library has suspend functions (as part of `CirAsyncHelper` / `CirErrorHelper`). With sync exception propagation, these must be emitted whenever **any** function exists — since any function can throw. The `CirErrorHelper` and `KotlinException` class should be emitted unconditionally (or at minimum, whenever any sync or async function is present).

### Consumer experience

```csharp
try
{
    string result = SyncExceptions.FeedCatTreat("Oreo");
}
catch (KotlinException ex)
{
    // ex.KotlinType == "kotlin.IllegalArgumentException"
    // ex.Message == "Oreo is on a diet!"
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);
}
```

## Consequences

### Breaking changes

- All sync `@CName` exports gain a trailing `COpaquePointer?` parameter — this is a C ABI change. Since all exports and P/Invoke declarations are generated, no manual changes are needed.
- The generated C# wrapper methods now throw `KotlinException` on Kotlin-side errors instead of crashing the process. This is a behavior change but strictly an improvement.

### Affected export generators

Every sync export builder in the KSP processor must be updated to:
1. Add the `errorOut: COpaquePointer?` parameter to the generated `@CName` function.
2. Wrap the function body in `try/catch(Throwable)`.
3. Write the error `StableRef<Pair<String, String>>` through `errorOut` on catch.

Affected files:
- `FunctionExports.kt` — top-level functions
- `ClassExports.kt` — class methods, companion methods
- `GenericFunctionExports.kt` — generic function typed variants
- `GenericClassExports.kt` — generic class method variants
- `ObjectExports.kt` — object methods
- `SealedClassExports.kt` — sealed subclass methods (if any)
- `ExtensionFunctionExports.kt` — extension functions

### Affected CIR / renderer

- `CirModel.kt` — sync `CirMethod.body` strings must include the error-check pattern.
- `CirRenderer.kt` — `renderMethod` must emit the `out IntPtr error` parameter on `[DllImport]` and the error-check block in wrappers.
- `CirTranslator.kt` / `CirFunctionTranslator.kt` / `CirClassTranslator.kt` — translate sync functions with the error-check body.

### Scope

**v1 (this ADR):**
- All sync top-level functions (including nullable variants).
- All sync class methods (including companion and extension).
- Object methods.
- Generic function and class method exports.

**Deferred:**
- Property getter/setter exceptions — uncommon in Kotlin convention; deferred until needed.
- Constructor exceptions — constructor failure has different semantics (partially-constructed objects); deferred.
- Stack trace propagation — deferred by ADR-023.
- Exception cause chain (`InnerException`) — deferred by ADR-023.
- `@Throws`-based opt-in — not needed given wrap-all approach, but could be added as an optimization later to skip try/catch on functions the author guarantees won't throw.

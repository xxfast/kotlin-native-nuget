# ADR-041: Kotlin → managed C# call mechanism — init-time function-pointer registration table

## Status

Proposed

## Context

Phase 8 (NuGet consumption) requires generated Kotlin stubs to invoke methods from a C# NuGet
dependency at runtime. This is structurally opposite to the forward direction (Kotlin exports called
by C# via P/Invoke) and also opposite to the Phase 7 per-call callback direction (C# passes a
function pointer to Kotlin alongside a specific call). In Phase 8, **Kotlin initiates the call** into
managed code, and there is no per-call opportunity for C# to hand Kotlin a function pointer.

### The host-process assumption

The Kotlin/Native library always runs **inside a .NET host process** — loaded via P/Invoke from a C#
application or library. The CLR is already resident in the same process. This means:

- No CLR bootstrapping is required on the Kotlin/Native side.
- Managed methods are reachable from native code if a stable function pointer to them can be obtained.
- The function pointer must be obtained on the managed side and handed to Kotlin; Kotlin cannot request
  one on demand without already having a channel into managed code.

This fact makes the mechanism strictly simpler than anything CocoaPods/spm4Kmp must do: they have
no equivalent shortcut because their native library is the host.

### How `[UnmanagedCallersOnly]` exposes managed methods to native code

`[System.Runtime.InteropServices.UnmanagedCallersOnlyAttribute]` (C# 9, .NET 5) marks a `static`
managed method as directly callable from native code:

- No managed-to-unmanaged thunk is generated; the method is a true native entry point.
- Its address is obtained with the C# address-of operator in an `unsafe` block:
  `(IntPtr)(delegate* unmanaged[Cdecl]<...>)(&MethodName)`.
- Constraints: must be `static`, must have blittable parameters only, must not be generic or inside a
  generic class. These constraints are non-issues for wrapping static C# library methods, where all
  managed state is passed through pointer parameters.

Source: [UnmanagedCallersOnlyAttribute](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.unmanagedcallersonlyattribute)

### How `[ModuleInitializer]` provides automatic startup execution

`[System.Runtime.CompilerServices.ModuleInitializerAttribute]` (C# 9, .NET 5) marks a method that
the CLR calls automatically when the containing assembly is loaded, before any other code in the
module runs. The method must be `static`, parameterless, `void`, and accessible from the module
(`internal` or `public`). Multiple methods can be marked; the CLR calls all of them in a
deterministic but unspecified order.

The .NET documentation explicitly identifies `[ModuleInitializer]` as the standard place for
source-generator initialization code. No consumer-side bootstrapping call is needed.

Source: [ModuleInitializer attribute](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/attributes/general#moduleinitializer-attribute)

### The registration handshake

The two attributes compose into a self-contained startup protocol:

1. The .NET host process loads the generated bindings assembly (directly referenced or on first use).
2. The CLR calls the `[ModuleInitializer]` in the generated shims file.
3. The `[ModuleInitializer]` calls a Kotlin-exported registration function via P/Invoke
   (`@CName` export), passing the addresses of `[UnmanagedCallersOnly]` thunks as `IntPtr` values.
4. The first P/Invoke call triggers native library loading (lazy loading; same mechanism as all
   existing forward-direction exports).
5. The Kotlin registration function stores the incoming pointers in global
   `CPointer<CFunction<...>>?` variables.
6. All subsequent Kotlin stub calls go through these stored pointers; stubs fail fast with
   `requireNotNull` if the table is not populated.

### Connection to ADR-036 Alternative 2

ADR-036 evaluated `[UnmanagedCallersOnly]` for the Phase 7 per-call callback problem and rejected
it because: (a) methods must be `static` and cannot capture state — a problem when the callback IS a
capturing user lambda; (b) requires `unsafe` code — undesirable in user-facing generated code.
For the startup registration table in Phase 8, both objections dissolve: the thunks are `static`
wrappers around static C# library methods (no capture needed), and `unsafe` in generated
generator-internal files is acceptable. The rejected alternative in ADR-036 is precisely the right
pattern here.

### What standalone Kotlin hosts would require

If the Kotlin/Native library were to run in a process with no .NET runtime — for example, a pure
Kotlin command-line binary — the CLR would need to be loaded via the .NET hosting APIs:
`hostfxr_initialize_for_runtime_config` → `hostfxr_get_runtime_delegate`. This requires bundling or
locating the .NET runtime, a `.runtimeconfig.json` at native load time, and a fundamentally different
deployment model where the Kotlin binary is the host rather than a satellite. All of this is
**explicitly out of scope for v1**; `hostfxr` hosting is noted as the future path if that scope ever
changes.

Source: [.NET hosting API (hostfxr)](https://learn.microsoft.com/en-us/dotnet/core/tutorials/netcore-hosting)

## Alternatives Considered

### 1. `[UnmanagedCallersOnly]` thunks + `[ModuleInitializer]` registration table (chosen)

For each C# type being bound, the generated shims file contains:
- One `[UnmanagedCallersOnly]` static method per callable C# method (the thunk).
- A single `[ModuleInitializer]` that calls the Kotlin registration export once at startup,
  passing all thunk pointers for that type.
- A Kotlin registration `@CName` export that stores the incoming pointers in global
  `CPointer<CFunction<...>>?` variables.
- Kotlin stubs that `requireNotNull` on the stored pointer before every call.

**Kotlin side — generated for a C# type `Acme.Utils.MathHelper`:**

```kotlin
// Generated: MathHelperBindings.kt
private var clampFn: CPointer<CFunction<(Double, Double, Double) -> Double>>? = null
private var formatFn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null

@CName("nuget_math_helper_register")
fun nuget_math_helper_register(
    clampPtr: COpaquePointer,
    formatPtr: COpaquePointer,
) {
    clampFn = clampPtr.reinterpret()
    formatFn = formatPtr.reinterpret()
}

// Generated stub (MathHelper.kt)
object MathHelper {
    fun clamp(value: Double, min: Double, max: Double): Double {
        val fn = requireNotNull(clampFn) {
            "MathHelper bindings are not registered. Ensure the generated C# shims for Acme.Utils are loaded before calling Kotlin → C# bridge functions."
        }
        return fn.invoke(value, min, max)
    }

    fun format(value: Double, fmt: String): String {
        val fn = requireNotNull(formatFn) {
            "MathHelper bindings are not registered. Ensure the generated C# shims for Acme.Utils are loaded before calling Kotlin → C# bridge functions."
        }
        val fmtPtr = fmt.encodeToByteArray().pin()
        val resultPtr = fn.invoke(fmtPtr.addressOf(0), null) // null = no error-out for v1 simple case
            ?: throw RuntimeException("MathHelper.Format returned null unexpectedly")
        fmtPtr.unpin()
        val result = resultPtr.reinterpret<ByteVar>().toKString()
        nativeHeap.free(resultPtr)  // free CoTaskMem-allocated string from C# side
        return result
    }
}
```

**C# side — generated registration shim:**

```csharp
// Generated: MathHelperRegistration.cs
internal static class MathHelperRegistration
{
    [DllImport("kotlin-native-lib", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_math_helper_register")]
    private static extern void nuget_math_helper_register(IntPtr clampPtr, IntPtr formatPtr);

    [ModuleInitializer]
    internal static unsafe void Initialize()
    {
        nuget_math_helper_register(
            (IntPtr)(delegate* unmanaged[Cdecl]<double, double, double, double>)(&Clamp_Thunk),
            (IntPtr)(delegate* unmanaged[Cdecl]<byte*, byte*, byte*>)(&Format_Thunk)
        );
    }

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private static double Clamp_Thunk(double value, double min, double max)
        => Acme.Utils.MathHelper.Clamp(value, min, max);

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private static unsafe byte* Format_Thunk(byte* valuePtr, byte* fmtPtr)
    {
        try
        {
            double value = double.Parse(Marshal.PtrToStringUTF8((IntPtr)valuePtr)!);
            string fmt = Marshal.PtrToStringUTF8((IntPtr)fmtPtr)!;
            string result = Acme.Utils.MathHelper.Format(value, fmt);
            return (byte*)Marshal.StringToCoTaskMemUTF8(result);
        }
        catch
        {
            return null;
        }
    }
}
```

**Consumer Kotlin API (what Kotlin developers write):**

```kotlin
// Kotlin calling a C# NuGet method as if it were a native Kotlin function
val clamped = MathHelper.clamp(3.7, 0.0, 1.0)   // calls Acme.Utils.MathHelper.Clamp via thunk
val formatted = MathHelper.format(clamped, "F2")  // calls Acme.Utils.MathHelper.Format via thunk
```

**Pros:**
- `[UnmanagedCallersOnly]` provides a direct native entry point with zero managed-to-unmanaged
  transition overhead on every Kotlin → C# call — the function pointer IS the native method.
- `[ModuleInitializer]` is the documented idiomatic pattern for source-generator initialization;
  no consumer-side bootstrapping required.
- The registered function pointers are stable for the process lifetime — no `GCHandle` lifetime
  management, unlike per-call `Marshal.GetFunctionPointerForDelegate`.
- The `static` + no-capture constraint of `[UnmanagedCallersOnly]` is a non-issue: C# NuGet
  library API methods are static (or take a `GCHandle`/opaque pointer for the target object).
- Fail-fast on missing registration: `requireNotNull(fn) { "..." }` gives a clear Kotlin exception
  with diagnostic text instead of a null-pointer crash.
- Requires `AllowUnsafeBlocks = true` in the generated project for `delegate* unmanaged<>` pointer
  syntax — already consistent with the project's use of `[DllImport]` and native pointer types
  throughout generated files.
- Aligns with NativeAOT's model for exposing managed code to native callers
  (`[UnmanagedCallersOnly]` is a key NativeAOT primitive).

**Cons:**
- `AllowUnsafeBlocks = true` must be set in the generated project; the Gradle plugin must emit this
  in the generated `.csproj` or the consumer must set it.
- When multiple `[ModuleInitializer]` methods exist (one per NuGet dependency), their relative
  execution order is unspecified; each registration is independent so this is not a concern.
- Blittable-only parameters require string values to cross as `byte*` (UTF-8 pointer) or as
  `GCHandle`-backed `IntPtr` for complex managed types; same marshalling discipline as the
  forward direction.
- `[UnmanagedCallersOnly]` methods must not be called from managed code — the thunks are internal
  and should never be called directly from C#; generated access modifiers enforce this.

### 2. Per-call `Marshal.GetFunctionPointerForDelegate` at startup

The same startup registration as Alternative 1, but using `Marshal.GetFunctionPointerForDelegate`
with static delegate fields instead of `[UnmanagedCallersOnly]` + `delegate* unmanaged<>`:

```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
private delegate double ClampDelegate(double value, double min, double max);

private static readonly ClampDelegate s_clamp = (v, min, max) => Acme.Utils.MathHelper.Clamp(v, min, max);
private static readonly GCHandle s_clampHandle = GCHandle.Alloc(s_clamp);

[ModuleInitializer]
internal static void Initialize()
{
    nuget_math_helper_register(Marshal.GetFunctionPointerForDelegate(s_clamp));
}
```

**Pros:**
- No `unsafe` code; no `AllowUnsafeBlocks` requirement.
- Static delegate fields keep the delegate alive without an explicit `GCHandle.Alloc`.

**Cons:**
- `Marshal.GetFunctionPointerForDelegate` generates a managed-to-unmanaged **thunk** (an IL stub)
  that is invoked on every Kotlin → C# call through the registered pointer. This thunk cost is paid
  on every call, not just at startup — every Kotlin → C# invocation in Phase 8 pays an extra
  transition. `[UnmanagedCallersOnly]` eliminates this overhead entirely.
- Not NativeAOT-compatible; `Marshal.GetFunctionPointerForDelegate` uses reflection and cannot be
  statically analyzed by the trimmer (aligned with the NativeAOT tracking in ADR-038).
- Requires a named static delegate type per thunk signature, similar to the per-call delegate
  machinery in ADR-036. The `[UnmanagedCallersOnly]` + `delegate* unmanaged<>` approach is more
  direct and produces less generated code.

### 3. `hostfxr` CLR hosting for standalone Kotlin hosts (explicitly out of scope for v1)

For a Kotlin/Native binary running as the process owner with no .NET runtime in-process, the CLR
must be bootstrapped from native code using the .NET hosting APIs (`hostfxr.dll` /
`libhostfxr.so`):

1. `hostfxr_initialize_for_runtime_config` loads the CLR into the process using a
   `.runtimeconfig.json` alongside the Kotlin binary.
2. `hostfxr_get_runtime_delegate(…, hdt_load_assembly_and_get_function_pointer, …)` retrieves a
   factory function for obtaining managed entry points.
3. The factory is called to load the C# NuGet assembly and get function pointers to its methods.
4. Kotlin calls through the obtained function pointers.

Analogues in other ecosystems: Python.NET hosts the CLR in a Python process using the same API
surface; C++/CLI embeds the CLR in a C++ process. Both require the full .NET runtime to be
distributed alongside the native binary.

**Rejected for v1** because:
- Requires the .NET runtime (or a self-contained bundle) on the end-user machine and discoverable
  by the Kotlin binary — a fundamentally different deployment model.
- The `.runtimeconfig.json` must describe the exact target framework at native build time; this
  couples the Kotlin build to a specific .NET version rather than running inside whatever .NET host
  the consuming application chooses.
- `hostfxr` initialization is non-trivial (path resolution, error handling, version negotiation) and
  would need its own infrastructure layer with no reuse from the existing P/Invoke model.
- All Phase 8 ROADMAP items assume the .NET host assumption; this alternative targets a scenario
  that is not yet a stated requirement.

**Recorded as the future path** if Phase 8 ever needs to support standalone Kotlin hosts. A new ADR
would be required before any `hostfxr` work begins.

Source: [.NET hosting API — netcore-hosting](https://learn.microsoft.com/en-us/dotnet/core/tutorials/netcore-hosting)

## Decision

Use **Alternative 1: `[UnmanagedCallersOnly]` thunks + `[ModuleInitializer]` registration table**
for all Kotlin → managed C# calls in Phase 8.

### Registration protocol (generated artifacts)

**Kotlin side — per bound C# type:**

1. One global `CPointer<CFunction<...>>?` variable per bound C# method, initialized to `null`.
2. One `@CName("nuget_{typename}_register")` export that accepts one `COpaquePointer` per method
   and stores them with `.reinterpret<CFunction<...>>()`.
3. One stub per bound C# method:
   - `requireNotNull(fnVar) { "..." }` fail-fast guard with a diagnostic naming the type and
     dependency.
   - Marshal parameters (primitives by value; strings as UTF-8 `COpaquePointer`; C# objects as
     `GCHandle`-backed `COpaquePointer`).
   - Invoke through the function pointer.
   - Unmarshal return value (free any C#-side allocated memory: `CoTaskMemFree` for strings).

**C# side — per bound C# type, in generated `{TypeName}Registration.cs`:**

1. One `[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })] private static`
   method per bound C# method. The thunk receives blittable parameters, calls the actual C# API,
   and returns a blittable result (strings via `Marshal.StringToCoTaskMemUTF8`; exceptions written
   to an out-pointer using the ADR-024 error-out pattern).
2. A `[DllImport]` declaration for the Kotlin registration export.
3. A `[ModuleInitializer] internal static unsafe void Initialize()` that calls the registration
   export, passing the addresses of all thunks via `(IntPtr)(delegate* unmanaged[Cdecl]<...>)(
   &ThunkName)`.

### Naming convention

Kotlin registration exports follow the pattern `nuget_{package_snake_case}_{typename_snake_case}_register`
(e.g., `nuget_acme_utils_math_helper_register`). This mirrors the forward-direction naming convention
(`{typename}_{methodname}`) and is unlikely to collide with user-defined Kotlin exports.

### Error propagation from C# thunks

Exception propagation from C# back to Kotlin through a thunk follows the ADR-024 error-out pattern
in reverse: the thunk accepts an additional `IntPtr outError` parameter. On exception, the thunk
writes a `StableRef<KotlinError>`-style handle to that pointer and returns a sentinel (null pointer
or 0). The Kotlin stub checks the out-error pointer after the call and throws a Kotlin exception,
mirroring ADR-023/024 for the reverse direction. Full error propagation semantics are a follow-on
concern; v1 thunks may return sentinel values on exception with diagnostics deferred to a later ADR.

### `AllowUnsafeBlocks` requirement

The generated `.csproj` (or the MSBuild `<PropertyGroup>` in the generated `.targets` file) must
include `<AllowUnsafeBlocks>true</AllowUnsafeBlocks>`. This is the only change to the generated
project configuration beyond what already exists.

### Standalone Kotlin host is explicitly out of scope for v1

Any scenario where the Kotlin/Native binary is the process owner and needs to load the CLR is
**out of scope for v1**. The `hostfxr`-based CLR hosting path is the designated future approach for
that use case and will require a separate ADR. All Phase 8 ROADMAP items are written under the
.NET-host-process assumption; this ADR records it as a structural constraint, not an oversight.

## Consequences

### New generated artifacts (per NuGet dependency)

- `{TypeName}Registration.cs` — one generated file per bound C# type:
  - `[UnmanagedCallersOnly]` thunk methods (one per bound method).
  - `[ModuleInitializer]` initialization method calling the Kotlin registration export.
  - `[DllImport]` declaration for the Kotlin registration function.
- `{TypeName}Bindings.kt` — one generated Kotlin file per bound C# type:
  - Global `CPointer<CFunction<...>>?` registration variables.
  - `@CName` registration export.
- `{TypeName}.kt` — the Kotlin stub file that Kotlin consumers use (the idiomatic Kotlin API).

### New Kotlin patterns

- Global nullable function pointer variables (one per callable C# method) initialized to `null`.
- A `requireNotNull` guard in every stub — the fail-fast contract for missing registration.
- `CoTaskMemFree` (or a generated `nuget_free_string` export) to release strings allocated by C#
  thunks and returned to Kotlin.

### New C# patterns

- `[UnmanagedCallersOnly]` static thunk methods in generated files (new — not used in
  forward-direction generated code; used in generated reverse-interop thunks).
- `[ModuleInitializer]` in generated files (new — not present in any existing generated output).
- `delegate* unmanaged[Cdecl]<...>` address-of expressions in `unsafe` initialization methods.
- `<AllowUnsafeBlocks>true</AllowUnsafeBlocks>` in the generated project.

### Interaction with Phase 7 (reverse interop machinery)

Phase 7's `Marshal.GetFunctionPointerForDelegate` + `GCHandle.Alloc` pattern (ADR-036/037/039)
remains the mechanism for **C# passing callbacks to Kotlin** (C# → Kotlin direction). Phase 8's
`[UnmanagedCallersOnly]` + `[ModuleInitializer]` is the mechanism for **Kotlin calling C#** (Kotlin
→ C# direction). Both mechanisms coexist: a single Kotlin/Native library can expose Kotlin functions
to C# (forward direction) and call C# NuGet methods (Phase 8) simultaneously; the two are orthogonal
and use different generated code paths.

### Breaking changes

None. Phase 8 is additive. Existing forward-direction generated code is unchanged. The new
registration shim files and Kotlin binding files are additional generated outputs.

### Scope

**In v1:**
- Static C# methods with primitive parameters and return types (`int`, `long`, `double`, `bool`,
  `float` and their unsigned variants — same set as the forward-direction primitives).
- Static C# methods accepting and returning `string` (marshalled as UTF-8 `byte*`; C# thunk
  allocates with `Marshal.StringToCoTaskMemUTF8`; Kotlin stub frees with `CoTaskMemFree`).
- Static C# methods returning `void`.
- One registration export per C# class/static class being bound.
- `AllowUnsafeBlocks` added to the generated project.

**Deferred:**
- C# instance methods — require passing a `GCHandle` for the target object; follow-on once the
  `GCHandle`-as-opaque-handle pattern (mirror of `StableRef`) is ADR'd for Phase 8.
- C# methods with C# object parameters — same `GCHandle` dependency.
- Exception propagation from C# thunk back to Kotlin — full error-out ABI; tracked for a follow-on
  ADR (mirror of ADR-023/024 in the reverse direction).
- `Task<T>`-returning C# methods — require `async` bridging (mirror of ADR-019); deferred.
- C# interface methods (vtable dispatch) — deferred.
- Standalone Kotlin host (`hostfxr` path) — explicitly out of scope; future ADR required.

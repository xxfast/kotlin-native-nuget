# ADR-051: C# objects as opaque handles in Kotlin — `GCHandle` representation, wrapper identity, and `Cleaner`-based lifetime with explicit `close()`

## Status

Accepted

## Context

Phase 9 (ROADMAP line 148) is the foundational reverse-direction object feature: today the reverse
bridge supports only static methods with `void`/`string`/primitive signatures (ADR-043/048/049 v1
ceiling). This ADR makes C# **objects** usable from Kotlin: a C# instance crosses the C ABI as an
opaque handle and surfaces as a generated Kotlin class. Instance constructors, instance
methods/properties, and every later reverse feature that touches a reference type build directly on
the decisions made here.

Three decisions must be settled:

1. **Handle representation** — what crosses the ABI, what the Kotlin wrapper stores, which
   `GCHandleType` is used.
2. **Wrapper identity semantics** — what happens when the same C# object reaches Kotlin twice;
   what `equals`/`hashCode`/`toString` do on the wrapper.
3. **Lifetime cleanup** — how the `GCHandle` is freed from the Kotlin side: automatic
   (`kotlin.native.ref.Cleaner`), explicit (`close()`), or both; and how the free call fits the
   ADR-048/049 registration contract.

### The forward-direction mirror (ADR-003, ADR-005)

The forward direction already solved the same problem in the opposite direction:

- **ADR-003**: Kotlin pins an object with `StableRef.create(obj).asCPointer()` → C# stores the
  `IntPtr` in a generated wrapper → C# `Dispose()` calls a Kotlin `_dispose` export that runs
  `handle.asStableRef<T>().dispose()`. Ownership rule: *the producing runtime pins, the consuming
  runtime frees*.
- **ADR-005**: each object-typed return creates a **new** `StableRef` and a **new** C# wrapper.
  Identity is not preserved (`oreo.Buddy != oreo.Buddy`), matching ObjC/Swift Export semantics.

The reverse direction inverts each role, using the .NET primitive purpose-built for exactly this:
[`System.Runtime.InteropServices.GCHandle`](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.gchandle)
— "Provides a way to access a managed object from unmanaged memory. … you can use it to prevent
the managed object from being collected by the garbage collector when an unmanaged client holds
the only reference." The API surface is a one-to-one mirror of `StableRef`:

| Forward (ADR-003) | Reverse (this ADR) |
|---|---|
| `StableRef.create(obj)` | `GCHandle.Alloc(obj)` (defaults to `GCHandleType.Normal`) |
| `.asCPointer()` → `COpaquePointer` | `GCHandle.ToIntPtr(h)` → `IntPtr` |
| `ptr.asStableRef<T>().get()` | `(T)GCHandle.FromIntPtr(ptr).Target!` |
| `stableRef.dispose()` | `handle.Free()` |
| C# wrapper stores `IntPtr _handle` | Kotlin wrapper stores `COpaquePointer` |
| C# `Dispose()` → Kotlin `_dispose` export (P/Invoke) | Kotlin `Cleaner`/`close()` → registered C# free thunk (function pointer — Kotlin cannot P/Invoke into managed code, ADR-041) |

One structural asymmetry matters: in the forward direction the consuming side (C#) can always call
the freeing function directly via P/Invoke, because Kotlin `@CName` exports are static native
symbols. In the reverse direction Kotlin has **no static entry point into managed code** — every
Kotlin → C# call goes through the ADR-041 registration table. The free call must therefore itself
be a registered `[UnmanagedCallersOnly]` thunk.

### How other ecosystems import foreign objects

**Xamarin / .NET for Android** (the exact mirror image of this project — C# consuming Java): each
managed callable wrapper (MCW) "holds a Java global reference, which is accessible through the
`Android.Runtime.IJavaObject.Handle` property. Global references are used to provide the mapping
between Java instances and managed instances." Lifetime is **automatic-primary with an explicit
escape hatch**: peers are normally reclaimed by the GC bridge, and "global references can be
explicitly freed by calling `Java.Lang.Object.Dispose()` on the managed callable wrapper."
Xamarin also **preserves identity** via a handle → peer map: "`Object.GetObject<T>()` checks to
see if there is already a corresponding C# instance for *handle*. If there is, it is returned."
That map is precisely what makes premature `Dispose()` dangerous in Xamarin (documented crash
class). Source: [Xamarin.Android architecture](https://learn.microsoft.com/en-us/previous-versions/xamarin/android/internals/architecture).

**Kotlin consuming Java** (the gold standard for "foreign API surfaced as Kotlin"): no lifetime
management at all — same JVM, same GC. Kotlin developers expect foreign objects to *just get
collected*. Deterministic release exists only for genuine resources, via `Closeable`/
`AutoCloseable` + `use { }`.

**Kotlin/Native ObjC interop / CocoaPods**: ObjC object lifetimes are managed automatically — the
Kotlin/Native runtime integrates with ObjC reference counting, so a Kotlin reference to an
`NSObject` keeps it alive and releases it when the Kotlin wrapper is collected. No user-visible
dispose. Source: [Interoperability with Objective-C](https://kotlinlang.org/docs/native-objc-interop.html).

**Python.NET** (a non-.NET guest holding CLR objects): .NET objects are kept alive while the
Python wrapper is alive and released when the Python wrapper is garbage-collected; `Dispose` /
explicit release exists for strict lifetime control, and GC-timing leaks are a recurring issue
class ([pythonnet#1734](https://github.com/pythonnet/pythonnet/issues/1734),
[Python.NET reference](https://pythonnet.github.io/pythonnet/reference.html)).

The consistent pattern across every "guest language holds host object" system: **automatic release
tied to the wrapper's GC lifetime as the default, explicit release as an opt-in for deterministic
cleanup.** This is the inversion of the forward direction's choice (ADR-003 chose
`IDisposable`-first because .NET consumers expect deterministic disposal); Kotlin consumers expect
the Java-interop experience — objects that clean themselves up.

### `kotlin.native.ref.Cleaner` guarantees

[`createCleaner`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native.ref/create-cleaner.html)
"creates an object with a cleanup associated. After the resulting object … is deallocated, the
cleanup is eventually called with the resource." Constraints that shape the design:

- **Unspecified thread**: "It is not specified which thread runs `cleanupAction`." The free thunk
  will be invoked from an arbitrary Kotlin-runtime thread. This is safe: calling a registered
  `[UnmanagedCallersOnly]` function pointer from any native thread attaches that thread to the CLR
  automatically, and `GCHandle.Free()` is callable from any thread.
- **Not guaranteed at exit**: "Cleaners cannot be used to perform actions during the program
  shutdown." A `GCHandle` leaked at process exit is harmless — the whole CLR heap dies with the
  process — so this limitation costs nothing here.
- **Self-reference leak hazard**: if the cleanup lambda (or the resource) captures the object
  holding the cleaner, both leak. The generated wrapper must pass a separate holder object as the
  cleaner's resource and capture **nothing** in the lambda.
- `createCleaner` requires `@OptIn(ExperimentalNativeApi::class)` — already used throughout the
  ADR-048 generated bindings for `@CName`.

### What the RIR already models, and what extraction must add

`RirMethod` already carries `isStatic` and the metadata reader already emits instance methods
(`isStatic = false`) — no extraction change needed for method discovery. Two additions are needed:

1. **`RirObjectHandleType`** — the `"kind": "handle"` type ref sketched (as a comment) in ADR-046.
   This ADR fixes its shape as `RirObjectHandleType(namespace, name)` rather than the sketched
   single `assemblyQualifiedName` string: both generators need the namespace/name split to resolve
   the referenced type to a Kotlin package + class, and parsing an AQN back apart is strictly more
   error-prone than never joining it. (Deviation from the ADR-046 comment sketch, flagged; the
   sketch was illustrative, not binding.)
2. **`RirClass.isStatic`** — a C# `static class` is `abstract sealed` in ECMA-335 metadata. The
   generator needs this bit to decide `object` (ADR-048 shape, uninstantiable) vs `class` wrapper
   (this ADR). Constructors remain excluded from extraction for now (`.ctor` is filtered by the
   existing `SpecialName` skip in `Program.cs`); the instance-constructor ROADMAP item lifts that.

## Alternatives Considered

### Decision 1 — handle representation

#### 1a. `GCHandleType.Normal` handle crossing as `GCHandle.ToIntPtr` → `COpaquePointer` (chosen)

The C# thunk allocates `GCHandle.Alloc(obj)` (Normal) and returns `GCHandle.ToIntPtr(h)`. The
value crosses the ABI as a blittable pointer-sized integer (`IntPtr` on the thunk signature, per
the ADR-049 inverse mapping table), lands in Kotlin as `COpaquePointer?`, and is stored inside the
generated Kotlin wrapper. `IntPtr.Zero` is reserved as the null sentinel — a valid allocated
`GCHandle` never converts to zero, and `GCHandle.FromIntPtr(IntPtr.Zero)` is documented to throw.

**Pros:**
- Exact mirror of the forward `StableRef.asCPointer()` pattern (ADR-003) — one mental model for
  both directions.
- `Normal` is the correct handle type: it prevents collection while the unmanaged (Kotlin) side
  holds the only reference, without constraining the .NET GC's ability to *move* the object
  (the handle is an opaque table slot, not an address).
- `IntPtr` is blittable — satisfies the `[UnmanagedCallersOnly]` constraint with no marshalling.
- The Kotlin wrapper never interprets the value; it is a pure token handed back to C# thunks.

**Cons:**
- One `GCHandle` per crossing (see Decision 2) — handle-table pressure if a consumer creates many
  wrappers without freeing. Mitigated by the Cleaner (Decision 3).

#### 1b. `GCHandleType.Weak` handle (rejected)

A weak handle does not keep the C# object alive: "Without such a handle, the object can be
collected by the garbage collector before completing its work on behalf of the unmanaged client"
([GCHandle docs](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.gchandle)).
The Kotlin wrapper is frequently the *only* reference to the object (e.g. a factory return that C#
code never stores); a weak handle would let the CLR collect it while Kotlin still holds the token,
turning every later call into a use-after-free (`Target == null`). Weak handles are the tool for
*identity caching* (Decision 2's rejected alternative), not for ownership.

#### 1c. `GCHandleType.Pinned` handle (rejected)

Pinning exists to obtain a stable memory address (`AddrOfPinnedObject`) for direct byte access.
The bridge never dereferences the object — every access goes back through a thunk — and pinning
arbitrary non-blittable objects fails outright. Pinning would also degrade the .NET GC (compaction
holes) for zero benefit.

#### 1d. C#-side wrapper table with integer IDs (rejected)

Keep a `Dictionary<long, object>` in the generated C# runtime and pass dictionary keys across the
ABI. Reimplements what `GCHandle` already is (the runtime's own handle table), adds a lock or
concurrent map on every crossing, and needs its own free protocol anyway. No advantage over the
runtime primitive.

---

### Decision 2 — wrapper identity semantics

#### 2a. New wrapper + new `GCHandle` per crossing; no identity caching; Kotlin-default `equals`/`hashCode`/`toString` (chosen)

Every time a C# object crosses into Kotlin, the thunk allocates a **fresh** `GCHandle` and the
stub constructs a **fresh** Kotlin wrapper. Two wrappers around the same C# object are independent:
each holds its own handle, each is freed independently, and the C# object stays alive until *all*
its handles are freed and no managed references remain — multiple `Normal` handles to one object
are explicitly legal. `equals`/`hashCode`/`toString` are left as Kotlin's defaults (reference
identity on the wrapper); nothing is delegated across the bridge in v1.

**Pros:**
- Exact mirror of ADR-005 (forward direction chose the same after studying ObjC/Swift Export):
  simple, stateless, no cache invalidation, no cross-runtime weak-reference machinery.
- Each GC keeps managing its own side; a `GCHandle` is just an extra .NET GC root, precisely as a
  `StableRef` is just an extra Kotlin GC root (ADR-005 "GC interaction" section applies verbatim
  with the runtimes swapped).
- Freeing one wrapper can never invalidate another — the failure class Xamarin documents around
  premature `Dispose()` of a *shared* peer cannot occur, because nothing is shared.

**Cons:**
- `a === b` is `false` and `a == b` is `false` for two wrappers of the same C# object — surprising
  if untold; must be documented on the generated class KDoc.
- Each crossing allocates (one `GCHandle` + one Kotlin object). ROADMAP Future Improvements
  already tracks identity caching "if profiling shows allocation overhead is significant".

#### 2b. Identity caching (handle → wrapper map, Xamarin-style) (rejected for v1)

Xamarin preserves identity with a global reference → peer map. Doing the same here requires: a
Kotlin-side map keyed by *C# object identity* (not by handle value — two `GCHandle`s to the same
object convert to different `IntPtr`s, so the key must come from a C#-side
`RuntimeHelpers.GetHashCode`/reference-identity thunk), weak references on the Kotlin side so the
cache doesn't leak, and invalidation coordination with `close()`. This is the "cached wrapper"
alternative ADR-005 already rejected forward, with strictly more moving parts in reverse. Deferred
to the existing Future Improvements line, revisit with profiling data.

#### 2c. Delegate `equals`/`hashCode`/`toString` to C# `Equals`/`GetHashCode`/`ToString` (deferred)

Kotlin-consuming-Java and Xamarin both delegate these. It is achievable here — `toString` and
`hashCode` are zero-arg thunks within the v1 type vocabulary, and `equals` needs only the handle
parameter support this ADR introduces — but it costs three extra registered slots per type, and
`equals` has real edge cases (comparing a wrapper against a non-wrapper Kotlin value, `null`,
closed handles). More importantly it would make `==` disagree with `===` in ways ADR-005
deliberately avoided until identity is settled. **Deferred** to a follow-on once instance methods
land; until then the generated KDoc states that equality is wrapper identity.

---

### Decision 3 — lifetime cleanup

#### 3a. `Cleaner`-based automatic release **plus** explicit `close()` via `kotlin.AutoCloseable` (chosen)

The generated wrapper owns a small internal holder (`NugetObjectHandle`) containing the raw
pointer and an atomic freed flag. A `createCleaner(holder) { it.free() }` releases the `GCHandle`
when the wrapper becomes unreachable; `close()` releases it deterministically and is idempotent;
the cleaner's later `free()` on a closed holder is a no-op via the same flag.

**Pros:**
- Matches what Kotlin consumers of foreign objects expect (Java interop: no dispose; ObjC interop:
  no dispose) — automatic is the *idiomatic default*, per GOALS ("Kotlin bindings should feel
  like Kotlin").
- Matches the strongest prior art: Xamarin peers are GC-reclaimed by default with `Dispose()` as
  the explicit escape hatch — automatic-primary, deterministic-optional. This ADR is the same
  policy with Kotlin spelling.
- The deterministic escape hatch is genuinely needed: `GCHandle`s hold C# objects alive until the
  *Kotlin* GC happens to collect the wrapper. A loop creating thousands of wrappers (or a wrapper
  pinning a large C# object graph) must be releasable without waiting for Kotlin GC pressure —
  the exact scenario Xamarin documents for scarce global references. `kotlin.AutoCloseable` +
  `use { }` is common-stdlib since Kotlin 2.0
  ([AutoCloseable](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-auto-closeable/)).
- Cleaner-at-exit non-guarantee is harmless here (OS reclaims the CLR with the process), unlike
  e.g. file-flush use cases the Kotlin docs warn about.
- The freed flag closes both hazards at once: double-free (`close()` then cleaner) and
  use-after-close (stub guard throws `IllegalStateException`, mirroring ADR-021's
  `ObjectDisposedException` guard forward).

**Cons:**
- Every wrapper carries a cleaner allocation. Accepted — it is the cost of the idiomatic default.
- `close()` on a wrapper whose C# object is still referenced elsewhere in Kotlin (a second
  wrapper) is safe (independent handles), but users may over-close out of C# habit; KDoc states
  `close()` is optional.

#### 3b. `Cleaner`-only, no explicit `close()` (rejected)

Simplest wrapper, but leaves no deterministic release path at all: a burst of short-lived wrappers
keeps their C# objects (and anything they reference) alive until Kotlin's GC runs, and Kotlin's GC
feels no pressure from the .NET-side memory it is pinning — the classic cross-runtime "neither GC
sees the whole cost" problem Python.NET issue threads document. Rejected: the escape hatch is
cheap and its absence is unfixable by the consumer.

#### 3c. Explicit `close()` only (forward-mirror `IDisposable` policy) (rejected)

The literal mirror of ADR-003 — but ADR-003 chose deterministic-first because *C#* consumers
expect `IDisposable`. Kotlin consumers expect the Java/ObjC interop experience; forcing `close()`
on every returned object would make the bindings feel like a wrapper, violating GOALS. Forgetting
`close()` would leak permanently (no safety net), the exact negative ADR-003 records.

#### 3d. Free-export placement: single shared runtime registration export (chosen) vs per-type `_free` slot (rejected)

`GCHandle.FromIntPtr(ptr).Free()` is type-agnostic — a free thunk needs no knowledge of the
object's type. Appending a `_free` pointer to every type's `nuget_{ns}_{type}_register` export
would generate N identical thunks and, worse, change the ADR-048 contract ("one pointer per method
in `reverse-ir.json` order") for every already-generated type.

**Chosen:** one shared, once-emitted registration pair:

- Kotlin: `@CName("nuget_runtime_register")` in a generated `NugetRuntime.kt` (in the existing
  `io.github.xxfast.kotlin.native.nuget.internal` package, alongside `freeManagedString`),
  storing `freeGcHandleFn`.
- C#: `NugetRuntimeRegistration.cs` with one `FreeGcHandle_Thunk` and its own
  `[ModuleInitializer]`.

This mirrors the forward direction's shared exports (`nuget_job_cancel`, `nuget_scope_drain`,
`nuget_error_*` — ADR-022/025/023): cross-cutting bridge machinery gets one shared symbol, not a
per-type copy. `[ModuleInitializer]` ordering between the runtime shim and type shims is
unspecified but irrelevant: all shims compile into the consuming module (ADR-049 `contentFiles`
delivery), so every initializer has run before any consumer code — and therefore before any Kotlin
stub call — executes. The Kotlin side still `requireNotNull`-guards the pointer (ADR-041 contract).

## Decision

Use **1a + 2a + 3a + 3d**: `Normal` `GCHandle` crossing as an opaque pointer; new wrapper + new
handle per crossing with no identity caching and Kotlin-default equality; `Cleaner`-based
automatic release plus idempotent `close()` (`kotlin.AutoCloseable`); one shared
`nuget_runtime_register` free export.

### RIR extension

```kotlin
// RirModel.kt — new type ref (fills the ADR-046 "handle" slot; shape fixed by this ADR)
@Serializable
@SerialName("handle")
data class RirObjectHandleType(
    val namespace: String,   // C# namespace of the referenced type, e.g. "Newtonsoft.Json.Linq"
    val name: String,        // simple type name, e.g. "JObject"
) : RirTypeRef

// RirClass gains:
val isStatic: Boolean = false   // ECMA-335: abstract + sealed
```

The metadata reader emits `{"kind": "handle", "namespace": "...", "name": "..."}` for a
parameter/return whose type is a **public, non-static, non-`ref struct` class defined in a bound
namespace of the same extraction**. References to types outside the bound set stay unmapped and
produce the existing per-member skip diagnostic (new kind `skipped_unbound_type_reference`),
keeping the ADR-043 fail-fast contract.

### Bridgeable-subset extension (`RirBridging.kt`)

`isV1Type` gains a `RirObjectHandleType` case that is bridgeable **iff** the referenced type
resolves to a bound, wrapper-eligible class. Because this needs cross-type context, the shared
filter signature becomes:

```kotlin
fun bridgeableStaticMethods(cls: RirClass, boundHandleTypes: Set<RirTypeKey>): List<RirMethod>
```

Both generators (ADR-049 Alternative 10) must derive `boundHandleTypes` from the same `RirFile` via
a shared helper — extending, not weakening, the anti-drift contract.

### Generated Kotlin wrapper shape

A non-static bound `RirClass` generates a `class` (statics move into its `companion object`);
static C# classes keep the ADR-048 `object` shape. Runtime support types are emitted once into the
existing internal package.

**`NugetRuntime.kt`** (generated once, `nativeMain`, internal package):

```kotlin
package io.github.xxfast.kotlin.native.nuget.internal

import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlin.experimental.ExperimentalNativeApi

internal var freeGcHandleFn: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null

@OptIn(ExperimentalNativeApi::class)
@CName("nuget_runtime_register")
fun nuget_runtime_register(freeGcHandlePtr: COpaquePointer) {
    freeGcHandleFn = freeGcHandlePtr.reinterpret()
}

// Holder passed as the Cleaner resource. Deliberately a separate object from the wrapper so the
// cleanup lambda captures nothing (createCleaner self-reference leak hazard).
internal class NugetObjectHandle(private val raw: COpaquePointer) {
    private val freed = AtomicInt(0)

    fun free() {
        if (freed.compareAndSet(0, 1)) {
            val fn = requireNotNull(freeGcHandleFn) {
                "NuGet interop runtime is not registered. Ensure the generated C# shims are " +
                "loaded in the host process before Kotlin → C# bridge objects are released."
            }
            fn.invoke(raw)
        }
    }

    fun require(typeName: String): COpaquePointer {
        check(freed.value == 0) { "$typeName is closed — the underlying C# object handle was already released." }
        return raw
    }
}
```

**Per-type wrapper** — generated for a bound non-static class, e.g. `Acme.Text.Template` with
static factory `Parse(string): Template` and static `Render(Template, string): string`
(instance members arrive with the next ROADMAP items and slot into the same class):

```kotlin
package acme.text

import io.github.xxfast.kotlin.native.nuget.internal.NugetObjectHandle
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.COpaquePointer

/**
 * Kotlin wrapper for the C# type `Acme.Text.Template`.
 *
 * Equality is wrapper identity: two wrappers around the same C# object are not equal.
 * The underlying C# object is released automatically when this wrapper is garbage-collected;
 * call [close] (or use `use { }`) for deterministic release. [close] is optional and idempotent.
 */
@OptIn(ExperimentalNativeApi::class)
class Template internal constructor(handle: COpaquePointer) : AutoCloseable {
    internal val handle: NugetObjectHandle = NugetObjectHandle(handle)

    @Suppress("unused")
    private val cleaner = createCleaner(this.handle) { it.free() }

    override fun close(): Unit = handle.free()

    companion object {
        fun parse(source: String): Template? {
            val fn = requireNotNull(parseFn) { "Template bindings are not registered. …" }
            // …string marshalling per ADR-048…
            val ptr: COpaquePointer? = fn.invoke(sourcePtr)
            return ptr?.let { Template(it) }        // IntPtr.Zero → null → no wrapper
        }

        fun render(template: Template, name: String): String {
            val fn = requireNotNull(renderFn) { "Template bindings are not registered. …" }
            val resultPtr = fn.invoke(template.handle.require("Template"), /* name … */)
                ?: error("Template.Render returned null — expected a non-null string pointer")
            // …toKString + freeManagedString per ADR-048…
        }
    }
}
```

**Consumer call site:**

```kotlin
import acme.text.Template

val template: Template = requireNotNull(Template.parse("Hello, {name}"))
val greeting: String = Template.render(template, "world")
// nothing else required — the GCHandle is freed when `template` is collected

// deterministic form:
Template.parse("Hello, {name}")?.use { t ->
    Template.render(t, "world")
}
```

### Generated C# thunk shapes (extends the ADR-049 inverse table)

| `RirTypeRef` | Kotlin stub type | Kotlin `CFunction` param | C# thunk type | Thunk-body conversion |
|---|---|---|---|---|
| `RirObjectHandleType` (parameter) | `Foo` | `COpaquePointer?` | `IntPtr` | `(Foo)GCHandle.FromIntPtr(ptr).Target!` |
| `RirObjectHandleType` (return) | `Foo?` | `COpaquePointer?` | `IntPtr` | `result is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(result))` |

```csharp
// {TypeName}Registration.cs — object-returning / object-consuming static thunks (ADR-049 house style)
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Parse_Thunk(IntPtr sourcePtr)
{
    string source = Marshal.PtrToStringUTF8(sourcePtr)!;
    Template? result = Template.Parse(source);
    return result is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(result));
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Render_Thunk(IntPtr templateHandle, IntPtr namePtr)
{
    var template = (Template)GCHandle.FromIntPtr(templateHandle).Target!;
    string result = Template.Render(template, Marshal.PtrToStringUTF8(namePtr)!);
    return Marshal.StringToCoTaskMemUTF8(result);
}
```

```csharp
// NugetRuntimeRegistration.cs — emitted once per nugetGenerateShims run
namespace IoGithubXxfast.KotlinNativeNuget
{
    using System;
    using System.Runtime.CompilerServices;
    using System.Runtime.InteropServices;

    internal static class NugetRuntimeRegistration
    {
        [DllImport("{nativeLibraryName}", CallingConvention = CallingConvention.Cdecl,
            EntryPoint = "nuget_runtime_register")]
        private static extern void nuget_runtime_register(IntPtr freeGcHandlePtr);

        [ModuleInitializer]
        internal static unsafe void Initialize() =>
            nuget_runtime_register((IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)(&FreeGcHandle_Thunk));

        // Called from Kotlin's Cleaner thread or close(); GCHandle.Free is thread-safe and the
        // CLR attaches unknown native threads automatically on entry.
        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
        private static void FreeGcHandle_Thunk(IntPtr handle) => GCHandle.FromIntPtr(handle).Free();
    }
}
```

### Registration contract impact

The ADR-048 per-type contract (`nuget_{ns_snake}_{type_snake}_register`, one pointer per
bridgeable method in `reverse-ir.json` order) is **unchanged**. Handle-typed parameters/returns
change only the `CFunction`/thunk *signatures*, not the registration shape. The only new export is
the shared `nuget_runtime_register`, emitted whenever any bound signature contains a
`RirObjectHandleType` (analogous to `freeManagedString` being emitted only when strings appear).

### Nullability (v1 judgment call, flagged)

RIR carries no nullable-reference-type metadata yet (separate Phase 9 ROADMAP item). v1 semantics:

- **Object returns are `Foo?`** — `IntPtr.Zero` is a legitimate C# `null` return (Try/factory
  patterns), and mapping it to an exception would make common APIs unusable. This intentionally
  differs from v1 string returns (non-null + `error()`), which predate any null-capable pattern.
- **Object parameters are non-null `Foo`** — passing `null` to C# is deferred until nullability
  metadata is mapped; a non-null parameter is always satisfiable by the caller.

Both flip to metadata-driven `T?` when the `NullableAttribute` ROADMAP item lands.

## Consequences

### New/changed artifacts

- `nuget-metadata-reader/Program.cs`: emit `"kind": "handle"` type refs for bound-class
  parameters/returns; emit `RirClass.isStatic`; new `skipped_unbound_type_reference` diagnostic.
- `RirModel.kt`: `RirObjectHandleType`, `RirClass.isStatic`, new diagnostic kind.
- `RirBridging.kt`: `isV1Type` handle case; shared `boundHandleTypes` derivation; filter signature
  change consumed by both generators.
- `NugetGenerateBindingsTask`: `class`-wrapper rendering (companion for statics), `NugetRuntime.kt`
  emission, `Foo?` return / `Foo` parameter marshalling in stubs.
- `NugetGenerateShimsTask`: handle-typed thunk signatures, `NugetRuntimeRegistration.cs` emission.
- Generated wrappers opt into `ExperimentalNativeApi` (`createCleaner`) — already the norm.

### Behavioural notes

- Two wrappers of one C# object are unequal and independently closeable (documented in generated
  KDoc). Identity caching and `Equals`/`GetHashCode`/`ToString` delegation stay in Future
  Improvements / a follow-on ADR.
- Use-after-`close()` throws `IllegalStateException` at the stub boundary (fail-fast, mirror of
  ADR-021's guard). Double-`close()` and cleaner-after-`close()` are no-ops via the atomic flag.
- A constructor/factory thunk that throws still fast-fails the process (ADR-049 v1 exception
  policy, unchanged; graceful propagation is Phase 11).
- Handles leaked at abnormal exit are reclaimed by process teardown; the Cleaner's no-guarantee-at-
  exit limitation is accepted and documented.

### Breaking changes

None at the consumer level. Internally, the shared `bridgeableStaticMethods` signature changes
(both call sites updated together per the ADR-049 anti-drift rule), and `reverse-ir.json` gains
additive fields only (`"kind": "handle"`, `isStatic` with a default — old JSON still parses).

### Test-fixture gap (flagged)

The current end-to-end fixture (`MimeMapping`, ADR-050) is all static string→string methods — it
cannot exercise object handles. A v1 round-trip needs a package exposing, within the v1 subset, a
single-overload static factory returning an instance type plus a static consumer of that type;
mainstream packages essentially never fit this shape unforced (ADR-050 already documented the
Newtonsoft dead end). **Recommendation:** implement the ROADMAP line 126 local-`.nupkg` source (or
a repo-local `sample-dependency/` C# classlib packed into the ADR-050 local feed) and author a
controlled fixture exposing exactly the shape above. Treat the local-source item as a de-facto
prerequisite for this feature's integration test; real-package candidates (e.g.
`SemanticVersioning`) should be verified against the overload-set filter before being relied on.

### Scope

**In v1 (this ADR):**
- `RirObjectHandleType` end to end (reader → RIR → both generators).
- Kotlin `class` wrapper (`NugetObjectHandle` + `Cleaner` + `AutoCloseable`/`close()`), statics in
  `companion object`; static C# classes keep the `object` shape.
- Handle-typed parameters and returns on **static** methods only (the existing bridgeable surface).
- Shared `nuget_runtime_register` / `FreeGcHandle_Thunk` free export.
- New-wrapper-per-crossing; Kotlin-default `equals`/`hashCode`/`toString`.
- Object returns as `Foo?`, object parameters as `Foo`.

**Deferred:**
- Instance constructors, instance methods, instance properties — next ROADMAP items; they reuse
  this ADR's handle + wrapper machinery unchanged (an instance thunk is a static thunk whose first
  parameter is the receiver handle).
- `Equals`/`GetHashCode`/`ToString` delegation across the bridge (Alternative 2c).
- Identity caching (ROADMAP Future Improvements, mirror of the forward note).
- Nullability from `NullableAttribute` metadata (separate Phase 9 item).
- Kotlin passing `null` for object parameters (falls out of the nullability item).
- Exception propagation from throwing factories/methods (Phase 11).

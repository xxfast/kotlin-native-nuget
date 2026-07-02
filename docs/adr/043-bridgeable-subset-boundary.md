# ADR-043: Bridgeable-subset boundary — which C# constructs cannot cross the C ABI and how to communicate that to users

## Status

Proposed

## Context

Phase 8 (ROADMAP line 124) adds the reverse direction: the Gradle plugin resolves a NuGet package,
reads its public API from ECMA-335 assembly metadata, and generates Kotlin-idiomatic stubs plus
C#-side `[UnmanagedCallersOnly]` registration thunks. Not every C# construct that is visible in
metadata can be represented as a flat C ABI export. This ADR defines the exact ceiling — what is
auto-mappable in v1, what is structurally excluded, and how the generator communicates the
boundary to users.

### The analogous problem in prior art

Every prior-art consumer of a foreign library hits an analogous visibility ceiling:

- **spm4Kmp / Kotlin CocoaPods plugin**: only the `@objc`/`NSObject`-rooted, `public` slice of a
  Swift or Objective-C API is visible to Kotlin cinterop. Pure-Swift types (structs, enums with
  associated values, `async` functions, protocols without `@objc`) cannot cross. spm4Kmp
  communicates this structurally: it generates a `src/swift/[cinteropName]` folder and seeds it
  with `StartYourBridgeHere.swift`, making the expected hand-written bridging layer visible and
  explicit. Members outside the slice are not diagnosed — they simply never appear in the cinterop
  output. Source: [spm4Kmp bridge docs](https://spmforkmp.eu/bridge/),
  [Kotlin ObjC interop](https://kotlinlang.org/docs/native-objc-interop.html).

- **Swift Export (JetBrains, experimental/alpha)**: supports only final classes that directly
  inherit `Any`; no cross-language inheritance, no open generics, no `async` functions in its
  current form. Swift Export communicates its ceiling via YouTrack issues and documentation, not
  at-build-time per-member diagnostics. Source:
  [Swift Export docs](https://kotlinlang.org/docs/native-swift-export.html),
  [KT-65894](https://youtrack.jetbrains.com/issue/KT-65894/Swift-Export-support-Kotlin-final-class-declarations).

- **Xamarin / .NET for Android binding generator**: skips unsupported Java members (non-public
  base classes, certain generics, etc.) and communicates them via MSBuild diagnostic warnings and
  the build log. Metadata XML (`Transforms/Metadata.xml`) lets users un-skip or remap individual
  members. Source:
  [Xamarin Android troubleshooting](https://learn.microsoft.com/en-us/xamarin/android/platform/binding-java-library/troubleshooting-bindings).

Our fail-fast convention (CLAUDE.md) aligns most closely with the Xamarin diagnostic model, but
with a build-time Gradle warning per skipped member rather than a silent drop — silent omission
would surprise Kotlin developers who wrote code expecting a method to be available.

### The forward-direction precedent: ADR-034

ADR-034 (secondary constructor overloads) already resolved the same structural problem in the
Kotlin → C# direction. Two Kotlin constructors that erase to the same C# parameter-type list
cannot both be exported: the chosen solution is **fail-fast with an explicit KSP error naming both
constructors**. The same principle applies here, translated to the reverse direction: the generator
emits a Gradle warning (not a hard error, because the package as a whole may still be usable)
naming each skipped member and the reason. See also the consistency note in the synthesis document
(D2, nuget-plugin-architecture-synthesis.md).

### The C ABI boundary

The reverse direction crosses the same C ABI as the forward direction, now with managed-side
`[UnmanagedCallersOnly]` thunks registered at startup (ADR-036 reverse-interop mechanism). The
fundamental constraints of that boundary determine the subset:

1. All parameter and return types in a `[UnmanagedCallersOnly]` method must be **blittable** or
   explicitly marshalled. No managed references, no boxed value types, no GC-heap-scanning
   contracts.
2. Each C export symbol must be **globally unique** (C has no overloading).
3. The thunk must be a **static method** — no instance dispatch, no virtual dispatch, no interface
   vtable lookup at the time of registration.
4. All type information (parameter types, return type) must be **fully concrete** at code-generation
   time; the generator reads metadata once at Gradle build time and emits static code.

### Constructs that cannot cross and why

#### 1. Overload sets (multiple methods with the same name in the same type)

ECMA-335 §II.22.26 (`MethodDef` table) stores methods by name; the same name with different
signatures is legal in .NET metadata and represents an overload. A C export symbol has no
equivalent polymorphism: two `[UnmanagedCallersOnly]` methods must have distinct names. When the
generator encounters two methods `Foo(int)` and `Foo(string)` on the same type, it cannot export
both under the symbol `TypeFoo` without a disambiguation strategy. Possible strategies (append
parameter type names, append index) would produce non-obvious Kotlin names and create ABI
instability if the C# library adds an overload. The ADR-034 precedent: fail-fast rather than
silently dropping one of the methods.

**v1 disposition:** skip the entire overload set (all members with the same name) and emit a
Gradle warning naming each skipped method and why. The escape hatch is to add a C# adapter that
re-exposes the desired overloads under distinct names (see Future Improvements).

#### 2. `ref struct` / `Span<T>` / `ReadOnlySpan<T>`

`ref struct` types (annotated with the `IsByRefLike` attribute in ECMA-335) are stack-only. The
C# language reference is explicit: "You can't box a `ref struct` to `System.ValueType` or
`System.Object`." ([ref struct types](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/ref-struct)).
Because `GCHandle` requires a managed heap object, a `ref struct` cannot be wrapped in a
`GCHandle` and cannot serve as an opaque handle on the Kotlin side. Any `[UnmanagedCallersOnly]`
parameter or return type that is or contains a `ref struct` is rejected by the .NET runtime at
registration time; there is no workaround at the ABI level.

**v1 disposition:** skip any method or property whose parameter or return type is a `ref struct`
(including `Span<T>`, `ReadOnlySpan<T>`, and any user-defined `ref struct`). Emit a Gradle
warning naming the member and the type.

#### 3. Open (uninstantiated) generics

ECMA-335 §II.9 defines open generic types: a `TypeSpec` or `TypeDef` whose generic parameters
(`GenericParam` rows) have no corresponding type arguments. At code-generation time the Gradle
plugin reads metadata for the resolved package; it sees `IEnumerable<T>` but `T` is unknown. No
concrete thunk signature can be emitted. `[UnmanagedCallersOnly]` requires concrete types in all
parameter and return positions. Instantiated generic usages (e.g., `IEnumerable<string>` appearing
as a parameter type in a concrete method) are not affected — those have concrete types and can be
mapped per the collection-type ADR mirror.

**v1 disposition:** skip methods/properties where the signature contains an open generic type
parameter (i.e., the type is `IsGenericTypeDefinition == true` at the parameter/return position
without a concrete type argument). Emit a Gradle warning. Instantiated generics are handled
separately per the collection and generics reverse ADRs.

#### 4. `dynamic`

In ECMA-335 metadata, `dynamic` compiles to `System.Object` with a `[DynamicAttribute]` custom
attribute applied to the parameter/return position. All operations on a `dynamic` value are
dispatched at runtime through the Dynamic Language Runtime (DLR) via `CallSite<T>` and binder
objects. A `[UnmanagedCallersOnly]` thunk cannot represent DLR late-binding: the thunk is a static
method with a concrete signature, but calling code through `dynamic` requires invoking runtime
binders that themselves require the CLR and DLR to be operational. Passing an `IntPtr`/opaque
handle into a method that expects `dynamic` would not trigger the expected DLR dispatch.

**v1 disposition:** skip any method or property whose signature contains `dynamic` (detected via
the presence of `DynamicAttribute` on the parameter/return type). Emit a Gradle warning.

#### 5. Default interface methods (DIMs)

C# 8 / .NET Core 3.0 introduced default interface members: concrete method implementations
declared directly on an interface type. In ECMA-335, these appear as non-abstract `MethodDef`
entries in an interface `TypeDef` that have an IL method body. Two problems arise:

- **Discovery**: the plugin would need to generate a thunk for the interface type itself, not a
  concrete class. Interfaces cannot be instantiated; a `[UnmanagedCallersOnly]` thunk for a DIM
  would require a concrete implementing type to dispatch through, but the implementing type is
  chosen by the caller at runtime.
- **Registration**: the startup registration table maps Kotlin stub calls to named C# functions
  (ADR-036 mechanism). A DIM has no stable single-class home: it is invoked through any
  implementing type that did not override it. There is no single `[UnmanagedCallersOnly]` function
  that can represent "call the DIM on whatever implementing type the caller provides."

Note: DIM-as-extension-method patterns (where the DIM provides a utility over the interface) are
typically re-exposed by concrete implementing types and would be bridgeable via those concrete
implementations. The problem is DIMs on interfaces that have no accessible concrete implementing
type in the package.

**v1 disposition:** skip default interface method entries (non-abstract, non-static methods on
interface types that have an IL body). Emit a Gradle warning naming the interface and method.
Static interface methods with a default implementation (a separate feature, also called "static
abstract members") are a distinct case; those may be auto-mappable if they are static and
concrete-typed, handled per the static-method ADR.

#### 6. Async shapes (`Task<T>`, `ValueTask<T>`, `IAsyncEnumerable<T>`)

These are **not structurally excluded** from the C ABI — `Task<T>` can be wrapped in a
`GCHandle`-backed opaque handle, and the forward direction (ADR-019) already maps `suspend fun` to
`Task<T>`. The reverse mapping (`Task<T>` → `suspend fun`) is therefore achievable and mirrors
ADR-019. However, each async shape needs its own design decisions (how to bridge the completion
callback, how to handle cancellation, how to map `ValueTask<T>` vs `Task<T>`, how to map
`IAsyncEnumerable<T>` as a `Flow`). These are deferred to individual per-feature ADRs per the
ROADMAP ("Map `Task<T>` → `suspend fun`", "Map C# collections and generics subsets").

**v1 disposition:** `Task<T>` / `ValueTask<T>` / `IAsyncEnumerable<T>` — defer to per-feature
reverse ADRs; **not** skip + diagnose. The generator emits an informational note that these are
recognized types not yet auto-mapped in v1.

#### 7. Cross-runtime inheritance (Kotlin subclassing a C# class)

Swift Export (JetBrains' own idiomatic-export effort, architecturally the closest analogue to this
project's goal layer) currently allows only **final classes directly inheriting `Any`** and
forbids cross-language subclassing. JetBrains cites the proportionate difficulty of cross-runtime
inheritance. The same constraint applies here in the reverse direction: Kotlin cannot subclass a
C# class because there is no ABI mechanism to forward `virtual` dispatch from the C# base class's
vtable into Kotlin's object system. The synthesis document (D5) records this decision explicitly.

Implementing a C# **interface** from Kotlin (by generating a bridge that calls back into Kotlin via
the Phase 7 function-pointer machinery, ADR-039) is in scope for Phase 8, as it uses the same
mechanism already built for bidirectional interface bridging.

**v1 disposition:** Kotlin subclassing a C# class — explicitly out of scope. Emit a Gradle note on
any C# class that would require subclassing to be usable (e.g., abstract classes with no factory
method). Implementing a C# interface from Kotlin — in scope (composes with Phase 7 ADR-039).

### Summary table

| Construct | ECMA-335 origin | Why it cannot cross the C ABI | v1 disposition |
|---|---|---|---|
| Overload set (same method name, different params) | `MethodDef` rows with identical `Name`, distinct `Signature` | C has no overloading; a unique export symbol is required per method; disambiguation strategies are unstable | skip entire overload set + diagnose |
| `ref struct` / `Span<T>` / `ReadOnlySpan<T>` | `TypeDef` with `IsByRefLike` custom attribute | Cannot be boxed, cannot be wrapped in `GCHandle`, cannot be a `[UnmanagedCallersOnly]` parameter/return type | skip member + diagnose |
| Open (uninstantiated) generic | `TypeSpec` with unresolved `GenericParam` in signature | No concrete type at code-gen time; no concrete thunk signature possible | skip member + diagnose |
| `dynamic` | `System.Object` + `DynamicAttribute` custom attribute on parameter | DLR late-binding cannot be represented in a static `[UnmanagedCallersOnly]` signature | skip member + diagnose |
| Default interface method (DIM) | Non-abstract `MethodDef` with IL body on an interface `TypeDef` | No single concrete implementing type to hang the thunk on; runtime dispatch cannot be registered statically | skip member + diagnose |
| `Task<T>` / `ValueTask<T>` / `IAsyncEnumerable<T>` | Concrete instantiated generic types | Bridgeable in principle (mirror of ADR-019/026); needs per-feature reverse ADR | defer to future feature ADR |
| Kotlin subclassing a C# class | C# class with `virtual` / `abstract` members | No ABI to forward C# vtable dispatch into Kotlin; Swift Export precedent | explicitly out of scope for v1 |
| Implementing a C# interface from Kotlin | C# interface type in metadata | Phase 7 N-flat-function-pointer mechanism (ADR-039) applies | in scope |

## Alternatives Considered

### 1. v1 = auto-mappable subset + skip with diagnostics (chosen)

The generator binds the auto-mappable slice (static methods, primitives, strings, void per
ROADMAP v1 scope) and, for each member it cannot map, emits a named Gradle warning:

```
w: [nuget] Skipping method Foo.Overloaded(int): overload set — C# method `Overloaded` has 3
   overloads that cannot be distinguished at the C export boundary. Add a C# adapter shim to
   expose distinct names. (package: SomeLib.Core, type: Foo)

w: [nuget] Skipping method Parser.Parse(ReadOnlySpan<char>): ref struct parameter —
   ReadOnlySpan<char> is a stack-only type (IsByRefLike) and cannot cross the C ABI via GCHandle.
   (package: SomeLib.Core, type: Parser)

w: [nuget] Skipping property DynamicService.Config: dynamic return type — `dynamic` is late-bound
   via the DLR and has no static C ABI representation. (package: SomeLib.Core, type: DynamicService)
```

The rest of the package is bound normally. This matches the project's fail-fast convention
(CLAUDE.md): the boundary is visible, named, and actionable.

**Pros:** maximum usable surface area from the auto-mappable slice; user knows exactly what is
missing and why; consistent with ADR-034 precedent. **Cons:** some packages' most important APIs
may fall outside the subset.

### 2. Skip silently (rejected)

Members outside the subset are not bound and not mentioned. The Kotlin developer discovers the gap
at call-site in IntelliSense or at runtime.

**Rejected:** violates the fail-fast convention (CLAUDE.md). spm4Kmp's structural communication
(the `StartYourBridgeHere.swift` folder) exists precisely because silent omission is a poor user
experience — the Kotlin developer has no way to know what is missing.

### 3. Hard error (abort) if any non-mappable member is found (rejected)

The generator fails the Gradle build if the package contains any member outside the
auto-mappable subset.

**Rejected:** too restrictive. Virtually every real-world .NET library contains at least one
overload set. Blocking the entire package because `string.Format` is overloaded would make Phase 8
unusable for any mainstream NuGet package.

### 4. Hand-written C# adapter shim that the plugin also binds (deferred, Future Improvements)

spm4Kmp's model: the user writes a thin C# project (`MyLibAdapter/`) that re-exposes the
non-mappable members under distinct, C-ABI-friendly names; the plugin discovers this project and
also generates bindings for it. This gives full coverage at the cost of hand-writing the adapter.

**Deferred:** adds complexity to the plugin's discovery and build pipeline (it must now bind
multiple projects). Recorded in ROADMAP Future Improvements and synthesis D2. The skip + diagnose
diagnostic tells the user exactly what to put in the adapter.

## Decision

Use **Alternative 1: auto-mappable subset only with named per-member diagnostics**.

The generator:

1. Reads ECMA-335 metadata for each selected package/namespace (per the DSL from synthesis D4).
2. For each `public` type member, applies the bridgeable-subset filter:
   - Overload sets → skip all methods in the set; one warning per set naming all method signatures.
   - `IsByRefLike` parameter or return → skip the member; one warning naming the type.
   - Open generic in signature → skip the member; one warning.
   - `DynamicAttribute` in signature → skip the member; one warning.
   - Non-abstract method on an interface type with IL body (DIM) → skip the member; one warning.
3. The remaining members are forwarded to the reverse-IR model (`nuketExtractApi` stage) for
   binding generation.
4. Each warning is a Gradle warning (not an error) so the project still builds with the
   auto-mappable slice.

### Diagnostic format

```
w: [nuget:<PackageName>] Skipping <type>.<member>(<signature>): <reason>.
   <actionable hint>
```

Examples:

```
w: [nuget:Newtonsoft.Json] Skipping JObject.Parse(ReadOnlySpan<char>): ref struct parameter —
   ReadOnlySpan<char> is stack-only (IsByRefLike) and cannot be passed via GCHandle across
   the C ABI. Expose this method in a C# adapter shim with a string parameter instead.

w: [nuget:Newtonsoft.Json] Skipping JToken.SelectToken(string) and 2 other overloads: overload
   set — methods named `SelectToken` cannot be uniquely exported to C without disambiguation.
   Add a C# adapter shim that re-exposes each overload under a distinct name.

w: [nuget:SomeLib] Skipping IDomainService.DefaultHandler: default interface method — DIMs have
   no single concrete export site. Override this method in a concrete adapter class.
```

### Cross-runtime inheritance (D5, per synthesis)

Kotlin **subclassing** a C# class is explicitly out of scope for v1. The generator emits an
informational note for C# abstract classes that have no factory method exposed through the
auto-mappable subset (because they would be unusable without subclassing). Kotlin **implementing**
a C# interface is in scope: the Phase 7 N-flat-function-pointer mechanism (ADR-039) and its
registration machinery apply directly in the reverse direction.

### Async shapes

`Task<T>`, `ValueTask<T>`, `IAsyncEnumerable<T>` are recognized as "not-yet-mapped" types in v1
and receive an informational note (not a skip warning): they are achievable in principle (mirrors
of ADR-019/026) but need individual per-feature reverse ADRs before the generator can emit
bindings for them.

## Consequences

- The reverse generator gains a `BridgeableSubsetFilter` stage (or equivalent logic in
  `nugetExtractApi`) that checks each member against the five exclusion rules before forwarding
  it to the reverse-IR model.
- Gradle warnings are surfaced per skipped member; zero new errors are added to builds that
  use the auto-mappable subset correctly.
- The escape hatch (hand-written C# adapter shim) is documented in the Gradle warning text and in
  ROADMAP Future Improvements (synthesis D2), but not implemented in v1.
- Kotlin subclassing of C# classes is documented as out of scope (synthesis D5, Swift Export
  precedent). Implementing C# interfaces from Kotlin composes with ADR-039.
- When per-feature reverse ADRs for `Task<T>`, `IAsyncEnumerable<T>`, etc. are implemented, the
  filter moves those constructs from "informational note" to "auto-mapped."

### Scope

**v1 auto-mappable (within the static-methods/primitives/strings/void scope of Phase 8):**
- `static` and instance methods with primitive / `string` / `void` / object-handle signatures
- Properties whose getter/setter has the same type constraints
- C# interfaces (for Kotlin implementation via ADR-039 machinery)

**Excluded / skip + diagnose (this ADR):**
- Overload sets
- `ref struct` / `Span<T>` / `ReadOnlySpan<T>` members
- Open generic members
- `dynamic` members
- Default interface methods

**Deferred to per-feature reverse ADRs:**
- `Task<T>` / `ValueTask<T>` (mirror of ADR-019)
- `IAsyncEnumerable<T>` (mirror of ADR-026)
- C# collections / generics subsets (mirror of ADR-010/011)

**Explicitly out of scope (v1 and beyond, synthesis D5):**
- Kotlin subclassing C# classes

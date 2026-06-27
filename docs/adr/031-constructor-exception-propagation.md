# ADR-031: Constructor exception propagation

## Status

Proposed

## Context

[ADR-024](024-sync-exception-propagation.md) established synchronous exception
propagation for non-suspend functions and class methods: every sync `@CName`
export gains a trailing `errorOut: COpaquePointer?` parameter, the body is
wrapped in `try/catch (e: Throwable)`, and on catch a `StableRef<NugetError>`
is written through the pointer via the shared `buildError(e)` helper. The C#
wrapper checks `out IntPtr error` and throws a mapped `Exception` subtype via
`NugetErrorNative.BuildException`.

[ADR-030](030-property-exception-propagation.md) extended the same pattern to
property accessors.

Both ADRs explicitly deferred constructor exceptions:

> ADR-024: "Constructor exceptions — constructor failure has different semantics
> (partially-constructed objects); deferred."

> ADR-030: "Constructor exception propagation — partially-constructed-object
> semantics; tracked as a separate roadmap item."

The constructor export (`${prefix}_create`) is the last remaining sync export
site without an error channel. Without it, an `init { require(…) }` block
crosses an export that has no error channel — Kotlin/Native calls
`terminateWithUnhandledException` and the process aborts.

### The partially-constructed-object wrinkle

For regular functions and properties, the dummy return on the error path is
unambiguous: zero for integers, `false` for booleans, `null` for pointers. For
a constructor, the "normal" success return is a non-null `COpaquePointer` —
there is no natural dummy value that distinguishes "no object created" from a
valid object handle.

The solution: change the constructor export return type to `COpaquePointer?`
(nullable). On the error path, return `null` instead of a bogus pointer. The
C# `[DllImport]` declaration already uses `IntPtr`, and `IntPtr.Zero` is the
natural representation of a null pointer.

The C# constructor wrapper must:
1. Call `Native_Create(…, out IntPtr error)` into a local `IntPtr handle`.
2. Check `error != IntPtr.Zero` and throw **before** assigning `_handle`.
3. Assign `_handle = handle` only on the success path.

Since a C# constructor that throws never returns an object reference to the
caller, `_handle` is never set to an invalid value. The `Dispose()` guard
`if (_handle != IntPtr.Zero) return;` means any stale path is a no-op. The
partially-constructed C# wrapper object is immediately eligible for GC, and no
Kotlin `StableRef` was created because the Kotlin constructor failed before
`StableRef.create(…)` was reached.

### How other Kotlin interop targets handle throwing constructors

#### Java (JVM)

Kotlin `init {}` blocks compile into the JVM `<init>` bytecode method. If the
init block throws, the exception propagates to the call site as a normal Java
exception — no partial object handle is ever returned. The JVM's object
allocation and constructor invocation are atomic from the caller's perspective,
so no special treatment is needed.

#### ObjC/Swift Export

ObjC initializers return `id`, which can be `nil`. Kotlin/Native's ObjC export
maps `@Throws`-annotated constructors to init methods that set `NSError**` and
return `nil` on failure.

Swift wraps this as a throwing `init() throws` — if Kotlin sets NSError and
returns nil, Swift throws. A failed init cannot return a partially-constructed
`self`.

Source: [Kotlin/Native ObjC interoperability — Errors and exceptions](https://kotlinlang.org/docs/native-objc-interop.html#errors-and-exceptions)

Key insight: ObjC/Swift returns a typed null on the error path — exactly what
the nullable `COpaquePointer?` return achieves on the C side.

#### Swift Export (Alpha)

Same mechanism as ObjC export. Swift's own failing initializers use `init?` or
`init() throws`, both of which communicate failure without returning a valid
instance.

Source: [Kotlin/Native Swift Interoperability](https://kotlinlang.org/docs/native-swift-interop.html#exceptions)

#### Kotlin/JS

`@JsExport` constructors throw standard JavaScript `Error` objects when a
Kotlin exception escapes. JavaScript callers use `try { new Foo(); } catch(e)
{}`. No partial object handle is returned because the JS runtime discards the
partially-constructed object when the constructor throws.

#### Kotlin/Wasm

The Wasm component model has limited structured exception support in current
Kotlin/Wasm releases. Constructor exceptions may trap depending on target
configuration; no typed error bridge is defined.

### C# idiomatic constructor exceptions

In .NET, throwing from a constructor is the standard validation pattern
mandated by the Framework Design Guidelines:

> "Do throw exceptions from constructors."

Source: [Constructor Design — .NET Framework Design Guidelines](https://learn.microsoft.com/en-us/dotnet/standard/design-guidelines/constructor)

If a constructor throws, the CLR guarantees the object reference is never
assigned to the caller. C# consumers naturally write:

```csharp
try
{
    var cat = new Cat("Oreo", -1);
}
catch (ArgumentException ex)   // or KotlinArgumentException per ADR-029
{
    logger.LogError("Bad argument: {Message}", ex.Message);
}
```

This is already how C# developers handle all constructors that validate inputs —
no special idiom is needed.

## Decision

**Extend the ADR-024 error-out-parameter pattern to the constructor export.**
The constructor export changes its return type from `COpaquePointer` to
`COpaquePointer?` (nullable), using `null` as the error-path dummy return
instead of a bogus handle. Do not require `@Throws`.

### Kotlin export shape (ClassExports.kt)

Before:

```kotlin
addFunction(
  FunSpec.builder("export_${prefix}_create")
    .addAnnotation(cNameAnnotation("${prefix}_create"))
    .addParameters(constructor)
    .returns(cOpaquePointer)
    .addStatement("return %T.create(%L(%L)).asCPointer()", stableRef, qualifiedName, ctorParamCall)
    .build()
)
```

After:

```kotlin
addFunction(
  FunSpec.builder("export_${prefix}_create")
    .addAnnotation(cNameAnnotation("${prefix}_create"))
    .addParameters(constructor)
    .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
    .returns(cOpaquePointer.copy(nullable = true))
    .addCode(buildString {
      appendLine("return try {")
      appendLine("  %T.create(%L(%L)).asCPointer()")
      appendLine("} catch (e: Throwable) {")
      appendLine("  if (errorOut != null) {")
      appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
      appendLine("      buildError(e)")
      appendLine("    ).asCPointer()")
      appendLine("  }")
      appendLine("  null")
      append("}")
    }, stableRef, qualifiedName, ctorParamCall, cOpaquePointerVar, stableRef)
    .build()
)
```

### C# P/Invoke declaration (CirClassRenderer.kt `renderClass`)

Before:

```csharp
[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_create")]
private static extern IntPtr Native_Create(string name, int age);
```

After:

```csharp
[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_create")]
private static extern IntPtr Native_Create(string name, int age, out IntPtr error);
```

### C# wrapper constructor (CirClassRenderer.kt `renderConstructor`)

Before:

```csharp
public Cat(string name, int age)
{
    _handle = Native_Create(name, age);
}
```

After:

```csharp
public Cat(string name, int age)
{
    IntPtr handle = Native_Create(name, age, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    _handle = handle;
}
```

For classes that extend another bridged class (where the renderer emits
`: base(IntPtr.Zero)`), the body is identical. The base constructor receives
`IntPtr.Zero` transiently, but since the C# constructor throws before returning,
the object is never exposed to the caller. `_handle` is inherited from the base
and remains `IntPtr.Zero`; `Dispose()` is a safe no-op.

### Data class `copy()` (ClassExports.kt, CirClassRenderer.kt `renderDataClassMethods`)

Kotlin's `copy()` calls the primary constructor, which re-runs `init` blocks.
A `copy()` call with modified arguments that violate class invariants will
throw. The `export_${prefix}_copy` export must also gain `errorOut` and
`try/catch (e: Throwable)` (same pattern as the constructor, returning
`COpaquePointer?` null on error). The C# `Copy()` wrapper checks the error
before constructing the new wrapper object.

Kotlin side before:

```kotlin
copyBuilder.addStatement(
  "return %T.create(handle.asStableRef<%L>().get().copy(%L)).asCPointer()",
  stableRef, qualifiedName, copyParamCall,
)
```

Kotlin side after — wrap in try/catch with `errorOut` parameter added to the
builder, same as the constructor.

C# `Copy()` before:

```csharp
public Cat Copy(string name, int age) => new Cat(Native_Copy(_handle, name, age));
```

C# `Copy()` after:

```csharp
public Cat Copy(string name, int age)
{
    IntPtr handle = Native_Copy(_handle, name, age, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return new Cat(handle);
}
```

### CIR model change (CirModel.kt)

Add `hasSyncErrorOut: Boolean = false` to `CirConstructor`:

```kotlin
data class CirConstructor(
  val parameters: List<CirParameter>,
  val body: String,
  val hasSyncErrorOut: Boolean = false,
)
```

`CirClassTranslator.kt` sets `hasSyncErrorOut = true` unconditionally for all
non-abstract class constructors (same wrap-all philosophy from ADR-024).

### Consumer experience

```csharp
// Regular class with throwing init block
try
{
    var cat = new Cat("Oreo", -1);   // init { require(age >= 0) }
}
catch (KotlinArgumentException ex)   // ArgumentException subtype per ADR-029
{
    // ex.KotlinType == "kotlin.IllegalArgumentException"
    // ex.Message    == "age must be non-negative"
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);
}

// Data class copy — same pattern
var valid = new Cat("Oreo", 3);
try
{
    var invalid = valid.Copy("Oreo", -1);
}
catch (KotlinArgumentException ex)
{
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);
}
```

## Consequences

### Breaking changes

- The constructor `@CName` export gains a trailing `COpaquePointer?` parameter
  and changes its return type from `COpaquePointer` to `COpaquePointer?` — a C
  ABI change. All exports and P/Invoke declarations are generated, so no manual
  changes are needed.
- The `copy()` export likewise gains `errorOut` and a nullable return.
- Generated C# constructors and `Copy()` now throw `Exception` subtypes (per
  ADR-029 mapping) instead of crashing the process. This is a behavior change
  and strictly an improvement.

### Affected files

- `ClassExports.kt` — constructor export (`export_${prefix}_create`); data
  class `copy()` export (`export_${prefix}_copy`).
- `CirModel.kt` — `CirConstructor` gains `hasSyncErrorOut: Boolean = false`.
- `CirClassTranslator.kt` — sets `hasSyncErrorOut = true` in constructor
  translation; `copy()` Kotlin body and C# body updated.
- `CirClassRenderer.kt` — `renderClass` adds `out IntPtr error` to the
  `[DllImport]` for `Native_Create` when `hasSyncErrorOut`; `renderConstructor`
  emits the error-check block and deferred `_handle` assignment;
  `renderDataClassMethods` updates the `Copy()` wrapper.

### Scope

**This ADR:**
- Primary constructor exception propagation for regular and derived (subclass)
  classes.
- Data class `copy()` exception propagation.

**Deferred:**
- Generic class constructor variants (`create_string`, `create_int`, etc. in
  `GenericClassExports.kt`) — different code path and C# rendering (12 typed
  variants plus `create_object`, separate `renderGenericClass`); tracked as a
  follow-up.
- Value class secondary constructors (`CirValueClassConstructor` rendered via
  `: this(body)` in `record struct`) — C#'s delegating-constructor chain syntax
  does not allow inserting an error-check block between the delegate call and
  the body; requires a different approach and is tracked separately.
- Secondary constructors for regular classes — none are currently exported by
  the KSP processor; not in scope.

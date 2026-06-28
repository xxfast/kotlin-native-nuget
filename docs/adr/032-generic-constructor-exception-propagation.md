# ADR-032: Generic class constructor exception propagation — error-out across typed create variants

## Status

Proposed

## Context

[ADR-031](031-constructor-exception-propagation.md) added constructor exception
propagation for regular (non-generic) classes: the `${prefix}_create` export
gained a trailing `errorOut: COpaquePointer?` parameter, a `try/catch
(e: Throwable)` body, and a nullable `COpaquePointer?` return that yields `null`
on the error path. The C# constructor calls `Native_Create(…, out IntPtr error)`,
throws via `NugetErrorNative.BuildException(error)` before assigning `_handle`,
and assigns `_handle` only on success.

ADR-031 explicitly deferred generic classes:

> Generic class constructor variants (`create_string`, `create_int`, etc. in
> `GenericClassExports.kt`) — different code path and C# rendering (12 typed
> variants plus `create_object`, separate `renderGenericClass`); tracked as a
> follow-up.

This ADR closes that gap. A generic class with a validating `init` block — e.g.
`class Box<T>(val value: T) { init { require(value != null) } }`, or any user
invariant — currently crosses an export with no error channel, so Kotlin/Native
calls `terminateWithUnhandledException` and the process aborts.

### How generic construction crosses the boundary today

Unlike a regular class (one `${prefix}_create` export), a generic class is
type-erased and emits **fourteen** construction exports
(`GenericClassExports.kt`):

- Twelve primitive-typed variants `export_${prefix}_create_<type>`
  (`string`, `byte`, `ubyte`, `short`, `ushort`, `int`, `uint`, `long`, `ulong`,
  `float`, `double`, `bool`) — emitted only when the type parameter has **no
  non-trivial bound**.
- `export_${prefix}_create_object` — always emitted; takes a `COpaquePointer`
  for the (reference-typed) argument.

Each is currently a one-liner returning a non-null `cOpaquePointer`:

```kotlin
.returns(cOpaquePointer)
.addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
```

On the C# side there are **two** construction code paths (`renderGenericClass`
in `CirClassRenderer.kt`):

1. **Unconstrained** (`where T : … ` absent): the single public constructor
   `public Box(T value)` calls the centralized runtime dispatcher
   `_handle = NugetMarshal.CreateBox<T>(value);`. `CreateBox<T>` (rendered by
   `CirMarshalRenderer.kt`) holds the `box_create_*` `[DllImport]` declarations
   and a `typeof(T)`-based dispatch that picks the matching native variant.
2. **Constrained** (`where T : SomeBound`): the constructor calls
   `_handle = BoxNative.Create_object((IntPtr)field!.GetValue(value)!);` via the
   per-class `${Name}Native.Create_object` `[DllImport]`.

### Is this just ADR-031 repeated?

The Kotlin side **is** a mechanical repeat of ADR-031 across fourteen exports:
each gains `errorOut`, a `try/catch`, and a `COpaquePointer?` return. The C#
side is **not** a mechanical repeat — it raises one genuine design question:
where does the error check live, given that the native call is hidden behind a
runtime dispatcher (`CreateBox<T>`) for the unconstrained path? That choice is
what this ADR records.

### How other Kotlin interop targets handle it

Generic classes are erased on every target, so none expose per-type-argument
constructor entry points the way our C bridge must. The relevant precedent is
purely ADR-031's own analysis (Java `<init>` propagation, ObjC/Swift typed-null
init, JS `Error`) — a throwing generic constructor is observed identically to a
throwing non-generic one. C# developers likewise expect `new Box<int>(…)` to
throw exactly as `new Cat(…)` does (Framework Design Guidelines: "Do throw
exceptions from constructors").

## Alternatives Considered

### 1. Centralize the error check in `CreateBox<T>` + per-class constructor for the constrained path (chosen)

All fourteen Kotlin exports gain `errorOut` + nullable return. On the C# side:

- **Unconstrained**: `NugetMarshal.CreateBox<T>` becomes the single error-check
  site. Each `box_create_*` `[DllImport]` gains `out IntPtr error`; `CreateBox<T>`
  captures the handle, checks `error != IntPtr.Zero`, throws
  `NugetErrorNative.BuildException(error)`, and returns the handle. The generated
  `public Box(T value)` body is unchanged (`_handle = NugetMarshal.CreateBox<T>(value);`)
  — it transparently throws because `CreateBox<T>` throws before returning.
- **Constrained**: `Create_object` gains `out IntPtr error` and the generated
  constructor checks it before assigning `_handle`, exactly as ADR-031.

Pros: one error-check site for the twelve primitive variants instead of twelve;
the public constructor surface and idiom (`new Box<int>(…)`) are unchanged; reuses
the existing ADR-031 C# helper. Cons: `CreateBox<T>` grows a few lines; two
distinct sites (dispatcher + constrained constructor) carry the check.

### 2. Inline the error check in every generated constructor / dispatch arm

Have `CreateBox<T>` return `(IntPtr handle, IntPtr error)` (or `out IntPtr error`)
and check in the public constructor. Pros: error check visible at the
constructor. Cons: pushes the check into both the unconstrained and constrained
public constructors and complicates the dispatcher's signature for no consumer
benefit; the per-`typeof(T)` arms still each need the `out` plumbing. More code,
no clearer behavior.

## Decision

Adopt **Alternative 1**. Apply the ADR-031 error-out pattern to all fourteen
generic construction exports, and centralize the C# error check inside
`NugetMarshal.CreateBox<T>` for the unconstrained path while applying the
ADR-031 per-constructor check to the constrained `Create_object` path.

### Kotlin export shape (`GenericClassExports.kt`)

Each `export_${prefix}_create_<type>` and `export_${prefix}_create_object`
changes from the one-liner to the ADR-031 body:

```kotlin
FunSpec.builder("export_${prefix}_create_int")
  .addAnnotation(cNameAnnotation("${prefix}_create_int"))
  .addParameter("value", Int::class)
  .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  .returns(cOpaquePointer.copy(nullable = true))
  .addCode(buildString {
    appendLine("return try {")
    appendLine("  %T.create(%L(value)).asCPointer()")
    appendLine("} catch (e: Throwable) {")
    appendLine("  if (errorOut != null) {")
    appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(buildError(e)).asCPointer()")
    appendLine("  }")
    appendLine("  null")
    append("}")
  }, stableRef, qualifiedName, cOpaquePointerVar, stableRef)
  .build()
```

(Factor the repeated builder into a helper to avoid fourteen copies.)

### C# `NugetMarshal` (`CirMarshalRenderer.kt`)

`box_create_*` declarations gain `out IntPtr error`; `CreateBox<T>` checks once:

```csharp
private static extern IntPtr box_create_int(int value, out IntPtr error);
// …

public static IntPtr CreateBox<T>(T value)
{
    IntPtr error;
    IntPtr handle;
    if (typeof(T) == typeof(string)) handle = box_create_string((string)(object)value!, out error);
    else if (typeof(T) == typeof(int)) handle = box_create_int((int)(object)value!, out error);
    // … remaining arms …
    else throw new NotSupportedException($"Unsupported box type {typeof(T)}");

    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return handle;
}
```

### C# constrained path (`renderGenericClass`)

```csharp
internal static extern IntPtr Create_object(IntPtr value, out IntPtr error);
// …
public Box(T value)
{
    var field = typeof(T).GetField("_handle", …);
    IntPtr handle = BoxNative.Create_object((IntPtr)field!.GetValue(value)!, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    _handle = handle;
}
```

### Consumer experience

```csharp
try
{
    var box = new Box<int>(-1);   // init { require(value >= 0) }
}
catch (KotlinArgumentException ex)   // ArgumentException subtype per ADR-029
{
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);
}
```

## Consequences

- Fourteen generic construction exports change their C ABI (trailing
  `COpaquePointer?` + nullable return). All exports and P/Invoke declarations are
  generated, so no manual changes are needed.
- `CreateBox<T>` and the constrained `Create_object` constructor now throw mapped
  `Exception` subtypes instead of aborting the process.

### Affected files

- `GenericClassExports.kt` — all `create_*` variants and `create_object`.
- `CirMarshalRenderer.kt` — `box_create_*` `[DllImport]`s + `CreateBox<T>` error check.
- `CirClassRenderer.kt` (`renderGenericClass`) — `Create_object` `[DllImport]` +
  constrained constructor error check.
- `CirModel.kt` / `CirClassTranslator.kt` — only if a flag is needed to gate the
  error-out wiring (default-on, mirroring `CirConstructor.hasErrorCheck`).

### Scope

**This ADR:** primary-constructor exception propagation for generic classes,
both unconstrained (typed dispatch) and constrained (`create_object`) paths.

**Deferred:** generic class *constructors taking multiple arguments* beyond the
single type-parameter value (the current bridge only models `Box<T>(value: T)`);
generic *function* construction is unaffected.

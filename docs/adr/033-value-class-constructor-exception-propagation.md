# ADR-033: Value class constructor exception propagation — private-helper, not static factory

## Status

Proposed

## Context

[ADR-031](031-constructor-exception-propagation.md) added constructor exception
propagation for regular classes and deferred value (inline) classes:

> Value class secondary constructors (`CirValueClassConstructor` rendered via
> `: this(body)` in `record struct`) — C#'s delegating-constructor chain syntax
> does not allow inserting an error-check block between the delegate call and the
> body; requires a different approach and is tracked separately.

The ROADMAP phrases this as "needs static-factory redesign — `: this(...)` syntax
blocks error check". This ADR examines that hypothesis and concludes a static
factory is **not** required.

### How value class construction works today

`ValueClassExports.kt` emits a `${prefix}_create` (and `${prefix}_create_${index}`
for further constructors) export **only for secondary constructors** —
`cls.declarations.filterIsInstance<KSFunctionDeclaration>()… filter { it != cls.primaryConstructor }`.
Each export returns the **underlying value**, not a handle:

```kotlin
builder.returns(ClassName.bestGuess(underlyingType))
builder.addStatement("return %L(%L).%L", qualifiedName, paramCall, underlyingPropName)
```

`renderValueClass` (`CirObjectRenderer.kt`) emits a C# `readonly record struct`
whose primary parameter is the underlying value, plus one delegating constructor
per secondary constructor:

```csharp
public readonly record struct Email(string Raw)
{
    [DllImport("sample", … EntryPoint = "email_create")]
    private static extern IntPtr Native_Create(string value);

    public Email(string value) : this(Native_Create(value)) { }
}
```

Two consequences fall out of this shape:

1. **The `: this(Native_Create(value))` chain blocks an error check.** C#
   evaluates the delegate-call argument, then runs the delegated constructor,
   then the body — there is no statement position between the native call and the
   delegation in which to test an error.
2. **The return is the *underlying* value, not a class handle.** For a primitive
   underlying type (`string` → `IntPtr`, `int` → `int`, …) the error-path dummy
   return is a primitive, so this is the ADR-024 *function* error pattern
   (dummy return + `errorOut`), **not** the ADR-031 nullable-pointer pattern.

### A separate gap (out of scope, noted): primary-constructor validation

Because exports are emitted only for secondary constructors, a value class whose
invariant lives in its **primary** constructor's `init` block is constructed
entirely C#-side via the `record struct` primary parameter — Kotlin's `require`
is never called and never crosses the bridge. Enforcing primary-constructor
validation would require *always* exporting a create entry point and routing
`new Email(...)` through it. This ADR does not change that; see Scope.

### How other Kotlin interop targets handle throwing value-class constructors

Value classes are unboxed wherever possible. On JVM, a throwing inline-class
constructor propagates through `<init>` like any other. ObjC/Swift export boxes
inline classes and maps `@Throws` constructors to `NSError**` / `init() throws`
(see ADR-031's ObjC/Swift analysis). In all cases the observable contract is
identical to a regular throwing constructor — the consumer never receives a
partially-constructed value. C# developers expect the same from `new Email(...)`.

## Alternatives Considered

### 1. Private static helper, keep `new Email(...)` (chosen)

Extract the native call + error check into a private static method and call it
from the delegation argument, so the existing `: this(...)` shape is preserved:

```csharp
public readonly record struct Email(string Raw)
{
    [DllImport("sample", … EntryPoint = "email_create")]
    private static extern IntPtr Native_Create(string value, out IntPtr error);

    private static IntPtr CreateChecked(string value)
    {
        IntPtr underlying = Native_Create(value, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return underlying;
    }

    public Email(string value) : this(CreateChecked(value)) { }
}
```

`CreateChecked` runs as the delegation argument is evaluated; if it throws, the
delegated `record struct` constructor never runs and no value is returned to the
caller. For a primitive underlying type the helper's return type is the primitive
(e.g. `int`) and `Native_Create` returns a dummy primitive on the error path
(ADR-024 pattern); the helper still distinguishes success from failure via the
`out IntPtr error` flag, not the return value.

Pros: keeps the idiomatic `new Email(...)` surface; reuses ADR-029 mapping;
minimal change to `renderValueClass`. Cons: one extra private method per
constructor.

### 2. Static factory `Email.Create(...)`

Replace the throwing constructor with `public static Email Create(string value)`.
Pros: trivially has a statement body for the check. Cons: violates the C#-native
goal in GOALS.md — C# developers expect `new Email(...)`, and a `record struct`
already exposes the underlying-typed primary constructor, so two construction
idioms (`new` for the raw value, `Create` for the validated one) would coexist
confusingly. Rejected.

### 3. Out-parameter `record struct` body without delegation

Drop the `: this(...)` chain and assign the positional property inside the body.
A `readonly record struct`'s positional property is init-only and assignable in
the constructor body, so an error check could precede assignment. Pros: no helper
method. Cons: fights the record-struct positional-constructor model (must assign
the compiler-generated backing property by name, brittle across C# versions);
the helper approach is clearer and localized. Rejected in favor of 1.

## Decision

Adopt **Alternative 1**: generate a private `static` helper per value-class
constructor that performs the native call and the ADR-031/ADR-029 error check,
and call it from the existing `: this(...)` delegation. The public surface stays
`new Email(...)`.

### Kotlin export shape (`ValueClassExports.kt`)

The secondary-constructor export gains `errorOut` and the ADR-024 try/catch.
For a primitive underlying it returns a dummy primitive on error; for a reference
underlying it returns `null` (`COpaquePointer?`):

```kotlin
builder
  .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  .returns(/* underlying type, nullable if reference */)
  .addCode(/* try { return X(params).underlying } catch (e: Throwable) { errorOut?… ; <dummy> } */)
```

### CIR + renderer changes

- `CirValueClassConstructor` (`CirModel.kt`) gains `hasErrorCheck: Boolean = false`
  (mirrors `CirConstructor.hasErrorCheck`), set true in `CirClassTranslator.kt`.
- `renderValueClass` (`CirObjectRenderer.kt`): when `hasErrorCheck`, emit
  `Native_Create…(…, out IntPtr error)`, a `CreateChecked…` private static, and
  `: this(CreateChecked…(…))`.

### Consumer experience

```csharp
try
{
    var email = new Email("not-an-email");   // init { require("@" in raw) }
}
catch (KotlinArgumentException ex)
{
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);
}
```

## Consequences

- Value-class constructor exports gain a trailing `COpaquePointer?` `errorOut`
  parameter — a C ABI change; all generated.
- Generated value-class constructors now throw mapped `Exception` subtypes
  instead of aborting the process.

### Affected files

- `ValueClassExports.kt` — secondary constructor exports gain `errorOut` + try/catch.
- `CirModel.kt` — `CirValueClassConstructor.hasErrorCheck`.
- `CirClassTranslator.kt` — set `hasErrorCheck = true`.
- `CirObjectRenderer.kt` (`renderValueClass`) — `out IntPtr error`, `CreateChecked`
  helper, delegation through the helper.

### Scope

**This ADR:** exception propagation for value-class secondary constructors
(those currently exported via `: this(...)`), for both primitive and reference
underlying types.

**Deferred:**
- Enforcing **primary**-constructor `init` validation (no create export exists
  for it today — would require always routing construction through Kotlin).
- Value classes wrapping another bridged value/reference type beyond the existing
  underlying-reference support.

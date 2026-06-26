# ADR-030: Property getter/setter exception propagation

## Status

Proposed

## Context

[ADR-024](024-sync-exception-propagation.md) established synchronous exception
propagation for non-suspend functions and class methods: every sync `@CName`
export gains a trailing `errorOut: COpaquePointer?` parameter, the body is
wrapped in `try/catch (e: Throwable)`, and on catch a
`StableRef<...>` describing the error is written through the pointer
(via the shared `buildError(e)` helper). The C# wrapper checks the `out IntPtr error`
and throws a `KotlinException` subtype.

ADR-024 explicitly **deferred property getter/setter exceptions** ("uncommon in
Kotlin convention; deferred until needed") and **constructor exceptions**
(different semantics around partially-constructed objects). Property accessors
are now in scope; constructors remain deferred as a separate roadmap item.

### Why properties need this

Although most Kotlin properties are simple field accessors, custom getters and
setters can throw — e.g. a setter that validates its input
(`require(value >= 0)`) or a computed getter that fails. Today such an exception
crosses an export that has **no** error channel, so Kotlin/Native calls
`terminateWithUnhandledException` and the process aborts. This is the exact
crash ADR-024 eliminated for functions, and the same safety-by-default argument
(plug-and-play Goal #1) applies to property accessors.

### How ObjC/Swift export handles this

Kotlin/Native's ObjC/Swift export propagates exceptions only from
`@Throws`-annotated members, and **property accessors cannot be annotated with
`@Throws`** — so there is no upstream precedent to mirror for properties. A
throwing accessor under ObjC export simply terminates the process. Our wrap-all
approach (chosen in ADR-024 to avoid the `@Throws` annotation burden) is
therefore the only consistent option here.

## Decision

**Extend the ADR-024 error-out-parameter pattern to every property accessor
export** — top-level properties, class member properties, and extension
properties — for both getters and setters. Do not require `@Throws`.

This reuses ADR-024's mechanism verbatim (`errorOut: COpaquePointer?` +
`try/catch (e: Throwable)` + `buildError(e)` + C# `out IntPtr error` + throw
`KotlinException`). No new helpers, encodings, or C# types are introduced.

### Accessor variants

Every shape that produces a property accessor export gains the error channel:

| Accessor shape | Exports affected |
| --- | --- |
| Scalar primitive / object getter & setter | `get_<p>` / `set_<p>` |
| Nullable two-call (ADR-002) getter | `get_<p>_has_value` **and** `get_<p>_value` |
| Nullable two-call setter | `set_<p>` **and** `set_<p>_null` |
| Collection-typed getter (`_collect` handle) | `get_<p>` (and its `_collect` companion) |
| Extension property getter | `<receiver>_get_<p>` |

For the **nullable two-call pattern**, *both* native calls take `errorOut`: an
exception can be thrown on either the `has_value`/null-probe call or the
`_value`/assignment call, so each is independently wrapped. The C# wrapper checks
the error after each native call and throws before proceeding to the second.

The dummy return on the catch path follows the existing `defaultValueFor(...)`
convention (`false` for the `has_value` probe, the type default for value
getters, `null` for opaque-pointer returns); `Unit`-returning setters omit the
dummy return.

### Consumer experience

Identical to function exceptions — no new API surface:

```csharp
try
{
    cat.TreatCount = -1;          // setter validates and throws
}
catch (KotlinArgumentException ex) // ADR-029 mapping of IllegalArgumentException
{
    // ex.KotlinType == "kotlin.IllegalArgumentException"
    // ex.Message    == "Treat count cannot be negative"
}

int treats = cat.TreatCount;      // getter can likewise throw
```

## Consequences

### Breaking changes

- Every property accessor `@CName` export gains a trailing `COpaquePointer?`
  parameter — a C ABI change. All exports and P/Invoke declarations are
  generated, so no manual consumer changes are needed.
- Generated C# property getters/setters now throw `KotlinException` subtypes on
  Kotlin-side errors instead of crashing the process. Behavior change, strictly
  an improvement.

### Affected files

- `PropertyExports.kt` — top-level property getters/setters.
- `ClassExports.kt` — class property getter/setter branches (scalar, nullable
  two-call, collection, object).
- `ExtensionPropertyExports.kt` — extension property getters.
- `CirModel.kt` — `CirProperty.getter` / `setter` (and extra-native) bodies carry
  the error-check pattern.
- The C# property renderer — emit `out IntPtr error` on property `[DllImport]`
  declarations and the error-check block in the generated getter/setter bodies,
  mirroring how methods already render.

### Scope

**This ADR:** getter/setter exception propagation for top-level, class member,
and extension properties, across scalar, nullable two-call, collection, and
object variants.

**Deferred:**
- Constructor exception propagation — partially-constructed-object semantics;
  tracked as a separate roadmap item.
- `@Throws`-based opt-in optimization — not needed given the wrap-all approach.

# ADR-002: Two-call pattern for nullable types

## Status

Accepted

## Context

When bridging nullable Kotlin types (`Int?`, `String?`) across the C boundary to C#, we need a way to represent "null" since C primitives have no null concept.

Three approaches were considered:

1. **C struct with flag** — `{ bool has_value; int value; }` marshalled in a single call
2. **Two separate calls** — `func_has_value()` + `func_value()`, combined into a C# wrapper returning `Nullable<T>`
3. **Sentinel values** — reserve a magic value (e.g., `Int.MIN_VALUE`) to mean null

## Decision

Use the two-call pattern. For each nullable return, KSP generates:
- `funcName_has_value(...)` → returns `bool`
- `funcName_value(...)` → returns the unwrapped value

The C# wrapper combines these into a clean `T?` API:

```csharp
public static int? nullableInt(bool hasValue)
{
    if (!nullableInt_has_value(hasValue)) return null;
    return nullableInt_value(hasValue);
}
```

## Consequences

**Positive:**
- Simple to implement — no `@CStruct` or `kotlinx.cinterop` complexity on the Kotlin side
- Clean consumer API — caller sees `int?`, unaware of the two-call bridge
- No unsafe code or pointer manipulation for primitive nullables
- Works identically on all platforms

**Negative:**
- Two P/Invoke crossings per nullable access (~5-20ns each on modern .NET)
- The function is called twice with the same arguments (once for has_value, once for value) — if the function has side effects, this is incorrect

**Known limitation:** This pattern assumes the nullable function is pure (no side effects). If a function has side effects, calling it twice produces incorrect results. This will need to be revisited in Phase 3 when we support stateful objects.

## Future optimization

If profiling shows the two-call overhead is a bottleneck:
- Switch to a single-call C struct return (`{ bool has_value; T value; }`) using `@CStruct` interop
- Or cache the result on the Kotlin side between the `has_value` and `value` calls

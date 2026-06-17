# ADR-017: Inline Function Mapping

## Status
Proposed

## Context

Kotlin `inline fun` is a compile-time directive that inlines the function body at every call site. The primary motivations are:

1. Eliminate lambda object allocation when the function takes function-type parameters
2. Enable `reified` type parameters
3. Allow non-local `return` from lambda arguments

### Kotlin/Native export constraint

`@CName` **cannot be applied to an `inline fun` directly** — the Kotlin compiler rejects this with an error. Inline functions cannot appear as exported C symbols under their own name.

However, the KSP processor generates **wrapper functions** (`export_$cname`) with `@CName` that delegate to the original function. These wrappers are non-inline (KotlinPoet's `FunSpec` does not propagate the `inline` modifier). The C export layer already works correctly: `@CName` is applied to the non-inline wrapper, not to the original inline function.

### How other Kotlin interop targets handle inline

- **ObjC/Swift export**: Inline functions are included in the generated ObjC header as normal methods. `inline` is transparent.
- **JVM (Java interop)**: Inline functions compile to normal bytecode methods. Java callers see a regular method.
- **JS/Wasm**: Inline functions appear as normal functions in the output module.

The consensus across all targets: `inline` is a Kotlin-internal optimisation hint. All interop targets expose the function as a regular callable.

### C# perspective

C# has no `inline` keyword. The JIT compiler decides automatically whether to inline methods. `[MethodImpl(MethodImplOptions.AggressiveInlining)]` exists as a hint but is not semantically equivalent. For a P/Invoke bridge, inlining the C# wrapper provides no benefit — the call crosses into native code regardless.

## Alternatives Considered

### 1. Skip inline functions (log a warning, emit nothing)

Filter `Modifier.INLINE` in `NugetProcessor` and produce no export.

**Cons:** `inline fun` is a very common Kotlin idiom (all higher-order standard library functions are inline). Skipping them would make the bridge incomplete for any library following idiomatic Kotlin style.

### 2. Treat inline functions as regular functions (chosen)

Let inline functions flow through the same export pipeline as non-inline functions. The generated `@CName` wrapper is already non-inline by construction.

**Pros:**
- Correct and complete: the C# consumer sees all public functions regardless of `inline`.
- Zero implementation delta: the existing processor already produces the correct output.
- Consistent with what ObjC export, JVM, and JS interop targets do.

**Cons:** None.

### 3. Detect `inline` and explicitly generate non-inline wrappers

Check `Modifier.INLINE` and explicitly ensure the generated wrapper is non-inline.

**Cons:** Redundant — KotlinPoet's `FunSpec` never produces an `inline` function unless `addModifiers(KModifier.INLINE)` is called explicitly.

## Decision

**Treat `inline fun` as a regular function.** The existing pipeline already handles it correctly. No processor code changes are needed.

### Bridge mechanism

No changes. The generated `CNameExports.kt` wrapper:
```kotlin
@CName("greet")
fun export_greet(name: String): String = greet(name)
```
calls the original inline function. The compiler may inline `greet` at this call site — either way the C symbol is correctly exported.

### C# side

No changes. The binding uses the exported C symbol as for any other function:
```csharp
public static string Greet(string name) => ...;
```

### Example

Kotlin:
```kotlin
inline fun greet(name: String): String = "Hello, $name!"
```

Generated export (non-inline wrapper):
```kotlin
@CName("greet")
fun export_greet(name: String): String = greet(name)
```

Generated C#:
```csharp
public static string Greet(string name) => ...;
```

## Consequences

- No code changes required — the existing processor handles inline functions correctly.
- The `inline` modifier on the Kotlin source declaration is transparent to the bridge.
- Add a sample inline function and C# test to verify and document the behavior.

# ADR-004: CIR (C# Intermediate Representation) for code generation

## Status

Accepted

## Context

The current KSP processor emits C# source code by writing strings directly to a `Writer`:

```kotlin
writer.write("        [DllImport(\"$libraryName\", ...")
writer.write("        public static extern $csharpReturnType $csName(...);\n\n")
```

This approach is fragile:
- No validation that the emitted C# is syntactically correct
- Formatting logic mixed with translation logic
- Difficult to unit test (have to run the full KSP pipeline to verify output)
- Hard to refactor when adding new constructs (interfaces, generics)

We researched how Kotlin's Swift Export handles the same problem.

## Alternatives Considered

### 1. Raw string emission (current)
Write C# directly as strings. Simple, no abstraction layer.

**Pros:** Fast to prototype, minimal code.
**Cons:** Fragile, untestable, mixing concerns. Every new feature requires touching string formatting.

### 2. Template engine (Mustache, FreeMarker, etc.)
Use a template language to define C# output shapes.

**Pros:** Separates format from data.
**Cons:** Adds a runtime dependency, templates are stringly-typed, limited composability, poor IDE support for templates.

### 3. CIR model with recursive descent renderer (Kotlin Swift Export approach)
Define an AST model (`CirNamespace`, `CirClass`, `CirMethod`, etc.) that represents C# constructs. Translate Kotlin declarations to CIR nodes, then render CIR to text.

**Pros:**
- Type-safe — can't produce invalid C# structure
- Testable — verify translation logic (Kotlin → CIR) separately from rendering (CIR → text)
- Composable — build complex types from smaller nodes
- Aligns with how the Kotlin team solved this for Swift
- The model documents supported C# constructs explicitly

**Cons:** More code upfront (AST node classes + renderer).

## Decision

Adopt the CIR model approach, aligned with Kotlin's Swift Export architecture:

```
Kotlin declarations (via KSP) → CIR model → Interop.cs
                                           → CNameExports.kt
```

The CIR model will have:
- `CirFile` — top-level container with usings
- `CirNamespace` — contains classes and static classes
- `CirClass` — with properties, methods, constructor, IDisposable
- `CirStaticClass` — for top-level function exports
- `CirMethod` / `CirProperty` / `CirParameter` — members
- `CirDllImport` — represents the native extern declaration

A `CirRenderer` walks the tree and emits formatted C# source with proper indentation.

## Consequences

**Positive:**
- Translation logic (`KSFunctionDeclaration → CirMethod`) is unit testable without running KSP
- Renderer is independently testable with hand-constructed CIR trees
- Adding interfaces/generics means adding new CIR nodes, not new string patterns
- Matches the architecture proven by Kotlin Swift Export at scale

**Negative:**
- More code than raw string emission
- Need to maintain the model as C# language features evolve

The upfront cost is offset by reduced fragility as we add Phase 3/4/5 features.

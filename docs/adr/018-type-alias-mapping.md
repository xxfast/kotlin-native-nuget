# ADR-018: Type alias mapping — transparent expansion to underlying type

## Status
Proposed

## Context
Kotlin type aliases (`typealias StringList = List<String>`) allow developers to create named shortcuts for types. When these appear in public API (function returns, parameters, properties), the bridge generator must decide how to represent them in C#.

The current processor does not handle type aliases. When KSP resolves a type that is an alias, `type.resolve().declaration` returns a `KSTypeAlias` instead of a `KSClassDeclaration`. This causes `as? KSClassDeclaration` casts to fail, and `declaration.simpleName.asString()` returns the alias name (e.g., `"StringList"`) instead of the underlying type (`"List"`). Functions/properties using aliases are silently skipped or generate incorrect bindings.

## Alternatives Considered

### 1. Transparent expansion (chosen)
Resolve the alias to its underlying type at KSP resolution time. A `KSType.expandAliases()` helper recursively dereferences `KSTypeAlias` declarations. The generated C# API uses the expanded type — `IReadOnlyList<string>` instead of `StringList`.

**Pros:**
- Zero new C# code or bridge mechanisms
- Consistent with every other Kotlin interop target (Java, ObjC, Swift all erase aliases)
- C# consumers see familiar .NET types

**Cons:**
- The alias name is lost in the C# API (but it carries no type safety anyway)

### 2. Generate `global using` aliases
Emit `global using StringList = System.Collections.Generic.IReadOnlyList<string>;` in C#.

**Pros:**
- Preserves the alias name for discoverability

**Cons:**
- Requires C# 10+ and project-level opt-in
- IntelliSense shows the expanded type anyway
- No type safety — `StringList` and `IReadOnlyList<string>` are fully interchangeable
- No other Kotlin interop target generates aliases in the consumer language

### 3. Generate wrapper types
Create a C# `struct` or `class` wrapping the underlying type.

**Pros:**
- Type safety — `StringList` would be a distinct type

**Cons:**
- Introduces a type that doesn't exist in Kotlin
- Breaks C# expectations (a `StringList` can't be passed where `IReadOnlyList<string>` is expected without conversion)
- Overkill for a compile-time convenience feature

## Decision
Transparent expansion. Add a `KSType.expandAliases()` extension that recursively resolves `KSTypeAlias` to the underlying type. Apply it at every `type.resolve()` site in the translator and exports code.

This is the same approach taken by Java interop, ObjC Export, and Swift Export. Type aliases are a Kotlin-side convenience; they carry no semantic meaning across the language boundary.

## Consequences
- Functions, properties, and parameters using type aliases will generate correct bindings for the first time
- No CIR model changes needed
- Chained aliases (`typealias A = B`, `typealias B = List<String>`) are handled by the recursive expansion
- Type aliases for function types expand to the underlying `FunctionN` type, which then follows the existing lambda mapping (ADR-012)

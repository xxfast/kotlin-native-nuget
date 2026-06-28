# ADR-034: Secondary constructor export + exception propagation for regular classes

## Status

Proposed

## Context

[ADR-031](031-constructor-exception-propagation.md) added constructor exception
propagation but only for the **primary** constructor, and deferred secondary
constructors of regular classes:

> Secondary constructors for regular classes — none are currently exported by the
> KSP processor; not in scope.

This feature therefore has two parts:

1. **Export** secondary constructors at all — today they are unreachable from C#.
2. **Propagate** exceptions from them, reusing the ADR-031 error-out pattern.

### How the primary constructor is exported today

`ClassExports.kt` reads exactly one constructor:

```kotlin
val constructor: KSFunctionDeclaration? = cls.primaryConstructor
if (constructor != null && !isAbstract) {
  // export_${prefix}_create with errorOut + try/catch (ADR-031)
}
```

`CirClass` (`CirModel.kt`) holds a single `constructor: CirConstructor?`, and
`renderConstructor` (`CirClassRenderer.kt`) emits one `public ${className}(…)`
that calls `Native_Create(…, out IntPtr error)`. So both the CIR model and the
renderer are single-constructor by construction.

Note: `ValueClassExports.kt` **already** iterates multiple constructors and names
them `${prefix}_create` / `${prefix}_create_${index}`. That naming precedent is
the starting point for the entry-point scheme below.

### The two real design problems

C has no overloading, so multiple constructors need **distinct C entry points**,
and C# needs **distinct `[DllImport]` method names** mapping to overloaded
`public` constructors. Two wrinkles:

- **Entry-point naming** must be stable and collision-free.
- **C# overload collision**: two Kotlin constructors can erase to the *same* C#
  parameter-type list (e.g. `constructor(a: Int)` and a value-class-typed param
  that also renders to `int`). C# cannot declare two constructors with identical
  signatures.

### How other Kotlin interop targets handle multiple constructors

- **JVM**: all constructors compile to overloaded `<init>` methods; the JVM
  resolves by descriptor. Exceptions propagate identically per constructor.
- **ObjC export**: initializers are name-mangled (`initWithA:`, `initWithA:b:`),
  so each constructor gets a distinct selector — the direct analogue of distinct
  C entry points.
- **Swift export**: maps to multiple `init(...)` overloads resolved by argument
  labels/types.

All three expose every constructor and propagate throws per constructor, which is
the behavior C# developers expect from overloaded constructors.

## Alternatives Considered

### 1. Discover all non-private constructors; primary keeps `_create`, secondaries get `_create_N` (chosen)

Replace `cls.primaryConstructor` with `cls.getConstructors()`. The primary keeps
the existing `${prefix}_create` entry point (no ABI churn for existing code);
each secondary, in declaration order, gets `${prefix}_create_2`, `_create_3`, …
(1-based starting at 2 so it never collides with the primary's `_create`). Each
C# overload maps to a distinct `[DllImport]` `Native_Create_N`. Every export
carries the ADR-031 `errorOut` + try/catch + nullable return; every C# overload
runs the ADR-031 error-check-before-`_handle` body.

Pros: backward-compatible primary entry point; reuses ADR-031 verbatim per
constructor; aligns with ObjC/Swift per-constructor mapping. Cons: requires
collision detection (below).

### 2. Mangle every entry point by parameter types (`cat_create_string_int`)

Name entry points after their parameter types. Pros: self-describing, no index
drift if constructors are reordered. Cons: changes the existing primary entry
point (ABI break for all current classes); verbose; still needs collision
handling for identical C#-erased signatures. Rejected for breaking the primary.

### Collision handling (applies to the chosen option)

When two constructors render to identical C# parameter-type lists, C# cannot
overload them. Options: (a) **skip** the colliding secondary with a KSP warning;
(b) **fail** the build with a clear diagnostic. Choose **(b) fail-fast with an
explicit KSP error** naming both constructors, consistent with the project's
"fail fast" convention — silently dropping a public constructor would surprise
consumers. (Disambiguation by name is deferred; see Scope.)

## Decision

Adopt **Alternative 1**. Discover all non-private constructors via
`getConstructors()`. The primary keeps `${prefix}_create`; secondaries get
`${prefix}_create_2`, `_create_3`, … in declaration order. Each export and each
generated C# constructor overload uses the ADR-031 error-out pattern unchanged.
On a C# signature collision, emit a KSP error naming the offending constructors.

### CIR model change (`CirModel.kt`)

`CirClass.constructor: CirConstructor?` becomes
`constructors: List<CirConstructor>` (or add `secondaryConstructors`; a single
list is cleaner). `CirConstructor` already carries `hasErrorCheck` (ADR-031) and
gains a `nativeName` (so the renderer can emit `_create` vs `_create_N` and the
matching `Native_Create_N`).

### Kotlin export (`ClassExports.kt`)

Iterate `cls.getConstructors()` (filter non-public/abstract as today), assign
`nativeName` per the scheme, and emit the ADR-031 export body for each.

### C# rendering (`CirClassRenderer.kt`)

`renderClass` emits one `[DllImport] Native_Create_N` per constructor;
`renderConstructor` is called once per `CirConstructor`, emitting the ADR-031
error-check body against the matching `Native_Create_N`. The internal
`${className}(IntPtr handle)` constructor is still emitted once.

### Delegation note

A Kotlin secondary constructor that delegates to the primary via `: this(...)`
re-runs the primary `init` blocks. Because each export wraps the *whole*
construction (`Type(args)`) in `try/catch (e: Throwable)`, a throw from the
re-run primary `init` is caught by the secondary's export — no special handling.

### Consumer experience

```kotlin
// Kotlin
class Cat(val name: String, val age: Int) {
    init { require(age >= 0) { "age must be non-negative" } }
    constructor(name: String) : this(name, 0)
}
```

```csharp
var kitten = new Cat("Oreo");          // secondary → cat_create_2
try
{
    var bad = new Cat("Oreo", -1);     // primary → cat_create, throws
}
catch (KotlinArgumentException ex)
{
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);
}
```

## Consequences

- Classes with secondary constructors gain new `${prefix}_create_N` exports and
  corresponding C# overloads — additive; the primary's existing `_create` entry
  point is unchanged.
- Every constructor (primary and secondary) throws mapped `Exception` subtypes
  per ADR-029 instead of aborting.
- A C# signature collision now fails the build with a clear KSP diagnostic rather
  than producing uncompilable C#.

### Affected files

- `ClassExports.kt` — iterate `getConstructors()`; per-constructor export + naming.
- `CirModel.kt` — `CirClass.constructors: List<CirConstructor>`; `CirConstructor.nativeName`.
- `CirClassTranslator.kt` — translate all constructors; collision detection/diagnostic.
- `CirClassRenderer.kt` — one `Native_Create_N` `[DllImport]` and one
  `renderConstructor` per constructor.

### Scope

**This ADR:** export + exception propagation for non-private secondary
constructors of regular (non-abstract, non-generic) classes whose C#-erased
signatures are distinct.

**Deferred:**
- Disambiguating constructors that collide on C#-erased signatures (would need a
  naming hint or labeled overloads).
- Secondary constructors of **generic** classes (different code path, ADR-032).
- `@JvmOverloads`-style synthetic default-argument constructors — only declared
  `constructor(...)` members are exported.
- Private/internal secondary constructors.

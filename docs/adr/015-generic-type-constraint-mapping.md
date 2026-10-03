# ADR-015: Generic Type Constraint Mapping — C# `where` clauses from Kotlin upper bounds

## Status
Proposed

## Context
Kotlin generics support upper bounds: `class Container<T : Pet>`, `fun <T : Comparable<T>> sort(...)`. These are compile-time constraints on what types can be used as type arguments. The C bridge (Kotlin/Native) has no representation for these — all type parameters are erased to `void*`. However, the C# side has full generic constraints (`where T : IPet`), and omitting them makes the generated API less type-safe and less idiomatic for C# consumers.

Currently, `CirGenericClass.typeParameters` is `List<String>` — just parameter names like `["T"]`. There is no way to carry bound information through the CIR to the renderer.

### How other platforms handle this

- **Java interop**: Kotlin upper bounds become Java bounded type parameters: `<T extends Comparable<T>>`. Preserved at compile time, erased at runtime.
- **ObjC Export**: Lightweight generics with protocol conformance: `id<IPet>`. Only the first bound is used; multiple bounds are not representable.
- **Swift Export**: Maps to `where T: SomeProtocol`, but not fully supported — complex constraints are skipped.
- **JS/Wasm Export**: Constraints are entirely dropped — no type system to represent them.

No existing Kotlin interop target propagates bounds faithfully across a native (C) boundary.

## Alternatives Considered

### 1. Parallel bounds list on CirGenericClass

Add `typeParameterBounds: List<List<String>>` alongside existing `typeParameters: List<String>`.

```kotlin
data class CirGenericClass(
  val typeParameters: List<String>,            // ["T"]
  val typeParameterBounds: List<List<String>>, // [["IPet"]]
  ...
)
```

**Pros:** Minimal change — existing code using `typeParameters` by index still works.
**Cons:** Two lists must be kept in sync. Fragile and non-obvious.

### 2. Structured CirTypeParameter (chosen)

Replace `List<String>` with `List<CirTypeParameter>` where `CirTypeParameter(name, bounds)`.

```kotlin
data class CirTypeParameter(
  val name: String,
  val bounds: List<String> = emptyList(),
)
```

```kotlin
data class CirGenericClass(
  val typeParameters: List<CirTypeParameter>,
  ...
)
```

**Pros:** Self-contained — bounds travel with their parameter name. Extensible (variance can be added later). Aligns with how KSP models `KSTypeParameter`.
**Cons:** Changes the shape of `CirGenericClass` — requires updating all construction and rendering sites.

### 3. Emit no constraints (defer entirely)

Leave `CirGenericClass.typeParameters` as `List<String>`, generate unconstrained `Box<T>` for all generic classes.

**Pros:** Zero implementation effort.
**Cons:** C# consumers can instantiate `PetBox<int>` which will crash at runtime. Loses type safety that Kotlin guarantees at compile time. This is the current state.

## Decision
Use **Option 2: structured `CirTypeParameter`** for the CIR model.

The bridge layer (Kotlin CName exports) requires **no changes** — constraints are compile-time only on the C# side. `NugetMarshal` requires no changes. The C# `where T : IPet` clause is a renderer-only concern.

### Bound mapping rules

| Kotlin bound                                      | C# constraint                                   |
|---------------------------------------------------|-------------------------------------------------|
| `kotlin.Any`                                      | `class`                                         |
| Exported interface `Pet`                          | `IPet` (I-prefix per existing interface naming) |
| Exported class `Animal`                           | `Animal`                                        |
| `kotlin.Comparable<T>` and other stdlib bounds     | dropped (see 2026-10-03 amendment)              |
| Unrecognized / external                           | not shipped; see the 2026-10-03 amendment       |
| Unconstrained (no bounds or only implicit `Any?`) | no `where` clause                               |

### CIR changes
- New `CirTypeParameter(name: String, bounds: List<String>)` in `CirModel.kt`
- `CirGenericClass.typeParameters: List<CirTypeParameter>` (was `List<String>`)
- Renderer emits `where T : IBound1, IBound2` after the class declaration line

### Translator changes (KSP)
- `translateGenericClass` reads `cls.typeParameters`, maps each param's `bounds` sequence to C# constraint strings via the bound-mapping table above
- Generic function translation does the same for function type parameters via `CirMethod`

### Renderer changes
- `renderGenericClass` emits `where` clause when any type parameter has non-empty bounds
- `renderMethod` emits `where` clause on generic method signatures

### Example

Kotlin source:
```kotlin
class PetBox<T : Pet>(val value: T)
```

Generated C#:
```csharp
public class PetBox<T> : IDisposable where T : IPet
{
    internal IntPtr _handle;

    public T Value => NugetMarshal.FromHandle<T>(PetBoxNative.Get_value(_handle));

    public void Dispose() { ... }
}
```

Bridge (unchanged from unconstrained generic):
```kotlin
@CName("petbox_get_value")
fun export_petbox_get_value(handle: COpaquePointer): COpaquePointer { ... }
```

## Consequences

- `CirGenericClass` shape changes — all construction sites need updating
- `CirRenderer.renderGenericClass` emits `where` clause when type parameters have bounds
- `CirRenderer.renderMethod` emits `where` clause for generic functions with bounds
- C# consumers get compile-time enforcement of Kotlin's type constraints
- The bridge layer is unchanged — no new CName functions generated
- `NugetMarshal` unchanged — runtime dispatch logic is type-parameter-agnostic
- Extensible: variance (`in`/`out`) can be added to `CirTypeParameter` later

## Amendment (2026-10-03): stdlib bounds are dropped

The table's `Comparable<T>` to `IComparable<T>` row and its "simple name with warning" row never
shipped and are superseded. A bound declared in a Kotlin builtin package (`Comparable<T>`,
`Number`, `CharSequence`, `Enum<T>`) used to produce C# and Kotlin that did not compile: the
`where` clause named `global::...Kotlin.IComparable` or a bare `Number` (CS0234, CS0246, CS0701),
and the generic function and class routes spelled `kotlin.Comparable` with no type arguments.

Rule: a builtin bound is dropped from the C# `where` clause, keeping `notnull` when the bound is
non-null (`class Ranked<T : Comparable<T>>` renders `where T : notnull`), and each dropped bound
is reported as `INFO_DROPPED_BOUND`; the declaration still binds. Mapping to `IComparable<T>` was
rejected because no generated wrapper implements it, so every wrapper type argument would fail
with CS0311 / CS0315 (spike). One helper, `cirBoundConstraint` (`cir/CirTypeMapping.kt`), serves the
generic class and generic function spellers, and checks the builtin package before the
interface/class split so `Comparable` and `Number` take one arm. The Kotlin half spells a bound's
type arguments as `Any?` (`kotlin.Comparable<Any?>`, valid because `Comparable` is contravariant)
in `forwardKotlinBoundSpelling`. The function route's two halves read one gate,
`legacyGenericHasNonTrivialBound`, for whether primitive variants exist. The same helper writes the
interface check as `declaration != null && declaration.classKind == ClassKind.INTERFACE`, which
removes the compiler "Condition is always 'true'" warning in `cirTypeParameters` and its twin in
`CirFunctionTranslator.kt`. `legacyBoundClassCsName`'s builtin fallback served only builtin classes
and is deleted.

Consequence for callers: with the bound gone, C# can pass a type argument Kotlin would reject,
which fails at the call.

Evidence. Verified: `Tier1BuiltinGenericBoundTest` six cells red before and green after;
`:nuget-processor:test` 1568 passed, 0 failed; no "Condition is always" warning in the build log;
full `scripts/verify.sh` green (Contract 3, Integration 3010, Leak 158, MultiPackage 9,
SharedException 2, all six NativeAOT shapes); `Ranked<int>`, `Ranked<string>`, `Tally<int>`,
`Tally<double>` and `Favourite<Treat>` run. Not covered on purpose: the star-projected and nested
generic bound-argument arms of `forwardKotlinBoundSpelling`. No LeakTests row.

Known limits, verified and tracked in ROADMAP: `T : Enum<T>` has no valid Kotlin type argument, so
its generated Kotlin does not compile on both generic routes; a multi-bound parameter
(`where T : Comparable<T>, T : Pet`) breaks the Kotlin half because `forwardOwnerTypeName` spells
the owner by its first bound only; a builtin-bounded generic function exports only the object
variant, so `Treats.Weigh<int>(4)` throws `NotSupportedException` and only a generated wrapper
works as `T`.

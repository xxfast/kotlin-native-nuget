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
| `kotlin.Comparable<T>`                            | `IComparable<T>`                                |
| Unrecognized / external                           | emitted as simple name with warning             |
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

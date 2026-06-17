# ADR-016: Generic Variance Mapping — `out T` / `in T` from Kotlin to C#

## Status
Proposed

## Context

Kotlin supports declaration-site variance on classes and interfaces:
- `class Producer<out T>` — covariant (T appears only in output positions)
- `class Consumer<in T>` — contravariant (T appears only in input positions)
- `class Box<T>` — invariant (default, T can appear anywhere)

It also supports use-site variance (type projections):
- `fun copy(from: Array<out Animal>)` — covariant projection at the call site

ADR-015 introduced `CirTypeParameter(name, bounds)` and explicitly noted: "Extensible: variance (`in`/`out`) can be added to `CirTypeParameter` later."

This ADR decides how to map Kotlin variance to C#.

### How Kotlin/Native exports variance across the C boundary

Kotlin/Native's C export (`@CName`) **does not carry variance information into the C header**. For any generic class — whether `Box<T>`, `Producer<out T>`, or `Consumer<in T>` — the exported C bridge function signatures are identical: all type parameters are erased to `void*` (COpaquePointer). Variance is a compile-time Kotlin constraint that has no representation in the C ABI.

This means: **the bridge layer requires zero changes for variance**. There is nothing to do on the Kotlin/CName side.

### How other Kotlin interop targets handle variance

**Java interop (JVM)**
Kotlin's `out T` becomes Java bounded wildcard at use sites: `? extends T`. Kotlin's `in T` becomes `? super T`. Declaration-site variance is preserved as a hint to the compiler; call sites get wildcard expansion automatically. This is the closest equivalent to C# variance on interfaces.

**ObjC Export (Kotlin/Native)**
Kotlin's ObjC exporter emits `__covariant` on the Objective-C lightweight generic type parameter for `out T`. Contravariant (`in T`) also receives an annotation, but contravariance on Obj-C is unusual and rarely used. This is purely a compile-time annotation in the Objective-C header — the runtime is fully erased (same pointer for all types).

**Swift Export**
Swift has no variance support on its own generics. Kotlin's covariant generics lose their variance annotation when crossing into Swift. The [kotlin-swift-interopedia](https://github.com/kotlin-hands-on/kotlin-swift-interopedia/blob/main/docs/generics/Generic%20classes.md#generic-classes) documents this explicitly: covariance is dropped, and consumers must use explicit type casts.

**JS/Wasm Export**
Variance annotations are dropped entirely. JavaScript and WebAssembly have no generic type systems; all generics are erased at the boundary.

### C# variance rules

C# supports declaration-site variance **on interfaces and delegates only**:
- `interface IProducer<out T>` — covariant: `T` may only appear in output (return) positions
- `interface IConsumer<in T>` — contravariant: `T` may only appear in input (parameter) positions
- `class Box<T>` — always invariant; `class Box<out T>` is a **compiler error**

This is a hard language rule. Classes in C# cannot be covariant or contravariant regardless of how the type parameter is used. This constraint exists because mutable generic classes with variance would break type safety.

Real C# examples from the .NET framework:
- `IEnumerable<out T>` — covariant because items are read-only
- `IReadOnlyList<out T>` — covariant (read-only list)
- `IReadOnlyCollection<out T>` — covariant
- `Action<in T>` — contravariant (consumer)
- `Func<in T, out TResult>` — contravariant input, covariant output

### The mismatch

The project generates:
- Kotlin **interfaces** → C# `interface I{Name}` — variance CAN be applied in C#
- Kotlin **classes** → C# `class {Name}` — variance CANNOT be applied in C#
- Kotlin **generic classes** (`CirGenericClass`) → C# `class {Name}<T>` — variance CANNOT be applied

This creates a split: variance is meaningful and emittable only for generated C# interfaces.

### Use-site variance (projections)

Kotlin use-site variance (`fun copy(from: Array<out T>)`) has no C# equivalent. C# has no wildcard types. Other Kotlin interop targets (Java excepted) universally drop use-site variance. This is deferred entirely.

## Alternatives Considered

### 1. Emit variance on C# interfaces only (chosen)

For Kotlin interfaces with `out T` or `in T`, emit the matching C# variance modifier on the generated `interface I{Name}<out T>` or `interface I{Name}<in T>`.

For Kotlin classes (including generic classes), drop variance — emit `class {Name}<T>` regardless of Kotlin's `out`/`in`. This is correct C# and matches what Swift Export and ObjC Export do (both lose variance on the class side).

```csharp
// Kotlin: interface Readable<out T>
public interface IReadable<out T> : IDisposable
{
    T Read();
}

// Kotlin: interface Writable<in T>
public interface IWritable<in T> : IDisposable
{
    void Write(T value);
}

// Kotlin: class Box<out T>(val value: T)
// C#: variance dropped — class cannot be covariant
public class Box<T> : IDisposable
{
    public T Value => NugetMarshal.FromHandle<T>(BoxNative.Get_value(_handle));
    ...
}
```

**Pros:**
- Correct and idiomatic C#. C# developers expect interfaces to carry variance.
- Emitting `out`/`in` on interfaces enables safe upcasting: `IReadable<Cat>` can be used where `IReadable<Animal>` is expected.
- Aligns with .NET's own pattern (`IEnumerable<out T>`, `IReadOnlyList<out T>`).
- Zero bridge changes — the C ABI is unchanged.
- Consistent with how every other Kotlin interop target handles the class side (variance dropped on classes).

**Cons:**
- Variance is lost on generic classes. A `Box<out T>` in Kotlin cannot be assigned to `Box<Animal>` in C# even though it would be safe.
- C# consumers of Kotlin covariant classes will not get the subtyping benefit on the class type itself (only on the interface type if the class implements one).

### 2. Emit variance on both interfaces and classes

Attempt to emit `out`/`in` on generated C# classes as well.

**Cons:** **Not valid C#.** The C# compiler rejects `class Box<out T>`. This option is a non-starter.

### 3. Monomorphize covariant classes into interfaces

For `class Box<out T>`, generate a covariant C# interface `IBox<out T>` plus a concrete invariant implementing class `BoxImpl<T> : IBox<T>`.

**Pros:** Consumers get a covariant interface.
**Cons:**
- Doubles the generated surface area for every covariant class.
- Consumers must work with the interface type, not the class.
- Complicates the CIR model significantly.
- No precedent in any other Kotlin interop target.

### 4. Drop variance entirely (defer)

Treat all type parameters as invariant, ignoring `out`/`in` even on interfaces.

**Cons:** Loses type safety on the C# side for interfaces. `IReadable<Cat>` would not be assignable to `IReadable<Animal>`, breaking expected subtyping that C# developers rely on for read-only interfaces.

## Decision

Use **Option 1: emit variance on C# interfaces only; drop variance on classes**.

### CIR changes

Add `variance` field to `CirTypeParameter`:

```kotlin
enum class CirVariance { INVARIANT, COVARIANT, CONTRAVARIANT }

data class CirTypeParameter(
  val name: String,
  val bounds: List<String> = emptyList(),
  val variance: CirVariance = CirVariance.INVARIANT,
)
```

### Translator changes (KSP)

In `translateInterface` (in `CirClassTranslator.kt`), read `param.variance` from `KSTypeParameter` and map:

| KSP `Variance`      | `CirVariance`   | C# emission |
|---------------------|-----------------|-------------|
| `INVARIANT`         | `INVARIANT`     | `T`         |
| `COVARIANT`         | `COVARIANT`     | `out T`     |
| `CONTRAVARIANT`     | `CONTRAVARIANT` | `in T`      |

In `translateGenericClass`, continue to use `CirVariance.INVARIANT` always — classes cannot be variant in C#.

### Renderer changes

In `renderInterface`, emit variance prefix when rendering type parameters:

```csharp
// Kotlin: interface Readable<out T>
public interface IReadable<out T> : IDisposable { ... }

// Kotlin: interface Writable<in T>
public interface IWritable<in T> : IDisposable { ... }
```

The renderer maps `CirVariance.COVARIANT` → `"out "`, `CirVariance.CONTRAVARIANT` → `"in "`, `CirVariance.INVARIANT` → `""`.

### Bridge mechanism

**No changes.** Variance is a compile-time C# modifier with no runtime representation. The C bridge (`@CName` exports) continues to use `COpaquePointer` / `void*` for all generic type parameters regardless of variance.

### Use-site variance

Deferred. Kotlin use-site variance (`fun foo(x: Array<out T>)`) has no C# equivalent. When encountered in KSP, it is silently dropped (use-site projections become plain type parameters at the C boundary anyway, since the bridge is fully erased).

### Example

Kotlin source:
```kotlin
interface Readable<out T> {
    fun read(): T
}

interface Writable<in T> {
    fun write(value: T)
}

class Box<out T>(val value: T)
```

Generated C#:
```csharp
public interface IReadable<out T> : IDisposable
{
    T Read();
}

public interface IWritable<in T> : IDisposable
{
    void Write(T value);
}

// Variance dropped for class — invariant is the only valid C# option
public class Box<T> : IDisposable
{
    internal IntPtr _handle;

    public Box(T value)
    {
        _handle = NugetMarshal.CreateBox<T>(value);
    }

    internal Box(IntPtr handle)
    {
        _handle = handle;
    }

    public T Value => NugetMarshal.FromHandle<T>(BoxNative.Get_value(_handle));

    public void Dispose() { ... }
}
```

This enables:
```csharp
IReadable<Cat> catReader = ...;
IReadable<Animal> animalReader = catReader;  // valid: IReadable<out T> is covariant
```

But not:
```csharp
Box<Cat> catBox = ...;
Box<Animal> animalBox = catBox;  // compile error: Box<T> is invariant (same as any C# class)
```

## Consequences

- `CirTypeParameter` gains a `variance: CirVariance` field (default `INVARIANT`, backward-compatible)
- `translateInterface` reads variance from KSP and populates `CirTypeParameter.variance`
- `translateGenericClass` continues to emit `INVARIANT` always (no change in behavior)
- `renderInterface` emits `out`/`in` prefix on type parameters when variance is non-invariant
- `renderGenericClass` is unchanged
- C# consumers of Kotlin covariant interfaces get correct subtyping (`IReadable<Cat>` assignable to `IReadable<Animal>`)
- C# consumers of Kotlin covariant classes do not get subtyping on the class itself — consistent with what Java, ObjC, and Swift interop targets produce
- Use-site variance (projections in function parameters) is deferred
- No KSP export changes, no C bridge changes, no `NugetMarshal` changes

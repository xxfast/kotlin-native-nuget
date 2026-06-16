# ADR-014: Value Class Mapping — readonly record struct with unwrapped bridge

## Status
Proposed

## Context
Kotlin value classes (`@JvmInline value class`) wrap exactly one property with zero runtime overhead. They are always final, cannot participate in class hierarchies (except implementing interfaces), and the compiler inlines them wherever possible. We need a C# representation that preserves type safety while respecting the zero-cost intent.

Other Kotlin interop targets handle this poorly: ObjC export loses type safety entirely (primitives become bare C types, reference-wrapping becomes `id`), and Swift Export doesn't support them at all. We can do better.

## Alternatives Considered

### 1. Unwrap + `readonly record struct` (chosen)
Pass the underlying value directly through the C bridge (no StableRef, no handle). On the C# side, wrap it in a `readonly record struct`.

```kotlin
@JvmInline
value class CatId(val id: String)
```
```csharp
public readonly record struct CatId(string Id);
```

Bridge functions take/return the underlying type directly:
```kotlin
@CName("catid_get_length")
fun export_catid_get_length(value: String): Int = CatId(value).length
```
```csharp
public readonly record struct CatId(string Id)
{
    public int Length => Native_GetLength(Id);
}
```

**Pros:**
- True value semantics — stack-allocated, no GC pressure, no `IDisposable`
- Auto-generated `Equals`, `GetHashCode`, `ToString` from `record`
- `with` expression is safe (no opaque handles to alias)
- Matches Kotlin's zero-cost intent — no boxing on either side of the bridge
- Most idiomatic C# for a type-safe wrapper (same pattern as `System.Index`, `System.Range`)

**Cons:**
- Computed properties and methods require bridge calls that reconstruct the value class on each invocation (marginal cost since value classes are trivially constructed)
- Object-wrapping value classes are more complex (deferred)

### 2. StableRef + regular class with `IDisposable`
Treat the value class like any other Kotlin class — box it, pin with StableRef, wrap in a disposable C# class.

```csharp
public class CatId : IDisposable
{
    internal IntPtr _handle;
    public string Id => Marshal.PtrToStringUTF8(Native_GetId(_handle));
    public void Dispose() => Native_Dispose(_handle);
}
```

**Pros:**
- Reuses existing class infrastructure entirely
- No new CIR node type needed

**Cons:**
- Defeats the zero-cost purpose of value classes — heap allocation + GC on both sides
- Forces `IDisposable` on a logically value-typed concept — `using var id = new CatId("x")` is unnatural
- No value equality without custom implementation
- Reference semantics where value semantics are expected

### 3. Global `using` type alias
```csharp
global using CatId = string;
```

**Pros:**
- Zero overhead, zero code

**Cons:**
- No type safety — `CatId` and `string` are fully interchangeable
- Cannot attach computed properties or methods
- Not a real type — doesn't appear in IntelliSense as distinct

## Decision
Use unwrapped bridge + `readonly record struct` (alternative 1). Value classes are detected via `Modifier.VALUE` in the KSP processor. The underlying property is always the single primary constructor parameter.

### Bridge mechanism
- **No StableRef, no handle** — the underlying value crosses the C boundary directly
- CName exports for computed properties and methods take the underlying value as first parameter and reconstruct the value class:
  ```kotlin
  @CName("catid_get_length")
  fun export_catid_get_length(value: String): Int = CatId(value).length
  ```
- When a value class appears as a parameter/return/property type on another declaration, the bridge uses the underlying type and wraps/unwraps at the boundary:
  ```kotlin
  // fun findCatById(id: CatId): Cat
  @CName("findcatbyid")
  fun export_findcatbyid(id: String): COpaquePointer =
      StableRef.create(findCatById(CatId(id))).asCPointer()
  ```
  ```csharp
  public static Cat FindCatById(CatId id)
      => new Cat(Native_FindCatById(id.Id));
  ```

### CIR model
New `CirValueClass` node type — distinct from `CirClass` because it renders as a struct, has no handle, and has no `IDisposable`.

### Scope (v1)
- Value classes wrapping primitive types and `String`
- Computed properties and methods
- Value class as parameter, return, or property type

### Deferred
- Value classes wrapping reference types
- Nested value classes (value class wrapping another value class)
- Value classes as generic type arguments
- Value classes in collections
- Value classes implementing interfaces (interface dispatch requires boxing)

## Consequences
- New `CirValueClass` CIR node type and corresponding renderer
- Value class type lookup needed during translation of other declarations (to detect when a parameter/return/property type is a value class and use the underlying type on the bridge)
- `readonly record struct` requires C# 10+ (already met — project targets .NET 10.0)

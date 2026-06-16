# ADR-013: Extension Property Mapping — GetXxx/SetXxx extension methods

## Status
Proposed

## Context
Kotlin extension properties (`val Cat.isKitten: Boolean`) need a C# representation. Unlike Swift, C# (up to C# 13) supports extension methods but not extension properties. We need a convention that feels natural to C# developers and integrates with the existing extension function bridge (`{ReceiverName}Extensions` static class).

## Alternatives Considered

### 1. `GetXxx()`/`SetXxx()` extension methods (chosen)
Extension properties become `GetIsKitten()` / `SetLabel()` extension methods in the same `CatExtensions` class used for extension functions.

**Pros:**
- Familiar C# convention — `IDataRecord.GetString()`, `XmlReader.GetAttribute()`, `Marshal.GetLastWin32Error()`
- Merges into existing `{ReceiverName}Extensions` class
- Clear distinction between property access (Get/Set) and method calls

**Cons:**
- More verbose than a native property (`cat.GetIsKitten()` vs `cat.IsKitten`)
- Will need migration path if C# 14 extension properties ship

### 2. No-prefix extension methods (`cat.IsKitten()`)
Drop the `Get` prefix — reads closer to a property but uses method call syntax.

**Pros:**
- Shorter call sites
- Reads like a property

**Cons:**
- Ambiguous: consumers can't tell if `IsKitten()` is a computed property or a method doing work
- Inconsistent with .NET conventions for property-like accessors

### 3. Wrapper class holding the receiver
```csharp
new CatView(cat).IsKitten  // true C# property
```

**Pros:**
- True property syntax

**Cons:**
- Adds allocation per access
- Unfamiliar pattern for .NET developers
- Doesn't compose with extension functions

### 4. C# 14 `extension` blocks (future)
```csharp
extension(Cat cat) { public bool IsKitten => ...; }
```

**Pros:**
- True `cat.IsKitten` property syntax

**Cons:**
- Not stable yet (preview in .NET 10)
- Track as a future migration path

## Decision
Use `GetXxx()`/`SetXxx()` extension methods. Extension properties are translated into the same `{ReceiverName}Extensions` static class used for extension functions. Getter-only properties produce a single `GetXxx()` method; mutable properties also produce a `SetXxx(value)` method.

### Bridge mechanism
- CName: `{receiverPrefix}_get_{propName}` / `{receiverPrefix}_set_{propName}`
- Class receivers: `handle: COpaquePointer` → `handle.asStableRef<T>().get().propName`
- Primitive receivers: direct parameter → `receiver.propName`

### Example
```kotlin
val Cat.isKitten: Boolean get() = lives > 7
```
Generates:
```csharp
public static bool GetIsKitten(this Cat cat)
    => Native_GetIsKitten(cat._handle);
```

## Consequences
- Extension properties and extension functions share the same `{ReceiverName}Extensions` class
- The `Get`/`Set` prefix convention should be documented for consumers
- When C# 14 extension properties stabilize, this can be migrated with a major version bump

# ADR-040: Interface return type mapping — concrete handle-backed C# class per Kotlin interface

## Status

Proposed

## Context

ADR-039 resolved C# → Kotlin interface bridging: a C# class implementing a Kotlin-projected interface
(`ICatEventListener`) can be passed to a Kotlin function via N flat function pointers, following the
`add*/remove*` subscription pair detection pattern.

ROADMAP line 106 ("Support implementing C# interfaces in Kotlin and passing them back to C# consumers")
covers the orthogonal Kotlin → C# direction: a Kotlin function or property whose **declared return
type is a Kotlin interface** (e.g., `fun createPet(): Pet`, `var friend: Pet?`) must produce an
`IPet`-valued result in C#. The Kotlin implementation behind the interface could be any concrete
class, an anonymous `object`, or a lambda via SAM conversion.

### The concrete gap

Currently, for each Kotlin interface `Foo`, the generator produces exactly one C# declaration:
`public interface IFoo : IDisposable`. No concrete C# class wraps an opaque handle and implements
`IFoo`. Because of this:

1. A Kotlin function returning `Foo` triggers the reference-type branch in `CirClassTranslator` and
   emits `new Foo(nativeResult)`. But `Foo` is an interface — `new Foo(...)` does not compile.
2. The C# property/method type would be rendered as `Foo` (the Kotlin simple name), not `IFoo`
   (the projected C# name), causing a second compile error.

The `exportedTypes` guard does NOT skip interface-typed properties/methods — interfaces ARE in
`exportedTypes` — so the broken code is emitted and the consumer build fails.

### How Kotlin/Native represents an interface-typed return at the C boundary

Kotlin/Native's C export mechanism is type-erased: every object (concrete class, anonymous object,
lambda-via-SAM) is returned as `COpaquePointer`. There is no discriminator telling C# which
concrete Kotlin class is behind the pointer.

A generated Kotlin export that stores its return value as `StableRef<Pet>` works regardless of
the underlying concrete type:

```kotlin
@CName("createpet")
fun export_createpet(errorOut: COpaquePointer?): COpaquePointer? = try {
    StableRef.create(createPet()).asCPointer()   // createPet() returns any Pet impl
} catch (e: Throwable) {
    if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
        StableRef.create(buildError(e)).asCPointer()
    null
}
```

Interface-dispatch exports (like `pet_speak`) use `asStableRef<Pet>().get()`. Even if the
underlying object was a `Cat` (whose `StableRef` was also created under the `Cat` type), Kotlin's
runtime upcasts correctly: `handle.asStableRef<Pet>().get()` returns the object as `Pet` and
dispatches polymorphically to `Cat.speak()`.

Source: [Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html)

### How other Kotlin interop targets handle interface-typed returns

#### ObjC/Swift Export (built-in)

Kotlin interfaces are exported as Objective-C `@protocol` declarations. Any Kotlin object
implementing the protocol is returned as an ObjC object conforming to the protocol. The ObjC
runtime dispatches method calls via `objc_msgSend` — the dispatcher IS the vtable, and the caller
never needs to know the concrete class. ARC handles lifetime. No explicit handle-wrapper class is
needed on the Swift/ObjC side.

Source: [Kotlin/Native ObjC interop](https://kotlinlang.org/docs/native-objc-interop.html)

This is the closest analogue: C# needs to do explicitly what `objc_msgSend` does implicitly —
look up the dispatch table for the object and call the right implementation.

#### JVM / Java

Kotlin interfaces compile to JVM interfaces. Any Kotlin object returned as an interface type is
just a Java object whose runtime class implements the interface. No wrapper class needed; method
dispatch is handled by the JVM.

#### Kotlin/JS

Both sides share the JS GC. Kotlin objects are JS objects; interface method dispatch is just
property access on the JS object.

#### SKIE (Touchlab)

SKIE builds on ObjC export. Protocol-typed returns work the same way as plain ObjC export.

#### Summary for C boundary

Every other target uses its runtime's native polymorphic dispatch. At the C boundary, the
generated C# code must implement the dispatch explicitly by generating interface-dispatch exports
on the Kotlin side and a concrete handle-backed wrapper class on the C# side.

### What's idiomatic in C#

When a Kotlin function declares `fun createPet(): Pet`, C# developers expect `IPet CreatePet()`.
The concrete object behind the `IPet` reference should be hidden — callers use the interface, not
the implementation type. The wrapper class is an implementation detail.

This mirrors how .NET's `IStream`, `IEnumerable<T>`, etc. work: a concrete backing class
(`MemoryStream`, `List<T>`) implements the interface, but callers program to `IStream` /
`IEnumerable<T>`. The generated bridge's backing class (named `Pet` after the Kotlin interface)
plays the role of `MemoryStream` — it is an opaque, unsealed implementation detail that the
consumer is unlikely to subclass.

Source: [C# interfaces (Microsoft Docs)](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/interface)

### Relationship to ADR-039

Both ADRs handle Kotlin interfaces, but in opposite directions:

| ADR | Direction | Pattern detected | C# API |
|-----|-----------|-----------------|--------|
| 039 | C# → Kotlin | `add{X}` / `remove{X}` pair with interface param | `IDisposable Add{X}(IFoo listener)` |
| 040 | Kotlin → C# | function/property declared to return an interface type | `IFoo SomeName { get; }` / `IFoo Method()` |

ADR-039 is about C# providing implementations to Kotlin. ADR-040 is about Kotlin providing
implementations that C# receives and uses.

The generated `ICatEventListener` (from Kotlin → C# direction in Phase 3) already serves as the
interface that both directions use. ADR-040 adds the concrete `CatEventListener` backing class
so that a Kotlin function returning `CatEventListener` can be projected to C# as a method
returning `ICatEventListener`.

### Disambiguation of ROADMAP lines 105–107

- **Line 105 (done — ADR-039)**: C# implements a Kotlin-projected interface and passes it TO a Kotlin
  function via the `add*/remove*` subscription pair (N flat function pointers, stored callback
  lifetime). Direction: C# → Kotlin.
- **Line 106 (this ADR)**: Kotlin returns an interface-typed value to C#. C# needs a concrete
  handle-backed wrapper class per Kotlin interface so that functions and properties with interface
  return types can be projected. Direction: Kotlin → C#.
- **Line 107 (deferred, not this ADR)**: C# passes a C#-implemented interface object to a Kotlin
  function that takes an interface parameter WITHOUT the `add*/remove*` naming convention (i.e., the
  general case of passing C# implementations to arbitrary Kotlin interface-typed parameters). This
  requires a mechanism beyond the `add*/remove*` detection in ADR-039.

## Alternatives Considered

### 1. Concrete handle-backed class per interface (chosen)

For each Kotlin interface `Foo`, in addition to the existing `public interface IFoo : IDisposable`,
generate a new `public sealed class Foo : IFoo, IDisposable` that:

- Holds `internal IntPtr _handle`.
- Has an `internal Foo(IntPtr handle)` constructor.
- Implements each interface method/property by calling a generated Kotlin interface-dispatch export
  (`foo_method_name`, `foo_get_property_name`).
- Implements `Dispose()` via a generated `foo_dispose` export.

The Kotlin interface-dispatch exports are `@CName` functions that call `asStableRef<Foo>().get()`
and dispatch through the interface:

```kotlin
@CName("pet_speak")
fun export_pet_speak(handle: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
    val obj = handle.asStableRef<Pet>().get()
    StableRef.create(obj.speak()).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
        StableRef.create(buildError(e)).asCPointer()
    null
}

@CName("pet_dispose")
fun export_pet_dispose(handle: COpaquePointer) {
    handle.asStableRef<Pet>().dispose()
}
```

**C# generated class (for `interface Pet { val name: String; fun speak(): String; fun greet(): String }`):**

```csharp
public sealed class Pet : IPet
{
    internal IntPtr _handle;

    internal Pet(IntPtr handle) { _handle = handle; }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_get_name")]
    private static extern IntPtr Native_GetName(IntPtr handle, out IntPtr error);

    public string Name
    {
        get
        {
            IntPtr nativeResult = Native_GetName(_handle, out IntPtr error);
            if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
            return Marshal.PtrToStringUTF8(nativeResult)!;
        }
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_speak")]
    private static extern IntPtr Native_Speak(IntPtr handle, out IntPtr error);

    public string Speak()
    {
        IntPtr nativeResult = Native_Speak(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_greet")]
    private static extern IntPtr Native_Greet(IntPtr handle, out IntPtr error);

    public string Greet()
    {
        IntPtr nativeResult = Native_Greet(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_dispose")]
    private static extern void Native_Dispose(IntPtr handle);

    public void Dispose()
    {
        IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);
        if (handle == IntPtr.Zero) return;
        Native_Dispose(handle);
    }
}
```

**Kotlin property/method returning interface — example (`var friend: Pet?` on `Cat`):**

```csharp
// C# getter (generated on Cat class)
[DllImport("sample", EntryPoint = "cat_get_friend")]
private static extern IntPtr Native_GetFriend(IntPtr handle, out IntPtr error);

public IPet? Friend
{
    get
    {
        IntPtr nativeResult = Native_GetFriend(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return nativeResult == IntPtr.Zero ? null : new Pet(nativeResult);
    }
}
```

**Consumer API:**

```csharp
using var oreo = new Cat("Oreo", 9);
using var kitten = new Cat("Kitten", 1);

oreo.Befriend(kitten);                  // Cat.befriend(pet: Pet) — accepts any IPet (Cat IS an IPet)

using IPet? friend = oreo.Friend;       // Pet? in Kotlin → IPet? in C#
Assert.NotNull(friend);
Assert.Equal("Kitten", friend!.Name);   // dispatches through pet_get_name → Cat.name
Assert.Equal("Meow! My name is Kitten", friend.Speak());
```

**Pros:**
- Direct extension of the existing concrete-class pattern. Every Kotlin class generates a concrete
  C# class; now every Kotlin interface generates one too.
- Consumer programs to the `IFoo` interface, never the backing class — the `sealed` modifier
  prevents misuse.
- The Kotlin interface-dispatch path (`asStableRef<Pet>().get()`) works for any Kotlin
  implementation behind the pointer. Anonymous objects, SAM conversions, and concrete classes all
  dispatch correctly through Kotlin's polymorphism.
- No new mechanism needed: the disposal and DllImport patterns are identical to how concrete Kotlin
  classes are already projected (ADR-003).
- Works correctly when the return type is nullable (`Pet?` → `IPet?`): return `null` when the
  pointer is zero, wrap in `new Pet(handle)` otherwise.
- The method-level type resolution needed to use `new Pet(handle)` (rather than the broken
  `new IntPtr(...)`) is a straightforward check: "is the return type a Kotlin interface?" → use the
  concrete wrapper class name.

**Cons:**
- Two generated types per Kotlin interface (`IFoo` + `Foo`). C# developers may initially be confused
  about which to use (answer: always `IFoo`; `Foo` is the bridge backing class).
- When a function declares return type `Pet` and actually returns a `Cat`, calling
  `friend.Speak()` dispatches through `pet_speak` (interface path) rather than `cat_speak`
  (direct path). The result is identical but the dispatch is one level of indirection higher.
  This is the expected cost of interface polymorphism and is not unique to this bridge.
- The concrete wrapper for a Kotlin interface (`Pet`) and the abstract class wrapper (e.g.,
  `Animal`, which already implements `IPet`) both exist in the same namespace. They are distinct
  types and both implement `IPet`. This is correct but means a `Cat` object accessed as `Animal`
  and the same `Cat` object accessed as `Pet` are two separate C# wrapper instances (same handle,
  different C# types). Object identity is not preserved — this is documented in ADR-005 and is
  consistent with the existing pattern.

### 2. Skip interface-typed return positions (status quo)

Leave interface-typed return values unsupported. Properties and methods whose declared Kotlin
return type is an interface are skipped with a `logger.warn(...)` entry. The existing `exportedTypes`
guard would need to be extended to exclude interfaces from the valid-reference-type set, or the
broken `new Pet(handle)` code is emitted and the consumer build fails.

**Pros:**
- No new generated types; no new Kotlin exports.
- Smaller scope per release.

**Cons:**
- A common, idiomatic Kotlin API pattern (interface-typed return) is entirely unmapped. Any Kotlin
  library that uses interfaces as return types (e.g., factory methods, repository patterns,
  provider patterns) produces broken or absent C# bindings.
- The current behaviour is not "skip and warn" but "emit broken code" (see gap description above).
  This is already a regression; doing nothing perpetuates it.

### 3. Abstract class instead of sealed class for the concrete wrapper

Generate `public abstract class Foo : IFoo, IDisposable` as the backing type instead of
`public sealed class Foo`. This would allow concrete Kotlin subclasses (like `Cat`) to extend `Foo`
in C#.

**Pros:**
- Preserves a degree of the Kotlin type hierarchy in C# (if `Cat : Pet` in Kotlin, `Cat` could
  extend `Pet` in C#).

**Cons:**
- `Cat` already extends `Animal` in Kotlin, and `Animal` is projected as an abstract class.
  C# does not support multiple inheritance of classes. `Cat` cannot extend both `Animal` and `Pet`.
  The hierarchy would have to be linearized arbitrarily.
- If `Cat` does not extend the abstract `Pet` class, calling `CreatePet()` and getting back an
  instance of `abstract class Pet` is still not possible — the return has to be a concrete
  instantiation.
- The `abstract class` designation suggests subclassing is intended. No consumer should subclass
  `Pet` — they should implement `IPet` instead (or use the C#→Kotlin bridging path from ADR-039).
  `sealed` communicates this correctly.

## Decision

Use **Alternative 1: concrete handle-backed sealed class per Kotlin interface**.

For each Kotlin interface `Foo`, the generator produces two C# declarations:
- **Existing**: `public interface IFoo : IDisposable` (the projected interface — unchanged).
- **New**: `public sealed class Foo : IFoo` (the opaque handle wrapper — new).

The `sealed class Foo` dispatches each interface method and property through newly generated
Kotlin interface-dispatch exports (`@CName` functions that call `asStableRef<Foo>().get()`).

### Kotlin export pattern (generated for `interface Pet`)

```kotlin
// Generated in InterfaceExports.kt (new file, parallel to ClassExports.kt)
@CName("pet_get_name")
fun export_pet_get_name(handle: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
    val obj = handle.asStableRef<Pet>().get()
    StableRef.create(obj.name).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
        StableRef.create(buildError(e)).asCPointer()
    null
}

@CName("pet_speak")
fun export_pet_speak(handle: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
    val obj = handle.asStableRef<Pet>().get()
    StableRef.create(obj.speak()).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
        StableRef.create(buildError(e)).asCPointer()
    null
}

@CName("pet_greet")
fun export_pet_greet(handle: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
    val obj = handle.asStableRef<Pet>().get()
    StableRef.create(obj.greet()).asCPointer()
} catch (e: Throwable) {
    if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
        StableRef.create(buildError(e)).asCPointer()
    null
}

@CName("pet_dispose")
fun export_pet_dispose(handle: COpaquePointer) {
    handle.asStableRef<Pet>().dispose()
}
```

### Handling interface-typed properties/methods on classes (example: `var friend: Pet?` on `Cat`)

The `befriend` Kotlin method (`fun befriend(pet: Pet)`) takes a `Pet` parameter. On the C# side, since
`Cat` already implements `IPet` (via the `Animal` hierarchy) and any C# class implementing `IPet`
can use the `_handle`-extraction path, the parameter type for `Befriend` is `IPet` and the bridge
extracts `_handle` via the existing reflection path (ADR-015/010):

```csharp
// Generated on Cat
[DllImport("sample", EntryPoint = "cat_befriend")]
private static extern void Native_Befriend(IntPtr handle, IntPtr pet, out IntPtr error);

public void Befriend(IPet pet)
{
    var field = typeof(... /* IPet concrete type */).GetField("_handle", ...);
    IntPtr petHandle = (IntPtr)field!.GetValue(pet)!;
    Native_Befriend(_handle, petHandle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
}
```

Note: `befriend(pet: Pet)` is NOT an `add*/remove*` pair — it stores the `Pet` but has no
`removeFriend`. This makes it a regular method whose parameter type is a Kotlin interface. The
bridge passes the `_handle` extracted from the C# `IPet`-implementing object. This is the
existing handle-extraction path and works for Kotlin-backed objects (like `Cat` which has `_handle`).
Passing a C#-implemented `IPet` (like `Dog` from `BidirectionalTests.cs`) here is LINE 107
(deferred) — it requires a different mechanism (N function pointers for a non-subscription parameter).

For the RETURN side (`var friend: Pet?`), the C# property getter:

```csharp
[DllImport("sample", EntryPoint = "cat_get_friend")]
private static extern IntPtr Native_GetFriend(IntPtr handle, out IntPtr error);

public IPet? Friend
{
    get
    {
        IntPtr nativeResult = Native_GetFriend(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return nativeResult == IntPtr.Zero ? null : new Pet(nativeResult);
    }
}
```

The return type is `IPet?` (the interface, not the backing class). The backing class `Pet` is used
only in the construction (`new Pet(nativeResult)`). The public API surface is `IPet?`.

### Type resolution rule (changes to `CirClassTranslator` / `CirFunctionTranslator`)

When a property or method return type resolves to a Kotlin interface (i.e., the KSP declaration's
`classKind == ClassKind.INTERFACE`):

| Position | Old behaviour | New behaviour |
|----------|--------------|---------------|
| C# type | `propType` (= Kotlin simple name, e.g. `Pet`) — incorrect | `I$propType` (= projected interface name, e.g. `IPet`) |
| Getter / return expression | `new $propType(handle)` — compile error | `new $propType(handle)` — now correct, `Pet` is a concrete class |
| Nullable getter | `new $propType(handle)` fallback — compile error | `nativeResult == IntPtr.Zero ? null : new $propType(handle)` |

The `propType` variable remains the Kotlin simple name (`Pet`). The C# property TYPE is `I$propType`
(for the interface), while the construction expression is `new $propType(handle)` (for the concrete
class). This is consistent with the existing concrete-class pattern.

### Sample Kotlin API (to add to `sample-library`)

New methods on `Cat.kt`:

```kotlin
// In Cat.kt
var friend: Pet? = null

fun befriend(pet: Pet) { friend = pet }
```

This exercises:
- `befriend(pet: Pet)` — method with interface-typed parameter (pass-through via `_handle` extraction)
- `val friend: Pet?` — nullable property with interface return type (new feature)

### Expected C# consumer test (failing until implemented)

```csharp
[Fact]
public void Cat_Befriend_StoresPetAndFriendReturnedAsIPet()
{
    using var oreo = new Cat("Oreo", 9);
    using var kitten = new Cat("Kitten", 1);

    oreo.Befriend(kitten);

    using IPet? friend = oreo.Friend;
    Assert.NotNull(friend);
    Assert.Equal("Kitten", friend!.Name);
    Assert.Equal("Meow! My name is Kitten", friend.Speak());
    Assert.Equal("Hi, I'm Kitten", friend.Greet());
}

[Fact]
public void Cat_Friend_NullWhenNotSet()
{
    using var cat = new Cat("Oreo", 9);
    Assert.Null(cat.Friend);
}
```

## Consequences

### New CIR nodes needed

- `CirInterfaceClass` — a new `CirDeclaration` (parallel to `CirClass`) representing the concrete
  sealed class generated for each Kotlin interface. Fields: `name` (e.g., `Pet`), `interfaceName`
  (e.g., `IPet`), `libraryName`, `nativePrefix` (e.g., `pet`), `properties: List<CirProperty>`,
  `methods: List<CirMethod>`. The renderer emits a `sealed class` with `_handle`, `internal`
  constructor, DllImports, and `Dispose()`.

### New Kotlin export pattern

`InterfaceExports.kt` (new file, parallel to `ClassExports.kt`):
- For each public Kotlin interface, generate `@CName` exports for each property getter and each
  method, dispatching through `asStableRef<Interface>().get()`.
- Generate `{prefix}_dispose` export (same disposal pattern as class exports).
- Interfaces are already excluded from `generateCNameWrappers` in `NugetProcessor`; this adds them.

### Changes to type resolution (`CirClassTranslator`, `CirFunctionTranslator`)

- When a property or method return type is a Kotlin interface:
  - C# type: `I$simpleName` (for the public-facing interface type)
  - Construction expression: `new $simpleName(handle)` (for the concrete backing class)
  - Nullable: `handle == IntPtr.Zero ? null : new $simpleName(handle)`
- Detection: `(returnDecl as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE`

### Changes to `CirRenderer` / `CirClassRenderer`

- Add `renderInterfaceClass(cls: CirInterfaceClass)` — similar to `renderClass` but emits
  `public sealed class $name : I$name` with dispatch through interface-level exports.

### New C# patterns

- `public sealed class Pet : IPet` — generated per Kotlin interface (new).
- Interface-typed properties use `I$name?` / `I$name` as the public C# type.
- Construction in getters/return values: `new $name(handle)` (backing class).

### Interaction with ADR-039 (C# → Kotlin interface bridging)

The `ICatEventListener` interface is used in two directions. With ADR-040, a concrete
`CatEventListener` class is also generated. If Kotlin has a function `fun getCurrentListener():
CatEventListener?`, C# gets `ICatEventListener?` backed by a `CatEventListener` handle wrapper.
If that underlying Kotlin object is actually the bridge object created in ADR-039, calling its
methods double-bridges (Kotlin → C# thunk → original C# implementation). This is correct but
inefficient. Documented as a known limitation (consistent with the v1 limitation noted in ADR-039).

### Interaction with `var friend: Pet?` setter (`befriend`)

`cat.befriend(pet: Pet)` takes a `Pet` parameter. In v1, only Kotlin-backed `IPet` objects
(those with `_handle`) can be passed — handle extraction via reflection. Passing a C#-implemented
`IPet` (like `Dog`) requires LINE 107 (N function pointers for a non-subscription parameter),
which is deferred.

### Breaking changes

None. Existing generated outputs are unchanged. The concrete `Pet` class is a new addition.
The `IPet` interface is unchanged.

### Scope

**In v1:**
- `CirInterfaceClass` generated for every Kotlin interface (regardless of whether it appears as a
  return type anywhere — generated proactively so the concrete backing class is always available).
- Interface properties with primitive and string return types.
- Interface methods returning `Unit`, primitives, or `String`.
- Nullable interface-typed returns (`Pet?` → `IPet?`).
- The `befriend(pet: Pet)` parameter (Kotlin-backed `IPet` only, via `_handle` extraction).

**Deferred:**
- Interface methods or properties whose return type is another Kotlin interface or class (requires
  chaining the type resolution; straightforward extension but widens the test matrix).
- Interfaces with generic type parameters (e.g., `IReadable<T>`) — requires composing with the
  generics machinery (ADR-010/015/016); deferred.
- Suspend interface methods — compose with ADR-019; deferred.
- Passing a C#-implemented `IPet` (like `Dog`) to `befriend(pet: Pet)` — requires LINE 107
  (general non-subscription interface parameter bridging); deferred.
- Exception propagation from interface method dispatch back through the C# wrapper — same
  mechanism as ADR-024/030; follow-on.
- `@NugetInterfaceReturn` annotation for Kotlin library authors who want to opt out of generating
  the concrete backing class for a specific interface (e.g., marker interfaces with no methods) —
  deferred until a real-world case arises.

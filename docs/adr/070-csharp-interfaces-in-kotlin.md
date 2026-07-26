# ADR-070: C#-declared interfaces in Kotlin — generated `interface` + handle-backed implementation, per-interface slot table

## Status

Accepted

## Context

ROADMAP Phase 9, last item: *"Map C#-declared interfaces → Kotlin `interface` (Kotlin consuming the
contract)."* Direction is **reverse**: C# declares the contract, Kotlin consumes it. Kotlin
*implementing* a C# interface and passing the instance back is explicitly **out of scope** (Phase 7
line 150 / Phase 13); this ADR's job includes making that later item cleanly reachable, and
Decision 4 below is the specific choice that keeps it separable.

### What the pipeline does today (all **verified** by running the real reader in this session)

Spike (scratch dir, never the repo):

```
$ cd $(mktemp -d) && dotnet new classlib -o probe   # csproj rewritten to net8.0, Nullable=enable
# Probe.Zoo: interface IAnimal { string Speak(); int Legs {get;} void Feed(string food); }
#            interface IGeneric<T>; interface IWithDefault { string Greeting() => ...; }
#            class Cat : IAnimal; class Shelter { IAnimal Adopt(); string Describe(IAnimal a);
#                                                 IAnimal? Featured {get;set;} Cat FirstCat(); }
$ dotnet run --project NugetMetadataReader.csproj -- --package Probe .../probe.dll --include Probe.Zoo
```

Real output, abridged:

```json
{ "kind": "interface", "name": "IAnimal",
  "methods": [ { "name": "Speak", "returnType": {"kind":"string","nullable":false}, ...
                 "managedSignature": "method|instance|Probe.Zoo.IAnimal|Speak|()|System.String" },
               { "name": "Feed",  "returnType": {"kind":"void"}, "parameters":[{"name":"food", ...}] } ],
  "properties": [ { "name": "Legs", "type": {"kind":"primitive","name":"int"}, "isReadOnly": true } ] }
{ "kind": "interface", "name": "IGeneric`1", "methods": [], "properties": [] }
{ "kind": "class", "name": "Cat", ... }          <-- no record that Cat implements IAnimal
{ "kind": "class", "name": "Shelter",
  "methods": [ { "name": "FirstCat", ... } ] }   <-- Adopt/Describe/Featured all gone
"diagnostics": [
  { "kind": "skipped_open_generic",            "typeName": "IGeneric`1", "memberName": "Get" },
  { "kind": "skipped_default_interface_method","typeName": "IWithDefault", "memberName": "Greeting" },
  { "kind": "skipped_unbound_type_reference",  "typeName": "Shelter", "memberName": "Adopt",
    "reason": "type `Probe.Zoo.IAnimal` is not a bridgeable bound class in this extraction run" },
  { "kind": "skipped_unbound_type_reference",  "typeName": "Shelter", "memberName": "Describe" },
  { "kind": "skipped_unbound_type_reference",  "typeName": "Shelter", "memberName": "Featured" } ]
```

So, **verified**:

1. The reader **does** emit `RirInterface` with methods and properties, complete with
   `managedSignature` (so ADR-057 bridge identities already work for interface members).
2. An interface-typed **return, parameter, or property** is *not* silently dropped: it produces a
   `skipped_unbound_type_reference` diagnostic and the whole member is skipped. The cause is
   `CollectBoundHandleTypeNames` (`NugetMetadataReader/Program.cs:253-260`), which does
   `if (isInterface || isStaticClass) continue;`, so `SignatureDecoder.GetTypeFromDefinition`
   (`:2199`) falls through to its "not a bridgeable bound class" branch (`:2205`). The message is
   *misleading* rather than wrong: the fix is not "bind this type's namespace" (it already is
   bound).
3. `RirClass` carries **no** implemented-interface list.
4. A generic interface reaches the RIR with its **arity-mangled CLR name**, `IGeneric\`1`, and zero
   members. Emitting that name into Kotlin source produces uncompilable output.

Neither generator consumes `RirInterface`: `NugetGenerateBindingsTask.kt:86/106/195/206/247` and
`NugetGenerateShimsTask.kt:74/89` only `filterIsInstance<RirEnum/RirStruct/RirClass>()`
(**verified**, read in source). `RirTypeRef` has no interface variant (`RirModel.kt:150-200`,
**verified**).

### Four more reader behaviours, each **verified** by the same spike, that set the v1 boundary

| C# shape | What the reader emits today | Consequence |
|---|---|---|
| `interface IDerived : IBase` | `IDerived` with **only its own** members; `Id` from `IBase` absent | a naive Kotlin `interface IDerived` silently loses inherited members |
| `static abstract int Rank();` | plain `RirMethod` with `isStatic: true`, **no diagnostic** | a shim emitting `IStatics.Rank()` is **CS8926** (verified by a separate compile spike: *"A static virtual or abstract interface member can be accessed only on a type parameter"*). `static int Shared() => 7;` (non-abstract) *does* compile as `IStatics.Shared()` |
| `int Score => 3;` (default **property**) | ordinary instance property, **no diagnostic** | the `skipped_default_interface_method` check (`Program.cs:1409`) is method-only. Harmless for consumption (virtual dispatch runs the default body) but inconsistent with the DIM method skip |
| `string this[int i] { get; }` | property literally named **`Item`**, indexer parameter dropped | the getter thunk would emit `receiver.Item`, which is **CS1546**. Pre-existing for classes too; interfaces make it far more likely |
| `event EventHandler? Changed;` | nothing at all, no diagnostic | pre-existing silent drop (add_/remove_ are `SpecialName`) |

### The load-bearing mechanism question, **verified** by spike

Can an `[UnmanagedCallersOnly]` thunk take a `GCHandle` and dispatch through an **interface**,
including to a runtime type the generated shim never names? Scratch console app, `net8.0` compile,
run under `DOTNET_ROLL_FORWARD=LatestMajor`:

```csharp
internal sealed class SecretFerret : IAnimal { ... }        // internal, never named by the shim
public class Shelter { public IAnimal Adopt() => new SecretFerret(); }

internal static class IAnimalRegistration
{
    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    internal static IntPtr Speak_Thunk(IntPtr selfHandle)
    {
        IAnimal receiver = (IAnimal)GCHandle.FromIntPtr(selfHandle).Target!;
        return Marshal.StringToCoTaskMemUTF8(receiver.Speak());
    }
}
// called through delegate* unmanaged[Cdecl]<IntPtr, IntPtr>, exactly as Kotlin would
```

Real output:

```
Speak -> dook
Legs  -> 4
ferret ate egg
runtime type -> Test.Zoo.SecretFerret
is IAnimal   -> True
is Shelter   -> False
```

**Verified**: interface dispatch inside a thunk is free. `[UnmanagedCallersOnly]`'s restrictions are
on the *signature* (blittable, static, non-generic type); the body may cast to and dispatch through
any interface, and the CLR does the virtual dispatch. The runtime type does not need to be public,
bound, or known to the generated shim. This is the whole reason a per-interface slot table works.

And **verified** (separate `System.Reflection.Metadata` spike over the same probe DLL):
`TypeDefinition.GetInterfaceImplementations()` yields `Probe.Zoo.Cat : [TypeDef:Probe.Zoo.IAnimal]`,
directly resolvable to a full name — so implemented-interface lists are cheap to add to the RIR.

### Prior art

- **Xamarin / .NET for Android binding libraries** (the exact mirror of this project: C# consuming
  Java) is the closest analogue. A bound Java interface produces **two** C# types: the interface
  itself, and an **Invoker** — *"Interface bindings have two parts: the C# interface definition, and
  an Invoker definition for the interface… The `Invoker` type definition must inherit
  `Java.Lang.Object`, implement the appropriate interface, and provide all connection methods…
  The `Invoker` type is only necessary when obtaining JNI references to Java-created instances."*
  ([Working with JNI](https://learn.microsoft.com/en-us/previous-versions/xamarin/android/platform/java-integration/working-with-jni)).
  That is precisely the shape this ADR adopts. The same page records their `ISortedMapInvoker`
  problem — `JNIEnv.GetMethodID` returns null for a method inherited from a *base* interface rather
  than the declared one — which is the JNI-flavoured version of our interface-inheritance issue.
- **Kotlin/Native ObjC interop**: `@protocol Foo` imports as Kotlin `interface FooProtocol`
  ([docs](https://kotlinlang.org/docs/native-objc-interop.html)). A protocol is a plain Kotlin
  interface; consumption is free. Crucial asymmetry: the ObjC runtime carries the object's class on
  the object, so Kotlin/Native can create a wrapper for the *actual* class. Our wire carries a bare
  `IntPtr` with no type tag, so we cannot (Decision 3).
- **Kotlin consuming Java**: a Java interface *is* a Kotlin interface, at zero cost, because both
  share one runtime and one vtable. Nothing to translate. That freedom is exactly what a C ABI
  removes: there is no vtable on our wire.
- **COM** is the canonical "interface at a C ABI boundary": *"the vtbl structure is called a binary
  standard because… the structure is completely determined by the particular interface being used…
  independent of the programming language"*, with `QueryInterface`/`AddRef`/`Release` as the first
  three slots
  ([Old New Thing](https://devblogs.microsoft.com/oldnewthing/20070424-00/?p=27143),
  [binary layout](https://www.cplusoop.com/basic-com/module2/com-interface-properties.php)).
  COM puts the dispatch table **in the object**, so any consumer can call any implementation and
  can *ask* an object for another interface. We deliberately do **not** do that: our dispatch table
  is registered **per interface, once, at init** (ADR-041), and the virtual dispatch happens inside
  managed code. That buys COM's key property — a slot table works for *any* implementation,
  including one the generator never saw (verified above) — without a per-object vtable, but it
  gives up `QueryInterface`: a Kotlin holder of `IAnimal` cannot ask "are you also a `Cat`?".

## Alternatives Considered

### 1. Kotlin `interface` + generated handle-backed implementation class, with a per-interface registration slot table (chosen)

Per admissible `RirInterface`, generate:

- `interface IFoo` — pure Kotlin, members only, **no handle member**;
- `class IFooHandle(handle) : IFoo, NugetHandleOwner, AutoCloseable` — the ADR-051 wrapper shape
  (`NugetObjectHandle`, `Cleaner`, idempotent `close()`), dispatching each member through the
  interface's own slots;
- `IFooBindings.kt` with a `nuget_{ns}_{IFoo}_register` export, and C#-side `IFooRegistration.cs`
  with one `[UnmanagedCallersOnly]` thunk per member, each casting the `GCHandle` target to `IFoo`.

Pros: the thunk table is keyed on the *interface*, so it works for any implementation, bound or not,
public or not (verified). Structurally identical to what a `RirClass` already emits, so registration,
`slotCount`/`contractHash` (ADR-054), `bridgeId` (ADR-057), `Cleaner` lifetime (ADR-051) and the
diagnostic model all carry over unchanged. Directly mirrors Xamarin's Invoker.
Cons: two Kotlin types per C# interface; a C# object reachable both as `IFoo` and as a bound class
yields two unrelated Kotlin types (Decision 3).

### 2. Dispatch interface members through the *concrete* class's slot table

No per-interface export; when Kotlin holds a handle typed `IFoo`, look up the concrete class's slots.

Rejected, and not actually implementable: the wire carries no runtime type tag, and the runtime type
may not be bound at all (verified: `Adopt()` returns an `internal sealed class` the shim never
names). There would be no table to select.

### 3. Reuse `RirObjectHandleType` for interface references (no new `RirTypeRef` variant)

The ABI is byte-identical to a class handle, so add interfaces to `CollectBoundHandleTypeNames` and
let the generators resolve the `RirTypeKey` to a `RirClass`-or-`RirInterface` at codegen time.

Pros: zero JSON contract change; old `reverse-ir.json` still parses.
Cons: `boundHandleTypes(file)` (`RirBridging.kt:16`) means "types that render as a wrapper class" and
is consulted by `isV1Type`, wrapper-shape selection and import derivation. Silently widening it
changes every one of those. Decisive counter-argument: every `when (type: RirTypeRef)` in both
generators is exhaustive over the sealed interface, so a **new variant makes the Kotlin compiler
enumerate every site that must change**, while reuse makes each site a silent, correct-looking
fall-through. Rejected for that reason, not for purity.

### 4. Flatten: no Kotlin `interface` at all, emit only a handle wrapper class named `IFoo`

Pros: one type, simplest generator.
Cons: a Kotlin `class` cannot later be implemented by a consumer, so Phase 13 would need a source-
breaking change to the generated API. Also loses `Cat : IAnimal` (Decision 5) permanently. Rejected.

### 5. Put the handle on the interface (`internal val handle: NugetObjectHandle` as an interface member)

Simplest unwrapping at interface-typed parameter positions.
Rejected for the reverse of ADR-040's shipped reasoning: ADR-040 considered and rejected an
`internal IntPtr NugetHandle` member on the generated C# `IFoo` because *"Interop.cs compiles into
the consumer assembly, so it breaks consumer-written impls with CS0535"*. Mirrored here, it forces
any Phase 13 Kotlin implementer of `IFoo` to invent a handle. Decision 4 uses a marker interface on
the *implementations* instead, which is the exact mirror of the shipped `NugetMarshal.HandleOf`.

## Decision

### Decision 1 — a new `RirTypeRef` variant, `RirInterfaceType` (Alternative 3 rejected)

```kotlin
@Serializable
@SerialName("interface")
data class RirInterfaceType(
  val namespace: String,
  val name: String,
  val nullable: Boolean = false,   // ADR-053
) : RirTypeRef
```

Reader side: collect `boundInterfaceTypeNames` alongside `boundHandleTypeNames`, and check it in
`SignatureDecoder.GetTypeFromDefinition` **after** the enum and struct branches and **before** the
`_boundHandleTypeNames` branch (an interface is never an enum or a struct, so the order is about
keeping the existing branches untouched, not about correctness).

> **Verified, and load-bearing**: `NullabilityHelpers.IsNullableCapable` is
> `typeRef is RirStringType or RirObjectHandleType` (`Program.cs:1928`) and `ApplyNullable` is a
> `switch` with a silent `_ => typeRef` default (`:1930-1935`). **Both must gain a
> `RirInterfaceType` case.** If they do not, every interface reference binds non-null with no
> warning — byte for byte the ADR-053 failure this project has already paid for once.

ABI representation is identical to `RirObjectHandleType`: `IntPtr` from
`GCHandle.ToIntPtr(GCHandle.Alloc(obj))`, `IntPtr.Zero` for null (**verified** by the ADR-051 code
path being reused verbatim; the spike above shows the receiving side works).

### Decision 2 — one registration export and one shim class per interface

`nuget_{ns}_{IFoo}_register` / `IFooRegistration.cs`, structurally identical to a class's. Canonical
slot ordering for an interface, a strict subset of the class ordering in
`bridgeableRegistrablesCandidates` (`RirBridging.kt:516`):

```
instance methods (sorted by identity) → per-property [getter, setter?] (sorted by name)
```

No constructors (an interface has none — **verified**, `canHaveConstructor` at `Program.cs:1191`),
and no statics (Decision 6). `contractHash(cls: RirClass, ...)` (`RirBridging.kt:644`) uses only
`cls.name` and the registrable list, so generalizing its first parameter to a `String` name covers
interfaces with no change to the hash algorithm or to ADR-054's runtime check.

Thunk bodies are the class bodies with the receiver line changed from
`Cat receiver = (Cat)GCHandle.FromIntPtr(selfHandle).Target!;`
(`NugetGenerateShimsTask.kt:730`) to
`IFoo receiver = (IFoo)GCHandle.FromIntPtr(selfHandle).Target!;`.
**Verified** by spike that this compiles under `net8.0` with the existing
`CallConvs = new[] { typeof(CallConvCdecl) }` shape and dispatches virtually to an unbound,
non-public runtime type.

### Decision 3 — an interface-typed value always becomes `IFooHandle`, never the concrete class

The wire carries no runtime type tag, and the runtime type may not be bound (**verified**). So a
handle arriving at an interface-typed position is always wrapped in the interface's own
implementation class. Consequences, stated plainly because a consumer will hit them:

- one C# object reachable as both `IFoo` and a bound `Cat` yields **two Kotlin objects of two
  unrelated Kotlin types**. Not equal, not the same class. This extends ADR-005/ADR-051's
  "new wrapper per crossing, no identity caching" from *identity* to *type*;
- there is no downcast: `star() as? Cat` is always `null`. This is our missing `QueryInterface`.
  Deferred (below), and the honest workaround is a C# adapter method returning the concrete type.

### Decision 4 — unwrap at interface-typed parameter positions via a `NugetHandleOwner` marker, not via an interface member

In `NugetRuntime.kt`:

```kotlin
internal interface NugetHandleOwner { val handle: NugetObjectHandle }
```

Every generated wrapper (`Cat`, `IFooHandle`) implements it; its `handle` property already exists
and is already reserved (`WRAPPER_MEMBER_NAMES`, `RirBridging.kt:361`, so no member can collide).
The generated interface `IFoo` stays **pure**. Argument lowering at an interface-typed parameter:

```kotlin
private fun IFoo.nugetHandle(): NugetObjectHandle = (this as? NugetHandleOwner)?.handle
  ?: error("[nuget] ${this::class.simpleName} is a Kotlin implementation of IFoo; passing a " +
           "Kotlin-implemented C# interface back to C# is not supported yet")
```

This is the exact mirror of ADR-040's shipped `NugetMarshal.HandleOf(object)`, which throws
`NotSupportedException` for a C#-implemented `IPet`
(pinned by `IntegrationTests/BidirectionalTests.Cat_Befriend_CSharpImplementedPet_ThrowsNotSupportedException`).

**This is what keeps Phase 13 separable.** Kotlin-implementing-`IFoo` is then purely additive: it
adds a second branch to `nugetHandle()` that allocates a Kotlin-side vtable and a `StableRef`. No
generated signature changes, no source break. The two features do **not** need to be designed
together.

### Decision 5 — a bound class declares the interface supertype only when every interface member is already an identically-signed bridged member of that class

The reader gains `interfaces: List<String>` on `RirClass` and on `RirInterface`, from
`GetInterfaceImplementations()` (**verified** decodable to `Probe.Zoo.IAnimal`), filtered to
interfaces that are themselves admissible and bound.

For a **class**: emit `class Cat(...) : IAnimal` (with `override` on the matching members) **iff**
for every member of `IAnimal` the class has a bridged member with an identical Kotlin signature
(name, parameter types, return type, nullability). Otherwise omit the supertype and emit
`SKIPPED_INTERFACE_SUPERTYPE` naming the interface and the first mismatching member.

The mismatch case is real, not theoretical (**verified**): C# explicit implementation
(`string IBase.Id => "hidden";`) is non-public in metadata, so the reader emits **no** `Id` on the
class at all, and `class Explicitly : IBase` would not compile.

For an **interface**: emit `interface IDerived : IBase` unconditionally when `IBase` is admissible
and bound. This is both correct and cheap, because interface slot tables are **handle-agnostic**:
`IDerivedHandle` implements `IBase`'s members by calling `IBase`'s own slots with the same handle.
Nothing needs to be re-registered. When a base interface is *not* admissible, bind the derived
interface with its declared members only and emit `INFO_INHERITED_INTERFACE_MEMBERS_ABSENT`.

Deferred (see below): synthesizing overrides on a class that dispatch through the *interface's*
slots, which would let `Cat : IAnimal` hold even for explicit implementations.

### Decision 6 — the v1 admissible interface

**Admitted**: a public, top-level, **non-generic** C# interface (name must contain no backtick —
**verified**, the reader emits the mangled `IGeneric\`1`) with at least one admissible member.
**Admissible members**: instance methods and instance properties (get, and set when public) whose
types are already in the ADR-043/051/056 v1 vocabulary — `void`, `string`, the eight primitives,
bound enums, bound structs, bound class handles, and now bound interface handles.

**Skipped, each with a named diagnostic:**

| Shape | Diagnostic | Why |
|---|---|---|
| generic interface (`IFoo\`1`) | `skipped_generic_interface` | open generic; the mangled name is not valid Kotlin (**verified**). Note this is a *new interface-level* diagnostic: today `IBox<T>.Get()` is skipped **per member** as `skipped_open_generic` and the interface itself still reaches the RIR with zero members and no diagnostic of its own (**verified** against the real `Test.Menagerie` `reverse-ir.json`) |
| any `static` member on an interface | `skipped_interface_static_member` | `static abstract`/`static virtual` is **CS8926** when called on the interface (**verified** by compile spike). `static` non-abstract *is* callable but is a rare shape; excluding both keeps one rule |
| default interface **method** | `skipped_default_interface_method` (existing) | unchanged from today (**verified** it already fires) |
| indexer (`this[int]`) | `skipped_indexer` | reaches the RIR as a parameterless property named `Item` (**verified**); a generated `receiver.Item` is CS1546 |
| `event` | `skipped_event` | currently dropped with no diagnostic at all (**verified**) |
| interface with zero admissible members | `skipped_empty_interface` | nothing to generate; must not emit an empty registration export |

Two of these (indexer, event) are **pre-existing gaps that also affect classes**. Fixing them at the
reader means classes get the diagnostics too, which is a strict improvement; call it out in the
implementation so it is not mistaken for a regression.

Also amend the misleading message: when the referenced type *is* a bound interface but the interface
is inadmissible, `skipped_unbound_type_reference`'s current hint ("Bind this type's namespace") is
wrong — the namespace is already bound. Emit the interface-specific reason instead.

### What the Kotlin consumer sees

Given the fixture below:

```kotlin
// generated: IFeedable.kt
internal interface IFeedable {
  fun describe(): String
  fun feed(food: String)
  val legs: Int
  var nickname: String?
}

// generated: IFeedableHandle.kt  (the Xamarin "Invoker")
internal class IFeedableHandle internal constructor(handle: COpaquePointer)
  : IFeedable, NugetHandleOwner, AutoCloseable { /* Cleaner + idempotent close(), ADR-051 */ }

// generated: Ferret.kt — supertype declared, every member matches (Decision 5)
internal class Ferret internal constructor(handle: COpaquePointer)
  : IFeedable, NugetHandleOwner, AutoCloseable { ... }
```

Consumer code in `test-library`:

```kotlin
val sanctuary = Sanctuary()

val star: IFeedable = sanctuary.star()          // C# returned IFeedable -> IFeedableHandle
println(star.describe())                        // virtual dispatch, C# side
star.nickname = "Bandit"                         // settable interface property

val hidden: IFeedable = sanctuary.hiddenResident()   // runtime type is an internal C# class
println(hidden.legs)                                  // still works (verified mechanism)

sanctuary.introduce(Ferret())                    // bound class at an interface-typed parameter
println(sanctuary.featured?.describe())          // nullable interface-typed property
```

## Consequences

### Work items

**Metadata reader** (`NugetMetadataReader/Program.cs`)

1. `CollectBoundInterfaceTypeNames` (admissibility per Decision 6), threaded into `SignatureDecoder`.
2. `RirInterfaceType` emitted from `GetTypeFromDefinition`; `IsNullableCapable` **and**
   `ApplyNullable` extended (see the warning in Decision 1).
3. `interfaces: string[]` on `RirClass` and `RirInterface` from `GetInterfaceImplementations()`.
4. New diagnostics per the Decision 6 table; the interface-specific
   `skipped_unbound_type_reference` reason.
5. Skip static members / indexers / generic interfaces before they reach `RirInterface`.

**Shared bridging** (`rir/RirModel.kt`, `rir/RirBridging.kt`)

6. `RirInterfaceType` + `interfaces` fields (both defaulted, so old `reverse-ir.json` still parses).
7. `boundInterfaceTypes(file)`; `isV1Type` gains the `RirInterfaceType` branch;
   `bridgeableInterfaceRegistrables(iface, ...)` (Decision 2 ordering);
   `contractHash`'s first parameter generalized to a name.
8. `RirTypeRef.describe()` / `signaturePart()` / `isNullable` gain the variant (the exhaustive
   `when`s will point at each one — that is the argument for Alternative 1 over 3).

**Kotlin generator** (`NugetGenerateBindingsTask.kt`) — `IFoo.kt`, `IFooHandle.kt`,
`IFooBindings.kt`; `NugetHandleOwner` in `NugetRuntime.kt`; supertype emission (Decision 5);
`nugetHandle()` lowering (Decision 4); `expectedRegistrations` gains interfaces so ADR-054's
"N of M registrations fired" counts them.

**C# generator** (`NugetGenerateShimsTask.kt`) — `IFooRegistration.cs` with the interface-cast
receiver line; `csNativeType(RirInterfaceType) = type.name`; `csAbiType = IntPtr`;
`paramConversion = (IFoo)GCHandle.FromIntPtr(p).Target!`; return conversion identical to
`RirObjectHandleType`.

### Fixture (`TestDependency/Menagerie.cs`, new namespace `Test.Menagerie`)

Designed to cross every seam, per the feature-design rule — not the simplest interface:

```csharp
#nullable enable
namespace Test.Menagerie;

public interface IFeedable
{
    string Describe();               // return needs marshalling (UTF8 alloc)
    int Legs { get; }                // return needs NO marshalling (pass-through primitive)
    void Feed(string food);          // param needs marshalling, void return
    string? Nickname { get; set; }   // nullable (ADR-053) + settable => getter AND setter slot
}

public interface ITagged : IFeedable { string Tag { get; } }   // interface inheritance

public class Ferret : IFeedable   { /* all members public => supertype declared */ }
public class Shy    : IFeedable   { string IFeedable.Describe() => "..."; /* explicit => supertype SKIPPED + diagnostic */ }
internal class Nocturnal : IFeedable { }                        // never bound; only reachable as IFeedable

public class Sanctuary
{
    public IFeedable Star();                     // interface-typed RETURN
    public IFeedable HiddenResident();           // RETURN whose runtime type is unbound + internal
    public string Introduce(IFeedable f);        // interface-typed PARAMETER
    public IFeedable? Featured { get; set; }      // interface-typed PROPERTY, nullable, settable
    public ITagged Flagship();                    // derived-interface return
}

// negative cases, one per Decision 6 diagnostic
public interface IBox<T>       { T Get(); }
public interface IWithStatics  { static abstract int Rank(); }
public interface IWithDim      { string Greeting() => "hi"; }
public interface IIndexedThing { string this[int i] { get; } }
public interface IEmpty        { }
```

Round-trip assertions in a new `MenagerieRoundTripTests.cs` (ADR-050 path), plus generated-text unit
tests in `nuget-plugin/src/test`.

### Deferred (for the roadmap)

1. **Kotlin implementing a C# interface and passing it back** (Phase 7 line 150 / Phase 13).
   Unchanged by this ADR and provably separable: Decision 4 gives it a single insertion point.
2. **Downcast / `QueryInterface`** — `star() as? Cat`. Would need a runtime type tag on the wire
   plus a bound-type registry.
3. **Object identity across the interface/class split** — one C# object, two Kotlin wrappers of two
   types. Same ROADMAP "object identity preservation" item as ADR-005/051.
4. **`Cat : IAnimal` when the class implements explicitly** — synthesized overrides dispatching
   through the interface's own slot table. Decision 5 skips the supertype instead.
5. **Generic interfaces** (`IReadOnlyList<T>` etc.), blocked on the same open-generic wall as
   ADR-043.
6. **Static abstract / static virtual interface members** (would need a per-implementation shim).
7. **Interface events and indexers** — deferred as *features*; this ADR only makes them diagnosed
   rather than silent, for classes as well as interfaces.
8. **Default interface method consumption** — currently skipped, though a DIM *is* callable through
   an interface-typed receiver, and the default *property* case is already bound today
   (**verified**). Admitting DIM methods is a small, separate, self-contained change.

### Inferred claims (not verified this session)

Everything load-bearing above is labelled **verified** with the command and output that proved it.
The remainder is inferred and the walking skeleton must falsify it:

- **Inferred**: the generated Kotlin (`IFooHandle` implementing a generated `interface`, plus the
  `NugetHandleOwner` marker) compiles on the real Kotlin/Native toolchain and round-trips. No Kotlin
  toolchain was exercised in this session. The reverse direction still has **no compile check on its
  generated Kotlin** (open ROADMAP item), so this is the highest-risk unverified claim here.
- **Inferred**: `contractHash`'s first-parameter generalization leaves existing class hashes
  byte-identical. Read in source (`RirBridging.kt:644-652`, it concatenates `cls.name`), not run.
- **Inferred**: interface-typed values compose with structs (`RirStructType` components are never
  handles today, so no interaction is expected) and with the ADR-059 arity ceiling (an interface
  member's receiver arity is 1, as for a class).
- **Inferred**: the `skipped_interface_static_member` rule loses nothing real. `static` non-abstract
  interface methods *are* callable (**verified** `IStatics.Shared()` compiles), so excluding them is
  a deliberate scope choice, not a hard constraint.

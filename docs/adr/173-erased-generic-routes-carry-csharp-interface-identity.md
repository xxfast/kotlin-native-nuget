# ADR-173: Erased generic routes carry a C#-implemented interface in through `Wrap<T>` and back out by token in `Materialize<T>`

## Status

Accepted

## Context

A Kotlin interface renders as a C# interface `IPet` plus a generated backing class `Pet : IPet`
(ADR-040), and a C# class implementing `IPet` crosses into Kotlin as a bridge object that carries a
token back to the original (ADR-084). ADR-136 made the `Task<IPet>` and `Flow<IPet>` reads resolve
that token, so a stored C# `Dog` comes back as the same instance, and it deferred the erased generic
routes (its Alternative 2) "if a generic route ever needs it".

Three erased generic routes exist, and a C# `Dog : IPet` fails on each of them at the ARGUMENT step,
before any read. All three findings below are **verified** from the generated `Interop.cs` of a
2026-09-28 spike (fixture in the research memo, generated with
`./gradlew :test-library:kspKotlinMingwX64`); the exception types are C# semantics of that text, not
observed at runtime:

| Route | Argument step | Result for `Dog` |
|---|---|---|
| Lambda (`KotlinFunc<...>.Invoke`) | `NugetMarshal.Wrap<T>` | `NotSupportedException("Cannot pass IPet to a Kotlin collection")` at `CirMarshalRenderer.kt:371` |
| ADR-147 generic class (`PetBox<T> where T : IPet`) | `NugetMarshal.Wrap<T>` | same |
| Legacy `fun <T>` (ADR-064: `identity`, `adoptPet`) | `((INugetHandle)value).Handle` (`CirFunctionTranslator.kt:1044`) | `InvalidCastException` |

The read side fails too: `Materialize<T>` (`CirMarshalRenderer.kt:142-147`) is a `Factories` lookup,
and `factoryEntries` (`CirTranslator.kt:1048`) registers nothing for an interface, so `T = IPet`
throws `NotSupportedException` even for a Kotlin-backed pet, and `T = Dog` can never have a factory.
And the lambda route spells an interface type argument as the backing class:
`fun petRelay(): (Pet) -> Pet` renders `KotlinFunc<global::TestLibrary.Cat.Pet, global::TestLibrary.Cat.Pet>`
(**verified**, spike), so a consumer cannot even write `relay.Invoke(dog)`.

The private `WrapArg<T>` copy the earlier research cited is gone (**verified** by reading
`CirFunctionRenderer.kt:3-6`): boundary nullability part A1 routed every lambda argument through
`NugetMarshal.Wrap<T>` with `owned` disposal. ADR-171 then added the value-class `Boxers` arm to
`Wrap<T>` and the `NugetUnbox` form to `Factories`. So two of the three routes already share one write
function, and the legacy route is the only one with its own dialect.

## Alternatives Considered

### 1. One interface-aware `Wrap<T>` / `Materialize<T>` pair, legacy route retired onto it (chosen)

Teach the shared marshal helpers about interfaces once; move the legacy `fun <T>` object arm onto
`Wrap<T>`; register an interface key in `Factories`; spell interface type arguments as the interface.
Pros: every erased route gets identity with no per-route code; the legacy route loses its private
cast and gains ADR-171's `Boxers` arm (value classes at `identity<ChartId>` currently hit the same
cast). Cons: touches the hot `Materialize<T>` path (mitigated by the probe condition), and the
respelling changes a public type argument.

### 2. Patch each route where it fails

Swap the legacy cast for `HandleOfOrZero`, replace `Wrap<T>`'s throw, add a factory entry. Pros:
smallest diff. Cons: keeps two write dialects (the legacy one still has no `Boxers` arm), which is
the drift that produced `WrapArg<T>` in the first place.

### 3. Read-only slice

Probe, factory and spelling only. Cons: `AdoptPet<IPet>(dog)` and `new PetBox<IPet>(dog)` still throw
at the argument, so the consumer promise ("pass your `IPet`, get it back") is not delivered.

### 4. Per-site `read:` delegate (ADR-136 Alternative 1)

Cons: `T` is open on the legacy and generic-class routes; there is no site to specialise.

### 5. Probe the token on every reference read

Cons: an extra P/Invoke per element on every `T = Cat` read, for no gain over the conditional probe.

## Decision

### Consumer API

```csharp
sealed class Dog : IPet { /* consumer implementation */ }

IPet dog = new Dog("Rex");

Assert.Same(dog, Helpers.AdoptPet<IPet>(dog));          // legacy fun <T : Pet>
Assert.Same(dog, PetRelayKt.RelayPet<Dog>(dog));        // legacy fun <T>, T = the consumer's type

using KotlinFunc<IPet, IPet> relay = PetRelayKt.PetRelay(); // was KotlinFunc<Pet, Pet>
Assert.Same(dog, relay.Invoke(dog));

using var box = new PetBox<IPet>(dog);                  // ADR-147 generic class
Assert.Same(dog, box.Value);

using var oreo = new Cat("Oreo", 9);
IPet adopted = Helpers.AdoptPet<IPet>(oreo);            // Kotlin-backed pet at T = IPet
Assert.Equal(oreo.Name, adopted.Name);
```

Fixture (`test-library/.../test/cat/PetRelay.kt`):

```kotlin
fun petRelay(): (Pet) -> Pet = { it }

fun <T> relayPet(value: T): T = value

interface Squeaker {
  val squeak: String
}

fun squeakerRelay(): (Squeaker) -> Squeaker = { it }
```

`Squeaker` appears nowhere but as a lambda type argument; it pins the reachability rule below. Its
generic-class twin, `interface Chewer` reached only through `fun chewerBox(): Box<Chewer>`, pins
the generic-class arm of the same rule.

A lambda return is admitted only on a top-level function (**verified**, spike: a member
`fun supplier(): () -> Pet` is skipped `SKIPPED_UNSUPPORTED_RETURN`), so the lambda fixture is
top-level.

### Write: `Wrap<T>` falls back to the bridge, and the legacy route uses `Wrap<T>`

`Wrap<T>` keeps its order (null, primitives, narrow kinds, ADR-171 `Boxers` on the runtime type,
`INugetHandle`) and replaces the final throw with the existing bridge extraction, assigning `owned`
only after the mint (the ADR-135 rule):

```csharp
owned = false;
if (value is INugetHandle wrapper) return wrapper.Handle;
IntPtr bridged = HandleOf((object)value!, typeof(T)); // NugetBridge.HandleFor, or the ADR-040 throw
owned = true;
return bridged;
```

**Verified** from generated text: `NugetBridge.HandleFor(object impl, Type declared)` matches
`declared == typeof(I...)` first, then walks `impl is I...` in generation order, so `T = Dog` and
`T = object` still bridge. With no bridge layer in the module (`includesBridge == false`),
`HandleOf` keeps the ADR-040 `NotSupportedException` (**verified** by reading
`CirMarshalRenderer.kt:557-561`). Every `Wrap<T>` call site already disposes on `owned` after the
native call (**verified**: `appendBoxedArguments` in `CirFunctionRenderer.kt`, generated
`PetBox`/`Box` constructors).

The legacy `fun <T>` object arm (`CirFunctionTranslator.kt`) stops casting and calls
`NugetMarshal.Wrap<T>(value!, out bool owned)` inside `try { ... } finally { if (owned) NugetMarshal.Dispose(handle); }`.
The `!` only quiets CS8604 on a `T?` parameter: `Wrap<T>` answers a null with `IntPtr.Zero` itself.
Inferred (not run): a value-class box passed this way survives the export's
`value?.asStableRef<Any>()?.get()` (generated `CNameExports.kt:5749`) because it is a `StableRef<Any>`
like every other handle.

### Read: the token probe goes first in `Materialize<T>`, before `Factories`, for interface and unknown keys

```csharp
internal static T Materialize<T>(IntPtr handle)
{
    Type key = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);      // ADR-171, unchanged
    if (!key.IsValueType && (key.IsInterface || !Factories.ContainsKey(key))
        && TryResolveCSharpObject(handle, out object original))
        return (T)original;                                             // probe disposed the handle
    if (Factories.TryGetValue(key, out Func<IntPtr, object>? factory)) return (T)factory(handle);
    throw new NotSupportedException(...);
}
```

- Value classes (ADR-171 `NugetUnbox` entries) are `IsValueType` and never pay the probe.
- `T = Cat` (a non-interface `Factories` hit) is unchanged.
- `T = IPet` must probe BEFORE the new `Factories[typeof(IPet)]` entry, or a C# `Dog` comes back as a
  fresh `Pet` wrapper: the silent-wrong-output case this ordering exists to prevent.
- `TryResolveCSharpObject(IntPtr, out object)` is an unconstrained sibling of
  `TryResolveCSharp<T> where T : class` (`CirMarshalRenderer.kt:602`), same body.
- `IsValueType` / `IsInterface` are `Type` properties, not member reflection: trim and AOT safe
  (inferred, not compiled under the ADR-102 AOT leg).

The probe is the ADR-136 mechanism: `nuget_csharp_token` returns
`(handle.asStableRef<Any>().get() as? NugetCSharpBridge)?.nugetToken`
(**verified** by reading `NugetRuntime.kt:731-733`). The legacy export returns
`NugetHandles.retain(result)` on the bare Kotlin object (**verified**, generated `CNameExports.kt:5749`),
so a relayed bridge resolves. Inferred (not opened): the `KotlinFunc` invoke export and the
generic-class getter retain the bare object the same way. A wrong inference fails `Assert.Same`
loudly; it does not pass silently.

### Factories: an interface key constructing its backing class

`factoryEntries` registers each exported, non-sealed, non-`fun` interface that has a backing class:

```csharp
[typeof(global::TestLibrary.Cat.IPet)] = static handle => new global::TestLibrary.Cat.Pet(handle, out _),
```

`CirFactoryEntry` gains `constructTypeName` (defaulting to the key, `qualifiedTypeName`), and
`CirClass` gains `backsInterface`, which `translateInterfaceBackingClass` sets to `I<Name>`.
`factoryEntries` emits the interface entry beside the backing class's own `Pet` entry, from the
namespace-level walk only, so a nested interface registers nothing yet (deferred). A statically
written line, so trim/AOT safe on the ADR-094 argument (inferred).

### Reachability: an erased type-argument position makes an interface reachable

A backing wrapper, its `Factories` entry, its `NugetBridge.HandleFor` arm and its Kotlin dispatch
exports all hang off one list, the ADR-040 / ADR-135 `reachableInterfaces` set in
`NugetProcessor`. That set was built from the ordinary plans only, and a lambda return or a
generic-class carrier is a legacy route that never becomes a plan. So an interface reached ONLY as
an erased type argument (`fun squeakerRelay(): (Squeaker) -> Squeaker`) was spelled `Squeaker` with
no such C# type (CS0234 at pack time).

The walk that builds the set gains one predicate, `erasedInterfaceArguments(type)`: when `type` is a
lambda (`LAMBDA_TYPES`) or an exported class with type parameters, every type argument the
classifier's `legacyFlowElementInterface` calls a `BridgeType.Interface` is reachable. It runs over
a superset of the positions `csTypeArgument` spells: every public top-level function return, every
top-level property (not a `csTypeArgument` caller; walked as harmless over-inclusion), and every
public declared property of a class, object or sealed subclass. It uses the
same classification `csTypeArgument` spells `I<Name>` with, so an interface is spelled at an erased
position exactly when it is made reachable. Over-inclusion (a member function's lambda return, which
is skipped named upstream, is deliberately not walked) would only mint an unused wrapper.

### Spelling: an interface type argument is the interface

`csTypeArgument` (`CirTypeMapping.kt`) takes the `ForwardBridgeTypeClassifier` (threaded through
`csTypeArguments`, `csTypeArgumentNames`, `translateSpecializedFunction` and `translateFunction`) and
gains an interface arm ahead of the `qualifiedElementCsType` fallback that calls
`legacyFlowElementInterface` (`ForwardLegacyRouteCollections.kt:897`) and spells its `csharpType`, so
there is one interface spelling. This flips every erased type-argument spelling of an interface, which is the point: the
backing class is an ADR-040 implementation detail. **Verified** by grep, the helper's only callers
are erased positions: lambda-typed class properties (`CirClassTranslator.kt:997`, `:1017`, `:1023`),
the sealed-subclass copy (`:2398`, `:2417`), lambda returns (`CirFunctionTranslator.kt:190`, `:210`)
and generic-class carrier returns (`:536`, `:557`), all read through `FromHandle<T>`. No callback
delegate (ADR-037) spells through it. No Tier 1 pin spells `.Pet>` as a C# type argument
(**verified** by grep).

### Breaking

Every public C# signature that spells an exported interface at an erased type argument changes:
`PetRelayKt.PetRelay()` returns `KotlinFunc<IPet, IPet>` where it returned `KotlinFunc<Pet, Pet>`,
and the same holds for a lambda-typed property and a generic-class carrier over an interface. A
consumer that named the old type explicitly (`KotlinFunc<Pet, Pet> f = ...`) stops compiling; `var`
and passing the result straight on keep compiling.

Justified from ADR-040: the backing wrapper `Pet` is not consumer-visible API. It is a `sealed`
handle wrapper with no public constructor that exists so a Kotlin-backed value has something to
be; the contract is `IPet`. The old spelling leaked that implementation detail into a public type
argument, and it made the route unusable for its purpose, since a consumer's own `Dog : IPet` is
not a `Pet` and `relay.Invoke(dog)` did not compile. No fixture, doc or Tier 1 pin depended on
the old spelling (verified by grep). Treated as a defect fix, not a deprecation cycle.

## Consequences

- A C# `IPet` passes through all three erased routes and returns as the same instance; a
  Kotlin-backed pet at `T = IPet` materialises as `IPet`.
- The legacy `fun <T>` route has no private write dialect left; it also accepts value classes
  (ADR-171 `Boxers`) as a side effect.
- `KotlinFunc<Pet, ...>` becomes `KotlinFunc<IPet, ...>`: a public signature change, treated as an
  ADR-040 defect fix.
- Each crossing mints a bridge transfer handle, freed by `owned` on the write and by the probe on the
  read; LeakTests gain one row per route.
- A collection inside a generic class instantiated at `T = IPet` now accepts and returns C# pets
  element-wise. That is not the static `List<Pet>` route, which stays refused (ADR-040).
- `T = Dog` when Kotlin returns something other than that `Dog`, inherent to erasure and documented:
  a Kotlin-backed pet misses the probe and misses `Factories[typeof(Dog)]`, so it throws
  `NotSupportedException("No generated factory materialises ...Dog...")`; a stored C# object of a
  different type (`Wolf : IPet`) hits the probe and throws `InvalidCastException` from the `(T)` cast.
- Landing order: the `csTypeArgument` respell and the `Factories[typeof(IPet)]` entry ship in the
  same PR. The respell alone turns every existing interface-typed erased read into
  `Materialize<IPet>` with no factory, breaking a consumer that works today.
- Deferred: sealed interfaces and `fun interface`s at type arguments; nested-interface factory keys
  (ADR-134 naming) until a nested fixture pins them; the unexplained skip of a top-level
  `fun petSupplier(pet: Pet): () -> Pet`; Kotlin-side `===` across crossings (ADR-084).
- ADR-136's deferral of the generic-function route becomes historical.

### Inferred claims (not verified)

1. ~~The `KotlinFunc` invoke export and the ADR-147 generic-class getter retain the bare Kotlin
   object.~~ Now **verified** at runtime: `ErasedInterfaceIdentityTests` asserts `Assert.Same` on
   all three routes, and LeakTests Rows 6g-6i return to baseline.
2. A value-class box survives the legacy export's `asStableRef<Any>()` unchanged.
3. The `IsValueType`/`IsInterface` probe condition and the new `Factories` line compile clean under
   the AOT/trim analyzers.
4. Nested interface backing-class naming matches a consumer's `typeof(Outer.IInner)`. If wrong, the
   nested interface read at `T = Outer.IInner` throws "No generated factory"; it does not return a
   wrong object.
5. No runtime exception was observed: the argument-step failures are read off the generated text.

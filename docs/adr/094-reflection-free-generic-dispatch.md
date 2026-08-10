# ADR-094: Forward, reflection-free generic dispatch via a generated factory registry

## Status

Accepted

## Context

The type-erased generics bridge (ADR-010) dispatches non-primitive type arguments at runtime with
private-member reflection, in exactly two shapes:

- **Materialise**: `Activator.CreateInstance(typeof(T), BindingFlags.NonPublic, ..., new object[] { handle })`
  against the wrapper's `internal X(IntPtr)` constructor. Two emit sites: the `FromHandle<T>` object
  fallback (`CirMarshalRenderer.kt:217-219`) and the generic top-level function return path
  (`CirFunctionTranslator.kt:845-847`). Verified against source on this branch.
- **Extract**: `typeof(T).GetField("_handle", NonPublic | Instance)` to pull the native handle out of
  a wrapper. Nine emit sites: `CirMarshalRenderer.kt` (`Wrap<T>`, `HandleOf(object)`,
  `HandleOf(object, out bool)`), `CirFunctionRenderer.kt` (`NugetFuncNative.WrapArg<T>` and
  `NugetSuspendFuncNative.WrapArg<T>`), `CirFunctionTranslator.kt` (generic function object-argument
  branch), and `CirClassRenderer.kt` (constrained and unconstrained generic-class constructors).
  Verified against source on this branch.

ADR-038 (Deferred) documents why this breaks under AOT: the trimmer cannot statically prove the
`_handle` field or the internal constructor reachable, and AOT-only runtimes have no JIT to fall
back on. This stopped being theoretical in the PeopleInSpace 0.3.0 integration: its LIMITATIONS
item 1 reports the generated bindings crashing on **Mac Catalyst**, where the Mono AOT-only
runtime hits this reflection in the generic paths. C# on iOS/Catalyst is AOT-only, so the failure
is unconditional there, not an opt-in publishing mode. ADR-038's own sequencing says the
reflection-free `genericDispatch` strategy "likely warrants its own ADR"; this is that ADR.

Constraints discovered from the source (all Verified by reading this branch):

- The marshal helper is emitted **once per generated file, into the root namespace**
  (`CirTranslator.kt:424-474` prepends `CirMarshalHelper` to `context.rootNamespace`). It is not
  per-namespace. Every other generated namespace is `rootNamespace` or `rootNamespace.<Suffix>`
  (`mapPackageToNamespace`, `CirTypeMapping.kt:156-176`, returns `"$rootNamespace.$suffix"`), so
  unqualified `NugetMarshal` references in child namespaces resolve by enclosing-namespace lookup,
  and one registry in the root helper can see every wrapper in the assembly.
- Every wrapper that reflection currently targets carries `internal IntPtr _handle` plus an
  `internal X(IntPtr)` constructor in the same assembly: ordinary classes
  (`CirClassRenderer.kt:203,243-247`), generic classes (`CirClassRenderer.kt:73,114-117`), sealed
  hierarchies (`CirSealedRenderer.kt:6-17`), `KotlinFunc` wrappers (`CirFunctionRenderer.kt`).
  Same-assembly code can call these constructors directly; no reflection is needed to reach them.
- The async path already materialises without reflection (`new ${type}(resultPtr)`,
  `CirConcurrencyRenderer.kt:92`), proving direct construction is sufficient where the type is
  statically known. The generic paths only reflect because `T` is erased at emit time.

## Alternatives Considered

### 1. Generated factory registry + `INugetHandle` interface, replacing reflection outright (chosen)

Emit a `Dictionary<Type, Func<IntPtr, object>>` in the root `NugetMarshal` with one statically
written entry per concrete wrapper type, and an internal `INugetHandle` interface implemented by
every wrapper, replacing every `GetField` with a type test. Details in Decision.

**Pros:** works identically under JIT and AOT (one dialect, one test surface); zero consumer-visible
API change (every new member is `internal`); each mechanism is plain statically-analyzable code the
trimmer keeps.
**Cons:** registry size is one line per wrapper (linear in exported types); arbitrary
consumer-invented closed generic instantiations lose the accidental `Activator` support (see
Consequences).

### 2. Keep reflection as the default behind ADR-038's `CSharpProfile`, registry opt-in

**Pros:** byte-identical default output; zero migration risk.
**Cons:** the Catalyst crash stays the default experience; two generic-dispatch dialects double the
test surface indefinitely; the registry has no JIT downside that would justify keeping reflection at
all. ADR-038 proposed the profile when the reflection-free design was unproven; the profile remains
the right vehicle for `[LibraryImport]` (a real dialect choice with a language-version floor), but
generic dispatch has a strictly better single answer.

### 3. `MakeGenericType` / `MakeGenericMethod` factories

**Cons:** rejected outright: constructing generic types at runtime over value-type arguments
requires JIT code generation and is exactly what AOT-only runtimes cannot do (inferred from the
.NET NativeAOT limitations documentation; not spiked, but this is the documented headline
limitation). Trades one reflection hole for a worse one.

### 4. `[DynamicallyAccessedMembers]` annotations on the existing reflection

Annotating `T` and the wrapper types tells the *trimmer* to keep `_handle` and the internal
constructor, which fixes trimmed CoreCLR NativeAOT in principle. **Cons:** it does not remove the
reflective *invocation*, so it only helps where the runtime can still reflect over kept members; on
Mono AOT-only targets (Catalyst, iOS) `Activator.CreateInstance` over a non-public constructor
still depends on runtime support that the interpreter-less configuration does not guarantee, and
the annotation cannot be applied to an unconstrained `T` at all call sites without infecting every
public generic signature. Inferred from trimming documentation; not spiked. A partial fix at best,
and it leaves the design goal (no reflection in generated output) unmet.

### 5. C# source generator on the consumer side

Move materialisation into a Roslyn source generator shipped with the package. **Cons:** a whole new
compiler-plugin deliverable to build, version and debug, to produce information the KSP processor
already has at generation time. The processor *is* our source generator.

## Decision

Replace the reflection outright. No profile flag for generic dispatch; ADR-038's `CSharpProfile`
remains scoped to the `[LibraryImport]` dialect when that work is picked up.

### The `INugetHandle` interface (extract direction)

One internal interface, emitted at the **global namespace** of the generated file (before the first
`namespace` block), so every generated namespace sees it unqualified without threading a namespace
string through the renderers:

```csharp
internal interface INugetHandle
{
    IntPtr Handle { get; }
}
```

Every class that declares `internal IntPtr _handle` adds `INugetHandle` to its base list and
implements it **explicitly**, keeping it out of the public IntelliSense surface:

```csharp
public sealed class Pet : IDisposable, INugetHandle
{
    internal IntPtr _handle;
    IntPtr INugetHandle.Handle => _handle;
    ...
}
```

Emit rule: the interface is implemented where `_handle` is declared, i.e. `CirClass` with
`superClass == null` (`CirClassRenderer.kt:202-207`), `CirGenericClass`, the sealed base class
(`CirSealedRenderer.kt:6`), and the function-wrapper classes `CirFunctionRenderer.kt` emits.
Corrected post-implementation: that last group is not "the `KotlinFunc`/`KotlinSuspendFunc`
wrappers" (implying one shape each) but **six** distinct `_handle`-declaring class shapes, one per
arity variant of each wrapper family: `KotlinFunc<TResult>` and `KotlinFunc<...>` (the wider-arity
generic form), `KotlinSuspendFunc<TResult>` and `KotlinSuspendFunc<...>`, and `KotlinSuspendAction`
and `KotlinSuspendAction<...>`. All six implement `INugetHandle` explicitly. Derived classes
(ordinary subclasses, sealed subclasses) inherit the implementation.

**Verified by spike** (scratch net10.0 console app, this session): a `public sealed class` in a
child namespace implementing an `internal` interface declared at the global namespace, with an
explicit implementation, compiles and runs; `value is INugetHandle h ? h.Handle : ...` extracts the
handle. Output observed: `handleOf: 42`.

Every `GetField("_handle")` site becomes a type test, preserving each site's existing miss
behaviour:

| Site | Today (miss = `field == null`) | After |
|------|-------------------------------|-------|
| `NugetMarshal.Wrap<T>` | `NotSupportedException` | `value is INugetHandle h ? h.Handle : throw` same message |
| `NugetMarshal.HandleOf(object)` | bridge fallback or `NotSupportedException` (ADR-084) | `value is INugetHandle h ? h.Handle :` same fallback |
| `NugetMarshal.HandleOf(object, out bool)` | delegates to above | same shape |
| `NugetFuncNative.WrapArg<T>` / suspend twin | `NotSupportedException` | same message |
| Generic fn object-arg branch (`CirFunctionTranslator.kt:834-848`, **two** emit sites, not one: the `returnsGenericClass` true and false branches each had their own `field!`, corrected post-implementation) | `field!` (throws NRE on miss) | `(INugetHandle)value!` cast (throws `InvalidCastException` on miss), both branches |
| Unconstrained generic-class ctor (`CirClassRenderer.kt:98-102`) | `NotSupportedException "Cannot create ..."` | `value is INugetHandle h` else same throw |
| Constrained generic-class ctor (`CirClassRenderer.kt:80-82`) | `field!` (bound guarantees hit) | `((INugetHandle)value!).Handle` (bound type implements the interface, cast always succeeds) |

### The factory registry (materialise direction)

In the root `NugetMarshal`, populated by a **static field initializer** with fully qualified type
references (the root namespace cannot see child-namespace types unqualified):

```csharp
internal static readonly System.Collections.Generic.Dictionary<Type, Func<IntPtr, object>> Factories = new()
{
    [typeof(global::TestLibrary.Cat)] = static h => new global::TestLibrary.Cat(h),
    [typeof(global::TestLibrary.Clinic.Chart)] = static h => new global::TestLibrary.Clinic.Chart(h),
    [typeof(global::TestLibrary.ApiResult.Success)] = static h => new global::TestLibrary.ApiResult.Success(h),
    // ... one entry per concrete wrapper with an internal IntPtr constructor
};

internal static T Materialize<T>(IntPtr handle)
{
    if (Factories.TryGetValue(typeof(T), out Func<IntPtr, object>? factory)) return (T)factory(handle);
    throw new NotSupportedException($"No generated factory materialises {typeof(T)} from a Kotlin handle");
}
```

**Verified against the shipped generator's actual output** (`test-library`'s `Interop.cs`, 55 registered
factories, real `Materialize<T>` body byte-identical to the sketch above):

```csharp
internal static readonly System.Collections.Generic.Dictionary<Type, Func<IntPtr, object>> Factories =
    new System.Collections.Generic.Dictionary<Type, Func<IntPtr, object>>
{
    [typeof(global::TestLibrary.Platform.Device)] = static handle => new global::TestLibrary.Platform.Device(handle),
    [typeof(global::TestLibrary.Platform.Sensor)] = static handle => new global::TestLibrary.Platform.Sensor(handle),
    [typeof(global::TestLibrary.Cat.AsyncCatService)] = static handle => new global::TestLibrary.Cat.AsyncCatService(handle),
    [typeof(global::TestLibrary.Cat.Kitten)] = static handle => new global::TestLibrary.Cat.Kitten(handle),
    // ... 51 more, one per concrete wrapper with an internal IntPtr constructor
};
```

`FromHandle<T>`'s `Activator` fallthrough (`CirMarshalRenderer.kt:217-219`) becomes
`return Materialize<T>(handle);`, and the generic-function return path
(`CirFunctionTranslator.kt:845-847`) becomes `return NugetMarshal.Materialize<T>(result);`.

**Population mechanism: a static field initializer suffices; no `[ModuleInitializer]`, no lazy
guard.** The dictionary is only ever read through static members of `NugetMarshal` itself, and the
CLR runs the type initializer before the first access to any static field of the type (ECMA-335
`beforefieldinit` semantics; the spec claim is inferred, but the end-to-end behaviour, first call to
`FromHandle<T>` observing a populated dictionary, was **verified by the spike above**: output
`materialised: 42` from a `FromHandle<Root.Sub.Pet>` call with no explicit initialization step).
There is no cross-assembly or cross-file ordering concern: the registry, the wrappers and every
call site live in the one generated file.

**The per-namespace question, resolved:** the brief's premise that `NugetMarshal` is generated per
namespace is wrong. Verified: `CirTranslator.kt:424-474` adds one `CirMarshalHelper` to the root
namespace per generated file, and every wrapper namespace nests under the root
(`CirTypeMapping.kt:156-176`), so a single registry sees, and can name via `global::`, every
wrapper in the assembly, whose internal constructors are callable because registry and wrappers
share the assembly.

### Registration set

Walk the fully built `namespaces: List<CirNamespace>` and register:

- every `CirClass` with `hasInternalHandleConstructor == true` and `isAbstract == false`, in every
  namespace: ordinary classes, data classes, objects' companion-hosting classes where applicable,
  and the ADR-040 interface backing wrappers (`sealed class Pet : IPet`);
- every `CirSealedSubclass`, registered as its nested type name `Base.Sub` (each has
  `internal Sub(IntPtr) : base(handle)`, verified `CirSealedRenderer.kt:17`); the abstract sealed
  base gets no entry (not constructible today either: `Activator` on an abstract type throws).

No entries for: `CirEnum` (no handle constructor; `FromHandle<T>` has no enum branch today, the
known ROADMAP:178 gap, and a registry miss now throws a *clearer* exception for it),
`CirValueClass` (record structs round-trip as their underlying, `ForwardCirCollectionComponents.kt:99`),
`CirObject` (rendered without `_handle`), interfaces (interface returns route through the dedicated
ADR-040 backing-wrapper path, never `Activator`), and **open generic wrappers** (`CirGenericClass`,
`KotlinFunc<...>`): a `Dictionary<Type, ...>` needs closed types, see Consequences.

Additionally, register every **closed generic-wrapper instantiation that appears in a planned
signature** (e.g. a Kotlin function type returning `Crate<Int>` puts `FromHandle<Crate<int>>` on a
`KotlinFunc` invoke path): the translator sees these instantiations at emit time and each becomes
one ordinary static entry, `[typeof(global::Ns.Crate<int>)] = static h => new global::Ns.Crate<int>(h)`.
Statically written closed instantiations are AOT-fine (the compiler instantiates them).

### The threading seam

`CirTranslator.translate`, at the exact point `CirMarshalHelper` is constructed
(`CirTranslator.kt:424-432`): the `namespaces` list already contains every declaration paired with
its namespace, *before* the helpers are prepended. Compute
`factories: List<CirFactoryEntry>` (`qualifiedTypeName: String`) there by filtering the walk above,
and add it as a new field on `CirMarshalHelper` (`CirModel.kt:146`), which `renderMarshalHelper`
(`CirMarshalRenderer.kt`) renders as the dictionary initializer. No new pipeline stage, no second
pass.

### AOT-safety of the design itself

- Dictionary keys are `typeof(ConcreteType)` over statically named types; values are lambdas
  containing direct constructor calls. Both are statically reachable code, so the trimmer keeps the
  constructors and the AOT compiler pre-compiles them. Inferred from the .NET trimming/NativeAOT
  model (static reachability is precisely what these tools honour); the JIT half is spike-verified.
- `typeof(T)` inside a shared generic instantiation returns the exact runtime type under AOT;
  dictionary lookup by it is ordinary code. Inferred.
- The design introduces no `GetField`, no `Activator`, no `MakeGenericType`/`MakeGenericMethod`, no
  generic virtual methods (`Materialize<T>` is a static non-virtual generic; `INugetHandle.Handle`
  is a non-generic instance property).
- **What cannot be proven here:** that the resulting output actually publishes and runs under
  `PublishAot=true` (or on a Catalyst device). No AOT lane exists in this repository and none was
  run for this ADR; every "works under AOT" statement above is inferred from the documented AOT
  model, not verified. The AOT publish smoke test is ADR-038 step 4 and stays a separate roadmap
  item; this ADR only removes the mechanism those toolchains are documented to break on.

## Consequences

- One registry line per concrete wrapper plus one `INugetHandle` implementation line per
  handle-declaring class; generated-file growth is linear and internal-only. The consumer-visible
  surface is unchanged: `INugetHandle` is internal, the implementation is explicit, `Factories`/
  `Materialize` are internal, and no public signature moves.
- **Behaviour change on the miss path:** `Activator`'s accidental support for *consumer-invented*
  closed generic instantiations goes away. Today `FromHandle<Box<Box<int>>>` (a nesting no Kotlin
  signature produced) happens to work via reflection; after this it throws the named
  `NotSupportedException` unless the instantiation appeared in a planned signature. Exceptions on
  already-broken paths (enum elements, interface element types) change type from
  `MissingMethodException` to the clearer named `NotSupportedException`.
- Existing IntegrationTests suites (`GenericTests`, `ListTests`, `FlowTests`, `StateFlowTests`,
  `ValueClassCollectionTests`, ...) are the behavioural regression proof: identical results under
  JIT are required. New Tier 1 golden tests pin the mechanism: generated C# contains `Factories`
  and `INugetHandle` and contains **no** `Activator.CreateInstance` and no `GetField("_handle"`.
- LIMITATIONS item 2 of the PeopleInSpace integration (callback/delegate AOT work) depends on this
  landing first but is a separate ADR; also out of scope here: `[LibraryImport]` (ADR-038 step 3),
  the AOT CI lane (ADR-038 step 4), and reverse-direction shims (already reflection-free at their
  dispatch sites).
- ADR-038 stays Deferred but its step 2 is designed by this ADR, and its Alternative 3 narrows:
  `CSharpProfile.genericDispatch` is dropped from the eventual profile; only the interop-attribute
  dialect remains a profile choice.

### Breaking changes

None to the public API. The generated internals change shape, so a consumer shipping a hand-patched
generated file would need to regenerate, which is already unsupported.

# ADR-040: Interface return type mapping — concrete handle-backed C# class per Kotlin interface

## Status

Accepted

### Implementation note (2026-07)

Sub-decision A shipped as `BridgeType.Interface(qualifiedName, csharpType, backingType)`, as recommended. Sub-decision B shipped as the shared reflective helper (`NugetMarshal.HandleOf(object)`), as recommended, and it does throw `NotSupportedException` for a C#-implemented `IPet`, pinned by `IntegrationTests/BidirectionalTests.Cat_Befriend_CSharpImplementedPet_ThrowsNotSupportedException`. Sub-decision C shipped reachability-driven, as recommended.

Declaration node 8 in Consequences ("New CIR declaration node + renderer... `CirInterfaceClass`") did not ship as written. The implementation instead adds an `isSealed` flag to the existing `CirClass` node, because `CirClass`'s property/method DllImport derivation, dispose rendering, and `ForwardAbiContract`/`ForwardAbiLegacyRoutes` extraction all already work unmodified for a sealed interface-backing class, whereas a new node type would have required re-deriving all three for functionally identical output. `CirInterface` (the plain `IFoo` projection) is unchanged.

## Context

### Revalidation (2026-07)

This ADR was written before ADR-062 (forward callable plan), ADR-063 (declaration-level export
scoping), ADR-064 (unsupported-declaration diagnostics) and ADR-066 (forward export reachability
closure). Revalidated against the current `main`; four things changed, and one of them changes the
whole framing:

1. **The "emits broken code" framing is wrong today.** An interface-typed return position is not
   emitted-and-broken; it is **silently dropped**, with no diagnostic at all. Verified with a Tier 1
   probe (command and output below). The gap is now *silence*, not breakage – same reclassification
   ADR-064 applied to cell 23.
2. **The work lands in `forward/`, not in `cir/CirClassTranslator.kt` + `cir/CirFunctionTranslator.kt`.**
   An interface-typed return is an ordinary synchronous callable, so per ADR-062 it must be planned
   once (`ForwardBridgeTypeClassifier` → `ForwardCallablePlanner`/`ForwardPropertyPlanner`) and both
   halves projected from that one plan (`ForwardKotlinPlanEmitter` /
   `ForwardCirPlanProjection` + `ForwardCirPropertyProjection`). The ordinary legacy CIR route has
   been deleted; `CirClassTranslator` now only keeps named specialized-protocol adapters. The
   original Consequences section is rewritten below.
3. **The parameter-side claim was wrong.** The plan route lowers a handle argument as direct field
   access `pet._handle` (`forward/ForwardCirPlanProjection.kt:457`), not "via the existing reflection
   path". `._handle` does not compile when the static parameter type is an interface, so the
   parameter side needs its own decision – added as an open sub-decision below.
4. **`CirInterfaceClass` is still needed, but only as a declaration node.** CIR declaration nodes
   (`CirClass`, `CirInterface`, `CirValueClass`, ...) are still hand-built; what plan projection
   replaced is their *members*. So a new `CirInterfaceClass` node is right, but its members must come
   from projected plans, not from a bespoke translator.

ADR-039 resolved C# → Kotlin interface bridging: a C# class implementing a Kotlin-projected interface
(`ICatEventListener`) can be passed to a Kotlin function via N flat function pointers, following the
`add*/remove*` subscription pair detection pattern.

This ADR covers the orthogonal Kotlin → C# direction: a Kotlin function or property whose **declared
return type is a Kotlin interface** (e.g., `fun createPet(): Pet`, `var friend: Pet?`) must produce an
`IPet`-valued result in C#. The Kotlin implementation behind the interface could be any concrete
class, an anonymous `object`, or a lambda via SAM conversion.

### This ADR was originally filed against the wrong ROADMAP line

This ADR was written against the ROADMAP line "Support implementing C# interfaces in Kotlin and
passing them back to C# consumers", then sitting in "Phase 7: Bidirectional support (C# → Kotlin)".
That line does **not** describe this ADR. Read with its three siblings, added together in `15fc360`
(2026-06-14), it is one step of a symmetric C# → Kotlin progression: research the reverse mechanism,
let Kotlin *call* a C#-defined interface, let Kotlin *implement* one and hand it back, then the
mirror. Its subject is an interface declared in **C#**.

This ADR's subject is an interface declared in **Kotlin**. The two are not variants of one feature:
when Kotlin owns the interface, C# can hold an opaque handle and dispatch back into Kotlin, which is
the entire mechanism below. When C# owns it, there is no Kotlin-owned handle to wrap at all.

The correction, applied after the feature shipped:

- The Kotlin-declared work this ADR specifies is now its own **Phase 3** item, beside "Map
  interfaces", where the rest of forward interface projection lives.
- The C#-declared line stays **open** in Phase 7, cross-referenced to Phase 13's
  "Implement a C#-defined interface in Kotlin and pass it back to C#", which is the same item
  sequenced after Phase 8's reverse pipeline. This ADR is a prerequisite to compose with there, not
  a partial delivery of it.

Recorded because the failure was silent and repeatable: this ADR quoted the mislabeled title and
redefined it in place, forward-looking sub-bullets were then back-filled under that line to match the
ADR, and the line was eventually ticked for work it never asked for. Every gate downstream of the ADR
measured against the ADR, so nothing caught it. An ADR that reinterprets a roadmap line instead of
rewording it leaves the roadmap asserting something untrue while code, tests and docs all agree with
each other.

### The concrete gap (re-established 2026-07, **verified**)

For each Kotlin interface `Foo` the generator still produces exactly one C# declaration:
`public interface IFoo : IDisposable` (`cir/CirClassTranslator.kt:1281` `translateInterface`,
rendered by `cir/CirClassRenderer.kt:87` `renderInterface`). No concrete C# class wraps an opaque
handle and implements `IFoo`, and no Kotlin interface-dispatch exports exist.

What actually happens today for an interface-typed position:

- `forward/ForwardBridgeTypeClassifier.kt:83-85` classifies **any** `ClassKind.INTERFACE` as
  `BridgeType.SpecializedProtocol("interface bridge <fqcn>")`, *before* the exported-object-handle
  membership test at `:98`. (So the old sentence about the `exportedTypes` guard is moot: interfaces
  are in `exportedObjectHandles` – `NugetProcessor.kt:340` – but classification never reaches that
  check for them.)
- For a **method**, `ForwardCallablePlanner.planOrSkip` turns that into
  `ForwardPlanSkipReason.CALLBACK_PROTOCOL` (`ForwardCallablePlanner.kt:1191`), whose
  `droppedFromCSharp = false` (`ForwardDiagnostic.kt:30`) marks it as "re-emitted by a named legacy
  route". For an interface *parameter* in an `add*/remove*` pair that is true (ADR-039). For an
  interface **return**, and for a non-paired interface **parameter**, it is false: nothing re-emits
  it, and because the reason is classified as a legacy deferral it never reaches
  `droppedCallables`, so **no diagnostic is emitted at all**.
- For a **property**, `ForwardPropertyPlanner.isPlannable` (`ForwardPropertyPlanner.kt:193`) returns
  `false` and the plan is simply `null`; `CirClassTranslator.kt:130-176` then drops the property
  ("Ordinary property types without a plan are skipped – no mapReturnType IntPtr fallthrough").
  Same for methods at `CirClassTranslator.kt:392-396`.

So the honest statement of the gap is: **an interface-typed return or parameter position disappears
from the generated C# API and from `CNameExports.kt`, silently, with a clean consumer build.** It is
the ADR-064 "silent drop" class of defect, not the ADR-053 "broken emit" class.

#### Verification spike (Tier 1 harness, real KSP2 run)

A temporary Tier 1 test (`Tier1Harness.run`, ADR-060) was run against this fixture and then deleted:

```kotlin
package tier1.probeiface

interface Pet {
  val name: String
  fun speak(): String
  fun legs(): Int
  fun touch()
}

class Cat(override val name: String) : Pet {
  var friend: Pet? = null
  override fun speak(): String = "Meow"
  override fun legs(): Int = 4
  override fun touch() {}
  fun befriend(pet: Pet) { friend = pet }
  fun findFriend(): Pet = friend ?: this
}
```

Real output (`./gradlew :nuget-processor:test --tests '*ZzProbeInterfaceReturnTest*' -i --rerun-tasks`):

- `kspExitCode: OK`, `kspErrors: []`, **`kspWarnings: []`** (empty – no diagnostic of any kind),
  `compileErrors: []`.
- `Interop.cs` contains `public class Cat : IPet` with `Name`, `Speak()`, `Legs()`, `Touch()`,
  `Dispose()` and `public interface IPet : IDisposable`. It contains **no** `Friend` property, **no**
  `Befriend`, **no** `FindFriend`.
- `CNameExports.kt` contains exactly `cat_create`, `cat_dispose`, `cat_get_name`, `cat_speak`,
  `cat_legs`, `cat_touch` and the shared error exports. There is **no** `cat_get_friend`,
  `cat_set_friend`, `cat_befriend` or `cat_findFriend`.

**Verified**, therefore: nullable interface-typed property, non-null interface-typed method return,
and interface-typed method parameter are all three silently absent today; the consumer build is
clean; nothing warns. No generator-level test covers interface classification at all
(`forward/ForwardBridgeTypeClassifierTest.kt` has no interface case).

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
underlying object was a `Cat` (whose `StableRef` was also created under the `Cat` type),
`handle.asStableRef<Pet>().get()` yields the object typed as `Pet` and dispatches polymorphically to
`Cat.speak()`.

**Verified by shipped, passing repo code** (not inferred): the sealed-class route already does
exactly this, in both directions across a hierarchy. In
`test-library/build/generated/ksp/macosArm64/macosArm64Main/.../CNameExports.kt`:

```kotlin
@CName("openBox")
public fun export_openBox(name: String, errorOut: COpaquePointer?): COpaquePointer? = try {
  StableRef.create(openBox(name)).asCPointer()      // static type Observation, runtime type Alive
...
public fun export_observation_alive_get_cat(handle: COpaquePointer): COpaquePointer =
  StableRef.create(handle.asStableRef<...Observation.Alive>().get().cat).asCPointer()  // read as the SUBtype
...
  val obj: ...Observation = handle.asStableRef<...Observation>().get()                 // read as the SUPERtype
```

`IntegrationTests/SealedClassTests.OpenBox_WhenAlive_ReturnsAlive` exercises that exact handle end to
end and passes. So `StableRef` is type-erased at the C boundary and `asStableRef<T>()` is an
unchecked reinterpretation of a real object reference: a handle created at a supertype static type
can be read back at a subtype and vice versa, and member calls dispatch on the runtime class. That is
precisely the mechanism the interface-dispatch exports depend on.

Corollary, **inferred** (no spike): because `asStableRef<T>()` performs no runtime type check, a
handle for the *wrong* Kotlin type passed to `pet_speak` is undefined behaviour rather than a clean
`ClassCastException`. Every interface-dispatch export is only ever called with a pointer the bridge
itself minted, so this is not reachable from the supported API, but it does mean the exports must not
be treated as a validating boundary.

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

### Disambiguation of the three neighbouring ROADMAP items (line numbers refreshed 2026-07)

- **"Generate Kotlin wrappers for C# interfaces" (done – ADR-039)**: C# implements a Kotlin-projected
  interface and passes it TO a Kotlin function via the `add*/remove*` subscription pair (N flat function
  pointers, stored callback lifetime). Direction: C# → Kotlin. Currently ROADMAP line ~139.
- **"Surface Kotlin-declared interfaces at return positions to C#" (this ADR)**: Kotlin declares the
  interface and returns an interface-typed value to C#, which holds an opaque handle and dispatches back
  into Kotlin. Currently ROADMAP line ~143 (with this ADR's sub-bullets). Direction: Kotlin → C#.
- **"Implement a C#-defined interface in Kotlin and pass it back to C#" (deferred, Phase 13)**: the
  interface is declared in **C#** and arrives through the reverse pipeline, so Kotlin sees a generated
  stub rather than a Kotlin-authored `interface` and there is no Kotlin-owned handle for C# to wrap.
  Needs ADR-039's flat function pointers pointed the other way plus a lifetime story for an object C#
  retains beyond the call. This is the item the old wording of line ~143 accidentally described.
- **"Support implementing Kotlin interfaces in C# and passing them to Kotlin producers" (deferred, not
  this ADR)**: C# passes a C#-implemented interface object to a Kotlin function taking an interface
  parameter WITHOUT the `add*/remove*` naming convention. Currently ROADMAP lines ~145-150; it needs a
  mechanism beyond ADR-039's pair detection (non-Unit returns back through a function pointer, property
  getters as pointers, runtime dispatch between a Kotlin-backed wrapper and a C#-implemented object).

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

### 2. Named skip instead of support (the minimum honest change)

Leave interface-typed return values unsupported, but stop dropping them silently: give the classifier
a distinct signal for "interface in a *return* position" (as opposed to ADR-039's `add*/remove*`
parameter position, which really does have a legacy route) so the planner can record a genuine drop
and `ForwardDiagnosticSink` can emit `SKIPPED_UNSUPPORTED_RETURN` naming the member.

**Pros:**
- No new generated types; no new Kotlin exports.
- Fixes the *actual* defect verified above – silence – for a fraction of the work, and is a strict
  prerequisite of Alternative 1 anyway (both need the classifier to distinguish an interface return
  from an ADR-039 interface parameter).

**Cons:**
- A common, idiomatic Kotlin API pattern (interface-typed return) stays unmapped. Any Kotlin library
  that uses interfaces as return types (factory, repository, provider patterns) still loses those
  members from its C# API.

This is a viable *first commit* under Alternative 1, not a competing end state.

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

## Sub-decisions added by the 2026-07 revalidation

The C#-shape decision above (Alternative 1) survives. Three mechanism decisions it did not cover are
now load-bearing, because the plan model of ADR-062 did not exist when this ADR was written. **A–C
below are open: they need a human decision.**

### A. How an interface enters the forward plan model

The plan model is closed over `BridgeType` (`forward/ForwardMarshallingModel.kt:7-89`), and
`ForwardCallablePlanValidator.validateType` (`:369`) hard-`error()`s on any `SpecializedProtocol`, so
an interface cannot reach a plan as-is.

1. **New `BridgeType.Interface(qualifiedName, csharpType, backingCsharpType)` (recommended).**
   Wire-identical to `ObjectHandle` everywhere (POINTER, `STABLE_REF_TO_HANDLE`/`HANDLE_TO_STABLE_REF`,
   `OWNED_HANDLE` + `DISPOSE_STABLE_REF` for a result, `STABLE_REF` helper), differing only in the two
   places where the *public C# spelling* and the *construction expression* diverge (`IPet` vs
   `new Pet(...)`). Keeps `ObjectHandle`'s existing invariant "csharpType is both the declared type and
   the constructor" true, which the projections rely on today
   (`ForwardCirPlanProjection.kt:535-541`, `:566-576`).
2. **Reuse `BridgeType.ObjectHandle` with an extra `constructionType` field.** Fewer `when` branches
   to add. But every existing site that assumes `csharpType` is constructible keeps compiling while
   being wrong for interfaces – exactly the silent-wrong-output failure mode CLAUDE.md warns about.
3. **Keep interfaces as `SpecializedProtocol` and give them a legacy route.** Contradicts ADR-062
   (ordinary synchronous callables go through the plan) and would need a second, parallel emitter for
   the Kotlin half; also loses ADR-055's plan/ABI contract check for these members.

### B. Handle extraction for an interface-typed *parameter* (`befriend(pet: Pet)`)

The original ADR text ("extracts `_handle` via the existing reflection path (ADR-015/010)") is
**wrong as a statement about today's code**. Verified in source: the plan route lowers an object-handle
argument as a direct field read, `${parameter.name}._handle`
(`forward/ForwardCirPlanProjection.kt:457`, nullable variant `:461`;
`ForwardCirPropertyProjection.kt:288` for setters). Reflection on `_handle` exists **only** inside the
generic helpers (`NugetMarshal.CreateBox`/`CreateList` and the `typeof(T)` sites in
`cir/CirMarshalRenderer.kt:281,307`, `cir/CirFunctionRenderer.kt:49,170`,
`cir/CirClassRenderer.kt:47`). `pet._handle` does not compile when the static type is `IPet`.

1. **Reflect via one shared helper (recommended).** Add `NugetMarshal.HandleOf(object value)` doing
   the same `GetField("_handle", Instance | NonPublic | Public)` lookup the `CreateBox` path already
   ships, throwing `NotSupportedException` naming the type and pointing at ROADMAP line 145+ when the
   field is absent (i.e. a C#-implemented `IPet`). Pros: no change to the generated `IPet`, so
   `IntegrationTests/BidirectionalTests.Dog : IPet` keeps compiling; one precedented mechanism; the
   unsupported case fails loudly. Cons: reflection per call at an ordinary parameter position (the
   existing reflective sites are all generic/marshal helpers).
2. **Add an `internal` handle member to the generated `IFoo`.** Two shapes, both spiked:
   - *Abstract* `internal IntPtr NugetHandle { get; }` – **breaking, verified**. `dotnet build` on a
     net8.0 classlib containing an existing same-assembly implementer that does not implement it:
     `error CS0535: 'Dog' does not implement interface member 'IPet.NugetHandle'`. `Interop.cs` is
     compiled *into the consumer assembly* (ADR-001), so every consumer-written `IFoo` implementation
     is same-assembly and breaks.
   - *Default* `internal IntPtr NugetHandle => IntPtr.Zero;` – **compiles, verified** (same spike,
     `Build succeeded`, `Dog` left untouched). Cheap, non-reflective `pet.NugetHandle` at the call
     site, but it silently hands Kotlin `IntPtr.Zero` for a C#-implemented object unless the generated
     code also guards, and it puts a bridge implementation detail in the public interface's metadata.
3. **Pattern-match to the known backing types** (`pet switch { Pet p => p._handle, Cat c => c._handle, ... }`).
   No reflection, but the generator must enumerate every C# type that could implement `IFoo`, which it
   cannot (consumers write their own).

Also open: **whether the parameter side is in this feature's v1 at all.** It is a separable
sub-feature from the return side (both are silently dropped today, for different reasons), and
ROADMAP line 145+ owns the C#-implemented case regardless.

### C. Which interfaces get a backing class and dispatch exports

Today `IFoo` is emitted for every public interface in ADR-063 scope, plus any admitted by ADR-066's
closure (`NugetProcessor.kt:306`). The original Scope section says to generate the *backing class*
proactively for every interface too.

1. **Reachability-driven (recommended).** Generate `sealed class Foo : IFoo` and the `foo_*` dispatch
   exports only for interfaces that actually appear in a planned return position (method result,
   property type; later collection element). Rationale: ADR-066 already made "the export set is a
   reachability closure" the house rule; every dispatch export is permanent ABI surface that ADR-055's
   contract check walks; `IFoo` itself is still emitted unconditionally, so no C# API disappears.
   Implementation is a second pass over the plan catalog collecting
   `plan.publicSignature.result`/`ForwardPropertyPlan.type` interface types, in the same spirit as
   `CollectionHelperTracker`.
2. **Proactive for every public interface.** Simpler (no ordering dependency between planning and
   interface-export generation), and matches how `IFoo` itself is generated. Cost: N × (members + 1)
   exports per interface nobody returns, including marker interfaces, and every one of them widens the
   ABI contract surface.

## Decision

Use **Alternative 1: concrete handle-backed sealed class per Kotlin interface**, with Alternative 2
(the named skip) as the first commit rather than a competing end state. Sub-decisions A, B and C above
are **still open** and gate implementation; A is recommended as A.1, B as B.1, C as C.1.

For each Kotlin interface `Foo` (see sub-decision C for *which* interfaces), the generator produces two
C# declarations:
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

Note (**verified**, `forward/ForwardKotlinPlanEmitter.kt:95`, `:325`): the Kotlin half of a *return*
position needs no new emitter logic. A handle result is already emitted as
`StableRef.create(<invocation>).asCPointer()` from the `BridgeType.ObjectHandle` branch and is entirely
type-agnostic; adding the new interface `BridgeType` to that same branch is the whole change. What is
genuinely new on the Kotlin side is only the **interface-dispatch export set** above, which has no
current producer (there is no `exports/InterfaceExports.kt`; `exports/InterfaceBridgeExports.kt` is
ADR-039's `add*/remove*` route and is unrelated).

### Handling interface-typed properties/methods on classes (example: `var friend: Pet?` on `Cat`)

The `befriend` Kotlin method (`fun befriend(pet: Pet)`) takes a `Pet` parameter, so the C# signature is
`void Befriend(IPet pet)`. **How the handle is extracted from an `IPet` is open – see sub-decision B.**
The shape below assumes B.1 (the shared reflective helper); it is *not* what the code does today for
class-typed parameters, which is the direct `pet._handle` field read:

```csharp
// Generated on Cat – B.1 shape
[DllImport("sample", EntryPoint = "cat_befriend")]
private static extern void Native_Befriend(IntPtr handle, IntPtr pet, out IntPtr error);

public void Befriend(IPet pet)
{
    IntPtr petHandle = NugetMarshal.HandleOf(pet);   // new shared helper, throws for a C#-implemented IPet
    Native_Befriend(_handle, petHandle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
}
```

Note: `befriend(pet: Pet)` is NOT an `add*/remove*` pair — it stores the `Pet` but has no
`removeFriend`. This makes it a regular method whose parameter type is a Kotlin interface, which
`ForwardCallablePlanner` currently drops silently (verified above). Only a Kotlin-backed `IPet` (one of
the generated wrapper classes, which do carry `_handle`) can be passed in v1. Passing a
C#-implemented `IPet` (like `Dog` from `BidirectionalTests.cs`) is the **ROADMAP line 145+** item
(general non-subscription interface parameters) and stays deferred – it needs N function pointers, not
a handle.

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

### Type resolution rule (2026-07: real seams, replacing the old translator table)

The rule itself is unchanged – public C# type `I$simpleName`, construction expression
`new $simpleName(handle)`, nullable guard on `IntPtr.Zero` – but it is now expressed once in the plan
and projected, never re-derived per renderer. Detection moves out of the translators entirely: it
happens once in `ForwardBridgeTypeClassifier.classifyNonNullable` at the existing
`classKind == ClassKind.INTERFACE` branch (`forward/ForwardBridgeTypeClassifier.kt:83-85`), which today
returns `SpecializedProtocol` and must instead return the new interface `BridgeType` (sub-decision A).

| Position | Today (**verified**) | With this ADR |
|----------|----------------------|---------------|
| Classification | `SpecializedProtocol("interface bridge …")` | `BridgeType.Interface(fqcn, "IPet", "Pet")` |
| Method result | `CALLBACK_PROTOCOL` skip, silent, member absent | `POINTER` result, `OWNED_HANDLE`, `STABLE_REF_TO_HANDLE`, `DISPOSE_STABLE_REF` cleanup |
| Property | `isPlannable` = false → plan `null` → member absent | plannable, `POINTER` wire type |
| C# result projection | n/a | `returnType = "IPet"`, body `return new Pet(nativeResult);` |
| C# nullable result | n/a | `return nativeResult == IntPtr.Zero ? null : new Pet(nativeResult);` |
| Kotlin export half | n/a | unchanged `StableRef.create(...).asCPointer()` branch |
| Interface parameter | `CALLBACK_PROTOCOL` skip, silent | sub-decision B |

### Fixture Kotlin API (`test-library/`, **not** `sample-library`)

The original text said `sample-library`. There is no such module: `test-library/` is the forward
fixture (packed by `scripts/verify.sh`, consumed by `IntegrationTests/`), and `sample/` is the NYTimes
sample app. Corrected.

The existing surface in `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/`
is `Pet.kt` (`interface Pet { val name: String; fun speak(): String; fun greet(): String = ... }`),
`Animal.kt` (`abstract class Animal(override val name: String) : Pet`) and `Cat.kt`
(`class Cat(...) : Animal(name)`), plus `CatEventListener.kt` (ADR-039's `add*/remove*` interface, which
must keep working unchanged).

Per the feature-design rule that the fixture must cross **every** mechanism, not the fewest types:

```kotlin
// Pet.kt – the interface whose members become dispatch exports
interface Pet {
  val name: String          // String getter: needs UTF8 marshalling
  val legs: Int             // primitive getter: no conversion at all
  val nickname: String?      // nullable String getter: IntPtr.Zero -> null
  fun speak(): String        // String-returning method
  fun greet(): String = "Hi, I'm $name"   // DEFAULT method: dispatch must reach the override
  fun fetch(item: String): String          // method with a String *input* on the dispatch export
  fun nap()                                // Unit-returning method (void export)
}

// Cat.kt – the interface-typed return positions
var friend: Pet? = null                    // nullable interface-typed property (get AND set)
fun befriend(pet: Pet) { friend = pet }    // interface-typed PARAMETER (sub-decision B)
fun closestFriend(): Pet = friend ?: this  // NON-NULL interface-typed method return
fun maybeFriend(): Pet? = friend           // nullable interface-typed method return
val self: Pet get() = this                 // NON-NULL interface-typed property

// Pet.kt (top level) – an anonymous object behind the interface: the strongest polymorphism proof,
// because its runtime class has no generated C# wrapper at all, so the consumer can only reach it
// through pet_* dispatch.
fun strayPet(): Pet = object : Pet {
  override val name: String = "Stray"
  override val legs: Int = 3
  override val nickname: String? = null
  override fun speak(): String = "Mrrp"
  override fun fetch(item: String): String = "drops the $item"
  override fun nap() = Unit
}
```

Why each piece is not trivially satisfiable:

- `name` (String) vs `legs` (Int) – one getter needs a conversion, one does not; a marshalling bug in
  one is invisible in the other.
- `nickname: String?` – the nullable-String branch of the dispatch export.
- `nap()` – a `void` dispatch export (no result transfer at all).
- `fetch(item)` – an *input* on a dispatch export, so the export is not uniformly `(handle) -> value`.
- `greet()` default + `Animal.greet()` override – proves the export calls the interface member and
  lands on the override, i.e. real virtual dispatch and not a re-implementation.
- `friend` nullable **and** `self`/`closestFriend()` non-null, at **both** property and method
  positions – four distinct projection branches.
- `Cat` behind `Pet` proves the upcast when a wrapper exists; `strayPet()`'s anonymous object proves it
  when **no** wrapper exists (this is the case that fails if the backing class ever tries to resolve
  the concrete Kotlin type).

**Ripple to flag before implementing:** adding abstract members to `Pet` regenerates `IPet` with
`Legs`, `Nickname`, `Fetch`, `Nap`, which breaks `IntegrationTests/BidirectionalTests.cs`'s
`private class Dog : IPet` (CS0535 – same error class as the sub-decision B spike). `Dog` must be
updated in the same change. `AbstractClassTests`, `InterfaceTests` and `GenericConstraintTests` also
reference `IPet` but only assign to it, so they are unaffected.

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

// The seam that fails if the backing class ever tries to resolve the concrete Kotlin type:
// the runtime object is an anonymous `object : Pet` with no generated C# wrapper at all.
[Fact]
public void StrayPet_AnonymousKotlinObject_DispatchesThroughIPet()
{
    using IPet stray = PetKt.strayPet();
    Assert.Equal("Stray", stray.Name);
    Assert.Equal(3, stray.Legs);
    Assert.Null(stray.Nickname);
    Assert.Equal("Mrrp", stray.Speak());
    Assert.Equal("Hi, I'm Stray", stray.Greet());   // interface default implementation
    Assert.Equal("drops the ball", stray.Fetch("ball"));
    stray.Nap();                                    // void dispatch export
}

// Non-null return positions, method and property.
[Fact]
public void Cat_ClosestFriend_AndSelf_AreNonNullIPet()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet closest = oreo.ClosestFriend();
    Assert.Equal("Oreo", closest.Name);
    using IPet self = oreo.Self;
    Assert.Equal("Oreo", self.Name);
}
```

## Consequences

### Where the work lands (rewritten 2026-07 – the original section named files that no longer own this)

All file:line references below were read on `main` at revalidation time.

**1. Classification (one site).**
`forward/ForwardBridgeTypeClassifier.kt:83-85` – the `ClassKind.INTERFACE` branch returns the new
`BridgeType` instead of `SpecializedProtocol`. Must be **narrower** than the current branch, which also
catches ADR-039's `add*/remove*` interface *parameters*; those must keep their `CALLBACK_PROTOCOL`
route, so either the classifier stays position-agnostic and the planner decides (preferred – the
`findInterfaceBridgePairs` exclusion already runs in `ForwardCallablePlanner.classEntries`
(`:379-384`) before `planOrSkip`), or the classifier gains position context (it currently has none).

**2. Marshalling model + validator (`forward/ForwardMarshallingModel.kt`).**
New `BridgeType` member (`:7-89`); `ForwardCallablePlanValidator.validateType` allow-list (`:371-373`);
`requiredConversion` (`:426-430`, alongside `ObjectHandle`); nothing else, since the wire type,
ownership, cleanup and helper requirement are all identical to `ObjectHandle`.

**3. Callable planner (`forward/ForwardCallablePlanner.kt`).**
`shapeOrNull` (`:972` `handleResultShape`), `nullableResultShape` (`:1008`),
`nativeInputParameters` (`:844` and the nullable variant `:886`) for the parameter side,
`isBridgeableComponent` (`:1141`), `skipReason` (`:1182`), `inputSkipReason` (`:1205`), `wireType`
(`:1249`). Plus a new entry point so an **interface's own members** get plans at all: interfaces are
not in the `classes` list the planner receives (`NugetProcessor.kt:358`), so a new
`interfaceEntries(iface)` is needed, shaped exactly like `classEntries` (`:361`) but with
`ForwardReceiver.Handle(BridgeType.ObjectHandle(ifaceFqcn))` as the receiver – the receiver type must be
`ObjectHandle` even for an interface, because a receiver is only ever lowered via `asStableRef`, never
constructed.

**4. Property planner (`forward/ForwardPropertyPlanner.kt`).**
`isPlannable` (`:193`), `wireType` (`:203`), `conversion` (`:230`). Also needed for the interface's own
properties if they are planned through this planner rather than `interfaceEntries`.

**5. C# projection (`forward/ForwardCirPlanProjection.kt`, `forward/ForwardCirPropertyProjection.kt`).**
`resultProjection`'s `ObjectHandle` branch (`ForwardCirPlanProjection.kt:535-541`) and its nullable
sibling (`:566-576`) gain an interface branch differing only in that `returnType` is the interface
spelling while the construction expression names the backing class; `callArgument` (`:457`, `:461`) for
the parameter side (sub-decision B); `csharpType()` (`:798`). Property side:
`ForwardCirPropertyProjection.kt:170`, `:178`, `:183`, `:281`, `:288`, `:296`, `:316`.

**6. Kotlin emission (`forward/ForwardKotlinPlanEmitter.kt`).**
Add the new type to the existing `ObjectHandle` branches – result (`:95`, `:325`), nullable result
(`:429`), parameter/receiver lowering (`:374`, `:388`, `:402`, `:507`, `:544`, `:652`, `:671`). No new
emission *shape*: `StableRef.create(x).asCPointer()` and `handle.asStableRef<Fqcn>().get()` are already
what an interface needs, and `asStableRef<Pet>()` with an interface type argument compiles and is
runtime-correct (see the verified sealed-hierarchy evidence in Context).

**7. New Kotlin export producer.**
`exports/InterfaceExports.kt` (new, parallel to `exports/ClassExports.kt`): per-property getter,
per-method, and `{prefix}_dispose`. Wired in `NugetProcessor.generateCNameWrappers`. Note
`exports/InterfaceBridgeExports.kt` already exists and is **ADR-039's unrelated route** – do not extend
it. Under ADR-062 the *bodies* should come from `ForwardKotlinPlanEmitter` over the plans from step 3,
not be hand-rolled in the new file.

**8. New CIR declaration node + renderer.**
`CirInterfaceClass` in `cir/CirModel.kt` (next to `CirInterface`, `:20`), a `renderInterfaceClass` in
`cir/CirClassRenderer.kt` (next to `renderInterface`, `:87`), and a branch in `CirRenderer`'s
exhaustive `when` (`cir/CirRenderer.kt:23-49`). This part of the original ADR still stands: plan
projection replaced CIR *members*, not CIR *declaration nodes*, which are all still hand-built. Its
`properties`/`methods` must be `ForwardCirPropertyProjection.classProperty` /
`ForwardCirPlanProjection.classMethod` output, so the ABI is stated once.
`ForwardAbiLegacyRoutes.add(declaration)` (`ForwardAbiLegacyRoutes.kt:61-110`) has an exhaustive `when`
over `CirDeclaration` and needs the new node listed (as a no-op, like `CirInterface`).

**9. ABI contract (`ForwardAbiContract`).**
`ForwardAbiContract.assertMatches` / `assertMatchesPlan` (`NugetProcessor.kt:381-395`) compare the C#
DllImports, the Kotlin `@CName` exports, and the plans. Any interface export emitted on only one side
fails the build – which is the desired guard, and also the reason sub-decision C matters: whichever set
of interfaces gets exports must be computed identically on both sides.

**10. Diagnostics (`forward/ForwardDiagnostic.kt`).**
Whatever stays out of scope (a C#-implemented `IPet` parameter; an interface with type parameters; an
interface member returning another interface) must emit a named `SKIPPED_*` kind rather than reverting
to today's silence. `CALLBACK_PROTOCOL`'s `droppedFromCSharp = false` is only honest for the
`add*/remove*` pair; every other interface position needs a reason with `droppedFromCSharp = true`.

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

`cat.befriend(pet: Pet)` takes a `Pet` parameter. In v1, only Kotlin-backed `IPet` objects (the
generated wrapper classes, which carry `_handle`) can be passed; how the handle is obtained is
sub-decision B, and the ADR's original "via reflection" wording did **not** describe the shipped code.
Passing a C#-implemented `IPet` (like `Dog`) is the deferred ROADMAP "implementing Kotlin interfaces in
C#" item (N function pointers for a non-subscription parameter).

### Breaking changes

- Generated C# **API**: additive only. `IPet` keeps its shape (unless sub-decision B.2 is chosen, which
  is a verified breaking change for consumer-written implementers – CS0535); the backing `Pet` class is
  new; members that were silently absent start appearing.
- Generated **ABI**: new `pet_*` exports. Because ADR-055's contract check compares the plan, the C#
  imports and the Kotlin exports, a partially-implemented change fails the build rather than shipping a
  skewed pair. A shim/native version skew at runtime is already covered by ADR-054.
- **This repo's fixture**: `IntegrationTests/BidirectionalTests.cs`'s `Dog : IPet` must gain the new
  `Pet` members (see the fixture section).
- Name collision, **not currently handled**: the backing class is named after the Kotlin interface
  (`Pet`), so two packages/namespaces or a reverse-generated type could collide with an existing C#
  `Pet`. Kotlin itself forbids `interface Pet` and `class Pet` in one package, so the common case is
  safe. Recommendation: reuse the existing fail-fast style (`ERROR_CSHARP_SIGNATURE_COLLISION`,
  `cir/CirClassTranslator.kt:100-115`) rather than silently mangling.

### Scope

**In v1:**
- Interface-typed **return** positions: class method result, class property (get and set), both
  nullable and non-null – the four projection branches the fixture pins.
- The interface's own members: `String`, primitive, nullable `String`, and `Unit` results, plus a
  `String` parameter, plus a defaulted member reaching a subclass override.
- The backing `sealed class Foo : IFoo` plus `foo_*` dispatch and `foo_dispose` exports, for the
  interface set chosen by **sub-decision C** (recommended: reachability-driven, i.e. only interfaces
  that appear in a planned return position – the original "proactively for every public interface" is
  no longer the recommendation now that ADR-066 exists).
- A named `SKIPPED_*` diagnostic for every interface position that stays out of scope, replacing
  today's verified silence.

**Deferred:**
- The interface-typed **parameter** side (`befriend(pet: Pet)`) if sub-decision B is not settled in this
  feature – it is separable, and a named skip is an acceptable interim end state.
- Interface members whose return type is another Kotlin interface or a class handle (chained type
  resolution; widens the matrix).
- Interfaces with generic type parameters (`Readable<out T>` / `Writable<in T>` already exist in
  `test-library/.../cat/Variance.kt`) – compose with ADR-010/015/016.
- Suspend interface members (ADR-019), `Flow`/`StateFlow`-valued interface members (ADR-026/065),
  collections of interfaces (`List<Pet>`).
- Passing a **C#-implemented** `IPet` to a Kotlin interface parameter – ROADMAP line 145+ (N function
  pointers), not this ADR.
- Object identity: two reads of `cat.friend` produce two distinct C# wrappers over the same Kotlin
  object (ADR-005); unchanged here.
- `@NugetInterfaceReturn` opt-out annotation – moot if sub-decision C.1 (reachability-driven) is chosen.

### Load-bearing claims that remain inferred

State plainly, so no implementer takes them as fact:

1. **Inferred (not spiked):** that a `sealed class Pet : IPet` disposing via a `pet_dispose` export
   (`asStableRef<Pet>().dispose()`) is safe when the same underlying Kotlin object also has a live
   `Cat` wrapper disposing via `cat_dispose`. Both dispose the *same* `StableRef`, so a
   double-`dispose()` is the risk. The existing per-wrapper `Interlocked.Exchange(ref _handle, ...)`
   guard (verified in generated output) only protects *within* one wrapper. If this is wrong, the
   symptom is a crash or heap corruption on the second dispose, not a compile error. **Spike this before
   implementing** by returning the same Kotlin object twice through two different wrapper types and
   disposing both.
2. **Inferred:** that KSP exposes an interface's defaulted members and their overrides such that the
   dispatch export can call the interface member and land on the override. The equivalent
   already-shipped fact is only that `IPet.Greet()` resolves through a `Cat` *class* export
   (`InterfaceTests.IPet_Greet_UsesDefaultImplementation`), which is a different mechanism.
3. **Inferred:** that adding a new `CirDeclaration` subtype does not disturb `ForwardAbiContract`'s
   C#-side signature extraction (`ForwardAbiContract.csharp(cirFile)`); it was only read, not run,
   against a new node type.

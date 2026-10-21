# ADR-222: Forward, a C#-implemented generic interface crosses to Kotlin: `class Diary : IKeepsake<string>` at a `Keepsake<String>` parameter

## Status
Accepted

Amends [ADR-084](084-csharp-implemented-interfaces.md) (Scope: "Deferred: ... generic interfaces";
the 2026-10-07 refusal list's "a type parameter on the interface"; the `var` setter slot is
ADR-084's own 2026-10-22 amendment) and [ADR-208](208-generic-instantiation-member-positions.md) (its table row "a
generic interface reference (`Shelf<String>`) keeps its `"generic declaration"` skip", for input
positions only). Closes the ROADMAP Phase 7 line left by
[ADR-218](218-type-parameter-property-setter.md).

## Context

ROADMAP Phase 7: "A generic interface (`IHolder<T>`) implemented in C# and passed to Kotlin is not
bridged: ADR-084 bridges no generic interface, so the C# side of an interface `var item: T` is out
of reach (ADR-218)."

The line names one gap; the source shows three, stacked:

1. **No Kotlin function can even be declared to take one.** A generic interface reference
   (`Keepsake<String>`, `Keepsake<T>`) classifies as
   `SpecializedProtocol("generic declaration ...")` (`forward/ForwardBridgeTypeClassifier.kt:441-443`),
   which the planner maps to `ForwardPlanSkipReason.GENERIC` (`forward/ForwardCallablePlanner.kt:5983`).
   ADR-208's table keeps that skip on purpose ("spelling a generic interface is a separate
   feature"). So `fun inspect(keepsake: Keepsake<String>)` has no C# member today, for a
   Kotlin-backed `Locket<string>` as much as for a C# class. **Verified by reading**; the skip is named (`SKIPPED_UNSUPPORTED_INPUT`, reason `GENERIC`, pinned by
   `Tier1UnroutedPositionsTest` before this ADR moved its witness).
2. **The bridge planner refuses the interface outright.**
   `ForwardInterfaceBridgePlanner.planOrRefuse` returns `Refused` for any `typeParameters`
   (`forward/ForwardInterfaceBridgePlanner.kt:250-261`) and `bridgeType` has no `TypeParameter` arm
   (`:470-525`). **Verified by reading.**
3. **A `var` refuses the whole interface, generic or not**
   (`forward/ForwardInterfaceBridgePlanner.kt:340-345`, "the bridge carries a property through a
   getter slot only"). `interface Keepsake<T> { var item: T }` is refused twice over. **Verified by
   reading.** Lifted for every interface by the ADR-084 2026-10-22 amendment.

What already exists and is reused (all **verified by reading**):

- The C# interface: `public interface IKeepsake<T> : IDisposable { T Item { get; set; } }` (ADR-218;
  the `: IDisposable` spelling seen in a previously built `Interop.cs`, which is build output, not
  proof of the current source).
- The ADR-084 factory: one `@CName` export per interface building `object : Iface, NugetCSharpBridge`
  over flat `(fnPtr, ctx)` slot pairs, a `createCleaner` release, an identity token
  (`exports/InterfaceBridgeFactoryExports.kt:35-70`); the C# `{Iface}BridgeState` whose slots
  dispatch through the shared `NugetThunks` shells with `RegisterCtx` keys
  (`cir/CirBridgeRenderer.kt:71-198`).
- ADR-215's `T` crossing for a lambda: Kotlin to C# is `NugetHandles.retain(arg)` read by
  `NugetMarshal.FromHandle<T>` (which disposes); C# to Kotlin is `NugetMarshal.WrapOwned<T>`
  (`cir/CirMarshalRenderer.kt:466-480`) read by `forwardBoundedRead` and released
  (`forward/ForwardKotlinPlanEmitter.kt:1398`). A bridge slot is a lambda slot with a different
  owner, so a `T` slot is that wire, not a new one.

Other targets: ObjC export maps a Kotlin generic interface to a lightweight-generic `@protocol`
whose type arguments are erased at runtime, and a Swift class conforming to it is dispatched by
`objc_msgSend` with `id` values, the same erasure this design uses on the Kotlin side
(inferred from the ObjC interop docs, not re-checked here). Kotlin/JVM and JS share a heap and
need no bridge. C# consumers expect `class Diary : IKeepsake<string>` to be accepted wherever the
generated API says `IKeepsake<string>`, exactly as `class Dog : IPet` is today.

## Experiment (verified by execution)

Scratch Kotlin/Native 2.4.10 `macosArm64` dynamic library and a net10.0 console app P/Invoking it,
run under the JIT and again published with `PublishAot=true` (outside the repo).
The Kotlin half declared `interface Keepsake<T> { var item: T; fun swap(next: T): T }` and
`interface Ranked<T : Comparable<T>> { val best: T }`, one generic bridge class per interface
(`class KeepsakeBridge<T>(...) : Keepsake<T>` reading C# boxes as `Any?` then `as T`), and one
factory export each, instantiated as `KeepsakeBridge<Any?>` and `RankedBridge<Nothing>`. Exports
read the bridge as the declared instantiation (`asStableRef<Keepsake<String>>()`,
`asStableRef<Keepsake<Int>>()`, `asStableRef<Ranked<String>>()`) or as the erased owner a generic
function's plan uses (`asStableRef<Keepsake<Any?>>()`). The C# half was a generic
`KeepsakeBridgeState<T>` whose slot lambdas call `FromHandle<T>` / `Wrap<T>`.

Output, identical under the JIT and NativeAOT:

```
read(diary) = read 4:Oreo
bump(counter) = 42, C# sees 42
swap(diary, Mylo) prev = Oreo, now Mylo
swap(counter, 7) prev = 42, now 7
read_catching(liar holding Int as Keepsake<String>) = CCE ClassCastException
best_length(podium) = 4
overload, closed static type: IKeepsake overload T=String
overload, class static type: erased HandleOf<Diary>
overload, open T: IKeepsake overload T=Int32
overload, object: erased HandleOf<Object>
```

What it proves:

- **One Kotlin export serves every instantiation.** `IKeepsake<string>` and `IKeepsake<int>`
  implementations both went through the one `keepsake_bridge_create`; Kotlin's erasure makes the
  `Keepsake<Any?>` bridge readable as `Keepsake<String>` and `Keepsake<Int>`. **Verified.**
- **Kotlin's use-site cast is the type check.** A C# `IKeepsake<object>` that hands back an `Int`
  through a `Keepsake<String>` read throws a Kotlin `ClassCastException` at the read, not memory
  corruption. The generated C# cannot construct that mismatch (the parameter is typed
  `IKeepsake<string>`), so this is only the backstop. **Verified.**
- **A self-referencing bound needs no trampoline on the bridge.** `RankedBridge<Nothing>` compiles
  (`Nothing` satisfies `T : Comparable<T>`) and answers `best.length == 4` through a
  `Ranked<String>` read, because the generic class's body is compiled once over the erased bound.
  **Verified** for `T : Comparable<T>`; a multi-bound `where T : A, T : B` takes the same rule and
  is **inferred** (Nothing satisfies every bound; not run).
- **A generic C# state class works under NativeAOT** for a reference and a value type argument,
  since each instantiation is statically reachable from its call site. **Verified** (the experiment used
  `Marshal.GetFunctionPointerForDelegate`, which warns IL3050; the generator uses the
  `NugetThunks` shells instead, so the warning does not carry over).
- **Overload resolution is not a safe dispatch.** A generic `HandleOf<T>(IKeepsake<T>, out bool)`
  overload beside today's `HandleOf<T>(T, out bool)` wins when the static type is `IKeepsake<X>`
  but loses to the erased overload when the static type is the implementing class. **Verified.**
  Hence the call site names the per-interface entry explicitly (Decision, C# half).

## Alternatives Considered

### 1. Erased Kotlin bridge, generic C# state, explicit per-interface entry at the call site (chosen)

Kotlin: one factory export per generic interface, building a generic bridge instantiated at
`Nothing`, always; a `T` slot rides ADR-215's box. C#: `{Iface}BridgeState<T...>`, plus a generated
`NugetBridge.{Iface}HandleOf<T>(IKeepsake<T> value, out bool owned)` that every generic-interface
parameter call site names. No reflection, NativeAOT safe, ABI is one export per interface.

### 2. Per-instantiation factory exports (`keepsake_string_bridge_create`)

Rejected: the instantiation is the C# consumer's choice (`IKeepsake<Cat>`, `IKeepsake<MyRecord>`),
unknowable at Kotlin build time, and the experiment shows one erased export already serves every one.

### 3. Runtime generic construction in `NugetBridge.HandleFor(object, Type)`
(`MakeGenericType(typeof(KeepsakeBridgeState<>), declared.GetGenericArguments())`)

Rejected: reflection on the hot path, and under NativeAOT a value-type instantiation the compiler
never saw has no code (inferred from the NativeAOT generic-instantiation rules, not run). It is
also the only route that could serve an *erased* position (a `Wrap<T>` of an `IKeepsake<string>`
inside a `List`), which is why those positions are deferred rather than solved this way.

### 4. Object-typed slots (`object Item`), a non-generic `IKeepsake` bridge

Rejected: the implementer writes `IKeepsake<string>`, so the bridge needs `T` to call `impl.Item`
without reflection; an `object` slot cannot call a `string Item`.

### 5. Keep the refusal, document "implement a non-generic sub-interface"

Rejected: Phase 7 is bidirectional parity, and the forward side already renders `IKeepsake<T>` for
C# to implement.

## Decision

Alternative 1. Consumer API:

```csharp
// Kotlin:
//   interface Keepsake<T> { var item: T }                         (existing, ADR-218)
//   interface Album<T> { val cover: T; var bookmark: T?; fun turn(next: T): T; fun title(): String }
//   interface Podium<T : Comparable<T>> { val best: T }
//   fun inspect(keepsake: Keepsake<String>): String
//   fun polish(keepsake: Keepsake<Int>): Int                       // item += 1, returns item
//   fun <T> trade(keepsake: Keepsake<T>, value: T): T              // returns the old item
//   fun <T : Furry> petName(keepsake: Keepsake<T>): String
//   fun <T : Comparable<T>> crown(podium: Podium<T>): String
//   class Curator { fun leaf(album: Album<String>): String }

private sealed class Diary : IKeepsake<string>
{
    public string Item { get; set; } = "Oreo";
    public void Dispose() { }
}

Assert.Equal("curator sees Oreo", Keepsakes.Inspect(new Diary()));     // getter slot
var counter = new Counter();                                           // IKeepsake<int>, Item = 41
Assert.Equal(42, Keepsakes.Polish(counter));                           // setter slot, int box
Assert.Equal(42, counter.Item);
Assert.Equal("Oreo", Keepsakes.Trade(diary, "Mylo"));                  // open T, string
using var mylo = new Cat("Mylo", 9);
var basket = new Basket(mylo);                                         // IKeepsake<Cat>
Assert.Equal("Mylo", Keepsakes.PetName(basket));                       // bounded T, borrowed Cat
using var locket = new Locket<string>("Oreo");
Assert.Equal("curator sees Oreo", Keepsakes.Inspect(locket));          // Kotlin-backed, same member
```

### Kotlin half (generated)

- **Reference classification.** A generic interface reference at an *input* position (a
  parameter of a top-level, class, object or generic function, a constructor parameter, or an
  extension receiver) classifies as `BridgeType.Interface` carrying its applied spellings, exactly
  as ADR-208's `genericReference` does for a generic class: C# `global::Ns.IKeepsake<string>` /
  `IKeepsake<T>`; Kotlin read type `Keepsake<String>`, or the erased owner (`Keepsake<Any?>`,
  `Keepsake<Furry>`) a generic function's plan already reads, or ADR-217's type-variable spelling
  inside a trampoline. It is a separate classifier entry (`genericInterfaceOrNull`) that only the
  parameter and receiver sites ask, so every other position keeps `classify`'s `"generic
  declaration"` skip untouched. The refusals ADR-208 gives a generic class carry over unchanged
  (use-site projection, `Box<Any>`, a lambda or collection argument). Wire: the unchanged ADR-040
  handle (`POINTER`, borrowed). When the interface's bridge cannot plan (a member's own `<R>`, a
  suspend member), the position skips by name with the bridge's refusal as the reason, rather than
  binding a call site no entry serves.
- **Factory.** One `<prefix>_bridge_create` per bridgeable generic interface. The slot walk reads
  the interface's own members; an inherited generic super-interface member is not substituted,
  so `IntHolder : Holder<Int>` is unchanged by this ADR. The bridge is a local generic function
  inside the export returning an anonymous object, so the shipped `object : Iface,
  NugetCSharpBridge` shape keeps its cleaner and token:

  ```kotlin
  fun <T> bridge(): pkg.Keepsake<T> = object : pkg.Keepsake<T>, NugetCSharpBridge {
    override val nugetToken: COpaquePointer = token
    private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) -> fn.invoke(ctx, null) }
    override var item: T
      get() { /* box (null when T is nullable), `as T`, NugetHandles.release */ }
      set(value) { /* NugetHandles.retain(value), itemSetFn.invoke(...) */ }
  }
  NugetHandles.retain(bridge<Nothing>())
  ```

  The type argument is `Nothing`, always: the function is compiled once over the erased bounds,
  so the instantiation only has to satisfy them, which `Nothing` does for an unbounded, a single
  and a self-referencing bound alike. The bounds are restated as a `where` clause (`where T :
  kotlin.Comparable<T>`). A nullable-from-bound `T` carries `IntPtr.Zero` for null in both
  directions, and a non-null `T` result reads with `!!`, exactly ADR-215's rules.
- **Slot vocabulary** gains the interface's own `T` / `T?` as a parameter, result or property
  type (wire `OBJECT`, boxed), with a `var`'s setter slot as the
  [ADR-084 amendment (2026-10-22)](084-csharp-implemented-interfaces.md) ships it for every
  interface. Still refused, by name: a member's own type parameter (`fun <R> map(...)`: C# cannot
  call `impl.Map<R>` with an `R` Kotlin erased, a genuine impossibility, not a deferral), and every
  other existing refusal.
- **Retain export.** A `T` result slot hands Kotlin a box it owns, so C# returns
  `NugetMarshal.WrapOwned<T>(...)`, which needs the `<lib>__retain_handle` export. Its gate
  (`ForwardCallablePlanCatalog.objectCallbackResultOwner`, `forward/ForwardRetainHandle.kt`) falls
  back to the first plan taking a generic interface, which becomes the ADR-117 owner.

### C# half (generated)

- `internal sealed class KeepsakeBridgeState<T> : NugetBridgeState` with
  `static KeepsakeBridgeState<T> Create(IKeepsake<T> impl)`, slot lambdas reading
  `NugetMarshal.FromHandle<T>(arg0)` and returning `NugetMarshal.WrapOwned<T>(impl.Item)`. The
  `where` clauses repeat the interface's (ADR-015 / ADR-198 spelling). The factory's `[DllImport]`
  sits on `NugetBridge` (`Native_KeepsakeBridgeStateCreate`), not on the generic state class: the
  CLR does not load a P/Invoke declared in a generic type (inferred from the runtime's documented
  rule, not run; the generator never emits the other shape).
- `NugetBridge` gains, per generic interface, an inferring entry, package-qualified like the state
  class (ADR-163: the fixture's is `PlayroomKeepsakeHandleOf`):

  ```csharp
  internal static IntPtr KeepsakeHandleOf<T>(IKeepsake<T> value, out bool owned)
  {
      if (value is INugetHandle wrapper) { owned = false; return wrapper.Handle; }
      IntPtr handle = KeepsakeBridgeState<T>.Create(value).KotlinHandle;
      owned = true;
      return handle;
  }
  ```

  and every generic-interface parameter call site calls it by name (a `T?` parameter guards null
  first, as `HandleOfOrZero` does). Not an overload of `HandleOf`: the experiment shows an overload is
  silently bypassed when the static type is the implementing class. A generic interface has no
  `HandleFor(object, Type)` arm, so an erased position still reaches its `NotSupportedException`.
- Lifetime, identity and release are ADR-084's, unchanged: a transfer handle per crossing,
  disposed by the call site's `finally`; the token is a `GCHandle` to the implementing object; the
  Kotlin cleaner frees the pins. The generic state shares the base class and its `FreeAll`;
  `CollectabilityTests.CSharpGenericKeepsake_ReleasedAfterKotlinGc` and the two LeakTests rows
  below check it.

## Scope

Ships:

- A C# class implementing a Kotlin generic interface, at a parameter of a top-level function, a
  class or object member, a constructor and a generic function (`fun <T>`, bounded, trampolined),
  and at an extension receiver (`fun Keepsake<String>.shine()`), with the interface's `T` as
  property, `var` setter, method parameter and method result; a nested generic interface and a
  multi-parameter one (`Pairing<K, V>`), each pinned by one Tier 1 cell.
- A Kotlin-backed value (`Locket<string>`) at the same parameters, which binds for the first time.

Not shipped, and not deferred by this ADR: a generic interface at an output position (a return, a
property getter, a class property typed by one) keeps ADR-208's named skip, now tracked by one
Phase 4 ROADMAP line, since it needs a generic C# backing wrapper nothing has designed; a C#
`IKeepsake<string>` Kotlin stores therefore cannot be read back, and this ADR has no `Assert.Same`
round trip. A generic interface at an erased position (a `List<Keepsake<String>>` element, a
`Box<Keepsake<String>>` argument, a lambda payload) is declined as ADR-208 declines the same
positions for a generic class, with the same remedy (type the position with a non-generic
interface, or pass the value as a direct parameter); not a roadmap item. A member-level type
parameter on a bridged interface is impossible, not deferred.

## Consequences

- `fun inspect(keepsake: Keepsake<String>)` binds as `Inspect(IKeepsake<string> keepsake)`; the
  `GENERIC` skip for a generic interface at an input position is gone. Output and erased
  positions keep ADR-208's named skips, whose sentence now names the remedy.
- `SKIPPED_UNIMPLEMENTABLE_INTERFACE` loses its "declares type parameters" refusal for the
  interface itself.
- The ADR-055 contract gains one export per generic interface reached at an input position. No
  shipped export changes shape.
- ROADMAP: the Phase 7 line closes; one Phase 4 line tracks the output position.

## Verified in the pipeline

`scripts/verify.sh`, under the JIT and NativeAOT: `GenericInterfaceBridgeTests` (closed `string`
and `int`, open `T` over `string`, `int` and `Cat`, bounded `T : Furry`, self-bounded
`T : Comparable<T>`, a class member with `T` in and out and a `T?` setter fed null, the extension
receiver, a Kotlin-backed `Locket<string>`), the LeakTests rows 8j-generic and 8j-generic-cat, and
`CollectabilityTests.CSharpGenericKeepsake_ReleasedAfterKotlinGc`. That moves the extension
receiver, the unbounded `Nothing` instantiation and the generic state's release from inferred to
verified.

## Inferred claims (not verified)

- A multi-bound `T` bridge instantiates at `Nothing` like the self-bound one. If wrong, Kotlin
  compile error (loud).
- The CLR rule that keeps the factory import off the generic state class (above).

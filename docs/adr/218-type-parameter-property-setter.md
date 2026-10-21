# ADR-218: Forward, a type-parameter property setter: `var current: T` on `Shelf<T>` as `T Current { get; set; }`

## Status
Accepted

Amends [ADR-147](147-generic-class-methods.md) (setter paragraph: "A `var` property's setter is not
admitted in v1") and corrects [ADR-212](212-type-parameter-as-collection-element.md) (Consequences:
"a `T` setter (`var items: List<T>`...)" deferred). Also widens the [ADR-113](113-interface-declaration-on-the-forward-plan.md)
type-parameter carve-out: an interface `var item: T` declares its setter.

## Context

ROADMAP Phase 4: "A `var` property of `List<T>` renders get-only, its setter dropped as a bare `T`
setter is (ADR-212)".

- **The collection half already binds. Verified by spike, then by execution.** On `class Shelf<T>`,
  `var items: List<T>`, `MutableList<T>`, `Set<T>`, `Map<String, T>`, on `Kennel<T : Furry>`
  `var pets: List<T>`, and on `Keyed<K : Any, V>` `var index: Map<K, V>`, each produced a
  `Native_Set_*` export and a C# `set { valueHandle = NugetMarshal.CreateList(value); ... }` with
  the list handle disposed in a `finally`. Cause: ADR-212 opened `isWrappableComponent` for a
  non-trampolined `T`, and the collection setter gate `isSetterEligible` is that predicate. The
  xunit rows in `TypeParameterPropertySetterTests` and the leak row now run them.
- **The bare `T` setter was refused by one check. Verified.** `collectionSetterOrNull` dropped it
  with "a type-parameter property binds read-only in v1; the write side has no boxing step
  (ADR-147)" (`SKIPPED_UNSUPPORTED_INPUT`).
- **That reason was stale. Verified.** The property setter's `Direct` body already runs a per-value
  handle step and `finally` cleanup (ADR-084 stage 3); the callable route already boxed a `T`
  argument with `Wrap<T>` and disposed only an owned box (incl. ADR-198 `Wrap<T?>`); the Kotlin read
  existed as `forwardBoundedRead`. The gaps were a `TypeParameter` arm in the property projection's
  `handleStep` / `handleCleanup` / `inputArgument` and three property-emitter arms that `error()`ed.
- **An interface `var item: T` was silently get-only.** The ADR-113 carve-out declared
  `T Item { get; }` with no diagnostic, and `explicitSetterInterfaces` filtered a `T`-typed member
  out, so `Box : Board(), Holder<String>` could not reach its CS0546-refused setter either.

Other targets: Kotlin/JVM emits `void setCurrent(T)` on the erased class; ObjC export emits a
`readwrite id` property. C# consumers expect `{ get; set; }` on a generic class property.

## Alternatives Considered

### 1. Reuse the callable `T` box in the property setter scope (chosen)
`IntPtr valueBox = NugetMarshal.Wrap<T>(value!, out bool valueOwned)` before the native call, dispose
only an owned box in the `finally`, Kotlin reads `value.asStableRef<Any>().get()` cast to the bound
chain. No ABI change, no runtime change; the export is one `IntPtr` value slot.

### 2. Keep the named refusal
Rejected: the stated cause is gone, and a get-only `T` on a mutable Kotlin class surprises a C#
consumer who can write `shelf.Items = ...` on the same class but not `shelf.Current = ...`.

### 3. Typed setter methods (`SetCurrentInt(int)`, ...)
Rejected: not a C# property, width variants are the route ADR-211 retires.

### 4. `object`-typed setter
Rejected: not `T`, so the type error moves to a Kotlin `ClassCastException`.

## Decision

Alternative 1, for every bare `T` / `T?` setter, including a trampolined one and an interface's.

```C#
using var pen = new Playpen<int>(9);
pen.Current = 7;                            // boxed, the box disposed after the call
pen.Items = new[] { 1, 2, 3 };              // already shipped (ADR-212), now pinned
using var names = new Playpen<string?>("Oreo");
names.Maybe = null;                         // IntPtr.Zero, no box
using var oreo = new Cat("Oreo", 9);
using var cats = new Playpen<Cat>(oreo);
cats.Current = oreo;                        // borrowed, still yours to dispose
using var sash = new Sash<Rankings.Medal>(Rankings.Medal.Silver);
sash.RunnerUp = null;                       // Wrap<T?> under `struct`: null, not Bronze
IKeepsake<string> keepsake = new Locket<string>("Oreo");
keepsake.Item = "Mylo";                     // IKeepsake<T> declares T Item { get; set; }
```

Mechanism, per half (all verified by the Tier 1 cells in `Tier1TypeParameterPropertySetterTest`
and by execution):

- **Planner.** The `TypeParameter` refusal in `collectionSetterOrNull` is deleted; the setter plans
  `ForwardPropertySetter.Direct` (`hasValueFanOutInner` has no `TypeParameter` arm, so `T?` is not
  `NullableDispatch`). `ForwardPropertyReceiver.Handle` carries the class's `ForwardGenericOwner`.
- **C# setter.** The callable route's `typeParameterPrelude` / `typeParameterCleanup` moved into
  the shared `forwardCirTypeParameterBox` / `forwardCirTypeParameterCleanup`
  (`ForwardCirHandleScope.kt`); the callable projection and the property projection's
  `handleStep` / `handleCleanup` both call them, so the ADR-198 `Wrap<T?>` rule lives once.
  `inputArgument("value")` passes `valueBox`. DllImport type `IntPtr`.
- **Kotlin setter.** `kotlinInputType(TypeParameter) = COpaquePointer` (nullable for `T?`);
  `inputLowering` is `forwardBoundedRead`. The receiver is the erased owner (`Playpen<Any?>`,
  `Cradle<Furry>`, `Crate<Any>`), so the assignment target is `Any?`, `Any` or the bound; a
  `Crate<T : Any>` takes the non-null read with no `!!`.
- **Trampolined and multi-bound `T`.** Neither has an erased argument (`ForwardGenericOwnerParameter.erased`
  is null), so the erased receiver is `Sash<*>` / `Arena<*>`, whose setter takes `Nothing`. The
  property emitter calls the callable emitter's existing `FunSpec.trampolined`, and the assignment
  inside it is `(handle.asStableRef<Any>().get() as Sash<T>).current = <read cast to T>`. Correction
  from execution: the draft scoped this to an ADR-198 trampolined `T`; a multi-bound `T`
  (`where T : Pet, T : Trainable`) has the same erased-receiver problem and takes the same route.
- **Interface-owned `T`.** The ADR-113 carve-out keeps a `var`'s setter (`T Item { get; set; }`),
  and `explicitSetterInterfaces` no longer filters a `T`-typed member, so a class whose public
  setter is CS0546-refused (`Box : Board(), Holder<String>` over `open val item`) reaches it through
  an explicit `string IHolder<string>.Item`, the rule `Tier1InterfaceVarPropertyTest` already pinned
  for a non-`T` interface `var`. The Kotlin-implemented side only: a C#-implemented `IHolder<T>`
  stays out of reach because ADR-084 bridges no generic interface (pre-existing).
- **Lifetime.** Kotlin stores the dereferenced object, never the pointer, so the C# `finally`
  disposes a minted box (primitive, string) right after the call; a wrapper is borrowed (`owned =
  false`) and `GC.KeepAlive`d. Proven by `GenericInstance_SetterBoxes_ReturnToBaseline`.
- **ABI contract.** One `_set_<name>` export per property, checked by the ADR-055 plan contract.

## Consequences

- `var current: T` / `var current: T?` on a generic class render `{ get; set; }`; the
  "binds read-only in v1" warning is gone.
- `var items: List<T>` (and `Set`/`Map`/mutable kinds) is pinned by Tier 1, xunit and leak rows; no
  code change for it.
- `interface Holder<T> { var item: T }` declares `T Item { get; set; }`.
- `Playpen<int>.Maybe = null` is CS0037: an unconstrained `T?` is spelled `T` in C# (ADR-147), so
  a value-type argument cannot take null, and `Keyring<string, int>.Lookup("tom")` (a `V?` result)
  is typed `int`, so it cannot answer null. Pre-existing, not this ADR's. The CS0037 is verified
  (the first red xunit row failed to compile on it; the leak row had the same assignment); the
  `Lookup` case is inferred from the same spelling and its null assert was removed unrun.
- The CS0546 remark on `Box.Item` names the explicit member as C# spells it, `IHolder<string>.Item`
  (it said `IHolder.Item` before: the remark dropped the interface's type arguments).
- Not changed: a trampolined `T` inside a collection (its own ROADMAP line).

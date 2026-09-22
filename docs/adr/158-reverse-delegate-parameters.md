# ADR-158: C# delegate parameters as Kotlin function types, over a one-slot Kotlin bridge

## Status
Accepted

## Context

Reverse direction (C# → Kotlin). The C# NuGet package declares the member; Kotlin consumes it. A
Kotlin consumer of a bound package calls a C# method or constructor that declares a `Func<>`,
`Action<>`, `Predicate<T>`, `Comparison<T>`, `Converter<,>`, or a package-declared `delegate`
parameter, and passes an ordinary Kotlin lambda: `workshop.apply(21) { it * 2 }`,
`workshop.forEachName { println(it) }`. The C# callee may invoke it synchronously, later, on
another thread, or store it; a Kotlin throw inside the lambda has to surface as a catchable
exception on both sides instead of killing the host.

Before this ADR, every such member was skipped, and one shape was mis-bound outright:

- A package-declared `delegate` compiles to a sealed class whose base is `System.MulticastDelegate`
  (ECMA-335 II.14.6). Nothing in the reader tested for that base, so the TypeDef fell into the
  bound-handle-type collector and was extracted as an ordinary `RirClass`: an `Invoke` method, an
  empty constructor list (the real `(object, IntPtr)` constructor was dropped with no diagnostic),
  and two noise diagnostics from `BeginInvoke`/`EndInvoke` referencing `System.AsyncCallback`/
  `System.IAsyncResult`. A parameter of that type resolved to a handle no Kotlin code could
  construct. Verified by spike, 2026-09-21.
- A generic BCL delegate (`Func<int,int>`, `Action<string>`) is a TypeSpec that reached the same
  path as an unbound generic instantiation, and was diagnosed `skipped_unbound_generic_instantiation`
  with a hint suggesting a BCL collection. A non-generic one (`System.Action`) is a TypeReference
  and was diagnosed `skipped_unbound_type_reference` with a hint suggesting `System.Private.CoreLib`.
  Both diagnoses blamed the wrong thing.

ECMA-335 parameter metadata carries no information about whether a callee stores its delegate
argument: `List<T>.ForEach(Action<T>)` (per-call) and `Timer(TimerCallback)` (stored) have the same
parameter shape (inferred from the spec; no counter-example found). Per-call and stored therefore
cannot be told apart at generation time, and any implementation has to pick one lifetime rule safe
for both.

ADR-085/086/087/089 already carry a Kotlin object into C# behind a ctx `StableRef`, with
Kotlin-minted function-pointer slots and a `SafeHandle`-driven release verified from arbitrary .NET
threads including the finalizer thread. A delegate is that mechanism with exactly one slot: the
`Invoke` member.

## Alternatives Considered

### 1. One-slot Kotlin bridge per delegate shape, delegate-owned lifetime (chosen)

A C# holder class holds a `KotlinRefHandle` ctx and one `delegate* unmanaged[Cdecl]` slot; a
factory constructs `new TDelegate(holder.Invoke)`. The Kotlin lambda's `StableRef` is released when
the .NET GC collects the holder, which happens once C# drops the delegate. Pros: one lifetime rule
correct for per-call and stored alike; reuses the shipped, spiked ADR-085/086/087/089 machinery
including its error channel; closed-delegate construction over an instance method is a static
`ldftn`/`newobj`, no reflection and no `Marshal.GetDelegateForFunctionPointer`. Cons: a per-call
lambda's capture is released GC-timed, not at return.

### 2. Per-call borrow (the literal ADR-036 inversion)

Release the `StableRef` when the C# call returns. Rejected: undetectable from metadata whether the
callee stored the delegate, so this is a use-after-free the first time it does.

### 3. Kotlin-owned `AutoCloseable` callback handle

Rejected: the consumer cannot know the required lifetime either, it is not Kotlin-idiomatic, and no
analogue does this except JNA's well-known footgun.

### 4. `fun interface` per delegate instead of a function type

Rejected: `Func`/`Action` have no useful nominal name, and splitting the surface between function
types and SAM interfaces for no reason makes `Func`-typed and custom-typed parameters feel
different. A `typealias` on the function type keeps the C# name for a custom delegate instead.

### 5. Raw function pointer via `Marshal.GetDelegateForFunctionPointer`

Rejected: no ctx, so no closures, and not AOT-safe (the exact family ADR-102 removed forward).

## Decision

### 1. Reader: a delegate is never a class, and gets its own type ref

`MetadataHelpers.IsDelegate` (base type `System.MulticastDelegate` or `System.Delegate`) excludes a
delegate TypeDef from the bound-handle-type collector and from class-member extraction entirely, so
a package-declared delegate produces no `Invoke`-method class, no dropped-constructor member, and
none of the `BeginInvoke`/`EndInvoke` noise. A new `RirTypeRef` variant carries every delegate shape:

```kotlin
@Serializable @SerialName("delegate")
data class RirDelegateType(
  val definition: String,               // CLR full name: "System.Func`2", "Test.Workshop.Transform"
  val typeArguments: List<RirTypeRef>,  // for spelling the closed type; empty when non-generic
  val parameters: List<RirTypeRef>,     // the Invoke signature, derived
  val returnType: RirTypeRef,
  val nullable: Boolean = false,
) : RirTypeRef
```

Two sources feed it:

- A `DelegateDefinitions` name table, matched on CLR full name only (never on resolution scope, the
  same ADR-155 reasoning applies: `Func`/`Action` resolve through different facade assemblies), for
  `System.Action`, ``Action`1..16``, ``Func`1..17``, `Predicate<T>`, `Comparison<T>`,
  `Converter<TIn,TOut>`. `TryDelegateShape` derives each one's `Invoke` signature positionally from
  its type arguments (`Predicate<T>` is `(T) -> Boolean`, `Comparison<T>` is `(T, T) -> Int`,
  `Converter<TIn,TOut>` is `(TIn) -> TOut`). `EventHandler`, `EventHandler<T>`, `AsyncCallback`, and
  the thread/timer callbacks are in the table so they are recognized as delegates but have no
  derived shape, so they keep the named skip rather than being misdiagnosed as an unbound
  collection. `Func`/`Action`/`Predicate` and the package-declared shape are exercised end to end by
  the `TestDependency/Workshop.cs` fixture and its round-trip tests; `Comparison<T>` and
  `Converter<TIn,TOut>` are derived
  by the same rule in `TryDelegateShape` but carry no fixture, so their shape is verified by reading
  only.
- A package-declared delegate's own `Invoke` `MethodDefinition`, decoded directly. Generic, nested,
  and delegate-typed-parameter custom delegates are refused by name (see Limitations); a delegate
  declared in an unbound external assembly keeps its pre-existing diagnostic (the reader has only a
  `TypeReference`, no `Invoke` signature, for that case).

Nullability reuses the ADR-053/072 decoder unchanged: `RirDelegateType` joins `IsAnnotatable`,
`CountAnnotatableNodes`, and `ApplyPreOrder` over its `typeArguments`, with the delegate node itself
counted first (`Action<string?>` is byte sequence `[1, 2]`; `Func<string?,string>?` is
`[2, 2, 1]`; a fully-agreeing shape carries no per-parameter attribute at all, only a method-level
`NullableContextAttribute`, which the shared decoder already reads). `parameters`/`returnType` are
rebuilt from the annotated `typeArguments` after the walk, never carried over from before it, or a
nullable argument would silently disappear from the derived shape while `typeArguments` still
carried it.

### 2. Kotlin surface: a plain function type, plus a `typealias` for a custom delegate

`Func<int,int>` is `(Int) -> Int`; `void`-returning is `Unit`; `Predicate<T>` is `(T) -> Boolean`; a
nullable delegate is a nullable function type, crossing `null` as `IntPtr.Zero`. A package-declared
delegate additionally emits a `typealias` in its namespace's package carrying the C# name:

```kotlin
// Generated: C# `Test.Workshop.Transform`
typealias Transform = (Int) -> Int
```

### 3. Wire: one transfer `GCHandle`, a per-shape C# holder, arity ceiling 4

A delegate parameter crosses the ordinary method/constructor thunk as one `IntPtr` transfer
`GCHandle`, exactly like an ADR-070 interface parameter. Per distinct delegate **shape** (its
`Invoke` signature, not its CLR name: `Func<int,int>` and `Func<int,string>` are different shapes),
the C# generator emits a holder class (`KotlinRefHandle` ctx plus one
`delegate* unmanaged[Cdecl]<IntPtr, args..., IntPtr*, ret>`) and an `[UnmanagedCallersOnly]` factory
`(invoke, ctx) -> GCHandle(new TDelegate(holder.Invoke))`; the Kotlin generator emits a matching
`staticCFunction` slot and a `mint{Shape}Delegate` that resolves-or-mints through the ADR-089 weak
reuse table by lambda identity. The factory rides the **declaring type's** ADR-054 registration
(one factory per distinct shape per declaring type), so it folds into that type's contract hash the
same way an interface's own slots do; two bound classes using the same shape mint two independent
factories.

`Invoke` arity up to **4** is admitted for a delegate slot (six C parameters: ctx + 4 + a trailing
error out-parameter), verified by spike on Kotlin 2.4.10 mingwX64: the emitters are already
arity-generic, and the hard ABI ceiling is 22. This is a delegate-only policy lift; the interface
bridge's own slot arity stays at 2.

### 4. Lifetime: the C# delegate owns the Kotlin lambda

The only rule metadata permits (see Context above): the `StableRef` behind the ctx is released by
`KotlinRefHandle.ReleaseHandle` when the .NET GC collects the holder, which happens once every C#
reference to the delegate (stored or not) is gone. The Kotlin caller owns nothing beyond the
transfer handle it frees immediately after the call; there is no `close()`, `Cleaner`, or
subscription object for a delegate parameter. This closes the ROADMAP Phase 13 "stored delegate"
line: it is not a second feature, it is the lifetime rule this ADR already has to be correct for.

As a byproduct, every ctx `StableRef` this mechanism mints (a delegate's, and the pre-existing
ADR-085 interface bridge's) is now minted and released through a counted `NugetHandles.retain`/
`release` pair reached across the runtime-klib seam via an `expect`/`actual` function
(`nugetRetainCtx`/`nugetReleaseCtx`, the same ADR-130 seam `nugetKotlinError` already uses), rather
than a bare `StableRef.create`/`dispose`. Before this change, `nuget_live_handles` never counted a
Kotlin object living inside a C# bridge at all. ADR-085 and ADR-120 each carry a dated amendment
(2026-09-22) recording this.

### 5. Errors: the existing four-hop channel, no new one

A Kotlin throw inside the lambda writes the ADR-087 envelope; the C# holder's `Invoke` throws the
ADR-029-mapped exception type (`KotlinInvalidOperationException` for `kotlin.IllegalStateException`,
falling back to the base `KotlinException` only for an unmapped Kotlin type); it propagates through
the C# method and out through the ADR-104 outer reverse-thunk channel; the Kotlin caller catches
`NugetManagedException(managedType, message)`, where `managedType` is the mapped type's
`GetType().FullName` in the forward bindings' namespace (for example
`"TestLibrary.KotlinInvalidOperationException"`, not a bare `"...KotlinException"` suffix). No new
channel; the receiver stays usable after the throw.

### 6. Overloads differing only by delegate shape bind, with a named info diagnostic

A `Task.Run(Action)` / `Task.Run(Func<int>)`-shaped overload pair binds every member. Verified by
spike on Kotlin 2.4.10 (K2): a **bare** Kotlin lambda is always an `Overload resolution ambiguity`
against such a pair, in both directions, including the empty `{ }` form, while a typed function
value (`val pick: () -> Int = { 1 }`) or an anonymous function (`fun(): Int = 1`) resolves. Dropping
the set instead (the ADR-057 collapse style) was rejected: it would delete `Task.Run`-shaped members
that stay perfectly callable. `INFO_DELEGATE_OVERLOAD_AMBIGUITY` names the set and both workarounds.

### 7. Scope: v1 admits a delegate parameter of a method or constructor of an ordinary bound class

Static and instance methods, and constructors, of a non-generic bound class. `SKIPPED_DELEGATE_SIGNATURE`
names a delegate shape the reader declines outright (async, arity above 4, a `ref`/`out`/`in`
position, an unsupported Invoke element, a generic or nested custom delegate, a custom delegate
whose own `Invoke` mentions another delegate). `SKIPPED_DELEGATE_POSITION` names a delegate the
reader admitted that the generators still decline: a return, a property, a struct member, a
bound-interface member, or a generic-class member.

## Consequences

- The ROADMAP Phase 13 "pass Kotlin lambdas where a C# API stores the delegate" line closes with
  this ADR; it was a lifetime question, not a second mechanism, and Decision 4 already covers it.
- **A delegate-typed return, which bound today by accident** as a handle class with a working
  `invoke()` method (because the mis-classified delegate class kept its `Invoke` member), **loses
  that accidental binding** and becomes `SKIPPED_DELEGATE_POSITION` instead. No fixture or doc
  depended on it (verified by grep); it reappears once a return position ships.
- Events (ROADMAP Phase 12) become expressible on top of this: `RirDelegateType` is a first-class
  type ref, and the ADR-089 reuse-by-identity table already keys on the right thing for
  `add_X`/`remove_X` to see the same delegate instance back. They still stay `skipped_event`; this
  ADR does not bind one.
- Async delegates (`Func<Task>`, `Func<CancellationToken, Task<T>>`, ...) remain the largest
  practical gap and share a design with the ROADMAP Phase 13 `Task`-typed-slot line: both need a
  Kotlin-to-C# completion mechanism (a `TaskCompletionSource` driven from a Kotlin callback) that no
  reverse code has today.
- Each declaring type that uses a delegate shape gains registration slots, moving that type's
  ADR-054 contract hash: an old C# shim built against a dylib that added a delegate parameter is
  caught by `checkContract`, as designed.
- A per-call lambda's captures live until the next .NET collection after the C# side drops its
  delegate, not until the crossing that passed it returns.
- `nuget_live_handles` now reflects every Kotlin object living inside a C# bridge (a
  Kotlin-implemented interface's ctx, or a delegate's), which it did not before this ADR; see
  Decision 4's amendment note on ADR-085 and ADR-120.
- Fixture `TestDependency/Workshop.cs`; three new `LeakTests/LiveHandleTests.cs` rows: row 13
  (`ReverseDelegate_PerCallCapturingLambda_ReturnsToBaseline`), row 14
  (`ReverseDelegate_StoredThenOwnerDisposed_ReturnsToBaseline`), row 15
  (`ReverseDelegate_ThrowingLambda_ReturnsToBaseline`).

## Discovered, not fixed here

- `delegateOverloadAmbiguityDiagnostics` filters to `RirRegistrable.Method` only; a constructor pair
  differing only by delegate shape binds with no diagnostic. See
  [`docs/backlog/delegate-overload-and-return-gaps.md`](../backlog/delegate-overload-and-return-gaps.md).
- Two custom delegates with the same simple name declared in different C# namespaces, both bound
  into one Kotlin package, alias to one `typealias`: the second overwrites the first silently. Same
  backlog file.
- Oblivious nullability on a custom delegate's own `Invoke` parameters is not reported with
  `info_oblivious_nullability`, unlike other annotatable positions. See
  [`docs/backlog/delegate-invoke-oblivious-nullability-unreported.md`](../backlog/delegate-invoke-oblivious-nullability-unreported.md).
- AOT/trimmer safety of the generated `new TDelegate(holder.Invoke)` factory is inferred, not run
  through `AotSmokeTest`. See
  [`docs/backlog/delegate-factory-aot-safety-unverified.md`](../backlog/delegate-factory-aot-safety-unverified.md).

## Prior art

Kotlin/Native's own ObjC-import callback rule (an ObjC block imports as a Kotlin function type and
the runtime retains the Kotlin lambda for as long as ObjC retains the block, with no per-call/stored
split) is the closer precedent than SAM conversion; Swift export follows the same rule for a Kotlin
closure crossing to Swift. Xamarin.Android's generated Java-listener peer, CsWinRT's ref-counted CCW
over a projected delegate, and Python.NET's delegate-holds-`PyObject` shape all give ownership of the
foreign callback to the callee's own runtime; none of them distinguishes per-call from stored
either. JNA's `Callback` is the counterexample: the Java caller must keep the callback reachable
itself, a well-known footgun, and the reason alternative 3 above is rejected.

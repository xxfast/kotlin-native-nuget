# ADR-135: Interface parameter positions join the ADR-084 bridge reachability set, and a failed `HandleOf` never reaches the dispose in `finally`

## Status

Accepted

## Context

[ADR-084](084-csharp-implemented-interfaces.md) lets a C# class implement a Kotlin interface and be
passed where a Kotlin function takes that interface. Its Detection rule says the bridge factory "is
generated for each interface reachable at such a [parameter] position", and the `CirTranslator`
comment at the bridge-plan site repeats "reachable at a return or parameter position".

That is not what ships. The set the bridge plan is built from is `reachableInterfaces`
(`NugetProcessor.kt:1165-1174`), which is [ADR-040](040-interface-return-type-mapping.md) sub-decision
C.1's **return**-reachable set: it walks `plan.publicSignature.result` and `propertyPlan.type` and
nothing else. **Verified by reading**, not inferred:

```kotlin
val reachableInterfaceNames: Set<String> = buildSet {
  ordinaryCatalog.plans.forEach { plan ->
    plan.publicSignature.result.interfaceQualifiedNameOrNull()?.let(::add)
  }
  ordinaryCatalog.propertyPlans.forEach { plan ->
    plan.type.interfaceQualifiedNameOrNull()?.let(::add)
  }
}
```

The same list is threaded to `CirTranslator` as `interfaceBackingClasses` (`CirTranslator.kt:122`)
and is the source of three things: the ADR-040 backing wrapper (`:409`), the Tier 1 owner-scope
collision check (`:492`), and the ADR-084 bridge plans (`:661`). The Kotlin half builds its bridge
factory exports from the same `reachableInterfaces` (`NugetProcessor.kt:1558-1565`).

So an interface that appears **only** at a parameter position (`fun greetVia(keeper: Keeper)` with
no `fun currentKeeper(): Keeper` anywhere) has no wrapper, no dispatch exports, no bridge state and
no factory. This is **not nested-only**: nothing in the walk looks at nesting, so a top-level
`interface Greeter` used only as `fun greet(g: Greeter)` is in exactly the same state (verified by
reading; the shipped fixtures never exercise it because every positioned interface in
`test-library` is also returned somewhere: `Pet` via `Cat.closestFriend`, `Aviary.Keeper` and
`Registry.Keeper` via `currentKeeper()` added for precisely this reason, see ADR-084's 2026-09-13
amendment). The Tier 1 pin `Tier1Issue112InterfaceProjectionTest` "non-reachable interface keeps
every member and gains no exports" uses an interface that is implemented and never positioned at
all, so it is unaffected by widening the walk.

### Why the process dies instead of throwing

ADR-084 and ADR-133 record that passing a C# implementation at such a position "crashes the host
with an unlocated Kotlin `NullPointerException`" where a managed `NotSupportedException` was
expected. Reading the generated shape explains it; every step below is **verified by reading** the
renderer source, and the last step is **inferred** (not run):

1. The C# call site's prelude for an interface-typed argument is
   `keeperHandle = NugetMarshal.HandleOf(keeper, out keeperOwned);`
   (`ForwardCirPlanProjection.kt:696-711`). Because the argument also has a cleanup
   (`interfaceCleanup`, `:716-722`), `forwardCirHandleScope` (`ForwardCirHandleScope.kt:29-58`)
   renders the split form: `IntPtr keeperHandle = IntPtr.Zero; bool keeperOwned = false;` declared
   before a `try`, the assignment inside it, the cleanup in `finally`.
2. `NugetMarshal.HandleOf(object value, out bool owned)` (`CirMarshalRenderer.kt:508-517`) is
   rendered as: not an `INugetHandle` wrapper, so `owned = true;` **then** `return HandleOf(value);`.
   The `out` parameter is assigned before the inner call can fail.
3. The inner `HandleOf(object)` (`:494-503`) falls to `NugetBridge.HandleFor(value)`, whose arms are
   one `if (impl is X)` per planned interface (`CirBridgeRenderer.kt:27-41`); with no plan for
   `Registry.IKeeper` it throws `NotSupportedException("... implements no bridgeable Kotlin
   interface.")`. When no interface in the library plans at all (`includesBridge == false`) the
   outer `HandleOf` throws the ADR-040 `NotSupportedException` directly (`:499-501`). Either way, a
   managed throw with `keeperOwned == true` and `keeperHandle == IntPtr.Zero`.
4. The `finally` runs `if (keeperOwned) { NugetMarshal.Dispose(keeperHandle); }`
   (`ForwardCirPlanProjection.kt:721-722`). There is **no** `!= IntPtr.Zero` guard, unlike the
   sibling `collectionCleanup` (`:758-759`), whose comment says exactly why one is needed: "this
   runs in a `finally` that a throw *from the creation itself* also reaches, where any handle is
   still Zero."
5. `NugetMarshal.Dispose(IntPtr.Zero)` P/Invokes `nuget_dispose`, which is
   `export_nuget_dispose(handle: COpaquePointer)` calling `handle.asStableRef<Any>().dispose()`
   (`nuget-runtime/.../NugetRuntime.kt:56-58, 132-134`). **Inferred**: a NULL arriving at a
   non-nullable `COpaquePointer` parameter of a `@CName` export raises `kotlin.NullPointerException`
   in the generated bridge, and an uncaught Kotlin exception in a `@CName` export terminates the
   process. This matches the recorded symptom ("Test host process crashed", unlocated
   `kotlin.NullPointerException`) exactly, and no other step in the chain can produce a Kotlin
   exception, but nobody has run it with a debugger attached.

The managed `NotSupportedException` is therefore thrown and then **overwritten** by the crash in
`finally`. The identical shape exists on the property-setter route (`setterPrelude`,
`ForwardCirPropertyProjection.kt:452-462`, same `HandleOf(out owned)` and the same finally-dispose)
and, since [ADR-132](132-extension-receiver-shapes.md), on an interface-typed extension receiver,
which shares the parameter prelude and cleanup. Any interface that plans to `null`
(`ForwardInterfaceBridgePlanner.plan`, an out-of-vocabulary member such as a `var`) has the same
crash today at every one of those positions, reachable or not; that residual case is the
ROADMAP-archive item "an interface with a `var` property ... silently gets no bridge factory".

## Alternatives Considered

### 1. Widen the reachability walk to parameter positions, and make the transfer cleanup throw-safe (chosen)

Two small changes, one per half of the contract.

**(a) Reachability.** `reachableInterfaceNames` also walks every plan's
`publicSignature.parameters` (unwrapping `Nullable`), so a parameter-only interface joins
`reachableInterfaces` and gets everything a return-reachable one gets: the ADR-040 backing wrapper
and `foo_*` dispatch exports, the collision check, the ADR-084 bridge plan, state class and factory
export. This is the design ADR-084 already claims to have.

- Pro: one site, five lines, in `NugetProcessor.kt`. Every downstream projection is unchanged.
- Pro: the parameter-only interface now behaves exactly like `Pet` does today, so Row 6 / 6b of
  `LiveHandleTests.cs` already describe its handle lifecycle.
- Con: the backing wrapper and its `foo_*` dispatch exports are dead for a parameter-only
  interface (nothing returns it, so nothing constructs the wrapper). They are additive permanent ABI
  surface under ADR-055; the cost per interface is the one ADR-040 sub-decision C already accepted.

**(b) Throw safety.** `HandleOf(object, out bool owned)` assigns `owned` only after the inner call
returns (`IntPtr handle = HandleOf(value); owned = true; return handle;`), and `interfaceCleanup`
gains the same `!= IntPtr.Zero` guard `collectionCleanup` has. Either change alone closes the crash;
both are cheap and each protects against the other regressing. With (b) in place an interface that
plans to `null` fails with the managed `NotSupportedException` step 3 already throws, at every
position, instead of killing the host.

### 2. Bridge plan from the parameter-reachable set, backing wrapper still from the return-reachable set

Keep `interfaceBackingClasses` as is and thread a second, wider list into `CirTranslator` and the
Kotlin export builder for the bridge plans only. Avoids the dead wrapper.

- Pro: no dead `foo_*` exports.
- Con: a new `translate(...)` parameter, a second list in `NugetProcessor.kt`, a second filter at
  `CirTranslator.kt:661` and `NugetProcessor.kt:1562`, and the collision check at `:492` has to be
  taught which list it guards. Three files and a signature change for a saving that ADR-040 already
  decided was not worth optimising for.
- Con: diverges from ADR-084's stated rule and from the `CirTranslator` comment for no consumer
  visible difference.

Rejected: more surface for the same consumer outcome. Revisit only if ADR-055's contract size ever
becomes a concern.

### 3. Refuse the callable with a named skip (`SKIPPED_UNSUPPORTED_INPUT`)

Emit a diagnostic for any callable whose interface-typed parameter is not return-reachable, and drop
the callable.

- Con: a regression. A parameter-only interface already works today when the argument is a
  Kotlin-backed object (`INugetHandle`, e.g. a `Cat` passed as `IPet`); refusing the callable would
  remove a shipped member to fix a C#-implementation crash.
- Con: contradicts ADR-084's own scope.

Rejected.

### 4. Alternative (b) only

Fix the crash and leave the parameter-only interface unbridged (managed `NotSupportedException` at
runtime).

- Con: satisfies "never a host crash" but not "a working ADR-084 bridge or a named diagnostic": the
  consumer gets a runtime exception naming the C# type and nothing at build time.

Rejected as the end state; it is the first commit of alternative 1.

## Decision

Alternative 1. Consumer view, against a fixture with only a parameter position:

```kotlin
// test-library, nested and top-level twins
object Registry {
  interface Clerk { fun stamp(): String }        // parameter position only
  fun fileVia(clerk: Clerk): String = "${clerk.stamp()} filed"
}
interface Greeter { fun greet(): String }         // top-level, parameter position only
fun greetWith(greeter: Greeter): String = "${greeter.greet()}!"
```

```csharp
// generated: Registry.IClerk / IGreeter as today; new: RegistryClerkBridgeState, GreeterBridgeState,
// registry_clerk_bridge_create, greeter_bridge_create, plus the ADR-040 Registry.Clerk / Greeter wrappers
private sealed class DeskClerk : Registry.IClerk
{
    public string Stamp() => "stamped";
    public void Dispose() { }
}

[Fact]
public void ParameterOnlyNestedInterface_CSharpImplementation_Bridges()
{
    var clerk = new DeskClerk();
    Assert.Equal("stamped filed", Registry.FileVia(clerk));
}
```

And the residual, plans-to-null case pinned as a managed throw rather than a crash:

```kotlin
interface Ledger { var balance: Int }             // `var`: out of ADR-084's v1 slot vocabulary
fun audit(ledger: Ledger): Int = ledger.balance
```

```csharp
[Fact]
public void UnbridgeableInterface_CSharpImplementation_ThrowsNotSupported()
{
    var ex = Assert.Throws<NotSupportedException>(() => TopLevel.Audit(new CSharpLedger()));
    Assert.Contains("CSharpLedger", ex.Message);
}
```

### Mechanism

- **Reachability (verified by reading the site to change).** `NugetProcessor.kt:1165-1174` adds a
  walk of `plan.publicSignature.parameters` through the existing
  `interfaceQualifiedNameOrNull()` helper. **Inferred**: whether an ADR-132 extension receiver's
  type is present in `publicSignature.parameters` or only on the native call's RECEIVER-role slot
  (`ForwardMarshallingModel.kt:332, :491, :631`); the implementer must confirm and walk both if
  they differ, or a receiver-only interface stays unbridged.
- **Kotlin half (verified by reading).** No change: `NugetProcessor.kt:1558-1565` already builds
  `addInterfaceExports` and the bridge factory from `reachableInterfaces`.
- **C# half (verified by reading).** No change: `CirTranslator.kt:409/492/661` already consume
  `interfaceBackingClasses`.
- **Throw safety (verified by reading).** `CirMarshalRenderer.kt:508-517` reorders the `owned`
  assignment; `ForwardCirPlanProjection.kt:721-722` and the setter cleanup in
  `ForwardCirPropertyProjection.kt` add `&& xHandle != IntPtr.Zero`. `HandleOfOrZero` (`:519-527`)
  already sets `owned = false` on the null path and delegates otherwise, so it inherits the fix.
- **Diagnostics.** None new. A parameter-only interface with in-vocabulary members now bridges; one
  with an out-of-vocabulary member still plans to `null` silently at build time and throws
  `NotSupportedException` at runtime. The build-time named skip for that case stays the
  ROADMAP-archive item (needs `ForwardInterfaceBridgePlanner.plan` to return a reason, out of scope
  here).

### Leak harness

No new handle kind: a parameter-only interface mints the same per-crossing transfer `StableRef`
Row 6 (`CSharpImplementedInterface_Argument_ReturnsToBaseline`) already measures. One row is worth
adding for the throw path, since the fix changes what `finally` does when `HandleOf` throws:
`UnbridgeableInterface_Argument_ThrowsAndReturnsToBaseline` (assert the throw inside
`AssertNoLeak`), which pins that a failed mint neither leaks nor disposes anything.

## Consequences

- A parameter-only interface, nested or top-level, gets a wrapper, dispatch exports, and a bridge.
  Additive ABI under ADR-055; no existing entry point changes.
- ADR-084's Detection rule becomes true; the "return-reachability" paragraph in its 2026-09-13
  amendment and the matching note in ADR-133 should point here once accepted.
- `HandleOf(out owned)` semantics change: `owned` is `true` only when a handle was actually minted.
  No shipped call site depends on the old order (the only readers are the two `finally` blocks).
- Deferred: the build-time named diagnostic for an interface that plans to `null`; the
  `Task<T>`/`Flow<T>` identity asymmetry noted in the same ADR-084 amendment; the ADR-132 receiver
  question above if it turns out to be a separate walk.
- Files an implementation touches: `NugetProcessor.kt` (walk), `CirMarshalRenderer.kt` (`owned`),
  `ForwardCirPlanProjection.kt` and `ForwardCirPropertyProjection.kt` (zero guard),
  `test-library/.../nested/Aviary.kt` or a new fixture file (parameter-only nested and top-level
  interfaces, one plans-to-null interface), `IntegrationTests/NestedTypesTests.cs` or a new
  `InterfaceParameterTests.cs`, `LeakTests/LiveHandleTests.cs` (one row), a Tier 1 cell asserting
  the bridge factory export exists for a parameter-only interface, and the docs listed above.

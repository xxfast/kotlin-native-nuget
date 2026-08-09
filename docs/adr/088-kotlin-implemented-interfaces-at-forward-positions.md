# ADR-088: Bound C# interfaces at forward-pipeline positions: original-type identity, not re-projection

## Status

Accepted

## Context

ROADMAP Phase 13: "Surface a Kotlin-implemented C# interface at forward-pipeline positions": a
Kotlin class implementing a bound C# interface (`test.menagerie.IFeedable`, the ADR-070 stub)
passed to, or returned from, an ordinary *forward*-direction Kotlin API, for example:

```kotlin
// test-library, exported package: the author's own public API
class Farm {
  fun adopt(feedable: IFeedable)      // parameter position
  fun resident(): IFeedable           // return position
}
```

ADR-085 wired only the reverse pipeline's own crossing points (`Sanctuary.Introduce` etc.) and
explicitly deferred this.

### What the forward pipeline does today (scope reality-check, all verified in source)

1. **The declaration above does not compile today.** The ADR-070 stub interface is emitted
   `internal` (`NugetGenerateBindingsTask.kt:4402`: `|internal interface ${iface.name}...`;
   confirmed in fresh generated output, `test-library/build/nuget-interop/kotlin/nativeMain/test/
   menagerie/IFeedable.kt:6`, built 2026-08-08 23:41 on this branch). A public Kotlin function
   exposing an internal type in its signature is a compile error (**inferred**: standard Kotlin
   visibility rules, `EXPOSED_PARAMETER_TYPE`/`EXPOSED_FUNCTION_RETURN_TYPE`,
   https://kotlinlang.org/docs/visibility-modifiers.html; not spiked, but it only strengthens the
   prerequisite: today the feature is *unreachable by users*, not silently skipped). So there is
   no "what does the planner do" question yet; the library author cannot write the declaration.
   ADR-085's own fixture keeps `Goat` `private` for exactly this reason
   (`MenagerieSample.kt:104`).

2. **KSP does see the generated stubs.** The reverse bindings are wired as a `srcDir` of
   `nativeMain` and `kspKotlin{Target}` depends on `nugetGenerateBindings`
   (`NugetPlugin.kt:184-205`). They are ordinary module-local source to the processor
   (`containingFile != null`).

3. **Bound packages are already always in forward scope** (ADR-063): `isPackageExported` checks
   `context.boundPackages` first (`NugetProcessor.kt:236-245`), threaded from the plugin as the
   KSP option `nuget.boundPackages` (`NugetPlugin.kt:251-269`). The cross-pipeline knowledge
   channel this feature needs already exists; it just carries too little data (a flat package
   list, no C# namespace, no per-type bridgeability).

4. **The shipped precedent for a public bound type at a forward position is duplication.** Enum
   stubs are generated `public` (`NugetGenerateBindingsTask.kt:1552`), so
   `fun catMoodRoundTrip(): CatMood` is legal, and the forward pipeline re-projects the stub into
   a **new** C# enum: fresh `Interop.cs` contains `namespace TestLibrary.Test.Enums` with its own
   `CatMood`, and `catMoodRoundTrip()` returns `global::TestLibrary.Test.Enums.CatMood`, not the
   original `Test.Enums.CatMood` the consumer already has from TestDependency (verified,
   `Interop.cs:8078-8098, 12080`). Tolerable for a value-copied enum; wrong for an interface: a
   duplicated `IIFeedable` (the classifier would name it `I` + simpleName,
   `ForwardBridgeTypeClassifier.kt:187-210`) could never be passed to `Sanctuary.Introduce`,
   which wants the real `Test.Menagerie.IFeedable`.

5. **Every marshalling primitive already exists, internal, in the same compilation.** The
   reverse-generated `nugetIFeedableValue(ptr)` resolves an incoming GCHandle IntPtr to a Kotlin
   `IFeedable`, including ADR-085's token probe (a Kotlin-implemented bridge resolves to the
   original Kotlin object, and the fresh transfer handle is freed on a probe hit) with an
   `IFeedableHandle(ptr)` fallback; `mintIFeedableBridge(impl)` turns a plain Kotlin
   implementation into a C#-side bridge behind a fresh transfer GCHandle (verified, generated
   `IFeedableBindings.kt:89-118`). Forward-generated Kotlin (`CNameExports.kt`) compiles into the
   same module, so `internal` is no obstacle on the Kotlin side. Forward-generated C#
   (`Interop.cs`) and the reverse shims ship in the same consumer assembly (one TestLibrary
   package feeds both `catMoodRoundTrip` and `Sanctuary` in IntegrationTests), so forward C# can
   reference `Test.Menagerie.IFeedable` and the shim helpers directly.

So the item is neither "map a known type kind" (the type identity must cross pipelines) nor
"teach two pipelines about each other from scratch" (ADR-063 built the channel; ADR-070/085 built
the marshalling). It is: widen the channel, flip one visibility, and add one classifier branch
plus emitter routes.

## Alternatives Considered

### 1. Bound-type classification with original C# identity (chosen)

The forward classifier learns `BridgeType.BoundInterface(kotlinName, csharpType =
"global::Test.Menagerie.IFeedable")`: the forward C# signature names the **original** bound
interface from the consumer's own dependency, and the wire is a GCHandle IntPtr marshalled
through the reverse pipeline's existing helpers.

Pros: the consumer sees one `IFeedable`, composable with the bound `Sanctuary` API; matches
GOALS ("feels like C#, never like a wrapper"); matches how Kotlin's other exports treat foreign
types (a Kotlin JVM API exposing a Java type surfaces the original Java type; Kotlin/Native's
ObjC export references a cinterop-imported ObjC class as itself in the framework header rather
than wrapping it — **inferred** from platform behaviour, not load-bearing here, the in-repo
GOALS argument stands alone). Cons: prerequisite visibility flip; new classifier/emitter routes;
a manifest to carry C# names across pipelines.

### 2. Re-projection (extend the enum precedent)

Let the stub go public and let the forward pipeline duplicate it as `TestLibrary.Test.Menagerie.
IIFeedable` + backing wrapper, converting at the boundary.

Pros: no classifier change, mechanical. Cons: two managed types for one concept; a value obtained
from the forward API cannot be passed to the bound API (or vice versa) without a conversion layer
that itself needs the chosen alternative's machinery; `IIFeedable` naming is absurd. The enum
duplication was accepted in ADR-063 as a correctness stopgap, not a design to extend. Rejected.

### 3. Status quo, plus a named skip

Keep stubs internal; the feature remains impossible and undiagnosed (the user's code simply does
not compile, with a standard Kotlin visibility error). A processor-side named skip is unreachable
because the declaration cannot exist. Rejected as a "design", but it is the honest fallback if
the prerequisite work is descoped.

## Decision

Adopt Alternative 1.

### Consumer API (end state)

```csharp
// C# consumer: one IFeedable, the real Test.Menagerie.IFeedable from TestDependency
var farm = new Farm();
farm.Adopt(new CSharpGoat());                       // any managed IFeedable implementation
Test.Menagerie.IFeedable r = farm.Resident();
Assert.Same(csharpGoat, farm.Resident());           // C#-implemented: original instance back
var sanctuary = new Sanctuary();
sanctuary.Introduce(r);                             // composes with the bound API, same type
```

```kotlin
// Kotlin author: IFeedable is now a public generated interface
class Farm {
  private var resident: IFeedable = Goat()
  fun adopt(feedable: IFeedable) { resident = feedable }   // Goat or IFeedableHandle, either works
  fun resident(): IFeedable = resident
}
```

### Prerequisite: stub interface visibility

`NugetGenerateBindingsTask` emits the **pure interface stub** (`:4402`) as `public`. The handle
class (`IFeedableHandle`, `:4532`), class stubs (`:3218`), and bindings objects stay `internal`.
Only the pure interfaces are needed in user signatures, and keeping the rest internal is what
keeps them out of the forward export scan (the root buckets admit only
`Visibility.PUBLIC` declarations, `NugetProcessor.kt:330-366`, verified).

**Guard against re-projection**: once public, a bound-package interface would enter
`rootInterfaces` and be duplicated as `IIFeedable` (mechanism verified: bucket construction +
`interfaceType` naming). Add a bound-package exclusion to the root-interface bucket (the
predicate data already exists via `nuget.boundPackages`). Bound **enums** keep today's shipped
duplication behaviour, explicitly unchanged by this ADR (see Deferred).

### Cross-pipeline type manifest

`nuget.boundPackages` (a flat package list) cannot produce `global::Test.Menagerie.IFeedable`
from `test.menagerie.IFeedable` (the alias map is not invertible from the package list alone),
nor say which interfaces are bridgeable. The plugin, which already knows both sides when it
generates the stubs, additionally writes a small manifest (e.g.
`build/nuget-interop/bound-types.json`) listing, per bound interface: the Kotlin qualified name,
the original C# full name, and whether it is Kotlin-implementable (ADR-085 admissibility, i.e. a
`mint{Iface}Bridge` exists; `ITagged` today has `nugetITaggedValue` but **no** mint, verified in
generated output). The manifest path rides a new KSP option, mirroring the existing
`nuget.boundPackages` threading (`NugetPlugin.kt:251-269`); the ordering dependency is already
guaranteed (`kspKotlin{Target}` dependsOn `nugetGenerateBindings`, verified). The processor reads
it into `ForwardBridgeTypeContext` and the classifier branches to `BridgeType.BoundInterface`
**before** the `exportedObjectHandles` membership check.

### Bridge mechanism per position

Wire type: GCHandle IntPtr, the reverse pipeline's existing reference wire. One ownership rule,
same as ADR-086: **the receiving side owns the handle; every crossing is a fresh transfer
handle.**

- **Forward parameter (C# → Kotlin).** Generated C# wrapper: `GCHandle.Alloc(feedable)` →
  IntPtr → native call (the exact pattern the reverse shims already use, verified). Generated
  Kotlin export: parameter lowers through `nugetIFeedableValue(ptr)` (verified behaviour: token
  probe frees the transfer handle and returns the original Kotlin object; otherwise
  `IFeedableHandle(ptr)` takes ownership and its ADR-070 cleaner frees it). Kotlin may store the
  value; nothing dangles.
- **Forward return (Kotlin → C#).** Generated Kotlin export lowers the returned `IFeedable`:
  - plain Kotlin implementation → `mintIFeedableBridge(impl)`, already a fresh transfer handle
    (verified);
  - `IFeedableHandle`-backed value (C# owns the object) → a **duplicate** GCHandle over the same
    managed target, because the stub's own stored handle is owned by its cleaner and must not be
    handed to C# to free. This is ADR-086's per-interface `dupHandleFn` thunk (Proposed, not yet
    shipped). If ADR-086 lands first, compose; otherwise this ADR ships that thunk. The
    mechanism, `GCHandle.Alloc(GCHandle.FromIntPtr(h).Target)`, is **inferred** (elementary .NET;
    the reverse shims already alloc GCHandles over managed targets, but no repo code performs
    this exact dup yet).

  Generated C# wrapper: `var gc = GCHandle.FromIntPtr(p); var v =
  (global::Test.Menagerie.IFeedable)gc.Target!; gc.Free(); return v;` after the usual error-out
  check.

### Identity (composes, nothing new promised)

- A C#-implemented `IFeedable` passed in and returned back is the **same managed instance**
  (GCHandle target identity, both crossings preserve the target).
- A Kotlin implementation returned to C# is a fresh bridge per crossing; C#-side
  `ReferenceEquals` across two returns of the same Kotlin object is **not** promised (ADR-085's
  shipped posture, unchanged).
- A Kotlin implementation passed back into a forward parameter resolves to the original Kotlin
  object via the token probe (verified in `nugetIFeedableValue`).

### Diagnostics

A bound interface at an unsupported position (property, collection component, nullable, lambda,
`Flow`, receiver) produces a named ADR-064 forward diagnostic (`SKIPPED_BOUND_TYPE_POSITION`,
naming the position and the original C# type), never silence. A bound interface that is not
Kotlin-implementable (`ITagged`: no mint) is admissible at **parameter** positions (only
`nugetITaggedValue` is needed) but a **return** of a plain Kotlin implementation of it cannot be
lowered; v1 keeps it simple: only manifest-flagged Kotlin-implementable interfaces are admissible
at return positions, others named-skip there.

### Scope

**v1:** non-nullable bound interfaces at ordinary forward **function/method/constructor
parameters** and **method / top-level function returns**. Interfaces only, and only those the
manifest lists (ADR-070-admissible, with returns further gated on Kotlin-implementability as
above).

**Deferred (each a named ROADMAP candidate), shipped as a named skip rather than silence, per the
addendum below:**
- Nullable bound interfaces (`IFeedable?`; the null-pointer ride is natural but adds emitter
  routes in four positions). Shipped skip: `SKIPPED_BOUND_TYPE_POSITION`.
- Bound interfaces at property positions and as collection components. Shipped skip:
  `SKIPPED_BOUND_TYPE_POSITION` at both.
- Bound **classes** at forward positions (`fun sanctuary(): Sanctuary`): same handle mechanics,
  needs the class stub public and the internal `(COpaquePointer)` constructor route; bigger
  visibility blast radius, so explicitly out.
- Bound **enum** identity unification (replacing today's `TestLibrary.Test.Enums.CatMood`
  duplication with the original `Test.Enums.CatMood`): **warning for whoever picks this up**: the
  Kotlin stub is ordinal-backed (verified, generated `CatMood.kt` header comment) and the forward
  wire is the ordinal, so a naive `(Test.Enums.CatMood)ordinal` cast silently corrupts values for
  any C# enum whose constants are not sequential-from-zero (**inferred**; the reverse shims
  translate value↔ordinal on their side and that translation would need to be crossed too).
- Derived interfaces (`ITagged : IFeedable`) at return positions with a Kotlin implementation
  (blocked on ADR-085's open derived-flattening item).

## Consequences

- ADR-070's generated pure interfaces become part of the consumer library's *Kotlin-facing* API
  surface (they were designed pure precisely so a Kotlin class can implement them; going public
  is the natural completion). `GeneratedBindingsCheck` golden files and any snapshot asserting
  `internal interface` need regeneration.
- The forward pipeline gains its first type whose C# spelling it does not own. The manifest is
  the single source of that spelling; slot/ABI contracts are untouched (no new registration
  slots except `dupHandleFn` if ADR-086 has not shipped first, which bumps only the affected
  interface's own contract tag, per ADR-086's design).
- The enum-duplication precedent stays, now clearly labelled as a stopgap this ADR deliberately
  did not extend.

## Mechanism claims ledger

**Verified (source or fresh generated output read directly, 2026-08-08 build):**
- Stub visibility split: interfaces/classes/handles `internal`
  (`NugetGenerateBindingsTask.kt:3218/4402/4532`), enums `public` (`:1552`); confirmed in
  generated `IFeedable.kt:6`, `Sanctuary.kt:31`, `CatMood.kt`.
- Forward duplication of a public bound enum: `TestLibrary.Test.Enums.CatMood` in fresh
  `Interop.cs` (`:8078-8098`, `:12080`), signatures use the duplicate.
- `boundPackages` channel: predicate (`NugetProcessor.kt:236-245`), plugin threading
  (`NugetPlugin.kt:251-269`).
- Root buckets admit only PUBLIC declarations (`NugetProcessor.kt:330-366`);
  `exportedObjectHandles` = the buckets (`:449-460`); classifier interface naming `I$simpleName`
  (`ForwardBridgeTypeClassifier.kt:187-210`).
- Generated-source wiring: `srcDir` + `kspKotlin{Target}` dependsOn `nugetGenerateBindings`
  (`NugetPlugin.kt:174-205`).
- Marshalling helpers and their ownership behaviour: `nugetIFeedableValue` (token probe, frees
  transfer handle on hit, `IFeedableHandle` fallback), `mintIFeedableBridge` (fresh transfer
  handle) — generated `IFeedableBindings.kt:89-118`; `ITagged` has value-resolution but no mint.
- Forward `Interop.cs` and reverse shims compile into one consumer assembly (single TestLibrary
  package serving both fixture families in IntegrationTests).

**Inferred (stated in the confident-red register where load-bearing):**
- `EXPOSED_PARAMETER_TYPE` being a compile error for a public API over an internal stub: standard
  Kotlin visibility rule, not spiked. If somehow wrong, the consequence is that today's state is
  a silent planner skip instead of a compile error; the design is unchanged either way.
- The GCHandle **duplicate** thunk (`GCHandle.Alloc(GCHandle.FromIntPtr(h).Target)`) behaving as
  described: elementary .NET, but **nobody has executed this exact dup in this repo**; it is also
  the load-bearing mechanism of ADR-086 (Proposed). If it is wrong, forward returns of
  C#-implemented objects would double-free or dangle: the implementing agent must cover it with a
  deterministic release test (the ADR-085 pattern) in the first commit that emits it.
- Ordinal↔value mismatch for non-sequential C# enums in the deferred enum-unification item.

## Implementation Addendum (2026-08-09)

Corrections found while shipping, none changing the Decision's shape.

**"Emit the stub as public" was wrong as stated.** A public interface over an internal class stub
does not compile, and the real build caught it on `IKeeper` (`TestDependency/Menagerie.cs`), whose
members take and return `Ferret`, a bound class stub that stays `internal` (bound classes at forward
positions are still deferred, see Scope). Shipped instead: `interfaceStubIsPublic` (
`NugetGenerateBindingsTask.kt:259`), a predicate over the interface's own declared surface plus its
bases, public iff nothing in that surface is an internal bound-class/struct/generic-instance stub
and every base interface is itself public. `IKeeper` stays `internal` and off `bound-types.json`;
`IFeedable`, `ITagged`, and `IPerformer` (whose surfaces name only primitives, `String`, and each
other) go public.

**The "`ITagged` has no mint" claim went stale mid-batch.** It was true when this ADR was drafted,
but [ADR-086](086-object-interface-slots-kotlin-bridge.md)'s derived-interface flattening landed
first in the same batch and gave `ITagged` a flattened `mintITaggedBridge`. `bound-types.json` in
this repo's own fixture now lists all three bound interfaces (`IFeedable`, `ITagged`, `IPerformer`)
as `"implementable": true`; `SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE` is real and shipped (
`ForwardDiagnosticKind`, `ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE`) but has no example
left in this project's own fixtures to fire against: it is pinned instead by
`Tier1BoundInterfacePositionTest`'s synthetic `implementable = false` manifest entry. The
`implementable` manifest flag itself is unchanged and load-bearing at return positions.

**ADR-086's dup thunk widened from conditional to unconditional per plannable interface.**
`KotlinBridgePlan.needsDupHandle` (`RirBridging.kt:1049`) defaults to `true` for every interface
this ADR plans a bridge factory for, not only ones with a handle-out **slot**. A forward return
(`fun resident(): IFeedable`) is not a slot at all, so slot shape can no longer gate the thunk's
registration; the cost is one extra registration pointer per plannable interface, and both
generators derive it from the same `KotlinBridgePlan`, so the ADR-054 contract hash moves in
lockstep on both sides.

**The dup-spike caveat is obsolete.** [ADR-086](086-object-interface-slots-kotlin-bridge.md) shipped
the `GCHandle.Alloc(GCHandle.FromIntPtr(h).Target)` duplicate thunk before this ADR landed, so the
"nobody has executed this exact dup" caveat above no longer applies; `Farm_Adopt_...SameInstanceBack`
(`IntegrationTests/BidirectionalTests.cs`) exercises it live via `Assert.Same(goat, farm.Resident())`
on every forward return of a stored C#-implemented resident.

**Transitive reachability confirmed, not merely assumed.** `Test.Menagerie` resolves inside
`IntegrationTests` even though the project has no direct `PackageReference` to `TestDependency`:
`TestLibrary`'s own generated `.nuspec` declares `TestDependency` as a package dependency with no
`exclude`, so NuGet's transitive restore surfaces `TestDependency.dll` as a compile asset (confirmed
via `IntegrationTests/obj/project.assets.json`, `TestDependency/<version>` under `net10.0`'s
`compile` assets), noted in the test header directly above the `Farm_*` fixtures.

**One asymmetry for the record, left as shipped rather than reconciled.** A property-position bound-
interface skip routes through a `boundInterface: Boolean` flag on `ForwardDroppedProperty`
(`NugetProcessor.kt`'s `warnDroppedForwardProperties`) rather than through `ForwardPlanSkipReason`,
the enum every parameter/return skip uses. Properties already had their own drop-tracking shape
(`ForwardDroppedProperty`, predating this ADR) independent of the callable catalog's skip-reason
enum, so the flag composes with the existing shape rather than routing properties through a second,
parallel enum. Documented as-is; a later pass may unify the two skip-reporting shapes, but that is
out of this ADR's scope.

**Non-exhaustive `when` sites needed an explicit branch, not just a new sealed variant.**
[ADR-076](076-instant-mapping.md)'s lesson (choose a *new* `BridgeType` variant so the compiler
enumerates every `when`) held for every **exhaustive** `when (type)` in the forward pipeline: adding
`BridgeType.BoundInterface` failed the build at every one of those until a branch was added, exactly
as intended. It does not help a `when` that already has a catch-all `else`, which compiles either way
and silently routes the new variant into whatever the `else` does. Three such sites had to be found
and patched by inspection rather than by the compiler:

- `ForwardCallablePlanner.skipReason()`'s `is BridgeType.Nullable` arm (`ForwardCallablePlanner.kt:1801`):
  without an explicit `BoundInterface` check, `IFeedable?` at a return position would have reported
  the generic `ForwardPlanSkipReason.NULLABLE` (a hint about `Boolean`) instead of
  `BOUND_INTERFACE_POSITION`.
- `ForwardCallablePlanner.inputSkipReason()`'s `is BridgeType.Nullable` arm (`ForwardCallablePlanner.kt:1897`):
  the same swallow, at the parameter side.
- `ForwardMarshallingModel.requiredConversion()` (`ForwardMarshallingModel.kt:508`, terminal
  `else -> null`): without the explicit `BoundInterface` branch, the validator would have read "no
  conversion required" for a type whose transfer is tagged `GC_HANDLE_TO_BOUND_VALUE`/
  `BOUND_VALUE_TO_GC_HANDLE`, and rejected the plan as carrying an "unnecessary conversion", a
  build-time crash rather than silent wrong output, but still a site the compiler could not catch.

Recorded as a new ROADMAP item (Tooling & Test Integrity) rather than folded into the ADR-062
"route legacy protocols through `BridgeType`" item, since it is a distinct hazard: exhaustive-`when`
coverage, not `BridgeType` adoption.

# ADR-113: Project the generated C# interface from the forward plan, not from Kotlin simple names

## Status

Accepted

## Context

Reported by [#112](https://github.com/xxfast/kotlin-native-nuget/issues/112) against `main` @ 6f09100,
found by consuming the plugin from a real project. `packNuget` is green with this in it; the failure
only appears when the generated `Interop.cs` is compiled by the consumer.

```kotlin
interface Advertisement {
  val identifier: Uuid
  val manufacturerData: ManufacturerData?
  val uuids: Collection<Uuid>
  fun manufacturerData(code: Int): ByteArray?
}
```

Generated C#:

```csharp
public interface IAdvertisement : IDisposable
{
    IntPtr Identifier { get; }
    IntPtr? ManufacturerData { get; }
    IntPtr Uuids { get; }

    IntPtr ManufacturerData(int code);
}
```

The reporter's errors: CS0102 (a property and a method both named `ManufacturerData`), CS0738 x2
(the implementing class's real wrapper types do not match `IntPtr`), CS0535 x2 (the class implements
neither `Uuids` nor `ManufacturerData(int)`, because the class route skipped both with
`SKIPPED_UNSUPPORTED_*`).

### The single root cause

**Verified by reading `nuget-processor/src/main/kotlin/.../cir/CirClassTranslator.kt` in this repo at
6f09100.** `translateInterface` (`:1455`) has the signature

```kotlin
internal fun translateInterface(
  iface: KSClassDeclaration,
  libraryName: String,
  logger: KSPLogger,
): CirInterface
```

It takes **no `ForwardCallablePlanCatalog`**. It walks `iface.getAllProperties()` /
`iface.getAllFunctions()` itself and projects each member's C# type by *Kotlin simple name string*
through `mapInterfacePropertyType` (`:1749`), `mapReturnType` and `mapParamType`.

`mapParamType` (`cir/CirTypeMapping.kt:158`) is, **verified**, exactly:

```kotlin
internal fun mapParamType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"
```

So every reference type that is not in the primitive table falls to the literal string `IntPtr`.
That is sub-problem (1) in full. There is no filter of any kind on the member list, which is
sub-problem (2) in full.

Meanwhile `translateInterfaceBackingClass` (`:1530`, ADR-040's concrete handle-backed wrapper) **does**
take `callableCatalog`, and projects each member through `callableCatalog.propertyFor(symbol)` /
`callableCatalog.planFor(symbol)` with `mapNotNull`, so an unplanned member simply disappears
(**verified by reading `:1530-1570`**). Its KDoc already says every member "already has a
dispatch-export plan built by `ForwardCallablePlanner.interfaceEntries` /
`ForwardPropertyPlanner.interfaceProperties`".

The interface declaration and every implementation of it are therefore projected from **two different
sources of truth**, and only one of them knows about bridge types and skips. The lead hypothesis in
the issue triage is confirmed.

Both call sites are in `cir/CirTranslator.kt`: `:377` (`interfaces.forEach { translateInterface(...) }`)
and `:400` (`interfaceBackingClasses.forEach { translateInterfaceBackingClass(..., callableCatalog, ...) }`).
**Verified by reading.**

### The complication: `interfaces` is not `interfaceBackingClasses`

**Verified by reading `NugetProcessor.kt:735-760`.** Interface plans are computed only for
`reachableInterfaces`, the ADR-040 sub-decision C.1 subset of interfaces that appear in a planned
*return* position:

```kotlin
val reachableInterfaces: List<KSClassDeclaration> = interfaces
  .filter { iface -> iface.qualifiedName?.asString() in reachableInterfaceNames }

val interfaceEntries = reachableInterfaces.flatMap { forwardPlanner.interfaceEntries(it) }
val interfacePropertyPlans = reachableInterfaces.flatMap { forwardPropertyPlanner.interfaceProperties(it) }
```

`translateInterface`, by contrast, runs for **every** exported interface (`CirTranslator.kt:377`).
ADR-040 was explicit that "`IFoo` itself stays unconditional, so no existing C# API disappears".

Naively passing today's `callableCatalog` into `translateInterface` would therefore empty out every
non-reachable interface: `propertyFor`/`planFor` would return `null` for all of its members and
`mapNotNull` would drop the lot. That is a silent, total API regression for any interface that is only
implemented, never returned. This is the load-bearing constraint on the design, and it is why the
narrow-looking "just thread the existing catalog through" is wrong.

Note also that the class in issue #112 that fails CS0738/CS0535 is a *user-written exported Kotlin
class* implementing `Advertisement`, projected by the ordinary class route (`translateClass`), not
ADR-040's backing wrapper. So the members `IFoo` must agree with are the ones the **ordinary class
plans** produce. Those come from the same `ForwardBridgeTypeClassifier` instance as the interface
plans (**verified**: `NugetProcessor.kt:776-777` constructs both `ForwardCallablePlanner` and the
second `ForwardPropertyPlanner` with the same `forwardClassifier`), so projecting `IFoo` from the
interface plan yields byte-identical C# type spellings to what the implementing class emits for the
same Kotlin member.

### Member shapes the plan has no entry for (question 1)

**Verified by comparing the filters.** `interfaceEntries` (`ForwardCallablePlanner.kt:782`) and
`interfaceProperties` (`ForwardPropertyPlanner.kt:163`) both carry
`.filter { it.parentDeclaration == iface }`. `translateInterface` carries no such filter, so it
uses `getAllProperties()` / `getAllFunctions()` **including inherited super-interface members**.

That is the one member shape the plan has no entry for. Today an interface `Advertisement : Named`
redeclares `Named`'s members on `IAdvertisement`, and `CirInterface` has no super-interface list at
all (**verified**: `CirModel.kt:22-28` has `name`, `typeParameters`, `properties`, `methods`, nothing
else; `CirClassRenderer.kt:139` renders `public interface IFoo : IDisposable` with no other base).
So `IAdvertisement` redeclares them while ADR-040's backing class, which filters to declared-only,
does not implement them: a latent CS0535 that predates this ADR.

Everything else `translateInterface` emits has a plan entry, including a member that is `suspend` or
generic (planned as `ForwardCallableCatalogEntry.Skipped`, **verified** at `ForwardCallablePlanner.kt:797-805`).

### Existing backlog items this closes (question 4)

- `docs/backlog/translateinterface-no-bridgeability-filter.md` (`ROADMAP.md:60`): "`translateInterface`
  emits every public interface member with no bridgeability filter, so an unbridgeable interface member
  yields CS0535". **Closed** by this ADR: that is sub-problem (2) exactly.
- `docs/roadmap-archive.md:66` records `mapInterfacePropertyType` being patched once before, for
  nullability (ADR-040 era). This ADR retires the function rather than patching it a third time.

Not closed, and deliberately left open:

- `docs/backlog/interface-route-overload-numbering.md` (`ROADMAP.md:53`): `interfaceEntries` has no
  overload numbering. Independent, and untouched here.
- `docs/backlog/interface-var-property-any-other-member-outside.md` (`ROADMAP.md:211`): ADR-084's
  bridge factory silently plans to `null`. A different planner (`ForwardInterfaceBridgePlanner`),
  untouched here.

## Alternatives Considered

### 1. Project `CirInterface` from a plan catalog computed over *all* exported interfaces (chosen)

Give `translateInterface` a catalog, and build that catalog from **every** exported interface, not
just the reachable ones, so no `IFoo` loses members it has today for reachability reasons.

The export-emitting catalog (`callableCatalog`) stays reachability-driven and byte-identical, so the
native ABI, the `@CName` export set and the ADR-055 contract hash do not move. The new catalog is a
declaration-shaping input only.

Pros: one source of truth for what a member's C# type is, shared with the implementing class; all
three sub-problems fall out of the same change; skips become structural (`mapNotNull`), so the
bridgeability filter is not a second hand-written predicate that can drift.

Cons: interface members of reachable interfaces get planned twice (once into `callableCatalog`, once
into the declaration catalog). Planning is pure, but the second planner instance's drop channels
(`droppedProperties`, `droppedPropertySetters`) must **not** be merged into the diagnostic channels,
or every reachable interface's skip is reported twice.

### 2. Thread the existing `callableCatalog` in unchanged

One-line call-site change. Rejected: as established above, it silently empties `IFoo` for every
non-reachable interface, which is a worse regression than the bug being fixed.

### 3. Keep the string-keyed projection, add a hand-written bridgeability filter

Fix sub-problem (2) only, by teaching `translateInterface` which Kotlin types are bridgeable.
Rejected: it does not fix sub-problem (1) at all (the surviving members still render `IntPtr`), and
it re-creates the exact class of bug the forward plan exists to prevent, a second predicate that has
to be kept in agreement with the planner's by hand. `docs/roadmap-archive.md:120` records the last
time two such predicates disagreed and the real member drop it caused.

### 4. Rename one side of the property/method name collision

For sub-problem (3), rename `fun manufacturerData(code)` to e.g. `ManufacturerData_1`.
Rejected, following ADR-110's precedent verbatim (**verified** at `CirTranslator.kt:27-40`):
"renaming either member would be a silently different API (ADR-034/ADR-082's diagnostic model)".
ADR-110 chose a fatal `ERROR_CSHARP_NAME_COLLISION` there, and that is the settled house rule for
CS0102.

## Decision

### A. `translateInterface` takes a plan catalog and projects from it

```kotlin
internal fun translateInterface(
  iface: KSClassDeclaration,
  libraryName: String,
  callableCatalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
): CirInterface
```

Its property and method lists become `mapNotNull` over `callableCatalog.propertyFor(symbol)` /
`callableCatalog.planFor(symbol)` keyed by `"${iface.qualifiedName}.${member.simpleName}"`, the same
symbol scheme `translateInterfaceBackingClass` already uses (**verified** at `CirClassTranslator.kt:1544`
and `:1558`).

Member C# types come from the plan, never from a Kotlin simple name:

- property type: `plan.type.csharpType()` (the same call `ForwardCirPropertyProjection.property` makes
  for the class, **verified** at `ForwardCirPropertyProjection.kt:62`)
- method return type and parameter types: `plan.publicSignature`, the same source
  `ForwardCirPlanProjection.classMethod` reads (**verified** at `ForwardCirPlanProjection.kt:346-390`)
- member name: `plan.publicName` / `plan.publicSignature.name`, so PascalCasing and any escaping are
  the plan's, not a local `replaceFirstChar`

`mapInterfacePropertyType` (`CirClassTranslator.kt:1749`) has no other caller (**verified by grep**:
its only reference is `:1483`) and is deleted.

**Inferred, not verified:** that `plan.type.csharpType()` and the class route's property projection
produce character-identical strings for the same Kotlin member. The reasoning is that both go through
the same `BridgeType` produced by the same `ForwardBridgeTypeClassifier` instance, which is verified;
but the two projections are separate call sites and only a build proves the rendered strings match.
**If this is wrong, the symptom is CS0738 again, in a different place**, so the C# tests below must
assert the interface member type spelling against the class member type spelling, not against a
hardcoded literal.

### B. Plan every exported interface's declaration, keep exports reachability-driven

In `NugetProcessor.kt`, alongside the existing reachable-only planning, build a second catalog over
all exported interfaces and pass only that one to `translateInterface`:

```kotlin
// Declaration-shaping only. ADR-040 keeps `IFoo` unconditional, so its member list cannot be
// reachability-driven the way the backing class and the `foo_*` exports are.
val interfaceDeclarationCatalog = ForwardCallablePlanCatalog(
  entries = interfaces.flatMap { declarationPlanner.interfaceEntries(it) },
  propertyPlans = interfaces.flatMap { declarationPropertyPlanner.interfaceProperties(it) },
  // Drop channels deliberately NOT merged: see D.
)
```

`callableCatalog` (the one that drives `addInterfaceExports`, the backing class and the ABI contract)
is unchanged. **Verified** that exports iterate the reachable list, not the catalog:
`NugetProcessor.kt:979` is `reachableInterfaces.forEach { builder.addInterfaceExports(it, callableCatalog) }`.

**Inferred, not verified:** that planning a non-reachable interface's members has no side effect
beyond the returned lists (no export registration, no contract entry). The planners read as pure
functions returning lists, but this was not executed. **If it is wrong, the symptom is new `@CName`
exports for interfaces that have no backing class**, which the ADR-055 contract check should catch at
startup rather than silently. The implementing agent should confirm the generated `.kt` export file
is byte-identical for a fixture with a non-reachable interface before and after.

### C. Skipped members are omitted silently from `IFoo` (question 2)

No new diagnostic. Rationale: the skip is already reported. For the issue #112 shape, the implementing
class's own route already emitted `SKIPPED_UNSUPPORTED_*` for `uuids` and `manufacturerData(code)`,
and for a reachable interface the interface planner's own drops are already merged into the diagnostic
channels (**verified** at `NugetProcessor.kt:762-772`, which merges `forwardPropertyPlanner.droppedProperties`
into the catalog explicitly for exactly this reason). A second diagnostic naming the same Kotlin
declaration is duplicate noise, and `ROADMAP.md:27` already records duplicate-diagnostic-per-hierarchy
as a known annoyance worth avoiding.

Residual hole, named as a follow-up rather than fixed here: an interface that is neither reachable nor
implemented by any exported class gets a silently thinner `IFoo` with nothing naming why.

### D. The new declaration planner's drops are not merged into the diagnostic channels

Because reachable interfaces are now planned twice, merging the declaration planner's
`droppedProperties` / `droppedPropertySetters` / `droppedExtensionReceivers` would double every
interface property diagnostic. They are discarded. The reachable path's merge at
`NugetProcessor.kt:762-772` stays exactly as it is.

### E. CS0102 property/method name collision is fatal, following ADR-110 (question 3)

**Verified**: ADR-034's guard is signature-based only, over *methods* against *methods*
(`CirClassTranslator.kt:249` for constructors, `:1171` and `:1690` for method overloads, all keyed
`listOf(method.name) + parameter types`). It has no concept of a property claiming a method's name,
so it cannot be reused here.

The applicable precedent is ADR-110's `ERROR_CSHARP_NAME_COLLISION`
(`ForwardDiagnostic.kt:210`, emitted by `emitCsharpNameCollisions` at `CirTranslator.kt:42`), which
handles exactly `val name` + `fun name()` on a generated container and is fatal with no rename.

`translateInterface` gains the same check over its own *post-filter* member lists: if any projected
method name equals any projected property name, emit

```
ERROR_CSHARP_NAME_COLLISION
  declaration: IAdvertisement.ManufacturerData
  reason: the interface property 'manufacturerData' already claims that C# name, and C# cannot
          declare a property and a method with one name (CS0102)
  hint: rename the Kotlin function 'manufacturerData' or the property it collides with
```

Post-filter matters: for issue #112's exact Kotlin, `fun manufacturerData(code: Int): ByteArray?` is
unbridgeable and is dropped by A, so the collision never fires and that build succeeds. The guard is
there for the case where both members survive the plan.

**Inferred, not verified:** that Roslyn reports CS0102 for a property and a method sharing a name in
an `interface` declaration, not only in a `class`. The C# spec rule is a member-name uniqueness rule
on the declaration space, which does not distinguish interfaces from classes, and the reporter's
error list in #112 names CS0102 against the generated interface. Treated as settled by the bug
report itself rather than by a compiler run here.

### Not decided here (explicitly out of scope)

- `CirInterfaceProperty.hasSetter` stays `false` for every member, exactly as today. A `var`
  interface property continues to render `{ get; }`. Deriving it from `plan.setter != null` would
  render `{ get; set; }` and risk CS0535 against an implementing class whose own setter was dropped
  by ADR-075's getter/setter independence. Follow-up item, below.
- Super-interface members. After this change they disappear from `IFoo` (no plan entry, see
  question 1), where today they appear untyped and unimplementable. That is strictly closer to the
  restatement (the class compiles), but it loses API surface, so it is called out in Consequences and
  gets its own follow-up item.

## Expected consumer-side C# API

Kotlin fixture shape (the issue's, reduced to types this repo already has fixtures for):

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.issue112

class Beacon(val label: String)

interface Advertisement {
  val identifier: String            // bridgeable
  val beacon: Beacon?               // bridgeable, reference-typed, nullable
  val codes: Collection<String>     // NOT bridgeable at a property position today
  fun payload(code: Int): ByteArray? // NOT bridgeable today
  fun describe(prefix: String): String // bridgeable
}

class BleAdvertisement(
  override val identifier: String,
  override val beacon: Beacon?,
) : Advertisement {
  override val codes: Collection<String> get() = emptyList()
  override fun payload(code: Int): ByteArray? = null
  override fun describe(prefix: String): String = "$prefix$identifier"
}
```

Expected generated C#:

```csharp
public interface IAdvertisement : IDisposable
{
    string Identifier { get; }
    Beacon? Beacon { get; }

    string Describe(string prefix);
}

public class BleAdvertisement : IAdvertisement { /* ... */ }
```

`Codes` and `Payload` appear on neither, so `BleAdvertisement` satisfies `IAdvertisement`. `Beacon`
is the wrapper class, not `IntPtr`.

### Test shape for `csharp-dev` (Step 3)

These should fail today, in `IntegrationTests/Issue112Tests.cs`:

```csharp
[Fact]
public void InterfaceMember_ReferenceType_IsWrapperNotIntPtr()
{
    var prop = typeof(IAdvertisement).GetProperty(nameof(IAdvertisement.Beacon));
    Assert.NotNull(prop);
    Assert.NotEqual(typeof(IntPtr), prop!.PropertyType);
    // The load-bearing assertion: the interface's type must equal the class's own type for the
    // same Kotlin member, not a hardcoded literal (see Decision A's inferred claim).
    Assert.Equal(
        typeof(BleAdvertisement).GetProperty(nameof(BleAdvertisement.Beacon))!.PropertyType,
        prop.PropertyType);
}

[Fact]
public void InterfaceMember_SkippedByThePlan_IsAbsent()
{
    Assert.Null(typeof(IAdvertisement).GetProperty("Codes"));
    Assert.Null(typeof(IAdvertisement).GetMethod("Payload"));
}

[Fact]
public void Interface_IsImplementedByTheExportedClass()
{
    // The compile itself is the real test (CS0535/CS0738 are compile errors, not runtime ones),
    // so this fact exists mainly to pin the relationship.
    Assert.True(typeof(IAdvertisement).IsAssignableFrom(typeof(BleAdvertisement)));
}

[Fact]
public void InterfaceMember_Bridgeable_RoundTrips()
{
    IAdvertisement ad = new BleAdvertisement("abc", new Beacon("home"));
    Assert.Equal("abc", ad.Identifier);
    Assert.Equal("home", ad.Beacon!.Label);
    Assert.Equal("id:abc", ad.Describe("id:"));
}
```

Plus a Tier 1 (`Tier1Issue112InterfaceProjectionTest.kt`) cell asserting the generated `Interop.cs`
for `IAdvertisement` contains no `IntPtr` member and no `Codes`/`Payload` member, and a cell for the
CS0102 guard: a fixture interface with `val tag: String` + `fun tag(n: Int): String` (both bridgeable)
must produce `ERROR_CSHARP_NAME_COLLISION` naming `ITagged.Tag`.

## Consequences

### What changes

- `CirClassTranslator.translateInterface` gains a catalog parameter and loses its string-keyed type
  mapping. `mapInterfacePropertyType` is deleted.
- `CirTranslator.kt:377` passes the new declaration catalog.
- `NugetProcessor.kt` builds one additional catalog, over all exported interfaces, whose drop channels
  are discarded.
- New fatal `ERROR_CSHARP_NAME_COLLISION` on the interface route.

Files touched: `cir/CirClassTranslator.kt`, `cir/CirTranslator.kt`, `NugetProcessor.kt`, plus fixtures
and tests. Three production files.

The "narrower" alternative (2) touches two production files and regresses non-reachable interfaces, so
it is not actually cheaper once its own fix-up is priced in. This is the narrowest option that
satisfies the restatement.

### What breaks

- **API surface shrinks on `IFoo`.** Any member the forward plan cannot express is gone from the
  generated interface. Consumers who wrote against those members were already unable to compile an
  implementation, so the practical break is limited to C# code that only *called* such a member
  through the ADR-040 backing class, which could never have worked (the backing class never implemented
  it). Inferred, not verified against a real consumer.
- **Super-interface members disappear from `IFoo`** (question 1's gap). Today they are present and
  unimplementable; after this they are absent. Net improvement, but a visible change.
- **A new fatal build error** for a Kotlin interface declaring `val x` and `fun x(...)` where both are
  bridgeable. No fixture has this shape today.

### Deferred, as new ROADMAP items

1. `CirInterface` has no super-interface list, so an interface hierarchy is flattened and, after this
   ADR, truncated: `IDerived` should render `: IBase` and inherit its members rather than redeclare or
   drop them. Verified by reading `CirModel.kt:22-28` and `CirClassRenderer.kt:139`.
2. The same CS0102 property/method name collision on the **ordinary class route** is still unguarded.
   Issue #112's class escaped it only because the colliding method happened to be unbridgeable.
   ADR-034's guard is signature-based and does not cover it (verified by reading all three call sites).
3. `CirInterfaceProperty.hasSetter` is never set, so a `var` interface property renders get-only.
   Needs reconciling with ADR-075's getter/setter independence and with the CS0546 hazard recorded at
   `docs/roadmap-archive.md:67` before it can be derived from the plan.
4. An interface that is neither reachable nor implemented by any exported class now silently loses
   unbridgeable members with no diagnostic anywhere (Decision C's residual hole).

## Post-implementation notes

Shipped as designed (`2c97d5a`), four production files rather than the three estimated above
(`ForwardCirPropertyProjection` also gained a `publicType(plan)` entry point). Corrections against
the Proposed text:

1. Decision E's "Roslyn reports CS0102 inside an `interface`" is now **verified**, not inferred.
   `Interop.cs(15439,16)` is `IntPtr CollarTag(int code);` inside `public interface IAdvertisement`
   opening at `:15433` (net8.0 Roslyn).
2. Decision B's "planning a non-reachable interface has no side effect beyond the returned lists"
   is now **verified**: stash / ksp / restore / ksp diff showed `CNameExports.kt` byte-identical,
   no new `microchipped_*` exports.
3. **Decision A's "both go through the same classifier, therefore the same string" is wrong as
   stated.** See "Two divergent public-C# type spellers" below. The implementer sidestepped the
   claim rather than it holding.
4. This ADR's own reduced fixture renamed the colliding pair to `beacon`/`payload`, two different
   names, so it had no CS0102 and could not exercise the post-filter requirement Decision E argues
   for. The shipped fixture (`Issue112Sample.kt`) keeps the literal collision: `val collarTag` and
   `fun collarTag(code: Int)`.
5. The fixture name `Beacon` would have collided with `test/platform`'s `expect class Beacon` on
   the global `simpleName.lowercase()` export prefix. The shipped fixture uses `CollarTag` instead.
6. The "What breaks" line ~329 mislabels a skip: `fun payload(code: Int): ByteArray?` fires
   `SKIPPED_UNSUPPORTED_RETURN` (a "NULLABLE type combination"), and under the Tier 1 harness (no
   `rootPackage`) the non-nullable one fires `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`. Tests assert
   absence, not kind, deliberately.
7. "What breaks" understated the loss: members typed with the interface's **own type parameter**
   also dropped (`IReadable<T>` lost `T Read()`, `IWritable<T>` lost `void Write(T value)`). The
   owner decided, once this was surfaced, to keep them; see the carve-out below.

### The type-parameter carve-out (owner-decided, after the shrink was surfaced)

Members whose signature names one of the interface's own type parameters keep rendering bare,
because they are valid C# in scope and were dropped only for want of a plan entry. Same rule as
issue #111's "a type parameter stays bare" (see
[Lambdas and callbacks](../topics/lambdas-and-callbacks.md#type-arguments-across-a-namespace-boundary)). The
condition is literally `returnName in typeParamNames || paramNames.any { it in typeParamNames }`,
never "the plan has no entry", which would resurrect the whole bug. Covers properties too
(`val head: T`), and dedupes against planned members of the same name/arity so it can never emit
CS0111. The 7th Tier 1 cell fails both ways: absent fails `assertContains("T Read();")`; reverted
fails `assertFalse("IntPtr" in readable)`.

### New ROADMAP item: two divergent public-C# type spellers

`ForwardCirPropertyProjection.kt:640`'s private `BridgeType.csharpType()` handles `Throwable` but
not `BoundInterface`/`Unit`. The shared `forwardPublicCsharpType()` (`forward/ForwardCsharpTypes.kt`,
delegated to by `ForwardCirPlanProjection.kt:1379`) handles `BoundInterface`/`Unit` but not
`Throwable`. Both end in `else -> error(...)`. Had interface properties been spelled with the
shared function, a `Throwable`-typed interface property would have crashed KSP with "Forward CIR
direct-value projection cannot render public type". Verified by reading. Merging the two copies is
a separate refactor; [ADR-114](114-collection-parameters-on-legacy-flow-and-suspend-routes.md)
extracted one of the two copies already, so together they are one item, not two. See
[ROADMAP.md](../../ROADMAP.md) / [details](../backlog/two-divergent-public-csharp-type-spellers.md).

### Deliberately uncovered

The `Variance` COVARIANT/CONTRAVARIANT/else `when` and the `qualifiedName ?: name` fallback in
`translateInterface` (carried over unchanged, cold because no Tier 1 fixture declares a generic
interface, though `test-library` does). Inside `typeParameterMethods`'s lambda: the dedupe
short-circuit and the String/`Unit`/`mapReturnType`/`mapParamType` arms for a mixed generic member
such as `fun read(n: Int): T`. No fixture has that shape.

No interface in `test-library` extends another, so the "inherited members now drop" behaviour
(deferred item 1 above) has no live fixture; the code-level argument is the
`.filter { it.parentDeclaration == iface }` on both planner helpers, verified by reading only.

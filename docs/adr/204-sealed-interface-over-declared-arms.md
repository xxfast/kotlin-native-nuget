# ADR-204: Sealed interface over declared arms: a discriminator on `I<Name>` when the arms already have a C# class

## Status

Accepted

## Context

Issue #463 (follow-up of #130). A Kotlin `sealed interface` whose arms also extend a class is
refused by ADR-112's eligibility gate, because an arm that already has a base cannot also extend
an abstract `ConnectableDevice`:

```kotlin
sealed class EvidenceDevice
sealed interface ConnectableDevice
data class NearbyDevice(/* ... */) : EvidenceDevice(), ConnectableDevice
data class RemoteDevice(val host: DeviceHost) : EvidenceDevice(), ConnectableDevice
data class SavedDevice(/* ... */) : EvidenceDevice(), ConnectableDevice
fun connect(device: ConnectableDevice): StateFlow<EvidenceDevice>
```

The gate is `armIneligibility()` (`forward/ForwardClassMembership.kt:91-95`). The classifier's
sealed branch (`forward/ForwardBridgeTypeClassifier.kt:343-376`) then returns a bare
`SpecializedProtocol` with no `sealedHandle`, and almost every position typed with the interface
skips. **Amended at implementation:** not every one. A class-method `Flow<I>`/`StateFlow<I>` return
and a `StateFlow<I>` property were *not* skipped: the legacy Flow route spells its element with
`qualifiedElementCsType`, which spelled the bare `global::Ns.ConnectableDevice`, a type nothing
declares, so the generated C# failed `nugetCompileInterop` with CS0234 and took the whole package
down (verified by the test author's red run). The same held for any sealed type with no
discriminator at a Flow item.
ADR-125 and ADR-157 widened eligibility twice and both left this shape out; ADR-157 lists it as
deferred.

### What is already generated for the refused shape (verified by run)

An out-of-repo Tier 1 probe (scratch test added to `:nuget-processor`'s test source set through
a Gradle `--init-script`, `Tier1Harness.run` against this branch) shows the C# types are all
there; only the discriminator and the position binding are missing:

```C#
public interface IConnectableDevice : IDisposable { string Address { get; } }
public abstract class EvidenceDevice : IDisposable, INugetHandle { /* FromHandle over 3 arms */ }
public sealed class NearbyDevice : EvidenceDevice, IConnectableDevice { public string Address ... }
public sealed class RemoteDevice : EvidenceDevice, IConnectableDevice
```

with one `SKIPPED_INELIGIBLE_SEALED_INTERFACE`, `SKIPPED_SEALED_POSITION` on the parameter,
return and `List<>` return, and `SKIPPED_UNSUPPORTED_PROPERTY` on a `var`. The same holds for a
top-level arm with a plain superclass (`public class Odd : Rhythm, IMixed`,
`public class Even : IMixed, IDisposable, INugetHandle`). A top-level `object` arm is the
exception: it is `public static class Rest` and its supertype is dropped
(`SKIPPED_UNEXPORTED_SUPERTYPE`).

### Constraints (verified by reading unless marked)

- A sealed base at any position is an `ObjectHandle(viaDiscriminator = true)`; C# reconstructs
  it as `"${csharpType()}.FromHandle($wireValue)"` (`forward/ForwardCirPlanProjection.kt:1826-1832`,
  `forward/ForwardCirPropertyProjection.kt:482`) and lowers an input as `x._handle`
  (`ForwardCirPlanProjection.kt:781`, `:821`, `:874`; `ForwardCirPropertyProjection.kt:736-737`,
  `:766`). Erased positions go through `NugetMarshal.Factories` with a `viaFromHandle` form
  (`cir/CirMarshalRenderer.kt:150-163`, `cir/CirTranslator.kt:1259-1300`).
- The interface route reconstructs only as the ADR-040 backing wrapper
  (`interfaceReturnExpression`, `ForwardCirPlanProjection.kt:1911-1917`), never an arm class.
- Generated C# is compiled into the consumer's own assembly (`contentFiles/cs/<tfm>/`,
  `nuget-plugin/.../PackNugetTask.kt:178`), so `internal` is visible to consumer code.
  `INugetHandle` is `internal` (`cir/CirRenderer.kt:36`), so a public interface cannot extend it
  (CS0061, verified by spike).
- Kotlin never accepts an implementer of a sealed interface from outside the hierarchy.

### The claims the design rests on, verified by spike

Scratch directories, dotnet SDK 10.0.301, `net10.0`, `LangVersion 14.0` (the generated code's
own settings, `NugetCompileInteropTask.kt:102`).

Spike B, one file compiled and run, holding the exact proposed shape:

```C#
public interface IConnectableDevice : IDisposable
{
    string Address { get; }
    internal NugetKotlinHandle _handle { get; }

    [DllImport("library", CallingConvention = CallingConvention.Cdecl, EntryPoint = "..._get_type")]
    private static extern int Native_GetType(NugetKotlinHandle handle);

    internal static IConnectableDevice FromHandle(IntPtr handle) { /* own, try, dispose on throw */ }
    internal static IConnectableDevice FromHandle(NugetKotlinHandle handle) => Native_GetType(handle) switch
    {
        0 => new NearbyDevice(handle, out _),
        1 => new RemoteDevice(handle, out _),
        _ => throw new InvalidOperationException("Unknown sealed interface type")
    };
}
public sealed class NearbyDevice : EvidenceDevice, IConnectableDevice, ISecond
{
    NugetKotlinHandle IConnectableDevice._handle => _handle;
    NugetKotlinHandle ISecond._handle => _handle;
}
```

Observed: it compiles; `device._handle` and `device?._handle ?? NugetKotlinHandle.Null` on an
interface-typed value compile and return the arm's handle; `IConnectableDevice.FromHandle(ptr)`
and `[typeof(global::Interop.IConnectableDevice)] = static handle =>
global::Interop.IConnectableDevice.FromHandle(handle)` compile and return the arm;
`using IConnectableDevice d = ...` compiles; `d switch { NearbyDevice n => ..., RemoteDevice r
=> ... }` selects the arm; a returned arm `is EvidenceDevice`. Program output: `remote remote`,
`43`, `0`, `Odd`, `Odd`, `is EvidenceDevice`.

Spike C, run: a `[DllImport] private static extern` declared inside an interface and reached
through an `internal static` interface method performs the P/Invoke (kernel32
`GetCurrentProcessId() == Environment.ProcessId` printed `True`).

Spike A2, same assembly: with the internal member, `class Rogue : IConnectableDevice { }` is
CS0535; a rogue that writes `NugetKotlinHandle IConnectableDevice._handle => ...` compiles; so
does `class Rogue3 : EvidenceDevice` calling the `internal` constructor today.

## Alternatives Considered

### 1. Sealed position route with the discriminator on the interface (chosen)

Keep `I<Name>` and the arm classes exactly as they are declared today. Classify the interface as
the `ObjectHandle(viaDiscriminator = true)` every sealed base already is, spelled `I<Name>`, and
give the interface the two things that spelling needs: a `_handle` member and a static
`FromHandle`. Pros: every ADR-105 position rides the existing route with no projection change;
no new C# type; a returned value is the concrete arm, so it is also its class base. Cons: an
internal underscore-named member on a public interface; three generated members inside an
interface body; an eligibility rule with a per-arm reason table. About 9 production files.

### 2. Interface route with a discriminator in `interfaceReturnExpression` (rejected)

Classify as `BridgeType.Interface` and switch `new $backingType(...)` for a `FromHandle` at the
one choke point. Rejected: the type then becomes "reachable", so the backing wrapper, dispatch
exports, bridge plan and `Factories` backing entry all have to be suppressed for it
(`NugetProcessor.kt:2215`, `:2262`, `ForwardCallablePlanner.kt:1502`,
`ForwardScopeOwnership.kt:269` and the translators), the position matrix becomes the interface
route's rather than the sealed one's, and input falls through `HandleOf` to the ADR-173 proxy
path that must not be reachable. About 11 files. This is ADR-112's rejected alternative 2 in
substance.

### 3. Issue shape only: reconstruct through the shared sealed base (rejected)

When every arm shares one eligible sealed class, return `(IConnectableDevice)
EvidenceDevice.FromHandle(h)`. No new export, about 4 files. Rejected: input still needs
`_handle` on the interface, hierarchies whose arms do not share a sealed base (`Odd : Rhythm()`
beside `Even`) stay refused, and admitting them later adds this ADR's mechanism beside it.

### 4. Make the arms extend an abstract `ConnectableDevice` (not available)

C# has single class inheritance; the arm already extends `EvidenceDevice`.

### 5. Keep refusing (rejected)

A capability marker across arms of a sealed class is an ordinary Kotlin shape, and the C# types
it needs already exist.

### Does ADR-112's rejection of "a discriminator on the interface route" bind?

No. That rejection (`docs/adr/112-sealed-interface-mapping.md:88-97`) had two grounds. First,
the ADR-040 backing wrapper owns the interface return position, so a discriminator would have to
displace it or coexist at every ADR-105 position: here the type never enters the interface route
at all, has no backing wrapper, and uses the one sealed position route. Second, it would invent
`sealed class Circle : IShape` arm classes and an `interface` + static `FromHandle` shape with no
precedent: here the arm classes are already declared by their own routes and already list the
interface (verified by run), and the shape is proven by spike B. ADR-112's own choice is
untouched: an eligible sealed interface is still an abstract class.

## Decision

Adopt alternative 1.

### Eligibility

ADR-112 eligibility is tested first and is unchanged. A sealed interface that fails it is
**reconstructable over its declared arms** iff every `getSealedSubclasses()` entry is declared by
another route as an instantiable handle class that lists `I<Name>`:

| Arm | v1 | Reason |
|---|---|---|
| class or `data object` arm of an eligible, non-generic sealed class | admitted | verified by run |
| top-level exported, non-abstract, non-generic plain class, with or without a superclass | admitted | verified by run |
| an arm implementing two sealed interfaces that are both on this route | admitted | verified end to end (`RemoteDevice`, `RemoteDevice_DualArm_CrossesAsBothInterfaces`) |
| `object` not under a sealed class | refused | it is a C# static class |
| `enum class` | refused | deferred; the ADR-157 box has no base to extend here |
| interface (sub-interface arm) | refused | no single class to construct |
| generic arm, generic interface, arm of a generic sealed class | refused | no closed type to construct (ADR-199) |
| abstract plain class, intermediate `sealed class` | refused | deferred; needs a non-`new` construct expression |
| nested in the interface with no sealed class base (the shipped `Mixed.Odd` fixture) | refused | declared by no route today (`NugetProcessor.kt:1781`) |
| a subclass of another arm of the same interface | refused | see the ordering hazard below |
| outside the export set | refused | nothing to construct |

Any refused arm refuses the whole interface, named per arm in
`SKIPPED_INELIGIBLE_SEALED_INTERFACE` as today.

**The predicate must not leak.** It is read by the classifier, the interface translator and the
diagnostic only. `isEligibleSealedType()`, `isEligibleSealedInterface()`, `isSealedSubclass()`,
`rootSealedClasses` and `rootInterfaces` keep answering exactly as they do today: the interface
stays in `rootInterfaces`, plain arms stay on the class route, sealed-class arms stay with their
class base. Flipping any of those instead removes the arms from the class route or renders the
interface a second time as an abstract class (CS0101).

**Ordering hazard (inferred, not verified; silent wrong output if ignored).** The Kotlin
discriminator is an ordered `when` of `is` tests (`exports/SealedClassExports.kt:45-66`). If arm
`B` extends arm `A` and both are direct subtypes of the interface, `is A` matches a `B` and C#
builds an `A` wrapper for it. A sealed class cannot produce this; this route can. v1 refuses the
hierarchy. If a later change admits it, arms must be emitted subclass-first, with a test that
returns a `B`.

### Bridge mechanism

- Kotlin: one `export_<prefix>_get_type(handle): Int`, the body of the existing sealed
  discriminator with the interface as the read type and the admitted arms as branches.
  `asStableRef<SealedInterface>()` is already shipped for the eligible kind (**verified** by the
  `Pulse` fixture); for an arm that also extends a class it is **inferred** from Kotlin semantics.
- Wire: unchanged. A handle is a `StableRef` to the arm; the C# owner is the arm's own
  `NugetKotlinHandle`.
- C#: `I<Name>` gains `internal NugetKotlinHandle _handle { get; }`, the private extern, and the
  two `FromHandle` overloads; each admitted arm gains
  `NugetKotlinHandle I<Name>._handle => _handle;`; `Factories` gains a `viaFromHandle` entry
  keyed on `typeof(I<Name>)`. All **verified** by spike B as C#; **inferred** as generator output.
- Classifier: `sealedHandle = ObjectHandle(qualifiedName, csharpType = <interface spelling>,
  viaDiscriminator = true)`.

### Files touched

As implemented (amended; the proposal's table missed the three legacy-route spellers and the
contract allowlist, and named `exports/InterfaceExports.kt`, which needed no change):

| File | Change |
|---|---|
| `forward/ForwardClassMembership.kt` | `isSealedInterfaceOverDeclaredArms(exported)` and `declaredArmsIneligibility(exported)` with the per-arm reasons, beside `sealedInterfaceIneligibility()`. |
| `forward/ForwardBridgeTypeClassifier.kt` sealed branch | A second, separate arm after `discriminated`: the new kind returns `SpecializedProtocol(sealedHandle = ObjectHandle(csharpType = interfaceType(...).csharpType, viaDiscriminator = true))`. `discriminated` itself is untouched. |
| `NugetProcessor.kt` | The `SKIPPED_INELIGIBLE_SEALED_INTERFACE` emit moves below `exportedObjectHandles`, since the predicate is answered against that set; the discriminator export is emitted for the admitted interfaces. |
| `exports/SealedClassExports.kt` | `get_type` extracted as `addSealedDiscriminatorExport(owner, prefix, arms)`, shared. |
| `cir/CirModel.kt` | `CirInterface.discriminator` (`CirInterfaceDiscriminator`); `handleInterfaces` on `CirClass` and `CirSealedSubclass`. |
| `cir/CirClassTranslator.kt` | `translateInterface` fills the discriminator (each arm spelled by the classifier); both base-list sites fill `handleInterfaces`; the class suspend route spells an admitted return as `I<Name>`. |
| `cir/CirFunctionTranslator.kt` | The top-level suspend route, the same spelling. |
| `cir/CirClassRenderer.kt`, `cir/CirSealedRenderer.kt` | Render the interface members and the explicit line. |
| `cir/CirTranslator.kt` | `Factories` entry for the interface. |
| `cir/CirTypeMapping.kt` | `qualifiedElementCsType` spells an ineligible sealed interface `I<Name>` (the only name C# declares for it). |
| `forward/ForwardLegacyRouteCollections.kt` | `Discriminated.isInterface` + `declaredCsharpType()`; a sealed Flow element with no discriminator is `Refused`. |
| `ForwardAbiLegacyRoutes.kt` | A `CirInterface` with a discriminator registers the `SEALED_CLASS` legacy route. |
| `forward/ForwardCallablePlanner.kt` | `sealedTypeDetail()` picks the sealed component of a `Map`, not its key (the "`the sealed type`" placeholder). |
| `forward/ForwardDiagnostic.kt`, `NugetProcessor.kt` | Reword the two hints that say "no other superclass". |

### Consumer API

```C#
using var nearby = new NearbyDevice("desk", "aa:bb");
using EvidenceDevice connected = Devices.Connect(nearby);   // parameter: any arm

using IConnectableDevice picked = Devices.Preferred();      // return: the concrete arm
string label = picked switch
{
    NearbyDevice n => $"nearby {n.Address}",
    RemoteDevice r => $"remote {r.Address}",
    SavedDevice s => $"saved {s.Address}",
    _ => throw new InvalidOperationException(),
};
bool alsoEvidence = picked is EvidenceDevice;                // true
```

### Positions

Identical to a sealed class: parameter, nullable parameter, return, `val`/`var` property,
nullable return, `List`/`Set`/`Map` component, constructor parameter, suspend parameter and result,
`Flow`/`StateFlow` item, sealed-arm member (**verified**, Tier 1 and the issue463 fixture).
Whatever the sealed-class route does not bind today stays unbound and is not widened here (a
callback payload). Contrary to the proposal, a nullable parameter is **not** such a gap: a nullable
sealed-class parameter binds today (`Tier1SealedParameterPositionTest` "a nullable sealed
parameter rides the null pointer in band", `Issue54Tests.DescribeMaybe_NullableSealedParameter_*`),
and the interface binds the same way, lowered as `device?._handle ?? NugetKotlinHandle.Null`
(**verified**: `Devices.DescribeOrNone`, `DescribeOrNone_NullableParameter_CarriesTheArmOrNull`).
The `Set` component is verified the same way (`Devices.ConnectableSet`).

**Amended at implementation:** "because the `BridgeType` is identical" held for every plan-route
position and for the suspend *parameter*, but not for two legacy positions, which re-derive the
C# name from the declaration instead of reading `sealedHandle.csharpType`: the suspend *return*
(the `nestedCsName()` fallback, `Task<ConnectableDevice>`) and the `Flow`/`StateFlow` *item*
(`qualifiedElementCsType`). Both now spell `I<Name>`; the read was already `T.FromHandle(...)` and
the erased `NugetMarshal.FromHandle<T>` respectively, which the interface's own `FromHandle` and
its `Factories` entry satisfy. A sealed `Flow` element with no discriminator at all (a refused
interface, an out-of-scope sealed class) now skips at the member with
a named `SKIPPED_*` diagnostic instead of spelling an undeclared name (Tier 1: a method and a
`StateFlow` property over a refused interface).

### Input semantics

`I<Name>` cannot be implemented by accident: the internal `_handle` member makes an empty
implementer CS0535 (**verified**). It can be implemented on purpose by in-assembly code, exactly
as an ADR-009 abstract class can be subclassed on purpose today (**verified**); a compile-time
barrier that holds against the consumer's own assembly does not exist under the contentFiles
layout. The ADR-173 `HandleOf` proxy is not on the direct path at all (input is `x._handle`), and
no bridge plan, backing wrapper or backing `Factories` entry arises, because all three are built
from `reachableInterfaces` (`NugetProcessor.kt:2262`, `:2916-2926`), which is seeded from
classification results (`:2185-2197`; `ForwardLegacyRouteCollections.kt:933-941`) and this type
never classifies as `BridgeType.Interface` (**verified by reading**, except the bodies of
`interfaceQualifiedNames()` and `componentInterfaceQualifiedNames()`). The Tier 1 cell must assert
it anyway: no `class <Name>` backing wrapper on a code line and exactly one
`typeof(...I<Name>)` key in `Factories`. A duplicate key in the `[key] = value` initializer form
would not fail the build; which entry wins is **not verified**.

### Ownership

A returned `I<Name>` is an arm wrapper owning one `NugetKotlinHandle`. `I<Name> : IDisposable` is
already rendered for every interface (`cir/CirClassRenderer.kt:18-20`, **verified** by run).
ADR-120's counter sees it through the arm's existing constructor path (**inferred**; a
`LeakTests/LiveHandleTests.cs` row is required).

### Diagnostics

`SKIPPED_INELIGIBLE_SEALED_INTERFACE` stops firing for an admitted hierarchy and keeps naming
every refusing arm otherwise, with the reasons in the table above. No new kind. As implemented,
the reason carries both refusals, ADR-112's first (unchanged wording, so its existing assertions
hold) and then this ADR's per-arm list: "... is not an abstract class because subclass `Odd`
extends another class `Rhythm`; and not over its declared arms because subclass `Odd` is nested
in `Mixed` with no sealed class base, ...".

One consequence the table implies but the proposal did not spell out: ADR-125's shipped
`sharedarm` cell (`data class Both : Left, Right`, two sealed interfaces, no superclass) is now
**admitted** under the "arm implementing two sealed interfaces" row, as `ILeft`/`IRight` over the
one plain `Both`, rather than refused. Its Tier 1 cell was updated to assert that.

## Consequences

- The issue's shape binds at every sealed position. `Mixed`-style hierarchies with top-level
  arms bind too.
- The shipped Tier 1 control (`Mixed.Odd` nested in the interface) stays refused, with a new
  reason; a cell per refused arm kind is added.
- Two C# shapes now exist for a Kotlin sealed interface: an abstract class (ADR-112) or `I<Name>`
  with a discriminator. Which one a hierarchy gets depends on whether any arm has a superclass.
  Adding a superclass to an arm of a shipped library is therefore a breaking change for its C#
  consumers (`Pulse` becomes `IPulse`). Needs a line on the topic page.
- A `switch` over `I<Name>` is not exhaustive in C#, as with every ADR-009 hierarchy.
- Deferred: enum, object, abstract, intermediate-sealed, nested, generic and arm-extends-arm arms.

## Prior art (to the depth that changes the decision)

Not consulted beyond the repo: ADR-009, 105, 112, 125 and 157 already fix the closed-hierarchy
idiom, and the only question was which existing route carries this shape. Swift Export, ObjC
export and JVM interop were skipped; none would change a choice between two in-repo routes.

## Claims ledger

| Claim | Status |
|---|---|
| Refused shape already declares `I<Name>`, its members, and arms listing it | Verified (Tier 1 probe run) |
| Top-level plain-superclass arms likewise; top-level `object` arm is a static class | Verified (Tier 1 probe run) |
| Nested arm of an ineligible sealed interface is declared by no route | Verified by reading (`NugetProcessor.kt:1781`) |
| `viaDiscriminator` handles reconstruct as `T.FromHandle(` and lower as `x._handle` | Verified by reading (lines above) |
| Interface with internal `_handle`, extern and static `FromHandle`; explicit arm implementation; nullable lowering; `Factories` entry; `using`; pattern match | Verified (spike B, compiled and run) |
| `DllImport` extern inside an interface P/Invokes at run time | Verified (spike C, run) |
| Empty implementer is CS0535; deliberate in-assembly implementer compiles | Verified (spike A2) |
| Public interface cannot extend `INugetHandle` | Verified (spike A2, CS0061) |
| Suspend, Flow and callback projections accept an interface-spelled `ObjectHandle` | **Wrong as stated** for the suspend return and the Flow item, which spell from the declaration; both fixed (see Positions). Suspend parameter verified as-is. Callback payload not bound for any sealed type |
| Position matrix equals the sealed-class matrix | Verified after the two spelling fixes (Tier 1 + issue463 fixture) |
| A nullable sealed input is an inherited unbound gap | **Wrong**: binds for a sealed class today and for this interface (`DescribeOrNone`) |
| An arm implementing two sealed interfaces works end to end | Verified (`RemoteDevice`, `RemoteDevice_DualArm_CrossesAsBothInterfaces`) |
| Every position typed with a refused interface skips | **Wrong**: the Flow item spelled an undeclared name (CS0234); now refused by name |
| Arm-extends-arm produces the wrong wrapper | Inferred from `when` ordering; refused in v1 |
| `asStableRef<SealedInterface>()` on an arm with a class base | Inferred |
| No bridge plan or backing wrapper arises for the type | Verified by reading (reachability is classification-seeded); two helper bodies unread, Tier 1 assertion required |
| A sealed base in a callback payload is unbound today (`ForwardBridgeTypeClassifier.kt:965`) | Inferred, read in passing; inherited gap if true |
| ADR-120 counter sees returned arms | Inferred |
| Cross-module (klib) sealed interface behaves the same | Inferred |

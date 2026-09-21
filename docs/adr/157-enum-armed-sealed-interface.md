# ADR-157: An enum arm of a sealed interface binds as a boxed, handle-backed arm

## Status

Accepted

## Context

GitHub issue #236, the deferred alternative 2 of [ADR-125](125-sealed-interface-sibling-arms.md).

Restatement (the contract): "Forward, Kotlin declares. A Kotlin sealed interface whose arms are
enum classes (alone or mixed with data class arms) binds in C# as a switchable hierarchy: each enum
arm is a boxed arm carrying its enum value, the discriminator survives a round trip, and the
interface binds at every ADR-105 position (property, constructor parameter, return), with
`SKIPPED_INELIGIBLE_SEALED_INTERFACE` and `SKIPPED_SEALED_POSITION` no longer firing for that
shape."

```kotlin
sealed interface Kind

enum class Family : Kind { A, B }
enum class Variant(val family: Family) : Kind { X(Family.A), Y(Family.B) }
data class Device(val id: String) : Kind

data class Item(val kind: Kind)
```

Today `Kind` is refused by one branch, `armIneligibility()`'s `ENUM_CLASS` test
(`forward/ForwardClassMembership.kt:81-84`, **verified by reading**), because a C# `enum` admits
only an integral base (CS1008, verified by spike in ADR-125). The refusal cascades: `Kind` then
classifies as a bare sealed protocol with no `sealedHandle`
(`forward/ForwardBridgeTypeClassifier.kt:240-267`, **verified by reading**), so every input typed
with it skips as `SEALED_POSITION` and takes the whole callable with it. The issue counts 27 lost
members from one interface.

The issue's three prohibitions are binding: no asking the author to convert the enums, no mapping
the arm to the bare enum value (two enum arms overlap on ordinals), no stopping at eligibility while
constructor parameters still skip.

### What the existing route already does (all verified by reading)

- A sealed value crosses as one `StableRef` handle typed as the **base**. The discriminator export
  is `handle.asStableRef<Base>().get()` followed by `when (obj) { is Arm -> index }` in
  `getSealedSubclasses()` order (`exports/SealedClassExports.kt:40-56`). It is an `is` test on the
  Kotlin runtime type, not an ordinal and not a tag stored beside the handle.
- C# reconstructs through `FromHandle`, which spells `new ${subclass.name}(handle)` per index
  (`cir/CirSealedRenderer.kt:64-66`). The C# arm name is a free field of `CirSealedSubclass`
  (`cir/CirModel.kt:290`), so it need not equal the Kotlin simple name.
- A sealed base at a position rides `BridgeType.ObjectHandle(viaDiscriminator = true)`
  (`forward/ForwardMarshallingModel.kt:85-97`, classifier `:240-267`). Inbound, C# passes the
  wrapper's `_handle` and Kotlin reads `asStableRef<Base>().get()`. Outbound, C# calls
  `Base.FromHandle(ptr)` (`forward/ForwardCirPlanProjection.kt:1480-1486`). That flag is set from
  `isEligibleSealedType()`, so **eligibility alone is what turns on every ADR-105 position**.
- An enum-typed position classifies as `BridgeType.Enum` *before* the sealed branch is reached
  (`forward/ForwardBridgeTypeClassifier.kt:235` precedes `:240`), and the reachability closure
  buckets `ENUM_CLASS` first (`forward/ForwardReachabilityClosure.kt:341`). So `val f: Family`
  stays a C# `Family` whether or not `Family` is also an arm.
- Enums cross as `int` ordinals and their own members are extension methods on
  `{Name}Extensions` ([ADR-006](006-enum-mapping.md)). `Variant.family` is `variant.Family()`,
  provided the enum keeps being declared as an enum.

  **Correction (implementation, 2026-09-21).** The draft said this route "needs nothing from this
  ADR". It was wrong, and the error was in the research memo's finding 7 as well: the claim came
  from reading ADR-006, not generated output. An enum member typed as **another enum** fell out of
  `mapReturnType`'s table as `IntPtr` on both halves, so `Swirl.patch` was emitted as
  `public static IntPtr Patch(this Swirl swirl)` over an export returning the Kotlin enum object
  itself. Verified from fresh generated output before the fix
  (`test-library/build/generated/ksp/macosArm64/macosArm64Main/resources/Interop.cs:25096`). It is
  a pre-existing defect of ADR-006, not of this feature: no fixture had an enum-typed enum member
  until this one. Fixed separately and first (`EnumExports.kt` returns `Int` and lowers with
  `.ordinal`; `translateEnum` records `isEnum` with the `int` wire; `CirEnumRenderer` casts back),
  pinned by `Tier1EnumTypedEnumMemberTest`.
- Handles are counted: `NugetHandles.retain` wraps `StableRef.create(value)` and bumps `live`
  (`nuget-runtime/.../NugetRuntime.kt:55-67`). Generated wrappers have no finalizer (no `~`,
  `Finalize` or `SuppressFinalize` anywhere under `cir/`), so a handle is released by `Dispose()`
  only.

### The claim the design rests on, verified by spike

An enum *entry* can ride a `StableRef`, the `is` discriminator tells two enum classes apart even
when their ordinals collide, the ordinal is recoverable, and the inbound value is the entry itself.

```bash
cd "$(mktemp -d)"   # spike.kt: Kind, Family, Variant(family), Device as above, then
# getType(h) = when (h.asStableRef<Kind>().get()) { is Family -> 0; is Variant -> 1; is Device -> 2 }
# boxFamily(o) = StableRef.create(Family.entries[o]).asCPointer(), boxVariant likewise
# valueOf(h)   = (h.asStableRef<Kind>().get() as Enum<*>).ordinal
~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/kotlinc-native spike.kt -o spike && ./spike.kexe
```

```
types: 0 1 2
ordinals: 1 1  (overlap on purpose)
inbound: B identity=true equalsItem=true
two boxes same entry: ptrEqual=false sameObj=true
member via entry: B
disposed ok
```

Kotlin/Native 2.4.10, macos-arm64, the repo's pinned version (`gradle/libs.versions.toml:2`). Read
it as: `Family.B` and `Variant.Y` both have ordinal 1 and discriminate as 0 and 1; `Item(kind)`
built from the handle holds the real `Family.B` singleton (`===`), so Kotlin-side `equals`, `when`
and `copy` behave as if no bridge were involved; two boxes of one entry are two distinct handles
over one object, so each must be released once and releasing one does not disturb the other.

The C# half, verified by spike (`dotnet new console`, builds with zero warnings, prints
`family B / variant Y` and `True`): an abstract `Kind` with `sealed` `FamilyArm` / `VariantArm` /
`Device` arms is switchable with `FamilyArm { Value: Family.A }` property patterns, and a managed
`Equals` over `Value` compiles. The same spike proves
`public static implicit operator Kind(Family value)` declared **on the abstract base** makes
`new Item(Family.B)` compile. Declared on `FamilyArm` it would not be found: C# searches the source
and target types and their bases, never a type derived from the target (inferred from the C#
specification's user-defined conversion rules; only the on-the-base spelling was spiked).

## Alternatives Considered

### 1. Handle-backed boxed arm (chosen)

`FamilyArm : Kind` is an ordinary sealed arm whose handle is a `StableRef` to the enum entry. It
adds exactly two members to what an `object` arm has: a public constructor taking the C# enum, and
a `Value` getter returning it.

Pros: the wire at every ADR-105 position is unchanged (one base-typed handle), so requirement 4
costs nothing beyond eligibility; the discriminator export is unchanged; mixed hierarchies need no
second convention; Kotlin receives the real entry. Cons: a boxed arm is `IDisposable` and owns a
counted handle, where a bare C# enum owns nothing; `Value` costs one P/Invoke per read unless
cached.

### 2. Managed-only box crossing as (arm tag, ordinal) (rejected)

`FamilyArm` holds no handle; a `Kind` position crosses as two ints. No disposal for enum arms. But a
mixed hierarchy still needs a handle for `Device`, so every `Kind` position would carry three wire
slots and a per-position branch on both sides, at every route the sealed base rides (plan
parameters, plan results, property getters, collection elements, the legacy suspend and flow
routes). That is a new wire convention through roughly a dozen emitters to avoid one `using`.

### 3. Declare an arm enum as a class with static instances instead of a C# `enum` (rejected)

`public sealed class Family : Kind { public static readonly Family A; }`. No box and no naming
problem, but it reverses ADR-006 for any enum that happens to implement a sealed interface: no
constant patterns, no `switch` on entries, no `[Flags]`-free integral cast, and an existing
consumer's `Family` changes kind the day the author adds `: Kind`.

### 4. Project the enum's properties onto the box (`VariantArm.Family`) (rejected for v1)

Mechanically cheap (the spike reads `.family` off the unboxed entry), but it gives one Kotlin
property two C# spellings and would need `name` / `ordinal` filtered off the arm. `arm.Value.Family()`
already satisfies requirement 2 through ADR-006.

### 5. Keep refusing (rejected)

Issue #236 prohibits it as the fix, and the cascade is not repairable any other way (ADR-125,
alternative 5).

## Decision

Adopt alternative 1.

### Consumer API

```csharp
public enum Family { A = 0, B = 1 }
public enum Variant { X = 0, Y = 1 }
public static class VariantExtensions { public static Family Family(this Variant value) ... } // ADR-006, unchanged

public abstract class Kind : IDisposable, INugetHandle
{
    internal static Kind FromHandle(IntPtr handle) => Native_GetType(handle) switch
    {
        0 => new FamilyArm(handle),
        1 => new VariantArm(handle),
        2 => new Device(handle),
        _ => throw new InvalidOperationException("Unknown sealed class type")
    };
    public abstract void Dispose();
}

public sealed class FamilyArm : Kind
{
    public FamilyArm(Family entry) { ... }       // kind_family_create(entry, error) -> owned handle
    public Family Value { get; }                 // kind_family_get_value(handle) -> ordinal
    public override bool Equals(object? obj) => obj is FamilyArm other && other.Value == Value;
    public override int GetHashCode() => Value.GetHashCode();
    public override string ToString() => Value.ToString();
    public override void Dispose() { ... }       // kind_family_dispose(handle)
}

public sealed class VariantArm : Kind { public VariantArm(Variant entry); public Variant Value { get; } ... }
public sealed class Device : Kind { public Device(string id); public string Id { get; } ... }

public sealed class Item : IDisposable, INugetHandle
{
    public Item(Kind kind) { ... }
    public Kind Kind { get; }
    public Item Copy(Kind kind) { ... }
}
```

```csharp
using var kind = item.Kind;
string text = kind switch
{
    FamilyArm { Value: Family.A } => "family A",
    FamilyArm family => $"family {family.Value}",
    VariantArm variant => $"variant {variant.Value} of {variant.Value.Family()}",
    Device device => device.Id,
    _ => throw new InvalidOperationException(),
};
```

There is no `IKind`. An interface that was ineligible yesterday bound as a plain `IKind`; it now
binds as `abstract class Kind`. That is a generated-API break for any consumer naming `IKind`, the
same one ADR-112 and ADR-125 made for the hierarchies they admitted.

### Naming

The box is `{Enum}Arm`, declared where the enum is: at namespace level for a top-level enum
(through the existing `outdentToNamespaceLevel()` sibling path), nested in the base for an enum
nested in the interface. The bare name is taken by the C# `enum`, and nesting the box as
`Kind.Family` would shadow the enum inside every member of `Kind`. If `{Enum}Arm` is already a
declared type in the same C# namespace, the interface is refused with that reason named (a new
`armIneligibility()` reason), rather than suffixed silently.

### Bridge mechanism

| Step | Mechanism | Status |
|---|---|---|
| Discriminator | Unchanged export; `is Family -> 0` | **Verified by spike** (above) and by reading `SealedClassExports.kt:40-56` |
| Kotlin to C# (`Kind` return, property, element) | Unchanged: `NugetHandles.retain(entry)`, C# `Kind.FromHandle(ptr)` picks `FamilyArm` | **Verified by reading** (`ForwardCirPlanProjection.kt:1480-1486`, `CirSealedRenderer.kt:64-66`); `StableRef` to an entry **verified by spike** |
| C# to Kotlin (`Kind` parameter) | Unchanged: `_handle` crosses, Kotlin reads `asStableRef<Kind>().get()` and gets the entry itself | **Verified by spike** (`identity=true`) |
| `new FamilyArm(Family.B)` | New export `kind_family_create(entry: Int, error): COpaquePointer?` = `NugetHandles.retain(Family.entries[entry])` | **Implemented.** Correction: the draft spelled it `_box`. It ships as `_create`, the suffix every other constructor on the sealed route uses, so the shared `constructorNativeImport` rule addresses it and no second naming convention exists. The parameter is `entry`, not `value`: `value` is a `PLAN_OWNED_NAMES` entry and a plan may not give a user-role slot one |
| `FamilyArm.Value` | New export `kind_family_get_value(handle): Int` = `(handle.asStableRef<Kind>().get() as Family).ordinal`, C# casts `(Family)` | **Verified by spike** |
| `Dispose` | The existing per-arm `${prefix}_${arm}_dispose` | **Verified by reading** (`SealedClassExports.kt:89-97`) |

`Family.entries[ordinal]` throws on a stale ordinal (a C# shim built against an older enum). An
exception escaping a `@CName` export terminates the process (inferred from Kotlin/Native docs, not
spiked), so the box export must carry the ordinary error slot. That is the argument for the route
fork below.

### Route: plan entries, not a fourth hand-written export

Arm members have been moving onto ADR-062 plans (ADR-111 properties, ADR-116 methods, ADR-148
constructors); only `_get_type`, `_dispose` and lambda properties are still hand-written on this
route (**verified by reading** `SealedClassExports.kt`).

- **End state (recommended):** the box constructor is a planned constructor entry and `Value` a
  planned property, both under the arm's `${sealed}_${arm}` prefix, with a new
  `ForwardCallableOrigin` whose Kotlin invocation is the identity on the lowered enum parameter
  (constructor) and the downcast receiver (getter). The enum lowering, the error slot, the ABI
  contract check and overload numbering then come from the shared emitters. **Inferred, not
  traced:** `ForwardCallablePlan` is plain data keyed by symbol strings
  (`ForwardMarshallingModel.kt:594`), so a synthetic plan is expressible, but nobody has checked
  that `addForwardKotlinPlanExport` can spell an identity invocation without a new branch. If it
  cannot, the cost is one branch in that emitter, not a redesign. About 13 main files.
- **Patch:** hand-write the two exports in `SealedClassExports.kt` and the two externs in
  `CirSealedRenderer.kt`. About 9 main files. It leaves a second hand-rolled error-slot
  spelling and two exports the plan-side ABI contract does not see, which is the drift ADR-111
  was written to end.

### Predicate changes

- Delete the `ENUM_CLASS` refusal (`ForwardClassMembership.kt:81-84`); add `isEnumArm()`.
- `isSealedSubclass()` (`:149`) answers **false** for an `ENUM_CLASS`. The enum route keeps owning
  the enum; the sealed route only adds the box. This makes `rootEnums`'s ADR-125 filter
  (`NugetProcessor.kt:996`) inert instead of wrong (left as is it would un-declare `Family` and
  break requirement 2), and lets the nested-declaration walk (`NugetProcessor.kt:1179`) keep
  declaring an enum nested in the interface.
- Every walk that plans an arm's *members* must skip an enum arm, because those members belong to
  `{Enum}Extensions`: `ForwardCallablePlanner.kt:662`, `ForwardPropertyPlanner.kt:183`,
  `CirClassTranslator.kt:1895`, `SealedClassExports.kt:76`, and the suspend/flow arm walks in
  `NugetProcessor.kt` (`:634`, `:1894-1995`, `:2080-2147`). One helper (`classArms()`) rather than
  a dozen inline filters.
- `CirTranslator.kt:508` records each arm's C# type name for collision detection; for an enum arm
  it must record `{Enum}Arm`, since the enum itself is already recorded at `:503`.
- Diagnostics hints that say "arms may not be enums" / "an enum can never be a subclass"
  (`NugetProcessor.kt:1283-1289`, `ForwardDiagnostic.kt:323`, `:1135`) lose that clause.

## Consequences

- `Kind`, its ten `kind` properties and the sixteen constructors taking one all bind; neither
  diagnostic fires for the shape. `copy(kind)` returns with the constructor.
- A boxed arm owns a counted handle. `new Item(new FamilyArm(Family.A))` without `using` leaks one
  `StableRef` to a permanent singleton: a rising `nuget_live_handles`, no Kotlin memory. This is
  the same contract ADR-148 arm constructors already have.
- **No implicit `Family` to `Kind` conversion in v1.** It compiles (spiked) and reads well, but it
  mints a handle the caller never sees and cannot dispose, in a codebase with no finalizers. Open
  question 2.
- No new handle kind: same `NugetHandles` counter, same release. One new **mint path** (a C#
  constructor producing a handle to a Kotlin singleton from an ordinal), so `LeakTests` gains one
  row, modelled on Row 1c (`LiveHandleTests.cs:190-207`).
- The `Tone` / `Pitch` control (`SealedInterfaceSample.kt:229-241`,
  `SealedInterfaceTests.cs:381-393`, `Tier1SealedInterfaceTest.kt:386`) inverts: `Tone` becomes
  eligible, `ITone` disappears, `PitchArm` appears, and `Pitch` must still be declared exactly once
  as an enum.
- `docs/backlog/fromhandle-no-enum-branch.md` is neither a prerequisite nor fixed. `List<Kind>`
  works through the existing per-sealed-base `viaFromHandle` entry
  (`cir/CirMarshalRenderer.kt:114-117`, verified by reading); `Flow<Kind>` stays inside that
  backlog item's sealed-base hole. `docs/backlog/tier1-interface-bridge-factory-nested-enum-stale.md`
  concerns the interface-bridge factory and is unrelated.
- Deferred: arms that extend another class (out of scope per the issue), enum properties projected
  onto the box, `Value` caching, implicit conversion, nullable `Kind?` inputs (already a separate
  `SEALED_POSITION` reading at `ForwardCallablePlanner.kt:3490`).

## Prior art (to the depth that changes the decision)

- **ADR-006** fixed a Kotlin enum as a C# `enum`. That is what forces a box; everything else here
  follows from it.
- **JVM, ObjC export, Swift export**: an enum is a class on all three, so it implements the
  interface directly and no box exists. Inferred from general knowledge, not researched further:
  none of them has C#'s integral-only enum, so none can inform the box's shape.
- **C#**: wrapping a value type to give it a place in a reference hierarchy is what `object` boxing
  and the one-case-per-class closed hierarchy idiom already do. Inferred; not load-bearing.

## Claims ledger

**Verified by spike:** `StableRef` to an enum entry; `is` discrimination across two enum classes
with colliding ordinals; ordinal recovery; inbound identity; two independent handles per entry;
the C# hierarchy, property patterns, managed `Equals`, and the implicit operator declared on the
abstract base.

**Verified by reading** (file and line inline): the single refusing branch; discriminator by `is`;
`FromHandle` spelling `subclass.name`; `viaDiscriminator` keyed on eligibility; enum classified
before sealed; ENUM bucket first; ADR-006 extension route; `NugetHandles` counting; no finalizers;
`rootEnums` and the nested walk filtering on `isSealedSubclass()`; `CirTranslator.kt:508` recording
arm names; `CirMarshalRenderer` `viaFromHandle`.

**Inferred, nobody has verified,** and what breaks if wrong:

1. ~~A synthetic plan with an identity invocation fits `addForwardKotlinPlanExport` without a new
   branch.~~ **Wrong, as the ledger allowed for.** `invocationExpression`'s `when` is exhaustive over
   `ForwardCallableOrigin`, so the new `ENUM_ARM_BOX` origin needed a branch there. It is one line
   (`-> arguments`, the identity), and the compiler named the site. Cost as predicted.

   The `Value` getter took a different seam than the ADR drafted: it is an ADR-062 **property** plan
   (`ForwardPropertyPlan`), not a callable plan, because that is what `addForwardPropertyPlanExports`
   and `ForwardCirPropertyProjection.classProperty` both read, and a C# property is what the arm
   owes. A property plan is keyed by a receiver rather than an origin, so the new variant there is
   `ForwardPropertyReceiver.EnumArm(base, enum)`, whose access expression is the downcast receiver
   with no member access after it. Both halves still come off one plan and both are contract-checked.
2. An exception escaping a `@CName` export aborts the process. If wrong (it is caught somewhere),
   the error slot on the box export is merely redundant.
3. C# does not find an implicit operator declared on the derived `FamilyArm` when converting to
   `Kind`. Only matters if open question 2 is answered yes; a compile error, not silent.
4. No other reader of `isSealedSubclass()` depends on an enum arm answering true. Eight readers
   were read (`NugetProcessor.kt:955/985/996/1179`, closure `:319/:349/:355`, classifier
   `:311/:347`, `CirClassTranslator.kt:2347`) and none does, but the Tier 1 CS0101 cell for
   `Pitch` is what actually proves it.

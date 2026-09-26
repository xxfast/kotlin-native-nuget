# ADR-168: An interface `var` widened over a read-only base gets an explicit setter implementation

## Status

Accepted

## Context

[ADR-113](113-interface-declaration-on-the-forward-plan.md) left `CirInterfaceProperty.hasSetter`
unset on purpose (ROADMAP line 28): deriving it from the plan would render `{ get; set; }` on the
generated interface, and a class whose own setter [ADR-075](075-collection-property-getter-setter-independence.md)
had refused would then fail to implement it.

That refusal is real for exactly one shape. A class can override an exported base class's `open
val` and an exported interface's `var` with a single Kotlin `override var`:

```kotlin
interface Tally { var count: Int }
open class Scoreboard { open val count: Int = 0 }
class TrainingClicker : Scoreboard(), Tally { override var count: Int = 0 }
```

ADR-075's read-only-base guard refuses `TrainingClicker.count`'s public setter: a C# `override`
cannot add a `set` accessor to a get-only base property (CS0546), so `TrainingClicker.Count` stays
`{ get; }`. If `ITally.Count` renders `{ get; set; }`, `TrainingClicker : Scoreboard, ITally` no
longer implements it: `error CS0535: 'TrainingClicker' does not implement interface member
'ITally.Count.set'` (measured by a scratch `dotnet build` against a hand-edited `Interop.cs`,
net8.0/net10.0 Roslyn). Every other exported interface `var` in the repository's fixtures had no
such base and needed no new render shape; this is the one case the one-line `hasSetter` fix alone
regresses.

## Alternatives Considered

### 1. Explicit interface implementation beside the get-only override (chosen)

`TrainingClicker` keeps `public override int Count { get; }` and gains

```csharp
int ITally.Count
{
    get => Count;
    set { /* the same native setter call the public property would have used */ }
}
```

The Kotlin setter export is minted exactly as it would be for a public setter; only the C# surface
differs. A caller reaches it by declaring the reference as `ITally`, exactly as any other explicit
interface member: `ITally tally = clicker; tally.Count = 5;`.

- Pro: no shape that compiles today regresses. `ITally.Count { get; set; }` is honoured for every
  implementer, including this one.
- Pro: the explicit member is spelled with `forwardSuperInterfaceSpelling`, the same helper the
  class's own base list uses, so it never drifts from how the base list names the interface
  (`ITally` in the same namespace, `global::Ns.ITally` across one, see the ADR-167 amendment below).
- Con: a second render shape for one property. A consumer reading `TrainingClicker`'s public API
  sees a get-only `Count` and has to know to cast to `ITally` to write it; `<remarks>` on the public
  property (below) is the mitigation.

### 2. A fatal named diagnostic, refuse the build

Report the Kotlin conflict (`ERROR_UNSUPPORTED_INTERFACE_VAR_OVERRIDE`-shaped) and stop, the way
[ADR-162](162-per-declaration-error-containment.md) already fails other conflicts.

- Pro: two files instead of about four; no new render shape.
- Con: fails a build that compiles today. `TrainingClicker` is legal Kotlin and its `ITally`
  narrowly worked before this feature (`IFoo` was get-only, so no CS0535 existed). Turning a
  previously-green shape fatal to ship an unrelated widening is a worse regression than keeping it
  get-only. Rejected; kept as the fallback if the explicit-member shape had not been wanted.

### 3. Downgrade `ITally` to `{ get; }` whenever any exported implementer refuses the setter

- Con: cross-declaration coupling. One class's shape (`TrainingClicker`) would punish every other
  implementer (`Abacus`) and every consumer of `ITally`, which lose the setter they could otherwise
  have. Rejected.

### 4. A throwing setter on the class

`public override int Count { get; set => throw new NotSupportedException(); }`.

- Con: [ADR-075](075-collection-property-getter-setter-independence.md)'s Question B already
  rejected converting a build-time-known fact into a runtime throw (`B2`), for the same reasoning:
  everything the planner knows statically is skipped or routed, never made to fail at call time.
  Rejected for consistency.

## Decision

`ForwardPropertyPlanner.collectionSetterOrNull`'s read-only-base branch, on refusing a class
property's public setter, now checks whether the same property also declares a settable `var` on
an exported interface the class implements (`explicitSetterInterfaces`, walked over the class's
direct and transitively re-homed exported interface supertypes, keeping only a `DECLARED`/
`DIAMOND_OVERRIDE` placement so an *inherited* `var` names the interface that actually declares it,
never the intermediate one; a member typed by the interface's own type parameter is excluded,
since ADR-113's carve-out already keeps it get-only and an explicit `set` there would be CS0550).
When that list is non-empty, the setter is still built and exported (the Kotlin `_set_` half is
unchanged either way) but tagged `explicitSetterInterfaces` on the plan instead of attached to the
public property, and the CS0546 diagnostic is reworded to say the setter is "reachable only through
the explicit `ITally.Count` implementation" rather than simply dropped.

`CirClassTranslator` spells each tagged interface with the same `forwardSuperInterfaceSpelling` the
base list uses, so the explicit member's qualifier always matches the base list's own. The renderer
emits the public property get-only, then one explicit member per tagged interface:

```csharp
public override int Count { get { /* ... */ } }

int ITally.Count
{
    get => Count;
    set { /* the same body a public setter would have had */ }
}
```

A refused setter that has **no** interface to attach to (`var lastSlip: Throwable?`, ADR-107) takes
the ordinary path unchanged: get-only public property, no explicit member, one diagnostic.

`ITally` itself renders `hasSetter = plan.setter != null` (ROADMAP line 28's one-line fix), so
`Abacus`, which has no read-only base, satisfies `ITally.Count { get; set; }` with its own ordinary
public setter and gains no explicit member at all; the explicit shape is reserved for exactly the
shape that would otherwise regress.

The sealed route gets no explicit member. A sealed arm's C# base list names only its sealed base,
never any interface it implements (`class Arm : Base`, ROADMAP's new sealed-arm base-list item), so
there is nothing to attach an explicit `ITally.Count` to; rendering one would be CS0540 ("cannot
implement an interface member because it is not implemented on an interface in the base list"). The
same ADR-075 read-only-base guard now also runs on the sealed route (it never received the sealed
base before this change), so an arm's `override var` over the sealed base's `open val` renders a
get-only override with the ordinary named skip, exactly like the non-sealed case with no interface
to fall back to.

## Consequences

- A `var`-bearing exported interface now has `{ get; set; }` for every consumer, including one
  whose own base class already renders the property read-only in C#.
- **Source break.** A C# class that implements a `var`-bearing `IFoo` without declaring a setter no
  longer compiles (CS0535): the interface asks for `set` now. There is no way to keep the old
  get-only shape for a specific interface; widen the class or stop implementing it.
- No ABI change: the Kotlin `_set_` export a case-D property needed already existed as an ordinary
  property setter export; only its C# surface moved from the public property to an explicit member.
- `ITally` gains a setter only for a member whose own `var` setter plans; the ADR-084 v1 boundary
  (a C#-implemented `IFoo` with a `var` has no bridge at all) is unchanged, tracked separately.
- A generic carve-out member (`var item: T`) stays get-only on the interface regardless of any base
  class, per ADR-113's own carve-out; explicit-member tagging never applies to it.

Fixture: `test-library/.../test/perchvar/TrainingClicker.kt` (`Tally`, `Pompom`, `Scoreboard`,
`TrainingClicker` for case D, `Abacus` as the ordinary-implementer control). Tests:
`Tier1InterfaceVarPropertyTest.kt`, `IntegrationTests/InterfaceVarPropertyTests.cs`,
`IntegrationTests/AbstractInterfacePropertyTests.cs` (the `aviary`/`Feathered` control),
`LeakTests/LiveHandleTests.cs` Row 6p. See [ROADMAP.md](../../ROADMAP.md) for the sealed-arm
base-list gap this ADR's own fixture surfaced but does not close.

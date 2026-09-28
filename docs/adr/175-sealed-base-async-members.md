# ADR-175: A sealed base projects its own `suspend`/`Flow`/`StateFlow` members and owns the scope

## Status

Accepted

## Context

Restatement (the contract): forward, Kotlin declares. A C# caller holding a value typed as a sealed
base (`Shape`, the ADR-112 abstract class for a `sealed interface`, or the ADR-009 abstract class for
a `sealed class`) can `await shape.AreaAsync()`, `await foreach` over `shape.Ticks()` and read
`shape.Level.Value`, without downcasting to an arm.

The ROADMAP line that opened this framed the gap as a base `IShape`-typed reference that ADR-174 "never
declares". That premise was stale: since ADR-112 an eligible sealed interface has no `I<Name>` at all, so
the consumer's base reference is the abstract class `Shape`. The restatement above is what shipped.
What happened before this ADR, **verified by a Tier 1 spike** on 2026-09-29 (scratch test deleted after
the run):

1. **Verified:** `sealed interface Shape { suspend fun area(): Int; fun ticks(): Flow<Int>; val level: StateFlow<Int>; suspend fun fallback(): Int = 9 }` with nested arms renders `public abstract class Shape : IDisposable, INugetHandle` and nothing async on it. `MakeShape()` returns `global::Interop.Shape`.
2. **Verified:** each arm (`Circle`, `Dot`) is `: Shape, IAsyncDisposable`, owns its own scope and renders `AreaAsync`, `Ticks`, `Level` over arm-prefixed imports.
3. **Verified:** `Shape.area`, `Shape.ticks`, `Shape.fallback` are named `SKIPPED_UNSUPPORTED_COMBINATION` (`SEALED_BASE_UNROUTED`, "sealed base class, which has no route yet (ADR-116)"). `Shape.level` is dropped **silently**.
4. **Verified:** `fallback` (a default body no arm overrides) is on no C# type.
5. **Verified:** with an enum arm (`enum class Note : Tone`, ADR-157), the boxed `NoteArm` renders no `PitchAsync` (`Note.pitch` is `SKIPPED_ENUM_MEMBER_FUNCTION`).
6. **Verified:** a `sealed class Job { abstract suspend fun run(): Int }` has the identical gap; the same `sealedBaseEntries` path (`ForwardCallablePlanner.kt:1410-1494`, verified by reading) serves both.
7. **Verified by reading:** synchronous base members already plan on the base prefix with a `virtual` C# member and arm `override`s (`ForwardCallablePlanner.kt:1439-1476`). Only the async half lacks a base route. `forwardSuspendRouteMethods`' docstring (`ForwardScopeOwnership.kt`) rests on "the generated sealed base declares no suspend member".

Prior decisions this reads against: ADR-159 (one scope per instance, root-most owner, abstract owner
declares `abstract DisposeAsync`, `override suspend fun` not re-projected), ADR-118 (arm suspend
route; rejected scope on the base because `IAsyncDisposable` there would advertise `DisposeAsync` on
arms that never suspend), ADR-124 (arm Flow route), ADR-116 (`SEALED_BASE_UNROUTED`), ADR-157 (an
enum arm's handle is a `StableRef` to the enum entry, read as `asStableRef<Base>()`, as ADR-157
reports), ADR-174 (interface async members; ruling 2 excludes sealed interfaces from `I<Name>`).

## Alternatives Considered

### 1. The sealed base projects the member and owns the scope, on ADR-159's rules (chosen)

The base carries each admitted async member through the existing legacy emitters with itself as
owner (`shape_area_async`, `shape_ticks_collect`, `shape_get_level_collect`/`_value`), receiver
`asStableRef<Shape>()`, so Kotlin's virtual dispatch reaches an arm override, a default body, and an
enum entry alike. The base is the root-most scope owner. Arms stop re-projecting overrides of those
members. Pros: covers default members and enum arms; reuses ADR-159 unchanged; one C# method per
Kotlin member. Cons: moves scope ownership from arm to base for such hierarchies (ABI-visible).

### 2. Abstract member on the base, arms `override` (keep per-arm scopes)

`public abstract Task<int> AreaAsync(...)` on `Shape`, each arm `public override`. Rejected:
CS0534 on an enum arm, which carries no member (Context 5), and never covers a default body no arm
overrides (Context 4).

### 3. Declare an `IShape` interface beside the abstract class (an ADR-174 mirror)

Rejected: two C# types for one Kotlin type, against ADR-112, which settled that the sealed interface
*is* the abstract class.

### 4. Pin the named skip and close

Rejected: fails the restatement, and the StateFlow half is silent today.

## Decision

Option 1, for sealed classes and eligible sealed interfaces alike.

1. **Admission.** A member `sealedBaseEntries` already admits for membership is projected on the
   base when the class route's own selectors admit it (the ADR-114/119/123 refusals, ADR-147 for a
   generic base). A refused member keeps a named skip with the refusal's reason, not
   `SEALED_BASE_UNROUTED`. The silent `StateFlow` property drop becomes bound or named.
2. **Kotlin half.** The sealed exports builder calls `addSuspendClassMethodExports`,
   `addFlowMethodExports`, `addFlowPropertyExports` with the base as owner and prefix. The emitters accept a sealed owner the way they accept an ordinary abstract
   class (ADR-159's `Brusher`).
3. **Scope.** The base is the ADR-159 owner: `_scopeHandle`, `internal GetOrCreateScope()`,
   `: IDisposable, IAsyncDisposable, INugetHandle`, `public abstract ValueTask DisposeAsync()`.
   Every arm renders `override DisposeAsync()` over its own `Native_Dispose` and its
   `override Dispose()` repeats the scope cleanup (ADR-159 rule 2). Arms declare no scope of their
   own; arm-declared async members use the inherited one. ADR-118's rejection of base scope is
   narrowed, not overturned: it still holds for a hierarchy whose base projects no scope-using member.
4. **No re-projection.** An arm's `override` of a base-projected async member is dropped on both
   halves (ADR-159 rule 4). `forwardDeclaresScopeMember`'s `isArm` branch and `forwardScopeOwner`'s chain walk keep the
   sealed base in the arm's chain.
5. **Enum arms.** Reached by dispatch through the base export: ADR-157's arm handle is a `StableRef` to the entry.
6. **Breaking.** Arm exports for base-declared members disappear (`shape_circle_area_async`), base
   exports appear; `Circle.AreaAsync` becomes the inherited `Shape.AreaAsync` (source-compatible
   for callers, binary break for a consumer compiled against the old package).
7. **Also shipped.** The sealed **class** base (`Job.rest`, the `rest` skip ADR-118 named) is folded in:
   `Job.RestAsync` is on the base, one entry point `test_issue115__job_rest_async`. `SEALED_BASE_UNROUTED`
   no longer applies to a non-generic base's suspend/Flow members; a refused base async member is named
   with the route's own reason. An arm keeps `IDisposable` (source compatibility) and overrides the
   base's `abstract DisposeAsync`. An enum arm (`CurlArm`) and a default body no arm overrides
   (`fallback`) both dispatch through the base export, and the previously silent `Shape.level` now
   binds. Verified at runtime by `IntegrationTests/SealedBaseAsyncTests.cs`
   and `LeakTests`.

### Consumer API

```csharp
Shape shape = ShapeSamples.MakeShape();
int area = await shape.AreaAsync();
int fallback = await shape.FallbackAsync();
await foreach (int tick in shape.Ticks()) { }
int level = shape.Level.Value;
await using (shape) { }

public abstract class Shape : IDisposable, IAsyncDisposable, INugetHandle
{
    public Task<int> AreaAsync(CancellationToken cancellationToken = default) { /* shape_area_async */ }
    public Task<int> FallbackAsync(CancellationToken cancellationToken = default) { ... }
    public KotlinFlow<int> Ticks() { ... }
    public KotlinStateFlow<int> Level { get { ... } }
    public abstract void Dispose();
    public abstract ValueTask DisposeAsync();
    public sealed class Circle : Shape { /* Dispose/DisposeAsync overrides, no AreaAsync */ }
}
```

## Consequences

- Two existing cells flipped, both because `test-library/.../issue115/JobSample.kt` declares
  `open suspend fun rest()` on the `Job` base: `IntegrationTests/SealedSubclassMethodTests.cs`
  (`Job.RestAsync` is on the base and every arm inherits it) and
  `Done_ArmWithNoSuspendMembers_IsNotAsyncDisposable` (`Job` is `IAsyncDisposable`, so `Done` is too,
  correctly, since it inherits `RestAsync`). The Kotlin side gains `job_rest_async`; the Tier 1 absence
  of `job_running_rest_async` holds. ADR-118's declared-only filter stays for arm members the base does
  not declare.
- **Breaking (binary), source-compatible.** Arm exports for base-declared members are gone and the
  scope moves to the base. Source is unchanged, since `Circle.AreaAsync` is now the inherited
  `Shape.AreaAsync`; a consumer compiled against the old package must rebuild.
- Amendment notes (2026-09-29) added to ADR-116, 118, 124, 159 and 174 where this ADR changes their text.
- Verification: `IntegrationTests/SealedBaseAsyncTests.cs`, `Tier1SealedBaseAsyncTest.kt`, the flipped cells
  in `SealedSubclassMethodTests.cs`, and two `LeakTests/LiveHandleTests.cs` rows: 9l (base-typed async
  calls return to baseline) and 9l-race (a 5000-iteration tight loop on `Loaf.area`, which has no
  suspension point). The fixture is `test-library/.../curlup/ShapeSample.kt`.
- Not covered: a generic sealed base (ADR-147 deferral), an exported base above a sealed base that
  owns the scope (no fixture), ineligible sealed interfaces (`IMixed`, unreachable per ADR-174 ruling 2),
  and lambda-parameter and generic base members.
- Known cosmetic, pre-existing: a `flowProperty` getter renders a stray blank line before `}`.

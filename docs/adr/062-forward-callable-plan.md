# ADR-062: Forward callable plan as the single source of truth for ordinary sync bridging

## Status

Accepted. Phase 10 of [MIGRATION.md](../../MIGRATION.md) completes the centralization: ordinary
synchronous callables are planned once and dual-projected; specialized protocols remain on named
legacy routes. `scripts/verify.sh` is green.

## Context

Through ADR-004 the forward generator already used a C# intermediate representation (CIR) for
Interop.cs, while Kotlin `@CName` exports were built independently in export-builder files. ADR-055
added a generation-time ABI contract check so the two halves cannot silently diverge. ADR-061
fixed method/extension nullable and non-primitive returns (including single-call `valueOut` for
nullable primitives).

Even so, each declaration position still re-derived parameter/return mapping, StableRef conversion,
collection materialization, and catch-path defaults (`mapParamType` → `IntPtr`, `defaultValueFor` →
`"0"` for any `kotlin.*`). That duplicated logic was the root of the ADR-060 position-not-type
defects and made ordinary sync migration incomplete.

[MIGRATION.md](../../MIGRATION.md) Phases 1–9 introduced `BridgeType`, `ForwardCallablePlan` /
`ForwardPropertyPlan`, shadow planning, and cutover for each ordinary family. Phase 10 finishes
totality: ordinary paths require a plan; position-level fallthroughs are deleted.

## Decision

### BridgeType classification once

Alias-expanded `KSType` values are classified once by `ForwardBridgeTypeClassifier` into a sealed
`BridgeType` model: Unit, primitives, Char, String, enum, object handles, value classes,
collections (with nested components), nullable composition, specialized protocols, and unsupported
types. No raw KSP type or untyped pointer reaches emission.

### ForwardCallablePlan / ForwardPropertyPlan as single source of truth

Ordinary synchronous callables produce a validated plan that owns:

- invocation identity (symbol, origin, target);
- public C# signature;
- evaluation mode (`EXACTLY_ONCE` or preserved `LEGACY_TWO_CALL`);
- ordered native export/import ABI parameters and directions;
- result convention, error slot, ownership, cleanup, helper requirements.

Properties use `ForwardPropertyPlan` (including `LegacyTwoCall` getters and nullable setter
dispatch). Callables use `ForwardCallablePlan`. A plan is complete before either renderer sees it.

### Dual projection from the same plan

- KotlinPoet: `addForwardKotlinPlanExport` / `addForwardPropertyPlanExports`
- CIR: `ForwardCirPlanProjection` / `ForwardCirPropertyProjection`

Both halves of a planned callable are emitted from that plan. The generator never splits a callable
between plan and legacy paths.

### ADR-055 contract check from plan-derived signatures

`ForwardAbiContract` derives expected signatures from plan native exports (and property calls) and
compares them to both the rendered Kotlin `@CName` set and the CIR `DllImport` set. Mismatches fail
KSP generation with an explicit export name and signature pair.

### What remains on named legacy routes

Specialized protocols stay explicit and are not guessed by a catch-all:

| Route | Reason |
| --- | --- |
| Suspend functions / methods | Async registration protocol (ADR-019+) |
| Flow properties / methods | Collect / callback protocol (ADR-026) |
| Lambda / stored-callback / interface-bridge methods | Callback registration (ADR-012, 037, 039) |
| Sealed-class helpers | Discriminated hierarchy (ADR-009) |
| Generic top-level / class / function families | Erasure and type-arg protocol (ADR-010) |
| Reference-underlying value-class constructors | ADR-035 defers primary planning |

Ordinary unplanned types (unsupported handles, Map/Set **inputs**, nested unsupported components)
are **skipped** with no emission — never `IntPtr` / `"0"` garbage.

### Compatibility decisions (from MIGRATION.md)

- No public generator API or consumer configuration is added.
- Existing working native signatures for green combinations are preserved.
- **Property and top-level nullable-primitive two-call ABI** (ADR-002) is preserved as
  `LEGACY_TWO_CALL` / `ForwardPropertyGetter.LegacyTwoCall`.
- **Method and extension nullable-primitive single-call `valueOut`** (ADR-061) is preserved as
  `EXACTLY_ONCE` with an OUT parameter.
- Switching property/top-level nullable primitives to single-call `valueOut` requires a separate
  versioned ADR.

## Consequences

**Positive**

- Ordinary sync marshalling is centralized; position-specific cascades and pointer fallthroughs are
  gone from the planned path.
- Kotlin and C# halves cannot silently diverge for planned callables (ADR-055 + plan equality).
- Adding a new ordinary type combination means extending classification and planning once, not
  every export/translator loop.

**Negative / follow-ups**

- Specialized protocols still duplicate logic until they gain their own plan adapters.
- Map/Set inputs remain intentionally skipped (no CreateMap/CreateSet helpers yet).
- Nullable Boolean method returns remain unplanned (ADR-061 deferred width); they are skipped rather
  than fallthrough-emitted.

## Amendment (2026-09-07): reserved ABI slot names are an invariant, not a check

A forward projection identifies the generator's own synthetic ABI parameters by string-matching
their literal name, not by position or a marker type: the instance receiver slot is `handle`, an
extension or non-reference value-class receiver is `receiver` or `value`, the ADR-024 exception
slot is `errorOut`, and ADR-061's nullable-primitive out-slot is `valueOut`. A user parameter
spelled the same was never rejected. Every case this actually turned up was loud, not silent: a
`handle`-named constructor parameter duplicates the `IntPtr handle` local a C# constructor wrapper
declares (CS0136), an `errorOut`/`valueOut`-named parameter flips `ForwardAbiContract`'s
name-based direction read so the Kotlin and C# projections disagree before either renders, and a
`String.tag(receiver: String)` extension duplicates the `receiver` parameter on both the C# extern
and the Kotlin `@CName` export. None of these ever crossed the bridge with silently wrong data;
`Interop.cs` (or, for `handle`/`receiver`/`value`/`errorOut`/`valueOut`, the Kotlin export itself)
simply failed to compile.

The fix is a naming invariant applied once, at plan time, so both projections see the shifted
name: `PLAN_OWNED_NAMES` (`handle`, `receiver`, `value`, `errorOut`, `valueOut`) in `Reserved.kt`,
applied through `String.bridgeParameterName()` at the eight construction sites in
`ForwardCallablePlanner.kt` where a user's Kotlin identifier enters the plan. A user parameter
spelling one of those names, or that literal followed only by underscores, shifts one underscore
(`handle` -> `handle_`, `handle_` -> `handle__`), the same injective chain rule issue #66 used for
`error`. `value` moves on every callable, not only where it would actually collide, so the rule
stays one predictable spelling rather than a per-callable collision test whose answer depends on
the receiver kind; a property named `value` is unaffected, since it renders `Value` (PascalCase)
and never meets a generator identifier. Two C#-render-time-only names, `nativeResult` and
`hasValue`, join `error` under `CSHARP_OWNED_NAMES` and `csharpParameterName()`, since neither is
declared by the Kotlin `@CName` emitter and both only ever appear as C# wrapper-body locals.

`value` renamed uniformly also renames two already-shipped fixture parameters,
`DescribeNickname(string? value)` -> `DescribeNickname(string? value_)` and
`DescribeOverloads(int value, bool flag)` -> `DescribeOverloads(int value_, bool flag)`: a
source-breaking change for a C# caller using named arguments, noted for the 0.6.0 upgrade notes.

A structural `role` field on `ForwardAbiParameter` (an enum distinguishing a generator-owned slot
from user data, checked once rather than string-matched at each read site) was considered and
deferred: it would replace this same name lookup at roughly twenty `ForwardAbiParameter`
construction sites, and it cannot reach `ForwardAbiContract`, which derives its expected
signatures from a rendered KotlinPoet `FunSpec`, a stage that has already discarded any such
field. Tracked in [ROADMAP.md](../../ROADMAP.md) Phase 3.

See `test-library/.../test/reserved/ReservedNamesSample.kt` and
`IntegrationTests/ReservedNamesTests.cs` for the fixture, and `Reserved.kt`'s
`PLAN_OWNED_NAMES`/`bridgeParameterName()` for the mechanism.

## Amendment (2026-09-07): method-name keyword escaping moved to render time

`publicSignature.name` on a plan is PascalCase after this ADR, so no planner path ever escaped a
C# keyword into a method name, and the two `ForwardCirPlanProjection.kt` sites that stripped a
leading `@` back off with `removePrefix("@")` were dead code. C# keyword escaping of a member name
is now a render concern only: `ForwardPublicSignature.csharpName` (`toCSharpName(name)`) is read at
the six public-member name sites in `ForwardCirPlanProjection.kt`. Extern and entry-point name
derivations (`Native_${name}` and friends) keep the raw plan name, since neither is ever rendered
as a C# identifier a keyword could collide with. `ForwardCallablePlanValidator.validate` now
`require`s that a plan name never starts with `@`, so a plan itself can never carry an escaped
name again. See `ForwardCirPlanProjectionTest`. The extern fallback for CIR that carries no plan name now
refuses an escaped name outright, see [ADR-090](090-ordinary-class-method-overloads.md)'s
2026-09-10 amendment.

## References

- [ADR-004](004-cir-intermediate-representation.md) — CIR model and dual emission
- [ADR-002](002-nullable-two-call-pattern.md) — preserved two-call for properties and top-level
  nullable primitives
- [ADR-055](055-forward-abi-contract-check.md) — generation-time ABI contract check
- [ADR-061](061-method-return-marshalling.md) — method/extension returns and valueOut
- [MIGRATION.md](../../MIGRATION.md) — phased cutover checklist

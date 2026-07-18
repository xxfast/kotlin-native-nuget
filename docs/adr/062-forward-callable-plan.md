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

## References

- [ADR-004](004-cir-intermediate-representation.md) — CIR model and dual emission
- [ADR-002](002-nullable-two-call-pattern.md) — preserved two-call for properties and top-level
  nullable primitives
- [ADR-055](055-forward-abi-contract-check.md) — generation-time ABI contract check
- [ADR-061](061-method-return-marshalling.md) — method/extension returns and valueOut
- [MIGRATION.md](../../MIGRATION.md) — phased cutover checklist

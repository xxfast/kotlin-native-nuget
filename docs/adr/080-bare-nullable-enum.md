# ADR-080: Bare nullable enums (`Mood?`): close the property-getter crash by binding, not skipping

## Status

Accepted

## Context

A bare `Nullable(Enum)` property (`var mood: Mood?` on an ordinary class, **not** wrapped in a
value class) crashes `packNuget`. **Verified by runtime spike** (temporary `var spikeMood: Mood? =
null` on `PropertyProbe`, `./gradlew :test-library:kspKotlinMacosArm64`, fixture reverted):

```
e: [ksp] java.lang.IllegalStateException: Forward property direct nullable getter is invalid for
io.github.xxfast.kotlin.native.nuget.test.cat.PropertyProbe.spikeMood:
Enum(qualifiedName=io.github.xxfast.kotlin.native.nuget.test.cat.Mood, csharpType=global::TestLibrary.Cat.Mood)
```

The mechanism (verified by source reading, this session):

- `ForwardPropertyPlanner.isPlannable`'s `Nullable` branch recurses to `isPlannable(Enum)` which is
  `true` (`ForwardPropertyPlanner.kt:404`), so the property is accepted.
- `hasValueFanOutInner()` has cases for `Primitive`, `Instant` and `ValueClass(Primitive|Enum)` but
  **no bare-`Enum` case** (`ForwardPropertyPlanner.kt:415-425`), so the getter takes the `Direct`
  route instead of `LegacyTwoCall`.
- `ForwardPropertyKotlinEmitter.addGetter`'s `Nullable` `when` (String / ObjectHandle / Interface /
  Collection / ValueClass) has no `Enum` branch either, so it hits
  `error("Forward property direct nullable getter is invalid for ...")`
  (`ForwardPropertyKotlinEmitter.kt:56-94`).

Bare `Mood?` **parameters and method returns** do not crash. **Verified by the same spike**: both
`fun spikeSoothe(mood: Mood?): Mood?` and the parameter-only variant skip the whole callable with

```
w: [ksp] ... [nuget:SKIPPED_UNSUPPORTED_RETURN] Skipping ...spikeSoothe: its NULLABLE type
combination is not supported. expose a non-nullable wrapper, or a separate has-value/value pair,
instead of a nullable Boolean return
```

(the parameter route is `inputSkipReason()`'s `Nullable` fallthrough `else ->
ForwardPlanSkipReason.NULLABLE`, `ForwardCallablePlanner.kt:1639`; `NULLABLE` maps fixedly to
`SKIPPED_UNSUPPORTED_RETURN`, `ForwardDiagnostic.kt:147`, so a parameter skip is mislabelled as a
return skip and the hint talks about "nullable Boolean return", a pre-existing wording bug, see
Consequences).

This is the same latency class as ADR-069's top-level `Boolean?` crash: an accepted plan whose
emitter has no route. ADR-069's precedent is decisive: it closed that hole by **binding `Boolean?`
at every forward position**, not by downgrading the crash to a skip. ADR-079 has since built every
shape a bare nullable enum needs. `Temperament?` (`value class Temperament(val value: Mood)`) rides
the has-value fan-out with the `int` ordinal in the value slot at property, parameter, method
return and top-level positions. A bare `Mood?` is exactly those shapes with the box/unbox step
deleted: the wire is already the ordinal.

## Alternatives Considered

### 1. Bind `Mood?` at every ordinary forward position (chosen)

C# sees `Mood?` (`Nullable<Mood>`), symmetric with `Temperament?` from ADR-079 and `bool?` from
ADR-069. Kotlin `null` ⇄ C# `null`; a present value crosses as the `int` ordinal.

- Pros: ADR-069 precedent (crash of this class was closed full-width); every shape already exists
  from ADR-079, so this is branch additions, not new machinery; deletes the crash *and* the
  parameter/return skips in one design; `csharpType()` already renders `Nullable(Enum)` as `Mood?`
  for free (`ForwardCirPropertyProjection.kt:473-479`, verified).
- Cons: touches the callable planner as well as the property path (~6 source files total); new
  exports change the forward ABI contract hash (expected, ADR-054/078 machinery handles it).

### 2. Property crash → named skip only (narrow)

Exclude bare `Enum` in `isPlannable`'s `Nullable` branch so the property records
`SKIPPED_UNSUPPORTED_PROPERTY` (the ADR-064 machinery already shipped); parameters/returns keep
their existing skip.

- Pros: one branch in one file plus a Tier 1 diagnostic cell; converts a build abort into an
  actionable warning immediately.
- Cons: leaves `Mood?` unsupported everywhere while `Temperament?` (the strictly more complex
  wrapped case) binds, an incoherent support matrix a consumer cannot predict; contradicts the
  ADR-069 precedent.

### 3. Property-only full binding

Bind `Mood?` properties (the crashing position), leave parameter/return on the named skip.

- Pros: half the surface of Option 1.
- Cons: same incoherence as Option 2 across positions; ADR-069 explicitly rejected shipping a
  position-partial nullable primitive.

## Decision

Option 1. Bind bare `Nullable(Enum)` at property (class, companion, top-level, extension),
constructor/method/extension/top-level parameter, method return and top-level return, as C#
`Mood?`, riding ADR-079's has-value shapes with the value-class box/unbox step removed. The enum
still converts ordinal ⇄ entry at the boundary (`.ordinal` out of Kotlin, `Mood.entries[value]`
into Kotlin, `(Mood)value` / `(int)value` on the C# side), exactly as the non-null enum wire
already does.

### Touch points, property path (all verified by source reading, this session)

- `ForwardPropertyPlanner.hasValueFanOutInner()`: add `is BridgeType.Enum -> type`
  (`ForwardPropertyPlanner.kt:415-425`). This alone flips the plan to
  `LegacyTwoCall`/`NullableDispatch` with correct wires: presence `BOOLEAN`, value `INT32`
  (`wireType()`'s `Enum` branch, `:435`), setter `valueParameter(Enum)` = `INT32` with
  `ORDINAL_TO_ENUM` (`:473-477`).
- `ForwardPropertyPlan.validate()`: widen `isNullableLegacyPrimitive` to admit a bare `Enum` inner
  (`ForwardPropertyPlan.kt:60-64`), else the new plan fails its own validator.
- `ForwardPropertyKotlinEmitter.addNullableValueGetter`: add `is BridgeType.Enum` branch returning
  `Int` with body `"${access}!!.ordinal"` (`ForwardPropertyKotlinEmitter.kt:182-235`).
- `ForwardPropertyKotlinEmitter.valueExpression`'s `Nullable` `when`: add
  `is BridgeType.Enum -> "${inner.qualifiedName}.entries[value]"` (`:305-329`). The
  `NullableDispatch` `set` export's value parameter is the bare inner (`valueParameter(fanOutInner)`),
  so `kotlinInputType(Enum) -> Int` already works (`:351`).
- `ForwardCirPropertyProjection.legacyGetter`: admit `Enum` in the `require` and add
  `returnExpression` case `"(${inner.csharpType})value"` (`ForwardCirPropertyProjection.kt:268-299`).
  The setter's `value.Value` lowering already has an `Enum` case (`(int)$name`, `:400`), and the
  public property type renders `Mood?` via the generic `Nullable` case (`:473`). Both verified.

### Touch points, parameter and return path (mirrors ADR-079 minus box)

Planning (`ForwardCallablePlanner.kt`), verified by source reading:

- `inputSkipReason()`'s `Nullable` branch: admit `is BridgeType.Enum -> null`, then extend the
  ADR-069/079 input fan-out pair in `nativeInputParameters` (`${name}HasValue: Boolean` +
  `${name}: Int` ordinal, `ORDINAL_TO_ENUM` on the value slot).
- Method return: `nullableResultShape` gains an `Enum` branch on the ADR-061 single-call `valueOut`
  shape, `valueOut` typed at the plain `int` ordinal via the existing `valueOutTransferType()`.
- Top-level return: `topLevelNullablePrimitivePlan`'s admission gate, its `require`, its
  `valueWireType` (`INT32`, not the `else -> INT64` default), its `ENUM_ORDINAL` helper and its
  `ENUM_TO_ORDINAL` result conversion.

Projection, **added after implementation: the original draft of this ADR missed both files.** The
Inferred claim below held in the sense that no *hidden* validator rejected the type, but three of
ADR-079's `require`s spell their admissible inners out explicitly and had to be widened by hand:

- `ForwardKotlinPlanEmitter.kt`: `addNullableResult` gains an `is BridgeType.Enum` branch (BOOLEAN
  result, `valueOut.reinterpret<IntVar>().pointed.value = result.ordinal`); `loweredArgument`'s
  `Nullable` `when` gains `if (${name}HasValue) Mood.entries[${name}] else null`; the
  `LegacyTwoCall` route needs its `require` widened plus `Enum` cases in `valueExpression`
  (`$invocation!!.ordinal`) and `valueDefault` (`"0"`).
- `ForwardCirPlanProjection.kt`: the C#-half equivalents. `callArgument`'s `Nullable` `when` gains
  `mood.HasValue, (int)mood.GetValueOrDefault()`; the nullable-result projection gains
  `hasValue ? (Mood)valueOut : (Mood?)null`; `staticLegacyTwoCall` needs its `require` widened plus
  `dllImportReturnType` `"int"` and `returnExpression` `(Mood)__nuget_value`.

The three widened `require`s are `ForwardKotlinPlanEmitter`'s and `ForwardCirPlanProjection`'s
`LegacyTwoCall` guards and `ForwardCirPropertyProjection.legacyGetter`'s. Each already listed
`Primitive || Instant || ValueClass`; none of them is reachable by classification alone, so they
fail the build loudly rather than emitting wrong output, which is what happened during
implementation.

The exact wire and shape for each position is what ADR-079 shipped for `Temperament?` with the
`Temperament(...)` wrap removed; no new ABI shape is introduced.

**Total, as built: 7 files, 15 edit sites.** The 6 sites this ADR originally listed were correct
individually but understated the change by both emitter halves.

**Inferred (not yet built): no other classifier or validator rejects a bare `Nullable(Enum)` once
the sites above admit it.** The property path was walked end to end in source; the callable path
was priced against ADR-079's just-shipped branches but not spiked with a build. If a hidden
`require` trips, it will be a loud build failure, not silently wrong output.

### Fixtures and tests

`test-library` fixtures at each position (e.g. `var mood: Mood?` on `PropertyProbe` + companion /
top-level / extension variants, `fun soothe(mood: Mood?): Mood?`, a top-level `Mood?` return, a
constructor `Mood?` parameter), `IntegrationTests` coverage mirroring
`ValueClassNullableUnderlyingTests.cs`, and Tier 1 cells mirroring
`Tier1ValueClassEnumUnderlyingTest`'s inverted cell. There is deliberately no fixture today; that
is why the crash went unseen (same reason as ADR-069's).

As shipped: `test-library/.../cat/NullableEnumSample.kt`, `IntegrationTests/NullableEnumTests.cs`
(15 facts), and `Tier1BareNullableEnumTest` (4 cells: property fan-out, parameter fan-out, method
return `valueOut`, top-level two-call). The Tier 1 cells are load-bearing for coverage, not
decoration: every branch this ADR adds is otherwise reachable only through the Kotlin/Native
`:test-library` KSP run, which has no coverage tooling.

## Consequences

- `Mood?` binds as C# `Mood?` everywhere `Temperament?` does; the crash and the
  `NULLABLE` skips for bare nullable enums disappear.
- New exports change the forward ABI contract hash; ADR-054/078 startup checks surface any
  stale-shim mismatch by design.
- **Out of scope, split out:** `Char?` at a property position hits the *same* Direct-route crash
  (`hasValueFanOutInner` has no `Char` case and `addGetter`'s `Nullable` `when` has no `Char`
  branch; verified by source reading, not runtime-reproduced). `Char?` stays deferred with
  ADR-061/069's Char-width reasoning; it should at minimum get the Option-2 style named skip when
  touched.
- **Out of scope, split out:** `ForwardPlanSkipReason.NULLABLE` maps fixedly to
  `SKIPPED_UNSUPPORTED_RETURN` with a "nullable Boolean return" hint even when the offender is a
  parameter (verified by spike). Once this ADR lands the enum instances vanish, but any remaining
  `NULLABLE` input skip keeps the misleading wording.
- Nullable enum as a collection component stays out of scope (nullable components have no write-side
  representation, see the ADR-073 follow-up items).

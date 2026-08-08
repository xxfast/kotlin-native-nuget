# ADR-079: `Nullable(ValueClass)` over Primitive- and Enum-underlying value classes: has-value fan-out per position

## Status
Accepted

## Context

ADR-077 shipped value classes at every ordinary position, including `Nullable(ValueClass)` for the
String and ObjectHandle underlyings, where Kotlin `null` rides the null pointer that the underlying
wire already has. It deliberately deferred the `Nullable` x {Primitive, Enum} underlying
combination (sub-item 4's Scope note, carried on ROADMAP line 104): a `double` or `int`-ordinal
wire has no null pointer, so `null` vs a legitimate `0.0` / first-ordinal is indistinguishable
in-band. `Dosage?` (`value class Dosage(val milligrams: Double)`) and `Temperament?`
(`value class Temperament(val mood: Mood)`) keep a named `VALUE_CLASS` skip today.

The repo already ships an out-of-band null channel for exactly this problem, in three
position-specific shapes (all **verified** in source):

- **Input parameters** (constructor, method, extension, top-level, companion): a nullable
  primitive fans out to two adjacent native parameters, `${name}HasValue` (BOOLEAN) + `${name}`
  (the primitive's wire), `ForwardCallablePlanner.nativeInputParameters` lines 1085-1104; Kotlin
  reassembles `if (nameHasValue) name else null` (`ForwardKotlinPlanEmitter.loweredArgument` line
  806); C# passes `name.HasValue, name.GetValueOrDefault()`
  (`ForwardCirPlanProjection.callArgument` line 518).
- **Method / extension / object / companion returns**: ADR-061's single call, BOOLEAN has-value
  return + `valueOut` POINTER out-parameter (`nullableResultShape`'s Primitive branch, planner
  lines 1265-1286; Kotlin `addNullableResult` Primitive branch; C# `checkedNullableValueBody`).
- **Properties** (class, top-level, extension, companion): ADR-002's `LegacyTwoCall` getter
  (presence export + `_value` export) and `NullableDispatch` setter (`set` + `set_null` exports),
  `ForwardPropertyPlanner` lines 174-241.
- **Top-level function returns**: ADR-002's two-call `${export}_has_value` + `${export}_value`
  (`topLevelNullablePrimitivePlan`, planner lines 625-698).

ADR-076 then proved the exact composition this ADR needs: nullable `Instant` rides all four of
those shapes with a semantic conversion (ticks) composed at the ends while the wire slot stays a
raw primitive (**verified**: the `BridgeType.Instant` branches adjacent to every Primitive branch
named above). A nullable primitive/enum-underlying value class is the same composition with the
ADR-077 box/unbox step instead of the ticks step, plus the enum's ordinal step for the enum
underlying, composed inside the emitted expressions exactly as ADR-077's single-slot-conversion
rule requires (`ForwardTransfer.conversion` is one field; conversions do not stack, **verified**
ADR-077 Context).

One more constraint discovered while reading (bare-enum status, all **verified by source
reading**, no runtime repro run):

- A **bare** `Mood?` has no has-value wire anywhere: `nullableResultShape` has no Enum branch
  (method return skips), `inputSkipReason`'s Nullable branch has no Enum case (parameter skips
  with `NULLABLE`), and `nativeInputParameters`' Nullable `when` would `error()` if one got
  through.
- A bare `Mood?` **property** is a latent build crash, not a skip:
  `ForwardPropertyPlanner.isPlannable`'s Nullable branch recurses (`isPlannable(Enum)` is true,
  line 365-372), `isNullableLegacyPrimitive` is false for Enum so the getter takes the `Direct`
  route, and `ForwardPropertyKotlinEmitter.addGetter`'s Nullable `when` has no Enum branch, so
  `packNuget` dies at `error("Forward property direct nullable getter is invalid for ...")` (line
  93). No fixture declares a `Mood?` property, which is why this is unseen; same latency class as
  ADR-069's top-level `Boolean?` crash. **Fixing bare `Mood?` is out of scope here** (this ADR is
  the value-class wrapper only, per the ROADMAP item), but the crash is recorded for a follow-up
  ROADMAP entry, and nothing in this ADR makes it worse.

## Alternatives Considered

### 1. Has-value fan-out, reusing each position's shipped nullable-primitive shape (chosen)

`Dosage?` / `Temperament?` ride the exact ABI shape a bare `Double?` / (hypothetical) enum
ordinal rides at the same position, with the value slot carrying the underlying's wire (the
primitive's own wire, INT32 ordinal for an enum) and the box/unbox + ordinal steps composed in the
emitted expressions.

- Pros: zero new wire machinery; per position this is the shape C# consumers and the validator
  already know; the ADR-076 Instant branches are a line-by-line template; the public surface is
  `Nullable<T>` over the shipped `readonly record struct`, identical to `ChartId?`
  (**verified**: `ValueClassNullableTests.cs` ships `Nullable<ChartId>`).
- Cons: two native parameters (input) or an out-parameter (return) instead of one; a dead default
  value crosses when has-value is false. Identical to the shipped nullable-primitive cost.

### 2. Box to a StableRef pointer and ride the null pointer

Make `Dosage?` cross as a nullable POINTER to a boxed instance, like `ChartRef?`.

- Pros: one nullable shape for all underlyings.
- Cons: an allocation + StableRef lifetime per call for one double; diverges from the non-null
  `Dosage`, which already crosses as a raw `double` (a nullable spelling would be a different
  kind of thing than its non-null spelling); ADR-061 and ADR-077 alternative 2 both rejected
  boxing for this reason. Rejected.

### 3. In-band sentinel (NaN, `-1`, `int.MinValue` ordinal)

Rejected outright: every sentinel is a legitimate underlying value for some value class
(`Dosage(Double.NaN)` is constructible; ordinals are dense from 0). Silent wrong answers.

### 4. Defer again

Rejected: the restatement is a ROADMAP commitment; the machinery to compose is fully shipped and
verified; NYTimes-class consumers hit nullable ID/quantity wrappers routinely.

## Decision

`Dosage?` and `Temperament?` bind at every ordinary position as C# `Dosage?` / `Temperament?`
(`Nullable<T>` over the generated `readonly record struct`; automatic because the struct is a
value type, **verified** via `isCSharpReferenceType` and the shipped `ChartId?` surface). The wire
is the position's existing nullable-primitive shape with the value slot at the underlying's wire:

| Position | Shape (all shipped, verified) | Value slot wire |
|---|---|---|
| Ctor / method / extension / top-level / companion parameter | `${name}HasValue` BOOLEAN + `${name}` adjacent pair | primitive's wire / INT32 ordinal |
| Method / extension / object / companion return | ADR-061 single call: BOOLEAN has-value + `valueOut` POINTER out | valueOut writes primitive / ordinal |
| Property getter | ADR-002 `LegacyTwoCall`: presence BOOLEAN + `_value` call | `_value` returns primitive / ordinal |
| Property setter | ADR-002 `NullableDispatch`: `set(value)` + `set_null()` | `set`'s value at primitive / ordinal |
| Top-level return | ADR-002 two-call: `_has_value` + `_value` | `_value` returns primitive / ordinal |

### Transfer tagging (the single-slot rule)

- **Input pair**: `${name}HasValue` keeps `Primitive(BOOLEAN)` / `DIRECT` (unchanged from the
  nullable-primitive pair). The value slot's transfer carries the **ValueClass** (non-null) with
  `BOX_VALUE_CLASS`, mirroring how the Instant pair's value slot carries `Instant` with
  `TICKS_TO_INSTANT` (**verified**, planner lines 1108-1127). This satisfies
  `ForwardCallablePlanValidator.requiredConversion` (a ValueClass-typed transfer must carry
  `BOX_VALUE_CLASS`, **verified** ADR-077 Context) and keeps the DllImport parameter rendered
  from `wireType.csharpType()` (**verified**: `nativeCsharpType()` reads the wire type except for
  the nullable-string special case).
- **Single-call return**: the outer result transfer carries `Nullable(ValueClass)` with
  `UNBOX_VALUE_CLASS` (the validator unwraps Nullable before demanding the conversion,
  **verified** ADR-077 sub-item 3); `valueOut`'s own transfer carries the **bare underlying
  primitive** (`Primitive(INT)` for the enum ordinal) with `DIRECT`, mirroring Instant's
  `Primitive(LONG)` valueOut (**verified**, planner lines 1304-1318). This is load-bearing for a
  Boolean underlying: ADR-069's `[MarshalAs(UnmanagedType.I1)]` emission keys on the
  out-parameter's transfer being `Primitive(BOOLEAN)`, so typing valueOut as the bare primitive
  inherits the 1-byte contract with no new code. **Verified during implementation**: the keying
  site is `ForwardCirPlanProjection.outParameterMarshalPrefix`, an equality test against
  `BridgeType.Primitive(PrimitiveKind.BOOLEAN)` read off `parameter.transfer.type`; the generated
  `Native_QuarantineFlag` import carries `[MarshalAs(UnmanagedType.I1)] out bool valueOut` and the
  `Flag(false)`-vs-`null` fixture cell passes.
- **Helper sets**: `planOrSkip` and `topLevelNullablePrimitivePlan` both already collect
  `VALUE_CLASS` (+ `UTF8`/`ENUM_ORDINAL` per underlying) from public parameter types via
  `unwrapNullable() as? ValueClass` (**verified**, planner lines 703-709 and 876-895), so
  nullable-wrapped inputs add their helpers with no change. The **result** path must add
  `VALUE_CLASS` (+ `ENUM_ORDINAL` for an enum underlying) in the new `nullableResultShape`
  branches, as sub-items 3/4 did for String/ObjectHandle. `ForwardPropertyPlanner`'s helper
  `when` already handles `unwrapNullable()` ValueClass (**verified**, lines 196-209).

### Planner changes

1. `inputSkipReason()`'s `Nullable` / `ValueClass` case (lines 1525-1530): admit
   `Primitive`/`Enum` underlyings (the condition becomes
   `inner.underlying.isOrdinaryValueClassUnderlying()`).
2. `nativeInputParameters()`'s `Nullable` / `ValueClass` case (lines 1073-1083): dispatch on the
   underlying. String/ObjectHandle keep the single pointer-shaped parameter; Primitive/Enum fan
   out to the HasValue pair per the tagging above.
3. `nullableResultShape()`'s `ValueClass` case (lines 1238-1263): add the Primitive/Enum arm
   returning the BOOLEAN + `valueOut` shape (copy the Primitive branch at lines 1265-1286, outer
   transfer re-typed `Nullable(ValueClass)` + `UNBOX_VALUE_CLASS`, valueOut typed at the bare
   underlying primitive; enum's valueOut is `Primitive(INT)`).
4. `staticEntry`'s two-call gate (lines 625-628) and `topLevelNullablePrimitivePlan`'s `require`
   (line 666): admit `Nullable(ValueClass)` with Primitive/Enum underlying; the `_value` call's
   wire is the underlying's wire.
5. `ForwardPropertyPlanner`: extend both `isNullableLegacyPrimitive` sites (lines 174-175 getter,
   232-233 setter) to include `type.type is ValueClass` with Primitive/Enum underlying, and widen
   `isPlannable`'s Nullable/ValueClass condition (lines 365-372) the same way. **Correction found
   during implementation**: there is a *third* copy of the same predicate, in
   `ForwardPropertyPlan.validate()` (`ForwardPropertyPlan.kt` line 57), which rejects a
   `LegacyTwoCall` getter or a `NullableDispatch` setter on any type it does not recognize. Missing
   it fails `packNuget` with "uses LegacyTwoCall for non-nullable primitive ...", so all three
   sites must widen together. The `_value` /
   `set` wire already resolves through `wireType()` / `inputWireType()`'s ValueClass delegation to
   the underlying (**verified**, lines 405, 412), and `valueParameter(type.type)` already tags
   `BOX_VALUE_CLASS` via `conversion()`'s ValueClass branch (**verified**, line 449).

### Kotlin emitter changes (`ForwardKotlinPlanEmitter`, `ForwardPropertyKotlinEmitter`)

- `loweredArgument`'s `Nullable`/`ValueClass` case (line 823): for a Primitive/Enum underlying
  emit the HasValue guard composing `valueClassUnderlyingLowering`:
  `if (dosageHasValue) Dosage(dosage) else null`,
  `if (tHasValue) Temperament(Mood.entries[t]) else null`. (String/ObjectHandle keep `?.let`.)
- `addNullableResult`'s `ValueClass` case (line 497): for a Primitive/Enum underlying emit the
  `nullablePrimitiveResultBody` shape writing the unboxed underlying through the underlying's
  CVar: `valueOut.reinterpret<DoubleVar>().pointed.value = result.milligrams` /
  `...reinterpret<IntVar>().pointed.value = result.mood.ordinal`, returning `Boolean`. The
  export's Kotlin body is new text; the CVar plumbing (`cVarType(kind)`) is shipped
  (**verified**, lines 520-534, 580).
- `addNullableValueGetter` (property `_value`): add the ValueClass arm returning the underlying's
  Kotlin type with `access!!.milligrams` / `access!!.mood.ordinal` (defaults from
  `primitiveDefault` / `"0"`). `addNullablePresenceGetter` (`access != null`) is type-agnostic
  and unchanged (**verified**, lines 164-176).
- `valueExpression`'s `Nullable`/`ValueClass` setter case (line 291) is **already correct** for
  the NullableDispatch `set` export only if the value arrives non-null; NullableDispatch's `set`
  export receives the bare underlying wire, so the setter arm becomes the non-null composition
  `Dosage(value)` / `Temperament(Mood.entries[value])` when dispatched (the `set_null` arm
  assigns `null`, **verified** `addSetter` lines 219-234). The implementing agent must route the
  NullableDispatch value export through the non-null lowering, not the `?.let` one.

### C# projection changes (`ForwardCirPlanProjection`, `ForwardCirPropertyProjection`)

- `callArgument`'s `Nullable`/`ValueClass` case (line 532): Primitive/Enum underlyings contribute
  the pair `"${name}.HasValue"`, `"${name}.GetValueOrDefault().Milligrams"` (capitalized
  underlying property, **verified** naming via `CirClassTranslator`) with `(int)...Mood` for the
  enum. `GetValueOrDefault()` on an empty `Nullable<Dosage>` yields `default(Dosage)` (zeroed
  struct, constructor bypassed), so the dead value is `0` / ordinal-0; Kotlin never reads it when
  HasValue is false. **Inferred** (C# language semantics, not spiked): `default` of a
  `readonly record struct` bypasses the validating constructor, so no validation can throw on the
  dead value.
- `resultProjection`'s `Nullable`/`ValueClass` case (line 738): dispatch on underlying.
  Primitive/Enum: `returnType = "${type.csharpType}?"`, `nativeReturnType = "bool"`, body a
  `checkedNullableValueBody` variant whose return is
  `hasValue ? new Dosage(valueOut) : null` / `hasValue ? new Temperament((Mood)valueOut) : null`
  (compose `valueClassUnderlyingWireCs` for the valueOut local's type, **verified** helper at
  line 810).
- `legacyGetter` (property two-call, lines 265-287): relax the `require` to admit the ValueClass
  inner; the `_value` read's local type comes from the underlying's wire and the return
  expression becomes the `valueClassGetterReconstruction` composition (helper **verified**, lines
  252-263, already handles Primitive and Enum underlyings for the non-null getter).
- `setterBody`'s NullableDispatch branch (lines 169-181): `valueArgument("value.Value")` must
  learn the ValueClass unwrap (`value.Value.Milligrams`, `(int)value.Value.Mood`); today's
  argument path would pass the struct itself where the import expects `double`/`int` (CS1503 in
  generated code, loud).
- `nativeOutCirParameters` / `nativeCsharpType`: no change expected; the valueOut slot renders
  from its wire type, and no nullable-string special case applies.

### Consumer API

```csharp
// Kotlin: value class Dosage(val milligrams: Double); value class Temperament(val mood: Mood)
// class Patient { var lastDosage: Dosage? = null
//                 fun taper(target: Dosage?): Dosage? = ...
//                 var maybeTemperament: Temperament? = null }
// fun standardDosage(kind: Int): Dosage?   // top-level

[Fact]
public void NullablePrimitiveUnderlyingRoundTrips()
{
    var patient = new Patient();
    Assert.Null(patient.LastDosage);                       // Nullable<Dosage>
    patient.LastDosage = new Dosage(2.5);
    Assert.Equal(2.5, patient.LastDosage!.Value.Milligrams);
    patient.LastDosage = null;                             // NullableDispatch set_null
    Assert.Null(patient.LastDosage);
}

[Fact]
public void NullableEnumUnderlyingRoundTrips()
{
    var patient = new Patient();
    patient.MaybeTemperament = new Temperament(Mood.CALM); // ordinal 0: the sentinel-catching cell
    Assert.Equal(Mood.CALM, patient.MaybeTemperament!.Value.Mood);
    patient.MaybeTemperament = null;
    Assert.Null(patient.MaybeTemperament);
}

[Fact]
public void NullableValueClassParameterAndReturn()
{
    var patient = new Patient();
    Assert.Null(patient.Taper(null));                      // HasValue=false in, null out
    Dosage? tapered = patient.Taper(new Dosage(4.0));      // single-call valueOut return
    Assert.Equal(2.0, tapered!.Value.Milligrams);
    Assert.Null(TestLibrary.StandardDosage(-1));           // top-level two-call
    Assert.Equal(0.0, TestLibrary.StandardDosage(0)!.Value.Milligrams); // legitimate zero != null
}
```

The `Mood.CALM` (ordinal 0) and `0.0` cells are mandatory, per ADR-069's lesson: they are exactly
where an in-band sentinel or a has-value/value mix-up passes for the wrong reason.

### Claim labelling

- **Verified in repo (code read, file:line cited above):** every shipped shape this ADR composes
  (input fan-out pair, single-call valueOut, LegacyTwoCall/NullableDispatch, top-level two-call,
  Instant's composition template, ADR-077's tagging rule and helpers, `Nullable<ChartId>` as the
  shipped C# surface, the underlying-wire delegations in both property planners/projections).
- **Verified by source reading, not runtime-reproduced:** the bare `Mood?` property crash path
  and the bare `Mood?` parameter/return skips. No fixture exists; no packNuget run was made with
  one.
- **Inferred (not spiked):** (a) ADR-069's `MarshalAs(I1)` emission fires for a
  Boolean-underlying value class's valueOut when the slot's transfer is `Primitive(BOOLEAN)`;
  wrong means a Boolean-underlying `Flag?` return silently reads 4 bytes, so the implementing
  agent must either confirm the keying site or include a Boolean-underlying `false` cell in the
  fixture (the fixture cell is the cheaper proof). (b) `default(Dosage)` bypasses the record
  struct's validating constructor (C# semantics; a throwing dead value would be a loud test
  failure, not silent). (c) The full end-to-end compile of the new Kotlin export bodies; adjacent
  shipped bodies are byte-similar, and failure is a loud packNuget error.

## Consequences

- `Dosage?` / `Temperament?` bind at constructor, method, extension, top-level and companion
  parameters, method and top-level returns, and property getter/setter. The named `VALUE_CLASS`
  skip stops firing for them; any Tier 1 test asserting that skip inverts.
- The `isOrdinaryValueClassUnderlying` set and the nullable-admissible set finally coincide;
  `Nullable(ValueClass)` is total over ADR-077's four underlyings.
- Out of scope, unchanged: bare `Nullable(Enum)` (`Mood?`) at every position, including its
  latent property-getter crash (new ROADMAP entry, same class as ADR-069's finding);
  value classes as collection components; Char-underlying value classes (excluded by
  `isOrdinaryValueClassUnderlying` already); the reverse direction.
- The C# surface stays `Nullable<T>` over the existing struct; no generated type changes shape,
  so no ABI or surface break for shipped bindings. New exports only.

# ADR-077: Value classes at ordinary positions: parameters, properties, nullables, and non-String underlyings

## Status
Proposed

## Context

ADR-014 designed value classes for parameter / return / property positions, and the model already
carries `BOX_VALUE_CLASS` / `UNBOX_VALUE_CLASS` conversions plus a `VALUE_CLASS` helper requirement
(`forward/ForwardMarshallingModel.kt`, **verified**). But the planner only consumes them in two
narrow slices:

- a value class's *own* members (ADR-062 Phase 9: receiver crosses as the underlying wire value), and
- a **String-underlying method return** at an ordinary position (`valueClassResultShape`, verified by
  `IntegrationTests/NewsroomReachabilityTests.cs`: `Newsroom.code(): StoryCode`).

Everything else drops the whole callable (**verified** in source):

1. **Ordinary parameter** (`SummaryState(uri: ArticleUri, ...)`): `inputSkipReason()` in
   `ForwardCallablePlanner.kt` has no `ValueClass` branch, falls to `else -> skipReason()` →
   `VALUE_CLASS` skip.
2. **Property** (`val uri: ArticleUri`): `ForwardPropertyPlanner.isPlannable` has no `ValueClass`
   branch (`else -> false`); the property never plans.
3. **`Nullable(ValueClass)`** (`TopStorySection?`): `valueClassResultShape` handles only non-null;
   `isPlannable` rejects `Nullable` of a non-plannable inner; `inputSkipReason`'s `Nullable` branch
   admits only `String`/`ObjectHandle`/`Primitive` inners.
4. **Non-String underlyings**: `valueClassResultShape` opens with
   `if (type.underlying != BridgeType.String) return null`.

NYTimes-KMP's DTO projection (MF-003) exists because of exactly these gaps.

Key constraint, **verified** against `ForwardCallablePlanValidator.requiredConversion`
(`ForwardMarshallingModel.kt`): a transfer whose (nullable-unwrapped) type is
`BridgeType.ValueClass` **must** carry `BOX_VALUE_CLASS` (INTO_KOTLIN) or `UNBOX_VALUE_CLASS`
(OUT_OF_KOTLIN), and `ForwardHelperRequirement.VALUE_CLASS` must be present in the plan's
`helperRequirements`, or `validate()` throws. `ForwardTransfer.conversion` is a **single slot**
(one enum field, verified); conversions do not stack. So an enum-underlying value class cannot be
tagged `UNBOX_VALUE_CLASS` *and* `ENUM_TO_ORDINAL`: the transfer carries only the value-class tag
and the emitters compose the underlying's step into the emitted expression, exactly as the shipped
String underlying already composes UTF-8 (`$invocation.value` on the Kotlin side +
`Marshal.PtrToStringUTF8` on the C# side under a single `UNBOX_VALUE_CLASS` tag, **verified** in
`ForwardKotlinPlanEmitter.addValueClassOrdinaryResult` / `ForwardCirPlanProjection.resultProjection`).

## Alternatives Considered

### 1. Underlying wire value + box/unbox at the boundary (chosen)

The value class crosses as its underlying wire value (String → `STRING`/`POINTER`, primitive → its
primitive wire, enum → `INT32` ordinal, ObjectHandle → `POINTER`/StableRef). Kotlin boxes on entry
(`ChartId(value)`) and unboxes on exit (`result.value`); C# unwraps on entry
(`id.Value`) and reconstructs on exit (`new ChartId(raw)`).

- Pros: exactly ADR-014's shipped design for returns and own-receivers; zero new wire machinery;
  the C# `readonly record struct` (ADR-033/035) already exists with a validating constructor and a
  capitalized underlying property (**verified**: `CirObjectRenderer.renderValueClass`,
  `CirClassTranslator` capitalizes the Kotlin property name, `value` → `Value`).
- Cons: Kotlin-side boxing re-runs the value class's `init` validation on every call; identical to
  the shipped own-member reconstruction (`valueClassReconstruction`'s `Owner(value)`), so not a new
  cost.

### 2. StableRef handle of the boxed instance

Cross every ordinary-position value class as a `POINTER` to a boxed Kotlin instance.

- Pros: one uniform shape for all underlyings.
- Cons: breaks the shipped C# surface: `StoryCode` is already a `readonly record struct`, not a
  handle-backed class; costs an allocation + StableRef lifetime per call; contradicts ADR-014.
  Rejected.

### 3. Nullable value class as a reference-style nullable or a has-value pair

For `TopStorySection?`, either surface a C# reference nullable / boxed class, or always fan out a
`HasValue` boolean + underlying value.

- Rejected per the ROADMAP prescription: C# must see a **nullable value type**
  (`TopStorySection?` = `Nullable<TopStorySection>`; **verified** that
  `BridgeType.Nullable.csharpType()` renders `"${inner}?"` and `isCSharpReferenceType()` already
  classifies `ValueClass` as a value type, so the spelling means `Nullable<T>`). A has-value pair
  is unnecessary for the String and ObjectHandle underlyings, where the wire is already a pointer
  and Kotlin `null` rides the null pointer, exactly like the shipped nullable-String result shape
  (**verified**: `nullableResultShape`'s String branch, `nativeInputParameters`' `Nullable(String)`
  branch). Chosen wire: null pointer for String/ObjectHandle underlyings; the primitive/enum
  underlying × nullable combination is deferred (see Scope).

## Decision

One decision per ROADMAP sub-item, in landing order. All four reuse mechanism verified in this
repo unless labelled inferred.

### Sub-item 1: String-underlying ordinary parameter

Positions: class method, constructor (incl. data-class primary), extension function, top-level
function, companion/object function: all funnel through `planOrSkip` → `nativeInputParameters`
(**verified**), so one planner change covers every position.

- `inputSkipReason()`: add `is BridgeType.ValueClass -> if (underlying == BridgeType.String) null
  else VALUE_CLASS` (widened by sub-item 4).
- `nativeInputParameters()`: add a `ValueClass` branch producing **one** native parameter:
  `wireType = STRING`, `direction = IN`, transfer `(name, type /* the ValueClass */, INTO_KOTLIN,
  VALUE, BORROWED, BOX_VALUE_CLASS)`.
- `planOrSkip`'s helper set: add `ForwardHelperRequirement.VALUE_CLASS` when any input's
  nullable-unwrapped type is a `ValueClass`. Without this the validator's
  `requiredConversion.helper() in plan.helperRequirements` check throws (**verified**: the check
  exists; today the helper is only added when `origin == VALUE_CLASS`).
- Kotlin emitter (`ForwardKotlinPlanEmitter`): `loweredArgument` gains
  `is BridgeType.ValueClass -> "${type.qualifiedName}(${parameter.name})"`; `kotlinInputType` gains
  `is BridgeType.ValueClass -> kotlinInputType(type.underlying, wireType)` (i.e. `String`).
- C# projection (`ForwardCirPlanProjection`): `callArgument` gains
  `is BridgeType.ValueClass -> listOf("${parameter.name}.${type.underlyingPropertyName
  .replaceFirstChar { it.uppercase() }}")` (e.g. `id.Value`; capitalization **verified** against
  `CirClassTranslator.underlyingName`). `isTrivialInput()` already returns `false` for
  `ValueClass`, correctly forcing the custom body path. The public parameter type already renders
  via `csharpType()`'s existing `ValueClass -> csharpType` branch (**verified**).

**Correction, recorded when sub-item 1 shipped:** `planOrSkip` is not the only place that builds a
helper set. `topLevelNullablePrimitivePlan` assembles its own, separately, so adding
`ForwardHelperRequirement.VALUE_CLASS` in `planOrSkip` alone is not enough: a top-level
`fun chartLength(id: ChartId): Int?` hard-fails `validate()` on the missing helper. Both sites need
the addition. Covered by the `chartLength` case in `Tier1ValueClassParameterTest`.

Consumer API (fixture: extend `ClinicSample.kt` with a `ChartId` parameter mirroring
`SummaryState(uri: ArticleUri, ...)`):

```csharp
// Kotlin: data class ChartEntry(val id: ChartId, val note: String)
var entry = new ChartEntry(new ChartId("CH-1"), "checkup");
Assert.Equal("CH-1", entry.Id.Value);          // Id property lands with sub-item 2

// Kotlin: fun Clinic.lookup(id: ChartId): String
string note = clinic.Lookup(new ChartId("CH-1"));
```

### Sub-item 2: String-underlying property getter/setter

- `ForwardPropertyPlanner.isPlannable`: add
  `is BridgeType.ValueClass -> type.underlying == BridgeType.String`. Note the existing `Nullable`
  branch recurses, so this *also* admits `Nullable(ValueClass(String))` properties; either land the
  nullable property facet together with sub-item 3, or temporarily guard the recursion. Recommended:
  guard in this commit (`type.type !is BridgeType.ValueClass`), remove the guard in sub-item 3.
- `ForwardPropertyPlanner.conversion()`: add
  `is BridgeType.ValueClass -> BOX_VALUE_CLASS / UNBOX_VALUE_CLASS` by flow. Today the `else`
  branch **silently** tags the transfer `DIRECT`, and `ForwardPropertyPlan.validate()` never checks
  conversions (**verified**), so nothing would catch the wrong tag.
- `ForwardPropertyPlanner.wireType()` / `inputWireType()` already delegate `ValueClass` to the
  underlying (**verified**), which is the correct getter-result / setter-value wire. The comment on
  `wireType()`'s `ValueClass` branch ("no ordinary property is ever declared `: SomeValueClass` on
  the plan path") becomes false with this change and must be rewritten; the branch bodies themselves
  need no change.
- `ForwardPropertyPlan.validateType`: add
  `is BridgeType.ValueClass -> validateType(type.underlying)` (currently `else -> error`, so a
  value-class property plan crashes validation).
- Kotlin emitter (`ForwardPropertyKotlinEmitter`): `addGetter` gains
  `is BridgeType.ValueClass -> valueBody("$access.${type.underlyingPropertyName}", "errorOut",
  "\"\"")`, returning `String`. `valueExpression` (setter) gains
  `is BridgeType.ValueClass -> "${type.qualifiedName}(value)"`. `kotlinInputType`'s existing
  `ValueClass -> kotlinInputType(type.underlying)` is already correct for the setter value
  (**verified**); its "only ever reached for an extension receiver" comment also goes stale.
- C# projection (`ForwardCirPropertyProjection`):
  - `wireType()`: add `is BridgeType.ValueClass -> underlying wire` (currently `else -> error`).
  - `checkedGetter`: `ValueClass` needs branches in **both** `when`s: `IntPtr nativeResult = ...`
    in the first, and `return new ${csharpType}(Marshal.PtrToStringUTF8(nativeResult)!);` in the
    second. The second `when`'s current `else -> "return nativeResult;"` is a **silent** fallthrough
    (generated C# fails to compile with CS0029, but the generator itself stays green).
  - `setterNativeType`: `ValueClass(String)` → `"string"` (`"string?"` when the outer type is
    `Nullable`, mirroring the existing String branch).
  - `valueArgument`: `ValueClass` → `"$name.Value"` (capitalized underlying property). The current
    `else -> name` **silently** passes the struct where the native import expects `string`
    (CS1503 in generated code).

Consumer API:

```csharp
// Kotlin: data class ChartEntry(val id: ChartId, ...) — val ⇒ get-only
ChartId id = entry.Id;
Assert.Equal("CH-1", id.Value);

// Kotlin: var currentChart: ChartId (on a class) ⇒ get/set
clinic.CurrentChart = new ChartId("CH-2");
```

### Sub-item 3: `Nullable(ValueClass(String))` at all of the above positions

Wire decision: **the null pointer, no has-value pair.** The underlying String already crosses as a
pointer (`STRING` wire in, `POINTER` out), and every shipped nullable-of-pointer shape
(String, ObjectHandle, Collection) rides the null pointer (**verified**). A Kotlin
`StoryUri?` that is `null` puts a null pointer on the wire; non-null unboxes to the (necessarily
non-null) underlying String. There is no third state to disambiguate: the value class's underlying
property is non-nullable by construction.

- Result: `nullableResultShape` gains `is BridgeType.ValueClass ->` (String underlying only in this
  commit): reuse the nullable-String shape, transfer type `Nullable(ValueClass)`, conversion
  `UNBOX_VALUE_CLASS`, helpers `UTF8 + VALUE_CLASS`. The validator unwraps `Nullable` before
  demanding the conversion (**verified**), so `UNBOX_VALUE_CLASS` is exactly what it requires.
- Kotlin result emission (`addNullableResult`, both the ordinary and value-class-origin emitters):
  `is BridgeType.ValueClass -> valueBody("$invocation?.${underlyingPropertyName}", errorName,
  "null")`, returning `String?`.
- C# result (`resultProjection`'s `Nullable` `when`): `is BridgeType.ValueClass ->` returnType
  `"${type.csharpType}?"` (i.e. `Nullable<StoryUri>`; ROADMAP's "nullable value type, never a
  reference nullable" is automatic because the struct is a value type, **verified** via
  `isCSharpReferenceType`), body
  `return nativeResult == IntPtr.Zero ? null : new StoryUri(Marshal.PtrToStringUTF8(nativeResult)!);`.
- Input: `inputSkipReason`'s `Nullable` branch admits `is BridgeType.ValueClass` with String
  underlying; `nativeInputParameters`' `Nullable` `when` gains a `ValueClass` case: one `STRING`
  wire parameter, transfer type `Nullable(ValueClass)`, conversion `BOX_VALUE_CLASS`. Kotlin
  lowering: `"${parameter.name}?.let { ${qualifiedName}(it) }"`; `kotlinInputType`'s `Nullable`
  `when` gains `is BridgeType.ValueClass -> String?`.
- C# input: `callArgument`'s `Nullable` `when` gains
  `is BridgeType.ValueClass -> listOf("${parameter.name}?.Value")` (null-conditional over
  `Nullable<T>` lifts to `string?`). **`nativeCsharpType()` must also learn this shape**: it
  currently special-cases only `Nullable(String)` to `string?`; `Nullable(ValueClass(String))`
  would silently render the DllImport parameter as non-null `string` (CS8604 warning, not an
  error).
- Property facet: remove sub-item 2's `isPlannable` guard. Getter: `addGetter`'s `Nullable` `when`
  gains `ValueClass` (`"$access?.$prop"`, default `null`); setter is the ordinary `Direct` route
  (`NullableDispatch` stays primitives-only, **verified** in `collectionSetterOrNull` and
  `ForwardPropertyPlan.validate`), with `valueExpression`
  `"value?.let { ${qualifiedName}(it) }"` and C# `valueArgument` `"$name?.Value"`;
  `checkedGetter`'s `Nullable` `when` gains the `IntPtr.Zero ? null : new ...` case.

Consumer API (fixture: a nullable `ChartId?` property/parameter mirroring
`TopStoriesState.section: TopStorySection?`):

```csharp
ChartId? previous = entry.PreviousId;       // Nullable<ChartId>
Assert.Null(previous);
entry = new ChartEntry(new ChartId("CH-1"), "note", previousId: null);
Assert.Equal("CH-0", entry.PreviousId!.Value.Value);  // Nullable<T>.Value, then underlying Value
```

### Sub-item 4: Primitive-, Enum-, and ObjectHandle-underlying at ordinary positions

Per-underlying wire table. Each is "same shape, new branch": the underlying's existing shape with
the transfer re-typed to the `ValueClass` and re-tagged `BOX_VALUE_CLASS`/`UNBOX_VALUE_CLASS`,
mirroring how `valueClassResultShape` already wraps the String shape (**verified**):

| Underlying | Wire | Kotlin out / in | C# out / in |
|---|---|---|---|
| `Primitive` | the primitive's wire | `result.prop` / `Owner(v)` | `new X(nativeResult)` / `x.Prop` |
| `Enum` | `INT32` | `result.prop.ordinal` / `Owner(E.entries[v])` | `new X((E)nativeResult)` / `(int)x.Prop` |
| `ObjectHandle` | `POINTER` (StableRef) | `StableRef.create(result.prop).asCPointer()` / `Owner(h.asStableRef<U>().get())` | `new X(new U(nativeResult))` / `x.Prop._handle` |

- The single-slot conversion constraint (Context) means the enum's ordinal step and the handle's
  StableRef step are composed **inside the emitted expressions**, keyed on `type.underlying`; the
  transfer tag stays the value-class pair. The ObjectHandle-underlying *result* additionally needs
  `OWNED_HANDLE` ownership + `DISPOSE_STABLE_REF` cleanup, copied from `handleResultShape`
  (**verified** the validator demands cleanup for `OWNED_HANDLE`).
- `valueClassResultShape` drops its String-only guard and dispatches per underlying;
  `inputSkipReason`'s ValueClass branch widens to these three; the C# `resultProjection`
  `ValueClass` branch (which today hardcodes `PtrToStringUTF8`) dispatches per underlying likewise.
- Receiver-side mechanics for Primitive and ObjectHandle underlyings already ship
  (`valueClassReconstruction`, `ForwardPropertyKotlinEmitter.accessExpression`, **verified**), so
  only the ordinary-position projections are new.
- **Inferred (not verified; no enum-underlying fixture exists anywhere in the repo):** whether
  `CirClassTranslator` renders a *correct C# struct* for an enum-underlying value class at all.
  `isReferenceUnderlying` there is `underlyingType !in KOTLIN_TO_CSHARP_PARAM`, which likely
  classifies an enum underlying as "reference" and takes the ADR-035 deferred-constructor path.
  The implementing agent must add an enum-underlying fixture **first** and check the rendered
  struct before wiring the ordinary positions; if the struct itself is wrong, that is a
  prerequisite fix, not part of this table.
- `Nullable` × {Primitive, Enum} underlying is **deferred** (the wire cannot carry null in-band;
  it would need the existing has-value fan-out shapes). It keeps a named `VALUE_CLASS` skip.
  `Nullable` × ObjectHandle underlying rides the null pointer and may land with this sub-item.

Consumer API:

```csharp
// Kotlin: value class Dosage(val milligrams: Double); fun prescribe(d: Dosage): Dosage
Dosage doubled = clinic.Prescribe(new Dosage(2.5));
Assert.Equal(5.0, doubled.Milligrams);

// Kotlin: value class ChartRef(val patient: Patient); fun refFor(p: Patient): ChartRef
ChartRef r = clinic.RefFor(patient);
Assert.Equal("Oreo", r.Patient.Name);
```

## Consequences

- NYTimes-KMP's `SummaryState`/`Article` constructors, `val uri: ArticleUri` properties and
  `section: TopStorySection?` fields bind without the DTO layer (MF-003).
- Every `else ->` branch listed per sub-item must gain an explicit `ValueClass` case; the
  genuinely *silent* ones (compile-green generator, broken or warning-laden generated C#) are:
  `ForwardPropertyPlanner.conversion()` (`DIRECT` tag, never validated),
  `ForwardCirPlanProjection.nativeCsharpType()` (missing `string?`, CS8604 warning only),
  `ForwardCirPropertyProjection.valueArgument()` (`else -> name`),
  and `ForwardCirPropertyProjection.checkedGetter`'s trailing `else -> "return nativeResult;"`.
  The rest fail loudly at generation time (`error(...)`) or in `validate()`.
- Deferred: `Nullable(ValueClass)` over primitive/enum underlyings; value class as a collection
  element (separate ROADMAP item); inherited members via `CharSequence by value` (ADR-064's open
  product decision, unaffected); the reverse direction.
- ADR-014 remains the consumer-surface authority (`readonly record struct`, unwrapped bridge);
  this ADR extends its wire design to the remaining ordinary positions rather than amending it,
  because the open choices here (nullable wire form, single-slot conversion composition, landing
  order) are plan-machinery decisions ADR-014 predates.

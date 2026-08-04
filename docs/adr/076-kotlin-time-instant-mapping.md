# ADR-076: Map `kotlin.time.Instant` as a first-class forward built-in (`DateTimeOffset`)

## Status

Accepted

## Context

Kotlin APIs increasingly use `kotlin.time.Instant` (stdlib since **2.3**, experimental in 2.1.20) for
absolute moments. ROADMAP line 103 names the consumer hit: NYTimes-KMP's
`Article.published_date: Instant` is dropped from the DTO projection today.

This is a **forward** (Kotlin → C#) known-type mapping in the same category as `String`, not the
Future Improvements "custom type mappers" escape hatch.

### What Instant is (stdlib)

**Verified** against `kotlin-stdlib` 2.4.10 sources (`kotlin/time/Instant.kt`):

- `public class Instant` (ordinary class, **not** a value class) with two stored properties:
  - `epochSeconds: Long` (Unix epoch, UTC-SLS)
  - `nanosecondsOfSecond: Int` in `0..999_999_999`
- Construction: `Instant.fromEpochSeconds(seconds, nanosecondAdjustment)` (and millis / parse variants)
- Range: `MIN_SECOND = -31557014167219200` … `MAX_SECOND = 31556889864403199` (~±1e9 years)
- Convenience sentinels: `DISTANT_PAST` / `DISTANT_FUTURE` at years ≈ ±100000
- Stabilized at `@SinceKotlin("2.3")` with `@WasExperimental(ExperimentalTime::class)`

### What happens in this repo today (verified in source)

1. `ForwardBridgeTypeClassifier.knownScalarType` lists only primitives + `Unit` — not Instant
   (`ForwardBridgeTypeClassifier.kt:281-295`).
2. Instant is a `ClassKind.CLASS` whose FQCN is never in `exportedObjectHandles` (stdlib / klib,
   `containingFile == null`).
3. Classification therefore yields `BridgeType.Unsupported(..., isUnexportedDependency = true)`
   (`:124-138`).
4. Planner skip reason: `UNEXPORTED_DEPENDENCY_TYPE` → diagnostic
   `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` with an `include("kotlin.time")` hint
   (`ForwardCallablePlanner.kt:1264-1267`, `ForwardDiagnostic.kt:182-187`). That hint is wrong for
   stdlib (same class of problem ADR-074 named for platform `actual typealias` targets):
   `include(...)` cannot export Instant as a handle, and a handle would be non-idiomatic anyway.
5. Practical effect: any property, constructor parameter, method parameter, or return that uses
   Instant (or `Instant?`) drops the whole callable / property. NYTimes DTO field drop matches this.

No prior Instant/time ADR or `docs/research/` note exists. FEATURES.md has no time row.

### Load-bearing spikes

#### Spike 1: C ABI for Instant (Kotlin/Native 2.4.10, `macos_arm64`)

Scratch `konanc -produce dynamic` (not a repo build):

| Export shape | Generated C signature (from `*_api.h`) | Runtime |
|---|---|---|
| `fun f(epochSeconds: Long, nanosecondsOfSecond: Int): Long` constructing `Instant` | `KLong f(KLong, KInt)` | **Works** (C driver called it; components reconstructed) |
| `fun f(secsOut: COpaquePointer?, nanosOut: COpaquePointer?): Boolean` writing components | `KBoolean f(void*, void*)` via `LongVar`/`IntVar` | **Works** (got `1704067200` / `123456789`) |
| `fun f(i: Instant): Long` | `KLong f(kref_kotlin_time_Instant)` | Compiles; Instant is an **opaque object ref**, same family as StableRef handles, **not** acceptable public C# |

**Verified claim:** Instant **cannot** cross as a blittable value type. The only acceptable C ABI is
to lower it to scalar components (or an ISO string). Component lowering works.

#### Spike 2: C# `DateTimeOffset` precision and range (net, `dotnet run`)

| Fact | Result |
|---|---|
| `TimeSpan.TicksPerSecond` | `10_000_000` (100 ns per tick) |
| Instant nanos `123_456_789` → DTO | `…1234567` ticks; **89 ns lost** (`nanos % 100`) |
| Round-trip DTO → secs/nanos | recovers nanos rounded down to 100 ns |
| `DateTimeOffset` Unix second range | `[-62135596800, 253402300799]` (year **0001–9999**) via `FromUnixTimeSeconds` |
| Outside that range | `ArgumentOutOfRangeException` (**verified**) |
| Instant `DISTANT_FUTURE` seconds (`3093527980800`) as ticks | `OverflowException` on `seconds * TicksPerSecond` (**verified**) |
| Single `long` epoch-nanos wire | Overflows Instant range after ~**292 years** (`Long.MAX_VALUE / 1e9 / year`); **rejected** as sole wire form |

**Verified claim:** wire form must carry **seconds + nanoseconds** (or an ISO string) to preserve
Instant's representation. C# public type cannot hold the full Instant range or full nanosecond
precision; that is a consumer-API ceiling, not a wire ceiling.

#### Spike 3: multi-component scalars in the forward planner

**Verified in source:** `nativeInputParameters` already **flatMaps** one declared parameter to
zero-or-more ABI parameters; the comment at `ForwardCallablePlanner.kt:890-894` says nullable
primitives are the *sole* multi-ABI fan-out today (`hasValue` + value). Instant is the second
multi-component ordinary scalar and fits that fan-out shape. Reverse-side ADR-056/058/059 already
decompose multi-field values onto the wire; forward value classes (ADR-014) unwrap **one**
underlying only.

## Alternatives considered

### A. Public C# type

#### A1. `System.DateTimeOffset` (chosen)

Idiomatic .NET type for an absolute instant with explicit offset; construct with `Offset = TimeSpan.Zero`
(UTC). Used widely for interop with Unix time APIs (`FromUnixTimeSeconds`, `ToUnixTimeSeconds`).

**Pros:** idiomatic; offset-aware APIs; clear UTC representation (`+00:00`).
**Cons:** value-type nullable is `DateTimeOffset?` (struct `Nullable<T>`), while Kotlin `Instant` is a
**reference** class; range only year 0001–9999; 100 ns precision.

#### A2. `System.DateTime` with `DateTimeKind.Utc`

**Pros:** familiar.
**Cons:** `Kind` is easy to strip/lose; less preferred than `DateTimeOffset` for absolute instants in
modern .NET guidance; same range/precision limits. Rejected for the public surface.

#### A3. Custom `readonly record struct KotlinInstant(long EpochSeconds, int NanosecondsOfSecond)`

**Pros:** lossless range and precision on the C# side.
**Cons:** non-idiomatic (GOALS #2); every consumer reinvents conversion to BCL time types. Rejected
for the public surface (may still appear only as wire intermediates if needed; it must not be the
published API).

#### A4. `long` Unix milliseconds / ticks only

**Pros:** single ABI scalar.
**Cons:** millis drop sub-ms Instant precision; ticks cannot express Instant range without overflow;
not what ROADMAP asked for. Rejected.

### B. Wire form

#### B1. `epochSeconds: Long` + `nanosecondsOfSecond: Int` (chosen)

Matches Instant's stored representation and `fromEpochSeconds` exactly. Two ABI slots per Instant
value (three with a has-value flag when nullable).

**Pros:** full Instant fidelity on the wire; trivial Kotlin lowering; no string alloc/parse.
**Cons:** first multi-component ordinary forward scalar; planner/emitter branches must learn fan-out.

#### B2. Single `long` 100-ns ticks since Unix epoch

**Pros:** one ABI arg; maps almost directly to `DateTimeOffset` ticks math.
**Cons:** cannot represent Instant outside DTO range without overflow (**verified**); loses the
sub-100 ns Instant bits the same as DTO; couples wire to the C# type's ceiling. Rejected as the
**only** wire form.

#### B3. ISO-8601 UTF-8 string (`Instant.toString` / `Instant.parse`)

**Pros:** one POINTER; human-debuggable; full Instant range/precision if parse is exact.
**Cons:** alloc + parse on every crossing; error handling for malformed strings; heavier than
scalars. Rejected for v1 (escape hatch remains possible later).

### C. Scope of the same design

#### C1. Ship Instant alone (chosen for v1 implementation)

ROADMAP: Duration and kotlinx.datetime Instant are "also consider", not required to ship with Instant.

#### C2. Also ship `kotlin.time.Duration` → `TimeSpan` in the same PR

Duration is a **value class** over a packed `Long` (`rawValue`); maps cleanly to `TimeSpan` with the
same 100 ns truncation story. Deferred: separate classifier/planner work; not needed for the NYTimes
DTO hit.

#### C3. Also hardcode `kotlinx.datetime.Instant`

Since kotlinx-datetime **0.7**, `kotlinx.datetime.Instant` is
`typealias Instant = kotlin.time.Instant` (**verified** in `0.7.1` sources:
`DeprecatedInstant.kt`). ADR-018 `expandAliases()` already erases ordinary typealiases, so
**current** consumers of the typealias need no second FQCN if KSP sees the alias.

**Older** kotlinx-datetime (pre-0.7) had a real `kotlinx.datetime.Instant` class. Optional v1
extension: treat that FQCN as the same built-in if it still appears. Default decision: **only**
`kotlin.time.Instant` as the known name; typealiases that expand to it work via ADR-018; pre-0.7
class form is deferred unless a fixture forces it.

## Decision

### 1. Public C# surface

| Kotlin | C# |
|---|---|
| `kotlin.time.Instant` | `System.DateTimeOffset` (UTC, `Offset == TimeSpan.Zero`) |
| `Instant?` | `DateTimeOffset?` |

Positions in v1 (all ordinary ADR-062 planned positions):

- data-class / secondary constructor parameter
- method / top-level / extension / companion / object parameter
- method / top-level / extension / companion / object return
- property getter and setter (member, top-level, extension, companion)

**Out of v1:** Instant as a **collection element/key**, Flow/StateFlow element, suspend-only special
routing beyond ordinary result planning, reverse-direction Instant, Duration, pre-0.7
`kotlinx.datetime.Instant` class.

### 2. Classification: `BridgeType.Instant`

Add `data object Instant : BridgeType` next to `String` / `Char`.

In `ForwardBridgeTypeClassifier.classifyNonNullable`, **before** the generic class / export-set
branch (same place as `kotlin.String`):

```kotlin
if (qualifiedName == "kotlin.time.Instant") return BridgeType.Instant
```

Nullable wrapping stays the existing `BridgeType.Nullable(BridgeType.Instant)` path.

Do **not** put Instant on the object-handle / reachability path. It is a stdlib scalar, never a
generated wrapper class and never `include("kotlin.time")`.

`ForwardCallablePlanValidator.validateType` must admit `BridgeType.Instant` like `String`.

### 3. Wire form and conversions

**Wire components** (fixed names in generated ABI where a single public parameter expands):

| Component | Kotlin | C ABI / `ForwardAbiWireType` | C# marshal |
|---|---|---|---|
| epoch seconds | `Long` | `INT64` | `long` |
| nanosecond-of-second | `Int` | `INT32` | `int` |

**Conversions** (new `ForwardConversion` values, names illustrative):

- `INTO_KOTLIN`: C# `DateTimeOffset` → `(epochSeconds, nanosecondsOfSecond)` then
  `Instant.fromEpochSeconds(secs, nanos)`
- `OUT_OF_KOTLIN`: `instant.epochSeconds` / `instant.nanosecondsOfSecond` → C# reconstruct

**C# reconstruction (inferred shape; implementer must match BCL APIs used):**

```csharp
// OUT_OF_KOTLIN: Instant components → DateTimeOffset (UTC)
// Truncate sub-100ns: nanos / 100 ticks
var dto = DateTimeOffset.UnixEpoch.AddTicks(
    checked(epochSeconds * TimeSpan.TicksPerSecond + nanosecondsOfSecond / 100));
// or FromUnixTimeSeconds + AddTicks for the sub-second part, same truncation

// INTO_KOTLIN: DateTimeOffset → components
var utc = value.ToUniversalTime();
long epochSeconds = utc.ToUnixTimeSeconds(); // whole seconds toward -∞? document vs Instant
// Prefer tick math for consistency with OUT:
long unixTicks = utc.UtcTicks - DateTimeOffset.UnixEpoch.UtcTicks;
long epochSeconds = Math.DivRem(unixTicks, TimeSpan.TicksPerSecond, out long remTicks);
int nanosecondsOfSecond = (int)(remTicks * 100); // 0..999_999_900 step 100
```

**Precision (verified):** sub-100-nanosecond Instant bits are dropped at the C# boundary. Wire and
Kotlin keep full nanos if both ends are Kotlin-mediated without materializing `DateTimeOffset`; the
public C# type cannot.

**Range (verified):** if `epochSeconds` is outside
`[DateTimeOffset.MinValue, DateTimeOffset.MaxValue]` Unix seconds, C# reconstruction **throws**
`ArgumentOutOfRangeException` (or a generated `KotlinException` wrapper if the project prefers one
house style). Do not clamp silently (GOALS #2 honesty; ADR-064 fail-visible policy). Kotlin → C#
returns of `DISTANT_PAST` / `DISTANT_FUTURE` will throw on the C# side; document that Instant values
used with this bridge should stay in year 0001–9999 unless the consumer catches.

**Kotlin side of range:** `Instant.fromEpochSeconds` already clamps/validates per stdlib; C# → Kotlin
values inside DTO range are always in Instant range.

### 4. ABI shapes by position

Mirrors existing nullable-primitive / multi-out patterns; Instant is multi-component at every
position.

#### 4a. Non-null Instant **parameter** (constructor, method, …)

Public C#: `DateTimeOffset publishedDate`

Native: **two IN scalars** (flatMapped), no has-value:

```text
…, long publishedDate_epochSeconds, int publishedDate_nanosecondsOfSecond, …
```

Kotlin export body:

```kotlin
val publishedDate = Instant.fromEpochSeconds(
  publishedDate_epochSeconds,
  publishedDate_nanosecondsOfSecond,
)
// use publishedDate
```

C# call site:

```csharp
ToComponents(publishedDate, out long secs, out int nanos);
Native_Foo(..., secs, nanos, out error);
```

Helper `NugetMarshal.ToInstantComponents(DateTimeOffset, out long, out int)` (name flexible) keeps
projection code small.

#### 4b. `Instant?` **parameter**

Public C#: `DateTimeOffset? publishedDate`

Native: **three IN scalars** (same family as `Boolean?` / `Int?` hasValue fan-out):

```text
bool publishedDateHasValue, long publishedDate_epochSeconds, int publishedDate_nanosecondsOfSecond
```

When `hasValue` is false, component slots are ignored (pass `0,0`); Kotlin rebuilds `null`.

#### 4c. Non-null Instant **return** (method / top-level / …)

Cannot return two scalars as one C return. Use **Unit/void + two OUT pointers** (ADR-056 multi
out-pointer spirit; ADR-061 single-call discipline: **one** Kotlin evaluation):

```kotlin
@CName("article_get_published")
fun export(handle: COpaquePointer?, secsOut: COpaquePointer?, nanosOut: COpaquePointer?, errorOut: COpaquePointer?) {
  // try/catch ADR-024 …
  val result = obj.published
  secsOut?.reinterpret<LongVar>()?.pointed?.value = result.epochSeconds
  nanosOut?.reinterpret<IntVar>()?.pointed?.value = result.nanosecondsOfSecond
}
```

C#:

```csharp
Native_GetPublished(_handle, out long secs, out int nanos, out IntPtr error);
return NugetMarshal.FromInstantComponents(secs, nanos);
```

`ForwardResultShape` gains an Instant branch with `wireType = VOID` (or a dedicated result mode) and
`extraParameters` for the two OUT slots, analogous to nullable-primitive `valueOut`.

#### 4d. `Instant?` **return** (method / extension / …)

Single evaluation: return `Boolean` has-value + two OUT component pointers (ADR-061 shape, widened
to two outs):

```csharp
bool has = Native_MaybePublished(_handle, out long secs, out int nanos, out IntPtr error);
return has ? FromInstantComponents(secs, nanos) : null;
```

#### 4e. Property getter / top-level property (ADR-002 family)

- Non-null `Instant`: **Direct** getter using the 4c out-pointer export (one native call).
- `Instant?`: **LEGACY_TWO_CALL** like other nullable value-ish getters:
  - `_has_value(): Boolean`
  - `_value()` writes components via the same two OUT pointers (or returns void + outs)
  - C# `DateTimeOffset?` assembles only when has-value is true

Do **not** invent a third null pattern. Reuse ADR-002 / ADR-061 evaluation modes.

#### 4f. Property setter

Same as parameter: two IN scalars (three if `Instant?`).

### 5. Implementation map (ADR-062 extension points)

One new type, threaded through the plan dual projection:

| Layer | Change |
|---|---|
| `BridgeType` | `data object Instant` |
| `ForwardBridgeTypeClassifier` | FQCN `kotlin.time.Instant` → `BridgeType.Instant` |
| `ForwardConversion` | component pack/unpack enums |
| `ForwardCallablePlanner.nativeInputParameters` | Instant → 2 ABI params; `Nullable(Instant)` → 3 |
| `ForwardCallablePlanner.shapeOrNull` / `nullableResultShape` | Instant result with OUT components |
| `ForwardPropertyPlanner` | getter/setter plans for Instant / Instant? |
| `ForwardKotlinPlanEmitter` / `ForwardPropertyKotlinEmitter` | `fromEpochSeconds` / property writes |
| `ForwardCirPlanProjection` / `ForwardCirPropertyProjection` | public `DateTimeOffset` / `DateTimeOffset?`, DllImport outs |
| `ForwardMarshallingModel` validator | admit Instant |
| `ForwardMarshallingMatrixTest` | add Instant cells |
| `NugetMarshal` (generated or shared) | `ToInstantComponents` / `FromInstantComponents` |
| FEATURES.md | new Primitives (or Time) row after ship |
| Tier 1 / integration fixture | `published: Instant` on a DTO (NYTimes shape) |

No new specialized legacy protocol. Instant stays on the ordinary plan path.

### 6. Design calls that are **not** required to ship Instant

| Item | Call |
|---|---|
| `kotlin.time.Duration` → `TimeSpan` | **Defer.** Same precision story (100 ns); value-class unwrap to a single `long` ticks (or seconds+nanos) is a follow-up ADR or a thin amendment. |
| `kotlinx.datetime.Instant` | **Rely on typealias expansion** for 0.7+; optional hardcode of the pre-0.7 class FQCN only if a consumer fixture needs it. |
| Collection / Flow elements of Instant | **Defer** (collection component allow-lists are already a known ROADMAP seam). |
| Reverse Instant (`DateTimeOffset` in a bound C# API → Kotlin) | **Defer** (Phase 9 reverse; not this feature). |

## Consequences

**Positive**

- Instant-bearing DTOs (NYTimes `published_date`) surface as idiomatic `DateTimeOffset` instead of
  vanishing or becoming `IDisposable` handles.
- Stdlib known-type path; no `include("kotlin.time")` footgun.
- Wire is Instant-native (secs + nanos); independent of C# range limits until the public type is
  materialized.

**Negative / accepted limits**

- First multi-component ordinary forward scalar: more planner/emitter cases than `String`.
- C# precision ceiling 100 ns; Instant nanosecond bits below that are lost at the public boundary
  (**verified**).
- C# range ceiling year 0001–9999; Instant `DISTANT_*` and far history/future **throw** on
  materialization (**verified**).
- Kotlin `Instant` is a class (nullable by reference); C# maps to a struct (`DateTimeOffset?`). This
  is intentional and matches "idiomatic C#", not a 1:1 runtime representation.

**Inferred (not spiked) claims implementers must not treat as runtime-proof**

1. Exact C# helper using `ToUnixTimeSeconds` vs pure tick math for negative sub-second instants:
   prefer tick math and cover with an integration test (leap-free UTC-SLS vs BCL is not re-derived
   here).
2. That every CIR renderer site that switches on `BridgeType` exhaustiveness will fail the build
   until Instant branches are added (expected; the sealed hierarchy forces it).
3. KSP always resolves `kotlinx.datetime.Instant` typealias uses to a `KSTypeAlias` that
   `expandAliases()` expands (likely true for ordinary typealiases; **not** re-spiked this session
   for the datetime artifact). If a consumer still sees `Unsupported(kotlinx.datetime.Instant)`,
   add an explicit FQCN alias in the classifier.

**Verified claims (summary)**

1. Instant CName as object = `kref` handle; component Long+Int and out-pointer writes work
   (konanc 2.4.10 dylib + C driver).
2. DateTimeOffset loses `nanos % 100`; range throws outside year 0001–9999; epoch-nanos long
   overflows Instant range.
3. Repo today classifies Instant as unexported dependency and drops the callable.
4. Planner already flatMaps multi-ABI inputs for nullable primitives.

## References

- ROADMAP.md line 103 (Instant first-class built-in)
- [kotlin.time.Instant](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-instant/) (stdlib 2.3+)
- ADR-002 nullable two-call; ADR-014 value class unwrap; ADR-018 typealiases; ADR-056 multi-component
  reverse structs; ADR-061 method return `valueOut`; ADR-062 forward plan; ADR-064 diagnostics;
  ADR-066 unexported dependency; ADR-069 nullable Boolean width; ADR-074 platform typealias skips
- kotlinx-datetime 0.7+ `typealias Instant = kotlin.time.Instant`

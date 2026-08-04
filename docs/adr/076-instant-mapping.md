# ADR-076: `kotlin.time.Instant` maps to `System.DateTimeOffset` over a single `Int64` of .NET ticks

## Status
Accepted

## Context

ROADMAP Phase 4 asks for `kotlin.time.Instant` to bind as a **first-class known stdlib type, in the
same category as `String`**, not through a user-supplied custom type mapper (that is the separate
Future Improvements escape hatch, line 344). The consumer hit is NYTimes-KMP's
`Article.published_date: Instant` (`sample/app/src/commonMain/kotlin/io/github/xxfast/nytimes/models/ApiModels.kt:31`
imports `kotlin.time.Instant`), whose generated DTO drops the field today.

Positions required: property, constructor parameter, method parameter, method return, and `Instant?`.

### What `kotlin.time.Instant` actually is

**Verified** by extracting `kotlin-stdlib-2.4.10-common-sources.jar`
(`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.10/9888df88.../`),
`commonMain/kotlin/time/Instant.kt`:

```
105:@SinceKotlin("2.3")
106:@WasExperimental(ExperimentalTime::class)
107:public class Instant internal constructor(
120:    public val epochSeconds: Long,
130:    public val nanosecondsOfSecond: Int
267:    public override fun toString(): String = formatIso(this)
319:        public fun fromEpochSeconds(epochSeconds: Long, nanosecondAdjustment: Long = 0): Instant
355:        public fun fromEpochSeconds(epochSeconds: Long, nanosecondAdjustment: Int): Instant
386:        public fun parse(input: CharSequence): Instant
474:private const val MIN_SECOND = -31557014167219200L // -1000000000-01-01T00:00:00Z
479:private const val MAX_SECOND = 31556889864403199L  // +1000000000-12-31T23:59:59
```

So: an ordinary `public class` (not a `value class`, not `expect`/`actual`, not `sealed`, no type
parameters), stable since Kotlin 2.3 (this repo is on 2.4.10, `gradle/libs.versions.toml:2`), with
nanosecond resolution over roughly ±1e9 years.

### What happens today

**Inferred from source reading, not from a run.** `ForwardBridgeTypeClassifier.classifyNonNullable`
falls through every branch for `kotlin.time.Instant` (no known scalar, not `Char`/`String`, no
specialized protocol, not a collection, not an enum, not a value class, not sealed, not an
interface, no type parameters, `classKind == CLASS`) and reaches the
`qualifiedName !in context.exportedObjectHandles` check at
`ForwardBridgeTypeClassifier.kt:124`. `kotlin.time` never passes the ADR-063 package filter, so the
type is not in the export set. Since it is read off the stdlib klib, `containingFile` is expected to
be `null` (ADR-066 **verified** this for klib dependency declarations; kotlin-stdlib is such a klib,
but I did not run KSP against an `Instant` fixture), giving
`BridgeType.Unsupported(isUnexportedDependency = true)` and therefore
`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, whose hint tells the user to bring the package into scope with
`include(...)`. **That hint is unactionable for a stdlib type**: the same defect class ADR-074
fixed for `actual typealias` targets. This ADR removes the case entirely rather than adding a third
diagnostic. `ForwardReachabilityClosure.SCALAR_TERMINALS` (`ForwardReachabilityClosure.kt:200-203`)
is the mirror seam: `kotlin.time.Instant` is currently walked as a `CLASS` edge.

### Constraint check: can the ADR-062 plan carry more than one scalar per crossing?

**Verified by source reading** (this was the question the design was gated on, and the answer is
"yes, at every position"):

- **Input**: `ForwardCallablePlanner.nativeInputParameters` returns `List<ForwardAbiParameter>`, its
  own KDoc says a nullable primitive "fan[s] out to two *adjacent* native parameters", and callers
  `flatMap` (`ForwardCallablePlanner.kt:817`, `:669`). Multi-scalar inputs are already a shipped
  shape.
- **Result**: `ForwardResultShape.extraParameters` is a `List<ForwardAbiParameter>` of OUT pointers
  (`ForwardCallablePlanner.kt:1096-1110`, the `valueOut` shape), and `ForwardEvaluation
  .LEGACY_TWO_CALL` exists for the top-level case.

So question A does **not** eliminate a seconds+nanos wire form on plan-model grounds. The choice
below is made on the merits, not forced. The one place that is genuinely narrower is
`ForwardPropertyPlanner`, whose `NullableDispatch` getter/setter shapes are written against
`Nullable(Primitive)` only (`ForwardPropertyPlanner.kt:173`, `:217`); a two-component property would
need new machinery there.

### The destination type's limits (verified by spike)

```
$ cd $(mktemp -d) && dotnet new console -o . && dotnet run     # TFM net10.0, SDK 10.0.300
DateTime.MinValue.Ticks = 0
DateTime.MaxValue.Ticks = 3155378975999999999
UnixEpoch.Ticks = 621355968000000000
local.Ticks = 638454980961230000 ; local.UtcTicks = 638454782961230000
roundtrip = 2024-03-08T07:04:56.1230000+00:00
equals original? True ; == ? True
Offset of rt = 00:00:00 ; kind of rt.DateTime = Unspecified ; UtcDateTime.Kind = Utc
neg ticks -> ArgumentOutOfRangeException
parse 9 digits: 638454980961234568
year +100000 -> FormatException
```

(`local` was `new DateTimeOffset(2024,3,8,12,34,56,123, new TimeSpan(5,30,0))`; `rt` was
`new DateTimeOffset(local.UtcTicks, TimeSpan.Zero)`.)

Reading the output:

- `DateTimeOffset` resolution is 100ns; range is `[0, 3155378975999999999]` ticks (years 0001-9999).
  Both endpoints fit `Int64` with three orders of magnitude to spare.
- `UtcTicks` normalizes any offset to UTC; a negative tick value is rejected with
  `ArgumentOutOfRangeException`, so **the tick wire domain is non-negative**, which makes `/` and
  `%` unambiguous on the Kotlin side.
- `DateTimeOffset.Equals`/`==` compare the **instant**, not the offset, so a consumer's non-UTC
  input compares equal to the UTC-normalized value that comes back. xunit `Assert.Equal` therefore
  passes across a round trip without the test having to normalize.
- `DateTimeOffset.Parse` of a 9-fractional-digit ISO string **rounds** (`...56.123456789` →
  `...4568`, not `...4567`), and a Kotlin `Instant.toString()` outside year 9999 (e.g.
  `Instant.DISTANT_FUTURE`, `+100000-01-01T00:00:00Z`) is a `FormatException`. Both are arguments
  against the ISO wire form below. (Observed on net10.0; the rounding-vs-truncation behaviour is
  the one line here I would not assume is identical on net8.0.)

## Alternatives Considered

### 1. Single `Int64` of .NET ticks (100ns since 0001-01-01T00:00:00 UTC) → `System.DateTimeOffset` (chosen)

One `INT64` scalar. Kotlin converts `epochSeconds`/`nanosecondsOfSecond` to ticks on the way out and
back on the way in; C# uses `value.UtcTicks` and `new DateTimeOffset(ticks, TimeSpan.Zero)`.

Pros:
- The wire form **is the destination type's own domain**, exactly and only. Nothing is carried that
  `DateTimeOffset` would discard one line later.
- One scalar, so the whole nullable story reduces to the already-shipped nullable-primitive `INT64`
  machinery at all four positions (input `hasValue` fan-out, `valueOut` out-pointer, top-level
  two-call, property `NullableDispatch`). No plan-model widening, no `ForwardPropertyPlanner`
  widening.
- Representability becomes a **single explicit range check on the Kotlin side**, whose failure rides
  the existing `errorOut` slot and surfaces as an ordinary bridged C# exception rather than a
  `FormatException` from deep inside a parse, or a silent wrap.
- No allocation, no parse, no UTF-8 marshalling per crossing.

Cons:
- Kotlin → C# truncates below 100ns. Unavoidable for any wire form, because `DateTimeOffset` cannot
  hold it; this option makes the loss happen once, at a named place.
- Kotlin → C# throws for an `Instant` outside years 0001-9999 (which includes `Instant.DISTANT_PAST`
  and `Instant.DISTANT_FUTURE`).
- The epoch-offset constant (62135596800 s / 621355968000000000 ticks) is a magic number that must
  be right. It is **verified** above, not derived.

### 2. `epochSeconds: Long` + `nanosecondsOfSecond: Int`, two wire components

Exact preservation of the Kotlin value. The plan model can express it (see constraint check).

Rejected: the exactness is unobservable. Both components are collapsed into a `DateTimeOffset` the
instant they arrive, and 900 of every 1000 nanoseconds are then discarded. In exchange it costs a
new multi-component result shape (two OUT pointers, or a widened `ForwardResultConvention`), a
two-component `NullableDispatch` in `ForwardPropertyPlanner`, and a new "which component carries the
null flag" question. Paying ADR-069-scale machinery for precision the destination cannot represent
is the wrong trade. It becomes the right trade only if the C# type is later changed to a
Kotlin-shaped struct, which the roadmap explicitly does not ask for.

### 3. ISO-8601 `String`

Reuses the entire existing `String` wire path (including the nullable `String` path, which already
works at all four positions), with zero new wire mechanism. `Instant.toString()` emits ISO and
`Instant.parse` reads it.

Rejected on failure modes, not on cost:
- **Verified** by the spike: .NET *rounds* >7 fractional digits while Kotlin's own arithmetic would
  truncate; the two directions disagree at the sub-100ns edge and round-trip assertions get brittle.
- **Verified**: an out-of-.NET-range Kotlin instant produces a `FormatException` inside generated
  marshalling code, with no useful message about which value or which member. The ticks form turns
  the same condition into a Kotlin-side `require` with the offending value in the message, delivered
  through `errorOut`.
- An allocation, a UTF-8 encode, a parse and a free per crossing, for a value that is 8 bytes.
- It is also the only option where the *bridge* would carry a text format as an ABI contract.

### 4. Single `Int64` of epoch nanoseconds

One scalar, no epoch-offset constant. Rejected on range: `Int64` nanoseconds covers only
1677-2262 (±292 years from 1970). That is narrower than `DateTimeOffset`'s own range, so the bridge
would reject values the destination type can hold perfectly well. Ticks are strictly better at
identical cost.

### 5. Opaque `StableRef` handle (`KotlinInstant : IDisposable`)

The shape `Instant` would get if it were simply added to the export set. Rejected by the roadmap
item itself ("would only ever surface as a non-idiomatic StableRef handle; neither is acceptable"),
and it is what Kotlin's ObjC export does for the same type today, the outcome we are avoiding, not
a precedent to follow.

## Decision

### 1. New `BridgeType.Instant`, modelled on `BridgeType.Enum`

`BridgeType.Enum` is the exact existing precedent for "a semantic type of its own that crosses as a
primitive, with a required conversion on both sides plus a helper requirement". `Instant` gets the
same shape:

```kotlin
// ForwardMarshallingModel.kt
internal sealed interface BridgeType {
  /** ADR-076: kotlin.time.Instant. Wires as INT64 .NET ticks; public C# type is DateTimeOffset. */
  data object Instant : BridgeType
}

internal enum class ForwardConversion { /* ... */ INSTANT_TO_TICKS, TICKS_TO_INSTANT }
internal enum class ForwardHelperRequirement { /* ... */ INSTANT }
```

**A sealed variant, not a re-use of `BridgeType.Primitive(LONG)` with a flag.** Same reasoning as
ADR-070: a new variant makes the compiler enumerate every `when` that must change, where re-use is
a silent fall-through that produces a public `long` where the consumer expected a date.

Classification (`ForwardBridgeTypeClassifier.classifyNonNullable`) recognizes
`kotlin.time.Instant` alongside `kotlin.Char`/`kotlin.String`, **before** the
`exportedObjectHandles` membership check. `"kotlin.time.Instant"` is added to
`ForwardReachabilityClosure.SCALAR_TERMINALS` so the closure stops walking it as a class edge.
This is what "known stdlib type, same category as `String`" means concretely: two hardcoded
qualified-name checks, no user configuration, no mapper registry.

### 2. Wire form: one `INT64`, .NET ticks, non-negative

`ForwardAbiWireType.INT64`. The value is **100-nanosecond ticks since 0001-01-01T00:00:00 UTC**,
i.e. `DateTime.Ticks` in the UTC frame, always in `[0, 3155378975999999999]` (**verified** by
spike).

Kotlin, out of Kotlin (generated helper, inside the export's existing try/catch so a failure lands
in `errorOut`):

```kotlin
private const val TICKS_UNIX_EPOCH: Long = 621_355_968_000_000_000L  // verified: DateTime.UnixEpoch.Ticks
private const val EPOCH_SECONDS_MIN: Long = -62_135_596_800L         // 0001-01-01T00:00:00Z
private const val EPOCH_SECONDS_MAX: Long = 253_402_300_799L         // 9999-12-31T23:59:59Z

internal fun Instant.toDotNetTicks(): Long {
  require(epochSeconds in EPOCH_SECONDS_MIN..EPOCH_SECONDS_MAX) {
    "Instant $this is outside System.DateTimeOffset's range (0001-01-01T00:00:00Z..9999-12-31T23:59:59.9999999Z)"
  }
  return TICKS_UNIX_EPOCH + epochSeconds * 10_000_000L + nanosecondsOfSecond / 100
}
```

The range check must run **before** the multiply. With it, `epochSeconds * 10_000_000` peaks at
about 2.53e18, comfortably inside `Long`; without it, `Instant.MAX`'s 3.16e16 seconds overflows
silently. (**Inferred**: the arithmetic; I did not run it on konanc this session.)

Kotlin, into Kotlin:

```kotlin
internal fun instantFromDotNetTicks(ticks: Long): Instant {
  require(ticks in 0L..3_155_378_975_999_999_999L) {
    "Tick value $ticks is not a valid System.DateTimeOffset"
  }
  val sinceEpoch: Long = ticks - TICKS_UNIX_EPOCH
  return Instant.fromEpochSeconds(
    epochSeconds = sinceEpoch / 10_000_000L,
    nanosecondAdjustment = ((sinceEpoch % 10_000_000L) * 100L).toInt(),
  )
}
```

The subtlety here, which the plain arithmetic hides: `sinceEpoch` is **negative** for every date
before 1970, so `/` and `%` truncate toward zero and hand `fromEpochSeconds` a *negative*
nanosecond adjustment. That is fine, and this is **verified**, not assumed, by running the exact
arithmetic against the real `kotlin-stdlib-2.4.10.jar` (the arithmetic and normalization live in
`commonMain`, so Kotlin/Native shares them):

```
$ javac -cp kotlin-stdlib-2.4.10.jar T.java && java -cp kotlin-stdlib-2.4.10.jar:. T
0                   -> 0001-01-01T00:00:00Z         (s=-62135596800, ns=0)         -> back 0                    OK
621355968000000000  -> 1970-01-01T00:00:00Z         (s=0, ns=0)                    -> back 621355968000000000   OK
3155378975999999999 -> 9999-12-31T23:59:59.999999900Z (s=253402300799, ns=999999900) -> back 3155378975999999999 OK
621355967999999999  -> 1969-12-31T23:59:59.999999900Z (s=-1, ns=999999900)         -> back 621355967999999999   OK
638454782961230000  -> 2024-03-08T07:04:56.123Z      (s=1709881496, ns=123000000)   -> back 638454782961230000   OK
599266080000000000  -> 1900-01-01T00:00:00Z          (s=-2208988800, ns=0)          -> back 599266080000000000   OK
```

That run also **verifies** the three constants above: ticks 0 is exactly `0001-01-01T00:00:00Z`
(`epochSeconds == -62_135_596_800`), ticks 3155378975999999999 is exactly
`9999-12-31T23:59:59.999999900Z` (`epochSeconds == 253_402_300_799`), and `TICKS_UNIX_EPOCH` lands
on `1970-01-01T00:00:00Z`. `(sinceEpoch % 10_000_000) * 100` fits `Int`
(max 999,999,900 < 2,147,483,647), so the `Int` overload is safe.

C#, at the four positions (mirroring exactly how `BridgeType.Enum` is already projected in
`ForwardCirPlanProjection.kt:118-124`, `:458`, `:844`):

| seam | enum today | Instant |
| --- | --- | --- |
| `csharpType()` | `csharpType` | `"global::System.DateTimeOffset"` |
| call argument | `(int)param` | `param.UtcTicks` |
| return lift | `(Mood)nativeResult` | `new global::System.DateTimeOffset(nativeResult, global::System.TimeSpan.Zero)` |
| `isCSharpReferenceType()` | `false` | `false` |

`UtcTicks` on the way in is load-bearing (**verified**): a consumer holding a `+05:30`
`DateTimeOffset` must not send its wall-clock ticks.

### 3. Public C# type: `System.DateTimeOffset`, `Offset == TimeSpan.Zero`

`DateTimeOffset`, not `DateTime`. Microsoft's own guidance is that `DateTimeOffset` "should be
considered the default date and time type for application development" and is the type for
unambiguously identifying a single point in time
([Choosing between DateTime, DateOnly, DateTimeOffset, TimeSpan, TimeOnly, and TimeZoneInfo](https://learn.microsoft.com/en-us/dotnet/standard/datetime/choosing-between-datetime)).
`Instant` is precisely "a single point in time carrying no zone", so `DateTimeOffset` is the type
with the matching semantics; `DateTime` carries a `Kind` that is advisory, is ignored by `DateTime`
equality, and is routinely lost across serialization, which would let a UTC instant silently compare
equal to a same-numbered local time.

`Offset` is **always** `TimeSpan.Zero`, because `Instant` carries no zone and inventing one would be
a lie. **Verified** consequence from the spike: `rt.DateTime.Kind == Unspecified` while
`rt.UtcDateTime.Kind == Utc`, so a consumer who wants a `DateTime` should use `.UtcDateTime`; this
belongs in the generated XML doc comment.

### 4. `Instant?` → `DateTimeOffset?`

`Instant` renders as a C# value type, so `Instant?` is `Nullable<DateTimeOffset>` and rides the
already-shipped nullable-primitive `INT64` machinery unchanged in shape. Four sites currently test
`is BridgeType.Primitive` and must also admit `BridgeType.Instant` (**verified** locations):

1. `ForwardCallablePlanner.nativeInputParameters`, nullable branch (`:980`): emits
   `${name}HasValue` (`BOOLEAN`) + `${name}` (`INT64`).
2. `ForwardCallablePlanner.nullableResultShape` (`:1091`): `BOOLEAN` result + `valueOut`
   OUT pointer carrying the ticks.
3. `ForwardCallablePlanner.topLevelNullablePrimitivePlan` (`:648`): the ADR-002 two-call
   `_has_value` / `_value` shape, reached only from a top-level `fun`. ADR-069 recorded that this
   path **crashes `packNuget`** for a shape it does not handle rather than skipping, so the fixture
   must contain a top-level `fun ...(): Instant?`.
4. `ForwardPropertyPlanner` `NullableDispatch` getter/setter (`:173`, `:217`).

There is no `Boolean?`-style width hazard here: the payload is `INT64` on both sides, and the
`hasValue` flag is the same `BOOLEAN` ADR-069 already pinned to `[MarshalAs(UnmanagedType.I1)]`.

### 5. Precision and range contract (state this in the generated docs)

| direction | precision | range |
| --- | --- | --- |
| C# → Kotlin | exact (every `DateTimeOffset` tick is representable in `Instant`) | total; the wire domain is `DateTimeOffset`'s own |
| Kotlin → C# | truncated toward the epoch-0001 origin to 100ns (the ticks are non-negative, so truncation is floor) | years 0001-9999 only; anything else throws `IllegalArgumentException` from the Kotlin export, delivered as a C# exception through the existing `errorOut` slot |
| C# → Kotlin → C# | exact | exact; a non-UTC input returns UTC-normalized and still compares `==` (**verified**) |

`Instant.DISTANT_PAST` and `Instant.DISTANT_FUTURE` are **outside** the supported range and throw.
That is deliberate: they are sentinels, not timestamps, and silently clamping them would make
"is this distant?" checks wrong on the C# side.

### 6. The C# the consumer sees

For the fixture in Consequences, the generated public surface is:

```csharp
public sealed class SightingLog : IDisposable
{
    public SightingLog(DateTimeOffset firstSeen, DateTimeOffset? lastSeen) { ... }

    public DateTimeOffset FirstSeen { get; }
    public DateTimeOffset? LastSeen { get; set; }

    public long ElapsedSeconds(DateTimeOffset until) { ... }
    public DateTimeOffset NextExpected() { ... }
    public DateTimeOffset? EarliestUnconfirmed() { ... }
}

public static class SightingLogKt   // ADR-007 file class
{
    // Verified against the real generator (not inferred, unlike the rest of this sample): a plain
    // top-level `fun` keeps its Kotlin camelCase spelling in C# (STYLE.md; every other top-level
    // function in this repo's fixtures does the same, e.g. `MappedExceptions.checkOreoWeight`).
    // Only methods/extensions/object/companion members are PascalCased.
    public static DateTimeOffset sightingEpoch() { ... }
    public static DateTimeOffset? parseSighting(string text) { ... }
}
```

And the test the consumer would write:

```csharp
var at = new DateTimeOffset(2024, 3, 8, 12, 34, 56, 123, new TimeSpan(5, 30, 0));
using var log = new SightingLog(at, null);

Assert.Equal(at, log.FirstSeen);                        // passes: DateTimeOffset equality is instant-based
Assert.Equal(TimeSpan.Zero, log.FirstSeen.Offset);      // always UTC-normalized coming back
Assert.Null(log.LastSeen);

log.LastSeen = DateTimeOffset.UnixEpoch;
Assert.Equal(621355968000000000L, log.LastSeen!.Value.UtcTicks);

// Every DateTimeOffset a consumer can construct is in range, so the C# -> Kotlin direction
// never throws. The throwing direction is Kotlin -> C#: a member returning Instant.DISTANT_FUTURE
// surfaces the Kotlin `require` failure through the existing errorOut slot as a C# exception.
```

The seam projections that produce this (mirroring `BridgeType.Enum`'s existing sites in
`ForwardCirPlanProjection.kt:118-124`, `:458`, `:844`) are in §2.

### 7. Prior art

- **Kotlin's own JVM interop** maps `kotlin.time.Instant` to the platform's idiomatic instant type
  by explicit conversion, not by opaque handle: `Instant.toJavaInstant()` /
  `java.time.Instant.toKotlinInstant()`, **verified** in
  `jvmMain/jdk8/kotlin/time/InstantConversions.kt` of the 2.4.10 sources jar (it goes through
  `epochSeconds` + `nanosecondsOfSecond`, which is lossless there because `java.time.Instant` is
  also nanosecond-resolution). The principle transfers; the losslessness does not, because
  `DateTimeOffset` is not.
- Its own serialized form is `epochSeconds: Long` + `nanosecondsOfSecond: Int`
  (**verified**, `jvmMain/kotlin/time/InstantJvm.kt`, `InstantSerialized`), which is what makes
  Alternative 2 the tempting one; the difference is that Java serialization's destination *can*
  hold it.
- **ObjC/Swift export**: `kotlin.time.Instant` is a plain Kotlin class outside the fixed
  stdlib-mapping list, so it surfaces as an opaque `KotlinInstant` wrapper class rather than
  `NSDate`; `kotlinx-datetime` supplies explicit, documented-as-lossy `toNSDate()`/`toKotlinInstant()`
  converters for Darwin instead of an automatic mapping
  ([kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime)). **Inferred** from documentation,
  not verified against a generated ObjC header. This is Alternative 5, which the roadmap rejects.

## Consequences

### Fixture surface in `test-library`

New file `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/SightingLog.kt`,
crossing each seam the feature actually goes through and no more:

```kotlin
class SightingLog(val firstSeen: Instant, var lastSeen: Instant?) {  // ctor param x2, val property, var nullable property
  fun elapsedSeconds(until: Instant): Long                            // method parameter
  fun nextExpected(): Instant                                         // method return
  fun earliestUnconfirmed(): Instant?                                 // nullable method return (valueOut)
}

fun sightingEpoch(): Instant                                          // top-level return
fun parseSighting(text: String): Instant?                             // top-level nullable return (ADR-002 two-call)
```

Boundary values the C# tests must cover, all **verified** as the real edges:
the Unix epoch (ticks 621355968000000000), a pre-1970 instant (proves the negative-remainder path in
§2), a NYTimes-shaped date, `DateTime.MinValue`/`MaxValue` at ticks 0 and 3155378975999999999,
a sub-100ns value (asserts truncation, not rounding), and `Instant.DISTANT_FUTURE` (asserts the
thrown exception, not a wrapped value). No `Duration` cell, no extension-on-`Instant` cell.

### What changes

- `BridgeType.Instant`, two `ForwardConversion` values, one `ForwardHelperRequirement`, and their
  `ForwardCallablePlanValidator.requiredConversion`/`helper()` rows.
- Classifier + reachability-closure terminal.
- The four nullable sites in §4, plus the enum-shaped projection seams in §2.
- Two generated Kotlin helper functions and two C# expressions.
- The `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` diagnostic stops firing for `Instant`, taking its
  unactionable `include(kotlin.time)` hint with it.

### What breaks

Nothing that binds today: `Instant` is currently skipped everywhere. Members that were dropped will
now appear in the public C# surface, which is a surface addition, not a change.

### Deferred, with the one-line reason each

- **`kotlin.time.Duration` → `System.TimeSpan`**: a different type with a different wire question
  (`TimeSpan` is also 100ns ticks, so the mechanism here extends cheaply as a second known-scalar
  branch, but it is not required to ship `Instant` and is not in this ADR's fixture).
- **Legacy `kotlinx.datetime.Instant`**: a distinct qualified name on a distinct dependency; a
  one-line classifier alias if a consumer ever needs it, but no current consumer does
  (NYTimes-KMP is on `kotlin.time.Instant`).
- **`kotlinx.datetime.LocalDate`/`LocalDateTime`/`TimeZone`**: different C# targets (`DateOnly`,
  `DateTime`, `TimeZoneInfo`) and different semantics; separate roadmap items.
- **Sub-100ns precision**: unrepresentable in `DateTimeOffset`; would require a bespoke C# struct,
  which the roadmap item does not ask for.
- **Years outside 0001-9999**: unrepresentable in `DateTimeOffset`; throwing is the contract.
- **`Instant` as a collection element / `Flow<Instant>` type argument**: the ADR-073/075
  `isWrappableComponent()` allow-list is a separate seam with its own boxing question.
- **`Instant` as an extension receiver**: no consumer shape asks for it; the `ForwardReceiver.Value`
  path would carry it for free but nothing proves that today.
- **A general custom type-mapper mechanism**: explicitly a different roadmap item
  (ROADMAP:344).

### Open questions / unverified load-bearing claims

1. **The two conversion helpers have not been compiled by konanc.** The arithmetic is verified
   against the real 2.4.10 stdlib on the JVM (§2), and it is `commonMain` code, but nobody has built
   a Kotlin/Native `@CName` export that calls it. The walking-skeleton integration test closes this.
2. **`containingFile == null` for a stdlib klib declaration.** Inferred (ADR-066 verified it for a
   dependency klib). It only affects which of two skip diagnostics fires *today*, and this ADR
   removes both for `Instant`, so nothing downstream rests on it.
3. **`DateTimeOffset.Parse` rounding on net8.0.** Observed on net10.0 only. Load-bearing only for
   Alternative 3, which is rejected.
4. **The spike ran on the net10.0 SDK, not net8.0.** Every `DateTimeOffset` value read from it
   (`MinValue.Ticks`, `MaxValue.Ticks`, `UnixEpoch.Ticks`, `UtcTicks` normalization, instant-based
   equality, negative-tick rejection) is a documented BCL invariant unchanged since .NET Framework,
   so the risk is low, but it is inference, not observation, that they are identical on net8.0.

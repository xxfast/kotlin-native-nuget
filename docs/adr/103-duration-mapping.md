# ADR-103: `kotlin.time.Duration` maps to `System.TimeSpan` over a single `Int64` of ticks

## Status
Accepted

## Context

The backlog item `docs/backlog/kotlin-time-duration-system-timespan.md` asks for
`kotlin.time.Duration` as the second first-class known stdlib type, extending the mechanism
[ADR-076](076-instant-mapping.md) built for `kotlin.time.Instant`: a `BridgeType` variant that
wires as `INT64` with a required conversion on both sides. ADR-076 explicitly deferred Duration as
"a second known-scalar branch". This ADR is that branch; it deliberately invents nothing that
ADR-076 did not already ship.

Positions required: property, constructor parameter, method parameter, method return, and
`Duration?`.

### What `kotlin.time.Duration` actually is

**Verified** by extracting `kotlin-stdlib-2.4.10-common-sources.jar`
(`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.10/9888df88.../`),
`commonMain/kotlin/time/Duration.kt`:

```
34:public value class Duration
37:internal constructor(private val rawValue: Long) :
40:    private val value: Long get() = rawValue shr 1
41:    private inline val unitDiscriminator: Int get() = rawValue.toInt() and 1
64:        public val INFINITE: Duration = durationOfMillis(MAX_MILLIS)
65:        internal val NEG_INFINITE: Duration = durationOfMillis(-MAX_MILLIS)
1589:internal const val MAX_NANOS = Long.MAX_VALUE / 2 / NANOS_IN_MILLIS * NANOS_IN_MILLIS - 1 // ends in ..._999_999
1592:internal const val MAX_MILLIS = Long.MAX_VALUE / 2
```

So, unlike `Instant`, Duration is a **`value class`** over one `Long`, whose low bit is a unit
discriminator selecting between two bands:

- **nanosecond band**: exact 1ns resolution over `±MAX_NANOS` = ±4,611,686,018,426,999,999 ns
  (about ±146 years);
- **millisecond band**: 1ms resolution over `±MAX_MILLIS` = ±4,611,686,018,427,387,903 ms
  (about ±146 **million** years), whose extreme values `±MAX_MILLIS` are reserved as the
  `INFINITE` / `NEG_INFINITE` sentinels.

`rawValue` is `private`, the constructor is `internal`, and the encoding is explicitly an
implementation detail. The public conversion surface is `toComponents { seconds, nanoseconds -> }`
(public inline; for negative durations both components are negative, truncated toward zero),
`inWhole*` accessors (which **saturate** at `Long.MIN_VALUE`/`Long.MAX_VALUE`, so they cannot
carry the ms band faithfully), and the `Long.nanoseconds`/`Long.milliseconds` companion
constructors, which pick the band by magnitude and silently truncate to 1ms beyond `±MAX_NANOS`
(all **verified** in the same source file, `toDuration` at lines 985-994).

One consequence worth stating loudly, because the backlog note implies the opposite:
**Duration's finite range exceeds `TimeSpan`'s, not the other way around.** `TimeSpan` spans about
±29,228 years; Duration's ms band spans about ±146 million years. Kotlin → C# therefore needs a
range check on *finite* values too, not just on the infinities.

### What happens today

**Inferred from source reading, not from a run.** `ForwardBridgeTypeClassifier.classifyNonNullable`
reaches `if (classDeclaration.isValueClass()) return valueClass(...)`
(`ForwardBridgeTypeClassifier.kt:117`) before the `exportedObjectHandles` check, and
`valueClass()` (`:265`) happily builds `BridgeType.ValueClass(qualifiedName,
Primitive(LONG), "rawValue", ...)` from the single primary-constructor parameter. That binding is
wrong twice over: the generated Kotlin would read `private val rawValue` and call an `internal`
constructor (expected to fail konanc compilation of the generated exports), and even if it
compiled, it would expose the discriminator-bit encoding as ABI. Nobody has run a fixture through
this path; the exact failure mode (compile error vs some later skip) is unverified, but every
outcome is broken, and the fix below is the same for all of them.

### The destination type's limits (verified by spike)

```
$ dotnet new console && dotnet build && dotnet exec ...   # SDK 10.0.300
TimeSpan.MinValue.Ticks = -9223372036854775808 (long.Min=-9223372036854775808)
TimeSpan.MaxValue.Ticks = 9223372036854775807 (long.Max=9223372036854775807)
TicksPerSecond = 10000000; TicksPerMillisecond = 10000
new TimeSpan(long.Min) = -10675199.02:48:05.4775808; new TimeSpan(long.Max) = 10675199.02:48:05.4775807  (no throw)
neg 2.5s: -00:00:02.5000000; Ticks=-25000000; TotalSeconds=-2.5
equality: True                       // new TimeSpan(25000000) == TimeSpan.FromSeconds(2.5)
Timeout.InfiniteTimeSpan.Ticks = -10000
MaxValue ~years = 29228
default == Zero: True
```

Reading the output:

- `TimeSpan`'s tick domain is the **full `Int64` range**, resolution 100ns, negatives included.
  `new TimeSpan(long ticks)` never throws, for any value. So the C# → Kotlin direction is total:
  every `TimeSpan` a consumer can hold is convertible.
- `Timeout.InfiniteTimeSpan` is the magic value **-1 millisecond** (-10000 ticks), not a real
  infinity, and collides with an honest -1ms duration. It is disqualified as a target for
  `Duration.INFINITE` (see Decision 5).
- Same caveat as ADR-076: observed on the net10.0 SDK; that these are identical on net8.0 is
  **inferred** (they are documented BCL invariants unchanged since .NET Framework).

### The conversion arithmetic (verified by spike, konanc 2.4.10)

Unlike ADR-076's JVM run, this spike ran on the actual target compiler:
`~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/konanc Spike.kt -o spike && ./spike.kexe`,
with the exact helper bodies from Decision 2. Key lines of real output:

```
INFINITE.toComponents = s=9223372036854775807 ns=0        // saturates; unusable as a range signal
INFINITE.inWholeNanoseconds = 9223372036854775807         // saturates too
1234.nanoseconds -> ticks=12 back=1.2us                   // sub-100ns truncates toward zero
(-1234).nanoseconds -> ticks=-12 back=-1.2us              // ... in both signs
ns-band max 4611686018426999999.nanoseconds -> ticks=46116860184269999
200y -> ticks=63072000000000000 back=73000d roundtripEq=true
30000 years -> THROWS: Duration 10950000d out of TimeSpan range
INFINITE -> THROWS ; NEG INFINITE -> THROWS
fromTicks(9223372036854775807) = 10675199d 2h 48m 5.477s  // TimeSpan.MaxValue lands in ms band
   backToTicks=9223372036854770000                        // ... round trip loses 5807 ticks (<1ms)
fromTicks(46116860184269999) exact=true                   // ns-band edge round trips exactly
fromTicks(-9223372036854775808) = -(10675199d 2h 48m 5.477s)  // TimeSpan.MinValue accepted
inMax  (10675199d 2h 48m 5.477s) -> 9223372036854770000   // boundary second, 477ms: fits
overMax (10675199d 2h 48m 5.478s) -> THROWS(over max)     // 478ms: the frac guard fires
underMin (-(10675199d 2h 48m 5.478s)) -> THROWS(under min)
unguarded overMax raw = -9223372036854771616              // without the guard: silent Long wrap
```

The last line is why the boundary-second guard in Decision 2 exists: at `seconds ==
±922,337,203,685` a plain `seconds * 10^7 + frac` **silently wraps negative**. The guard is
verified reachable (a real ms-band Duration of `...685s + 478ms` hits it) and verified to prevent
the wrap.

## Alternatives Considered

### 1. Single `Int64` of ticks (100ns units, signed) → `System.TimeSpan` (chosen)

One `INT64` scalar, `TimeSpan.Ticks`' own domain. Kotlin converts via
`toComponents`/`Long.nanoseconds` on its side; C# uses `value.Ticks` and
`new TimeSpan(ticks)`.

Pros:
- The wire form is the destination type's own domain, exactly and only, same argument as ADR-076.
- One scalar: the entire nullable story rides the shipped nullable-primitive `INT64` machinery at
  all four positions, and every seam is a copy of the `BridgeType.Instant` row that already exists.
- C# → Kotlin is **total** (every `Int64` of ticks is a representable finite Duration), so that
  direction never throws; the two lossy/partial spots (sub-100ns truncation out of Kotlin,
  out-of-range/infinite throw out of Kotlin) each happen once, at a named place, on the Kotlin
  side where `errorOut` already delivers exceptions.

Cons:
- Kotlin → C# truncates below 100ns (unavoidable: `TimeSpan` cannot hold it).
- Kotlin → C# throws for `INFINITE`/`NEG_INFINITE` and for finite durations beyond ±29,228 years.
- C# → Kotlin silently drops sub-ms ticks for |ticks| beyond the ns band (about ±146 years). This
  is Duration's **own documented construction behaviour** (`Long.nanoseconds` does exactly this,
  verified), so the bridge is no lossier than the type itself.

### 2. Single `Int64` of whole nanoseconds

No band arithmetic on the Kotlin side (`inWholeNanoseconds` out, `Long.nanoseconds` in). Rejected
on range and on a trap: `Int64` nanoseconds covers only about ±292 years, narrower than
`TimeSpan`'s ±29,228, so the bridge would reject values the destination holds fine (ADR-076
rejected epoch-nanos for the identical reason). Worse, `inWholeNanoseconds` **saturates** rather
than throwing (verified), so a 300-year duration would silently become `Long.MAX_VALUE` ns unless
extra guards re-derive what the ticks form gets for free. The extra 1ns resolution on the wire is
unobservable: `TimeSpan` discards it on arrival.

### 3. `seconds: Long` + `nanoseconds: Int`, two wire components

`toJavaDuration`'s own decomposition, and the plan model could express it (ADR-076's constraint
check). Rejected for the same reason as ADR-076 Alternative 2: the exactness is unobservable
(collapsed into a lossy `TimeSpan` one line later), and it costs multi-component result shapes and
a two-component `NullableDispatch` that nothing else needs.

### 4. `FLOAT64` of seconds (the NSTimeInterval shape)

The one form that can carry `INFINITE` (`Double.POSITIVE_INFINITY`, which `Duration.toDouble` emits,
verified in source). Rejected: `TimeSpan` cannot hold an infinity either, so the throw just moves
from a named Kotlin `require` into `TimeSpan.FromSeconds` (**inferred**: `FromSeconds` of an
infinity throws; not spiked, because the option is rejected on precision alone); and a `double`
mantissa loses tick precision for durations beyond about 104 days. Strictly worse than ticks.

### 5. Leave it to the value-class machinery (`rawValue` passthrough)

What the classifier does today. Rejected: `rawValue` is `private`, the constructor `internal`, and
the discriminator-bit encoding is an implementation detail of the stdlib that must never become
ABI. Also expected to not even compile (see Context).

### 6. ISO-8601 `String`

Rejected wholesale per ADR-076 Alternative 3, with an extra twist: `Duration.toString()` emits
Kotlin's own `1h 30m` component format, and `TimeSpan.Parse` speaks a different dialect entirely.
There is no shared text format to even argue about.

## Decision

### 1. New `BridgeType.Duration`, a clone of `BridgeType.Instant`'s row at every seam

```kotlin
// ForwardMarshallingModel.kt
internal sealed interface BridgeType {
  /** ADR-103: kotlin.time.Duration. Wires as INT64 TimeSpan ticks; public C# type is TimeSpan. */
  data object Duration : BridgeType
}

internal enum class ForwardConversion { /* ... */ DURATION_TO_TICKS, TICKS_TO_DURATION }
internal enum class ForwardHelperRequirement { /* ... */ DURATION }
```

A sealed variant, not `Primitive(LONG)` with a flag, for the ADR-070/076 reason: the compiler then
enumerates every `when` that must change. The full seam list is exactly the set of sites that
mention `BridgeType.Instant` today (**verified** by grep; file:line as of this writing):

- `ForwardMarshallingModel.kt`: `:479` (scalar bucket), `:561` (flow → conversion), `:599`
  (conversion → helper requirement).
- `ForwardBridgeTypeClassifier.kt:99`: the recognition site; see Decision 3 for the ordering trap.
- `ForwardReachabilityClosure.kt:205`: `SCALAR_TERMINALS` gains `"kotlin.time.Duration"`.
- `ForwardCallablePlanner.kt`: `:53` (defensive classification note), `:1093`/`:1139`/`:1200-1219`
  (top-level nullable two-call), `:1406` (input helper requirement), `:1482-1489` (input parameter
  + `TICKS_TO_DURATION`), `:1696-1712` (nullable valueOut), `:1772-1782` (non-null result),
  `:1923-1948` (nullable result), `:2149` (`ForwardPlanSkipReason.DURATION`, defensive), `:2206`,
  `:2234`, `:2330` (untagged pass-through exclusion), `:2416` (collection-element deferral).
- `ForwardPropertyPlanner.kt`: `:259` (helper requirement), `:425` (scalar bucket), `:457`
  (dispatchable), `:476` (wire type INT64), `:530-533` (flow → conversion).
- `ForwardPropertyPlan.kt`: `:63`, `:92`.
- `ForwardKotlinPlanEmitter.kt`: `:95` (INT64 assert), `:230` (two-call admit), `:274`
  (`!!.toDotNetTicks()`, same spelling as Instant, see Decision 2), `:284` (default `0L`),
  `:757-763` (nullable valueOut), `:866`, `:1030`/`:1066` (`durationFromDotNetTicks(...)`), `:984`.
- `ForwardPropertyKotlinEmitter.kt`: `:112`, `:208`, `:335`, `:361`, `:380`.
- `ForwardCirPlanProjection.kt`: `:248`/`:284` (legacy two-call), `:551` (`${name}.Ticks`), `:593`
  (nullable fan-out), `:865`/`:968` (result lift), `:1291` (`global::System.TimeSpan`), `:1336`
  (value-type bucket).
- `ForwardCirPropertyProjection.kt`: `:254`, `:292-303`, `:515` (`$name.Ticks`), `:557` (INT64),
  `:578` (C# type).
- `ForwardDiagnostic.kt:317` (display name).
- `NugetProcessor.kt:1166-1170` (helper emission gate) and `GenericClassExports.kt:875-935`
  (the helper pair lives next to Instant's).

Duration takes the same branch as Instant at every one of these sites, in most cases by widening
the existing condition (`inner == BridgeType.Instant || inner == BridgeType.Duration`). The four
nullable `is BridgeType.Primitive`-style sites ADR-076 §4 lists are inside this set and need no
new machinery: the payload is `INT64`, the `hasValue` flag the ADR-069 `[MarshalAs(UnmanagedType.I1)]`
`BOOLEAN`.

### 2. Wire form: one `INT64` of `TimeSpan` ticks (100ns, signed, full `Int64` domain)

`ForwardAbiWireType.INT64`, value = `TimeSpan.Ticks`. Generated Kotlin helpers (emitted beside the
ADR-076 pair in `GenericClassExports.kt`; **verified**: this exact code compiles under konanc
2.4.10 and produced the spike output in Context):

```kotlin
private const val TIMESPAN_MAX_SECONDS: Long = 922_337_203_685L   // floor(Long.MAX_VALUE / 10^7); verified by spike
private const val TIMESPAN_MAX_FRAC_TICKS: Long = 4_775_807L      // Long.MAX_VALUE - TIMESPAN_MAX_SECONDS * 10^7
private const val TIMESPAN_MIN_FRAC_TICKS: Long = -4_775_808L     // Long.MIN_VALUE + TIMESPAN_MAX_SECONDS * 10^7

internal fun Duration.toDotNetTicks(): Long {
  require(isFinite()) {
    "Duration $this is infinite and cannot be represented as a System.TimeSpan"
  }
  return toComponents { seconds, nanoseconds ->
    require(seconds in -TIMESPAN_MAX_SECONDS..TIMESPAN_MAX_SECONDS) {
      "Duration $this is outside System.TimeSpan's range (about ±10675199 days)"
    }
    val frac: Long = nanoseconds / 100L
    require(!(seconds == TIMESPAN_MAX_SECONDS && frac > TIMESPAN_MAX_FRAC_TICKS)) {
      "Duration $this is outside System.TimeSpan's range (about ±10675199 days)"
    }
    require(!(seconds == -TIMESPAN_MAX_SECONDS && frac < TIMESPAN_MIN_FRAC_TICKS)) {
      "Duration $this is outside System.TimeSpan's range (about ±10675199 days)"
    }
    seconds * 10_000_000L + frac
  }
}

internal fun durationFromDotNetTicks(ticks: Long): Duration =
  if (ticks in -92_233_720_368_547_758L..92_233_720_368_547_758L) (ticks * 100L).nanoseconds
  else (ticks / 10_000L).milliseconds
```

Why each guard exists, all **verified** by the konanc spike:

- `isFinite()` first, because every saturating accessor (`toComponents`, `inWholeNanoseconds`)
  reports `INFINITE` as `Long.MAX_VALUE` seconds / ns and cannot distinguish it from a huge finite
  value downstream.
- The seconds range check before the multiply (ADR-076's check-before-multiply rule): a finite
  ms-band Duration can hold ±146 million years, and `seconds * 10^7` for those overflows silently.
- The boundary-second `frac` guards: at exactly `±922,337,203,685` seconds the multiply fits but
  the add can wrap (`...685s + 478ms` → raw `-9223372036854771616` without the guard, throws with
  it). Both guards are reachable by real ms-band values and both fire in the spike.
- `durationFromDotNetTicks` is total. `ticks * 100` is safe up to `Long.MAX_VALUE / 100 =
  92_233_720_368_547_758`; within that, the stdlib's own `Long.nanoseconds` picks the band
  (exact ns up to ±146 years, silent 1ms truncation beyond, its documented behaviour). Beyond it
  (`TimeSpan`s longer than about ±292 years), `ticks / 10_000` goes straight to the ms band, which
  is where such a value would land anyway; max is about 9.2e14 ms, far inside `MAX_MILLIS`, so the
  result is always finite and never collides with the `INFINITE` sentinel.

The helper spelling `toDotNetTicks()` deliberately matches Instant's: they are extensions on
different receivers and coexist in the same generated file, which lets the
`ForwardKotlinPlanEmitter.kt:274`-style sites emit the identical string for both types.

C#, at the four positions (each row the exact analogue of the `BridgeType.Instant` row at the
cited projection sites):

| seam | Instant today | Duration |
| --- | --- | --- |
| `csharpType()` | `"global::System.DateTimeOffset"` | `"global::System.TimeSpan"` |
| call argument | `param.UtcTicks` | `param.Ticks` |
| return lift | `new DateTimeOffset(r, TimeSpan.Zero)` | `new global::System.TimeSpan(nativeResult)` |
| `isCSharpReferenceType()` | `false` | `false` |

`new TimeSpan(long)` never throws for any tick value (**verified** by spike, including
`long.MinValue`), so the lift needs no guard, unlike `DateTimeOffset`'s non-negative domain.

### 3. Classifier ordering: recognized before the value-class branch

**This is the one place Duration is not a mechanical copy of Instant.** Duration is a `value
class`, and `ForwardBridgeTypeClassifier.classifyNonNullable` tests
`classDeclaration.isValueClass()` at `:117`, which would win and produce the broken
`ValueClass("rawValue")` binding from Context. The check

```kotlin
if (qualifiedName == "kotlin.time.Duration") return BridgeType.Duration
```

goes at `ForwardBridgeTypeClassifier.kt:99`, immediately after the `kotlin.time.Instant` line,
which the existing branch order already places ahead of `isValueClass()` (**verified** by reading
`:93-:117`: known scalars, Char, String, Instant, specialized protocols and collections all
precede the enum/value-class/sealed shape branches). Any future refactor of this method must keep
known-stdlib-name checks ahead of shape checks; this is the ordering trap the backlog item warned
about, and it is real.

`"kotlin.time.Duration"` is also added to `ForwardReachabilityClosure.SCALAR_TERMINALS`
(`ForwardReachabilityClosure.kt:205`) so the closure stops walking it as a class edge, mirroring
Instant.

### 4. `Duration.INFINITE` / `NEG_INFINITE` throw; nothing is saturated

Three candidate treatments existed:

- **Throw via `errorOut` (chosen).** Consistent with ADR-076's `DISTANT_PAST`/`DISTANT_FUTURE`
  decision and for the same reason: they are sentinels, and silently clamping them makes "is this
  infinite?" checks wrong on the C# side.
- **Map to `TimeSpan.MaxValue`/`MinValue`.** Kotlin's own `toJavaDuration` does saturate
  (**verified** in `jvmMain/jdk8/kotlin/time/DurationConversions.kt`: it is
  `toComponents { s, ns -> java.time.Duration.ofSeconds(s, ns) }`, and for `INFINITE` the
  components saturate to `Long.MAX_VALUE` seconds). But that precedent does not transfer:
  `java.time.Duration` holds `Long.MAX_VALUE` seconds, about 292 **billion** years, a value no
  program treats as meaningful, while `TimeSpan.MaxValue` is a reachable 29,228 years that a real
  timeout could legitimately hold, and the saturated value round-trips back as a *finite*
  Duration (verified: `fromTicks(Long.MAX_VALUE)` is finite).
- **Map to `Timeout.InfiniteTimeSpan`.** Disqualified outright: it is the magic value -1ms
  (**verified**: -10000 ticks) and collides with an honest -1ms duration.

### 5. Precision and range contract (state this in the generated XML docs)

All rows **verified** by the konanc spike in Context:

| direction | precision | range |
| --- | --- | --- |
| C# → Kotlin | exact for \|ticks\| ≤ 46,116,860,184,269,999 (about ±146 years); 1ms granularity beyond, silently, matching `Duration`'s own construction semantics | total; every `Int64` of ticks is accepted and yields a finite Duration |
| Kotlin → C# | truncated toward zero to 100ns | about ±10,675,199 days (`TimeSpan`'s range); infinite or out-of-range finite Durations throw `IllegalArgumentException` from the Kotlin export, delivered through the existing `errorOut` slot |
| C# → Kotlin → C# | exact within ±146 years; ≤1ms loss beyond (e.g. `TimeSpan.MaxValue` comes back 5807 ticks short) | total |
| Kotlin → C# → Kotlin | exact for ms-band values in range and for ns-band values that are multiples of 100ns; sub-100ns truncated once | as above |

### 6. The C# the consumer sees

For the fixture in Consequences:

```csharp
public sealed class NapTracker : IDisposable
{
    public NapTracker(TimeSpan longestNap, TimeSpan? lastNap) { ... }

    public TimeSpan LongestNap { get; }
    public TimeSpan? LastNap { get; set; }

    public TimeSpan Extend(TimeSpan extra) { ... }
    public TimeSpan? ShortestNap() { ... }
    public string Describe(TimeSpan? nap) { ... }
}

public static class NapTrackerKt   // ADR-007 file class; top-level funs keep camelCase (ADR-076 §6)
{
    public static TimeSpan napEpsilon() { ... }
    public static TimeSpan? parseNap(string text) { ... }
}
```

And the assertions the consumer's tests hinge on:

```csharp
using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);
Assert.Equal(TimeSpan.FromMinutes(90), tracker.LongestNap);   // exact round trip
Assert.Null(tracker.LastNap);
tracker.LastNap = new TimeSpan(-25000000);                    // negative: fine, -2.5s
Assert.Equal(-25000000L, tracker.LastNap!.Value.Ticks);

// C# -> Kotlin is total: even TimeSpan.MaxValue is accepted; it comes back <=1ms short.
// Kotlin -> C# throws only from the Kotlin side: a member returning Duration.INFINITE
// (NapClock.InfiniteNap() below) surfaces the Kotlin require through errorOut as a C# exception.
Assert.Equal(1, NapTrackerKt.napEpsilon().Ticks);             // Kotlin 150.nanoseconds -> 1 tick, truncation not rounding
```

### 7. Prior art

- **Kotlin's own JVM interop** (`toJavaDuration`/`toKotlinDuration`, **verified** in the 2.4.10
  sources jar): explicit conversion to the platform's idiomatic duration type, via the public
  `toComponents` surface, exactly the shape of the generated helper here. Its saturation of
  `INFINITE` does not transfer (Decision 4).
- **.NET guidance**: `TimeSpan` is the type for "an elapsed period of time"
  ([Choosing between DateTime, DateOnly, DateTimeOffset, TimeSpan, TimeOnly, and TimeZoneInfo](https://learn.microsoft.com/en-us/dotnet/standard/datetime/choosing-between-datetime)).
  There is no rival candidate on the C# side.
- **ObjC/Swift export**: **inferred**, not verified against a generated header: `Duration` is a
  value class, which Kotlin/Native's ObjC export cannot surface as such; the manual route Kotlin
  documents is `Duration.toDouble(SECONDS)` → `NSTimeInterval`. Alternative 4's shape, rejected
  above on precision. Not investigated further; the JVM precedent settles the design.

## Consequences

### Fixture surface in `test-library`

New file
`test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/NapTracker.kt`,
crossing each seam the feature goes through and no more (the exact position list ADR-076's
`SightingLog` proved out, plus two members that exist only to reach the throwing paths from C#):

```kotlin
class NapTracker(val longestNap: Duration, var lastNap: Duration?) { // ctor x2, val property, var nullable property
  fun extend(extra: Duration): Duration                              // method param + non-null return
  fun shortestNap(): Duration?                                       // nullable method return (valueOut)
  fun describe(nap: Duration?): String                               // nullable param
  fun maybeEcho(nap: Duration?): Duration?                           // nullable in and out, same callable
  fun echo(nap: Duration): Duration                                  // non-null in and out, same callable
}

object NapClock {                                                    // static export path (OBJECT origin)
  fun defaultNap(): Duration                                         // static non-null return
  fun isLong(nap: Duration): Boolean                                 // static non-null parameter
  fun infiniteNap(): Duration = Duration.INFINITE                    // reaches the isFinite() require
  fun aeonNap(): Duration = (200_000L * 365).days                    // finite but outside TimeSpan: reaches the range require
}

fun napEpsilon(): Duration = 150.nanoseconds                         // top-level return; asserts 100ns truncation
fun parseNap(text: String): Duration?                                // top-level nullable return (ADR-002 two-call)
```

Boundary values the C# tests must cover, all **verified** as the real edges by the spikes:
`TimeSpan.Zero`; a negative `TimeSpan`; `TimeSpan.MinValue` and `TimeSpan.MaxValue` round-tripped
through `echo` (asserting the documented ≤1ms loss: `MaxValue` returns ticks
`9223372036854770000`, `MinValue` returns `-9223372036854770000`); the ns-band edge
`new TimeSpan(46116860184269999)` (asserting an exact round trip); `napEpsilon()` returning
exactly 1 tick (truncation, not rounding); `NapClock.InfiniteNap()` and `NapClock.AeonNap()` each
asserting a thrown exception, not a wrapped value. No `Instant` cell, no collection cell.

### What changes

- `BridgeType.Duration`, `DURATION_TO_TICKS`/`TICKS_TO_DURATION`, `ForwardHelperRequirement
  .DURATION`, and their validator rows.
- The classifier line at `ForwardBridgeTypeClassifier.kt:99` (ordering per Decision 3) and the
  `SCALAR_TERMINALS` entry.
- Every seam in the Decision 1 list, each a copy of its `BridgeType.Instant` neighbour.
- One generated Kotlin helper pair (three constants, two functions, **verified** to compile on
  konanc 2.4.10) and two C# expressions.

### What breaks

Nothing that works today. A `Duration`-bearing member currently produces either a konanc compile
failure of the generated exports or a broken `rawValue` binding (**inferred**, Context); both are
defects, not surfaces anyone consumes.

### Deferred, with the one-line reason each

- **`DurationUnit`**: an ordinary stdlib enum outside the export set; nothing asks for it.
- **`Duration` as a collection element / type argument**: same ADR-073/075
  `isWrappableComponent()` seam Instant defers (`ForwardCallablePlanner.kt:2412-2416`).
- **`Duration` as an extension receiver**: same deferral as Instant, no consumer shape asks.
- **`kotlinx.datetime` period types (`DateTimePeriod`, `DatePeriod`)**: calendar-relative, not a
  fixed elapsed time; `TimeSpan` is the wrong target and they need their own design.
- **Sub-100ns fidelity and >±29,228y finite durations**: unrepresentable in `TimeSpan`;
  truncating and throwing respectively is the contract.
- **An infinity convention (e.g. `Timeout.InfiniteTimeSpan`)**: rejected in Decision 4; revisit
  only if a real consumer needs `Duration.INFINITE` to cross.

### Open questions / unverified load-bearing claims

1. **Verified.** The generated-pipeline end-to-end path has now been run: a KSP round trip against
   the `NapTracker`/`NapClock` fixture confirms "what happens today" was misbound, not skipped.
   Reverting the classifier ordering (Decision 3) reproduces exactly the predicted failure: konanc
   fails to compile the generated `CNameExports.kt`, because it calls the `private val rawValue`
   / `internal` constructor the value-class branch (`ForwardBridgeTypeClassifier.kt:265`) would
   bind. A compile error, not a silent skip, as Alternative 5 and the Context section predicted.
2. **Verified.** KSP does resolve `kotlin.time.Duration` to a `KSClassDeclaration` with
   `isValueClass()` true from the stdlib klib: with the Decision 3 ordering removed, the
   classifier reaches `ForwardBridgeTypeClassifier.kt:117` for `Duration` and takes the
   value-class branch, producing the broken binding bullet 1 confirms. With the ordering in place
   (`:99`, ahead of `:117`), the same fixture classifies as `BridgeType.Duration` and produces the
   generated output in Decision 2 and Consequences below.
3. **`TimeSpan` invariants on net8.0.** Spiked on SDK 10.0.300; that `MinValue`/`MaxValue` ticks,
   the never-throwing ctor and `TicksPerSecond` are identical on net8.0 is **inferred** from their
   status as documented, framework-era invariants.
4. **`TimeSpan.FromSeconds(double.PositiveInfinity)` throwing** (Alternative 4): **inferred**, not
   spiked; load-bearing only for a rejected alternative.

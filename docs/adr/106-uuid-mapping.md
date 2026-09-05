# ADR-106: `kotlin.uuid.Uuid` maps to `System.Guid` over the RFC 9562 hex-dash `String` wire

## Status
Accepted

**As shipped**: the fixture object is `MicrochipRegistry`, not `ChipRegistry` as this ADR's prose
names it. `ChipRegistry` was already taken by the ADR-069 `NullableBooleanSample.kt` fixture in the
same `cat` package (see `test-library/src/nativeMain/kotlin/.../cat/Microchip.kt`).

## Context

Issue #56 part 3: a `kotlin.uuid.Uuid` at property, return, constructor-parameter or
method-parameter position is skipped today (`SKIPPED_UNSUPPORTED_PROPERTY` for the property,
`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` for `<init>`/`copy`), with the same unactionable
`include(kotlin)` hint ADR-076 removed for `Instant`. The consumer goal is the same as ADR-076 and
ADR-103: a third first-class known stdlib type, surfacing as the .NET type C# developers already
hold identifiers in, `System.Guid`, at every ordinary position, including `Uuid?` → `Guid?`.

**Why this is not a mechanical clone of ADR-103.** Instant and Duration both fit one `INT64` slot,
so every seam ADR-103 lists is a `inner == Instant || inner == Duration` widening of a
single-scalar row. `Uuid` is 128 bits and does not fit any scalar wire the bridge has. The two
candidates were therefore (a) a **binary** two-`INT64` form, which needs a new multi-scalar
property-getter shape, and (b) the **`String`** wire, whose row already exists at every position.
Both give identical consumer C#. The human decision at the Step 2 gate chose (b) on the narrowness
rule: the string wire is exactly ADR-103's file list with `BridgeType.String` as the neighbour row
and zero new plan shapes. The binary form is recorded in full under Alternatives, with its spike
evidence, so a future ADR can pick it up if per-crossing cost ever matters.

### What `kotlin.uuid.Uuid` actually is

**Verified** by extracting `kotlin-stdlib-2.4.10-common-sources.jar`
(`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.10/9888df88.../`),
`commonMain/kotlin/uuid/Uuid.kt`:

```
53:@SinceKotlin("2.4")
54:@WasExperimental(ExperimentalUuidApi::class)
55:public class Uuid private constructor(
56:    @PublishedApi internal val mostSignificantBits: Long,
57:    @PublishedApi internal val leastSignificantBits: Long,
58:) : Comparable<Uuid>, Serializable {
87:    public inline fun <T> toLongs(action: (mostSignificantBits: Long, leastSignificantBits: Long) -> T): T
147:    override fun toString(): String { return toHexDashString() }      // lowercase, 36 chars, RFC 9562 §4
213:    public fun toByteArray(): ByteArray                                 // 16 bytes, msb first
294:        public val NIL: Uuid = Uuid(0, 0)
314:        public fun fromLongs(mostSignificantBits: Long, leastSignificantBits: Long): Uuid =
399:        public fun parse(uuidString: String): Uuid                      // case-insensitive, hex-dash or 32-hex
433:        public fun parseOrNull(uuidString: String): Uuid?
581:        public fun random(): Uuid = @OptIn(ExperimentalUuidApi::class) generateV4()
```

So: a plain `public class` (not a `value class`, so **no ADR-103 ordering trap**; it falls through
to the `exportedObjectHandles` check exactly like `Instant` did), whose `toString()` is the
lowercase 36-character RFC 9562 hex-and-dash form (`toHexDashString` KDoc, :150-160: "in lowercase
and consists of 36 characters", "starting from the most significant 4 bits") and whose `parse` is
documented case-insensitive (:387-389) and throws `IllegalArgumentException` on malformed input
(:403-407). The only members still annotated `@ExperimentalUuidApi` in this file are `generateV4()`
(:618), `generateV7()` (:669), `generateV7NonMonotonicAt()` (:724) and the deprecated
`LEXICAL_ORDER` (:767).

**Verified (this session and by the main thread)**: `gradle/libs.versions.toml:2` pins Kotlin
2.4.10, so `Uuid`, `toString`, `parse`, `parseOrNull`, `random()` are stable and the generated
exports need **no** `@OptIn(ExperimentalUuidApi::class)`. The processor's existing opt-in list
(`NugetProcessor.kt:860-878`: `ExperimentalNativeApi`, `ExperimentalForeignApi`, conditionally
`ExperimentalCoroutinesApi`) is untouched. Nothing in `test-library/build.gradle.kts`, the root
`build.gradle.kts` or `nuget-plugin/src/main` pins `apiVersion`/`languageVersion` below 2.4 (grep).

### What happens today

**Verified by the issue reporter's output**, not by a run in this repo: the property is skipped
as `SKIPPED_UNSUPPORTED_PROPERTY` ("has no property getter or setter shape") and the
constructor/`copy` as `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`. `ForwardBridgeTypeClassifier
.classifyNonNullable` (`ForwardBridgeTypeClassifier.kt:93-125`) has no `kotlin.uuid.Uuid` line, so
the type reaches the `exportedObjectHandles` membership check and is reported as an unexported
dependency, the same path ADR-076 §Context describes for `Instant`.

### Text form on both sides (verified by spike)

Kotlin, against the real `kotlin-stdlib-2.4.10.jar` (JVM run; `Uuid` is `commonMain`, so the
behaviour is shared with Kotlin/Native, but **no konanc run was made**):

```
$ javac -cp kotlin-stdlib-2.4.10.jar T.java && java -cp "kotlin-stdlib-2.4.10.jar;." T
toString=00112233-4455-6677-8899-aabbccddeeff
msb=0x0011223344556677 lsb=0x8899aabbccddeeff
toByteArray=00112233445566778899AABBCCDDEEFF
fromLongs equals=true str=00112233-4455-6677-8899-aabbccddeeff
parse upper equals=true
NIL=00000000-0000-0000-0000-000000000000 fromLongs(0,0)==NIL true
```

.NET, compiled against the net8.0 reference pack, executed on the 9.0.17 runtime via
`DOTNET_ROLL_FORWARD=Major`:

```
$ dotnet new console && sed -i 's#net10.0#net8.0#' probe.csproj && dotnet run   # SDK 10.0.301
ToString()          = 00112233-4455-6677-8899-aabbccddeeff
ToByteArray()       = 33221100554477668899AABBCCDDEEFF          // mixed-endian: Data1/2/3 little-endian
ToByteArray(true)   = 00112233445566778899AABBCCDDEEFF          // == Kotlin toByteArray()
fromHalves          = 00112233-4455-6677-8899-aabbccddeeff  equals=True
Guid(bytes) LE ctor = 33221100-5544-7766-8899-aabbccddeeff      // same bytes, no bigEndian flag
TryWriteBytes ok=True n=16 msb=0x0011223344556677 lsb=0x8899aabbccddeeff
Guid(int,short,short,byte[8]) = 00112233-4455-6677-8899-aabbccddeeff equals=True
Guid.Empty = 00000000-0000-0000-0000-000000000000 ; default==Empty True
Parse upper ok: True
sizeof Guid = 16
```

What the chosen design takes from this:

- `Guid.ToString()` (the default `"D"` format) is **lowercase hex-dash, byte-for-byte identical to
  `Uuid.toString()`** for the same value, and `Guid.Parse` accepts uppercase too. Text is therefore
  an exact, lossless, canonical carrier for all 128 bits on both sides. None of the three failure
  modes that made ADR-076 reject the ISO wire for `Instant` (fractional rounding, out-of-range
  `FormatException`, dialect mismatch) exists here.
- `Guid.Empty == default(Guid)` and `Uuid.fromLongs(0,0) === Uuid.NIL`: the all-zero value is
  legitimate on both sides and must round-trip as a value, not as null (it does: its text form is
  `00000000-0000-0000-0000-000000000000`, a non-null string).
- The byte-order rows are kept **for the binary alternative only** (Alternative 2): they prove
  that the binary form is implementable and name its one trap (`bigEndian: true` is load-bearing).
  The string wire never touches bytes, so **no byte-order question exists in the chosen design**,
  and the `Guid(ReadOnlySpan<byte>, bool bigEndian)` availability question is moot
  (`IntegrationTests.csproj:5` targets net10.0 anyway, **verified** by the main thread).

### How `String` and `String?` cross today (verified by source reading)

This is the row `Uuid` copies, so it is stated precisely:

- **Wire**: `ForwardAbiWireType.STRING` at inputs (`ForwardPropertyPlanner.kt:581`), `POINTER` at
  results (`ForwardKotlinPlanEmitter.kt:71-79` requires `call.result == POINTER` and returns a
  Kotlin `String`; the Kotlin/Native runtime performs the `String` → `const char*` conversion, and
  there is no repo-side free export: a grep for `free_string`/`nativeHeap.free` finds none). C#
  reads results with `Marshal.PtrToStringUTF8(nativeResult)` (`ForwardCirPlanProjection.kt:919-925`,
  `ForwardCirPropertyProjection.kt:208-226`).
- **Nullable**: `Nullable(String)` is a **single slot whose null pointer is the sentinel**, unlike
  `Nullable(Primitive)`/`Instant?`, which need an out-of-band has-value channel.
  `ForwardCirPlanProjection.kt:587` emits just `parameter.name` for a `String?` argument while
  `:590` emits the `HasValue`/`GetValueOrDefault()` pair for `Primitive?`; ADR-079 (README:82)
  records the reason ("unlike `String`/`ObjectHandle` the wire has no spare null"). `Instant?`
  takes the has-value channel at all four positions (ADR-076 §4, `076-instant-mapping.md:303-320`).
  **`Uuid?` follows `String?`, not `Instant?`.**
- **Property**: `BridgeType.String` is a supported property type and a supported extension
  receiver (`ForwardPropertyPlanner.kt:179`, `:187`, `:468`, `:480`, `:516`, `:551`, `:581`,
  `:587`), with `Direct` getters/setters and the `NullableDispatch` setter for `String?`.

## Alternatives Considered

### 1. RFC 9562 hex-dash `String` wire (`Uuid.toString()` / `Uuid.parse`, `Guid.ToString()` / `Guid.Parse`) (chosen)

`BridgeType.Uuid` takes `BridgeType.String`'s branch at every seam, with a conversion composed on
each side: Kotlin emits `uuid.toString()` out and `Uuid.parse(text)` in; C# emits
`param.ToString()` out and `Guid.Parse(Marshal.PtrToStringUTF8(p)!)` in.

Pros:
- **Zero new plan shapes.** Inputs, non-null results, nullable results, top-level nullable
  results, property getters, property setters, nullable setters and extension receivers all
  already exist for `String`. The cost estimate is ADR-103's 23-file list, no more.
- Exact and total in both directions: every `Guid` has a 36-char text form `Uuid.parse` accepts,
  and every `Uuid` has one `Guid.Parse` accepts (**verified** for the spike vector, uppercase input
  and the all-zero value; the format is canonical and fixed-width, so there is no value-dependent
  edge). No range check, no throw on a well-formed crossing, no truncation.
- `Uuid?` rides the null-pointer sentinel with no new has-value machinery and no
  `topLevelNullablePrimitivePlan` reroute.
- Identical consumer C# to the binary alternative.

Cons:
- Per Kotlin → C# crossing: one Kotlin `String` allocation (36 chars), the runtime's
  `const char*` conversion, one UTF-8 decode into a managed `string`, and one `Guid.Parse`. Per
  C# → Kotlin crossing: one `Guid.ToString()` allocation, one UTF-8 encode, and one `Uuid.parse`.
  The same cost class as a `String` property today, for a value that is 16 bytes.
- The bridge carries a text format as its ABI contract. Mitigated: both ends are generated in
  lockstep from the same plan, and RFC 9562 §4 hex-dash is the twenty-year-stable canonical form.

### 2. Two `INT64` halves (`mostSignificantBits`, `leastSignificantBits`, RFC big-endian) (runner-up, verified implementable)

The wire would be `Uuid`'s own public bit surface (`toLongs`/`fromLongs`) and the shape of Kotlin's
JVM interop, `Uuid.toJavaUuid()` = `java.util.UUID(msb, lsb)`
([API reference](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.uuid/to-java-uuid.html)).
C# would reassemble with `BinaryPrimitives.WriteInt64BigEndian` ×2 into a `stackalloc byte[16]`
and `new Guid(span, bigEndian: true)`, and disassemble with `TryWriteBytes(span, bigEndian: true,
out _)` + `ReadInt64BigEndian` ×2. **Verified by the .NET spike above**: this reproduces
`Uuid.toString()` exactly; omitting `bigEndian: true` yields `33221100-5544-7766-...` from the
same bytes (the one trap); the `Guid(int, short, short, byte[8])` ctor fed `(int)(msb >> 32),
(short)(msb >> 16), (short)msb` + the big-endian `lsb` bytes is an equivalent spelling. Both
`bigEndian` overloads compile against the net8.0 reference pack (**verified**); runtime behaviour
was observed on 9.0.17 (**inferred** identical on 8.0/10.0).

Its exact costs, from the plan-model constraint check (**verified** by source reading):

- Inputs: free. `nativeInputParameters` returns a `List` and callers `flatMap`; nullable
  primitives already fan out to two adjacent params.
- Callable returns: small. `ForwardResultShape.extraParameters` is a list of OUT pointers
  (`ForwardCallablePlanner.kt:2175-2178`); nullable `Instant` already writes one `CPointer<LongVar>`
  (`:1984-2016`, `ForwardKotlinPlanEmitter.kt:757-770`). A second slot plus a VOID-result variant
  for the non-null case.
- **Property getters: new machinery.** `ForwardPropertyGetter` is `Direct(call)` /
  `LegacyTwoCall(presence, value)` (`ForwardPropertyPlan.kt:12-19`), each a single-result call.
  The model can express OUT parameters (`ForwardMarshallingModel.kt:314-325`) but
  `ForwardPropertyPlanner`, `ForwardPropertyKotlinEmitter` and `ForwardCirPropertyProjection`
  have no getter-with-OUT-parameters emit path. The getter must be single-call (a `_msb`/`_lsb`
  two-call on a `var` property can tear into a plausible wrong Guid, silently).
- `Uuid?`: needs the out-of-band **has-value channel** at every position (all-zero is legitimate,
  `fromLongs(0,0) === NIL`, `default(Guid) == Guid.Empty`), with the payload widened from
  `Instant?`'s one `INT64` to two, and the top-level `Uuid?` return rerouted off
  `topLevelNullablePrimitivePlan` (ADR-076 §4.3 records that path crashing `packNuget` for
  unhandled shapes).

Why it lost: identical consumer C#, and the narrowness rule. Allocation-free identifier reads are
a real but unrequested benefit; a new getter shape in three property files is a real cost that
every later property-position feature inherits. If a consumer ever measures `Guid` crossings as a
hot path, this alternative is fully specified and its byte-order trap is already spiked.

### 3. 16-byte binary buffer (`POINTER` to `byte[16]`, RFC order)

One slot at every position, `new Guid(span, bigEndian: true)` directly over the bytes. Rejected:
the String path's pointer is runtime-owned (Context), so on the Kotlin → C# direction this needs
either a Kotlin-side `nativeHeap.alloc` plus a new free export, or a caller-provided OUT buffer,
which is Alternative 2's getter machinery with an `unsafe byte*` instead of two `long`s.

### 4. C struct by value (`Guid` is a 16-byte blittable struct)

**Inferred, not verified**: Kotlin/Native `@CName` exports cannot declare or return a C struct by
value (`CValue<T>` exists only for cinterop-imported struct types), so this would need a cinterop
`.def` in the generated exports. Rejected on cost; load-bearing only for a rejected option.

### 5. Opaque `StableRef` handle (`KotlinUuid : IDisposable`)

What ObjC export does for the same type (§6). Rejected by the same reasoning as ADR-076
Alternative 5 and by the issue itself ("C# has `System.Guid`, so there is a clean target").

## Decision

### 1. New `BridgeType.Uuid`, a sealed variant that copies `BridgeType.String`'s row at every seam

```kotlin
// ForwardMarshallingModel.kt
internal sealed interface BridgeType {
  /** ADR-106: kotlin.uuid.Uuid. Wires exactly as String (RFC 9562 hex-dash text); public C# type is System.Guid. */
  data object Uuid : BridgeType
}

internal enum class ForwardConversion { /* ... */ UUID_TO_STRING, STRING_TO_UUID }
internal enum class ForwardHelperRequirement { /* ... */ UUID }   // drives `import kotlin.uuid.Uuid` in the generated file
```

A sealed variant, not a re-use of `BridgeType.String` with a flag, for the ADR-070/076 reason: the
compiler enumerates every `when`, and a fall-through here would publish a `string` where the
consumer expects a `Guid`. Recognition goes at `ForwardBridgeTypeClassifier.kt:104`, directly after
the `kotlin.time.Duration` line (`if (qualifiedName == "kotlin.uuid.Uuid") return BridgeType.Uuid`),
and `"kotlin.uuid.Uuid"` is added to `ForwardReachabilityClosure.SCALAR_TERMINALS`
(`ForwardReachabilityClosure.kt:208-211`). Ordering relative to `isValueClass()` is irrelevant
(`Uuid` is not a value class, **verified**), but the line stays in the known-stdlib block ahead of
the shape branches by convention.

### 2. Wire form: the `String` wire, carrying `Uuid.toString()`

At every seam `BridgeType.Uuid` takes the same branch as `BridgeType.String`: `STRING` wire type
at inputs, `POINTER` at results, the runtime-owned `const char*` on the way out, UTF-8 in. The
seam list is exactly the set of sites that mention `BridgeType.String` today (the same files
ADR-103's Decision 1 enumerates for `Instant`; the implementer greps `BridgeType.String` rather
than `BridgeType.Instant`). The conversion is composed on the Kotlin side and the C# side:

| seam | `String` today | `Uuid` |
| --- | --- | --- |
| Kotlin export return | `<expr>` (a `String`) | `<expr>.toString()` |
| Kotlin export nullable return | `<expr>` (a `String?`) | `<expr>?.toString()` |
| Kotlin export parameter | `param` | `Uuid.parse(param)` |
| Kotlin export nullable parameter | `param` | `param?.let(Uuid::parse)` |
| Kotlin property setter | `value` (`ForwardPropertyKotlinEmitter.kt:333`) | `Uuid.parse(value)` |
| `csharpType()` | `"string"` (`ForwardCirPlanProjection.kt:1316`) | `"global::System.Guid"` |
| C# call argument | `param` | `param.ToString()` |
| C# nullable call argument (`:587`) | `param` | `param?.ToString()` |
| C# result lift (`:919-925`) | `Marshal.PtrToStringUTF8(nativeResult)` | `global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!)` |
| C# nullable result lift | same, `string?` | `nativeResult == IntPtr.Zero ? null : global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!)` |
| C# property getter (`ForwardCirPropertyProjection.kt:219`, `:226`) | `Marshal.PtrToStringUTF8(nativeResult)!` | `Guid.Parse(...)`, nullable as above |
| `isCSharpReferenceType()` | `true` | **`false`** (so `Uuid?` renders `Nullable<Guid>`, `Guid?`) |

No generated Kotlin helper function is needed: `toString()` and `Uuid.parse` are the stdlib's own
surface. `ForwardHelperRequirement.UUID` exists only so the generated `CNameExports.kt` gains
`import kotlin.uuid.Uuid` (beside the ADR-076/103 imports in `GenericClassExports.kt:875-960`),
and so `ForwardCallablePlanValidator` can assert the conversion/helper pairing as it does for
`INSTANT`/`DURATION`.

**Text contract (verified)**: Kotlin emits lowercase hex-dash, 36 chars (`toHexDashString`).
`Guid.Parse` accepts it, and `Guid.ToString()` emits the identical lowercase `"D"` form, which
`Uuid.parse` accepts (case-insensitively, so a future .NET that emitted uppercase would still
parse). The implementer must **not** pass a format string to `ToString()`: `"N"` (no dashes) would
still parse on the Kotlin side (`parse` accepts 32-hex) but `"B"`/`"P"`/`"X"` would not.

**Failure modes**: `Uuid.parse` throws `IllegalArgumentException` on malformed text and
`Guid.Parse` throws `FormatException`; neither is reachable from generated code, because each side
only ever parses the other side's canonical `toString()`. If either ever fires, it is a generator
defect, not a consumer error, and it surfaces through the existing `errorOut` slot (Kotlin side) or
as an ordinary exception from the C# lift.

### 3. `Uuid?` → `Guid?` over the null-pointer sentinel, exactly as `String?`

`Uuid?` is `Nullable(Uuid)` and takes the `Nullable(String)` branch at every position: a single
slot whose `IntPtr.Zero` means null (`ForwardCirPlanProjection.kt:587` for arguments, the
`string?` result projection at `:919-925`, the `NullableDispatch` setter and `Direct` nullable
getter in the property files). **Not** `Instant?`'s has-value channel
(`076-instant-mapping.md:303-320`): that channel exists because an `INT64` has no spare null, and
a pointer does. The all-zero value is unaffected: `Uuid.NIL` / `Guid.Empty` crosses as the
non-null string `00000000-0000-0000-0000-000000000000`.

`ForwardPropertyPlan.validate` (`ForwardPropertyPlan.kt:56-60`), which asserts the
`LegacyTwoCall`/`NullableDispatch` pairing for `Instant?`, must **not** gain a `Uuid` row there;
`Uuid?` validates as `String?` does.

### 4. Precision and range contract

Exact and total in both directions; no value throws, truncates or clamps. `Guid.Empty` ↔
`Uuid.NIL`. Version/variant bits are carried verbatim; neither `Uuid.parse` nor `Guid.Parse`
validates RFC version fields (**verified** by source for `parse`; **inferred** for `Guid.Parse`,
which is documented to accept any 32 hex digits). Cost per crossing is stated in Consequences.

### 5. The C# the consumer sees

For the fixture in Consequences:

```csharp
public sealed class Microchip : IDisposable
{
    public Microchip(Guid chipId, Guid? previousChipId) { ... }

    public Guid ChipId { get; }
    public Guid? PreviousChipId { get; set; }

    public bool Matches(Guid candidate) { ... }
    public Guid Reissue() { ... }
    public Guid? LastRetired() { ... }
    public string Describe(Guid? tag) { ... }
    public Guid? MaybeEcho(Guid? tag) { ... }
    public Guid Echo(Guid tag) { ... }
}

public static class ChipRegistry          // Kotlin `object`: static export path
{
    public static Guid Nil() { ... }
    public static bool IsNil(Guid tag) { ... }
}

public static class MicrochipKt           // ADR-007 file class; top-level funs keep camelCase (ADR-076 §6)
{
    public static Guid wellKnownChip() { ... }
    public static Guid? parseChip(string text) { ... }
}
```

### 6. Prior art

- **Kotlin's own JVM interop**: `Uuid.toJavaUuid()` / `java.util.UUID.toKotlinUuid()` go through
  the two 64-bit halves
  ([API reference](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.uuid/to-java-uuid.html)).
  That is Alternative 2's shape; it was possible there because `java.util.UUID` has a `(long,
  long)` constructor and the JVM has no marshalling boundary to pay for. The principle that
  transfers is "explicit conversion to the platform's idiomatic identifier type, never an opaque
  handle"; the wire form does not have to.
- **.NET**: `System.Guid` is the identifier type; `Guid.Parse`/`ToString()` speak RFC hex-dash
  ([Guid.ToString](https://learn.microsoft.com/en-us/dotnet/api/system.guid.tostring),
  **verified** lowercase `"D"` default by spike).
- **ObjC/Swift export**: **inferred**, not verified against a generated header, and a web search
  turned up no documentation either way: `Uuid` is a plain Kotlin class outside the fixed ObjC
  stdlib-mapping list, so it is expected to surface as an opaque `KotlinUuid` wrapper rather than
  `NSUUID`, the same outcome ADR-076 §7 records for `Instant` and the Alternative 5 we avoid.
  Kotlin/JS: not investigated; the JVM precedent settles the design.

## Consequences

### Fixture surface in `test-library`

New file
`test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/Microchip.kt`
(a new file per feature is the precedent: `SightingLog.kt` for ADR-076, `NapTracker.kt` for
ADR-103; `Collar` was avoided because a reverse-direction `Collar` struct fixture already exists):

```kotlin
import kotlin.uuid.Uuid   // no @OptIn needed on 2.4.10 (verified)

class Microchip(val chipId: Uuid, var previousChipId: Uuid?) {   // ctor x2, val property, var nullable property
  fun matches(candidate: Uuid): Boolean                     // method parameter
  fun reissue(): Uuid                                       // non-null method return (POINTER, runtime-owned string)
  fun lastRetired(): Uuid?                                  // nullable method return (null pointer = null)
  fun describe(tag: Uuid?): String                          // nullable parameter (single slot)
  fun maybeEcho(tag: Uuid?): Uuid?                          // nullable in and out, same callable
  fun echo(tag: Uuid): Uuid                                 // non-null in and out, same callable
}

object ChipRegistry {                                       // static export path (OBJECT origin)
  fun nil(): Uuid = Uuid.NIL                                // static return; asserts Guid.Empty crosses as a value
  fun isNil(tag: Uuid): Boolean = tag == Uuid.NIL           // static parameter
}

fun wellKnownChip(): Uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")  // top-level return; the spike vector
fun parseChip(text: String): Uuid? = Uuid.parseOrNull(text)                      // top-level nullable return

data class ChipRecord(val id: Uuid)                         // the issue's exact repro: proves `copy` and the
                                                            // data-class ctor take the parameter position for free
```

Boundary values the C# tests must cover: the spike vector
`00112233-4455-6677-8899-aabbccddeeff` (asserting `wellKnownChip().ToString()` equals it exactly
and `wellKnownChip() == Guid.Parse(...)`); `Guid.Empty` through `Echo`, the `var` property and
`ChipRegistry.Nil()` (asserting it comes back as `Guid.Empty`, not null); a `Guid.NewGuid()`
round-tripped through ctor, `Echo` and the `var` property; `null` through every nullable position;
`parseChip("not a uuid")` returning `null`; a `ChipRecord` constructed from C# whose `Id`
round-trips. No `Instant` or `Duration` cell, no collection cell.

### What changes

- `BridgeType.Uuid`, `UUID_TO_STRING`/`STRING_TO_UUID`, `ForwardHelperRequirement.UUID`, validator
  rows; classifier line and `SCALAR_TERMINALS` entry.
- Every seam that mentions `BridgeType.String` gains a `BridgeType.Uuid` neighbour (or a widened
  condition) with the conversion from Decision 2 composed on top. The file list is ADR-103's
  (`ForwardMarshallingModel`, `ForwardBridgeTypeClassifier`, `ForwardReachabilityClosure`,
  `ForwardCallablePlanner`, `ForwardCirPlanProjection`, `ForwardCirPropertyProjection`,
  `ForwardDiagnostic`, `ForwardKotlinPlanEmitter`, `ForwardPropertyKotlinEmitter`,
  `ForwardPropertyPlan`, `ForwardPropertyPlanner`, `NugetProcessor`, `GenericClassExports`, the
  two processor tests, the fixture, `FEATURES.md`, `docs/topics/primitives-and-strings.md`,
  `docs/adr/README.md`), with **no new plan shape** anywhere.
- The `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` / `SKIPPED_UNSUPPORTED_PROPERTY` diagnostics stop
  firing for `Uuid`, taking the unactionable `include(kotlin)` hint with them.

### What it costs (state this in the generated XML docs)

- **Per crossing**: Kotlin → C# allocates a 36-char Kotlin `String`, converts it to a runtime-owned
  `const char*`, decodes UTF-8 into a managed `string`, and runs `Guid.Parse`. C# → Kotlin runs
  `Guid.ToString()` (one managed allocation), UTF-8-encodes it, and runs `Uuid.parse`. This is the
  same cost class as a `String`-typed property today. It is the price of zero new shapes; a
  consumer for whom `Guid` reads are hot should ask for Alternative 2.
- **Text as ABI**: the native boundary carries RFC 9562 hex-dash text between two generated
  halves. Both are produced from the same plan, so there is no independent-evolution risk, but a
  reader of the exported C signatures will see `const char*` where they might expect 16 bytes.

### What breaks

Nothing that binds today: `Uuid` is skipped everywhere. Members that were dropped appear in the
public C# surface; a surface addition.

### Deferred, with the one-line reason each

- **Binary two-`INT64` wire (Alternative 2)**: fully specified and byte-order-spiked above; adopt
  if a consumer measures `Guid` crossings as a hot path.
- **`Uuid` as a collection element / `Flow<Uuid>` type argument**: the ADR-073/075
  `isWrappableComponent()` seam, same deferral as Instant/Duration.
- **`Uuid` as an extension receiver**: `String` is a supported receiver (`ForwardPropertyPlanner
  .kt:187`), so this would likely fall out of the row copy, but no fixture proves it and no
  consumer shape asks.
- **Consumers pinning `apiVersion < 2.4`**: `@WasExperimental` means such a consumer would need
  `@OptIn(ExperimentalUuidApi::class)` in the generated exports; the plugin pins Kotlin 2.4.10 and
  no consumer has asked.

### Inferred vs Verified claims

**Verified** (output seen this session, or by the main thread as noted):
- `Uuid` is `@SinceKotlin("2.4") @WasExperimental`, a plain class; only `generateV4/V7*` and
  `LEXICAL_ORDER` still require `@ExperimentalUuidApi` (2.4.10 sources jar).
  `gradle/libs.versions.toml` pins Kotlin 2.4.10 (main thread), so no `@OptIn` is generated;
  nothing pins `apiVersion` lower (grep).
- `Uuid.toString()` is lowercase hex-dash; `Uuid.parse` accepts uppercase; `fromLongs(0,0) === NIL`
  (JVM run against the real 2.4.10 jar; `commonMain` code).
- `Guid.ToString()` is lowercase hex-dash identical to Kotlin's for the same value; `Guid.Parse`
  accepts uppercase; `Guid.Empty == default` (9.0.17 runtime, net8.0 build).
- `Nullable(String)` is a single null-pointer-sentinel slot (`ForwardCirPlanProjection.kt:587`)
  while `Primitive?`/`Instant?` use the has-value pair (`:590`, `076-instant-mapping.md:303-320`).
- String's result pointer is runtime-owned with no repo free export (source reading + grep).
- `IntegrationTests.csproj:5` targets net10.0 (main thread), so no net8.0-runtime claim is
  load-bearing for the chosen design.
- Alternative 2's byte-order mechanics and `bigEndian` overload availability on net8.0 (spike).

**Inferred** (not verified; what breaks if wrong):
1. **No konanc/KSP run.** The generated Kotlin is `x.toString()` and `Uuid.parse(s)` on a stable
   stdlib class; a compile failure would be loud, not silent. The implementer's KSP round trip
   against the `Microchip` fixture closes it. **If wrong**: a compile error in `CNameExports.kt`,
   never wrong output.
2. **`Guid.Parse` does not validate version/variant bits.** Documented behaviour; not spiked with a
   non-RFC value. **If wrong**: a Kotlin `Uuid` with unusual version bits would throw
   `FormatException` on the C# lift. Low risk; `Uuid.parse` on the Kotlin side verifiably does not
   validate them either.
3. **Kotlin/Native `@CName` cannot return a C struct by value** (Alternative 4). Load-bearing only
   for a rejected option.
4. **ObjC export surfaces `Uuid` as an opaque wrapper** (§6). Prior art only.
5. **Alternative 2's runtime behaviour identical on net8.0/net10.0** (observed on 9.0.17).
   Load-bearing only if Alternative 2 is later adopted.

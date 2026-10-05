# ADR-201: Forward, `Throwable` beyond the property getter: a constructed `System.Exception` out of Kotlin at every ordinary result, a `NugetManagedException` into Kotlin at a parameter declared `Throwable`, `Exception` or `RuntimeException`

## Status
Accepted

Extends [ADR-107](107-throwable-property-mapping.md). Supersedes its decision 4 (a
`Throwable`-taking constructor and `copy` are skipped): both bind now. Reuses
[ADR-161](161-csharp-callback-exception-into-kotlin.md)'s `NugetManagedException` as the Kotlin
value of an incoming exception, and ADR-107's error envelope as the outgoing one.

## Context

ADR-107 bound a `Throwable`, `Throwable?` or stdlib subtype only at a property getter and listed
four gaps as deferred. A Kotlin library that returns its last failure, collects failures in a
`List<Throwable>`, or takes one to report to a sink could not be called from C#:

- a method, top-level, extension, object, companion, sealed-arm, enum-member or generic-class
  return typed `Throwable` was a named `THROWABLE` skip;
- `List<Throwable>` was refused as a component;
- a parameter, `var` setter, constructor parameter or `copy` typed `Throwable` was refused, so a
  `data class Failure(val reason: String, val error: Throwable?)` was Kotlin-constructible only;
- the classifier's supertype walk was gated on `containingFile == null` (klib origin), so a
  module-local class that extends `Exception` but is not in the export set classified as
  `Unsupported` and skipped as an undeclared type.

## Alternatives Considered

### 1. Envelope out, a `NugetManagedException` in (chosen)

Out of Kotlin every position reuses the ADR-107 envelope, so there is no new export and no C#
runtime type. Into Kotlin, a C# exception crosses as one string and Kotlin rebuilds a
`NugetManagedException`, the runtime class a throwing C# callback already produces. Nothing crosses
as a handle, so there is nothing to dispose and no leak surface.

### 2. Accept only an `IKotlinException` previously received

Needs a live handle or a re-mint table, and rejects the main use, reporting a C# failure to a
Kotlin sink.

### 3. A live `KotlinThrowable` handle class (ADR-107 Alternative 5)

Preserves identity, but the value is not a `System.Exception`, needs `IDisposable` on every read,
and reverses the shipped property shape.

### 4. Re-mint the mapped rows on the way in (`ArgumentException` to `IllegalArgumentException`)

Keeps `is` checks for the ADR-029 rows but invents a second, reverse type table and still cannot
mint a user type. Possible later, additive.

### 5. Two `STRING` slots per exception (`errorType`, `errorMessage`)

The memo's first proposal. One slot is enough: the CLR full name never contains `": "`, so the text
`"{FullName}: {Message}"` splits at the first one without loss, and it is the string
`NugetManagedException` already builds its own message from. The plan needs no two-slot input.

### 6. Defer the parameter and ship the outputs only

A valid fallback if a lossy input were unwanted. Rejected: it leaves a failed-state record
constructible only from Kotlin.

## Decision

### Out of Kotlin

`BridgeType.Throwable` is plannable at every ordinary sync result position and as a collection
component on the read side. It rides the ADR-107 envelope unchanged: Kotlin returns a counted handle
to `buildError(value, ::nugetMappedType)`, and C# returns `NugetErrorNative.BuildException(handle)`,
which disposes it and returns the exception without throwing. The public type is `System.Exception`
(`System.Exception?` when nullable). Each read allocates a new exception, so it is a snapshot with
no identity, exactly as ADR-107.

Binds at: a member, top-level, class-extension, object, companion, sealed-arm, enum-member,
generic-class and interface result; a `Result<Throwable>` with its `Try` twin; a value-class
member and getter, nullable or not (nullable amended 2026-10-05, below); a `List` element
(nullable included); a `Map` value; a `List`/`Map` property getter; and the `suspend`/`Flow` results `suspend fun f():
List<Throwable>` and `Flow<List<Throwable>>`. The 2026-10-05 amendment at the end of this record adds the remaining
`suspend`, `Flow`, lambda and interface-slot positions.

### Into Kotlin

A parameter, `var` setter, constructor parameter or `copy` parameter declared exactly `Throwable`,
`Exception` or `RuntimeException` (nullable too) binds as `System.Exception` and accepts any
exception. It crosses as one string, `(ex.GetType().FullName ?? "System.Exception") + ": " +
ex.Message`, and a null rides the null string pointer. Kotlin splits the text at the first `": "`
and builds a `NugetManagedException(type, message)`. Only those three declared types are assignable
from a `RuntimeException`, so `BridgeType.Throwable` became a data class carrying the declared
qualified name, and every narrower declaration is a named `THROWABLE` skip.

**Lossy.** Kotlin receives the managed type name and message only. There is no `InnerException`
chain and no stack trace, Kotlin `is IllegalArgumentException` is false for a passed
`ArgumentException`, and a `KotlinException` that came from Kotlin and is passed back does not
round-trip as the original object: Kotlin sees a new `NugetManagedException` built from the C#
type's full name and message. Reading a stored one back out reaches C# as an `IKotlinException`
whose `KotlinType` is `NugetManagedException` and whose `Message` carries the managed type name as
a prefix (`System.InvalidOperationException: the bowl is empty`).

### Module-local subclasses

The classifier's `isThrowable()` walk no longer requires klib origin. Any class outside
`exportedObjectHandles` that inherits `kotlin.Throwable` classifies as `Throwable`, so a class in
your module that extends `Exception` but is not exported binds as `System.Exception`, mapped
through its stdlib base with its own qualified name in `KotlinType`. An exported exception class
keeps its ADR-107 decision 3 binding (open, abstract and final shapes each pinned by a Tier 1
cell). The check runs after the ADR-115 opt-in marker gate, so a marked exception still skips under
its marker. ADR-074's actual-typealias refusal is kept, with its reason reworded. Kotlin forbids a
generic `Throwable` subclass, so a generic exception class cannot reach the walk.

### Named skips

- an input declared narrower than `RuntimeException` (`IllegalStateException`, a module-local
  subclass); the hint names the three declared types that bind;
- a `Set` element or `Map` key typed `Throwable` (a fresh `System.Exception` per crossing compares
  by reference, the `ByteArray` precedent for the same two slots);
- a `Throwable` extension receiver, a value class over `Throwable`, an opt-in-marked exception, and
  an actual-typealias target.

This decision first also skipped a `List<Throwable>` input, a bare `suspend`/`Flow`/`StateFlow`
`Throwable`, a `suspend`/`Flow` parameter, a lambda payload or result and a C#-implemented interface
slot. Those bind now; see the 2026-10-05 amendment "The deferred positions bind".

### Fixed alongside

A bare `Flow<Throwable>` crashed the processor, and a bare `suspend fun f(): Throwable?` generated
`Task<Throwable?>` over `new Throwable(...)`, a C# type nothing declares. Both were first made named
skips at the legacy shape classifier, then bound by the 2026-10-05 amendment. This predates the
item and was reachable once ADR-107 added the type.

A nullable result on a value class's own method or getter (`String?`, `Int?` or `Throwable?`) crashed
the processor (`ERROR_INTERNAL_GENERATOR_FAILURE`), because the value-class member ABI has no error
slot and the nullable result arms need one. It was first made a named `SKIPPED_UNSUPPORTED_RETURN`
on both halves, with the class's other members still binding; that skip is replaced by a binding
(see the 2026-10-05 amendment below). This was general, not specific to `Throwable`; a non-null
`Throwable` result on a value class's member binds. Verified by `Tier1ValueClassNullableResultTest`.

### Diagnostics

`THROWABLE` stays a drop with no legacy route. Its hint says a C# exception reaches Kotlin as a
`NugetManagedException`, so an input binds only when declared `Throwable`, `Exception` or
`RuntimeException`, never as a receiver. The reason line names the declared
type: "its parameter `x` is declared `IllegalStateException`, which cannot hold the
`NugetManagedException` a C# exception arrives as", and for a receiver "its extension receiver is
declared `Throwable`, and a Kotlin throwable binds as a value, never as a receiver".

## Consequences

- ADR-107 decision 4 is superseded: a data class with a `Throwable` constructor parameter is
  C#-constructible, and `copy` binds. Fixtures that used `var x: Throwable?` as a refused-setter
  example now use `IllegalStateException?`.
- A stored `NugetManagedException` read back out through a return, property or list is not the
  original C# exception type, though the text is the same. **Inferred, not tested**: the envelope
  read also consults the thread-static managed-fault stash (ADR-161), so a stale stash with an
  identical type and message could be returned in place of a fresh exception. The text is the same
  either way; not solved here.
- Additive: no `nuget-runtime` change and no change to an existing export's ABI.
- The bare suspend/Flow, lambda and C#-implemented interface positions and `List<Throwable>` inputs
  were deferred here and bind now; `Set` elements and `Map` keys are permanently declined, and the
  lossy input is by design. All three are recorded in the 2026-10-05 amendment at the end.

## Evidence

**Verified (executed)**: `:nuget-processor:test` 1786 passed (`Tier1ThrowablePositionsTest`,
`Tier1ThrowablePropertyTest`), native pipeline `IntegrationTests` 3197 passed
(`ThrowablePositionsTests`, `Issue56Tests`), `LeakTests` 194. Leak rows
(`LeakTests/LiveHandleTests.cs`): `ThrowableProperty_Read_ReturnsToBaseline`,
`ThrowableReturn_NullAndNonNull_ReturnsToBaseline`, `ThrowableReturn_ThrowPath_ReturnsToBaseline`,
`ThrowableList_ReadEveryElement_ReturnsToBaseline` and
`ThrowableParameter_ReportAndSet_ReturnsToBaseline`. Fixtures:
`test-library/.../test/mishaps/MishapSample.kt` and `.../hidden/Hairball.kt`.

**Corrects the research memo**: the wire is one string, not two slots; `BridgeType.Throwable`
carries the declared type as a data class; and no `CirClassTranslator` abstract-method gate was
needed, because the existing refused-abstract filter already keeps an unbound override out of the
wrapper (verified by compiling the generated C# both ways).

## Amendment, 2026-10-05: a nullable result on a value class's own member now binds

The "Fixed alongside" skip for a nullable result on a value class's own method or getter
(`SKIPPED_UNSUPPORTED_RETURN`) is replaced by a binding. Nothing in that family stays skipped and
the planner's skip guard is removed.

**Rule.** A value class's own method or getter returns the nullable C# type, null included, for
`String`, primitives (`Boolean` too), enums, `Char`, `Duration`/`Instant`, `Uuid`, exported objects,
interfaces, `List`, `ByteArray`, `Throwable` (the ADR-107 envelope, `System.Exception?`) and value
classes over a pointer or primitive underlying. The non-null twins of these bind too.

**Mechanism.** These members keep the ADR-014 no-error-slot ABI, so a binding cannot use the
`errorOut` arms. The Kotlin half renders one shared body skeleton: `return try { ... } catch` where
a slot exists and `return run { ... }` where it does not. On the C# side `valueClassMemberExpression`
handles each nullable family, a value-type result (`Int?`, enum, `Boolean`) uses an extra `valueOut`
out slot on the `DllImport` and a `bool` result, and where the native result must be held in a
local the renderer emits a block body in place of an expression. Existing error-slot output is
unchanged.

**Defects fixed on the same path.** Non-null object, `List` and `ByteArray` results on a
value-class member generated C# that returned the raw native handle and did not compile. The Kotlin
half crashed on non-null interface, `Instant`/`Duration`, `Uuid` and value-class results.
Value-class members were not registered with the collection-helper tracker, so a `List` result
gave CS0246 on `IReadOnlyList<>`.

**Constraint kept.** A Kotlin throw from one of these members is still not caught on this route,
since there is no error slot (ADR-014).

**A generic value class.** A `T?` result on `value class Box<T>`'s own member is never planned: the
whole class is a named skip ("no value-class wire carries `T`"), so nothing reaches the renderer.
Pinned by `Tier1ValueClassNullableResultTest`, which answers the point first left open here.

**Verified (executed)**: `:nuget-processor:test` 1806 passed (`Tier1ValueClassNullableResultTest`),
native pipeline `IntegrationTests` 3216 passed (`ValueClassNullableResultTests`), `LeakTests` 197.
Leak rows (`LeakTests/LiveHandleTests.cs`): `ValueClassMemberObjectResult_NullAndNonNull_ReturnsToBaseline`,
`ValueClassMemberListResult_NullAndNonNull_ReturnsToBaseline` and
`ValueClassMemberThrowableResult_NullAndNonNull_ReturnsToBaseline`; no throw-path row, for the
constraint above. Fixture: `test-library/.../test/collartag/CollarTagSample.kt`.

## Amendment, 2026-10-05: the deferred positions bind, four shapes are declined for good, and the input is lossy by design

The positions the "Named skips" section deferred now bind with the two encodings above and no new
export. The remaining refusals are decisions, not gaps, and each names its reason.

### The deferred positions bind

Out of Kotlin a value is the ADR-107 envelope, built with `buildError(value, ::nugetMappedType)` and
read in C# with `NugetErrorNative.BuildException`, so it is an unthrown `System.Exception`. Into
Kotlin a value is the `"{FullName}: {Message}"` text and arrives as a `NugetManagedException`. A
null is the null pointer (or the null string pointer) on the same wire. Every input position binds
only when declared `Throwable`, `Exception` or `RuntimeException`.

| Position | Direction | Public C# type | How |
|---|---|---|---|
| `List`/`MutableList` element, `Map` value at an input (parameter, `MutableList` setter, `suspend` list parameter) | in | `IReadOnlyList<Exception>`, `IReadOnlyDictionary<string, Exception>` | Each element is projected to its text before boxing; Kotlin casts the box to `String` and builds the exception. |
| `suspend` or `Flow` parameter | in | `Exception`, `Exception?` | One nullable `STRING` slot, as at a sync parameter. |
| bare `suspend` result | out | `Task<Exception>`, `Task<Exception?>` | The completion reads the envelope. |
| bare `Flow` element | out | `KotlinFlow<Exception>`, `KotlinFlow<Exception?>` | Each item is boxed as its envelope. |
| `StateFlow` element on a property or method | out | `KotlinStateFlow<Exception?>` | The per-member `_value` and `_collect` exports box the envelope. |
| `Flow` acquired from a `suspend` member | out | `Task<KotlinFlow<Exception>>` | The per-member flow export, as above. |
| lambda payload; ADR-039 listener parameter | out | `Action<Exception>`; a listener's `OnMishap(Exception)` | The envelope, read with `BuildException`. The listener parameter has its own wire enum, bound with one more variant. |
| lambda result | in | `Func<Exception>` | The text over the `String` result box. |
| C#-implemented interface slot (ADR-084), parameter / result | out / in | `Exception`, `Exception?` | The envelope over the slot's `OBJECT` wire / the text over the same wire. |

Nullable forms bind where the position has a nullable wire. A nullable lambda payload does not: the
callback routes refuse every nullable payload, `Throwable` or not.

### Declined, not deferred

Each refusal is a named skip whose reason says why.

- **A `Set` element and a `Map` key, at an input or a result.** Out of Kotlin every read builds a
  fresh `System.Exception`, which compares by reference, so `Contains` and the indexer could never
  match anything the caller holds. Into Kotlin every exception becomes a fresh
  `NugetManagedException` (inferred: `Throwable` does not override `equals`, and the runtime class
  declares none), so the same C# exception passed twice is two different members. This is the
  `ByteArray` reasoning for the same two slots. Use a `List`.
- **An awaited `StateFlow<Throwable>` and any `MutableStateFlow<Throwable>`, property or method.**
  These read, and a `MutableStateFlow` writes, through the runtime's shared
  `nuget_stateflow_value`/`nuget_stateflow_collect` exports and the ADR-071 write seam. They box
  the value itself and have no per-member envelope seam, so an envelope read of that box would fail
  inside an export and abort the host. The `nuget_*` runtime ABI is frozen and gets no new export
  for one rare shape. Expose a read-only `StateFlow` property or method instead. The research memo
  named only the held method; the property and method forms had to be refused too, because a
  `Throwable` element would otherwise have been admitted for writing.
- **An input declared narrower than `RuntimeException`**, at every input position including a list
  element, a lambda result and an interface-slot result. The named skip is `THROWABLE`.
- **A C#-implemented interface whose result is declared narrower than `RuntimeException`.** No
  bridge can be planned, so a C# implementation would fail at runtime in `NugetMarshal.HandleOf`.
  This was silent; it is now `SKIPPED_UNSUPPORTED_RETURN`, naming the member and its declared type.
  Kotlin-backed implementations are unaffected.
- **Unchanged, following the general callback rules rather than a `Throwable` rule:** a
  `(Throwable?) -> Unit` payload, a lambda result narrower than `RuntimeException`, a stored
  lambda, a lambda property and a returned lambda. A `Throwable` extension receiver, a value class
  over `Throwable`, an opt-in-marked exception and an actual-typealias target also stay skipped, as
  above.

### The input is lossy by design

A C# exception reaches Kotlin as a `NugetManagedException` carrying the managed type name and
message: no `InnerException` chain, no stack trace, and a `KotlinException` passed back does not
arrive as the original object. This is the decision, not a gap. ADR-201's stated use, reporting a C#
failure to a Kotlin sink, is served. Two alternatives were priced and not built:

- **Identity round-trip** (250 to 350 lines). `NugetError` would carry its origin and a new export
  would return a second `StableRef`. A C# exception is not `IDisposable`, so a finalizer-owned
  handle per thrown exception would release it from the .NET finalizer thread. Every thrown
  `KotlinException` would then pin a Kotlin object graph until the .NET GC runs, and ADR-186's
  frozen delta for `nuget_live_handles` would stop holding on every throw path.
- **A data-only cause chain** (100 to 140 lines). Carry the `InnerException` records and a
  passed-back `KotlinType` as extra text in the same `STRING` slot. Additive, so it can be built
  later without changing anything decided here.

No ROADMAP line tracks either.

### Corrections

- `managedExceptionTextCs` documented that a passed exception which Kotlin rethrows unchanged comes
  back to C# as itself. It does not: the ADR-161 stash is set only by a throwing callback
  (`CreateManagedError`), never by the parameter path. The comment now says so.
- The earlier same-day amendment left "a `T?` result on a generic value class's own member" as not
  checked. It is never planned: `value class Box<T>` is skipped whole, pinned by
  `Tier1ValueClassNullableResultTest`.
- A nullable `String?` parameter on a C#-implemented interface slot ran `retain(null as Any)` in
  `InterfaceBridgeFactoryExports.kt`, which would throw in Kotlin before C# was called (inferred by
  reading, not reproduced). It now passes the null pointer.

### Evidence

**Verified (executed)**: `:nuget-processor:test` 1813 passed, native pipeline `IntegrationTests`
3235 passed, `LeakTests` 206. Tier 1 cells: `Tier1ThrowableRemainingPositionsTest` (a cell per code
path, including the runtime-pair `StateFlow` refusal and the narrower-slot-result refusal),
`Tier1ThrowablePositionsTest` (its `Set`/`Map`-key cell now asserts the declined-slot hint at an
input and a result) and `Tier1ValueClassNullableResultTest`. C# consumer facts: 19 in
`IntegrationTests/ThrowableRemainingPositionsTests.cs`. Leak rows (`LeakTests/LiveHandleTests.cs`):
`ThrowableListInput_ReturnsToBaseline`, `ThrowableListInput_ThrowPaths_ReturnToBaseline`,
`ThrowableBridgeSlot_AcceptAndLast_ReturnsToBaseline`, `ThrowableSuspendParameter_ReturnsToBaseline`,
`ThrowableCallbackPayload_InvokedAndThrowing_ReturnsToBaseline`,
`ThrowableListenerParameter_Announce_ReturnsToBaseline`,
`ThrowableSuspendResult_NullAndNonNull_ReturnsToBaseline` (with a tight loop for a member that never
suspends), `ThrowableFlow_CollectEveryItem_ReturnsToBaseline` (early break and throw path) and
`ThrowableStateFlow_ValueReads_ReturnsToBaseline`. Fixture:
`test-library/.../test/mishaps/MishapStream.kt`.

**Inferred**: the no-equality claim for `NugetManagedException`, and the `retain(null as Any)` throw
above.

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
non-null member and getter; a nullable result; a `List` element (nullable included); a `Map`
value; a `List`/`Map` property getter; and the `suspend`/`Flow` results `suspend fun f():
List<Throwable>` and `Flow<List<Throwable>>`.

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
- a `List<Throwable>` input and a `Throwable` extension receiver;
- a bare `suspend fun f(): Throwable?` and a bare `Flow<Throwable>`/`StateFlow<Throwable>`, a
  `suspend`/`Flow` parameter, a value class's own `Throwable?` result, a value class over
  `Throwable`, a lambda payload or result, a C#-implemented interface slot, an opt-in-marked
  exception, and an actual-typealias target.

### Fixed alongside

A bare `Flow<Throwable>` crashed the processor, and a bare `suspend fun f(): Throwable?` generated
`Task<Throwable?>` over `new Throwable(...)`, a C# type nothing declares. Both are now named skips
at the legacy shape classifier. This predates the item and was reachable once ADR-107 added the
type.

### Diagnostics

`THROWABLE` stays a drop with no legacy route. Its hint now says a C# exception reaches Kotlin as a
`NugetManagedException`, so an input binds only when declared `Throwable`, `Exception` or
`RuntimeException`, never as a receiver or inside a collection.

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
- Not bound, tracked in ROADMAP Phase 5: the bare suspend/Flow, lambda and C#-implemented interface
  positions, `List<Throwable>` inputs, `Set` elements and `Map` keys, and the lossy input.

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

# ADR-195: `Result<T>` returns gain a non-throwing `bool TryX(..., out T value, out Exception? failure)` twin

## Status
Accepted

Extends [ADR-108](108-result-return-mapping.md) (Alternative 2, "deferred, additive later").
ADR-108's throwing binding is unchanged in behaviour.

## Context

ADR-108 lowers an ordinary `fun f(): Result<T>` to `T F()` and appends `.getOrThrow()` inside the
export's `try`, so `Result.failure(e)` and `throw e` are the same bytes at the boundary and the same
`KotlinException` in C#. That was a deliberate semantic loss. The ROADMAP asked for a non-throwing
twin so a C# caller can tell a modelled failure from an unexpected exception, and listed it as a
literal `bool TryRun(out T)`.

Constraints (**verified** by reading source under
`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/`):

- The binding lives on the ADR-062 plan route: `ForwardCallablePlanner` sets `unwrapsKotlinResult`
  and `ForwardKotlinPlanEmitter` appends `.getOrThrow()`.
- Every Kotlin result body writes `errorOut` from one `catch (e: Throwable)`. Nothing on the wire
  distinguished the two failure kinds, so a Try cannot be built in C# alone. It needs one new piece
  of information across the ABI.
- The error slot must stay the last native parameter (emitter and `ForwardCallablePlanValidator`),
  so a new slot goes before it.
- Native OUT slots thread into the `DllImport` and into every body's call arguments automatically,
  with `[MarshalAs(UnmanagedType.I1)]` for a Boolean transfer.
- `NugetErrorNative.BuildException` returns the exception and disposes the error handle itself, so
  holding the exception instead of throwing it has the same handle lifecycle.
- The projection and the emitter assume one native export per plan.
- `suspend fun f(): Result<T>` is a named skip (`SKIPPED_UNSUPPORTED_RETURN`): there is no throwing
  suspend binding for a Try to sit beside.
- The change must be additive: a library that builds today still builds, with every existing C#
  member keeping its name and signature.

The C# idiom is the BCL Try pattern (`int.TryParse`): `Try` prefix, `bool` return, value through
`out`. **Inferred** from memory of the Framework Design Guidelines, not fetched.

## Alternatives Considered

### 1. One export with a `resultFailedOut` flag; C# twin `bool TryX(..., out T value, out Exception? failure)` (chosen)

The existing export gains one OUT flag. Kotlin writes `Result.isFailure` into it, then calls
`getOrThrow()` as before. C# renders a second member from the same plan that, when `error` is set,
builds the exception and either throws it (flag clear: the Kotlin body threw) or returns `false`
with it (flag set: a modelled failure).

- Pros: no second export, no per-`T` arm (the payload keeps its ADR-108 wire), ADR-028/029 mapping
  and cause chain reused, the throwing member behaves as before, and `failure` is always present.
- Cons: one extra native parameter on every `Result`-returning export; two extra `out`s for callers
  who only want the bool (`out _`).

### 2. The ROADMAP's literal `bool TryX(..., out T value)`, no exception
Discards the failure's type and message, so a caller learns that it failed and not why. For
`Result<Unit>` it renders `bool TryRun()`, which is CS0111 against an authored
`fun tryRun(): Boolean` (**verified by spike**). Rejected; this is the deliberate deviation from the
ROADMAP's wording recorded under Decision.

### 3. A second native export per Try
Doubles exports per `Result` function and breaks the one-export-per-plan assumption. Rejected.

### 4. Marker exception type or a C# `try/catch` wrapper
A marker needs a new type in the versioned `nuget_*` runtime. A wrapper throws and catches on the
expected path, the cost the Try pattern exists to avoid, and still needs the flag. Rejected.

### 5. Generated `Result<T>` struct or tuple return
Not a BCL idiom; ADR-108 already argued this. Rejected.

## Decision

### Consumer API

```kotlin
class Service {
  fun run(): Result<Unit>
  fun weigh(catName: String): Result<Int>
  fun feed(catName: String): Result<String>
  fun adopt(catName: String): Result<Cat>
}
```

```C#
public void Run();                                        // ADR-108, unchanged
public bool TryRun([NotNullWhen(false)] out Exception? failure);

public int Weigh(string catName);                         // ADR-108, unchanged
public bool TryWeigh(string catName, out int value, [NotNullWhen(false)] out Exception? failure);

public bool TryFeed(string catName, [MaybeNullWhen(false)] out string value,
                    [NotNullWhen(false)] out Exception? failure);
public bool TryAdopt(string catName, [MaybeNullWhen(false)] out Cat value,
                     [NotNullWhen(false)] out Exception? failure);
```

(Attributes are emitted as `global::System.Diagnostics.CodeAnalysis.*`.)

Rules:

- Name: `Try` plus the C# member name of the throwing binding, so `@CSharpName` carries through.
- Parameters: the throwing member's parameters (default values dropped, CS1737), then
  `out T value` (omitted for `Unit`), then `out Exception? failure`. `value` is
  `[MaybeNullWhen(false)]` when `T` renders as a non-nullable reference type or an unconstrained
  type parameter; `failure` is always `[NotNullWhen(false)]`. The twin carries no XML doc.
- `true` with `value == null` is a successful `Result.success(null)`.
- `failure` is the object the throwing member would have thrown (ADR-029 type, ADR-028 causes).
- An exception thrown by the Kotlin body, as opposed to returned in `Result.failure`, still throws
  from `TryX`. A `Result.failure` built by `runCatching` around a programming error returns `false`:
  the bridge reports what Kotlin modelled.
- An authored parameter named `value` or `failure` moves the generated out to `value_` /
  `failure_` (`freshName`, the rule `error` to `error_` already uses); the public name can not be
  `error`, which every body declares as a local (CS0136). `resultFailedOut` joins `errorOut` and
  `valueOut` among the plan-owned names, so an authored parameter spelled that way is renamed too.

### The `out Exception? failure` deviation

The ROADMAP wrote `bool TryRun(out T)`. The twin also takes `out Exception? failure` because
without it `Result<Unit>` has no legal shape (a bare `bool TryPing()` collides with an authored
`fun tryPing(): Boolean`, CS0111) and the Kotlin failure would be unrecoverable. Since every twin
ends in an `out`, none can equal the signature of an authored Kotlin function (**inferred**: no
authored callable projects a public `out` parameter; `nugetCompileInterop` would show CS0111 if
wrong).

### Mechanism

1. **Model.** New `ForwardAbiRole.RESULT_FAILED_OUT`. The validator requires one OUT pointer to a
   Boolean directly before the error slot exactly when the plan unwraps a `Result` and has an error
   slot.
2. **Planner.** `planOrSkip` inserts `resultFailedOut` (POINTER wire, Boolean transfer) before the
   error slot. A POINTER wire is what renders an OUT slot as `COpaquePointer?` in the export.
3. **Kotlin export.** The invocation suffix becomes
   `.also { nugetResult -> if (resultFailedOut != null) resultFailedOut.reinterpret<kotlinx.cinterop.BooleanVar>().pointed.value = nugetResult.isFailure }.getOrThrow()`.
   `kotlinx.cinterop.BooleanVar` is spelled in full because the invocation is spliced into a
   KotlinPoet format string. Before the call, an export carrying the flag zeroes every OUT slot it
   has: the flag, `valueOut` and `errorOut`. The thrown path never reaches the flag write, and C#
   reads the flag whenever `errorOut` is set, so it must not depend on what an unwritten out holds.
4. **C# projection.** The twin is a second `CirMethod` carried on the throwing member
   (`tryOverload`) so both share one `DllImport`. Both members of a pair get a hand-written body;
   existing throwing `Result` bodies changed text (an ignored `out bool resultFailedOut`), not
   behaviour. The twin body:

   ```C#
   IntPtr nativeResult = Native_Feed(_handle, catName, out bool resultFailedOut, out IntPtr error);
   if (error != IntPtr.Zero)
   {
       global::System.Exception exception = NugetErrorNative.BuildException(error);
       if (!resultFailedOut) throw exception;
       value = default;
       failure = exception;
       return false;
   }
   value = Marshal.PtrToStringUTF8(nativeResult)!;
   failure = null;
   return true;
   ```

### Scope

The twin binds on every owner where the throwing binding binds: class, object, companion,
top-level and extension members, sealed bases and arms, and these three, which the research draft
had excluded:

- **Interfaces.** The C# interface declares `TryX` as a default interface method whose body calls
  the throwing member, so it is reachable through the interface type and a C# implementer does not
  have to write it. A Kotlin class implementing the interface member keeps its own twin.
- **Abstract members.** An abstract `fun f(): Result<T>` has no plan; the base declares an abstract
  `TryF` beside the abstract `F`, and the override's twin is `override`. The nested `Backing`
  wrapper of an abstract class or abstract sealed arm
  ([ADR-009](009-sealed-class-mapping.md)'s 2026-10-04 amendment) builds its overrides after the
  collision pass below, so it overrides a twin exactly when the owner still declares it.
- **Overrides.** The twin copies `override`, `virtual`, `abstract` and `static` from the throwing
  member at render time.

Left out, each with a compiler reason or no throwing binding to sit beside:

- A `new` (covariant) sealed arm member: its twin would be an overload, not a hide, and `new` on it
  is CS0109. The arm carries no twin and inherits the base's, which reaches the same Kotlin override
  through the handle.
- An interface twin whose `out` value mentions a variant type parameter (`I<out T>` with
  `fun next(): Result<T>`): an `out T` parameter is invariant in C# (CS1961). The interface still
  declares the twins whose payload does not mention `T`, and implementing classes keep theirs.
  Neither this nor the `new` arm reports a diagnostic: the twin is not a Kotlin declaration, and
  its throwing member still binds.
- `suspend fun f(): Result<T>`: no throwing binding today, and `out` on an async method is CS1988.
- Value-class-own members and a `Result<T>` whose `T` has no return shape: ADR-108 skips them.

### Declined positions

Recorded behaviour, not work. `Result<T>` at a property, parameter or collection-component position,
and `Flow<Result<T>>`, stay named skips: C# has no value to pass for a failure arm, and an element
that can fail independently needs the generated struct ADR-108 rejected.

### Name collisions

`SKIPPED_RESULT_TRY_COLLISION` is a WARNING. The twin is dropped, and the throwing `X` stays, when:

- `TryX` is already a property, constant or nested type of the type that would declare it (CS0102),
  or the type's own name (CS0542);
- it would hide an inherited member: a base property, constant or nested type, a same-signature
  method, or a base twin (CS0108);
- it shares a name with an extension property rendered for the same receiver, which compiles but
  makes every read of that property ambiguous (CS9339);
- another twin in its override chain is dropped. The decision is per chain: a derived `override`
  twin whose base twin is gone has nothing to override (CS0115), and an abstract base twin whose
  override is gone leaves the concrete class unimplemented (CS0534). Every twin in the chain is
  dropped, one warning each.

An enum member property renders as an extension method, so a twin of the same name stays as an
overload and no warning is raised.

**Known limit**: a base the generated file does not declare (a dependency type) cannot be read, so
a twin over it is kept rather than guessed at.

This is a post-pass over the assembled file (`CirResultTryCollisions.kt`). ADR-110's fatal rule does
not apply: the guard grounds that rule in ADR-055 (every `@CName` export needs its P/Invoke), and
here the export keeps its throwing P/Invoke, so no export is left unimported. Fatal would also
break additivity. Warning rather than info because a convenience member the author may expect is
missing.

### Pre-existing bug fixed alongside

An abstract `fun f(): Result<T>` rendered `public abstract global::Interop.Result F()`, a C# type
nothing declares (CS0246), and the override's `T` return did not match it. The abstract member now
declares `T`.

## Consequences

- Additive: new C# members only. Every `Result`-returning export gains one native parameter, which
  is regenerated with its `DllImport` and is not part of the frozen `nuget_*` runtime ABI.
- One new diagnostic code, `SKIPPED_RESULT_TRY_COLLISION`.
- Documented in `docs/topics/exceptions.md`; the mapping row in `docs/topics/supported-features.md`
  and the leak-harness list in `docs/topics/registration-diagnostics.md` name the new rows.

## Evidence

**Verified (executed)**: `:nuget-processor:test` 1644 passed, 0 failed, including Tier 1 cells that
run a real `dotnet build` with warnings as errors over every owner, the collision cell, and each
inheritance, override-chain, extension-property and variance cell; the generated fixture
`Interop.cs` is byte-identical after the collision follow-up, so the native pipeline figures stand:
IntegrationTests 3044, LeakTests 166 and 7 AOT shapes;
`TryWeigh_Ghost_ThrownException_StillThrows` (a body that throws still throws from the Try);
LeakTests `ResultTry_ModelledFailureAndSuccess_ReturnToBaseline` and
`ResultTry_ThrownException_ReturnsToBaseline`.

**Verified by spike** (research session, .NET SDK 10.0.301, net10.0, Nullable enable, warnings as
errors): the signatures and body compile with consumer flow analysis intact, and CS0136, CS0100,
CS0102, CS0111 and CS1988 occur where stated.

**Not measured**: what C# would read from the one-byte `out bool` if Kotlin never wrote it. The
design does not depend on it because of the zeroing in Mechanism step 3.

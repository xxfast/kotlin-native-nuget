# ADR-241: Forward, an exported Kotlin exception class binds as a C# exception type: the handle class IS the exception, classified with `is` on the Kotlin side

## Status
Proposed

Amends [ADR-107](107-throwable-property-mapping.md) decision 3 (an exported exception class stays a
plain handle class) and [ADR-201](201-throwable-beyond-the-property-getter.md) ("An exported
exception class keeps its ADR-107 decision 3 binding"). Extends
[ADR-177](177-exception-mapping-by-class-hierarchy.md) and [ADR-202](202-runtime-route-exception-mapping.md)
(the classifier gains per-module arms) and narrows [ADR-203](203-reverse-envelope-shared-exception-mapper.md)
decision 2 (the reverse envelope classifies with a row-only twin). Issue #612.

## Context

Today every non-stdlib Kotlin exception reaches C# as a bare `KotlinException`
(`Kotlin.Native.Interop/KotlinException.cs:57`, the `_` arm of `CreateMapped`, verified by
reading). A consumer of a library that declares

```kotlin
sealed class StoreException(message: String) : Exception(message) {
  class Unauthorized : StoreException("not signed in")
  data class NotFound(val key: String) : StoreException("no $key")
}
```

has to write `catch (KotlinException e) when (e.KotlinType == "com.example.StoreException.Unauthorized")`.
The issue asks for a generated C# exception type per exported `Throwable` subclass, mirroring the
Kotlin hierarchy, on every error-building route, with an unexported subclass caught by its nearest
exported ancestor, and with `KotlinType`, `KotlinStackTrace` and the cause chain unchanged.

Constraints read in source:

1. **The same class already binds as a handle class.** An exported `class LookupError : Exception()`
   is an ordinary `ObjectHandle` (ADR-107 decision 3; pinned for open, abstract and final shapes by
   `Tier1ThrowablePositionsTest.kt:32-59`, verified by reading). Its C# wrapper is rendered by
   `CirClassRenderer.renderClass` with no base class at the root and `: IDisposable, INugetHandle`
   (`CirClassRenderer.kt:252-258`, `:277-285`, verified by reading). A second C# type of the same
   name cannot exist in the same namespace.
2. **The runtime cannot name library types.** `buildError` (`NugetRuntime.kt:635-656`) is in
   `nuget-runtime`; the suspend-lambda and `StateFlow` routes call it inside the runtime. ADR-202
   solved the same problem for optional rows: every generated site passes `::nugetMappedType`, and
   the module installs that same function into its own statically linked runtime copy at every mint
   site of a runtime-owned route (`NugetRuntime.kt:715-737`, `ExceptionMappingExports.kt:20-31`,
   verified by reading). So **whatever `nugetMappedType` returns is what every forward route
   classifies with**, runtime-side sites included (verified by reading the ~30 emitters:
   `grep -rn "::nugetMappedType" nuget-processor/src/main`).
3. **Every C# error route goes through one per-library function.** `NugetErrorNative.BuildException`
   (`CirErrorRenderer.kt:197-238`) is the only forward caller of `KotlinException.CreateMapped`; sync,
   suspend, Flow, Throwable-valued results and erased positions all call it (verified by grep:
   `CirTranslator.kt:1903-2024`, `CirClassTranslator.kt:2128-6231`, `CirFlowRenderer.kt:222,924`,
   `CirMarshalRenderer.kt:351`, `CirBridgeRenderer.kt:214,349`). The only other `CreateMapped` caller
   is the reverse shim's `NugetKotlinErrors.Build` (`NugetGenerateShimsTask.kt:3106`), fed by the
   plugin-generated `nugetKotlinError` which also passes `::nugetMappedType`
   (`NugetGenerateBindingsTask.kt:5263-5275`, verified by reading).
4. **`KotlinException`'s only constructor is `internal`** (`KotlinException.cs:16`), and the eleven
   mapped types are `public sealed` (e.g. `KotlinInvalidOperationException.cs:3`). A generated
   type in a consumer package cannot derive from either today. **Verified (spike S1):** a class in a
   second assembly deriving from a class whose only ctor is `internal` fails with `CS1729:
   'KotlinException' does not contain a constructor that takes 2 arguments`.
5. **ADR-177 already maps an exported subclass of a row.** `class FinalError : IllegalStateException`
   thrown today is a `KotlinInvalidOperationException` (row matched with `is`, ADR-177 S2:
   `myIse ... isISE=true`). A generated type that derives from `KotlinException` alone would stop
   `catch (InvalidOperationException)` from firing for it: a silent regression.
6. **Single class inheritance.** Any two exported `Throwable` classes are either in an
   ancestor/descendant relation or unrelated, so an `is` chain ordered by inheritance depth,
   deepest first, always yields the nearest exported ancestor (Kotlin language rule, inferred by
   reasoning, not spiked). The nearest stdlib row of any subclass of exported `E` is `E`'s own
   nearest row, because no stdlib or klib class can extend a module class; so `E`'s C# base can be
   chosen statically.

## Alternatives Considered

### 1. Unify: the handle class IS the exception type; the classifier key folds exported arms in front of the rows (chosen)

The exported class keeps its single C# type, its members and its handle; its root gains an exception
base. The per-module `nugetMappedType` tests exported classes first (deepest first), then the
optional rows, then the stdlib rows, and returns the matched class's Kotlin FQN. The per-library
`BuildException` switches on that key for this library's exported classes and constructs the
wrapper over a handle minted from the envelope; anything else falls through to `CreateMapped`
unchanged.

Pros: one C# type per Kotlin class, the shape ObjC export and Kotlin/JS already use (below); no name
collision; members (`NotFound.Key`) bind on a thrown instance because it holds a live handle, so the
issue's out-of-scope properties come for free rather than needing a second design; no runtime
signature change, no new classifier parameter at ~30 sites, the runtime-side routes covered by the
ADR-202 install that already exists. Cons: a thrown exception now owns a `NugetKotlinHandle`
(released by the SafeHandle finalizer when the consumer never disposes it, a new leak row);
`mappedType` stops meaning "a table row" on the forward wire; the shared contract must open
`KotlinException` and the mapped types to subclassing.

### 2. Emit a second, data-only exception type beside the handle class

`LookupError` stays the handle class and a new `LookupErrorException : KotlinException` is thrown.
Pros: no change to the handle class; no handle on a thrown instance. Cons: two C# types for one
Kotlin class, one of them with an invented name (or a nested `LookupError.Exception`); a value read
(`fun last(): LookupError`) and a thrown one are unrelated types, so `catch` and `is` disagree; adding
`NotFound.Key` later means either duplicating every member onto the second type or giving it a
handle anyway, at which point it is Alternative 1 with two types. Rejected.

### 3. Replace: exported exception classes stop being handle classes

They bind only as envelope snapshots (ADR-201's `System.Exception` route, typed). Pros: no handle
on a thrown instance. Cons: drops every member and every input position (a parameter typed
`LookupError` loses identity, ADR-201 skips inputs narrower than `RuntimeException`); breaking for
the shapes ADR-107 decision 3 pinned; properties later would need the handle reintroduced. Rejected.

### 4. A separate `exportedType` wire field beside `mappedType`

`NugetError.exportedType`, a new `nuget_error_cause_exported_type` export, and a second required
classifier through `buildError`, `launchForCSharp`, `collectForCSharp` and `nugetInstallMappedType`.
Pros: `mappedType` keeps meaning "a row"; the reverse envelope could carry both. Cons: changes the
runtime's public Kotlin signatures and every emitter (~15 files, ~30 sites) for information
constraint 6 says is statically derivable. A valid fallback if the human prefers the wire to stay
row-only (open question Q3).

### 5. An integer type id instead of the FQN key

The sealed-interface discriminator already does this (`Native_GetType(handle) switch { 0 => new
Arm(...) }`, `CirClassRenderer.kt:106-127`, verified by reading). Equivalent at runtime; rejected
for the same reason ADR-177 Alternative 5 was: one string keyspace across rows and classes, readable
in `NUGET_INTEROP_TRACE` output.

### 6. Exact-name switch on `KotlinType`, or a constants class of names

Forbidden by the issue; fails requirement 3 (an unexported subclass has its own `KotlinType`).
Rejected.

## Decision

Alternative 1.

### Consumer API

```csharp
try
{
    store.Fetch("oreo");
}
catch (StoreException.NotFound e)          // most derived first; reversed order is CS0160
{
    Console.WriteLine(e.Key);              // members bind: the instance holds a live handle
}
catch (StoreException e)                   // every other arm, and any unexported subclass
{
    Console.WriteLine(e.KotlinType);       // concrete Kotlin FQN, unchanged
}
```

Generated (shape; names illustrative):

```csharp
public abstract class StoreException : global::Kotlin.Native.Interop.KotlinException, IDisposable, INugetHandle
{
    internal NugetKotlinHandle _handle = NugetKotlinHandle.Null;
    IntPtr INugetHandle.Handle => _handle.DangerousGetHandle();

    // value positions (`fun last(): StoreException`): read the envelope from the borrowed handle
    internal StoreException(NugetKotlinHandle handle, out NugetHandleTag tag)
        : this(handle, NugetErrorNative.Snapshot(handle), out tag) { }

    // BuildException: the snapshot it already read
    internal StoreException(NugetKotlinHandle handle, NugetErrorSnapshot s, out NugetHandleTag tag)
        : base(s.KotlinType, s.Message, s.KotlinStackTrace, s.Inner)
    { tag = default; _handle = handle; }

    public sealed class Unauthorized : StoreException { /* handle ctors chain to base */ }
    public sealed class NotFound : StoreException { public string Key { get; } /* existing property render */ }
}
```

**Verified (spike S2, .NET 10.0.14):** a second-assembly class deriving from a contract
`KotlinException` through a `protected` ctor, chaining through a private ctor that takes a computed
snapshot, implementing `IDisposable`, with nested sealed subclasses, is caught by both
`catch (StoreException.Unauthorized)` and `catch (StoreException)`, and a class deriving from an
unsealed `KotlinInvalidOperationException` is caught by `catch (InvalidOperationException)`:

```
caught Unauthorized com.example.StoreException.Unauthorized msg Unauthorized
caught base for StoreException+NotFound isKotlinException=True
caught IOE tier1.FinalError
```

### C# base of a root exported exception

- No mapping row matches the class (it extends `Exception`, `RuntimeException`, `Throwable` or an
  unmapped klib exception): `KotlinException`.
- It IS-A a row (`class FinalError : IllegalStateException`): that row's `Kotlin*` type
  (`KotlinInvalidOperationException`), so ADR-177's `catch (InvalidOperationException)` keeps firing
  (constraint 5). Chosen statically by the processor from KSP supertypes and `KOTLIN_EXCEPTION_TYPES`
  order (constraint 6). This deviates from the issue's literal "deriving from `KotlinException`"; see
  open question Q1.

An exported class whose superclass is itself exported derives from that class's C# type, as today
(`CirClassRenderer.kt:252-254`).

### Kotlin side (generated, per module)

```kotlin
internal fun nugetMappedType(t: Throwable): String? = when {
  t is com.example.StoreException.NotFound -> "com.example.StoreException.NotFound"       // depth 2
  t is com.example.StoreException.Unauthorized -> "com.example.StoreException.Unauthorized"
  t is com.example.StoreException -> "com.example.StoreException"                         // depth 1
  t is kotlinx.io.IOException -> "kotlinx.io.IOException"                                 // optional rows
  else -> nugetStdlibMappedType(t)
}

/** ADR-241: the ADR-177/203 row-only classifier, for the reverse envelope. */
internal fun nugetRowType(t: Throwable): String? = /* the body nugetMappedType has today */
```

The keys are emitted by the processor from KSP qualified names, and the C# switch arms are emitted
by the same run, so they cannot disagree (verified by construction: one generator writes both). No
runtime `qualifiedName` is read for the key. `KotlinType` stays `t::class.qualifiedName`
(`NugetRuntime.kt:639`), unchanged. Every existing `::nugetMappedType` site, and through the ADR-202
install every runtime-owned forward route, classifies exported classes with no further change
(constraint 2, verified by reading). The reverse envelope (`NugetGenerateBindingsTask.kt:5263-5275`)
switches to `::nugetRowType`, because its C# half calls `CreateMapped` directly, which does not know
library keys and would silently drop the row (constraint 3).

### Kotlin side (runtime)

`NugetError` keeps the node's `Throwable` (a non-constructor field so the data-class equality of the
existing fields is unchanged), and one export mints a counted handle to it on demand:

```kotlin
@NugetRuntimeApi
@CName("nuget_error_cause_throwable")   // index 0 is the top node; caller owns the handle
public fun export_nuget_error_cause_throwable(handle: COpaquePointer, index: Int): COpaquePointer =
  NugetHandles.retain(handle.asStableRef<NugetError>().get().at(index).throwable)
```

Added to `NUGET_RUNTIME_EXPORTS` (`ForwardAbiContract.kt:648`). Additive, same reasoning as
`nuget_error_cause_mapped_type` (`NugetRuntime.kt:862-874`). C# calls it only inside an exported arm,
so a stdlib or unexported-only exception mints no handle.

### C# side (per library)

`BuildException` (and the cause loop) becomes:

```csharp
Exception built = MapExported(errorPtr, 0, kotlinType, mappedType, msg, stackTrace, inner)
    ?? global::Kotlin.Native.Interop.KotlinException.CreateMapped(kotlinType, mappedType, msg, stackTrace, inner);

private static Exception? MapExported(IntPtr errorPtr, int index, string kotlinType, string key,
    string msg, string stackTrace, Exception? inner) => key switch
{
    "com.example.StoreException.NotFound" => new StoreException.NotFound(
        new NugetKotlinHandle(CauseThrowable(errorPtr, index)), new(kotlinType, msg, stackTrace, inner), out _),
    // ... one arm per exported Throwable class of THIS library: a dispatch on the key the Kotlin
    // side classified with `is`, not a classification by name
    _ => null,
};
```

The handle is minted before `NugetMarshal.Dispose(errorPtr)` releases the envelope. An abstract
exported class (or a sealed base) has no arm: Kotlin cannot throw an instance whose nearest exported
class is abstract unless an unexported concrete subclass exists, so an abstract arm constructs the
generated private concrete backing the class already has for value positions (inferred: ADR-216's
`backingName` covers abstract classes; the implementing agent must confirm the abstract and sealed
renderers both provide one).

### Members on exception types

- Members bind exactly as on any handle class, through the existing renderers.
- A member overriding a `Throwable` member (`message`, `cause`) is not rendered: `Exception.Message`
  and `InnerException` already carry it.
- A bound member whose C# name collides with an `Exception` member (`Data`, `Source`, `HelpLink`,
  `HResult`, `StackTrace`, `TargetSite`) is a named skip in v1 (open question Q4).
- A data-class exception keeps `Equals`/`GetHashCode` but not the `ToString` override
  (`CirClassRenderer.kt:921`): `KotlinException.ToString` carries the Kotlin trace.

### Shared contract (`Kotlin.Native.Interop`)

`KotlinException` gains a `protected` ctor `(string kotlinType, string message, string
kotlinStackTrace, Exception? innerException)`. Each mapped `Kotlin*` type is unsealed and gains the
same `protected` ctor. Additive to the public surface (unsealing a class is not a breaking change to
callers, inferred from .NET's breaking-change rules); `ContractTests` grows a subclassing check.

### Inferred claims, not verified

- Prior art (from the Kotlin docs, not re-fetched this session): ObjC export exports a Kotlin
  exception class as an ordinary class under `KotlinThrowable`, and a thrown one reaches Swift as an
  `NSError` whose `kotlinException` is that same object, one type per class
  (<https://kotlinlang.org/docs/native-objc-interop.html#errors-and-exceptions>). Kotlin/JS maps
  `Throwable` onto JS `Error`, so an exported subclass is one class extending `Error`. JVM: the same
  class.
- An exception holding a `NugetKotlinHandle` that the consumer never disposes releases its handle on
  the SafeHandle finalizer (inferred from `NugetKotlinHandle : SafeHandle`, `CirRenderer.kt:79`, and
  SafeHandle semantics). If wrong, every thrown exported exception leaks one Kotlin object: the leak
  row below catches it.
- The `string` switch plus `new` in `MapExported` is NativeAOT-safe (no reflection; same shape as
  `CreateMapped`, which the AOT suites already exercise).
- `t::class.qualifiedName` of a nested class is dot-separated (`StoreException.Unauthorized`); ADR-177
  S2 verified top-level classes only. Only `KotlinType` depends on it; the key does not.
- Depth ordering yields the nearest exported ancestor (constraint 6, reasoning only).
- Kotlin/Native compiles `is` against a module's own exported nested class in the generated
  `CNameExports.kt` (no K/N spike run this session; it is the same `is` ADR-177 generates against
  klib classes).

## Consequences

- **Breaking for consumers.** An exported exception class's C# type gains an exception base; a
  consumer that caught one as `KotlinException` still does (no-row case), but its `Message`,
  `InnerException` and `ToString` change meaning on a value read, and a data-class exception's
  `ToString` stops being the Kotlin `toString()`. A thrown exported exception is no longer a bare
  `KotlinException` instance.
- One runtime export, one per-module function (`nugetRowType`), one contract ctor per exception
  class, and a new leak surface (thrown exceptions own a handle).
- `ForwardKdoc`'s ADR-150 `@throws T` cref names the generated type when `T` is exported.
- **Deferred**, each a ROADMAP line: the reverse envelope mapping to generated types (Kotlin slot
  throwing an exported exception into C#); C#-side construction and `throw` of a generated type into
  Kotlin (today a `Throwable` parameter receives a `NugetManagedException`, ADR-201); exceptions
  exported by a dependency library mapped in the consuming library; renaming instead of skipping
  `Exception`-member collisions.

### Spikes

S1 (scratch `dotnet build`, .NET SDK with runtime 10.0.14): contract class with only an `internal`
ctor, subclass in a referencing assembly:

```
P.cs(2,75): error CS1729: 'KotlinException' does not contain a constructor that takes 2 arguments
```

S2: the program in "Consumer API", output quoted there.

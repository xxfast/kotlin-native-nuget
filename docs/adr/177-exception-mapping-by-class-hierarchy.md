# ADR-177: Exception mapping by class hierarchy: classify with `is` on the Kotlin side, send the matched row across

## Status

Accepted

## Context

[ADR-029](029-exception-type-mapping.md) maps eight Kotlin stdlib exceptions to .NET analogs. The
map is an exact-string `switch` on the thrown class's fully qualified name, rendered into the
generated C# `BuildMapped` (`CirErrorRenderer.kt:147-155`, table at `CirErrorRenderer.kt:9-18`,
verified by reading). Issue #349 reports the two consequences:

- A `kotlinx.io.IOException`, or any subclass such as ktor's `ConnectTimeoutException`, thrown from a
  suspend function arrives as a bare `KotlinException`; `catch (IOException)` never fires.
- A Kotlin subclass of a mapped type (`class KennelFullException : IllegalStateException`) also falls
  through to `KotlinException`, so `catch (InvalidOperationException)` misses it.

ROADMAP.md:67 adds: `kotlin.NullPointerException` and `NoWhenBranchMatchedException` have no row, and
a Kotlin exception with a null message reads `Kotlin error` (`NugetRuntime.kt:530`), hiding the type.

Constraints found while researching (details and spike output below):

1. The Kotlin side is the only side that knows the hierarchy. It sends one string per node, the
   concrete FQN (`NugetRuntime.kt:529`), and Kotlin/Native has no `KClass.supertypes` to send a
   chain (inferred from the `KClass` API reference, where `supertypes` is JVM-only).
2. `buildError` runs in three places: ~30 generated forward call sites, **inside the runtime** on the
   suspend and Flow routes (`NugetLaunch.kt:47`, `:83`), which is the issue's own case, and the
   plugin-generated reverse envelope (`NugetGenerateBindingsTask.kt:5147-5148`). Verified by reading.
3. `nuget-runtime` depends only on kotlinx-coroutines (`nuget-runtime/build.gradle.kts:18-22`); it
   cannot name `kotlinx.io.IOException`. The Kotlin/Native stdlib has no `IOException` at all
   (verified, spike S1).
4. Two stdlib hierarchy facts make "first `is` match wins" order-sensitive (verified, spikes S1/S2):
   `NumberFormatException : IllegalArgumentException`, and
   `kotlin.coroutines.cancellation.CancellationException : IllegalStateException`.
5. `kotlin.NoWhenBranchMatchedException` is an **`internal`** class in the Kotlin/Native 2.4.10
   stdlib (verified, spike S1 and S3); no code outside the stdlib can write `is` against it.

The reverse direction already solved the mirror problem: a managed exception is classified on the
throwing side with `ex is OperationCanceledException ? 1 : 0` and the kind crosses beside the type
name (ADR-153/161, `CirErrorRenderer.kt:92`).

## Alternatives Considered

### 1. Classify with `is` on the Kotlin side; runtime owns stdlib rows, the processor adds optional-dependency rows (chosen)

`NugetError` gains `mappedType: String?`, the Kotlin FQN of the first table row the throwable IS-A,
per node. `buildError(e, mappedType: (Throwable) -> String?)` takes the classifier as a required
parameter. The runtime ships `nugetStdlibMappedType` (stdlib rows, most specific first). The
processor emits one per-module `nugetMappedType` that adds `kotlinx.io.IOException` when KSP resolves
that class on the compile classpath, and passes it at every generated site and into
`launchForCSharp`/`collectForCSharp`. A new export `nuget_error_cause_mapped_type` carries it; C#
switches on it.

Pros: subclasses map; no new runtime dependency; the classpath question is answered where the
classpath is known (the consumer's KSP run); more optional bases (Okio, ktor 2) are one row each;
a required parameter turns every missed call site into a compile error instead of a silent
stdlib-only fallback. Cons: touches every `buildError` emitter; the stdlib rows live in the runtime
and the C# rows in the processor, so a parity test is needed; the runtime-owned routes and the reverse envelope got stdlib rows only (the forward
runtime-owned routes: fixed by ADR-202).

### 2. `nuget-runtime` depends on kotlinx-io-core and owns every row

Same wire, but `buildError(e)` keeps its signature and classifies internally, including
`is kotlinx.io.IOException`.

Pros: one table on the Kotlin side, zero generated-site churn, reverse envelope gets IO for free.
Cons: every consumer's native library now links kotlinx-io; Gradle's highest-wins resolution can
silently upgrade a consumer's own kotlinx-io to the runtime's pin (inferred, the same behaviour
ADR-127 notes for coroutines); Okio's and ktor 2's `IOException` would each need yet another runtime
dependency. Viable fallback if the human prefers simplicity (see Open questions in the memo).

### 3. Keep exact names; add rows for `kotlinx.io.IOException` and known subclasses

Pros: C#-only change. Cons: a user or library subclass of any row still falls through; the issue
explicitly asks for hierarchy matching. Rejected.

### 4. Send the concrete type's supertype chain; match in C#

Cons: not implementable, Kotlin/Native exposes no supertypes at runtime (inferred, constraint 1).
Rejected.

### 5. Send an integer kind instead of a string

Cons: a shared enum across runtime, processor and plugin shim, opaque in a debugger. The string keeps
the C# switch keyed by the ADR-029/150 table. Rejected.

## Decision

Alternative 1.

### Kotlin side (runtime)

```kotlin
@NugetRuntimeApi
public data class NugetError(
  public val type: String,          // concrete FQN, unchanged: KotlinType stays e.g. io.ktor...ConnectTimeoutException
  public val mappedType: String?,   // NEW: the matched row's Kotlin FQN, or null
  public val message: String,
  public val stackTrace: String,
  public val cause: NugetError? = null,
)

// Required classifier: no default, so an unconverted call site does not compile.
@NugetRuntimeApi
public fun buildError(e: Throwable, mappedType: (Throwable) -> String?): NugetError
// message = t.message ?: <concrete FQN>   (was "Kotlin error")

/** ADR-177: stdlib rows, most specific first. Order is load-bearing (NFE < IAE, CE < ISE). */
@NugetRuntimeApi
public fun nugetStdlibMappedType(t: Throwable): String? = when {
  t is NumberFormatException -> "kotlin.NumberFormatException"
  t is IllegalArgumentException -> "kotlin.IllegalArgumentException"
  t is CancellationException -> "kotlin.coroutines.cancellation.CancellationException"
  t is IllegalStateException -> "kotlin.IllegalStateException"
  t is NoSuchElementException -> "kotlin.NoSuchElementException"
  t is ConcurrentModificationException -> "kotlin.ConcurrentModificationException"
  t is UnsupportedOperationException -> "kotlin.UnsupportedOperationException"
  t is ClassCastException -> "kotlin.ClassCastException"
  t is ArithmeticException -> "kotlin.ArithmeticException"
  t is NullPointerException -> "kotlin.NullPointerException"
  // internal in the stdlib: matched by name, exact class only
  t::class.qualifiedName == "kotlin.NoWhenBranchMatchedException" -> "kotlin.NoWhenBranchMatchedException"
  else -> null
}

@NugetRuntimeApi
@CName("nuget_error_cause_mapped_type")   // NEW export; "" for null; index 0 is the top node
public fun export_nuget_error_cause_mapped_type(handle: COpaquePointer, index: Int): String
```

`launchForCSharp` and `collectForCSharp` gain a `mappedType: (Throwable) -> String?` parameter and
pass it to `buildError`. Their `CancellationException` arms are unchanged, so a suspend or Flow
cancellation still reports `cancelled`, never an error (verified by reading `NugetLaunch.kt:43,79`).

### Kotlin side (generated, per module)

```kotlin
// emitted when resolver.getClassDeclarationByName("kotlinx.io.IOException") != null
internal fun nugetMappedType(t: Throwable): String? =
  if (t is kotlinx.io.IOException) "kotlinx.io.IOException" else nugetStdlibMappedType(t)

// emitted otherwise
internal fun nugetMappedType(t: Throwable): String? = nugetStdlibMappedType(t)
```

Every `buildError(e)` becomes `buildError(e, ::nugetMappedType)`. The IO row goes first: it is
disjoint from every stdlib row (`kotlinx.io.IOException : kotlin.Exception`, verified, spike S1).
Verified (spike S3): KSP 2.3.10 on `kspKotlinMingwX64` resolves `kotlinx.io.IOException` from a
kotlinx-io klib dependency with `origin=KOTLIN_LIB`, and returns `null` for a class not on the
classpath. Inferred: the class KSP resolves is exactly the class the generated `is` compiles
against, because both read the same target compile classpath; a dependency visible to neither
produces the stdlib-only classifier, which is correct for a library that cannot throw it.

### C# side

`BuildException` reads `nuget_error_cause_mapped_type` for each node (index 0 for the top) and
`BuildMapped(kotlinType, mappedType, message, stackTrace, inner)` switches on `mappedType`:

```csharp
private static Exception BuildMapped(string kotlinType, string mappedType, string message,
    string stackTrace, Exception? inner) =>
    mappedType switch
    {
        "kotlinx.io.IOException" => new KotlinIOException(kotlinType, message, stackTrace, inner),
        "kotlin.NumberFormatException" => new KotlinFormatException(kotlinType, message, stackTrace, inner),
        "kotlin.IllegalArgumentException" => new KotlinArgumentException(kotlinType, message, stackTrace, inner),
        "kotlin.coroutines.cancellation.CancellationException" =>
            new KotlinOperationCanceledException(kotlinType, message, stackTrace, inner),
        "kotlin.IllegalStateException" => new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),
        // ... NoSuchElement, ConcurrentModification -> KotlinInvalidOperationException (unchanged)
        // ... UnsupportedOperation, ClassCast, Arithmetic (unchanged)
        "kotlin.NullPointerException" => new KotlinNullReferenceException(kotlinType, message, stackTrace, inner),
        "kotlin.NoWhenBranchMatchedException" => new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),
        _ => new KotlinException(kotlinType, message, stackTrace, inner)
    };

public sealed class KotlinIOException : System.IO.IOException, IKotlinException { /* same shape as the others */ }
public sealed class KotlinNullReferenceException : NullReferenceException, IKotlinException { }
public sealed class KotlinOperationCanceledException : OperationCanceledException, IKotlinException { }
```

The three new classes are emitted unconditionally, like the existing six, so the public surface does
not depend on the classpath and ADR-150 crefs can always name them. Verified (spike S4, .NET SDK
10.0.301): `IOException`, `NullReferenceException` and `OperationCanceledException` are unsealed with
a `(string, Exception)` constructor; `SwitchExpressionException`, C#'s own non-exhaustive-switch
failure, derives from `InvalidOperationException`, the precedent for the `NoWhenBranchMatched` row.
Inferred: the net8.0 BCL has the same shapes.

`KotlinType` keeps naming the concrete class (verified, spike S2: a user subclass reports its own
FQN). `ToString()` on every Kotlin exception class adds a `Kotlin type: <fqn>` line. `Message` stays
the Kotlin message verbatim; only a null Kotlin message becomes the Kotlin FQN.

`KOTLIN_EXCEPTION_TYPES` stays the one processor table (C# switch and ADR-150 crefs); each row gains
how it matches (`IS` or `NAME`) and whether it needs a class on the classpath. A Tier 1 test pins
that the runtime's stdlib row keys equal the processor's non-optional keys, the same honesty device
as `NUGET_RUNTIME_EXPORTS`. The ADR-150 `@throws T` cref walks `T`'s KSP supertypes to the first row
`T` IS-A, so the documented exception stays the caught one.

### Reverse envelope

As shipped, the plugin-generated `nugetKotlinError(t)` passes `::nugetStdlibMappedType`, so the
envelope's `NugetError.mappedType` is populated, but the shim's `NugetKotlinErrors.Map` is
**unchanged**: no `nuget_kotlin_error_cause_mapped_type` export was added, and `Map` still switches
on the concrete `kotlinType` with its original exact-name table. A reverse-side subclass of a
mapped type, `NullPointerException`, and the IO family therefore still arrive as `KotlinException`
there. What the reverse envelope does inherit is the null-message rule: a Kotlin exception with no
message now reads its Kotlin type name, not `Kotlin error`. The original design (a mapped-type
export feeding `Map`) is deferred with the shared `BuildMapped` (ROADMAP.md).

### Scope of the IO row

The `kotlinx.io.IOException` row exists only where the module's own classifier does, that is on the
generated forward call sites, including the forward suspend and Flow routes. As shipped, routes the
runtime owns passed `::nugetStdlibMappedType` and got stdlib rows only, because the runtime cannot
see the module's KSP lookup: `nuget_suspend_func{0..3}_invoke` (a Kotlin suspend lambda invoked
from C#), `nuget_stateflow_collect`, and the reverse envelope. An `IOException` thrown on those
routes arrived as `KotlinException`. [ADR-202](202-runtime-route-exception-mapping.md) fixed this
for the forward routes (see the 2026-10-05 amendment below); the reverse envelope still maps stdlib
rows only.

### `@throws` name resolution

KSP exposes no file imports, so the ADR-150 `@throws T` name resolves as written when it is
qualified; otherwise in the declaration's own package, then `kotlin.`,
`kotlin.coroutines.cancellation.` and `kotlinx.io.`. The resolved class is walked to the first row it
IS-A. Only when nothing resolves does the simple name match a non-optional row, so an unimported
`kotlinx.io.IOException` never crefs `KotlinIOException` by name alone.

### Spikes

S1, Kotlin/Native 2.4.10 metadata (`klib dump-metadata`), output excerpted:

```
kotlinx-io-core-mingwX64Main-0.9.1.klib:
  public open class kotlinx/io/IOException : kotlin/Exception {
  public open class kotlinx/io/EOFException : kotlinx/io/IOException {
  public open class kotlinx/io/files/FileNotFoundException : kotlinx/io/IOException {
ktor-client-core-mingwX64Main-3.5.2.klib:
  public final class io/ktor/client/network/sockets/ConnectTimeoutException : kotlinx/io/IOException {
  public final class io/ktor/client/plugins/HttpRequestTimeoutException : kotlinx/io/IOException, ...
klib/common/stdlib (grep IOException: no matches), and:
  public open class kotlin/NullPointerException : kotlin/RuntimeException {
  public open class kotlin/NoSuchElementException : kotlin/RuntimeException {
  public open class kotlin/IllegalStateException : kotlin/RuntimeException {
  internal open class kotlin/NoWhenBranchMatchedException : kotlin/RuntimeException {
  internal open class kotlin/UninitializedPropertyAccessException : kotlin/RuntimeException {
  public open class kotlin/NumberFormatException : kotlin/IllegalArgumentException {
  public open class kotlin/ConcurrentModificationException : kotlin/RuntimeException {
  public open class kotlin/coroutines/cancellation/CancellationException : kotlin/IllegalStateException {
```

S2, a scratch `kotlinc-native main.kt -l kotlinx-io-core -l kotlinx-io-bytestring -o probe` on
mingwX64, run:

```
bangbang qn=kotlin.NullPointerException msg=null isISE=false isIAE=false isIO=false
nfe qn=kotlin.NumberFormatException msg=x isISE=false isIAE=true isIO=false
cancel qn=kotlin.coroutines.cancellation.CancellationException msg=c isISE=true isIAE=false isIO=false
myIse qn=MyIse msg=mine isISE=true isIAE=false isIO=false
eof qn=kotlinx.io.EOFException msg=eof isISE=false isIAE=false isIO=true
myIo qn=MyIo msg=io isISE=false isIAE=false isIO=true
nwbm qn=kotlin.NoWhenBranchMatchedException msg=null isISE=false isIAE=false isIO=false
```

(`nwbm` was constructed under `@Suppress("INVISIBLE_REFERENCE")`, which compiles on 2.4.10 with a
warning that the behaviour is unspecified.)

S3, a scratch KSP 2.3.10 processor on `kspKotlinMingwX64`, `commonMain` depending on kotlinx-io-core
0.9.1:

```
w: [ksp] SPIKE kotlinx.io.IOException -> kotlinx.io.IOException origin=KOTLIN_LIB mods=[OPEN, PUBLIC]
w: [ksp] SPIKE okio.IOException -> null origin=null mods=null
w: [ksp] SPIKE kotlin.NoWhenBranchMatchedException -> kotlin.NoWhenBranchMatchedException origin=KOTLIN_LIB mods=[OPEN, INTERNAL]
```

S4, a scratch console app on .NET SDK 10.0.301:

```
System.IO.IOException base=System.SystemException sealed=False ctor(string,Exception)=True
System.NullReferenceException base=System.SystemException sealed=False ctor(string,Exception)=True
System.OperationCanceledException base=System.SystemException sealed=False ctor(string,Exception)=True
System.Runtime.CompilerServices.SwitchExpressionException base=System.InvalidOperationException sealed=True ...
```

### Inferred claims, not verified

- Kotlin/Native has no runtime `KClass.supertypes` (from the API reference only).
- KSP's by-name resolution and the generated `is` see the same classpath in every consumer layout
  (verified for a direct `commonMain` dependency only, not for a transitive `implementation`
  dependency of a dependency). If wrong in the direction "KSP sees it, compile does not", the build
  fails loudly; in the other direction the IO row is silently omitted and an `IOException` surfaces
  as `KotlinException`, which is today's behaviour.
- The net8.0 BCL base types match the .NET 10 output of S4.
- `NoWhenBranchMatchedException`'s runtime `qualifiedName` is stable across Kotlin versions (verified
  on 2.4.10 only). If it changes, the row silently stops matching and the exception reverts to
  `KotlinException`; the fixture test catches it.

## Consequences

- **Breaking for consumers.** Any Kotlin exception that IS-A a row but is not the row's exact class,
  plus every NPE, `NoWhenBranchMatchedException`, `CancellationException` on a sync path and
  `kotlinx.io.IOException`, stops being a `KotlinException`. `catch (KotlinException)` no longer
  catches them; `catch (Exception e) when (e is IKotlinException)` does. A null Kotlin message now
  reads as the Kotlin FQN instead of `Kotlin error`. Release notes must say so.
- **Runtime ABI grows by one export** (`nuget_error_cause_mapped_type`) and `buildError`,
  `launchForCSharp`, `collectForCSharp` change Kotlin signature. The plugin pins the runtime version
  (ADR-127), and a new generator against an old runtime fails to compile on the `buildError` call, so
  no `NugetRuntimeAbi2` bump is proposed.
- **ADR-029 amended:** matching is by hierarchy, most specific first; three rows added
  (`kotlinx.io.IOException`, `NullPointerException`, `NoWhenBranchMatchedException`) plus the
  `CancellationException` row.
- **Deferred:** hierarchy and IO rows on the reverse envelope (the runtime-owned forward routes
  followed in ADR-202); `okio.IOException` and ktor 2's IOException rows;
  finer rows (`EOFException -> EndOfStreamException`, `FileNotFoundException`,
  `IndexOutOfBoundsException`, `UninitializedPropertyAccessException`); a forward error trace; sharing
  `BuildMapped` with the reverse shim (ROADMAP.md:221).

## Amendment 2026-10-05: the runtime-owned forward routes map module rows

[ADR-202](202-runtime-route-exception-mapping.md) closes the "Scope of the IO row" gap on the
forward side. A `kotlinx.io.IOException` thrown by a Kotlin suspend lambda invoked from C#
(`nuget_suspend_func{0..3}_invoke`) or surfacing from collecting a held or awaited `StateFlow`
(`nuget_stateflow_collect`) now arrives as `KotlinIOException : System.IO.IOException`, the same as
on a generated route. This completes this ADR's behaviour change on those routes: a consumer who
catches only `KotlinException` there no longer catches an `IOException`, a subclass of a mapped
type or an NPE. Still stdlib rows only: the reverse envelope (the deferral above stands).

Verified: a C# fact for the suspend-lambda route; runtime nativeTests that drive
`nuget_suspend_func{0..3}_invoke` and `nuget_stateflow_collect` with a throwing body and read
`NugetError.mappedType`. The `StateFlow` route has no C# end-to-end fact (inferred from the kotlinx.coroutines
contract: a stock `StateFlow` never throws from `collect`), so that route is pinned at the runtime level only.

# ADR-054: Reverse-bridge registration observability: always-on registry self-check + `NUGET_INTEROP_TRACE` opt-in trace

## Status

Accepted. The two load-bearing inferred claims below (`platform.posix.stderr` on `mingwX64`, and the
mis-arity register call's failure mode) were both verified by the walking skeleton; see "Inferred
claims" for the resolutions. `scripts/verify.sh --plugin` is green: 479 C# tests plus all plugin unit
tests.

## Context

The ADR-041 function-pointer registration table is unobservable. Nothing in the running system can
answer "did `nuget_{ns}_{type}_register` fire, and what landed?". ROADMAP.md line 220 records what
that cost during ADR-053: a 45-minute agent round bisecting a hypothesis that produced zero changes,
and hours spent on a "registration never fires" symptom that was itself a phantom of stale build
state (a consumer's `obj/project.assets.json` was not re-resolved, so Roslyn never compiled the
`[ModuleInitializer]` shims into the assembly at all).

The job of this feature is to make "did registration work, and what landed?" a five-second question.

### What the bridge looks like today

**Verified** (repo code, not documentation):

- `NugetGenerateBindingsTask.kt:436-443` emits one `COpaquePointer` parameter per registrable on the
  `@CName("nuget_{ns_snake}_{type_snake}_register")` export, in `bridgeableRegistrables()` order.
- `NugetGenerateShimsTask.kt:269-304` emits the matching `[ModuleInitializer]`, passing one thunk
  address per registrable in the same `bridgeableRegistrables()` order.
- The only guard is `NugetGenerateBindingsTask.kt:754-757`, `bindingsNotRegisteredMessage()`:

  ```
  "{Type} bindings are not registered. Ensure the generated C# shims for {packageId} are
   referenced in the consuming application before making Kotlin → C# bridge calls."
  ```

  It names the type and the package and nothing else. It cannot distinguish "the shim was never
  compiled into any loaded assembly" from "the shim ran and this one slot is wrong", which is
  precisely the distinction that cost hours.
- `nugetRuntimeContent()` already uses `kotlin.concurrent.AtomicInt` inside `NugetObjectHandle`, so
  `kotlin.concurrent` atomics compile on every target this project builds.
- The `expect`/`actual` `freeManagedString` split (`nativeMain` / `mingwMain` / `posixMain`) is the
  established mechanism for a Kotlin/Native helper whose implementation varies per platform.
- `sample-app/SampleApp.Tests/SampleApp.Tests.csproj` pins **xunit 2.9.3** on `net10.0`.

### Two failure classes, only one of which any current code can see

1. **Registration never fired.** No `[ModuleInitializer]` ran, so every slot stays `null`. The first
   bridge call throws the message above. The message does not say whether *anything* registered, so
   the reader cannot tell a whole-assembly problem (stale `contentFiles`, shim source never compiled
   in, native library not loaded) from a single-type problem.

2. **Registration fired with a mismatched contract.** The C# shim source and the Kotlin `.dylib` came
   from *different generations*. This is not hypothetical: the shims ship as source in
   `contentFiles/cs/any/` (ADR-049 Alternative 4) and are compiled into the *consumer's* assembly,
   while the registration exports live in the separately-built native library. NuGet caching by
   version (`~/.nuget/packages/samplelibrary/1.0.0`, called out in CLAUDE.md) makes it routine for
   one half to be stale. Today, a stale shim that passes 3 pointers to an export that reads 5 stores
   two garbage function pointers and crashes later, somewhere else, with no attribution. **Nothing
   verifies the count or the shapes at runtime.** ADR-048/049 fix the count and order *by contract*,
   which only holds within a single generation.

No build-time check can catch class 2. Both generators read the same `reverse-ir.json` in the same
Gradle task graph and call the same shared `bridgeableRegistrables()` (ADR-049 Alternative 10), so
they are identical *by construction* at generation time. The drift is between a generation and a
*different* generation, across a NuGet cache. It is inherently a **runtime** check.

### Prior art: how other FFI and dynamic-linking systems make binding observable

**glibc `ld.so`.** `LD_DEBUG` takes a category list (`bindings`, `symbols`, `libs`, `reloc`,
`scopes`, `versions`, `statistics`, `files`, `unused`, `all`, `help`), colon/comma/space separated.
`bindings` prints one line per symbol bound. Output goes to **stderr** by default; `LD_DEBUG_OUTPUT`
redirects it to a file, with `.{pid}` appended to the path.
([`ld.so(8)`](https://man7.org/linux/man-pages/man8/ld.so.8.html)) *(inferred: man page)*

**macOS `dyld`.** `DYLD_PRINT_BINDINGS` prints a line each time a symbolic name is bound;
`DYLD_PRINT_APIS` prints a line per dyld API call. Relevant because we ship a `.dylib`.
([Logging Dynamic Loader Events](https://developer.apple.com/library/archive/documentation/DeveloperTools/Conceptual/DynamicLibraries/100-Articles/LoggingDynamicLoaderEvents.html),
[`dyld(1)`](https://github.com/apple-opensource/dyld/blob/master/doc/man/man1/dyld.1)) *(inferred: docs)*

**.NET host.** `DOTNET_HOST_TRACE=1` (was `COREHOST_TRACE`; renamed in .NET 10, both still work)
enables host tracing. `DOTNET_HOST_TRACEFILE=<path>` redirects it to a file; **otherwise it goes to
stderr**. `DOTNET_HOST_TRACE_VERBOSITY=1..4` selects level. The documented workflow is literally
"set `DOTNET_HOST_TRACE=1` and `DOTNET_HOST_TRACEFILE=host_trace.txt`, then run the application".
([.NET environment variables](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-environment-variables))
*(inferred: docs)*

**JNI.** `-verbose:jni` prints one line per native method as it links or registers
(`[Registering JNI native method java.lang.Object.hashCode]`,
`[Dynamic-linking native method ...]`). This is the closest structural analogue to ADR-041: a
registration table populated at load time, made observable at **registration granularity, not call
granularity**. JNI does not trace every native call.
([Debugging native library linkage errors](https://jornvernee.github.io/java/panama-ffi/panama/jni/native/2021/09/13/debugging-unsatisfiedlinkerrors.html))
*(inferred: docs/blog)*

**gRPC.** `GRPC_TRACE` takes a comma-separated list of tracer names or glob patterns;
`GRPC_VERBOSITY` sets a level (now deprecated in favour of absl logging config, because it altered
the *whole application's* log settings, not just gRPC's).
([gRPC environment variables](https://github.com/grpc/grpc/blob/master/doc/environment_variables.md))
*(inferred: docs)*. The relevant lesson is the deprecation: a library that ships to third parties
should not reach for a global verbosity knob.

**JNA.** `jna.debug_load=true` (a system property, not an env var) prints the library search steps.
([JNA FAQ](https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md))
*(inferred: docs)*

**The converged pattern**, in every one of them: an **opt-in, off-by-default, boolean-or-category
env var**; output to **stderr by default with an optional file redirect**; and the trace is emitted
at **binding/registration time**, not per call. None of them trace every call through a bound symbol.
We should not either.

### The sink problem is not academic

The failure this feature exists to catch **crashed the .NET test host**. Two constraints follow:

- xunit v2 (this repo runs 2.9.3) removed Console capture entirely; output goes to
  `ITestOutputHelper`, which generated bridge code obviously cannot reach. xunit v3 reintroduced an
  opt-in `CaptureConsole` covering both stdout and stderr.
  ([Capturing Output](https://xunit.net/docs/capturing-output),
  [xunit#2071](https://github.com/xunit/xunit/issues/2071)) *(inferred: docs)*. A naive
  `Console.WriteLine` in the shim would have been invisible at exactly the moment it mattered.
- A process that dies via `FailFast` or a bad function pointer can lose whatever the test runner had
  buffered from the host's stdout/stderr pipe. A file sink, opened in append mode and flushed per
  line, survives that. This is exactly why `LD_DEBUG_OUTPUT` and `DOTNET_HOST_TRACEFILE` exist.

## Alternatives Considered

### 1. Trace only (rejected as the whole answer)

Emit an opt-in trace and nothing else.

**Rejected as sufficient**, kept as a component. A trace is passive: it only helps someone who
already suspects registration and thinks to turn it on. In the ADR-053 hunt nobody suspected
registration, which is why they bisected. A trace would have been decisive *if consulted*, and
worthless otherwise. Shipping only a trace optimises for the second time you hit the bug.

### 2. Registration-time assertion only (rejected as the whole answer)

Kotlin verifies at registration that it received what it expects, and fails fast naming the type.

**Rejected as sufficient**, kept as the centrepiece. An assertion inside the register export cannot
fire if the export is **never called**, which was the actual ADR-053 symptom. The assertion catches
contract drift (failure class 2). It does not catch "nothing ran" (failure class 1) on its own.

### 3. Both, with the *lazy* completeness check carrying failure class 1 (chosen)

Three parts, each aimed at a failure the others cannot see:

**(a) A generated `NugetRegistry` with the expected registration set baked in.** The Kotlin generator
already knows, at generation time, every registration export it emitted. It bakes that list into a
generated `NugetRegistry.kt`. Each register export records itself. The `requireNotNull` guard's
message is then *computed* rather than constant, and can say **"0 of 7 registrations have fired"**
versus **"6 of 7 have fired; only `Sample.Text.Template` is missing"**. Those two sentences are
different bugs with different fixes, and telling them apart is the entire multi-hour diagnosis.
Always on. No env var. Cost: one list insert per registered type at startup; zero on the call path.

**(b) A registration-time contract self-check.** Every register export gains two leading parameters,
a `slotCount: Int` and a `contractHash: Long`, both baked into *both* generated sides from the same
shared plugin function. Kotlin compares them against its own compile-time values and refuses to store
any pointer if they disagree. Turns silent memory corruption into a named error that says which side
is stale. Always on. Cost: two compares per type at startup.

**(c) An opt-in `NUGET_INTEROP_TRACE` registration trace.** For everything the two checks cannot
express: ordering, which `[ModuleInitializer]` entered but did not leave, whether the P/Invoke itself
died. Off by default; stderr by default; `NUGET_INTEROP_TRACEFILE` for the crashed-host case.

**Position on trace-versus-assertion, stated plainly:** the assertions are v1's centrepiece and would
have caught both ADR-053 bugs on their own with no env var and no one thinking to look. (a) would
have printed "0 of 7 fired, nothing registered at all, the shims are not compiled into the host
assembly, this is usually stale `obj/project.assets.json`" the first time the fixture ran. The trace
is the cheap complement that ships alongside, and it is what a *consumer* will reach for, because a
consumer debugging their own app cannot add a `println` to our generated code.

---

### 4. Sink: stderr by default, `NUGET_INTEROP_TRACEFILE` to redirect (chosen)

Exactly the `LD_DEBUG` / `LD_DEBUG_OUTPUT` and `DOTNET_HOST_TRACE` / `DOTNET_HOST_TRACEFILE` shape.
Both halves of the bridge (C# shim and Kotlin library) honour the same two variables and write to
the same place, so a single run interleaves both sides in one stream.

**Pros:**
- stderr is the default in every prior-art system listed above, and it is what a .NET developer
  expects from `DOTNET_HOST_TRACE`.
- The file sink answers the crashed-host and the xunit-swallows-output cases directly, which a
  console sink cannot.
- The trace stays a plain text stream, greppable, no tooling required.

**Cons:**
- Two variables instead of one.
- Per-line open/append/flush on the file sink is slow. Acceptable: at most a few dozen lines, once,
  at startup, only when explicitly enabled.

### 5. Sink: `Console.Out` (rejected)

**Rejected on evidence.** xunit v2 does not capture it, and this repo's harness is xunit 2.9.3.
This is the exact trap the parent task called out.

### 6. Sink: `EventSource` / EventPipe (rejected for v1, noted as a follow-on)

A `System.Diagnostics.Tracing.EventSource` in the generated shim, consumed with `dotnet-trace`.

**Rejected for v1**, on three grounds:
- **It is C#-only.** Half the events worth emitting are Kotlin-side (which register export actually
  landed, in the native library). The Kotlin/Native side cannot raise an `EventSource` event, so an
  EventSource-only design silently drops the half of the trace that proves the native side saw the
  call. A trace that only shows the caller's intent is precisely the trace that would *not* have
  settled the ADR-053 argument.
- **Capture cost.** `NUGET_INTEROP_TRACE=1 dotnet test` is a five-second question.
  `dotnet-trace collect --providers ... -- dotnet test`, then opening a `.nettrace`, is not.
- **It runs inside a `[ModuleInitializer]`**, the earliest user code in the process. Constructing an
  `EventSource` there is more moving parts than a `Console.Error.WriteLine` at the moment we least
  want moving parts. *(inferred: no evidence it fails, but the risk is asymmetric.)*

Noted as a natural follow-on if structured, always-on, low-overhead in-process diagnostics are ever
wanted alongside (not instead of) the text trace.

---

### 7. Control surface: env var only, boolean, no levels, no categories (chosen)

`NUGET_INTEROP_TRACE=1` (also accepting `true`/`all`), `NUGET_INTEROP_TRACEFILE=<path>`.

**Env var, not a build-time flag**, because the trace has to work in a *consumer's* application and
the consumer cannot regenerate our code. A build-time flag would mean "to diagnose this, first go
change the library author's Gradle build", which is not a diagnosis path.

**No verbosity levels and no subsystem categories in v1.** `LD_DEBUG` has ten categories because
`ld.so` has ten subsystems. `GRPC_TRACE` has a glob-matched tracer list because gRPC has dozens, and
gRPC's global `GRPC_VERBOSITY` knob is now *deprecated* precisely because a library reaching for a
process-wide verbosity setting is bad manners. We have exactly one subsystem (registration) and one
question (did it land). A single boolean is the honest surface. If per-call tracing ever lands, that
is when a category list (`NUGET_INTEROP_TRACE=registration,calls`) earns its keep, and the variable
is already shaped to grow into it.

### 8. Control surface: Gradle DSL flag baked into generated code (rejected)

`nuget { trace = true }`, generating trace calls or not.

**Rejected.** Useless to the consumer, who is the person most likely to need it and least able to
rebuild. Also makes the generated output non-deterministic w.r.t. a build flag, and would need a
matching `@Input` on both generator tasks for correct up-to-date checking, for no gain.

---

### 9. Per-call tracing (rejected for v1)

Trace every bridge call through a registered pointer.

**Rejected, opinionated.** Registration-time tracing is roughly 90% of the value at roughly 1% of the
cost. Every bug in the parent task's motivation is a *registration* bug. JNI's `-verbose:jni` made the
same call: it traces linking and registration, not invocation. A per-call trace would put a branch on
the hot path of every Kotlin → C# call, in generated code we cannot easily profile, and it would bury
the handful of lines that matter in a flood.

The one per-call event that *is* worth having costs nothing, because it is on the failure path
already: a bridge call finding an unregistered slot. That is not a trace, it is the existing
`requireNotNull` throw, and (a) above is what makes its message useful.

### 10. Slot-count check via a separate zero-arg query export (rejected)

Add `nuget_{ns}_{type}_slots(): Int` per type. C# P/Invokes it, compares with its own compile-time
count, and refuses to call `register` on a mismatch.

**Pros:** the mis-arity call to `register` is never made at all, which is strictly the safest thing.

**Rejected** on cost/benefit: it doubles the export count and the startup P/Invoke count, and it puts
the check on the C# side, where the compiled-in expectation is *itself* the thing most likely to be
stale. Chosen alternative 3(b) makes the mis-arity call but reads only argument 0 and 1 before
bailing, which is safe under the platform calling conventions (see the inferred-claims list). Kept on
record: if the walking skeleton finds that reading arguments the caller did not pass is unsafe on any
supported target, this is the fallback design.

## Decision

Ship **3 (registry + self-check + trace)**, **4 (stderr, `NUGET_INTEROP_TRACEFILE` redirect)**,
**7 (env var, boolean)**, and explicitly **not 9 (no per-call tracing)**.

Three points were open when this ADR was first drafted and have since been **decided by the human**;
they are recorded here as settled so nobody implements against a stale question:

1. **The full contract self-check ships: both `slotCount: Int` and `contractHash: Long`.** The ABI
   amendment below is approved. The hash is not optional; it is what catches same-arity type drift
   that a count alone cannot see.
2. **The env vars are `NUGET_INTEROP_TRACE` and `NUGET_INTEROP_TRACEFILE`**, mirroring
   `DOTNET_HOST_TRACE` / `DOTNET_HOST_TRACEFILE`.
3. **Everything else as proposed:** registry always-on, contract check always-on, trace opt-in,
   stderr sink, no per-call tracing.

### Amendment to the ADR-048 registration contract (ADR-048 is `Accepted`)

[ADR-048](048-kotlin-stub-generation-from-reverse-ir.md) is **Accepted**, and its "Parameter order in
the registration export" section fixes the register export as "one `COpaquePointer` per bridgeable
member, in the exact order the methods appear in `reverse-ir.json`". **ADR-054 amends that contract.**
This is a deliberate, recorded amendment, not a silent contradiction: two leading scalar parameters
are prepended, and the pointer parameters become nullable. ADR-048 carries a back-pointer to this ADR
at the head of its own contract section, and ADR-049 (which restates the same contract from the C#
side) carries the same pointer.

Nothing else in ADR-048's contract changes. The **export name** (`nuget_{ns_snake}_{type_snake}_register`),
the **pointer order** (`bridgeableRegistrables()` order), and **string ownership** are all untouched.

```
nuget_{ns_snake}_{type_snake}_register(
    slotCount: Int,             // NEW: number of pointer slots the C# shim is passing
    contractHash: Long,         // NEW: hash of the ordered registrable signature list
    ptr0: COpaquePointer?,      // was COpaquePointer (non-null)
    ptr1: COpaquePointer?,
    ...
)
```

`nuget_runtime_register` gets the same two leading parameters (with `slotCount = 1`).

The pointer parameters become **nullable** deliberately. If a stale C# shim passes fewer arguments
than the export declares, the Kotlin body reads uninitialised argument registers for the missing
tail. A non-null `COpaquePointer` parameter receiving a zero bit pattern is a boundary-invariant
violation before the body ever runs; a nullable one is not. The body checks `slotCount` and
`contractHash` first and returns without touching any pointer if either disagrees, so the garbage is
never observed.

**`contractHash` is computed by a single shared plugin function** (a sibling of
`bridgeableRegistrables()` in `RirBridging.kt`), called by *both* generators in the same task graph,
over the ordered list of `(memberKind, memberName, returnType, paramTypes..., nullability)` for the
class. It cannot drift between the two generated sides of one build, by construction, which is the
same anti-drift argument ADR-049 Alternative 10 already makes. It *can* differ between two different
builds, which is exactly what we want it to detect.

`slotCount` is redundant for *detection* (the hash subsumes it). It exists purely so the error message
can name the discrepancy: "the shim passed 3 slots, this library expects 5" is actionable in a way
that "hash 0x9a3f… != 0x21bc…" is not.

### Kotlin side: generated `NugetRegistry.kt`

Emitted once per `generateKotlinStubs` run into `io.github.xxfast.kotlin.native.nuget.internal`,
alongside the existing `NugetRuntime.kt` / `NugetInterop.kt` / `NugetEnums.kt`. Shape (illustrative,
not final source):

```kotlin
package io.github.xxfast.kotlin.native.nuget.internal

import kotlin.concurrent.AtomicReference

internal object NugetRegistry {
  // Baked at generation time: every registration export this build emitted.
  private val expected: List<String> = listOf(
    "<runtime>",
    "Sample.Text.Template",
    "Sample.Enums.Cat",
    // ...
  )

  private val landed = AtomicReference<List<String>>(emptyList())

  fun record(qualifiedType: String, slots: Int) {
    while (true) {
      val current: List<String> = landed.value
      if (landed.compareAndSet(current, current + qualifiedType)) break
    }
    nugetTrace { "registered $qualifiedType ($slots slots) [${landed.value.size}/${expected.size}]" }
  }

  // Called by every stub's requireNotNull guard, lazily, only on the failure path.
  fun notRegistered(qualifiedType: String, packageId: String): String { /* see message below */ }
}
```

`AtomicReference` + compare-and-set rather than a plain `MutableList` because the thread on which the
CLR runs each `[ModuleInitializer]` is not something this design should assume. Cost is one CAS per
registered type, once, at startup.

### Kotlin side: the register export body

```kotlin
@OptIn(ExperimentalNativeApi::class)
@CName("nuget_sample_text_template_register")
fun nuget_sample_text_template_register(
  slotCount: Int,
  contractHash: Long,
  ctorPtr: COpaquePointer?,
  renderPtr: COpaquePointer?,
) {
  NugetRegistry.checkContract(
    qualifiedType = "Sample.Text.Template",
    packageId = "SampleDependency",
    slotCount = slotCount,
    contractHash = contractHash,
    expectedSlots = 2,
    expectedHash = 0x9a3f1c02de44b671L,
  )
  TemplateBindings.ctorFn = requireNotNull(ctorPtr) { "…null ctor thunk pointer…" }.reinterpret()
  TemplateBindings.renderFn = requireNotNull(renderPtr) { "…null render thunk pointer…" }.reinterpret()
  NugetRegistry.record("Sample.Text.Template", 2)
}
```

`checkContract` writes its diagnostic to stderr **unconditionally** (not gated on
`NUGET_INTEROP_TRACE`, because this is a fatal condition, and because an exception escaping a
`@CName` export is a rough exit) and then throws `IllegalStateException`:

```
[nuget] FATAL: registration contract mismatch for Sample.Text.Template (SampleDependency).
  The C# shim passed 3 slots (contract 0x21bc5590aa17e004);
  this native library expects 2 slots (contract 0x9a3f1c02de44b671).
  The compiled C# shim and the native library were generated from different builds.
  One of them is stale. No pointers were stored (a mismatched table would corrupt memory).
  Fix: purge ~/.nuget/packages/<packageId>, delete the consumer's obj/ and bin/, re-run
  `./gradlew :sample-library:packNuget`, then rebuild the consumer. See scripts/verify.sh.
```

Refusing to store *anything* on mismatch is the load-bearing behaviour: it converts a silent
memory-corruption bug into a named error at the exact moment the two halves disagree.

### Kotlin side: the "never registered" message

`bindingsNotRegisteredMessage()` (currently a constant string,
`NugetGenerateBindingsTask.kt:754-757`) is replaced by a call to `NugetRegistry.notRegistered(...)`,
which computes one of two messages.

**Nothing landed:**

```
[nuget] Sample.Text.Template bindings are not registered (SampleDependency).
  0 of 7 expected registrations have fired. NOTHING has registered.
  Missing: <runtime>, Sample.Text.Template, Sample.Text.Formatter, Sample.Enums.Cat, …

  No [ModuleInitializer] in any *Registration.cs ran, so those files are not compiled into any
  assembly the host has loaded. This is almost never a codegen bug. In order of likelihood:
    1. Stale build state: the consuming project's obj/project.assets.json was not re-resolved, so
       NuGet never handed contentFiles/cs/any/*Registration.cs to the compiler. Delete obj/ and
       bin/, purge ~/.nuget/packages/<packageId>, restore, rebuild.
    2. The consuming project does not reference the packed package at all.
    3. The shim files compiled, but the assembly containing them was never loaded.
  Verify with: NUGET_INTEROP_TRACE=1 (each [ModuleInitializer] logs as it fires).
```

**Some landed, this one did not:**

```
[nuget] Sample.Text.Template bindings are not registered (SampleDependency).
  6 of 7 expected registrations have fired: <runtime>, Sample.Text.Formatter, Sample.Enums.Cat, …
  Missing: Sample.Text.Template

  Other shims DID register, so the shim source IS compiled in and the native library IS loaded.
  Scope this to Sample.Text.Template alone: its TemplateRegistration.cs is absent from the compiled
  output, or its [ModuleInitializer] threw before reaching the register call.
  Verify with: NUGET_INTEROP_TRACE=1.
```

Those two paragraphs are the whole point of this ADR. They are the sentences nobody could get an
answer to during ADR-053.

The same treatment applies to `NugetObjectHandle.free()`'s "NuGet interop runtime is not registered"
guard, which routes through `NugetRegistry.notRegistered("<runtime>", …)`.

### Trace sink

`internal fun nugetTrace(message: () -> String)` in the internal package, gated on a lazily-read
`val enabled: Boolean` (one `getenv` per process). The lambda parameter means the message string is
never built when the trace is off.

> **Corrected after verification.** This ADR originally proposed the sink behind an `expect`/`actual`
> split (`NugetInterop.kt`'s `mingwMain`/`posixMain` pattern), on the theory that writing to stderr
> from Kotlin/Native is a platform detail. Inferred claim 1 above resolved the opposite way:
> `platform.posix.stderr`, `fopen`, `fputs`, `fclose`, and `getenv` all bind and link on every target
> this project builds, `mingwX64` included. The shipped `NugetTrace.kt` is therefore one shared
> implementation in `nativeMain`, no `expect`/`actual` split:

```kotlin
internal fun nugetTrace(message: () -> String) {
  if (!nugetTraceEnabled) return
  val line = "[nuget] ${message()}\n"
  val path = nugetTraceFilePath
  if (path == null) {
    fputs(line, stderr)
  } else {
    val file = fopen(path, "a") ?: return
    fputs(line, file)
    fclose(file)
  }
}
```

- If `NUGET_INTEROP_TRACEFILE` is set, `fopen(path, "a")` / `fputs` / `fclose` per line.
  `fopen`/`fputs`/`fclose` are ordinary functions in `platform.posix` on every Kotlin/Native target
  this project builds, so the file sink carries no macro-binding risk.
- Otherwise write to stderr.

### C# side

A single generated `NugetTrace.cs` (emitted once, alongside `NugetRuntimeRegistration.cs`):

```csharp
internal static class NugetTrace
{
    private static readonly bool s_enabled = IsEnabled();
    private static readonly string? s_file =
        Environment.GetEnvironmentVariable("NUGET_INTEROP_TRACEFILE");

    private static bool IsEnabled() =>
        Environment.GetEnvironmentVariable("NUGET_INTEROP_TRACE") is "1" or "true" or "all";

    internal static void Write(string message)
    {
        if (!s_enabled) return;
        string line = $"[nuget:shim] {message}";
        if (s_file is null) Console.Error.WriteLine(line);
        else File.AppendAllText(s_file, line + Environment.NewLine);
    }
}
```

and each generated `[ModuleInitializer]` becomes:

```csharp
[ModuleInitializer]
internal static unsafe void Initialize()
{
    NugetTrace.Write("register enter Sample.Text.Template -> " +
        "nuget_sample_text_template_register(2 slots) dll=sample");
    try
    {
        nuget_sample_text_template_register(
            2,
            unchecked((long)0x9a3f1c02de44b671),
            (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr>)(&Ctor_Thunk),
            (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr>)(&Render_Thunk));
    }
    catch (DllNotFoundException e)
    {
        NugetTrace.WriteAlways($"FATAL: native library 'sample' not found: {e.Message}");
        throw;
    }
    catch (EntryPointNotFoundException e)
    {
        NugetTrace.WriteAlways(
            "FATAL: export 'nuget_sample_text_template_register' missing from 'sample'. " +
            "The native library predates this shim (stale build state). " + e.Message);
        throw;
    }
    NugetTrace.Write("register ok    Sample.Text.Template");
}
```

The `try/catch` here is **not** a violation of ADR-049's "let it crash". ADR-049 forbids catching in
*thunk bodies*, where a swallowed exception becomes a silently-wrong return value. This catch is in
the `[ModuleInitializer]`, catches two specific exception types (never bare `Exception`), adds the
one piece of context the raw exception lacks (which export, which library, and that stale build state
is the likely cause), and **rethrows**. That is exactly what CLAUDE.md's exception convention asks
for.

`WriteAlways` bypasses the env-var gate: a fatal condition is not opt-in.

### Trace events, complete list for v1

| Side | When | Line |
|---|---|---|
| C# | before the register P/Invoke | `register enter {Ns}.{Type} -> {export}({n} slots) dll={lib}` |
| C# | after it returns | `register ok    {Ns}.{Type}` |
| C# | shared runtime shim | `register enter <runtime> -> nuget_runtime_register(1 slot) dll={lib}` |
| Kotlin | inside each register export | `registered {Ns}.{Type} ({n} slots) [{m}/{N}]` |
| Kotlin | inside `nuget_runtime_register` | `registered <runtime> (1 slot) [{m}/{N}]` |
| Kotlin (always, ungated) | contract mismatch | the FATAL block above |
| Kotlin (always, ungated) | unregistered slot on a bridge call | the `notRegistered` block above |

The enter/ok pair on the C# side matters: if the process dies *inside* the P/Invoke (bad export,
missing library, mis-arity corruption), the last line in the trace names the type that killed it.

### Cost when the trace is off

- **Bridge call path: exactly zero.** No trace code is emitted into any stub, thunk, getter, setter,
  or constructor helper. There is no branch to skip.
- **Startup, C#:** one `Environment.GetEnvironmentVariable` per process (static field init), one
  branch per registered type.
- **Startup, Kotlin:** one `getenv` per process (lazy `val`), one branch per registered type, plus
  the always-on registry CAS and two scalar compares per type.

The always-on parts (registry, contract check) run once per bound type at process start. For a
realistic binding surface that is tens of operations, once, ever.

## Consequences

### ABI change (the one breaking item)

The register export signature changes (two leading scalars, nullable pointers). Both generated sides
change together in the same task graph, so within one build this is invisible. **Across builds it is
the whole point**: a stale C# shim compiled against the old signature will pass a thunk pointer where
the new export reads `slotCount`, the value will not equal the expected slot count, and the mismatch
message fires instead of a crash. The new contract detects the very staleness class that motivated
it, including staleness that predates the feature.

Both amended ADRs carry back-pointers to this one, so neither can be read in isolation and
implemented against the superseded signature:

- **ADR-048** (`Accepted`): a note under `## Status`, plus inline amendment callouts on "Parameter
  order in the registration export" and "Contract with the C# shim step".
- **ADR-049** (`Accepted`): a note under `## Status`, plus an inline callout on "`[ModuleInitializer]`
  and registration call shape".

### New generated artifacts

- `nativeMain/…/internal/NugetRegistry.kt`: expected set, landed set, `record`, `checkContract`,
  `notRegistered`. Emitted whenever any class binds.
- `nativeMain/…/internal/NugetTrace.kt` (+ `mingwMain` / `posixMain` actuals for `nugetTraceWrite`).
- `csharp/NugetTrace.cs`: the C# sink, emitted once per shim generation run.

### New shared plugin function

`contractHash(cls: RirClass, registrables: List<RirRegistrable>): Long` in `RirBridging.kt`, next to
`bridgeableRegistrables()` and `registrationExportName()`. Called by both generators. Must be a pure
function of the ordered registrable list, including parameter and return *types* and their
nullability, so a same-arity signature change still changes the hash.

### What is deferred

- **Per-call tracing.** Not in v1 (Alternative 9). If it ever lands, `NUGET_INTEROP_TRACE` grows a
  category list rather than a new variable.
- **Verbosity levels.** One subsystem does not need four levels.
- **`EventSource` / EventPipe provider.** Follow-on if structured diagnostics are wanted; the text
  trace is the primary surface either way, because the Kotlin half cannot participate in EventSource.
- **Forward-direction (P/Invoke) tracing.** The forward bridge has no registration table; it resolves
  by symbol name and fails loudly with `DllNotFoundException` / `EntryPointNotFoundException`. It has
  a different observability problem and does not share this one's solution.
- **Trace of the native library *load*** (which path the CLR resolved `sample` from). Genuinely
  useful, but that is `NativeLibrary.SetDllImportResolver` territory and orthogonal to registration.

## Inferred claims: the walking skeleton's first job

Every claim below is **inferred from documentation** and is *not* verified against a running
toolchain. ADR-053's false `MethodDefinitionHandle` claim is why this list exists. Validate these
before building on them; if one is false, this ADR is wrong and should be fixed, not worked around.

**Claims 1 and 2 are load-bearing. Validate them first, before any generator code is written.**

1. **`platform.posix.stderr` binds on `mingwX64`.** *(load-bearing)* The mingw `posix.def` in the
   Kotlin repo does list `stdio.h` among its headers and declares no exclusions
   ([posix.def](https://github.com/JetBrains/kotlin/blob/master/kotlin-native/platformLibs/src/platform/mingw/posix.def)),
   but on mingw-w64 `stderr` is a macro expanding to a call (`__acrt_iob_func(2)`), and cinterop only
   binds macros that expand to simple constants. It may well not be available. **Fallback if it is
   not:** the `mingwMain` actual uses `platform.windows.GetStdHandle(STD_ERROR_HANDLE)` + `WriteFile`,
   or the file sink (`fopen`/`fputs`, ordinary functions, no macro risk) becomes the only sink on
   Windows. This is why the sink lives behind `expect`/`actual` rather than in shared code.

   **Verified.** The `__acrt_iob_func` concern did not hold: `platform.posix.stderr`, `fopen`,
   `fputs`, `fclose`, and `getenv` all bind *and link* on `mingwX64` (cross-compiled from macOS). The
   fallback above was not needed, so the shipped sink is a single implementation in `nativeMain` with
   no `expect`/`actual` split at all, not the split described under "Trace sink" below (which that
   section now also corrects).

2. **Reading argument registers the caller did not populate is benign, provided the values are never
   dereferenced.** *(load-bearing, and this repo has already been burned by its neighbour)* Claimed
   for x86-64 SysV, Win64 and AArch64 AAPCS: arguments 0..n land in fixed registers regardless of the
   total count, and cdecl/AAPCS are caller-cleanup, so a callee that reads more argument slots than
   the caller wrote sees stale register contents rather than a corrupted stack.

   **Why this needs flagging harder than the rest.** This is the *exact* class of bug this repo fixed
   during ADR-053: the forward direction's C# P/Invoke declared one fewer parameter than the Kotlin
   export expected, and the Kotlin side then **wrote through** the unpopulated `errorOut` register,
   producing a `SIGBUS`. The distinction this ADR relies on is that **reading a scalar out of an
   unpopulated argument register is materially different from writing through an unpopulated pointer
   register**: the former yields a garbage `Int`/`Long`, the latter dereferences a garbage address.
   That distinction is probably sound, but it is exactly the kind of "probably" that produced the
   `SIGBUS`, so it does not get to be assumed.

   The situation the check **must survive rather than crash in** is the mis-arity *transition*: an old
   C# shim, generated before this ADR and therefore passing no scalars at all, calling a new Kotlin
   register export that expects two. That call is the whole point of the check. It must read
   `slotCount` and `contractHash` (which will be a thunk pointer bit pattern and garbage
   respectively), find them wrong, print, and throw, **without ever touching a pointer parameter**.
   Two design choices exist to make that survivable and neither is decoration:
   - the pointer parameters are declared **nullable** (`COpaquePointer?`), so a zero bit pattern
     arriving in an unpopulated register cannot trip a boundary non-null invariant before the body
     runs;
   - the body checks the two scalars and **returns before storing or reading any pointer**, so no
     garbage address is ever dereferenced or `reinterpret`ed into the table.

   **Walking skeleton must prove this on macOS arm64 (CI's target) and, if reachable, `mingwX64`,
   with a deliberately mis-arity call.** If it turns out unsafe on any supported target, Alternative
   10 (a separate zero-argument `nuget_{ns}_{type}_slots()` query export, so the mis-arity call is
   never made at all) is the pre-designed fallback and this ADR is amended, not worked around.

   **Verified, on macOS arm64 (CI's target).** A deliberately mis-arity registration call was
   exercised: `checkContract` reads only `slotCount`/`contractHash`, finds them wrong, and throws
   `IllegalStateException` before any pointer parameter is stored or dereferenced. The process
   terminates cleanly with the exception message printed, via `abort()`, not a `SIGBUS`. Reading a
   scalar out of an unpopulated argument register is confirmed benign on this target; Alternative 10
   stays on record but unused.

3. **An exception escaping a Kotlin/Native `@CName` export terminates the process with a printed
   message.** **Verified**, by the same mis-arity run as claim 2: the uncaught `IllegalStateException`
   from `checkContract` prints and terminates the process on its own. The shipped `checkContract`
   therefore does not carry a separate explicit stderr write before throwing, unlike this ADR's
   original body text below; the throw alone is sufficient.
4. **An exception thrown from a `[ModuleInitializer]` propagates and fails the process** rather than
   being swallowed. **Verified** by the same run: the `[ModuleInitializer]`'s `catch`/rethrow reaches
   `NugetTrace.WriteAlways` and the exception still fails the process afterward.
5. **All `[ModuleInitializer]` methods in one module run on the same thread** at module load. Assumed.
   The registry uses a CAS rather than a plain list precisely so this assumption is not load-bearing.
6. **xunit 2.9.3 does not capture `Console.Error`, and under `dotnet test`/VSTest the test host's
   stderr may be dropped on a crashed host.** Inferred from the xunit docs and issue tracker. If it
   turns out stderr *is* reliably surfaced, `NUGET_INTEROP_TRACEFILE` becomes convenience rather than
   necessity, and the design does not otherwise change.
7. **`Environment.GetEnvironmentVariable` and `File.AppendAllText` are safe to call from a
   `[ModuleInitializer]`.** Assumed. Very likely, but it is the earliest user code in the process.

## Decided, previously open

These were flagged as judgment calls in the first draft and have been **decided by the human**. They
are recorded here (and in the Decision section above) so no implementer treats them as still open.

1. **The ADR-048 ABI amendment is approved.** Two leading scalars on every register export, plus
   nullable pointer parameters. The degraded fallback (registry + trace only, leaving contract drift
   as silent corruption) is **not** what ships.
2. **`contractHash` ships alongside `slotCount`.** Both scalars. The hash is the correctness check
   (it catches same-arity type drift); the count exists so the error message can name the
   discrepancy.
3. **The variable names are `NUGET_INTEROP_TRACE` and `NUGET_INTEROP_TRACEFILE`**, mirroring
   `DOTNET_HOST_TRACE` / `DOTNET_HOST_TRACEFILE`.

Implemented and verified; status flipped to `Accepted` above.

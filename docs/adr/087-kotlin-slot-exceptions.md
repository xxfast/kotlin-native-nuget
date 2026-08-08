# ADR-087: Exceptions from Kotlin-implemented C# interface members: named per-slot fast-fail first, then error-envelope propagation

## Status

Proposed. **Stage 1 (the named per-slot fast-fail wrapper) is implemented and verified**:
`scripts/verify.sh` passes 939/0 with every generated slot body (`IFeedableBindings.kt`,
`IPerformerBindings.kt`) wrapped exactly as the Decision section's Stage 1 shows, message text and
all. Stage 2 (the catchable error-envelope propagation) is unshipped; this ADR stays `Proposed`
until that lands too.

## Context

ADR-085 lets a plain Kotlin class implement a bound C# interface (`class Goat : IFeedable`); each
member is a Kotlin `staticCFunction` slot that C#'s generated `IFeedableBridge` invokes through a
`delegate* unmanaged[Cdecl]`. ADR-085 shipped v1 slot bodies with **no `try`/`catch` at all** (its
"Slot exception policy" section records the correction: the originally proposed wrapper was dropped
from v1). Today an exception thrown inside a Kotlin-implemented member escapes the
`staticCFunction` and terminates through Kotlin/Native's unhandled-callback path.

Spiked baseline (verified by execution, 2026-08-09, scratch Kotlin/Native 2.4.10 macosArm64
`sharedLib` + net10.0 console host): the bare termination prints
`Uncaught Kotlin exception: kotlin.IllegalStateException: boom with no wrapper` followed by a
stack whose bridge frames are hex-mangled (`_7370696b652f...knbridge1`); nothing names the
interface member, and nothing tells the operator this was a Kotlin-implemented C# contract at all.

ROADMAP Phase 13 sequences two deliverables together:

1. **Stage 1, ship first:** a per-slot fast-fail wrapper that *names* the throwing member,
   matching the deliberate fast-fail posture of the C#-side thunks (ADR-049 "let it crash",
   `NugetGenerateShimsTask.kt:71-74`).
2. **Stage 2:** real propagation, so the C# caller of `bridge.Describe()` catches an ordinary
   .NET exception instead of losing the process.

The forward direction already solved propagation at `@CName` export boundaries: ADR-024's error
out-parameter carrying a `StableRef` envelope, extended by ADR-027 (stack trace), ADR-028 (cause
chain), ADR-029 (typed `KotlinException` hierarchy). The question is whether that envelope reuses
at the slot boundary or the slot ABI forces something new. It reuses; spiked below.

### Spike (verified by execution, 2026-08-09)

Scratch project outside the repo (`spike087`): Kotlin/Native 2.4.10 macosArm64 `sharedLib`,
net10.0 console app, the ADR-085 spike recipe. Kotlin side: an `envelopeSlot(ctx, a0, errOut:
CPointer<COpaquePointerVar>?)` whose body throws when `a0 > 0`, catches `Throwable`, writes
`StableRef.create(SpikeError(type, message, stackTraceToString()))` through `errOut`, returns a
dummy `0`; plus accessor exports (`spike_error_type/message/stack/free`) mirroring the forward
`nuget_error_*` shape. C# side: casts the slot pointer to
`delegate* unmanaged[Cdecl]<IntPtr, int, IntPtr*, int>`, invokes from the main thread and a
thread-pool thread, checks the out-param. Output:

```
[main happy] a0=-5 -> ok, returned -6, err=Zero
[main throw] a0=7 -> ENVELOPE type=kotlin.IllegalStateException msg='Kotlin slot boom, a0=7' stackFirstLine='kotlin.IllegalStateException: Kotlin slot boom, a0=7'
[pool throw] a0=8 -> ENVELOPE type=kotlin.IllegalStateException msg='Kotlin slot boom, a0=8' ...
[main again] a0=9 -> ENVELOPE ...
[main] PASS: caught-in-slot exceptions never crossed the boundary
exit=0
```

And the stage-1 wrapper (catch, print a line naming the member, rethrow):

```
[nuget] Kotlin implementation of `IFeedable.Describe` threw kotlin.IllegalStateException: boom inside a v1-style slot. A Kotlin-implemented C# interface member must not throw (ADR-087 v1); the process will now terminate.
Uncaught Kotlin exception: kotlin.IllegalStateException: boom inside a v1-style slot
    at 0   libspike.dylib ... knbridge1 ...
```

with process exit code **134** (SIGABRT), i.e. the ordinary Kotlin/Native unhandled-callback
termination still fires after the naming line, unchanged semantics plus attribution.

So, **verified**:

- A Kotlin `try`/`catch (t: Throwable)` inside a `staticCFunction` invoked from a .NET thread
  (main and thread-pool) catches the exception; nothing crosses the boundary as a panic.
- The catch can build a `StableRef` envelope (type + message + `stackTraceToString()`), write it
  through a trailing `CPointer<COpaquePointerVar>?` out-param that C# passes as `IntPtr*`, and
  return a dummy value; the host process continues, and repeated throwing calls keep working.
- Catch-print-rethrow terminates through the standard unhandled path *after* emitting the naming
  line (stage 1 needs no `exitProcess`/`terminateWithUnhandledException` API at all).
- The unwrapped baseline's termination is genuinely anonymous with respect to the member (mangled
  `knbridge` frames only).

## Alternatives Considered

### 1. Two stages sharing one catch: named fast-fail now, trailing error out-param envelope next (chosen)

Stage 1 wraps every generated slot body in `try`/`catch (t: Throwable)` that prints one line
naming `Interface.Member` and rethrows. ABI-neutral: no slot signature change, no contract-hash
change, shippable alone. Stage 2 changes the same catch to write the forward-style `NugetError`
envelope through a new trailing out-param and return a dummy; the C# bridge member checks the
out-param and throws through the forward exception mapping. The stage-1 catch block is literally
the insertion point stage 2 edits.

Pros: stage 1 is a pure diagnostic improvement with zero ABI risk; stage 2 reuses the entire
forward envelope (Kotlin `NugetError` + `nuget_error_*` accessors + C# `KotlinException`
hierarchy and type mapping), so both directions throw the *same* exception types. Cons: two
passes over the slot emitter.

### 2. Stage 2 only, skip the wrapper

Rejected: propagation touches the slot ABI on both generators and the per-interface contract
hash, while the wrapper is a body-only change. Leaving today's anonymous termination in place
until the bigger change lands is strictly worse, and the ROADMAP explicitly asks for the wrapper
on its own first.

### 3. Fast-fail via explicit termination API instead of rethrow

`kotlin.system.exitProcess(...)` or `kotlin.native.terminateWithUnhandledException(t)` after
printing. Rejected: the spike shows plain rethrow already reaches the standard unhandled-callback
termination (hook-respecting, stack-printing, exit 134) with no extra API, no opt-in annotations,
and no divergence from what ADR-085 documented as the failure path. Rethrow also keeps a
consumer-installed `setUnhandledExceptionHook` working, whereas `exitProcess` would bypass it.

### 4. Propagation via thread-local error or a C#-registered error callback

Both rejected for the reasons ADR-024 already recorded (fragile check-after-call contract,
thread-locals vs the .NET thread pool; a callback adds a registration slot and an ABI shape for
no gain over the out-param the forward direction has used since ADR-024).

## Decision

Adopt Alternative 1. The slot emitter is `kotlinBridgeSlotFunction` /
`kotlinBridgeBlock` (`NugetGenerateBindingsTask.kt:4960-5038`); the C# bridge member emitter is
the ADR-085 block in `NugetGenerateShimsTask`; slot shapes come from the shared
`kotlinBridgePlan` (`RirBridging.kt`), which both generators must keep consuming so the two sides
never drift.

### Stage 1: named per-slot fast-fail (ships alone)

**Shipped as designed.** Every generated slot body (method, getter, setter) in the real
`IFeedableBindings.kt`/`IPerformerBindings.kt` output carries exactly this wrapper, message text
included; the snippet below is simplified only in the slot's own function name
(`iFeedableDescribeSlot` here vs. the real signature-hashed
`iFeedableDescribe__fa0681f6f7a68dd9b326d010404efcfbSlot`):

```kotlin
private fun iFeedableDescribeSlot(ctx: COpaquePointer?): CPointer<ByteVar> =
  try {
    nugetKotlinString(ctx!!.asStableRef<IFeedable>().get().describe())
  } catch (t: Throwable) {
    println(
      "[nuget] Kotlin implementation of `IFeedable.Describe` threw " +
        "${t::class.qualifiedName ?: "an exception"}: ${t.message}. " +
        "A Kotlin-implemented C# interface member must not throw (ADR-087 stage 1); " +
        "the process will now terminate."
    )
    throw t
  }
```

The member name in the message is the **C# member name** (`IFeedable.Describe`, not
`describe()`), because the audience is whoever reads the dead host's log, usually the C#
consumer. Termination semantics are byte-for-byte today's: the rethrow escapes the
`staticCFunction` and Kotlin/Native terminates exactly as before (verified), now preceded by one
attributing line. No slot signature changes, no contract-hash change, no C#-side changes.

What the operator sees (verified output shape, quoted from the spike above): the naming line,
then `Uncaught Kotlin exception: <type>: <message>` and the Kotlin stack, then SIGABRT (exit 134).

### Stage 2: error-envelope propagation (reuses the forward envelope)

Every slot gains a **trailing error out-parameter**, the slot-boundary mirror of ADR-024:

```kotlin
// Kotlin slot (generated): (ctx, ...args, errOut) -> wire type
private fun iFeedableDescribeSlot(
  ctx: COpaquePointer?,
  errOut: CPointer<COpaquePointerVar>?,
): CPointer<ByteVar>? =
  try {
    nugetKotlinString(ctx!!.asStableRef<IFeedable>().get().describe())
  } catch (t: Throwable) {
    if (errOut != null) errOut.pointed.value = nugetKotlinError(t)  // StableRef<NugetError>
    null  // dummy; C# checks errOut before touching the result
  }
```

```csharp
// C# bridge member (generated):
public string Describe()
{
    IntPtr err = IntPtr.Zero;
    IntPtr p = _describe(_ctx.DangerousGetHandle(), &err);
    if (err != IntPtr.Zero) throw NugetErrorNative.BuildException(err); // forward ADR-029 mapping
    try { return Marshal.PtrToStringUTF8(p)!; } finally { Native.nuget_kotlin_string_free(p); }
}
```

Mechanism details, with claim labels:

- **Envelope shape (verified, repo code + spike).** The forward pipeline already emits, in every
  library, the Kotlin `NugetError(type, message, stackTrace, cause)` data class, its
  `nuget_error_type/message/stacktrace/cause_*` `@CName` accessor exports, and the C#
  `NugetErrorNative.BuildException` + `KotlinException`/`KotlinArgumentException`/... mapping.
  Emission is unconditional (`CirTranslator.kt:441`, `NugetProcessor.kt:1086`,
  `GenericClassExports.kt:690`, `CirErrorRenderer.kt`). The spike proved the identical
  write-`StableRef`-through-`IntPtr*` mechanics at a slot boundary, including from a thread-pool
  thread. Stage 2 therefore produces the **same catchable exception hierarchy** in both
  directions: a C# caller catches `KotlinException` (or the mapped subtype, e.g.
  `KotlinArgumentException : ArgumentException`) whether the throw came from a forward call or a
  Kotlin-implemented interface member.
- **Envelope builder (verified private, wiring inferred).** The forward `buildError(e)` that
  constructs the cause chain is `private` in the KSP-generated file
  (`GenericClassExports.kt:721`, `KModifier.PRIVATE`), so the reverse bindings cannot call it.
  The reverse runtime (`NugetRuntime.kt` emission in `NugetGenerateBindingsTask`) gains its own
  `nugetKotlinError(t: Throwable): COpaquePointer` constructing `NugetError` instances (the class
  itself has no visibility modifier, so it is public) with the same seen-set cycle guard.
  **Inferred (not verified by a build):** the reverse-generated Kotlin and the KSP-generated
  forward Kotlin compile into the same Kotlin/Native module, so the public `NugetError` class is
  referencable from the reverse bindings. If that is wrong, the fallback is an identical private
  copy of the envelope class plus reverse-owned accessor exports; the C# side is unaffected
  either way.
- **C#-side reuse (inferred, the one open wiring question).** `NugetErrorNative` is
  `internal static` inside a forward-bindings namespace (`CirRenderer.kt:20` renders per
  `CirNamespace`), and the reverse shims live in their own namespaces (`Test.Menagerie`,
  `IoGithubXxfast.KotlinNativeNuget`). Both are `<Compile>` items of the same consumer assembly,
  so `internal` reaches, but the shims generator must know the forward namespace to qualify the
  call, and **nobody has verified** that the two generators can see each other's namespace today.
  If threading that name through is ugly, the clean alternative is emitting a reverse-owned
  `NugetKotlinErrors` helper (7 `DllImport`s against the same `nuget_error_*` entry points +
  the same `BuildMapped` switch) in the runtime shim namespace, at the cost of duplicating the
  type-mapping table; the *exception types thrown* must still be the forward-generated public
  `KotlinException` family, or consumers would need two catch hierarchies. This is a build-time
  wiring decision for the implementer, not a boundary-mechanism risk: get it wrong and the C#
  does not compile, loudly.
- **Member identification (inferred, by construction).** The throw happens inside the generated
  `IFeedableBridge.Describe()`, so the .NET exception's own stack trace names the member;
  `ex.KotlinStackTrace` carries the Kotlin frames of the implementation. No message mangling.
- **Dummy returns (verified for `Int`/pointer by spike; rest inferred from the wire table).**
  Per `cfnType`: numeric `0`, `false`/zero byte, `IntPtr.Zero`/`null` pointer for
  `String`/`String?` (and ADR-086's handle-typed slots). The generated C# member must check
  `errOut` **before** interpreting the result, exactly like the forward wrappers; for a string
  slot this also means not calling `nuget_kotlin_string_free` on `Zero`.
- **ABI and contract hash (verified mechanism, required step).** Adding `errOut` changes every
  slot's arity, which `slotCount` cannot see (`RirBridging.kt:931-961` documents exactly this
  drift class). `kotlinBridgeContractHash` (`RirBridging.kt:1158`) XORs a version-tag string;
  stage 2 **must** bump that tag (e.g. `...+err_envelope`, or the next `kotlin_bridge_vN` if
  ADR-086's `v2` has landed) so an old shim against a new dylib fails the ADR-054 registration
  check loudly instead of misaligning arguments.
- **Setter/`Unit` slots:** keep `void` return, gain the same trailing `errOut`; the C# member
  checks and throws before returning.

### Sequencing confirmation

The ROADMAP's "wrapper ships first on its own" split survives the design: stage 1 is body-only
and ABI-neutral, stage 2 replaces the catch's `println`+rethrow with envelope-write+dummy-return
and adds the out-param on both sides in the same regenerated build. The catch is the same catch.
Nothing in stage 1 is thrown away except the message's final clause.

### Consumer-visible behaviour

Stage 1 (fast-fail, process still dies, now attributed):

```
[nuget] Kotlin implementation of `IFeedable.Describe` threw kotlin.IllegalStateException: goat is hungry. A Kotlin-implemented C# interface member must not throw (ADR-087 stage 1); the process will now terminate.
Uncaught Kotlin exception: kotlin.IllegalStateException: goat is hungry
    ...
```

Stage 2 (catchable):

```csharp
var sanctuary = new Sanctuary();
sanctuary.Introduce(goatFromKotlin);
try
{
    string s = sanctuary.Featured!.Describe();   // dispatches into the Kotlin Goat
}
catch (KotlinException ex)                       // or KotlinArgumentException etc. (ADR-029 map)
{
    // ex.KotlinType == "kotlin.IllegalStateException"
    // ex.Message == "goat is hungry"
    // ex.StackTrace names IFeedableBridge.Describe(); ex.KotlinStackTrace has the Kotlin frames
}
```

## Scope

**Stage 1 (v1, ships alone):** every ADR-085/086 slot kind: methods (arity 0-2), property
getters, property setters, over the full current slot vocabulary. No C# changes, no ABI changes.

**Stage 2:** the same slot set; envelope carries type, message, Kotlin stack trace, and cause
chain (full ADR-027/028/029 parity, since it reuses that envelope verbatim).

**Deferred:**
- Exceptions from future `Task`-typed slots (Phase 12 composition; the async error channel is
  ADR-023's callback envelope, not this out-param).
- Round-trip fidelity: a C#-origin exception crossing into Kotlin (via a bound API), being
  wrapped, thrown by a Kotlin implementation, and re-crossing to C# arrives as
  `KotlinException`-wrapping-text, not the original .NET exception object. Out of scope, same
  posture as the forward direction.
- Any `@Throws`-style opt-in: rejected for the same plug-and-play reasons as ADR-024; all slots
  get the wrapper.

## Consequences

- Stage 1 turns today's anonymous termination into an attributed one at zero ABI risk; ADR-085's
  "Slot exception policy" paragraph should be marked superseded by this ADR when stage 1 lands.
- Stage 2 makes Kotlin-implemented members throw the same catchable hierarchy as every forward
  call, closing the last "must not throw" hole in the interface bridge; the per-interface
  contract tag bumps, so mixed-build shims fail loudly at registration (ADR-054).
- Both generators change in lockstep off the shared `kotlinBridgePlan`; neither may derive the
  `errOut` position independently.

## Mechanism claims ledger

Verified (by the 2026-08-09 `spike087` run, output quoted above; Kotlin/Native 2.4.10
macosArm64 + .NET 10 host):
1. `try`/`catch (Throwable)` inside a `staticCFunction` invoked from .NET (main + pool threads)
   catches; writing a `StableRef` envelope through a trailing `IntPtr*` out-param and returning a
   dummy keeps the host process alive across repeated throwing calls.
2. Catch-print-rethrow emits the naming line, then the ordinary unhandled-callback termination
   (exit 134).
3. The unwrapped baseline terminates without naming the member (mangled `knbridge` frames only).

Verified (repo code): no `try`/`catch` in today's slot bodies and the emission site
(`NugetGenerateBindingsTask.kt:5000-5038`); C# thunk fast-fail posture
(`NugetGenerateShimsTask.kt:71-74`, `:1580`); unconditional forward error infrastructure,
Kotlin (`CirTranslator.kt:441`, `NugetProcessor.kt:1086`, `GenericClassExports.kt:690-800`,
`buildError` is `private`) and C# (`CirErrorRenderer.kt`: `NugetErrorNative.BuildException`,
`Check<T>`, the ADR-029 type map, public `KotlinException` family); contract-tag mechanism
(`RirBridging.kt:1158`) and the same-arity-drift warning it exists for (`RirBridging.kt:931`).

Inferred (not verified; each fails loudly at build time, not silently, if wrong):
1. Reverse-generated Kotlin and KSP-generated forward Kotlin compile into one Kotlin/Native
   module, so the public `NugetError` class is referencable from `NugetRuntime.kt`'s new
   `nugetKotlinError`. Fallback: reverse-owned envelope copy + accessors.
2. The reverse shims can reference the forward `internal` `NugetErrorNative` across namespaces
   within the consumer assembly, and the shims generator can learn that namespace. Fallback: a
   reverse-owned accessor helper that still throws the forward public exception types.
3. Dummy-return values for wire types other than `Int` and pointers (byte `0` for `bool`, etc.)
   are safe because the C# member checks `errOut` first; composition of shipped pieces, not
   spiked per type.

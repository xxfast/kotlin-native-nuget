# ADR-200: `KotlinStackTrace` keeps Kotlin frames only: trimmed at capture, cut at the export frame and at the first host frame

## Status
Accepted

Amends [ADR-027](027-stacktrace-propagation.md) (what the propagated trace contains, and its claim
that Kotlin/Native has no `getStackTrace()`) and [ADR-028](028-exception-cause-chain.md) (the cause
is no longer also embedded in its parent's trace).

## Context

ADR-027 writes `Throwable.stackTraceToString()` into the error envelope. Kotlin/Native captures the
whole native stack, and in a .NET host that is dozens of CLR and OS frames below the `@CName`
export. On mingwX64 they print as `0x0 + <address>`, or as the nearest known symbol with an absurd
offset (`_ZSt25__throw_bad_function_callv + 22803529`), which reads like a C++ crash. The Kotlin
runtime cannot name them, and the .NET half of the story is already on the C# exception.

The trace is captured in one place. `buildError` in `nuget-runtime` builds one `NugetError` per
`Throwable` in the cause chain, and every route calls it: generated sync, generic, lambda,
interface-bridge, stored-callback, `Flow` and property exports, the plugin's legacy emitter, and the
runtime-owned suspend and collect routes in `NugetLaunch.kt` (**verified by reading**). C# only
stores the string and appends it in `ToString()` (**verified by reading**).

Facts the design rests on, **verified** on mingwX64, Kotlin 2.4.10, inside an xunit host:

- The module column is `???` for every frame, Kotlin frames included, so it cannot tell library from
  host.
- User code prints as `kfun:...`. The generated `export_*` wrapper prints as `_konan_function_<n>`
  in a release build. The export frame is the C symbol itself,
  `kn_<hex of package id>_<path>__<name> + <offset>`. Real Kotlin offsets are small (15 to 1706);
  host offsets start at 22 million. Below the export, JIT code prints as `0x0 + <address>`.
- A `suspend` body throws on a coroutine worker whose stack has no export frame at all, only Kotlin
  runtime frames and then thread-start frames (`_ZN6Worker19processQueueElementEb`,
  `pthread_create_wrapper`) with huge offsets.
- `Throwable.getStackTrace(): Array<String>` exists on Kotlin/Native, behind
  `@ExperimentalNativeApi` on Kotlin 2.4.10 (the runtime file opts in). Each element is the frame
  text without the `    at ` prefix, with a trailing space. ADR-027 said there is no such array;
  that is false.
- `stackTraceToString()` appends each cause as a `Caused by:` section ending in
  `... and N more common stack frames skipped`, while the cause also crosses as its own
  `NugetError` and surfaces as `InnerException`.

## Alternatives Considered

### 1. Trim in `buildError`, per frame, from `getStackTrace()` (chosen)

One runtime function plus a three-line change at the capture site. Every route is covered because
every route calls `buildError`. The junk never crosses the bridge. The trimming is a pure function
over strings, so a unit test can run it on every host without a CLR.

### 2. Trim in C# when `KotlinStackTrace` is set

Needs the same change in eleven exception classes in `Kotlin.Native.Interop`, or in the two
generators that build them, still ships 100+ junk lines over the bridge, and has to re-parse a
joined string whose `Caused by:` count would go stale. Rejected.

### 3. A line filter over `stackTraceToString()`

Leaves `... and 28 more common stack frames skipped` pointing at frames that no longer exist.
Rejected.

### 4. Cut by address with `getStackTraceAddresses()`

Independent of the frame text, but the address count differs from the frame count (8 against 7 in a
spike), the export thunk is itself in the common suffix, and it does nothing on a worker stack.
Rejected.

### 5. Pass the module's export prefix into `buildError`

Changes a signature used at 28 generated sites for no gain over the generic `kn_[0-9a-f]+_`
pattern. Rejected.

## Decision

`buildError` renders each node as `t.toString()` followed by `\n    at <frame>` for each frame
`nugetTrimFrames(t.getStackTrace())` keeps, with the frame's trailing space removed. A trace the
rule would empty is returned unchanged, so a trace never loses all its frames.

`nugetTrimFrames` keeps a prefix of the frames:

1. **Export anchor.** If a frame matches `\s_?(kn_[0-9a-f]+_|nuget_)\S* \+ \d+`, keep frames up to
   and including the first such frame. The `_?` allows the Mach-O leading underscore; `nuget_`
   covers a runtime-owned export.
2. **Host cut, always.** Within that range, and on every trace whether or not an anchor was found,
   stop before the first frame whose symbol is `0x0` or whose `+ offset` is 1 MiB (2^20) or more.
   Running this rule on every trace, not only when the anchor misses, means a host whose frame text
   defeats the anchor still loses its CLR frames.

A trace therefore ends at the last Kotlin-symbolized frame: the `kn_<hex>_...` export on a route
whose throw site is on the calling thread, and the last Kotlin runtime frame on a suspend or `Flow`
route, which throws on a worker.

No `Caused by:` section is embedded. A cause crosses only as `NugetError.cause`, surfaces as
`InnerException`, and carries its own trimmed trace. `Suppressed:` sections are dropped for the same
reason: nothing in the bridge carries `suppressedExceptions` (deliberate; no fixture uses
`addSuppressed`, so it is untested).

## Consequences

- `KotlinStackTrace` on a sync route ends at the export frame, for example
  `kn_746573746c696272617279_cat__feedCatTreat + 156`; a property getter ends at
  `kn_746573746c696272617279_cat__snackbowl_get_nextSnack + 115`.
- `ToString()` of an exception with a cause no longer repeats the cause inside the outer Kotlin
  trace. This is a visible change to the text of `ToString()` and of `KotlinStackTrace` for any
  exception with a cause, and a trace that a consumer parses for `Caused by:` stops matching.
- The generated `export_*` wrapper frame (`_konan_function_<n>` in a release build) stays; dropping
  it needs a second pattern and it is harmless.
- A re-entrant stack (Kotlin calls a C# callback that calls Kotlin again) keeps only the innermost
  Kotlin segment, because the first anchor ends the trace. Not spiked.
- Reverse direction is unchanged: `NugetManagedException` carries no stack.
- No new handle route, so no `LeakTests` row.
- `buildError`'s signature is unchanged, so no processor, plugin or `Kotlin.Native.Interop` file
  changed.

## Evidence

**Verified** on mingwX64, Kotlin 2.4.10, in the .NET test host:

- Before the change a sync trace was 109 frames, its host frames printing as
  `_ZSt25__throw_bad_function_callv + N` with N from 22,803,529 up. No `0x0` frame appeared in that
  run, so the offset rule did the cutting. After it, the trace is the first line plus frames 0 to
  2, ending at the export frame.
- A property getter and a function with a cause trim the same way; the cause's own trace is trimmed
  and the outer trace no longer contains it.
- The suspend route ends at its last Kotlin frame.
- Runtime tests 46 passed, `IntegrationTests` 3187 passed, `LeakTests` 189 passed.
- `StackTraceTrimTest` (runtime) pins the rule over captured mingwX64 lines. `StackTraceTrimTests`
  (`IntegrationTests`) asserts the host-agnostic property on every host: no frame with symbol `0x0`
  or an offset of 1 MiB or more, at least one Kotlin frame, no `Caused by`. The check that the last
  frame is the export runs on Windows only.

**Inferred**, not verified on a macosArm64 or linuxX64 build:

- Frame text there is `N  <module>  0x<addr>  <symbol> + <offset> [(file:line:col)]`, with a real
  module column (`libkn_<hex>.dylib`, `libcoreclr.dylib`), the export printed as `kn_<hex>_...`
  (possibly with a leading `_` on Mach-O), and host frames symbolized honestly, so the offset rule
  would not fire there and the anchor is the only rule that trims them. The `StackTraceTrimTest`
  macOS and linux fixture lines are written from this reading, not captured.
- If the anchor regex misses the real text, the Kotlin frames stay intact and only the host frames
  the offset rule recognises are cut, so the trace is under-trimmed, never corrupted. The osx-arm64
  CI leg is the first real check.
- The `nuget_` alternative: no runtime-owned export has been seen on a throwing stack.

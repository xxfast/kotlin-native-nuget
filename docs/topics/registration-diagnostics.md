# Registration diagnostics

Consuming a bound C# package (see [Consuming C# in Kotlin](reverse-overview.md)) starts with a
registration step: every C# `[ModuleInitializer]` hands its function pointers to the matching
Kotlin register export at process startup. This page is what to read when that step doesn't
behave: a missing native library, a stale half of the package, or a type that never registered. A
type or member excluded when Kotlin bindings were generated is a different problem, a Gradle build
warning rather than a registration failure; see [The bridgeable subset](bridgeable-subset.md).

## Stale build: registration contract mismatch

Before storing anything, each register export compares a `slotCount` and a `contractHash` the C#
shim passes against this native library's own compile-time values. For a struct-typed reference,
the hash covers each component's **name** as well as its type, so reordering two same-typed fields
is source-compatible for C# callers but still changes the hash and is caught here:

```
[nuget] FATAL: registration contract mismatch for {Type} ({Package}). The C# shim passed {N}
slots (contract {H1}); this native library expects {M} slots (contract {H2}). The compiled C#
shim and the native library were generated from different builds. One of them is stale. No
pointers were stored (a mismatched table would corrupt memory).
```

The C# shim ships as source (`contentFiles/cs/any/`) compiled into *your* assembly, while the
register export lives in the separately built native library. NuGet caches by version, so it's
routine for one half to lag the other. Fix: purge the cached package
(`~/.nuget/packages/<packageId>`), delete the consuming project's `obj/`/`bin/`, and rebuild both
sides.

## Native library or export missing

Each `[ModuleInitializer]` also wraps its register call, so a load failure names its cause before
it rethrows the original exception:

```
[nuget:shim] FATAL: native library 'test' not found: <DllNotFoundException.Message>
```

```
[nuget:shim] FATAL: export 'nuget_sample_enums_cat_mood_service_register' missing from 'test'.
The native library predates this shim (stale build state). <EntryPointNotFoundException.Message>
```

The first means the native library itself never loaded. The second means the library loaded but
doesn't contain this export, an older native library paired with a newer C# shim, the same
stale-build fix as the contract mismatch above. Both messages print unconditionally, not only
under `NUGET_INTEROP_TRACE`.

## Nothing registered, or one type missing

When a generated stub finds its function pointer still null, it throws with one of two messages,
distinguishing zero registrations from a partial result, since they're different bugs:

```
[nuget] Test.Text.Template bindings are not registered (TestDependency). 0 of 7 expected
registrations have fired. NOTHING has registered. Missing: <runtime>, MimeMapping.MimeUtility,
Test.Enums.CatMoodService, Test.Nullability.Nickname, Test.Nullability.NicknameBook,
Test.Nullability.LegacyNicknameBook, Test.Text.Template.

No [ModuleInitializer] in any *Registration.cs ran, so those files are not compiled into any
assembly the host has loaded. This is almost never a codegen bug. In order of likelihood:
  1. Stale build state: the consuming project's obj/project.assets.json was not re-resolved, so
     NuGet never handed contentFiles/cs/any/*Registration.cs to the compiler. Delete obj/ and
     bin/, purge the NuGet cache at ~/.nuget/packages/TestDependency, restore, rebuild.
  2. The consuming project does not reference the packed package at all.
  3. The shim files compiled, but the assembly containing them was never loaded.
Verify with: NUGET_INTEROP_TRACE=1 (each [ModuleInitializer] logs as it fires).
```

```
[nuget] Test.Text.Template bindings are not registered (TestDependency). 6 of 7 expected
registrations have fired: <runtime>, MimeMapping.MimeUtility, Test.Enums.CatMoodService,
Test.Nullability.Nickname, Test.Nullability.NicknameBook, Test.Nullability.LegacyNicknameBook.
Missing: Test.Text.Template.

Other shims DID register, so the shim source IS compiled in and the native library IS loaded.
Scope this to Test.Text.Template alone: its TemplateRegistration.cs is absent from the compiled
output, or its [ModuleInitializer] threw before reaching the register call.
Verify with: NUGET_INTEROP_TRACE=1.
```

Zero registrations point at a stale `obj/project.assets.json`, fixed the same way as the contract
mismatch above. A partial count proves the shim compiled and the native library loaded, so look
only at the missing type's own `{Type}Registration.cs` and its `[ModuleInitializer]`.

## Tracing registration

Set `NUGET_INTEROP_TRACE=1` (also `true` or `all`) to log a line per registration on both sides of
the bridge, off by default. Redirect it to a file with `NUGET_INTEROP_TRACEFILE=<path>` (opened in
append mode and flushed per line, so it survives a crashed host); otherwise it goes to stderr,
since some test runners (xunit v2 included) don't capture stdout/stderr.

```
[nuget:shim] register enter Test.Enums.CatMoodService -> nuget_sample_enums_cat_mood_service_register(7 slots) dll=sample
[nuget] registered Test.Enums.CatMoodService (7 slots) [1/7]
[nuget:shim] register ok    Test.Enums.CatMoodService
[nuget:shim] register enter <runtime> -> nuget_runtime_register(7 slots) dll=sample
[nuget] registered <runtime> (7 slots) [2/7]
[nuget:shim] register ok    <runtime>
```

`[nuget:shim]` lines come from C#, printed before and after the register P/Invoke; `[nuget]` lines
come from inside the Kotlin export. If the process dies inside a P/Invoke, the last `enter` line
with no matching `ok` names the type that killed it. `[ModuleInitializer]` order is up to the CLR,
so each Kotlin line carries its own running `[m/N]` count rather than assuming a position.

This line fires for every consumer, including one that never binds a C# package, gated by the same
two variables:

```
[nuget:interop] runtime <version> loaded from <library>
```

`<library>` is the `DllImport` library name your generated bindings use; `<version>` is read at
load time through a dedicated `nuget_runtime_version` export rather than the version the generator
expected, so a stale native library shows up here even when the C# shim compiled clean. If the
library predates that export, the line instead reads
`[nuget:interop] runtime version unavailable from <library>: <ExceptionType>: <message>`.

There is no per-call trace: every diagnostic on this page is registration-granularity, checked once
per bound type at process start, not on the bridge-call path.

## Checking for a handle leak

`NugetMarshal.LiveHandles`, generated into the same shim, reports how many Kotlin `StableRef`
handles are currently retained: originally the forward bridge only (a Kotlin object passed to C#),
and, since a reverse `suspend fun` call retains a pending-continuation handle for the duration of
the await (see [Async methods](instance-members.md#async-methods)), the reverse bridge too. It's
`internal`, so code you write in the same consuming assembly can read it: snapshot the count, run
the operation you suspect leaks, then compare. `NugetBridge.GcCollect()` (also internal, in the
same shim) forces a pending release round before you re-read the count, since a release lands on a
later GC cycle, not promptly. The count is process-global, so isolate the check from anything else
running in the process that crosses a handle at the same time.

A boxed [`enum class` sealed arm](interfaces-abstract-sealed.md#an-enum-class-arm) counts here too:
its constructor mints a `StableRef` to a Kotlin enum entry, and reading one back through a holder's
property mints a second, independent handle, so both must come back to baseline on `Dispose`.

For a [cancellation-token-taking async call](instance-members.md#async-cancellation), this count
only proves the pending-continuation and `Task` handles came back to baseline: the bridge-owned
`CancellationTokenSource` is a plain .NET `GCHandle`, not one of the Kotlin `StableRef`s
`nuget_live_handles` counts, so it can't tell you whether that handle itself was released.

## Forward direction has no registration step

Kotlin exports called from C# resolve by symbol name through an ordinary P/Invoke: no contract
check and no register table. A mismatch there surfaces as a plain `DllNotFoundException` (the
native library didn't load) or `EntryPointNotFoundException` (the symbol isn't in it) at the first
call, rather than one of the messages above. The `[nuget:interop]` line is the only added signal on
this path; tracing which native asset the .NET host actually resolved is outside what this feature
covers.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bind-nuget-package-for-kotlin.md">Bind a NuGet package for Kotlin</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054: Reverse-bridge registration observability</a>
    </category>
</seealso>

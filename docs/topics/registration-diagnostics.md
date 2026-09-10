# Registration diagnostics

This page is not a language mapping. It's what to reach for when the reverse bridge's registration
step (see [Consuming C# in Kotlin](reverse-overview.md)) doesn't behave: nothing registered, one type
didn't register, or the process fails at startup with a contract-mismatch message. Every consumer of
a bound package hits this surface if they ever mix a stale build with a fresh one, so it ships to
them, not just to this repo.

## Two checks that are always on, no environment variable

Every `nuget_{ns}_{type}_register` export (and the shared `nuget_runtime_register`) does two things
before it stores a single function pointer:

1. **A contract self-check.** The C# `[ModuleInitializer]` passes two leading scalars ahead of the
   thunk pointers, `slotCount: Int` and `contractHash: Long`, computed by the same shared plugin
   function on both generated sides. Kotlin compares them against its own compile-time values. If
   they disagree, it stores nothing and throws, naming both sides. For a struct-typed reference, the
   hash covers each component's **name** as well as its type (ADR-058 Decision 5), so reordering two
   same-typed fields of a Shape B struct, which is source-compatible for C# callers, still changes the
   contract hash and is caught here rather than silently mismatching ABI slots:

   ```
   [nuget] FATAL: registration contract mismatch for {Type} ({Package}). The C# shim passed {N}
   slots (contract {H1}); this native library expects {M} slots (contract {H2}). The compiled C#
   shim and the native library were generated from different builds. One of them is stale. No
   pointers were stored (a mismatched table would corrupt memory).
   ```

   **A mismatch almost always means a stale build**: the C# shim ships as source
   (`contentFiles/cs/any/`) compiled into *your* assembly, while the register export lives in the
   separately built native library. NuGet caches by version, so it's routine for one half to be
   stale relative to the other. Fix: purge the cached package
   (`~/.nuget/packages/<packageId>`), delete the consuming project's `obj/`/`bin/`, and rebuild both
   sides. `scripts/verify.sh` does the purge and wipe in the right order; a manual
   `packNuget` does not.

2. **A computed "N of M registrations fired" message**, replacing what used to be a constant string.
   Every generated stub's `requireNotNull` guard, and `NugetObjectHandle.free()`'s runtime guard,
   route through a generated `NugetRegistry` that knows, from generation time, the full expected set
   of registrations for the build. On the failure path it distinguishes zero registrations from a
   partial result and gives the matching remediation:

   ```
   [nuget] Test.Text.Template bindings are not registered (TestDependency). 0 of 7 expected
   registrations have fired. NOTHING has registered. Missing: <runtime>, MimeMapping.MimeUtility,
   Test.Enums.CatMoodService, Test.Nullability.Nickname, Test.Nullability.NicknameBook,
   Test.Nullability.LegacyNicknameBook, Test.Text.Template.

   No [ModuleInitializer] in any *Registration.cs ran, so those files are not compiled into any
   assembly the host has loaded. This is almost never a codegen bug. In order of likelihood:
     1. Stale build state: delete the consuming project's obj/ and bin/, purge
        ~/.nuget/packages/TestDependency, then restore and rebuild.
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

   With zero registrations, the first remedy specifically addresses a stale
   `obj/project.assets.json`: NuGet did not re-resolve the package, so it did not give
   `contentFiles/cs/any/*Registration.cs` to the compiler. A partial result proves that the shim
   source was compiled and the native library loaded, so investigate only the missing type's
   `{Type}Registration.cs` and initializer.

Both checks cost nothing on the bridge-call path: no trace code is emitted into any stub, thunk,
getter, or setter. The cost is one CAS and two scalar compares per bound type, once, at process start.

## The opt-in trace: `NUGET_INTEROP_TRACE`

| Variable | Effect |
|---|---|
| `NUGET_INTEROP_TRACE=1` (also `true` or `all`) | Enables a line-per-registration trace on both sides of the bridge |
| `NUGET_INTEROP_TRACEFILE=<path>` | Redirects the trace from stderr to the given file, opened in append mode and flushed per line |

Off by default, and it stays off unless you set it: nothing is emitted into generated code beyond the
one `if (!enabled) return` check. Default sink is **stderr**, not `Console.Out`, because xunit v2
doesn't capture stdout/stderr and a naive `Console.WriteLine` would be invisible at exactly the moment
it matters. The file sink additionally survives a crashed test host, since it's flushed after every
line rather than buffered.

A real run (`NUGET_INTEROP_TRACE=1 NUGET_INTEROP_TRACEFILE=trace.log dotnet test`), both sides of the
bridge interleaved in one stream:

```
[nuget:shim] register enter Test.Enums.CatMoodService -> nuget_sample_enums_cat_mood_service_register(7 slots) dll=sample
[nuget] registered Test.Enums.CatMoodService (7 slots) [1/7]
[nuget:shim] register ok    Test.Enums.CatMoodService
[nuget:shim] register enter Test.Nullability.LegacyNicknameBook -> nuget_sample_nullability_legacy_nickname_book_register(2 slots) dll=sample
[nuget] registered Test.Nullability.LegacyNicknameBook (2 slots) [2/7]
[nuget:shim] register ok    Test.Nullability.LegacyNicknameBook
[nuget:shim] register enter MimeMapping.MimeUtility -> nuget_mimemapping_mime_utility_register(1 slot) dll=sample
[nuget] registered MimeMapping.MimeUtility (1 slot) [3/7]
[nuget:shim] register ok    MimeMapping.MimeUtility
[nuget:shim] register enter Test.Nullability.NicknameBook -> nuget_sample_nullability_nickname_book_register(12 slots) dll=sample
[nuget] registered Test.Nullability.NicknameBook (12 slots) [4/7]
[nuget:shim] register ok    Test.Nullability.NicknameBook
[nuget:shim] register enter Test.Nullability.Nickname -> nuget_sample_nullability_nickname_register(2 slots) dll=sample
[nuget] registered Test.Nullability.Nickname (2 slots) [5/7]
[nuget:shim] register ok    Test.Nullability.Nickname
[nuget:shim] register enter <runtime> -> nuget_runtime_register(1 slot) dll=sample
[nuget] registered <runtime> (1 slot) [6/7]
[nuget:shim] register ok    <runtime>
[nuget:shim] register enter Test.Text.Template -> nuget_sample_text_template_register(11 slots) dll=sample
[nuget] registered Test.Text.Template (11 slots) [7/7]
[nuget:shim] register ok    Test.Text.Template
```

`[nuget:shim]` lines come from the C# side, before and after the register P/Invoke; `[nuget]` lines
come from inside the Kotlin register export. The enter/ok pair on the C# side is what matters if the
process dies *inside* the P/Invoke: the last line names the type that killed it. Order across types
isn't fixed (`[ModuleInitializer]` order is up to the CLR), which is why each Kotlin line carries a
running `[m/N]` count rather than assuming a position.

Registration granularity only: there is no per-call trace, and none is planned for v1. Every bug this
feature exists to catch is a registration bug, not a call bug.

## Diagnosing forward handle leaks: `LiveHandles`

A different bridge health question: how many **forward** `StableRef` handles (Kotlin exports called
from C#) does Kotlin currently hold. Every generated mint and release routes through one shared pair
in the generated `CNameExports.kt`:

```kotlin
internal object NugetHandles {
  public val live: AtomicLong = AtomicLong(0L)

  public fun retain(`value`: Any): COpaquePointer {
    live.incrementAndGet()
    return StableRef.create(value).asCPointer()
  }

  public fun release(handle: COpaquePointer) {
    handle.asStableRef<Any>().dispose()
    live.decrementAndGet()
  }
}

@CName("nuget_live_handles")
public fun export_nuget_live_handles(): Long = NugetHandles.live.value
```

C# reads it as an `internal` property on `NugetMarshal`, matching the visibility of ADR-084's
`NugetBridgeState.ReleasedCount`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_live_handles")]
private static extern long Native_live_handles();

/// <summary>The number of Kotlin StableRef handles the forward bridge currently holds.</summary>
internal static long LiveHandles => Native_live_handles();
```

`LeakTests/LiveHandleTests.cs` uses the count to assert one crossing family at a time returns
to baseline: snapshot the count, run a batch of crossings, settle until the count stops moving, and
compare. Settling loops `GC.Collect()` + `WaitForPendingFinalizers()` + `NugetBridge.GcCollect()`
(the ADR-084 cleaner round) until the count is stable for several consecutive rounds, since some
releases land asynchronously:

```C#
private static void AssertNoLeak(Action crossing, int iterations = 50)
{
    for (int attempt = 1; ; attempt++)
    {
        Settle();
        long before = NugetMarshal.LiveHandles;

        for (int i = 0; i < iterations; i++) crossing();

        Settle();
        long after = NugetMarshal.LiveHandles;
        if (after == before) return;
        if (after < before && attempt < MeasurementAttempts) continue;

        Assert.Fail(
            $"expected {before} live handles after {iterations} crossings, got {after} (delta {after - before}) on attempt {attempt}");
    }
}
```

A negative delta on an early attempt is re-measured (it can be a release owed by earlier work landing
inside the window, not a leak of the crossing under test). A positive delta fails immediately, with
no tolerance band: that is the shape of a leak this harness exists to catch. See
[Exception safety on collection parameters and returns](collections.md#exception-safety-on-collection-parameters-and-returns)
for the collection-return leak this harness proved and closed.

Only forward handles are counted; the reverse side's own `StableRef` sites are not (see
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)).

Row 8g, `LambdaParameter_OnASealedArm_StringInAndOut_ReturnsToBaseline`, is committed but skipped:
the per-call callback route releases a handle-passed argument twice, a pre-existing gap on the
ordinary-class route too, not something the sealed-arm case introduced. See
[Lambda parameters on a sealed arm](interfaces-abstract-sealed.md#sealed-lambda-generated-c) and
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

<note>
    <p><code>NugetMarshal.LiveHandles</code> is process-global, so <code>LiveHandleTests.cs</code>
    and <code>CollectabilityTests.cs</code> run in their own xunit project, <code>LeakTests/</code>,
    as a separate <code>dotnet test</code> step, never inside <code>IntegrationTests/</code>. Sharing
    a process with the rest of the suite moved the count during a harness row's window and flaked
    Windows CI on unrelated rows. After <code>scripts/verify.sh</code> has built the package, run the
    harness on its own with <code>dotnet test LeakTests</code>.</p>
</note>

## Proving the object is collected, not only the handle

`LiveHandles` returning to baseline proves the `StableRef` is gone. It says nothing about the Kotlin
object the handle pointed at: something Kotlin-side (a closure, a registry, a list) could still hold
it reachable with zero live handles, and the counter would never see it. `test-library` ships a
`Morgue` fixture that watches one forward object with a Kotlin weak reference, so a C# test can prove
the object itself is collected, not just its handle:

```kotlin
@OptIn(ExperimentalNativeApi::class)
object Morgue {
  private var watched: WeakReference<Any>? = null

  /** Watch a plain class instance: the object behind `cat_create`, nothing else touches it. */
  fun watchCat(cat: Cat) { watched = WeakReference(cat) }

  /** Watch a stored-callback receiver: the unsubscribe closure captures it (ADR-039). */
  fun watchSource(source: CatEventSource) { watched = WeakReference(source) }

  /** True while the watched object is reachable; false once Kotlin's GC has collected it. */
  fun isAlive(): Boolean = watched?.get() != null

  fun forget() { watched = null }
}
```

C# does the sequencing: create the wrapper, hand it to `Morgue`, dispose it, then poll
`NugetBridge.GcCollect()` until the weak reference reads dead or a deadline passes. Each step is its
own P/Invoke, since a disposed object stays reachable while a Kotlin frame still holds its pointer
local (`LeakTests/CollectabilityTests.cs`):

```C#
private static bool CollectedWithin(TimeSpan budget)
{
    DateTime deadline = DateTime.UtcNow + budget;
    while (DateTime.UtcNow < deadline)
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        NugetBridge.GcCollect();
        if (!Morgue.IsAlive()) return true;
        Thread.Sleep(25);
    }
    return false;
}
```

`CollectabilityTests` also carries a negative control, `StoredCallbackReceiver_TokenStillHeld_StaysAlive`:
a held subscription token roots the receiver and the assertion stays false for a full second, then
flips true once the token is disposed. That is what proves the positive assertions above can actually
go red, not just pass by construction.

<note>
    <p>Today this covers a plain class and a stored-callback receiver only. A zero <code>LiveHandles</code>
    count next to a live weak reference means Kotlin-side retention the handle counter cannot see;
    see the open ROADMAP item to extend this to the other crossing families.</p>
</note>

## Limitations

- No structured, queryable diagnostics report for registration; the trace is a plain text stream,
  greppable but not machine-parseable beyond that.
- The forward direction (Kotlin exports called from C#) has no registration table and so no
  equivalent observability gap: it resolves by symbol name and fails loudly with
  `DllNotFoundException` / `EntryPointNotFoundException` at the P/Invoke site.
- Tracing the native library's own load path (which `runtimes/{rid}/native/` payload the CLR actually
  resolved) is a separate, unaddressed problem; this feature covers registration, not load resolution.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
        <a href="collections.md">Collections</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/041-kotlin-to-csharp-call-mechanism.md">ADR-041: Kotlin → managed C# call mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/048-kotlin-stub-generation-from-reverse-ir.md">ADR-048: Kotlin stub generation from reverse IR</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/049-csharp-registration-shim-generation.md">ADR-049: C# registration shim generation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054: Reverse-bridge registration observability</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/058-csharp-shape-b-structs-in-kotlin.md">ADR-058: C# Shape B structs in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/120-live-stableref-counter-and-leak-harness.md">ADR-120: Live StableRef counter and leak harness</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/121-kotlin-object-collectability-after-last-dispose.md">ADR-121: Kotlin object collectability after the last dispose</a>
    </category>
</seealso>

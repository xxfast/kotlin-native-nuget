# ADR-121: Forward, a `Morgue` fixture in `test-library` proves Kotlin's GC collects an object after its last `StableRef` is disposed, asserted eventually from C#

## Status
Accepted

## Context

ADR-120 counts forward `StableRef` handles and proves the *handle* is released. It says nothing about
the *object* behind the handle: a Kotlin-side list, a captured closure, a cleaner argument or a
registry could hold it alive with zero live handles, and `NugetMarshal.LiveHandles` would stay at
baseline. ROADMAP's "Performance & Resource Hygiene" line asks for the missing half: a Kotlin-side
weak reference plus a forced Kotlin GC, as a `test-library` fixture, not a plugin unit test. The
roadmap-archive ancestor called this "not feasible in standard unit tests"; it is feasible, and the
spikes below show which shape works and which does not.

The contract (the restatement handed to research): a C# consumer gets proof, as an xunit test, that
once it disposes the last wrapper of a forward object, Kotlin's GC actually collects it. Kotlin
declares the fixture, C# consumes it.

### What the repo already proves (all **Verified** by reading source)

- `Dispose()` on a generated class wrapper is synchronous: `{Type}Native.Dispose(_handle)` then
  `_handle = IntPtr.Zero` (`nuget-processor/.../cir/CirClassRenderer.kt:126-133`). The export is
  `NugetHandles.release(handle)` (`exports/ClassExports.kt:75-78`). No finalizer, no .NET GC in the
  path, so the C# side has nothing to release before the Kotlin object can go.
- `nuget_gc_collect` already exists: it calls `kotlin.native.runtime.GC.collect()` under
  `@OptIn(NativeRuntimeApi::class)` (`exports/InterfaceBridgeFactoryExports.kt:124-131`), reached from
  C# as `NugetBridge.GcCollect()` (`cir/CirBridgeRenderer.kt:39`). It is gated on `hasBridgeFactories`
  (`NugetProcessor.kt:1361`), which `TestLibrary` satisfies (`IPet`).
- The precedent for "dispose in one P/Invoke, collect in another, observe collection":
  `IntegrationTests/BidirectionalTests.cs:219-236` (`PetAssignedThroughTheSetter_DispatchesAndThenReleases`)
  sets `oreo.Friend = null`, then polls `GC.Collect()` + `NugetBridge.GcCollect()` for up to 5 seconds
  until the ADR-084 bridge's `createCleaner` fires (`NugetBridgeState.ReleasedCount`). That test is
  green in the suite, so a disposed `StableRef` followed by `GC.collect()` from a separate export
  **does** let Kotlin collect the object. This ADR generalises that proof from "a bridge object with a
  cleaner" to "any forward object, observed through a weak reference".
- Forward has no identity table. ADR-089's per-object bridge reuse table is reverse machinery
  (`NugetGenerateBindingsTask.kt`), so it cannot retain a forward object. The forward stored-callback
  route deliberately has "no global registry map" (`exports/StoredCallbackExports.kt:112-113`): the
  unsubscribe closure captures the receiver and the bridge lambda, and lives only as long as the
  token `StableRef` C# holds.

### Spikes (konanc 2.4.10, `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/konanc`)

Every claim below is **Verified** by the printed output, unless marked otherwise. Scratch files lived
in the session scratchpad, never in the repo.

1a. `kotlin.native.ref.WeakReference<T>` needs
`@OptIn(kotlin.experimental.ExperimentalNativeApi::class)`. Compiler output without it:

```
Spike.kt:27:45: error: this declaration needs opt-in. Its usage must be marked with
'@kotlin.experimental.ExperimentalNativeApi' or '@OptIn(kotlin.experimental.ExperimentalNativeApi::class)'
    println("B held, after collect: alive=${bWeak.get() != null}")
```

1b. `kotlin.native.runtime.GC.collect()` needs `@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)`.
A file-level `@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class, NativeRuntimeApi::class)`
compiles clean with no extra compiler flag. The processor already emits the same `NativeRuntimeApi`
opt-in into the generated library, so `test-library` compiles it today.

1c. Disposing a `StableRef` does **not** make the object unreachable until a GC runs, and one
`GC.collect()` is enough when the disposing frame has returned and nothing else roots the object.
Spike3 (mint, dispose and read all in their own frames, pointer in a global):

```
plain: after collect alive=false
inline create+dispose: before collect alive=true
inline create+dispose: after collect alive=false
held: after collect alive=true
disposed via global ptr: before collect alive=true
disposed via global ptr: after collect alive=false
disposed via global ptr: after 2nd collect alive=false
```

1c, the anomaly. When the frame that calls `GC.collect()` also holds the `COpaquePointer` as a local
(Spike5, Spike6) or a `Pair` containing it (Spike2, Spike4), the object stays alive across four
collections, whether or not the `dispose()` ran in a callee frame:

```
== spike6 (dispose in callee frame, ptr local in main) ==
B held alive=true
C disposed in callee frame, before collect alive=true
D disposed in callee frame, after 1 collect alive=true
```

The cause is **not established** (inferred: a frame slot in the collecting frame still reaches the
object, possibly through the pointer temporary; nobody has read the runtime to confirm). What the
spikes do establish is the safe shape: mint, dispose and collect must each be a separate top-level
entry with no Kotlin frame persisting between them, which is exactly what three separate P/Invokes
give (Spike3 is that shape with `main` standing in for the .NET caller, and `BidirectionalTests.cs:219`
is that shape for real). A fixture that mints, disposes and collects inside one Kotlin export would be
red for a reason that has nothing to do with the bridge.

## Alternatives Considered

### 1. A `Morgue` object in `test-library` holding one `WeakReference<Any>`; C# does the dispose and drives `NugetBridge.GcCollect()` in a polled loop (chosen)

Kotlin owns the weak reference and the "is it still alive" read. C# owns sequencing: create the
wrapper, hand it to `Morgue.watch`, dispose it, then poll `NugetBridge.GcCollect()` +
`Morgue.IsAlive` until false or a deadline. Pros: reuses the existing `nuget_gc_collect` export and
the `ReleaseFiredWithin` precedent; every step is its own P/Invoke, so the Spike6 anomaly cannot
apply; the eventual assertion tolerates a GC that needs more than one round. Cons: one more opt-in
annotated fixture in `test-library`; an eventual assertion is weaker than a strict one (a genuinely
retained object shows up as a 5-second timeout, not an instant failure).

### 2. `Morgue.collectAndCheck()` does `GC.collect()` and the weak read in one export, strict assertion

One P/Invoke fewer and a strict `Assert.False`. Rejected: Spike3 shows a single collect suffices only
in the clean shape, and the anomaly above shows how narrow "clean" is. A strict single-round check
would be flaky the first time Kotlin's GC is mid-cycle when the export enters, and `LiveHandleTests`
already established that this suite settles instead of asserting one round.

### 3. Reuse the ADR-084 cleaner as the liveness signal (`createCleaner` on the fixture object)

A `Cat`-like fixture with a `createCleaner` that bumps a Kotlin counter, read through an export.
Rejected: it only proves what `BidirectionalTests.cs:219` already proves, and the cleaner runs on a
separate worker after the GC, so it adds a second asynchronous hop to the assertion. A weak reference
is read synchronously on the calling thread.

### 4. Kotlin-side `kotlin.test` in `test-library/src/nativeTest`

No C# involved. Rejected by the restatement: the proof has to be that the *C# consumer's* `Dispose()`
is the last root, which only a test that drives the generated wrapper can show.

## Decision

Alternative 1. Two fixture shapes, one that needs no work at the seam and one that crosses the
stored-callback closure seam, plus a negative control that proves the assertion can go red.

### Kotlin fixture (`test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/Morgue.kt`)

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

/**
 * Watches one forward object weakly so C# can prove Kotlin's GC collects it once the last
 * wrapper is disposed (ADR-121). C# drives the collection through `NugetBridge.GcCollect()`.
 */
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

Mechanism claims:

- `@OptIn(ExperimentalNativeApi::class)` on the `object` does not remove it or its members from the
  forward export (**Verified**: `Issue113Sample.kt:66` control C2, `@OptIn` is a marker consumer, not a
  marker member).
- A private `var` of type `WeakReference<Any>?` is not exported (**Verified**: the planner keeps only
  `Visibility.PUBLIC` members, `NugetProcessor.kt:337`, `:346`, `:547`, `:554`).
- The two watchers are named `watchCat` / `watchSource` rather than overloaded, so the fixture does
  not depend on overload support for `object` members (not re-verified here; FEATURES only says
  "methods PascalCased" for `object`).
- `Morgue.watch(cat)` receives the same Kotlin object `cat_create` minted, not a copy (**Verified**:
  the parameter route is `handle.asStableRef<T>().get()`, ADR-120's "read, no release" row).
- `GC.collect()` is not called from the fixture; `NugetBridge.GcCollect()` is the existing export
  (**Verified** above). Nothing in the fixture holds a Kotlin frame between C# calls.

### C# test (`IntegrationTests/CollectabilityTests.cs`, following `LiveHandleTests.cs`)

```csharp
public class CollectabilityTests
{
    private sealed class Listener : ICatEventListener
    {
        public void OnMeow(string message) { }
        public void OnPurr() { }
        public void Dispose() { }
    }

    // Poll, not a fixed round count: BidirectionalTests.ReleaseFiredWithin is the precedent.
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

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void CreateWatchAndDispose()
    {
        using var oreo = new Cat("Oreo", 9);
        Morgue.WatchCat(oreo);
    }

    // Shape 1: a plain class, no seam. Red only if the bridge itself retains.
    [Fact]
    public void PlainClass_LastDispose_IsCollected()
    {
        CreateWatchAndDispose();
        Assert.True(CollectedWithin(TimeSpan.FromSeconds(5)), "Cat stayed reachable after its last Dispose");
        Morgue.Forget();
    }

    // Shape 2: the stored-callback seam. The unsubscribe closure captures the source; once the
    // subscription token and the wrapper are both disposed nothing may reach it.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void SubscribeUnsubscribeAndDispose()
    {
        using var source = new CatEventSource("Oreo");
        Morgue.WatchSource(source);
        using IDisposable sub = source.AddListener(new Listener());
        source.Trigger();
    }

    [Fact]
    public void StoredCallbackReceiver_AfterUnsubscribeAndDispose_IsCollected()
    {
        SubscribeUnsubscribeAndDispose();
        Assert.True(CollectedWithin(TimeSpan.FromSeconds(5)), "CatEventSource stayed reachable after unsubscribe + Dispose");
        Morgue.Forget();
    }

    // Negative control: a live subscription token is the only remaining root and must keep the
    // source alive. Proves the assertion above can go red, and documents that a leaked token roots
    // the receiver.
    [Fact]
    public void StoredCallbackReceiver_TokenStillHeld_StaysAlive()
    {
        IDisposable sub;
        using (var source = new CatEventSource("Oreo"))
        {
            Morgue.WatchSource(source);
            sub = source.AddListener(new Listener());
        }
        Assert.False(CollectedWithin(TimeSpan.FromSeconds(1)), "a live subscription token must root the source");
        sub.Dispose();
        Assert.True(CollectedWithin(TimeSpan.FromSeconds(5)));
        Morgue.Forget();
    }
}
```

The `NoInlining` helpers keep the wrapper out of the test method's frame, the same reason
`BidirectionalTests.AssignFriendAndDrop` exists. The wrapper's `_handle` is zeroed on `Dispose` so a
lingering C# reference to the wrapper roots nothing on the Kotlin side anyway; the helper is belt and
braces.

### Which retention seams each shape crosses

| Shape | Object watched | What could retain it with zero live handles | Expected |
|---|---|---|---|
| 1, plain class | `Cat` | nothing: `cat_create` mints, `cat_dispose` releases, no Kotlin-side registry | collected |
| 2, stored-callback receiver | `CatEventSource` | the unsubscribe closure captured by the token `StableRef` (`StoredCallbackExports.kt:112`), and `listeners` holding the bridge lambda (which does not reach the source) | collected once the token is disposed |
| control | `CatEventSource` | the token, deliberately held | alive until the token is disposed |

The ADR-084 bridge-object seam (`createCleaner` argument captures nothing, `InterfaceBridgeFactoryExports.kt:26-30`)
is already covered by `BidirectionalTests.cs:219` and is not repeated here.

## Consequences

- `test-library` gains one `@OptIn(ExperimentalNativeApi::class)` fixture. No compiler flag, no
  `languageSettings.optIn` in `build.gradle.kts` (**Verified** by the konanc spike: a declaration-level
  opt-in compiles clean).
- The assertion is eventual (5-second poll), matching `BidirectionalTests.ReleaseFiredWithin`. A
  genuine retention fails as a timeout with the message naming the fixture.
- Open: the Spike5/Spike6 anomaly (a pointer local in the collecting frame keeps a disposed object
  alive) is unexplained. It does not affect the chosen shape, which never collects from a frame that
  holds the pointer, but anyone tempted by Alternative 2 should read it first. If it turns out to be
  a runtime behaviour rather than a frame-slot artefact, the eventual assertion still holds because
  the .NET side's P/Invoke frames are gone between calls.
- Open: whether `TestLibrary`'s `Morgue` should watch a value from every crossing family (sealed arm,
  generic wrapper, suspend result). Deferred: the plain-class and stored-callback shapes are the two
  the restatement asks for; a per-family sweep belongs with ROADMAP's assembly-level `LiveHandles`
  sweep.
- Not changed: the processor, the generated C#, the reverse pipeline.

## Amendments (2026-09-09, implementation)

`scripts/verify.sh` green, 1569 tests, the class runs in about 1 s, three filtered reruns 3/3.
Everything below is **Verified** by that run.

- The negative-control listener could not be named `Listener`: `NestedInterfaceGateTests` scans the
  whole test assembly for any type named `Listener` / `IListener`, so `CollectabilityTests.cs` names
  its nested class `QuietListener` instead.
- `Morgue.Forget()` and the held subscription token's `Dispose()` both run in `finally` blocks, so a
  failed assertion in one test cannot leave `Morgue`'s weak reference pointed at a wrapper that no
  longer exists, and cannot leak the negative control's live token into a later test.
- The negative control (`StoredCallbackReceiver_TokenStillHeld_StaysAlive`) creates its
  `CatEventSource` inside a `[MethodImpl(NoInlining)]` helper (`SubscribeAndDisposeTheSourceOnly`) so
  no stack slot in the asserting frame holds the wrapper, the same reason `CreateWatchAndDispose` and
  `SubscribeUnsubscribeAndDispose` exist. The source stayed alive for the full 1-second poll while the
  token was held, and collected within the following 5-second poll once the token was disposed,
  confirming the retention claim in the "which retention seams" table above.
- Disposing the subscription token after the source wrapper is safe because the generated
  `removeListener` export never dereferences the receiver handle (`CNameExports.kt` around line 6628,
  confirmed by reading the generated source).
- `CollectabilityTests.cs` moved out of `IntegrationTests/` into `LeakTests/`, alongside ADR-120's
  `LiveHandleTests.cs`, for the same reason: its forced `NugetBridge.GcCollect()` rounds are exactly
  the kind of perturbation the leak harness must not share a process with. See
  [ADR-120](120-live-stableref-counter-and-leak-harness.md)'s Amendment 2 (2026-09-09).

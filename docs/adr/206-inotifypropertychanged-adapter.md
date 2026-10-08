# ADR-206: Forward, an opt-in `INotifyPropertyChanged` adapter over `KotlinStateFlow<T>`, generated per module

## Status
Proposed. Drafted 2026-10-08 from a read-only research run; no spike was run, every mechanism claim below is labelled.

## Context

ADR-065 maps `StateFlow<T>` to a generated `KotlinStateFlow<T> : KotlinFlow<T>` that is an `IAsyncEnumerable<T>` with a synchronous `.Value`, and rejected `INotifyPropertyChanged` as the core mapping because it needs an always-running collector with UI-thread affinity (ADR-065 Alternative 2). It named an opt-in adapter as deferred. XAML frameworks (WPF, WinUI, MAUI, Avalonia) bind to `INotifyPropertyChanged`, so without an adapter every consumer writes the same loop: start an `await foreach` on the dispatcher, copy each element into a view-model property, raise `PropertyChanged`, and remember to cancel it.

Constraints read in the repo (verified by reading unless marked):

- `KotlinStateFlow<T>` is emitted per module in the module root namespace (`cir/CirFlowRenderer.kt:300`, generated `Interop.cs` under `namespace TestLibrary`). The shared contract `Kotlin.Native.Interop` holds exceptions, `KotlinOptional<T>` and `KotlinNothing` only, and ADR-178:39 keeps runtime state per package. A contract-side adapter cannot name `KotlinStateFlow<T>`.
- The enumerator's `MoveNextAsync` awaits an unbounded `Channel<T>` without `ConfigureAwait(false)` (`CirFlowRenderer.kt`, `MoveNextAsync` body), and the collection's callbacks are rooted by a `GCHandle.Alloc` until the collect completes, errors, is cancelled or is disposed (`NugetFlowCallbacks.Root()`); the enumerator roots the owner wrapper through `_startCollect` (comment at `CirFlowRenderer.kt:119-124`).
- ADR-187's lifetime contract: a dropped wrapper is released by the GC; a discarded subscription keeps delivering until its owner is released or it is disposed (ADR-187:111-113).
- ROADMAP.md:12: additive work after 1.0.0 ships in 1.x; the adapter is additive.

## Alternatives Considered

### 1. Generated per module, `AsNotifying()` extension plus `KotlinStateFlowObservable<T>` (chosen)

Emitted by `CirFlowRenderer.kt` under the existing `includesStateFlow` gate, next to `KotlinStateFlow<T>`. Pros: can name `KotlinStateFlow<T>`; zero cost for a module with no StateFlow; no contract change; consistent with ADR-178's per-package rule; plain C#, compiled by the consumer (`contentFiles`). Cons: one copy per package (as `KotlinStateFlow<T>` already is); about 60 renderer lines; joins the semver-covered generated type set.

### 2. Contract library `Kotlin.Native.Interop`

Needs either an extension on `IAsyncEnumerable<T>` plus a seed value (pollutes every async stream in IntelliSense, loses the `.Value` seed) or a new `IKotlinStateFlow<T>` contract interface that the generated class implements (a processor change anyway, plus a contract surface). No identity benefit: nobody catches or shares an adapter across packages. Rejected.

### 3. Separate NuGet package

A new csproj, a `release.yml` push step and a nuspec dependency for a type of about 60 lines. Rejected as heavier than the feature.

### 4. `KotlinStateFlow<T>` implements `INotifyPropertyChanged` itself

ADR-065 Alternative 2: an always-running collector on every StateFlow, UI-thread affinity baked into a neutral type. Still rejected.

## Decision

Generate, per module that declares at least one `StateFlow<T>` member:

```csharp
public static class KotlinStateFlowExtensions
{
    public static KotlinStateFlowObservable<T> AsNotifying<T>(this KotlinStateFlow<T> flow, SynchronizationContext? context = null)
        => new KotlinStateFlowObservable<T>(flow, context ?? SynchronizationContext.Current);
}

public sealed class KotlinStateFlowObservable<T> : INotifyPropertyChanged, IDisposable
{
    private readonly SynchronizationContext? _context;
    private readonly CancellationTokenSource _cts = new();
    public event PropertyChangedEventHandler? PropertyChanged;
    public T Value { get; private set; }
    public Task Completion { get; }

    internal KotlinStateFlowObservable(KotlinStateFlow<T> flow, SynchronizationContext? context)
    {
        _context = context;
        Value = flow.Value;                     // one seed read; bindings never see default(T)
        Completion = RunAsync(flow);
    }

    private async Task RunAsync(KotlinStateFlow<T> flow)
    {
        try
        {
            await foreach (T item in flow.WithCancellation(_cts.Token).ConfigureAwait(false))
            {
                if (_context == null) Deliver(item);
                else _context.Post(static s => { var (self, v) = ((KotlinStateFlowObservable<T>, T))s!; self.Deliver(v); }, (this, item));
            }
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }
    }

    private void Deliver(T item)
    {
        if (_cts.IsCancellationRequested) return;
        Value = item;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Value)));
    }

    public void Dispose()
    {
        if (_cts.IsCancellationRequested) return;
        _cts.Cancel();
    }
}
```

Rules:

- **Threading.** `PropertyChanged` is raised through `SynchronizationContext.Post` on the context captured at `AsNotifying()` (or the one passed in). With no context, it is raised inline on the thread that received the element, like `ObservableCollection<T>`. `Value` is assigned on the same thread immediately before the event, so a handler reading `Value` sees the delivered element. **Inferred (not run)**: a WPF/WinUI/MAUI dispatcher context preserves `Post` order, so elements arrive in emission order; a consumer-supplied context that reorders posts would reorder notifications.
- **Value is cached.** `Value` is the last delivered element, never a re-read of `flow.Value`, so a binding read costs no P/Invoke and mints no wrapper (ADR-005 would otherwise create one undisposed wrapper per binding read). A replaced wrapper-typed element is not disposed by the adapter; it is released by the GC under ADR-187.
- **Lifetime.** `Dispose()` cancels the collection; the enumerator's cancellation registration calls `NugetJobNative.Cancel`, the Kotlin side reports cancelled, the channel completes and `DisposeAsync` frees the job cell (verified by reading `CirFlowRenderer.kt`; the end-to-end handle count returning to baseline is **inferred** until the LeakTests row runs). An undisposed adapter keeps the Kotlin collect job, the enumerator, and the owner wrapper rooted for the life of the process (verified by reading: `GCHandle.Alloc` in `NugetFlowCallbacks.Root()`, `_startCollect` held by the enumerator); no finalizer is added because the running loop roots the adapter and a finalizer would never run. This is ADR-187's subscription-token contract applied to a collection: dispose it, or it keeps delivering.
- **Errors.** A faulted collection (Kotlin exception) ends the loop and faults `Completion`; `PropertyChanged` is not raised for a fault. A consumer that wants to observe faults awaits or continues `Completion`.
- **ABI.** No new export, nothing in `nuget-runtime/` (Kotlin or C#), no contract change. Everything lives in the generated per-module file and regenerates with it.

## Consequences

- Seven files: `cir/CirFlowRenderer.kt`, one Tier 1 cell, `IntegrationTests/StateFlowNotifyingTests.cs`, one `LeakTests/LiveHandleTests.cs` row, `docs/topics/coroutines-and-flow.md`, `docs/topics/supported-features.md`, this ADR.
- Test fixtures already exist: `CatMoodTracker.EnergyLevel` (`StateFlow<Int>`), `.Mood` (`StateFlow<String>`), `.Playmate` (`StateFlow<Cat>`).
- Deferred: two-way `KotlinMutableStateFlowObservable<T>` (settable `Value` through ADR-071's `KotlinMutableStateFlow<T>`), nullable `KotlinStateFlow<T>?` members (consumer null-checks first), `IObservable<T>` (ADR-026 rejected an Rx dependency).
- Scheduling: additive, not a 1.0.0 gate; ships in 1.x per ROADMAP.md:12.

## Inferred claims an implementer must check

1. The cancel path from `Dispose()` returns `NugetMarshal.LiveHandles` to baseline (the LeakTests row proves it).
2. `SynchronizationContext.Post` order is preserved on the XAML dispatchers (documentation, not run).
3. The generated adapter compiles under NativeAOT and trimming with no warnings (plain C#, but AotIntegrationTests is the proof).

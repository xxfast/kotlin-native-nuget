# Coroutines and Flow

Kotlin coroutines map onto .NET's own async model: `suspend fun` becomes `async`/`Task<T>`, coroutine cancellation maps to `CancellationToken`, and structured concurrency means disposing the owning C# object cancels any coroutine it started. `Flow<T>` becomes `IAsyncEnumerable<T>`, consumable with `await foreach`.

| Kotlin | C# | Notes |
|---|---|---|
| `suspend fun` | `async` / `Task<T>` | [ADR-019](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md) |
| `suspend () -> R` lambda | `KotlinSuspendFunc<R>` / `Task<R>` | [ADR-020](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/020-suspend-lambda-mapping.md) |
| structured concurrency | honoured on `Dispose()` | [ADR-021](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/021-structured-concurrency.md) |
| coroutine cancellation | `CancellationToken` | [ADR-022](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/022-cancellation-token-support.md) |
| in-flight async drain | `IAsyncDisposable` | graceful drain, [ADR-025](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/025-async-disposable.md) |
| `Flow<T>` | `IAsyncEnumerable<T>` | cold streams, [ADR-026](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/026-flow-mapping.md) |

## `suspend fun`

From `test-library/src/nativeMain/kotlin/.../cat/AsyncCatService.kt`:

```kotlin
class AsyncCatService(private val prefix: String) {
  suspend fun fetch(): String {
    delay(5.seconds)
    return "$prefix result"
  }

  suspend fun fetchCat(name: String): Cat {
    delay(5.seconds)
    return Cat(name)
  }
}
```

Every `suspend fun` becomes an `async Task<T>` method, suffixed `Async`. Using it, from `IntegrationTests/SuspendFunctionTests.cs`:

```C#
[Fact]
public async Task AsyncCatService_FetchCat_ReturnsCatObject()
{
    using var service = new AsyncCatService("toys");
    using var cat = await service.FetchCatAsync("Oreo");
    Assert.Equal("Oreo", cat.Name);
}
```

## `suspend () -> R` lambdas

From `test-library/src/nativeMain/kotlin/.../cat/CatFeeder.kt`:

```kotlin
class CatFeeder(val catName: String) {
  val onFeed: suspend () -> String = {
    delay(1.seconds)
    "$catName gobbled up the food!"
  }

  val onFeedWith: suspend (String) -> String = { food ->
    delay(1.seconds)
    "$catName devoured the $food!"
  }
}
```

A suspend-lambda property becomes a `KotlinSuspendFunc<...>` handle with an `InvokeAsync` that optionally accepts a `CancellationToken`:

```C#
public KotlinSuspendFunc<string> OnFeed => new KotlinSuspendFunc<string>(Native_Get_onFeed(_handle));
public KotlinSuspendFunc<string, string> OnFeedWith => new KotlinSuspendFunc<string, string>(Native_Get_onFeedWith(_handle));
```

Using it, from `IntegrationTests/SuspendLambdaTests.cs`:

```C#
[Fact]
public async Task CatFeeder_OnFeedWith_InvokeAsync_ReturnsExpectedString()
{
    using var feeder = new CatFeeder("Mylo");
    using var onFeedWith = feeder.OnFeedWith;
    string result = await onFeedWith.InvokeAsync("salmon");
    Assert.Equal("Mylo devoured the salmon!", result);
}

[Fact]
public async Task CatFeeder_OnFeed_CancelViaToken_OreoFeedingInterrupted()
{
    using var feeder = new CatFeeder("Oreo");
    using var onFeed = feeder.OnFeed;
    var cts = new CancellationTokenSource();
    Task<string> feedTask = onFeed.InvokeAsync(cts.Token);
    await Task.Delay(50);
    cts.Cancel();
    await Assert.ThrowsAsync<TaskCanceledException>(() => feedTask);
}
```

## Structured concurrency and `CancellationToken`

From `test-library/src/nativeMain/kotlin/.../cat/CatNapService.kt`:

```kotlin
class CatNapService {
  suspend fun longNap(): String {
    delay(10.seconds)
    return "refreshed after nap"
  }

  suspend fun napWithDream(): String = coroutineScope {
    launch { delay(10.seconds) }
    delay(10.seconds)
    "had a dream"
  }
}
```

Disposing the owning wrapper cancels its scope, which cancels any in-flight coroutine it started, including child coroutines launched inside a `coroutineScope { launch { ... } }`. Using it, from `IntegrationTests/StructuredConcurrencyTests.cs`:

```C#
[Fact]
public async Task Dispose_WhileCoroutineInFlight_CancelsTask()
{
    Task<string> task;
    using (var service = new CatNapService())
    {
        task = service.LongNapAsync();
        await Task.Delay(50);
    }
    await Assert.ThrowsAsync<TaskCanceledException>(() => task);
}

[Fact]
public async Task ChildCoroutine_CancelsWithParentOnDispose()
{
    Task<string> task;
    using (var service = new CatNapService())
    {
        // napWithDream launches a child coroutine internally via coroutineScope { launch { ... } }
        task = service.NapWithDreamAsync();
        await Task.Delay(50);
    } // Dispose() cancels the parent, which should propagate to the child coroutine
    await Assert.ThrowsAsync<TaskCanceledException>(() => task);
}
```

Every generated `async` method also accepts an explicit `CancellationToken`, independent of `Dispose()`. From `IntegrationTests/CancellationTokenTests.cs`:

```C#
[Fact]
public async Task CancelCatNap_ViaToken_OreoNapInterrupted()
{
    using var service = new CatNapService();
    var cts = new CancellationTokenSource();
    Task<string> napTask = service.LongNapAsync(cts.Token);
    await Task.Delay(50);
    cts.Cancel();
    await Assert.ThrowsAsync<TaskCanceledException>(() => napTask);
}

[Fact]
public async Task CancelOneNap_SiblingNapCompletes()
{
    // Oreo's nap is cancelled, but Mylo's quick nap on the same scope finishes fine
    using var service = new CatNapService();
    var cts = new CancellationTokenSource();
    Task<string> longNapTask = service.LongNapAsync(cts.Token);
    Task<string> quickNapTask = service.QuickNapAsync();
    await Task.Delay(50);
    cts.Cancel();
    await Assert.ThrowsAsync<TaskCanceledException>(() => longNapTask);
    string result = await quickNapTask;
    Assert.Equal("quick nap done", result);
}
```

## `IAsyncDisposable` graceful drain

`Dispose()` cancels in-flight work immediately; `DisposeAsync()` instead waits for in-flight coroutines to finish naturally before releasing the handle. From `IntegrationTests/AsyncDisposableTests.cs`:

```C#
[Fact]
public async Task DisposeAsync_WaitsForOreoQuickNap_ThenCompletes()
{
    Task<string> oreoNap;
    var service = new CatNapService();
    oreoNap = service.QuickNapAsync();
    await service.DisposeAsync();
    string result = await oreoNap;
    Assert.Equal("quick nap done", result);
}

[Fact]
public async Task Dispose_StillCancels_DisposeAsync_Drains()
{
    // Dispose() yanks Mylo off the couch (cancels), DisposeAsync() lets Oreo finish his nap (drains)
    Task<string> myloNap;
    using (var cancelService = new CatNapService())
    {
        myloNap = cancelService.LongNapAsync();
        await Task.Delay(50);
    } // Dispose() — Mylo's nap is cancelled
    await Assert.ThrowsAsync<TaskCanceledException>(() => myloNap);
}
```

## `Flow<T>`

From `CatFeeder.kt`:

```kotlin
val mealAnnouncements: Flow<String> = flow {
  emit("$catName is hungry")
  delay(50.milliseconds)
  emit("$catName is eating")
  delay(50.milliseconds)
  emit("$catName is full")
}

fun treats(count: Int): Flow<String> = flow {
  (1..count).forEach { i ->
    delay(50.milliseconds)
    emit("$catName ate treat #$i")
  }
}
```

Every `Flow<T>`-typed property or return becomes a `KotlinFlow<T> : IAsyncEnumerable<T>`, built from a `collect` entry point plus a per-item callback triple (`onNext`/`onComplete`/`onError`):

```C#
public KotlinFlow<string> MealAnnouncements
{
    get
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(CatFeeder));
        return new KotlinFlow<string>((onNext, onComplete, onError, userData) =>
            Native_GetMealAnnouncementsCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData));
    }
}

public KotlinFlow<string> Treats(int count)
{
    if (_handle == IntPtr.Zero)
        throw new ObjectDisposedException(nameof(CatFeeder));
    return new KotlinFlow<string>((onNext, onComplete, onError, userData) =>
        Native_TreatsCollect(_handle, GetOrCreateScope(), count, onNext, onComplete, onError, userData));
}
```

`KotlinFlow<T>.GetAsyncEnumerator` bridges each emitted item through an unbounded `Channel<T>`, so `await foreach` sees items as they arrive:

```C#
public class KotlinFlow<T> : IAsyncEnumerable<T>
{
    private readonly NugetFlowCollectDelegate _startCollect;
    internal KotlinFlow(NugetFlowCollectDelegate startCollect) { _startCollect = startCollect; }

    public IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)
        => new KotlinFlowEnumerator<T>(_startCollect, cancellationToken);
}
```

Using it, from `IntegrationTests/FlowTests.cs`:

```C#
[Fact]
public async Task FlowProperty_CollectsAllMealAnnouncements_OreoEatsCycle()
{
    using var feeder = new CatFeeder("Oreo");
    var items = new List<string>();
    await foreach (var item in feeder.MealAnnouncements)
        items.Add(item);
    Assert.Equal(3, items.Count);
    Assert.Equal("Oreo is hungry", items[0]);
    Assert.Equal("Oreo is eating", items[1]);
    Assert.Equal("Oreo is full", items[2]);
}

[Fact]
public async Task Flow_MultipleSubscriptions_EachGetsFullSequence()
{
    // Flow is cold — Mylo gets the full announcement cycle every time we ask
    using var feeder = new CatFeeder("Mylo");
    var first = new List<string>();
    await foreach (var item in feeder.MealAnnouncements)
        first.Add(item);
    var second = new List<string>();
    await foreach (var item in feeder.MealAnnouncements)
        second.Add(item);
    Assert.Equal(first, second);
}

[Fact]
public async Task Flow_WithCancellation_ExitsCleanlyAfterFirstItem()
{
    using var feeder = new CatFeeder("Oreo");
    var cts = new CancellationTokenSource();
    var items = new List<string>();
    await foreach (var item in feeder.MealAnnouncements.WithCancellation(cts.Token))
    {
        items.Add(item);
        if (items.Count == 1) cts.Cancel();
    }
    Assert.True(items.Count >= 1);
}
```

## Limitations

Hot streams and several `Flow` positions are not yet supported (ROADMAP Phase 6):

- `SharedFlow<T>` (hot, multi-subscriber)
- `StateFlow<T>` (hot, always-current-value)
- `Flow<T>` as a function **parameter** (C# → Kotlin direction)
- Nullable `Flow<T>?`
- `Flow<T>` as a generic type argument (e.g. `Box<Flow<String>>`)
- `suspend fun` returning `Flow<T>` (the outer suspend is untreated separately from a non-suspend `Flow`-returning function)
- Flow backpressure (bounded `Channel<T>` with explicit resume signaling)

<seealso>
    <category ref="related">
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
        <a href="exceptions.md">Exceptions</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md">ADR-019: Suspend function mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/020-suspend-lambda-mapping.md">ADR-020: Suspend lambda mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/021-structured-concurrency.md">ADR-021: Structured concurrency</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/022-cancellation-token-support.md">ADR-022: CancellationToken support</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/025-async-disposable.md">ADR-025: AsyncDisposable</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/026-flow-mapping.md">ADR-026: Flow mapping</a>
    </category>
</seealso>

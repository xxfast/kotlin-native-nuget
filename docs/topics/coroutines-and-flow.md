# Coroutines and Flow

Kotlin coroutines map onto .NET's own async model: `suspend fun` becomes `async`/`Task<T>`, coroutine cancellation maps to `CancellationToken`, and structured concurrency means disposing the owning C# object cancels any coroutine it started. `Flow<T>` becomes `IAsyncEnumerable<T>`, consumable with `await foreach`.

| Kotlin | C# | Notes |
|---|---|---|
| `suspend fun` | `async` / `Task<T>` | overloads on a class or a sealed arm number `_2` on the native symbol only, no visible C# numbering, see [`suspend fun` overloads](#suspend-fun-overloads), [ADR-019](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md), [ADR-118](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md) |
| `suspend fun` returning `T?` | `async` / `Task<T?>` | nullable string, object, and primitive returns, [ADR-019](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md) |
| `suspend fun` returning `List<T>` / `Set<T>` / `Map<K, V>` | `Task<IReadOnlyList<T>>` / `Task<IReadOnlySet<T>>` / `Task<IReadOnlyDictionary<K, V>>` | spelled and read exactly as the property route spells the same type; any other generic return is a named skip, see [`suspend fun` returning a collection](#suspend-fun-returning-a-collection), [ADR-119](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/119-collection-returns-on-the-legacy-suspend-route.md) |
| `suspend () -> R` lambda | `KotlinSuspendFunc<R>` / `Task<R>` | [ADR-020](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/020-suspend-lambda-mapping.md) |
| structured concurrency | honoured on `Dispose()` | [ADR-021](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/021-structured-concurrency.md) |
| coroutine cancellation | `CancellationToken` | [ADR-022](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/022-cancellation-token-support.md) |
| in-flight async drain | `IAsyncDisposable` | graceful drain, [ADR-025](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/025-async-disposable.md) |
| `Flow<T>` | `IAsyncEnumerable<T>` | cold streams, [ADR-026](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/026-flow-mapping.md) |
| `StateFlow<T>` (incl. `MutableStateFlow<T>` narrowed via `.asStateFlow()`) | `KotlinStateFlow<T>` | hot, always-current-value; get-only `.Value` + `IAsyncEnumerable<T>`, [ADR-065](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/065-stateflow-mapping.md) |
| `MutableStateFlow<T>` (declared publicly) | `KotlinMutableStateFlow<T> : KotlinStateFlow<T>` | settable `.Value`, keyed on the declared type, [ADR-071](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/071-mutable-stateflow-mapping.md) |
| `StateFlow<T?>` (nullable element) | `KotlinStateFlow<T?>` | `.Value` and each emission are `T?`, [ADR-067](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/067-nullable-stateflow-mapping.md) |
| `StateFlow<T>?` (nullable member) | `KotlinStateFlow<T>?` | presence-probed; `null` before the member exists, [ADR-067](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/067-nullable-stateflow-mapping.md) |
| `suspend fun` returning `StateFlow<T>` | `Task<KotlinStateFlow<T>>` | outer suspend kept as `Task`, not collapsed to a sync return; class methods only, [ADR-068](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/068-suspend-returning-stateflow.md) |
| a class, `object`, sealed base, or sealed arm parameter on `Flow`/`StateFlow`/`suspend` | the mapped C# type, passed as `x._handle` | spelled exactly as the return position on the same member; see [Handle parameters on `Flow`, `StateFlow`, and `suspend` members](#handle-parameters-on-flow-stateflow-and-suspend-members), [ADR-122](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/122-handle-parameters-on-the-legacy-routes.md) |

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

## `suspend fun` overloads {id="suspend-fun-overloads"}

Two `suspend` overloads on one class (or a [sealed arm](interfaces-abstract-sealed.md#sealed-method-suspend-generated-c)) are one natural C# overload set with no visible numbering: the second takes `_2` on the native entry point and on the private extern's own C# name, off the same planner overload counter that numbers a plan-routed method ([ADR-090](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md)), read at the suspend route's composition sites ([ADR-118](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md)). From `test-library/src/nativeMain/kotlin/.../cat/AsyncCatService.kt`:

```kotlin
suspend fun fetchCat(name: String, lives: Int): Cat {
  delay(100.milliseconds)
  return Cat(name, lives)
}
```

Generated C#, both overloads staying `FetchCatAsync` while the private externs carry the number:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "asynccatservice_fetchCat_2_async")]
private static extern IntPtr Native_FetchCat_2Async(IntPtr handle, IntPtr scopeHandle, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, int lives, IntPtr callback, IntPtr userData);

public Task<Cat> FetchCatAsync(string name, int lives, CancellationToken cancellationToken = default)
```

Using it, from `IntegrationTests/SuspendMethodOverloadTests.cs`:

```C#
[Fact]
public async Task FetchCatAsync_SecondSuspendOverload_DispatchesToTheTwoParameterBody()
{
    using var service = new AsyncCatService("toys");

    using Cat mylo = await service.FetchCatAsync("Mylo", 3);

    Assert.Equal("Mylo", mylo.Name);
    Assert.Equal(3, mylo.Lives);
}
```

<note>
    <p>Two parameters that both cross as the identical wire type (e.g. two same-arity collection
    parameters) are still a legal C# overload set, but the number has to reach the private extern's
    <code>nativeName</code> as well as the entry point, not just the entry point: otherwise C#
    overload resolution picks the second overload by its distinct <i>public</i> signature, but its
    body silently calls the <i>first</i> overload's native symbol and returns the first overload's
    result. See <code>AsyncCatSitter.feed(List&lt;Int&gt;)</code> / <code>feed(Set&lt;String&gt;)</code> in
    <code>IntegrationTests/SuspendMethodOverloadTests.cs</code>.</p>
</note>

A top-level (non-class, non-arm) `suspend fun` overload pair still collides on one C symbol; see
[Limitations](#limitations).

## `suspend fun` returning a nullable type {id="suspend-fun-returning-a-nullable-type"}

A `suspend fun` returning `String?`, an object type, or a nullable primitive carries its `?` all the way through. From `test-library/src/nativeMain/kotlin/.../cat/AsyncFunctions.kt`:

```kotlin
suspend fun findCollarTag(catName: String): String? {
  delay(100.milliseconds)
  return if (catName == "Oreo") "Oreo - black with a white middle" else null
}

suspend fun findShelterCat(catName: String): Cat? {
  delay(100.milliseconds)
  return if (catName == "Mylo") Cat(catName) else null
}

suspend fun countTreatsLeft(catName: String): Int? {
  delay(100.milliseconds)
  return if (catName == "Oreo") 7 else null
}
```

Generated C# widens the `Task<T>` to `Task<T?>`; a nullable primitive shares the same `Nullable.GetUnderlyingType` unwrap the nullable `StateFlow` element further down this page uses:

```C#
public static Task<string?> FindCollarTagAsync(string catName, CancellationToken cancellationToken = default)
public static Task<Cat?> FindShelterCatAsync(string catName, CancellationToken cancellationToken = default)
public static Task<int?> CountTreatsLeftAsync(string catName, CancellationToken cancellationToken = default)
```

Using it, from `IntegrationTests/SuspendNullableReturnTests.cs`:

```C#
[Fact]
public async Task FindShelterCat_Mylo_ReturnsCat()
{
    using Cat? cat = await AsyncFunctions.FindShelterCatAsync("Mylo");
    Assert.NotNull(cat);
    Assert.Equal("Mylo", cat.Name);
}

[Fact]
public async Task CountTreatsLeft_Mylo_ReturnsNull()
{
    // Null must not arrive as a default 0.
    int? treats = await AsyncFunctions.CountTreatsLeftAsync("Mylo");
    Assert.Null(treats);
}
```

The same shape works identically on a class method (`AsyncCatService.findToyName`/`findAdoptedCat`/`countWhiskers`), suffixed `Async` as usual.

## `suspend fun` returning a collection {id="suspend-fun-returning-a-collection"}

A `suspend fun` returning `List<T>`, `Set<T>` or `Map<K, V>` (or a mutable variant) carries its type
arguments through, spelled exactly as a property of the same Kotlin type on the same class is
spelled, and read back through the same `nuget_list_*`/`nuget_set_*`/`nuget_map_*` helpers. From
`test-library/src/nativeMain/kotlin/.../issue122/AssignmentSample.kt`:

```kotlin
data class Existing(val members: List<Member>) : Assignment() {
  suspend fun fetch(limit: Int, offset: Int): List<Member> {
    delay(1.milliseconds)
    return members.drop(offset).take(limit)
  }
}

class Headcount(private val names: List<String>) {
  suspend fun ids(): Set<Int> { /* ... */ }
  suspend fun ages(): Map<String, Int> { /* ... */ }
  suspend fun tempers(): List<Temper> { /* ... */ }   // a bare enum element, cast back per element
}
```

Generated C#, the property route and the suspend route agreeing on one spelling:

```C#
public IReadOnlyList<Member> Members { get; }
public Task<IReadOnlyList<Member>> FetchAsync(int limit, int offset, CancellationToken cancellationToken = default)

public Task<IReadOnlySet<int>> IdsAsync(CancellationToken cancellationToken = default)
public Task<IReadOnlyDictionary<string, int>> AgesAsync(CancellationToken cancellationToken = default)
public Task<IReadOnlyList<Temper>> TempersAsync(CancellationToken cancellationToken = default)
```

Using it, from `IntegrationTests/Issue122Tests.cs`:

```C#
[Fact]
public async Task FetchAsync_ListReturnOnASealedArm_AgreesWithThePropertyRoute()
{
    using var factory = new AssignmentFactory();
    using Existing existing = factory.Existing([oreo, mylo, biscuit]);

    IReadOnlyList<Member> viaProperty = existing.Members;
    IReadOnlyList<Member> viaSuspend = await existing.FetchAsync(limit: 3, offset: 0);

    Assert.Equal(viaProperty.Select(m => m.Id), viaSuspend.Select(m => m.Id));
}
```

The awaited handle is materialised through `NugetMarshal.ReadList<T>` (and `ReadSet`/`ReadMap`),
whose `finally` disposes the wire handle, so the result is a fresh managed collection that owns
nothing native. The same shape works on an ordinary class, on a
[sealed arm](interfaces-abstract-sealed.md#sealed-method-suspend-generated-c), and at top level.

<note>
    <p>Any <i>other</i> generic <code>suspend</code> return (<code>Pair&lt;A, B&gt;</code>,
    <code>Result&lt;T&gt;</code>, <code>Flow&lt;T&gt;</code>, a <b>nullable</b> collection
    <code>List&lt;T&gt;?</code>, a user generic) is absent from C# and named
    <code>SKIPPED_UNSUPPORTED_RETURN</code>, rather than rendered over the type's bare simple name
    (<code>Task&lt;List&gt;</code>, <code>Task&lt;Pair&gt;</code>), which <code>packNuget</code>
    accepts and the consumer's compiler does not. A <code>StateFlow&lt;T&gt;</code> return is
    unaffected: it has its own mapping below.</p>
</note>


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

<note>
    <p>
        The generated wrapper's job handle and its <code>CancellationTokenRegistration</code> are
        released exactly once, whichever of the caller or the completion callback arrives second.
        This matters for a suspend body with no suspension point, which can complete before the
        native call has even returned the job handle to the caller
        (<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md">ADR-019</a>'s
        2026-09-09 amendment).
    </p>
</note>

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

### `Flow<T>` of a cross-module element type

A `Flow<T>` element type is emitted **qualified**, `global::{namespace}.{Type}`, not by its bare
simple name. This matters once the element type is admitted from a different Gradle module through
the export reachability closure ([ADR-066](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md);
see [The nuget {} DSL](nuget-dsl.md)): an unqualified name would only compile by accident if the
element type's namespace happened to coincide with the declaring class's own. From
`test-library/src/nativeMain/kotlin/.../Newsroom.kt`, where `TopStory` lives one module away in
`:test-models`:

```kotlin
fun stream(): Flow<TopStory> = flow {
  emit(TopStory("Oreo escapes the cardboard box (again)", 1, Byline("Mylo")))
  emit(TopStory("Mylo naps in a sunbeam for six hours straight", 2, null))
}
```

```C#
public KotlinFlow<global::TestLibrary.Models.TopStory> Stream()
{
    if (_handle == IntPtr.Zero)
        throw new ObjectDisposedException(nameof(Newsroom));
    return new KotlinFlow<global::TestLibrary.Models.TopStory>((onNext, onComplete, onError, userData) =>
        Native_StreamCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData));
}
```

## `StateFlow<T>`

`StateFlow<T>` is the hot, conflated, always-current-value stream: it always has a `value` readable
synchronously, replays that current value to every new collector, and conflates intermediate updates.
From `CatMoodTracker.kt`:

```kotlin
class CatMoodTracker(private val catName: String) {
  private val _energyLevel: MutableStateFlow<Int> = MutableStateFlow(100)
  val energyLevel: StateFlow<Int> = _energyLevel.asStateFlow()

  private val _mood: MutableStateFlow<String> = MutableStateFlow("sleepy")
  val mood: StateFlow<String> = _mood.asStateFlow()

  private val _playmate: MutableStateFlow<Cat> = MutableStateFlow(Cat(catName))
  val playmate: StateFlow<Cat> = _playmate.asStateFlow()

  fun moodReport(): StateFlow<String> = mood

  fun bumpEnergy(amount: Int) {
    _energyLevel.value += amount
  }

  fun setMood(newMood: String) {
    _mood.value = newMood
  }
}
```

A `StateFlow<T>` (or `MutableStateFlow<T>`, bound as a read-only view) property or non-suspend
function return becomes a `KotlinStateFlow<T> : KotlinFlow<T>`. It reuses `KotlinFlow<T>`'s
`_collect` export and enumerator unchanged, and adds a synchronous `T Value { get; }` backed by a
second, dedicated `_value` export:

```C#
public KotlinStateFlow<int> EnergyLevel
{
    get
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(CatMoodTracker));
        return new KotlinStateFlow<int>((onNext, onComplete, onError, userData) =>
            Native_GetEnergyLevelCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),
            () => Native_GetEnergyLevelValue(_handle));
    }
}
```

`KotlinStateFlow<T>` itself is generated once, alongside `KotlinFlow<T>`:

```C#
public class KotlinStateFlow<T> : KotlinFlow<T>
{
    private readonly Func<IntPtr> _readValue;

    internal KotlinStateFlow(NugetFlowCollectDelegate startCollect, Func<IntPtr> readValue)
        : base(startCollect)
    {
        _readValue = readValue;
    }

    public T Value => NugetMarshal.FromHandle<T>(_readValue());
}
```

Using it, from `IntegrationTests/StateFlowTests.cs`:

```C#
[Fact]
public void StateFlowProperty_IntType_ValueReturnsCurrentValueSynchronously_OreoStartsFullOfBeans()
{
    // Oreo starts at full energy -- no awaiting required to find out how zoomy he is
    using var tracker = new CatMoodTracker("Oreo");
    int current = tracker.EnergyLevel.Value;
    Assert.Equal(100, current);
}

[Fact]
public async Task StateFlowProperty_AwaitForeach_ReplaysCurrentValueAsFirstElement_OreosEnergyReadingStartsCurrent()
{
    // A brand-new subscriber immediately sees Oreo's CURRENT energy level, replay-1 semantics
    using var tracker = new CatMoodTracker("Oreo");
    var seen = new List<int>();
    var cts = new CancellationTokenSource();
    await foreach (var level in tracker.EnergyLevel.WithCancellation(cts.Token))
    {
        seen.Add(level);
        cts.Cancel(); // StateFlow never completes on its own -- must bound with cancellation
    }
    Assert.Equal(100, seen[0]);
}

[Fact]
public void StateFlowProperty_ReturnsKotlinStateFlow_IsAKotlinFlow_UpcastsLikeKotlinsOwn()
{
    // KotlinStateFlow<T> IS-A KotlinFlow<T> / IAsyncEnumerable<T> -- mirrors Kotlin's StateFlow : Flow
    using var tracker = new CatMoodTracker("Oreo");
    IAsyncEnumerable<int> asAsyncEnumerable = tracker.EnergyLevel;
    KotlinFlow<int> asKotlinFlow = tracker.EnergyLevel;
    Assert.NotNull(asAsyncEnumerable);
    Assert.NotNull(asKotlinFlow);
}
```

<note>
    <p>A <code>StateFlow&lt;T&gt;</code> <code>await foreach</code> never terminates on its own: it is
    hot and open, so <code>onComplete</code> is never fired. Bound it with a
    <code>CancellationToken</code> or a <code>break</code>, exactly as the tests above do.</p>
</note>

### `StateFlow<T>` / `Flow<T>` of a sealed class

`T` can itself be a sealed base class. `.Value` and `await foreach` both materialise the correct
generated subclass, dispatched through the sealed base's own generated `FromHandle(IntPtr)`
discriminator. From `test/issue40/Issue40Sample.kt`:

```kotlin
sealed class LoadState {
  data object Idle : LoadState()
  data class Loading(val progress: Int) : LoadState()
  data class Loaded(val payload: String) : LoadState()
}

class Loader {
  private val _state: MutableStateFlow<LoadState> = MutableStateFlow(LoadState.Idle)
  val state: StateFlow<LoadState> = _state.asStateFlow()
  val history: Flow<LoadState> = flowOf(
    LoadState.Idle,
    LoadState.Loading(50),
    LoadState.Loaded("done"),
  )
}
```

Using it, from `IntegrationTests/Issue40Tests.cs`:

```C#
using var loader = new Loader();
loader.Advance();

using LoadState current = loader.State.Value;
var loading = Assert.IsType<LoadState.Loading>(current);
Assert.Equal(50, loading.Progress);
```

```C#
await foreach (LoadState state in loader.History)
    seen.Add(state);
```

`MutableStateFlow<T>` at a property or function-return position binds as the same read-only
`KotlinStateFlow<T>` view **when the declared type is `StateFlow<T>`**, for example the ubiquitous
`private val _x = MutableStateFlow(...)` / `val x: StateFlow<T> = _x` idiom above. Object-typed
elements (`StateFlow<Cat>`) follow ADR-005: each `.Value` read hands back a fresh, disposable
wrapper, not a cached one. A `MutableStateFlow<T>`-**declared** member gets a settable `.Value`
instead; see the next section.

## Settable `.Value` on `MutableStateFlow<T>`

A member whose **declared** type is `MutableStateFlow<T>`, not narrowed to `StateFlow<T>`, surfaces
as `KotlinMutableStateFlow<T> : KotlinStateFlow<T>` with a settable `.Value`. Detection keys on the
declared type only, so this is purely additive: every `StateFlow<T>`-declared member (including the
`.asStateFlow()`-narrowed idiom above) is completely unaffected and stays get-only. From
`CatMoodTracker.kt`:

```kotlin
/** MutableStateFlow<Int> -- primitive element, no conversion at the write seam. */
val treatCount: MutableStateFlow<Int> = MutableStateFlow(0)

/** MutableStateFlow<String> -- needs conversion (string marshalling) at the write seam. */
val collarColour: MutableStateFlow<String> = MutableStateFlow("red")

/** MutableStateFlow<Cat> -- object element; crosses as a handle in both directions. */
val favouriteToy: MutableStateFlow<Cat> = MutableStateFlow(Cat("Mittens"))

/**
 * MutableStateFlow<T> as a non-suspend function return, sharing [treatCount]'s storage so a
 * write through one surface position is observable through the other.
 */
fun treatJar(): MutableStateFlow<Int> = treatCount
```

The generated `KotlinMutableStateFlow<T>` extends `KotlinStateFlow<T>` and `new`-shadows `Value`
with a setter, since C# does not allow an `override` to add a `set` accessor to a get-only base
property (`CS0546`):

```C#
public class KotlinMutableStateFlow<T> : KotlinStateFlow<T>
{
    private readonly Action<T> _writeValue;

    internal KotlinMutableStateFlow(
        NugetFlowCollectDelegate startCollect,
        Func<IntPtr> readValue,
        Action<T> writeValue,
        IntPtr ownedHandle = default)
        : base(startCollect, readValue, ownedHandle)
    {
        _writeValue = writeValue;
    }

    public new T Value
    {
        get => base.Value;
        set => _writeValue(value);
    }
}
```

The generated property getter for `treatCount`:

```C#
public KotlinMutableStateFlow<int> TreatCount
{
    get
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(CatMoodTracker));
        return new KotlinMutableStateFlow<int>((onNext, onComplete, onError, userData) =>
            Native_GetTreatCountCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),
            () => Native_GetTreatCountValue(_handle),
            v =>
            {
                Native_SetTreatCountValue(_handle, v, out IntPtr error);
                if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
            });
    
    }
}
```

Using it, from `IntegrationTests/MutableStateFlowTests.cs`:

```C#
[Fact]
public void SettableValue_IntElement_NoConversion_MyloGetsSevenTreats()
{
    // Mylo earns 7 treats -- a plain Int write, the cheapest seam on the write path
    using var tracker = new CatMoodTracker("Mylo");
    tracker.TreatCount.Value = 7;
    Assert.Equal(7, tracker.TreatCount.Value);
    Assert.Equal(7, tracker.TreatsGivenSoFar()); // the write really landed in Kotlin, not just C#
}

[Fact]
public void SettableValue_ObjectElement_CrossesAsAHandle_MyloGetsANewFavouriteToy()
{
    // Mylo's favourite toy is a Cat -- an object element, unwrapped via the handle
    using var tracker = new CatMoodTracker("Mylo");
    using var mouse = new Cat("Mouse", 9);
    tracker.FavouriteToy.Value = mouse;
    using var toy = tracker.FavouriteToy.Value; // fresh wrapper per read (ADR-005)
    Assert.Equal("Mouse", toy.Name);
}

[Fact]
public void SettableValue_PropertyAndFunctionReturn_ShareStorage_OreosTreatJarMatchesTreatCount()
{
    // TreatJar() is a non-suspend function return sharing storage with the TreatCount property
    using var tracker = new CatMoodTracker("Oreo");
    tracker.TreatJar().Value = 11;
    Assert.Equal(11, tracker.TreatCount.Value);
}
```

<note>
    <p>The setter carries an <code>errorOut</code>, unlike the getter. Kotlin's
    <code>MutableStateFlow.value</code> setter conflates by calling <code>Any.equals</code> on the
    <b>previous</b> value, so a throwing <code>equals</code> propagates out of the write. This is a
    deliberate asymmetry with the get-only <code>.Value</code>'s exception-free read.</p>
</note>

```C#
[Fact]
public void SettableValue_SetterExceptionPropagation_OreosGrudgeAgainstTheVetCannotBeReplaced()
{
    // Grudge.equals always throws -- MutableStateFlow.value's setter conflates by Any.equals
    // on the PREVIOUS value, so the throw propagates out of the setter export via errorOut.
    using var tracker = new CatMoodTracker("Oreo");
    using var newGrudge = new Grudge("the carrier");
    Assert.Throws<KotlinInvalidOperationException>(() => tracker.Grudge.Value = newGrudge);
}
```

A `.Value` write from an arbitrary .NET threadpool thread is safe, verified by
`SettableValue_WriteFromThreadpoolThread_MyloGetsHisTreatFromABackgroundThread`. A write is also
observed by a live collector, replay-1 and conflated, exactly like the read-only case above, and
`KotlinMutableStateFlow<T>` upcasts to `KotlinStateFlow<T>`, `KotlinFlow<T>`, and
`IAsyncEnumerable<T>`, mirroring Kotlin's own `MutableStateFlow : StateFlow : Flow`.

## Nullable `StateFlow<T?>` and `StateFlow<T>?`

A `StateFlow` can be nullable in two independent ways, and they can combine: the *element* can be
nullable (`StateFlow<T?>`, the tracker always exists but its current value can be null) or the
*member itself* can be nullable (`StateFlow<T>?`, the whole property or return can be absent). From
`CatMoodTracker.kt`:

```kotlin
private val _nickname: MutableStateFlow<String?> = MutableStateFlow(null)
val nickname: StateFlow<String?> = _nickname.asStateFlow()   // nullable reference element

private val _streak: MutableStateFlow<Int?> = MutableStateFlow(null)
val streak: StateFlow<Int?> = _streak.asStateFlow()          // nullable value element

private var _maybeMood: MutableStateFlow<String>? = null
val maybeMood: StateFlow<String>? get() = _maybeMood?.asStateFlow()   // nullable member

private var _maybeStreak: MutableStateFlow<Int?>? = null
val maybeStreak: StateFlow<Int?>? get() = _maybeStreak?.asStateFlow()   // both, together
```

A nullable element surfaces as `KotlinStateFlow<T?>`; `.Value` and every `await foreach` emission
are `T?`. A reference element (`String?`, `Cat?`) needs no marshalling change, a null current value
is just `IntPtr.Zero`. A value element (`Int?`) reuses the shipped per-primitive box readers through
a `Nullable<T>`-aware branch in `NugetMarshal.FromHandle<T>`:

```C#
public KotlinStateFlow<string?> Nickname { get; }   // reference element, unchanged marshalling
public KotlinStateFlow<int?> Streak { get; }        // value element, Nullable<T>-aware unwrap
```

A nullable member surfaces as `KotlinStateFlow<T>?`. There is no single handle to null-check for a
whole StateFlow (it is reconstructed from per-member `_collect` / `_value` exports), so the getter
calls a dedicated `_has_value` presence-probe export first and returns `null` before constructing
anything:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catmoodtracker_get_maybeMood_has_value")]
[return: MarshalAs(UnmanagedType.I1)]
private static extern bool Native_GetMaybeMoodHasValue(IntPtr handle);

public KotlinStateFlow<string>? MaybeMood
{
    get
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(CatMoodTracker));
        if (!Native_GetMaybeMoodHasValue(_handle))
            return null;
        return new KotlinStateFlow<string>((onNext, onComplete, onError, userData) =>
            Native_GetMaybeMoodCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),
            () => Native_GetMaybeMoodValue(_handle));
    }
}
```

Both compose: `maybeStreak: StateFlow<Int?>?` becomes `KotlinStateFlow<int?>?`, the presence probe
guarding a nullable-element `KotlinStateFlow<int?>`.

Using it, from `IntegrationTests/NullableStateFlowTests.cs`:

```C#
[Fact]
public void NullableValueElement_StartsNull_NotDefaultZero_OreosStreakIsUntracked()
{
    // Oreo's win streak hasn't started -- must be null, NOT default(int) == 0.
    using var tracker = new CatMoodTracker("Oreo");
    KotlinStateFlow<int?> streak = tracker.Streak;
    Assert.Null(streak.Value);
}

[Fact]
public void NullableMember_AbsentUntilTrackingStarts_MyloHasNoMoodTrackingYet()
{
    // The whole StateFlow is null until StartTracking() is called -- not merely a null value
    using var tracker = new CatMoodTracker("Mylo");
    Assert.Null(tracker.MaybeMood);
}

[Fact]
public void NullableMemberAndValueElement_PresentButValueNull_OreoTracksAnUntrackedStreak()
{
    // The member becomes present, but its current value is still null -- both nulls at once,
    // and they must be distinguishable: MaybeStreak itself is non-null, MaybeStreak.Value is null.
    using var tracker = new CatMoodTracker("Oreo");
    tracker.StartStreakTracking(null);

    KotlinStateFlow<int?>? maybeStreak = tracker.MaybeStreak;
    Assert.NotNull(maybeStreak);
    Assert.Null(maybeStreak!.Value);
}
```

<note>
    <p>A null current value crossing over <code>await foreach</code> is a genuine emission, not the
    cancel signal. Both are distinguished by the same <code>isCancelled</code> byte the base
    <code>KotlinFlow&lt;T&gt;</code> uses: <code>(null, isCancelled=0)</code> is a null element,
    <code>(null, isCancelled=1)</code> is cancellation.</p>
</note>

<note>
    <p>The nullable-member <code>_has_value</code> probe is generated symmetrically for both a
    nullable <code>StateFlow&lt;T&gt;?</code> property and a non-suspend function returning a
    nullable <code>StateFlow&lt;T&gt;?</code>. Only the property shape has an integration test
    today; the function-return shape compiles but is untested.</p>
</note>

## `suspend fun` returning `StateFlow<T>`

A `suspend fun` can genuinely suspend before handing back a `StateFlow<T>`, for example to build
the holder lazily. The outer suspend is **kept as a `Task`**, it is not collapsed into a
synchronous return: composing ADR-019's `suspend` → `Task` mapping over ADR-065's `StateFlow<T>` →
`KotlinStateFlow<T>` mapping gives `Task<KotlinStateFlow<T>> XxxAsync()`. From `CatMoodTracker.kt`:

```kotlin
suspend fun awaitMoodReport(): StateFlow<String> {
  kotlinx.coroutines.delay(1)
  return mood
}

suspend fun awaitPlaymateReport(): StateFlow<Cat> {
  kotlinx.coroutines.delay(1)
  return playmate
}
```

Both return the *same* underlying `MutableStateFlow` already exposed through `mood`/`moodReport()`
and `playmate` respectively, so mutation stays observable across every surface position. The
generated C#:

```C#
public Task<KotlinStateFlow<string>> AwaitMoodReportAsync(CancellationToken cancellationToken = default)
public Task<KotlinStateFlow<global::TestLibrary.Cat.Cat>> AwaitPlaymateReportAsync(CancellationToken cancellationToken = default)
```

The awaited `StateFlow` handle is no longer re-derivable from the parent object plus arguments (the
call that produced it was itself suspend), so its `_collect`/`_value` reads go through a shared
generic pair keyed on the flow's own handle, `nuget_stateflow_collect` / `nuget_stateflow_value`
(`NugetStateFlowNative` in the generated C#), rather than the per-member exports ADR-065 uses for a
non-suspend `StateFlow`. The resulting `KotlinStateFlow<T>` also owns that handle and disposes it
via `IDisposable`.

<note>
    <p>The one `await` is the outer suspend. Everything after it, <code>.Value</code>,
    <code>await foreach</code>, the <code>IAsyncEnumerable&lt;T&gt;</code> upcast, is ADR-065's
    <code>KotlinStateFlow&lt;T&gt;</code> unchanged.</p>
</note>

Using it, from `IntegrationTests/SuspendStateFlowTests.cs`:

```C#
[Fact]
public async Task AwaitMoodReport_ValueReadsCurrentValueSynchronouslyAfterAwait_OreoIsSleepyByDefault()
{
    // After the single await, .Value is synchronous thereafter -- exactly ADR-065's contract.
    using var tracker = new CatMoodTracker("Oreo");
    KotlinStateFlow<string> report = await tracker.AwaitMoodReportAsync();
    Assert.Equal("sleepy", report.Value);
}

[Fact]
public async Task AwaitPlaymateReport_ValueReturnsFreshDisposableWrapper_OreoGreetsHisPlaymate()
{
    // Object-element .Value returns a fresh, working, disposable Cat wrapper (ADR-005).
    using var tracker = new CatMoodTracker("Oreo");
    using var playmate = (await tracker.AwaitPlaymateReportAsync()).Value;
    Assert.Equal("Oreo", playmate.Name);
    Assert.Equal("Meow! My name is Oreo", playmate.Meow());
}
```

<note>
    <p>Only a class method is supported in v1. A top-level <code>suspend fun</code> returning a
    <code>StateFlow&lt;T&gt;</code> (no parent scope) is deferred.</p>
</note>

## Collection parameters on `Flow`, `StateFlow`, and `suspend` members

A `List`/`Set`/`Map` parameter on a `Flow`-returning, `StateFlow`-returning, or `suspend` member crosses as the real collection type, never `IntPtr`. From `test-library/src/nativeMain/kotlin/.../cat/TreatBoard.kt`:

```kotlin
class TreatBoard {
  fun served(kinds: List<String>): StateFlow<String> =
    MutableStateFlow(kinds.joinToString(", ") { "$it x2" })

  fun servings(kinds: List<String>): Flow<String> = kinds.asFlow()

  suspend fun forget(ids: Set<String>): Int {
    delay(1)
    return ids.size
  }
}
```

Generated C# builds the wire container immediately before each native call and disposes it in a `finally`, one lexical level inside whichever closure makes the call:

```C#
public KotlinStateFlow<string> Served(IReadOnlyList<string> kinds)
{
    if (_handle == IntPtr.Zero)
        throw new ObjectDisposedException(nameof(TreatBoard));
    return new KotlinStateFlow<string>((onNext, onComplete, onError, userData) =>
        {
            IntPtr kindsHandle = NugetMarshal.CreateList(kinds);
            try
            {
                return Native_ServedCollect(_handle, GetOrCreateScope(), kindsHandle, onNext, onComplete, onError, userData);
            }
            finally
            {
                NugetMarshal.Dispose(kindsHandle);
            }
        },
        () =>
        {
            IntPtr kindsHandle = NugetMarshal.CreateList(kinds);
            try
            {
                return Native_ServedValue(_handle, kindsHandle);
            }
            finally
            {
                NugetMarshal.Dispose(kindsHandle);
            }
        });
}
```

Using it, from `IntegrationTests/LegacyRouteCollectionParameterTests.cs`:

```C#
[Fact]
public void Served_StateFlowWithAListParameter_ReadsThroughTheCollection()
{
    using var board = new TreatBoard();

    Assert.Equal("biscuit x2, milo x2", board.Served(["biscuit", "milo"]).Value);
}
```

<note>
    <p>Reading <code>.Value</code> re-marshals the whole collection on every read (Kotlin has no
    <code>launch</code> on this route, so there is nothing to hoist the conversion above), and the
    C# lambda captures the caller's <code>IReadOnlyList&lt;T&gt;</code> by reference: mutating it
    after the call changes what a later <code>.Value</code> read sees. The <code>_collect</code>
    path takes a snapshot at subscription time instead.</p>
</note>

Any other generic-typed parameter on these routes that is not a supported collection (`Pair<A, B>`, `Array<T>`, a lambda parameter on a `Flow`-returning member) skips the member named, rather than emitting non-compiling Kotlin:

```
w: [nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping TreatBoard.paired: a Flow-returning or suspend member can take a primitive/String, a List/Set/Map, or a class/object/sealed-type handle, but not entry: Pair<String, Int>. pass a class, object or sealed type, a List/Set/Map, or a primitive/String, or expose the values as separate parameters
```

The same refusal, named `SKIPPED_UNSUPPORTED_INPUT`, covers every non-scalar, non-generic shape too: an enum, `Instant`/`Duration`/`Uuid`, a value class, an interface, an unexported class, or a nullable object parameter (`Observation?`). See [Handle parameters on `Flow`, `StateFlow`, and `suspend` members](#handle-parameters-on-flow-stateflow-and-suspend-members) for what *does* bind at that position.

## Handle parameters on `Flow`, `StateFlow`, and `suspend` members {id="handle-parameters-on-flow-stateflow-and-suspend-members"}

A class, `object`, sealed base, or sealed subclass parameter on the same routes crosses as the mapped C# type, spelled exactly as the return position on the same member already spells it, and passed through the argument's own `_handle`. Nested and sibling sealed arms, the sealed base itself, and an ordinary exported class are all the same shape, and a collection parameter and a handle parameter can appear on the same member. From `test-library/src/nativeMain/kotlin/.../issue126/ObservationRadio.kt`:

```kotlin
class ObservationRadio {
  fun watch(observation: Observation.Alive): StateFlow<String> {
    val state = MutableStateFlow("alive:${observation.cat.name}")
    scope.launch {
      repeat(2) { tick ->
        delay(20)
        state.value = "alive:${observation.cat.name}:${tick + 1}"
      }
    }
    return state
  }

  fun watch(observation: Observation.Dead): StateFlow<String> =
    MutableStateFlow("dead:${observation.cause}")

  fun watch(flat: Label): StateFlow<String> = MutableStateFlow("label:${flat.text}")

  fun watch(observation: Observation): StateFlow<String> = MutableStateFlow(
    when (observation) {
      Observation.Superposition -> "base:superposition"
      is Observation.Alive -> "base:alive:${observation.cat.name}"
      is Observation.Dead -> "base:dead:${observation.cause}"
    },
  )

  fun watch(cat: Cat): StateFlow<String> = MutableStateFlow("cat:${cat.name}")

  fun tally(kinds: List<String>, observation: Observation.Alive): StateFlow<Int> =
    MutableStateFlow(kinds.size * 10 + observation.cat.name.length)
}
```

### Generated C# {id="handle-parameter-generated-c"}

Five distinct signatures where there was one, each spelled the way the return position on the same class already spells it:

```C#
public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Observation.Alive observation)
{
    if (_handle == IntPtr.Zero)
        throw new ObjectDisposedException(nameof(ObservationRadio));
    return new KotlinStateFlow<string>((onNext, onComplete, onError, userData) =>
        Native_WatchCollect(_handle, GetOrCreateScope(), observation._handle, onNext, onComplete, onError, userData),
        () => Native_WatchValue(_handle, observation._handle));
}

public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Observation.Dead observation)
public KotlinStateFlow<string> Watch(global::TestLibrary.Issue54.Label flat)
public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Observation observation)
public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Cat cat)
```

A member mixing a collection parameter and a handle parameter builds and disposes the collection's wire container as usual, and passes the handle argument straight through with no conversion at the seam:

```C#
public KotlinStateFlow<int> Tally(IReadOnlyList<string> kinds, global::TestLibrary.Cat.Observation.Alive observation)
{
    if (_handle == IntPtr.Zero)
        throw new ObjectDisposedException(nameof(ObservationRadio));
    return new KotlinStateFlow<int>((onNext, onComplete, onError, userData) =>
        {
            IntPtr kindsHandle = NugetMarshal.CreateList(kinds);
            try
            {
                return Native_TallyCollect(_handle, GetOrCreateScope(), kindsHandle, observation._handle, onNext, onComplete, onError, userData);
            }
            finally
            {
                NugetMarshal.Dispose(kindsHandle);
            }
        },
        ...);
}
```

The Kotlin export declares the ABI slot as `COpaquePointer`, exactly like a collection parameter, and dereferences it into a local **before** `scope.launch`:

```kotlin
val observationArg = observation.asStableRef<io.github.xxfast.kotlin.native.nuget.test.cat.Observation.Alive>().get()
```

so the coroutine captures a strong Kotlin reference. A C# consumer disposing its argument wrapper mid-flow cannot invalidate what the coroutine is still reading.

### Using it from C# {id="handle-parameter-using-it-from-c"}

From `IntegrationTests/LegacyRouteHandleParameterTests.cs`:

```C#
[Fact]
public void Watch_WithANestedSealedArm_TakesTheArmTypeAndEmitsItsValue()
{
    using var radio = new ObservationRadio();
    using Observation observation = ObservationKt.OpenBox("Oreo");
    Observation.Alive alive = Assert.IsType<Observation.Alive>(observation);

    Assert.Equal("alive:Oreo", radio.Watch(alive).Value);
}

[Fact]
public void Watch_WithTheSealedBase_Discriminates()
{
    using var radio = new ObservationRadio();
    using Observation alive = ObservationKt.OpenBox("Oreo");
    using Observation dead = ObservationKt.OpenBox("Rex");
    using Observation unknown = ObservationKt.PeekBox();

    Assert.Equal("base:alive:Oreo", radio.Watch(alive).Value);
    Assert.Equal("base:dead:The cat was not Rex", radio.Watch(dead).Value);
    Assert.Equal("base:superposition", radio.Watch(unknown).Value);
}
```

<note>
    <p>The crossing mints no handle on either side: C# passes a <code>_handle</code> its wrapper
    already owns, and Kotlin takes no <code>StableRef</code> of its own. The
    <code>HandleParameter_StateFlowValueRead_ReturnsToBaseline</code> row in
    <code>LeakTests/LiveHandleTests.cs</code> is what proves it: a fix that took ownership to keep
    the argument alive across the flow would show up there as a per-crossing leak and nowhere
    else.</p>
</note>

## Limitations

`Flow`/`StateFlow` collection and `suspend`/`async` dispatch their callbacks through a static
`[UnmanagedCallersOnly]` thunk, AOT-safe, see
[Publishing Kotlin to C#: AOT and trimming](forward-overview.md#aot-and-trimming)
([ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md)).

Hot streams and several `Flow` positions are not yet supported (ROADMAP Phase 6):

- `SharedFlow<T>` (hot, multi-subscriber)
- `StateFlow<SomeEnum>` / `MutableStateFlow<SomeEnum>`: `NugetMarshal.FromHandle<T>` has no enum branch, so an enum element is unsupported on the `.Value` read path (pre-existing, predates both `StateFlow` mappings)
- `CompareAndSet` / `Update` / `Emit` / `TryEmit` / `ReplayCache` / `SubscriptionCount` on `MutableStateFlow<T>`
- Nullable element write (`MutableStateFlow<T?>.Value = ...`), nullable member write, and `suspend fun` returning `MutableStateFlow<T>`
- A `suspend fun` returning a nullable collection (`List<T>?`), a collection of a sealed base (`List<Shape>`), or any other generic type (`Pair`, `Result<T>`, `Flow<T>`): absent and named `SKIPPED_UNSUPPORTED_RETURN` ([ADR-119](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/119-collection-returns-on-the-legacy-suspend-route.md))
- Reassigning the whole `MutableStateFlow<T>` member itself (a `var` member, not just its `.value`)
- Top-level `suspend fun` returning `StateFlow<T>` (no parent class scope; class methods only in v1)
- `StateFlow<T>` as a function parameter or as a generic type argument
- `suspend fun` returning a nullable `StateFlow<T?>` / `StateFlow<T>?`, and nullable `StateFlow` as a function parameter or generic type argument
- `Boolean?` / `Char?` value elements on a nullable `StateFlow` (the same width fragility as ADR-061)
- Nullable `SharedFlow<T>` (follows `SharedFlow<T>` itself, still deferred)
- `INotifyPropertyChanged` adapter over `KotlinStateFlow<T>` (opt-in convenience, not core)
- `Flow<T>` as a function **parameter** (C# → Kotlin direction)
- Nullable `Flow<T>?`
- `Flow<T>` as a generic type argument (e.g. `Box<Flow<String>>`)
- `suspend fun` returning `Flow<T>` (would follow the same outer-suspend-kept-as-`Task` decision [ADR-068](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/068-suspend-returning-stateflow.md) made for its `StateFlow` sibling, not yet implemented)
- Flow backpressure (bounded `Channel<T>` with explicit resume signaling)
- A collection as a `Flow`/`StateFlow` **element** (`StateFlow<List<String>>`, as opposed to a collection parameter) has no fixture and no confirmed coverage today
- A nullable collection parameter (`List<T>?`) on a `Flow`/`StateFlow`/`suspend` member (ADR-067 territory, not widened by [ADR-114](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/114-collection-parameters-on-legacy-flow-and-suspend-routes.md))
- An enum parameter on a `Flow`/`StateFlow`-returning or `suspend` member is refused rather than bound, even though `(int)x` / `entries[x]` would express it ([ADR-122](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/122-handle-parameters-on-the-legacy-routes.md) Alternative 6, deferred)
- A **top-level** `suspend fun` overload pair (not a class or sealed-arm method) still collides on one C symbol: the top-level suspend route has no planner entry to number from ([ADR-118](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md))
- Two `suspend` overloads differing only in reference nullability still render `CS0111` in the generated file rather than failing the round, since async members bypass the C# signature-collision guard

A `suspend inline fun <reified T> Receiver.f(...): Result<T>` extension has no bridge at all: `inline`
plus `reified` erases at the C ABI, and `suspend` needs a concrete continuation type, so the
combination has no working route even though `suspend` and a generic type parameter each work on
their own. It is skipped with a `SKIPPED_UNSUPPORTED_COMBINATION` diagnostic naming the extension,
rather than the raw `Function1`/`Result` this generated before
([ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)).

<seealso>
    <category ref="related">
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
        <a href="exceptions.md">Exceptions</a>
        <a href="extensions.md">Extensions</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md">ADR-019: Suspend function mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/020-suspend-lambda-mapping.md">ADR-020: Suspend lambda mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/021-structured-concurrency.md">ADR-021: Structured concurrency</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/022-cancellation-token-support.md">ADR-022: CancellationToken support</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/025-async-disposable.md">ADR-025: AsyncDisposable</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/026-flow-mapping.md">ADR-026: Flow mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/065-stateflow-mapping.md">ADR-065: StateFlow mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066: Forward export reachability closure</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/067-nullable-stateflow-mapping.md">ADR-067: Nullable StateFlow mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/068-suspend-returning-stateflow.md">ADR-068: suspend fun returning StateFlow&lt;T&gt;</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/071-mutable-stateflow-mapping.md">ADR-071: MutableStateFlow&lt;T&gt; mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md">ADR-102: AOT-safe forward callbacks</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/114-collection-parameters-on-legacy-flow-and-suspend-routes.md">ADR-114: Collection parameters on the Flow and suspend legacy routes</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/118-suspend-route-sealed-arm-owners-and-overload-numbering.md">ADR-118: Suspend route: sealed-arm owners and overload numbering</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/122-handle-parameters-on-the-legacy-routes.md">ADR-122: Handle parameters on the legacy Flow and suspend routes</a>
    </category>
</seealso>

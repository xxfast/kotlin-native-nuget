# Lambdas and callbacks

Function types cross the bridge in three distinct shapes depending on which side owns the lambda and how long it lives.

1. **Kotlin → C#**: a Kotlin lambda *property* or *return value* is wrapped in a `KotlinFunc<...>` handle the C# caller invokes.
2. **C# → Kotlin, per-call**: a C# lambda passed as a *parameter* into a Kotlin function is pinned for the duration of that one call and invoked from inside Kotlin (e.g. inside `filter`/`forEach`).
3. **C# → Kotlin, stored**: a C# lambda passed to a Kotlin function that keeps it around past the call (an observer/listener) is registered as a subscription and returns an `IDisposable` that unregisters it.

A fourth shape, C# implementing a Kotlin *interface* and passing it as a parameter, is also supported for the specific case of `add`/`remove`-paired subscriptions (interface bridging).

| Kotlin | C# | Notes |
|---|---|---|
| `(T) -> R` (Kotlin → C#) | `Func<>` / `Action<>` (wrapped) | invoked from C#, [ADR-012](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/012-lambda-function-type-mapping.md) |
| `(T) -> R` parameter (C# → Kotlin) | `Func<>` / `Action<>` | reverse interop, arity 0+, per-call, AOT-safe static thunk dispatch, [ADR-036](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md), [ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md) |
| stored callback parameter | `IDisposable` subscription | Kotlin-side `_unsubscribe` export, AOT-safe static thunk dispatch, [ADR-037](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/037-stored-callbacks.md), [ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md) |
| interface parameter (C# → Kotlin) | C# implements `I`-prefixed type | `add`/`remove`-paired, `IDisposable`, AOT-safe static thunk dispatch, [ADR-039](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/039-interface-bridging.md), [ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md) |

## Kotlin → C#: lambda properties and returns

From `test-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

```kotlin
val onMeow: () -> String = { "Meow! My name is $name" }
val onPet: (String) -> String = { action -> "$name $action contentedly" }
val favoriteToy: () -> Toy = { toys.first() }
```

Generated C# wraps the returned function pointer in a `KotlinFunc<TResult>`/`KotlinFunc<T1, TResult>`:

```C#
public KotlinFunc<string> OnMeow => new KotlinFunc<string>(Native_Get_onMeow(_handle));
public KotlinFunc<string, string> OnPet => new KotlinFunc<string, string>(Native_Get_onPet(_handle));
public KotlinFunc<Toy> FavoriteToy => new KotlinFunc<Toy>(Native_Get_favoriteToy(_handle));
```

```C#
public class KotlinFunc<TResult> : IDisposable
{
    internal IntPtr _handle;
    internal KotlinFunc(IntPtr handle) { _handle = handle; }

    public TResult Invoke()
    {
        IntPtr result = NugetFuncNative.Invoke0(_handle);
        return NugetMarshal.FromHandle<TResult>(result);
    }

    public void Dispose() { /* ... */ }
}
```

Using it, from `IntegrationTests/LambdaTests.cs`:

```C#
[Fact]
public void Cat_OnPet_Invoke()
{
    using var cat = new Cat("Oreo", 9);
    using var onPet = cat.OnPet;
    string result = onPet.Invoke("purrs");
    Assert.Equal("Oreo purrs contentedly", result);
}
```

## C# → Kotlin: per-call lambda parameters

From `Cat.kt`, Kotlin functions accepting a C# lambda, arity 0 through 2:

```kotlin
fun describeWith(format: (String) -> String): String = format(name)
fun nicknamesMatching(predicate: (String) -> Boolean): List<String> = nicknames.filter(predicate)
fun greetUsing(greeting: () -> String): String = "${greeting()}, says $name"
fun forEachToy(action: (Toy) -> Unit) = toys.forEach(action)
fun combineNicknames(combine: (String, String) -> String): String = combine(nicknames[0], nicknames[1])
```

Generated C# allocates a `GCHandle` to the delegate as a ctx token and hands Kotlin a pointer to a
shared `[UnmanagedCallersOnly]` static thunk (`NugetThunks`, one per delegate shape, see
[Publishing Kotlin to C#: AOT and trimming](forward-overview.md#aot-and-trimming),
[ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md))
that recovers the delegate through the ctx and invokes it. The handle is freed once the call returns:

```C#
public string DescribeWith(Func<string, string> format)
{
    NugetStringStringCallback nativeCallback = (IntPtr arg0Ptr, IntPtr userData) =>
    {
        string arg0 = NugetMarshal.FromHandle<string>(arg0Ptr);
        return NugetMarshal.WrapString(format(arg0));
    };
    GCHandle cbHandle = GCHandle.Alloc(nativeCallback);
    try
    {
        IntPtr nativeResult = Native_DescribeWith(_handle, NugetThunks.NugetStringStringCallbackPtr, GCHandle.ToIntPtr(cbHandle), out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }
    finally
    {
        cbHandle.Free();
    }
}
```

Using it, from `IntegrationTests/ReverseLambdaTests.cs`:

```C#
[Fact]
public void Cat_NicknamesMatching_CapturingLambda()
{
    using var cat = new Cat("Oreo", 9);
    int minLength = 6;
    IReadOnlyList<string> matching = cat.NicknamesMatching(n => n.Length >= minLength);
    Assert.Equal(new List<string> { "Little Oreo" }, matching);
}

[Fact]
public void Cat_CombineNicknames_Arity2LambdaParameter()
{
    using var cat = new Cat("Oreo", 9);
    string result = cat.CombineNicknames((a, b) => $"{a} & {b}");
    Assert.Equal("Oreoy & Little Oreo", result);
}
```

## C# → Kotlin: stored callbacks

From `Cat.kt`, an observer added once and invoked on every future trigger:

```kotlin
private val moodListeners: MutableList<(Mood) -> Unit> = mutableListOf()

fun addMoodListener(listener: (Mood) -> Unit) = moodListeners.add(listener)

fun removeMoodListener(listener: (Mood) -> Unit) = moodListeners.remove(listener)

fun triggerMoodChange(mood: Mood) {
  this.mood = mood
  moodListeners.forEach { it(mood) }
}
```

`AddMoodListener` returns an `IDisposable` wrapping the `_unsubscribe` export, instead of requiring the caller to hold a reference and call `removeMoodListener` manually:

```C#
public IDisposable AddMoodListener(Action<global::TestLibrary.Cat.Mood> listener)
{
    NugetIntVoidCallback nativeCallback = (int arg0Ord, IntPtr _) => { global::TestLibrary.Cat.Mood arg0 = (global::TestLibrary.Cat.Mood)arg0Ord; listener(arg0); };
    GCHandle cbHandle = GCHandle.Alloc(nativeCallback);
    IntPtr sub = Native_AddMoodListener(_handle, NugetThunks.NugetIntVoidCallbackPtr, GCHandle.ToIntPtr(cbHandle), out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return new NugetSubscription(() => { Native_RemoveMoodListener(_handle, sub); cbHandle.Free(); });
}
```

Using it, from `IntegrationTests/StoredCallbackTests.cs`:

```C#
[Fact]
public void Cat_AddMoodListener_CallbackFiresOnTrigger()
{
    using var cat = new Cat("Oreo", 9);
    var recorded = new List<string>();
    using IDisposable sub = cat.AddMoodListener(mood => recorded.Add(mood.ToString()));

    cat.TriggerMoodChange(Mood.Happy);

    Assert.Equal(new[] { "Happy" }, recorded);
}

[Fact]
public void Cat_AddMoodListener_NoCallbackAfterDispose()
{
    using var cat = new Cat("Mylo", 9);
    var recorded = new List<string>();
    IDisposable sub = cat.AddMoodListener(mood => recorded.Add(mood.ToString()));

    sub.Dispose();
    cat.TriggerMoodChange(Mood.Grumpy);

    Assert.Empty(recorded);
}
```

## C# implementing a Kotlin interface as a parameter

From `test-library/src/nativeMain/kotlin/.../cat/CatEventListener.kt` and `CatEventSource.kt`:

```kotlin
interface CatEventListener {
  fun onMeow(message: String)
  fun onPurr()
}

class CatEventSource(val name: String) {
  private val listeners: MutableList<CatEventListener> = mutableListOf()

  fun addListener(listener: CatEventListener) { listeners.add(listener) }
  fun removeListener(listener: CatEventListener) { listeners.remove(listener) }

  fun trigger() {
    val msg = "$name says meow!"
    listeners.forEach { it.onMeow(msg) }
    listeners.forEach { it.onPurr() }
  }
}
```

`AddListener` takes the generated `ICatEventListener` interface and bridges each method as its own function pointer (N pointers for an N-method interface), returning an `IDisposable` the same way a stored callback does:

```C#
public IDisposable AddListener(ICatEventListener listener)
{
    if (_handle == IntPtr.Zero) throw new ObjectDisposedException(nameof(CatEventSource));
    NugetObjectVoidCallback onMeowCb = (IntPtr arg0Ptr, IntPtr _) =>
    {
        string arg0 = NugetMarshal.FromHandle<string>(arg0Ptr);
        NugetMarshal.Dispose(arg0Ptr);
        listener.OnMeow(arg0);
    };
    NugetVoidCallback onPurrCb = (IntPtr _) => { listener.OnPurr(); };
    GCHandle h0 = GCHandle.Alloc(onMeowCb);
    GCHandle h1 = GCHandle.Alloc(onPurrCb);
    IntPtr sub = Native_AddListener(_handle, NugetThunks.NugetObjectVoidCallbackPtr, GCHandle.ToIntPtr(h0),
        NugetThunks.NugetVoidCallbackPtr, GCHandle.ToIntPtr(h1), out IntPtr error);
    if (error != IntPtr.Zero) { h0.Free(); h1.Free(); throw NugetErrorNative.BuildException(error); }
    return new NugetSubscription(() => { Native_RemoveListener(_handle, sub); h0.Free(); h1.Free(); });
}
```

Using it, from `IntegrationTests/InterfaceBridgingTests.cs`:

```C#
private class RecordingCatListener : ICatEventListener
{
    public List<string> Meows { get; } = new();
    public int Purrs { get; private set; }
    public void OnMeow(string message) => Meows.Add(message);
    public void OnPurr() => Purrs++;
    public void Dispose() { }
}

[Fact]
public void CatEventSource_AddListener_TriggerFiresBothOnMeowAndOnPurr()
{
    using var source = new CatEventSource("Oreo");
    var listener = new RecordingCatListener();
    using IDisposable sub = source.AddListener(listener);

    source.Trigger();

    Assert.Equal(new[] { "Oreo says meow!" }, listener.Meows);
    Assert.Equal(1, listener.Purrs);
}
```

## Limitations

Be precise about what's supported here: the interface *parameter* shape on **this** page is only the `add`/`remove`-paired subscription route shown above. A Kotlin interface as a **return type**, and a general (non-subscription) interface-typed parameter or property setter, are both now supported too, see [Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md#interface-typed-return-values) and its [Implementing a Kotlin interface in C#](interfaces-abstract-sealed.md#implementing-a-kotlin-interface-in-c) section: a C#-implemented `IFoo` (no `_handle`) can be passed at an ordinary interface-typed parameter like `Cat.Befriend`, dispatched through a per-interface bridge factory rather than throwing `NotSupportedException`. The following are still explicitly not built (ROADMAP Phase 7):

- Converging the `add`/`remove` subscription route above onto that general bridge factory is not done; the two routes are separate machinery today, and the subscription route has its own known gaps (a non-Unit-returning or property-bearing subscription interface generates non-compiling Kotlin with no diagnostic), tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- Exception propagation from inside a C# callback back into Kotlin is not implemented (the forward-direction `ADR-024`/`ADR-028`/`ADR-029` machinery has no mirror here yet).
- `Flow<T>` or a suspend lambda (`suspend (T) -> R`) as a function parameter is not implemented.

<seealso>
    <category ref="related">
        <a href="coroutines-and-flow.md">Coroutines and Flow</a>
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/012-lambda-function-type-mapping.md">ADR-012: Lambda/function type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md">ADR-036: Reverse interop mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/037-stored-callbacks.md">ADR-037: Stored callbacks</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/039-interface-bridging.md">ADR-039: Interface bridging</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md">ADR-040: Interface return type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md">ADR-084: C#-implemented Kotlin interfaces</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md">ADR-102: AOT-safe forward callbacks</a>
    </category>
</seealso>

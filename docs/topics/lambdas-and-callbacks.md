# Lambdas and callbacks

Function types cross the bridge in three distinct shapes depending on which side owns the lambda and how long it lives.

1. **Kotlin → C#**: a Kotlin lambda *property* or *return value* is wrapped in a `KotlinFunc<...>` handle the C# caller invokes.
2. **C# → Kotlin, per-call**: a C# lambda passed as a *parameter* into a Kotlin function is pinned for the duration of that one call and invoked from inside Kotlin (e.g. inside `filter`/`forEach`).
3. **C# → Kotlin, stored**: a C# lambda passed to a Kotlin function that keeps it around past the call (an observer/listener) is registered as a subscription and returns an `IDisposable` that unregisters it.

A fourth shape, C# implementing a Kotlin *interface* and passing it as a parameter, is also supported for the specific case of `add`/`remove`-paired subscriptions (interface bridging).

| Kotlin | C# | Notes |
|---|---|---|
| `(T) -> R` (Kotlin → C#) | `Func<>` / `Action<>` (wrapped) | invoked from C#, [ADR-012](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/012-lambda-function-type-mapping.md) |
| `(T) -> R` parameter (C# → Kotlin) | `Func<>` / `Action<>` | reverse interop, arity 0+, per-call, [ADR-036](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md) |
| stored callback parameter | `IDisposable` subscription | Kotlin-side `_unsubscribe` export, [ADR-037](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/037-stored-callbacks.md) |
| interface parameter (C# → Kotlin) | C# implements `I`-prefixed type | `add`/`remove`-paired, `IDisposable`, [ADR-039](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/039-interface-bridging.md) |

## Kotlin → C#: lambda properties and returns

From `sample-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

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

Using it, from `sample-app/SampleApp.Tests/LambdaTests.cs`:

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

Generated C# pins the C# delegate with `GCHandle`, hands Kotlin a function pointer via `Marshal.GetFunctionPointerForDelegate`, and frees the handle once the call returns:

```C#
public string DescribeWith(Func<string, string> format)
{
    NugetStringStringCallback nativeCallback = (IntPtr arg0Ptr, IntPtr userData) =>
    {
        string arg0 = NugetMarshal.FromHandle<string>(arg0Ptr);
        return NugetMarshal.WrapString(format(arg0));
    };
    GCHandle cbHandle = GCHandle.Alloc(nativeCallback);
    IntPtr fnPtr = Marshal.GetFunctionPointerForDelegate(nativeCallback);
    try
    {
        IntPtr nativeResult = Native_DescribeWith(_handle, fnPtr, IntPtr.Zero, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }
    finally
    {
        cbHandle.Free();
    }
}
```

Using it, from `sample-app/SampleApp.Tests/ReverseLambdaTests.cs`:

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
public IDisposable AddMoodListener(Action<Mood> listener)
{
    // pins listener, calls cat_addMoodListener, returns a NugetSubscription
    IntPtr sub = Native_AddMoodListener(_handle, fnPtr, IntPtr.Zero, out IntPtr error);
    return new NugetSubscription(() => { Native_RemoveMoodListener(_handle, sub); cbHandle.Free(); });
}
```

Using it, from `sample-app/SampleApp.Tests/StoredCallbackTests.cs`:

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

From `sample-library/src/nativeMain/kotlin/.../cat/CatEventListener.kt` and `CatEventSource.kt`:

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
    IntPtr sub = Native_AddListener(_handle, Marshal.GetFunctionPointerForDelegate(onMeowCb), IntPtr.Zero,
        Marshal.GetFunctionPointerForDelegate(onPurrCb), IntPtr.Zero, out IntPtr error);
    if (error != IntPtr.Zero) { h0.Free(); h1.Free(); throw NugetErrorNative.BuildException(error); }
    return new NugetSubscription(() => { Native_RemoveListener(_handle, sub); h0.Free(); h1.Free(); });
}
```

Using it, from `sample-app/SampleApp.Tests/InterfaceBridgingTests.cs`:

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

Be precise about what's supported here: interface *parameters* are only the `add`/`remove`-paired subscription shape shown above. The following are explicitly not built (ROADMAP Phase 7):

- A Kotlin interface as a **return type** (generating a concrete handle-backed `sealed class Foo : IFoo` per interface, so a Kotlin function could return `IFoo`) is not implemented ([ADR-040](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md)).
- General C#-implemented interface parameters without an `add`/`remove` pair, i.e. a Kotlin function that takes an interface once and doesn't need a disposable subscription, are not implemented; there's no GCHandle release strategy for that shape yet.
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
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md">ADR-040: Interface return type mapping (not yet built)</a>
    </category>
</seealso>

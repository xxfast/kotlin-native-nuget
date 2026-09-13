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

### A lambda that returns `Unit` {id="a-lambda-that-returns-unit"}

`void` is a C# return type, never a type argument, so a Unit-returning lambda cannot be spelled `KotlinFunc<void>`. It binds as `KotlinAction` instead, dropping the return and carrying its arity in the parameters alone, the same shape `KotlinSuspendAction` uses on the suspend side.

```kotlin
var naps: Int = 0
val onNap: () -> Unit = { naps++ }
val onNapFor: (Int) -> Unit = { hours -> naps += hours }
```

```C#
public KotlinAction OnNap => new KotlinAction(Native_Get_onNap(_handle));
public KotlinAction<int> OnNapFor => new KotlinAction<int>(Native_Get_onNapFor(_handle));
```

`Invoke` returns `void`, and is otherwise the `KotlinFunc` handle in every respect: `using` it, or disposing it, releases the underlying lambda.

```C#
using var cat = new Cat("Oreo", 9);
using var onNap = cat.OnNap;
onNap.Invoke();
Assert.Equal(1, cat.Naps);
```

### A lambda's type arguments across a namespace boundary {id="type-arguments-across-a-namespace-boundary"}

`KotlinFunc<T1, TResult>`'s type arguments are qualified `global::Namespace.Name` exactly like any other cross-class reference, even when the lambda property lives in a different namespace from both of its type arguments. From `test-library/src/nativeMain/kotlin/.../catcam/CatCam.kt`:

```kotlin
class CatCam(val watching: String) {
  /** Expressible, cross-namespace: must qualify, must keep binding, must invoke. */
  val onPick: (CamId) -> Snapshot = { id -> Snapshot("${id.value}: $watching, unmoved") }

  /** The reported repro: `Flow<Snapshot>` is unspellable here, so the member must be absent. */
  val onStream: (CamId) -> Flow<Snapshot> = { id ->
    flowOf(Snapshot("${id.value}: $watching, still unmoved"))
  }
}
```

`CamId` and `Snapshot` live in `TestLibrary.Catcam.Lens`, one package below `CatCam` itself, so a bare simple name would only compile by coincidence. Generated C#:

```C#
public KotlinFunc<global::TestLibrary.Catcam.Lens.CamId, global::TestLibrary.Catcam.Lens.Snapshot> OnPick => new KotlinFunc<global::TestLibrary.Catcam.Lens.CamId, global::TestLibrary.Catcam.Lens.Snapshot>(Native_Get_onPick(_handle));
```

`OnStream` is absent from the generated class entirely: `Flow<Snapshot>` has no C# spelling on this route, so the member is dropped with a `SKIPPED_UNSUPPORTED_PROPERTY` naming it, rather than emitting `KotlinFunc<CamId, Flow>` and failing to compile. Using it, from `IntegrationTests/LambdaTypeArgumentTests.cs`:

```C#
[Fact]
public void CatCam_OnPick_QualifiesBothCrossNamespaceTypeArgumentsAndInvokes()
{
    using var cam = new CatCam("Oreo");
    using var id = new CamId("oreo-front");
    using KotlinFunc<CamId, Snapshot> pick = cam.OnPick;

    using Snapshot snapshot = pick.Invoke(id);

    Assert.Equal("oreo-front: Oreo, unmoved", snapshot.Caption);
}

[Theory]
[InlineData("OnStream")]
[InlineData("OnStreamAsync")]
[InlineData("OnSponsor")]
public void CatCam_UnspellableLambdaProperty_IsAbsent(string member)
{
    Assert.Null(typeof(CatCam).GetProperty(member));
    Assert.Empty(typeof(CatCam).GetMember(member));
}
```

A lambda's own type-**parameter** argument (`T` on a generic member) stays bare rather than being qualified, since it names no concrete class. The same qualify-or-skip rule applies to a lambda-typed property on a sealed subclass, the generic-return route, and a top-level function's lambda-typed return.

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

The same route now binds a method declared on a **sealed arm** too, re-keyed onto the arm's own
export prefix rather than a class name, exactly as the `suspend` and `Flow` routes were; see
[Lambda parameters on a sealed arm](interfaces-abstract-sealed.md#sealed-lambda-generated-c).

### A primitive payload {id="a-primitive-payload"}

A `kotlin.*` primitive payload (`Int`, `Boolean`, `Byte`, `Double`, ...) crosses by value instead of
going through `NugetMarshal.FromHandle`. From
`test-library/src/nativeMain/kotlin/.../metronome/Metronome.kt`:

```kotlin
class Metronome(private val beats: Int) {
  fun onTick(listener: (Int) -> Unit) = repeat(beats) { listener(it + 1) }
  fun onBeat(listener: (Boolean) -> Unit) = repeat(beats) { listener(it % 2 == 0) }
  fun onVelocity(listener: (Byte) -> Unit) = repeat(beats) { listener((it * 40 - 100).toByte()) }
  fun onTempo(listener: (Double) -> Unit) = repeat(beats) { listener(60.0 + it * 0.5) }
}
```

The generated delegate reads the argument directly, `Boolean` widening from a `byte` wire back to
`bool`. `Boolean` and `Byte` bind independently, `NugetBoolVoidCallback` and `NugetByteVoidCallback`,
even though both cross an 8-bit value: a class declaring both a `(Boolean) -> Unit` and a
`(Byte) -> Unit` per-call lambda parameter used to register `NugetByteVoidCallback` for both and fail
the consumer's own compile (CS1678) the moment the two disagreed on `byte` vs `sbyte`:

```C#
public void OnTick(Action<int> listener)
{
    NugetIntVoidCallback nativeCallback = (int arg0, IntPtr userData) =>
    {
    listener(arg0);
    };
    ...
}

public void OnBeat(Action<bool> listener)
{
    NugetBoolVoidCallback nativeCallback = (byte arg0Byte, IntPtr userData) =>
    {
    bool arg0 = arg0Byte != 0;
    listener(arg0);
    };
    ...
}

public void OnVelocity(Action<sbyte> listener)
{
    NugetByteVoidCallback nativeCallback = (sbyte arg0, IntPtr userData) =>
    {
    listener(arg0);
    };
    ...
}
```

Using it, from `IntegrationTests/PrimitiveLambdaPayloadTests.cs`:

```C#
[Fact]
public void Metronome_OnTick_DeliversIntPayloadByValue()
{
    using var metronome = new Metronome(4);
    var ticks = new List<int>();
    metronome.OnTick(tick => ticks.Add(tick));
    Assert.Equal(new List<int> { 1, 2, 3, 4 }, ticks);
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

## Ownership of a callback payload

A handle-passed argument on any of the three C# → Kotlin routes above (per-call, stored, interface
bridge) is owned by the C# side once it crosses. Kotlin retains it for the duration of the crossing
and never releases it afterwards; the rule is the same for every payload kind:

- a **`String`** (or any other marshalled kind) is read by `NugetMarshal.FromHandle<T>`, which
  disposes the handle immediately after reading the value. There is nothing left for the callback
  body to free.
- an **exported object** falls through to `NugetMarshal.Materialize<T>`, which hands the raw handle
  to the wrapper's constructor. The wrapper the callback body receives is the sole owner, and its
  `Dispose()` is the free, which is why `Cat.ForEachToy` disposes the `Toy` it's handed, from
  `IntegrationTests/ReverseLambdaTests.cs`:

```C#
cat.ForEachToy(toy =>
{
    using var t = toy;
    toyNames.Add(t.Name);
});
```

A **by-value primitive** payload never had a handle, so there's nothing to own. The callback's
*return* box is the other way round: nothing on the C# side frees it, so Kotlin still releases it
after reading it.

<note>
    <p>A generated wrapper has a <code>Dispose()</code> and no finalizer, so a callback body that
    never disposes an object payload it's handed leaks that handle for the life of the process.
    That's diagnosable through <code>NugetMarshal.LiveHandles</code>, unlike the use-after-free the
    alternative rule would have caused instead.</p>
</note>

## Limitations

Be precise about what's supported here: the interface *parameter* shape on **this** page is only the `add`/`remove`-paired subscription route shown above. A Kotlin interface as a **return type**, and a general (non-subscription) interface-typed parameter or property setter, are both now supported too, see [Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md#interface-typed-return-values) and its [Implementing a Kotlin interface in C#](interfaces-abstract-sealed.md#implementing-a-kotlin-interface-in-c) section: a C#-implemented `IFoo` (no `_handle`) can be passed at an ordinary interface-typed parameter like `Cat.Befriend`, dispatched through a per-interface bridge factory rather than throwing `NotSupportedException`. The following are still explicitly not built (ROADMAP Phase 7):

- Converging the `add`/`remove` subscription route above onto that general bridge factory is not done; the two routes are separate machinery today, and the subscription route has its own known gaps (a non-Unit-returning or property-bearing subscription interface generates non-compiling Kotlin with no diagnostic), tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- Exception propagation from inside a C# callback back into Kotlin is not implemented (the forward-direction `ADR-024`/`ADR-028`/`ADR-029` machinery has no mirror here yet).
- `Flow<T>` or a suspend lambda (`suspend (T) -> R`) as a function parameter is not implemented; since [ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)'s 2026-09-13 amendment both are now a named `SKIPPED_UNSUPPORTED_INPUT` skip rather than a silent one, the same as any other lambda position with no legacy route to re-emit it (an `object`, an interface default reached through the interface type, an extension, a secondary constructor, a `List<(T) -> R>` element, or a lambda return anywhere but a top-level function).
- `WrapArg<T>` only handles `string`/`int`/`long`/`float`/`double`/`bool` and `INugetHandle` at an **argument** position; an `sbyte`/`short`/`char`/`uint` argument, or a reference-underlying value-class argument, throws `NotSupportedException` at runtime rather than failing to build (see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)).
- A per-call lambda-parameter method returning anything other than `Unit` (`fun f(cb: (Int) -> Unit): Int`) fails `packNuget` with a forward ABI mismatch rather than generating: every shipped example on this page returns `Unit` ([ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)).

<seealso>
    <category ref="related">
        <a href="coroutines-and-flow.md">Coroutines and Flow</a>
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/012-lambda-function-type-mapping.md">ADR-012: Lambda/function type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066: Forward export reachability closure</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md">ADR-036: Reverse interop mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/037-stored-callbacks.md">ADR-037: Stored callbacks</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/039-interface-bridging.md">ADR-039: Interface bridging</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md">ADR-040: Interface return type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md">ADR-084: C#-implemented Kotlin interfaces</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md">ADR-102: AOT-safe forward callbacks</a>
    </category>
</seealso>

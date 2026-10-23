# A4b held: `IWhistle` nullable and suspend lambda slots

Cut from the ADR-233 amendment item: depends on lambda-property and lambda-result work. Today both
the class route and the bridge skip all eight `Whistle` members (nullable lambda parameter, nullable
and `suspend` lambda results, nullable plain and `suspend` lambda properties, and an interface
`var` of a lambda), so `IWhistle` declares nothing and `SilverWhistle` is not a C# implementation
Kotlin can take. The C# shapes in `SilverWhistle`/`LeakWhistle` are unconfirmed guesses.

## Fixture: `test-library/.../cattery/Catteries.kt`

```kotlin
/** Lambda slots: nullable plain and suspend lambdas as property, parameter and result, and a `var`. */
interface Whistle {
  val onCall: ((String) -> Int)?

  val onLater: (suspend (String) -> Int)?

  var tune: (Int) -> String

  fun maybeCall(callback: ((String) -> Int)?): Int

  suspend fun maybeLater(callback: (suspend (String) -> Int)?): Int

  fun caller(): ((Int) -> String)?

  fun later(): suspend (Int) -> String

  fun maybeLaterResult(): (suspend (Int) -> String)?
}

/** Kotlin reads every lambda slot of a C# [Whistle], with a value and a null each way. */
suspend fun blow(whistle: Whistle): String {
  val call: String = whistle.onCall?.invoke("Oreo")?.toString() ?: "none"
  val later: String = whistle.onLater?.invoke("Mylo")?.toString() ?: "none"
  val before: String = whistle.tune(2)
  whistle.tune = { times -> "kotlin x$times" }
  val after: String = whistle.tune(3)
  val passed: Int = whistle.maybeCall { cat -> cat.length }
  val passedNull: Int = whistle.maybeCall(null)
  val suspended: Int = whistle.maybeLater { cat -> cat.length * 10 }
  val suspendedNull: Int = whistle.maybeLater(null)
  val caller: String = whistle.caller()?.invoke(4) ?: "none"
  val awaited: String = whistle.later()(5)
  val maybeAwaited: String = whistle.maybeLaterResult()?.invoke(6) ?: "none"
  return listOf(
    "call=$call", "later=$later", "tune=$before/$after", "passed=$passed/$passedNull",
    "suspended=$suspended/$suspendedNull", "caller=$caller", "awaited=$awaited",
    "maybe=$maybeAwaited",
  ).joinToString(" ")
}
```

## `IntegrationTests/CatterySlotTests.cs`

```csharp
    // -----------------------------------------------------------------------------------------
    // Nullable and suspend lambdas as property, parameter and result, and a `var` of a lambda.
    // -----------------------------------------------------------------------------------------

    internal sealed class SilverWhistle : Cattery.IWhistle
    {
        public Func<string, int>? OnCall { get; } = cat => cat.Length * 2;

        public Func<string, CancellationToken, Task<int>>? OnLater { get; } =
            async (cat, cancellationToken) =>
            {
                await Task.Delay(1, cancellationToken);
                return cat.Length * 3;
            };

        public Func<int, string> Tune { get; set; } = times => $"csharp x{times}";

        public int MaybeCall(Func<string, int>? callback) => callback == null ? -1 : callback("Mylo");

        public async Task<int> MaybeLaterAsync(
            Func<string, CancellationToken, Task<int>>? callback, CancellationToken cancellationToken = default) =>
            callback == null ? -1 : await callback("Oreo", cancellationToken);

        public Func<int, string>? Caller() => times => $"meow x{times}";

        public Func<int, CancellationToken, Task<string>> Later() =>
            async (times, cancellationToken) =>
            {
                await Task.Delay(1, cancellationToken);
                return $"later x{times}";
            };

        public Func<int, CancellationToken, Task<string>>? MaybeLaterResult() => null;

        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

    [Fact]
    public async Task Blow_EveryNullableAndSuspendLambdaSlot()
    {
        var whistle = new SilverWhistle();

        Assert.Equal(
            "call=8 later=12 tune=csharp x2/kotlin x3 passed=4/-1 suspended=40/-1 " +
            "caller=meow x4 awaited=later x5 maybe=none",
            await Cattery.Catteries.BlowAsync(whistle));
        Assert.Equal("kotlin x9", whistle.Tune(9));
    }
```

## `LeakTests/LiveHandleTests.cs` (row 20g's Whistle part)

The helper class, and this line inside `CSharpInterfaceSlotVocabularyAmendment_Completion_ReturnsToBaseline`,
after the `RetraceAsync` assertion (the row's comment also listed "every nullable and suspend lambda slot"):

```csharp
    // A C# `IWhistle` for row 20g: every lambda slot answers with a fresh C# delegate.
    internal sealed class LeakWhistle : Cattery.IWhistle
    {
        public Func<string, int>? OnCall { get; } = cat => cat.Length * 2;
        public Func<string, CancellationToken, Task<int>>? OnLater { get; } =
            (cat, cancellationToken) => Task.FromResult(cat.Length * 3);
        public Func<int, string> Tune { get; set; } = times => $"csharp x{times}";
        public int MaybeCall(Func<string, int>? callback) => callback == null ? -1 : callback("Mylo");
        public async Task<int> MaybeLaterAsync(
            Func<string, CancellationToken, Task<int>>? callback, CancellationToken cancellationToken = default) =>
            callback == null ? -1 : await callback("Oreo", cancellationToken);
        public Func<int, string>? Caller() => times => $"meow x{times}";
        public Func<int, CancellationToken, Task<string>> Later() =>
            (times, cancellationToken) => Task.FromResult($"later x{times}");
        public Func<int, CancellationToken, Task<string>>? MaybeLaterResult() => null;
        public void Dispose() { }
        public ValueTask DisposeAsync() => default;
    }

            Assert.StartsWith("call=8 ", await Cattery.Catteries.BlowAsync(new LeakWhistle()));
```

## `LeakTests/CollectabilityTests.cs`

```csharp
    // ADR-233 amendment: a C#-implemented interface whose nullable, suspend and `var` lambda slots
    // each hand Kotlin a C# delegate is collectable once Kotlin drops its bridge and the lambdas it
    // was handed. A registration key a returned lambda left behind would root the whistle.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static async Task<WeakReference> BlowAndDropAWhistle()
    {
        var whistle = new LiveHandleTests.LeakWhistle();
        Assert.StartsWith("call=8 ", await Cattery.Catteries.BlowAsync(whistle));
        WeakReference weak = new(whistle);
        await Task.Yield();
        return weak;
    }

    [Fact]
    public async Task CSharpWhistle_ReleasedAfterKotlinGc()
    {
        int before = NugetBridgeState.ReleasedCount;

        WeakReference whistle = await BlowAndDropAWhistle();
        await Task.Yield();

        DateTime deadline = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        while (DateTime.UtcNow < deadline && (whistle.IsAlive || NugetBridgeState.ReleasedCount <= before))
        {
            NugetMarshal.GcCollect();
            GC.Collect();
            GC.WaitForPendingFinalizers();
            await Task.Delay(25);
        }

        Assert.True(
            NugetBridgeState.ReleasedCount > before,
            $"expected the Kotlin cleaner to release the Whistle bridge; ReleasedCount stayed at {before}");
        Assert.False(whistle.IsAlive, "the C# whistle stayed reachable after Kotlin collected its bridge");
    }
```

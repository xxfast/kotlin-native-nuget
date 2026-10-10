using TestLibrary;
using TestLibrary.Boxshelf;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-208 part E: a <c>Flow&lt;E&gt;</c> / <c>StateFlow&lt;E&gt;</c> as the type argument of an
/// exported generic class (<c>Box&lt;Flow&lt;Int&gt;&gt;</c>). <c>box.Value</c> is a
/// <c>KotlinFlow&lt;E&gt;</c> / <c>KotlinStateFlow&lt;E&gt;</c> the caller can <c>await foreach</c>
/// and must dispose. Each read mints a fresh holder over the same Kotlin flow, and the holder owns
/// the scope its collections run on: disposing it cancels them, and disposing the object that
/// produced the box neither cancels them nor waits for them. The Kotlin fixture is
/// <c>BoxRadio</c> in <c>test-library/.../test/boxshelf/BoxShelves.kt</c>.
///
/// Oreo (black, white in the middle) tunes the radio. Mylo (brown and creamy) sleeps through it.
/// </summary>
public class FlowTypeArgumentTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    private static async Task<List<T>> Collect<T>(KotlinFlow<T> flow)
    {
        var seen = new List<T>();
        await foreach (T item in flow) seen.Add(item);
        return seen;
    }

    // --- Flow argument -------------------------------------------------------------------------

    [Fact]
    public async Task BoxedFlow_IntElement_CollectsToCompletion()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<int>> box = radio.Ticks();
        using KotlinFlow<int> ticks = box.Value;

        Assert.Equal(new[] { 1, 2, 3 }, await Collect(ticks));
    }

    [Fact]
    public async Task BoxedFlow_EnumElement_IsProjectedAsTheEnum()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<Mood>> box = radio.Moods();
        using KotlinFlow<Mood> moods = box.Value;

        Assert.Equal(new[] { Mood.Sleepy, Mood.Grumpy }, await Collect(moods));
    }

    [Fact]
    public async Task BoxedFlow_NullableElement_CarriesNull()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<string?>> box = radio.Whispers();
        using KotlinFlow<string?> whispers = box.Value;

        Assert.Equal(new string?[] { "psst", null }, await Collect(whispers));
    }

    [Fact]
    public async Task BoxedFlow_CollectedTwice_EachEnumerationRunsTheFlowAgain()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<int>> box = radio.Ticks();
        using KotlinFlow<int> ticks = box.Value;

        Assert.Equal(new[] { 1, 2, 3 }, await Collect(ticks));
        Assert.Equal(new[] { 1, 2, 3 }, await Collect(ticks));
    }

    [Fact]
    public async Task TopLevelReturn_BoxedFlow_Collects()
    {
        using Box<KotlinFlow<int>> box = BoxShelves.BoxedTicks();
        using KotlinFlow<int> ticks = box.Value;

        Assert.Equal(new[] { 7, 8 }, await Collect(ticks));
    }

    [Fact]
    public async Task NestedBox_InnerBoxHoldsTheFlow()
    {
        using var radio = new BoxRadio();
        using Box<Box<KotlinFlow<int>>> outer = radio.NestedTicks();
        using Box<KotlinFlow<int>> inner = outer.Value;
        using KotlinFlow<int> ticks = inner.Value;

        Assert.Equal(new[] { 4 }, await Collect(ticks));
    }

    [Fact]
    public void NullableFlowArgument_NullReadsAsNull()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<int>?> box = radio.Silence();

        Assert.Null(box.Value);
    }

    [Fact]
    public async Task BoxedSharedFlow_BindsAsAPlainFlow_AndReplays()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<string>> box = radio.Jingles();
        using KotlinFlow<string> jingles = box.Value;

        // A SharedFlow never completes: take the replayed element and stop.
        await using IAsyncEnumerator<string> listener = jingles.GetAsyncEnumerator();
        Assert.True(await listener.MoveNextAsync().AsTask().WaitAsync(Patience));
        Assert.Equal("dinner", listener.Current);
    }

    // --- StateFlow argument --------------------------------------------------------------------

    [Fact]
    public void BoxedStateFlow_IntElement_ValueFollowsKotlin()
    {
        using var radio = new BoxRadio();
        using Box<KotlinStateFlow<int>> box = radio.Volume;
        using KotlinStateFlow<int> volume = box.Value;

        Assert.Equal(3, volume.Value);
        radio.TurnUp();
        Assert.Equal(4, volume.Value);
    }

    [Fact]
    public async Task BoxedStateFlow_EnumElement_ValueAndCollect()
    {
        using var radio = new BoxRadio();
        using Box<KotlinStateFlow<Mood>> box = radio.Mood();
        using KotlinStateFlow<Mood> mood = box.Value;

        Assert.Equal(Mood.Sleepy, mood.Value);
        radio.Sulk();
        Assert.Equal(Mood.Grumpy, mood.Value);

        await using IAsyncEnumerator<Mood> watcher = mood.GetAsyncEnumerator();
        Assert.True(await watcher.MoveNextAsync().AsTask().WaitAsync(Patience));
        Assert.Equal(Mood.Grumpy, watcher.Current);
    }

    [Fact]
    public void BoxedMutableStateFlow_BindsAsTheReadOnlyHolder()
    {
        using var radio = new BoxRadio();
        using Box<KotlinStateFlow<int>> box = radio.Dial();
        using KotlinStateFlow<int> dial = box.Value;

        Assert.IsType<KotlinStateFlow<int>>(dial);
        Assert.Equal(3, dial.Value);
    }

    // --- Read-only ------------------------------------------------------------------------------

    /// <summary>
    /// The holder is a view C# reads; it is not a Kotlin-backed object, so it cannot be handed
    /// back in as the content of a new box. Passing a flow into Kotlin is a separate mechanism.
    /// </summary>
    [Fact]
    public void PassingAHolderIntoKotlin_IsNotSupported()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<int>> box = radio.Ticks();
        using KotlinFlow<int> ticks = box.Value;

        Assert.Throws<NotSupportedException>(() => new Box<KotlinFlow<int>>(ticks));
    }

    // --- Holder ownership ----------------------------------------------------------------------

    [Fact]
    public async Task EachValueRead_MintsAFreshHolderOverTheSameFlow()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<int>> box = radio.Ticks();
        using KotlinFlow<int> first = box.Value;
        using KotlinFlow<int> second = box.Value;

        Assert.NotSame(first, second);
        first.Dispose();
        Assert.Equal(new[] { 1, 2, 3 }, await Collect(second));
    }

    [Fact]
    public async Task DisposingTheHolder_CancelsItsRunningCollection()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<int>> box = radio.Endless();
        KotlinFlow<int> endless = box.Value;
        await using IAsyncEnumerator<int> listener = endless.GetAsyncEnumerator();
        Assert.True(await listener.MoveNextAsync().AsTask().WaitAsync(Patience));
        Assert.Equal(1, listener.Current);

        // The flow is parked in awaitCancellation: only the holder's own scope can end it.
        endless.Dispose();

        Assert.False(await listener.MoveNextAsync().AsTask().WaitAsync(Patience));
    }

    [Fact]
    public void DisposedHolder_RefusesANewCollection()
    {
        using var radio = new BoxRadio();
        using Box<KotlinFlow<int>> box = radio.Ticks();
        KotlinFlow<int> ticks = box.Value;
        ticks.Dispose();

        Assert.Throws<ObjectDisposedException>(() => ticks.GetAsyncEnumerator());
    }

    [Fact]
    public async Task OwnerDisposedMidCollection_DoesNotHang_AndTheCollectionRunsOn()
    {
        var radio = new BoxRadio();
        // The owner drains only a scope it has, so a suspend call first makes the drain real.
        Assert.Equal(3, await radio.WarmUpAsync());
        using Box<KotlinFlow<int>> box = radio.Endless();
        using KotlinFlow<int> endless = box.Value;
        IAsyncEnumerator<int> listener = endless.GetAsyncEnumerator();
        Assert.True(await listener.MoveNextAsync().AsTask().WaitAsync(Patience));

        // The collection runs on the holder's scope, not the radio's, so the drain has nothing of
        // it to wait for.
        await radio.DisposeAsync().AsTask().WaitAsync(Patience);

        Task<bool> pending = listener.MoveNextAsync().AsTask();
        Assert.False(pending.IsCompleted);
        await listener.DisposeAsync();
        Assert.False(await pending.WaitAsync(Patience));
    }
}

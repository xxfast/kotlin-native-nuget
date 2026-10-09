using System.Collections.Concurrent;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

/// <summary>
/// ADR-206: the opt-in <c>INotifyPropertyChanged</c> adapter over <c>KotlinStateFlow&lt;T&gt;</c>.
/// <c>flow.AsNotifying(context)</c> returns a <c>KotlinStateFlowObservable&lt;T&gt;</c> whose
/// <c>Value</c> is seeded from <c>flow.Value</c> and then tracks every collected element, raising
/// <c>PropertyChanged("Value")</c> through the captured <see cref="SynchronizationContext"/> (or
/// inline on the delivering thread when there is none). <c>Dispose()</c> stops the collection.
///
/// No XAML here: <see cref="CatPump"/> is a one-thread <see cref="SynchronizationContext"/> that
/// stands in for a dispatcher. Fixture: <see cref="CatMoodTracker"/>, whose StateFlows cross every
/// element seam: <c>int</c> (no conversion), <c>string</c> (unwrap), <c>Cat</c> (handle wrapper),
/// and a declared <c>MutableStateFlow&lt;int&gt;</c> written from C#.
/// </summary>
public class StateFlowNotifyingTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    [Fact]
    public async Task AsNotifying_SeedsValueFromTheFlow_MyloWakesUpSleepyAndFullOfBeans()
    {
        // Before a single emission is collected, the binding already sees Mylo as he is right now.
        using var pump = new CatPump();
        using var tracker = new CatMoodTracker("Mylo");

        KotlinStateFlowObservable<string> mood = tracker.Mood.AsNotifying(pump);
        KotlinStateFlowObservable<int> energy = tracker.EnergyLevel.AsNotifying(pump);
        KotlinStateFlowObservable<Cat> playmate = tracker.Playmate.AsNotifying(pump);
        try
        {
            Assert.Equal("sleepy", mood.Value);
            Assert.Equal(100, energy.Value);
            Assert.Equal("Mylo", playmate.Value.Name);
        }
        finally
        {
            await StopAsync(mood);
            await StopAsync(energy);
            await StopAsync(playmate);
        }
    }

    [Fact]
    public async Task AsNotifying_KotlinSideWrite_RaisesValueChangedOnThePumpThread_MyloGetsTheZoomies()
    {
        // Mylo's mood flips in Kotlin; the "UI thread" hears about it, and only the UI thread.
        using var pump = new CatPump();
        using var tracker = new CatMoodTracker("Mylo");
        KotlinStateFlowObservable<string> mood = tracker.Mood.AsNotifying(pump);
        try
        {
            var heard = new TaskCompletionSource<(object? Sender, string? Property, string Value, int Thread)>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            mood.PropertyChanged += (sender, args) =>
            {
                if (mood.Value == "zoomies")
                {
                    heard.TrySetResult(
                        (sender, args.PropertyName, mood.Value, Environment.CurrentManagedThreadId));
                }
            };

            tracker.SetMood("zoomies");

            var (sender, property, value, thread) = await heard.Task.WaitAsync(Patience);
            Assert.Same(mood, sender);
            Assert.Equal(nameof(mood.Value), property);
            Assert.Equal("zoomies", value);
            Assert.Equal(pump.ThreadId, thread);
        }
        finally
        {
            await StopAsync(mood);
        }
    }

    [Fact]
    public async Task AsNotifying_IntAndCatElements_TrackKotlinWrites_OreoBouncesAndFindsAFriend()
    {
        // Oreo burns through his energy and makes a new friend; both bindings keep up.
        using var pump = new CatPump();
        using var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlowObservable<int> energy = tracker.EnergyLevel.AsNotifying(pump);
        KotlinStateFlowObservable<Cat> playmate = tracker.Playmate.AsNotifying(pump);
        try
        {
            Task<int> zoomed = WaitForAsync(energy, level => level == 70);
            Task<Cat> befriended = WaitForAsync(playmate, cat => cat.Name == "Mylo");

            tracker.BumpEnergy(-30);
            tracker.SetPlaymate("Mylo");

            Assert.Equal(70, await zoomed.WaitAsync(Patience));
            Assert.Equal("Mylo", (await befriended.WaitAsync(Patience)).Name);
            Assert.Equal(70, energy.Value);
            Assert.Equal("Mylo", playmate.Value.Name);
        }
        finally
        {
            await StopAsync(energy);
            await StopAsync(playmate);
        }
    }

    [Fact]
    public async Task AsNotifying_OnAMutableStateFlow_SeesACSharpWrite_OreoCountsHisTreats()
    {
        // KotlinMutableStateFlow<T> IS-A KotlinStateFlow<T>, so the adapter binds to it as well, and
        // a write made from C# comes back as a notification like any Kotlin-side write.
        using var pump = new CatPump();
        using var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlowObservable<int> treats = tracker.TreatCount.AsNotifying(pump);
        try
        {
            Assert.Equal(0, treats.Value);
            Task<int> counted = WaitForAsync(treats, count => count == 3);

            tracker.TreatCount.Value = 3;

            Assert.Equal(3, await counted.WaitAsync(Patience));
        }
        finally
        {
            await StopAsync(treats);
        }
    }

    [Fact]
    public async Task AsNotifying_WithNoContext_RaisesInlineOnTheDeliveringThread_MyloNapsOnAConsole()
    {
        // No dispatcher at all (a console host): the event fires on whichever thread delivered the
        // element, which is never the pump, because there is no pump.
        using var tracker = new CatMoodTracker("Mylo");
        KotlinStateFlowObservable<string> mood = await Task.Run(() =>
        {
            Assert.Null(SynchronizationContext.Current);
            return tracker.Mood.AsNotifying();
        });
        try
        {
            Assert.Equal("sleepy", mood.Value);
            var heard = new TaskCompletionSource<SynchronizationContext?>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            mood.PropertyChanged += (_, _) =>
            {
                if (mood.Value == "napping") heard.TrySetResult(SynchronizationContext.Current);
            };

            tracker.SetMood("napping");

            Assert.Null(await heard.Task.WaitAsync(Patience));
            Assert.Equal("napping", mood.Value);
        }
        finally
        {
            await StopAsync(mood);
        }
    }

    [Fact]
    public async Task Dispose_StopsDelivery_AndCompletionFinishesCleanly_OreoStopsListening()
    {
        // Oreo turns his ears off. The collection ends without a fault, and a later Kotlin write
        // never reaches the binding.
        using var pump = new CatPump();
        using var tracker = new CatMoodTracker("Oreo");
        KotlinStateFlowObservable<string> mood = tracker.Mood.AsNotifying(pump);

        mood.Dispose();
        mood.Dispose(); // idempotent
        await mood.Completion.WaitAsync(Patience);
        Assert.True(mood.Completion.IsCompletedSuccessfully);

        var heard = new List<string?>();
        mood.PropertyChanged += (_, args) => heard.Add(args.PropertyName);
        tracker.SetMood("grumpy");

        // The pump is FIFO: once this sentinel runs, anything posted before it has run too.
        await pump.DrainAsync().WaitAsync(Patience);

        Assert.Empty(heard);
        Assert.Equal("sleepy", mood.Value);
    }

    /// <summary>Completes with the first <c>Value</c> that satisfies <paramref name="match"/>.</summary>
    private static Task<T> WaitForAsync<T>(KotlinStateFlowObservable<T> observable, Func<T, bool> match)
    {
        var seen = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        observable.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(observable.Value) && match(observable.Value))
                seen.TrySetResult(observable.Value);
        };
        return seen.Task;
    }

    /// <summary>Disposes the adapter and waits for its collection to finish before the pump goes.</summary>
    private static async Task StopAsync<T>(KotlinStateFlowObservable<T> observable)
    {
        observable.Dispose();
        await observable.Completion.WaitAsync(Patience);
    }

    /// <summary>
    /// A one-thread <see cref="SynchronizationContext"/>: <see cref="Post"/> queues, a dedicated
    /// thread drains in order. Stands in for a XAML dispatcher without a UI framework.
    /// </summary>
    private sealed class CatPump : SynchronizationContext, IDisposable
    {
        private readonly BlockingCollection<(SendOrPostCallback Callback, object? State)> _queue =
            new();
        private readonly Thread _thread;

        public CatPump()
        {
            _thread = new Thread(Run) { IsBackground = true, Name = "Oreo and Mylo's UI thread" };
            _thread.Start();
        }

        public int ThreadId => _thread.ManagedThreadId;

        public override void Post(SendOrPostCallback d, object? state) => _queue.Add((d, state));

        public override void Send(SendOrPostCallback d, object? state) =>
            throw new NotSupportedException("the adapter only posts");

        /// <summary>Completes once every callback posted before this call has run.</summary>
        public Task DrainAsync()
        {
            var drained = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            Post(_ => drained.SetResult(), null);
            return drained.Task;
        }

        public void Dispose()
        {
            _queue.CompleteAdding();
            _thread.Join();
            _queue.Dispose();
        }

        private void Run()
        {
            SetSynchronizationContext(this);
            foreach ((SendOrPostCallback callback, object? state) in _queue.GetConsumingEnumerable())
                callback(state);
        }
    }
}

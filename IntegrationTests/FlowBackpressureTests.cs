using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

// ADR-207: a Kotlin `Flow` collected through `await foreach` is credit-gated. The Kotlin producer
// parks inside its next `emit` until the C# reader has taken the item before it, so a slow reader
// stalls the producer instead of letting it run ahead into an unbounded buffer. `Emitted` counts
// emits that returned: one item handed out plus one waiting on the C# side reads 2.
public class FlowBackpressureTests
{
    [Fact]
    public async Task SlowReader_StallsTheProducer_OneItemAhead()
    {
        // Oreo eats one treat and naps; the conveyor waits for him instead of piling up 99 more.
        using var conveyor = new TreatConveyor();
        await using IAsyncEnumerator<int> belt = conveyor.Belt.GetAsyncEnumerator();
        Assert.True(await belt.MoveNextAsync());
        Assert.Equal(0, belt.Current);

        await Task.Delay(300);
        // Item 0 handed out, item 1 waiting on the C# side, the body parked in the next `emit`.
        // Unbounded, this read 100.
        Assert.Equal(2, conveyor.Emitted);

        int rest = 0;
        while (await belt.MoveNextAsync())
        {
            Assert.Equal(rest + 1, belt.Current);
            rest++;
        }
        Assert.Equal(99, rest);
        Assert.Equal(100, conveyor.Emitted);
    }

    [Fact]
    public async Task CancelWhileStalled_DrainsAtMostOneItem()
    {
        using var conveyor = new TreatConveyor();
        using var cts = new CancellationTokenSource();
        var crates = new List<IReadOnlyList<string>>();
        await foreach (IReadOnlyList<string> crate in conveyor.Crates.WithCancellation(cts.Token))
        {
            crates.Add(crate);
            if (crates.Count == 1)
            {
                await Task.Delay(300);
                cts.Cancel();
            }
        }

        // Unbounded, everything emitted before the cancel was still handed out after it.
        int drainedAfterCancel = crates.Count - 1;
        // Crate 1 was already waiting on the C# side when the cancel landed (the reader returned the
        // credit for crate 0 300 ms earlier), so exactly one crate is handed out after it.
        Assert.True(
            drainedAfterCancel == 1,
            $"expected exactly one crate after the cancel, got {drainedAfterCancel} (emitted {conveyor.Emitted})");
        Assert.Equal(new[] { "treat 0" }, crates[0]);
        Assert.InRange(conveyor.Emitted, 2, 3);
    }

    [Fact]
    public async Task ThrowAfterAStall_SurfacesOnTheStream()
    {
        using var conveyor = new TreatConveyor();
        var seen = new List<int>();
        KotlinInvalidOperationException ex =
            await Assert.ThrowsAsync<KotlinInvalidOperationException>(async () =>
            {
                await foreach (int treat in conveyor.Spoiled)
                {
                    seen.Add(treat);
                    await Task.Delay(50);
                }
            });

        Assert.Equal(new[] { 1, 2, 3 }, seen);
        Assert.Equal("conveyor jammed", ex.Message);
        Assert.Equal("kotlin.IllegalStateException", ex.KotlinType);
    }

    [Fact]
    public async Task UnreadUndisposedEnumerator_DoesNotHangTheOwnersDisposeAsync()
    {
        // The reader takes one treat and walks off without disposing the enumerator. The producer
        // is parked on a credit nobody will return; the owner's drain must cancel it, not wait.
        var conveyor = new TreatConveyor();
        IAsyncEnumerator<int> belt = conveyor.Belt.GetAsyncEnumerator();
        Assert.True(await belt.MoveNextAsync());
        await Task.Delay(100);

        await conveyor.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10));

        // The cancelled collection ends the stream: the one waiting item, then the end.
        int rest = 0;
        while (await belt.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10))) rest++;
        Assert.InRange(rest, 0, 1);
        await belt.DisposeAsync();
    }
}

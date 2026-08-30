using TestLibrary;
using TestLibrary.Issue40;

namespace IntegrationTests;

/// <summary>
/// Issue #40: a <c>StateFlow&lt;Sealed&gt;</c> / <c>Flow&lt;Sealed&gt;</c> whose element is a sealed
/// BASE class must materialise in C# as the correct generated subclass.
///
/// Both <c>KotlinStateFlow&lt;T&gt;.Value</c> and <c>KotlinFlowEnumerator&lt;T&gt;.OnNext</c> route the
/// element through <c>NugetMarshal.Materialize&lt;T&gt;</c> with <c>T</c> = the abstract sealed base.
/// The ADR-094 factory registry holds entries for <c>LoadState.Idle</c> / <c>.Loading</c> /
/// <c>.Loaded</c> but none for <c>LoadState</c> itself, so nothing routes to the base's already
/// generated <c>LoadState.FromHandle(IntPtr)</c> discriminator.
///
/// Fixture: <see cref="Loader"/> (test-library, <c>issue40/Issue40Sample.kt</c>). Think of it as
/// Oreo's food-bowl sensor: idle, then filling, then finally full of dinner. Every transition is
/// driven explicitly by <see cref="Loader.Advance"/> -- no timers, so nothing races.
/// </summary>
public class Issue40Tests
{
    [Fact]
    public void StateFlowOfSealed_Value_Initially_MaterialisesTheDataObjectArm_OreosBowlSensorStartsIdle()
    {
        // Nobody has touched Oreo's bowl yet -- the sensor sits on the payload-free `data object` arm.
        using var loader = new Loader();
        using LoadState current = loader.State.Value;
        Assert.IsType<LoadState.Idle>(current);
    }

    [Fact]
    public void StateFlowOfSealed_Value_AfterAdvance_MaterialisesTheValuePayloadArm_TheBowlIsHalfFull()
    {
        // Mylo hears the kibble hit the bowl: the sensor moves to the arm carrying an Int payload.
        using var loader = new Loader();
        loader.Advance();

        using LoadState current = loader.State.Value;
        var loading = Assert.IsType<LoadState.Loading>(current);
        Assert.Equal(50, loading.Progress);
    }

    [Fact]
    public void StateFlowOfSealed_Value_AfterTwoAdvances_MaterialisesTheReferencePayloadArm_DinnerIsServed()
    {
        // Dinner is served -- the sensor lands on the arm carrying a String payload.
        using var loader = new Loader();
        loader.Advance();
        loader.Advance();

        using LoadState current = loader.State.Value;
        var loaded = Assert.IsType<LoadState.Loaded>(current);
        Assert.Equal("done", loaded.Payload);
    }

    [Fact]
    public async Task FlowOfSealed_AwaitForeach_YieldsEverySubclassArmWithItsPayload_TheWholeDinnerStory()
    {
        // The cold `history` flow replays all three acts of Oreo's dinner in order.
        using var loader = new Loader();
        var seen = new List<LoadState>();
        try
        {
            await foreach (LoadState state in loader.History)
                seen.Add(state);

            Assert.Equal(3, seen.Count);
            Assert.IsType<LoadState.Idle>(seen[0]);

            var loading = Assert.IsType<LoadState.Loading>(seen[1]);
            Assert.Equal(50, loading.Progress);

            var loaded = Assert.IsType<LoadState.Loaded>(seen[2]);
            Assert.Equal("done", loaded.Payload);
        }
        finally
        {
            foreach (LoadState state in seen) state.Dispose();
        }
    }

    [Fact]
    public async Task StateFlowOfSealed_AwaitForeach_ReplaysCurrentSubclassAsFirstElement_MyloSubscribesMidMeal()
    {
        // Mylo wanders in after the bowl is already full. A fresh subscriber to the hot StateFlow
        // must immediately see the CURRENT arm (replay-1), correctly discriminated as Loaded.
        // Bounded by cancellation -- a StateFlow never completes on its own.
        using var loader = new Loader();
        loader.Advance();
        loader.Advance();

        var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        LoadState? first = null;
        await foreach (LoadState state in loader.State.WithCancellation(cts.Token))
        {
            first = state;
            cts.Cancel();
        }

        Assert.NotNull(first);
        using (first)
        {
            var loaded = Assert.IsType<LoadState.Loaded>(first);
            Assert.Equal("done", loaded.Payload);
        }
    }
}

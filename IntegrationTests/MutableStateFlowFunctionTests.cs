using TestLibrary;
using TestLibrary.Dispenser;

namespace IntegrationTests;

/// <summary>
/// ADR-071: a <c>MutableStateFlow&lt;T&gt;</c> returned from a <b>function</b> must be the flow the
/// call returned, not a fresh one per access. Today the generated
/// <c>KotlinMutableStateFlow&lt;T&gt;</c> is three lambdas that each re-invoke the Kotlin function,
/// so a write goes into one throwaway flow and the next read builds another and hands back the
/// initial value.
///
/// Fixture: <see cref="CatSnackDispenser"/>, whose <c>Level()</c> builds a fresh
/// <c>MutableStateFlow(3)</c> on every call on purpose. Every shipped fixture on this route hands
/// back a stable instance (<c>CatMoodTracker.TreatJar()</c> returns a property, <c>CatRadio</c> and
/// <c>KeywordRoutes</c> memoise), so those stay green whatever the wrapper does.
///
/// Oreo believes the snack dispenser refills when he stares at it. The bridge must not agree.
/// </summary>
public class MutableStateFlowFunctionTests
{
    [Fact]
    public void FunctionReturn_WriteThenRead_OreoTopsUpTheDispenserAndItStaysToppedUp()
    {
        // The write and the read must hit the same flow: the one Level() handed out.
        using var dispenser = new CatSnackDispenser();
        using var level = dispenser.Level();
        level.Value = 7;
        Assert.Equal(7, level.Value);
    }

    [Fact]
    public void FunctionReturn_WriteIsVisibleToKotlin_OreosTopUpLandsInTheDispenserItself()
    {
        // Kotlin-side read-back of the same flow: proves the write landed in Kotlin, and that it
        // landed in the flow the caller was handed rather than in a throwaway one.
        using var dispenser = new CatSnackDispenser();
        using var level = dispenser.Level();
        level.Value = 7;
        Assert.Equal(7, dispenser.LastLevel());
    }

    [Fact]
    public async Task FunctionReturn_WriteIsObservedByALiveCollector_MyloWatchesTheDispenserFill()
    {
        // The collect side must subscribe to the held flow too, not to yet another fresh one.
        using var dispenser = new CatSnackDispenser();
        using var level = dispenser.Level();
        level.Value = 7;

        var seen = new List<int>();
        var cts = new CancellationTokenSource();
        await foreach (var n in level.WithCancellation(cts.Token))
        {
            seen.Add(n);
            cts.Cancel(); // StateFlow never completes on its own
        }
        Assert.Equal(7, seen[0]);
    }

    [Fact]
    public void FunctionReturn_SecondCallIsAFreshFlow_MyloGetsHisOwnDispenserReading()
    {
        // Kotlin semantics, kept exactly: a second call runs the body again, so it is a new flow at
        // its initial value. Holding the first flow must not turn the wrapper into a cache.
        using var dispenser = new CatSnackDispenser();
        using var oreosLevel = dispenser.Level();
        oreosLevel.Value = 7;

        using var mylosLevel = dispenser.Level();
        Assert.Equal(3, mylosLevel.Value);
        Assert.Equal(7, oreosLevel.Value); // the first flow is untouched by the second call
    }
}

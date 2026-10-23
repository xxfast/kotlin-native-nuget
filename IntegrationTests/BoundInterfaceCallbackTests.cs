using TestLibrary.Menagerie;
using Test.Menagerie;

namespace IntegrationTests;

/// <summary>
/// ADR-239: a bound C# interface (<c>IFeedable</c>, ADR-088) as a callback payload and result on
/// the plain and <c>suspend</c> callback routes. Both cross as the reverse pipeline's GCHandle, so
/// the C#-implemented resident reaches the C# lambda as the same managed object, and a resident a
/// C# lambda returns is the one Kotlin reads. Oreo and Mylo leave this pen to the goats.
/// </summary>
public class BoundInterfaceCallbackTests
{
    private sealed class Goat(int legs) : IFeedable
    {
        public string Describe() => $"a {legs}-legged goat";

        public int Legs => legs;

        public void Feed(string food) { }

        public string? Nickname { get; set; }
    }

    [Fact]
    public void Plain_PayloadAndResult()
    {
        using var pen = new Pen();
        var goat = new Goat(4);
        pen.Adopt(goat);

        IFeedable? seen = null;
        Assert.Equal(4, pen.Inspect(feedable =>
        {
            seen = feedable;
            return feedable.Legs;
        }));
        Assert.Same(goat, seen);
        Assert.Equal(3, pen.Fetch(() => new Goat(3)));
    }

    [Fact]
    public async Task Suspend_PayloadAndResult()
    {
        using var pen = new Pen();
        var goat = new Goat(4);
        pen.Adopt(goat);

        IFeedable? seen = null;
        Assert.Equal(4, await pen.InspectLaterAsync((feedable, _) =>
        {
            seen = feedable;
            return Task.FromResult(feedable.Legs);
        }));
        Assert.Same(goat, seen);
        Assert.Equal(2, await pen.FetchLaterAsync(_ => Task.FromResult<IFeedable>(new Goat(2))));
    }

    // --- The erased positions: a stored pair, a returned lambda and a lambda property ---------

    [Fact]
    public void StoredPair_PayloadCrossesAsTheSameObject()
    {
        using var pen = new Pen();
        var goat = new Goat(4);
        pen.Adopt(goat);
        IFeedable? seen = null;

        using (IDisposable keeper = pen.AddKeeper(feedable =>
        {
            seen = feedable;
            return feedable.Legs;
        }))
        {
            Assert.Equal(4, pen.RollCall());
        }
        Assert.Equal(0, pen.RollCall());
        Assert.Same(goat, seen);
    }

    [Fact]
    public void ReturnedLambda_PayloadAndResult()
    {
        using var pen = new Pen();
        var goat = new Goat(4);
        pen.Adopt(goat);

        Func<IFeedable, int> counter = pen.LegCounter();
        Func<IFeedable> supplier = pen.ResidentSupplier();

        Assert.Equal(3, counter(new Goat(3)));
        Assert.Same(goat, supplier());
    }

    [Fact]
    public void LambdaProperty_ReadAndSet()
    {
        using var pen = new Pen();
        var goat = new Goat(4);
        pen.Adopt(goat);

        Assert.Same(goat, pen.OnSwap());
        pen.OnSwap = () => new Goat(5);
        Assert.Equal(5, pen.SwapLegs());
    }
}

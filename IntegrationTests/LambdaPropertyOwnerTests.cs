using TestLibrary;
using TestLibrary.Zoomrun;

namespace IntegrationTests;

/// <summary>
/// ADR-236: a lambda-typed property binds as a BCL delegate property (<c>Func</c>, <c>Action</c>, or the
/// <c>Func&lt;..., CancellationToken, Task&lt;...&gt;&gt;</c> shape for a <c>suspend</c> one) on every owner: an <c>object</c>, a default and a named companion, the top level, an enum's
/// body and its companion, a generic class (payloads typed by its <c>T</c>), and a Kotlin interface
/// on both its Kotlin-backed and C#-implemented halves. A <c>val</c> reads a delegate that calls
/// Kotlin; a <c>var</c> also takes any C# delegate, which Kotlin then calls; a nullable <c>var</c> reads and
/// writes <c>null</c>. Also a sealed base and its arm, and an ADR-039 listener interface. Also a lambda property carrying a nested generic class, and a
/// <c>suspend</c> member returning a <c>StateFlow</c>/<c>SharedFlow</c> with a plain lambda the
/// returned flow keeps calling. Oreo does 3 a.m. laps; Mylo keeps score.
/// </summary>
public class LambdaPropertyOwnerTests
{
    // (1) An object.

    [Fact]
    public void Landing_OnLap_ValInvokesKotlin()
    {
        int before = Landing.Laps;
        Landing.OnLap.Invoke(3);
        Assert.Equal(before + 3, Landing.Laps);
    }

    [Fact]
    public void Landing_Format_VarReadsKotlinAndTakesACSharpDelegate()
    {
        Func<double, string> original = Landing.Format;
        Assert.Equal("Oreo ran 2.5 m", original.Invoke(2.5));
        try
        {
            Landing.Format = metres => $"Mylo timed {metres} m";
            Assert.Equal("Mylo timed 4 m", Landing.Describe(4.0));
            Assert.Equal("Mylo timed 1 m", Landing.Format.Invoke(1.0));
        }
        finally
        {
            Landing.Format = original;
        }
        Assert.Equal("Oreo ran 3.0 m", Landing.Describe(3.0));
    }

    [Fact]
    public void Landing_OnBump_NullableVarRoundTripsNull()
    {
        Assert.Null(Landing.OnBump);
        Assert.Equal("nobody bumped Mylo", Landing.Bump("Mylo"));
        Landing.OnBump = who => $"Oreo bumped into {who}";
        try
        {
            Assert.NotNull(Landing.OnBump);
            Assert.Equal("Oreo bumped into Mylo", Landing.Bump("Mylo"));
            Assert.Equal("Oreo bumped into the vase", Landing.OnBump!.Invoke("the vase"));
        }
        finally
        {
            Landing.OnBump = null;
        }
        Assert.Null(Landing.OnBump);
        Assert.Equal("nobody bumped Mylo", Landing.Bump("Mylo"));
    }

    [Fact]
    public async Task Landing_OnSprint_SuspendValIsAnAsyncDelegate()
    {
        Func<int, CancellationToken, Task<string>> onSprint = Landing.OnSprint;
        Assert.Equal("Oreo sprinted 4 laps", await onSprint.Invoke(4, CancellationToken.None));
    }

    [Fact]
    public async Task Landing_OnSettle_NullableSuspendVarTakesACSharpAsyncDelegate()
    {
        Assert.Null(Landing.OnSettle);
        Assert.Equal("Oreo never settled", await Landing.SettleAsync("Oreo"));
        Landing.OnSettle = async (who, token) =>
        {
            await Task.Delay(1, token);
            return $"{who} curled up on the stairs";
        };
        try
        {
            Assert.Equal("Oreo curled up on the stairs", await Landing.SettleAsync("Oreo"));
        }
        finally
        {
            Landing.OnSettle = null;
        }
        Assert.Null(Landing.OnSettle);
    }

    // (2) The top level, on the file facade.

    [Fact]
    public void TopLevel_OnZoom_ValInvokesKotlin()
    {
        int before = ZoomrunSample.ZoomCount();
        ZoomrunSample.OnZoom.Invoke(2);
        Assert.Equal(before + 2, ZoomrunSample.ZoomCount());
    }

    [Fact]
    public void TopLevel_ZoomFormat_VarTakesACSharpDelegate()
    {
        Func<int, string> original = ZoomrunSample.ZoomFormat;
        Assert.Equal("5 zooms", original.Invoke(5));
        try
        {
            ZoomrunSample.ZoomFormat = count => $"Mylo counted {count}";
            Assert.Equal("Mylo counted 7", ZoomrunSample.FormatZooms(7));
        }
        finally
        {
            ZoomrunSample.ZoomFormat = original;
        }
        Assert.Equal("1 zooms", ZoomrunSample.FormatZooms(1));
    }

    [Fact]
    public void TopLevel_OnZoomOut_NullableVarRoundTripsNull()
    {
        Assert.Null(ZoomrunSample.OnZoomOut);
        Assert.Equal("Oreo stayed in", ZoomrunSample.ZoomOut("Oreo"));
        ZoomrunSample.OnZoomOut = who => $"{who} zoomed out the cat flap";
        try
        {
            Assert.Equal("Oreo zoomed out the cat flap", ZoomrunSample.ZoomOut("Oreo"));
        }
        finally
        {
            ZoomrunSample.OnZoomOut = null;
        }
        Assert.Null(ZoomrunSample.OnZoomOut);
    }

    [Fact]
    public async Task TopLevel_OnZoomAsync_SuspendVal()
    {
        Assert.Equal(6, await ZoomrunSample.OnZoomAsync.Invoke(3, CancellationToken.None));
    }

    // (3) A default-named companion, static on the class.

    [Fact]
    public async Task Racetrack_CompanionLambdaProperties()
    {
        Assert.Equal("on your marks", Racetrack.OnStart.Invoke());
        Assert.Equal("Mylo by a whisker", await Racetrack.OnPhotoFinish.Invoke("Mylo", CancellationToken.None));

        Func<string, string> original = Racetrack.Announce;
        Assert.Equal("Oreo takes the lead", original.Invoke("Oreo"));
        try
        {
            Racetrack.Announce = runner => $"{runner} stops to sniff the finish line";
            Assert.Equal("Mylo stops to sniff the finish line", Racetrack.Call("Mylo"));
        }
        finally
        {
            Racetrack.Announce = original;
        }

        Assert.Null(Racetrack.OnFinish);
        Assert.Equal("no finish line", Racetrack.Finish(1));
        Racetrack.OnFinish = place => $"Oreo came {place}st";
        try
        {
            Assert.Equal("Oreo came 1st", Racetrack.Finish(1));
        }
        finally
        {
            Racetrack.OnFinish = null;
        }
        Assert.Null(Racetrack.OnFinish);
    }

    // (4) A NAMED companion: the Kotlin call is `Burrow.Warren.onDig`, the C# surface is static on
    // `Burrow`.

    [Fact]
    public void Burrow_NamedCompanionLambdaProperties()
    {
        Assert.Equal(9, Burrow.OnDig.Invoke(3));

        Func<int, string> original = Burrow.Measure;
        Assert.Equal("2 paws deep", original.Invoke(2));
        try
        {
            Burrow.Measure = depth => $"Mylo dug {depth} under the couch";
            Assert.Equal("Mylo dug 4 under the couch", Burrow.Survey(4));
        }
        finally
        {
            Burrow.Measure = original;
        }

        string? flattened = null;
        Assert.Null(Burrow.OnCollapse);
        Burrow.OnCollapse = who => flattened = who;
        try
        {
            Assert.Equal("collapsed on Oreo", Burrow.Collapse("Oreo"));
            Assert.Equal("Oreo", flattened);
        }
        finally
        {
            Burrow.OnCollapse = null;
        }
        Assert.Equal("no collapse handler", Burrow.Collapse("Oreo"));
    }

    // (5) An enum's body (extension members on `StrideExtensions`) and its companion (static on
    // `StrideExtensions`).

    [Fact]
    public async Task Stride_EnumBodyLambdaProperties()
    {
        Assert.Equal(6, Stride.Trot.OnStride().Invoke(3));
        Assert.Equal(12, Stride.Gallop.OnStride().Invoke(3));
        Assert.Equal("gallop for 5", await Stride.Gallop.OnPace().Invoke(5, CancellationToken.None));

        Func<string, string> original = Stride.Trot.Cheer();
        Assert.Equal("Oreo trots", original.Invoke("Oreo"));
        try
        {
            Stride.Trot.SetCheer(who => $"{who} prances");
            Assert.Equal("Mylo prances", Stride.Trot.Shout("Mylo"));
            Assert.Equal("Mylo gallops", Stride.Gallop.Shout("Mylo"));
        }
        finally
        {
            Stride.Trot.SetCheer(original);
        }

        Assert.Null(Stride.Gallop.OnTrip());
        Stride.Gallop.SetOnTrip(step => $"Oreo tripped on step {step}");
        try
        {
            Assert.Equal("Oreo tripped on step 3", Stride.Gallop.Trip(3));
            Assert.Equal("steady on step 3", Stride.Trot.Trip(3));
        }
        finally
        {
            Stride.Gallop.SetOnTrip(null);
        }
        Assert.Null(Stride.Gallop.OnTrip());
    }

    [Fact]
    public void Stride_EnumCompanionLambdaProperties()
    {
        Assert.Equal(Stride.Gallop, StrideExtensions.OnPick.Invoke(4));

        Assert.Null(StrideExtensions.Fallback);
        Assert.Equal("no fallback for TROT", StrideExtensions.Recover(Stride.Trot));
        StrideExtensions.Fallback = stride => $"Mylo falls back to a {stride}";
        try
        {
            Assert.Equal("Mylo falls back to a Gallop", StrideExtensions.Recover(Stride.Gallop));
        }
        finally
        {
            StrideExtensions.Fallback = null;
        }
        Assert.Null(StrideExtensions.Fallback);
    }

    // (6) A generic class: the delegates are typed by the closed `T`.

    [Fact]
    public async Task Relay_GenericClassLambdaProperties_TypedByString()
    {
        using var relay = new Relay<string>("Oreo");
        Func<string, string> onPass = relay.OnPass;
        Assert.Equal("Oreo", onPass.Invoke("Mylo"));
        Assert.Equal("Mylo", relay.Baton);
        Assert.Equal("Mylo", await relay.OnCarry.Invoke("Mylo", CancellationToken.None));

        Assert.Equal("holding Mylo", relay.Describe());
        relay.Inspect = held => $"{held} has the baton in his mouth";
        Assert.Equal("Mylo has the baton in his mouth", relay.Describe());

        string? dropped = null;
        Assert.Null(relay.OnDrop);
        Assert.Equal("nobody watching", relay.Drop());
        relay.OnDrop = held => dropped = held;
        Assert.Equal("dropped", relay.Drop());
        Assert.Equal("Mylo", dropped);
        relay.OnDrop = null;
        Assert.Null(relay.OnDrop);
    }

    [Fact]
    public void Relay_GenericClassLambdaProperties_TypedByAClass()
    {
        using var red = new Skein("red");
        using var brown = new Skein("brown");
        using var relay = new Relay<Skein>(red);
        using Skein previous = relay.OnPass.Invoke(brown);
        Assert.Equal("red", previous.Colour);
        relay.Inspect = held => $"Mylo is unravelling the {held.Colour} skein";
        Assert.Equal("Mylo is unravelling the brown skein", relay.Describe());
    }

    // (7) A Kotlin interface: the Kotlin-backed half (read through the backing wrapper) and the
    // C#-implemented half (read by Kotlin through the bridge, and written by Kotlin).

    private sealed class FeatherWand : IPouncer
    {
        public Func<int, string> OnPounce { get; } = times => $"Oreo swatted the feather {times} times";

        public Func<string, string>? Cue { get; set; }

        public Func<int, CancellationToken, Task<int>> OnLeap { get; } = async (height, token) =>
        {
            await Task.Delay(1, token);
            return height * 10;
        };

        public void Dispose() { }
    }

    [Fact]
    public async Task Pouncer_KotlinBacked_LambdaPropertiesReadThroughTheWrapper()
    {
        IPouncer dot = ZoomrunSample.LaserDot();
        try
        {
            Assert.Equal("Mylo pounced 3 times", dot.OnPounce.Invoke(3));
            Assert.Equal(5, await dot.OnLeap.Invoke(4, CancellationToken.None));
            Assert.Null(dot.Cue);
            dot.Cue = word => $"Oreo heard {word}";
            Assert.Equal("Oreo heard treats", ZoomrunSample.Cue(dot, "treats"));
            dot.Cue = null;
            Assert.Null(dot.Cue);
            ZoomrunSample.Teach(dot, "Mylo, ");
            Assert.Equal("Mylo,  sit", dot.Cue!.Invoke("sit"));
        }
        finally
        {
            (dot as IDisposable)?.Dispose();
        }
    }

    [Fact]
    public async Task Pouncer_CSharpImplemented_KotlinReadsAndWritesTheLambdaProperties()
    {
        using var wand = new FeatherWand();
        Assert.Equal("Oreo swatted the feather 2 times", ZoomrunSample.Pounce(wand, 2));
        Assert.Equal("no cue for dinner", ZoomrunSample.Cue(wand, "dinner"));
        wand.Cue = word => $"Mylo came running for {word}";
        Assert.Equal("Mylo came running for dinner", ZoomrunSample.Cue(wand, "dinner"));
        ZoomrunSample.Teach(wand, "Oreo:");
        Assert.NotNull(wand.Cue);
        Assert.Equal("Oreo: dinner", wand.Cue!.Invoke("dinner"));
        Assert.Equal("Oreo: dinner", ZoomrunSample.Cue(wand, "dinner"));
    }

    // (8) A lambda property carrying a nested generic class (generic type arguments on the
    // lambda-property route), on an ordinary class and on an object.

    [Fact]
    public void YarnBin_LambdaPropertyCarryingANestedGenericClass()
    {
        using var bin = new YarnBin("Oreo");
        Func<Tote<Tote<Skein>>> original = bin.Unwind;
        using (Tote<Tote<Skein>> outer = original.Invoke())
        using (Tote<Skein> inner = outer.Item)
        using (Skein skein = inner.Item)
        {
            Assert.Equal("Oreo's red", skein.Colour);
        }
        Assert.Equal("Oreo's red", bin.Unravel());

        bin.Unwind = () => new Tote<Tote<Skein>>(new Tote<Skein>(new Skein("Mylo's creamy")));
        Assert.Equal("Mylo's creamy", bin.Unravel());

        using (var tote = new Tote<Skein>(new Skein("black and white")))
        {
            Assert.Equal("black and white", bin.OnSort.Invoke(tote));
        }

        Assert.Null(bin.OnTangle);
        Assert.Equal("untangled", bin.Tangle("blue"));
        bin.OnTangle = tote => new Tote<Skein>(new Skein($"knotted {tote.Item.Colour}"));
        Assert.Equal("knotted blue", bin.Tangle("blue"));
        bin.OnTangle = null;
        Assert.Null(bin.OnTangle);
    }

    [Fact]
    public void YarnShop_ObjectLambdaPropertyCarryingANestedGenericClass()
    {
        Func<Tote<Tote<Skein>>> original = YarnShop.Unwind;
        Assert.Equal("Mylo's brown", YarnShop.Unravel());
        try
        {
            YarnShop.Unwind = () => new Tote<Tote<Skein>>(new Tote<Skein>(new Skein("Oreo's black")));
            Assert.Equal("Oreo's black", YarnShop.Unravel());
        }
        finally
        {
            YarnShop.Unwind = original;
        }
        Assert.Equal("Mylo's brown", YarnShop.Unravel());
    }

    // (10) A sealed base and its arm, on the same planned route.

    [Fact]
    public async Task Chase_SealedBaseAndArmLambdaProperties()
    {
        using Chase chase = ZoomrunSample.Mouse("Squeaky");
        Assert.Null(chase.OnPounce);
        Assert.Equal("no pounce", chase.Pounce(2));
        chase.OnPounce = times => $"Oreo pounced {times} times";
        Assert.Equal("Oreo pounced 2 times", chase.Pounce(2));
        Assert.Equal("Oreo pounced 5 times", chase.OnPounce!.Invoke(5));
        chase.OnPounce = null;
        Assert.Null(chase.OnPounce);
        Assert.Equal(4, await chase.OnPurr.Invoke(3, CancellationToken.None));

        var mouse = (Chase.Mouse)chase;
        Func<string, string> original = mouse.Squeak;
        Assert.Equal("Squeaky squeaks at Mylo", original.Invoke("Mylo"));
        mouse.Squeak = who => $"Squeaky hides from {who}";
        Assert.Equal("Squeaky hides from Oreo", mouse.Hear("Oreo"));
        mouse.Squeak = original;
        Assert.Equal("Squeaky squeaks at Oreo", mouse.Hear("Oreo"));

        Assert.Null(mouse.OnCaught);
        Assert.Equal("Squeaky got away from Oreo", await mouse.CatchByAsync("Oreo"));
        mouse.OnCaught = async (who, token) =>
        {
            await Task.Delay(1, token);
            return $"{who} caught Squeaky";
        };
        Assert.Equal("Oreo caught Squeaky", await mouse.CatchByAsync("Oreo"));
        mouse.OnCaught = null;
        Assert.Null(mouse.OnCaught);
    }

    // (11) An ADR-039 listener interface: Kotlin reads the C# listener's lambda properties back
    // through the subscription and calls them, and writes one.

    private sealed class LapCounter : ILapListener
    {
        public Func<int, string> OnLap { get; } = count => $"Mylo clocked lap {count}";

        public Func<int, int>? OnSplit { get; set; }

        public void Dispose() { }
    }

    [Fact]
    public void Stopwatch_ListenerLambdaProperties_KotlinReadsAndWritesThem()
    {
        using var stopwatch = new Stopwatch("Mylo");
        var counter = new LapCounter();
        using (IDisposable sub = stopwatch.AddLapListener(counter))
        {
            Assert.Equal("Mylo clocked lap 3", stopwatch.Lap(3));
            Assert.Equal(-1, stopwatch.Split(10));
            counter.OnSplit = seconds => seconds * 2;
            Assert.Equal(20, stopwatch.Split(10));
            stopwatch.Coach(5);
            Assert.NotNull(counter.OnSplit);
            Assert.Equal(15, counter.OnSplit!.Invoke(10));
            Assert.Equal(15, stopwatch.Split(10));
        }
        Assert.Equal(0, stopwatch.Count());
    }

    // (9) A suspend member returning a StateFlow/SharedFlow with a plain lambda: Kotlin keeps
    // calling the delegate after the Task completed, for as long as the flow lives.

    [Fact]
    public async Task LaserTower_BeamAsync_StateFlowResultKeepsTheDelegateAlive()
    {
        using var tower = new LaserTower("Oreo");
        using KotlinStateFlow<int> glow = await tower.BeamAsync(level => level * 2 + 1);
        Assert.Equal(1, glow.Value);
        Assert.Equal(7, tower.Pulse(3));
        Assert.Equal(7, glow.Value);
        Assert.Equal(11, tower.Pulse(5));
        Assert.Equal(11, glow.Value);
    }

    [Fact]
    public async Task LaserTower_FlashAsync_SharedFlowResultKeepsTheDelegateAlive()
    {
        using var tower = new LaserTower("Mylo");
        using KotlinSharedFlow<string> flashes = await tower.FlashAsync(times => $"Mylo chased {times} flickers");
        Assert.True(tower.Flick(1));
        Assert.True(tower.Flick(2));
        Assert.Equal(new[] { "Mylo chased 1 flickers", "Mylo chased 2 flickers" }, flashes.ReplayCache);
    }

    [Fact]
    public async Task Lighthouse_ShineAsync_ObjectStateFlowResultKeepsTheDelegateAlive()
    {
        using KotlinStateFlow<int> beam = await Lighthouse.ShineAsync(angle => angle + 90);
        Assert.Equal(90, beam.Value);
        Assert.Equal(135, Lighthouse.Turn(45));
        Assert.Equal(135, beam.Value);
    }
}

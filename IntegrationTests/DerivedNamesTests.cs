using TestLibrary;
using TestLibrary.Reserved;

namespace IntegrationTests;

/// <summary>
/// Generator names <em>derived</em> from a user parameter's name, meeting a real user parameter of
/// the same spelling: the <c>${name}HasValue</c> presence flag (plan route for primitives, enums,
/// value classes, <c>Char</c>, <c>Instant</c> and <c>Duration</c>; legacy suspend / Flow /
/// StateFlow routes), the ADR-164 dispatcher's <c>${name}IsSet</c> slot and its
/// <c>default_${name}</c> / <c>mask</c> / C# <c>${name}Value</c> locals, and the legacy routes'
/// <c>${name}Arg</c> local and fixed <c>scopeHandle</c> / <c>userData</c> slots.
/// <para>
/// Expected once fixed: the <em>generator's</em> identifier moves on a collision and the user's
/// never does, so every argument below is passed by name with exactly the Kotlin spelling. That is
/// the opposite of <c>ReservedNamesTests</c>, where fixed literals move the user's parameter.
/// </para>
/// <para>
/// Red today is twofold. Most shapes declare one name twice in the generated <c>@CName</c> export,
/// so the Kotlin/Native compile fails with <c>Conflicting declarations</c> and nothing here builds.
/// <c>Pantry.Pour</c> and <c>Pantry.Ration</c> are the silent pair: they compile, and Kotlin reads
/// the dispatcher's own <c>default_limit</c> / <c>mask</c> locals instead of the caller's
/// arguments.
/// </para>
/// <para>
/// Every fixture returns its arguments joined with <c>|</c>, and each test crosses the values (a
/// null next to <c>true</c>, a value next to <c>false</c>) so a shadowed flag reads back wrong.
/// Oreo (black, white in the middle) rations the kibble; Mylo (brown and creamy) checks whether the
/// bowl has any value at all.
/// </para>
/// </summary>
public class DerivedNamesTests
{
    // ---- plan route, `${name}HasValue` ----

    [Theory]
    [InlineData(null, true, "null|true")]
    [InlineData(7, false, "7|false")]
    public void Measure_TopLevelHasValueCollision_KeepsBothArguments(int? limit, bool limitHasValue, string expected)
    {
        Assert.Equal(expected, DerivedNamesSample.Measure(limit: limit, limitHasValue: limitHasValue));
    }

    [Theory]
    [InlineData(true, null, "true|null")]
    [InlineData(false, 7, "false|7")]
    public void MeasureReversed_FlagBeforeTheValue_KeepsBothArguments(bool limitHasValue, int? limit, string expected)
    {
        Assert.Equal(expected, DerivedNamesSample.MeasureReversed(limitHasValue: limitHasValue, limit: limit));
    }

    [Fact]
    public void Fill_MemberHasValueCollision_KeepsBothArguments()
    {
        using var pantry = new Pantry();

        Assert.Equal("null|true", pantry.Fill(limit: null, limitHasValue: true));
        Assert.Equal("7|false", pantry.Fill(limit: 7, limitHasValue: false));
    }

    [Fact]
    public void Hopper_ConstructorHasValueCollision_KeepsBothArguments()
    {
        using var oreo = new Hopper(limit: null, limitHasValue: true);
        using var mylo = new Hopper(limit: 7, limitHasValue: false);

        Assert.Equal("null|true", oreo.Describe());
        Assert.Equal("7|false", mylo.Describe());
    }

    [Fact]
    public void Judge_EnumHasValueCollision_KeepsBothArguments()
    {
        Assert.Equal("null|true", DerivedNamesSample.Judge(mood: null, moodHasValue: true));
        Assert.Equal("RAVENOUS|false", DerivedNamesSample.Judge(mood: Appetite.Ravenous, moodHasValue: false));
    }

    [Fact]
    public void Weigh_ValueClassHasValueCollision_KeepsBothArguments()
    {
        Assert.Equal("null|true", DerivedNamesSample.Weigh(w: null, wHasValue: true));
        Assert.Equal("40|false", DerivedNamesSample.Weigh(w: new Scoop(40), wHasValue: false));
    }

    [Fact]
    public void Wait_DurationHasValueCollision_KeepsBothArguments()
    {
        Assert.Equal("null|true", DerivedNamesSample.Wait(d: null, dHasValue: true));
        Assert.Equal("90|false", DerivedNamesSample.Wait(d: TimeSpan.FromSeconds(90), dHasValue: false));
    }

    [Fact]
    public void Letter_CharHasValueCollision_KeepsBothArguments()
    {
        Assert.Equal("null|true", DerivedNamesSample.Letter(c: null, cHasValue: true));
        Assert.Equal("M|false", DerivedNamesSample.Letter(c: 'M', cHasValue: false));
    }

    [Fact]
    public void Stamp_InstantHasValueCollision_KeepsBothArguments()
    {
        Assert.Equal("null|true", DerivedNamesSample.Stamp(at: null, atHasValue: true));
        Assert.Equal(
            "1000|false",
            DerivedNamesSample.Stamp(at: DateTimeOffset.FromUnixTimeSeconds(1000), atHasValue: false));
    }

    // ---- plan route, ADR-164 dispatcher names ----

    [Fact]
    public void Top_DefaultedHasValueCollision_UnsetAndExplicitBothArrive()
    {
        using var pantry = new Pantry();

        Assert.Equal("3|true", pantry.Top());
        Assert.Equal("null|false", pantry.Top(limit: null, limitHasValue: false));
        Assert.Equal("7|false", pantry.Top(limit: 7, limitHasValue: false));
    }

    [Fact]
    public void Serve_IsSetSlotCollision_KeepsBothArguments()
    {
        using var pantry = new Pantry();

        Assert.Equal("3|true", pantry.Serve(limit: default, limitIsSet: true));
        Assert.Equal("7|false", pantry.Serve(limit: 7, limitIsSet: false));
    }

    [Fact]
    public void Pour_DefaultLocalCollision_TheUserArgumentIsNotShadowed()
    {
        // Silent today: `val default_limit = if (limitHasValue) limit else null` shadows the user's
        // `default_limit`, so Kotlin reads back "7|7" and "3|null".
        using var pantry = new Pantry();

        Assert.Equal("7|9", pantry.Pour(limit: 7, default_limit: 9));
        Assert.Equal("3|9", pantry.Pour(limit: default, default_limit: 9));
        Assert.Equal("7|null", pantry.Pour(limit: 7, default_limit: null));
    }

    [Fact]
    public void Ration_MaskLocalCollision_TheUserArgumentIsNotTheBitmask()
    {
        // Silent today: the dispatcher's `var mask` shadows the user's `mask`, so Kotlin reads the
        // bitmask ("7|1", "3|0") instead of the 5 Oreo asked for.
        using var pantry = new Pantry();

        Assert.Equal("7|5", pantry.Ration(limit: 7, mask: 5));
        Assert.Equal("3|5", pantry.Ration(limit: null, mask: 5));
    }

    [Fact]
    public void Ladle_ValueLocalCollision_KeepsBothArguments()
    {
        // The C# wrapper unwraps `Optional<int?> limit` into a `limitValue` local.
        using var pantry = new Pantry();

        Assert.Equal("7|9", pantry.Ladle(limit: 7, limitValue: 9));
        Assert.Equal("3|9", pantry.Ladle(limit: default, limitValue: 9));
    }

    // ---- legacy routes ----

    [Fact]
    public async Task FillAsync_SuspendMemberHasValueCollision_KeepsBothArguments()
    {
        await using var larder = new Larder();

        Assert.Equal("null|true", await larder.FillAsync(limit: null, limitHasValue: true));
        Assert.Equal("7|false", await larder.FillAsync(limit: 7, limitHasValue: false));
    }

    [Fact]
    public async Task PortionAsync_TopLevelSuspendHasValueCollision_KeepsBothArguments()
    {
        Assert.Equal("null|true", await DerivedNamesSample.PortionAsync(limit: null, limitHasValue: true));
        Assert.Equal("7|false", await DerivedNamesSample.PortionAsync(limit: 7, limitHasValue: false));
    }

    [Fact]
    public async Task Snacks_FlowMemberHasValueCollision_CapturesBothArguments()
    {
        await using var larder = new Larder();
        var seen = new List<string>();

        await foreach (string snack in larder.Snacks(limit: null, limitHasValue: true))
            seen.Add(snack);
        await foreach (string snack in larder.Snacks(limit: 7, limitHasValue: false))
            seen.Add(snack);

        Assert.Equal(new List<string> { "null|true", "7|false" }, seen);
    }

    [Fact]
    public async Task Bowl_StateFlowMemberHasValueCollision_CarriesBothArguments()
    {
        await using var larder = new Larder();
        using KotlinStateFlow<string> empty = larder.Bowl(limit: null, limitHasValue: true);
        using KotlinStateFlow<string> full = larder.Bowl(limit: 7, limitHasValue: false);

        Assert.Equal("null|true", empty.Value);
        Assert.Equal("7|false", full.Value);
    }

    [Fact]
    public async Task DishAsync_SuspendStateFlowHasValueCollision_CarriesBothArguments()
    {
        await using var larder = new Larder();
        using KotlinStateFlow<string> empty = await larder.DishAsync(limit: null, limitHasValue: true);
        using KotlinStateFlow<string> full = await larder.DishAsync(limit: 7, limitHasValue: false);

        Assert.Equal("null|true", empty.Value);
        Assert.Equal("7|false", full.Value);
    }

    [Fact]
    public async Task MixAsync_ArgLocalCollision_EachHandleReachesItsOwnParameter()
    {
        // `x` lowers to a `val xArg = ...` local that shadows the user's `xArg` handle.
        using var oreo = new Kibble("Oreo Crunch");
        using var mylo = new Kibble("Mylo Malt");

        Assert.Equal("Oreo Crunch|Mylo Malt", await DerivedNamesSample.MixAsync(x: oreo, xArg: mylo));
    }

    [Fact]
    public async Task TallyAsync_TopLevelUserDataCollision_KeepsBothArguments()
    {
        Assert.Equal("2|5", await DerivedNamesSample.TallyAsync(scopeHandle: 2, userData: 5));
    }

    [Fact]
    public async Task CountAsync_MemberScopeHandleAndUserDataCollision_KeepsBothArguments()
    {
        await using var larder = new Larder();

        Assert.Equal("2|5", await larder.CountAsync(scopeHandle: 2, userData: 5));
    }

    // ---- legacy Flow routes' fixed names ----

    [Fact]
    public async Task Trickle_FlowFixedNamesCollision_EveryArgumentArrives()
    {
        // The collect lambda's own `(onNext, onComplete, onError, userData)` would shadow these.
        await using var spout = new Spout();
        var seen = new List<string>();

        await foreach (string drop in spout.Trickle(
                           onNext: 1, onComplete: 2, onError: 3, userData: 4, scopeHandle: 5, obj: 6, scope: 7))
            seen.Add(drop);

        Assert.Equal(new List<string> { "1|2|3|4|5|6|7" }, seen);
    }

    [Fact]
    public async Task Gauge_StateFlowLambdaNamesCollision_CollectAndValueBothCarryTheArguments()
    {
        await using var spout = new Spout();
        using KotlinStateFlow<string> gauge = spout.Gauge(onNext: 8, userData: 9);
        var seen = new List<string>();

        await foreach (string reading in gauge)
        {
            seen.Add(reading);
            break;
        }

        Assert.Equal("8|9", gauge.Value);
        Assert.Equal(new List<string> { "8|9" }, seen);
    }

    [Fact]
    public async Task Level_HeldMutableStateFlowLocalsCollision_KeepsBothArguments()
    {
        await using var spout = new Spout();
        using KotlinMutableStateFlow<string> level = spout.Level(flow: 3, collectScope: 4);

        Assert.Equal("3|4", level.Value);
    }

    // ---- plan route's callback slots and C# wrapper locals ----

    [Fact]
    public void Tick_CallbackSlotAndLocalCollision_EveryArgumentArrives()
    {
        Assert.Equal(
            "7|1|2|3|4",
            DerivedNamesSample.Tick(onTick: () => 7, onTickPtr: 1, onTickUserData: 2, onTickNative: 3, onTickCtx: 4));
    }

    [Fact]
    public void Label_CollectionHandleLocalCollision_KeepsBothArguments()
    {
        Assert.Equal(
            "Oreo,Mylo|5",
            DerivedNamesSample.Label(tags: new List<string> { "Oreo", "Mylo" }, tagsHandle: 5));
    }

    // ---- legacy suspend routes' wrapper and body locals ----

    [Fact]
    public async Task BrewAsync_SuspendWrapperLocalsCollision_EveryArgumentArrives()
    {
        Assert.Equal(
            "1|2|3|4|5|6",
            await DerivedNamesSample.BrewAsync(tcs: 1, callback: 2, callbackHandle: 3, job: 4, jobHandle: 5, reg: 6));
    }

    [Fact]
    public async Task SiftAsync_SuspendCollectionHandleLocalCollision_KeepsBothArguments()
    {
        Assert.Equal(
            "Oreo,Mylo|5",
            await DerivedNamesSample.SiftAsync(tags: new List<string> { "Oreo", "Mylo" }, tagsHandle: 5));
    }

    [Fact]
    public async Task StirAsync_SuspendBodyNamesCollision_EveryArgumentArrives()
    {
        await using var kettle = new Kettle();

        Assert.Equal(
            "1|2|3|4|5",
            await kettle.StirAsync(obj: 1, scope: 2, callbackPtr: 3, result: 4, resultRef: 5));
    }

    // ---- the generated trailing token and the callback delegate lambda ----

    [Fact]
    public async Task PourAsync_MemberCancellationTokenCollision_UserNameWinsAndTokenMoves()
    {
        await using var kettle = new Kettle();

        Assert.Equal("poured 3", await kettle.PourAsync(cancellationToken: 3));
        Assert.Equal("poured 4", await kettle.PourAsync(cancellationToken: 4, cancellationToken_: CancellationToken.None));
    }

    [Fact]
    public async Task SteepAsync_TopLevelCancellationTokenCollision_UserNameWinsAndTokenMoves()
    {
        Assert.Equal("steeped 3", await DerivedNamesSample.SteepAsync(cancellationToken: 3));
        Assert.Equal(
            "steeped 4",
            await DerivedNamesSample.SteepAsync(cancellationToken: 4, cancellationToken_: CancellationToken.None));
    }

    [Fact]
    public void Relay_CallbackNamedLikeTheDelegatePayload_ReceivesItsArgument()
    {
        Assert.Equal("Oreo 4", DerivedNamesSample.Relay(a0: n => $"Oreo {n}"));
    }

    [Fact]
    public void Echo_CallbackNamedLikeTheDelegateCtx_ReceivesItsArgument()
    {
        Assert.Equal("Mylo 5", DerivedNamesSample.Echo(ctx: n => $"Mylo {n}"));
    }
}

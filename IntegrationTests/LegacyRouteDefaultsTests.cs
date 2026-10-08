using System.Reflection;
using TestLibrary;
using TestLibrary.Suppertime;

namespace IntegrationTests;

/// <summary>
/// Kotlin default arguments on the <em>legacy</em> <c>suspend</c> / <c>Flow</c> routes, the mirror
/// of ADR-164's plan-route optional parameters: a defaulted Kotlin parameter binds as a C# optional
/// parameter, and omitting it makes <strong>Kotlin</strong> evaluate its own default.
/// <para>
/// Every default in <c>SuppertimeSample.kt</c> is one C# cannot fake with a constant: it reads the
/// receiver (<c>Dinnerbell.bowl</c>, <c>Course.Main.warmth</c>), bumps a per-instance counter, or is
/// computed from an earlier argument. A C#-only <c>= null</c> that passed a zero filler would read
/// back <c>0</c> (or <c>null</c>) here instead.
/// </para>
/// <para>
/// Every widened parameter is optional and nullable in C# (or, if it was already nullable,
/// <c>KotlinOptional&lt;T?&gt;</c>), and omitting it lets Kotlin evaluate its own default; a defaulted
/// handle or collection parameter stays required.
/// </para>
/// <para>
/// Oreo (black, white in the middle) is always first to the bowl; Mylo (brown and creamy) waits
/// for his portion, then asks for seconds.
/// </para>
/// </summary>
public class LegacyRouteDefaultsTests
{
    // ---- class suspend member ----

    [Fact]
    public async Task FeedAsync_AllDefaultsOmitted_KotlinEvaluatesEachDefault()
    {
        using var bell = new Dinnerbell(40);

        // portion = bowl + ++served, note = "for $cat", treats = 5: all evaluated in Kotlin.
        Assert.Equal("Oreo|41|for Oreo|5", await bell.FeedAsync("Oreo"));
    }

    [Fact]
    public async Task FeedAsync_OmittedTwice_KotlinReEvaluatesTheDefaultPerCall()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal("Oreo|41|for Oreo|5", await bell.FeedAsync("Oreo"));
        // A supplied portion never runs the default, so the counter does not move.
        Assert.Equal("Mylo|7|for Mylo|5", await bell.FeedAsync("Mylo", 7));
        Assert.Equal("Mylo|42|for Mylo|5", await bell.FeedAsync("Mylo"));
    }

    [Fact]
    public async Task FeedAsync_ZeroPortionSupplied_IsAValueNotUnset()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal("Oreo|0|for Oreo|5", await bell.FeedAsync("Oreo", 0));
    }

    [Fact]
    public async Task FeedAsync_NamedArgumentSkipsTheMiddleOptionals()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal("Mylo|41|for Mylo|2", await bell.FeedAsync("Mylo", treats: 2));
        Assert.Equal("Mylo|42|seconds|5", await bell.FeedAsync("Mylo", note: "seconds"));
    }

    [Fact]
    public async Task FeedAsync_NullableDefaultSetToNull_IsNullNotTheDefault()
    {
        using var bell = new Dinnerbell(40);

        // `treats: Int? = 5`: omitted is 5, an explicit null is null.
        Assert.Equal("Oreo|41|for Oreo|null", await bell.FeedAsync("Oreo", treats: null));
    }

    [Fact]
    public async Task FeedAsync_OnlyTheTrailingTokenPassed_DefaultsStillRunInKotlin()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal(
            "Oreo|41|for Oreo|5",
            await bell.FeedAsync("Oreo", cancellationToken: CancellationToken.None));
        Assert.Equal(
            "Mylo|3|dessert|null",
            await bell.FeedAsync("Mylo", 3, "dessert", null, CancellationToken.None));
    }

    [Fact]
    public async Task NapAsync_DefaultedMinutesAndATokenOnly_TokenStillCancels()
    {
        // bowl = 60: the Kotlin default is a one-minute nap, far longer than the test waits.
        using var bell = new Dinnerbell(60);
        using var cts = new CancellationTokenSource();

        Task<string> nap = bell.NapAsync(cancellationToken: cts.Token);
        await Task.Delay(50);
        cts.Cancel();

        await Assert.ThrowsAsync<TaskCanceledException>(() => nap);
    }

    [Fact]
    public void FeedAsync_WidenedParameters_AreOptionalAndNullable()
    {
        ParameterInfo[] parameters = typeof(Dinnerbell).GetMethod(nameof(Dinnerbell.FeedAsync))!.GetParameters();

        Assert.Equal(
            new[] { "cat", "portion", "note", "treats", "cancellationToken" },
            parameters.Select(p => p.Name).ToArray());
        Assert.False(parameters[0].IsOptional);
        Assert.Equal(typeof(int?), parameters[1].ParameterType);
        Assert.True(parameters[1].IsOptional);
        Assert.True(parameters[2].IsOptional);
        Assert.Equal(typeof(KotlinOptional<int?>), parameters[3].ParameterType);
        Assert.True(parameters[3].IsOptional);
    }

    // ---- class suspend returning StateFlow (ADR-068) ----

    [Fact]
    public async Task PlateAsync_DefaultsOmitted_StateFlowCarriesTheKotlinDefaults()
    {
        using var bell = new Dinnerbell(40);
        using KotlinStateFlow<string> plate = await bell.PlateAsync();

        Assert.Equal("80|kibble", plate.Value);
    }

    [Fact]
    public async Task PlateAsync_LabelSetToNullAndGramsSkipped_KeepsEachApart()
    {
        using var bell = new Dinnerbell(40);
        using KotlinStateFlow<string> bare = await bell.PlateAsync(label: null);
        using KotlinStateFlow<string> small = await bell.PlateAsync(grams: 5);

        Assert.Equal("80|null", bare.Value);
        Assert.Equal("5|kibble", small.Value);
    }

    // ---- sealed-arm suspend (ADR-118) ----

    [Fact]
    public async Task ServeAsync_SealedArmDefaultsOmitted_ReadsTheArmsOwnState()
    {
        using var supper = new Course.Main(6);

        Assert.Equal("tuna|12|5", await supper.ServeAsync());
    }

    [Fact]
    public async Task ServeAsync_SealedArmNamedAndNullArguments_OnlyThoseAreSet()
    {
        using var supper = new Course.Main(6);

        Assert.Equal("tuna|1|5", await supper.ServeAsync(minutes: 1));
        Assert.Equal("chicken|12|null", await supper.ServeAsync("chicken", sides: null));
    }

    [Fact]
    public async Task Nibbles_SealedArmFlowDefaultsOmitted_ReadsTheArmsOwnState()
    {
        using var supper = new Course.Main(6);
        var bites = new List<string>();

        await foreach (string bite in supper.Nibbles())
            bites.Add(bite);
        await foreach (string bite in supper.Nibbles(bite: "large", crumbs: null))
            bites.Add(bite);

        Assert.Equal(new List<string> { "6|small|5", "6|large|null" }, bites);
    }

    // ---- top-level suspend ----

    [Fact]
    public async Task WeighInAsync_TopLevelDefaultsOmitted_ComputedFromTheRequiredArgument()
    {
        // grams = name.length * 100: 400 for Oreo, 400 for Mylo, 300 for Tom. No C# constant fits.
        Assert.Equal("Oreo|400|Oreo on the scale|5", await SuppertimeSample.WeighInAsync("Oreo"));
        Assert.Equal("Tom|300|Tom on the scale|5", await SuppertimeSample.WeighInAsync("Tom"));
    }

    [Fact]
    public async Task WeighInAsync_TopLevelNamedSkipNullAndToken_ComposeWithTheDefaults()
    {
        Assert.Equal("Mylo|400|fluffy|5", await SuppertimeSample.WeighInAsync("Mylo", note: "fluffy"));
        Assert.Equal(
            "Mylo|400|Mylo on the scale|null",
            await SuppertimeSample.WeighInAsync("Mylo", treats: null, cancellationToken: CancellationToken.None));
    }

    // ---- class Flow member ----

    [Fact]
    public async Task Purrs_FlowDefaultsOmitted_KotlinEvaluatesThem()
    {
        using var bell = new Dinnerbell(40);
        var heard = new List<string>();

        await foreach (string purr in bell.Purrs("Oreo"))
            heard.Add(purr);

        Assert.Equal(new List<string> { "Oreo|40|purr-Oreo|5" }, heard);
    }

    [Fact]
    public async Task Purrs_FlowNamedSkipAndNullableSetToNull_OnlyThoseAreSet()
    {
        using var bell = new Dinnerbell(40);
        var heard = new List<string>();

        await foreach (string purr in bell.Purrs("Mylo", tag: "rumble"))
            heard.Add(purr);
        await foreach (string purr in bell.Purrs("Mylo", repeats: null))
            heard.Add(purr);

        Assert.Equal(new List<string> { "Mylo|40|rumble|5", "Mylo|40|purr-Mylo|null" }, heard);
    }

    // ---- class StateFlow member ----

    [Fact]
    public void Mood_StateFlowDefaultsOmitted_ValueCarriesTheKotlinDefaults()
    {
        using var bell = new Dinnerbell(40);
        using KotlinStateFlow<string> mood = bell.Mood();
        using KotlinStateFlow<string> sleepy = bell.Mood(word: "sleepy", extra: null);

        Assert.Equal("40|content|5", mood.Value);
        Assert.Equal("40|sleepy|null", sleepy.Value);
    }

    [Fact]
    public async Task Mood_StateFlowDefaultsOmitted_CollectCarriesThemToo()
    {
        using var bell = new Dinnerbell(40);
        using KotlinStateFlow<string> mood = bell.Mood(level: 9);

        await foreach (string reading in mood)
        {
            Assert.Equal("9|content|5", reading);
            break;
        }
    }

    // ---- class held MutableStateFlow member ----

    [Fact]
    public void Dish_HeldMutableStateFlowDefaultsOmitted_ValueCarriesTheKotlinDefaults()
    {
        using var bell = new Dinnerbell(40);
        using KotlinMutableStateFlow<string> dish = bell.Dish();
        using KotlinMutableStateFlow<string> chicken = bell.Dish(flavour: "chicken", topUp: null);

        Assert.Equal("40|salmon|5", dish.Value);
        Assert.Equal("40|chicken|null", chicken.Value);
    }

    // ---- defaulted handle parameters widen; a defaulted collection stays required ----

    [Fact]
    public async Task ShareAsync_HandleAndCollectionSupplied_TrailingScalarStillDefaults()
    {
        using var bell = new Dinnerbell(40);
        using var mat = new Placemat("Oreo");

        Assert.Equal(
            "Oreo|Oreo,Mylo|40",
            await bell.ShareAsync(mat, new List<string> { "Oreo", "Mylo" }));
    }

    [Fact]
    public async Task ShareAsync_MatNull_KotlinBuildsTheHouseMat()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal(
            "house|Oreo,Mylo|40",
            await bell.ShareAsync(null, new List<string> { "Oreo", "Mylo" }));
    }

    // ADR-164 rule 5: `mat` widens to `Placemat?` but the required `cats` after it keeps it
    // required-but-nullable; only the trailing `grams` gets a C# default.
    [Fact]
    public void ShareAsync_HandleBeforeRequiredCollection_IsNotOptional()
    {
        ParameterInfo[] parameters = typeof(Dinnerbell).GetMethod(nameof(Dinnerbell.ShareAsync))!.GetParameters();

        Assert.Equal("mat", parameters[0].Name);
        Assert.False(parameters[0].IsOptional);
        Assert.Equal("cats", parameters[1].Name);
        Assert.False(parameters[1].IsOptional);
        Assert.Equal("grams", parameters[2].Name);
        Assert.True(parameters[2].IsOptional);
    }

    [Fact]
    public async Task SettleAsync_MatOmitted_KotlinRunsTheDefault()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal("Oreo|house", await bell.SettleAsync("Oreo"));
    }

    [Fact]
    public async Task SettleAsync_MatNull_IsAValueNotUnset()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal("Oreo|floor", await bell.SettleAsync("Oreo", mat: null));
    }

    [Fact]
    public async Task SettleAsync_MatSupplied_CrossesAsABorrowedHandle()
    {
        using var bell = new Dinnerbell(40);
        using var mat = new Placemat("porch");

        Assert.Equal("Oreo|porch", await bell.SettleAsync("Oreo", mat));
    }

    [Fact]
    public async Task Lounge_FlowMatOmittedNullOrSupplied_EachReadsBackApart()
    {
        using var bell = new Dinnerbell(40);
        using var mat = new Placemat("porch");
        var heard = new List<string>();

        await foreach (string spot in bell.Lounge("Mylo"))
            heard.Add(spot);
        await foreach (string spot in bell.Lounge("Mylo", mat: null))
            heard.Add(spot);
        await foreach (string spot in bell.Lounge("Mylo", mat))
            heard.Add(spot);

        Assert.Equal(new List<string> { "Mylo|house", "Mylo|floor", "Mylo|porch" }, heard);
    }

    [Fact]
    public void Snooze_StateFlowMatOmitted_ReadsTheHouseMat()
    {
        using var bell = new Dinnerbell(40);
        using var mat = new Placemat("porch");
        using KotlinStateFlow<string> house = bell.Snooze();
        using KotlinStateFlow<string> porch = bell.Snooze(mat);

        Assert.Equal("house", house.Value);
        Assert.Equal("porch", porch.Value);
    }

    // ---- the CS0121 suspend prefix-overload pair ----

    [Fact]
    public async Task CountAsync_PrefixOverloadPair_NoArgumentsBindsTheShorterOverload()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal(-1, await bell.CountAsync());
        Assert.Equal(70, await bell.CountAsync(7));
    }

    [Fact]
    public async Task CountAsync_NullLimit_ResolvesAsKotlinResolvesCount()
    {
        using var bell = new Dinnerbell(40);

        // `null` means "let Kotlin run the default": the dispatcher's unset arm is `obj.count()`, and
        // Kotlin resolves that call to the real `count()`, never to `count(limit = 3)`'s default
        // (the candidate using no defaults wins). No Kotlin call site can reach that default while
        // `count()` exists, so C# gets exactly what a Kotlin caller of `count()` gets.
        Assert.Equal(-1, await bell.CountAsync(null));
    }

    [Fact]
    public void CountAsync_PrefixOverloadPair_DefaultedLimitStaysRequiredButNullable()
    {
        ParameterInfo limit = typeof(Dinnerbell).GetMethods()
            .Where(m => m.Name == nameof(Dinnerbell.CountAsync))
            .SelectMany(m => m.GetParameters())
            .Single(p => p.Name == "limit");

        Assert.Equal(typeof(int?), limit.ParameterType);
        Assert.False(limit.IsOptional);
    }

    // ---- ADR-164 rule 5, and the dispatcher's derived names beside a default ----

    [Fact]
    public async Task RationAsync_DefaultBeforeARequiredMask_NullRunsTheDefaultAndMaskArrives()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal("40|9", await bell.RationAsync(null, 9));
        Assert.Equal("3|9", await bell.RationAsync(portion: 3, mask: 9));
    }

    [Fact]
    public void RationAsync_DefaultBeforeARequiredParameter_IsRequiredButNullable()
    {
        ParameterInfo portion = typeof(Dinnerbell).GetMethod(nameof(Dinnerbell.RationAsync))!.GetParameters()[0];

        Assert.Equal(typeof(int?), portion.ParameterType);
        Assert.False(portion.IsOptional);
    }

    [Fact]
    public async Task SprinkleAsync_UserMaskBesideAnOmittedDefault_KeepsTheCallersMask()
    {
        using var bell = new Dinnerbell(40);

        // A shadowed `mask` would read the dispatch bitmask (0 unset, 1 set), never 9.
        Assert.Equal("9|40", await bell.SprinkleAsync(9));
        Assert.Equal("9|2", await bell.SprinkleAsync(mask: 9, pinch: 2));
    }

    [Fact]
    public async Task PourAsync_UserDefaultLocalName_KeepsTheCallersValue()
    {
        using var bell = new Dinnerbell(40);

        // A shadowed `default_grams` would read `grams`' resolved value instead of 7.
        Assert.Equal("7|40", await bell.PourAsync(default_grams: 7));
        Assert.Equal("7|3", await bell.PourAsync(default_grams: 7, grams: 3));
    }

    [Fact]
    public async Task Drip_FlowUserMaskBesideAnOmittedDefault_KeepsTheCallersMask()
    {
        using var bell = new Dinnerbell(40);
        var drops = new List<string>();

        await foreach (string drop in bell.Drip(9))
            drops.Add(drop);
        await foreach (string drop in bell.Drip(mask: 9, drops: 2))
            drops.Add(drop);

        Assert.Equal(new List<string> { "9|40", "9|2" }, drops);
    }

    // ---- enum parameters on the legacy routes (crossed by ordinal, ADR-164 widening included) ----

    [Fact]
    public async Task BegAsync_EnumDefaultsOmitted_KotlinEvaluatesThem()
    {
        using var hungry = new Dinnerbell(10);
        using var fed = new Dinnerbell(40);

        // hunger defaults from the receiver's bowl, fallback: Hunger? = STARVING.
        Assert.Equal("PECKISH|STARVING|STARVING", await hungry.BegAsync(Hunger.Peckish));
        Assert.Equal("STARVING|PECKISH|STARVING", await fed.BegAsync(Hunger.Starving));
    }

    [Fact]
    public async Task BegAsync_EnumNamedSkipAndNullableSetToNull_OnlyThoseAreSet()
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal("PECKISH|PECKISH|null", await bell.BegAsync(Hunger.Peckish, fallback: null));
        Assert.Equal("PECKISH|STARVING|PECKISH", await bell.BegAsync(Hunger.Peckish, Hunger.Starving, Hunger.Peckish));
    }

    [Fact]
    public async Task Meows_FlowEnumDefaultOmitted_KotlinEvaluatesIt()
    {
        using var bell = new Dinnerbell(10);
        var heard = new List<string>();

        await foreach (string meow in bell.Meows(Hunger.Peckish))
            heard.Add(meow);
        await foreach (string meow in bell.Meows(Hunger.Peckish, Hunger.Peckish))
            heard.Add(meow);

        Assert.Equal(new List<string> { "PECKISH|STARVING", "PECKISH|PECKISH" }, heard);
    }

    [Fact]
    public async Task BegAtTheTableAsync_TopLevelEnumDefaultsOmitted_ComputedFromTheName()
    {
        Assert.Equal("Oreo|STARVING|STARVING", await SuppertimeSample.BegAtTheTableAsync("Oreo"));
        Assert.Equal("Tom|PECKISH|null", await SuppertimeSample.BegAtTheTableAsync("Tom", fallback: null));
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    public async Task WeighAsync_UserHasValueAndIsSetNames_KeepTheirOwnArguments(bool gramsHasValue, bool treatsIsSet)
    {
        using var bell = new Dinnerbell(40);

        Assert.Equal(
            $"{gramsHasValue.ToString().ToLowerInvariant()}|{treatsIsSet.ToString().ToLowerInvariant()}|40|5",
            await bell.WeighAsync(gramsHasValue: gramsHasValue, treatsIsSet: treatsIsSet));
        Assert.Equal(
            $"{gramsHasValue.ToString().ToLowerInvariant()}|{treatsIsSet.ToString().ToLowerInvariant()}|3|null",
            await bell.WeighAsync(gramsHasValue, treatsIsSet, grams: 3, treats: null));
    }
}

using TestLibrary.Cheersquad;

namespace IntegrationTests;

/// <summary>
/// ADR-239: a callback RESULT beyond <c>Unit</c>, the primitives, <c>String</c> and a class handle.
/// An enum, <c>Char</c>, <c>Instant</c>, <c>Duration</c>, <c>Uuid</c>, a <c>List</c>, a <c>Set</c> and a
/// <c>Map</c>, and a nullable of each, are returned by a C# delegate in their usual C# spelling
/// (<c>Spirit</c>, <c>char</c>, <c>DateTimeOffset</c>, <c>TimeSpan</c>, <c>Guid</c>,
/// <c>IReadOnlyList&lt;string&gt;</c>, <c>IReadOnlySet&lt;int&gt;</c>,
/// <c>IReadOnlyDictionary&lt;string, int&gt;</c>) and Kotlin reads the value back.
///
/// Three routes per result, one member each so one refusal cannot hide another: a per-call lambda
/// parameter (<c>Spirit(pick)</c>), a <c>suspend</c> lambda parameter (<c>SpiritLaterAsync(pick)</c>)
/// and a lambda-typed <c>var</c> C# sets and Kotlin then calls (<c>OnSpirit</c>, read back by
/// <c>ReadSpirit()</c>); the <c>var</c>'s Kotlin default also crosses the result the other way. Kotlin
/// describes what it read as a <c>String</c>, so a delivered-but-wrong value fails here. Mylo judges
/// the routine; Oreo performs it.
/// </summary>
public class CallbackResultVocabularyTests
{
    private static Scorecard Mylo() => new("Mylo");

    // --- Enum ------------------------------------------------------------------------------

    [Fact]
    public async Task Enum_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo saw HYPED", card.Spirit(_ => Spirit.Hyped));
        Assert.Equal("Mylo saw CALM", card.MaybeSpirit(_ => Spirit.Calm));
        Assert.Equal("Mylo saw nothing", card.MaybeSpirit(_ => null));
        Assert.Equal("Mylo saw HYPED", await card.SpiritLaterAsync(async (_, token) =>
        {
            await Task.Delay(1, token);
            return Spirit.Hyped;
        }));
        Assert.Equal("Mylo saw nothing", await card.MaybeSpiritLaterAsync((_, _) => Task.FromResult<Spirit?>(null)));
        Assert.Equal("Mylo saw CALM", await card.MaybeSpiritLaterAsync((_, _) => Task.FromResult<Spirit?>(Spirit.Calm)));
    }

    [Fact]
    public void Enum_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, Spirit> kotlinDefault = card.OnSpirit;
        Assert.Equal(Spirit.Calm, kotlinDefault.Invoke("Oreo"));
        card.OnSpirit = who => who == "Mylo" ? Spirit.Hyped : Spirit.Calm;
        Assert.Equal("Mylo saw HYPED", card.ReadSpirit());

        Assert.Null(card.OnMaybeSpirit.Invoke("Oreo"));
        Assert.Equal("Mylo saw nothing", card.ReadMaybeSpirit());
        card.OnMaybeSpirit = _ => Spirit.Hyped;
        Assert.Equal("Mylo saw HYPED", card.ReadMaybeSpirit());
        card.OnMaybeSpirit = _ => null;
        Assert.Equal("Mylo saw nothing", card.ReadMaybeSpirit());
    }

    // --- Char ------------------------------------------------------------------------------

    [Fact]
    public async Task Char_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo wrote O", card.Initial(_ => 'O'));
        Assert.Equal("Mylo wrote M", card.MaybeInitial(who => who[0]));
        Assert.Equal("Mylo wrote nothing", card.MaybeInitial(_ => null));
        Assert.Equal("Mylo wrote é", await card.InitialLaterAsync((_, _) => Task.FromResult('é')));
        Assert.Equal("Mylo wrote nothing", await card.MaybeInitialLaterAsync((_, _) => Task.FromResult<char?>(null)));
        Assert.Equal("Mylo wrote Z", await card.MaybeInitialLaterAsync((_, _) => Task.FromResult<char?>('Z')));
    }

    [Fact]
    public void Char_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, char> kotlinDefault = card.OnInitial;
        Assert.Equal('O', kotlinDefault.Invoke("Oreo"));
        card.OnInitial = _ => 'Q';
        Assert.Equal("Mylo wrote Q", card.ReadInitial());

        Assert.Null(card.OnMaybeInitial.Invoke("Oreo"));
        card.OnMaybeInitial = who => who[^1];
        Assert.Equal("Mylo wrote o", card.ReadMaybeInitial());
        card.OnMaybeInitial = _ => null;
        Assert.Equal("Mylo wrote nothing", card.ReadMaybeInitial());
    }

    // --- Instant ---------------------------------------------------------------------------

    private static readonly DateTimeOffset Dinner = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_123);

    [Fact]
    public async Task Instant_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo stamped 1700000000123", card.Stamp(_ => Dinner));
        Assert.Equal("Mylo stamped 1700000000123", card.MaybeStamp(_ => Dinner));
        Assert.Equal("Mylo stamped nothing", card.MaybeStamp(_ => null));
        Assert.Equal("Mylo stamped 1700000000123", await card.StampLaterAsync((_, _) => Task.FromResult(Dinner)));
        Assert.Equal("Mylo stamped nothing", await card.MaybeStampLaterAsync((_, _) => Task.FromResult<DateTimeOffset?>(null)));
        Assert.Equal("Mylo stamped 1700000000123", await card.MaybeStampLaterAsync((_, _) => Task.FromResult<DateTimeOffset?>(Dinner)));
    }

    [Fact]
    public void Instant_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, DateTimeOffset> kotlinDefault = card.OnStamp;
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1_000), kotlinDefault.Invoke("Oreo"));
        card.OnStamp = _ => Dinner;
        Assert.Equal("Mylo stamped 1700000000123", card.ReadStamp());

        Assert.Null(card.OnMaybeStamp.Invoke("Oreo"));
        card.OnMaybeStamp = _ => Dinner;
        Assert.Equal("Mylo stamped 1700000000123", card.ReadMaybeStamp());
        card.OnMaybeStamp = _ => null;
        Assert.Equal("Mylo stamped nothing", card.ReadMaybeStamp());
    }

    // --- Duration --------------------------------------------------------------------------

    [Fact]
    public async Task Duration_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo timed 1500 ms", card.Timing(_ => TimeSpan.FromMilliseconds(1500)));
        Assert.Equal("Mylo timed 20 ms", card.MaybeTiming(_ => TimeSpan.FromMilliseconds(20)));
        Assert.Equal("Mylo timed nothing", card.MaybeTiming(_ => null));
        Assert.Equal("Mylo timed 3000 ms", await card.TimingLaterAsync((_, _) => Task.FromResult(TimeSpan.FromSeconds(3))));
        Assert.Equal("Mylo timed nothing", await card.MaybeTimingLaterAsync((_, _) => Task.FromResult<TimeSpan?>(null)));
        Assert.Equal("Mylo timed 7 ms", await card.MaybeTimingLaterAsync((_, _) => Task.FromResult<TimeSpan?>(TimeSpan.FromMilliseconds(7))));
    }

    [Fact]
    public void Duration_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, TimeSpan> kotlinDefault = card.OnTiming;
        Assert.Equal(TimeSpan.FromMilliseconds(250), kotlinDefault.Invoke("Oreo"));
        card.OnTiming = _ => TimeSpan.FromMinutes(1);
        Assert.Equal("Mylo timed 60000 ms", card.ReadTiming());

        Assert.Null(card.OnMaybeTiming.Invoke("Oreo"));
        card.OnMaybeTiming = _ => TimeSpan.FromMilliseconds(9);
        Assert.Equal("Mylo timed 9 ms", card.ReadMaybeTiming());
        card.OnMaybeTiming = _ => null;
        Assert.Equal("Mylo timed nothing", card.ReadMaybeTiming());
    }

    // --- Uuid ------------------------------------------------------------------------------

    private static readonly Guid Ticket = Guid.Parse("0c0ffee0-0a1e-4c0a-b0b0-0ff1ceb0bade");

    [Fact]
    public async Task Uuid_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo issued 0c0ffee0-0a1e-4c0a-b0b0-0ff1ceb0bade", card.Ticket(_ => Ticket));
        Assert.Equal("Mylo issued 0c0ffee0-0a1e-4c0a-b0b0-0ff1ceb0bade", card.MaybeTicket(_ => Ticket));
        Assert.Equal("Mylo issued nothing", card.MaybeTicket(_ => null));
        Assert.Equal("Mylo issued 00000000-0000-0000-0000-000000000000", await card.TicketLaterAsync((_, _) => Task.FromResult(Guid.Empty)));
        Assert.Equal("Mylo issued nothing", await card.MaybeTicketLaterAsync((_, _) => Task.FromResult<Guid?>(null)));
        Assert.Equal("Mylo issued 0c0ffee0-0a1e-4c0a-b0b0-0ff1ceb0bade", await card.MaybeTicketLaterAsync((_, _) => Task.FromResult<Guid?>(Ticket)));
    }

    [Fact]
    public void Uuid_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, Guid> kotlinDefault = card.OnTicket;
        Assert.Equal(Guid.Parse("feedface-0a1e-4c0a-b0b0-0ff1ceb0bade"), kotlinDefault.Invoke("Oreo"));
        card.OnTicket = _ => Ticket;
        Assert.Equal("Mylo issued 0c0ffee0-0a1e-4c0a-b0b0-0ff1ceb0bade", card.ReadTicket());

        Assert.Null(card.OnMaybeTicket.Invoke("Oreo"));
        card.OnMaybeTicket = _ => Ticket;
        Assert.Equal("Mylo issued 0c0ffee0-0a1e-4c0a-b0b0-0ff1ceb0bade", card.ReadMaybeTicket());
        card.OnMaybeTicket = _ => null;
        Assert.Equal("Mylo issued nothing", card.ReadMaybeTicket());
    }

    // --- List ------------------------------------------------------------------------------

    [Fact]
    public async Task List_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo called Oreo, Mylo", card.Lineup(_ => new List<string> { "Oreo", "Mylo" }));
        Assert.Equal("Mylo called ", card.Lineup(_ => new List<string>()));
        Assert.Equal("Mylo called Oreo", card.MaybeLineup(_ => new List<string> { "Oreo" }));
        Assert.Equal("Mylo called nothing", card.MaybeLineup(_ => null));
        Assert.Equal("Mylo called Mylo, Oreo", await card.LineupLaterAsync((_, _) =>
            Task.FromResult<IReadOnlyList<string>>(new List<string> { "Mylo", "Oreo" })));
        Assert.Equal("Mylo called nothing", await card.MaybeLineupLaterAsync((_, _) =>
            Task.FromResult<IReadOnlyList<string>?>(null)));
    }

    [Fact]
    public void List_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, IReadOnlyList<string>> kotlinDefault = card.OnLineup;
        Assert.Equal(["Mylo", "Oreo"], kotlinDefault.Invoke("Mylo"));
        card.OnLineup = who => new List<string> { who, who };
        Assert.Equal("Mylo called Mylo, Mylo", card.ReadLineup());

        Assert.Null(card.OnMaybeLineup.Invoke("Oreo"));
        card.OnMaybeLineup = _ => new List<string> { "Oreo" };
        Assert.Equal("Mylo called Oreo", card.ReadMaybeLineup());
        card.OnMaybeLineup = _ => null;
        Assert.Equal("Mylo called nothing", card.ReadMaybeLineup());
    }

    // --- Set -------------------------------------------------------------------------------

    [Fact]
    public async Task Set_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo counted 1, 2, 3", card.Numbers(_ => new HashSet<int> { 3, 1, 2 }));
        Assert.Equal("Mylo counted 9", card.MaybeNumbers(_ => new HashSet<int> { 9 }));
        Assert.Equal("Mylo counted nothing", card.MaybeNumbers(_ => null));
        Assert.Equal("Mylo counted 4, 5", await card.NumbersLaterAsync((_, _) =>
            Task.FromResult<IReadOnlySet<int>>(new HashSet<int> { 5, 4 })));
        Assert.Equal("Mylo counted nothing", await card.MaybeNumbersLaterAsync((_, _) =>
            Task.FromResult<IReadOnlySet<int>?>(null)));
    }

    [Fact]
    public void Set_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, IReadOnlySet<int>> kotlinDefault = card.OnNumbers;
        Assert.True(kotlinDefault.Invoke("Oreo").SetEquals([1, 3]));
        card.OnNumbers = _ => new HashSet<int> { 7, 6 };
        Assert.Equal("Mylo counted 6, 7", card.ReadNumbers());

        Assert.Null(card.OnMaybeNumbers.Invoke("Oreo"));
        card.OnMaybeNumbers = _ => new HashSet<int> { 2 };
        Assert.Equal("Mylo counted 2", card.ReadMaybeNumbers());
        card.OnMaybeNumbers = _ => null;
        Assert.Equal("Mylo counted nothing", card.ReadMaybeNumbers());
    }

    // --- Map -------------------------------------------------------------------------------

    private static Dictionary<string, int> Scores() => new() { ["Oreo"] = 9, ["Mylo"] = 7 };

    [Fact]
    public async Task Map_EveryRoute()
    {
        using Scorecard card = Mylo();

        Assert.Equal("Mylo tallied Mylo=7, Oreo=9", card.Tally(_ => Scores()));
        Assert.Equal("Mylo tallied Mylo=7, Oreo=9", card.MaybeTally(_ => Scores()));
        Assert.Equal("Mylo tallied nothing", card.MaybeTally(_ => null));
        Assert.Equal("Mylo tallied Mylo=7, Oreo=9", await card.TallyLaterAsync((_, _) =>
            Task.FromResult<IReadOnlyDictionary<string, int>>(Scores())));
        Assert.Equal("Mylo tallied nothing", await card.MaybeTallyLaterAsync((_, _) =>
            Task.FromResult<IReadOnlyDictionary<string, int>?>(null)));
    }

    [Fact]
    public void Map_PropertyRoute()
    {
        using Scorecard card = Mylo();

        Func<string, IReadOnlyDictionary<string, int>> kotlinDefault = card.OnTally;
        Assert.Equal(9, kotlinDefault.Invoke("Oreo")["Oreo"]);
        card.OnTally = who => new Dictionary<string, int> { [who] = 10 };
        Assert.Equal("Mylo tallied Mylo=10", card.ReadTally());

        Assert.Null(card.OnMaybeTally.Invoke("Oreo"));
        card.OnMaybeTally = _ => Scores();
        Assert.Equal("Mylo tallied Mylo=7, Oreo=9", card.ReadMaybeTally());
        card.OnMaybeTally = _ => null;
        Assert.Equal("Mylo tallied nothing", card.ReadMaybeTally());
    }

    // --- The stored add/remove pair ---------------------------------------------------------

    [Fact]
    public void Enum_StoredPairRoute()
    {
        using Scorecard card = Mylo();

        using (IDisposable hyped = card.AddJudge(_ => Spirit.Hyped))
        using (IDisposable calm = card.AddJudge(who => who == "Mylo" ? Spirit.Calm : Spirit.Hyped))
        {
            Assert.Equal("HYPED, CALM", card.Verdicts());
        }
        Assert.Equal("", card.Verdicts());
    }

    // --- The top-level owner of the per-call route ------------------------------------------

    [Fact]
    public void TopLevel_PerCallNullableResults()
    {
        Assert.Equal("Oreo is HYPED", ScorecardKt.JudgeSpirit("Oreo", _ => Spirit.Hyped));
        Assert.Equal("Oreo is unjudged", ScorecardKt.JudgeSpirit("Oreo", _ => null));
        Assert.Equal("Mylo signs M", ScorecardKt.JudgeInitial("Mylo", who => who[0]));
        Assert.Equal("Mylo signs nothing", ScorecardKt.JudgeInitial("Mylo", _ => null));
        Assert.Equal("Oreo leads Mylo, Oreo", ScorecardKt.JudgeLineup("Oreo", who => new List<string> { "Mylo", who }));
        Assert.Equal("Oreo leads nobody", ScorecardKt.JudgeLineup("Oreo", _ => null));
    }
}

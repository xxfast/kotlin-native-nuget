using System.Reflection;
using TestLibrary.Cat;
using TestLibrary.Issue365;

namespace IntegrationTests;

// Issue #365: a nullable class or sealed handle parameter on a legacy route (suspend member,
// sealed-arm suspend member, top-level suspend, Flow/StateFlow member) was skipped whole as
// SKIPPED_UNSUPPORTED_INPUT, so none of the members below existed in C#.
//
// After the fix the public spelling is `Cat?` / `Observation?`, `null` crosses as IntPtr.Zero on
// the one handle slot (no HasValue slot), and Kotlin rebuilds it as `null`.
//
// Every fixture spells what Kotlin received: "none" / "unobserved" for null, and a field read
// through the handle otherwise, so a null that arrived as a default instance, or a handle that
// arrived as a bare number, cannot pass. Each route carries both a plain class (`Cat`) and a
// sealed base (`Observation`), because they reach the refusal by different classifier paths.
//
// Expected red state before the fix: this file fails to compile (CS0246 / CS1061, the members are
// absent).
public class LegacyRouteNullableHandleParameterTests
{
    // --- suspend member ---

    [Fact]
    public async Task Examine_Null_ReachesKotlinAsNull()
    {
        // Mylo skipped his appointment. The room still has to arrive beside the null.
        using var checkup = new Checkup("Dr Purr");
        Assert.Equal("Dr Purr in room 2: none", await checkup.ExamineAsync("room 2", null));
    }

    [Fact]
    public async Task Examine_Value_ReachesKotlin()
    {
        using var checkup = new Checkup("Dr Purr");
        using var oreo = new Cat("Oreo");
        Assert.Equal("Dr Purr in room 2: Oreo", await checkup.ExamineAsync("room 2", oreo));
    }

    [Fact]
    public async Task Triage_Null_ReachesKotlinAsNull()
    {
        using var checkup = new Checkup("Dr Purr");
        Assert.Equal("Dr Purr triage: unobserved", await checkup.TriageAsync(null));
    }

    [Fact]
    public async Task Triage_Value_ReachesKotlin()
    {
        // The sealed base crosses as a handle and Kotlin discriminates: alive, with Oreo inside.
        using var checkup = new Checkup("Dr Purr");
        using Observation observation = ObservationKt.OpenBox("Oreo");
        Assert.Equal("Dr Purr triage: alive:Oreo", await checkup.TriageAsync(observation));
    }

    // --- sealed-arm suspend member (ADR-118) ---

    [Fact]
    public async Task Admit_Null_ReachesKotlinAsNull()
    {
        using var stay = new Stay.Overnight(3);
        Assert.Equal("night 3: none", await stay.AdmitAsync(null));
    }

    [Fact]
    public async Task Admit_Value_ReachesKotlin()
    {
        using var stay = new Stay.Overnight(3);
        using var mylo = new Cat("Mylo");
        Assert.Equal("night 3: Mylo", await stay.AdmitAsync(mylo));
    }

    [Fact]
    public async Task Observe_Null_ReachesKotlinAsNull()
    {
        using var stay = new Stay.Overnight(4);
        Assert.Equal("night 4 observed: unobserved", await stay.ObserveAsync(null));
    }

    [Fact]
    public async Task Observe_Value_ReachesKotlin()
    {
        // Nobody found Mylo in the box, which is its own answer, and not the same one as null.
        using var stay = new Stay.Overnight(4);
        using Observation observation = ObservationKt.OpenBox("Mylo");
        Assert.Equal("night 4 observed: dead:The cat was not Mylo", await stay.ObserveAsync(observation));
    }

    // --- top-level suspend ---

    [Fact]
    public async Task Weigh_Null_ReachesKotlinAsNull()
    {
        Assert.Equal("weighed: none", await CheckupKt.WeighAsync(null));
    }

    [Fact]
    public async Task Weigh_Value_ReachesKotlin()
    {
        using var oreo = new Cat("Oreo");
        Assert.Equal("weighed: Oreo", await CheckupKt.WeighAsync(oreo));
    }

    [Fact]
    public async Task Recheck_Null_ReachesKotlinAsNull()
    {
        Assert.Equal("rechecked: unobserved", await CheckupKt.RecheckAsync(null));
    }

    [Fact]
    public async Task Recheck_Value_ReachesKotlin()
    {
        using Observation observation = ObservationKt.PeekBox();
        Assert.Equal("rechecked: superposition", await CheckupKt.RecheckAsync(observation));
    }

    // --- Flow member ---

    [Fact]
    public async Task Rounds_Null_FlowReceivesNull()
    {
        using var checkup = new Checkup("Dr Whiskers");
        Assert.Equal("Dr Whiskers rounds: none", await FirstAsync(checkup.Rounds(null)));
    }

    [Fact]
    public async Task Rounds_Value_FlowReceivesValue()
    {
        using var checkup = new Checkup("Dr Whiskers");
        using var mylo = new Cat("Mylo");
        Assert.Equal("Dr Whiskers rounds: Mylo", await FirstAsync(checkup.Rounds(mylo)));
    }

    // --- StateFlow member ---

    [Fact]
    public void Chart_Null_StateFlowReceivesNull()
    {
        using var checkup = new Checkup("Dr Whiskers");
        using var chart = checkup.Chart(null);
        Assert.Equal("Dr Whiskers chart: unobserved", chart.Value);
    }

    [Fact]
    public void Chart_Value_StateFlowReceivesValue()
    {
        using var checkup = new Checkup("Dr Whiskers");
        using Observation observation = ObservationKt.OpenBox("Oreo");
        using var chart = checkup.Chart(observation);
        Assert.Equal("Dr Whiskers chart: alive:Oreo", chart.Value);
    }

    // --- suspend member returning StateFlow (ADR-068) ---

    [Fact]
    public async Task AwaitChart_Null_ReachesKotlinAsNull()
    {
        using var checkup = new Checkup("Dr Purr");
        using var chart = await checkup.AwaitChartAsync(null);
        Assert.Equal("Dr Purr awaited chart: none", chart.Value);
    }

    [Fact]
    public async Task AwaitChart_Value_ReachesKotlin()
    {
        using var checkup = new Checkup("Dr Purr");
        using var oreo = new Cat("Oreo");
        using var chart = await checkup.AwaitChartAsync(oreo);
        Assert.Equal("Dr Purr awaited chart: Oreo", chart.Value);
    }

    // --- the public C# surface itself ---

    [Theory]
    [InlineData(typeof(Checkup), nameof(Checkup.ExamineAsync), "cat", typeof(Cat))]
    [InlineData(typeof(Checkup), nameof(Checkup.TriageAsync), "observation", typeof(Observation))]
    [InlineData(typeof(Stay.Overnight), nameof(Stay.Overnight.AdmitAsync), "cat", typeof(Cat))]
    [InlineData(typeof(Stay.Overnight), nameof(Stay.Overnight.ObserveAsync), "observation", typeof(Observation))]
    [InlineData(typeof(CheckupKt), nameof(CheckupKt.WeighAsync), "cat", typeof(Cat))]
    [InlineData(typeof(CheckupKt), nameof(CheckupKt.RecheckAsync), "observation", typeof(Observation))]
    [InlineData(typeof(Checkup), nameof(Checkup.Rounds), "cat", typeof(Cat))]
    [InlineData(typeof(Checkup), nameof(Checkup.Chart), "observation", typeof(Observation))]
    [InlineData(typeof(Checkup), nameof(Checkup.AwaitChartAsync), "cat", typeof(Cat))]
    public void NullableHandleParameter_IsAnnotatedNullable(Type owner, string method, string name, Type expected)
    {
        // `Cat` and `Cat?` are the same CLR type, so only the nullable annotation tells them apart.
        // The handle type itself is pinned too: a fix that fell back to IntPtr would fail here.
        ParameterInfo parameter = Parameter(owner, method, name);
        Assert.Equal(expected, parameter.ParameterType);
        NullabilityInfo info = new NullabilityInfoContext().Create(parameter);
        Assert.Equal(NullabilityState.Nullable, info.WriteState);
    }

    [Fact]
    public void Examine_LeadingStringParameter_StaysNonNull()
    {
        // Widening the nullable handle must not drag its non-null neighbour along with it.
        ParameterInfo room = Parameter(typeof(Checkup), nameof(Checkup.ExamineAsync), "room");
        NullabilityInfo info = new NullabilityInfoContext().Create(room);
        Assert.Equal(NullabilityState.NotNull, info.WriteState);
    }

    private static ParameterInfo Parameter(Type owner, string method, string name)
    {
        MethodInfo? info = owner.GetMethod(method, BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static);
        Assert.NotNull(info);
        return Assert.Single(info!.GetParameters(), p => p.Name == name);
    }

    private static async Task<string> FirstAsync(IAsyncEnumerable<string> flow)
    {
        await foreach (string item in flow)
            return item;
        throw new InvalidOperationException("flow completed without emitting");
    }
}

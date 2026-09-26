using System.Reflection;
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

// Issue #299: a nullable scalar or String parameter on a legacy route (suspend member, top-level
// suspend, Flow/StateFlow member, suspend-returning-StateFlow member) was bound as non-null in C#
// (`int limit`, `string name`), so a C# caller could not pass null at all.
//
// After the fix (ADR-122 amendment) the public spelling is `int?` / `char?` / `bool?` / `string?`,
// a nullable scalar crosses as the ADR-098 HasValue pair (`limitHasValue`, `limit`), and a nullable
// String crosses as a single possibly-null UTF-8 pointer.
//
// Every fixture returns a String spelling what Kotlin actually received, and every cell is asserted
// with null AND a value. A value that happens to equal the wire default (0, '\0', false, "") would
// hide a HasValue flag that is ignored, which is why the Boolean? theory carries a `false` row.
//
// Expected red state before the fix: this file fails to compile (CS0037, cannot convert null to
// `int` / `char` / `bool`). The String rows compile today with a nullable warning, so the reflection
// rows at the bottom pin the `string?` spelling itself.
public class SuspendNullableParameterTests
{
    // --- suspend class members: Int?, Char?, String? ---

    [Fact]
    public async Task CountNaps_Null_ReachesKotlinAsNull()
    {
        // Oreo naps without limit. Null must not arrive as a default 0.
        using var service = new AsyncCatService("Oreo");
        Assert.Equal("Oreo naps: unlimited", await service.CountNapsAsync(null));
    }

    [Fact]
    public async Task CountNaps_Value_ReachesKotlin()
    {
        using var service = new AsyncCatService("Oreo");
        Assert.Equal("Oreo naps: 3", await service.CountNapsAsync(3));
    }

    [Fact]
    public async Task CountNaps_Zero_IsAValueNotNull()
    {
        // Mylo has been put on a strict no-naps regime. Zero is the wire default of the value slot,
        // so this only passes if the HasValue slot is honoured.
        using var service = new AsyncCatService("Mylo");
        Assert.Equal("Mylo naps: 0", await service.CountNapsAsync(0));
    }

    [Fact]
    public async Task InitialOf_Null_ReachesKotlinAsNull()
    {
        using var service = new AsyncCatService("Mylo");
        Assert.Equal("none", await service.InitialOfAsync(null));
    }

    [Fact]
    public async Task InitialOf_NonAsciiChar_RoundTrips()
    {
        // Mylo's bowl is engraved in a fancy French font. 'é' guards ADR-098's U2 marshalling
        // on the value slot of the Char? pair: a one-byte char would mangle it.
        using var service = new AsyncCatService("Mylo");
        Assert.Equal("é", await service.InitialOfAsync('é'));
    }

    [Fact]
    public async Task InitialOf_AsciiChar_RoundTrips()
    {
        using var service = new AsyncCatService("Oreo");
        Assert.Equal("O", await service.InitialOfAsync('O'));
    }

    [Fact]
    public async Task Greet_Null_ReachesKotlinAsNull()
    {
        // A stranger at the door. Before the fix this handed a null char* to a non-null Kotlin String.
        using var service = new AsyncCatService("Oreo");
        Assert.Equal("Oreo hisses at a stranger", await service.GreetAsync(null));
    }

    [Fact]
    public async Task Greet_Value_ReachesKotlin()
    {
        using var service = new AsyncCatService("Oreo");
        Assert.Equal("Oreo purrs at Mylo", await service.GreetAsync("Mylo"));
    }

    [Fact]
    public async Task Greet_Empty_IsAValueNotNull()
    {
        // An empty name is still a name: "" must not collapse to null on the way through.
        using var service = new AsyncCatService("Mylo");
        Assert.Equal("Mylo purrs at ", await service.GreetAsync(""));
    }

    // --- top-level suspend functions: Boolean?, String? ---

    [Theory]
    [InlineData(null, "unknown")]
    [InlineData(true, "indoor")]
    [InlineData(false, "outdoor")]
    public async Task DescribeIndoor_TopLevel(bool? indoor, string expected)
    {
        // Is Mylo an indoor cat? Nobody is sure, and "no" is not the same answer as "unknown".
        Assert.Equal(expected, await AsyncFunctions.DescribeIndoorAsync(indoor));
    }

    [Fact]
    public async Task NameTagFor_Null_ReachesKotlinAsNull()
    {
        Assert.Equal("tag: blank", await AsyncFunctions.NameTagForAsync(null));
    }

    [Fact]
    public async Task NameTagFor_NonAscii_RoundTrips()
    {
        // Oreo's full name on the tag, accent and all: the String? slot must stay UTF-8.
        Assert.Equal("tag: Oréo", await AsyncFunctions.NameTagForAsync("Oréo"));
    }

    // --- Flow member: Int? ---

    [Fact]
    public async Task Snacks_Null_FlowReceivesNull()
    {
        using var feeder = new CatFeeder("Mylo");
        Assert.Equal("Mylo snacks: unlimited", await FirstAsync(feeder.Snacks(null)));
    }

    [Fact]
    public async Task Snacks_Value_FlowReceivesValue()
    {
        using var feeder = new CatFeeder("Oreo");
        Assert.Equal("Oreo snacks: 2", await FirstAsync(feeder.Snacks(2)));
    }

    // --- StateFlow member: String? ---

    [Fact]
    public void BowlStatus_Null_StateFlowReceivesNull()
    {
        using var feeder = new CatFeeder("Oreo");
        using KotlinStateFlow<string> status = feeder.BowlStatus(null);
        Assert.Equal("Oreo bowl: unlabelled", status.Value);
    }

    [Fact]
    public void BowlStatus_Value_StateFlowReceivesValue()
    {
        using var feeder = new CatFeeder("Mylo");
        using KotlinStateFlow<string> status = feeder.BowlStatus("tuna");
        Assert.Equal("Mylo bowl: tuna", status.Value);
    }

    // --- suspend member returning StateFlow (ADR-068): Char? ---

    [Fact]
    public async Task AwaitBowlInitial_Null_ReachesKotlinAsNull()
    {
        using var feeder = new CatFeeder("Oreo");
        using KotlinStateFlow<string> initial = await feeder.AwaitBowlInitialAsync(null);
        Assert.Equal("Oreo bowl initial: none", initial.Value);
    }

    [Fact]
    public async Task AwaitBowlInitial_NonAsciiChar_RoundTrips()
    {
        using var feeder = new CatFeeder("Mylo");
        using KotlinStateFlow<string> initial = await feeder.AwaitBowlInitialAsync('é');
        Assert.Equal("Mylo bowl initial: é", initial.Value);
    }

    // --- the public C# surface itself ---

    [Theory]
    [InlineData(typeof(AsyncCatService), nameof(AsyncCatService.CountNapsAsync), typeof(int?))]
    [InlineData(typeof(AsyncCatService), nameof(AsyncCatService.InitialOfAsync), typeof(char?))]
    [InlineData(typeof(AsyncFunctions), nameof(AsyncFunctions.DescribeIndoorAsync), typeof(bool?))]
    [InlineData(typeof(CatFeeder), nameof(CatFeeder.Snacks), typeof(int?))]
    [InlineData(typeof(CatFeeder), nameof(CatFeeder.AwaitBowlInitialAsync), typeof(char?))]
    public void NullableScalarParameter_IsNullableValueType(Type owner, string method, Type expected)
    {
        Assert.Equal(expected, FirstParameter(owner, method).ParameterType);
    }

    [Theory]
    [InlineData(typeof(AsyncCatService), nameof(AsyncCatService.GreetAsync))]
    [InlineData(typeof(AsyncFunctions), nameof(AsyncFunctions.NameTagForAsync))]
    [InlineData(typeof(CatFeeder), nameof(CatFeeder.BowlStatus))]
    public void NullableStringParameter_IsAnnotatedNullable(Type owner, string method)
    {
        // `string` and `string?` are the same CLR type, so only the nullable annotation tells them
        // apart. Before the fix this reads NotNull.
        ParameterInfo parameter = FirstParameter(owner, method);
        Assert.Equal(typeof(string), parameter.ParameterType);
        NullabilityInfo info = new NullabilityInfoContext().Create(parameter);
        Assert.Equal(NullabilityState.Nullable, info.WriteState);
    }

    private static ParameterInfo FirstParameter(Type owner, string method)
    {
        MethodInfo? info = owner.GetMethod(method, BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static);
        Assert.NotNull(info);
        return info!.GetParameters()[0];
    }

    private static async Task<string> FirstAsync(IAsyncEnumerable<string> flow)
    {
        await foreach (string item in flow)
            return item;
        throw new InvalidOperationException("flow completed without emitting");
    }
}
